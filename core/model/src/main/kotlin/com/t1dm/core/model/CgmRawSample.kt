package com.t1dm.core.model

/**
 * Every sample as received, filed at [rxWallMs] — off the grid, and never snapped and stored back;
 * [CgmReading] is the grid (`../T1DMCOMMON/SPEC/invariants.md` §1). No `provenance`: a row here was
 * sent by the sensor. Display and diagnosis only, bounded retention — absence is normal.
 */
data class CgmRawSample(
    val sourceId: CgmSourceId,
    val rxWallMs: Long,
    val bgMgdl: Int?,
    val trendTenthsPerMin: Int?,
    /** Sensor minutes-since-activation; ordering/dedup/warm-up only, never a grid stamp. */
    val minFromStart: Int?,
    val quality: Int?,
    val flag: ReadingFlag,
    val tzOffsetMin: Int,
    val rssi: Int?,
)
