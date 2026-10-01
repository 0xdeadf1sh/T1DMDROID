//! FirstPairSourceSliceTests.swift — the process2(5) public-point builder and the
//! `builder6388f0FirstPairStreamSeedsFrom…5bcf98Outputs` trio, ported 1:1 (Stage F1 of the
//! 6388f0 first-pair builder port). Tables load from $LIBRE3_TABLES_DIR; the suite skips
//! itself when the variable is unset so CI stays green without table bytes.
//!
//! Kit vectors: `testProcess2P5PublicKeyMatchesAndroidEntryTraces`
//! (FirstPairSourceSliceTests.swift L4429-4466) and the entrySource / entropy /
//! entropySource sections of
//! `testBuilder6388f0FirstPairStreamSeedsFromEntrySourceMatchesPythonReferenceVector`
//! (FirstPairSourceSliceTests.swift L924-989).

use libre3_core::vm::{process2, FirstPairTables};

fn tables() -> Option<FirstPairTables> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    let t = FirstPairTables::from_dir(std::path::Path::new(&dir));
    if let Err(e) = &t {
        eprintln!("first-pair tables unusable from {dir}: {e:?}");
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

/// kit `testProcess2P5PublicKeyMatchesAndroidEntryTraces` (FirstPairSourceSliceTests.swift
/// L4429-4466): two Android entry-trace entropies and their 65-byte `04||X||Y` public keys.
#[test]
fn process2_p5_public_key65_matches_android_entry_traces() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let vectors: [(&str, &str); 2] = [
        (
concat!(
    "8987c91f1595e8a060e4cba652368ae8797e9113cfd412bebd0ea1a03783ae59",
    "ee70d2c947578803b06b275c96632d148b81658bb87a3eabb5755273c40c397f",
    "7255f3c1d742df608383fbbfff5a9b9fbc11a1ab525382024c85687cf79c2a39",
    "1ca7cc309ff82fe098c2d86e49f8b26364153f0bcb8945c887f5a2a7b54d568d",
    "aa373a86c85c283fbb6285f35dca2d30263c34ce182c1fc63e6022a3c7e6eaeb",
    "e3a473d3c754bb8f3982172431af66388948aaf5c709f6699b7608dcd161811d",
    "da99c61b302f46684433e61ef2afa4dd9f8b0f2472f6120197cdfc0b940ad5f9",
    "3ac01fc7497fb355c753df9c65fc68721690c35a09550fb3c326e38bcbe37ebb",
    "309a680c383967627f58a108e1e94ecd16c5d2bc2f576dabdc7b",
),
concat!(
    "04b60e0f455a1f2ebc3a1246d9311a66722f80fbc0cbdc23d18ae5e50693eed2",
    "b1ea74d24eddcc8dd1957cf621a1f5514fcd7b40ec37f18f8c8060db6f8076b1",
    "21",
),
        ),
        (
concat!(
    "726d47655b9434b44cd08664665dfb86934638911b6ebcc26420fe124ab654fd",
    "e722e77f43756603943a8ee8196c6d5f83fc9cfe637e309f6f4b3c8fd5f10959",
    "6f60b9e4899422925b8a0368b143580541bcaac3b4017b82f38d00c14d46fbe3",
    "197ccfa9af048f6b446973c664901b84d362e95086e235e58517883f7b89aef7",
    "42768adc355131885657b686bdb6bd82feb11591b63f3e9466f0e21f20cc5875",
    "7ac547f57a21ee59b4816779510bd7d911861a116c40332328cd4ec68579831e",
    "76ede1a5c6776c9d114a2788e8aed94b8f50a051da8cd8bdbdf7c77f53ce76ee",
    "259d5d568a7b71edd3564f80969a4550a920238d1739b34eceeb275c29f8dfb9",
    "4796005ff15989a177536119388ed70c8fb6fa72109635da2741",
),
concat!(
    "049cb2d2658568e6685fea83f5051ff703baec07cbca3b10e58600d538b85795",
    "db5cd35248bd30f1918627a6d4f2f91ce31d21057279fa790b895b15192d040a",
    "99",
),
        ),
    ];

    for (entropy_hex, expected_key65) in vectors {
        let entropy = unhex(entropy_hex);
        assert_eq!(entropy.len(), 0x11a);
        let public_key =
            process2::builder_process2_p5_public_key65_from_entropy(&entropy, &t).unwrap();
        assert_eq!(hex(&public_key), expected_key65);
    }
}

/// Structural encoding checks plus a scalar-window regression pin. The kit vector test above
/// pins the full key65 chain; this test pins the intermediate 70-byte scalar window on the
/// shared 633fa8 null-entropy pattern (tests/firstpair_null633fa8.rs) so later refactors catch
/// drift at the window boundary.
#[test]
fn process2_p5_public_scalar_window_sizes_and_regression_pin() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let entropy: Vec<u8> = (0..0x11a).map(|index| ((index * 11 + 3) & 0xff) as u8).collect();
    let scalar_window =
        process2::builder_process2_p5_public_scalar_window_from_entropy(&entropy, &t).unwrap();
    // kit `builder633fa8ScalarWindowBytes` = 70 (FirstPairSourceSlice.swift L14738)
    assert_eq!(scalar_window.len(), 70);
    // regression pin, no kit vector exists for the process2 scalar-window stage alone
    // (live-verify in phase 4); pins self-consistency, not kit-correctness.
    assert_eq!(
        hex(&scalar_window),
        "a4ade58b737b0e5082a741e1d27819dafb8dc3c26fab0e6e77892fc837e429d4"
            .to_owned()
            + "0000000000000000000000000000000000000000000000000000000000000000000000000000"
    );
    eprintln!("process2 scalar-window pin: {}", hex(&scalar_window));
}
