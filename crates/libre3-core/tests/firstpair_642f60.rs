//! FirstPairSourceSliceTests.swift — 642f60 caller-layer vectors, ported 1:1 (Stage C of the
//! 6388f0 first-pair builder port). Tables load from $LIBRE3_TABLES_DIR; the suite skips
//! itself when the variable is unset so CI stays green without table bytes.

use libre3_core::vm::{caller642, highseed, FirstPairTables};

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

/// kit `packUInt32LE` (FirstPairSourceSliceTests.swift L4492-4501).
fn pack_u32_le(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// kit `packUInt64LE` (FirstPairSourceSliceTests.swift L4481-4490).
fn pack_u64_le(words: &[u64]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// kit `testBuilder642f60InitialStagesMatchPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L1469-2010).
#[test]
fn builder642f60_initial_stages_match_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let x1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let x0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let x2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let output: Vec<u8> = (0..88).map(|index| ((index * 17 + 4) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 19 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0fedcba987654321;

    let sp2a8 = caller642::builder642f60_stage_sp2a8_words_from_x1(&x1, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp2a8)),
        "8f828a7da4493c59a03f2e57e5040ba00114d22495ebc08a9bb71d06bdc115d5"
    );
    assert_eq!(&sp2a8[..4], &[0x4a545152, 0xeb8ceacd, 0x6ee8542c, 0x8f614dc2]);
    assert_eq!(&sp2a8[sp2a8.len() - 4..], &[0x9da005cb, 0x375c139a, 0xc085fb32, 0x8542933b]);

    let first_workspace =
        caller642::builder642f60_first64bd0c_workspace_from_x1(&x1, &sp2a8, &t).unwrap();
    assert_eq!(first_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&first_workspace),
        "71999b7359489904962a33e87d4f7d7b045b1d59b6056d2031c3039df85b9003"
    );
    assert_eq!(
        hex(&first_workspace[..32]),
        "086cb880065cb85808ad97f53a270ada48ff6b096bd1aa3ea0268718ad4e3ddf"
    );
    assert_eq!(
        hex(&first_workspace[first_workspace.len() - 32..]),
        "0c413ff42dbed2247aaebca271a82607684e5068580db306084c720cc23e1703"
    );

    let arg0_words = caller642::builder64bd0c_arg0_u64_words(&arg0, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&arg0_words)),
        "959177503b2023288b94d3167c99cd47f8e7fc6f3449aa7f20f3a461d18251f9"
    );
    let updated64bd0c =
        caller642::builder64bd0c_workspace_after_update(&arg0_words, scalar, &first_workspace, &t)
            .unwrap();
    assert_eq!(
        sha256(&updated64bd0c),
        "bb1551ac44a49ebd22583cb1eb07bc918a8b522172e049092b8596fc7ab2bf29"
    );
    let output64bd0c = caller642::builder64bd0c_final_u32_words(&updated64bd0c, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output64bd0c)),
        "5d3ef1f04a3b810f276531edc05b4901e6ec17dca864503d7ee38c1dae9c5a6e"
    );
    assert_eq!(
        caller642::builder64bd0c_output_words(&arg0, scalar, &first_workspace, &t).unwrap(),
        output64bd0c
    );

    let sp1f8 = caller642::builder642f60_stage_sp1f8_words_from_x0(&x0, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp1f8)),
        "3fd4edb09dc91b6ddc9ad925775420a371151828eac3ee32b1e0e92932651e57"
    );
    assert_eq!(&sp1f8[..4], &[0x69e6983e, 0x52adf2b0, 0x9c3e0b1c, 0xce05e1cd]);
    assert_eq!(&sp1f8[sp1f8.len() - 4..], &[0xf8aada36, 0x50d2d9ef, 0x8d23cbd4, 0x222c26ac]);

    let sp300_from_first_output = caller642::builder642f60_stage_sp300_words_from64bd0c_output(
        &pack_u32_le(&output64bd0c),
        &t,
    )
    .unwrap();
    let second_workspace = caller642::builder642f60_second64bd0c_workspace(&sp1f8, &sp300_from_first_output, &t)
        .unwrap();
    assert_eq!(second_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&second_workspace),
        "ca10191ddcf021d37fe567b3840c74edd67cb2d8eb6f93e062ede78437ac75cd"
    );
    assert_eq!(
        hex(&second_workspace[..32]),
        "b471e2fe79a9a301ac050344d103e81f5a31686d8112adeef233681d13dc7244"
    );
    assert_eq!(
        hex(&second_workspace[second_workspace.len() - 32..]),
        "89dee183dabe10816ffdfd9fdd8fb8a0eac33fd506ace277084c720cc23e1703"
    );

    let second_output =
        caller642::builder64bd0c_output_words(&arg0, scalar, &second_workspace, &t).unwrap();
    let sp250_from_second_output = caller642::builder642f60_stage_sp250_words_from64bd0c_output(
        &pack_u32_le(&second_output),
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp250_from_second_output)),
        "589183582eca59a369e4539a9af3447aa94a9254685aa95ec7b6425ae70cb98b"
    );

    let third_workspace = caller642::builder642f60_third64bd0c_workspace_from_x2(&x2, &t).unwrap();
    assert_eq!(third_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&third_workspace),
        "eb364923c9af8081354fdb867b420b25a7671cd6ad29be6a59d6f42575c64b78"
    );
    assert_eq!(
        hex(&third_workspace[..32]),
        "0c104df2b29622434c82979a125bceab958df21121cfd218fe9b83f1886f08e8"
    );
    assert_eq!(
        hex(&third_workspace[third_workspace.len() - 32..]),
        "8c4da37cf84d2509e8b7b9e083eec7ba482c27a7e28df965084c720cc23e1703"
    );
    let third_output =
        caller642::builder64bd0c_output_words(&arg0, scalar, &third_workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&third_output)),
        "eb48d72aa8e50042313ee7997d44bac8913c326beaf560c1526172eb65aab723"
    );
    let sp148_from_third_output = caller642::builder642f60_stage_sp148_words_from64bd0c_output(
        &pack_u32_le(&third_output),
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp148_from_third_output)),
        "dc2f907533997ec0281f478799f738cedb091dd4823583aca78fb29b735ab721"
    );
    let fourth_workspace =
        caller642::builder642f60_fourth64bd0c_workspace(&sp148_from_third_output, &t).unwrap();
    assert_eq!(fourth_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&fourth_workspace),
        "4e05b824f913c717b12674a3aab7228c7da82df22939cf9d5aaa17067e2fff63"
    );
    assert_eq!(
        hex(&fourth_workspace[..32]),
        "0cffa70f53d9c80d14057382fc257a18b1b8a01412c2938bd8d3ffdcc1cb8d6a"
    );
    assert_eq!(
        hex(&fourth_workspace[fourth_workspace.len() - 32..]),
        "a04db05d65de38a4488b463bf601890618d225871af87a14084c720cc23e1703"
    );
    let fourth_output =
        caller642::builder64bd0c_output_words(&arg0, scalar, &fourth_workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&fourth_output)),
        "eb1a69a95b8180f875b7ed6f2e5445b5144eac396c6d2f736dfd52472971aab7"
    );
    let spf0_from_fourth_output = caller642::builder642f60_stage_spf0_words_from64bd0c_output(
        &pack_u32_le(&fourth_output),
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&spf0_from_fourth_output)),
        "5e40c800bafaf359c2f85016931771aab6bed679314cd57d6b93825ff824b9e1"
    );

    let mid_spa90 = caller642::builder642f60_mid_stage_spa90_words_from_x0(&x0, &t).unwrap();
    assert_eq!(mid_spa90.len(), 22);
    assert_eq!(
        sha256(&pack_u64_le(&mid_spa90)),
        "aac4238d8bbb4b8919022d2808a5f78b6b8dfe8b2e65f74bf02e8e4e9c8e8589"
    );
    let mid_sp40 = caller642::builder642f60_mid_stage_sp40_words_from_spa90(&mid_spa90, &t).unwrap();
    assert_eq!(mid_sp40.len(), 44);
    assert_eq!(
        sha256(&pack_u32_le(&mid_sp40)),
        "1504c65189554c49926041804dd19ae228fba4c1e385c12314f75bbec25bc375"
    );
    assert_eq!(&mid_sp40[..4], &[0x35529dd1, 0x72007bb1, 0xb3df1669, 0x5db9b4dc]);
    assert_eq!(&mid_sp40[mid_sp40.len() - 4..], &[0x6c8a982f, 0x5f4c42a3, 0x4f51060d, 0x99fb2d87]);

    let mid_streams = caller642::builder642f60_mid_stage_streams_from_context_spf0(
        &highseed::builder6388f0_shared_context_from_bundle(&t).unwrap(),
        &spf0_from_fourth_output,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&mid_streams.spa90_words)),
        "4612a77fcc1862e03e8f9071ed96021fd8278d9e4b759902f9d708d93f015518"
    );
    assert_eq!(
        sha256(&pack_u64_le(&mid_streams.sp510_prefix)),
        "bd332ed27e750a48e1253148b35979fd033779dd54687278031cdf38487ec1b4"
    );
    assert_eq!(
        sha256(&pack_u64_le(&mid_streams.sp880_words)),
        "1060d4058c115741df2d3c32ecc5b72d07f4d51a0feab492c54d3c05cd9cac3b"
    );
    assert_eq!(
        sha256(&pack_u64_le(&mid_streams.sp9e0_prefix)),
        "dc97df1d2bb966138bd3636c12fd741949ed0b64be4ad80d03846a375a5fc19c"
    );
    let mid_sp670 = caller642::builder642f60_mid_stage_sp670_words(
        &mid_streams.spa90_words,
        &mid_streams.sp510_prefix,
        &mid_streams.sp880_words,
        &mid_streams.sp9e0_prefix,
    )
    .unwrap();
    assert_eq!(mid_sp670.len(), 44);
    assert_eq!(
        sha256(&pack_u64_le(&mid_sp670)),
        "7022e51b4a7f4e8cf3efaf9a22df2490fe16cd52af14345dda8bdfba274913c7"
    );
    let mid_sp40_b = caller642::builder642f60_mid_stage_spa90_sp880_from_sp40(&mid_sp40, &t).unwrap();
    assert_eq!(mid_sp40_b.side_init, 0x9b3fe2a5f2a431c6);
    assert_eq!(
        sha256(&pack_u64_le(&mid_sp40_b.spa90_words)),
        "704129f7d2f099279c16bdc46558950398e440a5696c0d3ce486af146f571c16"
    );
    assert_eq!(
        sha256(&pack_u64_le(&mid_sp40_b.sp880_prefix)),
        "0c2f3db5097284b92a2c9e9c657723251e88d0404d5557a2082a954bb479b6c2"
    );
    let mid_static = caller642::builder642f60_mid_stage_static_sp9e0_sp7d0(mid_sp40_b.side_init, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&mid_static.sp9e0_words)),
        "940228bc7962e67ba2baac5819a3e5fd6f97d4040f6a36f4a962e9d7e71165b8"
    );
    assert_eq!(
        sha256(&pack_u64_le(&mid_static.sp7d0_prefix)),
        "2f9bd217712c0037a4bb0dc7f6428debe9f47d104e394b4aee09c39bf4e3b13f"
    );
    let mid_sp510 = caller642::builder642f60_mid_stage_sp510_words(
        &mid_sp40_b.spa90_words,
        &mid_sp40_b.sp880_prefix,
        &mid_static.sp9e0_words,
        &mid_static.sp7d0_prefix,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&mid_sp510)),
        "0ec28196c798b8373f978ff6f23daca738c2743fcb3bd6a815fa80e41517e0a1"
    );
    let fifth_workspace =
        caller642::builder642f60_mid_fifth64bd0c_workspace(&mid_sp670, &mid_sp510).unwrap();
    assert_eq!(fifth_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&fifth_workspace),
        "b82a12c1bc5b397f1408b510140ebaf5fc4ceedaedf8b39ae1b83cb406ae246d"
    );
    let fifth_output =
        caller642::builder64bd0c_output_words(&arg0, scalar, &fifth_workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&fifth_output)),
        "ca6c0bf7532688d60636480d3ff0baed7a7bbb9c14b6f5c102716808baf8551f"
    );
    let sp1a0_from_fifth_output = caller642::builder642f60_stage_sp1a0_words_from64bd0c_output(
        &pack_u32_le(&fifth_output),
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp1a0_from_fifth_output)),
        "faf7f3a4cb372cf5e9c5428cdd2cac3737321290d618c099447201017a2065bd"
    );
    let sixth_streams = caller642::builder642f60_sixth_streams_from_sp1a0(&sp1a0_from_fifth_output, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&sixth_streams.spa90_words)),
        "e9c19157950aad3351142adeb2f1fed3804280bf27f8d8b3001f65f5448d1780"
    );
    assert_eq!(
        sha256(&pack_u64_le(&sixth_streams.sp670_prefix)),
        "d59a1bea1b4b21a4b33acfc7e6d1ebc349e357a447a4c82fe30c4116ce76075f"
    );
    assert_eq!(
        sha256(&pack_u64_le(&sixth_streams.sp880_words)),
        "c908be53c01f80bf8c7e4f487a4fecf5fc989aeb62a5a49de086021d946025de"
    );
    assert_eq!(
        sha256(&pack_u64_le(&sixth_streams.sp510_prefix)),
        "f2ed82df4b86e6610cddb46a12ca466b28cb7fb8b51e8d48b398729af7b77b81"
    );
    let sixth_workspace = caller642::builder642f60_sixth64bd0c_workspace(
        &sixth_streams.spa90_words,
        &sixth_streams.sp670_prefix,
        &sixth_streams.sp880_words,
        &sixth_streams.sp510_prefix,
    )
    .unwrap();
    assert_eq!(sixth_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&sixth_workspace),
        "f3f92c8a7b28e42606d05893406c61b98ba68869d51dd5886326b27137fdec19"
    );
    let sixth_output =
        caller642::builder64bd0c_output_words(&arg0, scalar, &sixth_workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sixth_output)),
        "d5fb1d1b7a140d9fb9530ab11c3e748b7c2cb6a98caa11dcb29027b59aef43ef"
    );

    let out0_source = caller642::builder642f60_out0_source_words(
        &pack_u32_le(&sixth_output),
        &highseed::builder6388f0_shared_context_from_bundle(&t).unwrap(),
        &sp250_from_second_output,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&out0_source)),
        "a786ef481ee4d3a137fb62b582fd2a0a25b067aa747b8e119e6dccb64f3d7931"
    );
    let out0 = caller642::builder642f60_out0_words_from_source(&out0_source, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&out0)),
        "ec26506de5e68c3fd206e6ec3d809c9425c8a48e2f7033bfab37f639b2d39ee6"
    );

    let seventh_source = caller642::builder642f60_seventh_source_words(
        &sp250_from_second_output,
        &highseed::builder6388f0_shared_context_from_bundle(&t).unwrap(),
        &out0,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&seventh_source)),
        "a2e9303b9b5b9b3d6724efd2270260e25499279966cb839934468d34a4a7a3f2"
    );
    let seventh_sp148 =
        caller642::builder642f60_seventh_stage_sp148_words_from_source(&seventh_source, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&seventh_sp148)),
        "95259f0656efd4da682c6d44b3e75fbd5e0255b2cf4deb18fda24c314361f960"
    );
    let seventh_streams = caller642::builder642f60_seventh_streams(&sp1a0_from_fifth_output, &seventh_sp148, &t)
        .unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&seventh_streams.sp670_words)),
        "c994283392c158405962b94a3a3d1151c00a1697dda6078880de0549c0ba0ba4"
    );
    assert_eq!(
        sha256(&pack_u64_le(&seventh_streams.spa90_prefix)),
        "ad186b4c6c314109cc76496bb2cfd20d4c9ce4b6cb46eabe2fa8bfedece4370d"
    );
    assert_eq!(
        sha256(&pack_u64_le(&seventh_streams.sp510_words)),
        "9fb2e0f00e7464c47a64ffaa0e82e40b469579a7a95b8de1476b5e219788e21f"
    );
    assert_eq!(
        sha256(&pack_u64_le(&seventh_streams.sp880_prefix)),
        "1f9b41793357ab8779a27573de0b8798a97fc3873a9936359e3fd218d7a3d410"
    );
    let seventh_sp9e0 = caller642::builder642f60_seventh_sp9e0_words(
        &seventh_streams.sp670_words,
        &seventh_streams.spa90_prefix,
        &seventh_streams.sp510_words,
        &seventh_streams.sp880_prefix,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&seventh_sp9e0)),
        "e37413004fb514f85c75729da018412b5c415c9380bda9d0b719fea6db31f3a6"
    );
    assert_eq!(&seventh_sp9e0[..4], &[0x5f6dd1e1, 0xabcb1928, 0xa1967cd8, 0xfeb29d92]);
    assert_eq!(
        &seventh_sp9e0[seventh_sp9e0.len() - 4..],
        &[0xdcab123e, 0x4b8612e8, 0x02abe7d2, 0x7a2fefdd]
    );
    let seventh_spa90 =
        caller642::builder642f60_seventh_spa90_words_from_sp300(&sp300_from_first_output, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&seventh_spa90)),
        "d6b753435f42d5c24fbb383ff1b3ee1b5fd1aad791a4ef8692902747f0e72997"
    );
    let seventh_sp7d0 =
        caller642::builder642f60_seventh_sp7d0_words_from_spa90(&seventh_spa90, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&seventh_sp7d0)),
        "3e59a9f321203b7f35d979f45eebfea1886d4cfad5949626bc2664bcd875c748"
    );
    let seventh_source44 = caller642::builder642f60_seventh_source44_words(
        &seventh_sp9e0,
        &highseed::builder6388f0_shared_context_from_bundle(&t).unwrap(),
        &seventh_sp7d0,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&seventh_source44)),
        "0dc439079188a120ee22095c559c1cc42fff70dd25c14986b09348d215f27b6f"
    );
    let seventh_sp40 =
        caller642::builder642f60_seventh_sp40_words_from_source44(&seventh_source44, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&seventh_sp40)),
        "3b9636afe859f7dac63be47e1b5f8ba67ac0b08c025b311d0a155aba9ddd77aa"
    );
    let seventh_workspace = caller642::builder642f60_seventh64bd0c_workspace(&seventh_sp40, &t).unwrap();
    assert_eq!(seventh_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&seventh_workspace),
        "622ff09e3ef098d358adda7b57e061abd86c5a5cbc7d5c3f25452330f75ef9cb"
    );
    let seventh_output =
        caller642::builder64bd0c_output_words(&arg0, scalar, &seventh_workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&seventh_output)),
        "3a822a7f1a90cf5cf5ae217a07640d66269e95f0be53dbbb607f486279ecbc9c"
    );
    let out1 =
        caller642::builder642f60_out1_words_from64bd0c_output(&pack_u32_le(&seventh_output), &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&out1)),
        "3e6436605d188c9ced00cd0accd72edf521d96eec87a3ef9527c911c19cb5b0f"
    );
    assert_eq!(&out1[..4], &[0xce8a9be4, 0xc663f93d, 0xf77e4c6a, 0x657aca3e]);
    assert_eq!(&out1[out1.len() - 4..], &[0x38cb4c20, 0xd773b033, 0x6e86dad6, 0x6a737805]);

    let eighth_streams = caller642::builder642f60_eighth_streams(&sp2a8, &x2, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&eighth_streams.spa90_words)),
        "c327b7a453a17eb81062883abcbe55d935200cfe912ed92dbbee1734b5afc5d1"
    );
    assert_eq!(
        sha256(&pack_u64_le(&eighth_streams.sp670_prefix)),
        "e66cf3bd22a351f438ef25f0489a7e6d7623002d4de4700fa8f665b0355ce8a9"
    );
    assert_eq!(
        sha256(&pack_u64_le(&eighth_streams.sp880_words)),
        "bbc6d8b43127645cb932c82e7be91e4bae25b6daa6dafd89a42cfa8d60976f92"
    );
    assert_eq!(
        sha256(&pack_u64_le(&eighth_streams.sp510_prefix)),
        "e9674687f3fa00f954d928d71797432cb57983150b2428f2dc6a04a020ac2980"
    );
    let eighth_workspace = caller642::builder642f60_eighth64bd0c_workspace(
        &eighth_streams.spa90_words,
        &eighth_streams.sp670_prefix,
        &eighth_streams.sp880_words,
        &eighth_streams.sp510_prefix,
    )
    .unwrap();
    assert_eq!(eighth_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&eighth_workspace),
        "17b5fde88b8e761b44de016710946bbd52847db3f194fb548d424727daf94347"
    );
    let eighth_output =
        caller642::builder64bd0c_output_words(&arg0, scalar, &eighth_workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&eighth_output)),
        "42e92eaf7ec2510b60090919242b7659adb855a220e3d208895a163f96e6775b"
    );
    let out2 =
        caller642::builder642f60_out2_words_from64bd0c_output(&pack_u32_le(&eighth_output), &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&out2)),
        "6742594d34d56c1f0c72ee89012f929548a832844b8b609775e9985fec3746b1"
    );
    assert_eq!(&out2[..4], &[0x317a551b, 0x1951c2c8, 0x696bf2c7, 0xfeedf1a6]);
    assert_eq!(&out2[out2.len() - 4..], &[0xb927d2dd, 0xd529e3e0, 0x5dcdae1c, 0x0f6c6f5a]);

    let affine_vectors: [(&str, Vec<u32>, &str); 5] = [
        (
            "sp300",
            caller642::builder642f60_stage_sp300_words_from64bd0c_output(&output, &t).unwrap(),
            "230a5a9f7600e5c7f4edf9a41de45b802c0e86c9f0d34d6ec851fc9dfe7a3d01",
        ),
        (
            "sp250",
            caller642::builder642f60_stage_sp250_words_from64bd0c_output(&output, &t).unwrap(),
            "06a9ce49a16f0594114a591bf8f10adf630e65f39baab23042372d9b7f7b457a",
        ),
        (
            "sp148",
            caller642::builder642f60_stage_sp148_words_from64bd0c_output(&output, &t).unwrap(),
            "814f15ddb3e704703b4cdfbd670e848007b58858cce69cce3c03ab16c1b96465",
        ),
        (
            "spf0",
            caller642::builder642f60_stage_spf0_words_from64bd0c_output(&output, &t).unwrap(),
            "89f74ab7564faa6ec3ce6ff27117df2948ca38e27362c58ba68ed05b53e30039",
        ),
        (
            "sp1a0",
            caller642::builder642f60_stage_sp1a0_words_from64bd0c_output(&output, &t).unwrap(),
            "b1364b48497efd7082324483daab477fc7dba684c5673c2b80f109fbbbe73e0a",
        ),
    ];
    for (name, words, hash) in affine_vectors {
        assert_eq!(words.len(), 22, "{name}");
        assert_eq!(sha256(&pack_u32_le(&words)), hash, "{name}");
    }
}

