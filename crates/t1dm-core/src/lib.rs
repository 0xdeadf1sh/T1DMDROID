//! t1dm-core — the correctness core. Phase 1 lands the cheapest, most safety-adjacent
//! members: the AiDEX X advertisement decode + its bespoke CRC32 (CGM.md §3.1/§3.2)
//! and the Kovatchev risk transform `f`/`f_inv` with clamp guards (INFERENCE.md §5).
//! Model pre/post, the curve engine and watch crypto land in later phases
//! (SPEC.private.md §2.2).
//!
//! Release builds are `panic = "abort"`, so a panic tears down the whole process.
//! The discipline that follows: **every `#[uniffi::export]` fn returns `Result` (or is
//! total) and never panics on hostile input** — a malformed advert is `Err`, not a
//! slice-OOB. `decode_advert`/`advert_crc32` are fuzzed to prove this.

uniffi::setup_scaffolding!();

/// Model pre/post-processing pipeline (savgol, normalize, context build, quantile
/// assemble/decode, degeneracy guard). Phase 2, INFERENCE.md §§6-8.
mod preproc;
pub use preproc::*;

/// The shared curve/PK engine (gamma Ra + Bateman basal + bolus PK, bucketize, IOB/COB,
/// basal tiling). Phase 4, SPEC.private.md §3.3; bit-faithful to `simulator.py`.
mod curve;
pub use curve::*;

/// Watch-link cryptography (X25519 ECDH → HKDF-SHA256 → per-direction AES-128-GCM with a
/// windowed nonce + deterministic SAS). Phase 5, SPEC.private.md §3, [[watch-link]].
mod watch;
pub use watch::*;

/// Advanced glycemic statistics (TIR/TBR/TAR, LBGI/HBGI, MAGE, AGP percentile bands,
/// per-channel) vs a user-configurable target range. Phase 6, SPEC.private.md §"Phase 6".
mod stats;
pub use stats::*;

/// On-device forecast-accuracy aggregator (per-horizon RMSE/MAE/MARD + central-90
/// coverage) over matured prediction↔realization pairs. Phase 7C, SPEC.private.md §"Phase 7".
mod accuracy;
pub use accuracy::*;

/// 2D arcade car physics for the in-app hill-climb minigame, whose terrain IS the glucose
/// trace. Cosmetic only — no §3.6 path, no reading, dose or alarm depends on it. A uniffi
/// Object so a 60 Hz frame loop costs one FFI call per frame.
mod game;
pub use game::*;

// ── Kovatchev risk transform constants (INFERENCE.md §5, §11) ──────────────────────
const KOV_SCALE: f64 = 1.509;
const KOV_POWER: f64 = 1.084;
const KOV_OFFSET: f64 = 5.381;
/// Physical BG bounds; the only two numbers borrowed from the simulator (INFERENCE.md §5).
const BG_CLAMP_MIN: f64 = 20.0;
const BG_CLAMP_MAX: f64 = 500.0;

// ── AiDEX advertisement layout (CGM.md §3.1) ───────────────────────────────────────
/// The 20-byte 0x0059 glucose payload: 16 data bytes + a trailing LE u32 CRC.
const ADVERT_LEN: usize = 20;
/// Bytes the CRC is computed over, and the seed is derived from (P[0..15]).
const ADVERT_DATA_LEN: usize = 16;
/// CRC32 (advertisement) polynomial: MSB-first, no reflect, no final xor (CGM.md §3.2).
const ADVERT_CRC_POLY: u32 = 0x04C1_1DB7;
/// Seed modulus applied to the summed LE words (CGM.md §3.2).
const ADVERT_SEED_MOD: u32 = 0x7F_A777;
/// Glucose occupies the low 10 bits of the 16-bit bitfield; the valid flag is bit 15.
const GLUCOSE_MASK: u16 = 0x03FF;

