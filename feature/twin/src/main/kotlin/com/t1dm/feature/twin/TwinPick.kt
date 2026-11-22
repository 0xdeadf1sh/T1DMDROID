package com.t1dm.feature.twin

import android.content.Context
import com.t1dm.core.model.SensorPlacement
import kotlin.math.abs
import kotlin.math.sqrt

internal class SkinHit(val tri: Int, val u: Float, val v: Float)

internal object TwinPick {
    private const val EPS = 1e-7f
    private const val BARY_SLACK = 1e-4f

    /** Nearest triangle the ray [o] + t·[d], t > 0, meets (Möller–Trumbore); null on a miss. */
    fun ray(pos: FloatArray, idx: ShortArray, o: FloatArray, d: FloatArray): SkinHit? {
        var best = Float.MAX_VALUE
        var hit: SkinHit? = null
        for (t in 0 until idx.size / 3) {
            val a = (idx[t * 3].toInt() and 0xFFFF) * 3
            val b = (idx[t * 3 + 1].toInt() and 0xFFFF) * 3
            val c = (idx[t * 3 + 2].toInt() and 0xFFFF) * 3
            val e1x = pos[b] - pos[a]
            val e1y = pos[b + 1] - pos[a + 1]
            val e1z = pos[b + 2] - pos[a + 2]
            val e2x = pos[c] - pos[a]
            val e2y = pos[c + 1] - pos[a + 1]
            val e2z = pos[c + 2] - pos[a + 2]
            val px = d[1] * e2z - d[2] * e2y
            val py = d[2] * e2x - d[0] * e2z
            val pz = d[0] * e2y - d[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < EPS) continue
            val inv = 1f / det
            val sx = o[0] - pos[a]
            val sy = o[1] - pos[a + 1]
            val sz = o[2] - pos[a + 2]
            val u = (sx * px + sy * py + sz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist > EPS && dist < best) {
                best = dist
                hit = SkinHit(t, u, v)
            }
        }
        return hit
    }

    /** Writes the skin point, then its outward unit normal, into [out] (6 floats). */
    fun anchor(pos: FloatArray, idx: ShortArray, tri: Int, u: Float, v: Float, out: FloatArray) {
        val a = (idx[tri * 3].toInt() and 0xFFFF) * 3
        val b = (idx[tri * 3 + 1].toInt() and 0xFFFF) * 3
        val c = (idx[tri * 3 + 2].toInt() and 0xFFFF) * 3
        val w = 1f - u - v
        for (k in 0 until 3) out[k] = w * pos[a + k] + u * pos[b + k] + v * pos[c + k]
        normal(pos, a, b, c, out, 3)
    }

    /** The mesh winds counter-clockwise outward (positive signed volume), so e1 × e2 points out. */
    private fun normal(pos: FloatArray, a: Int, b: Int, c: Int, out: FloatArray, at: Int) {
        val e1x = pos[b] - pos[a]
        val e1y = pos[b + 1] - pos[a + 1]
        val e1z = pos[b + 2] - pos[a + 2]
        val e2x = pos[c] - pos[a]
        val e2y = pos[c + 1] - pos[a + 1]
        val e2z = pos[c + 2] - pos[a + 2]
        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
        out[at] = nx / len
        out[at + 1] = ny / len
        out[at + 2] = nz / len
    }

    fun valid(p: SensorPlacement, body: TwinBody): Boolean =
        p.meshHash == body.topologyHash &&
            p.tri in 0 until body.indices.size / 3 &&
            p.u >= -BARY_SLACK && p.v >= -BARY_SLACK && p.u + p.v <= 1f + BARY_SLACK

    /** Region of [p] from its heaviest bone at rest; front and back split only the torso. */
    fun region(body: TwinBody, p: SensorPlacement): String? {
        if (!valid(p, body)) return null
        val idx = body.indices
        val corners = intArrayOf(
            idx[p.tri * 3].toInt() and 0xFFFF,
            idx[p.tri * 3 + 1].toInt() and 0xFFFF,
            idx[p.tri * 3 + 2].toInt() and 0xFFFF,
        )
        val bary = floatArrayOf(1f - p.u - p.v, p.u, p.v)
        val load = FloatArray(body.boneCount)
        for (k in 0 until 3) for (i in 0 until 4) {
            val bone = body.skinBones[corners[k] * 4 + i].toInt() and 0xFF
            load[bone] += bary[k] * body.skinWeights[corners[k] * 4 + i]
        }
        val bone = load.indices.maxByOrNull { load[it] } ?: return null
        val n = FloatArray(3)
        normal(body.neutral, corners[0] * 3, corners[1] * 3, corners[2] * 3, n, 0)
        val front = n[2] > 0f
        val name = body.boneNames[bone]
        val base = when (name.substringBefore('.')) {
            "root", "spine05" -> if (front) "Abdomen" else "Lower back"
            "spine03", "spine01" -> if (front) "Chest" else "Upper back"
            "neck01" -> "Neck"
            "head" -> "Head"
            "clavicle" -> "Shoulder"
            "upperarm01" -> "Upper arm"
            "lowerarm01" -> "Forearm"
            "wrist" -> "Hand"
            "upperleg01" -> "Thigh"
            "lowerleg01" -> "Lower leg"
            "foot" -> "Foot"
            else -> return null
        }
        return when {
            name.endsWith(".L") -> "$base L"
            name.endsWith(".R") -> "$base R"
            else -> base
        }
    }
}

/** Where [p] sits, e.g. `Upper arm L`; null when it was made on another mesh. */
fun bodySpot(context: Context, p: SensorPlacement): String? = TwinPick.region(loadBody(context), p)
