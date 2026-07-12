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
        // Vendored custom ExecuTorch 1.3.1 AAR with EXECUTORCH_BUILD_VULKAN=ON (issue 20). Consumed
        // by :inference behind the `t1dm.vulkan` gradle property; carries BOTH VulkanBackend and the
        // authoritative XnnpackBackend. flatDir carries no POM, so :inference adds the AAR's runtime
        // transitives (fbjni / soloader) explicitly.
        flatDir { dirs("$rootDir/third_party/executorch-vulkan") }
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
    ":feature:security",
    ":feature:settings",
    ":feature:journal",
)

include(":cgm", ":sensors", ":inference", ":calc", ":sync", ":watch", ":alerts")

include(":data", ":core:design", ":core:native", ":core:model", ":core:common")

include(":ui:graph")
