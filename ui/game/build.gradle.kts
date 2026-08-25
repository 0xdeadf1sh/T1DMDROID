plugins {
    id("t1dm.android.library")
    id("t1dm.android.compose")
}

android {
    namespace = "com.t1dm.ui.game"
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    // The world is the BG panel's own render model. Deliberately NOT :data — the host collects the
    // state and passes it down, as :feature:dashboard does for :ui:graph.
    implementation(project(":ui:graph"))

    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
