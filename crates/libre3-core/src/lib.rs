//! Release: panic = "abort"; every uniffi::export fn returns Result and never panics.

uniffi::setup_scaffolding!();

mod crc;
mod nfc;
mod receiver;

pub mod ccm;
pub mod cert;
pub mod challenge;
pub mod cipherfn;
pub mod cmac;
pub mod dataplane;
pub mod ephemeral;
pub mod frame;
pub mod glucose;
pub mod history;
pub mod kauth;
pub mod kbkdf;
pub mod libaes;
pub mod p256;
pub mod pairing;
pub mod phase5;
pub mod status;
pub mod session_key;
pub mod vm;

pub use nfc::{Libre3PatchInfo, Libre3SwitchResponse, ProvisionAction};
pub use receiver::Libre3Region;

/// PLAN_T1DMDROID.md §15: the crypto layer's failures, all fail-closed. Exported as uniffi
/// errors when the Kotlin seam grows them.
#[derive(Debug, thiserror::Error)]
pub enum CryptoError {
    #[error("ccm parameters rejected")]
    InvalidParameters,
    #[error("ccm tag mismatch")]
    MacMismatch,
    #[error("p256 point or scalar rejected")]
    InvalidP256Point,
    #[error("libaes: {reason}")]
    LibAes { reason: String },
    #[error("runtime table missing: {name}")]
    TablesMissing { name: String },
    #[error("runtime table {name}: want {want} bytes, got {got}")]
    TablesSize { name: String, want: usize, got: usize },
    #[error("runtime table set incomplete")]
    InternalTables,
    #[error("phase6 echo mismatch: {field}")]
    Phase6EchoMismatch { field: String },
    /// FirstPairSourceSlice layer failures (PLAN_T1DMDROID.md §5.3 first-pair source builder);
    /// all fail-closed, reason carries the kit's error case.
    #[error("first-pair slice: {reason}")]
    Slice { reason: String },
}

/// PLAN_T1DMDROID.md §15; the per-layer variants land with the phases that raise them.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Libre3Error {
    /// Never-expected states, surfaced as `Err` to stay off the panic path.
    #[error("internal error: {reason}")]
    Internal { reason: String },
    /// `CryptoError` mapped at the seam; `reason` carries the kit error case spelling.
    #[error("crypto: {reason}")]
    Crypto { reason: String },
}

/// PLAN_T1DMDROID.md §15: the provisioning layer's failures, all fail-closed.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ProvisionError {
    /// NFC error 0xb1: the account/region fold is not the one the sensor holds.
    #[error("NFC error 0xb1: account/region mismatch")]
    NfcB1,
    #[error("NFC error 0x{code:02x}")]
    Nfc { code: u8 },
    #[error("malformed response: {reason}")]
    Malformed { reason: String },
}

