#!/usr/bin/env python3
"""Low-poly twin body with anthropometric morphs; layout in TwinBody.kt. Organs go beside it.

Usage: python3 -I build.py --cache DIR --out feature/twin/src/main/assets/twin_body.bin
CC0 MakeHuman measure targets are solved to hit anthro_fit.json, then fitted as linear morphs.
"""

import argparse
import json
import struct
import urllib.request
from pathlib import Path

import fast_simplification
import numpy as np

import anatomy
import anthro
import organs
import rig
from measure import Ruler

RAW = "https://raw.githubusercontent.com/makehumancommunity/makehuman/master/makehuman/data"
ETHNIC = ("african", "asian", "caucasian")
GENDERS = ("female", "male")
TRIS = 3600
# Without the eyeballs the sockets are holes that show the backdrop.
EYES = ("helper-r-eye", "helper-l-eye")
FIT = Path(__file__).with_name("anthro_fit.json")

# Feature origin and scale; TwinMorphs.kt mirrors these.
REF_CM, REF_KG, REF_AGE = 170.0, 70.0, 40.0
SPAN = 10.0
FEATURES = ("stature", "mass", "age", "male", "male_stature", "male_mass", "male_age")

# What MakeHuman's neutral body stands for: its stature, a BMI of 22, and its "young" age.
NEUTRAL_AS = (165.9, 60.5, 25.0, 0.5)

MEASURE = (
    "neck-circ", "neck-height", "upperarm-circ", "upperarm-length", "lowerarm-length", "wrist-circ",
    "frontchest-dist", "bust-circ", "underbust-circ", "waist-circ", "napetowaist-dist",
    "waisttohip-dist", "shoulder-dist", "hips-circ", "upperleg-height", "thigh-circ",
    "lowerleg-height", "calf-circ", "knee-circ", "ankle-circ",
)
SHAPE = (
    "torso/torso-scale-depth", "torso/torso-scale-horiz", "hip/hip-scale-depth", "hip/hip-scale-horiz",
    "stomach/stomach-pregnant", "buttocks/buttocks-volume",
)
SIDED = ("armslegs/{}-upperarm-fat", "armslegs/{}-upperleg-fat", "armslegs/{}-lowerleg-fat")
WEIGHT = {"stature": 20.0, "crotch_h": 3.0, "navel_h": 3.0, "acromion_h": 3.0, "cervicale_h": 3.0}
RIDGE = 1e-4
# MakeHuman's targets are drawn for ±1; past ±3 the mesh folds.
W_MAX = 3.0


def fetch(cache: Path, rel: str) -> Path:
    dst = cache / rel
    if not dst.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(f"{RAW}/{rel}") as r:
            dst.write_bytes(r.read())
    return dst


def load_body(path: Path):
    """Vertices, the body's triangles, and each eyeball's triangles (right, left)."""
    verts, groups, group = [], {}, None
    for line in path.read_text().splitlines():
        if line.startswith("v "):
            verts.append([float(x) for x in line.split()[1:4]])
        elif line.startswith("g "):
            group = line.split(maxsplit=1)[1].strip()
        elif line.startswith("f ") and group in ("body", *EYES):
            idx = [int(tok.split("/")[0]) - 1 for tok in line.split()[1:]]
            groups.setdefault(group, []).extend([idx[0], idx[i], idx[i + 1]] for i in range(1, len(idx) - 1))
    tris = {g: np.asarray(f, np.int64) for g, f in groups.items()}
    return np.asarray(verts, np.float64), tris["body"], [tris[g] for g in EYES]


def load_joints(path: Path) -> dict[str, list[int]]:
    """MakeHuman's joint helpers: group name → its vertex indices."""
    joints, group = {}, None
    for line in path.read_text().splitlines():
        if line.startswith("g "):
            group = line.split(maxsplit=1)[1].strip()
        elif line.startswith("f ") and group and group.startswith("joint-"):
            joints.setdefault(group, set()).update(int(t.split("/")[0]) - 1 for t in line.split()[1:])
    return {g: sorted(v) for g, v in joints.items()}


def load_target(path: Path, n: int) -> np.ndarray:
    d = np.zeros((n, 3))
    for line in path.read_text().splitlines():
        if line and not line.startswith("#"):
            i, x, y, z = line.split()
            d[int(i)] = (float(x), float(y), float(z))
    return d


