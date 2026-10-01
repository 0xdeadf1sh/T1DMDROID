//! PLAN_T1DMDROID.md §5.7: the post-pairing data plane — outer framing, AES-CCM crypto with
//! the 13-B nonce, channel↔descriptor mapping, the notification assembler, and the decoder.
//! Port of kit DataFrame.swift + DataPlaneCrypto.swift + DataPlaneDecoder.swift
//! (LibreCRKit/Sources/LibreCRKit/DataPlane/). Table-free (plan §5.7); every failure is
//! fail-closed, never a panic.

use crate::ccm;
use crate::glucose::RealtimeGlucoseReading;
use crate::history::{ClinicalReadingRecord, HistoricalReadingPage};
use crate::status::PatchStatus;
use crate::{CryptoError, DataPlaneError};

use aes::cipher::{BlockEncrypt, KeyInit};
use aes::Aes128;

/// Port of kit `DataFrame` (DataFrame.swift L24-55): the OUTER framing
/// `[ encrypted_payload | seq_byte | type_byte ]`; parsing does NOT decrypt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataFrame {
    pub encrypted: Vec<u8>,
    pub seq: u8,
    pub type_: u8,
}

impl DataFrame {
    /// Kit minimum: 1 encrypted byte + 2 trailer bytes (DataFrame.swift L30).
    pub const MIN_RAW_SIZE: usize = 3;

    /// Kit `DataFrame.parse` (DataFrame.swift L29-36).
    pub fn parse(raw: &[u8]) -> Result<Self, DataPlaneError> {
        if raw.len() < Self::MIN_RAW_SIZE {
            return Err(DataPlaneError::TooShort { got: raw.len() });
        }
        let trailer_start = raw.len() - 2;
        Ok(Self {
            encrypted: raw[..trailer_start].to_vec(),
            seq: raw[trailer_start],
            type_: raw[trailer_start + 1],
        })
    }

    /// Kit `sequenceNumber` (DataFrame.swift L41-43): the final two bytes are LE on the wire.
    pub fn sequence_number(&self) -> u16 {
        u16::from(self.seq) | (u16::from(self.type_) << 8)
    }

    /// Kit `raw` (DataFrame.swift L48-54).
    pub fn raw(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.encrypted.len() + 2);
        out.extend_from_slice(&self.encrypted);
        out.push(self.seq);
        out.push(self.type_);
        out
    }
}

/// Port of kit `DataPlanePacketKind` (DataPlaneCrypto.swift L13-38). Declaration order is the
/// kit's `allCases` order and drives `decrypt_trying_all`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DataPlanePacketKind {
    Kind0,
    Handshake,
    Kind2,
    Kind3,
    Kind4,
    Kind5,
    Kind6,
    PatchData,
}

impl DataPlanePacketKind {
    /// Kit `patchControlWrite` (DataPlaneCrypto.swift L24).
    pub const PATCH_CONTROL_WRITE: Self = Self::Kind0;

    /// Every kind, in kit `allCases` order (DataPlaneCrypto.swift L13-21).
    pub const ALL_CASES: [Self; 8] = [
        Self::Kind0,
        Self::Handshake,
        Self::Kind2,
        Self::Kind3,
        Self::Kind4,
        Self::Kind5,
        Self::Kind6,
        Self::PatchData,
    ];

    /// Kit `descriptor` (DataPlaneCrypto.swift L28-36) — the 3 static descriptor bytes.
    pub fn descriptor(self) -> [u8; 3] {
        match self {
            Self::Kind0 => [0x00, 0x00, 0x00],
            Self::Handshake => [0x00, 0x00, 0x0f],
            Self::Kind2 => [0x00, 0x00, 0xf0],
            Self::Kind3 => [0x00, 0x0f, 0x00],
            Self::Kind4 => [0x00, 0xf0, 0x00],
            Self::Kind5 => [0x0f, 0x00, 0x00],
            Self::Kind6 => [0xf0, 0x00, 0x00],
            Self::PatchData => [0x44, 0x00, 0x00],
        }
    }
}

/// Port of kit `DataPlaneChannel` (DataPlaneDecoder.swift L3-46). Mapped by the 8-char
/// lowercase UUID prefix (base `0898xxxx-EF89-11E9-81B4-2A2AE2DBCCE4`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DataPlaneChannel {
    PatchControl,
    PatchStatus,
    GlucoseData,
    HistoricData,
    EventLog,
    ClinicalData,
    FactoryData,
}

