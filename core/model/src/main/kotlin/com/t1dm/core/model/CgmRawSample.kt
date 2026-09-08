package com.t1dm.core.model

/** As-received sample at [rxWallMs], off-grid, never snapped back; display/diagnosis only. */
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
