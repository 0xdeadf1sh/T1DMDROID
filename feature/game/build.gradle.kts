plugins {
    id("t1dm.android.library")
    id("t1dm.android.compose")
}

android {
    namespace = "com.t1dm.feature.game"
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    // GameWorld + the dispatcher holder.
    implementation(project(":core:common"))
    // GraphFrame / PaintFrame / ChalkPens, and the tool geometry the paint layer is drawn with.
    implementation(project(":ui:graph"))
    implementation(project(":ui:game"))

    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    // BackHandler: back is an exit affordance here, not a pop.
    implementation(libs.androidx.activity.compose)
    // LocalLifecycleOwner: the frame clock stops on window detach, not on a paused-but-visible
    // Activity, and the solver must freeze the instant the screen stops being resumed.
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
