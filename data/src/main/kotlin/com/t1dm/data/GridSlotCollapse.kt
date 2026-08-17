package com.t1dm.data

import com.t1dm.core.model.isRealMeasurement
import com.t1dm.data.db.CgmReadingEntity

/**
 * Reduce a class-wide reading window to one reading per five-minute grid slot (§3.1).
 *
 * The BG panel draws the history of a sensor MODEL, so two sensors of that model worn at once — the
 * replacement warming up while the old one still reports — both hold a row at the same `tsMs`. Drawn
 * as they come the trace zigzags between two sensors that disagree by a few mg/dL; this picks one.
 *
 * The order of preference, and why:
 *
 *  1. **A real measurement beats a fabricated one.** `MEASURED` + `NORMAL` outranks anything
 *     `INTERPOLATED` or flagged `WARMUP`/`INVALID`, whichever sensor produced it. This is the case
 *     the sensor change actually produces: the replacement spends its first hour in warm-up while
 *     the sensor being retired is still measuring, and ranking selection first would draw the new
 *     sensor's suppressed warm-up value over a good reading the phone already holds. §3.6 keeps
 *     warm-up and interpolated values out of inference and out of alarm evaluation for the same
 *     reason; the graph should not quietly prefer them either.
 *  2. **Then the selected source.** Among readings of equal standing it is the authoritative one
 *     (§3.1) — the one the headline number, the alarm engine and the model already read — so the
 *     trace agrees with the number printed above it.
 *  3. **Then the newest reception** (`rxWallMs`) — the fresher sensor's view of the slot. Note this
 *     is NOT [supersedesGridSlot]'s rule, and deliberately so: that one settles which of ONE
 *     sensor's samples claims a slot, where nearest-to-the-instant is what the slot claims to be;
 *     this settles which of TWO SENSORS to believe, where the later reception is the better answer
 *     and distance-to-instant says nothing about it.
 *  4. **Then the lower `sourceId`.** A pure tie-break, present so the output is a function of the
 *     input alone: without it two readings received in the same millisecond would resolve by row
 *     order, and the trace could change under a reader for no reason.
 *
 * [rows] must be ordered by `tsMs` ascending — as `observeRangeForSources` returns them — so equal
 * slots are adjacent and this is one linear pass with no allocation per slot.
 */
internal fun collapseByGridSlot(
    rows: List<CgmReadingEntity>,
    selectedSourceId: String?,
): List<CgmReadingEntity> {
    if (rows.size < 2) return rows
    var i = 0
    var out: ArrayList<CgmReadingEntity>? = null
    while (i < rows.size) {
        var j = i + 1
        while (j < rows.size && rows[j].tsMs == rows[i].tsMs) j++
        if (j - i > 1) {
            // The first contested slot is also the first proof the copy is needed; until one turns up,
            // the query's own list is already the answer and is handed back untouched.
            if (out == null) out = ArrayList<CgmReadingEntity>(rows.size).apply { addAll(rows.subList(0, i)) }
            var best = rows[i]
            for (k in i + 1 until j) best = preferredOf(best, rows[k], selectedSourceId)
            out.add(best)
        } else {
            out?.add(rows[i])
        }
        i = j
    }
    return out ?: rows
}

private fun preferredOf(
    a: CgmReadingEntity,
    b: CgmReadingEntity,
    selectedSourceId: String?,
): CgmReadingEntity {
    val aReal = a.isRealMeasurement
    val bReal = b.isRealMeasurement
    if (aReal != bReal) return if (aReal) a else b
    val aSelected = a.sourceId == selectedSourceId
    val bSelected = b.sourceId == selectedSourceId
    if (aSelected != bSelected) return if (aSelected) a else b
    if (a.rxWallMs != b.rxWallMs) return if (a.rxWallMs > b.rxWallMs) a else b
    return if (a.sourceId <= b.sourceId) a else b
}

/** §3.6's own predicate, read here for display ranking only — it decides which of two readings is
 *  drawn, never whether either counts. */
private val CgmReadingEntity.isRealMeasurement: Boolean
    get() = isRealMeasurement(provenance, flag)
