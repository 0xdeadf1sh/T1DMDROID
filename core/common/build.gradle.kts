plugins {
    id("t1dm.jvm.library")
}

// The Rust core's own golden fixture, on the host test classpath: KovatchevScaleTest reads it.
sourceSets["test"].resources {
    srcDir(rootProject.file("crates/t1dm-core/src/testdata"))
    include("golden.json")
}

dependencies {
    // NativeCore references DecodedAdvert; downstream impls need it too.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
