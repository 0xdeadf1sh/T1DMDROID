package com.t1dm.app.stats

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.UnitSpace
import com.t1dm.data.stats.StatsRepository
import com.t1dm.feature.stats.StatsSource
import kotlinx.coroutines.flow.Flow

class AppStatsSource(
    private val stats: StatsRepository,
    private val native: NativeCore,
) : StatsSource {

    override val targetRange = stats.targetRange
    override val unitSpace: Flow<UnitSpace> = stats.unitSpace

    override suspend fun setUnitSpace(space: UnitSpace) = stats.setUnitSpace(space)

    override suspend fun setTargetRange(lowMgdl: Int, highMgdl: Int) = stats.setTargetRange(lowMgdl, highMgdl)

    override fun kovatchevF(mgdl: Double): Double = native.kovatchevF(mgdl)

    override suspend fun localStats(window: StatsWindow, refresh: Boolean): AdvancedStats =
        stats.localStats(window, force = refresh)
}
