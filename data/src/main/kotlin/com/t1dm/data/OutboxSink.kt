package com.t1dm.data

import com.t1dm.data.db.OutboxKind

/** The append-only outbox seam; [T1dmRepository] is the production binding. */
interface OutboxSink {
    /** Append deduped (unique dedupKey ⇒ IGNORE), -1 on dedup; notBeforeMs=0 = eligible at once. */
    suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long

    /** Append under dedupKey, REPLACING any pending row; -1 if in-flight. For SNAPSHOT payloads. */
    suspend fun enqueueReplacingPending(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long

    /** Append under dedupKey, removing ANY state; MEAL/DOSE only, keyed by client_id. */
    suspend fun enqueueSuperseding(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long
}
