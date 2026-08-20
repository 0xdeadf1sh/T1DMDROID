package com.t1dm.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.CgmRawSample
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.EventTombstone
import com.t1dm.core.model.isRealMeasurement
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ForecastWindow
import com.t1dm.core.model.ForecastWindowSet
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.ReconstructedBg
import com.t1dm.core.model.RecentMeal
import com.t1dm.data.backup.ArchiveCounts
import com.t1dm.data.backup.ArchiveReader
import com.t1dm.data.backup.ArchiveResult
import com.t1dm.data.backup.ArchiveWriter
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmAdvertRawEntity
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.CgmSensorSecretEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.DoseEventEntity
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.EventTombstoneEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.TOMBSTONE_KIND_DOSE
import com.t1dm.data.db.TOMBSTONE_KIND_MEAL
import com.t1dm.data.db.actingUntilMs
import com.t1dm.data.db.toModel as infillToModel
import com.t1dm.data.db.affectsChannel
import com.t1dm.data.db.curveAfterEdit
import com.t1dm.data.db.toModel as tombstoneToModel
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.HwTelemetryEntity
import com.t1dm.data.db.KvEntity
import com.t1dm.data.db.NS_ENTRY_DEDUP_PREFIX
import com.t1dm.data.db.NS_TREATMENT_DEDUP_PREFIX
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.PaintStrokeDao
import com.t1dm.data.db.PredictionEntity
import com.t1dm.data.db.BgInfillEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.LoraEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.db.SampleEntity
import com.t1dm.data.db.SampleWindowFingerprint
import com.t1dm.data.db.SavedMealEntity
import com.t1dm.data.db.SavedMealItemEntity
import com.t1dm.data.db.ServerProfileEntity
import com.t1dm.data.db.StepBucketRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * The single write/read gateway over [AppDatabase] (§3.5). Every mutation runs on
 * [T1dmDispatchers.io]; contributing-event writes atomically re-project the wide `sample` row and
 * thinly enqueue an `INGEST` outbox item (drained in Phase 3). Reads surface [Flow]s.
 *
 * Invariants enforced here rather than in the schema:
 *  - grid alignment: every `sample`/`cgm_reading` timestamp satisfies `ts % GRID_MS == 0`;
 *  - grid upsert-in-place: `(sourceId, tsMs)` is the reading key, so a MEASURED value overwrites a
 *    prior INTERPOLATED one at the same slot (SPEC §3.1);
 *  - projection provenance: INVALID readings never reach `sample`; the sample carries the bg
 *    provenance/flag so downstream (alarm, graph) can honour §3.6-A.
 */
/** How far a source's stored record actually reaches, both ends inclusive. */
data class ReadingExtent(val oldestMs: Long, val newestMs: Long)

/**
 * One grid slot's BG as it stood before a cut — everything an undo needs to put it back.
 *
 * Carries the ROWS, not a value: a slot can hold a reading from every source that ever wrote it,
 * each with its own provenance and flag, and `sample` holds the resolved four fields separately.
 * Restoring from a single mg/dL number would file whatever came back as an ordinary measurement,
 * which is exactly the claim `isRealMeasurement` exists to refuse.
 */
data class BgCut(
    val ts: Long,
    val readings: List<CgmReadingEntity>,
    val bgMgdl: Int?,
    val bgSource: String?,
    val bgProvenance: ReadingProvenance?,
    val bgFlag: ReadingFlag?,
)

