//! PLAN_T1DMDROID.md §12.1: Phase5KeyScheduleTests.swift ported 1:1 — six Python-derived
//! golden vectors over the 66→16 key schedule, plus a CCM round-trip over the derived key.
//! Tables load from $LIBRE3_TABLES_DIR; the suite skips itself when unset.

use libre3_core::phase5;

fn tables() -> Option<phase5::ScheduleTables> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    let t = phase5::ScheduleTables::from_dir(std::path::Path::new(&dir));
    if let Err(e) = &t {
        eprintln!("phase5 tables unusable from {dir}: {e:?}");
    }
    Some(t.expect("tables"))
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
fn python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let vectors: [(&str, &str); 6] = [
        (
            &"00".repeat(66),
            "4facb8db3692f2714ebaea5f9ff22de6",
        ),
        (
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\
             202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f4041",
            "56120a7e63561935008ef24e76f45d2a",
        ),
        (
            "050404000106040200060403000402060006030600010305070302070500030500020\
             605020406000607050606000507060103030207040705040406030200060507",
            "3b16168843c299ad7fa311ba2440d58a",
        ),
        (
            "02040702070305000006040006040206030003020605000105000301050000010202\
             0605060500000207040000060507020702060304070705050502060603000407",
            "3b16168843c299ad7fa311ba2440d58a",
        ),
        (
            "070705010506010205030100000305050600030300060304010205050201050606000407\
             010102020704030606050705010404060101060507010404000200000204",
            "3e2199e34b872cec7ea8b621542c77ff",
        ),
        (
            "040407050401050502030004070502010204000007030103030500070704060304\
             040404000206070605020103020500020404000203010107040404070403050004",
            "83f168a697970f7288c8a0abd0d83fee",
        ),
    ];
    for (input_hex, expected_hex) in vectors {
        let input = unhex(input_hex);
        let key = phase5::derive_raw_key(&input, &t).unwrap();
        assert_eq!(hex(&key), expected_hex, "input {input_hex}");
    }
}

#[test]
fn rejects_wrong_input_length() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    assert!(phase5::derive_raw_key(&vec![0u8; 65], &t).is_err());
}

/// The live 2026-05-06 tuple: derived key + wire block must reproduce the captured output.
#[test]
fn live_tuple_end_to_end() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let libaes_t = {
        let dir = std::env::var("LIBRE3_TABLES_DIR").unwrap();
        libre3_core::libaes::LibAESTables::from_dir(std::path::Path::new(&dir)).unwrap()
    };
    // Third vector's input derives the captured live key.
    let input = unhex(
        "050404000106040200060403000402060006030600010305070302070500030500020\
         605020406000607050606000507060103030207040705040406030200060507",
    );
    let key = phase5::derive_raw_key(&input, &t).unwrap();
    assert_eq!(hex(&key), "3b16168843c299ad7fa311ba2440d58a");

    let ctx = libre3_core::libaes::key_setup(&key, &libaes_t).unwrap();
    let plaintext = unhex("07210400008f8c4b0000000000000001");
    let out = libre3_core::libaes::phase5_block_encrypt(&plaintext, &ctx, &libaes_t).unwrap();
    assert_eq!(hex(&out), "c4ccfb387363f51bf61df08fc6d39304");
}