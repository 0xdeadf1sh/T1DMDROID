import com.android.build.api.dsl.LibraryExtension

// Convention plugin for the module that hosts the Rust `t1dm-core` crate (:core:native).
// It is a plain Android library restricted to arm64-v8a; the actual cargo/uniffi wiring
// (host binding generation + the guarded cargo-ndk cross-build) lives in that module's
// build.gradle.kts, so this plugin stays free of any NDK requirement.
plugins {
    id("t1dm.android.library")
}

extensions.configure<LibraryExtension> {
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    // NOTE: `ndkVersion` is intentionally NOT set here. Setting it forces an NDK install at
    // configuration time, which we do not want on host-only machines. cargo-ndk locates the
    // NDK itself (env / $SDK/ndk/<ver>) inside the module's guarded cargoNdkBuild task.
}
