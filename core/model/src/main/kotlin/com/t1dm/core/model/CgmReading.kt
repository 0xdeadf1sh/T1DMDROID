package com.t1dm.core.model

/** Where a grid-stamped reading came from (§3.1 / §3.6-A). */
enum class ReadingProvenance {
    /** A real, CRC-validated sensor reading snapped onto the 5-min grid. */
    MEASURED,

    /** A gap-fill value (linear interpolation across a dropout); never clears an alarm. */
    INTERPOLATED,

    /**
     * Model-reconstructed over a gap, promoted by a deliberate patient action
     * (`SPEC/invariants.md` §1, `http-api.md`'s `bg_reconstructed`). Never clears an alarm, never
     * counts as measured context, never enters a statistic as a measurement.
     */
    RECONSTRUCTED,
}

/** Presentation/gating classification of a reading (§3.1). */
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
 * One 5-minute grid sample (§3.1). `tsMs` is [rxWallMs] snapped to the grid (`tsMs % 300_000 == 0`).
 * [rxWallMs] is the instant the reading is FILED under: the phone-receive instant, or the sample
 * instant reconstructed from a source's sample index. Never a sensor's own clock — nothing on the
 * wire says whether it was ever set.
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
    val rxWallMs: Long,                // the instant filed under, before the grid snap
    val rssi: Int?,
)

/** Deliberately excludes `bgMgdl != null`: whether a value is present is a separate question, asked
 *  at the call sites that need both. */
fun isRealMeasurement(provenance: ReadingProvenance, flag: ReadingFlag): Boolean =
    provenance == ReadingProvenance.MEASURED && flag == ReadingFlag.NORMAL
