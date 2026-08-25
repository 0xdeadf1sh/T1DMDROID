package com.t1dm.inference

import com.t1dm.core.model.MaskGeometry

/** Which of `SPEC/inference.md` §4's three geometries each replayed window is built at. Forecast
 *  takes half: it is the only shape the counterfactual guard can measure on. Positional, not random,
 *  so each geometry falls in the same proportion either side of the chronological split. */
internal object LoraGeometryPlan {
    fun geometryAt(index: Int): MaskGeometry = when (index % 4) {
        0, 1 -> MaskGeometry.FORECAST
        2 -> MaskGeometry.INFILL
        else -> MaskGeometry.BACKCAST
    }

    /** `startPatch` of the masked run: the middle for an infill, the left edge for a backcast. Null
     *  when the context cannot hold [lenPatches] with a patch to spare on each side. */
    fun startPatch(geometry: MaskGeometry, ctxPatches: Int, lenPatches: Int): Int? = when {
        lenPatches < 1 -> null
        geometry == MaskGeometry.BACKCAST -> if (ctxPatches >= lenPatches + 1) 0 else null
        geometry == MaskGeometry.INFILL ->
            if (ctxPatches >= lenPatches + 2) (ctxPatches - lenPatches) / 2 else null
        else -> null
    }
}

/** Index into the whole series; mirrors `build_graph_input` — left-preferring, else the right
 *  neighbour's FIRST step. [ctxFrom] is the window's first context step, [o] one past its last.
 *  A null [startPatch] is a trailing forecast. */
internal fun anchorStepOf(
    ctxFrom: Int,
    o: Int,
    startPatch: Int?,
    lenPatches: Int,
    patchSize: Int,
): Int {
    if (startPatch == null) return o - 1
    if (startPatch > 0) return ctxFrom + startPatch * patchSize - 1
    return ctxFrom + (startPatch + lenPatches) * patchSize
}
