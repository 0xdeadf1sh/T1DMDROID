plugins {
    id("t1dm.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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

    implementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:${libs.versions.room.get()}")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
