package com.t1dm.feature.twin

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** One captured frame: upright ARGB [pixels] and landmarks x,y,z normalized to [width]. */
internal class FaceShot(val pixels: IntArray, val width: Int, val height: Int, val points: FloatArray)

/** Bakes face shots into a square ARGB texture over the canonical face's UVs (row 0 = v 1). */
internal class FaceBake(private val canonUv: FloatArray, private val tris: ShortArray, private val size: Int = SIZE) {
    private val nTri = tris.size / 3
    private val nVert = canonUv.size / 2

    /** Unpremultiplied ARGB, [size]²; alpha 1 inside the face, fading to 0 at its outline. */
    fun bake(shots: List<FaceShot>): IntArray {
        require(shots.isNotEmpty())
        val weights = shots.map { vertexWeights(it, facingSign(shots[0])) }
        val gains = gains(shots, weights)
        val r = FloatArray(size * size)
        val g = FloatArray(size * size)
        val b = FloatArray(size * size)
        val wsum = FloatArray(size * size)
        val rgb = FloatArray(3)
        raster(1) { texel, ia, ib, ic, la, lb, lc ->
            for ((s, shot) in shots.withIndex()) {
                val wv = weights[s]
                val w = la * wv[ia] + lb * wv[ib] + lc * wv[ic]
                if (w <= 0f) continue
                sample(shot, ia, ib, ic, la, lb, lc, rgb)
                val k = gains[s]
                r[texel] += w * rgb[0] * k[0]
                g[texel] += w * rgb[1] * k[1]
                b[texel] += w * rgb[2] * k[2]
                wsum[texel] += w
            }
        }
        val covered = FloatArray(size * size) { if (wsum[it] > 0f) 1f else 0f }
        val fade = maxOf(1, size * FADE_PX / SIZE)
        val alpha = boxBlur(boxBlur(covered, fade), fade)
        val out = IntArray(size * size)
        for (i in out.indices) {
            val a = if (covered[i] > 0f) ((alpha[i] - 0.5f) * 2f).coerceIn(0f, 1f) else 0f
            val w = wsum[i]
            out[i] = if (w > 0f) argb(a, r[i] / w, g[i] / w, b[i] / w) else 0
        }
        bleed(out, covered)
        return out
    }

    /** +1 or −1 so that triangles facing the camera in the first shot score positive. */
    private fun facingSign(shot: FaceShot): Float {
        var sum = 0f
        for (t in 0 until nTri) sum += normalZ(shot, t)
        return if (sum >= 0f) 1f else -1f
    }

    /** Unit normal's z for triangle [t] in pixel space, z scaled like x. */
    private fun normalZ(shot: FaceShot, t: Int): Float {
        val p = shot.points
        val w = shot.width.toFloat()
        val h = shot.height.toFloat()
        fun c(k: Int, axis: Int): Float {
            val v = (tris[t * 3 + k].toInt() and 0xFFFF) * 3 + axis
            return p[v] * if (axis == 1) h else w
        }
        val ux = c(1, 0) - c(0, 0)
        val uy = c(1, 1) - c(0, 1)
        val uz = c(1, 2) - c(0, 2)
        val vx = c(2, 0) - c(0, 0)
        val vy = c(2, 1) - c(0, 1)
        val vz = c(2, 2) - c(0, 2)
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        return if (len > 0f) nz / len else 0f
    }

    /** Per canonical vertex: mean squared facing of its triangles, so seams blend smoothly. */
    private fun vertexWeights(shot: FaceShot, sign: Float): FloatArray {
        val sum = FloatArray(nVert)
        val n = IntArray(nVert)
        for (t in 0 until nTri) {
            val f = (sign * normalZ(shot, t)).coerceAtLeast(0f)
            for (k in 0 until 3) {
                val v = tris[t * 3 + k].toInt() and 0xFFFF
                sum[v] += f * f
                n[v]++
            }
        }
        return FloatArray(nVert) { if (n[it] > 0) sum[it] / n[it] else 0f }
    }

    /** Per-shot RGB gain matching each shot to the first where both see the face well. */
    private fun gains(shots: List<FaceShot>, weights: List<FloatArray>): List<FloatArray> {
        val ref = Array(shots.size) { DoubleArray(3) }
        val own = Array(shots.size) { DoubleArray(3) }
        val a = FloatArray(3)
        val c = FloatArray(3)
        raster(GAIN_STRIDE) { _, ia, ib, ic, la, lb, lc ->
            val w0 = la * weights[0][ia] + lb * weights[0][ib] + lc * weights[0][ic]
            if (w0 < OVERLAP) return@raster
            sample(shots[0], ia, ib, ic, la, lb, lc, a)
            for (s in 1 until shots.size) {
                val ws = la * weights[s][ia] + lb * weights[s][ib] + lc * weights[s][ic]
                if (ws < OVERLAP) continue
                sample(shots[s], ia, ib, ic, la, lb, lc, c)
                for (k in 0 until 3) {
                    ref[s][k] += a[k]
                    own[s][k] += c[k]
                }
            }
        }
        return shots.indices.map { s ->
            FloatArray(3) { k ->
                if (s == 0 || own[s][k] <= 0.0) 1f else (ref[s][k] / own[s][k]).toFloat().coerceIn(0.6f, 1.6f)
            }
        }
    }

