package com.t1dm.core.model

/** 90% band lives only here; re-mirrored/restored values have none — hatched, never re-promote. */
data class ReconstructedBg(
    val tsMs: Long,
    val mgdl: Double,
    val lo90: Double,
    val hi90: Double,
    val modelId: String,
    val spanStartMs: Long,
    val promoted: Boolean,
    /** 7 mg/dL levels, ascending τ, or empty (pre-column row, outer pair). Never interpolate. */
    val bands: List<Double> = emptyList(),
    /** Which quantile [mgdl] is the line at. `0.5` is the median. */
    val tau: Double = 0.5,
)

/** One of `SPEC/inference.md` §4's three geometries; derived from span position, never chosen. */
enum class MaskGeometry {
    /** No left anchor, only right. Drawable, never promotable — would extend history one anchor. */
    BACKCAST,

    /** Measured evidence on both sides. The only geometry a promotion may come from. */
    INFILL,

    /** Past newest measurement; length is the descriptor's horizon, not the drag. Never stored. */
    FORECAST,
}

/** In memory only: a Room txn per frame under the τ slider would stall it; commits on release. */
data class SpanLinePreview(
    val spanStartMs: Long,
    val tau: Double,
    val mgdl: Map<Long, Double>,
)
