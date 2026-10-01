//! PLAN_T1DMDROID.md §5.7: the 29-byte realtime glucose decode, the glucose-value display
//! clamp, and the quality/usability model. Port of kit RealtimeGlucoseReading.swift 1:1
//! (LibreCRKit/Sources/LibreCRKit/DataPlane/). Raw/unknown values stay visible, never coerced;
//! `.notActionable` is advisory and never suppresses the reading.

use crate::status::SensorLifecycle;
use crate::DataPlaneError;

/// Port of kit `RealtimeGlucoseReading` (RealtimeGlucoseReading.swift L3-227).
#[derive(Debug, Clone, PartialEq)]
pub struct RealtimeGlucoseReading {
    pub life_count: u16,
    pub current_word: u16,
    pub reading_mg_dl: u16,
    pub dq_error_raw: u16,
    pub dq_error: Libre3DataQualityError,
    pub sensor_condition_raw: u8,
    pub sensor_condition: Libre3SensorCondition,
    pub current_glucose_mg_dl: Option<u16>,
    pub is_current_glucose_valid: bool,
    pub rate_of_change_raw: i16,
    pub rate_of_change_mg_dl_per_minute: Option<f32>,
    pub trend_raw: u8,
    /// Kit pins this at 0 (RealtimeGlucoseReading.swift L167); kept as a field for 1:1.
    pub esa_duration: u16,
    pub temperature_status: u16,
    pub projected_glucose: u16,
    pub historical_life_count: u16,
    pub historical_word: u16,
    pub historical_reading: u16,
    pub historical_reading_dq_error_raw: u16,
    pub historical_reading_dq_error: Libre3DataQualityError,
    pub historic_result_range_status_raw: u8,
    pub historic_result_range_status: Libre3ResultRangeStatus,
    pub historical_glucose_mg_dl: Option<u16>,
    pub is_historical_glucose_valid: bool,
    pub trend_and_status_byte: u8,
    pub trend: u8,
    pub trend_kind: Libre3Trend,
    /// Kit `rest` / `statusBits` (L182, L44-46).
    pub rest: u8,
    pub actionable_status: u8,
    pub actionability: Libre3ActionableStatus,
    pub uncapped_current_mg_dl: u16,
    pub uncapped_historic_mg_dl: u16,
    pub temperature: u16,
    pub fast_data: Vec<u8>,
    pub fast_data_words_le: [u16; 4],
    pub words_le: Vec<u16>,
    pub trailing_byte: u8,
}

impl RealtimeGlucoseReading {
    pub const PLAINTEXT_SIZE: usize = 29; // RealtimeGlucoseReading.swift L4

