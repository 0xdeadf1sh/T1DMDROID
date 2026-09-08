package com.t1dm.app.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager
import com.t1dm.feature.hardware.HardwareInfo
import java.io.File

/** Every field degrades to null, never throws. Call [probe] off-main: GL spins an EGL pbuffer. */
class HardwareProbe(private val context: Context) {

    fun probe(): HardwareInfo = HardwareInfo(
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        soc = soc(),
        cpuTopology = cpuTopology(),
        cpuMaxFreqsGhz = cpuMaxFreqsGhz(),
        abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
        pageSizeKb = pageSizeKb(),
        ramTotalMb = ram()?.first,
        ramAvailMb = ram()?.second,
        gpuRenderer = gpuRenderer(),
        npu = npu(),
        display = display(),
        androidVersion = "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}",
        securityPatch = runCatching { Build.VERSION.SECURITY_PATCH }.getOrNull()?.ifBlank { null },
        thermalStatus = thermalStatus(),
        battery = battery(),
        batteryTempC = batteryTempC(),
        backends = backends(),
    )

    private fun soc(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mfr = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != "unknown" }
            val model = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != "unknown" }
            listOfNotNull(mfr, model).joinToString(" ").ifBlank { null }
        } else null
    }.getOrNull() ?: runCatching {
        listOfNotNull(
            Build.HARDWARE.takeIf { it.isNotBlank() },
            Build.BOARD.takeIf { it.isNotBlank() },
        ).distinct().joinToString(" · ").ifBlank { null }
    }.getOrNull()

    private fun cpuTopology(): String? = runCatching {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = cpuMaxFreqsGhz()
        val tiers = freqs.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }
        val topo = if (tiers.isNotEmpty()) {
            tiers.joinToString(" + ") { "${it.value}×${"%.2f".format(it.key)}GHz" }
        } else "$n cores"
        "$n cores ($topo)"
    }.getOrNull()

    private fun cpuMaxFreqsGhz(): List<Double> = runCatching {
        val n = Runtime.getRuntime().availableProcessors()
        (0 until n).mapNotNull { i ->
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            runCatching { f.readText().trim().toLong() }.getOrNull()?.let { it / 1_000_000.0 }
        }
    }.getOrDefault(emptyList())

    private fun pageSizeKb(): Int? = runCatching {
        // 16 KB-page detection (target-device.md).
        val bytes = if (Build.VERSION.SDK_INT >= 34) android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) else 4096L
        (bytes / 1024L).toInt()
    }.getOrNull()

    private fun ram(): Pair<Long, Long>? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / (1L shl 20)) to (mi.availMem / (1L shl 20))
    }.getOrNull()

    private fun display(): String? = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val d: Display = wm.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(metrics)
        val hdr = if (Build.VERSION.SDK_INT >= 24 && d.isHdr) " · HDR" else ""
        val refresh = runCatching { d.supportedModes.maxOf { it.refreshRate } }.getOrDefault(d.refreshRate)
        "${metrics.widthPixels}×${metrics.heightPixels} @ ${refresh.toInt()}Hz$hdr"
    }.getOrNull()

    private fun thermalStatus(): String? = runCatching {
        if (Build.VERSION.SDK_INT < 29) return@runCatching null
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }.getOrNull()

    private fun battery(): String? = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        buildString {
            append("$pct%")
            if (charging) append(" (charging)")
        }
    }.getOrNull()

    private fun batteryTempC(): Double? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10.0 }
    }.getOrNull()

    private fun npu(): String? = runCatching {
        // No public NPU inventory API; inferred from the SoC (target-device.md).
        val plat = (Build.HARDWARE + " " + Build.BOARD).lowercase()
        when {
            plat.contains("mt6993") -> "MediaTek APU 990 (Dimensity 9500, inferred)"
            plat.contains("mt") -> "MediaTek APU (inferred from ${Build.HARDWARE})"
            else -> null
        }
    }.getOrNull()

    /** Static routing targets, not what ran; live backend is [RunningModel.backend]. */
    private fun backends(): List<String> = listOf(
        "ExecuTorch XNNPACK fp32 (CPU) — the only delegate this build registers, and the only path a " +
            "dose may be scored on",
    )

    private fun gpuRenderer(): String? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
        try {
            val cfgAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val nCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, nCfg, 0) || nCfg[0] == 0) return null
            val ctx = EGL14.eglCreateContext(
                display, cfgs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (ctx == EGL14.EGL_NO_CONTEXT) return null
            val surf = EGL14.eglCreatePbufferSurface(
                display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            try {
                if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return null
                val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
                val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                listOfNotNull(renderer, vendor?.takeIf { it != renderer }).joinToString(" · ").ifBlank { null }
            } finally {
                EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }.getOrNull()
}
