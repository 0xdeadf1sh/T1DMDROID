package com.t1dm.feature.twin

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Written by `tools/twin-mesh/build.py` (layout in its `main`); decimetres, y up. */
class TwinBody(
    val neutral: FloatArray,
    val indices: ShortArray,
    val morphNames: List<String>,
    val morphs: List<FloatArray>,
    val boneNames: List<String>,
    /** -1 for the root; a parent always precedes its children. */
    val boneParents: IntArray,
    /** Rest pivot xyz per bone, and its per-morph delta in [morphNames] order. */
    val bonePivots: FloatArray,
    val bonePivotMorphs: List<FloatArray>,
    /** Four bone indices and four weights (summing to 1) per vertex. */
    val skinBones: ByteArray,
    val skinWeights: FloatArray,
) {
    val vertexCount: Int get() = neutral.size / 3
    val boneCount: Int get() = boneNames.size

    /** CRC32 of [indices]: a stored placement is only valid on the topology it was made on. */
    val topologyHash: Int by lazy {
        val bytes = ByteBuffer.allocate(indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(indices)
        CRC32().apply { update(bytes.array()) }.value.toInt()
    }

    /** Bone pivots for [weights]; writes xyz per bone into [out]. */
    fun pivots(weights: FloatArray, out: FloatArray = FloatArray(bonePivots.size)): FloatArray {
        bonePivots.copyInto(out)
        for (m in bonePivotMorphs.indices) {
            val w = weights[m]
            if (w == 0f) continue
            val d = bonePivotMorphs[m]
            for (i in out.indices) out[i] += w * d[i]
        }
        return out
    }

    fun boneIndex(name: String): Int = boneNames.indexOf(name)

    /** [weights] in [morphNames] order; writes into [out], sized like [neutral]. */
    fun blend(weights: FloatArray, out: FloatArray = FloatArray(neutral.size)): FloatArray {
        neutral.copyInto(out)
        for (m in morphs.indices) {
            val w = weights[m]
            if (w == 0f) continue
            val d = morphs[m]
            for (i in out.indices) out[i] += w * d[i]
        }
        return out
    }

    fun morphIndex(name: String): Int = morphNames.indexOf(name)

    /** Standing height in cm: the y extent, scaled from decimetres. */
    fun heightCm(weights: FloatArray): Float {
        val p = blend(weights)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 1 until p.size step 3) {
            if (p[i] < lo) lo = p[i]
            if (p[i] > hi) hi = p[i]
        }
        return (hi - lo) * 10f
    }

    companion object {
        fun parse(bytes: ByteArray): TwinBody {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(magic.contentEquals("TWN3".toByteArray())) { "not a twin body" }
            val nv = b.int
            val nt = b.int
            val nm = b.int
            val nb = b.int
            val neutral = b.floats(nv * 3)
            val indices = b.shorts(nt * 3)
            val names = ArrayList<String>(nm)
            val morphs = ArrayList<FloatArray>(nm)
            repeat(nm) {
                names += b.name()
                morphs += b.floats(nv * 3)
            }
            val boneNames = ArrayList<String>(nb)
            val parents = IntArray(nb)
            val pivots = FloatArray(nb * 3)
            val pivotMorphs = List(nm) { FloatArray(nb * 3) }
            for (i in 0 until nb) {
                boneNames += b.name()
                parents[i] = b.get().toInt()
                for (k in 0 until 3) pivots[i * 3 + k] = b.float
                for (m in 0 until nm) for (k in 0 until 3) pivotMorphs[m][i * 3 + k] = b.float
            }
            val skinBones = ByteArray(nv * 4).also { b.get(it) }
            val skinWeights = b.floats(nv * 4)
            require(!b.hasRemaining()) { "trailing bytes in twin body" }
            return TwinBody(neutral, indices, names, morphs, boneNames, parents, pivots, pivotMorphs, skinBones, skinWeights)
        }

        private fun ByteBuffer.name(): String = String(ByteArray(get().toInt() and 0xFF).also { get(it) })

        private fun ByteBuffer.floats(n: Int): FloatArray =
            FloatArray(n).also { asFloatBuffer().get(it); position(position() + n * 4) }

        private fun ByteBuffer.shorts(n: Int): ShortArray =
            ShortArray(n).also { asShortBuffer().get(it); position(position() + n * 2) }
    }
}
