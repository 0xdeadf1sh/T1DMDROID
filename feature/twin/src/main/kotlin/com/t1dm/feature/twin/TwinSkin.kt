package com.t1dm.feature.twin

import kotlin.math.cos
import kotlin.math.sin

/** Linear-blend skinning on the CPU; the rest pose is the morphed body itself. */
internal class TwinSkin(private val body: TwinBody) {
    private val nb = body.boneCount
    private val world = FloatArray(nb * 9)
    private val posed = FloatArray(nb * 3)
    private val local = FloatArray(9)

    /** Per bone: a row-major 3×3 rotation then a translation, mapping rest to posed. */
    val matrices = FloatArray(nb * 12)

    /** [rot]: pitch, yaw, roll (rad) per bone, in the parent's frame; [shift]: root move (dm). */
    fun pose(pivots: FloatArray, rot: FloatArray, shift: FloatArray) {
        for (b in 0 until nb) {
            euler(rot[b * 3], rot[b * 3 + 1], rot[b * 3 + 2], local)
            val p = body.boneParents[b]
            if (p < 0) {
                local.copyInto(world, b * 9)
                for (k in 0 until 3) posed[b * 3 + k] = pivots[b * 3 + k] + shift[k]
            } else {
                mul(world, p * 9, local, world, b * 9)
                for (k in 0 until 3) {
                    var v = posed[p * 3 + k]
                    for (j in 0 until 3) v += world[p * 9 + k * 3 + j] * (pivots[b * 3 + j] - pivots[p * 3 + j])
                    posed[b * 3 + k] = v
                }
            }
            for (k in 0 until 3) {
                var t = posed[b * 3 + k]
                for (j in 0 until 3) {
                    val r = world[b * 9 + k * 3 + j]
                    matrices[b * 12 + k * 3 + j] = r
                    t -= r * pivots[b * 3 + j]
                }
                matrices[b * 12 + 9 + k] = t
            }
        }
    }

    /** [rest]: xyz per vertex; writes the posed body into [out]. */
    fun apply(rest: FloatArray, out: FloatArray) {
        val bones = body.skinBones
        val weights = body.skinWeights
        for (v in 0 until body.vertexCount) {
            val x = rest[v * 3]
            val y = rest[v * 3 + 1]
            val z = rest[v * 3 + 2]
            var ox = 0f
            var oy = 0f
            var oz = 0f
            for (i in 0 until 4) {
                val w = weights[v * 4 + i]
                if (w == 0f) continue
                val m = (bones[v * 4 + i].toInt() and 0xFF) * 12
                ox += w * (matrices[m] * x + matrices[m + 1] * y + matrices[m + 2] * z + matrices[m + 9])
                oy += w * (matrices[m + 3] * x + matrices[m + 4] * y + matrices[m + 5] * z + matrices[m + 10])
                oz += w * (matrices[m + 6] * x + matrices[m + 7] * y + matrices[m + 8] * z + matrices[m + 11])
            }
            out[v * 3] = ox
            out[v * 3 + 1] = oy
            out[v * 3 + 2] = oz
        }
    }

    /** Where [bone]'s matrix sends [p] (xyz at [at]); writes into [out] at [oo]. */
    fun transform(bone: Int, p: FloatArray, at: Int, out: FloatArray, oo: Int) {
        val m = bone * 12
        val x = p[at]
        val y = p[at + 1]
        val z = p[at + 2]
        out[oo] = matrices[m] * x + matrices[m + 1] * y + matrices[m + 2] * z + matrices[m + 9]
        out[oo + 1] = matrices[m + 3] * x + matrices[m + 4] * y + matrices[m + 5] * z + matrices[m + 10]
        out[oo + 2] = matrices[m + 6] * x + matrices[m + 7] * y + matrices[m + 8] * z + matrices[m + 11]
    }

    /** [bone]'s rotation as a column-major mat3, for glUniformMatrix3fv. */
    fun rotationColumnMajor(bone: Int, out: FloatArray) {
        for (r in 0 until 3) for (c in 0 until 3) out[c * 3 + r] = world[bone * 9 + r * 3 + c]
    }

    private companion object {
        /** Ry · Rx · Rz: yaw outermost, so a turned head still nods about its own axis. */
        fun euler(pitch: Float, yaw: Float, roll: Float, out: FloatArray) {
            val cx = cos(pitch)
            val sx = sin(pitch)
            val cy = cos(yaw)
            val sy = sin(yaw)
            val cz = cos(roll)
            val sz = sin(roll)
            out[0] = cy * cz + sy * sx * sz
            out[1] = -cy * sz + sy * sx * cz
            out[2] = sy * cx
            out[3] = cx * sz
            out[4] = cx * cz
            out[5] = -sx
            out[6] = -sy * cz + cy * sx * sz
            out[7] = sy * sz + cy * sx * cz
            out[8] = cy * cx
        }

        fun mul(a: FloatArray, ao: Int, b: FloatArray, out: FloatArray, oo: Int) {
            for (r in 0 until 3) for (c in 0 until 3) {
                out[oo + r * 3 + c] = a[ao + r * 3] * b[c] + a[ao + r * 3 + 1] * b[3 + c] + a[ao + r * 3 + 2] * b[6 + c]
            }
        }
    }
}
