package com.t1dm.core.model

/** Where a grid-stamped reading came from (SPEC.private.md §3.1 / §3.6-A). */
enum class ReadingProvenance {
    /** A real, CRC-validated sensor reading snapped onto the 5-min grid. */
    MEASURED,

    /** A gap-fill value (linear interpolation across a dropout); never clears an alarm. */
    INTERPOLATED,
}

/** Presentation/gating classification of a reading (SPEC.private.md §3.1). */
enum class ReadingFlag {
    /** Passed the validity gate; eligible for inference and alarm evaluation. */
    NORMAL,

    /** Within the sensor warm-up window (minFromStart < WARMUP_WINDOW_MIN); suppressed from
     *  inference and alarm evaluation, shown distinctly on the graph. */
    WARMUP,

    /** Failed the validity gate (bad valid-bit / status / range); not persisted as a value. */
    INVALID,
}

/**
 * One 5-minute grid sample from a CGM source (SPEC.private.md §3.1). `tsMs` is phone-receive
 * time snapped to the grid (`tsMs % 300_000 == 0`); the sensor's own clock is never trusted in
 * passive mode. Nullable value fields let a row exist (e.g. INTERPOLATED, WARMUP) without a
 * defined measurement.
 */
data class CgmReading(
    val sourceId: CgmSourceId,
    val tsMs: Long,                    // ts % 300_000 == 0
    val bgMgdl: Int?,
    val trendTenthsPerMin: Int?,       // rate-of-change in 0.1 mg/dL/min units
    val minFromStart: Int?,            // sensor minutes-since-activation; ordering/dedup/warmup only
    val quality: Int?,
    val provenance: ReadingProvenance,
    val flag: ReadingFlag,
    val tzOffsetMin: Int,
    val rxWallMs: Long,                // raw phone-receive wall time before grid snap
    val rssi: Int?,
)
