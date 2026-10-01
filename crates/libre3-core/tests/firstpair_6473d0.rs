//! FirstPairSourceSliceTests.swift — 64c524/6473d0/64cd40 caller-layer vectors, ported 1:1
//! (Stage D of the 6388f0 first-pair builder port). Tables load from $LIBRE3_TABLES_DIR; the
//! suite skips itself when the variable is unset so CI stays green without table bytes.
//!
//! Out of scope here (next stage): the 64cd40 call-state builders asserted by
//! testBuilder6388f0First64cd40CallState… (FirstPairSourceSliceTests.swift L2197+).

use libre3_core::vm::{caller6473d0, highseed, lowseed, FirstPairTables};

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

/// kit `testBuilder6473d0OutputsWithBundledContextMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L2055-2103).
#[test]
fn builder6473d0_outputs_with_bundled_context_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();

    let result = caller6473d0::builder6473d0_outputs_from_bundled_context(
        &in0,
        &in1,
        &in2,
        Some(&out0_seed),
        Some(&out1_seed),
        &t,
    )
    .unwrap();

    let vectors: [(&str, &Vec<u8>, &str); 8] = [
        ("in0_after", &result.in0_after, "9692c8145a24dcadc1fd23963c583512c8aebf55dc7c68ad677cb8f53f2117ea"),
        ("in1_after", &result.in1_after, "a4a1bb98f66e3d53a51c810379507e4a1f856bf51be0d007e10d5b3afc90252b"),
        ("in2_after", &result.in2_after, "8eb586c217d306dbde11f9301ab67d009e8dba5414bcebe90944e8542082edee"),
        ("out0", &result.out0, "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd"),
        ("out1", &result.out1, "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8"),
        ("out2", &result.out2, "c5e3ec0675df26d11bd8390e34135652ad6b530fb8e003151b44cf1dfce6e169"),
        ("out3", &result.out3, "d1486d791a35e129933d31bab4e814a0cdcd3db8c3b4895950882cba18791c90"),
        ("out4", &result.out4, "3d1a32df33f5ce078ed6cfa67972c041d5aff9606ff86c381b5d257fa4bb3517"),
    ];
    for (name, data, expected) in vectors {
        assert_eq!(data.len(), 88, "{name}");
        assert_eq!(sha256(data), expected, "{name}");
    }

    let mut combined = Vec::new();
    for (_, data, _) in vectors {
        combined.extend_from_slice(data);
    }
    assert_eq!(
        sha256(&combined),
        "62d20b19dfc648c822a404a8672031efe193c1da60496b82a337458c1c1d2a5c"
    );

    let explicit_context = caller6473d0::builder6473d0_outputs(
        &in0,
        &in1,
        &in2,
        &highseed::builder6388f0_shared_context_from_bundle(&t).unwrap(),
        Some(&out0_seed),
        Some(&out1_seed),
        &t,
    )
    .unwrap();
    assert_eq!(explicit_context, result);
}

/// kit `testBuilder6473d0PreimageStackAndPostVectorsMatchPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L2105-2195).
#[test]
fn builder6473d0_preimage_stack_and_post_vectors_match_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();

    let result = caller6473d0::builder6473d0_outputs_from_bundled_context(
        &in0,
        &in1,
        &in2,
        Some(&out0_seed),
        Some(&out1_seed),
        &t,
    )
    .unwrap();
    let preimages = lowseed::Builder6473d0OutputPreimages {
        out4: result.out4.clone(),
        out3: result.out3.clone(),
        out2: result.out2.clone(),
        out1: result.out1.clone(),
        out0: result.out0.clone(),
    };

    let stack = caller6473d0::builder6473d0_minimal_stack20_from_preimages(&preimages).unwrap();
    assert_eq!(stack.len(), 0xc00);
    assert_eq!(
        sha256(&stack),
        "ee8cadccc44f822947d1cc8acc0b48081211016c8c8d20076751ac592d2a3640"
    );

    let stack_chunks: [(&str, usize, &str, &str, &str); 5] = [
        (
            "out4",
            0x000,
            "3d1a32df33f5ce078ed6cfa67972c041d5aff9606ff86c381b5d257fa4bb3517",
            "ef7d09f934d4eb5e",
            "fc9ab4e34537c920",
        ),
        (
            "out3",
            0x058,
            "d1486d791a35e129933d31bab4e814a0cdcd3db8c3b4895950882cba18791c90",
            "22b8d195c99bd38f",
            "d7d6048d727efcdb",
        ),
        (
            "out2",
            0x0b0,
            "c5e3ec0675df26d11bd8390e34135652ad6b530fb8e003151b44cf1dfce6e169",
            "e9be8cecc1fb8a0d",
            "e0a9fd4dacfaa894",
        ),
        (
            "out1",
            0x210,
            "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8",
            "0b1c2d3e4f607182",
            "5b6c7d8e9fb0c1d2",
        ),
        (
            "out0",
            0x268,
            "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd",
            "05121f2c39465360",
            "15222f3c49566370",
        ),
    ];
    for (name, offset, window_hash, first8, last8) in stack_chunks {
        let window = &stack[offset..(offset + 88)];
        assert_eq!(sha256(window), window_hash, "{name}");
        assert_eq!(hex(&window[..8]), first8, "{name}");
        assert_eq!(hex(&window[80..]), last8, "{name}");
    }

    let post_vectors = caller6473d0::builder6473d0_post_vectors(&result);
    assert_eq!(post_vectors.len(), 8);
    let expected_post_vectors: [(usize, &str); 8] = [
        (0x3708, "3d1a32df33f5ce078ed6cfa67972c041d5aff9606ff86c381b5d257fa4bb3517"),
        (0x3760, "d1486d791a35e129933d31bab4e814a0cdcd3db8c3b4895950882cba18791c90"),
        (0x37b8, "c5e3ec0675df26d11bd8390e34135652ad6b530fb8e003151b44cf1dfce6e169"),
        (0x3810, "8eb586c217d306dbde11f9301ab67d009e8dba5414bcebe90944e8542082edee"),
        (0x3868, "a4a1bb98f66e3d53a51c810379507e4a1f856bf51be0d007e10d5b3afc90252b"),
        (0x38c0, "9692c8145a24dcadc1fd23963c583512c8aebf55dc7c68ad677cb8f53f2117ea"),
        (0x3918, "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8"),
        (0x3970, "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd"),
    ];
    for (offset, expected_hash) in expected_post_vectors {
        let actual = post_vectors.get(&offset).unwrap_or_else(|| panic!("missing post vector at {offset:#x}"));
        assert_eq!(actual.len(), 88);
        assert_eq!(sha256(actual), expected_hash, "{offset:#x}");
    }
}

