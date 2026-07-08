package com.t1dm.core.nativecore

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.BuiltContext
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.Forecast
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelDescriptor

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

    // ── Model pre/post pipeline (Phase 2) — real path is [UniffiNativeCore] ──────────

    override fun parseDescriptor(json: String): ModelDescriptor? =
        TODO("Phase 2: native parse_descriptor")

    override fun causalSmooth(series: List<Double>, clampMin: Double?, clampMax: Double?): List<Double> =
        TODO("Phase 2: native causal_smooth")

    override fun normalizeSample(desc: ModelDescriptor, bg: Double, carb: Double, insulin: Double): List<Double> =
        TODO("Phase 2: native normalize_sample")

    override fun denormalizeSample(desc: ModelDescriptor, z: List<Double>): List<Double> =
        TODO("Phase 2: native denormalize_sample")

    override fun buildContext(
        desc: ModelDescriptor,
        bg: List<Double>,
        carb: List<Double>,
        insulin: List<Double>,
        announcedCarb: List<Double>?,
        announcedInsulin: List<Double>?,
    ): BuiltContext = TODO("Phase 2: native build_context")

    override fun assembleDecode(
        desc: ModelDescriptor,
        headRaw: List<Double>,
        lastBg: Double,
        carrySpread: Double,
    ): Forecast = TODO("Phase 2: native assemble_decode")

    override fun forecastDegeneracyCheck(forecast: Forecast): ForecastStatus =
        TODO("Phase 2: native forecast_degeneracy_check")
}
