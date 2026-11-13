package com.t1dm.feature.twin

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Written by `tools/twin-mesh/build.py` (layout in its `main`); decimetres, y up. */
class TwinBody(
    val neutral: FloatArray,
    val indices: ShortArray,
    val morphNames: List<String>,
    val morphs: List<FloatArray>,
    /** u,v per vertex in MediaPipe's canonical face UV space (v up); [NO_UV] off the face. */
    val faceUv: FloatArray,
    /** The canonical face's 468 UVs and its triangles, which the face texture is baked over. */
    val canonUv: FloatArray,
    val canonTris: ShortArray,
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
        const val NO_UV = -1f

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
            val faceUv = b.floats(nv * 2)
            val ncv = b.int
            val nct = b.int
            val canonUv = b.floats(ncv * 2)
            val canonTris = b.shorts(nct * 3)
            require(!b.hasRemaining()) { "trailing bytes in twin body" }
            return TwinBody(neutral, indices, names, morphs, faceUv, canonUv, canonTris)
        }

        private fun ByteBuffer.floats(n: Int): FloatArray =
            FloatArray(n).also { asFloatBuffer().get(it); position(position() + n * 4) }

        private fun ByteBuffer.shorts(n: Int): ShortArray =
            ShortArray(n).also { asShortBuffer().get(it); position(position() + n * 2) }
    }
}
