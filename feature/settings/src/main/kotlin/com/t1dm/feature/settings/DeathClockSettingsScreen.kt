package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/**
 * Settings → Death clock (F5, locked decision D2). Tunes the three forward-from-prior-landmark offsets
 * that drive the Circadian screen's insulin-exhaustion countdown: hours from IOB reaching zero to DKA,
 * from DKA to coma, and from coma to death. Each is measured from the PRECEDING landmark, not from now.
 * These figures are a deliberately morbid projection for the personal build — DISPLAY-ONLY: no §3.6 rail
 * or gate ever reads them, so changing them cannot alter any safety behaviour. In hours. Pure/stateless.
 */
@Composable
fun DeathClockSettingsScreen(
    dkaAfterIobZeroH: Double,
    comaAfterDkaH: Double,
    deathAfterComaH: Double,
    onSetDka: (Double) -> Unit,
    onSetComa: (Double) -> Unit,
    onSetDeath: (Double) -> Unit,
) {
    SettingsScaffold("Death clock") {
        SettingsNote(
            "Sets how the insulin-exhaustion countdown on the Circadian screen is projected. Each offset " +
                "is counted from the previous landmark: DKA from when insulin-on-board decays to zero, coma " +
                "from DKA, death from coma. It is an estimate for reflection only and drives nothing in the " +
                "safety system.",
        )
        DoubleStepper("DKA after IOB reaches zero", dkaAfterIobZeroH, "h", step = 0.5, min = 0.0) { onSetDka(it) }
        DoubleStepper("Coma after DKA", comaAfterDkaH, "h", step = 0.5, min = 0.0) { onSetComa(it) }
        DoubleStepper("Death after coma", deathAfterComaH, "h", step = 0.5, min = 0.0) { onSetDeath(it) }
    }
}
