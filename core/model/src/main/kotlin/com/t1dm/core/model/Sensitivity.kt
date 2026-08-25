package com.t1dm.core.model

/**
 * One unit, so the difference IS the response. One definition: `:calc`'s `SensitivityProbe` and
 * `:inference`'s adapter guard both report a response PER UNIT against this same probe.
 */
const val PROBE_DOSE_U = 1.0

/**
 * Display-only: never enters `:calc`'s decision path, never stored, never synced. [isfMgdlPerU] is
 * mg/dL the median falls per unit of rapid insulin, [icrGPerU] grams one unit cancels; both
 * positive, both marginal at [horizonMs] so smaller than the whole-action definitions.
 */
data class SensitivityEstimate(
    val atMs: Long,
    val horizonMs: Long,
    val isfMgdlPerU: Double,
    val icrGPerU: Double,
    /** A selection change makes the held figures stale at once, whatever their age says. */
    val modelId: String,
)
