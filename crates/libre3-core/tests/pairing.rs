//! PLAN_T1DMDROID.md §5.3/§5.4 golden tests for the pairing byte-machines, ported from
//! LibreCRKitTests/PublicSmokeTests.swift (`testCachedReconnectHandshakeSkipsCertAndEphemeralExchange`,
//! `testAuthorizationHandshakeRejectsNonFourByteTailBeforeTransportUse`) plus a full §5.3
//! wire-order pin. Tables load from $LIBRE3_TABLES_DIR; every test skips itself when unset.

use std::collections::VecDeque;

use libre3_core::ccm;
use libre3_core::cert::{self, PhoneCert};
use libre3_core::challenge;
use libre3_core::frame::{self, WRITE_CHUNK_PAYLOAD};
use libre3_core::libaes::{self, LibAESTables};
use libre3_core::pairing::{
    CachedReconnectMachine, FirstPairMachine, PairingAction, PairingChar, PairingError,
};
use libre3_core::phase5::ScheduleTables;
use libre3_core::session_key::{
    self, BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE, FirstPairPhase5KeyInputs,
};
use libre3_core::vm::FirstPairTables;

fn tables_dir() -> Option<std::path::PathBuf> {
    std::env::var("LIBRE3_TABLES_DIR").ok().map(std::path::PathBuf::from)
}

#[allow(dead_code)]
fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn ranges(start: u8, end: u8) -> Vec<u8> {
    (start..end).collect()
}

/// A constructor that must fail — without needing `Debug` on the machine type.
fn ctor_err<T>(result: Result<T, PairingError>) -> PairingError {
    match result {
        Err(e) => e,
        Ok(_) => panic!("expected a constructor error, got a machine"),
    }
}

// MARK: - Kit fixtures (cited)

/// PublicSmokeTests L119: the 140-B level1-signed sensor cert (also BleFramingTests phase2).
const SENSOR_CERT_RAW: &str = "0157259416c6007ae00550043e1f46f25d44b3d72a8c37dcfebc7c339ed01fc5668a6387458084ac9cafebe7438b649f76b81eeca9343287da162b07c5c07362997e40e13035df14cdf3d5d81a5fe9ffc84de8c8ecc33be609bf0d8ebf97bed6bae899e181bb32960c81e99d5aab30b2fc7a77b0d45eff9885d46722371cd9375e14352d890f60fa1e7159e6";

/// SessionKeyTests L150: the post08 live-accepted 0x11a null entropy.
const POST08_NULL_ENTROPY: &str = "8987c91f1595e8a060e4cba652368ae8797e9113cfd412bebd0ea1a03783ae59ee70d2c947578803b06b275c96632d148b81658bb87a3eabb5755273c40c397f7255f3c1d742df608383fbbfff5a9b9fbc11a1ab525382024c85687cf79c2a391ca7cc309ff82fe098c2d86e49f8b26364153f0bcb8945c887f5a2a7b54d568daa373a86c85c283fbb6285f35dca2d30263c34ce182c1fc63e6022a3c7e6eaebe3a473d3c754bb8f3982172431af66388948aaf5c709f6699b7608dcd161811dda99c61b302f46684433e61ef2afa4dd9f8b0f2472f6120197cdfc0b940ad5f93ac01fc7497fb355c753df9c65fc68721690c35a09550fb3c326e38bcbe37ebb309a680c383967627f58a108e1e94ecd16c5d2bc2f576dabdc7b";

/// SessionKeyTests L151: the post08 live sensor ephemeral point.
const POST08_SENSOR_EPH65: &str = "04057637b02770974bf685ccf017992cf586e94bf7a6cbe229bd813f68873a90e1606b8b73d6d8873a31b3a556feae538c9a808fcd936cee8e73ad556922b98f87";

/// PublicSmokeTests L65: the scripted challenge nonce.
const CHALLENGE_NONCE7: [u8; 7] = [0x21, 0x04, 0x00, 0x00, 0x8f, 0x8c, 0x4b];
/// PublicSmokeTests L66: the scripted phase-6 nonce.
const PHASE6_NONCE7: [u8; 7] = [0x22, 0x04, 0x00, 0x00, 0x7f, 0x43, 0x8e];
/// PublicSmokeTests L68: the scripted 4-byte BLE-PIN tail.
const TAIL4: [u8; 4] = [0x32, 0x25, 0xec, 0x72];

// MARK: - Scripted sensor (kit ScriptedCommandPairingTransport pattern)

/// The sensor's scripted notify traffic, one logical message per await.
struct ScriptedSensor {
    /// Command-clock response payloads; each is fed RAW — the clock is an unframed channel
    /// (live EU 2026-09-24: a bare 1-B notify), no seq prefix.
    cmd: VecDeque<Vec<u8>>,
    /// Cert-character messages, each pre-fragmented into seq-prefixed chunks.
    cert_msgs: VecDeque<Vec<Vec<u8>>>,
    /// Challenge-character messages, each pre-fragmented into seq-prefixed chunks.
    challenge_msgs: VecDeque<Vec<Vec<u8>>>,
}

impl ScriptedSensor {
    fn empty() -> ScriptedSensor {
        ScriptedSensor {
            cmd: VecDeque::new(),
            cert_msgs: VecDeque::new(),
            challenge_msgs: VecDeque::new(),
        }
    }

    /// Seq-prefixed fragments of ≤ `max` payload bytes each (§5.2), seq restarting per message.
    fn fragments_from(payload: &[u8], max: usize, start_seq: u8) -> Vec<Vec<u8>> {
        let mut out = Vec::new();
        let mut seq = start_seq;
        let mut idx = 0usize;
        while idx < payload.len() {
            let end = (idx + max).min(payload.len());
            let mut chunk = vec![seq];
            chunk.extend_from_slice(&payload[idx..end]);
            out.push(chunk);
            seq = seq.wrapping_add(1);
            idx = end;
        }
        out
    }

