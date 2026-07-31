package com.t1dm.feature.insulin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.ConfirmLogDialog
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.PendingLog
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.BasalPreset
import com.t1dm.core.model.BolusPreset
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.IobCobReadout
import kotlin.math.roundToInt

private enum class Tab { BOLUS, BASAL }

// Insulin slider bounds (Phase 7C, item 11): 1–20 U in 1 U steps ⇒ 20 stops ⇒ 18 interior steps.
private const val DOSE_MIN = 1.0
private const val DOSE_MAX = 20.0
private const val DOSE_STEPS = 18

/** Enough for any dose anyone will ever type, and far short of the ~309 digits that reach +Infinity. */
private const val MAX_UNITS_CHARS = 8

/**
 * A dose is loggable only if it is positive and FINITE.
 *
 * `> 0.0` alone is not that test: it rejects NaN by accident but accepts +Infinity, which flows into
 * the action curve as an infinite scale, writes a row carrying Inf and NaN, and then poisons IOB —
 * where it defeats the §3.6-C ceiling outright, since every comparison against NaN is false.
 */
private fun Double?.loggableDose(): Double? = this?.takeIf { it.isFinite() && it > 0.0 }

/**
 * The Phase-4 insulin entry surface (deliverable 1 — "manual bolus/basal entry").
 * Both channels feed the model as a **PK action** rate (model-io-curves.md): a bolus is a gamma
 * peaking ~50 min ([BolusPreset]); a basal is a broad, near-flat Bateman ([BasalPreset]). `:app`
 * writes the self-describing `logged_dose` row (exponential action for rapid / Bateman rates for
 * basal), folds units into the wide `sample` (bolusU / basalU), and
 * enqueues `PUT /v1/series/{bolus,basal}`.
 *
 * Stateless + callback-driven, dependency-light. IOB is surfaced at the top with its §3.6-F
 * provenance ("from logged doses only; last logged N min ago") so a nonzero dose taken after a long
 * logging gap is visibly under-counted — the same fact the calculator's decision card will gate on.
 * A "pick a saved insulin type / draw a custom curve" affordance is a seam for the curve-editor work
 * (deliverable 4), reached through the [footer] slot.
 *
 * Every dose passes a confirm-then-commit dialog before the callback fires. [rapidLabel]/[basalLabel]
 * are the clinical presets `:app` will actually resolve and persist (issue 19) — the writer resolves
 * them from Settings and ignores the [BolusPreset]/[BasalPreset] this screen passes back, so the
 * on-screen quick presets are not what ends up in `logged_dose`. The confirmation restates the
 * resolved label precisely so that gap cannot be confirmed blind; both fall back to the enum label
 * when the caller supplies nothing (previews, tests).
 *
 * N10 — this screen had no scroll container at all, so the BASAL tab (units field + slider + two
 * full-width preset chips + labelled sparkline + advisory + button) simply ran off the bottom, and
 * the on-screen keyboard raised by the units field buried "Log bolus"/"Log basal" with no way to
 * reach them. It now owns EXACTLY ONE vertical scroll, and [footer] renders inside it: see the
 * matching note on `MealsScreen` for why a caller must never place siblings after this screen in a
 * plain Column (they are measured with `maxHeight = 0`).
 */
@Composable
fun InsulinScreen(
    iobCob: IobCobReadout? = null,
    previewBolus: (suspend (units: Double) -> DoubleArray)? = null,
    previewBasal: (suspend (units: Double, preset: BasalPreset) -> DoubleArray)? = null,
    rapidLabel: String? = null,
    basalLabel: String? = null,
    onLogBolus: (units: Double, preset: BolusPreset) -> Unit = { _, _ -> },
    onLogBasal: (units: Double, preset: BasalPreset) -> Unit = { _, _ -> },
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.BOLUS) }
    val scroll = rememberScrollState()
    val haptics = rememberT1dmHaptics()

    Column(Modifier.fillMaxSize().verticalScrollbar(scroll).verticalScroll(scroll).padding(16.dp)) {
        iobCob?.let { IobLine(it) }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Tab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); tab = t },
                    shape = SegmentedButtonDefaults.itemShape(i, Tab.entries.size),
                ) { Text(if (t == Tab.BOLUS) "Bolus" else "Basal") }
            }
        }

        when (tab) {
            Tab.BOLUS -> BolusEntry(previewBolus, rapidLabel, onLogBolus)
            Tab.BASAL -> BasalEntry(previewBasal, basalLabel, onLogBasal)
        }

        footer()
    }
}

