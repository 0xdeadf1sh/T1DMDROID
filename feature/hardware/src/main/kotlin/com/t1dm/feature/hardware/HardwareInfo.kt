package com.t1dm.feature.hardware

/** Null in any field means the source was unreadable, never a crash. Pure data; no Android types. */
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
    /** Null before the probe runs. */
    val vulkan: VulkanInfo? = null,
) {
    companion object {
        val UNKNOWN = HardwareInfo(
            device = "…", soc = null, cpuTopology = null, cpuMaxFreqsGhz = emptyList(),
            abis = emptyList(), pageSizeKb = null, ramTotalMb = null, ramAvailMb = null,
            gpuRenderer = null, npu = null, display = null, androidVersion = null,
            securityPatch = null, thermalStatus = null, battery = null, batteryTempC = null,
            backends = emptyList(), vulkan = null,
        )
    }
}

/** [rows] are ordered `Label → Value` pairs rendered verbatim; [note] carries a reason when
 *  [available] is false. Describes the GPU only — it does NOT imply the model runs on Vulkan. */
data class VulkanInfo(
    val available: Boolean,
    val rows: List<Pair<String, String>>,
    val note: String?,
)