    fn fragments(payload: &[u8], max: usize) -> Vec<Vec<u8>> {
        Self::fragments_from(payload, max, 0)
    }

    /// One seq-prefixed notify chunk carrying the first ≤19 payload bytes.
    fn one_chunk(payload: &[u8], seq: u8) -> Vec<u8> {
        let mut chunk = vec![seq];
        chunk.extend_from_slice(&payload[..payload.len().min(19)]);
        chunk
    }

    fn take(&mut self, char: PairingChar) -> Vec<Vec<u8>> {
        match char {
            PairingChar::SecCommandResponse => {
                let payload = self.cmd.pop_front().expect("scripted command response");
                vec![payload]
            }
            PairingChar::SecCertData => self.cert_msgs.pop_front().expect("scripted cert message"),
            PairingChar::SecChallengeData => {
                self.challenge_msgs.pop_front().expect("scripted challenge message")
            }
        }
    }
}

/// Uniform drive surface over the two machines (Rust-internal, no uniffi).
trait Drive {
    fn step_next(&mut self) -> Result<PairingAction, PairingError>;
    fn feed(&mut self, char: PairingChar, chunk: &[u8]) -> Result<Option<PairingAction>, PairingError>;
}

impl Drive for FirstPairMachine {
    fn step_next(&mut self) -> Result<PairingAction, PairingError> {
        FirstPairMachine::next_action(self)
    }
    fn feed(&mut self, char: PairingChar, chunk: &[u8]) -> Result<Option<PairingAction>, PairingError> {
        FirstPairMachine::on_notify(self, char, chunk)
    }
}

impl Drive for CachedReconnectMachine {
    fn step_next(&mut self) -> Result<PairingAction, PairingError> {
        CachedReconnectMachine::next_action(self)
    }
    fn feed(&mut self, char: PairingChar, chunk: &[u8]) -> Result<Option<PairingAction>, PairingError> {
        CachedReconnectMachine::on_notify(self, char, chunk)
    }
}

/// Drives the machine to `Established`, feeding each scripted message when the machine asks
/// for it. Actions returned by `on_notify` are queued so the recorded order stays wire-exact.
fn drive(
    machine: &mut impl Drive,
    sensor: &mut ScriptedSensor,
    actions: &mut Vec<PairingAction>,
) -> Result<(), PairingError> {
    let mut queue: VecDeque<PairingAction> = VecDeque::new();
    loop {
        let action = match queue.pop_front() {
            Some(a) => a,
            None => match machine.step_next() {
                Ok(a) => a,
                Err(PairingError::AlreadyEstablished) => break,
                Err(e) => return Err(e),
            },
        };
        match action {
            established @ PairingAction::Established { .. } => {
                actions.push(established);
                break;
            }
            write @ PairingAction::WriteChar { .. } => actions.push(write),
            await_step @ PairingAction::AwaitNotify { char, .. } => {
                actions.push(await_step.clone());
                for chunk in sensor.take(char) {
                    if let Some(next) = machine.feed(char, &chunk)? {
                        queue.push_back(next);
                    }
                }
            }
        }
    }
    Ok(())
}

/// Drives until the machine returns the await named `label`, then stops (the caller feeds
/// that message itself).
fn drive_until_await(
    machine: &mut impl Drive,
    sensor: &mut ScriptedSensor,
    label: &str,
) -> Result<(), PairingError> {
    let mut queue: VecDeque<PairingAction> = VecDeque::new();
    loop {
        let action = match queue.pop_front() {
            Some(a) => a,
            None => machine.step_next()?,
        };
        match action {
            PairingAction::AwaitNotify { label: l, .. } if l == label => return Ok(()),
            PairingAction::WriteChar { .. } => continue,
            PairingAction::AwaitNotify { char, .. } => {
                for chunk in sensor.take(char) {
                    if let Some(next) = machine.feed(char, &chunk)? {
                        queue.push_back(next);
                    }
                }
            }
            PairingAction::Established { .. } => panic!("reached Established before {label}"),
        }
    }
}

// MARK: - Assertion helpers

fn assert_write_cmd(action: &PairingAction, byte: u8) {
    match action {
        PairingAction::WriteChar {
            char: PairingChar::SecCommandResponse,
            chunks,
        } => {
            assert_eq!(chunks.len(), 1, "command write is a single byte");
            assert_eq!(chunks[0], vec![byte], "command write payload");
        }
        other => panic!("expected a secCommandResponse write, got {other:?}"),
    }
}

fn assert_write_msg(action: &PairingAction, char: PairingChar, expected_chunks: &[Vec<u8>]) {
    match action {
        PairingAction::WriteChar { char: c, chunks } => {
            assert_eq!(c, &char, "write characteristic");
            assert_eq!(chunks, expected_chunks, "write chunks (§5.2 fragments)");
        }
        other => panic!("expected a write on {char:?}, got {other:?}"),
    }
}

#[allow(clippy::too_many_arguments)]
fn assert_await(
    action: &PairingAction,
    char: PairingChar,
    exactly: usize,
    expect_prefix: Option<&[u8]>,
    drain: bool,
    label: &str,
) {
    match action {
        PairingAction::AwaitNotify {
            char: c,
            exactly: e,
            expect_prefix: p,
            drain: d,
            label: l,
        } => {
            assert_eq!(c, &char, "await characteristic");
            assert_eq!(e, &exactly, "await exactly");
            assert_eq!(p.as_deref(), expect_prefix, "await prefix");
            assert_eq!(d, &drain, "await drain");
            assert_eq!(l, label, "await label");
        }
        other => panic!("expected AwaitNotify({label}), got {other:?}"),
    }
}

