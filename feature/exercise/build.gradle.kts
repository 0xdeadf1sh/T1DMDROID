plugins {
    id("t1dm.android.library")
    id("t1dm.android.compose")
}

android {
    namespace = "com.t1dm.feature.exercise"
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":ui:graph"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.osmdroid.android)
    // LocalLifecycleOwner: the osmdroid MapView's tile thread and disk cache pause on the Activity's
    // state, not on window detach.
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
