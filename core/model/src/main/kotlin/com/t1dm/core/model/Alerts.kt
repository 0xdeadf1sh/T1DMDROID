package com.t1dm.core.model

/** Deterministic glucose bands for the model-free alarm (§3.6-A). */
enum class AlertBand { URGENT_LOW, LOW, IN_RANGE, HIGH, URGENT_HIGH }

/** Deliberately UNBOUNDED, user-set, urgentLow<low<=high<urgentHigh; bandFor is MEASURED only. */
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
