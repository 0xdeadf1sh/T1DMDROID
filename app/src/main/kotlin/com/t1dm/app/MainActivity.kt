package com.t1dm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.t1dm.core.common.NativeCore
import com.t1dm.core.design.T1dmTheme
import com.t1dm.core.nativecore.UniffiNativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class MainActivity : ComponentActivity() {

    // Manually injected for Phase 0 (no DI framework yet). The Native agent's uniffi-backed
    // impl will replace StubNativeCore behind this same NativeCore type.
    private val nativeCore: NativeCore = UniffiNativeCore()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { nativeCore.roundtrip("hello") }
                    .getOrElse { "error: ${it.message}" }
            }
            Timber.tag("NativeCore").i("roundtrip(\"hello\") -> %s", result)
        }

        setContent {
            T1dmTheme {
                T1dmApp()
            }
        }
    }
}
