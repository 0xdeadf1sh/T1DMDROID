//! PLAN_T1DMDROID.md §12.1: FirstPairSourceSliceTests.swift ported 1:1 — the 679f48 slice
//! branch (context init, 67aa8c/67eb94/67d630/67dd7c streaming, df80 compress, finalizer,
//! 67a960→64de54 derivation). Tables load from $LIBRE3_TABLES_DIR; the suite skips itself
//! when the variable is unset so CI stays green without table bytes.

use libre3_core::phase5::ScheduleTables;
use libre3_core::vm::slice;
use libre3_core::vm::FirstPairTables;

fn tables() -> Option<FirstPairTables> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    let t = FirstPairTables::from_dir(std::path::Path::new(&dir));
    if let Err(e) = &t {
        eprintln!("first-pair tables unusable from {dir}: {e:?}");
    }
    Some(t.expect("tables"))
}

fn sched() -> Option<phase5::ScheduleTables> {
    let dir = std::env::var("LIBRE3_TABLES_DIR").ok()?;
    Some(phase5::ScheduleTables::from_dir(std::path::Path::new(&dir)).expect("schedule tables"))
}

use libre3_core::phase5;
use libre3_core::vm::schedule;
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

/// kit `make679f48Context` (test helper).
fn make679f48_context(context_length: u64, block_index: u32) -> Vec<u8> {
    let mut bytes: Vec<u8> = (0..0x20c).map(|index| ((index * 7 + 3) & 7) as u8).collect();
    for (i, b) in context_length.to_le_bytes().iter().enumerate() {
        bytes[i] = *b;
    }
    for (i, b) in block_index.to_le_bytes().iter().enumerate() {
        bytes[0x110 + i] = *b;
    }
    bytes
}

#[test]
fn init679f48_and_initial_aa8c() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let initial = slice::init679f48_context(&t).unwrap();
    assert_eq!(sha256(&initial), "2df6726428a8e0ab82f3a9807e742ee08fae532a00f8da456b2978ef4a93e6c8");
    assert_eq!(
        hex(&initial[..72]),
        concat!("000000000000000005000402050203010503050404010603020105040504050703", "010604050703050006020605050606020704070503040303030704070602010704", "010206030501")
    );

    let updated = slice::update67aa8c_len4_initial(&initial, &[0, 0, 0, 1], &t).unwrap();
    assert_eq!(
        sha256(&updated),
        "bcfc48c06814b940af45b26c60b35161014f3131cbfa9bcb7a3bdd178b7cc179"
    );
    assert_eq!(
        hex(&updated[updated.len() - 72..]),
        concat!("0000000000000000000000000000000000000000000000000000000000000000", "000000000400000067e6096a85ae67bb72f36e3c3af54fa57f520e518c68059b", "abd9831f19cde05b")
    );
}

#[test]
fn initial_aa8c_rejects_invalid_inputs() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let initial = slice::init679f48_context(&t).unwrap();
    assert!(slice::update67aa8c_len4_initial(&initial, &[1, 2, 3], &t).is_err());

    let mut flagged = initial;
    flagged[0x1a4] = 2;
    assert!(slice::update67aa8c_len4_initial(&flagged, &[0, 0, 0, 1], &t).is_err());
}

#[test]
fn apply67eb94_pending_blocks() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let initial = slice::init679f48_context(&t).unwrap();
    let updated = slice::update67aa8c_len4_initial(&initial, &[0, 0, 0, 1], &t).unwrap();
    let applied = slice::apply67eb94_pending_blocks(&updated, &t).unwrap();
    assert_eq!(
        sha256(&applied),
        "144260d5df72c48af66d02ee64b7ab3e901b4f5b6c1a69cf41020bc8028c8425"
    );
    assert_eq!(
        hex(&applied[0x114..0x1a4]),
        concat!("060604030004050007010507070303070200050206010400020205020203070503", "070602040500000701040300070303030700070400020207020705010103050006", "010606070202060204020100030507040600000004070002060404050203060702", "050704030600020402060404060006060301040705020701070701050206030707", "000306050504000700040403")
    );
}

#[test]
fn encode67d630_block_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let vectors: [(Vec<u8>, &str); 2] = [
        (
            vec![0, 0, 0, 1],
            concat!("050604040404020504060402060202020206050502020604020605040202050605", "020506060402050506020202050606040506020206060202060604020606060402"),
        ),
        (
            (0..16).collect(),
            concat!("040600060104000207000305060202050606000500030307000302040003050305", "060004000100000502070400070301050500000302030206060104020606060502"),
        ),
    ];
    for (src, expected) in vectors {
        let encoded = slice::encode67d630_block(&src, &t).unwrap();
        assert_eq!(hex(&encoded), expected);
    }
    assert!(slice::encode67d630_block(&[], &t).is_err());
    assert!(slice::encode67d630_block(&vec![0u8; 17], &t).is_err());
}

#[test]
fn apply67eb94_with_pending_raw_adapter() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let initial = slice::init679f48_context(&t).unwrap();
    let updated = slice::update67aa8c_len4_initial(&initial, &[0, 0, 0, 1], &t).unwrap();
    let applied = slice::apply67eb94_with_pending_raw_adapter(&updated, &t).unwrap();
    assert_eq!(
        sha256(&applied),
        "7311b8040cd2b3c972246f43f27662024b9b64d2c9b117a0571a3eef59e759c1"
    );
    assert_eq!(
        hex(&applied[0x08..0x4a]),
        concat!("050202020202040604020404020506050502050506040404060206040506060406", "060505040406050504040406060204020507000102050605050504050502040404")
    );
}

