package com.t1dm.watch

import com.t1dm.watch.crypto.InMemoryWatchStores
import com.t1dm.watch.crypto.LoopbackWatchSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowPowerSuspendTest {

    @Test fun `low power sends one flagged frame then suspends the pusher`() = runBlocking<Unit> {
        val watch = FakePeripheral(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), extended = false)
        val air = FakeAir(watch)
        val codec = FakeCodec()
        var low = false
        val hub = WatchHub(
            centralProvider = { FakeCentral(air) },
            sessionFactory = LoopbackWatchSessionFactory(),
            stores = InMemoryWatchStores(),
            codec = codec,
            glanceSource = { testGlance },
            extendedSource = FakeSources,
            lowPower = { low },
            dispatchers = testDispatchers,
            config = WatchLinkConfig(enabled = true, autoConnect = false),
        )
        val scope = CoroutineScope(SupervisorJob() + testDispatchers.default)
        hub.start(scope)

        hub.beginPairing()
        awaitValue { hub.pairing.value?.takeIf { it.phase == WatchLinkPhase.AWAIT_SAS } }
        hub.confirmSas(null)
        awaitValue { hub.devices.value.singleOrNull()?.takeIf { it.lastPushMs != null } }
        assertEquals("a plain watch takes the glance only", listOf(1), watch.kinds())

        hub.tick(1_000L)
        awaitValue { hub.devices.value.single().takeIf { it.lastPushMs == 1_000L } }
        assertEquals(2, watch.records.size)
        assertFalse("normal frame must not set LOW_POWER", codec.glances[watch.records[1][1].toInt()].status.lowPowerSuspending)

        low = true
        hub.tick(2_000L)
        awaitValue { hub.devices.value.single().takeIf { it.phase == WatchLinkPhase.SUSPENDED_LOW_POWER } }
        assertEquals(3, watch.records.size)
        assertTrue(
            "final low-power frame must set LOW_POWER bit",
            codec.glances[watch.records[2][1].toInt()].status.lowPowerSuspending,
        )

        hub.tick(3_000L)
        delay(300)
        assertEquals("pusher must stay idle while suspended", 3, watch.records.size)

        scope.coroutineContext[Job]?.cancel()
    }
}