/// PLAN_T1DMDROID.md §15 + §5.7: the data-plane layer's failures, all fail-closed. Kit
/// spellings per DataFrameError/DataPlaneCryptoError + the plaintext-size errors
/// (DataFrame.swift L57-59, DataPlaneCrypto.swift L114-119).
#[derive(Debug, thiserror::Error)]
pub enum DataPlaneError {
    /// Kit `DataFrameError.tooShort(count)` (DataFrame.swift L30).
    #[error("tooShort({got})")]
    TooShort { got: usize },
    /// Kit `DataPlaneCryptoError.wrongKEncSize` (DataPlaneCrypto.swift L52).
    #[error("wrongKEncSize({got})")]
    WrongKEncSize { got: usize },
    /// Kit `DataPlaneCryptoError.wrongIVEncSize` (DataPlaneCrypto.swift L53).
    #[error("wrongIVEncSize({got})")]
    WrongIVEncSize { got: usize },
    /// Kit `DataPlaneCryptoError.payloadTooShort` (DataPlaneCrypto.swift L73).
    #[error("payloadTooShort({got})")]
    PayloadTooShort { got: usize },
    /// Kit `DataPlaneCryptoError.noDescriptorMatched` (DataPlaneCrypto.swift L92).
    #[error("noDescriptorMatched")]
    NoDescriptorMatched,
    /// Kit `wrongPlaintextSize` across the decoders (e.g. RealtimeGlucoseReading.swift L225).
    #[error("wrongPlaintextSize({got})")]
    WrongPlaintextSize { got: usize },
    /// Underlying AES-CCM failure (CryptoError::MacMismatch and friends).
    #[error("ccm: {0}")]
    Ccm(#[from] CryptoError),
}

/// Scaffold seam check; the protocol exports land with the phases that own them.
#[uniffi::export]
pub fn libre3_ping() -> Result<String, Libre3Error> {
    Ok("libre3-core".to_owned())
}

/// PLAN_T1DMDROID.md §5.8: the account-ID fold the sensor stores.
#[uniffi::export]
pub fn libre3_receiver_id(account_id: String, region: Libre3Region) -> Result<u32, Libre3Error> {
    Ok(receiver::receiver_id(&account_id, region))
}

/// PLAN_T1DMDROID.md §5.9 state rule.
#[uniffi::export]
pub fn libre3_provision_action(sensor_state: u8) -> Result<ProvisionAction, Libre3Error> {
    Ok(nfc::provision_action(sensor_state))
}

/// PLAN_T1DMDROID.md §5.9: `0xa1` patch-info read.
#[uniffi::export]
pub fn libre3_nfc_patch_info_cmd() -> Result<Vec<u8>, Libre3Error> {
    Ok(nfc::patch_info_cmd())
}

/// PLAN_T1DMDROID.md §5.9: `0xa0` activation; starts the wear clock, irreversible.
#[uniffi::export]
pub fn libre3_nfc_activate_cmd(time_seconds: u32, receiver_id: u32) -> Result<Vec<u8>, Libre3Error> {
    Ok(nfc::activate_cmd(time_seconds, receiver_id))
}

/// PLAN_T1DMDROID.md §5.9: `0xa8` switch-receiver; mints a fresh BLE PIN.
#[uniffi::export]
pub fn libre3_nfc_switch_cmd(time_seconds: u32, receiver_id: u32) -> Result<Vec<u8>, Libre3Error> {
    Ok(nfc::switch_cmd(time_seconds, receiver_id))
}

/// PLAN_T1DMDROID.md §5.9: normalized patch-info parse; flags byte stripped inside.
#[uniffi::export]
pub fn libre3_nfc_parse_patch_info(response: Vec<u8>) -> Result<Libre3PatchInfo, ProvisionError> {
    nfc::parse_patch_info(&response)
}

/// PLAN_T1DMDROID.md §5.9: `0xa0`/`0xa8` answer parse; raw 19 B, prefix asserted.
#[uniffi::export]
pub fn libre3_nfc_parse_switch_response(
    response: Vec<u8>,
) -> Result<Libre3SwitchResponse, ProvisionError> {
    nfc::parse_switch_response(&response)
}

fn crypto_error(e: CryptoError) -> Libre3Error {
    Libre3Error::Crypto { reason: e.to_string() }
}

/// §15: data-plane failures surface as `Libre3Error::Crypto`; the reason keeps the kit's
/// error-case spelling so Kotlin logs read like the kit's.
fn data_plane_error(e: DataPlaneError) -> Libre3Error {
    Libre3Error::Crypto { reason: e.to_string() }
}

/// PLAN_T1DMDROID.md §5.3: the first-pair entropy seam. Kotlin feeds native entropy (e.g.
/// `SecureRandom`) per attempt; the same entropy derives the null scalar and the
/// `process2(5)` wire point, which is what makes a verified Phase 6 reachable. `byte_count`
/// is the requested entropy length (`builder633fa8NullEntropyBytes` = 0x11a).
#[uniffi::export(callback_interface)]
pub trait Libre3EntropySource: Send + Sync {
    fn entropy(&self, byte_count: u32) -> Result<Vec<u8>, Libre3Error>;
}

/// PLAN_T1DMDROID.md §5.3: the runtime table set (FirstPairTables + phase5 ScheduleTables)
/// loaded once from the pushed tables dir (§9). Fail-closed on missing/mismatched tables.
#[derive(uniffi::Object)]
pub struct Libre3FirstPair {
    tables: crate::vm::FirstPairTables,
    schedule: crate::phase5::ScheduleTables,
}

/// The native first-pair ephemeral material (kit `FirstPairNativeEphemeralMaterial`).
#[derive(uniffi::Record)]
pub struct Libre3FirstPairNativeEphemeral {
    /// 32-byte big-endian private scalar (the CryptoKit rawRepresentation equivalent).
    pub private_be32: Vec<u8>,
    /// The `process2(5)` wire point, `04 || X || Y` (65 B).
    pub public_key65: Vec<u8>,
    /// The accepted 282-byte null-entropy block.
    pub null_entropy_11a: Vec<u8>,
    /// The native null-branch scalar window (70-byte LE).
    pub null_scalar_window: Vec<u8>,
    pub attempts: u32,
}

/// The first-pair Phase 5 key material (kit `FirstPairPhase5KeyMaterial`).
#[derive(uniffi::Record)]
pub struct Libre3FirstPairPhase5KeyMaterial {
    pub source66: Vec<u8>,
    pub raw_key: Vec<u8>,
    pub null_entropy_11a: Vec<u8>,
    pub null_scalar_window: Vec<u8>,
    pub null_attempts: u32,
}

#[uniffi::export]
impl Libre3FirstPair {
    /// §9: `tables_dir` is the pushed external-files dir; anything missing/mismatched is an
    /// error here, not downstream.
    #[uniffi::constructor]
    pub fn load(tables_dir: String) -> Result<Self, Libre3Error> {
        let dir = std::path::Path::new(&tables_dir);
        Ok(Self {
            tables: crate::vm::FirstPairTables::from_dir(dir).map_err(crypto_error)?,
            schedule: crate::phase5::ScheduleTables::from_dir(dir).map_err(crypto_error)?,
        })
    }

