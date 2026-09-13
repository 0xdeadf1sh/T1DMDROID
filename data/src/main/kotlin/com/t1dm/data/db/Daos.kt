package com.t1dm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CgmSourceDao {
    @Upsert suspend fun upsert(source: CgmSourceEntity)

    @Query("SELECT * FROM cgm_source ORDER BY addedAtMs")
    fun observeAll(): Flow<List<CgmSourceEntity>>

    @Query("SELECT * FROM cgm_source WHERE authoritative = 1 LIMIT 1")
    fun observeAuthoritative(): Flow<CgmSourceEntity?>

    @Query("SELECT * FROM cgm_source WHERE sourceId = :sourceId")
    suspend fun byId(sourceId: String): CgmSourceEntity?

    @Query("SELECT sourceId FROM cgm_source WHERE authoritative = 1 LIMIT 1")
    suspend fun authoritativeSourceId(): String?

    @Query("SELECT * FROM cgm_source WHERE active = 1 ORDER BY addedAtMs, sourceId")
    fun observeActiveSources(): Flow<List<CgmSourceEntity>>

    /** One-shot [observeActiveSources], for callers already inside a transaction. */
    @Query("SELECT sourceId FROM cgm_source WHERE active = 1 ORDER BY addedAtMs, sourceId")
    suspend fun activeSourceIds(): List<String>

    /** Ids only, never joined to cgm_reading (Room invalidates per TABLE; lastSeenMs churns). */
    @Query("SELECT sourceId FROM cgm_source WHERE sensorModelId = :sensorModelId ORDER BY addedAtMs, sourceId")
    fun observeIdsForSensorModel(sensorModelId: String): Flow<List<String>>

    /** One-shot [observeIdsForSensorModel], for callers already inside a transaction. */
    @Query("SELECT sourceId FROM cgm_source WHERE sensorModelId = :sensorModelId ORDER BY addedAtMs, sourceId")
    suspend fun idsForSensorModel(sensorModelId: String): List<String>

    /** Exactly-one-authoritative: clear all, then set the row. Run inside a @Transaction. */
    @Query("UPDATE cgm_source SET authoritative = 0")
    suspend fun clearAuthoritative()

    /** active/hidden ride along (both invariant of authoritative); nothing un-hides/reactivates. */
    @Query("UPDATE cgm_source SET authoritative = 1, active = 1, hidden = 0 WHERE sourceId = :sourceId")
    suspend fun setAuthoritative(sourceId: String)

    /** Un-hides too: a sensor the app reads belongs on the picker list. */
    @Query("UPDATE cgm_source SET active = 1, hidden = 0 WHERE sourceId = :sourceId")
    suspend fun activate(sourceId: String)

    /** authoritative=0 guard is in WHERE not precondition; invariant holds regardless of UI. */
    @Query("UPDATE cgm_source SET active = 0 WHERE sourceId = :sourceId AND authoritative = 0")
    suspend fun deactivate(sourceId: String)

    /** authoritative=0 guard in WHERE not precondition; never-hidden holds regardless of UI. */
    @Query("UPDATE cgm_source SET hidden = 1, active = 0 WHERE sourceId = :sourceId AND authoritative = 0")
    suspend fun hide(sourceId: String)

    /** Column-scoped, not an upsert: flags untouched, so the exactly-one invariant holds. */
    @Query("UPDATE cgm_source SET warmupWindowMin = :minutes WHERE sourceId = :sourceId")
    suspend fun setWarmupWindowMin(sourceId: String, minutes: Int)

    @Query("DELETE FROM cgm_source")
    suspend fun deleteAll()

    @Query("SELECT * FROM cgm_source ORDER BY addedAtMs")
    suspend fun all(): List<CgmSourceEntity>

    /** Read pre-restore: imports can't land a SECOND authoritative row; active is unconstrained. */
    @Query("SELECT COUNT(*) FROM cgm_source WHERE authoritative = 1")
    suspend fun authoritativeCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<CgmSourceEntity>): List<Long>

    /** -1 when none minted; read inside assigning transaction, or two sensors claim one number. */
    @Query("SELECT COALESCE(MAX(ordinal), -1) FROM cgm_source")
    suspend fun maxOrdinal(): Int

    /** Rows still carrying the unassigned sentinel. */
    @Query("SELECT sourceId FROM cgm_source WHERE ordinal < 0 ORDER BY addedAtMs, sourceId")
    suspend fun unnumberedSourceIds(): List<String>

    @Query("UPDATE cgm_source SET ordinal = :ordinal WHERE sourceId = :sourceId")
    suspend fun setOrdinal(sourceId: String, ordinal: Int)
}

/** No queries: nothing lists or joins these, and only the owning plugin reads one. */
@Dao
interface CgmSensorSecretDao {
    @Upsert suspend fun upsert(row: CgmSensorSecretEntity)

    @Query("SELECT * FROM cgm_sensor_secret WHERE sourceId = :sourceId")
    suspend fun byId(sourceId: String): CgmSensorSecretEntity?

    /** Per-sensor, no delete-all: erasing the only release means destroys hardware, not data. */
    @Query("DELETE FROM cgm_sensor_secret WHERE sourceId = :sourceId")
    suspend fun deleteById(sourceId: String)
}

@Dao
interface CgmReadingDao {
    /** Grid-stamp upsert on `(sourceId, tsMs)` (§3.1). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reading: CgmReadingEntity)

    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId " +
            "AND tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs",
    )
    fun observeRange(sourceId: String, fromMs: Long, toMs: Long): Flow<List<CgmReadingEntity>>

    /** Ties go to the lower id, so the same window always resolves to the same sensor. */
    @Query(
        "SELECT sourceId FROM cgm_reading WHERE tsMs BETWEEN :fromMs AND :toMs " +
            "AND bgMgdl IS NOT NULL GROUP BY sourceId ORDER BY COUNT(*) DESC, sourceId LIMIT 1",
    )
    suspend fun sourceWithMostReadings(fromMs: Long, toMs: Long): String?

    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId ORDER BY tsMs DESC LIMIT 1")
    fun observeLatest(sourceId: String): Flow<CgmReadingEntity?>

