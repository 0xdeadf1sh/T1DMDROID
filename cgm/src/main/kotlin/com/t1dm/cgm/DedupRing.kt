package com.t1dm.cgm

/** Recent `minFromStart` ring; [record] is post-CRC only; not thread-safe, one ring per source. */
class DedupRing(private val capacity: Int = 16) {
    private val ring = IntArray(capacity) { Int.MIN_VALUE }
    private var idx = 0

    fun contains(key: Int): Boolean = ring.contains(key)

    fun record(key: Int) {
        ring[idx] = key
        idx = (idx + 1) % capacity
    }
}
