package com.t1dm.feature.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.t1dm.ui.game.BIRD_SPEED_MS
import com.t1dm.ui.game.CLOUD_DRIFT_MS
import com.t1dm.ui.game.CLOUD_PARALLAX
import com.t1dm.ui.game.PropField
import com.t1dm.ui.game.PropKind
import com.t1dm.ui.game.PropSet
import com.t1dm.ui.game.groundH
import com.t1dm.ui.game.groundHalfW
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Units either side of the plot a glyph may still reach into it; culling margin. */
private const val REACH_DP = 30f

/** Hours the sun is up, and when the stars are fully out; each fades over the hour beside it. */
private const val SUNRISE_H = 6f
private const val SUNSET_H = 18f
private const val STARS_OUT_H = 20.5f
private const val STARS_GONE_H = 5.5f
private const val STAR_COUNT = 24

private const val TAU = (2.0 * PI).toFloat()

/** Glyph unit in dp: a tree at 17 units stands ~44 dp, two thirds of the 64 dp golfer. */
private const val UNIT_DP = 2.6f

/** Reused draw state: a path, the sign labels laid out once, and the unit every size is in. */
class PropArt(
    private val measurer: TextMeasurer,
    private val labelStyle: TextStyle,
    /** Pixels per dp: prop sizes never track the zoom, like the golfer. */
    dpPx: Float,
) {
    val unitPx = dpPx * UNIT_DP
    val path = Path()
    private val labels = HashMap<Int, TextLayoutResult>()

    /** Sign [i]'s label, measured once; a handful per trace. */
    fun label(i: Int, text: String): TextLayoutResult =
        labels[i] ?: measurer.measure(text, labelStyle).also { labels[i] = it }
}

/** Everything behind the ground line: sun, moon, stars, clouds, birds, balloons, kites. */
internal fun DrawScope.drawSkyProps(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    trackLength: Float,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
    tS: Float,
    hour: Float,
    plotTop: Float,
    plotBottom: Float,
) {
    val u = art.unitPx
    val reach = REACH_DP * u / pxX
    drawSkyBodies(skin, u, hour, tS, size.width, plotTop, plotBottom)

    // Clouds: half the camera's pace, so the sky sits behind the hills, and a slow drift downwind.
    val cloudLeft = CLOUD_PARALLAX * camLeft
    props.clouds.drifting(CLOUD_DRIFT_MS * tS, trackLength, cloudLeft - reach, cloudLeft + camWidth + reach) { i, xw ->
        drawCloud(skin, u, (xw - cloudLeft) * pxX, floorPx - props.clouds.ys[i] * pxY, props.clouds.seeds[i])
    }
    props.birds.drifting(-BIRD_SPEED_MS * tS, trackLength, camLeft - reach, camLeft + camWidth + reach) { i, xw ->
        val seed = props.birds.seeds[i]
        val bob = 3f * u * sin(tS * 0.9f + seed * TAU)
        drawFlock(skin, u, (xw - camLeft) * pxX, floorPx - props.birds.ys[i] * pxY + bob, seed, tS)
    }
    props.sky.visible(camLeft - reach, camLeft + camWidth + reach) { i ->
        val sx = (props.sky.xs[i] - camLeft) * pxX
        val sy = floorPx - props.sky.ys[i] * pxY
        val seed = props.sky.seeds[i]
        when (props.sky.kindAt(i)) {
            PropKind.Balloon -> drawBalloon(skin, u, sx, sy + 4f * u * sin(tS * 0.7f + seed * TAU), seed)
            PropKind.Kite -> drawKite(skin, u, sx, sy, props.sky.amounts[i] * pxY, seed, tS)
            else -> Unit
        }
    }
}

