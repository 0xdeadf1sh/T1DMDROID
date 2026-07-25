plugins {
    id("t1dm.jvm.library")
}

// The crate's own golden fixture, on the host test classpath: KovatchevScaleTest cross-checks the
// pure-Kotlin KovatchevScale against the very f/f_inv pairs the Rust core is gated on, so the two
// implementations cannot drift apart silently.
sourceSets["test"].resources {
    srcDir(rootProject.file("crates/t1dm-core/src/testdata"))
    include("golden.json")
}

dependencies {
    // Exposed transitively: NativeCore references DecodedAdvert; downstream impls need it too.
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
