package com.t1dm.feature.models

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.BASELINE_MODEL_ID
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.BandFitRefusal
import com.t1dm.core.model.BackendAvailability
import com.t1dm.core.model.BackendComparison
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.CgEgaRegion
import com.t1dm.core.model.ErrorGridLattices
import com.t1dm.core.model.ExcursionAccuracy
import com.t1dm.core.model.HorizonMetrics
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelMeta
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.PointBlock
import com.t1dm.core.model.TrendMatrix
import com.t1dm.core.model.TREND_CATEGORIES
import com.t1dm.core.model.ModelTelemetry
import com.t1dm.core.model.displayName
import kotlin.math.abs

/**
 * The per-model PERFORMANCE drill-down (Phase 7C — item 24). Three blocks:
 *
 *  1. META — parameter count, on-disk size, arch dims + geometry (item 7, size reasoning).
 *  2. TELEMETRY — this install's cumulative avg inference EXEC time, #predictions, and TOTAL time
 *     spent in the backend forward (the [ModelTelemetry] the CycleRunner updates + persists).
 *  3. ON-DEVICE REALIZED ACCURACY — the full metric suite of `SPEC/invariants.md` §6.1-6.3,
 *     computed by the golden-gated Rust core over stored `prediction` rows vs the realized
 *     `cgm_reading` trajectory. It reproduces `T1DMAI/realdata/metrics.py::compute_suite`, so these
 *     figures are directly comparable to that project's validation table — every block except
 *     CG-EGA, which is not: that project passes the truth and the forecast to `cg_ega_counts`
 *     transposed, so the %AP/%BE/%EP it publishes assigns the glycaemic region by the forecast and
 *     is a different statistic from the one shown here (see the divergence note above the CG-EGA
 *     block in `t1dm-core::accuracy`). A gap between the two is not evidence about the export.
 *
 *     Two identity rules govern how it may be shown, both from the spec:
 *
 *       - The BASIS is part of every figure (§6.2). The band projection is the headline and the
 *         median line is a separate table beneath it — never one column carrying both. And because
 *         a wider band can only lower the error, no band figure appears without `band_cov50` and
 *         `band_width50` in the same row: a band widened until it swallows every truth scores a
 *         flawless zero, and those two are the only things that expose it.
 *       - CG-EGA is a WHOLE-WINDOW statistic (§6.3), so it carries no horizon label. It is also the
 *         costly pass, and is computed only on a tap.
 *
 *     A horizon with too little matured history says so PLAINLY (never a noisy stat), and an empty
 *     panel states which of the two reasons it is: nothing matured yet, or matured forecasts whose
 *     realized trajectory had a CGM gap.
 *
 *     Each table is paired with a Canvas figure (AccuracyFigures.kt) over the SAME rows — the table
 *     is the exact number, the figure the shape: the error against the persistence baseline it is
 *     scored against, the coverage against the target it claims, and the two zone partitions, which
 *     are read as proportions rather than as four decimals apiece. A figure only ever receives
 *     horizons that PASSED `sufficient`, so none of them draws an axis over a horizon the tables
 *     have just declined to score.
 *
 *     Three figures share ONE horizon — the reader's, defaulting to [CLARKE_GRID_DEFAULT_MIN]: the
 *     Clarke error grid, the DTS error grid, and the Trend Accuracy Matrix. Each is a scatter or a
 *     table of that horizon's pairs; the two grids' regions come from lattices the core classified
 *     ([lattices]) rather than from boundaries restated here, and all three are drawn on the MEDIAN
 *     LINE — the one basis a scatter can carry honestly (see `ErrorGridFigure`). Choosing a horizon
 *     the suite declined to score draws nothing and says so by name; it never quietly plots a
 *     neighbouring one, and the picker stays live underneath so the reader can move.
 *
 *  4. THE TWO 2024 METRICS. The DTS Error Grid and the Trend Accuracy Matrix are Klonoff et al.
 *     2024 (J Diabetes Sci Technol 18(6):1346). Unlike everything above them they have NO
 *     counterpart in `T1DMAI/realdata/metrics.py`, so they are pinned to the paper rather than to
 *     that project and no figure here may be read against its validation table. They are also
 *     device metrics by origin — a monitor against a reference — applied here to a forecast against
 *     the realized trajectory, which is the same repurposing this screen already makes of Clarke
 *     and CG-EGA and carries the same caveat: never quote one against a published CGM accuracy
 *     figure. The DTS grid's `pZA` is reported ALONE, never as an A+B, because its panel declined
 *     to publish a combined zone.
 *
 * Everything is advisory: the accuracy of a FORECAST, never a dosing claim.
 *
 * **The descriptor's reference metrics are not shown, and must not be.** [ModelMeta.reference] is
 * still parsed — the `model_card` block is part of the descriptor — so it remains one field access
 * away, and it does not belong on this screen: it is a held-out validation table from another
 * dataset, and beneath the realized suite it reads as a second opinion on THIS patient's forecasts,
 * which is the one thing it cannot be. The realized numbers are the only accuracy claim made here.
 */