    /// §5.3: `SessionKey.makeFirstPairNativeEphemeral` — sample the accepted null-branch
    /// entropy, derive the native null scalar for Phase 5, and send the `process2(5)`
    /// fixed-point public point derived from that same entropy.
    pub fn make_first_pair_native_ephemeral(
        &self,
        max_attempts: u32,
        entropy_source: Box<dyn Libre3EntropySource>,
    ) -> Result<Libre3FirstPairNativeEphemeral, Libre3Error> {
        let out = session_key::make_first_pair_native_ephemeral(
            max_attempts as usize,
            move |attempt: usize| -> Result<Vec<u8>, CryptoError> {
                entropy_source
                    .entropy(crate::vm::lowseed::BUILDER633FA8_NULL_ENTROPY_BYTES as u32)
                    .map_err(|e| CryptoError::Slice { reason: format!("entropySource: {e}") })
            },
            &self.tables,
        )
        .map_err(crypto_error)?;
        Ok(Libre3FirstPairNativeEphemeral {
            private_be32: out.key_pair.private_key.to_bytes().to_vec(),
            public_key65: out.key_pair.public_key65,
            null_entropy_11a: out.null_entropy_11a,
            null_scalar_window: out.null_scalar_window,
            attempts: out.attempts as u32,
        })
    }

    /// §5.3: `SessionKey.deriveFirstPairPhase5Material` from the sensor's Phase 4 ephemeral
    /// point and the certificate's static point. `entry_source` nil = bundled default;
    /// `static_scalar_window` nil = the entry-source derivation (the `03 03` phone cert
    /// override is applied by the caller, mirroring `PhoneCert.swift`).
    pub fn first_pair_phase5_material(
        &self,
        entry_source: Option<Vec<u8>>,
        null_entropy_11a: Vec<u8>,
        sensor_ephemeral_pub65: Vec<u8>,
        sensor_static_pub65: Vec<u8>,
        static_scalar_window: Option<Vec<u8>>,
    ) -> Result<Libre3FirstPairPhase5KeyMaterial, Libre3Error> {
        let inputs = session_key::FirstPairPhase5KeyInputs {
            entry_source: entry_source
                .unwrap_or_else(|| session_key::BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE.to_vec()),
            null_entropy_11a,
            sensor_ephemeral_pub65,
            sensor_static_pub65,
            static_scalar_window,
        };
        let out = session_key::derive_first_pair_phase5_material(&inputs, &self.tables, &self.schedule)
            .map_err(crypto_error)?;
        Ok(Libre3FirstPairPhase5KeyMaterial {
            source66: out.source66,
            raw_key: out.raw_key.to_vec(),
            null_entropy_11a: out.null_entropy_11a,
            null_scalar_window: out.null_scalar_window,
            null_attempts: out.null_attempts as u32,
        })
    }

    /// §5.3: `EphemeralExchange.sharedSecret` — raw 32-byte X9.63 shared secret. Consumed by
    /// the pairing byte-machines for both ECDH products (§5.3).
    pub fn ephemeral_shared_secret(
        &self,
        private_be32: Vec<u8>,
        peer_pub65: Vec<u8>,
    ) -> Result<Vec<u8>, Libre3Error> {
        let private_key = ::p256::SecretKey::from_slice(&private_be32)
            .map_err(|_| Libre3Error::Crypto { reason: "invalidScalarEncoding".to_owned() })?;
        let peer = ephemeral::exchange::parse_peer_pubkey(&peer_pub65).map_err(crypto_error)?;
        ephemeral::exchange::shared_secret(&private_key, &peer).map_err(crypto_error)
    }
}

// MARK: - data plane seam (PLAN_T1DMDROID.md §5.7, phase 5)

/// §5.7: the post-auth data-plane characteristics, mapped by 8-char lowercase UUID prefix.
/// Port of kit `DataPlaneChannel` (DataPlaneDecoder.swift L3-46); UUIDs cited in
/// `dataplane::DataPlaneChannel::from_uuid`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Libre3DataChannel {
    PatchControl,
    PatchStatus,
    GlucoseData,
    HistoricData,
    EventLog,
    ClinicalData,
    FactoryData,
}

impl From<dataplane::DataPlaneChannel> for Libre3DataChannel {
    fn from(c: dataplane::DataPlaneChannel) -> Self {
        match c {
            dataplane::DataPlaneChannel::PatchControl => Self::PatchControl,
            dataplane::DataPlaneChannel::PatchStatus => Self::PatchStatus,
            dataplane::DataPlaneChannel::GlucoseData => Self::GlucoseData,
            dataplane::DataPlaneChannel::HistoricData => Self::HistoricData,
            dataplane::DataPlaneChannel::EventLog => Self::EventLog,
            dataplane::DataPlaneChannel::ClinicalData => Self::ClinicalData,
            dataplane::DataPlaneChannel::FactoryData => Self::FactoryData,
        }
    }
}

