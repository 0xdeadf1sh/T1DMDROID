//! PLAN_T1DMDROID.md §5.7: patchStatus (12 B) decode, sensor error/attention model, and the
//! sensor lifecycle. Port of kit PatchStatus.swift + SensorLifecycle.swift
//! (LibreCRKit/Sources/LibreCRKit/DataPlane/). Pure decode + pure derivation; raw/unknown
//! values stay visible, never coerced (plan §5.7).

use crate::DataPlaneError;

/// Port of kit `PatchStatus` (PatchStatus.swift L3-165).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PatchStatus {
    /// Minutes, u16 on the wire; widened unsigned, since the kit's i16 reads 22.75 d as negative.
    pub life_count: i32,
    pub error_data: i16,
    pub event_data_raw: i16,
    pub event_data: i32,
    pub index: i8,
    pub total_events: i32,
    pub patch_state: i8,
    /// As [`Self::life_count`].
    pub current_life_count: i32,
    pub stack_disconnect_reason: i8,
    pub app_disconnect_reason: i8,
}

impl PatchStatus {
    pub const PLAINTEXT_SIZE: usize = 12; // PatchStatus.swift L4

    /// Kit `PatchStatus(plaintext:)` (PatchStatus.swift L141-155). Little-endian words,
    /// signed per the kit.
    pub fn parse(plaintext: &[u8]) -> Result<Self, DataPlaneError> {
        if plaintext.len() != Self::PLAINTEXT_SIZE {
            return Err(DataPlaneError::WrongPlaintextSize { got: plaintext.len() });
        }
        let u16le = |off: usize| u16::from_le_bytes([plaintext[off], plaintext[off + 1]]);
        let event_data_raw = u16le(4) as i16;
        let index = plaintext[6] as i8;
        Ok(Self {
            life_count: i32::from(u16le(0)),
            error_data: u16le(2) as i16,
            event_data_raw,
            // PatchStatus.swift L148: display value is 4000 + raw.
            event_data: 4000 + event_data_raw as i32,
            index,
            // PatchStatus.swift L150.
            total_events: index as i32 + 1,
            patch_state: plaintext[7] as i8,
            current_life_count: i32::from(u16le(8)),
            stack_disconnect_reason: plaintext[10] as i8,
            app_disconnect_reason: plaintext[11] as i8,
        })
    }

    pub fn patch_state_kind(&self) -> Libre3PatchState {
        Libre3PatchState::from_raw(self.patch_state)
    }

    pub fn is_patch_state_active(&self) -> bool {
        self.patch_state_kind().is_active()
    }

    pub fn is_patch_state_expired_or_error(&self) -> bool {
        self.patch_state_kind().is_expired_or_error()
    }

    pub fn is_patch_state_terminated(&self) -> bool {
        self.patch_state_kind().is_terminated()
    }

    /// PatchStatus.swift L33-35.
    pub fn has_error_data(&self) -> bool {
        self.error_data != 0
    }

    /// PatchStatus.swift L42-44.
    pub fn sensor_error(&self) -> Libre3SensorError {
        Libre3SensorError::from_code(self.error_data)
    }

    /// PatchStatus.swift L56-58: user-facing attention from `errorData`, patch-state fallback.
    pub fn sensor_attention(&self) -> Libre3SensorAttention {
        Libre3SensorAttention::from_parts(self.error_data, Some(self.patch_state))
    }

    /// PatchStatus.swift L62-64.
    pub fn should_notify_user(&self) -> bool {
        self.sensor_attention().should_notify_user()
    }

    /// PatchStatus.swift L67-69.
    pub fn should_notify_replace_sensor(&self) -> bool {
        self.sensor_attention().is_replace_sensor()
    }

    /// PatchStatus.swift L77-79: post-shutdown terminated (never true for `.expired`).
    pub fn is_shutdown_terminated(&self) -> bool {
        self.sensor_error().is_shutdown_terminated()
    }

    /// PatchStatus.swift L82-84.
    pub fn is_insertion_failure(&self) -> bool {
        self.sensor_error().is_insertion_failure()
    }

    /// PatchStatus.swift L105-107: disconnect evidence; non-zero does not mean shutdown.
    pub fn has_disconnect_reason(&self) -> bool {
        self.stack_disconnect_reason != 0 || self.app_disconnect_reason != 0
    }

