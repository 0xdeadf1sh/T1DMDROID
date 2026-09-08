package com.t1dm.ui.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** DISPLAY-ONLY: from [RolledForecast], never ModelPrediction, so it cant reach :calc or alerts. */
class RolledSeries internal constructor(
    val tsMs: LongArray,
    val median: FloatArray,
    /** Nested lower edges, outer→inner. A producer with no interior levels gives ONE pair. */
    val lo: Array<FloatArray>,
    val hi: Array<FloatArray>,
    /** Prefix length inside the validated horizon; steps past it are extrapolated. */
    val validatedSteps: Int,
    val degenerate: Boolean,
    val requestedHours: Double,
) {
    val size: Int get() = tsMs.size
    val isEmpty: Boolean get() = tsMs.isEmpty()
    val extrapolatedSteps: Int get() = (size - validatedSteps).coerceAtLeast(0)
    val maxTsMs: Long? get() = if (isEmpty) null else tsMs.last()

    /** Rolled step nearest [ms], or -1 outside span. INDEX, so caller checks [extrapolatedAt]. */
    fun nearestIndex(ms: Double): Int = nearestWithinHalfStep(tsMs, ms)

    fun extrapolatedAt(i: Int): Boolean = i >= validatedSteps
}

/** Off-thread; the mg/dL fan is unit-converted once. */
suspend fun rolledSeriesOf(
    rolled: RolledForecast?,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevF: ((Double) -> Double)? = null,
): RolledSeries? = withContext(Dispatchers.Default) { buildRolledSeries(rolled, unit, kovatchevF) }

/** Pure. Null when there is nothing to draw. */
fun buildRolledSeries(
    rolled: RolledForecast?,
    unit: UnitSpace,
    kovatchevF: ((Double) -> Double)?,
): RolledSeries? {
    if (rolled == null || rolled.isEmpty) return null
    val n = rolled.size
    fun conv(mgdl: Double): Float = when (unit) {
        UnitSpace.MgDl -> mgdl
        UnitSpace.MmolL -> mgdl / 18.0182
        UnitSpace.Kovatchev -> kovatchevF?.invoke(mgdl) ?: mgdl
    }.toFloat()
    val ts = LongArray(n) { i -> rolled.anchorTsMs + (i + 1L) * rolled.stepMs }
    val median = FloatArray(n) { conv(rolled.medianBg[it]) }
    // Ascending-τ: 0=.05 1=.10 2=.25 3=.50 4=.75 5=.90 6=.95. Pairs outer→inner as buildPredSeries.
    val q = if (n > 0 && rolled.bandsMgdl.size % n == 0) rolled.bandsMgdl.size / n else 0
    val lo: Array<FloatArray>
    val hi: Array<FloatArray>
    if (q >= N_QUANTILES) {
        val loCols = intArrayOf(0, 1, 2)
        val hiCols = intArrayOf(q - 1, q - 2, q - 3)
        lo = Array(3) { b -> FloatArray(n) { i -> conv(rolled.bandsMgdl[i * q + loCols[b]]) } }
        hi = Array(3) { b -> FloatArray(n) { i -> conv(rolled.bandsMgdl[i * q + hiCols[b]]) } }
    } else {
        lo = arrayOf(FloatArray(n) { conv(rolled.lowerBg.getOrElse(it) { rolled.medianBg[it] }) })
        hi = arrayOf(FloatArray(n) { conv(rolled.upperBg.getOrElse(it) { rolled.medianBg[it] }) })
    }
    return RolledSeries(
        tsMs = ts, median = median, lo = lo, hi = hi,
        validatedSteps = rolled.validatedSteps.coerceIn(0, n),
        degenerate = rolled.degenerate,
        requestedHours = rolled.requestedHours,
    )
}

/** Fans terminal outer edge at the shared instant. Drawn once, no double uncertainty state. */
class RolledSeam(val tsMs: Long, val lo: Float, val hi: Float)

