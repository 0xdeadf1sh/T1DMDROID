package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.TimeZone

/**
 * PLAN_T1DMDROID.md §4: the registry's handle on one Libre 3. start() pairs (§5.3/§5.4 via
 * [Libre3Session]) and, on Established, hands the OPEN link to [Libre3DataStream] — the §5.7
 * data plane streams on the same GATT link the handshake ran on (§4: a fresh reconnect would
 * re-run the whole VM handshake). Readings/status flow from the stream; on pairing or stream
 * failure the source goes SignalLost. kEnc/ivEnc stay session-scoped; kAuth (when the machine
 * ever carries one) is the driver's to persist; the driver owns state.
 */
class Libre3ConnectedSource(
    override val descriptor: CgmSourceDescriptor,
    private val transport: Libre3GattTransport,
    private val native: Libre3Native,
    private val tablesDir: String,
    private val sensor: Libre3SensorState,
    private val repository: CgmRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tzOffsetMinFor: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
    /** Injected R2 for tests; the session mints its own otherwise. */
    private val r2: ByteArray? = null,
    private val random: SecureRandom? = null,
    /** §5.7 data-plane factory; a stated wear of 0 goes as none, so no reading is "expired". */
    private val startDataPlane: (kEnc: ByteArray, ivEnc: ByteArray) -> Libre3Call<Libre3DataPlaneHandle> =
        { kEnc, ivEnc -> native.startDataPlane(kEnc, ivEnc, WARMUP_WINDOW_MIN, sensor.statedLifetimeMin) },
    /** The stream's arm-round settle delay; 0 in tests. */
    private val armBackfillDelayMs: Long = ARM_BACKFILL_DELAY_MS,
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) : ConnectedCgmSession {

    private val _status = MutableStateFlow(CgmSourceStatus.Idle)
    override val status: StateFlow<CgmSourceStatus> = _status.asStateFlow()

    private val _readings = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = READINGS_BUFFER)
    override fun readings(): Flow<CgmReading> = _readings.asSharedFlow()

    /** Polled `readRemoteRssi` value; null before the first read (Ct5's 15 s cadence). */
    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    override val statedLifetimeMin: StateFlow<Int?> = MutableStateFlow(sensor.statedLifetimeMin)

    /** Phase-4 surface: the pairing outcome, for the driver's state model; null while running. */
    private val _pairing = MutableStateFlow<Libre3Session.Outcome?>(null)
    val pairing: StateFlow<Libre3Session.Outcome?> = _pairing.asStateFlow()

    /** Carries only the sensor's own arrow; the other channels stay null. */
    private val _telemetry = MutableStateFlow<CgmSourceTelemetry?>(null)
    override val telemetry: StateFlow<CgmSourceTelemetry?> = _telemetry.asStateFlow()

    /** Drives the panel's Fetch-history spinner; true across the whole round trip. */
    private val _backfillInFlight = MutableStateFlow(false)
    override val backfillInFlight: StateFlow<Boolean> = _backfillInFlight.asStateFlow()

    /**
     * §15: the classified last failure, for the panel's row. Set on a pairing or stream
     * failure, cleared when a session establishes again; an ordinary SignalLost is the status,
     * not a fault, and sets nothing.
     */
    private val _failure = MutableStateFlow<String?>(null)
    override val failure: StateFlow<String?> = _failure.asStateFlow()

    private var job: Job? = null

    /** RSSI poll; a quiet link still reports strength (Ct5ConnectedSource's cadence). */
    private var rssiJob: Job? = null

    /** The stream the data plane runs in; built on Established, null before (requestBackfill). */
    private var stream: Libre3DataStream? = null

    /** Console only; null while no data plane is open. */
    val dataPlane: Libre3DataPlaneHandle? get() = stream?.handle

    override fun start() {
        if (job != null) return
        _status.value = CgmSourceStatus.Scanning
        // Listener before any link: reads before the GATT exists just fail silently.
        transport.setRssiListener { rssi -> _rssi.value = rssi }
        rssiJob = scope.launch {
            while (isActive) {
                delay(RSSI_POLL_MS)
                transport.readRemoteRssi()
            }
        }
        job = scope.launch {
            val outcome = runCatching {
                Libre3Session(native, transport, tablesDir, sensor, r2 = r2, random = random, log = log).runFirstPair()
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                log.w(TAG, "pairing for ${sensor.serial} threw: ${e.javaClass.simpleName}")
                Libre3Session.Outcome.Failed("session threw ${e.javaClass.simpleName}", "session")
            }
            _pairing.value = outcome
            when (outcome) {
                is Libre3Session.Outcome.Established -> {
                    log.i(TAG, "paired ${sensor.serial} — the open link hands to the data plane")
                    _failure.value = null
                    runStream(outcome.kEnc, outcome.ivEnc)
                }

                is Libre3Session.Outcome.Failed -> {
                    log.w(TAG, "pairing for ${sensor.serial} failed at ${outcome.step}: ${outcome.reason}")
                    _failure.value = classifyFailure(outcome)
                    _status.value = CgmSourceStatus.SignalLost
                    // The session already closed the transport on Failed; make sure (idempotent).
                    transport.close()
                }
            }
        }
    }

    /** §5.7 post-Established driver loop, in the same job: status/readings stream-driven. */
    private suspend fun runStream(kEnc: ByteArray, ivEnc: ByteArray) {
        val dataStream = Libre3DataStream(
            sourceId = descriptor.id,
            transport = transport,
            startDataPlane = { startDataPlane(kEnc, ivEnc) },
            sensor = sensor,
            repository = repository,
            emitReading = { reading -> _readings.tryEmit(reading) },
            onTelemetry = { t -> _telemetry.value = t },
            onStatus = { s -> _status.value = s },
            historicalBackfillCmd = { lifeCount -> native.historicalBackfillCmd(lifeCount) },
            clinicalBackfillCmd = { lifeCount -> native.clinicalBackfillCmd(lifeCount) },
            historicalBackfillRangeCmd = { start, end -> native.historicalBackfillRangeCmd(start, end) },
            clinicalBackfillRangeCmd = { start, end -> native.clinicalBackfillRangeCmd(start, end) },
            armBackfillDelayMs = armBackfillDelayMs,
            rssi = { _rssi.value },
            nowMs = nowMs,
            tzOffsetMinFor = tzOffsetMinFor,
            log = log,
        )
        stream = dataStream
        val outcome = runCatching { dataStream.run() }.getOrElse { e ->
            if (e is CancellationException) throw e
            log.w(TAG, "data plane for ${sensor.serial} threw: ${e.javaClass.simpleName}")
            Libre3DataStream.Outcome.Failed("stream threw ${e.javaClass.simpleName}")
        }
        when (outcome) {
            // Fail-closed: a stream that ended without a terminal reason reads as lost.
            Libre3DataStream.Outcome.Started,
            Libre3DataStream.Outcome.SignalLost,
            -> _status.value = CgmSourceStatus.SignalLost

            is Libre3DataStream.Outcome.Failed -> {
                log.w(TAG, "data plane for ${sensor.serial} failed: ${outcome.reason}")
                _failure.value = outcome.reason
                _status.value = CgmSourceStatus.SignalLost
            }
        }
    }

    /**
     * §15 at the [Libre3Native] boundary: the failure class names the panel's line, the reason
     * keeps the detail (the Rust spellings ride through unchanged — fail-closed, never coerced).
     */
    private fun classifyFailure(f: Libre3Session.Outcome.Failed): String = when {
        f.reason.contains("account mismatch") -> "account/region mismatch"

        f.step == "tables" -> "tables: ${f.reason}"

        f.step == "connect" -> "link: ${f.reason}"

        f.step == null -> f.reason

        else -> "pairing failed at ${f.step}: ${f.reason}"
    }

    override fun close() {
        rssiJob?.cancel()
        rssiJob = null
        job?.cancel()
        job = null
        stream = null
        // The stream's teardown closes the transport too; idempotent, safe to call twice.
        transport.close()
        _status.value = CgmSourceStatus.Idle
    }

    /** UI only; a paired-but-quiet source reads as signal lost to the panel. */
    override fun markSignalLost() {
        when (_status.value) {
            CgmSourceStatus.Live, CgmSourceStatus.Warmup -> _status.value = CgmSourceStatus.SignalLost
            else -> Unit
        }
    }

    /** Libre3DataStream.backfillRound: kit pair, top-up range, bounded walk from the floor. */
    override fun requestBackfill() {
        // Unconditional entry point: a silent tap (no log anywhere) is undebuggable (live run
        // 2026-09-24 — the button produced nothing and the log carried no Libre3 line at all).
        log.i(TAG, "fetch history for ${sensor.serial}: stream ${if (stream != null) "present" else "absent"}")
        val dataStream = stream ?: return
        // A tap while a round trip runs would double-send; the spinner covers the wait instead.
        if (_backfillInFlight.value) {
            log.i(TAG, "fetch history for ${sensor.serial}: already running")
            return
        }
        _backfillInFlight.value = true
        scope.launch {
            try {
                dataStream.requestBackfill()
            } finally {
                _backfillInFlight.value = false
            }
        }
    }

    private companion object {
        const val TAG = "Libre3Pair"

        /** §5.7 warmup = 60 min default from currentLifeCount (SensorLifecycle.swift L10). */
        const val WARMUP_WINDOW_MIN = 60

        /** RSSI poll cadence, the Ct5 mirror (Ct5ConnectedSource.RSSI_POLL_MS). */
        const val RSSI_POLL_MS = 15_000L

        /** Realtime only; backfill is stored, never emitted (Ct5's emit semantics). */
        const val READINGS_BUFFER = 64

        /** The arm-round settle (Libre3DataStream's live-calibrated value). */
        const val ARM_BACKFILL_DELAY_MS = 5_000L
    }
}