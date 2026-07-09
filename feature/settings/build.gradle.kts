plugins {
    id("t1dm.android.library")
    id("t1dm.android.compose")
}

android {
    namespace = "com.t1dm.feature.settings"
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":ui:graph"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.activity.compose)

    // QR token scan (item 12): ZXing embedded, Apache-2.0, no Play Services. Provides ScanContract
    // + a self-contained CaptureActivity that requests CAMERA at runtime.
    implementation(libs.zxing.android.embedded)
}
