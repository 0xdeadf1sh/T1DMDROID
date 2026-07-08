plugins {
    id("t1dm.android.library")
    id("t1dm.android.compose")
}

android {
    namespace = "com.t1dm.ui.graph"
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))

    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
