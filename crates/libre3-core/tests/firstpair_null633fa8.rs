//! FirstPairSourceSliceTests.swift — 633fa8 static/null/low-seed layer vectors, ported 1:1
//! (Stage B of the 6388f0 first-pair builder port). Tables load from $LIBRE3_TABLES_DIR;
//! the suite skips itself when the variable is unset so CI stays green without table bytes.
//!
//! Out-of-scope Swift assertions (later stages) are intentionally not ported here:
//! `testBuilder6388f0FirstPairStreamSeedsFromEntrySourceMatchesPythonReferenceVector` and the
//! 642f60/6473d0/64cd40 caller-row stage chains that consume these preimages.

use libre3_core::vm::lowseed;
use libre3_core::vm::FirstPairTables;

fn tables() -> Option<FirstPairTables> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    let t = FirstPairTables::from_dir(std::path::Path::new(&dir));
    if let Err(e) = &t {
        eprintln!("first-pair tables unusable from {dir}: {e:?}");
    }
    Some(t.expect("tables"))
}

use sha2::Digest;

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn sha256(bytes: &[u8]) -> String {
    hex(&sha2::Sha256::digest(bytes))
}

fn pack_u32_le(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

fn pack_u64_le(words: &[u64]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// kit `testBundledFirstPairEntrySourceMatchesPythonReference` (SessionKeyTests.swift L35-42).
#[test]
fn bundled6388f0_low_seed_entry_source_matches_python_reference() {
    let source = &lowseed::BUILDER6388F0_LOW_SEED_ENTRY_SOURCE;
    assert_eq!(source.len(), 0x214);
    assert_eq!(
        sha256(source),
        "263e4b14637a6779be45abeaf3b688cfe34df4614cb928cb3ee7d883acfa028a"
    );
}

/// kit `testBuilder6388f0LowSeedCF0SeedsFromEntrySourceMatchesPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L1071-1138).
#[test]
fn builder6388f0_low_seed_cf0_seeds_from_entry_source_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let entry_source: Vec<u8> = (0..0x214usize).map(|index| ((index * 5 + 1) & 7) as u8).collect();
    let seeds = lowseed::builder6388f0_low_seed_cf0_seeds_from_entry_source(&entry_source, &t).unwrap();
    assert_eq!(seeds.phase1.len(), 0x10a);
    assert_eq!(seeds.phase2.len(), 0x10a);
    assert_eq!(seeds.phase3.len(), 0x10a);
    assert_eq!(
        sha256(&seeds.phase1),
        "9b2d39eb30062613f7ccf5520345c80937d149f06c42c3816f9547388745df3b"
    );
    assert_eq!(
        sha256(&seeds.phase2),
        "a34a6cc968db094daaecfd303bbecdda322cd0e01c606c1705422f800dbf02fd"
    );
    assert_eq!(
        sha256(&seeds.phase3),
        "c60ed79f4e6da3aa3768e10d95a2c5837708652bb7e2c06c1b91b09adcb2d451"
    );
    assert_eq!(hex(&seeds.phase3[seeds.phase3.len() - 16..]), "04020200010104050701060202000004");

    let pair = lowseed::builder6388f0_low_seed_tail_pair_from_entry_source(&entry_source, &t).unwrap();
    assert_eq!(
        sha256(&pair.left),
        "2c31a9b72d1587155839611a00ebb6756ae34b459dbcbb10b7977ec6f2f85fa8"
    );
    assert_eq!(
        sha256(&pair.right),
        "2da6677e738231f8eeeec117db576aaffd4c1c495efd72bf847bf15813ed1d4c"
    );
    let tail_stage = lowseed::builder6388f0_low_seed_tail_stage_from_pair(&pair, &t).unwrap();
    assert_eq!(
        sha256(&tail_stage),
        "05755c6dd9bc68d980beeef36392143524e6bbfcb20b270893509d57ebbc83a3"
    );
    let prelude = lowseed::builder6388f0_low_seed_prelude_source_from_tail_stage(&tail_stage, &t).unwrap();
    assert_eq!(
        sha256(&prelude),
        "e07c11f4368e33eb9812c3d31c186b76741a5b63eb7e77c1fd79a80dd680aaf2"
    );
    let seed_blocks = lowseed::builder6388f0_low_seed_blocks_from_prelude_source(&prelude, &t).unwrap();
    assert_eq!(seed_blocks.len(), 20 * 16);
    assert_eq!(
        sha256(&seed_blocks),
        "77da5cce8122c3f8309100320249ddfc7d87e8a407e1699eb0d96032a3eb1283"
    );

    let loop_result = lowseed::builder6388f0_low_seed_loop_from_blocks(&seed_blocks, &t).unwrap();
    assert_eq!(hex(&loop_result.final6377f0), "010103020202020202040402040402030502");
    assert_eq!(
        loop_result.schedule_words[..4],
        [0x27985d74, 0x602c800b, 0xb5823fb5, 0x3b970a6f]
    );
    assert_eq!(
        loop_result.schedule_words[loop_result.schedule_words.len() - 4..],
        [0x1185db13, 0x397e64c3, 0xec257cd4, 0x995e53cc]
    );

    let preimages = lowseed::builder6388f0_row0_low_seed_preimages_from_entry_source(&entry_source, &t).unwrap();
    assert_eq!(
        sha256(&preimages.out4),
        "e70d3f912b290b5bd31c6dd27e8816448c16863247354286fc66957bdf2a8e27"
    );
    assert_eq!(
        sha256(&preimages.out3),
        "feb5a841e9f99f5c149350296ffb74725c839af719015cfebfc2e1c01714acbc"
    );
    assert_eq!(
        sha256(&preimages.out2),
        "236c8c5040f999f86bfa6bdfd7f9e8e3ee79ce19a6568cf75fd6ef58880bced2"
    );
}

/// kit `testBuilder633fa8StaticScalarWindowFromEntrySourceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L1140-1167).
#[test]
fn builder633fa8_static_scalar_window_from_entry_source_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let entry_source: Vec<u8> = (0..0x214usize).map(|index| ((index * 5 + 1) & 7) as u8).collect();
    let boundary = lowseed::builder633fa8_static_tail_boundary_from_entry_source(&entry_source, &t).unwrap();
    assert_eq!(
        sha256(&boundary.prelude_source),
        "e07c11f4368e33eb9812c3d31c186b76741a5b63eb7e77c1fd79a80dd680aaf2"
    );
    assert_eq!(
        sha256(&pack_u32_le(&boundary.words3ab0)),
        "9bb588ed741963c1ed0b32efab701fbd87819dfe65f9e0192e8830e8a7a7574d"
    );
    assert_eq!(
        sha256(&pack_u32_le(&boundary.words3120)),
        "fe4e9fc8207e0cc3276f2cb073a8050bbaa842cd2b114165630eb8214fb30b01"
    );
    assert_eq!(
        sha256(&pack_u32_le(&boundary.words2dfc)),
        "2acd8bebf1f8746c4d0c264f28cd42010725f116a4587b702b29b75b8fbb2052"
    );
    assert_eq!(boundary.seed3110, lowseed::BUILDER633FA8_INVARIANT_SEED_3110);

    let scalar = lowseed::builder633fa8_static_scalar_window_from_entry_source(&entry_source, &t).unwrap();
    assert_eq!(
        hex(&scalar),
        "f38d95844ac5834265c854266814ed9e67ce508eea912fc81a9b2d28db0ddd5e".to_owned()
            + "0000000000000000000000000000000000000000000000000000000000000000000000000000"
    );
}

