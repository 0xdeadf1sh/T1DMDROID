package com.t1dm.core.model

/**
 * The 90 % band lives ONLY here: the wire carries a boolean saying a sample was reconstructed and
 * nothing about how uncertain it was, so a value arriving back from a re-mirror or a restore has no
 * band and must be drawn as what it is — hatched, band-less, not promotable again. [spanStartMs] is
 * the `tsMs` of the run's first slot, because promotion and demotion act on a span.
 */
data class ReconstructedBg(
    val tsMs: Long,
    val mgdl: Double,
    val lo90: Double,
    val hi90: Double,
    val modelId: String,
    val spanStartMs: Long,
    val promoted: Boolean,
    /** The whole fan at this slot — seven mg/dL levels, ascending τ — or empty when the row predates
     *  the column and holds only its outer pair. Draw what is given: filling the interior in from the
     *  edges would put a shape on the panel the model never emitted. */
    val bands: List<Double> = emptyList(),
    /** Which quantile [mgdl] is the line at. `0.5` is the median. */
    val tau: Double = 0.5,
)

/** Which of `SPEC/inference.md` §4's three geometries a masked span is — derived from where the
 *  span sits, never chosen. */
enum class MaskGeometry {
    /** Nothing brackets it on the left, so its only anchor is its right neighbour. Drawable, never
     *  promotable — storing one extends the patient's history backwards on a single anchor. */
    BACKCAST,

    /** Measured evidence on both sides. The only geometry a promotion may come from. */
    INFILL,

    /** Past the newest measurement. Its length is the descriptor's horizon rather than the drag,
     *  and nothing from it is stored. */
    FORECAST,
}

/**
 * Held in memory, never stored: a commit per frame would put a Room transaction under the τ slider
 * for as long as a thumb is down, so the sweep previews and commits on release. Every position it
 * passes through is still a level the model emitted. [mgdl] is one value per slot, keyed by `tsMs`.
 */
data class SpanLinePreview(
    val spanStartMs: Long,
    val tau: Double,
    val mgdl: Map<Long, Double>,
)
