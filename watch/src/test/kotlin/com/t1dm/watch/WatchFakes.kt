package com.t1dm.watch

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus
import com.t1dm.watch.ble.WatchCentral
import com.t1dm.watch.ble.WatchCentralEvent
import com.t1dm.watch.ble.WatchConnection
import com.t1dm.watch.ble.WatchTarget
import com.t1dm.watch.crypto.InMemoryWatchStores
import com.t1dm.watch.crypto.LoopbackWatchSession
import com.t1dm.watch.crypto.LoopbackWatchSessionFactory
import com.t1dm.watch.crypto.NonceStore
import com.t1dm.watch.crypto.WatchDevice
import com.t1dm.watch.crypto.WatchDeviceStore
import com.t1dm.watch.crypto.WatchStores
import com.t1dm.watch.proto.ControlFrame
import com.t1dm.watch.proto.KexFrame
import com.t1dm.watch.proto.WatchCodec
import com.t1dm.watch.proto.WatchDeviceStatus
import com.t1dm.watch.proto.WatchDisplay
import com.t1dm.watch.proto.WatchForecast
import com.t1dm.watch.proto.WatchHistory
import com.t1dm.watch.proto.WatchOutlook
import com.t1dm.watch.proto.WatchPalette
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchStats
import com.t1dm.watch.proto.WatchStatus
import com.t1dm.watch.proto.WatchTrend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout

internal val testDispatchers = object : T1dmDispatchers {
    override val main = Dispatchers.Default
    override val default = Dispatchers.Default
    override val io = Dispatchers.Default
    override val inference = Dispatchers.Default
    override val game = Dispatchers.Default
}

internal val testGlance = WatchPush(
    bgMgdl = 140, trendTenths = 0, bgTrend = WatchTrend.FLAT, bgTrendFitted = false, readingAgeMs = 60_000L,
    alertBand = AlertBand.IN_RANGE, forecastStatus = ForecastStatus.OK,
    fcEndMgdl = 150, fcHorizonSteps = 24, fcTrend = WatchTrend.FLAT,
    summary = "140 flat", status = WatchStatus(), outlook = WatchOutlook(WatchOutlook.State.STABLE),
)

/** FakePeripheral's frames; record byte 0 is the kind, byte 1 indexes [glances]. */
internal class FakeCodec : WatchCodec {
    val glances = mutableListOf<WatchPush>()

    override fun kex(frame: KexFrame): ByteArray = when (frame) {
        is KexFrame.Hello -> byteArrayOf(1, 1, frame.epoch.toByte()) + frame.publicKey
        is KexFrame.Confirm -> byteArrayOf(3, 1, frame.epoch.toByte(), if (frame.ok) 1 else 0)
    }

    override fun control(bytes: ByteArray): ControlFrame? {
        if (bytes.size < 3) return null
        val epoch = bytes[2].toInt() and 0xFF
        return when (bytes[0].toInt() and 0xFF) {
            0x02 -> ControlFrame.HelloAck(epoch, bytes.copyOfRange(3, 35))
            0x04 -> ControlFrame.ConfirmAck(epoch, bytes[3].toInt() != 0)
            0x10 -> ControlFrame.ErrEpoch(epoch)
            0x11 -> ControlFrame.ErrAuth(epoch)
            else -> null
        }
    }

    override fun unpair() = byteArrayOf(6)

    override fun glance(push: WatchPush): ByteArray = synchronized(glances) {
        glances += push
        byteArrayOf(1, (glances.size - 1).toByte())
    }

    override fun outlook(o: WatchOutlook) = byteArrayOf(7, o.state.ordinal.toByte())
    override fun history(h: WatchHistory) = listOf(byteArrayOf(2))
    override fun forecast(f: WatchForecast) = listOf(byteArrayOf(3), byteArrayOf(3))
    override fun stats(s: WatchStats) = byteArrayOf(4)
    override fun display(d: WatchDisplay) = byteArrayOf(5)

    override fun status(bytes: ByteArray): WatchDeviceStatus? {
        if (bytes.size < 11 || bytes[0].toInt() != 1) return null
        val id = bytes.copyOfRange(3, 11).joinToString("") { "%02x".format(it) }
        return WatchDeviceStatus(id, "T1DM-Watch-" + id.take(8), bytes[1].toInt() and 0xFF, bytes[2].toInt() and 1 != 0)
    }
}

