package com.t1dm.core.model

/**
 * One five-minute slot a model reconstructed over a sensor gap, with the 90 % band it came with.
 *
 * A `:core:model` record so `:ui:graph` and `:feature:dashboard` can draw a fill without reaching
 * into `:data` — the same reason `GraphFrame` takes `List<CgmReading>` and never a Room row.
 *
 * **The band lives only here.** The wire carries a boolean saying a sample was reconstructed and
 * nothing about how uncertain it was, so a promoted value that arrives back from a re-mirror, or a
 * restore onto a second phone, has no band at all. A reader must draw that state as what it is —
 * hatched, band-less, and not promotable again — rather than as a plain point.
 *
 * @param spanStartMs the `tsMs` of the first slot of the run this belongs to. Promotion and
 *   demotion act on a span, and a contiguity scan at read time is not an identity two operations
 *   can be relied on to agree about.
 * @param promoted whether this reconstruction has been written into the record as a stored,
 *   syncable sample. Unpromoted it is a drawing and nothing else.
 */
data class ReconstructedBg(
    val tsMs: Long,
    val mgdl: Double,
    val lo90: Double,
    val hi90: Double,
    val modelId: String,
    val spanStartMs: Long,
    val promoted: Boolean,
    /**
     * The whole fan at this slot — seven mg/dL levels, ascending τ — or empty when the row predates
     * the column and holds only its outer pair.
     *
     * A reader draws what it is given: seven levels are nested bands, and two are one band. Filling
     * the interior in from the edges would put a shape on the panel the model never emitted.
     */
    val bands: List<Double> = emptyList(),
    /** Which quantile [mgdl] is the line at. `0.5` is the median. */
    val tau: Double = 0.5,
)

/**
 * Which of `SPEC/inference.md` §4's three geometries a masked span is.
 *
 * **Derived from where the span sits, never chosen.** The model has one objective and one artifact;
 * backcast, infill and forecast are the same run under different inputs, so naming the geometry as
 * an input would be a second copy of a fact the geometry already fixes — and the two copies would
 * be free to disagree.
 */
enum class MaskGeometry {
    /** Nothing brackets it on the left: the span reaches the context floor, so its only anchor is
     *  its right neighbour. Drawable, never promotable — storing one extends the patient's history
     *  backwards on a single anchor. */
    BACKCAST,

    /** Measured evidence on both sides. The only geometry a promotion may come from. */
    INFILL,

    /** The span runs past the newest measurement. Its length is the descriptor's horizon rather
     *  than the drag, and nothing from it is stored. */
    FORECAST,
}

/**
 * A drawn span's line at a τ the user is still dragging towards — held in memory, never stored.
 *
 * The τ slider has to move the line under the thumb, and the line is read out of a stored fan: a
 * commit per frame would put a Room transaction on the critical path for as long as a thumb is
 * down. So the sweep previews here and commits on release, and every position it passes through is
 * still a level the model emitted — the interpolation is the crate's, over the span's own fan.
 *
 * @param mgdl one value per slot of the span, keyed by `tsMs`.
 */
data class SpanLinePreview(
    val spanStartMs: Long,
    val tau: Double,
    val mgdl: Map<Long, Double>,
)
