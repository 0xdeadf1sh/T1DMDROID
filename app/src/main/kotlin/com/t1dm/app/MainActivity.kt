package com.t1dm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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

    /** True when it navigated; false leaves the key as the system volume. */
    internal var volumeShortcut: ((up: Boolean) -> Boolean)? = null

    /** The key claimed on ACTION_DOWN, so its ACTION_UP is consumed by that same decision. */
    private var claimedKeyCode: Int? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            grants.forEach { (perm, granted) -> Timber.tag(TAG).i("perm %s granted=%b", perm, granted) }
            // Started regardless of grants: a missing one narrows the service, not fails it.
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
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private companion object {
        const val TAG = "CgmScan"
    }
}
