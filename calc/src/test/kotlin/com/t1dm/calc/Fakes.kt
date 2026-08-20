package com.t1dm.calc

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.ForecastStatus

/** 5-min grid, matching `CurveEngine.STEP_MS`. */
const val STEP_MS = 300_000L

/**
 * A deterministic stand-in for the real [RollingForecaster], so the calculator's safety logic is
 * exercised without a model on the host (the on-device `.pte` path is verified separately per the
 * plan). BG is a simple linear response: each candidate unit lowers BG by [mgdlPerU] and each
 * announced/candidate carb gram raises it by [mgdlPerG], ramped linearly over the roll; the band
 * widens with the horizon. [forceEligibility] injects the DEGENERATE / STALE / MISSING failure modes
 * the fail-closed invariants require.
 */
class FakeForecastPort(
    private val startBg: Double = 200.0,
    private val driftPerStep: Double = 0.0,
    private val mgdlPerU: Double = 15.0,
    private val mgdlPerG: Double = 3.0,
    private val bandBase: Double = 5.0,
    private val bandGrowthPerStep: Double = 0.6,
    private val forceEligibility: ForecastEligibility? = null,
    private val forceStatus: ForecastStatus = ForecastStatus.OK,
) : ForecastPort {

    var rollCount = 0
        private set

    override suspend fun roll(request: ForecastRequest): PredFan {
        rollCount++
        val elig = forceEligibility
        if (elig != null && elig != ForecastEligibility.ELIGIBLE) {
            return PredFan(request.candidateU, emptyList(), STEP_MS, request.validatedSteps, forceStatus, elig)
        }
        val n = request.fullRollSteps
        val carbG = request.announced.filter { it.kind == CurveKind.CARB }.sumOf { it.total } +
            (request.candidate?.filter { it.kind == CurveKind.CARB }?.sumOf { it.total } ?: 0.0)
        val steps = (0 until n).map { i ->
            val frac = (i + 1).toDouble() / n
            val median = startBg + driftPerStep * i - request.candidateU * mgdlPerU * frac + carbG * mgdlPerG * frac
            val half = bandBase + bandGrowthPerStep * i
            FanStep(median, median - half, median + half)
        }
        return PredFan(request.candidateU, steps, STEP_MS, request.validatedSteps, ForecastStatus.OK, ForecastEligibility.ELIGIBLE)
    }
}

/** The fake port keys off [ForecastRequest.candidateU]; the resolved events only matter to the real port. */
class FakeBolusResolver : BolusResolver {
    override suspend fun resolve(doseU: Double, atMs: Long): List<CurveEvent> =
        listOf(CurveEvent(atMs, STEP_MS, CurveKind.INSULIN, doseU, listOf(doseU)))
}

fun fakeAnchor(
    nowMs: Long,
    ageMin: Long = 2,
    currentBg: Double? = 160.0,
    interpolatedFraction: Double = 0.0,
    warmup: Boolean = false,
    hasMeasured: Boolean = true,
): AnchorInfo = AnchorInfo(
    lastMeasuredTsMs = if (hasMeasured) nowMs - ageMin * 60_000L else null,
    anchorTsMs = nowMs,
    currentBgMgdl = currentBg,
    interpolatedFraction = interpolatedFraction,
    warmup = warmup,
)

fun fakeIob(
    nowMs: Long,
    iobU: Double? = 1.0,
    cobG: Double = 0.0,
    lastLoggedMinAgo: Long? = 30,
): IobSnapshot = IobSnapshot(
    iobU = iobU,
    cobG = cobG,
    lastLoggedDoseTsMs = lastLoggedMinAgo?.let { nowMs - it * 60_000L },
)

fun fp32Backend(agreementOk: Boolean? = null): BackendInfo =
    BackendInfo(com.t1dm.core.model.BackendId.EXECUTORCH_XNNPACK_FP32, com.t1dm.core.model.Precision.FP32, agreementOk)

/** Assemble a [DoseAdvisor] over the fakes with sensible defaults; override any dependency per test. */
fun advisorOf(
    port: ForecastPort,
    anchor: AnchorInfo?,
    iob: IobSnapshot?,
    backend: BackendInfo? = fp32Backend(),
): DoseAdvisor = DoseAdvisor(
    bolus = BolusCalculator(port, FakeBolusResolver()),
    anchorSource = { anchor },
    iobSource = { iob },
    backendSource = { backend },
)
