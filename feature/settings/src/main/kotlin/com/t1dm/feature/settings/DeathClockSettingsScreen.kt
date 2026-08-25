package com.t1dm.feature.settings

import androidx.compose.runtime.Composable

/** Hours, each measured from the preceding landmark. Display-only: no §3.6 rail or gate reads them. */
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
