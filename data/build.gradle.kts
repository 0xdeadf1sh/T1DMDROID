plugins {
    id("t1dm.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // The backup archive parses one small JSON object per record. kotlinx.serialization rather than
    // `org.json` because the host JVM's `org.json` is a DIFFERENT implementation from Android's, so
    // a unit test pinning the archive's compatibility against it would be testing something other
    // than what ships — the reasoning the settings backup already records.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.t1dm.data"
    defaultConfig {
        // In-memory Room tests run instrumented on the target device (sensor-free, deterministic).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Exported schemas ship as androidTest assets so MigrationTestHelper can validate v1→v2.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

room {
    // Exported Room schemas back the keep-forever ALTER-only migration runner (Data implementer).
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Ship our own SQLite (with FTS5) — HyperOS/Android 16 system SQLite omits the fts5 module.
    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Host JVM unit tests for the curve/channel layer (StubNativeCore Kotlin port + fakes) and for
    // the archive's record codec, which is pure and needs neither Room nor a device.
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:${libs.versions.room.get()}")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
