package com.t1dm.core.model

/** 90% band lives ONLY here (wire has no uncertainty); re-mirrored/restored rows draw band-less */
data class ReconstructedBg(
    val tsMs: Long,
    val mgdl: Double,
    val lo90: Double,
    val hi90: Double,
    val modelId: String,
    val spanStartMs: Long,
    val promoted: Boolean,
    /** 7 mg/dL levels, ascending τ; empty if row predates column (outer pair only). */
    val bands: List<Double> = emptyList(),
    /** Which quantile [mgdl] is the line at. `0.5` is the median. */
    val tau: Double = 0.5,
)

/** Which of §4's three geometries (SPEC/inference.md) a span is; derived, never chosen. */
enum class MaskGeometry {
    /** No left bracket, anchored only by its right neighbour; drawable, never promotable. */
    BACKCAST,

    /** Measured evidence on both sides. The only geometry a promotion may come from. */
    INFILL,

    /** Past newest measurement; length is the descriptor's horizon, not drag; nothing stored. */
    FORECAST,
}

/** In-memory only (no Room txn under the slider); commits on release. [mgdl] keyed by tsMs. */
data class SpanLinePreview(
    val spanStartMs: Long,
    val tau: Double,
    val mgdl: Map<Long, Double>,
)
