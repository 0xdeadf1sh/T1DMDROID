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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * N4a — a per-theme morning/noon/evening/night glyph derived from the ACTUAL local time, in the same
 * geometry system as the nav icons and subtly animated.
 *
 * It lives in `:core:design` because the BG read-out it sits beside is now the app's bottom bar
 * (`:app`), not the dashboard panel it was written for.
 */
@Composable
fun TimeOfDayIcon(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val animationsOn = LocalAnimationsEnabled.current
    // Re-evaluate the period once a minute so it stays honest without a busy loop.
    val hour by produceState(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        while (true) {
            value = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            delay(60_000)
        }
    }
    val style = iconStyleForTheme(LocalT1dmSemantics.current.id)
    val period = dayPeriodFor(hour)
    val icon = remember(period, style) { timeOfDayIcon(period, style) }
    // A subtle breath; a static 1f when motion is disabled (N4c). Held as State and unwrapped inside
    // the layer block — a 2.6 s breath that never ends must invalidate a layer property, not the
    // composition that produced it.
    val scale: State<Float> = if (animationsOn) {
        val transition = rememberInfiniteTransition(label = "tod")
        transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
            label = "todScale",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    Icon(
        imageVector = icon,
        contentDescription = "Time of day: ${period.name.lowercase()}",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
    )
}