impl DataPlaneChannel {
    /// Kit `init?(uuidString:)` (DataPlaneDecoder.swift L12-32).
    pub fn from_uuid(uuid: &str) -> Option<Self> {
        let lower = uuid.to_lowercase();
        let prefix = |p: &str| lower.as_bytes().starts_with(p.as_bytes());
        if prefix("08981338") {
            Some(Self::PatchControl)
        } else if prefix("08981482") {
            Some(Self::PatchStatus)
        } else if prefix("0898177a") {
            Some(Self::GlucoseData)
        } else if prefix("0898195a") {
            Some(Self::HistoricData)
        } else if prefix("08981bee") {
            Some(Self::EventLog)
        } else if prefix("08981ab8") {
            Some(Self::ClinicalData)
        } else if prefix("08981d24") {
            Some(Self::FactoryData)
        } else {
            None
        }
    }

    /// Kit `preferredInboundKind` (DataPlaneDecoder.swift L34-45).
    pub fn preferred_inbound_kind(self) -> Option<DataPlanePacketKind> {
        match self {
            Self::PatchStatus => Some(DataPlanePacketKind::Kind2),
            Self::GlucoseData => Some(DataPlanePacketKind::Kind3),
            Self::HistoricData => Some(DataPlanePacketKind::Kind4),
            Self::PatchControl | Self::EventLog | Self::ClinicalData | Self::FactoryData => None,
        }
    }

    /// Kit case spelling (DataPlaneDecoder.swift L3-10), for error reasons and logs.
    pub fn name(self) -> &'static str {
        match self {
            Self::PatchControl => "patchControl",
            Self::PatchStatus => "patchStatus",
            Self::GlucoseData => "glucoseData",
            Self::HistoricData => "historicData",
            Self::EventLog => "eventLog",
            Self::ClinicalData => "clinicalData",
            Self::FactoryData => "factoryData",
        }
    }

    /// Stable slot index for per-channel sequencing (the kit's per-channel seq counters,
    /// DataFrame.swift L10). Declaration order, 0..7.
    pub fn slot(self) -> usize {
        match self {
            Self::PatchControl => 0,
            Self::PatchStatus => 1,
            Self::GlucoseData => 2,
            Self::HistoricData => 3,
            Self::EventLog => 4,
            Self::ClinicalData => 5,
            Self::FactoryData => 6,
        }
    }
}

/// Standard AES-128 block primitive for the data plane: the kit's
/// `AESCCM.commonCryptoBlockEncrypt(key:)` equivalent over the `aes` crate. Built from an
/// already-constructed cipher (key setup is validated once, in `DataPlaneCrypto::new`).
fn aes_block(cipher: Aes128) -> impl Fn(&[u8; 16]) -> Result<[u8; 16], CryptoError> {
    move |input: &[u8; 16]| {
        let mut block = (*input).into();
        cipher.encrypt_block(&mut block);
        Ok(block.into())
    }
}

/// Port of kit `DataPlaneDecryptResult` (DataPlaneCrypto.swift L40-43).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataPlaneDecryptResult {
    pub kind: DataPlanePacketKind,
    pub plaintext: Vec<u8>,
}

/// Port of kit `DataPlaneCrypto` (DataPlaneCrypto.swift L45-112).
///
///   key      = Phase 6 kEnc (16 B)
///   nonce13  = seq16_LE || packetDescriptor3 || ivEnc8   (DataPlaneCrypto.swift L6)
///   tag      = 4 B CCM tag appended to the ciphertext    (DataPlaneCrypto.swift L7)
#[derive(Clone)]
pub struct DataPlaneCrypto {
    /// Kit `public let kEnc` (DataPlaneCrypto.swift L48); the block cipher is pre-keyed.
    pub k_enc: [u8; 16],
    iv_enc: [u8; 8],
    aes: Aes128,
}

impl DataPlaneCrypto {
    pub const TAG_SIZE: usize = 4; // DataPlaneCrypto.swift L46

    /// Kit `init(kEnc:ivEnc:)` (DataPlaneCrypto.swift L51-56): sizes validated up front.
    pub fn new(k_enc: &[u8], iv_enc: &[u8]) -> Result<Self, DataPlaneError> {
        let k_enc: [u8; 16] = k_enc
            .try_into()
            .map_err(|_| DataPlaneError::WrongKEncSize { got: k_enc.len() })?;
        let iv_enc: [u8; 8] = iv_enc
            .try_into()
            .map_err(|_| DataPlaneError::WrongIVEncSize { got: iv_enc.len() })?;
        // A 16-byte key always fits; the error path exists to stay off the panic path.
        let aes = Aes128::new_from_slice(&k_enc)
            .map_err(|_| DataPlaneError::Ccm(CryptoError::InvalidParameters))?;
        Ok(Self { k_enc, iv_enc, aes })
    }

