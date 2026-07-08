package com.t1dm.core.model

/** Deterministic glucose bands for the model-free alarm (PLAN.private.md §3.6-A). */
enum class AlertBand { URGENT_LOW, LOW, IN_RANGE, HIGH, URGENT_HIGH }

/**
 * User-set, deliberately UNBOUNDED alarm boundaries (PLAN.private.md safety lock). Ordered
 * `urgentLow < low <= high < urgentHigh`. [bandFor] is the frozen band classification the
 * model-free threshold alarm evaluates directly on a MEASURED reading — no forecast involved.
 */
data class AlertThresholds(
    val urgentLowMgdl: Int,
    val lowMgdl: Int,
    val highMgdl: Int,
    val urgentHighMgdl: Int,
) {
    fun bandFor(bgMgdl: Int): AlertBand = when {
        bgMgdl < urgentLowMgdl -> AlertBand.URGENT_LOW
        bgMgdl < lowMgdl -> AlertBand.LOW
        bgMgdl >= urgentHighMgdl -> AlertBand.URGENT_HIGH
        bgMgdl >= highMgdl -> AlertBand.HIGH
        else -> AlertBand.IN_RANGE
    }
}
