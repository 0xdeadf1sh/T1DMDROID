"""Tape-measure the MakeHuman body: landmark heights, lengths, and plane cross-sections.

Landmarks are vertices picked once on the neutral body, so they ride along with any morph.
A girth is the convex-hull perimeter of a cross-section, which is what a tape spans.
"""

import numpy as np


def _hull_perimeter(pts: np.ndarray) -> float:
    pts = np.unique(pts, axis=0)
    if len(pts) < 3:
        return 0.0
    pts = pts[np.lexsort((pts[:, 1], pts[:, 0]))]

    def turn(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    def half(seq):
        out = []
        for p in seq:
            while len(out) >= 2 and turn(out[-2], out[-1], p) <= 0:
                out.pop()
            out.append(p)
        return out

    hull = np.array(half(pts)[:-1] + half(pts[::-1])[:-1])
    return float(np.linalg.norm(hull - np.roll(hull, 1, axis=0), axis=1).sum())


def section(pos: np.ndarray, tri: np.ndarray, origin, normal, near) -> np.ndarray:
    """Points of the closed cross-section through [origin] whose component lies nearest [near]."""
    n = np.asarray(normal, float)
    n /= np.linalg.norm(n)
    s = (pos - origin) @ n
    st = s[tri]
    hit = tri[(st.min(1) < 0) & (st.max(1) > 0)]
    edges = np.concatenate([hit[:, [0, 1]], hit[:, [1, 2]], hit[:, [2, 0]]])
    a, b = edges[:, 0], edges[:, 1]
    cross = (s[a] < 0) != (s[b] < 0)
    tri_id = np.tile(np.arange(len(hit)), 3)[cross]
    a, b = a[cross], b[cross]
    key = np.minimum(a, b) * len(pos) + np.maximum(a, b)
    t = s[a] / (s[a] - s[b])
    pt = pos[a] + (pos[b] - pos[a]) * t[:, None]
    uniq, inv = np.unique(key, return_inverse=True)
    parent = np.arange(len(uniq))

    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    order = np.argsort(tri_id, kind="stable")
    for i, j in zip(order[0::2], order[1::2]):
        ri, rj = root(inv[i]), root(inv[j])
        if ri != rj:
            parent[ri] = rj
    comp = np.array([root(i) for i in inv])
    best, best_d = pt[:0], np.inf
    for c in np.unique(comp):
        p = pt[comp == c]
        d = np.linalg.norm(p.mean(0) - near)
        if d < best_d:
            best, best_d = p, d
    return best


class Ruler:
    """Measures in centimetres; the mesh is in decimetres."""

    def __init__(self, neutral: np.ndarray, tri: np.ndarray):
        self.tri = tri
        used = np.unique(tri)
        q = neutral[used]
        lo, hi = q[:, 1].min(), q[:, 1].max()
        self.H = hi - lo
        self.floor = lo

        def pick(x, y, z_side=None, x_side=None, r=0.012):
            tx, ty = x * self.H, lo + y * self.H
            near = used[(np.abs(neutral[used, 0] - tx) < r * self.H) & (np.abs(neutral[used, 1] - ty) < r * self.H)]
            assert len(near), (x, y)
            if z_side:
                return int(near[np.argmax(z_side * neutral[near, 2])])
            if x_side:
                return int(near[np.argmax(x_side * neutral[near, 0])])
            return int(near[np.argmin(np.abs(neutral[near, 1] - ty))])

        mid = used[np.abs(neutral[used, 0]) < 0.006 * self.H]
        self.lm = {
            "crown": int(used[np.argmax(q[:, 1])]),
            "crotch": int(mid[np.argmin(np.where(neutral[mid, 1] > lo + 0.3 * self.H, neutral[mid, 1], np.inf))]),
            "knee": pick(-0.07, 0.28, z_side=1),
            "ankle": pick(-0.12, 0.045, x_side=-1),
            "calf": pick(-0.08, 0.19, z_side=-1),
            "buttock": pick(-0.05, 0.5, z_side=-1, r=0.04),
            "navel": pick(0.0, 0.61, z_side=1),
            "nipple": pick(-0.063, 0.73, z_side=1),
            "acromion_r": pick(-0.115, 0.81, r=0.02),
            "acromion_l": pick(0.115, 0.81, r=0.02),
            "cervicale": pick(0.0, 0.845, z_side=-1),
            "neck": pick(0.0, 0.862, z_side=-1),
            "elbow": pick(-0.215, 0.678, x_side=-1, r=0.02),
            "wrist": pick(-0.275, 0.56, x_side=-1, r=0.02),
        }
        for k in ("acromion_r", "acromion_l"):
            ring = used[np.linalg.norm(neutral[used] - neutral[self.lm[k]], axis=1) < 0.02 * self.H]
            self.lm[k] = int(ring[np.argmax(neutral[ring, 1])])

        # Parts by neutral position, so a heavy arm or thigh never joins a neighbour's girth.
        x, y = np.abs(neutral[:, 0]) / self.H, (neutral[:, 1] - lo) / self.H
        arm = ((x > 0.105) & (y > 0.55)) | (x > 0.2)
        right = neutral[:, 0] < 0
        self.torso_tri = tri[~arm[tri].any(1)]
        self.arm_tri = tri[(arm & right)[tri].all(1)]
        self.leg_tri = tri[(right & (y < 0.5))[tri].all(1)]

    def measure(self, pos: np.ndarray) -> dict[str, float]:
        lm = {k: pos[v] for k, v in self.lm.items()}
        floor = pos[np.unique(self.tri), 1].min()
        up = np.array([0.0, 1.0, 0.0])

        def h(k):
            return (lm[k][1] - floor) * 10

        def ring(y_of, near, tri=None):
            o = np.array([0.0, lm[y_of][1], 0.0])
            return section(pos, self.torso_tri if tri is None else tri, o, up, near)

        torso = np.array([0.0, 0.0, 0.0])
        leg = np.array([lm["knee"][0], 0.0, lm["knee"][2]])
        chest = ring("nipple", torso + [0, lm["nipple"][1], 0])
        waist = ring("navel", torso + [0, lm["navel"][1], 0])
        hip = ring("buttock", torso + [0, lm["buttock"][1], 0])
        thigh_o = np.array([0.0, lm["crotch"][1] - 0.015 * self.H, 0.0])
        thigh = section(pos, self.leg_tri, thigh_o, up, leg + thigh_o)
        calf = ring("calf", leg + [0, lm["calf"][1], 0], self.leg_tri)
        neck_z = (lm["cervicale"][2] + lm["nipple"][2]) / 2
        neck = ring("neck", np.array([0.0, lm["neck"][1], neck_z]))
        arm_mid = (lm["acromion_r"] + lm["elbow"]) / 2
        arm = section(pos, self.arm_tri, arm_mid, lm["elbow"] - lm["acromion_r"], arm_mid)

        def girth(p, axes=(0, 2)):
            return _hull_perimeter(p[:, list(axes)]) * 10

        def plane_girth(p, normal):
            n = normal / np.linalg.norm(normal)
            u = np.cross(n, [0.0, 0.0, 1.0])
            u /= np.linalg.norm(u)
            v = np.cross(n, u)
            return _hull_perimeter(np.stack([p @ u, p @ v], 1)) * 10

        def extent(p, axis):
            return float(np.ptp(p[:, axis])) * 10

        return {
            "stature": (pos[self.lm["crown"], 1] - floor) * 10,
            "crotch_h": h("crotch"),
            "knee_h": h("knee"),
            "ankle_h": h("ankle"),
            "buttock_h": h("buttock"),
            "navel_h": h("navel"),
            "chest_h": h("nipple"),
            "acromion_h": (h("acromion_r") + h("acromion_l")) / 2,
            "cervicale_h": h("cervicale"),
            "shoulder_w": abs(lm["acromion_l"][0] - lm["acromion_r"][0]) * 10,
            "upperarm_l": float(np.linalg.norm(lm["elbow"] - lm["acromion_r"])) * 10,
            "forearm_l": float(np.linalg.norm(lm["wrist"] - lm["elbow"])) * 10,
            "chest_w": extent(chest, 0),
            "chest_d": extent(chest, 2),
            "chest_c": girth(chest),
            "waist_w": extent(waist, 0),
            "waist_d": extent(waist, 2),
            "waist_c": girth(waist),
            "hip_w": extent(hip, 0),
            "hip_d": extent(hip, 2),
            "hip_c": girth(hip),
            "thigh_c": girth(thigh),
            "calf_c": girth(calf),
            "neck_c": girth(neck),
            "upperarm_c": plane_girth(arm, lm["elbow"] - lm["acromion_r"]),
        }
