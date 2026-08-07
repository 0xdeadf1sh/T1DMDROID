package com.t1dm.feature.insulin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.calc.AdviceResult
import com.t1dm.calc.Candidate
import com.t1dm.calc.DecisionCard
import com.t1dm.core.design.ConfirmLogDialog
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.PendingLog
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.InsulinKind
import kotlin.math.roundToInt

/**
 * The ranked-candidate bolus-calculator surface (Phase 4 §5 + §3.6-F). It is a pure,
 * stateless function of the [AdviceResult] the `:app` computed off-thread via `DoseAdvisor`; the UI
 * itself never scores, never gates — it only *presents* the fail-closed verdict and holds the
 * point-of-decision acknowledgement.
 *
 * Two terminal shapes:
 *  - [AdviceResult.Refused] → the plain-language WHY of every global rail that blocked (freshness /
 *    degeneracy / no-model / fp16-disagreement). There is no Accept — a refusal has no dose.
 *  - [AdviceResult.Recommended] → the recommended dose (or carb-rescue), the ranked alternatives, and
 *    the §3.6-F decision card. **Accept is gated behind acknowledging the card**, and behind a second
 *    mandatory confirmation whenever [AdviceResult.Recommended.requiresConfirmation] is set (a long gap
 *    since the last logged dose + a nonzero dose). Accept only ever *records the human's decision* —
 *    it does not, and cannot, actuate insulin. A confirm-then-commit dialog then restates the
 *    `logged_dose` about to be written; it is a third, additive gate and replaces neither checkbox.
 *
 * The calculator scores every candidate toward a SINGLE target BG the user picks on the [TargetBgSlider]
 * (§3.6, advisory only). Its bounds are the calculator's own low/high target thresholds — the same band
 * the in-range objective is measured against — so the chosen target is user-set within [hypo, hyper] and
 * needs no further clamping; [onRecompute] carries that value down into the fail-closed search.
 */
@Composable
fun BolusCalculatorScreen(
    result: AdviceResult?,
    targetLowMgdl: Double,
    targetHighMgdl: Double,
    initialTargetMgdl: Double,
    isComputing: Boolean = false,
    /** The resolved rapid insulin the search ran against and the write will carry — read asynchronously,
     *  so null means "not yet known", and an Accept that would write a dose stays closed until it lands
     *  rather than confirming a row whose insulin it cannot name. */
    insulinLabel: String? = null,
    /** True once the recommendation has outlived the anchor it was computed from; Accept goes dead and
     *  the card is greyed, because every freshness figure on it describes a BG that has since aged. */
    adviceExpired: Boolean = false,
    onAccept: (Candidate) -> Unit = {},
    onRecompute: (targetMgdl: Double) -> Unit = {},
) {
    // The slider's own bounds ARE the clamp (the calculator low/high target). Guard only the widget's
    // invariant that start ≤ end (the thresholds are user-set and unbounded, so a mis-ordered or
    // zero-width pair is possible) — this never narrows the user's target, it only keeps Slider legal.
    val lo = minOf(targetLowMgdl, targetHighMgdl)
    val hi = maxOf(targetLowMgdl, targetHighMgdl).let { if (it > lo) it else lo + 1.0 }
    var targetMgdl by remember(lo, hi, initialTargetMgdl) {
        mutableStateOf(initialTargetMgdl.coerceIn(lo, hi))
    }
    val scroll = rememberScrollState()
    val haptics = rememberT1dmHaptics()

    // N10 — the 16 dp frame sits INSIDE the scroll (as on every other panel) so the scrollbar, which
    // must precede `verticalScroll`, tracks the true viewport and rides flush against its edge rather
    // than floating 16 dp in from it.
    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TargetBgSlider(targetMgdl, lo, hi) { targetMgdl = it }
        when (result) {
            null -> Text("No recommendation yet", style = MaterialTheme.typography.bodyMedium)
            is AdviceResult.Refused -> RefusedCard(result)
            is AdviceResult.Recommended -> RecommendedBody(result, insulinLabel, adviceExpired, onAccept)
        }
        Button(
            onClick = { haptics.perform(HapticEvent.Tap); onRecompute(targetMgdl) },
            enabled = !isComputing,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            if (isComputing) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(8.dp))
                Text("Recomputing…")
            } else {
                Text("Recompute")
            }
        }
    }
}

