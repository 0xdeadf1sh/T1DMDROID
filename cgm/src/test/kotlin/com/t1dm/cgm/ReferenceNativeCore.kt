package com.t1dm.cgm

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.DecodedAdvert

/**
 * Test-only [NativeCore] backed by [AidexCodec] — a stand-in for the Rust `t1dm-core` whose advert
 * decode is wired in a later phase. Lets the :cgm pipeline be driven bit-faithfully against the
 * CGM.md golden vectors without the native library.
 */
class ReferenceNativeCore : NativeCore {
    override fun roundtrip(msg: String): String = msg
    override fun decodeAdvert(payload: ByteArray): DecodedAdvert? = AidexCodec.decode(payload)
    override fun advertCrc32(payload: ByteArray): Long = AidexCodec.crcOf(payload)
    override fun kovatchevF(mgdl: Double): Double = 0.0
    override fun kovatchevFInv(risk: Double): Double = 0.0
}
