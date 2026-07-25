package com.t1dm.watch

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus
import com.t1dm.watch.ble.WatchCentral
import com.t1dm.watch.ble.WatchCentralEvent
import com.t1dm.watch.crypto.InMemoryNonceStore
import com.t1dm.watch.crypto.InMemoryWatchPairingStore
import com.t1dm.watch.crypto.LoopbackWatchSession
import com.t1dm.watch.crypto.LoopbackWatchSessionFactory
import com.t1dm.watch.proto.KexFrame
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchPushCodec
import com.t1dm.watch.proto.WatchStatus
import com.t1dm.watch.proto.WatchTrend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The locked low-power rule (watch-link.md): battery-saver SUSPENDS the 5-min push — the link seals
 * ONE final frame with the LOW_POWER status bit set, flips to SUSPENDED_LOW_POWER, then idles (a
 * later tick writes nothing). Drives the REAL [WatchLink.pushNow] against a fake central that plays
 * the ESP32-C3 side of the handshake and decrypts the captured pushes.
 */
class LowPowerSuspendTest {

    private val dispatchers = object : T1dmDispatchers {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.Default
        override val inference = Dispatchers.Default
        override val game = Dispatchers.Default
    }

    private class FakeCentral : WatchCentral {
        private val _events = MutableSharedFlow<WatchCentralEvent>(replay = 0, extraBufferCapacity = 64)
        override val events = _events.asSharedFlow()
        override var isReady: Boolean = false; private set
        val pushes = mutableListOf<ByteArray>()
        val watch = LoopbackWatchSessionFactory().fresh() as LoopbackWatchSession

        override suspend fun connectByName(namePrefix: String, timeoutMs: Long) {
            isReady = true
            _events.emit(WatchCentralEvent.Ready("T1DM-Watch-fake", 247))
        }
        override suspend fun readStatus(): ByteArray = byteArrayOf(0x01, 0x00)
        override suspend fun writeKex(bytes: ByteArray) {
            when (bytes[0].toInt() and 0xFF) {
                KexFrame.TYPE_HELLO -> {
                    val epoch = bytes[2].toInt() and 0xFF
                    val phonePub = bytes.copyOfRange(3, 35)
                    val watchPub = watch.startHandshake()
                    watch.acceptPeer(phonePub)
                    _events.emit(WatchCentralEvent.Notified(byteArrayOf(0x02, 0x01, epoch.toByte()) + watchPub))
                }
                KexFrame.TYPE_CONFIRM -> {
                    val epoch = bytes[2].toInt() and 0xFF
                    watch.confirm()
                    _events.emit(WatchCentralEvent.Notified(byteArrayOf(0x04, 0x01, epoch.toByte(), 0x01)))
                }
            }
        }
        override suspend fun writePush(bytes: ByteArray) { pushes.add(bytes) }
        override fun disconnect() { isReady = false }

        /** Decrypt a captured wire push exactly as the watch firmware would, returning the glance. The
         *  wire bytes ARE the authoritative record (§6.1); the watch opens it whole. */
        fun open(wire: ByteArray): WatchPush =
            WatchPushCodec.decode(watch.emulatorOpenPush(wire))
    }

    private val glance = WatchPush(
        bgMgdl = 140, trendTenths = 0, readingAgeMs = 60_000L,
        alertBand = AlertBand.IN_RANGE, forecastStatus = ForecastStatus.OK,
        fcEndMgdl = 150, fcHorizonSteps = 24, fcTrend = WatchTrend.FLAT,
        summary = "140 flat", status = WatchStatus(),
    )

    @Test fun `low power sends one flagged frame then suspends the pusher`() = runBlocking<Unit> {
        val central = FakeCentral()
        var low = false
        val link = WatchLink(
            centralProvider = { central },
            sessionFactory = LoopbackWatchSessionFactory(),
            nonceStore = InMemoryNonceStore(),
            pairingStore = InMemoryWatchPairingStore(),
            glanceSource = { glance },
            lowPower = { low },
            dispatchers = dispatchers,
            config = WatchLinkConfig(enabled = true, autoConnect = false),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        link.start(scope)

        link.beginPairing()
        await(link) { it.phase == WatchLinkPhase.AWAIT_SAS }
        link.confirmSas()
        await(link) { it.phase == WatchLinkPhase.LIVE }

        // 1) normal push — LOW_POWER bit clear, glance intact
        link.pushNow(1_000L)
        await(link) { it.lastPushMs == 1_000L }
        assertEquals(1, central.pushes.size)
        val g1 = central.open(central.pushes[0])
        assertFalse("normal frame must not set LOW_POWER", g1.status.lowPowerSuspending)
        assertEquals(140, g1.bgMgdl)

        // 2) battery saver on — exactly ONE more (flagged) frame, phase → SUSPENDED_LOW_POWER
        low = true
        link.pushNow(2_000L)
        await(link) { it.phase == WatchLinkPhase.SUSPENDED_LOW_POWER }
        assertEquals(2, central.pushes.size)
        assertTrue("final low-power frame must set LOW_POWER bit", central.open(central.pushes[1]).status.lowPowerSuspending)

        // 3) still suspended — a subsequent tick writes NOTHING (pusher idle)
        link.pushNow(3_000L)
        delay(300)
        assertEquals("pusher must stay idle while suspended", 2, central.pushes.size)

        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun await(link: WatchLink, cond: (WatchSecurityState) -> Boolean) {
        withTimeout(5_000L) { while (!cond(link.state.value)) delay(20) }
    }
}
