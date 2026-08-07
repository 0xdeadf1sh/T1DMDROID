package com.t1dm.feature.insulin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
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
import com.t1dm.core.design.IobCobLine
import com.t1dm.core.design.PendingLog
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.core.model.UnitSpace
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
 * Both channels feed the model as a **PK action** rate (model-io-curves.md): a rapid bolus is the
 * Loop/OpenAPS exponential activity curve; a long-acting basal is a broad, near-flat Bateman. `:app`
 * writes the self-describing `logged_dose` row (the chosen preset's peak/DIA for rapid, its DIA +
 * ka/ke for basal), folds units into the wide `sample` (bolusU / basalU), and enqueues
 * `PUT /v1/series/{bolus,basal}`.
 *
 * Stateless + callback-driven, dependency-light. IOB is surfaced at the top with its §3.6-F
 * provenance ("from logged doses only; last logged N min ago") so a nonzero dose taken after a long
 * logging gap is visibly under-counted — the same fact the calculator's decision card will gate on.
 * A "pick a saved insulin type / draw a custom curve" affordance is a seam for the curve-editor work
 * (deliverable 4), reached through the [footer] slot.
 *
 * **The panel picks the insulin, and the pick governs.** [presetCatalog] is the shared clinical
 * catalogue (`insulin_preset_catalog`) partitioned here by family; the label the user selects is what
 * [onLogBolus]/[onLogBasal] hand the writer, and the writer commits that preset's curve. It did not
 * always: the screen once offered a one-variant rapid enum and a two-variant basal enum, and the
 * writer discarded both to resolve a Settings selection instead — so the row named an insulin the
 * panel had never shown. Every dose still passes a confirm-then-commit dialog, and the dialog names
 * the selected preset precisely so a disagreement of that kind cannot be confirmed blind.
 *
 * [initialRapidLabel]/[initialBasalLabel] seed each tab from the insulin LAST LOGGED of that kind.
 * That stickiness follows the dose, never the tap: selecting a chip and walking away changes nothing,
 * and the caller writes the memory only when a row is actually committed.
 *
 * N10 — this screen had no scroll container at all, so the BASAL tab (units field + slider + preset
 * chips + labelled sparkline + advisory + button) simply ran off the bottom, and the on-screen
 * keyboard raised by the units field buried "Log bolus"/"Log basal" with no way to reach them. It now
 * owns EXACTLY ONE vertical scroll, and [footer] renders inside it: see the matching note on
 * `MealsScreen` for why a caller must never place siblings after this screen in a plain Column (they
 * are measured with `maxHeight = 0`). The preset rows scroll HORIZONTALLY inside it — seven labels as
 * long as "Ultra-rapid lispro · Lyumjev" would otherwise take the whole panel to stack.
 */
@Composable
fun InsulinScreen(
    iobCob: IobCobReadout? = null,
    // The model-probed ISF/ICR, shown beside IOB/COB exactly as the BG and Meals panels show it —
    // see `:core:design` OnBoardReadout for why all three read from one definition. Null renders as
    // "N/A" rather than vanishing. DISPLAY-ONLY: the dose calculator on this screen searches the
    // model directly and neither reads nor is influenced by these figures.
    sensitivity: SensitivityEstimate? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    presetCatalog: List<InsulinPresetSpec> = emptyList(),
    initialRapidLabel: String? = null,
    initialBasalLabel: String? = null,
    previewCurve: (suspend (units: Double, preset: InsulinPresetSpec) -> DoubleArray)? = null,
    onLogBolus: (units: Double, presetLabel: String) -> Unit = { _, _ -> },
    onLogBasal: (units: Double, presetLabel: String) -> Unit = { _, _ -> },
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.BOLUS) }
    val scroll = rememberScrollState()
    val haptics = rememberT1dmHaptics()
    val rapids = remember(presetCatalog) { presetCatalog.filter { it.family == InsulinFamily.RapidExp } }
    val basals = remember(presetCatalog) { presetCatalog.filter { it.family == InsulinFamily.BasalBateman } }

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
    ) {
        iobCob?.let { IobCobLine(it, sensitivity, unit, provenance = iobProvenance(it)) }

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
            Tab.BOLUS -> DoseEntry(InsulinKind.BOLUS, rapids, initialRapidLabel, previewCurve, onLogBolus)
            Tab.BASAL -> DoseEntry(InsulinKind.BASAL, basals, initialBasalLabel, previewCurve, onLogBasal)
        }

        footer()
    }
}

