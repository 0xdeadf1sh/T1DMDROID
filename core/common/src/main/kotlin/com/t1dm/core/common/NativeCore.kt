package com.t1dm.core.common

import com.t1dm.core.model.DecodedAdvert

/**
 * Kotlin-facing surface of the Rust `t1dm-core` crate. :app (and every consumer) depends on
 * THIS, never on the uniffi-generated binding directly; :core:native supplies the real impl.
 *
 * The Phase-1 FFI surface is frozen here as Kotlin signatures; the Rust bodies + the uniffi
 * wiring land in :core:native in the next phase. Every function is total on garbage input
 * (`panic = "abort"` in the crate) — a short or CRC-failing advert is `null`, never a panic.
 */
interface NativeCore {
    fun roundtrip(msg: String): String

    /** Decode + CRC-validate a 20-byte LinX advert payload (CGM.md §3.1/§3.2); `null` on a
     *  short or CRC-failing payload. */
    fun decodeAdvert(payload: ByteArray): DecodedAdvert?

    /** The advert CRC32 (CGM.md §3.2), as an unsigned value in the low 32 bits of the Long. */
    fun advertCrc32(payload: ByteArray): Long

    /** Kovatchev BG → risk-space transform `f` (used by the graph unit space + stats axis). */
    fun kovatchevF(mgdl: Double): Double

    /** Inverse Kovatchev transform `f_inv`, risk-space → mg/dL. */
    fun kovatchevFInv(risk: Double): Double
}
