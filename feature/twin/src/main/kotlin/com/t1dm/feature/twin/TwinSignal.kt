package com.t1dm.feature.twin

import com.t1dm.core.model.SensorPlacement

/** One listed CGM for the twin; [placement] null until put on the body. */
data class TwinSensor(
    val id: String,
    val placement: SensorPlacement?,
    /** Median-smoothed link RSSI; null while no session is held. */
    val rssiDbm: Int?,
    val lastReadingMs: Long?,
    val focus: Boolean,
    /** Top row of the box floating beside the puck. */
    val label: String = "",
    /** Bottom row, already masked as the name setting wants; null drops the row. */
    val serial: String? = null,
)

object TwinSignal {
    const val FLOOR_DBM = -100
    const val CEIL_DBM = -50

    /** Linear in dB, which is already logarithmic like perceived brightness. */
    fun glow(dbm: Int): Float = ((dbm - FLOOR_DBM).toFloat() / (CEIL_DBM - FLOOR_DBM)).coerceIn(0f, 1f)

    /** Null without evidence: no link, or no reading within [staleAfterMs]. */
    fun glowAt(rssiDbm: Int?, lastReadingMs: Long?, nowMs: Long, staleAfterMs: Long): Float? {
        if (rssiDbm == null || lastReadingMs == null || nowMs - lastReadingMs >= staleAfterMs) return null
        return glow(rssiDbm)
    }
}

/** Median of each link's last [WINDOW] changes; a dropped link forgets its history. */
class RssiMedian {
    private val history = HashMap<String, ArrayDeque<Int>>()

    fun feed(id: String, dbm: Int?): Int? {
        if (dbm == null) {
            history.remove(id)
            return null
        }
        val h = history.getOrPut(id) { ArrayDeque() }
        if (h.lastOrNull() != dbm) {
            h.addLast(dbm)
            if (h.size > WINDOW) h.removeFirst()
        }
        // Lower middle: of two samples, the weaker one.
        return h.sorted()[(h.size - 1) / 2]
    }

    fun retain(ids: Set<String>) {
        history.keys.retainAll(ids)
    }

    private companion object {
        const val WINDOW = 3
    }
}