    /// Kit `RealtimeGlucoseReading(plaintext:)` (RealtimeGlucoseReading.swift L138-201).
    pub fn parse(plaintext: &[u8]) -> Result<Self, DataPlaneError> {
        if plaintext.len() != Self::PLAINTEXT_SIZE {
            return Err(DataPlaneError::WrongPlaintextSize { got: plaintext.len() });
        }
        let u16le = |off: usize| u16::from_le_bytes([plaintext[off], plaintext[off + 1]]);
        let rate_of_change_raw = u16le(4) as i16;
        let current_word = u16le(2);
        let historical_word = u16le(12);
        let trend_and_rest = plaintext[14];
        let trend = trend_and_rest & 0x07; // RealtimeGlucoseReading.swift L146
        let actionable_status: u8 = if trend_and_rest & 0x08 == 0 { 0 } else { 1 }; // L147
        let uncapped_current_mg_dl = u16le(15);
        let current_glucose_mg_dl = normalized_glucose_mg_dl(uncapped_current_mg_dl);
        let uncapped_historic_mg_dl = u16le(17);
        let historical_glucose_mg_dl = normalized_glucose_mg_dl(uncapped_historic_mg_dl);

        let words_le: Vec<u16> = (0..plaintext.len() - 1)
            .step_by(2)
            .map(|off| u16le(off))
            .collect();

        Ok(Self {
            life_count: u16le(0),
            current_word,
            reading_mg_dl: glucose_value_from_packed_word(current_word),
            dq_error_raw: dq_error_raw_from_packed_word(current_word),
            dq_error: Libre3DataQualityError::from_raw(dq_error_raw_from_packed_word(current_word)),
            sensor_condition_raw: status_bits_13_to_14(current_word),
            sensor_condition: Libre3SensorCondition::from_raw(status_bits_13_to_14(current_word)),
            current_glucose_mg_dl,
            is_current_glucose_valid: current_glucose_mg_dl.is_some(),
            rate_of_change_raw,
            // L163-165: Int16.min means "no rate"; everything else is 1/100 mg/dL/min.
            rate_of_change_mg_dl_per_minute: if rate_of_change_raw == i16::MIN {
                None
            } else {
                Some(f32::from(rate_of_change_raw) / 100.0)
            },
            trend_raw: trend,
            esa_duration: 0,
            temperature_status: u16le(6),
            projected_glucose: u16le(8),
            historical_life_count: u16le(10),
            historical_word,
            historical_reading: glucose_value_from_packed_word(historical_word),
            historical_reading_dq_error_raw: dq_error_raw_from_packed_word(historical_word),
            historical_reading_dq_error: Libre3DataQualityError::from_raw(
                dq_error_raw_from_packed_word(historical_word),
            ),
            historic_result_range_status_raw: status_bits_13_to_14(historical_word),
            historic_result_range_status: Libre3ResultRangeStatus::from_raw(
                status_bits_13_to_14(historical_word),
            ),
            historical_glucose_mg_dl,
            is_historical_glucose_valid: historical_glucose_mg_dl.is_some(),
            trend_and_status_byte: trend_and_rest,
            trend,
            trend_kind: Libre3Trend::from_raw(trend),
            rest: trend_and_rest >> 3,
            actionable_status,
            actionability: Libre3ActionableStatus::from_raw(actionable_status),
            uncapped_current_mg_dl,
            uncapped_historic_mg_dl,
            temperature: u16le(19),
            fast_data: plaintext[21..29].to_vec(),
            fast_data_words_le: [
                u16le(21),
                u16le(23),
                u16le(25),
                u16le(27),
            ],
            words_le,
            trailing_byte: plaintext[plaintext.len() - 1],
        })
    }

    /// Kit `statusBits` (RealtimeGlucoseReading.swift L44-46).
    pub fn status_bits(&self) -> u8 {
        self.rest
    }

    /// Kit `currentGlucoseStatus` (L48-50).
    pub fn current_glucose_status(&self) -> Libre3GlucoseValueStatus {
        Libre3GlucoseValueStatus::from_raw_sensor_value(self.uncapped_current_mg_dl)
    }

    /// Kit `historicalGlucoseStatus` (L52-54).
    pub fn historical_glucose_status(&self) -> Libre3GlucoseValueStatus {
        Libre3GlucoseValueStatus::from_raw_sensor_value(self.uncapped_historic_mg_dl)
    }

    /// Kit `rateOfChangeAvailable` (L56-58).
    pub fn rate_of_change_available(&self) -> bool {
        self.rate_of_change_mg_dl_per_minute.is_some()
    }

    /// Kit `isCurrentDQGood` (L60-62).
    pub fn is_current_dq_good(&self) -> bool {
        self.dq_error.is_good() && self.sensor_condition == Libre3SensorCondition::Ok
    }

    /// Kit `isHistoricalDQGood` (L64-66).
    pub fn is_historical_dq_good(&self) -> bool {
        self.historical_reading_dq_error.is_good()
            && self.historic_result_range_status == Libre3ResultRangeStatus::InRange
    }

    /// Kit `isCurrentGlucoseUsable` without lifecycle (L68-70).
    pub fn is_current_glucose_usable(&self) -> bool {
        self.is_current_glucose_valid && self.is_current_dq_good()
    }

    /// Kit `currentGlucoseQualityAssessment` (L72-74).
    pub fn current_glucose_quality_assessment(&self) -> Libre3GlucoseQualityAssessment {
        self.current_glucose_quality_assessment_lifecycle(None)
    }

