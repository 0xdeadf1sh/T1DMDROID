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

/** No SavGol, unlike the neural cycle: fit/predict on raw series. Fit gaps NaN, core drops rows. */
class BaselineRunner(
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val store: BaselineStore?,
    private val events: CurveEventSource?,
    /** Committed dose tails over the pred zone, so a just-logged dose moves the forecast now. */
    private val futureOverrides: FutureOverrideSource?,
) {
    private val fitMutex = Mutex()

    @Volatile
    private var model: BaselineModel? = null

    @Volatile
    private var restored = false

    val fitted: BaselineModel? get() = model

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

    /** Serialised: a fit in flight refuses a 2nd entry. Thin history returns a zero-delta fit. */
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
                    // Callers threshold, not cores floor (19). This delta IS the guards interval.
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

    suspend fun clear() {
        model = null
        runCatching { store?.clear() }.onFailure { Timber.tag(TAG).w(it, "baseline clear failed") }
    }

    /** Null means this cycle publishes nothing for the baseline — never a padded forecast. */
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
        // Last GRID slot covered, not `anchorTsMs`, which tracks the last MEASURED reading.
        val anchorMs = series.gridStartMs + (series.mgdl.size - 1L) * GRID_MS
        val ev = if (m.spec.useIob || m.spec.useCob) {
            events?.events(anchorMs, anchorMs + GRID_MS).orEmpty()
        } else {
            emptyList()
        }

        // Zone opens AFTER the anchor, fits alignment. Unwired/short ⇒ core rejects, no fabricate.
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
                futureCarb = future?.carb?.toList().orEmpty(),
                futureInsulin = future?.insulin?.toList().orEmpty(),
            )
        } ?: return null

        val status: ForecastStatus =
            withContext(dispatchers.default) { native.baselineDegeneracyCheck(forecast) }
        val latMs = (System.nanoTime() - t0) / 1_000_000.0

        return ModelPrediction(
            modelId = BASELINE_MODEL_ID,
            cycleTsMs = cycleTs,
            anchorTsMs = series.anchorTsMs,
            sourceId = series.sourceId,
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
            // No circadian head; `SPEC/http-api.md` allows a null for a model without one.
            predictedTime = null,
        )
    }

    class BaselineFitException(val refusal: BaselineFitRefusal) : Exception(refusal.name)

    companion object {
        private const val TAG = "BaselineRunner"
        private const val GRID_MS = 300_000L
        private const val N_QUANTILES = 7

        /** 14 days at 5-minute cadence — both splits meaningful without an `O(n·d²)` wait. */
        const val FIT_WINDOW_STEPS = 4032

        /** Headroom above lag+horizon so a just-clearing fit doesnt fail the split guard. */
        private const val MIN_FIT_SLACK_STEPS = 288
    }
}
