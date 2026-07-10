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
    //
    // `-Pt1dm.vulkan=false` selects the STOCK org.pytorch AAR (XNNPACK only) — used to capture the
    // authoritative-CPU baseline for the "CPU path unchanged" proof. The DEFAULT (property absent or
    // any value ≠ "false") selects the vendored custom AAR, whose single libexecutorch.so carries
    // BOTH VulkanBackend and the same XnnpackBackend. flatDir supplies no POM, so the AAR's runtime
    // transitives (fbjni / soloader nativeloader) are declared explicitly to match the stock POM.
    if (providers.gradleProperty("t1dm.vulkan").orNull == "false") {
        implementation(libs.executorch.android)
    } else {
        implementation(group = "", name = "executorch-vulkan-1.3.1", version = "", ext = "aar")
        implementation("com.facebook.fbjni:fbjni:0.7.0")
        implementation("com.facebook.soloader:nativeloader:0.10.5")
    }

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
}
