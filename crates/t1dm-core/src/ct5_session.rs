//! CT5 wire codec. Key recoverable only up to (k, k^0xFF); wrong decoder answers confidently.

use crate::CoreError;

/// Sum of `b[i..=j]`, byte-truncated. 0 out of range, as the vendor does.
fn receive_sum(b: &[u8], i: usize, j: usize) -> u8 {
    if j >= b.len() || i > j {
        return 0;
    }
    b[i..=j].iter().fold(0u8, |acc, &x| acc.wrapping_add(x))
}

/// `[opcode][payload…][checksum]`: no delimiters, no length field. The length is the ATT PDU's.
pub fn is_legal(b: &[u8]) -> bool {
    b.len() > 1 && b[b.len() - 1] == receive_sum(b, 0, b.len() - 2)
}

pub fn build_frame(opcode: u8, payload: &[u8]) -> Vec<u8> {
    let mut f = Vec::with_capacity(payload.len() + 2);
    f.push(opcode);
    f.extend_from_slice(payload);
    let sum = receive_sum(&f, 0, f.len() - 1);
    f.push(sum);
    f
}

/// Degenerates to `{op, 0x55, 0xAA, op-1}`: `55 AA` is not a magic word.
pub fn build_filler_request(opcode: u8) -> Vec<u8> {
    build_frame(opcode, &[0x55, 0xAA])
}

fn to_bits(bytes: &[u8]) -> Vec<u8> {
    let mut bits = Vec::with_capacity(bytes.len() * 8);
    for &b in bytes {
        for s in (0..8).rev() {
            bits.push((b >> s) & 1);
        }
    }
    bits
}

fn from_bits(bits: &[u8]) -> Vec<u8> {
    bits.chunks(8)
        .map(|c| c.iter().fold(0u8, |acc, &bit| (acc << 1) | bit))
        .collect()
}

/// Applied to a payload as received; the bit stream is continuous, byte n depends on byte n+1.
pub fn deobfuscate(input: &[u8], k: u8) -> Vec<u8> {
    if input.is_empty() {
        return Vec::new();
    }
    let bits = to_bits(&input.iter().map(|b| b ^ k).collect::<Vec<_>>());
    let n = bits.len();
    let mut out = Vec::with_capacity(n);
    // Reads the ORIGINAL neighbour, never a rewritten one.
    for i in 0..n - 1 {
        out.push(u8::from(bits[i] == bits[i + 1]));
    }
    out.push(bits[n - 1]);
    from_bits(&out)
}

/// Applied to a payload being BUILT. Exact inverse of [`deobfuscate`].
pub fn obfuscate(input: &[u8], k: u8) -> Vec<u8> {
    if input.is_empty() {
        return Vec::new();
    }
    let mut bits = to_bits(input);
    let n = bits.len();
    // Descending, and reads the ALREADY-REWRITTEN neighbour — the dependency runs from the end.
    for i in (0..n - 1).rev() {
        if bits[i + 1] == 0 {
            bits[i] ^= 1;
        }
    }
    from_bits(&bits).iter().map(|b| b ^ k).collect()
}

/// The two keys that produce identical plaintext everywhere except the region's final bit.
pub fn key_pair(k: u8) -> (u8, u8) {
    (k, k ^ 0xFF)
}

/// The two vendor parsers disagree, and the wrong order is silently wrong.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TempOrder {
    FractionFirst,
    IntegerFirst,
}

/// Every quantity an EXACT integer in the wire's own resolution; the `f32` views are accessors.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ct5Record {
    /// Background current, hundredths of a µA. Reads 0 in practice.
    pub ib_x100: u16,
    /// Working-electrode current, hundredths of a µA.
    pub iw_x100: u16,
    /// Centi-degrees Celsius.
    pub temp_c_x100: i32,
    pub trend_code: u8,
    /// Natively mg/dL. `None` where the field is zero: zero is ABSENT, never 0 mg/dL.
    pub glucose_mgdl: Option<u16>,
    pub error_code: u8,
    /// BE/WE/RE/CE electrode potentials in mV, present only in the 15-byte form.
    pub electrodes_mv: Option<[u16; 4]>,
    /// Battery, unscaled; 15-byte form only.
    pub battery: Option<u16>,
}

impl Ct5Record {
    pub fn ib(&self) -> f32 {
        f32::from(self.ib_x100) / 100.0
    }

    pub fn iw(&self) -> f32 {
        f32::from(self.iw_x100) / 100.0
    }

    pub fn temp_c(&self) -> f32 {
        self.temp_c_x100 as f32 / 100.0
    }
}

/// `Iw <= 1.0` regardless of `K0`; the other two gates are normalised by it.
const MIN_CURRENT: f32 = 1.0;
const TAKE_OFF_RATIO: f32 = 0.6;
const FLOODING_RATIO: f32 = 57.6;

/// Above this the temperature compensation is switched off entirely, a 4.2% step.
const NO_COMPENSATION_CURRENT: f32 = 50.0;

/// Nothing emits until here whatever the lifetime code; vendor's warmup=15, gate fires 1 early.
const ALGO_WARMUP_SAMPLES: i32 = 14;

/// Past this the vendor wipes its state and repeats the last reading forever.
const STATE_CAP_SAMPLES: i32 = 10_095;

/// Vendor conversion (its display); wire glucose is a plain Iw multiple, discarded here.
pub fn glucose_from_current(
    iw_x100: i32,
    temp_c_x100: i32,
    k_x100: i32,
    glucose_id: i32,
) -> Option<i32> {
    if k_x100 <= 0 || !(ALGO_WARMUP_SAMPLES..STATE_CAP_SAMPLES).contains(&glucose_id) {
        return None;
    }
    let iw = iw_x100 as f32 / 100.0;
    let k0 = k_x100 as f32 / 100.0;
    let normalised = iw / k0;
    if iw <= MIN_CURRENT || normalised <= TAKE_OFF_RATIO || normalised > FLOODING_RATIO {
        return None;
    }

    let factor = if iw > NO_COMPENSATION_CURRENT {
        1.0
    } else {
        let tc = (temp_c_x100 as f32 / 100.0).clamp(12.0, 48.0);
        let g = if tc > 39.4 {
            0.55
        } else if tc > 36.4 {
            4.35 / (tc - 32.0)
        } else if tc > 32.0 {
            0.2 * (tc - 32.0) / 4.4 + 0.8
        } else {
            1.0
        };
        1.0 + (tc - 32.0) * -0.045_929_998_159_408_569 * g
    };

    let mut sensitivity = 1.2 * k0;
    if glucose_id <= 479 {
        sensitivity *= 0.9 + glucose_id as f32 * 0.1 / 480.0;
    }
    let mmol = iw * factor / sensitivity.clamp(0.5 * k0, 2.5 * k0);

    Some(match mmol {
        m if m > 27.8 => 500,
        m if m < 1.7 => 31,
        m => (m * 18.0 + 0.5) as i32,
    })
}

pub const RECORD_SHORT: usize = 11;
pub const RECORD_VOLTAGE: usize = 15;

/// The 11-byte short form and the 15-byte voltage form are identical through offset 8.
pub fn decode_record(rec: &[u8], order: TempOrder) -> Option<Ct5Record> {
    if rec.len() != RECORD_SHORT && rec.len() != RECORD_VOLTAGE {
        return None;
    }
    let be = |hi: u8, lo: u8| u16::from(hi) << 8 | u16::from(lo);

    let (frac, int) = match order {
        TempOrder::FractionFirst => (rec[4], rec[5]),
        TempOrder::IntegerFirst => (rec[5], rec[4]),
    };
    let glucose = ((u16::from(rec[6]) & 0x0F) << 8) | u16::from(rec[7]);
    let voltage = rec.len() == RECORD_VOLTAGE;

    Some(Ct5Record {
        ib_x100: be(rec[0], rec[1]),
        iw_x100: be(rec[2], rec[3]),
        temp_c_x100: i32::from(frac) + (i32::from(int) - 40) * 100,
        trend_code: rec[6] >> 4,
        glucose_mgdl: (glucose != 0).then_some(glucose),
        error_code: rec[8],
        // One wire byte is 6 mV.
        electrodes_mv: voltage.then(|| {
            [
                u16::from(rec[9]) * 6,
                u16::from(rec[10]) * 6,
                u16::from(rec[11]) * 6,
                u16::from(rec[12]) * 6,
            ]
        }),
        battery: voltage.then(|| be(rec[13], rec[14])),
    })
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AdvertSample {
    pub iw_raw: u16,
    pub temp_raw: u16,
}

pub const ADVERT_BLOCK: usize = 18;
pub const ADVERT_SAMPLES: usize = 6;

pub fn decode_advert_block(block: &[u8], k: u8) -> Option<Vec<AdvertSample>> {
    if block.len() != ADVERT_BLOCK {
        return None;
    }
    let plain = deobfuscate(block, k);
    Some(
        plain
            .chunks_exact(3)
            .map(|c| {
                let w = u32::from(c[0]) << 16 | u32::from(c[1]) << 8 | u32::from(c[2]);
                AdvertSample {
                    iw_raw: (w >> 10) as u16 & 0x3FFF,
                    temp_raw: w as u16 & 0x03FF,
                }
            })
            .collect(),
    )
}

/// market_no/life_time/calibration exist only in 21-char form; ""/0 means "can't say", not zero.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct KrDecodeData {
    /// Scale factor on the working-electrode current.
    pub k: f32,
    pub r: f32,
    /// `k` and `r` in EXACT hundredths: what crosses the FFI and what `0x38` plants in the sensor.
    pub k_x100: i32,
    pub r_x100: i32,
    pub life_time: i32,
    pub calibration: i32,
    pub unit_order: i32,
    pub year: i32,
    pub market_no: String,
    pub electrode_type: String,
    pub serial_no: String,
    pub sensor_no: String,
    pub electrode_tec_no: String,
    pub enzyme_tec_no: String,
    pub membrane_tec_no: String,
}

impl KrDecodeData {
    /// `None` where `k` is zero: a legal decode, and the conversion divides by `K`.
    pub fn usable_k(&self) -> Option<f32> {
        (self.k > 0.0).then_some(self.k)
    }
}

fn is_upper_alnum(b: u8) -> bool {
    b.is_ascii_digit() || b.is_ascii_uppercase()
}

/// Near-Base58, not Base58: `l` and `L` are in, `i` and `o` are out.
fn is_market(b: u8) -> bool {
    matches!(b,
        b'1'..=b'9'
        | b'A'..=b'H' | b'J'..=b'N' | b'P'..=b'Z'
        | b'a'..=b'h' | b'j'..=b'n' | b'p'..=b'z')
}

fn is_serial_pair(a: u8, b: u8) -> bool {
    match a {
        b'0' => matches!(b, b'1'..=b'9'),
        b'1'..=b'9' => b.is_ascii_digit(),
        b'A'..=b'Z' => matches!(b, b'1'..=b'9' | b'A'..=b'Z'),
        _ => false,
    }
}

fn is_counting_triple(t: &[u8]) -> bool {
    t.iter().all(u8::is_ascii_digit) && t.iter().any(|&c| c != b'0')
}

fn matches_long(s: &[u8]) -> bool {
    s.len() == 21
        && ((s[0] == b'0' && s[1] == b'0') || (is_market(s[0]) && matches!(s[1], b'1'..=b'9')))
        && is_upper_alnum(s[2])
        && s[3].is_ascii_digit()
        && is_serial_pair(s[4], s[5])
        && is_counting_triple(&s[6..9])
        && is_counting_triple(&s[9..12])
        && s[12..18].iter().all(u8::is_ascii_digit)
        && s[18..21].iter().all(|&b| is_upper_alnum(b))
}

fn matches_short(s: &[u8]) -> bool {
    let n = s.len();
    (n == 17 || n == 18)
        && is_upper_alnum(s[0])
        && s[1].is_ascii_digit()
        && is_serial_pair(s[2], s[3])
        && is_counting_triple(&s[4..7])
        && is_counting_triple(&s[7..10])
        && s[10..n - 3].iter().all(u8::is_ascii_digit)
        && s[n - 3..].iter().all(|&b| is_upper_alnum(b))
}

/// Decimal value of an already-validated digit run.
fn digits(s: &[u8]) -> i32 {
    s.iter().fold(0, |acc, &c| acc * 10 + i32::from(c - b'0'))
}

fn text(s: &[u8]) -> String {
    String::from_utf8_lossy(s).into_owned()
}

fn decode_long(s: &[u8]) -> KrDecodeData {
    KrDecodeData {
        k: digits(&s[13..16]) as f32 / 100.0,
        r: digits(&s[16..18]) as f32 / 10.0,
        k_x100: digits(&s[13..16]),
        r_x100: digits(&s[16..18]) * 10,
        life_time: digits(&s[1..2]),
        calibration: digits(&s[12..13]),
        unit_order: digits(&s[6..9]),
        year: digits(&s[3..4]),
        market_no: text(&s[0..1]),
        electrode_type: text(&s[2..3]),
        serial_no: text(&s[4..6]),
        sensor_no: text(&s[9..12]),
        electrode_tec_no: text(&s[18..19]),
        enzyme_tec_no: text(&s[19..20]),
        membrane_tec_no: text(&s[20..21]),
    }
}

fn decode_short(s: &[u8]) -> KrDecodeData {
    // K takes a third digit only at 18 characters, and that digit shifts every field behind it.
    let (k, k_x100, p) = if s.len() == 18 {
        (digits(&s[10..13]) as f32 / 100.0, digits(&s[10..13]), 13)
    } else {
        // `d/10.0` and `(d*10)/100.0` are bit-identical; no second rounding.
        (digits(&s[10..12]) as f32 / 10.0, digits(&s[10..12]) * 10, 12)
    };
    KrDecodeData {
        k,
        r: digits(&s[p..p + 2]) as f32 / 10.0,
        k_x100,
        r_x100: digits(&s[p..p + 2]) * 10,
        unit_order: digits(&s[4..7]),
        year: digits(&s[1..2]),
        electrode_type: text(&s[0..1]),
        serial_no: text(&s[2..4]),
        sensor_no: text(&s[7..10]),
        electrode_tec_no: text(&s[p + 2..p + 3]),
        enzyme_tec_no: text(&s[p + 3..p + 4]),
        membrane_tec_no: text(&s[p + 4..p + 5]),
        ..KrDecodeData::default()
    }
}

