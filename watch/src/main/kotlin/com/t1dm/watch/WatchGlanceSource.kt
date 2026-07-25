package com.t1dm.watch

import com.t1dm.watch.proto.WatchPush

/**
 * Supplies the current glance to push (task deliverable 3/4). :app binds this from the repository
 * (current BG + trend + reading age), the alarm engine (alert band + signal-loss/alarm bits), and
 * the SELECTED model's latest prediction (forecast end/horizon/trend + the one-line summary +
 * warmup/degeneracy/predicted-crossing bits). Keeping it a port is what lets `:watch` stay free of
 * `:inference`, `:alerts`, and Room — the whole seam removes by deleting the module + this binding.
 *
 * [WatchLink] owns only [WatchPush.status]'s `lowPowerSuspending` bit (it alone knows the scheduler
 * is suspended); everything else comes from here. A null return means "nothing worth pushing yet"
 * (e.g. no reading at all) and the link skips the tick.
 */
fun interface WatchGlanceSource {
    suspend fun currentGlance(nowMs: Long): WatchPush?
}

/** Whether the phone is in low-power / battery-saver mode (default entry 20 %, configurable —
 * Q9). When true, [WatchLink] sends one final flagged frame then suspends the pusher. */
fun interface LowPowerProvider {
    suspend fun isLowPower(): Boolean
}

/** Tunables for the link; all defaults are conservative and overridable from settings/DI. */
data class WatchLinkConfig(
    val enabled: Boolean = false,
    val autoConnect: Boolean = true,
    val connectTimeoutMs: Long = 20_000L,
    val handshakeTimeoutMs: Long = 15_000L,
    val backoffInitialMs: Long = 2_000L,
    val backoffMaxMs: Long = 5 * 60_000L,
    /** Informational: the FGS 5-min grid tick drives [WatchLink.pushNow]; this documents the cadence. */
    val pushIntervalMs: Long = 300_000L,
)
