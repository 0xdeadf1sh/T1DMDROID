package com.t1dm.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.t1dm.core.model.DkaTimeline

/**
 * The landmarks sit EVENLY, one leg of road apiece, rather than on a time axis; [journeyProgress]
 * warps time to match, so the arrow still reaches each figure when that landmark is projected.
 * Display-only: no §3.6 rail reads it.
 */

private fun Color.blend(other: Color, t: Float): Color = lerp(this, other, t)

/** Fractions of the whole span, in `[0, 1]`. */
data class JourneyMarks(val dka: Float, val coma: Float, val death: Float) {
    companion object {
        val EVEN = JourneyMarks(1f / 3f, 2f / 3f, 1f)
    }
}

/** One leg of road per landmark. */
private const val LEG = 1f / 3f

/**
 * Clamped to `[0, 1]`. [anchorMs] is the projected IOB-zero instant and may lie in the PAST (a
 * decayed dose) or the FUTURE (insulin on board). PIECEWISE, one third of the road per leg: the
 * landmarks are evenly spaced while the hours behind them are not.
 */
fun journeyProgress(nowMs: Long, anchorMs: Long, tl: DkaTimeline): Float {
    val legs = doubleArrayOf(
        tl.iobZeroToDkaHours.coerceAtLeast(0.0),
        tl.dkaToComaHours.coerceAtLeast(0.0),
        tl.comaToDeathHours.coerceAtLeast(0.0),
    )
    if (legs.sum() <= 0.0) return if (nowMs >= anchorMs) 1f else 0f
    var remainingH = (nowMs - anchorMs) / 3_600_000.0
    if (remainingH <= 0.0) return 0f
    var frac = 0f
    for (leg in legs) {
        if (remainingH <= 0.0) break
        // A zero-hour leg is crossed instantly: award its road, spend no time.
        if (leg <= 0.0) {
            frac += LEG
            continue
        }
        frac += LEG * (remainingH / leg).coerceAtMost(1.0).toFloat()
        remainingH -= leg
    }
    return frac.coerceIn(0f, 1f)
}

/** [progress] is read inside the draw scope, so a ticker repaints the road without recomposing the
 *  panel around it. */
@Composable
fun JourneyPath(
    progress: () -> Float,
    marks: JourneyMarks,
    modifier: Modifier = Modifier,
    road: Color = MaterialTheme.colorScheme.outline,
    travelled: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    alarm: Color = MaterialTheme.colorScheme.error,
    spent: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(modifier) {
        val p = progress().coerceIn(0f, 1f)
        val w = size.width
        val h = size.height
        val r = minOf(h * 0.30f, w * 0.085f)
        val x0 = r * 1.2f
        val x1 = w - r * 1.2f
        val span = (x1 - x0).coerceAtLeast(1f)
        val roadY = h * 0.80f
        val iconCy = roadY - h * 0.36f
        fun at(f: Float) = x0 + f.coerceIn(0f, 1f) * span

        drawLine(road, Offset(x0, roadY), Offset(x1, roadY), strokeWidth = h * 0.045f, cap = StrokeCap.Round)
        val nowX = at(p)
        if (nowX > x0) {
            drawLine(travelled, Offset(x0, roadY), Offset(nowX, roadY), strokeWidth = h * 0.062f, cap = StrokeCap.Round)
        }
        drawCircle(travelled, radius = h * 0.045f, center = Offset(x0, roadY))

        val fracs = floatArrayOf(marks.dka, marks.coma, marks.death)
        for (i in 0..2) {
            val passed = p >= fracs[i]
            val tint = when {
                passed -> spent.copy(alpha = 0.45f)
                i == 0 -> alarm
                else -> ink
            }
            val cx = at(fracs[i])
            drawLine(
                tint.copy(alpha = if (passed) 0.35f else 0.8f),
                Offset(cx, roadY - h * 0.075f), Offset(cx, roadY + h * 0.075f),
                strokeWidth = h * 0.022f, cap = StrokeCap.Round,
            )
            when (i) {
                0 -> drawKetoneDrop(cx, iconCy, r, tint)
                1 -> drawClosedEye(cx, iconCy, r, tint)
                else -> drawGravestone(cx, iconCy, r, tint)
            }
        }

        val a = h * 0.115f
        val head = Path().apply {
            moveTo(nowX + a * 0.62f, roadY)
            lineTo(nowX - a * 0.52f, roadY - a * 0.66f)
            lineTo(nowX - a * 0.22f, roadY)
            lineTo(nowX - a * 0.52f, roadY + a * 0.66f)
            close()
        }
        val arrow = if (p >= 1f) alarm else travelled
        drawCircle(arrow.copy(alpha = 0.20f), radius = a * 0.95f, center = Offset(nowX, roadY))
        drawPath(head, arrow)
    }
}

