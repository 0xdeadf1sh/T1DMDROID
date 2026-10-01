//! Clean-room port of lib+0x5de1e4 (PLAN_T1DMDROID.md §5.5): the Phase 5 wire-cipher key
//! schedule. Input is the 66-byte stage-1 source; output is the raw 16-byte key consumed by
//! [crate::libaes::phase5_block_encrypt]. Tables come from the out-of-band runtime set.

use crate::CryptoError;

const REGION_BASE: usize = 0x274000;
const REGION_LENGTH: usize = 0x2000;
const PROG_DATA_BASE: usize = 0x274624;
const STAGE1_TABLE_OFFSET: usize = 0x124;
pub const STAGE1_COUNT: usize = 66;
const PHASE5_SBOX_OFF: usize = 0x9d3;
const POST_LOOP_BASE: usize = 0x274a13;
const SCRAMBLER_OFFSET_IN_SBOX_MASTER: usize = 0x20001;

const SUB_ORC_VMA1_OFFSET: usize = 465;
const SUB_ORC_VMB_OFFSET: usize = 194;
const SUB_ORC_VMB_LENGTH_A: usize = 2;
const SUB_ORC_VMB_LENGTH_B: usize = 4;
const SUB_ORC_VMB_LENGTH_C: usize = 2;
const SUB_ORC_VMA2_OFFSET: usize = 833;

const PHASE2_SCRATCH_DSTS: [usize; 4] = [0xb4, 0x92, 0xe8, 0xd6];
const PHASE2_SCRATCH_SRCS: [usize; 4] = [0, 0xb4, 0x92, 0xe8];
const SUB_ORC_ARG_SP: usize = 0xd6;
const SUB_ORC_ACCUM_SP: usize = 0x104;

const PHASE2_CHUNK_OFFSETS_SP: [usize; 16] = [
    0x8c, 0x86, 0x80, 0x7a, 0x74, 0x6e, 0x68, 0x62, 0x5c, 0x56, 0x50, 0x4a, 0x44, 0x3e, 0x38, 0x32,
];

const PHASE2_PROG_OFFSETS: [[usize; 4]; 16] = [
    [611, 364, 805, 382],
    [581, 208, 569, 160],
    [124, 136, 765, 737],
    [106, 112, 370, 18],
    [731, 523, 425, 843],
    [553, 895, 675, 715],
    [487, 24, 398, 879],
    [12, 743, 821, 30],
    [669, 657, 873, 118],
    [547, 517, 587, 188],
    [166, 0, 182, 286],
    [837, 36, 651, 867],
    [404, 202, 663, 376],
    [605, 130, 575, 681],
    [6, 599, 749, 499],
    [645, 493, 358, 541],
];

const PHASE2_SRC2_LIB_OFFSETS: [[usize; 4]; 16] = [
    [0x2749bb, 0x2749c1, 0x2749c7, 0x2749cd],
    [0x275a53, 0x275a59, 0x275a5f, 0x275a65],
    [0x275a6b, 0x275a71, 0x275a77, 0x275a7d],
    [0x275a83, 0x275a89, 0x275a8f, 0x275a95],
    [0x275a9b, 0x275aa1, 0x275aa7, 0x275aad],
    [0x275ab3, 0x275ab9, 0x275abf, 0x275ac5],
    [0x275acb, 0x275ad1, 0x275ad7, 0x275add],
    [0x275ae3, 0x275ae9, 0x275aef, 0x275af5],
    [0x275afb, 0x275b01, 0x275b07, 0x275b0d],
    [0x275b13, 0x275b19, 0x275b1f, 0x275b25],
    [0x275b2b, 0x275b31, 0x275b37, 0x275b3d],
    [0x275b43, 0x275b49, 0x275b4f, 0x275b55],
    [0x275b5b, 0x275b61, 0x275b67, 0x275b6d],
    [0x275b73, 0x275b79, 0x275b7f, 0x275b85],
    [0x275b8b, 0x275b91, 0x275b97, 0x275b9d],
    [0x275ba3, 0x275ba9, 0x275baf, 0x275bb5],
];

