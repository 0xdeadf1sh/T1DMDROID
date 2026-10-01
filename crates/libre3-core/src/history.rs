//! PLAN_T1DMDROID.md §5.7: historical pages, backfill coverage, patchControl plaintext
//! builders, and the clinical-stream record. Port of kit HistoricalReadingPage.swift +
//! HistoricalBackfill.swift + PatchControlCommand.swift + ClinicalReadingRecord.swift
//! (LibreCRKit/Sources/LibreCRKit/DataPlane/). Pure byte-level code; no tables.

use crate::glucose::Libre3GlucoseValueStatus;
use crate::DataPlaneError;

/// Port of kit `HistoricalReadingSample` (HistoricalReadingPage.swift L3-14).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HistoricalReadingSample {
    pub life_count: u16,
    pub raw_value: u16,
}

impl HistoricalReadingSample {
    /// Kit `glucoseMgDL` (L7-9).
    pub fn glucose_mg_dl(&self) -> Option<u16> {
        self.glucose_status().display_mg_dl()
    }

    /// Kit `glucoseStatus` (L11-13).
    pub fn glucose_status(&self) -> Libre3GlucoseValueStatus {
        Libre3GlucoseValueStatus::from_raw_sensor_value(self.raw_value)
    }
}

/// Port of kit `HistoricalReadingPage` (HistoricalReadingPage.swift L16-46): 14-B plaintext,
/// `startLifeCount_LE2 || 6 × value_LE2`, samples at +0,+5,…,+25 life-count minutes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoricalReadingPage {
    pub start_life_count: u16,
    pub values: Vec<u16>,
}

impl HistoricalReadingPage {
    pub const PLAINTEXT_SIZE: usize = 14; // HistoricalReadingPage.swift L17
    /// HistoricalReadingPage.swift L18.
    pub const SAMPLE_SPACING_LIFE_COUNTS: u16 = 5;

    /// Kit `init(plaintext:)` (L23-32).
    pub fn parse(plaintext: &[u8]) -> Result<Self, DataPlaneError> {
        if plaintext.len() != Self::PLAINTEXT_SIZE {
            return Err(DataPlaneError::WrongPlaintextSize { got: plaintext.len() });
        }
        let words: Vec<u16> = (0..plaintext.len() - 1)
            .step_by(2)
            .map(|off| u16::from_le_bytes([plaintext[off], plaintext[off + 1]]))
            .collect();
        Ok(Self {
            start_life_count: words[0],
            values: words[1..].to_vec(),
        })
    }

    /// Kit `samples` (L34-41). Arithmetic wraps: lifeCount is the sensor's own wrapping u16
    /// minute counter, and the kit's trap-on-overflow must not become a panic here (plan §15).
    pub fn samples(&self) -> Vec<HistoricalReadingSample> {
        self.values
            .iter()
            .enumerate()
            .map(|(index, value)| HistoricalReadingSample {
                life_count: self.start_life_count
                    .wrapping_add((index as u16).wrapping_mul(Self::SAMPLE_SPACING_LIFE_COUNTS)),
                raw_value: *value,
            })
            .collect()
    }

    /// Kit `endLifeCount` (L43-45); wrapping like `samples` (plan §15: no panics).
    pub fn end_life_count(&self) -> u16 {
        self.start_life_count
            .wrapping_add(
                (self.values.len() as u16)
                    .wrapping_sub(1)
                    .wrapping_mul(Self::SAMPLE_SPACING_LIFE_COUNTS),
            )
    }
}

/// Port of kit `HistoricalBackfillGap` (HistoricalBackfill.swift L3-11).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HistoricalBackfillGap {
    pub after_life_count: u16,
    pub before_life_count: u16,
}

/// Port of kit `HistoricalBackfill` (HistoricalBackfill.swift L13-53).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HistoricalBackfill {
    pub pages: Vec<HistoricalReadingPage>,
}

impl HistoricalBackfill {
    pub fn new() -> Self {
        Self::default()
    }

    /// Kit `append` (L20-22).
    pub fn append(&mut self, page: HistoricalReadingPage) {
        self.pages.push(page);
    }

    /// Kit `samples` (L24-28): flattened, sorted by lifeCount (stable, matching `sorted`).
    pub fn samples(&self) -> Vec<HistoricalReadingSample> {
        let mut out: Vec<HistoricalReadingSample> =
            self.pages.iter().flat_map(|page| page.samples()).collect();
        out.sort_by_key(|sample| sample.life_count);
        out
    }

