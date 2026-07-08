plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.calc"
}

dependencies {
    implementation(project(":inference"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    // Host JVM unit tests: the fail-closed rail-invariant property tests + objective/calculator tests
    // run against a deterministic fake ForecastPort (no .pte on the host; StubNativeCore leaves the
    // model pre/post as TODO), so the safety logic is exercised without a model.
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
