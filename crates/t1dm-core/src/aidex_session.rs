//! AiDEX X connected-session protocol — CGM.md §4–§7.

use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockEncrypt, KeyInit};
use aes::Aes128;
use md5::{Digest, Md5};

use crate::{glucose_of, le16, CoreError};

/// `crc16_ccitt_false` polynomial, MSB-first.
const CRC16_POLY: u16 = 0x1021;
/// `crc8_maxim` reflected polynomial.
const CRC8_POLY: u8 = 0x8C;
/// AES-128 block/key size; the session blob is that key plus a crc8 byte.
const BLOCK: usize = 16;
const SESSION_BLOB_LEN: usize = 17;
/// Glucose validity window treated as a real reading (CGM.md §9).
const GLUCOSE_MIN: i32 = 18;
const GLUCOSE_MAX: i32 = 800;
/// The realtime `CurrentGlucose` struct on `0xF003`.
const REALTIME_LEN: usize = 15;
/// `LocalStartTime` wire structure (CGM.md §7).
const LOCAL_START_TIME_LEN: usize = 9;

/// `crc8_maxim` (reflected, init 0) — the session-key check byte (CGM.md §4.4).
fn crc8_maxim(data: &[u8]) -> u8 {
    let mut crc = 0u8;
    for &b in data {
        crc ^= b;
        for _ in 0..8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ CRC8_POLY } else { crc >> 1 };
        }
    }
    crc
}

/// `crc16_ccitt_false` (init 0xFFFF, MSB-first, no reflect/xorout); trailing frame CRC (§4.5).
fn crc16_ccitt_false(data: &[u8]) -> u16 {
    let mut crc = 0xFFFFu16;
    for &b in data {
        crc ^= (b as u16) << 8;
        for _ in 0..8 {
            crc = if crc & 0x8000 != 0 { (crc << 1) ^ CRC16_POLY } else { crc << 1 };
        }
    }
    crc
}

fn md5_16(bytes: &[u8]) -> [u8; BLOCK] {
    let mut h = Md5::new();
    h.update(bytes);
    let out = h.finalize();
    let mut r = [0u8; BLOCK];
    r.copy_from_slice(&out);
    r
}

enum Dir {
    Encrypt,
    Decrypt,
}

/// AES-128-CFB128, `iv` reused per call (CGM.md §4.5); always ENCRYPT, feedback source differs.
fn aes128_cfb(key: &[u8; BLOCK], iv: &[u8; BLOCK], data: &[u8], dir: Dir) -> Vec<u8> {
    let cipher = Aes128::new(GenericArray::from_slice(key));
    let mut feedback = *iv;
    let mut out = Vec::with_capacity(data.len());
    for chunk in data.chunks(BLOCK) {
        let mut block = GenericArray::clone_from_slice(&feedback);
        cipher.encrypt_block(&mut block); // keystream = E(feedback)
        let mut next = [0u8; BLOCK];
        for (i, &b) in chunk.iter().enumerate() {
            let o = b ^ block[i];
            out.push(o);
            // Feedback is the ciphertext: the output on encrypt, the input on decrypt.
            next[i] = match dir {
                Dir::Encrypt => o,
                Dir::Decrypt => b,
            };
        }
        if chunk.len() == BLOCK {
            feedback = next;
        }
    }
    out
}

/// `snval(c)`: the per-character nibble-ish value (CGM.md §4.3).
fn snval(c: u8) -> Result<u8, CoreError> {
    match c {
        b'0'..=b'9' => Ok(c - b'0'),
        b'A'..=b'Z' => Ok(c - 0x37),
        b'a'..=b'z' => Ok(c - 0x57),
        _ => Err(CoreError::Decode { reason: format!("invalid serial char: {c:#04x}") }),
    }
}

fn snvals(serial: &str) -> Result<Vec<u8>, CoreError> {
    if serial.is_empty() {
        return Err(CoreError::Decode { reason: "empty serial".into() });
    }
    serial.bytes().map(snval).collect()
}

/// The fixed AES IV, reused for every message (CGM.md §4.3).
fn derive_iv(serial: &str) -> Result<[u8; BLOCK], CoreError> {
    let input: Vec<u8> =
        snvals(serial)?.iter().map(|&v| ((v as u16 * 17 + 0x13) & 0xFF) as u8).collect();
    Ok(md5_16(&input))
}

/// `askKey`, written to `0xF001` to open the handshake (CGM.md §4.3).
fn derive_askkey(serial: &str) -> Result<[u8; BLOCK], CoreError> {
    let input: Vec<u8> =
        snvals(serial)?.iter().map(|&v| ((v as u16 * 13 + 61) & 0xFF) as u8).collect();
    Ok(md5_16(&input))
}

fn key_iv(sess: &[u8], iv: &[u8]) -> Result<([u8; BLOCK], [u8; BLOCK]), CoreError> {
    if sess.len() != BLOCK {
        return Err(CoreError::Decode {
            reason: format!("sess must be {BLOCK} bytes, got {}", sess.len()),
        });
    }
    if iv.len() != BLOCK {
        return Err(CoreError::Decode { reason: format!("iv must be {BLOCK} bytes, got {}", iv.len()) });
    }
    let mut k = [0u8; BLOCK];
    let mut v = [0u8; BLOCK];
    k.copy_from_slice(sess);
    v.copy_from_slice(iv);
    Ok((k, v))
}