// MARK: - Shared table loaders

fn load_first_pair_tables(
    dir: &std::path::Path,
) -> (FirstPairTables, ScheduleTables, LibAESTables) {
    (
        FirstPairTables::from_dir(dir).expect("first-pair tables"),
        ScheduleTables::from_dir(dir).expect("schedule tables"),
        LibAESTables::from_dir(dir).expect("libaes tables"),
    )
}

/// Shared fixture for the cached-reconnect flow (PublicSmokeTests L63-116 values).
struct CachedFixture {
    r1: Vec<u8>,
    r2: Vec<u8>,
    raw_key: Vec<u8>,
    k_enc: Vec<u8>,
    iv_enc: Vec<u8>,
    k_auth_blob: Vec<u8>,
    phase6_body: Vec<u8>,
    phase5_wire: Vec<u8>,
}

impl CachedFixture {
    fn build(dir: &std::path::Path) -> CachedFixture {
        let r1 = ranges(0x10, 0x20);
        let r2 = ranges(0x30, 0x40);
        let raw_key = ranges(0x40, 0x50);
        let k_enc = ranges(0x50, 0x60);
        let iv_enc = ranges(0x60, 0x68);
        let k_auth_blob = vec![0xa1, 0xa2, 0xa3, 0xa4];
        // Kit ScriptedCommandPairingTransport: the phase-6 body is built under the same
        // phase-5 block the machine will use (PublicSmokeTests L72-79).
        let tables = LibAESTables::from_dir(dir).expect("libaes tables");
        let ctx = libaes::key_setup(&raw_key, &tables).expect("key setup");
        let block = |b: &[u8; 16]| libaes::phase5_block_encrypt(b, &ctx, &tables);
        let phase6_plain = [r2.as_slice(), r1.as_slice(), k_enc.as_slice(), iv_enc.as_slice()].concat();
        let (ct6, tag6) =
            ccm::encrypt(&PHASE6_NONCE7, &phase6_plain, &[], 4, &block).expect("phase6 encrypt");
        let phase6_body = [ct6, tag6, PHASE6_NONCE7.to_vec()].concat();
        assert_eq!(phase6_body.len(), challenge::PHASE6_WIRE_SIZE);
        let mut phase5_plain = r1.clone();
        phase5_plain.extend_from_slice(&r2);
        phase5_plain.extend_from_slice(&TAIL4);
        let phase5_wire = challenge::phase5_encrypt(&phase5_plain, &CHALLENGE_NONCE7, &block)
            .expect("phase5 encrypt")
            .wire_bytes();
        CachedFixture { r1, r2, raw_key, k_enc, iv_enc, k_auth_blob, phase6_body, phase5_wire }
    }

    fn sensor(&self) -> ScriptedSensor {
        ScriptedSensor {
            // PublicSmokeTests L81-84: commandResponses [0x08 0x17], [0x08 0x43].
            cmd: VecDeque::from(vec![vec![0x08, 0x17], vec![0x08, 0x43]]),
            cert_msgs: VecDeque::new(),
            challenge_msgs: VecDeque::from(vec![
                ScriptedSensor::fragments(&[self.r1.as_slice(), &CHALLENGE_NONCE7].concat(), 19),
                ScriptedSensor::fragments(&self.phase6_body, 19),
            ]),
        }
    }
}

// MARK: - 1: cached reconnect skips cert + ephemeral

/// 1:1 port of `testCachedReconnectHandshakeSkipsCertAndEphemeralExchange`
/// (PublicSmokeTests L63-116), driven through the byte-machine instead of the transport.
#[test]
fn cached_reconnect_handshake_skips_cert_and_ephemeral() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let f = CachedFixture::build(&dir);

    let machine_tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let mut machine = CachedReconnectMachine::new(
        machine_tables,
        &TAIL4,
        &f.r2,
        &f.raw_key,
        Some(f.k_auth_blob.clone()),
    )
    .expect("cached machine");

    let mut sensor = f.sensor();
    let mut actions = Vec::new();
    drive(&mut machine, &mut sensor, &mut actions).expect("cached handshake");

    // kit: commandWrites == [0x11, 0x08] byte-exact.
    let cmd_writes: Vec<u8> = actions
        .iter()
        .filter_map(|a| match a {
            PairingAction::WriteChar { char: PairingChar::SecCommandResponse, chunks } => {
                chunks.first().map(|c| c[0])
            }
            _ => None,
        })
        .collect();
    assert_eq!(cmd_writes, vec![0x11, 0x08]);

    // kit: messageWrites maps to [.challenge] exactly once, at the 54-B wire size.
    let challenge_writes: Vec<&Vec<Vec<u8>>> = actions
        .iter()
        .filter_map(|a| match a {
            PairingAction::WriteChar { char: PairingChar::SecChallengeData, chunks } => Some(chunks),
            _ => None,
        })
        .collect();
    assert_eq!(challenge_writes.len(), 1);
    let written: Vec<u8> = challenge_writes[0].iter().flat_map(|c| c[2..].to_vec()).collect();
    assert_eq!(written, f.phase5_wire);
    assert_eq!(written.len(), challenge::PHASE5_WIRE_SIZE);
    // §5.2: the chunks are the 54-B wire as 3 × 18-B offset-prefixed fragments.
    assert_eq!(
        challenge_writes[0],
        &frame::fragment_for_write(&f.phase5_wire, WRITE_CHUNK_PAYLOAD).expect("fragments")
    );

    // kit: result.phase5Sent.decrypt(aes:nonce:) == R1 || R2 || tail4.
    let block_tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let block_ctx = libaes::key_setup(&f.raw_key, &block_tables).expect("key setup");
    let block = |b: &[u8; 16]| libaes::phase5_block_encrypt(b, &block_ctx, &block_tables);
    let decrypted = ccm::decrypt(
        &CHALLENGE_NONCE7,
        &f.phase5_wire[..36],
        &f.phase5_wire[36..40],
        &[],
        &block,
    )
    .expect("phase5 decrypt");
    assert_eq!(
        decrypted,
        [f.r1.as_slice(), f.r2.as_slice(), TAIL4.as_slice()].concat()
    );

    // kit: sessionMaterial — the phase-6 echo verified inside the machine, so the
    // Established material must match the fixture.
    match actions.last().expect("established") {
        PairingAction::Established { k_enc, iv_enc, k_auth } => {
            assert_eq!(k_enc, &f.k_enc);
            assert_eq!(iv_enc, &f.iv_enc);
            assert_eq!(k_auth, &Some(f.k_auth_blob.clone()));
        }
        other => panic!("expected Established, got {other:?}"),
    }
}

