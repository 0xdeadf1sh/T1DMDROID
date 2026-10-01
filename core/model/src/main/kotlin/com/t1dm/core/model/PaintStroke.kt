package com.t1dm.core.model

/** Not a `data class`: array fields would give it a lying identity-based `equals`. */
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