    /** The newest REAL measurement — never an interpolation and never a promoted reconstruction. */
    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND bgMgdl IS NOT NULL " +
            "AND provenance = 'MEASURED' AND flag = 'NORMAL' ORDER BY tsMs DESC LIMIT 1",
    )
    fun observeLatestMeasured(sourceId: String): Flow<CgmReadingEntity?>

    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND tsMs = :ts LIMIT 1")
    suspend fun byTs(sourceId: String, ts: Long): CgmReadingEntity?

    /** Spans model class, not source; sample follows whichever held authority (avoids reimport). */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM cgm_reading WHERE sourceId IN (:sourceIds) AND tsMs = :ts)",
    )
    suspend fun existsForSources(sourceIds: List<String>, ts: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(readings: List<CgmReadingEntity>)

    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId " +
            "ORDER BY tsMs DESC LIMIT :limit",
    )
    suspend fun recent(sourceId: String, limit: Int): List<CgmReadingEntity>

    /** Source-scoped (row per source,slot); a different sensor's slot corrupts scoring as error. */
    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND tsMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY tsMs",
    )
    suspend fun rangeForSource(sourceId: String, fromMs: Long, toMs: Long): List<CgmReadingEntity>

    /** Two queries not one: SQLite's MIN/MAX optimisation is lone-aggregate only. */
    @Query("SELECT MIN(tsMs) FROM cgm_reading WHERE sourceId = :sourceId")
    suspend fun oldestTs(sourceId: String): Long?

    /** How far back the BG panel may be PANNED, which is not how far back it has loaded. */
    @Query("SELECT MIN(tsMs) FROM cgm_reading WHERE sourceId = :sourceId")
    fun observeOldestTsForSource(sourceId: String): Flow<Long?>

    @Query("SELECT MAX(tsMs) FROM cgm_reading WHERE sourceId = :sourceId")
    suspend fun newestTs(sourceId: String): Long?

    @Query("DELETE FROM cgm_reading WHERE sourceId = :sourceId AND tsMs = :ts")
    suspend fun deleteAt(sourceId: String, ts: Long)

    /** Demotion must use this: promoted rows file under the authoritative source THEN, not now. */
    @Query("SELECT * FROM cgm_reading WHERE tsMs = :ts")
    suspend fun allAt(ts: Long): List<CgmReadingEntity>

    /** Promotion refuses when null, or the promoted rows would be the source's only rows. */
    @Query(
        "SELECT MAX(tsMs) FROM cgm_reading WHERE sourceId = :sourceId AND bgMgdl IS NOT NULL " +
            "AND provenance = 'MEASURED' AND flag = 'NORMAL'",
    )
    suspend fun newestMeasuredTs(sourceId: String): Long?

    /** Null is what makes a span a BACKCAST: drawable, never promotable. */
    @Query(
        "SELECT MAX(tsMs) FROM cgm_reading WHERE sourceId = :sourceId AND tsMs < :ts " +
            "AND bgMgdl IS NOT NULL AND provenance = 'MEASURED' AND flag = 'NORMAL'",
    )
    suspend fun newestMeasuredBefore(sourceId: String, ts: Long): Long?

    /** Nearest measurement, prefers left (§7.4); never clock's zone now (DST/flight straddle). */
    @Query(
        "SELECT tzOffsetMin FROM cgm_reading WHERE sourceId = :sourceId AND provenance = 'MEASURED' " +
            "ORDER BY (CASE WHEN tsMs <= :ts THEN 0 ELSE 1 END), ABS(tsMs - :ts) LIMIT 1",
    )
    suspend fun tzOffsetNearest(sourceId: String, ts: Long): Int?

    @Query("DELETE FROM cgm_reading")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT sourceId FROM cgm_reading")
    suspend fun sourceIds(): List<String>

    /** Keyset not OFFSET: table is keep-forever, export walks all; OFFSET would make quadratic. */
    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND tsMs > :afterTs ORDER BY tsMs LIMIT :limit")
    suspend fun pageFrom(sourceId: String, afterTs: Long, limit: Int): List<CgmReadingEntity>

    /** IGNORE not REPLACE: archive may FILL a gap, never rewrite; -1 return how restore counts. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<CgmReadingEntity>): List<Long>
}

/** No Flow: Room invalidates per TABLE, written on every sample/sweep; read on demand only. */
@Dao
interface CgmRawSampleDao {
    /** IGNORE not REPLACE: nothing beats the filed sample; write is idempotent, retries free. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: CgmRawSampleEntity): Long

    /** Window is in FILED instants, not grid slots; rawSamplesForSlot owns that conversion. */
    @Query(
        "SELECT * FROM cgm_sample_raw WHERE sourceId = :sourceId " +
            "AND rxWallMs BETWEEN :fromMs AND :toMs ORDER BY rxWallMs",
    )
    suspend fun rangeForSource(sourceId: String, fromMs: Long, toMs: Long): List<CgmRawSampleEntity>

    @Query("SELECT COUNT(*) FROM cgm_sample_raw")
    suspend fun count(): Int

    /** Returns the rows dropped. */
    @Query("DELETE FROM cgm_sample_raw WHERE rxWallMs < :beforeMs")
    suspend fun pruneBefore(beforeMs: Long): Int

    @Query("DELETE FROM cgm_sample_raw")
    suspend fun deleteAll()
}


@Dao
interface SampleDao {
    @Upsert suspend fun upsert(sample: SampleEntity)

    @Query("SELECT * FROM sample WHERE ts = :ts")
    suspend fun byTs(ts: Long): SampleEntity?

    @Query("SELECT * FROM sample WHERE ts > :cursor ORDER BY ts LIMIT :limit")
    suspend fun page(cursor: Long, limit: Int): List<SampleEntity>

    @Query("SELECT MAX(ts) FROM sample")
    suspend fun maxTs(): Long?

