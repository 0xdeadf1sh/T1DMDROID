"""Procedural heart and brain for the twin, anchored to body vertices so they follow its morphs.

TWO1, little-endian: u32 organ count; per organ u8 name length, name, u32 nv, u32 nt, nv×3 f32
positions about the centre (dm), nt×3 u16, u32 k, k u16 anchors, k f32 weights, 3 f32 offset.
"""

import struct

import numpy as np

ANCHORS = 12


def icosphere(level: int):
    t = (1 + 5 ** 0.5) / 2
    v = [(-1, t, 0), (1, t, 0), (-1, -t, 0), (1, -t, 0), (0, -1, t), (0, 1, t),
         (0, -1, -t), (0, 1, -t), (t, 0, -1), (t, 0, 1), (-t, 0, -1), (-t, 0, 1)]
    f = [(0, 11, 5), (0, 5, 1), (0, 1, 7), (0, 7, 10), (0, 10, 11), (1, 5, 9), (5, 11, 4),
         (11, 10, 2), (10, 7, 6), (7, 1, 8), (3, 9, 4), (3, 4, 2), (3, 2, 6), (3, 6, 8),
         (3, 8, 9), (4, 9, 5), (2, 4, 11), (6, 2, 10), (8, 6, 7), (9, 8, 1)]
    verts = [np.array(p, float) / np.linalg.norm(p) for p in v]
    for _ in range(level):
        cache, nf = {}, []

        def mid(a, b):
            key = (min(a, b), max(a, b))
            if key not in cache:
                m = verts[a] + verts[b]
                verts.append(m / np.linalg.norm(m))
                cache[key] = len(verts) - 1
            return cache[key]

        for a, b, c in f:
            ab, bc, ca = mid(a, b), mid(b, c), mid(c, a)
            nf += [(a, ab, ca), (b, bc, ab), (c, ca, bc), (ab, bc, ca)]
        f = nf
    return np.array(verts), np.array(f, np.int64)


def _rot(axis: int, deg: float) -> np.ndarray:
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    i, j = [k for k in range(3) if k != axis]
    r = np.eye(3)
    r[i, i], r[i, j], r[j, i], r[j, j] = c, -s, s, c
    return r


def heart(level: int = 3):
    """Taubin's heart surface, ~1.2 dm tall, apex turned to the body's left and forward."""
    d, tri = icosphere(level)

    def inside(p):
        x, y, z = p[..., 0], p[..., 2], p[..., 1]  # the formula has z up and y as depth
        a = x * x + 2.25 * y * y + z * z - 1
        return a ** 3 - x * x * z ** 3 - 0.1125 * y * y * z ** 3 < 0

    lo, hi = np.zeros(len(d)), np.full(len(d), 2.0)
    for _ in range(40):
        m = (lo + hi) / 2
        ok = inside(d * m[:, None])
        lo, hi = np.where(ok, m, lo), np.where(ok, hi, m)
    p = d * lo[:, None] * 0.55
    p = p @ _rot(2, 30).T @ _rot(0, 20).T
    return p, tri


def _ellipsoid(level, centre, radii, ridge):
    d, tri = icosphere(level)
    p = d * radii
    p *= (1 + ridge(p))[:, None]
    return p + centre, tri


def brain():
    """Two folded hemispheres and a ridged cerebellum, ~1.5 dm long."""
    def gyri(p):
        x, y, z = p[:, 0] * 9, p[:, 1] * 9, p[:, 2] * 9
        return 0.045 * (np.sin(x + 2 * np.sin(z * 0.7)) * np.sin(y * 1.1 + np.sin(x * 0.8))
                        + np.sin(z * 1.3 + 1.5 * np.sin(y)) * 0.6)

    parts = [
        _ellipsoid(3, (-0.36, 0.0, 0.0), (0.34, 0.47, 0.8), gyri),
        _ellipsoid(3, (0.36, 0.0, 0.0), (0.34, 0.47, 0.8), gyri),
        _ellipsoid(2, (0.0, -0.36, -0.52), (0.48, 0.2, 0.28), lambda p: 0.04 * np.sin(p[:, 1] * 60)),
    ]
    pos, tris, base = [], [], 0
    for p, t in parts:
        pos.append(p)
        tris.append(t + base)
        base += len(p)
    p = np.concatenate(pos)
    p[:, 1] = np.where(p[:, 1] < -0.2, -0.2 + (p[:, 1] + 0.2) * 0.6, p[:, 1])
    return p * 0.9, np.concatenate(tris)


def centres(body: np.ndarray, eyes) -> dict[str, np.ndarray]:
    """Heart behind the sternum, a little to the body's left; brain behind and above the eyes."""
    mid = (eyes[0] + eyes[1]) / 2
    top = body[:, 1].max()
    y = top - 0.27 * (top - body[:, 1].min())
    band = body[(np.abs(body[:, 1] - y) < 0.15) & (np.abs(body[:, 0]) < 0.6)]
    return {
        "heart": np.array([0.12, y, (band[:, 2].max() + band[:, 2].min()) / 2]),
        "brain": mid + np.array([0.0, 0.42, -0.95]),
    }


def anchors(body: np.ndarray, centre: np.ndarray):
    d = np.linalg.norm(body - centre, axis=1)
    idx = np.argsort(d)[:ANCHORS]
    w = 1 / np.maximum(d[idx], 1e-6)
    w /= w.sum()
    return idx, w, centre - (w[:, None] * body[idx]).sum(0)


def write(path, body: np.ndarray, eyes) -> None:
    meshes = {"heart": heart(), "brain": brain()}
    cs = centres(body, eyes)
    with open(path, "wb") as f:
        f.write(b"TWO1" + struct.pack("<I", len(meshes)))
        for name, (p, t) in meshes.items():
            idx, w, off = anchors(body, cs[name])
            f.write(struct.pack("<B", len(name)) + name.encode())
            f.write(struct.pack("<II", len(p), len(t)))
            f.write(p.astype("<f4").tobytes() + t.astype("<u2").tobytes())
            f.write(struct.pack("<I", len(idx)) + idx.astype("<u2").tobytes() + w.astype("<f4").tobytes())
            f.write(off.astype("<f4").tobytes())
            print(f"{name}: {len(p)} verts, {len(t)} tris, centre {np.round(cs[name], 2)}")