impl From<Libre3DataChannel> for dataplane::DataPlaneChannel {
    fn from(c: Libre3DataChannel) -> Self {
        match c {
            Libre3DataChannel::PatchControl => Self::PatchControl,
            Libre3DataChannel::PatchStatus => Self::PatchStatus,
            Libre3DataChannel::GlucoseData => Self::GlucoseData,
            Libre3DataChannel::HistoricData => Self::HistoricData,
            Libre3DataChannel::EventLog => Self::EventLog,
            Libre3DataChannel::ClinicalData => Self::ClinicalData,
            Libre3DataChannel::FactoryData => Self::FactoryData,
        }
    }
}

/// One notify chunk as captured; `channel` None = unassembled, every packet kind tried.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3CapturedChunk {
    pub channel: Option<Libre3DataChannel>,
    pub bytes: Vec<u8>,
}

/// `kind`: `DataPlanePacketKind` declaration index. None = no tag verifies, or under 3 B.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3OpenedFrame {
    /// Index of the chunk that completed the frame.
    pub chunk: u32,
    pub channel: Option<Libre3DataChannel>,
    pub sequence: Option<u16>,
    pub kind: Option<u8>,
    pub plaintext: Option<Vec<u8>>,
}

/// §5.7: the warmup/active/expired lifecycle (kit `SensorLifecycle`).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3LifecycleRecord {
    /// "warmup" | "active" | "expired" (kit `SensorLifecyclePhase` rawValue).
    pub phase: String,
    pub is_warming_up: bool,
    pub is_expired: bool,
    pub remaining_warmup_min: u32,
    pub remaining_wear_min: Option<u32>,
}

impl From<status::SensorLifecycle> for Libre3LifecycleRecord {
    fn from(l: status::SensorLifecycle) -> Self {
        Self {
            phase: l.phase().description().to_owned(),
            is_warming_up: l.is_warming_up(),
            is_expired: l.is_expired(),
            remaining_warmup_min: u32::try_from(l.remaining_warmup_minutes()).unwrap_or(0),
            remaining_wear_min: l.remaining_wear_minutes().map(|v| u32::try_from(v).unwrap_or(0)),
        }
    }
}

/// §5.7: the 12-B patchStatus record plus the user-facing inference (kit `PatchStatus` +
/// `Libre3DataPlaneState`'s attention surface).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3PatchStatusRecord {
    pub life_count: i32,
    pub error_data: i16,
    pub event_data_raw: i16,
    /// Kit `eventData` = 4000 + raw (PatchStatus.swift L148).
    pub event_data: i32,
    pub index: i8,
    pub total_events: i32,
    pub patch_state: i8,
    pub current_life_count: i32,
    pub stack_disconnect_reason: i8,
    pub app_disconnect_reason: i8,
    pub is_patch_state_active: bool,
    pub is_patch_state_expired_or_error: bool,
    pub is_patch_state_terminated: bool,
    /// Kit `Libre3SensorError.description`.
    pub sensor_error: String,
    /// Kit `Libre3SensorAttention.description`.
    pub attention: String,
    pub should_notify_user: bool,
    pub should_notify_replace_sensor: bool,
    pub is_shutdown_terminated: bool,
    pub is_insertion_failure: bool,
    pub has_disconnect_reason: bool,
    /// Lifecycle re-assessed from this status and the session's warmup/wear durations.
    pub lifecycle: Libre3LifecycleRecord,
}

impl Libre3PatchStatusRecord {
    fn build(status: &status::PatchStatus, lifecycle: &status::SensorLifecycle) -> Self {
        Self {
            life_count: status.life_count,
            error_data: status.error_data,
            event_data_raw: status.event_data_raw,
            event_data: status.event_data,
            index: status.index,
            total_events: status.total_events,
            patch_state: status.patch_state,
            current_life_count: status.current_life_count,
            stack_disconnect_reason: status.stack_disconnect_reason,
            app_disconnect_reason: status.app_disconnect_reason,
            is_patch_state_active: status.is_patch_state_active(),
            is_patch_state_expired_or_error: status.is_patch_state_expired_or_error(),
            is_patch_state_terminated: status.is_patch_state_terminated(),
            sensor_error: status.sensor_error().description(),
            attention: status.sensor_attention().description(),
            should_notify_user: status.should_notify_user(),
            should_notify_replace_sensor: status.should_notify_replace_sensor(),
            is_shutdown_terminated: status.is_shutdown_terminated(),
            is_insertion_failure: status.is_insertion_failure(),
            has_disconnect_reason: status.has_disconnect_reason(),
            lifecycle: (*lifecycle).into(),
        }
    }
}

