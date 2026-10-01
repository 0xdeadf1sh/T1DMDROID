plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.inference"

    // Parity with :app, which already filters at merge.
    defaultConfig {
        ndk { abiFilters += providers.gradleProperty("t1dm.abis").get().split(',') }
    }
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // Pinned to the exporter's version (descriptor.json -> 1.3.1). The stock AAR registers the
    // XNNPACK CPU delegate and nothing else, which is the one backend this app runs.
    implementation(libs.executorch.android)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
    // Shadows the unit-test android.jar's org.json stub, which throws "not mocked".
    testImplementation("org.json:json:20240303")
}
