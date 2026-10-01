//! §5.3/§5.4 pairing byte-machines, ported from kit PairingFlow.swift; no I/O, fail-closed (§15).

use std::collections::VecDeque;

use crate::challenge::{self, PHASE6_WIRE_SIZE};
use crate::cert::{PhoneCert, SensorCert, SENSOR_CERT_SIZE};
use crate::ephemeral::{EphemeralKeyPair, exchange};
use crate::frame::{WRITE_CHUNK_PAYLOAD, NotifyReassembler, fragment_for_write};
use crate::libaes::{self, LibAESTables};
use crate::phase5::ScheduleTables;
use crate::session_key::{self, BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE, FirstPairPhase5KeyInputs};
use crate::vm::FirstPairTables;
use crate::CryptoError;

// MARK: - Characteristics

/// The three security-service characteristics the handshake runs on (PLAN_T1DMDROID.md §5.1).
/// UUIDs cited from LibreSensorGATT.swift L44/L46/L49.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PairingChar {
    /// `secCertData` — certs + ephemeral keys (phases 1–4).
    SecCertData,
    /// `secChallengeData` — phase 5 challenge, phase 6 response.
    SecChallengeData,
    /// `secCommandResponse` — the single-byte command clock.
    SecCommandResponse,
}

impl PairingChar {
    /// Full lowercase UUID (LibreSensorGATT.swift L44/L46/L49; base
    /// `0898xxxx-EF89-11E9-81B4-2A2AE2DBCCE4`).
    pub fn uuid(self) -> &'static str {
        match self {
            PairingChar::SecCertData => "089823fa-ef89-11e9-81b4-2a2ae2dbcce4",
            PairingChar::SecChallengeData => "089822ce-ef89-11e9-81b4-2a2ae2dbcce4",
            PairingChar::SecCommandResponse => "08982198-ef89-11e9-81b4-2a2ae2dbcce4",
        }
    }

    /// Reassembler slot for this machine's per-character reassemblers.
    fn index(self) -> usize {
        match self {
            PairingChar::SecCertData => 0,
            PairingChar::SecChallengeData => 1,
            PairingChar::SecCommandResponse => 2,
        }
    }
}

// MARK: - Wire sizes

/// Phase 3 write is the 65-B wire point padded to 72 B = 4 × 18-B fragments
/// (PairingFlow.swift L222-226: "padded the message to 72B = 4 fragments × 18B").
pub const PHONE_EPHEMERAL_WIRE_SIZE: usize = 72;
/// Sensor ephemeral pubkey notify (PairingFlow.swift L338).
pub const SENSOR_EPHEMERAL_SIZE: usize = 65;
/// Sensor challenge notify `R1_16 || nonce7` (PairingFlow.swift L366; §5.5).
pub const SENSOR_CHALLENGE_SIZE: usize = 23;
/// First-pair null-entropy block length (`builder633fa8NullEntropyBytes`, 0x11a).
pub const NULL_ENTROPY_11A_SIZE: usize = 0x11a;

// MARK: - Actions and errors

/// One step of driver work (PLAN_T1DMDROID.md §4: `WriteChar(uuid, bytes)` | `AwaitNotify` |
/// `Established{kEnc, ivEnc, kAuth}`). Write chunks carry the logical message already
/// fragmented per §5.2 (`frame::fragment_for_write`, 18-byte payloads).
#[derive(Debug, Clone, PartialEq)]
pub enum PairingAction {
    WriteChar {
        char: PairingChar,
        chunks: Vec<Vec<u8>>,
    },
    AwaitNotify {
        char: PairingChar,
        exactly: usize,
        expect_prefix: Option<Vec<u8>>,
        drain: bool,
        label: String,
    },
    Established {
        k_enc: Vec<u8>,
        iv_enc: Vec<u8>,
        k_auth: Option<Vec<u8>>,
    },
}

/// PLAN_T1DMDROID.md §15: the pairing layer's failures, all fail-closed. Kit spelling per
/// PairingFlow.swift L129-149.
#[derive(Debug, Clone, thiserror::Error)]
pub enum PairingError {
    /// Kit `unexpectedCommandResponse(label:expectedPrefix:actual:)` (L133): the command-clock
    /// notify did not start with the expected prefix; `got` is the hex of what arrived.
    #[error("command rejected at {step}: got {got}")]
    CommandRejected { step: String, got: String },
    /// Kit `sensorCertificateVerificationFailed` (L147-148).
    #[error("sensor certificate verification failed")]
    CertRejected,
    /// Kit `phase6VerificationFailed` (L139): the Phase 6 R1/R2 echo did not verify.
    #[error("phase6 echo mismatch: {field}")]
    Phase6EchoMismatch { field: String },
    /// Kit `sensorR1WrongSize` (L134).
    #[error("R1 wrong size: got {got}")]
    R1WrongSize { got: usize },
    /// Kit `tail4WrongSize` (L136).
    #[error("tail4 wrong size: got {got}")]
    TailWrongSize { got: usize },
    /// Kit `ChallengeError.wrongPlaintextSize` on the 16-B R2 (PairingFlow.swift L464-466).
    #[error("R2 wrong size: got {got}")]
    R2WrongSize { got: usize },
    /// Kit `ChallengeError.wrongKeySize` (PairingFlow.swift L470-472).
    #[error("phase5 raw key wrong size: got {got}")]
    KeyWrongSize { got: usize },
    /// A reassembled notify had the wrong size (cert 140 / ephemeral 65 / R1 23 / phase 6 67).
    #[error("notify wrong size: want {want}, got {got}")]
    NotifyWrongSize { want: usize, got: usize },
    /// Runtime tables missing or mismatched (§9); raised at construction, before any action.
    #[error("tables: {reason}")]
    Tables { reason: String },
    /// Reassembly/parse/derivation failures with kit-style reasons, all fail-closed.
    #[error("malformed: {reason}")]
    Malformed { reason: String },
    /// The machine already reached Phase 6 and emitted `Established`; it accepts no further
    /// steps.
    #[error("machine already established")]
    AlreadyEstablished,
}

