package com.t1dm.sync

/**
 * Tunables for the durable outbox (Phase 3): exponential backoff with jitter, plus
 * the size AND age bounds — both user-configurable per the sync decisions. Defaults are conservative
 * placeholders; the Settings → Server screen surfaces them in Phase 7.
 */
data class DrainConfig(
    val baseBackoffMs: Long = 5_000,
    val maxBackoffMs: Long = 30 * 60_000,          // 30 min ceiling between retries
    val jitterFrac: Double = 0.25,                 // ±25% spread to de-correlate a thundering herd
    val batchLimit: Int = 200,                     // rows attempted per drainOnce
    val maxQueueSize: Int = 20_000,                // hard size bound (eviction by priority)
    val maxAgeMs: Long = 7L * 24 * 60 * 60_000,    // 7-day age bound
) {
    init {
        require(baseBackoffMs > 0 && maxBackoffMs >= baseBackoffMs) { "bad backoff bounds" }
        require(jitterFrac in 0.0..1.0) { "jitterFrac must be in [0,1]" }
        require(maxQueueSize > 0 && batchLimit > 0) { "bad bounds" }
    }
}

/**
 * Exponential-with-jitter backoff. Pure and deterministic given [rand01] ∈ [0,1) — the drainer feeds
 * `random.nextDouble()` — so the schedule is unit-testable without a clock or RNG seam. [attempts]
 * is the count of prior failures (0 ⇒ first retry). The base delay `base·2^attempts` is capped at
 * [DrainConfig.maxBackoffMs] *before* jitter, and jitter is a symmetric ±[DrainConfig.jitterFrac]
 * band around it, so a zero jitter fraction yields the exact doubling schedule.
 */
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
