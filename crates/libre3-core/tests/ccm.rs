//! PLAN_T1DMDROID.md §12.1: AESCCMTests.swift ported 1:1 — RFC 3610 packet vector #1 and the
//! tamper case, over a software AES-128 block primitive.

use aes::cipher::{BlockEncrypt, KeyInit};
use aes::Aes128;
use libre3_core::{ccm, CryptoError};

fn aes_ecb(key: &[u8; 16]) -> impl Fn(&[u8; 16]) -> Result<[u8; 16], CryptoError> {
    let cipher = Aes128::new_from_slice(key).expect("aes-128 key");
    move |input: &[u8; 16]| {
        let mut block = (*input).into();
        cipher.encrypt_block(&mut block);
        Ok(block.into())
    }
}

/// RFC 3610 Packet Vector #1: 13-byte nonce (L=2), 8-byte AAD, 23-byte plaintext, M=8.
#[test]
fn rfc3610_vector1_round_trip() {
    let key: [u8; 16] = [
        0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xCB, 0xCC, 0xCD, 0xCE,
        0xCF,
    ];
    let nonce = [
        0x00, 0x00, 0x00, 0x03, 0x02, 0x01, 0x00, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5,
    ];
    let aad = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07];
    let pt: [u8; 23] = [
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
        0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E,
    ];
    let expected_ct: [u8; 23] = [
        0x58, 0x8C, 0x97, 0x9A, 0x61, 0xC6, 0x63, 0xD2, 0xF0, 0x66, 0xD0, 0xC2, 0xC0, 0xF9, 0x89,
        0x80, 0x6D, 0x5F, 0x6B, 0x61, 0xDA, 0xC3, 0x84,
    ];
    let expected_tag: [u8; 8] = [0x17, 0xE8, 0xD1, 0x2C, 0xFD, 0xF9, 0x26, 0xE0];

    let aes = aes_ecb(&key);
    let (ct, tag) = ccm::encrypt(&nonce, &pt, &aad, 8, &aes).unwrap();
    assert_eq!(ct, expected_ct);
    assert_eq!(tag, expected_tag);

    let recovered = ccm::decrypt(&nonce, &ct, &tag, &aad, &aes).unwrap();
    assert_eq!(recovered, pt);
}

#[test]
fn tamper_fails_auth() {
    let key = [0u8; 16];
    let nonce = [0u8; 13];
    let aes = aes_ecb(&key);
    let (ct, tag) = ccm::encrypt(&nonce, b"hello world", &[], 8, &aes).unwrap();
    let mut tampered = ct.clone();
    tampered[0] ^= 1;
    assert!(matches!(
        ccm::decrypt(&nonce, &tampered, &tag, &[], &aes),
        Err(CryptoError::MacMismatch)
    ));
}