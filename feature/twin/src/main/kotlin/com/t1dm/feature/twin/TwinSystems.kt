package com.t1dm.feature.twin

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Vessels or nerves riding the body: each vertex blends body vertices, plus an offset. */
internal class TwinSystem(
    val name: String,
    val lines: Boolean,
    private val anchors: IntArray,
    private val weights: FloatArray,
    private val offset: FloatArray,
    /** Per vertex: distance from its hub (heart or brain, dm) and class, see [ARTERY]. */
    val attrs: FloatArray,
    val indices: ShortArray,
) {
    val vertexCount: Int get() = offset.size / 3

    /** [body] is a blended body, xyz per vertex; writes xyz per system vertex into [out]. */
    fun positions(body: FloatArray, out: FloatArray) {
        for (v in 0 until vertexCount) {
            var x = offset[v * 3]
            var y = offset[v * 3 + 1]
            var z = offset[v * 3 + 2]
            for (k in 0 until ANCHORS) {
                val a = anchors[v * ANCHORS + k] * 3
                val w = weights[v * ANCHORS + k]
                x += w * body[a]
                y += w * body[a + 1]
                z += w * body[a + 2]
            }
            out[v * 3] = x
            out[v * 3 + 1] = y
            out[v * 3 + 2] = z
        }
    }

    companion object {
        const val ARTERY = 0f
        const val VEIN = 1f
        const val NERVE = 2f
        private const val ANCHORS = 4

        /** Written by `tools/twin-mesh/anatomy.py` (layout in its `write`). */
        fun parse(bytes: ByteArray): List<TwinSystem> {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(magic.contentEquals("TWS1".toByteArray())) { "not twin systems" }
            return List(b.int) {
                val name = String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) })
                val lines = b.get().toInt() == 1
                val nv = b.int
                val ni = b.int
                val anchors = IntArray(nv * ANCHORS)
                val weights = FloatArray(nv * ANCHORS)
                val offset = FloatArray(nv * 3)
                val attrs = FloatArray(nv * 2)
                for (v in 0 until nv) {
                    for (k in 0 until ANCHORS) anchors[v * ANCHORS + k] = b.short.toInt() and 0xFFFF
                    for (k in 0 until ANCHORS) weights[v * ANCHORS + k] = b.float
                    for (k in 0 until 3) offset[v * 3 + k] = b.float
                    attrs[v * 2] = b.float
                    attrs[v * 2 + 1] = b.float
                }
                val indices = ShortArray(ni) { b.short }
                TwinSystem(name, lines, anchors, weights, offset, attrs, indices)
            }.also { require(!b.hasRemaining()) { "trailing bytes in twin systems" } }
        }
    }
}