    /// Kit `nonce(sequence:kind:)` (DataPlaneCrypto.swift L62-69).
    pub fn nonce(&self, sequence: u16, kind: DataPlanePacketKind) -> [u8; 13] {
        let mut out = [0u8; 13];
        out[..2].copy_from_slice(&sequence.to_le_bytes());
        out[2..5].copy_from_slice(&kind.descriptor());
        out[5..].copy_from_slice(&self.iv_enc);
        out
    }

    /// Kit `decrypt(_:kind:)` (DataPlaneCrypto.swift L71-84): tag = last 4 B of the encrypted
    /// payload; anything shorter than the tag is `payloadTooShort`.
    pub fn decrypt(&self, frame: &DataFrame, kind: DataPlanePacketKind) -> Result<Vec<u8>, DataPlaneError> {
        if frame.encrypted.len() < Self::TAG_SIZE {
            return Err(DataPlaneError::PayloadTooShort { got: frame.encrypted.len() });
        }
        let tag_start = frame.encrypted.len() - Self::TAG_SIZE;
        let ciphertext = &frame.encrypted[..tag_start];
        let tag = &frame.encrypted[tag_start..];
        let block = aes_block(self.aes.clone());
        ccm::decrypt(
            &self.nonce(frame.sequence_number(), kind),
            ciphertext,
            tag,
            &[],
            &block,
        )
        .map_err(DataPlaneError::Ccm)
    }

    /// Kit `decryptTryingAllKinds` (DataPlaneCrypto.swift L86-93): walk `allCases` in
    /// declaration order, accept the first descriptor whose CCM tag verifies.
    pub fn decrypt_trying_all(&self, frame: &DataFrame) -> Result<DataPlaneDecryptResult, DataPlaneError> {
        for kind in Self::all_cases() {
            if let Ok(plaintext) = self.decrypt(frame, kind) {
                return Ok(DataPlaneDecryptResult { kind, plaintext });
            }
        }
        Err(DataPlaneError::NoDescriptorMatched)
    }

    fn all_cases() -> impl Iterator<Item = DataPlanePacketKind> {
        DataPlanePacketKind::ALL_CASES.into_iter()
    }

    /// Kit `encrypt(plaintext:sequence:kind:)` (DataPlaneCrypto.swift L95-111): ciphertext ||
    /// 4-B tag in `encrypted`, the LE sequence split into (seq, type) on the trailer.
    pub fn encrypt(
        &self,
        plaintext: &[u8],
        sequence: u16,
        kind: DataPlanePacketKind,
    ) -> Result<DataFrame, DataPlaneError> {
        let block = aes_block(self.aes.clone());
        let (ciphertext, tag) = ccm::encrypt(
            &self.nonce(sequence, kind),
            plaintext,
            &[],
            Self::TAG_SIZE,
            &block,
        )
        .map_err(DataPlaneError::Ccm)?;
        let mut encrypted = ciphertext;
        encrypted.extend_from_slice(&tag);
        Ok(DataFrame {
            encrypted,
            seq: sequence as u8,
            type_: (sequence >> 8) as u8,
        })
    }
}

/// Port of kit `DataPlaneNotificationAssembler` (DataPlaneDecoder.swift L129-159):
/// `glucoseData` arrives as a 15-B prefix then a 20-B suffix; the suffix carries the normal
/// 2-B sequence trailer, so the two notifications must be concatenated before CCM.
#[derive(Default)]
pub struct DataPlaneNotificationAssembler {
    glucose_prefix: Option<Vec<u8>>,
}

impl DataPlaneNotificationAssembler {
    pub fn new() -> Self {
        Self::default()
    }

    /// Kit `feed(fragment:channel:)` (L135-152): non-glucose channels and non-15-B glucose
    /// chunks pass straight through (defensive: a whole 35-B frame in one notify also parses).
    pub fn feed(&mut self, fragment: &[u8], channel: DataPlaneChannel) -> Option<Vec<u8>> {
        if channel != DataPlaneChannel::GlucoseData {
            return Some(fragment.to_vec());
        }
        if let Some(prefix) = self.glucose_prefix.take() {
            let mut frame = prefix;
            frame.extend_from_slice(fragment);
            return Some(frame);
        }
        if fragment.len() == 15 {
            self.glucose_prefix = Some(fragment.to_vec());
            return None;
        }
        Some(fragment.to_vec())
    }

    /// Kit `reset` (L154-158).
    pub fn reset(&mut self) {
        self.glucose_prefix = None;
    }
}

/// Port of kit `DataPlaneDecodedPayload` (DataPlaneDecoder.swift L48-54).
#[derive(Debug, Clone, PartialEq)]
pub enum DataPlaneDecodedPayload {
    RealtimeGlucose(RealtimeGlucoseReading),
    PatchStatus(PatchStatus),
    HistoricalReadingPage(HistoricalReadingPage),
    ClinicalReadingRecord(ClinicalReadingRecord),
    Raw(Vec<u8>),
}

