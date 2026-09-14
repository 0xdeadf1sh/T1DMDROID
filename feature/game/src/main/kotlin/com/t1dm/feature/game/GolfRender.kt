package com.t1dm.feature.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.t1dm.core.model.GolfCup
import com.t1dm.core.model.GolfRun
import com.t1dm.core.model.GolfTuning
import com.t1dm.ui.game.GameTrack
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Drawn radius as a share of the true one, and its px rails: 3 m of ball is a boulder. */
private const val BALL_DRAW_FRAC = 0.3f
private const val BALL_MIN_PX = 6f
private const val BALL_MAX_PX = 8f

/** Flag height in ball radii, and the pennant's own reach. */
private const val FLAG_POLE_R = 6f
private const val FLAG_FLY_R = 2.4f

/** The lit body inside the ball's own disc, and how far up-right it sits; the rest is shadow. */
private const val BALL_LIT = 0.78f
private const val BALL_LIGHT_R = 0.20f

/** The figure's proportions, as shares of its screen height: feet at 0, crown at 1. */
private const val HIP_H = 0.47f
private const val SHOULDER_H = 0.78f
private const val NECK_H = 0.105f
private const val HEAD_R_H = 0.062f
private const val ARM_H = 0.33f
private const val CLUB_H = 0.46f

/** Half-widths of the torso quad — shoulders over hips — and the feet's spread at a stance. */
private const val SHOULDER_W = 0.105f
private const val HIP_W = 0.072f
private const val FOOT_SPREAD = 0.075f

/** Run cycle: the stride's reach, the foot's lift, and the bob over the planted foot. */
private const val STRIDE_H = 0.20f
private const val LIFT_H = 0.11f
private const val BOB_H = 0.022f

/** Knee flex: how far it drops the hip, and how far forward it carries the knee. */
private const val KNEE_DROP_H = 0.06f
private const val KNEE_FWD_H = 0.055f

/** Weight transfer: the hip's travel across the feet, and the back heel's lift. */
private const val WEIGHT_X_H = 0.05f
private const val HEEL_H = 0.045f

/** Radians the back bends at a full knee flex; an address IS a bent back over flexed knees. */
private const val BACK_RAD = 0.30f

/** Elbow bow behind the arm's line, and the further fold the trailing arm takes at the top. */
private const val ELBOW_H = 0.045f
private const val ELBOW_FOLD = 1.6f

/** Running: radians of arm counter-swing, and radians the carried club tilts back. */
private const val RUN_ARM_RAD = 0.55f
private const val CARRY_RAD = 2f

/** A cheer's hop off the turf, and where over the head it throws the hands. */
private const val HOP_H = 0.05f
private const val HAND_UP_X = 0.16f
private const val HAND_UP_Y = 0.30f

/** Club head: its reach past the shaft, square to it, and its own thickness. */
private const val CLUB_HEAD_L = 0.055f
private const val CLUB_HEAD_W = 0.030f

/** Line weights, all shares of the figure's height. */
private const val LIMB_W = 0.030f
private const val ARM_W = 0.026f
private const val CLUB_W = 0.016f
private const val SHOE_W = 0.028f
private const val SHOE_L = 0.085f
private const val HAND_R_H = 0.022f

/** Brim: its thickness as a share of the height, its reach in head radii. */
private const val BRIM_W = 0.022f
private const val BRIM_R = 1.7f

/** Radians the club tilts forward at address; with [ARM_H] and [CLUB_H] it reaches the ball. */
private const val ADDRESS_RAD = -0.35f

/** Where the club head lands at address, in heights; the stance IS this, so it meets the ball. */
internal val STANCE_H =
    (SHOULDER_H - HIP_H) * sin(BACK_RAD * ADDRESS_BEND) + (ARM_H + CLUB_H) * sin(-ADDRESS_RAD)

/** Dash and gap of the preview arc, in pixels. */
private const val ARC_DASH_PX = 9f

/** Splash ring's reach in DRAWN ball radii, and its stroke. */
private const val SPLASH_R = 5f
private const val SPLASH_W_PX = 3f

/** Tee mark height in DRAWN ball radii. */
private const val TEE_R = 4f

/** Ball, cup and flag geometry; the hole arrives from the loop once the world is built. */
internal class GolfArt(tuning: GolfTuning) {
    val ballRadius = tuning.ballRadius
    val gravity = tuning.gravity
    val maxLaunchSpeed = tuning.maxLaunchSpeed

    /** Rust's, not derived here; null until the loop has opened the world. */
    @Volatile
    var cup: GolfCup? = null

    /** The figure's height in px: one dp conversion, done in composition, never a zoom of it. */
    var golferPx = 0f

    /** Reused per frame; a Path rewound is a Path reused. */
    val cupPath = Path()
    val flagPath = Path()
    val bodyPath = Path()

