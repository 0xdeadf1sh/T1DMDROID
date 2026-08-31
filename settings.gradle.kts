pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK 21 toolchain when org.gradle.java.home is not already 21.
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
    ":feature:pubs",
    ":feature:stats",
    ":feature:models",
    ":feature:hardware",
    ":feature:network",
    ":feature:meals",
    ":feature:insulin",
    ":feature:exercise",
    ":feature:security",
    ":feature:settings",
    ":feature:logs",
    ":feature:game",
    ":feature:backup",
)

include(":cgm", ":sensors", ":inference", ":calc", ":sync", ":watch", ":alerts")

include(":data", ":core:design", ":core:native", ":core:model", ":core:common")

include(":ui:graph", ":ui:game")
