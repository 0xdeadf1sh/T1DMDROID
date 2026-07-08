plugins {
    `kotlin-dsl`
}

group = "com.t1dm.buildlogic"

// No explicit toolchain: build-logic compiles the convention plugins with the launcher JVM
// (the AS JBR / JDK 21 locally). The plugins still pin the *consumer* modules to jvmToolchain(21).

dependencies {
    // On the runtime classpath so the precompiled convention plugins can apply these by id.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
}