    /// Kit `currentGlucoseQualityAssessment(lifecycle:)` (L80-111). Warmup/expiry come from the
    /// lifecycle; everything else from the reading itself. Not-actionable is advisory only.
    pub fn current_glucose_quality_assessment_lifecycle(
        &self,
        lifecycle: Option<&SensorLifecycle>,
    ) -> Libre3GlucoseQualityAssessment {
        let mut issues: Vec<Libre3GlucoseQualityIssue> = Vec::new();

        if let Some(lifecycle) = lifecycle {
            if lifecycle.is_expired() {
                issues.push(Libre3GlucoseQualityIssue::SensorExpired);
            } else if lifecycle.is_warming_up() {
                issues.push(Libre3GlucoseQualityIssue::SensorWarmup {
                    remaining_minutes: lifecycle.remaining_warmup_minutes(),
                });
            }
        }

        if !self.current_glucose_status().is_displayable() {
            issues.push(Libre3GlucoseQualityIssue::CurrentGlucoseUnavailable(
                self.current_glucose_status(),
            ));
        }
        if !self.dq_error.is_good() {
            issues.push(Libre3GlucoseQualityIssue::CurrentDataQuality(self.dq_error));
        }
        if self.sensor_condition != Libre3SensorCondition::Ok {
            issues.push(Libre3GlucoseQualityIssue::SensorCondition(self.sensor_condition));
        }
        if self.actionability != Libre3ActionableStatus::Actionable {
            issues.push(Libre3GlucoseQualityIssue::NotActionable(self.actionability));
        }

        let is_usable = !issues.iter().any(|issue| issue.is_usability_blocking());
        Libre3GlucoseQualityAssessment {
            is_usable,
            issues,
            evidence: self.quality_evidence(),
        }
    }

    /// Kit `qualityEvidence` (RealtimeGlucoseReading.swift L113-136).
    pub fn quality_evidence(&self) -> RealtimeGlucoseQualityEvidence {
        RealtimeGlucoseQualityEvidence {
            dq_error_raw: self.dq_error_raw,
            dq_error: self.dq_error,
            historical_reading_dq_error_raw: self.historical_reading_dq_error_raw,
            historical_reading_dq_error: self.historical_reading_dq_error,
            sensor_condition_raw: self.sensor_condition_raw,
            sensor_condition: self.sensor_condition,
            historic_result_range_status_raw: self.historic_result_range_status_raw,
            historic_result_range_status: self.historic_result_range_status,
            actionable_status: self.actionable_status,
            actionability: self.actionability,
            temperature_status: self.temperature_status,
            current_glucose: self.current_glucose_status(),
            historical_glucose: self.historical_glucose_status(),
            rate_of_change_available: self.rate_of_change_available(),
            trend: self.trend_kind,
            trend_bits: self.trend,
            status_bits: self.status_bits(),
            temperature_raw: self.temperature,
            fast_data: self.fast_data.clone(),
            fast_data_words_le: self.fast_data_words_le,
        }
    }
}

/// Kit `glucoseValue(fromPackedWord:)` (RealtimeGlucoseReading.swift L212-214).
fn glucose_value_from_packed_word(value: u16) -> u16 {
    value & 0x1fff
}

/// Kit `dqErrorRaw(fromPackedWord:)` (RealtimeGlucoseReading.swift L216-218).
fn dq_error_raw_from_packed_word(value: u16) -> u16 {
    if value & 0x8000 == 0 {
        0
    } else {
        value
    }
}

/// Kit `statusBits13To14(fromPackedWord:)` (RealtimeGlucoseReading.swift L220-222).
fn status_bits_13_to_14(value: u16) -> u8 {
    ((value >> 13) & 0x03) as u8
}

/// Kit `normalizedGlucoseMgDL` (L208-210): the display clamp via `Libre3GlucoseValueStatus`.
fn normalized_glucose_mg_dl(value: u16) -> Option<u16> {
    Libre3GlucoseValueStatus::from_raw_sensor_value(value).display_mg_dl()
}

/// Port of kit `Libre3DataQualityError` (RealtimeGlucoseReading.swift L229-293).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3DataQualityError {
    Good,
    SensorTooHot(u16),
    SensorTooCold(u16),
    NotDisplayable(u16),
    Raw(u16),
}