    /** Styles outlive the frame too: a `Stroke(...)` in the draw is an allocation at 60 Hz. */
    val cupStroke = Stroke(width = 2.2f)
    val ballStroke = Stroke(width = 1f)
    val splashStroke = Stroke(width = SPLASH_W_PX)

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
    drawPath(art.cupPath, skin.trace, style = art.cupStroke)

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
    // Off the DRAWN ball: a mark the ball cannot be read against is furniture, not a measure.
    val h = TEE_R * ballPx(art, pxX)
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
    /** Physical centre to drawn centre: the dashes leave the ball that is on the panel. */
    ballDy: Float,
) {
    if (n < 2) return
    var carry = 0f
    var on = true
    var px = (art.arc[0] - camLeft) * pxX
    var py = floorPx - art.arc[1] * pxY + ballDy
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
    val cx = (f.splashX - camLeft) * pxX
    val cy = floorPx - f.splashY * pxY
    // Round and off the drawn ball: the ring is what swallowed THAT disc.
    val r = ballPx(art, pxX) * (1f + SPLASH_R * (1f - life))
    drawCircle(skin.trace.copy(alpha = life * 0.7f), r, Offset(cx, cy), style = art.splashStroke)
}

/** Drawn radius, railed in px: at true scale a 3 m ball is a boulder on the panel. */
internal fun ballPx(art: GolfArt, pxX: Float): Float =
    (art.ballRadius * pxX * BALL_DRAW_FRAC).coerceIn(BALL_MIN_PX, BALL_MAX_PX)

/** Screen y of the DRAWN ball's centre; the disc is anchored at its contact point. */
internal fun ballCentrePx(art: GolfArt, ballY: Float, pxY: Float, floorPx: Float, rPx: Float): Float =
    floorPx - (ballY - art.ballRadius) * pxY - rPx

/** ROUND, unlike the car: the world's two scales differ, and a stretched ball reads as a bowl. */
internal fun DrawScope.drawBall(
    art: GolfArt,
    f: BallFrame,
    skin: GameSkin,
    camLeft: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    val r = ballPx(art, pxX)
    val cx = (f.x - camLeft) * pxX
    val cy = ballCentrePx(art, f.y, pxY, floorPx, r)
    if (cx + r < 0f || cx - r > size.width) return

    drawCircle(skin.ballShade, r, Offset(cx, cy))
    // The lit body, offset up-right; what the shade disc still shows is the crescent under it.
    drawCircle(skin.ball, r * BALL_LIT, Offset(cx + r * BALL_LIGHT_R, cy - r * BALL_LIGHT_R))
    // No seam: at five pixels across an arc inside the disc is a smudge. The crescent is not.
    drawCircle(skin.ballEdge, r, Offset(cx, cy), style = art.ballStroke)
}

/** Two segments, the elbow bowed BACK off the line the hand hangs on. */
private fun DrawScope.drawArm(
    ink: Color,
    sx: Float,
    sy: Float,
    hx: Float,
    hy: Float,
    face: Float,
    bow: Float,
    w: Float,
) {
    val dx = hx - sx
    val dy = hy - sy
    val len = hypot(dx, dy)
    val k = if (len > 1e-3f) bow / len else 0f
    val ex = (sx + hx) * 0.5f - face * dy * k
    val ey = (sy + hy) * 0.5f + face * dx * k
    drawLine(ink, Offset(sx, sy), Offset(ex, ey), w, StrokeCap.Round)
    drawLine(ink, Offset(ex, ey), Offset(hx, hy), w, StrokeCap.Round)
}

