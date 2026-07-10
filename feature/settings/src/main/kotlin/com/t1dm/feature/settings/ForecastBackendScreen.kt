package com.t1dm.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.BackendAvailability
import com.t1dm.core.model.BackendComparison
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.Precision
import com.t1dm.core.model.displayName

/**
 * Settings → Forecast & models → Compute backend (issue 20 STEP 4). The explicit CPU↔GPU switcher:
 * every routable backend with an evidence-based availability verdict + precision, a LIVE "executing
 * on" line reflecting what is ACTUALLY running (not what was requested), and an on-device GPU-vs-CPU
 * measurement + agreement probe.
 *
 * The switcher governs the FORECAST CYCLE only. Per §3.6-E the dose calculator + predictive alerts run
 * on the authoritative fp32 XNNPACK CPU path, OR on a backend that PASSED the agreement probe; a
 * non-authoritative backend that has not agreed leaves the forecast rendering while DOSING fails closed
 * — stated plainly on this screen. Selecting an unavailable backend refuses in plain language.
 *
 * Pure/stateless: [onSelect]`(null)` = auto (the authority); [onRunComparison] triggers the probe.
 */
@Composable
fun ForecastBackendScreen(
    catalog: List<BackendAvailability>,
    requested: BackendId?,
    executing: BackendId?,
    executingPrecision: Precision?,
    comparison: BackendComparison?,
    onSelect: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
) {
    var refusal by remember { mutableStateOf<String?>(null) }

    SettingsScaffold("Compute backend") {
        SettingsNote(
            "Choose which compute unit runs the glucose forecast. The fp32 XNNPACK CPU path is the " +
                "authority: it is always trusted for dose advice. Any other backend (the Vulkan GPU " +
                "delegate) is used for the forecast only, and can feed the dose calculator or predictive " +
                "alerts ONLY after it has passed the on-device agreement probe below — otherwise the " +
                "forecast still renders but dose advice fails closed, with the reason shown.",
        )

        // Live truth: what is ACTUALLY executing this cycle (may differ from the request on a load fail).
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("Executing on", style = MaterialTheme.typography.labelMedium)
                Text(
                    executing?.displayName() ?: "no model loaded",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (requested != null && requested != executing) {
                    Text(
                        "requested ${requested.displayName()} — fell back (it could not load; see below)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SettingsSectionHeader("Backend")
        // Auto row.
        BackendChoiceRow(
            title = "Auto (fp32 CPU authority)",
            subtitle = "Always the authoritative XNNPACK CPU path — trusted for dose advice.",
            available = true,
            selected = requested == null,
            onClick = { refusal = null; onSelect(null) },
        )
        // U5 — this build ships exactly two real compute paths: the XNNPACK CPU authority and the
        // Vulkan GPU delegate. The Play-delivered NeuroPilot NPU / legacy LiteRT rows are not
        // reachable in a sideload build, so they are not offered here at all.
        val shown = catalog.filter {
            it.backend == BackendId.EXECUTORCH_XNNPACK_FP32 ||
                it.backend == BackendId.EXECUTORCH_VULKAN_FP16 ||
                it.backend == BackendId.EXECUTORCH_VULKAN_FP32
        }
        shown.forEach { b ->
            BackendChoiceRow(
                title = b.backend.displayName(),
                subtitle = buildString {
                    if (b.authoritative) append("authority · ")
                    if (b.available) append("available") else append("unavailable")
                    b.reason?.let { append("\n"); append(it) }
                },
                available = b.available,
                selected = requested == b.backend,
                onClick = {
                    if (b.available) { refusal = null; onSelect(b.backend) }
                    else refusal = "${b.backend.displayName()} is unavailable: ${b.reason ?: "no artifact on device"}"
                },
            )
        }
        refusal?.let {
            DangerBanner(it)
        }

        SettingsSectionHeader("GPU vs CPU (on-device)")
        SettingsNote(
            "Run the same fixed input on the selected non-CPU backend and on the CPU authority, then " +
                "compare speed and numerics. The decoded-mg/dL agreement verdict is the §3.6-E gate: a " +
                "PASS is what allows the backend to feed dose advice.",
        )
        Button(onClick = { refusal = null; onRunComparison() }) { Text("Run agreement probe & measure") }
        comparison?.let { ComparisonCard(it) }
    }
}

@Composable
private fun BackendChoiceRow(
    title: String,
    subtitle: String,
    available: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (selected) "●" else "○",
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComparisonCard(c: BackendComparison) {
    val pass = c.agreementOk
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (pass) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${c.backend.displayName()}  vs  ${c.authority.displayName()}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Mono("warm median   GPU ${"%.2f".format(c.warmMedianMsBackend)} ms   CPU ${"%.2f".format(c.warmMedianMsAuthority)} ms")
            Mono("cold          GPU ${"%.1f".format(c.coldMsBackend)} ms   CPU ${"%.1f".format(c.coldMsAuthority)} ms")
            Mono("max|Δ| head_raw    ${"%.3e".format(c.maxAbsHeadRawDelta)}")
            Mono("max|Δ| mg/dL       ${"%.4f".format(c.maxAbsDecodedMgdlDelta)}  (tol ${"%.1f".format(c.toleranceMgdl)})")
            c.loadRssGrowthKb?.let { Mono("load RSS growth    ${it} KB (unified memory)") }
            Text(
                if (pass) "AGREEMENT: PASS — this backend may feed dose advice."
                else "AGREEMENT: FAIL — forecast only; dose advice stays on the CPU authority.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (pass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}
