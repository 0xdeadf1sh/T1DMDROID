package com.t1dm.ui.graph

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One frame for every BG-panel drawer. Drive mode shares it; never transcribe it per call site. */
object GraphInsets {
    val Left: Dp = 46.dp
    val Right: Dp = 12.dp

    /** Two label rows: time ticks, then dates. */
    val Bottom: Dp = 32.dp

    val ModelAxis: Dp = 14.dp

    private val TopBase: Dp = 10.dp

    fun top(hasModelClock: Boolean): Dp = if (hasModelClock) TopBase + ModelAxis else TopBase
}