/// `payload || le16(crc16_ccitt_false(payload))` (CGM.md §4.5), unencrypted.
fn frame_plaintext(payload: &[u8]) -> Vec<u8> {
    let crc = crc16_ccitt_false(payload);
    let mut pt = Vec::with_capacity(payload.len() + 2);
    pt.extend_from_slice(payload);
    pt.extend_from_slice(&crc.to_le_bytes());
    pt
}

fn encrypt_frame(sess: &[u8], iv: &[u8], payload: &[u8]) -> Result<Vec<u8>, CoreError> {
    let (k, v) = key_iv(sess, iv)?;
    let pt = frame_plaintext(payload);
    Ok(aes128_cfb(&k, &v, &pt, Dir::Encrypt))
}

fn decrypt_frame(sess: &[u8], iv: &[u8], ct: &[u8]) -> Result<Vec<u8>, CoreError> {
    let (k, v) = key_iv(sess, iv)?;
    if ct.len() < 2 {
        return Err(CoreError::Decode { reason: format!("frame too short: {} bytes", ct.len()) });
    }
    let pt = aes128_cfb(&k, &v, ct, Dir::Decrypt);
    let n = pt.len();
    let payload = &pt[..n - 2];
    let want = crc16_ccitt_false(payload);
    let got = u16::from_le_bytes([pt[n - 2], pt[n - 1]]);
    if want != got {
        return Err(CoreError::Decode { reason: format!("frame crc16 mismatch: want {want:#06x} got {got:#06x}") });
    }
    Ok(payload.to_vec())
}

/// A `LastPast` (CGM.md §6) — the same layout as the advertisement block (§3.1).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AidexLastPast {
    pub min_from_start: i32,
    pub status: i32,
    pub cal_temp: i32,
    pub trend_tenths_per_min: i32,
    pub glucose_mgdl: i32,
    pub valid: bool,
    pub quality: i32,
}

/// On-sensor history sample (CGM.md §6); timestamp = `activation_epoch + record_id*60`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AidexHistoryGlucose {
    pub record_id: i32,
    pub glucose_mgdl: i32,
    pub warmup: bool,
    pub valid: bool,
    pub is_real: bool,
}

/// Realtime `CurrentGlucose` off `0xF003`; `reading_type` 1=normal, 3=ended.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AidexRealtime {
    pub reading_type: i32,
    pub trend_tenths_per_min: i32,
    pub min_from_start: i32,
    pub glucose_mgdl: i32,
    pub warmup: bool,
    pub valid: bool,
    pub is_real: bool,
}

/// `0x110` device info.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AidexDeviceInfo {
    pub model1: i32,
    pub firmware: String,
    pub version: Vec<i32>,
    pub life_days: i32,
    pub model2: i32,
    pub name: String,
}

/// CGM.md §7. The fields are local wall clock; `epoch_secs` is UTC.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AidexLocalStartTime {
    pub year: i32,
    pub month: i32,
    pub day: i32,
    pub hour: i32,
    pub minute: i32,
    pub second: i32,
    pub tz_quarter_hours: i32,
    pub dst_quarter_hours: i32,
    pub epoch_secs: i64,
}

/// Tagged parse of decrypted `0xF002` (CGM.md §6); tag = `opcode | (status<<8)`, 0x01 = success.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum AidexResponse {
    /// `0x110` — device info.
    DeviceInfo { info: AidexDeviceInfo },
    /// `0x111` — current value as a `LastPast` (CGM.md §6).
    Current { reading: AidexLastPast },
    /// `0x121` — sensor activation time (CGM.md §7).
    StartTime { time: AidexLocalStartTime },
    /// `0x122` — newest absolute record id on the sensor (CGM.md §6).
    LastId { last_id: i32 },
    /// `0x123` — a history batch (CGM.md §6).
    History { start_id: i32, entries: Vec<AidexHistoryGlucose> },
    /// `0x120`/`0x131`/`0x134`/`0x135` — a bare acknowledgement.
    Ack { tag: i32 },
    /// `0x1F2` ok / `0x0F2` fail (CGM.md §6).
    Disconnect { success: bool },
    Unknown { tag: i32, payload: Vec<u8> },
}

/// Days from the Unix epoch to `y-m-d`, proleptic Gregorian (Hinnant's `days_from_civil`).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400; // [0, 399]
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1; // [0, 365]
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
    era * 146097 + doe - 719468
}

fn timegm(y: i64, mo: i64, d: i64, h: i64, mi: i64, s: i64) -> i64 {
    days_from_civil(y, mo, d) * 86400 + h * 3600 + mi * 60 + s
}

fn decode_local_start_time(b: &[u8]) -> Result<AidexLocalStartTime, CoreError> {
    if b.len() < LOCAL_START_TIME_LEN {
        return Err(CoreError::Decode {
            reason: format!("LocalStartTime needs {LOCAL_START_TIME_LEN} bytes, got {}", b.len()),
        });
    }
    let year = le16(&b[0..2]) as i32;
    let month = b[2] as i32;
    let day = b[3] as i32;
    let hour = b[4] as i32;
    let minute = b[5] as i32;
    let second = b[6] as i32;
    let tz = (b[7] as i8) as i32;
    let dst = b[8] as i32;
    let epoch = timegm(year as i64, month as i64, day as i64, hour as i64, minute as i64, second as i64)
        - (tz as i64 + dst as i64) * 15 * 60;
    Ok(AidexLocalStartTime {
        year,
        month,
        day,
        hour,
        minute,
        second,
        tz_quarter_hours: tz,
        dst_quarter_hours: dst,
        epoch_secs: epoch,
    })
}