// MARK: - 2: constructor size gates

/// 1:1 port of `testAuthorizationHandshakeRejectsNonFourByteTailBeforeTransportUse`
/// (PublicSmokeTests L44-61), extended to R2, the null entropy, and the cached raw key. A
/// failed constructor produces no machine and therefore no actions.
#[test]
fn tail4_and_r2_and_key_sizes_fail_before_transport_use() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let phone_cert = PhoneCert::bundled_162b(&dir).expect("phone cert");
    let phone_priv = vec![0x01u8; 32];
    let phone_pub65 = unhex(POST08_SENSOR_EPH65);
    let entropy = unhex(POST08_NULL_ENTROPY);
    let r2_16 = vec![0x30u8; 16];

    // First pair: tail4 3 B fails before any action exists.
    let (t, s, l) = load_first_pair_tables(&dir);
    let err = ctor_err(FirstPairMachine::new(
        t, s, l, &phone_cert.raw, &phone_priv, &phone_pub65, &entropy, &[0x01, 0x02, 0x03], &r2_16,
    ));
    assert!(matches!(err, PairingError::TailWrongSize { got: 3 }), "{err:?}");

    // First pair: R2 15 B.
    let (t, s, l) = load_first_pair_tables(&dir);
    let err = ctor_err(FirstPairMachine::new(
        t, s, l, &phone_cert.raw, &phone_priv, &phone_pub65, &entropy, &TAIL4, &[0x30u8; 15],
    ));
    assert!(matches!(err, PairingError::R2WrongSize { got: 15 }), "{err:?}");

    // First pair: null entropy short.
    let (t, s, l) = load_first_pair_tables(&dir);
    let err = ctor_err(FirstPairMachine::new(
        t,
        s,
        l,
        &phone_cert.raw,
        &phone_priv,
        &phone_pub65,
        &entropy[..0x119],
        &TAIL4,
        &r2_16,
    ));
    assert!(matches!(err, PairingError::Malformed { .. }), "{err:?}");

    // Cached: tail4 / R2 / raw key gates.
    let l = LibAESTables::from_dir(&dir).expect("libaes tables");
    let err = ctor_err(CachedReconnectMachine::new(l, &[0x01, 0x02, 0x03], &r2_16, &[0x40u8; 16], None));
    assert!(matches!(err, PairingError::TailWrongSize { got: 3 }), "{err:?}");

    let l = LibAESTables::from_dir(&dir).expect("libaes tables");
    let err = ctor_err(CachedReconnectMachine::new(l, &TAIL4, &[0x30u8; 15], &[0x40u8; 16], None));
    assert!(matches!(err, PairingError::R2WrongSize { got: 15 }), "{err:?}");

    let l = LibAESTables::from_dir(&dir).expect("libaes tables");
    let err = ctor_err(CachedReconnectMachine::new(l, &TAIL4, &r2_16, &[0x40u8; 15], None));
    assert!(matches!(err, PairingError::KeyWrongSize { got: 15 }), "{err:?}");
}

// MARK: - 3: first pair, command-gated, byte-exact

