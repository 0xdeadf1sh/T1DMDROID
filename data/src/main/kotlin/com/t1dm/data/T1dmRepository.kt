package com.t1dm.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.JournalNote
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmAdvertRawEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.DoseEventEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.NoteEntity
import com.t1dm.data.db.HwTelemetryEntity
import com.t1dm.data.db.KvEntity
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.PredictionEntity
import com.t1dm.data.db.SampleEntity
import com.t1dm.data.db.SavedMealEntity
import com.t1dm.data.db.SavedMealItemEntity
import com.t1dm.data.db.ServerProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The single write/read gateway over [AppDatabase] (PLAN.private.md §3.5). Every mutation runs on
 * [T1dmDispatchers.io]; contributing-event writes atomically re-project the wide `sample` row and
 * thinly enqueue an `INGEST` outbox item (drained in Phase 3). Reads surface [Flow]s.
 *
 * Invariants enforced here rather than in the schema:
 *  - grid alignment: every `sample`/`cgm_reading` timestamp satisfies `ts % GRID_MS == 0`;
 *  - grid upsert-in-place: `(sourceId, tsMs)` is the reading key, so a MEASURED value overwrites a
 *    prior INTERPOLATED one at the same slot (PLAN §3.1);
 *  - projection provenance: INVALID readings never reach `sample`; the sample carries the bg
 *    provenance/flag so downstream (alarm, graph) can honour §3.6-A.
 */
