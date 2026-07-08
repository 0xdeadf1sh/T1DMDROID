package com.t1dm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.t1dm.app.di.AppContainer
import com.t1dm.app.service.CgmScanService
import com.t1dm.core.design.T1dmTheme
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
            T1dmTheme {
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
