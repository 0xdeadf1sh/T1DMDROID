package com.t1dm.data

import com.t1dm.data.db.OutboxKind

/**
 * The append-only outbox seam the `:sync` [com.t1dm.sync.OutboxEnqueuer] writes through. Extracted
 * as an interface so the enqueuer — and its host-JVM unit tests — depend on this one method rather
 * than the whole Room-backed [T1dmRepository]. The production binding is [T1dmRepository] itself;
 * a test supplies an in-memory double.
 */
interface OutboxSink {
    /** Append a deduped outbox row (unique `dedupKey` ⇒ IGNORE); returns the row id, or -1 on dedup. */
    suspend fun enqueue(kind: OutboxKind, dedupKey: String, payload: ByteArray, nowMs: Long): Long
}
