package com.t1dm.inference

import com.t1dm.core.model.MaskGeometry

/** §4's 3 geometries per window; forecast=half, the only guard-measurable shape; positional. */
internal object LoraGeometryPlan {
    fun geometryAt(index: Int): MaskGeometry = when (index % 4) {
        0, 1 -> MaskGeometry.FORECAST
        2 -> MaskGeometry.INFILL
        else -> MaskGeometry.BACKCAST
    }

    /** Masked-run startPatch: infill=middle, backcast=left edge; null if ctx too small. */
    fun startPatch(geometry: MaskGeometry, ctxPatches: Int, lenPatches: Int): Int? = when {
        lenPatches < 1 -> null
        geometry == MaskGeometry.BACKCAST -> if (ctxPatches >= lenPatches + 1) 0 else null
        geometry == MaskGeometry.INFILL ->
            if (ctxPatches >= lenPatches + 2) (ctxPatches - lenPatches) / 2 else null
        else -> null
    }
}

/** Series index, mirrors build_graph_input: left-preferring else right neighbour's FIRST step. */
internal fun anchorStepOf(
    ctxFrom: Int,                      // window's first context step
    o: Int,                            // one past the window's last step
    startPatch: Int?,                   // null = trailing forecast
    lenPatches: Int,
    patchSize: Int,
): Int {
    if (startPatch == null) return o - 1
    if (startPatch > 0) return ctxFrom + startPatch * patchSize - 1
    return ctxFrom + (startPatch + lenPatches) * patchSize
}
