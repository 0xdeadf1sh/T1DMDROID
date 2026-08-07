package com.t1dm.inference

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BASELINE_MODEL_ID
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.BaselineFit
import com.t1dm.core.model.BaselineFitRefusal
import com.t1dm.core.model.BaselineModel
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The classical baseline's cycle: fit on demand, predict every cycle, publish an ordinary
 * [ModelPrediction] the rest of the app cannot tell apart from a neural one.
 *
 * Two things it deliberately does NOT share with the neural path, both because sharing them would
 * make the comparison dishonest rather than fair:
 *
 *  * **No SavGol smoothing.** The neural cycle filters the BG channel before normalizing
 *    (`SPEC/inference.md` §7.1 leaves that to the consumer). The baseline is fitted on the raw
 *    series and predicts from the raw series, so its train and inference inputs match each other.
 *    Feeding it a filtered tail it was never fitted on would be a silent distribution shift.
 *  * **Gaps, not carry-forward.** The fit reads [BgHistoryProvider.fitBgSeries], whose gaps are
 *    `NaN`, so the core drops the affected rows instead of learning persistence from stretches of
 *    filled data. Inference still uses the ordinary dense series, which is what a model conditions
 *    on at run time.
 */
class BaselineRunner(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val store: BaselineStore?,
    private val events: CurveEventSource?,
    /** The COMMITTED dose tails over the prediction zone — the same source the neural cycle carries
     *  into its prediction patches. This is what makes a just-logged meal or bolus move the forecast
     *  immediately, instead of waiting for the next CGM sample to advance the anchor past it. */
    private val futureOverrides: FutureOverrideSource?,
) {
    private val fitMutex = Mutex()

    @Volatile
    private var model: BaselineModel? = null

    @Volatile
    private var restored = false

    /** The fitted model, or null before the first successful fit. */
    val fitted: BaselineModel? get() = model

    /** Load a previously fitted model. Idempotent; a corrupt or absent blob leaves it unfitted. */
    suspend fun restore() {
        if (restored) return
        restored = true
        model = runCatching { store?.load() }
            .onFailure { Timber.tag(TAG).w(it, "baseline restore failed") }
            .getOrNull()
        model?.let {
            Timber.tag(TAG).i(
                "restored baseline: %d rows, calibrated=%b, fitted %d",
                it.nTrainRows, it.calibrated, it.fittedAtMs,
            )
        }
    }

    /**
     * Fit the model from the patient's own history and persist it. Serialised: a second entry while
     * a fit is in flight is refused outright rather than queued, because the button that triggers
     * this is user-facing and a queued second fit would run against the same data for no benefit.
     *
     * A fit that RUNS but finds too little held-out history still returns a [BaselineFit] — with an
     * all-zero delta and `conformal.sufficient = false`. That model is stored and used: it predicts
     * a median, its fan stays degenerate, and the degeneracy guard withholds every forecast until a
     * later fit calibrates it. The alternative — discarding it — would hide from the user that the
     * fit ran at all.
     */
    suspend fun fit(history: BgHistoryProvider, nowMs: Long, minCalWindows: Int): Result<BaselineFit> {
        if (!fitMutex.tryLock()) return Result.failure(BaselineFitException(BaselineFitRefusal.BUSY))
        try {
            val spec = withContext(dispatchers.default) { native.baselineDefaultSpec() }
            val minSteps = spec.nLags + spec.horizonSteps + MIN_FIT_SLACK_STEPS
            val series = history.fitBgSeries(FIT_WINDOW_STEPS, minSteps)
                ?: return Result.failure(BaselineFitException(BaselineFitRefusal.INSUFFICIENT_HISTORY))

            val endMs = series.gridStartMs + series.mgdl.size.toLong() * GRID_MS
            val ev = events?.events(series.gridStartMs, endMs).orEmpty()

            val fit = withContext(dispatchers.default) {
                native.fitBaselineRidge(
                    bgMgdl = series.mgdl.toList(),
                    gridStartMs = series.gridStartMs,
                    events = ev,
                    spec = spec,
                    nowMs = nowMs,
                    // The caller's policy threshold, not the core's arithmetic floor. The floor
                    // (`conformalMinCalWindows`, 19) is only the count below which a level's order
                    // statistic clamps — the core raises anything smaller to it, and its own note says
                    // a caller should ask for far more. Asking for the floor would hold this band to a
                    // weaker standard than the neural model's display-only correction is held to,
                    // which is backwards: this delta IS the interval, and it is what decides whether
                    // the forecast escapes the collapsed-band guard at all.
                    minCalWindows = minCalWindows,
                )
            } ?: return Result.failure(BaselineFitException(BaselineFitRefusal.CORE_REFUSED))

            model = fit.model
            runCatching { store?.save(fit.model) }
                .onFailure { Timber.tag(TAG).w(it, "baseline persist failed") }
            Timber.tag(TAG).i(
                "baseline fit: %d rows, %d holdout windows, calibrated=%b, cov90=%s, rmse@30=%s vs zoh %s",
                fit.model.nTrainRows, fit.nHoldoutWindows, fit.model.calibrated,
                fit.conformal.cov90Cal?.let { "%.3f".format(it) } ?: "n/a",
                fit.holdoutRmseMgdl.getOrNull(5)?.let { "%.1f".format(it) } ?: "n/a",
                fit.persistenceRmseMgdl.getOrNull(5)?.let { "%.1f".format(it) } ?: "n/a",
            )
            return Result.success(fit)
        } finally {
            fitMutex.unlock()
        }
    }

    /** Discard the fitted model — the counterpart of deleting a neural model's artifact. */
    suspend fun clear() {
        model = null
        runCatching { store?.clear() }.onFailure { Timber.tag(TAG).w(it, "baseline clear failed") }
    }

    /**
     * One cycle's forecast, or null when there is no fitted model, the tail is too short, or the
     * core rejects the input. Null means this cycle publishes nothing for the baseline — never a
     * substituted or padded forecast.
     */
    suspend fun predict(
        series: BgSeries,
        cycleTs: Long,
        selected: Boolean,
        stale: Boolean,
    ): ModelPrediction? {
        val m = model ?: return null
        val p = m.spec.nLags
        if (series.mgdl.size < p) return null

        val t0 = System.nanoTime()
        // The anchor is the last GRID slot the series covers, which is what its final value belongs
        // to — not `anchorTsMs`, which tracks the last MEASURED reading and can sit further back.
        val anchorMs = series.gridStartMs + (series.mgdl.size - 1L) * GRID_MS
        val ev = if (m.spec.useIob || m.spec.useCob) {
            events?.events(anchorMs, anchorMs + GRID_MS).orEmpty()
        } else {
            emptyList()
        }

        // The prediction zone opens at the step AFTER the anchor, which is the alignment the fit
        // slices out of its own window. Unwired (or a source that returns short) ⇒ no forward
        // features are available, and a model fitted WITH them must not be run against a fabricated
        // zero future — the core rejects the short array and this cycle publishes nothing.
        val future = if (m.spec.useForward) {
            futureOverrides?.overrides(anchorMs + GRID_MS, m.spec.horizonSteps)
        } else {
            null
        }

        val forecast = withContext(dispatchers.default) {
            val iob = if (m.spec.useIob) native.baselineOnBoardAt(ev, anchorMs, CurveKind.INSULIN) else 0.0
            val cob = if (m.spec.useCob) native.baselineOnBoardAt(ev, anchorMs, CurveKind.CARB) else 0.0
            native.baselinePredict(
                model = m,
                bgTail = series.mgdl.copyOfRange(series.mgdl.size - p, series.mgdl.size).toList(),
                iob = iob,
                cob = cob,
                futureCarb = future?.first?.toList().orEmpty(),
                futureInsulin = future?.second?.toList().orEmpty(),
            )
        } ?: return null

        val status: ForecastStatus =
            withContext(dispatchers.default) { native.baselineDegeneracyCheck(forecast) }
        val latMs = (System.nanoTime() - t0) / 1_000_000.0

        return ModelPrediction(
            modelId = BASELINE_MODEL_ID,
            cycleTsMs = cycleTs,
            anchorTsMs = series.anchorTsMs,
            stepMs = GRID_MS,
            medianBg = forecast.medianBg,
            bandsMgdl = forecast.bandsMgdl,
            nQuantiles = N_QUANTILES,
            lastBg = series.mgdl[series.mgdl.size - 1],
            status = status,
            backend = BackendId.NATIVE_RIDGE_FP64,
            precision = Precision.FP64,
            selected = selected,
            stale = stale,
            latencyMs = latMs,
            // No circadian head: the wire carries `circadian: null` for this model, which
            // `SPEC/http-api.md` already allows for a model that has none.
            predictedTime = null,
        )
    }

    /** Thrown into a [Result] so a caller can distinguish the refusals the UI must word differently. */
    class BaselineFitException(val refusal: BaselineFitRefusal) : Exception(refusal.name)

    companion object {
        private const val TAG = "BaselineRunner"
        private const val GRID_MS = 300_000L
        private const val N_QUANTILES = 7

        /** 14 days at 5-minute cadence — enough for both splits to be meaningful without making the
         *  `O(n·d²)` accumulation something a user waits on. */
        const val FIT_WINDOW_STEPS = 4032

        /** Headroom above the bare lag+horizon minimum, so a fit that only just clears the length
         *  check does not then fail the core's own split-size guard. */
        private const val MIN_FIT_SLACK_STEPS = 288
    }
}
