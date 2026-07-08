package com.t1dm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Frozen DAO surface for the Room v1 schema (PLAN.private.md Phase 1). Signatures only — Room
 * generates the bodies once the @Database (Data implementer) references these. Observable reads
 * return [Flow]; mutations are `suspend` (callers dispatch on IO).
 */

@Dao
interface CgmSourceDao {
    @Upsert suspend fun upsert(source: CgmSourceEntity)

    @Query("SELECT * FROM cgm_source ORDER BY addedAtMs")
    fun observeAll(): Flow<List<CgmSourceEntity>>

    @Query("SELECT * FROM cgm_source WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<CgmSourceEntity?>

    @Query("SELECT * FROM cgm_source WHERE sourceId = :sourceId")
    suspend fun byId(sourceId: String): CgmSourceEntity?

    /** One-shot active-source lookup for the in-transaction sample projection (additive to the
     *  frozen [observeActive] Flow). */
    @Query("SELECT sourceId FROM cgm_source WHERE active = 1 LIMIT 1")
    suspend fun activeSourceId(): String?

    /** Exactly-one-active invariant: clear all, then set the chosen row. Run in a @Transaction. */
    @Query("UPDATE cgm_source SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE cgm_source SET active = 1 WHERE sourceId = :sourceId")
    suspend fun setActive(sourceId: String)
}

@Dao
interface CgmReadingDao {
    /** Grid-stamp upsert on `(sourceId, tsMs)` (PLAN.private.md §3.1). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reading: CgmReadingEntity)

    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId " +
            "AND tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs",
    )
    fun observeRange(sourceId: String, fromMs: Long, toMs: Long): Flow<List<CgmReadingEntity>>

    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId ORDER BY tsMs DESC LIMIT 1")
    fun observeLatest(sourceId: String): Flow<CgmReadingEntity?>

    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId " +
            "ORDER BY tsMs DESC LIMIT :limit",
    )
    suspend fun recent(sourceId: String, limit: Int): List<CgmReadingEntity>
}

@Dao
interface SampleDao {
    @Upsert suspend fun upsert(sample: SampleEntity)

    @Query("SELECT * FROM sample WHERE ts BETWEEN :fromMs AND :toMs ORDER BY ts")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<SampleEntity>>

    @Query("SELECT * FROM sample WHERE ts = :ts")
    suspend fun byTs(ts: Long): SampleEntity?

    @Query("SELECT * FROM sample WHERE ts > :cursor ORDER BY ts LIMIT :limit")
    suspend fun page(cursor: Long, limit: Int): List<SampleEntity>
}

@Dao
interface DoseEventDao {
    @Insert suspend fun insert(dose: DoseEventEntity): Long

    @Upsert suspend fun upsert(dose: DoseEventEntity)

    @Query("SELECT * FROM dose_event WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<DoseEventEntity>>
}

@Dao
interface CgmAdvertRawDao {
    @Insert suspend fun insert(advert: CgmAdvertRawEntity): Long

    @Query("DELETE FROM cgm_advert_raw WHERE rxWallMs < :beforeMs")
    suspend fun pruneBefore(beforeMs: Long): Int
}

/** Lightweight projection for size/age eviction — priority is a Kotlin concern (see `:sync`). */
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

    /** Oldest enqueue time across the queue, for the Network panel's age-vs-bound read (null = empty). */
    @Query("SELECT MIN(createdAtMs) FROM outbox")
    suspend fun oldestCreatedAt(): Long?

    /** Oldest-first over the whole queue (bounded by the configured max size); priority-ranked and
     *  trimmed in Kotlin because Android SQLite lacks `DELETE … ORDER BY … LIMIT`. */
    @Query("SELECT id, kind, createdAtMs FROM outbox ORDER BY createdAtMs, id")
    suspend fun evictionRows(): List<OutboxEvictRow>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    /** Reclaim rows wedged in INFLIGHT by a crash mid-send, back to PENDING for the next drain. */
    @Query("UPDATE outbox SET state = :to WHERE state = :from")
    suspend fun resetState(from: OutboxState, to: OutboxState): Int

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, nextAttemptMs = :nextAttemptMs WHERE id = :id")
    suspend fun reschedule(id: Long, state: OutboxState, attempts: Int, nextAttemptMs: Long)
}

@Dao
interface PredictionDao {
    /** One row per `(madeAtMs, modelId)`; a re-run of the same cycle REPLACEs in place. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prediction: PredictionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(predictions: List<PredictionEntity>)

    /** Every model's prediction at the most recent cycle, selected model first (overlay rehydrate). */
    @Query(
        "SELECT * FROM prediction WHERE madeAtMs = (SELECT MAX(madeAtMs) FROM prediction) " +
            "ORDER BY selected DESC, modelId",
    )
    suspend fun latestCycle(): List<PredictionEntity>

    @Query("SELECT * FROM prediction WHERE madeAtMs BETWEEN :fromMs AND :toMs ORDER BY madeAtMs DESC, modelId")
    suspend fun range(fromMs: Long, toMs: Long): List<PredictionEntity>

    /** The single newest prediction row, for a glanceable "latest forecast" observer. */
    @Query("SELECT * FROM prediction ORDER BY madeAtMs DESC, selected DESC LIMIT 1")
    fun observeLatest(): Flow<PredictionEntity?>
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
}

@Dao
interface KvDao {
    @Upsert suspend fun put(entry: KvEntity)

    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM kv WHERE `key` = :key")
    fun observe(key: String): Flow<String?>
}

@Dao
interface HwTelemetryDao {
    @Insert suspend fun insert(row: HwTelemetryEntity): Long

    @Query(
        "SELECT * FROM hw_telemetry WHERE (:modelId IS NULL OR modelId = :modelId) " +
            "AND tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs",
    )
    suspend fun range(modelId: String?, fromMs: Long, toMs: Long): List<HwTelemetryEntity>
}
