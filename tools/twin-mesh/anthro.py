"""Per-sex linear models of body measures from stature (cm), mass (kg) and age (y).

ANSUR II (Gordon et al. 2014, NATICK/TR-15/007; US Army, public domain): lengths, breadths, girths.
NHANES 2017-2018 body measures (CDC NCHS, public domain): waist, hip and upper-arm girth.
"""

import json
import urllib.request
from pathlib import Path

import numpy as np

URLS = {
    "ansur2_male.csv": "https://tools.openlab.psu.edu/publicData/ANSUR_II_MALE_Public.csv",
    "ansur2_female.csv": "https://tools.openlab.psu.edu/publicData/ANSUR_II_FEMALE_Public.csv",
    "BMX_J.xpt": "https://wwwn.cdc.gov/Nchs/Data/Nhanes/Public/2017/DataFiles/BMX_J.xpt",
    "DEMO_J.xpt": "https://wwwn.cdc.gov/Nchs/Data/Nhanes/Public/2017/DataFiles/DEMO_J.xpt",
}

# Ruler measure -> ANSUR II column (mm).
ANSUR = {
    "crotch_h": "crotchheight",
    "knee_h": "kneeheightmidpatella",
    "ankle_h": "lateralmalleolusheight",
    "buttock_h": "buttockheight",
    "navel_h": "waistheightomphalion",
    "chest_h": "chestheight",
    "acromion_h": "acromialheight",
    "cervicale_h": "cervicaleheight",
    "shoulder_w": "biacromialbreadth",
    "upperarm_l": "acromionradialelength",
    "forearm_l": "radialestylionlength",
    "chest_w": "chestbreadth",
    "chest_d": "chestdepth",
    "chest_c": "chestcircumference",
    "waist_w": "waistbreadth",
    "waist_d": "waistdepth",
    "hip_w": "hipbreadth",
    "hip_d": "buttockdepth",
    "thigh_c": "thighcircumference",
    "calf_c": "calfcircumference",
    "neck_c": "neckcircumference",
}

# Ruler measure -> NHANES BMX column (cm); a general population spans the BMI and age range.
NHANES = {"waist_c": "BMXWAIST", "hip_c": "BMXHIP", "upperarm_c": "BMXARMC"}


def _download(cache: Path, name: str) -> Path:
    dst = cache / name
    if not dst.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(URLS[name], headers={"User-Agent": "twin-mesh"})
        dst.write_bytes(urllib.request.urlopen(req, timeout=120).read())
    return dst


def _ols(x: np.ndarray, y: np.ndarray) -> tuple[list[float], float]:
    a = np.column_stack([np.ones(len(x)), x])
    coef, *_ = np.linalg.lstsq(a, y, rcond=None)
    r2 = 1 - ((a @ coef - y) ** 2).sum() / ((y - y.mean()) ** 2).sum()
    return [float(c) for c in coef], float(r2)


def fit(cache: Path) -> dict:
    """Coefficients [intercept, per cm stature, per kg, per year] in cm, with R² and n."""
    import pandas as pd

    out: dict = {"inputs": ["stature_cm", "mass_kg", "age_y"], "sources": URLS, "measures": {}}
    for sex in ("female", "male"):
        df = pd.read_csv(_download(cache, f"ansur2_{sex}.csv"), encoding="latin-1")
        x = np.column_stack([df["stature"] / 10, df["weightkg"] / 10, df["Age"]])
        for name, col in ANSUR.items():
            coef, r2 = _ols(x, df[col].to_numpy() / 10)
            out["measures"].setdefault(name, {"source": "ANSUR II"})[sex] = {"coef": coef, "r2": r2, "n": len(df)}

    bmx = pd.read_sas(_download(cache, "BMX_J.xpt"))
    demo = pd.read_sas(_download(cache, "DEMO_J.xpt"))
    df = bmx.merge(demo[["SEQN", "RIAGENDR", "RIDAGEYR", "RIDEXPRG"]], on="SEQN")
    df = df[(df["RIDAGEYR"] >= 18) & (df["RIDEXPRG"] != 1)]
    for sex, code in (("female", 2), ("male", 1)):
        s = df[df["RIAGENDR"] == code]
        for name, col in NHANES.items():
            t = s.dropna(subset=["BMXHT", "BMXWT", "RIDAGEYR", col])
            x = np.column_stack([t["BMXHT"], t["BMXWT"], t["RIDAGEYR"]])
            coef, r2 = _ols(x, t[col].to_numpy())
            out["measures"].setdefault(name, {"source": "NHANES 2017-2018"})[sex] = {"coef": coef, "r2": r2, "n": len(t)}
    return out


def predict(model: dict, name: str, stature: float, mass: float, age: float, male: float) -> float:
    """P(male) blends the two sexes' predictions linearly."""
    m = model["measures"][name]

    def one(sex):
        c = m[sex]["coef"]
        return c[0] + c[1] * stature + c[2] * mass + c[3] * age

    return (1 - male) * one("female") + male * one("male")


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    a.out.write_text(json.dumps(fit(a.cache), indent=1) + "\n")
