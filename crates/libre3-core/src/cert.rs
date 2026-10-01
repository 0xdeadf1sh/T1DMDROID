//! Phone and sensor certificates (PLAN_T1DMDROID.md §5.3), ported from the kit.
//!
//! Phone cert — 162 bytes: `03 03`-family pairs live fresh sensors (the `03 00` variant does
//! not). It is Juggluco's universal `LIBRE3_APP_CERTIFICATES_B[1]`, a static public artifact
//! identical on every install. Delivered out of band with the runtime tables (§9).
//!
//! Sensor cert — 140 bytes: `header11 || staticPub65 || sig64`. ECDSA-P256/SHA-256, raw r||s,
//! over `raw[0..76]`, verified against the Abbott patch-signing public keys.

use crate::CryptoError;

// MARK: - Phone certificate

pub const PHONE_CERT_SIZE: usize = 162;
const PHONE_CERT_PUBKEY_RANGE: (usize, usize) = (33, 98);

pub struct PhoneCert {
    pub raw: Vec<u8>,
    /// 65-byte uncompressed P-256 point (with 0x04 prefix).
    pub static_pub: Vec<u8>,
}

impl PhoneCert {
    pub fn parse(raw: &[u8]) -> Result<PhoneCert, CryptoError> {
        if raw.len() != PHONE_CERT_SIZE {
            return Err(CryptoError::LibAes {
                reason: format!("phone cert must be {PHONE_CERT_SIZE} bytes, got {}", raw.len()),
            });
        }
        let (a, b) = PHONE_CERT_PUBKEY_RANGE;
        let public_key = &raw[a..b];
        if public_key.first() != Some(&0x04) {
            return Err(CryptoError::LibAes {
                reason: "phone cert pubkey is not an uncompressed point".into(),
            });
        }
        Ok(PhoneCert {
            raw: raw.to_vec(),
            static_pub: public_key.to_vec(),
        })
    }

    /// First-pair Phase 5 static-scalar policy keyed on the cert family: `03 03` uses the
    /// index-1 native scalar window; `03 00` uses the entry-source-derived scalar (None).
    pub fn phase5_static_scalar_window_override(&self) -> Option<Vec<u8>> {
        if self.raw.first() == Some(&0x03) && self.raw.get(1) == Some(&0x03) {
            Some(first_pair_static_scalar_window_index1())
        } else {
            None
        }
    }

    /// Loads `phone_cert_162b.bin` from the out-of-band table dir.
    pub fn bundled_162b(dir: &std::path::Path) -> Result<PhoneCert, CryptoError> {
        let raw = std::fs::read(dir.join("phone_cert_162b.bin"))
            .map_err(|_| CryptoError::TablesMissing { name: "phone_cert_162b".into() })?;
        PhoneCert::parse(&raw)
    }
}

/// Native `0x6388f0` row-59 static scalar window for the Abbott cert-private index 1
/// (`03 03` cert family): the 32-byte prefix, then 38 zero bytes.
pub fn first_pair_static_scalar_window_index1() -> Vec<u8> {
    let prefix: [u8; 32] = [
        0x97, 0x8d, 0x11, 0xed, 0x64, 0x6e, 0xe3, 0x55, 0x93, 0x36, 0xd5, 0xfe, 0xba, 0x58, 0x7c,
        0xe9, 0x84, 0x12, 0x31, 0x98, 0xcd, 0x9e, 0x88, 0x0d, 0x34, 0xba, 0xd0, 0xfa, 0xc8, 0xa9,
        0x97, 0xbf,
    ];
    let mut out = Vec::with_capacity(70);
    out.extend_from_slice(&prefix);
    out.extend(std::iter::repeat(0u8).take(38));
    out
}

// MARK: - Sensor certificate

pub const SENSOR_CERT_SIZE: usize = 140;
const SIGNED_PAYLOAD_RANGE: (usize, usize) = (0, 76);
const SENSOR_CERT_PUBKEY_RANGE: (usize, usize) = (11, 76);
const SIGNATURE_RANGE: (usize, usize) = (76, 140);

/// Abbott patch-signing public keys (public verifier material, not private).
/// Family 0 and the family observed on fresh-pair cert fixtures.
pub const PATCH_SIGNING_KEYS: [&[u8; 65]; 2] = [&LEVEL0, &LEVEL1];

