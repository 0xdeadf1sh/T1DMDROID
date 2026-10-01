//! White-box AES VM (PLAN_T1DMDROID.md §5.5/§5.6 KAuth paths): a 13-opcode bytecode VM over
//! ~3 MB of lookup tables. The "key" is the tables — there is no separate key input. Ported
//! 1:1 from the kit's CipherFn.swift (cleanroom_cipher_fn/cipher_fn_v3.py lineage).

use crate::CryptoError;

pub const SCRATCH_SIZE: usize = 0x2500;

/// The cipher-fn table set, validated on load.
pub struct CipherFnTables {
    pub sbox19: Vec<u8>,
    pub sbox12: Vec<u8>,
    pub decode: Vec<u8>,
    pub params: Vec<u8>,
    pub bytecode: Vec<u8>,
    pub t5_seed: Vec<u8>,
    pub singleton: Vec<u8>,
    pub phase2_pairs: Vec<(u16, u16)>,
}

const SIZES: &[(&str, usize)] = &[
    ("sbox_19bit_lib_986819", 524_288),
    ("sbox_12bit_full", 2_097_152),
    ("decode_table_lib_237dcc", 65_536),
    ("params_lib_22a1a0", 56_364),
    ("bytecode_lib_b25d20", 413_696),
    ("t5_seed_lib_b25708", 1_560),
    ("singleton_4k", 16_384),
    ("phase2_pairs", 2_176),
];

impl CipherFnTables {
    pub fn from_dir(dir: &std::path::Path) -> Result<CipherFnTables, CryptoError> {
        let read = |name: &str, want: usize| -> Result<Vec<u8>, CryptoError> {
            let bytes = std::fs::read(dir.join(format!("{name}.bin")))
                .map_err(|_| CryptoError::TablesMissing { name: name.to_owned() })?;
            if bytes.len() != want {
                return Err(CryptoError::TablesSize {
                    name: name.to_owned(),
                    want,
                    got: bytes.len(),
                });
            }
            Ok(bytes)
        };
        let mut loaded: [Vec<u8>; 8] = [Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new()];
        for (i, (name, want)) in SIZES.iter().enumerate() {
            loaded[i] = read(name, *want)?;
        }
        let [sbox19, sbox12, decode, params, bytecode, t5_seed, singleton, pairs_data] = loaded;
        let mut phase2_pairs = Vec::with_capacity(pairs_data.len() / 4);
        for i in (0..pairs_data.len()).step_by(4) {
            phase2_pairs.push((
                u16::from_le_bytes([pairs_data[i], pairs_data[i + 1]]),
                u16::from_le_bytes([pairs_data[i + 2], pairs_data[i + 3]]),
            ));
        }
        Ok(CipherFnTables {
            sbox19,
            sbox12,
            decode,
            params,
            bytecode,
            t5_seed,
            singleton,
            phase2_pairs,
        })
    }
}

/// `CipherFn.aes_K`: bundled singleton + bytecode, 16-byte block in and out.
pub fn aes_k(input: &[u8], t: &CipherFnTables) -> Result<[u8; 16], CryptoError> {
    if input.len() != 16 {
        return Err(CryptoError::LibAes {
            reason: format!("cipher fn input must be 16 bytes, got {}", input.len()),
        });
    }
    run(input, &t.singleton, &t.bytecode, t)
}

/// The 16 input slots, in wire order.
const INPUT_OFFSETS: [usize; 16] = [
    0x0f6e, 0x1c8d, 0x100d, 0x0a61, 0x065e, 0x1134, 0x1a14, 0x1da2, 0x1963, 0x0801, 0x2133, 0x17f6,
    0x0980, 0x158d, 0x0d7b, 0x0b46,
];

/// Output extraction is a permutation of the input slots.
const OUTPUT_PERM: [usize; 16] = [9, 0, 3, 2, 11, 7, 8, 5, 14, 4, 15, 13, 1, 6, 12, 10];

fn dispatch_to_opcode(next_disp: i32) -> Option<u8> {
    match next_disp {
        228 => Some(0),
        56 => Some(1),
        360 => Some(2),
        552 => Some(3),
        456 => Some(4),
        820 => Some(5),
        504 => Some(6),
        600 => Some(7),
        408 => Some(8),
        320 => Some(9),
        648 => Some(10),
        780 => Some(11),
        868 => Some(12),
        _ => None,
    }
}

