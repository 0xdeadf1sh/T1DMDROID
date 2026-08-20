package com.t1dm.app.lab

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.targetBits
import com.t1dm.core.model.LoraGuardVerdict
import com.t1dm.inference.loraAttachRefusal
import com.t1dm.core.model.MaskGeometry
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.data.PromoteResult
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.BgInfillEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList
import com.t1dm.data.db.LoraEntity
import com.t1dm.feature.models.LabAdapter
import com.t1dm.core.model.SpanLinePreview
import com.t1dm.feature.models.LabSynth
import com.t1dm.feature.models.LabUiState
import com.t1dm.feature.models.LoraFitSpec
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.inference.InferenceController
import com.t1dm.inference.ModelChannels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Drives the Lab, the adapter panel and the BG panel's reconstruction.
 *
 * The three share one holder because they share one awkward resource: a window of history, its four
 * channels, and a loaded model to push them through. Nothing here writes a reading, a prediction, a
 * statistic or an outbox row — the single exception is [runSpan], which writes reconstructed
 * samples into their own table and nowhere else.
 */
class LabController(
    private val native: NativeCore,
    private val controller: InferenceController,
    private val repository: T1dmRepository,
    private val history: BgHistoryProvider,
    private val channels: suspend (Long, Int) -> ModelChannels,
    private val adaptersDir: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    // ── surface state ───────────────────────────────────────────────────────────────

    suspend fun refresh(models: List<String>) {
        val picked = _state.value.modelId?.takeIf { it in models } ?: models.firstOrNull()
        val desc = picked?.let { controller.descriptorOf(it) }
        val realPatches = realContextPatches(desc)
        _state.update {
            it.copy(
                models = models,
                modelId = picked,
                contextPatches = desc?.minContextPatches ?: 0,
                realPatches = realPatches,
                adapters = picked?.let { id -> adaptersFor(id) } ?: emptyList(),
            )
        }
    }

    fun pickModel(id: String) = _state.update { it.copy(modelId = id, generated = null) }
    fun setSeed(seed: Long) = _state.update { it.copy(seed = seed, generated = null) }

    private suspend fun realContextPatches(desc: ModelDescriptor?): Int {
        if (desc == null) return 0
        val steps = desc.maxContextPatches * desc.patchSize
        val s = runCatching { history.recentBgSeries(steps, desc.patchSize) }.getOrNull() ?: return 0
        return s.mgdl.size / desc.patchSize
    }

    /** One model's adapters, for a surface that names its own model rather than the picked one. */
    suspend fun adaptersOf(modelId: String): List<LabAdapter> = adaptersFor(modelId)

    private suspend fun adaptersFor(modelId: String): List<LabAdapter> =
        runCatching { repository.lorasFor(modelId).map { it.toUi() } }.getOrElse { emptyList() }

    // ── generation ──────────────────────────────────────────────────────────────────

    /**
     * Generate a synthetic patient over the selected model's context window.
     *
     * Synthetic mode fills only the MISSING steps — every real sample survives. That is what makes
     * it usable at all: a seven-day context on a phone that has two days of readings is two real
     * days and five invented ones, and the trace says which is which.
     *
     * The model is picked for its window LENGTH and nothing else. No inference runs here.
     */
    suspend fun generate() {
        val st = _state.value
        val modelId = st.modelId ?: return
        val desc = controller.descriptorOf(modelId) ?: return
        _state.update { it.copy(generating = true, error = null) }
        try {
            val steps = desc.minContextPatches * desc.patchSize
            val synth = windowFor(desc, steps, st.seed)
                ?: run {
                    _state.update { it.copy(generating = false, error = "No window to generate over") }
                    return
                }
            _state.update { it.copy(generating = false, generated = synth) }
        } catch (t: Throwable) {
            Timber.w(t, "lab generate failed")
            _state.update { it.copy(generating = false, error = t.message ?: "Generate failed") }
        }
    }

    /** The synthetic window: this phone's own measurements where it has them, invented elsewhere. */
    private suspend fun windowFor(desc: ModelDescriptor, steps: Int, seed: Long): LabSynth? {
        val real = runCatching { history.recentBgSeries(steps, desc.patchSize) }.getOrNull()
        // The grid the synthetic window sits on: the real one where there is one, else ending now.
        val anchorMs = real?.let { it.gridStartMs + (it.mgdl.size - 1).toLong() * STEP_MS }
            ?: (clock() / STEP_MS * STEP_MS)
        val startMs = anchorMs - (steps - 1).toLong() * STEP_MS
        val gen = native.synthSeries(steps, localHourAt(startMs), native.synthDefaultParams(), seed)
        val realBg = DoubleArray(steps) { Double.NaN }
        val realCh = runCatching { channels(startMs, steps) }.getOrElse { ModelChannels.zero(steps) }
        // The MEASURED series, not the carried-forward one: a dropout inside the history is a step
        // with no reading, and synthetic mode exists to fill exactly those. Reading the dense
        // series here would hand back a flat carry-forward and call it real.
        val measured = runCatching { history.fitBgSeries(steps, desc.patchSize) }.getOrNull() ?: real
        if (measured != null) {
            // Placed on the synthetic grid by timestamp, not by index: the two windows share an
            // end but need not share a length.
            val offset = ((measured.gridStartMs - startMs) / STEP_MS).toInt()
            measured.mgdl.forEachIndexed { i, v ->
                val j = offset + i
                if (j in 0 until steps && !v.isNaN()) realBg[j] = v
            }
        }
        val filled = native.synthFillGaps(
            realBg.toList(),
            realCh.carb.toList(),
            realCh.insulin.toList(),
            realCh.exercise.toList(),
            gen,
        )
        return LabSynth(
            seed = seed,
            gridStartMs = startMs,
            stepMs = STEP_MS,
            bg = filled.bg,
            // Read off the MEASURED series, so a step the generator filled is marked as invented
            // even where the dense series would have carried a value forward into it.
            real = List(steps) { !realBg[it].isNaN() },
            carb = filled.carb,
            insulin = filled.insulin,
            exercise = filled.exercise,
        )
    }

    private fun localHourAt(ms: Long): Double {
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.timeInMillis = ms
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
    }

    // ── adapters ────────────────────────────────────────────────────────────────────

    /**
     * Fit an adapter and store it DETACHED — attaching is a separate, deliberate act.
     *
     * [onReplay] and [onEpoch] report the two phases as they run; both are optional and both are
     * called on this coroutine's own thread.
     */
    suspend fun fit(
        modelId: String,
        spec: LoraFitSpec,
        onReplay: ((done: Int, total: Int) -> Unit)? = null,
        onEpoch: ((epoch: Int, epochs: Int) -> Unit)? = null,
    ): String {
        val desc = controller.descriptorOf(modelId) ?: return "Model not loaded"
        val head = desc.head ?: return "This model ships no head file — no adapter can attach"
        // One hour, and deliberately not wider. A wider stride buys independence by losing
        // windows faster than it gains it, and past an hour it takes a fresh sensor under the
        // 16-sample floor two lines down — measured on this patient's record, on both sensors.
        val stride = desc.patchSize * 2
        val samples = controller.loraSamples(modelId, spec.windows, stride, onReplay)
        if (samples.size < 16) return "Only ${samples.size} usable windows — need more history"
        val config = LoraConfig(
            rank = spec.rank,
            alpha = spec.rank * 2.0,
            targetHidden = spec.targetHidden,
            targetL0 = spec.targetL0,
            targetL1 = spec.targetL1,
            targetL2 = spec.targetL2,
        )
        val opts = LoraTrainOpts(
            epochs = spec.epochs,
            lr = 3e-3,
            holdoutFrac = 0.25,
            weightDecay = 1e-4,
            seed = clock(),
        )
        val result = controller.trainLora(modelId, samples, config, opts) { epoch, epochs, _, _ ->
            onEpoch?.invoke(epoch, epochs)
        }
        check(result.weights.headSha256 == head.sha256) {
            "the fit produced an adapter for another head"
        }
        val now = clock()
        repository.saveLora(
            LoraEntity(
                modelId = modelId,
                name = spec.name,
                blob = native.loraSerialize(result.weights),
                rank = config.rank,
                alpha = config.alpha,
                targets = config.targetBits(),
                nParams = result.weights.params.size,
                nTrain = result.report.nTrain,
                nHoldout = result.report.nHoldout,
                epochs = result.report.epochsRun,
                holdoutBefore = result.report.holdoutLossBefore,
                holdoutAfter = result.report.holdoutLossAfter,
                improved = result.report.improved,
                attached = false,
                createdAtMs = now,
                updatedAtMs = now,
                // Every input to the verdict, not just the verdict: a refusal must be readable
                // rather than trusted, and a ratio alone hides which side of it moved.
                guardVerdict = (result.report.guard?.verdict ?: LoraGuardVerdict.ABSENT).name,
                guardWindows = result.report.guard?.nWindows ?: 0,
                guardFrozenMgdl = result.report.guard?.frozenResponseMgdl ?: 0.0,
                guardAdaptedMgdl = result.report.guard?.adaptedResponseMgdl ?: 0.0,
                guardRetention = result.report.guard?.retention ?: 0.0,
                guardSignAgreement = result.report.guard?.signAgreement ?: 0.0,
                guardWhy = result.report.guard?.why.orEmpty(),
                nPaired = result.report.nPaired,
                distillScale = result.report.distillScale,
                fittedAtMs = now,
            ),
        )
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
        val r = result.report
        // The window count comes FIRST: a held-out loss read without knowing how many windows —
        // and how many INDEPENDENT ones — produced it is not a number anyone can act on. At the
        // one-hour stride a 3.5-day context makes consecutive windows ~99 % shared, so the count
        // of non-overlapping windows is the honest denominator and it is usually very small.
        val windowSteps = desc.minContextPatches * desc.patchSize +
            (desc.predictionHorizonHours * 12 / desc.patchSize) * desc.patchSize
        val independent = (windowSteps + (samples.size - 1) * stride) / windowSteps
        return "${r.nTrain}+${r.nHoldout} windows (~$independent independent) · " +
            "held out ${"%.4f".format(r.holdoutLossBefore)} → ${"%.4f".format(r.holdoutLossAfter)}" +
            if (r.improved) " @ epoch ${r.bestEpoch}" else " · no gain"
    }

    /**
     * Attach an adapter, and drop what described the model before it.
     *
     * The band correction and the realised-accuracy history were both fitted against the frozen
     * forecaster. An adapted model is a different one, and keeping either would report the old
     * model's calibration on the new one's fan.
     */
    suspend fun attach(modelId: String, id: Long): String? {
        val row = repository.loraById(id) ?: return "Adapter is gone"
        // The blob, before the gate. An adapter the crate will not load, or one fitted on another
        // model's head, attaches cleanly and then runs as NOTHING — the forecast path deserialises
        // per cycle and falls back to null on failure — while the panel goes on showing it
        // attached. Silent identity is the one outcome worse than a refusal.
        val head = controller.descriptorOf(modelId)?.head
            ?: return "This model ships no head file — nothing to attach an adapter to"
        val w = native.loraDeserialize(row.blob) ?: return "This adapter will not load"
        if (w.headSha256 != head.sha256) return "This adapter belongs to another head"
        // And the stored site set against the weights' own. They are written together by the fit,
        // so a disagreement means the row was edited or restored from a file that lost them, and
        // every surface naming the sites would be describing weights that do not have them.
        if (w.config.targetBits() != row.targets) return "Stored sites do not match the weights"
        // STRUCTURAL, not a disabled button. A gate that lives only in a composable is one
        // deeplink or one refactor away from being bypassed, and what it guards is whether the
        // forecaster a dose is read off still responds to insulin.
        val refusal = loraAttachRefusal(
            verdict = runCatching { LoraGuardVerdict.valueOf(row.guardVerdict) }
                .getOrDefault(LoraGuardVerdict.ABSENT),
            overrideAtMs = row.guardOverrideAtMs,
            historyMutatedAtMs = row.historyMutatedAtMs,
            fittedAtMs = row.fittedAtMs,
            why = row.guardWhy,
        )
        if (refusal != null) return refusal
        repository.attachLora(id, modelId, clock())
        repository.clearForecastDerived(modelId)
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
        return null
    }

    /**
     * Measure a stored adapter against the model's dose response, and record the verdict.
     *
     * The only writer of a verdict other than the fit. Without it an imported adapter, an
     * archive-restored one, and any fit that had too few paired holdout windows all sit at `ABSENT`
     * for ever, and the ONLY way past is the override — which turns a deliberate escape hatch into
     * the ordinary route.
     */
    suspend fun probe(
        modelId: String,
        id: Long,
        windows: Int = 200,
        onReplay: ((done: Int, total: Int) -> Unit)? = null,
    ): String {
        val row = repository.loraById(id) ?: return "Adapter is gone"
        val desc = controller.descriptorOf(modelId) ?: return "Model not loaded"
        val head = desc.head ?: return "This model ships no head file — no adapter can attach"
        val w = native.loraDeserialize(row.blob) ?: return "This adapter will not load"
        // An adapter belongs to ONE head, and a verdict measured against another model's head would
        // describe an adapter that can never run here.
        if (w.headSha256 != head.sha256) return "This adapter belongs to another head"
        val samples = controller.loraSamples(modelId, windows, desc.patchSize * 2, onReplay)
        val report = controller.guardAdapter(modelId, w, samples)
        repository.setLoraGuard(
            id = id,
            verdict = report.verdict.name,
            windows = report.nWindows,
            frozenMgdl = report.frozenResponseMgdl,
            adaptedMgdl = report.adaptedResponseMgdl,
            retention = report.retention,
            signAgreement = report.signAgreement,
            why = report.why,
            nowMs = clock(),
        )
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
        return report.why.ifBlank {
            "${report.verdict.name.lowercase()} over ${report.nWindows} windows"
        }
    }

    /**
     * Record that the user deliberately overrode a refusal for THIS adapter.
     *
     * A second, separate action rather than a confirm on the attach button: what is being
     * overridden is a measurement saying the model no longer responds to insulin the way it did,
     * and that deserves an act of its own. The stamp sticks to the row, so a re-fit — which makes
     * a fresh row — starts again with no override.
     *
     * [typedName] must be the adapter's own name, and it is compared HERE rather than in the
     * dialog that collects it. A confirmation enforced only by a composable is one deeplink or one
     * refactor away from being a bare button press, and what it stands in front of is a
     * measurement saying the forecaster a dose is read off no longer responds to insulin.
     */
    suspend fun overrideGuard(modelId: String, id: Long, typedName: String): String? {
        val row = repository.loraById(id) ?: return "Adapter is gone"
        if (typedName.trim() != row.name) return "Type the adapter's name exactly to override"
        repository.setLoraGuardOverride(id, clock())
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
        return null
    }

    suspend fun detach(modelId: String) {
        repository.detachLoras(modelId, clock())
        repository.clearForecastDerived(modelId)
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
    }

    suspend fun rename(modelId: String, id: Long, name: String) {
        repository.renameLora(id, name, clock())
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
    }

    suspend fun delete(modelId: String, id: Long) {
        val row = repository.loraById(id)
        repository.deleteLora(id)
        if (row?.attached == true) repository.clearForecastDerived(modelId)
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
    }

    /** Write one adapter beside the models, where `adb pull` and the archive can both reach it. */
    suspend fun export(id: Long): String {
        val row = repository.loraById(id) ?: return "Adapter is gone"
        val dir = adaptersDir().apply { mkdirs() }
        // Both halves are sanitised: a model id comes from a descriptor, which is a file the app
        // did not write, and `../` in one would put the export wherever it liked.
        fun safe(v: String) = v.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "adapter" }
        val file = File(dir, "${safe(row.modelId)}-${safe(row.name)}$EXT")
        return runCatching {
            file.writeBytes(row.blob)
            file.absolutePath
        }.getOrElse { "Export failed: ${it.message}" }
    }

    /** Take in every adapter file beside the models that this phone does not already hold. */
    suspend fun import(modelId: String): String {
        val head = controller.descriptorOf(modelId)?.head
            ?: return "This model ships no head file — nothing to attach an adapter to"
        val files = adaptersDir().listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty()
        if (files.isEmpty()) return "No $EXT files in ${adaptersDir().absolutePath}"
        val have = repository.lorasFor(modelId).map { it.blob.toList() }.toSet()
        var added = 0
        var refused = 0
        for (f in files) {
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            // The crate checks the digest and the geometry; a blob it will not load is refused here
            // rather than stored to fail silently at the next forecast.
            val w = native.loraDeserialize(bytes)
            if (w == null) { refused++; continue }
            // An adapter belongs to ONE head. Same-shaped weights from another model would load
            // and decode plausibly wrong, so the digest is checked here as well as at attach.
            if (w.headSha256 != head.sha256) { refused++; continue }
            if (bytes.toList() in have) continue
            val now = clock()
            repository.saveLora(
                LoraEntity(
                    modelId = modelId,
                    name = f.name.removeSuffix(EXT).substringAfter("$modelId-", f.name.removeSuffix(EXT)),
                    blob = bytes,
                    rank = w.config.rank,
                    alpha = w.config.alpha,
                    // From the blob that was just deserialised, not assumed: an imported adapter
                    // touches the sites it was fitted with, and recording all four made the panel
                    // describe sites the weights do not have.
                    targets = w.config.targetBits(),
                    nParams = w.params.size,
                    nTrain = 0,
                    nHoldout = 0,
                    epochs = 0,
                    holdoutBefore = Double.NaN,
                    holdoutAfter = Double.NaN,
                    improved = false,
                    attached = false,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            )
            added++
        }
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
        return "Imported $added" + if (refused > 0) ", refused $refused" else ""
    }

    // ── reconstruction ──────────────────────────────────────────────────────────────

    /** A stretch of the curve to reconstruct, in absolute time. */
    data class Gap(val startMs: Long, val endMs: Long, val steps: Int)

    /**
     * Reconstruct one span with [modelId] and store the fill. The BG panel's edit bar is the only
     * caller: reconstruction lives where the curve being changed is drawn, and the second surface
     * that could also write a fill has been removed rather than kept in step.
     *
     * [geometry] is DERIVED by the caller from where the span sits (`SPEC/inference.md` §4 makes
     * backcast, infill and forecast one objective under different inputs) and is passed only so the
     * refusals below can differ. Nothing here chooses it.
     */
    suspend fun runSpan(modelId: String, startMs: Long, endMs: Long, geometry: MaskGeometry): String {
        val gap = Gap(startMs = startMs, endMs = endMs, steps = ((endMs - startMs) / STEP_MS).toInt())
        val desc = controller.descriptorOf(modelId) ?: return "Model not loaded"
        // The ATTACHED adapter, the same one the live cycle runs. A reconstruction drawn beside a
        // forecast that came from a different forecaster is two models on one panel with nothing
        // saying which drew what — and a promoted one would put the frozen model's output into the
        // record of a patient whose model is adapted.
        //
        // A refusal rather than a fall-back to frozen, for the reason `adaptedHeadRaw` gives: an
        // adapter that is attached but cannot be applied means the forecaster the user is looking
        // at is not the one that would answer, and quietly answering with the other one is the
        // substitution this app does not make.
        val attached = repository.attachedLora(modelId)
        val lora = if (attached == null) {
            null
        } else {
            // `loraDeserialize` answers a blob it rejects with NULL, not an exception — a digest or
            // geometry mismatch comes back as an absent adapter rather than a thrown one. Letting
            // that null flow into `runMasked` would reconstruct with the FROZEN model while an
            // adapter is attached, which is the silent substitution `adaptedHeadRaw` refuses to
            // make: the fill would come from a forecaster the panel is not showing, and a promoted
            // one would put it in the record.
            runCatching { native.loraDeserialize(attached.blob) }.getOrNull()
                ?: return "Attached adapter could not be read — detach it first"
        }
        val s = desc.patchSize
        val nCtx = desc.minContextPatches
        val steps = nCtx * s
        val dense = history.recentBgSeries(steps * 3, steps) ?: return "Not enough history"
        val gapStart = ((gap.startMs - dense.gridStartMs) / STEP_MS).toInt()
        val gapEnd = ((gap.endMs - dense.gridStartMs) / STEP_MS).toInt()
        if (gapStart < 0 || gapEnd > dense.mgdl.size) return "Gap is outside the window"
        // Aim for a third of the window after the gap, and take what the history actually has.
        val trailing = min(dense.mgdl.size - gapEnd, steps / 3)
        // The window ORIGIN has to land on an ABSOLUTE patch boundary, not merely on a multiple of
        // the patch size counted from `gridStartMs` — which is a reading timestamp and need not be
        // one. The panel snaps its selection to absolute boundaries, so a window origin that is not
        // one maps the selection onto fractional patch indices: the span then straddles a boundary
        // and comes back ONE PATCH LONGER than the drag, which is how a selection clamped to the
        // model's own envelope still met "the model was trained to N patches".
        //
        // Aligned LAST, and the fit re-checked afterwards. Clamping into the window after aligning
        // — which is what this did — undoes the alignment precisely when the window cannot slide,
        // and that is the ordinary case for a fill near the newest reading.
        val from = alignedWindowOrigin(
            preferred = gapEnd + trailing - steps,
            gridStartMs = dense.gridStartMs,
            size = dense.mgdl.size,
            steps = steps,
            patchSize = s,
        ) ?: return "Not enough history"
        val to = from + steps
        if (gapStart < from || gapEnd > to) return "Gap does not fit one window"
        // The alignment is what makes every patch index below exact. A residue here would mask a
        // different stretch than the one the finger drew, so it is refused rather than rounded.
        if ((gapStart - from) % s != 0 || (gapEnd - from) % s != 0) {
            return "Span does not land on a patch boundary"
        }

        // A forecast is the ordinary shape and not a masked span at all: the future zone is masked
        // by construction and its length is the descriptor's horizon, not the drag. Nothing from
        // one is written to `bg_infill`.
        if (geometry == MaskGeometry.FORECAST) {
            val series = BgSeries(
                dense.mgdl.copyOfRange(from, to),
                anchorTsMs = dense.gridStartMs + (to - 1).toLong() * STEP_MS,
                gridStartMs = dense.gridStartMs + from.toLong() * STEP_MS,
            )
            val ch = runCatching { channels(series.gridStartMs, steps) }
                .getOrElse { ModelChannels.zero(steps) }
            val out = controller.runMasked(
                modelId = modelId,
                series = series,
                channels = ch,
                future = null,
                spans = emptyList(),
                withForecast = true,
                lora = lora,
                synthetic = false,
            )
            return if (out.status.name != "OK") {
                "Refused: " + out.status.name.lowercase().replace('_', ' ')
            } else {
                "Forecast drawn"
            }
        }

        val firstPatch = (gapStart - from) / s
        val lastPatch = (gapEnd - 1 - from) / s
        // A span abutting the LEFT edge is a BACKCAST: `SPEC/inference.md` §7.4 anchors it on its
        // right neighbour alone, and `resolve_mask_spans` already accepts it. It may be drawn.
        // Promotion refuses it separately, because storing one extends the patient's history
        // backwards on a single anchor.
        if (firstPatch < 0 || (firstPatch == 0 && geometry != MaskGeometry.BACKCAST)) {
            return "Gap sits at the window edge"
        }
        if (lastPatch >= nCtx - 1) return "Gap sits at the window edge"
        val span = MaskSpan(firstPatch, lastPatch - firstPatch + 1)
        if (span.length > desc.maxMaskedPatches) return "Gap is longer than the head's ${desc.maxMaskedPatches} slots"
        // Beyond the longest span the training sampler ever drew, the model is being asked for
        // something it has never seen. It would answer — plausibly, and with a fan that says
        // nothing about being off-distribution — so the repair declines instead of storing it.
        if (span.length > desc.maskSpanMax) {
            return "Gap spans ${span.length} patches; the model was trained to ${desc.maskSpanMax}"
        }

        val series = BgSeries(
            dense.mgdl.copyOfRange(from, to),
            anchorTsMs = dense.gridStartMs + (to - 1).toLong() * STEP_MS,
            gridStartMs = dense.gridStartMs + from.toLong() * STEP_MS,
        )
        val ch = runCatching { channels(series.gridStartMs, steps) }
            .getOrElse { ModelChannels.zero(steps) }
        val out = controller.runMasked(
            modelId = modelId,
            series = series,
            channels = ch,
            future = null,
            spans = listOf(span),
            withForecast = false,
            lora = lora,
            synthetic = false,
        )
        if (out.status.name != "OK") return "Refused: ${out.status.name.lowercase().replace('_', ' ')}"
        val rows = ArrayList<BgInfillEntity>(gap.steps)
        val now = clock()
        val spanStartStep = span.startPatch * s
        out.forecast.medianBg.forEachIndexed { i, v ->
            val step = from + spanStartStep + i
            val ts = dense.gridStartMs + step.toLong() * STEP_MS
            if (ts in gap.startMs until gap.endMs) {
                // The WHOLE fan for this slot, both spaces, stored beside the line. The panel's τ
                // slider reads a level the model already emitted rather than inventing one, and
                // this row is the only place the fan survives the run that made it.
                val mgdlFan = List(N_QUANTILES) { k -> out.forecast.bandsMgdl[i * N_QUANTILES + k] }
                val riskFan = List(N_QUANTILES) { k -> out.forecast.qTauRisk[i * N_QUANTILES + k] }
                rows.add(
                    BgInfillEntity(
                        ts = ts,
                        mgdl = v,
                        // The central 90 % is τ.05 and τ.95 — the fan's outer pair, columns 0
                        // and 6. Columns 1 and 5 are τ.10/τ.90, which is the central 80 %.
                        lo90 = mgdlFan.first(),
                        hi90 = mgdlFan.last(),
                        modelId = modelId,
                        createdAtMs = now,
                        // One run is one span, and the span is what promotion and demotion act on.
                        spanStartMs = gap.startMs,
                        bandsMgdl = mgdlFan.toBlob(),
                        bandsRisk = riskFan.toBlob(),
                        // The line as drawn is the median until a slider moves it.
                        tau = 0.5,
                    ),
                )
            }
        }
        if (rows.isEmpty()) return "Nothing to fill"
        if (!repository.saveInfill(rows)) return "Span already promoted"
        return "Filled ${rows.size} steps"
    }

    /**
     * Write a reconstructed span into the record as stored, syncable samples.
     *
     * Here and not on the BG panel, deliberately. Nothing drawn on the dashboard is stored, and
     * that standing property is worth more than the convenience of promoting where the fill was
     * drawn — this is the surface that names the model, its held-out numbers and its adapter, which
     * is what a person deciding whether to keep a reconstruction needs in front of them.
     */
    /**
     * Move a drawn span's line to the fan's τ-th quantile.
     *
     * Reads the fan the run already emitted — one `band_line_at` per span, in risk space, decoded
     * through the SAME descriptor that produced it. Nothing is re-run and no median is moved: the
     * line changes which level of an emitted fan it traces, and the level is stored so a promotion
     * of it cannot later be read as the median.
     *
     * Refuses a span whose rows predate the fan columns, and one already promoted.
     */
    suspend fun retau(spanStartMs: Long, tau: Double): String {
        val rows = repository.infillSpan(spanStartMs)
        if (rows.isEmpty()) return "Span is gone"
        if (rows.any { it.promotedAtMs != null }) return "Span is promoted — demote it first"
        val line = lineAt(rows, tau) ?: return "This fill was drawn before the fan was kept"
        if (!repository.retauInfillSpan(spanStartMs, tau, line)) return "Span is gone"
        return "τ ${"%.2f".format(tau)}"
    }

    /**
     * The same line, WITHOUT storing it — what the slider draws while the thumb is still down.
     *
     * One implementation shared with [retau], so the line the user watches and the line that lands
     * cannot be read from the fan two different ways.
     */
    suspend fun previewTau(spanStartMs: Long, tau: Double): SpanLinePreview? {
        val rows = repository.infillSpan(spanStartMs)
        if (rows.isEmpty() || rows.any { it.promotedAtMs != null }) return null
        val line = lineAt(rows, tau) ?: return null
        return SpanLinePreview(
            spanStartMs = spanStartMs,
            tau = tau,
            mgdl = rows.mapIndexed { i, r -> r.ts to line[i] }.toMap(),
        )
    }

    /** Read a span's own fan at [tau], in risk space, through the descriptor that produced it. */
    private suspend fun lineAt(rows: List<BgInfillEntity>, tau: Double): List<Double>? {
        val desc = controller.descriptorOf(rows.first().modelId) ?: return null
        val fan = ArrayList<Double>(rows.size * N_QUANTILES)
        for (r in rows) {
            val slot = r.bandsRisk.toDoubleList()
            if (slot.size != N_QUANTILES) return null
            fan.addAll(slot)
        }
        val line = runCatching { native.bandLineAt(desc, fan, tau) }.getOrNull() ?: return null
        return line.takeIf { it.size == rows.size }
    }

    /** Throw a drawn span away. Refuses a promoted one — demotion is the way out of that state. */
    suspend fun discard(spanStartMs: Long): String =
        if (repository.discardInfillSpan(spanStartMs)) "Fill discarded" else "Span is promoted"

    suspend fun promote(spanStartMs: Long): String =
        when (val r = repository.promoteInfillSpan(spanStartMs, clock())) {
            is PromoteResult.Promoted -> "Promoted " + r.steps + " steps"
            is PromoteResult.Refused -> r.why
        }

    suspend fun demote(spanStartMs: Long): String =
        when (val r = repository.demoteInfillSpan(spanStartMs, clock())) {
            is PromoteResult.Promoted -> "Demoted " + r.steps + " steps"
            is PromoteResult.Refused -> r.why
        }

    /** The reconstructed spans in a window, newest first — the Lab's promotion list. */
    fun spans(fromMs: Long, toMs: Long) = repository.observeReconstructedSpans(fromMs, toMs)

    suspend fun spanSize(spanStartMs: Long): Int = repository.reconstructedSpanSize(spanStartMs)

    private fun LoraEntity.toUi() = LabAdapter(
        id = id,
        modelId = modelId,
        name = name,
        rank = rank,
        nParams = nParams,
        nTrain = nTrain,
        nHoldout = nHoldout,
        holdoutBefore = holdoutBefore,
        holdoutAfter = holdoutAfter,
        improved = improved,
        attached = attached,
        updatedAtMs = updatedAtMs,
        // The SAME predicate `attach` enforces, so the button and the gate cannot disagree about
        // what is allowed. The panel renders this; it does not decide it.
        attachRefusal = loraAttachRefusal(
            verdict = runCatching { LoraGuardVerdict.valueOf(guardVerdict) }
                .getOrDefault(LoraGuardVerdict.ABSENT),
            overrideAtMs = guardOverrideAtMs,
            historyMutatedAtMs = historyMutatedAtMs,
            fittedAtMs = fittedAtMs,
            why = guardWhy,
        ),
        guardRetention = guardRetention,
        guardWindows = guardWindows,
        guardFrozenMgdl = guardFrozenMgdl,
        guardAdaptedMgdl = guardAdaptedMgdl,
        guardOverridden = guardOverrideAtMs != null,
    )

    private companion object {
        const val STEP_MS = 300_000L
        const val EXT = ".t1dmlora"

        /** The levels the head emits (`SPEC/invariants.md` §6) — one slot's fan is this wide. */
        const val N_QUANTILES = 7
    }
}

