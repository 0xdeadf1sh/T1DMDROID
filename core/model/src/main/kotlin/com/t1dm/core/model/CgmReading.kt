package com.t1dm.core.model

/** Where a grid-stamped reading came from (§3.1 / §3.6-A). */
enum class ReadingProvenance {
    /** A real, CRC-validated sensor reading snapped onto the 5-min grid. */
    MEASURED,

    /** A gap-fill value (linear interpolation across a dropout); never clears an alarm. */
    INTERPOLATED,

    /** Model-reconstructed, patient-promoted (SPEC/invariants.md §1); never alarm/context/stat. */
    RECONSTRUCTED,
}

/** Presentation/gating classification of a reading (§3.1). */
enum class ReadingFlag {
    /** Passed the validity gate; eligible for inference and alarm evaluation. */
    NORMAL,

    /** In warm-up window (minFromStart < WARMUP_WINDOW_MIN); suppressed, shown distinctly. */
    WARMUP,

    /** Failed the validity gate (bad valid-bit / status / range); not persisted as a value. */
    INVALID,
}

/** §3.1: tsMs is measuredAtMs snapped to grid. */
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
    // Pre-snap: receipt, or the record's own sample clock; a gap-fill's or reconstruction's slot.
    val rxWallMs: Long,
    val rssi: Int?,
    // Unsnapped instant tsMs came from: receipt passively, the sensor's own clock when connected.
    val measuredAtMs: Long? = null,
)

/** Excludes bgMgdl != null on purpose; presence is a separate question asked at call sites. */
fun isRealMeasurement(provenance: ReadingProvenance, flag: ReadingFlag): Boolean =
    provenance == ReadingProvenance.MEASURED && flag == ReadingFlag.NORMAL
