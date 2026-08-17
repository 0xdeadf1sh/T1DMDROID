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
    // LocalLifecycleOwner. The osmdroid MapView holds a tile thread and a disk cache, and neither the
    // frame clock nor window detach is a pause for it — it has to be driven from the Activity's state.
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