    /** Bounded, not [maxTs]: exercise disposal curve writes its tail into slots ahead of clock. */
    @Query("SELECT MAX(ts) FROM sample WHERE ts <= :atMs")
    suspend fun maxTsAtOrBefore(atMs: Long): Long?

    /** Scalar sample-write signal; per-TABLE invalidation emits on same writes as observeRange. */
    @Query("SELECT MAX(ts) FROM sample")
    fun observeMaxTs(): Flow<Long?>

    /** Test spans EVERY source (sample isn't source-scoped), else fakes a missing history. */
    @Query(
        "SELECT * FROM sample WHERE bgMgdl IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM cgm_reading WHERE cgm_reading.tsMs = sample.ts" +
            ") ORDER BY ts",
    )
    suspend fun bgSlotsMissingReading(): List<SampleEntity>

    @Query("SELECT * FROM sample WHERE ts BETWEEN :fromMs AND :toMs ORDER BY ts")
    suspend fun rangeList(fromMs: Long, toMs: Long): List<SampleEntity>

    /** `COALESCE` gives an empty window a defined `maxUpdatedAt` rather than a null. */
    @Query(
        "SELECT COUNT(*) AS n, COALESCE(MAX(updatedAt), 0) AS maxUpdatedAt, " +
            "COUNT(bgMgdl) AS nBg, COUNT(steps) AS nSteps, COUNT(mood) AS nMood FROM sample " +
            "WHERE ts BETWEEN :fromMs AND :toMs",
    )
    suspend fun windowFingerprint(fromMs: Long, toMs: Long): SampleWindowFingerprint

    /** NULL buckets are skipped; an empty window is 0. */
    @Query("SELECT COALESCE(SUM(steps), 0) FROM sample WHERE ts BETWEEN :fromMs AND :toMs")
    suspend fun stepsInRange(fromMs: Long, toMs: Long): Int

    /** Only NULL dropped, ZERO kept: 0 is still five minutes, missing is never-measured. */
    @Query(
        "SELECT ts, steps FROM sample WHERE ts BETWEEN :fromMs AND :toMs " +
            "AND steps IS NOT NULL ORDER BY ts",
    )
    suspend fun stepSeriesInRange(fromMs: Long, toMs: Long): List<StepBucketRow>

    @Query("SELECT mood FROM sample WHERE mood IS NOT NULL ORDER BY ts DESC LIMIT 1")
    fun observeLatestMood(): Flow<Int?>

    @Query("DELETE FROM sample")
    suspend fun deleteAll()

    /** IGNORE: wide row is a PROJECTION; a phone-projected slot beats an archived copy. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<SampleEntity>): List<Long>
}

@Dao
interface DoseEventDao {
    @Insert suspend fun insert(dose: DoseEventEntity): Long

    @Upsert suspend fun upsert(dose: DoseEventEntity)

    @Query("SELECT * FROM dose_event WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<DoseEventEntity>>

    @Query("DELETE FROM dose_event")
    suspend fun deleteAll()
}

@Dao
interface LoggedDoseDao {
    @Insert suspend fun insert(dose: LoggedDoseEntity): Long

    @Upsert suspend fun upsert(dose: LoggedDoseEntity)

    /** Conflicts on unique clientId (catch-up can't duplicate); -1 if held; sample untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(dose: LoggedDoseEntity): Long

    @Query("SELECT * FROM logged_dose WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    suspend fun inRange(fromMs: Long, toMs: Long): List<LoggedDoseEntity>

    @Query("SELECT * FROM logged_dose WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<LoggedDoseEntity>>

    /** The dose half of the event high-water mark, max'd with [LoggedMealDao.latestTs]. */
    @Query("SELECT MAX(tsMs) FROM logged_dose")
    suspend fun latestTs(): Long?

    @Update suspend fun update(dose: LoggedDoseEntity)

    @Query("SELECT * FROM logged_dose WHERE id = :id")
    suspend fun byId(id: Long): LoggedDoseEntity?

    @Query("SELECT * FROM logged_dose WHERE clientId = :clientId")
    suspend fun byClientId(clientId: String): LoggedDoseEntity?

    /** Not MAX(tsMs): earlier of claimed/logged, so edits move mark only backward, never quiet. */
    @Query("SELECT MAX(MIN(tsMs, loggedAtMs)) FROM logged_dose")
    suspend fun latestLoggedMarkTs(): Long?

    /** LATER of pre/post-edit curve ends; post-edit alone lets a shortening edit end IOB early. */
    @Query(
        "SELECT MAX(MAX(tsMs + CAST(durationMin * 60000 AS INTEGER), " +
            "COALESCE(mutatedActingUntilMs, 0))) FROM logged_dose WHERE mutatedAtMs IS NOT NULL",
    )
    suspend fun editedDoseActiveUntilMs(): Long?

    @Query("SELECT MAX(mutatedAtMs) FROM logged_dose")
    suspend fun latestMutationMs(): Long?

    @Insert
    suspend fun insertAll(doses: List<LoggedDoseEntity>)

    /** Bounded at the QUERY, not by tailing an observer: the store is keep-forever. */
    @Query("SELECT * FROM logged_dose ORDER BY tsMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LoggedDoseEntity>>

    @Query("DELETE FROM logged_dose WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM logged_dose")
    suspend fun deleteAll()

    /** (tsMs,id) cursor; tsMs alone isn't unique — tsMs-only cursor could skip or loop on ties. */
    @Query(
        "SELECT * FROM logged_dose WHERE tsMs > :afterTs OR (tsMs = :afterTs AND id > :afterId) " +
            "ORDER BY tsMs, id LIMIT :limit",
    )
    suspend fun pageFrom(afterTs: Long, afterId: Long, limit: Int): List<LoggedDoseEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<LoggedDoseEntity>): List<Long>
}

@Dao
interface LoggedMealDao {
    @Insert suspend fun insert(meal: LoggedMealEntity): Long

    @Upsert suspend fun upsert(meal: LoggedMealEntity)

