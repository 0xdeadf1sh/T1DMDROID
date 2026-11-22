package com.t1dm.core.design

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay

/** Seconds on the shared backdrop clock; wraps on a whole number of 6 s cycles. */
@Composable
internal fun rememberBackdropClock(): State<Float> = produceState(0f) {
    val start = System.nanoTime()
    while (true) {
        value = ((System.nanoTime() - start) / 1e9f) % CLOCK_WRAP_S
        delay(FRAME_MS)
    }
}

/** Paints [effect], an AGSL `effect()` from [BAR_SWEEP_EFFECTS], only behind the bars. */
@RequiresApi(33)
@Composable
internal fun BarSweepOverlay(
    p: T1dmPalette,
    bars: BackdropBars?,
    effect: String,
    modifier: Modifier = Modifier,
) {
    // A shader that fails to compile must leave the still backdrop, not crash the app.
    val shader = remember(effect) {
        runCatching { RuntimeShader(BAR_SWEEP_PRELUDE + effect + BAR_SWEEP_MAIN) }.getOrNull()
    } ?: return
    val brush = remember(shader) { ShaderBrush(shader) }
    val clock by rememberBackdropClock()
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("t", clock)
        shader.setColorUniform("bg", p.background.toArgb())
        shader.setColorUniform("c1", p.primary.toArgb())
        shader.setColorUniform("c2", p.secondary.toArgb())
        shader.setColorUniform("ink", p.ink.toArgb())
        shader.setFloatUniform("topEdge", bars?.topEdgePx ?: 0f)
        shader.setFloatUniform("bottomEdge", (bars?.bottomEdgePx ?: size.height).coerceAtMost(size.height))
        drawRect(brush)
    }
}

/** Themes with a bar-strip effect; the rest keep their still backdrop. */
internal val BAR_SWEEP_EFFECTS: Map<String, String> = mapOf(
    ThemeIds.TRON to TRON_EFFECT,
    ThemeIds.UMBRELLA to UMBRELLA_EFFECT,
    ThemeIds.HELLO_KITTY to HELLO_KITTY_EFFECT,
    ThemeIds.MATRIX to MATRIX_EFFECT,
)

private const val BAR_SWEEP_PRELUDE = """
uniform float2 res;
uniform float t;
uniform float topEdge;
uniform float bottomEdge;
layout(color) uniform half4 bg;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 ink;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

// 4 s across, then 2 s off-strip; every effect period divides the 6 s clock wrap.
float sweepPos() {
    float s = fract(t / 6.0) * 1.5;
    return s < 1.0 ? s * 2.4 - 0.7 : 9.0;
}
"""

// effect(): lp is the pixel in its strip, sz the strip; returns colour in rgb, strength in a.
private const val BAR_SWEEP_MAIN = """
half4 main(float2 fc) {
    float inTop = 1.0 - smoothstep(topEdge - 24.0, topEdge + 8.0, fc.y);
    float inBottom = smoothstep(bottomEdge - 8.0, bottomEdge + 24.0, fc.y);
    float m = max(inTop, inBottom);
    if (m <= 0.0) return half4(0.0);
    bool top = inTop >= inBottom;
    float2 sz = top ? float2(res.x, max(topEdge, 1.0)) : float2(res.x, max(res.y - bottomEdge, 1.0));
    float2 lp = top ? fc : float2(fc.x, fc.y - bottomEdge);
    float4 e = effect(lp, sz);
    float a = clamp(e.a * m, 0.0, 1.0);
    return half4(half3(e.rgb * a), half(a));
}
"""

/** Five neon light-cycle lanes; each has its own colour, speed and start delay, so they stagger. */
private const val TRON_EFFECT = """
float4 lane(float2 lp, float2 sz, float yf, float3 col, float speed, float delay) {
    float s = fract(t / 6.0 * speed - delay) * 1.5;
    float pos = s < 1.0 ? s * 2.4 - 0.7 : 9.0;
    float d = pos * sz.x - lp.x;
    float trail = d > 0.0 ? exp(-d / (sz.x * 0.22)) : exp(-d * d / (sz.x * sz.x * 0.0003));
    float dy = abs(lp.y - sz.y * yf);
    float line = 1.0 - smoothstep(0.0, 1.5, dy);
    float glow = 1.0 - smoothstep(0.0, 6.0, dy);
    float hot = exp(-d * d / (sz.x * sz.x * 0.0008));
    float a = trail * (line * 0.5 + glow * 0.07);
    return float4(mix(col, float3(1.0), hot * 0.6), a);
}

float4 over(float4 a, float4 b) {
    float o = a.a + b.a * (1.0 - a.a);
    return float4(o > 0.0 ? (a.rgb * a.a + b.rgb * b.a * (1.0 - a.a)) / o : float3(0.0), o);
}

float4 effect(float2 lp, float2 sz) {
    float4 r = lane(lp, sz, 0.14, float3(c1.rgb), 1.0, 0.00);
    r = over(r, lane(lp, sz, 0.32, float3(1.0, 0.45, 0.05), 2.0, 0.30));
    r = over(r, lane(lp, sz, 0.50, float3(1.0, 0.10, 0.75), 1.0, 0.45));
    r = over(r, lane(lp, sz, 0.68, float3(c2.rgb), 3.0, 0.15));
    r = over(r, lane(lp, sz, 0.86, float3(1.0, 0.92, 0.15), 2.0, 0.65));
    return r;
}
"""