private fun DrawScope.drawKetoneDrop(cx: Float, cy: Float, r: Float, tint: Color) {
    val body = Path().apply {
        moveTo(cx, cy - r * 0.94f)
        cubicTo(
            cx + r * 0.58f, cy - r * 0.16f,
            cx + r * 0.74f, cy + r * 0.26f,
            cx, cy + r * 0.84f,
        )
        cubicTo(
            cx - r * 0.74f, cy + r * 0.26f,
            cx - r * 0.58f, cy - r * 0.16f,
            cx, cy - r * 0.94f,
        )
        close()
    }
    drawPath(
        body,
        Brush.verticalGradient(
            colors = listOf(tint.blend(Color.White, 0.28f), tint, tint.blend(Color.Black, 0.30f)),
            startY = cy - r, endY = cy + r,
        ),
    )
    drawPath(body, tint.blend(Color.Black, 0.45f).copy(alpha = 0.7f), style = Stroke(width = r * 0.07f))
    val punch = tint.blend(Color.Black, 0.62f)
    drawLine(
        punch,
        Offset(cx, cy - r * 0.30f), Offset(cx, cy + r * 0.18f),
        strokeWidth = r * 0.17f, cap = StrokeCap.Round,
    )
    drawCircle(punch, radius = r * 0.10f, center = Offset(cx, cy + r * 0.46f))
}

private fun DrawScope.drawClosedEye(cx: Float, cy: Float, r: Float, tint: Color) {
    val lid = Path().apply {
        moveTo(cx - r * 0.86f, cy - r * 0.16f)
        quadraticTo(cx, cy + r * 0.70f, cx + r * 0.86f, cy - r * 0.16f)
    }
    drawPath(lid, tint, style = Stroke(width = r * 0.15f, cap = StrokeCap.Round))
    for (k in -1..1) {
        val f = k * 0.42f
        val bx = cx + f * r
        val by = cy + r * (0.44f - kotlin.math.abs(f) * 0.62f)
        drawLine(
            tint.copy(alpha = 0.8f),
            Offset(bx, by), Offset(bx + f * r * 0.34f, by + r * 0.32f),
            strokeWidth = r * 0.09f, cap = StrokeCap.Round,
        )
    }
    val brow = Path().apply {
        moveTo(cx - r * 0.66f, cy - r * 0.46f)
        quadraticTo(cx, cy - r * 0.80f, cx + r * 0.66f, cy - r * 0.46f)
    }
    drawPath(brow, tint.copy(alpha = 0.34f), style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
}

private fun DrawScope.drawGravestone(cx: Float, cy: Float, r: Float, tint: Color) {
    val hw = r * 0.56f
    val top = cy - r * 0.92f
    val baseY = cy + r * 0.72f

    // The mound goes down FIRST, so the slab is planted in it rather than wearing it as a brim.
    val mound = Path().apply {
        moveTo(cx - r * 1.02f, baseY + r * 0.24f)
        quadraticTo(cx, baseY - r * 0.42f, cx + r * 1.02f, baseY + r * 0.24f)
        close()
    }
    drawPath(mound, tint.copy(alpha = 0.30f))
    drawRoundRect(
        tint.blend(Color.Black, 0.30f),
        topLeft = Offset(cx - hw * 1.16f, baseY - r * 0.05f),
        size = Size(hw * 2.32f, r * 0.19f),
        cornerRadius = CornerRadius(r * 0.05f, r * 0.05f),
    )

    val slab = Path().apply {
        moveTo(cx - hw, baseY)
        lineTo(cx - hw, top + hw)
        arcTo(Rect(cx - hw, top, cx + hw, top + hw * 2f), 180f, 180f, false)
        lineTo(cx + hw, baseY)
        close()
    }
    drawPath(
        slab,
        Brush.verticalGradient(
            colors = listOf(tint.blend(Color.White, 0.22f), tint, tint.blend(Color.Black, 0.38f)),
            startY = top, endY = baseY,
        ),
    )
    drawPath(slab, tint.blend(Color.Black, 0.50f).copy(alpha = 0.7f), style = Stroke(width = r * 0.06f))

    val engrave = tint.blend(Color.Black, 0.55f)
    drawLine(
        engrave,
        Offset(cx, top + hw * 0.62f), Offset(cx, top + hw * 2.05f),
        strokeWidth = r * 0.09f, cap = StrokeCap.Round,
    )
    drawLine(
        engrave,
        Offset(cx - hw * 0.44f, top + hw * 1.14f), Offset(cx + hw * 0.44f, top + hw * 1.14f),
        strokeWidth = r * 0.09f, cap = StrokeCap.Round,
    )
}
