package com.t1dm.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.statusWord

private const val CALM_MS = 2600
private const val URGENT_MS = 700

/** Deeper trough for the urgent cadence, so the two read apart with the colour ignored. */
private const val CALM_FLOOR = 0.8f
private const val URGENT_FLOOR = 0.35f

/** State by colour and cadence; [statusWord] survives as the content description. */
@Composable
fun CgmStatusIcon(status: CgmSourceStatus, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val animationsOn = LocalAnimationsEnabled.current
    val palette = LocalT1dmSemantics.current
    val cs = MaterialTheme.colorScheme
    val tint = when (status) {
        CgmSourceStatus.Live -> palette.inRange
        CgmSourceStatus.Warmup -> palette.high
        CgmSourceStatus.Scanning -> cs.primary
        CgmSourceStatus.SignalLost, CgmSourceStatus.Faulted -> cs.error
        CgmSourceStatus.Idle -> cs.onSurfaceVariant
    }
    // A fault is a settled verdict, not a search; it holds still so the searching states stand out.
    val periodMs = when (status) {
        CgmSourceStatus.Live, CgmSourceStatus.Warmup -> CALM_MS
        CgmSourceStatus.Scanning, CgmSourceStatus.SignalLost -> URGENT_MS
        CgmSourceStatus.Faulted, CgmSourceStatus.Idle -> 0
    }
    val floor = if (periodMs == URGENT_MS) URGENT_FLOOR else CALM_FLOOR
    val icon = remember(palette.id) { navIcon("cgm", iconStyleForTheme(palette.id)) }
    // HELD not unwrapped: reading .value here would recompose the bar every frame; see Pulse.kt.
    val alpha: State<Float> = if (animationsOn && periodMs > 0) {
        val transition = rememberInfiniteTransition(label = "cgmStatus")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = floor,
            animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
            label = "cgmStatusAlpha",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    Icon(
        imageVector = icon,
        contentDescription = statusWord(status),
        tint = tint,
        modifier = modifier.size(size).graphicsLayer { this.alpha = alpha.value },
    )
}
