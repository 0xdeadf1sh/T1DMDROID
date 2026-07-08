package com.t1dm.core.nativecore

import com.t1dm.core.common.NativeCore

/**
 * TEMPORARY pure-Kotlin stand-in so Phase 0 runs end-to-end while the Rust cross-build is
 * blocked (no NDK / no aarch64 Rust std). The host crate at core/native/rust/ already proves
 * the same round-trip under `cargo test`.
 *
 * TODO(native-agent): replace with the uniffi-generated binding calling t1dm-core::roundtrip.
 */
class StubNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = "t1dm-core(stub):$msg"
}