#[test]
fn apply67dd7c_update_crosses_df80_boundary() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let context = make679f48_context(0, 3);
    let encoded = slice::encode67d630_block(&(0..16).collect::<Vec<u8>>(), &t).unwrap();
    let applied = slice::apply67dd7c_update_until_df80(&context, &encoded, 16, &t).unwrap();
    assert_eq!(
        sha256(&applied),
        "dff592f458a6f495de02a3c60bf4c0f2c46692df8d67020a65bcdf1277ce2030"
    );
    assert_eq!(
        hex(&applied[0x114..0x1a4]),
        concat!("050700050504040205060504010701000006060302070207060503050701060406", "040104030206070100070704010400040006050500000202030200040700060003", "050502070702050503060203010406050606000007070103000306010705030507", "040500000301070101020600010401070102000205050204060007030003060703", "050403060205070004010000")
    );
}

#[test]
fn derive_from_679f48_inputs() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let previous: Vec<u8> = (0..(2 * 66)).map(|index| ((index * 5 + 2) & 7) as u8).collect();
    let updates = slice::previous_descriptor_blocks_to_dd7c_inputs(&previous, &t).unwrap();
    assert_eq!(updates.len(), 132);
    assert_eq!(
        sha256(&updates),
        "44e2abbbeb7fa7615a64007196fcdbbfb396ed9fe8e48e6b048404e8f96a2730"
    );

    let context = slice::finalized679f48_context_from_inputs(&previous, &[0, 0, 0, 1], &t).unwrap();
    assert_eq!(
        sha256(&context),
        "8a57624059dee8d2679edd1b2e6de78f8d1856a871eb268d59f309943d10aa11"
    );
    let source = slice::derive_from_679f48_inputs(&previous, &[0, 0, 0, 1], 0, 16, &t).unwrap();
    assert_eq!(
        hex(&source),
        concat!("040400020506020406010204030101020502070705070404000302040501050505", "010004030304070206030607070000000005000102030205000107030202050000")
    );
}

#[test]
fn constructor67076c_and_raw_descriptor_derivation() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let raw: Vec<u8> = (0..(2 * 66)).map(|index| ((index * 3 + 1) & 7) as u8).collect();
    let ptr28 = slice::constructor670978_ptr28_blocks(&raw, &t).unwrap();
    assert_eq!(
        sha256(&ptr28),
        "706be35f728909a58b2924e4ddb1a8aad7a725fa7f614445cd92feefb611de1c"
    );
    let ptr10 = slice::constructor670a54_ptr10_blocks(&raw, &t).unwrap();
    assert_eq!(
        sha256(&ptr10),
        "914e5cf5c7677d9c570a74351fffbd879f75aca534c08426f3042eb2c6212d2b"
    );

    let source660448 =
        slice::derive_from_660448_raw_descriptor(&raw, &[0, 0, 0, 1], 0, 16, &t).unwrap();
    assert_eq!(
        hex(&source660448),
        concat!("040401010705040302070407030002030400040004030507070305050106050402", "070401040604040707070702010000030500040203000304030103030004060301")
    );
}

#[test]
fn finalize679f48_to_second_df80_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let vectors: [(&str, u64, u32, &str, &str, &str); 2] = [
        (
            "low4_idx2",
            132,
            2,
            concat!("010602060707010605040204060404050206040406050406020404040402020402", "020204040506060504020604060604040502040504020602040505050604050502"),
            "0a47106ad8b1d372b9f821d1ed1c421c9a33ac9c6f91e350c8bd89e4d1497143",
            concat!("040407050303060105040303000500050302040700030303070502040102030405", "000604000506020200030003060500040200000306060700070205070206030600"),
        ),
        (
            "low0_idx3",
            128,
            3,
            concat!("010606060505040605040204060404050206040406050406020404040402020402", "020204040506060504020604060604040502040504020602040505050604050506"),
            "17967df31a85c7937a2c2c0da54297a4f45387ede33b3c8aa10d8f584443c20f",
            concat!("040402060207050701070101000501060401010007010403050107000401060205", "070107070404010306000003050006020503030402070706000100010206040004"),
        ),
    ];
    for (name, context_length, block_index, final_len, context_hash, source) in &vectors {
        let context = make679f48_context(*context_length, *block_index);
        let finalized = slice::finalize679f48_to_second_df80(&context, &t).unwrap();
        assert_eq!(
            hex(&slice::final679f48_length_block(*context_length, &t).unwrap()),
            *final_len,
            "{name}"
        );
        assert_eq!(sha256(&finalized), *context_hash, "{name}");
        let source_got = slice::derive_from_679f48_context(&context, 0, 16, &t).unwrap();
        assert_eq!(hex(&source_got), *source, "{name}");
    }
    let bad = make679f48_context(132, 5);
    assert!(slice::finalize679f48_to_second_df80(&bad, &t).is_err());
}

