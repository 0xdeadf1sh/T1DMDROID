plugins {
    id("t1dm.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.t1dm.sync"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    // `api`: an injectable default on the client/stream constructors, so it crosses into :app's DI.
    api(libs.okhttp)

    // Room entity/DAO types cross the seam; the DB itself is built in :app.
    implementation(libs.androidx.room.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
