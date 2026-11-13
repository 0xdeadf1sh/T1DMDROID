package com.t1dm.feature.twin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.LocalAnimationsEnabled
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

internal enum class TutorialTarget { BODY, MOCK, EDIT }

/** What the tutorial watches: gesture totals from the twin, slider commits, target bounds. */
@Stable
internal class TutorialState {
    var orbitRad by mutableFloatStateOf(0f)
    var zoomLog by mutableFloatStateOf(0f)
    var slides by mutableIntStateOf(0)
    val targets = mutableStateMapOf<TutorialTarget, Rect>()
}

internal fun Modifier.tutorialTarget(state: TutorialState, target: TutorialTarget): Modifier =
    onGloballyPositioned { state.targets[target] = it.boundsInRoot() }

private enum class Step(val text: String, val target: TutorialTarget?, val hint: Hint) {
    ORBIT("Drag the twin to turn it", TutorialTarget.BODY, Hint.SWIPE),
    ZOOM("Pinch to zoom on the face", TutorialTarget.BODY, Hint.PINCH),
    SAMPLE("Tap Mock for a sample twin", TutorialTarget.MOCK, Hint.TAP),
    OPEN_EDIT("Tap Edit", TutorialTarget.EDIT, Hint.TAP),
    SLIDE("Drag the twin up or down · it reshapes", TutorialTarget.BODY, Hint.DRAG_V),
    FINISH("Scan face reads a new twin · Trends charts each scan", null, Hint.NONE),
}

private enum class Hint { SWIPE, DRAG_V, PINCH, TAP, NONE }

private const val ORBIT_GOAL_RAD = 1.5f
private const val ZOOM_GOAL_LOG = 0.6f

/** Spotlights one control at a time and advances only once the user has done the step. */
@Composable
internal fun TwinTutorial(state: TutorialState, hasScan: Boolean, editing: Boolean, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val animate = LocalAnimationsEnabled.current
    val steps = remember {
        Step.entries.filter { it != Step.SAMPLE || !hasScan }
    }
    var index by remember { mutableIntStateOf(0) }
    val step = steps[index]
    val base = remember(step) { floatArrayOf(state.orbitRad, state.zoomLog, state.slides.toFloat()) }
    val progress = when (step) {
        Step.ORBIT -> (state.orbitRad - base[0]) / ORBIT_GOAL_RAD
        Step.ZOOM -> (state.zoomLog - base[1]) / ZOOM_GOAL_LOG
        Step.SAMPLE -> if (hasScan) 1f else 0f
        Step.OPEN_EDIT -> if (editing) 1f else 0f
        Step.SLIDE -> if (state.slides > base[2]) 1f else 0f
        Step.FINISH -> 0f
    }.coerceIn(0f, 1f)
    val complete = progress >= 1f
    LaunchedEffect(step, complete) {
        if (!complete) return@LaunchedEffect
        delay(if (animate) 650 else 0)
        if (index < steps.lastIndex) index++
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = step.target?.let { state.targets[it] }?.translate(-origin)
    val pad = with(LocalDensity.current) { 6.dp.toPx() }
    val hole = target?.let { if (step.target == TutorialTarget.BODY) it.deflate(pad * 4) else it.inflate(pad) }
    val shown by animateValueAsState(hole ?: Rect.Zero, Rect.VectorConverter, label = "hole")
    val holeAlpha by animateFloatAsState(if (hole != null) 1f else 0f, label = "holeAlpha")
    val pulse = rememberInfiniteTransition(label = "hint")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val phase = if (animate) t else 0.25f
    val scrim = cs.background.copy(alpha = 0.78f)
    val ring = cs.primary
    val accent = cs.secondary

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(scrim)
            if (holeAlpha <= 0f) return@Canvas
            val corner = CornerRadius(10.dp.toPx())
            drawRoundRect(Color.Black, shown.topLeft, shown.size, corner, alpha = holeAlpha, blendMode = BlendMode.Clear)
            val glow = if (complete) accent else ring
            drawRoundRect(glow.copy(alpha = holeAlpha * (0.55f + 0.45f * sin(phase * 2 * PI.toFloat()).let { it * it })),
                shown.topLeft, shown.size, corner, style = Stroke(1.5.dp.toPx()))
            if (!complete) drawHint(step.hint, shown, phase, ring)
        }
        val cardAtTop = step.target != null && step.target != TutorialTarget.BODY
        TutorialCard(
            step = step,
            number = index + 1,
            count = steps.size,
            progress = progress,
            complete = complete,
            onSkip = onDone,
            onDone = onDone,
            modifier = Modifier.align(if (cardAtTop) Alignment.TopCenter else Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TutorialCard(
    step: Step,
    number: Int,
    count: Int,
    progress: Float,
    complete: Boolean,
    onSkip: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shown by animateFloatAsState(progress, label = "progress")
    Column(
        modifier
            .padding(16.dp)
            .fillMaxWidth()
            .border(1.dp, cs.primary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .background(cs.surface.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { s ->
            Column {
                Text(
                    "$number/$count" + if (complete) " · DONE" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.secondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(s.text, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            }
        }
        if (step == Step.ORBIT || step == Step.ZOOM) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth(),
                color = if (complete) cs.secondary else cs.primary,
                trackColor = cs.onSurfaceVariant.copy(alpha = 0.2f),
                drawStopIndicator = {},
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (step == Step.FINISH) {
                TextButton(onClick = onDone) { Text("Done") }
            } else {
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
    }
}

/** A ghost finger showing the gesture inside [r]; [p] runs 0 → 1 per loop. */
private fun DrawScope.drawHint(hint: Hint, r: Rect, p: Float, color: Color) {
    val dot = 13.dp.toPx()
    fun finger(c: Offset, a: Float) {
        drawCircle(color.copy(alpha = 0.28f * a), dot, c)
        drawCircle(color.copy(alpha = 0.8f * a), dot, c, style = Stroke(1.5.dp.toPx()))
    }
    val wave = sin(p * 2 * PI.toFloat())
    when (hint) {
        Hint.SWIPE -> {
            val c = Offset(r.center.x, r.top + r.height * 0.55f)
            val span = r.width * 0.22f
            for (k in 3 downTo 1) finger(Offset(c.x + span * sin((p - k * 0.03f) * 2 * PI.toFloat()), c.y), 0.25f / k)
            finger(Offset(c.x + span * wave, c.y), 1f)
        }
        Hint.DRAG_V -> {
            val c = Offset(r.center.x, r.center.y)
            val span = r.height * 0.15f
            for (k in 3 downTo 1) finger(Offset(c.x, c.y + span * sin((p - k * 0.03f) * 2 * PI.toFloat())), 0.25f / k)
            finger(Offset(c.x, c.y + span * wave), 1f)
        }
        Hint.PINCH -> {
            val c = Offset(r.center.x, r.top + r.height * 0.2f)
            val d = (18f + 34f * (0.5f + 0.5f * wave)).dp.toPx() * 0.7f
            finger(Offset(c.x - d, c.y + d), 1f)
            finger(Offset(c.x + d, c.y - d), 1f)
        }
        Hint.TAP -> {
            val c = r.center
            drawCircle(color.copy(alpha = (1f - p) * 0.7f), dot + p * 22.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
            finger(c, 0.6f + 0.4f * (1f - p))
        }
        Hint.NONE -> Unit
    }
}
