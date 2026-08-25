package com.t1dm.ui.game

import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.SmoothedTrace

/** Copy-free view over the source arrays; nothing here is mutated. */
class TrackTrace internal constructor(
    val t0Ms: Long,
    val unit: UnitSpace,
    /** Minutes since [t0Ms], ascending, `minutes[0] == 0`. */
    val minutes: FloatArray,
    /** Value already in [unit]. */
    val values: FloatArray,
    /** True where a dropout follows point `i`. */
    val breakAfter: BooleanArray,
    val dataMinY: Float,
    val dataMaxY: Float,
) {
    val size: Int get() = minutes.size
    val isEmpty: Boolean get() = minutes.isEmpty()

    fun absMs(i: Int): Long = t0Ms + Math.round(minutes[i].toDouble() * 60_000.0)

    companion object {
        val EMPTY = TrackTrace(
            0L, UnitSpace.MgDl, FloatArray(0), FloatArray(0), BooleanArray(0), 0f, 0f,
        )

        /** Build [frame] with `maxPoints` ABOVE the reading count: envelope decimation can emit a
         *  bucket's maximum before its minimum, harmless as a drawn line but a false cliff as terrain. */
        fun of(frame: GraphFrame): TrackTrace =
            if (frame.isEmpty) {
                EMPTY
            } else {
                TrackTrace(
                    t0Ms = frame.t0Ms,
                    unit = frame.unit,
                    minutes = frame.xs,
                    values = frame.ys,
                    breakAfter = frame.breakAfter,
                    dataMinY = frame.dataMinY,
                    dataMaxY = frame.dataMaxY,
                )
            }

        fun of(smoothed: SmoothedTrace, unit: UnitSpace): TrackTrace {
            if (smoothed.isEmpty) return EMPTY
            val t0 = smoothed.tsMs[0]
            val minutes = FloatArray(smoothed.size) { ((smoothed.tsMs[it] - t0).toDouble() / 60_000.0).toFloat() }
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (v in smoothed.ys) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return TrackTrace(t0, unit, minutes, smoothed.ys, smoothed.breakAfter, lo, hi)
        }
    }
}
