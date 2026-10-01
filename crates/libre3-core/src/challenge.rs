//! Phase 5/6 challenge framing (PLAN_T1DMDROID.md §5.5/§5.6), ported from the kit.
//!
//! Phase 5 phone→sensor wire (54 B): ct36 (`R1 || R2 || tail4`) || tag4 (M=4) || zero pad 14.
//! CCM nonce = the 7-byte trailer of the sensor's 23-byte challenge (`R1_16 || nonce7`).
//!
//! Phase 6 sensor→phone wire (67 B): ct56 (`R2 || R1 || kEnc || ivEnc8`) || tag4 || nonce7.
//! R1/R2 echo verification is mandatory; kEnc/ivEnc are the data-plane keys (§5.7).

use crate::ccm::{self, Block};
use crate::CryptoError;

pub const PHASE5_PLAINTEXT_SIZE: usize = 36;
pub const PHASE5_LOGICAL_SIZE: usize = 40;
pub const PHASE5_WIRE_SIZE: usize = 54;

pub const PHASE6_PLAINTEXT_SIZE: usize = 56;
pub const PHASE6_LOGICAL_SIZE: usize = 60;
pub const PHASE6_WIRE_SIZE: usize = 67;

/// Phase 5 wire message: ciphertext + tag, wire-padded to 54 bytes.
pub struct Phase5Challenge {
    pub ciphertext: Vec<u8>,
    pub tag: Vec<u8>,
}

/// Encrypt `R1 || R2 || tail4` under the phase-5 block primitive.
pub fn phase5_encrypt(
    plaintext: &[u8],
    nonce: &[u8],
    aes: &Block<'_>,
) -> Result<Phase5Challenge, CryptoError> {
    if plaintext.len() != PHASE5_PLAINTEXT_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("phase5 plaintext must be {PHASE5_PLAINTEXT_SIZE} bytes"),
        });
    }
    if nonce.len() != 7 {
        return Err(CryptoError::LibAes {
            reason: format!("ccm nonce must be 7 bytes, got {}", nonce.len()),
        });
    }
    let (ct, tag) = ccm::encrypt(nonce, plaintext, &[], 4, aes)?;
    Ok(Phase5Challenge { ciphertext: ct, tag })
}

impl Phase5Challenge {
    /// 40-byte logical form (ct36 || tag4).
    pub fn logical_bytes(&self) -> Vec<u8> {
        [self.ciphertext.clone(), self.tag.clone()].concat()
    }

    /// 54-byte wire form (logical || 14 zero bytes, so 3 × 18-byte BLE writes line up).
    pub fn wire_bytes(&self) -> Vec<u8> {
        let mut out = self.logical_bytes();
        out.resize(PHASE5_WIRE_SIZE, 0);
        out
    }
}

/// Parse the 54-byte wire payload or the 40-byte logical form.
pub fn phase5_decode(raw: &[u8]) -> Result<Phase5Challenge, CryptoError> {
    if raw.len() != PHASE5_WIRE_SIZE && raw.len() != PHASE5_LOGICAL_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("phase5 wire wants {PHASE5_WIRE_SIZE} or {PHASE5_LOGICAL_SIZE} bytes, got {}", raw.len()),
        });
    }
    Ok(Phase5Challenge {
        ciphertext: raw[..36].to_vec(),
        tag: raw[36..40].to_vec(),
    })
}

/// The session material revealed by a verified Phase 6 response.
pub struct Phase6SessionMaterial {
    /// The phone's R2, echoed back.
    pub phone_r2: Vec<u8>,
    /// The sensor's R1, echoed back.
    pub sensor_r1: Vec<u8>,
    /// 16-byte data-plane key.
    pub k_enc: Vec<u8>,
    /// 8-byte data-plane IV base.
    pub iv_enc: Vec<u8>,
}

/// Verify R1/R2 echo and unpack kEnc/ivEnc (§5.6). Echo mismatch is fail-closed.
pub fn phase6_decrypt(
    raw: &[u8],
    expected_r1: &[u8],
    expected_r2: &[u8],
    aes: &Block<'_>,
) -> Result<Phase6SessionMaterial, CryptoError> {
    if raw.len() != PHASE6_WIRE_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("phase6 wire wants {PHASE6_WIRE_SIZE} bytes, got {}", raw.len()),
        });
    }
    if expected_r1.len() != 16 || expected_r2.len() != 16 {
        return Err(CryptoError::LibAes {
            reason: "R1/R2 must be 16 bytes each".into(),
        });
    }
    let plaintext = ccm::decrypt(&raw[60..67], &raw[..56], &raw[56..60], &[], aes)?;
    if plaintext.len() != PHASE6_PLAINTEXT_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("phase6 plaintext must be {PHASE6_PLAINTEXT_SIZE} bytes"),
        });
    }
    let phone_r2 = &plaintext[..16];
    let sensor_r1 = &plaintext[16..32];
    if sensor_r1 != expected_r1 {
        return Err(CryptoError::Phase6EchoMismatch { field: "R1".into() });
    }
    if phone_r2 != expected_r2 {
        return Err(CryptoError::Phase6EchoMismatch { field: "R2".into() });
    }
    Ok(Phase6SessionMaterial {
        phone_r2: phone_r2.to_vec(),
        sensor_r1: sensor_r1.to_vec(),
        k_enc: plaintext[32..48].to_vec(),
        iv_enc: plaintext[48..56].to_vec(),
    })
}

/// Parse the 23-byte sensor challenge notify: R1(16) || nonce7(7).
pub fn parse_sensor_challenge(raw: &[u8]) -> Result<(Vec<u8>, Vec<u8>), CryptoError> {
    if raw.len() != 23 {
        return Err(CryptoError::LibAes {
            reason: format!("sensor challenge must be 23 bytes, got {}", raw.len()),
        });
    }
    Ok((raw[..16].to_vec(), raw[16..23].to_vec()))
}