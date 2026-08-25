plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.calc"
}

dependencies {
    implementation(project(":inference"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
}
