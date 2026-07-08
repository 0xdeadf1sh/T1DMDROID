plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.sensors"
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
