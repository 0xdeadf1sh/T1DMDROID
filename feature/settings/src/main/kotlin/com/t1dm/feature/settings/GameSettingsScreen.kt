package com.t1dm.feature.settings

import androidx.compose.runtime.Composable
import com.t1dm.core.model.GamePropDensity

@Composable
fun GameSettingsScreen(
    props: GamePropDensity,
    onSetProps: (GamePropDensity) -> Unit,
) {
    SettingsScaffold(SettingsScreenKey.GAMES) {
        ChipPicker(
            gameProps,
            listOf(GamePropDensity.Sparse to "Sparse", GamePropDensity.Busy to "Busy"),
            props,
        ) { onSetProps(it) }
    }
}

private val gameProps = SettingsKnob(
    id = "games.props",
    screen = SettingsScreenKey.GAMES,
    section = "Scenery",
    label = "Scenery density",
    subtitle = "Trees, clouds, fossils and signs along the trace in Drive and Golf",
    synonyms = listOf(
        "game", "games", "drive", "golf", "scenery", "props", "background", "clouds", "birds", "trees",
        "fossils", "signs", "sparse", "busy", "minigame",
    ),
)

internal val settingsGameKnobs = listOf(gameProps)