/// §5.7: the 29-B realtime glucose record plus the quality assessment (kit
/// `RealtimeGlucoseReading` + `Libre3GlucoseQualityAssessment`).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Libre3RealtimeGlucoseRecord {
    pub life_count: u16,
    pub current_mgdl: Option<u16>,
    pub uncapped_current_mgdl: u16,
    /// Kit `Libre3GlucoseValueStatus` description, e.g. `valid(103)`.
    pub current_status: String,
    pub historical_life_count: u16,
    pub historical_mgdl: Option<u16>,
    pub uncapped_historical_mgdl: u16,
    pub historical_status: String,
    /// `None` = the kit's Int16.min "no rate" sentinel.
    pub rate_raw: Option<i16>,
    /// mg/dL/min (1/100 of the raw rate).
    pub rate_mgdl_per_min: Option<f32>,
    pub trend: u8,
    pub trend_kind: String,
    pub actionability: String,
    pub actionable: bool,
    pub temperature_status: u16,
    pub projected_glucose: u16,
    pub temperature: u16,
    pub fast_data: Vec<u8>,
    pub dq_error: String,
    pub sensor_condition: String,
    pub historic_range_status: String,
    /// Kit `assessment.isUsable`.
    pub usable: bool,
    /// A warmup issue was among the blocking issues.
    pub warmup: bool,
    /// An expiry issue was among the blocking issues.
    pub expired: bool,
    /// All assessment issues, kit description strings, in kit order.
    pub issues: Vec<String>,
}

impl Libre3RealtimeGlucoseRecord {
    fn build(
        reading: &glucose::RealtimeGlucoseReading,
        assessment: &glucose::Libre3GlucoseQualityAssessment,
    ) -> Self {
        let blocking = assessment.blocking_issues();
        Self {
            life_count: reading.life_count,
            current_mgdl: reading.current_glucose_mg_dl,
            uncapped_current_mgdl: reading.uncapped_current_mg_dl,
            current_status: reading.current_glucose_status().to_string(),
            historical_life_count: reading.historical_life_count,
            historical_mgdl: reading.historical_glucose_mg_dl,
            uncapped_historical_mgdl: reading.uncapped_historic_mg_dl,
            historical_status: reading.historical_glucose_status().to_string(),
            rate_raw: (reading.rate_of_change_raw != i16::MIN).then_some(reading.rate_of_change_raw),
            rate_mgdl_per_min: reading.rate_of_change_mg_dl_per_minute,
            trend: reading.trend,
            trend_kind: reading.trend_kind.to_string(),
            actionability: reading.actionability.to_string(),
            actionable: reading.actionability == glucose::Libre3ActionableStatus::Actionable,
            temperature_status: reading.temperature_status,
            projected_glucose: reading.projected_glucose,
            temperature: reading.temperature,
            fast_data: reading.fast_data.clone(),
            dq_error: reading.dq_error.to_string(),
            sensor_condition: reading.sensor_condition.to_string(),
            historic_range_status: reading.historic_result_range_status.to_string(),
            usable: assessment.is_usable,
            warmup: blocking
                .iter()
                .any(|issue| matches!(issue, glucose::Libre3GlucoseQualityIssue::SensorWarmup { .. })),
            expired: blocking
                .iter()
                .any(|issue| matches!(issue, glucose::Libre3GlucoseQualityIssue::SensorExpired)),
            issues: assessment.issues.iter().map(|issue| issue.to_string()).collect(),
        }
    }
}

/// §5.7: one 14-B historical page, 6 samples at a 5-minute stride.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3HistoricalPageRecord {
    pub start_life_count: u16,
    pub values: Vec<u16>,
    pub sample_life_counts: Vec<u16>,
}

/// §5.7: the 14-B clinical-stream record, single time point (NOT a 6-sample page).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3ClinicalRecord {
    pub life_count: u16,
    pub current_glucose_raw: u16,
    pub historic_glucose_raw: u16,
    pub current_mgdl: Option<u16>,
    pub historic_mgdl: Option<u16>,
}

/// §5.7: what one decrypted data-plane notification means (kit
/// `Libre3DataPlaneStateUpdate`, flattened for the Kotlin seam).
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum Libre3DataPlaneUpdate {
    PatchStatus { status: Libre3PatchStatusRecord },
    RealtimeGlucose { reading: Libre3RealtimeGlucoseRecord },
    HistoricalPage { page: Libre3HistoricalPageRecord },
    Clinical { record: Libre3ClinicalRecord },
    Raw { channel: Libre3DataChannel, plaintext: Vec<u8> },
}

/// Mutable data-plane session state (kit `Libre3DataPlaneState` + the notification
/// assembler + per-channel tx counters).
struct DataPlaneSessionState {
    decoder: dataplane::DataPlaneDecoder,
    assembler: dataplane::DataPlaneNotificationAssembler,
    tx: dataplane::TxSequencer,
    warmup_duration_min: i32,
    wear_duration_min: Option<i32>,
    latest_status: Option<status::PatchStatus>,
    latest_lifecycle: Option<status::SensorLifecycle>,
    latest_realtime: Option<glucose::RealtimeGlucoseReading>,
    latest_quality_assessment: Option<glucose::Libre3GlucoseQualityAssessment>,
    last_accepted_glucose_life_count: Option<u16>,
    last_accepted_glucose_mgdl: Option<u16>,
    backfill: history::HistoricalBackfill,
}

