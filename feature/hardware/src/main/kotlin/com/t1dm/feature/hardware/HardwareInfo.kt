package com.t1dm.feature.hardware

/** Null in any field means unreadable source, never a crash; pure data, no Android types. */
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
    /** Celsius; null when unreadable. */
    val batteryTempC: Double? = null,
    val backends: List<String>,
) {
    companion object {
        val UNKNOWN = HardwareInfo(
            device = "…", soc = null, cpuTopology = null, cpuMaxFreqsGhz = emptyList(),
            abis = emptyList(), pageSizeKb = null, ramTotalMb = null, ramAvailMb = null,
            gpuRenderer = null, npu = null, display = null, androidVersion = null,
            securityPatch = null, thermalStatus = null, battery = null, batteryTempC = null,
            backends = emptyList(),
        )
    }
}
