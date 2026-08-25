import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("t1dm.android.rust")
}

val crateDir = rootProject.layout.projectDirectory.dir("crates/t1dm-core")
val generatedUniffiDir = layout.buildDirectory.dir("generated/uniffi")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")

android {
    namespace = "com.t1dm.core.nativecore"
    sourceSets["main"].java.srcDir(generatedUniffiDir)
    sourceSets["main"].jniLibs.srcDir(generatedJniLibsDir)
}

dependencies {
    api(project(":core:common"))
    // The generated bindings inline every helper but load the cdylib through JNA.
    implementation("${libs.jna.get().module}:${libs.jna.get().version}@aar")
}

// Not AGP's `ndkVersion`, which would fail configuration when the NDK is absent.
fun findNdkHome(): String? {
    System.getenv("ANDROID_NDK_HOME")?.let { if (file(it).exists()) return it }
    System.getenv("ANDROID_NDK_ROOT")?.let { if (file(it).exists()) return it }
    val sdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: file("${rootProject.projectDir}/local.properties")
            .takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("=")
    val ndkRoot = sdk?.let { file("$it/ndk") } ?: return null
    return ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
}

fun onPath(exe: String): Boolean =
    (System.getenv("PATH") ?: "").split(File.pathSeparator).any { file("$it/$exe").canExecute() }

// Debug, not release: release strips the metadata symbols library-mode bindgen reads.
val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    group = "rust"
    description = "Build the host cdylib and generate uniffi Kotlin bindings (library mode)."
    workingDir = rootProject.projectDir
    inputs.dir(crateDir)
    outputs.dir(generatedUniffiDir)
    val out = generatedUniffiDir.get().asFile.absolutePath
    commandLine(
        "bash", "-c",
        "cargo build -p t1dm-core && " +
            "cargo run -p t1dm-core --bin uniffi-bindgen -- generate " +
            "--library target/debug/libt1dm_core.so --language kotlin --out-dir '$out'"
    )
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Cross-build libt1dm_core.so for arm64-v8a into jniLibs via cargo-ndk."
    workingDir = rootProject.projectDir
    // Without these inputs Gradle calls the task up-to-date and repackages a stale .so.
    inputs.dir(crateDir)
    inputs.file(rootProject.layout.projectDirectory.file("Cargo.lock"))
    outputs.dir(generatedJniLibsDir)
    val ndk = findNdkHome()
    // No NDK is a legitimate host-only skip; an NDK without cargo-ndk is misconfigured, so it throws.
    onlyIf {
        if (ndk == null) {
            logger.warn("cargoNdkBuild SKIPPED — no NDK found; the arm64 .so will not be built (host-only).")
            false
        } else {
            true
        }
    }
    doFirst {
        if (!onPath("cargo-ndk")) throw GradleException(
            "cargoNdkBuild needs cargo-ndk on PATH (NDK found at $ndk). Install it with " +
                "`cargo install cargo-ndk` and ensure ~/.cargo/bin is on PATH."
        )
    }
    if (ndk != null) environment("ANDROID_NDK_HOME", ndk)
    val out = generatedJniLibsDir.get().asFile.absolutePath
    // Writes <out>/arm64-v8a/libt1dm_core.so; the 16 KB link args live in .cargo/config.toml.
    commandLine(
        "bash", "-c",
        "cargo ndk -t arm64-v8a -o '$out' build --release"
    )
}

tasks.withType<KotlinCompile>().configureEach { dependsOn(generateUniffiBindings) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoNdkBuild) }
