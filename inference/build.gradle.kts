plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.inference"

    // Parity with :app, which already filters to arm64-v8a at merge.
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
    }
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // Pinned to the exporter's version (descriptor.json -> 1.3.1). `false` is the stock AAR
    // (XNNPACK only); the default vendored AAR carries Vulkan and XNNPACK. flatDir supplies no POM,
    // so the vendored branch declares the runtime transitives the stock POM would have.
    if (providers.gradleProperty("t1dm.vulkan").orNull == "false") {
        implementation(libs.executorch.android)
    } else {
        implementation(group = "", name = "executorch-vulkan-1.3.1", version = "", ext = "aar")
        implementation("com.facebook.fbjni:fbjni:0.7.0")
        implementation("com.facebook.soloader:nativeloader:0.10.5")
    }

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    // Shadows the unit-test android.jar's org.json stub, which throws "not mocked".
    testImplementation("org.json:json:20240303")
}