/// kit `testBuilder642f60OutputsWithBundledContextMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L2011-2054).
#[test]
fn builder642f60_outputs_with_bundled_context_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let x1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let x0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let x2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();

    let result =
        caller642::builder642f60_outputs_from_bundled_context(&x0, &x1, &x2, &t).unwrap();
    assert_eq!(result.out0.len(), 88);
    assert_eq!(result.out1.len(), 88);
    assert_eq!(result.out2.len(), 88);
    assert_eq!(
        sha256(&result.out0),
        "e4e4bc44d23db2b617f3d9a3f84a9dc1a6767d4d242a4c52b8427c587148a813"
    );
    assert_eq!(
        sha256(&result.out1),
        "f6e027253992cc3f10bc117332271b9c97a5e6570be183b0808309bcc759bfd2"
    );
    assert_eq!(
        sha256(&result.out2),
        "9c0ec2ac6f581933c3e457e0c4267507a575e1280caf5cbe09b962f265307e92"
    );

    let mut combined = result.out0.clone();
    combined.extend(&result.out1);
    combined.extend(&result.out2);
    assert_eq!(
        sha256(&combined),
        "7b97c74090a4e4e2c720abf39d86a1343ba5e1961f206e227b3e06a531bc51ff"
    );

    let explicit_context = caller642::builder642f60_outputs(
        &x0,
        &x1,
        &x2,
        &highseed::builder6388f0_shared_context_from_bundle(&t).unwrap(),
        &t,
    )
    .unwrap();
    assert_eq!(explicit_context, result);
}
