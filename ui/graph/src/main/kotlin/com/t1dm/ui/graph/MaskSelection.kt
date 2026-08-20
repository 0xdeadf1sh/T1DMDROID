package com.t1dm.ui.graph

import com.t1dm.core.model.MaskGeometry

/**
 * What the selected model's descriptor permits a mask to be.
 *
 * Every number here comes from the descriptor and none of it is chosen locally: the app holds no
 * geometry of its own, and a second copy of a patch size or a span cap would be free to disagree
 * with the artifact it is supposed to describe.
 *
 * @param patchMs one patch in milliseconds — the granularity a selection snaps to.
 * @param maxSpans how many separate masked runs the model was trained to see at once.
 * @param maxSpanPatches the longest single run the sampler ever drew.
 * @param maxMaskedPatches the total masked budget across every run.
 * @param newestMeasuredMs the newest real measurement. A selection reaching past it is a forecast.
 * @param contextFloorMs the oldest instant the context window covers. A selection reaching it has
 *   nothing bracketing it on the left, which is what makes it a backcast.
 * @param fromDescriptor whether these bounds came from a model at all. False is the CUT-ONLY shape:
 *   no model is selected, so there is no patch geometry and the panel falls back to the five-minute
 *   grid the store keys. Erasing a reading needs no model, and gating the whole edit mode behind one
 *   left a phone with no model loaded unable to take a bad value back out.
 */
data class MaskControls(
    val patchMs: Long,
    val maxSpans: Int,
    val maxSpanPatches: Int,
    val maxMaskedPatches: Int,
    val forecastPatches: Int,
    val newestMeasuredMs: Long,
    val contextFloorMs: Long,
    val fromDescriptor: Boolean = true,
)

/** A half-open masked run `[startMs, endMs)`, both ends on absolute patch boundaries. */
data class MaskSelection(val startMs: Long, val endMs: Long) {
    fun patches(patchMs: Long): Int = ((endMs - startMs) / patchMs).toInt()
}

/**
 * Snap an instant DOWN to the absolute patch boundary.
 *
 * Absolute, not relative to any window origin: epoch 0 is midnight UTC and `patchMs` is a whole
 * number of five-minute steps, so the boundaries are stable and a selection means the same thing
 * whatever the panel happens to be showing. A boundary derived from the visible window would move
 * under the user as they panned.
 */
internal fun snapDown(ms: Long, patchMs: Long): Long = Math.floorDiv(ms, patchMs) * patchMs

/**
 * Which of `SPEC/inference.md` §4's three geometries a selection is.
 *
 * **Derived, never chosen.** The model has one objective; the geometry is a fact about where the
 * span sits, and offering it as an input would let the label and the geometry disagree.
 */
fun geometryOf(sel: MaskSelection, c: MaskControls): MaskGeometry = when {
    sel.endMs > c.newestMeasuredMs -> MaskGeometry.FORECAST
    sel.startMs <= c.contextFloorMs -> MaskGeometry.BACKCAST
    else -> MaskGeometry.INFILL
}

/**
 * Turn a drag into ONE stretch, snapped to patch boundaries and clamped to the model's own limit.
 *
 * **Clamps rather than refuses.** A drag that runs past the longest span the sampler ever drew is a
 * user asking for something the model was never trained on, and the honest answer is to give them
 * the longest span it WAS trained on rather than nothing at all — a refusal at the end of a drag
 * reads as a broken gesture. The caller says what was clamped.
 *
 * **The clamp keeps the END the finger stopped on.** Dragging right-to-left past the cap and having
 * the stretch grow rightwards from where the finger left off is the opposite of what the hand just
 * asked for; the span shrinks towards the anchor instead.
 *
 * One stretch, not a set: the edit bar acts on the thing the finger is pointing at, and a second
 * selection that stayed on screen while a tool fired at the first is a control aimed at something
 * other than what it highlights.
 */
