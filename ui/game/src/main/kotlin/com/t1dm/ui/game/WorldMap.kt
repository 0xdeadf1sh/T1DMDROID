package com.t1dm.ui.game

import com.t1dm.core.model.UnitSpace

/**
 * World scale. Both numbers are physics constants, not display preferences: they fix how a glucose
 * excursion feels under the wheels, so they are frozen for every run rather than fitted per viewport
 * (see [WorldMap] for why that distinction is the whole design).
 *
 * ONE MINUTE OF HISTORY IS ONE METRE. Readings land on the 5-min grid
 * (`T1dmRepository.GRID_MS`), so a reading interval is a 5 m stretch of ground under a 2.8 m car —
 * the trace's own texture is the terrain's texture, and a day is a 4 320 m track — about 43 s at the
 * tune's 101 m/s limiter. There is no longer a tank to run out of: a run ends by crashing or by
 * reaching the present moment, never by distance.
 */
const val METRES_PER_MINUTE = 3f

/**
 * Vertical extent of the play area, and with [METRES_PER_MINUTE] the pair that sets how STEEP the
 * record is to drive. Both were retuned after the first version proved undrivable: at 1 m/min over a
 * 40 m axis an ordinary 3 mg/dL/min drift was a 27° slope and a sensor spike went past the tune's ~48°
 * loop limit, so the car flipped on contact with normal data.
 *
 * It must stay an INTEGER: `TERRAIN_DX_M` divides the 5-minute grid exactly (3 m/min ⇒ 15 m per grid
 * step ⇒ 15 samples), which is what makes the terrain reproduce the trace at every reading. 2.5
 * broke that — 12.5 m against a 1 m dx — and the resampling, chasm-lip and pickup tests all caught it.
 *
 * At 22 m the ground was so gentle the car never left it: measured over a realistic track — 5-minute
 * readings joined linearly, the shape the panel actually draws — it was airborne 0 frames in 900. The
 * jagged `glucose_terrain` test fixture flattered it; real glucose is six straight segments across a
 * half-hour window, with no kink to launch from.
 *
 * Swept against that realistic track (`smooth_airtime_probe`), airtime appears between 45 and 70 and
 * is useful by 100 (~2 % of frames airborne, peak clearance ~10 % of the world height, routine drift
 * a 23° face, no crashes); by 140 the car is flying 76 % of the time and crashes, which is not
 * driving. Hence 100.
 *
 * Neither constant changes what is DRAWN, and this one provably cannot: terrain heights scale with
 * it while the vertical pixel scale is `plotHeight / worldHeight`, so the two cancel and the curve is
 * identical at any value. It is a pure physics knob — relief the car feels, not relief you see.
 */
const val WORLD_HEIGHT_M = 100f

/**
 * Heightfield sample spacing. One metre = one minute, which divides the 5-min reading grid exactly,
 * so resampling a piecewise-linear trace onto this grid is EXACT at every reading and linear between
 * — the terrain is the drawn polyline, not an approximation of it.
 */
const val TERRAIN_DX_M = 1f

/** `t1dm-core::game`'s `MAX_TERRAIN_SAMPLES`. Transcribed only to coarsen [TERRAIN_DX_M] before the
 *  constructor would reject the track; the Rust remains the authority that enforces it. */
internal const val MAX_TERRAIN_SAMPLES = 200_000

/**
 * The frozen value→world and time→world transform for one run.
 *
 * **Why frozen.** [com.t1dm.ui.graph.GlucoseGraph] re-fits `[yMin, yMax]` over the VISIBLE window on
 * every draw — correct for a panel the eye reads, fatal for terrain the car drives: the same hill
 * would be jumpable at one camera position and not another, and the physics would stop being a
 * function of the data. So one map is derived at track build from the frame-GLOBAL extremes and never
 * touched again; the camera then moves over a world that does not move under it.
 *
 * **Why normalised.** The value axis spans 20…250 in mg/dL, 1.1…13.9 in mmol/L and roughly −3.2…+1.5
 * in Kovatchev risk space. Feeding any of those into a solver tuned in metres makes the same car a
 * mountain goat in one unit and immovable in another, so the SPAN — not the value — is what maps onto
 * [worldHeight]. The shape follows the eye; the scale stays constant.
 *
 * Between mg/dL and mmol/L the terrain is then IDENTICAL, exactly: both are affine images of the same
 * mg/dL quantity and [worldValueSpan] deliberately applies no tick rounding (unlike the display's
 * `fixedYRange`, which rounds out to a nice step — a physics scale must not jump because a tick label
 * would). Kovatchev is a genuinely different track: `f` is monotone but nonlinear, so the ordering of
 * every height is preserved while the hypo end is stretched and the hyper end compressed — which is
 * precisely what that unit is for, and it shows up as steeper ground under a low.
 *
 * X is affine in absolute time with no rounding anywhere, which is what lets the paint layer — stored
 * as `(tsMs, yFrac)` — land in the world with no registration step at all.
 */