class Targets:
    def __init__(self, cache: Path, n: int):
        self.cache, self.n = cache, n

    def __call__(self, rel: str) -> np.ndarray:
        return load_target(fetch(self.cache, f"targets/{rel}.target"), self.n)

    def signed(self, rel: str) -> np.ndarray:
        """One direction per incr/decr pair, so a weight of ±1 is either end."""
        return (self(f"{rel}-incr") - self(f"{rel}-decr")) / 2


def character(t: Targets):
    """MakeHuman's neutral, plus its sex, age and muscle directions, which no survey measures."""
    young = {(e, g): t(f"macrodetails/{e}-{g}-young") for e in ETHNIC for g in GENDERS}
    old = {(e, g): t(f"macrodetails/{e}-{g}-old") for e in ETHNIC for g in GENDERS}
    neutral = sum(young.values()) / 6
    male = sum(young[(e, "male")] for e in ETHNIC) / 3
    female = sum(young[(e, "female")] for e in ETHNIC) / 3

    def universal(muscle: str, weight: str) -> np.ndarray:
        return np.mean([t(f"macrodetails/universal-{g}-young-{muscle}-{weight}") for g in GENDERS], axis=0)

    avg = universal("averagemuscle", "averageweight")
    return neutral + avg, {
        "sex": (male - female) / 2,
        "age": sum(old.values()) / 6 - neutral,
        "muscle_up": universal("maxmuscle", "averageweight") - avg,
        "muscle_down": universal("minmuscle", "averageweight") - avg,
    }


def basis(t: Targets) -> np.ndarray:
    dirs = [t.signed(f"measure/measure-{m}") for m in MEASURE]
    dirs += [t.signed(s) for s in SHAPE]
    dirs += [t.signed(s.format("l")) + t.signed(s.format("r")) for s in SIDED]
    return np.stack(dirs)


def features(cm: float, kg: float, age: float, male: float) -> np.ndarray:
    h, m, a, p = (cm - REF_CM) / SPAN, (kg - REF_KG) / SPAN, (age - REF_AGE) / SPAN, male - 0.5
    return np.array([h, m, a, p, p * h, p * m, p * a])


