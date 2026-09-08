package com.t1dm.core.model

/** Where a grid-stamped reading came from (§3.1 / §3.6-A). */
enum class ReadingProvenance {
    /** A real, CRC-validated sensor reading snapped onto the 5-min grid. */
    MEASURED,

    /** A gap-fill value (linear interpolation across a dropout); never clears an alarm. */
    INTERPOLATED,

    /** Reconstructed, deliberate action (§1,`bg_reconstructed`); never alarm, measured, or stat. */
    RECONSTRUCTED,
}

/** Presentation/gating classification of a reading (§3.1). */
enum class ReadingFlag {
    /** Passed the validity gate; eligible for inference and alarm evaluation. */
    NORMAL,

    /** minFromStart<WARMUP_WINDOW_MIN; suppressed from inference/alarm, shown distinct on graph. */
    WARMUP,

    /** Failed the validity gate (bad valid-bit / status / range); not persisted as a value. */
    INVALID,
}

/** 5-min sample (§3.1): tsMs=rxWallMs snapped (%300_000==0); filed instant, never sensor clock. */
data class CgmReading(
    val sourceId: CgmSourceId,
    val tsMs: Long,                    // ts % 300_000 == 0
    val bgMgdl: Int?,
    val trendTenthsPerMin: Int?,       // rate-of-change in 0.1 mg/dL/min units
    val minFromStart: Int?,            // sensor min-since-activation; ordering/dedup/warmup only
    val quality: Int?,
    val provenance: ReadingProvenance,
    val flag: ReadingFlag,
    val tzOffsetMin: Int,
    val rxWallMs: Long,                // the instant filed under, before the grid snap
    val rssi: Int?,
)

/** Excludes `bgMgdl != null` deliberately; presence is asked separately where both are needed. */
fun isRealMeasurement(provenance: ReadingProvenance, flag: ReadingFlag): Boolean =
    provenance == ReadingProvenance.MEASURED && flag == ReadingFlag.NORMAL
