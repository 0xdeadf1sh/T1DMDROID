package com.t1dm.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.BezierPoint
import kotlin.math.hypot

/**
 * A reusable CUBIC BÉZIER **draggable-control-point** curve editor (Phase 7D item 19,
 * replacing the piecewise-linear knot editor). Shared by the meal builder (a custom carb-appearance
 * shape) and the insulin builder (a custom action shape). The rendered stroke is the same C¹ cubic
 * the sampler integrates ([BezierCurve.valueAt]) so the drawing IS the preview. Interactions mirror
 * the old editor:
 *  - **drag** a control point freely (x clamped to `[0, durationMin]`, y ≥ 0);
 *  - **tap** empty space to add a point there;
 *  - **long-press** a point to delete it (while > 2 remain).
 *
 * State is internal but hoisted on every edit via [onChange] as a fresh, x-sorted [BezierCurve]; pass
 * a changing [resetKey] to reseed from a new [curve]. The absolute y-scale is irrelevant downstream —
 * consumers area-normalise via [BezierCurve.sampleNormalized] — so the editor only conveys the shape.
 */
@Composable
fun CurveEditor(
    curve: BezierCurve,
    onChange: (BezierCurve) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    resetKey: Any = curve.durationMin,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val padPx = with(density) { 14.dp.toPx() }
    val hitPx = with(density) { 22.dp.toPx() }

    val pts: SnapshotStateList<BezierPoint> = remember(resetKey) { curve.points.toMutableStateList() }
    var dragIdx by remember(resetKey) { mutableIntStateOf(-1) }
    var sizePx by remember { mutableStateOf(Offset.Zero) }

    val dur = curve.durationMin.coerceAtLeast(1.0)
    fun yMax(): Double = (pts.maxOfOrNull { it.y } ?: 1.0).coerceAtLeast(1e-6) * 1.15

    fun emit() = onChange(BezierCurve(dur, pts.sortedBy { it.xMin }))

    fun toPxX(xMin: Double, w: Float): Float = padPx + ((xMin / dur) * (w - 2 * padPx)).toFloat()
    fun toPxY(y: Double, h: Float): Float = (h - padPx) - ((y / yMax()) * (h - 2 * padPx)).toFloat()
    fun toDomX(px: Float, w: Float): Double = (((px - padPx) / (w - 2 * padPx)) * dur).coerceIn(0.0, dur)
    fun toDomY(py: Float, h: Float): Double = ((((h - padPx) - py) / (h - 2 * padPx)) * yMax()).coerceAtLeast(0.0)

    fun nearest(pos: Offset): Int {
        val w = sizePx.x; val h = sizePx.y
        if (w <= 0f) return -1
        var best = -1; var bestD = hitPx
        pts.forEachIndexed { i, k ->
            val d = hypot(toPxX(k.xMin, w) - pos.x, toPxY(k.y, h) - pos.y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { sizePx = Offset(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { pos ->
                        val w = sizePx.x; val h = sizePx.y
                        if (w > 0f && nearest(pos) < 0) {
                            pts.add(BezierPoint(toDomX(pos.x, w), toDomY(pos.y, h)))
                            pts.sortBy { it.xMin }
                            emit()
                        }
                    },
                    onLongPress = { pos ->
                        val i = nearest(pos)
                        if (i >= 0 && pts.size > 2) { pts.removeAt(i); emit() }
                    },
                )
            }
            .pointerInput(resetKey) {
                detectDragGestures(
                    onDragStart = { pos -> dragIdx = nearest(pos) },
                    onDragEnd = { if (dragIdx >= 0) { pts.sortBy { it.xMin }; dragIdx = -1; emit() } },
                    onDragCancel = { dragIdx = -1 },
                    onDrag = { change, _ ->
                        val i = dragIdx
                        if (i in pts.indices) {
                            val w = sizePx.x; val h = sizePx.y
                            pts[i] = BezierPoint(toDomX(change.position.x, w), toDomY(change.position.y, h))
                            emit()
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width; val h = size.height
            val grid = cs.onSurface.copy(alpha = 0.10f)
            val axis = cs.onSurface.copy(alpha = 0.30f)
            for (f in 0..4) {
                val y = padPx + f * (h - 2 * padPx) / 4f
                drawLine(grid, Offset(padPx, y), Offset(w - padPx, y), 1f)
            }
            drawLine(axis, Offset(padPx, h - padPx), Offset(w - padPx, h - padPx), 1.5f)
            drawLine(axis, Offset(padPx, padPx), Offset(padPx, h - padPx), 1.5f)

            val sorted = pts.sortedBy { it.xMin }
            if (sorted.size >= 2) {
                val shape = BezierCurve(dur, sorted)
                // Dense sampling of the SAME interpolant the sampler uses — the drawing is the preview.
                val steps = 120
                val fill = Path()
                val line = Path()
                fill.moveTo(toPxX(0.0, w), h - padPx)
                var started = false
                for (s in 0..steps) {
                    val x = dur * s / steps
                    val y = shape.valueAt(x)
                    val px = toPxX(x, w); val py = toPxY(y, h)
                    if (!started) { line.moveTo(px, py); started = true } else line.lineTo(px, py)
                    fill.lineTo(px, py)
                }
                fill.lineTo(toPxX(dur, w), h - padPx)
                fill.close()
                drawPath(fill, cs.primary.copy(alpha = 0.14f))
                drawPath(line, cs.primary, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
            pts.forEachIndexed { i, k ->
                val c = Offset(toPxX(k.xMin, w), toPxY(k.y, h))
                drawCircle(if (i == dragIdx) cs.secondary else cs.primary, radius = 9f, center = c)
                drawCircle(cs.surface, radius = 4f, center = c)
            }
        }
    }
}

/**
 * A read-only rendering of an already-resolved per-step curve (grams-per-5-min for a carb
 * appearance, units-per-5-min for an insulin action). Used for the meal/insulin live previews.
 */
@Composable
fun CurvePreview(
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    stepMin: Double = 5.0,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val padPx = with(density) { 10.dp.toPx() }

    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width; val h = size.height
            val axis = cs.onSurface.copy(alpha = 0.30f)
            drawLine(axis, Offset(padPx, h - padPx), Offset(w - padPx, h - padPx), 1.5f)
            if (values.isEmpty()) return@Canvas
            val yMax = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
            val n = values.size
            fun px(slot: Int): Float = padPx + slot.toFloat() / n * (w - 2 * padPx)
            fun py(v: Double): Float = (h - padPx) - ((v / yMax) * (h - 2 * padPx)).toFloat()

            val fill = Path().apply {
                moveTo(px(0), h - padPx)
                values.forEachIndexed { i, v -> lineTo(px(i + 1), py(v)) }
                lineTo(px(n), h - padPx)
                close()
            }
            drawPath(fill, cs.primary.copy(alpha = 0.18f))
            val line = Path().apply {
                moveTo(px(0), py(0.0))
                values.forEachIndexed { i, v -> lineTo(px(i + 1), py(v)) }
            }
            drawPath(line, cs.primary, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            val peak = values.indices.maxByOrNull { values[it] } ?: 0
            if (values[peak] > 0.0) {
                drawCircle(cs.secondary, radius = 5f, center = Offset(px(peak + 1), py(values[peak])))
            }
        }
    }
}
