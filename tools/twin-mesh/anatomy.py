"""Blood vessels (BodyParts3D) and nerves (Z-Anatomy) carried onto the twin along its skeleton.

Each source point rides its nearest bone segments (BP3D landmark → MakeHuman joint), is pushed back
inside the skin, then anchored to body vertices so it follows the morphs. Layout: see `write`.
"""

import json
import shutil
import struct
import subprocess
import urllib.request
import zipfile
from pathlib import Path

import numpy as np

import organs

ZRAW = "https://raw.githubusercontent.com/Z-Anatomy/Models-of-human-anatomy/master/Z-Anatomy.zip"
ANCHORS = 4
SIGMA = 0.35  # dm: how far a bone segment's pull reaches
MARGIN = 0.04  # dm kept between a vessel or nerve and the skin
ARTERY, VEIN, NERVE = 0.0, 1.0, 2.0
TRIS = {"arteries": 22000, "veins": 14000, "cord": 1500}
# Z-Anatomy bone → BP3D compound, to fit Z-Anatomy's frame onto BP3D's.
ZBONES = {"Femur.l": "left femur", "Femur.r": "right femur", "Humerus.l": "left humerus",
          "Humerus.r": "right humerus", "Tibia.l": "left tibia", "Tibia.r": "right tibia",
          "Radius.l": "left radius", "Radius.r": "right radius", "Atlas (C1)": "atlas"}


def _ends(p: np.ndarray):
    """Low and high ends of a bone along its long axis, by height."""
    c = p.mean(0)
    ax = np.linalg.svd(p - c, full_matrices=False)[2][0]
    t = (p - c) @ ax
    a, b = p[t < np.quantile(t, 0.03)].mean(0), p[t > np.quantile(t, 0.97)].mean(0)
    return (a, b) if a[1] < b[1] else (b, a)


class Skeleton:
    """Matched landmark chains: BP3D (body axes, dm) and MakeHuman neutral (dm)."""

    def __init__(self, bp: organs.BP3D, axes: np.ndarray, joints: dict[str, np.ndarray], top: float):
        def bone(name):
            return bp.organ(name)[0] @ axes.T / 100.0

        src, dst = {}, {}
        for s, side in (("l", "left"), ("r", "right")):
            knee, hip = _ends(bone(f"{side} femur"))
            ankle, _ = _ends(bone(f"{side} tibia"))
            elbow, shoulder = _ends(bone(f"{side} humerus"))
            wrist, _ = _ends(bone(f"{side} radius"))
            sternal = min(_ends(bone(f"{side} clavicle")), key=lambda q: abs(q[0]))
            src |= {f"hip{s}": hip, f"knee{s}": knee, f"ankle{s}": ankle, f"elbow{s}": elbow,
                    f"shoulder{s}": shoulder, f"wrist{s}": wrist, f"clav{s}": sternal}
            dst |= {f"hip{s}": joints[f"joint-{s}-upper-leg"], f"knee{s}": joints[f"joint-{s}-knee"],
                    f"ankle{s}": joints[f"joint-{s}-ankle"], f"elbow{s}": joints[f"joint-{s}-elbow"],
                    f"shoulder{s}": joints[f"joint-{s}-shoulder"], f"wrist{s}": joints[f"joint-{s}-hand"],
                    f"clav{s}": joints[f"joint-{s}-clavicle"]}
            # Hands and feet have no matched bone: carry the last segment's direction on.
            for end, prev, tip in (("wrist", "elbow", "hand-3"), ("ankle", "knee", "foot-2")):
                d = dst[f"{end}{s}"]
                reach = np.linalg.norm(joints[f"joint-{s}-{tip}"] - d)
                src[f"{end}tip{s}"] = src[f"{end}{s}"] + reach * _unit(src[f"{end}{s}"] - src[f"{prev}{s}"])
                dst[f"{end}tip{s}"] = joints[f"joint-{s}-{tip}"]
        src["pelvis"] = (src["hipl"] + src["hipr"]) / 2
        dst["pelvis"] = (dst["hipl"] + dst["hipr"]) / 2
        src["l5"] = bone("fifth lumbar vertebra").mean(0)
        src["t1"] = bone("first thoracic vertebra").mean(0)
        src["atlas"] = bone("atlas").mean(0)
        skull = bone("skull")
        src["top"] = skull[np.argmax(skull[:, 1])]
        dst |= {"l5": joints["joint-spine-4"], "t1": joints["joint-neck"], "atlas": joints["joint-head"]}
        dst["top"] = np.array([0.0, top, joints["joint-head"][2]])
        # MakeHuman's middle spine joints sit at the same fraction of the L5–T1 run in BP3D.
        for name in ("joint-spine-3", "joint-spine-2", "joint-spine-1"):
            f = (joints[name][1] - dst["l5"][1]) / (dst["t1"][1] - dst["l5"][1])
            src[name] = src["l5"] + f * (src["t1"] - src["l5"])
            dst[name] = joints[name]
        chains = [["pelvis", "l5", "joint-spine-3", "joint-spine-2", "joint-spine-1", "t1", "atlas", "top"]]
        for s in "lr":
            chains += [["pelvis", f"hip{s}", f"knee{s}", f"ankle{s}", f"ankletip{s}"],
                       ["t1", f"clav{s}", f"shoulder{s}", f"elbow{s}", f"wrist{s}", f"wristtip{s}"]]
        segs = [(c[i], c[i + 1]) for c in chains for i in range(len(c) - 1)]
        self.a = np.array([src[x] for x, _ in segs])
        self.b = np.array([src[y] for _, y in segs])
        self.a2 = np.array([dst[x] for x, _ in segs])
        self.b2 = np.array([dst[y] for _, y in segs])
        self.rot = np.array([_rotation(_unit(b - a), _unit(b2 - a2))
                             for a, b, a2, b2 in zip(self.a, self.b, self.a2, self.b2)])
        self.scale = np.linalg.norm(self.b2 - self.a2, axis=1) / np.linalg.norm(self.b - self.a, axis=1)

    def warp(self, p: np.ndarray) -> np.ndarray:
        """BP3D body-axes points → MakeHuman neutral, blended over the two nearest segments."""
        out = np.empty_like(p)
        ab = self.b - self.a
        for i in range(0, len(p), 4096):
            q = p[i:i + 4096]
            t = ((q[:, None, :] - self.a[None]) * ab[None]).sum(-1) / (ab * ab).sum(-1)[None]
            foot = self.a[None] + np.clip(t, 0, 1)[..., None] * ab[None]
            d = np.linalg.norm(q[:, None, :] - foot, axis=-1)
            w = np.exp(-((d - d.min(1, keepdims=True)) / SIGMA) ** 2)
            w[d > np.sort(d, 1)[:, [1]]] = 0
            w /= w.sum(1, keepdims=True)
            base = self.a[None] + t[..., None] * ab[None]
            off = np.einsum("sij,nsj->nsi", self.rot, q[:, None, :] - base) * self.scale[None, :, None]
            mapped = self.a2[None] + t[..., None] * (self.b2 - self.a2)[None] + off
            out[i:i + 4096] = (w[..., None] * mapped).sum(1)
        return out


