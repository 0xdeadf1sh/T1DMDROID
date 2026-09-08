package com.t1dm.ui.graph

/** Memoises axis labels; not thread-safe — composed then read from draw, both on UI thread. */
class GraphLabelCache {
    private val values = HashMap<Int, String>()
    private val times = HashMap<Long, String>()
    private val dates = HashMap<Long, String>()
    private val clocks = HashMap<Long, String>()

    private var valuesTag = Int.MIN_VALUE
    private var timesTag = Int.MIN_VALUE
    private var timesStep = Long.MIN_VALUE
    private var datesTag = Int.MIN_VALUE
    private var clocksTag: Any? = Unit

    /** [tag]: every input the formatter reads besides [v] — here, the unit space. */
    fun value(v: Float, tag: Int, compute: () -> String): String {
        if (tag != valuesTag) { values.clear(); valuesTag = tag }
        if (values.size > MAX) values.clear()
        return values.getOrPut(v.toRawBits(), compute)
    }

    /** [stepMs] in the key: formatTime renders MM-dd ≥12h, HH:mm below — same instant differs. */
    fun time(ms: Long, tzOffsetMin: Int, stepMs: Long, compute: () -> String): String {
        if (tzOffsetMin != timesTag || stepMs != timesStep) {
            times.clear(); timesTag = tzOffsetMin; timesStep = stepMs
        }
        if (times.size > MAX) times.clear()
        return times.getOrPut(ms, compute)
    }

    /** Keyed inside the day; own map, not [times] — one instant needs both rows above 12h step. */
    fun date(ms: Long, tzOffsetMin: Int, compute: () -> String): String {
        if (tzOffsetMin != datesTag) { dates.clear(); datesTag = tzOffsetMin }
        if (dates.size > MAX) dates.clear()
        return dates.getOrPut(ms, compute)
    }

    /** [tag] is the clock object, structural-compared; a digest could collide and mislabel. */
    fun clock(ms: Long, tag: Any?, compute: () -> String): String {
        if (tag != clocksTag) { clocks.clear(); clocksTag = tag }
        if (clocks.size > MAX) clocks.clear()
        return clocks.getOrPut(ms, compute)
    }

    companion object {
        /** Generous against one axis of ticks. */
        const val MAX = 256
    }
}
