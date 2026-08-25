package com.t1dm.inference

import com.t1dm.core.model.LoraGuardVerdict

/** Null when the adapter may attach. Enforced in `LabController.attach`, not the UI. [overrideAtMs]
 *  clears ABSENT, BLOCKED and INCONCLUSIVE, only for the row it is stored on, and never the
 *  history-edit refusal. */
fun loraAttachRefusal(
    verdict: LoraGuardVerdict,
    overrideAtMs: Long?,
    historyMutatedAtMs: Long?,
    fittedAtMs: Long,
    why: String = "",
): String? {
    // Never overridable: an edit landing AFTER an override is information it could not weigh.
    // `fittedAtMs == 0` is an imported adapter, never fitted here — not an ancient fit.
    if (fittedAtMs > 0L && historyMutatedAtMs != null && historyMutatedAtMs > fittedAtMs) {
        return "Fitted on a dose or meal history that has since been edited — re-fit it"
    }
    if (overrideAtMs != null) return null
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