impl Libre3DataQualityError {
    /// Kit `init(rawValue:)` (L236-249); the 0xA000/0xC000 masks decide hot/cold before the
    /// generic not-displayable branch.
    pub fn from_raw(raw_value: u16) -> Self {
        match raw_value {
            0 => Self::Good,
            value if (value & 0xE000) == 0xA000 => Self::SensorTooHot(value),
            value if (value & 0xE000) == 0xC000 => Self::SensorTooCold(value),
            value if (value & 0x8000) != 0 => Self::NotDisplayable(value),
            other => Self::Raw(other),
        }
    }

    /// Kit `rawValue` (L251-264).
    pub fn raw_value(&self) -> u16 {
        match self {
            Self::Good => 0,
            Self::SensorTooHot(v)
            | Self::SensorTooCold(v)
            | Self::NotDisplayable(v)
            | Self::Raw(v) => *v,
        }
    }

    pub fn is_good(&self) -> bool {
        *self == Self::Good
    }

    /// Kit `isNotDisplayable` (L270-277).
    pub fn is_not_displayable(&self) -> bool {
        matches!(self, Self::SensorTooHot(_) | Self::SensorTooCold(_) | Self::NotDisplayable(_))
    }
}

impl std::fmt::Display for Libre3DataQualityError {
    /// Kit `description` (L279-292).
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Good => write!(f, "good"),
            Self::SensorTooHot(v) => write!(f, "sensorTooHot(0x{v:04x})"),
            Self::SensorTooCold(v) => write!(f, "sensorTooCold(0x{v:04x})"),
            Self::NotDisplayable(v) => write!(f, "notDisplayable(0x{v:04x})"),
            Self::Raw(v) => write!(f, "raw(0x{v:04x})"),
        }
    }
}

/// Port of kit `Libre3SensorCondition` (RealtimeGlucoseReading.swift L295-339).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3SensorCondition {
    Ok,
    Invalid,
    Esa,
    Raw(u8),
}

impl Libre3SensorCondition {
    pub fn from_raw(raw_value: u8) -> Self {
        match raw_value {
            0 => Self::Ok,
            1 => Self::Invalid,
            2 => Self::Esa,
            other => Self::Raw(other),
        }
    }

    pub fn raw_value(&self) -> u8 {
        match self {
            Self::Ok => 0,
            Self::Invalid => 1,
            Self::Esa => 2,
            Self::Raw(v) => *v,
        }
    }
}

impl std::fmt::Display for Libre3SensorCondition {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Ok => write!(f, "ok"),
            Self::Invalid => write!(f, "invalid"),
            Self::Esa => write!(f, "esa"),
            Self::Raw(v) => write!(f, "raw({v})"),
        }
    }
}

/// Port of kit `Libre3ResultRangeStatus` (RealtimeGlucoseReading.swift L341-385).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3ResultRangeStatus {
    InRange,
    BelowRange,
    AboveRange,
    Raw(u8),
}

impl Libre3ResultRangeStatus {
    pub fn from_raw(raw_value: u8) -> Self {
        match raw_value {
            0 => Self::InRange,
            1 => Self::BelowRange,
            2 => Self::AboveRange,
            other => Self::Raw(other),
        }
    }

    pub fn raw_value(&self) -> u8 {
        match self {
            Self::InRange => 0,
            Self::BelowRange => 1,
            Self::AboveRange => 2,
            Self::Raw(v) => *v,
        }
    }
}

impl std::fmt::Display for Libre3ResultRangeStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InRange => write!(f, "inRange"),
            Self::BelowRange => write!(f, "belowRange"),
            Self::AboveRange => write!(f, "aboveRange"),
            Self::Raw(v) => write!(f, "raw({v})"),
        }
    }
}

/// Port of kit `Libre3ActionableStatus` (RealtimeGlucoseReading.swift L387-424).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3ActionableStatus {
    NotActionable,
    Actionable,
    Raw(u8),
}

