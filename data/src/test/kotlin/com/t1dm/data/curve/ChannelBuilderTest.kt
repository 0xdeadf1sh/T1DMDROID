package com.t1dm.data.curve

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the Kotlin curve/channel layer over [StubNativeCore] (the faithful Kotlin
 * port of `t1dm-core::curve`, itself golden-checked against simulator.py in the crate). These
 * exercise the wiring [ChannelBuilder] adds on top of the pure math: window padding, kind
 * separation into the two model channels, existing-tail carry-forward, candidate injection, and
 * the "IOB from logged doses only" provenance rule.
 */
class ChannelBuilderTest {

    private val dispatchers = DefaultT1dmDispatchers(
        main = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
        inference = Dispatchers.Unconfined,
    )

    private val engine = CurveEngine(StubNativeCore(), dispatchers)

    /** A fake store with hand-placed events, so the builder logic is tested in isolation. */
    private class FakeStore(
        val carbs: List<CurveEvent> = emptyList(),
        val insulin: List<CurveEvent> = emptyList(),
        val schedule: BasalSchedule? = null,
    ) : DoseStore {
        override suspend fun carbEvents(fromMs: Long, toMs: Long) = carbs
        override suspend fun insulinEvents(fromMs: Long, toMs: Long) = insulin
        override suspend fun activeBasalSchedule() = schedule
    }

    @Test
    fun bolusEvent_reference_peaks_at_50min_and_sums_to_dose() = runTest {
        val ev = engine.bolusEvent(units = 5.0, startMs = 0L)
        assertEquals(CurveKind.INSULIN, ev.kind)
        assertEquals(5.0, ev.values.sum(), 1e-9)
        val peak = ev.values.indices.maxByOrNull { ev.values[it] }!!
        assertEquals(50.0, (peak + 1) * 5.0, 0.0) // gamma peak (k-1)·θ = 50 min
    }

    @Test
    fun contextChannels_separate_carb_and_insulin_by_kind() = runTest {
        val g0 = 1_000_000_000_000L
        val carb = engine.carbEvent(grams = 40.0, startMs = g0, k = 3.0, theta = 20.0, durMin = 240.0)
        val bolus = engine.bolusEvent(units = 6.0, startMs = g0)
        val builder = ChannelBuilder(engine, FakeStore(carbs = listOf(carb), insulin = listOf(bolus)))

        val n = 48
        val ch = builder.contextChannels(g0, n)
        // Each channel integrates to its event total within the window; kinds don't cross-contaminate.
        assertEquals(40.0, ch.carb.sum(), 1e-6)
        assertEquals(6.0, ch.insulin.sum(), 1e-6)
        assertTrue(ch.carb.all { it >= 0.0 } && ch.insulin.all { it >= 0.0 })
    }

    @Test
    fun futureOverrides_carries_existing_tail_and_injects_candidate() = runTest {
        val rollStart = 2_000_000_000_000L
        // A bolus taken 20 min BEFORE the roll start: its PK tail must carry into the horizon.
        val existing = engine.bolusEvent(units = 5.0, startMs = rollStart - 20 * 60_000L)
        val builder = ChannelBuilder(engine, FakeStore(insulin = listOf(existing)))

        val n = 60 // 5 h horizon
        val candidate = listOf(engine.bolusEvent(units = 3.0, startMs = rollStart))
        val fc = builder.futureOverrides(rollStart, n, announced = emptyList(), candidate = candidate)

        // The insulin channel folds the existing tail + the candidate; strictly more than tail alone.
        val tailOnly = builder.futureOverrides(rollStart, n, emptyList(), null)
        assertTrue(fc.insulin.sum() > tailOnly.insulin.sum())
        // IOB provenance: from the STORE dose only (the pre-roll bolus), candidate excluded.
        assertEquals(tailOnly.iobAtStart, fc.iobAtStart, 1e-12)
        assertTrue(fc.iobAtStart > 0.0 && fc.iobAtStart < 5.0) // some of the 5 U already acted
    }

    @Test
    fun futureOverrides_extends_basal_background() = runTest {
        val rollStart = 3_000_000_000_000L
        val sched = BasalSchedule(
            tzOffsetMin = 0,
            doses = listOf(
                BasalDoseSpec(
                    timeOfDayMin = 8 * 60,
                    doseU = 24.0,
                    durationMin = CurveEngine.Presets.TRESIBA_DIA_MIN,
                    kaPerHour = CurveEngine.Presets.BASAL_KA_PER_HOUR,
                    kePerHour = CurveEngine.Presets.BASAL_KE_PER_HOUR,
                ),
            ),
        )
        val builder = ChannelBuilder(engine, FakeStore(schedule = sched))
        val fc = builder.futureOverrides(rollStart, 60, emptyList(), null)
        // The auto-extended basal puts a positive insulin background at every horizon step.
        assertTrue(fc.insulin.all { it > 0.0 })
        assertTrue(fc.iobAtStart > 0.0)
    }
}
