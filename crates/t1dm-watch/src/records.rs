//! Record plaintexts, SPEC/watch.md §5. Encoders refuse input the layout cannot carry.

use crate::{dec, Result};

pub const RECORD_MAX: usize = 215;
pub const GRID_MS: i64 = 300_000;

pub const KIND_GLANCE: u8 = 0x01;
pub const KIND_HISTORY: u8 = 0x02;
pub const KIND_FORECAST: u8 = 0x03;
pub const KIND_STATS: u8 = 0x04;
pub const KIND_DISPLAY: u8 = 0x05;
pub const KIND_UNPAIR: u8 = 0x06;
pub const KIND_OUTLOOK: u8 = 0x07;

const GLANCE_HEAD: usize = 18;
const BG_TREND_FITTED: u8 = 0x80;
pub const SUMMARY_MAX: usize = 40;
const HISTORY_HEAD: usize = 10;
pub const HISTORY_MAX_SLOTS: usize = (RECORD_MAX - HISTORY_HEAD) / 2;
const HISTORY_EMPTY: u16 = 0xFFFF;
const HISTORY_MGDL_MASK: u16 = 0x0FFF;
const FORECAST_HEAD: usize = 18;
const FORECAST_NONE: u16 = 0xFFFF;
const STATS_HEAD: usize = 6;
const STATS_WINDOW: usize = 23;
pub const STATS_MAX_WINDOWS: usize = 3;
pub const PALETTE_LEN: usize = 15;
const DISPLAY_HEAD: usize = 78;
pub const THEME_NAME_MAX: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct GlanceStatus {
    pub low_power: bool,
    pub stale: bool,
    pub signal_loss: bool,
    pub warmup: bool,
    pub predicted_low: bool,
    pub predicted_high: bool,
    pub alarm: bool,
    pub forecast_unavailable: bool,
}

impl GlanceStatus {
    pub fn bits(&self) -> u8 {
        [
            self.low_power,
            self.stale,
            self.signal_loss,
            self.warmup,
            self.predicted_low,
            self.predicted_high,
            self.alarm,
            self.forecast_unavailable,
        ]
        .iter()
        .enumerate()
        .fold(0u8, |acc, (i, &on)| acc | ((on as u8) << i))
    }

