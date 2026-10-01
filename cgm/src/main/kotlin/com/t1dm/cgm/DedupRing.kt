package com.t1dm.cgm

/** [record] runs only post-CRC; corrupt frames can't poison a slot. One ring per [CgmSource]. */
class DedupRing(private val capacity: Int = 16) {
    private val ring = IntArray(capacity) { Int.MIN_VALUE }
    private var idx = 0

    fun contains(key: Int): Boolean = ring.contains(key)

    fun record(key: Int) {
        ring[idx] = key
        idx = (idx + 1) % capacity
    }
}