/// kit `testBuilder633fa8NullEntrySourcesAndInitialMatchPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L1169-1308). The tail-qword/E10/scalar assertions of the
/// Swift test overlap the dedicated vector tests below and are covered there.
#[test]
fn builder633fa8_null_entry_sources_and_initial_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let sources = lowseed::builder633fa8_null_entry_sources_from_invariant_entry().unwrap();
    assert_eq!(sources.prologue_source.len(), 0x11a);
    assert_eq!(
        sha256(&sources.prologue_source),
        "b2b08a579ebd69e28c8bbb33b19317c3d4c22cce9ec2a6d60eb81e49c7729115"
    );
    assert_eq!(
        sha256(&pack_u32_le(&sources.check1_source_words)),
        "4b29cac325304080f0e7b82a92ffe3ef9c3e252aac748ab6e776673fe7e73db6"
    );
    assert_eq!(
        sha256(&pack_u32_le(&sources.check2_source_words)),
        "8905c6b8cd1d1fec875c168212d3555909e4554ee63432a54744a404db243e4d"
    );

    let entropy: Vec<u8> = (0..0x11a).map(|index| ((index * 11 + 3) & 0xff) as u8).collect();
    let initial = lowseed::builder633fa8_null_initial_from_entropy(&entropy, &sources.prologue_source, &t).unwrap();
    assert_eq!(
        sha256(&initial.masked_entropy),
        "23db0a42e5599a320a6384094203a1ecf34a1f7517c94c7fd47267708c760834"
    );
    assert_eq!(
        sha256(&initial.cf0),
        "9f50c4c539508ffd0d5a87b37d60997bb1622db044b55662c4d1d6d8bad6f532"
    );
    assert_eq!(
        sha256(&initial.e10),
        "0d0d59d1394d720b3d30d2a5f0ae4af4e811d2c9c690767b95d603883164cba5"
    );
    assert_eq!(
        sha256(&initial.seed_inputs),
        "296b545eb6c3114d4b731abf59bbcb43e1e34b321e787683228ceb02c11d9cc2"
    );
    assert_eq!(
        sha256(&initial.seed_blocks),
        "30f124b2c0d6cd19c0bbf4e4f8cf1974e5766ed3171e9556e69979108e74626f"
    );

    let loop_result = lowseed::builder633fa8_null_first_loop_from_blocks(&initial.seed_blocks, &t).unwrap();
    assert_eq!(hex(&loop_result.final_t_lane), "060504020202040404020204040404010504");
    assert_eq!(
        sha256(&pack_u32_le(&loop_result.schedule_words)),
        "652ce3a7810e6b09bf6ce92f7029f7a79599a95db235538aea7e84bec65e21f0"
    );
    assert_eq!(
        loop_result.schedule_words[..4],
        [0x77de69c8, 0xc857bd48, 0x65000b63, 0xa6ddb53b]
    );
    assert_eq!(
        loop_result.schedule_words[loop_result.schedule_words.len() - 4..],
        [0x7c13a2ce, 0xe082b5ba, 0xbfaf4d29, 0xc67887e7]
    );

    let acceptance = lowseed::builder633fa8_null_schedule_acceptance(
        &loop_result.schedule_words,
        &sources.check1_source_words,
        &sources.check2_source_words,
        &t,
    )
    .unwrap();
    assert!(acceptance.first_ok);
    assert!(acceptance.second_ok);

    let mut rejected_words = loop_result.schedule_words.clone();
    rejected_words[19] ^= 1;
    let rejected = lowseed::builder633fa8_null_schedule_acceptance(
        &rejected_words,
        &sources.check1_source_words,
        &sources.check2_source_words,
        &t,
    )
    .unwrap();
    assert!(!rejected.first_ok);
    assert!(!rejected.second_ok);

    let post_accept = lowseed::builder633fa8_null_post_accept_blocks(&loop_result.schedule_words, &t).unwrap();
    assert_eq!(post_accept.blocks4080.len(), 20 * 16);
    assert_eq!(post_accept.blocks3f40.len(), 20 * 16);
    assert_eq!(
        sha256(&post_accept.blocks4080),
        "a8732537d6be3b54f8d00663ae3d0461ed7974b6861b1095b0d16730c08f9c86"
    );
    assert_eq!(
        sha256(&post_accept.blocks3f40),
        "8975ea6381dc1f9149d202522d21abf7105e2faf2888a306b5122d3c8f6f0b7c"
    );
    assert_eq!(hex(&post_accept.blocks4080[..16]), "01070705070306040206010301020603");
    assert_eq!(hex(&post_accept.blocks3f40[..16]), "02030203010200000706020600030203");

    let prelude = lowseed::builder633fa8_null_prelude_source_from_post_accept(
        &post_accept.blocks4080,
        &post_accept.blocks3f40,
        &t,
    )
    .unwrap();
    assert_eq!(prelude.len(), 0x10a);
    assert_eq!(
        sha256(&prelude),
        "ed4e5c29dff15da45590bf9bc4ea8b7124af32f51f3add5e786cf77fd36c747d"
    );
    assert_eq!(hex(&prelude[..16]), "05000204070405070006020301060606");
    assert_eq!(hex(&prelude[prelude.len() - 16..]), "06040206050602040404020605060204");

    let entropy_prelude = lowseed::builder633fa8_null_prelude_source_from_entropy(&entropy, &t).unwrap();
    assert_eq!(entropy_prelude, prelude);

    let scalar = lowseed::builder633fa8_null_scalar_window_from_entropy(&entropy, &t).unwrap();
    assert_eq!(
        sha256(&scalar),
        "c4f2357511bf2071de2a5478a5d3d8a17c2b4da7b46c6cb46f4834ecb3a2f2ba"
    );
    assert_eq!(
        hex(&scalar),
        "3b588dd68f20da5f883993332cabcda6576645712cdd039d0a8195f4b1c0b52e".to_owned()
            + "0000000000000000000000000000000000000000000000000000000000000000000000000000"
    );
    assert_eq!(
        lowseed::builder633fa8_scalar_window_from_prelude_source(&prelude, &t).unwrap(),
        scalar
    );

    // kit L1291-1307: the retrying entropy-source wrapper, success on attempt 1 and the
    // maxAttempts == 0 rejection.
    let mut entropy_calls = 0;
    let entropy_for_retry = entropy.clone();
    let retry_result = lowseed::builder633fa8_null_scalar_window_from_entropy_source(
        3,
        |requested_count| {
            entropy_calls += 1;
            assert_eq!(requested_count, 0x11a);
            Ok(entropy_for_retry.clone())
        },
        &t,
    )
    .unwrap();
    assert_eq!(entropy_calls, 1);
    assert_eq!(retry_result.scalar_window, scalar);
    assert_eq!(retry_result.entropy11a, entropy);
    assert_eq!(retry_result.attempts, 1);

    let err = lowseed::builder633fa8_null_scalar_window_from_entropy_source(0, |_| Ok(entropy.clone()), &t)
        .unwrap_err();
    assert_eq!(
        match &err {
            libre3_core::CryptoError::Slice { reason } => reason.clone(),
            other => format!("{other:?}"),
        },
        "invalid633fa8NullMaxAttempts(0)"
    );
}

