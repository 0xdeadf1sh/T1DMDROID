//! PLAN_T1DMDROID.md §12.1: DataPlaneTests.swift ported 1:1 — data-plane framing, descriptor
//! crypto, channel mapping, live-capture decrypts, the realtime/status decoders, lifecycle,
//! patch-control commands, and backfill. Pure byte-level code; no tables, no skips.
//! The kit's shutdownPatch plaintext assertion (`05000000000000`) is omitted: plan §5.7 marks
//! the shutdown command terminal — never sent, not exposed.
//!
//! Session-level extras (marked "session" in comments) drive the uniffi seam
//! (`Libre3DataPlaneSession::feed` / `next_patch_control_frame`) with the same live vectors,
//! covering the assembler latch and the `Libre3DataPlaneState` re-assessment semantics.

use libre3_core::dataplane::{
    DataPlaneChannel, DataPlaneCrypto, DataPlaneDecoder, DataPlaneNotificationAssembler,
    DataPlanePacketKind, DataFrame,
};
use libre3_core::glucose::{
    Libre3ActionableStatus, Libre3DataQualityError, Libre3GlucoseValueStatus, Libre3ResultRangeStatus,
    Libre3SensorCondition, Libre3Trend, RealtimeGlucoseReading,
};
use libre3_core::history::{ClinicalReadingRecord, HistoricalBackfill, HistoricalReadingPage, PatchControlCommand};
use libre3_core::status::{Libre3PatchState, Libre3SensorAttention, Libre3SensorError, PatchStatus, SensorLifecycle};
use libre3_core::{Libre3CapturedChunk, Libre3DataChannel, Libre3DataPlaneSession, Libre3DataPlaneUpdate};

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Kit `settingU16` (DataPlaneTests.swift L12-18).
fn setting_u16(data: &[u8], offset: usize, value: u16) -> Vec<u8> {
    let mut copy = data.to_vec();
    copy[offset] = (value & 0xff) as u8;
    copy[offset + 1] = (value >> 8) as u8;
    copy
}

/// kit `testDataFrameRoundTrip` (DataPlaneTests.swift L20-27).
#[test]
fn data_frame_round_trip() {
    let body = [0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0x07, 0x00];
    let frame = DataFrame::parse(&body).unwrap();
    assert_eq!(frame.encrypted, vec![0xaa, 0xbb, 0xcc, 0xdd, 0xee]);
    assert_eq!(frame.seq, 0x07);
    assert_eq!(frame.type_, 0x00);
    assert_eq!(frame.raw(), body);
}

/// kit `testDataFrameTooShort` (L29-34).
#[test]
fn data_frame_too_short() {
    match DataFrame::parse(&[0x01]) {
        Err(libre3_core::DataPlaneError::TooShort { got: 1 }) => {}
        other => panic!("expected tooShort(1), got {other:?}"),
    }
}

/// kit `testRealtimeReadingFrameStructure` (L36-43): captured 0x0014 notify from fresh_pair
/// (rec 432, 18B body).
#[test]
fn realtime_reading_frame_structure() {
    let body = unhex("9d7476cb096253cc0d492755b1caa0ad0100");
    let frame = DataFrame::parse(&body).unwrap();
    assert_eq!(frame.encrypted.len(), 16, "realtime reading body is 16B encrypted");
    assert_eq!(frame.seq, 0x01);
    assert_eq!(frame.type_, 0x00);
}

/// kit `testDataPlaneChannelMappingPinsLiveDescriptors` (L45-61).
#[test]
fn channel_mapping_pins_live_descriptors() {
    assert_eq!(
        DataPlaneChannel::from_uuid("08981482-EF89-11E9-81B4-2A2AE2DBCCE4"),
        Some(DataPlaneChannel::PatchStatus)
    );
    assert_eq!(
        DataPlaneChannel::from_uuid("0898177A-EF89-11E9-81B4-2A2AE2DBCCE4"),
        Some(DataPlaneChannel::GlucoseData)
    );
    assert_eq!(
        DataPlaneChannel::from_uuid("0898195A-EF89-11E9-81B4-2A2AE2DBCCE4"),
        Some(DataPlaneChannel::HistoricData)
    );
    assert_eq!(DataPlaneChannel::PatchStatus.preferred_inbound_kind(), Some(DataPlanePacketKind::Kind2));
    assert_eq!(DataPlaneChannel::GlucoseData.preferred_inbound_kind(), Some(DataPlanePacketKind::Kind3));
    assert_eq!(DataPlaneChannel::HistoricData.preferred_inbound_kind(), Some(DataPlanePacketKind::Kind4));
}

/// kit `testLiveGlucoseDataFragmentsReassembleAndDecrypt` (L63-90): the live session material
/// (phase 4), a 15-B prefix + 20-B suffix on glucoseData, kind3.
#[test]
fn live_glucose_data_fragments_reassemble_and_decrypt() {
    let crypto = DataPlaneCrypto::new(
        &unhex("7c536d2ec3c76c741a776de85596959d"),
        &unhex("00000000bcc696a2"),
    )
    .unwrap();
    let decoder = DataPlaneDecoder::new(crypto);
    let mut assembler = DataPlaneNotificationAssembler::new();
    let prefix = unhex("3c937752c72e2dfdcfe05a5dce315b");
    let suffix = unhex("6f3785f23dbc85066856d60027c5a41f45c90100");
    assert!(assembler.feed(&prefix, DataPlaneChannel::GlucoseData).is_none());
    let raw_frame = assembler.feed(&suffix, DataPlaneChannel::GlucoseData).unwrap();
    let frame = DataFrame::parse(&raw_frame).unwrap();
    let packet = decoder.decrypt(&frame, DataPlaneChannel::GlucoseData).unwrap();

    assert_eq!(frame.sequence_number(), 0x0001);
    assert_eq!(packet.kind, DataPlanePacketKind::Kind3);
    assert!(packet.used_preferred_kind());
    assert_eq!(
        hex(&packet.plaintext),
        "da386700e3ff0000ac26c73871000b670071001a0abf03f33c59100000"
    );
    match &packet.payload {
        libre3_core::dataplane::DataPlaneDecodedPayload::RealtimeGlucose(reading) => {
            assert_eq!(reading.life_count, 14554);
            assert_eq!(reading.current_glucose_mg_dl, Some(103));
        }
        other => panic!("expected realtime glucose payload, got {other:?}"),
    }
}

