package com.t1dm.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.JournalNote
import com.t1dm.core.model.AccuracyPair
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.RecentMeal
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmAdvertRawEntity
import com.t1dm.data.db.CgmReadingEntity
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
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.SavedMealItemEntity
import com.t1dm.data.db.ServerProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The single write/read gateway over [AppDatabase] (SPEC.private.md §3.5). Every mutation runs on
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

    /** Enforce exactly-one-active atomically (SPEC §3.1). */
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

    /** Newest grid ts in the wide projection, or null when empty (the WS-connect catch-up cursor). */
    suspend fun newestSampleTs(): Long? = withContext(io) { samples.maxTs() }

    /** Windowed wide-sample read for the stats recompute (Phase 6); oldest-first. */
    suspend fun samplesInRange(fromMs: Long, toMs: Long): List<SampleEntity> =
        withContext(io) { samples.rangeList(fromMs, toMs) }

    /** Steps arrive already bucketed on the 5-min grid by :sensors (SPEC §3.5). */
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

    // ─── Curve-engine event stores (Room v3, SPEC §3.3) ──────────────────────────────────────

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

    /** Timestamp of the most recent logged insulin dose (IOB provenance, §3.6-F); null = none. */
    suspend fun latestLoggedInsulinTs(): Long? = withContext(io) { loggedDoses.latestTs() }

    // ─── Journal notes (Room v4, Phase 4 deliverable 2) ────────────────────────────────

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

    /** Thin enqueue-on-write (Phase 1); dedup is enforced by the unique `dedupKey` index. */
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

    /** Purge every stored forecast of a removed model (Phase 7C model deletion). */
    suspend fun deletePredictionsForModel(modelId: String) = withContext(io) { predictions.deleteByModel(modelId) }

    fun observeLatestPrediction(): Flow<ModelPrediction?> =
        predictions.observeLatest().map { it?.toModel() }

    /**
     * Pair every MATURED forecast of [modelId] with the realized MEASURED BG at each of [horizonsMin]
     * minutes past the forecast's `madeAt`, for the on-device accuracy aggregator (Phase 7C, Models
     * drill-down). Walks the dedicated `prediction` table over `[sinceMs, nowMs]`, and for each finite
     * (non-degenerate) row emits an [AccuracyPair] per horizon whose target time `madeAt + h` is (a)
     * already in the past (matured) and (b) matched by a MEASURED/NORMAL reading within [toleranceMs]
     * — the nearest such reading. `predicted` is the median line at the horizon step; `band_lo/hi` are
     * the τ.05 / τ.95 fan edges there (for central-90 coverage). No match ⇒ that (row, horizon) is
     * silently skipped, so a horizon simply accrues fewer pairs (surfaced as "insufficient history").
     */
    suspend fun forecastAccuracyPairs(
        modelId: String,
        horizonsMin: List<Int>,
        sinceMs: Long,
        nowMs: Long,
        toleranceMs: Long = 150_000L, // half a 5-min grid step
    ): List<AccuracyPair> = withContext(io) {
        val maxHorizonMs = (horizonsMin.maxOrNull() ?: 0).toLong() * 60_000L
        // Realized truth: MEASURED, in-range-flagged readings from sinceMs out to the last maturable
        // target. Sorted ascending for a binary-search nearest match.
        val truth = readings.readingsInRange(sinceMs, nowMs + maxHorizonMs)
            .asSequence()
            .filter { it.bgMgdl != null && it.provenance == ReadingProvenance.MEASURED && it.flag == ReadingFlag.NORMAL }
            .map { it.tsMs to it.bgMgdl!! }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()
        if (truth.isEmpty()) return@withContext emptyList()
        val truthTs = LongArray(truth.size) { truth[it].first }

        val rows = predictions.range(sinceMs, nowMs).map { it.toModel() }
            .filter { it.modelId == modelId && it.status == ForecastStatus.OK }
        val out = ArrayList<AccuracyPair>()
        for (p in rows) {
            val h = p.medianBg.size
            val nq = p.nQuantiles
            if (h == 0 || nq <= 0 || p.stepMs <= 0) continue
            for (hMin in horizonsMin) {
                val stepIdx = ((hMin.toLong() * 60_000L) / p.stepMs).toInt() - 1
                if (stepIdx < 0 || stepIdx >= h) continue
                val targetTs = p.cycleTsMs + hMin.toLong() * 60_000L
                if (targetTs > nowMs) continue // not yet matured
                val realized = nearestWithin(truthTs, truth, targetTs, toleranceMs) ?: continue
                val predicted = p.medianBg[stepIdx]
                if (!predicted.isFinite()) continue
                val lo = p.bandsMgdl.getOrNull(stepIdx * nq)
                val hi = p.bandsMgdl.getOrNull(stepIdx * nq + (nq - 1))
                val hasBand = lo != null && hi != null && lo.isFinite() && hi.isFinite()
                out += AccuracyPair(
                    horizonMin = hMin,
                    predicted = predicted,
                    realized = realized.toDouble(),
                    bandLo = lo ?: 0.0,
                    bandHi = hi ?: 0.0,
                    hasBand = hasBand,
                )
            }
        }
        out
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
     * Fold a server-originated wide row into `sample` under last-writer-wins (§3.5). Returns `true`
     * iff a write occurred (the incoming row was strictly newer). Runs in a transaction so the
     * read-modify-write cannot race a concurrent local projection.
     */
    suspend fun mergeServerSample(patch: SamplePatch): Boolean = withContext(io) {
        requireGrid(patch.ts)
        inWriteTx {
            // Hydrate the authoritative cgm_reading store (active source) so server-synced history
            // reaches BOTH the graph (observeReadings) and the model (recentBgSeries) — sample is a
            // dead-end for display/inference (SPEC §3.5). GAP-FILL ONLY: never overwrite a local
            // reading (its provenance/flag is authoritative), and enqueue NO ingest (this data
            // originated from the phone; re-pushing would echo-loop). The §3.6 alarm/loss-of-signal
            // path is readingBus-driven (live BLE), never the DB, so a DB write cannot perturb it.
            val active = sources.activeSourceId()
            if (active != null && patch.bgMgdl != null && readings.byTs(active, patch.ts) == null) {
                readings.upsert(
                    CgmReadingEntity(
                        sourceId = active,
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
            // Hydrate the carb/insulin EVENT tables too (logged_meal / logged_dose) so server-synced
            // carbs & boluses reach the graph overlay AND the model's dose channels — ChannelBuilder
            // reads the event tables, never `sample`. This runs for BOTH catch-up rows and LIVE WS
            // samples (the single chokepoint), so live synthetic data no longer stops at `sample`.
            // GAP-FILL: skip a slot already carrying a local meal/bolus. Carbs → medium-GI gamma; a bolus
            // is stored BARE (no gamma params) so RoomDoseStore reconstructs it via the exp-action model
            // (no simulator gamma). Raw inserts: no re-projection into `sample`, no outbox push.
            val carbs = patch.carbsG
            if (carbs != null && carbs > 0.0 && loggedMeals.coveringCount(patch.ts) == 0) {
                loggedMeals.insert(syncedMeal(patch.ts, carbs, patch.tzOffsetMin, patch.updatedAt))
            }
            val bolus = patch.bolusU
            if (bolus != null && bolus > 0.0 && loggedDoses.coveringCount(patch.ts, DoseKind.BOLUS) == 0) {
                loggedDoses.insert(syncedBolus(patch.ts, bolus, patch.tzOffsetMin, patch.updatedAt))
            }
            val basal = patch.basalU
            if (basal != null && basal > 0.0 && loggedDoses.coveringCount(patch.ts, DoseKind.BASAL) == 0) {
                loggedDoses.insert(syncedBasal(patch.ts, basal, patch.tzOffsetMin, patch.updatedAt))
            }
            val merged = LwwMerge.merge(samples.byTs(patch.ts), patch) ?: return@inWriteTx false
            samples.upsert(merged)
            true
        }
    }

    /** A server carb bucket taken AS a single-bucket point of the appearance CURVE — a one-element
     *  `customCurve` so ChannelBuilder places exactly [value] at [ts]. The union of a meal's buckets
     *  reproduces the pushed curve verbatim (no re-smearing), closing the app→server→app round-trip. */
    private fun syncedMeal(ts: Long, value: Double, tzOffsetMin: Int, updatedAt: Long): LoggedMealEntity =
        LoggedMealEntity(
            tsMs = ts, grams = value, gi = null, k = null, theta = null, durationMin = GRID_MS / 60_000.0,
            customCurve = listOf(value).toBlob(), tzOffsetMin = tzOffsetMin, note = "synced", updatedAt = updatedAt,
        )

    /** A server bolus bucket taken AS a single-bucket point of the action CURVE (see [syncedMeal]). */
    private fun syncedBolus(ts: Long, value: Double, tzOffsetMin: Int, updatedAt: Long): LoggedDoseEntity =
        syncedDose(ts, DoseKind.BOLUS, value, tzOffsetMin, updatedAt)

    /** A server basal bucket taken AS a single-bucket point of the action CURVE (see [syncedMeal]). */
    private fun syncedBasal(ts: Long, value: Double, tzOffsetMin: Int, updatedAt: Long): LoggedDoseEntity =
        syncedDose(ts, DoseKind.BASAL, value, tzOffsetMin, updatedAt)

    private fun syncedDose(ts: Long, kind: DoseKind, value: Double, tzOffsetMin: Int, updatedAt: Long): LoggedDoseEntity =
        LoggedDoseEntity(
            tsMs = ts, kind = kind, units = value, durationMin = GRID_MS / 60_000.0,
            k = null, theta = null, kaPerHour = null, kePerHour = null, customCurve = listOf(value).toBlob(),
            tzOffsetMin = tzOffsetMin, note = "synced", updatedAt = updatedAt,
        )

    /**
     * One-shot reconcile that gap-fills the active source's `cgm_reading` from the wide `sample`
     * projection — the migration path for server history synced into `sample` BEFORE the reading
     * hydration existed (and a belt-and-braces self-heal thereafter). Inserts only slots the source
     * lacks a reading for (never clobbers a live reading's provenance/flag); the §3.6 alarm path is
     * live-BLE-driven, not the DB, so this is inert to it. Returns the number of rows inserted; 0 when
     * there is no active source or nothing is missing. Off-main; one transaction.
     */
    suspend fun reconcileReadingsFromSamples(): Int = withContext(io) {
        val active = sources.activeSourceId() ?: return@withContext 0
        inWriteTx {
            val have = readings.tsForSource(active).toHashSet()
            val fill = samples.rangeList(Long.MIN_VALUE, Long.MAX_VALUE).asSequence()
                .filter { it.bgMgdl != null && it.ts !in have }
                .map { s ->
                    CgmReadingEntity(
                        sourceId = active,
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
     * The carb analog of [reconcileReadingsFromSamples]: gap-fill `logged_meal` from the wide `sample`
     * projection so server-synced carb history reaches the graph overlay AND the model's carb channel
     * (both read the event table, never `sample`). Each server carb/bolus bucket is taken AS a single-
     * bucket point of the CURVE (`customCurve = [value]`), so the union of a dose's buckets reproduces the
     * pushed curve verbatim — no re-smearing — and the app→server→app round-trip is exact (a reset→resync
     * rebuilds the same curve). GAP-FILL by curve COVERAGE (a bucket already SPANNED by a local event's
     * curve is skipped, so the app's own pushed future buckets never double its local event); RAW inserts
     * (no re-projection into `sample`, no outbox enqueue). Basal excluded (auto-extended schedule). This is
     * the backlog path; live/catch-up rows hydrate inline in [mergeServerSample]. Off-main; one transaction.
     */
    suspend fun reconcileDoseEventsFromSamples(): Int = withContext(io) {
        inWriteTx {
            val rows = samples.rangeList(Long.MIN_VALUE, Long.MAX_VALUE)
            val meals = ArrayList<LoggedMealEntity>()
            val doses = ArrayList<LoggedDoseEntity>()
            for (s in rows) {
                val g = s.carbsG
                if (g != null && g > 0.0 && loggedMeals.coveringCount(s.ts) == 0) {
                    meals += syncedMeal(s.ts, g, s.tzOffsetMin, s.updatedAt)
                }
                val u = s.bolusU
                if (u != null && u > 0.0 && loggedDoses.coveringCount(s.ts, DoseKind.BOLUS) == 0) {
                    doses += syncedBolus(s.ts, u, s.tzOffsetMin, s.updatedAt)
                }
                val b = s.basalU
                if (b != null && b > 0.0 && loggedDoses.coveringCount(s.ts, DoseKind.BASAL) == 0) {
                    doses += syncedBasal(s.ts, b, s.tzOffsetMin, s.updatedAt)
                }
            }
            if (meals.isNotEmpty()) loggedMeals.insertAll(meals)
            if (doses.isNotEmpty()) loggedDoses.insertAll(doses)
            meals.size + doses.size
        }
    }

    // ─── kv / hw_telemetry ──────────────────────────────────────────────────────────────────

    suspend fun putKv(key: String, value: String, nowMs: Long) =
        withContext(io) { kv.put(KvEntity(key, value, nowMs)) }

    suspend fun getKv(key: String): String? = withContext(io) { kv.get(key) }

    fun observeKv(key: String): Flow<String?> = kv.observe(key)

    /** Every kv key→value pair (Phase 7C item 17 — config export). Off-main. */
    suspend fun allKv(): Map<String, String> =
        withContext(io) { kv.all().associate { it.key to it.value } }

    /** Bulk-write kv pairs in one upsert (config import). Off-main. */
    suspend fun putKvBatch(pairs: Map<String, String>, nowMs: Long) =
        withContext(io) { kv.putAll(pairs.map { (k, v) -> KvEntity(k, v, nowMs) }) }

    suspend fun recordTelemetry(row: HwTelemetryEntity): Long =
        withContext(io) { telemetry.insert(row) }

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
     * Keystore-wrapped server token) are burned by the caller. Off-main.
     */
    suspend fun wipeAllData() = withContext(io) {
        inWriteTx {
            readings.deleteAll()
            samples.deleteAll()
            sources.deleteAll()
            doses.deleteAll()
            loggedDoses.deleteAll()
            loggedMeals.deleteAll()
            basalSchedules.deleteAll()
            advertsRaw.deleteAll()
            outbox.deleteAllRows()
            predictions.deleteAll()
            profiles.deleteAll()
            telemetry.deleteAll()
            db.noteDao().deleteAll()
            db.savedMealDao().deleteAllItems()
            db.savedMealDao().deleteAllMeals()
            db.foodDao().deleteAllCustom()
            db.insulinTypeDao().deleteAllCustom()
            // kv LAST: it holds the watch nonce ceilings + pairing bits + every setting.
            kv.deleteAll()
        }
    }

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