/// Error surface crossing the FFI. uniffi maps this onto `CoreException` on the Kotlin
/// side, so callers see a typed throwable rather than a process abort. `decode_advert`
/// yields `Decode` on a short or CRC-failing payload; the Kotlin adapter maps that to the
/// contract's `null` (NativeCore.decodeAdvert returns `DecodedAdvert?`).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// A byte payload failed to decode/validate (too short, CRC mismatch, bad framing).
    #[error("decode failed: {reason}")]
    Decode { reason: String },
    /// A catch-all for invariants that should never trip in practice; surfacing it as
    /// `Err` keeps us on the no-panic path even for "impossible" states.
    #[error("internal error: {reason}")]
    Internal { reason: String },
}

/// One of the two trailing minute-glucose readings in a LinX advert (CGM.md §3.1).
/// uniffi record → Kotlin `com.t1dm.core.model.PrevGlucose` (mapped in the adapter).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PrevGlucose {
    pub glucose_mgdl: i32,
    pub valid: bool,
    pub quality: i32,
}

/// The decoded, CRC-validated 20-byte LinX / AiDEX X glucose advertisement
/// (CGM.md §3.1/§3.2). A pure data carrier — the WARMUP heuristic, gating and
/// grid-stamping live upstream in Kotlin. `crc32` holds the validated (== stored ==
/// computed) CRC as an unsigned value in the low 32 bits of the i64.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DecodedAdvert {
    pub min_from_start: i32,
    pub status: i32,
    pub trend_tenths_per_min: i32,
    pub glucose_mgdl: i32,
    pub valid: bool,
    pub quality: i32,
    pub prev: Vec<PrevGlucose>,
    pub crc32: i64,
}

/// Phase-0 liveness probe for the Kotlin↔Rust seam. Echoes its argument through the
/// uniffi boundary so both sides can assert byte-identical behaviour.
#[uniffi::export]
pub fn roundtrip(msg: String) -> Result<String, CoreError> {
    Ok(format!("rust-core echo: {msg}"))
}

// ── AiDEX advertisement decode + CRC (CGM.md §3.1/§3.2) ─────────────────────────────

