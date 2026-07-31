package com.t1dm.sync

/**
 * The kv keys the §3.8 store-epoch handshake keeps its state under. One copy of each string, here:
 * the gate that RECORDS [MIRRORED_EPOCH] is [CatchUpCoordinator], the walk that earns it lives at the
 * `:app` composition root (it needs the outbox enqueuers and the entity→DTO mappers), and a second
 * spelling in either place is a divergence with no way to notice itself.
 */
object ReMirrorKeys {
    /** The last server `store_epoch` whose history the phone has seen DELIVERED. */
    const val MIRRORED_EPOCH = "sync.mirrored_epoch"

    /** The `store_epoch` the in-flight walk is being raised against. */
    const val PENDING_EPOCH = "sync.mirror_pending_epoch"

    /** `createdAtMs` carried by every row the in-flight walk's event/stats phase enqueued. */
    const val WALK_STAMP = "sync.mirror_walk_stamp"

    /** The [WALK_STAMP] whose event/stats phase has been raised in full AND seen delivered. */
    const val EVENTS_STAMP = "sync.mirror_events_stamp"

    /** Which store the in-flight walk is being sent to — see [ReMirrorLedger]. */
    const val WALK_STORE = "sync.mirror_walk_store"

    /** Exclusive `ts` cursor: every scalar page at or before it has been proved delivered. */
    const val SCALAR_CURSOR = "sync.mirror_scalar_cursor"
}

/**
 * Where a resumed §3.8 walk stands, as [ReMirrorLedger.resume] found it.
 */
data class ReMirrorWalk(
    /** The `createdAtMs` the event/stats phase stamps its rows with, and the instant the eviction
     *  horizon is measured from. */
    val stampMs: Long,
    /** Resume the scalar page walk from here (exclusive). 0 = from the start of the local history. */
    val scalarCursor: Long,
    /** Whether the meal/dose/stats phase must be (re-)enqueued: a walk against a different store
     *  epoch, a walk whose stamp can no longer be trusted, or the first walk ever. */
    val raiseEvents: Boolean,
)

/**
 * The persisted bookkeeping behind the §3.8 (H7) re-mirror: which store epoch the phone is mid-walk
 * on, how far the scalar walk has provably got, and whether what it enqueued has actually left.
 *
 * It exists apart from the walk itself because the walk needs `:app` (outbox enqueuers, DTO mappers)
 * while every judgement here is a handful of kv reads and one `MIN(createdAtMs)` — so this part is
 * testable, and it is the part where getting it wrong loses a patient's history silently.
 *
 * **Delivery is inferred from absence, and absence has three causes.** The outbox has no SENT state
 * ([com.t1dm.data.db.OutboxState] is `{PENDING, INFLIGHT, FAILED}`); [QueueDrainer] DELETEs a row on
 * HTTP success, on a permanent 4xx, and on eviction alike. So "no row is as old as the walk" reads as
 * delivered, and two of those three causes make that a lie:
 *
 *  - **Eviction.** `QueueDrainer.evict()` runs at the head of every pass and drops `ageEvictable`
 *    kinds (INGEST, STATS) past [DrainConfig.maxAgeMs]. [delivered] therefore refuses to promote a
 *    walk that has aged that far without being seen delivered — including one facing an *empty*
 *    queue, since a walk whose markers were all evicted during an outage would otherwise read as
 *    complete without a single row being examined. The caller re-raises it instead, which is cheap
 *    and total: the local `sample` table is never pruned (its only DELETE is the full wipe), so every
 *    evicted marker regenerates. Re-mirroring is the sole cure for an evicted row, and banking the
 *    epoch is what would foreclose it.
 *  - **A different store.** [SyncHttpClient] resolves the endpoint per request and an outbox row
 *    carries no store identity, so repointing the active profile mid-drain sends one store's history
 *    to another. [resume] records an opaque [storeIdentity] with the walk and [delivered] re-reads it
 *    at the moment of promotion; a walk whose target moved is discarded rather than credited, and so
 *    is the scalar prefix it banked — the prefix is proved against a (store, epoch) pair, and crediting
 *    it to a store that never received it is the one error here that can never be retried. The identity
 *    is expected to carry the profile's last-edit stamp, so a profile repointed away and back again
 *    *within* one pass still reads as a different store on the return and starts the walk over.
 *
 * A permanent 4xx does still read as delivered. That is deliberate: the drainer logs it, and a
 * request the server refuses on its merits will be refused again.
 */
