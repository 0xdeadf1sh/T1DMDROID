plugins {
    id("t1dm.jvm.library")
}

dependencies {
    // Exposed transitively: NativeCore references DecodedAdvert; downstream impls need it too.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
