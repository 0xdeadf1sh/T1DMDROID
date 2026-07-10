package com.t1dm.feature.insulin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 *    it does not, and cannot, actuate insulin.
 */
@Composable
fun BolusCalculatorScreen(
    result: AdviceResult?,
    onAccept: (Candidate) -> Unit = {},
    onRecompute: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bolus advisor", style = MaterialTheme.typography.titleLarge)
        when (result) {
            null -> Text("No recommendation yet — tap Recompute.", style = MaterialTheme.typography.bodyMedium)
            is AdviceResult.Refused -> RefusedCard(result)
            is AdviceResult.Recommended -> RecommendedBody(result, onAccept)
        }
        Button(onClick = onRecompute, modifier = Modifier.padding(top = 4.dp)) { Text("Recompute") }
    }
}

@Composable
private fun RefusedCard(refused: AdviceResult.Refused) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No dose recommended", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            refused.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun RecommendedBody(rec: AdviceResult.Recommended, onAccept: (Candidate) -> Unit) {
    var acknowledged by remember(rec) { mutableStateOf(false) }
    var confirmed by remember(rec) { mutableStateOf(false) }
    val confirmSatisfied = !rec.requiresConfirmation || confirmed

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rec.rescueCarbsG != null) {
                Text("Treat the low first", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("~${rec.rescueCarbsG!!.toInt()} g fast carbs — insulin withheld.", style = MaterialTheme.typography.headlineSmall)
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
            rec.railNotes.forEach { Text("• $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }
        }
    }

    DecisionCardView(rec.card)

    RankedList(rec.ranked)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
        Text("I have read the decision card above.", style = MaterialTheme.typography.bodyMedium)
    }
    if (rec.requiresConfirmation) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                Text("Mandatory confirmation", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            rec.card.confirmationReasons.forEach {
                Text("• $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    Button(
        onClick = { onAccept(rec.best) },
        enabled = acknowledged && confirmSatisfied,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (rec.rescueCarbsG != null) "Accept — treat low" else "Accept ${fmt(rec.best.doseU)} U") }
}

@Composable
private fun DecisionCardView(card: DecisionCard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Decision card", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            fieldRow("Last real reading", card.ageOfLastRealReadingMin?.let { "$it min ago" } ?: "none")
            fieldRow("Interpolated/warm-up", "${(card.interpolatedFraction * 100).toInt()}%" + if (card.warmup) " · WARM-UP" else "")
            fieldRow("Backend", "${card.backend} · ${card.precision}" + agreementSuffix(card.agreementOk))
            fieldRow("Assumed IOB", card.assumedIobU?.let { "${fmt(it)} U (logged only)" } ?: "unknown")
            fieldRow("Last logged dose", card.minSinceLastLoggedDose?.let { "$it min ago" } ?: "never")
            fieldRow("Forecast band width", card.bandWidthMgdl?.let { "±${(it / 2).toInt()} mg/dL" } ?: "—")
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
            val low = c.fan.minLowerBg()?.toInt()
            fieldRow("${fmt(c.doseU)} U", "score ${"%.2f".format(c.score)}" + (low?.let { " · min low $it" } ?: ""))
        }
    }
}

@Composable
private fun fieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun agreementSuffix(ok: Boolean?): String = when (ok) {
    null -> ""
    true -> " · fp16✓"
    false -> " · fp16✗"
}

private fun fmt(u: Double): String = if (u == u.toLong().toDouble()) u.toLong().toString() else "%.1f".format(u)
