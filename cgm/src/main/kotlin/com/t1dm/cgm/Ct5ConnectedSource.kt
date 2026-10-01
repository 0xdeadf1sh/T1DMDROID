package com.t1dm.cgm

import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/** 0x1002 is write-without-response, driven by notify/ladder timeout. Bind is irreversible. */
class Ct5ConnectedSource(
    override val descriptor: CgmSourceDescriptor,
    /** Identity; never the BLE address. */
    private val bsn: String,
    private val transport: Ct5GattTransport,
    private val session: Ct5Session,
    private val repository: CgmRepository,
    private val scope: CoroutineScope,
    /** `null` for a sensor this app has never bound or imported. */
    initialState: Ct5SensorState?,
    /** Advert's bound flag, null if unreported. Bound+no secret = another's sensor, unclaimable. */
    private val advertisedBound: Boolean? = null,
    private val dedup: DedupRing = DedupRing(),
    /** Frames from a stopped counter, indistinguishable to the id ring; spans an hour at 3min. */
    private val replayed: DedupRing = DedupRing(capacity = 24),
    private val gridStamper: GridStamper = GridStamper(),
    private val nonces: Ct5Nonces = SecureRandomCt5Nonces,
    private val tzOffsetMinFor: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Told when this session clears the sensor's secret, so the driver's cache can drop it. */
    private val onSecretReleased: (String) -> Unit = {},
    /** Every checksum-valid frame decoding to nothing physical; kept while the fault persists. */
    private val onUndecodable: (ByteArray) -> Unit = {},
    /** Told when 0x31 checkID accepts: proof of read access, distinct from a reading (driver). */
    private val onAuthenticated: suspend () -> Unit = {},
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) : ConnectedCgmSession {

    private enum class Phase {
        IDLE, CONNECTING, NEGOTIATING_MTU, DISCOVERING, ARMING_NOTIFY,

        /** No protocol deadline (held so it CAN bind), but a liveness one; see [budgetMs]. */
        AWAITING_BIND,

        // checkID first, alone: 0x03 before 0x31 draws no reply on hardware and the session dies.
        REJOIN_CHECKID,

        // 0x30/0x38 kept adjacent — failure between strands the sensor; both are the RESUME point.
        BIND_SETDATE, BIND_VERSION, BIND_SELFCHECK, BIND_QUERYSSN, BIND_SETID, BIND_SETPARAMS, BIND_INIT,

        LIVE, FAILED,
    }

    private val _status = MutableStateFlow(CgmSourceStatus.Idle)
    override val status: StateFlow<CgmSourceStatus> = _status.asStateFlow()

    private val _readings = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 64)
    override fun readings(): Flow<CgmReading> = _readings.asSharedFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    /** Display only; nothing here is stored. */
    private val _telemetry = MutableStateFlow<CgmSourceTelemetry?>(null)
    override val telemetry: StateFlow<CgmSourceTelemetry?> = _telemetry.asStateFlow()

    /** Set once after 0x3F is read; BIND-path only, so a rejoin never overwrites a tuned window. */
    private val _declaredWarmupWindowMin = MutableStateFlow<Int?>(null)
    override val declaredWarmupWindowMin: StateFlow<Int?> = _declaredWarmupWindowMin.asStateFlow()

    /** Set from [enter], cleared by [fail] and [close]. */
    private val _bindable = MutableStateFlow(false)
    override val bindable: StateFlow<Boolean> = _bindable.asStateFlow()

    /** Any pull, automatic or tapped; set by [beginBackfill], cleared when it ends or dies. */
    private val _backfillInFlight = MutableStateFlow(false)
    override val backfillInFlight: StateFlow<Boolean> = _backfillInFlight.asStateFlow()

    private val _historyExhausted = MutableStateFlow(false)
    override val historyExhausted: StateFlow<Boolean> = _historyExhausted.asStateFlow()

    /** Last time a push carried a new id; session-local, so a reconnect restarts the clock. */
    private var idAdvancedMs: Long = 0

    /** Bind anchor: past rated wear the id stops while sampling goes on; so does minFromStart. */
    private val _sensorStartMs = MutableStateFlow(initialState?.bindTimeMs)
    override val sensorStartMs: StateFlow<Long?> = _sensorStartMs.asStateFlow()

    @Volatile
    private var lastRssi: Int? = null

    /** WRITTEN by the collector; READ by watchdog too, hence volatile. Rest is collector-only. */
    @Volatile
    private var phase = Phase.IDLE
        set(value) {
            if (value != field) log.d(TAG, "phase $field → $value")
            field = value
        }

    /** Replaced by a successful bind; re-persisted if the bind instant is learned from a push. */
    @Volatile
    private var state: Ct5SensorState? = initialState
        set(value) {
            field = value
            _sensorStartMs.value = value?.bindTimeMs
        }

    val isBound: Boolean get() = state != null

    /** "Nothing said" counts bound: unclaimed misread could bind somebody's live wear. */
    private val claimedElsewhere: Boolean get() = advertisedBound != false

    /** The last thing that counted as PROTOCOL progress: a phase transition, or a push. */
    @Volatile
    private var lastProgressMs: Long = 0

    /** Link-alive: progress or RSSI. Keeps a silent-but-healthy link from stalling the watchdog. */
    @Volatile
    private var lastLinkMs: Long = 0

    /** Filed under receive instant past [sampleInstant]'s skew; logged once, counted always. */
    @Volatile
    private var skewFallbacks: Int = 0

    /** Consecutive records outside PLAUSIBLE_TEMP_CX100; reset by any good record. */
    private var implausibleRun: Int = 0

    /** Whether the key is INDEPENDENTLY confirmed; the 0x38 echo can't do it. See [plausible]. */
    private var keyConfirmed: Boolean = false

    private var watchdog: Job? = null

    /** Bind press to the collector; [requestBind] on MAIN can't touch [phase]. Cap 1, drop last. */
    private val bindRequests = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)

    /** Backfill tick; pause can't run on the collector, so a spare coroutine ticks it. Cap 1. */
    private val backfillTicks = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)

    private enum class PullRequest {
        /** Silent for IDLE_PULL_MS: reader asks the store, not waiting. Watchdog raises it. */
        IDLE,

        /** User asked for what the store holds the raw store lacks, back to retention floor. */
        GAPS,
    }

    /** Capacity one, dropping the newest, for the reason [bindRequests] is. */
    private val pullRequests = Channel<PullRequest>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)

    private sealed interface Input {
        @JvmInline value class Gatt(val event: Ct5GattEvent) : Input
        data object Bind : Input
        data object Backfill : Input
        @JvmInline value class Pull(val request: PullRequest) : Input
    }

    // Bind scratch, valid only inside one bind sequence.
    private var bindNonceA: ByteArray? = null
    private var bindNonceB: ByteArray? = null
    private var bindRandomId: String? = null
    private var bindIdentity: Ct5SensorIdentity? = null
    private var bindStartedMs: Long = 0

    /** What the in-flight 0x38 plants; a RESUMED bind reads these four from the secret. */
    private class Params(val kX100: Int, val rX100: Int, val randomId: String, val cipherId: Int)

    private var pending: Params? = null

    // Backfill fields below: collector-only, none volatile/locked. Exception: [state], volatile.

    /** Latched from probe-reply LENGTH: 165 B = 15 short or 11 voltage. Null until answered. */
    private var recordSize: Int? = null

    private var nextBackfillId: Int = 0

    private var batchInFlight: Boolean = false

    /** Pull done for now: [endBackfill] and refusals set it, [beginBackfill] clears it. */
    private var backfillOver: Boolean = false

    private var recovered: Int = 0

    /** Set by this session's first push, which opens the pull; one source per session. */
    private var backfillStarted: Boolean = false

    /** Anchor trust for pull-derived instants; set by [learnBindInstant] alone. Collector only. */
    private var anchorTrusted: Boolean = false

    /** The [lastProgressMs] an idle pull was raised for; one silence raises one. Watchdog only. */
    private var idlePulledFor: Long = -1L

    /** Re-pull for the slot a pull stopped short of; ends with the session scope. */
    private var repull: Job? = null

    /** Highest id a live push delivered; loaded at [goLive] so a repair-reset survives it. */
    private var highestLiveId: Int = 0


    private var cursorPersistedMs: Long = 0

    /** Reset by any batch that answers the range actually outstanding. */
    private var historyRetries: Int = 0

    /** Highest glucoseId slot accounted for; persisted via saveSourceCursor, not secret. */
    private var backfillCursor: Int = 0

    /** Opcode the pacer has in flight; [expects] checks a reply against it in LIVE, two queued. */
    private var outstanding: Int? = null

    /** ATT MTU negotiated, sizes history batches. Fallback default; over-sized batch truncates. */
    private var transportMtu: Int = Ct5Constants.FALLBACK_MTU

    /** The coordinator owns restart and reconnect policy. */
    override fun start() {
        _status.value = CgmSourceStatus.Scanning
        enter(Phase.CONNECTING)
        armWatchdog()
        // ONE collector for both inputs, so a bind press and a notification never race the machine.
        scope.launch {
            // A throw would end this coroutine and session silently; now an ordinary failure path.
            try {
                merge(
                    transport.events.map { Input.Gatt(it) },
                    bindRequests.receiveAsFlow().map { Input.Bind },
                    backfillTicks.receiveAsFlow().map { Input.Backfill },
                    pullRequests.receiveAsFlow().map { Input.Pull(it) },
                ).collect { onInput(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(TAG, "CT5 session machine threw", e)
                fail("unhandled ${e.javaClass.simpleName} in the session machine")
            }
        }
        scope.launch {
            while (isActive) {
                delay(RSSI_POLL_MS)
                transport.readRemoteRssi()
            }
        }
        transport.connect()
    }

    /** User's explicit IRREVERSIBLE bind, MAIN thread; never called by coordinator or reconnect. */
    override fun requestBind() {
        bindRequests.trySend(Unit)
    }

    /** MAIN thread, like [requestBind]; the collector runs every guard. */
    override fun requestBackfill() {
        pullRequests.trySend(PullRequest.GAPS)
    }

    private fun beginBind() {
        if (phase != Phase.AWAITING_BIND) {
            log.w(TAG, "bind ignored — phase is $phase, not AWAITING_BIND")
            return
        }
        if (state != null) {
            log.w(TAG, "bind ignored — this sensor is already bound")
            return
        }
        if (claimedElsewhere) {
            // Belt-and-braces: never reaches AWAITING_BIND; its absence would risk a live wear.
            log.w(TAG, "bind refused — the air says $bsn is claimed (flag=$advertisedBound) and no key is held")
            return
        }
        log.i(TAG, "bind requested by the user for $bsn")
        bindStartedMs = nowMs()
        bindNonceA = null
        bindNonceB = null
        bindRandomId = null
        bindIdentity = null
        pending = null
        enter(Phase.BIND_SETDATE)
        sendSetDate()
    }

    override fun close() {
        watchdog?.cancel()
        watchdog = null
        // Before the transport goes: an in-flight tick would otherwise write to a closed link.
        backfillOver = true
        batchInFlight = false
        _backfillInFlight.value = false
        transport.close()
        phase = Phase.IDLE
        lastRssi = null
        _rssi.value = null
        _bindable.value = false
    }

    /** UI only. The alarm path fires independently on a wall-clock tick. */
    override fun markSignalLost() {
        val running = _status.value
        if (running == CgmSourceStatus.Live ||
            running == CgmSourceStatus.Warmup ||
            running == CgmSourceStatus.Faulted
        ) {
            _status.value = CgmSourceStatus.SignalLost
        }
    }


    private suspend fun onInput(input: Input) = when (input) {
        is Input.Gatt -> onGattEvent(input.event)
        Input.Bind -> beginBind()
        Input.Backfill -> requestNextBatch()
        is Input.Pull -> onPull(input.request)
    }

    internal suspend fun onGattEvent(ev: Ct5GattEvent) {
        when (ev) {
            is Ct5GattEvent.Connection -> onConnection(ev)
            is Ct5GattEvent.MtuChanged -> onMtu(ev)
            is Ct5GattEvent.ServicesDiscovered -> onServices(ev)
            is Ct5GattEvent.NotifyEnabled -> onNotifyEnabled(ev)
            is Ct5GattEvent.Notify -> decodingChunk(ev.rx) { onNotify(ev.value) }
            is Ct5GattEvent.LadderExhausted -> onLadderExhausted(ev.opcode)
            is Ct5GattEvent.Rssi -> if (ev.ok) {
                lastRssi = ev.dbm
                _rssi.value = ev.dbm
                // A read that came BACK is idle-link evidence; a failed one lets the clock run out.
                lastLinkMs = nowMs()
            }
            is Ct5GattEvent.Failure -> fail("transport error: ${ev.reason}")
        }
    }

    private fun onConnection(ev: Ct5GattEvent.Connection) {
        if (!ev.connected) {
            // SignalLost UNCONDITIONALLY: arms while Scanning; coordinator awaits it to reconnect.
            if (phase != Phase.IDLE && phase != Phase.FAILED) {
                log.w(TAG, "disconnected during $phase (status=${ev.statusCode})")
                fail("disconnected during $phase (status=${ev.statusCode})")
            }
            return
        }
        if (!ev.statusOk) {
            fail("connection error (status=${ev.statusCode})")
            return
        }
        // MTU FIRST, not discovery: the identity reply does not fit the default MTU.
        enter(Phase.NEGOTIATING_MTU)
        if (!transport.requestMtu(Ct5Constants.REQUESTED_MTU)) {
            // Not fatal: a refused request may still carry enough MTU; the bind's checks decide.
            log.w(TAG, "requestMtu rejected; continuing to discovery")
            beginDiscovery()
        }
    }

    private fun onMtu(ev: Ct5GattEvent.MtuChanged) {
        if (phase != Phase.NEGOTIATING_MTU) return
        if (ev.ok && ev.mtu > transportMtu) transportMtu = ev.mtu
        // Below this a bind can't read K; fails closed (truncated body fails the identity grammar).
        if (!ev.ok || ev.mtu < MIN_USEFUL_MTU) {
            log.w(TAG, "MTU is ${ev.mtu} (ok=${ev.ok}); a bind needs at least $MIN_USEFUL_MTU")
        }
        beginDiscovery()
    }

    private fun beginDiscovery() {
        enter(Phase.DISCOVERING)
        if (!transport.discoverServices()) fail("discoverServices() rejected")
    }

    private fun onServices(ev: Ct5GattEvent.ServicesDiscovered) {
        if (phase != Phase.DISCOVERING) return
        if (!ev.ok || !ev.hasCt5Service) {
            fail("service discovery failed (ok=${ev.ok}, service=${ev.hasCt5Service})")
            return
        }
        // Notifications are armed BEFORE any command, because every reply to every command is one.
        enter(Phase.ARMING_NOTIFY)
        if (!transport.setNotify(true)) fail("enable notify rejected")
    }

    private fun onNotifyEnabled(ev: Ct5GattEvent.NotifyEnabled) {
        if (phase != Phase.ARMING_NOTIFY) return
        if (!ev.ok) {
            fail("CCCD write failed")
            return
        }
        if (state == null) {
            if (claimedElsewhere) {
                // Claimed+unreadable: a held link pins forever; hand back now, covers UNKNOWN too.
                fail("the air says $bsn is claimed (flag=$advertisedBound) and no key is held — nothing to read or claim")
                return
            }
            // The link is HELD here so the user can bind; nothing happens on its own.
            log.i(TAG, "$bsn is unbound — holding the link and awaiting an explicit bind")
            enter(Phase.AWAITING_BIND)
            return
        }
        sendCheckId()
    }

    private suspend fun onNotify(frame: ByteArray) {
        if (frame.isEmpty()) return
        val opcode = frame[0].toInt() and 0xFF

        // A push can arrive ANY time, mid-bind too, never the awaited reply; handled first always.
        if (opcode == Ct5Constants.Opcode.PUSH) {
            onPush(frame)
            return
        }
        if (!expects(opcode)) {
            // Ladder retries up to 5x; a late reply is ignored, keeps 0x30 from re-deriving key.
            log.d(TAG, "ignored 0x${opcode.toString(16)} in phase $phase")
            return
        }
        transport.acknowledge()
        when (phase) {
            Phase.REJOIN_CHECKID -> onCheckId(frame)
            Phase.BIND_SETDATE -> onBindSetDate(frame)
            Phase.BIND_VERSION -> onBindVersion(frame)
            Phase.BIND_SELFCHECK -> onBindSelfCheck(frame)
            Phase.BIND_QUERYSSN -> onBindQuerySsn(frame)
            Phase.BIND_SETID -> onBindSetId(frame)
            Phase.BIND_SETPARAMS -> onBindSetParameters(frame)
            Phase.BIND_INIT -> onBindInit(frame)
            Phase.LIVE -> if (opcode == Ct5Constants.Opcode.PULL_HISTORY) onHistory(frame)
            else -> {}
        }
    }

    private fun expects(opcode: Int): Boolean = when (phase) {
        // 0x03 draws 0x04; LIVE expects it so the cosmetic setDate ladder stops, no-op.
        Phase.BIND_SETDATE ->
            opcode == Ct5Constants.Opcode.SET_DATE || opcode == Ct5Constants.Opcode.SET_DATE_REPLY
        // LIVE queues two frames; a reply must match what's outstanding, or a ladder never fires.
        Phase.LIVE -> when (outstanding) {
            Ct5Constants.Opcode.SET_DATE ->
                opcode == Ct5Constants.Opcode.SET_DATE || opcode == Ct5Constants.Opcode.SET_DATE_REPLY
            Ct5Constants.Opcode.PULL_HISTORY ->
                opcode == Ct5Constants.Opcode.PULL_HISTORY && batchInFlight
            else -> false
        }
        Phase.REJOIN_CHECKID -> opcode == Ct5Constants.Opcode.CHECK_ID
        Phase.BIND_VERSION -> opcode == Ct5Constants.Opcode.VERSION
        Phase.BIND_SELFCHECK -> opcode == Ct5Constants.Opcode.SELF_CHECK
        Phase.BIND_QUERYSSN -> opcode == Ct5Constants.Opcode.QUERY_SSN
        Phase.BIND_SETID -> opcode == Ct5Constants.Opcode.SET_ID
        Phase.BIND_SETPARAMS -> opcode == Ct5Constants.Opcode.SET_PARAMETERS
        Phase.BIND_INIT -> opcode == Ct5Constants.Opcode.INIT
        else -> false
    }

    private fun onLadderExhausted(opcode: Int) {
        // A no-reply history request ends only the pull; cursor persists, next rejoin resumes.
        if (opcode == Ct5Constants.Opcode.PULL_HISTORY) {
            batchInFlight = false
            endBackfill("0x37 drew no reply")
            return
        }
        if (phase == Phase.LIVE || phase == Phase.AWAITING_BIND || phase == Phase.IDLE) return
        fail("0x${opcode.toString(16)} drew no reply in $phase")
    }


    /** 0x31 checkID, first rejoin frame, the only one answered cold; carries B only. */
    private fun sendCheckId() {
        val b = state?.b ?: run { fail("no persisted nonce to check in"); return }
        val f = session.buildCheckId(b) ?: run { fail("checkID could not be built"); return }
        enter(Phase.REJOIN_CHECKID)
        send(f)
    }

    private suspend fun onCheckId(frame: ByteArray) {
        val st = state ?: run { fail("checkID reply arrived with no stored identity"); return }
        when (session.parseCheckIdResponse(frame)) {
            Ct5BindVerdict.ACCEPTED -> log.i(TAG, "checkID accepted for $bsn")
            // Positively refused: the sensor does not know this B.
            Ct5BindVerdict.REJECTED -> {
                if (!st.initialised) {
                    // Unplanted blob is a password for nothing; keeping it blocks bind forever.
                    log.w(TAG, "the sensor never took the unfinished bind for $bsn — releasing the key")
                    repository.clearSensorSecret(descriptor.id)
                    // The driver's own cache of "a secret is held" can't see this happen.
                    onSecretReleased(bsn)
                    state = null
                    // Storage suspends; the session may have failed meanwhile under a dead link.
                    if (phase != Phase.REJOIN_CHECKID) return
                    if (claimedElsewhere) {
                        fail("$bsn refused the stored identity and the air does not say it is unclaimed")
                    } else {
                        enter(Phase.AWAITING_BIND)
                    }
                    return
                }
                // A finished bind's secret is the real unbind password; kept, user must rebind.
                fail("the sensor refused the stored identity for $bsn — it needs rebinding")
                return
            }
            // Ambiguous verdict is never positive; a wrong key surfaces later in plausible().
            Ct5BindVerdict.AMBIGUOUS ->
                log.w(TAG, "checkID verdict unreadable (${frame.size} B); listening anyway")
        }
        if (!st.initialised) {
            // 0x30 landed but 0x38/0x06 didn't; resume 0x38 with same params or nothing reports.
            log.w(TAG, "resuming the unfinished bind for $bsn from setParameters")
            sendSetParameters(st.kX100, st.rX100, st.randomId, st.cipherId)
            return
        }
        goLive()
    }


    private fun onBindSetDate(frame: ByteArray) {
        if (!session.parseSetDateResponse(frame)) {
            fail("setDate reply rejected during bind")
            return
        }
        enter(Phase.BIND_VERSION)
        send(session.buildVersionRequest())
    }

    private fun onBindVersion(frame: ByteArray) {
        // Informational, no checksum slot; wrong length means the link can't carry a bind.
        val v = session.parseVersion(frame)
        if (v == null) {
            fail("version reply was ${frame.size} bytes, not 14")
            return
        }
        log.i(TAG, "transmitter ${v.version} algorithm ${v.algorithm} built ${v.year}-${v.month}-${v.day}")
        enter(Phase.BIND_SELFCHECK)
        send(session.buildSelfCheck())
    }

    private fun onBindSelfCheck(frame: ByteArray) {
        // The last cheap check before the irreversible writes.
        if (!session.parseSelfCheck(frame)) {
            fail("self-check reply was ${frame.size} bytes or failed its checksum")
            return
        }
        enter(Phase.BIND_QUERYSSN)
        send(session.buildQuerySsn())
    }

    private fun onBindQuerySsn(frame: ByteArray) {
        // PLAINTEXT on an unbound sensor -- what makes this bind possible with no captured secret.
        val id = session.parseSsnResponse(frame, Ct5Session.NO_CIPHER_ID)
        if (id == null) {
            fail("the sensor's identity string did not decode (${frame.size} B)")
            return
        }
        // Checked before any write; K=0 is legal but unusable -- glucose is proportional to Iw/K.
        if (id.kX100 <= 0) {
            fail("the sensor's identity string decodes to K = 0, which cannot be used")
            return
        }
        log.i(TAG, "identity ${id.ssn} → K=${id.kX100}/100 R=${id.rX100}/100 lifetime=${id.lifeTime}")
        bindIdentity = id
        // Only place the lifetime code appears; unpublished, the 2 extended-warmup codes are dead.
        _declaredWarmupWindowMin.value = Ct5Constants.warmupWindowMinFor(id.lifeTime)

        val a = nonces.nonce()
        val b = nonces.nonce()
        val f = session.buildSetId(b, a) ?: run { fail("setID could not be built"); return }
        bindNonceA = a
        bindNonceB = b
        bindRandomId = nonces.randomId()
        enter(Phase.BIND_SETID)
        send(f)
    }

    private suspend fun onBindSetId(frame: ByteArray) {
        val a = bindNonceA
        val b = bindNonceB
        val randomId = bindRandomId
        val id = bindIdentity
        if (a == null || b == null || randomId == null || id == null) {
            fail("setID reply arrived with no bind in progress")
            return
        }
        // Length and nonce validated before key derivation; vendor checks neither, no replay.
        val cipherId = session.cipherIdFromSetIdReply(frame, a, b)
        if (cipherId == null) {
            fail("setID reply rejected (${frame.size} B) — no key derived, nothing persisted")
            return
        }
        // Persists before 0x38, marked unfinished: RANDOM_ID is the unbind password 0x38 plants.
        val fresh = Ct5SensorState(
            cipherId = cipherId,
            a = a,
            b = b,
            randomId = randomId,
            kX100 = id.kX100,
            rX100 = id.rX100,
            ssn = id.ssn,
                // Provisional; refined from the first push.
            bindTimeMs = bindStartedMs,
            initialised = false,
        )
        // Sealing (Keystore+SQLite) can throw; uncaught it kills the collector, 0x38 unsent.
        if (!persistSecret(fresh)) {
            fail("the bind secret could not be stored — stopping before setParameters")
            return
        }
        state = fresh
        // Sealing may outlast the step watchdog; phase re-read before the irreversible send.
        if (phase != Phase.BIND_SETID) {
            log.w(TAG, "setID persisted but the session left BIND_SETID (now $phase); not writing 0x38")
            return
        }
        log.i(TAG, "setID accepted; secret persisted for $bsn before setParameters")
        sendSetParameters(id.kX100, id.rX100, randomId, cipherId)
    }

    private fun sendSetParameters(kX100: Int, rX100: Int, randomId: String, cipherId: Int) {
        val f = session.buildSetParameters(
            kX100 = kX100,
            rX100 = rX100,
            intervalMin = Ct5Constants.SAMPLE_INTERVAL_MIN,
            cycleDays = Ct5Constants.CYCLE_DAYS,
            randomId = randomId,
            cipherId = cipherId,
        ) ?: run { fail("setParameters could not be built"); return }
        pending = Params(kX100, rX100, randomId, cipherId)
        enter(Phase.BIND_SETPARAMS)
        send(f)
    }

    private fun onBindSetParameters(frame: ByteArray) {
        val p = pending
        if (p == null) {
            fail("setParameters reply arrived with no bind in progress")
            return
        }
        // Verifies the echo (12B intact, not key agreement); plausible() proves agreement.
        val ok = session.verifySetParametersEcho(
            reply = frame,
            kX100 = p.kX100,
            rX100 = p.rX100,
            intervalMin = Ct5Constants.SAMPLE_INTERVAL_MIN,
            cycleDays = Ct5Constants.CYCLE_DAYS,
            randomId = p.randomId,
            cipherId = p.cipherId,
        )
        if (!ok) {
            fail("setParameters echo did not match what was sent — stopping before init")
            return
        }
        enter(Phase.BIND_INIT)
        send(session.buildInit())
    }

    private suspend fun onBindInit(frame: ByteArray) {
        if (!session.parseInitResponse(frame)) {
            fail("init reply rejected")
            return
        }
        // Activation success signal; recording it stops the next reconnect re-sending 0x38.
        val st = state
        if (st != null && !st.initialised) {
            val done = st.copy(initialised = true)
            // Not fatal here (unlike pre-0x38): only the flag is lost, re-plants K/R next connect.
            state = if (persistSecret(done)) done else st
            // As in onBindSetId: storage suspends, watchdog may have failed the session under this.
            if (phase != Phase.BIND_INIT) return
        }
        log.i(TAG, "bind complete for $bsn — the sensor is running")
        bindNonceA = null
        bindNonceB = null
        bindRandomId = null
        bindIdentity = null
        pending = null
        // 0x0F lowPower deliberately NOT sent: a go-idle hint for a client that drops the link.
        goLive()
    }

    private suspend fun goLive() {
        enter(Phase.LIVE)
        // Settled here: a link dying before the first push is loss-of-signal, not unreadable.
        onAuthenticated()
        log.i(TAG, "session LIVE for $bsn — awaiting pushes every ${Ct5Constants.SAMPLE_INTERVAL_MS / 1000} s")
        // Cosmetic, clock never read back; sent after LIVE so its ladder timeout is a no-op.
        sendSetDate()
        // Pull not started here: instants derive from bindTimeMs, proven by the first push.
        backfillStarted = false
        anchorTrusted = false
        idAdvancedMs = nowMs()
        highestLiveId = 0
        // Before the first push: a fresh dedup ring would re-anchor on every reconnect.
        backfillCursor = repository.loadSourceCursor(descriptor.id)
        // Not the cursor: a repair resets that, but the guard still needs delivered ids.
        highestLiveId = repository.loadHighestDeliveredId(descriptor.id)
    }

    /** Reopens the pull; refused mid-batch or pre-first-push. GAPS rewinds to the lowest gap. */
    private suspend fun onPull(request: PullRequest) {
        val st = state ?: return
        if (phase != Phase.LIVE || batchInFlight || !backfillStarted) {
            log.i(TAG, "$request pull for $bsn ignored — phase=$phase inFlight=$batchInFlight pushed=$backfillStarted")
            return
        }
        if (request == PullRequest.GAPS) {
            val lowest = lowestMissingId(st)
            if (lowest == null) {
                log.i(TAG, "history for $bsn: nothing missing back to the retention floor")
                return
            }
            log.i(TAG, "history for $bsn: the store lacks sample $lowest; pulling from there")
            trimCursorTo(lowest - 1)
        }
        beginBackfill(liveId = liveCeiling(st), anchorTrusted = anchorTrusted)
    }

    /** Lowest missing sample id (starts at 1), or null if none; dropped records are re-asked. */
    private suspend fun lowestMissingId(st: Ct5SensorState): Int? {
        val held = repository.receivedSampleMinutes(descriptor.id)
        val ceiling = liveCeiling(st)
        val behindFloorMs = held.completeSinceMs - st.bindTimeMs
        val floor = if (behindFloorMs <= 0) {
            1
        } else {
            ((behindFloorMs + Ct5Constants.SAMPLE_INTERVAL_MS - 1) / Ct5Constants.SAMPLE_INTERVAL_MS)
                .coerceIn(1L, ceiling.toLong() + 1)
                .toInt()
        }
        for (id in floor..ceiling) {
            if (id * Ct5Constants.SAMPLE_INTERVAL_MIN !in held.minutes) return id
        }
        return null
    }



    /** Recovers samples via a 1-record probe; writes storage not readings(), skips LOS clearing. */
    private suspend fun beginBackfill(liveId: Int, anchorTrusted: Boolean, liveAdvanced: Boolean = true) {
        val st = state ?: return
        backfillCursor = repository.loadSourceCursor(descriptor.id)
        // Catches a stale cursor before any read; a stopped counter's repeat disproves nothing.
        if (liveAdvanced && backfillCursor > liveId) rewindCursor(liveId - 1)
        if (!anchorTrusted) {
            // Unrepaired anchor: unlike live, a recovered sample has no receive-time fallback.
            log.w(TAG, "backfill for $bsn refused — the anchor could not be trusted at sample $liveId")
            backfillOver = true
            return
        }
        recordSize = null
        batchInFlight = false
        backfillOver = false
        recovered = 0
        historyRetries = 0
        nextBackfillId = backfillCursor + 1
        val ceiling = liveCeiling(st)
        if (nextBackfillId > ceiling) {
            // Already level with the wear: nothing behind the live stream to fetch.
            backfillOver = true
            return
        }
        log.i(TAG, "backfill for $bsn: probing from sample $nextBackfillId (about $ceiling taken so far)")
        _backfillInFlight.value = true
        sendHistoryRequest(count = 1)
    }

    /** Highest plausible sample id, a ceiling not a terminator; bound by id space, not rated. */
    private fun liveCeiling(st: Ct5SensorState): Int {
        val elapsed = nowMs() - st.bindTimeMs
        if (elapsed <= 0) return 0
        return (elapsed / Ct5Constants.SAMPLE_INTERVAL_MS)
            .coerceAtMost(Ct5Constants.MAX_SAMPLE_ID.toLong())
            .toInt()
    }

    /** Highest slot settled; a pull may walk past it, never records above what's delivered. */
    private fun accountableCeiling(st: Ct5SensorState): Int =
        if (highestLiveId > 0) highestLiveId else liveCeiling(st)

    /** Put one `0x37` on the wire, [count] records from [nextBackfillId]. */
    private fun sendHistoryRequest(count: Int) {
        if (backfillOver || batchInFlight || phase != Phase.LIVE) return
        val frame = session.buildPullHistory(nextBackfillId, count) ?: run {
            endBackfill("a pull-history frame could not be built for sample $nextBackfillId")
            return
        }
        batchInFlight = true
        send(frame)
    }

    /** Re-checked, not trusted: the tick is up to 1s stale, session may have moved on. */
    private fun requestNextBatch() {
        val size = recordSize ?: return
        sendHistoryRequest(session.historyBatchSize(transportMtu, size))
    }

    /** Batches keep the negotiated MTU's size; an unparsed one is re-asked a record at a time. */
    private suspend fun onHistory(frame: ByteArray) {
        batchInFlight = false
        val st = state ?: run { endBackfill("no key held"); return }

        val size = recordSize ?: session.historyRecordSize(frame.size)?.also {
            recordSize = it
            log.i(TAG, "backfill for $bsn: this sensor speaks the $it-byte record")
        } ?: run {
            // Guessing the dialect re-slices a batch into records that were never sent.
            endBackfill("a ${frame.size}-byte probe reply names no record dialect")
            return
        }

        val batch = session.parseHistory(frame, st.cipherId, size) ?: run {
            // Likely an over-size-MTU truncation (MTU unreliable downward); retry 1 record.
            if (historyRetries < MAX_HISTORY_RETRIES) {
                historyRetries++
                log.w(TAG, "a ${frame.size}-byte batch did not parse; re-asking $nextBackfillId one record at a time")
                sendHistoryRequest(count = 1)
            } else {
                endBackfill("a ${frame.size}-byte history batch did not parse")
            }
            return
        }
        log.dec(
            null,
            "history from ${batch.startId}: ${batch.samples.size}/${batch.slots} slots" +
                (if (batch.endOfHistory) ", end of store" else "") +
                batch.samples.joinToString(prefix = " · ", separator = " · ") { describe(it) },
            CgmLogTopic.HISTORY,
        )
        // Not lastProgressMs: a history reply proves the link, not the sensor, alive.
        lastLinkMs = nowMs()

        if (batch.startId != nextBackfillId) {
            // Never filed (instants derive from id); the two directions want opposite handling.
            if (batch.startId < nextBackfillId) {
                // Late duplicate: acking it cancelled the queued ladder; re-ask restores one.
                if (historyRetries >= MAX_HISTORY_RETRIES) {
                    endBackfill("sample $nextBackfillId was overtaken by duplicates $historyRetries times")
                    return
                }
                historyRetries++
                log.d(
                    TAG,
                    "a duplicate batch at ${batch.startId} cancelled the request for $nextBackfillId; re-asking",
                    CgmLogTopic.HISTORY,
                )
                requestNextBatch()
                return
            }
            endBackfill("asked for sample $nextBackfillId and was answered ${batch.startId}")
            return
        }
        historyRetries = 0

        val unfilable = firstUnfilable(st, batch)
        val filable = if (unfilable == null) batch.samples else batch.samples.filter { it.glucoseId < unfilable }
        for (sample in filable) storeRecovered(st, sample)
        recovered += filable.size

        if (unfilable != null) {
            // Consumed, it would be lost: nothing automatic asks for it again.
            nextBackfillId = unfilable
            persistCursor(unfilable - 1, force = true)
            repullOnceFilable(slotInstant(st, unfilable))
            endBackfill("sample $unfilable is too recent to file")
            return
        }

        val storeEnd = firstBlankPastDelivered(batch)
        if (storeEnd != null) {
            // Past rated wear the store stops at the stopped id while the bind clock runs on.
            nextBackfillId = storeEnd
            persistCursor(storeEnd - 1, force = true)
            endBackfill("the sensor's store ends at sample ${storeEnd - 1}")
            return
        }

        // Slots, not samples: an empty-answered gap must be consumed or re-asked forever.
        if (batch.slots > 0) {
            nextBackfillId = batch.startId + batch.slots
            persistCursor(nextBackfillId - 1, force = true)
        }

        if (batch.endOfHistory) {
            endBackfill("the sensor's store ends at sample ${nextBackfillId - 1}")
            return
        }
        if (batch.slots == 0) {
            // Legal-but-empty, no terminator: can't advance blind or re-ask (loops).
            endBackfill("sample $nextBackfillId was answered with an empty batch")
            return
        }
        val ceiling = liveCeiling(st)
        if (nextBackfillId > ceiling) {
            // Fixed runs overshoot the present; future slots would strand the cursor forever.
            trimCursorTo(ceiling)
            endBackfill("caught up with the live stream at sample $backfillCursor")
            return
        }

        // Paced: a wear-long pull is ~250 round trips sharing the link with the alarm's 0x35.
        scope.launch {
            delay(Ct5Constants.HISTORY_BATCH_GAP_MS)
            backfillTicks.trySend(Unit)
        }
    }

    /** Live path's gates minus 3: recoveredInstant, no readings() emit, no implausibleRun count. */
    private suspend fun storeRecovered(st: Ct5SensorState, sample: Ct5PushSample) {
        if (!plausible(sample)) return
        val minFromStart = sample.glucoseId * Ct5Constants.SAMPLE_INTERVAL_MIN
        val bgMgdl = session.glucoseMgdl(sample, st.kX100)
        val flag = flagFor(sample, minFromStart, bgMgdl)
        if (flag == ReadingFlag.INVALID) return
        val sampleMs = recoveredInstant(st, sample.glucoseId) ?: return

        repository.upsertReading(
            CgmReading(
                sourceId = descriptor.id,
                tsMs = gridStamper.snap(sampleMs),
                bgMgdl = bgMgdl,
                // The record carries a trend CODE, a 4-bit enum, not a rate.
                trendTenthsPerMin = null,
                minFromStart = minFromStart,
                quality = null,
                provenance = ReadingProvenance.MEASURED,
                flag = flag,
                // §2: east-positive minutes at event time, may be the other side of a DST change.
                tzOffsetMin = tzOffsetMinFor(sampleMs),
                rxWallMs = sampleMs,
                // The link this arrived over says nothing about signal at the sample's own moment.
                rssi = null,
                // The sensor's own clock, hours before receipt on a history read.
                measuredAtMs = sampleMs,
            ),
        )
    }

    /** bindTimeMs + glucoseId*SAMPLE_INTERVAL_MS, not sampleInstant; fails closed if too recent. */
    private fun recoveredInstant(st: Ct5SensorState, glucoseId: Int): Long? =
        slotInstant(st, glucoseId).takeUnless { tooRecent(it) }

    private fun slotInstant(st: Ct5SensorState, glucoseId: Int): Long =
        st.bindTimeMs + glucoseId * Ct5Constants.SAMPLE_INTERVAL_MS

    /** The window a live reading owns; [recoveredInstant] refuses anything inside it. */
    private fun tooRecent(slotMs: Long): Boolean = nowMs() - slotMs <= Ct5Constants.MAX_CLOCK_SKEW_MS

    /** First sampled slot no push delivered and [recoveredInstant] refuses; null if none. */
    private fun firstUnfilable(st: Ct5SensorState, batch: Ct5HistoryBatch): Int? {
        val last = minOf(batch.startId + batch.slots - 1, liveCeiling(st))
        return (batch.startId..last).firstOrNull { !dedup.contains(it) && tooRecent(slotInstant(st, it)) }
    }

    /** Blank slot above every id a push delivered; blanks below are the vendor's interior gaps. */
    private fun firstBlankPastDelivered(batch: Ct5HistoryBatch): Int? {
        if (highestLiveId <= 0) return null
        val held = batch.samples.mapTo(HashSet()) { it.glucoseId }
        return (batch.startId until batch.startId + batch.slots)
            .firstOrNull { it > highestLiveId && it !in held }
    }

    private fun repullOnceFilable(slotMs: Long) {
        repull?.cancel()
        repull = scope.launch {
            delay(slotMs + Ct5Constants.MAX_CLOCK_SKEW_MS + 1 - nowMs())
            pullRequests.trySend(PullRequest.IDLE)
        }
    }

    /** Stop the pull for this session. The next rejoin resumes from the persisted cursor. */
    private fun endBackfill(why: String) {
        _backfillInFlight.value = false
        if (backfillOver) return
        backfillOver = true
        batchInFlight = false
        if (recovered > 0) {
            log.i(TAG, "backfill for $bsn recovered $recovered samples — $why")
        } else {
            log.i(TAG, "backfill for $bsn recovered nothing — $why")
        }
    }

    /** Persists per batch during a pull (~250 trips), rate-limited once live is advancing it. */
    private suspend fun persistCursor(cursor: Int, force: Boolean) {
        if (cursor <= backfillCursor || cursor > 0xFFFF) return
        // In-memory cursor always moves, only the write is rationed; guard needs cursor+1 exactly.
        backfillCursor = cursor
        val now = nowMs()
        if (!force && now - cursorPersistedMs < CURSOR_PERSIST_INTERVAL_MS) return
        cursorPersistedMs = now
        // Stored value clamped to the wear (memory isn't); a stored cursor ahead can't self-heal.
        val stored = state?.let { minOf(cursor, accountableCeiling(it)) } ?: cursor
        repository.saveSourceCursor(descriptor.id, stored)
    }

    /** Rewinds cursor to what a push just disproved; unconditional, unlike persistCursor. */
    private suspend fun rewindCursor(to: Int) {
        val target = to.coerceAtLeast(0)
        if (target >= backfillCursor) return
        log.w(TAG, "cursor for $bsn was ahead of the live stream at $backfillCursor — rewound to $target")
        trimCursorTo(target)
    }

    /** Write half of rewindCursor and the pull's own trim; unlike persistCursor, moves down. */
    private suspend fun trimCursorTo(cursor: Int) {
        if (cursor >= backfillCursor) return
        backfillCursor = cursor
        cursorPersistedMs = nowMs()
        repository.saveSourceCursor(descriptor.id, cursor)
    }


    private suspend fun onPush(frame: ByteArray) {
        if (phase == Phase.FAILED || phase == Phase.IDLE) {
            // A failed session's status was already read; Live here would contradict it.
            return
        }
        val st = state
        if (st == null) {
            log.d(TAG, "push ignored — no key held for $bsn")
            return
        }
        val rxMs = nowMs()
        // Checksum-valid = progress regardless of the gate; watchdog fed before any drop.
        val push = session.parsePush(frame, st.cipherId)
        if (push == null) {
            log.w(TAG, "push rejected (${frame.size} B) — length, opcode or checksum")
            return
        }
        lastProgressMs = rxMs
        lastLinkMs = rxMs
        val alreadyDelivered = highestLiveId
        val liveAdvanced = push.glucoseId > alreadyDelivered
        log.dec(null, "push ${describe(push)}", CgmLogTopic.GLUCOSE)
        if (liveAdvanced && alreadyDelivered > 0 && push.glucoseId > alreadyDelivered + 1) {
            val missed = push.glucoseId - alreadyDelivered - 1
            log.w(TAG, "counter jumped $alreadyDelivered → ${push.glucoseId}: $missed missed", CgmLogTopic.GLUCOSE)
        }
        if (push.glucoseId > highestLiveId) {
            highestLiveId = push.glucoseId
            repository.saveHighestDeliveredId(descriptor.id, push.glucoseId)
        }
        if (liveAdvanced) {
            idAdvancedMs = rxMs
            _historyExhausted.value = false
        } else if (rxMs - idAdvancedMs > Ct5Constants.COUNTER_STOPPED_MS && !_historyExhausted.value) {
            log.w(TAG, "counter for $bsn stopped at ${push.glucoseId}; the sensor stores nothing newer")
            _historyExhausted.value = true
        }

        // Unacked push kills the link at 360.3s (firmware timer); bypasses send/outstanding.
        if (phase == Phase.LIVE) {
            transport.sendPaced(
                session.buildPushAck(),
                Ct5Constants.PUSH_ACK_LADDER_MS,
                expectReply = false,
            )
        }
        if (phase != Phase.LIVE && phase != Phase.AWAITING_BIND) {
            // Pushes interleave into a bind; stored but must not disturb the sequence.
            log.d(TAG, "push during $phase", CgmLogTopic.GLUCOSE)
        }
        // Stopped counter repeats one id; dedup on frame bytes so §3.6-A LOS isn't refreshed.
        val staleCounter = push.glucoseId <= backfillCursor
        if (staleCounter) {
            val fingerprint = frame.contentHashCode()
            if (replayed.contains(fingerprint)) {
                log.w(TAG, "push ${push.glucoseId} is a byte-identical repeat; not a new measurement", CgmLogTopic.GLUCOSE)
                return
            }
            replayed.record(fingerprint)
        } else {
            if (dedup.contains(push.glucoseId)) {
                log.d(TAG, "push ${push.glucoseId} already delivered", CgmLogTopic.GLUCOSE)
                return
            }
            dedup.record(push.glucoseId)
        }

        // Checksum-over-ciphertext proves the key; what fails is neither stored nor shown.
        if (!plausible(push)) {
            implausibleRun++
            log.w(
                TAG,
                "push ${push.glucoseId} decoded to ${push.tempCx100 / 100} C — implausible " +
                    "($implausibleRun in a row); the key may not be the sensor's",
            )
            // Obfuscated, as received: an 8-bit key, so these bytes are what a search runs over.
            log.w(TAG, "push ${push.glucoseId} as received: ${frame.joinToString("") { "%02X".format(it) }}")
            onUndecodable(frame.copyOf())
            if (implausibleRun >= Ct5Constants.MAX_IMPLAUSIBLE_RECORDS) {
                fail("$implausibleRun consecutive records did not decode to anything physical")
            }
            return
        }
        implausibleRun = 0
        if (!keyConfirmed) {
            keyConfirmed = true
            log.i(TAG, "key confirmed for $bsn by a record that decodes to ${push.tempCx100 / 100} C")
        }

        // The anchor may be corrected by this push; this sample derives from the corrected value.
        val anchored = learnBindInstant(st, push.glucoseId, rxMs, alreadyDelivered)

        // Pull opens on the first push: earliest point the anchor is proven against delivery.
        if (!backfillStarted && phase == Phase.LIVE) {
            backfillStarted = true
            beginBackfill(liveId = push.glucoseId, anchorTrusted = anchorTrusted, liveAdvanced = liveAdvanced)
        }

        // MINUTES since start, not the sample index: every consumer reads the field as minutes.
        val minFromStart = push.glucoseId * Ct5Constants.SAMPLE_INTERVAL_MIN
        val sampleMs = sampleInstant(anchored, push.glucoseId, rxMs)
        // §2: east-positive minutes at event (sample) time, not delivery; never shifts it.
        val tzOffsetMin = tzOffsetMinFor(sampleMs)

        // Before the value gate, for every checksum-valid record: non-zero error means withheld.
        _telemetry.value = CgmSourceTelemetry(
            sampledAtMs = sampleMs,
            tempCx100 = push.tempCx100,
            batteryRaw = push.batteryRaw,
            iwX100 = push.iwX100,
            ibX100 = push.ibX100,
            electrodesMv = push.electrodesMv,
            errorCode = push.errorCode,
            trendCode = push.trendCode,
        )

        // Above the gate: an answered slot counts regardless of reading, strictly next slot.
        if (backfillCursor > push.glucoseId) {
            if (liveAdvanced) rewindCursor(push.glucoseId - 1)
        } else if (push.glucoseId == backfillCursor + 1) {
            persistCursor(push.glucoseId, force = false)
        }

        val bgMgdl = session.glucoseMgdl(push, anchored.kX100)
        val flag = flagFor(push, minFromStart, bgMgdl)
        if (flag == ReadingFlag.INVALID) {
            // Dropped so staleness keeps ticking: fault reports aren't readings, link is fine.
            if (phase != Phase.FAILED && phase != Phase.IDLE) _status.value = CgmSourceStatus.Faulted
            log.dec(
                TAG,
                "push ${push.glucoseId} dropped (error=${push.errorCode} bg=${push.glucoseMgdl})",
                CgmLogTopic.GLUCOSE,
                level = CgmLogLevel.W,
            )
            // A stopped counter repeats one stored record; a sampling one moves these values.
            log.i(
                TAG,
                "push ${push.glucoseId} channels: T=${push.tempCx100} Iw=${push.iwX100} Ib=${push.ibX100} " +
                    "batt=${push.batteryRaw} mV=${push.electrodesMv?.joinToString("/")} trend=${push.trendCode}",
            )
            return
        }

        // Counterpart to the drop log: without it, a working sensor looks silent in the log.
        log.dec(
            TAG,
            "push ${push.glucoseId} stored $bgMgdl mg/dL ($flag, Iw=${push.iwX100}, T=${push.tempCx100})",
            CgmLogTopic.GLUCOSE,
            value = bgMgdl,
        )

        // Stores the frame obfuscated; stays decodable since CIPHER_ID is kept beside it.
        repository.insertRawAdvert(
            sourceId = descriptor.id,
            // Frame arrival time, not sample time: answers only "bytes reached the phone".
            rxWallMs = rxMs,
            rssi = lastRssi,
            payload = frame,
            crcValid = true,
            minFromStart = minFromStart,
        )
        val readings = gridStamper.stamp(
            sourceId = descriptor.id,
            bgMgdl = bgMgdl,
            // Trend is a 4-bit CODE, not a rate; deriving a rate would invent one.
            trendTenthsPerMin = null,
            minFromStart = minFromStart,
            quality = null,
            flag = flag,
            rxWallMs = sampleMs,
            tzOffsetMin = tzOffsetMin,
            rssi = lastRssi,
        )
        for (reading in readings) {
            repository.upsertReading(reading)
            updateStatus(reading)
            _readings.emit(reading)
        }
    }

    private fun describe(p: Ct5PushSample): String =
        "#${p.glucoseId} bg=${p.glucoseMgdl} T=${p.tempCx100} Iw=${p.iwX100} Ib=${p.ibX100} " +
            "batt=${p.batteryRaw} mV=${p.electrodesMv?.joinToString("/")} err=${p.errorCode} trend=${p.trendCode}"

    /** Key-agreement check 0x38 echo can't make: correct decode is 200/200 in-band, wrong 60. */
    internal fun plausible(push: Ct5PushSample): Boolean =
        push.tempCx100 in Ct5Constants.PLAUSIBLE_TEMP_CX100

    /** bgMgdl is our own conversion, not the sensor's field (which zeroes on session end). */
    internal fun flagFor(push: Ct5PushSample, minFromStart: Int, bgMgdl: Int?): ReadingFlag {
        if (push.errorCode !in Ct5Constants.ACCEPTED_ERROR_CODES) return ReadingFlag.INVALID
        val bg = bgMgdl
        val warming = minFromStart < descriptor.warmupWindowMin
        return when {
            bg == null -> if (warming) ReadingFlag.WARMUP else ReadingFlag.INVALID
            bg !in CgmConstants.VALID_BG_RANGE -> ReadingFlag.INVALID
            warming -> ReadingFlag.WARMUP
            else -> ReadingFlag.NORMAL
        }
    }

    /** Sensor clock or receive-time fallback; behind adds lag, ahead widens §3.6-D window. */
    private fun sampleInstant(st: Ct5SensorState, glucoseId: Int, rxMs: Long): Long {
        val derived = st.bindTimeMs + glucoseId * Ct5Constants.SAMPLE_INTERVAL_MS
        val ahead = derived - rxMs
        if (derivable(derived, rxMs)) {
            // Within tolerance the derived instant is used, but never a future one, even so.
            return if (ahead > 0) rxMs else derived
        }
        skewFallbacks++
        if (skewFallbacks == 1) {
            log.w(
                TAG,
                "sample $glucoseId derives ${ahead / 1000} s from its delivery; " +
                    "filing under receive time instead",
            )
        }
        return rxMs
    }

    /** Skew-band definition shared by sampleInstant/learnBindInstant -- must never disagree. */
    private fun derivable(derived: Long, rxMs: Long): Boolean =
        derived - rxMs <= Ct5Constants.MAX_FORWARD_SKEW_MS &&
            rxMs - derived <= Ct5Constants.MAX_CLOCK_SKEW_MS

    /** Re-anchors the clock on a live push; interval isn't exactly 180.000s, drift needs fixing. */
    private suspend fun learnBindInstant(
        st: Ct5SensorState,
        glucoseId: Int,
        rxMs: Long,
        alreadyDeliveredId: Int,
    ): Ct5SensorState {
        anchorTrusted = true
        if (derivable(st.bindTimeMs + glucoseId * Ct5Constants.SAMPLE_INTERVAL_MS, rxMs)) return st
        // Stopped-counter repeats drag the anchor forever; only past-delivered samples move it.
        val settled = maxOf(backfillCursor, alreadyDeliveredId)
        if (glucoseId <= settled) {
            log.w(TAG, "sample $glucoseId is not past the $settled already delivered; the anchor is left where it is")
            return st
        }
        val learned = rxMs - glucoseId * Ct5Constants.SAMPLE_INTERVAL_MS
        val corrected = st.copy(bindTimeMs = learned)
        // Not fatal, not applied unless persisted: un-agreed anchor moves on next connect.
        if (!persistSecret(corrected)) {
            // Known wrong, unrepaired: a pull would write a stretch of the wear at the wrong hour.
            anchorTrusted = false
            return st
        }
        state = corrected
        log.i(
            TAG,
            "bind instant re-anchored at sample $glucoseId by ${(learned - st.bindTimeMs) / 1000} s",
        )
        return corrected
    }

    /** Stores the secret, returns success/failure instead of throwing (kills the collector). */
    private suspend fun persistSecret(st: Ct5SensorState): Boolean = try {
        repository.saveSensorSecret(descriptor.id, st.encode())
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // The blob's contents are never logged: it holds the session key and unbind password.
        log.e(TAG, "the sealed store refused the secret for $bsn: ${e.javaClass.simpleName}")
        false
    }

    private fun updateStatus(reading: CgmReading) {
        if (reading.provenance != ReadingProvenance.MEASURED) return
        // Push path suspends; the watchdog may fail mid-way, status must not climb back.
        if (phase == Phase.FAILED || phase == Phase.IDLE) return
        _status.value = when (reading.flag) {
            ReadingFlag.WARMUP -> CgmSourceStatus.Warmup
            ReadingFlag.NORMAL -> CgmSourceStatus.Live
            ReadingFlag.INVALID -> _status.value
        }
    }


    /** Bounds pre-LIVE steps then push cadence; AWAITING_BIND uses lastLinkMs, sets SignalLost. */
    private fun armWatchdog() {
        if (watchdog != null) return
        watchdog = scope.launch {
            while (isActive) {
                // One phase snapshot: reading it 3x could pair one budget with another's clock.
                val p = phase
                val budget = budgetMs(p)
                if (budget == NO_DEADLINE) {
                    delay(Ct5Constants.PUSH_STALE_MS)
                    continue
                }
                val since = if (p == Phase.AWAITING_BIND) lastLinkMs else lastProgressMs
                val now = nowMs()
                // Once per silence, ahead of deadline: an idle live link is asked for its store.
                if (p == Phase.LIVE && idlePulledFor != since && now - since >= Ct5Constants.IDLE_PULL_MS) {
                    idlePulledFor = since
                    pullRequests.trySend(PullRequest.IDLE)
                }
                val nextIdle =
                    if (p == Phase.LIVE && idlePulledFor != since) since + Ct5Constants.IDLE_PULL_MS else Long.MAX_VALUE
                val remaining = minOf(since + budget, nextIdle) - now
                if (remaining > 0) {
                    delay(remaining)
                    continue
                }
                val idleMin = (nowMs() - since) / 60_000
                log.w(TAG, "watchdog: $p silent for ${idleMin}m — treating the link as dead")
                fail("nothing came back for ${idleMin}m in $p")
                return@launch
            }
        }
    }

    /** How long [p] may stay silent, or [NO_DEADLINE] where silence is expected. */
    private fun budgetMs(p: Phase): Long = when (p) {
        Phase.LIVE -> Ct5Constants.PUSH_STALE_MS
        // An unbound sensor pushes nothing to wait for; measured against lastLinkMs instead.
        Phase.AWAITING_BIND -> LINK_STALE_MS
        Phase.IDLE, Phase.FAILED -> NO_DEADLINE
        else -> Ct5Constants.HANDSHAKE_STEP_TIMEOUT_MS
    }

    /** Entering counts as progress, so the watchdog measures this step and not the last. */
    private fun enter(next: Phase) {
        phase = next
        val now = nowMs()
        lastProgressMs = now
        lastLinkMs = now
        // The one place every phase change passes through, keeps the panel's offer in step.
        _bindable.value = next == Phase.AWAITING_BIND
    }

    private fun send(frame: ByteArray) {
        val opcode = frame[0].toInt() and 0xFF
        val topic = ct5LogTopic(opcode)
        log.i(TAG, "→ 0x${opcode.toString(16)} (${frame.size} B) in $phase", topic, opens = topic != CgmLogTopic.NONE)
        outstanding = opcode
        transport.sendPaced(frame, Ct5Constants.ladderFor(opcode), expectReply = true)
    }

    private fun sendSetDate() {
        // Local wall clock, no tz byte, cosmetic (RTC never read back); freely repeatable.
        val now = nowMs()
        val local = Instant.ofEpochMilli(now).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMinFor(now) * 60))
        val f = session.buildSetDate(
            year = local.year,
            month = local.monthValue,
            day = local.dayOfMonth,
            hour = local.hour,
            minute = local.minute,
            second = local.second,
        )
        if (f == null) {
            // Cosmetic in LIVE (must not end a working session); a real bind step before LIVE.
            if (phase == Phase.LIVE) {
                log.w(TAG, "setDate could not be encoded for the current local time; the RTC is left alone")
            } else {
                fail("setDate could not be encoded for the current local time")
            }
            return
        }
        send(f)
    }

    private fun fail(reason: String) {
        log.w(TAG, "CT5 session failed: $reason")
        phase = Phase.FAILED
        watchdog?.cancel()
        watchdog = null
        lastRssi = null
        _rssi.value = null
        _bindable.value = false
        _backfillInFlight.value = false
        // CgmSourceStatus has no error state; the coordinator blocks on this, reason is logged.
        _status.value = CgmSourceStatus.SignalLost
    }


    internal fun installAwaitingBindForTest() {
        _status.value = CgmSourceStatus.Scanning
        enter(Phase.AWAITING_BIND)
    }

    /** Pull marked already open, so tests read a push mapping without a 0x37 beside it. */
    internal fun installLiveForTest(installed: Ct5SensorState) {
        state = installed
        _status.value = CgmSourceStatus.Scanning
        enter(Phase.LIVE)
        backfillStarted = true
    }

    /** As installLiveForTest, but through the real goLive so the first push opens the backfill. */
    internal suspend fun goLiveForTest(installed: Ct5SensorState) {
        state = installed
        _status.value = CgmSourceStatus.Scanning
        goLive()
    }

    /** Opens the pull as the first push does, without putting a reading through the store. */
    internal suspend fun openBackfillForTest(liveId: Int) {
        backfillStarted = true
        beginBackfill(liveId, anchorTrusted = true)
    }

    internal fun armWatchdogForTest() = armWatchdog()

    internal val phaseName: String get() = phase.name

    internal val skewFallbackCount: Int get() = skewFallbacks

    companion object {
        private const val TAG = "Ct5Connect"

        internal const val RSSI_POLL_MS = 15_000L

        /** 4 polls; 3 fails a healthy link on one deferred wake-up. Only AWAITING_BIND uses it. */
        internal const val LINK_STALE_MS = RSSI_POLL_MS * 4

        private const val NO_DEADLINE = -1L

        /** The 22-byte identity reply plus the 3-byte ATT header. */
        private const val MIN_USEFUL_MTU = 25

        /** Not correctness, just cost -- a sealed write per 3min for 16 days; pull skips it. */
        private const val CURSOR_PERSIST_INTERVAL_MS = 10 * 60_000L

        /** Small: a sensor duplicating this persistently is one to leave to the next rejoin. */
        private const val MAX_HISTORY_RETRIES = 2

    /** lifeTime is 0 until 0x3F is read; seeds the window only, a stored source has its own. */
        fun descriptorFor(bsn: String, advertName: String? = null, lifeTime: Int = 0): CgmSourceDescriptor =
            CgmSourceDescriptor(
                id = CgmSourceId("${Ct5Constants.VENDOR_ID}:$bsn"),
                vendorId = Ct5Constants.VENDOR_ID,
                sensorModelId = Ct5Constants.MODEL_ID,
                advertName = advertName,
                displayName = "${Ct5Constants.BRAND} $bsn",
                serialSuffix = bsn,
                warmupWindowMin = Ct5Constants.warmupWindowMinFor(lifeTime),
                passiveOnly = false,
            )
    }
}
