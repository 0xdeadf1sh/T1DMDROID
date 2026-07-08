package com.t1dm.core.model

/** One of the two trailing minute-glucose readings in a LinX advert (CGM.md §3.1). */
data class PrevGlucose(
    val glucoseMgdl: Int,   // bitfield & 0x3FF
    val valid: Boolean,     // bitfield bit15
    val quality: Int,
)

/**
 * The decoded, CRC-validated 20-byte LinX / AiDEX X glucose advertisement (CGM.md §3.1/§3.2).
 * Produced by the Rust `decode_advert`; `null` from that call means a short or CRC-failing
 * payload. This is a pure data carrier — the WARMUP heuristic and grid-stamping live upstream.
 */
data class DecodedAdvert(
    val minFromStart: Int,          // u16, minutes since sensor activation
    val status: Int,                // u8, 0 = normal
    val trendTenthsPerMin: Int,     // i8, rate-of-change in 0.1 mg/dL/min
    val glucoseMgdl: Int,           // bitfield & 0x3FF
    val valid: Boolean,             // bitfield bit15
    val quality: Int,               // u8
    val prev: List<PrevGlucose>,    // minute-1, minute-2
    val crc32: Long,                // the validated CRC32, unsigned value in the low 32 bits
)
