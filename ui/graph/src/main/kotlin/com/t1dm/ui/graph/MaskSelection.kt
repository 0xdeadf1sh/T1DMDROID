package com.t1dm.ui.graph

import com.t1dm.core.model.MaskGeometry

/** Every bound comes from the descriptor; fromDescriptor false is the cut-only, 5-min fallback. */
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

/** Half-open `[startMs, endMs)`, both ends on absolute patch boundaries. */
data class MaskSelection(val startMs: Long, val endMs: Long) {
    fun patches(patchMs: Long): Int = ((endMs - startMs) / patchMs).toInt()
}

/** Absolute boundaries, not window-relative: window-derived would move under a pan. */
internal fun snapDown(ms: Long, patchMs: Long): Long = Math.floorDiv(ms, patchMs) * patchMs

/** `SPEC/inference.md` §4. Derived from where the span sits, never chosen. */
fun geometryOf(sel: MaskSelection, c: MaskControls): MaskGeometry = when {
    sel.endMs > c.newestMeasuredMs -> MaskGeometry.FORECAST
    sel.startMs <= c.contextFloorMs -> MaskGeometry.BACKCAST
    else -> MaskGeometry.INFILL
}

/** One stretch, snapped to patches; clamps a too-long drag toward the anchor, not refusing it. */
fun selectionOf(dragFromMs: Long, dragToMs: Long, c: MaskControls): MaskSelection? {
    if (c.patchMs <= 0L) return null
    val lo = snapDown(minOf(dragFromMs, dragToMs), c.patchMs)
    // Exclusive end, so a drag inside one patch still selects that patch.
    val hi = snapDown(maxOf(dragFromMs, dragToMs), c.patchMs) + c.patchMs
    // One span, so the cap is the smaller of the longest run and the whole masked budget.
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

/** A forecast's length is the descriptor's horizon, not the drag. Clamped, not refused. */
private fun forecastClamped(sel: MaskSelection, c: MaskControls): MaskSelection {
    if (sel.endMs <= c.newestMeasuredMs) return sel
    if (c.forecastPatches <= 0) return sel
    // The horizon starts at the patch after the one holding the newest measurement.
    val horizonEnd = snapDown(c.newestMeasuredMs, c.patchMs) + c.patchMs +
        c.forecastPatches.toLong() * c.patchMs
    if (sel.endMs <= horizonEnd) return sel
    // Trim the end, not the start: moving the start would lengthen the stretch past the cap.
    return MaskSelection(sel.startMs, maxOf(horizonEnd, sel.startMs + c.patchMs))
}

/** The pointer handler is keyed on this; a re-key cancels a gesture in flight. */
internal enum class GraphGesture { NAVIGATE, PAINT, EDIT }

/** A second finger switches SELECT to TRANSFORM, one-way. */
internal enum class EditGesture { SELECT, TRANSFORM }

/** Stretch as drawn, a patch snap travels not teleports. Double not Float: ~130ms gap by 2026. */
internal fun lerpSelection(from: MaskSelection?, to: MaskSelection?, t: Float): MaskSelection? {
    if (to == null) return null
    if (from == null || t >= 1f) return to
    val p = t.toDouble().coerceIn(0.0, 1.0)
    fun mix(a: Long, b: Long): Long = (a + (b - a) * p).toLong()
    return MaskSelection(mix(from.startMs, to.startMs), mix(from.endMs, to.endMs))
}

internal data class SelectionTween(val from: MaskSelection?, val to: MaskSelection?)