/// kit `testBuilder633fa8TailQwordsFromSourcesMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L1310-1355).
#[test]
fn builder633fa8_tail_qwords_from_sources_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let words3ab0: [u32; 20] = [
        0x561f0a13, 0x2703b81f, 0xc60ebb71, 0x13ae9923, 0x6151794d,
        0xcbd488b3, 0x105a57ba, 0xbe270b51, 0x35178421, 0x9c1e6b02,
        0x8131d744, 0x995e53cc, 0xe98d93e2, 0xbcf84415, 0xbfccce8e,
        0x6c32338c, 0xd608b5a1, 0xe7c2db10, 0x8131d744, 0x995e53cc,
    ];
    let words3120: [u32; 20] = [
        0xb33842d7, 0x7b6ba784, 0xa2f90f36, 0xde5e2ad7, 0x3c3537a9,
        0x81d564f6, 0x339ab4a2, 0x999de03b, 0x56c13b42, 0xff14a487,
        0x5a31640c, 0xc3f85236, 0x3c1dc79e, 0x58a8d4a6, 0x541cb00e,
        0x63323fcd, 0x1aa54a16, 0x01f1b661, 0x5a31640c, 0xc3f85236,
    ];
    let words2dfc: [u32; 20] = [
        0x9bed19fd, 0xc70a4d0f, 0x8257d22b, 0xe2fafcb3, 0x02c77d20,
        0xb5ed0efa, 0x878c1b06, 0x4bd92d7d, 0x21c6944f, 0xd3ec5d2f,
        0x876fda86, 0x37f3e22a, 0x3cfcd7ce, 0xabdc16eb, 0x84ad2f7d,
        0x4bd92d7d, 0xf647adce, 0xaa7b701e, 0x876fda86, 0x37f3e22a,
    ];
    let qwords = lowseed::builder633fa8_tail_qwords_from_sources(
        &words3ab0,
        &words3120,
        &words2dfc,
        0xb6ccf02833a9825e,
        &t,
    )
    .unwrap();
    assert_eq!(
        qwords,
        vec![
            0x278653e978fb8d86, 0x01531105e76d5345, 0x6ca239d879644a5c, 0xa06b5f9758fb4bd5,
            0xd4aba6030256919a, 0x701b8d245771a9c8, 0x25f9e61e7612a2cb, 0x42af4c71aeed4949,
            0xf69e5c8932e52f6c, 0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f,
            0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f,
            0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f,
        ]
    );
    assert_eq!(
        sha256(&pack_u64_le(&qwords)),
        "8718a3b565f0e38d8631d894877d72c491cfaa21abccc8958829a7b0ca97b15d"
    );
    let e10_words = lowseed::builder633fa8_e10_words_from_tail_qwords(&qwords, &t).unwrap();
    assert_eq!(
        hex(&lowseed::builder633fa8_scalar_window_from_e10_words(&e10_words, &t).unwrap()),
        "4532bea83bfdabcf74fdaeeb0319a83c051a31e40a620e3bd0db1cd993ed8522".to_owned()
            + "0000000000000000000000000000000000000000000000000000000000000000000000000000"
    );
}