class Solver:
    """Basis weights that bring the body's tape measures to the survey's prediction."""

    def __init__(self, ruler: Ruler, base, char, dirs, model):
        self.ruler, self.base, self.char, self.dirs, self.model = ruler, base, char, dirs, model
        self.ref = ruler.measure(base)
        self.names = list(self.ref)
        self.pred0 = {k: anthro.predict(model, k, *NEUTRAL_AS) for k in self.names if k != "stature"}
        jac = np.empty((len(self.names), len(dirs)))
        for j, d in enumerate(dirs):
            up, dn = ruler.measure(base + 0.5 * d), ruler.measure(base - 0.5 * d)
            jac[:, j] = [up[k] - dn[k] for k in self.names]
        self.jac = jac

    def target(self, cm, kg, age, male) -> np.ndarray:
        """Stature as given; every other measure scaled from the neutral's by the survey's ratio."""
        return np.array([
            cm if k == "stature" else self.ref[k] * anthro.predict(self.model, k, cm, kg, age, male) / self.pred0[k]
            for k in self.names
        ])

    def __call__(self, cm, kg, age, male):
        start = self.base + self.char["sex"] * (2 * male - 1) + self.char["age"] * (age - 25) / 65
        target = self.target(cm, kg, age, male)
        scale = np.array([WEIGHT.get(k, 1.0) for k in self.names]) / target
        a = self.jac * scale[:, None]
        lhs = a.T @ a + RIDGE * np.eye(len(self.dirs))
        def resid(w):
            got = self.ruler.measure(start + np.tensordot(w, self.dirs, 1))
            return (target - np.array([got[k] for k in self.names])) * scale

        w = np.zeros(len(self.dirs))
        r = resid(w)
        for _ in range(8):
            step = np.linalg.solve(lhs, a.T @ r)
            for _ in range(5):
                w_new = np.clip(w + step, -W_MAX, W_MAX)
                r_new = resid(w_new)
                if r_new @ r_new < r @ r:
                    w, r = w_new, r_new
                    break
                step /= 2
        pos = start + np.tensordot(w, self.dirs, 1)
        got = self.ruler.measure(pos)
        return pos, w, {k: got[k] / t - 1 for k, t in zip(self.names, target)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--refit", action="store_true", help="refit anthro_fit.json from the surveys")
    a = ap.parse_args()

    if a.refit:
        FIT.write_text(json.dumps(anthro.fit(a.cache), indent=1) + "\n")
    model = json.loads(FIT.read_text())

    verts, faces, eyes = load_body(fetch(a.cache, "3dobjs/base.obj"))
    t = Targets(a.cache, len(verts))
    base_delta, char = character(t)
    base = verts + base_delta
    solve = Solver(Ruler(base, faces), base, char, basis(t), model)

    samples, phi, worst, wmax = [], [], {}, 0.0
    for male in (0.0, 0.5, 1.0):
        for cm in (145.0, 160.0, 175.0, 190.0, 205.0):
            for bmi in (16.0, 21.0, 27.0, 34.0, 42.0):
                for age in (18.0, 45.0, 90.0):
                    kg = bmi * (cm / 100) ** 2
                    pos, w, err = solve(cm, kg, age, male)
                    samples.append((pos - base).ravel())
                    phi.append(np.concatenate([[1.0], features(cm, kg, age, male)]))
                    wmax = max(wmax, np.abs(w).max())
                    for k, e in err.items():
                        worst[k] = max(worst.get(k, 0.0), abs(e))
    coef, *_ = np.linalg.lstsq(np.array(phi), np.array(samples), rcond=None)
    resid = np.array(samples) - np.array(phi) @ coef
    print("solve error, worst per measure:", {k: f"{v:.1%}" for k, v in worst.items()})
    print(f"largest basis weight {wmax:.2f}")
    print(f"linear morph fit: rms {np.sqrt((resid ** 2).mean()) * 100:.2f} mm, max {np.abs(resid).max() * 100:.1f} mm")

    neutral = base + coef[0].reshape(-1, 3)
    deltas = {name: coef[i + 1].reshape(-1, 3) for i, name in enumerate(FEATURES)}
    deltas["muscle_up"] = char["muscle_up"]
    deltas["muscle_down"] = char["muscle_down"]

    shown = np.concatenate([faces, *eyes])
    used = np.unique(shown)
    remap = np.full(len(verts), -1)
    remap[used] = np.arange(len(used))
    pos = neutral[used]
    tri = remap[shown]
    centres = tuple(neutral[np.unique(e)].mean(0) for e in eyes)
    lo_pos, lo_tri = fast_simplification.simplify(
        pos.astype(np.float32), tri.astype(np.int32), target_reduction=1 - TRIS / len(tri)
    )
    nearest = np.argmin(((lo_pos[:, None, :] - pos[None, :, :]) ** 2).sum(-1), axis=1)
    assert len(lo_pos) < 65536, "indices are u16"
    skeleton = rig.Rig(fetch(a.cache, "rigs/default.mhskel"), fetch(a.cache, "rigs/default_weights.mhw"))
    bone_idx, bone_w = skeleton.skin(len(verts))

    with a.out.open("wb") as f:
        f.write(b"TWN3")
        f.write(struct.pack("<IIII", len(lo_pos), len(lo_tri), len(deltas), len(rig.KEPT)))
        f.write(lo_pos.astype("<f4").tobytes())
        f.write(lo_tri.astype("<u2").tobytes())
        for name, d in deltas.items():
            f.write(struct.pack("<B", len(name)) + name.encode())
            f.write(d[used][nearest].astype("<f4").tobytes())
        for name, parent in zip(rig.KEPT, skeleton.parents()):
            f.write(struct.pack("<B", len(name)) + name.encode() + struct.pack("<b", parent))
            f.write(skeleton.pivot(name, neutral).astype("<f4").tobytes())
            for d in deltas.values():
                f.write(skeleton.pivot(name, d).astype("<f4").tobytes())
        f.write(bone_idx[used][nearest].tobytes())
        f.write(bone_w[used][nearest].astype("<f4").tobytes())
    print(f"{len(lo_pos)} verts, {len(lo_tri)} tris, {len(deltas)} morphs, {len(rig.KEPT)} bones -> {a.out}")
    lo = lo_pos.astype(np.float64)
    hubs = organs.write(a.out.with_name("twin_organs.bin"), lo, centres, a.cache)
    joints = {g: neutral[v].mean(0) for g, v in load_joints(fetch(a.cache, "3dobjs/base.obj")).items()}
    skin = remap[faces]
    anatomy.write(a.out.with_name("twin_systems.bin"), pos, anatomy.vertex_normals(pos, skin), lo, joints, a.cache, hubs)


if __name__ == "__main__":
    main()
