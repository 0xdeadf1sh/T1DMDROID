//! FirstPairSourceSlice VM primitives (kit: FirstPairSourceSlice.swift lines 13484-13833).
//!
//! Two state machines over bundled tables:
//! - `step`: an 8-bit state over the 19-bit sbox; index = (state&0xf8) ^ src1 | src2<<8 ^ prog<<11.
//! - `step16_masked`/`step16_full`: a 16-bit state over the child23 ttable-B halfwords.
//! The `vm*` runners decode a `magic` u64 into (prog offset, primer, count, tail) and drive
//! the state machine over the program bytes:
//!   prog_off = magic & 0x3f_ffff; count = (magic >> 36) & 0x3fff; tail = magic >> 50;
//!   primer   = (magic >> 22) & 0x3fff  (primer-style runners only).

use super::tables::FirstPairTables;
use crate::CryptoError;

pub(crate) fn slice_err(reason: impl Into<String>) -> CryptoError {
    CryptoError::Slice { reason: reason.into() }
}

/// `checkedSlice` from the kit: bounds-checked sub-slice.
pub(crate) fn checked_slice<'a>(
    bytes: &'a [u8],
    offset: usize,
    count: usize,
    name: &str,
) -> Result<&'a [u8], CryptoError> {
    let end = offset
        .checked_add(count)
        .ok_or_else(|| slice_err(format!("slice overflow: {name}")))?;
    bytes.get(offset..end).ok_or_else(|| slice_err(format!("table read out of bounds: {name} at {offset}")))
}

pub(crate) fn require(bytes: &[u8], count: usize, label: &str) -> Result<(), CryptoError> {
    if bytes.len() < count {
        return Err(slice_err(format!("source too short: {label} has {}, wants {count}", bytes.len())));
    }
    Ok(())
}

pub(crate) fn read_u32_le(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]])
}

pub(crate) fn read_u64_le(bytes: &[u8], offset: usize) -> u64 {
    let mut v = 0u64;
    for i in 0..8 {
        v |= (bytes[offset + i] as u64) << (i * 8);
    }
    v
}