/**
 * The single target-BG picker (§3.6 — advisory only). One slider spanning the calculator's own
 * [low]…[high] target thresholds; its value is the BG the grid search drives the forecast median toward.
 * The bounds are the clamp — no separate "safe range" limit is imposed on the user's choice.
 */
@Composable
private fun TargetBgSlider(target: Double, low: Double, high: Double, onChange: (Double) -> Unit) {
    // Continuous, so the grain is imposed here: one tick per whole mg/dL — exactly the number the
    // read-out above the slider shows, so the texture and the digits agree.
    val targetDetent = rememberHapticDetent(HapticEvent.ScrubTick)
    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Target BG", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${target.roundToInt()} mg/dL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = target.toFloat(),
                onValueChange = { targetDetent.at(it.roundToInt()); onChange(it.toDouble()) },
                valueRange = low.toFloat()..high.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${low.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.6f))
                Text("${high.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.6f))
            }
            Text(
                "Aims the forecast median here · advisory only",
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun RefusedCard(refused: AdviceResult.Refused) {
    val haptics = rememberT1dmHaptics()
    // §3.6 fail-closed: the calculator declined to recommend anything at all. It lands asynchronously,
    // seconds after Recompute, replacing a card the user may not be looking at — so the refusal is
    // announced. Keyed on the reasons, so a re-render of the same refusal is silent while a NEW one
    // (a different rail tripping) speaks again.
    LaunchedEffect(refused.reasons) { haptics.perform(HapticEvent.Warn) }
    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No dose recommended", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            refused.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun RecommendedBody(
    rec: AdviceResult.Recommended,
    insulinLabel: String?,
    adviceExpired: Boolean,
    onAccept: (Candidate) -> Unit,
) {
    var acknowledged by remember(rec) { mutableStateOf(false) }
    var confirmed by remember(rec) { mutableStateOf(false) }
    val confirmSatisfied = !rec.requiresConfirmation || confirmed
    // The confirm-then-commit dialog is a THIRD gate, strictly additive to the two §3.6-F checkboxes
    // above — those still gate the button's `enabled`; this only gates what the press does. A
    // carb-rescue accept writes no dose at all, so it has nothing to restate and raises no dialog.
    var pendingAccept by remember(rec) { mutableStateOf<Candidate?>(null) }
    val haptics = rememberT1dmHaptics()

    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rec.rescueCarbsG != null) {
                Text("Treat the low first", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("~${rec.rescueCarbsG!!.toInt()} g fast carbs — insulin withheld", style = MaterialTheme.typography.headlineSmall)
            } else {
                Text("Recommended", style = MaterialTheme.typography.titleMedium)
                Text("${fmt(rec.best.doseU)} U", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                rec.best.splits?.let { parts ->
                    Text("split: " + parts.joinToString(" → ") { "${fmt(it.units)}U @ +${it.offsetMin}m" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (rec.railNotes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rec.railNotes.forEach { Text("• $it", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f)) }
        }
    }

    DecisionCardView(rec.card, adviceExpired)

    RankedList(rec.ranked)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = acknowledged,
            onCheckedChange = { on -> haptics.toggled(on); acknowledged = on },
        )
        Text("I have read the decision card.", style = MaterialTheme.typography.bodyMedium)
    }
    if (rec.requiresConfirmation) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = confirmed,
                    onCheckedChange = { on -> haptics.toggled(on); confirmed = on },
                )
                Text("Mandatory confirmation", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            rec.card.confirmationReasons.forEach {
                Text("• $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    // A carb-rescue or a 0 U acceptance writes no `logged_dose` at all, so it names no insulin; every
    // other acceptance does, and that is what makes [insulinLabel] a precondition below.
    val writesDose = rec.rescueCarbsG == null && rec.best.doseU > 0.0
    // The §3.6-F terminal accept — the only press in the app that records a dosing decision. The two
    // branches deliberately differ: a carb-rescue or 0 U acceptance writes NO dose and raises no
    // dialog, so this press is the whole act and carries the Commit itself; a real dose only proposes,
    // and its Commit is owned by the receipt once the `logged_dose` row exists.
    //
    // Held closed until the resolved insulin has landed, for the reason the insulin panel's log button
    // is: the catalogue is read asynchronously, and a press before it arrives would leave the
    // confirmation with no insulin to restate but the dose still about to be written.
    Button(
        onClick = {
            if (!writesDose) {
                haptics.perform(HapticEvent.Commit)
                onAccept(rec.best)
            } else {
                haptics.perform(HapticEvent.Tap)
                pendingAccept = rec.best
            }
        },
        enabled = acknowledged && confirmSatisfied && !adviceExpired && (!writesDose || insulinLabel != null),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                adviceExpired -> "Expired — recompute"
                rec.rescueCarbsG != null -> "Accept — treat low"
                else -> "Accept ${fmt(rec.best.doseU)} U"
            },
        )
    }

    // Non-null by the gate above — the dialog restates the insulin the writer will resolve, never a
    // placeholder standing in for one it has not read yet.
    val label = insulinLabel
    if (label != null) pendingAccept?.let { c ->
        ConfirmLogDialog(
            pending = PendingLog.Dose(c.doseU, InsulinKind.BOLUS, label),
            onConfirm = { onAccept(c); pendingAccept = null },
            onDismiss = { pendingAccept = null },
        )
    }
}

@Composable
private fun DecisionCardView(card: DecisionCard, expired: Boolean = false) {
    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Decision card", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            // Every figure below was measured when the search ran. Once that has lapsed they are a
            // record, not a description, and the card says so rather than reading present-tense.
            if (expired) {
                Text(
                    "As measured at the time of the search",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            fieldRow("Last real reading", card.ageOfLastRealReadingMin?.let { "$it min ago" } ?: "none")
            fieldRow("Interpolated/warm-up", "${(card.interpolatedFraction * 100).toInt()}%" + if (card.warmup) " · WARM-UP" else "")
            fieldRow("Backend", "${card.backend} · ${card.precision}" + agreementSuffix(card.agreementOk))
            fieldRow("Assumed IOB", card.assumedIobU?.let { "${fmt(it)} U (logged only)" } ?: "unknown")
            fieldRow("Last logged dose", card.minSinceLastLoggedDose?.let { "$it min ago" } ?: "never")
            fieldRow("Forecast band width", card.bandWidthMgdl?.let { "±${(it / 2).toInt()} mg/dL" } ?: "—")
            // §3.6-F: the input filter is disclosed only when it is NOT the default, since it shifts
            // the last_bg anchor every other row on this card is qualifying.
            if (card.smoothingWindow != DecisionCard.DEFAULT_SMOOTHING_WINDOW) {
                fieldRow(
                    "BG input filter",
                    if (card.smoothingWindow <= 1) "off (raw)"
                    else "${card.smoothingWindow} samples · ${card.smoothingWindow * 5} min",
                )
            }
        }
    }
}

@Composable
private fun RankedList(ranked: List<Candidate>) {
    val top = ranked.filter { it.score.isFinite() }.take(6)
    if (top.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Alternatives", style = MaterialTheme.typography.titleSmall)
        top.forEach { c ->
            // The MEDIAN minimum over the validated window — the quantity the veto and the objective
            // now read. The band's minimum would explain a block that no longer keys on it.
            val low = c.fan.minMedianBg()?.toInt()
            fieldRow("${fmt(c.doseU)} U", "score ${"%.2f".format(c.score)}" + (low?.let { " · min $it" } ?: ""))
        }
    }
}

/**
 * Used both inside a panel (the decision card) and on the bare page (the alternatives list), so its
 * dimmed label derives from the content colour IN FORCE rather than from
 * `MaterialTheme.colorScheme.onSurface`. Inside a panel the two differ: `panelCardColors` may have had
 * to reject the palette role, and a label computed from the rejected one would sit at the opposite
 * polarity to the value beside it.
 */
@Composable
private fun fieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun agreementSuffix(ok: Boolean?): String = when (ok) {
    null -> ""
    true -> " · fp16✓"
    false -> " · fp16✗"
}

private fun fmt(u: Double): String = if (u == u.toLong().toDouble()) u.toLong().toString() else "%.1f".format(u)
