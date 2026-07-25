package com.t1dm.core.model

/**
 * The BG panel's drawing implements — the palette the user picks from, and the vocabulary
 * [PaintStroke.tool] is written in.
 *
 * An enum HERE but an open TEXT column THERE, deliberately: the persisted stroke keeps a plain string
 * so a row authored by a later build (or restored from a config backup) can never fail to decode on an
 * older one — the renderer resolves an unknown name onto the fine pencil and still draws it. [forKey]
 * is the only decode path and is total for the same reason. The eraser is absent on purpose: it
 * authors nothing, so it is a mode of the palette rather than a tool a stroke can be tagged with.
 *
 * [defaultWidthDp] and [defaultAlpha] are SEEDS, not constraints — picking a tool loads them into the
 * width slider and the colour picker's alpha, which the user may then override. What the tool alone
 * decides at draw time is its *character*: cap and join geometry, and whether the line is textured.
 * Keeping the seeds here rather than in the renderer is what lets the palette show an honest preview
 * without `:feature:dashboard` reaching into `:ui:graph` internals.
 */
enum class PaintTool(
    val key: String,
    val displayName: String,
    val defaultWidthDp: Float,
    val defaultAlpha: Float,
) {
    /** Thin and fully opaque — the annotating pencil, legible at any zoom. */
    FINE("fine", "Fine", 2f, 1f),

    /** Medium, round-capped, slightly translucent so crossings read as ink rather than paint. */
    MARKER("marker", "Marker", 7f, 0.72f),

    /** Broken, grainy line — the only textured tool. */
    CHALK("chalk", "Chalk", 9f, 0.85f),

    /** Wide, flat-capped and very translucent: made for banding a region, not for drawing on it. */
    HIGHLIGHTER("highlighter", "Highlighter", 18f, 0.22f),

    /**
     * The flood pen — wide enough to cover the panel in two or three passes, and fully opaque so
     * overlapping passes read as one field rather than a striped one. Round-capped like the pencil
     * rather than flat like the highlighter, because the eraser's reach is modelled on a round cap:
     * half a nib of phantom hit region is negligible at 18 dp and absurd at this width.
     */
    BROAD("broad", "Broad", 96f, 1f);

    companion object {
        val DEFAULT = FINE

        /** Tolerant decode of a persisted/imported tool name; an unknown one is [DEFAULT], never a throw. */
        fun forKey(key: String?): PaintTool = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
