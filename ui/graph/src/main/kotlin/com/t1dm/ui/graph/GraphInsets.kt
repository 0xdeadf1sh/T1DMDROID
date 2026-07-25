package com.t1dm.ui.graph

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one frame every drawer of the BG panel measures from: the chart itself, drive mode's furniture,
 * and the tap-to-place hint's x→time map. Drive mode is a MODE of the panel, not a screen of its own —
 * the axes and grid must land on the same pixels they occupied a chip-tap earlier — so a frame
 * transcribed per call site would drift the plot under the user at the handover.
 */
object GraphInsets {
    val Left: Dp = 46.dp
    val Right: Dp = 12.dp
    val Bottom: Dp = 20.dp

    /** The strip the model's predicted-clock axis occupies above the plot. */
    val ModelAxis: Dp = 14.dp

    private val TopBase: Dp = 10.dp

    fun top(hasModelClock: Boolean): Dp = if (hasModelClock) TopBase + ModelAxis else TopBase
}
