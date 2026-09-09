package com.t1dm.feature.stats

import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.AgpBin
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.SubBands
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private class FakeSource(
        var local: AdvancedStats = EMPTY,
    ) : StatsSource {
        override val targetRange = MutableStateFlow(TargetRange.DEFAULT)
        override val unitSpace = MutableStateFlow(UnitSpace.MgDl)
        override suspend fun setUnitSpace(space: UnitSpace) { unitSpace.value = space }
        override suspend fun setTargetRange(lowMgdl: Int, highMgdl: Int) { targetRange.value = TargetRange(lowMgdl, highMgdl) }
        override fun kovatchevF(mgdl: Double): Double = 0.0
        override suspend fun localStats(window: StatsWindow, refresh: Boolean) = local
    }

    @Test
    fun populated_local_yields_composite_without_empty_reason() = runTest {
        val src = FakeSource(local = populated())
        val vm = StatsViewModel(src, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val s = vm.state.value
        assertNotNull(s.composite)
        assertNull(s.emptyReason)
        assertEquals(144, s.composite!!.local.nSamples)
    }

    @Test
    fun empty_local_yields_plain_language_empty_reason() = runTest {
        val src = FakeSource(local = EMPTY)
        val vm = StatsViewModel(src, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        assertNotNull(vm.state.value.emptyReason)
    }

    @Test
    fun selecting_window_reloads() = runTest {
        val src = FakeSource(local = populated())
        val vm = StatsViewModel(src, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.selectWindow(StatsWindow.D90)
        assertEquals(StatsWindow.D90, vm.state.value.window)
        assertEquals(StatsWindow.D90, vm.state.value.composite!!.window)
    }

    private companion object {
        val EMPTY = AdvancedStats.EMPTY

        fun populated() = AdvancedStats.EMPTY.copy(
            nSamples = 144,
            spanMs = 3L * 86_400_000L,
            tir = 0.7, tbr = 0.1, tar = 0.2,
            subBands = SubBands(0.02, 0.08, 0.7, 0.15, 0.05),
            meanBg = 140.0, sd = 40.0, cv = 28.6, gmi = 6.66,
            totalCarbs = 150.0, totalBolus = 30.0, totalBasal = 20.0,
            meanDailyCarbs = 50.0, tdd = 16.6, bolusBasalRatio = 1.5,
            meanSteps = 42.0,
            agp = listOf(AgpBin(0, 90.0, 110.0, 130.0, 160.0, 200.0)),
        )
    }
}