/** Fossils, pipes, aquifers: under the trace in a buried ink, before the ground line goes on. */
internal fun DrawScope.drawUndergroundProps(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
    tS: Float,
) {
    val u = art.unitPx
    val reach = REACH_DP * u / pxX
    val f = props.underground
    f.visible(camLeft - reach, camLeft + camWidth + reach) { i ->
        val sx = (f.xs[i] - camLeft) * pxX
        val sy = floorPx - f.ys[i] * pxY
        val seed = f.seeds[i]
        when (f.kindAt(i)) {
            PropKind.Fossil -> drawFossil(skin, u, sx, sy, seed)
            PropKind.Ammonite -> drawAmmonite(skin, u, sx, sy, seed)
            PropKind.Bone -> drawBone(skin, u, sx, sy, seed)
            PropKind.Pipe -> drawPipe(skin, u, sx, sy, seed, tS)
            PropKind.Manhole -> drawManhole(skin, u, sx, sy)
            PropKind.Aquifer -> drawAquifer(skin, art.path, u, sx, sy, seed, tS)
            PropKind.Vein -> drawVein(skin, art.path, u, sx, sy, seed)
            PropKind.Root -> drawRoots(skin, u, sx, sy, seed)
            PropKind.Chest -> drawChest(skin, u, sx, sy)
            else -> Unit
        }
    }
}

/** Widest ground prop, metres either side; the culling margin for the world-sized layer. */
private const val GROUND_REACH_M = 12f

/** Trees, rocks, a cabin, a windmill: WORLD-sized, so each fills the box its collider is. */
internal fun DrawScope.drawGroundProps(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
    tS: Float,
) {
    val u = art.unitPx
    val f = props.ground
    f.visible(camLeft - GROUND_REACH_M, camLeft + camWidth + GROUND_REACH_M) { i ->
        val sx = (f.xs[i] - camLeft) * pxX
        val sy = floorPx - f.ys[i] * pxY
        val seed = f.seeds[i]
        val kind = f.kindAt(i)
        val w = 2f * groundHalfW(kind, seed) * pxX
        val h = groundH(kind, seed) * pxY
        when (kind) {
            PropKind.Tree -> drawTree(skin, u, sx, sy, w, h)
            PropKind.Pine -> drawPine(skin, art.path, u, sx, sy, w, h)
            PropKind.Bush -> drawBush(skin, sx, sy, w, h)
            PropKind.Tuft -> drawTuft(skin, u, sx, sy, w, h, seed, tS)
            PropKind.Rock -> drawRock(skin, art.path, u, sx, sy, w, h)
            PropKind.Cabin -> drawCabin(skin, art.path, u, sx, sy, w, h, tS)
            PropKind.Windmill -> drawWindmill(skin, art.path, u, sx, sy, w, h, seed, tS)
            PropKind.Fence -> drawFence(skin, u, sx, sy, w, h)
            PropKind.Scarecrow -> drawScarecrow(skin, art.path, u, sx, sy, w, h)
            else -> Unit
        }
    }
}

/** Road signs at every hypo nadir and hyper apex, over the ground line so they stand on it. */
internal fun DrawScope.drawSigns(
    props: PropSet,
    art: PropArt,
    skin: GameSkin,
    camLeft: Float,
    camWidth: Float,
    pxX: Float,
    pxY: Float,
    floorPx: Float,
) {
    val u = art.unitPx
    val reach = REACH_DP * u / pxX
    val f = props.signs
    f.visible(camLeft - reach, camLeft + camWidth + reach) { i ->
        val sx = (f.xs[i] - camLeft) * pxX
        val sy = floorPx - f.ys[i] * pxY
        val low = f.kindAt(i) == PropKind.SignLow
        val label = art.label(i, props.signLabels[i])
        val postH = 14f * u
        drawLine(skin.figure, Offset(sx, sy), Offset(sx, sy - postH), 1f * u, StrokeCap.Round)
        val w = label.size.width + 4f * u
        val h = label.size.height + 2.5f * u
        val top = sy - postH - h
        val border = if (low) skin.signLow else skin.signHigh
        if (low) {
            // A warning triangle sits point-up, so the face is the wide base under the apex.
            val apex = top - 4f * u
            art.path.rewind()
            art.path.moveTo(sx, apex)
            art.path.lineTo(sx + w * 0.5f + 2f * u, top + h)
            art.path.lineTo(sx - w * 0.5f - 2f * u, top + h)
            art.path.close()
            drawPath(art.path, skin.signFace)
            drawPath(art.path, border, style = Stroke(width = 1.2f * u))
            drawText(label, skin.signText, Offset(sx - label.size.width * 0.5f, top + h - label.size.height - 1f * u))
        } else {
            drawRect(skin.signFace, Offset(sx - w * 0.5f, top), Size(w, h))
            drawRect(border, Offset(sx - w * 0.5f, top), Size(w, h), style = Stroke(width = 1.2f * u))
            drawText(label, skin.signText, Offset(sx - label.size.width * 0.5f, top + 1.25f * u))
        }
    }
}