    /** Conflicts on unique clientId (catch-up can't duplicate); -1 if held; sample untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(meal: LoggedMealEntity): Long

    /** The meal half of the event high-water mark, max'd with [LoggedDoseDao.latestTs]. */
    @Query("SELECT MAX(tsMs) FROM logged_meal")
    suspend fun latestTs(): Long?

    @Update suspend fun update(meal: LoggedMealEntity)

    @Query("SELECT * FROM logged_meal WHERE id = :id")
    suspend fun byId(id: Long): LoggedMealEntity?

    @Query("SELECT * FROM logged_meal WHERE clientId = :clientId")
    suspend fun byClientId(clientId: String): LoggedMealEntity?

    @Query("SELECT * FROM logged_meal WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    suspend fun inRange(fromMs: Long, toMs: Long): List<LoggedMealEntity>

    @Query("SELECT * FROM logged_meal WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<LoggedMealEntity>>

    @Insert
    suspend fun insertAll(meals: List<LoggedMealEntity>)

    @Query("SELECT * FROM logged_meal ORDER BY tsMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LoggedMealEntity>>

    @Query("DELETE FROM logged_meal WHERE id = :id")
    suspend fun delete(id: Long)

    /** Multi-food builder meals (gi IS NULL) excluded; simple carb form can't round-trip them. */
    @Query(
        "SELECT grams AS grams, gi AS gi FROM logged_meal WHERE gi IS NOT NULL " +
            "GROUP BY grams, gi ORDER BY MAX(tsMs) DESC LIMIT :limit",
    )
    fun observeRecentDistinct(limit: Int): Flow<List<RecentMealRow>>

    @Query("DELETE FROM logged_meal")
    suspend fun deleteAll()

    /** See [LoggedDoseDao.pageFrom] on the `(tsMs, id)` cursor. */
    @Query(
        "SELECT * FROM logged_meal WHERE tsMs > :afterTs OR (tsMs = :afterTs AND id > :afterId) " +
            "ORDER BY tsMs, id LIMIT :limit",
    )
    suspend fun pageFrom(afterTs: Long, afterId: Long, limit: Int): List<LoggedMealEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<LoggedMealEntity>): List<Long>
}

data class RecentMealRow(val grams: Double, val gi: Double?)

@Dao
interface BasalScheduleDao {
    @Insert suspend fun insert(row: BasalScheduleEntity): Long

    @Insert suspend fun insertAll(rows: List<BasalScheduleEntity>)

    @Query("SELECT * FROM basal_schedule WHERE active = 1 ORDER BY timeOfDayMin")
    suspend fun activeDoses(): List<BasalScheduleEntity>

    @Query("SELECT * FROM basal_schedule WHERE active = 1 ORDER BY timeOfDayMin")
    fun observeActive(): Flow<List<BasalScheduleEntity>>

    @Query("SELECT * FROM basal_schedule WHERE scheduleId = :scheduleId ORDER BY timeOfDayMin")
    suspend fun byScheduleId(scheduleId: String): List<BasalScheduleEntity>

    @Query("UPDATE basal_schedule SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE basal_schedule SET active = 1 WHERE scheduleId = :scheduleId")
    suspend fun setActive(scheduleId: String)

    @Query("DELETE FROM basal_schedule WHERE scheduleId = :scheduleId")
    suspend fun deleteSchedule(scheduleId: String)

    @Query("DELETE FROM basal_schedule")
    suspend fun deleteAll()

    @Query("SELECT * FROM basal_schedule ORDER BY scheduleId, timeOfDayMin")
    suspend fun all(): List<BasalScheduleEntity>

    /** Restore's merge key; schedule restores WHOLE (no per-row identity), else interleave. */
    @Query("SELECT DISTINCT scheduleId FROM basal_schedule")
    suspend fun scheduleIds(): List<String>
}

@Dao
interface CgmAdvertRawDao {
    @Insert suspend fun insert(advert: CgmAdvertRawEntity): Long

    @Query("DELETE FROM cgm_advert_raw WHERE rxWallMs < :beforeMs")
    suspend fun pruneBefore(beforeMs: Long): Int

    @Query("DELETE FROM cgm_advert_raw")
    suspend fun deleteAll()
}

data class OutboxEvictRow(val id: Long, val kind: OutboxKind, val createdAtMs: Long)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(item: OutboxEntity): Long

    /** One destination only: a shared batch lets the older lane's backlog take every slot. */
    @Query(
        "SELECT * FROM outbox WHERE state = :state AND nextAttemptMs <= :nowMs AND kind = :kind " +
            "ORDER BY createdAtMs, id LIMIT :limit",
    )
    suspend fun dueBatchOfKind(state: OutboxState, nowMs: Long, kind: OutboxKind, limit: Int): List<OutboxEntity>

    /** The other lane; see [dueBatchOfKind]. */
    @Query(
        "SELECT * FROM outbox WHERE state = :state AND nextAttemptMs <= :nowMs AND kind != :kind " +
            "ORDER BY createdAtMs, id LIMIT :limit",
    )
    suspend fun dueBatchExcludingKind(state: OutboxState, nowMs: Long, kind: OutboxKind, limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeDepth(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT MIN(createdAtMs) FROM outbox")
    suspend fun oldestCreatedAt(): Long?

    /** Re-mirror walk infers delivered from absence; bridge row (bound elsewhere) mustn't count. */
    @Query("SELECT MIN(createdAtMs) FROM outbox WHERE kind != :excluded")
    suspend fun oldestCreatedAtExcluding(excluded: OutboxKind): Long?

    /** Any state, unlike deleteByDedupKeyInState (spares INFLIGHT for no-idempotency hosts). */
    @Query("DELETE FROM outbox WHERE dedupKey = :dedupKey")
    suspend fun deleteByDedupKey(dedupKey: String): Int

    /** Ranked and trimmed in Kotlin: Android SQLite lacks `DELETE … ORDER BY … LIMIT`. */
    @Query("SELECT id, kind, createdAtMs FROM outbox ORDER BY createdAtMs, id")
    suspend fun evictionRows(): List<OutboxEvictRow>

    /** No SENT state (drainer DELETEs on success); absent = sent OR evicted, indistinguishable. */
    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: Long): OutboxEntity?

    /** Read INSIDE the deleting transaction, so a concurrent drain cannot land between the two. */
    @Query("SELECT * FROM outbox WHERE dedupKey = :dedupKey")
    suspend fun byDedupKey(dedupKey: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /** Returns 0 or 1 (unique index); already-claimed INFLIGHT row stays untouched (see repo). */
    @Query("DELETE FROM outbox WHERE dedupKey = :dedupKey AND state = :state")
    suspend fun deleteByDedupKeyInState(dedupKey: String, state: OutboxState): Int

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    /** Read just before [resetState] so drainer knows what moved; mid-send/never-sent unclear. */
    @Query("SELECT id FROM outbox WHERE state = :state")
    suspend fun idsInState(state: OutboxState): List<Long>

    /** Reclaims rows wedged in INFLIGHT by a crash mid-send. */
    @Query("UPDATE outbox SET state = :to WHERE state = :from")
    suspend fun resetState(from: OutboxState, to: OutboxState): Int

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, nextAttemptMs = :nextAttemptMs WHERE id = :id")
    suspend fun reschedule(id: Long, state: OutboxState, attempts: Int, nextAttemptMs: Long)

    /** Conditional makes INFLIGHT a mutex token; 0 means deleted/claimed under it, don't send. */
    @Query("UPDATE outbox SET state = :to WHERE id = :id AND state = :from")
    suspend fun claim(id: Long, from: OutboxState, to: OutboxState): Int

    /** Distinct from the id-list [deleteAll]. */
    @Query("DELETE FROM outbox")
    suspend fun deleteAllRows()
}

@Dao
interface PredictionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prediction: PredictionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(predictions: List<PredictionEntity>)

    @Query(
        "SELECT * FROM prediction WHERE madeAtMs = (SELECT MAX(madeAtMs) FROM prediction) " +
            "ORDER BY selected DESC, modelId",
    )
    suspend fun latestCycle(): List<PredictionEntity>

    @Query("SELECT * FROM prediction WHERE madeAtMs BETWEEN :fromMs AND :toMs ORDER BY madeAtMs DESC, modelId")
    suspend fun range(fromMs: Long, toMs: Long): List<PredictionEntity>

    /** One model, ascending — not [range], which is every-model and newest-first. */
    @Query("SELECT * FROM prediction WHERE modelId = :modelId AND madeAtMs BETWEEN :fromMs AND :toMs ORDER BY madeAtMs")
    suspend fun rangeForModel(modelId: String, fromMs: Long, toMs: Long): List<PredictionEntity>

    @Query("SELECT * FROM prediction ORDER BY madeAtMs DESC, selected DESC LIMIT 1")
    fun observeLatest(): Flow<PredictionEntity?>

    @Query("DELETE FROM prediction WHERE modelId = :modelId")
    suspend fun deleteByModel(modelId: String)

    /** The forecasts whose context could have held an event the patient has since edited. */
    @Query("DELETE FROM prediction WHERE madeAtMs >= :fromMs")
    suspend fun deleteFrom(fromMs: Long)

    @Query("DELETE FROM prediction")
    suspend fun deleteAll()
}

@Dao
interface ServerProfileDao {
    @Upsert suspend fun upsert(profile: ServerProfileEntity)

    @Query("SELECT * FROM server_profile ORDER BY createdAtMs")
    fun observeAll(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profile WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<ServerProfileEntity?>

    @Query("SELECT * FROM server_profile WHERE active = 1 LIMIT 1")
    suspend fun active(): ServerProfileEntity?

    @Query("SELECT * FROM server_profile WHERE id = :id")
    suspend fun byId(id: String): ServerProfileEntity?

    @Query("UPDATE server_profile SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE server_profile SET active = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Query("DELETE FROM server_profile WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM server_profile")
    suspend fun deleteAll()

    /** rw token is NOT a column (lives in Keystore); this query can't leak it into a backup. */
    @Query("SELECT * FROM server_profile ORDER BY createdAtMs")
    suspend fun all(): List<ServerProfileEntity>

    /** See [CgmSourceDao.authoritativeCount]. */
    @Query("SELECT COUNT(*) FROM server_profile WHERE active = 1")
    suspend fun activeCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<ServerProfileEntity>): List<Long>
}

@Dao
interface KvDao {
    @Upsert suspend fun put(entry: KvEntity)

    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM kv WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT * FROM kv ORDER BY `key`")
    suspend fun all(): List<KvEntity>

    @Upsert suspend fun putAll(entries: List<KvEntity>)

    /** Drops watch pairing/epoch/nonce-ceiling rows too, so a re-pair can't reuse (key,nonce). */
    @Query("DELETE FROM kv")
    suspend fun deleteAll()
}

@Dao
interface HwTelemetryDao {
    @Insert suspend fun insert(row: HwTelemetryEntity): Long

    @Query(
        "SELECT * FROM hw_telemetry WHERE (:modelId IS NULL OR modelId = :modelId) " +
            "AND tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs",
    )
    suspend fun range(modelId: String?, fromMs: Long, toMs: Long): List<HwTelemetryEntity>

    @Query("DELETE FROM hw_telemetry")
    suspend fun deleteAll()
}

@Dao
interface FoodDao {
    @Insert suspend fun insert(food: FoodEntity): Long

    @Insert suspend fun insertAll(foods: List<FoodEntity>)

    @Upsert suspend fun upsert(food: FoodEntity)

    @Query("SELECT COUNT(*) FROM food") suspend fun count(): Int

    @Query("SELECT * FROM food WHERE id = :id") suspend fun byId(id: Long): FoodEntity?

    /** match is raw FTS5 MATCH; SkipQueryVerification since food_fts is hand-rolled, unmodeled. */
    @SkipQueryVerification
    @Query(
        "SELECT food.* FROM food JOIN food_fts ON food.id = food_fts.rowid " +
            "WHERE food_fts MATCH :match ORDER BY rank LIMIT :limit",
    )
    suspend fun search(match: String, limit: Int): List<FoodEntity>

    @Query("SELECT * FROM food ORDER BY name LIMIT :limit")
    suspend fun all(limit: Int): List<FoodEntity>

    @Query("SELECT * FROM food WHERE custom = 1 ORDER BY updatedAt DESC")
    fun observeCustom(): Flow<List<FoodEntity>>

    @Query("DELETE FROM food WHERE id = :id AND custom = 1")
    suspend fun deleteCustom(id: Long)

    /** The seed dictionary is kept. The `food_ad` trigger keeps `food_fts` in lockstep. */
    @Query("DELETE FROM food WHERE custom = 1")
    suspend fun deleteAllCustom()

    /** Seed rows ship with the APK, so the archive does not carry them. */
    @Query("SELECT * FROM food WHERE custom = 1 ORDER BY id")
    suspend fun allCustom(): List<FoodEntity>

    /** The restore's merge key: `id` is autogenerated per device and does not travel. */
    @Query("SELECT name, brand FROM food WHERE custom = 1")
    suspend fun customKeys(): List<FoodKeyRow>
}

data class FoodKeyRow(val name: String, val brand: String?)

@Dao
interface SavedMealDao {
    @Insert suspend fun insertMeal(meal: SavedMealEntity): Long

    @Insert suspend fun insertItems(items: List<SavedMealItemEntity>)

    /** 0 = meal deleted under editor (item rows orphan, no FK); issued even if name unchanged. */
    @Query("UPDATE saved_meal SET name = :name, updatedAt = :nowMs WHERE id = :id")
    suspend fun updateMeal(id: Long, name: String, nowMs: Long): Int

    @Query("SELECT * FROM saved_meal ORDER BY updatedAt DESC")
    fun observeMeals(): Flow<List<SavedMealEntity>>

    @Query("SELECT * FROM saved_meal ORDER BY updatedAt DESC")
    suspend fun allMeals(): List<SavedMealEntity>

    @Query("SELECT * FROM saved_meal_item WHERE mealId = :mealId")
    suspend fun itemsOf(mealId: Long): List<SavedMealItemEntity>

    @Query("DELETE FROM saved_meal_item WHERE mealId = :mealId")
    suspend fun deleteItems(mealId: Long)

    @Query("DELETE FROM saved_meal WHERE id = :id")
    suspend fun deleteMeal(id: Long)

    @Query("DELETE FROM saved_meal_item")
    suspend fun deleteAllItems()

    @Query("DELETE FROM saved_meal")
    suspend fun deleteAllMeals()

    /** Ordered by `mealId`, so the writer emits each meal's items together without re-sorting. */
    @Query("SELECT * FROM saved_meal_item ORDER BY mealId, id")
    suspend fun allItems(): List<SavedMealItemEntity>

    /** The restore's merge key: the `id` is per-device. */
    @Query("SELECT name FROM saved_meal")
    suspend fun names(): List<String>
}

@Dao
interface InsulinTypeDao {
    @Insert suspend fun insert(type: InsulinTypeEntity): Long

    @Insert suspend fun insertAll(types: List<InsulinTypeEntity>)

    @Upsert suspend fun upsert(type: InsulinTypeEntity)

    @Query("SELECT COUNT(*) FROM insulin_type WHERE builtin = 1") suspend fun builtinCount(): Int

    @Query("SELECT * FROM insulin_type ORDER BY builtin DESC, name")
    fun observeAll(): Flow<List<InsulinTypeEntity>>

    @Query("SELECT * FROM insulin_type ORDER BY builtin DESC, name")
    suspend fun all(): List<InsulinTypeEntity>

    @Query("DELETE FROM insulin_type WHERE id = :id AND builtin = 0")
    suspend fun deleteCustom(id: Long)

    /** The builtin presets are kept. */
    @Query("DELETE FROM insulin_type WHERE builtin = 0")
    suspend fun deleteAllCustom()

    /** Builtins are seeded by the install, so the archive does not carry them. */
    @Query("SELECT * FROM insulin_type WHERE builtin = 0 ORDER BY id")
    suspend fun allCustom(): List<InsulinTypeEntity>

    /** The restore's merge key. */
    @Query("SELECT name FROM insulin_type WHERE builtin = 0")
    suspend fun customNames(): List<String>
}

@Dao
interface PaintStrokeDao {
    @Insert suspend fun insert(stroke: PaintStrokeEntity): Long

    /** Intersection not containment; wide strokes still draw. Later paints over earlier. */
    @Query(
        "SELECT * FROM bg_paint_stroke WHERE maxTsMs >= :fromMs AND minTsMs <= :toMs " +
            "ORDER BY createdAtMs, id",
    )
    fun observeOverlapping(fromMs: Long, toMs: Long): Flow<List<PaintStrokeEntity>>

    @Query("DELETE FROM bg_paint_stroke WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bg_paint_stroke")
    suspend fun deleteAll()

    /** Cursor is rowid, not createdAtMs: strokes insert in order, id unique, createdAtMs isn't. */
    @Query("SELECT * FROM bg_paint_stroke WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageFrom(afterId: Long, limit: Int): List<PaintStrokeEntity>

    /** The restore's merge key. */
    @Query("SELECT createdAtMs FROM bg_paint_stroke")
    suspend fun allCreatedAt(): List<Long>

    /** The caller has already filtered by [allCreatedAt]. */
    @Insert
    suspend fun insertAll(rows: List<PaintStrokeEntity>)
}

@Dao
interface BgInfillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<BgInfillEntity>)

    @Query("SELECT * FROM bg_infill WHERE ts BETWEEN :fromMs AND :toMs ORDER BY ts")
    suspend fun inRange(fromMs: Long, toMs: Long): List<BgInfillEntity>

    @Query("SELECT * FROM bg_infill WHERE ts BETWEEN :fromMs AND :toMs ORDER BY ts")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<BgInfillEntity>>

    @Query("DELETE FROM bg_infill WHERE ts BETWEEN :fromMs AND :toMs")
    suspend fun deleteRange(fromMs: Long, toMs: Long)

    /** Promoted rows exempt: table holds only copy of a promoted band; demotion reads it here. */
    @Query("DELETE FROM bg_infill WHERE modelId = :modelId AND promotedAtMs IS NULL")
    suspend fun deleteByModel(modelId: String)

    /** A fill over a since-edited curve describes a history no longer existing; promoted exempt. */
    @Query("DELETE FROM bg_infill WHERE ts >= :fromMs AND promotedAtMs IS NULL")
    suspend fun deleteFrom(fromMs: Long)

    @Query("SELECT * FROM bg_infill WHERE ts = :ts")
    suspend fun at(ts: Long): BgInfillEntity?

    @Query("SELECT * FROM bg_infill WHERE spanStartMs = :spanStartMs ORDER BY ts")
    suspend fun span(spanStartMs: Long): List<BgInfillEntity>

    /** Null demotes. */
    @Query("UPDATE bg_infill SET promotedAtMs = :atMs WHERE spanStartMs = :spanStartMs")
    suspend fun markPromoted(spanStartMs: Long, atMs: Long?)

    /** Promoted guard in the statement, not caller; the one removal a finger can reach. */
    @Query("DELETE FROM bg_infill WHERE spanStartMs = :spanStartMs AND promotedAtMs IS NULL")
    suspend fun deleteSpanIfUnpromoted(spanStartMs: Long): Int

    @Query("UPDATE bg_infill SET mgdl = :mgdl, tau = :tau WHERE ts = :ts")
    suspend fun setLineAt(ts: Long, mgdl: Double, tau: Double)

    /** For a measurement landing there, which makes the fill stale by construction. */
    @Query("DELETE FROM bg_infill WHERE ts = :ts")
    suspend fun deleteAt(ts: Long)

    /** The archive's source: this table is the only place a promoted fill's band exists. */
    @Query("SELECT * FROM bg_infill WHERE promotedAtMs IS NOT NULL ORDER BY ts")
    suspend fun allPromoted(): List<BgInfillEntity>

    /** Each span's FIRST SURVIVING row; the head can be deleted by a landing measurement. */
    @Query(
        "SELECT * FROM bg_infill AS h WHERE ts = " +
            "(SELECT MIN(ts) FROM bg_infill AS b WHERE b.spanStartMs = h.spanStartMs) " +
            "AND ts BETWEEN :fromMs AND :toMs ORDER BY ts DESC",
    )
    fun observeSpanHeads(fromMs: Long, toMs: Long): Flow<List<BgInfillEntity>>

    @Query("SELECT COUNT(*) FROM bg_infill WHERE spanStartMs = :spanStartMs")
    suspend fun spanSize(spanStartMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<BgInfillEntity>): List<Long>

    @Query("DELETE FROM bg_infill")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM bg_infill")
    suspend fun count(): Int
}

@Dao
interface LoraDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LoraEntity): Long