/** A double helix repeating along the strip, scrolling sideways, rungs between the strands. */
private const val UMBRELLA_EFFECT = """
float4 effect(float2 lp, float2 sz) {
    float lam = 120.0;
    float u = lp.x / lam * 6.2831853 - t * 2.0943951;
    float amp = sz.y * 0.34;
    float cy = sz.y * 0.5;
    float s = sin(u);
    float slope = amp * cos(u) * 6.2831853 / lam;
    float norm = 1.0 / sqrt(1.0 + slope * slope);
    float d1 = abs(lp.y - (cy + amp * s)) * norm;
    float d2 = abs(lp.y - (cy - amp * s)) * norm;
    float depth = cos(u);
    float a1 = (1.0 - smoothstep(0.0, 1.8, d1)) * (0.65 + 0.35 * depth);
    float a2 = (1.0 - smoothstep(0.0, 1.8, d2)) * (0.65 - 0.35 * depth);
    float r = fract(u / 6.2831853 * 8.0) - 0.5;
    float inside = step(abs(lp.y - cy), amp * abs(s));
    float rung = (1.0 - smoothstep(0.0, 1.2, abs(r) * lam / 8.0)) * inside * 0.45;
    float3 col = (float3(c1.rgb) * a1 + float3(ink.rgb) * a2 + float3(ink.rgb) * rung) / max(a1 + a2 + rung, 0.001);
    return float4(col, clamp(a1 + a2 + rung, 0.0, 1.0) * 0.4);
}
"""

/** Twinkling four-point sparkles drifting right on a 36 px lattice. */
private const val HELLO_KITTY_EFFECT = """
float4 effect(float2 lp, float2 sz) {
    float2 q = float2(lp.x - t * 12.0, lp.y) / 36.0;
    float2 cid = floor(q);
    float2 f = fract(q) - 0.5;
    float h = hash(cid);
    float live = step(0.62, h);
    float2 jit = float2(hash(cid + 3.1), hash(cid + 7.7)) * 0.4 - 0.2;
    float2 g = abs(f - jit);
    float body = 1.0 - smoothstep(0.0, 0.34, g.x + g.y);
    float arms = (1.0 - smoothstep(0.0, 0.05, min(g.x, g.y))) * (1.0 - smoothstep(0.0, 0.30, max(g.x, g.y)));
    float tw = max(0.0, sin(6.2831853 * (t / 2.0 + h * 5.0)));
    tw = tw * tw * tw;
    float a = live * tw * clamp(body * 0.7 + arms, 0.0, 1.0);
    return float4(mix(float3(c1.rgb), float3(c2.rgb), hash(cid + 1.3)), a * 0.45);
}
"""

/** Columns of small dots fall at three speeds; the head is the brightest dot. */
private const val MATRIX_EFFECT = """
float4 effect(float2 lp, float2 sz) {
    float cell = 8.0;
    float col = floor(lp.x / cell);
    float h = hash(float2(col, 1.0));
    float live = step(0.55, hash(float2(col, 2.0)));
    float k = 1.0 + floor(h * 3.0);
    float head = fract(t * k / 3.0 + hash(float2(col, 3.0)));
    float dy = head - lp.y / sz.y;
    dy = dy < 0.0 ? dy + 1.0 : dy;
    float trail = exp(-dy * 5.0) * step(dy, 0.85);
    float cellY = floor(lp.y / cell);
    float glyph = step(0.3, hash(float2(col * 7.0 + cellY, floor(t * 6.0))));
    float2 f = (fract(lp / cell) - 0.5) * cell;
    float pip = 1.0 - smoothstep(0.8, 1.5, length(f));
    float headGlow = 1.0 - smoothstep(0.0, 0.06, dy);
    return float4(mix(float3(c1.rgb), float3(1.0), headGlow * 0.8), live * trail * glyph * pip * 0.4);
}
"""
