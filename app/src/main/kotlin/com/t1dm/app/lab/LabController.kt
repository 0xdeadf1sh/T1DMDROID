package com.t1dm.app.lab

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.LoraConfig
import com.t1dm.core.model.targetBits
import com.t1dm.core.model.LoraGuardVerdict
import com.t1dm.inference.loraAttachRefusal
import com.t1dm.core.model.MaskGeometry
import com.t1dm.core.model.LoraTrainOpts
import com.t1dm.core.model.MaskSpan
import com.t1dm.data.PromoteResult
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.BgInfillEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList
import com.t1dm.data.db.LoraEntity
import com.t1dm.core.model.SpanLinePreview
import com.t1dm.feature.models.LoraAdapter
import com.t1dm.feature.models.LoraFitSpec
import com.t1dm.inference.BgHistoryProvider
import com.t1dm.inference.BgSeries
import com.t1dm.inference.InferenceController
import com.t1dm.inference.ModelChannels
import java.io.File
import kotlin.math.min

/** Drives the adapter panel and BG reconstruction; writes nothing except runSpan's own table. */
class LabController(
    private val native: NativeCore,
    private val controller: InferenceController,
    private val repository: T1dmRepository,
    private val history: BgHistoryProvider,
    private val channels: suspend (Long, Int) -> ModelChannels,
    private val adaptersDir: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun adaptersOf(modelId: String): List<LoraAdapter> =
        runCatching { repository.lorasFor(modelId).map { it.toUi() } }.getOrElse { emptyList() }

    /** Stores adapter DETACHED; onReplay/onEpoch called on this coroutine's own thread. */
    suspend fun fit(
        modelId: String,
        spec: LoraFitSpec,
        onReplay: ((done: Int, total: Int) -> Unit)? = null,
        onEpoch: ((epoch: Int, epochs: Int) -> Unit)? = null,
    ): String {
        val desc = controller.descriptorOf(modelId) ?: return "Model not loaded"
        val head = desc.head ?: return "This model ships no head file — no adapter can attach"
        // One hour: wider loses windows faster than it gains, starves a fresh sensor under 16.
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
                // Every input to the verdict, not just the verdict: a ratio hides which side moved.
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
        val r = result.report
        // At the one-hour stride windows are ~99% shared; non-overlap is the honest denominator.
        val windowSteps = desc.minContextPatches * desc.patchSize +
            (desc.predictionHorizonHours * 12 / desc.patchSize) * desc.patchSize
        val independent = (windowSteps + (samples.size - 1) * stride) / windowSteps
        return "${r.nTrain}+${r.nHoldout} windows (~$independent independent) · " +
            "held out ${"%.4f".format(r.holdoutLossBefore)} → ${"%.4f".format(r.holdoutLossAfter)}" +
            if (r.improved) " @ epoch ${r.bestEpoch}" else " · no gain"
    }

    /** Attaching drops band correction/accuracy history: both fit against the frozen forecaster. */
    suspend fun attach(modelId: String, id: Long): String? {
        val row = repository.loraById(id) ?: return "Adapter is gone"
        // Blob before the gate: an unloadable one attaches clean and runs as NOTHING silently.
        val head = controller.descriptorOf(modelId)?.head
            ?: return "This model ships no head file — nothing to attach an adapter to"
        val w = native.loraDeserialize(row.blob) ?: return "This adapter will not load"
        if (w.headSha256 != head.sha256) return "This adapter belongs to another head"
        // Sites are written with the weights; disagreement means the row was edited/restored raw.
        if (w.config.targetBits() != row.targets) return "Stored sites do not match the weights"
        // Structural, not a disabled button: a composable-only gate is one deeplink from bypass.
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
        return null
    }

    /** Only writer of a verdict besides fit; else an imported adapter sits ABSENT forever. */
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
        // A verdict measured against another head describes an adapter that can never run here.
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
        return report.why.ifBlank {
            "${report.verdict.name.lowercase()} over ${report.nWindows} windows"
        }
    }

    /** typedName must equal the adapter's name, compared here not the dialog; re-fit resets it. */
    suspend fun overrideGuard(id: Long, typedName: String): String? {
        val row = repository.loraById(id) ?: return "Adapter is gone"
        if (typedName.trim() != row.name) return "Type the adapter's name exactly to override"
        repository.setLoraGuardOverride(id, clock())
        return null
    }

    suspend fun detach(modelId: String) {
        repository.detachLoras(modelId, clock())
        repository.clearForecastDerived(modelId)
    }

    suspend fun rename(id: Long, name: String) {
        repository.renameLora(id, name, clock())
    }

    suspend fun delete(modelId: String, id: Long) {
        val row = repository.loraById(id)
        repository.deleteLora(id)
        if (row?.attached == true) repository.clearForecastDerived(modelId)
    }

    suspend fun export(id: Long): String {
        val row = repository.loraById(id) ?: return "Adapter is gone"
        val dir = adaptersDir().apply { mkdirs() }
        // A model id comes from a descriptor the app did not write; `../` in one would escape.
        fun safe(v: String) = v.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "adapter" }
        val file = File(dir, "${safe(row.modelId)}-${safe(row.name)}$EXT")
        return runCatching {
            file.writeBytes(row.blob)
            file.absolutePath
        }.getOrElse { "Export failed: ${it.message}" }
    }

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
            // Refused here rather than stored to fail silently at the next forecast.
            val w = native.loraDeserialize(bytes)
            if (w == null) { refused++; continue }
            // Same-shaped weights from another head would decode plausibly wrong.
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
                    // From the blob, not assumed: an import touches only its fitted sites.
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
        return "Imported $added" + if (refused > 0) ", refused $refused" else ""
    }

    data class Gap(val startMs: Long, val endMs: Long, val steps: Int)

    /** Reconstructs one span, stores the fill; geometry (§4) only differs the refusals below. */
    suspend fun runSpan(modelId: String, startMs: Long, endMs: Long, geometry: MaskGeometry): String {
        val gap = Gap(startMs = startMs, endMs = endMs, steps = ((endMs - startMs) / STEP_MS).toInt())
        val desc = controller.descriptorOf(modelId) ?: return "Model not loaded"
        // ATTACHED adapter as live cycle; refused, not frozen-fallback, else a fill lies.
        val attached = repository.attachedLora(modelId)
        val lora = if (attached == null) {
            null
        } else {
            // loraDeserialize returns null not throws; unhandled it reconstructs FROZEN.
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
        val trailing = min(dense.mgdl.size - gapEnd, steps / 3)
        // Aligned LAST, re-checked after: post-align clamp undoes it when the window can't slide.
        val from = alignedWindowOrigin(
            preferred = gapEnd + trailing - steps,
            gridStartMs = dense.gridStartMs,
            size = dense.mgdl.size,
            steps = steps,
            patchSize = s,
        ) ?: return "Not enough history"
        val to = from + steps
        if (gapStart < from || gapEnd > to) return "Gap does not fit one window"
        // A residue would mask a different stretch than the finger drew: refused, not rounded.
        if ((gapStart - from) % s != 0 || (gapEnd - from) % s != 0) {
            return "Span does not land on a patch boundary"
        }

        // A forecast isn't masked: future zone masks by construction, none writes to bg_infill.
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
        // LEFT-edge span is a BACKCAST (§7.4): drawable, refused at promotion, one-anchor extend.
        if (firstPatch < 0 || (firstPatch == 0 && geometry != MaskGeometry.BACKCAST)) {
            return "Gap sits at the window edge"
        }
        if (lastPatch >= nCtx - 1) return "Gap sits at the window edge"
        val span = MaskSpan(firstPatch, lastPatch - firstPatch + 1)
        if (span.length > desc.maxMaskedPatches) return "Gap is longer than the head's ${desc.maxMaskedPatches} slots"
        // Past the longest trained span, model still answers plausibly, no off-distribution signal.
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
                // Whole fan, both spaces: only place it survives the run; tau slider reads it.
                val mgdlFan = List(N_QUANTILES) { k -> out.forecast.bandsMgdl[i * N_QUANTILES + k] }
                val riskFan = List(N_QUANTILES) { k -> out.forecast.qTauRisk[i * N_QUANTILES + k] }
                rows.add(
                    BgInfillEntity(
                        ts = ts,
                        mgdl = v,
                        // Central 90 % is the outer pair, columns 0 and 6; 1 and 5 are τ.10/τ.90.
                        lo90 = mgdlFan.first(),
                        hi90 = mgdlFan.last(),
                        modelId = modelId,
                        createdAtMs = now,
                        // One run is one span; promotion and demotion act on the span.
                        spanStartMs = gap.startMs,
                        bandsMgdl = mgdlFan.toBlob(),
                        bandsRisk = riskFan.toBlob(),
                        tau = 0.5,
                    ),
                )
            }
        }
        if (rows.isEmpty()) return "Nothing to fill"
        if (!repository.saveInfill(rows)) return "Span already promoted"
        return "Filled ${rows.size} steps"
    }

    /** Moves a span's line to tau; traces the emitted fan in risk space, stored not re-derived. */
    suspend fun retau(spanStartMs: Long, tau: Double): String {
        val rows = repository.infillSpan(spanStartMs)
        if (rows.isEmpty()) return "Span is gone"
        if (rows.any { it.promotedAtMs != null }) return "Span is promoted — demote it first"
        val line = lineAt(rows, tau) ?: return "This fill was drawn before the fan was kept"
        if (!repository.retauInfillSpan(spanStartMs, tau, line)) return "Span is gone"
        return "τ ${"%.2f".format(tau)}"
    }

    /** [retau]'s line without storing it — what the slider draws while the thumb is down. */
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

    /** Newest first. */
    fun spans(fromMs: Long, toMs: Long) = repository.observeReconstructedSpans(fromMs, toMs)

    suspend fun spanSize(spanStartMs: Long): Int = repository.reconstructedSpanSize(spanStartMs)

    private fun LoraEntity.toUi() = LoraAdapter(
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
        // The same predicate [attach] enforces; the panel renders it, it does not decide it.
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

        /** `SPEC/invariants.md` §6. */
        const val N_QUANTILES = 7
    }
}

/** Context window origin index, on an ABSOLUTE patch boundary; null if no aligned window fits. */
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