    @Query("SELECT * FROM lora ORDER BY modelId, name")
    fun observeAll(): Flow<List<LoraEntity>>

    @Query("SELECT * FROM lora WHERE modelId = :modelId ORDER BY name")
    suspend fun byModel(modelId: String): List<LoraEntity>

    @Query("UPDATE lora SET guardOverrideAtMs = :atMs, updatedAtMs = :atMs WHERE id = :id")
    suspend fun setGuardOverride(id: Long, atMs: Long)

    /** Every input rides beside the verdict: a refusal has to be readable rather than trusted. */
    @Query(
        "UPDATE lora SET guardVerdict = :verdict, guardWindows = :windows, " +
            "guardFrozenMgdl = :frozenMgdl, guardAdaptedMgdl = :adaptedMgdl, " +
            "guardRetention = :retention, guardSignAgreement = :signAgreement, " +
            "guardWhy = :why, updatedAtMs = :atMs WHERE id = :id",
    )
    suspend fun setGuard(
        id: Long,
        verdict: String,
        windows: Int,
        frozenMgdl: Double,
        adaptedMgdl: Double,
        retention: Double,
        signAgreement: Double,
        why: String,
        atMs: Long,
    )

    /** fittedAtMs>0 excludes imported/restored adapters; override can't clear the refusal. */
    @Query("UPDATE lora SET historyMutatedAtMs = :nowMs WHERE fittedAtMs > 0 AND fittedAtMs <= :nowMs")
    suspend fun markHistoryMutated(nowMs: Long)

