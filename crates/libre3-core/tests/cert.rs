//! PLAN_T1DMDROID.md §12.1/§12.3: the extracted `phone_cert_162b.bin` must parse, be the
//! `03 03` family, and carry a valid P-256 point; sensor-cert fixtures verify against the
//! Abbott patch-signing keys. Real cert fixtures come from the out-of-band table dir.

use libre3_core::cert;

fn tables_dir() -> Option<std::path::PathBuf> {
    std::env::var("LIBRE3_TABLES_DIR").ok().map(std::path::PathBuf::from)
}

#[test]
fn bundled_162b_is_the_pairing_family() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let c = cert::PhoneCert::bundled_162b(&dir).expect("phone_cert_162b.bin");
    assert_eq!(c.raw.len(), 162);
    assert_eq!(&c.raw[..2], &[0x03, 0x03]);
    assert_eq!(c.static_pub.len(), 65);
    assert_eq!(c.static_pub[0], 0x04);
    // The `03 03` family carries the index-1 static scalar window override.
    let w = c.phase5_static_scalar_window_override().expect("03 03 override");
    assert_eq!(w.len(), 70);
}

#[test]
fn firstpair_cert_has_no_static_override() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let raw = std::fs::read(dir.join("phone_cert_firstpair.bin")).expect("firstpair cert");
    let c = cert::PhoneCert::parse(&raw).expect("parse");
    assert!(c.phase5_static_scalar_window_override().is_none(), "03 00 has no override");
}

/// The static scalar window is exactly the documented prefix + 38 zeros.
#[test]
fn static_scalar_window_prefix() {
    let w = cert::first_pair_static_scalar_window_index1();
    assert_eq!(w.len(), 70);
    assert_eq!(w[..4], [0x97, 0x8d, 0x11, 0xed]);
    assert!(w[32..].iter().all(|b| *b == 0));
}

/// A 140-byte blob with a non-point pubkey byte is refused at parse time.
#[test]
fn sensor_cert_parse_rejects_non_point() {
    let mut raw = vec![0u8; 140];
    raw[11] = 0x02; // not 0x04
    assert!(cert::SensorCert::parse(&raw).is_err());
    assert!(cert::SensorCert::parse(&vec![0u8; 139]).is_err());
}

/// A synthetic cert with a zero signature fails signature decoding (r or s is zero).
#[test]
fn sensor_cert_garbage_signature_fails() {
    let mut raw = vec![0u8; 140];
    raw[11] = 0x04;
    let c = cert::SensorCert::parse(&raw).unwrap();
    // All-zero r||s is rejected by the signature decoder, not merely unverifiable.
    assert!(c.verify_ecdsa(&cert::PATCH_SIGNING_KEYS[0].to_vec()).is_err());
    assert!(c.verified_signing_key_index().is_err());
}