/// Session-level rerun of `testLiveGlucoseDataFragmentsReassembleAndDecrypt` through the
/// uniffi seam: `feed` latches the prefix (empty update vec), then decodes the suffix; a
/// whole 35-B frame in one notify also parses (defensive pass-through).
#[test]
fn session_live_glucose_fragments_reassemble_and_decrypt() {
    let session = Libre3DataPlaneSession::new(
        unhex("7c536d2ec3c76c741a776de85596959d"),
        unhex("00000000bcc696a2"),
        60,
        None,
    )
    .unwrap();

    // The latched 15-B prefix emits nothing.
    let updates = session
        .feed(Libre3DataChannel::GlucoseData, unhex("3c937752c72e2dfdcfe05a5dce315b"))
        .unwrap();
    assert!(updates.is_empty());

    let updates = session
        .feed(
            Libre3DataChannel::GlucoseData,
            unhex("6f3785f23dbc85066856d60027c5a41f45c90100"),
        )
        .unwrap();
    assert_eq!(updates.len(), 1);
    match &updates[0] {
        Libre3DataPlaneUpdate::RealtimeGlucose { reading } => {
            assert_eq!(reading.life_count, 14554);
            assert_eq!(reading.current_mgdl, Some(103));
            // No lifecycle yet, clean data: usable with no issues.
            assert!(reading.usable);
            assert!(reading.issues.is_empty());
        }
        other => panic!("expected RealtimeGlucose update, got {other:?}"),
    }

    // Defensive: the same 35-B frame in a single notify passes the assembler untouched.
    let whole = {
        let mut whole = unhex("3c937752c72e2dfdcfe05a5dce315b");
        whole.extend_from_slice(&unhex("6f3785f23dbc85066856d60027c5a41f45c90100"));
        whole
    };
    let updates = session.feed(Libre3DataChannel::GlucoseData, whole).unwrap();
    match &updates[0] {
        Libre3DataPlaneUpdate::RealtimeGlucose { reading } => assert_eq!(reading.life_count, 14554),
        other => panic!("expected RealtimeGlucose update, got {other:?}"),
    }
}

fn live_session() -> Libre3DataPlaneSession {
    Libre3DataPlaneSession::new(
        unhex("7c536d2ec3c76c741a776de85596959d"),
        unhex("00000000bcc696a2"),
        60,
        None,
    )
    .unwrap()
}

const LIVE_GLUCOSE_PREFIX: &str = "3c937752c72e2dfdcfe05a5dce315b";
const LIVE_GLUCOSE_SUFFIX: &str = "6f3785f23dbc85066856d60027c5a41f45c90100";
const LIVE_GLUCOSE_PLAINTEXT: &str = "da386700e3ff0000ac26c73871000b670071001a0abf03f33c59100000";

#[test]
fn open_captured_reassembles_without_touching_the_stream() {
    let session = live_session();
    assert!(session
        .feed(Libre3DataChannel::GlucoseData, unhex(LIVE_GLUCOSE_PREFIX))
        .unwrap()
        .is_empty());

    let chunk = |channel, s: &str| Libre3CapturedChunk { channel, bytes: unhex(s) };
    let opened = session
        .open_captured(vec![
            chunk(Some(Libre3DataChannel::GlucoseData), LIVE_GLUCOSE_PREFIX),
            chunk(Some(Libre3DataChannel::GlucoseData), LIVE_GLUCOSE_SUFFIX),
            chunk(None, &format!("{LIVE_GLUCOSE_PREFIX}{LIVE_GLUCOSE_SUFFIX}")),
            chunk(None, &format!("{LIVE_GLUCOSE_PREFIX}{}", "00".repeat(20))),
            chunk(None, "0102"),
        ])
        .unwrap();
    assert_eq!(opened.len(), 4);
    for (frame, chunk) in opened[..2].iter().zip([1, 2]) {
        assert_eq!(frame.chunk, chunk);
        assert_eq!(frame.sequence, Some(0x0001));
        assert_eq!(frame.kind, Some(DataPlanePacketKind::Kind3 as u8));
        assert_eq!(frame.plaintext.as_deref().map(hex), Some(LIVE_GLUCOSE_PLAINTEXT.to_owned()));
    }
    assert_eq!((opened[2].sequence, opened[2].kind), (Some(0x0000), None));
    assert_eq!((opened[3].sequence, opened[3].plaintext.as_ref()), (None, None));

    // The prefix latched before open_captured still completes on the live stream.
    let updates = session
        .feed(Libre3DataChannel::GlucoseData, unhex(LIVE_GLUCOSE_SUFFIX))
        .unwrap();
    assert!(matches!(&updates[..], [Libre3DataPlaneUpdate::RealtimeGlucose { .. }]));
}

#[test]
fn seal_frame_reproduces_the_live_frame_without_moving_tx() {
    let session = live_session();
    let sealed = session
        .seal_frame(DataPlanePacketKind::Kind3 as u8, 0x0001, unhex(LIVE_GLUCOSE_PLAINTEXT))
        .unwrap();
    assert_eq!(hex(&sealed), format!("{LIVE_GLUCOSE_PREFIX}{LIVE_GLUCOSE_SUFFIX}"));

    assert!(session.seal_frame(8, 1, vec![0]).is_err());

    let control = session.next_patch_control_frame(unhex("01000105000000")).unwrap();
    assert_eq!(DataFrame::parse(&control).unwrap().sequence_number(), 0x0001);
}

/// kit `testLivePatchStatusDecryptsThroughChannelDecoder` (L92-111).
#[test]
fn live_patch_status_decrypts_through_channel_decoder() {
    let crypto = DataPlaneCrypto::new(
        &unhex("7c536d2ec3c76c741a776de85596959d"),
        &unhex("00000000bcc696a2"),
    )
    .unwrap();
    let frame = DataFrame::parse(&unhex("4e6fe9573b6365476193e9c42009acd20100")).unwrap();
    let packet = DataPlaneDecoder::new(crypto)
        .decrypt(&frame, DataPlaneChannel::PatchStatus)
        .unwrap();

    assert_eq!(frame.sequence_number(), 0x0001);
    assert_eq!(packet.kind, DataPlanePacketKind::Kind2);
    assert_eq!(hex(&packet.plaintext), "9d280000e0000704db381607");
    match &packet.payload {
        libre3_core::dataplane::DataPlaneDecodedPayload::PatchStatus(status) => {
            assert_eq!(status.life_count, 10397);
            assert_eq!(status.current_life_count, 14555);
            assert_eq!(status.stack_disconnect_reason, 22);
            assert_eq!(status.app_disconnect_reason, 7);
        }
        other => panic!("expected patch status payload, got {other:?}"),
    }
}