@Composable
fun ModelDetailScreen(
    state: InferenceState,
    modelId: String,
    accuracy: ModelMetrics?,
    accuracyLoading: Boolean,
    onRecomputeAccuracy: () -> Unit,
    /** Null while the lattices are still being classified off-main; empty when the core had none. */
    lattices: ErrorGridLattices?,
    /** The core's own trend rate-bin edges; empty on a stub core, which leaves the axes unlabelled. */
    trendBinEdges: List<Double> = emptyList(),
    cgEga: CgEga?,
    cgEgaLoading: Boolean,
    onComputeCgEga: () -> Unit,
    catalog: List<BackendAvailability>,
    requestedBackend: BackendId?,
    comparison: BackendComparison?,
    onSelectBackend: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
    probeRunning: Boolean = false,
    /** Why the last probe produced no comparison; null when it produced one. The probe refuses on a
     *  backend that is not loaded, and its note is rendered on the Models list, not here — so without
     *  this the button ran and the screen said nothing. */
    probeRefusal: String? = null,
    /** The stored §8.4 band correction for this model, or null when it has never been fitted. */
    bandCalibration: BandCalibration? = null,
    bandCalibrationFitting: Boolean = false,
    /** What the last fit STARTED FROM THIS SCREEN did; null on a fresh open, so a reopen shows the
     *  correction without re-announcing a result the user has already read. */
    bandCalibrationOutcome: BandCalibrationOutcome? = null,
    onFitBandCalibration: () -> Unit = {},
    /** Runs the classical baseline's fit. Non-null only when this drill-down IS the baseline's, so
     *  its own model is the one place its fit lives — a neural model's screen never shows it. */
    onFitBaseline: (() -> Unit)? = null,
    baselineFitting: Boolean = false,
    /** What the last fit started from this screen produced; null on a fresh open, so reopening does
     *  not re-announce a result the user has already read. */
    baselineFitNote: String? = null,
) {
    val isBaseline = modelId == BASELINE_MODEL_ID
    val meta = state.metaOf(modelId)
    val telemetry = state.telemetryOf(modelId)
    val running = state.runningOf(modelId)
    val haptics = rememberT1dmHaptics()

    // The error grid's horizon, hoisted ABOVE the LazyColumn deliberately: `section` is a lazy
    // `item {}`, so a `remember` placed inside the grid's own section is discarded the moment that
    // item scrolls out of the viewport and the choice would snap silently back to the default.
    // Saveable so a rotation keeps it; keyed on the model and stored nowhere, so a fresh open of any
    // drill-down starts at the default — the same lifetime the suite, the CG-EGA walk and the fit
    // outcome already have here. It is a reading posture, not a preference, so it gets no setting.
    var gridHorizonMin by rememberSaveable(modelId) { mutableStateOf(CLARKE_GRID_DEFAULT_MIN) }

    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp).fadingEdges(listState),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(modelId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            running?.let {
                Text(
                    // displayName() already carries the precision; and only a graph model can be the
                    // fp32 dosing authority, so the baseline must not claim to be one.
                    it.backend.displayName() + when {
                        it.selected && isBaseline -> " · SELECTED"
                        it.selected -> " · SELECTED (fp32-authoritative)"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 0. The classical baseline's own fit ──
        //
        // This lives on the model's own screen rather than on the Models list because it belongs to
        // one model, and because the row is listed before the first fit there is always somewhere to
        // put it. The figures below are the fit's OWN held-out evidence; the realized-accuracy
        // sections further down score it the same way they score a neural model.
        if (isBaseline && onFitBaseline != null) {
            section("Baseline") {
                val b = state.baselineModel
                if (b == null) {
                    Note("Not fitted — no forecast")
                } else {
                    KeyVal("fitted", "%tF %<tR".format(b.fittedAtMs))
                    KeyVal("trained on", "${b.nTrainRows} rows")
                    KeyVal(
                        "features",
                        listOfNotNull(
                            "${b.spec.nLags} BG lags",
                            "IOB".takeIf { b.spec.useIob },
                            "COB".takeIf { b.spec.useCob },
                        ).joinToString(" · "),
                    )
                    KeyVal("horizon", "${b.spec.horizonSteps * 5 / 60} h")
                    // The band IS the model here, so an uncalibrated one is not a missing garnish —
                    // it is why nothing is being forecast, and the §3.6-B guard withholds every cycle.
                    if (!b.calibrated) Note("Band uncalibrated — forecasts withheld")
                }
                // The one asymmetry with a graph model, and the user meets it the moment they select
                // this one and open the calculator. Say it here rather than let the refusal there
                // read as a fault.
                Note("No dose advice — the calculator needs a graph model")
                baselineFitNote?.let { Note(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !baselineFitting,
                        onClick = { haptics.perform(HapticEvent.Commit); onFitBaseline() },
                    ) { Text(if (b == null) "Fit" else "Refit") }
                    if (baselineFitting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        // ── 1. Meta ──
        // Skipped for the baseline: every field here is descriptor- or artifact-derived, and it has
        // neither, so the section could only ever say "n/a" six times.
        if (!isBaseline) section("Model") {
            if (meta == null) {
                Note("No descriptor metadata")
            } else {
                KeyVal("parameters", meta.paramCount?.let { "${fmtParams(it)}  (${"%,d".format(it)})" } ?: "n/a")
                KeyVal("artifact size", meta.diskBytes?.let { fmtBytes(it) } ?: "n/a (no .pte on disk)")
                KeyVal("d_model / layers / heads", listOfNotNull(meta.dModel, meta.nLayers, meta.nHeads).joinToStringOrNa())
                KeyVal("patch dim", meta.patchDim?.toString() ?: "n/a")
                KeyVal("context patches", rangeOrNa(meta.minContextPatches, meta.maxContextPatches))
                KeyVal("forecast horizon", meta.predictionHorizonHours?.let { "$it h" } ?: "n/a")
                KeyVal("arch / ExecuTorch", "${meta.archVersion ?: "?"} / ${meta.executorchVersion ?: "?"}")
            }
        }

        // ── 2. Cumulative telemetry ──
        section("Inference telemetry (this install)") {
            if (telemetry == null || telemetry.predictions == 0L) {
                Note("No forecasts recorded yet")
            } else {
                KeyVal("predictions made", "%,d".format(telemetry.predictions))
                KeyVal("avg exec time", fmtMs(telemetry.avgInferenceMs))
                KeyVal("total inference time", fmtDuration(telemetry.totalInferenceMs))
                state.latencyOf(modelId)?.let {
                    KeyVal("recent p50 / p95", "${fmtMs(it.p50Ms)} / ${fmtMs(it.p95Ms)}")
                }
            }
        }

        // ── Compute backend (per-model forecast switcher; issue 20 STEP 4) ──
        // Only meaningful while the model is in the running set (a loaded backend to switch), AND only
        // for the SELECTED model: the switcher's [catalog]/[comparison] are the controller's single
        // selected-model-bound state (backendCatalog / backendComparison), so rendering them for a
        // non-selected running model would show the SELECTED model's availability + run the agreement
        // probe against it — a mismatch. Selecting a model rebuilds that catalog for it (selectModel).
        if (running != null) {
            section("Compute backend") {
                if (running.selected) {
                    ComputeBackendControls(
                        running = running,
                        catalog = catalog,
                        requestedBackend = requestedBackend,
                        comparison = comparison,
                        onSelectBackend = onSelectBackend,
                        onRunComparison = onRunComparison,
                        probeRunning = probeRunning,
                        probeRefusal = probeRefusal,
                    )
                } else {
                    Note(
                        "Applies to the selected model — pick it on Models",
                    )
                }
            }
        }

        // ── 3. On-device realized accuracy ──
        // Keep the prior rows on screen through a recompute (issue 6): only collapse to the one-line
        // "Computing…" when there is NO prior suite; otherwise the tables stay put and a subtle
        // inline hint (in the button row, so section height is unchanged) marks the refresh.
        val suite = accuracy?.suite
        val scored = suite?.horizons.orEmpty().filter { it.sufficient }

        section("Realized accuracy — band τ.25–.75") {
            Note("Forecast vs realized BG (advisory — not a dosing claim)")
            when {
                scored.isNotEmpty() -> {
                    BandTable(scored)
                    // Kept in this section deliberately: §6.2 forbids a band figure standing apart
                    // from its coverage and width, and the table above carries both.
                    ErrorByHorizonFigure(scored)
                }
                accuracyLoading -> Note("Computing…")
                else -> Note(emptyWhy(accuracy))
            }
            suite?.horizons.orEmpty().filterNot { it.sufficient }.forEach {
                Note("${it.horizonMin} min: n=${it.n}, need ${accuracy?.minSamples ?: 0}")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onRecomputeAccuracy() },
                ) { Text("Recompute") }
                if (accuracyLoading && scored.isNotEmpty()) {
                    Note("Recomputing…")
                }
            }
        }

        if (scored.isNotEmpty()) {
            // Both bands' realized coverage against what they claim (§6.2) — the figure that says
            // whether the fan is honest, and the only reading under which a flawless error column
            // can still be a bad forecast.
            section("Calibration") { CalibrationFigure(scored) }
            // The basis is part of a figure's identity (§6.2) and this one is off the band, like
            // every other level metric — so the header carries it, exactly as the band table's does.
            section("Clarke zones — band τ.25–.75") { ClarkeFigure(scored) }
            // The same window on the 2024 DTS grid. `pZA` is its headline and the table prints all
            // five shares individually — never an A+B, which the paper's panel explicitly declines
            // to report and which this screen must therefore not offer either.
            section("DTS zones — band τ.25–.75") {
                DtsFigure(scored)
                DtsTable(scored)
            }
            // Per horizon, like the level metrics and unlike CG-EGA: trend agreement decays with
            // horizon, and pooling the whole window would hide exactly that.
            section("Trend risk categories — median line") {
                Note("1 no risk · 2 under · 3 over · 4/5 extreme")
                TrendCategoryFigure(scored)
                TrendTable(scored)
            }
            // §6.2 — the same block on the median line, kept a table apart from the band figures.
            section("Median line") { MedianTable(scored) }
            section("Outer band τ.05–.95 · persistence") { OuterTable(scored) }
            section("Excursions vs alarm bands") {
                Note("Hypo off the τ.25 edge, hyper off τ.75")
                ExcursionTable(scored)
            }
        }

        // ── 3a. Clarke error grid — one horizon's scatter over the zone regions ──
        //
        // Outside the `scored` gate deliberately, as CG-EGA is: a figure that declines to draw must
        // say why, and a section that simply vanishes says nothing at all.
        //
        // ONE horizon still, but the reader chooses which, opening on [CLARKE_GRID_DEFAULT_MIN] rather
        // than on the longest the suite scored. Every other horizon is a tap away, and each keeps its
        // zone shares in the stacked figure above regardless. The basis is the MEDIAN LINE and the
        // header carries it (§6.2) — see the figure's own note for why the band projection, normative
        // everywhere else, is the one basis this picture may not be drawn on.
        //
        // Two things keep the picker honest. The options are the suite's OWN horizons rather than a
        // list restated here (`ACCURACY_HORIZONS_MIN` belongs to the app container, and a copy of it
        // in this module would go stale in silence), and [clarkeGridPick] returns the chosen
        // [HorizonMetrics] itself rather than an index — so the caption's horizon, its `n` and the
        // pairs it plots are three readings of one object and cannot come apart. A horizon the tables
        // declined is REFUSED by name ahead of every other empty state, never redrawn as its
        // neighbour, and its tab stays live so the reader can move to one that scored.
        val pick = clarkeGridPick(suite?.horizons.orEmpty(), gridHorizonMin)
        val gridRefusal = pick.refusal(accuracy?.minSamples ?: 0)

        // Both error grids and the trend matrix read the SAME horizon, and each section carries the
        // picker so a reader who has scrolled to one can move without going back. One piece of state
        // behind all three: the three are three readings of one horizon's pairs, and letting them
        // drift apart would invite comparing a 30-minute Clarke share against a 120-minute DTS one.
        gridSection(
            "Clarke error grid — median line",
            "Band projection clips to the truth; its grid reads as coverage",
            pick, gridRefusal, accuracy, { gridHorizonMin = it },
        ) { h ->
            when {
                lattices == null -> Note("Computing…")
                lattices.clarke.isEmpty -> Note("Zone regions unavailable")
                h.medianLine.points.isEmpty() -> Note("No scored pairs")
                else -> ErrorGridFigure(h.horizonMin, h.medianLine.points, { it.clarke.ordinal }, lattices.clarke)
            }
        }

        // ── 3a-ii. The DTS error grid (Klonoff 2024) ──
        //
        // The same pairs on the grid that supersedes the Surveillance Error Grid. It sits beside
        // Clarke rather than replacing it because the two disagree in ways worth seeing: the DTS
        // zone A is a shade under Clarke's flat ±20 % and is not symmetric about it, and the whole
        // plane is asymmetric — reading high is penalised harder than reading low by the same ratio,
        // which Clarke does not do at all. The exact edges are `dts_risk`'s, in the crate.
        gridSection(
            "DTS error grid — median line",
            "Klonoff 2024 · reading high scores worse than reading low",
            pick, gridRefusal, accuracy, { gridHorizonMin = it },
        ) { h ->
            when {
                lattices == null -> Note("Computing…")
                lattices.dts.isEmpty -> Note("Zone regions unavailable")
                h.medianLine.points.isEmpty() -> Note("No scored pairs")
                else -> ErrorGridFigure(h.horizonMin, h.medianLine.points, { it.dts.ordinal }, lattices.dts)
            }
        }

        // ── 3a-iii. The Trend Accuracy Matrix ──
        //
        // Median line only, and the section header says so. §6.2's own consequence is why: the band
        // projection equals the truth wherever the band covered, so a rate differenced from it
        // inherits the truth's derivative and the matrix would sit on its diagonal by construction.
        gridSection(
            "Trend accuracy — median line",
            "Rate over 15 min vs realized",
            pick, gridRefusal, accuracy, { gridHorizonMin = it },
        ) { h ->
            if (h.trend.isEmpty) Note("No scored pairs") else TrendMatrixFigure(h.trend, trendBinLabels(trendBinEdges))
        }

        // ── 3b. CG-EGA — whole window (§6.3), computed only on request ──
        section("CG-EGA") {
            when {
                cgEga != null -> {
                    CgEgaFigure(cgEga)
                    CgEgaTable(cgEga)
                }
                cgEgaLoading -> Note("Computing…")
                scored.isEmpty() -> Note("Needs scored windows")
                else -> TextButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onComputeCgEga() },
                ) { Text("Compute") }
            }
        }

        // ── 3c. Band recalibration (§8.4) — the one action here that changes what is DRAWN ──
        //
        // Outside the `scored` gate, like CG-EGA and the error grid: an action that declines to run
        // must say why, and a section that simply vanishes says nothing at all.
        //
        // The rows are the whole statement of what the correction is worth, and they are chosen so
        // that none of them can be read alone. `fit / held out` says how much history each half saw;
        // the coverage pair says what the correction bought on windows it never fitted; and the
        // WIDTH pair beside it is what §6.2 requires of any band figure, because a band widened
        // until it swallows every truth covers perfectly and forecasts nothing. `max shift` is how a
        // reader tells a real correction from one that rounds to the raw fan.
        // Skipped for the baseline: §8.4's correction recalibrates a fan a model already produced,
        // and the baseline's band is not that — it IS its interval, fitted with the weights and
        // shown in the Baseline section above. Offering a second, display-only correction on top
        // would stack two estimators of the same thing.
        if (!isBaseline) section("Band recalibration") {
            Note("Display only — alarms and doses read the raw band")
            if (bandCalibration == null) {
                Note("Not fitted — raw bands")
            } else {
                // A lapsed correction stops being drawn (the apply reads the same predicate), so the
                // rows below become a record of what it once bought rather than a description of the
                // fan on screen. Saying so is the whole point: the figures are unchanged and true,
                // and without this line they read as present tense.
                if (bandCalibration.expiredAt(System.currentTimeMillis())) {
                    Note("Expired after ${bandCalibration.windowDays} d — raw bands")
                }
                KeyVal("fitted", "%tF %<tR".format(bandCalibration.fittedAtMs))
                KeyVal("fit / held out", "${bandCalibration.nCal} / ${bandCalibration.nEval}")
                KeyVal(
                    "cov τ.05–.95",
                    "${f2(bandCalibration.cov90Raw)} → ${f2(bandCalibration.cov90Cal)}",
                )
                KeyVal(
                    "band width",
                    "${f1(bandCalibration.meanWidth90Raw)} → ${f1(bandCalibration.meanWidth90Cal)} mg/dL",
                )
                KeyVal("max shift", "${f1(bandCalibration.maxAbsDeltaMgdl)} mg/dL")
            }
            // The fail-closed outcomes. A refusal names both numbers — the same shape the
            // insufficient-horizon notes above use — so the user learns how far short they are
            // rather than merely that the fit declined.
            //
            // The two BandFitRefusal arms come first and are worded as conditions of the app,
            // because they are: neither reached the window walk, so neither has grounds to say
            // anything about how much of this patient's history matured. Falling through to
            // `emptyWhy` would tell them their data was inadequate on evidence nobody gathered.
            bandCalibrationOutcome?.let { o ->
                val fit = o.fit
                when {
                    o.refusal == BandFitRefusal.BUSY -> Note("Fit already running")
                    o.refusal == BandFitRefusal.HORIZON_UNKNOWN -> Note("No forecast yet — horizon unknown")
                    fit == null -> Note(emptyWhy(accuracy))
                    !fit.sufficient -> Note("${fit.nCal} fit windows, need ${fit.minCalWindows}")
                    else -> Unit // The rows above already changed, and they say it exactly.
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !bandCalibrationFitting,
                    onClick = { haptics.perform(HapticEvent.Tap); onFitBandCalibration() },
                ) { Text("Recalibrate") }
                if (bandCalibrationFitting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ── The realized-accuracy tables ───────────────────────────────────────────────────────────────
//
// Column order follows `T1DMAI/realdata/report.py::_suite_table` so a figure here lines up with the
// same figure there. Every table scrolls sideways (DataTable) rather than crushing its columns.

private fun col(header: String, weight: Float) =
    com.t1dm.core.design.TableColumn(header, weight, numeric = true)

private const val WIDE = 980

/** §6.2 headline. `cov50`/`w50` are in the SAME row as the band errors, deliberately: they are what
 *  keeps a band widened until it swallows every truth from reading as a flawless score. */
@Composable
private fun BandTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.7f),
            col("RMSE pt", 1f), col("RMSE wm", 1f), col("MAE pt", 1f), col("MAE wm", 1f),
            col("MARD %", 1f), col("A %", 0.9f), col("A+B %", 1f), col("E %", 0.9f),
            col("cov50 %", 1f), col("w50", 0.9f), col("skill", 0.9f), col("n", 0.7f),
        ),
        rows = hs.map { h ->
            pointCells(h, h.band) + listOf(
                pct(h.bandCov50), f1(h.bandWidth50), skill(h.band), h.n.toString(),
            )
        },
        minWidth = WIDE,
    )
}

/** The same block on the median line — a different quantity on one forecast (§6.2), so a separate
 *  table rather than extra columns. No coverage: the median is a line and has none. */
@Composable
private fun MedianTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.7f),
            col("RMSE pt", 1f), col("RMSE wm", 1f), col("MAE pt", 1f), col("MAE wm", 1f),
            col("MARD %", 1f), col("A %", 0.9f), col("A+B %", 1f), col("E %", 0.9f),
            col("skill", 0.9f), col("n", 0.7f),
        ),
        rows = hs.map { h ->
            pointCells(h, h.medianLine) + listOf(skill(h.medianLine), h.n.toString())
        },
        minWidth = WIDE,
    )
}