/// kit `testBuilder64c524OutputWordsMatchPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L2830-2888).
#[test]
fn builder64c524_output_words_match_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;
    let x2_workspace: Vec<u8> = (0..352).map(|index| ((index * 5 + 11) & 0xff) as u8).collect();

    let arg0_words = caller6473d0::builder64c524_arg0_u64_words(&arg0, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&arg0_words)),
        "b4c289307b76fd400a7ebb93cfd81f6df15931c9553af9a9c8bc1a9f047ca741"
    );
    assert_eq!(
        &arg0_words[..4],
        &[0x3ff86d27c281a51b, 0x82c9235432f698da, 0xf846c40785c0883c, 0x9f193ff19acdd538]
    );

    let updated = caller6473d0::builder64c524_workspace_after_update(&arg0_words, scalar, &x2_workspace, &t)
        .unwrap();
    assert_eq!(updated.len(), 44 * 8);
    assert_eq!(
        sha256(&updated),
        "0dce7bc80d9fd277b44333a61aa8e14a608c6205b63cee6b9ea498a4407533ee"
    );
    assert_eq!(
        hex(&updated[..32]),
        "af4609c269497b9cc32a915a34c3bbd1c87d116319769c5fab21064d51fe471b"
    );
    assert_eq!(
        hex(&updated[updated.len() - 32..]),
        "88227680af94c55adff3deccb1f86d6c81b410c13274fcc1c3c8cdd2d7dce1e6"
    );

    let output = caller6473d0::builder64c524_final_u32_words(&updated, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "59eebec8dcac326baa8e1be4844f38155835ea1734a2985fd12249e77de2d6b3"
    );
    assert_eq!(&output[..4], &[0x2c9a5e95, 0x39ffcae7, 0xdb27a8fe, 0x35947d74]);
    assert_eq!(&output[output.len() - 4..], &[0x35c32db9, 0x322abd54, 0x38781571, 0xeb184e5f]);
    assert_eq!(
        caller6473d0::builder64c524_output_words(&arg0, scalar, &x2_workspace, &t).unwrap(),
        output
    );
}

/// kit `testBuilder6473d0First64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L2890-2955).
#[test]
fn builder6473d0_first64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;

    let streams = caller6473d0::builder6473d0_first_streams_from_in2(&in2, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "b1f4dcc49a972799f58fb3a398591ad74882914c02ccbe7e3457bb85d15f9e2a"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "546485573a7fa23d64c96fb217f9196d379f766f199e80e02609e286e0c0d2ee"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "07ca498940d4fdd6ecaf9d82d38c47e974e51b0c1afad1db014de69f9bd0b560"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "6b2c5100098474366f5642502dabe909b52b3ea6217c32b4d5ab4d0e206a805c"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0x266614493d8fa940, 0x6007c6dd9fbb3cce, 0x4a0463a7929bbbdb, 0xe71ae9adfa4dffb7]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0x21609531c6f7bbc2, 0x3a01cb0167bc1884, 0x6d10d5fd47b5d72f, 0xb3988c7c118905f3]
    );

    let workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "5acfd8607b33aa61a6f38cc24ecc4555d321cd500d9e6b9ff684d36884a827ed"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "8c8f9e0050dc253a33746dd5535a791918564522a208dfbd7939d24e6a8f7413"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "9537b90ea920365d794473ef8e6ecc24fceb758e6044aa2151eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "107a44c71f1c2072c3659db4f8df499dcd81173019370d11fa3e5bf73ca829cd"
    );
    assert_eq!(&output[..4], &[0x4b27dc37, 0xbdbbe5dc, 0xe19df049, 0xa7b9b740]);
    assert_eq!(&output[output.len() - 4..], &[0xff7a3bdb, 0x56fb02ef, 0xf5984e81, 0x9ea400e1]);

    let sp488 =
        caller6473d0::builder6473d0_sp488_words_from64c524_output(&pack_u32_le(&output), &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp488)),
        "04041ca272f4abc0c853f762e581febd27c7eb6aac3d480aa0d94d5efe605160"
    );
    assert_eq!(&sp488[..4], &[0xa51e755a, 0x4ce7e2cb, 0xb6a6acfa, 0xd6d1cdb6]);
    assert_eq!(&sp488[sp488.len() - 4..], &[0xfa4d4fcc, 0xf93c4e87, 0xa7b82e47, 0x8a95e647]);
}

/// kit `testBuilder6473d0Second64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L2957-3029).
#[test]
fn builder6473d0_second64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();

    let streams = caller6473d0::builder6473d0_second_streams(&out0_seed, &sp488, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "df9ea4f10d2698fde031d956c2143316bb63595e4db592193408cbec3997a792"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "35e95635019b19c3fc100148b2a078890400453759c7e0e8a45fa91855518d64"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "b9099f56f72378936c3e52698f85809aede89bf5ca0cdab1248cc9864b954403"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "c0acb1f9c3464f26c4eb07d3ec1f88aed7a3203c5220256ea342fa643f86907a"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0xf0e978b818b93c69, 0x75a018a3672cb577, 0xa4c98421009de949, 0x0684e334238a06ce]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0x9fca7b25b214721c, 0x9f76e8d6d38da8e4, 0x8f10bab198854e7f, 0x1b1e7339e8891893]
    );

    let workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "cd1f9403619681b0c5d076306a3a56bff7ae16312a1aca44cc6e81aa7b685fed"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "9fc6f0b5a5b45c108bae7302f9b19bd803d70a934a2fc7e122894534255008c3"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "daf46992df1ed21b8b56ac397d611828f1716ca712847b7551eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "33532e554e19a5b35f3d621d486f9b7716d29c0605198e4a6ac1d320038bc28d"
    );
    assert_eq!(&output[..4], &[0x2b7825c8, 0xc4c63905, 0x0211b018, 0x9c1118f8]);
    assert_eq!(&output[output.len() - 4..], &[0x474ddcc0, 0xa689879c, 0xf11dde81, 0xac1cba94]);
}