/// kit `testLiveHistoricalDataDecryptsThroughChannelDecoder` (L113-130).
#[test]
fn live_historical_data_decrypts_through_channel_decoder() {
    let crypto = DataPlaneCrypto::new(
        &unhex("7c536d2ec3c76c741a776de85596959d"),
        &unhex("00000000bcc696a2"),
    )
    .unwrap();
    let frame = DataFrame::parse(&unhex("4637aa722274876d69a34f20096c49adced70100")).unwrap();
    let packet = DataPlaneDecoder::new(crypto)
        .decrypt(&frame, DataPlaneChannel::HistoricData)
        .unwrap();

    assert_eq!(frame.sequence_number(), 0x0001);
    assert_eq!(packet.kind, DataPlanePacketKind::Kind4);
    assert_eq!(hex(&packet.plaintext), "98036d006b0069006e006a006500");
    match &packet.payload {
        libre3_core::dataplane::DataPlaneDecodedPayload::HistoricalReadingPage(page) => {
            assert_eq!(page.start_life_count, 920);
            assert_eq!(page.values, vec![109, 107, 105, 110, 106, 101]);
        }
        other => panic!("expected historical page payload, got {other:?}"),
    }
}

/// kit `testRealtimeGlucoseReadingParsesLivePlaintext` (L132-210): four live vectors, every
/// field pinned including fastData words, wordsLE, and the trailing byte.
#[test]
fn realtime_glucose_reading_parses_live_plaintext() {
    let cases: [(&str, u16, u16, i16, u16, &str, [u16; 4]); 4] = [
        (
            "e3386200d6ff0000f023d1386c000b62006c00200a9e03113d03100000",
            14563,
            98,
            -42,
            2592,
            "9e03113d03100000",
            [0x039e, 0x3d11, 0x1003, 0x0000],
        ),
        (
            "e4386200d6ff0000f023d1386c000b62006c001f0a9b03093d0b100000",
            14564,
            98,
            -42,
            2591,
            "9b03093d0b100000",
            [0x039b, 0x3d09, 0x100b, 0x0000],
        ),
        (
            "e5386100d9ff00008c23d1386c000b61006c001e0a9403053d12100000",
            14565,
            97,
            -39,
            2590,
            "9403053d12100000",
            [0x0394, 0x3d05, 0x1012, 0x0000],
        ),
        (
            "e6386100daff00008c23d1386c000b61006c001d0a9203003dfb0f0000",
            14566,
            97,
            -38,
            2589,
            "9203003dfb0f0000",
            [0x0392, 0x3d00, 0x0ffb, 0x0000],
        ),
    ];

    for c in cases {
        let reading = RealtimeGlucoseReading::parse(&unhex(c.0)).unwrap();
        assert_eq!(reading.life_count, c.1);
        assert_eq!(reading.current_word, c.2);
        assert_eq!(reading.reading_mg_dl, c.2);
        assert_eq!(reading.dq_error_raw, 0);
        assert_eq!(reading.dq_error, Libre3DataQualityError::Good);
        assert_eq!(reading.sensor_condition_raw, 0);
        assert_eq!(reading.sensor_condition, Libre3SensorCondition::Ok);
        assert_eq!(reading.current_glucose_mg_dl, Some(c.2));
        assert!(reading.is_current_glucose_valid);
        assert!(reading.is_current_dq_good());
        assert!(reading.is_current_glucose_usable());
        assert_eq!(reading.rate_of_change_raw, c.3);
        let rate = reading.rate_of_change_mg_dl_per_minute.unwrap();
        assert!((rate - f32::from(c.3) / 100.0).abs() < 0.0001);
        assert_eq!(reading.esa_duration, 0);
        assert_eq!(reading.temperature_status, 0);
        assert_eq!(reading.projected_glucose, if c.2 == 98 { 9200 } else { 9100 });
        assert_eq!(reading.historical_life_count, 14545);
        assert_eq!(reading.historical_word, 108);
        assert_eq!(reading.historical_reading, 108);
        assert_eq!(reading.historical_reading_dq_error_raw, 0);
        assert_eq!(reading.historical_reading_dq_error, Libre3DataQualityError::Good);
        assert_eq!(reading.historic_result_range_status_raw, 0);
        assert_eq!(reading.historic_result_range_status, Libre3ResultRangeStatus::InRange);
        assert_eq!(reading.historical_glucose_mg_dl, Some(108));
        assert!(reading.is_historical_glucose_valid);
        assert!(reading.is_historical_dq_good());
        assert_eq!(reading.trend_and_status_byte, 0x0b);
        assert_eq!(reading.trend_raw, 3);
        assert_eq!(reading.trend, 3);
        assert_eq!(reading.trend_kind, Libre3Trend::Stable);
        assert_eq!(reading.rest, 1);
        assert_eq!(reading.actionable_status, 1);
        assert_eq!(reading.actionability, Libre3ActionableStatus::Actionable);
        assert_eq!(reading.uncapped_current_mg_dl, c.2);
        assert_eq!(reading.uncapped_historic_mg_dl, 108);
        assert_eq!(reading.temperature, c.4);
        assert_eq!(hex(&reading.fast_data), c.5);
        assert_eq!(reading.fast_data_words_le, c.6);
        let evidence = &reading.quality_evidence();
        assert_eq!(evidence.dq_error_raw, 0);
        assert_eq!(evidence.dq_error, Libre3DataQualityError::Good);
        assert_eq!(evidence.historical_reading_dq_error_raw, 0);
        assert_eq!(evidence.historical_reading_dq_error, Libre3DataQualityError::Good);
        assert_eq!(evidence.sensor_condition_raw, 0);
        assert_eq!(evidence.sensor_condition, Libre3SensorCondition::Ok);
        assert_eq!(evidence.historic_result_range_status_raw, 0);
        assert_eq!(evidence.historic_result_range_status, Libre3ResultRangeStatus::InRange);
        assert_eq!(evidence.actionable_status, 1);
        assert_eq!(evidence.actionability, Libre3ActionableStatus::Actionable);
        assert_eq!(evidence.fast_data_words_le, c.6);
        assert_eq!(evidence.status_bits, 1);
        assert_eq!(evidence.trend, Libre3Trend::Stable);
        assert_eq!(evidence.current_glucose, Libre3GlucoseValueStatus::Valid(c.2));
        assert_eq!(reading.words_le[0], c.1);
        assert_eq!(reading.words_le[1], c.2);
        assert_eq!(reading.trailing_byte, 0);
    }
}

