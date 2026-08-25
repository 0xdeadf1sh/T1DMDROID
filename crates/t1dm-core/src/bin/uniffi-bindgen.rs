//! Run in library mode against the built `libt1dm_core` cdylib: the crate is proc-macro
//! scaffolded, with no UDL. Driven by `generateUniffiBindings` in core/native/build.gradle.kts.
fn main() {
    uniffi::uniffi_bindgen_main()
}