pub fn run(
    input: &[u8],
    singleton: &[u8],
    bytecode: &[u8],
    t: &CipherFnTables,
) -> Result<[u8; 16], CryptoError> {
    if input.len() != 16 {
        return Err(CryptoError::LibAes {
            reason: format!("cipher fn input must be 16 bytes, got {}", input.len()),
        });
    }
    let mut scratch = vec![0u8; SCRATCH_SIZE];
    let seed = &t.t5_seed;
    let n = seed.len().min(SCRATCH_SIZE);
    scratch[..n].copy_from_slice(&seed[..n]);
    for i in 0..16 {
        scratch[INPUT_OFFSETS[i]] = input[i];
    }
    for (src_off, dst_off) in &t.phase2_pairs {
        let s = *src_off as usize;
        let d = *dst_off as usize;
        if s + 6 <= singleton.len() && d + 6 <= SCRATCH_SIZE {
            scratch[d..d + 6].copy_from_slice(&singleton[s..s + 6]);
        }
    }

    run_vm(&mut scratch, bytecode, t)?;

    let mut out = [0u8; 16];
    for i in 0..16 {
        out[i] = scratch[INPUT_OFFSETS[OUTPUT_PERM[i]]];
    }
    Ok(out)
}

// MARK: - VM dispatcher

fn run_vm(scratch: &mut [u8], bytecode: &[u8], t: &CipherFnTables) -> Result<(), CryptoError> {
    let num_insns = bytecode.len() / 24;
    let mut next_op: Option<u8> = Some(0);

    for i in 0..num_insns {
        let Some(op) = next_op else { return Ok(()) };
        let base = i * 24;
        let operand_lo = read_u32(bytecode, base);
        let operand_hi = read_u32(bytecode, base + 4);
        let field8 = read_u16(bytecode, base + 8) as usize;
        let field_a = read_u16(bytecode, base + 10) as usize;
        let field_c = read_u16(bytecode, base + 12) as usize;
        let next_disp = read_i32(bytecode, base + 16);

        match op {
            0 => op_encode(scratch, operand_lo, field8, field_a, field_c, t),
            1 => op_memcpy(scratch, operand_lo, operand_hi, field8, field_a),
            2 => op_51f8(scratch, operand_lo, field8, field_a, t),
            5 => op_4ae4(scratch, operand_lo, field8, field_a, t),
            9 => op_decode(scratch, operand_lo, field8, field_a, t),
            11 => op_shiftrows(scratch, operand_lo, field8, field_a),
            3 | 4 | 6 | 7 | 8 | 10 | 12 => op_3bit_chain(
                scratch,
                operand_lo,
                field8,
                field_a,
                field_c,
                sbc_ops(op),
                t,
            ),
            _ => {}
        }

        next_op = dispatch_to_opcode(next_disp);
    }
    Ok(())
}

// MARK: - Iter descriptors for the 7 three-bit-chain opcodes

/// Mirrors the kit's SBC_OPS iteration descriptors exactly.
enum SbcIter {
    Full { s1: usize, s2: usize, p: usize, d: Option<usize> },
    NoSrc1 { s2: usize, p: usize, d: Option<usize> },
    PrmOnly { p: usize, d: Option<usize> },
}

const fn sbc_full(i: usize) -> SbcIter {
    SbcIter::Full { s1: i, s2: i, p: i, d: Some(i) }
}

const fn sbc_full_nd(i: usize) -> SbcIter {
    SbcIter::Full { s1: i, s2: i, p: i, d: None }
}

const fn sbc_full_shift(i: usize, shift: usize) -> SbcIter {
    SbcIter::Full { s1: i, s2: i, p: i, d: Some(i - shift) }
}

const fn sbc_nosrc1(i: usize) -> SbcIter {
    SbcIter::NoSrc1 { s2: i, p: i, d: Some(i) }
}

const fn sbc_prm_only(p: usize, d: usize) -> SbcIter {
    SbcIter::PrmOnly { p, d: Some(d) }
}

