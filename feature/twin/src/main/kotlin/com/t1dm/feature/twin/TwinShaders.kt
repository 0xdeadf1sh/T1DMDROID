package com.t1dm.feature.twin

internal const val FULLSCREEN_VS = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.999, 1.0);
}
"""

internal const val BACKDROP_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform vec3 uC1;
uniform vec3 uBg;
out vec4 o;
void main() {
    float glow = exp(-pow((vUv.y - 0.32) * 4.0, 2.0)) * 0.05;
    vec3 c = uBg + uC1 * glow;
    float v = length(vUv - vec2(0.5, 0.45));
    o = vec4(c * (1.0 - v * 0.7), 1.0);
}
"""

internal const val FLOOR_VS = """#version 300 es
uniform mat4 uMvp;
uniform float uY;
uniform float uSize;
out vec3 vW;
void main() {
    vec2 c = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1) * 2.0 - 1.0;
    vW = vec3(c.x * uSize, uY, c.y * uSize);
    gl_Position = uMvp * vec4(vW, 1.0);
}
"""

internal const val FLOOR_FS = """#version 300 es
precision highp float;
in vec3 vW;
uniform float uT;
uniform vec3 uC1;
uniform vec3 uC2;
out vec4 o;
void main() {
    vec2 p = vW.xz / 2.0;
    vec2 g = abs(fract(p - 0.5) - 0.5) / fwidth(p);
    float line = 1.0 - min(min(g.x, g.y), 1.0);
    float d = length(vW.xz);
    float fade = exp(-d * 0.055);
    float ring = exp(-pow(d - mod(uT * 5.0, 50.0), 2.0) * 0.1);
    float disc = exp(-d * d * 0.06);
    vec3 c = uC1 * (line * 0.5 + disc * 0.35) * fade + uC2 * ring * (0.25 + line) * 0.4 * fade;
    float a = clamp((line * 0.8 + ring * 0.4 + disc * 0.4 + 0.08) * fade, 0.0, 1.0);
    o = vec4(c, a);
}
"""

internal const val BODY_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aBary;
layout(location = 2) in vec2 aUv;
uniform mat4 uMvp;
out vec3 vW;
out vec3 vB;
out vec2 vUv;
void main() {
    vW = aPos;
    vB = aBary;
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

internal const val BODY_FS = """#version 300 es
precision highp float;
in vec3 vW;
in vec3 vB;
in vec2 vUv;
uniform vec3 uEye;
uniform float uT;
uniform float uReveal;
uniform vec3 uC1;
uniform vec3 uC2;
uniform sampler2D uFace;
uniform float uFaceOn;
out vec4 o;
void main() {
    if (vW.y > uReveal) discard;
    vec3 n = normalize(cross(dFdx(vW), dFdy(vW)));
    vec3 v = normalize(uEye - vW);
    if (dot(n, v) < 0.0) n = -n;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 2.5);
    float diff = 0.3 + 0.7 * max(dot(n, normalize(vec3(0.4, 0.8, 0.5))), 0.0);
    float w = min(min(vB.x, vB.y), vB.z);
    float edge = 1.0 - smoothstep(0.0, fwidth(w) * 1.2, w);
    float flick = 0.94 + 0.06 * sin(uT * 37.0 + vW.y * 2.0);
    float cut = exp(-pow((uReveal - vW.y) * 3.0, 2.0));
    vec3 c = uC1 * (0.05 + 0.13 * diff) + uC1 * rim * 0.75 + uC1 * edge * 0.5;
    // Premultiplied texels; UVs off the face clamp to the texture's transparent border.
    vec4 f = texture(uFace, vec2(vUv.x, 1.0 - vUv.y)) * uFaceOn;
    float lum = dot(f.rgb, vec3(0.299, 0.587, 0.114)) / max(f.a, 1e-3);
    vec3 tint = uC1 * (0.08 + 1.1 * lum * lum) * (0.7 + 0.3 * diff);
    c = mix(c, tint + uC1 * edge * 0.35, f.a * 0.85);
    c += vec3(1.0) * cut * 0.7;
    o = vec4(c * flick, 1.0);
}
"""

internal const val PARTICLE_VS = """#version 300 es
uniform mat4 uMvp;
uniform float uT;
uniform float uMinY;
uniform float uMaxY;
uniform float uPx;
out float vA;
float h(float n) { return fract(sin(n * 12.9898) * 43758.5453); }
void main() {
    float id = float(gl_VertexID) * 7.0;
    float r = 3.0 + h(id) * 12.0;
    float a = h(id + 1.0) * 6.2831 + uT * 0.06 * (h(id + 2.0) - 0.5);
    float span = uMaxY - uMinY + 8.0;
    float y = uMinY - 1.0 + mod(h(id + 3.0) * span + uT * (0.3 + h(id + 4.0)), span);
    vec4 p = uMvp * vec4(cos(a) * r, y, sin(a) * r, 1.0);
    gl_Position = p;
    gl_PointSize = clamp((1.0 + h(id + 5.0) * 2.5) * uPx / p.w, 1.0, 10.0);
    vA = 0.3 + 0.7 * h(id + 6.0);
}
"""

internal const val PARTICLE_FS = """#version 300 es
precision mediump float;
in float vA;
uniform vec3 uC1;
out vec4 o;
void main() {
    vec2 q = gl_PointCoord * 2.0 - 1.0;
    float d = dot(q, q);
    if (d > 1.0) discard;
    o = vec4(uC1, (1.0 - d) * vA * 0.7);
}
"""
