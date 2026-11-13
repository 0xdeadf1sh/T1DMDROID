"""Rasterise the launcher eye from core:design's eye_paths.xml into density PNGs.

HyperOS does not draw a vector foreground whose pathData points at string resources.
Run from anywhere: python tools/launcher-icon/render.py
"""

import math
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
PATHS = ROOT / "core/design/src/main/res/values/eye_paths.xml"
RES = ROOT / "app/src/main/res"
SS = 8
# The 32-unit eye sits at +30, x1.5 inside the 108dp adaptive foreground (66dp safe zone).
FG_DP = 108
OFFSET, SCALE = 30.0, 1.5
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_DP = 48


def load_paths():
    return {s.get("name"): s.text for s in ET.parse(PATHS).getroot().iter("string")}


def _arc(p0, rx, ry, phi, large, sweep, p1, n=48):
    """SVG endpoint arc → sampled points (SVG 1.1 appendix F.6.5)."""
    phi = math.radians(phi)
    c, s = math.cos(phi), math.sin(phi)
    dx, dy = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1, y1 = c * dx + s * dy, -s * dx + c * dy
    lam = (x1 / rx) ** 2 + (y1 / ry) ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    k = math.sqrt(max(num, 0) / (rx * rx * y1 * y1 + ry * ry * x1 * x1))
    if large == sweep:
        k = -k
    cx1, cy1 = k * rx * y1 / ry, -k * ry * x1 / rx
    cx = c * cx1 - s * cy1 + (p0[0] + p1[0]) / 2
    cy = s * cx1 + c * cy1 + (p0[1] + p1[1]) / 2
    a0 = math.atan2((y1 - cy1) / ry, (x1 - cx1) / rx)
    da = math.atan2((-y1 - cy1) / ry, (-x1 - cx1) / rx) - a0
    if sweep and da < 0:
        da += 2 * math.pi
    elif not sweep and da > 0:
        da -= 2 * math.pi
    pts = []
    for i in range(1, n + 1):
        a = a0 + da * i / n
        ex, ey = rx * math.cos(a), ry * math.sin(a)
        pts.append((c * ex - s * ey + cx, s * ex + c * ey + cy))
    return pts


def parse(d):
    """Absolute M/L/H/V/A/Z only → list of point lists, one per subpath."""
    toks = re.findall(r"[MLHVAZ]|-?[\d.]+", d)
    subs: list[list[tuple[float, float]]] = []
    cur: list[tuple[float, float]] = []
    i, cmd = 0, None
    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd == "Z":
                subs.append(cur)
                cur = []
                continue
        f = lambda j: float(toks[i + j])
        if cmd == "M":
            if cur:
                subs.append(cur)
            cur = [(f(0), f(1))]
            i += 2
            cmd = "L"
        elif cmd == "L":
            cur.append((f(0), f(1)))
            i += 2
        elif cmd == "H":
            cur.append((f(0), cur[-1][1]))
            i += 1
        elif cmd == "V":
            cur.append((cur[-1][0], f(0)))
            i += 1
        elif cmd == "A":
            cur += _arc(cur[-1], f(0), f(1), f(2), int(f(3)), int(f(4)), (f(5), f(6)))
            i += 7
    if cur:
        subs.append(cur)
    return subs


def _offset(poly, d):
    """Convex polygon grown by d (shrunk if negative), mitred corners."""
    n = len(poly)
    area = sum(poly[i][0] * poly[(i + 1) % n][1] - poly[(i + 1) % n][0] * poly[i][1] for i in range(n))
    sign = 1 if area > 0 else -1
    lines = []
    for i in range(n):
        (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % n]
        ex, ey = x1 - x0, y1 - y0
        ln = math.hypot(ex, ey)
        lines.append(((x0 + sign * ey / ln * d, y0 - sign * ex / ln * d), (ex, ey)))
    out = []
    for i in range(n):
        (p, u), (q, v) = lines[i - 1], lines[i]
        t = ((q[0] - p[0]) * v[1] - (q[1] - p[1]) * v[0]) / (u[0] * v[1] - u[1] * v[0])
        out.append((p[0] + u[0] * t, p[1] + u[1] * t))
    return out


def _layer(big):
    return Image.new("L", (big, big), 0)


def foreground(px):
    """White eye on a transparent px×px canvas, matching the old vector's strokes and fills."""
    big = px * SS
    k = big / FG_DP * SCALE
    o = OFFSET * big / FG_DP
    tf = lambda pts: [(o + x * k, o + y * k) for x, y in pts]
    paths = load_paths()

    strokes = _layer(big)
    draw = ImageDraw.Draw(strokes)
    for pts in parse(paths["eye_brackets"]):
        for a, b in zip(tf(pts), tf(pts)[1:]):
            draw.line([a, b], fill=255, width=round(1.4 * k))
    lid = parse(paths["eye_lid"])[0]
    draw.polygon(tf(_offset(lid, 0.8)), fill=255)
    draw.polygon(tf(_offset(lid, -0.8)), fill=0)

    inside = _layer(big)
    ImageDraw.Draw(inside).polygon(tf(lid), fill=255)
    fills = _layer(big)
    fd = ImageDraw.Draw(fills)
    for pts in parse(paths["eye_scan"]):
        fd.polygon(tf(pts), fill=round(255 * 0.18))
    iris, *holes = parse(paths["eye_iris"])
    fd.polygon(tf(iris), fill=255)
    for pts in holes:
        fd.polygon(tf(pts), fill=0)

    a = np.maximum(np.array(strokes), np.minimum(np.array(fills), np.array(inside)))
    out = Image.new("RGBA", (px, px), (255, 255, 255, 0))
    out.putalpha(Image.fromarray(a.astype(np.uint8)).resize((px, px), Image.Resampling.LANCZOS))
    return out


def legacy(px):
    """Pre-adaptive launchers: the foreground's middle 72dp on a black rounded square."""
    fg = foreground(round(px * FG_DP / 72))
    m = (fg.width - px) // 2
    fg = fg.crop((m, m, m + px, m + px))
    big = px * SS
    bg = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(bg).rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.2, fill=(0, 0, 0, 255))
    bg = bg.resize((px, px), Image.Resampling.LANCZOS)
    bg.alpha_composite(fg)
    return bg


def main():
    for name, d in DENSITIES.items():
        fg_dir = RES / f"drawable-{name}"
        fg_dir.mkdir(exist_ok=True)
        foreground(round(FG_DP * d)).save(fg_dir / "ic_launcher_fg.png", optimize=True)
        legacy(round(LEGACY_DP * d)).save(RES / f"mipmap-{name}" / "ic_launcher.png", optimize=True)


if __name__ == "__main__":
    main()