    /// Kit `firstLifeCount` (L30-32).
    pub fn first_life_count(&self) -> Option<u16> {
        self.samples().first().map(|sample| sample.life_count)
    }

    /// Kit `lastLifeCount` (L34-36).
    pub fn last_life_count(&self) -> Option<u16> {
        self.samples().last().map(|sample| sample.life_count)
    }

    /// Kit `gaps` (L38-48): a jump of more than one 5-minute spacing is a gap.
    pub fn gaps(&self) -> Vec<HistoricalBackfillGap> {
        let ordered = self.samples();
        if ordered.len() <= 1 {
            return Vec::new();
        }
        ordered
            .windows(2)
            .filter_map(|pair| {
                let (previous, next) = (pair[0], pair[1]);
                (next.life_count
                    > previous.life_count.saturating_add(
                        HistoricalReadingPage::SAMPLE_SPACING_LIFE_COUNTS,
                    ))
                .then_some(HistoricalBackfillGap {
                    after_life_count: previous.life_count,
                    before_life_count: next.life_count,
                })
            })
            .collect()
    }

    /// Kit `isContiguous` (L50-52).
    pub fn is_contiguous(&self) -> bool {
        self.gaps().is_empty()
    }
}

/// Port of kit `BackfillStream` (HistoricalBackfill.swift L55-76).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackfillStream {
    Historical,
    Clinical,
}

impl BackfillStream {
    /// Kit `wireValue` (L59-66).
    pub fn wire_value(self) -> u8 {
        match self {
            Self::Historical => 0x00,
            Self::Clinical => 0x01,
        }
    }

    /// Kit `label` (L68-75).
    pub fn label(self) -> &'static str {
        match self {
            Self::Historical => "historic",
            Self::Clinical => "clinical",
        }
    }
}

/// Port of kit `PatchControlCommand` (PatchControlCommand.swift L8-105): plaintext builders
/// for patchControl writes, exactly 7 bytes before the data-plane CCM wrapper.
///
/// Kit `shutdownPatch` (`05 00 00 00 00 00 00`) is deliberately NOT ported: plan §5.7 marks it
/// terminal — never sent by the client.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PatchControlCommand {
    pub label: String,
    pub plaintext: Vec<u8>,
}

impl PatchControlCommand {
    pub const PLAINTEXT_SIZE: usize = 7; // PatchControlCommand.swift L14

    /// Kit `init(label:plaintext:)` (L12-18): rejects anything that is not 7 bytes.
    pub fn new(label: impl Into<String>, plaintext: Vec<u8>) -> Result<Self, DataPlaneError> {
        if plaintext.len() != Self::PLAINTEXT_SIZE {
            return Err(DataPlaneError::WrongPlaintextSize { got: plaintext.len() });
        }
        Ok(Self { label: label.into(), plaintext })
    }

    /// Kit `historicalBackfillGreaterEqual` (L20-25), selector default 0x01.
    pub fn historical_backfill_greater_equal(
        life_count: u16,
        selector: u8,
    ) -> Result<Self, DataPlaneError> {
        Self::backfill_greater_equal(BackfillStream::Historical, life_count, selector)
    }

    /// Kit `clinicalBackfillGreaterEqual` (L27-32), selector default 0x01.
    pub fn clinical_backfill_greater_equal(
        life_count: u16,
        selector: u8,
    ) -> Result<Self, DataPlaneError> {
        Self::backfill_greater_equal(BackfillStream::Clinical, life_count, selector)
    }

    /// Kit `backfillGreaterEqual(stream:lifeCount:selector:)` (L34-47); ACKed, never paged.
    pub fn backfill_greater_equal(
        stream: BackfillStream,
        life_count: u16,
        selector: u8,
    ) -> Result<Self, DataPlaneError> {
        Self::new(
            format!("{} >= {life_count}", stream.label()),
            vec![0x01, stream.wire_value(), selector, life_count as u8, (life_count >> 8) as u8, 0x00, 0x00],
        )
    }

    /// Kit `makeRangeCommand` (L82-104), aux = end: the only form paged (live 2026-09-24).
    pub fn backfill_range(
        stream: BackfillStream,
        start_life_count: u16,
        end_life_count: u16,
        selector: u8,
    ) -> Result<Self, DataPlaneError> {
        Self::new(
            format!("{} in [{start_life_count}, {end_life_count}]", stream.label()),
            vec![
                0x01,
                stream.wire_value(),
                selector,
                start_life_count as u8,
                (start_life_count >> 8) as u8,
                end_life_count as u8,
                (end_life_count >> 8) as u8,
            ],
        )
    }

