package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.PROBE_DOSE_U
import com.t1dm.core.model.SensitivityEstimate
import timber.log.Timber

/** The GI is the resolver's to pin: it moves how much of the meal has appeared by the probe's
 *  horizon, so probes taken under two GIs are not comparable. */
fun interface CarbResolver {
    suspend fun resolve(grams: Double, atMs: Long): List<CurveEvent>
}

/**
 * ISF (mg/dL per U) and ICR (g per U) from three rolls of one [ForecastPort], differenced at the END
 * of the validated window — so the ISF is strictly smaller than the whole-action figure a clinician
 * quotes, and the ICR is shifted by however much of the meal has appeared by then.
 *
 * Both counterfactuals ride `candidate`, never `announced`: only the candidate is re-anchored onto
 * the prediction zone's first bucket, and as `announced` the meal and the dose land at instants that
 * differ, with the leading Ra bucket droppable by `bucketize`'s negative-index guard.
 *
 * Null ⇒ no model response was obtained. A response is reported unfiltered, wrong sign and all;
 * nothing downstream can act on it, since no rail, advisor or store accepts a [SensitivityEstimate].
 */
class SensitivityProbe(
    private val port: ForecastPort,
    private val insulin: BolusResolver,
    private val carb: CarbResolver,
    private val selectedModelId: suspend () -> String?,
) {

    suspend fun probe(
        nowMs: Long,
        config: CalcConfig,
        /** Pinned across all three rolls: differencing fans built on different BG input filters
         *  would attribute the filter's own step to the dose. */
        smoothingWindow: Int? = null,
    ): SensitivityEstimate? {
        val steps = config.horizon.validatedSteps
        if (steps <= 0) return withhold("validated horizon is 0 steps")

        fun request(candidate: List<CurveEvent>?, candidateU: Double) =
            ForecastRequest(
                rollStartMs = nowMs,
                fullRollSteps = steps,
                validatedSteps = steps,
                announced = emptyList(),
                candidate = candidate,
                candidateU = candidateU,
                smoothingWindow = smoothingWindow,
            )

        val modelBefore = selectedModelId() ?: return withhold("no selected model")

        val baseline = port.roll(request(null, 0.0))
        val withInsulin = port.roll(request(insulin.resolve(PROBE_DOSE_U, nowMs), PROBE_DOSE_U))
        // candidateU stays 0: it is the candidate's INSULIN total, and a meal contributes none.
        val withCarb = port.roll(request(carb.resolve(PROBE_CARB_G, nowMs), 0.0))

        // The three rolls are comparable only if one artifact produced all three.
        val modelAfter = selectedModelId()
        if (modelAfter != modelBefore) return withhold("model changed mid-probe: $modelBefore -> $modelAfter")

        val fans = listOf(baseline, withInsulin, withCarb)
        if (fans.any { !it.eligible }) return withhold("fans ${fans.map { it.eligibility }}")

        val windows = fans.map { it.validatedWindow() }
        val n = windows[0].size
        // Ragged windows would difference two instants and call the gap a dose response.
        if (n == 0 || windows.any { it.size != n }) return withhold("windows ${windows.map { it.size }}")

        val terminal = windows.map { it[n - 1].medianBg }
        if (terminal.any { !it.isFinite() }) return withhold("non-finite terminal $terminal")

        val insulinDrop = terminal[0] - terminal[1]
        val carbRise = terminal[2] - terminal[0]

        // No direction or magnitude filter: report what the model said. Only arithmetic with no
        // result refuses — a zero carb response divides to an infinity, which is not a figure.
        val isf = insulinDrop / PROBE_DOSE_U
        val icr = isf * PROBE_CARB_G / carbRise
        if (!isf.isFinite() || !icr.isFinite()) {
            return withhold("non-finite: dI=%.2f dC=%.2f isf=%s icr=%s".format(insulinDrop, carbRise, isf, icr))
        }
        Timber.tag(TAG).i("probe: ISF %.1f mg/dL/U, ICR %.1f g/U (dI=%.2f dC=%.2f)", isf, icr, insulinDrop, carbRise)

        return SensitivityEstimate(
            atMs = nowMs,
            horizonMs = steps.toLong() * baseline.stepMs,
            isfMgdlPerU = isf,
            icrGPerU = icr,
            modelId = modelBefore,
        )
    }

    private fun withhold(why: String): SensitivityEstimate? {
        Timber.tag(TAG).i("probe withheld: %s", why)
        return null
    }

    companion object {
        private const val TAG = "Sensitivity"

        /** Ten grams, not one: a single gram's predicted rise sits inside the decode's own grain,
         *  and the response is not assumed linear. */
        const val PROBE_CARB_G = 10.0
    }
}
