#!/usr/bin/env python3
"""Low-poly twin body and macro morphs from MakeHuman's CC0 assets; layout in TwinBody.kt.

Usage: python3 -I build.py --cache DIR --out feature/twin/src/main/assets/twin_body.bin
Morphs map onto the simplified mesh through the nearest base vertex.
"""

import argparse
import struct
import urllib.request
from pathlib import Path

import fast_simplification
import numpy as np

RAW = "https://raw.githubusercontent.com/makehumancommunity/makehuman/master/makehuman/data"
ETHNIC = ("african", "asian", "caucasian")
GENDERS = ("female", "male")
TRIS = 3600


def fetch(cache: Path, rel: str) -> Path:
    dst = cache / rel
    if not dst.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(f"{RAW}/{rel}") as r:
            dst.write_bytes(r.read())
    return dst


def load_body(path: Path):
    verts, faces, group = [], [], None
    for line in path.read_text().splitlines():
        if line.startswith("v "):
            verts.append([float(x) for x in line.split()[1:4]])
        elif line.startswith("g "):
            group = line.split(maxsplit=1)[1].strip()
        elif line.startswith("f ") and group == "body":
            idx = [int(tok.split("/")[0]) - 1 for tok in line.split()[1:]]
            faces.extend([idx[0], idx[i], idx[i + 1]] for i in range(1, len(idx) - 1))
    return np.asarray(verts, np.float64), np.asarray(faces, np.int64)


def load_target(path: Path, n: int) -> np.ndarray:
    d = np.zeros((n, 3))
    for line in path.read_text().splitlines():
        if line and not line.startswith("#"):
            i, x, y, z = line.split()
            d[int(i)] = (float(x), float(y), float(z))
    return d


def morphs(cache: Path, n: int) -> tuple[np.ndarray, dict[str, np.ndarray]]:
    """Neutral is MakeHuman's default human; each morph is its delta at weight 1."""

    def t(name: str) -> np.ndarray:
        return load_target(fetch(cache, f"targets/macrodetails/{name}.target"), n)

    young = {(e, g): t(f"{e}-{g}-young") for e in ETHNIC for g in GENDERS}
    old = {(e, g): t(f"{e}-{g}-old") for e in ETHNIC for g in GENDERS}
    neutral = sum(young.values()) / 6
    male = sum(young[(e, "male")] for e in ETHNIC) / 3
    female = sum(young[(e, "female")] for e in ETHNIC) / 3

    def universal(muscle: str, weight: str) -> np.ndarray:
        return np.mean([t(f"universal-{g}-young-{muscle}-{weight}") for g in GENDERS], axis=0)

    def height(extreme: str) -> np.ndarray:
        rel = "height/{}-young-averagemuscle-averageweight-{}"
        return np.mean([t(rel.format(g, extreme)) for g in GENDERS], axis=0)

    avg = universal("averagemuscle", "averageweight")
    out = {
        "sex": (male - female) / 2,
        "age": sum(old.values()) / 6 - neutral,
        "weight_up": universal("averagemuscle", "maxweight") - avg,
        "weight_down": universal("averagemuscle", "minweight") - avg,
        "muscle_up": universal("maxmuscle", "averageweight") - avg,
        "muscle_down": universal("minmuscle", "averageweight") - avg,
        "height_up": height("maxheight"),
        "height_down": height("minheight"),
    }
    return neutral + avg, out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()

    base, faces = load_body(fetch(a.cache, "3dobjs/base.obj"))
    neutral_delta, deltas = morphs(a.cache, len(base))
    used = np.unique(faces)
    remap = np.full(len(base), -1)
    remap[used] = np.arange(len(used))
    pos = (base + neutral_delta)[used]
    tri = remap[faces]

    lo_pos, lo_tri = fast_simplification.simplify(
        pos.astype(np.float32), tri.astype(np.int32), target_reduction=1 - TRIS / len(tri)
    )
    nearest = np.argmin(((lo_pos[:, None, :] - pos[None, :, :]) ** 2).sum(-1), axis=1)
    assert len(lo_pos) < 65536, "indices are u16"

    with a.out.open("wb") as f:
        f.write(b"TWN1")
        f.write(struct.pack("<III", len(lo_pos), len(lo_tri), len(deltas)))
        f.write(lo_pos.astype("<f4").tobytes())
        f.write(lo_tri.astype("<u2").tobytes())
        for name, d in deltas.items():
            f.write(struct.pack("<B", len(name)) + name.encode())
            f.write(d[used][nearest].astype("<f4").tobytes())
    print(f"{len(lo_pos)} verts, {len(lo_tri)} tris, {len(deltas)} morphs -> {a.out}")


if __name__ == "__main__":
    main()