/** The outer envelope and the persistence baseline both bases' skill is measured against. */
@Composable
private fun OuterTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.8f),
            col("cov90 %", 1f), col("w90", 1f), col("persist pt", 1.2f), col("persist wm", 1.2f),
        ),
        rows = hs.map { h ->
            listOf(
                "${h.horizonMin}m", pct(h.bandCov90), f1(h.bandWidth90),
                f1(h.rmsePersistPoint), f1(h.rmsePersistWinmean),
            )
        },
    )
}

/** §6.1 band-edge recall/precision, with the denominators beside them — a recall of 1.00 over one
 *  true crossing is not the same claim as one over forty. */
@Composable
private fun ExcursionTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.7f),
            col("hypo rec", 1.1f), col("hypo prec", 1.2f), col("hypo t/p", 1.1f),
            col("hyper rec", 1.2f), col("hyper prec", 1.3f), col("hyper t/p", 1.2f),
        ),
        rows = hs.map { h ->
            listOf("${h.horizonMin}m") + excursionCells(h.hypo) + excursionCells(h.hyper)
        },
        minWidth = 620,
    )
}

/**
 * The DTS Error Grid's five shares, per horizon (Klonoff 2024).
 *
 * `A` alone is the paper's metric — `pZA` — and there is deliberately no A+B column: the panel that
 * published this grid declined to report one, so offering it here would be this screen inventing a
 * figure the statistic does not have. `|risk|` is the mean absolute risk the zones band, carried
 * beside them because it does not round a near-miss up into a whole zone the way a share does.
 *
 * `cov50` and `w50` sit in the SAME row, exactly as [BandTable]'s do, and for a sharper reason. The
 * band projection is `clip(truth, lo, hi)`, so a pair whose truth fell inside the band has
 * `pred == truth` and scores a DTS risk of `ln(1) = 0` — unconditionally zone A. `pZA` is therefore
 * bounded below by the realized coverage, and a band widened until it swallows every truth prints a
 * flawless `A 100.0 · |risk| 0.000`. These two columns are the only things in the row that move when
 * that happens, which is what §6.2 requires of any band figure.
 */
