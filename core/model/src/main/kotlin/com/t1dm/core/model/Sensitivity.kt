package com.t1dm.core.model

/** One unit, so difference IS response; :calc's SensitivityProbe and adapter guard share it. */
const val PROBE_DOSE_U = 1.0

/** Display-only, never :calc/stored/synced; [isfMgdlPerU] mg/dL/U, [icrGPerU] g/U, both positive */
data class SensitivityEstimate(
    val atMs: Long,
    val horizonMs: Long,
    val isfMgdlPerU: Double,
    val icrGPerU: Double,
    /** A selection change makes the held figures stale at once, whatever their age says. */
    val modelId: String,
)
