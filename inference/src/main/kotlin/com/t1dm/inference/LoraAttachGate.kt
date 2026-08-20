package com.t1dm.inference

import com.t1dm.core.model.LoraGuardVerdict

/**
 * Why an adapter may not be attached, or null when it may.
 *
 * A PURE function of the four facts, so the rule can be tested without a database, a model or a
 * screen — and so the gate and the button that renders it cannot drift apart, because both call
 * this. The enforcement itself is in `LabController.attach`, not in the UI: a gate that lives only
 * in a composable is one deeplink away from being bypassed.
 *
 * The four refusals, in the order they are checked:
 *
 *  1. **Never measured.** `ABSENT` is what a stored row backfills to, and what an adapter arriving
 *     by import or by an archive restore carries. Silence is not a pass — an unmeasured adapter is
 *     refused exactly like a measured-and-blocked one, because the thing at stake is whether the
 *     model still responds to insulin, and nobody has looked.
 *  2. **Measured and blocked.** The adapter collapsed, amplified or reversed the model's marginal
 *     dose response. `why` carries the numbers.
 *  3. **Inconclusive.** Too few held-out forecast windows, or a frozen model with no response to
 *     preserve. Refused for the same reason as (1): the guard could not answer.
 *  4. **Fitted on a history that has since been rewritten.** The windows it learnt from describe a
 *     record that no longer exists. Only for an adapter fitted HERE: [fittedAtMs] is zero on one
 *     that arrived by import or by an archive restore, which was never fitted on this phone's
 *     history at all, and reading that zero as an ancient fit instant made the first log edit brick
 *     it permanently — refused by a rule the override deliberately cannot clear.
 *
 * [overrideAtMs] clears the first three, and only for the row it is stored on: a re-fit makes a
 * fresh row with no override, so an override can never outlive the adapter it was granted for. It
 * does NOT clear (4), because a history edit after the override is new information.
 */
fun loraAttachRefusal(
    verdict: LoraGuardVerdict,
    overrideAtMs: Long?,
    historyMutatedAtMs: Long?,
    fittedAtMs: Long,
    why: String = "",
): String? {
    // Checked first and never overridable: an edit that lands AFTER an override is information the
    // override could not have taken into account. `fittedAtMs == 0` is not an ancient fit — it is
    // an adapter that was never fitted on this record, and this rule has nothing to say about one.
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
