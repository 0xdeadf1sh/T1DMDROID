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
    // `api`: the OkHttp client is an injectable default on the client/stream constructors, so it
    // crosses the seam into :app's DI (which builds them). Downstream never touches it directly.
    api(libs.okhttp)

    // Room entity/DAO types cross the seam (outbox rows, sample rows); the DB itself is built in :app.
    implementation(libs.androidx.room.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
