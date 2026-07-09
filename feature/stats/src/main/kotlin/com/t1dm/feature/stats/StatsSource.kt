package com.t1dm.feature.stats

import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.ServerStats
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.flow.Flow

/**
 * The `:feature:stats` port (PLAN "Phase 6 — Stats"). `:app` supplies the implementation, composing
 * the `:data` `StatsRepository` (settings + local Rust recompute) with the `:sync` read client
 * (server cached block). Keeping the surface in `:core:model` types lets this feature module stay
 * free of both `:data` and `:sync`.
 */
interface StatsSource {
    /** The global stats target range (drives TIR/TBR/TAR; distinct from the alarm thresholds). */
    val targetRange: Flow<TargetRange>

    /** The active glucose unit space for the axis + metric display. */
    val unitSpace: Flow<UnitSpace>

    suspend fun setUnitSpace(space: UnitSpace)

    suspend fun setTargetRange(lowMgdl: Int, highMgdl: Int)

    /** Kovatchev BG → risk-space `f` for the Kovatchev axis/metric display (the Rust authority). */
    fun kovatchevF(mgdl: Double): Double

    /** The server's daily-cached shared block, or a plain-language reason it is unavailable.
     *  [refresh] forces the server to recompute fresh rather than serve its ≤24 h cache. */
    suspend fun serverStats(window: StatsWindow, refresh: Boolean): ServerStatsResult

    /** The local Rust recompute over the Room `sample` series for [window] (off the main thread). */
    suspend fun localStats(window: StatsWindow): AdvancedStats
}

/** Server-block outcome: the shared stats, or why they could not be fetched (offline/no profile). */
sealed interface ServerStatsResult {
    data class Ok(val stats: ServerStats) : ServerStatsResult
    data class Unavailable(val reason: String) : ServerStatsResult
}
