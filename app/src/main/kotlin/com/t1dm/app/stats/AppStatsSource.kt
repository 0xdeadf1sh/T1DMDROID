package com.t1dm.app.stats

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.EventStat
import com.t1dm.core.model.ServerStats
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.UnitSpace
import com.t1dm.data.stats.StatsRepository
import com.t1dm.feature.stats.ServerStatsResult
import com.t1dm.feature.stats.StatsSource
import com.t1dm.sync.NoActiveProfileException
import com.t1dm.sync.StatsDto
import com.t1dm.sync.SyncHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The `:app` composition of the Stats port: the `:data` [StatsRepository] (kv settings + the local
 * Rust recompute) unioned with the `:sync` [SyncHttpClient]'s server cached block. It maps the wire
 * [StatsDto] onto the neutral `:core:model` [ServerStats] and turns every offline/no-profile/HTTP
 * failure into a plain-language [ServerStatsResult.Unavailable] reason — never a crash (the stats
 * screen degrades to the local recompute alone).
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

    override suspend fun serverStats(window: StatsWindow, refresh: Boolean): ServerStatsResult =
        withContext(dispatchers.io) {
            try {
                ServerStatsResult.Ok(http.getStats(window.wire, refresh).toServerStats())
            } catch (_: NoActiveProfileException) {
                ServerStatsResult.Unavailable("No server profile configured — showing the local recompute only.")
            } catch (e: Exception) {
                ServerStatsResult.Unavailable(
                    "Server unreachable (${e.message ?: e::class.simpleName}) — showing the local recompute only.",
                )
            }
        }

    override suspend fun localStats(window: StatsWindow): AdvancedStats = stats.localStats(window)
}

private fun StatsDto.toServerStats(): ServerStats = ServerStats(
    window = StatsWindow.fromWire(window),
    tir = tir,
    timeBelow = time_below,
    timeAbove = time_above,
    meanBg = mean_bg,
    gmi = gmi,
    cv = cv,
    sd = sd,
    hypoEvents = EventStat(hypo_events.count, hypo_events.duration_ms),
    hyperEvents = EventStat(hyper_events.count, hyper_events.duration_ms),
    meanDailyCarbs = mean_daily_carbs,
    tdd = tdd,
    bolusBasalRatio = bolus_basal_ratio,
    meanHr = mean_hr,
    bgHrCorr = bg_hr_corr,
    nSamples = n_samples,
)