/// PLAN_T1DMDROID.md §5.3 steps 1–8, fully pinned: a synthesized command-gated run over the
/// live-fixture sensor material (PublicSmokeTests L119 cert; SessionKeyTests post08 entropy
/// and ephemeral point). This test pins the whole §5.3 wire order byte-exact.
#[test]
fn first_pair_command_gated_happy_path_byte_exact() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let sensor_cert_raw = unhex(SENSOR_CERT_RAW);
    let sensor_eph65 = unhex(POST08_SENSOR_EPH65);

    // §5.3: the accepted post08 entropy drives both the null scalar and the process2(5)
    // wire point (SessionKeyTests coupling pin); attempts > 1 are rejected.
    let t = FirstPairTables::from_dir(&dir).expect("first-pair tables");
    let accepted = unhex(POST08_NULL_ENTROPY);
    let calls = std::cell::Cell::new(0usize);
    let native = session_key::make_first_pair_native_ephemeral(
        4,
        move |byte_count| {
            assert_eq!(byte_count, 0x11a);
            let n = calls.get() + 1;
            calls.set(n);
            if n == 1 {
                Ok(accepted.clone())
            } else {
                Err(libre3_core::CryptoError::Slice {
                    reason: "rejected633fa8NullEntropy".to_owned(),
                })
            }
        },
        &t,
    )
    .expect("native ephemeral on the first attempt");
    let phone_cert = PhoneCert::bundled_162b(&dir).expect("phone cert");
    assert_eq!(&phone_cert.raw[..2], &[0x03, 0x03]);

    // The expected raw key derives from the SAME inputs the machine sees.
    let sched = ScheduleTables::from_dir(&dir).expect("schedule tables");
    let inputs = FirstPairPhase5KeyInputs {
        entry_source: BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE.to_vec(),
        null_entropy_11a: native.null_entropy_11a.clone(),
        sensor_ephemeral_pub65: sensor_eph65.clone(),
        sensor_static_pub65: cert::SensorCert::parse(&sensor_cert_raw)
            .expect("sensor cert")
            .static_pub
            .clone(),
        static_scalar_window: phone_cert.phase5_static_scalar_window_override(),
    };
    let expected = session_key::derive_first_pair_phase5_material(&inputs, &t, &sched)
        .expect("expected phase5 material");

    // Synthesized sensor material, built under the expected raw key's block.
    let r1 = ranges(0x10, 0x20);
    let r2 = ranges(0x30, 0x40);
    let k_enc = ranges(0x50, 0x60);
    let iv_enc = ranges(0x60, 0x68);
    let check_tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let check_ctx = libaes::key_setup(&expected.raw_key, &check_tables).expect("key setup");
    let block_check = |b: &[u8; 16]| libaes::phase5_block_encrypt(b, &check_ctx, &check_tables);
    let mut phase5_plain = r1.clone();
    phase5_plain.extend_from_slice(&r2);
    phase5_plain.extend_from_slice(&TAIL4);
    let phase5_wire = challenge::phase5_encrypt(&phase5_plain, &CHALLENGE_NONCE7, &block_check)
        .expect("phase5 encrypt")
        .wire_bytes();
    let phase6_plain = [r2.as_slice(), r1.as_slice(), k_enc.as_slice(), iv_enc.as_slice()].concat();
    let (ct6, tag6) =
        ccm::encrypt(&PHASE6_NONCE7, &phase6_plain, &[], 4, &block_check).expect("phase6 encrypt");
    let phase6_body = [ct6, tag6, PHASE6_NONCE7.to_vec()].concat();

    // The machine takes the tables by value (the check tables were a separate load).
    let phone_priv32 = native.key_pair.private_key.to_bytes().to_vec();
    let mut machine = FirstPairMachine::new(
        t,
        sched,
        check_tables,
        &phone_cert.raw,
        &phone_priv32,
        &native.key_pair.public_key65,
        &native.null_entropy_11a,
        &TAIL4,
        &r2,
    )
    .expect("first-pair machine");

    let mut sensor = ScriptedSensor {
        // Command responses RAW each (PublicSmokeTests L81-84 style; the clock is unframed).
        cmd: VecDeque::from(vec![
            vec![0x04],       // CertificateAccepted
            vec![0x0a],       // CertificateReady
            vec![0x0f],       // EphemeralReady
            vec![0x08, 0x17], // ChallengeLoadDone
            vec![0x08, 0x43], // PatchChallengeLoadDone
        ]),
        cert_msgs: VecDeque::from(vec![
            ScriptedSensor::fragments(&sensor_cert_raw, 19),
            ScriptedSensor::fragments(&sensor_eph65, 19),
        ]),
        challenge_msgs: VecDeque::from(vec![
            ScriptedSensor::fragments(&[r1.as_slice(), &CHALLENGE_NONCE7].concat(), 19),
            ScriptedSensor::fragments(&phase6_body, 19),
        ]),
    };

    let mut actions = Vec::new();
    drive(&mut machine, &mut sensor, &mut actions).expect("first pair handshake");

    assert_eq!(actions.len(), 21, "§5.3 emits 11 writes + 9 awaits + Established");
    assert_write_cmd(&actions[0], 0x01);
    assert_write_cmd(&actions[1], 0x02);
    assert_write_msg(
        &actions[2],
        PairingChar::SecCertData,
        &frame::fragment_for_write(&phone_cert.raw, WRITE_CHUNK_PAYLOAD).expect("cert fragments"),
    );
    assert_write_cmd(&actions[3], 0x03);
    assert_await(&actions[4], PairingChar::SecCommandResponse, 1, Some(&[0x04]), true, "CertificateAccepted");
    assert_write_cmd(&actions[5], 0x09);
    assert_await(&actions[6], PairingChar::SecCommandResponse, 1, Some(&[0x0a]), true, "CertificateReady");
    assert_await(&actions[7], PairingChar::SecCertData, 140, None, false, "sensorCertNotify");
    assert_write_cmd(&actions[8], 0x0d);
    let mut phase3 = native.key_pair.public_key65.clone();
    phase3.resize(72, 0);
    assert_write_msg(
        &actions[9],
        PairingChar::SecCertData,
        &frame::fragment_for_write(&phase3, WRITE_CHUNK_PAYLOAD).expect("eph fragments"),
    );
    assert_write_cmd(&actions[10], 0x0e);
    assert_await(&actions[11], PairingChar::SecCommandResponse, 1, Some(&[0x0f]), true, "EphemeralReady");
    assert_await(&actions[12], PairingChar::SecCertData, 65, None, false, "sensorEphemeralNotify");
    assert_write_cmd(&actions[13], 0x11);
    assert_await(&actions[14], PairingChar::SecCommandResponse, 1, Some(&[0x08]), true, "ChallengeLoadDone");
    assert_await(&actions[15], PairingChar::SecChallengeData, 23, None, false, "sensorR1Notify");
    assert_write_msg(
        &actions[16],
        PairingChar::SecChallengeData,
        &frame::fragment_for_write(&phase5_wire, WRITE_CHUNK_PAYLOAD).expect("phase5 fragments"),
    );
    assert_write_cmd(&actions[17], 0x08);
    assert_await(&actions[18], PairingChar::SecCommandResponse, 1, Some(&[0x08]), true, "PatchChallengeLoadDone");
    assert_await(&actions[19], PairingChar::SecChallengeData, 67, None, false, "phase6Notify");
    match &actions[20] {
        PairingAction::Established { k_enc: k, iv_enc: iv, k_auth } => {
            assert_eq!(k, &k_enc);
            assert_eq!(iv, &iv_enc);
            assert!(k_auth.is_none(), "first pair carries no kAuth");
        }
        other => panic!("expected Established, got {other:?}"),
    }

    // Post-established contract: no further actions, no further feeding.
    assert!(matches!(machine.step_next(), Err(PairingError::AlreadyEstablished)));
    assert!(matches!(
        machine.feed(PairingChar::SecCommandResponse, &[0x00, 0x01]),
        Err(PairingError::AlreadyEstablished)
    ));
}

