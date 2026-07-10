package com.t1dm.app.hardware

import com.t1dm.feature.hardware.VulkanInfo
import timber.log.Timber

/**
 * JNI bridge to the headless Vulkan capability shim (`app/src/main/cpp/vulkan_probe.cpp`,
 * issue 20 — STEP 5). Enumerates the device's GPU/driver/compute facts via the NDK Vulkan
 * loader WITHOUT running the model — so the Hardware panel's GPU/Vulkan section is populated
 * even though the custom Vulkan-delegate ExecuTorch AAR is a separate (and possibly failing)
 * build. The native side returns newline-delimited `Label\tValue` rows; a load/JNI failure
 * degrades to an unavailable [VulkanInfo] rather than crashing (the whole panel is best-effort).
 */
object VulkanProbe {

    private val available: Boolean = runCatching {
        System.loadLibrary("t1dmvk")
        true
    }.getOrElse {
        Timber.tag("VulkanProbe").w(it, "libt1dmvk not loadable; Vulkan section will read unavailable")
        false
    }

    private external fun nativeProbe(): String

    /** Best-effort Vulkan capability readout; null when the loader/JNI is unavailable. */
    fun probe(): VulkanInfo? {
        if (!available) return VulkanInfo(available = false, rows = emptyList(), note = "Vulkan probe library unavailable")
        val raw = runCatching { nativeProbe() }.getOrElse {
            Timber.tag("VulkanProbe").w(it, "nativeProbe threw")
            return VulkanInfo(available = false, rows = emptyList(), note = "Vulkan probe failed: ${it.message}")
        }
        val rows = raw.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toList()
        val ok = rows.any { it.first == "Status" && it.second == "ok" }
        return VulkanInfo(available = ok, rows = rows.filterNot { it.first == "Status" && it.second == "ok" }, note = null)
    }
}
