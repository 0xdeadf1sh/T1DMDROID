package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/**
 * Snaps each accepted advert to the 5-minute grid in *phone-receive* time and reconstructs a
 * gap-free series (PLAN.private.md §3.1, cgm-ingestion memory). Passive mode has no trustworthy
 * sensor epoch, so the wall-clock receive time — not `minFromStart` — defines `tsMs`.
 *
 *  - A `NORMAL` advert produces a `MEASURED` reading at `round(rxWallMs / GRID_MS) * GRID_MS`,
 *    upserted in place on `(sourceId, tsMs)`.
 *  - A dropout between two consecutive `NORMAL` measurements is back-filled with `INTERPOLATED`
 *    readings, one per empty grid slot, linearly on BG. INTERPOLATED rows carry no trend /
 *    `minFromStart` / quality and (per §3.6-A) can never suppress or clear an alarm.
 *  - `WARMUP` readings are stamped `MEASURED`/`WARMUP` with their value intact but do **not**
 *    anchor interpolation — a suppressed warm-up value must not seed a fabricated line.
 *
 * Stateful per source; a single [CgmSource] owns one stamper on one dispatcher.
 */
class GridStamper(private val gridMs: Long = CgmConstants.GRID_MS) {

    private var lastMeasuredTs: Long? = null
    private var lastMeasuredBg: Int? = null

    /** Snap [rxWallMs] to the nearest grid instant. */
    fun snap(rxWallMs: Long): Long = Math.round(rxWallMs.toDouble() / gridMs) * gridMs

    /**
     * Produce the readings for one accepted advert: any INTERPOLATED gap-fills first (chronological
     * order), then the reading for this advert itself.
     */
    fun stamp(
        sourceId: CgmSourceId,
        decoded: DecodedAdvert,
        flag: ReadingFlag,
        rxWallMs: Long,
        tzOffsetMin: Int,
        rssi: Int?,
    ): List<CgmReading> {
        val ts = snap(rxWallMs)
        val out = ArrayList<CgmReading>()

        val lTs = lastMeasuredTs
        val lBg = lastMeasuredBg
        if (flag == ReadingFlag.NORMAL && lTs != null && lBg != null && ts > lTs + gridMs) {
            val steps = ((ts - lTs) / gridMs).toInt()
            for (i in 1 until steps) {
                val fillTs = lTs + i * gridMs
                val frac = i.toDouble() / steps
                val bg = Math.round(lBg + (decoded.glucoseMgdl - lBg) * frac).toInt()
                out += CgmReading(
                    sourceId = sourceId,
                    tsMs = fillTs,
                    bgMgdl = bg,
                    trendTenthsPerMin = null,
                    minFromStart = null,
                    quality = null,
                    provenance = ReadingProvenance.INTERPOLATED,
                    flag = ReadingFlag.NORMAL,
                    tzOffsetMin = tzOffsetMin,
                    rxWallMs = fillTs,
                    rssi = null,
                )
            }
        }

        out += CgmReading(
            sourceId = sourceId,
            tsMs = ts,
            bgMgdl = decoded.glucoseMgdl,
            trendTenthsPerMin = decoded.trendTenthsPerMin,
            minFromStart = decoded.minFromStart,
            quality = decoded.quality,
            provenance = ReadingProvenance.MEASURED,
            flag = flag,
            tzOffsetMin = tzOffsetMin,
            rxWallMs = rxWallMs,
            rssi = rssi,
        )

        if (flag == ReadingFlag.NORMAL) {
            lastMeasuredTs = ts
            lastMeasuredBg = decoded.glucoseMgdl
        }
        return out
    }
}
