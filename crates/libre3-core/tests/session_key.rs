//! PLAN_T1DMDROID.md §12.1: SessionKeyTests.swift ported 1:1 — the first-pair entropy
//! coupling end to end (null entropy → 633fa8 scalar window → seeds → source66 → raw key),
//! the static-scalar-window override (`03 03` phone cert), and both reject paths. The kit's
//! `testDeriveThrowsNotYetSpecified` / `testInputsEquatable` are stub-only (the kit's
//! `SessionKey.derive` always throws `.notYetSpecified`) and are intentionally absent here.
//! Tables load from $LIBRE3_TABLES_DIR; the suite skips itself when unset.

use libre3_core::cert::first_pair_static_scalar_window_index1;
use libre3_core::ephemeral::EphemeralKeyPair;
use libre3_core::phase5::ScheduleTables;
use libre3_core::session_key::{
    derive_first_pair_phase5_material_from_entropy_source, derive_first_pair_phase5_material,
    make_first_pair_native_ephemeral, BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE, FirstPairPhase5KeyInputs,
};
use libre3_core::vm::FirstPairTables;
use sha2::Digest;

fn tables() -> Option<(FirstPairTables, ScheduleTables)> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    let dir = std::path::Path::new(&dir);
    let t = match FirstPairTables::from_dir(dir) {
        Ok(t) => t,
        Err(e) => panic!("first-pair tables unusable: {e:?}"),
    };
    let sched = ScheduleTables::from_dir(dir).expect("schedule tables");
    Some((t, sched))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn sha256(bytes: &[u8]) -> String {
    hex(&sha2::Sha256::digest(bytes))
}

/// kit `FirstPairPhase5KeyInputs` with the bundled entry source.
fn inputs(entropy: &[u8], eph65: &[u8], static65: &[u8], override70: Option<&[u8]>) -> FirstPairPhase5KeyInputs {
    FirstPairPhase5KeyInputs {
        entry_source: BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE.to_vec(),
        null_entropy_11a: entropy.to_vec(),
        sensor_ephemeral_pub65: eph65.to_vec(),
        sensor_static_pub65: static65.to_vec(),
        static_scalar_window: override70.map(|o| o.to_vec()),
    }
}

/// kit `testBundledFirstPairEntrySourceMatchesPythonReference` (SessionKeyTests.swift L35-42).
#[test]
fn bundled_first_pair_entry_source_matches_python_reference() {
    assert_eq!(BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE.len(), 0x214);
    assert_eq!(sha256(&BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE), "263e4b14637a6779be45abeaf3b688cfe34df4614cb928cb3ee7d883acfa028a");
}

/// kit `testFirstPairIndex1StaticScalarWindowMatchesHarnessTrace` (L44-51); the window
/// constant lives in cert.rs (PhoneCert.swift L95-105).
#[test]
fn first_pair_index1_static_scalar_window_matches_harness_trace() {
    let scalar = first_pair_static_scalar_window_index1();
    assert_eq!(scalar.len(), 70);
    assert_eq!(sha256(&scalar), "32d3f057582e12b27701edf28f38b08018a252dacd19638f9c13b079d3952e7a");
}

/// kit `testFirstPairPhase5SourceFromSensorPublicKeysMatchesPythonReferenceVector` (L53-80).
#[test]
fn first_pair_phase5_source_from_sensor_public_keys_matches_python_reference_vector() {
    let Some((t, sched)) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let entry_source: Vec<u8> = (0..0x214).map(|i| ((i * 5 + 1) & 7) as u8).collect();
    let null_entropy: Vec<u8> = (0..0x11a).map(|i| ((i * 11 + 3) & 0xff) as u8).collect();
    let inputs = FirstPairPhase5KeyInputs {
        entry_source,
        null_entropy_11a: null_entropy.clone(),
        sensor_ephemeral_pub65: unhex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
        sensor_static_pub65: unhex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
        static_scalar_window: None,
    };
    let material = derive_first_pair_phase5_material(&inputs, &t, &sched).expect("material");
    assert_eq!(material.null_entropy_11a, null_entropy);
    assert_eq!(material.null_attempts, 1);
    assert_eq!(hex(&material.source66), "040407060006060200050707070504020507010701060006020007070600020504020407050605040400060004020400000106060102060205030303040600040606");
    assert_eq!(hex(&material.raw_key), hex(&
        libre3_core::phase5::derive_raw_key(&material.source66, &sched).expect("raw key")));
}

