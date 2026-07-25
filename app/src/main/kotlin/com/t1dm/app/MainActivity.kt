package com.t1dm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.t1dm.app.di.AppContainer
import com.t1dm.app.service.CgmScanService
import com.t1dm.core.design.HapticStrength
import com.t1dm.core.design.T1dmFontId
import com.t1dm.core.design.T1dmTheme
import com.t1dm.core.design.resolvePalette
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as T1dmApplication).container

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            grants.forEach { (perm, granted) -> Timber.tag(TAG).i("perm %s granted=%b", perm, granted) }
            // Start (or top-up) the monitor once the user has answered — a missing grant only
            // narrows what the service can do; the service itself is resilient to it.
            CgmScanService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()

        setContent {
            val ss = container.settingsStore
            val themeId by ss.themeId.collectAsState("tron")
            val fontKey by ss.fontId.collectAsState("system")
            val animations by ss.animationsEnabled.collectAsState(true)
            val death by ss.deathMode.collectAsState(false)
            val hapticsKey by ss.hapticsLevel.collectAsState(HapticStrength.DEFAULT.name)
            val customJson by ss.customThemeJson.collectAsState(null)
            val palette = remember(themeId, customJson) { resolvePalette(themeId, customJson) }
            T1dmTheme(
                palette = palette,
                font = T1dmFontId.forKey(fontKey),
                animationsEnabled = animations,
                deathMode = death,
                hapticStrength = HapticStrength.forKey(hapticsKey),
            ) {
                T1dmApp(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate inference immediately on resume so the panels reflect the CURRENT context instead of
        // the last 5-min grid cycle's possibly-stale forecast (which lingered as a STABLE read-out on
        // reopen). Serialised + gated identically inside the controller — never bypasses a §3.6 gate.
        container.reevaluateInferenceNow()
    }

    override fun onStop() {
        super.onStop()
        // Defer the theme→launcher-icon swap to backgrounding: toggling an <activity-alias> while the
        // task is foregrounded lets HyperOS's recents evict us mid-swap (see build gotchas). By onStop
        // the user has left, so the churn is invisible and the eviction window is closed.
        // Never disable the alias that launched us (`intent.component.className` resolves to the alias
        // for an alias-launched Activity) — belt-and-braces against a recents eviction that could
        // otherwise strand the task on a now-disabled component.
        runCatching {
            LauncherIconManager.apply(
                applicationContext,
                container.themeIdSnapshot,
                keepEnabledAlias = intent?.component?.className,
            )
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            // The watch link holds a GATT session, and on Android 12+ BLUETOOTH_CONNECT is granted
            // independently of BLUETOOTH_SCAN — so it must be requested explicitly or connectGatt()
            // fails with a permission error even after the user allows "Nearby devices".
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            CgmScanService.start(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private companion object {
        const val TAG = "CgmScan"
    }
}
