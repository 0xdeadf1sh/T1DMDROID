package com.t1dm.ui.game

import com.t1dm.core.model.TerrainSpec
import com.t1dm.ui.graph.forEachTraceRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** `GraphSettings.BgRange.DEFAULT`, transcribed so `:ui:game` need not depend on `:data`. */
const val DEFAULT_AXIS_MIN_MGDL = 20

const val DEFAULT_AXIS_MAX_MGDL = 250

/** Shortest ground a run may present: a one-reading run is otherwise a needle the car cannot land on. */
private const val MIN_PLATFORM_M = 4f

/** Per-side ceiling on widening a lip into the chasm beside it. */
private const val MAX_LIP_PAD_M = 2f

/** Grid coincidence tolerance, in samples: a lip lands on an exact integer only up to float error. */
private const val GRID_EPS = 1e-4f

/**
 * World x = 0 is the start instant and x grows with time. [heights] is metres above the world floor
 * at `x = i · dx`; NaN marks a chasm, and is the only gap marker — the core reads non-finite OR
 * negative as no ground, and heights here are floored non-negative.
 */
class GameTrack internal constructor(
    val map: WorldMap,
    val heights: FloatArray,
    val dx: Float,
    /** Epoch-ms of the start and finish lines. */
    val startMs: Long,
    val endMs: Long,
) {
    /** Matches `GameWorld.trackLength`, derived the same way. */
    val length: Float get() = if (heights.isEmpty()) 0f else (heights.size - 1) * dx

    /** [FloatArray.asList] is a VIEW: uniffi boxes one element at a time, not a second copy. */
    val terrain: TerrainSpec = TerrainSpec(heights.asList(), dx, map.worldHeight)

    /** `GameWorld`'s `MIN_TERRAIN_SAMPLES` is 2, plus something solid on it. */
    val isPlayable: Boolean get() = heights.size >= 2 && heights.any { it.isFinite() }

    /** NaN over a chasm or past either end. Piecewise-linear, as the solver reads it. */
    fun groundAt(worldX: Float): Float {
        val n = heights.size
        if (n == 0 || !worldX.isFinite()) return Float.NaN
        val u = worldX / dx
        if (u < -GRID_EPS || u > (n - 1) + GRID_EPS) return Float.NaN
        val i = floor(u).toInt().coerceIn(0, n - 1)
        val a = heights[i]
        val f = u - i
        if (i == n - 1 || f <= 0f) return a
        val b = heights[i + 1]
        if (a.isNaN() || b.isNaN()) return Float.NaN
        return a + (b - a) * f
    }

    fun groundAtMs(tsMs: Long): Float = groundAt(map.worldXOf(tsMs))

    companion object {
        val EMPTY = GameTrack(
            map = WorldMap(0L, METRES_PER_MINUTE, WORLD_HEIGHT_M, 0f, 1f),
            heights = FloatArray(0), dx = TERRAIN_DX_M, startMs = 0L, endMs = 0L,
        )
    }
}

suspend fun gameTrackOf(
    trace: TrackTrace,
    rangeMinMgdl: Int = DEFAULT_AXIS_MIN_MGDL,
    rangeMaxMgdl: Int = DEFAULT_AXIS_MAX_MGDL,
    kovatchevF: ((Double) -> Double)? = null,
    metresPerMinute: Float = METRES_PER_MINUTE,
    worldHeight: Float = WORLD_HEIGHT_M,
    dx: Float = TERRAIN_DX_M,
): GameTrack = withContext(Dispatchers.Default) {
    buildGameTrack(trace, rangeMinMgdl, rangeMaxMgdl, kovatchevF, metresPerMinute, worldHeight, dx)
}

/**
 * Pure CPU — safe from a `@Preview`. Runs come from [forEachTraceRun], the walk the polyline and the
 * corridor mask use, so the ground is cut at exactly the dropouts the panel refuses to bridge.
 * [dx] is coarsened past the solver's sample cap — level of detail on the terrain, never the trace.
 */
