package com.t1dm.core.model

/** One unit; `:calc`'s `SensitivityProbe` and `:inference`'s adapter guard both use this probe. */
const val PROBE_DOSE_U = 1.0

/** Display-only, never stored/synced; raw ISF/ICR at [horizonMs], any sign: <= 0 is unusable. */
data class SensitivityEstimate(
    val atMs: Long,
    val horizonMs: Long,
    val isfMgdlPerU: Double,
    val icrGPerU: Double,
    /** A selection change makes the held figures stale at once, whatever their age says. */
    val modelId: String,
)
