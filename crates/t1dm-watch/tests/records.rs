use serde_json::{json, Value};
use t1dm_watch::frames::{
    advertised_name, Control, Kex, Status, CONTROL_UUID, FLAG_EXTENDED, KEX_UUID, PUSH_UUID, SERVICE_UUID,
    STATUS_UUID,
};
use t1dm_watch::records::*;

const TS: i64 = 1_699_999_800_000;

fn tohex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn golden() -> Value {
    serde_json::from_str(include_str!("../testdata/records_golden.json")).unwrap()
}

fn glance() -> Glance {
    Glance {
        status: GlanceStatus { predicted_low: true, alarm: true, ..Default::default() },
        bg_mgdl: Some(142),
        trend_tenths: Some(-12),
        alert_band: Some(2),
        forecast_status: Some(0),
        fc_end_mgdl: Some(96),
        fc_horizon_steps: 24,
        fc_trend: 2,
        reading_age_s: 125,
        bg_trend: Some(2),
        bg_trend_fitted: true,
        summary: "falling to ~96 in 2h".into(),
    }
}

fn history_slots() -> Vec<Option<Sample>> {
    vec![
        Some(Sample { mgdl: 120, provenance: Provenance::Measured }),
        None,
        Some(Sample { mgdl: 4095, provenance: Provenance::Warmup }),
        Some(Sample { mgdl: 90, provenance: Provenance::Interpolated }),
        Some(Sample { mgdl: 0, provenance: Provenance::Reconstructed }),
    ]
}

fn forecast() -> Forecast {
    let h = 13;
    let offs = [-30.0, -20.0, -10.0, 0.0, 10.0, 20.0, 30.0];
    let median: Vec<f64> = (0..h).map(|k| 120.4 + k as f64).collect();
    let mut fan: Vec<f64> = median.iter().flat_map(|m| offs.map(|o| m + o)).collect();
    fan[5] = f64::NAN;
    Forecast {
        anchor_ts: TS,
        anchor_mgdl: 118.0,
        forecast_status: 0,
        stale: false,
        calibrated: true,
        step_min: 5,
        levels: 7,
        median,
        fan,
    }
}

fn stats() -> Stats {
    Stats {
        target_low: 70,
        target_high: 180,
        windows: vec![
            StatsWindow::from_stats(7, 2016, [0.01, 0.04, 0.70, 0.20, 0.05], 150.26, 50.04, 33.3, 6.904),
            StatsWindow::from_stats(30, 0, [0.0; 5], 0.0, 0.0, 0.0, 0.0),
        ],
    }
}

fn display() -> Display {
    Display {
        dark: true,
        palette: Palette {
            background: 0xFF060A12,
            surface: 0xFF0E1626,
            surface_variant: 0xFF152134,
            primary: 0xFF00E5FF,
            on_primary: 0xFF04121A,
            secondary: 0xFFFFB300,
            on_secondary: 0xFF1A1200,
            ink: 0xFFDCEAF5,
            ink_muted: 0xFF8FA9BE,
            grid: 0xFF1E3350,
            urgent_low: 0xFFFF2D55,
            low: 0xFFFFC142,
            in_range: 0xFF00E5A8,
            high: 0xFFFF9E2C,
            urgent_high: 0xFFFF3B30,
        },
        thresholds: [55, 70, 180, 250],
        range_min: 20,
        range_max: 250,
        window_h: 6,
        stale_min: 15,
        loss_min: 20,
        name: "Tron Legacy".into(),
    }
}

fn status() -> Status {
    Status { proto: 1, epoch: 0, flags: FLAG_EXTENDED, device_id: [1, 2, 3, 4, 5, 6, 7, 8] }
}

fn kex() -> [Kex; 2] {
    [Kex::Hello { epoch: 0, public: [0x11; 32] }, Kex::Confirm { epoch: 0, ok: true }]
}

fn uuid(u: u128) -> String {
    let h = format!("{u:032x}");
    format!("{}-{}-{}-{}-{}", &h[..8], &h[8..12], &h[12..16], &h[16..20], &h[20..])
}

fn control() -> [Control; 5] {
    [
        Control::HelloAck { epoch: 0, public: [0x22; 32] },
        Control::ConfirmAck { epoch: 0, ok: true },
        Control::ErrEpoch { epoch: 3 },
        Control::ErrAuth { epoch: 0 },
        Control::PushAck { epoch: 0, seq: 7 },
    ]
}

