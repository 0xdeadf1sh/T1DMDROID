//! FirstPairSourceSliceTests.swift — high-seed stream-start layer vectors, ported 1:1
//! (Stage A of the 6388f0 first-pair builder port). Tables load from $LIBRE3_TABLES_DIR;
//! the suite skips itself when the variable is unset so CI stays green without table bytes.
//!
//! Out-of-scope Swift assertions (later stages) are intentionally not ported here:
//! `deriveFrom6388f0FirstPairStreamSeeds`, the entrySource/entropy/sensor-point stream-seed
//! variants (low-seed 633fa8 layer), and the 642f60/6473d0 stage chains.

use libre3_core::vm::highseed;
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

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn sha256(bytes: &[u8]) -> String {
    hex(&sha2::Sha256::digest(bytes))
}

fn pack_u32_le(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// kit `testBuilder6388f0Next642f60InputsFrom64cd40OutputsMatchPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L681-709).
#[test]
fn builder6388f0_next642f60_inputs_from64cd40_outputs_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let first: Vec<u8> = (0..88).map(|index| ((index * 3 + 1) & 0xff) as u8).collect();
    let second: Vec<u8> = (0..88).map(|index| ((index * 5 + 2) & 0xff) as u8).collect();
    let third: Vec<u8> = (0..88).map(|index| ((index * 7 + 4) & 0xff) as u8).collect();

    let next = highseed::builder6388f0_next642f60_inputs_from64cd40_outputs(&first, &second, &third, &t).unwrap();
    assert_eq!(
        hex(&next.x0),
        concat!(
            "727ff3b03b7f9b9445dc32088470dba2e6560584f7811dda9c792f21b509e1d2",
            "92391698dbe3f37f255093af64049e2b463bc0751707c7423cc615e155ef3aa8",
            "b2f3387f7b484c6b05c4f356449860b4a61f7b67378c0d92"
        )
    );
    assert_eq!(
        hex(&next.x1),
        concat!(
            "82da21c2812f75174d7cb165a116dbf009e53aea970434b39f33459e272f861b",
            "e27b90bce1deb6322d5fcb2a41d541dfa991d42677480a7cbfe36d5d8767d5e1",
            "424e8159410f29ef0dd7bbe7e1623750495d780257d74f2d"
        )
    );
    assert_eq!(
        hex(&next.x2),
        concat!(
            "24966d1c50f77db7f8f3f44c7c6f1c9008083749fa291b844bf1adcccc9903b8",
            "445a06e7b06968ce9823d9b09c5ab1b3687801c69a9ee22fab48de1eac457f9",
            "56487f0801049ff2d38d61817bc4546d7c8e8cb423a13aadb"
        )
    );
}

/// kit `testBuilder6388f0StreamStart642f60InputsMatchPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L711-745).
#[test]
fn builder6388f0_stream_start642f60_inputs_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 9 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 11 + 7) & 0xff) as u8).collect();

    let start = highseed::builder6388f0_stream_start642f60_inputs(&out0_seed, &out1_seed, None, &t).unwrap();
    assert_eq!(
        hex(&start.x0),
        concat!(
            "6a98ace04c88d51d6ab1d75da53dff508ffdea49d14182592c7500a439baad09",
            "8a47a5a0ecaafcc40aeef4178563c64f2f9e8ba1f142af1f8c2af649193b49f6",
            "aaf69d608ccd236caa2a12d265898d4ecf3e2cf91144d37d"
        )
    );
    assert_eq!(
        hex(&start.x1),
        concat!(
            "b5912a7855f4aa29d3882cbc13838a3ddb7e261648807def1c24491cda8c00ef",
            "959d913335cd3f2ef30e15433318e7babb6b628ea8c7820d7ca628d67a01846",
            "c75a9f8ee15a6d44513dae60c53ca4cfd9bdda4ac08d0b22e"
        )
    );
    assert_eq!(start.x2, highseed::STREAM_START_642F60_X2_SOURCE);
    assert_eq!(
        sha256(&start.x2),
        "64eec98b6cf193a8c6f413af4eb1ed6bb4d4f06cb6c343284c46c9ce85ebde6f"
    );

    assert_eq!(
        highseed::builder6388f0_recover_stream_start_out0_seed_from642f60_x0(&start.x0, &t).unwrap(),
        out0_seed
    );
    assert_eq!(
        highseed::builder6388f0_recover_stream_start_out1_seed_from642f60_x1(&start.x1, &t).unwrap(),
        out1_seed
    );
}

