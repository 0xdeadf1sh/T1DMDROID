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
import com.t1dm.data.db.LoggedExerciseEntity
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.EventTombstoneEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.TOMBSTONE_KIND_DOSE
import com.t1dm.data.db.TOMBSTONE_KIND_EXERCISE
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
    /** Wall clock for an outbox row's createdAtMs; every other timestamp comes from the caller. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OutboxSink {
    private val io get() = dispatchers.io

    /** Bumped on logged meal/dose writes, which skip sample and observeSampleWrites. */
    private val _logEvents = MutableStateFlow(0L)
    val logEvents: StateFlow<Long> = _logEvents.asStateFlow()

    /** Set by :app from persisted config; volatile not kv, checked on the CGM hot path. */
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
    private val profiles get() = db.serverProfileDao()
    private val paintStrokes get() = db.paintStrokeDao()
    private val conformalDeltas get() = db.conformalDeltaDao()
    private val loras get() = db.loraDao()
    private val infills get() = db.bgInfillDao()
    private val exerciseSessions get() = db.exerciseSessionDao()
    private val tombstones get() = db.eventTombstoneDao()
    private val exerciseFixes get() = db.exerciseFixDao()

    /** Room KTX withTransaction throws with a SQLiteDriver; uses the writer connection directly. */
    private suspend fun <R> inWriteTx(body: suspend () -> R): R =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { body() }
        }

    fun observeSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeAll().map { list -> list.map { it.toDescriptor() } }

    /** Deduplicated: a lastSeenMs touch re-runs the query but yields an equal descriptor. */
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
                    // Minted once, never revised; archive restore numbers its own rows.
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

    /** Last line of defence: numbers any row still carrying the unassigned sentinel, list order. */
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

    /** Deduplicated: `cgm_reading` is written more often than its newest row changes. */
    fun observeLatestReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatest(sourceId.value).map { it?.toModel() }.distinctUntilChanged()

    /** Separate from observeLatestReading so a promoted reconstruction never reads as glucose. */
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


    /** Raw sub-grid row first, unconditionally; skip on lost contest keeps sample.bgMgdl right. */
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
                // A real measurement stales a fill; PROMOTED spared, demotion is deliberate.
                if (isRealMeasurement(reading.provenance, reading.flag)) {
                    val fill = infills.at(reading.tsMs)
                    if (fill != null && fill.promotedAtMs == null) infills.deleteAt(reading.tsMs)
                }
                projectBg(reading)
                // WRITE instant not the event's: sensor-recovered would else be age-evicted.
                val queuedAt = nowMs()
                enqueueIngest(reading.tsMs, queuedAt)
                // Not in enqueueIngest: steps/mood would re-queue unchanged BG, no idempotency key.
                if (nightscoutBridgeEnabled) {
                    enqueueRow(
                        OutboxKind.NIGHTSCOUT,
                        "$NS_ENTRY_DEDUP_PREFIX${reading.tsMs}",
                        ByteArray(0),
                        queuedAt,
                        // Held until slot closes; coalesces while QUEUED, else date re-uploads.
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

    /** Change signal, not a value: newest grid ts per sample write; deliberately not deduped. */
    fun observeSampleWrites(): Flow<Long?> = samples.observeMaxTs()

    suspend fun sampleAt(ts: Long): SampleEntity? = withContext(io) { samples.byTs(ts) }

    /** Tenths mg/dL/min; authoritative not active, so the arrow and the number share one source. */
    suspend fun authoritativeTrendAt(ts: Long): Int? = withContext(io) {
        val sourceId = sources.authoritativeSourceId() ?: return@withContext null
        readings.byTs(sourceId, ts)?.trendTenthsPerMin
    }

    /** Bounded, not MAX(ts): recordExerciseCurve writes ahead, past-now cursor finds nothing. */
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

    /** buckets carry grams of carb equivalent per 5-min bucket (SPEC §3,§5), not seconds. */
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

    /** Snaps tsMs to grid (§4-#1), mints blank clientId (§3.2), stamps loggedAtMs; returns row. */
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

    /** clientId/loggedAtMs preserved (server upserts on the first); updatedAt forced newer. */
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

    /** Dose twin of editLoggedMeal; mutatedActingUntilMs records PRE-edit end, once only. */
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

    /** updatedAt forced strictly newer than the retired row (SPEC §7), never from nowMs. */
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

    /** Dose twin of tombstoneLoggedMeal; records the action end since no row remains to read it. */
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

    /** Ordered on updatedAt; pushEnqueuedAtMs marks a remotely-originated delete. */
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
            // No exercise event exists on the wire, so the server can never have authored one.
            require(kind != CurveKind.EXERCISE) { "the server authors no exercise tombstone" }
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

    /** Hydration is insertIgnore, drops an edit on an existing row; columns minted here. */
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

    /** Sole owner of what a channel mutation invalidates; a note/tz correction must not. */
    private suspend fun invalidateForecastDerivedInTx(affectedFromMs: Long) {
        predictions.deleteFrom(affectedFromMs)
        infills.deleteFrom(affectedFromMs)
        // Only where the fit window reaches the change; losing it TIGHTENS the band silently.
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

    /** Newest first, entities not a domain type; dedupKey format is :sync's, not re-spelled. */
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

    /** MAX(MIN(tsMs,loggedAtMs)) not MAX(tsMs): retimed forward must not quiet log-gap rail. */
    suspend fun latestLoggedInsulinTs(): Long? = withContext(io) { loggedDoses.latestLoggedMarkTs() }

    /** Union of both stores: logged_dose answers EDITED, tombstone answers DELETED. */
    suspend fun editedDoseActiveUntilMs(): Long? = withContext(io) {
        val edited = loggedDoses.editedDoseActiveUntilMs()
        val deleted = tombstones.latestActingUntilMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    /** Unioned like editedDoseActiveUntilMs: without it a delete-raised block never clears. */
    suspend fun latestDoseMutationMs(): Long? = withContext(io) {
        val edited = loggedDoses.latestMutationMs()
        val deleted = tombstones.latestCreatedAtMs(TOMBSTONE_KIND_DOSE)
        when {
            edited == null -> deleted
            deleted == null -> edited
            else -> maxOf(edited, deleted)
        }
    }

    /** Catch-up's event high-water mark; tombstone stops a delete pulling the event back in. */
    suspend fun newestEventTs(): Long? = withContext(io) {
        listOfNotNull(
            loggedMeals.latestTs(),
            loggedDoses.latestTs(),
            tombstones.latestTs(listOf(TOMBSTONE_KIND_MEAL, TOMBSTONE_KIND_DOSE)),
        ).maxOrNull()
    }

    fun observeLatestMood(): Flow<Int?> = samples.observeLatestMood()

    /** Oldest-authored first — the order they must be painted in. Intersecting, not contained. */
    fun observePaintStrokes(fromMs: Long, toMs: Long): Flow<List<PaintStroke>> =
        paintStrokes.observeOverlapping(fromMs, toMs)
            .map { list -> list.map { it.toModel() } }
            // `toModel` decodes geometry per row and the collectors are `collectAsState`.
            .flowOn(io)

    /** Zero-point stroke refused: no time bounds to index by, could never be selected back out. */
    suspend fun addPaintStroke(stroke: PaintStroke): Long = withContext(io) {
        require(!stroke.isEmpty) { "a paint stroke must carry at least one point" }
        paintStrokes.insert(stroke.toEntity())
    }

    suspend fun deletePaintStroke(id: Long) = withContext(io) { paintStrokes.delete(id) }

    suspend fun deleteAllPaintStrokes() = withContext(io) { paintStrokes.deleteAll() }

    // Phone-local, all three tables; only the disposal curve recordExerciseCurve lays into sample.

    /** Row and curve in ONE transaction, else unrecoverable by inspection; buckets priorGrams=0. */
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

    /** unwind takes old curve out, write lays new in, separate lists so an overlap keeps both. */
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

    /** unwind carries this row's grams as priorGrams; tombstone stops a restore losing them. */
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
                        pushEnqueuedAtMs = nowMs,
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

    fun observeRecentLoggedExercise(limit: Int): Flow<List<LoggedExerciseEntity>> =
        loggedExercise.observeRecent(limit)

    /** Returns the PERSISTED row; startMs is NOT grid-snapped, only the per-bucket write is. */
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

    /** One transaction: no foreign key, so a half-applied delete orphans fixes unnoticed. */
    suspend fun deleteExerciseSession(
        id: Long,
        unwind: List<ExerciseCurveBucket>,
        nowMs: Long,
    ) = withContext(io) {
        inWriteTx {
            // Takes the bout's grams back out, else disposal persists; priorGrams is its own share.
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

    /** Returns false, writes nothing, if gone or a seed row; @Upsert not gated on custom. */
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

    /** Items replaced wholesale under id; false/no-write if header gone (orphans unreachable). */
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
        // tzOffsetMin seeds a NEW row only; §2 fixes tz_offset to the authoring offset, not today.
        val base = samples.byTs(gridTs) ?: emptySample(gridTs, tzOffsetMin, nowMs)
        samples.upsert(edit(base).copy(updatedAt = maxOf(base.updatedAt, nowMs)))
        enqueueIngest(gridTs, nowMs)
    }

    /** Oldest first, including samples that lost the slot; empty means a gap-filled slot. */
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

    /** Returns how many went; AGE bound only, no size bound, samples arrive at sensor cadence. */
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

    /** Returns -1 if the key is claimed; only PENDING is swept, delete+insert share a tx. */
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

    /** Third party has no tombstone; an undelivered mirror lands there after a local delete. */
    private suspend fun withdrawBridgedTreatment(clientId: String) =
        outbox.deleteByDedupKey("$NS_TREATMENT_DEDUP_PREFIX$clientId")

    /** Whether the mirror was there to take; PENDING only, an INFLIGHT delete won't unsend it. */
    suspend fun withdrawEditedBridgedTreatment(clientId: String): Boolean = withContext(io) {
        outbox.deleteByDedupKeyInState("$NS_TREATMENT_DEDUP_PREFIX$clientId", OutboxState.PENDING) > 0
    }

    /** Deduplicated: the queue writes far more often than DEPTH moves (claim/backoff/attempt). */
    fun observeOutboxDepth(): Flow<Int> = outbox.observeDepth().distinctUntilChanged()

    /** Null when empty. */
    suspend fun oldestOutboxCreatedAt(): Long? = withContext(io) { outbox.oldestCreatedAt() }

    /** Server-bound rows only: a stuck bridge row must not falsify the re-mirror delivery proof. */
    suspend fun oldestServerBoundOutboxCreatedAt(): Long? =
        withContext(io) { outbox.oldestCreatedAtExcluding(OutboxKind.NIGHTSCOUT) }

    /** DISPLACES whatever is queued; plain enqueue would drop a demotion under an INFLIGHT row. */
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
            // Not a backoff: attempts stays 0, createdAtMs untouched, FIFO/age stay from the write.
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

    /** Rows as written, never re-forecast; scoped to authoritative source, pre-v25 refused too. */
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

    /** MATURED windows for the on-device metric suite; lastBg anchors persistence (SPEC §6.3). */
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

        // Sorted for binary-search, back one tolerance; scoped to AUTHORITATIVE source only.
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

        // Forecast side of the same scoping; null sourceId (pre-v25) is refused, not assumed.
        val ofModel = predictions.range(sinceMs, nowMs - horizonMs).map { it.toModel() }
            .filter { it.modelId == modelId && it.status == ForecastStatus.OK }
        val rows = ofModel.filter { it.sourceId == authoritative }
        // Counted not dropped: an unexplained empty panel after a sensor change reads as a bug.
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

    /** No-server-over-local gap-fill (§3.3): fills only fields the local row lacks. */
    suspend fun mergeServerSample(patch: SamplePatch): Boolean = withContext(io) {
        requireGrid(patch.ts)
        inWriteTx {
            // Gap-fill only, no ingest (echo-loop); have-it test spans the whole MODEL CLASS.
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

    /** One-shot gap-fill of authoritative cgm_reading from sample; inserts only missing slots. */
    suspend fun reconcileReadingsFromSamples(): Int = withContext(io) {
        val authoritative = sources.authoritativeSourceId() ?: return@withContext 0
        // Slots NO sensor holds a reading for; a narrower test lets a change re-import a record.
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

    /** Id-keyed hydration on REST catch-up (§3.4); idempotent on clientId, never enqueues. */
    suspend fun hydrateMealEvent(ev: LoggedMealEntity): Long = withContext(io) {
        if (tombstoned(ev.clientId, ev.updatedAt)) -1L else loggedMeals.insertIgnore(ev)
    }

    suspend fun hydrateDoseEvent(ev: LoggedDoseEntity): Long = withContext(io) {
        if (tombstoned(ev.clientId, ev.updatedAt)) -1L else loggedDoses.insertIgnore(ev)
    }

    /** One bounded page of the §3.8 re-mirror; newest ts enqueued, or null when done. */
    suspend fun reMirrorScalarsBatch(afterTs: Long, limit: Int, nowMs: Long): Long? = withContext(io) {
        val page = samples.page(afterTs, limit)
        if (page.isEmpty()) return@withContext null
        inWriteTx { for (s in page) enqueueIngest(s.ts, nowMs) }
        page.last().ts
    }

    suspend fun putKv(key: String, value: String, nowMs: Long) =
        withContext(io) { kv.put(KvEntity(key, value, nowMs)) }

    suspend fun getKv(key: String): String? = withContext(io) { kv.get(key) }

    /** Deduplicated: Room invalidates per TABLE, so heartbeat/telemetry re-run every kv query. */
    fun observeKv(key: String): Flow<String?> = kv.observe(key).distinctUntilChanged()

    suspend fun allKv(): Map<String, String> =
        withContext(io) { kv.all().associate { it.key to it.value } }

    suspend fun putKvBatch(pairs: Map<String, String>, nowMs: Long) =
        withContext(io) { kv.putAll(pairs.map { (k, v) -> KvEntity(k, v, nowMs) }) }

    /** SPEC/inference.md §8.4; sufficient fit only, a refusal keeps the stored correction as-is. */
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

    /** A row whose blob disagrees with steps*nQuantiles is dropped, not reshaped; draws raw fan. */
    fun observeBandCalibrations(): Flow<Map<String, BandCalibration>> =
        conformalDeltas.observeAll()
            .map { rows -> rows.mapNotNull { it.toModel() }.associateBy { it.modelId } }
            .distinctUntilChanged()
            .flowOn(io)

    suspend fun deleteBandCalibration(modelId: String) =
        withContext(io) { conformalDeltas.deleteByModel(modelId) }

    /** For when the model IS changes under a fixed id; keeping either misreports calibration. */
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

    /** Local row always wins, only adds what's missing; throws on a non-archive stream. */
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

    /** Adapter fit on since-rewritten windows describes a stale record; refuses until re-fit. */
    suspend fun markLoraHistoryMutated(nowMs: Long) =
        withContext(io) { loras.markHistoryMutated(nowMs) }

    suspend fun attachLora(id: Long, modelId: String, nowMs: Long) = inWriteTx {
        loras.detachAll(modelId, nowMs)
        loras.attach(id, nowMs)
    }

    suspend fun detachLoras(modelId: String, nowMs: Long) = withContext(io) { loras.detachAll(modelId, nowMs) }

    suspend fun deleteLora(id: Long) = withContext(io) { loras.delete(id) }

    suspend fun deleteLorasForModel(modelId: String) = withContext(io) { loras.deleteByModel(modelId) }

    /** Never a reading (BgInfillEntity); false/no-write if a slot already holds a PROMOTED fill. */
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

    /** Turns model output into history, flagged RECONSTRUCTED; never clears alarm or feeds dose. */
    suspend fun promoteInfillSpan(spanStartMs: Long, nowMs: Long): PromoteResult = withContext(io) {
        inWriteTx {
            val rows = infills.span(spanStartMs)
            if (rows.isEmpty()) return@inWriteTx PromoteResult.Refused("Span is gone")
            if (rows.any { it.promotedAtMs != null }) {
                return@inWriteTx PromoteResult.Refused("Already promoted")
            }
            val src = sources.authoritativeSourceId()
                ?: return@inWriteTx PromoteResult.Refused("No authoritative sensor")

            // Fails closed: with nothing behind it, glance surfaces show a model's number.
            val newestMeasured = readings.newestMeasuredTs(src)
                ?: return@inWriteTx PromoteResult.Refused("No measured reading to promote behind")
            if (rows.any { it.ts >= newestMeasured }) {
                // Forbids promoting a FORECAST span: it would read as the current BG.
                return@inWriteTx PromoteResult.Refused("Not in the past")
            }

            // Forbids a BACKCAST: extends history backwards on one anchor. Drawing is fine.
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
                // Row's OWN offset, not the clock's zone (SPEC §2): a gap can span a DST change.
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
                    // Never received, no receive instant: the slot is the only honest answer.
                    rxWallMs = row.ts,
                    rssi = null,
                )
                // Grid-slot rule declines a valued INTERPOLATED row; count is what was WRITTEN.
                if (!supersedesGridSlot(readings.byTs(src, row.ts), entity)) continue
                readings.upsert(entity)
                written++
                val base = samples.byTs(row.ts) ?: emptySample(row.ts, tz, row.ts)
                samples.upsert(
                    base.copy(
                        bgMgdl = entity.bgMgdl,
                        // Null not source id: bgSource asserts which SENSOR produced it; none did.
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

    /** Only RECONSTRUCTED rows go; resolved across EVERY source, not just current authority. */
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
            // removed==0 is legitimate, not a refusal, else the span strands; cutBgRange closes it.
            infills.markPromoted(spanStartMs, null)
            PromoteResult.Promoted(removed)
        }.also { _logEvents.update { t -> t + 1 } }
    }


    suspend fun infillInRange(fromMs: Long, toMs: Long): List<BgInfillEntity> =
        withContext(io) { infills.inRange(fromMs, toMs) }

    fun observeInfill(fromMs: Long, toMs: Long): Flow<List<BgInfillEntity>> =
        infills.observeRange(fromMs, toMs)

    /** Refuses a PROMOTED span in the statement; this table holds the only copy of its band. */
    suspend fun discardInfillSpan(spanStartMs: Long): Boolean = withContext(io) {
        infills.deleteSpanIfUnpromoted(spanStartMs) > 0
    }

    /** line: one mg/dL per span row, oldest first, off the stored fan at tau; refuses promoted. */
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

    /** Returned rows are all restoreBgCut needs, captured before delete; nothing else holds it. */
    suspend fun cutBgRange(fromMs: Long, toMs: Long, nowMs: Long): List<BgCut> = withContext(io) {
        requireGrid(fromMs)
        requireGrid(toMs)
        val cuts = ArrayList<BgCut>()
        inWriteTx {
            var ts = fromMs
            while (ts <= toMs) {
                val slotReadings = readings.allAt(ts)
                // Reconstruction refused whole, else bg_infill's only band copy becomes deletable.
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
            // Unpromoted fills only, not invalidateForecastDerivedInTx: drops what sweep replays.
            if (cuts.isNotEmpty()) infills.deleteFrom(fromMs)
        }
        if (cuts.isNotEmpty()) _logEvents.update { t -> t + 1 }
        cuts
    }

    /** Re-push not optional: a cut sends a clear; without it the value survives on the phone. */
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

    /** Row-only wipe to first-run; cgm_sensor_secret stays, only deleteSensorSecret clears it. */
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
            // A deletion outlives what it deleted, else it re-pushes and blocks re-hydration.
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

        /** Seven days, same age bound as outbox (sync/Backoff.kt); grid series is keep-forever. */
        const val RAW_SAMPLE_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        internal fun rawSampleCutoff(nowMs: Long): Long = nowMs - RAW_SAMPLE_RETENTION_MS

        /** Inverse of snapToGrid: [gridTs-GRID_MS/2, gridTs+GRID_MS/2-1], late side half-open. */
        internal fun rawSampleWindowFor(gridTs: Long): LongRange =
            (gridTs - GRID_MS / 2)..(gridTs + GRID_MS / 2 - 1)

        /** stored-prior is every OTHER bout's share, kept, floored at zero; no ceiling (§5 sum). */
        internal fun mergedExerciseGrams(storedGrams: Double?, priorGrams: Double, grams: Double): Double {
            fun sane(v: Double) = if (v.isFinite()) v.coerceAtLeast(0.0) else 0.0
            val others = (sane(storedGrams ?: 0.0) - sane(priorGrams)).coerceAtLeast(0.0)
            return others + sane(grams)
        }

        private fun requireGrid(ts: Long) =
            require(ts % GRID_MS == 0L) { "timestamp not on the 5-min grid: $ts" }

        /** Round-to-nearest (SPEC §1): floor/round validate but file into different buckets. */
        fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        /** §3.2: v4 not v7 (§8.6) — v7 orders by time but isn't in the JDK. */
        private fun newClientId(): String = java.util.UUID.randomUUID().toString()

        /** No LWW guard: supersedesGridSlot already decided; updatedAt stays a MAXIMUM (§7 key). */
        internal fun projectedBgSample(base: SampleEntity, reading: CgmReading): SampleEntity =
            base.copy(
                tzOffsetMin = reading.tzOffsetMin,
                bgMgdl = reading.bgMgdl,
                // Stamped from the reading, not looked up; only authoritative source reaches here.
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
