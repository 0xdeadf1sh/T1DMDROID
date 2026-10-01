//! PLAN_T1DMDROID.md §12.1: BleFramingTests.swift ported 1:1 — §5.2 write fragmentation and
//! notify reassembly over the live-capture fixtures (162-B phone cert across 9 writes,
//! 140-B sensor cert across 8 notifies). Pure functions; no tables, no skips.

use libre3_core::frame::{fragment_for_write, reassemble_write, NotifyReassembler, WRITE_CHUNK_PAYLOAD};

/// Kit `phase1Phone162` (BleFramingTests.swift L8-18).
const PHASE1_PHONE_162: &str = "03030102030405060708090a0b0c0d0e0f10000161897655010000000000000000048242be33f1a330880112fa62cc4842a43d1204922ad201d8775bb226f611f75b0ef3d5bc6cc4317caa457584ab003f1712336089d3a4f29838ed0dc666deaea2d65a00dfff5d7bcae21655e302e3458e774daaaaca87af75f1b87884b18d4ce875d0d108c903a834471a4ff674b2d30bcba062373014b7786e4437b177aec3c8";

/// Kit `phase2Sensor140` (BleFramingTests.swift L21-30).
const PHASE2_SENSOR_140: &str = "0157259416c6007ae00550043e1f46f25d44b3d72a8c37dcfebc7c339ed01fc5668a6387458084ac9cafebe7438b649f76b81eeca9343287da162b07c5c07362997e40e13035df14cdf3d5d81a5fe9ffc84de8c8ecc33be609bf0d8ebf97bed6bae899e181bb32960c81e99d5aab30b2fc7a77b0d45eff9885d46722371cd9375e14352d890f60fa1e7159e6";

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// kit `testWriteFragmenter162Cert` (BleFramingTests.swift L32-48).
#[test]
fn write_fragmenter_162_cert() {
    let message = unhex(PHASE1_PHONE_162);
    assert_eq!(message.len(), 162);
    let frags = fragment_for_write(&message, WRITE_CHUNK_PAYLOAD).expect("fragment");
    assert_eq!(frags.len(), 9);
    for (i, frag) in frags.iter().enumerate() {
        let claimed_offset = frag[0] as usize | ((frag[1] as usize) << 8);
        assert_eq!(claimed_offset, i * 18, "fragment {i} offset");
        assert!(frag.len() - 2 <= 18);
    }
    // First fragment carries the test-pattern header bytes.
    assert_eq!(
        hex(&frags[0][2..20]),
        "03030102030405060708090a0b0c0d0e0f10"
    );
    // Reassembling fragments gives the original message back.
    let refs: Vec<&[u8]> = frags.iter().map(|f| f.as_slice()).collect();
    let round_trip = reassemble_write(&refs).expect("round trip");
    assert_eq!(round_trip, message);
}

/// kit `testNotifyReassemblerSensorCert` (L50-66).
#[test]
fn notify_reassembler_sensor_cert() {
    let message = unhex(PHASE2_SENSOR_140);
    assert_eq!(message.len(), 140);
    let mut asm = NotifyReassembler::new();
    let mut seq: u8 = 0;
    let mut idx = 0usize;
    while idx < message.len() {
        let end = (idx + 19).min(message.len());
        let mut frag = vec![seq];
        frag.extend_from_slice(&message[idx..end]);
        asm.feed(&frag).expect("feed");
        seq = seq.wrapping_add(1);
        idx = end;
    }
    assert_eq!(asm.available_bytes(), 140);
    let assembled = asm.take(140).expect("take");
    assert_eq!(assembled, message);
}

/// kit `testNotifyReassemblerSequenceGap` (L68-74).
#[test]
fn notify_reassembler_sequence_gap() {
    let mut asm = NotifyReassembler::new();
    asm.feed(&[0x00, 0xaa]).expect("first fragment");
    let err = asm.feed(&[0x02, 0xbb]).expect_err("gap must fail closed");
    assert_eq!(err.to_string(), "first-pair slice: sequenceGap(expected: 1, got: 2)");
}

/// kit `testTakeMoreThanAvailable` (L76-82).
#[test]
fn take_more_than_available() {
    let mut asm = NotifyReassembler::new();
    asm.feed(&[0x00, 0xaa, 0xbb]).expect("feed");
    let err = asm.take(5).expect_err("take beyond available must fail closed");
    assert_eq!(err.to_string(), "first-pair slice: notEnoughBytes(have: 2, want: 5)");
}