#[test]
fn df80_transform_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let blocks: Vec<u8> = (0..(4 * 66)).map(|index| ((index * 5 + 3) & 7) as u8).collect();
    let state: Vec<u8> = (0..(8 * 18)).map(|index| ((index * 7 + 2) & 7) as u8).collect();
    let transformed = slice::df80_transform(&state, &blocks, &t).unwrap();

    assert_eq!(transformed.len(), 144);
    assert_eq!(
        sha256(&transformed),
        "83d6b1d8af5c9ae3696aa44b9f62680633b0a996598b934781aa571ba0bbe58d"
    );
    assert_eq!(
        hex(&transformed[..72]),
        concat!("060505060603010607050500030303010703040102050702030300020103040406", "060304030403060005010706050001060203000404030301030000040706010105", "000102030607")
    );
    assert_eq!(
        hex(&transformed[transformed.len() - 72..]),
        concat!("040707050405010406070505010204020502060200000307060605040407020503", "070404070704010106060002050303040503010604070007070402050504070703", "040704070607")
    );

    let workspace = slice::df80_initial_workspace(&blocks, &t).unwrap();
    let schedule = slice::df80_expanded_schedule(&workspace, &t).unwrap();
    let compressed = slice::df80_compress_state(&state, &schedule, &t).unwrap();
    assert_eq!(compressed, transformed);

    assert!(slice::df80_compress_state(&vec![0u8; 143], &schedule, &t).is_err());
    assert!(slice::df80_compress_state(&state, &vec![0u8; 0x47f], &t).is_err());
}

#[test]
fn df80_expanded_schedule_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let blocks: Vec<u8> = (0..(4 * 66)).map(|index| ((index * 5 + 3) & 7) as u8).collect();
    let workspace = slice::df80_initial_workspace(&blocks, &t).unwrap();
    let schedule = slice::df80_expanded_schedule(&workspace, &t).unwrap();

    assert_eq!(schedule.len(), 0x480);
    assert_eq!(
        sha256(&schedule),
        "19a0a495eb712175fc15dda37e6a5719940376609560f2ca26e3586abde2db77"
    );
    assert_eq!(
        hex(&schedule[0x120..0x120 + 72]),
        concat!("020607070005060306030005020607060101020000050407040500050007030003", "000706030505050407040304000200040402050302010205040206000104010503", "050402060306")
    );
    assert_eq!(
        hex(&schedule[schedule.len() - 72..]),
        concat!("030505060104000601010201060203060004070203010405040204000207040103", "060500020604000107000107030305000001000005010004010706020701070400", "050103070204")
    );
    assert!(slice::df80_expanded_schedule(&vec![0u8; 287], &t).is_err());
}

#[test]
fn df80_initial_workspace_vector() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let blocks: Vec<u8> = (0..(4 * 66)).map(|index| ((index * 5 + 3) & 7) as u8).collect();
    let expected_hex = [
        "020606050700020404010501070401010504060201020704010204010501060601",
        "010305020601020201030306000307070005050200040200040203020404020407",
        "030401070102020600020104040506050500010603040207060207040106030303",
        "050500020506040501020605070606040204030206040403040502060206060301",
        "070106000103000603050002020606040301060100020107060205070303060204",
        "060300050606000107050405070702020604060702060001070305060105070704",
        "000402050404060100040705010205010207020605010505000002070601050207",
        "060001060203000502070503040305020107060504020603000302000407030707",
        "010507060106030301040304000002060007020004030006",
    ]
    .concat();
    let got = slice::df80_initial_workspace(&blocks, &t).unwrap();
    assert_eq!(hex(&got), expected_hex);
    assert!(slice::df80_initial_workspace(&vec![0u8; 263], &t).is_err());
}

#[test]
fn final679f48_length_block_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let vectors: [(u64, &str); 4] = [
        (
            0,
            concat!("010606060404040505040204060404050206040406050406020404040402020402", "020204040506060504020604060604040502040504020602040505050604050506"),
        ),
        (
            4,
            concat!("010602060101040505040204060404050206040406050406020404040402020402", "020204040506060504020604060604040502040504020602040505050604050502"),
        ),
        (
            68,
            concat!("010602060701070707010204060404050206040406050406020404040402020402", "020204040506060504020604060604040502040504020602040505050604050502"),
        ),
        (
            132,
            concat!("010602060707010605040204060404050206040406050406020404040402020402", "020204040506060504020604060604040502040504020602040505050604050502"),
        ),
    ];
    for (context_length, expected) in &vectors {
        let got = slice::final679f48_length_block(*context_length, &t).unwrap();
        assert_eq!(hex(&got), *expected);
    }
}

#[test]
fn derive64de54_slice_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let encoded: Vec<u8> = (0..(66 * 2)).map(|index| ((index * 3 + 1) & 7) as u8).collect();
    let vectors: [(usize, &str); 2] = [
        (
            0,
            concat!("040404000403000004010706050306060105010204060302010504030702000005", "040100010000060707050301030202050401000403030601050100040200020405"),
        ),
        (
            16,
            concat!("040407000000070705020503020004070506070000030105040307060003070705", "030702030207040100070200030105070205030003010407060403000307040103"),
        ),
    ];
    for (offset, expected) in &vectors {
        let got = slice::derive64de54_slice(&encoded, *offset, 16, &t).unwrap();
        assert_eq!(hex(&got), *expected);
    }
    assert!(slice::derive64de54_slice(&vec![0u8; 65], 0, 16, &t).is_err());
}

