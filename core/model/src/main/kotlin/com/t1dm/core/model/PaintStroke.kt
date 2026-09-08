package com.t1dm.core.model

/** Display-only; not a data class (array fields give a lying identity `equals`). */
class PaintStroke(
    val id: Long,                      // 0 until the store mints it
    val createdAtMs: Long,
    val tool: String,                  // open TEXT vocabulary; a later-build stroke still decodes
    val colorArgb: Int,
    val widthDp: Float,
    val tsMs: LongArray,               // absolute epoch-ms instants
    val yFrac: FloatArray,             // PLOT BOX fraction (0=top,1=bottom); outside [0,1] clips.
) {
    init {
        require(tsMs.size == yFrac.size) {
            "paint stroke carries ${tsMs.size} timestamps but ${yFrac.size} y values"
        }
    }

    val size: Int get() = tsMs.size

    val isEmpty: Boolean get() = size == 0
}
