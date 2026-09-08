package com.t1dm.core.model

/** Enum here, TEXT column there: unknown → fine pencil; no eraser; width/alpha are SEEDS. */
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

    /** Round-capped not flat: models the eraser's reach; a half-nib phantom hit is absurd here. */
    BROAD("broad", "Broad", 96f, 1f);

    companion object {
        val DEFAULT = FINE

        /** An unknown or absent name is [DEFAULT], never a throw. */
        fun forKey(key: String?): PaintTool = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