class T1dmRepository(
    private val db: AppDatabase,
    private val dispatchers: T1dmDispatchers,
) : OutboxSink {
    private val io get() = dispatchers.io

    private val sources get() = db.cgmSourceDao()
    private val readings get() = db.cgmReadingDao()
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

    fun observeActiveSource(): Flow<CgmSourceDescriptor?> =
        sources.observeActive().map { it?.toDescriptor() }

    /** Register or update a source, preserving its original `addedAtMs` across updates. */
    suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        active: Boolean,
        nowMs: Long,
    ) = withContext(io) {
        inWriteTx {
            val existing = sources.byId(descriptor.id.value)
            sources.upsert(
                CgmSourceEntity(
                    sourceId = descriptor.id.value,
                    vendorId = descriptor.vendorId,
                    displayName = descriptor.displayName,
                    serialSuffix = descriptor.serialSuffix,
                    active = active,
                    warmupWindowMin = descriptor.warmupWindowMin,
                    addedAtMs = existing?.addedAtMs ?: nowMs,
                    lastSeenMs = nowMs,
                ),
            )
            if (active) {
                sources.clearActive()
                sources.setActive(descriptor.id.value)
            }
        }
    }

    /** Enforce exactly-one-active atomically (PLAN §3.1). */
    suspend fun setActiveSource(id: CgmSourceId) = withContext(io) {
        inWriteTx {
            sources.clearActive()
            sources.setActive(id.value)
        }
    }

    suspend fun activeSourceId(): CgmSourceId? = withContext(io) {
        sources.activeSourceId()?.let(::CgmSourceId)
    }

    // ─── Readings + wide-sample projection ──────────────────────────────────────────────────

    fun observeReadings(sourceId: CgmSourceId, fromMs: Long, toMs: Long): Flow<List<CgmReading>> =
        readings.observeRange(sourceId.value, fromMs, toMs).map { list -> list.map { it.toModel() } }

    fun observeLatestReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatest(sourceId.value).map { it?.toModel() }

    /** The most recent [limit] readings for [sourceId], newest first (Phase-2 inference context). */
    suspend fun recentReadings(sourceId: CgmSourceId, limit: Int): List<CgmReading> =
        withContext(io) { readings.recent(sourceId.value, limit).map { it.toModel() } }

    /**
     * Grid-stamp upsert-in-place; if the reading is on the active source and not INVALID, project
     * its bg into `sample` (LWW on `rxWallMs`) and enqueue one INGEST item for that grid slot.
     */
    suspend fun upsertReading(reading: CgmReading) = withContext(io) {
        requireGrid(reading.tsMs)
        inWriteTx {
            readings.upsert(reading.toEntity())
            val active = sources.activeSourceId()
            if (active == reading.sourceId.value && reading.flag != ReadingFlag.INVALID) {
                projectBg(reading)
                enqueueIngest(reading.tsMs, reading.rxWallMs)
            }
        }
    }

    private suspend fun projectBg(reading: CgmReading) {
        val existing = samples.byTs(reading.tsMs)
        if (existing != null && reading.rxWallMs < existing.updatedAt) return // LWW: keep newer
        val base = existing ?: emptySample(reading.tsMs, reading.tzOffsetMin, reading.rxWallMs)
        samples.upsert(
            base.copy(
                tzOffsetMin = reading.tzOffsetMin,
                bgMgdl = reading.bgMgdl,
                bgProvenance = reading.provenance,
                bgFlag = reading.flag,
                updatedAt = maxOf(base.updatedAt, reading.rxWallMs),
            ),
        )
    }

    // ─── Samples ────────────────────────────────────────────────────────────────────────────

    fun observeSamples(fromMs: Long, toMs: Long): Flow<List<SampleEntity>> =
        samples.observeRange(fromMs, toMs)

    suspend fun sampleAt(ts: Long): SampleEntity? = withContext(io) { samples.byTs(ts) }

    /** Steps arrive already bucketed on the 5-min grid by :sensors (PLAN §3.5). */
    suspend fun recordSteps(gridTs: Long, tzOffsetMin: Int, steps: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(steps = steps) }

    suspend fun recordMood(gridTs: Long, tzOffsetMin: Int, mood: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(mood = mood) }

    /** Log a discrete dose and fold its units into the enclosing grid bucket's `sample`. */
    suspend fun logDose(dose: DoseEventEntity): Long = withContext(io) {
        inWriteTx {
            val id = doses.insert(dose)
            val gridTs = snapToGrid(dose.tsMs)
            mergeSampleInTx(gridTs, dose.tzOffsetMin, dose.updatedAt) { s ->
                when (dose.kind) {
                    DoseKind.BOLUS -> s.copy(bolusU = (s.bolusU ?: 0.0) + dose.units)
                    DoseKind.BASAL -> s.copy(basalU = (s.basalU ?: 0.0) + dose.units)
                }
            }
            id
        }
    }

    // ─── Curve-engine event stores (Room v3, PLAN §3.3) ──────────────────────────────────────

    /**
     * Log an insulin dose with its full curve params (`logged_dose`) and fold its units into the
     * enclosing grid bucket's `sample` (bolusU / basalU). This is the Phase-4 self-describing
     * successor to [logDose]; the reconstructed insulin channel reads `logged_dose`, so a caller
     * uses this OR the legacy [logDose], not both, for the same event.
     */
    suspend fun logLoggedDose(dose: LoggedDoseEntity): Long = withContext(io) {
        inWriteTx {
            val id = loggedDoses.insert(dose)
            val gridTs = snapToGrid(dose.tsMs)
            mergeSampleInTx(gridTs, dose.tzOffsetMin, dose.updatedAt) { s ->
                when (dose.kind) {
                    DoseKind.BOLUS -> s.copy(bolusU = (s.bolusU ?: 0.0) + dose.units)
                    DoseKind.BASAL -> s.copy(basalU = (s.basalU ?: 0.0) + dose.units)
                }
            }
            id
        }
    }

    /** Log a meal (`logged_meal`) and fold its grams into the enclosing grid bucket's `sample`. */
    suspend fun logMeal(meal: LoggedMealEntity): Long = withContext(io) {
        inWriteTx {
            val id = loggedMeals.insert(meal)
            val gridTs = snapToGrid(meal.tsMs)
            mergeSampleInTx(gridTs, meal.tzOffsetMin, meal.updatedAt) { s ->
                s.copy(carbsG = (s.carbsG ?: 0.0) + meal.grams)
            }
            id
        }
    }

    /** Window reads for curve/channel reconstruction (feed [com.t1dm.data.curve.RoomDoseStore]). */
    suspend fun loggedDosesInRange(fromMs: Long, toMs: Long): List<LoggedDoseEntity> =
        withContext(io) { loggedDoses.inRange(fromMs, toMs) }

    suspend fun loggedMealsInRange(fromMs: Long, toMs: Long): List<LoggedMealEntity> =
        withContext(io) { loggedMeals.inRange(fromMs, toMs) }

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

    /** Timestamp of the most recent logged insulin dose (IOB provenance, §3.6-F); null = none. */
    suspend fun latestLoggedInsulinTs(): Long? = withContext(io) { loggedDoses.latestTs() }

    // ─── Journal notes (Room v4, PLAN §Phase 4 deliverable 2) ────────────────────────────────

    /**
     * Persist a free-text journal note. Unlike a dose/meal/mood, a note is NOT projected into the
     * wide `sample` (it annotates an instant, not a 5-min bucket) — the `NOTE` outbox push is
     * enqueued by the caller (`:app`), keeping this store free of any `:sync` dependency.
     */
    suspend fun logNote(note: NoteEntity): Long = withContext(io) { db.noteDao().insert(note) }

    fun observeNotes(limit: Int = 100): Flow<List<JournalNote>> =
        db.noteDao().observeRecent(limit).map { list -> list.map { it.toJournalNote() } }

    /** The most recent non-null mood across the wide sample (journal picker "current mood"). */
    fun observeLatestMood(): Flow<Int?> = samples.observeLatestMood()

    // ─── Glycemic dictionary / saved meals / insulin types (Room v5, PLAN §Phase 4) ───────────

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

    // ─── Raw adverts (forensics/replay) ─────────────────────────────────────────────────────

    suspend fun recordRawAdvert(advert: CgmAdvertRawEntity): Long =
        withContext(io) { advertsRaw.insert(advert) }

    suspend fun pruneRawAdvertsBefore(beforeMs: Long): Int =
        withContext(io) { advertsRaw.pruneBefore(beforeMs) }

    // ─── Outbox ─────────────────────────────────────────────────────────────────────────────

    /** Thin enqueue-on-write (PLAN Phase 1); dedup is enforced by the unique `dedupKey` index. */
    override suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
    ): Long = withContext(io) { enqueueRow(kind, dedupKey, payload, nowMs) }

    fun observeOutboxDepth(): Flow<Int> = outbox.observeDepth()

    /** Oldest enqueue timestamp across the queue (null = empty); Network panel age-vs-bound read. */
    suspend fun oldestOutboxCreatedAt(): Long? = withContext(io) { outbox.oldestCreatedAt() }

    private suspend fun enqueueIngest(gridTs: Long, nowMs: Long) =
        enqueueRow(OutboxKind.INGEST, "ingest:sample:$gridTs", ByteArray(0), nowMs)

    private suspend fun enqueueRow(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
    ): Long = outbox.enqueue(
        OutboxEntity(
            kind = kind,
            dedupKey = dedupKey,
            payload = payload,
            createdAtMs = nowMs,
            attempts = 0,
            nextAttemptMs = 0,
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

    fun observeLatestPrediction(): Flow<ModelPrediction?> =
        predictions.observeLatest().map { it?.toModel() }

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
     * Fold a server-originated wide row into `sample` under last-writer-wins (§3.5). Returns `true`
     * iff a write occurred (the incoming row was strictly newer). Runs in a transaction so the
     * read-modify-write cannot race a concurrent local projection.
     */
    suspend fun mergeServerSample(patch: SamplePatch): Boolean = withContext(io) {
        requireGrid(patch.ts)
        inWriteTx {
            val merged = LwwMerge.merge(samples.byTs(patch.ts), patch) ?: return@inWriteTx false
            samples.upsert(merged)
            true
        }
    }

    // ─── kv / hw_telemetry ──────────────────────────────────────────────────────────────────

    suspend fun putKv(key: String, value: String, nowMs: Long) =
        withContext(io) { kv.put(KvEntity(key, value, nowMs)) }

    suspend fun getKv(key: String): String? = withContext(io) { kv.get(key) }

    fun observeKv(key: String): Flow<String?> = kv.observe(key)

    suspend fun recordTelemetry(row: HwTelemetryEntity): Long =
        withContext(io) { telemetry.insert(row) }

    companion object {
        const val GRID_MS: Long = 300_000L

        private fun requireGrid(ts: Long) =
            require(ts % GRID_MS == 0L) { "timestamp not on the 5-min grid: $ts" }

        private fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        private fun emptySample(ts: Long, tzOffsetMin: Int, updatedAt: Long) = SampleEntity(
            ts = ts,
            tzOffsetMin = tzOffsetMin,
            bgMgdl = null,
            bgProvenance = null,
            bgFlag = null,
            carbsG = null,
            bolusU = null,
            basalU = null,
            steps = null,
            mood = null,
            hr = null,
            sleep = null,
            exercise = null,
            updatedAt = updatedAt,
        )
    }
}