/** Where the band opens: one step before the validated boundary, so the tail abuts the prefix. */
internal fun RolledSeries.bandFromIndex(): Int =
    (validatedSteps.coerceIn(0, size) - 1).coerceAtLeast(0)

/** Paints a BAND, not bare median: sound, tail ≥2 steps. Also gates other fans §8.4 correction. */
fun RolledSeries.paintsBand(): Boolean = !degenerate && size - bandFromIndex() >= 2

/** Band's opening lower edge: the fan's when [seam] falls on that instant, else the roll's own. */
internal fun RolledSeries.bandOpenLo(seam: RolledSeam?, band: Int): Float {
    val i = bandFromIndex()
    // Only the OUTERMOST pair meets the fan: the seam carries one uncertainty, not a fan.
    return if (band == 0 && seam != null && seam.tsMs == tsMs[i]) seam.lo else lo[band][i]
}

/** The band's opening upper edge — see [bandOpenLo]. */
internal fun RolledSeries.bandOpenHi(seam: RolledSeam?, band: Int): Float {
    val i = bandFromIndex()
    return if (band == 0 && seam != null && seam.tsMs == tsMs[i]) seam.hi else hi[band][i]
}

// Raw-pixel and roll-independent; one immutable effect serves every draw.
private val EXTRAPOLATED_MEDIAN_DASH: PathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))

/** In [drawPredSeries]s own hand (same alphas/weight): the roll is the cycle re-fed to itself. */
internal fun DrawScope.drawRolledSeries(
    s: RolledSeries,
    absToPx: AbsToPx,
    valToPx: ValToPx,
    lineColor: Color,
    fanColor: Color,
    seam: RolledSeam? = null,
    scratch: Path,
) {
    if (s.isEmpty) return
    fun px(i: Int) = absToPx.of(s.tsMs[i].toDouble())

    // Band starts where the cycle fan stops, opening from its edge; past that no correction is fit.
    if (s.paintsBand()) {
        val from = s.bandFromIndex()
        // `drawPredSeries`'s own order and alphas: innermost first, outermost last and heaviest.
        for (b in s.lo.size - 1 downTo 0) {
            val openLo = s.bandOpenLo(seam, b)
            val openHi = s.bandOpenHi(seam, b)
            fun lo(i: Int) = if (i == from) openLo else s.lo[b][i]
            fun hi(i: Int) = if (i == from) openHi else s.hi[b][i]
            // Reused across frames; see the note in `drawPredSeries`.
            val band = scratch.also { it.reset() }
            for (i in from until s.size) {
                val x = px(i); val y = valToPx.of(hi(i))
                if (i == from) band.moveTo(x, y) else band.lineTo(x, y)
            }
            for (i in s.size - 1 downTo from) band.lineTo(px(i), valToPx.of(lo(i)))
            band.close()
            // A roll with a single pair is the outermost one, so it takes the outermost weight.
            val alpha = if (s.lo.size == 1) 0.16f else 0.06f + 0.05f * (2 - b)
            drawPath(band, fanColor.copy(alpha = alpha))
        }
    }

    // Over the prefix too: no cycle forecast at warm-up, a roll beginning mid-air is unreadable.
    val alpha = if (s.degenerate) 0.5f else 1f
    val effect = if (s.degenerate) EXTRAPOLATED_MEDIAN_DASH else null
    for (i in 0 until s.size - 1) {
        drawLine(
            lineColor.copy(alpha = alpha),
            Offset(px(i), valToPx.of(s.median[i])),
            Offset(px(i + 1), valToPx.of(s.median[i + 1])),
            strokeWidth = 2.4f, cap = StrokeCap.Round, pathEffect = effect,
        )
    }
    if (!s.degenerate) {
        val li = s.size - 1
        drawCircle(lineColor, 3.2f, Offset(px(li), valToPx.of(s.median[li])), style = Stroke(width = 1.6f))
    }
}

/** `SPEC/invariants.md` §6. */
private const val N_QUANTILES = 7