fun selectionOf(dragFromMs: Long, dragToMs: Long, c: MaskControls): MaskSelection? {
    if (c.patchMs <= 0L) return null
    val lo = snapDown(minOf(dragFromMs, dragToMs), c.patchMs)
    // The end is exclusive, so a drag inside one patch still selects that whole patch.
    val hi = snapDown(maxOf(dragFromMs, dragToMs), c.patchMs) + c.patchMs
    // ONE span, so the binding cap is the smaller of the two the descriptor states: the longest run
    // the sampler ever drew, and the total masked budget a single run would spend on its own.
    val cap = minOf(c.maxSpanPatches, c.maxMaskedPatches).coerceAtLeast(1)
    val patches = ((hi - lo) / c.patchMs).toInt().coerceAtLeast(1)
    val forward = dragToMs >= dragFromMs
    val capped = cap.toLong() * c.patchMs
    // Which end the finger settled on is which end survives the clamp.
    val sel = when {
        patches <= cap -> MaskSelection(lo, hi)
        forward -> MaskSelection(lo, lo + capped)
        else -> MaskSelection(hi - capped, hi)
    }
    return forecastClamped(sel, c)
}

/**
 * A stretch reaching past the newest measurement is a FORECAST, and a forecast's length is the
 * descriptor's horizon rather than the drag — so a highlight running further than that describes a
 * run nothing will make.
 *
 * Clamped rather than refused, for the same reason the span cap is: the honest answer to "predict
 * further than the model was trained to" is the furthest it WAS trained to, not a message.
 */
private fun forecastClamped(sel: MaskSelection, c: MaskControls): MaskSelection {
    if (sel.endMs <= c.newestMeasuredMs) return sel
    if (c.forecastPatches <= 0) return sel
    // The horizon runs from the patch AFTER the one holding the newest measurement, which is where
    // the run's own prediction zone begins.
    val horizonEnd = snapDown(c.newestMeasuredMs, c.patchMs) + c.patchMs +
        c.forecastPatches.toLong() * c.patchMs
    if (sel.endMs <= horizonEnd) return sel
    // Trim the END back to the horizon and leave the start alone — moving the start would LENGTHEN
    // the stretch, which is the opposite of a clamp and would hand the runner a span past the cap
    // the caller just applied.
    return MaskSelection(sel.startMs, maxOf(horizonEnd, sel.startMs + c.patchMs))
}

/**
 * What one finger does on the BG panel.
 *
 * An enum rather than a pair of booleans because the modes are mutually exclusive by construction,
 * and because the pointer handler is keyed on exactly this — a re-key cancels a gesture in flight,
 * so the key has to be one value that changes only when the mode genuinely does.
 */
internal enum class GraphGesture { NAVIGATE, PAINT, EDIT }

/** What a gesture in edit mode turned out to be. A second finger switches SELECT to TRANSFORM and
 *  the switch is one-way: a gesture that became a pan never goes back to selecting. */
internal enum class EditGesture { SELECT, TRANSFORM }

/**
 * The stretch as it is DRAWN, between where it was and where it has just moved to.
 *
 * A selection snaps to whole patches, so a resize jumps half an hour at a time and the highlight
 * teleported under the thumb. Interpolating the two edges over a tenth of a second reads as the
 * edge being pushed rather than replaced, and because each new target starts from wherever the
 * previous interpolation had reached, a drag that crosses several boundaries produces one
 * continuous travel rather than a queue of jumps.
 *
 * In `Double`, not `Float`: these are epoch milliseconds, and a `Float` mantissa puts the nearest
 * representable neighbours of a 2026 instant about 130 ms apart — several pixels on a 6 h window.
 */
internal fun lerpSelection(from: MaskSelection?, to: MaskSelection?, t: Float): MaskSelection? {
    if (to == null) return null
    if (from == null || t >= 1f) return to
    val p = t.toDouble().coerceIn(0.0, 1.0)
    fun mix(a: Long, b: Long): Long = (a + (b - a) * p).toLong()
    return MaskSelection(mix(from.startMs, to.startMs), mix(from.endMs, to.endMs))
}

/** Where an interpolating selection is now, and where it is heading. */
internal data class SelectionTween(val from: MaskSelection?, val to: MaskSelection?)