def _unit(v):
    return v / np.linalg.norm(v)


def _rotation(u, v):
    """Smallest rotation taking unit u onto unit v (Rodrigues)."""
    k = np.cross(u, v)
    s, c = np.linalg.norm(k), float(np.dot(u, v))
    if s < 1e-9:
        return np.eye(3)
    k /= s
    kx = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + s * kx + (1 - c) * kx @ kx


def inside(p: np.ndarray, skin: np.ndarray, normals: np.ndarray) -> np.ndarray:
    """Points pushed back under the nearest skin vertex's tangent plane by MARGIN."""
    p = p.copy()
    for i in range(0, len(p), 1024):
        q = p[i:i + 1024]
        j = np.argmin(((q[:, None, :] - skin[None]) ** 2).sum(-1), axis=1)
        h = ((q - skin[j]) * normals[j]).sum(-1) + MARGIN
        q -= np.maximum(h, 0)[:, None] * normals[j]
    return p


def vessels(isa: organs.BP3D, skull: np.ndarray, axes: np.ndarray):
    """Systemic arteries and veins (IS-A tree: it has the limb veins) as decimated tube meshes."""
    heart = set(isa.elements["coronary artery"]) | set(isa.elements["cardiac vein"])
    lo_c, hi_c = skull.min(0), skull.max(0)
    out = []
    for cls, name, budget in ((ARTERY, "systemic artery", "arteries"), (VEIN, "systemic vein", "veins")):
        keep = [f for f in isa.elements[name] if f not in heart and f in isa.members]
        pos, tri, base = [], [], 0
        for f in keep:
            v, t = isa.mesh(f)
            # Vessels inside the skull would glow over the face; the brain view has its own.
            if ((v.mean(0) > lo_c) & (v.mean(0) < hi_c)).all():
                continue
            pos.append(v)
            tri.append(t + base)
            base += len(v)
        p = np.concatenate(pos) @ axes.T / 100.0
        lo, lt, _ = organs.decimate(p, np.concatenate(tri), TRIS[budget], near_source=False)
        out.append((lo, lt, np.full(len(lo), cls)))
    return out


def cord(bp: organs.BP3D, axes: np.ndarray):
    v, t, _ = bp.organ("spinal cord")
    lo, lt, _ = organs.decimate(v @ axes.T / 100.0, t, TRIS["cord"], near_source=False)
    return lo, lt