#[test]
fn derive_from_67cc18_sources_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let source: Vec<u8> = (0..(66 * 2)).map(|index| ((index * 5 + 2) & 7) as u8).collect();
    let vectors: [(usize, &str); 2] = [
        (
            0,
            concat!("040400010204020403070505010102040500070506070505040300070500020600", "000406070603060706070607020602010602010202010400040206060406000602"),
        ),
        (
            16,
            concat!("040404030306020107070006010600010003020602000507000601000701020004", "000004010506000003060407010603030005020605010101070405020206020702"),
        ),
    ];
    for (offset, expected) in &vectors {
        let got = slice::derive_from_67cc18_sources(&source, *offset, 16, &t).unwrap();
        assert_eq!(hex(&got), *expected);
    }
}

#[test]
fn phase5_raw_key_from_67cc18_sources_vectors() {
    let (Some(t), Some(sched)) = (tables(), sched()) else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let source: Vec<u8> = (0..(66 * 2)).map(|index| ((index * 5 + 2) & 7) as u8).collect();
    let vectors: [(usize, &str); 2] = [
        (0, "1fc9367dbfe4d23015419023b8ff18b6"),
        (16, "4b92eac60192ed83e6666a2810a936a6"),
    ];
    for (offset, expected) in &vectors {
        let got = slice::phase5_raw_key_from_67cc18_sources(&source, *offset, &t, &sched).unwrap();
        assert_eq!(hex(&got), *expected);
    }
}

#[test]
fn derive_from_67a960_inputs_vectors() {
    let (Some(t), Some(sched)) = (tables(), sched()) else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let src1: Vec<u8> = (0..130).map(|index| ((index * 3 + 4) & 7) as u8).collect();
    let src2: Vec<u8> = (0..130).map(|index| ((index * 5 + 1) & 7) as u8).collect();
    let vectors: [(usize, &str, &str); 2] = [
        (
            0,
            concat!("040400040302000203010504050606010602060206010706040604020201000605", "060300050706070506050406030505060205040406070504060105050706010702"),
            "1a36bec545101e734f469c930b565b59",
        ),
        (
            16,
            concat!("040400000700070601050100070301030104070402060100060100050502030707", "030004040000020104020600040306040607040304000206050404040402060606"),
            "61efbe0d4f32b0c424a29ff609c73a18",
        ),
    ];
    for (offset, expected_hex, expected_key) in &vectors {
        let source = slice::derive_from_67a960_inputs(&src1, &src2, *offset, 16, &t).unwrap();
        assert_eq!(hex(&source), *expected_hex);
        let key = slice::phase5_raw_key_from_67a960_inputs(&src1, &src2, *offset, &t, &sched).unwrap();
        assert_eq!(hex(&key), *expected_key);
    }
}