/// kit `testFirstPairPhase5MaterialMatchesSingleRunEntropyTrace` (L82-126).
#[test]
fn first_pair_phase5_material_matches_single_run_entropy_trace() {
    let Some((t, sched)) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let material = derive_first_pair_phase5_material(
        &inputs(
            &unhex("010105000204040000030500050105000602060002030707040503060402040705070105010607030301030707010306060500030107030104040206030705010504020706020404060201000603070606050303070606010105070601030103050204040102020107050306010007000201040607030607010505040404030005060600070707070601030606030006010106010004040005000101020007030306030705030107040406070401050003070700050104000001030106070002070402030202060600050406070007050704030607060702050504050501020407020006060605070104000301010205000204000702010304000105020106040301060206020102000507020400000404050706010002050305"),
            &unhex("04e40ff95713629069c7be93644140a6d641435b84cb343adb3a208571b20b29a48322a60f864b12c1136cba8171ec68f0adce245a9f8be567d05c18bbe528b016"),
            &unhex("043e1f46f25d44b3d72a8c37dcfebc7c339ed01fc5668a6387458084ac9cafebe7438b649f76b81eeca9343287da162b07c5c07362997e40e13035df14cdf3d5d8"),
            None,
        ),
        &t,
        &sched,
    )
    .expect("material");
    assert_eq!(material.null_attempts, 1);
    assert_eq!(hex(&material.source66), "040404070404070200010700040400070602030604040602030706060405020003050701010602000206010207070005060307000202000300010003040004060203");
    assert_eq!(hex(&material.raw_key), "3fad08acb65701a8552a31a003ab2556");
    assert_eq!(sha256(&material.source66), "8ceeb7ddd894f8100cf50519140be9dc53f560014392aac96a808833b39339d9");
}

/// kit `testFirstPairPhase5MaterialWithFirstPairIndex1ScalarMatchesFreshTraceCandidate`
/// (L128-174): fresh sensor 0RKHDKRA8, 2026-05-09, native index-1 static scalar.
#[test]
fn first_pair_phase5_material_with_first_pair_index1_scalar_matches_fresh_trace_candidate() {
    let Some((t, sched)) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let material = derive_first_pair_phase5_material(
        &inputs(
            &unhex("31c165896d9172293ad6ced18e0b5de05678a01d4b4c2510f244559f504f681cc125d81e63dca5eb04dadbce3b870002e58603e0b4eea14fef6325159f1907d0cc92833be4b8ccaed896e907f9456c3a90fc3afc58740629de28e348308a46eafc861b93694fd23e83a82e3112bd55a383a563ee91909e6670174811dc79c360017e6e87b301a09c345f895ca2146d885b207a0e84e27e78cf65c302450c5eeca553343c0e99de1952b51fd7239f2213f10db9c621d980073f204d9cc5c390757012153219e5d327b72a02e52c910b509cec0e4d8dd28ae443d95f6eb0d7fb010b2c0fd96b3591e954def75a86705d920beb020cdd47cd7f41b20084cbc5a1937a36bc0e81a7a6ca377b400f1f88c58e6725293fc3054e74b988"),
            &unhex("040794129c51c5785b620b21e8737f9a29bc59a9b4fc820284d7e870e6030cd7b118c230b4eb8ff17fbf2f352fe85d6d9893a277737d549aea21b2f0d601967b18"),
            &unhex("0456d505b8de7ea821a2a43c2329bf613d7309595bacbfb5ac4bb49ecc1ddd88fc331e314c23b739e10c8fa6e63f955603f9b9a2cfc12aa41f669490d2047893ef"),
            Some(&first_pair_static_scalar_window_index1()),
        ),
        &t,
        &sched,
    )
    .expect("material");
    assert_eq!(material.null_attempts, 1);
    assert_eq!(hex(&material.source66), "040406040104010300050302040704000607030001050102030203050106040100020203010205050601000503060405070404050103060003070002020501010102");
    assert_eq!(hex(&material.raw_key), "a44aa812c72f0fb7c1321e62caef2312");
}

