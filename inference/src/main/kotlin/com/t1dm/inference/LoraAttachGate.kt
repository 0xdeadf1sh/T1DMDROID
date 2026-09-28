package com.t1dm.inference

import com.t1dm.core.model.LoraGuardVerdict
import com.t1dm.core.model.MaskGeometry

/** overrideAtMs clears ABSENT/BLOCKED/INCONCLUSIVE for its row only, never history-edit refusal. */
fun loraAttachRefusal(
    verdict: LoraGuardVerdict,
    overrideAtMs: Long?,
    historyMutatedAtMs: Long?,
    fittedAtMs: Long,
    why: String = "",
    kind: MaskGeometry = MaskGeometry.FORECAST,
): String? {
    // Never overridable (edit after override unweighed); fittedAtMs==0 means imported, not old.
    if (fittedAtMs > 0L && historyMutatedAtMs != null && historyMutatedAtMs > fittedAtMs) {
        return "Fitted on a dose or meal history that has since been edited — re-fit it"
    }
    // The probe measures a forecast; a fill never reaches the dose calculator.
    if (overrideAtMs != null || kind != MaskGeometry.FORECAST) return null
    return when (verdict) {
        LoraGuardVerdict.PASS -> null
        LoraGuardVerdict.ABSENT ->
            "Never checked against the model's dose response — fit it on this phone, or override"
        LoraGuardVerdict.BLOCKED ->
            if (why.isBlank()) "Changed the model's dose response" else why
        LoraGuardVerdict.INCONCLUSIVE ->
            if (why.isBlank()) "The dose-response check could not reach a verdict" else why
    }
}
