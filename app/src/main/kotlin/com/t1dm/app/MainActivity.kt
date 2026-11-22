package com.t1dm.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.t1dm.app.di.AppContainer
import com.t1dm.app.service.BackgroundControls
import com.t1dm.app.service.CgmScanService
import com.t1dm.app.settings.SettingsStore
import com.t1dm.core.design.HapticStrength
import com.t1dm.core.design.T1dmFontId
import com.t1dm.core.design.T1dmTheme
import com.t1dm.core.design.resolvePalette
import com.t1dm.feature.settings.BackgroundFix
import kotlinx.coroutines.launch
import timber.log.Timber

/** Alarm, notification and widget taps open BG, not the start screen. */
fun bgIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).putExtra(EXTRA_ROUTE, ROUTE_BG)

private const val EXTRA_ROUTE = "com.t1dm.app.ROUTE"
private const val ROUTE_BG = "dashboard"

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as T1dmApplication).container

    /** Exported activity: only [ROUTE_BG] is honoured, whatever a caller puts in the extra. */
    private val requestedRoute = mutableStateOf<String?>(null)

    private fun takeRoute(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_ROUTE) == ROUTE_BG) requestedRoute.value = ROUTE_BG
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeRoute(intent)
    }

    /** True when it navigated; false leaves the key as the system volume. */
    internal var volumeShortcut: ((up: Boolean) -> Boolean)? = null

    /** The key claimed on ACTION_DOWN, so its ACTION_UP is consumed by that same decision. */
    private var claimedKeyCode: Int? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            grants.forEach { (perm, granted) -> Timber.tag(TAG).i("perm %s granted=%b", perm, granted) }
            CgmScanService.start(this)
            promptBatteryOnce()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation replays the launch intent; only the first creation may navigate.
        if (savedInstanceState == null) takeRoute(intent)

        requestRuntimePermissions()

        setContent {
            val ss = container.settingsStore
            val themeId by ss.themeId.collectAsState(SettingsStore.DEFAULT_THEME)
            val fontKey by ss.fontId.collectAsState(SettingsStore.DEFAULT_FONT)
            val animations by ss.animationsEnabled.collectAsState(true)
            val death by ss.deathMode.collectAsState(false)
            val hapticsKey by ss.hapticsLevel.collectAsState(HapticStrength.DEFAULT.name)
            val palette = remember(themeId) { resolvePalette(themeId) }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !palette.dark
                    isAppearanceLightNavigationBars = !palette.dark
                }
            }
            T1dmTheme(
                palette = palette,
                font = T1dmFontId.forKey(fontKey),
                animationsEnabled = animations,
                deathMode = death,
                hapticStrength = HapticStrength.forKey(hapticsKey),
            ) {
                T1dmApp(container, requestedRoute.value) { requestedRoute.value = null }
            }
        }
    }

    /** Volume up navigates to Meals, volume down to Insulin. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return claimedKeyCode == code
                val claimed = volumeShortcut?.invoke(code == KeyEvent.KEYCODE_VOLUME_UP) ?: false
                claimedKeyCode = if (claimed) code else null
                if (claimed) return true
            }
            KeyEvent.ACTION_UP -> if (claimedKeyCode == code) {
                claimedKeyCode = null
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // Serialised and gated inside the controller; this never bypasses a §3.6 gate.
        container.reevaluateInferenceNow()
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            // Granted independently of BLUETOOTH_SCAN on 12+; without it connectGatt() fails.
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
            promptBatteryOnce()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** Once per install; Settings › Background reopens it. */
    private fun promptBatteryOnce() {
        if (BackgroundControls.batteryUnrestricted(this)) return
        lifecycleScope.launch {
            val ss = container.settingsStore
            if (ss.batteryPromptShown()) return@launch
            ss.setBatteryPromptShown()
            BackgroundControls.open(this@MainActivity, BackgroundFix.BATTERY)
        }
    }

    private companion object {
        const val TAG = "CgmScan"
    }
}
