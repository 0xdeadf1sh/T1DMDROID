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

    /**
     * Append a row under [dedupKey], first REPLACING any still-pending row already filed there;
     * returns the new row id, or -1 when an in-flight send owns the key (see below).
     *
     * [enqueue] is append-only, and its unique-key IGNORE means a second write under one key is
     * silently dropped — right for a row whose content is a pure function of its key (a logged event,
     * a dirty-marker), and wrong for one that is a SNAPSHOT of state that can still change. A
     * prediction batch is the latter: a cycle re-run inside the same 5-min slot recomputes the same
     * `pred:<cycleTs>` key with a different forecast, and the drop leaves the server on the older one.
     * Replacing keeps the invariant that made the key worth having — at most one push per cycle
     * queued — while making it the latest rather than the first.
     *
     * Losing the older payload is safe in exactly the way §7 of the shared invariants describes:
     * the write is an idempotent upsert ordered by `updated_at`, so the newer body subsumes the one
     * it displaced whether or not that one ever left the phone.
     */
    suspend fun enqueueReplacingPending(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long

    /**
     * Append a row under [dedupKey], first removing whatever is filed there in ANY state — PENDING
     * or INFLIGHT.
     *
     * [enqueueReplacingPending] deliberately spares an in-flight row, and that sparing is for the
     * Nightscout host: it has no idempotency key and no delete, so a request already on the wire
     * must stay recorded or the phone loses track of something that still lands. Neither reason
     * holds for the phone's own server. Its writes are keyed on `client_id` and ordered on
     * `updated_at`, so a superseded in-flight PUT that arrives late is simply corrected by the
     * newer body behind it — and an edit or a deletion that could not replace a queued create would
     * otherwise be dropped by the unique index and lost.
     *
     * Restricted to MEAL and DOSE for that reason. `QueueDrainer`'s post-send `delete`/`reschedule`
     * both no-op on a row already gone, so nothing else changes.
     */
    suspend fun enqueueSuperseding(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
        notBeforeMs: Long = 0L,
    ): Long
}
