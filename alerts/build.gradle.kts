plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.alerts"
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