#[inline]
fn le32(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

#[inline]
fn le16(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}

/// `crc32_normal` (CGM.md §3.2): bit-serial, MSB-first, big-endian bit order, no
/// reflection, no final xor. The `<< 1` on `u32` drops the high bit (wraps) rather than
/// panicking — shift-overflow only panics on an out-of-range shift *amount*, never on
/// value bits shifting out.
fn crc32_normal(buf: &[u8], init: u32) -> u32 {
    let mut crc = init;
    for &b in buf {
        crc ^= (b as u32) << 24;
        for _ in 0..8 {
            crc = if crc & 0x8000_0000 != 0 {
                (crc << 1) ^ ADVERT_CRC_POLY
            } else {
                crc << 1
            };
        }
    }
    crc
}

/// The advertisement CRC32 over the 16-byte data region (CGM.md §3.2): a data-derived
/// seed (sum of the four LE words mod `0x7FA777`) fed into `crc32_normal`. `data` must
/// be at least 16 bytes; only the first 16 are used.
fn advert_crc(data: &[u8]) -> u32 {
    // The seed accumulates in a WRAPPING u32 — the sensor firmware's native uint32.
    // A wider accumulator diverges the moment the four LE words sum past 2^32; CGM.md's
    // published vectors never overflow, so this stayed invisible until live hardware
    // (whose larger minfromstart/quality bytes push the sum over) exercised the wrap.
    let seed = le32(&data[0..4])
        .wrapping_add(le32(&data[4..8]))
        .wrapping_add(le32(&data[8..12]))
        .wrapping_add(le32(&data[12..16]))
        % ADVERT_SEED_MOD;
    crc32_normal(&data[0..ADVERT_DATA_LEN], seed)
}

#[inline]
fn glucose_of(bitfield: u16) -> (i32, bool) {
    ((bitfield & GLUCOSE_MASK) as i32, (bitfield >> 15) & 1 == 1)
}

/// Decode + CRC-validate a LinX / AiDEX X glucose advertisement (CGM.md §3.1/§3.2).
/// `payload` is the 0x0059 manufacturer data (≥20 bytes; the interleaved 5-byte status
/// advert and any trailing bytes are rejected/ignored). Returns `Err(Decode)` — never
/// panics — on a short or CRC-failing payload, which the Kotlin adapter maps to `null`.
#[uniffi::export]
pub fn decode_advert(payload: Vec<u8>) -> Result<DecodedAdvert, CoreError> {
    if payload.len() < ADVERT_LEN {
        return Err(CoreError::Decode {
            reason: format!("short payload: {} bytes (need {ADVERT_LEN})", payload.len()),
        });
    }
    let p = &payload[..ADVERT_LEN];
    let computed = advert_crc(p);
    let stored = le32(&p[16..20]);
    if computed != stored {
        return Err(CoreError::Decode {
            reason: format!("crc mismatch: computed {computed:#010x} stored {stored:#010x}"),
        });
    }

    let (glucose_mgdl, valid) = glucose_of(le16(&p[5..7]));
    let prev = vec![
        {
            let (g, v) = glucose_of(le16(&p[8..10]));
            PrevGlucose { glucose_mgdl: g, valid: v, quality: p[10] as i32 }
        },
        {
            let (g, v) = glucose_of(le16(&p[11..13]));
            PrevGlucose { glucose_mgdl: g, valid: v, quality: p[13] as i32 }
        },
    ];

    Ok(DecodedAdvert {
        min_from_start: le16(&p[0..2]) as i32,
        status: p[2] as i32,
        trend_tenths_per_min: (p[4] as i8) as i32,
        glucose_mgdl,
        valid,
        quality: p[7] as i32,
        prev,
        crc32: stored as i64,
    })
}

/// The advertisement CRC32 over a payload's 16-byte data region (CGM.md §3.2), returned
/// unsigned in the low 32 bits of the i64. `Err(Decode)` — never a panic — if the payload
/// is shorter than the 16 data bytes the CRC is defined over.
#[uniffi::export]
pub fn advert_crc32(payload: Vec<u8>) -> Result<i64, CoreError> {
    if payload.len() < ADVERT_DATA_LEN {
        return Err(CoreError::Decode {
            reason: format!(
                "short payload: {} bytes (need {ADVERT_DATA_LEN} for CRC)",
                payload.len()
            ),
        });
    }
    Ok(advert_crc(&payload) as i64)
}

// ── Kovatchev risk transform (INFERENCE.md §5) ─────────────────────────────────────

/// `f(g) = SCALE·(ln(g)^POWER − OFFSET)`, mg/dL → risk. Total: BG is clamped to the
/// physical `[20, 500]` first (guaranteeing a positive `ln` base and a finite result),
/// matching the model, which only ever applies `f` to causal-smoothed, clamped BG
/// (INFERENCE.md §5). A NaN input is treated as the low bound rather than propagated.
#[uniffi::export]
pub fn kovatchev_f(mgdl: f64) -> f64 {
    let g = if mgdl.is_nan() {
        BG_CLAMP_MIN
    } else {
        mgdl.clamp(BG_CLAMP_MIN, BG_CLAMP_MAX)
    };
    KOV_SCALE * (g.ln().powf(KOV_POWER) - KOV_OFFSET)
}

/// `f_inv(r) = exp((r/SCALE + OFFSET)^(1/POWER))`, risk → mg/dL, with the guards of
/// INFERENCE.md §5: non-finite risk is replaced (NaN/−inf → f(20), +inf → f(500)), the
/// risk input is clamped to `[f(20), f(500)]` (keeping the base ≥ 0, so no complex/NaN
/// and no fp `exp` overflow), and the output is clamped to `[20, 500]` mg/dL.
#[uniffi::export]
pub fn kovatchev_f_inv(risk: f64) -> f64 {
    let r_lo = kovatchev_f(BG_CLAMP_MIN); // f(20) ≈ −3.1629
    let r_hi = kovatchev_f(BG_CLAMP_MAX); // f(500) ≈ +2.8133
    let r = if risk.is_nan() || risk == f64::NEG_INFINITY {
        r_lo
    } else if risk == f64::INFINITY {
        r_hi
    } else {
        risk
    };
    let r = r.clamp(r_lo, r_hi);
    let base = r / KOV_SCALE + KOV_OFFSET; // ≥ 0 by the clamp above
    let mgdl = base.powf(1.0 / KOV_POWER).exp();
    mgdl.clamp(BG_CLAMP_MIN, BG_CLAMP_MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        s.split_whitespace()
            .map(|b| u8::from_str_radix(b, 16).unwrap())
            .collect()
    }

    #[test]
    fn roundtrip_echoes() {
        assert_eq!(roundtrip("x".to_string()).unwrap(), "rust-core echo: x");
        assert_eq!(roundtrip(String::new()).unwrap(), "rust-core echo: ");
    }

    // ── CGM.md §3.1/§3.2 golden vectors ─────────────────────────────────────────────

    #[test]
    fn decode_advert_golden_vector() {
        let p = hex("60 54 01 00 1F 5C 80 3E 54 80 3A 4E 80 36 00 00 7E AE DD 01");
        let d = decode_advert(p).expect("golden advert must decode");
        assert_eq!(d.min_from_start, 21600);
        assert_eq!(d.status, 1);
        assert_eq!(d.trend_tenths_per_min, 31);
        assert_eq!(d.glucose_mgdl, 92);
        assert!(d.valid);
        assert_eq!(d.prev.len(), 2);
        assert_eq!(d.prev[0].glucose_mgdl, 84);
        assert!(d.prev[0].valid);
        assert_eq!(d.prev[1].glucose_mgdl, 78);
        assert!(d.prev[1].valid);
        assert_eq!(d.crc32, 0x01DD_AE7E);
    }

    #[test]
    fn advert_crc32_golden_vector() {
        let p = hex("60 54 01 00 1F 5C 80 3E 54 80 3A 4E 80 36 00 00 7E AE DD 01");
        assert_eq!(advert_crc32(p).unwrap(), 0x01DD_AE7E);
    }

    #[test]
    fn decode_advert_live_sample() {
        // Live sample, serial 22222C74D9: 115 mg/dL, trend +0.8, prev 112/110, minfromstart 1418.
        let p = hex("8a 05 00 00 08 73 80 63 70 80 64 6e 80 63 00 00 a0 7d b6 66");
        let d = decode_advert(p).expect("live sample must decode");
        assert_eq!(d.min_from_start, 1418);
        assert_eq!(d.trend_tenths_per_min, 8);
        assert_eq!(d.glucose_mgdl, 115);
        assert!(d.valid);
        assert_eq!(d.prev[0].glucose_mgdl, 112);
        assert_eq!(d.prev[1].glucose_mgdl, 110);
        assert_eq!(d.crc32, 0x66B6_7DA0);
    }

    #[test]
    fn decode_advert_live_overflow_seed() {
        // Real capture, serial 22222C74D9 (minfromstart 4338): the four LE seed words
        // sum past 2^32, so a non-wrapping accumulator computes the wrong CRC. Locks in
        // the u32-wrapping seed that CGM.md's smaller published vectors never exercise.
        let p = hex("f2 10 00 00 14 ad 80 63 ac 80 64 a8 80 63 00 00 dd fe f8 3f");
        let d = decode_advert(p).expect("live overflow-seed advert must decode");
        assert_eq!(d.min_from_start, 4338);
        assert_eq!(d.glucose_mgdl, 173);
        assert!(d.valid);
        assert_eq!(d.crc32, 0x3FF8_FEDD);
        assert_eq!(
            advert_crc32(hex("f2 10 00 00 14 ad 80 63 ac 80 64 a8 80 63 00 00 dd fe f8 3f")).unwrap(),
            0x3FF8_FEDD
        );
    }

    // ── decode is total on hostile input (never panics, always Err) ─────────────────

    #[test]
    fn decode_rejects_short_payload() {
        for len in 0..ADVERT_LEN {
            let p = vec![0xAAu8; len];
            assert!(matches!(decode_advert(p), Err(CoreError::Decode { .. })));
        }
    }

    #[test]
    fn decode_rejects_crc_failure() {
        // Golden vector with one corrupted CRC byte must fail closed.
        let mut p = hex("60 54 01 00 1F 5C 80 3E 54 80 3A 4E 80 36 00 00 7E AE DD 01");
        p[16] ^= 0xFF;
        assert!(matches!(decode_advert(p), Err(CoreError::Decode { .. })));
    }

    #[test]
    fn advert_crc32_rejects_short_payload() {
        for len in 0..ADVERT_DATA_LEN {
            assert!(advert_crc32(vec![0u8; len]).is_err());
        }
    }

    #[test]
    fn decode_fuzz_never_panics() {
        // Deterministic xorshift stream of 20-byte payloads. Garbage overwhelmingly fails
        // the CRC → Err; the invariant under test is that no input panics (the harness
        // turns any panic into a failure) and that a decoded packet's CRC is self-consistent.
        let mut state: u64 = 0x1234_5678_9ABC_DEF0;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        let mut decoded = 0u32;
        for _ in 0..200_000 {
            let mut p = [0u8; ADVERT_LEN];
            for chunk in p.chunks_mut(8) {
                let r = next().to_le_bytes();
                chunk.copy_from_slice(&r[..chunk.len()]);
            }
            match decode_advert(p.to_vec()) {
                Ok(d) => {
                    decoded += 1;
                    // A successful decode means computed == stored CRC by construction.
                    assert_eq!(d.crc32 as u32, advert_crc(&p));
                }
                Err(CoreError::Decode { .. }) => {}
                Err(e) => panic!("unexpected error variant: {e:?}"),
            }
        }
        // Also feed variable-length garbage, including sub-minimal lengths.
        for len in 0..40usize {
            let p: Vec<u8> = (0..len).map(|i| (next() as u8) ^ (i as u8)).collect();
            let _ = decode_advert(p);
        }
        // Sanity: a random 32-bit CRC matches ~1/2^32, so essentially none of 200k should pass.
        assert!(decoded < 8, "improbably many random CRC hits: {decoded}");
    }

    // ── INFERENCE.md §5 Kovatchev reference values + round-trip ──────────────────────

    #[test]
    fn kovatchev_f_reference_values() {
        let cases = [
            (20.0, -3.1629),
            (70.0, -0.8806),
            (100.0, -0.2196),
            (180.0, 0.8792),
            (400.0, 2.3884),
            (500.0, 2.8133),
        ];
        for (g, want) in cases {
            let got = kovatchev_f(g);
            assert!((got - want).abs() < 1e-3, "f({g}) = {got}, want {want}");
        }
    }

    #[test]
    fn kovatchev_f_inv_round_trips() {
        for g in [20.0, 55.0, 70.0, 100.0, 120.0, 180.0, 250.0, 400.0, 500.0] {
            let back = kovatchev_f_inv(kovatchev_f(g));
            assert!((back - g).abs() < 1e-6, "f_inv(f({g})) = {back}");
        }
    }

    #[test]
    fn kovatchev_f_inv_reference_values() {
        // Inverse of the §5 reference risks lands back on the mg/dL values.
        let cases = [(-3.1629, 20.0), (-0.2196, 100.0), (0.8792, 180.0)];
        for (r, want) in cases {
            let got = kovatchev_f_inv(r);
            assert!((got - want).abs() < 0.05, "f_inv({r}) = {got}, want {want}");
        }
    }

    #[test]
    fn kovatchev_guards_are_total() {
        // Non-finite and out-of-range inputs must clamp, never NaN/inf out.
        for r in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -1e9, 1e9] {
            let g = kovatchev_f_inv(r);
            assert!(g.is_finite() && (BG_CLAMP_MIN..=BG_CLAMP_MAX).contains(&g), "f_inv({r}) = {g}");
        }
        for g in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -50.0, 1e9] {
            let r = kovatchev_f(g);
            assert!(r.is_finite(), "f({g}) = {r}");
        }
    }
}