/// kit `testRealtimeGlucoseReadingUsesCurrentNormalization` (L212-237).
#[test]
fn realtime_glucose_reading_uses_current_normalization() {
    let base = unhex("e3386200d6ff0000f023d1386c000b62006c00200a9e03113d03100000");

    let low = RealtimeGlucoseReading::parse(&setting_u16(&base, 15, 38)).unwrap();
    assert_eq!(low.reading_mg_dl, 98);
    assert_eq!(low.uncapped_current_mg_dl, 38);
    assert_eq!(low.current_glucose_mg_dl, Some(39));
    assert_eq!(
        low.current_glucose_status(),
        Libre3GlucoseValueStatus::BelowDisplayRange { raw: 38, display_mg_dl: 39 }
    );
    assert!(low.is_current_glucose_valid);

    let high = RealtimeGlucoseReading::parse(&setting_u16(&base, 15, 600)).unwrap();
    assert_eq!(high.uncapped_current_mg_dl, 600);
    assert_eq!(high.current_glucose_mg_dl, Some(501));
    assert_eq!(
        high.current_glucose_status(),
        Libre3GlucoseValueStatus::AboveDisplayRange { raw: 600, display_mg_dl: 501 }
    );
    assert!(high.is_current_glucose_valid);

    let invalid_zero = RealtimeGlucoseReading::parse(&setting_u16(&base, 15, 0)).unwrap();
    assert_eq!(invalid_zero.current_glucose_mg_dl, None);
    assert_eq!(invalid_zero.current_glucose_status(), Libre3GlucoseValueStatus::Unavailable(0));
    assert!(!invalid_zero.is_current_glucose_valid);

    let invalid_high = RealtimeGlucoseReading::parse(&setting_u16(&base, 15, 1000)).unwrap();
    assert_eq!(invalid_high.current_glucose_mg_dl, None);
    assert_eq!(invalid_high.current_glucose_status(), Libre3GlucoseValueStatus::Unavailable(1000));
    assert!(!invalid_high.is_current_glucose_valid);
}

/// kit `testRealtimeGlucoseReadingParsesAbbottDQAndStatusBits` (L239-288).
#[test]
fn realtime_glucose_reading_parses_dq_and_status_bits() {
    let base = unhex("e3386200d6ff0000f023d1386c000b62006c00200a9e03113d03100000");

    let current_dq = RealtimeGlucoseReading::parse(&setting_u16(&base, 2, 0x8062)).unwrap();
    assert_eq!(current_dq.reading_mg_dl, 98);
    assert_eq!(current_dq.dq_error_raw, 0x8062);
    assert_eq!(current_dq.dq_error, Libre3DataQualityError::NotDisplayable(0x8062));
    assert_eq!(current_dq.sensor_condition_raw, 0);
    assert_eq!(current_dq.sensor_condition, Libre3SensorCondition::Ok);
    assert!(!current_dq.is_current_dq_good());
    assert!(!current_dq.is_current_glucose_usable());

    let current_sensor_condition = RealtimeGlucoseReading::parse(&setting_u16(&base, 2, 0x2062)).unwrap();
    assert_eq!(current_sensor_condition.reading_mg_dl, 98);
    assert_eq!(current_sensor_condition.dq_error_raw, 0);
    assert_eq!(current_sensor_condition.dq_error, Libre3DataQualityError::Good);
    assert_eq!(current_sensor_condition.sensor_condition_raw, 1);
    assert_eq!(current_sensor_condition.sensor_condition, Libre3SensorCondition::Invalid);
    assert!(!current_sensor_condition.is_current_dq_good());

    let historical_dq = RealtimeGlucoseReading::parse(&setting_u16(&base, 12, 0x806c)).unwrap();
    assert_eq!(historical_dq.historical_reading, 108);
    assert_eq!(historical_dq.historical_reading_dq_error_raw, 0x806c);
    assert_eq!(historical_dq.historical_reading_dq_error, Libre3DataQualityError::NotDisplayable(0x806c));
    assert_eq!(historical_dq.historic_result_range_status_raw, 0);
    assert_eq!(historical_dq.historic_result_range_status, Libre3ResultRangeStatus::InRange);
    assert!(!historical_dq.is_historical_dq_good());

    let historical_range = RealtimeGlucoseReading::parse(&setting_u16(&base, 12, 0x406c)).unwrap();
    assert_eq!(historical_range.historical_reading, 108);
    assert_eq!(historical_range.historical_reading_dq_error_raw, 0);
    assert_eq!(historical_range.historical_reading_dq_error, Libre3DataQualityError::Good);
    assert_eq!(historical_range.historic_result_range_status_raw, 2);
    assert_eq!(historical_range.historic_result_range_status, Libre3ResultRangeStatus::AboveRange);
    assert!(!historical_range.is_historical_dq_good());

    let mut not_actionable_payload = base.clone();
    not_actionable_payload[14] = 0x03;
    let not_actionable = RealtimeGlucoseReading::parse(&not_actionable_payload).unwrap();
    assert_eq!(not_actionable.trend, 3);
    assert_eq!(not_actionable.trend_kind, Libre3Trend::Stable);
    assert_eq!(not_actionable.actionable_status, 0);
    assert_eq!(not_actionable.actionability, Libre3ActionableStatus::NotActionable);
    // Actionability is advisory: an otherwise-clean non-actionable reading stays usable.
    assert!(not_actionable.is_current_glucose_usable());

    let temperature_status = RealtimeGlucoseReading::parse(&setting_u16(&base, 6, 0x0201)).unwrap();
    assert_eq!(temperature_status.temperature_status, 0x0201);
}