internal object FakeSources : WatchExtendedSource {
    override suspend fun history(nowMs: Long, slots: Int) = WatchHistory(0, IntArray(slots), IntArray(slots))
    override suspend fun forecast() =
        WatchForecast(0, 120.0, ForecastStatus.OK, false, false, 5, 7, DoubleArray(0), DoubleArray(0))
    override suspend fun stats() = WatchStats(70, 180, emptyList())
    override suspend fun display() = WatchDisplay(
        true, WatchPalette(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(55, 70, 180, 250), 20, 250, 6, 15, 20, "t",
    )
}

/** A peripheral on the fake air: loopback crypto, answers KEX like firmware would. */
internal class FakePeripheral(var deviceId: ByteArray, val extended: Boolean) {
    /** Set to advertise a name its STATUS does not back. */
    var advertisedName: String? = null
    val name get() = advertisedName ?: ("T1DM-Watch-" + deviceId.copyOf(4).joinToString("") { "%02x".format(it) })
    /** Opens pushes; a handshake's keys replace it only on CONFIRM (SPEC/watch.md §3). */
    @Volatile private var live: LoopbackWatchSession? = null
    @Volatile private var pending: LoopbackWatchSession? = null
    /** Plaintexts as the peripheral opened them. */
    val records = mutableListOf<ByteArray>()
    var central: FakeCentral? = null
    /** False plays a desktop whose user has not opened pairing. */
    @Volatile var answersHello = true

    fun status() = byteArrayOf(1, 0, if (extended) 1 else 0) + deviceId

    suspend fun onKex(bytes: ByteArray, events: MutableSharedFlow<WatchCentralEvent>) {
        val epoch = bytes[2]
        when (bytes[0].toInt() and 0xFF) {
            1 -> if (answersHello) {
                val s = LoopbackWatchSessionFactory().fresh() as LoopbackWatchSession
                val pub = s.startHandshake()
                s.acceptPeer(bytes.copyOfRange(3, 35))
                pending = s
                events.emit(WatchCentralEvent.Notified(byteArrayOf(0x02, 0x01, epoch) + pub))
            }
            3 -> {
                live = checkNotNull(pending) { "CONFIRM without HELLO" }.also { it.confirm() }
                pending = null
                events.emit(WatchCentralEvent.Notified(byteArrayOf(0x04, 0x01, epoch, 0x01)))
            }
        }
    }

    fun onPush(frame: ByteArray) {
        val keys = checkNotNull(live) { "no keys" }
        synchronized(records) { records += keys.emulatorOpenPush(frame) }
    }

    fun kinds(): List<Int> = synchronized(records) { records.map { it[0].toInt() } }

    suspend fun sendControl(bytes: ByteArray) = central!!.bus.emit(WatchCentralEvent.Notified(bytes))
}

internal class FakeAir(vararg ps: FakePeripheral) {
    val peripherals = ps.toMutableList()

    fun find(target: WatchTarget): FakePeripheral? = peripherals.firstOrNull {
        when (target) {
            is WatchTarget.Known -> it.name == target.name
            is WatchTarget.New -> it.name.startsWith(target.prefix) && it.name !in target.exclude
        }
    }
}

internal class FakeCentral(private val air: FakeAir) : WatchCentral {
    val bus = MutableSharedFlow<WatchCentralEvent>(replay = 0, extraBufferCapacity = 64)
    override val events = bus.asSharedFlow()
    override var isReady: Boolean = false; private set
    private var peer: FakePeripheral? = null

    override suspend fun connect(target: WatchTarget, timeoutMs: Long): WatchConnection {
        val p = air.find(target) ?: error("nothing advertises for $target")
        peer = p
        p.central = this
        isReady = true
        return WatchConnection(p.name, 247, "02:00:00:00:00:0" + air.peripherals.indexOf(p))
    }

    /** A peripheral that re-registered its service: STATUS fails, pushes vanish, link stays up. */
    @Volatile var moved = false

    override suspend fun readStatus(): ByteArray? = if (moved) null else peer?.status()
    override suspend fun writeKex(bytes: ByteArray) = peer!!.onKex(bytes, bus)
    override suspend fun writePush(bytes: ByteArray) { if (!moved) peer!!.onPush(bytes) }
    override fun disconnect() { isReady = false }

    suspend fun drop() {
        isReady = false
        bus.emit(WatchCentralEvent.Disconnected("test drop"))
    }
}

/** Pass-through stores whose reads throw on demand, as a Keystore or kv failure would. */
internal class FlakyStores(private val inner: WatchStores = InMemoryWatchStores()) : WatchStores {
    @Volatile var failDevices = false
    @Volatile var failCeiling = false

    override val devices = object : WatchDeviceStore {
        override suspend fun load() = if (failDevices) error("devices unreadable") else inner.devices.load()
        override suspend fun put(device: WatchDevice) = inner.devices.put(device)
        override suspend fun remove(id: String) = inner.devices.remove(id)
    }

    override fun pairing(id: String) = inner.pairing(id)

    override fun nonces(id: String): NonceStore {
        val real = inner.nonces(id)
        return object : NonceStore by real {
            override suspend fun loadCeiling(epoch: Int) =
                if (failCeiling) error("ceiling unreadable") else real.loadCeiling(epoch)
        }
    }
}

internal suspend fun <T> awaitValue(timeoutMs: Long = 5_000L, read: () -> T?): T =
    withTimeout(timeoutMs) {
        var v = read()
        while (v == null) { delay(20); v = read() }
        v
    }
