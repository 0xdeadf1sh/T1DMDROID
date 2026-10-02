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
import com.t1dm.core.model.MaskGeometry
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
import com.t1dm.data.db.LoggedExerciseEntity
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.EventTombstoneEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.TOMBSTONE_KIND_BG
import com.t1dm.data.db.TOMBSTONE_KIND_DOSE
import com.t1dm.data.db.TOMBSTONE_KIND_EXERCISE
import com.t1dm.data.db.TOMBSTONE_KIND_MEAL
import com.t1dm.data.db.actingUntilMs
import com.t1dm.data.db.bgTombstoneId
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

data class ReadingCounts(val total: Int, val measured: Int)

/** The rows, not a value; a slot holds a reading from every source, each with its provenance. */
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
    /** Wall clock, only for outbox createdAtMs; every other timestamp is the caller's. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OutboxSink {
    private val io get() = dispatchers.io

    /** Bumped on meal/dose writes; they don't project onto sample, so this won't fire. */
    private val _logEvents = MutableStateFlow(0L)
    val logEvents: StateFlow<Long> = _logEvents.asStateFlow()

    /** Set by :app; read on the hot-path transaction, hence volatile not a kv read. */
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
    private val loggedExercise get() = db.loggedExerciseDao()
    private val basalSchedules get() = db.basalScheduleDao()
    private val advertsRaw get() = db.cgmAdvertRawDao()
    private val outbox get() = db.outboxDao()
    private val kv get() = db.kvDao()
    private val telemetry get() = db.hwTelemetryDao()
    private val predictions get() = db.predictionDao()
    private val paintStrokes get() = db.paintStrokeDao()
    private val conformalDeltas get() = db.conformalDeltaDao()
    private val loras get() = db.loraDao()
    private val infills get() = db.bgInfillDao()
    private val exerciseSessions get() = db.exerciseSessionDao()
    private val tombstones get() = db.eventTombstoneDao()
    private val exerciseFixes get() = db.exerciseFixDao()

    /** BEGIN IMMEDIATE on the writer connection; every DAO call in body joins this transaction. */
    private suspend fun <R> inWriteTx(body: suspend () -> R): R =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { body() }
        }


    fun observeSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeAll().map { list -> list.map { it.toDescriptor() } }

    /** Deduplicated on descriptor; a lastSeenMs touch re-runs the query, same value out. */
    fun observeAuthoritativeSource(): Flow<CgmSourceDescriptor?> =
        sources.observeAuthoritative().map { it?.toDescriptor() }.distinctUntilChanged()

    /** Oldest-registered first. Deduplicated as [observeAuthoritativeSource] is. */
    fun observeActiveSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeActiveSources()
            .map { list -> list.map { it.toDescriptor() } }
            .distinctUntilChanged()

    /** addedAtMs/hidden preserved from the stored row on re-sighting; authoritative un-hides. */
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
                    // From the stored row unless promoting; authoritative means adopt only.
                    authoritative = authoritative || (existing?.authoritative ?: false),
                    // Preserved likewise: a re-sighting must not restart a source the user stopped.
                    active = authoritative || (existing?.active ?: false),
                    warmupWindowMin = descriptor.warmupWindowMin,
                    addedAtMs = existing?.addedAtMs ?: nowMs,
                    lastSeenMs = nowMs,
                    hidden = !authoritative && (existing?.hidden ?: false),
                    // Minted once at first insert; restore resolves its own numbers.
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

    /** Numbers unassigned-sentinel rows in list order; backstop, writers number their own. */
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


    /** Sealed before it arrives; stored and returned verbatim, and opaque here. */
    suspend fun sensorSecret(id: CgmSourceId): ByteArray? = withContext(io) {
        sensorSecrets.byId(id.value)?.blob
    }

    suspend fun putSensorSecret(id: CgmSourceId, blob: ByteArray, nowMs: Long) = withContext(io) {
        sensorSecrets.upsert(CgmSensorSecretEntity(sourceId = id.value, blob = blob, updatedAtMs = nowMs))
    }

    /** Unrecoverable; see [CgmSensorSecretDao.deleteById]. */
    suspend fun deleteSensorSecret(id: CgmSourceId) = withContext(io) {
        sensorSecrets.deleteById(id.value)
    }

    /** Exactly-one-authoritative, atomically (SPEC §3.1); the replaced source stays active. */
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

    /** Refuses the authoritative source ([CgmSourceDao.deactivate]). */
    suspend fun deactivateSource(id: CgmSourceId) = withContext(io) { sources.deactivate(id.value) }

    /** Every sensor ever registered, hidden and inactive included, oldest first. */
    suspend fun allSourceIds(): List<CgmSourceId> = withContext(io) {
        sources.all().map { CgmSourceId(it.sourceId) }
    }

    suspend fun activeSourceIds(): List<CgmSourceId> = withContext(io) {
        sources.activeSourceIds().map(::CgmSourceId)
    }

    /** Minutes, clamped to WARMUP_WINDOW_RANGE here; the one door into the column. */
    suspend fun setSourceWarmupWindowMin(id: CgmSourceId, minutes: Int) = withContext(io) {
        val clamped = minutes.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE)
        sources.setWarmupWindowMin(id.value, clamped)
    }

    /** The readings stay, and so does the row. Refuses the authoritative source. */
    suspend fun hideSource(id: CgmSourceId) = withContext(io) { sources.hide(id.value) }


    fun observeReadings(sourceId: CgmSourceId, fromMs: Long, toMs: Long): Flow<List<CgmReading>> =
        readings.observeRange(sourceId.value, fromMs, toMs).map { list -> list.map { it.toModel() } }

    /** One sensor's readings only; splicing two same-model sensors draws an unmeasured line. */
    fun observeReadingsForSource(
        sourceId: CgmSourceId,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<CgmReading>> =
        readings.observeRange(sourceId.value, fromMs, toMs)
            .map { rows -> rows.map { it.toModel() } }
            // Entity→domain pass would run on Compose main; Room already emits off-main.
            .flowOn(io)

    /** The sensor holding the most readings in the window, believed now or not; null if none. */
    suspend fun sourceWithMostReadingsIn(fromMs: Long, toMs: Long): CgmSourceId? = withContext(io) {
        readings.sourceWithMostReadings(fromMs, toMs)?.let { CgmSourceId(it) }
    }

    /** This sensor's floor as one aggregate, not windowed; covers the whole wear. */
    fun observeOldestTsForSource(sourceId: CgmSourceId): Flow<Long?> =
        readings.observeOldestTsForSource(sourceId.value)
            .distinctUntilChanged()
            .flowOn(io)

    /** Deduplicated: `cgm_reading` is written far more often than its newest row changes. */
    fun observeLatestReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatest(sourceId.value).map { it?.toModel() }.distinctUntilChanged()

    /** MEASURED rows only; a promoted reconstruction is ordinary, would reach the lock screen. */
    fun observeLastMeasuredReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatestMeasured(sourceId.value).map { it?.toModel() }.distinctUntilChanged()

    /** Newest first. */
    suspend fun recentReadings(sourceId: CgmSourceId, limit: Int): List<CgmReading> =
        withContext(io) { readings.recent(sourceId.value, limit).map { it.toModel() } }

    /** Oldest first. */
    suspend fun readingsInRange(sourceId: CgmSourceId, fromMs: Long, toMs: Long): List<CgmReading> =
        withContext(io) { readings.rangeForSource(sourceId.value, fromMs, toMs).map { it.toModel() } }

    suspend fun readingExtent(sourceId: CgmSourceId): ReadingExtent? = withContext(io) {
        val oldest = readings.oldestTs(sourceId.value) ?: return@withContext null
        val newest = readings.newestTs(sourceId.value) ?: return@withContext null
        ReadingExtent(oldest, newest)
    }

    /** Rows on record for the source, and how many of them are measured, valid values. */
    suspend fun readingCounts(sourceId: CgmSourceId): ReadingCounts = withContext(io) {
        ReadingCounts(readings.countForSource(sourceId.value), readings.countMeasuredForSource(sourceId.value))
    }


    /** Raw sub-grid row first (MEASURED only); a cut slot or a lost contest changes only raw. */
    suspend fun upsertReading(reading: CgmReading) = withContext(io) {
        requireGrid(reading.tsMs)
        inWriteTx {
            if (reading.provenance == ReadingProvenance.MEASURED) {
                rawSamples.insertIgnore(reading.toRawEntity())
            }
            if (tombstones.byClientId(bgTombstoneId(reading.tsMs)) != null) return@inWriteTx
            val entity = reading.toEntity()
            val stored = readings.byTs(entity.sourceId, entity.tsMs)
            if (!supersedesGridSlot(stored, entity)) return@inWriteTx
            readings.upsert(entity)
            val authoritative = sources.authoritativeSourceId()
            if (authoritative == reading.sourceId.value && reading.flag != ReadingFlag.INVALID) {
                // A real measurement stales an unpromoted fill; PROMOTED spans are spared.
                if (isRealMeasurement(reading.provenance, reading.flag)) {
                    val fill = infills.at(reading.tsMs)
                    if (fill != null && fill.promotedAtMs == null) infills.deleteAt(reading.tsMs)
                }
                projectBg(reading)
                // Fed here, not via mergeSampleInTx; else steps/mood re-queue unchanged BG.
                if (nightscoutBridgeEnabled) {
                    enqueueRow(
                        OutboxKind.NIGHTSCOUT,
                        "$NS_ENTRY_DEDUP_PREFIX${reading.tsMs}",
                        ByteArray(0),
                        // Write instant, not the event's; a late reading else age-evicts at once.
                        nowMs(),
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


    /** Change signal for the wide projection; not deduplicated, the emission is the signal. */
    fun observeSampleWrites(): Flow<Long?> = samples.observeMaxTs()

    suspend fun sampleAt(ts: Long): SampleEntity? = withContext(io) { samples.byTs(ts) }

    /** Tenths mg/dL/min at one slot, authoritative source only (matches the number shown). */
    suspend fun authoritativeTrendAt(ts: Long): Int? = withContext(io) {
        val sourceId = sources.authoritativeSourceId() ?: return@withContext null
        readings.byTs(sourceId, ts)?.trendTenthsPerMin
    }

    /** Bounded, not bare MAX(ts): exercise writes ahead of now, catch-up mustn't read those. */
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

    /** Steps arrive already bucketed on the 5-min grid by `:sensors` (SPEC §3.5). */
    suspend fun recordSteps(gridTs: Long, tzOffsetMin: Int, steps: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(steps = steps) }

    suspend fun recordMood(gridTs: Long, tzOffsetMin: Int, mood: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(mood = mood) }

    /** Grams/5min bucket (SPEC §3,§5); SET within a bout, ADDS across bouts, one transaction. */
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

    /** Superseded by [logLoggedDose]. No `sample` projection. */
    suspend fun logDose(dose: DoseEventEntity): Long = withContext(io) { doses.insert(dose) }


    /** Snap+mint+stamp authority: grid-snaps tsMs, mints clientId, stamps loggedAtMs. */
    suspend fun logLoggedDose(dose: LoggedDoseEntity): LoggedDoseEntity = withContext(io) {
        val row = dose.copy(
            clientId = dose.clientId.ifBlank { newClientId() },
            tsMs = snapToGrid(dose.tsMs),
            loggedAtMs = dose.loggedAtMs.takeIf { it != 0L } ?: nowMs(),
        )
        row.copy(id = loggedDoses.insert(row)).also { _logEvents.update { t -> t + 1 } }
    }

    /** Meal twin of logLoggedDose; same snap+mint+stamp rule, same persisted return. */
    suspend fun logMeal(meal: LoggedMealEntity): LoggedMealEntity = withContext(io) {
        val row = meal.copy(
            clientId = meal.clientId.ifBlank { newClientId() },
            tsMs = snapToGrid(meal.tsMs),
            loggedAtMs = meal.loggedAtMs.takeIf { it != 0L } ?: nowMs(),
        )
        row.copy(id = loggedMeals.insert(row)).also { _logEvents.update { t -> t + 1 } }
    }


    /** clientId/loggedAtMs preserved; the latter is the log-gap mark. */
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

    /** Dose twin of editLoggedMeal; mutatedActingUntilMs writes once, edits can't move it. */
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

    /** updatedAt forced newer than the retired row, not trusting nowMs (clock skew). */
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

    /** Dose twin of tombstoneLoggedMeal; also records action-curve end since the row is gone. */
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

    /** Single owner of this decision; runs in caller's tx, only for channel-affecting changes. */
    private suspend fun invalidateForecastDerivedInTx(affectedFromMs: Long) {
        predictions.deleteFrom(affectedFromMs)
        infills.deleteFrom(affectedFromMs)
        // Fit window meets affectedFromMs exactly at fittedAtMs>=that; blanket delete looks wrong.
        for (row in conformalDeltas.all()) {
            if (row.fittedAtMs >= affectedFromMs) conformalDeltas.deleteByModel(row.modelId)
        }
        // Flagged, never auto-detached: that would change the forecaster as an edit side effect.
        loras.markHistoryMutated(nowMs())
    }

    suspend fun loggedDosesInRange(fromMs: Long, toMs: Long): List<LoggedDoseEntity> =
        withContext(io) { loggedDoses.inRange(fromMs, toMs) }

    fun observeLoggedDosesInRange(fromMs: Long, toMs: Long): Flow<List<LoggedDoseEntity>> =
        loggedDoses.observeRange(fromMs, toMs)

    suspend fun loggedMealById(id: Long): LoggedMealEntity? = withContext(io) { loggedMeals.byId(id) }

    suspend fun loggedDoseById(id: Long): LoggedDoseEntity? = withContext(io) { loggedDoses.byId(id) }

    suspend fun loggedMealsInRange(fromMs: Long, toMs: Long): List<LoggedMealEntity> =
        withContext(io) { loggedMeals.inRange(fromMs, toMs) }

    fun observeLoggedMealsInRange(fromMs: Long, toMs: Long): Flow<List<LoggedMealEntity>> =
        loggedMeals.observeRange(fromMs, toMs)

    /** Newest first; entities, not the domain type. */
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

    /** MAX(MIN(tsMs,loggedAtMs)), not MAX(tsMs): an edit only moves the mark backward. */
    suspend fun latestLoggedInsulinTs(): Long? = withContext(io) { loggedDoses.latestLoggedMarkTs() }

    /** Union of both stores: logged_dose for an edit, tombstone carries a deleted dose's end. */
    suspend fun editedDoseActiveUntilMs(): Long? = withContext(io) {
        val edited = loggedDoses.editedDoseActiveUntilMs()
        val deleted = tombstones.latestActingUntilMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    /** Unioned like editedDoseActiveUntilMs; else a delete-raised block could never ack. */
    suspend fun latestDoseMutationMs(): Long? = withContext(io) {
        val edited = loggedDoses.latestMutationMs()
        val deleted = tombstones.latestCreatedAtMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    fun observeLatestMood(): Flow<Int?> = samples.observeLatestMood()


    /** Oldest-authored first (paint order); intersection not containment, wide strokes arrive. */
    fun observePaintStrokes(fromMs: Long, toMs: Long): Flow<List<PaintStroke>> =
        paintStrokes.observeOverlapping(fromMs, toMs)
            .map { list -> list.map { it.toModel() } }
            // Per-row blob decode; without this the window's geometry deserialises on Compose main.
            .flowOn(io)

    /** Refuses a zero-point stroke; it has no time bounds to index by, could never be selected. */
    suspend fun addPaintStroke(stroke: PaintStroke): Long = withContext(io) {
        require(!stroke.isEmpty) { "a paint stroke must carry at least one point" }
        paintStrokes.insert(stroke.toEntity())
    }

    suspend fun deletePaintStroke(id: Long) = withContext(io) { paintStrokes.delete(id) }

    suspend fun deleteAllPaintStrokes() = withContext(io) { paintStrokes.deleteAll() }

    // Phone-local, all three tables.

    /** Row+curve in one transaction (else half-applied is unrecoverable); buckets: priorGrams=0. */
    suspend fun logLoggedExercise(
        row: LoggedExerciseEntity,
        buckets: List<ExerciseCurveBucket>,
        nowMs: Long,
    ): LoggedExerciseEntity = withContext(io) {
        val minted = row.copy(
            clientId = row.clientId.ifBlank { newClientId() },
            tsMs = snapToGrid(row.tsMs),
            loggedAtMs = row.loggedAtMs.takeIf { it != 0L } ?: nowMs,
        )
        inWriteTx {
            val id = loggedExercise.insert(minted)
            for (b in buckets) {
                mergeSampleInTx(b.gridTs, b.tzOffsetMin, nowMs) {
                    it.copy(exercise = mergedExerciseGrams(it.exercise, b.priorGrams, b.grams))
                }
            }
            minted.copy(id = id)
        }.also { _logEvents.update { t -> t + 1 } }
    }

    /** unwind removes the old curve, write lays the new; separate since a shift overlaps itself. */
    suspend fun editLoggedExercise(
        row: LoggedExerciseEntity,
        unwind: List<ExerciseCurveBucket>,
        write: List<ExerciseCurveBucket>,
        nowMs: Long,
    ): LoggedExerciseEntity? = withContext(io) {
        val next = row.copy(
            tsMs = snapToGrid(row.tsMs),
            updatedAt = maxOf(row.updatedAt + 1, nowMs),
            mutatedAtMs = nowMs,
        )
        inWriteTx {
            if (loggedExercise.byId(next.id) == null) return@inWriteTx null
            for (b in unwind) {
                mergeSampleInTx(b.gridTs, b.tzOffsetMin, nowMs) {
                    it.copy(exercise = mergedExerciseGrams(it.exercise, b.priorGrams, 0.0))
                }
            }
            for (b in write) {
                mergeSampleInTx(b.gridTs, b.tzOffsetMin, nowMs) {
                    it.copy(exercise = mergedExerciseGrams(it.exercise, b.priorGrams, b.grams))
                }
            }
            loggedExercise.update(next)
            next
        }?.also { _logEvents.update { t -> t + 1 } }
    }

    /** unwind carries priorGrams; tombstone stops a restore reviving the row grams-less. */
    suspend fun deleteLoggedExercise(
        id: Long,
        unwind: List<ExerciseCurveBucket>,
        nowMs: Long,
    ) = withContext(io) {
        inWriteTx {
            loggedExercise.byId(id)?.let { row ->
                tombstones.upsert(
                    EventTombstoneEntity(
                        clientId = row.clientId,
                        kind = TOMBSTONE_KIND_EXERCISE,
                        tsMs = row.tsMs,
                        tzOffsetMin = row.tzOffsetMin,
                        updatedAt = maxOf(nowMs, row.updatedAt + 1),
                        createdAtMs = nowMs,
                        actingUntilMs = null,
                    ),
                )
            }
            for (b in unwind) {
                mergeSampleInTx(b.gridTs, b.tzOffsetMin, nowMs) {
                    it.copy(exercise = mergedExerciseGrams(it.exercise, b.priorGrams, 0.0))
                }
            }
            loggedExercise.delete(id)
        }
        _logEvents.update { t -> t + 1 }
    }

    suspend fun loggedExerciseById(id: Long): LoggedExerciseEntity? =
        withContext(io) { loggedExercise.byId(id) }

    suspend fun loggedExerciseInRange(fromMs: Long, toMs: Long): List<LoggedExerciseEntity> =
        withContext(io) { loggedExercise.inRange(fromMs, toMs) }

    fun observeLoggedExerciseInRange(fromMs: Long, toMs: Long): Flow<List<LoggedExerciseEntity>> =
        loggedExercise.observeRange(fromMs, toMs)

    fun observeRecentLoggedExercise(limit: Int): Flow<List<LoggedExerciseEntity>> =
        loggedExercise.observeRecent(limit)

    /** startMs is NOT grid-snapped, unlike logMeal/logLoggedDose; only the sample write is. */
    suspend fun startExerciseSession(row: ExerciseSessionEntity): ExerciseSessionEntity =
        withContext(io) {
            val minted = row.copy(clientId = row.clientId.ifBlank { newClientId() })
            minted.copy(id = exerciseSessions.insert(minted))
        }

    /** See ExerciseSessionDao.close on why identity columns are not in the statement. */
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

    suspend fun appendExerciseFixes(rows: List<ExerciseFixEntity>) = withContext(io) {
        if (rows.isNotEmpty()) exerciseFixes.insertAll(rows)
    }

    fun observeExerciseSessions(): Flow<List<ExerciseSessionEntity>> = exerciseSessions.observeAll()

    suspend fun exerciseSession(id: Long): ExerciseSessionEntity? =
        withContext(io) { exerciseSessions.byId(id) }

    suspend fun exerciseTrack(sessionId: Long): List<ExerciseFixEntity> =
        withContext(io) { exerciseFixes.forSession(sessionId) }

    suspend fun openExerciseSessions(): List<ExerciseSessionEntity> =
        withContext(io) { exerciseSessions.open() }

    suspend fun newestExerciseFixTs(sessionId: Long): Long? =
        withContext(io) { exerciseFixes.newestTs(sessionId) }

    /** One transaction: no FK enforces it; a half-applied delete leaves orphaned fixes. */
    suspend fun deleteExerciseSession(
        id: Long,
        unwind: List<ExerciseCurveBucket>,
        nowMs: Long,
    ) = withContext(io) {
        inWriteTx {
            // Removes this bout's disposal grams from every slot; priorGrams leaves overlaps alone.
            for (b in unwind) {
                mergeSampleInTx(b.gridTs, b.tzOffsetMin, nowMs) {
                    it.copy(exercise = mergedExerciseGrams(it.exercise, b.priorGrams, 0.0))
                }
            }
            exerciseFixes.deleteForSession(id)
            exerciseSessions.delete(id)
        }
    }


    suspend fun foodCount(): Int = withContext(io) { db.foodDao().count() }

    /** Idempotent at the call site: seed only when empty. */
    suspend fun seedFoods(rows: List<FoodEntity>) = withContext(io) { db.foodDao().insertAll(rows) }

    /** Sanitized to a prefix MATCH; blank falls to browse. Alnum tokens only (FTS can throw). */
    suspend fun searchFoods(rawQuery: String, limit: Int = 30): List<FoodEntity> = withContext(io) {
        val tokens = rawQuery.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) db.foodDao().all(limit)
        else db.foodDao().search(tokens.joinToString(" ") { "$it*" }, limit)
    }

    suspend fun browseFoods(limit: Int = 50): List<FoodEntity> = withContext(io) { db.foodDao().all(limit) }

    suspend fun foodById(id: Long): FoodEntity? = withContext(io) { db.foodDao().byId(id) }

    suspend fun upsertFood(food: FoodEntity) = withContext(io) { db.foodDao().upsert(food) }

    /** False if row is gone or a seed row; @Upsert isn't custom-gated, so guard here. */
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

    /** Snapshots replaced wholesale under id; false if header gone (no FK, would orphan). */
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

    suspend fun builtinInsulinTypes(): List<InsulinTypeEntity> = withContext(io) { db.insulinTypeDao().builtins() }

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
        // tzOffsetMin seeds a new row only; §2 fixes tz_offset as authored-at, not today's zone.
        val base = samples.byTs(gridTs) ?: emptySample(gridTs, tzOffsetMin, nowMs)
        samples.upsert(edit(base).copy(updatedAt = maxOf(base.updatedAt, nowMs)))
    }


    /** Oldest first, including samples that lost the slot; a slot can legitimately be empty. */
    suspend fun rawSamplesForSlot(sourceId: CgmSourceId, gridTs: Long): List<CgmRawSample> =
        withContext(io) {
            requireGrid(gridTs)
            val window = rawSampleWindowFor(gridTs)
            rawSamples
                .rangeForSource(sourceId.value, window.first, window.last)
                .map { it.toModel() }
        }

    /** Oldest first, on the clock samples are filed under, not on the grid. */
    suspend fun rawSamplesInRange(sourceId: CgmSourceId, fromMs: Long, toMs: Long): List<CgmRawSample> =
        withContext(io) { rawSamples.rangeForSource(sourceId.value, fromMs, toMs).map { it.toModel() } }

    suspend fun rawSampleCount(): Int = withContext(io) { rawSamples.count() }

    /** Every minFromStart filed for sourceId at/after sinceMs; complete only from that cutoff. */
    suspend fun receivedSampleMinutes(sourceId: CgmSourceId, sinceMs: Long): List<Int> =
        withContext(io) { rawSamples.minutesForSource(sourceId.value, sinceMs) }

    /** The instant behind which the raw store has been swept and vouches for nothing. */
    fun rawSamplesCompleteSince(nowMs: Long): Long = rawSampleCutoff(nowMs)

    /** Age bound only, no size bound (samples can't burst); driven by 5-min housekeeping. */
    suspend fun pruneRawSamples(nowMs: Long): Int =
        withContext(io) { rawSamples.pruneBefore(rawSampleCutoff(nowMs)) }


    suspend fun recordRawAdvert(advert: CgmAdvertRawEntity): Long =
        withContext(io) { advertsRaw.insert(advert) }

    /** When each frame arrived, beside its sample index; see Ct5AnchorRepair. */
    suspend fun advertArrivals(sourceId: CgmSourceId): List<Pair<Long, Int>> =
        withContext(io) { advertsRaw.arrivalsForSource(sourceId.value).map { it.rxWallMs to it.minFromStart } }

    /** Deletes every reading/raw sample for sourceId; secret/row/cursor untouched. */
    suspend fun deleteReadingsForSource(sourceId: CgmSourceId): Int = withContext(io) {
        inWriteTx {
            // Projection goes too: sample feeds stats and the forecast.
            samples.clearBgFromSource(sourceId.opaque) +
                rawSamples.deleteForSource(sourceId.value) +
                readings.deleteForSource(sourceId.value)
        }
    }

    /** Nothing drives this; cgm_advert_raw grows unbounded, pruneRawSamples sweeps it. */
    suspend fun pruneRawAdvertsBefore(beforeMs: Long): Int =
        withContext(io) { advertsRaw.pruneBefore(beforeMs) }


    /** Dedup is enforced by the unique `dedupKey` index. */
    override suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long,
    ): Long = withContext(io) { enqueueRow(kind, dedupKey, payload, nowMs, notBeforeMs) }

    /** Third party has no tombstone; an undelivered mirror can still arrive there after delete. */
    private suspend fun withdrawBridgedTreatment(clientId: String) =
        outbox.deleteByDedupKey("$NS_TREATMENT_DEDUP_PREFIX$clientId")

    /** True only for a never-tried mirror: a tried one may have landed, /api/v1 has no update. */
    suspend fun withdrawEditedBridgedTreatment(clientId: String): Boolean = withContext(io) {
        outbox.deleteUntriedByDedupKey("$NS_TREATMENT_DEDUP_PREFIX$clientId", OutboxState.PENDING) > 0
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
            // Not a backoff: attempts stays 0, createdAtMs untouched, FIFO/age measure from write.
            nextAttemptMs = notBeforeMs,
            state = OutboxState.PENDING,
        ),
    )


    /** `(madeAtMs, modelId, sourceId)` replaces, a null source included. */
    suspend fun upsertPredictions(preds: List<ModelPrediction>, nowMs: Long) = withContext(io) {
        val rows = preds.map { it.toEntity(nowMs) }
        inWriteTx {
            for (r in rows) predictions.deleteSlot(r.madeAtMs, r.modelId, r.sourceId)
            predictions.upsertAll(rows)
        }
    }

    /** The authoritative sensor's latest cycle, selected model first. */
    suspend fun latestCyclePredictions(): List<ModelPrediction> = withContext(io) {
        predictions.latestCycle(sources.authoritativeSourceId()).map { it.toModel() }
    }

    suspend fun predictionsInRange(fromMs: Long, toMs: Long): List<ModelPrediction> =
        withContext(io) { predictions.range(fromMs, toMs).map { it.toModel() } }

    /** Ascending write order; one sensor's, else another's fan bleeds in. */
    suspend fun predictionsForModelInRange(
        modelId: String,
        sourceId: CgmSourceId,
        fromMs: Long,
        toMs: Long,
    ): List<ModelPrediction> = withContext(io) {
        predictions.rangeForModel(modelId, fromMs, toMs)
            .map { it.toModel() }
            .filter { it.sourceId == sourceId.value }
    }

    suspend fun deletePredictionsForModel(modelId: String) = withContext(io) { predictions.deleteByModel(modelId) }

    fun observeLatestPrediction(): Flow<ModelPrediction?> =
        predictions.observeLatest().map { it?.toModel() }

    /** Window madeAt+1..horizonMaxMin; any CGM gap drops it. Truth = MEASURED/NORMAL (§6.3). */
    suspend fun forecastWindows(
        modelId: String,
        horizonMaxMin: Int,
        sinceMs: Long,
        nowMs: Long,
        toleranceMs: Long = 150_000L, // half a 5-min grid step
    ): ForecastWindowSet {
        val authoritative = withContext(io) { sources.authoritativeSourceId() } ?: return ForecastWindowSet.EMPTY
        return windowsOf(setOf(authoritative), horizonMaxMin, sinceMs, nowMs, toleranceMs) { horizonMs ->
            predictions.range(sinceMs, nowMs - horizonMs).map { it.toModel() }.filter { it.modelId == modelId }
        }
    }

    /** [forecastWindows]'s pairing for forecasts that were never stored: a backtest's, pooled. */
    suspend fun forecastWindowsOf(
        forecasts: List<ModelPrediction>,
        sourceIds: Set<CgmSourceId>,
        horizonMaxMin: Int,
        sinceMs: Long,
        nowMs: Long,
        toleranceMs: Long = 150_000L,
    ): ForecastWindowSet =
        windowsOf(sourceIds.mapTo(HashSet()) { it.value }, horizonMaxMin, sinceMs, nowMs, toleranceMs) { forecasts }

    private class Truth(val ts: LongArray, val rows: List<Pair<Long, Int>>)

    private suspend fun windowsOf(
        scored: Set<String>,
        horizonMaxMin: Int,
        sinceMs: Long,
        nowMs: Long,
        toleranceMs: Long,
        forecasts: suspend (horizonMs: Long) -> List<ModelPrediction>,
    ): ForecastWindowSet = withContext(io) {
        val stepMs = CurveEngine.STEP_MS
        val horizonMs = horizonMaxMin.toLong() * 60_000L
        if (horizonMaxMin <= 0 || horizonMs % stepMs != 0L) return@withContext ForecastWindowSet.EMPTY
        val nSteps = (horizonMs / stepMs).toInt()

        // Sorted for binary search; a forecast meets its own sensor, else cross-sensor noise=error.
        val truthBySource = HashMap<String, Truth>(scored.size * 2)
        for (src in scored) {
            val rows = readings.rangeForSource(src, sinceMs - toleranceMs, nowMs)
                .asSequence()
                .filter { it.bgMgdl != null && isRealMeasurement(it.provenance, it.flag) }
                .map { it.tsMs to it.bgMgdl!! }
                .distinctBy { it.first }
                .sortedBy { it.first }
                .toList()
            if (rows.isNotEmpty()) truthBySource[src] = Truth(LongArray(rows.size) { rows[it].first }, rows)
        }
        if (truthBySource.isEmpty()) return@withContext ForecastWindowSet.EMPTY

        // Forecast side of the same scoping; a null sourceId (pre-v25) never matches, is refused.
        val ofModel = forecasts(horizonMs).filter { it.status == ForecastStatus.OK }
        val rows = ofModel.filter { it.sourceId in scored }
        // Counted, not just dropped: an empty panel after a sensor change would look broken.
        val nForeignSource = ofModel.size - rows.size

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

            val truth = truthBySource[p.sourceId]
            val anchor = truth?.let { nearestWithin(it.ts, it.rows, p.cycleTsMs, toleranceMs) }
            if (truth == null || anchor == null) { nIncomplete++; continue }
            val realized = ArrayList<Double>(nSteps)
            for (i in 1..nSteps) {
                val v = nearestWithin(truth.ts, truth.rows, p.cycleTsMs + i * stepMs, toleranceMs) ?: break
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

    /** [truthTs] must be sorted ascending. */
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


    suspend fun putKv(key: String, value: String, nowMs: Long) =
        withContext(io) { kv.put(KvEntity(key, value, nowMs)) }

    suspend fun getKv(key: String): String? = withContext(io) { kv.get(key) }

    /** Rates under [sourcePrefix] before [beforeMs] ÷10, once: [onceKey] commits in the same tx. */
    suspend fun divideRatesByTenOnce(sourcePrefix: String, beforeMs: Long, onceKey: String, nowMs: Long): Int =
        withContext(io) {
            inWriteTx {
                if (kv.get(onceKey) != null) return@inWriteTx 0
                val n = readings.divideRatesByTen(sourcePrefix, beforeMs) +
                    rawSamples.divideRatesByTen(sourcePrefix, beforeMs)
                kv.put(KvEntity(onceKey, nowMs.toString(), nowMs))
                n
            }
        }

    /** Deduplicated on the raw string; a write to any key else re-runs every kv query. */
    fun observeKv(key: String): Flow<String?> = kv.observe(key).distinctUntilChanged()

    suspend fun allKv(): Map<String, String> =
        withContext(io) { kv.all().associate { it.key to it.value } }

    suspend fun putKvBatch(pairs: Map<String, String>, nowMs: Long) =
        withContext(io) { kv.putAll(pairs.map { (k, v) -> KvEntity(k, v, nowMs) }) }

    // Band recalibration: `SPEC/inference.md` §8.4

    /** Caller writes only a sufficient fit; a refusal doesn't mean the stored one is wrong. */
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

    /** A row whose blob length disagrees with steps·nQuantiles is dropped, model draws raw fan. */
    fun observeBandCalibrations(): Flow<Map<String, BandCalibration>> =
        conformalDeltas.observeAll()
            .map { rows -> rows.mapNotNull { it.toModel() }.associateBy { it.modelId } }
            .distinctUntilChanged()
            .flowOn(io)

    suspend fun deleteBandCalibration(modelId: String) =
        withContext(io) { conformalDeltas.deleteByModel(modelId) }

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


    /** [out] is NOT closed here; the caller owns the SAF stream. */
    suspend fun writeArchive(
        out: OutputStream,
        configJson: String?,
        appVersion: String,
        nowMs: Long,
    ): ArchiveCounts = withContext(io) { ArchiveWriter(db).write(out, configJson, appVersion, nowMs) }

    /** Local row always wins, adds only what's missing; re-import is a no-op. */
    suspend fun readArchive(input: InputStream): ArchiveResult =
        withContext(io) { ArchiveReader(db).read(input) }



    /** Model-major. */
    fun observeLoras(): Flow<List<LoraEntity>> = loras.observeAll()

    suspend fun lorasFor(modelId: String): List<LoraEntity> = withContext(io) { loras.byModel(modelId) }

    suspend fun loraById(id: Long): LoraEntity? = withContext(io) { loras.byId(id) }

    suspend fun attachedLora(modelId: String, kind: MaskGeometry): LoraEntity? =
        withContext(io) { loras.attachedFor(modelId, kind.name) }

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

    /** Flags every adapter fitted before nowMs; attach refuses until it is re-fitted. */
    suspend fun markLoraHistoryMutated(nowMs: Long) =
        withContext(io) { loras.markHistoryMutated(nowMs) }

    /** At most one adapter per model and kind: each run shape reads exactly one row. */
    suspend fun attachLora(id: Long, modelId: String, kind: String, nowMs: Long) = inWriteTx {
        loras.detachKind(modelId, kind, nowMs)
        loras.attach(id, nowMs)
    }

    suspend fun detachLoras(modelId: String, nowMs: Long) = withContext(io) { loras.detachAll(modelId, nowMs) }

    suspend fun deleteLora(id: Long) = withContext(io) { loras.delete(id) }

    suspend fun deleteLorasForModel(modelId: String) = withContext(io) { loras.deleteByModel(modelId) }


    /** ts order, span key = first row's ts; false if any slot already holds a PROMOTED fill. */
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

    /** Stays flagged RECONSTRUCTED (no alarms/dose/scoring); bypasses upsertReading. */
    suspend fun promoteInfillSpan(spanStartMs: Long, nowMs: Long): PromoteResult = withContext(io) {
        inWriteTx {
            val rows = infills.span(spanStartMs)
            if (rows.isEmpty()) return@inWriteTx PromoteResult.Refused("Span is gone")
            if (rows.any { it.promotedAtMs != null }) {
                return@inWriteTx PromoteResult.Refused("Already promoted")
            }
            val src = sources.authoritativeSourceId()
                ?: return@inWriteTx PromoteResult.Refused("No authoritative sensor")

            // Fails closed: no measurement behind it, a glance surface would show a model's number.
            val newestMeasured = readings.newestMeasuredTs(src)
                ?: return@inWriteTx PromoteResult.Refused("No measured reading to promote behind")
            if (rows.any { it.ts >= newestMeasured }) {
                // Forbids promoting a forecast span; it would read as the current BG.
                return@inWriteTx PromoteResult.Refused("Not in the past")
            }

            // Forbids a backcast: a one-sided reconstruction extends history on a single anchor.
            if (readings.newestMeasuredBefore(src, rows.first().ts) == null) {
                return@inWriteTx PromoteResult.Refused("Nothing measured before the span")
            }

            for (row in rows) {
                val stored = readings.byTs(src, row.ts)
                if ((stored != null && stored.provenance == ReadingProvenance.MEASURED) ||
                    sampleHoldsMeasurement(samples.byTs(row.ts))
                ) {
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
                // Nearest bracketing measurement, left-pref (§7.4); own offset, not clock's zone.
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
                    // Never received, no receive instant of its own; gets no raw-sample row either.
                    rxWallMs = row.ts,
                    rssi = null,
                )
                // Counts what was written, not considered; rule also spares an INTERPOLATED row.
                if (!supersedesGridSlot(readings.byTs(src, row.ts), entity)) continue
                readings.upsert(entity)
                written++
                val slot = samples.byTs(row.ts)
                // Else demotion would null another sensor's gap-fill.
                if (!reconstructionTakesSample(slot)) continue
                val base = slot ?: emptySample(row.ts, tz, row.ts)
                samples.upsert(
                    base.copy(
                        bgMgdl = entity.bgMgdl,
                        // Null, not source id; bgSource asserts which sensor produced it, none did.
                        bgSource = null,
                        bgProvenance = ReadingProvenance.RECONSTRUCTED,
                        bgFlag = ReadingFlag.NORMAL,
                        // Cleared with the value it belonged to; a reconstruction was not measured.
                        bgMeasuredAtMs = null,
                        tzOffsetMin = tz,
                        updatedAt = maxOf(base.updatedAt + 1, nowMs),
                    ),
                )
            }
            if (written == 0) return@inWriteTx PromoteResult.Refused("Every slot already holds a value")
            infills.markPromoted(spanStartMs, nowMs)
            PromoteResult.Promoted(written)
        }.also { if (it is PromoteResult.Promoted) _logEvents.update { t -> t + 1 } }
    }

    /** Removes only still-RECONSTRUCTED rows; resolved across every source, not just current. */
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
                }
                removed++
            }
            // removed==0 is legitimate, not a refusal; a real measurement can supersede the fill.
            infills.markPromoted(spanStartMs, null)
            PromoteResult.Promoted(removed)
        }.also { _logEvents.update { t -> t + 1 } }
    }


    suspend fun infillInRange(fromMs: Long, toMs: Long): List<BgInfillEntity> =
        withContext(io) { infills.inRange(fromMs, toMs) }

    fun observeInfill(fromMs: Long, toMs: Long): Flow<List<BgInfillEntity>> =
        infills.observeRange(fromMs, toMs)

    /** Refuses a PROMOTED span; this table holds the only copy of its band. Demote first. */
    suspend fun discardInfillSpan(spanStartMs: Long): Boolean = withContext(io) {
        infills.deleteSpanIfUnpromoted(spanStartMs) > 0
    }

    /** line: mg/dL per span row, oldest first, at tau; refuses a promoted span. */
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

    /** Returned rows are the whole of what restoreBgCut needs, captured before the delete. */
    suspend fun cutBgRange(fromMs: Long, toMs: Long, nowMs: Long): List<BgCut> = withContext(io) {
        requireGrid(fromMs)
        requireGrid(toMs)
        val cuts = ArrayList<BgCut>()
        inWriteTx {
            var ts = fromMs
            while (ts <= toMs) {
                val slotReadings = readings.allAt(ts)
                // A stored reconstruction refuses whole; keyed on the row, not the promoted band.
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
                val stamp = if (row == null) nowMs else maxOf(nowMs, row.updatedAt + 1)
                if (row != null) {
                    samples.upsert(
                        row.copy(
                            bgMgdl = null,
                            bgSource = null,
                            bgProvenance = null,
                            bgFlag = null,
                            updatedAt = stamp,
                        ),
                    )
                }
                tombstones.upsert(
                    EventTombstoneEntity(
                        clientId = bgTombstoneId(c.ts),
                        kind = TOMBSTONE_KIND_BG,
                        tsMs = c.ts,
                        tzOffsetMin = row?.tzOffsetMin ?: c.readings.firstOrNull()?.tzOffsetMin ?: 0,
                        updatedAt = stamp,
                        createdAtMs = nowMs,
                    ),
                )
            }
            // Unpromoted fills only, not invalidateForecastDerivedInTx (that drops prediction too).
            if (cuts.isNotEmpty()) infills.deleteFrom(fromMs)
        }
        if (cuts.isNotEmpty()) _logEvents.update { t -> t + 1 }
        cuts
    }

    suspend fun restoreBgCut(cuts: List<BgCut>, nowMs: Long) = withContext(io) {
        if (cuts.isEmpty()) return@withContext
        inWriteTx {
            for (c in cuts) {
                tombstones.deleteByClientId(bgTombstoneId(c.ts))
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
            }
            infills.deleteFrom(cuts.minOf { it.ts })
        }
        _logEvents.update { t -> t + 1 }
    }

    suspend fun clearInfillForModel(modelId: String) = withContext(io) { infills.deleteByModel(modelId) }

    suspend fun infillCount(): Int = withContext(io) { infills.count() }

    /** Row-only wipe, never drop/recreate; preserveCgmSources keeps the live binding on reset. */
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
            telemetry.deleteAll()
            db.savedMealDao().deleteAllItems()
            db.savedMealDao().deleteAllMeals()
            db.foodDao().deleteAllCustom()
            db.insulinTypeDao().deleteAllCustom()
            paintStrokes.deleteAll()
            conformalDeltas.deleteAll()
            loras.deleteAll()
            infills.deleteAll()
            // A deletion outlives what it deleted; left behind, it would veto a later restore.
            tombstones.deleteAll()
            exerciseFixes.deleteAll()
            exerciseSessions.deleteAll()
            loggedExercise.deleteAll()
            // kv LAST: it holds the watch nonce ceilings + pairing bits + every setting.
            kv.deleteAll()
        }
    }

    companion object {
        const val GRID_MS: Long = 300_000L

        /** 7 days, same bound as the outbox; rows are display/diagnosis, the grid is forever. */
        const val RAW_SAMPLE_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        internal fun rawSampleCutoff(nowMs: Long): Long = nowMs - RAW_SAMPLE_RETENTION_MS

        /** Inverse of snapToGrid: [gridTs-half, gridTs+half-1], half-open late (tie rule). */
        internal fun rawSampleWindowFor(gridTs: Long): LongRange =
            (gridTs - GRID_MS / 2)..(gridTs + GRID_MS / 2 - 1)

        /** stored-prior = other bouts' share, floored at 0; no ceiling (§5), NaN=0. */
        internal fun mergedExerciseGrams(storedGrams: Double?, priorGrams: Double, grams: Double): Double {
            fun sane(v: Double) = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
            val others = (sane(storedGrams ?: 0.0) - sane(priorGrams)).coerceAtLeast(0.0)
            return others + sane(grams)
        }

        private fun requireGrid(ts: Long) =
            require(ts % GRID_MS == 0L) { "timestamp not on the 5-min grid: $ts" }

        /** Round-to-nearest onto the grid (SPEC §1); public since outside callers need it too. */
        fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        /** v4 UUID — acceptable per §8.6 (v7 preferred for time-ordering, but not in the JDK). */
        private fun newClientId(): String = java.util.UUID.randomUUID().toString()

        /** No LWW guard; supersedesGridSlot decided already. updatedAt stays a max (§7). */
        internal fun projectedBgSample(base: SampleEntity, reading: CgmReading): SampleEntity =
            base.copy(
                tzOffsetMin = reading.tzOffsetMin,
                bgMgdl = reading.bgMgdl,
                // From the reading's own source; label can never name a sensor other than this one.
                bgSource = reading.sourceId.opaque,
                bgProvenance = reading.provenance,
                bgFlag = reading.flag,
                bgMeasuredAtMs = reading.measuredAtMs,
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
