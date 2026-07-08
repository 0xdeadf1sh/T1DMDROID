plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.data"
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
