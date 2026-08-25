package com.t1dm.sync

data class DrainConfig(
    val baseBackoffMs: Long = 5_000,
    val maxBackoffMs: Long = 30 * 60_000,
    val jitterFrac: Double = 0.25,
    val batchLimit: Int = 200,
    val maxQueueSize: Int = 20_000,
    val maxAgeMs: Long = 7L * 24 * 60 * 60_000,
) {
    init {
        require(baseBackoffMs > 0 && maxBackoffMs >= baseBackoffMs) { "bad backoff bounds" }
        require(jitterFrac in 0.0..1.0) { "jitterFrac must be in [0,1]" }
        require(maxQueueSize > 0 && batchLimit > 0) { "bad bounds" }
    }
}

/** [attempts] is the count of prior failures; 0 gives the first retry delay. [rand01] ∈ [0,1). */
object Backoff {
    fun delayMs(cfg: DrainConfig, attempts: Int, rand01: Double): Long {
        val shifted = if (attempts >= 40) Double.MAX_VALUE
        else cfg.baseBackoffMs.toDouble() * (1L shl attempts.coerceAtMost(40))
        val capped = minOf(shifted, cfg.maxBackoffMs.toDouble())
        val span = capped * cfg.jitterFrac
        val jittered = capped - span + rand01.coerceIn(0.0, 1.0) * 2.0 * span
        return jittered.toLong().coerceAtLeast(0L)
    }
}
