plugins {
    `kotlin-dsl`
}

group = "com.t1dm.buildlogic"

// No toolchain here: compiles with the launcher JVM. The plugins pin consumers to jvmToolchain(21).

dependencies {
    // On the runtime classpath so the precompiled convention plugins can apply these by id.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
}