/// kit `testBuilder6473d0Third64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L3031-3130).
#[test]
fn builder6473d0_third64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();
    let second_workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    let second_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &second_workspace,
        &t,
    )
    .unwrap());

    let source = caller6473d0::builder6473d0_third_source_words(
        &second_output,
        &highseed::builder6388f0_shared_context_from_bundle(&t).unwrap(),
        &in0,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&source)),
        "6e34220606e6f5401122a935081884080412601262e305b059fddbe077803051"
    );
    assert_eq!(&source[..4], &[0xf5b579d3, 0x9d2de314, 0x2241a5f4, 0x79d5b76f]);
    assert_eq!(&source[source.len() - 4..], &[0xab6e381a, 0xe56c72d8, 0x2e241889, 0xf358dc37]);

    let sp430 = caller6473d0::builder6473d0_third_sp430_words(&source, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp430)),
        "08a18f8160f3efca8c1f5834fa918d904b749724799d71b3d4420491df15318d"
    );
    assert_eq!(&sp430[..4], &[0x113e3183, 0x7ec235c3, 0x55dcb210, 0xf48aa162]);
    assert_eq!(&sp430[sp430.len() - 4..], &[0x7b0eec9c, 0xcdf26752, 0xca504858, 0x06f09076]);

    let streams = caller6473d0::builder6473d0_third_streams(&in2, &sp488, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "cdcd8b9cba1a6f86509bbcc4a95e3d88787cfef390803bad4bb1f50cbd55eddf"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "20e806f4dec45407ae529955b5ae41af7c5228c240dbed1fb916a74a85417c6e"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "a7ebd396d071b5f6457ce58bbd5111e98dd19a87bf847c7de7d7b4c61cfc3ad3"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "6626fad83edd6d479df4bdf0c5fbc728c94c4e83fcc3a32eddb68581955954f3"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0x9ec32fb9eb41c8b4, 0xeadea57a0d5f4f66, 0xf47a4e4eba3970b9, 0xc5338a3849d5495d]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0x12ff4184f57f0b10, 0x2bba1c1466693768, 0x31d61d1b90c42b39, 0xe7a4053ead3bfd75]
    );

    let workspace = caller6473d0::builder6473d0_third64c524_workspace(&in2, &sp488, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "fb42796391ab808c901953f0f4c3be19bbd4d49553f4df5c111d566766a1ceb6"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "2c35faea21f269ffd52ef8ec5e1ea3b888891ca17d146b93cddedd013d7ad84c"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "fe379f241197fec60883cc17e6f9b2c9133658a70c24c1a951eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "bddbfeeece5007421a61931dd17aa25f9e70ac7141aa394880fabbec7357b655"
    );
    assert_eq!(&output[..4], &[0xbaf05b61, 0xbcfafe93, 0xfdfbf603, 0x32bd3651]);
    assert_eq!(&output[output.len() - 4..], &[0xdd26ba56, 0x344e60a5, 0xe12df139, 0x64f97835]);
}

/// kit `testBuilder6473d0Fourth64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L3132-3218).
#[test]
fn builder6473d0_fourth64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();
    let _second_workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    let third_workspace = caller6473d0::builder6473d0_third64c524_workspace(&in2, &sp488, &t).unwrap();
    let third_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &third_workspace,
        &t,
    )
    .unwrap());

    let streams =
        caller6473d0::builder6473d0_fourth_streams(&out1_seed, &third_output, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "7d6cc7ceb43b20b5f628756c842c5ac7be1fde886d9309ed4518ac9ae4bb287c"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "f5bc9070ebae188fb4864386433ac3eeda3e68ed9a9e471aa5c21ebb45ca573b"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "c7d8186bbd46e981d96b8bf8ebce2bae4735a83eaf70726093750c54a7a1d025"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "3d7ab324afb3cbbf6fe7dd05ffc55e81d344fb246f24231d4f33db46209452c8"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0xc718cd948cdbbe6c, 0x19ab42c3d336544e, 0x40aeaaac042cd268, 0xf3351ed19d2a2273]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0x0848a5b0e557414a, 0x8327a66a50524d8f, 0xcf6aaf3c6af10b71, 0xa598b68e0c681612]
    );

    let workspace =
        caller6473d0::builder6473d0_fourth64c524_workspace(&out1_seed, &third_output, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "2805724203e148d53582172f661f1c76fac1cceb16baa58a2d36f584fde6ebd0"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "910a780dd0d36d7aa3cf54cfb62cd2bd1d63e86f26c61fe15c2948c36ef5f2c4"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "ee066461203fc21a55f5ee8cc35f022825c98846d169126651eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "1be5186490600873daaffdaf8dc02f9b053d163a70b498b6dabee5c10b7dbf88"
    );
    assert_eq!(&output[..4], &[0x9163deaa, 0x817053d2, 0xad6f4dd2, 0x74570c4c]);
    assert_eq!(&output[output.len() - 4..], &[0x60ba61fc, 0xca5e579f, 0xea186ea5, 0x758cad4a]);
}

