package com.t1dm.feature.hardware

/**
 * A snapshot of the detected device hardware for the Hardware panel's top readout (PLAN.private.md
 * Phase 7C — item 8). Built by the `:app` `HardwareProbe` off Build/os APIs, /proc, /sys,
 * ActivityManager, Display, and the thermal/battery services; every field degrades to a plain "n/a"
 * (null here) rather than crashing when a source is unavailable. Pure data so `:feature:hardware`
 * needs no Android Context.
 */
data class HardwareInfo(
    val device: String,
    val soc: String?,
    val cpuTopology: String?,
    val cpuMaxFreqsGhz: List<Double>,
    val abis: List<String>,
    val pageSizeKb: Int?,
    val ramTotalMb: Long?,
    val ramAvailMb: Long?,
    val gpuRenderer: String?,
    val npu: String?,
    val display: String?,
    val androidVersion: String?,
    val securityPatch: String?,
    val thermalStatus: String?,
    val battery: String?,
    val backends: List<String>,
) {
    companion object {
        /** A safe placeholder while the probe runs (or on host preview). */
        val UNKNOWN = HardwareInfo(
            device = "…", soc = null, cpuTopology = null, cpuMaxFreqsGhz = emptyList(),
            abis = emptyList(), pageSizeKb = null, ramTotalMb = null, ramAvailMb = null,
            gpuRenderer = null, npu = null, display = null, androidVersion = null,
            securityPatch = null, thermalStatus = null, battery = null, backends = emptyList(),
        )
    }
}
