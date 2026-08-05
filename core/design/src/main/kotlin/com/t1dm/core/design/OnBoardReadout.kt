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
 * The one definition of what is on board and how it is worded, for every panel that says so.
 *
 * Three screens carry this read-out — the BG panel, Meals and Insulin — and until now each wrote its
 * own. They drifted exactly as the suite's never-a-second-copy rule predicts: Meals said
 * `COB · IOB` while the other two said `IOB · COB`, so the same two numbers swapped places as the
 * user moved between panels. Nothing was wrong on any one screen, which is why it survived.
 *
 * What is shared here is the ORDER and the WORDING — IOB, then COB, then ICR, then ISF. What is not
 * shared is the layout: the BG panel's line is dense and rides a horizontally-scrollable row beside
 * the forecast countdown, so it renders these parts itself through [Part]; Meals and Insulin have a
 * line to themselves and use [IobCobLine]. One set of facts, two densities.
 */
object OnBoardReadout {

    /** What the panel prints when no model response was obtained at all (see `SensitivityProbe`). */
    const val SENSITIVITY_NA = "ICR/ISF N/A"

    /** The separator between parts. The BG panel packs them tighter than the roomy panels do. */
    fun separator(compact: Boolean): String = if (compact) " · " else "   ·   "

    fun iob(iobU: Double, compact: Boolean): String =
        if (compact) "IOB ${"%.1f".format(iobU)}U" else "IOB ${"%.2f".format(iobU)} U"

    fun cob(cobG: Double, compact: Boolean): String =
        if (compact) "COB ${"%.0f".format(cobG)}g" else "COB ${"%.0f".format(cobG)} g"

    /**
     * Grams per unit, with a decimal only where whole grams would lose the figure: nothing bounds the
     * ratio, and `%.0f` renders anything under 0.5 — including a negative near zero — as a flat
     * `0g/U`, which is the one rendering that would hide the sign the reader needs.
     */
    fun icr(gPerU: Double, compact: Boolean): String {
        val n = if (Math.abs(gPerU) < 10.0) "%.1f".format(gPerU) else "%.0f".format(gPerU)
        return if (compact) "ICR ${n}g/U" else "ICR $n g/U"
    }

    /**
     * The ISF in the display unit. mg/dL and mmol/L are the same quantity in two scales
     * (`SPEC/invariants.md` §3 — 18.0182, not 18.0), so both are shown per unit of insulin.
     *
     * Kovatchev falls back to mg/dL rather than converting. The risk transform is non-linear and
     * dimensionless, so "risk per unit" is not a constant of the patient and a number carrying that
     * label would mean nothing at a second BG — the one place the axis unit is deliberately not
     * followed.
     */
    fun isf(isfMgdlPerU: Double, unit: UnitSpace, compact: Boolean): String {
        val (n, u) = when (unit) {
            UnitSpace.MmolL -> "%.1f".format(isfMgdlPerU / MGDL_PER_MMOLL) to "mmol/L/U"
            else -> "%.0f".format(isfMgdlPerU) to "mg/dL/U"
        }
        return if (compact) "ISF $n$u" else "ISF $n $u"
    }

    /** The probe's window, as the shortest true thing: "2h", or "90m" when it is not whole hours. */
    fun horizon(horizonMs: Long): String {
        val minutes = horizonMs / 60_000L
        return if (minutes % 60L == 0L) "${minutes / 60L}h" else "${minutes}m"
    }

    /**
     * Whether the figures are wrong-signed — insulin does not raise BG and carbohydrate does not
     * lower it, so such a pair is objectively wrong and every panel marks it rather than hiding it.
     * Magnitude is deliberately NOT judged: no band survives in `SensitivityProbe`, and inventing one
     * in a renderer would put back the filter that was removed on purpose.
     */
    fun suspect(estimate: SensitivityEstimate): Boolean =
        estimate.isfMgdlPerU <= 0.0 || estimate.icrGPerU <= 0.0

    /** The ICR and ISF parts, in order, or the single N/A part when there is no estimate. */
    fun sensitivityParts(estimate: SensitivityEstimate?, unit: UnitSpace, compact: Boolean): List<String> =
        if (estimate == null) {
            listOf(SENSITIVITY_NA)
        } else {
            listOf(
                icr(estimate.icrGPerU, compact),
                // The horizon rides the ISF rather than each figure: it qualifies both, and the pair
                // is always shown together. Without it these wear two clinical names whose textbook
                // meaning is the WHOLE action of a dose, while what is measured is the marginal
                // response at the validated window — reliably the smaller number.
                "${isf(estimate.isfMgdlPerU, unit, compact)} @${horizon(estimate.horizonMs)}",
            )
        }

    private const val MGDL_PER_MMOLL = 18.0182
}

/**
 * The roomy on-board line, for the panels that give it a row of its own — Meals and Insulin.
 *
 * The sensitivity pair is shown even when there is nothing to show, as `N/A`: a blank where a figure
 * belongs cannot be told apart from a feature that never shipped, and reporting what the selected
 * model does is the pair's whole remaining purpose.
 *
 * [provenance] is the §3.6-F sub-line naming where IOB came from. Insulin passes it; Meals does not,
 * because a carbohydrate panel restating when insulin was last logged is noise on that screen.
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
        // The separators stay in the base ink and only the figures are marked — the BG panel joins
        // its parts the same way. A tinted middot is harmless on its own, but "the panels render this
        // identically" is the whole point of the file.
        parts.forEach {
            withStyle(SpanStyle(color = ink)) { append(sep) }
            withStyle(SpanStyle(color = if (marked) suspectInk else ink)) { append(it) }
        }
    }
    // HORIZONTALLY SCROLLABLE, like the BG panel's line, and for the same reason: four parts do not
    // fit a phone width. Without this the Text soft-wraps inside the panel's vertical scroll and
    // breaks a figure away from its unit mid-line. `softWrap = false` is what makes the overflow
    // scroll rather than wrap — the scroll container alone still lets Text choose to break.
    // One ScrollState for both lines so the provenance tracks the figures it qualifies.
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