/// kit `testFirstPairPhase5MaterialMatchesPost08LiveAttempt16` (L176-225): the first live
/// attempt that sent the post-Phase-5 command 0x08; the null scalar window is pinned too.
#[test]
fn first_pair_phase5_material_matches_post08_live_attempt_16() {
    let Some((t, sched)) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let material = derive_first_pair_phase5_material(
        &inputs(
            &unhex("8987c91f1595e8a060e4cba652368ae8797e9113cfd412bebd0ea1a03783ae59ee70d2c947578803b06b275c96632d148b81658bb87a3eabb5755273c40c397f7255f3c1d742df608383fbbfff5a9b9fbc11a1ab525382024c85687cf79c2a391ca7cc309ff82fe098c2d86e49f8b26364153f0bcb8945c887f5a2a7b54d568daa373a86c85c283fbb6285f35dca2d30263c34ce182c1fc63e6022a3c7e6eaebe3a473d3c754bb8f3982172431af66388948aaf5c709f6699b7608dcd161811dda99c61b302f46684433e61ef2afa4dd9f8b0f2472f6120197cdfc0b940ad5f93ac01fc7497fb355c753df9c65fc68721690c35a09550fb3c326e38bcbe37ebb309a680c383967627f58a108e1e94ecd16c5d2bc2f576dabdc7b"),
            &unhex("04057637b02770974bf685ccf017992cf586e94bf7a6cbe229bd813f68873a90e1606b8b73d6d8873a31b3a556feae538c9a808fcd936cee8e73ad556922b98f87"),
            &unhex("0456d505b8de7ea821a2a43c2329bf613d7309595bacbfb5ac4bb49ecc1ddd88fc331e314c23b739e10c8fa6e63f955603f9b9a2cfc12aa41f669490d2047893ef"),
            Some(&first_pair_static_scalar_window_index1()),
        ),
        &t,
        &sched,
    )
    .expect("material");
    assert_eq!(material.null_attempts, 1);
    assert_eq!(hex(&material.null_scalar_window), "1b2c5bac8edb26c91d0d89d976e065040704fcd7858c792f82ae8e97829fd2f30000000000000000000000000000000000000000000000000000000000000000000000000000");
    assert_eq!(hex(&material.source66), "040407030302040007070700030207010601000101020103050707010704070706060404020305010105030005050006010300000202060300030002020504060404");
    assert_eq!(hex(&material.raw_key), "4d4bdbc9e8881dc2918e5225ebfd56a2");
}

/// kit `testFirstPairPhase5SourceRejectsInvalidSensorPointEncoding` (L227-244).
#[test]
fn first_pair_phase5_source_rejects_invalid_sensor_point_encoding() {
    let Some((t, sched)) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let inputs = FirstPairPhase5KeyInputs {
        entry_source: (0..0x214).map(|i| ((i * 5 + 1) & 7) as u8).collect(),
        null_entropy_11a: vec![0; 0x11a],
        sensor_ephemeral_pub65: vec![0x05],
        sensor_static_pub65: vec![0x04; 65],
        static_scalar_window: None,
    };
    let err = libre3_core::session_key::derive_first_pair_phase5_source(&inputs, &t, &sched)
        .expect_err("invalid sensor ephemeral point must fail closed");
    assert!(err.to_string().contains("invalidSensorPointEncoding"), "{err}");
    assert!(err.to_string().contains("count: 1"), "{err}");
}

