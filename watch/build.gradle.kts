plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.watch"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// A CLEAN REMOVABLE SEAM (architecture-native-core.md / PLAN §2.1): nothing depends on :watch.
// It reaches out only to the shared model + the dispatcher port — never to :inference, :alerts,
// :sync, :core:native or Room — so the whole accessory can be excised by deleting the module and
// its DI bindings. The push snapshot + crypto + nonce persistence all arrive through ports the
// composition root binds. The real X25519/AES-128-GCM lives in the Rust `t1dm-core` (bound in
// :app via a WatchSession over uniffi); this module ships a loopback session so the phone-side
// handshake/push validate against a desktop BLE-peripheral emulator before the ESP32-C3 exists.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