#[test]
fn derive_from_finalized679f48_context_vectors() {
    let (Some(t), Some(sched)) = (tables(), sched()) else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let context: Vec<u8> = (0..0x20c).map(|index| ((index * 7 + 3) & 7) as u8).collect();
    let vectors: [(usize, &str, &str); 2] = [
        (
            0,
            concat!("040405070607060600000304050601020107020701070601000201030506040705", "060707060203060200050700050006050303040107020404040607010306000605"),
            "1e6348e3a52751cbac7cc95200f39d9e",
        ),
        (
            16,
            concat!("040405010207070104030605020701010403030404070701070005030507010101", "070705070202030500000005010301060606010703040400070207020302020707"),
            "b2f4925e0545eb07acd86a4c00beee05",
        ),
    ];
    for (offset, expected_hex, expected_key) in &vectors {
        let source =
            slice::derive_from_finalized679f48_context(&context, *offset, 16, &t).unwrap();
        assert_eq!(hex(&source), *expected_hex);
        let key =
            slice::phase5_raw_key_from_finalized679f48_context(&context, *offset, &t, &sched)
                .unwrap();
        assert_eq!(hex(&key), *expected_key);
    }
}
fn pack_u64_le(words: &[u64]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

fn pack_u32_le(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

#[test]
fn builder6388f0_tail_layers() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let internal: Vec<u8> = (0..(2 * 66)).map(|i| ((i * 5 + 1) & 7) as u8).collect();
    let prefinal: Vec<u8> = (0..(2 * 66)).map(|i| ((i * 3 + 2) & 7) as u8).collect();
    let workspace: Vec<u8> = (0..266).map(|i| ((i * 7 + 4) & 7) as u8).collect();
    let stage_a: Vec<u8> = (0..282).map(|i| ((i * 5 + 2) & 7) as u8).collect();
    let stage_b: Vec<u8> = (0..282).map(|i| ((i * 3 + 6) & 7) as u8).collect();

    let final_raw = schedule::final_raw_blocks_6388f0(&internal, &t).unwrap();
    assert_eq!(final_raw.len(), 132);
    assert_eq!(sha256(&final_raw), "56ddfdbf0fcc1b60f339a70d9cba7e8262d02c9e7bed64970126f795fb635576");

    let prefinal_internal = schedule::prefinal_len32_internal_blocks_6388f0(&prefinal, &t).unwrap();
    assert_eq!(prefinal_internal.len(), 132);
    assert_eq!(sha256(&prefinal_internal), "b6af68238f927cb2f27d318fa8fb6e0c77494d16555c4dd64a06d1376544995a");

    let workspace_prefinal = schedule::len32_prefinal_sources_from_workspace_6388f0(&workspace, &t).unwrap();
    assert_eq!(workspace_prefinal.len(), 132);
    assert_eq!(sha256(&workspace_prefinal), "b7fed8b0449eb2368269402165ea6bf581f68580bdb3a1719b0ecf14ac2f9172");

    let stage_prefinal =
        schedule::len32_prefinal_sources_from_stage_inputs_6388f0(&stage_a, &stage_b, &t).unwrap();
    assert_eq!(stage_prefinal.len(), 132);
    assert_eq!(sha256(&stage_prefinal), "cc33aa4c896081feb2a44b378afd6b9830ca655c174f6d507c9716e8a68a7830");
}

#[test]
fn derive_from_6388f0_layers() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let internal: Vec<u8> = (0..(2 * 66)).map(|i| ((i * 5 + 1) & 7) as u8).collect();
    let prefinal: Vec<u8> = (0..(2 * 66)).map(|i| ((i * 3 + 2) & 7) as u8).collect();
    let workspace_a: Vec<u8> = (0..266).map(|i| ((i * 7 + 4) & 7) as u8).collect();
    let workspace_b: Vec<u8> = (0..266).map(|i| ((i * 7 + 5) & 7) as u8).collect();
    let stage_a0: Vec<u8> = (0..282).map(|i| ((i * 5 + 2) & 7) as u8).collect();
    let stage_b0: Vec<u8> = (0..282).map(|i| ((i * 3 + 6) & 7) as u8).collect();
    let stage_a1: Vec<u8> = (0..282).map(|i| ((i * 3 + 1) & 7) as u8).collect();
    let stage_b1: Vec<u8> = (0..282).map(|i| ((i * 5 + 4) & 7) as u8).collect();

    assert_eq!(
        hex(&schedule::derive_from_6388f0_internal_streams(&internal, &prefinal, &[0, 0, 0, 1], 0, 16, &t).unwrap()),
        concat!(
            "040400040702000206000504060306040706020100040303030205020506030603",
            "020207050704040602060006000600020002020305050000020401070006030103"
        )
    );
    assert_eq!(
        hex(&schedule::derive_from_6388f0_prefinal_len32_streams(&prefinal, &internal, &[0, 0, 0, 1], 0, 16, &t).unwrap()),
        concat!(
            "040400000703050704050203030503020706030400060402020001060302050104",
            "070104060000030706020104020007060300040207050502050502000405050700"
        )
    );
    assert_eq!(
        hex(&schedule::derive_from_6388f0_workspace_len32_streams(&workspace_a, &workspace_b, &[0, 0, 0, 1], 0, 16, &t).unwrap()),
        concat!(
            "040407040606030500050503030005050606010206050607040407000306050202",
            "070100040200040407070303020600010302040007020501000306000406020107"
        )
    );
    assert_eq!(
        hex(&schedule::derive_from_6388f0_stage_len32_streams(&stage_a0, &stage_b0, &stage_a1, &stage_b1, &[0, 0, 0, 1], 0, 16, &t).unwrap()),
        concat!(
            "040400030102000103000703030201010707070106000506020306040203060202",
            "070203000305040200010504020501070103070706000700040501060003060005"
        )
    );
}

#[test]
fn builder6388f0_pack_and_lane_layers() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let primary0: Vec<u8> = (0..(20 * 16)).map(|i| ((i * 3 + 1) & 7) as u8).collect();
    let secondary0: Vec<u8> = (0..(20 * 16)).map(|i| ((i * 5 + 2) & 7) as u8).collect();
    let primary1: Vec<u8> = (0..(20 * 16)).map(|i| ((i * 7 + 4) & 7) as u8).collect();
    let secondary1: Vec<u8> = (0..(20 * 16)).map(|i| ((i * 3 + 6) & 7) as u8).collect();

    let (b_head0, b_body0, a_head0, a_body0) =
        schedule::pack_outputs_from_lane_blocks_6388f0(&primary0, &secondary0, &t).unwrap();
    assert_eq!(sha256(&b_head0), "cbb73fff67fc8d84576195e087bdadabd862476bea09fd3603ec19f75f703f28");
    assert_eq!(sha256(&b_body0), "1ed65e8008b64c579a8dc5ef91bbb8026a7bd10180c16fd9022395cc6f21e583");
    assert_eq!(sha256(&a_head0), "0419abe716a28762aae3d3abdfcbd20069e74db5f4cc62060cbf32f11ed3c2ea");
    assert_eq!(sha256(&a_body0), "ec559fc571319e7d69e31ba7761b9035072bcddc75324c3de9f78b7eca830560");

    let (stage_a0, stage_b0) = schedule::len32_stage_inputs_from_pack_outputs_6388f0(
        &b_head0, &b_body0, &a_head0, &a_body0, &t,
    ).unwrap();
    assert_eq!(sha256(&stage_a0), "fbece039249b3700c3908af39c16cfdffda2a8e94765ce1243280ede14a0db22");
    assert_eq!(sha256(&stage_b0), "55622eae1f29c6264a33d62e2b41fb3875832683281b456831037d8a4e01d4f6");

    let pack1 = schedule::pack_outputs_from_lane_blocks_6388f0(&primary1, &secondary1, &t).unwrap();
    let source_from_pack = schedule::derive_from_6388f0_pack_len32_streams(
        &b_head0, &b_body0, &a_head0, &a_body0,
        &pack1.0, &pack1.1, &pack1.2, &pack1.3,
        &[0, 0, 0, 1], 0, 16, &t,
    ).unwrap();
    assert_eq!(
        hex(&source_from_pack),
        concat!(
            "040400060501060401060002050500050600010504000007060300050100000407",
            "060406030405050001050500010002050100020501010304040405040706050200"
        )
    );

    let source_from_lanes = schedule::derive_from_6388f0_lane_len32_streams(
        &primary0, &secondary0, &primary1, &secondary1, &[0, 0, 0, 1], 0, 16, &t,
    ).unwrap();
    assert_eq!(source_from_lanes, source_from_pack);
}

