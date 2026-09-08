package com.t1dm.data

import com.t1dm.core.model.isRealMeasurement
import com.t1dm.data.db.CgmReadingEntity

/** One reading per 5-min slot (§3.1): ranks real, selected, rxWallMs; rows tsMs-ascending. */
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
            // Copy lazily: until a slot is contested the query's own list is the answer.
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

/** §3.6's predicate, read here for display ranking only. */
private val CgmReadingEntity.isRealMeasurement: Boolean
    get() = isRealMeasurement(provenance, flag)
