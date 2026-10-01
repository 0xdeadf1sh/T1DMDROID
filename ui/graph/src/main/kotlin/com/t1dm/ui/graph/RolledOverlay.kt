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

/** DISPLAY-ONLY rolled forecast; never a ModelPrediction, so it can't reach calc or alerts. */
class RolledSeries internal constructor(
    val tsMs: LongArray,
    val median: FloatArray,
    /** Nested lower edges outer→inner; a roll with no interior levels has ONE pair. */
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

    /** Nearest rolled step to [ms], or -1 outside span; caller must check extrapolatedAt too. */
    fun nearestIndex(ms: Double): Int = nearestWithinHalfStep(tsMs, ms)

    fun extrapolatedAt(i: Int): Boolean = i >= validatedSteps
}

/** Off-thread; the mg/dL fan is unit-converted once. */
suspend fun rolledSeriesOf(
    rolled: RolledForecast?,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevFClinicalBatch: ((DoubleArray) -> DoubleArray)? = null,
): RolledSeries? = withContext(Dispatchers.Default) { buildRolledSeries(rolled, unit, kovatchevFClinicalBatch) }

/** Pure. Null when there is nothing to draw. */
fun buildRolledSeries(
    rolled: RolledForecast?,
    unit: UnitSpace,
    kovatchevFClinicalBatch: ((DoubleArray) -> DoubleArray)?,
): RolledSeries? {
    if (rolled == null || rolled.isEmpty) return null
    val n = rolled.size
    val ts = LongArray(n) { i -> rolled.anchorTsMs + (i + 1L) * rolled.stepMs }
    // τ columns ascend 0=.05..6=.95; fan pairs outer→inner, matching buildPredSeries' order.
    val q = if (n > 0 && rolled.bandsMgdl.size % n == 0) rolled.bandsMgdl.size / n else 0
    val pairs = if (q >= N_QUANTILES) 3 else 1
    // Lanes of n: median, then lo outer→inner, then hi outer→inner.
    val mgdl = DoubleArray((1 + 2 * pairs) * n)
    for (i in 0 until n) {
        mgdl[i] = rolled.medianBg[i]
        if (pairs == 3) {
            for (b in 0 until 3) {
                mgdl[(1 + b) * n + i] = rolled.bandsMgdl[i * q + b]
                mgdl[(4 + b) * n + i] = rolled.bandsMgdl[i * q + q - 1 - b]
            }
        } else {
            mgdl[n + i] = rolled.lowerBg.getOrElse(i) { rolled.medianBg[i] }
            mgdl[2 * n + i] = rolled.upperBg.getOrElse(i) { rolled.medianBg[i] }
        }
    }
    val v = toUnit(mgdl, unit, kovatchevFClinicalBatch) ?: return null
    fun lane(k: Int) = v.copyOfRange(k * n, (k + 1) * n)
    val median = lane(0)
    val lo = Array(pairs) { lane(1 + it) }
    val hi = Array(pairs) { lane(1 + pairs + it) }
    return RolledSeries(
        tsMs = ts, median = median, lo = lo, hi = hi,
        validatedSteps = rolled.validatedSteps.coerceIn(0, n),
        degenerate = rolled.degenerate,
        requestedHours = rolled.requestedHours,
    )
}

/** Fan's terminal outer edge at the instant it meets the roll; tsMs is checked, not assumed. */
class RolledSeam(val tsMs: Long, val lo: Float, val hi: Float)

/** Where the band opens: one step before the validated boundary, so the tail abuts the prefix. */
internal fun RolledSeries.bandFromIndex(): Int =
    (validatedSteps.coerceIn(0, size) - 1).coerceAtLeast(0)

/** True if the roll paints a band (sound, tail≥2 steps); shared predicate for §8.4 gating too. */
fun RolledSeries.paintsBand(): Boolean = !degenerate && size - bandFromIndex() >= 2

/** Band's opening lower edge: the fan's if [seam] falls on that instant, else the roll's own. */
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

/** Drawn like drawPredSeries deliberately — the roll is the cycle forecast re-fed to itself. */
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

    // Band starts where the cycle fan stops; past that boundary no correction may be invented.
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

    // Drawn over the prefix too — during warm-up there's no cycle forecast to anchor against.
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
