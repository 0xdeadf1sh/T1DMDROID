//! uniffi face of `t1dm-watch` (T1DMCOMMON SPEC/watch.md); the crate itself carries no uniffi.

use std::sync::Arc;

use t1dm_watch::frames::{Control, Kex, Status};
use t1dm_watch::records::{
    Display, Forecast, Glance, GlanceStatus, History, Palette, Provenance, Record, Sample, Stats, StatsWindow,
};
use t1dm_watch::WatchError;

use crate::CoreError;

impl From<WatchError> for CoreError {
    fn from(e: WatchError) -> Self {
        match e {
            WatchError::Decode(reason) => CoreError::Decode { reason },
            WatchError::Internal(reason) => CoreError::Internal { reason },
        }
    }
}

fn narrow<T: TryFrom<i64>>(x: impl Into<i64>, what: &str) -> Result<T, CoreError> {
    let x = x.into();
    T::try_from(x).map_err(|_| CoreError::Decode { reason: format!("{what}: {x} out of range") })
}

/// One per pairing; `Arc`-shared, internally locked.
#[derive(uniffi::Object)]
pub struct WatchSession {
    inner: t1dm_watch::WatchSession,
}

#[uniffi::export]
impl WatchSession {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { inner: t1dm_watch::WatchSession::new() })
    }

    /// Restore and BURN the send window: jumps to ceiling until `export_state` checkpoints.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        Ok(Arc::new(Self { inner: t1dm_watch::WatchSession::restore(&state)? }))
    }

    pub fn public_key(&self) -> Result<Vec<u8>, CoreError> {
        Ok(self.inner.public_key()?.to_vec())
    }

    pub fn epoch(&self) -> Result<u32, CoreError> {
        Ok(self.inner.epoch()?)
    }

    pub fn is_established(&self) -> Result<bool, CoreError> {
        Ok(self.inner.is_established()?)
    }

    pub fn send_seq(&self) -> Result<u64, CoreError> {
        Ok(self.inner.send_seq()?)
    }

    pub fn recv_min(&self) -> Result<u64, CoreError> {
        Ok(self.inner.recv_min()?)
    }

    pub fn accept_peer(&self, peer_public: Vec<u8>) -> Result<(), CoreError> {
        Ok(self.inner.accept_peer(&peer_public)?)
    }

    pub fn sas(&self) -> Result<String, CoreError> {
        Ok(self.inner.sas()?)
    }

    pub fn seal(&self, plaintext: Vec<u8>, aad: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        Ok(self.inner.seal(&plaintext, &aad)?)
    }

    pub fn open(&self, frame: Vec<u8>, aad: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        Ok(self.inner.open(&frame, &aad)?)
    }

    pub fn rotate(&self) -> Result<u32, CoreError> {
        Ok(self.inner.rotate()?)
    }

    pub fn reset(&self) -> Result<(), CoreError> {
        Ok(self.inner.reset()?)
    }

    pub fn export_state(&self) -> Result<Vec<u8>, CoreError> {
        Ok(self.inner.export_state()?)
    }
}

#[uniffi::export]
pub fn watch_sas(a: Vec<u8>, b: Vec<u8>) -> Result<String, CoreError> {
    Ok(t1dm_watch::watch_sas(&a, &b)?)
}

/// §5.3; an enum ordinal outside its wire range reads as none.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WatchGlanceIn {
    pub low_power: bool,
    pub stale: bool,
    pub signal_loss: bool,
    pub warmup: bool,
    pub predicted_low: bool,
    pub predicted_high: bool,
    pub alarm: bool,
    pub forecast_unavailable: bool,
    pub bg_mgdl: Option<i32>,
    pub trend_tenths: Option<i32>,
    pub alert_band: Option<i32>,
    pub forecast_status: Option<i32>,
    pub fc_end_mgdl: Option<i32>,
    pub fc_horizon_steps: i32,
    pub fc_trend: i32,
    pub reading_age_ms: i64,
    pub bg_trend: Option<i32>,
    pub bg_trend_fitted: bool,
    pub summary: String,
}

