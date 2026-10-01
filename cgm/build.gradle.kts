plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.cgm"
    // The source logs via android.util.Log; unmocked android.* stubs throw "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    // `JsonElement` only. Parses the bind-client import document identically on device and on the host
    // JVM; `org.json`'s test stand-in has different `opt*` null semantics. Already in the APK.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // Write pacing and the staleness watchdog are timing; `runTest`'s virtual clock skips the waiting.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
