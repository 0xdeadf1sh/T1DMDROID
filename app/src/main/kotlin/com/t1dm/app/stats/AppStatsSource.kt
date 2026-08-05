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

/**
 * The `:app` composition of the Stats port. The `:data` [StatsRepository] (kv settings + the local
 * Rust recompute) is the sole stats authority: the phone pushes its own blocks (§3.6), so the former
 * `:sync` server fetch is gone — reading the phone's own echo is pointless. [serverStats] therefore
 * returns a fixed `"local (authoritative)"` stub, which the `:feature:stats` union renderer folds
 * into the composite as `server = null` + that reason, leaving the screen's shape unchanged.
 * [AdvancedStats.toStatsPushDto] maps the local recompute onto the flat wire block the push enqueues.
 *
 * [http] and [dispatchers] are retained solely to keep the constructor seam (the `:app` container's
 * instantiation) stable; the stubbed [serverStats] no longer touches either.
 */
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

    /**
     * The phone is the authoritative stats author (§3.6): it never fetches the server block, which
     * would only be its own echo. Returns the `local (authoritative)` reason so the composite keeps
     * `server = null` without any HTTP round-trip.
     */
    override suspend fun serverStats(window: StatsWindow, refresh: Boolean): ServerStatsResult =
        ServerStatsResult.Unavailable("local (authoritative)")

    override suspend fun localStats(window: StatsWindow, refresh: Boolean): AdvancedStats =
        stats.localStats(window, force = refresh)
}

/**
 * Map the locally-recomputed [AdvancedStats] onto the flat [StatsPushDto] the phone pushes and the
 * server stores verbatim (§3.6). [updatedAt] is the phone clock, carried verbatim (never re-stamped).
 * `mean_hr`/`bg_hr_corr` have no home in [AdvancedStats] yet (§8.2, cross-lane dep) so they default 0.
 */
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
