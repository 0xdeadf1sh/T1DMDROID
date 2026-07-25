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
    SettingsScaffold(SettingsScreenKey.DEATH_CLOCK) {
        SettingsNote("Each offset chains from the previous landmark — reflection only")
        DoubleStepper(deathClockDka, dkaAfterIobZeroH, "h", step = 0.5, min = 0.0) { onSetDka(it) }
        DoubleStepper(deathClockComa, comaAfterDkaH, "h", step = 0.5, min = 0.0) { onSetComa(it) }
        DoubleStepper(deathClockDeath, deathAfterComaH, "h", step = 0.5, min = 0.0) { onSetDeath(it) }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private const val DEATH_CLOCK_SECTION = "Death clock"

private val deathClockDka = SettingsKnob(
    id = "death_clock.dka",
    screen = SettingsScreenKey.DEATH_CLOCK,
    section = DEATH_CLOCK_SECTION,
    label = "DKA after IOB reaches zero",
    subtitle = "Hours from IOB-zero to ketoacidosis, on the Circadian countdown",
    synonyms = listOf(
        "dka", "ketoacidosis", "ketones", "death clock", "countdown", "circadian", "projection",
        "iob zero", "insulin exhaustion", "hours", "morbid",
    ),
)

private val deathClockComa = SettingsKnob(
    id = "death_clock.coma",
    screen = SettingsScreenKey.DEATH_CLOCK,
    section = DEATH_CLOCK_SECTION,
    label = "Coma after DKA",
    subtitle = "Hours from ketoacidosis to coma, measured from the preceding landmark",
    synonyms = listOf("coma", "unconscious", "dka", "death clock", "countdown", "circadian", "projection", "hours"),
)

private val deathClockDeath = SettingsKnob(
    id = "death_clock.death",
    screen = SettingsScreenKey.DEATH_CLOCK,
    section = DEATH_CLOCK_SECTION,
    label = "Death after coma",
    subtitle = "Hours from coma to death; display-only, no safety rail reads it",
    synonyms = listOf("death", "die", "dying", "coma", "death clock", "countdown", "circadian", "projection", "hours"),
)

internal val settingsDeathClockKnobs = listOf(deathClockDka, deathClockComa, deathClockDeath)