impl Libre3ActionableStatus {
    pub fn from_raw(raw_value: u8) -> Self {
        match raw_value {
            0 => Self::NotActionable,
            1 => Self::Actionable,
            other => Self::Raw(other),
        }
    }

    pub fn raw_value(&self) -> u8 {
        match self {
            Self::NotActionable => 0,
            Self::Actionable => 1,
            Self::Raw(v) => *v,
        }
    }
}

impl std::fmt::Display for Libre3ActionableStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotActionable => write!(f, "notActionable"),
            Self::Actionable => write!(f, "actionable"),
            Self::Raw(v) => write!(f, "raw({v})"),
        }
    }
}

/// Port of kit `Libre3Trend` (RealtimeGlucoseReading.swift L426-491).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3Trend {
    NotDetermined,
    FallingQuickly,
    Falling,
    Stable,
    Rising,
    RisingQuickly,
    Raw(u8),
}

impl Libre3Trend {
    pub fn from_raw(raw_value: u8) -> Self {
        match raw_value {
            0 => Self::NotDetermined,
            1 => Self::FallingQuickly,
            2 => Self::Falling,
            3 => Self::Stable,
            4 => Self::Rising,
            5 => Self::RisingQuickly,
            other => Self::Raw(other),
        }
    }

    pub fn raw_value(&self) -> u8 {
        match self {
            Self::NotDetermined => 0,
            Self::FallingQuickly => 1,
            Self::Falling => 2,
            Self::Stable => 3,
            Self::Rising => 4,
            Self::RisingQuickly => 5,
            Self::Raw(v) => *v,
        }
    }
}

impl std::fmt::Display for Libre3Trend {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotDetermined => write!(f, "notDetermined"),
            Self::FallingQuickly => write!(f, "fallingQuickly"),
            Self::Falling => write!(f, "falling"),
            Self::Stable => write!(f, "stable"),
            Self::Rising => write!(f, "rising"),
            Self::RisingQuickly => write!(f, "risingQuickly"),
            Self::Raw(v) => write!(f, "raw({v})"),
        }
    }
}

/// Port of kit `Libre3GlucoseValueStatus` (RealtimeGlucoseReading.swift L560-610): the
/// 39..501 display window with 1–38 → 39 and 502–999 → 501 clamps.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Libre3GlucoseValueStatus {
    Valid(u16),
    BelowDisplayRange { raw: u16, display_mg_dl: u16 },
    AboveDisplayRange { raw: u16, display_mg_dl: u16 },
    Unavailable(u16),
}

impl Libre3GlucoseValueStatus {
    /// Kit `init(rawSensorValue:)` (L566-577).
    pub fn from_raw_sensor_value(value: u16) -> Self {
        match value {
            // Swift 1..<39 (values 1–38) → below, clamped to 39.
            1..=38 => Self::BelowDisplayRange { raw: value, display_mg_dl: 39 },
            39..=501 => Self::Valid(value),
            // Swift 502..<1000 (values 502–999) → above, clamped to 501.
            502..=999 => Self::AboveDisplayRange { raw: value, display_mg_dl: 501 },
            other => Self::Unavailable(other),
        }
    }

    /// Kit `displayMgDL` (L579-590).
    pub fn display_mg_dl(&self) -> Option<u16> {
        match self {
            Self::Valid(mg_dl) => Some(*mg_dl),
            Self::BelowDisplayRange { display_mg_dl, .. } => Some(*display_mg_dl),
            Self::AboveDisplayRange { display_mg_dl, .. } => Some(*display_mg_dl),
            Self::Unavailable(_) => None,
        }
    }

    /// Kit `isDisplayable` (L592-594).
    pub fn is_displayable(&self) -> bool {
        self.display_mg_dl().is_some()
    }
}

impl std::fmt::Display for Libre3GlucoseValueStatus {
    /// Kit `description` (L597-610).
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Valid(mg_dl) => write!(f, "valid({mg_dl})"),
            Self::BelowDisplayRange { raw, display_mg_dl } => {
                write!(f, "belowDisplayRange(raw: {raw}, displayMgDL: {display_mg_dl})")
            }
            Self::AboveDisplayRange { raw, display_mg_dl } => {
                write!(f, "aboveDisplayRange(raw: {raw}, displayMgDL: {display_mg_dl})")
            }
            Self::Unavailable(raw) => write!(f, "unavailable(raw: {raw})"),
        }
    }
}