// MARK: - Step table

/// One protocol step. Write labels are cited in the step tables below; await labels are the
/// kit's `waitFor` labels (PairingFlow.swift L271-282/L408-419).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Step {
    WriteCommand { byte: u8 },
    WritePhoneCert,
    WritePhoneEphemeral,
    AwaitCommand { prefix: u8, label: &'static str },
    AwaitSensorCert,
    AwaitSensorEph,
    AwaitR1,
    WritePhase5,
    AwaitPhase6,
    Done,
}

/// The await a driver is currently parked on.
struct AwaitSpec {
    char: PairingChar,
    exactly: usize,
    expect_prefix: Option<Vec<u8>>,
    drain: bool,
    label: &'static str,
}

/// Machine flavor: which tables and key material drive the Phase 5 derivation.
enum Flavor {
    FirstPair {
        firstpair_tables: FirstPairTables,
        schedule_tables: ScheduleTables,
        /// ECDH private key; the Phase 3 wire point is the injected `process2(5)` override
        /// (`EphemeralExchange.swift` L39-49 semantics).
        key_pair: EphemeralKeyPair,
        null_entropy_11a: Vec<u8>,
        static_scalar_window: Option<Vec<u8>>,
    },
    Cached {
        raw_key: [u8; 16],
    },
}

// MARK: - Core driver

/// Shared byte-machine driver for both machines. One reassembler per characteristic, kept
/// across awaits; reset only when a message completes and the buffer empties (§5.2). The
/// command clock (`secCommandResponse`) is the exception: the sensor answers it with RAW
/// bytes, no seq-prefix framing (live EU 2026-09-24: a bare 1-B `04` notify), so its buffer
/// is a plain byte queue that drains wholesale.
struct Core {
    libaes: LibAESTables,
    flavor: Flavor,
    steps: Vec<Step>,
    cursor: usize,
    pending: VecDeque<PairingAction>,
    reasm: [NotifyReassembler; 3],
    /// Raw command-clock bytes (`secCommandResponse`), drained wholesale by `drain` awaits.
    raw_cmd: Vec<u8>,
    // Precomputed write payloads.
    phone_cert_chunks: Vec<Vec<u8>>,
    phone_eph_chunks: Vec<Vec<u8>>,
    r2: Vec<u8>,
    tail4: Vec<u8>,
    // Cached-reconnect passthrough (§5.4): the persisted kAuth blob rides on `Established`.
    k_auth: Option<Vec<u8>>,
    // Runtime state.
    sensor_cert: Option<SensorCert>,
    phase5_ctx: Option<Vec<u8>>,
    phase5_chunks: Option<Vec<Vec<u8>>>,
    r1: Option<Vec<u8>>,
    result: Option<(Vec<u8>, Vec<u8>, Option<Vec<u8>>)>,
    established: bool,
    failed: Option<PairingError>,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

impl Core {
    fn step(&self) -> Step {
        self.steps.get(self.cursor).copied().unwrap_or(Step::Done)
    }

    /// Marks the machine dead and returns the error; every later call replays it (fail-closed).
    fn fail(&mut self, e: PairingError) -> PairingError {
        self.failed = Some(e.clone());
        e
    }

    fn fail_malformed(&mut self, e: CryptoError) -> PairingError {
        let reason = e.to_string();
        self.fail(PairingError::Malformed { reason })
    }

    /// The await the machine is parked on, if any.
    fn awaiting(&self) -> Option<AwaitSpec> {
        match self.step() {
            // Command-clock responses drain whatever is buffered; `exactly` is the prefix
            // length (PairingFlow.swift L271-282: waitFor starts-with check).
            Step::AwaitCommand { prefix, label } => Some(AwaitSpec {
                char: PairingChar::SecCommandResponse,
                exactly: 1,
                expect_prefix: Some(vec![prefix]),
                drain: true,
                label,
            }),
            Step::AwaitSensorCert => Some(AwaitSpec {
                char: PairingChar::SecCertData,
                exactly: SENSOR_CERT_SIZE,
                expect_prefix: None,
                drain: false,
                label: "sensorCertNotify",
            }),
            Step::AwaitSensorEph => Some(AwaitSpec {
                char: PairingChar::SecCertData,
                exactly: SENSOR_EPHEMERAL_SIZE,
                expect_prefix: None,
                drain: false,
                label: "sensorEphemeralNotify",
            }),
            Step::AwaitR1 => Some(AwaitSpec {
                char: PairingChar::SecChallengeData,
                exactly: SENSOR_CHALLENGE_SIZE,
                expect_prefix: None,
                drain: false,
                label: "sensorR1Notify",
            }),
            Step::AwaitPhase6 => Some(AwaitSpec {
                char: PairingChar::SecChallengeData,
                exactly: PHASE6_WIRE_SIZE,
                expect_prefix: None,
                drain: false,
                label: "phase6Notify",
            }),
            _ => None,
        }
    }