/// Encode into the 9-byte `LocalStartTime` for `setNewSensor` (CGM.md §7).
fn encode_local_start_time(
    year: i32,
    month: i32,
    day: i32,
    hour: i32,
    minute: i32,
    second: i32,
    tz_quarter_hours: i32,
    dst_quarter_hours: i32,
) -> Result<Vec<u8>, CoreError> {
    let bad = |what: &str| CoreError::Decode { reason: format!("LocalStartTime {what} out of range") };
    if !(0..=0xFFFF).contains(&year) {
        return Err(bad("year"));
    }
    if !(1..=12).contains(&month) {
        return Err(bad("month"));
    }
    if !(1..=31).contains(&day) {
        return Err(bad("day"));
    }
    if !(0..=23).contains(&hour) {
        return Err(bad("hour"));
    }
    if !(0..=59).contains(&minute) {
        return Err(bad("minute"));
    }
    if !(0..=60).contains(&second) {
        return Err(bad("second"));
    }
    if !(-128..=127).contains(&tz_quarter_hours) {
        return Err(bad("tz"));
    }
    if !(0..=255).contains(&dst_quarter_hours) {
        return Err(bad("dst"));
    }
    let mut out = Vec::with_capacity(LOCAL_START_TIME_LEN);
    out.extend_from_slice(&(year as u16).to_le_bytes());
    out.push(month as u8);
    out.push(day as u8);
    out.push(hour as u8);
    out.push(minute as u8);
    out.push(second as u8);
    out.push((tz_quarter_hours as i8) as u8);
    out.push(dst_quarter_hours as u8);
    Ok(out)
}

/// A decrypted `LastPast`/advert block, 8 bytes (CGM.md §3.1/§6).
fn parse_last_past(b: &[u8]) -> Result<AidexLastPast, CoreError> {
    if b.len() < 8 {
        return Err(CoreError::Decode { reason: format!("LastPast needs 8 bytes, got {}", b.len()) });
    }
    let (glucose_mgdl, valid) = glucose_of(le16(&b[5..7]));
    Ok(AidexLastPast {
        min_from_start: le16(&b[0..2]) as i32,
        status: b[2] as i32,
        cal_temp: b[3] as i32,
        trend_tenths_per_min: (b[4] as i8) as i32,
        glucose_mgdl,
        valid,
        quality: b[7] as i32,
    })
}

/// The 15-byte realtime `CurrentGlucose`.
fn parse_realtime(pt: &[u8]) -> Result<AidexRealtime, CoreError> {
    if pt.len() < REALTIME_LEN {
        return Err(CoreError::Decode {
            reason: format!("CurrentGlucose needs {REALTIME_LEN} bytes, got {}", pt.len()),
        });
    }
    let bitfield = le16(&pt[6..8]);
    let (glucose_mgdl, valid) = glucose_of(bitfield);
    let warmup = (bitfield >> 10) & 1 == 1;
    let is_real = valid && (GLUCOSE_MIN..=GLUCOSE_MAX).contains(&glucose_mgdl);
    Ok(AidexRealtime {
        reading_type: pt[0] as i32,
        trend_tenths_per_min: (pt[3] as i8) as i32,
        min_from_start: le16(&pt[4..6]) as i32,
        glucose_mgdl,
        warmup,
        valid,
        is_real,
    })
}

/// One 2-byte `HistoryGlucose` bitfield (CGM.md §6).
fn history_glucose(bitfield: u16, record_id: i32) -> AidexHistoryGlucose {
    let (glucose_mgdl, valid) = glucose_of(bitfield);
    let warmup = (bitfield >> 10) & 1 == 1;
    let is_real = valid && (GLUCOSE_MIN..=GLUCOSE_MAX).contains(&glucose_mgdl);
    AidexHistoryGlucose { record_id, glucose_mgdl, warmup, valid, is_real }
}

/// A decrypted `0xF002` payload — tag + data, CRC already stripped (CGM.md §6).
fn parse_response(pt: &[u8]) -> Result<AidexResponse, CoreError> {
    if pt.len() < 2 {
        return Err(CoreError::Decode { reason: format!("response too short: {} bytes", pt.len()) });
    }
    let tag = le16(&pt[0..2]) as i32; // opcode | (status<<8)
    let body = &pt[2..];
    Ok(match tag {
        0x110 => AidexResponse::DeviceInfo { info: parse_device_info(pt)? },
        0x111 => AidexResponse::Current { reading: parse_last_past(body)? },
        0x121 => AidexResponse::StartTime { time: decode_local_start_time(body)? },
        0x122 => {
            // `lastId` (u16) precedes stripped CRC — last 2 bytes here (CGM.md §6 [len-4:len-2]).
            if pt.len() < 4 {
                return Err(CoreError::Decode { reason: "lastId response too short".into() });
            }
            let n = pt.len();
            AidexResponse::LastId { last_id: le16(&pt[n - 2..n]) as i32 }
        }
        0x123 => {
            if body.len() < 2 {
                return Err(CoreError::Decode { reason: "history response too short".into() });
            }
            let start_id = le16(&body[0..2]) as i32;
            let entries = body[2..]
                .chunks_exact(2)
                .enumerate()
                .map(|(i, e)| history_glucose(le16(e), start_id + i as i32))
                .collect();
            AidexResponse::History { start_id, entries }
        }
        0x120 | 0x131 | 0x134 | 0x135 => AidexResponse::Ack { tag },
        0x1F2 => AidexResponse::Disconnect { success: true },
        0x0F2 => AidexResponse::Disconnect { success: false },
        _ => AidexResponse::Unknown { tag, payload: pt.to_vec() },
    })
}