class T1dmRepository(
    private val db: AppDatabase,
    private val dispatchers: T1dmDispatchers,
    /**
     * The wall clock, for the one thing in this class that is about NOW rather than about an event:
     * an outbox row's `createdAtMs`, which is its write instant.
     *
     * Every other timestamp here is passed in by the caller and stays that way. This one cannot be:
     * a reading now reaches [upsertReading] either from a live sensor, where its own `rxWallMs` IS
     * now, or recovered from a sensor's internal store hours or days after it was taken — and using
     * the event instant for a queue row makes the queue believe it was written then. See
     * [upsertReading].
     */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OutboxSink {
    private val io get() = dispatchers.io

    /**
     * A monotonically-advancing tick bumped on every logged-meal / logged-dose write. Meal/dose events
     * no longer project onto the wide `sample` row (the carb/bolus/basal scalar columns were retired,
     * §3.1), so [observeSampleWrites] no longer fires on a log — this is the trigger the IOB/COB read-out
     * subscribes to so a just-logged dose refreshes it immediately instead of waiting for the next CGM
     * reading (fixes the fresh-log staleness the sample-projection used to cover).
     */
    private val _logEvents = MutableStateFlow(0L)
    val logEvents: StateFlow<Long> = _logEvents.asStateFlow()

    /**
     * Whether an authoritative reading should also queue a Nightscout-bridge row.
     *
     * A plain volatile flag rather than a kv read, because the check sits inside the reading-projection
     * TRANSACTION on the CGM hot path — once per reading, forever — and the answer changes only when the
     * user edits a settings screen. `:app` owns it: it is set at composition from the persisted config
     * and re-set whenever the bridge is saved. Defaulting to false is what keeps a bridge nobody has
     * configured from queueing rows that could only ever be dropped.
     */
    @Volatile
    var nightscoutBridgeEnabled: Boolean = false

    private val sources get() = db.cgmSourceDao()
    private val readings get() = db.cgmReadingDao()
    private val rawSamples get() = db.cgmRawSampleDao()
    private val sensorSecrets get() = db.cgmSensorSecretDao()
    private val samples get() = db.sampleDao()
    private val doses get() = db.doseEventDao()
    private val loggedDoses get() = db.loggedDoseDao()
    private val loggedMeals get() = db.loggedMealDao()
    private val basalSchedules get() = db.basalScheduleDao()
    private val advertsRaw get() = db.cgmAdvertRawDao()
    private val outbox get() = db.outboxDao()
    private val kv get() = db.kvDao()
    private val telemetry get() = db.hwTelemetryDao()
    private val predictions get() = db.predictionDao()
    private val profiles get() = db.serverProfileDao()
    private val paintStrokes get() = db.paintStrokeDao()
    private val conformalDeltas get() = db.conformalDeltaDao()
    private val loras get() = db.loraDao()
    private val infills get() = db.bgInfillDao()
    private val exerciseSessions get() = db.exerciseSessionDao()
    private val tombstones get() = db.eventTombstoneDao()
    private val exerciseFixes get() = db.exerciseFixDao()

    /**
     * Room 2.7 driver-compatible write transaction. The KTX [androidx.room.withTransaction] uses the
     * legacy `SupportSQLiteOpenHelper.beginTransaction` path, which throws once a [SQLiteDriver] is
     * configured (`BundledSQLiteDriver`, shipped for its FTS5 — see [AppDatabase]). Instead acquire
     * the single writer connection and run a `BEGIN IMMEDIATE` transaction on it: [immediateTransaction]
     * commits when [body] returns and rolls back on any throw (including a `TransactionScope.rollback`).
     *
     * Atomicity of the invariant-critical bodies rests on Room's **connection confinement**: while
     * inside [useWriterConnection] a `ConnectionElement` sits in the coroutine context, so every
     * suspend DAO call made by [body] (reads and writes alike) reuses THIS writer connection and
     * therefore joins THIS transaction rather than acquiring its own — proven by the rollback test in
     * `TransactionTest`. (The only nesting Room forbids is upgrading a reader connection to a writer;
     * we always enter as a writer, so the reads-then-writes mix here is legal.)
     */
    private suspend fun <R> inWriteTx(body: suspend () -> R): R =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { body() }
        }

    // ─── CGM sources ────────────────────────────────────────────────────────────────────────

    fun observeSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeAll().map { list -> list.map { it.toDescriptor() } }

    /**
     * Deduplicated on the DESCRIPTOR, which is what consumers actually read.
     *
     * Room invalidates per TABLE, so any write to `cgm_source` re-runs this query — and [toDescriptor]
     * drops `lastSeenMs`, so a liveness touch yields a descriptor equal to the previous one. Undeduped,
     * that identical value propagated; and because the hot consumers reach it through `flatMapLatest`
     * (the glance combine, `dashboardReadings`), every emission tore down and restarted a downstream
     * query which then emitted again on subscription. One touch became two rebuilds, at whatever rate
     * the write path happened to run.
     */
    fun observeAuthoritativeSource(): Flow<CgmSourceDescriptor?> =
        sources.observeAuthoritative().map { it?.toDescriptor() }.distinctUntilChanged()

    /**
     * Every source the app is currently reading, oldest-registered first — what the BG panel may be
     * switched between and what the CGM panel lists as live.
     *
     * Deduplicated on the descriptors for the reason [observeAuthoritativeSource] is: a `lastSeenMs`
     * touch is a write to `cgm_source`, so it re-runs this query and yields a list equal to the last
     * one, at whatever rate sensors are re-sighted.
     */
    fun observeActiveSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeActiveSources()
            .map { list -> list.map { it.toDescriptor() } }
            .distinctUntilChanged()

    /**
     * Register or update a source, preserving its original `addedAtMs` across updates.
     *
     * `hidden` is preserved from the STORED row for the same reason and with more force: this runs on
     * every re-sighting, from a descriptor the caller has held in memory since before the removal, so
     * taking the flag from the argument would write a removed sensor back onto the list within one
     * scan. An [authoritative] source is un-hidden and activated here rather than merely left alone —
     * the same invariants [CgmSourceDao.setAuthoritative] holds, applied on the path that adopts a
     * source without going through it.
     */
    suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        nowMs: Long,
    ): Int = withContext(io) {
        inWriteTx {
            val existing = sources.byId(descriptor.id.value)
            val ordinal = existing?.ordinal?.takeIf { it >= 0 } ?: (sources.maxOrdinal() + 1)
            sources.upsert(
                CgmSourceEntity(
                    sourceId = descriptor.id.value,
                    vendorId = descriptor.vendorId,
                    sensorModelId = descriptor.sensorModelId,
                    advertName = descriptor.advertName,
                    displayName = descriptor.displayName,
                    serialSuffix = descriptor.serialSuffix,
                    // Preserved from the STORED row unless the caller is promoting. [authoritative]
                    // means "adopt this one", never "this one is not it": a re-sighting passing false
                    // for a source that IS authoritative used to clear the flag and re-set it inside
                    // the same transaction, which raced every concurrent promotion and could leave the
                    // table with no authoritative row at all.
                    authoritative = authoritative || (existing?.authoritative ?: false),
                    // Preserved for a related reason: whether the app is reading a sensor is the user's
                    // standing decision, and a re-sighting must not restart a source they stopped. A
                    // source being promoted is activated below.
                    active = authoritative || (existing?.active ?: false),
                    warmupWindowMin = descriptor.warmupWindowMin,
                    addedAtMs = existing?.addedAtMs ?: nowMs,
                    lastSeenMs = nowMs,
                    hidden = !authoritative && (existing?.hidden ?: false),
                    // Minted ONCE, here, inside the transaction that first records the row — so it is
                    // assigned for both sensor families in one place, and two sensors recorded
                    // concurrently cannot claim the same number. Never revised afterwards: the number is
                    // what a user reads as "this physical sensor". An archive restore does NOT come
                    // through here — it inserts into the table directly — and resolves its own numbers
                    // against the stored ones; see `ArchiveReader.renumbered`.
                    ordinal = ordinal,
                ),
            )
            if (authoritative) {
                sources.clearAuthoritative()
                sources.setAuthoritative(descriptor.id.value)
            }
            ordinal
        }
    }

    /**
     * Give every row still carrying the unassigned sentinel a number, in the order the lists already show
     * them.
     *
     * The last line of defence, not a step of any flow: the migration numbers what was on the phone when
     * it ran, [upsertSource] numbers what the app records, and an archive restore numbers what it inserts.
     * A row reaching the table with the sentinel means one of those three has a hole — and an unnumbered
     * sensor is one the privacy label cannot name at all, so it is worth catching cheaply rather than
     * showing. Runs on the hydrate path: one query on a table with a row per sensor the phone has ever
     * met, writing nothing when there is nothing to number.
     */
    suspend fun assignMissingSourceOrdinals() = withContext(io) {
        val pending = sources.unnumberedSourceIds()
        if (pending.isEmpty()) return@withContext
        inWriteTx {
            var next = sources.maxOrdinal() + 1
            for (sourceId in pending) {
                sources.setOrdinal(sourceId, next)
                next++
            }
        }
    }

    // ─── Sealed per-sensor secrets ────────────────────────────────────────────────────────────────

    /**
     * One sensor's opaque secret, or null when none is held.
     *
     * The bytes are sealed before they arrive and are stored and returned verbatim; nothing here knows
     * what they mean. See [com.t1dm.data.db.CgmSensorSecretEntity] for why they are worth this much care.
     */
    suspend fun sensorSecret(id: CgmSourceId): ByteArray? = withContext(io) {
        sensorSecrets.byId(id.value)?.blob
    }

    suspend fun putSensorSecret(id: CgmSourceId, blob: ByteArray, nowMs: Long) = withContext(io) {
        sensorSecrets.upsert(CgmSensorSecretEntity(sourceId = id.value, blob = blob, updatedAtMs = nowMs))
    }

    /** Forget ONE sensor's secret. Unrecoverable; see [CgmSensorSecretDao.deleteById]. */
    suspend fun deleteSensorSecret(id: CgmSourceId) = withContext(io) {
        sensorSecrets.deleteById(id.value)
    }

    /** Enforce exactly-one-authoritative atomically (SPEC §3.1). The promoted source is activated and
     *  un-hidden by [CgmSourceDao.setAuthoritative]; the one it replaces stays active, so demotion is
     *  not a disconnection. */
    suspend fun setAuthoritativeSource(id: CgmSourceId) = withContext(io) {
        inWriteTx {
            sources.clearAuthoritative()
            sources.setAuthoritative(id.value)
        }
    }

    suspend fun authoritativeSourceId(): CgmSourceId? = withContext(io) {
        sources.authoritativeSourceId()?.let(::CgmSourceId)
    }

    /** Start reading [id]. Additive — nothing else stops. */
    suspend fun activateSource(id: CgmSourceId) = withContext(io) { sources.activate(id.value) }

    /** Stop reading [id]. Refuses the authoritative source ([CgmSourceDao.deactivate]), which is the
     *  invariant the caller relies on rather than re-checks. */
    suspend fun deactivateSource(id: CgmSourceId) = withContext(io) { sources.deactivate(id.value) }

    /** Every source the app is currently reading, for the callers that need it once rather than
     *  observed — the connected coordinator deciding which sessions to hold. */
    suspend fun activeSourceIds(): List<CgmSourceId> = withContext(io) {
        sources.activeSourceIds().map(::CgmSourceId)
    }

    /**
     * Retune [id]'s warm-up window (minutes). Clamped to [CgmSourceDescriptor.WARMUP_WINDOW_RANGE] here
     * rather than trusted from the caller: this is the one door into the column, and a nonsensical
     * window would silently mis-flag every reading the source produces.
     */
    suspend fun setSourceWarmupWindowMin(id: CgmSourceId, minutes: Int) = withContext(io) {
        val clamped = minutes.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE)
        sources.setWarmupWindowMin(id.value, clamped)
    }

    /** Take a retired sensor off the sensor lists. Its readings stay: the row remains, so the BG
     *  panel's model-wide history still selects them ([CgmSourceDao.hide] refuses the authoritative source). */
    suspend fun hideSource(id: CgmSourceId) = withContext(io) { sources.hide(id.value) }

    // ─── Readings + wide-sample projection ──────────────────────────────────────────────────

    fun observeReadings(sourceId: CgmSourceId, fromMs: Long, toMs: Long): Flow<List<CgmReading>> =
        readings.observeRange(sourceId.value, fromMs, toMs).map { list -> list.map { it.toModel() } }

    /**
     * The same window over every source of one sensor MODEL, collapsed to one reading per grid slot
     * (§3.1) — what the BG panel draws.
     *
     * A `sourceId` dies with the sensor it names, so a source-scoped panel emptied itself every time a
     * sensor was replaced; [sensorModelId] is the family, and history is continuous across it.
     * [selectedSourceId] resolves a contested slot only — it does not filter, and passing null merely
     * falls through to the newest reception. Nothing here widens AUTHORITY: the live value, the alarm
     * engine and the `sample` projection all remain scoped to the one authoritative source.
     *
     * Two queries rather than a join, and the id list deduplicated between them. Room invalidates per
     * TABLE, so a joined query would re-run on every `cgm_source` write — including the `lastSeenMs`
     * touch each re-sighting performs — and re-materialise the whole never-pruned history at scan
     * rate. Deduplicating the projection means the readings query is torn down and restarted only when
     * the class's membership genuinely changes, which is when a sensor is first met.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeReadingsForSensorModel(
        sensorModelId: String,
        selectedSourceId: CgmSourceId?,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<CgmReading>> =
        sources.observeIdsForSensorModel(sensorModelId)
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                // SQLite rejects `IN ()`, which is what Room emits for an empty list — and a class with
                // no source has no history to draw anyway.
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    readings.observeRangeForSources(ids, fromMs, toMs).map { rows ->
                        collapseByGridSlot(rows, selectedSourceId?.value).map { it.toModel() }
                    }
                }
            }
            // The collapse and the entity→domain pass walk every row of the window, and the sole
            // consumer collects this in composition — without this they would run on the main thread.
            // Room already emits its query off-main; it is the operators above that need it.
            .flowOn(io)

    /**
     * How far back this sensor model's record actually goes, or null while it holds nothing.
     *
     * The companion to [observeReadingsForSensorModel]'s window. The panel loads a recent slice —
     * loading a lifetime re-materialises every reading of every sensor ever worn on each new reading,
     * which grows without bound — but the graph's pannable domain has to reach the whole record, or
     * a meal logged before the sensor was replaced becomes unreachable again. So the trace is
     * windowed and the FLOOR is read separately, one aggregate rather than a hundred thousand rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOldestTsForSensorModel(sensorModelId: String): Flow<Long?> =
        sources.observeIdsForSensorModel(sensorModelId)
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(null) else readings.observeOldestTsForSources(ids)
            }
            .distinctUntilChanged()
            .flowOn(io)

    /** Deduplicated for the reason [observeAuthoritativeSource] is: `cgm_reading` is written more often than
     *  its newest row changes, and every one of those writes otherwise reached the glance combine and
     *  the dashboard. */
    fun observeLatestReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatest(sourceId.value).map { it?.toModel() }.distinctUntilChanged()

    /**
     * The newest row that is a real MEASUREMENT — what every glance surface reads its glucose,
     * trend and staleness from.
     *
     * Separate from [observeLatestReading] rather than filtered at each call site: a promoted
     * reconstruction is a row in `cgm_reading` like any other, and handing one to a glance would
     * put a model's number on the lock screen as the patient's glucose.
     */
    fun observeLastMeasuredReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatestMeasured(sourceId.value).map { it?.toModel() }.distinctUntilChanged()

    /** The most recent [limit] readings for [sourceId], newest first (Phase-2 inference context). */
    suspend fun recentReadings(sourceId: CgmSourceId, limit: Int): List<CgmReading> =
        withContext(io) { readings.recent(sourceId.value, limit).map { it.toModel() } }

    /** How far back [sourceId]'s record goes, or null when it holds nothing. */
    suspend fun readingExtent(sourceId: CgmSourceId): ReadingExtent? = withContext(io) {
        val oldest = readings.oldestTs(sourceId.value) ?: return@withContext null
        val newest = readings.newestTs(sourceId.value) ?: return@withContext null
        ReadingExtent(oldest, newest)
    }


    /**
     * File one accepted reading: keep it verbatim in the sub-grid record, then let it contest its
     * five-minute slot; if it takes the slot and is on the authoritative source and not INVALID,
     * project its bg into `sample` (LWW on `rxWallMs`) and enqueue one INGEST item for that slot.
     *
     * Both writes ride the one transaction, so the sub-grid record and the grid series can never
     * disagree about whether a sample was received.
     *
     * **The raw row first, and unconditionally.** It is filed under the receive instant, so it has no
     * contest to lose: a sample that gives up the slot is exactly the sample this store exists to
     * keep. Only a `MEASURED` reading reaches it — a gap-fill is fabricated by the stamper and was
     * never received, so it has no receive instant to be filed under.
     *
     * **Then the slot.** [supersedesGridSlot] decides, and where it says no this returns having
     * changed nothing but the raw store: not the reading, not `sample`, not the outbox. Skipping the
     * projection is what keeps `sample.bgMgdl` equal to the reading that won, and it is the ONLY thing
     * that keeps them equal — the projection itself no longer second-guesses the decision (see
     * [projectedBgSample]). The extra read this costs is one primary-key seek.
     */
    suspend fun upsertReading(reading: CgmReading) = withContext(io) {
        requireGrid(reading.tsMs)
        inWriteTx {
            if (reading.provenance == ReadingProvenance.MEASURED) {
                rawSamples.insertIgnore(reading.toRawEntity())
            }
            val entity = reading.toEntity()
            val stored = readings.byTs(entity.sourceId, entity.tsMs)
            if (!supersedesGridSlot(stored, entity)) return@inWriteTx
            readings.upsert(entity)
            val authoritative = sources.authoritativeSourceId()
            if (authoritative == reading.sourceId.value && reading.flag != ReadingFlag.INVALID) {
                // A real measurement has landed in a slot a model reconstructed, so the
                // reconstruction of it is stale by construction — a sensor's own history pull, or a
                // catch-up, can recover a gap days later, and leaving the fill would draw a band
                // around a real reading.
                //
                // Placed HERE, after the slot contest and inside the authoritative branch, and
                // gated on a real measurement: a non-authoritative sensor's row, or one flagged
                // WARMUP, must not take the band away from a slot it does not own. A PROMOTED span
                // is spared outright — its row is the only copy of the band the stored sample
                // carries, and deleting it would leave a reconstruction with no uncertainty and no
                // way back. The measurement still wins the slot; the promoted span is demoted by a
                // deliberate act, not by a side effect.
                if (isRealMeasurement(reading.provenance, reading.flag)) {
                    val fill = infills.at(reading.tsMs)
                    if (fill != null && fill.promotedAtMs == null) infills.deleteAt(reading.tsMs)
                }
                projectBg(reading)
                // `createdAtMs` is the WRITE instant, never the event's — see [enqueueRow]. They are
                // the same number for a live reading and days apart for one recovered from a sensor's
                // own store, and passing the event instant there would age-evict the whole recovery
                // before a single attempt (the queue would read it as having been written when the
                // sample was taken) and sort what survived ahead of every alert queued afterwards.
                val queuedAt = nowMs()
                enqueueIngest(reading.tsMs, queuedAt)
                // The bridge is fed HERE and not from `enqueueIngest`, which the scalar merge path also
                // calls: a steps or mood write would otherwise re-queue a slot whose BG has not changed,
                // and the receiver has no idempotency key to absorb the re-upload with.
                if (nightscoutBridgeEnabled) {
                    enqueueRow(
                        OutboxKind.NIGHTSCOUT,
                        "$NS_ENTRY_DEDUP_PREFIX${reading.tsMs}",
                        ByteArray(0),
                        queuedAt,
                        // Held until the slot closes. The marker only coalesces while it is still
                        // QUEUED, and drains run far more often than every five minutes — so without
                        // this a sub-grid sensor whose later sample supersedes the slot re-queues a key
                        // that has already drained and uploads the same `date` twice, which the
                        // receiver has no idempotency key to absorb.
                        notBeforeMs = reading.tsMs + GRID_MS,
                    )
                }
            }
        }
    }

    private suspend fun projectBg(reading: CgmReading) {
        val base = samples.byTs(reading.tsMs)
            ?: emptySample(reading.tsMs, reading.tzOffsetMin, reading.rxWallMs)
        samples.upsert(projectedBgSample(base, reading))
    }

    // ─── Samples ────────────────────────────────────────────────────────────────────────────

    /**
     * A CHANGE SIGNAL for the wide projection — emits once on collect and again on every write to
     * `sample`, carrying only the newest grid ts.
     *
     * Room invalidates per TABLE, so this fires on exactly the same events an `observeRange` over the
     * table fires on; the difference is that it materialises one `Long` instead of every row. A
     * consumer that only needs to know "something in `sample` moved" — the IOB/COB read-out being the
     * only one that does — used to subscribe to an unbounded `observeRange` and discard the list,
     * which loaded the whole never-pruned projection into the heap on every reading, forever. That
     * whole-table observer is gone with its last caller rather than left as a trap. Deliberately
     * NOT deduplicated: the emission, not the value, is the signal, and a mood rewritten into an
     * existing slot leaves the maximum unchanged while still being a write the consumer wants.
     */
    fun observeSampleWrites(): Flow<Long?> = samples.observeMaxTs()

    suspend fun sampleAt(ts: Long): SampleEntity? = withContext(io) { samples.byTs(ts) }

    /**
     * The AUTHORITATIVE source's trend at one grid slot, in tenths of mg/dL per minute, or null.
     *
     * The `sample` projection carries BG but not trend, and the Nightscout bridge needs the arrow.
     * Authoritative rather than active on purpose: a second worn sensor may be read and drawn without
     * being believed (see `CgmSourceEntity`), and the arrow must come from the same source as the
     * number beside it or the two would disagree on screen for no visible reason.
     */
    suspend fun authoritativeTrendAt(ts: Long): Int? = withContext(io) {
        val sourceId = sources.authoritativeSourceId() ?: return@withContext null
        readings.byTs(sourceId, ts)?.trendTenthsPerMin
    }

    /**
     * Newest grid ts at or before [atMs] — the WS-connect catch-up cursor.
     *
     * Bounded, and not a bare `MAX(ts)`, because [recordExerciseCurve] writes a bout's ninety-minute
     * tail into slots ahead of the clock. Taking the raw maximum as the cursor would ask the server
     * for rows newer than a timestamp that has not happened yet, and the catch-up would fetch nothing
     * at all until wall time caught up with the tail.
     */
    suspend fun newestSampleTsAtOrBefore(atMs: Long): Long? =
        withContext(io) { samples.maxTsAtOrBefore(atMs) }

    /** Windowed wide-sample read for the stats recompute (Phase 6); oldest-first. */
    suspend fun samplesInRange(fromMs: Long, toMs: Long): List<SampleEntity> =
        withContext(io) { samples.rangeList(fromMs, toMs) }

    /** The `[fromMs, toMs]` window's staleness fingerprint — see [SampleWindowFingerprint]. */
    suspend fun sampleWindowFingerprint(fromMs: Long, toMs: Long): SampleWindowFingerprint =
        withContext(io) { samples.windowFingerprint(fromMs, toMs) }

    /** Steps summed over `[fromMs, toMs]` — for a caller that wants the total and not the buckets. */
    suspend fun stepsInRange(fromMs: Long, toMs: Long): Int =
        withContext(io) { samples.stepsInRange(fromMs, toMs) }

    /** The per-bucket step series over `[fromMs, toMs]`, oldest-first, with the empty buckets already
     *  dropped — for the caller that wants the buckets and not the total. */
    suspend fun stepSeriesInRange(fromMs: Long, toMs: Long): List<StepBucketRow> =
        withContext(io) { samples.stepSeriesInRange(fromMs, toMs) }

    /** Steps arrive already bucketed on the 5-min grid by :sensors (SPEC §3.5). */
    suspend fun recordSteps(gridTs: Long, tzOffsetMin: Int, steps: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(steps = steps) }

    suspend fun recordMood(gridTs: Long, tzOffsetMin: Int, mood: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(mood = mood) }

    /**
     * Fold a bout's glucose-disposal curve into the wide sample, one grid slot per [buckets] entry.
     *
     * **The unit is grams of carbohydrate equivalent per five-minute bucket** — the carbohydrate the
     * disposal offsets — per `../T1DMCOMMON/SPEC/invariants.md` §3, and the series is §5's exercise
     * gamma: shape resolved by [com.t1dm.data.curve.ExerciseDisposal], curve built by the one
     * `CurveEngine.gamma` the carbohydrate channel goes through. It is never a duration, an
     * intensity or an energy. Seconds live on in `exercise_session.activeSec`, which is phone-local
     * and crosses no wire; the ACSM kcal figure stays display-only.
     *
     * **The whole curve is ONE transaction, and that is the point of taking a list.** A bout's curve
     * is rewritten from scratch whenever its duration grows — magnitude scales with duration, so
     * every bucket moves at once — and a bucket-at-a-time writer would fire Room's per-table
     * invalidation, and the dashboard recompositions behind it, once per bucket instead of once per
     * rewrite. It also means a partially-rewritten curve is never visible: the buckets sum to the
     * event total or none of them changed (§5).
     *
     * **The curve runs past `now`, by up to the ninety minutes of §5's tail.** That is inherent to
     * the definition — a curve sums to its event's total, and the total is only complete once the
     * tail is written — and it makes this the one writer that mints `sample` rows ahead of the
     * clock. [newestSampleTsAtOrBefore] exists because of it.
     *
     * **Within one bout a bucket is SET; across bouts it ADDS.** [recordSteps] can set outright
     * because the step recorder reads one continuous hardware total, but a bout is recorded from
     * zero, so a bout beginning in a slot an earlier bout already wrote would otherwise overwrite
     * it — stop at 09:07, restart at 09:08, and the 09:05 slot would end up holding the second
     * bout's grams instead of both. Carrying what this bout last wrote makes the write idempotent
     * (a rewrite replaces its own contribution) while leaving anything another bout put there
     * intact. See [mergedExerciseGrams].
     */
    suspend fun recordExerciseCurve(buckets: List<ExerciseCurveBucket>, nowMs: Long) = withContext(io) {
        if (buckets.isEmpty()) return@withContext
        inWriteTx {
            for (b in buckets) {
                mergeSampleInTx(b.gridTs, b.tzOffsetMin, nowMs) {
                    it.copy(exercise = mergedExerciseGrams(it.exercise, b.priorGrams, b.grams))
                }
            }
        }
    }

    /**
     * Log a Phase-1 discrete dose (`dose_event`). This legacy minimal store is superseded by
     * [logLoggedDose] (self-describing curve + server push); it no longer projects into `sample` —
     * the `bolus`/`basal` scalar columns are retired, insulin being a self-describing curve event (§3.1).
     */
    suspend fun logDose(dose: DoseEventEntity): Long = withContext(io) { doses.insert(dose) }

    // ─── Curve-engine event stores (Room v3, SPEC §3.3) ──────────────────────────────────────

    /**
     * Log an insulin dose with its full curve params (`logged_dose`) — the self-describing event the
     * reconstructed insulin channel reads AND the row a `PUT /v1/doses` mirrors (pushed at the `:app`
     * seam). This repository writer is the single authority for the two invariants the event must
     * satisfy: it grid-snaps `tsMs` (round-to-nearest, §4-#1) so the event bucket agrees with its own
     * sample bucket, and it mints the phone `clientId` (§3.2) when the caller left it blank (never
     * re-minted). No `sample` projection (the `bolus`/`basal` scalar columns are retired, §3.1).
     *
     * It also stamps `loggedAtMs` when the caller left it at its default, for the same reason it
     * mints the id here rather than at each of the five call sites: the log-gap mark is
     * `MAX(MIN(tsMs, loggedAtMs))`, so a single unstamped row pins the whole mark at zero and the
     * rail then reports a gap of the entire Unix epoch on every recommendation, forever. A caller
     * that carries its own stamp — a catch-up hydrating a server-authored row, a restore — keeps
     * it.
     *
     * Returns the PERSISTED entity — snapped `tsMs`, minted `clientId`, DB rowid — so the `:app` seam
     * builds the matching `PUT /v1/doses` DTO from it and app + wire agree on one id and one grid ts.
     */
    suspend fun logLoggedDose(dose: LoggedDoseEntity): LoggedDoseEntity = withContext(io) {
        val row = dose.copy(
            clientId = dose.clientId.ifBlank { newClientId() },
            tsMs = snapToGrid(dose.tsMs),
            loggedAtMs = dose.loggedAtMs.takeIf { it != 0L } ?: nowMs(),
        )
        row.copy(id = loggedDoses.insert(row)).also { _logEvents.update { t -> t + 1 } }
    }

    /**
     * Log a meal (`logged_meal`) — the self-describing appearance-curve event the reconstructed carb
     * channel reads AND the row a `PUT /v1/meals` mirrors (pushed at the `:app` seam). As with
     * [logLoggedDose] this writer is the single snap+id+stamp authority: it grid-snaps `tsMs`
     * (round-to-nearest, §4-#1), mints the phone `clientId` (§3.2) when the caller left it blank
     * (never re-minted), and stamps `loggedAtMs`. No `sample` projection (the `carbs` scalar is
     * retired, §3.1).
     *
     * Returns the PERSISTED entity — snapped `tsMs`, minted `clientId`, DB rowid — so the `:app` seam
     * builds the matching `PUT /v1/meals` DTO from it and app + wire agree on one id and one grid ts.
     */
    suspend fun logMeal(meal: LoggedMealEntity): LoggedMealEntity = withContext(io) {
        val row = meal.copy(
            clientId = meal.clientId.ifBlank { newClientId() },
            tsMs = snapToGrid(meal.tsMs),
            loggedAtMs = meal.loggedAtMs.takeIf { it != 0L } ?: nowMs(),
        )
        row.copy(id = loggedMeals.insert(row)).also { _logEvents.update { t -> t + 1 } }
    }

    // ─── Editing and deleting a logged event (Room v21) ────────────────────────────────────

    /**
     * Rewrite a logged meal in place, keeping its identity.
     *
     * `clientId` and `loggedAtMs` are preserved — the first because the server upserts on it and a
     * re-minted id would create a second record beside the one being corrected, the second because
     * it is the log-gap mark and must not move when the numbers do. `updatedAt` and `mutatedAtMs`
     * take [nowMs], so the wire's ordering guard sees a strictly newer authoring stamp than the
     * create it replaces.
     *
     * The stored appearance curve is rescaled or dropped rather than carried — see
     * [com.t1dm.data.db.curveAfterEdit], which is where the reason is written down.
     *
     * Returns the persisted row, so the caller builds the wire DTO from what was actually stored,
     * exactly as the logging path already does.
     */
    suspend fun editLoggedMeal(row: LoggedMealEntity, nowMs: Long): LoggedMealEntity? =
        withContext(io) {
            val stored = inWriteTx {
                val old = loggedMeals.byId(row.id) ?: return@inWriteTx null
                val next = row.copy(
                    clientId = old.clientId,
                    loggedAtMs = old.loggedAtMs,
                    tsMs = snapToGrid(row.tsMs),
                    customCurve = old.curveAfterEdit(row),
                    updatedAt = maxOf(nowMs, old.updatedAt + 1),
                    mutatedAtMs = nowMs,
                )
                loggedMeals.update(next)
                if (old.affectsChannel(next)) {
                    invalidateForecastDerivedInTx(minOf(old.tsMs, next.tsMs))
                }
                next
            }
            stored?.also { _logEvents.update { t -> t + 1 } }
        }

    /**
     * The dose twin of [editLoggedMeal], with one extra column, and the same curve rule: the action
     * curve a bolus carries is absolute, so correcting 8 U logged as 4 U has to move it.
     *
     * `mutatedActingUntilMs` records where the dose's action curve ended BEFORE the edit, and it is
     * written once — on the first edit — so a chain of edits cannot walk it forward. The
     * dose-history rail reads the later of that and the post-edit end, because the edits that
     * understate IOB most are exactly the ones that shorten the post-edit window: cutting
     * `durationMin` from 360 to 30 would otherwise buy a five-minute block against five and a half
     * hours of wrong IOB.
     */
    suspend fun editLoggedDose(row: LoggedDoseEntity, nowMs: Long): LoggedDoseEntity? =
        withContext(io) {
            val stored = inWriteTx {
                val old = loggedDoses.byId(row.id) ?: return@inWriteTx null
                val next = row.copy(
                    clientId = old.clientId,
                    loggedAtMs = old.loggedAtMs,
                    tsMs = snapToGrid(row.tsMs),
                    customCurve = old.curveAfterEdit(row),
                    updatedAt = maxOf(nowMs, old.updatedAt + 1),
                    mutatedAtMs = nowMs,
                    mutatedActingUntilMs = old.mutatedActingUntilMs ?: old.actingUntilMs(),
                )
                loggedDoses.update(next)
                if (old.affectsChannel(next)) {
                    invalidateForecastDerivedInTx(minOf(old.tsMs, next.tsMs))
                }
                next
            }
            stored?.also { _logEvents.update { t -> t + 1 } }
        }

    /**
     * Delete a logged meal, leaving a tombstone in its place.
     *
     * There is no refusal branch. The wire carries a deletion as an ordinary upsert flagged
     * `deleted`, so it is ordered against the create by `updatedAt` and a redelivery of the record
     * cannot overtake it — which is what the old "the push has already drained, so this cannot be
     * revoked" refusal existed for.
     *
     * `updatedAt` is forced strictly newer than the row it retires rather than trusting [nowMs]: a
     * phone clock that has not advanced, or has moved backwards (`SPEC/invariants.md` §7 names
     * that), would otherwise author a stamp the server correctly ignores, and the deletion would
     * be frozen with no sign of it anywhere.
     *
     * Returns the tombstone so `:app` — which owns the wire envelope — can file the push. The
     * `pushEnqueuedAtMs` column and the connect-time replay cover the crash window between the two.
     */
    suspend fun tombstoneLoggedMeal(rowId: Long, nowMs: Long): EventTombstone? =
        withContext(io) {
            val out = inWriteTx {
                val row = loggedMeals.byId(rowId) ?: return@inWriteTx null
                val tomb = EventTombstoneEntity(
                    clientId = row.clientId,
                    kind = TOMBSTONE_KIND_MEAL,
                    tsMs = row.tsMs,
                    tzOffsetMin = row.tzOffsetMin,
                    updatedAt = maxOf(nowMs, row.updatedAt + 1),
                    createdAtMs = nowMs,
                    pushEnqueuedAtMs = null,
                    actingUntilMs = null,
                )
                tombstones.upsert(tomb)
                loggedMeals.delete(rowId)
                withdrawBridgedTreatment(row.clientId)
                invalidateForecastDerivedInTx(row.tsMs)
                tomb.tombstoneToModel()
            }
            out?.also { _logEvents.update { t -> t + 1 } }
        }

    /** The dose twin of [tombstoneLoggedMeal]. It additionally records where the deleted dose's
     *  action curve ended, because after the delete there is no row left to read a duration off and
     *  the dose-history rail has to keep blocking while that insulin could still be acting. */
    suspend fun tombstoneLoggedDose(rowId: Long, nowMs: Long): EventTombstone? =
        withContext(io) {
            val out = inWriteTx {
                val row = loggedDoses.byId(rowId) ?: return@inWriteTx null
                val tomb = EventTombstoneEntity(
                    clientId = row.clientId,
                    kind = TOMBSTONE_KIND_DOSE,
                    tsMs = row.tsMs,
                    tzOffsetMin = row.tzOffsetMin,
                    updatedAt = maxOf(nowMs, row.updatedAt + 1),
                    createdAtMs = nowMs,
                    pushEnqueuedAtMs = null,
                    actingUntilMs = maxOf(row.actingUntilMs(), row.mutatedActingUntilMs ?: 0L),
                )
                tombstones.upsert(tomb)
                loggedDoses.delete(rowId)
                withdrawBridgedTreatment(row.clientId)
                invalidateForecastDerivedInTx(row.tsMs)
                tomb.tombstoneToModel()
            }
            out?.also { _logEvents.update { t -> t + 1 } }
        }

    /** Deletions whose push has not been filed yet — the connect-time replay's work list. */
    suspend fun unpushedTombstones(): List<EventTombstone> =
        withContext(io) { tombstones.unpushed().map { it.tombstoneToModel() } }

    suspend fun markTombstonePushed(clientId: String, atMs: Long) =
        withContext(io) { tombstones.markPushed(clientId, atMs) }

    /**
     * Apply a deletion another read-write session authored, arriving on catch-up.
     *
     * Idempotent, and ordered: a tombstone already on record with an equal or newer `updatedAt`
     * wins, so a redelivery cannot walk the stamp backward. `pushEnqueuedAtMs` is stamped because
     * this deletion did not originate here and there is nothing for this phone to push.
     */
    suspend fun applyServerTombstone(
        clientId: String,
        kind: CurveKind,
        tsMs: Long,
        tzOffsetMin: Int,
        updatedAt: Long,
        nowMs: Long,
    ) = withContext(io) {
        inWriteTx {
            val known = tombstones.byClientId(clientId)
            if (known != null && known.updatedAt >= updatedAt) return@inWriteTx
            val kindText = if (kind == CurveKind.INSULIN) TOMBSTONE_KIND_DOSE else TOMBSTONE_KIND_MEAL
            val acting = if (kind == CurveKind.INSULIN) {
                loggedDoses.byClientId(clientId)?.let { d ->
                    maxOf(d.actingUntilMs(), d.mutatedActingUntilMs ?: 0L)
                }
            } else {
                null
            }
            tombstones.upsert(
                EventTombstoneEntity(
                    clientId = clientId,
                    kind = kindText,
                    tsMs = tsMs,
                    tzOffsetMin = tzOffsetMin,
                    updatedAt = updatedAt,
                    createdAtMs = nowMs,
                    pushEnqueuedAtMs = nowMs,
                    actingUntilMs = acting,
                ),
            )
            if (kind == CurveKind.INSULIN) {
                loggedDoses.byClientId(clientId)?.let { loggedDoses.delete(it.id) }
            } else {
                loggedMeals.byClientId(clientId)?.let { loggedMeals.delete(it.id) }
            }
            invalidateForecastDerivedInTx(tsMs)
        }
        _logEvents.update { t -> t + 1 }
    }

    /**
     * Apply an edit another read-write session authored, arriving on catch-up.
     *
     * The hydration path is `insertIgnore`, which is right for a create and wrong for an edit: an
     * id-keyed insert against an existing row is dropped, so a second session raising a dose from
     * 4 U to 8 U would never reach this phone and local IOB would stay understated for the life of
     * the row. This is the other direction of the same door the tombstone filter closes.
     *
     * Ordered on `updatedAt`, like every other application of a remote write.
     *
     * A remote edit arms the dose-history rail exactly as a local one does. The wire carries neither
     * mutation column — a DTO is the record, not this phone's history of being told about it — so
     * they are minted here or the rail never sees the edit at all, and a remote reduction of a bolus
     * lowers local IOB with nothing standing in front of the next recommendation. The pre-edit
     * action end is taken from the row being replaced, and only on the first mutation, so a chain of
     * remote edits cannot walk it forward and a shortened `durationMin` cannot shorten the block.
     */
    suspend fun applyServerDoseEdit(ev: LoggedDoseEntity): Boolean = withContext(io) {
        val changed = inWriteTx {
            val old = loggedDoses.byClientId(ev.clientId) ?: return@inWriteTx false
            if (ev.updatedAt <= old.updatedAt) return@inWriteTx false
            val candidate = ev.copy(
                id = old.id,
                loggedAtMs = old.loggedAtMs,
                mutatedAtMs = old.mutatedAtMs,
                mutatedActingUntilMs = old.mutatedActingUntilMs,
            )
            val moved = old.affectsChannel(candidate)
            val next = if (!moved) candidate else candidate.copy(
                mutatedAtMs = maxOf(ev.updatedAt, old.mutatedAtMs ?: Long.MIN_VALUE),
                mutatedActingUntilMs = old.mutatedActingUntilMs ?: old.actingUntilMs(),
            )
            loggedDoses.update(next)
            if (moved) invalidateForecastDerivedInTx(minOf(old.tsMs, next.tsMs))
            true
        }
        if (changed) _logEvents.update { t -> t + 1 }
        changed
    }

    /** The meal twin of [applyServerDoseEdit]. */
    suspend fun applyServerMealEdit(ev: LoggedMealEntity): Boolean = withContext(io) {
        val changed = inWriteTx {
            val old = loggedMeals.byClientId(ev.clientId) ?: return@inWriteTx false
            if (ev.updatedAt <= old.updatedAt) return@inWriteTx false
            val candidate = ev.copy(id = old.id, loggedAtMs = old.loggedAtMs, mutatedAtMs = old.mutatedAtMs)
            val moved = old.affectsChannel(candidate)
            val next = if (!moved) candidate else {
                candidate.copy(mutatedAtMs = maxOf(ev.updatedAt, old.mutatedAtMs ?: Long.MIN_VALUE))
            }
            loggedMeals.update(next)
            if (old.affectsChannel(next)) invalidateForecastDerivedInTx(minOf(old.tsMs, next.tsMs))
            true
        }
        if (changed) _logEvents.update { t -> t + 1 }
        changed
    }

    /** True when the local record already covers a deletion of [clientId] at or after [updatedAt]. */
    private suspend fun tombstoned(clientId: String, updatedAt: Long): Boolean =
        (tombstones.byClientId(clientId)?.updatedAt ?: Long.MIN_VALUE) >= updatedAt

    /**
     * Drop everything derived from a forecast that was computed over a window this mutation moved.
     *
     * The single owner of that decision — the infill promotion and the adapter phases call it
     * rather than each dropping their own subset, because three triggers dropping overlapping sets
     * is how one of them ends up dropping something another still needs.
     *
     * Runs inside the caller's transaction. Only ever called for a CHANNEL-AFFECTING change: a note
     * or a timezone correction moves no curve and invalidates nothing.
     */
    private suspend fun invalidateForecastDerivedInTx(affectedFromMs: Long) {
        predictions.deleteFrom(affectedFromMs)
        infills.deleteFrom(affectedFromMs)
        // A band correction is dropped only when the window it was fitted over actually reaches the
        // change: `[fittedAtMs - windowDays, fittedAtMs]` meets `[affectedFromMs, ∞)` exactly when
        // `fittedAtMs >= affectedFromMs`. A blanket delete would take this morning's calibration off
        // every model because a week-old meal's grams were corrected — and the direction is not
        // benign: `conformal.rs` fits a signed offset that usually WIDENS the fan, so losing one
        // draws a visibly tighter band with nothing saying why.
        for (row in conformalDeltas.all()) {
            if (row.fittedAtMs >= affectedFromMs) conformalDeltas.deleteByModel(row.modelId)
        }
        // An adapter is FLAGGED, never auto-detached. Detaching would silently change the
        // forecaster under the patient as a side effect of an edit they did not connect to it;
        // the flag refuses the next attach and says why, which is a decision they get to make.
        loras.markHistoryMutated(nowMs())
    }

    /** Window reads for curve/channel reconstruction (feed [com.t1dm.data.curve.RoomDoseStore]). */
    suspend fun loggedDosesInRange(fromMs: Long, toMs: Long): List<LoggedDoseEntity> =
        withContext(io) { loggedDoses.inRange(fromMs, toMs) }

    /** One logged meal by rowid — what an edit reads before it rewrites. */
    suspend fun loggedMealById(id: Long): LoggedMealEntity? = withContext(io) { loggedMeals.byId(id) }

    /** One logged dose by rowid. */
    suspend fun loggedDoseById(id: Long): LoggedDoseEntity? = withContext(io) { loggedDoses.byId(id) }

    suspend fun loggedMealsInRange(fromMs: Long, toMs: Long): List<LoggedMealEntity> =
        withContext(io) { loggedMeals.inRange(fromMs, toMs) }

    /**
     * The newest [limit] logged meals / doses, newest first — the two halves of the Logs panel's feed.
     * Entities rather than a domain type on purpose: the committed-vs-delivered verdict is a join
     * against the outbox by dedupKey, and that key's format is owned by `:sync` (`mealDedupKey` /
     * `doseDedupKey`), which this module must not depend on and must not re-spell. `:app` sees both and
     * does the join there.
     */
    fun observeRecentLoggedMeals(limit: Int): Flow<List<LoggedMealEntity>> =
        loggedMeals.observeRecent(limit)

    fun observeRecentLoggedDoses(limit: Int): Flow<List<LoggedDoseEntity>> =
        loggedDoses.observeRecent(limit)

    /** The last [limit] distinct GI-bearing meals as [RecentMeal] quick-picks (Phase 7C, item 9). */
    fun observeRecentMeals(limit: Int = 3): Flow<List<RecentMeal>> =
        loggedMeals.observeRecentDistinct(limit).map { rows ->
            rows.mapNotNull { r -> r.gi?.let { gi -> RecentMeal(r.grams, gi) } }
        }

    /**
     * Replace a basal schedule's injections and (optionally) make it the active one. All rows for
     * [scheduleId] are dropped and re-inserted; activation is exactly-one atomically.
     */
    suspend fun saveBasalSchedule(
        scheduleId: String,
        rows: List<BasalScheduleEntity>,
        makeActive: Boolean,
    ) = withContext(io) {
        inWriteTx {
            basalSchedules.deleteSchedule(scheduleId)
            basalSchedules.insertAll(rows)
            if (makeActive) {
                basalSchedules.clearActive()
                basalSchedules.setActive(scheduleId)
            }
        }
    }

    suspend fun activeBasalDoses(): List<BasalScheduleEntity> =
        withContext(io) { basalSchedules.activeDoses() }

    /**
     * The log-gap rail's mark — `MAX(MIN(tsMs, loggedAtMs))`, not `MAX(tsMs)`.
     *
     * An edit can only ever move this mark BACKWARD, so a dose retimed forward cannot quiet
     * `Rails.mandatoryConfirmation`. That matters because the rail's question is "how long since the
     * phone was told about insulin", and dragging a dose's timestamp into the present answers it
     * with a claim rather than an observation. Legitimate backdating is unaffected: the min is the
     * claimed `tsMs` there, which is what a backdated dose means.
     */
    suspend fun latestLoggedInsulinTs(): Long? = withContext(io) { loggedDoses.latestLoggedMarkTs() }

    /**
     * When the last edited or deleted dose stops acting, or null when none is still in flight.
     *
     * The union of both stores is the whole point. `logged_dose` answers for an EDITED dose; a
     * DELETED one has no row left to read a duration off, so its action end is carried on the
     * tombstone. Omitting the second half would leave the rail unable to fire for exactly the case
     * it exists to cover — a deleted dose silently lowers assumed IOB, and nothing would stand
     * between that and a larger recommended bolus.
     */
    suspend fun editedDoseActiveUntilMs(): Long? = withContext(io) {
        val edited = loggedDoses.editedDoseActiveUntilMs()
        val deleted = tombstones.latestActingUntilMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    /** The newest dose-history mutation an acknowledgement has to cover — an edit stamp or a
     *  deletion stamp. Unioned for the same reason as [editedDoseActiveUntilMs]: without the
     *  deletion term a block raised by a delete could never be acknowledged. */
    suspend fun latestDoseMutationMs(): Long? = withContext(io) {
        val edited = loggedDoses.latestMutationMs()
        val deleted = tombstones.latestCreatedAtMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    /**
     * Newest event ts held locally — `MAX(ts)` over `logged_meal ∪ logged_dose ∪ event_tombstone` —
     * the event high-water mark the WS-connect catch-up pulls meal/dose history forward from
     * (§3.5), the event-side twin of [newestSampleTsAtOrBefore]. Null when none of the three holds
     * a row.
     *
     * The tombstone term is what keeps a deletion from widening the pull window. Without it,
     * deleting the newest event moves this mark backward, the next catch-up re-reads the range the
     * deleted event was in, and the event comes back. The hydration filter is the belt to that
     * brace.
     */
    suspend fun newestEventTs(): Long? = withContext(io) {
        listOfNotNull(loggedMeals.latestTs(), loggedDoses.latestTs(), tombstones.latestTs()).maxOrNull()
    }

    /** The most recent non-null mood across the wide sample. */
    fun observeLatestMood(): Flow<Int?> = samples.observeLatestMood()

    // ─── Graph paint layer (Room v8) ────────────────────────────────────────────────────

    /**
     * Every stroke intersecting `[fromMs, toMs]`, oldest-authored first (the order they must be
     * painted in). Intersection rather than containment, so a stroke wider than the window or half
     * scrolled off it still arrives — see [PaintStrokeDao.observeOverlapping] for the exact predicate.
     */
    fun observePaintStrokes(fromMs: Long, toMs: Long): Flow<List<PaintStroke>> =
        paintStrokes.observeOverlapping(fromMs, toMs)
            .map { list -> list.map { it.toModel() } }
            // `toModel` is `PaintStrokeBlob.decode` per row, and the collectors are `collectAsState`:
            // without this the whole selected window's geometry was deserialised on the Compose main
            // dispatcher every time the keep-forever table changed.
            .flowOn(io)

    /**
     * Persist one lifted stroke; returns the minted row id. A zero-point stroke is refused rather
     * than stored: it has no time bounds to index by, so it could never be selected back out, and a
     * gesture that produces none is a bug upstream rather than an empty drawing.
     */
    suspend fun addPaintStroke(stroke: PaintStroke): Long = withContext(io) {
        require(!stroke.isEmpty) { "a paint stroke must carry at least one point" }
        paintStrokes.insert(stroke.toEntity())
    }

    suspend fun deletePaintStroke(id: Long) = withContext(io) { paintStrokes.delete(id) }

    /** Clear the whole annotation layer (a user-initiated "erase all", distinct from the issue-5 reset). */
    suspend fun deleteAllPaintStrokes() = withContext(io) { paintStrokes.deleteAll() }

    // ─── Exercise bouts + their tracks (Room v16) ───────────────────────────────────────
    //
    // Phone-local, both tables. What syncs is the disposal curve [recordExerciseCurve] lays into the
    // wide sample, and nothing here.

    /**
     * Open a bout and return the PERSISTED row — minted `clientId`, DB rowid — so the service that
     * records it and the panel that draws it address one bout, the contract [logMeal] has.
     *
     * `startMs` is NOT grid-snapped, unlike [logMeal]/[logLoggedDose]: a bout boundary is a
     * wall-clock instant the user chose, and snapping it would move the moment they pressed the
     * button. Only the per-bucket sample write is on the grid, and [mergeSample] is what enforces
     * that.
     */
    suspend fun startExerciseSession(row: ExerciseSessionEntity): ExerciseSessionEntity =
        withContext(io) {
            val minted = row.copy(clientId = row.clientId.ifBlank { newClientId() })
            minted.copy(id = exerciseSessions.insert(minted))
        }

    /** Close a bout with what was actually recorded; see [ExerciseSessionDao.close] on why the
     *  bout's identity columns are not in the statement. */
    suspend fun endExerciseSession(
        id: Long,
        endMs: Long,
        activeSec: Int,
        distanceM: Double?,
        kcal: Int?,
        interrupted: Boolean,
        nowMs: Long,
    ) = withContext(io) {
        exerciseSessions.close(id, endMs, activeSec, distanceM, kcal, interrupted, nowMs)
    }

    /** Append accepted fixes to a bout's track. Batched by the caller — a run is not a write a
     *  second. */
    suspend fun appendExerciseFixes(rows: List<ExerciseFixEntity>) = withContext(io) {
        if (rows.isNotEmpty()) exerciseFixes.insertAll(rows)
    }

    fun observeExerciseSessions(): Flow<List<ExerciseSessionEntity>> = exerciseSessions.observeAll()

    suspend fun exerciseSession(id: Long): ExerciseSessionEntity? =
        withContext(io) { exerciseSessions.byId(id) }

    suspend fun exerciseTrack(sessionId: Long): List<ExerciseFixEntity> =
        withContext(io) { exerciseFixes.forSession(sessionId) }

    /** Bouts left open by a process that died mid-recording (plus the one running now). */
    suspend fun openExerciseSessions(): List<ExerciseSessionEntity> =
        withContext(io) { exerciseSessions.open() }

    /** The newest fix a bout recorded, or null when it recorded none — where an interrupted bout is
     *  closed. */
    suspend fun newestExerciseFixTs(sessionId: Long): Long? =
        withContext(io) { exerciseFixes.newestTs(sessionId) }

    /** Delete a bout and its track together. One transaction because there is no foreign key to do
     *  it for us: a half-applied delete leaves fixes keyed to a bout that no longer exists, and
     *  nothing would ever select them again to notice. */
    suspend fun deleteExerciseSession(
        id: Long,
        unwind: List<ExerciseCurveBucket>,
        nowMs: Long,
    ) = withContext(io) {
        inWriteTx {
            // Take the bout's disposal grams back out of every slot it wrote, in the same
            // transaction. Deleting the session alone leaves them in the wide sample forever, and
            // the model goes on seeing carbohydrate disposal from a bout the patient erased. Each
            // bucket carries `priorGrams` — what THIS bout put there — so the merge removes its own
            // share and leaves an overlapping bout's alone, exactly as [recordExerciseCurve]'s
            // within-bout rewrite does.
            for (b in unwind) {
                mergeSampleInTx(b.gridTs, b.tzOffsetMin, nowMs) {
                    it.copy(exercise = mergedExerciseGrams(it.exercise, b.priorGrams, 0.0))
                }
                enqueueIngest(b.gridTs, nowMs)
            }
            exerciseFixes.deleteForSession(id)
            exerciseSessions.delete(id)
        }
    }

    // ─── Glycemic dictionary / saved meals / insulin types (Room v5, Phase 4) ───────────

    suspend fun foodCount(): Int = withContext(io) { db.foodDao().count() }

    /** Bulk-seed the bundled dictionary (idempotent at the call site: seed only when empty). */
    suspend fun seedFoods(rows: List<FoodEntity>) = withContext(io) { db.foodDao().insertAll(rows) }

    /**
     * Full-text food search over the FTS5 index. [rawQuery] is sanitized into a prefix MATCH
     * (`term*`); a blank query falls back to an alphabetical browse. Malformed FTS syntax can throw,
     * so the MATCH is built from alnum tokens only.
     */
    suspend fun searchFoods(rawQuery: String, limit: Int = 30): List<FoodEntity> = withContext(io) {
        val tokens = rawQuery.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) db.foodDao().all(limit)
        else db.foodDao().search(tokens.joinToString(" ") { "$it*" }, limit)
    }

    suspend fun browseFoods(limit: Int = 50): List<FoodEntity> = withContext(io) { db.foodDao().all(limit) }

    suspend fun foodById(id: Long): FoodEntity? = withContext(io) { db.foodDao().byId(id) }

    suspend fun upsertFood(food: FoodEntity) = withContext(io) { db.foodDao().upsert(food) }

    /**
     * In-place edit of a USER food, under its existing [FoodEntity.id]. Returns false having written
     * nothing when the row no longer exists or is a bundled seed row.
     *
     * The guard is the whole point: unlike `FoodDao.deleteCustom`, `@Upsert` is NOT gated on `custom`,
     * and `Food.toCustomEntity` hard-sets `custom = true` / `source = "user"`. An unguarded write would
     * therefore either RESURRECT a deleted id (Room's upsert inserts with the explicit primary key when
     * nothing conflicts) or silently convert a shipped dictionary row into a user food. Read and write
     * share one write transaction so the check cannot be overtaken by a concurrent delete.
     *
     * `food_fts` needs no manual reindex: the `food_au` trigger delete-and-reinserts the external-content
     * row (see `FoodFts.DDL`), so a renamed food is searchable under its new name and not its old one.
     *
     * Saved and logged meals are deliberately NOT re-resolved. Their components snapshot carbs/GI/curve
     * at add time ([com.t1dm.core.model.MealComponent]) and `saved_meal_item` carries no foreign key
     * back here — that is what lets a stored meal survive this edit, and the food's deletion.
     */
    suspend fun updateCustomFood(food: FoodEntity): Boolean = withContext(io) {
        inWriteTx {
            val existing = db.foodDao().byId(food.id)
            if (existing == null || !existing.custom) return@inWriteTx false
            db.foodDao().upsert(food)
            true
        }
    }

    suspend fun deleteCustomFood(id: Long) = withContext(io) { db.foodDao().deleteCustom(id) }

    fun observeCustomFoods(): Flow<List<FoodEntity>> = db.foodDao().observeCustom()

    /** Persist a saved meal header + its portion snapshots atomically; returns the new meal id. */
    suspend fun saveMeal(name: String, items: List<SavedMealItemEntity>, nowMs: Long): Long =
        withContext(io) {
            inWriteTx {
                val mealId = db.savedMealDao().insertMeal(SavedMealEntity(name = name, updatedAt = nowMs))
                if (items.isNotEmpty()) db.savedMealDao().insertItems(items.map { it.copy(mealId = mealId) })
                mealId
            }
        }

    /**
     * Edit a saved meal in place: the header is rewritten and its portion snapshots are REPLACED
     * wholesale, atomically, under the meal's existing [id] (an edit is never a fork).
     *
     * Returns false having written nothing when the header no longer exists — the meal was deleted
     * while it was being edited. That check is the reason the header write comes first: `saved_meal_item`
     * carries no foreign key, so items inserted against a vanished header are invisible orphans no
     * query reaches and only a full wipe reaps. The delete-then-reinsert of the items likewise has no
     * meaning outside the transaction: a throw between them would leave a named, empty meal.
     */
    suspend fun updateSavedMeal(id: Long, name: String, items: List<SavedMealItemEntity>, nowMs: Long): Boolean =
        withContext(io) {
            inWriteTx {
                if (db.savedMealDao().updateMeal(id, name, nowMs) == 0) return@inWriteTx false
                db.savedMealDao().deleteItems(id)
                if (items.isNotEmpty()) db.savedMealDao().insertItems(items.map { it.copy(mealId = id) })
                true
            }
        }

    fun observeSavedMeals(): Flow<List<SavedMealEntity>> = db.savedMealDao().observeMeals()

    suspend fun savedMealItems(mealId: Long): List<SavedMealItemEntity> =
        withContext(io) { db.savedMealDao().itemsOf(mealId) }

    suspend fun deleteSavedMeal(id: Long) = withContext(io) {
        inWriteTx {
            db.savedMealDao().deleteItems(id)
            db.savedMealDao().deleteMeal(id)
        }
    }

    suspend fun insulinTypeBuiltinCount(): Int = withContext(io) { db.insulinTypeDao().builtinCount() }

    suspend fun seedInsulinTypes(types: List<InsulinTypeEntity>) =
        withContext(io) { db.insulinTypeDao().insertAll(types) }

    fun observeInsulinTypes(): Flow<List<InsulinTypeEntity>> = db.insulinTypeDao().observeAll()

    suspend fun upsertInsulinType(type: InsulinTypeEntity) =
        withContext(io) { db.insulinTypeDao().upsert(type) }

    suspend fun deleteCustomInsulinType(id: Long) =
        withContext(io) { db.insulinTypeDao().deleteCustom(id) }

    private suspend fun mergeSample(
        gridTs: Long,
        tzOffsetMin: Int,
        nowMs: Long,
        edit: (SampleEntity) -> SampleEntity,
    ) = withContext(io) {
        inWriteTx { mergeSampleInTx(gridTs, tzOffsetMin, nowMs, edit) }
    }

    private suspend fun mergeSampleInTx(
        gridTs: Long,
        tzOffsetMin: Int,
        nowMs: Long,
        edit: (SampleEntity) -> SampleEntity,
    ) {
        requireGrid(gridTs)
        val base = samples.byTs(gridTs) ?: emptySample(gridTs, tzOffsetMin, nowMs)
        samples.upsert(edit(base).copy(tzOffsetMin = tzOffsetMin, updatedAt = maxOf(base.updatedAt, nowMs)))
        enqueueIngest(gridTs, nowMs)
    }

    // ─── Raw sub-grid samples ───────────────────────────────────────────────────────────────

    /**
     * The samples received into one grid slot, oldest first — including the ones that lost the slot.
     *
     * The window comes from [rawSampleWindowFor], which is where it is spelled.
     *
     * A slot can legitimately answer with nothing: a five-minute sensor puts exactly one sample in a
     * slot, a gap-filled slot never had one, and the retention bound below eventually takes them all.
     */
    suspend fun rawSamplesForSlot(sourceId: CgmSourceId, gridTs: Long): List<CgmRawSample> =
        withContext(io) {
            requireGrid(gridTs)
            val window = rawSampleWindowFor(gridTs)
            rawSamples
                .rangeForSource(sourceId.value, window.first, window.last)
                .map { it.toModel() }
        }

    /** Every sample this source was heard to send in `[fromMs, toMs]`, oldest first — on the same clock
     *  the samples are filed under ([com.t1dm.core.model.CgmRawSample]), not on the grid. */
    suspend fun rawSamplesInRange(sourceId: CgmSourceId, fromMs: Long, toMs: Long): List<CgmRawSample> =
        withContext(io) { rawSamples.rangeForSource(sourceId.value, fromMs, toMs).map { it.toModel() } }

    /** How many sub-grid samples are held — a diagnostic read-out, so the retention bound is visible
     *  rather than merely asserted. */
    suspend fun rawSampleCount(): Int = withContext(io) { rawSamples.count() }

    /**
     * Drop every sub-grid sample older than [RAW_SAMPLE_RETENTION_MS]; returns how many went.
     *
     * Driven from the foreground service's five-minute housekeeping pass — the same tick the outbox
     * eviction rides — because that is where the pressure is created and it is the one loop that runs
     * exactly while samples are arriving. A sweep that finds nothing costs one index seek and writes
     * nothing, so it does not invalidate the table and is free in the steady state.
     *
     * An AGE bound with no size bound, unlike the outbox's: samples arrive at the sensor's cadence
     * and cannot burst, so bounding the age bounds the row count too. Nothing derives from these rows
     * — no forecast, no statistic, no alarm, and they never cross the wire — so nothing observes the
     * horizon except a display asking for a window older than the phone still holds.
     */
    suspend fun pruneRawSamples(nowMs: Long): Int =
        withContext(io) { rawSamples.pruneBefore(rawSampleCutoff(nowMs)) }

    // ─── Raw adverts (forensics/replay) ─────────────────────────────────────────────────────

    suspend fun recordRawAdvert(advert: CgmAdvertRawEntity): Long =
        withContext(io) { advertsRaw.insert(advert) }

    /** NOTE: nothing drives this. `cgm_advert_raw` therefore grows without bound; the retention is a
     *  shape, not a policy. [pruneRawSamples] is the one that is actually swept. */
    suspend fun pruneRawAdvertsBefore(beforeMs: Long): Int =
        withContext(io) { advertsRaw.pruneBefore(beforeMs) }

    // ─── Outbox ─────────────────────────────────────────────────────────────────────────────

    /** Thin enqueue-on-write (Phase 1); dedup is enforced by the unique `dedupKey` index. */
    override suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long = withContext(io) { enqueueRow(kind, dedupKey, payload, nowMs, notBeforeMs) }

    /**
     * Enqueue under [dedupKey], displacing a still-PENDING row already filed there. Returns the new
     * row id, or -1 when the key is held by a row the drainer has already claimed.
     *
     * **The drain race, and why only PENDING is swept.** `QueueDrainer` takes a snapshot batch of
     * PENDING rows and then spends one HTTP round trip apiece, so a row can sit in that snapshot for
     * minutes; INFLIGHT is the mutual-exclusion token that separates "still ours" from "on the wire",
     * and `OutboxDao.claim` is where a row crosses. Deleting only PENDING rows therefore lands cleanly
     * on either side of it:
     *
     *  - We win the race: the row is gone before the claim, `claim` returns 0, and the drainer skips
     *    it — exactly the path an undo already relies on. Our replacement, a distinct row, is sent.
     *  - The drainer wins: the row is INFLIGHT, we delete nothing, the insert loses to the unique
     *    index and returns -1. The body already on the wire is delivered and the fresher one is not
     *    queued; the next cycle re-enqueues under a new key seconds later, and the server's
     *    `updated_at` ordering means neither delivery can un-do a newer one.
     *
     * The delete and the insert share one transaction so a claim cannot land between them, and
     * `outbox.id` is `AUTOINCREMENT` — rowids are never reused — so the drainer's snapshot of the
     * displaced row can never resolve onto the replacement that took its place.
     */
    override suspend fun enqueueReplacingPending(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long = withContext(io) {
        inWriteTx {
            outbox.deleteByDedupKeyInState(dedupKey, OutboxState.PENDING)
            enqueueRow(kind, dedupKey, payload, nowMs, notBeforeMs)
        }
    }

    override suspend fun enqueueSuperseding(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long = withContext(io) {
        inWriteTx {
            outbox.deleteByDedupKey(dedupKey)
            enqueueRow(kind, dedupKey, payload, nowMs, notBeforeMs)
        }
    }

    /**
     * Take the bridged mirror of one event out of the queue.
     *
     * A deletion used to travel as a withdrawal, which removed the bridged row with it. It travels
     * as a tombstone now, and the third party has no tombstone: an undelivered mirror left in the
     * queue is a meal or a dose that the patient deleted here and that arrives THERE afterwards,
     * with no route to take it back out again.
     */
    private suspend fun withdrawBridgedTreatment(clientId: String) =
        outbox.deleteByDedupKey("$NS_TREATMENT_DEDUP_PREFIX$clientId")

    /**
     * Take an EDITED event's bridged mirror out of the queue, and answer whether it was still there
     * to take.
     *
     * The answer is what the caller needs, not the withdrawal. A bridged treatment carries the amount
     * frozen at the instant it was queued, and `/api/v1` has no update for one that has landed — so an
     * edit can only be honoured on the host by withdrawing the mirror BEFORE it goes and sending the
     * corrected one instead. Re-sending after it has gone would not replace anything: the bridged
     * `created_at` derives from `updatedAt`, which an edit bumps, so the host would file a SECOND
     * treatment beside the first and the logbook would double-count the insulin.
     *
     * State-scoped, unlike [withdrawBridgedTreatment], and for the same reason
     * [enqueueReplacingPending] is: a row already claimed INFLIGHT is being POSTed right now, and
     * deleting it would not unsend it — it would only make the caller believe nothing had gone.
     */
    suspend fun withdrawEditedBridgedTreatment(clientId: String): Boolean = withContext(io) {
        outbox.deleteByDedupKeyInState("$NS_TREATMENT_DEDUP_PREFIX$clientId", OutboxState.PENDING) > 0
    }

    /** Deduplicated: the queue table is written far more often than its DEPTH moves — a row going
     *  INFLIGHT, being rescheduled under backoff or having its attempt count bumped all invalidate the
     *  table without changing the count. Each such emission previously cost `SyncManager` an extra
     *  `oldestOutboxCreatedAt()` query and a fresh `SyncStatus`, which two dashboard collectors combine
     *  — so a row churning cost a DB read and two recompositions to report an unchanged number. */
    fun observeOutboxDepth(): Flow<Int> = outbox.observeDepth().distinctUntilChanged()

    /** Oldest enqueue timestamp across the queue (null = empty); Network panel age-vs-bound read. */
    suspend fun oldestOutboxCreatedAt(): Long? = withContext(io) { outbox.oldestCreatedAt() }

    /** [oldestOutboxCreatedAt] counting only rows bound for T1DMSERVER — the re-mirror walk's
     *  delivery proof, which a stuck bridge row must not be able to falsify. */
    suspend fun oldestServerBoundOutboxCreatedAt(): Long? =
        withContext(io) { outbox.oldestCreatedAtExcluding(OutboxKind.NIGHTSCOUT) }

    /**
     * Queue the ingest push for one grid slot, DISPLACING whatever is queued for it.
     *
     * A plain enqueue is `INSERT OR IGNORE` on the unique dedup key, so a slot with a row already
     * INFLIGHT — claimed by the drainer, waiting on an HTTP round trip that takes as long as the
     * link does — silently drops the new write. A demotion or a BG deletion made in that window
     * would be discarded, `mergeServerSample` would gap-fill the server's retained value back in,
     * and the reconstruct-at-drain path would re-upload it. Meals and doses have used the
     * superseding form since they gained an edit; this is the same door on the ingest side.
     *
     * Superseding is safe against the in-flight request: the drainer deletes by ROW ID on success,
     * so removing the row leaves that delete a no-op and the fresh row drains afterwards, from the
     * sample as it stands at drain time.
     */
    private suspend fun enqueueIngest(gridTs: Long, nowMs: Long): Long {
        val key = "ingest:sample:$gridTs"
        outbox.deleteByDedupKey(key)
        return enqueueRow(OutboxKind.INGEST, key, ByteArray(0), nowMs)
    }

    private suspend fun enqueueRow(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long = outbox.enqueue(
        OutboxEntity(
            kind = kind,
            dedupKey = dedupKey,
            payload = payload,
            createdAtMs = nowMs,
            attempts = 0,
            // Not a backoff: `attempts` stays 0, so the row's first real failure still gets the first
            // (shortest) delay. `createdAtMs` is untouched too, which is what keeps FIFO order and the
            // age bound measured from the WRITE rather than from the end of the hold.
            nextAttemptMs = notBeforeMs,
            state = OutboxState.PENDING,
        ),
    )

    // ─── Predictions (dedicated table; replaces the Phase-2 kv blob) ──────────────────────────

    /** Persist every running model's forecast for one cycle; `(madeAtMs, modelId)` REPLACEs. */
    suspend fun upsertPredictions(preds: List<ModelPrediction>, nowMs: Long) = withContext(io) {
        predictions.upsertAll(preds.map { it.toEntity(nowMs) })
    }

    /** The whole most-recent cycle, selected model first — the overlay-rehydrate read. */
    suspend fun latestCyclePredictions(): List<ModelPrediction> = withContext(io) {
        predictions.latestCycle().map { it.toModel() }
    }

    suspend fun predictionsInRange(fromMs: Long, toMs: Long): List<ModelPrediction> =
        withContext(io) { predictions.range(fromMs, toMs).map { it.toModel() } }

    /** One model's stored forecasts over a window, ascending — what the BG panel sweeps in hindsight.
     *  Read-only and display-only: these are the rows as they were written, never a re-forecast. */
    suspend fun predictionsForModelInRange(modelId: String, fromMs: Long, toMs: Long): List<ModelPrediction> =
        withContext(io) { predictions.rangeForModel(modelId, fromMs, toMs).map { it.toModel() } }

    /** Purge every stored forecast of a removed model (Phase 7C model deletion). */
    suspend fun deletePredictionsForModel(modelId: String) = withContext(io) { predictions.deleteByModel(modelId) }

    fun observeLatestPrediction(): Flow<ModelPrediction?> =
        predictions.observeLatest().map { it?.toModel() }

    /**
     * Walk every MATURED forecast of [modelId] over `[sinceMs, nowMs]` into the whole-window record
     * the on-device metric suite scores (Phase 7C, Models drill-down): the full quantile fan and
     * median line as stored, the realized trajectory beside them, and the persistence anchor.
     *
     * A window runs from `madeAt + 1 step` to `madeAt + [horizonMaxMin]`, so every reported horizon
     * must land inside it. Unlike the per-horizon pairing it replaces, the suite scores one
     * rectangular `windows × steps` grid and CG-EGA reads every step of it, so a window is emitted
     * only when the realized trajectory covers ALL of them — one CGM gap drops the whole forecast,
     * counted in [ForecastWindowSet.nIncomplete] rather than quietly shortening the window.
     *
     * The truth is the same filter the pairing used: MEASURED, NORMAL-flagged readings, matched to
     * each target time by the nearest within [toleranceMs]. The persistence anchor is that same
     * series read at `madeAt` — `SPEC/invariants.md` §6.3's "measured BG at the forecast's
     * `made_at`" — so the rate at `t = 0` differences two values off one basis.
     *
     * The fan width is whatever the stored rows carry: the first accepted row fixes it and rows
     * disagreeing are dropped, which keeps the set rectangular without this layer restating how
     * many levels §6 defines. A width the scorer does not recognise is its own to reject.
     */
    suspend fun forecastWindows(
        modelId: String,
        horizonMaxMin: Int,
        sinceMs: Long,
        nowMs: Long,
        toleranceMs: Long = 150_000L, // half a 5-min grid step
    ): ForecastWindowSet = withContext(io) {
        val stepMs = CurveEngine.STEP_MS
        val horizonMs = horizonMaxMin.toLong() * 60_000L
        if (horizonMaxMin <= 0 || horizonMs % stepMs != 0L) return@withContext ForecastWindowSet.EMPTY
        val nSteps = (horizonMs / stepMs).toInt()

        // Realized truth, sorted ascending for a binary-search nearest match. Reaches back one
        // tolerance before the range so the earliest forecast's anchor is matchable.
        //
        // Scoped to the AUTHORITATIVE source, and that scoping is the whole correctness of the figure:
        // `cgm_reading` holds a row per (source, slot), so an unscoped read returns one row per sensor
        // for the same instant and `distinctBy` keeps whichever the query happened to order first. The
        // forecast was conditioned on one sensor's history, so scoring it against a slot that another
        // sensor supplied measures the disagreement between two sensors and calls it model error — and
        // the §8.4 fit, which is built from these same windows, would push that disagreement into the
        // band the patient is shown. With no authoritative source there is nothing to score against.
        val authoritative = sources.authoritativeSourceId() ?: return@withContext ForecastWindowSet.EMPTY
        val truth = readings.rangeForSource(authoritative, sinceMs - toleranceMs, nowMs)
            .asSequence()
            .filter { it.bgMgdl != null && isRealMeasurement(it.provenance, it.flag) }
            .map { it.tsMs to it.bgMgdl!! }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()
        if (truth.isEmpty()) return@withContext ForecastWindowSet.EMPTY
        val truthTs = LongArray(truth.size) { truth[it].first }

        val rows = predictions.range(sinceMs, nowMs - horizonMs).map { it.toModel() }
            .filter { it.modelId == modelId && it.status == ForecastStatus.OK }

        var nMatured = 0
        var nIncomplete = 0
        var nq = 0
        val out = ArrayList<ForecastWindow>()
        for (p in rows) {
            if (p.stepMs != stepMs || p.nQuantiles <= 0) continue
            if (p.medianBg.size < nSteps || p.bandsMgdl.size < nSteps * p.nQuantiles) continue
            if (p.cycleTsMs + horizonMs > nowMs) continue // window not fully matured
            nMatured++
            if (nq == 0) nq = p.nQuantiles else if (p.nQuantiles != nq) { nIncomplete++; continue }

            val anchor = nearestWithin(truthTs, truth, p.cycleTsMs, toleranceMs)
            if (anchor == null) { nIncomplete++; continue }
            val realized = ArrayList<Double>(nSteps)
            for (i in 1..nSteps) {
                val v = nearestWithin(truthTs, truth, p.cycleTsMs + i * stepMs, toleranceMs) ?: break
                realized += v.toDouble()
            }
            if (realized.size != nSteps) { nIncomplete++; continue }

            out += ForecastWindow(
                bandsMgdl = p.bandsMgdl.subList(0, nSteps * nq).toList(),
                medianBg = p.medianBg.subList(0, nSteps).toList(),
                realizedBg = realized,
                lastBg = anchor.toDouble(),
            )
        }
        ForecastWindowSet(out, nMatured, nIncomplete)
    }

    /** Nearest realized BG to [targetTs] within [toleranceMs], or null. Binary search on [truthTs]. */
    private fun nearestWithin(
        truthTs: LongArray,
        truth: List<Pair<Long, Int>>,
        targetTs: Long,
        toleranceMs: Long,
    ): Int? {
        if (truthTs.isEmpty()) return null
        var idx = truthTs.binarySearch(targetTs)
        if (idx < 0) idx = -(idx + 1)
        var best: Int? = null
        var bestDelta = Long.MAX_VALUE
        for (j in (idx - 1)..(idx + 1)) {
            if (j < 0 || j >= truthTs.size) continue
            val d = kotlin.math.abs(truthTs[j] - targetTs)
            if (d <= toleranceMs && d < bestDelta) { bestDelta = d; best = truth[j].second }
        }
        return best
    }

    // ─── Server profiles (N-profile, one active; token lives in the TokenStore) ───────────────

    fun observeProfiles(): Flow<List<ServerProfileEntity>> = profiles.observeAll()

    fun observeActiveProfile(): Flow<ServerProfileEntity?> = profiles.observeActive()

    suspend fun activeProfile(): ServerProfileEntity? = withContext(io) { profiles.active() }

    suspend fun profileById(id: String): ServerProfileEntity? = withContext(io) { profiles.byId(id) }

    /** Upsert a profile; when [makeActive], enforce exactly-one-active atomically. */
    suspend fun upsertProfile(profile: ServerProfileEntity, makeActive: Boolean) = withContext(io) {
        inWriteTx {
            profiles.upsert(profile)
            if (makeActive) {
                profiles.clearActive()
                profiles.setActive(profile.id)
            }
        }
    }

    suspend fun setActiveProfile(id: String) = withContext(io) {
        inWriteTx {
            profiles.clearActive()
            profiles.setActive(id)
        }
    }

    suspend fun deleteProfile(id: String) = withContext(io) { profiles.delete(id) }

    // ─── Catch-up merge (WS reconnect / REST series) ──────────────────────────────────────────

    /**
     * Fold a server-originated wide row into local state under **no-server-over-local** presence
     * gap-fill (§3.3). Returns `true` iff a write occurred. Runs in a transaction so the
     * read-modify-write cannot race a concurrent local projection.
     *
     * The phone is the sole read-write author, so a catch-up/WS row is only the phone's own earlier
     * push reflected back; [SampleGapFill] fills ONLY fields the local row lacks and never overwrites
     * a present local value, comparing no `updated_at` (no cross-clock hazard, decisions #2/#3).
     */
    suspend fun mergeServerSample(patch: SamplePatch): Boolean = withContext(io) {
        requireGrid(patch.ts)
        inWriteTx {
            // Hydrate the authoritative source's cgm_reading rows so server-synced history
            // reaches BOTH the graph (observeReadings) and the model (recentBgSeries) — sample is a
            // dead-end for display/inference (SPEC §3.5). GAP-FILL ONLY: never overwrite a local
            // reading (its provenance/flag is authoritative), and enqueue NO ingest (this data
            // originated from the phone; re-pushing would echo-loop). The §3.6 alarm/loss-of-signal
            // path is readingBus-driven (live BLE), never the DB, so a DB write cannot perturb it.
            // The "already have it" test spans the authoritative source's whole MODEL CLASS, matching
            // [reconcileReadingsFromSamples]. Asked per source it disagreed with that sibling pass, and
            // the disagreement was the bug: `sample` was authored by whichever sensor held authority at
            // the time, so after a sensor change every historical slot looked missing to the NEW source
            // and a full resync re-imported the entire record under its id — a second copy of the whole
            // history, from an ordinary Settings → Save.
            val authoritative = sources.authoritativeSourceId()
            val klass = authoritative?.let { sources.byId(it)?.sensorModelId }
            val siblings = when {
                authoritative == null -> emptyList()
                klass == null -> listOf(authoritative)
                else -> sources.idsForSensorModel(klass)
            }
            if (authoritative != null && patch.bgMgdl != null &&
                !readings.existsForSources(siblings, patch.ts)
            ) {
                readings.upsert(
                    CgmReadingEntity(
                        sourceId = authoritative,
                        tsMs = patch.ts,
                        bgMgdl = patch.bgMgdl,
                        trendTenthsPerMin = null,
                        minFromStart = null,
                        quality = null,
                        provenance = patch.bgProvenance ?: ReadingProvenance.MEASURED,
                        flag = patch.bgFlag ?: ReadingFlag.NORMAL,
                        tzOffsetMin = patch.tzOffsetMin,
                        rxWallMs = patch.updatedAt,
                        rssi = null,
                    ),
                )
            }
            // Carbs/bolus/basal are NO LONGER projected here: they are self-describing curve events
            // (logged_meal / logged_dose), hydrated by client_id via [hydrateMealEvent] /
            // [hydrateDoseEvent] on REST catch-up (§3.4), never smeared out of a scalar sample bucket.
            val merged = SampleGapFill.fill(samples.byTs(patch.ts), patch) ?: return@inWriteTx false
            samples.upsert(merged)
            true
        }
    }

    /**
     * One-shot reconcile that gap-fills the authoritative source's `cgm_reading` from the wide `sample`
     * projection — the migration path for server history synced into `sample` BEFORE the reading
     * hydration existed (and a belt-and-braces self-heal thereafter). Inserts only slots the source
     * lacks a reading for (never clobbers a live reading's provenance/flag); the §3.6 alarm path is
     * live-BLE-driven, not the DB, so this is inert to it. Returns the number of rows inserted; 0 when
     * there is no authoritative source or nothing is missing. Off-main; one transaction.
     *
     * The gap set is resolved by the QUERY ([SampleDao.bgSlotsMissingReading]) rather than by pulling
     * both tables into the heap and diffing them there. It ran on every WS (re)connect — a flapping
     * link re-ran it per reconnect — and neither table is pruned, so the old form boxed every reading
     * ts of the source into a HashSet and materialised the entire projection beside it, inside a WRITE
     * transaction that blocks every other writer for its duration. The rows selected are identical:
     * `bgMgdl IS NOT NULL` is the same predicate, `NOT EXISTS` over the `(sourceId, tsMs)` key is the
     * same membership test, and `ORDER BY ts` is the same order. In the steady state — where
     * [mergeServerSample] has already hydrated each catch-up row into `cgm_reading` itself — the
     * query now returns nothing and the pass reads nothing back.
     */
    suspend fun reconcileReadingsFromSamples(): Int = withContext(io) {
        val authoritative = sources.authoritativeSourceId() ?: return@withContext 0
        // The gap set spans the authoritative source's whole MODEL CLASS. Asking it per source made every
        // sensor replacement inherit a full duplicate of the record — see [SampleDao.bgSlotsMissingReading].
        val klass = sources.byId(authoritative)?.sensorModelId
        val siblings = if (klass == null) listOf(authoritative) else sources.idsForSensorModel(klass)
        inWriteTx {
            val fill = samples.bgSlotsMissingReading(siblings).asSequence()
                .map { s ->
                    CgmReadingEntity(
                        sourceId = authoritative,
                        tsMs = s.ts,
                        bgMgdl = s.bgMgdl,
                        trendTenthsPerMin = null,
                        minFromStart = null,
                        quality = null,
                        provenance = s.bgProvenance ?: ReadingProvenance.MEASURED,
                        flag = s.bgFlag ?: ReadingFlag.NORMAL,
                        tzOffsetMin = s.tzOffsetMin,
                        rxWallMs = s.updatedAt,
                        rssi = null,
                    )
                }
                .toList()
            if (fill.isNotEmpty()) readings.upsertAll(fill)
            fill.size
        }
    }

    /**
     * Id-keyed hydration of a phone-authored meal/dose event received on REST catch-up (§3.4),
     * replacing the retired sample→event reconcile. A redelivery conflicts on the unique `clientId`
     * index and is IGNOREd (idempotent, no duplicate); this NEVER re-projects into `sample` and NEVER
     * enqueues (the event originated on this phone — re-pushing would echo-loop). Off-main.
     */
    suspend fun hydrateMealEvent(ev: LoggedMealEntity): Long = withContext(io) {
        if (tombstoned(ev.clientId, ev.updatedAt)) -1L else loggedMeals.insertIgnore(ev)
    }

    suspend fun hydrateDoseEvent(ev: LoggedDoseEntity): Long = withContext(io) {
        if (tombstoned(ev.clientId, ev.updatedAt)) -1L else loggedDoses.insertIgnore(ev)
    }

    /**
     * One bounded page of the §3.8 full re-mirror to a freshly-wiped server (the H7 upload direction).
     * Re-enqueues an `INGEST` dirty-marker for every local `sample` with `ts > [afterTs]`, up to [limit]
     * rows in ascending `ts` order, and returns the newest ts enqueued — the exclusive cursor for the
     * next page — or null when no sample remains past [afterTs] (the scalar walk is complete).
     *
     * INGEST is reconstruct-at-drain (`QueueDrainer` resolves the current `sample` by ts), so this
     * re-uploads scalars WITHOUT re-encoding them here, keeping `:data` free of any `:sync` type. It is
     * idempotent by construction: a still-pending INGEST row for a slot conflicts on its dedupKey
     * (`ingest:sample:<ts>`) and is ignored; an already-drained slot re-enqueues and re-uploads.
     *
     * The caller drains each page and confirms the queue is empty of it before persisting the returned
     * cursor, so the walk resumes across connects rather than restarting — a history of tens of
     * thousands of rows does not clear in one pass. It also re-mirrors the curve EVENTS and the stats
     * blocks at the `:app`/`:sync` seam (those PUTs carry `:sync` DTO envelopes this module cannot
     * build), and records `sync.mirrored_epoch` only once the whole walk has been seen DELIVERED — never
     * merely enqueued.
     */
    suspend fun reMirrorScalarsBatch(afterTs: Long, limit: Int, nowMs: Long): Long? = withContext(io) {
        val page = samples.page(afterTs, limit)
        if (page.isEmpty()) return@withContext null
        inWriteTx { for (s in page) enqueueIngest(s.ts, nowMs) }
        page.last().ts
    }

    // ─── kv / hw_telemetry ──────────────────────────────────────────────────────────────────

    suspend fun putKv(key: String, value: String, nowMs: Long) =
        withContext(io) { kv.put(KvEntity(key, value, nowMs)) }

    suspend fun getKv(key: String): String? = withContext(io) { kv.get(key) }

    /**
     * One kv key's value. `distinctUntilChanged` because Room invalidates per TABLE: a write to ANY
     * key re-runs EVERY registered kv query, and the FGS liveness heartbeat (`last_alive_ts`, 1/min)
     * plus the per-cycle inference telemetry blob write kv continuously with the UI closed. Every
     * settings-backed Flow in the process therefore re-emitted an identical value about twice a
     * minute — and through the glance combine that reached a widget push, which rebuilds a 48 h
     * insulin curve, rescans the day's steps and crosses the RemoteViews boundary. Same hazard, and
     * the same remedy, as every other table-scoped observer here.
     *
     * Deduplicating on the RAW string is what keeps this safe for a liveness reader as well as a
     * value reader. An emission here never identified WHICH key was written, so it could not carry
     * an event about this one; and anything that genuinely means "still alive" is a stamp that
     * differs on every write, so it is not suppressed. The first emission is always delivered, and
     * each collector holds its own comparison state, so a late subscriber still gets the value.
     */
    fun observeKv(key: String): Flow<String?> = kv.observe(key).distinctUntilChanged()

    /** Every kv key→value pair (Phase 7C item 17 — config export). Off-main. */
    suspend fun allKv(): Map<String, String> =
        withContext(io) { kv.all().associate { it.key to it.value } }

    /** Bulk-write kv pairs in one upsert (config import). Off-main. */
    suspend fun putKvBatch(pairs: Map<String, String>, nowMs: Long) =
        withContext(io) { kv.putAll(pairs.map { (k, v) -> KvEntity(k, v, nowMs) }) }

    // ── Band recalibration (`SPEC/inference.md` §8.4) ────────────────────────────────────────────

    /**
     * Persist a fitted band correction for one model, replacing whatever was there.
     *
     * The caller writes ONLY a sufficient fit. A refusal must not reach here: it has established
     * that too little history matured to fit on, which says nothing about whether the correction
     * already stored is wrong, and overwriting it would drop the user back to the raw fan on the
     * strength of a walk that scored nothing.
     */
    suspend fun putBandCalibration(cal: BandCalibration) = withContext(io) {
        conformalDeltas.upsert(
            ConformalDeltaEntity(
                modelId = cal.modelId,
                steps = cal.steps,
                nQuantiles = cal.nQuantiles,
                deltaBlob = cal.delta.toBlob(),
                nCal = cal.nCal,
                nEval = cal.nEval,
                maxAbsDeltaMgdl = cal.maxAbsDeltaMgdl,
                cov90Raw = cal.cov90Raw,
                cov90Cal = cal.cov90Cal,
                meanWidth90Raw = cal.meanWidth90Raw,
                meanWidth90Cal = cal.meanWidth90Cal,
                windowDays = cal.windowDays,
                fittedAtMs = cal.fittedAtMs,
            ),
        )
    }

    /** One model's stored correction, or null when it has never been fitted. Off-main. */
    suspend fun bandCalibration(modelId: String): BandCalibration? =
        withContext(io) { conformalDeltas.get(modelId)?.toModel() }

    /**
     * Every stored correction, keyed by model — the map the BG panel's overlay reads. Observed
     * rather than fetched so a fit lands on the graph without the panel being reopened; a row whose
     * blob length disagrees with its own `steps · nQuantiles` is dropped rather than reshaped, and
     * that model simply draws the raw fan.
     */
    fun observeBandCalibrations(): Flow<Map<String, BandCalibration>> =
        conformalDeltas.observeAll()
            .map { rows -> rows.mapNotNull { it.toModel() }.associateBy { it.modelId } }
            .distinctUntilChanged()
            .flowOn(io)

    /** Drop a removed model's correction alongside its forecasts. Off-main. */
    suspend fun deleteBandCalibration(modelId: String) =
        withContext(io) { conformalDeltas.deleteByModel(modelId) }

    /**
     * Drop everything fitted against a model's own forecasts — its band correction and the stored
     * predictions the realised-accuracy suite reads.
     *
     * Called when what the model IS changes under a fixed id: a replaced artifact, or an adapter
     * attached or detached. Both describe a forecaster that no longer exists, and keeping either
     * would report one model's calibration on another's fan.
     */
    suspend fun clearForecastDerived(modelId: String) = withContext(io) {
        conformalDeltas.deleteByModel(modelId)
        predictions.deleteByModel(modelId)
    }

    private fun ConformalDeltaEntity.toModel(): BandCalibration? {
        val delta = deltaBlob.toDoubleList()
        if (steps <= 0 || nQuantiles <= 0 || delta.size != steps * nQuantiles) return null
        return BandCalibration(
            modelId = modelId,
            delta = delta,
            steps = steps,
            nQuantiles = nQuantiles,
            nCal = nCal,
            nEval = nEval,
            maxAbsDeltaMgdl = maxAbsDeltaMgdl,
            cov90Raw = cov90Raw,
            cov90Cal = cov90Cal,
            meanWidth90Raw = meanWidth90Raw,
            meanWidth90Cal = meanWidth90Cal,
            windowDays = windowDays,
            fittedAtMs = fittedAtMs,
        )
    }

    suspend fun recordTelemetry(row: HwTelemetryEntity): Long =
        withContext(io) { telemetry.insert(row) }

    // ─── Full-record archive (backup / restore) ─────────────────────────────────────────────────

    /**
     * Stream the whole local record — readings, the wide projection, logged events, the
     * user-authored catalogues, the drawings, and [configJson] — to [out] as a gzipped
     * `t1dm.archive`. [out] is NOT closed here; the caller owns the SAF stream and closes it.
     *
     * Routed through this gateway rather than letting callers reach for [AppDatabase] directly, so
     * the archive inherits the same dispatcher discipline as every other read here: it walks the
     * entire keep-forever store and must never touch the main thread.
     */
    suspend fun writeArchive(
        out: OutputStream,
        configJson: String?,
        appVersion: String,
        nowMs: Long,
    ): ArchiveCounts = withContext(io) { ArchiveWriter(db).write(out, configJson, appVersion, nowMs) }

    /**
     * Merge an archive back in. The local row always wins, so this can only ever add what is
     * missing, and re-importing the same file is a no-op. Throws
     * [com.t1dm.data.backup.NotAnArchiveException] when the stream is some other kind of file —
     * which the caller uses to fall back to the older settings-and-drawings reader rather than
     * reporting a failure on a file that never claimed to be an archive.
     */
    suspend fun readArchive(input: InputStream): ArchiveResult =
        withContext(io) { ArchiveReader(db).read(input) }

    // ─── Full app reset (issue 5 — DESTRUCTIVE) ───────────────────────────────────────────────

    /**
     * Erase every user + runtime table in ONE transaction, returning the store to its first-run
     * contents at the **CURRENT** schema version — this is a row-only wipe (never a drop/recreate,
     * which would risk landing on an older schema; Phase-1 keep-forever store). What survives is
     * exactly what a fresh install ships: the seed `food` dictionary and the builtin `insulin_type`
     * presets (only the user-added `custom`/non-builtin rows go). Wiping the whole `kv` table both
     * resets every setting to its coded default (readers fall back when a key is absent) AND clears
     * the watch pairing bits, epoch, and the burned nonce ceilings, so a later re-pair with fresh
     * X25519 keys can never reuse a (key, nonce) pair. The model `.pte`/`.tflite` artifacts live on
     * the filesystem, not in Room, so they are untouched. Secrets that live outside Room (the
     * Keystore-wrapped server token) are burned by the caller. The graph's freehand annotation layer
     * (`bg_paint_stroke`) is user data like any other and goes whole — it ships with no seed rows, so
     * "first-run contents" for it means empty. So do the exercise bouts and, with them, the GPS
     * tracks: this is the one path that erases the stored location, and a reset that left it behind
     * would not be one. Off-main.
     *
     * [preserveCgmSources] keeps the `cgm_source` rows (the discovered set + the exactly-one-active
     * flag): the connected-GATT session that holds the live sensor is process-scoped, so a reset that
     * leaves the process (and that session) alive must not drop the active-source binding — the sensor
     * stays connected and its new readings repopulate the just-wiped `cgm_reading` table.
     *
     * **It keeps `cgm_sensor_secret` too, and that is a deliberate limit on "erase everything".** Some
     * sensor families hold per-sensor state that the sensor itself will not hand back once established,
     * so for a sensor still on the patient's arm these bytes can be the only copy in existence. Discarding
     * them is not erasing data, it is retiring hardware. Clearing one is a per-sensor action
     * ([deleteSensorSecret]), taken for one named sensor by the plugin that owns it and only where that
     * sensor no longer needs it — never for all of them by a global reset.
     */
    // ── Adapters (LoRA) ─────────────────────────────────────────────────────────────

    /** Every stored adapter, model-major — what the adapter panel lists. */
    fun observeLoras(): Flow<List<LoraEntity>> = loras.observeAll()

    suspend fun lorasFor(modelId: String): List<LoraEntity> = withContext(io) { loras.byModel(modelId) }

    suspend fun loraById(id: Long): LoraEntity? = withContext(io) { loras.byId(id) }

    /** The adapter the forecast path must run for [modelId], or null when none is attached. */
    suspend fun attachedLora(modelId: String): LoraEntity? = withContext(io) { loras.attachedFor(modelId) }

    suspend fun saveLora(row: LoraEntity): Long = withContext(io) { loras.upsert(row) }

    suspend fun renameLora(id: Long, name: String, nowMs: Long) =
        withContext(io) { loras.rename(id, name, nowMs) }

    /**
     * Attach [id] and detach every other adapter of the same model, in one transaction. At most
     * one adapter per model may be attached: two would be a fan nobody could name, and the
     * forecast path reads exactly one row.
     */
    /** Record a deliberate override of the counterfactual guard on ONE adapter row. */
    suspend fun setLoraGuardOverride(id: Long, nowMs: Long) =
        withContext(io) { loras.setGuardOverride(id, nowMs) }

    /** Store what the counterfactual guard measured about one adapter. */
    suspend fun setLoraGuard(
        id: Long,
        verdict: String,
        windows: Int,
        frozenMgdl: Double,
        adaptedMgdl: Double,
        retention: Double,
        signAgreement: Double,
        why: String,
        nowMs: Long,
    ) = withContext(io) {
        loras.setGuard(
            id, verdict, windows, frozenMgdl, adaptedMgdl, retention, signAgreement, why, nowMs,
        )
    }

    /**
     * Flag every adapter fitted before [nowMs] as standing on a history that has since changed.
     *
     * Called from the same invalidation a channel-affecting log edit runs: an adapter fitted on
     * windows the patient has since rewritten describes a record that no longer exists, and attach
     * refuses until it is re-fitted.
     */
    suspend fun markLoraHistoryMutated(nowMs: Long) =
        withContext(io) { loras.markHistoryMutated(nowMs) }

    suspend fun attachLora(id: Long, modelId: String, nowMs: Long) = inWriteTx {
        loras.detachAll(modelId, nowMs)
        loras.attach(id, nowMs)
    }

    suspend fun detachLoras(modelId: String, nowMs: Long) = withContext(io) { loras.detachAll(modelId, nowMs) }

    suspend fun deleteLora(id: Long) = withContext(io) { loras.delete(id) }

    /** Drop a removed model's adapters with the model. */
    suspend fun deleteLorasForModel(modelId: String) = withContext(io) { loras.deleteByModel(modelId) }

    // ── Reconstructed samples over a sensor gap ─────────────────────────────────────

    /** Store what a model reconstructed over a gap. Never a reading: see [BgInfillEntity]. */
    /**
     * Store a reconstructed run, keyed as one span. The caller passes rows in `ts` order; the span
     * key is the first row's `ts` unless the caller set one.
     *
     * Returns false, writing nothing, when any slot already holds a PROMOTED fill. The upsert is a
     * REPLACE on `ts`, so without the check a second drag across those slots orphans the promotion
     * outright: the old span key disappears (demotion answers "Span is gone"), the new key is
     * unpromoted (it answers "Not promoted"), and the `RECONSTRUCTED` readings stay in the record —
     * on the phone and on the server — with no route left to take them out.
     */
    suspend fun saveInfill(rows: List<BgInfillEntity>): Boolean = withContext(io) {
        if (rows.isEmpty()) return@withContext false
        inWriteTx {
            for (r in rows) {
                if (infills.at(r.ts)?.promotedAtMs != null) return@inWriteTx false
            }
            val spanStart = rows.first().ts
            infills.upsert(rows.map { if (it.spanStartMs == 0L) it.copy(spanStartMs = spanStart) else it })
            true
        }
    }

    /** Reconstructions in a window, as the drawing layer's own type. */
    fun observeReconstructed(fromMs: Long, toMs: Long): Flow<List<ReconstructedBg>> =
        infills.observeRange(fromMs, toMs).map { rows -> rows.map { it.infillToModel() } }

    suspend fun reconstructedInRange(fromMs: Long, toMs: Long): List<ReconstructedBg> =
        withContext(io) { infills.inRange(fromMs, toMs).map { it.infillToModel() } }

    /** The span heads in a window — one row per reconstructed run, for the Lab's list. */
    fun observeReconstructedSpans(fromMs: Long, toMs: Long): Flow<List<ReconstructedBg>> =
        infills.observeSpanHeads(fromMs, toMs).map { rows -> rows.map { it.infillToModel() } }

    /** One span's rows, oldest first — what the τ sweep and the discard act on. */
    suspend fun infillSpan(spanStartMs: Long): List<BgInfillEntity> =
        withContext(io) { infills.span(spanStartMs) }

    suspend fun reconstructedSpanSize(spanStartMs: Long): Int =
        withContext(io) { infills.spanSize(spanStartMs) }

    /**
     * Write a reconstructed span into the record as stored, syncable samples — or say why not.
     *
     * This is the one act in the app that turns model output into something every other reader
     * treats as the patient's history, so every refusal below is a safety property rather than a
     * convenience. The value stays permanently flagged `RECONSTRUCTED`, which is the discriminator
     * `isRealMeasurement` keys on: it may never clear an alarm, feed a dose, be a fit target, or be
     * scored against.
     *
     * It deliberately does NOT go through [upsertReading]. That path stamps `bgSource` from the
     * reading and feeds the Nightscout queue, and a reconstruction may do neither — no sensor
     * produced it, and a third party with no way to take a record back must not receive one.
     */
    suspend fun promoteInfillSpan(spanStartMs: Long, nowMs: Long): PromoteResult = withContext(io) {
        inWriteTx {
            val rows = infills.span(spanStartMs)
            if (rows.isEmpty()) return@inWriteTx PromoteResult.Refused("Span is gone")
            if (rows.any { it.promotedAtMs != null }) {
                return@inWriteTx PromoteResult.Refused("Already promoted")
            }
            val src = sources.authoritativeSourceId()
                ?: return@inWriteTx PromoteResult.Refused("No authoritative sensor")

            // Explicitly the null branch, and it fails closed. With no measurement to promote
            // behind, the promoted rows would become the ONLY rows for this source, and every
            // glance surface that reads the newest row — the bottom bar most of all — would show a
            // model's number as the patient's glucose.
            val newestMeasured = readings.newestMeasuredTs(src)
                ?: return@inWriteTx PromoteResult.Refused("No measured reading to promote behind")
            if (rows.any { it.ts >= newestMeasured }) {
                // This is what forbids promoting a FORECAST span: a promoted forecast becomes the
                // newest row in `cgm_reading` and is read as the current BG.
                return@inWriteTx PromoteResult.Refused("Not in the past")
            }

            // And this is what forbids promoting a BACKCAST. A one-sided reconstruction has nothing
            // bracketing it on the left, so its only anchor is its right neighbour — promoting one
            // extends the patient's history backwards on a single anchor. Drawing one is fine.
            if (readings.newestMeasuredBefore(src, rows.first().ts) == null) {
                return@inWriteTx PromoteResult.Refused("Nothing measured before the span")
            }

            for (row in rows) {
                val stored = readings.byTs(src, row.ts)
                if (stored != null && stored.provenance == ReadingProvenance.MEASURED) {
                    return@inWriteTx PromoteResult.Refused("Slot measured")
                }
                if (!row.mgdl.isFinite() || !row.lo90.isFinite() || !row.hi90.isFinite() ||
                    row.lo90 > row.mgdl || row.mgdl > row.hi90 || row.mgdl <= 0.0
                ) {
                    return@inWriteTx PromoteResult.Refused("Band is degenerate")
                }
            }

            var written = 0
            for (row in rows) {
                // The offset comes from the nearest bracketing measurement, left-preferring — the
                // one-sided rule `SPEC/inference.md` §7.4 uses for the anchor — and never from the
                // clock's current zone. `SPEC/invariants.md` §2 keys every day-boundary reduction on
                // the row's OWN offset, and a gap can straddle a DST change or a flight.
                val tz = readings.tzOffsetNearest(src, row.ts) ?: 0
                val entity = CgmReadingEntity(
                    sourceId = src,
                    tsMs = row.ts,
                    bgMgdl = Math.round(row.mgdl).toInt(),
                    trendTenthsPerMin = null,
                    minFromStart = null,
                    quality = null,
                    provenance = ReadingProvenance.RECONSTRUCTED,
                    flag = ReadingFlag.NORMAL,
                    tzOffsetMin = tz,
                    // A reconstruction was never received, so it has no receive instant of its own
                    // to be filed under; the slot it reconstructs is the only honest answer. It gets
                    // no `cgm_sample_raw` row for the same reason.
                    rxWallMs = row.ts,
                    rssi = null,
                )
                // Not every pre-flighted row reaches the record: the grid-slot rule also declines
                // to displace a valued INTERPOLATED row, which `GridStamper` writes across any
                // dropout while the Lab still lists that stretch as a gap. The count below is of
                // what was WRITTEN, not of what was considered, so the read-out cannot claim a
                // record change that did not happen.
                if (!supersedesGridSlot(readings.byTs(src, row.ts), entity)) continue
                readings.upsert(entity)
                written++
                val base = samples.byTs(row.ts) ?: emptySample(row.ts, tz, row.ts)
                samples.upsert(
                    base.copy(
                        bgMgdl = entity.bgMgdl,
                        // Null, deliberately, and not the source id: `bgSource` is an assertion
                        // about which SENSOR produced the number, and none did. The reading row's
                        // `sourceId` is a storage key and is a different question.
                        bgSource = null,
                        bgProvenance = ReadingProvenance.RECONSTRUCTED,
                        bgFlag = ReadingFlag.NORMAL,
                        tzOffsetMin = tz,
                        updatedAt = maxOf(base.updatedAt + 1, nowMs),
                    ),
                )
                enqueueIngest(row.ts, nowMs)
            }
            if (written == 0) return@inWriteTx PromoteResult.Refused("Every slot already holds a value")
            infills.markPromoted(spanStartMs, nowMs)
            PromoteResult.Promoted(written)
        }.also { if (it is PromoteResult.Promoted) _logEvents.update { t -> t + 1 } }
    }

    /**
     * Take a promoted span back out of the record.
     *
     * Only rows still flagged `RECONSTRUCTED` are removed — a measurement that has since landed in
     * one of those slots is the real thing and stays. The server's copy is cleared through the
     * ingest `clear` array, which is the only way the contract offers to erase a stored scalar;
     * without it the next catch-up would merge the promoted value straight back.
     *
     * The slot is resolved across EVERY source rather than under the current authority. Promotion
     * files under whichever source was authoritative at the time, a sensor lasts ten to fourteen
     * days, and the span list reaches back a fortnight — so any sensor change between promoting and
     * demoting would otherwise leave the reading in place while this reported it removed.
     */
    suspend fun demoteInfillSpan(spanStartMs: Long, nowMs: Long): PromoteResult = withContext(io) {
        inWriteTx {
            val rows = infills.span(spanStartMs)
            if (rows.isEmpty()) return@inWriteTx PromoteResult.Refused("Span is gone")
            if (rows.all { it.promotedAtMs == null }) {
                return@inWriteTx PromoteResult.Refused("Not promoted")
            }
            var removed = 0
            for (row in rows) {
                val stored = readings.allAt(row.ts)
                    .filter { it.provenance == ReadingProvenance.RECONSTRUCTED }
                if (stored.isEmpty()) continue
                for (r in stored) readings.deleteAt(r.sourceId, row.ts)
                val sample = samples.byTs(row.ts)
                if (sample != null && sample.bgProvenance == ReadingProvenance.RECONSTRUCTED) {
                    samples.upsert(
                        sample.copy(
                            bgMgdl = null,
                            bgSource = null,
                            bgProvenance = null,
                            bgFlag = null,
                            updatedAt = maxOf(sample.updatedAt + 1, nowMs),
                        ),
                    )
                    enqueueIngest(row.ts, nowMs)
                }
                removed++
            }
            // `removed == 0` is a legitimate end state, not a refusal: the sensor's own history can
            // deliver real measurements for slots a promoted fill occupied, and `supersedesGridSlot`
            // lets a measurement replace a reconstruction in place while this table's promoted rows
            // are deliberately spared. Nothing of the span is in the record any more, so unpromoting
            // is exactly what the record already says — and refusing here stranded the span
            // permanently: undemotable, undiscardable, and (once a cut refused to cross a promoted
            // span) blocking a cut of the real measurements that had replaced it.
            //
            // The desync this once guarded against is closed at its source instead: `cutBgRange`
            // refuses to erase a stored RECONSTRUCTED reading.
            infills.markPromoted(spanStartMs, null)
            PromoteResult.Promoted(removed)
        }.also { _logEvents.update { t -> t + 1 } }
    }


    suspend fun infillInRange(fromMs: Long, toMs: Long): List<BgInfillEntity> =
        withContext(io) { infills.inRange(fromMs, toMs) }

    fun observeInfill(fromMs: Long, toMs: Long): Flow<List<BgInfillEntity>> =
        infills.observeRange(fromMs, toMs)

    /**
     * Throw a drawn span away — the panel's discard, and the only route by which a fill the patient
     * did not want leaves the device.
     *
     * Refuses a PROMOTED span, in the statement rather than here: a promoted span has rows in
     * `sample` and on the server, and this table holds the only copy of the band they carry.
     * Demotion is the way out of that state, and it reads the span from here.
     *
     * @return true when a span was removed.
     */
    suspend fun discardInfillSpan(spanStartMs: Long): Boolean = withContext(io) {
        infills.deleteSpanIfUnpromoted(spanStartMs) > 0
    }

    /**
     * Move a drawn span's line to a different quantile of the fan it already holds.
     *
     * [line] is one mg/dL value per row of the span, oldest first, as the crate read the stored fan
     * at [tau]. Nothing is recomputed here and no fan is touched: this changes WHICH level of an
     * emitted fan is drawn, and records that level so a promotion of it cannot later be mistaken
     * for the median.
     *
     * Refuses a promoted span. Once written into the record the line is a stored sample, and moving
     * it would rewrite the patient's history from a slider.
     */
    suspend fun retauInfillSpan(spanStartMs: Long, tau: Double, line: List<Double>): Boolean =
        withContext(io) {
            inWriteTx {
                val rows = infills.span(spanStartMs)
                if (rows.isEmpty() || rows.any { it.promotedAtMs != null }) return@inWriteTx false
                if (rows.size != line.size) return@inWriteTx false
                rows.forEachIndexed { i, r -> infills.setLineAt(r.ts, line[i], tau) }
                true
            }
        }

    /**
     * Erase every BG on the grid in `[fromMs, toMs]` and hand back what was erased.
     *
     * The returned rows are the WHOLE of what [restoreBgCut] needs to put them back — each source's
     * reading at the slot, and the `sample` row's four BG fields. Captured before the delete rather
     * than reconstructed after it: once the rows are gone nothing else on the device holds a
     * deleted reading's provenance, and an undo that guessed at it would file model output, or a
     * calibration, as an ordinary sensor measurement.
     */
    suspend fun cutBgRange(fromMs: Long, toMs: Long, nowMs: Long): List<BgCut> = withContext(io) {
        requireGrid(fromMs)
        requireGrid(toMs)
        val cuts = ArrayList<BgCut>()
        inWriteTx {
            var ts = fromMs
            while (ts <= toMs) {
                val slotReadings = readings.allAt(ts)
                // A STORED RECONSTRUCTION in the range is refused whole, rather than erased along
                // with the measurements around it. Erasing one takes its row out of `cgm_reading`
                // while `bg_infill` still calls the span promoted, and the two then disagree; the
                // band — the only copy there is — becomes ordinary deletable state, and an undo
                // restores the stored reconstruction with nothing left able to take it out again.
                // Demotion is the operation that removes one, and it already exists.
                //
                // Keyed on the stored ROW rather than on a promoted band, because those two come
                // apart: a real measurement can supersede a promoted fill in place, leaving a band
                // still marked promoted over a stretch that is now entirely measured — and refusing
                // there would make ordinary readings uncuttable.
                if (slotReadings.any { it.provenance == ReadingProvenance.RECONSTRUCTED }) {
                    throw IllegalStateException("Demote the reconstruction in this stretch first")
                }
                val row = samples.byTs(ts)
                if (slotReadings.isNotEmpty() || row?.bgMgdl != null) {
                    cuts.add(
                        BgCut(
                            ts = ts,
                            readings = slotReadings,
                            bgMgdl = row?.bgMgdl,
                            bgSource = row?.bgSource,
                            bgProvenance = row?.bgProvenance,
                            bgFlag = row?.bgFlag,
                        ),
                    )
                }
                ts += GRID_MS
            }
            for (c in cuts) {
                for (r in c.readings) readings.deleteAt(r.sourceId, c.ts)
                val row = samples.byTs(c.ts)
                if (row != null) {
                    samples.upsert(
                        row.copy(
                            bgMgdl = null,
                            bgSource = null,
                            bgProvenance = null,
                            bgFlag = null,
                            updatedAt = maxOf(nowMs, row.updatedAt + 1),
                        ),
                    )
                }
                enqueueIngest(c.ts, nowMs)
            }
            // The UNPROMOTED fills only, and deliberately not [invalidateForecastDerivedInTx].
            //
            // That owner's remit is a CHANNEL-affecting change — a meal's grams, a dose's units —
            // and part of what it drops is `prediction`, every stored forecast made at or after the
            // change. A stored forecast is the record of what the model SAID at that cycle, which
            // is exactly what the hindsight sweep exists to replay; dropping a day of it because
            // one compression low was erased destroys the history rather than correcting it. A BG
            // edit falsifies one thing: a reconstruction OF that stretch, which describes a curve
            // that no longer exists.
            if (cuts.isNotEmpty()) infills.deleteFrom(fromMs)
        }
        if (cuts.isNotEmpty()) _logEvents.update { t -> t + 1 }
        cuts
    }

    /**
     * Put back exactly what [cutBgRange] took, provenance and all, and re-push each slot.
     *
     * The re-push is not optional. A cut sends the server a `clear`, and the server obeys; without
     * a matching push the value is back on the phone and gone everywhere else, which is a worse
     * state than either the cut or the undo.
     */
    suspend fun restoreBgCut(cuts: List<BgCut>, nowMs: Long) = withContext(io) {
        if (cuts.isEmpty()) return@withContext
        inWriteTx {
            for (c in cuts) {
                for (r in c.readings) readings.upsert(r)
                val row = samples.byTs(c.ts)
                if (row != null) {
                    samples.upsert(
                        row.copy(
                            bgMgdl = c.bgMgdl,
                            bgSource = c.bgSource,
                            bgProvenance = c.bgProvenance,
                            bgFlag = c.bgFlag,
                            updatedAt = maxOf(nowMs, row.updatedAt + 1),
                        ),
                    )
                }
                enqueueIngest(c.ts, nowMs)
            }
            infills.deleteFrom(cuts.minOf { it.ts })
        }
        _logEvents.update { t -> t + 1 }
    }

    suspend fun clearInfillForModel(modelId: String) = withContext(io) { infills.deleteByModel(modelId) }

    suspend fun infillCount(): Int = withContext(io) { infills.count() }

    suspend fun wipeAllData(preserveCgmSources: Boolean = false) = withContext(io) {
        inWriteTx {
            readings.deleteAll()
            rawSamples.deleteAll()
            samples.deleteAll()
            if (!preserveCgmSources) sources.deleteAll()
            doses.deleteAll()
            loggedDoses.deleteAll()
            loggedMeals.deleteAll()
            basalSchedules.deleteAll()
            advertsRaw.deleteAll()
            outbox.deleteAllRows()
            predictions.deleteAll()
            profiles.deleteAll()
            telemetry.deleteAll()
            db.savedMealDao().deleteAllItems()
            db.savedMealDao().deleteAllMeals()
            db.foodDao().deleteAllCustom()
            db.insulinTypeDao().deleteAllCustom()
            paintStrokes.deleteAll()
            conformalDeltas.deleteAll()
            loras.deleteAll()
            infills.deleteAll()
            // A deletion outlives the thing it deleted, so a reset that leaves it behind carries
            // the patient's old deletions into a new server profile — where they re-push and then
            // permanently refuse to re-hydrate the events they name.
            tombstones.deleteAll()
            exerciseFixes.deleteAll()
            exerciseSessions.deleteAll()
            // kv LAST: it holds the watch nonce ceilings + pairing bits + every setting.
            kv.deleteAll()
        }
    }

    companion object {
        const val GRID_MS: Long = 300_000L

        /**
         * How long a sub-grid sample is kept — seven days, the same age bound the outbox uses
         * (`sync/Backoff.kt`, `DrainConfig.maxAgeMs`), because both answer the same question: how far
         * back is a row still worth holding when nothing derives from it.
         *
         * These rows exist for display and diagnosis, which is a question asked about the last few
         * days at most; the grid series they were snapped into is keep-forever and is what any
         * long-range reader wants. Seven days of a three-minute sensor is about 3 400 rows.
         */
        const val RAW_SAMPLE_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** The filed instant below which a sub-grid sample has expired at [nowMs]. */
        internal fun rawSampleCutoff(nowMs: Long): Long = nowMs - RAW_SAMPLE_RETENTION_MS

        /**
         * The instants that [snapToGrid] files into [gridTs] — the inverse of the snap, and
         * the only place it is written down.
         *
         * Round-to-nearest makes it `[gridTs − GRID_MS/2, gridTs + GRID_MS/2 − 1]`: the lower bound
         * rounds up into this slot, and one millisecond past the upper bound rounds up into the next.
         * Half-open on the late side rather than the early one, because that is which way the snap
         * rounds a tie — spelling it here rather than at the call site keeps the asymmetry from
         * becoming a second, subtly different copy of §1's rule.
         */
        internal fun rawSampleWindowFor(gridTs: Long): LongRange =
            (gridTs - GRID_MS / 2)..(gridTs + GRID_MS / 2 - 1)

        /**
         * What `sample.exercise` becomes when a bout that has already written [priorGrams] into a
         * bucket now claims [grams] there — [recordExerciseCurve]'s whole arithmetic, lifted out so
         * it is testable without a database.
         *
         * `stored − prior` is what every OTHER bout (and any earlier life of this one, across a
         * process death) put in the slot, and it is kept. The subtraction floors at zero rather than
         * trusting the difference: the slot can be re-materialised under a running bout — an archive
         * restore, or a server row [SampleGapFill] materialised for a bucket the phone had none for
         * — and crediting a negative would take that back out again.
         *
         * **No ceiling, unlike the seconds this replaced.** A bucket could not hold more than five
         * minutes of time, so a seconds figure had a natural bound; grams of carbohydrate equivalent
         * have none, and clamping one would silently truncate the peak of a long bout's curve —
         * which is to say break §5's invariant that a curve sums to its event's total. A non-finite
         * input reads as nothing rather than poisoning the column: a hand-edited kv row can produce
         * one, and a NaN here would reach the wire.
         */
        internal fun mergedExerciseGrams(storedGrams: Double?, priorGrams: Double, grams: Double): Double {
            fun sane(v: Double) = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
            val others = (sane(storedGrams ?: 0.0) - sane(priorGrams)).coerceAtLeast(0.0)
            return others + sane(grams)
        }

        private fun requireGrid(ts: Long) =
            require(ts % GRID_MS == 0L) { "timestamp not on the 5-min grid: $ts" }

        /**
         * Round-to-nearest onto the five-minute grid — the repository seam's one snap, and the rule
         * `../T1DMCOMMON/SPEC/invariants.md` §1 makes part of the contract rather than an
         * implementation detail: floor and round both land on the grid and both pass every
         * validation while filing the same event in different buckets.
         *
         * Public because callers outside `:data` need it — the exercise recorder aligns a bout's
         * disposal curve with it — and a second copy in another module is exactly the divergence §1
         * warns about.
         */
        fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        /** A fresh phone-minted event id (§3.2). v4 UUID — acceptable per §8.6 (v7 preferred for
         *  time-ordering but not in the JDK); the repository writer guarantees a non-blank id. */
        private fun newClientId(): String = java.util.UUID.randomUUID().toString()

        /**
         * [base] with [reading]'s BG projected onto it — the whole of the write, lifted out so it is
         * testable without a database.
         *
         * **There is deliberately no last-writer-wins guard here.** One did exist, comparing the
         * reading's own instant against the slot's `updatedAt` and returning early when the reading was
         * older, and it was wrong twice over.
         *
         * It was redundant: [supersedesGridSlot] has already run, it is a total order over the SET of
         * samples offered for the slot, and it has just said this reading is the one the slot holds. A
         * second, differently-shaped comparison after it can only disagree with it.
         *
         * And it could silently lose a reading. `updatedAt` is bumped by every channel that writes the
         * row — steps, mood, heart rate, and the exercise disposal curve, which writes buckets up to
         * ninety minutes AHEAD of the clock. So a source that files a reading under the instant it was
         * SAMPLED rather than delivered would fail the comparison against a slot some other channel had
         * touched, and the slot would end with no `bgMgdl`, no INGEST row, nothing on the wire and
         * nothing in the statistics — while `cgm_reading` still held the reading, so nothing anywhere
         * would look wrong.
         *
         * `updatedAt` stays a MAXIMUM, and that is what keeps the row syncable once a reading's instant
         * can lag it. It is §7's ordering key for the server's idempotent upsert, so a stamp that went
         * backwards would make the server ignore the row until wall time caught up again. Held at the
         * maximum, every stamp this row ever sends is at least the one the server already holds — and
         * `POST /v1/ingest` is explicitly the endpoint that accepts an EQUAL stamp (`http-api.md`: so two
         * partial fills of one slot sharing a stamp both land), so a BG projected onto a slot another
         * channel has already stamped still reaches the server rather than being read as a redelivery.
         */
        internal fun projectedBgSample(base: SampleEntity, reading: CgmReading): SampleEntity =
            base.copy(
                tzOffsetMin = reading.tzOffsetMin,
                bgMgdl = reading.bgMgdl,
                // Stamped with the reading's own source rather than looked up: only the authoritative
                // source reaches here, and taking it from the reading means the label can never name a
                // sensor other than the one that produced the number beside it.
                bgSource = reading.sourceId.opaque,
                bgProvenance = reading.provenance,
                bgFlag = reading.flag,
                updatedAt = maxOf(base.updatedAt, reading.rxWallMs),
            )

        private fun emptySample(ts: Long, tzOffsetMin: Int, updatedAt: Long) = SampleEntity(
            ts = ts,
            tzOffsetMin = tzOffsetMin,
            bgMgdl = null,
            bgSource = null,
            bgProvenance = null,
            bgFlag = null,
            steps = null,
            mood = null,
            hr = null,
            sleep = null,
            exercise = null,
            updatedAt = updatedAt,
        )
    }
}