/** Ascending scan of the world-x window; the lambda is inlined, so the loop allocates nothing. */
private inline fun PropField.visible(lo: Float, hi: Float, block: (i: Int) -> Unit) {
    var i = firstFrom(lo)
    while (i < size && xs[i] <= hi) {
        block(i)
        i++
    }
}

/** Same window with every x moved by [shift] and wrapped into `[0, length)`: two scans at most. */
private inline fun PropField.drifting(
    shift: Float,
    length: Float,
    lo: Float,
    hi: Float,
    block: (i: Int, xWorld: Float) -> Unit,
) {
    if (size == 0 || length <= 0f) return
    var a = lo - shift
    var b = hi - shift
    val k = floor(a / length)
    a -= k * length
    b -= k * length
    val back = shift + k * length
    visible(a, minOf(b, length)) { block(it, xs[it] + back) }
    if (b > length) visible(0f, b - length) { block(it, xs[it] + back + length) }
}

// Sky ------------------------------------------------------------------------------------------

private fun DrawScope.drawSkyBodies(
    skin: GameSkin, u: Float, hour: Float, tS: Float, plotW: Float, plotTop: Float, plotBottom: Float,
) {
    if (!hour.isFinite()) return
    val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
    val day = hour in SUNRISE_H..SUNSET_H
    // Both bodies cross the same arc: in at the left, over the top at noon or midnight.
    val phase = if (day) (hour - SUNRISE_H) / (SUNSET_H - SUNRISE_H) else ((hour + 6f) % 24f) / 12f
    val bx = plotW * (0.08f + 0.84f * phase)
    val by = plotTop + plotH * (0.30f - 0.22f * sin(phase * PI.toFloat()))
    if (day) {
        drawCircle(skin.sun, 9f * u, Offset(bx, by))
        for (k in 0 until 8) {
            val a = k * TAU / 8f + tS * 0.05f
            drawLine(
                skin.sun,
                Offset(bx + cos(a) * 12f * u, by + sin(a) * 12f * u),
                Offset(bx + cos(a) * 16f * u, by + sin(a) * 16f * u),
                1.5f * u,
                StrokeCap.Round,
            )
        }
    } else {
        drawCircle(skin.moon, 8f * u, Offset(bx, by))
        drawCircle(skin.sky, 7f * u, Offset(bx + 4f * u, by - 2f * u))
    }
    val night = when {
        hour >= STARS_OUT_H -> (hour - STARS_OUT_H).coerceIn(0f, 1f)
        hour <= STARS_GONE_H -> (STARS_GONE_H - hour).coerceIn(0f, 1f)
        else -> 0f
    }
    if (night > 0f) {
        for (k in 0 until STAR_COUNT) {
            // A fixed constellation: golden-ratio stepping spreads the stars without a table.
            val fx = (k * 0.6180339f) % 1f
            val fy = ((k * 0.7548777f) % 1f) * 0.45f
            val twinkle = 0.55f + 0.45f * sin(tS * 2f + k * 1.7f)
            drawCircle(
                skin.star.copy(alpha = skin.star.alpha * night * twinkle),
                1.2f * u,
                Offset(plotW * fx, plotTop + plotH * fy),
            )
        }
    }
}

private fun DrawScope.drawCloud(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float) {
    val s = u * (0.7f + 0.7f * seed)
    drawCircle(skin.cloud, 7f * s, Offset(x - 7f * s, y + 1f * s))
    drawCircle(skin.cloud, 10f * s, Offset(x, y - 3f * s))
    drawCircle(skin.cloud, 7f * s, Offset(x + 8f * s, y + 1f * s))
    drawRect(skin.cloud, Offset(x - 12f * s, y + 1f * s), Size(25f * s, 5f * s))
}

