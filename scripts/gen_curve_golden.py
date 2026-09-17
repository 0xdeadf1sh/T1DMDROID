"""Regenerates crates/t1dm-core/src/testdata/curve_golden.json from the sibling T1DMSIM checkout."""

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "T1DMSIM"))
import simulator as S  # noqa: E402

rapid = S.BOLUS_VARIANTS["aspart"]
ultra = S.BOLUS_VARIANTS["faster_aspart"]
golden = {
    "gamma": [
        {"total": t, "k": k, "theta": th, "dur": d, "values": S.gamma_curve(t, k, th, d).tolist()}
        for t, k, th, d in [(60.0, 2.0, 15.0, 120.0), (45.0, 3.25, 22.5, 292.5), (5.0, 3.0, 45.0, 336.0),
                            (15.0, 2.55, 66.5, 360.0), (0.0, 3.0, 20.0, 100.0), (1.0, 3.0, 20.0, 3.0)]
    ],
    "bateman": [
        {"total": t, "dur": v["action_hours"] * 60.0, "ka": v["ka"], "ke": v["ke"],
         "values": S.basal_curve(t, v["action_hours"] * 60.0, v["ka"], v["ke"]).tolist()}
        for t, v in [(24.0, S.BASAL_VARIANTS["glargine_u100"]), (30.0, S.BASAL_VARIANTS["glargine_u300"]),
                     (18.0, S.BASAL_VARIANTS["degludec"])]
    ] + [{"total": 24.0, "dur": 1440.0, "ka": 0.3, "ke": 0.07,
          "values": S.basal_curve(24.0, 1440.0, 0.3, 0.07).tolist()}],
    "bolus_pk": [
        dict(zip(("k", "theta", "dur"), S.bolus_pk_for_dose(dose, v["gamma_k"], v["gamma_theta"], v["dia_base_hours"])),
             dose=dose, base_k=v["gamma_k"], base_theta=v["gamma_theta"], base_dia_h=v["dia_base_hours"])
        for v in (rapid, ultra) for dose in (0.1, 0.5, 1.0, 3.0, 5.0, 7.0, 15.0, 30.0, 80.0)
    ],
    "gi_gamma": [dict(zip(("k", "theta", "dur"), S.gi_gamma_params(gi)), gi=gi) for gi in (-5.0, 0.0, 31.0, 50.0, 85.0, 100.0, 120.0)],
}
out = ROOT / "crates/t1dm-core/src/testdata/curve_golden.json"
out.write_text(json.dumps(golden, indent=1) + "\n")
