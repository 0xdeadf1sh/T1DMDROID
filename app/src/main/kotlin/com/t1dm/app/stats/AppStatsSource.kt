package com.t1dm.app.stats

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.UnitSpace
import com.t1dm.data.stats.StatsRepository
import com.t1dm.feature.stats.ServerStatsResult
import com.t1dm.feature.stats.StatsSource
import com.t1dm.sync.EventStatDto
import com.t1dm.sync.StatsPushDto
import com.t1dm.sync.SyncHttpClient
import kotlinx.coroutines.flow.Flow

/** [http] and [dispatchers] are unused; they keep the constructor seam stable. */
class AppStatsSource(
    private val stats: StatsRepository,
    private val http: SyncHttpClient,
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
) : StatsSource {

    override val targetRange = stats.targetRange
    override val unitSpace: Flow<UnitSpace> = stats.unitSpace

    override suspend fun setUnitSpace(space: UnitSpace) = stats.setUnitSpace(space)

    override suspend fun setTargetRange(lowMgdl: Int, highMgdl: Int) = stats.setTargetRange(lowMgdl, highMgdl)

    override fun kovatchevF(mgdl: Double): Double = native.kovatchevF(mgdl)

    /** The phone authors stats (§3.6); the server block would only be its own echo. */
    override suspend fun serverStats(window: StatsWindow, refresh: Boolean): ServerStatsResult =
        ServerStatsResult.Unavailable("local (authoritative)")

    override suspend fun localStats(window: StatsWindow, refresh: Boolean): AdvancedStats =
        stats.localStats(window, force = refresh)
}

/** §3.6. [updatedAt] is the phone clock, never re-stamped. `mean_hr`/`bg_hr_corr` have no source
 *  yet (§8.2), so they default 0. */
internal fun AdvancedStats.toStatsPushDto(window: StatsWindow, updatedAt: Long): StatsPushDto = StatsPushDto(
    window = window.wire,
    updated_at = updatedAt,
    tir = tir,
    time_below = tbr,
    time_above = tar,
    mean_bg = meanBg,
    gmi = gmi,
    cv = cv,
    sd = sd,
    hypo_events = EventStatDto(count = hypoEpisodes.count, duration_ms = hypoEpisodes.totalDurationMs),
    hyper_events = EventStatDto(count = hyperEpisodes.count, duration_ms = hyperEpisodes.totalDurationMs),
    mean_daily_carbs = meanDailyCarbs,
    tdd = tdd,
    bolus_basal_ratio = bolusBasalRatio,
    mean_hr = 0.0,
    bg_hr_corr = 0.0,
    n_samples = nSamples,
)