/// kit `testBuilder633fa8E10WordsFromTailQwordsMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L1357-1384).
#[test]
fn builder633fa8_e10_words_from_tail_qwords_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let tail_qwords: [u64; 20] = [
        0x278653e978fb8d86, 0x01531105e76d5345, 0x6ca239d879644a5c, 0xa06b5f9758fb4bd5,
        0xd4aba6030256919a, 0x701b8d245771a9c8, 0x25f9e61e7612a2cb, 0x42af4c71aeed4949,
        0xf69e5c8932e52f6c, 0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f,
        0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f,
        0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f, 0x7785655189e16a0f,
    ];
    let words = lowseed::builder633fa8_e10_words_from_tail_qwords(&tail_qwords, &t).unwrap();
    assert_eq!(
        words,
        vec![
            0x5a1e4b39, 0x5e9483af, 0xcf48138f, 0x9e28b8cd, 0x55b48903,
            0xdefd3261, 0x2c462f90, 0x5d22446d, 0x5170b893, 0xdcd2fa37,
            0xfaacce40, 0x997a6bab, 0x7781207b, 0x182c4538, 0x5475ee9a,
            0xf1fd3b9c, 0x8281f8c2, 0x0ba21025, 0xfaacce40, 0x997a6bab,
        ]
    );
    assert_eq!(
        sha256(&pack_u32_le(&words)),
        "4f7646b6cb17189560193adc7b951d47443edf292ea8213d2481cd8c89ba79a9"
    );
    assert_eq!(
        hex(&lowseed::builder633fa8_scalar_window_from_e10_words(&words, &t).unwrap()),
        "4532bea83bfdabcf74fdaeeb0319a83c051a31e40a620e3bd0db1cd993ed8522".to_owned()
            + "0000000000000000000000000000000000000000000000000000000000000000000000000000"
    );
}

