package com.t1dm.cgm

/**
 * A tiny fixed-capacity ring of recently-committed `minFromStart` values (SPEC.private.md Phase
 * 1). The AiDEX X re-advertises the same minute's reading several times, so the pipeline commits
 * each `minFromStart` exactly once. [contains] is a cheap short-circuit *before* the CRC/decode;
 * [record] is only invoked after a CRC-valid decode, so a corrupted frame can never poison a
 * minute slot and suppress the good copy that follows.
 *
 * Not thread-safe by design — a single [CgmSource] drives one ring from one dispatcher.
 */
class DedupRing(private val capacity: Int = 16) {
    private val ring = IntArray(capacity) { Int.MIN_VALUE }
    private var idx = 0

    /** True if [key] was recently committed. */
    fun contains(key: Int): Boolean = ring.contains(key)

    /** Commit [key], evicting the oldest slot. */
    fun record(key: Int) {
        ring[idx] = key
        idx = (idx + 1) % capacity
    }
}
