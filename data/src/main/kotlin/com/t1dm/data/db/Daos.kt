package com.t1dm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Frozen DAO surface for the Room v1 schema (Phase 1). Signatures only — Room
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

    /** Retune one source's warm-up window in place. A column-scoped UPDATE, not an upsert: the row's
     *  identity, `addedAtMs` and `active` flag are untouched, so the edit cannot disturb the
     *  exactly-one-active invariant. */
    @Query("UPDATE cgm_source SET warmupWindowMin = :minutes WHERE sourceId = :sourceId")
    suspend fun setWarmupWindowMin(sourceId: String, minutes: Int)

    /** Full-erase (issue 5, app reset). Row-only DELETE — the schema/table is untouched. */
    @Query("DELETE FROM cgm_source")
    suspend fun deleteAll()

    /** One-shot read of the discovered set (archive export). */
    @Query("SELECT * FROM cgm_source ORDER BY addedAtMs")
    suspend fun all(): List<CgmSourceEntity>

    /** How many rows claim the exactly-one-active flag. Read before an archive restore so imported
     *  sources can be forced inactive rather than landing a SECOND active row and breaking §3.1. */
    @Query("SELECT COUNT(*) FROM cgm_source WHERE active = 1")
    suspend fun activeCount(): Int

    /** Merge insert (archive restore): a `sourceId` the phone already knows wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<CgmSourceEntity>): List<Long>
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

    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId ORDER BY tsMs DESC LIMIT 1")
    fun observeLatest(sourceId: String): Flow<CgmReadingEntity?>

    /** The reading at one grid slot for [sourceId], or null — the gap check for server-history
     *  hydration (a server row only fills a slot the phone has no local reading for). */
    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND tsMs = :ts LIMIT 1")
    suspend fun byTs(sourceId: String, ts: Long): CgmReadingEntity?

    /** Batch gap-fill for the sample→reading reconcile (server history that predates this build). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(readings: List<CgmReadingEntity>)

    @Query(
        "SELECT * FROM cgm_reading WHERE sourceId = :sourceId " +
            "ORDER BY tsMs DESC LIMIT :limit",
    )
    suspend fun recent(sourceId: String, limit: Int): List<CgmReadingEntity>

    /** Every reading (any source) in `[fromMs, toMs]` — the realized-BG series the accuracy
     *  aggregator pairs matured forecasts against (Phase 7C). Filtered/matched in Kotlin. */
    @Query("SELECT * FROM cgm_reading WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    suspend fun readingsInRange(fromMs: Long, toMs: Long): List<CgmReadingEntity>

    /**
     * Oldest / newest stamp this source holds; null when it holds nothing. The extent the start-day
     * picker bounds itself by — how far back there is anything to drive over.
     *
     * Two queries rather than one returning both columns: SQLite's MIN/MAX optimisation applies only
     * to a lone aggregate, and either one alone is a single seek down
     * `sqlite_autoindex_cgm_reading_1 (sourceId, tsMs)`. Asking for both at once forfeits it and scans.
     */
    @Query("SELECT MIN(tsMs) FROM cgm_reading WHERE sourceId = :sourceId")
    suspend fun oldestTs(sourceId: String): Long?

    @Query("SELECT MAX(tsMs) FROM cgm_reading WHERE sourceId = :sourceId")
    suspend fun newestTs(sourceId: String): Long?

    @Query("DELETE FROM cgm_reading")
    suspend fun deleteAll()

    /** Every source id that actually holds readings — the outer loop of the archive export, so a
     *  source discovered but never heard from costs no page. */
    @Query("SELECT DISTINCT sourceId FROM cgm_reading")
    suspend fun sourceIds(): List<String>

    /**
     * One keyset page of a source's readings, oldest-first, strictly after [afterTs].
     *
     * Keyset rather than OFFSET because the table is keep-forever and the export walks all of it:
     * each page is one seek down the `(sourceId, tsMs)` primary key, where OFFSET re-scans
     * everything it skips and turns a full export into a quadratic one. This is what lets the
     * writer hold a single page in the heap rather than a year of entities.
     */
    @Query("SELECT * FROM cgm_reading WHERE sourceId = :sourceId AND tsMs > :afterTs ORDER BY tsMs LIMIT :limit")
    suspend fun pageFrom(sourceId: String, afterTs: Long, limit: Int): List<CgmReadingEntity>

    /**
     * Merge insert (archive restore). IGNORE rather than the REPLACE [upsertAll] uses: a grid slot
     * the phone already holds is the authority (§3.1), so an archive may only ever FILL a gap and
     * never rewrite a reading. The returned rowid is -1 for each ignored row, which is how the
     * restore counts what it actually applied without a second read.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<CgmReadingEntity>): List<Long>
}


@Dao
interface SampleDao {
    @Upsert suspend fun upsert(sample: SampleEntity)

    @Query("SELECT * FROM sample WHERE ts = :ts")
    suspend fun byTs(ts: Long): SampleEntity?

    @Query("SELECT * FROM sample WHERE ts > :cursor ORDER BY ts LIMIT :limit")
    suspend fun page(cursor: Long, limit: Int): List<SampleEntity>

    /** Newest grid ts held locally, or null when the projection is empty — the forward cursor a
     *  fresh WS connect catches up from (only pull rows the phone is missing). */
    @Query("SELECT MAX(ts) FROM sample")
    suspend fun maxTs(): Long?

    /** [maxTs] as a Flow — the scalar `sample`-write signal ([T1dmRepository.observeSampleWrites]).
     *  Room's invalidation is per TABLE, so this emits on exactly the writes an `observeRange` over
     *  the projection emits on, without materialising a row; `ts` is the primary key, so the
     *  aggregate is one seek rather than a scan. */
    @Query("SELECT MAX(ts) FROM sample")
    fun observeMaxTs(): Flow<Long?>

    /** The `sample` rows carrying a BG that [sourceId] has no `cgm_reading` for — the gap set the
     *  sample→reading reconcile inserts, resolved in SQL instead of by diffing the whole projection
     *  against the whole ts column of the source in the heap. `NOT EXISTS` seeks the
     *  `(sourceId, tsMs)` primary key per candidate row, so a reconcile with nothing to do reads
     *  nothing back. */
    @Query(
        "SELECT * FROM sample WHERE bgMgdl IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM cgm_reading WHERE cgm_reading.sourceId = :sourceId AND cgm_reading.tsMs = sample.ts" +
            ") ORDER BY ts",
    )
    suspend fun bgSlotsMissingReading(sourceId: String): List<SampleEntity>

    /** One-shot windowed read (oldest-first) for the stats recompute (Phase 6). */
    @Query("SELECT * FROM sample WHERE ts BETWEEN :fromMs AND :toMs ORDER BY ts")
    suspend fun rangeList(fromMs: Long, toMs: Long): List<SampleEntity>

    /** Steps summed over a window — the aggregate the widget and the BG panel actually asked for, in
     *  place of [rangeList]'s whole rows summed in Kotlin. `SUM` skips NULL buckets exactly as the
     *  Kotlin `?: 0` did, and `COALESCE` gives an empty window the 0 an empty list summed to. */
    @Query("SELECT COALESCE(SUM(steps), 0) FROM sample WHERE ts BETWEEN :fromMs AND :toMs")
    suspend fun stepsInRange(fromMs: Long, toMs: Long): Int

    /** The most recent non-null mood — what the Logs panel's picker shows as the current selection. */
    @Query("SELECT mood FROM sample WHERE mood IS NOT NULL ORDER BY ts DESC LIMIT 1")
    fun observeLatestMood(): Flow<Int?>

    @Query("DELETE FROM sample")
    suspend fun deleteAll()

    /**
     * Merge insert (archive restore). The wide row is a PROJECTION — its BG comes from
     * `cgm_reading` and its steps/mood from the sensors and the Logs panel — so a slot the phone
     * has already projected is better evidence than an archived copy of it, and IGNORE keeps it.
     */
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

    /** Id-keyed hydration insert (§3.4): a redelivered event conflicts on the unique `clientId`
     *  index and is IGNOREd, so a server catch-up never duplicates a phone-authored dose. Returns
     *  the new rowid, or -1 when the clientId already exists. Never re-projects into `sample`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(dose: LoggedDoseEntity): Long

    /** Event window read for curve/channel reconstruction (SPEC §3.3) and event-range hydration /
     *  re-mirror (§3.4/§3.8). Ordered oldest-first. */
    @Query("SELECT * FROM logged_dose WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    suspend fun inRange(fromMs: Long, toMs: Long): List<LoggedDoseEntity>

    @Query("SELECT * FROM logged_dose WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<LoggedDoseEntity>>

    /** Timestamp of the most recent logged insulin dose (IOB provenance, §3.6-F); null = none. Also
     *  the dose half of the event high-water mark `T1dmRepository.newestEventTs()` (§3.5), max'd with
     *  [LoggedMealDao.latestTs] — the WS catch-up pulls only meal/dose history newer than it. */
    @Query("SELECT MAX(tsMs) FROM logged_dose")
    suspend fun latestTs(): Long?

    /** Batch gap-fill for the sample→dose reconcile (server bolus history that predates this build). */
    @Insert
    suspend fun insertAll(doses: List<LoggedDoseEntity>)

    /** The newest [limit] doses, newest first — the insulin half of the Logs panel's feed. Bounded at
     *  the QUERY rather than by tailing a whole-store observer: the store is keep-forever. */
    @Query("SELECT * FROM logged_dose ORDER BY tsMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LoggedDoseEntity>>

    @Query("DELETE FROM logged_dose WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM logged_dose")
    suspend fun deleteAll()

    /** One keyset page for the archive export, oldest-first. The cursor is the `(tsMs, id)` pair
     *  [observeRecent] already orders by, because `tsMs` alone is not unique — two doses logged in
     *  the same millisecond would make a `tsMs`-only cursor skip one or loop on it forever. */
    @Query(
        "SELECT * FROM logged_dose WHERE tsMs > :afterTs OR (tsMs = :afterTs AND id > :afterId) " +
            "ORDER BY tsMs, id LIMIT :limit",
    )
    suspend fun pageFrom(afterTs: Long, afterId: Long, limit: Int): List<LoggedDoseEntity>

    /** Batched [insertIgnore] for the archive restore — the same unique-`clientId` merge, one
     *  statement per page rather than per row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<LoggedDoseEntity>): List<Long>
}

@Dao
interface LoggedMealDao {
    @Insert suspend fun insert(meal: LoggedMealEntity): Long

    @Upsert suspend fun upsert(meal: LoggedMealEntity)

    /** Id-keyed hydration insert (§3.4): a redelivered event conflicts on the unique `clientId`
     *  index and is IGNOREd, so a server catch-up never duplicates a phone-authored meal. Returns
     *  the new rowid, or -1 when the clientId already exists. Never re-projects into `sample`. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(meal: LoggedMealEntity): Long

    /** Timestamp of the most recent logged meal; null = none. The meal half of the event high-water
     *  mark `T1dmRepository.newestEventTs()` (§3.5), max'd with [LoggedDoseDao.latestTs]. */
    @Query("SELECT MAX(tsMs) FROM logged_meal")
    suspend fun latestTs(): Long?

    /** Event window read for curve/channel reconstruction and event-range hydration / re-mirror
     *  (§3.4/§3.8). Ordered oldest-first. */
    @Query("SELECT * FROM logged_meal WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    suspend fun inRange(fromMs: Long, toMs: Long): List<LoggedMealEntity>

    @Query("SELECT * FROM logged_meal WHERE tsMs BETWEEN :fromMs AND :toMs ORDER BY tsMs")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<LoggedMealEntity>>

    /** Batch gap-fill for the sample→meal reconcile (server carb history that predates this build). */
    @Insert
    suspend fun insertAll(meals: List<LoggedMealEntity>)

    /** The newest [limit] meals, newest first — the carb half of the Logs panel's feed. */
    @Query("SELECT * FROM logged_meal ORDER BY tsMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LoggedMealEntity>>

    @Query("DELETE FROM logged_meal WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * The last [limit] DISTINCT `(grams, gi)` meals with a non-null GI — the "recent meals"
     * quick-picks (Phase 7C, item 9). `GROUP BY grams, gi` collapses repeats; `MAX(tsMs)` orders by
     * the most-recent occurrence. Meals logged via the multi-food builder (gi IS NULL, custom curve)
     * are excluded — the simple carb form cannot round-trip them.
     */
    @Query(
        "SELECT grams AS grams, gi AS gi FROM logged_meal WHERE gi IS NOT NULL " +
            "GROUP BY grams, gi ORDER BY MAX(tsMs) DESC LIMIT :limit",
    )
    fun observeRecentDistinct(limit: Int): Flow<List<RecentMealRow>>

    @Query("DELETE FROM logged_meal")
    suspend fun deleteAll()

    /** One keyset page for the archive export; see [LoggedDoseDao.pageFrom] on the `(tsMs, id)` cursor. */
    @Query(
        "SELECT * FROM logged_meal WHERE tsMs > :afterTs OR (tsMs = :afterTs AND id > :afterId) " +
            "ORDER BY tsMs, id LIMIT :limit",
    )
    suspend fun pageFrom(afterTs: Long, afterId: Long, limit: Int): List<LoggedMealEntity>

    /** Batched [insertIgnore] for the archive restore (unique `clientId` merge). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<LoggedMealEntity>): List<Long>
}

/** Projection for [LoggedMealDao.observeRecentDistinct] — just the two fields the carb form binds. */
data class RecentMealRow(val grams: Double, val gi: Double?)

@Dao
interface BasalScheduleDao {
    @Insert suspend fun insert(row: BasalScheduleEntity): Long

    @Insert suspend fun insertAll(rows: List<BasalScheduleEntity>)

    /** The active schedule's injections (one BasalSchedule), ordered by time-of-day. */
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

    /** Every injection of every schedule (archive export). Bounded by construction — a schedule is
     *  a day's worth of rows and there are a handful of them — so this needs no paging. */
    @Query("SELECT * FROM basal_schedule ORDER BY scheduleId, timeOfDayMin")
    suspend fun all(): List<BasalScheduleEntity>

    /** The schedule ids already present — the archive restore's merge key. A schedule is restored
     *  WHOLE or not at all: its rows have no stable per-row identity, so merging them injection by
     *  injection could interleave two schedules into one that the user never authored. */
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

    /** The row behind a rowid handed out by [enqueue], or null once it has drained. There is no SENT
     *  state — `QueueDrainer` DELETEs on HTTP success — so *absent* is the only "already sent" signal
     *  there is, and it is indistinguishable from evicted. Read before an undo withdraws the push, so
     *  the receipt can say which of the two happened instead of guessing (see
     *  `T1dmRepository.withdrawPush`). This returns the whole row rather than just the state so that
     *  the dedupKey cross-check there is possible. */
    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: Long): OutboxEntity?

    /** The row filed under [dedupKey] (the index is unique), or null once it has drained. The Logs
     *  panel's delete path resolves a push this way rather than by rowid: the enqueue rowid was handed
     *  to a snackbar minutes-to-days ago and is long forgotten, whereas the dedupKey is a pure function
     *  of the event's `client_id`. Read INSIDE the deleting transaction — see
     *  `T1dmRepository.deleteCommittedMeal` — so a concurrent drain cannot land between the two. */
    @Query("SELECT * FROM outbox WHERE dedupKey = :dedupKey")
    suspend fun byDedupKey(dedupKey: String): OutboxEntity?

    /** Every queued dedupKey of the given [kinds]. There is no SENT state (this queue DELETEs on a
     *  2xx), so membership of this set is the whole of "the server has not accepted it yet" — which is
     *  what the Logs panel's committed/delivered split is read from. Table-scoped Room invalidation
     *  means this re-emits on any outbox write, so callers should collapse equal emissions. */
    @Query("SELECT dedupKey FROM outbox WHERE kind IN (:kinds)")
    fun observeDedupKeys(kinds: List<OutboxKind>): Flow<List<String>>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Delete the row filed under [dedupKey] ONLY while it is still in [state]; returns the rows
     * removed (0 or 1, the index being unique). The replace-the-pending-push half of
     * `T1dmRepository.enqueueReplacingPending` — see there for why a row already claimed INFLIGHT is
     * deliberately left alone rather than swept with it.
     */
    @Query("DELETE FROM outbox WHERE dedupKey = :dedupKey AND state = :state")
    suspend fun deleteByDedupKeyInState(dedupKey: String, state: OutboxState): Int

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    /** Reclaim rows wedged in INFLIGHT by a crash mid-send, back to PENDING for the next drain. */
    @Query("UPDATE outbox SET state = :to WHERE state = :from")
    suspend fun resetState(from: OutboxState, to: OutboxState): Int

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, nextAttemptMs = :nextAttemptMs WHERE id = :id")
    suspend fun reschedule(id: Long, state: OutboxState, attempts: Int, nextAttemptMs: Long)

    /** CONDITIONAL state change; returns the rows actually updated (0 or 1). `QueueDrainer` takes a
     *  whole batch of PENDING rows in one snapshot and then spends an HTTP round trip per row, so a
     *  row can sit in that snapshot for minutes while still PENDING on disk — long enough for an undo
     *  to withdraw it. Claiming through this rather than the unconditional [reschedule] is what makes
     *  INFLIGHT the mutual-exclusion token `T1dmRepository.withdrawPush` already assumes it is: a zero
     *  return means the row was deleted (or claimed) underneath the snapshot and must not be sent. */
    @Query("UPDATE outbox SET state = :to WHERE id = :id AND state = :from")
    suspend fun claim(id: Long, from: OutboxState, to: OutboxState): Int

    /** Full-erase (issue 5): drop the entire queue (distinct from the id-list [deleteAll]). */
    @Query("DELETE FROM outbox")
    suspend fun deleteAllRows()
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

    /** Drop every forecast row of a removed model (Phase 7C model deletion). */
    @Query("DELETE FROM prediction WHERE modelId = :modelId")
    suspend fun deleteByModel(modelId: String)

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

    /** One-shot read of every profile (archive export). The `rw` token is NOT here — it lives in
     *  the Keystore-backed store keyed by [ServerProfileEntity.id] — so this cannot leak the secret
     *  into a backup file, which is exactly why the schema was built this way. */
    @Query("SELECT * FROM server_profile ORDER BY createdAtMs")
    suspend fun all(): List<ServerProfileEntity>

    /** See [CgmSourceDao.activeCount] — the same exactly-one-active guard on the restore path. */
    @Query("SELECT COUNT(*) FROM server_profile WHERE active = 1")
    suspend fun activeCount(): Int

    /** Merge insert (archive restore): a profile `id` the phone already holds wins. */
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

    /** Every kv row (Phase 7C item 17 — versioned config export). Ordered for a stable dump. */
    @Query("SELECT * FROM kv ORDER BY `key`")
    suspend fun all(): List<KvEntity>

    @Upsert suspend fun putAll(entries: List<KvEntity>)

    /** Full-erase (issue 5): drops every setting (readers fall back to coded defaults) AND the watch
     *  pairing/epoch/nonce-ceiling rows, so a later re-pair cannot reuse a (key, nonce) pair. */
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

    /**
     * Full-text search over `food_fts` (external-content FTS5 on `food`), ranked by relevance.
     * [match] is a raw FTS5 MATCH expression (the repository appends `*` for prefix search).
     * `@SkipQueryVerification` because `food_fts` is a hand-rolled virtual table Room does not
     * model as an entity (created in [MigrationRunner.MIGRATION_4_5] + the DB `onCreate` callback).
     */
    @SkipQueryVerification
    @Query(
        "SELECT food.* FROM food JOIN food_fts ON food.id = food_fts.rowid " +
            "WHERE food_fts MATCH :match ORDER BY rank LIMIT :limit",
    )
    suspend fun search(match: String, limit: Int): List<FoodEntity>

    /** Alphabetical browse (empty-query fallback). */
    @Query("SELECT * FROM food ORDER BY name LIMIT :limit")
    suspend fun all(limit: Int): List<FoodEntity>

    @Query("SELECT * FROM food WHERE custom = 1 ORDER BY updatedAt DESC")
    fun observeCustom(): Flow<List<FoodEntity>>

    /** Only a user-added food may be deleted; seed rows are immutable. */
    @Query("DELETE FROM food WHERE id = :id AND custom = 1")
    suspend fun deleteCustom(id: Long)

    /** Full-erase (issue 5): drop every USER-added food; the shipped seed dictionary is kept so the
     *  store returns to its first-run contents. The `food_ad` trigger keeps `food_fts` in lockstep. */
    @Query("DELETE FROM food WHERE custom = 1")
    suspend fun deleteAllCustom()

    /** Every user-added food (archive export). Seed rows are excluded: they ship with the APK, so
     *  archiving them would bloat the file with a dictionary the restoring install already has. */
    @Query("SELECT * FROM food WHERE custom = 1 ORDER BY id")
    suspend fun allCustom(): List<FoodEntity>

    /** `name`/`brand` pairs already held — the archive restore's merge key for custom foods, which
     *  carry no stable id across installs (their `id` is autogenerated per device). */
    @Query("SELECT name, brand FROM food WHERE custom = 1")
    suspend fun customKeys(): List<FoodKeyRow>
}

/** Projection for [FoodDao.customKeys] — the natural key a custom food is deduplicated on. */
data class FoodKeyRow(val name: String, val brand: String?)

@Dao
interface SavedMealDao {
    @Insert suspend fun insertMeal(meal: SavedMealEntity): Long

    @Insert suspend fun insertItems(items: List<SavedMealItemEntity>)

    /**
     * In-place edit of a saved-meal header; returns rows affected, which is 0 exactly when the meal
     * was deleted out from under the editor (the repository refuses to write item rows then — with no
     * foreign key on `saved_meal_item` they would be permanent orphans).
     *
     * The caller issues this even when [name] is unchanged: [observeMeals] selects from `saved_meal`
     * alone, so Room's invalidation tracker never sees an edit confined to the item rows and the
     * saved-meals list would stay stale until something else touched the header.
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

    /** Every portion row across every saved meal (archive export) — one read instead of an
     *  [itemsOf] per meal. Ordered by `mealId` so the writer can emit each meal's items together
     *  without re-sorting. */
    @Query("SELECT * FROM saved_meal_item ORDER BY mealId, id")
    suspend fun allItems(): List<SavedMealItemEntity>

    /** Saved-meal names already held — the archive restore's merge key (the `id` is per-device). */
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

    /** Full-erase (issue 5): drop every USER-added type; the builtin presets are kept (first-run). */
    @Query("DELETE FROM insulin_type WHERE builtin = 0")
    suspend fun deleteAllCustom()

    /** Every user-defined type (archive export). The builtin presets are seeded by the install and
     *  so are excluded, exactly as the seed foods are. */
    @Query("SELECT * FROM insulin_type WHERE builtin = 0 ORDER BY id")
    suspend fun allCustom(): List<InsulinTypeEntity>

    /** Names of the custom types already held — the archive restore's merge key. */
    @Query("SELECT name FROM insulin_type WHERE builtin = 0")
    suspend fun customNames(): List<String>
}

@Dao
interface PaintStrokeDao {
    @Insert suspend fun insert(stroke: PaintStrokeEntity): Long

    /**
     * Every stroke whose time span intersects the visible window — the sole read this table has, and
     * the reason `minTsMs`/`maxTsMs` are hoisted out of the points BLOB and indexed. Intersection, not
     * containment: a stroke drawn wider than the current window (or scrolled half off it) must still
     * be drawn, so the test is `maxTsMs >= from AND minTsMs <= to`, inclusive at both ends (a stroke
     * touching the window by a single instant counts). Ordered by authoring time so later strokes
     * paint over earlier ones; `id` breaks ties within a millisecond.
     */
    @Query(
        "SELECT * FROM bg_paint_stroke WHERE maxTsMs >= :fromMs AND minTsMs <= :toMs " +
            "ORDER BY createdAtMs, id",
    )
    fun observeOverlapping(fromMs: Long, toMs: Long): Flow<List<PaintStrokeEntity>>

    @Query("DELETE FROM bg_paint_stroke WHERE id = :id")
    suspend fun delete(id: Long)

    /** Full-erase (issue 5, app reset). Row-only DELETE — the schema/table is untouched. */
    @Query("DELETE FROM bg_paint_stroke")
    suspend fun deleteAll()

    /** One keyset page for the archive export. The rowid is the cursor here rather than
     *  `createdAtMs`: strokes are immutable and inserted in authoring order, so `id` is already the
     *  order the writer wants and it is unique, which `createdAtMs` is not guaranteed to be. */
    @Query("SELECT * FROM bg_paint_stroke WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageFrom(afterId: Long, limit: Int): List<PaintStrokeEntity>

    /** Every authoring instant already held — the archive restore's merge key, the same one the
     *  settings-and-drawings import has always deduplicated on. */
    @Query("SELECT createdAtMs FROM bg_paint_stroke")
    suspend fun allCreatedAt(): List<Long>

    /** Batched insert for the archive restore; the caller has already filtered by [allCreatedAt]. */
    @Insert
    suspend fun insertAll(rows: List<PaintStrokeEntity>)
}

@Dao
interface ConformalDeltaDao {
    /** One row per model; a later fit REPLACEs it whole. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(delta: ConformalDeltaEntity)

    @Query("SELECT * FROM conformal_delta WHERE modelId = :modelId")
    suspend fun get(modelId: String): ConformalDeltaEntity?

    /** Every stored correction, for the in-memory map the display path reads per frame. */
    @Query("SELECT * FROM conformal_delta")
    fun observeAll(): Flow<List<ConformalDeltaEntity>>

    /** Drop a removed model's correction with the model (mirrors `PredictionDao.deleteByModel`). */
    @Query("DELETE FROM conformal_delta WHERE modelId = :modelId")
    suspend fun deleteByModel(modelId: String)

    /** Full-erase (issue 5, app reset). Row-only DELETE — the schema/table is untouched. */
    @Query("DELETE FROM conformal_delta")
    suspend fun deleteAll()

    /** One-shot read of every correction (archive export) — one row per model, so it is bounded. */
    @Query("SELECT * FROM conformal_delta")
    suspend fun all(): List<ConformalDeltaEntity>

    /**
     * Merge insert (archive restore). IGNORE rather than the REPLACE [upsert] uses: a correction
     * fitted on THIS phone's own matured forecasts describes this phone, and an archived one — very
     * possibly older, and fitted against a history this install has not lived — must not displace it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<ConformalDeltaEntity>): List<Long>
}
