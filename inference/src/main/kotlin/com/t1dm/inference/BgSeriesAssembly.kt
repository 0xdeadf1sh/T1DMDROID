package com.t1dm.inference

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import java.util.TreeMap

private const val GRID_MS = 300_000L

/** Rows read past the newest `maxSteps`; the carried value at the window's start comes from them. */
const val BG_SERIES_ROW_MARGIN = 12

/**
 * [newestFirst]: the newest `maxSteps + BG_SERIES_ROW_MARGIN` rows of one source, unfiltered.
 * WARMUP/INVALID excluded (§3.1), gaps carried, null below [minSteps]. [fills] runs only
 * [withReconstructed], over `[start, anchor]`.
 */
suspend fun assembleBgSeries(
    newestFirst: List<CgmReading>,
    sourceId: String,
    maxSteps: Int,
    minSteps: Int,
    withReconstructed: Boolean,
    fills: suspend (fromMs: Long, toMs: Long) -> Map<Long, Double>,
): BgSeries? {
    // Provenance test stops dosing from swallowing a reconstruction, closing the loop.
    val readings = newestFirst.filter {
        it.bgMgdl != null && it.flag == ReadingFlag.NORMAL &&
            (withReconstructed || it.provenance != ReadingProvenance.RECONSTRUCTED)
    }
    if (readings.size < minSteps) return null

    val byTs = TreeMap<Long, Double>()
    for (r in readings) byTs[r.tsMs] = r.bgMgdl!!.toDouble()
    val anchor = byTs.lastKey()
    val earliest = byTs.firstKey()

    var nSteps = ((anchor - earliest) / GRID_MS + 1L).toInt().coerceAtMost(maxSteps)
    nSteps -= nSteps % 6 // whole patches (PATCH_SIZE = 6)
    if (nSteps < minSteps) return null

    val start = anchor - (nSteps - 1L) * GRID_MS
    // A fill stands in for a slot the sensor never covered; no fill reaches a fit series.
    val filled = if (withReconstructed) fills(start, anchor) else emptyMap()
    val out = DoubleArray(nSteps)
    var last = byTs.ceilingEntry(start)?.value ?: byTs.firstEntry()?.value ?: out[0]
    for (i in 0 until nSteps) {
        val ts = start + i * GRID_MS
        val v = byTs[ts] ?: filled[ts]
        if (v != null) last = v
        out[i] = last
    }
    // Newest-first input; the last entry would age the anchor (§3.6-D).
    val lastMeasured = readings.firstOrNull { it.provenance == ReadingProvenance.MEASURED }?.tsMs ?: anchor
    return BgSeries(out, anchorTsMs = lastMeasured, gridStartMs = start, sourceId = sourceId)
}