fn encoded() -> Value {
    let hexes = |v: Vec<Vec<u8>>| v.iter().map(|b| tohex(b)).collect::<Vec<_>>();
    let gatt = [SERVICE_UUID, KEX_UUID, CONTROL_UUID, PUSH_UUID, STATUS_UUID].map(uuid);
    json!({
        "glance": tohex(&glance().encode()),
        "history": hexes(History::encode(TS, &history_slots()).unwrap()),
        "forecast": hexes(forecast().encode().unwrap()),
        "stats": tohex(&stats().encode().unwrap()),
        "display": tohex(&display().encode()),
        "status": tohex(&status().encode()),
        "status_name": advertised_name(&status().device_id),
        "kex": kex().iter().map(|k| tohex(&k.encode())).collect::<Vec<_>>(),
        "control": control().iter().map(|c| tohex(&c.encode())).collect::<Vec<_>>(),
        "unpair": tohex(&Record::unpair()),
        "gatt": gatt,
    })
}

#[test]
#[ignore = "prints the vectors records_golden.json pins"]
fn emit_golden() {
    println!("{}", serde_json::to_string_pretty(&encoded()).unwrap());
}

#[test]
fn encodings_match_golden() {
    let g = golden();
    let e = encoded();
    for k in e.as_object().unwrap().keys() {
        assert_eq!(e[k], g[k], "{k}");
    }
}

#[test]
fn records_round_trip() {
    let g = glance();
    assert_eq!(Glance::decode(&g.encode()).unwrap(), g);
    assert_eq!(Record::decode(&g.encode()).unwrap(), Record::Glance(g));
    assert_eq!(glance().encode()[16], 0x82);
    let reported = Glance { bg_trend_fitted: false, ..glance() };
    assert_eq!(reported.encode()[16], 0x02);
    assert_eq!(Glance::decode(&reported.encode()).unwrap(), reported);
    let none = Glance { bg_trend: None, bg_trend_fitted: false, ..glance() };
    assert_eq!(none.encode()[16], 0xFF);
    assert_eq!(Glance::decode(&none.encode()).unwrap(), none);

    let recs = History::encode(TS, &history_slots()).unwrap();
    assert_eq!(recs.len(), 1);
    let h = History::decode(&recs[0]).unwrap();
    assert_eq!((h.start_ts, h.slots), (TS, history_slots()));

    let s = stats();
    assert_eq!(Stats::decode(&s.encode().unwrap()).unwrap(), s);
    assert_eq!(s.windows[0].bands_permille, [10, 40, 700, 200, 50]);
    assert_eq!(
        (s.windows[0].mean_tenths, s.windows[0].sd_tenths, s.windows[0].cv_permille, s.windows[0].gmi_hundredths),
        (1503, 500, 333, 690)
    );
    assert_eq!(s.windows[1].n_samples, 0);

    let d = display();
    assert_eq!(Display::decode(&d.encode()).unwrap(), d);
    assert_eq!(Record::decode(&Record::unpair()).unwrap(), Record::Unpair);

    let st = status();
    assert_eq!(Status::decode(&st.encode()).unwrap(), st);
    assert!(st.extended());
    assert_eq!(advertised_name(&st.device_id), "T1DM-Watch-01020304");

    for k in kex() {
        assert_eq!(Kex::decode(&k.encode()).unwrap(), k);
    }
    for c in control() {
        assert_eq!(Control::decode(&c.encode()).unwrap(), c);
    }
}

#[test]
fn forecast_parts_assemble() {
    let f = forecast();
    let parts = f.encode().unwrap();
    assert_eq!(parts.len(), 2, "13 steps at 7 levels: 12 + 1");
    assert!(parts.iter().all(|p| p.len() <= RECORD_MAX));

    let mut asm = ForecastAssembler::default();
    let p1 = ForecastPart::decode(&parts[1]).unwrap();
    assert!(asm.push(p1).is_none(), "out-of-order part alone is incomplete");
    let set = asm.push(ForecastPart::decode(&parts[0]).unwrap()).unwrap();
    assert_eq!(set.steps.len(), 13);
    assert!(set.calibrated && !set.stale);
    assert_eq!(set.anchor_mgdl, Some(118));
    assert_eq!(set.steps[0], vec![Some(120), Some(90), Some(100), Some(110), Some(120), Some(130), None, Some(150)]);
    assert_eq!(set.steps[12][0], Some(132));

    let other = Forecast { anchor_ts: TS + 300_000, ..forecast() }.encode().unwrap();
    assert!(asm.push(ForecastPart::decode(&parts[0]).unwrap()).is_none());
    assert!(asm.push(ForecastPart::decode(&other[1]).unwrap()).is_none(), "a new anchor restarts");
    assert!(asm.push(ForecastPart::decode(&other[0]).unwrap()).is_some());

    let none = Forecast { median: vec![], fan: vec![], ..forecast() }.encode().unwrap();
    assert_eq!(none.len(), 1);
    let set = asm.push(ForecastPart::decode(&none[0]).unwrap()).unwrap();
    assert!(set.steps.is_empty(), "H = 0 withdraws the forecast");
}