/// kit `testBuilder6473d0Fifth64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L3220-3343).
#[test]
fn builder6473d0_fifth64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;
    let context = highseed::builder6388f0_shared_context_from_bundle(&t).unwrap();

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();
    let second_workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    let second_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &second_workspace,
        &t,
    )
    .unwrap());
    let third_workspace = caller6473d0::builder6473d0_third64c524_workspace(&in2, &sp488, &t).unwrap();
    let third_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &third_workspace,
        &t,
    )
    .unwrap());
    let third_source =
        caller6473d0::builder6473d0_third_source_words(&second_output, &context, &in0, &t).unwrap();
    let sp430 = caller6473d0::builder6473d0_third_sp430_words(&third_source, &t).unwrap();
    let fourth_workspace =
        caller6473d0::builder6473d0_fourth64c524_workspace(&out1_seed, &third_output, &t).unwrap();
    let fourth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &fourth_workspace,
        &t,
    )
    .unwrap());

    let source = caller6473d0::builder6473d0_fifth_source_words(&fourth_output, &context, &in1, &t)
        .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&source)),
        "45c409753e24cb9cf75d8d77f758eaed100f898588b6ed1159f790a3754ee721"
    );
    assert_eq!(&source[..4], &[0x038ec1c7, 0x7d124e33, 0x9326dce9, 0x7d8762df]);
    assert_eq!(&source[source.len() - 4..], &[0x85cf25f9, 0x09b28e3d, 0xef2023e0, 0x3b53428d]);

    let sp3d8 = caller6473d0::builder6473d0_fifth_sp3d8_words(&source, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp3d8)),
        "d655cd5c70f502d4fa869630aad63fa3640a51cae610093237b5d9a2e5c3fb52"
    );
    assert_eq!(&sp3d8[..4], &[0x2e3356bb, 0x050404a6, 0x85d8c536, 0xef821eaf]);
    assert_eq!(&sp3d8[sp3d8.len() - 4..], &[0xd8ade60a, 0x603b94bb, 0xe4df7216, 0x39d4082c]);

    let streams = caller6473d0::builder6473d0_fifth_streams(&sp430, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "df784feb1db29901a7c95bd7093566adc6e0bd687800292035222c6c8266f735"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "520a4f15468ed4cd614ee48c53dd7cc5c37f65f43d8d7eb1b43fdfe50674f3b3"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "ecfb510b8864783d07f75918ac018b14c18cacefdf787b4f4aa980545635256f"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "6df97c23d326cb47adfb8243934446f76499ad1ee3eb4efeedd9b0587ee1f232"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0xd8656d2421961388, 0xda62e987118e1762, 0x8c90d0b55f2257ac, 0xdad118e6d0f8f21e]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0x9c6325c164fa4842, 0x3273ec272266d840, 0xc4822fb10931880e, 0x8af8e29135df5a14]
    );

    let workspace = caller6473d0::builder6473d0_fifth64c524_workspace(&sp430, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "08b5f2127f4f9d38a87ed43cc9df67aa41ee69d2f8f59b28bb09cdb00ed7d475"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "01b043c35a882aca61ea3fa3b362448c9df8b98203ec08e86131a8ae94572ae8"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "416cbbf2fcd52b7c510c434a347cbb7081571771f3e92ebd51eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "7b8aeba91679cc87e0038e22256590e68819f8ae38935c5428e89b1783d3f4cc"
    );
    assert_eq!(&output[..4], &[0x7b4d3ac1, 0x5f7925de, 0x2d36a812, 0x4b7e2c6b]);
    assert_eq!(&output[output.len() - 4..], &[0x1c1eacbf, 0xce0b74f3, 0x40694fb5, 0xeee0898f]);
}

/// kit `testBuilder6473d0Sixth64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L3345-3448).
#[test]
fn builder6473d0_sixth64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;
    let context = highseed::builder6388f0_shared_context_from_bundle(&t).unwrap();

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();
    let second_workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    let second_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &second_workspace,
        &t,
    )
    .unwrap());
    let third_source =
        caller6473d0::builder6473d0_third_source_words(&second_output, &context, &in0, &t).unwrap();
    let sp430 = caller6473d0::builder6473d0_third_sp430_words(&third_source, &t).unwrap();
    let fifth_workspace = caller6473d0::builder6473d0_fifth64c524_workspace(&sp430, &t).unwrap();
    let fifth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &fifth_workspace,
        &t,
    )
    .unwrap());

    let sp380 = caller6473d0::builder6473d0_sixth_sp380_words(&fifth_output, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp380)),
        "9504a74f2e28965005e5404ec5efe753f7422bcb693e58f4a5a6a7c18c0ad9b0"
    );
    assert_eq!(&sp380[..4], &[0x90f87c6e, 0x3895b89c, 0x930c4ee5, 0x982ce414]);
    assert_eq!(&sp380[sp380.len() - 4..], &[0x212cdf90, 0xf3a5675c, 0xa476bcfc, 0xb2c330ba]);

    let streams = caller6473d0::builder6473d0_sixth_streams(&sp430, &sp380, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "2887bd4468fadc7e22e31f0fb9217bed4d97fe0244489fdf7f68d8bb9be25cbe"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "0935766dcd7efadb89b9ee300f6515d6919f7ca13efbef3d94b4c4338f44c0a9"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "a4c2f18f60e79acd4a34eeea084ed1f01cf4e3fc323f3805ee2deaf919331b3d"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "31ffa478e2e0ebbf1099622b29d76f4a1b9a8c3d9b91f87311818169d3bfb0c2"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0xe1348b33a7f370a5, 0x72acdb6c47b5dbd1, 0xcd420f20def32441, 0x1f16b89f702955ef]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0xc8956057eff4378e, 0xd3717a55b09625a0, 0x13542bc82050c562, 0x918fcdcd225d692c]
    );

    let workspace = caller6473d0::builder6473d0_sixth64c524_workspace(&sp430, &sp380, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "4c4b1b102a9fa301c2ba8541851a2c324d6064c6ca38d695888dd660bd77ef6a"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "65953c135dd69e4313e0ec23d690d764b3156da680db69c345161f884e4177e3"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "5dd6d783f1600ea4f55d88064599a48d519049d01c5bcd8751eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "2f5a21c7c184f09bdb00f9f11eb2d4292e2814306b2cf01cd97806dbb1f9c91b"
    );
    assert_eq!(&output[..4], &[0x7c89cb83, 0x90e88426, 0x4d2b1ea6, 0x017fc88c]);
    assert_eq!(&output[output.len() - 4..], &[0x39f78b96, 0xae98d6c3, 0x9d47f134, 0xae88538b]);
}

