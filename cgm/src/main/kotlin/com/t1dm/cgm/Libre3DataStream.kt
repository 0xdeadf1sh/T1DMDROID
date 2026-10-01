package com.t1dm.cgm

import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.SensorArrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

/**
 * PLAN_T1DMDROID.md §4/§5.7/§11: the post-Established driver loop. Listens on the seven data
 * characteristics, feeds every notify chunk to the Rust data-plane session (§5.2 assembly and
 * §5.7 decrypt/decode happen in Rust), and dispatches the updates: realtime glucose →
 * [GridStamper] → [CgmRepository] (the Ct5 path), patchStatus → lifecycle/status, historical
 * pages → backfill ingestion, clinical/raw → visible in logs, never coerced (§5.7/§15).
 *
 * Fail-closed teardown in [run]'s finally: the stream owned the link and the handle, both die
 * with it (the source's close is idempotent on top).
 *
 * NO stall watchdog: the GATT failure surface (transport's data-phase onFail) and the sensor's
 * own patchStatus cover link death and wear end; recovery is the registry's reconnect loop
 * (1 s→30 s doubling backoff, reset by a healthy session), and one bounded backfill round at
 * the persisted cursor re-arms on every reconnect (§14 phase 6).
 */
class Libre3DataStream(
    private val sourceId: CgmSourceId,
    private val transport: Libre3GattTransport,
    /** §5.7: builds the Rust data-plane session from the Established kEnc/ivEnc; scripted in tests. */
    private val startDataPlane: () -> Libre3Call<Libre3DataPlaneHandle>,
    private val sensor: Libre3SensorState,
    private val repository: CgmRepository,
    /** The stream's reading and status signals; the source wires its own flows. */
    private val emitReading: (CgmReading) -> Unit,
    /** The sensor's own arrow, published before its reading is filed. */
    private val onTelemetry: (CgmSourceTelemetry) -> Unit = {},
    private val onStatus: (CgmSourceStatus) -> Unit,
    /** §5.7 historical command builder (the native seam); null-refusal is logged, not fatal. */
    private val historicalBackfillCmd: (lifeCount: Int) -> ByteArray? = { null },
    /** §5.7 clinical command builder (the native seam); null = the round sends historical only. */
    private val clinicalBackfillCmd: (lifeCount: Int) -> ByteArray? = { null },
    /** §5.7 Range backfill builders (the native seam); null = that write is skipped. */
    private val historicalBackfillRangeCmd: (start: Int, end: Int) -> ByteArray? = { _, _ -> null },
    private val clinicalBackfillRangeCmd: (start: Int, end: Int) -> ByteArray? = { _, _ -> null },
    /** Settle time between arming and the arm round's first write; 0 in tests. */
    private val armBackfillDelayMs: Long = ARM_BACKFILL_DELAY_MS,
    /** Last RSSI read, supplier; stamped onto readings (Ct5's lastRssi). */
    private val rssi: () -> Int? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** §2: east-positive minutes at event time (the Ct5 supplier). */
    private val tzOffsetMinFor: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
    private val gridStamper: GridStamper = GridStamper(),
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) {

    sealed interface Outcome {
        /** Armed and streaming; the [emitReading]/[onStatus] hooks carry the traffic from here. */
        data object Started : Outcome

        /** Setup refused (data-plane build, §5.10 CCCD arm); nothing streamed. */
        data class Failed(val reason: String) : Outcome

        /** Link gone, or the patch terminated the wear (§5.7 terminal states). */
        data object SignalLost : Outcome
    }

    private sealed interface Setup {
        data object Armed : Setup
        data class LinkFailed(val reason: String) : Setup
    }

    private sealed interface Event {
        data class Chunk(val char: Libre3DataChar, val bytes: ByteArray, val rx: Long?) : Event
        data class GattFailed(val reason: String) : Event
    }

    /** Set in [run] while the data plane is open; the source's requestBackfill reads it. */
    @Volatile
    var handle: Libre3DataPlaneHandle? = null
        private set

    /** Working copy of the sensor state; the life counts advance on accepted traffic. */
    private var state: Libre3SensorState = sensor

    /** The in-flight backfill command's reply; null completion = the stream ended. */
    @Volatile
    private var pendingReply: CompletableDeferred<ByteArray?>? = null

    /** One backfill round at a time: the arm round and a tap share one sensor procedure slot. */
    private val backfillLock = Mutex()

    /** Newest realtime life count this session decoded, dropped frames too; null before one. */
    private val liveLifeCount = MutableStateFlow<Int?>(null)

    suspend fun run(): Outcome {
        // Before the first ingest, or this build's own rows would be divided too.
        val repaired = repository.divideRatesByTenOnce(
            "${Libre3FamilyDriver.VENDOR_ID}:", RATE_REPAIR_BEFORE_MS, RATE_REPAIR_KEY,
        )
        if (repaired > 0) log.i(TAG, "rate repair: $repaired stored rates ÷10")
        var plane: Libre3DataPlaneHandle? = null
        try {
            val live = when (val started = startDataPlane()) {
                is Libre3Call.Ok -> started.value
                is Libre3Call.AccountMismatch -> return Outcome.Failed("data plane refused (account mismatch)")
                is Libre3Call.Failed -> return Outcome.Failed("data plane: ${started.reason}")
            }
            plane = live
            handle = live
            val chunks = Channel<Event.Chunk>(Channel.UNLIMITED)
            val gattFailures = Channel<Event.GattFailed>(Channel.UNLIMITED)
            // Listeners BEFORE the CCCD enables: the first data notify may follow the last
            // write closely (Libre3Session attaches its listeners before connect for the same
            // reason; §5.10 arming is one-by-one).
            for (c in Libre3DataChar.entries) {
                transport.setNotifyListener(c.uuid) { chunk, rx -> chunks.trySend(Event.Chunk(c, chunk, rx)) }
            }
            val armed = CompletableDeferred<Unit>()
            transport.enableDataChars(
                onReady = { armed.complete(Unit) },
                onFail = { reason -> gattFailures.trySend(Event.GattFailed(reason)) },
            )
            when (val setup = withTimeoutOrNull(ENABLE_MS) {
                select<Setup> {
                    armed.onAwait { Setup.Armed }
                    gattFailures.onReceive { Setup.LinkFailed(it.reason) }
                }
            }) {
                null -> return Outcome.Failed("the data characteristics did not arm in $ENABLE_MS ms (§5.10)")
                is Setup.LinkFailed -> return Outcome.Failed("enable failed: ${setup.reason}")
                Setup.Armed -> log.i(TAG, "data plane for ${sensor.serial} armed (§5.1 order); streaming")
            }
            return coroutineScope {
                // Off the read loop: its replies arrive through that loop.
                val armRound = launch {
                    delay(armBackfillDelayMs)
                    val count = withTimeoutOrNull(ARM_LIVE_WAIT_MS) { liveLifeCount.filterNotNull().first() }
                    if (count == null) {
                        log.i(TAG, "top-up skipped — no realtime frame in ${ARM_LIVE_WAIT_MS / 1000} s")
                        return@launch
                    }
                    backfillLock.withLock { sendGapTopUp(live, count) }
                }
                try {
                    stream(live, chunks, gattFailures)
                } finally {
                    armRound.cancel()
                }
            }
        } finally {
            handle = null
            pendingReply?.complete(null)
            // Fail-closed teardown: the stream owned both the link and the session — on EVERY
            // exit, including a data-plane build refusal (which previously returned before the
            // try and left the GATT link open; phase-6 fix, live-harmless only because the
            // registry always tears the session down).
            transport.close()
            plane?.close()
        }
    }

    private suspend fun stream(
        plane: Libre3DataPlaneHandle,
        chunks: Channel<Event.Chunk>,
        gattFailures: Channel<Event.GattFailed>,
    ): Outcome {
        while (true) {
            when (val event = select<Event> {
                chunks.onReceive { it }
                gattFailures.onReceive { it }
            }) {
                is Event.GattFailed -> {
                    log.w(TAG, "data-plane link failed for ${sensor.serial}: ${event.reason}")
                    onStatus(CgmSourceStatus.SignalLost)
                    return Outcome.SignalLost
                }

                is Event.Chunk -> {
                    val terminal = decodingChunk(event.rx) { onChunk(plane = plane, char = event.char, chunk = event.bytes) }
                    if (terminal) {
                        onStatus(CgmSourceStatus.SignalLost)
                        return Outcome.SignalLost
                    }
                }
            }
        }
    }

    /** One chunk in. Returns true only when the stream must stop (terminal patch status). */
    private suspend fun onChunk(plane: Libre3DataPlaneHandle, char: Libre3DataChar, chunk: ByteArray): Boolean {
        val updates = try {
            plane.feed(char, chunk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ONE bad frame is not a dead link (§5.7): log and keep streaming.
            log.w(TAG, "feed ${char.name} threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (updates == null) {
            log.w(TAG, "feed ${char.name} refused (${chunk.size} B) — frame dropped, stream continues")
            return false
        }
        for (update in updates) {
            if (dispatch(update)) return true
        }
        return false
    }

    /** One decoded update; true = terminal, stop the stream. */
    private suspend fun dispatch(update: Libre3DataUpdate): Boolean = when (update) {
        is Libre3DataUpdate.RealtimeGlucose -> onRealtime(update)

        is Libre3DataUpdate.PatchStatus -> onPatchStatus(update)

        is Libre3DataUpdate.HistoricalPage -> onHistoricalPage(update)

        is Libre3DataUpdate.Clinical -> onClinical(update)

        is Libre3DataUpdate.Raw -> {
            // Unknown payload shape: visible, never coerced (§5.7/§15).
            android.util.Log.d(TAG, "raw ${update.channel} ${update.plaintext.size} B: ${hex(update.plaintext)}")
            log.dec(
                tag = null,
                text = "raw ${update.channel} ${update.plaintext.size} B",
                topic = LIBRE3_LOG_CHARS[update.channel.uuid.lowercase()]?.second ?: CgmLogTopic.NONE,
                level = CgmLogLevel.D,
                bytes = update.plaintext,
            )
            if (update.channel == Libre3DataChar.PatchControl) pendingReply?.complete(update.plaintext)
            false
        }
    }

    // MARK: - dispatch arms

    /** Logged only: no data-quality word, so a minute realtime rejected would file as NORMAL. */
    private fun onClinical(c: Libre3DataUpdate.Clinical): Boolean {
        log.dec(
            TAG,
            "clinical ${c.lifeCount}: current ${c.currentMgdl} historic ${c.historicMgdl} mg/dL, not stored",
            CgmLogTopic.CLINICAL,
            value = c.currentMgdl,
        )
        return false
    }

    /** §11: first hour is WARMUP even before a patchStatus sets a lifecycle, as pages are. */
    private suspend fun onRealtime(r: Libre3DataUpdate.RealtimeGlucose): Boolean {
        val prior = liveLifeCount.value
        liveLifeCount.value = maxOf(r.lifeCount, prior ?: r.lifeCount)
        val glucose = CgmLogTopic.GLUCOSE
        log.dec(
            null,
            "realtime ${r.lifeCount}: ${r.mgdl} mg/dL rate=${r.rateRaw}/100 trend=${r.trendKind} " +
                "usable=${r.usable} warmup=${r.warmup} expired=${r.expired} issues=${r.issues}",
            glucose,
        )
        if (prior != null && r.lifeCount > prior + 1) {
            log.w(TAG, "life count jumped $prior → ${r.lifeCount}: ${r.lifeCount - prior - 1} missed", glucose)
        }
        val flag = when {
            r.mgdl == null -> {
                val level = if (r.warmup) CgmLogLevel.I else CgmLogLevel.W
                log.dec(TAG, "realtime ${r.lifeCount} dropped: not displayable (${r.issues})", glucose, level = level)
                return false
            }

            r.expired -> {
                log.dec(TAG, "realtime ${r.lifeCount} dropped: sensor expired", glucose, level = CgmLogLevel.W)
                return false
            }

            r.warmup -> ReadingFlag.WARMUP

            !r.usable -> {
                log.dec(TAG, "realtime ${r.lifeCount} dropped: ${r.issues}", glucose, level = CgmLogLevel.W)
                return false
            }

            r.lifeCount < WARMUP_WINDOW_MIN -> ReadingFlag.WARMUP

            else -> ReadingFlag.NORMAL
        }
        val sampleMs = sampleInstantMs(r.lifeCount)
        // Before the reading: whoever the filed row wakes finds the arrow already there.
        onTelemetry(CgmSourceTelemetry(sampledAtMs = sampleMs, arrow = libre3Arrow(r.trendKind)))
        ingest(
            mgdl = r.mgdl!!,
            rateRaw = r.rateRaw?.toInt(),
            minFromStart = r.lifeCount,
            flag = flag,
            sampleMs = sampleMs,
            live = true,
        )
        log.dec(TAG, "realtime ${r.lifeCount} stored ${r.mgdl} mg/dL ($flag, trend=${r.trendKind})", glucose, r.mgdl)
        if (flag == ReadingFlag.NORMAL) advanceRealtime(r.lifeCount)
        return false
    }

    /**
     * §5.7 patchStatus: one log line (never the PIN/keys — these fields are fine), terminal
     * states end the stream, and the status follows the lifecycle.
     */
    private suspend fun onPatchStatus(s: Libre3DataUpdate.PatchStatus): Boolean {
        log.dec(
            TAG,
            "patchStatus life=${s.lifeCount} current=${s.currentLifeCount} patchState=${s.patchState} " +
                "error=${s.errorData} attention=${s.attention} lifecycle=${s.lifecycle.phase} " +
                "warmupLeft=${s.lifecycle.remainingWarmupMin} wearLeft=${s.lifecycle.remainingWearMin}",
            CgmLogTopic.STATUS,
        )
        if (s.shouldNotifyReplaceSensor || s.shouldNotifyUser) {
            // UI surfacing is a later phase; the log keeps it visible meanwhile.
            log.i(
                TAG,
                "patch asks for attention: replaceSensor=${s.shouldNotifyReplaceSensor} " +
                    "notifyUser=${s.shouldNotifyUser}",
            )
        }
        if (!s.isPatchStateActive) {
            log.i(TAG, "patch state ${s.patchState} is not active (3/5/7 = expired/error handling, §5.7)")
        }
        if (s.isPatchStateTerminated || s.isShutdownTerminated) {
            log.w(
                TAG,
                "patch terminated (state=${s.patchState}, shutdown=${s.isShutdownTerminated}) — stream ends",
            )
            return true
        }
        when {
            s.lifecycle.isWarmingUp -> onStatus(CgmSourceStatus.Warmup)
            s.isPatchStateActive -> onStatus(CgmSourceStatus.Live)
        }
        return false
    }

    /**
     * §11 historical mapping: the clamp is the gate (no DQ words on pages); a displayable
     * sample stores NORMAL past warmup, WARMUP inside it. The page itself counts as accepted
     * progress — a fully non-displayable page still advances the count (those minutes are
     * gone; re-asking is pointless).
     */
    private suspend fun onHistoricalPage(page: Libre3DataUpdate.HistoricalPage): Boolean {
        var stored = 0
        for ((i, mgdl) in page.valuesMgdl.withIndex()) {
            val lifeCount = page.sampleLifeCounts[i]
            val mgdlValue = mgdl ?: continue
            val sampleMs = sampleInstantMs(lifeCount)
            val flag = if (lifeCount < WARMUP_WINDOW_MIN) ReadingFlag.WARMUP else ReadingFlag.NORMAL
            ingest(
                mgdl = mgdlValue,
                rateRaw = null,
                minFromStart = lifeCount,
                flag = flag,
                sampleMs = sampleMs,
                live = false,
            )
            stored++
        }
        page.sampleLifeCounts.maxOrNull()?.let { advanceHistorical(it) }
        log.dec(
            TAG,
            "historical page from ${page.startLifeCount}: $stored/${page.valuesMgdl.size} stored " +
                page.sampleLifeCounts.zip(page.valuesMgdl).joinToString(" ") { (n, v) -> "$n:$v" },
            CgmLogTopic.HISTORY,
        )
        return false
    }

    /** GridStamper → repository; only [live] rows reach readings and status (Ct5's rule). */
    private suspend fun ingest(
        mgdl: Int,
        rateRaw: Int?,
        minFromStart: Int,
        flag: ReadingFlag,
        sampleMs: Long,
        live: Boolean,
    ) {
        val readings = gridStamper.stamp(
            sourceId = sourceId,
            bgMgdl = mgdl,
            trendTenthsPerMin = rateRaw?.let(::hundredthsToTenths),
            minFromStart = minFromStart,
            quality = null,
            flag = flag,
            // rxWallMs = sampleMs: the connected-path rule (measuredAtMs = the sensor's own clock).
            rxWallMs = sampleMs,
            // §2: east-positive minutes at event time; may straddle a DST change.
            tzOffsetMin = tzOffsetMinFor(sampleMs),
            // The §5.1 set has no RSSI characteristic; the polled readRemoteRssi value rides.
            rssi = rssi(),
        )
        for (reading in readings) {
            repository.upsertReading(reading)
            if (!live) continue
            emitReading(reading)
            updateStatus(reading)
        }
    }

    /** NORMAL → Live, WARMUP → Warmup (Ct5ConnectedSource.updateStatus); never climbs back. */
    private fun updateStatus(reading: CgmReading) {
        when (reading.flag) {
            ReadingFlag.NORMAL -> onStatus(CgmSourceStatus.Live)
            ReadingFlag.WARMUP -> onStatus(CgmSourceStatus.Warmup)
            ReadingFlag.INVALID -> Unit
        }
    }

    /**
     * PLAN §11 measuredAtMs rule: activation + lifeCount*60_000 — the sensor's own sample
     * clock. More than [MAX_FORWARD_SKEW_MS] ahead of the wall clock → file under receive
     * time instead (fail-soft; the Ct5ConnectedSource.sampleInstant spirit).
     */
    private fun sampleInstantMs(lifeCount: Int): Long {
        val derived = lifeCountMs(lifeCount)
        val now = nowMs()
        if (derived - now > MAX_FORWARD_SKEW_MS) {
            log.w(
                TAG,
                "lifeCount $lifeCount derives ${(derived - now) / 60_000} min ahead of the clock; " +
                    "filing under receive time instead",
            )
            return now
        }
        return derived
    }

    private fun lifeCountMs(lifeCount: Int): Long = sensor.activationTimeS * 1000L + lifeCount * 60_000L

    /** Monotonic max + persist-on-change (the blob is small; Ct5 rate-limits, phase 5 keeps it simple). */
    private suspend fun advanceRealtime(lifeCount: Int) {
        val current = state.lastRealtimeLifeCount
        if (current != null && lifeCount <= current) return
        state = state.copy(lastRealtimeLifeCount = lifeCount)
        persistLifeCounts()
    }

    private suspend fun advanceHistorical(lifeCount: Int) {
        val current = state.lastHistoricalLifeCount
        if (current != null && lifeCount <= current) return
        state = state.copy(lastHistoricalLifeCount = lifeCount)
        persistLifeCounts()
    }

    /** The walk cursor: monotonic max + persist-on-change (accepted-progress rule). */
    private suspend fun advanceFloor(lifeCount: Int) {
        val current = state.lastHistoricalFloorLifeCount
        if (current != null && lifeCount <= current) return
        state = state.copy(lastHistoricalFloorLifeCount = lifeCount)
        persistLifeCounts()
    }

    /** Fail-closed persist (Ct5ConnectedSource.persistSecret pattern): logged, never thrown. */
    private suspend fun persistLifeCounts() {
        try {
            val stored = repository.loadSensorSecret(sourceId)
            // A tap mid-stream saves a new PIN; this session must not write the old one back.
            if (stored != null && Libre3SensorState.decode(stored)?.provisionedAtMs != sensor.provisionedAtMs) {
                log.w(TAG, "life counts for ${sensor.serial} not stored: the blob on record is another provision")
                return
            }
            repository.saveSensorSecret(sourceId, state.encode())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Blob contents are never logged (PIN/kAuth ride inside); the loss costs one
            // catch-up backfill after a restart, not the link.
            log.e(TAG, "the life counts for ${sensor.serial} were not stored: ${e.javaClass.simpleName}")
        }
    }

    /** §11/§14 phase 6 manual fetch: kit pair, top-up, walk; one command in flight at a time. */
    suspend fun requestBackfill() {
        val plane = handle
        if (plane == null) {
            log.i(TAG, "backfill refused — the data plane is not armed")
            return
        }
        // §14 phase 6: kit pair, live-edge top-up, walk; a reconnect runs only sendGapTopUp.
        backfillRound(plane, liveLifeCount.value ?: state.lastRealtimeLifeCount)
    }

    /**
     * §14 phase 6, live-proven 2026-09-24: the sensor ignores open-ended ≥ requests (ACK
     * `01 01 08 00`/`01 01 40 00`, no pages) and streams pages only for a BOUNDED range
     * (`01 <stream> <selector> <start_LE2> <end_LE2>` — the vendor's BackfillRange shape).
     * A fetch round has three parts, all bounded:
     *
     *  1. the kit's reconnect pair (≥ the accepted bound) — manual tap only;
     *  2. the live-edge top-up — one range over the most recent committed window;
     *  3. the walk (manual tap only) — bounded windows ascending from the persisted floor
     *     [floor, floor+W], … up to where the top-up takes over, filling the wear's gap.
     *
     * A window advances the floor only when its writes CONFIRMED (live 17:24: a busy link
     * rejects writes and the sensor never sees the command).
     */
    private suspend fun backfillRound(plane: Libre3DataPlaneHandle, liveCount: Int?) {
        backfillLock.withLock {
            val lowerBound = state.lastHistoricalLifeCount ?: 0
            val historical = historicalBackfillCmd(lowerBound) ?: run {
                log.w(TAG, "backfill skipped — the historical command could not be built")
                return
            }
            sendPatchControl(plane, historical, "historical ≥ $lowerBound")
            val clinical = clinicalBackfillCmd(lowerBound)
            if (clinical != null) sendPatchControl(plane, clinical, "clinical ≥ $lowerBound")
            walkGap(plane, sendTopUp(plane, liveCount))
        }
    }

    /**
     * The live-edge top-up: one range over the most recent [RANGE_PROBE_WINDOW_MIN] committed
     * minutes. Covers what a disconnect's missed minutes cost (the auto reconnect round's
     * payload). Returns the window's start — the walk's natural ceiling — or null when the
     * probe is skipped (no realtime count yet, or nothing committed past the accepted bound).
     */
    private suspend fun sendTopUp(plane: Libre3DataPlaneHandle, liveCount: Int?): Int? {
        if (liveCount == null) {
            log.i(TAG, "top-up skipped — no realtime life count yet")
            return null
        }
        val window = topUpWindow(liveCount) ?: return null
        // An unconfirmed top-up means the link is busy: the walk below would fail too.
        if (!sendRange(plane, window.first, window.last)) return null
        return window.first
    }

    /** Historical only (clinical is never stored), first to last window minute the store lacks. */
    private suspend fun sendGapTopUp(plane: Libre3DataPlaneHandle, liveCount: Int) {
        val window = topUpWindow(liveCount) ?: return
        val missing = missingMinutes(window)
        if (missing == null) {
            log.i(TAG, "top-up skipped — every slot in [${window.first}, ${window.last}] is stored")
            return
        }
        sendRange(plane, missing.first, missing.last, withClinical = false)
    }

    /** [floor or end − 120, live − 17]; null when nothing is committed past the floor. */
    private fun topUpWindow(liveCount: Int): IntRange? {
        val end = liveCount - COMMITTED_HISTORY_LAG_MIN
        val start = maxOf(state.lastHistoricalFloorLifeCount ?: 0, end - RANGE_PROBE_WINDOW_MIN)
        if (end <= start) {
            log.i(TAG, "top-up skipped — nothing committed past the floor ${state.lastHistoricalFloorLifeCount}")
            return null
        }
        return start..end
    }

    /** By grid slot, not minute: one 5-min page sample covers its slot's other four minutes. */
    private suspend fun missingMinutes(window: IntRange): IntRange? {
        val sinceMs = lifeCountMs(window.first) - CgmConstants.GRID_MS
        val held = repository.receivedSampleMinutes(sourceId, notBeforeMs = sinceMs).minutes
        val covered = held.mapTo(HashSet()) { gridStamper.snap(lifeCountMs(it)) }
        fun lacks(lifeCount: Int) = gridStamper.snap(lifeCountMs(lifeCount)) !in covered
        val first = window.firstOrNull(::lacks) ?: return null
        return first..window.last(::lacks)
    }

    /**
     * The walk: bounded [WALK_WINDOW_MIN] windows ascending from the persisted floor up to
     * [topUpStart] (where the top-up takes over), both streams per window. A window advances
     * the floor ONLY when its writes were actually CONFIRMED — a window the GATT refused was
     * never asked (live 2026-09-24: a busy link rejects writes in ~400 ms), so the walk stops
     * there and the next tap retries from the floor. Capped at [WALK_MAX_WINDOWS] per tap.
     */
    private suspend fun walkGap(plane: Libre3DataPlaneHandle, topUpStart: Int?) {
        if (topUpStart == null) return
        var bottom = state.lastHistoricalFloorLifeCount ?: 0
        if (bottom >= topUpStart) return
        var windows = 0
        while (bottom < topUpStart && windows < WALK_MAX_WINDOWS) {
            val hi = minOf(bottom + WALK_WINDOW_MIN, topUpStart)
            if (!sendRange(plane, bottom, hi)) {
                log.w(TAG, "backfill walk stopped at [$bottom, $hi] — the write was not confirmed; re-tap later")
                return
            }
            bottom = hi
            advanceFloor(bottom)
            windows++
        }
        log.i(TAG, "backfill walk done: $windows window(s), floor → $bottom (top-up starts at $topUpStart)")
    }

    /** One bounded range, historical then clinical; true only when every write confirmed. */
    private suspend fun sendRange(
        plane: Libre3DataPlaneHandle,
        start: Int,
        end: Int,
        withClinical: Boolean = true,
    ): Boolean {
        val historical = historicalBackfillRangeCmd(start, end) ?: run {
            log.w(TAG, "range [$start, $end] skipped — the historical range command could not be built")
            return false
        }
        if (!sendPatchControl(plane, historical, "historical range [$start, $end]")) return false
        if (!withClinical) return true
        val clinical = clinicalBackfillRangeCmd(start, end) ?: return true
        return sendPatchControl(plane, clinical, "clinical range [$start, $end]")
    }

    /** Written once, never resent; true only when confirmed AND answered by the sensor. */
    private suspend fun sendPatchControl(plane: Libre3DataPlaneHandle, plaintext: ByteArray, what: String): Boolean {
        val frame = try {
            plane.nextPatchControlFrame(plaintext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A tap races the stream's teardown: uniffi throws on a closed session.
            log.w(TAG, "backfill stopped — the $what frame threw ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        if (frame == null) {
            log.w(TAG, "backfill skipped — the $what frame could not be encrypted")
            return false
        }
        val reply = CompletableDeferred<ByteArray?>()
        pendingReply = reply
        val done = CompletableDeferred<Boolean>()
        val history = CgmLogTopic.HISTORY
        log.dec(null, "patchControl ← $what", history, bytes = plaintext, opens = true)
        transport.writeChunk(Libre3DataChar.PatchControl.uuid, frame) { ok -> done.complete(ok) }
        val sent = withTimeoutOrNull(BACKFILL_WRITE_MS) { done.await() } == true
        if (sent) {
            log.i(TAG, "$what backfill sent (${frame.size} B frame)", history)
        } else {
            log.w(TAG, "$what backfill not confirmed (${frame.size} B frame)", history)
        }
        if (!sent) return false
        if (withTimeoutOrNull(BACKFILL_REPLY_MS) { reply.await() } == null) {
            log.w(TAG, "$what backfill unanswered in $BACKFILL_REPLY_MS ms")
            return false
        }
        return true
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "Libre3Data"

        /** §5.7 warmup = 60 min default (SensorLifecycle.swift L10); also the page flag rule. */
        const val WARMUP_WINDOW_MIN = 60

        /** §5.10: seven acked CCCD writes; HyperOS connect alone took 10 s live (PROGRESS). */
        const val ENABLE_MS = 30_000L

        /** §5.10 write confirmation pacing, as Libre3Session's chunk budget. */
        const val WRITE_CHUNK_MS = 2_000L

        /**
         * Backfill write budget: the live 2026-09-24 first fetch saw the write confirmation
         * timeout fire at 2 s while the sensor's ACK was still inbound (2.2 s); a busy GATT
         * queue (1/min realtime + patchStatus) justifies the wider budget on this path.
         */
        const val BACKFILL_WRITE_MS = 10_000L

        /** Forward-skew band for the sensor sample clock (5 min; Ct5's sampleInstant spirit). */
        const val MAX_FORWARD_SKEW_MS = 5 * 60_000L

        /** §5.7 live-edge top-up window: the most recent committed minutes (the proven probe). */
        const val RANGE_PROBE_WINDOW_MIN = 120

        /** The walk's per-window span; pages ride 5-min strides, clinical rides minute records. */
        const val WALK_WINDOW_MIN = 120

        /** Runaway guard: ≤ 24 windows (2 days) per tap, so the spinner always ends. */
        const val WALK_MAX_WINDOWS = 24

        /** A clinical 120-min range completes in ~15 s (live 19:45). */
        const val BACKFILL_REPLY_MS = 20_000L

        /** The arm round waits out the CCCD burst + the sensor's own settle before writing. */
        const val ARM_BACKFILL_DELAY_MS = 5_000L

        /** Realtime lands 1/min; a session silent this long has no live edge to top up to. */
        const val ARM_LIVE_WAIT_MS = 3 * 60_000L

        /** Set once rows filed with hundredths as tenths are ÷10; a wipe clears it and them. */
        const val RATE_REPAIR_KEY = "libre3.rate_tenths_repaired"

        /** 2026-09-27T00:00Z, past the last unscaled write; a wipe+restore re-run spares newer. */
        const val RATE_REPAIR_BEFORE_MS = 1_790_467_200_000L

        /** The sensor's committed history lags realtime by ~17 min (ClinicalReadingRecord.swift
         *  HISTORIC_POINT_LATENCY); every range's end stays below the realtime count. */
        const val COMMITTED_HISTORY_LAG_MIN = 17
    }
}

/** 1/100 → 1/10 mg/dL/min, half away from zero, as the stored-row repair's SQL ROUND. */
internal fun hundredthsToTenths(raw: Int): Int = (raw + if (raw < 0) -5 else 5) / 10

/** Keyed on the native `Libre3Trend` Display spellings; notDetermined and raw(n) fall to a fit. */
internal fun libre3Arrow(trendKind: String): SensorArrow = when (trendKind) {
    "fallingQuickly" -> SensorArrow.FALLING_FAST
    "falling" -> SensorArrow.FALLING
    "stable" -> SensorArrow.FLAT
    "rising" -> SensorArrow.RISING
    "risingQuickly" -> SensorArrow.RISING_FAST
    else -> SensorArrow.UNDETERMINED
}