const KEY_POS_BY_ITER: [usize; 16] = [
    3, 2, 1, 0, 7, 6, 5, 4, 11, 10, 9, 8, 15, 14, 13, 12,
];

#[derive(Clone, Copy, PartialEq)]
enum VmOp {
    VmA,
    VmB,
    VmD,
}

#[derive(Clone, Copy)]
enum Source {
    Input,
    Stack(usize),
}

struct Phase1Call {
    op: VmOp,
    prog_offset: usize,
    count_a: usize,
    count_b: usize,
    src1: Source,
    src2: Source,
    dst: usize,
}

const fn call(
    op: VmOp,
    prog_offset: usize,
    count_a: usize,
    count_b: usize,
    src1: Source,
    src2: Source,
    dst: usize,
) -> Phase1Call {
    Phase1Call {
        op,
        prog_offset,
        count_a,
        count_b,
        src1,
        src2,
        dst,
    }
}

const fn stack(off: usize) -> Source {
    Source::Stack(off)
}

const INPUT: Source = Source::Input;

const PHASE1_CALLS: [Phase1Call; 30] = [
    call(VmOp::VmA, 0x1af, 0, 34, INPUT, INPUT, 0xb4),
    call(VmOp::VmB, 0x0d6, 32, 34, INPUT, INPUT, 0x92),
    call(VmOp::VmA, 0x269, 0, 18, stack(0xb4), stack(0xb4), 0xe8),
    call(VmOp::VmB, 0x303, 16, 18, stack(0xb4), stack(0xb4), 0xd6),
    call(VmOp::VmA, 0x02a, 0, 10, stack(0xe8), stack(0xe8), 0x104),
    call(VmOp::VmB, 0x1d5, 8, 10, stack(0xe8), stack(0xe8), 0xfa),
    call(VmOp::VmD, 0x211, 0, 6, stack(0x104), stack(0x104), 0x32),
    call(VmOp::VmB, 0x32b, 4, 6, stack(0x104), stack(0x104), 0x38),
    call(VmOp::VmD, 0x1f9, 0, 6, stack(0xfa), stack(0xfa), 0x3e),
    call(VmOp::VmB, 0x2d1, 4, 6, stack(0xfa), stack(0xfa), 0x44),
    call(VmOp::VmA, 0x0ac, 0, 10, stack(0xd6), stack(0xd6), 0x104),
    call(VmOp::VmB, 0x2b9, 8, 10, stack(0xd6), stack(0xd6), 0xfa),
    call(VmOp::VmD, 0x1a3, 0, 6, stack(0x104), stack(0x104), 0x4a),
    call(VmOp::VmB, 0x034, 4, 6, stack(0x104), stack(0x104), 0x50),
    call(VmOp::VmD, 0x217, 0, 6, stack(0xfa), stack(0xfa), 0x56),
    call(VmOp::VmB, 0x27b, 4, 6, stack(0xfa), stack(0xfa), 0x5c),
    call(VmOp::VmA, 0x08e, 0, 18, stack(0x92), stack(0x92), 0xe8),
    call(VmOp::VmB, 0x03e, 16, 18, stack(0x92), stack(0x92), 0xd6),
    call(VmOp::VmA, 0x22f, 0, 10, stack(0xe8), stack(0xe8), 0x104),
    call(VmOp::VmB, 0x351, 8, 10, stack(0xe8), stack(0xe8), 0xfa),
    call(VmOp::VmD, 0x1ff, 0, 6, stack(0x104), stack(0x104), 0x62),
    call(VmOp::VmB, 0x375, 4, 6, stack(0x104), stack(0x104), 0x68),
    call(VmOp::VmD, 0x33b, 0, 6, stack(0xfa), stack(0xfa), 0x6e),
    call(VmOp::VmB, 0x2af, 4, 6, stack(0xfa), stack(0xfa), 0x74),
    call(VmOp::VmA, 0x184, 0, 10, stack(0xd6), stack(0xd6), 0x104),
    call(VmOp::VmB, 0x385, 8, 10, stack(0xd6), stack(0xd6), 0xfa),
    call(VmOp::VmD, 0x118, 0, 6, stack(0x104), stack(0x104), 0x7a),
    call(VmOp::VmB, 0x2f3, 4, 6, stack(0x104), stack(0x104), 0x80),
    call(VmOp::VmD, 0x251, 0, 6, stack(0xfa), stack(0xfa), 0x86),
    call(VmOp::VmB, 0x060, 4, 6, stack(0xfa), stack(0xfa), 0x8c),
];