impl DataPlaneSessionState {
    /// Kit `assess` (Libre3DataPlaneState.swift L164-166).
    fn assess(
        &self,
        reading: &glucose::RealtimeGlucoseReading,
    ) -> glucose::Libre3GlucoseQualityAssessment {
        reading.current_glucose_quality_assessment_lifecycle(self.latest_lifecycle.as_ref())
    }

    /// Kit `recordAcceptedGlucoseIfUsable` (Libre3DataPlaneState.swift L168-177).
    fn record_accepted_glucose_if_usable(
        &mut self,
        reading: &glucose::RealtimeGlucoseReading,
        assessment: &glucose::Libre3GlucoseQualityAssessment,
    ) {
        if !assessment.is_usable {
            return;
        }
        let Some(mgdl) = reading.current_glucose_mg_dl else {
            return;
        };
        self.last_accepted_glucose_life_count = Some(reading.life_count);
        self.last_accepted_glucose_mgdl = Some(mgdl);
    }

    /// Kit `record(_ packet:)` patchStatus branch (Libre3DataPlaneState.swift L126-138): store
    /// the status + re-assessed lifecycle, then re-assess the LATEST realtime reading against
    /// it (a landing status can change the reading's usability).
    fn record_status(&mut self, status: status::PatchStatus) -> Libre3PatchStatusRecord {
        let lifecycle = status.lifecycle(self.warmup_duration_min, self.wear_duration_min);
        self.latest_status = Some(status.clone());
        self.latest_lifecycle = Some(lifecycle);
        if let Some(reading) = self.latest_realtime.clone() {
            let assessment = reading.current_glucose_quality_assessment_lifecycle(Some(&lifecycle));
            self.latest_quality_assessment = Some(assessment.clone());
            self.record_accepted_glucose_if_usable(&reading, &assessment);
        }
        Libre3PatchStatusRecord::build(&status, &lifecycle)
    }

    /// Kit `record(_ packet:)` realtimeGlucose branch (Libre3DataPlaneState.swift L140-145).
    fn record_realtime(
        &mut self,
        reading: glucose::RealtimeGlucoseReading,
    ) -> Libre3RealtimeGlucoseRecord {
        let assessment = self.assess(&reading);
        self.latest_realtime = Some(reading.clone());
        self.latest_quality_assessment = Some(assessment.clone());
        self.record_accepted_glucose_if_usable(&reading, &assessment);
        Libre3RealtimeGlucoseRecord::build(&reading, &assessment)
    }
}

/// §5.7: one sensor's post-pairing data plane — decrypt, reassemble, decode, and sequence,
/// with the kit's `Libre3DataPlaneState` semantics (latest status/reading/lifecycle/backfill,
/// warmup suppression, reconnect backfill bound). All methods return `Result`; nothing panics.
#[derive(uniffi::Object)]
pub struct Libre3DataPlaneSession {
    state: std::sync::Mutex<DataPlaneSessionState>,
}

impl Libre3DataPlaneSession {
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, DataPlaneSessionState>, Libre3Error> {
        self.state.lock().map_err(|_| Libre3Error::Internal {
            reason: "data plane state poisoned".to_owned(),
        })
    }

    /// Kit plaintext-state seeding, no crypto; off the FFI seam; the golden tests seed status here.
    pub fn record_patch_status_plaintext(
        &self,
        plaintext: Vec<u8>,
    ) -> Result<Libre3PatchStatusRecord, Libre3Error> {
        let status = status::PatchStatus::parse(&plaintext).map_err(data_plane_error)?;
        Ok(self.lock()?.record_status(status))
    }
}

#[uniffi::export]
impl Libre3DataPlaneSession {
    /// `kEnc` 16 B + `ivEnc` 8 B (kit `DataPlaneCrypto.init`); warmup defaults to 60 min
    /// (SensorLifecycle.swift L10) and wear comes from provisioning (§5.7).
    #[uniffi::constructor]
    pub fn new(
        k_enc: Vec<u8>,
        iv_enc: Vec<u8>,
        warmup_duration_min: u32,
        wear_duration_min: Option<u32>,
    ) -> Result<Self, Libre3Error> {
        let crypto = dataplane::DataPlaneCrypto::new(&k_enc, &iv_enc).map_err(data_plane_error)?;
        Ok(Self {
            state: std::sync::Mutex::new(DataPlaneSessionState {
                decoder: dataplane::DataPlaneDecoder::new(crypto),
                assembler: dataplane::DataPlaneNotificationAssembler::new(),
                tx: dataplane::TxSequencer::new(),
                warmup_duration_min: i32::try_from(warmup_duration_min).unwrap_or(i32::MAX),
                wear_duration_min: wear_duration_min.map(|w| i32::try_from(w).unwrap_or(i32::MAX)),
                latest_status: None,
                latest_lifecycle: None,
                latest_realtime: None,
                latest_quality_assessment: None,
                last_accepted_glucose_life_count: None,
                last_accepted_glucose_mgdl: None,
                backfill: history::HistoricalBackfill::new(),
            }),
        })
    }