/// A `0x110` device-info payload: tag at `[0..2]`, name at `[10..20]`.
fn parse_device_info(pt: &[u8]) -> Result<AidexDeviceInfo, CoreError> {
    if pt.len() < 10 {
        return Err(CoreError::Decode { reason: format!("deviceInfo too short: {} bytes", pt.len()) });
    }
    let model1 = le16(&pt[2..4]) as i32;
    let v = [pt[4] as i32, pt[5] as i32, pt[6] as i32, pt[7] as i32];
    let life_days = pt[8] as i32;
    let model2 = pt[9] as i32;
    let name_bytes: Vec<u8> = pt
        .iter()
        .skip(10)
        .take(10)
        .copied()
        .take_while(|&c| c != 0)
        .collect();
    let name = String::from_utf8_lossy(&name_bytes).trim_end().to_string();
    Ok(AidexDeviceInfo {
        model1,
        firmware: format!("{}.{}.{}.{}", v[0], v[1], v[2], v[3]),
        version: v.to_vec(),
        life_days,
        model2,
        name,
    })
}

fn cmd_simple(sess: &[u8], iv: &[u8], opcode: u8) -> Result<Vec<u8>, CoreError> {
    encrypt_frame(sess, iv, &[opcode])
}

fn cmd_flag(sess: &[u8], iv: &[u8], opcode: u8) -> Result<Vec<u8>, CoreError> {
    encrypt_frame(sess, iv, &[opcode, 0x01])
}

/// The serial's per-character `snval` values (CGM.md §4.3).
#[uniffi::export]
pub fn aidex_snval(serial: String) -> Result<Vec<i32>, CoreError> {
    Ok(snvals(&serial)?.into_iter().map(|v| v as i32).collect())
}

/// The fixed AES IV derived from the serial (CGM.md §4.3), 16 bytes.
#[uniffi::export]
pub fn aidex_iv(serial: String) -> Result<Vec<u8>, CoreError> {
    Ok(derive_iv(&serial)?.to_vec())
}

/// The `askKey` written to `0xF001` to open the handshake (CGM.md §4.3), 16 bytes.
#[uniffi::export]
pub fn aidex_askkey(serial: String) -> Result<Vec<u8>, CoreError> {
    Ok(derive_askkey(&serial)?.to_vec())
}

/// Derive `sess` (CGM.md §4.4): CFB-decrypt 17-byte `0xF002` blob under masterkey+IV, verify CRC.
#[uniffi::export]
pub fn aidex_derive_session(
    serial: String,
    masterkey: Vec<u8>,
    blob: Vec<u8>,
) -> Result<Vec<u8>, CoreError> {
    if masterkey.len() != BLOCK {
        return Err(CoreError::Decode {
            reason: format!("masterkey must be {BLOCK} bytes, got {}", masterkey.len()),
        });
    }
    if blob.len() != SESSION_BLOB_LEN {
        return Err(CoreError::Decode {
            reason: format!("session blob must be {SESSION_BLOB_LEN} bytes, got {}", blob.len()),
        });
    }
    let iv = derive_iv(&serial)?;
    let mut key = [0u8; BLOCK];
    key.copy_from_slice(&masterkey);
    let session_key = aes128_cfb(&key, &iv, &blob, Dir::Decrypt); // 17 bytes
    let sess = &session_key[0..BLOCK];
    let want = crc8_maxim(sess);
    if want != session_key[BLOCK] {
        return Err(CoreError::Decode {
            reason: format!("session-key crc8 mismatch: want {want:#04x} got {:#04x}", session_key[BLOCK]),
        });
    }
    Ok(sess.to_vec())
}

/// `payload || le16(crc16)` — the unencrypted framed plaintext (CGM.md §4.5).
#[uniffi::export]
pub fn aidex_frame_plaintext(payload: Vec<u8>) -> Vec<u8> {
    frame_plaintext(&payload)
}

/// Append CRC16 and CFB-encrypt under `sess`/`iv` (CGM.md §4.5).
#[uniffi::export]
pub fn aidex_encrypt_frame(sess: Vec<u8>, iv: Vec<u8>, payload: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    encrypt_frame(&sess, &iv, &payload)
}

/// CFB-decrypt, verify and strip the trailing CRC16 (CGM.md §4.5).
#[uniffi::export]
pub fn aidex_decrypt_frame(sess: Vec<u8>, iv: Vec<u8>, ct: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    decrypt_frame(&sess, &iv, &ct)
}

/// `deviceInfo` (0x10) — plaintext `10 c1 f3` (CGM.md §5).
#[uniffi::export]
pub fn aidex_cmd_device_info(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_simple(&sess, &iv, 0x10)
}

/// `getBroadcast` (0x11) — plaintext `11 e0 e3` (CGM.md §5).
#[uniffi::export]
pub fn aidex_cmd_get_broadcast(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_simple(&sess, &iv, 0x11)
}