    /// Bounded-range twin of [Self::historical_backfill_greater_equal].
    pub fn historical_backfill_range(
        start_life_count: u16,
        end_life_count: u16,
        selector: u8,
    ) -> Result<Self, DataPlaneError> {
        Self::backfill_range(BackfillStream::Historical, start_life_count, end_life_count, selector)
    }

    /// Bounded-range twin of [Self::clinical_backfill_greater_equal].
    pub fn clinical_backfill_range(
        start_life_count: u16,
        end_life_count: u16,
        selector: u8,
    ) -> Result<Self, DataPlaneError> {
        Self::backfill_range(BackfillStream::Clinical, start_life_count, end_life_count, selector)
    }

    /// Kit `eventLog(index:)` (L49-55).
    pub fn event_log(index: u8) -> Result<Self, DataPlaneError> {
        Self::new(
            format!("event log >= {index}"),
            vec![0x04, index, 0x00, 0x00, 0x00, 0x00, 0x00],
        )
    }

    /// Kit `factoryData()` (L57-63).
    pub fn factory_data() -> Result<Self, DataPlaneError> {
        Self::new("factory data", vec![0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
    }
}

/// Port of kit `ClinicalReadingRecord` (ClinicalReadingRecord.swift L55-127): a 14-B single
/// time-point record (NOT six samples at 5-min stride). Word semantics per the kit table.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ClinicalReadingRecord {
    /// LifeCount of this clinical record (per-minute granularity).
    pub life_count: u16,
    /// Raw sensor channels (words 1–3); word 3's high byte is pinned 0x0e (suspected temp).
    pub raw_sensor_word1: u16,
    pub raw_sensor_word2: u16,
    pub raw_sensor_word3: u16,
    /// Word 4 — only 0x0000 observed.
    pub reserved_word: u16,
    /// Current-minute glucose, keyed at `lifeCount` (equals realtime current).
    pub current_glucose_raw: u16,
    /// Most recent 5-min committed glucose (equals the realtime embedded historical).
    pub historic_glucose_raw: u16,
}

impl ClinicalReadingRecord {
    pub const PLAINTEXT_SIZE: usize = 14; // ClinicalReadingRecord.swift L56

    /// Kit `init(plaintext:)` (ClinicalReadingRecord.swift L81-95).
    pub fn parse(plaintext: &[u8]) -> Result<Self, DataPlaneError> {
        if plaintext.len() != Self::PLAINTEXT_SIZE {
            return Err(DataPlaneError::WrongPlaintextSize { got: plaintext.len() });
        }
        let u16le =
            |off: usize| u16::from_le_bytes([plaintext[off], plaintext[off + 1]]);
        Ok(Self {
            life_count: u16le(0),
            raw_sensor_word1: u16le(2),
            raw_sensor_word2: u16le(4),
            raw_sensor_word3: u16le(6),
            reserved_word: u16le(8),
            current_glucose_raw: u16le(10),
            historic_glucose_raw: u16le(12),
        })
    }

    /// Kit `currentGlucose` (L97-99).
    pub fn current_glucose(&self) -> Libre3GlucoseValueStatus {
        Libre3GlucoseValueStatus::from_raw_sensor_value(self.current_glucose_raw)
    }

    /// Kit `currentGlucoseMgDL` (L101-103).
    pub fn current_glucose_mg_dl(&self) -> Option<u16> {
        self.current_glucose().display_mg_dl()
    }

    /// Kit `historicGlucose` (L105-107).
    pub fn historic_glucose(&self) -> Libre3GlucoseValueStatus {
        Libre3GlucoseValueStatus::from_raw_sensor_value(self.historic_glucose_raw)
    }

    /// Kit `historicGlucoseMgDL` (L109-111).
    pub fn historic_glucose_mg_dl(&self) -> Option<u16> {
        self.historic_glucose().display_mg_dl()
    }

    /// Kit `historicLifeCountEstimate` (L122-126): snap `lifeCount − 17` (the kit's 17-minute
    /// historic latency) down to the 5-minute boundary; `None` on underflow (very early life).
    /// Prefer the realtime frame's own `historicalLifeCount` when both are in hand.
    pub fn historic_life_count_estimate(&self) -> Option<u16> {
        let lagged = i32::from(self.life_count) - 17;
        if lagged < 0 {
            return None;
        }
        Some((lagged - lagged % 5) as u16)
    }
}
