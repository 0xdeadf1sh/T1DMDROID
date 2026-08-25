package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import kotlin.math.abs

/**
 * Does [incoming] take the five-minute slot from [stored]? — the STORAGE-side decision, within one
 * source. Ranks `NORMAL` over suppressed, then nearest `|rxWallMs − tsMs|`, then the earlier
 * instant. Distinct from [collapseByGridSlot], which ranks SEVERAL SOURCES for display.
 */
internal fun supersedesGridSlot(stored: CgmReadingEntity?, incoming: CgmReadingEntity): Boolean {
    if (stored == null) return true
    // A reconstruction fills a HOLE only; without this it meets the early-out below and takes the
    // slot from a real reading.
    if (incoming.provenance == ReadingProvenance.RECONSTRUCTED) {
        return stored.bgMgdl == null || stored.provenance == ReadingProvenance.RECONSTRUCTED
    }
    if (stored.provenance != ReadingProvenance.MEASURED ||
        incoming.provenance != ReadingProvenance.MEASURED
    ) {
        return true
    }
    val storedNormal = stored.flag == ReadingFlag.NORMAL
    val incomingNormal = incoming.flag == ReadingFlag.NORMAL
    if (storedNormal != incomingNormal) return incomingNormal

    val storedDistance = abs(stored.rxWallMs - stored.tsMs)
    val incomingDistance = abs(incoming.rxWallMs - incoming.tsMs)
    if (incomingDistance != storedDistance) return incomingDistance < storedDistance
    if (incoming.rxWallMs != stored.rxWallMs) return incoming.rxWallMs < stored.rxWallMs
    return true
}
