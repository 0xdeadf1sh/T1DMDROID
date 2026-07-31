package com.t1dm.data.curve

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.BasalDoseSpec
import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    /** A fake store with hand-placed events, so the builder logic is tested in isolation.
     *  [insulin] is BOLUS-only (issue #6); [basalInjections] are the discrete long-acting BASAL
     *  events the builder falls back to ONLY when no [schedule] is active (schedule XOR injections). */
    private class FakeStore(
        val carbs: List<CurveEvent> = emptyList(),
        val insulin: List<CurveEvent> = emptyList(),
        val schedule: BasalSchedule? = null,
        val basalInjections: List<CurveEvent> = emptyList(),
    ) : DoseStore {
        /** How many times each window read was issued — so "one gather, not two" is asserted. */
        var insulinReads = 0
        var scheduleReads = 0
        var basalInjectionReads = 0

        /** How many BOTH-HALVES gathers the builder asked for. The default implementation delegates to
         *  the two reads above, so this counts the builder's gathers, not the store's queries — the
         *  Room store answers one gather with a single `logged_dose` window read. */
        var combinedReads = 0

        override suspend fun carbEvents(fromMs: Long, toMs: Long) = carbs
        override suspend fun insulinEvents(fromMs: Long, toMs: Long) = insulin.also { insulinReads++ }
        override suspend fun activeBasalSchedule() = schedule.also { scheduleReads++ }
        override suspend fun basalInjectionEvents(fromMs: Long, toMs: Long) =
            basalInjections.also { basalInjectionReads++ }

        override suspend fun insulinAndBasalInjectionEvents(fromMs: Long, toMs: Long) =
            super.insulinAndBasalInjectionEvents(fromMs, toMs).also { combinedReads++ }
    }

    @Test
    fun contextChannels_separate_carb_and_insulin_by_kind() = runTest {
        val g0 = 1_000_000_000_000L
        val carb = engine.carbEvent(grams = 40.0, startMs = g0, k = 3.0, theta = 20.0, durMin = 240.0)
        val bolus = engine.rapidEvent(units = 6.0, startMs = g0, peakMin = 75.0, diaMin = 360.0)
        val builder = ChannelBuilder(engine, FakeStore(carbs = listOf(carb), insulin = listOf(bolus)))

        val n = 72 // 6 h window fully covers the 360-min rapid DIA, so each channel integrates to its total
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
        val existing = engine.rapidEvent(units = 5.0, startMs = rollStart - 20 * 60_000L, peakMin = 75.0, diaMin = 360.0)
        val builder = ChannelBuilder(engine, FakeStore(insulin = listOf(existing)))

        val n = 60 // 5 h horizon
        val candidate = listOf(engine.rapidEvent(units = 3.0, startMs = rollStart, peakMin = 75.0, diaMin = 360.0))
        val fc = builder.futureOverrides(rollStart, n, announced = emptyList(), candidate = candidate)

        // The insulin channel folds the existing tail + the candidate; strictly more than tail alone.
        val tailOnly = builder.futureOverrides(rollStart, n, emptyList(), null)
        assertTrue(fc.insulin.sum() > tailOnly.insulin.sum())
        // IOB provenance: from the STORE dose only (the pre-roll bolus), candidate excluded.
        assertEquals(tailOnly.iobAtStart, fc.iobAtStart, 1e-12)
        assertTrue(fc.iobAtStart > 0.0 && fc.iobAtStart < 5.0) // some of the 5 U already acted
    }

    @Test
    fun futureOverrides_committed_tails_land_in_correct_channel_no_swap() = runTest {
        // The exact call the DASHBOARD's FutureOverrideSource makes (announced/candidate empty): the
        // committed logged meal + bolus, already absorbing before the roll start, must carry their
        // TAILS into the prediction zone in the RIGHT channel — carb→feat1, insulin→feat2, no swap —
        // so the main-view forecast RAISES on a committed meal (not the old zeroed pred-zone dip).
        val rollStart = 4_000_000_000_000L
        val committedMeal = engine.carbEvent(grams = 50.0, startMs = rollStart - 15 * 60_000L, k = 3.0, theta = 20.0, durMin = 240.0)
        val committedBolus = engine.rapidEvent(units = 4.0, startMs = rollStart - 15 * 60_000L, peakMin = 75.0, diaMin = 360.0)
        val builder = ChannelBuilder(engine, FakeStore(carbs = listOf(committedMeal), insulin = listOf(committedBolus)))

        val fc = builder.futureOverrides(rollStart, nSteps = 48, announced = emptyList(), candidate = null)

        // Both committed tails are present and positive (committed action carried past the boundary)…
        assertTrue("committed carb tail must appear in the carb channel", fc.carb.sum() > 0.0)
        assertTrue("committed insulin tail must appear in the insulin channel", fc.insulin.sum() > 0.0)
        // …and neither leaks a spurious total larger than its source event (no cross-contamination).
        assertTrue(fc.carb.sum() <= 50.0 + 1e-9 && fc.insulin.sum() <= 4.0 + 1e-9)
        assertTrue(fc.carb.all { it >= 0.0 } && fc.insulin.all { it >= 0.0 })
        // Remaining-on-board at the roll start is a fraction of each dose (some already acted).
        assertTrue(fc.cobAtStart > 0.0 && fc.cobAtStart < 50.0)
        assertTrue(fc.iobAtStart > 0.0 && fc.iobAtStart < 4.0)
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

    @Test
    fun basalSchedule_xor_injection_isIntegratedExactlyOnce() = runTest {
        // Issue #6: with an active schedule AND a discrete BASAL injection both present, the basal
        // background must be counted ONCE (schedule-primary XOR), never summed — a double-count would
        // deflate the forecast and admit a larger dose (fail-open). Schedule 24 U/day vs a single 12 U
        // injection so the two representations are distinguishable over the window.
        val g0 = 3_000_000_000_000L // on the 5-min grid, so the injection's bucket offset is exact
        val n = 72                  // 6 h
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
        val injection = CurveEvent(
            g0, CurveEngine.STEP_MS, CurveKind.INSULIN, 12.0,
            engine.bateman(12.0, CurveEngine.Presets.TRESIBA_DIA_MIN, CurveEngine.Presets.BASAL_KA_PER_HOUR, CurveEngine.Presets.BASAL_KE_PER_HOUR).toList(),
        )
        val inj = listOf(injection)

        // No boluses in any store, so contextChannels.insulin isolates the single basal representation.
        val both = ChannelBuilder(engine, FakeStore(schedule = sched, basalInjections = inj))
        val schedOnly = ChannelBuilder(engine, FakeStore(schedule = sched))
        val injOnly = ChannelBuilder(engine, FakeStore(basalInjections = inj)) // schedule == null

        val bothInsulin = both.contextChannels(g0, n).insulin
        val schedInsulin = schedOnly.contextChannels(g0, n).insulin
        val injInsulin = injOnly.contextChannels(g0, n).insulin

        // Precondition: both representations genuinely contribute and differ, so the XOR is observable.
        assertTrue("schedule must contribute basal area", schedInsulin.sum() > 0.0)
        assertTrue("injection must contribute basal area", injInsulin.sum() > 0.0)
        assertTrue("the two representations must be distinguishable", abs(schedInsulin.sum() - injInsulin.sum()) > 1e-3)

        // With BOTH present the schedule wins and the injection is NOT added (integrated once).
        assertArrayEquals(schedInsulin, bothInsulin, 0.0)
        // A double-count would have summed the two:
        assertTrue(bothInsulin.sum() < schedInsulin.sum() + injInsulin.sum() - 1e-6)

        // With NO schedule the discrete injection is the single basal representation.
        val directInj = engine.bucketize(inj, g0, n, CurveKind.INSULIN)
        assertArrayEquals(directInj, injInsulin, 0.0)

        // Mirror the XOR on the dashboard overlay's separate basal series.
        assertArrayEquals(schedOnly.overlayChannels(g0, n).basal, both.overlayChannels(g0, n).basal, 0.0)
        assertArrayEquals(directInj, injOnly.overlayChannels(g0, n).basal, 0.0)
    }

    /** The overlay's three series must be exactly what the two calls it replaces produced: the same
     *  carb and COMBINED insulin channels [contextChannels] builds, and the basal sub-series that used
     *  to come from a second resolve of the same window. */
    @Test
    fun overlayChannels_matches_the_two_calls_it_replaces() = runTest {
        val g0 = 3_000_000_000_000L
        val n = 72
        val meal = engine.carbEvent(grams = 35.0, startMs = g0, k = 3.0, theta = 20.0, durMin = 240.0)
        val bolus = engine.rapidEvent(units = 5.0, startMs = g0, peakMin = 75.0, diaMin = 360.0)
        val injection = CurveEvent(
            g0, CurveEngine.STEP_MS, CurveKind.INSULIN, 14.0,
            engine.bateman(14.0, CurveEngine.Presets.TRESIBA_DIA_MIN, CurveEngine.Presets.BASAL_KA_PER_HOUR, CurveEngine.Presets.BASAL_KE_PER_HOUR).toList(),
        )
        val builder = ChannelBuilder(
            engine,
            FakeStore(carbs = listOf(meal), insulin = listOf(bolus), basalInjections = listOf(injection)),
        )

        val overlay = builder.overlayChannels(g0, n)
        val context = builder.contextChannels(g0, n)

        assertArrayEquals(context.carb, overlay.carb, 0.0)
        assertArrayEquals(context.insulin, overlay.insulin, 0.0)
        // The basal series is the injection alone, and a COMPONENT of the combined channel — the two
        // must not have been built from two different gathers of the same window.
        assertArrayEquals(engine.bucketize(listOf(injection), g0, n, CurveKind.INSULIN), overlay.basal, 0.0)
        assertTrue(overlay.insulin.sum() > overlay.basal.sum())
    }

    /** ...and it must gather ONCE. The overlay rebuilds on every reading, every logged dose/meal and
     *  every forecast, over a window the panel caps at ~14 days of 5-min buckets. */
    @Test
    fun overlayChannels_gathers_the_window_once() = runTest {
        val g0 = 3_500_000_000_000L
        val store = FakeStore(insulin = listOf(engine.rapidEvent(units = 2.0, startMs = g0, peakMin = 75.0, diaMin = 360.0)))
        val builder = ChannelBuilder(engine, store)

        builder.overlayChannels(g0, 72)

        // One schedule lookup and ONE both-halves gather for all three series — the basal one used to
        // cost a second of each. (The fake's default splits that gather back into the two counted
        // reads; the Room store answers it with a single `logged_dose` query.)
        assertEquals(1, store.scheduleReads)
        assertEquals(1, store.combinedReads)
    }

    /** With a schedule configured the discrete-injection read must not happen at all: the basal comes
     *  from the auto-extended template, and asking the dose table for injections would be a query whose
     *  result the XOR then discards. */
    @Test
    fun overlayChannels_with_a_schedule_never_reads_the_injections() = runTest {
        val g0 = 3_600_000_000_000L
        val sched = BasalSchedule(
            tzOffsetMin = 0,
            doses = listOf(
                BasalDoseSpec(
                    timeOfDayMin = 8 * 60,
                    doseU = 20.0,
                    durationMin = CurveEngine.Presets.TRESIBA_DIA_MIN,
                    kaPerHour = CurveEngine.Presets.BASAL_KA_PER_HOUR,
                    kePerHour = CurveEngine.Presets.BASAL_KE_PER_HOUR,
                ),
            ),
        )
        val store = FakeStore(schedule = sched)
        val builder = ChannelBuilder(engine, store)

        builder.overlayChannels(g0, 72)

        assertEquals(1, store.insulinReads)
        assertEquals(0, store.combinedReads)
        assertEquals(0, store.basalInjectionReads)
    }

    @Test
    fun insulinZeroMs_single_bolus_is_last_nonzero_step_of_its_pk() = runTest {
        val g0 = 1_000_000_000_000L
        val bolus = engine.rapidEvent(units = 5.0, startMs = g0, peakMin = 75.0, diaMin = 360.0)
        val builder = ChannelBuilder(engine, FakeStore(insulin = listOf(bolus)))

        val expected = g0 + bolus.values.indexOfLast { it > 0.0 } * bolus.stepMs
        assertEquals(expected, builder.insulinZeroMs(g0 + 10 * 60 * 60_000L))
    }

    @Test
    fun insulinZeroMs_no_insulin_is_null() = runTest {
        val builder = ChannelBuilder(engine, FakeStore())
        assertNull(builder.insulinZeroMs(1_000_000_000_000L))
    }

    @Test
    fun insulinZeroMs_basal_tail_extends_past_a_shorter_bolus() = runTest {
        val g0 = 3_000_000_000_000L
        // A 5 U bolus decays within a few hours; the basal background is still acting hours later,
        // so the combined zero-crossing must be pushed out to the basal tail, not the bolus tail.
        val bolus = engine.rapidEvent(units = 5.0, startMs = g0, peakMin = 75.0, diaMin = 360.0)
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
        val builder = ChannelBuilder(engine, FakeStore(insulin = listOf(bolus), schedule = sched))

        val atMs = g0 + 12 * 60 * 60_000L
        val bolusZero = g0 + bolus.values.indexOfLast { it > 0.0 } * bolus.stepMs
        val zero = builder.insulinZeroMs(atMs)!!
        assertTrue("bolus must have decayed before atMs", bolusZero < atMs)
        assertTrue("basal tail must win the max", zero > bolusZero)
    }

    /** [ChannelBuilder.insulinOnBoard] must answer exactly what the two separate calls answered — it is
     *  the same window, and the whole point of merging them is that nothing about the result changes. */
    @Test
    fun insulinOnBoard_matches_the_two_calls_it_replaces() = runTest {
        val g0 = 4_000_000_000_000L
        val bolus = engine.rapidEvent(units = 4.5, startMs = g0, peakMin = 75.0, diaMin = 360.0)
        val injection = CurveEvent(
            g0 - 60 * 60_000L, CurveEngine.STEP_MS, CurveKind.INSULIN, 18.0,
            engine.bateman(18.0, CurveEngine.Presets.TRESIBA_DIA_MIN, CurveEngine.Presets.BASAL_KA_PER_HOUR, CurveEngine.Presets.BASAL_KE_PER_HOUR).toList(),
        )
        val atMs = g0 + 2 * 60 * 60_000L
        val builder = ChannelBuilder(engine, FakeStore(insulin = listOf(bolus), basalInjections = listOf(injection)))

        val merged = builder.insulinOnBoard(atMs)

        assertEquals(builder.onBoard(atMs, CurveKind.INSULIN), merged.iobU, 0.0)
        assertEquals(builder.insulinZeroMs(atMs), merged.zeroMs)
    }

    /** ...and it must reach the store ONCE. The widget pulls IOB on every refresh, so a second gather
     *  here is a second `logged_dose` read, a second schedule lookup and a second PK reconstruction of
     *  every row, several times a minute. */
    @Test
    fun insulinOnBoard_reads_the_window_once() = runTest {
        val g0 = 4_500_000_000_000L
        val store = FakeStore(insulin = listOf(engine.rapidEvent(units = 3.0, startMs = g0, peakMin = 75.0, diaMin = 360.0)))
        val builder = ChannelBuilder(engine, store)

        builder.insulinOnBoard(g0 + 60 * 60_000L)

        assertEquals(1, store.scheduleReads)
        // No schedule ⇒ the discrete-injection fallback, and both halves come from ONE gather (which
        // the fake's default splits back into its two counted reads; the Room store does not).
        assertEquals(1, store.combinedReads)
        assertEquals(1, store.insulinReads)
        assertEquals(1, store.basalInjectionReads)
    }

    @Test
    fun insulinZeroMs_fully_decayed_past_dose_is_before_now() = runTest {
        val g0 = 5_000_000_000_000L
        // A 3 U rapid bolus (6 h DIA) is fully decayed 8 h later; the zero instant lies BEFORE atMs
        // (past instants are not clipped away — the dose still reports where its action ended).
        val bolus = engine.rapidEvent(units = 3.0, startMs = g0, peakMin = 75.0, diaMin = 360.0)
        val builder = ChannelBuilder(engine, FakeStore(insulin = listOf(bolus)))

        val atMs = g0 + 8 * 60 * 60_000L
        val zero = builder.insulinZeroMs(atMs)!!
        assertTrue(zero < atMs)
    }
}