class ReMirrorLedger(
    private val getKv: suspend (String) -> String?,
    private val putKv: suspend (String, String, Long) -> Unit,
    /** `MIN(createdAtMs)` over the whole outbox; null when it is empty. */
    private val oldestQueuedAtMs: suspend () -> Long?,
    /** [DrainConfig.maxAgeMs] — past this an age-evictable row's absence stops meaning "sent". */
    private val maxQueueAgeMs: Long,
) {
    /**
     * Claim a walk against [serverEpoch] and [storeIdentity] and say where it stands. Called at the
     * head of every pass; it either resumes the walk already on disk or raises a new one, and either
     * way the state it returns is the state it has just persisted.
     *
     * **Nothing is taken on trust that was not banked.** [ReMirrorWalk.raiseEvents] stays true until
     * [bankEvents] records that the event/stats phase was raised in full *and* seen out of the queue,
     * so a pass killed halfway through enqueuing meals does not leave the remainder unmentioned for a
     * week; it simply raises them again under a fresh stamp, idempotently, on the next connect.
     *
     * The scalar cursor survives an aged-out walk, but not a move of the target. Its prefix is proved
     * page by page against a (store, epoch) PAIR, and that proof does not expire with the walk stamp —
     * but neither half of the pair may change under it. The epoch alone will not do: it is read once,
     * at the head of the pass, while [SyncHttpClient] resolves the endpoint per request, so pages banked
     * after a mid-pass repoint were proved against a store the epoch does not name. Banking is the one
     * act here that permanently forecloses re-sending, so it is discarded on any doubt and the walk
     * restarts at the beginning of the local history, which costs a drain and loses nothing.
     */
    suspend fun resume(serverEpoch: String, storeIdentity: String, nowMs: Long): ReMirrorWalk {
        val sameEpoch = getKv(ReMirrorKeys.PENDING_EPOCH) == serverEpoch
        val sameStore = getKv(ReMirrorKeys.WALK_STORE) == storeIdentity
        val sameTarget = sameEpoch && sameStore
        val stamp = getKv(ReMirrorKeys.WALK_STAMP)?.toLongOrNull()
        val landedStamp = getKv(ReMirrorKeys.EVENTS_STAMP)?.toLongOrNull()
        val cursor = if (sameTarget) getKv(ReMirrorKeys.SCALAR_CURSOR)?.toLongOrNull() ?: 0L else 0L

        if (sameTarget && stamp != null && landedStamp == stamp && nowMs - stamp < maxQueueAgeMs) {
            return ReMirrorWalk(stampMs = stamp, scalarCursor = cursor, raiseEvents = false)
        }
        if (!sameTarget) putKv(ReMirrorKeys.SCALAR_CURSOR, "0", nowMs)
        putKv(ReMirrorKeys.WALK_STAMP, nowMs.toString(), nowMs)
        putKv(ReMirrorKeys.WALK_STORE, storeIdentity, nowMs)
        putKv(ReMirrorKeys.PENDING_EPOCH, serverEpoch, nowMs)
        return ReMirrorWalk(stampMs = nowMs, scalarCursor = cursor, raiseEvents = true)
    }

    /** Credit the event/stats phase of the walk stamped [stampMs]: it was raised whole and every row
     *  it enqueued has left the queue, so later passes on this walk may skip it. */
    suspend fun bankEvents(stampMs: Long, nowMs: Long) =
        putKv(ReMirrorKeys.EVENTS_STAMP, stampMs.toString(), nowMs)

    /** Credit one scalar page: everything up to [ts] has left the queue and need never be walked
     *  again, for this epoch, however many connects the rest of the history takes. */
    suspend fun bankScalarCursor(ts: Long, nowMs: Long) =
        putKv(ReMirrorKeys.SCALAR_CURSOR, ts.toString(), nowMs)

    /** True once no outbox row is as old as [throughMs] — everything enqueued at or before it has
     *  left. Rows stamped later (live samples, forecasts) do not count, so a busy phone still
     *  converges. The caller pairs this with a drain it drives itself, and treats it as evidence of
     *  transmission only inside the horizon [delivered] enforces. */
    suspend fun drainedThrough(throughMs: Long): Boolean =
        oldestQueuedAtMs()?.let { it > throughMs } ?: true

    /**
     * Whether the walk on disk may be promoted — the one question whose wrong answer is silent,
     * permanent loss, since a recorded epoch is never revisited.
     *
     * Everything must still hold at this instant: the state describes a walk against exactly this
     * [serverEpoch] and this [storeIdentity], the walk is younger than the eviction horizon, and no
     * row it enqueued remains. A false sends the caller round again on the next connect.
     */
    suspend fun delivered(serverEpoch: String, storeIdentity: String, nowMs: Long): Boolean {
        if (getKv(ReMirrorKeys.PENDING_EPOCH) != serverEpoch) return false
        if (getKv(ReMirrorKeys.WALK_STORE) != storeIdentity) return false
        val stamp = getKv(ReMirrorKeys.WALK_STAMP)?.toLongOrNull() ?: return false
        if (nowMs - stamp >= maxQueueAgeMs) return false
        return drainedThrough(stamp)
    }
}