fn sbc_ops(op: u8) -> &'static [SbcIter] {
    // Max 8 iters; trailing entries are the empty never-matched filler.
    // Eager 'static table; each row is boxed once, then borrowed forever.
    static OPS: std::sync::OnceLock<[&'static [SbcIter]; 13]> = std::sync::OnceLock::new();
    OPS.get_or_init(|| {
        let mk = |v: Vec<SbcIter>| &*Box::leak(v.into_boxed_slice());
        let mut table: [&'static [SbcIter]; 13] = [&[]; 13];
        table[12] = mk(vec![sbc_full(0), sbc_full(1), sbc_full(2), sbc_full(3), sbc_full(4), sbc_full(5)]);
        table[10] = mk(vec![sbc_full(0), sbc_full(1), sbc_full(2), sbc_full(3), sbc_nosrc1(4), sbc_nosrc1(5)]);
        table[7] = mk(vec![sbc_full(0), sbc_full(1), sbc_full(2), sbc_full(3)]);
        table[3] = mk(vec![sbc_full_nd(0), sbc_full_nd(1), sbc_full_shift(2, 2), sbc_full_shift(3, 2), sbc_full_shift(4, 2), sbc_full_shift(5, 2)]);
        table[6] = mk(vec![sbc_full_nd(0), sbc_full_shift(1, 1), sbc_full_shift(2, 1), sbc_full_shift(3, 1), sbc_full_shift(4, 1)]);
        table[4] = mk(vec![sbc_full_nd(0), sbc_full_nd(1), sbc_full_nd(2), sbc_full_shift(3, 3), sbc_full_shift(4, 3), sbc_full_shift(5, 3), sbc_prm_only(6, 3)]);
        table[8] = mk(vec![sbc_full_nd(0), sbc_full_nd(1), sbc_full_shift(2, 2), sbc_full_shift(3, 2), sbc_full_shift(4, 2), sbc_full_shift(5, 2), sbc_prm_only(6, 4), sbc_prm_only(7, 5)]);
        table
    })[op as usize]
}

// MARK: - Helpers

#[inline]
fn read_u16(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes([b[off], b[off + 1]])
}

#[inline]
fn read_u32(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

#[inline]
fn read_i32(b: &[u8], off: usize) -> i32 {
    i32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

#[inline]
fn sb_get(scratch: &[u8], off: usize) -> u8 {
    if off < SCRATCH_SIZE {
        scratch[off]
    } else {
        0
    }
}

#[inline]
fn sb_set(scratch: &mut [u8], off: usize, val: usize) {
    if off < SCRATCH_SIZE {
        scratch[off] = (val & 0xff) as u8;
    }
}

#[inline]
fn safe_sbox12(t: &CipherFnTables, idx: usize) -> usize {
    let byte_idx = idx & !1;
    if byte_idx + 2 > t.sbox12.len() {
        return 0;
    }
    (t.sbox12[byte_idx] as usize) | ((t.sbox12[byte_idx + 1] as usize) << 8)
}

#[inline]
fn safe_sbox19(t: &CipherFnTables, idx: usize) -> usize {
    if idx < t.sbox19.len() {
        t.sbox19[idx] as usize
    } else {
        0
    }
}

#[inline]
fn safe_params(t: &CipherFnTables, idx: usize) -> usize {
    if idx < t.params.len() {
        t.params[idx] as usize
    } else {
        0
    }
}

// MARK: - Opcode handlers

fn op_encode(
    scratch: &mut [u8],
    operand: u32,
    field8: usize,
    field_a: usize,
    _field_c: usize,
    t: &CipherFnTables,
) {
    // Inline_4794 (opcode 0): byte → 6-byte 3-bit encode via the decode table.
    let src_byte = sb_get(scratch, field8) as usize;
    let base = operand as usize + src_byte * 3;
    let (mut b0, mut b1, mut b2) = (0usize, 0usize, 0usize);
    if base + 3 <= t.decode.len() {
        b0 = t.decode[base] as usize;
        b1 = t.decode[base + 1] as usize;
        b2 = t.decode[base + 2] as usize;
    }
    sb_set(scratch, field_a + 0, b0 & 0x07);
    sb_set(scratch, field_a + 1, b0 >> 3);
    sb_set(scratch, field_a + 2, b1 & 0x07);
    sb_set(scratch, field_a + 3, b1 >> 3);
    sb_set(scratch, field_a + 4, b2 & 0x07);
    sb_set(scratch, field_a + 5, b2 >> 3);
}

fn op_memcpy(scratch: &mut [u8], operand_lo: u32, operand_hi: u32, field8: usize, field_a: usize) {
    // Inline_4840 (opcode 1): generic memcpy with overlap support.
    let src_adj = (operand_lo & 0xffff) as isize;
    let dst_adj = ((operand_lo >> 16) & 0xffff) as isize;
    let length = (operand_hi & 0xffff) as usize;
    let src = field8 as isize + src_adj;
    let dst = field_a as isize + dst_adj;
    if src >= 0
        && src as usize + length <= SCRATCH_SIZE
        && dst >= 0
        && dst as usize + length <= SCRATCH_SIZE
        && length > 0
    {
        if dst < src {
            for i in 0..length {
                scratch[dst as usize + i] = scratch[src as usize + i];
            }
        } else {
            for i in (0..length).rev() {
                scratch[dst as usize + i] = scratch[src as usize + i];
            }
        }
    }
}

fn op_3bit_chain(
    scratch: &mut [u8],
    operand: u32,
    src1_off: usize,
    src2_off: usize,
    dst_off: usize,
    iters: &'static [SbcIter],
    t: &CipherFnTables,
) {
    let params_offset = operand as usize;
    let mut carry: usize = 0;
    for it in iters {
        let idx: usize;
        let dst_i: Option<usize>;
        match it {
            SbcIter::Full { s1, s2, p, d } => {
                let s1 = sb_get(scratch, src1_off + s1) as usize;
                let s2 = sb_get(scratch, src2_off + s2) as usize;
                let prm = safe_params(t, params_offset + p);
                let part = ((carry ^ s1) & 0xff) | (s2 << 8);
                idx = (part ^ (prm << 11)) & 0x7_ffff;
                dst_i = *d;
            }
            SbcIter::NoSrc1 { s2, p, d } => {
                let s2 = sb_get(scratch, src2_off + s2) as usize;
                let prm = safe_params(t, params_offset + p);
                idx = ((carry | (s2 << 8)) ^ (prm << 11)) & 0x7_ffff;
                dst_i = *d;
            }
            SbcIter::PrmOnly { p, d } => {
                let prm = safe_params(t, params_offset + p);
                idx = (carry | (prm << 11)) & 0x7_ffff;
                dst_i = *d;
            }
        }
        let byte = safe_sbox19(t, idx);
        carry = byte & 0xf8;
        if let Some(di) = dst_i {
            sb_set(scratch, dst_off + di, byte & 0x07);
        }
    }
}

fn op_4ae4(scratch: &mut [u8], operand: u32, src_off: usize, dst_off: usize, t: &CipherFnTables) {
    // Op_4ae4 (opcode 5): halfword 12-bit chain, 9 lookups → 6 output bytes.
    let params_offset = operand as usize;
    let sbox12 = &t.sbox12;
    #[inline]
    fn s12_at(sbox12: &[u8], idx: usize) -> usize {
        let b = idx & !1;
        if b + 2 > sbox12.len() {
            return 0;
        }
        (sbox12[b] as usize) | ((sbox12[b + 1] as usize) << 8)
    }
    let src = |scratch: &[u8], i: usize| sb_get(scratch, src_off + i) as usize;
    macro_rules! prm {
        ($i:expr) => {
            safe_params(t, params_offset + $i) as usize
        };
    }

    let mut idx = (src(scratch, 0) << 1) | (prm!(0) << 13);
    let mut state: usize = 0;
    let mut lookup: usize = 0;
    for i in 1..=3 {
        lookup = s12_at(sbox12, idx);
        state = lookup & 0xff8;
        idx = ((state ^ src(scratch, i)) << 1) | (prm!(i) << 13);
    }
    lookup = s12_at(sbox12, idx);
    sb_set(scratch, dst_off, lookup & 0x07);
    state = lookup & 0xff8;

    // dst[1..2] use the masked-state index.
    for (k, (si, pi)) in [(4usize, 4usize), (5, 5)].iter().enumerate() {
        idx = ((state ^ src(scratch, *si)) << 1) | (prm!(*pi) << 13);
        lookup = s12_at(sbox12, idx);
        sb_set(scratch, dst_off + 1 + k, lookup & 0x07);
        if k == 0 {
            state = lookup & 0xff8;
        }
    }
    // dst[3..5] use the full unmasked previous lookup (the dst[2] one).
    let mut prev = lookup;
    for (k, (si, pi)) in [(2usize, 6usize), (3, 7), (4, 8)].iter().enumerate() {
        idx = ((prev ^ src(scratch, *si)) << 1) ^ (prm!(*pi) << 13);
        lookup = s12_at(sbox12, idx);
        sb_set(scratch, dst_off + 3 + k, lookup & 0x07);
        prev = lookup;
    }
}

fn op_51f8(scratch: &mut [u8], operand: u32, src_off: usize, dst_off: usize, t: &CipherFnTables) {
    // Op_51f8 (opcode 2): 4-output halfword 12-bit chain.
    let params_offset = operand as usize;
    let sbox12 = &t.sbox12;
    #[inline]
    fn s12_at(sbox12: &[u8], idx: usize) -> usize {
        let b = idx & !1;
        if b + 2 > sbox12.len() {
            return 0;
        }
        (sbox12[b] as usize) | ((sbox12[b + 1] as usize) << 8)
    }
    let src = |scratch: &[u8], i: usize| sb_get(scratch, src_off + i) as usize;
    macro_rules! prm {
        ($i:expr) => {
            safe_params(t, params_offset + $i) as usize
        };
    }

    let mut idx = (src(scratch, 0) << 1) | (prm!(0) << 13);
    let mut state: usize = 0;
    let mut lookup: usize = 0;
    for i in 1..=3 {
        lookup = s12_at(sbox12, idx);
        state = lookup & 0xff8;
        idx = ((state ^ src(scratch, i)) << 1) | (prm!(i) << 13);
    }
    lookup = s12_at(sbox12, idx);
    sb_set(scratch, dst_off, lookup & 0x07);
    state = lookup & 0xff8;

    // dst[1]
    idx = ((state ^ src(scratch, 4)) << 1) | (prm!(4) << 13);
    lookup = s12_at(sbox12, idx);
    sb_set(scratch, dst_off + 1, lookup & 0x07);
    state = lookup & 0xff8;

    // dst[2]
    idx = ((state ^ src(scratch, 5)) << 1) | (prm!(5) << 13);
    lookup = s12_at(sbox12, idx);
    sb_set(scratch, dst_off + 2, lookup & 0x07);

    // dst[3]: special — idx = (src[2] | (prm[6]<<12)) XOR the full unmasked dst[2] lookup.
    let special_idx = (src(scratch, 2) | (prm!(6) << 12)) ^ lookup;
    let byte_off = (special_idx << 1) & 0x1f_ffff;
    let mut lookup2 = 0usize;
    if byte_off + 2 <= sbox12.len() {
        lookup2 = (sbox12[byte_off] as usize) | ((sbox12[byte_off + 1] as usize) << 8);
    }
    sb_set(scratch, dst_off + 3, lookup2 & 0x07);
}

fn op_decode(scratch: &mut [u8], operand_lo: u32, field8: usize, field_a: usize, t: &CipherFnTables) {
    // Inline_4738 (opcode 9): nibble combiner, 3-bit pairs → 8-bit decode.
    let src_off_lo = ((operand_lo as usize) & 0x1fff) << 2;
    let src_off_hi = ((operand_lo >> 13) as usize) & 0x7_ffff;
    let src_base = field8 + src_off_lo;
    let s2 = sb_get(scratch, src_base + 2) as usize;
    let s3 = sb_get(scratch, src_base + 3) as usize;
    let s4 = sb_get(scratch, src_base + 4) as usize;
    let s5 = sb_get(scratch, src_base + 5) as usize;
    let idx1 = s4 ^ (s5 << 3);
    let idx2 = s2 ^ (s3 << 3);
    let (mut lookup1, mut lookup2) = (0usize, 0usize);
    if src_off_hi + idx1 < t.decode.len() && src_off_hi + idx2 < t.decode.len() {
        lookup1 = t.decode[src_off_hi + idx1] as usize;
        lookup2 = t.decode[src_off_hi + idx2] as usize;
    }
    let out_byte = (lookup1 & 0xf0) | (lookup2 & 0x0f);
    sb_set(scratch, field_a, out_byte);
}

fn op_shiftrows(scratch: &mut [u8], operand: u32, field8: usize, field_a: usize) {
    // Inline_456c (opcode 11): backwards copy with bound check + fill.
    let length = (operand & 0x1ff) as usize;
    let src_limit = ((operand >> 9) & 0x1ff) as usize;
    let start = ((operand >> 18) & 0x1ff) as usize;
    let fill = ((operand >> 27) & 0x07) as usize;
    if length == 0 {
        return;
    }
    let src_base = field8;
    let dst_base = field_a;

    if start.saturating_sub(1) < length {
        sb_set(scratch, dst_base + length - 1, fill);
        if length > 1 {
            for i in 0..length - 1 {
                sb_set(scratch, dst_base + i, 0);
            }
        }
        return;
    }

    let mut w10 = start - 1;
    while w10 >= length {
        let src_idx = w10 - length;
        let byte: usize = if src_idx < src_limit {
            sb_get(scratch, src_base + src_idx) as usize
        } else {
            0
        };
        sb_set(scratch, dst_base + w10, byte);
        w10 -= 1;
    }
    sb_set(scratch, dst_base + length - 1, fill);
    if length > 1 {
        for i in 0..length - 1 {
            sb_set(scratch, dst_base + i, 0);
        }
    }
}