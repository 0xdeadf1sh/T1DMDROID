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

/** Both ends inclusive. */
data class ReadingExtent(val oldestMs: Long, val newestMs: Long)

/** The rows, not a value: an undo must restore each source's provenance rather than refile it. */
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
    /** Wall clock for an outbox row's `createdAtMs`; every other timestamp comes from the caller. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OutboxSink {
    private val io get() = dispatchers.io

    /** Bumped on every logged meal/dose write. Those no longer touch `sample`, so
     *  [observeSampleWrites] does not fire for them. */
    private val _logEvents = MutableStateFlow(0L)
    val logEvents: StateFlow<Long> = _logEvents.asStateFlow()

    /** Set by `:app` from the persisted config. Volatile rather than a kv read: checked inside the
     *  reading-projection transaction on the CGM hot path. False keeps an unconfigured bridge quiet. */
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

    /** Room KTX `withTransaction` throws once a `SQLiteDriver` is configured, hence the writer
     *  connection directly. Connection confinement puts every DAO call in [body] in this transaction. */
    private suspend fun <R> inWriteTx(body: suspend () -> R): R =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { body() }
        }

    fun observeSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeAll().map { list -> list.map { it.toDescriptor() } }

    /** Deduplicated: a `lastSeenMs` touch re-runs the query but yields an equal descriptor, and the
     *  `flatMapLatest` consumers rebuild on every emission. */
    fun observeAuthoritativeSource(): Flow<CgmSourceDescriptor?> =
        sources.observeAuthoritative().map { it?.toDescriptor() }.distinctUntilChanged()

    /** Oldest-registered first. Deduplicated for the reason [observeAuthoritativeSource] is. */
    fun observeActiveSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeActiveSources()
            .map { list -> list.map { it.toDescriptor() } }
            .distinctUntilChanged()

    /** [authoritative] adopts the source: it is un-hidden and activated here. */
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
                    // [authoritative] means "adopt this one", never "this one is not it".
                    authoritative = authoritative || (existing?.authoritative ?: false),
                    // A re-sighting must not restart a source the user stopped.
                    active = authoritative || (existing?.active ?: false),
                    warmupWindowMin = descriptor.warmupWindowMin,
                    addedAtMs = existing?.addedAtMs ?: nowMs,
                    lastSeenMs = nowMs,
                    hidden = !authoritative && (existing?.hidden ?: false),
                    // Minted once, never revised. An archive restore bypasses this path and numbers
                    // its own rows; see `ArchiveReader.renumbered`.
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

    /** Last line of defence: number any row still carrying the unassigned sentinel, in list order. */
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

    /** Sealed before it arrives; stored and returned verbatim. */
    suspend fun sensorSecret(id: CgmSourceId): ByteArray? = withContext(io) {
        sensorSecrets.byId(id.value)?.blob
    }

    suspend fun putSensorSecret(id: CgmSourceId, blob: ByteArray, nowMs: Long) = withContext(io) {
        sensorSecrets.upsert(CgmSensorSecretEntity(sourceId = id.value, blob = blob, updatedAtMs = nowMs))
    }

    /** Unrecoverable. */
    suspend fun deleteSensorSecret(id: CgmSourceId) = withContext(io) {
        sensorSecrets.deleteById(id.value)
    }

    /** SPEC §3.1. The source it replaces stays active — demotion is not a disconnection. */
    suspend fun setAuthoritativeSource(id: CgmSourceId) = withContext(io) {
        inWriteTx {
            sources.clearAuthoritative()
            sources.setAuthoritative(id.value)
        }
    }

    suspend fun authoritativeSourceId(): CgmSourceId? = withContext(io) {
        sources.authoritativeSourceId()?.let(::CgmSourceId)
    }

    /** Additive — nothing else stops. */
    suspend fun activateSource(id: CgmSourceId) = withContext(io) { sources.activate(id.value) }

    /** Refuses the authoritative source; the caller relies on that rather than re-checking. */
    suspend fun deactivateSource(id: CgmSourceId) = withContext(io) { sources.deactivate(id.value) }

    suspend fun activeSourceIds(): List<CgmSourceId> = withContext(io) {
        sources.activeSourceIds().map(::CgmSourceId)
    }

    /** Minutes. The one door into the column, so the clamp is here rather than at the caller. */
    suspend fun setSourceWarmupWindowMin(id: CgmSourceId, minutes: Int) = withContext(io) {
        val clamped = minutes.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE)
        sources.setWarmupWindowMin(id.value, clamped)
    }

    /** Lists only; the row and its readings stay. Refuses the authoritative source. */
    suspend fun hideSource(id: CgmSourceId) = withContext(io) { sources.hide(id.value) }

    fun observeReadings(sourceId: CgmSourceId, fromMs: Long, toMs: Long): Flow<List<CgmReading>> =
        readings.observeRange(sourceId.value, fromMs, toMs).map { list -> list.map { it.toModel() } }

    /**
     * One reading per grid slot (§3.1) across every source of one sensor MODEL. [selectedSourceId]
     * resolves a contested slot only; it does not filter, and null falls through to the newest
     * reception. Widens no AUTHORITY: live value, alarms and the `sample` projection stay scoped.
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
                // SQLite rejects `IN ()`, which is what Room emits for an empty list.
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    readings.observeRangeForSources(ids, fromMs, toMs).map { rows ->
                        collapseByGridSlot(rows, selectedSourceId?.value).map { it.toModel() }
                    }
                }
            }
            // The operators above walk every row, and the sole consumer collects in composition.
            .flowOn(io)

    /** Read separately from the windowed trace so the graph's pannable domain still reaches the
     *  whole record. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOldestTsForSensorModel(sensorModelId: String): Flow<Long?> =
        sources.observeIdsForSensorModel(sensorModelId)
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(null) else readings.observeOldestTsForSources(ids)
            }
            .distinctUntilChanged()
            .flowOn(io)

    /** Deduplicated: `cgm_reading` is written more often than its newest row changes. */
    fun observeLatestReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatest(sourceId.value).map { it?.toModel() }.distinctUntilChanged()

    /** Separate from [observeLatestReading] so a promoted reconstruction never reaches a glance
     *  surface as the patient's glucose. */
    fun observeLastMeasuredReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatestMeasured(sourceId.value).map { it?.toModel() }.distinctUntilChanged()

    /** Newest first. */
    suspend fun recentReadings(sourceId: CgmSourceId, limit: Int): List<CgmReading> =
        withContext(io) { readings.recent(sourceId.value, limit).map { it.toModel() } }

    suspend fun readingExtent(sourceId: CgmSourceId): ReadingExtent? = withContext(io) {
        val oldest = readings.oldestTs(sourceId.value) ?: return@withContext null
        val newest = readings.newestTs(sourceId.value) ?: return@withContext null
        ReadingExtent(oldest, newest)
    }


    /**
     * The raw sub-grid row first and unconditionally — only a MEASURED reading has a receive instant
     * to be filed under. Then the slot contest: where [supersedesGridSlot] says no, nothing but the
     * raw store changed. That skip is the only thing keeping `sample.bgMgdl` equal to the winner.
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
                // A real measurement in a slot a model reconstructed makes the fill stale. A PROMOTED
                // span is spared: its row is the only copy of the band the stored sample carries, and
                // demotion is a deliberate act, not a side effect.
                if (isRealMeasurement(reading.provenance, reading.flag)) {
                    val fill = infills.at(reading.tsMs)
                    if (fill != null && fill.promotedAtMs == null) infills.deleteAt(reading.tsMs)
                }
                projectBg(reading)
                // The WRITE instant, never the event's: a reading recovered from a sensor's own store
                // would otherwise be age-evicted before a single attempt.
                val queuedAt = nowMs()
                enqueueIngest(reading.tsMs, queuedAt)
                // Not in `enqueueIngest`: a steps or mood write would re-queue a slot whose BG has not
                // changed, and the receiver has no idempotency key to absorb it.
                if (nightscoutBridgeEnabled) {
                    enqueueRow(
                        OutboxKind.NIGHTSCOUT,
                        "$NS_ENTRY_DEDUP_PREFIX${reading.tsMs}",
                        ByteArray(0),
                        queuedAt,
                        // Held until the slot closes: the marker coalesces only while QUEUED, so a
                        // later sub-grid sample would upload the same `date` twice.
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

    /** A change signal, not a value: the newest grid ts on every `sample` write. Deliberately NOT
     *  deduplicated — the emission is the signal. */
    fun observeSampleWrites(): Flow<Long?> = samples.observeMaxTs()

    suspend fun sampleAt(ts: Long): SampleEntity? = withContext(io) { samples.byTs(ts) }

    /** Tenths of mg/dL per minute. Authoritative rather than active, so the arrow and the number
     *  beside it come from one source. */
    suspend fun authoritativeTrendAt(ts: Long): Int? = withContext(io) {
        val sourceId = sources.authoritativeSourceId() ?: return@withContext null
        readings.byTs(sourceId, ts)?.trendTenthsPerMin
    }

    /** Bounded, not a bare `MAX(ts)`: [recordExerciseCurve] writes slots ahead of the clock, and a
     *  cursor past now would fetch nothing. */
    suspend fun newestSampleTsAtOrBefore(atMs: Long): Long? =
        withContext(io) { samples.maxTsAtOrBefore(atMs) }

    /** Oldest-first. */
    suspend fun samplesInRange(fromMs: Long, toMs: Long): List<SampleEntity> =
        withContext(io) { samples.rangeList(fromMs, toMs) }

    suspend fun sampleWindowFingerprint(fromMs: Long, toMs: Long): SampleWindowFingerprint =
        withContext(io) { samples.windowFingerprint(fromMs, toMs) }

    suspend fun stepsInRange(fromMs: Long, toMs: Long): Int =
        withContext(io) { samples.stepsInRange(fromMs, toMs) }

    /** Oldest-first, empty buckets already dropped. */
    suspend fun stepSeriesInRange(fromMs: Long, toMs: Long): List<StepBucketRow> =
        withContext(io) { samples.stepSeriesInRange(fromMs, toMs) }

    /** Already bucketed on the 5-min grid by `:sensors` (SPEC §3.5). */
    suspend fun recordSteps(gridTs: Long, tzOffsetMin: Int, steps: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(steps = steps) }

    suspend fun recordMood(gridTs: Long, tzOffsetMin: Int, mood: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(mood = mood) }

    /**
     * [buckets] carry grams of carbohydrate equivalent per five-minute bucket
     * (`../T1DMCOMMON/SPEC/invariants.md` §3, §5's exercise gamma) — never seconds, intensity or
     * energy. One transaction, so a partly rewritten curve is never visible. Writes past `now`.
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

    /** Legacy `dose_event` store, superseded by [logLoggedDose]. */
    suspend fun logDose(dose: DoseEventEntity): Long = withContext(io) { doses.insert(dose) }

    /**
     * Snaps `tsMs` to the grid (§4-#1), mints a blank `clientId` (§3.2, never re-minted) and stamps an
     * unset `loggedAtMs` — one unstamped row pins the log-gap mark at zero forever. Returns the
     * PERSISTED row, so the `:app` seam's `PUT /v1/doses` carries the same id and grid ts.
     */
    suspend fun logLoggedDose(dose: LoggedDoseEntity): LoggedDoseEntity = withContext(io) {
        val row = dose.copy(
            clientId = dose.clientId.ifBlank { newClientId() },
            tsMs = snapToGrid(dose.tsMs),
            loggedAtMs = dose.loggedAtMs.takeIf { it != 0L } ?: nowMs(),
        )
        row.copy(id = loggedDoses.insert(row)).also { _logEvents.update { t -> t + 1 } }
    }

    /** The meal twin of [logLoggedDose]: same snap, mint and stamp, same PERSISTED return. */
    suspend fun logMeal(meal: LoggedMealEntity): LoggedMealEntity = withContext(io) {
        val row = meal.copy(
            clientId = meal.clientId.ifBlank { newClientId() },
            tsMs = snapToGrid(meal.tsMs),
            loggedAtMs = meal.loggedAtMs.takeIf { it != 0L } ?: nowMs(),
        )
        row.copy(id = loggedMeals.insert(row)).also { _logEvents.update { t -> t + 1 } }
    }

    /**
     * `clientId` and `loggedAtMs` are preserved — the server upserts on the first, and the second is
     * the log-gap mark. `updatedAt` is forced strictly newer than the create it replaces. Returns the
     * persisted row.
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
     * The dose twin of [editLoggedMeal]. `mutatedActingUntilMs` records the PRE-edit action end and is
     * written once, so a chain of edits cannot walk it forward and a shortened `durationMin` cannot
     * shorten the block.
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
     * `updatedAt` is forced strictly newer than the row it retires rather than trusting [nowMs]: a
     * phone clock that has not advanced (`SPEC/invariants.md` §7) would author a stamp the server
     * ignores. Returns the tombstone; `:app` owns the wire envelope and files the push.
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

    /** The dose twin of [tombstoneLoggedMeal]. Records the action end — after the delete no row is
     *  left to read a duration off, and the rail must keep blocking. */
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

    /** The connect-time replay's work list. */
    suspend fun unpushedTombstones(): List<EventTombstone> =
        withContext(io) { tombstones.unpushed().map { it.tombstoneToModel() } }

    suspend fun markTombstonePushed(clientId: String, atMs: Long) =
        withContext(io) { tombstones.markPushed(clientId, atMs) }

    /** Ordered on `updatedAt`, so a redelivery cannot walk the stamp backward. `pushEnqueuedAtMs` is
     *  stamped: this deletion did not originate here, so there is nothing to push. */
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
     * The hydration path is `insertIgnore`, which drops an edit against an existing row. Ordered on
     * `updatedAt`. The wire carries neither mutation column, so both are minted here or the
     * dose-history rail never sees a remote edit; the pre-edit end is taken only on the first one.
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

    private suspend fun tombstoned(clientId: String, updatedAt: Long): Boolean =
        (tombstones.byClientId(clientId)?.updatedAt ?: Long.MIN_VALUE) >= updatedAt

    /**
     * The single owner of what a channel-affecting mutation invalidates. Runs inside the caller's
     * transaction. A note or a timezone correction moves no curve and must not reach here.
     */
    private suspend fun invalidateForecastDerivedInTx(affectedFromMs: Long) {
        predictions.deleteFrom(affectedFromMs)
        infills.deleteFrom(affectedFromMs)
        // Only where the fit window reaches the change. Losing a correction draws a visibly TIGHTER
        // band — `conformal.rs` fits an offset that usually widens the fan — with nothing saying why.
        for (row in conformalDeltas.all()) {
            if (row.fittedAtMs >= affectedFromMs) conformalDeltas.deleteByModel(row.modelId)
        }
        // FLAGGED, never auto-detached: detaching would change the forecaster under the patient.
        loras.markHistoryMutated(nowMs())
    }

    suspend fun loggedDosesInRange(fromMs: Long, toMs: Long): List<LoggedDoseEntity> =
        withContext(io) { loggedDoses.inRange(fromMs, toMs) }

    suspend fun loggedMealById(id: Long): LoggedMealEntity? = withContext(io) { loggedMeals.byId(id) }

    suspend fun loggedDoseById(id: Long): LoggedDoseEntity? = withContext(io) { loggedDoses.byId(id) }

    suspend fun loggedMealsInRange(fromMs: Long, toMs: Long): List<LoggedMealEntity> =
        withContext(io) { loggedMeals.inRange(fromMs, toMs) }

    /** Newest first. Entities, not a domain type: the delivered verdict joins against the outbox on
     *  a dedupKey format `:sync` owns and this module must not re-spell. */
    fun observeRecentLoggedMeals(limit: Int): Flow<List<LoggedMealEntity>> =
        loggedMeals.observeRecent(limit)

    fun observeRecentLoggedDoses(limit: Int): Flow<List<LoggedDoseEntity>> =
        loggedDoses.observeRecent(limit)

    fun observeRecentMeals(limit: Int = 3): Flow<List<RecentMeal>> =
        loggedMeals.observeRecentDistinct(limit).map { rows ->
            rows.mapNotNull { r -> r.gi?.let { gi -> RecentMeal(r.grams, gi) } }
        }

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

    /** `MAX(MIN(tsMs, loggedAtMs))`, not `MAX(tsMs)`: a dose retimed forward must not quiet the
     *  log-gap rail. */
    suspend fun latestLoggedInsulinTs(): Long? = withContext(io) { loggedDoses.latestLoggedMarkTs() }

    /** The union of both stores: `logged_dose` answers for an EDITED dose, the tombstone for a
     *  DELETED one, which has no row left to read a duration off. */
    suspend fun editedDoseActiveUntilMs(): Long? = withContext(io) {
        val edited = loggedDoses.editedDoseActiveUntilMs()
        val deleted = tombstones.latestActingUntilMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    /** Unioned like [editedDoseActiveUntilMs]: without the deletion term a block raised by a delete
     *  could never be acknowledged. */
    suspend fun latestDoseMutationMs(): Long? = withContext(io) {
        val edited = loggedDoses.latestMutationMs()
        val deleted = tombstones.latestCreatedAtMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    /** The catch-up's event high-water mark. The tombstone term stops a deletion moving the mark
     *  backward and pulling the deleted event back in. */
    suspend fun newestEventTs(): Long? = withContext(io) {
        listOfNotNull(loggedMeals.latestTs(), loggedDoses.latestTs(), tombstones.latestTs()).maxOrNull()
    }

    fun observeLatestMood(): Flow<Int?> = samples.observeLatestMood()

    /** Oldest-authored first — the order they must be painted in. Intersecting, not contained. */
    fun observePaintStrokes(fromMs: Long, toMs: Long): Flow<List<PaintStroke>> =
        paintStrokes.observeOverlapping(fromMs, toMs)
            .map { list -> list.map { it.toModel() } }
            // `toModel` decodes geometry per row and the collectors are `collectAsState`.
            .flowOn(io)

    /** A zero-point stroke is refused: it has no time bounds to index by, so it could never be
     *  selected back out. */
    suspend fun addPaintStroke(stroke: PaintStroke): Long = withContext(io) {
        require(!stroke.isEmpty) { "a paint stroke must carry at least one point" }
        paintStrokes.insert(stroke.toEntity())
    }

    suspend fun deletePaintStroke(id: Long) = withContext(io) { paintStrokes.delete(id) }

    suspend fun deleteAllPaintStrokes() = withContext(io) { paintStrokes.deleteAll() }

    // Phone-local, both tables. What syncs is the disposal curve [recordExerciseCurve] lays into the
    // wide sample.

    /** Returns the PERSISTED row. `startMs` is NOT grid-snapped, unlike [logMeal]: a bout boundary is
     *  the instant the user chose. Only the per-bucket sample write is on the grid. */
    suspend fun startExerciseSession(row: ExerciseSessionEntity): ExerciseSessionEntity =
        withContext(io) {
            val minted = row.copy(clientId = row.clientId.ifBlank { newClientId() })
            minted.copy(id = exerciseSessions.insert(minted))
        }

    /** See [ExerciseSessionDao.close] on why the identity columns are not in the statement. */
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

    /** Batched by the caller. */
    suspend fun appendExerciseFixes(rows: List<ExerciseFixEntity>) = withContext(io) {
        if (rows.isNotEmpty()) exerciseFixes.insertAll(rows)
    }

    fun observeExerciseSessions(): Flow<List<ExerciseSessionEntity>> = exerciseSessions.observeAll()

    suspend fun exerciseSession(id: Long): ExerciseSessionEntity? =
        withContext(io) { exerciseSessions.byId(id) }

    suspend fun exerciseTrack(sessionId: Long): List<ExerciseFixEntity> =
        withContext(io) { exerciseFixes.forSession(sessionId) }

    /** Includes the one running now. */
    suspend fun openExerciseSessions(): List<ExerciseSessionEntity> =
        withContext(io) { exerciseSessions.open() }

    suspend fun newestExerciseFixTs(sessionId: Long): Long? =
        withContext(io) { exerciseFixes.newestTs(sessionId) }

    /** One transaction: there is no foreign key, so a half-applied delete orphans the fixes and
     *  nothing would ever select them again to notice. */
    suspend fun deleteExerciseSession(
        id: Long,
        unwind: List<ExerciseCurveBucket>,
        nowMs: Long,
    ) = withContext(io) {
        inWriteTx {
            // Take the bout's grams back out, or the model goes on seeing disposal from a bout the
            // patient erased. `priorGrams` is THIS bout's share; an overlapping bout's is left alone.
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

    suspend fun foodCount(): Int = withContext(io) { db.foodDao().count() }

    /** Idempotent at the call site: seed only when empty. */
    suspend fun seedFoods(rows: List<FoodEntity>) = withContext(io) { db.foodDao().insertAll(rows) }

    /** Malformed FTS syntax can throw, so the MATCH is built from alnum tokens only. */
    suspend fun searchFoods(rawQuery: String, limit: Int = 30): List<FoodEntity> = withContext(io) {
        val tokens = rawQuery.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) db.foodDao().all(limit)
        else db.foodDao().search(tokens.joinToString(" ") { "$it*" }, limit)
    }

    suspend fun browseFoods(limit: Int = 50): List<FoodEntity> = withContext(io) { db.foodDao().all(limit) }

    suspend fun foodById(id: Long): FoodEntity? = withContext(io) { db.foodDao().byId(id) }

    suspend fun upsertFood(food: FoodEntity) = withContext(io) { db.foodDao().upsert(food) }

    /**
     * Returns false having written nothing when the row is gone or is a bundled seed row. `@Upsert` is
     * NOT gated on `custom`, so an unguarded write would resurrect a deleted id or convert a shipped
     * row into a user food; read and write share one transaction so the check cannot be raced.
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

    suspend fun saveMeal(name: String, items: List<SavedMealItemEntity>, nowMs: Long): Long =
        withContext(io) {
            inWriteTx {
                val mealId = db.savedMealDao().insertMeal(SavedMealEntity(name = name, updatedAt = nowMs))
                if (items.isNotEmpty()) db.savedMealDao().insertItems(items.map { it.copy(mealId = mealId) })
                mealId
            }
        }

    /** Items are replaced wholesale under the existing [id]. Returns false having written nothing
     *  when the header is gone: `saved_meal_item` has no foreign key, so items inserted against a
     *  vanished header are orphans no query reaches. */
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

    /** Oldest first, including the samples that lost the slot. Empty is legitimate: a five-minute
     *  sensor puts one sample in a slot, and a gap-filled slot never had one. */
    suspend fun rawSamplesForSlot(sourceId: CgmSourceId, gridTs: Long): List<CgmRawSample> =
        withContext(io) {
            requireGrid(gridTs)
            val window = rawSampleWindowFor(gridTs)
            rawSamples
                .rangeForSource(sourceId.value, window.first, window.last)
                .map { it.toModel() }
        }

    /** Oldest first, on the clock the samples are filed under ([CgmRawSample]), not on the grid. */
    suspend fun rawSamplesInRange(sourceId: CgmSourceId, fromMs: Long, toMs: Long): List<CgmRawSample> =
        withContext(io) { rawSamples.rangeForSource(sourceId.value, fromMs, toMs).map { it.toModel() } }

    suspend fun rawSampleCount(): Int = withContext(io) { rawSamples.count() }

    /** Returns how many went. An AGE bound with no size bound, unlike the outbox's: samples arrive at
     *  the sensor's cadence and cannot burst, and nothing derives from these rows. */
    suspend fun pruneRawSamples(nowMs: Long): Int =
        withContext(io) { rawSamples.pruneBefore(rawSampleCutoff(nowMs)) }

    suspend fun recordRawAdvert(advert: CgmAdvertRawEntity): Long =
        withContext(io) { advertsRaw.insert(advert) }

    /** Nothing drives this; `cgm_advert_raw` grows without bound. */
    suspend fun pruneRawAdvertsBefore(beforeMs: Long): Int =
        withContext(io) { advertsRaw.pruneBefore(beforeMs) }

    /** Dedup is the unique `dedupKey` index. */
    override suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long = withContext(io) { enqueueRow(kind, dedupKey, payload, nowMs, notBeforeMs) }

    /**
     * Returns -1 when the key is held by a row the drainer has already claimed. Only PENDING is swept:
     * INFLIGHT means the body is on the wire. The delete and the insert share one transaction, and
     * `outbox.id` is AUTOINCREMENT, so the drainer's snapshot cannot resolve onto the replacement.
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

    /** The third party has no tombstone: an undelivered mirror left queued arrives THERE after the
     *  patient deleted it here, with no route to take it back out. */
    private suspend fun withdrawBridgedTreatment(clientId: String) =
        outbox.deleteByDedupKey("$NS_TREATMENT_DEDUP_PREFIX$clientId")

    /**
     * Answers whether the mirror was still there to take. `/api/v1` has no update for a landed
     * treatment, and its `created_at` derives from `updatedAt`, so re-sending files a SECOND treatment
     * and the logbook double-counts. PENDING only: deleting an INFLIGHT row would not unsend it.
     */
    suspend fun withdrawEditedBridgedTreatment(clientId: String): Boolean = withContext(io) {
        outbox.deleteByDedupKeyInState("$NS_TREATMENT_DEDUP_PREFIX$clientId", OutboxState.PENDING) > 0
    }

    /** Deduplicated: the queue table is written far more often than its DEPTH moves — a claim, a
     *  backoff reschedule and an attempt bump all invalidate it without changing the count. */
    fun observeOutboxDepth(): Flow<Int> = outbox.observeDepth().distinctUntilChanged()

    /** Null when empty. */
    suspend fun oldestOutboxCreatedAt(): Long? = withContext(io) { outbox.oldestCreatedAt() }

    /** Server-bound rows only: a stuck bridge row must not falsify the re-mirror's delivery proof. */
    suspend fun oldestServerBoundOutboxCreatedAt(): Long? =
        withContext(io) { outbox.oldestCreatedAtExcluding(OutboxKind.NIGHTSCOUT) }

    /**
     * DISPLACES whatever is queued for the slot. A plain enqueue is `INSERT OR IGNORE`, so a slot
     * already INFLIGHT would drop a demotion or a BG deletion and `mergeServerSample` would gap-fill
     * the old value back. Safe in flight: the drainer deletes by ROW ID, so its delete no-ops.
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
            // Not a backoff: `attempts` stays 0 and `createdAtMs` is untouched, so FIFO order and the
            // age bound stay measured from the write.
            nextAttemptMs = notBeforeMs,
            state = OutboxState.PENDING,
        ),
    )

    /** `(madeAtMs, modelId)` REPLACEs. */
    suspend fun upsertPredictions(preds: List<ModelPrediction>, nowMs: Long) = withContext(io) {
        predictions.upsertAll(preds.map { it.toEntity(nowMs) })
    }

    /** Selected model first. */
    suspend fun latestCyclePredictions(): List<ModelPrediction> = withContext(io) {
        predictions.latestCycle().map { it.toModel() }
    }

    suspend fun predictionsInRange(fromMs: Long, toMs: Long): List<ModelPrediction> =
        withContext(io) { predictions.range(fromMs, toMs).map { it.toModel() } }

    /**
     * The rows as written, never a re-forecast. Scoped to the authoritative source: a fan conditioned
     * on the outgoing sensor would otherwise be painted over the incoming sensor's trace. A forecast
     * with no recorded source (pre-v25) is refused on the same terms.
     */
    suspend fun predictionsForModelInRange(modelId: String, fromMs: Long, toMs: Long): List<ModelPrediction> =
        withContext(io) {
            val authoritative = sources.authoritativeSourceId() ?: return@withContext emptyList()
            predictions.rangeForModel(modelId, fromMs, toMs)
                .map { it.toModel() }
                .filter { it.sourceId == authoritative }
        }

    suspend fun deletePredictionsForModel(modelId: String) = withContext(io) { predictions.deleteByModel(modelId) }

    fun observeLatestPrediction(): Flow<ModelPrediction?> =
        predictions.observeLatest().map { it?.toModel() }

    /**
     * MATURED windows of [modelId] for the on-device metric suite. `lastBg` is the persistence anchor
     * of `SPEC/invariants.md` §6.3 — measured BG at the forecast's `made_at`. A window is dropped
     * whole when the realized trajectory misses any step.
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

        // Sorted ascending for the binary-search match, reaching back one tolerance so the earliest
        // anchor is matchable. Scoped to the AUTHORITATIVE source: `cgm_reading` holds a row per
        // (source, slot), so an unscoped read scores one sensor's forecast against another's readings.
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

        // The forecast side of the same scoping. A null `sourceId` is UNKNOWN (pre-v25) and never
        // matches, so it is refused rather than assumed to be this one.
        val ofModel = predictions.range(sinceMs, nowMs - horizonMs).map { it.toModel() }
            .filter { it.modelId == modelId && it.status == ForecastStatus.OK }
        val rows = ofModel.filter { it.sourceId == authoritative }
        // Counted, not merely dropped: an unexplained empty panel after a sensor change reads as a bug.
        val nForeignSource = ofModel.size - rows.size

        var nMatured = 0
        var nIncomplete = 0
        var nq = 0
        val out = ArrayList<ForecastWindow>()
        for (p in rows) {
            if (p.stepMs != stepMs || p.nQuantiles <= 0) continue
            if (p.medianBg.size < nSteps || p.bandsMgdl.size < nSteps * p.nQuantiles) continue
            if (p.cycleTsMs + horizonMs > nowMs) continue
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
        ForecastWindowSet(out, nMatured, nIncomplete, nForeignSource)
    }

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

    fun observeProfiles(): Flow<List<ServerProfileEntity>> = profiles.observeAll()

    fun observeActiveProfile(): Flow<ServerProfileEntity?> = profiles.observeActive()

    suspend fun activeProfile(): ServerProfileEntity? = withContext(io) { profiles.active() }

    suspend fun profileById(id: String): ServerProfileEntity? = withContext(io) { profiles.byId(id) }

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

    /** No-server-over-local presence gap-fill (§3.3): [SampleGapFill] fills only fields the local row
     *  lacks, comparing no `updated_at`. The phone is the sole read-write author. */
    suspend fun mergeServerSample(patch: SamplePatch): Boolean = withContext(io) {
        requireGrid(patch.ts)
        inWriteTx {
            // Gap-fill only: never overwrite a local reading, and enqueue NO ingest — this data
            // originated here and re-pushing would echo-loop. The "already have it" test spans the
            // whole MODEL CLASS, or a sensor change re-imports the entire record under the new id.
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
            val merged = SampleGapFill.fill(samples.byTs(patch.ts), patch) ?: return@inWriteTx false
            samples.upsert(merged)
            true
        }
    }

    /**
     * One-shot gap-fill of the authoritative source's `cgm_reading` from the wide `sample` projection
     * — the migration path for server history synced before reading hydration existed, and a self-heal
     * since. Inserts only slots with no reading; returns how many. Off-main; one transaction.
     */
    suspend fun reconcileReadingsFromSamples(): Int = withContext(io) {
        val authoritative = sources.authoritativeSourceId() ?: return@withContext 0
        // Slots NO sensor holds a reading for. A narrower test lets a sensor change re-import another
        // sensor's record under this one's id.
        inWriteTx {
            val fill = samples.bgSlotsMissingReading().asSequence()
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

    /** Id-keyed hydration on REST catch-up (§3.4). Idempotent on the unique `clientId` index; never
     *  enqueues — the event originated on this phone and re-pushing would echo-loop. */
    suspend fun hydrateMealEvent(ev: LoggedMealEntity): Long = withContext(io) {
        if (tombstoned(ev.clientId, ev.updatedAt)) -1L else loggedMeals.insertIgnore(ev)
    }

    suspend fun hydrateDoseEvent(ev: LoggedDoseEntity): Long = withContext(io) {
        if (tombstoned(ev.clientId, ev.updatedAt)) -1L else loggedDoses.insertIgnore(ev)
    }

    /**
     * One bounded page of the §3.8 re-mirror. Returns the newest ts enqueued — the next page's
     * exclusive cursor — or null when the walk is complete. INGEST is reconstruct-at-drain, so this
     * re-uploads without encoding anything and keeps `:data` free of any `:sync` type.
     */
    suspend fun reMirrorScalarsBatch(afterTs: Long, limit: Int, nowMs: Long): Long? = withContext(io) {
        val page = samples.page(afterTs, limit)
        if (page.isEmpty()) return@withContext null
        inWriteTx { for (s in page) enqueueIngest(s.ts, nowMs) }
        page.last().ts
    }

    suspend fun putKv(key: String, value: String, nowMs: Long) =
        withContext(io) { kv.put(KvEntity(key, value, nowMs)) }

    suspend fun getKv(key: String): String? = withContext(io) { kv.get(key) }

    /** Deduplicated: Room invalidates per TABLE, so the FGS heartbeat and the telemetry blob re-run
     *  every kv query. Safe for a liveness reader too — anything meaning "still alive" is a stamp that
     *  differs on every write, and the first emission is always delivered. */
    fun observeKv(key: String): Flow<String?> = kv.observe(key).distinctUntilChanged()

    suspend fun allKv(): Map<String, String> =
        withContext(io) { kv.all().associate { it.key to it.value } }

    suspend fun putKvBatch(pairs: Map<String, String>, nowMs: Long) =
        withContext(io) { kv.putAll(pairs.map { (k, v) -> KvEntity(k, v, nowMs) }) }

    /** `SPEC/inference.md` §8.4. A sufficient fit only: a refusal says nothing about whether the
     *  stored correction is wrong, and overwriting it would drop the user back to the raw fan. */
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
                sourceId = cal.sourceId,
                cov90Raw = cal.cov90Raw,
                cov90Cal = cal.cov90Cal,
                meanWidth90Raw = cal.meanWidth90Raw,
                meanWidth90Cal = cal.meanWidth90Cal,
                windowDays = cal.windowDays,
                fittedAtMs = cal.fittedAtMs,
            ),
        )
    }

    suspend fun bandCalibration(modelId: String): BandCalibration? =
        withContext(io) { conformalDeltas.get(modelId)?.toModel() }

    /** A row whose blob length disagrees with its own `steps · nQuantiles` is dropped rather than
     *  reshaped; that model draws the raw fan. */
    fun observeBandCalibrations(): Flow<Map<String, BandCalibration>> =
        conformalDeltas.observeAll()
            .map { rows -> rows.mapNotNull { it.toModel() }.associateBy { it.modelId } }
            .distinctUntilChanged()
            .flowOn(io)

    suspend fun deleteBandCalibration(modelId: String) =
        withContext(io) { conformalDeltas.deleteByModel(modelId) }

    /** For when what the model IS changes under a fixed id — a replaced artifact, an adapter attached
     *  or detached. Keeping either would report one model's calibration on another's fan. */
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
            sourceId = sourceId,
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

    /** Gzipped `t1dm.archive`. [out] is NOT closed here; the caller owns the stream. */
    suspend fun writeArchive(
        out: OutputStream,
        configJson: String?,
        appVersion: String,
        nowMs: Long,
    ): ArchiveCounts = withContext(io) { ArchiveWriter(db).write(out, configJson, appVersion, nowMs) }

    /** The local row always wins, so this only ever adds what is missing. Throws
     *  [com.t1dm.data.backup.NotAnArchiveException] when the stream is some other kind of file. */
    suspend fun readArchive(input: InputStream): ArchiveResult =
        withContext(io) { ArchiveReader(db).read(input) }

    /** Model-major. */
    fun observeLoras(): Flow<List<LoraEntity>> = loras.observeAll()

    suspend fun lorasFor(modelId: String): List<LoraEntity> = withContext(io) { loras.byModel(modelId) }

    suspend fun loraById(id: Long): LoraEntity? = withContext(io) { loras.byId(id) }

    suspend fun attachedLora(modelId: String): LoraEntity? = withContext(io) { loras.attachedFor(modelId) }

    suspend fun saveLora(row: LoraEntity): Long = withContext(io) { loras.upsert(row) }

    suspend fun renameLora(id: Long, name: String, nowMs: Long) =
        withContext(io) { loras.rename(id, name, nowMs) }

    suspend fun setLoraGuardOverride(id: Long, nowMs: Long) =
        withContext(io) { loras.setGuardOverride(id, nowMs) }

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

    /** An adapter fitted on windows the patient has since rewritten describes a record that no longer
     *  exists; attach refuses until it is re-fitted. */
    suspend fun markLoraHistoryMutated(nowMs: Long) =
        withContext(io) { loras.markHistoryMutated(nowMs) }

    suspend fun attachLora(id: Long, modelId: String, nowMs: Long) = inWriteTx {
        loras.detachAll(modelId, nowMs)
        loras.attach(id, nowMs)
    }

    suspend fun detachLoras(modelId: String, nowMs: Long) = withContext(io) { loras.detachAll(modelId, nowMs) }

    suspend fun deleteLora(id: Long) = withContext(io) { loras.delete(id) }

    suspend fun deleteLorasForModel(modelId: String) = withContext(io) { loras.deleteByModel(modelId) }

    /**
     * Never a reading: see [BgInfillEntity]. Rows come in `ts` order; the span key is the first row's
     * `ts` unless the caller set one. Returns false, writing nothing, when any slot already holds a
     * PROMOTED fill — the upsert REPLACEs on `ts`, and that would orphan the promotion outright.
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

    fun observeReconstructed(fromMs: Long, toMs: Long): Flow<List<ReconstructedBg>> =
        infills.observeRange(fromMs, toMs).map { rows -> rows.map { it.infillToModel() } }

    suspend fun reconstructedInRange(fromMs: Long, toMs: Long): List<ReconstructedBg> =
        withContext(io) { infills.inRange(fromMs, toMs).map { it.infillToModel() } }

    /** One row per reconstructed run. */
    fun observeReconstructedSpans(fromMs: Long, toMs: Long): Flow<List<ReconstructedBg>> =
        infills.observeSpanHeads(fromMs, toMs).map { rows -> rows.map { it.infillToModel() } }

    /** Oldest first. */
    suspend fun infillSpan(spanStartMs: Long): List<BgInfillEntity> =
        withContext(io) { infills.span(spanStartMs) }

    suspend fun reconstructedSpanSize(spanStartMs: Long): Int =
        withContext(io) { infills.spanSize(spanStartMs) }

    /**
     * The one act that turns model output into stored history. The value stays flagged
     * `RECONSTRUCTED`, which [isRealMeasurement] keys on: it may never clear an alarm, feed a dose, be
     * a fit target, or be scored. Not via [upsertReading]: no `bgSource`, and nothing for the bridge.
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

            // Fails closed: with nothing to promote behind, these rows would be the newest for the
            // source and every glance surface would show a model's number as the patient's glucose.
            val newestMeasured = readings.newestMeasuredTs(src)
                ?: return@inWriteTx PromoteResult.Refused("No measured reading to promote behind")
            if (rows.any { it.ts >= newestMeasured }) {
                // Forbids promoting a FORECAST span: it would be the newest row and read as current BG.
                return@inWriteTx PromoteResult.Refused("Not in the past")
            }

            // Forbids promoting a BACKCAST: one-sided, so promoting it extends the patient's history
            // backwards on a single anchor. Drawing one is fine.
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
                // The row's OWN offset, never the clock's current zone (`SPEC/invariants.md` §2): a gap
                // can straddle a DST change or a flight. Left-preferring, per `inference.md` §7.4.
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
                    // Never received, so there is no receive instant: the slot is the only honest
                    // answer. It gets no `cgm_sample_raw` row for the same reason.
                    rxWallMs = row.ts,
                    rssi = null,
                )
                // The grid-slot rule also declines to displace a valued INTERPOLATED row, so the count
                // is of what was WRITTEN, not of what was considered.
                if (!supersedesGridSlot(readings.byTs(src, row.ts), entity)) continue
                readings.upsert(entity)
                written++
                val base = samples.byTs(row.ts) ?: emptySample(row.ts, tz, row.ts)
                samples.upsert(
                    base.copy(
                        bgMgdl = entity.bgMgdl,
                        // Null, not the source id: `bgSource` asserts which SENSOR produced the
                        // number, and none did. The reading row's `sourceId` is a storage key.
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
     * Only rows still flagged `RECONSTRUCTED` go; a measurement that has since landed stays. The slot
     * is resolved across EVERY source, not under the current authority: a sensor change between
     * promoting and demoting would leave the reading in place while this reported it removed.
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
            // `removed == 0` is a legitimate end state, not a refusal: a real measurement can have
            // superseded the fill in place. Refusing here stranded the span — undemotable and
            // undiscardable. The desync is closed in `cutBgRange`, which refuses to erase one.
            infills.markPromoted(spanStartMs, null)
            PromoteResult.Promoted(removed)
        }.also { _logEvents.update { t -> t + 1 } }
    }


    suspend fun infillInRange(fromMs: Long, toMs: Long): List<BgInfillEntity> =
        withContext(io) { infills.inRange(fromMs, toMs) }

    fun observeInfill(fromMs: Long, toMs: Long): Flow<List<BgInfillEntity>> =
        infills.observeRange(fromMs, toMs)

    /** Refuses a PROMOTED span, in the statement rather than here: its rows are in `sample` and on the
     *  server, and this table holds the only copy of their band. Demotion is the way out of that. */
    suspend fun discardInfillSpan(spanStartMs: Long): Boolean = withContext(io) {
        infills.deleteSpanIfUnpromoted(spanStartMs) > 0
    }

    /**
     * [line] is one mg/dL value per span row, oldest first, read off the stored fan at [tau]. Nothing
     * is recomputed and no fan is touched. Refuses a promoted span: its line is a stored sample, and
     * moving it would rewrite the patient's history from a slider.
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

    /** The returned rows are the whole of what [restoreBgCut] needs. Captured before the delete:
     *  nothing else holds a deleted reading's provenance, and an undo that guessed would misfile it. */
    suspend fun cutBgRange(fromMs: Long, toMs: Long, nowMs: Long): List<BgCut> = withContext(io) {
        requireGrid(fromMs)
        requireGrid(toMs)
        val cuts = ArrayList<BgCut>()
        inWriteTx {
            var ts = fromMs
            while (ts <= toMs) {
                val slotReadings = readings.allAt(ts)
                // A stored reconstruction is refused whole: erasing one leaves `bg_infill` calling the
                // span promoted with the only copy of the band now ordinary deletable state. Keyed on
                // the stored ROW — a measurement can supersede a promoted fill in place.
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
            // Unpromoted fills only, deliberately NOT [invalidateForecastDerivedInTx]: that also drops
            // `prediction`, the record of what the model SAID, which the hindsight sweep replays. A BG
            // edit falsifies only a reconstruction OF that stretch.
            if (cuts.isNotEmpty()) infills.deleteFrom(fromMs)
        }
        if (cuts.isNotEmpty()) _logEvents.update { t -> t + 1 }
        cuts
    }

    /** The re-push is not optional: a cut sends the server a `clear`, and without a matching push the
     *  value is back on the phone and gone everywhere else. */
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

    /** A row-only wipe to first-run contents: the seed `food` and builtin `insulin_type` rows stay,
     *  and so does `cgm_sensor_secret` — for a sensor still worn those bytes can be the only copy in
     *  existence, so clearing one is [deleteSensorSecret]'s job, never a global reset's. */
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
            // A deletion outlives what it deleted: left behind, it re-pushes into a new server profile
            // and then permanently refuses to re-hydrate the events it names.
            tombstones.deleteAll()
            exerciseFixes.deleteAll()
            exerciseSessions.deleteAll()
            // kv LAST: it holds the watch nonce ceilings + pairing bits + every setting.
            kv.deleteAll()
        }
    }

    companion object {
        const val GRID_MS: Long = 300_000L

        /** Seven days, the same age bound the outbox uses (`sync/Backoff.kt`): these rows are for
         *  display and diagnosis, and the grid series they were snapped into is keep-forever. */
        const val RAW_SAMPLE_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        internal fun rawSampleCutoff(nowMs: Long): Long = nowMs - RAW_SAMPLE_RETENTION_MS

        /** The inverse of [snapToGrid]: `[gridTs − GRID_MS/2, gridTs + GRID_MS/2 − 1]`. Half-open on
         *  the late side because that is which way the snap rounds a tie. */
        internal fun rawSampleWindowFor(gridTs: Long): LongRange =
            (gridTs - GRID_MS / 2)..(gridTs + GRID_MS / 2 - 1)

        /**
         * `stored − prior` is what every OTHER bout put in the slot, and it is kept. Floored at zero:
         * the slot can be re-materialised under a running bout. No ceiling — clamping would break §5's
         * sum-to-total. A non-finite input reads as nothing rather than sending a NaN over the wire.
         */
        internal fun mergedExerciseGrams(storedGrams: Double?, priorGrams: Double, grams: Double): Double {
            fun sane(v: Double) = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
            val others = (sane(storedGrams ?: 0.0) - sane(priorGrams)).coerceAtLeast(0.0)
            return others + sane(grams)
        }

        private fun requireGrid(ts: Long) =
            require(ts % GRID_MS == 0L) { "timestamp not on the 5-min grid: $ts" }

        /**
         * Round-to-nearest, per `../T1DMCOMMON/SPEC/invariants.md` §1: floor and round both land on the
         * grid and both pass every validation while filing the same event in different buckets. Public
         * because the exercise recorder aligns a bout's curve with it, and a second copy would diverge.
         */
        fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        /** §3.2. v4 rather than v7 per §8.6: v7 is preferred for time-ordering but is not in the JDK. */
        private fun newClientId(): String = java.util.UUID.randomUUID().toString()

        /**
         * No last-writer-wins guard here, deliberately: [supersedesGridSlot] has already decided the
         * slot. `updatedAt` stays a MAXIMUM — it is §7's ordering key, and `POST /v1/ingest` accepts an
         * EQUAL stamp (`http-api.md`), so a BG projected onto an already-stamped slot still reaches.
         */
        internal fun projectedBgSample(base: SampleEntity, reading: CgmReading): SampleEntity =
            base.copy(
                tzOffsetMin = reading.tzOffsetMin,
                bgMgdl = reading.bgMgdl,
                // Stamped from the reading rather than looked up: only the authoritative source
                // reaches here, so the label can never name a sensor other than the one that produced it.
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
