//! PLAN_T1DMDROID.md §12.1: LibAESTests.swift ported 1:1 — key-setup/block vectors, CCM over
//! libaes, the Phase 5 wire block, and CCM over the 5defec primitive with the live tuple.
//! Tables load from $LIBRE3_TABLES_DIR (delivered out of band, §9); the suite skips itself
//! when the variable is unset so CI still covers the table-free modules.

use libre3_core::libaes::{self, LibAESTables};

fn tables() -> Option<LibAESTables> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    let t = LibAESTables::from_dir(std::path::Path::new(&dir));
    if let Err(e) = &t {
        eprintln!("libaes tables unusable from {dir}: {e:?}");
    }
    Some(t.expect("tables"))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn key_setup_and_block_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let raw_key = unhex("3bb02ee4fdefe1737312a4668e7f8604");
    let ctx = libaes::key_setup(&raw_key, &t).unwrap();
    assert_eq!(ctx.len(), libaes::CONTEXT_SIZE);

    let vectors: [(&str, Vec<u8>, &str); 5] = [
        ("zero block", vec![0u8; 16], "bf7a0358dc26e61faeb3d310b30f0826"),
        (
            "counter block",
            (0u8..16).collect(),
            "d388d17cc37abcfa3354656979c5ac77",
        ),
        (
            "apr26 R1",
            unhex("db94448c6abde8bc183df11cf5cf197f"),
            "ef66b86917499e796a66a9edf2b6f8d2",
        ),
        (
            "ff block",
            vec![0xff; 16],
            "a87e6d65ec16f3669541ccebbb585758",
        ),
        (
            "10..1f block",
            (0x10u8..0x20).collect(),
            "2c534187108ce4af212a12cfc4cf52fb",
        ),
    ];
    for (name, plaintext, expected) in vectors {
        let actual = libaes::block_encrypt(&plaintext, &ctx, &t).unwrap();
        assert_eq!(hex(&actual), expected, "{name} diverged from Python lib_aes");
    }
}

#[test]
fn ccm_over_libaes() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let raw_key = unhex("3bb02ee4fdefe1737312a4668e7f8604");
    let ctx = libaes::key_setup(&raw_key, &t).unwrap();
    let aes = |block: &[u8; 16]| {
        Ok::<[u8; 16], libre3_core::CryptoError>(
            libaes::block_encrypt(block, &ctx, &t).expect("libaes block"),
        )
    };
    let nonce = unhex("010000007500dc");
    let plaintext: Vec<u8> = (0u8..36).collect();

    let (ct, tag) = libre3_core::ccm::encrypt(&nonce, &plaintext, &[], 8, &aes).unwrap();
    assert_eq!(
        hex(&[ct.clone(), tag.clone()].concat()),
        "2be74afb317295bdc5eb664a38780ea660e8660fdb743dd487591762ee7d115657918fc4294fa2abd83c575a"
    );
    let recovered = libre3_core::ccm::decrypt(&nonce, &ct, &tag, &[], &aes).unwrap();
    assert_eq!(recovered, plaintext);
}

#[test]
fn phase5_wire_block_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let vectors: [(&str, Vec<u8>, Vec<u8>, &str); 3] = [
        ("zero key zero block", vec![0u8; 16], vec![0u8; 16], "6b9bddb402786cba9adac3304b86028b"),
        (
            "range key range block",
            (0u8..16).collect(),
            (0u8..16).collect(),
            "aa4454e26d649350498357b4ce2596ed",
        ),
        (
            "live 2026-05-06 A1",
            unhex("3b16168843c299ad7fa311ba2440d58a"),
            unhex("07210400008f8c4b0000000000000001"),
            "c4ccfb387363f51bf61df08fc6d39304",
        ),
    ];
    for (name, key, plaintext, expected) in vectors {
        let ctx = libaes::key_setup(&key, &t).unwrap();
        let actual = libaes::phase5_block_encrypt(&plaintext, &ctx, &t).unwrap();
        assert_eq!(hex(&actual), expected, "{name} diverged from Python block_5defec");
    }
}

#[test]
fn phase5_ccm_over_wire_block() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let key = unhex("3b16168843c299ad7fa311ba2440d58a");
    let ctx = libaes::key_setup(&key, &t).unwrap();
    let aes = |block: &[u8; 16]| {
        Ok::<[u8; 16], libre3_core::CryptoError>(
            libaes::phase5_block_encrypt(block, &ctx, &t).expect("phase5 block"),
        )
    };
    let nonce = unhex("210400008f8c4b");
    let plaintext = unhex(
        "8d2f296f882c1c0991d0e38c097892288c5b0b7441a7486d930806db08acdf1e3225ec72",
    );

    let (ct, tag) = libre3_core::ccm::encrypt(&nonce, &plaintext, &[], 4, &aes).unwrap();
    assert_eq!(
        hex(&[ct.clone(), tag.clone()].concat()),
        "49e3d257fb4fe91267cd1303cfab012ca215375f94040f8e9340a139de69720a88dc15dd50d3931a"
    );
    let recovered = libre3_core::ccm::decrypt(&nonce, &ct, &tag, &[], &aes).unwrap();
    assert_eq!(recovered, plaintext);
}