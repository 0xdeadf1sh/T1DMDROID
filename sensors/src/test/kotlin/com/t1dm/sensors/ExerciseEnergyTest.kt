package com.t1dm.sensors

import com.t1dm.core.model.ExerciseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds [ExerciseEnergy] to the published ACSM figures and, more importantly, to its refusals: the
 * energy figure is withheld whenever an input it needs is missing or outside what the equations
 * describe, rather than guessed.
 */
class ExerciseEnergyTest {

    private val mass = 70.0

    /**
     * One five-minute segment: the metres measured in it, the seconds the bout was open, and the
     * interval those metres were covered over — which defaults to the whole of the segment, the
     * uninterrupted case. Where the two intervals differ the test says so explicitly, because that
     * difference is the whole of what [ExerciseEnergy] has to get right.
     */
    private fun seg(distanceM: Double?, seconds: Int, trackedMs: Long = seconds * 1_000L) =
        ExerciseBucket(bucketStartMs = 0L, activeSec = seconds, distanceM = distanceM, trackedMs = trackedMs)

    /** A steady bout of [minutes] minutes at [metresPerMinute], as the five-minute segments the
     *  bucketer would actually emit for it. */
    private fun steady(metresPerMinute: Double, minutes: Int) =
        List(minutes / 5) { seg(metresPerMinute * 5.0, 300) }

    /** A clean bucket boundary, so a bout started at a named offset sits at a known phase to it. */
    private val b0 = 1_500_000_000_000L

    /** Metres per degree of latitude on [ExerciseBucketer]'s own sphere, so a synthetic track can be
     *  laid out in metres and measure back as those metres. */
    private val mPerDegLat = ExerciseBucketer.haversineM(0.0, 0.0, 1.0, 0.0)

    /**
     * A steady bout at [paceMPerMin] driven through the real [ExerciseBucketer] at a real fix cadence,
     * scored after every fix exactly as [ExerciseRecorder] scores it — the closed buckets plus the open
     * partial. Returns the figure the panel would have shown at each fix.
     */
    private fun kcalAfterEachFix(
        startMs: Long,
        cadenceMs: Long,
        paceMPerMin: Double,
        fixes: Int,
        kind: ExerciseKind = ExerciseKind.RUN,
    ): List<Int?> {
        val stepDeg = paceMPerMin / 60.0 * (cadenceMs / 1_000.0) / mPerDegLat
        val bucketer = ExerciseBucketer(startMs)
        val segments = LinkedHashMap<Long, ExerciseBucket>()
        return List(fixes) { i ->
            val fix = ExerciseFix(startMs + i * cadenceMs, 51.5 + i * stepDeg, 0.0, 5f, null)
            bucketer.onFix(fix).forEach { segments[it.bucketStartMs] = it }
            val open = bucketer.peek()
            segments[open.bucketStartMs] = open
            ExerciseEnergy.kcal(kind, segments.values, mass)
        }
    }