    @Query("SELECT * FROM lora WHERE id = :id")
    suspend fun byId(id: Long): LoraEntity?

    @Query("SELECT * FROM lora WHERE modelId = :modelId AND attached = 1 LIMIT 1")
    suspend fun attachedFor(modelId: String): LoraEntity?

    @Query("UPDATE lora SET attached = 0, updatedAtMs = :nowMs WHERE modelId = :modelId")
    suspend fun detachAll(modelId: String, nowMs: Long)

    @Query("UPDATE lora SET attached = 1, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun attach(id: Long, nowMs: Long)

    @Query("UPDATE lora SET name = :name, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun rename(id: Long, name: String, nowMs: Long)

    @Query("DELETE FROM lora WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM lora WHERE modelId = :modelId")
    suspend fun deleteByModel(modelId: String)

    @Query("DELETE FROM lora")
    suspend fun deleteAll()

    @Query("SELECT * FROM lora")
    suspend fun all(): List<LoraEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<LoraEntity>)
}

@Dao
interface ConformalDeltaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(delta: ConformalDeltaEntity)

    @Query("SELECT * FROM conformal_delta WHERE modelId = :modelId")
    suspend fun get(modelId: String): ConformalDeltaEntity?

    @Query("SELECT * FROM conformal_delta")
    fun observeAll(): Flow<List<ConformalDeltaEntity>>

