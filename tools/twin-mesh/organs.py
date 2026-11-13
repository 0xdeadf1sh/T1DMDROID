"""Heart and brain from BodyParts3D (CC BY 4.0, DBCLS), anchored to body vertices to follow morphs.

TWO2 (little-endian) layout: see `write`. Motion class: 0 atrium, 1 ventricle, 2 still.
"""

import struct
import urllib.request
import zipfile
from pathlib import Path

import fast_simplification
import numpy as np

BASE = "https://dbarchive.biosciencedbc.jp/data/bodyparts3d/LATEST/"
FILES = ("partof_parts_list_e.txt", "partof_element_parts.txt", "partof_BP3D_4.0_obj_99.zip")
ANCHORS = 12
ATRIA = ("right atrium", "left atrium")
VENTRICLES = ("right ventricle", "left ventricle")
STILL = 2.0
# Skull and scalp between brain and skin, dm.
SKULL = 0.07
# Triangle budgets after decimation: the heart is drawn as faces, the brain also as edge lines.
TRIS = {"heart": 6000, "brain": 8000}


class BP3D:
    """BodyParts3D's PART-OF tree: compound organ name → element meshes, in mm, z up."""

    def __init__(self, cache: Path):
        d = cache / "bp3d"
        d.mkdir(parents=True, exist_ok=True)
        for name in FILES:
            if not (d / name).exists():
                with urllib.request.urlopen(BASE + name) as r:
                    (d / name).write_bytes(r.read())
        self.elements: dict[str, list[str]] = {}
        for line in (d / "partof_element_parts.txt").read_text().splitlines()[1:]:
            _, name, fid = line.split("\t")
            self.elements.setdefault(name, []).append(fid)
        self.zip = zipfile.ZipFile(d / FILES[2])
        self.members = {Path(n).stem: n for n in self.zip.namelist() if n.endswith(".obj")}

    def mesh(self, fid: str):
        v, f = [], []
        for line in self.zip.read(self.members[fid]).decode().splitlines():
            p = line.split()
            if not p:
                continue
            if p[0] == "v":
                v.append([float(x) for x in p[1:4]])
            elif p[0] == "f":
                idx = [int(t.split("/")[0]) - 1 for t in p[1:]]
                f.extend([idx[0], idx[i], idx[i + 1]] for i in range(1, len(idx) - 1))
        return np.asarray(v), np.asarray(f, np.int64)

    def organ(self, name: str):
        """Every element of [name] merged; per vertex the element id it came from."""
        pos, tri, src, base = [], [], [], 0
        for fid in self.elements[name]:
            if fid not in self.members:
                continue
            v, f = self.mesh(fid)
            pos.append(v)
            tri.append(f + base)
            src += [fid] * len(v)
            base += len(v)
        return np.concatenate(pos), np.concatenate(tri), np.asarray(src)


def body_axes(bp: BP3D) -> np.ndarray:
    """Rows: the body's left, up and forward in BP3D coordinates, read off the brain's anatomy."""
    left = bp.organ("left cerebral hemisphere")[0].mean(0)
    right = bp.organ("right cerebral hemisphere")[0].mean(0)
    back = bp.organ("cerebellum")[0].mean(0)
    x = left - right
    x /= np.linalg.norm(x)
    up = np.array([0.0, 0.0, 1.0])
    z = np.cross(x, up)
    if np.dot((left + right) / 2 - back, z) < 0:
        z = -z
    return np.stack([x, up, z])


def decimate(pos, tri, n):
    if len(tri) <= n:
        return pos, tri, np.arange(len(pos))
    lo, lt = fast_simplification.simplify(pos.astype(np.float32), tri.astype(np.int32), 1 - n / len(tri))
    # Chunked: the full low × high distance matrix runs to gigabytes.
    near = np.concatenate([
        np.argmin(((lo[i:i + 256, None, :] - pos[None, :, :]) ** 2).sum(-1), axis=1)
        for i in range(0, len(lo), 256)
    ])
    return lo.astype(np.float64), lt.astype(np.int64), near