    /** Bilinear RGB (0–255) of [shot] at the image point under barycentric (la, lb, lc). */
    private fun sample(shot: FaceShot, ia: Int, ib: Int, ic: Int, la: Float, lb: Float, lc: Float, out: FloatArray) {
        val p = shot.points
        val x = (la * p[ia * 3] + lb * p[ib * 3] + lc * p[ic * 3]) * shot.width - 0.5f
        val y = (la * p[ia * 3 + 1] + lb * p[ib * 3 + 1] + lc * p[ic * 3 + 1]) * shot.height - 0.5f
        val x0 = floor(x).toInt().coerceIn(0, shot.width - 2)
        val y0 = floor(y).toInt().coerceIn(0, shot.height - 2)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val px = shot.pixels
        val i = y0 * shot.width + x0
        val c00 = px[i]
        val c10 = px[i + 1]
        val c01 = px[i + shot.width]
        val c11 = px[i + shot.width + 1]
        for (k in 0 until 3) {
            val sh = 16 - 8 * k
            fun ch(c: Int) = (c shr sh and 0xFF).toFloat()
            val top = ch(c00) + (ch(c10) - ch(c00)) * fx
            val bot = ch(c01) + (ch(c11) - ch(c01)) * fx
            out[k] = top + (bot - top) * fy
        }
    }

    private fun interface TexelFn {
        fun at(texel: Int, ia: Int, ib: Int, ic: Int, la: Float, lb: Float, lc: Float)
    }

    /** Every [stride]-th texel centre inside each canonical triangle, with its barycentrics. */
    private fun raster(stride: Int, fn: TexelFn) {
        for (t in 0 until nTri) {
            val ia = tris[t * 3].toInt() and 0xFFFF
            val ib = tris[t * 3 + 1].toInt() and 0xFFFF
            val ic = tris[t * 3 + 2].toInt() and 0xFFFF
            val ax = canonUv[ia * 2] * size
            val ay = (1f - canonUv[ia * 2 + 1]) * size
            val bx = canonUv[ib * 2] * size
            val by = (1f - canonUv[ib * 2 + 1]) * size
            val cx = canonUv[ic * 2] * size
            val cy = (1f - canonUv[ic * 2 + 1]) * size
            val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(den) < 1e-9f) continue
            val x0 = floor(minOf(ax, bx, cx)).toInt().coerceIn(0, size - 1)
            val x1 = floor(maxOf(ax, bx, cx)).toInt().coerceIn(0, size - 1)
            val y0 = floor(minOf(ay, by, cy)).toInt().coerceIn(0, size - 1)
            val y1 = floor(maxOf(ay, by, cy)).toInt().coerceIn(0, size - 1)
            for (y in y0..y1) {
                if (y % stride != 0) continue
                val py = y + 0.5f
                for (x in x0..x1) {
                    if (x % stride != 0) continue
                    val px = x + 0.5f
                    val la = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den
                    val lb = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den
                    val lc = 1f - la - lb
                    if (la < -EDGE || lb < -EDGE || lc < -EDGE) continue
                    fn.at(y * size + x, ia, ib, ic, la, lb, lc)
                }
            }
        }
    }

    /** Separable box blur of radius [r], clamped at the borders. */
    private fun boxBlur(src: FloatArray, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val n = 2 * r + 1
        for (y in 0 until size) for (x in 0 until size) {
            var s = 0f
            for (d in -r..r) s += src[y * size + (x + d).coerceIn(0, size - 1)]
            tmp[y * size + x] = s / n
        }
        for (y in 0 until size) for (x in 0 until size) {
            var s = 0f
            for (d in -r..r) s += tmp[(y + d).coerceIn(0, size - 1) * size + x]
            out[y * size + x] = s / n
        }
        return out
    }

    /** Spreads edge colours a few texels outward at alpha 0, so filtering never pulls in black. */
    private fun bleed(px: IntArray, covered: FloatArray) {
        val have = BooleanArray(px.size) { covered[it] > 0f }
        repeat(BLEED_PX) {
            val next = have.copyOf()
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                if (have[i]) continue
                val j = intArrayOf(i - 1, i + 1, i - size, i + size).firstOrNull { k ->
                    k in px.indices && have[k] && abs(k % size - x) <= 1
                } ?: continue
                px[i] = px[j] and 0x00FFFFFF
                next[i] = true
            }
            have.indices.forEach { have[it] = next[it] }
        }
    }

    private fun argb(a: Float, r: Float, g: Float, b: Float): Int =
        ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
            ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (b + 0.5f).toInt().coerceIn(0, 255)

    companion object {
        const val SIZE = 512
        private const val FADE_PX = 10
        private const val BLEED_PX = 4
        private const val GAIN_STRIDE = 4
        private const val OVERLAP = 0.3f
        private const val EDGE = 1e-4f
    }
}
