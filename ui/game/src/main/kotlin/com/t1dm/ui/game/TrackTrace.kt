package com.t1dm.ui.game

import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.SmoothedTrace

/**
 * The one drawn line the track is cut from, in whichever form the panel is currently showing it.
 *
 * "Smoothed" is a SWAP on the BG panel, not an overlay (there is only ever one trace on screen), so
 * the game inherits exactly that: [of] a [GraphFrame] when the toggle is off, [of] a [SmoothedTrace]
 * when it is on. Same unit, same values, same dropout cuts — drive over what you were looking at.
 *
 * Deliberately a copy-free view over the source arrays: nothing here is mutated, and the terrain build
 * reads each exactly once.
 */
class TrackTrace internal constructor(
    val t0Ms: Long,
    val unit: UnitSpace,
    /** Minutes since [t0Ms], ascending, `minutes[0] == 0`. */
    val minutes: FloatArray,
    /** Value already in [unit] — the number the panel plotted. */
    val values: FloatArray,
    /** True where a genuine dropout follows point `i`; the ground is cut there, not bridged. */
    val breakAfter: BooleanArray,
    val dataMinY: Float,
    val dataMaxY: Float,
) {
    val size: Int get() = minutes.size
    val isEmpty: Boolean get() = minutes.isEmpty()

    /** Absolute epoch-ms of point [i]. */
    fun absMs(i: Int): Long = t0Ms + Math.round(minutes[i].toDouble() * 60_000.0)

    companion object {
        val EMPTY = TrackTrace(
            0L, UnitSpace.MgDl, FloatArray(0), FloatArray(0), BooleanArray(0), 0f, 0f,
        )

        /**
         * The raw trace. Pass a frame built with a [GraphFrame] `maxPoints` ABOVE the reading count:
         * min/max envelope decimation keeps each bucket's extremes in time order, so a bucket whose
         * maximum precedes its minimum emits them adjacent — harmless as a drawn line, but as terrain
         * a near-vertical cliff whose position is a function of bucket boundaries rather than of the
         * data. 90 days at the 5-min grid is only ~25 920 points; the terrain grid, not the frame, is
         * where level-of-detail belongs.
         */
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

        /** The smoothed trace: absolute stamps rebased onto minutes, extremes scanned (a
         *  [SmoothedTrace] carries none — the panel re-fits them per window). */
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
