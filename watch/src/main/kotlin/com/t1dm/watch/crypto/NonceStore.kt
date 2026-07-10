package com.t1dm.watch.crypto

/**
 * Durable persistence of the per-epoch send-counter CEILING (risk S6, "windowed send-counter +
 * cold-start burn-the-window"). The AEAD nonce is a monotonic counter; reusing a `(key,nonce)` pair
 * annihilates GCM confidentiality AND integrity, so across process death / a `kill -9` / a battery
 * yank we must resume STRICTLY ABOVE the last value that could conceivably have gone out.
 *
 * [WatchLink] checkpoints the seq as it seals (batched/high-water) and, on cold start, reads the
 * ceiling to seed [WatchSessionFactory.resume]. :app binds this to the Room `kv` store; the
 * in-memory default keeps host tests + non-persistent runs total.
 */
interface NonceStore {
    /** Highest send seq known to have possibly been transmitted for [epoch] (0 if none). */
    suspend fun loadCeiling(epoch: Int): Long

    /** Record a new high-water send seq for [epoch]. Monotonic: never lowers the stored ceiling. */
    suspend fun recordCeiling(epoch: Int, seq: Long)

    /** Forget all epochs (on UNPAIR / reset). */
    suspend fun clear()

    /**
     * The safety margin added on top of the persisted ceiling when burning the window at cold start,
     * so a checkpoint that lagged the last few in-flight seals can never collide. Generous: the
     * counter is 32 bits and we send ~once per 5 min.
     */
    val burnMargin: Long get() = 256L
}

/** Non-persistent default (host tests, and the pre-wiring build). Loses the ceiling on restart —
 *  acceptable only where the process (and thus the session keys) also do not survive. */
class InMemoryNonceStore : NonceStore {
    private val ceilings = HashMap<Int, Long>()

    override suspend fun loadCeiling(epoch: Int): Long = ceilings[epoch] ?: 0L

    override suspend fun recordCeiling(epoch: Int, seq: Long) {
        val prev = ceilings[epoch] ?: 0L
        if (seq > prev) ceilings[epoch] = seq
    }

    override suspend fun clear() = ceilings.clear()
}