class WorldMap internal constructor(
    /** Absolute epoch-ms at world x = 0: the start line. */
    val t0Ms: Long,
    val metresPerMinute: Float,
    val worldHeight: Float,
    /** Value (in the trace's unit) at world y = 0, the world floor. */
    val valueLo: Float,
    /** Value at world y = [worldHeight], the world ceiling. */
    val valueHi: Float,
) {
    private val metresPerMs = metresPerMinute.toDouble() / 60_000.0
    private val heightPerValue = worldHeight / (valueHi - valueLo)

    fun worldXOf(tsMs: Long): Float = ((tsMs - t0Ms).toDouble() * metresPerMs).toFloat()

    fun worldXOfMinutes(minutes: Float): Float = minutes * metresPerMinute

    fun tsMsAt(worldX: Float): Long = t0Ms + Math.round(worldX.toDouble() / metresPerMs)

    /** Ground height for a glucose value already in the trace's unit. Unclamped: a reading beyond the
     *  configured axis genuinely is a hill above the ceiling, and clamping would flatten the spike
     *  that made the run interesting. */
    fun worldYOf(value: Float): Float = (value - valueLo) * heightPerValue

    fun valueAt(worldY: Float): Float = valueLo + worldY / heightPerValue

    /**
     * A paint stroke's plot-box height fraction as a world height. `yFrac` is 0 at the plot TOP and 1
     * at the bottom, so it inverts onto a y-up world; values outside `[0, 1]` stay outside, exactly as
     * the panel keeps them (a finger that strayed past the plot is clipped, never flattened).
     *
     * Registration caveat, stated once here because nothing downstream can recover it: `yFrac` records
     * where on the SCREEN the finger was, and `paint_stroke` persists nothing about the axis fit in
     * force when it was drawn — so a stroke cannot be un-projected onto a glucose value. What makes
     * this land right anyway is that [worldValueSpan] uses the same configured axis span the panel
     * pins itself to, which for the default 20–250 mg/dL is near-constant across the whole history.
     * In practice `yFrac` therefore IS a value coordinate; in principle it is not, and art drawn while
     * the axis had grown to fit a 400 mg/dL spike will sit a little low.
     */
    fun worldYOfFrac(yFrac: Float): Float = worldHeight * (1f - yFrac)
}

/**
 * The value span one run maps onto the world height: the configured axis range (converted into the
 * trace's unit) GROWN to include any data beyond it, so no reading is ever clipped out of the terrain
 * — the same rule the panel's `fixedYRange` follows, minus the tick rounding.
 *
 * [kovatchevF] must be the SAME transform the trace was built with; without it [UnitSpace.Kovatchev]
 * falls back to reading the mg/dL bounds raw, matching `buildGraphFrame`'s own fallback rather than
 * fabricating a curve.
 */
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
        // A degenerate axis would divide by ~0 and turn sensor noise into cliffs. Widen about the
        // midpoint rather than the floor so a flat trace stays in the middle of the world.
        val mid = (hi + lo) / 2f
        lo = mid - min / 2f
        hi = mid + min / 2f
    }
    return lo to hi
}

/** Smallest world-mapped span, per unit — the same magnitudes `GlucoseGraph.minValueSpan` uses for
 *  the drawn axis, and for the same reason: below this the ratio amplifies noise into terrain. */
private fun minValueSpan(unit: UnitSpace): Float = when (unit) {
    UnitSpace.MgDl -> 40f
    UnitSpace.MmolL -> 2.2f
    UnitSpace.Kovatchev -> 0.6f
}