#[uniffi::export]
pub fn watch_encode_glance(g: WatchGlanceIn) -> Vec<u8> {
    let i16_of = |x: i32| x.clamp(0, i16::MAX as i32) as i16;
    let ordinal = |x: Option<i32>| x.and_then(|v| u8::try_from(v).ok()).filter(|&v| v < 0xFF);
    Glance {
        status: GlanceStatus {
            low_power: g.low_power,
            stale: g.stale,
            signal_loss: g.signal_loss,
            warmup: g.warmup,
            predicted_low: g.predicted_low,
            predicted_high: g.predicted_high,
            alarm: g.alarm,
            forecast_unavailable: g.forecast_unavailable,
        },
        bg_mgdl: g.bg_mgdl.map(i16_of),
        trend_tenths: g.trend_tenths.map(|t| t.clamp(i16::MIN as i32 + 1, i16::MAX as i32) as i16),
        alert_band: ordinal(g.alert_band),
        forecast_status: ordinal(g.forecast_status),
        fc_end_mgdl: g.fc_end_mgdl.map(i16_of),
        fc_horizon_steps: g.fc_horizon_steps.clamp(0, u8::MAX as i32) as u8,
        fc_trend: g.fc_trend.clamp(0, u8::MAX as i32) as u8,
        reading_age_s: (g.reading_age_ms / 1000).clamp(0, u32::MAX as i64) as u32,
        bg_trend: ordinal(g.bg_trend).filter(|&v| v < 0x80),
        bg_trend_fitted: g.bg_trend_fitted,
        summary: g.summary,
    }
    .encode()
}

/// §5.4: slot i at `start_ts_ms + i·300000`; `mgdl < 0` is empty; provenance 0..3.
#[uniffi::export]
pub fn watch_encode_history(
    start_ts_ms: i64,
    mgdl: Vec<i32>,
    provenance: Vec<i32>,
) -> Result<Vec<Vec<u8>>, CoreError> {
    if mgdl.len() != provenance.len() {
        return Err(CoreError::Decode { reason: format!("history: {} values, {} provenances", mgdl.len(), provenance.len()) });
    }
    let slots = mgdl
        .iter()
        .zip(&provenance)
        .map(|(&v, &p)| {
            if v < 0 {
                return Ok(None);
            }
            Ok(Some(Sample { mgdl: narrow(v, "history mg/dL")?, provenance: Provenance::from_code(narrow(p, "provenance")?)? }))
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    Ok(History::encode(start_ts_ms, &slots)?)
}

/// §5.5; empty `median` withdraws the forecast. `fan` is step-major, `levels` per step.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WatchForecastIn {
    pub anchor_ts_ms: i64,
    pub anchor_mgdl: f64,
    pub forecast_status: i32,
    pub stale: bool,
    pub calibrated: bool,
    pub step_min: i32,
    pub levels: i32,
    pub median: Vec<f64>,
    pub fan: Vec<f64>,
}

#[uniffi::export]
pub fn watch_encode_forecast(f: WatchForecastIn) -> Result<Vec<Vec<u8>>, CoreError> {
    Ok(Forecast {
        anchor_ts: f.anchor_ts_ms,
        anchor_mgdl: f.anchor_mgdl,
        forecast_status: narrow(f.forecast_status, "forecast status")?,
        stale: f.stale,
        calibrated: f.calibrated,
        step_min: narrow(f.step_min, "step_min")?,
        levels: narrow(f.levels, "levels")?,
        median: f.median,
        fan: f.fan,
    }
    .encode()?)
}

/// §5.6; band fractions 0..1, CV and GMI in percent, as `AdvancedStats` carries them.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WatchStatsWindowIn {
    pub days: i32,
    pub n_samples: i32,
    pub very_low: f64,
    pub low: f64,
    pub in_range: f64,
    pub high: f64,
    pub very_high: f64,
    pub mean_mgdl: f64,
    pub sd_mgdl: f64,
    pub cv_pct: f64,
    pub gmi_pct: f64,
}

#[uniffi::export]
pub fn watch_encode_stats(
    target_low: i32,
    target_high: i32,
    windows: Vec<WatchStatsWindowIn>,
) -> Result<Vec<u8>, CoreError> {
    let windows = windows
        .into_iter()
        .map(|w| {
            Ok(StatsWindow::from_stats(
                narrow(w.days, "days")?,
                w.n_samples.max(0) as u32,
                [w.very_low, w.low, w.in_range, w.high, w.very_high],
                w.mean_mgdl,
                w.sd_mgdl,
                w.cv_pct,
                w.gmi_pct,
            ))
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    Ok(Stats { target_low: narrow(target_low, "target_low")?, target_high: narrow(target_high, "target_high")?, windows }
        .encode()?)
}

/// §5.7 roles, ARGB.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WatchPaletteIn {
    pub background: i32,
    pub surface: i32,
    pub surface_variant: i32,
    pub primary: i32,
    pub on_primary: i32,
    pub secondary: i32,
    pub on_secondary: i32,
    pub ink: i32,
    pub ink_muted: i32,
    pub grid: i32,
    pub urgent_low: i32,
    pub low: i32,
    pub in_range: i32,
    pub high: i32,
    pub urgent_high: i32,
}

/// §5.7; `thresholds` are 4 mg/dL values.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WatchDisplayIn {
    pub dark: bool,
    pub palette: WatchPaletteIn,
    pub thresholds: Vec<i32>,
    pub range_min: i32,
    pub range_max: i32,
    pub window_h: i32,
    pub stale_min: i32,
    pub loss_min: i32,
    pub name: String,
}

