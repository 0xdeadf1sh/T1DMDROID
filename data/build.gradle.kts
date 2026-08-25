plugins {
    id("t1dm.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Not `org.json`: the host JVM's is a DIFFERENT implementation from Android's, so a unit test
    // against it would not be testing what ships.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.t1dm.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // androidTest assets so MigrationTestHelper can read the exported schemas.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Bundled for FTS5: the HyperOS/Android 16 system SQLite omits the fts5 module.
    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:${libs.versions.room.get()}")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