/// Nothing is normalised first: no trimming, no case folding, no tolerance of a stray space.
pub fn decode_ct(s: &str) -> Option<KrDecodeData> {
    // Truncated at the first NUL, as the vendor's NUL-terminated copy is.
    let b = s.as_bytes();
    let b = &b[..b.iter().position(|&c| c == 0).unwrap_or(b.len())];

    if matches_long(b) {
        Some(decode_long(b))
    } else if matches_short(b) {
        Some(decode_short(b))
    } else {
        None
    }
}

/// All-zero where decode_ct gives None, indistinguishable from a legal zero decode; mirrors vendor.
pub fn decode_ct_vendor(s: &str) -> KrDecodeData {
    decode_ct(s).unwrap_or_default()
}

/// b.len() terms, not full convolution; tail products fall off the end, changing the fold/key.
pub fn convolve(b: &[u8], a: &[u8]) -> Vec<i32> {
    if a.is_empty() || b.is_empty() {
        return Vec::new();
    }
    let mut out = vec![0i32; b.len()];
    for (i, &x) in b.iter().enumerate() {
        for (j, &y) in a.iter().enumerate() {
            match out.get_mut(i + j) {
                Some(slot) => *slot += i32::from(x) * i32::from(y),
                None => break,
            }
        }
    }
    out
}

pub fn xor_fold(v: &[i32]) -> i32 {
    v.iter().fold(0, |acc, &x| acc ^ x)
}

pub const NONCE_LEN: usize = 4;
pub const SET_ID_REPLY_LEN: usize = 10;

/// Fail closed: vendor checks neither length nor echoed B; a bad key persists (0x30 unrepeatable).
pub fn cipher_id_from_set_id_reply(reply: &[u8], a: &[u8], b: &[u8]) -> Option<u8> {
    if reply.len() != SET_ID_REPLY_LEN
        || reply[0] != 0x30
        || !is_legal(reply)
        || a.len() != NONCE_LEN
        || b.len() != NONCE_LEN
        || &reply[1..1 + NONCE_LEN] != b
    {
        return None;
    }
    let tail = &reply[5..SET_ID_REPLY_LEN - 1];
    Some((xor_fold(&convolve(tail, a)) & 0xFF) as u8)
}

/// 0x03 setDate: local wall clock, no tz byte. Cosmetic; readings use no sensor-clock timestamp.
pub fn build_set_date(year: i32, month: i32, day: i32, hour: i32, minute: i32, second: i32) -> Option<Vec<u8>> {
    if !(1900..=2155).contains(&year)
        || !(1..=12).contains(&month)
        || !(1..=31).contains(&day)
        || !(0..=23).contains(&hour)
        || !(0..=59).contains(&minute)
        || !(0..=60).contains(&second)
    {
        return None;
    }
    Some(build_frame(
        0x03,
        &[
            (year - 1900) as u8,
            month as u8,
            day as u8,
            hour as u8,
            minute as u8,
            second as u8,
        ],
    ))
}

/// `0x01` version — a bare opcode: no filler, no checksum.
pub fn build_version_request() -> Vec<u8> {
    vec![0x01]
}

/// `0x05` self-check. Read-only.
pub fn build_self_check() -> Vec<u8> {
    build_filler_request(0x05)
}

/// `0x3F` querySSN. An unbound sensor answers in PLAINTEXT, so no captured secret is needed.
pub fn build_query_ssn() -> Vec<u8> {
    build_filler_request(0x3F)
}

/// 0x06 init, starts the sensor; not 06 55 01 5C, which also checksums and differs only by byte 3.
pub fn build_init() -> Vec<u8> {
    build_filler_request(0x06)
}

/// `0x0F` lowPower. May draw no reply.
pub fn build_low_power() -> Vec<u8> {
    build_filler_request(0x0F)
}

/// 0x35 push ack. Draws no reply; not optional, an unacknowledged push tears the link down.
pub fn build_push_ack() -> Vec<u8> {
    build_filler_request(0x35)
}

/// 0x30 setID, first irreversible frame. A never sent; only convolve(B,A), truncated to 4 bytes.
pub fn build_set_id(b: &[u8], a: &[u8]) -> Option<Vec<u8>> {
    if b.len() != NONCE_LEN || a.len() != NONCE_LEN {
        return None;
    }
    let conv = convolve(b, a);
    let mut payload = Vec::with_capacity(NONCE_LEN * 2);
    payload.extend_from_slice(b);
    payload.extend(conv.iter().take(NONCE_LEN).map(|&v| v as u8));
    Some(build_frame(0x30, &payload))
}

/// 0x31 checkID; B goes in cleartext, sensor returns the verdict, anyone holding B passes.
pub fn build_check_id(b: &[u8]) -> Option<Vec<u8>> {
    if b.len() != NONCE_LEN {
        return None;
    }
    Some(build_frame(0x31, b))
}

pub const SET_PARAMETERS_PAYLOAD: usize = 12;
pub const SET_PARAMETERS_FRAME: usize = 14;
pub const RANDOM_ID_LEN: usize = 4;

/// The 12 plaintext bytes 0x38 carries; key-independent, reviewable before any key exists.
pub fn set_parameters_payload(
    k_x100: i32,
    r_x100: i32,
    interval_min: i32,
    cycle_days: i32,
    random_id: &[u8],
) -> Option<[u8; SET_PARAMETERS_PAYLOAD]> {
    // K is the conversion's divisor; K/R are 3 decimal digits by grammar, 9.99 is the ceiling.
    if !(1..=999).contains(&k_x100)
        || !(0..=999).contains(&r_x100)
        || !(1..=255).contains(&interval_min)
        || !(1..=255).contains(&cycle_days)
        || random_id.len() != RANDOM_ID_LEN
        || !random_id.iter().all(u8::is_ascii_digit)
    {
        return None;
    }
    Some([
        (k_x100 / 100) as u8,
        (k_x100 % 100) as u8,
        (r_x100 / 100) as u8,
        (r_x100 % 100) as u8,
        interval_min as u8,
        cycle_days as u8,
        // switchMagneticState, then diabeticType. Both are fixed on this family.
        0x55,
        0x00,
        random_id[0],
        random_id[1],
        random_id[2],
        random_id[3],
    ])
}

/// 0x38 setParameters; RANDOM_ID is the only unbind password, planted here not by 0x30.
pub fn build_set_parameters(
    k_x100: i32,
    r_x100: i32,
    interval_min: i32,
    cycle_days: i32,
    random_id: &[u8],
    cipher_id: u8,
) -> Option<Vec<u8>> {
    let payload = set_parameters_payload(k_x100, r_x100, interval_min, cycle_days, random_id)?;
    Some(build_frame(0x38, &obfuscate(&payload, cipher_id)))
}

/// Reply is obfuscated, deobfuscates first; a match proves 12 bytes intact, not key agreement.
pub fn verify_set_parameters_echo(
    reply: &[u8],
    k_x100: i32,
    r_x100: i32,
    interval_min: i32,
    cycle_days: i32,
    random_id: &[u8],
    cipher_id: u8,
) -> bool {
    if reply.len() != SET_PARAMETERS_FRAME || !is_legal(reply) {
        return false;
    }
    let Some(want) = set_parameters_payload(k_x100, r_x100, interval_min, cycle_days, random_id) else {
        return false;
    };
    deobfuscate(&reply[1..1 + SET_PARAMETERS_PAYLOAD], cipher_id) == want
}

pub const PUSH_SHORT: usize = RECORD_SHORT + 4;
pub const PUSH_VOLTAGE: usize = RECORD_VOLTAGE + 4;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ct5PushFrame {
    /// The sensor's own sample counter. Reading time is `bindTime + glucose_id * 180 s`.
    pub glucose_id: u16,
    pub record: Ct5Record,
}

/// Temperature order is integer-first, fixed not a param; alternative fits one captured frame.
pub fn parse_push(frame: &[u8], cipher_id: u8) -> Option<Ct5PushFrame> {
    if (frame.len() != PUSH_SHORT && frame.len() != PUSH_VOLTAGE) || frame[0] != 0x35 || !is_legal(frame)
    {
        return None;
    }
    // LITTLE-endian, alone among this protocol's multi-byte fields.
    let glucose_id = u16::from(frame[1]) | (u16::from(frame[2]) << 8);
    let plain = deobfuscate(&frame[3..frame.len() - 1], cipher_id);
    Some(Ct5PushFrame {
        glucose_id,
        record: decode_record(&plain, TempOrder::IntegerFirst)?,
    })
}

/// Opcode, the little-endian start id, and the trailing checksum.
pub const HISTORY_ENVELOPE: usize = 4;

/// The vendor's own ceiling on one batch (`defpackage/rt1.java:45-56`).
pub const HISTORY_MAX_BATCH: u8 = 45;

/// Start id little-endian. count forced to at least 1; zero-count reads as end of store.
pub fn build_pull_history(start_id: u16, count: u8) -> Vec<u8> {
    build_frame(
        0x37,
        &[(start_id & 0xFF) as u8, (start_id >> 8) as u8, count.max(1)],
    )
}

/// Vendor's min(45, (mtu-4)/record_size - 1); the -1 margin is deliberate, firmware caps silently.
pub fn history_batch_size(mtu: usize, record_size: usize) -> u8 {
    if record_size == 0 || mtu <= HISTORY_ENVELOPE {
        return 1;
    }
    let fits = (mtu - HISTORY_ENVELOPE) / record_size;
    u8::try_from(fits.saturating_sub(1).clamp(1, HISTORY_MAX_BATCH as usize)).unwrap_or(1)
}

