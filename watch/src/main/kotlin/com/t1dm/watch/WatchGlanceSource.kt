package com.t1dm.watch

import com.t1dm.watch.proto.WatchPush

/** Null ⇒ nothing to push, link skips the tick. [WatchLink] fills lowPowerSuspending itself. */
fun interface WatchGlanceSource {
    suspend fun currentGlance(nowMs: Long): WatchPush?
}

/** True ⇒ [WatchLink] sends one final flagged frame, then suspends the pusher. */
fun interface LowPowerProvider {
    suspend fun isLowPower(): Boolean
}

data class WatchLinkConfig(
    val enabled: Boolean = false,
    val autoConnect: Boolean = true,
    val connectTimeoutMs: Long = 20_000L,
    val handshakeTimeoutMs: Long = 15_000L,
    val backoffInitialMs: Long = 2_000L,
    val backoffMaxMs: Long = 5 * 60_000L,
    /** Informational: the FGS 5-min grid tick drives [WatchLink.pushNow]. */
    val pushIntervalMs: Long = 300_000L,
)
