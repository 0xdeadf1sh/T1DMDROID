package com.t1dm.core.design

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/** Root-space px: top bar's bottom edge, bottom bar's top edge; the sweep runs outside them. */
@Stable
class BackdropBars {
    var topEdgePx by mutableFloatStateOf(0f)
    var bottomEdgePx by mutableFloatStateOf(Float.MAX_VALUE)
}

@RequiresApi(33)
@Composable
internal fun PolyphemusShaderBackdrop(
    p: T1dmPalette,
    alpha: Float,
    bars: BackdropBars?,
    modifier: Modifier = Modifier,
) {
    val shader = remember { RuntimeShader(POLYPHEMUS_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    // ~30 fps: a backdrop needs no more, and it runs behind every screen.
    val clock by produceState(0f) {
        val start = System.nanoTime()
        while (true) {
            value = ((System.nanoTime() - start) / 1e9f) % CLOCK_WRAP_S
            delay(FRAME_MS)
        }
    }
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("t", clock)
        shader.setColorUniform("bg", p.background.toArgb())
        shader.setColorUniform("c1", p.primary.toArgb())
        shader.setColorUniform("c2", p.secondary.toArgb())
        shader.setColorUniform("gridc", p.grid.toArgb())
        shader.setFloatUniform("baseAlpha", alpha)
        shader.setFloatUniform("topEdge", bars?.topEdgePx ?: 0f)
        shader.setFloatUniform("bottomEdge", (bars?.bottomEdgePx ?: size.height).coerceAtMost(size.height))
        drawRect(brush)
    }
}

/** The shader's still frame, for API 31–32, animations off, and the widget. */
internal fun DrawScope.drawPolyphemusBackground(p: T1dmPalette) {
    val w = size.width
    val h = size.height
    drawRect(p.background, size = size)

    val r = w / HEXES_ACROSS / sqrt(3f)
    val dx = r * sqrt(3f)
    val dy = r * 1.5f
    val hex = Path()
    var row = 0
    var y = 0f
    while (y < h + r) {
        var x = if (row % 2 == 0) 0f else dx / 2f
        while (x < w + dx) {
            hexagon(hex, x, y, r)
            x += dx
        }
        y += dy
        row++
    }
    drawPath(hex, p.grid.copy(alpha = 0.55f), style = Stroke(width = 1.2f))

    drawCircle(
        Brush.radialGradient(listOf(p.secondary.copy(alpha = 0.10f), p.secondary.copy(alpha = 0f)), Offset(w, 0f), w),
        radius = w, center = Offset(w, 0f),
    )
    drawCircle(
        Brush.radialGradient(listOf(p.primary.copy(alpha = 0.08f), p.primary.copy(alpha = 0f)), Offset(0f, h), w),
        radius = w, center = Offset(0f, h),
    )

    val dark = p.background.copy(alpha = 0.35f)
    var sy = 0f
    while (sy < h) {
        drawRect(dark, Offset(0f, sy), Size(w, 1f))
        sy += 3f
    }
}

private fun hexagon(path: Path, cx: Float, cy: Float, r: Float) {
    val h = r * sqrt(3f) / 2f
    path.moveTo(cx, cy - r)
    path.lineTo(cx + h, cy - r / 2f)
    path.lineTo(cx + h, cy + r / 2f)
    path.lineTo(cx, cy + r)
    path.lineTo(cx - h, cy + r / 2f)
    path.lineTo(cx - h, cy - r / 2f)
    path.close()
}

private const val HEXES_ACROSS = 9f
private const val FRAME_MS = 33L
/** A whole number of 6 s wave cycles, so the wrap never makes the wave jump. */
private const val CLOCK_WRAP_S = 1002f

private const val POLYPHEMUS_AGSL = """
uniform float2 res;
uniform float t;
uniform float baseAlpha;
uniform float topEdge;
uniform float bottomEdge;
layout(color) uniform half4 bg;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 gridc;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

float hexEdge(float2 p) {
    const float2 s = float2(1.0, 1.7320508);
    float2 a = mod(p, s) - s * 0.5;
    float2 b = mod(p - s * 0.5, s) - s * 0.5;
    float2 g = dot(a, a) < dot(b, b) ? a : b;
    float2 q = abs(g);
    return 0.5 - max(dot(q, s * 0.5), q.x);
}

half4 main(float2 fc) {
    float2 uv = fc / res;
    float slice = floor(uv.y * 40.0);
    float glitch = step(0.985, hash(float2(slice, floor(t * 6.0))))
                 * step(0.7, hash(float2(floor(t * 0.5), 3.0)));
    fc.x += glitch * (hash(float2(slice, t)) - 0.5) * 40.0;

    float e = hexEdge(fc / (res.x / 9.0) + float2(0.0, t * 0.03));
    float edge = 1.0 - smoothstep(0.0, 0.035, e);
    float3 col = mix(float3(bg.rgb), float3(gridc.rgb), edge * 0.55);

    float breath = 0.5 + 0.5 * sin(t * 0.6);
    col += float3(c2.rgb) * 0.10 * breath * smoothstep(0.9, 0.0, distance(uv, float2(1.0, 0.0)));
    col += float3(c1.rgb) * 0.08 * (1.0 - breath) * smoothstep(0.9, 0.0, distance(uv, float2(0.0, 1.0)));

    col *= 0.86 + 0.14 * step(1.5, mod(fc.y, 3.0));
    col += (hash(fc + fract(t)) - 0.5) * 0.035;
    float v = length(uv - 0.5) * 1.25;
    col *= 1.0 - 0.45 * v * v;

    // A wave of lit hex edges, only behind the bars; past the user's backdrop alpha, premultiplied.
    float y = fc.y;
    float inTop = 1.0 - smoothstep(topEdge - 24.0, topEdge + 8.0, y);
    float inBottom = smoothstep(bottomEdge - 8.0, bottomEdge + 24.0, y);
    float sTop = y / max(topEdge, 1.0);
    float sBottom = (y - bottomEdge) / max(res.y - bottomEdge, 1.0);
    // 4 s sweep, then 2 s off-strip; ±0.7 past the strip leaves the window under 2 %.
    float sweep = fract(t / 6.0) * 1.5;
    float pos = sweep < 1.0 ? sweep * 2.4 - 0.7 : 9.0;
    float dTop = sTop - pos;
    float dBottom = sBottom - pos;
    float wave = inTop * exp(-dTop * dTop * 9.0) + inBottom * exp(-dBottom * dBottom * 9.0);
    float bandA = clamp(wave * (edge * 0.16 + 0.0075), 0.0, 1.0);
    float a = baseAlpha + bandA * (1.0 - baseAlpha);
    float3 outCol = col * baseAlpha * (1.0 - bandA) + float3(c1.rgb) * bandA;
    return half4(half3(outCol), half(a));
}
"""
