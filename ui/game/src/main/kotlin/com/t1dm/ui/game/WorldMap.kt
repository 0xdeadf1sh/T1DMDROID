package com.t1dm.ui.game

import com.t1dm.core.model.UnitSpace

/** Metres of track per minute, frozen per run; integer, exact multiple of TERRAIN_DX_M. */
const val METRES_PER_MINUTE = 3f

/** Vertical extent of play area, tuned from an airtime sweep; physics only, drawing unaffected. */
const val WORLD_HEIGHT_M = 100f

/** Heightfield sample spacing, metres; divides the 5-min grid, terrain exact at each reading. */
const val TERRAIN_DX_M = 1f

/** Transcribed from `t1dm-core::game`, which remains the authority that enforces it. */
internal const val MAX_TERRAIN_SAMPLES = 200_000

/** Derived once at build from frame-GLOBAL extremes, unlike GlucoseGraph's per-window fit. */
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

    /** Value in the trace's unit; unclamped, a reading past the axis is a hill past the ceiling. */
    fun worldYOf(value: Float): Float = (value - valueLo) * heightPerValue

    fun valueAt(worldY: Float): Float = valueLo + worldY / heightPerValue

    /** yFrac is 0 at plot TOP, inverting onto y-up; paint_stroke ignores axis growth after fit. */
    fun worldYOfFrac(yFrac: Float): Float = worldHeight * (1f - yFrac)
}

/** Configured axis range, grown to cover data past it. kovatchevF must match the trace's build. */
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
        // A ~0 span turns noise into cliffs; widen about midpoint so a flat trace stays centred.
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
