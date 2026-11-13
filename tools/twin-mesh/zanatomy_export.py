"""Run by Blender: Z-Anatomy's major nerves as world polylines and some bone centroids, to JSON.

blender --background Startup.blend --python zanatomy_export.py -- OUT.json (anatomy.py runs it).
"""

import json
import sys

import bpy

MAJOR = ("plexus", "axillary nerve", "musculocutaneous nerve", "median nerve", "ulnar nerve", "radial nerve",
         "intercostal nerve", "femoral nerve", "obturator nerve", "sciatic nerve", "tibial nerve",
         "fibular nerve", "plantar nerve", "saphenous nerve", "sural nerve", "phrenic nerve", "vagus nerve",
         "spinal nerve", "cervical nerve", "thoracic nerve", "lumbar nerve", "sacral nerve", "pudendal nerve")
SKIP = ("digital", "cutaneous", "communicating", "artery", "vein", "articular", "muscular branch")
BONES = ("Femur.l", "Femur.r", "Humerus.l", "Humerus.r", "Tibia.l", "Tibia.r", "Radius.l", "Radius.r", "Atlas (C1)")


def main(out: str) -> None:
    col = bpy.data.collections["7: Nervous system & Sense organs"]
    dg = bpy.context.evaluated_depsgraph_get()
    nerves = {}
    for o in col.all_objects:
        name = o.name.lower()
        if o.type != "CURVE" or not any(k in name for k in MAJOR) or any(k in name for k in SKIP):
            continue
        # No bevel: the evaluated mesh is then the centreline itself.
        o.data.bevel_depth = 0.0
        o.data.bevel_object = None
        o.data.extrude = 0.0
        dg.update()
        m = o.evaluated_get(dg).to_mesh()
        pts = [list(o.matrix_world @ v.co) for v in m.vertices]
        edges = [list(e.vertices) for e in m.edges]
        o.evaluated_get(dg).to_mesh_clear()
        if edges:
            nerves[o.name] = {"pts": pts, "edges": edges}
    bones = {}
    for n in BONES:
        o = bpy.data.objects.get(n)
        if o and o.type == "MESH":
            vs = [o.matrix_world @ v.co for v in o.data.vertices]
            bones[n] = [sum(v[i] for v in vs) / len(vs) for i in range(3)]
    with open(out, "w") as f:
        json.dump({"nerves": nerves, "bones": bones}, f)


main(sys.argv[sys.argv.index("--") + 1])
