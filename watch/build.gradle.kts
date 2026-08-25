plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.watch"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// A removable seam: nothing depends on :watch, and it reaches out only through ports.
// The real X25519/AES-128-GCM lives in Rust `t1dm-core`; this module ships only a loopback session.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