// MARK: - 4: command-clock rejection

#[test]
fn command_response_mismatch_is_command_rejected() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let (t, s, l) = load_first_pair_tables(&dir);
    let phone_cert = PhoneCert::bundled_162b(&dir).expect("phone cert");
    let mut machine = FirstPairMachine::new(
        t,
        s,
        l,
        &phone_cert.raw,
        &[0x01u8; 32],
        &unhex(POST08_SENSOR_EPH65),
        &unhex(POST08_NULL_ENTROPY),
        &TAIL4,
        &ranges(0x30, 0x40),
    )
    .expect("first-pair machine");

    drive_until_await(&mut machine, &mut ScriptedSensor::empty(), "CertificateAccepted")
        .expect("drive to CertificateAccepted");

    // A wrong prefix on the command clock: raw `05 01` at CertificateAccepted.
    let err = machine
        .on_notify(PairingChar::SecCommandResponse, &[0x05, 0x01])
        .expect_err("wrong prefix must be rejected");
    match err {
        PairingError::CommandRejected { step, got } => {
            assert!(step.contains("CertificateAccepted"), "step: {step}");
            assert!(got.starts_with("05"), "got: {got}");
        }
        other => panic!("expected CommandRejected, got {other:?}"),
    }
    // Fail-closed: the machine is dead, never established.
    assert!(matches!(machine.step_next(), Err(PairingError::CommandRejected { .. })));
}

// MARK: - 5: sensor cert wrong size + bad signature

#[test]
fn sensor_cert_wrong_size_and_bad_signature_fail_closed() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let sensor_cert_raw = unhex(SENSOR_CERT_RAW);
    let sensor_eph65 = unhex(POST08_SENSOR_EPH65);

    // A 139-byte cert never completes on its own; the sensor's next cert-character message
    // (the ephemeral point) arriving on the same reassembler exposes the short message.
    let (t, s, l) = load_first_pair_tables(&dir);
    let phone_cert = PhoneCert::bundled_162b(&dir).expect("phone cert");
    let mut machine = FirstPairMachine::new(
        t,
        s,
        l,
        &phone_cert.raw,
        &[0x01u8; 32],
        &sensor_eph65,
        &unhex(POST08_NULL_ENTROPY),
        &TAIL4,
        &ranges(0x30, 0x40),
    )
    .expect("first-pair machine");
    let mut sensor = ScriptedSensor {
        cmd: VecDeque::from(vec![vec![0x04], vec![0x0a], vec![0x0f]]),
        cert_msgs: VecDeque::from(vec![ScriptedSensor::fragments(&sensor_cert_raw[..139], 19)]),
        challenge_msgs: VecDeque::new(),
    };
    drive_until_await(&mut machine, &mut sensor, "sensorCertNotify").expect("drive to sensor cert");
    for frag in ScriptedSensor::fragments(&sensor_cert_raw[..139], 19) {
        let fed = machine.on_notify(PairingChar::SecCertData, &frag).expect("buffered");
        assert!(fed.is_none(), "a short cert must not complete");
    }
    // seq continues from the cert fragments (0..7) — the machine takes everything reassembled.
    let err = machine
        .on_notify(PairingChar::SecCertData, &ScriptedSensor::one_chunk(&sensor_eph65, 8))
        .expect_err("short cert must fail closed");
    assert!(matches!(err, PairingError::NotifyWrongSize { want: 140, .. }), "{err:?}");

    // A single flipped signature byte must fail verification, closed.
    let (t, s, l) = load_first_pair_tables(&dir);
    let phone_cert = PhoneCert::bundled_162b(&dir).expect("phone cert");
    let mut machine = FirstPairMachine::new(
        t,
        s,
        l,
        &phone_cert.raw,
        &[0x01u8; 32],
        &sensor_eph65,
        &unhex(POST08_NULL_ENTROPY),
        &TAIL4,
        &ranges(0x30, 0x40),
    )
    .expect("first-pair machine");
    let mut broken_cert = sensor_cert_raw.clone();
    let last = broken_cert.len() - 1;
    broken_cert[last] ^= 0xff;
    let mut sensor = ScriptedSensor {
        cmd: VecDeque::from(vec![vec![0x04], vec![0x0a], vec![0x0f]]),
        cert_msgs: VecDeque::from(vec![ScriptedSensor::fragments(&broken_cert, 19)]),
        challenge_msgs: VecDeque::new(),
    };
    drive_until_await(&mut machine, &mut sensor, "sensorCertNotify").expect("drive to sensor cert");
    let mut rejected = false;
    for frag in ScriptedSensor::fragments(&broken_cert, 19) {
        if let Err(e) = machine.on_notify(PairingChar::SecCertData, &frag) {
            assert!(matches!(e, PairingError::CertRejected), "flipped signature: {e:?}");
            rejected = true;
            break;
        }
    }
    assert!(rejected, "flipped signature byte must be CertRejected");
}

// MARK: - 6: phase 6 echo mismatch

