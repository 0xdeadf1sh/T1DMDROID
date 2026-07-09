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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.t1dm.core.model.PwlCurve
import com.t1dm.core.model.PwlKnot
import kotlin.math.hypot

/**
 * A reusable piecewise-linear **draggable-knot** curve editor (PLAN.private.md Phase 4 deliverable
 * 4). Shared by the meal builder (a custom carb-appearance shape) and the insulin builder (a custom
 * action shape). Deliberately faithful to the spec: **no x-snapping**, a **linear amount axis**, and
 * a **live** rendering that is itself the preview. Interactions:
 *  - **drag** a knot to move it freely (x clamped to `[0, durationMin]`, y ≥ 0);
 *  - **tap** empty space to add a knot there;
 *  - **long-press** a knot to delete it (while > 2 remain).
 *
 * State is internal but hoisted out on every edit via [onChange] as a fresh, x-sorted [PwlCurve];
 * pass a changing [resetKey] to reseed from a new [curve] (e.g. when the caller switches presets).
 * The absolute y-scale is irrelevant downstream — consumers area-normalize via
 * [PwlCurve.sampleNormalized] — so the editor only needs to convey the *shape*.
 */
@Composable
fun CurveEditor(
    curve: PwlCurve,
    onChange: (PwlCurve) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    resetKey: Any = curve.durationMin,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val padPx = with(density) { 14.dp.toPx() }
    val hitPx = with(density) { 22.dp.toPx() }

    val knots: SnapshotStateList<PwlKnot> = remember(resetKey) { curve.knots.toMutableStateList() }
    var dragIdx by remember(resetKey) { mutableIntStateOf(-1) }
    var sizePx by remember { mutableStateOf(Offset.Zero) }

    val dur = curve.durationMin.coerceAtLeast(1.0)
    fun yMax(): Double = (knots.maxOfOrNull { it.y } ?: 1.0).coerceAtLeast(1e-6) * 1.15

    fun emit() = onChange(PwlCurve(dur, knots.sortedBy { it.xMin }))

    fun toPxX(xMin: Double, w: Float): Float = padPx + ((xMin / dur) * (w - 2 * padPx)).toFloat()
    fun toPxY(y: Double, h: Float): Float = (h - padPx) - ((y / yMax()) * (h - 2 * padPx)).toFloat()
    fun toDomX(px: Float, w: Float): Double = (((px - padPx) / (w - 2 * padPx)) * dur).coerceIn(0.0, dur)
    fun toDomY(py: Float, h: Float): Double = ((((h - padPx) - py) / (h - 2 * padPx)) * yMax()).coerceAtLeast(0.0)

    fun nearest(pos: Offset): Int {
        val w = sizePx.x; val h = sizePx.y
        if (w <= 0f) return -1
        var best = -1; var bestD = hitPx
        knots.forEachIndexed { i, k ->
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
                            knots.add(PwlKnot(toDomX(pos.x, w), toDomY(pos.y, h)))
                            emit()
                        }
                    },
                    onLongPress = { pos ->
                        val i = nearest(pos)
                        if (i >= 0 && knots.size > 2) { knots.removeAt(i); emit() }
                    },
                )
            }
            .pointerInput(resetKey) {
                detectDragGestures(
                    onDragStart = { pos -> dragIdx = nearest(pos) },
                    onDragEnd = { if (dragIdx >= 0) { knots.sortBy { it.xMin }; dragIdx = -1; emit() } },
                    onDragCancel = { dragIdx = -1 },
                    onDrag = { change, _ ->
                        val i = dragIdx
                        if (i in knots.indices) {
                            val w = sizePx.x; val h = sizePx.y
                            knots[i] = PwlKnot(toDomX(change.position.x, w), toDomY(change.position.y, h))
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
            // Baseline + a few horizontal guides.
            for (f in 0..4) {
                val y = padPx + f * (h - 2 * padPx) / 4f
                drawLine(grid, Offset(padPx, y), Offset(w - padPx, y), 1f)
            }
            drawLine(axis, Offset(padPx, h - padPx), Offset(w - padPx, h - padPx), 1.5f)
            drawLine(axis, Offset(padPx, padPx), Offset(padPx, h - padPx), 1.5f)

            val sorted = knots.sortedBy { it.xMin }
            if (sorted.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(toPxX(sorted.first().xMin, w), toPxY(sorted.first().y, h))
                    sorted.drop(1).forEach { lineTo(toPxX(it.xMin, w), toPxY(it.y, h)) }
                }
                drawPath(path, cs.primary, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
            knots.forEachIndexed { i, k ->
                val c = Offset(toPxX(k.xMin, w), toPxY(k.y, h))
                drawCircle(if (i == dragIdx) cs.secondary else cs.primary, radius = 8f, center = c)
                drawCircle(cs.surface, radius = 3.5f, center = c)
            }
        }
    }
}

/**
 * A read-only rendering of an already-resolved per-step curve (grams-per-5-min for a carb
 * appearance, units-per-5-min for an insulin action). Used for the meal/insulin live previews where
 * there is nothing to edit — just the shape the model will consume, with a shaded area and a peak
 * marker.
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
            // Anchoring (PLAN.private.md Phase 7A item 4): `values[i]` is the gamma sample at
            // t = (i+1)·stepMin, and the curve is 0 at t=0 (the log instant). Plot over `n+1` slots so
            // slot 0 = (t=0, 0) and slot i+1 = (t=(i+1)·step, values[i]); the shape now rises FROM the
            // log instant instead of starting already-onboard at value[0].
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
                moveTo(px(0), py(0.0)) // (log instant, 0)
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
