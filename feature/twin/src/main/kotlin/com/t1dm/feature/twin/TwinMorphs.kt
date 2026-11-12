package com.t1dm.feature.twin

import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait

/** Scan outputs → morph weights in [TwinBody.morphNames] order; a missing output stays at 0. */
object TwinMorphs {
    private const val YOUNG_Y = 25.0
    private const val OLD_Y = 90.0
    private const val NEUTRAL_BMI = 22.0
    private const val BMI_SPAN_UP = 13.0
    private const val BMI_SPAN_DOWN = 5.0

    fun weights(scan: TwinScan?, body: TwinBody): FloatArray {
        val w = FloatArray(body.morphNames.size)
        if (scan == null) return w
        fun set(name: String, v: Double) {
            val i = body.morphIndex(name)
            if (i >= 0) w[i] = v.coerceIn(-1.0, 1.0).toFloat()
        }
        scan[TwinTrait.SEX]?.let { set("sex", 2 * it - 1) }
        scan[TwinTrait.AGE]?.let { set("age", ((it - YOUNG_Y) / (OLD_Y - YOUNG_Y)).coerceAtLeast(0.0)) }
        scan[TwinTrait.MUSCLE]?.let { m ->
            set("muscle_up", (2 * (m - 0.5)).coerceAtLeast(0.0))
            set("muscle_down", (2 * (0.5 - m)).coerceAtLeast(0.0))
        }
        val h = scan[TwinTrait.HEIGHT]
        val kg = scan[TwinTrait.WEIGHT]
        if (h != null && kg != null) {
            val bmi = kg / ((h / 100) * (h / 100))
            set("weight_up", ((bmi - NEUTRAL_BMI) / BMI_SPAN_UP).coerceAtLeast(0.0))
            set("weight_down", ((NEUTRAL_BMI - bmi) / BMI_SPAN_DOWN).coerceAtLeast(0.0))
        }
        if (h != null) fitHeight(w, h.toFloat(), body)
        return w
    }

    /** Height morphs are near-linear in cm, so one secant step lands within a millimetre. */
    private fun fitHeight(w: FloatArray, targetCm: Float, body: TwinBody) {
        val up = body.morphIndex("height_up")
        val down = body.morphIndex("height_down")
        if (up < 0 || down < 0) return
        val base = body.heightCm(w)
        val probe = w.copyOf()
        val i = if (targetCm >= base) up else down
        probe[i] = 1f
        val gain = body.heightCm(probe) - base
        if (gain == 0f) return
        w[i] = ((targetCm - base) / gain).coerceIn(0f, 1f)
    }
}