/// kit `testPatchStatusParsesLivePlaintext` (L290-315).
#[test]
fn patch_status_parses_live_plaintext() {
    let status = PatchStatus::parse(&unhex("9d280000e0000704e4381300")).unwrap();

    assert_eq!(status.life_count, 10397);
    assert_eq!(status.error_data, 0);
    assert_eq!(status.event_data_raw, 224);
    assert_eq!(status.event_data, 4224);
    assert_eq!(status.index, 7);
    assert_eq!(status.total_events, 8);
    assert_eq!(status.patch_state, 4);
    assert_eq!(status.patch_state_kind(), Libre3PatchState::Active);
    assert_eq!(status.current_life_count, 14564);
    assert_eq!(status.stack_disconnect_reason, 19);
    assert_eq!(status.app_disconnect_reason, 0);
    assert!(!status.has_error_data());
    assert!(status.has_disconnect_reason());
    assert_eq!(status.sensor_error(), Libre3SensorError::None);
    assert!(!status.is_shutdown_terminated());
    assert!(!status.is_insertion_failure());

    let lifecycle = status.lifecycle(60, Some(20160));
    assert_eq!(lifecycle.phase(), libre3_core::status::SensorLifecyclePhase::Active);
    assert!(lifecycle.is_warmup_complete());
    assert!(!lifecycle.is_expired());
    assert_eq!(lifecycle.remaining_wear_minutes(), Some(5596));
}

/// Synthetic 40000 min (0x9C40) at both counts; an i16 read it negative, the lifecycle warm-up.
#[test]
fn patch_status_life_counts_stay_unsigned_past_i16() {
    let status = PatchStatus::parse(&unhex("409c0000e0000704409c1300")).unwrap();

    assert_eq!(status.life_count, 40000);
    assert_eq!(status.current_life_count, 40000);
    let lifecycle = status.lifecycle(60, None);
    assert!(lifecycle.is_warmup_complete());
    assert!(!lifecycle.is_warming_up());
}

/// kit `testPatchStatusDecodesSensorErrorCodes` (L317-365): synthetic frames with the
/// errorData word (offset 2-3, LE) swapped.
#[test]
fn patch_status_decodes_sensor_error_codes() {
    let status = |error_word: &str| -> PatchStatus {
        PatchStatus::parse(&unhex(&format!("9d28{error_word}e0000704e4381300"))).unwrap()
    };

    assert_eq!(status("0000").sensor_error(), Libre3SensorError::None);
    assert_eq!(status("0300").sensor_error(), Libre3SensorError::InsertionFailure);
    assert_eq!(status("0500").sensor_error(), Libre3SensorError::Expired);
    assert_eq!(status("0600").sensor_error(), Libre3SensorError::Terminated);
    assert_eq!(status("0700").sensor_error(), Libre3SensorError::TransmissionError);
    assert_eq!(status("0800").sensor_error(), Libre3SensorError::Terminated);
    assert_eq!(status("0900").sensor_error(), Libre3SensorError::Unknown(9));

    assert_eq!(status("0000").sensor_attention(), Libre3SensorAttention::None);
    assert_eq!(status("0300").sensor_attention(), Libre3SensorAttention::CheckSensor);
    assert_eq!(status("0500").sensor_attention(), Libre3SensorAttention::SensorEnded);
    assert_eq!(status("0600").sensor_attention(), Libre3SensorAttention::SensorEnded);
    assert_eq!(status("0700").sensor_attention(), Libre3SensorAttention::CheckSensor);
    assert_eq!(status("0800").sensor_attention(), Libre3SensorAttention::ReplaceSensor);
    assert_eq!(status("0900").sensor_attention(), Libre3SensorAttention::Unknown(9));
    // Code 7 is a transient transmission error: notifies the user (soft check-sensor) but is
    // NOT a replace condition. Code 8 is the terminal replace.
    assert!(!status("0700").should_notify_replace_sensor());
    assert!(status("0700").should_notify_user());
    assert!(status("0800").should_notify_replace_sensor());
    assert!(!status("0500").should_notify_replace_sensor());
    assert!(status("0300").should_notify_user());
    assert!(!status("0000").should_notify_user());

    // Expiry remains BLE-visible until shutdown; terminated is the post-shutdown/no-advertising
    // state. Insertion failure is separate.
    assert!(status("0300").is_insertion_failure());
    assert!(!status("0000").is_insertion_failure());
    assert!(!status("0500").is_insertion_failure());
    assert!(!status("0600").is_insertion_failure());
    assert!(!status("0700").is_insertion_failure());
    assert!(!status("0800").is_insertion_failure());
    assert!(!status("0900").is_insertion_failure());

    assert!(status("0600").is_shutdown_terminated());
    assert!(status("0800").is_shutdown_terminated());
    assert!(!status("0000").is_shutdown_terminated());
    assert!(!status("0300").is_shutdown_terminated());
    assert!(!status("0500").is_shutdown_terminated());
    assert!(!status("0700").is_shutdown_terminated());
    assert!(!status("0900").is_shutdown_terminated());
}

/// kit `testPatchStatusExposesPatchStateGroups` (L367-409).
#[test]
fn patch_status_exposes_patch_state_groups() {
    let status = |patch_state: u8| -> PatchStatus {
        let mut bytes = unhex("9d280000e0000704e4381300");
        bytes[7] = patch_state;
        PatchStatus::parse(&bytes).unwrap()
    };

    let active = status(4);
    assert_eq!(active.patch_state_kind(), Libre3PatchState::Active);
    assert_eq!(active.patch_state_kind().raw_value(), 4);
    assert!(active.is_patch_state_active());
    assert!(!active.is_patch_state_expired_or_error());
    assert!(!active.is_patch_state_terminated());

    for state in [3u8, 5, 7] {
        let value = status(state);
        assert_eq!(value.patch_state_kind(), Libre3PatchState::Raw(state as i8));
        assert!(!value.is_patch_state_active());
        assert!(value.is_patch_state_expired_or_error());
        assert!(!value.is_patch_state_terminated());
    }

    assert_eq!(status(3).sensor_attention(), Libre3SensorAttention::CheckSensor);
    assert_eq!(status(5).sensor_attention(), Libre3SensorAttention::SensorEnded);
    assert_eq!(status(6).sensor_attention(), Libre3SensorAttention::SensorEnded);
    assert_eq!(status(7).sensor_attention(), Libre3SensorAttention::ReplaceSensor);
    assert_eq!(status(8).sensor_attention(), Libre3SensorAttention::ReplaceSensor);
    assert!(status(7).should_notify_replace_sensor());

    for state in [6u8, 8] {
        let value = status(state);
        assert_eq!(value.patch_state_kind(), Libre3PatchState::Raw(state as i8));
        assert!(!value.is_patch_state_active());
        assert!(!value.is_patch_state_expired_or_error());
        assert!(value.is_patch_state_terminated());
    }

    let unknown = status(9);
    assert_eq!(unknown.patch_state_kind(), Libre3PatchState::Raw(9));
    assert!(!unknown.is_patch_state_active());
    assert!(!unknown.is_patch_state_expired_or_error());
    assert!(!unknown.is_patch_state_terminated());
}