/// kit `testBuilder633fa8ScalarWindowFromE10WordsMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L1386-1404).
#[test]
fn builder633fa8_scalar_window_from_e10_words_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let e10_words: [u32; 20] = [
        0xf15eecb3, 0x6c31d20d, 0x7a812282, 0x88c66764, 0xc7daeb98,
        0xcb55b447, 0x7dc4c98a, 0xe8533b12, 0x3976a2b8, 0x39a2c9bd,
        0xa7ca28ea, 0x6e74c495, 0x06708db4, 0x5a2caf42, 0xedb8643d,
        0xd19d3544, 0x8281f8c2, 0x0ba21025, 0xfaacce40, 0x997a6bab,
    ];
    let scalar = lowseed::builder633fa8_scalar_window_from_e10_words(&e10_words, &t).unwrap();
    assert_eq!(scalar.len(), 70);
    assert_eq!(
        hex(&scalar),
        "f38d95844ac5834265c854266814d19822125ef87edcfcab64db2fd1a3b4b0e7a".to_owned()
            + "d6a1fa15f51ce7eea7853023be2e9ecb5a99876f7a8a0e00000000000000000000000000000"
    );
    assert_eq!(
        sha256(&scalar),
        "af6aea9e701fb090af64b2446d8ccdef01327837f264bbe65b20db784345fa16"
    );
}