/// kit `testFirstPairPhase5MaterialEntropySourceRejectsInvalidAttemptLimit` (L246-268):
/// maxAttempts 0 fails closed before the entropy source is ever consulted.
#[test]
fn first_pair_phase5_material_entropy_source_rejects_invalid_attempt_limit() {
    let Some((t, sched)) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let called = std::cell::Cell::new(false);
    let err = derive_first_pair_phase5_material_from_entropy_source(
        &(0..0x214).map(|i| ((i * 5 + 1) & 7) as u8).collect::<Vec<u8>>(),
        &unhex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
        &unhex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"),
        None,
        0,
        |_attempt| {
            called.set(true);
            Ok(Vec::new())
        },
        &t,
        &sched,
    )
    .expect_err("maxAttempts 0 must fail closed");
    assert!(!called.get(), "entropy source must not be called");
    assert!(err.to_string().contains("invalid633fa8NullMaxAttempts"), "{err}");
}

/// Coupling pin for the §5.3 invariant (no dedicated kit vector exists for
/// `makeFirstPairNativeEphemeral` itself): the post08 live-accepted entropy (pinned by
/// `first_pair_phase5_material_matches_post08_live_attempt_16`) must be accepted on the
/// first attempt, produce the pinned null scalar window, and the wire public key must be
/// the process2(5) point for the SAME entropy.
#[test]
fn make_first_pair_native_ephemeral_couples_entropy_to_wire_key() {
    let Some((t, _sched)) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let accepted = unhex("8987c91f1595e8a060e4cba652368ae8797e9113cfd412bebd0ea1a03783ae59ee70d2c947578803b06b275c96632d148b81658bb87a3eabb5755273c40c397f7255f3c1d742df608383fbbfff5a9b9fbc11a1ab525382024c85687cf79c2a391ca7cc309ff82fe098c2d86e49f8b26364153f0bcb8945c887f5a2a7b54d568daa373a86c85c283fbb6285f35dca2d30263c34ce182c1fc63e6022a3c7e6eaebe3a473d3c754bb8f3982172431af66388948aaf5c709f6699b7608dcd161811dda99c61b302f46684433e61ef2afa4dd9f8b0f2472f6120197cdfc0b940ad5f93ac01fc7497fb355c753df9c65fc68721690c35a09550fb3c326e38bcbe37ebb309a680c383967627f58a108e1e94ecd16c5d2bc2f576dabdc7b");
    let accepted_for_closure = accepted.clone();
    let calls = std::cell::Cell::new(0);
    let material = make_first_pair_native_ephemeral(
        4,
        move |byte_count| {
            assert_eq!(byte_count, 0x11a); // kit passes builder633fa8NullEntropyBytes
            let n = calls.get() + 1;
            calls.set(n);
            if n == 1 {
                Ok(accepted_for_closure.clone())
            } else {
                Err(libre3_core::CryptoError::Slice {
                    reason: "rejected633fa8NullEntropy".to_owned(),
                })
            }
        },
        &t,
    )
    .expect("accepted on the first attempt");
    assert_eq!(material.attempts, 1);
    assert_eq!(hex(&material.null_entropy_11a), hex(&accepted));
    // kit pin: the post08 live attempt's null scalar window (SessionKeyTests.swift L214-218).
    assert_eq!(
        hex(&material.null_scalar_window),
        "1b2c5bac8edb26c91d0d89d976e065040704fcd7858c792f82ae8e97829fd2f30000000000000000000000000000000000000000000000000000000000000000000000000000"
    );
    assert_eq!(material.key_pair.public_key65.len(), 65);
    assert_eq!(material.key_pair.public_key65.first(), Some(&0x04));
    // The private scalar is the LE-window scalar (first 32 bytes reversed); it must stay a
    // valid P-256 scalar so the same material drives both ECDH products (§5.3).
    let round_trip = EphemeralKeyPair::from_native_scalar_window_le(&material.null_scalar_window)
        .expect("scalar window must yield a valid key");
    assert_eq!(round_trip.private_key.to_bytes().as_slice(), material.key_pair.private_key.to_bytes().as_slice());
}
