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
    // GameWorld + the dispatcher holder. The screen never names NativeCore: :app hands it a
    // `(TerrainSpec) -> GameWorld` factory, exactly as it hands :feature:dashboard a smoother.
    implementation(project(":core:common"))
    // The BG panel's own render model — GraphFrame / PaintFrame / ChalkPens — which :ui:game's world
    // is cut from, and the tool geometry the paint layer must be drawn with.
    implementation(project(":ui:graph"))
    implementation(project(":ui:game"))

    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    // BackHandler: the back gesture is an exit affordance here, not a pop, so the route has to claim it.
    implementation(libs.androidx.activity.compose)
    // LocalLifecycleOwner. The frame clock alone is not a pause: it stops on window DETACH, not on a
    // paused-but-visible Activity (a dialog, the recents peek, a partially-occluding overlay), and the
    // solver must freeze the instant the screen stops being RESUMED.
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
