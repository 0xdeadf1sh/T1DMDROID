package com.t1dm.watch

import com.t1dm.watch.proto.WatchDisplay
import com.t1dm.watch.proto.WatchForecast
import com.t1dm.watch.proto.WatchHistory
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchStats

/** Null means nothing worth pushing yet; WatchLink fills status.lowPowerSuspending itself. */
fun interface WatchGlanceSource {
    suspend fun currentGlance(nowMs: Long): WatchPush?
}

/** Extended records, SPEC/watch.md §5.4–§5.7; a null or thrown kind is skipped, not fatal. */
interface WatchExtendedSource {
    /** The [slots] grid slots ending at the slot holding [nowMs]. */
    suspend fun history(nowMs: Long, slots: Int): WatchHistory?

    /** The selected forecast, or a withdrawal when none is shown. */
    suspend fun forecast(): WatchForecast?

    suspend fun stats(): WatchStats?

    suspend fun display(): WatchDisplay?
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
    val rotationSasTimeoutMs: Long = 120_000L,
    val backoffInitialMs: Long = 2_000L,
    val backoffMaxMs: Long = 5 * 60_000L,
    /** RSSI and the STATUS liveness read (SPEC/watch.md §7, at most 15 s). */
    val pollMs: Long = 15_000L,
    /** Informational: the FGS 5-min grid tick drives [WatchHub.tick]. */
    val pushIntervalMs: Long = 300_000L,
    val statsIntervalMs: Long = 60 * 60_000L,
)