/**
 * One tab. The two kinds differ only in which slice of the catalogue they offer, the sparkline's
 * accent, and the basal's signpost to the schedule search — everything else (the dose field, the
 * preset row, the preview, the confirm-then-commit beat) is the same surface, so they share it.
 *
 * The log button is disabled until BOTH a finite positive dose and a preset exist. The second half
 * matters: the catalogue arrives asynchronously, and a press before it lands would otherwise have to
 * invent an insulin to name in the confirmation.
 */
@Composable
private fun DoseEntry(
    kind: InsulinKind,
    presets: List<InsulinPresetSpec>,
    initialLabel: String?,
    previewCurve: (suspend (Double, InsulinPresetSpec) -> DoubleArray)?,
    onLog: (Double, String) -> Unit,
) {
    var unitsText by remember { mutableStateOf("") }
    // Re-seeded when the catalogue or the last-logged label arrives (both are read asynchronously),
    // and never afterwards — a tap moves the selection, and only a committed dose moves the seed.
    var selectedLabel by remember(presets, initialLabel) {
        mutableStateOf(presets.firstOrNull { it.label == initialLabel }?.label ?: presets.firstOrNull()?.label)
    }
    val preset = presets.firstOrNull { it.label == selectedLabel } ?: presets.firstOrNull()
    val dose = unitsText.toDoubleOrNull().loggableDose()
    var pending by remember { mutableStateOf<PendingLog.Dose?>(null) }
    val haptics = rememberT1dmHaptics()
    val presetScroll = rememberScrollState()

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        UnitsField(unitsText) { unitsText = it }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(presetScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { p ->
                FilterChip(
                    selected = p.label == preset?.label,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); selectedLabel = p.label },
                    label = { Text(p.label, maxLines = 1) },
                )
            }
        }
        // The selected preset's own provenance, verbatim from the catalogue — it carries the peak,
        // the DIA and where they come from. This is the only place that survives: it used to sit
        // under the Settings picker, and dropping the picker without it would have left a clinical
        // PK choice on a dose path with nothing behind it. Rendered for the SELECTION alone; seven
        // citations at once would bury the chips.
        preset?.let {
            Text(
                it.citation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // N9 — the basal PK-action curve is previewed like the bolus but normalized to its OWN peak so
        // its deliberately broad, near-flat plateau is visible (a 24–42 h Bateman spreads a dose so
        // thinly it would otherwise vanish on any shared scale). Labelled so the flatness reads as
        // intended, not as a bug.
        if (previewCurve != null && dose != null && preset != null) {
            Text(
                if (kind == InsulinKind.BOLUS) {
                    "PK action — units per 5 min"
                } else {
                    "PK action — units per 5 min (broad + near-flat by design)"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            val curve by produceState(DoubleArray(0), dose, preset) {
                value = runCatching { previewCurve(dose, preset) }.getOrDefault(DoubleArray(0))
            }
            CurveSparkline(
                curve,
                if (kind == InsulinKind.BOLUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
        }

        if (kind == InsulinKind.BASAL) {
            Text(
                "Logs a one-off injection — schedule + basal-rate search in Settings → Basal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Propose only. `unitsText` is cleared on confirm, never on the press, so a Cancel keeps what
        // was typed; and ConfirmLogDialog owns the Warn/Confirm/Reject beat while the commit receipt
        // owns the Commit, so this press is a plain Tap. Anything heavier would announce a dose that
        // has not been written yet.
        Button(
            onClick = {
                haptics.perform(HapticEvent.Tap)
                if (dose != null && preset != null) pending = PendingLog.Dose(dose, kind, preset.label)
            },
            enabled = dose != null && preset != null,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(if (kind == InsulinKind.BOLUS) "Log bolus" else "Log basal") }
    }

    pending?.let { p ->
        ConfirmLogDialog(
            pending = p,
            // The dialog restated `p.typeLabel`, so that is the label the write must carry — not
            // whatever the chip row holds by the time Log is pressed.
            onConfirm = { onLog(p.units, p.typeLabel); unitsText = ""; pending = null },
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

/** §3.6-F: IOB is computed from LOGGED doses only, so a long silence since the last one is the fact
 *  the reader needs beside the number. Insulin is the screen that owns that caveat; Meals does not
 *  restate it. */
private fun iobProvenance(r: IobCobReadout): String =
    r.minsSinceLastLoggedInsulin
        ?.let { "logged doses only · last logged $it min ago" }
        ?: "no insulin logged yet"

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
