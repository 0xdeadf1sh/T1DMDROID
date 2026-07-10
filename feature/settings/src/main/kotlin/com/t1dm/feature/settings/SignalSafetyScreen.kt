package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Signal & freshness (Phase 7C item 14; §3.6-A/D). Two safety windows:
 *  - the **loss-of-signal** window (minutes with no MEASURED reading before the model-free alarm
 *    fires) and its **escalated** shortening when the last real reading was low or falling;
 *  - the **dosing staleness gate**: the calculator refuses to recommend a dose off an anchor older
 *    than this many minutes (§3.6-D freshness rail).
 *
 * All are user-set; the loss windows have a 1-minute floor only. Pure/stateless.
 */
@Composable
fun SignalSafetyScreen(
    lossMin: Int,
    lossEscalatedMin: Int,
    dosingStaleMin: Int,
    onSetLoss: (lossMin: Int, escalatedMin: Int) -> Unit,
    onSetDosingStale: (min: Int) -> Unit,
) {
    SettingsScaffold("Signal & freshness") {
        SettingsSectionHeader("Loss of signal")
        SettingsNote(
            "How long the app waits with no measured reading before raising a loss-of-signal alarm. " +
                "The escalated window is used when the last real reading was low or falling.",
        )
        IntStepper("Loss-of-signal window", lossMin, "min", step = 1, min = 1) { onSetLoss(it, lossEscalatedMin) }
        IntStepper("Escalated window", lossEscalatedMin, "min", step = 1, min = 1) { onSetLoss(lossMin, it) }

        SettingsSectionHeader("Dosing freshness gate")
        DangerBanner(
            "The dose calculator refuses to recommend anything when the last measured reading is " +
                "older than this. Raising it lets the calculator act on staler data — a safety trade-off.",
        )
        IntStepper("Refuse dosing if anchor older than", dosingStaleMin, "min", step = 1, min = 1) { onSetDosingStale(it) }
    }
}