/// kit `testBuilder6473d0Seventh64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L3450-3563).
#[test]
fn builder6473d0_seventh64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;
    let context = highseed::builder6388f0_shared_context_from_bundle(&t).unwrap();

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();
    let second_workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    let second_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &second_workspace,
        &t,
    )
    .unwrap());
    let third_source =
        caller6473d0::builder6473d0_third_source_words(&second_output, &context, &in0, &t).unwrap();
    let sp430 = caller6473d0::builder6473d0_third_sp430_words(&third_source, &t).unwrap();
    let fifth_workspace = caller6473d0::builder6473d0_fifth64c524_workspace(&sp430, &t).unwrap();
    let fifth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &fifth_workspace,
        &t,
    )
    .unwrap());
    let sp380 = caller6473d0::builder6473d0_sixth_sp380_words(&fifth_output, &t).unwrap();
    let sixth_workspace = caller6473d0::builder6473d0_sixth64c524_workspace(&sp430, &sp380, &t).unwrap();
    let sixth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &sixth_workspace,
        &t,
    )
    .unwrap());

    let sp328 = caller6473d0::builder6473d0_seventh_sp328_words(&sixth_output, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp328)),
        "82f7fa5ba1bfc8e84f566c3807ad6f87b5d574c792446ed797577ebb74eaf27a"
    );
    assert_eq!(&sp328[..4], &[0xc92f75a4, 0x843b24fe, 0xea821e69, 0x1fa6924f]);
    assert_eq!(&sp328[sp328.len() - 4..], &[0x1a86ab99, 0x4a980308, 0x2dda072f, 0x84fba1df]);

    let streams = caller6473d0::builder6473d0_seventh_streams(&in0, &sp380, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "591cba75b0159e431614c436e10997c886d68a6d759a8d31be910eca22cf85e8"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "6b23d88bd5e0da2f00dfb68fdfba69ec6d94b5230348df4cb69d7adde0687c10"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "3c1a6ff0cfdf896e5edb0f455bb62ac7c504900176871e382f2e7c4f14d8a0bc"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "79ffe314f5ca539b42ab1ffa0e1247adf4ae14f09d5898478b3f3b931010b3d6"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0x124a61b90fed5e3a, 0x7c9ab6bc7f99fe0e, 0xc78f559a52db131e, 0x13b5c433fb943ac1]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0x586fe37826186e1d, 0x26c4451dd0d61491, 0x5ae38b9970a524a1, 0xe4a19cfee3a96103]
    );

    let workspace = caller6473d0::builder6473d0_seventh64c524_workspace(&in0, &sp380, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "2700fbf891008ce8edc1f11ba1557fd080d684b965dd65136260fbf2b0964878"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "a7d5fa4acffa4b5b71873b9227d3fbb39bf0519294c52829de97e9cd730e84c2"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "3da77073b43b4e286e33ecb22052330e918d36ca6f2384cf51eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "0eccbe5cdbf85dff7a37c87d7e555481d1fd8bdae3b39088b6b6273af860a786"
    );
    assert_eq!(&output[..4], &[0x0083c378, 0x3b2d8fff, 0x1ae9f799, 0xf338f6eb]);
    assert_eq!(&output[output.len() - 4..], &[0x808945ec, 0x1da59779, 0x2d0c9f18, 0x9b286f04]);
}

/// kit `testBuilder6473d0Eighth64c524SliceMatchesPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L3565-3698).
#[test]
fn builder6473d0_eighth64c524_slice_matches_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;
    let context = highseed::builder6388f0_shared_context_from_bundle(&t).unwrap();

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();
    let second_workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    let second_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &second_workspace,
        &t,
    )
    .unwrap());
    let third_workspace = caller6473d0::builder6473d0_third64c524_workspace(&in2, &sp488, &t).unwrap();
    let third_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &third_workspace,
        &t,
    )
    .unwrap());
    let third_source =
        caller6473d0::builder6473d0_third_source_words(&second_output, &context, &in0, &t).unwrap();
    let sp430 = caller6473d0::builder6473d0_third_sp430_words(&third_source, &t).unwrap();
    let fourth_workspace =
        caller6473d0::builder6473d0_fourth64c524_workspace(&out1_seed, &third_output, &t).unwrap();
    let fourth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &fourth_workspace,
        &t,
    )
    .unwrap());
    let fifth_source =
        caller6473d0::builder6473d0_fifth_source_words(&fourth_output, &context, &in1, &t).unwrap();
    let sp3d8 = caller6473d0::builder6473d0_fifth_sp3d8_words(&fifth_source, &t).unwrap();
    let fifth_workspace = caller6473d0::builder6473d0_fifth64c524_workspace(&sp430, &t).unwrap();
    let fifth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &fifth_workspace,
        &t,
    )
    .unwrap());
    let sp380 = caller6473d0::builder6473d0_sixth_sp380_words(&fifth_output, &t).unwrap();
    let seventh_workspace = caller6473d0::builder6473d0_seventh64c524_workspace(&in0, &sp380, &t)
        .unwrap();
    let seventh_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &seventh_workspace,
        &t,
    )
    .unwrap());

    let sp2d0 = caller6473d0::builder6473d0_eighth_sp2d0_words(&seventh_output, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp2d0)),
        "56cbeb55b6aa494a61207e0fca41d0265e917f6002b1ba2b8ece63458b76b085"
    );
    assert_eq!(&sp2d0[..4], &[0xc96913d6, 0x99773534, 0x7817f9b9, 0x56bb5785]);
    assert_eq!(&sp2d0[sp2d0.len() - 4..], &[0x912f288c, 0x8432d14f, 0x4a8c6c61, 0x7c00265d]);

    let streams = caller6473d0::builder6473d0_eighth_streams(&sp3d8, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_words)),
        "183f2be6b622dc2eb373fa393d1683d6dad201749fa3e60f45f6d027a267bdd9"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_words)),
        "8b884e80ad5ed6bd11860fe89f504e27b2a92ec8007bda2a1a51bf08fd9bb9f3"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.a_prefix)),
        "dcceda41820f364bf5f77e6c726942b5c6c4f60c558994e809659c233e17ce29"
    );
    assert_eq!(
        sha256(&pack_u64_le(&streams.b_prefix)),
        "c7cf326fc28abf2692e8e36c18851db85b28e83b92d51c6c27fa8fd1820a8a99"
    );
    assert_eq!(
        &streams.a_words[..4],
        &[0xaec355f61f8f894d, 0xaf57fb0fb715ffaf, 0x6753b2a275722cad, 0x6947fb771dfc5b54]
    );
    assert_eq!(
        &streams.b_words[..4],
        &[0x6a6ed2db7d276cee, 0x7fddfe69097ed3f4, 0x7fc2a48944be010e, 0x241e62d2712f2593]
    );

    let workspace = caller6473d0::builder6473d0_eighth64c524_workspace(&sp3d8, &t).unwrap();
    assert_eq!(workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&workspace),
        "be9f91423ea1b893b3e58a877eb02980b4b44bc8906e2cd19a8a6df6b3d941b3"
    );
    assert_eq!(
        hex(&workspace[..32]),
        "3deb21d360b851b9d15548db2281aed4a9aafb92a4b162f9b5e42cb228336459"
    );
    assert_eq!(
        hex(&workspace[workspace.len() - 32..]),
        "d0dc135d19aa1ea3557c6030ac3bef953d026a8e0d3a913f51eb2ff475dc024d"
    );

    let output = caller6473d0::builder64c524_output_words(&arg0, scalar, &workspace, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "cc933de7d8905c4cac18b91c959fea8e7c302bd9fa3573677fe59f251908255a"
    );
    assert_eq!(&output[..4], &[0xa8338b67, 0x48585485, 0xa7d8b3f7, 0x9b238737]);
    assert_eq!(&output[output.len() - 4..], &[0x57541043, 0x528d8294, 0xb0149d05, 0xc47caee4]);
}

