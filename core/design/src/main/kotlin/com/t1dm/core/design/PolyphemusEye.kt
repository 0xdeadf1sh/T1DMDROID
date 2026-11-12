package com.t1dm.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** [distressed]: no usable reading; the eye holds a pulsing RGB split. */
@Composable
fun PolyphemusEye(size: Dp, distressed: Boolean = false, modifier: Modifier = Modifier) {
    val paths = rememberEyePaths()
    val line = MaterialTheme.colorScheme.primary
    val ghost = MaterialTheme.colorScheme.secondary
    val clock: State<Float> = if (LocalAnimationsEnabled.current) {
        produceState(0f) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { value = (it - start) / 1e9f }
        }
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Canvas(modifier.size(size).semantics { contentDescription = "Polyphemus" }) {
        val t = clock.value
        val unit = this.size.minDimension / GRID
        scale(unit, unit, pivot = Offset.Zero) {
            drawEye(paths, t, line, ghost, distressed)
        }
    }
}

private class EyePaths(val brackets: Path, val lid: Path, val scan: Path, val iris: Path)

@Composable
private fun rememberEyePaths(): EyePaths {
    val b = stringResource(R.string.eye_brackets)
    val l = stringResource(R.string.eye_lid)
    val s = stringResource(R.string.eye_scan)
    val i = stringResource(R.string.eye_iris)
    return remember(b, l, s, i) {
        EyePaths(parse(b), parse(l), parse(s), parse(i).apply { fillType = PathFillType.EvenOdd })
    }
}

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

private fun DrawScope.drawEye(p: EyePaths, t: Float, line: Color, ghost: Color, distressed: Boolean) {
    val breath = 0.5f + 0.5f * sin(t * TAU / BREATH_S)
    val glitch = if (distressed) 0.55f + 0.35f * sin(t * TAU / DISTRESS_S) else glitchAt(t)
    val irisX = gazeAt(t)

    drawPath(p.brackets, line.copy(alpha = 0.55f + 0.45f * breath), style = Stroke(1.4f))
    withTransform({ scale(1f, opennessAt(t), pivot = CENTER) }) {
        drawPath(p.lid, line.copy(alpha = 0.12f + 0.2f * breath), style = Stroke(4f, join = StrokeJoin.Miter))
        drawPath(p.lid, line, style = Stroke(1.6f, join = StrokeJoin.Miter))
        clipPath(p.lid) {
            val drift = -((t * SCAN_UNITS_PER_S) % SCAN_PITCH)
            translate(top = drift) { drawPath(p.scan, line.copy(alpha = 0.18f)) }
            translate(top = drift + SCAN_PITCH) { drawPath(p.scan, line.copy(alpha = 0.18f)) }
            if (glitch > 0f) {
                translate(left = irisX - 1.4f * glitch) { drawPath(p.iris, ghost.copy(alpha = 0.85f)) }
                translate(left = irisX + 1.4f * glitch) { drawPath(p.iris, line.copy(alpha = 0.6f)) }
            }
            translate(left = irisX) { drawPath(p.iris, line) }
        }
    }
    val sweepY = (t % SWEEP_S) / SWEEP_S * (GRID + 8f) - 4f
    if (sweepY in 0f..GRID) {
        drawLine(line.copy(alpha = 0.5f), Offset(0f, sweepY), Offset(GRID, sweepY), strokeWidth = 0.5f)
    }
}

/** Holds a gaze, then glides to the next over [GLIDE_S], eased at both ends. */
private fun gazeAt(t: Float): Float {
    val i = floor(t / GAZE_HOLD_S).toInt()
    val from = gazeTarget(i - 1)
    val to = gazeTarget(i)
    val k = ((t - i * GAZE_HOLD_S) / GLIDE_S).coerceIn(0f, 1f)
    return from + (to - from) * k * k * (3f - 2f * k)
}

private fun gazeTarget(i: Int): Float = if (i < 0) 0f else (hash(i * 31 + 3) - 0.5f) * 2f * GAZE_RANGE

/** 1 open, ~0.08 shut. Never blinks in the first 2 s, so a static frame is open. */
private fun opennessAt(t: Float): Float {
    val i = floor(t / BLINK_PERIOD_S).toInt()
    val dt = t - (i * BLINK_PERIOD_S + 2f + hash(i * 17 + 1) * 3f)
    return if (dt in 0f..BLINK_S) 1f - 0.92f * sin(PI.toFloat() * dt / BLINK_S) else 1f
}

private fun glitchAt(t: Float): Float {
    val i = floor(t / GLITCH_PERIOD_S).toInt()
    if (hash(i * 13 + 5) < 0.35f) return 0f
    val dt = t - (i * GLITCH_PERIOD_S + 1f + hash(i * 7 + 2) * 3f)
    return if (dt in 0f..GLITCH_S) sin(PI.toFloat() * dt / GLITCH_S) else 0f
}

/** Deterministic in `[0,1)`. */
private fun hash(n: Int): Float {
    var x = n * -0x61c88647
    x = x xor (x ushr 15)
    x *= 0x2c1b3c6d
    x = x xor (x ushr 12)
    return (x ushr 8) / 16_777_216f
}

private const val GRID = 32f
private val CENTER = Offset(16f, 16f)
private const val TAU = (2 * PI).toFloat()
private const val BREATH_S = 3.2f
private const val SCAN_PITCH = 2f
private const val SCAN_UNITS_PER_S = 1.5f
private const val SWEEP_S = 4.5f
private const val GAZE_HOLD_S = 2.4f
private const val GLIDE_S = 0.6f
private const val GAZE_RANGE = 3f
private const val BLINK_PERIOD_S = 7f
private const val BLINK_S = 0.3f
private const val GLITCH_PERIOD_S = 5.3f
private const val GLITCH_S = 0.7f
private const val DISTRESS_S = 2.2f
