package com.t1dm.feature.twin

import kotlin.math.abs

/** The head-turn shots: per yaw bin, the sharp frame nearest the bin's centre. */
internal class FaceSweep {
    private val best = arrayOfNulls<FaceFrame>(BINS.size)
    private val miss = FloatArray(BINS.size) { Float.MAX_VALUE }

    fun filled(bin: Int): Boolean = best[bin] != null

    val count: Int get() = best.count { it != null }

    val done: Boolean get() = count == BINS.size

    /** The user's own left first, then right. */
    val turningLeft: Boolean get() = !(filled(0) && filled(1))

    /** True when [f] filled an empty bin. */
    fun offer(f: FaceFrame): Boolean {
        if (f.pixels == null || abs(f.pitchDeg) > MAX_PITCH_DEG) return false
        if ((f.metrics?.sharpness ?: 0f) < FaceMetrics.MIN_SHARP) return false
        var fresh = false
        for (i in BINS.indices) {
            val e = abs(f.yawDeg - BINS[i])
            if (e < TOLERANCE_DEG && e < miss[i]) {
                if (best[i] == null) fresh = true
                best[i] = f
                miss[i] = e
            }
        }
        return fresh
    }

    fun shots(): List<FaceFrame> = best.filterNotNull()

    companion object {
        /** Degrees of yaw, + = the user's own left, as [FaceFrame.yawDeg]. */
        val BINS = floatArrayOf(30f, 15f, -15f, -30f)
        const val TOLERANCE_DEG = 7f
        const val MAX_PITCH_DEG = 20f
        const val RANGE_DEG = 40f
    }
}
