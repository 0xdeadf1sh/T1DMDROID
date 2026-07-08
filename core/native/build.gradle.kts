import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("t1dm.android.rust")
}

// ─────────────────────────────────────────────────────────────────────────────────────────
// :core:native hosts the Rust `t1dm-core` crate (workspace at repo root, member at
// crates/t1dm-core) and exposes it to Kotlin through uniffi. Two build steps bridge them:
//
//   1. generateUniffiBindings — HOST cargo. Builds the (non-stripped) host cdylib and runs
//      the crate's `uniffi-bindgen` binary in *library mode* to emit Kotlin into
//      build/generated/uniffi. This is what the Kotlin below compiles against; it needs only
//      a host toolchain (no NDK), so a plain `cargo test` and this generation both work when
//      the Android target/NDK are absent.
//   2. cargoNdkBuild — cross-compiles libt1dm_core.so for arm64-v8a via cargo-ndk into
//      build/generated/jniLibs. GUARDED: it only runs when both an NDK and cargo-ndk are
//      present, so configuring/compiling the module never forces the NDK to exist. Without it
//      the module still compiles; only the on-device load of the .so is unavailable.
// ─────────────────────────────────────────────────────────────────────────────────────────

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
    // The uniffi-generated bindings inline all helpers but load the cdylib through JNA.
    implementation("${libs.jna.get().module}:${libs.jna.get().version}@aar")
}

// Locate an NDK without forcing AGP's `ndkVersion` (which would fail configuration when the
// NDK is absent). Prefers the env, then falls back to $SDK/ndk/<newest>.
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

// Step 1: host bindings. Builds the debug (unstripped — the release profile strips the
// metadata symbols library-mode bindgen reads) host cdylib, then generates Kotlin.
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

// Step 2: the arm64-v8a cdylib for jniLibs. Guarded so the Android target/NDK being absent
// is a skip, not a failure.
val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Cross-build libt1dm_core.so for arm64-v8a into jniLibs via cargo-ndk."
    workingDir = rootProject.projectDir
    // Track the crate sources as inputs so a Rust change actually re-runs the cross-build.
    // Without this, Gradle marked the task up-to-date and repackaged a STALE .so; the Kotlin
    // bindings then referenced symbols the packaged library lacked (see build-gotchas memory).
    inputs.dir(crateDir)
    inputs.file(rootProject.layout.projectDirectory.file("Cargo.lock"))
    outputs.dir(generatedJniLibsDir)
    val ndk = findNdkHome()
    // Host-only machines (no NDK) legitimately SKIP the cross-build — the module still compiles
    // against the host-generated bindings. But a machine that HAS an NDK yet lacks cargo-ndk is
    // misconfigured: fail LOUDLY (a silent skip would package a missing or stale .so).
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
    // cargo-ndk lays out <out>/arm64-v8a/libt1dm_core.so; the 16 KB link args come from
    // .cargo/config.toml at the repo root.
    commandLine(
        "bash", "-c",
        "cargo ndk -t arm64-v8a -o '$out' build --release"
    )
}

tasks.withType<KotlinCompile>().configureEach { dependsOn(generateUniffiBindings) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoNdkBuild) }
