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
import androidx.compose.material3.OutlinedButton
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

/** Stateless fail-closed verdict; Accept records decision, never actuates. Refusal: no Accept */
@Composable
fun BolusCalculatorScreen(
    result: AdviceResult?,
    targetLowMgdl: Double,
    targetHighMgdl: Double,
    initialTargetMgdl: Double,
    isComputing: Boolean = false,
    /** Null ⇒ not yet resolved; an Accept that would write a dose stays closed until it lands. */
    insulinLabel: String? = null,
    onAccept: (Candidate) -> Unit = {},
    onRecompute: (targetMgdl: Double) -> Unit = {},
) {
    // Guards only Slider's start ≤ end; the bounds are the clamp, this never narrows them.
    val lo = minOf(targetLowMgdl, targetHighMgdl)
    val hi = maxOf(targetLowMgdl, targetHighMgdl).let { if (it > lo) it else lo + 1.0 }
    var targetMgdl by remember(lo, hi, initialTargetMgdl) {
        mutableStateOf(initialTargetMgdl.coerceIn(lo, hi))
    }
    val scroll = rememberScrollState()
    val haptics = rememberT1dmHaptics()

    // Padding inside the scroll; scrollbar before `verticalScroll`, so it tracks the true viewport.
    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TargetBgSlider(targetMgdl, lo, hi) { targetMgdl = it }
        when (result) {
            null -> Text("No recommendation yet", style = MaterialTheme.typography.bodyMedium)
            is AdviceResult.Refused -> RefusedCard(result)
            is AdviceResult.Recommended -> RecommendedBody(
                result,
                insulinLabel,
                onAccept,
            )
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

/** The [low]…[high] bounds are the clamp; no separate "safe range" is imposed on the choice. */
@Composable
private fun TargetBgSlider(target: Double, low: Double, high: Double, onChange: (Double) -> Unit) {
    // One detent per whole mg/dL, matching the read-out's digits.
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
                "Aims the forecast median here",
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun RefusedCard(refused: AdviceResult.Refused) {
    val haptics = rememberT1dmHaptics()
    // Keyed on reasons: the same refusal re-rendered is silent, a new rail tripping speaks again.
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
    onAccept: (Candidate) -> Unit,
) {
    var acknowledged by remember(rec) { mutableStateOf(false) }
    var confirmed by remember(rec) { mutableStateOf(false) }
    val confirmSatisfied = !rec.requiresConfirmation || confirmed
    // A third gate, additive to the checkboxes: they gate `enabled`, this gates the press action.
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

    DecisionCardView(rec.card)

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
    // Only a dose-writing accept needs [insulinLabel], which is why it is a precondition below.
    val writesDose = rec.rescueCarbsG == null && rec.best.doseU > 0.0
    // Only press recording a dosing decision. Carb-rescue/0 U writes no dose, carries Commit.
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
        enabled = acknowledged && confirmSatisfied && (!writesDose || insulinLabel != null),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                rec.rescueCarbsG != null -> "Accept — treat low"
                else -> "Accept ${fmt(rec.best.doseU)} U"
            },
        )
    }

    // Non-null by the gate above: never a placeholder standing in for an insulin it has not read.
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
private fun DecisionCardView(card: DecisionCard) {
    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Decision card", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            fieldRow("Last real reading", card.ageOfLastRealReadingMin?.let { "$it min ago" } ?: "none")
            fieldRow("Interpolated/warm-up", "${(card.interpolatedFraction * 100).toInt()}%" + if (card.warmup) " · WARM-UP" else "")
            fieldRow("Backend", "${card.backend}")
            fieldRow("Assumed IOB", card.assumedIobU?.let { "${fmt(it)} U (logged only)" } ?: "unknown")
            fieldRow("Last logged dose", card.minSinceLastLoggedDose?.let { "$it min ago" } ?: "never")
            fieldRow("Forecast band width", card.bandWidthMgdl?.let { "±${(it / 2).toInt()} mg/dL" } ?: "—")
            // Disclosed only off default: it shifts the last_bg anchor the other rows qualify.
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
            // Median minimum, the quantity the veto reads; the band's minimum is a different block.
            val low = c.fan.minMedianBg()?.toInt()
            fieldRow("${fmt(c.doseU)} U", "score ${"%.2f".format(c.score)}" + (low?.let { " · min $it" } ?: ""))
        }
    }
}

/** Dimmed label derives from content colour IN FORCE, not `onSurface` — polarity may differ. */
@Composable
private fun fieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmt(u: Double): String = if (u == u.toLong().toDouble()) u.toLong().toString() else "%.1f".format(u)