    @Query("DELETE FROM conformal_delta WHERE modelId = :modelId")
    suspend fun deleteByModel(modelId: String)

    @Query("DELETE FROM conformal_delta")
    suspend fun deleteAll()

    @Query("SELECT * FROM conformal_delta")
    suspend fun all(): List<ConformalDeltaEntity>

    /** IGNORE not REPLACE: a correction fitted on this phone's forecasts must not be displaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<ConformalDeltaEntity>): List<Long>
}

@Dao
interface LoggedExerciseDao {
    @Insert suspend fun insert(row: LoggedExerciseEntity): Long

    @Update suspend fun update(row: LoggedExerciseEntity)

    @Query("SELECT * FROM logged_exercise WHERE id = :id")
    suspend fun byId(id: Long): LoggedExerciseEntity?

    @Query("SELECT * FROM logged_exercise WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    suspend fun inRange(fromMs: Long, toMs: Long): List<LoggedExerciseEntity>

    @Query("SELECT * FROM logged_exercise ORDER BY tsMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LoggedExerciseEntity>>

    @Query("DELETE FROM logged_exercise WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM logged_exercise")
    suspend fun deleteAll()

    /** See [LoggedDoseDao.pageFrom] on the `(tsMs, id)` cursor. */
    @Query(
        "SELECT * FROM logged_exercise WHERE tsMs > :afterTs OR (tsMs = :afterTs AND id > :afterId) " +
            "ORDER BY tsMs, id LIMIT :limit",
    )
    suspend fun pageFrom(afterTs: Long, afterId: Long, limit: Int): List<LoggedExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<LoggedExerciseEntity>): List<Long>
}

