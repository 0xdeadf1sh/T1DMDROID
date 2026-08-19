package com.t1dm.app.lab

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.MaskSpan
import com.t1dm.core.model.ModelDescriptor
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.BgInfillEntity
import com.t1dm.data.db.LoraEntity
import com.t1dm.feature.models.LabAdapter
import com.t1dm.feature.models.LabFanLadder
import com.t1dm.feature.models.LabRun
import com.t1dm.feature.models.LabSpan
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
 * Drives the Lab, the adapter panel and the gap repair.
 *
 * The three share one holder because they share one awkward resource: a window of history, its four
 * channels, and a loaded model to push them through. Nothing here writes a reading, a prediction, a
 * statistic or an outbox row — the single exception is the gap repair, which writes reconstructed
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

    /** τ levels the ladder carries — the slider's resolution, and the fan's own two edges included. */
    private val tauLadder = (1..19).map { it * 0.05 }

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
                maxMaskedPatches = desc?.maxMaskedPatches ?: 0,
                maxSpans = desc?.maskMaxSpans ?: 0,
                maxSpanPatches = desc?.maskSpanMax ?: 0,
                realPatches = realPatches,
                forecastPatches = desc?.let { d -> d.predictionHorizonHours * 12 / d.patchSize } ?: 0,
                adapters = picked?.let { id -> adaptersFor(id) } ?: emptyList(),
                adapterId = null,
            )
        }
    }

    fun pickModel(id: String) = _state.update { it.copy(modelId = id, run = null, spans = emptyList()) }
    fun setSynthetic(on: Boolean) = _state.update { it.copy(synthetic = on, run = null) }
    fun setSeed(seed: Long) = _state.update { it.copy(seed = seed, run = null) }
    fun setSpans(spans: List<LabSpan>) = _state.update { it.copy(spans = spans) }
    fun setForecast(on: Boolean) = _state.update { it.copy(withForecast = on) }
    fun pickAdapter(id: Long?) = _state.update { it.copy(adapterId = id) }
    fun setTau(tau: Double) = _state.update { it.copy(tau = tau) }

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

    // ── the run ─────────────────────────────────────────────────────────────────────

    /** Run the current masked set. One experiment; the result replaces the last. */
    suspend fun run() {
        val st = _state.value
        val modelId = st.modelId ?: return
        val desc = controller.descriptorOf(modelId) ?: return
        _state.update { it.copy(running = true, error = null) }
        try {
            val nCtx = desc.minContextPatches
            val steps = nCtx * desc.patchSize
            val (series, ch, synthetic) = windowFor(desc, steps, st.synthetic, st.seed)
                ?: run {
                    _state.update { it.copy(running = false, error = "No history for this window") }
                    return
                }
            val adapter = st.adapterId?.let { id ->
                repository.loraById(id)?.blob?.let { native.loraDeserialize(it) }
            }
            val future = if (st.withForecast) {
                runCatching {
                    channels(
                        series.gridStartMs + series.mgdl.size.toLong() * STEP_MS,
                        desc.predictionHorizonHours * 12,
                    )
                }.getOrNull()
            } else {
                null
            }
            val out = controller.runMasked(
                modelId = modelId,
                series = series,
                channels = ch,
                future = future,
                spans = st.spans.map { MaskSpan(it.startPatch, it.patches) },
                withForecast = st.withForecast,
                lora = adapter,
                synthetic = synthetic,
            )
            _state.update {
                it.copy(running = false, run = toRun(desc, out, series, st, adapterName(st)))
            }
        } catch (t: Throwable) {
            Timber.w(t, "lab run failed")
            _state.update { it.copy(running = false, error = t.message ?: "Run failed") }
        }
    }

    private fun adapterName(st: LabUiState): String? =
        st.adapterId?.let { id -> st.adapters.firstOrNull { it.id == id }?.name }

    /**
     * The window a run reads: real history, or a synthetic patient filling whatever the real
     * history lacks.
     *
     * Synthetic mode fills only the MISSING steps — every real sample survives. That is what makes
     * it usable at all: a seven-day context on a phone that has two days of readings is two real
     * days and five invented ones, and the run says so.
     */
    private suspend fun windowFor(
        desc: ModelDescriptor,
        steps: Int,
        synthetic: Boolean,
        seed: Long,
    ): Triple<BgSeries, ModelChannels, Boolean>? {
        val real = runCatching { history.recentBgSeries(steps, desc.patchSize) }.getOrNull()
        if (!synthetic) {
            if (real == null || real.mgdl.size < steps) return null
            val ch = runCatching { channels(real.gridStartMs, real.mgdl.size) }
                .getOrElse { ModelChannels.zero(real.mgdl.size) }
            return Triple(real, ch, false)
        }
        // The grid the synthetic window sits on: the real one where there is one, else ending now.
        val anchorMs = real?.let { it.gridStartMs + (it.mgdl.size - 1).toLong() * STEP_MS }
            ?: (clock() / STEP_MS * STEP_MS)
        val startMs = anchorMs - (steps - 1).toLong() * STEP_MS
        val gen = native.synthSeries(steps, localHourAt(startMs), native.synthDefaultParams(), seed)
        val realBg = DoubleArray(steps) { Double.NaN }
        val realCh = runCatching { channels(startMs, steps) }.getOrElse { ModelChannels.zero(steps) }
        // The MEASURED series, not the carried-forward one: a dropout inside the history is a step
        // with no reading, and synthetic mode exists to fill exactly those. Reading the dense
        // series here would hand the model a flat carry-forward and call it real.
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
        val series = BgSeries(filled.bg.toDoubleArray(), anchorTsMs = anchorMs, gridStartMs = startMs)
        val ch = ModelChannels(
            filled.carb.toDoubleArray(),
            filled.insulin.toDoubleArray(),
            filled.exercise.toDoubleArray(),
        )
        return Triple(series, ch, true)
    }

    private fun localHourAt(ms: Long): Double {
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.timeInMillis = ms
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
    }

    /** Project one run onto what the screen draws, ladder included. */
    private fun toRun(
        desc: ModelDescriptor,
        out: InferenceController.MaskedRun,
        series: BgSeries,
        st: LabUiState,
        adapterName: String?,
    ): LabRun {
        val s = desc.patchSize
        val f = out.forecast
        val masked = f.slotPatch.toSet()
        // Context steps, with the withheld patches punched out — the trace as the model got it.
        val contextBg = ArrayList<Double?>(series.mgdl.size)
        series.mgdl.forEachIndexed { i, v ->
            val patch = out.padPatches + i / s
            contextBg.add(if (patch in masked) null else v)
        }
        val ladders = ArrayList<LabFanLadder>()
        var slot = 0
        while (slot < f.slotPatch.size) {
            var end = slot + 1
            while (end < f.slotPatch.size && f.slotPatch[end] == f.slotPatch[end - 1] + 1) end++
            val fromPatch = f.slotPatch[slot]
            val span = native.forecastSlice(f, fromPatch, f.slotPatch[end - 1] + 1)
            val n = span.medianBg.size
            val lo = List(n) { span.bandsMgdl[it * 7] }
            val hi = List(n) { span.bandsMgdl[it * 7 + 6] }
            ladders.add(
                LabFanLadder(
                    startStep = (fromPatch - out.padPatches) * s,
                    isForecast = out.firstForecastPatch >= 0 && fromPatch >= out.firstForecastPatch,
                    lo = lo,
                    hi = hi,
                    ladder = tauLadder.map { tau -> tau to native.bandLine(desc, span, tau) },
                ),
            )
            slot = end
        }
        return LabRun(
            modelId = out.modelId,
            synthetic = out.synthetic,
            adapterName = adapterName,
            statusNote = out.status.takeIf { it.name != "OK" }?.name?.lowercase()?.replace('_', ' '),
            latencyMs = out.latencyMs,
            contextBg = contextBg,
            gridStartMs = series.gridStartMs,
            stepMs = STEP_MS,
            patchSize = s,
            nCtx = out.nCtx,
            fans = ladders,
        )
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
            targetHidden = true,
            targetL0 = true,
            targetL1 = true,
            targetL2 = true,
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
                targets = 0b1111,
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
    suspend fun attach(modelId: String, id: Long) {
        repository.attachLora(id, modelId, clock())
        repository.clearForecastDerived(modelId)
        _state.update { it.copy(adapters = adaptersFor(modelId)) }
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
                    targets = 0b1111,
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

    // ── gap repair ──────────────────────────────────────────────────────────────────

    /** A gap the sensor left, as the repair offers it. */
    data class Gap(val startMs: Long, val endMs: Long, val steps: Int)

    /** The gaps in the trailing [days] of measured signal, longest first. */
    suspend fun gaps(days: Int = 14, minSteps: Int = 3): List<Gap> {
        val steps = days * 24 * 12
        val fit = history.fitBgSeries(steps, 12) ?: return emptyList()
        return native.findGaps(fit.mgdl.toList(), minSteps).map {
            Gap(
                startMs = fit.gridStartMs + it.start.toLong() * STEP_MS,
                endMs = fit.gridStartMs + it.end.toLong() * STEP_MS,
                steps = it.end - it.start,
            )
        }
    }

    /**
     * Reconstruct one gap with [modelId] and store the fill.
     *
     * The window is placed so the gap sits INSIDE it with real evidence on both sides where the
     * history allows — that is the whole advantage of an infill over a forecast, and a gap pushed to
     * the right edge would just be a forecast with extra steps. Nothing but the gap's own steps is
     * written, and they go to their own table: a fill is what a model thinks was there, never a
     * reading.
     */
    suspend fun repair(modelId: String, gap: Gap): String {
        val desc = controller.descriptorOf(modelId) ?: return "Model not loaded"
        val s = desc.patchSize
        val nCtx = desc.minContextPatches
        val steps = nCtx * s
        val dense = history.recentBgSeries(steps * 3, steps) ?: return "Not enough history"
        val gapStart = ((gap.startMs - dense.gridStartMs) / STEP_MS).toInt()
        val gapEnd = ((gap.endMs - dense.gridStartMs) / STEP_MS).toInt()
        if (gapStart < 0 || gapEnd > dense.mgdl.size) return "Gap is outside the window"
        // Aim for a third of the window after the gap, and take what the history actually has.
        val trailing = min(dense.mgdl.size - gapEnd, steps / 3)
        var from = gapEnd + trailing - steps
        from = max(0, min(from, dense.mgdl.size - steps))
        from -= from % s
        val to = from + steps
        if (gapStart < from || gapEnd > to) return "Gap does not fit one window"

        val firstPatch = (gapStart - from) / s
        val lastPatch = (gapEnd - 1 - from) / s
        // A span abutting either edge has no visible neighbour to anchor on.
        if (firstPatch <= 0 || lastPatch >= nCtx - 1) return "Gap sits at the window edge"
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
            lora = null,
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
                rows.add(
                    BgInfillEntity(
                        ts = ts,
                        mgdl = v,
                        // The central 90 % is τ.05 and τ.95 — the fan's outer pair, columns 0
                        // and 6. Columns 1 and 5 are τ.10/τ.90, which is the central 80 %.
                        lo90 = out.forecast.bandsMgdl[i * 7],
                        hi90 = out.forecast.bandsMgdl[i * 7 + 6],
                        modelId = modelId,
                        createdAtMs = now,
                    ),
                )
            }
        }
        if (rows.isEmpty()) return "Nothing to fill"
        repository.saveInfill(rows)
        return "Filled ${rows.size} steps"
    }

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
    )

    private companion object {
        const val STEP_MS = 300_000L
        const val EXT = ".t1dmlora"
    }
}
