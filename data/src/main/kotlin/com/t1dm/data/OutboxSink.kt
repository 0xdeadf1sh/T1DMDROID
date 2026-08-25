package com.t1dm.data

import com.t1dm.data.db.OutboxKind

/** The append-only outbox seam; [T1dmRepository] is the production binding. */
interface OutboxSink {
    /**
     * Append a deduped row (unique `dedupKey` ⇒ IGNORE); returns the row id, or -1 on dedup.
     * [notBeforeMs] is the earliest instant the drainer may attempt the row; 0 = eligible at once.
     */
    suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long

    /**
     * Append under [dedupKey], first REPLACING any still-pending row there; returns the row id, or
     * -1 when an in-flight send owns the key. For a payload that is a SNAPSHOT of changing state,
     * where [enqueue]'s IGNORE would keep the stale first one.
     */
    suspend fun enqueueReplacingPending(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long

    /**
     * Append under [dedupKey], first removing whatever is filed there in ANY state — PENDING or
     * INFLIGHT. MEAL and DOSE only: their writes are keyed on `client_id` and ordered on
     * `updated_at`, so a superseded in-flight PUT arriving late is corrected by the newer body.
     */
    suspend fun enqueueSuperseding(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long
}