    /// Kit `PatchStatus.lifecycle(warmupDurationMinutes:wearDurationMinutes:)`
    /// (PatchStatus.swift L121-130) — keyed at `currentLifeCount` (offset 8), not `lifeCount`.
    pub fn lifecycle(
        &self,
        warmup_duration_minutes: i32,
        wear_duration_minutes: Option<i32>,
    ) -> SensorLifecycle {
        SensorLifecycle::new(
            self.current_life_count,
            warmup_duration_minutes,
            wear_duration_minutes,
        )
    }
}

/// Port of kit `Libre3PatchState` (PatchStatus.swift L322-369): only 4 is named; every other
/// value stays visible as `Raw`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3PatchState {
    Active,
    Raw(i8),
}

impl Libre3PatchState {
    pub fn from_raw(raw_value: i8) -> Self {
        match raw_value {
            4 => Self::Active, // PatchStatus.swift L327-332
            other => Self::Raw(other),
        }
    }

    pub fn raw_value(&self) -> i8 {
        match self {
            Self::Active => 4,
            Self::Raw(value) => *value,
        }
    }

    pub fn is_active(&self) -> bool {
        self.raw_value() == 4
    }

    /// PatchStatus.swift L352-354: expired/error handling, distinct from terminated 6/8.
    pub fn is_expired_or_error(&self) -> bool {
        matches!(self.raw_value(), 3 | 5 | 7)
    }

    /// PatchStatus.swift L357-359: already terminated/shut down.
    pub fn is_terminated(&self) -> bool {
        matches!(self.raw_value(), 6 | 8)
    }

    /// Kit `description` (PatchStatus.swift L361-368).
    pub fn description(&self) -> String {
        match self {
            Self::Active => "active".to_owned(),
            Self::Raw(value) => format!("raw({value})"),
        }
    }
}

/// Port of kit `Libre3SensorError` (PatchStatus.swift L259-320): `3 → insertionFailure`,
/// `5 → expired`, `6/8 → terminated`, `7 → transmissionError`; everything else stays visible.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3SensorError {
    None,
    InsertionFailure,
    Expired,
    Terminated,
    TransmissionError,
    Unknown(i16),
}

impl Libre3SensorError {
    /// Kit `init(code:)` (PatchStatus.swift L277-292).
    pub fn from_code(code: i16) -> Self {
        match code {
            0 => Self::None,
            3 => Self::InsertionFailure,
            5 => Self::Expired,
            6 | 8 => Self::Terminated,
            7 => Self::TransmissionError,
            other => Self::Unknown(other),
        }
    }

    /// Kit `isShutdownTerminated` (PatchStatus.swift L295-297).
    pub fn is_shutdown_terminated(&self) -> bool {
        *self == Self::Terminated
    }

    /// Kit `isInsertionFailure` (PatchStatus.swift L300-302).
    pub fn is_insertion_failure(&self) -> bool {
        *self == Self::InsertionFailure
    }

    /// Kit `description` (PatchStatus.swift L304-319).
    pub fn description(&self) -> String {
        match self {
            Self::None => "none".to_owned(),
            Self::InsertionFailure => "insertionFailure".to_owned(),
            Self::Expired => "expired".to_owned(),
            Self::Terminated => "terminated".to_owned(),
            Self::TransmissionError => "transmissionError".to_owned(),
            Self::Unknown(code) => format!("unknown({code})"),
        }
    }
}

/// Port of kit `Libre3SensorAttention` (PatchStatus.swift L181-248): `3 → checkSensor`,
/// `5/6 → sensorEnded`, `7 → checkSensor` (transient, NOT replace), `8 → replaceSensor`;
/// `errorData == 0` falls to the patch state (`3 → checkSensor`, `7/8 → replaceSensor`,
/// `5/6 → sensorEnded`, else none).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3SensorAttention {
    None,
    CheckSensor,
    SensorEnded,
    ReplaceSensor,
    Unknown(i16),
}