@Composable
private fun DtsTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.8f),
            col("A %", 1f), col("B %", 1f), col("C %", 1f), col("D %", 1f), col("E %", 1f),
            col("|risk|", 1.1f), col("cov50 %", 1.1f), col("w50", 0.9f), col("n", 0.8f),
        ),
        rows = hs.map { h ->
            val b = h.band
            listOf(
                "${h.horizonMin}m",
                f1(b.dtsA), f1(b.dtsB), f1(b.dtsC), f1(b.dtsD), f1(b.dtsE),
                f3(b.dtsMeanAbsRisk), pct(h.bandCov50), f1(h.bandWidth50), h.n.toString(),
            )
        },
        minWidth = 760,
    )
}

/** The Trend Accuracy Matrix's five risk categories, per horizon. `n` is the matrix's own count,
 *  which is below the horizon's `n` wherever a window's 15-minute lookback did not reach. */
@Composable
private fun TrendTable(hs: List<HorizonMetrics>) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("h", 0.8f),
        ) + List(TREND_CATEGORIES) { col("${it + 1} %", 1f) } + listOf(col("n", 0.8f)),
        rows = hs.map { h -> listOf("${h.horizonMin}m") + trendCells(h.trend) },
        minWidth = 560,
    )
}

private fun trendCells(m: TrendMatrix): List<String> =
    List(TREND_CATEGORIES) { f1(m.categoryPct.getOrNull(it)) } + m.n.toString()

