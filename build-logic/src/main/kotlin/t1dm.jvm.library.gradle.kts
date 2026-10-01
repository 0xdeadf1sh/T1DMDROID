import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    // Lets the app's lint check these modules' API calls against its minSdk.
    id("com.android.lint")
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
}
