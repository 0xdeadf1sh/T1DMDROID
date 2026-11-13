package com.t1dm.feature.twin

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Written by `tools/twin-mesh/build.py` (layout in its `main`); decimetres, y up. */
class TwinBody(
    val neutral: FloatArray,
    val indices: ShortArray,
    val morphNames: List<String>,
    val morphs: List<FloatArray>,
) {
    val vertexCount: Int get() = neutral.size / 3

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
            require(magic.contentEquals("TWN2".toByteArray())) { "not a twin body" }
            val nv = b.int
            val nt = b.int
            val nm = b.int
            val neutral = b.floats(nv * 3)
            val indices = b.shorts(nt * 3)
            val names = ArrayList<String>(nm)
            val morphs = ArrayList<FloatArray>(nm)
            repeat(nm) {
                names += String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) })
                morphs += b.floats(nv * 3)
            }
            b.position(b.position() + nv * 2 * 4)
            val ncv = b.int
            val nct = b.int
            b.position(b.position() + ncv * 2 * 4 + nct * 3 * 2)
            require(!b.hasRemaining()) { "trailing bytes in twin body" }
            return TwinBody(neutral, indices, names, morphs)
        }

        private fun ByteBuffer.floats(n: Int): FloatArray =
            FloatArray(n).also { asFloatBuffer().get(it); position(position() + n * 4) }

        private fun ByteBuffer.shorts(n: Int): ShortArray =
            ShortArray(n).also { asShortBuffer().get(it); position(position() + n * 2) }
    }
}
