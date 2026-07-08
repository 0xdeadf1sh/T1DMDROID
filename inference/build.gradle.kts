plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.inference"

    // The single target device is arm64-v8a; the ExecuTorch AAR also ships an x86_64 .so which we
    // do not need. :app already filters to arm64-v8a at merge, so this keeps parity for the module.
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
    }
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // ExecuTorch Android runtime, pinned to the exporter's version (descriptor.json → 1.3.1).
    implementation(libs.executorch.android)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
}
