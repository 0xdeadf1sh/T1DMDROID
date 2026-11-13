"""MediaPipe's canonical face (Apache-2.0) laid onto the body's head, for the face texture.

The face texture lives in the canonical model's UV space; each body vertex on the front of the
head gets the canonical UV under it in a front projection, others get NO_UV.
"""

import urllib.request
from pathlib import Path

import numpy as np

URL = (
    "https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/"
    "mediapipe/modules/face_geometry/data/canonical_face_model.obj"
)
NO_UV = -1.0
EYE_R = (33, 133)
EYE_L = (362, 263)
CHIN = 152


class Canon:
    """468 canonical vertices (cm, y up, +z toward the viewer), per-vertex UV (v up), triangles."""

    def __init__(self, cache: Path):
        path = cache / "mediapipe/canonical_face_model.obj"
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(URL) as r:
                path.write_bytes(r.read())
        v, vt, tri, tex = [], [], [], []
        for line in path.read_text().splitlines():
            p = line.split()
            if not p:
                continue
            if p[0] == "v":
                v.append([float(x) for x in p[1:4]])
            elif p[0] == "vt":
                vt.append([float(x) for x in p[1:3]])
            elif p[0] == "f":
                corners = [c.split("/") for c in p[1:4]]
                tri.append([int(c[0]) - 1 for c in corners])
                tex.append([int(c[1]) - 1 for c in corners])
        self.pos = np.asarray(v)
        self.tri = np.asarray(tri, dtype=np.int64)
        uv = np.zeros((len(v), 2))
        uv[self.tri.ravel()] = np.asarray(vt)[np.asarray(tex, dtype=np.int64).ravel()]
        self.uv = uv


def _normals(pos: np.ndarray, tri: np.ndarray) -> np.ndarray:
    n = np.zeros_like(pos)
    fn = np.cross(pos[tri[:, 1]] - pos[tri[:, 0]], pos[tri[:, 2]] - pos[tri[:, 0]])
    for k in range(3):
        np.add.at(n, tri[:, k], fn)
    return n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)


def _bary(p: np.ndarray, a: np.ndarray, b: np.ndarray, c: np.ndarray) -> np.ndarray:
    """Barycentric (n_points × n_tris × 3) of 2D points in 2D triangles."""
    v0, v1 = b - a, c - a
    d = v0[:, 0] * v1[:, 1] - v1[:, 0] * v0[:, 1]
    q = p[:, None, :] - a[None]
    l1 = (q[..., 0] * v1[:, 1] - v1[:, 0] * q[..., 1]) / d
    l2 = (v0[:, 0] * q[..., 1] - q[..., 0] * v0[:, 1]) / d
    return np.stack([1 - l1 - l2, l1, l2], axis=-1)


def chin_height(pos: np.ndarray, eye_mid: np.ndarray, drop: float) -> float:
    """Lowest forward midline point between 0.7 and 1.4 × the guessed eye-to-chin [drop]."""
    band = (
        (np.abs(pos[:, 0] - eye_mid[0]) < 0.06)
        & (pos[:, 1] < eye_mid[1] - 0.7 * drop)
        & (pos[:, 1] > eye_mid[1] - 1.4 * drop)
    )
    pts = pos[band]
    front = pts[pts[:, 2] > pts[:, 2].max() - 0.2]
    return float(front[:, 1].min())


def fit_2d(pos: np.ndarray, eyes: tuple[np.ndarray, np.ndarray], canon: Canon) -> np.ndarray:
    """Canonical vertices in the body's front view: eyes onto eyes, eye-to-chin span matched."""
    c = canon.pos
    c_r, c_l = c[list(EYE_R)].mean(0), c[list(EYE_L)].mean(0)
    sx = np.linalg.norm((eyes[1] - eyes[0])[:2]) / np.linalg.norm((c_l - c_r)[:2])
    mid_b, mid_c = (eyes[0] + eyes[1]) / 2, (c_r + c_l) / 2
    chin = chin_height(pos, mid_b, sx * (mid_c[1] - c[CHIN, 1]))
    sy = (mid_b[1] - chin) / (mid_c[1] - c[CHIN, 1])
    return np.column_stack([(c[:, 0] - mid_c[0]) * sx + mid_b[0], (c[:, 1] - mid_c[1]) * sy + mid_b[1]])


def face_uv(pos: np.ndarray, tri: np.ndarray, eyes: tuple[np.ndarray, np.ndarray], canon: Canon) -> np.ndarray:
    """Per-vertex canonical UV for [pos] (decimetres, y up, +z forward); NO_UV off the face.

    [eyes] are the right and left eyeball centres.
    """
    fit = fit_2d(pos, eyes, canon)
    mid = (eyes[0] + eyes[1]) / 2
    chin = fit[CHIN, 1]
    n = _normals(pos, tri)
    near = (pos[:, 1] > chin - 0.2 * (mid[1] - chin)) & (pos[:, 2] > mid[2] - 0.6)
    uv = np.full((len(pos), 2), NO_UV)
    ft = canon.tri
    idx = np.nonzero(near)[0]
    w = _bary(pos[idx, :2], fit[ft[:, 0]], fit[ft[:, 1]], fit[ft[:, 2]])
    inside = (w >= -1e-6).all(-1)
    # Inside the face, nostrils, lip seams and eyeballs keep their UV whichever way they face.
    keep = inside.any(1) | (n[idx, 2] > 0.15)
    # Off the face: extrapolate the least-outside triangle; those texels have alpha 0.
    pick = np.where(inside.any(1), inside.argmax(1), w.min(-1).argmax(1))
    wp = w[np.arange(len(idx)), pick]
    uv[idx[keep]] = np.einsum("nk,nkd->nd", wp, canon.uv[ft[pick]])[keep]
    return uv
