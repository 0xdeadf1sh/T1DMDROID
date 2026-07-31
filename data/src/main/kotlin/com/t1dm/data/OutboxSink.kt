package com.t1dm.data

import com.t1dm.data.db.OutboxKind

/**
 * The append-only outbox seam the `:sync` [com.t1dm.sync.OutboxEnqueuer] writes through. Extracted
 * as an interface so the enqueuer — and its host-JVM unit tests — depend on this one method rather
 * than the whole Room-backed [T1dmRepository]. The production binding is [T1dmRepository] itself;
 * a test supplies an in-memory double.
 */
interface OutboxSink {
    /**
     * Append a deduped outbox row (unique `dedupKey` ⇒ IGNORE); returns the row id, or -1 on dedup.
     *
     * [notBeforeMs] is the earliest instant the drainer may attempt this row. It lands in
     * `nextAttemptMs`, which `OutboxDao.dueBatch` already filters on, so a future value holds the row
     * back with no new machinery and no new state — and a hold that outlives the process survives it,
     * the bound being persisted rather than timed in memory. `0` (the default) means eligible at once,
     * which is what every kind but a freshly logged meal/dose wants; only the enqueuer knows which is
     * which, hence a parameter rather than a rule buried here.
     */
    suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long
}
