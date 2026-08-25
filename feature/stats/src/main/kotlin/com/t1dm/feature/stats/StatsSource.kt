package com.t1dm.feature.stats

import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.ServerStats
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.flow.Flow

/** The port `:app` implements. `:core:model` types only, so this module needs neither `:data` nor
 *  `:sync`. */
interface StatsSource {
    /** Distinct from the alarm thresholds. */
    val targetRange: Flow<TargetRange>

    val unitSpace: Flow<UnitSpace>

    suspend fun setUnitSpace(space: UnitSpace)

    suspend fun setTargetRange(lowMgdl: Int, highMgdl: Int)

    /** mg/dL → Kovatchev risk space; the Rust authority. */
    fun kovatchevF(mgdl: Double): Double

    /** [refresh] forces a fresh server recompute rather than its ≤24 h cache. */
    suspend fun serverStats(window: StatsWindow, refresh: Boolean): ServerStatsResult

    /** Off the main thread, memoized per window; [refresh] forces the reduction anyway. */
    suspend fun localStats(window: StatsWindow, refresh: Boolean = false): AdvancedStats
}

sealed interface ServerStatsResult {
    data class Ok(val stats: ServerStats) : ServerStatsResult
    data class Unavailable(val reason: String) : ServerStatsResult
}