#[test]
fn history_splits_a_day() {
    let slots: Vec<Option<Sample>> =
        (0..288).map(|i| (i % 7 != 0).then_some(Sample { mgdl: 100 + i as u16, provenance: Provenance::Measured })).collect();
    let recs = History::encode(TS, &slots).unwrap();
    assert_eq!(recs.len(), 3);
    assert!(recs.iter().all(|r| r.len() <= RECORD_MAX));
    let mut back = Vec::new();
    for (i, r) in recs.iter().enumerate() {
        let h = History::decode(r).unwrap();
        assert_eq!(h.start_ts, TS + (i * HISTORY_MAX_SLOTS) as i64 * GRID_MS);
        back.extend(h.slots);
    }
    assert_eq!(back, slots);
    assert!(History::encode(TS, &[]).unwrap().is_empty());
}

#[test]
fn encoders_refuse_what_the_layout_cannot_carry() {
    assert!(History::encode(TS + 1, &history_slots()).is_err(), "off-grid start");
    assert!(History::encode(TS, &[Some(Sample { mgdl: 4096, provenance: Provenance::Measured })]).is_err());
    assert!(Forecast { fan: vec![1.0], ..forecast() }.encode().is_err());
    assert!(Forecast { levels: 0, ..forecast() }.encode().is_err());
    assert!(Forecast { step_min: 0, ..forecast() }.encode().is_err());
    let four = Stats { windows: vec![stats().windows[0]; 4], ..stats() };
    assert!(four.encode().is_err());

    let long = Glance { summary: "é".repeat(30), ..glance() };
    let back = Glance::decode(&long.encode()).unwrap();
    assert_eq!(back.summary, "é".repeat(20), "truncated at a char boundary, ≤ 40 bytes");
    let nan = StatsWindow::from_stats(7, 10, [f64::NAN, 0.0, 1.0, 0.0, 0.0], 100.0, 1.0, 1.0, 5.0);
    assert_eq!(nan.n_samples, 0, "non-finite input withholds the window");
}

#[test]
fn hostile_bytes_never_panic() {
    let valid: Vec<Vec<u8>> = vec![
        glance().encode(),
        History::encode(TS, &history_slots()).unwrap().remove(0),
        forecast().encode().unwrap().remove(0),
        stats().encode().unwrap(),
        display().encode(),
        Record::unpair(),
    ];
    for v in &valid {
        for cut in 0..v.len() {
            assert!(Record::decode(&v[..cut]).is_err(), "truncated at {cut} of {}", v[0]);
        }
        let mut long = v.clone();
        long.push(0);
        assert!(Record::decode(&long).is_err(), "trailing byte, kind {}", v[0]);
        for i in 1..v.len() {
            let mut m = v.clone();
            m[i] ^= 0xFF;
            let _ = Record::decode(&m);
        }
    }
    assert_eq!(Record::decode(&[0x7F, 1, 2]).unwrap(), Record::Unknown(0x7F));
    assert!(Record::decode(&vec![KIND_GLANCE; RECORD_MAX + 1]).is_err());
    assert!(Status::decode(&[1, 0, 0]).is_err());
    assert!(Status::decode(&[2; 11]).is_err(), "wrong proto");
    assert!(Kex::decode(&[1, 1, 0, 5]).is_err(), "short HELLO");
    assert!(Kex::decode(&[6, 1, 0]).is_err(), "unpair is a sealed record, not a KEX frame");
    assert!(Control::decode(&[0x20, 1, 0, 1]).is_err(), "short PUSH_ACK");
    assert!(Control::decode(&[0x04, 2, 0, 1]).is_err(), "wrong proto");
}
