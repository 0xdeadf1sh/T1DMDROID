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

    /**
     * Ids, never a join to `cgm_reading`: Room invalidates per TABLE, and `lastSeenMs` is touched on
     * every re-sighting, so a joined history would re-materialise at scan rate.
     */
    @Query("SELECT sourceId FROM cgm_source WHERE sensorModelId = :sensorModelId ORDER BY addedAtMs, sourceId")
    fun observeIdsForSensorModel(sensorModelId: String): Flow<List<String>>

    /** One-shot [observeIdsForSensorModel], for callers already inside a transaction. */
    @Query("SELECT sourceId FROM cgm_source WHERE sensorModelId = :sensorModelId ORDER BY addedAtMs, sourceId")
    suspend fun idsForSensorModel(sensorModelId: String): List<String>

    /** Exactly-one-authoritative invariant: clear all, then set the chosen row. Run in a @Transaction. */
    @Query("UPDATE cgm_source SET authoritative = 0")
    suspend fun clearAuthoritative()

    /** `active` and `hidden` ride along: both are invariants of being authoritative, and nothing
     *  else re-lists a hidden source or re-activates a stopped one. */
    @Query("UPDATE cgm_source SET authoritative = 1, active = 1, hidden = 0 WHERE sourceId = :sourceId")
    suspend fun setAuthoritative(sourceId: String)

    /** Un-hides too: a sensor the app reads belongs on the picker list. */
    @Query("UPDATE cgm_source SET active = 1, hidden = 0 WHERE sourceId = :sourceId")
    suspend fun activate(sourceId: String)

    /** The `authoritative = 0` guard is in the WHERE, not a precondition: this is the one door into
     *  the column, so "the authoritative source is always active" holds whatever the UI does. */
    @Query("UPDATE cgm_source SET active = 0 WHERE sourceId = :sourceId AND authoritative = 0")
    suspend fun deactivate(sourceId: String)

    /** The `authoritative = 0` guard is in the WHERE, not a precondition: this is the one door into
     *  the column, so "the authoritative source is never hidden" holds whatever the UI does. */
    @Query("UPDATE cgm_source SET hidden = 1, active = 0 WHERE sourceId = :sourceId AND authoritative = 0")
    suspend fun hide(sourceId: String)

    /** Column-scoped, not an upsert: the flags are untouched, so the exactly-one invariant holds. */
    @Query("UPDATE cgm_source SET warmupWindowMin = :minutes WHERE sourceId = :sourceId")
    suspend fun setWarmupWindowMin(sourceId: String, minutes: Int)

    @Query("DELETE FROM cgm_source")
    suspend fun deleteAll()

    @Query("SELECT * FROM cgm_source ORDER BY addedAtMs")
    suspend fun all(): List<CgmSourceEntity>

    /** Read before a restore, so imported sources cannot land a SECOND authoritative row. `active`
     *  is unconstrained: many may be active. */
    @Query("SELECT COUNT(*) FROM cgm_source WHERE authoritative = 1")
    suspend fun authoritativeCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<CgmSourceEntity>): List<Long>

    /** `-1` when none minted. Read inside the transaction that assigns the next, or two sensors
     *  recorded at once claim one number. */
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

    /** Per-sensor, and no delete-all: erasing the only means of releasing a sensor still on the
     *  patient's arm destroys hardware rather than data. */
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

    /**
     * Ordered by `tsMs` alone, so two sources at one slot arrive adjacent for the caller to collapse.
     * SQLite sorts the union in a temp B-tree, so the caller must bound the window. [sourceIds] must
     * be non-empty: Room emits `IN ()` for an empty list, which SQLite rejects.
     */
    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId IN (:sourceIds) " +
            "AND tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs",
    )
    fun observeRangeForSources(
        sourceIds: List<String>,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<CgmReadingEntity>>

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

    /** Spans the model class, not one source: `sample` was authored by whichever source held
     *  authority, so a per-source test re-imports the record on every sensor replacement. */
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

    /**
     * Source-scoped deliberately: the table holds a row per `(source, slot)`, and scoring a forecast
     * against a slot a DIFFERENT sensor supplied reports sensor disagreement as model error.
     */
    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND tsMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY tsMs",
    )
    suspend fun rangeForSource(sourceId: String, fromMs: Long, toMs: Long): List<CgmReadingEntity>

    /** Two queries, not one returning both columns: SQLite's MIN/MAX optimisation applies only to a
     *  lone aggregate, and asking for both at once forfeits it and scans. */
    @Query("SELECT MIN(tsMs) FROM cgm_reading WHERE sourceId = :sourceId")
    suspend fun oldestTs(sourceId: String): Long?

    /** How far back the BG panel may be PANNED, which is not how far back it has loaded. */
    @Query("SELECT MIN(tsMs) FROM cgm_reading WHERE sourceId IN (:sourceIds)")
    fun observeOldestTsForSources(sourceIds: List<String>): Flow<Long?>

    @Query("SELECT MAX(tsMs) FROM cgm_reading WHERE sourceId = :sourceId")
    suspend fun newestTs(sourceId: String): Long?

    @Query("DELETE FROM cgm_reading WHERE sourceId = :sourceId AND tsMs = :ts")
    suspend fun deleteAt(sourceId: String, ts: Long)

    /** Demotion must use this: a promoted row is filed under whichever source was authoritative
     *  then, so resolving only the current one leaves the reconstruction in place. */
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

    /** Nearest measurement, preferring its left: `SPEC/inference.md` §7.4. Never the clock's zone
     *  now — a gap can straddle a DST change or a flight. */
    @Query(
        "SELECT tzOffsetMin FROM cgm_reading WHERE sourceId = :sourceId AND provenance = 'MEASURED' " +
            "ORDER BY (CASE WHEN tsMs <= :ts THEN 0 ELSE 1 END), ABS(tsMs - :ts) LIMIT 1",
    )
    suspend fun tzOffsetNearest(sourceId: String, ts: Long): Int?

    @Query("DELETE FROM cgm_reading")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT sourceId FROM cgm_reading")
    suspend fun sourceIds(): List<String>

    /** Keyset, not OFFSET: the table is keep-forever and the export walks all of it, which OFFSET
     *  would make quadratic. */
    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND tsMs > :afterTs ORDER BY tsMs LIMIT :limit")
    suspend fun pageFrom(sourceId: String, afterTs: Long, limit: Int): List<CgmReadingEntity>

    /** IGNORE, not the REPLACE [upsertAll] uses: an archive may FILL a gap, never rewrite a slot
     *  the phone already holds. Ignored rows return -1, which is how the restore counts. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<CgmReadingEntity>): List<Long>
}

/**
 * Deliberately NO `Flow`: Room invalidates per TABLE, and this is written on every sample and every
 * retention sweep. Read on demand; nothing waits on these rows.
 */
