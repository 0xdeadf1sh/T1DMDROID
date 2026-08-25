package com.t1dm.core.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.UnitSpace

/**
 * The one definition of the read-out's ORDER and WORDING — IOB, then COB, then ICR, then ISF. Layout
 * is not shared: the BG panel renders the parts itself, Meals and Insulin use [IobCobLine].
 */
object OnBoardReadout {

    const val SENSITIVITY_NA = "ICR/ISF N/A"

    fun separator(compact: Boolean): String = if (compact) " · " else "   ·   "

    fun iob(iobU: Double, compact: Boolean): String =
        if (compact) "IOB ${"%.1f".format(iobU)}U" else "IOB ${"%.2f".format(iobU)} U"

    fun cob(cobG: Double, compact: Boolean): String =
        if (compact) "COB ${"%.0f".format(cobG)}g" else "COB ${"%.0f".format(cobG)} g"

    /** A decimal below 10 g/U: `%.0f` renders anything under 0.5 — a small negative included — as a
     *  flat `0g/U`, hiding the sign. */
    fun icr(gPerU: Double, compact: Boolean): String {
        val n = if (Math.abs(gPerU) < 10.0) "%.1f".format(gPerU) else "%.0f".format(gPerU)
        return if (compact) "ICR ${n}g/U" else "ICR $n g/U"
    }

    /**
     * Scales per `SPEC/invariants.md` §3. Kovatchev falls back to mg/dL rather than converting: the
     * risk transform is non-linear and dimensionless, so "risk per unit" is not a patient constant.
     */
    fun isf(isfMgdlPerU: Double, unit: UnitSpace, compact: Boolean): String {
        val (n, u) = when (unit) {
            UnitSpace.MmolL -> "%.1f".format(isfMgdlPerU / MGDL_PER_MMOLL) to "mmol/L/U"
            else -> "%.0f".format(isfMgdlPerU) to "mg/dL/U"
        }
        return if (compact) "ISF $n$u" else "ISF $n $u"
    }

    fun horizon(horizonMs: Long): String {
        val minutes = horizonMs / 60_000L
        return if (minutes % 60L == 0L) "${minutes / 60L}h" else "${minutes}m"
    }

    /** Sign only. Magnitude is deliberately NOT judged: no band survives in `SensitivityProbe`, and
     *  inventing one in a renderer would put back the filter that was removed on purpose. */
    fun suspect(estimate: SensitivityEstimate): Boolean =
        estimate.isfMgdlPerU <= 0.0 || estimate.icrGPerU <= 0.0

    fun sensitivityParts(estimate: SensitivityEstimate?, unit: UnitSpace, compact: Boolean): List<String> =
        if (estimate == null) {
            listOf(SENSITIVITY_NA)
        } else {
            listOf(
                icr(estimate.icrGPerU, compact),
                // The horizon qualifies both figures: what is measured is the marginal response at
                // that window, not the textbook whole-dose meaning the two names carry.
                "${isf(estimate.isfMgdlPerU, unit, compact)} @${horizon(estimate.horizonMs)}",
            )
        }

    private const val MGDL_PER_MMOLL = 18.0182
}

/**
 * The sensitivity pair reads `N/A` rather than blanking: a blank cannot be told from a feature that
 * never shipped. [provenance] is the §3.6-F sub-line naming where IOB came from; Meals passes none.
 */
@Composable
fun IobCobLine(
    iobCob: IobCobReadout,
    sensitivity: SensitivityEstimate?,
    unit: UnitSpace,
    modifier: Modifier = Modifier,
    provenance: String? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val suspectInk = MaterialTheme.colorScheme.error
    val sep = OnBoardReadout.separator(compact = false)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = ink)) {
            append(OnBoardReadout.iob(iobCob.iobU, compact = false))
            append(sep)
            append(OnBoardReadout.cob(iobCob.cobG, compact = false))
        }
        val parts = OnBoardReadout.sensitivityParts(sensitivity, unit, compact = false)
        val marked = sensitivity != null && OnBoardReadout.suspect(sensitivity)
        // Only the figures are marked; the separators stay in the base ink, as the BG panel does.
        parts.forEach {
            withStyle(SpanStyle(color = ink)) { append(sep) }
            withStyle(SpanStyle(color = if (marked) suspectInk else ink)) { append(it) }
        }
    }
    // Horizontally scrollable: four parts do not fit a phone width. `softWrap = false` is what makes
    // the overflow scroll rather than wrap — the container alone still lets Text break. One
    // ScrollState for both lines, so the provenance tracks the figures it qualifies.
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth().horizontalScroll(scroll).padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ink, softWrap = false)
        if (provenance != null) {
            Text(
                provenance,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                softWrap = false,
            )
        }
    }
}
