package com.t1dm.app.hardware

import com.t1dm.feature.hardware.VulkanInfo
import timber.log.Timber

/** JNI to `app/src/main/cpp/vulkan_probe.cpp`; probes without running the model. Native returns
 *  newline-delimited `Label\tValue` rows; a load or JNI failure degrades to unavailable. */
object VulkanProbe {

    private val available: Boolean = runCatching {
        System.loadLibrary("t1dmvk")
        true
    }.getOrElse {
        Timber.tag("VulkanProbe").w(it, "libt1dmvk not loadable; Vulkan section will read unavailable")
        false
    }

    private external fun nativeProbe(): String

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
