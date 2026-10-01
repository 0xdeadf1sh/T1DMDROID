//! PLAN_T1DMDROID.md §12.1: Phase6ResponseTests.swift ported 1:1 — two live Phase 6 captures
//! decrypt to session material under the phase5 block primitive, tampering fails auth.

use libre3_core::challenge::{self, parse_sensor_challenge};
use libre3_core::libaes::{self, LibAESTables};
use libre3_core::CryptoError;

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn tables() -> Option<LibAESTables> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    Some(LibAESTables::from_dir(std::path::Path::new(&dir)).expect("tables"))
}

fn phase5_aes<'t>(
    raw_key: &[u8],
    t: &'t LibAESTables,
) -> impl Fn(&[u8; 16]) -> Result<[u8; 16], CryptoError> + 't {
    let ctx = libaes::key_setup(raw_key, t).expect("key setup");
    move |block: &[u8; 16]| libaes::phase5_block_encrypt(block, &ctx, t)
}

#[test]
fn live_phase6_response_decrypts_to_session_material() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let key = unhex("f84d8c0f6251e81f46ff263343239fb9");
    let live_r1 = unhex("544921efc2c2bcd4a9d0b119d3a923e0");
    let live_r2 = unhex("9db1cc3f06e9d092d6602bdc5c7ad312");
    let wire = unhex(
        "85aa09d24bfcd0cddc7984d10e7451b34595c5\
         ab3947106858d729a947ee2f372a2403d9e728\
         34a82f870f16b415afa032654b1572c848361b\
         ef399535040000a4e148",
    );
    let aes = phase5_aes(&key, &t);
    let material = challenge::phase6_decrypt(&wire, &live_r1, &live_r2, &aes).unwrap();
    assert_eq!(hex(&material.k_enc), "4bbce496a63cc9a435adeeb4f78e1617");
    assert_eq!(hex(&material.iv_enc), "0000000067c72c01");
}

#[test]
fn firstpair_0rkhd_kra8_phase6_response_decrypts() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let raw_key = unhex("21b5cd0e7ab84d60f5d7453a6e2ddf40");
    let sensor_r1 = unhex("d02d410e6ef40e76e36abf7455dcc432");
    let phone_r2 = unhex("6fdddbd344b7379a581a856e31c01d25");
    let wire = unhex(
        "c7abf31874dc02e9f775b8ef83906a35632c99\
         8ca6c34ca81d4410c0a062d18ac0c8859e92a3\
         8c1c7521198de87394b2086b4e458cf7fe8161\
         9540f208000000f38356",
    );
    let aes = phase5_aes(&raw_key, &t);
    let material = challenge::phase6_decrypt(&wire, &sensor_r1, &phone_r2, &aes).unwrap();
    assert_eq!(hex(&material.k_enc), "9f15acd6f2584a911429b45060276617");
    assert_eq!(hex(&material.iv_enc), "00000000faaa2dda");
}

#[test]
fn tampered_phase6_response_fails_auth() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let key = unhex("f84d8c0f6251e81f46ff263343239fb9");
    let live_r1 = unhex("544921efc2c2bcd4a9d0b119d3a923e0");
    let live_r2 = unhex("9db1cc3f06e9d092d6602bdc5c7ad312");
    let mut wire = unhex(
        "85aa09d24bfcd0cddc7984d10e7451b34595c5\
         ab3947106858d729a947ee2f372a2403d9e728\
         34a82f870f16b415afa032654b1572c848361b\
         ef399535040000a4e148",
    );
    wire[0] ^= 0x01;
    let aes = phase5_aes(&key, &t);
    assert!(matches!(
        challenge::phase6_decrypt(&wire, &live_r1, &live_r2, &aes),
        Err(CryptoError::MacMismatch)
    ));
}

/// The 23-byte sensor challenge splits into R1(16) || nonce7(7) (§5.5).
#[test]
fn sensor_challenge_parses() {
    let r1 = (0u8..16).collect::<Vec<u8>>();
    let nonce = (0u8..7).collect::<Vec<u8>>();
    let mut raw = r1.clone();
    raw.extend(&nonce);
    let (parsed_r1, parsed_nonce) = parse_sensor_challenge(&raw).unwrap();
    assert_eq!(parsed_r1, r1);
    assert_eq!(parsed_nonce, nonce);
    assert!(parse_sensor_challenge(&raw[..22]).is_err());
}

/// Phase 5 framing: wire form is the logical form + 14 zero bytes, and decode round-trips.
#[test]
fn phase5_wire_framing() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let key = unhex("f84d8c0f6251e81f46ff263343239fb9");
    let nonce = unhex("35040000a4e148");
    let mut plaintext = unhex("544921efc2c2bcd4a9d0b119d3a923e0");
    plaintext.extend(unhex("9db1cc3f06e9d092d6602bdc5c7ad312"));
    plaintext.extend(vec![0xaa, 0xbb, 0xcc, 0xdd]);
    let aes = phase5_aes(&key, &t);
    let msg = challenge::phase5_encrypt(&plaintext, &nonce, &aes).unwrap();
    assert_eq!(msg.logical_bytes().len(), 40);
    let wire = msg.wire_bytes();
    assert_eq!(wire.len(), 54);
    assert!(wire[40..].iter().all(|b| *b == 0));
    let parsed = challenge::phase5_decode(&wire).unwrap();
    assert_eq!(parsed.ciphertext, msg.ciphertext);
    assert_eq!(parsed.tag, msg.tag);
    let recovered = libre3_core::ccm::decrypt(&nonce, &parsed.ciphertext, &parsed.tag, &[], &aes)
        .unwrap();
    assert_eq!(recovered, plaintext);
}