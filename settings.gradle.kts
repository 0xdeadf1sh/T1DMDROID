pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-provision the JDK 21 toolchain if org.gradle.java.home isn't already 21.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "T1DMDROID"

include(":app")

include(
    ":feature:dashboard",
    ":feature:stats",
    ":feature:models",
    ":feature:hardware",
    ":feature:network",
    ":feature:meals",
    ":feature:insulin",
    ":feature:security",
    ":feature:settings",
    ":feature:journal",
)

include(":cgm", ":sensors", ":inference", ":calc", ":sync", ":watch", ":alerts")

include(":data", ":core:design", ":core:native", ":core:model", ":core:common")