def heart(bp: BP3D, axes: np.ndarray):
    """Heart in dm about its centroid; motion carries each chamber's centre and class."""
    pos, tri, src = bp.organ("heart")
    pos = pos @ axes.T / 100.0
    pos -= pos.mean(0)
    motion = np.zeros((len(pos), 4))
    motion[:, 3] = STILL
    for cls, names in ((0.0, ATRIA), (1.0, VENTRICLES)):
        for name in names:
            mine = np.isin(src, bp.elements[name])
            if mine.any():
                motion[mine, :3] = pos[mine].mean(0)
                motion[mine, 3] = cls
    # The coronary vessels lie on the chambers' surface: each moves with the chamber under it.
    still = np.nonzero(motion[:, 3] == STILL)[0]
    moving = np.nonzero(motion[:, 3] != STILL)[0]
    for i in range(0, len(still), 256):
        block = still[i:i + 256]
        d = ((pos[block, None, :] - pos[None, moving, :]) ** 2).sum(-1)
        motion[block] = motion[moving[np.argmin(d, axis=1)]]
    lo, lt, near = decimate(pos, tri, TRIS["heart"])
    return lo, motion[near], lt


def brain(bp: BP3D, axes: np.ndarray):
    pos, tri, _ = bp.organ("brain")
    pos = pos @ axes.T / 100.0
    pos -= pos.mean(0)
    lo, lt, _ = decimate(pos, tri, TRIS["brain"])
    motion = np.zeros((len(lo), 4))
    motion[:, 3] = STILL
    return lo, motion, lt


def heart_centre(body: np.ndarray) -> np.ndarray:
    """Behind the sternum, a little to the body's left."""
    top = body[:, 1].max()
    y = top - 0.27 * (top - body[:, 1].min())
    band = body[(np.abs(body[:, 1] - y) < 0.15) & (np.abs(body[:, 0]) < 0.6)]
    return np.array([0.12, y, (band[:, 2].max() + band[:, 2].min()) / 2])


def fit_brain(body: np.ndarray, eyes, p: np.ndarray):
    """Scaled (never up) to clear the cranium by SKULL, top under the crown → (p, centre)."""
    eye_y = (eyes[0][1] + eyes[1][1]) / 2
    cranium = body[(body[:, 1] > eye_y + 0.15) & (np.abs(body[:, 0]) < 1.2)]
    lo, hi = cranium.min(0), cranium.max(0)
    blo, bhi = p.min(0), p.max(0)
    s = min(1.0, (hi[0] - lo[0] - 2 * SKULL) / (bhi[0] - blo[0]), (hi[2] - lo[2] - 2 * SKULL) / (bhi[2] - blo[2]))
    p = p * s
    centre = np.array([
        (lo[0] + hi[0]) / 2 - (blo[0] + bhi[0]) / 2 * s,
        hi[1] - SKULL - bhi[1] * s,
        (lo[2] + hi[2]) / 2 - (blo[2] + bhi[2]) / 2 * s,
    ])
    print(f"brain scaled {s:.2f} to fit the cranium")
    return p, centre


def anchors(body: np.ndarray, centre: np.ndarray):
    d = np.linalg.norm(body - centre, axis=1)
    idx = np.argsort(d)[:ANCHORS]
    w = 1 / np.maximum(d[idx], 1e-6)
    w /= w.sum()
    return idx, w, centre - (w[:, None] * body[idx]).sum(0)


def write(path, body: np.ndarray, eyes, cache: Path) -> None:
    """u32 count; per organ: u8 name length, name, u32 nv, u32 nt, nv×3 f32 position (dm, about
    the centre), nv×4 f32 motion, nt×3 u16, u32 k, k u16 anchors, k f32 weights, 3 f32 offset."""
    bp = BP3D(cache)
    axes = body_axes(bp)
    hp, hm, ht = heart(bp, axes)
    bp_, bm, bt = brain(bp, axes)
    bp_, brain_c = fit_brain(body, eyes, bp_)
    meshes = {"heart": (hp, hm, ht), "brain": (bp_, bm, bt)}
    cs = {"heart": heart_centre(body), "brain": brain_c}
    with open(path, "wb") as f:
        f.write(b"TWO2" + struct.pack("<I", len(meshes)))
        for name, (p, m, t) in meshes.items():
            idx, w, off = anchors(body, cs[name])
            f.write(struct.pack("<B", len(name)) + name.encode())
            f.write(struct.pack("<II", len(p), len(t)))
            f.write(p.astype("<f4").tobytes() + m.astype("<f4").tobytes() + t.astype("<u2").tobytes())
            f.write(struct.pack("<I", len(idx)) + idx.astype("<u2").tobytes() + w.astype("<f4").tobytes())
            f.write(off.astype("<f4").tobytes())
            ext = (p.max(0) - p.min(0)) * 10
            print(f"{name}: {len(p)} verts, {len(t)} tris, {np.round(ext, 1)} cm, centre {np.round(cs[name], 2)}")
