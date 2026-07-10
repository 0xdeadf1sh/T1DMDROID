package com.t1dm.feature.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelLatency
import com.t1dm.core.model.RunningModel
import com.t1dm.core.model.TempUnit
import com.t1dm.core.model.displayName

/**
 * The Hardware panel — per-model inference rows (Phase 2 §8 "Hardware panel — per-
 * model rows"): each `model_id`'s backend, precision, and p50/p95 latency, plus the aggregate cycle
 * duration/cause. The fp16 agreement Δ column is stubbed pending the deferred NPU shadow (§3.6-E);
 * the per-model split is already keyed so it drops in without a layout change.
 */
@Composable
fun HardwareScreen(
    state: InferenceState,
    hardware: HardwareInfo = HardwareInfo.UNKNOWN,
    temperatureUnit: TempUnit = TempUnit.CELSIUS,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Detected hardware readout (item 8) ──
        item {
            Text("Device hardware", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HwRow("Device", hardware.device)
                HwRow("SoC", hardware.soc)
                HwRow("CPU", hardware.cpuTopology)
                HwRow("CPU max freq", hardware.cpuMaxFreqsGhz.takeIf { it.isNotEmpty() }
                    ?.joinToString(" / ") { "%.2f".format(it) }?.let { "$it GHz" })
                HwRow("GPU", hardware.gpuRenderer)
                HwRow("NPU / APU", hardware.npu)
                HwRow("RAM", if (hardware.ramTotalMb != null)
                    "${hardware.ramAvailMb ?: "?"} / ${hardware.ramTotalMb} MB free" else null)
                HwRow("Display", hardware.display)
                HwRow("Android", hardware.androidVersion)
                HwRow("Security patch", hardware.securityPatch)
                HwRow("ABI / page", listOfNotNull(
                    hardware.abis.firstOrNull(),
                    hardware.pageSizeKb?.let { "${it}KB pages" },
                ).joinToString(" · ").ifBlank { null })
                HwRow("Thermal", hardware.thermalStatus)
                HwRow("Battery", hardware.battery)
                // U9 — the device temperature (battery sensor) in the user's chosen unit. No fan RPM is
                // shown: it is permission-denied even to adb shell on this device, so never proxied.
                HwRow("Temperature", hardware.batteryTempC?.let { temperatureUnit.format(it) })
            }
            // N5 — the per-backend availability + evidence-based unavailability reasons moved to
            // Settings → Forecast & models → Compute backend, where a user actually chooses a backend.
            // Hardware keeps device + GPU/Vulkan capability + the live "Executing on" line + per-model
            // timing below.
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        // ── GPU / Vulkan compute capability (issue 20 — STEP 5) ──
        item {
            Text("GPU / Vulkan compute", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val vk = hardware.vulkan
            when {
                vk == null ->
                    Text("probing…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                !vk.available && vk.rows.isEmpty() ->
                    Text(vk.note ?: "Vulkan unavailable", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                else -> Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    vk.rows.forEach { (label, value) -> HwRow(label, value) }
                    Text(
                        "This describes the GPU itself; the model runs on Vulkan only when a Vulkan-delegate " +
                            "ExecuTorch backend is selected (fp32 CPU XNNPACK stays authoritative).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        item {
            Text("Inference hardware", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // The LIVE execution truth (issue 1 — no "stub" ambiguity): the selected model's actual
            // backend this cycle, named explicitly. STUB means no working .pte reached the device.
            val selected = state.running.firstOrNull { it.selected }
            // displayName() already carries the precision (e.g. "XNNPACK CPU · fp32"), so don't append
            // the raw enum precision again.
            val executing = selected?.backend?.displayName() ?: "no model selected"
            Text(
                "Executing on: $executing",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            val cadence = state.lastCycleDurationMs?.let { "$it ms" } ?: "—"
            val cause = state.lastCause?.name ?: "—"
            Text(
                "last cycle: $cadence · cause $cause" +
                    (if (!state.realBackendAvailable) " · real forecast path blocked (no .pte)" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            state.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        // N5 — the forecast-backend availability catalog + evidence reasons now live in
        // Settings → Forecast & models → Compute backend (the switcher), not here. A single pointer
        // is kept so a user knows where to change/inspect it.
        item {
            Text(
                "Choose the active compute backend, and see each backend's availability + reasons and " +
                    "the last GPU-vs-CPU agreement measurement, in Settings → Forecast & models → " +
                    "Compute backend.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        if (state.running.isEmpty()) {
            item { Text("No models loaded.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(state.running, key = { it.modelId }) { model ->
                ModelRow(model, state.latencyOf(model.modelId))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ModelRow(model: RunningModel, latency: ModelLatency?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                (if (model.selected) "● " else "○ ") + model.modelId,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (model.selected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                model.backend.displayName(),
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("p50", latency?.let { fmt(it.p50Ms) } ?: "—")
            Stat("p95", latency?.let { fmt(it.p95Ms) } ?: "—")
            Stat("last", latency?.let { fmt(it.lastMs) } ?: "—")
            Stat("runs", latency?.runs?.toString() ?: "0")
            Stat("agree Δ", "—") // fp16 shadow deferred (§3.6-E)
        }
    }
}

@Composable
private fun HwRow(label: String, value: String?) {
    // Shared aligned key/value table (issues 10/11/15) — a long SoC/renderer string wraps at word
    // boundaries instead of stealing the value's column and fracturing a bare number.
    com.t1dm.core.design.KeyValueRow(label, value, numeric = false)
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun fmt(ms: Double): String = if (ms >= 100) "${ms.toInt()} ms" else "%.1f ms".format(ms)
