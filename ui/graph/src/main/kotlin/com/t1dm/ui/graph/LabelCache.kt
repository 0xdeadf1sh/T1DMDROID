package com.t1dm.ui.graph

/**
 * Memoises the axis label STRINGS across frames.
 *
 * The `TextMeasurer`'s own LRU already caches the layout, but it is asked for the layout of a string
 * that has to exist first — so every frame still ran ~7 `java.time` formats for the local-time axis,
 * ~7 `String.format`s for the model-clock axis (each allocating a fresh `Formatter`) and ~6 more for
 * the value axis, producing text byte-identical to the frame before. On a still viewport that is the
 * whole of the work: the ticks do not move, so the answers cannot change.
 *
 * The `compute` lambdas are NOT inlined — this is public API of the module, and an inline function
 * may not read private state. That costs one short-lived lambda per lookup, which is the trade the
 * class exists to make: a capture escape analysis may remove entirely, against a date format and a
 * `Formatter` allocation it certainly cannot.
 *
 * Keys are primitives, so a lookup allocates nothing else. The maps are cleared rather than evicted
 * when they outgrow [MAX] — a viewport that has panned far enough to fill one is not coming back to
 * those ticks, and clearing is O(1) against an LRU's bookkeeping on every hit.
 *
 * Not thread-safe, and does not need to be: created in composition, read only from a draw lambda,
 * both of which are the UI thread.
 */
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

    /** [tag] is everything the formatter depends on besides [v] — here, the unit space. */
    fun value(v: Float, tag: Int, compute: () -> String): String {
        if (tag != valuesTag) { values.clear(); valuesTag = tag }
        if (values.size > MAX) values.clear()
        return values.getOrPut(v.toRawBits(), compute)
    }

    /**
     * [ms] is the tick; [tzOffsetMin] and [stepMs] are the other two inputs `formatTime` reads.
     *
     * The step MUST be part of the key: `formatTime` renders `MM-dd` at or above a 12 h tick step and
     * `HH:mm` below it, so a tick visited at one zoom and revisited at another needs a different
     * answer for the same instant. Keying on the tick alone let the first zoom that touched a tick fix
     * its format for the life of the composition — a date string sitting between two clock times, or
     * the reverse.
     */
    fun time(ms: Long, tzOffsetMin: Int, stepMs: Long, compute: () -> String): String {
        if (tzOffsetMin != timesTag || stepMs != timesStep) {
            times.clear(); timesTag = tzOffsetMin; timesStep = stepMs
        }
        if (times.size > MAX) times.clear()
        return times.getOrPut(ms, compute)
    }

    /**
     * The date row beneath the time axis, keyed on an instant inside the day it names.
     *
     * It gets its own map rather than sharing [times]: at or above a 12 h tick step the ticks are
     * themselves dates, so one instant is a key in both rows and would have to answer `01-07` there
     * and `January 7th, Wednesday` here.
     */
    fun date(ms: Long, tzOffsetMin: Int, compute: () -> String): String {
        if (tzOffsetMin != datesTag) { dates.clear(); datesTag = tzOffsetMin }
        if (dates.size > MAX) dates.clear()
        return dates.getOrPut(ms, compute)
    }

    /**
     * [tag] is the clock OBJECT, compared structurally — it is a data class, so this is exact. A
     * numeric digest of its fields would be smaller but could collide, and the cost of a collision
     * here is a label showing the wrong predicted time, which is not a trade worth making.
     */
    fun clock(ms: Long, tag: Any?, compute: () -> String): String {
        if (tag != clocksTag) { clocks.clear(); clocksTag = tag }
        if (clocks.size > MAX) clocks.clear()
        return clocks.getOrPut(ms, compute)
    }

    companion object {
        /** Generous next to one axis's worth of ticks, small next to the strings it saves. */
        const val MAX = 256
    }
}
