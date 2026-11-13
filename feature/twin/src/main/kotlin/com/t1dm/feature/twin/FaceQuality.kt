package com.t1dm.feature.twin

import kotlin.math.abs
import kotlin.math.hypot

/** What blocks a capture, in the order the scan asks for it. */
internal enum class FaceIssue(val prompt: String, val group: Group) {
    DARK("Too dark", Group.LIGHT),
    BACKLIT("Light behind you", Group.LIGHT),
    BRIGHT("Too bright", Group.LIGHT),
    UNEVEN("Light on one side", Group.LIGHT),
    GLASSES("Remove glasses", Group.VIEW),
    COVERED("Uncover mouth and chin", Group.VIEW),
    FOREHEAD("Uncover forehead", Group.VIEW),
    BLUR("Blurry · hold still", Group.FOCUS),
    ;

    enum class Group { LIGHT, VIEW, FOCUS }
}

/** Luma is BT.601 on 0–255; ratios compare a patch to the mean of both cheeks. */
internal data class FaceMetrics(
    val luma: Float,
    val clipped: Float,
    val frameLuma: Float,
    val sideBalance: Float,
    val sharpness: Float,
    val bridgeEdges: Float,
    val eyeShade: Float,
    val lowerChroma: Float,
    val lowerShade: Float,
    val foreChroma: Float,
    val foreShade: Float,
) {
    /** Untuned thresholds; the tracker logs every metric so they can be set from real frames. */
    fun issue(): FaceIssue? = when {
        luma < 60f -> FaceIssue.DARK
        frameLuma - luma > 50f && luma < 110f -> FaceIssue.BACKLIT
        clipped > 0.15f -> FaceIssue.BRIGHT
        sideBalance > 0.25f -> FaceIssue.UNEVEN
        eyeShade < 0.4f || bridgeEdges > 3.5f -> FaceIssue.GLASSES
        // Dark coverings pass: a beard reads the same, so only light ones (masks) block.
        lowerChroma > 0.07f && lowerShade > 0.6f -> FaceIssue.COVERED
        foreChroma > 0.07f && foreShade > 0.6f -> FaceIssue.FOREHEAD
        sharpness < 15f -> FaceIssue.BLUR
        else -> null
    }

    /** Exponential smoothing; [k] is the weight of [next]. */
    fun blend(next: FaceMetrics, k: Float): FaceMetrics {
        fun m(a: Float, b: Float) = a + (b - a) * k
        return FaceMetrics(
            m(luma, next.luma), m(clipped, next.clipped), m(frameLuma, next.frameLuma),
            m(sideBalance, next.sideBalance), m(sharpness, next.sharpness), m(bridgeEdges, next.bridgeEdges),
            m(eyeShade, next.eyeShade), m(lowerChroma, next.lowerChroma), m(lowerShade, next.lowerShade),
            m(foreChroma, next.foreChroma), m(foreShade, next.foreShade),
        )
    }
}

/** Image checks on [px] (ARGB, row-major, upright [w]×[h]) around the landmarks of [f]. */
internal class FaceQuality(private val px: IntArray, private val w: Int, private val h: Int, private val f: FaceFrame) {
    private val faceW = hypot((f.x(CHEEK_L) - f.x(CHEEK_R)) * w, (f.y(CHEEK_L) - f.y(CHEEK_R)) * h)

    private class Stats(val r: Float, val g: Float, val b: Float, val luma: Float, val clipped: Float, val grad: Float) {
        val chromaR get() = r / maxOf(r + g + b, 1f)
        val chromaG get() = g / maxOf(r + g + b, 1f)
    }

    fun measure(): FaceMetrics {
        val face = box(intArrayOf(CHEEK_L, CHEEK_R, FOREHEAD_TOP, CHIN), shrink = 0.15f, step = 2)
        val frame = stats(1, 1, w - 2, h - 2, step = 8)
        val cheekL = patch(CHEEK_IN_L, 0.08f)
        val cheekR = patch(CHEEK_IN_R, 0.08f)
        val skin = Stats(
            (cheekL.r + cheekR.r) / 2, (cheekL.g + cheekR.g) / 2, (cheekL.b + cheekR.b) / 2,
            (cheekL.luma + cheekR.luma) / 2, 0f, 0f,
        )
        val fore = patch(FOREHEAD, 0.06f)
        val bridge = patch(BRIDGE, 0.07f, 0.035f)
        val eyes = (patch(EYE_R_TOP, EYE_R_BOTTOM, 0.03f).luma + patch(EYE_L_TOP, EYE_L_BOTTOM, 0.03f).luma) / 2
        val lower = patch(LIP_LOW, CHIN, 0.05f)
        val skinL = maxOf(skin.luma, 1f)
        return FaceMetrics(
            luma = face.luma,
            clipped = face.clipped,
            frameLuma = frame.luma,
            sideBalance = abs(cheekL.luma - cheekR.luma) / maxOf(cheekL.luma + cheekR.luma, 1f),
            sharpness = sharpness(),
            bridgeEdges = bridge.grad / maxOf(fore.grad, 2f),
            eyeShade = eyes / skinL,
            lowerChroma = chroma(lower, skin),
            lowerShade = lower.luma / skinL,
            foreChroma = chroma(fore, skin),
            foreShade = fore.luma / skinL,
        )
    }