/** §6.3 — the whole window, so no horizon column and no horizon in any label. */
@Composable
private fun CgEgaTable(cg: CgEga) {
    com.t1dm.core.design.DataTable(
        columns = listOf(
            com.t1dm.core.design.TableColumn("region", 1f),
            col("AP %", 1f), col("BE %", 1f), col("EP %", 1f), col("n", 0.8f),
        ),
        rows = listOf(
            cgEgaCells("hypo", cg.hypo),
            cgEgaCells("eu", cg.eu),
            cgEgaCells("hyper", cg.hyper),
        ),
    )
}

private fun pointCells(h: HorizonMetrics, b: PointBlock): List<String> = listOf(
    "${h.horizonMin}m",
    f1(b.rmsePoint), f1(b.rmseWinmean), f1(b.maePoint), f1(b.maeWinmean),
    f1(b.mard), f1(b.clarkeA), f1(b.clarkeAb), "%.2f".format(b.clarkeE),
)

private fun excursionCells(e: ExcursionAccuracy): List<String> =
    listOf(f2(e.recall), f2(e.precision), "${e.nTrue}/${e.nPred}")

private fun cgEgaCells(name: String, r: CgEgaRegion): List<String> =
    listOf(name, f1(r.apPct), f1(r.bePct), f1(r.epPct), r.n.toString())

