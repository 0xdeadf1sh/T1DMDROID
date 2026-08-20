package com.t1dm.core.model

/**
 * One CGM sample exactly as it was received: at the instant it is filed under, off the grid.
 *
 * The companion of [CgmReading], not a replacement for it. `../T1DMCOMMON/SPEC/invariants.md` §1
 * fixes the five-minute grid for every physiologic sample the suite exchanges, and [CgmReading] is
 * that grid: one row per `(source, slot)`, which is what the model, the statistics, the alarms and
 * the wire read. A sensor that samples faster than the grid therefore has more samples than there
 * are slots, and the surplus used to be discarded by the upsert. This is where the surplus is kept.
 *
 * Two consequences of that follow from the definition and are worth stating:
 *
 *  - **There is no `provenance`.** Every row here was received; an interpolated gap-fill is
 *    fabricated by [com.t1dm.cgm.GridStamper] and has no instant of its own to be filed under, so it
 *    never reaches this store. Reading a row from here means the sensor sent it.
 *  - **`rxWallMs` is not on the grid** and must never be snapped and stored back as though it were.
 *    Which slot a sample was filed under is a question with one answer
 *    (`T1dmRepository.snapToGrid`), and recording that answer beside the sample would be a second
 *    copy of it.
 *
 * `rxWallMs` is [CgmReading.rxWallMs] — the instant the sample is filed under, which for a source that
 * reports a sample index is the reconstructed sample instant rather than the delivery instant. That is
 * what makes the `(source, instant)` primary key idempotent under a re-delivery: the same sample carries
 * the same instant however many times it arrives, while its delivery instant would differ each time.
 *
 * Display and diagnosis only. Nothing derives a forecast, a statistic or an alarm from these rows,
 * and they carry a bounded retention (`T1dmRepository.RAW_SAMPLE_RETENTION_MS`) — so a reader must
 * treat their absence as normal rather than as a gap in the record.
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
