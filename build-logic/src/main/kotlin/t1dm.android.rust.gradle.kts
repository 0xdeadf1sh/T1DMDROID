import com.android.build.api.dsl.LibraryExtension

// For :core:native. The cargo/uniffi wiring lives in that module's build.gradle.kts, so this
// plugin needs no NDK.
plugins {
    id("t1dm.android.library")
}

extensions.configure<LibraryExtension> {
    defaultConfig {
        ndk {
            abiFilters += providers.gradleProperty("t1dm.abis").get().split(',')
        }
    }
    // No ndkVersion: it would force an NDK install at configuration time on host-only machines.
    // cargo-ndk locates the NDK itself.
}