/// kit `testLifecycleTakesWarmupAndWearFromPatchInfo` (L411-456), plus the
/// `Libre3DataPlaneState` equivalents driven through the session's plaintext seeding.
#[test]
fn lifecycle_takes_warmup_and_wear_from_patch_info() {
    // 12-minute-old sensor: still in warmup when warmup is the patchInfo value of 60 (would
    // already be "active" under a smaller default).
    let status = PatchStatus::parse(&unhex("0c000000000000040c000000")).unwrap();
    assert_eq!(status.current_life_count, 12);

    // Patch-info cross-check through the §5.9 seam: the kit's Libre3NFCPatchInfo on this raw
    // response reports warmupMinutes 60 / wearDurationMinutes 21600; the crate's Libre3PatchInfo
    // carries the wear duration (warmup is the SensorLifecycle default of 60).
    let patch_info = libre3_core::libre3_nfc_parse_patch_info(unhex(
        "00a50001000200010060541e020401040c04305252433938394151c6ca",
    ))
    .unwrap();
    assert_eq!(patch_info.wear_duration_min, 21600);
    let warmup = SensorLifecycle::DEFAULT_WARMUP_DURATION_MINUTES; // 60 (SensorLifecycle.swift L10)

    let lifecycle = status.lifecycle(warmup, Some(21600));
    assert_eq!(lifecycle.warmup_duration_minutes, 60);
    assert_eq!(lifecycle.wear_duration_minutes, Some(21600));
    assert_eq!(lifecycle.phase(), libre3_core::status::SensorLifecyclePhase::Warmup);
    assert!(lifecycle.is_warming_up());
    assert_eq!(lifecycle.remaining_warmup_minutes(), 48);
    assert_eq!(lifecycle.remaining_wear_minutes(), Some(21588));

    // Libre3DataPlaneState(patchInfo:latestPatchStatus:) equivalent: seed via the session.
    let session =
        Libre3DataPlaneSession::new(vec![0; 16], vec![0; 8], warmup as u32, Some(21600)).unwrap();
    let record = session.record_patch_status_plaintext(unhex("0c000000000000040c000000")).unwrap();
    assert_eq!(record.lifecycle.phase, "warmup");
    assert!(record.lifecycle.is_warming_up);
    assert_eq!(record.lifecycle.remaining_warmup_min, 48);
    assert_eq!(record.lifecycle.remaining_wear_min, Some(21588));
    assert_eq!(record.attention, "none");
    assert!(!record.should_notify_user);
    assert!(!record.should_notify_replace_sensor);
    let latest = session.latest_lifecycle().unwrap().unwrap();
    assert_eq!(latest.phase, "warmup");
    assert_eq!(latest.remaining_warmup_min, 48);

    // errorData 8 (terminated) is a genuine terminal replace. Code 7 is a transient
    // transmission error and maps to checkSensor, not replace.
    let replace = session
        .record_patch_status_plaintext(unhex("0c000800000000040c000000"))
        .unwrap();
    assert_eq!(replace.attention, "replaceSensor");
    assert!(replace.should_notify_user);
    assert!(replace.should_notify_replace_sensor);

    let transient = session
        .record_patch_status_plaintext(unhex("0c000700000000040c000000"))
        .unwrap();
    assert_eq!(transient.attention, "checkSensor");
    assert!(transient.should_notify_user);
    assert!(!transient.should_notify_replace_sensor);
}

/// Session-level `Libre3DataPlaneState.record()` semantics: a landed patchStatus re-assesses
/// the LATEST realtime reading (warmup turns the previously usable reading unusable).
#[test]
fn session_status_reassesses_latest_realtime_reading() {
    let session = Libre3DataPlaneSession::new(
        unhex("7c536d2ec3c76c741a776de85596959d"),
        unhex("00000000bcc696a2"),
        60,
        None,
    )
    .unwrap();

    let glucose_update = |session: &Libre3DataPlaneSession| {
        let prefix = unhex("3c937752c72e2dfdcfe05a5dce315b");
        let suffix = unhex("6f3785f23dbc85066856d60027c5a41f45c90100");
        assert!(session.feed(Libre3DataChannel::GlucoseData, prefix).unwrap().is_empty());
        let updates = session.feed(Libre3DataChannel::GlucoseData, suffix).unwrap();
        match &updates[0] {
            Libre3DataPlaneUpdate::RealtimeGlucose { reading } => reading.clone(),
            other => panic!("expected RealtimeGlucose update, got {other:?}"),
        }
    };

    // Clean reading, no lifecycle yet: usable, and accepted as the backfill lower bound.
    let first = glucose_update(&session);
    assert!(first.usable);
    assert_eq!(session.last_accepted_glucose_life_count().unwrap(), Some(14554));

    // A warmup status (currentLifeCount 12 < 60) lands: the latest reading re-assesses to
    // unusable with a warmup blocking issue, and nothing new is accepted.
    let status = session
        .record_patch_status_plaintext(unhex("0c000000000000040c000000"))
        .unwrap();
    assert_eq!(status.lifecycle.phase, "warmup");

    let second = glucose_update(&session);
    assert!(!second.usable);
    assert!(second.warmup);
    assert!(!second.expired);
    assert_eq!(
        second.issues,
        vec!["sensorWarmup(remainingMinutes: 48)".to_owned()]
    );
    assert_eq!(session.last_accepted_glucose_life_count().unwrap(), Some(14554));
}

/// kit `testSessionControlFrames` (L458-473): 0x0011 traffic from fresh_pair (recs 433, 437,
/// 438, 453). Variable encrypted length but consistent (seq, type) trailer.
#[test]
fn session_control_frames() {
    let cases: [(&str, usize, u8, u8); 4] = [
        ("ed7df1f64812b45afa57010100", 11, 0x01, 0x00),
        ("665f5b31bd9f9add0100", 8, 0x01, 0x00),
        ("0fdf3ccfe55e0045cb71920200", 11, 0x02, 0x00),
        ("d4d552d97bd404680200", 8, 0x02, 0x00),
    ];
    for c in cases {
        let frame = DataFrame::parse(&unhex(c.0)).unwrap();
        assert_eq!(frame.encrypted.len(), c.1, "case {}", c.0);
        assert_eq!(frame.seq, c.2, "case {}", c.0);
        assert_eq!(frame.type_, c.3, "case {}", c.0);
    }
}

