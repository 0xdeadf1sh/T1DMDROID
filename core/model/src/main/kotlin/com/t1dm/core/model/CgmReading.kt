package com.t1dm.core.model

/** Where a grid-stamped reading came from (§3.1 / §3.6-A). */
enum class ReadingProvenance {
    /** A real, CRC-validated sensor reading snapped onto the 5-min grid. */
    MEASURED,

    /** A gap-fill value (linear interpolation across a dropout); never clears an alarm. */
    INTERPOLATED,

    /**
     * A value a model reconstructed over a sensor gap, which the patient then promoted to a stored
     * sample by a deliberate action (`SPEC/invariants.md` §1, `http-api.md`'s `bg_reconstructed`).
     *
     * The flag is for life. Such a value may never clear an alarm, never count as measured context
     * for a cold start or a warm-up, and never enter a statistic as a measurement — which is to say
     * it is not a measurement, and [isRealMeasurement] is the one place that has to keep saying so.
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
 * One 5-minute grid sample from a CGM source (§3.1). `tsMs` is [rxWallMs] snapped to the grid
 * (`tsMs % 300_000 == 0`). Nullable value fields let a row exist (e.g. INTERPOLATED, WARMUP) without a
 * defined measurement.
 *
 * **[rxWallMs] is the instant the reading is FILED under**, and which instant that is depends on what
 * the source can tell us:
 *
 *  - A source that dates nothing is filed under the phone-receive instant. That is the best available
 *    answer to "when was this measured", and for a sensor that samples on the grid it is exact enough
 *    that the two questions never come apart.
 *  - A source that reports a sample INDEX on a fixed cadence is filed under the sample instant
 *    reconstructed from that index. For a sensor faster than the grid this is the better answer and the
 *    difference is material: several of its samples fall in one slot, and filing them by delivery time
 *    puts them in slots they were not measured in.
 *
 * A sensor's own real-time clock is used for neither. They are set by the phone at best and read a
 * factory default at worst, and nothing on the wire says which.
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
    val rxWallMs: Long,                // the instant filed under, before the grid snap; see above
    val rssi: Int?,
)

/**
 * Whether a reading is the sensor's own measurement rather than fabricated or suppressed (§3.6-A).
 *
 * Spelled once, over the two enums rather than over a row type, because the callers hold different
 * ones: `:alerts` an eligibility check on a domain [CgmReading], `:data` a display-ranking check on a
 * stored entity. Both mean the same thing, and a second spelling of it is how one of them ends up
 * quietly disagreeing about what counts as real.
 *
 * Deliberately NOT part of it: `bgMgdl != null`. Presence of a value is a separate question — the
 * alarm path needs both and says so at its own call site, while ranking two readings for the graph
 * does not care. Folding it in here would make every caller pay for a check only some of them want.
 */
fun isRealMeasurement(provenance: ReadingProvenance, flag: ReadingFlag): Boolean =
    provenance == ReadingProvenance.MEASURED && flag == ReadingFlag.NORMAL