/// kit `testBuilder6421c0HighSeedHelpersMatchPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L747-861).
#[test]
fn builder6421c0_high_seed_helpers() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let x0: Vec<u8> = (0..80).map(|index| ((index * 11 + 7) & 0xff) as u8).collect();
    let x1: Vec<u8> = (0..88).map(|index| ((index * 13 + 3) & 0xff) as u8).collect();
    let x2: Vec<u8> = (0..88).map(|index| ((index * 17 + 5) & 0xff) as u8).collect();
    let output = highseed::builder6421c0_output_words(&x0, &x1, &x2, 0x0123456789abcdef, &t).unwrap();
    assert_eq!(
        output,
        vec![
            0xdbc1c7c6, 0x2033fae4, 0xdbba46f4, 0x51d8e106, 0x06acf332, 0x8bad4314, 0xb5c9adb4,
            0x54da2609, 0x4ea01830, 0x00da7af7, 0x207da04a, 0xbaa6764d, 0x0e8a02aa, 0x41fc4b04,
            0x299ed743, 0xa8d7eaf6, 0x088c1fe0, 0x83d47285, 0x9d6a5499, 0x640e0bb3, 0x799af52d,
            0xa7308434,
        ]
    );
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "613beae0326a26de5b07c1bca00a356d6d497e222d8cfe34e4cb84ae26be14a8"
    );

    let source70: Vec<u8> = (0..70).map(|index| ((index * 19 + 9) & 0xff) as u8).collect();
    let high_x0 = highseed::builder6388f0_high_seed_x0_source_from5bcf98_output(&source70, &t).unwrap();
    assert_eq!(
        hex(&high_x0),
        concat!(
            "3a2dfcb318b7344cf51e5b96b0815468383cdb6a10f727cbc3e953daf6513562",
            "261b8e3aeda4df7071901e85677af85bdc709a418e53b9f15fe323f7937078",
            "c9120920c1c2928a95ed01e2731e03cdea"
        )
    );
    let high_output = highseed::builder6421c0_output_words(
        &high_x0,
        &highseed::HIGH_SEED_6421C0_X1_SOURCE,
        &highseed::HIGH_SEED_6421C0_X2_SOURCE,
        highseed::HIGH_SEED_6421C0_SCALAR,
        &t,
    )
    .unwrap();
    assert_eq!(
        high_output,
        vec![
            0x808a1855, 0x783ef112, 0x27aa1861, 0x18f09114, 0x3d286c05, 0x83db42f3, 0x57a5bb1e,
            0x208b0c9e, 0x64223ac2, 0x97cc4564, 0x0ef21945, 0xe627151f, 0xd8178670, 0xdba71039,
            0xdcae32d6, 0x26e1b50b, 0x8fb269cb, 0x6bcc9065, 0x9d1492af, 0x94fe8376, 0xd8178670,
            0xdba71039,
        ]
    );
    assert_eq!(
        sha256(&pack_u32_le(&high_output)),
        "cbe9227ccdfa92d4e23f2bd4f11e67cc0ef66de6b945f812bd0c2d213b7afd93"
    );

    let second_source70: Vec<u8> = (0..70).map(|index| ((index * 23 + 4) & 0xff) as u8).collect();
    let high_seeds = highseed::builder6388f0_high_seed_stream_start_seeds_from5bcf98_outputs(
        &source70, &second_source70, None, None, None, &t,
    )
    .unwrap();
    assert_eq!(high_seeds.out0, pack_u32_le(&high_output));
    assert_eq!(
        hex(&high_seeds.out1),
        concat!(
            "8d85f2399a367b70a58ac991bc36c7604a45a128e91d968a99cb7b3dd020a2b5",
            "f7b82949a78159b9b962810cbd3e57fd708617d83910a7dbd632aedc0bb5e126",
            "cb69b28f6590cc6baf92149d7683fe94708617d83910a7db"
        )
    );
    let mut combined = high_seeds.out0.clone();
    combined.extend(&high_seeds.out1);
    assert_eq!(
        sha256(&combined),
        "bd9129fe22f4ab7d395e31c3e369cfc6cc62b3109df0b4ec7d82cffa5117d0e2"
    );

    let scalar_window = unhex(concat!(
        "3b588dd68f20da5f883993332cabcda6576645712cdd039d0a8195f4b1c0b52e",
        "0000000000000000000000000000000000000000000000000000000000000000000000000000"
    ));
    let generator_point = unhex(concat!(
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
        "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
    ));
    let p256_outputs = highseed::builder5bcf98_p256_outputs(&scalar_window, &generator_point).unwrap();
    assert_eq!(
        sha256(&p256_outputs.x_output70),
        "fb123cffe9d4e8e9e27f9c5251cdcd24a9f513c43d130a07925b8ceee0fe75d0"
    );
    assert_eq!(
        sha256(&p256_outputs.y_output70),
        "5f6979616cb8bbeb57dac5b362653508b597e8292b6bc0a3defc0787cc4737ca"
    );
    assert_eq!(
        hex(&p256_outputs.x_output70),
        concat!(
            "a1e69a746868223565f55b036dcb352ac7ad64457d8304d2a015b5ee90942023",
            "0000000000000000000000000000000000000000000000000000000000000000000000000000"
        )
    );
    assert_eq!(
        hex(&p256_outputs.y_output70),
        concat!(
            "3ac85ab9f4754fade9fb79588ec4d48ef3af4d916151ad0477d595de947261ea",
            "0000000000000000000000000000000000000000000000000000000000000000000000000000"
        )
    );

    let p256_high_seeds = highseed::builder6388f0_high_seed_stream_start_seeds_from_scalar_p256(
        &scalar_window, &generator_point, None, None, None, &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&p256_high_seeds.out0),
        "fbc744031431d9fda2ceed80266ee2dfefb9a55e585ea5bbc2666a144379f042"
    );
    assert_eq!(
        sha256(&p256_high_seeds.out1),
        "f9b223b45fe8ec5687cdcbd18218714f4b261938a4befb37e0ced7b42d012289"
    );
}