#[test]
fn builder6388f0_schedule_layer() {
    let (Some(t), Some(sched)) = (tables(), sched()) else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let schedule0: Vec<u32> = [
        0x11223344, 0x12243648, 0x1326394c, 0x14283c50, 0x152a3f54, 0x162c4258, 0x172e455c,
        0x18304860, 0x19324b64, 0x1a344e68, 0x1b36516c, 0x1c385470, 0x1d3a5774, 0x1e3c5a78,
        0x1f3e5d7c, 0x20406080, 0x21426384, 0x22446688, 0x2346698c, 0x24486c90,
    ]
    .to_vec();
    let schedule1: Vec<u32> = [
        0x89abcdef, 0x8aaccef0, 0x8badcff1, 0x8caed0f2, 0x8dafd1f3, 0x8eb0d2f4, 0x8fb1d3f5,
        0x90b2d4f6, 0x91b3d5f7, 0x92b4d6f8, 0x93b5d7f9, 0x94b6d8fa, 0x95b7d9fb, 0x96b8dafc,
        0x97b9dbfd, 0x98badcfe, 0x99bbddff, 0x9abcdf00, 0x9bbde001, 0x9cbee102,
    ]
    .to_vec();

    let (lanes0_primary, lanes0_secondary) = schedule::lane_blocks_from_schedule_words_6388f0(&schedule0, &t).unwrap();
    assert_eq!(sha256(&lanes0_primary), "1b70254a30185288de09f9a35ec6b1293474b349a720f89794631dd5cd43c2f8");
    assert_eq!(sha256(&lanes0_secondary), "d718360163754dcd6fc33adfe4d184ce65e7f9f6e9bd32b4fcb677b425845bc1");

    let (lanes1_primary, lanes1_secondary) = schedule::lane_blocks_from_schedule_words_6388f0(&schedule1, &t).unwrap();
    assert_eq!(sha256(&lanes1_primary), "2ebd449c1b18906b11e48c56f3bdef9cb98072e5be686a4b89cb02b2dab45d04");
    assert_eq!(sha256(&lanes1_secondary), "cbc6175ef451f45406d268a601058f257271e2ddcd12d6a773c0b51f48c8f2f0");

    let source = schedule::derive_from_6388f0_schedule_len32_streams(&schedule0, &schedule1, &[0, 0, 0, 1], 0, 16, &t).unwrap();
    assert_eq!(
        hex(&source),
        concat!(
            "040401070203010704050106010001020204070005060100030704020306000701",
            "070104060605050201000402010102050201000404040305070103060002030602"
        )
    );

    let key = schedule::phase5_raw_key_from_6388f0_schedule_len32_streams(&schedule0, &schedule1, &t, &sched).unwrap();
    assert_eq!(hex(&key), "e407917d692fd119fbf18baf60644ded");
}

