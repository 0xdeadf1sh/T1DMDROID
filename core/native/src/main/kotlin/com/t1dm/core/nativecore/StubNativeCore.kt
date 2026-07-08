package com.t1dm.core.nativecore

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.DecodedAdvert

/**
 * TEMPORARY pure-Kotlin stand-in so the app runs end-to-end while the Rust cross-build is
 * blocked (no NDK / no aarch64 Rust std). The host crate already proves `roundtrip` under
 * `cargo test`.
 *
 * TODO(native-agent): replace with the uniffi-generated binding calling t1dm-core.
 */
class StubNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = "t1dm-core(stub):$msg"

    override fun decodeAdvert(payload: ByteArray): DecodedAdvert? =
        TODO("Phase 1: native decode_advert")

    override fun advertCrc32(payload: ByteArray): Long =
        TODO("Phase 1: native advert_crc32")

    override fun kovatchevF(mgdl: Double): Double =
        TODO("Phase 1: native kovatchev_f")

    override fun kovatchevFInv(risk: Double): Double =
        TODO("Phase 1: native kovatchev_f_inv")
}
