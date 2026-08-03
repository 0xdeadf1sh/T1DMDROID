import java.util.Properties

plugins {
    id("t1dm.android.application")
    id("t1dm.android.compose")
}

// Release signing: read a gitignored keystore.properties if present, else fall back to the
// debug keystore so `assembleRelease` always produces an installable APK on a fresh checkout.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
}

// Short git SHA for the About panel's internal build info (Phase 7C). Degrades to "unknown"
// off a git checkout so a fresh export still builds.
val gitSha: String = runCatching {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().ifBlank { "unknown" }
}.getOrDefault("unknown")

android {
    namespace = "com.t1dm.app"

    defaultConfig {
        applicationId = "com.t1dm.app"
        versionCode = 65
        versionName = "0.22.1"

        // arm64-v8a only (single target device). Harmless until native .so libs ship.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Vulkan capability-probe JNI shim (issue 20 — STEP 5). arm64-only C++17, links the NDK
        // Vulkan loader; enumerates the GPU without running the model.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        // About-panel build provenance (public-safe).
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "EXECUTORCH_VERSION", "\"1.3.1\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Personal build: the medical disclaimer is compiled out (no-op stub source set).
        create("personal") {
            dimension = "distribution"
        }
        // Public build: ships the real disclaimer composable.
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
    // :data exposes Room via `implementation`; the composition root builds the DB, so it needs
    // room-runtime on its own classpath to name AppDatabase/RoomDatabase.
    implementation(libs.androidx.room.runtime)
    implementation(project(":cgm"))
    implementation(project(":sensors"))
    implementation(project(":inference"))
    implementation(project(":calc"))
    implementation(project(":alerts"))
    implementation(project(":sync"))
    implementation(project(":watch"))

    implementation(project(":feature:dashboard"))
    implementation(project(":feature:pubs"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:models"))
    implementation(project(":feature:hardware"))
    implementation(project(":feature:network"))
    implementation(project(":feature:meals"))
    implementation(project(":feature:insulin"))
    implementation(project(":feature:security"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:logs"))
    implementation(project(":feature:game"))
    implementation(project(":feature:backup"))
    // Not for drawing: the composition root names PredictedClock to hand the panel's own axis
    // clock from the dashboard to drive mode, and :feature:dashboard exposes :ui:graph via
    // `implementation`, so the type is off this classpath otherwise.
    implementation(project(":ui:graph"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // JsonElement only (no @Serializable codegen, so no compiler plugin): the config-backup envelope
    // is parsed with the SAME implementation on device and on the host JVM, which `org.json` — an
    // Android reimplementation whose unit-test stand-in differs — cannot offer. Already in the APK
    // via :sync and :feature:pubs.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Home / lock glance widgets (Phase 7B).
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
