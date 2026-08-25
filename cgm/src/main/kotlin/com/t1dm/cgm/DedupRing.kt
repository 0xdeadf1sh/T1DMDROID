package com.t1dm.cgm

/**
 * A fixed-capacity ring of recently-committed `minFromStart` values. [contains] short-circuits before
 * the CRC/decode; [record] runs only after a CRC-valid decode, so a corrupt frame cannot poison a
 * minute slot. Not thread-safe: one [CgmSource] drives one ring from one dispatcher.
 */
class DedupRing(private val capacity: Int = 16) {
    private val ring = IntArray(capacity) { Int.MIN_VALUE }
    private var idx = 0

    fun contains(key: Int): Boolean = ring.contains(key)

    fun record(key: Int) {
        ring[idx] = key
        idx = (idx + 1) % capacity
    }
}
