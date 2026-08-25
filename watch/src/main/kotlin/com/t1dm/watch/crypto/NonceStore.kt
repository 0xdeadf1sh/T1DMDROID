package com.t1dm.watch.crypto

/**
 * The AEAD nonce is a monotonic counter: reusing a `(key, nonce)` annihilates GCM confidentiality
 * AND integrity, so across process death the session must resume STRICTLY ABOVE the last seq that
 * could conceivably have gone out.
 */
interface NonceStore {
    /** Highest send seq possibly transmitted for [epoch]; 0 if none. */
    suspend fun loadCeiling(epoch: Int): Long

    /** Monotonic: never lowers the stored ceiling. */
    suspend fun recordCeiling(epoch: Int, seq: Long)

    suspend fun clear()

    /** Added on top of the persisted ceiling at cold start, so a checkpoint that lagged the last
     *  in-flight seals cannot collide. Generous: the seq is 64 bits and one frame goes out per 5 min. */
    val burnMargin: Long get() = 256L
}

/** Loses the ceiling on restart: acceptable only where the session keys do not survive either. */
class InMemoryNonceStore : NonceStore {
    private val ceilings = HashMap<Int, Long>()

    override suspend fun loadCeiling(epoch: Int): Long = ceilings[epoch] ?: 0L

    override suspend fun recordCeiling(epoch: Int, seq: Long) {
        val prev = ceilings[epoch] ?: 0L
        if (seq > prev) ceilings[epoch] = seq
    }

    override suspend fun clear() = ceilings.clear()
}