#[test]
fn phase6_echo_mismatch_fails_closed() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let f = CachedFixture::build(&dir);

    // The phase-6 plaintext carries a REPLACED R2 with a correct tag, so the failure is the
    // echo check, not the CCM tag (kit phase6VerificationFailed, PairingFlow.swift L490-495).
    let tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let ctx = libaes::key_setup(&f.raw_key, &tables).expect("key setup");
    let block = |b: &[u8; 16]| libaes::phase5_block_encrypt(b, &ctx, &tables);
    let mut wrong_r2 = f.r2.clone();
    wrong_r2[0] ^= 0xff;
    let forged_plain =
        [wrong_r2.as_slice(), f.r1.as_slice(), f.k_enc.as_slice(), f.iv_enc.as_slice()].concat();
    let (ct6, tag6) = ccm::encrypt(&PHASE6_NONCE7, &forged_plain, &[], 4, &block).expect("phase6 encrypt");
    let forged_body = [ct6, tag6, PHASE6_NONCE7.to_vec()].concat();

    let machine_tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let mut machine = CachedReconnectMachine::new(machine_tables, &TAIL4, &f.r2, &f.raw_key, None)
        .expect("cached machine");
    let mut sensor = ScriptedSensor {
        cmd: VecDeque::from(vec![vec![0x08, 0x17], vec![0x08, 0x43]]),
        cert_msgs: VecDeque::new(),
        challenge_msgs: VecDeque::from(vec![
            ScriptedSensor::fragments(&[f.r1.as_slice(), &CHALLENGE_NONCE7].concat(), 19),
            ScriptedSensor::fragments(&forged_body, 19),
        ]),
    };
    let err = drive(&mut machine, &mut sensor, &mut Vec::new()).expect_err("echo mismatch");
    match err {
        PairingError::Phase6EchoMismatch { field } => assert_eq!(field, "R2", "field: {field}"),
        other => panic!("expected Phase6EchoMismatch, got {other:?}"),
    }
}

// MARK: - 7: early arrival across characteristics

#[test]
fn early_arrival_buffers_across_chars() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let sensor_cert_raw = unhex(SENSOR_CERT_RAW);
    let sensor_eph65 = unhex(POST08_SENSOR_EPH65);

    let t = FirstPairTables::from_dir(&dir).expect("first-pair tables");
    let accepted = unhex(POST08_NULL_ENTROPY);
    let calls = std::cell::Cell::new(0usize);
    let native = session_key::make_first_pair_native_ephemeral(
        4,
        move |byte_count| {
            assert_eq!(byte_count, 0x11a);
            let n = calls.get() + 1;
            calls.set(n);
            if n == 1 {
                Ok(accepted.clone())
            } else {
                Err(libre3_core::CryptoError::Slice {
                    reason: "rejected633fa8NullEntropy".to_owned(),
                })
            }
        },
        &t,
    )
    .expect("native ephemeral on the first attempt");
    let phone_cert = PhoneCert::bundled_162b(&dir).expect("phone cert");
    let sched = ScheduleTables::from_dir(&dir).expect("schedule tables");
    let inputs = FirstPairPhase5KeyInputs {
        entry_source: BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE.to_vec(),
        null_entropy_11a: native.null_entropy_11a.clone(),
        sensor_ephemeral_pub65: sensor_eph65.clone(),
        sensor_static_pub65: cert::SensorCert::parse(&sensor_cert_raw)
            .expect("sensor cert")
            .static_pub
            .clone(),
        static_scalar_window: phone_cert.phase5_static_scalar_window_override(),
    };
    let expected = session_key::derive_first_pair_phase5_material(&inputs, &t, &sched)
        .expect("expected phase5 material");
    let r1 = ranges(0x10, 0x20);
    let r2 = ranges(0x30, 0x40);
    let k_enc = ranges(0x50, 0x60);
    let iv_enc = ranges(0x60, 0x68);
    let check_tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let check_ctx = libaes::key_setup(&expected.raw_key, &check_tables).expect("key setup");
    let block_check = |b: &[u8; 16]| libaes::phase5_block_encrypt(b, &check_ctx, &check_tables);
    let mut phase5_plain = r1.clone();
    phase5_plain.extend_from_slice(&r2);
    phase5_plain.extend_from_slice(&TAIL4);
    let phase5_wire = challenge::phase5_encrypt(&phase5_plain, &CHALLENGE_NONCE7, &block_check)
        .expect("phase5 encrypt")
        .wire_bytes();
    let phase6_plain = [r2.as_slice(), r1.as_slice(), k_enc.as_slice(), iv_enc.as_slice()].concat();
    let (ct6, tag6) =
        ccm::encrypt(&PHASE6_NONCE7, &phase6_plain, &[], 4, &block_check).expect("phase6 encrypt");
    let phase6_body = [ct6, tag6, PHASE6_NONCE7.to_vec()].concat();

    let phone_priv32 = native.key_pair.private_key.to_bytes().to_vec();
    let mut machine = FirstPairMachine::new(
        t,
        sched,
        check_tables,
        &phone_cert.raw,
        &phone_priv32,
        &native.key_pair.public_key65,
        &native.null_entropy_11a,
        &TAIL4,
        &r2,
    )
    .expect("first-pair machine");

    // ALL sensor bytes — every notify chunk, all three characteristics — in wire emission
    // order, fed BEFORE any next_action call.
    let mut chunks: Vec<(PairingChar, Vec<u8>)> = Vec::new();
    chunks.push((PairingChar::SecCommandResponse, vec![0x04]));
    chunks.push((PairingChar::SecCommandResponse, vec![0x0a]));
    for frag in ScriptedSensor::fragments(&sensor_cert_raw, 19) {
        chunks.push((PairingChar::SecCertData, frag));
    }
    chunks.push((PairingChar::SecCommandResponse, vec![0x0f]));
    for frag in ScriptedSensor::fragments(&sensor_eph65, 19) {
        chunks.push((PairingChar::SecCertData, frag));
    }
    chunks.push((PairingChar::SecCommandResponse, vec![0x08, 0x17]));
    for frag in ScriptedSensor::fragments(&[r1.as_slice(), &CHALLENGE_NONCE7].concat(), 19) {
        chunks.push((PairingChar::SecChallengeData, frag));
    }
    chunks.push((PairingChar::SecCommandResponse, vec![0x08, 0x43]));
    for frag in ScriptedSensor::fragments(&phase6_body, 19) {
        chunks.push((PairingChar::SecChallengeData, frag));
    }

    let mut all: Vec<PairingAction> = Vec::new();
    for (char, chunk) in &chunks {
        match machine.on_notify(*char, chunk) {
            Ok(Some(a)) => all.push(a),
            Ok(None) => {}
            Err(e) => panic!("buffered feed failed: {e:?}"),
        }
    }
    // Drain the machine: it must finish the walk from the buffer alone.
    loop {
        match machine.step_next() {
            Ok(established @ PairingAction::Established { .. }) => {
                all.push(established);
                break;
            }
            Ok(a) => all.push(a),
            Err(PairingError::AlreadyEstablished) => break,
            Err(e) => panic!("early-arrival walk failed: {e:?}"),
        }
    }

    // The writes arrive byte-exact in the §5.3 order; the only await surfaced mid-feed is the
    // R1 await, which the buffer then satisfies.
    let writes: Vec<&PairingAction> = all
        .iter()
        .filter(|a| matches!(a, PairingAction::WriteChar { .. }))
        .collect();
    assert_eq!(writes.len(), 11, "11 writes on the §5.3 path");
    assert_write_cmd(writes[0], 0x01);
    assert_write_cmd(writes[1], 0x02);
    assert_write_msg(
        writes[2],
        PairingChar::SecCertData,
        &frame::fragment_for_write(&phone_cert.raw, WRITE_CHUNK_PAYLOAD).expect("cert fragments"),
    );
    assert_write_cmd(writes[3], 0x03);
    assert_write_cmd(writes[4], 0x09);
    assert_write_cmd(writes[5], 0x0d);
    let mut phase3 = native.key_pair.public_key65.clone();
    phase3.resize(72, 0);
    assert_write_msg(
        writes[6],
        PairingChar::SecCertData,
        &frame::fragment_for_write(&phase3, WRITE_CHUNK_PAYLOAD).expect("eph fragments"),
    );
    assert_write_cmd(writes[7], 0x0e);
    assert_write_cmd(writes[8], 0x11);
    assert_write_msg(
        writes[9],
        PairingChar::SecChallengeData,
        &frame::fragment_for_write(&phase5_wire, WRITE_CHUNK_PAYLOAD).expect("phase5 fragments"),
    );
    assert_write_cmd(writes[10], 0x08);
    match all.last().expect("established") {
        PairingAction::Established { k_enc: k, iv_enc: iv, k_auth } => {
            assert_eq!(k, &k_enc);
            assert_eq!(iv, &iv_enc);
            assert!(k_auth.is_none());
        }
        other => panic!("expected Established, got {other:?}"),
    }
}