/// Port of kit `Libre3GlucoseQualityIssue` (RealtimeGlucoseReading.swift L612-655).
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Libre3GlucoseQualityIssue {
    SensorWarmup { remaining_minutes: i32 },
    SensorExpired,
    CurrentGlucoseUnavailable(Libre3GlucoseValueStatus),
    CurrentDataQuality(Libre3DataQualityError),
    SensorCondition(Libre3SensorCondition),
    NotActionable(Libre3ActionableStatus),
}

impl Libre3GlucoseQualityIssue {
    /// Kit `isAdvisory` (L625-633): `.notActionable` never suppresses the reading.
    pub fn is_advisory(&self) -> bool {
        matches!(self, Self::NotActionable(_))
    }

    /// Kit `isUsabilityBlocking` (L635-637).
    pub fn is_usability_blocking(&self) -> bool {
        !self.is_advisory()
    }
}

impl std::fmt::Display for Libre3GlucoseQualityIssue {
    /// Kit `description` (L639-654).
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::SensorWarmup { remaining_minutes } => {
                write!(f, "sensorWarmup(remainingMinutes: {remaining_minutes})")
            }
            Self::SensorExpired => write!(f, "sensorExpired"),
            Self::CurrentGlucoseUnavailable(status) => {
                write!(f, "currentGlucoseUnavailable({status})")
            }
            Self::CurrentDataQuality(error) => write!(f, "currentDataQuality({error})"),
            Self::SensorCondition(condition) => write!(f, "sensorCondition({condition})"),
            Self::NotActionable(status) => write!(f, "notActionable({status})"),
        }
    }
}

/// Port of kit `RealtimeGlucoseQualityEvidence` (RealtimeGlucoseReading.swift L674-695): the
/// raw fields behind an assessment, kept for logging/telemetry.
#[derive(Debug, Clone, PartialEq)]
pub struct RealtimeGlucoseQualityEvidence {
    pub dq_error_raw: u16,
    pub dq_error: Libre3DataQualityError,
    pub historical_reading_dq_error_raw: u16,
    pub historical_reading_dq_error: Libre3DataQualityError,
    pub sensor_condition_raw: u8,
    pub sensor_condition: Libre3SensorCondition,
    pub historic_result_range_status_raw: u8,
    pub historic_result_range_status: Libre3ResultRangeStatus,
    pub actionable_status: u8,
    pub actionability: Libre3ActionableStatus,
    pub temperature_status: u16,
    pub current_glucose: Libre3GlucoseValueStatus,
    pub historical_glucose: Libre3GlucoseValueStatus,
    pub rate_of_change_available: bool,
    pub trend: Libre3Trend,
    pub trend_bits: u8,
    pub status_bits: u8,
    pub temperature_raw: u16,
    pub fast_data: Vec<u8>,
    pub fast_data_words_le: [u16; 4],
}

/// Port of kit `Libre3GlucoseQualityAssessment` (RealtimeGlucoseReading.swift L657-672).
#[derive(Debug, Clone, PartialEq)]
pub struct Libre3GlucoseQualityAssessment {
    pub is_usable: bool,
    pub issues: Vec<Libre3GlucoseQualityIssue>,
    pub evidence: RealtimeGlucoseQualityEvidence,
}

impl Libre3GlucoseQualityAssessment {
    /// Kit `blockingIssues` (L663-665): issues that suppress the reading.
    pub fn blocking_issues(&self) -> Vec<Libre3GlucoseQualityIssue> {
        self.issues.iter().filter(|i| i.is_usability_blocking()).copied().collect()
    }

    /// Kit `advisories` (L669-671): surfaced but non-suppressing (currently not-actionable).
    pub fn advisories(&self) -> Vec<Libre3GlucoseQualityIssue> {
        self.issues.iter().filter(|i| i.is_advisory()).copied().collect()
    }
}
