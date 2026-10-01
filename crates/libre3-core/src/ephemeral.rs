//! PLAN_T1DMDROID.md §5.3: Phase 3/4 ephemeral P-256 ECDH, ported 1:1 from the kit's
//! EphemeralExchange.swift. The first-pair path never samples a random key: the native
//! scalar window (70-byte LE, from the accepted 633fa8 null-entropy branch) becomes the
//! private key (first 32 bytes reversed = big-endian scalar), and the wire public key is the
//! `process2(5)` fixed-point override. Curve arithmetic comes from the `p256` crate; the
//! hand-rolled VM multiplier in `crate::p256` is a different contract (70-byte LE windows)
//! and is not used here. All fail-closed.

use crate::CryptoError;
use p256::elliptic_curve::sec1::ToEncodedPoint;

/// Kit `EphemeralKeyPair` (EphemeralExchange.swift L19-69).
#[derive(Debug)]
pub struct EphemeralKeyPair {
    pub private_key: p256::SecretKey,
    /// `04 || X || Y` (X9.63 / SEC1 uncompressed, 65 B).
    pub public_key65: Vec<u8>,
}

impl EphemeralKeyPair {
    /// Kit `init(nativeScalarWindowLE:)` (L54-58): private key from the native null-branch
    /// scalar window; the public point is derived from the key.
    pub fn from_native_scalar_window_le(native_scalar_window_le: &[u8]) -> Result<Self, CryptoError> {
        let raw = Self::raw_private_key_representation(native_scalar_window_le)?;
        let private_key = p256::SecretKey::from_slice(&raw)
            .map_err(|_| CryptoError::Slice { reason: "invalidScalarEncoding".to_owned() })?;
        Ok(Self { public_key65: Self::encode65(&private_key), private_key })
    }

    /// Kit `init(nativeScalarWindowLE:publicKey65Override:)` (L39-49): native first-pair
    /// `process2(5)` derives the Phase 3 public point through a separate fixed-point path,
    /// while Phase 5 still needs the accepted null-branch scalar/entropy.
    pub fn from_native_scalar_window_le_with_override(
        native_scalar_window_le: &[u8],
        public_key65_override: &[u8],
    ) -> Result<Self, CryptoError> {
        if public_key65_override.len() != 65 || public_key65_override.first() != Some(&0x04) {
            return Err(CryptoError::Slice { reason: "invalidEncoding".to_owned() });
        }
        let raw = Self::raw_private_key_representation(native_scalar_window_le)?;
        let private_key = p256::SecretKey::from_slice(&raw)
            .map_err(|_| CryptoError::Slice { reason: "invalidScalarEncoding".to_owned() })?;
        Ok(Self { private_key, public_key65: public_key65_override.to_vec() })
    }

    /// Kit `publicKey65(nativeScalarWindowLE:)` (L60-62).
    pub fn public_key65(native_scalar_window_le: &[u8]) -> Result<Vec<u8>, CryptoError> {
        Ok(Self::from_native_scalar_window_le(native_scalar_window_le)?.public_key65)
    }

    /// Kit `rawPrivateKeyRepresentation` (L64-69): 32-byte big-endian scalar from the
    /// little-endian 70-byte native window.
    fn raw_private_key_representation(native_scalar_window_le: &[u8]) -> Result<Vec<u8>, CryptoError> {
        if native_scalar_window_le.len() < 32 {
            return Err(CryptoError::Slice {
                reason: format!("invalidScalarWindowLength({})", native_scalar_window_le.len()),
            });
        }
        let mut raw = native_scalar_window_le[..32].to_vec();
        raw.reverse();
        Ok(raw)
    }

    fn encode65(private_key: &p256::SecretKey) -> Vec<u8> {
        private_key.public_key().to_encoded_point(false).as_bytes().to_vec()
    }
}

/// Kit `EphemeralExchange` (EphemeralExchange.swift L72-90).
pub mod exchange {
    use super::CryptoError;

    /// Kit `parsePeerPubkey`: parse a 65-byte uncompressed P-256 point.
    pub fn parse_peer_pubkey(raw65: &[u8]) -> Result<p256::PublicKey, CryptoError> {
        if raw65.len() != 65 || raw65.first() != Some(&0x04) {
            return Err(CryptoError::Slice { reason: "invalidEncoding".to_owned() });
        }
        p256::PublicKey::from_sec1_bytes(raw65)
            .map_err(|_| CryptoError::Slice { reason: "invalidEncoding".to_owned() })
    }

    /// Kit `sharedSecret`: ECDH as the raw 32-byte X9.63 shared secret (the x-coordinate
    /// of the scalar product — CryptoKit `sharedSecretFromKeyAgreement` semantics).
    pub fn shared_secret(
        private_key: &p256::SecretKey,
        peer: &p256::PublicKey,
    ) -> Result<Vec<u8>, CryptoError> {
        let secret = p256::ecdh::diffie_hellman(
            private_key.to_nonzero_scalar(),
            peer.as_affine(),
        );
        Ok(secret.raw_secret_bytes().to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Kit SessionKeyTests `testFirstPairIndex1StaticScalarWindowMatchesHarnessTrace` couples
    /// the static window; here the round-trip encoding is pinned instead: a known-good
    /// window must produce a parseable point, and the X9.63 encoding is 65 bytes.
    #[test]
    fn native_window_yields_65_byte_point() {
        let mut window = vec![0u8; 70];
        window[0] = 0x01; // scalar 1 LE
        let pair = EphemeralKeyPair::from_native_scalar_window_le(&window)
            .expect("scalar 1 is a valid P-256 scalar");
        assert_eq!(pair.public_key65.len(), 65);
        assert_eq!(pair.public_key65.first(), Some(&0x04));
    }

    #[test]
    fn short_window_fails_closed() {
        let err = EphemeralKeyPair::from_native_scalar_window_le(&[0u8; 31]).unwrap_err();
        assert_eq!(err.to_string(), "first-pair slice: invalidScalarWindowLength(31)");
    }

    #[test]
    fn override_must_be_65_byte_uncompressed() {
        let mut window = vec![0u8; 70];
        window[0] = 0x01;
        assert_eq!(
            EphemeralKeyPair::from_native_scalar_window_le_with_override(&window, &[0x05]).unwrap_err().to_string(),
            "first-pair slice: invalidEncoding"
        );
        let good = EphemeralKeyPair::from_native_scalar_window_le(&window).unwrap();
        let overridden = EphemeralKeyPair::from_native_scalar_window_le_with_override(
            &window,
            &good.public_key65,
        )
        .unwrap();
        assert_eq!(overridden.public_key65, good.public_key65);
    }
}