    /// Queues the write actions between the cursor and the next await step. Writes are
    /// latched as emitted when returned (contract rule (a)); the frontier resolution makes
    /// them queue up while buffered notifies drive the machine ahead.
    fn resolve_frontier(&mut self) -> Result<(), PairingError> {
        loop {
            match self.step() {
                Step::WriteCommand { byte } => {
                    self.cursor += 1;
                    self.pending.push_back(PairingAction::WriteChar {
                        char: PairingChar::SecCommandResponse,
                        chunks: vec![vec![byte]],
                    });
                }
                Step::WritePhoneCert => {
                    self.cursor += 1;
                    self.pending.push_back(PairingAction::WriteChar {
                        char: PairingChar::SecCertData,
                        chunks: self.phone_cert_chunks.clone(),
                    });
                }
                Step::WritePhoneEphemeral => {
                    self.cursor += 1;
                    self.pending.push_back(PairingAction::WriteChar {
                        char: PairingChar::SecCertData,
                        chunks: self.phone_eph_chunks.clone(),
                    });
                }
                Step::WritePhase5 => {
                    let Some(chunks) = self.phase5_chunks.clone() else {
                        return Err(self.fail(PairingError::Malformed {
                            reason: "internal: phase5 wire not built".to_owned(),
                        }));
                    };
                    self.cursor += 1;
                    self.pending.push_back(PairingAction::WriteChar {
                        char: PairingChar::SecChallengeData,
                        chunks,
                    });
                }
                _ => return Ok(()),
            }
        }
    }

    /// Bytes currently buffered for `char`: the raw command-clock queue, or the framed
    /// reassembler's available count.
    fn available(&self, char: PairingChar) -> usize {
        if char == PairingChar::SecCommandResponse {
            self.raw_cmd.len()
        } else {
            self.reasm[char.index()].available_bytes()
        }
    }

    /// Consumes the awaited message: takes everything reassembled (drain semantics keep
    /// over-arrival out of the next message; for data steps the size check then pins the
    /// exact wire size), resets the reassembler, validates, and runs the transition.
    fn consume_awaited(&mut self, spec: &AwaitSpec) -> Result<(), PairingError> {
        let (message, drained_cmd) = if spec.char == PairingChar::SecCommandResponse {
            (std::mem::take(&mut self.raw_cmd), true)
        } else {
            let idx = spec.char.index();
            let avail = self.reasm[idx].available_bytes();
            let taken = self.reasm[idx].take(avail);
            let message = match taken {
                Ok(m) => m,
                Err(e) => return Err(self.fail(PairingError::Malformed { reason: e.to_string() })),
            };
            self.reasm[idx].reset();
            (message, false)
        };
        debug_assert!(spec.drain == drained_cmd, "await flavor must match its channel");
        self.apply_awaited(spec, &message)?;
        self.cursor += 1;
        Ok(())
    }

    /// Satisfaction check + consumption. Returns false while the driver must keep waiting.
    fn try_consume(&mut self) -> Result<bool, PairingError> {
        let Some(spec) = self.awaiting() else {
            return Ok(false);
        };
        if self.available(spec.char) < spec.exactly {
            return Ok(false);
        }
        self.consume_awaited(&spec)?;
        Ok(true)
    }