impl Libre3SensorAttention {
    /// Kit `init(errorData:patchState:)` (PatchStatus.swift L188-224).
    pub fn from_parts(error_data: i16, patch_state: Option<i8>) -> Self {
        match error_data {
            0 => {}
            3 => return Self::CheckSensor,
            5 | 6 => return Self::SensorEnded,
            7 => return Self::CheckSensor,
            8 => return Self::ReplaceSensor,
            other => return Self::Unknown(other),
        }
        match patch_state {
            Some(3) => Self::CheckSensor,
            Some(7 | 8) => Self::ReplaceSensor,
            Some(5 | 6) => Self::SensorEnded,
            _ => Self::None,
        }
    }

    /// Kit `shouldNotifyUser` (PatchStatus.swift L226-228).
    pub fn should_notify_user(&self) -> bool {
        *self != Self::None
    }

    /// Kit `isReplaceSensor` (PatchStatus.swift L230-232).
    pub fn is_replace_sensor(&self) -> bool {
        *self == Self::ReplaceSensor
    }

    /// Kit `description` (PatchStatus.swift L234-247).
    pub fn description(&self) -> String {
        match self {
            Self::None => "none".to_owned(),
            Self::CheckSensor => "checkSensor".to_owned(),
            Self::SensorEnded => "sensorEnded".to_owned(),
            Self::ReplaceSensor => "replaceSensor".to_owned(),
            Self::Unknown(code) => format!("unknown({code})"),
        }
    }
}

/// Port of kit `SensorLifecyclePhase` (SensorLifecycle.swift L3-7).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SensorLifecyclePhase {
    Warmup,
    Active,
    Expired,
}

impl SensorLifecyclePhase {
    /// Kit rawValue string (SensorLifecycle.swift L3-7).
    pub fn description(&self) -> &'static str {
        match self {
            Self::Warmup => "warmup",
            Self::Active => "active",
            Self::Expired => "expired",
        }
    }
}

/// Port of kit `SensorLifecycle` (SensorLifecycle.swift L9-66). Durations clamp at 0 like the
/// kit (`max(0, …)`); `currentLifeCountMinutes` is the sensor's own clock, i32 like Swift Int.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SensorLifecycle {
    pub current_life_count_minutes: i32,
    pub warmup_duration_minutes: i32,
    pub wear_duration_minutes: Option<i32>,
}

impl SensorLifecycle {
    /// SensorLifecycle.swift L10.
    pub const DEFAULT_WARMUP_DURATION_MINUTES: i32 = 60;

    pub fn new(
        current_life_count_minutes: i32,
        warmup_duration_minutes: i32,
        wear_duration_minutes: Option<i32>,
    ) -> Self {
        Self {
            current_life_count_minutes,
            warmup_duration_minutes: warmup_duration_minutes.max(0),
            wear_duration_minutes: wear_duration_minutes.map(|w| w.max(0)),
        }
    }

    /// SensorLifecycle.swift L26-28.
    pub fn elapsed_minutes(&self) -> i32 {
        self.current_life_count_minutes.max(0)
    }

    /// SensorLifecycle.swift L30-32.
    pub fn remaining_warmup_minutes(&self) -> i32 {
        (self.warmup_duration_minutes - self.elapsed_minutes()).max(0)
    }

    /// SensorLifecycle.swift L34-36.
    pub fn remaining_wear_minutes(&self) -> Option<i32> {
        self.wear_duration_minutes.map(|w| (w - self.elapsed_minutes()).max(0))
    }

    /// SensorLifecycle.swift L38-40.
    pub fn is_warmup_complete(&self) -> bool {
        self.elapsed_minutes() >= self.warmup_duration_minutes
    }

    /// SensorLifecycle.swift L42-44.
    pub fn is_warming_up(&self) -> bool {
        !self.is_warmup_complete() && !self.is_expired()
    }

    /// SensorLifecycle.swift L46-51: no wear duration ⇒ never expired.
    pub fn is_expired(&self) -> bool {
        match self.wear_duration_minutes {
            Some(wear) => self.elapsed_minutes() >= wear,
            None => false,
        }
    }

    /// SensorLifecycle.swift L57-65: expired wins, then warmup, then active.
    pub fn phase(&self) -> SensorLifecyclePhase {
        if self.is_expired() {
            SensorLifecyclePhase::Expired
        } else if !self.is_warmup_complete() {
            SensorLifecyclePhase::Warmup
        } else {
            SensorLifecyclePhase::Active
        }
    }
}
