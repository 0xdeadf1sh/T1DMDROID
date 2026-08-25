package com.t1dm.sync

/** One copy of each §3.8 kv key: the gate that records them is [CatchUpCoordinator], the walk that
 *  earns them lives at the `:app` root. */
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

data class ReMirrorWalk(
    /** The `createdAtMs` the event/stats phase stamps its rows with; the eviction horizon runs
     *  from here. */
    val stampMs: Long,
    /** Resume the scalar page walk from here (exclusive). 0 = from the start of the local history. */
    val scalarCursor: Long,
    /** True for a different store or epoch, a stamp that can no longer be trusted, or the first
     *  walk ever. */
    val raiseEvents: Boolean,
)

/**
 * Delivery is inferred from ABSENCE: the outbox has no SENT state and [QueueDrainer] deletes a row on
 * success, on a permanent 4xx and on eviction alike. So an aged-out walk is refused promotion, a walk
 * whose target store moved is discarded, and a permanent 4xx does read as delivered, deliberately.
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
     * Claim a walk against [serverEpoch] and [storeIdentity]; the state returned is the state just
     * persisted. The scalar cursor survives an aged-out walk but not a move of either half of the
     * (store, epoch) pair it was proved against — banking is what forecloses re-sending.
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

    /** The event/stats phase of walk [stampMs] was raised whole and has left the queue. */
    suspend fun bankEvents(stampMs: Long, nowMs: Long) =
        putKv(ReMirrorKeys.EVENTS_STAMP, stampMs.toString(), nowMs)

    /** Everything up to [ts] has left the queue and need never be walked again for this epoch. */
    suspend fun bankScalarCursor(ts: Long, nowMs: Long) =
        putKv(ReMirrorKeys.SCALAR_CURSOR, ts.toString(), nowMs)

    /** True once no outbox row is as old as [throughMs]. Rows stamped later (live samples,
     *  forecasts) do not count, so a busy phone still converges. */
    suspend fun drainedThrough(throughMs: Long): Boolean =
        oldestQueuedAtMs()?.let { it > throughMs } ?: true

    /**
     * The one question whose wrong answer is silent, permanent loss: a recorded epoch is never
     * revisited. Every condition must hold at this instant; a false sends the caller round again.
     */
    suspend fun delivered(serverEpoch: String, storeIdentity: String, nowMs: Long): Boolean {
        if (getKv(ReMirrorKeys.PENDING_EPOCH) != serverEpoch) return false
        if (getKv(ReMirrorKeys.WALK_STORE) != storeIdentity) return false
        val stamp = getKv(ReMirrorKeys.WALK_STAMP)?.toLongOrNull() ?: return false
        if (nowMs - stamp >= maxQueueAgeMs) return false
        return drainedThrough(stamp)
    }
}
