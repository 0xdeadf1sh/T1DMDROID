import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("t1dm.android.rust")
}

// One entry per cdylib the app links: package, the debug .so library-mode bindgen reads, bindgen bin.
data class Crate(val pkg: String, val lib: String, val bindgen: String)

val crates = listOf(
    Crate("t1dm-core", "libt1dm_core.so", "uniffi-bindgen"),
    Crate("libre3-core", "liblibre3_core.so", "libre3-uniffi-bindgen"),
)
val crateDirs = crates.map { rootProject.layout.projectDirectory.dir("crates/${it.pkg}") }
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
    description = "Build the host cdylibs and generate uniffi Kotlin bindings (library mode)."
    workingDir = rootProject.projectDir
    crateDirs.forEach { inputs.dir(it) }
    outputs.dir(generatedUniffiDir)
    val out = generatedUniffiDir.get().asFile.absolutePath
    val packages = crates.joinToString(" ") { "-p ${it.pkg}" }
    val bindgen = crates.joinToString(" && ") { (pkg, lib, bindgenBin) ->
        "cargo run -p $pkg --bin $bindgenBin -- generate " +
            "--library target/debug/$lib --language kotlin --out-dir '$out'"
    }
    commandLine("bash", "-c", "cargo build $packages && $bindgen")
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Cross-build the cdylibs for every t1dm.abis entry into jniLibs via cargo-ndk."
    workingDir = rootProject.projectDir
    val abis = providers.gradleProperty("t1dm.abis").get().split(',')
    // Without these inputs Gradle calls the task up-to-date and repackages a stale .so.
    crateDirs.forEach { inputs.dir(it) }
    inputs.file(rootProject.layout.projectDirectory.file("Cargo.lock"))
    inputs.property("abis", abis)
    outputs.dir(generatedJniLibsDir)
    val ndk = findNdkHome()
    // No NDK: a host-only skip, which packages whatever .so generated/jniLibs still holds.
    onlyIf {
        if (ndk == null) {
            logger.warn("cargoNdkBuild SKIPPED — no NDK found; no Android .so will be built (host-only).")
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
    // Writes <out>/<abi>/<lib>; the 16 KB link args live in .cargo/config.toml. The test
    // guard fails the task if cargo-ndk quietly skips a cdylib or an ABI.
    val packages = crates.joinToString(" ") { "-p ${it.pkg}" }
    val targets = abis.joinToString(" ") { "-t $it" }
    val shipped = abis.flatMap { abi -> crates.map { "test -f '$out/$abi/${it.lib}'" } }.joinToString(" && ")
    commandLine(
        "bash", "-c",
        "cargo ndk $targets -o '$out' build --release $packages && $shipped"
    )
}

tasks.withType<KotlinCompile>().configureEach { dependsOn(generateUniffiBindings) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoNdkBuild) }