private fun DrawScope.drawFlock(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float, tS: Float) {
    val n = 3 + (seed * 3f).toInt()
    val flap = 0.35f + 0.45f * sin(tS * 7f + seed * TAU)
    for (k in 0 until n) {
        // A V: the leader ahead, the rest trailing behind and out on alternate sides.
        val row = (k + 1) / 2
        val side = if (k % 2 == 0) 1f else -1f
        val bx = x + row * 9f * u
        val by = if (k == 0) y else y + side * row * 5f * u
        val wing = 4f * u
        val lift = wing * flap
        drawLine(skin.bird, Offset(bx - wing, by - lift), Offset(bx, by), 1.4f * u, StrokeCap.Round)
        drawLine(skin.bird, Offset(bx, by), Offset(bx + wing, by - lift), 1.4f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawBalloon(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float) {
    val r = 8f * u
    drawCircle(skin.balloon, r, Offset(x, y))
    drawCircle(skin.balloonBand, r, Offset(x, y), style = Stroke(width = 1.2f * u))
    drawLine(skin.balloonBand, Offset(x, y - r), Offset(x, y + r), 1f * u)
    val basketY = y + r + 7f * u
    drawLine(skin.figure, Offset(x - 3f * u, y + r * 0.8f), Offset(x - 2f * u, basketY), 1f * u)
    drawLine(skin.figure, Offset(x + 3f * u, y + r * 0.8f), Offset(x + 2f * u, basketY), 1f * u)
    drawRect(skin.wood, Offset(x - 3f * u, basketY), Size(6f * u, 4f * u))
}

private fun DrawScope.drawKite(skin: GameSkin, u: Float, x: Float, y: Float, stringPx: Float, seed: Float, tS: Float) {
    val sway = 6f * u * sin(tS * 1.3f + seed * TAU)
    val kx = x + sway
    // The string leaves the ground where the kite is anchored, so a gust only bends it.
    drawLine(skin.string, Offset(x, y + stringPx), Offset(kx, y + 6f * u), 1f * u)
    drawLine(skin.kite, Offset(kx, y - 8f * u), Offset(kx + 6f * u, y), 2f * u, StrokeCap.Round)
    drawLine(skin.kite, Offset(kx + 6f * u, y), Offset(kx, y + 6f * u), 2f * u, StrokeCap.Round)
    drawLine(skin.kite, Offset(kx, y + 6f * u), Offset(kx - 6f * u, y), 2f * u, StrokeCap.Round)
    drawLine(skin.kite, Offset(kx - 6f * u, y), Offset(kx, y - 8f * u), 2f * u, StrokeCap.Round)
    var tx = kx
    var ty = y + 6f * u
    for (k in 0 until 3) {
        val nx = tx + 3f * u * (if (k % 2 == 0) -1f else 1f) + sway * 0.2f
        val ny = ty + 4f * u
        drawLine(skin.kite, Offset(tx, ty), Offset(nx, ny), 1.2f * u, StrokeCap.Round)
        tx = nx
        ty = ny
    }
}

// Underground ----------------------------------------------------------------------------------

private fun DrawScope.drawFossil(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float) {
    val s = u * (0.8f + 0.6f * seed)
    val w = 1.4f * u
    drawLine(skin.buried, Offset(x - 12f * s, y), Offset(x + 10f * s, y), w, StrokeCap.Round)
    for (k in 0 until 5) {
        val rx = x - 8f * s + k * 4f * s
        drawLine(skin.buried, Offset(rx, y), Offset(rx - 1.5f * s, y - 5f * s), w, StrokeCap.Round)
        drawLine(skin.buried, Offset(rx, y), Offset(rx - 1.5f * s, y + 5f * s), w, StrokeCap.Round)
    }
    drawCircle(skin.buried, 3.5f * s, Offset(x + 12f * s, y), style = Stroke(width = w))
    drawLine(skin.buried, Offset(x - 12f * s, y), Offset(x - 16f * s, y - 4f * s), w, StrokeCap.Round)
    drawLine(skin.buried, Offset(x - 12f * s, y), Offset(x - 16f * s, y + 4f * s), w, StrokeCap.Round)
}

private fun DrawScope.drawAmmonite(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float) {
    val s = u * (0.8f + 0.6f * seed)
    val w = 1.4f * u
    // Four half-turns, each tighter than the last, alternating sides: a spiral in arcs.
    var r = 9f * s
    var cx = x
    var start = 180f
    for (k in 0 until 4) {
        drawArc(skin.buried, start, 180f, false, Offset(cx - r, y - r), Size(2f * r, 2f * r), style = Stroke(width = w))
        val next = r * 0.62f
        cx += if (k % 2 == 0) (r - next) else -(r - next)
        r = next
        start += 180f
    }
}

private fun DrawScope.drawBone(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float) {
    val s = u * (0.8f + 0.6f * seed)
    rotate(-30f + 60f * seed, Offset(x, y)) {
        drawLine(skin.buried, Offset(x - 8f * s, y), Offset(x + 8f * s, y), 2.4f * s, StrokeCap.Round)
        for (side in intArrayOf(-1, 1)) {
            drawCircle(skin.buried, 2.4f * s, Offset(x + side * 8f * s, y - 1.8f * s))
            drawCircle(skin.buried, 2.4f * s, Offset(x + side * 8f * s, y + 1.8f * s))
        }
    }
}

private fun DrawScope.drawPipe(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float, tS: Float) {
    val half = 18f * u
    val h = 6f * u
    drawRect(skin.buried, Offset(x - half, y - h * 0.5f), Size(2f * half, h), style = Stroke(width = 1.4f * u))
    for (side in intArrayOf(-1, 1)) {
        drawRect(skin.buried, Offset(x + side * half - 1.5f * u, y - h * 0.5f - 1.5f * u), Size(3f * u, h + 3f * u))
    }
    // Flow: ticks marching along the bore.
    val step = 9f * u
    var tx = x - half + 3f * u + (tS * 6f * u + seed * step) % step
    while (tx < x + half - 3f * u) {
        drawLine(skin.water, Offset(tx, y + 1f * u), Offset(tx + 3f * u, y + 1f * u), 1.5f * u, StrokeCap.Round)
        tx += step
    }
}

private fun DrawScope.drawManhole(skin: GameSkin, u: Float, x: Float, y: Float) {
    drawLine(skin.buried, Offset(x - 4f * u, y), Offset(x - 4f * u, y + 16f * u), 1.4f * u)
    drawLine(skin.buried, Offset(x + 4f * u, y), Offset(x + 4f * u, y + 16f * u), 1.4f * u)
    for (k in 1..3) {
        val ry = y + k * 4f * u
        drawLine(skin.buried, Offset(x - 4f * u, ry), Offset(x + 4f * u, ry), 1.2f * u)
    }
    drawOval(skin.stone, Offset(x - 6f * u, y - 1.5f * u), Size(12f * u, 3f * u))
}

private fun DrawScope.drawAquifer(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, seed: Float, tS: Float) {
    val w = 22f * u * (0.8f + 0.6f * seed)
    val h = 7f * u
    drawOval(skin.water, Offset(x - w, y - h), Size(2f * w, 2f * h))
    // Three ripples, the phase creeping so the lens reads as water and not a stone.
    for (row in 0 until 3) {
        val ry = y - h * 0.5f + row * h * 0.5f
        val span = w * (0.85f - 0.15f * row)
        path.rewind()
        var px = -span
        path.moveTo(x + px, ry + 1.5f * u * sin(px / (5f * u) + tS * 1.5f + row))
        px += 2f * u
        while (px <= span) {
            path.lineTo(x + px, ry + 1.5f * u * sin(px / (5f * u) + tS * 1.5f + row))
            px += 2f * u
        }
        drawPath(path, skin.waterLine, style = Stroke(width = 1.2f * u))
    }
}

private fun DrawScope.drawVein(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, seed: Float) {
    path.rewind()
    var px = x - 15f * u
    var py = y
    path.moveTo(px, py)
    for (k in 0 until 6) {
        px += 5f * u
        py += (if (k % 2 == 0) -1f else 1f) * (2f + 3f * seed) * u
        path.lineTo(px, py)
    }
    drawPath(path, skin.ore, style = Stroke(width = 2f * u, cap = StrokeCap.Round))
}

private fun DrawScope.drawRoots(skin: GameSkin, u: Float, x: Float, y: Float, seed: Float) {
    val w = 1.4f * u
    val reach = 10f * u * (0.8f + 0.5f * seed)
    drawLine(skin.buried, Offset(x, y), Offset(x - reach * 0.7f, y + reach), w, StrokeCap.Round)
    drawLine(skin.buried, Offset(x, y), Offset(x, y + reach * 1.2f), w, StrokeCap.Round)
    drawLine(skin.buried, Offset(x, y), Offset(x + reach * 0.7f, y + reach), w, StrokeCap.Round)
    drawLine(skin.buried, Offset(x - reach * 0.35f, y + reach * 0.5f), Offset(x - reach * 0.8f, y + reach * 0.4f), w, StrokeCap.Round)
    drawLine(skin.buried, Offset(x + reach * 0.35f, y + reach * 0.5f), Offset(x + reach * 0.8f, y + reach * 0.4f), w, StrokeCap.Round)
}

private fun DrawScope.drawChest(skin: GameSkin, u: Float, x: Float, y: Float) {
    val w = 14f * u
    val h = 8f * u
    drawRect(skin.wood, Offset(x - w * 0.5f, y - h * 0.5f), Size(w, h))
    drawRect(skin.buried, Offset(x - w * 0.5f, y - h * 0.5f), Size(w, h), style = Stroke(width = 1.4f * u))
    drawArc(skin.buried, 180f, 180f, false, Offset(x - w * 0.5f, y - h * 0.5f - 3f * u), Size(w, 6f * u), style = Stroke(width = 1.4f * u))
    drawCircle(skin.ore, 1.6f * u, Offset(x, y))
}

// Ground ---------------------------------------------------------------------------------------

// Each glyph fills the w × h box its collider is; x is the box's centre, y its ground line.

private fun DrawScope.drawTree(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawRect(skin.wood, Offset(x - w * 0.09f, y - h * 0.55f), Size(w * 0.18f, h * 0.55f))
    drawOval(skin.leaf, Offset(x - w * 0.5f, y - h * 0.78f), Size(w * 0.62f, h * 0.44f))
    drawOval(skin.leaf, Offset(x - w * 0.12f, y - h * 0.78f), Size(w * 0.62f, h * 0.44f))
    drawOval(skin.leaf, Offset(x - w * 0.34f, y - h), Size(w * 0.68f, h * 0.5f))
}

private fun DrawScope.drawPine(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawRect(skin.wood, Offset(x - w * 0.07f, y - h * 0.3f), Size(w * 0.14f, h * 0.3f))
    var base = y - h * 0.22f
    var half = w * 0.5f
    for (k in 0 until 3) {
        path.rewind()
        path.moveTo(x, base - h * 0.42f)
        path.lineTo(x + half, base)
        path.lineTo(x - half, base)
        path.close()
        drawPath(path, skin.leaf)
        base -= h * 0.18f
        half *= 0.72f
    }
}

private fun DrawScope.drawBush(skin: GameSkin, x: Float, y: Float, w: Float, h: Float) {
    drawOval(skin.leaf, Offset(x - w * 0.5f, y - h * 0.8f), Size(w * 0.5f, h * 0.8f))
    drawOval(skin.leaf, Offset(x, y - h * 0.8f), Size(w * 0.5f, h * 0.8f))
    drawOval(skin.leaf, Offset(x - w * 0.3f, y - h), Size(w * 0.6f, h))
}

private fun DrawScope.drawTuft(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    val lean = w * 0.2f * sin(tS * 1.7f + seed * TAU)
    for (k in -1..1) {
        drawLine(skin.leaf, Offset(x + k * w * 0.2f, y), Offset(x + k * w * 0.45f + lean, y - h), 1.2f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawRock(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float) {
    path.rewind()
    path.moveTo(x - w * 0.5f, y)
    path.lineTo(x - w * 0.36f, y - h * 0.7f)
    path.lineTo(x + w * 0.07f, y - h)
    path.lineTo(x + w * 0.43f, y - h * 0.55f)
    path.lineTo(x + w * 0.5f, y)
    path.close()
    drawPath(path, skin.stone)
    drawLine(skin.buried, Offset(x - w * 0.14f, y - h * 0.75f), Offset(x + w * 0.14f, y - h * 0.3f), 1f * u, StrokeCap.Round)
}

private fun DrawScope.drawCabin(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float, tS: Float) {
    val wall = h * 0.6f
    drawRect(skin.wood, Offset(x - w * 0.5f, y - wall), Size(w, wall))
    path.rewind()
    path.moveTo(x - w * 0.58f, y - wall)
    path.lineTo(x, y - h)
    path.lineTo(x + w * 0.58f, y - wall)
    path.close()
    drawPath(path, skin.roof)
    drawRect(skin.figure, Offset(x - w * 0.1f, y - wall * 0.55f), Size(w * 0.2f, wall * 0.55f))
    drawRect(skin.window, Offset(x + w * 0.18f, y - wall * 0.75f), Size(w * 0.2f, wall * 0.3f))
    drawRect(skin.stone, Offset(x + w * 0.22f, y - h * 0.98f), Size(w * 0.14f, h * 0.3f))
    // Smoke: three puffs rising on a three-second loop, growing as they thin out.
    for (k in 0 until 3) {
        val t = ((tS + k * 1f) % 3f) / 3f
        drawCircle(
            skin.smoke.copy(alpha = (1f - t) * 0.6f),
            (1.5f + 3f * t) * u,
            Offset(x + w * 0.29f + w * 0.2f * t, y - h - 2f * u - h * 0.35f * t),
        )
    }
}

private fun DrawScope.drawWindmill(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float, seed: Float, tS: Float) {
    val tower = h * 0.9f
    path.rewind()
    path.moveTo(x - w * 0.5f, y)
    path.lineTo(x + w * 0.5f, y)
    path.lineTo(x + w * 0.22f, y - tower)
    path.lineTo(x - w * 0.22f, y - tower)
    path.close()
    drawPath(path, skin.stone)
    val hubY = y - tower
    val spin = tS * (0.6f + 0.6f * seed)
    val blade = h * 0.5f
    for (k in 0 until 4) {
        val a = spin + k * TAU / 4f
        drawLine(skin.figure, Offset(x, hubY), Offset(x + cos(a) * blade, hubY + sin(a) * blade), 1.6f * u, StrokeCap.Round)
        // Each sail: a slat off the blade's leading edge.
        val bx = x + cos(a) * blade * 0.7f
        val by = hubY + sin(a) * blade * 0.7f
        drawLine(skin.cloth, Offset(bx, by), Offset(bx - sin(a) * blade * 0.25f, by + cos(a) * blade * 0.25f), 3f * u)
    }
    drawCircle(skin.figure, 1.6f * u, Offset(x, hubY))
}

private fun DrawScope.drawFence(skin: GameSkin, u: Float, x: Float, y: Float, w: Float, h: Float) {
    for (k in -2..2) {
        val px = x + k * w * 0.23f
        drawLine(skin.wood, Offset(px, y), Offset(px, y - h), 1.6f * u, StrokeCap.Round)
    }
    drawLine(skin.wood, Offset(x - w * 0.5f, y - h * 0.75f), Offset(x + w * 0.5f, y - h * 0.75f), 1.2f * u)
    drawLine(skin.wood, Offset(x - w * 0.5f, y - h * 0.35f), Offset(x + w * 0.5f, y - h * 0.35f), 1.2f * u)
}

private fun DrawScope.drawScarecrow(skin: GameSkin, path: Path, u: Float, x: Float, y: Float, w: Float, h: Float) {
    drawLine(skin.wood, Offset(x, y), Offset(x, y - h * 0.75f), 1.6f * u, StrokeCap.Round)
    drawLine(skin.wood, Offset(x - w * 0.5f, y - h * 0.55f), Offset(x + w * 0.5f, y - h * 0.55f), 1.6f * u, StrokeCap.Round)
    path.rewind()
    path.moveTo(x - w * 0.36f, y - h * 0.18f)
    path.lineTo(x - w * 0.22f, y - h * 0.55f)
    path.lineTo(x + w * 0.22f, y - h * 0.55f)
    path.lineTo(x + w * 0.36f, y - h * 0.18f)
    path.close()
    drawPath(path, skin.cloth)
    drawOval(skin.straw, Offset(x - w * 0.22f, y - h * 0.9f), Size(w * 0.44f, h * 0.16f))
    drawLine(skin.figure, Offset(x - w * 0.34f, y - h * 0.9f), Offset(x + w * 0.34f, y - h * 0.9f), 1.4f * u, StrokeCap.Round)
    drawRect(skin.figure, Offset(x - w * 0.18f, y - h), Size(w * 0.36f, h * 0.1f))
}
