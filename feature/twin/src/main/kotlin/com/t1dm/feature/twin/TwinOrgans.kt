package com.t1dm.feature.twin

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.sqrt
import kotlin.random.Random

/** An organ in decimetres about its centre; the centre rides on body vertices. */
internal class TwinOrgan(
    val name: String,
    val pos: FloatArray,
    /** Per vertex: its chamber's centre (xyz, about the organ centre) and class, see [STILL]. */
    val motion: FloatArray,
    val tris: ShortArray,
    private val anchors: IntArray,
    private val weights: FloatArray,
    private val offset: FloatArray,
) {
    val vertexCount: Int get() = pos.size / 3

    /** [body] is a blended body, xyz per vertex; writes the centre into [out]. */
    fun centre(body: FloatArray, out: FloatArray) {
        offset.copyInto(out)
        for (k in anchors.indices) {
            val v = anchors[k] * 3
            val w = weights[k]
            out[0] += w * body[v]
            out[1] += w * body[v + 1]
            out[2] += w * body[v + 2]
        }
    }

    /** Each undirected mesh edge once, as vertex pairs. */
    fun edges(): IntArray {
        val seen = HashSet<Long>()
        val out = ArrayList<Int>()
        for (t in 0 until tris.size / 3) for (k in 0 until 3) {
            val a = tris[t * 3 + k].toInt() and 0xFFFF
            val b = tris[t * 3 + (k + 1) % 3].toInt() and 0xFFFF
            if (seen.add(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong())) {
                out += a
                out += b
            }
        }
        return out.toIntArray()
    }

    /** Per vertex, distance along [edges] from each of [SEEDS] seeds, packed as vec4s. */
    fun seedDistances(edges: IntArray, rnd: Random = Random(7)): FloatArray {
        val n = vertexCount
        val adj = Array(n) { ArrayList<Int>() }
        for (e in 0 until edges.size / 2) {
            adj[edges[e * 2]] += edges[e * 2 + 1]
            adj[edges[e * 2 + 1]] += edges[e * 2]
        }
        val parts = components(adj)
        val out = FloatArray(n * SEEDS)
        for (s in 0 until SEEDS) {
            val dist = FloatArray(n) { Float.MAX_VALUE }
            val queue = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
            // One seed in every disconnected part per channel, so every part fires.
            for (part in parts) {
                val seed = part[rnd.nextInt(part.size)]
                dist[seed] = 0f
                queue += 0f to seed
            }
            while (queue.isNotEmpty()) {
                val (d, v) = queue.poll()!!
                if (d > dist[v]) continue
                for (u in adj[v]) {
                    val nd = d + length(v, u)
                    if (nd < dist[u]) {
                        dist[u] = nd
                        queue += nd to u
                    }
                }
            }
            for (v in 0 until n) out[v * SEEDS + s] = if (dist[v] == Float.MAX_VALUE) UNREACHED else dist[v]
        }
        return out
    }

    private fun components(adj: Array<ArrayList<Int>>): List<IntArray> {
        val seen = BooleanArray(adj.size)
        val parts = ArrayList<IntArray>()
        for (start in adj.indices) {
            if (seen[start]) continue
            val part = ArrayList<Int>()
            val stack = ArrayDeque(listOf(start))
            seen[start] = true
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                part += v
                for (u in adj[v]) if (!seen[u]) {
                    seen[u] = true
                    stack += u
                }
            }
            parts += part.toIntArray()
        }
        return parts
    }

    private fun length(a: Int, b: Int): Float {
        val dx = pos[a * 3] - pos[b * 3]
        val dy = pos[a * 3 + 1] - pos[b * 3 + 1]
        val dz = pos[a * 3 + 2] - pos[b * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        const val SEEDS = 4

        /** Motion classes: atrium 0, ventricle 1, and this for parts that do not contract. */
        const val STILL = 2f
        private const val UNREACHED = 1e3f

        /** Written by `tools/twin-mesh/organs.py` (layout in its `write`). */
        fun parse(bytes: ByteArray): Map<String, TwinOrgan> {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(magic.contentEquals("TWO2".toByteArray())) { "not twin organs" }
            val out = LinkedHashMap<String, TwinOrgan>()
            repeat(b.int) {
                val name = String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) })
                val nv = b.int
                val nt = b.int
                val pos = FloatArray(nv * 3) { b.float }
                val motion = FloatArray(nv * 4) { b.float }
                val tris = ShortArray(nt * 3) { b.short }
                val k = b.int
                val anchors = IntArray(k) { b.short.toInt() and 0xFFFF }
                val weights = FloatArray(k) { b.float }
                val offset = FloatArray(3) { b.float }
                out[name] = TwinOrgan(name, pos, motion, tris, anchors, weights, offset)
            }
            require(!b.hasRemaining()) { "trailing bytes in twin organs" }
            return out
        }
    }
}
