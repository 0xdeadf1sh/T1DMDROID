package com.t1dm.data

import com.t1dm.data.db.OutboxKind

/** The append-only outbox seam; [T1dmRepository] is the production binding. */
interface OutboxSink {
    /** dedupKey unique -> IGNORE; returns row id, -1 if deduped. notBeforeMs 0 = eligible now. */
    suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long
}
