package com.t1dm.core.model

/**
 * Display-only annotation: nothing reads it back. X is an absolute epoch-ms instant, Y a fraction of
 * the PLOT BOX height ([yFrac] 0 = top, 1 = bottom) — never a pixel, so the art scrolls and zooms
 * with the data it was drawn over. Values outside `[0, 1]` are legal, clipped at draw time. Not a
 * `data class`: array fields would give it an identity-based `equals` that silently lies. [tool] is
 * an open TEXT vocabulary, so a stroke authored by a later build still decodes. [id] is 0 until the
 * store mints it.
 */
class PaintStroke(
    val id: Long,
    val createdAtMs: Long,
    val tool: String,
    val colorArgb: Int,
    val widthDp: Float,
    val tsMs: LongArray,
    val yFrac: FloatArray,
) {
    init {
        require(tsMs.size == yFrac.size) {
            "paint stroke carries ${tsMs.size} timestamps but ${yFrac.size} y values"
        }
    }

    val size: Int get() = tsMs.size

    val isEmpty: Boolean get() = size == 0
}