/// kit `testDataPlaneNonceUsesSequenceDescriptorAndPhase6IV` (L475-485).
#[test]
fn nonce_uses_sequence_descriptor_and_phase6_iv() {
    let crypto = DataPlaneCrypto::new(
        &unhex("4bbce496a63cc9a435adeeb4f78e1617"),
        &unhex("0000000067c72c01"),
    )
    .unwrap();
    assert_eq!(hex(&crypto.nonce(0x0001, DataPlanePacketKind::PatchData)), "01004400000000000067c72c01");
}

/// kit `testDataPlaneCryptoRoundTripsAndFindsDescriptor` (L487-514).
#[test]
fn crypto_round_trips_and_finds_descriptor() {
    let crypto = DataPlaneCrypto::new(
        &unhex("4bbce496a63cc9a435adeeb4f78e1617"),
        &unhex("0000000067c72c01"),
    )
    .unwrap();
    let plaintext = unhex("00112233445566778899aabb");
    let frame = crypto.encrypt(&plaintext, 0x0201, DataPlanePacketKind::PatchData).unwrap();

    assert_eq!(frame.seq, 0x01);
    assert_eq!(frame.type_, 0x02);
    assert_eq!(frame.sequence_number(), 0x0201);
    assert_eq!(crypto.decrypt(&frame, DataPlanePacketKind::PatchData).unwrap(), plaintext);

    let found = crypto.decrypt_trying_all(&frame).unwrap();
    assert_eq!(found.kind, DataPlanePacketKind::PatchData);
    assert_eq!(found.plaintext, plaintext);

    match crypto.decrypt(&frame, DataPlanePacketKind::Kind0) {
        Err(libre3_core::DataPlaneError::Ccm(libre3_core::CryptoError::MacMismatch)) => {}
        other => panic!("expected macMismatch, got {other:?}"),
    }
}

/// kit `testPatchControlCommandPlaintextsMatchIOSBuilders` (L516-548), minus the shutdown
/// assertion (plan §5.7: terminal, never sent, not exposed). The 7-B vectors are also pinned
/// through the uniffi free fns Kotlin will call.
#[test]
fn patch_control_command_plaintexts_match_ios_builders() {
    assert_eq!(
        hex(
            &PatchControlCommand::historical_backfill_greater_equal(5, 0x01)
                .unwrap()
                .plaintext
        ),
        "01000105000000"
    );
    assert_eq!(
        hex(
            &PatchControlCommand::clinical_backfill_greater_equal(1, 0x01)
                .unwrap()
                .plaintext
        ),
        "01010101000000"
    );
    assert_eq!(
        hex(
            &PatchControlCommand::backfill_greater_equal(
                libre3_core::history::BackfillStream::Historical,
                907,
                0x01
            )
            .unwrap()
            .plaintext
        ),
        "0100018b030000"
    );
    assert_eq!(hex(&PatchControlCommand::event_log(1).unwrap().plaintext), "04010000000000");
    assert_eq!(hex(&PatchControlCommand::factory_data().unwrap().plaintext), "06000000000000");

    // PLAN §5.7 Range probe (2026-09-24): the vendor's bounded backfill shape via the kit's
    // makeRangeCommand aux field — `01 <stream> <selector> <start_LE2> <end_LE2>`. No kit
    // vector exists (no kit API sets aux); the shape is the kit builder itself.
    assert_eq!(
        hex(
            &PatchControlCommand::historical_backfill_range(907, 1284, 0x01)
                .unwrap()
                .plaintext
        ),
        "0100018b030405"
    );
    assert_eq!(
        hex(
            &PatchControlCommand::clinical_backfill_range(5, 60, 0x01)
                .unwrap()
                .plaintext
        ),
        "01010105003c00"
    );

    match PatchControlCommand::new("bad", vec![0x01]) {
        Err(libre3_core::DataPlaneError::WrongPlaintextSize { got: 1 }) => {}
        other => panic!("expected wrongPlaintextSize(1), got {other:?}"),
    }

    // The uniffi free fns agree with the builders.
    assert_eq!(libre3_core::libre3_historical_backfill_cmd(5, 0x01).unwrap(), unhex("01000105000000"));
    assert_eq!(libre3_core::libre3_clinical_backfill_cmd(1, 0x01).unwrap(), unhex("01010101000000"));
    assert_eq!(libre3_core::libre3_event_log_cmd(1).unwrap(), unhex("04010000000000"));
    assert_eq!(libre3_core::libre3_factory_data_cmd().unwrap(), unhex("06000000000000"));
    assert_eq!(
        libre3_core::libre3_historical_backfill_range_cmd(907, 1284, 0x01).unwrap(),
        unhex("0100018b030405")
    );
    assert_eq!(
        libre3_core::libre3_clinical_backfill_range_cmd(5, 60, 0x01).unwrap(),
        unhex("01010105003c00")
    );
}

/// kit `testPatchControlCommandEncryptsTo13ByteWriteFrame` (L550-567).
#[test]
fn patch_control_command_encrypts_to_13_byte_write_frame() {
    let crypto = DataPlaneCrypto::new(
        &unhex("4bbce496a63cc9a435adeeb4f78e1617"),
        &unhex("0000000067c72c01"),
    )
    .unwrap();
    let command = PatchControlCommand::historical_backfill_greater_equal(5, 0x01).unwrap();
    let frame = crypto
        .encrypt(&command.plaintext, 0x0001, DataPlanePacketKind::PATCH_CONTROL_WRITE)
        .unwrap();

    assert_eq!(frame.raw().len(), 13);
    assert_eq!(frame.encrypted.len(), 11);
    assert_eq!(frame.seq, 0x01);
    assert_eq!(frame.type_, 0x00);
    assert_eq!(
        crypto.decrypt(&frame, DataPlanePacketKind::PATCH_CONTROL_WRITE).unwrap(),
        command.plaintext
    );
}

