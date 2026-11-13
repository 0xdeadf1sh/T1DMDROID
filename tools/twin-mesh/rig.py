"""MakeHuman's default skeleton and skin weights (CC0), collapsed to the bones the idle clips move.

Every dropped bone hands its weights to its nearest kept ancestor. A pivot is the mean of its joint
helper's vertices, so it is linear in the morphs like the skin it sits in.
"""

import json
from pathlib import Path

import numpy as np

# Order is the asset's bone order; TwinRig.kt names the same bones. Parents come before children.
KEPT = (
    "root", "spine05", "spine03", "spine01", "neck01", "head",
    "clavicle.L", "upperarm01.L", "lowerarm01.L", "wrist.L",
    "clavicle.R", "upperarm01.R", "lowerarm01.R", "wrist.R",
    "upperleg01.L", "lowerleg01.L", "foot.L",
    "upperleg01.R", "lowerleg01.R", "foot.R",
)
INFLUENCES = 4


class Rig:
    def __init__(self, skel: Path, weights: Path):
        s = json.loads(skel.read_text())
        self.bones = s["bones"]
        self.joints = {k: np.asarray(v, np.int64) for k, v in s["joints"].items()}
        self.weights = json.loads(weights.read_text())["weights"]

    def kept_of(self, bone: str) -> str:
        while bone not in KEPT:
            bone = self.bones[bone]["parent"]
        return bone

    def parents(self) -> list[int]:
        out = []
        for name in KEPT:
            p = self.bones[name]["parent"]
            out.append(-1 if p is None else KEPT.index(self.kept_of(p)))
        assert all(p < i for i, p in enumerate(out)), "parents must precede children"
        return out

    def pivot(self, name: str, field: np.ndarray) -> np.ndarray:
        """Mean of the bone's head joint over a per-vertex [field] (positions or a morph delta)."""
        return field[self.joints[self.bones[name]["head"]]].mean(0)

    def skin(self, n_verts: int) -> tuple[np.ndarray, np.ndarray]:
        """Per vertex: the [INFLUENCES] heaviest kept bones (u8) and their weights, summing to 1."""
        dense = np.zeros((n_verts, len(KEPT)))
        for bone, pairs in self.weights.items():
            k = KEPT.index(self.kept_of(bone))
            for v, w in pairs:
                dense[v, k] += w
        idx = np.argsort(-dense, axis=1)[:, :INFLUENCES]
        w = np.take_along_axis(dense, idx, axis=1)
        unweighted = w.sum(1) <= 0
        idx[unweighted, 0], w[unweighted, 0] = 0, 1.0
        return idx.astype(np.uint8), w / w.sum(1, keepdims=True)
