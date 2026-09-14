package com.t1dm.feature.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.GolfCup
import com.t1dm.core.model.GolfTuning
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Below this the ball is a dot nobody can aim; the drawn size stops tracking the world here. */
private const val MIN_BALL_PX = 7f

/** Flag height in ball radii, and the pennant's own reach. */
private const val FLAG_POLE_R = 6f
private const val FLAG_FLY_R = 2.4f

/** Dimples, so the roll is legible on a plain circle. */
private const val DIMPLES = 3

/** Dash and gap of the preview arc, in pixels. */
private const val ARC_DASH_PX = 9f

/** Splash ring's reach in ball radii, and its stroke. */
private const val SPLASH_R = 5f
private const val SPLASH_W_PX = 3f

/** Tee mark height in ball radii. */
private const val TEE_R = 1.6f

/** Ball, cup and flag geometry; the hole arrives from the loop once the world is built. */
internal class GolfArt(tuning: GolfTuning) {
    val ballRadius = tuning.ballRadius
    val gravity = tuning.gravity
    val maxLaunchSpeed = tuning.maxLaunchSpeed

    /** Rust's, not derived here; null until the loop has opened the world. */
    @Volatile
    var cup: GolfCup? = null

    /** Reused per frame; a Path rewound is a Path reused. */
    val cupPath = Path()
    val flagPath = Path()

    /** Sampled once per frame while aiming; never allocated in the draw. */
    val arc = FloatArray(ARC_POINTS * 2)
}

/** The hole: the trace is erased across the mouth, then the walls are stroked in its own ink. */
internal fun DrawScope.drawCup(
    art: GolfArt,
    skin: GameSkin,
    camLeft: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    val cup = art.cup ?: return
    val x0 = (cup.x0 - camLeft) * pxX
    val x1 = (cup.x1 - camLeft) * pxX
    val rim = floorPx - cup.rimY * pxY
    val bottom = floorPx - (cup.rimY - cup.depth) * pxY
    if (x1 < -pxX || x0 > size.width + pxX) return

    // Opaque, so the ground line drawn a moment ago does not bridge the mouth.
    drawRect(skin.sky, topLeft = Offset(x0, rim), size = Size((x1 - x0).coerceAtLeast(1f), bottom - rim))
    art.cupPath.rewind()
    art.cupPath.moveTo(x0, rim)
    art.cupPath.lineTo(x0, bottom)
    art.cupPath.lineTo(x1, bottom)
    art.cupPath.lineTo(x1, rim)
    drawPath(art.cupPath, skin.trace, style = Stroke(width = 2.2f, cap = StrokeCap.Round))

    val poleX = x1
    val poleTop = rim - FLAG_POLE_R * art.ballRadius * pxY
    drawLine(skin.marker, Offset(poleX, rim), Offset(poleX, poleTop), strokeWidth = 2.4f)
    val fly = FLAG_FLY_R * art.ballRadius * pxX
    val drop = FLAG_FLY_R * art.ballRadius * pxY * 0.45f
    val pennant = art.flagPath
    pennant.rewind()
    pennant.moveTo(poleX, poleTop)
    pennant.lineTo(poleX - fly, poleTop + drop * 0.5f)
    pennant.lineTo(poleX, poleTop + drop)
    pennant.close()
    drawPath(pennant, skin.finish, style = Fill)
}

/** Where the round started, so a long shot still shows what it is being measured from. */
internal fun DrawScope.drawTee(
    art: GolfArt,
    f: BallFrame,
    skin: GameSkin,
    camLeft: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    val sx = (f.teeX - camLeft) * pxX
    if (sx < -8f || sx > size.width + 8f) return
    val baseY = floorPx - (f.teeY - art.ballRadius) * pxY
    val h = TEE_R * art.ballRadius * pxY
    drawLine(skin.marker, Offset(sx, baseY), Offset(sx, baseY - h), strokeWidth = 2f)
}

/** Dashed, so it reads as a prediction rather than a drawn line of the trace. */
internal fun DrawScope.drawAimArc(
    art: GolfArt,
    n: Int,
    camLeft: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
    skin: GameSkin,
) {
    if (n < 2) return
    var carry = 0f
    var on = true
    var px = (art.arc[0] - camLeft) * pxX
    var py = floorPx - art.arc[1] * pxY
    for (i in 1 until n) {
        val nx = (art.arc[2 * i] - camLeft) * pxX
        val ny = floorPx - art.arc[2 * i + 1] * pxY
        // Walked in pixels, so the dash is even however the zoom has stretched the segment.
        val len = hypot(nx - px, ny - py)
        var t = 0f
        while (t < len) {
            val step = minOf(ARC_DASH_PX - carry, len - t)
            if (on) {
                val a = t / len
                val b = (t + step) / len
                drawLine(
                    skin.accent,
                    Offset(px + (nx - px) * a, py + (ny - py) * a),
                    Offset(px + (nx - px) * b, py + (ny - py) * b),
                    strokeWidth = 2.4f,
                    cap = StrokeCap.Round,
                )
            }
            t += step
            carry += step
            if (carry >= ARC_DASH_PX) {
                carry = 0f
                on = !on
            }
        }
        px = nx
        py = ny
    }
    // The landing point, so a shot can be aimed at the hole rather than merely away from the tee.
    drawCircle(skin.accent, 3.5f, Offset(px, py))
}

/** Expanding ring where the ball was lost; fades with [BallFrame.splash]. */
internal fun DrawScope.drawSplash(
    art: GolfArt,
    f: BallFrame,
    skin: GameSkin,
    camLeft: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    val life = f.splash
    if (life <= 0f) return
    val grow = 1f - life
    val cx = (f.splashX - camLeft) * pxX
    val cy = floorPx - f.splashY * pxY
    val rx = art.ballRadius * pxX * (1f + SPLASH_R * grow)
    val ry = art.ballRadius * pxY * (1f + SPLASH_R * grow)
    drawOval(
        skin.trace.copy(alpha = life * 0.7f),
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(2f * rx, 2f * ry),
        style = Stroke(width = SPLASH_W_PX),
    )
}

/** True scale where it can be; a floor keeps it aimable when the camera has widened right out. */
internal fun DrawScope.drawBall(
    art: GolfArt,
    f: BallFrame,
    skin: GameSkin,
    camLeft: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    val cx = (f.x - camLeft) * pxX
    val cy = floorPx - f.y * pxY
    val rx = (art.ballRadius * pxX).coerceAtLeast(MIN_BALL_PX)
    val ry = (art.ballRadius * pxY).coerceAtLeast(MIN_BALL_PX)
    fun oval(color: androidx.compose.ui.graphics.Color, k: Float, style: Stroke? = null) = drawOval(
        color,
        topLeft = Offset(cx - rx * k, cy - ry * k),
        size = Size(2f * rx * k, 2f * ry * k),
        style = style ?: Fill,
    )
    oval(skin.body, 1f)
    oval(skin.bodyEdge, 1f, Stroke(width = 1.6f))
    // Not negated unlike the chassis: angle is forward-positive, as the solver reports the roll.
    for (k in 0 until DIMPLES) {
        val t = f.angle + k * (2f * Math.PI.toFloat() / DIMPLES)
        drawOval(
            skin.bodyDeep,
            topLeft = Offset(cx + cos(t) * rx * 0.45f - rx * 0.17f, cy + sin(t) * ry * 0.45f - ry * 0.17f),
            size = Size(rx * 0.34f, ry * 0.34f),
        )
    }
}