/// Port of kit `DataPlaneDecodedPacket` (DataPlaneDecoder.swift L56-67).
#[derive(Debug, Clone, PartialEq)]
pub struct DataPlaneDecodedPacket {
    pub channel: DataPlaneChannel,
    pub frame: DataFrame,
    pub kind: DataPlanePacketKind,
    pub preferred_kind: Option<DataPlanePacketKind>,
    pub plaintext: Vec<u8>,
    pub payload: DataPlaneDecodedPayload,
}

impl DataPlaneDecodedPacket {
    /// Kit `usedPreferredKind` (DataPlaneDecoder.swift L64-66).
    pub fn used_preferred_kind(&self) -> bool {
        self.preferred_kind.is_none() || self.preferred_kind == Some(self.kind)
    }
}

/// Port of kit `DataPlaneDecoder` (DataPlaneDecoder.swift L69-122).
#[derive(Clone)]
pub struct DataPlaneDecoder {
    pub crypto: DataPlaneCrypto,
}

impl DataPlaneDecoder {
    pub fn new(crypto: DataPlaneCrypto) -> Self {
        Self { crypto }
    }

    /// Kit `decrypt(frame:channel:)` (DataPlaneDecoder.swift L76-94): try the channel's
    /// preferred kind first; on CCM failure (or no preferred kind) fall back to trying ALL
    /// kinds in `allCases` order — the kit's exact semantics.
    pub fn decrypt(
        &self,
        frame: &DataFrame,
        channel: DataPlaneChannel,
    ) -> Result<DataPlaneDecodedPacket, DataPlaneError> {
        let preferred_kind = channel.preferred_inbound_kind();
        let result = match preferred_kind {
            Some(kind) => match self.crypto.decrypt(frame, kind) {
                Ok(plaintext) => DataPlaneDecryptResult { kind, plaintext },
                Err(_) => self.crypto.decrypt_trying_all(frame)?,
            },
            None => self.crypto.decrypt_trying_all(frame)?,
        };

        let payload = Self::decode_payload(channel, &result.plaintext);
        Ok(DataPlaneDecodedPacket {
            channel,
            frame: frame.clone(),
            kind: result.kind,
            preferred_kind,
            plaintext: result.plaintext,
            payload,
        })
    }

    /// Kit `decodePayload(channel:plaintext:)` (DataPlaneDecoder.swift L96-121): a size
    /// mismatch falls back to `Raw`, never an error.
    fn decode_payload(channel: DataPlaneChannel, plaintext: &[u8]) -> DataPlaneDecodedPayload {
        let raw = || DataPlaneDecodedPayload::Raw(plaintext.to_vec());
        match channel {
            DataPlaneChannel::GlucoseData => RealtimeGlucoseReading::parse(plaintext)
                .map_or_else(|_| raw(), DataPlaneDecodedPayload::RealtimeGlucose),
            DataPlaneChannel::PatchStatus => PatchStatus::parse(plaintext)
                .map_or_else(|_| raw(), DataPlaneDecodedPayload::PatchStatus),
            DataPlaneChannel::HistoricData => HistoricalReadingPage::parse(plaintext)
                .map_or_else(|_| raw(), DataPlaneDecodedPayload::HistoricalReadingPage),
            DataPlaneChannel::ClinicalData => ClinicalReadingRecord::parse(plaintext)
                .map_or_else(|_| raw(), DataPlaneDecodedPayload::ClinicalReadingRecord),
            DataPlaneChannel::PatchControl
            | DataPlaneChannel::EventLog
            | DataPlaneChannel::FactoryData => raw(),
        }
    }
}

/// Outbound tx sequencing: a per-channel u16 counter starting 0x0001 (kit evidence:
/// testPatchControlCommandEncryptsTo13ByteWriteFrame — the first patchControl write carries
/// sequence 0x0001; DataFrame.swift L10 "per-channel monotonic counter advancing 0x01..0x0n").
/// Owned by the session; wraps at the u16 edge rather than failing the stream.
#[derive(Debug, Clone, Default)]
pub struct TxSequencer {
    next: [u16; 7],
}

impl TxSequencer {
    pub fn new() -> Self {
        let mut next = [0u16; 7];
        for slot in next.iter_mut() {
            *slot = 0x0001;
        }
        Self { next }
    }

    /// Returns this channel's next sequence and bumps its counter.
    pub fn take(&mut self, channel: DataPlaneChannel) -> u16 {
        let seq = self.next[channel.slot()];
        self.next[channel.slot()] = seq.wrapping_add(1);
        seq
    }

    /// Read-ahead without bumping (diagnostics).
    pub fn peek(&self, channel: DataPlaneChannel) -> u16 {
        self.next[channel.slot()]
    }
}