private fun f1(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.1f".format(v)

private fun f2(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.2f".format(v)

private fun f3(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.3f".format(v)

private fun pct(v: Double): String = if (!v.isFinite()) "—" else "%.1f".format(v * 100)

private fun skill(b: PointBlock): String = f2(b.skillPoint)

// ── The Clarke error grid's horizon choice ─────────────────────────────────────────────────────

/**
 * The horizon the error grid opens on, in minutes.
 *
 * Not the longest the suite scored, which is what this section used to plot. 60 min is the horizon
 * a forecast is read at — long enough for the model's own error to separate from persistence, short
 * enough that the scatter is still about the forecast rather than about the drift of the day — and
 * the other horizons are one tap away rather than one recompute.
 *
 * A default, not a member of the option list: the options are the suite's own horizons, and this
 * resolves against them ([clarkeGridPick]) rather than adding to them.
 */
internal const val CLARKE_GRID_DEFAULT_MIN = 60

/**
 * What the error grid's picker offers, and which of it the current choice resolves to.
 *
 * [options] is EVERY horizon the suite scored, ascending — including one that failed `sufficient`,
 * so a reader who lands on it has somewhere to go. [selected] is the chosen horizon's own metrics
 * record, deliberately the object rather than its index or its number: the figure's caption, its
 * `n` and its scatter are all read off this one value, so a picture labelled 60 min cannot be
 * plotting 120's pairs. Null only where the suite scored no horizon at all.
 */
internal data class ClarkeGridPick(
    val options: List<Int>,
    val selected: HorizonMetrics?,
) {
    /**
     * Why the chosen horizon may not be drawn, or null where it may.
     *
     * The same shape as the insufficient rows the accuracy section prints above, deliberately: "too
     * few windows" is one fact and reads the same wherever this screen states it. A refusal never
     * substitutes another horizon — the picker is what moves the reader, not the figure.
     */
    fun refusal(minSamples: Int): String? =
        selected?.takeUnless { it.sufficient }?.let { "${it.horizonMin} min: n=${it.n}, need $minSamples" }
}

/**
 * Resolve the standing choice [wantedMin] against the horizons the suite actually scored.
 *
 * The choice is held as a NUMBER of minutes rather than an index or a position, so it survives a
 * recompute that adds or drops a horizon. Where the suite does not carry it — a stale choice, or
 * the default against a horizon set that has none — the nearest offered horizon is taken, ties to
 * the shorter. That is not a silent fallback: the picker shows the resolution as its selection, so
 * what is drawn and what is highlighted are the same horizon, and neither is one the reader is
 * still being told they chose.
 */
internal fun clarkeGridPick(horizons: List<HorizonMetrics>, wantedMin: Int): ClarkeGridPick {
    val ordered = horizons.sortedBy { it.horizonMin }
    val chosen = ordered.firstOrNull { it.horizonMin == wantedMin }
        ?: ordered.minByOrNull { abs(it.horizonMin - wantedMin) }
    return ClarkeGridPick(ordered.map { it.horizonMin }, chosen)
}

/**
 * The grid's horizon row — this screen's second single-choice control, and a segmented row rather
 * than [BackendChoiceRow] because three numbers need no subtitle apiece and the section is a figure,
 * not a form. Material3 1.3.1 performs no haptic of its own, so the tick is fired here, as at every
 * other choice site in the app.
 */
@Composable
private fun ClarkeHorizonPicker(options: List<Int>, selected: Int?, onSelect: (Int) -> Unit) {
    val haptics = rememberT1dmHaptics()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        options.forEachIndexed { i, h ->
            SegmentedButton(
                selected = h == selected,
                onClick = { haptics.perform(HapticEvent.SegmentTick); onSelect(h) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text("${h}m") }
        }
    }
}

/** Why the panel is empty — never merely THAT it is. */
private fun emptyWhy(m: ModelMetrics?): String {
    if (m == null) return "Insufficient history — nothing scored yet"
    val built = m.nMatured - m.nIncomplete
    return when {
        m.nMatured == 0 -> "Insufficient history — no matured forecast yet"
        built == 0 -> "CGM gaps — ${m.nIncomplete} of ${m.nMatured} forecasts dropped"
        m.suite.nWindows == 0 -> "Fan not scoreable — $built forecasts rejected"
        else -> "Insufficient history — ${m.suite.nWindows} scored windows"
    }
}

/**
 * The per-model forecast-backend chooser (issue 20 STEP 4, relocated from Settings). Governs the
 * DISPLAY forecast only; per §3.6-E dose advice always runs on the fp32 XNNPACK CPU authority (or a
 * backend that PASSED the agreement probe), so a non-authoritative choice renders the forecast while
 * dosing stays pinned to the CPU authority.
 */
@Composable
private fun ComputeBackendControls(
    running: com.t1dm.core.model.RunningModel,
    catalog: List<BackendAvailability>,
    requestedBackend: BackendId?,
    comparison: BackendComparison?,
    onSelectBackend: (BackendId?) -> Unit,
    onRunComparison: () -> Unit,
    probeRunning: Boolean,
    probeRefusal: String?,
) {
    var refusal by remember { mutableStateOf<String?>(null) }
    val haptics = rememberT1dmHaptics()

    Note(
        "Display forecast only; dose advice stays on CPU unless probed",
    )

    // Live truth: what is ACTUALLY executing (may differ from the request on a load failure).
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Executing on ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            running.backend.displayName(), // already ends in the precision
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }

    // Auto row (auto = the fp32 CPU authority).
    BackendChoiceRow(
        title = "Auto (fp32 CPU authority)",
        subtitle = "Authoritative XNNPACK CPU path — trusted for dose advice",
        available = true,
        selected = requestedBackend == null,
        onClick = { haptics.perform(HapticEvent.SegmentTick); refusal = null; onSelectBackend(null) },
    )
    // This build ships exactly two real compute paths: the XNNPACK CPU authority and the Vulkan GPU
    // delegate. The Play-delivered NeuroPilot NPU / legacy LiteRT rows are not reachable here.
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
            selected = requestedBackend == b.backend,
            // An unavailable backend is a REAL refusal — the row stays enabled deliberately so it can
            // explain itself in [refusal] rather than going dead, which is exactly the case the
            // vocabulary's Reject exists for (a disabled control could say nothing at all).
            onClick = {
                if (b.available) {
                    haptics.perform(HapticEvent.SegmentTick)
                    refusal = null
                    onSelectBackend(b.backend)
                } else {
                    haptics.perform(HapticEvent.Reject)
                    refusal = "${b.backend.displayName()} unavailable — ${b.reason ?: "no artifact on device"}"
                }
            },
        )
    }
    // A row refusal is the fresher of the two — tapping a row clears the probe's, and tapping the
    // probe clears the row's — so whichever is set is the one the last tap produced.
    (refusal ?: probeRefusal)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Note(
        "Backend vs CPU on one input — a PASS unlocks dose advice",
    )
    Button(
        enabled = !probeRunning,
        onClick = { haptics.perform(HapticEvent.Tap); refusal = null; onRunComparison() },
    ) {
        if (probeRunning) {
            // LocalContentColor, not the indicator's default primary: inside a DISABLED filled button
            // the content is drawn at 38 % onSurface, and a primary-coloured spinner reads as live.
            CircularProgressIndicator(
                Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(if (probeRunning) "Probing…" else "Run agreement probe")
    }
    comparison?.let { BackendComparisonCard(it) }
}

@Composable
private fun BackendChoiceRow(
    title: String,
    subtitle: String,
    available: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
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
private fun BackendComparisonCard(c: BackendComparison) {
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
            c.loadRssGrowthKb?.let { Mono("load RSS growth    $it KB (unified memory)") }
            Text(
                if (pass) "AGREEMENT: PASS — may feed dose advice."
                else "AGREEMENT: FAIL — forecast only; dose advice stays on the CPU authority.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (pass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ── small building blocks ──

/**
 * One figure section keyed on the error grids' shared horizon: the note, the picker, and either the
 * figure or the single reason it may not be drawn.
 *
 * A `LazyListScope` extension rather than a composable because [section] is a lazy `item {}` — the
 * body is composed later, inside that item, and a `@Composable` helper called from the list builder
 * would be invoked in the builder's own non-composable scope.
 *
 * The refusal is passed in already resolved so all three sections state it identically: "too few
 * windows" is one fact and reads the same wherever this screen says it. A refusal never substitutes
 * another horizon — the picker is what moves the reader, and it stays live underneath.
 */
private inline fun androidx.compose.foundation.lazy.LazyListScope.gridSection(
    title: String,
    note: String,
    pick: ClarkeGridPick,
    refusal: String?,
    accuracy: ModelMetrics?,
    crossinline onSelect: (Int) -> Unit,
    crossinline body: @Composable (HorizonMetrics) -> Unit,
) {
    section(title) {
        Note(note)
        if (pick.options.size > 1) {
            ClarkeHorizonPicker(
                options = pick.options,
                selected = pick.selected?.horizonMin,
                onSelect = { onSelect(it) },
            )
        }
        val h = pick.selected
        when {
            h == null -> Note(emptyWhy(accuracy))
            refusal != null -> Note(refusal)
            else -> body(h)
        }
    }
}

private inline fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    crossinline body: @Composable () -> Unit,
) {
    item {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { body() }
    }
}

@Composable
private fun KeyVal(k: String, v: String) {
    com.t1dm.core.design.KeyValueRow(k, v, numeric = false)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

private fun List<Int>.joinToStringOrNa(): String = if (isEmpty()) "n/a" else joinToString(" / ")

private fun rangeOrNa(lo: Int?, hi: Int?): String =
    if (lo == null && hi == null) "n/a" else "${lo ?: "?"}–${hi ?: "?"}"

private fun fmtMs(ms: Double): String = if (ms >= 100) "${ms.toInt()} ms" else "%.1f ms".format(ms)

private fun fmtDuration(ms: Double): String = when {
    ms >= 3_600_000 -> "%.2f h".format(ms / 3_600_000)
    ms >= 60_000 -> "%.1f min".format(ms / 60_000)
    ms >= 1_000 -> "%.2f s".format(ms / 1_000)
    else -> "${ms.toInt()} ms"
}