    /// Ingest one notify chunk on a channel: assemble → parse → decrypt (preferred → try-all)
    /// → decode. Returns a possibly-empty vec (a latched 15-B glucose prefix emits nothing);
    /// a payload size mismatch falls back to `Raw`, never an error; CCM failure after try-all
    /// is `Err` and Kotlin decides whether to continue streaming.
    pub fn feed(
        &self,
        channel: Libre3DataChannel,
        chunk: Vec<u8>,
    ) -> Result<Vec<Libre3DataPlaneUpdate>, Libre3Error> {
        let channel: dataplane::DataPlaneChannel = channel.into();
        let mut state = self.lock()?;
        let Some(assembled) = state.assembler.feed(&chunk, channel) else {
            return Ok(Vec::new());
        };
        let frame = dataplane::DataFrame::parse(&assembled).map_err(data_plane_error)?;
        let packet = state.decoder.decrypt(&frame, channel).map_err(|e| match e {
            DataPlaneError::NoDescriptorMatched => Libre3Error::Crypto {
                reason: format!("noDescriptorMatched(channel: {})", channel.name()),
            },
            other => data_plane_error(other),
        })?;

        let update = match packet.payload {
            dataplane::DataPlaneDecodedPayload::PatchStatus(status) => {
                Libre3DataPlaneUpdate::PatchStatus { status: state.record_status(status) }
            }
            dataplane::DataPlaneDecodedPayload::RealtimeGlucose(reading) => {
                Libre3DataPlaneUpdate::RealtimeGlucose { reading: state.record_realtime(reading) }
            }
            dataplane::DataPlaneDecodedPayload::HistoricalReadingPage(page) => {
                let record = Libre3HistoricalPageRecord {
                    start_life_count: page.start_life_count,
                    values: page.values.clone(),
                    sample_life_counts: page.samples().iter().map(|s| s.life_count).collect(),
                };
                state.backfill.append(page);
                Libre3DataPlaneUpdate::HistoricalPage { page: record }
            }
            dataplane::DataPlaneDecodedPayload::ClinicalReadingRecord(record) => {
                // Pass through unchanged (Libre3DataPlaneState.swift L151-157): a different
                // commit cadence; clinical never folds into historicalBackfill.
                Libre3DataPlaneUpdate::Clinical {
                    record: Libre3ClinicalRecord {
                        life_count: record.life_count,
                        current_glucose_raw: record.current_glucose_raw,
                        historic_glucose_raw: record.historic_glucose_raw,
                        current_mgdl: record.current_glucose_mg_dl(),
                        historic_mgdl: record.historic_glucose_mg_dl(),
                    },
                }
            }
            dataplane::DataPlaneDecodedPayload::Raw(plaintext) => Libre3DataPlaneUpdate::Raw {
                channel: channel.into(),
                plaintext,
            },
        };
        Ok(vec![update])
    }