/// The schedule's two tables: the master 19-bit sbox and the 0x2000 region at 0x274000.
pub struct ScheduleTables {
    sbox_master: Vec<u8>,
    region274000: Vec<u8>,
}

impl ScheduleTables {
    /// Loads `sbox_19bit_lib_986819.bin` (0x80000) and the key-schedule region — carved from
    /// the vendor lib at 0x274000, delivered as `phase5_keysched_region_274000.bin` (0x2000).
    pub fn from_dir(dir: &std::path::Path) -> Result<ScheduleTables, CryptoError> {
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
        Ok(ScheduleTables {
            sbox_master: read("sbox_19bit_lib_986819", 0x80000)?,
            region274000: read("phase5_keysched_region_274000", REGION_LENGTH)?,
        })
    }
}

/// 66-byte stage-1 source → 16-byte raw Phase 5 key.
pub fn derive_raw_key(
    input66: &[u8],
    tables: &ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    if input66.len() != STAGE1_COUNT {
        return Err(CryptoError::LibAes {
            reason: format!("stage-1 input must be {STAGE1_COUNT} bytes, got {}", input66.len()),
        });
    }
    let sbox = &tables.sbox_master;
    let region = &tables.region274000;
    let prog = &region[PROG_DATA_BASE - REGION_BASE..];

    let mut in_buf = vec![0u8; 0x90];
    let stage1_prog = &prog[STAGE1_TABLE_OFFSET..STAGE1_TABLE_OFFSET + STAGE1_COUNT];
    let stage1 = vm_a(sbox, input66, input66, stage1_prog, STAGE1_COUNT);
    in_buf[..stage1.len()].copy_from_slice(&stage1);

    let mut stack = vec![0u8; 0x180];
    for c in &PHASE1_CALLS {
        let total_read = match c.op {
            VmOp::VmD => 6,
            VmOp::VmB => c.count_a + c.count_b,
            VmOp::VmA => c.count_b,
        };
        let src1 = source(c.src1, &in_buf, &stack, total_read);
        let src2 = source(c.src2, &in_buf, &stack, total_read);
        let prog_seg = &prog[c.prog_offset..c.prog_offset + total_read];
        let out = match c.op {
            VmOp::VmA => vm_a(sbox, &src1, &src2, prog_seg, c.count_b),
            VmOp::VmB => vm_b(sbox, &src1, &src2, prog_seg, c.count_a, c.count_b, 0),
            VmOp::VmD => vm_d(sbox, &src1, &src2, prog_seg),
        };
        stack[c.dst..c.dst + out.len()].copy_from_slice(&out);
    }

    let mut sub_orc_bytes = [0u8; 16];
    for iter in 0..16 {
        let chunk_in_off = PHASE2_CHUNK_OFFSETS_SP[iter];
        for round in 0..4 {
            let src1_off = if round == 0 {
                chunk_in_off
            } else {
                PHASE2_SCRATCH_SRCS[round]
            };
            let src1 = stack[src1_off..src1_off + 6].to_vec();
            let lib_off = PHASE2_SRC2_LIB_OFFSETS[iter][round];
            let src2 = region_slice(region, lib_off, 6)?;
            let prog_seg = &prog[PHASE2_PROG_OFFSETS[iter][round]..PHASE2_PROG_OFFSETS[iter][round] + 6];
            let out = vm_d(sbox, &src1, &src2, prog_seg);
            stack[PHASE2_SCRATCH_DSTS[round]..PHASE2_SCRATCH_DSTS[round] + 6].copy_from_slice(&out);
        }

        let compressed = sub_orc(
            &stack[SUB_ORC_ARG_SP..SUB_ORC_ARG_SP + 6],
            sbox,
            prog,
            region,
        )?;
        sub_orc_bytes[iter] = compressed;
        stack[SUB_ORC_ACCUM_SP] = compressed;
    }

    let mut aes_key = [0u8; 16];
    for iter in 0..16 {
        let table_offset = if iter == 0 { 0 } else { iter * 0x100 + 0x40 };
        let region_off = POST_LOOP_BASE - REGION_BASE + table_offset + sub_orc_bytes[iter] as usize;
        aes_key[KEY_POS_BY_ITER[iter]] = *region
            .get(region_off)
            .ok_or(CryptoError::InvalidP256Point)?;
    }
    Ok(aes_key)
}

