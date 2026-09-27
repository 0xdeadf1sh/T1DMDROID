package com.t1dm.watch.crypto

/** Read back only by a factory without key material; real sessions resume from theirs. */
interface NonceStore {
    /** Highest send seq possibly transmitted for [epoch]; 0 if none. */
    suspend fun loadCeiling(epoch: Int): Long

    /** Monotonic: never lowers the stored ceiling. */
    suspend fun recordCeiling(epoch: Int, seq: Long)

    suspend fun clear()
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
