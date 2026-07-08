//! uniffi binding generator entry point. Invoked in *library mode* against the built
//! `libt1dm_core` cdylib to emit the Kotlin bindings — the crate uses proc-macro
//! scaffolding (no UDL), so library mode is the supported path. See the
//! `generateUniffiBindings` task in core/native/build.gradle.kts.
fn main() {
    uniffi::uniffi_bindgen_main()
}