/** Fixed dp tall and ISOTROPIC; the loop owns the act, the draw owns where the pixels land. */
internal fun DrawScope.drawGolfer(
    art: GolfArt,
    f: BallFrame,
    skin: GameSkin,
    track: GameTrack,
    camLeft: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    if (!f.golferShown) return
    val h = art.golferPx
    if (h <= 0f) return
    val walk = f.golferWalk.coerceIn(0f, 1f)
    val wx = f.golferX
    val fx = (wx - camLeft) * pxX
    if (fx + h < 0f || fx - h > size.width) return
    val under = track.groundAt(wx)
    val fy = floorPx - (if (under.isFinite()) under else f.golferY) * pxY

    val face = if (f.golferFacing < 0f) -1f else 1f
    val bend = f.kneeBend.coerceIn(0f, 1f)
    val fwd = f.weightFwd.coerceIn(0f, 1f) - 0.5f
    val up = f.armsUp.coerceIn(0f, 1f)
    val both = if (f.run == GolfRun.Holed.ordinal) up else 0f
    val phase = f.legPhase
    // A cheer takes the feet with it; a run's bob is the body rising over the planted foot.
    val hop = HOP_H * h * both
    val bob = BOB_H * h * abs(sin(phase)) * walk

    val limb = LIMB_W * h
    val hipX = fx + face * WEIGHT_X_H * h * fwd
    val hipY = fy - HIP_H * h + KNEE_DROP_H * h * bend - hop - bob
    val tilt = f.lean + BACK_RAD * bend
    val torso = (SHOULDER_H - HIP_H) * h
    val shX = hipX + face * torso * sin(tilt)
    val shY = hipY - torso * cos(tilt)

    // Legs first: the shirt is filled over the hip they hang from. k=0 leads, k=1 trails.
    for (k in 0..1) {
        val t = phase + k * Math.PI.toFloat()
        val lift = cos(t).coerceAtLeast(0f) * walk
        val spread = if (k == 0) FOOT_SPREAD else -FOOT_SPREAD
        val footX = fx + face * h * (STRIDE_H * sin(t) * walk + spread * (1f - walk))
        val heel = if (k == 0) 0f else HEEL_H * h * (2f * fwd).coerceIn(0f, 1f) * (1f - walk)
        val footY = fy - LIFT_H * h * lift - heel - hop
        val kneeX = (hipX + footX) * 0.5f + face * KNEE_FWD_H * h * (bend + lift)
        val kneeY = (hipY + footY) * 0.5f
        drawLine(skin.trousers, Offset(hipX, hipY), Offset(kneeX, kneeY), limb, StrokeCap.Round)
        drawLine(skin.trousers, Offset(kneeX, kneeY), Offset(footX, footY), limb, StrokeCap.Round)
        drawLine(
            skin.figure,
            Offset(footX - face * SHOE_L * h * 0.25f, footY),
            Offset(footX + face * SHOE_L * h * 0.75f, footY),
            SHOE_W * h,
            StrokeCap.Round,
        )
    }

    val sa = ADDRESS_RAD + f.shoulderRad + walk * RUN_ARM_RAD * sin(phase)
    val gripX = shX - face * ARM_H * h * sin(sa)
    val gripY = shY + ARM_H * h * cos(sa)
    val headX = shX + face * NECK_H * h * sin(tilt * 0.5f)
    val headY = shY - NECK_H * h * cos(tilt * 0.5f)
    val headR = HEAD_R_H * h
    // Shading the eyes puts the hand on the brim; a cheer throws it over the crown instead.
    val brimX = headX + face * headR * BRIM_R
    val brimY = headY - headR * 0.3f
    val overX = headX + face * HAND_UP_X * h
    val overY = headY - HAND_UP_Y * h
    val leadX = gripX + (brimX + (overX - brimX) * both - gripX) * up
    val leadY = gripY + (brimY + (overY - brimY) * both - gripY) * up
    val trailX = gripX + (headX - face * HAND_UP_X * h - gripX) * both
    val trailY = gripY + (overY - gripY) * both
    val fold = (f.shoulderRad / BACKSWING_RAD).coerceIn(0f, 1f)

    // Under the shirt, so the figure has a front and a back.
    drawArm(skin.flesh, shX, shY, trailX, trailY, face, ELBOW_H * h * (1f + ELBOW_FOLD * fold), ARM_W * h)

    val nx = cos(tilt)
    val ny = face * sin(tilt)
    val body = art.bodyPath
    body.rewind()
    body.moveTo(shX + nx * SHOULDER_W * h, shY + ny * SHOULDER_W * h)
    body.lineTo(shX - nx * SHOULDER_W * h, shY - ny * SHOULDER_W * h)
    body.lineTo(hipX - nx * HIP_W * h, hipY - ny * HIP_W * h)
    body.lineTo(hipX + nx * HIP_W * h, hipY + ny * HIP_W * h)
    body.close()
    drawPath(body, skin.shirt, style = Fill)

    drawLine(skin.flesh, Offset(shX, shY), Offset(headX, headY), limb * 0.8f, StrokeCap.Round)
    drawCircle(skin.flesh, headR, Offset(headX, headY))
    // 180°→360° is the TOP half on a y-down canvas; the brim is a thick line to the front.
    drawArc(
        skin.cap,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(headX - headR, headY - headR),
        size = Size(2f * headR, 2f * headR),
        style = Fill,
    )
    drawLine(skin.cap, Offset(headX, brimY), Offset(brimX, brimY), BRIM_W * h, StrokeCap.Butt)

    drawArm(skin.flesh, shX, shY, leadX, leadY, face, ELBOW_H * h, ARM_W * h)
    val handR = HAND_R_H * h
    drawCircle(skin.flesh, handR, Offset(leadX, leadY))
    drawCircle(skin.flesh, handR, Offset(trailX, trailY))

    // A cheer has both hands up, so there is no grip left to hang the club from.
    if (both > 0.5f) return
    val ca = ADDRESS_RAD + f.clubRad + walk * CARRY_RAD
    val ux = -face * sin(ca)
    val uy = cos(ca)
    val tipX = gripX + ux * CLUB_H * h
    val tipY = gripY + uy * CLUB_H * h
    drawLine(skin.club, Offset(gripX, gripY), Offset(tipX, tipY), CLUB_W * h, StrokeCap.Round)
    // Square to the shaft and thick: a butt-capped line IS a filled head, and costs no path.
    drawLine(
        skin.club,
        Offset(tipX, tipY),
        Offset(tipX + face * uy * CLUB_HEAD_L * h, tipY - face * ux * CLUB_HEAD_L * h),
        CLUB_HEAD_W * h,
        StrokeCap.Butt,
    )
}