@Dao
interface ExerciseSessionDao {
    @Insert suspend fun insert(row: ExerciseSessionEntity): Long

    /** Column-scoped, not upsert (clientId/startMs/kind are identity); post-delete stop no-ops. */
    @Query(
        "UPDATE exercise_session SET endMs = :endMs, activeSec = :activeSec, distanceM = :distanceM, " +
            "kcal = :kcal, interrupted = :interrupted, updatedAt = :nowMs WHERE id = :id",
    )
    suspend fun close(
        id: Long,
        endMs: Long,
        activeSec: Int,
        distanceM: Double?,
        kcal: Int?,
        interrupted: Boolean,
        nowMs: Long,
    )

    @Query("SELECT * FROM exercise_session ORDER BY startMs DESC, id DESC")
    fun observeAll(): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM exercise_session WHERE id = :id")
    suspend fun byId(id: Long): ExerciseSessionEntity?

    /** A live recording is in here too, which is why the reconcile runs once at start-up. */
    @Query("SELECT * FROM exercise_session WHERE endMs IS NULL ORDER BY startMs")
    suspend fun open(): List<ExerciseSessionEntity>

    @Query("DELETE FROM exercise_session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM exercise_session")
    suspend fun deleteAll()

    /** See [LoggedDoseDao.pageFrom] on the pair cursor. */
    @Query(
        "SELECT * FROM exercise_session WHERE startMs > :afterStartMs OR (startMs = :afterStartMs AND id > :afterId) " +
            "ORDER BY startMs, id LIMIT :limit",
    )
    suspend fun pageFrom(afterStartMs: Long, afterId: Long, limit: Int): List<ExerciseSessionEntity>

    /** -1 on conflict tells restore which bouts it added; only those get archived fixes applied. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<ExerciseSessionEntity>): List<Long>
}

@Dao
interface ExerciseFixDao {
    @Insert suspend fun insertAll(rows: List<ExerciseFixEntity>)

    @Query("SELECT * FROM exercise_fix WHERE sessionId = :sessionId ORDER BY tsMs, id")
    suspend fun forSession(sessionId: Long): List<ExerciseFixEntity>

    /** Where an interrupted bout is closed: the last instant the app can prove it was running. */
    @Query("SELECT MAX(tsMs) FROM exercise_fix WHERE sessionId = :sessionId")
    suspend fun newestTs(sessionId: Long): Long?

    @Query("DELETE FROM exercise_fix WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM exercise_fix")
    suspend fun deleteAll()

    /** id rides in cursor (two fixes can share a ms); tsMs-only cursor would silently drop rows. */
    @Query(
        "SELECT * FROM exercise_fix WHERE sessionId = :sessionId AND " +
            "(tsMs > :afterTs OR (tsMs = :afterTs AND id > :afterId)) ORDER BY tsMs, id LIMIT :limit",
    )
    suspend fun pageFrom(sessionId: Long, afterTs: Long, afterId: Long, limit: Int): List<ExerciseFixEntity>
}

/** Keep-forever: dropping a tombstone re-opens resurrection hole; phone can't know retention. */
@Dao
interface EventTombstoneDao {
    @Upsert suspend fun upsert(row: EventTombstoneEntity)

    @Query("SELECT * FROM event_tombstone WHERE clientId = :clientId")
    suspend fun byClientId(clientId: String): EventTombstoneEntity?

    /** Tombstone term in high-water mark (else deletes re-hydrate); [kinds] is WIRE kinds only. */
    @Query("SELECT MAX(tsMs) FROM event_tombstone WHERE kind IN (:kinds)")
    suspend fun latestTs(kinds: List<String>): Long?

    /** Connect-time replay's work list: death between delete and enqueue, or queue-cap eviction. */
    @Query("SELECT * FROM event_tombstone WHERE pushEnqueuedAtMs IS NULL ORDER BY createdAtMs")
    suspend fun unpushed(): List<EventTombstoneEntity>

    @Query("UPDATE event_tombstone SET pushEnqueuedAtMs = :atMs WHERE clientId = :clientId")
    suspend fun markPushed(clientId: String, atMs: Long)

    /** `logged_dose` cannot answer this: the row the duration would be read from is gone. */
    @Query("SELECT MAX(actingUntilMs) FROM event_tombstone WHERE kind = :kind")
    suspend fun latestActingUntilMs(kind: String): Long?

    @Query("SELECT MAX(createdAtMs) FROM event_tombstone WHERE kind = :kind")
    suspend fun latestCreatedAtMs(kind: String): Long?

    @Query("SELECT * FROM event_tombstone ORDER BY createdAtMs")
    suspend fun all(): List<EventTombstoneEntity>

    @Query("DELETE FROM event_tombstone")
    suspend fun deleteAll()
}
