//! Release panic="abort": every #[uniffi::export] fn returns Result or is total, never panics.

uniffi::setup_scaffolding!();

/// INFERENCE.md §§6-8.
mod preproc;
pub use preproc::*;

/// The trunk stays frozen inside the `.pte`; only the head and its adapter are trainable here.
mod head;
pub use head::*;

/// Bit-faithful to `simulator.py`.
mod curve;
pub use curve::*;

mod watch;
pub use watch::*;

mod stats;
pub use stats::*;

/// `SPEC/invariants.md` §6.1-6.3.
mod accuracy;
pub use accuracy::*;

/// SPEC/inference.md §8.4; display-only, nothing that classifies reads a calibrated fan.
mod conformal;
pub use conformal::*;

/// Continuous Glucose-Error Grid Analysis (Kovatchev 2004).
mod cg_ega;

/// The heightfield both minigames stand on; cosmetic only, like them.
mod terrain;
pub use terrain::*;

/// Cosmetic: no reading/dose/alarm depends on it; uniffi Object, one FFI call per frame.
mod game;
pub use game::*;

/// Cosmetic only — the same trace, played as a hole rather than a track.
mod golf;
pub use golf::*;

// PUBLISHED params (Kovatchev 1997, §5); NOT model risk space — never decode outputs with these.
const KOV_CLINICAL_SCALE: f64 = 1.509;
const KOV_CLINICAL_POWER: f64 = 1.084;
const KOV_CLINICAL_OFFSET: f64 = 5.381;
/// Clinical-scale BG bounds (INFERENCE.md §5); the model's own ride the descriptor.
pub(crate) const CLINICAL_BG_CLAMP_MIN: f64 = 20.0;
pub(crate) const CLINICAL_BG_CLAMP_MAX: f64 = 500.0;

/// The 20-byte 0x0059 glucose payload (CGM.md §3.1): 16 data bytes + a trailing LE u32 CRC.
const ADVERT_LEN: usize = 20;
/// CRC and seed input: P[0..15].
const ADVERT_DATA_LEN: usize = 16;
/// MSB-first, no reflect, no final xor (CGM.md §3.2).
const ADVERT_CRC_POLY: u32 = 0x04C1_1DB7;
/// Seed modulus applied to the summed LE words (CGM.md §3.2).
const ADVERT_SEED_MOD: u32 = 0x7F_A777;
/// Glucose is the low 10 bits of the 16-bit bitfield; the valid flag is bit 15.
const GLUCOSE_MASK: u16 = 0x03FF;

/// Maps to Kotlin CoreException; adapter maps Decode to null (decodeAdvert → DecodedAdvert?).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("decode failed: {reason}")]
    Decode { reason: String },
    #[error("internal error: {reason}")]
    Internal { reason: String },
}

/// CGM.md §3.1. uniffi record → Kotlin `com.t1dm.core.model.PrevGlucose`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PrevGlucose {
    pub glucose_mgdl: i32,
    pub valid: bool,
    pub quality: i32,
}

/// CGM.md §3.1/§3.2. `crc32` holds the validated CRC unsigned in the low 32 bits of the i64.
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

#[uniffi::export]
pub fn roundtrip(msg: String) -> Result<String, CoreError> {
    Ok(format!("rust-core echo: {msg}"))
}

#[inline]
fn le32(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

#[inline]
fn le16(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}

/// CGM.md §3.2; <<1 on u32 drops the high bit (shift-overflow only panics out-of-range).
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

/// CGM.md §3.2: seed = four LE words summed mod 0x7FA777; data must be ≥16 bytes, rest ignored.
fn advert_crc(data: &[u8]) -> u32 {
    // WRAPPING u32 (firmware's native type); a wider accumulator diverges once sums pass 2^32.
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

/// CGM.md §3.1/§3.2; payload ≥20 bytes, trailing ignored. Err(Decode) — never panic — maps to null
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

/// CGM.md §3.2 over 16-byte region; unsigned in low 32 bits of i64. Err(Decode), never panic.
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

/// mg/dL → risk, CLINICAL scale (§5); NaN = low bound. Model outputs use their own scale.
#[uniffi::export]
pub fn kovatchev_f(mgdl: f64) -> f64 {
    let g = if mgdl.is_nan() {
        CLINICAL_BG_CLAMP_MIN
    } else {
        mgdl.clamp(CLINICAL_BG_CLAMP_MIN, CLINICAL_BG_CLAMP_MAX)
    };
    KOV_CLINICAL_SCALE * (g.ln().powf(KOV_CLINICAL_POWER) - KOV_CLINICAL_OFFSET)
}

/// risk → mg/dL, inverse of kovatchev_f with §5 guards; not the forecast-decode transform.
#[uniffi::export]
pub fn kovatchev_f_inv(risk: f64) -> f64 {
    let r_lo = kovatchev_f(CLINICAL_BG_CLAMP_MIN); // f(20) ≈ −3.1629
    let r_hi = kovatchev_f(CLINICAL_BG_CLAMP_MAX); // f(500) ≈ +2.8133
    let r = if risk.is_nan() || risk == f64::NEG_INFINITY {
        r_lo
    } else if risk == f64::INFINITY {
        r_hi
    } else {
        risk
    };
    let r = r.clamp(r_lo, r_hi);
    let base = r / KOV_CLINICAL_SCALE + KOV_CLINICAL_OFFSET; // ≥ 0 by the clamp above
    let mgdl = base.powf(1.0 / KOV_CLINICAL_POWER).exp();
    mgdl.clamp(CLINICAL_BG_CLAMP_MIN, CLINICAL_BG_CLAMP_MAX)
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
        // The four LE seed words sum past 2^32; a non-wrapping accumulator gets the wrong CRC.
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

    #[test]
    fn decode_rejects_short_payload() {
        for len in 0..ADVERT_LEN {
            let p = vec![0xAAu8; len];
            assert!(matches!(decode_advert(p), Err(CoreError::Decode { .. })));
        }
    }

    #[test]
    fn decode_rejects_crc_failure() {
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
        // Deterministic xorshift; the invariant under test is that no input panics.
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
                    assert_eq!(d.crc32 as u32, advert_crc(&p));
                }
                Err(CoreError::Decode { .. }) => {}
                Err(e) => panic!("unexpected error variant: {e:?}"),
            }
        }
        for len in 0..40usize {
            let p: Vec<u8> = (0..len).map(|i| (next() as u8) ^ (i as u8)).collect();
            let _ = decode_advert(p);
        }
        // A random 32-bit CRC matches ~1/2^32, so essentially none of 200k should pass.
        assert!(decoded < 8, "improbably many random CRC hits: {decoded}");
    }

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
        let cases = [(-3.1629, 20.0), (-0.2196, 100.0), (0.8792, 180.0)];
        for (r, want) in cases {
            let got = kovatchev_f_inv(r);
            assert!((got - want).abs() < 0.05, "f_inv({r}) = {got}, want {want}");
        }
    }

    #[test]
    fn kovatchev_guards_are_total() {
        for r in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -1e9, 1e9] {
            let g = kovatchev_f_inv(r);
            assert!(
                g.is_finite() && (CLINICAL_BG_CLAMP_MIN..=CLINICAL_BG_CLAMP_MAX).contains(&g),
                "f_inv({r}) = {g}"
            );
        }
        for g in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -50.0, 1e9] {
            let r = kovatchev_f(g);
            assert!(r.is_finite(), "f({g}) = {r}");
        }
    }
}
