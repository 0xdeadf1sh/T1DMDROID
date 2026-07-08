package com.t1dm.core.common

/**
 * Kotlin-facing surface of the Rust `t1dm-core` crate. :app depends on THIS, never on the
 * uniffi-generated binding directly; the Native agent supplies the real impl in :core:native.
 */
interface NativeCore {
    fun roundtrip(msg: String): String
}
