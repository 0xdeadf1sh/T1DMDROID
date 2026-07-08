package com.t1dm.core.nativecore

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.PrevGlucose
import uniffi.t1dm_core.CoreException
import uniffi.t1dm_core.advertCrc32 as uniffiAdvertCrc32
import uniffi.t1dm_core.decodeAdvert as uniffiDecodeAdvert
import uniffi.t1dm_core.kovatchevF as uniffiKovatchevF
import uniffi.t1dm_core.kovatchevFInv as uniffiKovatchevFInv
import uniffi.t1dm_core.roundtrip as uniffiRoundtrip
import uniffi.t1dm_core.DecodedAdvert as UniffiDecodedAdvert

/**
 * The real [NativeCore], backed by the uniffi-generated binding into the Rust `t1dm-core`
 * crate. Requires libt1dm_core.so in jniLibs (produced by the `cargoNdkBuild` task); until
 * the NDK cross-build runs, [StubNativeCore] stands in so the app runs on host-only tooling.
 *
 * The Rust `decode_advert` returns `Result`, so a short or CRC-failing payload surfaces as
 * `CoreException.Decode`; we map that to the frozen contract's `null` here. The uniffi
 * record types live under `uniffi.t1dm_core`; [toModel] projects them onto the `:core:model`
 * data classes that every downstream consumer speaks.
 */
class UniffiNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = uniffiRoundtrip(msg)

    override fun decodeAdvert(payload: ByteArray): DecodedAdvert? =
        try {
            uniffiDecodeAdvert(payload).toModel()
        } catch (_: CoreException) {
            null
        }

    override fun advertCrc32(payload: ByteArray): Long = uniffiAdvertCrc32(payload)

    override fun kovatchevF(mgdl: Double): Double = uniffiKovatchevF(mgdl)

    override fun kovatchevFInv(risk: Double): Double = uniffiKovatchevFInv(risk)
}

private fun UniffiDecodedAdvert.toModel(): DecodedAdvert = DecodedAdvert(
    minFromStart = minFromStart,
    status = status,
    trendTenthsPerMin = trendTenthsPerMin,
    glucoseMgdl = glucoseMgdl,
    valid = valid,
    quality = quality,
    prev = prev.map { PrevGlucose(glucoseMgdl = it.glucoseMgdl, valid = it.valid, quality = it.quality) },
    crc32 = crc32,
)
