plugins {
    id("t1dm.android.library")
}

android {
    namespace = "com.t1dm.sync"
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