const LEVEL0: [u8; 65] = [
    0x04, 0xb6, 0x9d, 0x17, 0x34, 0xf5, 0xe4, 0x25, 0xbc, 0xc0, 0x57, 0x6a, 0xd1, 0xf7, 0x27,
    0xc1, 0x31, 0x1c, 0x90, 0xb6, 0xea, 0x98, 0x6f, 0x00, 0x6e, 0x7e, 0x9f, 0x90, 0x96, 0xf6,
    0xa8, 0x28, 0x4f, 0x12, 0xbf, 0x7d, 0xdf, 0xe1, 0x54, 0xa3, 0xf1, 0xd4, 0x5a, 0x0f, 0x27,
    0x34, 0xec, 0xab, 0xca, 0x6b, 0x9e, 0xb5, 0x6e, 0xe4, 0xec, 0xca, 0x87, 0x85, 0x3a, 0xd8,
    0x53, 0xb6, 0xa6, 0x41, 0x80,
];

const LEVEL1: [u8; 65] = [
    0x04, 0xa2, 0xd8, 0x47, 0x89, 0x90, 0x94, 0x5f, 0x70, 0xa9, 0x57, 0x0a, 0xde, 0x07, 0xb1,
    0x55, 0xbc, 0x90, 0x4d, 0x2d, 0x38, 0x06, 0x47, 0x58, 0x7b, 0x12, 0x39, 0x17, 0x01, 0x30,
    0x9b, 0xd1, 0x0b, 0x59, 0x90, 0xc4, 0xc4, 0x7c, 0x47, 0xf1, 0xf0, 0x80, 0x46, 0xcb, 0x6f,
    0x2d, 0xe0, 0x74, 0x8d, 0x1f, 0xa7, 0xf7, 0x37, 0x90, 0xec, 0x9d, 0x8d, 0xd6, 0x37, 0x21,
    0x27, 0x78, 0x52, 0x88, 0x38,
];

pub struct SensorCert {
    pub raw: Vec<u8>,
    /// 65-byte uncompressed P-256 point (with 0x04 prefix).
    pub static_pub: Vec<u8>,
    /// 64-byte raw r||s.
    pub signature: Vec<u8>,
}

impl SensorCert {
    pub fn parse(raw: &[u8]) -> Result<SensorCert, CryptoError> {
        if raw.len() != SENSOR_CERT_SIZE {
            return Err(CryptoError::LibAes {
                reason: format!("sensor cert must be {SENSOR_CERT_SIZE} bytes, got {}", raw.len()),
            });
        }
        let (a, b) = SENSOR_CERT_PUBKEY_RANGE;
        if raw[a] != 0x04 {
            return Err(CryptoError::LibAes {
                reason: "sensor cert pubkey is not an uncompressed point".into(),
            });
        }
        let (s, e) = SIGNATURE_RANGE;
        Ok(SensorCert {
            raw: raw.to_vec(),
            static_pub: raw[a..b].to_vec(),
            signature: raw[s..e].to_vec(),
        })
    }

    pub fn header(&self) -> &[u8] {
        &self.raw[..11]
    }

    pub fn signed_payload(&self) -> &[u8] {
        let (a, b) = SIGNED_PAYLOAD_RANGE;
        &self.raw[a..b]
    }

    /// ECDSA-P256/SHA-256 over `raw[0..76]`, raw `r||s`, against one signing key.
    pub fn verify_ecdsa(&self, signing_public_key: &[u8]) -> Result<bool, CryptoError> {
        if signing_public_key.len() != 65 || signing_public_key[0] != 0x04 {
            return Err(CryptoError::LibAes {
                reason: "signing key must be a 65-byte uncompressed point".into(),
            });
        }
        let verify_key = p256::ecdsa::VerifyingKey::from_sec1_bytes(signing_public_key)
            .map_err(|_| CryptoError::LibAes {
                reason: "signing key is not a valid P-256 point".into(),
            })?;
        use p256::ecdsa::signature::Verifier;
        let sig = p256::ecdsa::Signature::from_slice(&self.signature)
            .map_err(|_| CryptoError::LibAes {
                reason: "signature is not raw r||s".into(),
            })?;
        Ok(verify_key.verify(self.signed_payload(), &sig).is_ok())
    }

    /// Index of the patch-signing key this cert verifies under, if any.
    pub fn verified_signing_key_index(&self) -> Result<Option<usize>, CryptoError> {
        for (index, key) in PATCH_SIGNING_KEYS.iter().enumerate() {
            if self.verify_ecdsa(*key)? {
                return Ok(Some(index));
            }
        }
        Ok(None)
    }
}