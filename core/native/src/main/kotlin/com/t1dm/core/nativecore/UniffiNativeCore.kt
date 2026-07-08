package com.t1dm.core.nativecore

import com.t1dm.core.common.NativeCore
import uniffi.t1dm_core.roundtrip as uniffiRoundtrip

/**
 * The real [NativeCore], backed by the uniffi-generated binding into the Rust `t1dm-core`
 * crate. Requires libt1dm_core.so in jniLibs (produced by the `cargoNdkBuild` task); until
 * the NDK cross-build runs, [StubNativeCore] stands in so the app runs on host-only tooling.
 *
 * The generated `roundtrip` throws `CoreException` (a `Result::Err` on the Rust side); the
 * Phase-0 surface never errs, so we let it propagate rather than swallow it.
 */
class UniffiNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = uniffiRoundtrip(msg)
}
