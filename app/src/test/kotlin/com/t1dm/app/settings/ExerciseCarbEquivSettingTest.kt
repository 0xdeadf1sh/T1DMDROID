package com.t1dm.app.settings

import com.t1dm.data.curve.ExerciseDisposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carb-equivalent knob's persistence contract and its placement.
 *
 * It is the single per-patient number in `../T1DMCOMMON/SPEC/invariants.md` §5's exercise disposal
 * curve, so the two things worth pinning are that the store and the curve resolver cannot disagree
 * about its default or its rails — a slider offering a value the resolver would clamp is a slider
 * that lies — and that it is exportable while the body mass beside it is not.
 */
class ExerciseCarbEquivSettingTest {

    @Test
    fun `the default is SPEC's population constant, read from the resolver rather than restated`() {
        assertEquals(ExerciseDisposal.DEFAULT_CARB_EQUIV_PER_MIN, SettingsStore.decodeCarbEquivPerMin(null), EPS)
        assertEquals(0.5, SettingsStore.decodeCarbEquivPerMin(null), EPS)
    }

    @Test
    fun `a set value round-trips`() {
        for (g in listOf(0.1, 0.3, 0.5, 0.9, 1.5)) {
            assertEquals(g, SettingsStore.decodeCarbEquivPerMin(SettingsStore.encodeCarbEquivPerMin(g)), EPS)
        }
    }

    @Test
    fun `both directions clamp to the resolver's own rails`() {
        val min = ExerciseDisposal.MIN_CARB_EQUIV_PER_MIN
        val max = ExerciseDisposal.MAX_CARB_EQUIV_PER_MIN
        assertTrue(min < max)
        assertEquals(max, SettingsStore.decodeCarbEquivPerMin(SettingsStore.encodeCarbEquivPerMin(9.0)), EPS)
        assertEquals(min, SettingsStore.decodeCarbEquivPerMin(SettingsStore.encodeCarbEquivPerMin(0.0)), EPS)
        assertEquals(max, SettingsStore.decodeCarbEquivPerMin("100"), EPS)
        assertEquals(min, SettingsStore.decodeCarbEquivPerMin("-4"), EPS)
    }

    @Test
    fun `a garbled or empty row reads as the default, never as zero`() {
        // Zero would mean a bout disposes of nothing, which is not a setting anybody chose.
        assertEquals(0.5, SettingsStore.decodeCarbEquivPerMin("half"), EPS)
        assertEquals(0.5, SettingsStore.decodeCarbEquivPerMin(""), EPS)
        assertEquals(0.5, SettingsStore.decodeCarbEquivPerMin("NaN"), EPS)
        assertEquals(0.5, SettingsStore.encodeCarbEquivPerMin(Double.NaN).toDouble(), EPS)
    }

    @Test
    fun `the rate is exportable configuration, and it does not carry the body mass with it`() {
        assertTrue(SettingsStore.isConfigKey(SettingsStore.K_EXERCISE_CARB_EQUIV))
        // By exact key and not an `exercise.` prefix: the body mass is the user's own and stays out.
        assertFalse(SettingsStore.isConfigKey(SettingsStore.K_EXERCISE_BODY_MASS_KG))
        assertFalse(SettingsStore.isConfigKey("exercise.anything_else"))
    }

    @Test
    fun `an imported value is coerced, because a hand-edited file is the one writer no slider bounds`() {
        val coerce = SettingsStore.CONFIG_COERCE[SettingsStore.K_EXERCISE_CARB_EQUIV]!!
        assertEquals(ExerciseDisposal.MAX_CARB_EQUIV_PER_MIN, coerce("40")!!.toDouble(), EPS)
        assertEquals(null, coerce("plenty"))
    }

    private companion object {
        const val EPS = 1e-9
    }
}
