package com.t1dm.core.model

/** A trailing minute-glucose reading (CGM.md §3.1). */
data class PrevGlucose(
    val glucoseMgdl: Int,   // bitfield & 0x3FF
    val valid: Boolean,     // bitfield bit15
    val quality: Int,
)

/** CGM.md §3.1/§3.2. Rust `decode_advert` returns null for a short or CRC-failing payload. */
data class DecodedAdvert(
    val minFromStart: Int,          // u16, minutes since sensor activation
    val status: Int,                // u8, 0 = normal
    val trendTenthsPerMin: Int,     // i8, rate-of-change in 0.1 mg/dL/min
    val glucoseMgdl: Int,           // bitfield & 0x3FF
    val valid: Boolean,             // bitfield bit15
    val quality: Int,               // u8
    val prev: List<PrevGlucose>,    // minute-1, minute-2
    val crc32: Long,                // unsigned, in the low 32 bits
)
