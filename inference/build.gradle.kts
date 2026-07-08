plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.inference"
}

dependencies {
    implementation(project(":core:native"))
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
