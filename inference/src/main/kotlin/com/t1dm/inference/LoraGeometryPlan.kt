package com.t1dm.inference

import com.t1dm.core.model.MaskGeometry

/**
 * Which of `SPEC/inference.md` §4's three geometries each replayed window is built at.
 *
 * **Why a mix.** The model has one objective and three input shapes, and an adapter fitted through
 * one of them is applied through all three: `runMasked` reconstructs at infill and backcast
 * geometry, and those reconstructions are promotable into the patient's record. A fit that only
 * ever saw the trailing-forecast shape is being asked, at every fill, about a shape it has never
 * been corrected on.
 *
 * **Why forecast still dominates.** A forecast window is the only one the counterfactual guard can
 * measure on — the response is read at the horizon's terminal step, and the terminal step of an
 * infill is not one — and it is also the shape the live 5-minute cycle actually runs. Half the
 * windows keep it, so the guard keeps its evidence and the pairing cost stays near half of what
 * pairing every window would cost.
 *
 * The plan is positional rather than random: the replay's held-out split is chronological, so a
 * fixed cycle puts the same proportion of each geometry either side of it, which a draw would not.
 */
internal object LoraGeometryPlan {
    /** forecast, forecast, infill, backcast — repeating. */
    fun geometryAt(index: Int): MaskGeometry = when (index % 4) {
        0, 1 -> MaskGeometry.FORECAST
        2 -> MaskGeometry.INFILL
        else -> MaskGeometry.BACKCAST
    }

    /**
     * Where the masked run sits inside a context of [ctxPatches], as `startPatch` — the middle for
     * an infill, the left edge for a backcast, which is the whole of what distinguishes them: a
     * backcast has nothing bracketing it on the left and must be reconstructed from one side.
     *
     * Returns null when the context cannot hold a run of [lenPatches] with a patch to spare on each
     * side, which would make the span the window rather than a run within it.
     */
    fun startPatch(geometry: MaskGeometry, ctxPatches: Int, lenPatches: Int): Int? = when {
        lenPatches < 1 -> null
        geometry == MaskGeometry.BACKCAST -> if (ctxPatches >= lenPatches + 1) 0 else null
        geometry == MaskGeometry.INFILL ->
            if (ctxPatches >= lenPatches + 2) (ctxPatches - lenPatches) / 2 else null
        else -> null
    }
}

/**
 * The one step a replayed window's masked run anchors on, as an index into the whole series.
 *
 * Mirrors `build_graph_input`'s own rule — left-preferring, and the right neighbour's FIRST step
 * when there is no left one — rather than restating it as a fresh convention. [startPatch] null is
 * a trailing forecast, whose left neighbour is the last context patch, so the anchor is the last
 * context step.
 *
 * @param ctxFrom index of the window's first context step
 * @param o index one past its last context step
 */
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