    private fun chroma(a: Stats, b: Stats) = hypot(a.chromaR - b.chromaR, a.chromaG - b.chromaG)

    private fun px(i: Int) = f.x(i) * w
    private fun py(i: Int) = f.y(i) * h

    private fun patch(i: Int, rx: Float, ry: Float = rx) = around(px(i), py(i), rx, ry)
    private fun patch(a: Int, b: Int, r: Float) = around((px(a) + px(b)) / 2, (py(a) + py(b)) / 2, r, r)

    private fun around(cx: Float, cy: Float, rx: Float, ry: Float): Stats =
        stats((cx - rx * faceW).toInt(), (cy - ry * faceW).toInt(), (cx + rx * faceW).toInt(), (cy + ry * faceW).toInt(), 1)

    private fun box(ids: IntArray, shrink: Float, step: Int): Stats {
        val l = ids.minOf { px(it) }
        val r = ids.maxOf { px(it) }
        val t = ids.minOf { py(it) }
        val b = ids.maxOf { py(it) }
        val dx = (r - l) * shrink
        val dy = (b - t) * shrink
        return stats((l + dx).toInt(), (t + dy).toInt(), (r - dx).toInt(), (b - dy).toInt(), step)
    }

    private fun lumaAt(x: Int, y: Int): Float {
        val c = px[y * w + x]
        return 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
    }

    /** Inclusive box, clamped one pixel inside the frame so the gradient taps stay in bounds. */
    private fun stats(x0: Int, y0: Int, x1: Int, y1: Int, step: Int): Stats {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var l = 0.0
        var grad = 0.0
        var clip = 0
        var n = 0
        for (y in y0.coerceIn(1, h - 2)..y1.coerceIn(1, h - 2) step step) {
            for (x in x0.coerceIn(1, w - 2)..x1.coerceIn(1, w - 2) step step) {
                val c = px[y * w + x]
                r += c shr 16 and 0xFF
                g += c shr 8 and 0xFF
                b += c and 0xFF
                val v = lumaAt(x, y)
                l += v
                if (v >= 250f) clip++
                grad += abs(lumaAt(x + 1, y) - lumaAt(x - 1, y)) + abs(lumaAt(x, y + 1) - lumaAt(x, y - 1))
                n++
            }
        }
        if (n == 0) return Stats(0f, 0f, 0f, 0f, 0f, 0f)
        return Stats((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat(), (l / n).toFloat(), clip.toFloat() / n, (grad / n).toFloat())
    }

    /** Laplacian variance between the outer eye corners and the mouth. */
    private fun sharpness(): Float {
        val x0 = minOf(px(EYE_OUT_R), px(EYE_OUT_L)).toInt().coerceIn(1, w - 2)
        val x1 = maxOf(px(EYE_OUT_R), px(EYE_OUT_L)).toInt().coerceIn(1, w - 2)
        val y0 = py(EYE_R_TOP).toInt().coerceIn(1, h - 2)
        val y1 = py(LIP_UP).toInt().coerceIn(1, h - 2)
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in y0..y1) for (x in x0..x1) {
            val lap = 4 * lumaAt(x, y) - lumaAt(x - 1, y) - lumaAt(x + 1, y) - lumaAt(x, y - 1) - lumaAt(x, y + 1)
            sum += lap
            sq += lap * lap
            n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sq / n - mean * mean).toFloat()
    }

    /** MediaPipe face-mesh landmark ids. */
    companion object {
        const val CHEEK_L = FaceFrame.CHEEK_L
        const val CHEEK_R = FaceFrame.CHEEK_R
        const val FOREHEAD_TOP = FaceFrame.FOREHEAD
        const val CHIN = FaceFrame.CHIN
        const val FOREHEAD = 151
        const val BRIDGE = 168
        const val CHEEK_IN_R = 50
        const val CHEEK_IN_L = 280
        const val EYE_R_TOP = 159
        const val EYE_R_BOTTOM = 145
        const val EYE_L_TOP = 386
        const val EYE_L_BOTTOM = 374
        const val EYE_OUT_R = 33
        const val EYE_OUT_L = 263
        const val LIP_UP = 13
        const val LIP_LOW = 14
    }
}
