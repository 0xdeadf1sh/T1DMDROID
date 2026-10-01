package com.t1dm.cgm

import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/** Live frames stamp on receive time; history rows on the 0x121 epoch + id min. CGM.md §4–§7. */
class AidexXConnectedSource(
    override val descriptor: CgmSourceDescriptor,
    private val serial: String,
    private val transport: AidexGattTransport,
    private val session: AidexSession,
    private val repository: CgmRepository,
    private val scope: CoroutineScope,
    private val dedup: DedupRing = DedupRing(),
    private val gridStamper: GridStamper = GridStamper(),
    private val tzOffsetMinFor: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Once per session, after the key is derived: the handle it was dialled on is proven. */
    private val onAuthenticated: suspend () -> Unit = {},
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) : ConnectedCgmSession {

    private enum class Phase {
        IDLE, CONNECTING, DISCOVERING,
        SUB_F001, SUB_F002, AWAIT_ASKKEY_WRITE, AWAIT_MASTERKEY, AWAIT_BLOB,
        DEVICE_INFO, CHECK_ACTIVATION,
        // Activation: F003 CCCD first so no push is dropped, then 0x20 → 0x35 → 0x34 → 0x21 → 0x11.
        ACTIVATE_SUB_F003, ACTIVATE_SETNEW, ACTIVATE_DYNADV, ACTIVATE_AUTOUPDATE,
        ACTIVATE_CONFIRM, ACTIVATE_BROADCAST,
        // Already-active arming: F003 → 0x34 → 0x11.
        SUB_F003, ENABLE_AUTOUPDATE, ENABLE_BROADCAST, LIVE, FAILED,
    }

    private val _status = MutableStateFlow(CgmSourceStatus.Idle)
    override val status: StateFlow<CgmSourceStatus> = _status.asStateFlow()

    private val _readings = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 64)
    override fun readings(): Flow<CgmReading> = _readings.asSharedFlow()

    /** dBm; polled off the GATT link. Null until the first sample. */
    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _statedLifetimeMin = MutableStateFlow<Int?>(null)
    override val statedLifetimeMin: StateFlow<Int?> = _statedLifetimeMin.asStateFlow()

    /** Any pull, automatic or tapped; set by [onPull], cleared when it ends or the link goes. */
    private val _backfillInFlight = MutableStateFlow(false)
    override val backfillInFlight: StateFlow<Boolean> = _backfillInFlight.asStateFlow()

    @Volatile
    private var lastRssi: Int? = null

    /** Written by the collector; the idle poller reads it too. */
    @Volatile
    private var phase = Phase.IDLE
        set(value) {
            if (value != field) log.d(TAG, "phase $field → $value")
            field = value
        }
    private lateinit var ivBytes: ByteArray
    private lateinit var askKeyBytes: ByteArray
    private var masterKey: ByteArray? = null
    private var sess: ByteArray? = null

    /** F002 blob read was refused (busy GATT); retried from the askKey write callback. */
    private var blobReadPending = false

    private enum class PullRequest {
        /** The session just went live, or nothing has been stored for [IDLE_PULL_MS]. */
        AUTO,

        /** The user asked for every grid slot the store lacks, back to the retention floor. */
        GAPS,
    }

    /** Capacity one, dropping the newest: a second press while one runs changes nothing. */
    private val pullRequests = Channel<PullRequest>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)

    private enum class SensorCommand { ACTIVATE }

    /** A MAIN-thread press, carried to the collector; capacity one, dropping the newest. */
    private val sensorCommands = Channel<SensorCommand>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)

    /** Batch pacing; the delay cannot run on the collector, so a spare coroutine ticks it. */
    private val backfillTicks = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)

    /** Carries the generation of the pull request whose reply deadline passed. */
    private val replyTimeouts = Channel<Int>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)
    private var replyDeadline: Job? = null
    private var replyGeneration: Int = 0

    private sealed interface Input {
        @JvmInline value class Gatt(val event: AidexGattEvent) : Input
        data object Tick : Input
        @JvmInline value class Pull(val request: PullRequest) : Input
        @JvmInline value class ReplyTimeout(val generation: Int) : Input
        @JvmInline value class Command(val command: SensorCommand) : Input
        data object Stall : Input
    }

    // Backfill state below: collector-only, bar [lastReadingMs], which the idle poller reads.

    /** `0x121`'s epoch — the sole anchor for a recovered instant (CGM.md §6). */
    private var activationEpochSecs: Long? = null

    /** Absolute id of relId 0. Unknown until a reply: only `relId=1` reveals where the range starts. */
    private var historyBase: Int? = null

    /** `0x122`'s newest absolute id; the ceiling a pull walks up to. */
    private var lastAbsoluteId: Int = 0

    /** Highest absolute id accounted for; persisted, so a reconnect resumes rather than restarts. */
    private var backfillCursor: Int = 0

    private var nextAbsoluteId: Int = 0
    private var batchInFlight: Boolean = false
    private var awaitingLastId: Boolean = false
    private var backfillOver: Boolean = false
    private var recovered: Int = 0
    private var cursorPersistedMs: Long = 0
    private var pendingRequest: PullRequest = PullRequest.AUTO

    // One grid slot's best candidate: ids are 1-minute, the grid is 5, so four in five are dropped.
    private var slotTs: Long = Long.MIN_VALUE
    private var slotSample: AidexHistorySample? = null
    private var slotInstantMs: Long = 0
    private var slotDistance: Long = Long.MAX_VALUE

    @Volatile
    private var lastReadingMs: Long = 0

    /** The [lastReadingMs] an idle pull was raised for; one silence raises one. Poller only. */
    private var idlePulledFor: Long = -1L

    private var idlePull: Job? = null

    /** Pre-LIVE, any GATT event bar RSSI; LIVE, only an F003 frame that decrypts. */
    @Volatile
    private var lastProgressMs: Long = 0

    private var watchdog: Job? = null

    /** Watchdog to collector; the collector re-checks, since progress may land in between. */
    private val stalls = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)

    /** Call once per source; autoConnect is off, so a drop does not auto-reconnect. */
    override fun start() {
        scope.launch {
            if (!beginHandshake()) return@launch
            // ONE collector for every input, so a pull press and a notification never race the machine.
            try {
                merge(
                    transport.events.map { Input.Gatt(it) },
                    backfillTicks.receiveAsFlow().map { Input.Tick },
                    pullRequests.receiveAsFlow().map { Input.Pull(it) },
                    replyTimeouts.receiveAsFlow().map { Input.ReplyTimeout(it) },
                    sensorCommands.receiveAsFlow().map { Input.Command(it) },
                    stalls.receiveAsFlow().map { Input.Stall },
                ).collect { onInput(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(TAG, "AiDEX session machine threw", e)
                fail("unhandled ${e.javaClass.simpleName} in the session machine")
            }
        }
        scope.launch {
            while (isActive) {
                delay(RSSI_POLL_MS)
                transport.readRemoteRssi()
            }
        }
        armIdlePull()
        armWatchdog()
    }

    private suspend fun onInput(input: Input) = when (input) {
        is Input.Gatt -> onGattEvent(input.event)
        Input.Tick -> sendHistoryRequest()
        is Input.Pull -> onPull(input.request)
        is Input.ReplyTimeout -> onReplyTimeout(input.generation)
        is Input.Command -> onCommand(input.command)
        Input.Stall -> onStall()
    }

    /** Polls: a sleep sized to LIVE's budget would outlast a shorter step begun meanwhile. */
    private fun armWatchdog() {
        watchdog = scope.launch {
            while (isActive) {
                delay(STALL_POLL_MS)
                if (silentPastBudgetMs() != null) stalls.trySend(Unit)
            }
        }
    }

    /** SignalLost is what makes the registry rebuild the session. */
    private fun onStall() {
        val silentMs = silentPastBudgetMs() ?: return
        fail("nothing came back for ${silentMs / 1000}s in $phase")
    }

    /** Null within the phase's budget, or in a phase that has none. */
    private fun silentPastBudgetMs(): Long? {
        val budget = stallBudgetMs(phase) ?: return null
        return (nowMs() - lastProgressMs).takeIf { it > budget }
    }

    private fun stallBudgetMs(p: Phase): Long? = when (p) {
        // The stack's own connect timeout bounds CONNECTING.
        Phase.IDLE, Phase.CONNECTING, Phase.FAILED -> null
        Phase.LIVE -> LIVE_STALL_MS
        else -> STEP_STALL_MS
    }

    private fun armReplyDeadline() {
        replyDeadline?.cancel()
        val generation = ++replyGeneration
        replyDeadline = scope.launch {
            delay(REPLY_TIMEOUT_MS)
            replyTimeouts.trySend(generation)
        }
    }

    private fun disarmReplyDeadline() {
        replyDeadline?.cancel()
        replyDeadline = null
    }

    /** A stale generation lost the race to its reply or to a newer request. */
    private suspend fun onReplyTimeout(generation: Int) {
        if (generation != replyGeneration || !(awaitingLastId || batchInFlight)) return
        val what = if (awaitingLastId) "getLastId (0x22)" else "getHistory (0x23)"
        awaitingLastId = false
        endBackfill("$what drew no reply in $REPLY_TIMEOUT_MS ms")
    }

    /** One pull per silence, not one per tick: a stalled link is asked once until a reading lands. */
    private fun armIdlePull() {
        idlePull = scope.launch {
            while (isActive) {
                delay(IDLE_PULL_POLL_MS)
                val since = lastReadingMs
                if (phase != Phase.LIVE || since == 0L) continue
                if (idlePulledFor == since || nowMs() - since < IDLE_PULL_MS) continue
                idlePulledFor = since
                pullRequests.trySend(PullRequest.AUTO)
            }
        }
    }

    /** Re-writes sensor start time, restarts warm-up; user-triggered, MAIN thread. Live only. */
    override fun requestActivate() {
        sensorCommands.trySend(SensorCommand.ACTIVATE)
    }

    /** Ends any pull first: its late reply would otherwise be read as this command's ack. */
    private suspend fun onCommand(command: SensorCommand) {
        val s = sess
        if (phase != Phase.LIVE || s == null) {
            log.w(TAG, "$command ignored — phase=$phase, key=${s != null}")
            return
        }
        disarmReplyDeadline()
        awaitingLastId = false
        endBackfill("$command requested")
        batchInFlight = false
        // The old wear's anchor and range; pulls stay refused until a new 0x121 is read.
        activationEpochSecs = null
        historyBase = null
        when (command) {
            SensorCommand.ACTIVATE -> {
                log.i(TAG, "manual activation requested")
                beginActivation()
            }
        }
    }

    /** MAIN thread; the collector runs every guard. */
    override fun requestBackfill() {
        pullRequests.trySend(PullRequest.GAPS)
    }

    override fun close() {
        idlePull?.cancel()
        idlePull = null
        watchdog?.cancel()
        watchdog = null
        disarmReplyDeadline()
        // Before the transport goes: an in-flight tick would otherwise write to a closed link.
        backfillOver = true
        batchInFlight = false
        _backfillInFlight.value = false
        transport.close()
        phase = Phase.IDLE
        lastRssi = null
        _rssi.value = null
    }

    /** UI only; loss-of-signal alarms fire independently on AlarmController's tick (§3.6-A). */
    override fun markSignalLost() {
        if (_status.value == CgmSourceStatus.Live || _status.value == CgmSourceStatus.Warmup) {
            _status.value = CgmSourceStatus.SignalLost
        }
    }

    /** False on a bad serial. */
    internal fun beginHandshake(): Boolean {
        return try {
            ivBytes = session.iv(serial)
            askKeyBytes = session.askKey(serial)
            if (_status.value == CgmSourceStatus.Idle) _status.value = CgmSourceStatus.Scanning
            phase = Phase.CONNECTING
            transport.connect()
            true
        } catch (t: Throwable) {
            fail("key-material derivation failed for serial '$serial': ${t.message}")
            false
        }
    }

    internal suspend fun onGattEvent(ev: AidexGattEvent) {
        if (ev !is AidexGattEvent.Rssi && phase != Phase.LIVE) lastProgressMs = nowMs()
        when (ev) {
            is AidexGattEvent.Connection -> onConnection(ev)
            is AidexGattEvent.ServicesDiscovered -> onServices(ev)
            is AidexGattEvent.NotifyEnabled -> onNotifyEnabled(ev)
            is AidexGattEvent.Write -> onWrite(ev)
            is AidexGattEvent.Read -> decodingChunk(ev.rx) { onRead(ev) }
            is AidexGattEvent.Notify -> decodingChunk(ev.rx) { onNotify(ev) }
            is AidexGattEvent.Rssi -> if (ev.ok) {
                lastRssi = ev.dbm
                _rssi.value = ev.dbm
            }
            is AidexGattEvent.Failure -> fail("transport error: ${ev.reason}")
        }
    }

    private fun onConnection(ev: AidexGattEvent.Connection) {
        if (!ev.connected) {
            _backfillInFlight.value = false
            if (phase == Phase.LIVE) {
                // Direct set: phase may be LIVE while status is Scanning; markSignalLost() no-ops.
                _status.value = CgmSourceStatus.SignalLost
                log.w(TAG, "disconnected while live (status=${ev.statusCode})")
            } else if (phase != Phase.IDLE && phase != Phase.FAILED) {
                fail("disconnected during $phase (status=${ev.statusCode})")
            }
            return
        }
        if (!ev.statusOk) {
            fail("connection error (status=${ev.statusCode})")
            return
        }
        // No createBond: the F001 CCCD write pairs an unpaired link (CGM.md §4).
        phase = Phase.DISCOVERING
        if (!transport.discoverServices()) fail("discoverServices() rejected")
    }

    private fun onServices(ev: AidexGattEvent.ServicesDiscovered) {
        if (phase != Phase.DISCOVERING) return
        if (!ev.ok || !ev.hasCgmService) {
            fail("service discovery failed (ok=${ev.ok}, cgmService=${ev.hasCgmService})")
            return
        }
        phase = Phase.SUB_F001
        if (!transport.setNotify(AidexChar.F001, true)) fail("enable F001 notify rejected")
    }

    private fun onNotifyEnabled(ev: AidexGattEvent.NotifyEnabled) {
        if (!ev.ok) {
            fail("CCCD write failed for ${ev.char}")
            return
        }
        when (phase) {
            Phase.SUB_F001 -> if (ev.char == AidexChar.F001) {
                phase = Phase.SUB_F002
                if (!transport.setNotify(AidexChar.F002, true)) fail("enable F002 notify rejected")
            }
            Phase.SUB_F002 -> if (ev.char == AidexChar.F002) {
                phase = Phase.AWAIT_ASKKEY_WRITE
                log.i(TAG, "writing askKey to F001")
                log.tx("F001", askKeyBytes, text = "askKey")
                if (!transport.write(AidexChar.F001, askKeyBytes, withResponse = true)) {
                    fail("askKey write rejected")
                }
            }
            Phase.SUB_F003 -> if (ev.char == AidexChar.F003) {
                phase = Phase.ENABLE_AUTOUPDATE
                writeF002(session.cmdSetAutoUpdate(sess!!, ivBytes), "setAutoUpdate")
            }
            Phase.ACTIVATE_SUB_F003 -> if (ev.char == AidexChar.F003) {
                val localStart = buildLocalStartTime()
                if (localStart == null) {
                    fail("could not encode current local time for setNewSensor")
                    return
                }
                phase = Phase.ACTIVATE_SETNEW
                writeF002(session.cmdSetNewSensor(sess!!, ivBytes, localStart), "setNewSensor")
            }
            else -> {}
        }
    }

    private suspend fun onWrite(ev: AidexGattEvent.Write) {
        if (!ev.ok) {
            if (phase != Phase.LIVE) fail("write to ${ev.char} failed during $phase")
            return
        }
        when (phase) {
            Phase.AWAIT_ASKKEY_WRITE -> if (ev.char == AidexChar.F001) {
                phase = Phase.AWAIT_MASTERKEY // now await the F001 masterkey notify
            }
            // Race: notify advanced phase; blob read was refused while this write was in flight.
            Phase.AWAIT_BLOB -> if (ev.char == AidexChar.F001 && blobReadPending) {
                blobReadPending = false
                if (!transport.read(AidexChar.F002)) fail("read of F002 blob rejected")
            }
            // On the write, not the reply: a sensor that never answers 0x10 still gets read.
            Phase.DEVICE_INFO -> if (ev.char == AidexChar.F002) {
                phase = Phase.CHECK_ACTIVATION
                writeF002(session.cmdGetStartTime(sess!!, ivBytes), "getStartTime")
            }
            Phase.ENABLE_AUTOUPDATE -> if (ev.char == AidexChar.F002) {
                phase = Phase.ENABLE_BROADCAST
                writeF002(session.cmdGetBroadcast(sess!!, ivBytes), "getBroadcast (initial value)")
            }
            Phase.ENABLE_BROADCAST -> if (ev.char == AidexChar.F002) {
                goLive("session LIVE")
            }
            Phase.ACTIVATE_BROADCAST -> if (ev.char == AidexChar.F002) {
                goLive("session LIVE (post-activation)")
            }
            // These writes elicit only an F002 notify; the machine advances on the response.
            else -> {}
        }
    }

    private suspend fun onRead(ev: AidexGattEvent.Read) {
        if (phase != Phase.AWAIT_BLOB || ev.char != AidexChar.F002) return
        if (!ev.ok) {
            fail("read of F002 session blob failed")
            return
        }
        val mk = masterKey
        if (mk == null) {
            fail("session blob arrived before masterkey")
            return
        }
        val derived = session.deriveSession(serial, mk, ev.value)
        if (derived == null) {
            fail("session derivation failed (bad blob size / crc8)")
            return
        }
        sess = derived
        log.i(TAG, "session key derived; reading device info")
        // At connect, as the vendor app does (CGM.md §7).
        phase = Phase.DEVICE_INFO
        writeF002(session.cmdDeviceInfo(sess!!, ivBytes), "deviceInfo")
        onAuthenticated()
    }

    private suspend fun onNotify(ev: AidexGattEvent.Notify) {
        val s = sess
        when {
            // Masterkey notify may beat the write callback; valid in AWAIT_ASKKEY_WRITE too.
            ev.char == AidexChar.F001 &&
                (phase == Phase.AWAIT_MASTERKEY || phase == Phase.AWAIT_ASKKEY_WRITE) -> {
                if (ev.value.size != MASTERKEY_LEN) {
                    fail("masterkey wrong size (${ev.value.size} != $MASTERKEY_LEN)")
                    return
                }
                masterKey = ev.value.copyOf()
                phase = Phase.AWAIT_BLOB
                log.i(TAG, "masterkey received; reading F002 session blob")
                // Not dead: one GATT op at a time; retried from write-callback if outstanding.
                if (!transport.read(AidexChar.F002)) {
                    blobReadPending = true
                    log.w(TAG, "F002 blob read refused; re-issuing when the askKey write lands")
                }
            }
            ev.char == AidexChar.F003 && s != null -> onRealtimeFrame(s, ev.value)
            ev.char == AidexChar.F002 && s != null -> onF002Response(s, ev.value)
            else -> log.d(TAG, "ignored notify on ${ev.char} in phase $phase")
        }
    }

    private suspend fun onF002Response(s: ByteArray, ct: ByteArray) {
        val pt = session.decryptFrame(s, ivBytes, ct) ?: run {
            log.w(TAG, "F002 frame decrypt/CRC failed in $phase")
            return
        }
        val resp = session.parseResponse(pt) ?: run {
            log.dec(TAG, "F002 response parse failed", level = CgmLogLevel.W, bytes = pt)
            return
        }
        log.dec(null, describe(resp), topicOf(resp), bytes = pt)
        // These may land in any later phase; read as that phase's answer, they would derail it.
        when (resp) {
            is AidexSessionResponse.DeviceInfo -> onDeviceInfo(resp)
            is AidexSessionResponse.LastId -> onLastId(resp.lastId)
            is AidexSessionResponse.History -> onHistory(resp)
            else -> when (phase) {
                Phase.CHECK_ACTIVATION -> onStartTimeResponse(resp)
                Phase.ACTIVATE_SETNEW -> onActivationAck(resp, "setNewSensor", ACK_SETNEW) {
                    phase = Phase.ACTIVATE_DYNADV
                    writeF002(session.cmdSetDynamicAdvMode(sess!!, ivBytes), "setDynamicAdvMode")
                }
                Phase.ACTIVATE_DYNADV -> onActivationAck(resp, "setDynamicAdvMode", ACK_DYNADV) {
                    phase = Phase.ACTIVATE_AUTOUPDATE
                    writeF002(session.cmdSetAutoUpdate(sess!!, ivBytes), "setAutoUpdate (activation)")
                }
                Phase.ACTIVATE_AUTOUPDATE -> onActivationAck(resp, "setAutoUpdate", ACK_AUTOUPDATE) {
                    phase = Phase.ACTIVATE_CONFIRM
                    writeF002(session.cmdGetStartTime(sess!!, ivBytes), "getStartTime (confirm)")
                }
                Phase.ACTIVATE_CONFIRM -> onActivationConfirm(resp)
                else -> onLiveResponse(resp, pt) // ENABLE_*/LIVE
            }
        }
    }

    private fun onStartTimeResponse(resp: AidexSessionResponse) {
        when (resp) {
            is AidexSessionResponse.StartTime -> {
                if (resp.year >= MIN_ACTIVATION_YEAR) {
                    activationEpochSecs = resp.epochSecs
                    log.i(TAG, "sensor already activated (start ${resp.year}, epoch ${resp.epochSecs}); reading")
                    subscribeRealtime()
                } else {
                    log.i(TAG, "sensor UNACTIVATED (year=${resp.year}); running fresh-sensor activation (§7)")
                    beginActivation()
                }
            }
            else -> {
                // Ambiguous: never reset a possibly-active sensor.
                log.w(TAG, "unexpected getStartTime response ($resp); proceeding read-only (no activation)")
                subscribeRealtime()
            }
        }
    }

    /** F003 CCCD is armed FIRST, so no push is dropped once auto-update turns on. */
    private fun beginActivation() {
        // From LIVE the clock is the last F003 frame's, minutes old; the step budget starts now.
        lastProgressMs = nowMs()
        phase = Phase.ACTIVATE_SUB_F003
        if (!transport.setNotify(AidexChar.F003, true)) fail("enable F003 notify rejected (activation)")
    }

    /** Lenient: a sensor that does not echo the exact tag still proceeds to [next]. */
    private inline fun onActivationAck(resp: AidexSessionResponse, step: String, expectedTag: Int, next: () -> Unit) {
        if (resp is AidexSessionResponse.Ack && resp.tag == expectedTag) {
            log.i(TAG, "$step ack (0x${expectedTag.toString(16)})")
        } else {
            log.w(TAG, "$step: unexpected response ($resp); proceeding")
        }
        next()
    }

    /** Issues the final getBroadcast whatever the confirm says. */
    private fun onActivationConfirm(resp: AidexSessionResponse) {
        if (resp is AidexSessionResponse.StartTime) {
            activationEpochSecs = resp.epochSecs
            log.i(TAG, "activation confirmed (start ${resp.year}, epoch ${resp.epochSecs})")
        } else {
            log.w(TAG, "activation confirm: unexpected response ($resp); proceeding to broadcast")
        }
        phase = Phase.ACTIVATE_BROADCAST
        writeF002(session.cmdGetBroadcast(sess!!, ivBytes), "getBroadcast (activation)")
    }

    /** CGM.md §7: UTC offset is a multiple of 15 min; folds into tzQuarterHours, dst=0. */
    private fun buildLocalStartTime(): ByteArray? {
        return try {
            val now = nowMs()
            val offsetMin = tzOffsetMinFor(now)
            val local = Instant.ofEpochMilli(now).atOffset(ZoneOffset.ofTotalSeconds(offsetMin * 60))
            session.encodeLocalStartTime(
                year = local.year,
                month = local.monthValue,
                day = local.dayOfMonth,
                hour = local.hour,
                minute = local.minute,
                second = local.second,
                tzQuarterHours = offsetMin / 15,
                dstQuarterHours = 0,
            )
        } catch (t: Throwable) {
            log.w(TAG, "encodeLocalStartTime failed: ${t.message}")
            null
        }
    }

    private fun subscribeRealtime() {
        phase = Phase.SUB_F003
        if (!transport.setNotify(AidexChar.F003, true)) fail("enable F003 notify rejected")
    }

    /** [topic]: GLUCOSE for F003's periodic status frame, so it folds with the pushes around it. */
    private suspend fun onLiveResponse(
        resp: AidexSessionResponse,
        pt: ByteArray,
        topic: CgmLogTopic = CgmLogTopic.NONE,
    ) {
        when (resp) {
            is AidexSessionResponse.Current -> emitCurrent(resp, pt)
            is AidexSessionResponse.StartTime -> log.i(TAG, "start time epoch=${resp.epochSecs}", topic)
            is AidexSessionResponse.DeviceInfo -> onDeviceInfo(resp)
            is AidexSessionResponse.LastId -> onLastId(resp.lastId)
            is AidexSessionResponse.History -> onHistory(resp)
            is AidexSessionResponse.Ack -> log.i(TAG, "ack tag=0x${resp.tag.toString(16)}", topic)
            is AidexSessionResponse.Disconnect -> log.i(TAG, "disconnect success=${resp.success}", topic)
            is AidexSessionResponse.Unknown -> log.i(TAG, "unknown response tag=0x${resp.tag.toString(16)}", topic)
        }
    }

    /** Zero states no wear. A failed store costs the countdown only until the next connect. */
    private suspend fun onDeviceInfo(resp: AidexSessionResponse.DeviceInfo) {
        log.i(TAG, "device '${resp.name}' fw ${resp.firmware} life ${resp.lifeDays}d")
        if (resp.lifeDays <= 0) return
        val minutes = resp.lifeDays * MINUTES_PER_DAY
        _statedLifetimeMin.value = minutes
        try {
            repository.saveSensorLifetimeMin(descriptor.id, minutes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(TAG, "the stated lifetime was not stored: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun onRealtimeFrame(s: ByteArray, ct: ByteArray) {
        val pt = session.decryptFrame(s, ivBytes, ct) ?: run {
            log.w(TAG, "F003 realtime frame decrypt/CRC failed")
            return
        }
        lastProgressMs = nowMs()
        val rt = session.parseRealtime(pt) ?: run {
            // F003 also carries a 3-byte status frame (~4min); parsed as a response, not an error.
            val resp = session.parseResponse(pt)
            if (resp == null) {
                log.dec(
                    TAG,
                    "F003 frame is neither a realtime record nor a response (${pt.size} B)",
                    CgmLogTopic.GLUCOSE,
                    level = CgmLogLevel.W,
                    bytes = pt,
                )
            } else {
                log.dec(null, "status ${describe(resp)}", CgmLogTopic.GLUCOSE, bytes = pt)
                onLiveResponse(resp, pt, CgmLogTopic.GLUCOSE)
            }
            return
        }
        val summary = "realtime min=${rt.minFromStart} g=${rt.glucoseMgdl} trend=${rt.trendTenthsPerMin} " +
            "valid=${rt.valid} warmup=${rt.warmup} type=${rt.readingType}"
        if (dedup.contains(rt.minFromStart)) {
            log.dec(null, "$summary — already delivered", CgmLogTopic.GLUCOSE, bytes = pt)
            return
        }
        dedup.record(rt.minFromStart)
        val flag = flagFor(rt.valid, rt.glucoseMgdl, warmupBit = rt.warmup, minFromStart = rt.minFromStart)
        if (flag == ReadingFlag.INVALID) {
            log.dec(TAG, "$summary — dropped", CgmLogTopic.GLUCOSE, level = CgmLogLevel.W, bytes = pt)
            return
        }
        log.dec(null, "$summary — stored $flag", CgmLogTopic.GLUCOSE, value = rt.glucoseMgdl, bytes = pt)
        val decoded = DecodedAdvert(
            minFromStart = rt.minFromStart,
            status = 0,
            trendTenthsPerMin = rt.trendTenthsPerMin,
            glucoseMgdl = rt.glucoseMgdl,
            valid = rt.valid,
            quality = 0,
            prev = emptyList(),
            crc32 = 0L,
        )
        persistAndEmit(decoded, flag, nowMs(), pt)
    }

    private suspend fun emitCurrent(resp: AidexSessionResponse.Current, pt: ByteArray) {
        if (dedup.contains(resp.minFromStart)) return
        dedup.record(resp.minFromStart)
        // A LastPast carries no hardware warm-up bit: null, not `false`, which would assert one.
        val flag = flagFor(resp.valid, resp.glucoseMgdl, warmupBit = null, minFromStart = resp.minFromStart)
        if (flag == ReadingFlag.INVALID) return
        val decoded = DecodedAdvert(
            minFromStart = resp.minFromStart,
            status = 0,
            trendTenthsPerMin = resp.trendTenthsPerMin,
            glucoseMgdl = resp.glucoseMgdl,
            valid = resp.valid,
            quality = resp.quality,
            prev = emptyList(),
            crc32 = 0L,
        )
        persistAndEmit(decoded, flag, nowMs(), pt)
    }

    /** Fail-closed value gate first; non-null warmupBit decides, else window (0 disables). */
    private fun flagFor(valid: Boolean, glucose: Int, warmupBit: Boolean?, minFromStart: Int): ReadingFlag = when {
        !valid || glucose !in CgmConstants.VALID_BG_RANGE -> ReadingFlag.INVALID
        warmupBit != null -> if (warmupBit) ReadingFlag.WARMUP else ReadingFlag.NORMAL
        minFromStart < descriptor.warmupWindowMin -> ReadingFlag.WARMUP
        else -> ReadingFlag.NORMAL
    }

    private suspend fun persistAndEmit(decoded: DecodedAdvert, flag: ReadingFlag, rxWallMs: Long, plaintext: ByteArray) {
        lastReadingMs = rxWallMs
        repository.insertRawAdvert(
            sourceId = descriptor.id,
            rxWallMs = rxWallMs,
            rssi = lastRssi,
            payload = plaintext,
            crcValid = true, // the frame CRC16 was verified by decryptFrame
            minFromStart = decoded.minFromStart,
        )
        val readings = gridStamper.stamp(
            sourceId = descriptor.id,
            decoded = decoded,
            flag = flag,
            rxWallMs = rxWallMs,
            tzOffsetMin = tzOffsetMinFor(rxWallMs),
            rssi = lastRssi,
        )
        for (reading in readings) {
            repository.upsertReading(reading)
            updateStatus(reading)
            _readings.emit(reading)
        }
    }

    private fun updateStatus(reading: CgmReading) {
        if (reading.provenance != ReadingProvenance.MEASURED) return
        _status.value = when (reading.flag) {
            ReadingFlag.WARMUP -> CgmSourceStatus.Warmup
            ReadingFlag.NORMAL -> CgmSourceStatus.Live
            ReadingFlag.INVALID -> _status.value
        }
    }

    private suspend fun goLive(what: String) {
        phase = Phase.LIVE
        lastReadingMs = nowMs()
        backfillCursor = repository.loadSourceCursor(descriptor.id)
        log.i(TAG, "$what — realtime on F003 armed")
        // The anchor is 0x21's and already proven, so unlike CT5 this needs no push before it pulls.
        pullRequests.trySend(PullRequest.AUTO)
    }

    /** Opens a pull; the ceiling, and on a first ask the range base, are fetched before any record. */
    private suspend fun onPull(request: PullRequest) {
        if (phase != Phase.LIVE || batchInFlight || awaitingLastId) {
            log.i(TAG, "$request pull ignored — phase=$phase inFlight=$batchInFlight awaitingCeiling=$awaitingLastId")
            return
        }
        if (activationEpochMs() == null) {
            // A recovered sample has no receive-time fallback: without the anchor it would be misdated.
            log.w(TAG, "$request pull refused — no plausible activation time was read this session")
            return
        }
        pendingRequest = request
        awaitingLastId = true
        _backfillInFlight.value = true
        armReplyDeadline()
        writeF002(session.cmdGetLastId(sess!!, ivBytes), "getLastId (0x22)", CgmLogTopic.HISTORY)
    }

    /** `0x122` names the newest ABSOLUTE id; where to start is then decided against the grid. */
    private suspend fun onLastId(lastId: Int) {
        // Late, after a timeout or a sensor command ended its pull.
        if (!awaitingLastId) return
        disarmReplyDeadline()
        awaitingLastId = false
        val epochMs = activationEpochMs() ?: run {
            _backfillInFlight.value = false
            return
        }
        lastAbsoluteId = lastId
        // A reset restarts ids at 1: a cursor past the newest id belongs to the previous wear.
        if (backfillCursor > lastId) {
            log.i(TAG, "history: cursor $backfillCursor is past the newest id $lastId — a new wear; cursor reset")
            backfillCursor = 0
            repository.saveSourceCursor(descriptor.id, backfillCursor)
        }
        // Held minutes from before this activation are the previous wear's, whatever their number.
        val held = repository.receivedSampleMinutes(descriptor.id, notBeforeMs = epochMs)
        val floor = floorId(epochMs, held.completeSinceMs, lastId)
        val target = when (pendingRequest) {
            PullRequest.GAPS -> lowestMissingId(epochMs, floor, lastId, held)
            PullRequest.AUTO -> maxOf(backfillCursor + 1, floor).takeIf { it <= lastId }
        }
        if (target == null) {
            log.i(TAG, "history: every slot back to the retention floor is covered (newest id $lastId)")
            backfillOver = true
            _backfillInFlight.value = false
            return
        }
        // A user ask may reach below the cursor; it must follow, or the next automatic pull skips it.
        if (target - 1 < backfillCursor) {
            backfillCursor = target - 1
            repository.saveSourceCursor(descriptor.id, backfillCursor)
        }
        nextAbsoluteId = target
        recovered = 0
        backfillOver = false
        log.i(TAG, "history: pulling from id $target up to $lastId")
        sendHistoryRequest()
    }

    /** relId is 1-based into the sensor's RANGE, not an absolute id, and only a reply names the base. */
    private suspend fun sendHistoryRequest() {
        if (backfillOver || batchInFlight || phase != Phase.LIVE) return
        val base = historyBase
        val relId = if (base == null) 1 else nextAbsoluteId - base
        if (relId < 1) {
            endBackfill("id $nextAbsoluteId sits below the range the sensor still holds")
            return
        }
        batchInFlight = true
        armReplyDeadline()
        writeF002(session.cmdGetHistory(sess!!, ivBytes, relId), "getHistory (0x23 relId=$relId)", CgmLogTopic.HISTORY)
    }

    private suspend fun onHistory(resp: AidexSessionResponse.History) {
        if (!batchInFlight) return
        disarmReplyDeadline()
        batchInFlight = false
        val epochMs = activationEpochMs() ?: run { endBackfill("the activation time went away mid-pull"); return }
        if (historyBase == null) {
            historyBase = resp.startId - 1
            log.i(TAG, "history: the sensor's range starts at id ${resp.startId}")
        }
        if (resp.samples.isEmpty()) {
            // Legal but empty, and there is no terminator: advancing blind would strand the cursor.
            endBackfill("id $nextAbsoluteId was answered with an empty batch")
            return
        }
        // The relId=1 probe answers from the range base; the target set by onLastId still stands.
        val wanted = nextAbsoluteId
        for (sample in resp.samples) if (sample.recordId >= wanted) admit(sample, epochMs)
        nextAbsoluteId = maxOf(wanted, resp.startId + resp.samples.size)
        persistCursor(nextAbsoluteId - 1)
        if (nextAbsoluteId > lastAbsoluteId) {
            endBackfill("caught up with the sensor's newest id $lastAbsoluteId")
            return
        }
        // Paced: a wear-long pull shares one link with the realtime pushes it must not starve.
        scope.launch {
            delay(HISTORY_BATCH_GAP_MS)
            backfillTicks.trySend(Unit)
        }
    }

    /** Keeps the sample nearest a slot's centre — the row GridSlotSelection would have picked anyway. */
    private suspend fun admit(sample: AidexHistorySample, epochMs: Long) {
        val instantMs = epochMs + sample.recordId * 60_000L
        // Dated ahead of now is the sensor's clock disagreeing with ours, not a measurement.
        if (instantMs > nowMs()) return
        val ts = gridStamper.snap(instantMs)
        if (ts != slotTs) {
            flushSlot()
            slotTs = ts
        }
        val distance = abs(instantMs - ts)
        if (distance < slotDistance) {
            slotDistance = distance
            slotSample = sample
            slotInstantMs = instantMs
        }
    }

    /** Live path's gates minus three: its own instant, no readings() emit, no status climb. */
    private suspend fun flushSlot() {
        val sample = slotSample ?: return
        slotSample = null
        slotDistance = Long.MAX_VALUE
        val flag = flagFor(sample.valid, sample.glucoseMgdl, warmupBit = sample.warmup, minFromStart = sample.recordId)
        if (flag == ReadingFlag.INVALID) return
        repository.upsertReading(
            CgmReading(
                sourceId = descriptor.id,
                tsMs = slotTs,
                bgMgdl = sample.glucoseMgdl,
                // A history record carries no trend; deriving one would invent it.
                trendTenthsPerMin = null,
                minFromStart = sample.recordId,
                quality = null,
                provenance = ReadingProvenance.MEASURED,
                flag = flag,
                // §2: east-positive minutes at the sample's own moment, not at receipt.
                tzOffsetMin = tzOffsetMinFor(slotInstantMs),
                rxWallMs = slotInstantMs,
                // The link this arrived over says nothing about signal when the sample was taken.
                rssi = null,
                // The sensor's own clock, hours before receipt on a history read.
                measuredAtMs = slotInstantMs,
            ),
        )
        recovered++
    }

    /** Stops this session's pull; the next one resumes from the persisted cursor. */
    private suspend fun endBackfill(why: String) {
        _backfillInFlight.value = false
        if (backfillOver) return
        flushSlot()
        slotTs = Long.MIN_VALUE
        backfillOver = true
        batchInFlight = false
        if (recovered > 0) {
            repository.saveSourceCursor(descriptor.id, backfillCursor)
            log.i(TAG, "history: recovered $recovered samples — $why")
        } else {
            log.i(TAG, "history: recovered nothing — $why")
        }
    }

    /** Rate-limited during a long pull; [endBackfill] writes the final value unconditionally. */
    private suspend fun persistCursor(cursor: Int) {
        if (cursor <= backfillCursor || cursor > 0xFFFF) return
        backfillCursor = cursor
        val now = nowMs()
        if (now - cursorPersistedMs < CURSOR_PERSIST_INTERVAL_MS) return
        cursorPersistedMs = now
        repository.saveSourceCursor(descriptor.id, cursor)
    }

    /** Ids below the store's retention floor are claimed neither way, so are never asked for. */
    private fun floorId(epochMs: Long, completeSinceMs: Long, ceiling: Int): Int {
        if (completeSinceMs <= epochMs) return 1
        return (((completeSinceMs - epochMs) + 59_999L) / 60_000L).coerceIn(1L, ceiling + 1L).toInt()
    }

    /** Lowest id whose GRID SLOT holds nothing; by id alone four in five would always read missing. */
    private fun lowestMissingId(epochMs: Long, floor: Int, ceiling: Int, held: CgmReceivedSamples): Int? {
        val covered = held.minutes.mapTo(HashSet()) { gridStamper.snap(epochMs + it * 60_000L) }
        for (id in floor..ceiling) {
            if (gridStamper.snap(epochMs + id * 60_000L) !in covered) return id
        }
        return null
    }

    /** Null unless a plausible `0x121` was read; an epoch at or after now cannot date a past sample. */
    private fun activationEpochMs(): Long? {
        val ms = (activationEpochSecs ?: return null) * 1000L
        return ms.takeIf { it > 0L && it < nowMs() }
    }

    private fun fail(reason: String) {
        log.w(TAG, "connected session failed: $reason")
        phase = Phase.FAILED
        lastRssi = null
        _rssi.value = null
        _backfillInFlight.value = false
        disarmReplyDeadline()
        watchdog?.cancel()
        watchdog = null
        // CgmSourceStatus has no error state; SignalLost is the closest the UI shows.
        _status.value = CgmSourceStatus.SignalLost
    }

    /** Tests drive [onGattEvent] directly and never start the collector, so the channel is bypassed. */
    internal suspend fun pullForTest(user: Boolean) =
        onPull(if (user) PullRequest.GAPS else PullRequest.AUTO)

    /** The paced batch tick, which a spare coroutine would otherwise send after its delay. */
    internal suspend fun tickForTest() = sendHistoryRequest()

    internal suspend fun activateForTest() = onCommand(SensorCommand.ACTIVATE)

    /** What the watchdog's poll would ask the collector to do. */
    internal fun stallCheckForTest() = onStall()

    internal fun installSessionForTest(sessionKey: ByteArray) {
        ivBytes = session.iv(serial)
        sess = sessionKey
        phase = Phase.LIVE
        if (_status.value == CgmSourceStatus.Idle) _status.value = CgmSourceStatus.Scanning
    }

    companion object {
        private const val TAG = "AidexConnect"
        private const val MASTERKEY_LEN = 16
        /** Slow: the meter only reflects a held link. Matches the watch's poll. */
        private const val RSSI_POLL_MS = 15_000L
        private const val ACK_SETNEW = 0x120
        private const val ACK_DYNADV = 0x135
        private const val ACK_AUTOUPDATE = 0x134

        /** At or above this is a genuine activation; below it the sensor needs the §7 sequence. */
        private const val MIN_ACTIVATION_YEAR = 2000

        /** Spans more than two grid slots, so ordinary push jitter cannot fire it. */
        private const val IDLE_PULL_MS = 11 * 60_000L

        private const val IDLE_PULL_POLL_MS = 60_000L

        /** A wear-long pull is hundreds of round trips on the link the pushes also use. */
        private const val HISTORY_BATCH_GAP_MS = 750L

        /** A 0x22/0x23 reply lands ~130 ms after the write (live 2026-09-24). */
        private const val REPLY_TIMEOUT_MS = 10_000L

        private const val CURSOR_PERSIST_INTERVAL_MS = 10 * 60_000L

        /** One handshake step; spans the ~30 s pairing the F001 CCCD write can hold. */
        private const val STEP_STALL_MS = 60_000L

        /** Past [IDLE_PULL_MS] plus one ~4 min F003 status frame, so the idle pull goes first. */
        private const val LIVE_STALL_MS = 15 * 60_000L

        private const val STALL_POLL_MS = 15_000L

        /** New serial; BLE addr isn't identity (§3.1); warmupWindowMin is vendor seed only. */
        fun descriptorFor(serial: String, advertName: String? = null): CgmSourceDescriptor {
            val match = advertName?.let(CgmConstants::matchAdvertName)
            return CgmSourceDescriptor(
                id = CgmSourceId("aidexx:$serial"),
                vendorId = "aidexx",
                // A name that matched nothing cannot have come through the scanner.
                sensorModelId = match?.sensorModelId ?: CgmSensorModelId.AIDEX_X,
                advertName = advertName,
                displayName = "${match?.brand ?: "AiDEX X"} $serial",
                serialSuffix = serial,
                warmupWindowMin = CgmConstants.WARMUP_WINDOW_MIN,
                passiveOnly = false,
            )
        }
    }

    private fun writeF002(bytes: ByteArray, what: String, topic: CgmLogTopic = CgmLogTopic.NONE) {
        if (topic == CgmLogTopic.NONE) log.i(TAG, "F002 ← $what") else log.d(TAG, "F002 ← $what", topic, opens = true)
        log.tx("F002", bytes, topic, what)
        // F002 is WRITE_NR only; write-with-response is rejected by the sensor.
        if (!transport.write(AidexChar.F002, bytes, withResponse = false)) fail("$what write rejected")
    }

    private fun topicOf(resp: AidexSessionResponse): CgmLogTopic = when (resp) {
        is AidexSessionResponse.History, is AidexSessionResponse.LastId -> CgmLogTopic.HISTORY
        is AidexSessionResponse.Current -> CgmLogTopic.GLUCOSE
        else -> CgmLogTopic.NONE
    }

    private fun describe(resp: AidexSessionResponse): String = when (resp) {
        is AidexSessionResponse.History ->
            "history from ${resp.startId}: ${resp.samples.size} records · " +
                resp.samples.joinToString(" · ") { "#${it.recordId} ${it.glucoseMgdl} v=${it.valid} w=${it.warmup}" }
        else -> resp.toString()
    }
}