// MARK: - 8: established machines refuse further work

#[test]
fn established_then_next_action_errors() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let f = CachedFixture::build(&dir);
    let machine_tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let mut machine =
        CachedReconnectMachine::new(machine_tables, &TAIL4, &f.r2, &f.raw_key, None).expect("machine");
    let mut sensor = f.sensor();
    let mut actions = Vec::new();
    drive(&mut machine, &mut sensor, &mut actions).expect("cached handshake");
    assert!(matches!(actions.last(), Some(PairingAction::Established { .. })));

    let err = machine.step_next().expect_err("next_action after established");
    assert!(matches!(err, PairingError::AlreadyEstablished), "{err:?}");
    let fed = machine
        .feed(PairingChar::SecCommandResponse, &[0x00, 0x08])
        .expect_err("on_notify after established");
    assert!(matches!(fed, PairingError::AlreadyEstablished), "{fed:?}");
}

// MARK: - 9: R1 wrong size

#[test]
fn r1_wrong_size_fails_closed() {
    let Some(dir) = tables_dir() else {
        eprintln!("skipping: LIBRE3_TABLES_DIR unset");
        return;
    };
    let f = CachedFixture::build(&dir);
    let machine_tables = LibAESTables::from_dir(&dir).expect("libaes tables");
    let mut machine =
        CachedReconnectMachine::new(machine_tables, &TAIL4, &f.r2, &f.raw_key, None).expect("machine");

    // Drive to the R1 await, then feed a 22-byte challenge (one byte short).
    let mut sensor = f.sensor();
    drive_until_await(&mut machine, &mut sensor, "sensorR1Notify").expect("drive to R1");
    let short_r1 = [&f.r1[..15], &CHALLENGE_NONCE7].concat();
    assert_eq!(short_r1.len(), 22);
    for frag in ScriptedSensor::fragments(&short_r1, 19) {
        let fed = machine.on_notify(PairingChar::SecChallengeData, &frag).expect("buffered");
        assert!(fed.is_none(), "a short R1 must not complete");
    }
    // The sensor's next challenge-character message (phase 6) arriving on the same
    // reassembler exposes the short message — NotifyWrongSize, fail-closed.
    let err = machine
        .on_notify(PairingChar::SecChallengeData, &ScriptedSensor::one_chunk(&f.phase6_body, 2))
        .expect_err("short R1 must fail closed");
    assert!(matches!(err, PairingError::NotifyWrongSize { want: 23, .. }), "{err:?}");
}