@Composable
private fun BolusEntry(
    previewBolus: (suspend (units: Double) -> DoubleArray)?,
    rapidLabel: String?,
    onLogBolus: (Double, BolusPreset) -> Unit,
) {
    var unitsText by remember { mutableStateOf("") }
    val preset = BolusPreset.NOVORAPID
    val units = unitsText.toDoubleOrNull()
    val dose = units.loggableDose()
    var pending by remember { mutableStateOf<PendingLog.Dose?>(null) }
    val haptics = rememberT1dmHaptics()

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        UnitsField(unitsText) { unitsText = it }
        Text(preset.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))

        if (previewBolus != null && dose != null) {
            Text(
                "PK action — units per 5 min",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            val curve by produceState(DoubleArray(0), dose) {
                value = runCatching { previewBolus(dose) }.getOrDefault(DoubleArray(0))
            }
            CurveSparkline(curve, MaterialTheme.colorScheme.primary)
        }

        // Propose only — `unitsText` is cleared on confirm, never on the press, so a Cancel keeps
        // what was typed. The dialog names [rapidLabel] (the Settings-resolved clinical preset the
        // writer will actually persist), not the quick-preset enum shown above it.
        // Propose only: ConfirmLogDialog owns the Warn/Confirm/Reject beat and the commit receipt
        // owns the Commit, so this press is a plain Tap. Anything heavier would announce a dose that
        // has not been written yet.
        Button(
            onClick = {
                haptics.perform(HapticEvent.Tap)
                dose?.let {
                    pending = PendingLog.Dose(it, InsulinKind.BOLUS, rapidLabel ?: preset.label)
                }
            },
            enabled = dose != null,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Log bolus") }
    }

    pending?.let { p ->
        ConfirmLogDialog(
            pending = p,
            onConfirm = { onLogBolus(p.units, preset); unitsText = ""; pending = null },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun BasalEntry(
    previewBasal: (suspend (units: Double, preset: BasalPreset) -> DoubleArray)?,
    basalLabel: String?,
    onLogBasal: (Double, BasalPreset) -> Unit,
) {
    var unitsText by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(BasalPreset.LANTUS) }
    val units = unitsText.toDoubleOrNull()
    val dose = units.loggableDose()
    var pending by remember { mutableStateOf<PendingLog.Dose?>(null) }
    val haptics = rememberT1dmHaptics()

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        UnitsField(unitsText) { unitsText = it }
        // I14 — the basal presets render identically regardless of their (uneven-length) labels: each is
        // a full-width row with a leading selection dot and a single-line label, so "Lantus · glargine ·
        // ~24 h" and "Tresiba · degludec · ~42 h" line up instead of one stretching while the other wraps.
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasalPreset.entries.forEach { p ->
                FilterChip(
                    selected = preset == p,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); preset = p },
                    label = {
                        Text(
                            p.label,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // N9 — the basal PK-action curve, previewed like the bolus but normalized to its OWN peak so
        // its deliberately broad, near-flat plateau is visible (a 24–42 h Bateman spreads a dose so
        // thinly it would otherwise vanish on any shared scale). Labelled so the flatness reads as
        // intended, not as a bug.
        if (previewBasal != null && dose != null) {
            Text(
                "PK action — units per 5 min (broad + near-flat by design)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            val curve by produceState(DoubleArray(0), dose, preset) {
                value = runCatching { previewBasal(dose, preset) }.getOrDefault(DoubleArray(0))
            }
            CurveSparkline(curve, MaterialTheme.colorScheme.tertiary)
        }

        Text(
            "Logs a one-off injection — schedule + basal-rate search in Settings → Basal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = {
                haptics.perform(HapticEvent.Tap)
                dose?.let {
                    pending = PendingLog.Dose(it, InsulinKind.BASAL, basalLabel ?: preset.label)
                }
            },
            enabled = dose != null,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Log basal") }
    }

    pending?.let { p ->
        ConfirmLogDialog(
            pending = p,
            onConfirm = { onLogBasal(p.units, preset); unitsText = ""; pending = null },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun UnitsField(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            // The length cap is load-bearing, not cosmetic: the digit filter admits no 'e' and no
            // sign, but a digit string past ~308 characters still parses to +Infinity.
            onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }.take(MAX_UNITS_CHARS)) },
            label = { Text("Units (U)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Insulin slider (1–20 U, 1 U steps) bound to the SAME dose value: dragging rewrites the
        // text, typing repositions the thumb (rounded/clamped). Empty/out-of-range text parks the
        // thumb at 1 U without clobbering what the user typed.
        val units = value.toDoubleOrNull()
        // Keyed off the drag's own `onValueChange` and quantised to the 1 U stop — NOT off `units`,
        // which the two-way binding also moves when the user types into the field above.
        val doseDetent = rememberHapticDetent()
        Slider(
            value = (units ?: DOSE_MIN).coerceIn(DOSE_MIN, DOSE_MAX).toFloat(),
            onValueChange = { doseDetent.at(it.roundToInt()); onChange(it.roundToInt().toString()) },
            valueRange = DOSE_MIN.toFloat()..DOSE_MAX.toFloat(),
            steps = DOSE_STEPS,
        )
    }
}

@Composable
private fun IobLine(r: IobCobReadout) {
    val gap = r.minsSinceLastLoggedInsulin
    val provenance = when {
        gap == null -> "no insulin logged yet"
        else -> "logged doses only · last logged ${gap} min ago"
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            "IOB ${"%.2f".format(r.iobU)} U   ·   COB ${"%.0f".format(r.cobG)} g",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        Text(
            provenance,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** A tiny filled sparkline of a per-5-min PK curve (preview only). N7 — the curve is 0 at t=0 with the
 *  first sample at t=+5 min: `values[i]` is the appearance/action over `[i·5, (i+1)·5)` min, so it is
 *  anchored at slot `i+1` and slot 0 is the zero baseline (mirrors `CurvePreview` / the dashboard
 *  overlay). Previously `values[0]` was drawn at x=0, making a high-GI curve appear to start mid-rise. */
@Composable
internal fun CurveSparkline(values: DoubleArray, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) {
        if (values.isEmpty()) return@Canvas
        val peak = values.maxOrNull()?.toFloat() ?: return@Canvas
        if (peak <= 0f) return@Canvas
        val n = values.size
        val dx = size.width / n
        val path = Path().apply {
            moveTo(0f, size.height) // t=0, value 0
            for (i in 0 until n) {
                lineTo((i + 1) * dx, size.height - (values[i].toFloat() / peak) * size.height * 0.9f)
            }
            lineTo(n * dx, size.height)
            close()
        }
        drawPath(path, color.copy(alpha = 0.25f))
    }
}
