package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/** Snaps to the 5-min grid, linear-fills gaps (§3.1); INTERPOLATED never suppresses an alarm. */
class GridStamper(private val gridMs: Long = CgmConstants.GRID_MS) {

    private var lastMeasuredTs: Long? = null
    private var lastMeasuredBg: Int? = null

    fun snap(rxWallMs: Long): Long = Math.round(rxWallMs.toDouble() / gridMs) * gridMs

    /** Gap-fills first, in chronological order, then this advert's own reading. */
    fun stamp(
        sourceId: CgmSourceId,
        decoded: DecodedAdvert,
        flag: ReadingFlag,
        rxWallMs: Long,
        tzOffsetMin: Int,
        rssi: Int?,
    ): List<CgmReading> = stamp(
        sourceId = sourceId,
        bgMgdl = decoded.glucoseMgdl,
        trendTenthsPerMin = decoded.trendTenthsPerMin,
        minFromStart = decoded.minFromStart,
        quality = decoded.quality,
        flag = flag,
        rxWallMs = rxWallMs,
        tzOffsetMin = tzOffsetMin,
        rssi = rssi,
    )

    /** stamp over plain values; bgMgdl NULLABLE, a zero would wrongly band URGENT_LOW. */
    fun stamp(
        sourceId: CgmSourceId,
        bgMgdl: Int?,
        trendTenthsPerMin: Int?,
        minFromStart: Int?,
        quality: Int?,
        flag: ReadingFlag,
        rxWallMs: Long,
        tzOffsetMin: Int,
        rssi: Int?,
    ): List<CgmReading> {
        val ts = snap(rxWallMs)
        val out = ArrayList<CgmReading>()

        val lTs = lastMeasuredTs
        val lBg = lastMeasuredBg
        if (flag == ReadingFlag.NORMAL && bgMgdl != null && lTs != null && lBg != null && ts > lTs + gridMs) {
            val steps = ((ts - lTs) / gridMs).toInt()
            for (i in 1 until steps) {
                val fillTs = lTs + i * gridMs
                val frac = i.toDouble() / steps
                val bg = Math.round(lBg + (bgMgdl - lBg) * frac).toInt()
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
            bgMgdl = bgMgdl,
            trendTenthsPerMin = trendTenthsPerMin,
            minFromStart = minFromStart,
            quality = quality,
            provenance = ReadingProvenance.MEASURED,
            flag = flag,
            tzOffsetMin = tzOffsetMin,
            rxWallMs = rxWallMs,
            rssi = rssi,
            // Passive: the sensor broadcasts its current value, so receipt is the best clock.
            measuredAtMs = rxWallMs,
        )

        // A valueless NORMAL reading cannot anchor interpolation across a gap nothing measured.
        if (flag == ReadingFlag.NORMAL) {
            lastMeasuredTs = if (bgMgdl != null) ts else null
            lastMeasuredBg = bgMgdl
        }
        return out
    }
}