/// kit `testBuilder6473d0NinthSourceReducersMatchPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L3700-4024).
#[test]
fn builder6473d0_ninth_source_reducers_match_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;
    let context = highseed::builder6388f0_shared_context_from_bundle(&t).unwrap();

    let first_workspace = caller6473d0::builder6473d0_first64c524_workspace(&in2, &t).unwrap();
    let first_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &first_workspace,
        &t,
    )
    .unwrap());
    let sp488 = caller6473d0::builder6473d0_sp488_words_from64c524_output(&first_output, &t).unwrap();
    let second_workspace =
        caller6473d0::builder6473d0_second64c524_workspace(&out0_seed, &sp488, &t).unwrap();
    let second_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &second_workspace,
        &t,
    )
    .unwrap());
    let third_workspace = caller6473d0::builder6473d0_third64c524_workspace(&in2, &sp488, &t).unwrap();
    let third_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &third_workspace,
        &t,
    )
    .unwrap());
    let third_source =
        caller6473d0::builder6473d0_third_source_words(&second_output, &context, &in0, &t).unwrap();
    let sp430 = caller6473d0::builder6473d0_third_sp430_words(&third_source, &t).unwrap();
    let fourth_workspace =
        caller6473d0::builder6473d0_fourth64c524_workspace(&out1_seed, &third_output, &t).unwrap();
    let fourth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &fourth_workspace,
        &t,
    )
    .unwrap());
    let fifth_source =
        caller6473d0::builder6473d0_fifth_source_words(&fourth_output, &context, &in1, &t).unwrap();
    let sp3d8 = caller6473d0::builder6473d0_fifth_sp3d8_words(&fifth_source, &t).unwrap();
    let fifth_workspace = caller6473d0::builder6473d0_fifth64c524_workspace(&sp430, &t).unwrap();
    let fifth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &fifth_workspace,
        &t,
    )
    .unwrap());
    let sp380 = caller6473d0::builder6473d0_sixth_sp380_words(&fifth_output, &t).unwrap();
    let sixth_workspace = caller6473d0::builder6473d0_sixth64c524_workspace(&sp430, &sp380, &t).unwrap();
    let sixth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &sixth_workspace,
        &t,
    )
    .unwrap());
    let sp328 = caller6473d0::builder6473d0_seventh_sp328_words(&sixth_output, &t).unwrap();
    let seventh_workspace =
        caller6473d0::builder6473d0_seventh64c524_workspace(&in0, &sp380, &t).unwrap();
    let seventh_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &seventh_workspace,
        &t,
    )
    .unwrap());
    let sp2d0 = caller6473d0::builder6473d0_eighth_sp2d0_words(&seventh_output, &t).unwrap();
    let eighth_workspace = caller6473d0::builder6473d0_eighth64c524_workspace(&sp3d8, &t).unwrap();
    let eighth_output = pack_u32_le(&caller6473d0::builder64c524_output_words(
        &arg0,
        scalar,
        &eighth_workspace,
        &t,
    )
    .unwrap());

    let source1 = caller6473d0::builder6473d0_ninth_first_source_words(
        &eighth_output,
        &context,
        &sp328,
        &sp2d0,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&source1)),
        "e18c8416b17464c4bae6ff625375d164dfec55af019420d2ca0116c3cc0ebed6"
    );
    assert_eq!(&source1[..4], &[0x955b96bf, 0xa2b58afd, 0xd700ff5a, 0x06a83167]);
    assert_eq!(&source1[source1.len() - 4..], &[0xd2d4d332, 0x351e47aa, 0x93b71ffe, 0x2c7ac25c]);

    let out2 = caller6473d0::builder6473d0_ninth_out2_words(&source1, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&out2)),
        "8368856aed45b1d49270602d6edba23e92809b2346290be6bc6a392f0aaf988d"
    );
    assert_eq!(&out2[..4], &[0x0d39094b, 0x077ba0c5, 0x54ba9e1d, 0x94e0f0df]);
    assert_eq!(&out2[out2.len() - 4..], &[0xaf6062a0, 0x87026000, 0x552ef927, 0x67a5cc22]);

    let source2 =
        caller6473d0::builder6473d0_ninth_second_source_words(&sp2d0, &context, &out2, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&source2)),
        "f8eb019cf9c0f4154874174ce8abf240b8e14a3ec59e411d84a550998a7a9d5c"
    );
    assert_eq!(&source2[..4], &[0x0aa6b2ba, 0x62d4fe90, 0x681904f8, 0xdf2014ff]);
    assert_eq!(&source2[source2.len() - 4..], &[0x231bce32, 0x3be767fb, 0x21037d7f, 0x2c868663]);

    let sp278 = caller6473d0::builder6473d0_ninth_sp278_words(&source2, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp278)),
        "d572b9c1bb74f6bfc893a8cf8991fee8f10520a516289e4ba9c18db8eccd7750"
    );
    assert_eq!(&sp278[..4], &[0x9413cee2, 0x0460a288, 0x22d03034, 0xbd433cdf]);
    assert_eq!(&sp278[sp278.len() - 4..], &[0xb3476dc3, 0x694125fd, 0x80373a26, 0x76a974a1]);

    let ninth_first_streams =
        caller6473d0::builder6473d0_ninth_first_streams(&sp3d8, &sp278, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&ninth_first_streams.a_words)),
        "b71bdd49fef5edf17f87cbed6f144013ad6570fb619d7259eecee78563d4321e"
    );
    assert_eq!(
        sha256(&pack_u64_le(&ninth_first_streams.b_words)),
        "95ceb0b250e78470133394a723b433a5bf8e3f7669cfb77be6dcaefcccac094d"
    );
    assert_eq!(
        sha256(&pack_u64_le(&ninth_first_streams.a_prefix)),
        "43db401e21867455f70a7da5b3688986851ff1c50922e2b4a764382a75ec7bd7"
    );
    assert_eq!(
        sha256(&pack_u64_le(&ninth_first_streams.b_prefix)),
        "e327fc0348a9eb211eec7c47eb4ad39feeabd01ad929c43990736a69c6961b40"
    );
    assert_eq!(
        &ninth_first_streams.a_words[..4],
        &[0x545b0c404f636e24, 0xb5392a3d7628dcd6, 0x7ff978526fe2c884, 0xb99d48d053f750c3]
    );
    assert_eq!(
        &ninth_first_streams.b_words[..4],
        &[0x098d91ac4188cdd0, 0xd599083ddc2ffcb2, 0x8982a5c60026fe6a, 0xaf9785833d9b9f3e]
    );

    let sp1c8 = caller6473d0::builder6473d0_ninth_sp1c8_words(
        &ninth_first_streams.a_words,
        &ninth_first_streams.b_words,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp1c8)),
        "86cae00a8644b96d2f81fdcc84e5a861e6086d2e57635918c1aa0a285f01a839"
    );
    assert_eq!(&sp1c8[..4], &[0x1ca7a428, 0x2c776676, 0x334f6402, 0xf76aaada]);
    assert_eq!(&sp1c8[sp1c8.len() - 4..], &[0x6ffb40fd, 0xcfc772cc, 0x84b24ddf, 0x72f88ab3]);

    let ninth_second_streams =
        caller6473d0::builder6473d0_ninth_second_streams(&in1, &sp328, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&ninth_second_streams.a_words)),
        "e2610e9a2bb7e7431a9d50e0c52d546c4dbf46dbf3d806253e11af0b66db64e0"
    );
    assert_eq!(
        sha256(&pack_u64_le(&ninth_second_streams.b_words)),
        "5cb107493620008b697a14577e98aca3b0b99ecbc0d5eb11824683df14589d8f"
    );
    assert_eq!(
        sha256(&pack_u64_le(&ninth_second_streams.a_prefix)),
        "89e8408978c20c1bdfb852e7f784ce2693f894aa065649d7a01aeacfd55e8594"
    );
    assert_eq!(
        sha256(&pack_u64_le(&ninth_second_streams.b_prefix)),
        "43af916e53a4d9773e251fd1a3401b53ef0acb92af42e8f008c539d39c6ed808"
    );
    assert_eq!(
        &ninth_second_streams.a_words[..4],
        &[0xfb7a80d9192d1ec3, 0x6e6d75ae9199026b, 0xa2b5cbeef35ac70a, 0x5de39a957ccb60ee]
    );
    assert_eq!(
        &ninth_second_streams.b_words[..4],
        &[0x6634d7f4c2cf2b36, 0x3cf9a20168417e5c, 0x73d6e1fd6db42ec0, 0x9fb8812a0485cfe1]
    );

    let sp118 = caller6473d0::builder6473d0_ninth_sp118_words(
        &ninth_second_streams.a_words,
        &ninth_second_streams.b_words,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp118)),
        "ded780efbdff94c54fe57299d3fa3542f688bd86e0fc0b6890bd0a135e686c4c"
    );
    assert_eq!(&sp118[..4], &[0x4a28ffde, 0x782ea9be, 0x71a8268f, 0xf40984a7]);
    assert_eq!(&sp118[sp118.len() - 4..], &[0xea23c4c1, 0x34cb8794, 0x8c5f0b7e, 0x6fbb76ae]);

    let source3 = caller6473d0::builder6473d0_ninth_third_source_words(&sp1c8, &context, &sp118, &t)
        .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&source3)),
        "551881a522a53344a1f14689741406abc30267914aa02e8d36bd722182015f11"
    );
    assert_eq!(&source3[..4], &[0x61ce1299, 0x99472497, 0xc507ee19, 0xb11859f9]);
    assert_eq!(&source3[source3.len() - 4..], &[0xad72c809, 0x26e0941c, 0xa45e51b4, 0x0394a1b2]);

    let sp68 = caller6473d0::builder6473d0_ninth_sp68_words(&source3, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&sp68)),
        "b5b7d07e747d5a677fb6cf9182a96755196408c6487d1e4e77bb37364cd12a07"
    );
    assert_eq!(&sp68[..4], &[0xf69fa821, 0x6d436f56, 0x7165aa1f, 0x0a29d5a2]);
    assert_eq!(&sp68[sp68.len() - 4..], &[0xcebd599c, 0xfa56cc6b, 0x26f17702, 0x1d4c6065]);

    let ninth_workspace = caller6473d0::builder6473d0_ninth64c524_workspace(&sp68, &t).unwrap();
    assert_eq!(ninth_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&ninth_workspace),
        "01fab44ba58a157c53f8035e82a33ad31e7d5f3090040841bd4041841e9a2b22"
    );
    assert_eq!(
        hex(&ninth_workspace[..32]),
        "6bc3af8c573468f34171d1b016dea4b2ebc4e3cff360f7404bdeb7283373accf"
    );
    assert_eq!(
        hex(&ninth_workspace[ninth_workspace.len() - 32..]),
        "18cde1b75184abdb2a95c21dea4ed777a2b23d7400c863cc78769979df2c176d"
    );

    let ninth_output = caller6473d0::builder64c524_output_words(&arg0, scalar, &ninth_workspace, &t)
        .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&ninth_output)),
        "d81b8468a5a7d4b17bf44f39b479476a8568bfd82aadc937b563221ed16604c7"
    );
    assert_eq!(&ninth_output[..4], &[0xa6afa142, 0xadd6d28a, 0x52b1d3b4, 0xa8a41d1b]);
    assert_eq!(
        &ninth_output[ninth_output.len() - 4..],
        &[0x546fe16d, 0xfcd5ce72, 0xba890604, 0x5ef00136]
    );

    let out3 = caller6473d0::builder6473d0_tenth_out3_words(&pack_u32_le(&ninth_output), &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&out3)),
        "8b4cdea006adbc427fc62ed7e593a7368be01c5433e4792558564f7d944edcd1"
    );
    assert_eq!(&out3[..4], &[0x02cfb61b, 0x83a302bf, 0x7dce5814, 0xa99120b6]);
    assert_eq!(&out3[out3.len() - 4..], &[0x1733e4c3, 0x509ea371, 0x2e866201, 0xdcad41e9]);

    let tenth_streams = caller6473d0::builder6473d0_tenth_streams(&in2, &sp430, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&tenth_streams.a_words)),
        "bdb411ed05059270ed4e6b090a287fefb9565b45f1c041d2a752d359c48a73c4"
    );
    assert_eq!(
        sha256(&pack_u64_le(&tenth_streams.b_words)),
        "b208110ec78dc587a68c8f8684542ec44e1629968c474e2a573ec958bba72774"
    );
    assert_eq!(
        sha256(&pack_u64_le(&tenth_streams.a_prefix)),
        "115333e8d18efc2935219ffc80e2686f551d909b52fdaf22e135c5b67cd24f3a"
    );
    assert_eq!(
        sha256(&pack_u64_le(&tenth_streams.b_prefix)),
        "0effdc504ddc2b26b284681c862826d56a3b7ca3f59dda63f6a6196913b7904b"
    );

    let tenth_workspace = caller6473d0::builder6473d0_tenth64c524_workspace(&in2, &sp430, &t).unwrap();
    assert_eq!(tenth_workspace.len(), 44 * 8);
    assert_eq!(
        sha256(&tenth_workspace),
        "29506e31bf43027e2b607a86a6b4dd22f33d88454f491b93e8872a9171e6a9e2"
    );
    assert_eq!(
        hex(&tenth_workspace[..32]),
        "dd5e1b73483adb7c6779b22e8a27aabb3763e5ce240fc28b13c06819287ab5a1"
    );
    assert_eq!(
        hex(&tenth_workspace[tenth_workspace.len() - 32..]),
        "9330f51ba1248fb1a1b5f5113f6fbaf9d58bd27f24e9617451eb2ff475dc024d"
    );

    let tenth_output = caller6473d0::builder64c524_output_words(&arg0, scalar, &tenth_workspace, &t)
        .unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&tenth_output)),
        "a36706388c5ac543203e0cd3eb2af42d02f3242aec9d4c183cb4fba7a3d7bb0c"
    );
    assert_eq!(&tenth_output[..4], &[0x8783fae8, 0x0ddad1eb, 0x8749248c, 0x4fe49618]);
    assert_eq!(
        &tenth_output[tenth_output.len() - 4..],
        &[0xb2d83004, 0x1d858880, 0x3da98491, 0x53b73a9b]
    );

    let out4 = caller6473d0::builder6473d0_final_out4_words(&pack_u32_le(&tenth_output), &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&out4)),
        "401fe726d4a75d7c7e6d399aa2e00fea0d56a3d6c5b3dc1b328178ea24d7a442"
    );
    assert_eq!(&out4[..4], &[0xb434cfbd, 0x433f90df, 0xc1b14cc0, 0xcff6365f]);
    assert_eq!(&out4[out4.len() - 4..], &[0x9b1fe3f8, 0x4823bd87, 0xb576ca82, 0xf3f2e79c]);
}