/// `getStartTime` (0x21) — plaintext `21 b3 d5` (CGM.md §5).
#[uniffi::export]
pub fn aidex_cmd_get_start_time(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_simple(&sess, &iv, 0x21)
}

/// `getLastId` (0x22) — plaintext `22 d0 e5` (CGM.md §5).
#[uniffi::export]
pub fn aidex_cmd_get_last_id(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_simple(&sess, &iv, 0x22)
}

/// `getHistory` (0x23) from `rel_id`, a u16 (CGM.md §5/§6).
#[uniffi::export]
pub fn aidex_cmd_get_history(sess: Vec<u8>, iv: Vec<u8>, rel_id: i32) -> Result<Vec<u8>, CoreError> {
    if !(0..=0xFFFF).contains(&rel_id) {
        return Err(CoreError::Decode { reason: format!("rel_id out of u16 range: {rel_id}") });
    }
    let mut payload = vec![0x23u8];
    payload.extend_from_slice(&(rel_id as u16).to_le_bytes());
    encrypt_frame(&sess, &iv, &payload)
}

/// `setAutoUpdate` (0x34) — plaintext `34 01 7f c4` (CGM.md §5).
#[uniffi::export]
pub fn aidex_cmd_set_auto_update(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_flag(&sess, &iv, 0x34)
}

/// `setDynamicAdvMode` (0x35) — plaintext `35 01 4e f7` (CGM.md §5).
#[uniffi::export]
pub fn aidex_cmd_set_dynamic_adv_mode(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_flag(&sess, &iv, 0x35)
}

/// 0x31: never sent; activation starts at 0x20 (CGM.md §5). Plaintext `31 01 8a 3b`.
#[uniffi::export]
pub fn aidex_cmd_prepare_new_sensor(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_flag(&sess, &iv, 0x31)
}

/// `setNewSensor` (0x20) — `local_start` is the 9-byte `LocalStartTime` (CGM.md §7).
#[uniffi::export]
pub fn aidex_cmd_set_new_sensor(sess: Vec<u8>, iv: Vec<u8>, local_start: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    if local_start.len() != LOCAL_START_TIME_LEN {
        return Err(CoreError::Decode {
            reason: format!("LocalStartTime must be {LOCAL_START_TIME_LEN} bytes, got {}", local_start.len()),
        });
    }
    let mut payload = vec![0x20u8];
    payload.extend_from_slice(&local_start);
    encrypt_frame(&sess, &iv, &payload)
}

/// 0xF2: disconnect, bond kept (CGM.md §5, §2.d). Plaintext `f2 ad 2e`.
#[uniffi::export]
pub fn aidex_cmd_disconnect(sess: Vec<u8>, iv: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    cmd_simple(&sess, &iv, 0xF2)
}

/// Encode a wall-clock local time + tz/dst into the 9-byte `LocalStartTime` (CGM.md §7).
#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn aidex_encode_local_start_time(
    year: i32,
    month: i32,
    day: i32,
    hour: i32,
    minute: i32,
    second: i32,
    tz_quarter_hours: i32,
    dst_quarter_hours: i32,
) -> Result<Vec<u8>, CoreError> {
    encode_local_start_time(year, month, day, hour, minute, second, tz_quarter_hours, dst_quarter_hours)
}

/// Decode a 9-byte `LocalStartTime` into fields + UTC epoch (CGM.md §7).
#[uniffi::export]
pub fn aidex_decode_local_start_time(bytes: Vec<u8>) -> Result<AidexLocalStartTime, CoreError> {
    decode_local_start_time(&bytes)
}

/// Parse the decrypted 15-byte realtime `CurrentGlucose` from `0xF003`.
#[uniffi::export]
pub fn aidex_parse_realtime(plaintext: Vec<u8>) -> Result<AidexRealtime, CoreError> {
    parse_realtime(&plaintext)
}

