package com.t1dm.ui.game

import com.t1dm.core.model.UnitSpace

/** Metres of track per minute of history. Frozen for every run, never fitted per viewport. Must stay
 *  an integer: 3 m/min is 15 m per 5-min grid step, an exact multiple of [TERRAIN_DX_M]. */
const val METRES_PER_MINUTE = 3f

/** Vertical extent of the play area. 100 from an airtime sweep over a realistic track: below ~45 the
 *  car never leaves the ground, by 140 it flies 76 % of frames and crashes. Physics only — heights
 *  scale with it and the pixel scale divides by it, so nothing drawn changes. */
const val WORLD_HEIGHT_M = 100f

/** Heightfield sample spacing, metres. Divides the 5-min reading grid exactly, so the resampled
 *  terrain is exact at every reading and linear between. */
const val TERRAIN_DX_M = 1f

/** Transcribed from `t1dm-core::game`, which remains the authority that enforces it. */
internal const val MAX_TERRAIN_SAMPLES = 200_000

/** Derived once at track build from the frame-GLOBAL extremes: [com.t1dm.ui.graph.GlucoseGraph]
 *  re-fits per visible window, and terrain that moved under the camera would stop being a function of
 *  the data. The SPAN maps onto [worldHeight], with no tick rounding unlike the display's `fixedYRange`. */
class WorldMap internal constructor(
    /** Absolute epoch-ms at world x = 0. */
    val t0Ms: Long,
    val metresPerMinute: Float,
    val worldHeight: Float,
    /** Value in the trace's unit at world y = 0. */
    val valueLo: Float,
    /** Value at world y = [worldHeight]. */
    val valueHi: Float,
) {
    private val metresPerMs = metresPerMinute.toDouble() / 60_000.0
    private val heightPerValue = worldHeight / (valueHi - valueLo)

    fun worldXOf(tsMs: Long): Float = ((tsMs - t0Ms).toDouble() * metresPerMs).toFloat()

    fun worldXOfMinutes(minutes: Float): Float = minutes * metresPerMinute

    fun tsMsAt(worldX: Float): Long = t0Ms + Math.round(worldX.toDouble() / metresPerMs)

    /** Value in the trace's unit. Unclamped: a reading past the axis is a hill above the ceiling. */
    fun worldYOf(value: Float): Float = (value - valueLo) * heightPerValue

    fun valueAt(worldY: Float): Float = valueLo + worldY / heightPerValue

    /** `yFrac` is 0 at the plot TOP, so it inverts onto the y-up world; outside `[0, 1]` stays
     *  outside. `paint_stroke` records no axis fit, so a stroke drawn while the axis had grown to fit
     *  a spike sits a little low. */
    fun worldYOfFrac(yFrac: Float): Float = worldHeight * (1f - yFrac)
}

/** The configured axis range in the trace's unit, grown to cover any data beyond it. [kovatchevF]
 *  must be the SAME transform the trace was built with; without it Kovatchev reads the mg/dL bounds
 *  raw, matching `buildGraphFrame`'s own fallback. */
fun worldValueSpan(
    dataMin: Float,
    dataMax: Float,
    unit: UnitSpace,
    rangeMinMgdl: Int,
    rangeMaxMgdl: Int,
    kovatchevF: ((Double) -> Double)? = null,
): Pair<Float, Float> {
    fun conv(mgdl: Int): Float = when (unit) {
        UnitSpace.MgDl -> mgdl.toFloat()
        UnitSpace.MmolL -> (mgdl / 18.0182).toFloat()
        UnitSpace.Kovatchev -> (kovatchevF?.invoke(mgdl.toDouble()) ?: mgdl.toDouble()).toFloat()
    }

    var lo = minOf(dataMin, conv(rangeMinMgdl))
    var hi = maxOf(dataMax, conv(rangeMaxMgdl))
    if (!lo.isFinite() || !hi.isFinite()) {
        lo = conv(rangeMinMgdl)
        hi = conv(rangeMaxMgdl)
    }
    val min = minValueSpan(unit)
    if (hi - lo < min) {
        // A ~0 span turns noise into cliffs; widen about the midpoint so a flat trace stays centred.
        val mid = (hi + lo) / 2f
        lo = mid - min / 2f
        hi = mid + min / 2f
    }
    return lo to hi
}

/** The same magnitudes `GlucoseGraph.minValueSpan` uses for the drawn axis. */
private fun minValueSpan(unit: UnitSpace): Float = when (unit) {
    UnitSpace.MgDl -> 40f
    UnitSpace.MmolL -> 2.2f
    UnitSpace.Kovatchev -> 0.6f
}
