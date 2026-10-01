package com.t1dm.feature.cgm

import com.t1dm.core.model.CgmLogEntry
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import java.util.EnumMap

/** One exchange: an opener and the lines of its topic that followed within [UNIT_GAP_MS]. */
class CgmLogUnit internal constructor(val topic: CgmLogTopic) {
    private var idx = IntArray(4)

    /** Lines in the unit; each is an index into the folded list. */
    var size = 0
        private set

    internal fun add(i: Int) {
        if (size == idx.size) idx = idx.copyOf(size * 2)
        idx[size++] = i
    }

    operator fun get(k: Int): Int = idx[k]

    val first: Int get() = idx[0]
    val last: Int get() = idx[size - 1]

    /** Every line routine: a topic, and nothing at W or above. */
    internal fun routine(entries: List<CgmLogEntry>): Boolean {
        if (topic == CgmLogTopic.NONE) return false
        for (k in 0 until size) if (entries[idx[k]].level >= CgmLogLevel.W) return false
        return true
    }
}

sealed interface CgmLogRow {
    /** An index into the folded list. */
    class Line(val index: Int) : CgmLogRow

    /** Consecutive routine exchanges of one topic; value range null when none carried one. */
    class Fold(
        val topic: CgmLogTopic,
        val units: List<CgmLogUnit>,
        val firstMs: Long,
        val lastMs: Long,
        val lines: Int,
        val valueMin: Int?,
        val valueMax: Int?,
    ) : CgmLogRow {
        /** Stable while the list only grows: the index of its first line. */
        val key: Int get() = units[0].first
    }
}

/** A line time-adjacent to one of its topic belongs to its exchange unless it opens its own. */
const val UNIT_GAP_MS = 2_000L

/** Non-routine lines split the log; between splits routine exchanges fold per topic. */
fun foldCgmLog(entries: List<CgmLogEntry>, unitGapMs: Long = UNIT_GAP_MS): List<CgmLogRow> {
    val rows = ArrayList<CgmLogRow>()
    val segment = LinkedHashMap<CgmLogTopic, MutableList<CgmLogUnit>>()
    for (u in units(entries, unitGapMs)) {
        if (u.routine(entries)) {
            segment.getOrPut(u.topic) { ArrayList() } += u
        } else {
            flush(segment, rows, entries, holdNewestGlucose = false)
            for (k in 0 until u.size) rows += CgmLogRow.Line(u[k])
        }
    }
    flush(segment, rows, entries, holdNewestGlucose = true)
    return rows
}

private fun units(entries: List<CgmLogEntry>, gapMs: Long): List<CgmLogUnit> {
    val out = ArrayList<CgmLogUnit>()
    val open = EnumMap<CgmLogTopic, CgmLogUnit>(CgmLogTopic::class.java)
    val lastMs = LongArray(CgmLogTopic.entries.size)
    for (i in entries.indices) {
        val e = entries[i]
        val t = e.topic
        if (t == CgmLogTopic.NONE) {
            out += CgmLogUnit(t).also { it.add(i) }
            continue
        }
        val cur = open[t]
        if (cur == null || e.opens || e.wallMs - lastMs[t.ordinal] > gapMs) {
            val fresh = CgmLogUnit(t).also { it.add(i) }
            open[t] = fresh
            out += fresh
        } else {
            cur.add(i)
        }
        lastMs[t.ordinal] = e.wallMs
    }
    return out
}

/** The newest glucose exchange of the last segment stays open, after that segment's folds. */
private fun flush(
    segment: LinkedHashMap<CgmLogTopic, MutableList<CgmLogUnit>>,
    rows: MutableList<CgmLogRow>,
    entries: List<CgmLogEntry>,
    holdNewestGlucose: Boolean,
) {
    val held = if (holdNewestGlucose) {
        segment[CgmLogTopic.GLUCOSE]?.let { list ->
            // removeAt, not removeLast: the JDK 21 overload is absent below Android 15.
            list.removeAt(list.lastIndex).also { if (list.isEmpty()) segment.remove(CgmLogTopic.GLUCOSE) }
        }
    } else {
        null
    }
    for ((topic, list) in segment) {
        if (list.size == 1) {
            val u = list[0]
            for (k in 0 until u.size) rows += CgmLogRow.Line(u[k])
        } else {
            rows += fold(topic, list, entries)
        }
    }
    held?.let { u -> for (k in 0 until u.size) rows += CgmLogRow.Line(u[k]) }
    segment.clear()
}

private fun fold(topic: CgmLogTopic, units: List<CgmLogUnit>, entries: List<CgmLogEntry>): CgmLogRow.Fold {
    var lines = 0
    var lo: Int? = null
    var hi: Int? = null
    for (u in units) {
        lines += u.size
        for (k in 0 until u.size) {
            val v = entries[u[k]].value ?: continue
            lo = if (lo == null) v else minOf(lo, v)
            hi = if (hi == null) v else maxOf(hi, v)
        }
    }
    return CgmLogRow.Fold(
        topic = topic,
        units = units,
        firstMs = entries[units.first().first].wallMs,
        lastMs = entries[units.last().last].wallMs,
        lines = lines,
        valueMin = lo,
        valueMax = hi,
    )
}

const val CGM_LOG_PAGE = 500

/** Pages count back from the newest, page 0 holding the last [size] rows. */
fun cgmLogPageCount(total: Int, size: Int = CGM_LOG_PAGE): Int = maxOf(1, (total + size - 1) / size)

/** Index range of [page], oldest first within it. */
fun cgmLogPage(total: Int, page: Int, size: Int = CGM_LOG_PAGE): IntRange {
    val end = total - page * size
    return maxOf(0, end - size) until maxOf(0, end)
}
