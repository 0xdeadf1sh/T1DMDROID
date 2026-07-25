package com.t1dm.ui.game

import com.t1dm.core.model.TerrainSpec
import com.t1dm.ui.graph.forEachTraceRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** The BG panel's default axis span (`GraphSettings.BgRange.DEFAULT`), transcribed as the terrain
 *  builder's fallback so `:ui:game` need not depend on `:data`. A host that reads the setting passes
 *  its own; these are only what an un-configured install would have shown. */
const val DEFAULT_AXIS_MIN_MGDL = 20

const val DEFAULT_AXIS_MAX_MGDL = 250

/** Shortest stretch of ground a run may present. A run of one reading — a point isolated between two
 *  dropouts, which `forEachTraceRun` still emits — would otherwise be a single sample wide: a needle
 *  the car cannot land on, and an unwinnable track for no reason the player can see. */
private const val MIN_PLATFORM_M = 4f

/** Ceiling on how far a lip may be widened into the chasm beside it, per side. A run long enough to
 *  land on is never touched, so ordinary chasms keep the exact width the dropout had. */
private const val MAX_LIP_PAD_M = 2f

/** Grid/segment coincidence tolerance, in samples. `xs` are float minutes and the reading grid is
 *  5-minutely, so a lip sample lands on an exact integer — but only up to float error, and losing the
 *  lip to a rounding wobble would open a chasm one sample wider than the dropout was. */
private const val GRID_EPS = 1e-4f

/**
 * A built track: the heightfield the car drives, plus the [WorldMap] every other layer registers
 * against. Everything expensive happened once, off the main thread, in [buildGameTrack].
 *
 * The car drives LEFT→RIGHT, which is forward in time: world x = 0 is the chosen start instant and
 * the right-hand edge is the most recent reading, so `RunState.Finished` is literally arriving at
 * the present moment.
 *
 * [heights] is metres above the world floor at `x = i · dx`, and **NaN marks a chasm** — a stretch
 * the sensor never reported. `t1dm-core::game` reads any non-finite or negative sample as no ground
 * at all, so a dropout is a hole the car falls through rather than a straight line the graph already
 * refuses to draw. Ground heights are non-negative: [worldValueSpan] never raises its floor above the
 * data, and the resampler floors the one place float can still undershoot it (a lerp landing an ulp
 * below the reading it interpolates to), so NaN is the only marker a gap ever has.
 */
class GameTrack internal constructor(
    val map: WorldMap,
    val heights: FloatArray,
    val dx: Float,
    /** Absolute epoch-ms of the start line and the finish line — the window every other layer of the
     *  world (paint, pickups) is derived over. */
    val startMs: Long,
    val endMs: Long,
) {
    /** x of the finish line. Matches `GameWorld.trackLength`, which derives it the same way. */
    val length: Float get() = if (heights.isEmpty()) 0f else (heights.size - 1) * dx

    /**
     * The heightfield as `t1dm-core::game` takes it. [FloatArray.asList] is a VIEW, so the boxed
     * `List<Float>` uniffi lowers costs no second copy of the field — it boxes one element at a time
     * on the way across, once per track.
     */
    val terrain: TerrainSpec = TerrainSpec(heights.asList(), dx, map.worldHeight)

    /** Enough ground to hand to `GameWorld` (`MIN_TERRAIN_SAMPLES` = 2) and something solid on it. */
    val isPlayable: Boolean get() = heights.size >= 2 && heights.any { it.isFinite() }

    /** Ground height at [worldX], or NaN over a chasm / past either end. Piecewise-linear between
     *  samples, exactly as the solver reads it. */
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

    /** Ground height at an absolute instant — the placement rule for everything derived from the
     *  record, since a logged event knows its time and nothing else about the world. */
    fun groundAtMs(tsMs: Long): Float = groundAt(map.worldXOf(tsMs))

    companion object {
        val EMPTY = GameTrack(
            map = WorldMap(0L, METRES_PER_MINUTE, WORLD_HEIGHT_M, 0f, 1f),
            heights = FloatArray(0), dx = TERRAIN_DX_M, startMs = 0L, endMs = 0L,
        )
    }
}

/** Build the track off the main thread. */
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
 * Pure CPU transform — safe from a `@Preview` or a test.
 *
 * Resamples [trace] onto the uniform grid `t1dm-core::game` needs, run by run. The runs come from
 * [forEachTraceRun], the same walk the polyline and the corridor mask use, so the ground is cut at
 * EXACTLY the dropouts the panel refuses to bridge — derive them anywhere else and the terrain gains
 * a hole where the graph shows a line, or a bridge where it shows nothing.
 *
 * [dx] is honoured unless the span would exceed the solver's sample cap, in which case the grid is
 * coarsened rather than the track refused: a year of history is a 525 km track and still has to load.
 * Note this is a level-of-detail knob on the TERRAIN, never on the trace — see [TrackTrace.of].
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
            // The whole run falls strictly between two grid samples — possible only for a lone reading
            // under a grid coarser than the reading cadence. Give it the nearest sample rather than no
            // ground, unless a neighbour already owns it.
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
                // Floored, because the solver reads a NEGATIVE sample as a gap exactly as it reads a
                // non-finite one. `v` here is a lerp, and `a + (b - a) * 1` can land one ulp under `b`
                // in float — enough, when `b` is the run's minimum and the configured axis floor sits
                // on it, to make the trace's nadir a chasm the canvas nonetheless draws as ground.
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

/**
 * Give every run at least [MIN_PLATFORM_M] of ground by extending its lips at constant height into
 * the chasm beside it, never past [MAX_LIP_PAD_M] and never over ground another run already owns.
 * Runs already long enough are untouched, so an ordinary chasm keeps the exact width of the dropout
 * that made it — only a one-reading island grows, and only to the point of being landable.
 */
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