#[uniffi::export]
pub fn watch_encode_display(d: WatchDisplayIn) -> Result<Vec<u8>, CoreError> {
    let p = &d.palette;
    let palette = Palette {
        background: p.background as u32,
        surface: p.surface as u32,
        surface_variant: p.surface_variant as u32,
        primary: p.primary as u32,
        on_primary: p.on_primary as u32,
        secondary: p.secondary as u32,
        on_secondary: p.on_secondary as u32,
        ink: p.ink as u32,
        ink_muted: p.ink_muted as u32,
        grid: p.grid as u32,
        urgent_low: p.urgent_low as u32,
        low: p.low as u32,
        in_range: p.in_range as u32,
        high: p.high as u32,
        urgent_high: p.urgent_high as u32,
    };
    let thresholds: [u16; 4] = d
        .thresholds
        .iter()
        .map(|&t| narrow(t, "threshold"))
        .collect::<Result<Vec<u16>, _>>()?
        .try_into()
        .map_err(|_| CoreError::Decode { reason: format!("display: {} thresholds", d.thresholds.len()) })?;
    Ok(Display {
        dark: d.dark,
        palette,
        thresholds,
        range_min: narrow(d.range_min, "range_min")?,
        range_max: narrow(d.range_max, "range_max")?,
        window_h: narrow(d.window_h, "window_h")?,
        stale_min: narrow(d.stale_min, "stale_min")?,
        loss_min: narrow(d.loss_min, "loss_min")?,
        name: d.name,
    }
    .encode())
}

/// §2 STATUS; `device_id` is 16 lowercase hex digits, `name` what the peripheral advertises.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WatchStatusOut {
    pub epoch: i32,
    pub extended: bool,
    pub device_id: String,
    pub name: String,
}

#[uniffi::export]
pub fn watch_decode_status(bytes: Vec<u8>) -> Result<WatchStatusOut, CoreError> {
    let s = Status::decode(&bytes)?;
    Ok(WatchStatusOut {
        epoch: s.epoch as i32,
        extended: s.extended(),
        device_id: s.device_id.iter().map(|b| format!("{b:02x}")).collect(),
        name: t1dm_watch::frames::advertised_name(&s.device_id),
    })
}

#[uniffi::export]
pub fn watch_encode_hello(epoch: i32, key: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    let public = key
        .as_slice()
        .try_into()
        .map_err(|_| CoreError::Decode { reason: format!("HELLO: key of {} bytes", key.len()) })?;
    Ok(Kex::Hello { epoch: narrow(epoch, "epoch")?, public }.encode())
}

#[uniffi::export]
pub fn watch_encode_confirm(epoch: i32, ok: bool) -> Result<Vec<u8>, CoreError> {
    Ok(Kex::Confirm { epoch: narrow(epoch, "epoch")?, ok }.encode())
}

/// §7: the plaintext the central seals to unpair.
#[uniffi::export]
pub fn watch_encode_unpair() -> Vec<u8> {
    Record::unpair()
}

/// §3, §6.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum WatchControlOut {
    HelloAck { epoch: i32, key: Vec<u8> },
    ConfirmAck { epoch: i32, ok: bool },
    ErrEpoch { epoch: i32 },
    ErrAuth { epoch: i32 },
    PushAck { epoch: i32, seq: i64 },
}