/// A decrypted `0xF002` payload from `aidex_decrypt_frame` (CGM.md §6).
#[uniffi::export]
pub fn aidex_parse_response(plaintext: Vec<u8>) -> Result<AidexResponse, CoreError> {
    parse_response(&plaintext)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        s.split_whitespace().map(|b| u8::from_str_radix(b, 16).unwrap()).collect()
    }

    fn tohex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect::<Vec<_>>().join("")
    }

    #[test]
    fn snval_golden() {
        assert_eq!(aidex_snval("00000T1DM0".into()).unwrap(), vec![0, 0, 0, 0, 0, 29, 1, 13, 22, 0]);
    }

    #[test]
    fn snval_rejects_bad_char() {
        assert!(aidex_snval("0000-T1DM0".into()).is_err());
        assert!(aidex_snval("".into()).is_err());
    }

    const SERIAL: &str = "00000T1DM1";
    const IV_HEX: &str = "47 8C E9 1D 19 6C F6 CD CE F5 62 A3 BF 93 6A 04";
    const ASKKEY_HEX: &str = "B7 92 60 F2 0C 5F F2 05 12 C0 7C D7 A0 CC 08 0F";
    const MASTERKEY_HEX: &str = "00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F";
    const BLOB_HEX: &str = "AA 5C 7C E5 1D 0C 86 DB 1B 91 72 8A B9 5B 3E 63 E0";
    const SESS_HEX: &str = "0F 1E 2D 3C 4B 5A 69 78 87 96 A5 B4 C3 D2 E1 F0";

    #[test]
    fn iv_golden() {
        assert_eq!(tohex(&aidex_iv(SERIAL.into()).unwrap()), tohex(&hex(IV_HEX)));
    }

    #[test]
    fn askkey_golden() {
        assert_eq!(tohex(&aidex_askkey(SERIAL.into()).unwrap()), tohex(&hex(ASKKEY_HEX)));
    }

    #[test]
    fn derive_session_golden() {
        let sess = aidex_derive_session(SERIAL.into(), hex(MASTERKEY_HEX), hex(BLOB_HEX)).unwrap();
        assert_eq!(tohex(&sess), tohex(&hex(SESS_HEX)));
        // crc8 of sess is the blob's 17th byte.
        assert_eq!(crc8_maxim(&sess), 0xA3);
    }

    #[test]
    fn derive_session_rejects_bad_crc() {
        let mut blob = hex(BLOB_HEX);
        blob[16] ^= 0xFF; // corrupt the crc8 byte
        assert!(aidex_derive_session(SERIAL.into(), hex(MASTERKEY_HEX), blob).is_err());
    }

    #[test]
    fn derive_session_rejects_bad_sizes() {
        assert!(aidex_derive_session(SERIAL.into(), vec![0; 15], hex(BLOB_HEX)).is_err());
        assert!(aidex_derive_session(SERIAL.into(), hex(MASTERKEY_HEX), vec![0; 16]).is_err());
    }

    #[test]
    fn encrypt_frame_golden() {
        let sess = hex(SESS_HEX);
        let iv = hex(IV_HEX);
        // crc16(0x10) = 0xF3C1 → `c1 f3`.
        assert_eq!(tohex(&frame_plaintext(&[0x10])), "10c1f3");
        let ct = aidex_encrypt_frame(sess.clone(), iv.clone(), vec![0x10]).unwrap();
        assert_eq!(tohex(&ct), "50f314");
        assert_eq!(tohex(&aidex_cmd_device_info(sess, iv).unwrap()), "50f314");
    }

    #[test]
    fn decrypt_frame_inverts_encrypt_golden() {
        let sess = hex(SESS_HEX);
        let iv = hex(IV_HEX);
        let payload = aidex_decrypt_frame(sess, iv, hex("50 f3 14")).unwrap();
        assert_eq!(tohex(&payload), "10");
    }

    #[test]
    fn command_plaintexts_match_table() {
        assert_eq!(tohex(&frame_plaintext(&[0x10])), "10c1f3"); // deviceInfo
        assert_eq!(tohex(&frame_plaintext(&[0x11])), "11e0e3"); // getBroadcast
        assert_eq!(tohex(&frame_plaintext(&[0x21])), "21b3d5"); // getStartTime
        assert_eq!(tohex(&frame_plaintext(&[0x22])), "22d0e5"); // getLastId
        assert_eq!(tohex(&frame_plaintext(&[0x31, 0x01])), "31018a3b"); // prepareNewSensor
        assert_eq!(tohex(&frame_plaintext(&[0x34, 0x01])), "34017fc4"); // setAutoUpdate
        assert_eq!(tohex(&frame_plaintext(&[0x35, 0x01])), "35014ef7"); // setDynamicAdvMode
        assert_eq!(tohex(&frame_plaintext(&[0xF2])), "f2ad2e"); // disconnect
    }

    #[test]
    fn command_builders_roundtrip_to_payload() {
        let sess = hex(SESS_HEX);
        let iv = hex(IV_HEX);
        let cases: Vec<(Vec<u8>, Vec<u8>)> = vec![
            (aidex_cmd_device_info(sess.clone(), iv.clone()).unwrap(), vec![0x10]),
            (aidex_cmd_get_broadcast(sess.clone(), iv.clone()).unwrap(), vec![0x11]),
            (aidex_cmd_get_start_time(sess.clone(), iv.clone()).unwrap(), vec![0x21]),
            (aidex_cmd_get_last_id(sess.clone(), iv.clone()).unwrap(), vec![0x22]),
            (aidex_cmd_set_auto_update(sess.clone(), iv.clone()).unwrap(), vec![0x34, 0x01]),
            (aidex_cmd_set_dynamic_adv_mode(sess.clone(), iv.clone()).unwrap(), vec![0x35, 0x01]),
            (aidex_cmd_prepare_new_sensor(sess.clone(), iv.clone()).unwrap(), vec![0x31, 0x01]),
            (aidex_cmd_disconnect(sess.clone(), iv.clone()).unwrap(), vec![0xF2]),
        ];
        for (ct, want_payload) in cases {
            let got = aidex_decrypt_frame(sess.clone(), iv.clone(), ct).unwrap();
            assert_eq!(got, want_payload);
        }
        // getHistory carries a u16 relID.
        let hct = aidex_cmd_get_history(sess.clone(), iv.clone(), 0x0102).unwrap();
        assert_eq!(aidex_decrypt_frame(sess.clone(), iv.clone(), hct).unwrap(), vec![0x23, 0x02, 0x01]);
        assert!(aidex_cmd_get_history(sess.clone(), iv.clone(), 0x1_0000).is_err());
    }

    #[test]
    fn set_new_sensor_wraps_local_start_time() {
        let sess = hex(SESS_HEX);
        let iv = hex(IV_HEX);
        let lst = hex("EA 07 02 0F 0C 21 36 04 00");
        let ct = aidex_cmd_set_new_sensor(sess.clone(), iv.clone(), lst.clone()).unwrap();
        let payload = aidex_decrypt_frame(sess, iv, ct).unwrap();
        assert_eq!(payload[0], 0x20);
        assert_eq!(&payload[1..], &lst[..]);
        assert_eq!(payload.len(), 10);
    }

    #[test]
    fn frame_roundtrip_all_lengths() {
        let sess = hex(SESS_HEX);
        let iv = hex(IV_HEX);
        for len in 0..40usize {
            let p: Vec<u8> = (0..len).map(|i| (i as u8).wrapping_mul(37).wrapping_add(5)).collect();
            let ct = aidex_encrypt_frame(sess.clone(), iv.clone(), p.clone()).unwrap();
            let back = aidex_decrypt_frame(sess.clone(), iv.clone(), ct).unwrap();
            assert_eq!(back, p, "roundtrip failed at len {len}");
        }
    }

    #[test]
    fn decrypt_frame_rejects_tamper_and_runt() {
        let sess = hex(SESS_HEX);
        let iv = hex(IV_HEX);
        let mut ct = aidex_encrypt_frame(sess.clone(), iv.clone(), vec![1, 2, 3, 4]).unwrap();
        ct[0] ^= 0x01; // flip a ciphertext bit → CRC fails after decrypt
        assert!(aidex_decrypt_frame(sess.clone(), iv.clone(), ct).is_err());
        assert!(aidex_decrypt_frame(sess.clone(), iv.clone(), vec![0x00]).is_err()); // < 2 bytes
        assert!(aidex_encrypt_frame(vec![0; 15], iv.clone(), vec![1]).is_err()); // bad key len
    }

    #[test]
    fn local_start_time_golden() {
        let t = aidex_decode_local_start_time(hex("EA 07 02 0F 0C 21 36 04 00")).unwrap();
        assert_eq!((t.year, t.month, t.day), (2026, 2, 15));
        assert_eq!((t.hour, t.minute, t.second), (12, 33, 54));
        assert_eq!((t.tz_quarter_hours, t.dst_quarter_hours), (4, 0)); // UTC+1, dst 0
        // 2026-02-15 12:33:54 local (UTC+1) == 11:33:54 UTC.
        assert_eq!(t.epoch_secs, 1_771_155_234);
    }

    #[test]
    fn local_start_time_encode_roundtrips() {
        let enc = aidex_encode_local_start_time(2026, 2, 15, 12, 33, 54, 4, 0).unwrap();
        assert_eq!(tohex(&enc), tohex(&hex("EA 07 02 0F 0C 21 36 04 00")));
        let back = aidex_decode_local_start_time(enc).unwrap();
        assert_eq!(back.epoch_secs, 1_771_155_234);
        // A negative tz (west of UTC) round-trips through the i8 byte.
        let west = aidex_encode_local_start_time(2026, 7, 4, 9, 0, 0, -20, 0).unwrap();
        assert_eq!(west[7], (-20i8) as u8);
        assert_eq!(aidex_decode_local_start_time(west).unwrap().tz_quarter_hours, -20);
    }

    #[test]
    fn local_start_time_rejects_bad_fields() {
        assert!(aidex_encode_local_start_time(2026, 13, 1, 0, 0, 0, 0, 0).is_err()); // month
        assert!(aidex_encode_local_start_time(2026, 1, 1, 24, 0, 0, 0, 0).is_err()); // hour
        assert!(aidex_encode_local_start_time(70000, 1, 1, 0, 0, 0, 0, 0).is_err()); // year
        assert!(aidex_decode_local_start_time(vec![0; 8]).is_err()); // short
    }

    #[test]
    fn realtime_parse_golden() {
        // bitfield = 115 | (1<<15) = 0x8073, LE `73 80`.
        let pt = hex("01 00 00 08 8a 05 73 80 00 00 00 00 00 00 00");
        let r = aidex_parse_realtime(pt).unwrap();
        assert_eq!(r.reading_type, 1);
        assert_eq!(r.trend_tenths_per_min, 8);
        assert_eq!(r.min_from_start, 1418);
        assert_eq!(r.glucose_mgdl, 115);
        assert!(r.valid && r.is_real && !r.warmup);
    }

    #[test]
    fn realtime_parse_warmup_and_invalid() {
        let warm = 100u16 | (1 << 10) | (1 << 15);
        let mut pt = vec![0x01, 0, 0, 0, 0, 0];
        pt.extend_from_slice(&warm.to_le_bytes());
        pt.extend_from_slice(&[0u8; 7]);
        let r = aidex_parse_realtime(pt).unwrap();
        assert!(r.warmup && r.valid && r.is_real && r.glucose_mgdl == 100);
        // glucose 10 (< GLUCOSE_MIN) ⇒ valid but not real.
        let low = 10u16 | (1 << 15);
        let mut pt2 = vec![0x03, 0, 0, 0, 0, 0];
        pt2.extend_from_slice(&low.to_le_bytes());
        pt2.extend_from_slice(&[0u8; 7]);
        let r2 = aidex_parse_realtime(pt2).unwrap();
        assert_eq!(r2.reading_type, 3); // sensor ended
        assert!(r2.valid && !r2.is_real);
        assert!(aidex_parse_realtime(vec![0; 14]).is_err()); // short
    }

    #[test]
    fn history_parse_golden() {
        // tag 0x123 (`23 01`), startId 1000 (`E8 03`), then three samples.
        let s0 = 118u16 | (1 << 15);
        let s1 = 92u16 | (1 << 10) | (1 << 15);
        let s2 = 0u16;
        let mut pt = hex("23 01 E8 03");
        for s in [s0, s1, s2] {
            pt.extend_from_slice(&s.to_le_bytes());
        }
        match aidex_parse_response(pt).unwrap() {
            AidexResponse::History { start_id, entries } => {
                assert_eq!(start_id, 1000);
                assert_eq!(entries.len(), 3);
                assert_eq!(entries[0].record_id, 1000);
                assert_eq!(entries[0].glucose_mgdl, 118);
                assert!(entries[0].valid && entries[0].is_real && !entries[0].warmup);
                assert_eq!(entries[1].record_id, 1001);
                assert_eq!(entries[1].glucose_mgdl, 92);
                assert!(entries[1].warmup && entries[1].is_real);
                assert_eq!(entries[2].record_id, 1002);
                assert!(!entries[2].valid && !entries[2].is_real);
            }
            other => panic!("expected History, got {other:?}"),
        }
    }

    #[test]
    fn response_current_via_decrypt() {
        let mut payload = hex("11 01"); // tag
        payload.extend_from_slice(&hex("8A 05 00 00 08 73 80 63")); // LastPast
        let sess = hex(SESS_HEX);
        let iv = hex(IV_HEX);
        let ct = aidex_encrypt_frame(sess.clone(), iv.clone(), payload).unwrap();
        let pt = aidex_decrypt_frame(sess, iv, ct).unwrap();
        match aidex_parse_response(pt).unwrap() {
            AidexResponse::Current { reading } => {
                assert_eq!(reading.min_from_start, 1418);
                assert_eq!(reading.trend_tenths_per_min, 8);
                assert_eq!(reading.glucose_mgdl, 115);
                assert!(reading.valid);
                assert_eq!(reading.quality, 0x63);
            }
            other => panic!("expected Current, got {other:?}"),
        }
    }

    #[test]
    fn response_start_time() {
        let mut pt = hex("21 01");
        pt.extend_from_slice(&hex("EA 07 02 0F 0C 21 36 04 00"));
        match aidex_parse_response(pt).unwrap() {
            AidexResponse::StartTime { time } => assert_eq!(time.epoch_secs, 1_771_155_234),
            other => panic!("expected StartTime, got {other:?}"),
        }
    }

    #[test]
    fn response_last_id() {
        // 0x122: u32 mid 0x00010001, then u16 lastId (0x0457) as the last two bytes.
        let pt = hex("22 01 01 00 01 00 57 04");
        match aidex_parse_response(pt).unwrap() {
            AidexResponse::LastId { last_id } => assert_eq!(last_id, 0x0457),
            other => panic!("expected LastId, got {other:?}"),
        }
    }

    #[test]
    fn response_device_info() {
        let mut pt = hex("10 01 02 01 01 08 02 00 0F 07");
        pt.extend_from_slice(b"GX-01S");
        pt.extend_from_slice(&[0, 0, 0, 0]); // NUL padding to name[10]
        match aidex_parse_response(pt).unwrap() {
            AidexResponse::DeviceInfo { info } => {
                assert_eq!(info.model1, 0x0102);
                assert_eq!(info.firmware, "1.8.2.0");
                assert_eq!(info.version, vec![1, 8, 2, 0]);
                assert_eq!(info.life_days, 15);
                assert_eq!(info.model2, 0x07);
                assert_eq!(info.name, "GX-01S");
            }
            other => panic!("expected DeviceInfo, got {other:?}"),
        }
    }

    #[test]
    fn response_acks_and_results() {
        assert_eq!(aidex_parse_response(hex("20 01")).unwrap(), AidexResponse::Ack { tag: 0x120 });
        assert_eq!(aidex_parse_response(hex("31 01")).unwrap(), AidexResponse::Ack { tag: 0x131 });
        assert_eq!(aidex_parse_response(hex("34 01")).unwrap(), AidexResponse::Ack { tag: 0x134 });
        assert_eq!(aidex_parse_response(hex("35 01")).unwrap(), AidexResponse::Ack { tag: 0x135 });
        assert_eq!(aidex_parse_response(hex("F2 01")).unwrap(), AidexResponse::Disconnect { success: true });
        assert_eq!(aidex_parse_response(hex("F2 00")).unwrap(), AidexResponse::Disconnect { success: false });
        match aidex_parse_response(hex("99 01 DE AD")).unwrap() {
            AidexResponse::Unknown { tag, payload } => {
                assert_eq!(tag, 0x199);
                assert_eq!(tohex(&payload), "9901dead");
            }
            other => panic!("expected Unknown, got {other:?}"),
        }
        assert!(aidex_parse_response(vec![0x11]).is_err()); // < 2 bytes
    }

    #[test]
    fn parsers_never_panic_on_garbage() {
        let mut state: u64 = 0x0BAD_F00D_DEAD_BEEF;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        for _ in 0..50_000 {
            let len = (next() % 40) as usize;
            let p: Vec<u8> = (0..len).map(|_| next() as u8).collect();
            let _ = aidex_parse_response(p.clone());
            let _ = aidex_parse_realtime(p.clone());
            let _ = aidex_decode_local_start_time(p.clone());
            let _ = aidex_decrypt_frame(hex(SESS_HEX), hex(IV_HEX), p);
        }
    }
}
