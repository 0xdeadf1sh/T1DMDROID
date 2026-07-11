package com.t1dm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.t1dm.app.di.AppContainer
import com.t1dm.app.service.CgmScanService
import com.t1dm.core.design.T1dmFontId
import com.t1dm.core.design.T1dmTheme
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.design.TronPalette
import com.t1dm.core.design.paletteForId
import com.t1dm.core.design.parseThemeJson
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
            val customJson by ss.customThemeJson.collectAsState(null)
            val palette = remember(themeId, customJson) {
                val cj = customJson
                if (themeId == ThemeIds.CUSTOM && !cj.isNullOrBlank()) {
                    runCatching { parseThemeJson(cj) }.getOrDefault(TronPalette)
                } else {
                    paletteForId(themeId)
                }
            }
            // Keep the home-screen launcher icon in step with the selected theme (issues 2/6).
            LaunchedEffect(themeId) { LauncherIconManager.apply(applicationContext, themeId) }
            T1dmTheme(palette = palette, font = T1dmFontId.forKey(fontKey), animationsEnabled = animations, deathMode = death) {
                T1dmApp(container)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
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