fun buildGameTrack(
    trace: TrackTrace,
    rangeMinMgdl: Int = DEFAULT_AXIS_MIN_MGDL,
    rangeMaxMgdl: Int = DEFAULT_AXIS_MAX_MGDL,
    kovatchevF: ((Double) -> Double)? = null,
    metresPerMinute: Float = METRES_PER_MINUTE,
    worldHeight: Float = WORLD_HEIGHT_M,
    dx: Float = TERRAIN_DX_M,
): GameTrack {
    if (trace.isEmpty) return GameTrack.EMPTY

    val (valueLo, valueHi) =
        worldValueSpan(trace.dataMinY, trace.dataMaxY, trace.unit, rangeMinMgdl, rangeMaxMgdl, kovatchevF)
    val map = WorldMap(trace.t0Ms, metresPerMinute, worldHeight, valueLo, valueHi)

    val last = trace.size - 1
    val spanM = map.worldXOfMinutes(trace.minutes[last])
    var step = dx
    var n = floor(spanM / step).toInt() + 1
    if (n > MAX_TERRAIN_SAMPLES) {
        n = MAX_TERRAIN_SAMPLES
        step = spanM / (n - 1)
    }
    n = n.coerceAtLeast(2)

    val heights = FloatArray(n) { Float.NaN }
    val runLo = IntArray(trace.size)
    val runHi = IntArray(trace.size)
    var runs = 0

    forEachTraceRun(0, last, { trace.breakAfter[it] }) { a, b ->
        val xa = map.worldXOfMinutes(trace.minutes[a])
        val xb = map.worldXOfMinutes(trace.minutes[b])
        var iLo = ceil(xa / step - GRID_EPS).toInt().coerceIn(0, n - 1)
        var iHi = floor(xb / step + GRID_EPS).toInt().coerceIn(0, n - 1)
        if (iLo > iHi) {
            // The run falls between two grid samples — a lone reading under a coarse grid. Give it the
            // nearest sample rather than no ground, unless a neighbour already owns it.
            val i = Math.round(((xa + xb) / 2f) / step).coerceIn(0, n - 1)
            if (heights[i].isNaN()) {
                heights[i] = map.worldYOf(trace.values[a])
                iLo = i
                iHi = i
            } else {
                return@forEachTraceRun
            }
        } else {
            var j = a
            for (i in iLo..iHi) {
                val m = (i * step) / metresPerMinute
                while (j < b && trace.minutes[j + 1] < m) j++
                val v = when {
                    j >= b -> trace.values[b]
                    m <= trace.minutes[j] -> trace.values[j]
                    else -> {
                        val t = (m - trace.minutes[j]) / (trace.minutes[j + 1] - trace.minutes[j])
                        trace.values[j] + (trace.values[j + 1] - trace.values[j]) * t.coerceIn(0f, 1f)
                    }
                }
                // Floored: the solver reads a negative sample as a gap, and a lerp can land an ulp
                // under the run's minimum, which at the axis floor turns the nadir into a chasm.
                heights[i] = max(0f, map.worldYOf(v))
            }
        }
        runLo[runs] = iLo
        runHi[runs] = iHi
        runs++
    }

    widenShortPlatforms(heights, runLo, runHi, runs, step)
    return GameTrack(map, heights, step, trace.t0Ms, trace.absMs(last))
}

/** Extend a short run's lips at constant height into the chasm beside it, never over ground another
 *  run owns. A run already long enough is untouched, so its chasm keeps the dropout's exact width. */
private fun widenShortPlatforms(
    heights: FloatArray, runLo: IntArray, runHi: IntArray, runs: Int, step: Float,
) {
    val maxPad = floor(MAX_LIP_PAD_M / step).toInt()
    if (maxPad <= 0) return
    for (r in 0 until runs) {
        val lo = runLo[r]
        val hi = runHi[r]
        val widthM = (hi - lo) * step
        if (widthM >= MIN_PLATFORM_M) continue
        val pad = ceil(((MIN_PLATFORM_M - widthM) / 2f) / step).toInt().coerceAtMost(maxPad)
        val loH = heights[lo]
        val hiH = heights[hi]
        for (k in 1..pad) {
            val i = lo - k
            if (i < 0 || !heights[i].isNaN()) break
            heights[i] = loH
        }
        for (k in 1..pad) {
            val i = hi + k
            if (i >= heights.size || !heights[i].isNaN()) break
            heights[i] = hiH
        }
    }
}
