package com.t1dm.feature.twin

import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinTrait

/** Scan outputs → morph weights; origin and span mirror `tools/twin-mesh/build.py`. */
object TwinMorphs {
    private const val REF_CM = 170.0
    private const val REF_KG = 70.0
    private const val REF_AGE = 40.0
    private const val SPAN = 10.0

    /** Morphs are linear in these features (fitted to ANSUR II and NHANES); missing = origin. */
    fun weights(scan: TwinScan?, body: TwinBody): FloatArray {
        val w = FloatArray(body.morphNames.size)
        if (scan == null) return w
        fun set(name: String, v: Double) {
            val i = body.morphIndex(name)
            if (i >= 0) w[i] = v.toFloat()
        }
        val h = scan[TwinTrait.HEIGHT]?.let { (it - REF_CM) / SPAN } ?: 0.0
        val m = scan[TwinTrait.WEIGHT]?.let { (it - REF_KG) / SPAN } ?: 0.0
        val a = scan[TwinTrait.AGE]?.let { (it - REF_AGE) / SPAN } ?: 0.0
        val p = scan[TwinTrait.SEX]?.let { it - 0.5 } ?: 0.0
        set("stature", h)
        set("mass", m)
        set("age", a)
        set("male", p)
        set("male_stature", p * h)
        set("male_mass", p * m)
        set("male_age", p * a)
        scan[TwinTrait.MUSCLE]?.let { mu ->
            set("muscle_up", (2 * (mu - 0.5)).coerceIn(0.0, 1.0))
            set("muscle_down", (2 * (0.5 - mu)).coerceIn(0.0, 1.0))
        }
        scan[TwinTrait.HEIGHT]?.let { fitHeight(w, it.toFloat(), body) }
        return w
    }

    /** Stature is near-linear in cm; one secant step absorbs what the other morphs add. */
    private fun fitHeight(w: FloatArray, targetCm: Float, body: TwinBody) {
        val i = body.morphIndex("stature")
        if (i < 0) return
        val base = body.heightCm(w)
        val probe = w.copyOf()
        probe[i] += 1f
        val gain = body.heightCm(probe) - base
        if (gain <= 0f) return
        w[i] += (targetCm - base) / gain
    }
}
