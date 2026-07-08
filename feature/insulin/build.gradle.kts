plugins {
    id("t1dm.android.library")
    id("t1dm.android.compose")
}

android {
    namespace = "com.t1dm.feature.insulin"
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":ui:graph")) // reusable draggable-knot CurveEditor / CurvePreview
    implementation(project(":calc")) // the fail-closed bolus/basal advisory engine (AdviceResult / DecisionCard)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
}
