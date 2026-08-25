package com.t1dm.core.model

/**
 * An enum HERE but an open TEXT column THERE: the persisted stroke keeps a plain string, and the
 * renderer resolves an unknown name onto the fine pencil rather than failing. The eraser is absent
 * on purpose — it authors nothing, so it is a mode of the palette, not a tool a stroke can be tagged
 * with. [defaultWidthDp] and [defaultAlpha] are SEEDS the user may override, not constraints.
 */
enum class PaintTool(
    val key: String,
    val displayName: String,
    val defaultWidthDp: Float,
    val defaultAlpha: Float,
) {
    FINE("fine", "Fine", 2f, 1f),

    MARKER("marker", "Marker", 7f, 0.72f),

    /** The only textured tool. */
    CHALK("chalk", "Chalk", 9f, 0.85f),

    HIGHLIGHTER("highlighter", "Highlighter", 18f, 0.22f),

    /** Round-capped, not flat like the highlighter: the eraser's reach is modelled on a round cap,
     *  and half a nib of phantom hit region is absurd at this width. */
    BROAD("broad", "Broad", 96f, 1f);

    companion object {
        val DEFAULT = FINE

        /** An unknown or absent name is [DEFAULT], never a throw. */
        fun forKey(key: String?): PaintTool = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
