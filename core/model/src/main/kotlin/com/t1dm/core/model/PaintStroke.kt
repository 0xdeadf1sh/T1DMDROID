package com.t1dm.core.model

/**
 * One freehand stroke the user drew over the BG graph panel. Pure annotation: nothing reads it back
 * — no calculator, no channel, no §3.6 rail — so it is display-only user data in the strictest sense.
 *
 * The anchoring is the whole design. X is an **absolute epoch-ms** instant and Y a **fraction of the
 * plot box height** ([yFrac] 0 = plot top, 1 = plot bottom); neither is a pixel, so the art scrolls
 * and zooms with the data it was drawn over, and the graph's Y auto-fit — which re-scales the value
 * axis on every window change — can never stretch or shear it. Y is deliberately the *plot box*, not
 * the composable: the box moves when the predicted-clock axis appears, and the same stroke has to
 * survive being re-rendered at widget size. Values outside `[0, 1]` are legal (a finger that strays
 * past the plot is clipped at draw time, not flattened onto the edge).
 *
 * Parallel primitive arrays rather than a `List<Point>`: a stroke is sampled at pointer rate, and the
 * `:ui:graph` render models it will join are primitive-array-only by module discipline. Not a `data
 * class` — array fields would give it an identity-based `equals` that silently lies.
 *
 * [tool] is an open TEXT vocabulary the render layer interprets, not an enum: a stroke authored by a
 * later build (or restored from a config backup) must never fail to decode on an older one. [id] is 0
 * until the row is inserted; the store mints it.
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