pub(crate) fn write_u32_le(value: u32, out: &mut [u8], offset: usize) {
    out[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

pub(crate) fn write_u64_le(value: u64, out: &mut [u8], offset: usize) {
    out[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}

pub(crate) fn replace_at(dst: &mut [u8], offset: usize, src: &[u8]) {
    dst[offset..offset + src.len()].copy_from_slice(src);
}

/// The 8-bit state machine step (kit `step`). `prog` selects the sbox sub-page.
pub(crate) fn step(
    state: usize,
    src1: Option<u8>,
    src2: Option<u8>,
    prog: u8,
    sbox: &[u8],
) -> Result<usize, CryptoError> {
    let mut idx = state & 0xf8;
    if let Some(s1) = src1 {
        idx ^= s1 as usize;
    }
    if let Some(s2) = src2 {
        idx |= (s2 as usize) << 8;
    }
    idx ^= (prog as usize) << 11;
    sbox
        .get(idx)
        .copied()
        .map(|b| b as usize)
        .ok_or_else(|| slice_err(format!("sbox19 read out of bounds at {idx}")))
}

fn ttable_halfword(byte_offset: usize, ttable: &[u8]) -> Result<usize, CryptoError> {
    let hi = byte_offset
        .checked_add(1)
        .ok_or_else(|| slice_err("ttableBExt byte offset overflow"))?;
    let lo = ttable
        .get(byte_offset)
        .copied()
        .ok_or_else(|| slice_err(format!("ttableBExt read out of bounds at {byte_offset}")))?;
    let hi = ttable
        .get(hi)
        .copied()
        .ok_or_else(|| slice_err(format!("ttableBExt read out of bounds at {byte_offset}")))?;
    Ok(lo as usize | ((hi as usize) << 8))
}

/// 16-bit step, masked src (kit `step16Masked`).
fn step16_masked(state: usize, src: u8, prog: u8, ttable: &[u8]) -> Result<usize, CryptoError> {
    let byte_offset = (((state & 0xff8) ^ src as usize) << 1) | ((prog as usize) << 13);
    ttable_halfword(byte_offset, ttable)
}

/// 16-bit step, full state XOR src (kit `step16Full`).
fn step16_full(state: usize, src: u8, prog: u8, ttable: &[u8]) -> Result<usize, CryptoError> {
    let byte_offset = ((prog as usize) << 13) ^ ((state ^ src as usize) << 1);
    ttable_halfword(byte_offset, ttable)
}

/// kit `vm64e2b8` — program from `firstpair_prog_64e2b8_3041b4`.
pub fn vm64e2b8(magic: u64, src1: &[u8], src2: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    vm_pair(magic, src1, src2, &t.prog64e2b8, "64e2b8", &t.sbox19)
}

/// kit `vm638840` — program from `firstpair_prog_638840_2f5046`.
pub fn vm638840(magic: u64, src1: &[u8], src2: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    vm_pair(magic, src1, src2, &t.prog638840, "638840", &t.sbox19)
}

/// kit `vm67cc18` — program from `firstpair_prog_67cc18_369862`.
pub fn vm67cc18(magic: u64, src1: &[u8], src2: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    vm_pair(magic, src1, src2, &t.prog67cc18, "67cc18", &t.sbox19)
}

/// kit `vm67076c` — fixed 66-byte block transform over `firstpair_prog_67076c_35d3ef`.
pub fn vm67076c(magic: u64, src1: &[u8], src2: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    const BLOCK66: usize = 0x42;
    let prog_off = (magic & 0x3f_ffff) as usize;
    let prog = checked_slice(&t.prog67076c, prog_off, BLOCK66, "prog67076c")?;
    require(src1, BLOCK66, "vm67076c src1")?;
    require(src2, BLOCK66, "vm67076c src2")?;
    let mut state = 0usize;
    let mut out = vec![0u8; BLOCK66];
    for i in 0..BLOCK66 {
        state = step(state, Some(src1[i]), Some(src2[i]), prog[i], &t.sbox19)?;
        out[i] = (state & 7) as u8;
    }
    Ok(out)
}

/// Shared shape of `vm64e2b8`/`vm638840`/`vm67cc18`: count paired steps, then tail steps
/// driven by src2 only.
fn vm_pair(
    magic: u64,
    src1: &[u8],
    src2: &[u8],
    prog_table: &[u8],
    label: &str,
    sbox: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let prog_off = (magic & 0x3f_ffff) as usize;
    let count = ((magic >> 36) & 0x3fff) as usize;
    let tail = (magic >> 50) as usize;
    let total = count + tail;
    let prog = checked_slice(prog_table, prog_off, total, label)?;
    require(src1, count, &format!("vm{label} src1"))?;
    require(src2, total, &format!("vm{label} src2"))?;

    let mut state = 0usize;
    let mut out = vec![0u8; total];
    for i in 0..count {
        state = step(state, Some(src1[i]), Some(src2[i]), prog[i], sbox)?;
        out[i] = (state & 7) as u8;
    }
    for i in 0..tail {
        let pos = count + i;
        state = step(state, None, Some(src2[pos]), prog[pos], sbox)?;
        out[pos] = (state & 7) as u8;
    }
    Ok(out)
}

/// kit `vm67cecc` — primer + count paired steps, then tail steps from prog only; program
/// from `firstpair_prog_67cc18_369862`.
pub fn vm67cecc(magic: u64, src1: &[u8], src2: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    vm_primed(magic, src1, src2, &t.prog67cc18, "67cecc", &t.sbox19)
}

/// kit `vm6420d8` — primer + count paired steps, then tail steps from prog only; program
/// from `firstpair_prog_638840_2f5046`.
pub fn vm6420d8(magic: u64, src1: &[u8], src2: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    vm_primed(magic, src1, src2, &t.prog638840, "6420d8", &t.sbox19)
}

fn vm_primed(
    magic: u64,
    src1: &[u8],
    src2: &[u8],
    prog_table: &[u8],
    label: &str,
    sbox: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let prog_off = (magic & 0x3f_ffff) as usize;
    let primer = ((magic >> 22) & 0x3fff) as usize;
    let count = ((magic >> 36) & 0x3fff) as usize;
    let tail = (magic >> 50) as usize;
    let total_prog = primer + count + tail;
    let prog = checked_slice(prog_table, prog_off, total_prog, label)?;
    require(src1, primer + count, &format!("vm{label} src1"))?;
    require(src2, primer + count, &format!("vm{label} src2"))?;

    let mut state = 0usize;
    for i in 0..primer {
        state = step(state, Some(src1[i]), Some(src2[i]), prog[i], sbox)?;
    }
    let mut out = vec![0u8; count + tail];
    for i in 0..count {
        let pos = primer + i;
        state = step(state, Some(src1[pos]), Some(src2[pos]), prog[pos], sbox)?;
        out[i] = (state & 7) as u8;
    }
    for i in 0..tail {
        let pos = primer + count + i;
        state = step(state, None, None, prog[pos], sbox)?;
        out[count + i] = (state & 7) as u8;
    }
    Ok(out)
}

/// kit `vm67d524` — 16-bit-state reducer over the 67cc18 program; output = count + 3.
pub fn vm67d524(magic: u64, src: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let prog_off = (magic & 0x3f_ffff) as usize;
    let primer = ((magic >> 22) & 0x3fff) as usize;
    let count = ((magic >> 36) & 0x3fff) as usize;
    let total_prog = primer + count + 3;
    let prog = checked_slice(&t.prog67cc18, prog_off, total_prog, "67d524")?;
    require(src, std::cmp::max(primer + count, 5), "vm67d524 src")?;

    let mut state = 0usize;
    for i in 0..primer {
        state = step16_masked(state, src[i], prog[i], &t.ttable_b_ext)?;
    }
    let mut out = vec![0u8; count + 3];
    for i in 0..count {
        state = step16_masked(state, src[primer + i], prog[primer + i], &t.ttable_b_ext)?;
        out[i] = (state & 7) as u8;
    }
    let tail_prog = primer + count;
    for i in 0..3 {
        state = step16_full(state, src[2 + i], prog[tail_prog + i], &t.ttable_b_ext)?;
        out[count + i] = (state & 7) as u8;
    }
    Ok(out)
}

/// kit `vm641fcc` — the 638840-program sibling of `vm67d524` (used by the low-seed loop).
pub fn vm641fcc(magic: u64, src: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let prog_off = (magic & 0x3f_ffff) as usize;
    let primer = ((magic >> 22) & 0x3fff) as usize;
    let count = ((magic >> 36) & 0x3fff) as usize;
    if primer < 2 {
        return Err(slice_err(format!("641fcc primer {primer} < 2")));
    }
    let total_prog = primer + count + 3;
    let prog = checked_slice(&t.prog638840, prog_off, total_prog, "641fcc")?;
    require(src, std::cmp::max(primer + count, 5), "vm641fcc src")?;

    let mut state = 0usize;
    for i in 0..primer {
        state = step16_masked(state, src[i], prog[i], &t.ttable_b_ext)?;
    }
    let mut out = vec![0u8; count + 3];
    for i in 0..count {
        state = step16_masked(state, src[primer + i], prog[primer + i], &t.ttable_b_ext)?;
        out[i] = (state & 7) as u8;
    }
    let tail_prog = primer + count;
    for i in 0..3 {
        state = step16_full(state, src[2 + i], prog[tail_prog + i], &t.ttable_b_ext)?;
        out[count + i] = (state & 7) as u8;
    }
    Ok(out)
}

/// kit `vm64e17c` — fixed-preamble 16-bit reducer over the 64e2b8 program; 130 bytes in, 130 out.
pub fn vm64e17c(src0: &[u8], src1: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    const SCRATCH: usize = 130;
    const PROG_OFFSET: usize = 0x1ce;
    const PROG_LEN: usize = 0x7e;
    require(src0, SCRATCH, "vm64e17c src0")?;
    require(src1, SCRATCH, "vm64e17c src1")?;

    let mut state = step(0, Some(src0[0]), Some(src1[0]), 14, &t.sbox19)?;
    for (index, prog_byte) in [22u8, 9, 33].iter().enumerate() {
        let pos = index + 1;
        state = step(state, Some(src0[pos]), Some(src1[pos]), *prog_byte, &t.sbox19)?;
    }

    let prog = checked_slice(&t.prog64e2b8, PROG_OFFSET, PROG_LEN, "prog64e2b8")?;
    let mut out = vec![0u8; SCRATCH];
    for i in 0..PROG_LEN {
        state = step(state, Some(src0[4 + i]), Some(src1[4 + i]), prog[i], &t.sbox19)?;
        out[i] = (state & 7) as u8;
    }
    for (i, prog_byte) in [12u8, 17, 18, 27].iter().enumerate() {
        let pos = PROG_LEN + i;
        state = step(state, None, None, *prog_byte, &t.sbox19)?;
        out[pos] = (state & 7) as u8;
    }
    Ok(out)
}