#[test]
fn builder63c278_initial_mix_and_tail_reducers() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let arg0: Vec<u8> = (0..88).map(|i| ((i * 7 + 3) & 0xff) as u8).collect();
    let arg1: Vec<u8> = (0..88).map(|i| ((i * 5 + 11) & 0xff) as u8).collect();
    let arg2: Vec<u8> = (0..88).map(|i| ((i * 3 + 17) & 0xff) as u8).collect();
    let scalar: u64 = 0x0123456789abcdef;

    let (x1_vec44, x0_vec22) = schedule::initial_vectors_63c278(&arg0, &arg1, &t).unwrap();
    assert_eq!(x1_vec44.len(), 44);
    assert_eq!(x0_vec22.len(), 22);
    assert_eq!(sha256(&pack_u64_le(&x1_vec44)), "7510279962382f9fbfd7acbc437c419e0efe3aa928c3b86a09fa3235880799d1");
    assert_eq!(sha256(&pack_u64_le(&x0_vec22)), "cd3066baa97e86c4cd0882325622da57d6283f598225b6c7b19f3e066e18a9d3");

    let (x2_vec44, x0b_vec22) = schedule::second_initial_vectors_63c278(&arg0, &arg2, &t).unwrap();
    assert_eq!(sha256(&pack_u64_le(&x2_vec44)), "571998e49bf13bc6a2e9d7df592371186beacebb27c8fd7d498924f41591f53e");
    assert_eq!(sha256(&pack_u64_le(&x0b_vec22)), "dbbd9840317de13798bd1cf50ef6fec8ad0e26638b894a62b1cfd14aee785fda");

    let mixed1 = schedule::scalar_mix_vector_63c278(&x1_vec44, &x0_vec22, scalar, &t).unwrap();
    let mixed2 = schedule::scalar_mix2_vector_63c278(&x2_vec44, &x0b_vec22, scalar, &t).unwrap();
    assert_eq!(sha256(&pack_u64_le(&mixed1)), "4692583a47aa6989ac7d4fab5d20c97ee27970af79f4590824a8268dbf1b27dd");
    assert_eq!(sha256(&pack_u64_le(&mixed2)), "5b7a4c6be8ac3f3c33331e866588d75f17b62dcdf9b1bfab22846801cd262dca");

    let tail1 = schedule::tail1_u32_words_63c278(&mixed1, &t).unwrap();
    let tail2 = schedule::tail2_u32_words_63c278(&mixed2, &t).unwrap();
    assert_eq!(sha256(&pack_u32_le(&tail1)), "27e1f0bcd8f8555166c80cb3ee788ce5c03a1ee08dde0f2aa8e9c3282a72b472");
    assert_eq!(sha256(&pack_u32_le(&tail2)), "9b840d5956cdaae86b1835984c6bdcfa4b44577c1cfeae66455c2c04739d1e4f");
    assert_eq!(&tail1[..4], &[0xc21a61c6, 0x74c4feaf, 0x58177aec, 0x7a88bfb1]);
    assert_eq!(&tail2[..4], &[0xc822edf3, 0x3210de15, 0x669f83ce, 0x9d56a88e]);

    let (sp440, sp4f0, sp5a0, sp390) = schedule::accumulator_streams_63c278(&arg2, &tail2, &t).unwrap();
    assert_eq!(sha256(&pack_u64_le(&sp440)), "b5ba8730b4f348f2bead511a543354ac3cba1388e04d737af3487124cbc79598");
    assert_eq!(sha256(&pack_u64_le(&sp4f0)), "2f26f4d41701f9596582f852831caeede87aa078ff624ab70e59dad3eb170b5d");
    assert_eq!(sha256(&pack_u64_le(&sp5a0)), "84932501eaef1bcf7c0b58a51b1bf8653c46ae9527a759b0469ff2653e43db03");
    assert_eq!(sha256(&pack_u64_le(&sp390)), "22518465559d66c1e916c293fe4bc2e9380a5018f23d213d7bedfaef9f3f746b");

    let bridge_conv = schedule::bridge_convolution_vector_63c278(&sp440, &sp4f0, &sp5a0, &sp390);
    let bridge_x0 = schedule::bridge_x0_vector_63c278(&arg0, &t).unwrap();
    let bridge_mix = schedule::bridge_mix_vector_63c278(&bridge_conv, &bridge_x0, scalar, &t).unwrap();
    let sp128 = schedule::bridge_sp128_words_63c278(&bridge_mix, &t).unwrap();
    assert_eq!(sha256(&pack_u64_le(&bridge_conv)), "9d307ee87af9694b08e35935470c931390def241ff8cf03071b0c97e9288a7e6");
    assert_eq!(sha256(&pack_u64_le(&bridge_x0)), "ba1a0930d3edc74b7b1cbc65257d0d5e0b84935ba8baf2a8d1bffb131618f370");
    assert_eq!(sha256(&pack_u64_le(&bridge_mix)), "51c7581842c0ac902cde4145e6edec72344c6b174e1637b7804a54cf8c639e1c");
    assert_eq!(sha256(&pack_u32_le(&sp128)), "eacc144f8735791a4d91c9c60617e7203b18f9338142c63fd549bc9e19957670");

    let (sp390_static, sp440_pre, sp6b0_pre, sp658_pre) =
        schedule::prebranch_initial_streams_63c278(&arg0, &tail1, &sp128, &t).unwrap();
    let pre4f0 = schedule::prebranch_sp4f0_words_63c278(&arg0, &t).unwrap();
    let pre230 = schedule::prebranch_sp230_words_63c278(&pre4f0, &t).unwrap();
    let pre5a0 = schedule::prebranch_sp5a0_words_63c278(&pre230, &t).unwrap();
    assert_eq!(sha256(&pack_u32_le(&sp390_static)), "bb76f8765891dfcb76e25a5b078bf9a703142137ab005f646526f4654e693626");
    assert_eq!(sha256(&pack_u32_le(&sp440_pre)), "7eb39fcc20f253b51776dd9ea7a697b92813d2db917ca91ed5c8378b1e7fd37c");
    assert_eq!(sha256(&pack_u32_le(&sp6b0_pre)), "a1594bb70ed406c9e25a8cd067e68d56e23292b717ec02cd551456e5ed89f6a4");
    assert_eq!(sha256(&pack_u32_le(&sp658_pre)), "8ac4e5d77a1070b2926a06ef3c8fbb01a0c4618c8237d5073f4198044ce1c02d");
    assert_eq!(sha256(&pack_u32_le(&pre4f0)), "d9523b2165986722e70834869ba3bc017dfe1c0ae5bc42ca299bb7dabd2450c6");
    assert_eq!(sha256(&pack_u32_le(&pre230)), "651bc9459d7ca269bd73de0e77a883f06126649d59e0ed448912291dd626832f");
    assert_eq!(sha256(&pack_u32_le(&pre5a0)), "fe3dad8c38db4b9a4ebf05fa507d885080f4119e6751a0c30a92ef115b676746");
}

