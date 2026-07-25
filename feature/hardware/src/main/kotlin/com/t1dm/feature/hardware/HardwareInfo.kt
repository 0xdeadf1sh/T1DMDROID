package com.t1dm.feature.hardware

/**
 * A snapshot of the detected device hardware for the Hardware panel's top readout (
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
    /** The battery-sensor temperature in Celsius (U9); rendered in the user's chosen unit by the UI.
     *  Null when unreadable. There is no readable fan RPM on this device — never a proxied figure. */
    val batteryTempC: Double? = null,
    val backends: List<String>,
    /** GPU / Vulkan compute capability readout (issue 20 — STEP 5); null before the probe runs. */
    val vulkan: VulkanInfo? = null,
) {
    companion object {
        /** A safe placeholder while the probe runs (or on host preview). */
        val UNKNOWN = HardwareInfo(
            device = "…", soc = null, cpuTopology = null, cpuMaxFreqsGhz = emptyList(),
            abis = emptyList(), pageSizeKb = null, ramTotalMb = null, ramAvailMb = null,
            gpuRenderer = null, npu = null, display = null, androidVersion = null,
            securityPatch = null, thermalStatus = null, battery = null, batteryTempC = null,
            backends = emptyList(), vulkan = null,
        )
    }
}

/**
 * The device's Vulkan compute capabilities (issue 20 — STEP 5), enumerated by the `:app`
 * `VulkanProbe` JNI shim over the NDK Vulkan loader. [rows] are ordered `Label → Value` pairs
 * (API/driver/device, subgroup size, fp16 support, workgroup limits, queue families, memory
 * heaps, unified-memory flag) rendered verbatim; [available] is true when the probe created an
 * instance and enumerated a physical device; [note] carries a plain reason when it could not.
 * This describes the GPU itself — it does NOT imply the model runs on Vulkan (that needs the
 * custom delegate AAR). Pure data; no Android types.
 */
data class VulkanInfo(
    val available: Boolean,
    val rows: List<Pair<String, String>>,
    val note: String?,
)