    /// Encrypt one 7-B patchControl plaintext with kind0 and this channel's next tx sequence;
    /// returns the 13-B wire frame. Wrong size = `Err` (kit `wrongPlaintextSize`).
    pub fn next_patch_control_frame(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, Libre3Error> {
        let command =
            history::PatchControlCommand::new("patchControl", plaintext).map_err(data_plane_error)?;
        let mut state = self.lock()?;
        let sequence = state.tx.take(dataplane::DataPlaneChannel::PatchControl);
        let frame = state
            .decoder
            .crypto
            .encrypt(
                &command.plaintext,
                sequence,
                dataplane::DataPlanePacketKind::PATCH_CONTROL_WRITE,
            )
            .map_err(data_plane_error)?;
        Ok(frame.raw())
    }

    /// §5.7: the latest lifecycle, re-assessed on every patchStatus; `None` until the first
    /// status lands (kit `Libre3DataPlaneState.latestLifecycle`).
    pub fn latest_lifecycle(&self) -> Result<Option<Libre3LifecycleRecord>, Libre3Error> {
        Ok(self.lock()?.latest_lifecycle.map(Into::into))
    }

    /// §5.7: the reconnect backfill lower bound — the last accepted (usable, displayable)
    /// realtime glucose lifeCount (kit `reconnectBackfillLowerBoundLifeCount`).
    pub fn last_accepted_glucose_life_count(&self) -> Result<Option<u16>, Libre3Error> {
        Ok(self.lock()?.last_accepted_glucose_life_count)
    }

    /// Console only: own assembler, copied key; stream state and tx counters untouched.
    pub fn open_captured(
        &self,
        chunks: Vec<Libre3CapturedChunk>,
    ) -> Result<Vec<Libre3OpenedFrame>, Libre3Error> {
        let decoder = self.lock()?.decoder.clone();
        let mut assembler = dataplane::DataPlaneNotificationAssembler::new();
        let mut opened = Vec::new();
        for (i, chunk) in chunks.into_iter().enumerate() {
            let channel = chunk.channel.map(dataplane::DataPlaneChannel::from);
            let assembled = match channel {
                Some(c) => match assembler.feed(&chunk.bytes, c) {
                    Some(frame) => frame,
                    None => continue,
                },
                None => chunk.bytes,
            };
            let mut out = Libre3OpenedFrame {
                chunk: u32::try_from(i).unwrap_or(u32::MAX),
                channel: chunk.channel,
                sequence: None,
                kind: None,
                plaintext: None,
            };
            if let Ok(frame) = dataplane::DataFrame::parse(&assembled) {
                out.sequence = Some(frame.sequence_number());
                let result = match channel {
                    Some(c) => decoder.decrypt(&frame, c).map(|p| (p.kind, p.plaintext)),
                    None => decoder.crypto.decrypt_trying_all(&frame).map(|r| (r.kind, r.plaintext)),
                };
                if let Ok((kind, plaintext)) = result {
                    out.kind = Some(kind as u8);
                    out.plaintext = Some(plaintext);
                }
            }
            opened.push(out);
        }
        Ok(opened)
    }

    /// Console only: `kind` as in [`Libre3OpenedFrame`]; tx counters untouched, nothing sent.
    pub fn seal_frame(&self, kind: u8, sequence: u16, plaintext: Vec<u8>) -> Result<Vec<u8>, Libre3Error> {
        let kinds = dataplane::DataPlanePacketKind::ALL_CASES;
        let kind = *kinds.get(usize::from(kind)).ok_or_else(|| Libre3Error::Crypto {
            reason: format!("kind {kind}: 0–{}", kinds.len() - 1),
        })?;
        let crypto = self.lock()?.decoder.crypto.clone();
        let frame = crypto.encrypt(&plaintext, sequence, kind).map_err(data_plane_error)?;
        Ok(frame.raw())
    }
}

/// §5.7: `historicalBackfillGreaterEqual(lifeCount, selector)` — 7-B patchControl plaintext
/// `01 00 selector lifeCount_LE2 00 00` (PatchControlCommand.swift L20-47).
#[uniffi::export]
pub fn libre3_historical_backfill_cmd(life_count: u16, selector: u8) -> Result<Vec<u8>, Libre3Error> {
    Ok(history::PatchControlCommand::historical_backfill_greater_equal(life_count, selector)
        .map_err(data_plane_error)?
        .plaintext)
}

/// §5.7: `clinicalBackfillGreaterEqual(lifeCount, selector)` — `01 01 selector lifeCount_LE2
/// 00 00` (PatchControlCommand.swift L27-47).
#[uniffi::export]
pub fn libre3_clinical_backfill_cmd(life_count: u16, selector: u8) -> Result<Vec<u8>, Libre3Error> {
    Ok(history::PatchControlCommand::clinical_backfill_greater_equal(life_count, selector)
        .map_err(data_plane_error)?
        .plaintext)
}

/// §5.7 Range backfill (history.rs `backfill_range`): `01 00 selector start_LE2 end_LE2`.
#[uniffi::export]
pub fn libre3_historical_backfill_range_cmd(
    start_life_count: u16,
    end_life_count: u16,
    selector: u8,
) -> Result<Vec<u8>, Libre3Error> {
    Ok(history::PatchControlCommand::historical_backfill_range(
        start_life_count,
        end_life_count,
        selector,
    )
    .map_err(data_plane_error)?
    .plaintext)
}

/// §5.7 Range backfill: bounded `01 01 selector start_LE2 end_LE2` (clinical stream).
#[uniffi::export]
pub fn libre3_clinical_backfill_range_cmd(
    start_life_count: u16,
    end_life_count: u16,
    selector: u8,
) -> Result<Vec<u8>, Libre3Error> {
    Ok(history::PatchControlCommand::clinical_backfill_range(
        start_life_count,
        end_life_count,
        selector,
    )
    .map_err(data_plane_error)?
    .plaintext)
}

/// §5.7: `eventLog(index)` — `04 index 00 00 00 00 00` (PatchControlCommand.swift L49-55).
#[uniffi::export]
pub fn libre3_event_log_cmd(index: u8) -> Result<Vec<u8>, Libre3Error> {
    Ok(history::PatchControlCommand::event_log(index).map_err(data_plane_error)?.plaintext)
}

/// §5.7: `factoryData` — `06 00 00 00 00 00 00` (PatchControlCommand.swift L57-63).
#[uniffi::export]
pub fn libre3_factory_data_cmd() -> Result<Vec<u8>, Libre3Error> {
    Ok(history::PatchControlCommand::factory_data().map_err(data_plane_error)?.plaintext)
}