    pub fn from_bits(b: u8) -> Self {
        let on = |i: u8| b & (1 << i) != 0;
        GlanceStatus {
            low_power: on(0),
            stale: on(1),
            signal_loss: on(2),
            warmup: on(3),
            predicted_low: on(4),
            predicted_high: on(5),
            alarm: on(6),
            forecast_unavailable: on(7),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Glance {
    pub status: GlanceStatus,
    pub bg_mgdl: Option<i16>,
    /// 0.1 mg/dL/min.
    pub trend_tenths: Option<i16>,
    pub alert_band: Option<u8>,
    pub forecast_status: Option<u8>,
    pub fc_end_mgdl: Option<i16>,
    pub fc_horizon_steps: u8,
    pub fc_trend: u8,
    pub reading_age_s: u32,
    /// The direction beside the reading, in fc_trend's order.
    pub bg_trend: Option<u8>,
    /// The phone fitted [Glance::bg_trend] because the sensor reported no rate.
    pub bg_trend_fitted: bool,
    pub summary: String,
}

impl Glance {
    pub fn encode(&self) -> Vec<u8> {
        let summary = truncate_utf8(&self.summary, SUMMARY_MAX);
        let mut v = Vec::with_capacity(GLANCE_HEAD + summary.len());
        v.push(KIND_GLANCE);
        v.push(self.status.bits());
        v.extend_from_slice(&self.bg_mgdl.map_or(-1, |x| x.max(0)).to_le_bytes());
        v.extend_from_slice(&self.trend_tenths.map_or(i16::MIN, |x| x.max(i16::MIN + 1)).to_le_bytes());
        v.push(self.alert_band.unwrap_or(0xFF));
        v.push(self.forecast_status.unwrap_or(0xFF));
        v.extend_from_slice(&self.fc_end_mgdl.map_or(-1, |x| x.max(0)).to_le_bytes());
        v.push(self.fc_horizon_steps);
        v.push(self.fc_trend);
        v.extend_from_slice(&self.reading_age_s.to_le_bytes());
        let fitted = if self.bg_trend_fitted { BG_TREND_FITTED } else { 0 };
        v.push(self.bg_trend.map_or(0xFF, |d| (d & !BG_TREND_FITTED) | fitted));
        v.push(summary.len() as u8);
        v.extend_from_slice(summary.as_bytes());
        v
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        let mut r = Reader::new(b, KIND_GLANCE)?;
        let status = GlanceStatus::from_bits(r.u8()?);
        let bg = r.i16()?;
        let trend = r.i16()?;
        let band = r.u8()?;
        let fc_status = r.u8()?;
        let fc_end = r.i16()?;
        let fc_horizon_steps = r.u8()?;
        let fc_trend = r.u8()?;
        let reading_age_s = r.u32()?;
        let bg_trend = r.u8()?;
        let n = r.u8()? as usize;
        if n > SUMMARY_MAX {
            return Err(dec(format!("glance: summary {n} > {SUMMARY_MAX}")));
        }
        let summary = String::from_utf8_lossy(r.take(n)?).into_owned();
        r.end()?;
        Ok(Glance {
            status,
            bg_mgdl: (bg >= 0).then_some(bg),
            trend_tenths: (trend != i16::MIN).then_some(trend),
            alert_band: (band != 0xFF).then_some(band),
            forecast_status: (fc_status != 0xFF).then_some(fc_status),
            fc_end_mgdl: (fc_end >= 0).then_some(fc_end),
            fc_horizon_steps,
            fc_trend,
            reading_age_s,
            bg_trend: (bg_trend != 0xFF).then_some(bg_trend & !BG_TREND_FITTED),
            bg_trend_fitted: bg_trend != 0xFF && bg_trend & BG_TREND_FITTED != 0,
            summary,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Provenance {
    Measured = 0,
    Interpolated = 1,
    Reconstructed = 2,
    /// Measured while the sensor was warming up.
    Warmup = 3,
}

impl Provenance {
    pub fn from_code(c: u8) -> Result<Self> {
        match c {
            0 => Ok(Provenance::Measured),
            1 => Ok(Provenance::Interpolated),
            2 => Ok(Provenance::Reconstructed),
            3 => Ok(Provenance::Warmup),
            _ => Err(dec(format!("provenance {c}"))),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Sample {
    pub mgdl: u16,
    pub provenance: Provenance,
}

/// One HISTORY record: slot `i` sits at `start_ts + i·GRID_MS`; `None` is empty.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct History {
    pub start_ts: i64,
    pub slots: Vec<Option<Sample>>,
}

impl History {
    /// Splits `slots` into as many records as it needs; none for an empty span.
    pub fn encode(start_ts: i64, slots: &[Option<Sample>]) -> Result<Vec<Vec<u8>>> {
        check_grid(start_ts)?;
        let mut out = Vec::with_capacity(slots.len().div_ceil(HISTORY_MAX_SLOTS));
        for (i, chunk) in slots.chunks(HISTORY_MAX_SLOTS).enumerate() {
            let ts = start_ts + (i * HISTORY_MAX_SLOTS) as i64 * GRID_MS;
            let mut v = Vec::with_capacity(HISTORY_HEAD + 2 * chunk.len());
            v.push(KIND_HISTORY);
            v.extend_from_slice(&ts.to_le_bytes());
            v.push(chunk.len() as u8);
            for s in chunk {
                let w = match s {
                    None => HISTORY_EMPTY,
                    Some(s) if s.mgdl > HISTORY_MGDL_MASK => {
                        return Err(dec(format!("history: {} mg/dL > {HISTORY_MGDL_MASK}", s.mgdl)))
                    }
                    Some(s) => s.mgdl | ((s.provenance as u16) << 12),
                };
                v.extend_from_slice(&w.to_le_bytes());
            }
            out.push(v);
        }
        Ok(out)
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        let mut r = Reader::new(b, KIND_HISTORY)?;
        let start_ts = r.i64()?;
        check_grid(start_ts)?;
        let n = r.u8()? as usize;
        if n == 0 || n > HISTORY_MAX_SLOTS {
            return Err(dec(format!("history: n = {n}")));
        }
        let mut slots = Vec::with_capacity(n);
        for _ in 0..n {
            let w = r.u16()?;
            slots.push(if w == HISTORY_EMPTY {
                None
            } else if w & 0xC000 != 0 {
                return Err(dec(format!("history: reserved bits in {w:#06x}")));
            } else {
                Some(Sample { mgdl: w & HISTORY_MGDL_MASK, provenance: Provenance::from_code((w >> 12) as u8)? })
            });
        }
        r.end()?;
        Ok(History { start_ts, slots })
    }
}

/// A whole forecast as the central holds it; values mg/dL, `fan` step-major `H×levels`.
#[derive(Debug, Clone, PartialEq)]
pub struct Forecast {
    pub anchor_ts: i64,
    pub anchor_mgdl: f64,
    pub forecast_status: u8,
    pub stale: bool,
    pub calibrated: bool,
    pub step_min: u8,
    pub levels: u8,
    pub median: Vec<f64>,
    pub fan: Vec<f64>,
}

impl Forecast {
    /// Steps one record carries at `levels`.
    pub fn steps_per_record(levels: u8) -> usize {
        (RECORD_MAX - FORECAST_HEAD) / (2 * (1 + levels as usize))
    }

    /// An empty `median` encodes the one record that withdraws the forecast.
    pub fn encode(&self) -> Result<Vec<Vec<u8>>> {
        let h = self.median.len();
        let l = self.levels as usize;
        if h > u8::MAX as usize {
            return Err(dec(format!("forecast: horizon {h}")));
        }
        if l == 0 || Self::steps_per_record(self.levels) == 0 {
            return Err(dec(format!("forecast: {l} levels")));
        }
        if self.fan.len() != h * l {
            return Err(dec(format!("forecast: fan {} != {h}×{l}", self.fan.len())));
        }
        if h > 0 && self.step_min == 0 {
            return Err(dec("forecast: step_min 0"));
        }
        let per = Self::steps_per_record(self.levels);
        let mut out = Vec::with_capacity(h.div_ceil(per).max(1));
        let mut first = 0;
        loop {
            let m = per.min(h - first);
            let mut v = Vec::with_capacity(FORECAST_HEAD + 2 * m * (1 + l));
            v.push(KIND_FORECAST);
            v.extend_from_slice(&self.anchor_ts.to_le_bytes());
            v.extend_from_slice(&mgdl_u16(self.anchor_mgdl).to_le_bytes());
            v.push(self.forecast_status);
            v.push(self.stale as u8 | (self.calibrated as u8) << 1);
            v.extend_from_slice(&[h as u8, self.levels, self.step_min, first as u8, m as u8]);
            for k in first..first + m {
                v.extend_from_slice(&mgdl_u16(self.median[k]).to_le_bytes());
                for q in 0..l {
                    v.extend_from_slice(&mgdl_u16(self.fan[k * l + q]).to_le_bytes());
                }
            }
            out.push(v);
            first += m;
            if first >= h {
                return Ok(out);
            }
        }
    }
}

/// One FORECAST record; each step is `[median, level_0 .. level_{L-1}]`, `None` where non-finite.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForecastPart {
    pub anchor_ts: i64,
    pub anchor_mgdl: Option<u16>,
    pub forecast_status: u8,
    pub stale: bool,
    pub calibrated: bool,
    pub horizon: u8,
    pub levels: u8,
    pub step_min: u8,
    pub first: u8,
    pub steps: Vec<Vec<Option<u16>>>,
}

impl ForecastPart {
    pub fn decode(b: &[u8]) -> Result<Self> {
        let mut r = Reader::new(b, KIND_FORECAST)?;
        let anchor_ts = r.i64()?;
        let anchor = r.u16()?;
        let forecast_status = r.u8()?;
        let flags = r.u8()?;
        let horizon = r.u8()?;
        let levels = r.u8()?;
        let step_min = r.u8()?;
        let first = r.u8()?;
        let m = r.u8()? as usize;
        if flags & !0x03 != 0 {
            return Err(dec(format!("forecast: flags {flags:#04x}")));
        }
        if levels == 0 || first as usize + m > horizon as usize || m > Forecast::steps_per_record(levels) {
            return Err(dec(format!("forecast: H {horizon} L {levels} first {first} m {m}")));
        }
        if horizon > 0 && (m == 0 || step_min == 0) {
            return Err(dec("forecast: empty part of a live forecast"));
        }
        let mut steps = Vec::with_capacity(m);
        for _ in 0..m {
            let mut s = Vec::with_capacity(1 + levels as usize);
            for _ in 0..=levels {
                let v = r.u16()?;
                s.push((v != FORECAST_NONE).then_some(v));
            }
            steps.push(s);
        }
        r.end()?;
        Ok(ForecastPart {
            anchor_ts,
            anchor_mgdl: (anchor != FORECAST_NONE).then_some(anchor),
            forecast_status,
            stale: flags & 1 != 0,
            calibrated: flags & 2 != 0,
            horizon,
            levels,
            step_min,
            first,
            steps,
        })
    }
}

/// A complete forecast; `steps` is empty when the central withdrew it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForecastSet {
    pub anchor_ts: i64,
    pub anchor_mgdl: Option<u16>,
    pub forecast_status: u8,
    pub stale: bool,
    pub calibrated: bool,
    pub levels: u8,
    pub step_min: u8,
    pub steps: Vec<Vec<Option<u16>>>,
}

type PendingForecast = (ForecastPart, Vec<Option<Vec<Option<u16>>>>);

/// Joins parts of one forecast; a part of any other forecast restarts it.
#[derive(Debug, Default)]
pub struct ForecastAssembler {
    pending: Option<PendingForecast>,
}

impl ForecastAssembler {
    pub fn push(&mut self, part: ForecastPart) -> Option<ForecastSet> {
        let key = |p: &ForecastPart| {
            (p.anchor_ts, p.anchor_mgdl, p.horizon, p.levels, p.step_min, p.forecast_status, p.stale, p.calibrated)
        };
        if self.pending.as_ref().map(|(p, _)| key(p)) != Some(key(&part)) {
            self.pending = Some((part.clone(), vec![None; part.horizon as usize]));
        }
        let (_, slots) = self.pending.as_mut()?;
        for (i, s) in part.steps.into_iter().enumerate() {
            slots[part.first as usize + i] = Some(s);
        }
        if slots.iter().any(Option::is_none) {
            return None;
        }
        let (head, slots) = self.pending.take()?;
        Some(ForecastSet {
            anchor_ts: head.anchor_ts,
            anchor_mgdl: head.anchor_mgdl,
            forecast_status: head.forecast_status,
            stale: head.stale,
            calibrated: head.calibrated,
            levels: head.levels,
            step_min: head.step_min,
            steps: slots.into_iter().flatten().collect(),
        })
    }
}

/// One stats window in wire units: bands ‰, mean and SD 0.1 mg/dL, CV ‰, GMI 0.01 %.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StatsWindow {
    pub days: u8,
    pub n_samples: u32,
    /// very_low, low, in_range, high, very_high.
    pub bands_permille: [u16; 5],
    pub mean_tenths: u16,
    pub sd_tenths: u16,
    pub cv_permille: u16,
    pub gmi_hundredths: u16,
}

impl StatsWindow {
    /// `bands` are fractions 0..1, `cv_pct` and `gmi_pct` percent; any non-finite input empties it.
    #[allow(clippy::too_many_arguments)]
    pub fn from_stats(
        days: u8,
        n_samples: u32,
        bands: [f64; 5],
        mean: f64,
        sd: f64,
        cv_pct: f64,
        gmi_pct: f64,
    ) -> Self {
        let finite = bands.iter().chain([mean, sd, cv_pct, gmi_pct].iter()).all(|x| x.is_finite());
        if !finite || n_samples == 0 {
            return StatsWindow {
                days,
                n_samples: 0,
                bands_permille: [0; 5],
                mean_tenths: 0,
                sd_tenths: 0,
                cv_permille: 0,
                gmi_hundredths: 0,
            };
        }
        let q = |x: f64, scale: f64| (x * scale).round().clamp(0.0, u16::MAX as f64) as u16;
        StatsWindow {
            days,
            n_samples,
            bands_permille: bands.map(|b| q(b, 1000.0).min(1000)),
            mean_tenths: q(mean, 10.0),
            sd_tenths: q(sd, 10.0),
            cv_permille: q(cv_pct, 10.0),
            gmi_hundredths: q(gmi_pct, 100.0),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Stats {
    pub target_low: u16,
    pub target_high: u16,
    pub windows: Vec<StatsWindow>,
}

impl Stats {
    pub fn encode(&self) -> Result<Vec<u8>> {
        if self.windows.len() > STATS_MAX_WINDOWS {
            return Err(dec(format!("stats: {} windows", self.windows.len())));
        }
        let mut v = Vec::with_capacity(STATS_HEAD + STATS_WINDOW * self.windows.len());
        v.push(KIND_STATS);
        v.extend_from_slice(&self.target_low.to_le_bytes());
        v.extend_from_slice(&self.target_high.to_le_bytes());
        v.push(self.windows.len() as u8);
        for w in &self.windows {
            v.push(w.days);
            v.extend_from_slice(&w.n_samples.to_le_bytes());
            for b in w.bands_permille {
                v.extend_from_slice(&b.to_le_bytes());
            }
            for x in [w.mean_tenths, w.sd_tenths, w.cv_permille, w.gmi_hundredths] {
                v.extend_from_slice(&x.to_le_bytes());
            }
        }
        Ok(v)
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        let mut r = Reader::new(b, KIND_STATS)?;
        let target_low = r.u16()?;
        let target_high = r.u16()?;
        let k = r.u8()? as usize;
        if k > STATS_MAX_WINDOWS {
            return Err(dec(format!("stats: {k} windows")));
        }
        let mut windows = Vec::with_capacity(k);
        for _ in 0..k {
            let days = r.u8()?;
            let n_samples = r.u32()?;
            let mut bands_permille = [0u16; 5];
            for b in &mut bands_permille {
                *b = r.u16()?;
            }
            windows.push(StatsWindow {
                days,
                n_samples,
                bands_permille,
                mean_tenths: r.u16()?,
                sd_tenths: r.u16()?,
                cv_permille: r.u16()?,
                gmi_hundredths: r.u16()?,
            });
        }
        r.end()?;
        Ok(Stats { target_low, target_high, windows })
    }
}

/// ARGB per role; the wire carries them in field order, SPEC/watch.md §5.7.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Palette {
    pub background: u32,
    pub surface: u32,
    pub surface_variant: u32,
    pub primary: u32,
    pub on_primary: u32,
    pub secondary: u32,
    pub on_secondary: u32,
    pub ink: u32,
    pub ink_muted: u32,
    pub grid: u32,
    pub urgent_low: u32,
    pub low: u32,
    pub in_range: u32,
    pub high: u32,
    pub urgent_high: u32,
}

impl Palette {
    pub fn to_wire(&self) -> [u32; PALETTE_LEN] {
        let p = self;
        [
            p.background, p.surface, p.surface_variant, p.primary, p.on_primary, p.secondary, p.on_secondary,
            p.ink, p.ink_muted, p.grid, p.urgent_low, p.low, p.in_range, p.high, p.urgent_high,
        ]
    }

    pub fn from_wire(c: [u32; PALETTE_LEN]) -> Self {
        let [
            background, surface, surface_variant, primary, on_primary, secondary, on_secondary,
            ink, ink_muted, grid, urgent_low, low, in_range, high, urgent_high,
        ] = c;
        Palette {
            background, surface, surface_variant, primary, on_primary, secondary, on_secondary,
            ink, ink_muted, grid, urgent_low, low, in_range, high, urgent_high,
        }
    }
}

/// `thresholds` urgent_low, low, high, urgent_high.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Display {
    pub dark: bool,
    pub palette: Palette,
    pub thresholds: [u16; 4],
    pub range_min: u16,
    pub range_max: u16,
    pub window_h: u8,
    pub stale_min: u8,
    pub loss_min: u8,
    pub name: String,
}

impl Display {
    pub fn encode(&self) -> Vec<u8> {
        let name = truncate_utf8(&self.name, THEME_NAME_MAX);
        let mut v = Vec::with_capacity(DISPLAY_HEAD + name.len());
        v.push(KIND_DISPLAY);
        v.push(self.dark as u8);
        for c in self.palette.to_wire() {
            v.extend_from_slice(&c.to_le_bytes());
        }
        for t in self.thresholds {
            v.extend_from_slice(&t.to_le_bytes());
        }
        v.extend_from_slice(&self.range_min.to_le_bytes());
        v.extend_from_slice(&self.range_max.to_le_bytes());
        v.extend_from_slice(&[self.window_h, self.stale_min, self.loss_min, name.len() as u8]);
        v.extend_from_slice(name.as_bytes());
        v
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        let mut r = Reader::new(b, KIND_DISPLAY)?;
        let flags = r.u8()?;
        let mut palette = [0u32; PALETTE_LEN];
        for c in &mut palette {
            *c = r.u32()?;
        }
        let mut thresholds = [0u16; 4];
        for t in &mut thresholds {
            *t = r.u16()?;
        }
        let range_min = r.u16()?;
        let range_max = r.u16()?;
        let window_h = r.u8()?;
        let stale_min = r.u8()?;
        let loss_min = r.u8()?;
        let n = r.u8()? as usize;
        if n > THEME_NAME_MAX {
            return Err(dec(format!("display: name {n} > {THEME_NAME_MAX}")));
        }
        let name = String::from_utf8_lossy(r.take(n)?).into_owned();
        r.end()?;
        Ok(Display {
            dark: flags & 1 != 0,
            palette: Palette::from_wire(palette),
            thresholds,
            range_min,
            range_max,
            window_h,
            stale_min,
            loss_min,
            name,
        })
    }
}

/// SPEC/watch.md §5.8; `eta_s` runs from the push to the first forecast step out of range.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outlook {
    Void,
    Stable,
    Unsure,
    Hypo { eta_s: u16 },
    Hyper { eta_s: u16 },
}

impl Outlook {
    pub fn encode(&self) -> Vec<u8> {
        let (state, eta_s) = match *self {
            Outlook::Void => (0, 0),
            Outlook::Stable => (1, 0),
            Outlook::Unsure => (2, 0),
            Outlook::Hypo { eta_s } => (3, eta_s),
            Outlook::Hyper { eta_s } => (4, eta_s),
        };
        let [lo, hi] = eta_s.to_le_bytes();
        vec![KIND_OUTLOOK, state, lo, hi]
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        let mut r = Reader::new(b, KIND_OUTLOOK)?;
        let state = r.u8()?;
        let eta_s = r.u16()?;
        r.end()?;
        Ok(match state {
            1 => Outlook::Stable,
            2 => Outlook::Unsure,
            3 => Outlook::Hypo { eta_s },
            4 => Outlook::Hyper { eta_s },
            _ => Outlook::Void,
        })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Record {
    Glance(Glance),
    History(History),
    Forecast(ForecastPart),
    Stats(Stats),
    Display(Display),
    /// The central dropped this pairing; the peripheral wipes its keys, SPEC/watch.md §7.
    Unpair,
    Outlook(Outlook),
    /// A kind this build does not know; the receiver ignores it.
    Unknown(u8),
}

impl Record {
    pub fn unpair() -> Vec<u8> {
        vec![KIND_UNPAIR]
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        match b.first() {
            None => Err(dec("record: empty")),
            Some(&KIND_UNPAIR) => Reader::new(b, KIND_UNPAIR)?.end().map(|()| Record::Unpair),
            Some(&KIND_GLANCE) => Glance::decode(b).map(Record::Glance),
            Some(&KIND_HISTORY) => History::decode(b).map(Record::History),
            Some(&KIND_FORECAST) => ForecastPart::decode(b).map(Record::Forecast),
            Some(&KIND_STATS) => Stats::decode(b).map(Record::Stats),
            Some(&KIND_DISPLAY) => Display::decode(b).map(Record::Display),
            Some(&KIND_OUTLOOK) => Outlook::decode(b).map(Record::Outlook),
            Some(&k) => Ok(Record::Unknown(k)),
        }
    }
}

fn check_grid(ts: i64) -> Result<()> {
    if ts < 0 || ts % GRID_MS != 0 {
        return Err(dec(format!("timestamp {ts} is off the grid")));
    }
    Ok(())
}

fn mgdl_u16(x: f64) -> u16 {
    if x.is_finite() {
        x.round().clamp(0.0, (FORECAST_NONE - 1) as f64) as u16
    } else {
        FORECAST_NONE
    }
}

fn truncate_utf8(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

struct Reader<'a> {
    b: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn new(b: &'a [u8], kind: u8) -> Result<Self> {
        if b.len() > RECORD_MAX {
            return Err(dec(format!("record: {} bytes > {RECORD_MAX}", b.len())));
        }
        if b.first() != Some(&kind) {
            return Err(dec(format!("record: kind {:?}, want {kind:#04x}", b.first())));
        }
        Ok(Reader { b, at: 1 })
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let s = self
            .b
            .get(self.at..self.at + n)
            .ok_or_else(|| dec(format!("record: short at {}", self.at)))?;
        self.at += n;
        Ok(s)
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn i16(&mut self) -> Result<i16> {
        Ok(i16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }

    fn end(&self) -> Result<()> {
        if self.at != self.b.len() {
            return Err(dec(format!("record: {} trailing bytes", self.b.len() - self.at)));
        }
        Ok(())
    }
}
