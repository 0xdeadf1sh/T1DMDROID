//! FirstPairSourceSliceTests.swift — seeded caller64 composition vectors, ported 1:1
//! (Stage E of the 6388f0 first-pair builder port): the 64cd40 call states, the seeded
//! caller64 rows, the seeded 63c278 schedules, and the derive/phase5 entry points that close
//! the chain seeds → rows → schedules → source66. Tables load from $LIBRE3_TABLES_DIR; the
//! suite skips itself when the variable is unset so CI stays green without table bytes.
//!
//! Boundary: the sensor-point section of kit
//! `testBuilder6388f0FirstPairStreamSeedsFromEntrySourceMatchesPythonReferenceVector`
//! (FirstPairSourceSliceTests.swift L991-1060) exercises this stage's seeds assembly and is
//! ported below. The `builder6388f0FirstPairStreamSeedsFrom…5bcf98Outputs` trio
//! (FirstPairSourceSlice.swift L2109-2201; kit test L924-989) landed with Stage F1 — its
//! entrySource / entropy / entropySource sections are ported in
//! `builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs_trio` below.

use libre3_core::phase5;
use libre3_core::vm::{caller6473d0, highseed, lowseed, schedule, seeded64, FirstPairTables};

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

/// kit `packUInt32LE` (FirstPairSourceSliceTests.swift L4492-4501).
fn pack_u32_le(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// kit `testBuilder6388f0First64cd40CallStateMatchesPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L2197-2302).
#[test]
fn builder6388f0_first64cd40_call_state_matches_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let context = highseed::builder6388f0_caller_context_from_bundle(&t).unwrap();
    let result = caller6473d0::builder6473d0_outputs(
        &in0,
        &in1,
        &in2,
        &context,
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
    let stack20 = caller6473d0::builder6473d0_minimal_stack20_from_preimages(&preimages).unwrap();
    let post_vectors = caller6473d0::builder6473d0_post_vectors(&result);

    // (entry, x2Workspace sha, x2Workspace prefix8, x2Workspace suffix8,
    //  stackWindow sha, stackWindow prefix8, stackWindow suffix8,
    //  output sha, output prefix8, output suffix8)
    let expected_rows: [(usize, &str, &str, &str, &str, &str, &str, &str, &str, &str); 3] = [
        (
            0,
            "9b74a2232218a70449b125c5b25a1be077125ce8b113783a46454ed02d816021",
            "9b8cd70e7c008f70",
            "adfaa15b83fb7c0a",
            "e9a7694cd9ab4b0bae1d8434ff29d370977c0828549628487f9a1395e4469f8e",
            "22604f4f42c846ff",
            "0000000000000000",
            "a87ab02c52a3f0e4d24c373618e06f5ed46879e1cfd78bad86063abb421dbbc4",
            "a29c55c2cd095992",
            "21f8df859b8bfaae",
        ),
        (
            17,
            "80e40a3e574da19ee4e2698456b25369913b97fa6b5a8c5692dc57e96cef45cb",
            "15c9a7f3b301eb2e",
            "adfaa15b83fb7c0a",
            "4d9957cb000e2390f1f3351a15f22f85d4dde632936f68fc4e91ecafbdf3476e",
            "22604f4f42c846ff",
            "0000000000000000",
            "8970960806c647297af6a2c2e803fc62cb1a188b546b1234c35b507b3854788c",
            "f17f9f4f39b1673b",
            "21f8df859b8bfaae",
        ),
        (
            58,
            "433d81b9f713d296a31dbd232f7456b5545ca0f89640d5c98f257737207dad79",
            "c18fefc075d749c5",
            "adfaa15b83fb7c0a",
            "ec7eb53f85b5c2ae678ede450ae0d2ab16d1c8b6cdd35de4c6d8db90aefc3a70",
            "22604f4f42c846ff",
            "0000000000000000",
            "fe323876b4ec44df84474ba3d699aa824c9417b8363ab776f23c0c0def8e6359",
            "dfd4e82cac137e2b",
            "21f8df859b8bfaae",
        ),
    ];

    for row in &expected_rows {
        let state = seeded64::builder6388f0_first64cd40_call_state(
            &context,
            &stack20,
            &post_vectors,
            row.0,
            &t,
        )
        .unwrap();
        let call = seeded64::builder6388f0_call64_call(state, &t).unwrap();

        assert_eq!(call.scalar, 0x68404ef676a9b7d3, "entry {}", row.0);
        assert_eq!(call.arg0.len(), 88, "entry {}", row.0);
        assert_eq!(sha256(&call.arg0), "496aa2bee379c421196b33f0e1ea8ff833a919340d09d4dd9c360e8322c9d362", "entry {}", row.0);
        assert_eq!(hex(&call.arg0[..8]), "d6ce5d63de75b391", "entry {}", row.0);
        assert_eq!(hex(&call.arg0[call.arg0.len() - 8..]), "19ae4d0dc970204b", "entry {}", row.0);

        assert_eq!(call.x2_workspace.len(), 352, "entry {}", row.0);
        assert_eq!(sha256(&call.x2_workspace), row.1, "entry {}", row.0);
        assert_eq!(hex(&call.x2_workspace[..8]), row.2, "entry {}", row.0);
        assert_eq!(hex(&call.x2_workspace[call.x2_workspace.len() - 8..]), row.3, "entry {}", row.0);

        assert_eq!(call.x3_preimage.len(), 88, "entry {}", row.0);
        assert_eq!(sha256(&call.x3_preimage), "10eef285deef7a4b7c82b22aa53589b7833df29de3814649c772bbd5c832f365", "entry {}", row.0);

        assert_eq!(call.stack_window.len(), 0xb50, "entry {}", row.0);
        assert_eq!(sha256(&call.stack_window), row.4, "entry {}", row.0);
        assert_eq!(hex(&call.stack_window[..8]), row.5, "entry {}", row.0);
        assert_eq!(hex(&call.stack_window[call.stack_window.len() - 8..]), row.6, "entry {}", row.0);

        assert_eq!(call.output.len(), 88, "entry {}", row.0);
        assert_eq!(sha256(&call.output), row.7, "entry {}", row.0);
        assert_eq!(hex(&call.output[..8]), row.8, "entry {}", row.0);
        assert_eq!(hex(&call.output[call.output.len() - 8..]), row.9, "entry {}", row.0);
    }
}

/// kit `testBuilder6388f0Second64cd40CallStateMatchesPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L2304-2410).
#[test]
fn builder6388f0_second64cd40_call_state_matches_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let context = highseed::builder6388f0_caller_context_from_bundle(&t).unwrap();
    let result = caller6473d0::builder6473d0_outputs(
        &in0,
        &in1,
        &in2,
        &context,
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
    let stack20 = caller6473d0::builder6473d0_minimal_stack20_from_preimages(&preimages).unwrap();
    let post_vectors = caller6473d0::builder6473d0_post_vectors(&result);

    let expected_rows: [(usize, &str, &str, &str, &str, &str, &str, &str, &str, &str); 3] = [
        (
            0,
            "fdf23daa0954614bc792d8a4dfa7bdb95f110bb155ee30e63a3079c2c636d4db",
            "88d330fdf438d13f",
            "adfaa15b83fb7c0a",
            "3a3bd479b489dae29e94ac6fe23fb61edf0444b8489d330b0f86a30530cdc2de",
            "22604f4f42c846ff",
            "0000000000000000",
            "a88449fc45dda85642e0fb0945bdb49cc80f61fb70f2a2973239c4dc66234551",
            "543d01d17c9b3d9d",
            "21f8df859b8bfaae",
        ),
        (
            17,
            "674d446fa8d4488a5733b4abdea474ae081c4bd0066feff1870a3207d06e935e",
            "a95d0d81704cb873",
            "adfaa15b83fb7c0a",
            "889cd7b12cfc4a4dd772e404acdedc3bf64ea0b7f75e38c179381bfa60e47793",
            "22604f4f42c846ff",
            "0000000000000000",
            "25556b8bcbaede26866b77bd708a51231b62ba928254d3ebc71e8034c580337f",
            "47d5daecac25c281",
            "21f8df859b8bfaae",
        ),
        (
            58,
            "3bccb00b36a989df5282f89f0d9cad1874be43fc7ade4b7f14c64d16f80696e2",
            "77572d1aa71cdc21",
            "adfaa15b83fb7c0a",
            "fce4abbb0537b096ee71bcd98efb5f056fead875cf1fd53762d5608671e69580",
            "22604f4f42c846ff",
            "0000000000000000",
            "ccdf21b6f656f68f98024e8c8e049460de41630149212b23b38aeec2e6a30235",
            "02802ed57a5241ee",
            "21f8df859b8bfaae",
        ),
    ];

    for row in &expected_rows {
        let first_state = seeded64::builder6388f0_first64cd40_call_state(
            &context,
            &stack20,
            &post_vectors,
            row.0,
            &t,
        )
        .unwrap();
        let first_call = seeded64::builder6388f0_call64_call(first_state, &t).unwrap();
        let state = seeded64::builder6388f0_second64cd40_call_state(
            &context,
            &stack20,
            &post_vectors,
            &first_call.output,
            row.0,
            &t,
        )
        .unwrap();
        let call = seeded64::builder6388f0_call64_call(state, &t).unwrap();

        assert_eq!(call.scalar, 0x68404ef676a9b7d3, "entry {}", row.0);
        assert_eq!(call.arg0.len(), 88, "entry {}", row.0);
        assert_eq!(sha256(&call.arg0), "496aa2bee379c421196b33f0e1ea8ff833a919340d09d4dd9c360e8322c9d362", "entry {}", row.0);
        assert_eq!(call.x2_workspace.len(), 352, "entry {}", row.0);
        assert_eq!(sha256(&call.x2_workspace), row.1, "entry {}", row.0);
        assert_eq!(hex(&call.x2_workspace[..8]), row.2, "entry {}", row.0);
        assert_eq!(hex(&call.x2_workspace[call.x2_workspace.len() - 8..]), row.3, "entry {}", row.0);

        assert_eq!(call.x3_preimage, first_call.output, "entry {}", row.0);
        assert_eq!(call.x3_preimage.len(), 88, "entry {}", row.0);

        assert_eq!(call.stack_window.len(), 0xb50, "entry {}", row.0);
        assert_eq!(sha256(&call.stack_window), row.4, "entry {}", row.0);
        assert_eq!(hex(&call.stack_window[..8]), row.5, "entry {}", row.0);
        assert_eq!(hex(&call.stack_window[call.stack_window.len() - 8..]), row.6, "entry {}", row.0);

        assert_eq!(call.output.len(), 88, "entry {}", row.0);
        assert_eq!(sha256(&call.output), row.7, "entry {}", row.0);
        assert_eq!(hex(&call.output[..8]), row.8, "entry {}", row.0);
        assert_eq!(hex(&call.output[call.output.len() - 8..]), row.9, "entry {}", row.0);
    }
}

/// kit `testBuilder6388f0Third64cd40CallStateMatchesPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L2412-2526).
#[test]
fn builder6388f0_third64cd40_call_state_matches_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let in2: Vec<u8> = (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect();
    let in0: Vec<u8> = (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect();
    let in1: Vec<u8> = (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect();
    let out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let context = highseed::builder6388f0_caller_context_from_bundle(&t).unwrap();
    let result = caller6473d0::builder6473d0_outputs(
        &in0,
        &in1,
        &in2,
        &context,
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
    let stack20 = caller6473d0::builder6473d0_minimal_stack20_from_preimages(&preimages).unwrap();
    let post_vectors = caller6473d0::builder6473d0_post_vectors(&result);

    let expected_rows: [(usize, &str, &str, &str, &str, &str, &str, &str, &str, &str); 3] = [
        (
            0,
            "27fe07cba0b4e7f62d5da4f07f91f5d1887d8f2038431d7f5d14e6d6c38683eb",
            "cc1a18d569c77ddd",
            "adfaa15b83fb7c0a",
            "a9194cbdf9a9f7e76911b05d2641ecddcb6a76e1721f0f1c37cac228a7b7995e",
            "22604f4f42c846ff",
            "0000000000000000",
            "a1c8d6d83a91ce22e8025eb97c641fa5396cdb629e7105d3b646767e1c2d6a29",
            "13e228e615652c1f",
            "21f8df859b8bfaae",
        ),
        (
            17,
            "611f5018d6ecd757ec6298405c6da354f4c757fa882244c1dc8e05cf0adadf75",
            "11af0e262ddece94",
            "adfaa15b83fb7c0a",
            "c3d48c9a66812e39a6cffe630fdabaea3a4f449953f3a14fdb30b9222bc34524",
            "22604f4f42c846ff",
            "0000000000000000",
            "4e61a8e846d745236315e1d28fc341466130e735de858695281b7bd40b10f2e2",
            "49821358928f5609",
            "21f8df859b8bfaae",
        ),
        (
            58,
            "c2ca68df25da9dab6dc07a22501cb037543119fee471ffcf4a139e28240a498b",
            "d7661ccde60dfbe2",
            "adfaa15b83fb7c0a",
            "1a99dbd410c58bc5b0fea71ec0a21804bf5151de2b77ee22ca393cd8355531c2",
            "22604f4f42c846ff",
            "0000000000000000",
            "6348e4b69ed966be0a14615776c7ad56f938543f74f0fea19539ebdd7b3a1449",
            "0bfcade05c9c24dd",
            "21f8df859b8bfaae",
        ),
    ];

    for row in &expected_rows {
        let first_state = seeded64::builder6388f0_first64cd40_call_state(
            &context,
            &stack20,
            &post_vectors,
            row.0,
            &t,
        )
        .unwrap();
        let first_call = seeded64::builder6388f0_call64_call(first_state, &t).unwrap();
        let second_state = seeded64::builder6388f0_second64cd40_call_state(
            &context,
            &stack20,
            &post_vectors,
            &first_call.output,
            row.0,
            &t,
        )
        .unwrap();
        let second_call = seeded64::builder6388f0_call64_call(second_state, &t).unwrap();
        let state = seeded64::builder6388f0_third64cd40_call_state(
            &context,
            &stack20,
            &post_vectors,
            &second_call.output,
            row.0,
            &t,
        )
        .unwrap();
        let call = seeded64::builder6388f0_call64_call(state, &t).unwrap();

        assert_eq!(call.scalar, 0x68404ef676a9b7d3, "entry {}", row.0);
        assert_eq!(call.arg0.len(), 88, "entry {}", row.0);
        assert_eq!(sha256(&call.arg0), "496aa2bee379c421196b33f0e1ea8ff833a919340d09d4dd9c360e8322c9d362", "entry {}", row.0);
        assert_eq!(call.x2_workspace.len(), 352, "entry {}", row.0);
        assert_eq!(sha256(&call.x2_workspace), row.1, "entry {}", row.0);
        assert_eq!(hex(&call.x2_workspace[..8]), row.2, "entry {}", row.0);
        assert_eq!(hex(&call.x2_workspace[call.x2_workspace.len() - 8..]), row.3, "entry {}", row.0);

        assert_eq!(call.x3_preimage, second_call.output, "entry {}", row.0);
        assert_eq!(call.x3_preimage.len(), 88, "entry {}", row.0);

        assert_eq!(call.stack_window.len(), 0xb50, "entry {}", row.0);
        assert_eq!(sha256(&call.stack_window), row.4, "entry {}", row.0);
        assert_eq!(hex(&call.stack_window[..8]), row.5, "entry {}", row.0);
        assert_eq!(hex(&call.stack_window[call.stack_window.len() - 8..]), row.6, "entry {}", row.0);

        assert_eq!(call.output.len(), 88, "entry {}", row.0);
        assert_eq!(sha256(&call.output), row.7, "entry {}", row.0);
        assert_eq!(hex(&call.output[..8]), row.8, "entry {}", row.0);
        assert_eq!(hex(&call.output[call.output.len() - 8..]), row.9, "entry {}", row.0);
    }
}

/// kit `testBuilder6388f0SeededCaller64RowMatchesPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L2528-2598).
#[test]
fn builder6388f0_seeded_caller64_row_matches_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let current = highseed::Builder6388f0Next642f60Inputs {
        x0: (0..88).map(|index| ((index * 15 + 2) & 0xff) as u8).collect(),
        x1: (0..88).map(|index| ((index * 13 + 9) & 0xff) as u8).collect(),
        x2: (0..88).map(|index| ((index * 21 + 7) & 0xff) as u8).collect(),
    };
    let preimages = lowseed::Builder6473d0OutputPreimages {
        out4: (0..88).map(|index| ((index * 3 + 1) & 0xff) as u8).collect(),
        out3: (0..88).map(|index| ((index * 5 + 2) & 0xff) as u8).collect(),
        out2: (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect(),
        out1: (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect(),
        out0: (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect(),
    };
    let context = highseed::builder6388f0_caller_context_from_bundle(&t).unwrap();

    // (entry, first64cd40 output sha, second64cd40 output sha, third64cd40 output sha,
    //  next642f60.x0 sha, next642f60.x1 sha, next642f60.x2 sha)
    let expected_rows: [(usize, &str, &str, &str, &str, &str, &str); 3] = [
        (
            0,
            "5d2fd9d65710079e70120c4b41d30ef96b43de537f0812f9c55bcde5d13da6ff",
            "a1738cb1be833dfc46837e95defc6b05ce0d477432c31ce23185931226fd54c2",
            "4d643ba15818807886f8ce5a4b5b3dfdd1eaeb0c1fa1aa217372ef3c41daee36",
            "16c108e1f0d3e9f6056c72f55125d51ecebe8499991f335f23e7cbbbb925af77",
            "815963ee2b9a9870282a384b356ef0593e1dafb8b8db3721803374854b2a5a7e",
            "c92bd26c7e87a582242cd62421ca05a6fd2820dead0b9fa14002882b331c0e79",
        ),
        (
            17,
            "02afcb4bc9530532a885d949bd994c13c953de5ea643dbf476a98a35a7133bfa",
            "2a13bd55dca210d9687243e8871f8e71a130da7ca0d0bd19d3fdc446dd3ff7eb",
            "760c499e675bb4a49c79f3c4ce2ffda348cf28a8238e950121f82ca6b79e6e3e",
            "08d621986111b3b183965665743855d3e3babc160708137f5f38c9ffe236675c",
            "645a5a07228ceb9f3da9396abcdc1b052b50ffe7196e746dfbe9d4b3fc4cae61",
            "1a9300451e9d8f4398354952d1dddfabbbd5123f77649e1b59746df4921096a4",
        ),
        (
            58,
            "843342ef8bf55e0707d422f4b7df49fe1955f7c2fd9093169d3ce09045ee8c76",
            "81f4b91efc1d50c84ff8d861a4f1cd2fe1aa4e70f7a289bc16570faac0f55e92",
            "3b13937db283e3408050fbb9b07c6b1445ea6d0799c5bbecbbe0cf4edff68730",
            "4cd9881f383c036b27aa7956269a5e7e40863b7849f112c7abae3f459a7143d2",
            "e8e0c80abbcc900e14eaf167711b0aac4b705109f47b0311ea941e3fcc41d32c",
            "011ae4ecb8fdee4ed0158a4e198f43df9aed402ddbc981695e22ff8dfc113059",
        ),
    ];

    for expected in &expected_rows {
        let row = seeded64::builder6388f0_seeded_caller64_row(
            expected.0,
            &current,
            &preimages,
            Some(&context),
            &t,
        )
        .unwrap();

        assert_eq!(row.index, expected.0);
        assert_eq!(row.current642f60, current);
        assert_eq!(row.preimages, preimages);
        assert_eq!(sha256(&row.after642f60.out0), "e4e4bc44d23db2b617f3d9a3f84a9dc1a6767d4d242a4c52b8427c587148a813");
        assert_eq!(sha256(&row.after642f60.out1), "f6e027253992cc3f10bc117332271b9c97a5e6570be183b0808309bcc759bfd2");
        assert_eq!(sha256(&row.after642f60.out2), "9c0ec2ac6f581933c3e457e0c4267507a575e1280caf5cbe09b962f265307e92");
        assert_eq!(sha256(&row.after6473d0.out2), "0d5c73fceaada7a6b59cc13b20292c84f7ed53adb1761938dcc102b24df3b333");
        assert_eq!(sha256(&row.after6473d0.out3), "7d7898d5e1c1655ad924fba7ba2ecfd10669e8215583328da2e3b5e579c6d1a2");
        assert_eq!(sha256(&row.after6473d0.out4), "4f8334dadc445d8e94bb4478cc915f6b7ed5097d904b916608a7f96b291c8c1d");
        assert_eq!(sha256(&row.minimal_stack20), "2a768e7ee55607748ec665cd8f02d2afa8e7585fac8796918438dc443bde1df4");
        assert_eq!(sha256(&row.first64cd40.output), expected.1, "entry {}", expected.0);
        assert_eq!(sha256(&row.second64cd40.output), expected.2, "entry {}", expected.0);
        assert_eq!(sha256(&row.third64cd40.output), expected.3, "entry {}", expected.0);
        assert_eq!(sha256(&row.next642f60.x0), expected.4, "entry {}", expected.0);
        assert_eq!(sha256(&row.next642f60.x1), expected.5, "entry {}", expected.0);
        assert_eq!(sha256(&row.next642f60.x2), expected.6, "entry {}", expected.0);
    }
}

/// kit `testBuilder6388f0SeededCaller64RowsThroughRow59MatchPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L2600-2701).
#[test]
fn builder6388f0_seeded_caller64_rows_through_row59_match_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let row0_out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let row0_out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let row59_out0_seed: Vec<u8> = (0..88).map(|index| ((index * 19 + 7) & 0xff) as u8).collect();
    let row59_out1_seed: Vec<u8> = (0..88).map(|index| ((index * 23 + 3) & 0xff) as u8).collect();
    let starts = highseed::builder6388f0_first_pair642f60_stream_starts(
        &row0_out0_seed,
        &row0_out1_seed,
        &row59_out0_seed,
        &row59_out1_seed,
        None,
        &t,
    )
    .unwrap();
    let row0_low_preimages = lowseed::Builder6473d0OutputPreimages {
        out4: (0..88).map(|index| ((index * 3 + 1) & 0xff) as u8).collect(),
        out3: (0..88).map(|index| ((index * 5 + 2) & 0xff) as u8).collect(),
        out2: (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect(),
        out1: row0_out1_seed.clone(),
        out0: row0_out0_seed.clone(),
    };

    let context = highseed::builder6388f0_caller_context_from_bundle(&t).unwrap();
    let rows = seeded64::builder6388f0_seeded_caller64_rows(
        &starts,
        &row0_low_preimages,
        Some(&context),
        60,
        None,
        &t,
    )
    .unwrap();
    assert_eq!(rows.len(), 60);

    // (row, current642f60.x0 sha, preimages.out4 sha, preimages.out1 sha, preimages.out0 sha,
    //  after6473d0.out4 sha, first64cd40 output sha, second64cd40 output sha,
    //  third64cd40 output sha, next642f60.x0 sha, next642f60.x1 sha, next642f60.x2 sha)
    let expected_rows:
        [(usize, &str, &str, &str, &str, &str, &str, &str, &str, &str, &str, &str); 4] = [
        (
            0,
            "ff807599b1ce0b18fbafc1f5ef3b1af310536e6a355f84b6fe6e5e7e70cb5d07",
            "32c5c3611fb9ddd8920ced6bd80e2f7823679cbeee2de9f315a1f98e47ade845",
            "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8",
            "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd",
            "aa133ccd20f64550f79e24d95edb4bd3840ea41170fb073ccfa7ed2f0a5ec2a6",
            "0c4e075f7394388253e849f3d6640211b122a3b80d4ec2f0fd6215ed2e2fa2d9",
            "50f98bbf26612a10b73c619cc69c6762dc158314f8f92eb9d5d6361129d1fae6",
            "82c27ffaaccebe24cfc57998d8c2db525d9fe19c49bca454cf60e9b09be83457",
            "735de0e678900db387d508151c8fecb730ea63cfd0c032c074d1259e6e312982",
            "c7fee1e743d7292d4cbaf16608f95bfdbe17aaac23696d2a79dff247ef44f833",
            "6b3c31cad24792ed5bef2eed597201dcf6ec3f3a0914bdf5a6db1b3fae3b994a",
        ),
        (
            1,
            "735de0e678900db387d508151c8fecb730ea63cfd0c032c074d1259e6e312982",
            "aa133ccd20f64550f79e24d95edb4bd3840ea41170fb073ccfa7ed2f0a5ec2a6",
            "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8",
            "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd",
            "387aa890d590abea241e416b8445b86cfcc0a167dfc7b108d746e462070d677e",
            "b8336b40eeb0d42767f8ff51ef6a32045fa0c08edb97c957f251290b9620bfcf",
            "c558bc603fed9ec13aa0b2479ca91d9eb5e57f14aabf113cfc8771dbc6525324",
            "2d6815f226308263a2648eea425e228698171b3a5947f7e10a615cb472d694ff",
            "66e37479739d0ec8db1f675bbf6e89320057bb0dae89f52fdf0f0f72aa4d3f84",
            "bf69b44f03b7832f07b7ca5e3be463f2884c3a52756834101008fdc9e76d5e7c",
            "95334b88f87b86184812a0451dbbf2138087eaa12cc3748af820ab8645959c5e",
        ),
        (
            58,
            "0f8cf013564d57471ee687ff1259e4a475284ac082bda3c145d1b39ba10735ff",
            "f8397a23467f7b742df4ac4ad0ef5039a53da85e2c59ecfb9201bf33284bedb3",
            "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8",
            "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd",
            "c0a36f54bde4a40820313a1f342aafece509cf5688f1cc6db298966d2c3d0fca",
            "1ea5c372b85c3d5a6d7653ed5e4eb6d3675633ed78224c5b61995994cfc8860e",
            "3ecc46b9fe2761cebc879dd2449b713f0bfadfd62091540e71412c246ef094c5",
            "89751b3b26a68d55cfd1b35764b4f26cf8f708bc04303cabc54228e8b20aaab6",
            "4ea5999b0919caf0302f7a36d44c30f88f5f0ea65ad455d7c8ad87c4d5e3af1a",
            "17c85652013c611c936ffda604594177fdc765a9b180be98c5a0edcff665903b",
            "88a7fe81fc5d1f1b6d5b24290c54f843de57fb3339e423afdb8a0e2da0dfe934",
        ),
        (
            59,
            "a2105eb9e12ffa599c55ff1714223addc7fb0fcfa9fb7b6ed4bec1acbcf1c31c",
            "c0a36f54bde4a40820313a1f342aafece509cf5688f1cc6db298966d2c3d0fca",
            "378ca711504b9c45e43c6abb6bbcfc018c3f1eb73af7be15195da5c2498d985d",
            "297391c138bee4a4837718a9e29bc9f007a0fd05321b8a84c0d2281d0111f905",
            "d6f95c8bd7aa8b4bdadcd02c4564a79ffa05c47fbc9c9f742a568e898e62dacc",
            "fde9684c6e046abd640e0d23a7df62c944606b55dae31bf0abb273861c7cb8b5",
            "93f147f5441ccbe38cdb51420c5c9a581a3cc59437ab74f6c829ed38276c898e",
            "0243976e2069612ff32ef284e79dfc3b11383403d465954c6b7d5e30aa5988ac",
            "2df364928960728a222d1a524f62a1affac4a974f17d06728aba4d1f0ec9772e",
            "5bbcc6cfaa034ffcc8f11766af29399c0717143569892f2cd0283ca21fb64338",
            "8f8f1879368b68c69b226763814a7c4cff7ed661c93283b3bc1f3c13b66f0ee4",
        ),
    ];

    for expected in &expected_rows {
        let row = &rows[expected.0];
        assert_eq!(row.index, expected.0);
        assert_eq!(sha256(&row.current642f60.x0), expected.1, "row {}", expected.0);
        assert_eq!(sha256(&row.preimages.out4), expected.2, "row {}", expected.0);
        assert_eq!(sha256(&row.preimages.out1), expected.3, "row {}", expected.0);
        assert_eq!(sha256(&row.preimages.out0), expected.4, "row {}", expected.0);
        assert_eq!(sha256(&row.after6473d0.out4), expected.5, "row {}", expected.0);
        assert_eq!(sha256(&row.first64cd40.output), expected.6, "row {}", expected.0);
        assert_eq!(sha256(&row.second64cd40.output), expected.7, "row {}", expected.0);
        assert_eq!(sha256(&row.third64cd40.output), expected.8, "row {}", expected.0);
        assert_eq!(sha256(&row.next642f60.x0), expected.9, "row {}", expected.0);
        assert_eq!(sha256(&row.next642f60.x1), expected.10, "row {}", expected.0);
        assert_eq!(sha256(&row.next642f60.x2), expected.11, "row {}", expected.0);
    }
}

/// kit `testBuilder6388f0Seeded63c278SchedulesFromRowsMatchPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L2703-2793).
#[test]
fn builder6388f0_seeded63c278_schedules_from_rows_match_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let row0_out0_seed: Vec<u8> = (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect();
    let row0_out1_seed: Vec<u8> = (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect();
    let row59_out0_seed: Vec<u8> = (0..88).map(|index| ((index * 19 + 7) & 0xff) as u8).collect();
    let row59_out1_seed: Vec<u8> = (0..88).map(|index| ((index * 23 + 3) & 0xff) as u8).collect();
    let starts = highseed::builder6388f0_first_pair642f60_stream_starts(
        &row0_out0_seed,
        &row0_out1_seed,
        &row59_out0_seed,
        &row59_out1_seed,
        None,
        &t,
    )
    .unwrap();
    let row0_low_preimages = lowseed::Builder6473d0OutputPreimages {
        out4: (0..88).map(|index| ((index * 3 + 1) & 0xff) as u8).collect(),
        out3: (0..88).map(|index| ((index * 5 + 2) & 0xff) as u8).collect(),
        out2: (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect(),
        out1: row0_out1_seed.clone(),
        out0: row0_out0_seed.clone(),
    };
    let context = highseed::builder6388f0_caller_context_from_bundle(&t).unwrap();
    let rows = seeded64::builder6388f0_seeded_caller64_rows(
        &starts,
        &row0_low_preimages,
        Some(&context),
        seeded64::BUILDER6388F0_FIRST_PAIR_STREAM_ROWS,
        None,
        &t,
    )
    .unwrap();

    let schedules = seeded64::builder6388f0_seeded63c278_schedules_from_rows(&rows, &t).unwrap();
    assert_eq!(rows.len(), 118);
    assert_eq!(schedules.first.row_index, 58);
    assert_eq!(schedules.second.row_index, 117);
    assert_eq!(schedules.first.scalar, schedule::PRE63C278_SCALAR);
    assert_eq!(schedules.second.scalar, schedule::PRE63C278_SCALAR);
    assert_eq!(
        sha256(&schedules.first.arg0),
        "1b0499442309d372cc3cd1fa5ea6d8640f3e14661456c368f50123bebc51b647"
    );
    assert_eq!(
        sha256(&schedules.first.arg1),
        "4ea5999b0919caf0302f7a36d44c30f88f5f0ea65ad455d7c8ad87c4d5e3af1a"
    );
    assert_eq!(
        sha256(&schedules.first.arg2),
        "88a7fe81fc5d1f1b6d5b24290c54f843de57fb3339e423afdb8a0e2da0dfe934"
    );
    assert_eq!(
        schedules.first.schedule_words,
        vec![
            0x42fa386c, 0xc627b657, 0xc8638fd3, 0xc97a2ab7, 0xd23eb4ac,
            0x3dc33146, 0xee7d479c, 0xb1f34d23, 0xee536419, 0xaffc9f1c,
            0x3b95a64c, 0xcce6d138, 0x70227a65, 0x87ca2121, 0xefb07a8f,
            0xc4749659, 0x1cd92603, 0xe0ab3767, 0x3b95a64c, 0xcce6d138,
        ]
    );
    assert_eq!(
        sha256(&pack_u32_le(&schedules.first.schedule_words)),
        "b31620465a69c66dee3504561f60addaf2c74055d82ad778a1f13e52da4750d0"
    );

    assert_eq!(
        sha256(&schedules.second.arg0),
        "1b0499442309d372cc3cd1fa5ea6d8640f3e14661456c368f50123bebc51b647"
    );
    assert_eq!(
        sha256(&schedules.second.arg1),
        "88e31fd2bc2b9bfc0d710473797991beb5dcd9df6b2489550f2a0d33f6a65b17"
    );
    assert_eq!(
        sha256(&schedules.second.arg2),
        "8c49a1d43b53e3d6c0f09bbb75dafb847c67f33e4031e2db45e7eadc9145840d"
    );
    assert_eq!(
        schedules.second.schedule_words,
        vec![
            0x91390f77, 0x6f5270d3, 0x6412ea8c, 0x4e288c3b, 0x352b86cf,
            0x693acdea, 0x28d9d7d2, 0x08306255, 0x8f45b76a, 0x056e96b3,
            0x3b95a64c, 0xcce6d138, 0x70227a65, 0x87ca2121, 0xefb07a8f,
            0xc4749659, 0x1cd92603, 0xe0ab3767, 0x3b95a64c, 0xcce6d138,
        ]
    );
    assert_eq!(
        sha256(&pack_u32_le(&schedules.second.schedule_words)),
        "f5187d8a9042e39cbc2758abd244f0a7bc91bc63a624c743ee8b10bb7af64cf1"
    );

    let source = seeded64::derive_from_6388f0_seeded_caller64_rows(&rows, &[0, 0, 0, 1], 0, 0x10, &t)
        .unwrap();
    assert_eq!(
        hex(&source),
        concat!(
            "040400010402030107000002030007050505030706070204050000060303020307",
            "020600070302040604000305030603020102020003010306050605060207060303"
        )
    );
    let sched = sched().unwrap();
    let raw_key = seeded64::phase5_raw_key_from_6388f0_seeded_caller64_rows(&rows, &t, &sched).unwrap();
    assert_eq!(hex(&raw_key), "515ca99cb8c0deaf1208df352078064d");
}

/// kit `testDeriveFrom6388f0FirstPairStreamSeedsMatchesPythonReferenceVectors`
/// (FirstPairSourceSliceTests.swift L2795-2828).
#[test]
fn derive_from_6388f0_first_pair_stream_seeds_matches_python_reference_vectors() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let seeds = highseed::Builder6388f0FirstPairStreamSeeds {
        null_scalar_window: (0..70).map(|index| ((index * 29 + 1) & 0xff) as u8).collect(),
        static_scalar_window: (0..70).map(|index| ((index * 31 + 2) & 0xff) as u8).collect(),
        null_entropy_11a: (0..0x11a).map(|index| ((index * 37 + 3) & 0xff) as u8).collect(),
        null_attempts: 2,
        row0_out4: (0..88).map(|index| ((index * 3 + 1) & 0xff) as u8).collect(),
        row0_out3: (0..88).map(|index| ((index * 5 + 2) & 0xff) as u8).collect(),
        row0_out2: (0..88).map(|index| ((index * 7 + 3) & 0xff) as u8).collect(),
        row0_out1: (0..88).map(|index| ((index * 17 + 11) & 0xff) as u8).collect(),
        row0_out0: (0..88).map(|index| ((index * 13 + 5) & 0xff) as u8).collect(),
        row59_out1: (0..88).map(|index| ((index * 23 + 3) & 0xff) as u8).collect(),
        row59_out0: (0..88).map(|index| ((index * 19 + 7) & 0xff) as u8).collect(),
    };

    let starts = highseed::builder6388f0_first_pair642f60_stream_starts_from_seeds(&seeds, None, &t)
        .unwrap();
    assert_eq!(
        sha256(&starts.row0.x0),
        "ff807599b1ce0b18fbafc1f5ef3b1af310536e6a355f84b6fe6e5e7e70cb5d07"
    );
    assert_eq!(
        sha256(&starts.row59.x0),
        "a2105eb9e12ffa599c55ff1714223addc7fb0fcfa9fb7b6ed4bec1acbcf1c31c"
    );

    let source = seeded64::derive_from_6388f0_first_pair_stream_seeds(&seeds, &[0, 0, 0, 1], 0, 0x10, &t)
        .unwrap();
    assert_eq!(
        hex(&source),
        concat!(
            "040400010402030107000002030007050505030706070204050000060303020307",
            "020600070302040604000305030603020102020003010306050605060207060303"
        )
    );
    let sched = sched().unwrap();
    let raw_key = seeded64::phase5_raw_key_from_6388f0_first_pair_stream_seeds(&seeds, &t, &sched)
        .unwrap();
    assert_eq!(hex(&raw_key), "515ca99cb8c0deaf1208df352078064d");
}

/// kit `testBuilder6388f0FirstPairStreamSeedsFromEntrySourceMatchesPythonReferenceVector` —
/// the sensor-point section only (FirstPairSourceSliceTests.swift L991-1060). The earlier
/// 5bcf98-outputs sections (kit L888-989) exercise the
/// `builder6388f0FirstPairStreamSeedsFrom…5bcf98Outputs` trio (FirstPairSourceSlice.swift
/// L2109-2201), which is outside this stage's range and stays unported.
#[test]
fn builder6388f0_first_pair_stream_seeds_from_entry_source_sensor_point_section() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let entry_source: Vec<u8> = (0..0x214).map(|index| ((index * 5 + 1) & 7) as u8).collect();
    let null_entropy: Vec<u8> = (0..0x11a).map(|index| ((index * 11 + 3) & 0xff) as u8).collect();
    let generator_point = unhex(
        concat!(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        ),
    );
    let sensor_point_seeds =
        seeded64::builder6388f0_first_pair_stream_seeds_from_entropy_and_sensor_points(
            &entry_source,
            &null_entropy,
            &generator_point,
            &generator_point,
            None,
            None,
            None,
            &t,
        )
        .unwrap();
    assert_eq!(sensor_point_seeds.null_attempts, 1);
    assert_eq!(sensor_point_seeds.null_entropy_11a, null_entropy);
    assert_eq!(
        sha256(&sensor_point_seeds.static_scalar_window),
        "581f613027f3b91683819a69b62ac6b691103abc55a62825f0f6a889322c1269"
    );
    assert_eq!(
        sha256(&sensor_point_seeds.row0_out0),
        "fbc744031431d9fda2ceed80266ee2dfefb9a55e585ea5bbc2666a144379f042"
    );
    assert_eq!(
        sha256(&sensor_point_seeds.row0_out1),
        "f9b223b45fe8ec5687cdcbd18218714f4b261938a4befb37e0ced7b42d012289"
    );
    assert_eq!(
        sha256(&sensor_point_seeds.row59_out0),
        "4e9c7fbc86f08bf6293e64ffc1c7aad72df8289410f1928576d42e0062bbf929"
    );
    assert_eq!(
        sha256(&sensor_point_seeds.row59_out1),
        "58f151f44d8166ab108b348e911e479fe881bb31598ae34e709a8f4bff586c80"
    );

    let retry_point_seeds =
        seeded64::builder6388f0_first_pair_stream_seeds_from_entropy_source_and_sensor_points(
            &entry_source,
            &generator_point,
            &generator_point,
            4,
            None,
            None,
            None,
            |requested_count| {
                assert_eq!(requested_count, 0x11a);
                Ok(null_entropy.clone())
            },
            &t,
        )
        .unwrap();
    assert_eq!(retry_point_seeds, sensor_point_seeds);

    let sensor_point_source =
        seeded64::derive_from_6388f0_first_pair_entropy_and_sensor_points(
            &entry_source,
            &null_entropy,
            &generator_point,
            &generator_point,
            &[0, 0, 0, 1],
            0,
            0x10,
            &t,
        )
        .unwrap();
    assert_eq!(
        sha256(&sensor_point_source),
        "ef4495c4b868489d0b4a30546bbf3d3b3ef51498e314a792214092d50ea09f2f"
    );
    assert_eq!(
        hex(&sensor_point_source),
        concat!(
            "04040706000606020005070707050402050701070106000602000707060002050402",
            "0407050605040400060004020400000106060102060205030303040600040606"
        )
    );

    let retry_point_source =
        seeded64::derive_from_6388f0_first_pair_entropy_source_and_sensor_points(
            &entry_source,
            &generator_point,
            &generator_point,
            4,
            &[0, 0, 0, 1],
            0,
            0x10,
            |requested_count| {
                assert_eq!(requested_count, 0x11a);
                Ok(null_entropy.clone())
            },
            &t,
        )
        .unwrap();
    assert_eq!(retry_point_source, sensor_point_source);

    let sched = sched().unwrap();
    let sensor_point_raw_key =
        seeded64::phase5_raw_key_from_6388f0_first_pair_entropy_and_sensor_points(
            &entry_source,
            &null_entropy,
            &generator_point,
            &generator_point,
            &t,
            &sched,
        )
        .unwrap();
    assert_eq!(
        sensor_point_raw_key.to_vec(),
        phase5::derive_raw_key(&sensor_point_source, &sched).unwrap()
    );
}

/// kit `testBuilder6388f0FirstPairStreamSeedsFromEntrySourceMatchesPythonReferenceVector`,
/// entrySource / entropy / entropySource sections (FirstPairSourceSliceTests.swift L924-989):
/// the `builder6388f0FirstPairStreamSeedsFrom…5bcf98Outputs` trio (FirstPairSourceSlice.swift
/// L2109-2201) over the landed high-seed / low-seed layers.
#[test]
fn builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs_trio() {
    let Some(t) = tables() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    // kit L864-867: synthetic 5bcf98 P-256 outputs for row0/row59.
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

    // kit L888-900 (`seeds`): the raw from5bcf98Outputs inputs, reused verbatim below.
    let null_scalar_window: Vec<u8> = (0..70).map(|index| ((index * 29 + 1) & 0xff) as u8).collect();
    let static_scalar_window: Vec<u8> = (0..70).map(|index| ((index * 31 + 2) & 0xff) as u8).collect();
    let null_entropy_11a: Vec<u8> = (0..0x11a).map(|index| ((index * 37 + 3) & 0xff) as u8).collect();

    // kit L924-943: the entry-source composition with explicit static scalar window.
    let entry_source: Vec<u8> = (0..0x214usize).map(|index| ((index * 5 + 1) & 7) as u8).collect();
    let entry_seeds = seeded64::builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs(
        &entry_source,
        &row0_first_output70,
        &row0_second_output70,
        &row59_first_output70,
        &row59_second_output70,
        &null_scalar_window,
        &static_scalar_window,
        &null_entropy_11a,
        2,
        None,
        None,
        None,
        &t,
    )
    .unwrap();
    assert_eq!(
        sha256(&entry_seeds.row0_out4),
        "e70d3f912b290b5bd31c6dd27e8816448c16863247354286fc66957bdf2a8e27"
    );
    assert_eq!(entry_seeds.row0_out0, high_seeds.row0.out0);
    assert_eq!(entry_seeds.row0_out1, high_seeds.row0.out1);
    assert_eq!(entry_seeds.row59_out0, high_seeds.row59.out0);
    assert_eq!(entry_seeds.row59_out1, high_seeds.row59.out1);

    // kit L945-960: the static scalar window defaults to the entry-source derivation.
    let entry_seeds_with_derived_static =
        seeded64::builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs(
            &entry_source,
            &row0_first_output70,
            &row0_second_output70,
            &row59_first_output70,
            &row59_second_output70,
            &null_scalar_window,
            &[],
            &null_entropy_11a,
            2,
            None,
            None,
            None,
            &t,
        )
        .unwrap();
    assert_eq!(entry_seeds_with_derived_static.row0_out4, entry_seeds.row0_out4);
    assert_eq!(
        hex(&entry_seeds_with_derived_static.static_scalar_window),
        "f38d95844ac5834265c854266814ed9e67ce508eea912fc81a9b2d28db0ddd5e".to_owned()
            + "0000000000000000000000000000000000000000000000000000000000000000000000000000"
    );

    // kit L962-976: the entropy variant, nullAttempts fixed at 1.
    let null_entropy: Vec<u8> = (0..0x11a).map(|index| ((index * 11 + 3) & 0xff) as u8).collect();
    let entropy_seeds = seeded64::builder6388f0_first_pair_stream_seeds_from_entropy_and5bcf98_outputs(
        &entry_source,
        &row0_first_output70,
        &row0_second_output70,
        &row59_first_output70,
        &row59_second_output70,
        &null_entropy,
        None,
        None,
        None,
        &t,
    )
    .unwrap();
    assert_eq!(entropy_seeds.null_entropy_11a, null_entropy);
    assert_eq!(entropy_seeds.null_attempts, 1);
    assert_eq!(
        sha256(&entropy_seeds.null_scalar_window),
        "c4f2357511bf2071de2a5478a5d3d8a17c2b4da7b46c6cb46f4834ecb3a2f2ba"
    );

    // kit L978-989: the retrying entropy-source variant equals the entropy variant.
    let retry_seeds =
        seeded64::builder6388f0_first_pair_stream_seeds_from_entropy_source_and5bcf98_outputs(
            &entry_source,
            &row0_first_output70,
            &row0_second_output70,
            &row59_first_output70,
            &row59_second_output70,
            4,
            None,
            None,
            None,
            |requested_count| {
                assert_eq!(requested_count, 0x11a);
                Ok(null_entropy.clone())
            },
            &t,
        )
        .unwrap();
    assert_eq!(retry_seeds, entropy_seeds);
}