/// kit `testBuilder64cd40OutputWordsMatchPythonReferenceVector`
/// (FirstPairSourceSliceTests.swift L4026-4086). Only the output-words primitive is in
/// Stage D scope; the 64cd40 call-state builders are the next stage.
#[test]
fn builder64cd40_output_words_match_python_reference_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let arg0: Vec<u8> = (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;
    let x2_workspace: Vec<u8> = (0..352).map(|index| ((index * 5 + 11) & 0xff) as u8).collect();

    let arg0_words = caller6473d0::builder64cd40_arg0_u64_words(&arg0, &t).unwrap();
    assert_eq!(
        sha256(&pack_u64_le(&arg0_words)),
        "a44f4e91a177dc3a5cb312ef4f9719cdc71ddecceaddc3ec4e2edf789628e5d1"
    );
    assert_eq!(
        &arg0_words[..4],
        &[0x10ddde967190d344, 0xebd463e50c8fe285, 0x1a0e6a0158814d23, 0x0e751bdbe34a0c27]
    );

    let updated = caller6473d0::builder64cd40_workspace_after_update(&arg0_words, scalar, &x2_workspace, &t)
        .unwrap();
    assert_eq!(updated.len(), 44 * 8);
    assert_eq!(
        sha256(&updated),
        "4f72ca5ec1a21eaefb25bc5e1952d71e22482eff8776ab4d7d7b097dd898f698"
    );
    assert_eq!(
        hex(&updated[..32]),
        "d7b094c172d1d6f663803ebc41632b3a20173805dd60fba63f6cdf91fc96abe6"
    );
    assert_eq!(
        hex(&updated[updated.len() - 32..]),
        "428e3ebc4f8e7959b5f33857d937a682a920452868aad593c3c8cdd2d7dce1e6"
    );

    let output = caller6473d0::builder64cd40_final_u32_words(&updated, &t).unwrap();
    assert_eq!(
        sha256(&pack_u32_le(&output)),
        "d9242621c538e422518e9e87083886228bb6f88827716cf209de9e42fb4a336d"
    );
    assert_eq!(
        output,
        vec![
            0x00cec4df, 0x26e4cde3, 0xcaeeb424, 0xe561e5c9, 0xe47fcf9f, 0x0946526d, 0xed187991,
            0xb16fcb0d, 0x3165ae59, 0xedbc680c, 0x5d8e3672, 0x6f6ecb46, 0x73c1ecad, 0xdb28c019,
            0x4d2396d0, 0xb9045673, 0x6e108816, 0x491e7a22, 0x6ca69691, 0xa935ba59, 0x67d4d8c8,
            0x511b912c,
        ]
    );
    assert_eq!(
        caller6473d0::builder64cd40_output_words(&arg0, scalar, &x2_workspace, &t).unwrap(),
        output
    );
}