/// Dialect of a single-record probe reply; a full batch can't settle it (ambiguous body length).
pub fn history_record_size(frame_len: usize) -> Option<usize> {
    match frame_len {
        n if n == HISTORY_ENVELOPE + RECORD_SHORT => Some(RECORD_SHORT),
        n if n == HISTORY_ENVELOPE + RECORD_VOLTAGE => Some(RECORD_VOLTAGE),
        _ => None,
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ct5HistorySample {
    /// The SLOT's id, `start_id + position`. See [`parse_history`] on why it is the position.
    pub glucose_id: u16,
    pub record: Ct5Record,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Ct5HistoryReply {
    pub start_id: u16,
    /// Shorter than [`slots`] wherever the sensor held a gap.
    pub samples: Vec<Ct5HistorySample>,
    /// A full record of `0xFC` was reached; paging past it runs to the end of the id space.
    pub end_of_history: bool,
    /// Slots accounted for (terminator excluded), what a cursor advances by; not samples.len().
    pub slots: u16,
}

/// The all-0xFF slot after deobfuscation; catches it under a key that maps it elsewhere.
fn is_blank_record(r: &Ct5Record) -> bool {
    r.iw_x100 > 65500 && r.ib_x100 > 65500 && r.temp_c_x100 > 21500
}

/// Terminators matched on literal wire bytes pre-deobfuscation; id = slot's position.
pub fn parse_history(frame: &[u8], cipher_id: u8, record_size: usize) -> Option<Ct5HistoryReply> {
    if frame.len() < HISTORY_ENVELOPE || frame[0] != 0x37 || !is_legal(frame) {
        return None;
    }
    if record_size != RECORD_SHORT && record_size != RECORD_VOLTAGE {
        return None;
    }
    let body = &frame[3..frame.len() - 1];
    if body.len() % record_size != 0 {
        return None;
    }
    // LITTLE-endian.
    let start_id = u16::from(frame[1]) | (u16::from(frame[2]) << 8);
    let count = body.len() / record_size;

    let marker = |position: usize, byte: u8| {
        body[position * record_size..(position + 1) * record_size].iter().all(|&b| b == byte)
    };

    // The end-of-store terminator isn't itself a slot; nothing behind it is read.
    let mut slots = count;
    let mut end_of_history = false;
    for position in 0..count {
        if marker(position, 0xFC) {
            slots = position;
            end_of_history = true;
            break;
        }
    }

    let mut samples = Vec::with_capacity(slots);
    let mut run_start = 0usize;
    let mut position = 0usize;
    // Walk the slots, deobfuscating each maximal marker-free span in one pass.
    while position <= slots {
        let breaks = position == slots || marker(position, 0xFF);
        if !breaks {
            position += 1;
            continue;
        }
        if position > run_start {
            let plain = deobfuscate(&body[run_start * record_size..position * record_size], cipher_id);
            for (n, chunk) in plain.chunks_exact(record_size).enumerate() {
                let Some(record) = decode_record(chunk, TempOrder::IntegerFirst) else {
                    continue;
                };
                if is_blank_record(&record) {
                    continue;
                }
                // Saturating: wrapping would file a whole wear's tail at the bind instant.
                samples.push(Ct5HistorySample {
                    glucose_id: start_id.saturating_add((run_start + n) as u16),
                    record,
                });
            }
        }
        position += 1;
        run_start = position;
    }

    Some(Ct5HistoryReply {
        start_id,
        samples,
        end_of_history,
        slots: u16::try_from(slots).unwrap_or(u16::MAX),
    })
}

/// Length and printable-ASCII gates first, so nothing reaches decode_ct that UTF-8 would repair.
fn identity_of(candidate: &[u8]) -> Option<KrDecodeData> {
    if !matches!(candidate.len(), 17 | 18 | 21) || !candidate.iter().all(|&c| (0x20..=0x7E).contains(&c)) {
        return None;
    }
    // Printable ASCII, so this cannot fail.
    decode_ct(std::str::from_utf8(candidate).ok()?)
}

/// Body is frame[1..] including the final byte (data, not checksum, the one such response).
pub fn parse_ssn_response(frame: &[u8], cipher_id: Option<u8>) -> Option<(Vec<u8>, KrDecodeData)> {
    if frame.len() < 2 || frame[0] != 0x3F {
        return None;
    }
    let body = &frame[1..];
    if let Some(k) = cipher_id {
        let plain = deobfuscate(body, k);
        if let Some(kr) = identity_of(&plain) {
            return Some((plain, kr));
        }
    }
    identity_of(body).map(|kr| (body.to_vec(), kr))
}

pub const VERSION_REPLY_LEN: usize = 14;

/// INFORMATIONAL: logged, never gating anything, so rendered leniently rather than refused.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Ct5VersionInfo {
    pub year: i32,
    pub month: i32,
    pub day: i32,
    /// As sent; an ASCII character in every observed build.
    pub protocol_code: i32,
    pub version: String,
    pub algorithm: String,
}

/// Log lines only.
fn render_ascii(b: &[u8]) -> String {
    b.iter()
        .map(|&c| if (0x20..=0x7E).contains(&c) { c as char } else { '?' })
        .collect()
}

/// NO checksum: all thirteen bytes after the opcode are data, so length is the only validation.
pub fn parse_version(reply: &[u8]) -> Option<Ct5VersionInfo> {
    if reply.len() != VERSION_REPLY_LEN || reply[0] != 0x01 {
        return None;
    }
    Some(Ct5VersionInfo {
        year: i32::from(reply[1]) * 100 + i32::from(reply[2]),
        month: i32::from(reply[3]),
        day: i32::from(reply[4]),
        protocol_code: i32::from(reply[5]),
        version: format!("V{}", render_ascii(&reply[6..10])),
        algorithm: render_ascii(&reply[10..14]),
    })
}

pub const SELF_CHECK_REPLY_LEN: usize = 20;

/// EXACTLY 20 bytes with a valid checksum, or the bind aborts.
pub fn parse_self_check(reply: &[u8]) -> bool {
    reply.len() == SELF_CHECK_REPLY_LEN && reply[0] == 0x05 && is_legal(reply)
}

/// The sensor answers `0x04` to a `0x03` request and `0x03` to a `0x04` one, so either is accepted.
pub fn parse_set_date_response(reply: &[u8]) -> bool {
    is_legal(reply) && matches!(reply[0], 0x03 | 0x04)
}

/// The activation success signal. Length unconstrained.
pub fn parse_init_response(reply: &[u8]) -> bool {
    is_legal(reply) && reply[0] == 0x06
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ct5CheckId {
    Accepted,
    Rejected,
    /// Real, not padding: verdict byte [5] is also the checksum at 6 bytes; caller decides meaning.
    Ambiguous,
}

pub fn parse_check_id_response(reply: &[u8]) -> Ct5CheckId {
    if !is_legal(reply) || reply[0] != 0x31 || reply.len() <= 6 {
        return Ct5CheckId::Ambiguous;
    }
    if reply[5] == 1 {
        Ct5CheckId::Accepted
    } else {
        Ct5CheckId::Rejected
    }
}

pub const ADVERT_MFG_LEN: usize = 26;
pub const ADVERT_MFG_SHORT_LEN: usize = 4;
/// Records the 18-byte block holds. The vendor never clamps and walks off the buffer past it.
pub const ADVERT_MAX_RECORDS: usize = 6;

/// The advertisement carries NO glucose: presence and liveness, never a reading.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Ct5Advert {
    /// Flips only after 0x38+0x06, not 0x30; unbound-looking may already hold a CIPHER_ID.
    pub bound: bool,
    /// The telemetry block is populated; it is all `0xFF` until the sensor runs.
    pub running: bool,
    pub record_count: i32,
    pub format_tag: i32,
    pub start_glucose_id: i32,
    /// Sum over [4..25]. Reported, not enforced; a checksum surprise mustn't hide a present sensor.
    pub checksum_valid: bool,
}

/// 26-byte long form and 4-byte short form (category+bound flag, no payload, no checksum).
pub fn parse_advert(mfg: &[u8]) -> Option<Ct5Advert> {
    if mfg.len() < ADVERT_MFG_SHORT_LEN || &mfg[0..3] != b"CGM" {
        return None;
    }
    let bound = mfg[3] == 1;
    if mfg.len() < ADVERT_MFG_LEN {
        return Some(Ct5Advert {
            bound,
            running: false,
            record_count: 0,
            format_tag: 0,
            start_glucose_id: 0,
            checksum_valid: false,
        });
    }
    let block = &mfg[7..7 + ADVERT_BLOCK];
    Some(Ct5Advert {
        bound,
        running: !block.iter().all(|&b| b == 0xFF),
        // Logical shift and a clamp; the vendor's arithmetic shift goes negative past 0x80.
        record_count: usize::from(mfg[4] & 0x0F).min(ADVERT_MAX_RECORDS) as i32,
        format_tag: i32::from(mfg[4] >> 4),
        start_glucose_id: i32::from(mfg[5]) | (i32::from(mfg[6]) << 8),
        checksum_valid: mfg[ADVERT_MFG_LEN - 1] == receive_sum(mfg, 4, ADVERT_MFG_LEN - 2),
    })
}

// Every int i32/i64, byte string Vec<u8>, no Option/floats. Release panic="abort"; export is total.

/// Out of range is refused, never masked into a different, working key.
fn cipher_byte(cipher_id: i32) -> Result<u8, CoreError> {
    u8::try_from(cipher_id).map_err(|_| CoreError::Decode {
        reason: format!("cipher_id out of 0..=255: {cipher_id}"),
    })
}

/// `-1` means no key held yet.
fn optional_cipher_byte(cipher_id: i32) -> Result<Option<u8>, CoreError> {
    if cipher_id < 0 {
        return Ok(None);
    }
    cipher_byte(cipher_id).map(Some)
}

fn decode_err(what: &str) -> CoreError {
    CoreError::Decode { reason: what.to_string() }
}

/// Presence rides a flag not a sentinel; glucose_present==false is warm-up, reaches as absent.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Ct5Push {
    pub glucose_id: i32,
    pub glucose_present: bool,
    pub glucose_mgdl: i32,
    pub trend_code: i32,
    pub error_code: i32,
    /// Centi-degrees Celsius.
    pub temp_c_x100: i32,
    /// Hundredths of the wire's current unit.
    pub ib_x100: i32,
    pub iw_x100: i32,
    pub battery_present: bool,
    pub battery_raw: i32,
    /// BE/WE/RE/CE in mV; EMPTY for the 11-byte short record.
    pub electrodes_mv: Vec<i32>,
}

impl From<Ct5PushFrame> for Ct5Push {
    fn from(p: Ct5PushFrame) -> Self {
        let r = p.record;
        Ct5Push {
            glucose_id: i32::from(p.glucose_id),
            glucose_present: r.glucose_mgdl.is_some(),
            glucose_mgdl: i32::from(r.glucose_mgdl.unwrap_or(0)),
            trend_code: i32::from(r.trend_code),
            error_code: i32::from(r.error_code),
            temp_c_x100: r.temp_c_x100,
            ib_x100: i32::from(r.ib_x100),
            iw_x100: i32::from(r.iw_x100),
            battery_present: r.battery.is_some(),
            battery_raw: i32::from(r.battery.unwrap_or(0)),
            electrodes_mv: r
                .electrodes_mv
                .map(|e| e.iter().map(|&v| i32::from(v)).collect())
                .unwrap_or_default(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Ct5Identity {
    pub ssn: String,
    /// Exact hundredths; K multiplies the whole wear, so it never crosses as a float.
    pub k_x100: i32,
    pub r_x100: i32,
    /// 0 in the 17/18-char forms means "can't say", not "sensor reports zero".
    pub life_time: i32,
    pub calibration: i32,
    pub unit_order: i32,
    pub year: i32,
    pub market_no: String,
    pub electrode_type: String,
    pub serial_no: String,
    pub sensor_no: String,
}

/// LOCAL time, no timezone byte.
#[uniffi::export]
pub fn ct5_build_set_date(
    year: i32,
    month: i32,
    day: i32,
    hour: i32,
    minute: i32,
    second: i32,
) -> Result<Vec<u8>, CoreError> {
    build_set_date(year, month, day, hour, minute, second)
        .ok_or_else(|| decode_err("setDate fields out of range"))
}

#[uniffi::export]
pub fn ct5_glucose_from_current(
    iw_x100: i32,
    temp_c_x100: i32,
    k_x100: i32,
    glucose_id: i32,
) -> Option<i32> {
    glucose_from_current(iw_x100, temp_c_x100, k_x100, glucose_id)
}

#[uniffi::export]
pub fn ct5_build_version_request() -> Vec<u8> {
    build_version_request()
}

#[uniffi::export]
pub fn ct5_build_self_check() -> Vec<u8> {
    build_self_check()
}

#[uniffi::export]
pub fn ct5_build_query_ssn() -> Vec<u8> {
    build_query_ssn()
}

/// IRREVERSIBLE.
#[uniffi::export]
pub fn ct5_build_init() -> Vec<u8> {
    build_init()
}

/// May draw no reply.
#[uniffi::export]
pub fn ct5_build_low_power() -> Vec<u8> {
    build_low_power()
}

/// Draws no reply, and is not optional: see [`build_push_ack`].
#[uniffi::export]
pub fn ct5_build_push_ack() -> Vec<u8> {
    build_push_ack()
}

/// IRREVERSIBLE. `a` never leaves the caller.
#[uniffi::export]
pub fn ct5_build_set_id(b: Vec<u8>, a: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    build_set_id(&b, &a).ok_or_else(|| decode_err("setID nonces must be 4 bytes each"))
}

#[uniffi::export]
pub fn ct5_build_check_id(b: Vec<u8>) -> Result<Vec<u8>, CoreError> {
    build_check_id(&b).ok_or_else(|| decode_err("checkID nonce must be 4 bytes"))
}

/// Key-independent: reviewable before anything is written.
#[uniffi::export]
pub fn ct5_set_parameters_payload(
    k_x100: i32,
    r_x100: i32,
    interval_min: i32,
    cycle_days: i32,
    random_id: String,
) -> Result<Vec<u8>, CoreError> {
    set_parameters_payload(k_x100, r_x100, interval_min, cycle_days, random_id.as_bytes())
        .map(|p| p.to_vec())
        .ok_or_else(|| decode_err("setParameters fields out of range"))
}

/// IRREVERSIBLE; plants the only unbind password.
#[uniffi::export]
pub fn ct5_build_set_parameters(
    k_x100: i32,
    r_x100: i32,
    interval_min: i32,
    cycle_days: i32,
    random_id: String,
    cipher_id: i32,
) -> Result<Vec<u8>, CoreError> {
    let k = cipher_byte(cipher_id)?;
    build_set_parameters(k_x100, r_x100, interval_min, cycle_days, random_id.as_bytes(), k)
        .ok_or_else(|| decode_err("setParameters fields out of range"))
}

/// See [`verify_set_parameters_echo`] for what a match does not prove.
#[uniffi::export]
pub fn ct5_verify_set_parameters_echo(
    reply: Vec<u8>,
    k_x100: i32,
    r_x100: i32,
    interval_min: i32,
    cycle_days: i32,
    random_id: String,
    cipher_id: i32,
) -> Result<bool, CoreError> {
    let k = cipher_byte(cipher_id)?;
    Ok(verify_set_parameters_echo(
        &reply,
        k_x100,
        r_x100,
        interval_min,
        cycle_days,
        random_id.as_bytes(),
        k,
    ))
}

/// `Err` on replies the vendor would accept but that cannot yield a trustworthy key.
#[uniffi::export]
pub fn ct5_cipher_id_from_set_id_reply(
    reply: Vec<u8>,
    a: Vec<u8>,
    b: Vec<u8>,
) -> Result<i32, CoreError> {
    cipher_id_from_set_id_reply(&reply, &a, &b)
        .map(i32::from)
        .ok_or_else(|| decode_err("setID reply rejected: length, opcode, checksum or nonce"))
}

#[uniffi::export]
pub fn ct5_parse_push(frame: Vec<u8>, cipher_id: i32) -> Result<Ct5Push, CoreError> {
    let k = cipher_byte(cipher_id)?;
    parse_push(&frame, k)
        .map(Ct5Push::from)
        .ok_or_else(|| decode_err("not a valid 0x35 push"))
}

/// A key that decodes the frame at all; caller decides which is physical (thresholds elsewhere).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Ct5KeyCandidate {
    pub cipher_id: i32,
    pub push: Ct5Push,
}

/// Every key the frame decodes under; k and k^0xFF always survive together, recovers a bad key.
#[uniffi::export]
pub fn ct5_push_under_every_key(frame: Vec<u8>) -> Vec<Ct5KeyCandidate> {
    (0..=u8::MAX)
        .filter_map(|k| {
            parse_push(&frame, k).map(|p| Ct5KeyCandidate {
                cipher_id: i32::from(k),
                push: Ct5Push::from(p),
            })
        })
        .collect()
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Ct5History {
    pub start_id: i32,
    pub samples: Vec<Ct5Push>,
    /// The store ends here; a reader that pages past it pages to the end of the id space.
    pub end_of_history: bool,
    /// What a cursor advances by, NOT `samples.len()`; see [`Ct5HistoryReply::slots`].
    pub slots: i32,
}

#[uniffi::export]
pub fn ct5_build_pull_history(start_id: i32, count: i32) -> Result<Vec<u8>, CoreError> {
    let id = u16::try_from(start_id)
        .map_err(|_| decode_err("history start id out of 0..=65535"))?;
    let n = u8::try_from(count.clamp(1, i32::from(HISTORY_MAX_BATCH)))
        .map_err(|_| decode_err("history count out of range"))?;
    Ok(build_pull_history(id, n))
}

/// Never zero.
#[uniffi::export]
pub fn ct5_history_batch_size(mtu: i32, record_size: i32) -> i32 {
    let mtu = usize::try_from(mtu).unwrap_or(0);
    let rs = usize::try_from(record_size).unwrap_or(0);
    i32::from(history_batch_size(mtu, rs))
}

/// `0` where the length is neither dialect: an unexpected probe reply is a frame to drop.
#[uniffi::export]
pub fn ct5_history_record_size(frame_len: i32) -> i32 {
    usize::try_from(frame_len)
        .ok()
        .and_then(history_record_size)
        .and_then(|n| i32::try_from(n).ok())
        .unwrap_or(0)
}

#[uniffi::export]
pub fn ct5_parse_history(
    frame: Vec<u8>,
    cipher_id: i32,
    record_size: i32,
) -> Result<Ct5History, CoreError> {
    let k = cipher_byte(cipher_id)?;
    let rs = usize::try_from(record_size).unwrap_or(0);
    let reply = parse_history(&frame, k, rs)
        .ok_or_else(|| decode_err("not a valid 0x37 history batch"))?;
    Ok(Ct5History {
        start_id: i32::from(reply.start_id),
        samples: reply
            .samples
            .into_iter()
            .map(|s| Ct5Push::from(Ct5PushFrame { glucose_id: s.glucose_id, record: s.record }))
            .collect(),
        end_of_history: reply.end_of_history,
        slots: i32::from(reply.slots),
    })
}

/// `cipher_id` of `-1` means no key is held.
#[uniffi::export]
pub fn ct5_parse_ssn_response(frame: Vec<u8>, cipher_id: i32) -> Result<Ct5Identity, CoreError> {
    let k = optional_cipher_byte(cipher_id)?;
    let (ssn, kr) = parse_ssn_response(&frame, k)
        .ok_or_else(|| decode_err("0x3F body decoded to no identity the grammar accepts"))?;
    Ok(Ct5Identity {
        // Printable ASCII only, so this is lossless.
        ssn: String::from_utf8_lossy(&ssn).into_owned(),
        k_x100: kr.k_x100,
        r_x100: kr.r_x100,
        life_time: kr.life_time,
        calibration: kr.calibration,
        unit_order: kr.unit_order,
        year: kr.year,
        market_no: kr.market_no,
        electrode_type: kr.electrode_type,
        serial_no: kr.serial_no,
        sensor_no: kr.sensor_no,
    })
}

#[uniffi::export]
pub fn ct5_parse_version(reply: Vec<u8>) -> Result<Ct5VersionInfo, CoreError> {
    parse_version(&reply).ok_or_else(|| decode_err("version reply must be exactly 14 bytes"))
}

/// `false` must abort a bind.
#[uniffi::export]
pub fn ct5_parse_self_check(reply: Vec<u8>) -> bool {
    parse_self_check(&reply)
}

#[uniffi::export]
pub fn ct5_parse_set_date_response(reply: Vec<u8>) -> bool {
    parse_set_date_response(&reply)
}

#[uniffi::export]
pub fn ct5_parse_init_response(reply: Vec<u8>) -> bool {
    parse_init_response(&reply)
}

/// `1` accepted, `0` rejected, `-1` no verdict readable — see [`Ct5CheckId::Ambiguous`].
#[uniffi::export]
pub fn ct5_parse_check_id_response(reply: Vec<u8>) -> i32 {
    match parse_check_id_response(&reply) {
        Ct5CheckId::Accepted => 1,
        Ct5CheckId::Rejected => 0,
        Ct5CheckId::Ambiguous => -1,
    }
}

#[uniffi::export]
pub fn ct5_parse_advert(mfg: Vec<u8>) -> Result<Ct5Advert, CoreError> {
    parse_advert(&mfg).ok_or_else(|| decode_err("not a CGM manufacturer block"))
}

/// Exposed so the driver needs no second copy of the rule in Kotlin.
#[uniffi::export]
pub fn ct5_frame_is_legal(frame: Vec<u8>) -> bool {
    is_legal(&frame)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checksum_matches_captured_frames() {
        assert!(is_legal(&[0x3B, 0x55, 0xAA, 0x3A]));
        assert!(is_legal(&[0x06, 0x55, 0x01, 0x5C]));
        assert!(is_legal(&[
            0x15, 0x00, 0x28, 0xA0, 0x28, 0xA0, 0x27, 0x10, 0x00, 0x00, 0x00, 0xDC
        ]));
        assert!(!is_legal(&[0x3B, 0x55, 0xAA, 0x3B]));
        assert!(!is_legal(&[0x3B]));
    }

    #[test]
    fn filler_request_degenerates_to_op_minus_one() {
        assert_eq!(build_filler_request(0x3B), vec![0x3B, 0x55, 0xAA, 0x3A]);
        assert_eq!(build_filler_request(0x11), vec![0x11, 0x55, 0xAA, 0x10]);
    }

    #[test]
    fn deobfuscate_matches_the_reference_vector() {
        // k = 0 is not the identity.
        assert_eq!(deobfuscate(&[0x01, 0x02, 0x03], 0), vec![0xFC, 0xF9, 0xFB]);
    }

    #[test]
    fn obfuscate_inverts_deobfuscate_at_every_length_and_key() {
        for len in [1usize, 11, 12, 15, 17, 18] {
            let payload: Vec<u8> = (0..len).map(|i| (i as u8).wrapping_mul(37).wrapping_add(11)).collect();
            for k in 0..=255u8 {
                let round = deobfuscate(&obfuscate(&payload, k), k);
                assert_eq!(round, payload, "len {len} key {k}");
            }
        }
    }

    #[test]
    fn complement_key_differs_only_in_the_final_bit() {
        let payload = [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB];
        for k in 0..=255u8 {
            let (a, b) = key_pair(k);
            let da = deobfuscate(&payload, a);
            let db = deobfuscate(&payload, b);
            let last = da.len() - 1;
            assert_eq!(da[..last], db[..last], "key {k}");
            assert_eq!(da[last] ^ db[last], 1, "key {k}");
        }
    }

    const SWEEPS: &str = include_str!("testdata/ct5_sweeps.csv");

    /// One vendor call per row, constant Iw/T; trustworthy iff positive and error 0 or 5.
    #[test]
    fn conversion_matches_the_vendor_sweep_grid() {
        let mut checked = 0;
        let mut withheld = 0;
        let mut off_lattice = 0;
        for line in SWEEPS.lines().filter(|l| !l.starts_with('#')).skip(1) {
            let f: Vec<&str> = line.split(',').collect();
            // Sweep bisects to adjacent binary32; wire is hundredths, off-lattice untestable.
            let hundredths = |v: &str| {
                let exact: f32 = v.parse().unwrap();
                let x100 = (f64::from(exact) * 100.0).round() as i32;
                (x100 as f32 / 100.0 == exact).then_some(x100)
            };
            let (Some(iw), Some(t), Some(k)) = (hundredths(f[0]), hundredths(f[1]), hundredths(f[2]))
            else {
                off_lattice += 1;
                continue;
            };

            let got = glucose_from_current(iw, t, k, f[3].parse().unwrap());
            let glu: i32 = f[4].parse().unwrap();
            let err: i32 = f[5].parse().unwrap();

            if glu > 0 && (err == 0 || err == 5) {
                let got = got.unwrap_or_else(|| panic!("withheld a good reading: {line}"));
                assert!((got - glu).abs() <= 1, "{line} gave {got}");
                checked += 1;
            } else {
                assert_eq!(got, None, "valued a reading the library refused: {line}");
                withheld += 1;
            }
        }
        assert_eq!((checked, withheld, off_lattice), (1707, 123, 288));
    }

    #[test]
    fn the_conversion_withholds_rather_than_inventing() {
        // K is the divisor; a zero decode is the vendor's silent failure, not a sensitivity.
        assert_eq!(glucose_from_current(800, 3200, 0, 600), None);
        // Warm-up: the gate is the sample index, not the wire's absent field.
        assert_eq!(glucose_from_current(800, 3200, 150, 13), None);
        assert!(glucose_from_current(800, 3200, 150, 14).is_some());
        // Past the state cap the vendor repeats its last answer forever.
        assert_eq!(glucose_from_current(800, 3200, 150, 10_095), None);
        // Take-off, flooding, and a current too small to be a signal.
        assert_eq!(glucose_from_current(80, 3200, 150, 600), None);
        assert_eq!(glucose_from_current(1000, 3200, 10, 600), None);
        assert_eq!(glucose_from_current(100, 3200, 150, 600), None);
    }

    /// Wire's own field ignores temperature/K; these two records differ only in temperature.
    #[test]
    fn temperature_moves_the_conversion_and_the_wire_field_does_not() {
        let cold = glucose_from_current(800, 3000, 150, 600).unwrap();
        let warm = glucose_from_current(800, 3400, 150, 600).unwrap();
        assert!(cold > warm, "cold {cold} warm {warm}");
    }

    #[test]
    fn record_reads_glucose_as_native_mgdl() {
        let mut rec = [0u8; RECORD_SHORT];
        rec[6] = 0x30 | 0x01; // trend 3, glucose bits 11..8
        rec[7] = 0x2C; // glucose 0x12C = 300 mg/dL
        let r = decode_record(&rec, TempOrder::FractionFirst).unwrap();
        assert_eq!(r.glucose_mgdl, Some(300));
        assert_eq!(r.trend_code, 3);
    }

    #[test]
    fn an_absent_glucose_field_is_none_not_zero() {
        let rec = [0u8; RECORD_SHORT];
        assert_eq!(decode_record(&rec, TempOrder::FractionFirst).unwrap().glucose_mgdl, None);
    }

    #[test]
    fn currents_are_big_endian_hundredths() {
        let mut rec = [0u8; RECORD_SHORT];
        rec[0] = 0x01;
        rec[1] = 0x2C; // 300 -> 3.00
        rec[2] = 0x27;
        rec[3] = 0x10; // 10000 -> 100.00
        let r = decode_record(&rec, TempOrder::FractionFirst).unwrap();
        assert_eq!((r.ib_x100, r.iw_x100), (300, 10_000));
        assert!((r.ib() - 3.0).abs() < 1e-6);
        assert!((r.iw() - 100.0).abs() < 1e-6);
    }

    #[test]
    fn the_two_temperature_orders_disagree_and_both_are_reachable() {
        let mut rec = [0u8; RECORD_SHORT];
        rec[4] = 50; // 0.50 as a fraction, or 10 C as an integer
        rec[5] = 73; // 33 C as an integer, or 0.73 as a fraction
        let a = decode_record(&rec, TempOrder::FractionFirst).unwrap();
        let b = decode_record(&rec, TempOrder::IntegerFirst).unwrap();
        assert_eq!(a.temp_c_x100, 3350);
        assert_eq!(b.temp_c_x100, 1073);
        assert!((a.temp_c() - 33.5).abs() < 1e-4);
        assert!((b.temp_c() - 10.73).abs() < 1e-4);
    }

    #[test]
    fn electrodes_and_battery_exist_only_in_the_voltage_form() {
        let mut rec = [0u8; RECORD_VOLTAGE];
        rec[9] = 173;
        rec[10] = 173;
        rec[11] = 166;
        rec[12] = 108;
        rec[13] = 0x06;
        rec[14] = 0x43;
        let v = decode_record(&rec, TempOrder::IntegerFirst).unwrap();
        assert_eq!(v.electrodes_mv, Some([1038, 1038, 996, 648]));
        assert_eq!(v.battery, Some(1603));

        let s = decode_record(&[0u8; RECORD_SHORT], TempOrder::IntegerFirst).unwrap();
        assert_eq!(s.electrodes_mv, None);
        assert_eq!(s.battery, None);
    }

    #[test]
    fn only_the_two_record_lengths_decode() {
        assert!(decode_record(&[0u8; 10], TempOrder::FractionFirst).is_none());
        assert!(decode_record(&[0u8; 14], TempOrder::FractionFirst).is_none());
        assert!(decode_record(&[0u8; RECORD_VOLTAGE], TempOrder::FractionFirst).is_some());
    }

    #[test]
    fn advert_block_yields_six_samples() {
        let block = [0x5Au8; ADVERT_BLOCK];
        let s = decode_advert_block(&block, 0x42).unwrap();
        assert_eq!(s.len(), ADVERT_SAMPLES);
        assert!(s.iter().all(|x| x.iw_raw <= 0x3FFF && x.temp_raw <= 0x03FF));
        assert!(decode_advert_block(&[0u8; 17], 0x42).is_none());
    }

    /// Synthetic identity (K=1.25, R=1.0); never a real SSN, a serial this repo can't carry.
    const ANCHOR: &str = "001734456789012510B2C";

    /// (code, K bits, R bits, lifeTime, calibration, unitOrder, year, ...); K/R exact bits.
    type Vector = (
        &'static str,
        u32,
        u32,
        i32,
        i32,
        i32,
        i32,
        &'static str,
        &'static str,
        &'static str,
        &'static str,
        &'static str,
    );

    const ACCEPTED: &[Vector] = &[
        ("c10273456789105218A1B", 0x3f051eb8, 0x3fe66666, 1, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c20273456789105218A1B", 0x3f051eb8, 0x3fe66666, 2, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c30273456789105218A1B", 0x3f051eb8, 0x3fe66666, 3, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c50273456789105218A1B", 0x3f051eb8, 0x3fe66666, 5, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c60273456789105218A1B", 0x3f051eb8, 0x3fe66666, 6, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c70273456789105218A1B", 0x3f051eb8, 0x3fe66666, 7, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c80273456789105218A1B", 0x3f051eb8, 0x3fe66666, 8, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c90273456789105218A1B", 0x3f051eb8, 0x3fe66666, 9, 1, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789700000A1B", 0x00000000, 0x00000000, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789700101A1B", 0x3c23d70a, 0x3dcccccd, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789701010A1B", 0x3dcccccd, 0x3f800000, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789710050A1B", 0x3f800000, 0x40a00000, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789705218A1B", 0x3f051eb8, 0x3fe66666, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789712837A1B", 0x3fa3d70a, 0x406ccccd, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789750555A1B", 0x40a1999a, 0x40b00000, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789799999A1B", 0x411fd70a, 0x411e6666, 4, 7, 456, 2, "c", "0", "73", "789", "A1B"),
        ("000273456789105218A1B", 0x3f051eb8, 0x3fe66666, 0, 1, 456, 2, "0", "0", "73", "789", "A1B"),
        ("c40273456789005218A1B", 0x3f051eb8, 0x3fe66666, 4, 0, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456789905218A1B", 0x3f051eb8, 0x3fe66666, 4, 9, 456, 2, "c", "0", "73", "789", "A1B"),
        ("c402A7456789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "c", "0", "A7", "789", "A1B"),
        ("c40273456789105218012", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "c", "0", "73", "789", "012"),
        ("A2734567891052A1B", 0x3f800000, 0x40a66666, 0, 0, 456, 2, "", "A", "73", "789", "A1B"),
        ("B5019876543299A1B", 0x404ccccd, 0x411e6666, 0, 0, 987, 5, "", "B", "01", "654", "A1B"),
        ("A27345678910521A1B", 0x3f866666, 0x40066666, 0, 0, 456, 2, "", "A", "73", "789", "A1B"),
        ("B50198765499999A1B", 0x411fd70a, 0x411e6666, 0, 0, 987, 5, "", "B", "01", "654", "A1B"),
        ("001734456789012510B2C", 0x3fa00000, 0x3f800000, 0, 0, 456, 7, "0", "1", "34", "789", "B2C"),
        ("001734456789000000AA5", 0x00000000, 0x00000000, 0, 0, 456, 7, "0", "1", "34", "789", "AA5"),
        ("l40273456789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "l", "0", "73", "789", "A1B"),
        ("L40273456789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "L", "0", "73", "789", "A1B"),
        ("c402AZ456789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "c", "0", "AZ", "789", "A1B"),
        ("c40201456789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "c", "0", "01", "789", "A1B"),
        ("c40273001789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 1, 2, "c", "0", "73", "789", "A1B"),
        ("c40273999789105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 999, 2, "c", "0", "73", "789", "A1B"),
        ("c40273456001105218A1B", 0x3f051eb8, 0x3fe66666, 4, 1, 456, 2, "c", "0", "73", "001", "A1B"),
        ("A2734567890000A1B", 0x00000000, 0x00000000, 0, 0, 456, 2, "", "A", "73", "789", "A1B"),
        ("A2734567899999A1B", 0x411e6666, 0x411e6666, 0, 0, 456, 2, "", "A", "73", "789", "A1B"),
        ("A27345678900000A1B", 0x00000000, 0x00000000, 0, 0, 456, 2, "", "A", "73", "789", "A1B"),
        ("A27345678999999A1B", 0x411fd70a, 0x411e6666, 0, 0, 456, 2, "", "A", "73", "789", "A1B"),
        ("17344567890801B2C", 0x3f4ccccd, 0x3dcccccd, 0, 0, 456, 7, "", "1", "34", "789", "B2C"),
        ("173445678908012B2C", 0x3f4ccccd, 0x3f99999a, 0, 0, 456, 7, "", "1", "34", "789", "B2C"),
    ];

    const REJECTED: &[&str] = &[
        "a20123456789",
        "b30154123456",
        "c40254987654",
        "d60309111111",
        "r20507222222",
        "C40254987654",
        "i40273456789105218A1B",
        "040273456789105218A1B",
        "c00273456789105218A1B",
        "c40273000789105218A1B",
        "c4027A456789105218A1B",
        "c40273456789105218A1",
        "c40273456789105218A1BC",
        "A273456789105A1B",
        "A273456789105218A1B",
        "c40273456789105218a1b",
        "c4027345678 105218A1B",
        "c40254987654ABCDEFGHI",
        "o40273456789105218A1B",
        "I40273456789105218A1B",
        "O40273456789105218A1B",
        "c40273456000105218A1B",
        "c402A0456789105218A1B",
        "c40200456789105218A1B",
        "c40273456789105218a1B",
        "a2734567891052A1B",
        "A2734567891052AA1B",
        "c40123456789",
        "001734456789012510b2C",
        "00173445678901251 B2C",
    ];

    #[test]
    fn the_anchor_decodes_to_the_constants_a_real_sensor_was_measured_at() {
        let d = decode_ct(ANCHOR).unwrap();
        // 1.25 and 1.0 are exact binary fractions, so == is the right comparison.
        assert_eq!(d.k, 1.25);
        assert_eq!(d.r, 1.0);
        assert_eq!(d.k.to_bits(), 0x3fa0_0000);
        assert_eq!(d.r.to_bits(), 0x3f80_0000);
        assert_eq!(d.life_time, 0);
        assert_eq!(d.calibration, 0);
        assert_eq!(d.unit_order, 456);
        assert_eq!(d.year, 7);
        assert_eq!(d.market_no, "0");
        assert_eq!(d.electrode_type, "1");
        assert_eq!(d.serial_no, "34");
        assert_eq!(d.sensor_no, "789");
        assert_eq!(d.electrode_tec_no, "B");
        assert_eq!(d.enzyme_tec_no, "2");
        assert_eq!(d.membrane_tec_no, "C");
    }

    #[test]
    fn the_twelve_character_printed_code_is_rejected() {
        // The code printed on the sensor: 12 chars, no grammar admits it. Vendor returns K=0.
        assert!(decode_ct("c40123456789").is_none());
        assert_eq!(decode_ct_vendor("c40123456789"), KrDecodeData::default());
    }

    #[test]
    fn every_accepted_vector_matches_the_vendor_bit_for_bit() {
        for &(code, k, r, life, cal, unit, year, market, electrode, serial, sensor, tec) in ACCEPTED
        {
            let d = decode_ct(code).unwrap_or_else(|| panic!("{code} rejected"));
            assert_eq!(d.k.to_bits(), k, "K of {code}");
            assert_eq!(d.r.to_bits(), r, "R of {code}");
            assert_eq!(d.life_time, life, "lifeTime of {code}");
            assert_eq!(d.calibration, cal, "calibration of {code}");
            assert_eq!(d.unit_order, unit, "unitOrder of {code}");
            assert_eq!(d.year, year, "year of {code}");
            assert_eq!(d.market_no, market, "marketNo of {code}");
            assert_eq!(d.electrode_type, electrode, "electrodeType of {code}");
            assert_eq!(d.serial_no, serial, "serialNo of {code}");
            assert_eq!(d.sensor_no, sensor, "sensorNo of {code}");
            assert_eq!(
                [
                    d.electrode_tec_no.as_str(),
                    d.enzyme_tec_no.as_str(),
                    d.membrane_tec_no.as_str()
                ]
                .concat(),
                tec,
                "Tec of {code}"
            );
            // The grammar forbids an all-zero counting group.
            assert_ne!(d.unit_order, 0, "unitOrder of {code}");
        }
    }

    #[test]
    fn every_rejected_vector_is_rejected_and_zero_filled() {
        for &code in REJECTED {
            assert!(decode_ct(code).is_none(), "{code} accepted");
            assert_eq!(decode_ct_vendor(code), KrDecodeData::default(), "{code}");
        }
        assert!(decode_ct("").is_none());
    }

    #[test]
    fn only_three_lengths_are_accepted() {
        let padded = format!("{ANCHOR}000000000");
        for n in 0..=padded.len() {
            let probe = &padded[..n];
            assert_eq!(
                decode_ct(probe).is_some(),
                matches!(n, 17 | 18 | 21),
                "length {n}: {probe:?}"
            );
        }
    }

    #[test]
    fn a_truncated_identity_string_answers_confidently_and_wrongly() {
        // The anchor's first 17 characters are a legal short code with a different K, unsignalled.
        let cut = decode_ct(&ANCHOR[..17]).unwrap();
        let whole = decode_ct(ANCHOR).unwrap();
        assert_eq!(cut.k, 8.9);
        assert_eq!(whole.k, 1.25);
        assert_eq!(cut.unit_order, 344);
        assert_eq!(whole.unit_order, 456);
    }

    #[test]
    fn k_and_r_are_the_decimals_the_identity_string_spells() {
        // 0.52/9.99 aren't representable in binary32; compare against half the quantise step.
        for (code, k, r) in [
            ("c40273456789105218A1B", 0.52_f32, 1.8_f32),
            ("c40273456789799999A1B", 9.99, 9.9),
            ("A2734567891052A1B", 1.0, 5.2),
            ("A27345678910521A1B", 1.05, 2.1),
        ] {
            let d = decode_ct(code).unwrap();
            assert!((d.k - k).abs() < 0.005, "K of {code} was {}", d.k);
            assert!((d.r - r).abs() < 0.05, "R of {code} was {}", d.r);
        }
    }

    #[test]
    fn the_market_position_admits_l_and_refuses_i_and_o() {
        for c in ['l', 'L'] {
            assert!(decode_ct(&format!("{c}40273456789105218A1B")).is_some(), "{c}");
        }
        // '0' too: a zero market character is legal only beside a zero lifetime digit.
        for c in ['i', 'I', 'o', 'O', '0'] {
            assert!(decode_ct(&format!("{c}40273456789105218A1B")).is_none(), "{c}");
        }
    }

    #[test]
    fn a_zero_k_is_a_legal_decode_and_not_a_rejection() {
        let d = decode_ct("001734456789000000AA5").unwrap();
        assert_eq!(d.k, 0.0);
        assert_eq!(d.r, 0.0);
        assert_eq!(d.unit_order, 456);
        assert!(d.usable_k().is_none());
        assert_eq!(decode_ct(ANCHOR).unwrap().usable_k(), Some(1.25));
    }

    #[test]
    fn the_short_forms_carry_no_market_lifetime_or_calibration() {
        for code in ["A2734567891052A1B", "A27345678910521A1B"] {
            let d = decode_ct(code).unwrap();
            assert_eq!(d.market_no, "");
            assert_eq!(d.life_time, 0);
            assert_eq!(d.calibration, 0);
        }
    }

    #[test]
    fn the_decode_stops_at_the_first_nul() {
        assert_eq!(decode_ct(&format!("{ANCHOR}\u{0}\u{93}")), decode_ct(ANCHOR));
        assert!(decode_ct(&format!("\u{0}{ANCHOR}")).is_none());
    }

    #[test]
    fn k_and_r_cross_the_ffi_as_exact_hundredths_of_the_floats_beside_them() {
        // The integer reaches the sensor/FFI; the float is what the vendor vectors compare against.
        for &(code, ..) in ACCEPTED {
            let d = decode_ct(code).unwrap();
            assert_eq!(d.k_x100 as f32 / 100.0, d.k, "K of {code}");
            assert_eq!(d.r_x100 as f32 / 100.0, d.r, "R of {code}");
        }
        let anchor = decode_ct(ANCHOR).unwrap();
        assert_eq!((anchor.k_x100, anchor.r_x100), (125, 100));
    }

    /// Measured against the working Linux binder; extra convolution terms decode temp wrong.
    #[test]
    fn convolve_keeps_the_vendor_s_four_terms_and_the_frame_is_unchanged() {
        let b = [0xAAu8, 0xBB, 0xCC, 0xDD];
        let a = [0x11u8, 0x22, 0x33, 0x44];
        let c = convolve(&b, &a);
        // 4x4 -> first 4 products only, none reduced; the first alone exceeds a byte.
        assert_eq!(c, vec![2890, 8959, 18496, 31790]);
        assert_eq!(xor_fold(&c) & 0xFF, 219);
        // The frame is what it always was: the fold is where the term count shows.
        let frame = build_set_id(&b, &a).unwrap();
        assert_eq!(&frame[5..9], &[74u8, 255, 64, 46]);
        assert!(is_legal(&frame));
        assert_eq!(frame.len(), 10);
        assert_eq!(&frame[1..5], &b);
        assert!(convolve(&[], &a).is_empty());
    }

    /// Agree on the frame, disagree on the key; nothing the wire carries could catch this.
    #[test]
    fn the_dropped_terms_change_the_key_and_not_the_frame() {
        fn full_linear(b: &[u8], a: &[u8]) -> Vec<i32> {
            let mut out = vec![0i32; a.len() + b.len() - 1];
            for (i, &x) in b.iter().enumerate() {
                for (j, &y) in a.iter().enumerate() {
                    out[i + j] += i32::from(x) * i32::from(y);
                }
            }
            out
        }
        let mut state: u32 = 0x2468_ACE0;
        let mut differed = 0;
        for _ in 0..2_000 {
            let mut byte = || {
                state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                (state >> 24) as u8
            };
            let b = [byte(), byte(), byte(), byte()];
            let a = [byte(), byte(), byte(), byte()];
            let vendor = convolve(&b, &a);
            let full = full_linear(&b, &a);
            assert_eq!(vendor, full[..NONCE_LEN], "the frame's four bytes are the same either way");
            if xor_fold(&vendor) & 0xFF != xor_fold(&full) & 0xFF {
                differed += 1;
            }
        }
        assert!(differed > 1_900, "the term count decides the key almost always, not {differed}/2000");
    }

    #[test]
    fn only_the_low_byte_of_the_fold_survives_so_truncation_cannot_change_the_key() {
        // ^ and + preserve low bits; mod-256 reduction gives the same CIPHER_ID either way.
        let mut state: u32 = 0x1357_9BDF;
        for _ in 0..2_000 {
            let mut byte = || {
                state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                (state >> 24) as u8
            };
            let b = [byte(), byte(), byte(), byte()];
            let a = [byte(), byte(), byte(), byte()];
            let c = convolve(&b, &a);
            let reduced: Vec<i32> = c.iter().map(|&v| v & 0xFF).collect();
            assert_eq!(xor_fold(&c) & 0xFF, xor_fold(&reduced) & 0xFF);
        }
    }

    #[test]
    fn a_set_id_reply_of_any_length_but_ten_yields_no_key() {
        let a = [0x8Au8, 0x95, 0x8C, 0xBD];
        // Vendor asserts checksum/opcode not length; each would produce a persisted key.
        for len in 2..=16usize {
            let payload: Vec<u8> = (1..len as u8).map(|i| i.wrapping_mul(29)).collect();
            let reply = build_frame(0x30, &payload);
            assert_eq!(reply.len(), len + 1);
            assert!(is_legal(&reply));
            // Whatever this frame carries in [1..5]; length is the only gate under test.
            let echo: Vec<u8> = reply[1..].iter().take(NONCE_LEN).copied().collect();
            assert_eq!(
                cipher_id_from_set_id_reply(&reply, &a, &echo).is_some(),
                reply.len() == SET_ID_REPLY_LEN,
                "length {}",
                reply.len(),
            );
        }
        let ok = build_frame(0x30, &[1, 2, 3, 4, 5, 6, 7, 8]);
        let b = [1u8, 2, 3, 4];
        assert!(cipher_id_from_set_id_reply(&ok, &a, &b).is_some());
        assert!(cipher_id_from_set_id_reply(&ok, &a[..3], &b).is_none()); // short nonce A
        assert!(cipher_id_from_set_id_reply(&ok, &a, &b[..3]).is_none()); // short nonce B
        // A reply answering a different nonce; vendor persists that key, 0x30 unrepeatable.
        let mut other = b;
        other[0] ^= 1;
        assert!(cipher_id_from_set_id_reply(&ok, &a, &other).is_none());
        let mut bad_sum = ok.clone();
        bad_sum[9] ^= 1;
        assert!(cipher_id_from_set_id_reply(&bad_sum, &a, &b).is_none());
        let mut bad_op = ok.clone();
        bad_op[0] = 0x31;
        assert!(cipher_id_from_set_id_reply(&bad_op, &a, &b).is_none());
    }

    #[test]
    fn set_date_is_year_minus_1900_with_no_timezone_byte() {
        let f = build_set_date(2026, 8, 17, 11, 46, 13).unwrap();
        assert_eq!(f, vec![0x03, 126, 8, 17, 11, 46, 13, 0xE0]);
        assert!(is_legal(&f));
        assert_eq!(f.len(), 8, "seven fields and a checksum — no timezone byte");
        assert!(build_set_date(1899, 1, 1, 0, 0, 0).is_none());
        assert!(build_set_date(2026, 13, 1, 0, 0, 0).is_none());
        assert!(build_set_date(2026, 1, 0, 0, 0, 0).is_none());
        assert!(build_set_date(2026, 1, 1, 24, 0, 0).is_none());
    }

    #[test]
    fn the_no_argument_requests_are_the_frames_the_hardware_answered() {
        assert_eq!(build_version_request(), vec![0x01]);
        assert_eq!(build_self_check(), vec![0x05, 0x55, 0xAA, 0x04]);
        assert_eq!(build_query_ssn(), vec![0x3F, 0x55, 0xAA, 0x3E]);
        assert_eq!(build_init(), vec![0x06, 0x55, 0xAA, 0x05]);
        assert_eq!(build_low_power(), vec![0x0F, 0x55, 0xAA, 0x0E]);
        assert_eq!(build_push_ack(), vec![0x35, 0x55, 0xAA, 0x34]);
        // The dead builder: it checksums too, and only the third byte tells them apart.
        assert!(is_legal(&[0x06, 0x55, 0x01, 0x5C]));
        assert_ne!(build_init(), vec![0x06, 0x55, 0x01, 0x5C]);
    }

    #[test]
    fn check_id_carries_the_persisted_nonce_in_cleartext() {
        let f = build_check_id(&[0x5D, 0x08, 0xE4, 0x93]).unwrap();
        assert_eq!(f, vec![0x31, 0x5D, 0x08, 0xE4, 0x93, 0x0D]);
        assert!(is_legal(&f));
        assert!(build_check_id(&[1, 2, 3]).is_none());
    }

    /// Synthetic key and unbind password; a real sensor's pair in a fixture would never move again.
    const SET_PARAMETERS_VECTOR: &[u8] = &[
        0x38, 0x0F, 0xF8, 0x0F, 0xF0, 0xF1, 0xFF, 0x3C, 0x0F, 0x1F, 0xE1, 0x1E, 0x1C, 0xB3,
    ];
    const VECTOR_CIPHER_ID: u8 = 0x5A;
    const VECTOR_RANDOM_ID: &[u8] = b"1234";
    /// The pair a real sensor WAS measured at; a calibration constant is not an identifier.
    const VECTOR_K_X100: i32 = 125;
    const VECTOR_R_X100: i32 = 100;
    const INTERVAL_MIN: i32 = 3;
    const CYCLE_DAYS: i32 = 16;

    #[test]
    fn set_parameters_reproduces_the_bind_vector_byte_for_byte() {
        let built = build_set_parameters(
            VECTOR_K_X100,
            VECTOR_R_X100,
            INTERVAL_MIN,
            CYCLE_DAYS,
            VECTOR_RANDOM_ID,
            VECTOR_CIPHER_ID,
        )
        .unwrap();
        assert_eq!(built, SET_PARAMETERS_VECTOR);
        assert_eq!(built.len(), SET_PARAMETERS_FRAME);
        // The checksum covers the OBFUSCATED bytes, as received.
        assert!(is_legal(&built));

        let payload =
            set_parameters_payload(VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS, VECTOR_RANDOM_ID)
                .unwrap();
        assert_eq!(payload, [1, 25, 1, 0, 0x03, 0x10, 0x55, 0x00, b'1', b'2', b'3', b'4']);
        assert_eq!(deobfuscate(&built[1..13], VECTOR_CIPHER_ID), payload);
    }

    #[test]
    fn set_parameters_refuses_a_random_id_that_is_not_four_digits() {
        for bad in [&b"123"[..], b"12345", b"12a4", b"", b"12 4"] {
            assert!(
                set_parameters_payload(VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS, bad).is_none(),
                "{bad:?}",
            );
        }
        // RANDOM_ID is the only unbind password; a malformed one must never be built.
        assert!(build_set_parameters(VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS, b"12a4", 0).is_none());
        assert!(build_set_parameters(VECTOR_K_X100, VECTOR_R_X100, 0, CYCLE_DAYS, VECTOR_RANDOM_ID, 0).is_none());
        assert!(build_set_parameters(VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, 0, VECTOR_RANDOM_ID, 0).is_none());
    }

    #[test]
    fn the_set_parameters_echo_is_checked_field_by_field() {
        let ok = verify_set_parameters_echo(
            SET_PARAMETERS_VECTOR,
            VECTOR_K_X100,
            VECTOR_R_X100,
            INTERVAL_MIN,
            CYCLE_DAYS,
            VECTOR_RANDOM_ID,
            VECTOR_CIPHER_ID,
        );
        assert!(ok, "the vector's own echo must verify");

        // One wrong hundredth of K is the failure this check exists to catch.
        assert!(!verify_set_parameters_echo(
            SET_PARAMETERS_VECTOR,
            VECTOR_K_X100 + 1,
            VECTOR_R_X100,
            INTERVAL_MIN,
            CYCLE_DAYS,
            VECTOR_RANDOM_ID,
            VECTOR_CIPHER_ID,
        ));
        assert!(!verify_set_parameters_echo(
            SET_PARAMETERS_VECTOR, VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS, b"1235", VECTOR_CIPHER_ID,
        ));
        assert!(!verify_set_parameters_echo(
            SET_PARAMETERS_VECTOR, VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, 15, VECTOR_RANDOM_ID, VECTOR_CIPHER_ID,
        ));
        let mut corrupt = SET_PARAMETERS_VECTOR.to_vec();
        corrupt[3] ^= 0x01;
        assert!(!verify_set_parameters_echo(
            &corrupt, VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS, VECTOR_RANDOM_ID, VECTOR_CIPHER_ID,
        ));
        assert!(!verify_set_parameters_echo(
            &SET_PARAMETERS_VECTOR[..13], VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS, VECTOR_RANDOM_ID,
            VECTOR_CIPHER_ID,
        ));
    }

    /// Sensor echoes the received frame, obfuscated; backwards is permanent (0x38 already taken).
    #[test]
    fn the_echo_is_the_obfuscated_frame_not_the_plaintext_payload() {
        let payload =
            set_parameters_payload(VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS, VECTOR_RANDOM_ID)
                .unwrap();
        let plaintext_reply = build_frame(0x38, &payload);
        // The two frames are genuinely different bytes, so the assertions below discriminate.
        assert_ne!(plaintext_reply, SET_PARAMETERS_VECTOR);

        assert!(
            verify_set_parameters_echo(
                SET_PARAMETERS_VECTOR, VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS,
                VECTOR_RANDOM_ID, VECTOR_CIPHER_ID,
            ),
            "the obfuscated echo is what the sensor sends and must verify",
        );
        assert!(
            !verify_set_parameters_echo(
                &plaintext_reply, VECTOR_K_X100, VECTOR_R_X100, INTERVAL_MIN, CYCLE_DAYS,
                VECTOR_RANDOM_ID, VECTOR_CIPHER_ID,
            ),
            "a plaintext-payload reply is not this protocol's, and accepting it proves nothing about K",
        );
    }

    /// Two captured warm-up records, re-keyed; wrong temp order reads an impossible value.
    const PUSH_A: &[u8] = &[
        0x35, 0x05, 0x00, 0xF0, 0xF0, 0xF1, 0xDB, 0xCD, 0xF3, 0x0F, 0x0F, 0x0F, 0x6B, 0x94, 0x6D,
        0x2B, 0x0D, 0x31, 0xA8,
    ];
    const PUSH_B: &[u8] = &[
        0x35, 0x06, 0x00, 0x0F, 0x0F, 0x0E, 0x33, 0x32, 0xC9, 0xF0, 0xF0, 0xF0, 0x94, 0x6B, 0x92,
        0xD4, 0xF2, 0xCC, 0x88,
    ];

    #[test]
    fn the_captured_pushes_decode_to_the_numbers_the_hardware_showed() {
        for (frame, id, temp, iw, battery) in
            [(PUSH_A, 5u16, 3104i32, 893u16, 1603u16), (PUSH_B, 6, 3075, 836, 1604)]
        {
            assert!(is_legal(frame), "checksum of push {id}");
            let p = parse_push(frame, VECTOR_CIPHER_ID).unwrap_or_else(|| panic!("push {id} rejected"));
            assert_eq!(p.glucose_id, id);
            assert_eq!(p.record.temp_c_x100, temp, "temperature of push {id}");
            assert_eq!(p.record.iw_x100, iw, "Iw of push {id}");
            assert_eq!(p.record.battery, Some(battery));
            // Warm-up: the wire glucose field is zero, an ABSENT value and never 0 mg/dL.
            assert_eq!(p.record.glucose_mgdl, None, "glucose of push {id}");
            assert_eq!(p.record.error_code, 0);
            assert_eq!(p.record.trend_code, 0);
            assert_eq!(p.record.ib_x100, 0);
            assert_eq!(p.record.electrodes_mv, Some([1038, 1038, 996, 648]));
        }
    }

    #[test]
    fn the_wrong_temperature_order_is_plausible_on_one_frame_and_impossible_on_the_other() {
        let wrong = |frame: &[u8]| {
            let plain = deobfuscate(&frame[3..frame.len() - 1], VECTOR_CIPHER_ID);
            decode_record(&plain, TempOrder::FractionFirst).unwrap().temp_c_x100
        };
        assert_eq!(wrong(PUSH_A), -3529, "impossible, and the whole reason for this vector");
        assert_eq!(wrong(PUSH_B), 3570, "plausible — one frame alone settles nothing");
        assert_eq!(parse_push(PUSH_A, VECTOR_CIPHER_ID).unwrap().record.temp_c_x100, 3104);
    }

    #[test]
    fn the_complement_key_changes_only_the_battery_low_bit_of_a_real_push() {
        // The final bit lands in the battery's low byte: worth one unit, and nothing reads it.
        let (k, comp) = key_pair(VECTOR_CIPHER_ID);
        assert_eq!(comp, 0xA5);
        for (frame, battery) in [(PUSH_A, 1603u16), (PUSH_B, 1604)] {
            let a = parse_push(frame, k).unwrap().record;
            let b = parse_push(frame, comp).unwrap().record;
            assert_eq!(a.battery, Some(battery));
            assert_eq!(b.battery.unwrap().abs_diff(battery), 1);
            assert_eq!(
                Ct5Record { battery: a.battery, ..b },
                a,
                "the complement key must differ in the battery and nowhere else",
            );
        }
    }

    #[test]
    fn only_the_two_push_lengths_parse() {
        assert_eq!(PUSH_VOLTAGE, 19);
        assert_eq!(PUSH_SHORT, 15);
        for len in 0..24usize {
            let payload: Vec<u8> = (0..len).map(|i| (i as u8).wrapping_mul(7)).collect();
            let frame = build_frame(0x35, &payload);
            assert_eq!(
                parse_push(&frame, VECTOR_CIPHER_ID).is_some(),
                matches!(frame.len(), PUSH_SHORT | PUSH_VOLTAGE),
                "length {}",
                frame.len(),
            );
        }
        let mut corrupt = PUSH_A.to_vec();
        corrupt[18] ^= 1;
        assert!(parse_push(&corrupt, VECTOR_CIPHER_ID).is_none());
        let mut wrong_op = PUSH_A.to_vec();
        wrong_op[0] = 0x37;
        assert!(parse_push(&wrong_op, VECTOR_CIPHER_ID).is_none());
    }

    /// Plaintext record; a batch shares one bit stream, so concatenating separate ones lies.
    fn record_of(push: &[u8], key: u8) -> Vec<u8> {
        deobfuscate(&push[3..push.len() - 1], key)
    }

    /// A marker record, written LITERALLY on the wire and therefore outside the obfuscated stream.
    #[derive(Clone, Copy)]
    enum Slot<'a> {
        Rec(&'a [u8]),
        Literal(u8),
    }

    /// Built like the sensor builds one: each marker-free run obfuscated as one stream.
    fn history_frame(start_id: u16, slots: &[Slot], key: u8) -> Vec<u8> {
        let mut payload = vec![(start_id & 0xFF) as u8, (start_id >> 8) as u8];
        let mut run: Vec<u8> = Vec::new();
        let flush = |run: &mut Vec<u8>, payload: &mut Vec<u8>| {
            if !run.is_empty() {
                payload.extend_from_slice(&obfuscate(run, key));
                run.clear();
            }
        };
        for slot in slots {
            match slot {
                Slot::Rec(r) => run.extend_from_slice(r),
                Slot::Literal(b) => {
                    flush(&mut run, &mut payload);
                    payload.extend(std::iter::repeat_n(*b, RECORD_VOLTAGE));
                }
            }
        }
        flush(&mut run, &mut payload);
        build_frame(0x37, &payload)
    }

    #[test]
    fn a_history_batch_decodes_to_what_the_same_samples_pushed_live() {
        let (a, b) = (record_of(PUSH_A, VECTOR_CIPHER_ID), record_of(PUSH_B, VECTOR_CIPHER_ID));
        let frame = history_frame(5, &[Slot::Rec(&a), Slot::Rec(&b)], VECTOR_CIPHER_ID);
        let reply = parse_history(&frame, VECTOR_CIPHER_ID, RECORD_VOLTAGE).expect("batch rejected");

        assert_eq!(reply.start_id, 5);
        assert_eq!(reply.slots, 2);
        assert!(!reply.end_of_history);
        assert_eq!(reply.samples.len(), 2);

        // Byte-identical to the live decode of the same samples.
        for (sample, push) in reply.samples.iter().zip([PUSH_A, PUSH_B]) {
            let live = parse_push(push, VECTOR_CIPHER_ID).unwrap();
            assert_eq!(sample.glucose_id, live.glucose_id);
            assert_eq!(sample.record, live.record);
        }
    }

    #[test]
    fn a_batch_is_one_obfuscated_stream_and_not_a_row_of_independent_records() {
        // One continuous bit stream (§5.3); per-record decode is wrong in the LSB of the last byte.
        let (a, b) = (record_of(PUSH_A, VECTOR_CIPHER_ID), record_of(PUSH_B, VECTOR_CIPHER_ID));
        let frame = history_frame(5, &[Slot::Rec(&a), Slot::Rec(&b)], VECTOR_CIPHER_ID);
        let body = &frame[3..frame.len() - 1];

        let whole = deobfuscate(body, VECTOR_CIPHER_ID);
        let per_record: Vec<u8> = body
            .chunks_exact(RECORD_VOLTAGE)
            .flat_map(|c| deobfuscate(c, VECTOR_CIPHER_ID))
            .collect();
        assert_ne!(whole, per_record, "the two readings must actually differ, or this proves nothing");
        assert_eq!(
            whole[..RECORD_VOLTAGE - 1],
            per_record[..RECORD_VOLTAGE - 1],
            "and they may differ ONLY in the last byte of a record",
        );

        let reply = parse_history(&frame, VECTOR_CIPHER_ID, RECORD_VOLTAGE).unwrap();
        assert_eq!(reply.samples.len(), 2);
        assert_eq!(reply.samples[0].record, decode_record(&a, TempOrder::IntegerFirst).unwrap());
        assert_eq!(reply.samples[1].record, decode_record(&b, TempOrder::IntegerFirst).unwrap());
    }

    #[test]
    fn a_literal_marker_never_supplies_the_neighbour_bit_of_the_record_in_front_of_it() {
        // A marker is literal, a run stops at it; folded in, it would change the record before it.
        let a = record_of(PUSH_A, VECTOR_CIPHER_ID);
        let plain = decode_record(&a, TempOrder::IntegerFirst).unwrap();
        for marker in [0xFC, 0xFF] {
            let frame = history_frame(3, &[Slot::Rec(&a), Slot::Literal(marker)], VECTOR_CIPHER_ID);
            let reply = parse_history(&frame, VECTOR_CIPHER_ID, RECORD_VOLTAGE).unwrap();
            assert_eq!(reply.samples.len(), 1, "marker {marker:#x}");
            assert_eq!(reply.samples[0].record, plain, "marker {marker:#x} changed the record before it");
        }
    }

    #[test]
    fn the_batch_size_matches_the_vendors_own_table() {
        // PROTOCOL_CT5.md §7.3.
        assert_eq!(history_batch_size(211, RECORD_SHORT), 17);
        assert_eq!(history_batch_size(211, RECORD_VOLTAGE), 12);
        assert_eq!(history_batch_size(480, RECORD_SHORT), 42);
        assert_eq!(history_batch_size(480, RECORD_VOLTAGE), 30);
        assert_eq!(history_batch_size(0, RECORD_VOLTAGE), 1);
        assert_eq!(history_batch_size(4, RECORD_VOLTAGE), 1);
        assert_eq!(history_batch_size(20, RECORD_VOLTAGE), 1);
        assert_eq!(history_batch_size(100_000, RECORD_SHORT), HISTORY_MAX_BATCH);
        assert_eq!(history_batch_size(480, 0), 1);
    }

    #[test]
    fn a_full_record_of_fc_ends_the_store_and_is_not_a_slot() {
        let (a, b) = (record_of(PUSH_A, VECTOR_CIPHER_ID), record_of(PUSH_B, VECTOR_CIPHER_ID));
        let frame = history_frame(
            40,
            &[Slot::Rec(&a), Slot::Literal(0xFC), Slot::Rec(&b)],
            VECTOR_CIPHER_ID,
        );
        let reply = parse_history(&frame, VECTOR_CIPHER_ID, RECORD_VOLTAGE).unwrap();

        assert!(reply.end_of_history);
        // Terminator stops the scan, consumes no slot.
        assert_eq!(reply.slots, 1);
        assert_eq!(reply.samples.len(), 1);
        assert_eq!(reply.samples[0].glucose_id, 40);
    }

    #[test]
    fn an_empty_slot_is_consumed_without_shifting_the_ids_behind_it() {
        // Vendor compacts an interior all-0xFF record out, filing every later sample 3min early.
        let (a, b) = (record_of(PUSH_A, VECTOR_CIPHER_ID), record_of(PUSH_B, VECTOR_CIPHER_ID));
        let frame = history_frame(
            100,
            &[Slot::Rec(&a), Slot::Literal(0xFF), Slot::Rec(&b)],
            VECTOR_CIPHER_ID,
        );
        let reply = parse_history(&frame, VECTOR_CIPHER_ID, RECORD_VOLTAGE).unwrap();

        assert!(!reply.end_of_history);
        // A cursor advances by the slots, or it asks for the hole for ever.
        assert_eq!(reply.slots, 3);
        assert_eq!(reply.samples.len(), 2);
        assert_eq!(reply.samples[0].glucose_id, 100);
        assert_eq!(reply.samples[1].glucose_id, 102, "the sample behind a hole keeps its own slot");
    }

    #[test]
    fn a_record_that_deobfuscates_to_the_blank_sentinel_is_dropped() {
        // Not all-`0xFF` on the wire, so only the post-deobfuscation guard catches it.
        let blank = [0xFFu8; RECORD_VOLTAGE];
        assert_ne!(
            obfuscate(&blank, VECTOR_CIPHER_ID).to_vec(),
            blank.to_vec(),
            "the literal check would have caught it",
        );
        let a = record_of(PUSH_A, VECTOR_CIPHER_ID);
        let frame = history_frame(7, &[Slot::Rec(&blank), Slot::Rec(&a)], VECTOR_CIPHER_ID);
        let reply = parse_history(&frame, VECTOR_CIPHER_ID, RECORD_VOLTAGE).unwrap();

        assert_eq!(reply.slots, 2);
        assert_eq!(reply.samples.len(), 1);
        assert_eq!(reply.samples[0].glucose_id, 8, "the surviving sample keeps its own slot");
    }

    #[test]
    fn the_probe_reply_length_settles_the_record_dialect() {
        assert_eq!(history_record_size(HISTORY_ENVELOPE + RECORD_SHORT), Some(RECORD_SHORT));
        assert_eq!(history_record_size(HISTORY_ENVELOPE + RECORD_VOLTAGE), Some(RECORD_VOLTAGE));
        assert_eq!(history_record_size(0), None);
        assert_eq!(history_record_size(4), None);
        // 165 body bytes is fifteen short records or eleven voltage ones.
        assert_eq!(history_record_size(HISTORY_ENVELOPE + 165), None);
    }

    #[test]
    fn the_pull_request_carries_a_little_endian_start_id() {
        let frame = build_pull_history(0x0105, 30);
        assert!(is_legal(&frame));
        assert_eq!(frame[0], 0x37);
        assert_eq!(&frame[1..4], &[0x05, 0x01, 30], "id low byte first, then the count");
        assert_eq!(frame.len(), 5);
        // Zero is forced to one: an empty reply is indistinguishable from the end of the store.
        assert_eq!(build_pull_history(0, 0)[3], 1);
    }

    #[test]
    fn a_batch_is_refused_rather_than_half_read() {
        let (a, b) = (record_of(PUSH_A, VECTOR_CIPHER_ID), record_of(PUSH_B, VECTOR_CIPHER_ID));
        let good = history_frame(5, &[Slot::Rec(&a), Slot::Rec(&b)], VECTOR_CIPHER_ID);
        assert!(parse_history(&good, VECTOR_CIPHER_ID, RECORD_VOLTAGE).is_some());

        // A partial record is a truncated notification, not a batch with a short tail.
        let mut truncated = good.clone();
        truncated.truncate(good.len() - 3);
        let sum = receive_sum(&truncated, 0, truncated.len() - 1);
        truncated.push(sum);
        assert!(parse_history(&truncated, VECTOR_CIPHER_ID, RECORD_VOLTAGE).is_none());

        // Read under the wrong dialect the same body is refused, not silently re-sliced.
        assert!(parse_history(&good, VECTOR_CIPHER_ID, RECORD_SHORT).is_none());

        let mut corrupt = good.clone();
        let last = corrupt.len() - 1;
        corrupt[last] ^= 1;
        assert!(parse_history(&corrupt, VECTOR_CIPHER_ID, RECORD_VOLTAGE).is_none());

        let mut wrong_op = good.clone();
        wrong_op[0] = 0x35;
        let last = wrong_op.len() - 1;
        wrong_op[last] = receive_sum(&wrong_op, 0, last - 1);
        assert!(parse_history(&wrong_op, VECTOR_CIPHER_ID, RECORD_VOLTAGE).is_none());

        // An envelope with no records at all is a legal frame answering nothing.
        let empty = history_frame(9, &[], VECTOR_CIPHER_ID);
        let reply = parse_history(&empty, VECTOR_CIPHER_ID, RECORD_VOLTAGE).unwrap();
        assert_eq!(reply.slots, 0);
        assert!(reply.samples.is_empty());
        assert!(!reply.end_of_history);

        assert!(parse_history(&good, VECTOR_CIPHER_ID, 0).is_none());
        assert!(parse_history(&good, VECTOR_CIPHER_ID, 12).is_none());
    }

    #[test]
    fn an_id_at_the_ceiling_saturates_rather_than_wrapping_a_wear_onto_the_bind_instant() {
        let (a, b) = (record_of(PUSH_A, VECTOR_CIPHER_ID), record_of(PUSH_B, VECTOR_CIPHER_ID));
        let frame = history_frame(u16::MAX - 1, &[Slot::Rec(&a), Slot::Rec(&b)], VECTOR_CIPHER_ID);
        let reply = parse_history(&frame, VECTOR_CIPHER_ID, RECORD_VOLTAGE).unwrap();
        assert_eq!(reply.samples[0].glucose_id, u16::MAX - 1);
        assert_eq!(reply.samples[1].glucose_id, u16::MAX);
    }

    #[test]
    fn the_history_ffi_carries_the_batch_across_in_one_crossing() {
        let (a, b) = (record_of(PUSH_A, VECTOR_CIPHER_ID), record_of(PUSH_B, VECTOR_CIPHER_ID));
        let frame = history_frame(5, &[Slot::Rec(&a), Slot::Rec(&b)], VECTOR_CIPHER_ID);
        let h = ct5_parse_history(
            frame.clone(),
            i32::from(VECTOR_CIPHER_ID),
            RECORD_VOLTAGE as i32,
        )
        .unwrap();
        assert_eq!(h.start_id, 5);
        assert_eq!(h.slots, 2);
        assert!(!h.end_of_history);
        assert_eq!(h.samples.len(), 2);
        let live = ct5_parse_push(PUSH_A.to_vec(), i32::from(VECTOR_CIPHER_ID)).unwrap();
        assert_eq!(h.samples[0], live);

        assert!(ct5_parse_history(frame.clone(), 256, RECORD_VOLTAGE as i32).is_err());
        assert!(ct5_parse_history(frame.clone(), -1, RECORD_VOLTAGE as i32).is_err());
        assert!(ct5_parse_history(frame, i32::from(VECTOR_CIPHER_ID), 0).is_err());

        assert_eq!(ct5_history_record_size((HISTORY_ENVELOPE + RECORD_VOLTAGE) as i32), 15);
        assert_eq!(ct5_history_record_size(7), 0);
        assert_eq!(ct5_history_batch_size(480, RECORD_VOLTAGE as i32), 30);
        assert_eq!(ct5_history_batch_size(-1, RECORD_VOLTAGE as i32), 1);

        // The builder clamps rather than refusing; paging covers the rest.
        assert_eq!(ct5_build_pull_history(5, 900).unwrap()[3], HISTORY_MAX_BATCH);
        assert_eq!(ct5_build_pull_history(5, 0).unwrap()[3], 1);
        assert!(ct5_build_pull_history(-1, 5).is_err());
        assert!(ct5_build_pull_history(65_536, 5).is_err());
    }

    #[test]
    fn the_glucose_id_is_little_endian_alone_in_this_protocol() {
        // 0x0105 = 261 read little-endian; big-endian would read 0x0501 = 1281.
        let mut payload = vec![0x05u8, 0x01];
        payload.extend(std::iter::repeat_n(0u8, RECORD_VOLTAGE));
        let frame = build_frame(0x35, &payload);
        assert_eq!(frame.len(), PUSH_VOLTAGE);
        assert_eq!(parse_push(&frame, 0).unwrap().glucose_id, 261);
    }

    #[test]
    fn the_ssn_body_includes_its_final_byte_and_may_be_plaintext_or_not() {
        // Plaintext, as an UNBOUND sensor answers: opcode plus 21 characters, no checksum slot.
        let mut plain = vec![0x3F];
        plain.extend_from_slice(ANCHOR.as_bytes());
        assert_eq!(plain.len(), 22);
        assert!(!is_legal(&plain), "the trailing byte is data, not a checksum");

        for key in [None, Some(VECTOR_CIPHER_ID)] {
            let (ssn, kr) = parse_ssn_response(&plain, key).unwrap();
            assert_eq!(ssn, ANCHOR.as_bytes(), "key {key:?}");
            assert_eq!((kr.k_x100, kr.r_x100), (125, 100));
        }

        // Obfuscated, which is what a BOUND sensor answers.
        let mut sealed = vec![0x3F];
        sealed.extend_from_slice(&obfuscate(ANCHOR.as_bytes(), VECTOR_CIPHER_ID));
        let (ssn, kr) = parse_ssn_response(&sealed, Some(VECTOR_CIPHER_ID)).unwrap();
        assert_eq!(ssn, ANCHOR.as_bytes());
        assert_eq!(kr.k_x100, 125);
        assert!(parse_ssn_response(&sealed, None).is_none());
        // Dropping the final byte, as the vendor's drop-last would, loses the grammar's length.
        assert!(parse_ssn_response(&plain[..21], None).is_none());
        assert!(parse_ssn_response(&[0x3F], None).is_none());
        assert!(parse_ssn_response(&[0x30, 1, 2, 3], None).is_none());
    }

    #[test]
    fn a_truncated_ssn_reply_cannot_decode_to_a_confident_wrong_k() {
        // At the default ATT MTU a 22-byte notification arrives as 20, leaving a 19-character body.
        let mut plain = vec![0x3F];
        plain.extend_from_slice(ANCHOR.as_bytes());
        assert!(parse_ssn_response(&plain[..20], None).is_none());
        // 18 characters would decode, to a different K.
        let (_, cut) = parse_ssn_response(&plain[..19], None).unwrap();
        assert_ne!(cut.k_x100, 125);
    }

    #[test]
    fn the_version_reply_must_be_exactly_fourteen_bytes_and_carries_no_checksum() {
        // Reproduces the firmware string the real transmitter reports: V1130_20250618.
        let reply = [0x01u8, 20, 25, 6, 18, b'C', b'1', b'1', b'3', b'0', b'1', b'1', b'0', b'0'];
        assert!(!is_legal(&reply), "all thirteen bytes after the opcode are data");
        let v = parse_version(&reply).unwrap();
        assert_eq!((v.year, v.month, v.day), (2025, 6, 18));
        assert_eq!(v.version, "V1130");
        assert_eq!(v.algorithm, "1100");
        assert_eq!(v.protocol_code, i32::from(b'C'));
        assert!(parse_version(&reply[..13]).is_none());
        assert!(parse_version(&[reply.as_slice(), &[0]].concat()).is_none());
        let mut wrong_op = reply;
        wrong_op[0] = 0x02;
        assert!(parse_version(&wrong_op).is_none());
        // Never panics on an unprintable version string.
        assert_eq!(parse_version(&[0x01u8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]).unwrap().version, "V????");
    }

    #[test]
    fn the_self_check_reply_must_be_exactly_twenty_valid_bytes() {
        for len in 0..26usize {
            let payload: Vec<u8> = (0..len).map(|i| (i as u8).wrapping_mul(13).wrapping_add(3)).collect();
            let reply = build_frame(0x05, &payload);
            assert_eq!(parse_self_check(&reply), reply.len() == SELF_CHECK_REPLY_LEN, "length {}", reply.len());
        }
        let good = build_frame(0x05, &[7u8; 18]);
        assert!(parse_self_check(&good));
        let mut corrupt = good.clone();
        corrupt[19] ^= 1;
        assert!(!parse_self_check(&corrupt), "a bad checksum must abort the bind");
        let mut wrong_op = good;
        wrong_op[0] = 0x06;
        assert!(!parse_self_check(&wrong_op));
    }

    #[test]
    fn set_date_and_init_acknowledgements_accept_what_the_sensor_actually_answers() {
        assert!(parse_set_date_response(&build_frame(0x04, &[1, 2, 3])));
        assert!(parse_set_date_response(&build_frame(0x03, &[126, 8, 17, 11, 46, 13])));
        assert!(!parse_set_date_response(&build_frame(0x05, &[1])));
        assert!(!parse_set_date_response(&[0x04, 0x00]));
        assert!(!parse_set_date_response(&[]));

        assert!(parse_init_response(&build_init()));
        assert!(!parse_init_response(&[0x06, 0x55, 0xAA, 0x06]));
        assert!(!parse_init_response(&build_frame(0x07, &[0x55, 0xAA])));
    }

    #[test]
    fn the_check_id_verdict_separates_a_rejection_from_a_silence() {
        assert_eq!(parse_check_id_response(&build_frame(0x31, &[0, 0, 0, 0, 1])), Ct5CheckId::Accepted);
        assert_eq!(parse_check_id_response(&build_frame(0x31, &[0, 0, 0, 0, 0])), Ct5CheckId::Rejected);
        // Six bytes: [5] is the checksum slot as well as the verdict slot.
        assert_eq!(parse_check_id_response(&build_check_id(&[1, 2, 3, 4]).unwrap()), Ct5CheckId::Ambiguous);
        assert_eq!(parse_check_id_response(&build_frame(0x30, &[0, 0, 0, 0, 1])), Ct5CheckId::Ambiguous);
        let mut corrupt = build_frame(0x31, &[0, 0, 0, 0, 1]);
        corrupt[6] ^= 1;
        assert_eq!(parse_check_id_response(&corrupt), Ct5CheckId::Ambiguous);
        assert_eq!(parse_check_id_response(&[]), Ct5CheckId::Ambiguous);
    }

    #[test]
    fn the_real_advertisement_reads_unbound_and_not_running() {
        // Before it is bound: 26 bytes, all-0xFF telemetry block.
        let mfg: Vec<u8> = {
            let mut v = vec![b'C', b'G', b'M', 0x00];
            v.extend(std::iter::repeat(0xFFu8).take(21));
            v.push(0xEB);
            v
        };
        assert_eq!(mfg.len(), ADVERT_MFG_LEN);
        let a = parse_advert(&mfg).unwrap();
        assert!(!a.bound);
        assert!(!a.running, "the block is all 0xFF until the sensor runs, so no key search is possible");
        assert!(a.checksum_valid, "the sum over [4..25] is the trailing byte");
        assert_eq!(a.record_count, ADVERT_MAX_RECORDS as i32);
        assert_eq!(a.format_tag, 15);

        // A running, bound sensor.
        let mut live = mfg.clone();
        live[3] = 1;
        live[7] = 0x12;
        live[25] = receive_sum(&live, 4, ADVERT_MFG_LEN - 2);
        let b = parse_advert(&live).unwrap();
        assert!(b.bound && b.running && b.checksum_valid);

        // The 4-byte short form carries the flag and nothing else.
        let short = parse_advert(&[b'C', b'G', b'M', 1]).unwrap();
        assert!(short.bound && !short.running && !short.checksum_valid);

        assert!(parse_advert(&[b'C', b'G', b'M']).is_none());
        assert!(parse_advert(&[b'X', b'G', b'M', 0]).is_none());
        let mut bad = mfg;
        bad[25] ^= 1;
        assert!(!parse_advert(&bad).unwrap().checksum_valid);
    }

    #[test]
    fn the_exported_surface_range_checks_the_key_and_never_masks_it() {
        assert!(ct5_parse_push(PUSH_A.to_vec(), 256).is_err());
        assert!(ct5_parse_push(PUSH_A.to_vec(), -1).is_err());
        // A key and that key plus 256 must not be the same key.
        assert!(ct5_parse_push(PUSH_A.to_vec(), 256 + i32::from(VECTOR_CIPHER_ID)).is_err());
        let p = ct5_parse_push(PUSH_A.to_vec(), i32::from(VECTOR_CIPHER_ID)).unwrap();
        assert_eq!(p.glucose_id, 5);
        assert!(!p.glucose_present);
        assert_eq!(p.glucose_mgdl, 0, "absent is carried by the flag, not by the value");
        assert_eq!(p.temp_c_x100, 3104);
        assert_eq!(p.electrodes_mv, vec![1038, 1038, 996, 648]);
        assert!(p.battery_present);
        assert_eq!(p.battery_raw, 1603);

        // -1 means no key held.
        let mut plain = vec![0x3F];
        plain.extend_from_slice(ANCHOR.as_bytes());
        let id = ct5_parse_ssn_response(plain.clone(), -1).unwrap();
        assert_eq!(id.ssn, ANCHOR);
        assert_eq!((id.k_x100, id.r_x100), (125, 100));
        assert!(ct5_parse_ssn_response(plain, 256).is_err());

        assert_eq!(
            ct5_build_set_parameters(125, 100, 3, 16, "1234".into(), 0x5A).unwrap(),
            SET_PARAMETERS_VECTOR,
        );
        assert!(ct5_build_set_parameters(125, 100, 3, 16, "1234".into(), 300).is_err());
        assert!(ct5_build_set_parameters(125, 100, 3, 16, "42".into(), 0x5A).is_err());
        assert!(ct5_verify_set_parameters_echo(
            SET_PARAMETERS_VECTOR.to_vec(), 125, 100, 3, 16, "1234".into(), 0x5A,
        )
        .unwrap());
        assert_eq!(ct5_parse_check_id_response(build_frame(0x31, &[0, 0, 0, 0, 1])), 1);
        assert_eq!(ct5_parse_check_id_response(build_frame(0x31, &[0, 0, 0, 0, 0])), 0);
        assert_eq!(ct5_parse_check_id_response(vec![]), -1);
        assert!(ct5_frame_is_legal(build_init()));
        assert!(!ct5_frame_is_legal(vec![0x06, 0x55, 0xAA, 0x00]));
    }

    #[test]
    fn ct5_fuzz_never_panics_and_every_acceptance_is_self_consistent() {
        // Release panic="abort"; a hostile-frame panic tears down the alarm path too.
        let mut state: u64 = 0x0BAD_C0DE_DEAD_BEEF;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        // Real opcodes, or only the rejection paths are ever exercised.
        const OPCODES: [u8; 9] = [0x35, 0x3F, 0x30, 0x31, 0x38, 0x05, 0x06, 0x04, 0x01];
        let mut pushes = 0u32;
        let mut keys = 0u32;
        for i in 0..60_000u32 {
            let len = (next() % 40) as usize;
            let body: Vec<u8> = (0..len).map(|_| next() as u8).collect();
            let key = next() as u8;
            // Half well-formed frames (correct additive checksum, a real opcode), half raw garbage.
            let frame = if i % 2 == 0 {
                build_frame(OPCODES[(next() % OPCODES.len() as u64) as usize], &body)
            } else {
                body
            };

            if let Some(p) = parse_push(&frame, key) {
                pushes += 1;
                assert!(is_legal(&frame) && frame[0] == 0x35);
                assert!(matches!(frame.len(), PUSH_SHORT | PUSH_VOLTAGE));
                assert!(p.record.glucose_mgdl.is_none_or(|g| g <= 0x0FFF));
                assert_eq!(p.record.battery.is_some(), frame.len() == PUSH_VOLTAGE);
                assert_eq!(p.record.electrodes_mv.is_some(), frame.len() == PUSH_VOLTAGE);
            }
            if let Some((ssn, kr)) = parse_ssn_response(&frame, Some(key)) {
                assert!(matches!(ssn.len(), 17 | 18 | 21));
                assert!(kr.k_x100 >= 0 && kr.r_x100 >= 0);
                assert_eq!(kr.k_x100 as f32 / 100.0, kr.k);
            }
            // The echo this frame carries, or the nonce gate swallows the whole corpus.
            let echo: Vec<u8> = frame.iter().skip(1).take(NONCE_LEN).copied().collect();
            if let Some(k) = cipher_id_from_set_id_reply(&frame, &[1, 2, 3, 4], &echo) {
                keys += 1;
                assert_eq!(frame.len(), SET_ID_REPLY_LEN);
                assert_eq!(cipher_id_from_set_id_reply(&frame, &[1, 2, 3, 4], &echo), Some(k));
            }
            let _ = verify_set_parameters_echo(&frame, 125, 100, 3, 16, b"1234", key);
            let _ = parse_version(&frame);
            let _ = parse_self_check(&frame);
            let _ = parse_advert(&frame);
            let _ = parse_check_id_response(&frame);
            let _ = parse_set_date_response(&frame);
            let _ = parse_init_response(&frame);
            let _ = ct5_parse_push(frame.clone(), i32::from(key));
            let _ = ct5_parse_advert(frame);
        }
        // Floors well under expectation; they catch a dead generator, not a shortfall.
        assert!(pushes > 40, "the push accept path barely ran ({pushes})");
        assert!(keys > 20, "the setID accept path barely ran ({keys})");
    }
}

#[cfg(test)]
mod key_recovery_tests {
    use super::*;

    /// Real frame, wrongly derived key; 0x69/0x96 alone decode to a physical value.
    const CAPTURED: &str = "350C00C3C3C2FB00F7C3C3C3A758A1E53E39C0";

    fn frame() -> Vec<u8> {
        (0..CAPTURED.len() / 2)
            .map(|i| u8::from_str_radix(&CAPTURED[i * 2..i * 2 + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn every_key_that_decodes_the_frame_is_offered() {
        let all = ct5_push_under_every_key(frame());
        assert!(all.len() > 200, "the checksum covers the ciphertext, so most keys decode");
        for c in &all {
            assert!((0..=255).contains(&c.cipher_id));
            assert_eq!(c.push.glucose_id, 12, "the id is outside the obfuscated region");
        }
    }

    fn pairs_passing(temp_and_error_only: bool) -> Vec<i32> {
        let accepted = [0u8, 4, 5, 105];
        let mut pairs: Vec<i32> = ct5_push_under_every_key(frame())
            .into_iter()
            .filter(|c| {
                (1200..=4800).contains(&c.push.temp_c_x100)
                    && accepted.contains(&(c.push.error_code as u8))
                    && (temp_and_error_only || c.push.ib_x100 == 0)
            })
            .map(|c| c.cipher_id.min(c.cipher_id ^ 0xFF))
            .collect();
        pairs.sort_unstable();
        pairs.dedup();
        pairs
    }

    /// A physical temperature and an accepted error code are NOT enough on their own.
    #[test]
    fn temperature_and_error_alone_leave_two_candidates() {
        assert_eq!(pairs_passing(true), vec![0x69, 0x6A]);
    }

    /// Background current settles it; reads zero on this family, the impostor decodes it nonzero.
    #[test]
    fn the_background_current_narrows_the_captured_frame_to_one_pair() {
        assert_eq!(pairs_passing(false), vec![0x69]);
    }

    #[test]
    fn a_key_and_its_complement_always_survive_together() {
        let keys: Vec<i32> = ct5_push_under_every_key(frame()).into_iter().map(|c| c.cipher_id).collect();
        for k in &keys {
            assert!(keys.contains(&(k ^ 0xFF)), "{k} decoded but {} did not", k ^ 0xFF);
        }
    }

    #[test]
    fn the_true_key_reproduces_the_live_parser() {
        let direct = parse_push(&frame(), 0x69).unwrap();
        let searched = ct5_push_under_every_key(frame())
            .into_iter()
            .find(|c| c.cipher_id == 0x69)
            .unwrap();
        assert_eq!(searched.push, Ct5Push::from(direct));
        assert_eq!(searched.push.temp_c_x100, 2992);
        assert_eq!(searched.push.iw_x100, 841);
        assert_eq!(searched.push.error_code, 0);
    }

    #[test]
    fn a_frame_that_is_not_a_push_offers_no_key() {
        assert!(ct5_push_under_every_key(vec![]).is_empty());
        assert!(ct5_push_under_every_key(vec![0x37, 0x00, 0x00, 0x37]).is_empty());
    }
}
