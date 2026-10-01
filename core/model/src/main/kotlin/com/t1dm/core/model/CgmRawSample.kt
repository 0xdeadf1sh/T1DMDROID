package com.t1dm.core.model

/** Off-grid, never snapped back; [CgmReading] is the grid (invariants.md §1); display/diagnosis. */
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
