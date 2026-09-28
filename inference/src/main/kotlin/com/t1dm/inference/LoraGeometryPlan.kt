package com.t1dm.inference

import com.t1dm.core.model.MaskGeometry

/** SPEC/inference.md §4 geometries, positional. */
internal object LoraGeometryPlan {
    /** startPatch: middle for infill, left edge for backcast; null if no spare patch each side. */
    fun startPatch(geometry: MaskGeometry, ctxPatches: Int, lenPatches: Int): Int? = when {
        lenPatches < 1 -> null
        geometry == MaskGeometry.BACKCAST -> if (ctxPatches >= lenPatches + 1) 0 else null
        geometry == MaskGeometry.INFILL ->
            if (ctxPatches >= lenPatches + 2) (ctxPatches - lenPatches) / 2 else null
        else -> null
    }
}

/** Steps from a window's first context step: the predicted span and the whole window. */
internal class LoraSpanShape(
    val ctxSteps: Int,
    val spanOff: Int,
    val spanLen: Int,
    val winLen: Int,
    /** Null for a forecast. */
    val startPatch: Int?,
    val spanPatches: Int,
)

/** Forecast horizon capped where context has room, so the kinds pose a comparable question. */
internal fun loraSpanShape(
    kind: MaskGeometry,
    ctxPatches: Int,
    patchSize: Int,
    predSteps: Int,
    maskSpanMax: Int,
): LoraSpanShape? {
    val ctxSteps = ctxPatches * patchSize
    if (kind == MaskGeometry.FORECAST) {
        if (predSteps <= 0) return null
        return LoraSpanShape(ctxSteps, ctxSteps, predSteps, ctxSteps + predSteps, null, 0)
    }
    val spanPatches = (predSteps / patchSize).coerceIn(1, maskSpanMax.coerceAtLeast(1))
    val start = LoraGeometryPlan.startPatch(kind, ctxPatches, spanPatches) ?: return null
    return LoraSpanShape(ctxSteps, start * patchSize, spanPatches * patchSize, ctxSteps, start, spanPatches)
}

/** Onsets chained while each is ≤ [mergeMs] after the last; each cluster as first..last ms. */
internal fun eventClusters(sortedOnsets: LongArray, mergeMs: Long): List<LongRange> {
    if (sortedOnsets.isEmpty()) return emptyList()
    val out = ArrayList<LongRange>()
    var first = sortedOnsets[0]
    var last = first
    for (i in 1 until sortedOnsets.size) {
        val t = sortedOnsets[i]
        if (t - last > mergeMs) {
            out.add(first..last)
            first = t
        }
        last = t
    }
    out.add(first..last)
    return out
}

/** Span opens on the step after the last onset; a backcast's, read backwards, before the first. */
internal fun eventWindowStart(kind: MaskGeometry, shape: LoraSpanShape, firstStep: Int, lastStep: Int): Int =
    if (kind == MaskGeometry.BACKCAST) {
        firstStep - shape.spanOff - shape.spanLen
    } else {
        lastStep + 1 - shape.spanOff
    }

/** The step an event beside the span sits on, per [eventWindowStart]. */
internal fun boundaryStep(kind: MaskGeometry, shape: LoraSpanShape, w: Int): Int =
    if (kind == MaskGeometry.BACKCAST) w + shape.spanOff + shape.spanLen else w + shape.spanOff - 1

/** Mirrors build_graph_input: left-preferring else right neighbour FIRST step. Null = trailing. */
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
