import java.util.Properties

plugins {
    id("t1dm.android.application")
    id("t1dm.android.compose")
}

// keystore.properties is gitignored, so it is absent on a fresh checkout.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
}

val gitSha: String = runCatching {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().ifBlank { "unknown" }
}.getOrDefault("unknown")

android {
    namespace = "com.t1dm.app"

    defaultConfig {
        applicationId = "com.t1dm.app"
        versionCode = 168
        versionName = "0.67.1"

        // Single target device.
        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "EXECUTORCH_VERSION", "\"1.3.1\"")
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Disclaimer compiled out via a no-op stub source set; the public flavor ships it.
        create("personal") {
            dimension = "distribution"
        }
        create("public") {
            dimension = "distribution"
            applicationIdSuffix = ".pub"
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:native"))
    implementation(project(":core:design"))

    implementation(project(":data"))
    // :data exposes Room via `implementation`; the root names AppDatabase itself.
    implementation(libs.androidx.room.runtime)
    implementation(project(":cgm"))
    implementation(project(":sensors"))
    implementation(project(":inference"))
    implementation(project(":calc"))
    implementation(project(":alerts"))
    implementation(project(":sync"))
    implementation(project(":watch"))

    implementation(project(":feature:dashboard"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:models"))
    implementation(project(":feature:meals"))
    implementation(project(":feature:insulin"))
    implementation(project(":feature:exercise"))
    implementation(project(":feature:security"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:logs"))
    implementation(project(":feature:game"))
    implementation(project(":feature:backup"))
    // Not for drawing: :feature:dashboard exposes :ui:graph via `implementation`, so the root
    // cannot name PredictedClock without this.
    implementation(project(":ui:graph"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // JsonElement only, no codegen: the same JSON implementation on device and on the host JVM,
    // which `org.json`'s unit-test stand-in is not.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