#[uniffi::export]
pub fn watch_decode_control(bytes: Vec<u8>) -> Result<WatchControlOut, CoreError> {
    Ok(match Control::decode(&bytes)? {
        Control::HelloAck { epoch, public } => WatchControlOut::HelloAck { epoch: epoch.into(), key: public.to_vec() },
        Control::ConfirmAck { epoch, ok } => WatchControlOut::ConfirmAck { epoch: epoch.into(), ok },
        Control::ErrEpoch { epoch } => WatchControlOut::ErrEpoch { epoch: epoch.into() },
        Control::ErrAuth { epoch } => WatchControlOut::ErrAuth { epoch: epoch.into() },
        Control::PushAck { epoch, seq } => WatchControlOut::PushAck { epoch: epoch.into(), seq: seq.into() },
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_face_round_trips() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();
        assert_eq!(a.sas().unwrap(), b.sas().unwrap());
        let f = a.seal(b"x".to_vec(), vec![]).unwrap();
        assert_eq!(b.open(f.clone(), vec![]).unwrap(), b"x");
        assert!(matches!(b.open(f, vec![]), Err(CoreError::Decode { .. })), "replay maps to Decode");
        let restored = WatchSession::restore(b.export_state().unwrap()).unwrap();
        assert_eq!(restored.recv_min().unwrap(), 1);
        assert!(WatchSession::restore(vec![]).is_err());
        assert!(watch_sas(vec![0; 3], vec![0; 32]).is_err());
    }

    #[test]
    fn encoders_carry_the_crate_layout() {
        let g = watch_encode_glance(WatchGlanceIn {
            low_power: false,
            stale: false,
            signal_loss: false,
            warmup: false,
            predicted_low: true,
            predicted_high: false,
            alarm: true,
            forecast_unavailable: false,
            bg_mgdl: Some(142),
            trend_tenths: Some(-12),
            alert_band: Some(2),
            forecast_status: Some(0),
            fc_end_mgdl: Some(96),
            fc_horizon_steps: 24,
            fc_trend: 2,
            reading_age_ms: 125_900,
            bg_trend: Some(2),
            bg_trend_fitted: true,
            summary: "falling to ~96 in 2h".into(),
        });
        let golden = "01508e00f4ff0200600018027d000000821466616c6c696e6720746f207e393620696e203268";
        assert_eq!(g.iter().map(|b| format!("{b:02x}")).collect::<String>(), golden);

        let h = watch_encode_history(1_699_999_800_000, vec![120, -1], vec![0, 0]).unwrap();
        assert_eq!(h.len(), 1);
        assert!(watch_encode_history(1_699_999_800_000, vec![120], vec![]).is_err());
        assert!(watch_encode_history(1_699_999_800_000, vec![120], vec![9]).is_err());
        assert!(watch_encode_history(1_699_999_800_001, vec![120], vec![0]).is_err());

        let none = watch_encode_forecast(WatchForecastIn {
            anchor_ts_ms: 0,
            anchor_mgdl: f64::NAN,
            forecast_status: 0,
            stale: false,
            calibrated: false,
            step_min: 5,
            levels: 7,
            median: vec![],
            fan: vec![],
        })
        .unwrap();
        assert_eq!(none.len(), 1);

        let black = -16_777_216;
        let d = WatchDisplayIn {
            dark: true,
            palette: WatchPaletteIn {
                background: black,
                surface: black,
                surface_variant: black,
                primary: black,
                on_primary: black,
                secondary: black,
                on_secondary: black,
                ink: black,
                ink_muted: black,
                grid: black,
                urgent_low: black,
                low: black,
                in_range: black,
                high: black,
                urgent_high: 0x00FF_0000,
            },
            thresholds: vec![55, 70, 180, 250],
            range_min: 20,
            range_max: 250,
            window_h: 6,
            stale_min: 15,
            loss_min: 20,
            name: "x".into(),
        };
        let bytes = watch_encode_display(d.clone()).unwrap();
        assert_eq!(&bytes[2..6], &[0, 0, 0, 0xFF]);
        assert_eq!(&bytes[58..62], &[0, 0, 0xFF, 0], "urgent_high is the last colour");
        assert!(watch_encode_display(WatchDisplayIn { thresholds: vec![1; 3], ..d.clone() }).is_err());
        assert!(watch_encode_display(WatchDisplayIn { window_h: 256, ..d }).is_err());

        let s = watch_decode_status(vec![1, 0, 1, 0xAB, 2, 3, 4, 5, 6, 7, 8]).unwrap();
        assert_eq!((s.device_id.as_str(), s.name.as_str(), s.extended), ("ab02030405060708", "T1DM-Watch-ab020304", true));
        assert!(watch_decode_status(vec![1, 0, 1]).is_err());
    }

    #[test]
    fn frames_carry_the_crate_layout() {
        let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
        assert_eq!(hex(&watch_encode_hello(0, vec![0x11; 32]).unwrap()), format!("010100{}", "11".repeat(32)));
        assert!(watch_encode_hello(0, vec![0x11; 31]).is_err());
        assert!(watch_encode_hello(256, vec![0x11; 32]).is_err());
        assert_eq!(hex(&watch_encode_confirm(0, true).unwrap()), "03010001");
        assert_eq!(watch_encode_unpair(), vec![0x06]);

        let ack = watch_decode_control([vec![0x02, 1, 0], vec![0x22; 32]].concat()).unwrap();
        assert_eq!(ack, WatchControlOut::HelloAck { epoch: 0, key: vec![0x22; 32] });
        assert_eq!(watch_decode_control(vec![0x11, 1, 0]).unwrap(), WatchControlOut::ErrAuth { epoch: 0 });
        assert_eq!(
            watch_decode_control(vec![0x20, 1, 0, 7, 0, 0, 0]).unwrap(),
            WatchControlOut::PushAck { epoch: 0, seq: 7 }
        );
        assert!(watch_decode_control(vec![0x11, 1]).is_err(), "ERR_AUTH needs its epoch byte");
    }
}