#[test]
fn builder63c278_schedule_words_vectors() {
    let (Some(t), Some(sched)) = (tables(), sched()) else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let arg0 = &schedule::PRE63C278_ARG0_SOURCE;
    let arg1_a = unhex(concat!(
        "870f4045410102fa6ae48ed935d4528112946ec5085053dcda8e537f1f02ea84",
        "ddb295ec23ff6a8c58b97fed9a2736abd68482c3517b7ad27fbe711c7fb9f",
        "32a00c1356f38b3025e143d56dd9017a173d68482c3517b7ad2"
    ));
    let arg2_a = unhex(concat!(
        "d9f6a9980cacf13569f2843968ae9cdc112262a50b5bdb6a435931a1d02896c3",
        "ab70c95476b7ea17fb1fadf32aeabc0881ab695ce36c62eca5621fafcf684",
        "616c5198a7948a03ad3e82ae3783b32b01681ab695ce36c62ec"
    ));
    let arg1_b = unhex(concat!(
        "2576fe36ecd8e7be514212a7129bcb32c361f0d230d0612528124ca25fd446a2",
        "5979de6adb6a70c2b534e301985718a0d68482c3517b7ad27fbe711c7fb9f",
        "32a00c1356f38b3025e143d56dd9017a173d68482c3517b7ad2"
    ));
    let arg2_b = unhex(concat!(
        "37b2af34160b02ddf8e45aef8f22c626ed2984e27b754dc75c89f9a58cb0b0a8",
        "eea62a650526362ea30760fa9319f39781ab695ce36c62eca5621fafcf684",
        "616c5198a7948a03ad3e82ae3783b32b01681ab695ce36c62ec"
    ));
    let expected0: Vec<u32> = [
        0x8c15c5da, 0x34dd429d, 0x955af9fe, 0x6897e537, 0x1bad4a31, 0xb3206998, 0x3bda123d,
        0x3fdb46c5, 0xd42db9fd, 0x29dc0f3a, 0x3b95a64c, 0xcce6d138, 0x70227a65, 0x87ca2121,
        0xefb07a8f, 0xc4749659, 0x1cd92603, 0xe0ab3767, 0x3b95a64c, 0xcce6d138,
    ]
    .to_vec();
    let expected1: Vec<u32> = [
        0x04961c3d, 0x1f110752, 0x271f9e47, 0x551739bc, 0x828a0f59, 0xd01fa5be, 0x6703b5b7,
        0x22e03d75, 0x9cbed758, 0x7f4e06d1, 0x3b95a64c, 0xcce6d138, 0x70227a65, 0x87ca2121,
        0xefb07a8f, 0xc4749659, 0x1cd92603, 0xe0ab3767, 0x3b95a64c, 0xcce6d138,
    ]
    .to_vec();

    let sched0 = schedule::schedule_words_63c278(arg0, &arg1_a, &arg2_a, schedule::PRE63C278_SCALAR, &t).unwrap();
    assert_eq!(sched0, expected0);
    assert_eq!(
        sha256(&pack_u32_le(&sched0)),
        "bca47c5f0b63efce696822be0e0b00455d7f4d592cf55332429588fcba3e285b"
    );
    let sched1 = schedule::schedule_words_63c278(arg0, &arg1_b, &arg2_b, schedule::PRE63C278_SCALAR, &t).unwrap();
    assert_eq!(sched1, expected1);
    assert_eq!(
        sha256(&pack_u32_le(&sched1)),
        "8b6ad5e9244eb599dbfdaabee633b41bd6230643ed86ec7c90e9d3335c621a3c"
    );

    let source = schedule::derive_from_63c278_schedule_inputs(
        arg0, &arg1_a, &arg2_a, &arg1_b, &arg2_b, schedule::PRE63C278_SCALAR, &[0, 0, 0, 1], 0, 16, &t,
    ).unwrap();
    let default_source = schedule::derive_from_pre63c278_schedule_inputs(
        &arg1_a, &arg2_a, &arg1_b, &arg2_b, 0, 16, &t,
    ).unwrap();
    assert_eq!(default_source, source);
    assert_eq!(
        hex(&source),
        concat!(
            "040402020404000202060205040102060705010600010704020506070300050007",
            "070004010407010502000304070207010604030305070405040204060700000702"
        )
    );
    let raw_key = schedule::phase5_raw_key_from_63c278_schedule_inputs(
        arg0, &arg1_a, &arg2_a, &arg1_b, &arg2_b, schedule::PRE63C278_SCALAR, &t, &sched,
    ).unwrap();
    assert_eq!(hex(&raw_key), "8df19b56ae4a0d4044a5c0d5fc86a34e");
    let default_raw_key = schedule::phase5_raw_key_from_pre63c278_schedule_inputs(
        &arg1_a, &arg2_a, &arg1_b, &arg2_b, &t, &sched,
    ).unwrap();
    assert_eq!(default_raw_key, raw_key);
}