// MARK: - VM primitives

fn vm_a(sbox: &[u8], src1: &[u8], src2: &[u8], prog: &[u8], length: usize) -> Vec<u8> {
    let mut state = 0usize;
    let mut out = vec![0u8; length];
    for i in 0..length {
        let next = vm_op_no_write(state, src1[i], src2[i], prog[i], sbox);
        state = next;
        out[i] = (next & 7) as u8;
    }
    out
}

fn vm_b(
    sbox: &[u8],
    src1: &[u8],
    src2: &[u8],
    prog: &[u8],
    length_a: usize,
    length_b: usize,
    length_c: usize,
) -> Vec<u8> {
    let mut state = 0usize;
    for i in 0..length_a {
        state = vm_op_no_write(state, src1[i], src2[i], prog[i], sbox);
    }
    let mut out = vec![0u8; length_b + length_c];
    for i in 0..length_b {
        let idx = length_a + i;
        let next = vm_op_no_write(state, src1[idx], src2[idx], prog[idx], sbox);
        state = next;
        out[i] = (next & 7) as u8;
    }
    for i in 0..length_c {
        let idx = length_a + length_b + i;
        let sbox_idx = ((state & 0xf8) | ((prog[idx] as usize) << 11)) & 0x7_ffff;
        state = sbox[sbox_idx] as usize;
        out[length_b + i] = (state & 7) as u8;
    }
    out
}

fn vm_d(sbox: &[u8], src1: &[u8], src2: &[u8], prog: &[u8]) -> Vec<u8> {
    vm_a(sbox, src1, src2, prog, 6)
}

#[inline]
fn vm_op_no_write(state: usize, src1: u8, src2: u8, prog: u8, sbox: &[u8]) -> usize {
    let idx = ((((state & 0xf8) ^ src1 as usize) | ((src2 as usize) << 8) ^ ((prog as usize) << 11)))
        & 0x7_ffff;
    sbox[idx] as usize
}