    fn apply_awaited(&mut self, spec: &AwaitSpec, message: &[u8]) -> Result<(), PairingError> {
        if let Some(prefix) = &spec.expect_prefix {
            if !message.starts_with(prefix) {
                let got = hex(message);
                let step = spec.label.to_owned();
                return Err(self.fail(PairingError::CommandRejected { step, got }));
            }
        }
        match self.step() {
            Step::AwaitCommand { .. } => Ok(()),
            Step::AwaitSensorCert => {
                if message.len() != SENSOR_CERT_SIZE {
                    return Err(self.fail(PairingError::NotifyWrongSize {
                        want: SENSOR_CERT_SIZE,
                        got: message.len(),
                    }));
                }
                let cert = match SensorCert::parse(message) {
                    Ok(c) => c,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                // Kit `verifySensorCertificate` (PairingFlow.swift L644-654): no signing key
                // match ⇒ sensorCertificateVerificationFailed; a decode error stays Malformed.
                match cert.verified_signing_key_index() {
                    Ok(Some(_)) => {
                        self.sensor_cert = Some(cert);
                        Ok(())
                    }
                    Ok(None) => Err(self.fail(PairingError::CertRejected)),
                    Err(e) => Err(self.fail_malformed(e)),
                }
            }
            Step::AwaitSensorEph => {
                if message.len() != SENSOR_EPHEMERAL_SIZE {
                    return Err(self.fail(PairingError::NotifyWrongSize {
                        want: SENSOR_EPHEMERAL_SIZE,
                        got: message.len(),
                    }));
                }
                let sensor_eph = match exchange::parse_peer_pubkey(message) {
                    Ok(p) => p,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                let Flavor::FirstPair {
                    firstpair_tables,
                    schedule_tables,
                    key_pair,
                    null_entropy_11a,
                    static_scalar_window,
                } = &self.flavor
                else {
                    return Err(self.fail(PairingError::Malformed {
                        reason: "internal: ephemeral exchange on cached machine".to_owned(),
                    }));
                };
                let sensor_static_pub65 = match self.sensor_cert.as_ref() {
                    Some(c) => c.static_pub.clone(),
                    None => {
                        return Err(self.fail(PairingError::Malformed {
                            reason: "internal: sensor cert missing at ephemeral step".to_owned(),
                        }));
                    }
                };
                // PairingFlow.swift L341-349: both ECDH products are computed in the
                // preamble. The machine's Established carries only the §5.6 data-plane
                // keys, so the secrets are validated here (fail-closed) and dropped.
                let sensor_static = match exchange::parse_peer_pubkey(&sensor_static_pub65) {
                    Ok(p) => p,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                if let Err(e) = exchange::shared_secret(&key_pair.private_key, &sensor_static) {
                    return Err(self.fail_malformed(e));
                }
                if let Err(e) = exchange::shared_secret(&key_pair.private_key, &sensor_eph) {
                    return Err(self.fail_malformed(e));
                }
                let inputs = FirstPairPhase5KeyInputs {
                    entry_source: BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE.to_vec(),
                    null_entropy_11a: null_entropy_11a.clone(),
                    sensor_ephemeral_pub65: message.to_vec(),
                    sensor_static_pub65,
                    static_scalar_window: static_scalar_window.clone(),
                };
                // Kit `SessionKey.deriveFirstPairPhase5Material` (SessionKey.swift L176-206),
                // called from the first-pair key provider (PairingFlow.swift L730-737).
                let material = match session_key::derive_first_pair_phase5_material(
                    &inputs,
                    firstpair_tables,
                    schedule_tables,
                ) {
                    Ok(m) => m,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                let ctx = match libaes::key_setup(&material.raw_key, &self.libaes) {
                    Ok(c) => c,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                self.phase5_ctx = Some(ctx);
                Ok(())
            }
            Step::AwaitR1 => {
                if message.len() != SENSOR_CHALLENGE_SIZE {
                    return Err(self.fail(PairingError::NotifyWrongSize {
                        want: SENSOR_CHALLENGE_SIZE,
                        got: message.len(),
                    }));
                }
                let (r1, nonce7) = match challenge::parse_sensor_challenge(message) {
                    Ok(v) => v,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                // Cached path: the raw key is injected at construction, so the white-box
                // context is built here (PairingFlow.swift L473: phase5BlockEncryptor).
                // First pair already derived it at the Phase 4 step.
                if let Flavor::Cached { raw_key } = &self.flavor {
                    match libaes::key_setup(raw_key, &self.libaes) {
                        Ok(ctx) => self.phase5_ctx = Some(ctx),
                        Err(e) => return Err(self.fail_malformed(e)),
                    }
                }
                let Some(ctx) = self.phase5_ctx.clone() else {
                    return Err(self.fail(PairingError::Malformed {
                        reason: "internal: phase5 key not derived".to_owned(),
                    }));
                };
                let wire = match self.build_phase5_wire(&ctx, &r1, &nonce7) {
                    Ok(w) => w,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                let chunks = match fragment_for_write(&wire, WRITE_CHUNK_PAYLOAD) {
                    Ok(c) => c,
                    Err(e) => return Err(self.fail_malformed(e)),
                };
                self.r1 = Some(r1);
                self.phase5_chunks = Some(chunks);
                Ok(())
            }
            Step::AwaitPhase6 => {
                if message.len() != PHASE6_WIRE_SIZE {
                    return Err(self.fail(PairingError::NotifyWrongSize {
                        want: PHASE6_WIRE_SIZE,
                        got: message.len(),
                    }));
                }
                let Some(ctx) = self.phase5_ctx.clone() else {
                    return Err(self.fail(PairingError::Malformed {
                        reason: "internal: phase5 key not derived".to_owned(),
                    }));
                };
                let Some(r1) = self.r1.clone() else {
                    return Err(self.fail(PairingError::Malformed {
                        reason: "internal: R1 missing at phase6".to_owned(),
                    }));
                };
                let outcome = {
                    let block = |b: &[u8; 16]| libaes::phase5_block_encrypt(b, &ctx, &self.libaes);
                    // §5.6: the R1/R2 echo verification is mandatory (challenge.rs).
                    challenge::phase6_decrypt(message, &r1, &self.r2, &block)
                };
                match outcome {
                    Ok(material) => {
                        self.result = Some((material.k_enc, material.iv_enc, self.k_auth.clone()));
                        Ok(())
                    }
                    Err(crate::CryptoError::Phase6EchoMismatch { field }) => {
                        Err(self.fail(PairingError::Phase6EchoMismatch { field }))
                    }
                    Err(e) => Err(self.fail_malformed(e)),
                }
            }
            _ => Err(self.fail(PairingError::Malformed {
                reason: "internal: consume outside an await step".to_owned(),
            })),
        }
    }

    /// Phase 5 wire (§5.5): `ccm_ct36(R1 || R2 || tail4) || tag4 || zero14` under the
    /// white-box phase-5 block, nonce = the challenge's nonce7
    /// (PairingFlow.swift L473-479 / L558-564).
    fn build_phase5_wire(&self, ctx: &[u8], r1: &[u8], nonce7: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let block =
            |b: &[u8; 16]| -> Result<[u8; 16], CryptoError> { libaes::phase5_block_encrypt(b, ctx, &self.libaes) };
        let mut plaintext = Vec::with_capacity(challenge::PHASE5_PLAINTEXT_SIZE);
        plaintext.extend_from_slice(r1);
        plaintext.extend_from_slice(&self.r2);
        plaintext.extend_from_slice(&self.tail4);
        Ok(challenge::phase5_encrypt(&plaintext, nonce7, &block)?.wire_bytes())
    }

    /// Contract rule (a)-(d): drain queued writes first, then either consume a satisfied
    /// await (early arrival) or hand the await to the driver.
    fn next_action(&mut self) -> Result<PairingAction, PairingError> {
        if self.established {
            return Err(PairingError::AlreadyEstablished);
        }
        if let Some(e) = self.failed.clone() {
            return Err(e);
        }
        loop {
            self.resolve_frontier()?;
            if let Some(action) = self.pending.pop_front() {
                return Ok(action);
            }
            if self.step() == Step::Done {
                let Some((k_enc, iv_enc, k_auth)) = self.result.clone() else {
                    return Err(self.fail(PairingError::Malformed {
                        reason: "internal: established without material".to_owned(),
                    }));
                };
                self.established = true;
                return Ok(PairingAction::Established { k_enc, iv_enc, k_auth });
            }
            if self.try_consume()? {
                continue;
            }
            let Some(spec) = self.awaiting() else {
                return Err(self.fail(PairingError::Malformed {
                    reason: "internal: await step without spec".to_owned(),
                }));
            };
            return Ok(PairingAction::AwaitNotify {
                char: spec.char,
                exactly: spec.exactly,
                expect_prefix: spec.expect_prefix,
                drain: spec.drain,
                label: spec.label.to_owned(),
            });
        }
    }

    /// Contract rule for notify feeding: buffer the chunk on its character's reassembler;
    /// if it completes the awaited message, run the transition, then keep advancing
    /// internally as far as the buffered data allows (rule (c)). Chunks for a characteristic
    /// the machine is not waiting on are buffered for later (Ok(None)).
    fn on_notify(
        &mut self,
        char: PairingChar,
        chunk: &[u8],
    ) -> Result<Option<PairingAction>, PairingError> {
        if self.established {
            return Err(PairingError::AlreadyEstablished);
        }
        if let Some(e) = self.failed.clone() {
            return Err(e);
        }
        let idx = char.index();
        if char == PairingChar::SecCommandResponse {
            // Live EU 2026-09-24: the command clock answers with raw bytes (a bare `04`
            // notify), no §5.2 seq framing — buffer them as-is for the next drain await.
            self.raw_cmd.extend_from_slice(chunk);
        } else if let Err(e) = self.reasm[idx].feed(chunk) {
            return Err(self.fail(PairingError::Malformed { reason: e.to_string() }));
        }
        let mut consumed = false;
        loop {
            self.resolve_frontier()?;
            if self.step() == Step::Done {
                break;
            }
            let Some(spec) = self.awaiting() else {
                break;
            };
            // The first consumption in this call must be on the fed characteristic; the
            // cascade may then cross to other characteristics with buffered data.
            if !consumed && spec.char != char {
                break;
            }
            if self.available(spec.char) < spec.exactly {
                break;
            }
            self.consume_awaited(&spec)?;
            consumed = true;
        }
        if consumed {
            Ok(Some(self.next_action()?))
        } else {
            Ok(None)
        }
    }
}

// MARK: - First pair machine

/// PLAN_T1DMDROID.md §5.3 steps 1–8: the command-gated first-pair machine, ported from
/// `runCommandGatedFirstPairPreamble` + `runCommandGatedAuthorizationHandshake`
/// (PairingFlow.swift L261-379/L535-589). The machine's Phase 3 wire point is the injected
/// `process2(5)` public key, NOT the private key's own point (§5.3 constraint).
pub struct FirstPairMachine {
    core: Core,
}

impl FirstPairMachine {
    /// Validates every input at construction — before any action exists — and pre-fragments
    /// the two cert-character writes. Tables are owned so the uniffi object can hold the
    /// machine whole; tests re-load per machine (§9 files are small).
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        firstpair_tables: FirstPairTables,
        schedule_tables: ScheduleTables,
        libaes: LibAESTables,
        phone_cert_raw: &[u8],
        phone_private_be32: &[u8],
        phone_public_key65: &[u8],
        null_entropy_11a: &[u8],
        tail4: &[u8],
        r2: &[u8],
    ) -> Result<Self, PairingError> {
        if tail4.len() != 4 {
            return Err(PairingError::TailWrongSize { got: tail4.len() });
        }
        if r2.len() != 16 {
            return Err(PairingError::R2WrongSize { got: r2.len() });
        }
        if null_entropy_11a.len() != NULL_ENTROPY_11A_SIZE {
            return Err(PairingError::Malformed {
                reason: format!(
                    "null entropy must be {NULL_ENTROPY_11A_SIZE} bytes, got {}",
                    null_entropy_11a.len()
                ),
            });
        }
        let phone_cert = PhoneCert::parse(phone_cert_raw)
            .map_err(|e| PairingError::Malformed { reason: e.to_string() })?;
        if phone_public_key65.len() != 65 || phone_public_key65.first() != Some(&0x04) {
            return Err(PairingError::Malformed {
                reason: format!(
                    "phone public key must be a 65-byte uncompressed point, got {} bytes",
                    phone_public_key65.len()
                ),
            });
        }
        let private_key = p256::SecretKey::from_slice(phone_private_be32).map_err(|_| {
            PairingError::Malformed { reason: "invalidScalarEncoding".to_owned() }
        })?;
        // Kit `init(nativeScalarWindowLE:publicKey65Override:)` shape: the wire point is the
        // override, the ECDH scalar is the private key.
        let key_pair = EphemeralKeyPair {
            private_key,
            public_key65: phone_public_key65.to_vec(),
        };
        let phone_cert_chunks =
            fragment_for_write(&phone_cert.raw, WRITE_CHUNK_PAYLOAD)
                .map_err(|e| PairingError::Malformed { reason: e.to_string() })?;
        let mut phase3_wire = phone_public_key65.to_vec();
        phase3_wire.resize(PHONE_EPHEMERAL_WIRE_SIZE, 0);
        let phone_eph_chunks = fragment_for_write(&phase3_wire, WRITE_CHUNK_PAYLOAD)
            .map_err(|e| PairingError::Malformed { reason: e.to_string() })?;
        Ok(Self {
            core: Core {
                libaes,
                flavor: Flavor::FirstPair {
                    firstpair_tables,
                    schedule_tables,
                    key_pair,
                    null_entropy_11a: null_entropy_11a.to_vec(),
                    static_scalar_window: phone_cert.phase5_static_scalar_window_override(),
                },
                steps: first_pair_steps(),
                cursor: 0,
                pending: VecDeque::new(),
                reasm: [NotifyReassembler::new(), NotifyReassembler::new(), NotifyReassembler::new()],
                raw_cmd: Vec::new(),
                phone_cert_chunks,
                phone_eph_chunks,
                r2: r2.to_vec(),
                tail4: tail4.to_vec(),
                k_auth: None,
                sensor_cert: None,
                phase5_ctx: None,
                phase5_chunks: None,
                r1: None,
                result: None,
                established: false,
                failed: None,
            },
        })
    }

    pub fn next_action(&mut self) -> Result<PairingAction, PairingError> {
        self.core.next_action()
    }

    pub fn on_notify(
        &mut self,
        char: PairingChar,
        chunk: &[u8],
    ) -> Result<Option<PairingAction>, PairingError> {
        self.core.on_notify(char, chunk)
    }
}

// MARK: - Cached reconnect machine

/// PLAN_T1DMDROID.md §5.4: the cached direct-reconnect machine — starts at StartAuthorization
/// and skips cert + ephemeral entirely (kit `runCachedReconnectPreamble` +
/// `runCachedReconnectHandshake`, PairingFlow.swift L401-504). The Phase 5 raw key comes from
/// the persisted kAuth blob; `k_auth_blob` rides on `Established` unchanged.
pub struct CachedReconnectMachine {
    core: Core,
}

impl CachedReconnectMachine {
    /// All sizes validated at construction, before any action exists.
    pub fn new(
        libaes: LibAESTables,
        tail4: &[u8],
        r2: &[u8],
        phase5_raw_key: &[u8],
        k_auth_blob: Option<Vec<u8>>,
    ) -> Result<Self, PairingError> {
        if tail4.len() != 4 {
            return Err(PairingError::TailWrongSize { got: tail4.len() });
        }
        if r2.len() != 16 {
            return Err(PairingError::R2WrongSize { got: r2.len() });
        }
        if phase5_raw_key.len() != 16 {
            return Err(PairingError::KeyWrongSize {
                got: phase5_raw_key.len(),
            });
        }
        let mut raw_key = [0u8; 16];
        raw_key.copy_from_slice(phase5_raw_key);
        Ok(Self {
            core: Core {
                libaes,
                flavor: Flavor::Cached { raw_key },
                steps: cached_reconnect_steps(),
                cursor: 0,
                pending: VecDeque::new(),
                reasm: [NotifyReassembler::new(), NotifyReassembler::new(), NotifyReassembler::new()],
                raw_cmd: Vec::new(),
                phone_cert_chunks: Vec::new(),
                phone_eph_chunks: Vec::new(),
                r2: r2.to_vec(),
                tail4: tail4.to_vec(),
                k_auth: k_auth_blob,
                sensor_cert: None,
                phase5_ctx: None,
                phase5_chunks: None,
                r1: None,
                result: None,
                established: false,
                failed: None,
            },
        })
    }

    pub fn next_action(&mut self) -> Result<PairingAction, PairingError> {
        self.core.next_action()
    }

    pub fn on_notify(
        &mut self,
        char: PairingChar,
        chunk: &[u8],
    ) -> Result<Option<PairingAction>, PairingError> {
        self.core.on_notify(char, chunk)
    }
}

// MARK: - Step tables

/// Kit `runCommandGatedFirstPairPreamble` (PairingFlow.swift L261-379) +
/// `runCommandGatedAuthorizationHandshake` (L535-589) + `sendChallengeLoadDoneIfAvailable`
/// (L699-714); PLAN_T1DMDROID.md §5.3 steps 1–8.
fn first_pair_steps() -> Vec<Step> {
    vec![
        // L287-290: send StartAuthentication 0x01
        Step::WriteCommand { byte: 0x01 },
        // L291-294: send LoadCertificate 0x02
        Step::WriteCommand { byte: 0x02 },
        // L296-299: send phone cert (162 B → 9 writes), label "phoneCertWrite"
        Step::WritePhoneCert,
        // L301-304: send SendCertificateLoadDone 0x03
        Step::WriteCommand { byte: 0x03 },
        // L305: wait prefix 0x04 "CertificateAccepted"
        Step::AwaitCommand { prefix: 0x04, label: "CertificateAccepted" },
        // L307-310: send GetCertificate 0x09
        Step::WriteCommand { byte: 0x09 },
        // L311: wait prefix 0x0a "CertificateReady"
        Step::AwaitCommand { prefix: 0x0a, label: "CertificateReady" },
        // L313-318: sensor cert notify (140 B) + verify
        Step::AwaitSensorCert,
        // L320-323: send ValidateCertificate 0x0d
        Step::WriteCommand { byte: 0x0d },
        // L325-329: send phone ephemeral (65 B padded to 72 B → 4 writes), "phoneEphemeralWrite"
        Step::WritePhoneEphemeral,
        // L331-334: send SendEphemeralDone 0x0e
        Step::WriteCommand { byte: 0x0e },
        // L335: wait prefix 0x0f "EphemeralReady"
        Step::AwaitCommand { prefix: 0x0f, label: "EphemeralReady" },
        // L337-359: sensor ephemeral notify + ECDH + Phase 5 material
        Step::AwaitSensorEph,
        // L361-362: send StartAuthorization 0x11
        Step::WriteCommand { byte: 0x11 },
        // L363: wait prefix 0x08 "ChallengeLoadDone"
        Step::AwaitCommand { prefix: 0x08, label: "ChallengeLoadDone" },
        // L365-372: R1 challenge notify (23 B)
        Step::AwaitR1,
        // L558-568: build + send Phase 5 (54 B → 3 writes), "phase5Write"
        Step::WritePhase5,
        // L699-704: send SendChallengeLoadDone 0x08 AFTER the phase-5 write
        Step::WriteCommand { byte: 0x08 },
        // L705-713: wait prefix 0x08 "PatchChallengeLoadDone"
        Step::AwaitCommand { prefix: 0x08, label: "PatchChallengeLoadDone" },
        // L571-580: phase 6 notify (67 B), R1/R2 echo verify
        Step::AwaitPhase6,
        Step::Done,
    ]
}

/// Kit `runCachedReconnectPreamble` (PairingFlow.swift L401-441) +
/// `runCachedReconnectHandshake` (L450-504); PLAN_T1DMDROID.md §5.4 steps 1–2.
fn cached_reconnect_steps() -> Vec<Step> {
    vec![
        // L422-425: send StartAuthorization 0x11
        Step::WriteCommand { byte: 0x11 },
        // L426: wait prefix 0x08 "ChallengeLoadDone"
        Step::AwaitCommand { prefix: 0x08, label: "ChallengeLoadDone" },
        // L428-435: R1 challenge notify
        Step::AwaitR1,
        // L473-483: build + send Phase 5, "phase5Write"
        Step::WritePhase5,
        // L484 → L699-704: send SendChallengeLoadDone 0x08 after the phase-5 write
        Step::WriteCommand { byte: 0x08 },
        // L705-713: wait prefix 0x08 "PatchChallengeLoadDone"
        Step::AwaitCommand { prefix: 0x08, label: "PatchChallengeLoadDone" },
        // L486-495: phase 6 notify + echo verification
        Step::AwaitPhase6,
        Step::Done,
    ]
}

// MARK: - uniffi seam (mirrors lib.rs's Libre3FirstPair patterns)

/// PLAN_T1DMDROID.md §5.1: the three security-service characteristics. UUIDs cited from
/// LibreSensorGATT.swift L44/L46/L49 (base `0898xxxx-EF89-11E9-81B4-2A2AE2DBCCE4`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Libre3PairingChar {
    SecCertData,
    SecChallengeData,
    SecCommandResponse,
}

impl From<PairingChar> for Libre3PairingChar {
    fn from(c: PairingChar) -> Self {
        match c {
            PairingChar::SecCertData => Libre3PairingChar::SecCertData,
            PairingChar::SecChallengeData => Libre3PairingChar::SecChallengeData,
            PairingChar::SecCommandResponse => Libre3PairingChar::SecCommandResponse,
        }
    }
}

impl From<Libre3PairingChar> for PairingChar {
    fn from(c: Libre3PairingChar) -> Self {
        match c {
            Libre3PairingChar::SecCertData => PairingChar::SecCertData,
            Libre3PairingChar::SecChallengeData => PairingChar::SecChallengeData,
            Libre3PairingChar::SecCommandResponse => PairingChar::SecCommandResponse,
        }
    }
}

impl Libre3PairingChar {
    /// Full lowercase UUID (LibreSensorGATT.swift L44/L46/L49). Plain inherent method: the
    /// FFI maps `&'static str` poorly, so the constant is re-cited here for the Rust side.
    pub fn uuid(&self) -> &'static str {
        PairingChar::from(*self).uuid()
    }
}

/// PLAN_T1DMDROID.md §4/§5.3/§5.4: the action the Kotlin driver must perform next
/// (kit `PairingTransport` semantics, PairingTransport.swift L18-26). Write chunks carry the
/// logical message already fragmented per §5.2.
#[derive(uniffi::Enum)]
pub enum Libre3PairingAction {
    WriteChar {
        char: Libre3PairingChar,
        chunks: Vec<Vec<u8>>,
    },
    AwaitNotify {
        char: Libre3PairingChar,
        exactly: u32,
        expect_prefix: Option<Vec<u8>>,
        drain: bool,
        label: String,
    },
    Established {
        k_enc: Vec<u8>,
        iv_enc: Vec<u8>,
        k_auth: Option<Vec<u8>>,
    },
}

impl From<PairingAction> for Libre3PairingAction {
    fn from(a: PairingAction) -> Self {
        match a {
            PairingAction::WriteChar { char, chunks } => Libre3PairingAction::WriteChar {
                char: char.into(),
                chunks,
            },
            PairingAction::AwaitNotify { char, exactly, expect_prefix, drain, label } => {
                Libre3PairingAction::AwaitNotify {
                    char: char.into(),
                    exactly: exactly as u32,
                    expect_prefix,
                    drain,
                    label,
                }
            }
            PairingAction::Established { k_enc, iv_enc, k_auth } => {
                Libre3PairingAction::Established { k_enc, iv_enc, k_auth }
            }
        }
    }
}

/// PLAN_T1DMDROID.md §15: the pairing layer's failures, all fail-closed. Same variants as the
/// Rust-internal [`PairingError`] with `usize` fields widened to `u32` over the FFI; kit
/// spellings per PairingFlow.swift L129-149.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Libre3PairingError {
    #[error("command rejected at {step}: got {got}")]
    CommandRejected { step: String, got: String },
    #[error("sensor certificate verification failed")]
    CertRejected,
    #[error("phase6 echo mismatch: {field}")]
    Phase6EchoMismatch { field: String },
    #[error("R1 wrong size: got {got}")]
    R1WrongSize { got: u32 },
    #[error("tail4 wrong size: got {got}")]
    TailWrongSize { got: u32 },
    #[error("R2 wrong size: got {got}")]
    R2WrongSize { got: u32 },
    #[error("phase5 raw key wrong size: got {got}")]
    KeyWrongSize { got: u32 },
    #[error("notify wrong size: want {want}, got {got}")]
    NotifyWrongSize { want: u32, got: u32 },
    #[error("tables: {reason}")]
    Tables { reason: String },
    #[error("malformed: {reason}")]
    Malformed { reason: String },
    #[error("machine already established")]
    AlreadyEstablished,
}

impl From<PairingError> for Libre3PairingError {
    fn from(e: PairingError) -> Self {
        match e {
            PairingError::CommandRejected { step, got } => {
                Libre3PairingError::CommandRejected { step, got }
            }
            PairingError::CertRejected => Libre3PairingError::CertRejected,
            PairingError::Phase6EchoMismatch { field } => {
                Libre3PairingError::Phase6EchoMismatch { field }
            }
            PairingError::R1WrongSize { got } => Libre3PairingError::R1WrongSize { got: got as u32 },
            PairingError::TailWrongSize { got } => Libre3PairingError::TailWrongSize { got: got as u32 },
            PairingError::R2WrongSize { got } => Libre3PairingError::R2WrongSize { got: got as u32 },
            PairingError::KeyWrongSize { got } => Libre3PairingError::KeyWrongSize { got: got as u32 },
            PairingError::NotifyWrongSize { want, got } => {
                Libre3PairingError::NotifyWrongSize { want: want as u32, got: got as u32 }
            }
            PairingError::Tables { reason } => Libre3PairingError::Tables { reason },
            PairingError::Malformed { reason } => Libre3PairingError::Malformed { reason },
            PairingError::AlreadyEstablished => Libre3PairingError::AlreadyEstablished,
        }
    }
}

enum Libre3PairingInner {
    FirstPair(FirstPairMachine),
    Cached(CachedReconnectMachine),
}

/// PLAN_T1DMDROID.md §4 (Rust owns the pairing step machines) / §5.3/§5.4: the uniffi seam
/// over the two byte-machines (kit PairingFlow.swift). Every method returns Result and never
/// panics; lock poisoning surfaces as `Malformed`, never as a panic.
#[derive(uniffi::Object)]
pub struct Libre3PairingMachine {
    inner: std::sync::Mutex<Libre3PairingInner>,
}

fn tables_error(e: crate::CryptoError) -> Libre3PairingError {
    Libre3PairingError::Tables { reason: e.to_string() }
}

#[uniffi::export]
impl Libre3PairingMachine {
    /// PLAN_T1DMDROID.md §5.3/§9: the command-gated first-pair machine. Loads
    /// FirstPairTables + ScheduleTables + LibAESTables and the bundled 162-B `03 03` phone
    /// cert from the pushed tables dir (§9); anything missing/mismatched fails here, before
    /// any action exists (PhoneCert.swift `bundled162b`).
    #[uniffi::constructor]
    pub fn start_first_pair(
        tables_dir: String,
        phone_private_be32: Vec<u8>,
        phone_public_key65: Vec<u8>,
        null_entropy_11a: Vec<u8>,
        tail4: Vec<u8>,
        r2: Vec<u8>,
    ) -> Result<Self, Libre3PairingError> {
        let dir = std::path::Path::new(&tables_dir);
        let firstpair = crate::vm::FirstPairTables::from_dir(dir).map_err(tables_error)?;
        let schedule = crate::phase5::ScheduleTables::from_dir(dir).map_err(tables_error)?;
        let libaes = crate::libaes::LibAESTables::from_dir(dir).map_err(tables_error)?;
        let phone_cert = crate::cert::PhoneCert::bundled_162b(dir).map_err(tables_error)?;
        let machine = FirstPairMachine::new(
            firstpair,
            schedule,
            libaes,
            &phone_cert.raw,
            &phone_private_be32,
            &phone_public_key65,
            &null_entropy_11a,
            &tail4,
            &r2,
        )
        .map_err(Libre3PairingError::from)?;
        Ok(Self {
            inner: std::sync::Mutex::new(Libre3PairingInner::FirstPair(machine)),
        })
    }

    /// PLAN_T1DMDROID.md §5.4/§9: the cached-reconnect machine — only the libaes tables are
    /// needed; the Phase 5 raw key comes from the persisted kAuth blob
    /// (`Child23KAuthImport.phase5RawKey(forKAuthBlob:)`, PairingFlow.swift L506-521).
    #[uniffi::constructor]
    pub fn start_cached_reconnect(
        tables_dir: String,
        tail4: Vec<u8>,
        r2: Vec<u8>,
        phase5_raw_key: Vec<u8>,
        k_auth_blob: Option<Vec<u8>>,
    ) -> Result<Self, Libre3PairingError> {
        let libaes = crate::libaes::LibAESTables::from_dir(std::path::Path::new(&tables_dir))
            .map_err(tables_error)?;
        let machine =
            CachedReconnectMachine::new(libaes, &tail4, &r2, &phase5_raw_key, k_auth_blob)
                .map_err(Libre3PairingError::from)?;
        Ok(Self {
            inner: std::sync::Mutex::new(Libre3PairingInner::Cached(machine)),
        })
    }

    /// PLAN_T1DMDROID.md §4: the next action the driver must perform. A write is latched once
    /// when returned; awaits repeat until satisfied; buffered notify data advances the
    /// machine as far as it allows (§5.2). After `Established` this errors, never panics.
    pub fn next_action(&self) -> Result<Libre3PairingAction, Libre3PairingError> {
        let mut guard = self.lock()?;
        let action = match &mut *guard {
            Libre3PairingInner::FirstPair(m) => m.next_action()?,
            Libre3PairingInner::Cached(m) => m.next_action()?,
        };
        Ok(action.into())
    }

    /// §5.2 chunk, seq-prefixed except raw `secCommandResponse`; unawaited chars buffer.
    pub fn on_notify(
        &self,
        char: Libre3PairingChar,
        chunk: Vec<u8>,
    ) -> Result<Option<Libre3PairingAction>, Libre3PairingError> {
        let mut guard = self.lock()?;
        let action = match &mut *guard {
            Libre3PairingInner::FirstPair(m) => m.on_notify(PairingChar::from(char), &chunk)?,
            Libre3PairingInner::Cached(m) => m.on_notify(PairingChar::from(char), &chunk)?,
        };
        Ok(action.map(Into::into))
    }
}

impl Libre3PairingMachine {
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Libre3PairingInner>, Libre3PairingError> {
        self.inner.lock().map_err(|_| Libre3PairingError::Malformed {
            reason: "pairing machine lock poisoned".to_owned(),
        })
    }
}
