plugins {
    id("t1dm.android.library")
    id("t1dm.android.compose")
}

android {
    namespace = "com.t1dm.core.design"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:native"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
}