/// kit `testBuilder6388f0FirstPairStreamSeedsFrom5bcf98OutputsMatchPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L863-1069) — in-scope assertions only.
#[test]
fn builder6388f0_first_pair_stream_seeds_from5bcf98_outputs() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let row0_first_output70: Vec<u8> = (0..70).map(|index| ((index * 19 + 9) & 0xff) as u8).collect();
    let row0_second_output70: Vec<u8> = (0..70).map(|index| ((index * 23 + 4) & 0xff) as u8).collect();
    let row59_first_output70: Vec<u8> = (0..70).map(|index| ((index * 41 + 6) & 0xff) as u8).collect();
    let row59_second_output70: Vec<u8> = (0..70).map(|index| ((index * 43 + 7) & 0xff) as u8).collect();

    let high_seeds = highseed::builder6388f0_first_pair_high_seed_stream_start_seeds_from5bcf98_outputs(
        &row0_first_output70,
        &row0_second_output70,
        &row59_first_output70,
        &row59_second_output70,
        None,
        None,
        None,
        &t,
    )
    .unwrap();
    assert_eq!(
        hex(&high_seeds.row0.out0),
        concat!(
            "55188a8012f13e786118aa271491f018056c283df342db831ebba5579e0c8b20",
            "c23a22646445cc974519f20e1f1527e6708617d83910a7dbd632aedc0bb5e126",
            "cb69b28f6590cc6baf92149d7683fe94708617d83910a7db"
        )
    );
    assert_eq!(
        hex(&high_seeds.row59.out1),
        concat!(
            "59a1e91687277937b599fe911da5ba4ac96f50c6c5cebb77a0a54a7387248dad",
            "a8360f4c149e5731729ffa13bd3e57fd708617d83910a7dbd632aedc0bb5e126",
            "cb69b28f6590cc6baf92149d7683fe94708617d83910a7db"
        )
    );

    let seeds = highseed::builder6388f0_first_pair_stream_seeds_from5bcf98_outputs(
        &(0..88).map(|index| ((index * 3 + 1) & 0xff) as u8).collect::<Vec<u8>>(),
        &(0..88).map(|index| ((index * 5 + 2) & 0xff) as u8).collect::<Vec<u8>>(),
        &(0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect::<Vec<u8>>(),
        &row0_first_output70,
        &row0_second_output70,
        &row59_first_output70,
        &row59_second_output70,
        &(0..70).map(|index| ((index * 29 + 1) & 0xff) as u8).collect::<Vec<u8>>(),
        &(0..70).map(|index| ((index * 31 + 2) & 0xff) as u8).collect::<Vec<u8>>(),
        &(0..0x11a).map(|index| ((index * 37 + 3) & 0xff) as u8).collect::<Vec<u8>>(),
        2,
        None,
        None,
        None,
        &t,
    )
    .unwrap();
    assert_eq!(seeds.row0_out0, high_seeds.row0.out0);
    assert_eq!(seeds.row0_out1, high_seeds.row0.out1);
    assert_eq!(seeds.row59_out0, high_seeds.row59.out0);
    assert_eq!(seeds.row59_out1, high_seeds.row59.out1);
    assert_eq!(seeds.null_attempts, 2);

    let starts = highseed::builder6388f0_first_pair642f60_stream_starts_from_seeds(&seeds, None, &t).unwrap();
    assert_eq!(
        sha256(&starts.row0.x0),
        "6cf7247e7ccce409f16a110e66e29d319ef41e9dc20b12951b97f9d3a996166a"
    );
    assert_eq!(
        sha256(&starts.row59.x1),
        "3e9f58eae41463a5ddc7b08db2cd537e8bff419108e5d5b9c1e6fd6e6bbfcc9d"
    );
    // Out of scope (later stages): deriveFrom6388f0FirstPairStreamSeeds, the entrySource /
    // entropy / sensor-point stream-seed variants below this point in the Swift test.
}