def zanatomy_nerves(cache: Path, bp: organs.BP3D, axes: np.ndarray):
    """Z-Anatomy's major nerves as line segments, moved into BP3D's frame by a bone fit."""
    d = cache / "zanatomy"
    data = d / "nerves.json"
    if not data.exists():
        d.mkdir(parents=True, exist_ok=True)
        blend = d / "Z-Anatomy" / "Startup.blend"
        if not blend.exists():
            z = d / "Z-Anatomy.zip"
            if not z.exists():
                req = urllib.request.Request(ZRAW, headers={"User-Agent": "twin-mesh"})
                with urllib.request.urlopen(req) as r:
                    z.write_bytes(r.read())
            zipfile.ZipFile(z).extract("Z-Anatomy/Startup.blend", d)
        blender = shutil.which("blender")
        assert blender, "Blender exports Z-Anatomy's nerves; install it"
        script = Path(__file__).with_name("zanatomy_export.py")
        subprocess.run([blender, "--background", "--factory-startup", str(blend), "--python", str(script),
                        "--", str(data)], check=True, capture_output=True)
    raw = json.loads(data.read_text())
    zs, bs = [], []
    for zname, bname in ZBONES.items():
        if zname in raw["bones"]:
            zs.append(raw["bones"][zname])
            bs.append(bp.organ(bname)[0].mean(0) @ axes.T / 100.0)
    fit = _similarity(np.array(zs), np.array(bs))
    pts, idx, base = [], [], 0
    for n in raw["nerves"].values():
        p = np.array(n["pts"])
        pts.append(fit(p))
        idx.append(np.array(n["edges"]) + base)
        base += len(p)
    return np.concatenate(pts), np.concatenate(idx)


def _similarity(src: np.ndarray, dst: np.ndarray):
    """Least-squares scale, rotation and shift taking src onto dst (Umeyama)."""
    ms, md = src.mean(0), dst.mean(0)
    a, b = src - ms, dst - md
    u, s, vt = np.linalg.svd(b.T @ a)
    d = np.diag([1, 1, np.sign(np.linalg.det(u @ vt))])
    r = u @ d @ vt
    k = (s * np.diag(d)).sum() / (a ** 2).sum()
    err = np.linalg.norm(k * a @ r.T + md - dst, axis=1)
    print(f"Z-Anatomy → BP3D fit: scale {k:.2f}, bone error max {err.max() * 100:.1f} mm")
    return lambda p: k * (p - ms) @ r.T + md


def write(path, body_full, normals, body_lo, joints, cache: Path, hub: dict[str, np.ndarray]) -> None:
    """u32 count; per system: u8 name length, name, u8 kind (0 triangles, 1 lines), u32 nv, u32 ni,
    nv records (4 u16 anchors, 4 f32 weights, 3 f32 offset, f32 hub distance, f32 class), ni u16."""
    bp = organs.BP3D(cache)
    axes = organs.body_axes(bp)
    skel = Skeleton(bp, axes, joints, body_full[:, 1].max())
    (ap, at, ac), (vp, vt, vc) = vessels(organs.BP3D(cache, "isa"), bp.organ("neurocranium")[0], axes)
    cp, ct = cord(bp, axes)
    zp, ze = zanatomy_nerves(cache, bp, axes)
    systems = [
        ("vessels", 0, np.concatenate([ap, vp]), np.concatenate([at, vt + len(ap)]),
         np.concatenate([ac, vc]), hub["heart"]),
        ("cord", 0, cp, ct, np.full(len(cp), NERVE), hub["brain"]),
        ("nerves", 1, zp, ze, np.full(len(zp), NERVE), hub["brain"]),
    ]
    with open(path, "wb") as f:
        f.write(b"TWS1" + struct.pack("<I", len(systems)))
        for name, kind, p, idx, cls, centre in systems:
            q = inside(skel.warp(p), body_full, normals)
            assert len(q) < 65536, f"{name}: indices are u16"
            f.write(struct.pack("<B", len(name)) + name.encode() + struct.pack("<BII", kind, len(q), idx.size))
            rec = np.empty(len(q), dtype=[("a", "<u2", 4), ("w", "<f4", 4), ("o", "<f4", 3),
                                          ("d", "<f4"), ("c", "<f4")])
            for i in range(0, len(q), 1024):
                block = q[i:i + 1024]
                dd = ((block[:, None, :] - body_lo[None]) ** 2).sum(-1)
                a = np.argsort(dd, 1)[:, :ANCHORS]
                w = 1 / np.maximum(np.sqrt(np.take_along_axis(dd, a, 1)), 1e-4)
                w /= w.sum(1, keepdims=True)
                rec["a"][i:i + 1024] = a
                rec["w"][i:i + 1024] = w
                rec["o"][i:i + 1024] = block - (w[..., None] * body_lo[a]).sum(1)
            rec["d"] = np.linalg.norm(q - centre, axis=1)
            rec["c"] = cls
            f.write(rec.tobytes() + idx.astype("<u2").tobytes())
            print(f"{name}: {len(q)} verts, {idx.size} indices")
