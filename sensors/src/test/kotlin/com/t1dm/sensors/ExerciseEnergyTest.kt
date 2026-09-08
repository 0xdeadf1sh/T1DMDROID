package com.t1dm.sensors

import com.t1dm.core.model.ExerciseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseEnergyTest {

    private val mass = 70.0

    /** [trackedMs] defaults to the whole segment; a test says so where the two intervals differ. */
    private fun seg(distanceM: Double?, seconds: Int, trackedMs: Long = seconds * 1_000L) =
        ExerciseBucket(bucketStartMs = 0L, activeSec = seconds, distanceM = distanceM, trackedMs = trackedMs)

    private fun steady(metresPerMinute: Double, minutes: Int) =
        List(minutes / 5) { seg(metresPerMinute * 5.0, 300) }

    private val b0 = 1_500_000_000_000L

    /** On [ExerciseBucketer]'s own sphere, so a synthetic track measures back as its own metres. */
    private val mPerDegLat = ExerciseBucketer.haversineM(0.0, 0.0, 1.0, 0.0)

    /** Scored after every fix as ExerciseRecorder scores it: closed buckets plus open partial. */
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
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, List(12) { seg(null, 300) }, mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, List(12) { seg(0.0, 300) }, mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, List(12) { seg(Double.NaN, 300) }, mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, emptyList(), mass))
    }

    @Test
    fun withoutAnIntervalToHaveMeasuredTheMetresOverThereIsNoFigure() {
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, 0)), mass))
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, -60)), mass))
        // Metres with no interval name no speed, so no gait and no guard: dropped.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, 300, trackedMs = 0L)), mass))
    }

    @Test
    fun anActivityTheEquationsDoNotDescribeGetsNoFigure() {
        // A 20 km/h ride is 333 m/min: running eq states ~1470 kcal vs ~590, mass carried by bike.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.OTHER, steady(333.333, 60), mass))
        // The point is the activity, not the speed.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.OTHER, steady(83.333, 60), mass))
    }

    @Test
    fun aSpeedNoGaitReachesGetsNoFigure() {
        // 30 km/h is a vehicle whatever the bout was labelled.
        assertNull(ExerciseEnergy.kcal(ExerciseKind.RUN, steady(500.0, 60), mass))
    }

    @Test
    fun aBoutMostlyNotTheExerciseItWasLabelledGetsNoFigure() {
        // 800m/10min + 8km/15min averages 352 m/min, under guard; per-segment drive is 533 m/min.
        val walked = List(2) { seg(400.0, 300) }
        val driven = List(3) { seg(2_666.67, 300) }
        assertNull(ExerciseEnergy.kcal(ExerciseKind.WALK, walked + driven, mass))
        assertNotNull(ExerciseEnergy.kcal(ExerciseKind.WALK, walked, mass))
    }

    @Test
    fun oneVehicleSegmentIsDroppedAndTheRestOfTheBoutIsStillScored() {
        // A dropped segment costs the bout its own five minutes and nothing more.
        val walked = List(11) { seg(416.665, 300) }
        val driven = seg(2_666.67, 300)
        val withIt = ExerciseEnergy.kcal(ExerciseKind.WALK, walked + driven, mass)
        assertNotNull(withIt)
        assertEquals(ExerciseEnergy.kcal(ExerciseKind.WALK, walked, mass), withIt)
        assertEquals(228.0, withIt!!.toDouble(), 2.0)
    }

    @Test
    fun theSpeedIsTakenOverTheIntervalTheMetresWereCoveredOverNotTheBucketsSeconds() {
        // Straddling 4s segment charged to a 1s-old bucket: 13.3m/1s=800m/min, real 4s is 12km/h.
        val young = seg(13.3333, seconds = 1, trackedMs = 4_000L)
        assertEquals(200.0, 13.3333 / (4_000L / 60_000.0), 0.01)
        val kcal = ExerciseEnergy.kcal(ExerciseKind.RUN, steady(200.0, 5) + young, mass)!!
        // 76.1 kcal for the bucket behind it, plus this one's metres so far.
        assertEquals(77.0, kcal.toDouble(), 1.0)
    }

    @Test
    fun aRunAcrossBucketBoundariesAlwaysHasAFigure() {
        // At every phase/cadence, a 12km/h run must have a figure at every fix; null blanks panel.
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
        // 20 min at 200 m/min: 0.2*4000 + 3.5*20 = 870 mL O2/kg, 304.5 kcal at 70 kg.
        val run = kcalAfterEachFix(b0, LocationSource.MIN_TIME_MS, paceMPerMin = 200.0, fixes = 301)
        assertEquals(304.5, run.last()!!.toDouble(), 2.0)
        // The same 20 min walked: 0.1 * 1666.7 + 3.5 * 20 = 236.7 mL O2/kg, 82.8 kcal.
        val walk = kcalAfterEachFix(b0, LocationSource.MIN_TIME_MS, 83.333, 301, ExerciseKind.WALK)
        assertEquals(82.8, walk.last()!!.toDouble(), 2.0)
    }

    @Test
    fun aSegmentWithNoFixIsSkippedRatherThanVoidingTheBout() {
        // A receiver still acquiring measured nothing, and is evidence of nothing implausible.
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
        val whole = ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(5_000.0, 3_600)), mass)!!
        val split = ExerciseEnergy.kcal(ExerciseKind.WALK, steady(83.333, 60), mass)!!
        assertEquals(whole.toDouble(), split.toDouble(), 1.0)
    }

    @Test
    fun aMixedGaitBoutIsScoredSegmentBySegmentAndNotOnItsAverage() {
        // 5 min at 50 m/min then 5 at 200: per segment 14.9 + 76.1 = 91; on the 125 average, 100.
        val bout = listOf(seg(250.0, 300), seg(1_000.0, 300))
        assertEquals(91, ExerciseEnergy.kcal(ExerciseKind.WALK, bout, mass))
        assertEquals(100, ExerciseEnergy.kcal(ExerciseKind.WALK, listOf(seg(1_250.0, 600)), mass))
    }

    @Test
    fun theEquationSwitchesFromWalkingToRunningAtOneHundredMetresPerMinute() {
        // At threshold the running eq applies; a foot-borne bout's gait follows speed, not label.
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
