import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

extensions.configure<ApplicationExtension> {
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        targetSdk = 36
    }
    // Library and JVM modules are linted through the app, so the release lint pass covers them.
    lint {
        checkDependencies = true
        fatal += "NewApi"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    jvmToolchain(21)
}