@Dao
interface CgmRawSampleDao {
    /** IGNORE, not REPLACE: nothing later knows better than the sample filed at that instant. The
     *  write is idempotent, so a retry or a re-delivered sample costs nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: CgmRawSampleEntity): Long

    /** The window is in FILED instants, not grid slots; [com.t1dm.data.T1dmRepository.rawSamplesForSlot]
     *  owns that conversion. */
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

    /** Bounded rather than [maxTs] because the exercise disposal curve writes its tail into slots
     *  ahead of the clock. */
    @Query("SELECT MAX(ts) FROM sample WHERE ts <= :atMs")
    suspend fun maxTsAtOrBefore(atMs: Long): Long?

    /** The scalar `sample`-write signal: per-TABLE invalidation makes it emit on exactly the writes
     *  an `observeRange` would, without materialising a row. */
    @Query("SELECT MAX(ts) FROM sample")
    fun observeMaxTs(): Flow<Long?>

    /**
     * The membership test spans EVERY source, and must: `sample` is not source-scoped, so a
     * per-source or per-class test makes each sensor change look like a whole missing history and
     * rewrites the outgoing sensor's record as the incoming sensor's own MEASURED readings.
     */
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

    /** Only NULL is dropped; a recorded ZERO is kept. A `0` is a still five minutes, a missing row
     *  was never measured, and the read-out must stay silent about the second. */
    @Query(
        "SELECT ts, steps FROM sample WHERE ts BETWEEN :fromMs AND :toMs " +
            "AND steps IS NOT NULL ORDER BY ts",
    )
    suspend fun stepSeriesInRange(fromMs: Long, toMs: Long): List<StepBucketRow>

    @Query("SELECT mood FROM sample WHERE mood IS NOT NULL ORDER BY ts DESC LIMIT 1")
    fun observeLatestMood(): Flow<Int?>

    @Query("DELETE FROM sample")
    suspend fun deleteAll()

    /** IGNORE: the wide row is a PROJECTION, so a slot the phone has already projected beats an
     *  archived copy of it. */
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

    /** Conflicts on the unique `clientId`, so a catch-up cannot duplicate a phone-authored dose.
     *  -1 when the clientId is already held. Never re-projects into `sample`. */
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