    @Test
    fun withoutABodyMassThereIsNoFigure() {
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, steady(83.333, 60), bodyMassKg = null))
    }

    @Test
    fun anImpossibleBodyMassIsNotABodyMass() {
        val bout = steady(83.333, 60)
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, bout, 0.0))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, bout, -70.0))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, bout, Double.NaN))
    }

    @Test
    fun withoutMeasuredDistanceThereIsNoFigure() {
        // A bout indoors, or one with location denied: its seconds are recorded, its energy is not.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, List(12) { seg(null, 300) }, mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, List(12) { seg(0.0, 300) }, mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, List(12) { seg(Double.NaN, 300) }, mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, emptyList(), mass))
    }

    @Test
    fun withoutAnIntervalToHaveMeasuredTheMetresOverThereIsNoFigure() {
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, 0)), mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, -60)), mass))
        // Metres with no interval behind them name no speed, so no gait and no guard: dropped.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, 300, trackedMs = 0L)), mass))
    }

    @Test
    fun anActivityTheEquationsDoNotDescribeGetsNoFigure() {
        // The failure this refusal exists for: a 20 km/h ride is 333 m/min, which the speed branch
        // alone would score with the RUNNING equation at ~1470 kcal for the hour against roughly 590
        // actual, because a rider's mass is carried by the bicycle and not by the rider.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.OTHER, steady(333.333, 60), mass))
        // And for a walk-paced OTHER too — the point is the activity, not the speed.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.OTHER, steady(83.333, 60), mass))
    }

    @Test
    fun aSpeedNoGaitReachesGetsNoFigure() {
        // 30 km/h is a vehicle whatever the bout was labelled; extrapolating the running equation
        // there states a large number with nothing behind it.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.RUN, steady(500.0, 60), mass))
    }

    @Test
    fun aBoutMostlyNotTheExerciseItWasLabelledGetsNoFigure() {
        // The bout the whole-bout average could not see: 800 m walked in 10 min, then 8 km driven in
        // 15 with the bout still running. Averaged, that is 352 m/min — under the vehicle guard — and
        // the running equation would state ~650 kcal for a walk that cost about 50, permanently,
        // because nothing recomputes a stored kcal. Per segment the drive is 533 m/min and is dropped,
        // and dropping more of the bout than is left withholds the figure rather than labelling ten
        // minutes' energy as twenty-five's.
        val walked = List(2) { seg(400.0, 300) }
        val driven = List(3) { seg(2_666.67, 300) }
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, walked + driven, mass))
        // The walked half on its own is still perfectly scoreable — the refusal is the drive's.
        assertNotNull(ExerciseEnergy.kcal(ExerciseKind.WALK, walked, mass))
    }

    @Test
    fun oneVehicleSegmentIsDroppedAndTheRestOfTheBoutIsStillScored() {
        // A cable car down, a lift home, or the one fix wild enough to pass the bucketer's own filter
        // and slow enough to reach this one. It costs the bout its own five minutes and nothing more:
        // an hour's walk with one such segment in it scores exactly the same as the hour without it.
        val walked = List(11) { seg(416.665, 300) }
        val driven = seg(2_666.67, 300)
        val withIt = ExerciseEnergy.kcal(ExerciseKind.WALK, walked + driven, mass)
        assertNotNull(withIt)
        assertEquals(ExerciseEnergy.kcal(ExerciseKind.WALK, walked, mass), withIt)
        assertEquals(228.0, withIt!!.toDouble(), 2.0)
    }

    @Test
    fun theSpeedIsTakenOverTheIntervalTheMetresWereCoveredOverNotTheBucketsSeconds() {
        // The open partial one second after a boundary: the 4 s segment that straddled it is charged
        // whole to this bucket, whose own seconds start at the boundary. 13.3 m over that 1 s is
        // 800 m/min and no gait reaches it; over the 4 s it was really covered in, it is a 12 km/h run.
        val young = seg(13.3333, seconds = 1, trackedMs = 4_000L)
        assertEquals(200.0, 13.3333 / (4_000L / 60_000.0), 0.01)
        val kcal = ExerciseEnergy.kcal(ExerciseKind.RUN, steady(200.0, 5) + young, mass)!!
        // 76.1 kcal for the bucket behind it, plus the metres this one has already measured.
        assertEquals(77.0, kcal.toDouble(), 1.0)
    }

    @Test
    fun aRunAcrossBucketBoundariesAlwaysHasAFigure() {
        // The defect this exists for, end to end through the real bucketer. At every phase of the
        // boundary and at both fix cadences, a 12 km/h run must produce a figure at every fix: the
        // recorder scores the open partial on each one, and a null there is the panel's kcal blanking
        // out mid-run.
        for ((cadenceMs, phaseStepMs) in mapOf(
            LocationSource.MIN_TIME_MS to 250L,
            LocationSource.LOW_POWER_MIN_TIME_MS to 500L,
        )) {
            var phaseMs = 0L
            while (phaseMs < cadenceMs) {
                val figures = kcalAfterEachFix(b0 + phaseMs, cadenceMs, paceMPerMin = 200.0, fixes = 400)
                figures.drop(1).forEachIndexed { i, kcal ->
                    assertNotNull("cadence=$cadenceMs phase=$phaseMs fix=${i + 1}", kcal)
                }
                phaseMs += phaseStepMs
            }
        }
    }

    @Test
    fun aBoutFoldedIntoBucketsScoresWhatItsMetresAndSecondsSay() {
        // Folding a bout into five-minute buckets must not move its energy: every metre is scored
        // once, on the gait it was actually covered at. 20 min at 200 m/min is 4000 m over 1200 s, so
        // 0.2 * 4000 + 3.5 * 20 = 870 mL O2/kg and 304.5 kcal at 70 kg.
        val run = kcalAfterEachFix(b0, LocationSource.MIN_TIME_MS, paceMPerMin = 200.0, fixes = 301)
        assertEquals(304.5, run.last()!!.toDouble(), 2.0)
        // And the same 20 min walked: 1666.7 m, so 0.1 * 1666.7 + 3.5 * 20 = 236.7 mL O2/kg, 82.8 kcal.
        val walk = kcalAfterEachFix(b0, LocationSource.MIN_TIME_MS, 83.333, 301, ExerciseKind.WALK)
        assertEquals(82.8, walk.last()!!.toDouble(), 2.0)
    }

    @Test
    fun aSegmentWithNoFixIsSkippedRatherThanVoidingTheBout() {
        // A receiver still acquiring at the start of a walk. That segment measured nothing, so it
        // scores nothing — but it is not evidence of anything implausible either.
        val bout = listOf(seg(null, 300)) + steady(83.333, 55)
        val scored = ExerciseEnergy.kcal(ExerciseKind.WALK, bout, mass)!!
        assertEquals(228.0, scored.toDouble(), 2.0)
    }

    @Test
    fun anHourWalkingAtFiveKmPerHourLandsOnTheAcsmFigure() {
        // S = 83.33 m/min -> VO2 = 11.83 mL/kg/min -> 4.14 kcal/min -> ~249 kcal gross.
        val kcal = ExerciseEnergy.kcal(ExerciseKind.WALK, steady(83.333, 60), mass)!!
        assertEquals(249.0, kcal.toDouble(), 2.0)
    }

    @Test
    fun anHourRunningAtTwelveKmPerHourLandsOnTheAcsmFigure() {
        // S = 200 m/min -> VO2 = 43.5 mL/kg/min -> 15.2 kcal/min -> ~914 kcal gross.
        val kcal = ExerciseEnergy.kcal(ExerciseKind.RUN, steady(200.0, 60), mass)!!
        assertEquals(914.0, kcal.toDouble(), 2.0)
    }

    @Test
    fun aBoutHeldInsideOneGaitScoresTheSameSplitAsWhole() {
        // Both equations are linear in speed, so segmenting a steady bout must not move its figure.
        // That is what makes per-segment scoring free in the ordinary case.
        val whole = ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, 3_600)), mass)!!
        val split = ExerciseEnergy.kcal(ExerciseKind.WALK, steady(83.333, 60), mass)!!
        assertEquals(whole.toDouble(), split.toDouble(), 1.0)
    }

    @Test
    fun aMixedGaitBoutIsScoredSegmentBySegmentAndNotOnItsAverage() {
        // 5 min strolling at 50 m/min then 5 min running at 200 m/min. Per segment: 14.9 + 76.1 = 91.
        // On the 125 m/min average the whole thing crosses into the running branch and reads 100.
        val bout = listOf(seg(250.0, 300), seg(1_000.0, 300))
        assertEquals(91, ExerciseEnergy.kcal(ExerciseKind.WALK, bout, mass))
        assertEquals(100, ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(1_250.0, 600)), mass))
    }

    @Test
    fun theEquationSwitchesFromWalkingToRunningAtOneHundredMetresPerMinute() {
        // Exactly at the threshold the running equation applies; a metre per minute slower is walking,
        // and the discontinuity between them is the equations' own, not a rounding artefact. The gait
        // follows the SPEED within a foot-borne bout: a walk that breaks into a jog is scored as one.
        val running = ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(100.0, 60)), mass)!!
        val walking = ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(99.0, 60)), mass)!!
        assertEquals(8, running)
        assertEquals(5, walking)
        assertTrue(running > walking)
    }

    @Test
    fun energyScalesWithBodyMass() {
        val bout = steady(83.333, 60)
        val light = ExerciseEnergy.kcal(ExerciseKind.WALK, bout, 50.0)!!
        val heavy = ExerciseEnergy.kcal(ExerciseKind.WALK, bout, 100.0)!!
        assertEquals(2.0, heavy.toDouble() / light.toDouble(), 0.02)
    }
}