/// kit `testBuilder6388f0CallerContextResourcesMatchPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L1406-1467).
#[test]
fn builder6388f0_caller_context_resources() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let shared = highseed::builder6388f0_shared_context_from_bundle(&t).unwrap();
    assert_eq!(shared.len(), 0x520);
    assert_eq!(
        sha256(&shared),
        "ef3f9995fade12005f0f4410bc1ffa23a03412851e31503042e149da302f2dac"
    );
    assert_eq!(
        hex(&shared[..32]),
        "21ed7e8fc9862976ac50b4cb1e31a91f30fa05c70682ac26bc7db76219fd1d35"
    );
    assert_eq!(
        hex(&shared[shared.len() - 32..]),
        "4a411f4b3cf073011ded82b57188f50f977c1b57e3fbc2051c7577ffbb255fc9"
    );

    let loop_tables = highseed::builder6388f0_caller_loop_tables_from_bundle(&t).unwrap();
    assert_eq!(loop_tables.first.len(), 59 * 0x58);
    assert_eq!(loop_tables.second.len(), 59 * 0x58);
    assert_eq!(
        sha256(&loop_tables.first),
        "08e40f696924cbde7e31db9c9102d071f1d17a0a60a17f58768b01f5ec067d35"
    );
    assert_eq!(
        hex(&loop_tables.first[..32]),
        "db7c3afca9d52301c0064bb894889a5e8c592cf871412afd4a411f5dad1b1a64"
    );
    assert_eq!(
        hex(&loop_tables.first[loop_tables.first.len() - 32..]),
        "4a411f4b3cf073011ded82b57188f50f977c1b57e3fbc2051c7577ffbb255fc9"
    );
    assert_eq!(
        sha256(&loop_tables.second),
        "6471d5ae1bc99ec976683bc2e568af44b58f900cf0242439b9626d69cb54ec65"
    );
    assert_eq!(
        hex(&loop_tables.second[..32]),
        "bfcf00c8b7ecf353653481454ad35d2054aae79357422e128e2024529ca00ce0"
    );
    assert_eq!(
        hex(&loop_tables.second[loop_tables.second.len() - 32..]),
        "fccee0a880f009f9e121349c190bb74368363d144a33c2dd9112d90aeb6e5f5d"
    );

    let context = highseed::builder6388f0_caller_context_from_loop_tables(&loop_tables, &t).unwrap();
    assert_eq!(context.len(), 0x2d58);
    assert_eq!(
        sha256(&context),
        "f5059c7c440707b8bdc08c309540e629e109e78941406442a7d189f5c23fbe5f"
    );
    assert_eq!(
        hex(&context[..32]),
        "21ed7e8fc9862976ac50b4cb1e31a91f30fa05c70682ac26bc7db76219fd1d35"
    );
    assert_eq!(
        hex(&context[context.len() - 32..]),
        "fccee0a880f009f9e121349c190bb74368363d144a33c2dd9112d90aeb6e5f5d"
    );
    assert_eq!(&context[0x4c8..0x4c8 + loop_tables.first.len()], &loop_tables.first[..]);
    assert_eq!(&context[0x1910..0x1910 + loop_tables.second.len()], &loop_tables.second[..]);
    assert_eq!(highseed::builder6388f0_caller_context_from_bundle(&t).unwrap(), context);
}