fn sub_orc(
    arg6b: &[u8],
    sbox_master: &[u8],
    prog_data: &[u8],
    region: &[u8],
) -> Result<u8, CryptoError> {
    let scratch = transform6b(arg6b, sbox_master)?;
    let vma1_prog = &prog_data[SUB_ORC_VMA1_OFFSET..SUB_ORC_VMA1_OFFSET + 4];
    let vma2_prog = &prog_data[SUB_ORC_VMA2_OFFSET..SUB_ORC_VMA2_OFFSET + 4];

    let keep = vm_a(
        sbox_master,
        &scratch[0..4],
        &scratch[0..4],
        vma1_prog,
        4,
    );

    let mut scratch_mut = scratch;
    let vmb_prog = &prog_data
        [SUB_ORC_VMB_OFFSET..SUB_ORC_VMB_OFFSET + SUB_ORC_VMB_LENGTH_A + SUB_ORC_VMB_LENGTH_B + SUB_ORC_VMB_LENGTH_C];
    let vmb_out = vm_b(
        sbox_master,
        &scratch_mut,
        &scratch_mut,
        vmb_prog,
        SUB_ORC_VMB_LENGTH_A,
        SUB_ORC_VMB_LENGTH_B,
        SUB_ORC_VMB_LENGTH_C,
    );
    scratch_mut[..vmb_out.len()].copy_from_slice(&vmb_out);

    let step5 = vm_a(sbox_master, &keep, &keep, vma2_prog, 4);
    let idx5 = (step5[2] as usize) ^ ((step5[3] as usize) << 3);
    let lookup5 = *region
        .get(PHASE5_SBOX_OFF + idx5)
        .ok_or(CryptoError::InvalidP256Point)?;

    let post_vmb = scratch_mut[0..4].to_vec();
    let keep2 = vm_a(sbox_master, &post_vmb, &post_vmb, vma1_prog, 4);
    let step7 = vm_a(sbox_master, &keep2, &keep2, vma2_prog, 4);
    let idx7 = (step7[2] as usize) ^ ((step7[3] as usize) << 3);
    let lookup7 = *region
        .get(PHASE5_SBOX_OFF + idx7)
        .ok_or(CryptoError::InvalidP256Point)?;

    Ok((lookup7 & 0xf0) | (lookup5 & 0x0f))
}

fn transform6b(arg6b: &[u8], sbox_master: &[u8]) -> Result<[u8; 6], CryptoError> {
    let read_half = |index: usize| -> Result<usize, CryptoError> {
        let byte_offset = SCRAMBLER_OFFSET_IN_SBOX_MASTER + index * 2;
        let lo = *sbox_master
            .get(byte_offset)
            .ok_or(CryptoError::InvalidP256Point)?;
        let hi = *sbox_master
            .get(byte_offset + 1)
            .ok_or(CryptoError::InvalidP256Point)?;
        Ok((lo as usize) | ((hi as usize) << 8))
    };
    let mut out = [0u8; 6];
    let mut x = read_half((arg6b[0] as usize) + 0x2000)?;
    x = read_half(((x & 0xff8) ^ arg6b[1] as usize) | 0x2000)?;
    x = read_half(((x & 0xff8) ^ arg6b[2] as usize) | 0x2000)?;
    x = read_half(((x & 0xff8) ^ arg6b[3] as usize) | 0x4000)?;
    out[0] = (x & 7) as u8;

    x = read_half((((x & 0xff8) ^ arg6b[4] as usize) | 0x21000) + 0xd000)?;
    out[1] = (x & 7) as u8;
    x = read_half(((x & 0xff8) ^ arg6b[5] as usize) | 0x21000)?;
    out[2] = (x & 7) as u8;

    x = read_half((x ^ arg6b[2] as usize) ^ 0x6000)?;
    out[3] = (x & 7) as u8;
    x = read_half((x ^ arg6b[3] as usize) ^ 0x2000)?;
    out[4] = (x & 7) as u8;
    x = read_half((x ^ arg6b[4] as usize) ^ 0x4000)?;
    out[5] = (x & 7) as u8;
    Ok(out)
}

fn source(s: Source, in_buf: &[u8], stack: &[u8], count: usize) -> Vec<u8> {
    match s {
        Source::Input => in_buf[..count].to_vec(),
        Source::Stack(off) => stack[off..off + count].to_vec(),
    }
}

fn region_slice(region: &[u8], lib_offset: usize, count: usize) -> Result<Vec<u8>, CryptoError> {
    let offset = lib_offset - REGION_BASE;
    if offset + count > region.len() {
        return Err(CryptoError::InvalidP256Point);
    }
    Ok(region[offset..offset + count].to_vec())
}