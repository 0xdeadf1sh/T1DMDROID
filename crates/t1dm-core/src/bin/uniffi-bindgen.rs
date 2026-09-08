//! Run against the built libt1dm_core cdylib, driven by generateUniffiBindings in build.gradle.kts.
fn main() {
    uniffi::uniffi_bindgen_main()
}