/// Session tx sequencing: per-channel counter starting 0x0001, kind0, 13-B frames.
#[test]
fn session_patch_control_frames_sequence_per_channel() {
    let session =
        Libre3DataPlaneSession::new(unhex("4bbce496a63cc9a435adeeb4f78e1617"), unhex("0000000067c72c01"), 60, None)
            .unwrap();
    let crypto = DataPlaneCrypto::new(
        &unhex("4bbce496a63cc9a435adeeb4f78e1617"),
        &unhex("0000000067c72c01"),
    )
    .unwrap();

    for expected_seq in [0x0001u16, 0x0002, 0x0003] {
        let raw = session
            .next_patch_control_frame(unhex("01000105000000"))
            .unwrap();
        assert_eq!(raw.len(), 13);
        let frame = DataFrame::parse(&raw).unwrap();
        assert_eq!(frame.sequence_number(), expected_seq);
        assert_eq!(frame.type_, 0x00);
        assert_eq!(
            crypto.decrypt(&frame, DataPlanePacketKind::PATCH_CONTROL_WRITE).unwrap(),
            unhex("01000105000000")
        );
    }

    // Wrong size fails closed, and the counter does not advance.
    assert!(session.next_patch_control_frame(vec![0x01]).is_err());
    let raw = session.next_patch_control_frame(unhex("01000105000000")).unwrap();
    let frame = DataFrame::parse(&raw).unwrap();
    assert_eq!(frame.sequence_number(), 0x0004);
}

/// kit `testHistoricalReadingPageParsesLivePlaintext` (L569-588).
#[test]
fn historical_reading_page_parses_live_plaintext() {
    let page = HistoricalReadingPage::parse(&unhex("8e0369006b006d006b0069006e00")).unwrap();

    assert_eq!(page.start_life_count, 910);
    assert_eq!(page.end_life_count(), 935);
    assert_eq!(page.values, vec![105, 107, 109, 107, 105, 110]);
    let samples = page.samples();
    assert_eq!(
        samples,
        vec![
            libre3_core::history::HistoricalReadingSample { life_count: 910, raw_value: 105 },
            libre3_core::history::HistoricalReadingSample { life_count: 915, raw_value: 107 },
            libre3_core::history::HistoricalReadingSample { life_count: 920, raw_value: 109 },
            libre3_core::history::HistoricalReadingSample { life_count: 925, raw_value: 107 },
            libre3_core::history::HistoricalReadingSample { life_count: 930, raw_value: 105 },
            libre3_core::history::HistoricalReadingSample { life_count: 935, raw_value: 110 },
        ]
    );
    assert_eq!(samples[0].glucose_mg_dl(), Some(105));
    assert_eq!(samples[0].glucose_status(), Libre3GlucoseValueStatus::Valid(105));
}

/// kit `testHistoricalBackfillSummarizesCoverageAndGaps` (L590-608).
#[test]
fn historical_backfill_summarizes_coverage_and_gaps() {
    let first = HistoricalReadingPage::parse(&unhex("8e0369006b006d006b0069006e00")).unwrap();
    let second = HistoricalReadingPage::parse(&unhex("ac036f00710070006c006a006b00")).unwrap();
    let third = HistoricalReadingPage::parse(&unhex("e803780079007a007b007c007d00")).unwrap();

    let mut backfill = HistoricalBackfill::new();
    backfill.append(second);
    backfill.append(first);

    assert_eq!(backfill.first_life_count(), Some(910));
    assert_eq!(backfill.last_life_count(), Some(965));
    assert!(backfill.is_contiguous());
    assert_eq!(backfill.gaps(), vec![]);
    let life_counts: Vec<u16> = backfill.samples().iter().map(|s| s.life_count).collect();
    assert_eq!(
        life_counts,
        vec![910, 915, 920, 925, 930, 935, 940, 945, 950, 955, 960, 965]
    );

    backfill.append(third);
    assert!(!backfill.is_contiguous());
    assert_eq!(
        backfill.gaps(),
        vec![libre3_core::history::HistoricalBackfillGap {
            after_life_count: 965,
            before_life_count: 1000,
        }]
    );
}

/// The clinical decoder: one 14-B single-time-point record, historic estimate at
/// `lifeCount − 17` snapped down to the 5-minute boundary (ClinicalReadingRecord.swift
/// L122-126).
#[test]
fn clinical_record_decodes_single_time_point() {
    // lifeCount 950, words 1..3 raw channels, reserved 0, current 104, historic 105.
    let record = ClinicalReadingRecord::parse(&unhex("b6030a000b000c00000068006900")).unwrap();
    assert_eq!(record.life_count, 950);
    assert_eq!(record.raw_sensor_word1, 0x000a);
    assert_eq!(record.raw_sensor_word2, 0x000b);
    assert_eq!(record.raw_sensor_word3, 0x000c);
    assert_eq!(record.reserved_word, 0);
    assert_eq!(record.current_glucose_raw, 104);
    assert_eq!(record.historic_glucose_raw, 105);
    assert_eq!(record.current_glucose_mg_dl(), Some(104));
    assert_eq!(record.historic_glucose_mg_dl(), Some(105));
    // 950 − 17 = 933 → snapped down to 930.
    assert_eq!(record.historic_life_count_estimate(), Some(930));
    // Underflow (lifeCount < 17) yields None.
    let early = ClinicalReadingRecord::parse(&unhex("05000a000b000c00000068006900")).unwrap();
    assert_eq!(early.historic_life_count_estimate(), None);
}

/// Session-level clinical page: the record passes through unchanged and never folds into the
/// historical backfill (Libre3DataPlaneState.swift L151-157).
#[test]
fn session_feeds_clinical_record_through() {
    let session =
        Libre3DataPlaneSession::new(unhex("4bbce496a63cc9a435adeeb4f78e1617"), unhex("0000000067c72c01"), 60, None)
            .unwrap();
    let crypto = DataPlaneCrypto::new(
        &unhex("4bbce496a63cc9a435adeeb4f78e1617"),
        &unhex("0000000067c72c01"),
    )
    .unwrap();
    let frame = crypto
        .encrypt(&unhex("b6030a000b000c00000068006900"), 0x0001, DataPlanePacketKind::Kind5)
        .unwrap();
    let updates = session
        .feed(Libre3DataChannel::ClinicalData, frame.raw())
        .unwrap();
    match &updates[0] {
        Libre3DataPlaneUpdate::Clinical { record } => {
            assert_eq!(record.life_count, 950);
            assert_eq!(record.current_mgdl, Some(104));
            assert_eq!(record.historic_mgdl, Some(105));
        }
        other => panic!("expected Clinical update, got {other:?}"),
    }
}

/// The Kotlin arrow mapping keys on these spellings; renaming one silently fits that arrow.
#[test]
fn trend_display_spellings_are_the_kotlin_contract() {
    let spellings: Vec<String> = (0..=6).map(|raw| Libre3Trend::from_raw(raw).to_string()).collect();
    assert_eq!(
        spellings,
        ["notDetermined", "fallingQuickly", "falling", "stable", "rising", "risingQuickly", "raw(6)"],
    );
}
