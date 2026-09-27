package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.SampleEntity
import kotlin.math.abs

/** Any source's; legacy NULL provenance counts as measured. */
internal fun sampleHoldsMeasurement(sample: SampleEntity?): Boolean =
    sample != null && sample.bgMgdl != null &&
        sample.bgProvenance != ReadingProvenance.INTERPOLATED &&
        sample.bgProvenance != ReadingProvenance.RECONSTRUCTED

/** [supersedesGridSlot]'s reconstruction rule on `sample`: a hole or another reconstruction. */
internal fun reconstructionTakesSample(sample: SampleEntity?): Boolean =
    sample == null || sample.bgMgdl == null || sample.bgProvenance == ReadingProvenance.RECONSTRUCTED

/** Does [incoming] take [stored]'s slot? Storage-side: NORMAL>suppressed, nearest, earlier wins. */
internal fun supersedesGridSlot(stored: CgmReadingEntity?, incoming: CgmReadingEntity): Boolean {
    if (stored == null) return true
    // A reconstruction fills a HOLE only; without this it would take the slot from a real reading.
    if (incoming.provenance == ReadingProvenance.RECONSTRUCTED) {
        return stored.bgMgdl == null || stored.provenance == ReadingProvenance.RECONSTRUCTED
    }
    // A gap-fill line never displaces a usable measurement; a suppressed one it still may.
    if (incoming.provenance == ReadingProvenance.INTERPOLATED && stored.provenance == ReadingProvenance.MEASURED) {
        return stored.flag != ReadingFlag.NORMAL || stored.bgMgdl == null
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