    /** Not `MAX(tsMs)`: taking the earlier of claimed and logged means an edit can only move the
     *  mark backward, so retiming a dose forward cannot quiet the log-gap rail. */
    @Query("SELECT MAX(MIN(tsMs, loggedAtMs)) FROM logged_dose")
    suspend fun latestLoggedMarkTs(): Long?

    /** The LATER of the pre-edit and post-edit curve ends: reading the post-edit row alone lets an
     *  edit that shortens the curve end the block while the IOB it invalidated is still wrong. */
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

    /** `(tsMs, id)` cursor: `tsMs` alone is not unique, and a `tsMs`-only cursor would skip a dose
     *  logged in the same millisecond or loop on it. */
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

    /** Conflicts on the unique `clientId`, so a catch-up cannot duplicate a phone-authored meal.
     *  -1 when the clientId is already held. Never re-projects into `sample`. */
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

    /** Multi-food builder meals (gi IS NULL) are excluded: the simple carb form cannot round-trip
     *  them. */
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

    /** The restore's merge key. A schedule restores WHOLE: its rows have no per-row identity, so a
     *  per-injection merge could interleave two schedules. */
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

    @Query(
        "SELECT * FROM outbox WHERE state = :state AND nextAttemptMs <= :nowMs " +
            "ORDER BY createdAtMs, id LIMIT :limit",
    )
    suspend fun dueBatch(state: OutboxState, nowMs: Long, limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeDepth(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT MIN(createdAtMs) FROM outbox")
    suspend fun oldestCreatedAt(): Long?

    /** The re-mirror walk infers "delivered" from the absence of a row this old. A bridge row is
     *  bound elsewhere, so counting one would let a third party hold the walk open forever. */
    @Query("SELECT MIN(createdAtMs) FROM outbox WHERE kind != :excluded")
    suspend fun oldestCreatedAtExcluding(excluded: OutboxKind): Long?

    /** Any state, unlike [deleteByDedupKeyInState], which spares an INFLIGHT row for a host with no
     *  idempotency key. The phone's own server corrects a superseded PUT with the body behind it. */
    @Query("DELETE FROM outbox WHERE dedupKey = :dedupKey")
    suspend fun deleteByDedupKey(dedupKey: String): Int

    /** Ranked and trimmed in Kotlin: Android SQLite lacks `DELETE … ORDER BY … LIMIT`. */
    @Query("SELECT id, kind, createdAtMs FROM outbox ORDER BY createdAtMs, id")
    suspend fun evictionRows(): List<OutboxEvictRow>

    /** No SENT state — the drainer DELETEs on success — so absent is the only "already sent" signal,
     *  and it cannot be told from evicted. */
    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: Long): OutboxEntity?

    /** Read INSIDE the deleting transaction, so a concurrent drain cannot land between the two. */
    @Query("SELECT * FROM outbox WHERE dedupKey = :dedupKey")
    suspend fun byDedupKey(dedupKey: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /** Returns 0 or 1, the index being unique. A row already claimed INFLIGHT is left alone; see
     *  `T1dmRepository.enqueueReplacingPending`. */
    @Query("DELETE FROM outbox WHERE dedupKey = :dedupKey AND state = :state")
    suspend fun deleteByDedupKeyInState(dedupKey: String, state: OutboxState): Int

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    /** Read immediately before [resetState], so the drainer knows which rows the reclaim moved. A
     *  reclaimed row that was mid-send cannot be told from one that never reached the wire. */
    @Query("SELECT id FROM outbox WHERE state = :state")
    suspend fun idsInState(state: OutboxState): List<Long>

    /** Reclaims rows wedged in INFLIGHT by a crash mid-send. */
    @Query("UPDATE outbox SET state = :to WHERE state = :from")
    suspend fun resetState(from: OutboxState, to: OutboxState): Int

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, nextAttemptMs = :nextAttemptMs WHERE id = :id")
    suspend fun reschedule(id: Long, state: OutboxState, attempts: Int, nextAttemptMs: Long)

    /** Conditional, which is what makes INFLIGHT a mutual-exclusion token: a zero return means the
     *  row was deleted or claimed underneath the drainer's snapshot and must not be sent. */
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

    /** The `rw` token is NOT a column — it lives in the Keystore — so this cannot leak it into a
     *  backup file. */
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

    /** Drops the watch pairing/epoch/nonce-ceiling rows too, so a re-pair cannot reuse a
     *  (key, nonce) pair. */
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

    /** [match] is a raw FTS5 MATCH expression. `@SkipQueryVerification` because `food_fts` is a
     *  hand-rolled virtual table Room does not model as an entity. */
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

    /**
     * Returns 0 exactly when the meal was deleted under the editor; `saved_meal_item` has no foreign
     * key, so item rows written then are permanent orphans. Issued even when [name] is unchanged, or
     * an edit confined to the item rows never invalidates [observeMeals].
     */
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

    /** Intersection, not containment, inclusive at both ends: a stroke wider than the window still
     *  draws. Ordered by authoring time, so later strokes paint over earlier ones. */
    @Query(
        "SELECT * FROM bg_paint_stroke WHERE maxTsMs >= :fromMs AND minTsMs <= :toMs " +
            "ORDER BY createdAtMs, id",
    )
    fun observeOverlapping(fromMs: Long, toMs: Long): Flow<List<PaintStrokeEntity>>

    @Query("DELETE FROM bg_paint_stroke WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bg_paint_stroke")
    suspend fun deleteAll()

    /** The rowid is the cursor, not `createdAtMs`: strokes insert in authoring order and `id` is
     *  unique, which `createdAtMs` is not. */
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

    /** Promoted rows are exempt: this table holds the only copy of a promoted sample's band, and
     *  demotion reads the span from here. */
    @Query("DELETE FROM bg_infill WHERE modelId = :modelId AND promotedAtMs IS NULL")
    suspend fun deleteByModel(modelId: String)

    /** A fill made over a curve the patient has since edited describes a history that no longer
     *  exists. Promoted rows are exempt, for the reason [deleteByModel] states. */
    @Query("DELETE FROM bg_infill WHERE ts >= :fromMs AND promotedAtMs IS NULL")
    suspend fun deleteFrom(fromMs: Long)

    @Query("SELECT * FROM bg_infill WHERE ts = :ts")
    suspend fun at(ts: Long): BgInfillEntity?

    @Query("SELECT * FROM bg_infill WHERE spanStartMs = :spanStartMs ORDER BY ts")
    suspend fun span(spanStartMs: Long): List<BgInfillEntity>

    /** Null demotes. */
    @Query("UPDATE bg_infill SET promotedAtMs = :atMs WHERE spanStartMs = :spanStartMs")
    suspend fun markPromoted(spanStartMs: Long, atMs: Long?)

    /** The promoted guard is in the statement, not at the caller: this is the one removal a finger
     *  can reach, and demotion reads the span from this table. */
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

    /** Each span's FIRST SURVIVING row: the head row itself can be deleted by a measurement landing
     *  in its slot, and keying on `ts = spanStartMs` then loses the whole span. */
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

    /** `fittedAtMs > 0` excludes an imported or restored adapter: it was never fitted on this
     *  phone's record, and flagging one refuses it for ever under a rule the override cannot clear. */
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

    /** IGNORE, not the REPLACE [upsert] uses: a correction fitted on this phone's own matured
     *  forecasts must not be displaced by an archived one. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<ConformalDeltaEntity>): List<Long>
}

@Dao
interface ExerciseSessionDao {
    @Insert suspend fun insert(row: ExerciseSessionEntity): Long

    /** Column-scoped, not an upsert: `clientId`, `startMs` and `kind` are the bout's identity. A
     *  stop landing after the row was deleted updates nothing. */
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

    /** The -1 a conflict returns tells the restore which bouts it added — the only ones whose
     *  archived fixes may be applied, a held bout having its own track already. */
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

    /** `id` rides in the cursor because two fixes could share a millisecond, which a `tsMs`-only
     *  cursor would silently drop from the export. */
    @Query(
        "SELECT * FROM exercise_fix WHERE sessionId = :sessionId AND " +
            "(tsMs > :afterTs OR (tsMs = :afterTs AND id > :afterId)) ORDER BY tsMs, id LIMIT :limit",
    )
    suspend fun pageFrom(sessionId: Long, afterTs: Long, afterId: Long, limit: Int): List<ExerciseFixEntity>
}

/**
 * Keep-forever: dropping a tombstone re-opens the resurrection hole for any catch-up still reaching
 * that range, and the phone cannot know the server's retention.
 */
@Dao
interface EventTombstoneDao {
    @Upsert suspend fun upsert(row: EventTombstoneEntity)

    @Query("SELECT * FROM event_tombstone WHERE clientId = :clientId")
    suspend fun byClientId(clientId: String): EventTombstoneEntity?

    /** The tombstone term in the event high-water mark: without it, deleting the newest event walks
     *  the catch-up cursor backward and re-hydrates what was deleted. */
    @Query("SELECT MAX(tsMs) FROM event_tombstone")
    suspend fun latestTs(): Long?

    /** The connect-time replay's work list: a process death between the delete and the enqueue, or
     *  a tombstone the queue's size cap evicted. */
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