/**
 * Where a reconstruction's context window starts, as an index into the dense series.
 *
 * The origin must land on an ABSOLUTE patch boundary, not merely on a multiple of the patch size
 * counted from `gridStartMs` — which is a reading timestamp and need not be one. The panel snaps its
 * selection to absolute boundaries, so an origin that is not one maps the selection onto fractional
 * patch indices: the span straddles a boundary and comes back ONE PATCH LONGER than the drag, which
 * is how a selection already clamped to the model's own envelope still met "the model was trained to
 * N patches".
 *
 * [preferred] is where the caller would like it — far enough back that the gap has real evidence on
 * both sides. This walks DOWN to the boundary at or before it and, only if that leaves the series,
 * back up a patch. Null when no aligned window of [steps] fits at all.
 *
 * Aligning is the LAST step, and that ordering is the whole of the fix: clamping into the series
 * after aligning undoes the alignment precisely when the window cannot slide, which is the ordinary
 * case for a fill near the newest reading.
 */
internal fun alignedWindowOrigin(
    preferred: Int,
    gridStartMs: Long,
    size: Int,
    steps: Int,
    patchSize: Int,
): Int? {
    if (steps <= 0 || patchSize <= 0 || size < steps) return null
    val stepMs = 300_000L
    val patchMs = patchSize.toLong() * stepMs
    fun slipSteps(f: Int): Int {
        val originMs = gridStartMs + f.toLong() * stepMs
        return ((((originMs % patchMs) + patchMs) % patchMs) / stepMs).toInt()
    }
    var from = preferred.coerceIn(0, size - steps)
    from -= slipSteps(from)
    if (from < 0) from += patchSize
    if (from + steps > size) from -= patchSize
    if (from < 0 || from + steps > size) return null
    return from
}
