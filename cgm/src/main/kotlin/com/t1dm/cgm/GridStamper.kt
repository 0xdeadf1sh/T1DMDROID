package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/**
 * Snaps each accepted reading to the 5-minute grid and reconstructs a gap-free series (§3.1).
 *
 * `tsMs` is `rxWallMs` rounded to the nearest slot, and `rxWallMs` is whatever instant the CALLER files
 * the sample under — see [com.t1dm.core.model.CgmReading]. `minFromStart` never defines a stamp: it is
 * kept for ordering, dedup, the warm-up test and the sensor-age read-out, and a source that reports one
 * converts it to an instant itself rather than handing this a sensor epoch to trust.
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

    /**
     * [stamp] over plain values, for a source whose decode is not a [DecodedAdvert].
     *
     * [bgMgdl] is NULLABLE here, and that is the whole reason this overload exists.
     * [DecodedAdvert.glucoseMgdl] is a non-null `Int` — it is the AiDEX advert carrier and the
     * debug-injection type — and widening it would ripple through both branches for one family's
     * benefit. A source that can report "no value" (a sensor in warm-up whose wire glucose field reads
     * zero) needs to pass that through as absent rather than as a zero, because a zero would be banded
     * URGENT_LOW downstream and sent on the wire as a real reading.
     *
     * The interpolation branch still requires a NORMAL flag AND a non-null value at both ends, so a
     * valueless row can neither anchor a fabricated line nor be one.
     */
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
        )

        // A NORMAL reading with no value cannot anchor the next interpolation either — there would be
        // nothing to interpolate FROM, and carrying the previous anchor forward would draw a line across
        // a gap it never measured.
        if (flag == ReadingFlag.NORMAL) {
            lastMeasuredTs = if (bgMgdl != null) ts else null
            lastMeasuredBg = bgMgdl
        }
        return out
    }
}
