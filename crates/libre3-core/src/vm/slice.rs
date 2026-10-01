//! FirstPairSourceSlice — clean-room port of the first-pair Phase 5 key-source slicer
//! (kit: FirstPairSourceSlice.swift). This file carries the 679f48 context branch: context
//! init (67cc18-seeded), the 67aa8c/67eb94/67d630/67dd7c streaming updates, the df80
//! compress, the finalizer, and the 67a960/64de54 derivation chain that yields the
//! 66-byte Phase 5 source. Golden vectors: tests/firstpair_vm.rs (ported from
//! FirstPairSourceSliceTests.swift).

use super::firstpair::*;
use super::tables::FirstPairTables;
use crate::phase5;
use crate::CryptoError;

pub(crate) const BLOCK66: usize = 0x42;
pub(crate) const SCRATCH130: usize = 130;
const DF80_INPUT_BLOCK_COUNT: usize = 4;
pub(crate) const DF80_WORD: usize = 0x12;
const DF80_DERIVED_SCHEDULE: usize = 0x360;
const DF80_INITIAL_STRIDE: usize = 0x48;
const DF80_INITIAL_WORKSPACE: usize = 0x120;
const DF80_SCHEDULE: usize = 0x480;
const DF80_STATE: usize = 8 * DF80_WORD;
const CONTEXT679F48: usize = 0x20c;

const FINALIZER_DD7C_PAD_OFFSET: usize = 0x0000;
const FINALIZER_ZERO_LOW_BLOCK_OFFSET: usize = 0x0e70;
const FINALIZER_PAD2_OFFSET: usize = 0x0eb2;
const FINALIZER_STATIC_BLOCK_OFFSET: usize = 0x1290;
const EXPAND67ED24_A_OFFSET: usize = 0x0870;
const EXPAND67ED24_B_OFFSET: usize = 0x0b70;
const INIT679F48_BLOCK66_SRC_OFFSET: usize = 0;
const RAW67D630_TABLE_A_OFFSET: usize = 0x0d2;
const RAW67D630_TABLE_B_OFFSET: usize = 0x3d2;
const INIT679F48_BLOCK66_DST_OFFSETS: [usize; 4] = [0x08, 0x4a, 0x8c, 0xce];

const INIT679F48_BLOCK18_SPECS: [(u64, usize, usize); 8] = [
    (0x120000058e3, 0x42, 0x114),
    (0x12000004b6b, 0x54, 0x126),
    (0x1200000388e, 0x66, 0x138),
    (0x12000000662, 0x78, 0x14a),
    (0x12000000c90, 0x8a, 0x15c),
    (0x120000045f6, 0x9c, 0x16e),
    (0x12000000139, 0xae, 0x180),
    (0x12000002cb1, 0xc0, 0x192),
];

const AA8C_INITIAL_REDUCER_SPECS: [(u64, usize, usize); 8] = [
    (0x400120000030e8, 0x114, 0x1ec),
    (0x40012000000006, 0x126, 0x1f0),
    (0x400120000022d5, 0x138, 0x1f4),
    (0x40012000001859, 0x14a, 0x1f8),
    (0x40012000005e7a, 0x15c, 0x1fc),
    (0x40012000004661, 0x16e, 0x200),
    (0x4001200000178f, 0x180, 0x204),
    (0x40012000002c8f, 0x192, 0x208),
];

const EB94_UPDATE_MAGICS: [u64; 8] = [
    0x12000004154,
    0x1200000392c,
    0x120000036db,
    0x12000000f83,
    0x120000000f9,
    0x12000000a72,
    0x120000018d7,
    0x1200000191d,
];

// ---------------------------------------------------------------- 679f48 context

/// kit `init679f48Context`: zero context, 18-byte seed blocks, one shared 66-byte block.
pub fn init679f48_context(t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let mut context = vec![0u8; CONTEXT679F48];
    for &(magic, src_off, dst_off) in &INIT679F48_BLOCK18_SPECS {
        let src = checked_slice(
            &t.seed_tables_679f48,
            src_off,
            DF80_WORD,
            "679f48 seed tables",
        )?;
        replace_at(&mut context, dst_off, &vm67cc18(magic, src, src, t)?);
    }
    let src66 = checked_slice(
        &t.seed_tables_679f48,
        INIT679F48_BLOCK66_SRC_OFFSET,
        BLOCK66,
        "679f48 seed tables",
    )?;
    let block66 = vm67cc18(0x42000001e72, src66, src66, t)?;
    for &dst in &INIT679F48_BLOCK66_DST_OFFSETS {
        replace_at(&mut context, dst, &block66);
    }
    Ok(context)
}

/// kit `seed67aa8cInitialWords`.
fn seed67aa8c_initial_words(context: &mut [u8], t: &FirstPairTables) -> Result<(), CryptoError> {
    for &(magic, src_off, dst_off) in &AA8C_INITIAL_REDUCER_SPECS {
        let window = context[src_off..src_off + DF80_WORD].to_vec();
        let reduced = reducer67ea28_word(&vm67cecc(magic, &window, &window, t)?, t)?;
        replace_at(context, dst_off, &reduced);
    }
    Ok(())
}

/// kit `reducer67ea28Word`.
fn reducer67ea28_word(src: &[u8], t: &FirstPairTables) -> Result<[u8; 4], CryptoError> {
    let tmp18 = vm67cc18(0x120000048e2, src, src, t)?;
    let mut state18 = vm67d524(0xc00f000c0578e, &tmp18, t)?;

    let mut packed: u32 = 0;
    let mut out_shift: u32 = 0;
    let mut bit_budget: u32 = 0x20;
    for round_index in 0..8usize {
        let scratch4 = vm67cc18(0x40000033d7, &state18, &state18, t)?;
        if bit_budget >= 5 {
            state18 = vm67cecc(0x8010000805f94, &state18, &state18, t)?;
        }
        let tmp4 = vm67cc18(0x4000004513, &scratch4, &scratch4, t)?;
        let table_index = tmp4[2] as usize ^ ((tmp4[3] as usize) << 3);
        let table_byte = *t
            .reducer67ea28_nibble
            .get(table_index)
            .ok_or_else(|| slice_err(format!("reducer67ea28 nibble index {table_index} out of bounds")))?;
        let nibble: u32 = if round_index & 1 == 0 {
            (table_byte & 0x0f) as u32
        } else {
            (table_byte >> 4) as u32
        };

        let mask: u32 = if bit_budget >= 4 {
            bit_budget -= 4;
            u32::MAX
        } else {
            let m = if bit_budget == 0 { 0 } else { (1u32 << bit_budget) - 1 };
            bit_budget = 0;
            m
        };
        packed |= (nibble & mask) << out_shift;
        out_shift += 4;
    }
    Ok(packed.to_le_bytes())
}

/// kit `update67aa8cLen4Initial`.
pub fn update67aa8c_len4_initial(
    context: &[u8],
    src4: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if src4.len() != 4 {
        return Err(slice_err(format!("67aa8c initial source must be 4 bytes, got {}", src4.len())));
    }
    if context.len() < CONTEXT679F48 {
        return Err(slice_err(format!("67aa8c context too short: {}", context.len())));
    }
    let mut ctx = context.to_vec();
    if ctx[0x1a4] != 0 {
        return Err(slice_err(format!("67aa8c initial flag must be 0, got {}", ctx[0x1a4])));
    }
    ctx[0x1a4] = 1;
    seed67aa8c_initial_words(&mut ctx, t)?;
    replace_at(&mut ctx, 0x1a5, src4);
    write_u32_le(4, &mut ctx, 0x1e8);
    Ok(ctx)
}

/// kit `expandWordTrits` (finalizer tables → 24 trits).
fn expand_word_trits(word_le: &[u8], table_offset: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    require(word_le, 4, "67ed24 word")?;
    let mut out = Vec::with_capacity(24);
    for byte in &word_le[..4] {
        let index = table_offset + (*byte as usize) * 3;
        let packed = checked_slice(&t.finalizer_tables, index, 3, "finalizer tables")?;
        for value in packed {
            out.push(value & 7);
            out.push(value >> 3);
        }
    }
    Ok(out)
}

/// kit `fold24To18`.
fn fold24_to18(first_magic: u64, tail_magic: u64, src24: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    require(src24, 24, "67ed24 fold source")?;
    let mut out = vm67cc18(first_magic, src24, src24, t)?;
    let mut tail: Vec<u8> = Vec::with_capacity(18);
    let mut offset = 6usize;
    loop {
        let src = &src24[offset..];
        tail.extend(vm67cc18(tail_magic, src, src, t)?);
        if offset == 18 {
            break;
        }
        offset += 6;
    }
    out.extend(&tail[2..6]);
    out.extend(&tail[8..12]);
    out.extend(&tail[14..18]);
    Ok(out)
}

/// kit `expand67ed24`: 4-byte LE word → 18-byte block via two trit expansions.
fn expand67ed24(word_le: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    require(word_le, 4, "67ed24 word")?;
    let side_a = expand_word_trits(word_le, EXPAND67ED24_A_OFFSET, t)?;
    let side_b = expand_word_trits(word_le, EXPAND67ED24_B_OFFSET, t)?;

    let folded_a = fold24_to18(0x600000133b, 0x6000003479, &side_a, t)?;
    let wide_a = vm67cecc(0x40012000000028, &folded_a, &folded_a, t)?;

    let folded_b = fold24_to18(0x6000004936, 0x6000000000, &side_b, t)?;
    let wide_b = vm67cecc(0x40012000004683, &folded_b, &folded_b, t)?;

    let mixed = vm67cc18(0x22000004d74, &wide_a, &wide_b, t)?;
    let out = vm67d524(0xc01f000c05d34, &mixed, t)?;
    Ok(out)
}

/// kit `update67eb94Blocks`: 8 LE words → 8×18-byte blocks.
fn update67eb94_blocks(words_le: &[[u8; 4]], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if words_le.len() != 8 {
        return Err(slice_err(format!("67eb94 wants 8 words, got {}", words_le.len())));
    }
    let mut blocks = Vec::with_capacity(DF80_STATE);
    for (word, magic) in words_le.iter().zip(EB94_UPDATE_MAGICS) {
        let expanded = expand67ed24(word, t)?;
        blocks.extend(vm67cc18(magic, &expanded, &expanded, t)?);
    }
    Ok(blocks)
}

/// kit `apply67eb94PendingBlocks`.
pub fn apply67eb94_pending_blocks(context: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if context.len() < CONTEXT679F48 {
        return Err(slice_err(format!("67eb94 context too short: {}", context.len())));
    }
    let mut ctx = context.to_vec();
    if ctx[0x1a4] == 0 {
        return Ok(ctx);
    }
    ctx[0x1a4] = 0;
    let mut words: Vec<[u8; 4]> = Vec::with_capacity(8);
    for index in 0..8usize {
        let start = 0x1ec + index * 4;
        let mut w = [0u8; 4];
        w.copy_from_slice(&ctx[start..start + 4]);
        words.push(w);
    }
    replace_at(&mut ctx, 0x114, &update67eb94_blocks(&words, t)?);
    Ok(ctx)
}

// ---------------------------------------------------------------- 67d630 encode

/// kit `expandRawByte67d630`.
fn expand_raw_byte_67d630(byte: u8, table_offset: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let index = table_offset + (byte as usize) * 3;
    let packed = checked_slice(&t.seed_tables_679f48, index, 3, "679f48 seed tables")?;
    let mut out = Vec::with_capacity(6);
    for value in packed {
        out.push(value & 7);
        out.push(value >> 3);
    }
    Ok(out)
}

/// kit `fold96To66`.
fn fold96_to66(first_magic: u64, tail_magic: u64, src96: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    require(src96, 0x60, "67d630 fold source")?;
    let mut padded = src96.to_vec();
    padded.resize(0x120, 0);
    let first = vm67cc18(first_magic, &padded[0..0x60], &padded[0..0x60], t)?;
    let mut out = first[..6].to_vec();
    let mut offset = 6usize;
    while offset < 0x60 {
        let chunk = vm67cc18(tail_magic, &padded[offset..offset + 0x60], &padded[offset..offset + 0x60], t)?;
        out.extend(&chunk[2..6]);
        offset += 6;
    }
    Ok(out)
}

/// kit `encode67d630Block`: ≤16 raw bytes → 66-byte encoded block.
pub fn encode67d630_block(src: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if src.is_empty() || src.len() > 0x10 {
        return Err(slice_err(format!("67d630 block length must be 1..=16, got {}", src.len())));
    }
    let mut scratch16 = [0u8; 0x10];
    for index in 0..src.len() {
        scratch16[0x10 - src.len() + index] = src[src.len() - 1 - index];
    }

    let mut side_a: Vec<u8> = Vec::with_capacity(0x60);
    let mut side_b: Vec<u8> = Vec::with_capacity(0x60);
    for byte in &scratch16 {
        side_a.extend(expand_raw_byte_67d630(*byte, RAW67D630_TABLE_A_OFFSET, t)?);
        side_b.extend(expand_raw_byte_67d630(*byte, RAW67D630_TABLE_B_OFFSET, t)?);
    }

    let folded_a = fold96_to66(0x600000032b2, 0x60000005e9c, &side_a, t)?;
    let mixed_a = vm67cc18(0x42000004b29, &folded_a, &folded_a, t)?;
    let folded_b = fold96_to66(0x60000000133, 0x600000033db, &side_b, t)?;
    let mixed_b = vm67cc18(0x42000000263, &folded_b, &folded_b, t)?;
    let mixed = vm67cc18(0x42000000b0e, &mixed_a, &mixed_b, t)?;
    vm67d524(0xc03f000c0112f, &mixed, t)
}

/// kit `apply67eb94WithPendingRawAdapter`.
pub fn apply67eb94_with_pending_raw_adapter(context: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if context.len() < CONTEXT679F48 {
        return Err(slice_err(format!("67eb94 context too short: {}", context.len())));
    }
    let mut ctx = context.to_vec();
    if ctx[0x1a4] == 0 {
        return Ok(ctx);
    }
    let pending_length = read_u32_le(&ctx, 0x1e8) as usize;
    if pending_length > 0x40 {
        return Err(slice_err(format!("67eb94 pending length {pending_length} > 0x40")));
    }
    ctx = apply67eb94_pending_blocks(&ctx, t)?;
    let pending = checked_slice(&ctx, 0x1a5, pending_length, "67eb94 pending bytes")?.to_vec();
    let mut offset = 0usize;
    while offset < pending.len() {
        let end = (offset + 0x10).min(pending.len());
        let chunk = &pending[offset..end];
        let encoded = encode67d630_block(chunk, t)?;
        ctx = apply67dd7c_update_until_df80(&ctx, &encoded, chunk.len(), t)?;
        offset = end;
    }
    write_u32_le(0, &mut ctx, 0x1e8);
    Ok(ctx)
}

// ---------------------------------------------------------------- 67dd7c stream update

/// kit `shift67dd7cRemainder`.
fn shift67dd7c_remainder(block66: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    require(block66, BLOCK66, "67dd7c remainder block")?;
    let mut shifted = vec![0u8, 0, 0, 3];
    shifted.extend(&block66[..0x3e]);
    vm67cc18(0x42000001974, &shifted, &shifted, t)
}

/// kit `apply67dd7cUpdateUntilDF80`.
pub fn apply67dd7c_update_until_df80(
    context: &[u8],
    encoded66: &[u8],
    raw_length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if context.len() < CONTEXT679F48 {
        return Err(slice_err(format!("67dd7c context too short: {}", context.len())));
    }
    if encoded66.len() != BLOCK66 {
        return Err(slice_err(format!("67dd7c encoded block must be {BLOCK66} bytes, got {}", encoded66.len())));
    }
    if raw_length == 0 || raw_length > 0x10 {
        return Err(slice_err(format!("67dd7c raw length must be 1..=16, got {raw_length}")));
    }

    let mut ctx = context.to_vec();
    let context_length = read_u64_le(&ctx, 0);
    let low = (context_length & 0x0f) as usize;
    let room = 0x10 - low;
    let mut block_index = read_u32_le(&ctx, 0x110);
    let slot = 0x08 + (block_index as usize) * BLOCK66;

    if low != 0 {
        let mut staged = vm67cc18(0x42000005c05, encoded66, encoded66, t)?;
        for _ in 0..low {
            staged = vm67cecc(0x1003e001002eaf, &staged, &staged, t)?;
        }
        let pad = checked_slice(
            &t.finalizer_tables,
            FINALIZER_DD7C_PAD_OFFSET + (low ^ 0x0f) * BLOCK66,
            BLOCK66,
            "finalizer tables",
        )?;
        let current = checked_slice(&ctx, slot, BLOCK66, "67dd7c context slot")?;
        let prefix = vm67cc18(0x42000002fd4, current, pad, t)?;
        replace_at(&mut ctx, slot, &vm67cc18(0x42000003060, &prefix, &staged, t)?);
    } else {
        replace_at(&mut ctx, slot, &vm67cc18(0x42000001c66, encoded66, encoded66, t)?);
    }

    if room <= raw_length {
        block_index += 1;
        write_u32_le(block_index, &mut ctx, 0x110);
        if block_index == 4 {
            let transformed = df80_transform(&ctx[0x114..0x1a4], &ctx[0x08..0x110], t)?;
            replace_at(&mut ctx, 0x114, &transformed);
            block_index = 0;
            write_u32_le(0, &mut ctx, 0x110);
        }
        if room < raw_length {
            let mut remainder = vm67cc18(0x42000003d8b, encoded66, encoded66, t)?;
            for _ in 0..room {
                remainder = shift67dd7c_remainder(&remainder, t)?;
            }
            let next_slot = 0x08 + (block_index as usize) * BLOCK66;
            replace_at(&mut ctx, next_slot, &vm67cc18(0x420000008de, &remainder, &remainder, t)?);
        }
    }
    write_u64_le(context_length + raw_length as u64, &mut ctx, 0);
    Ok(ctx)
}

/// kit `previousDescriptorBlocksToDD7CInputs`.
pub fn previous_descriptor_blocks_to_dd7c_inputs(
    previous_blocks: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if previous_blocks.len() % BLOCK66 != 0 {
        return Err(slice_err(format!("dd7c inputs: encoded block length {}", previous_blocks.len())));
    }
    let mut out = Vec::with_capacity(previous_blocks.len());
    for start in (0..previous_blocks.len()).step_by(BLOCK66) {
        let block = &previous_blocks[start..start + BLOCK66];
        let encoded = vm67cc18(0x42000001341, block, block, t)?;
        let staged = vm67cc18(0x420000053ba, &encoded, &encoded, t)?;
        out.extend(vm67cc18(0x42000000c2c, &staged, &staged, t)?);
    }
    Ok(out)
}

// ---------------------------------------------------------------- 67076c constructors

/// kit `constructor67076cBlocks` with the 670978 (ptr28) magic.
pub fn constructor670978_ptr28_blocks(raw_descriptor_blocks: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    constructor67076c_blocks(raw_descriptor_blocks, 0x42000000000, t)
}

/// kit `constructor670a54Ptr10Blocks` with the 670a54 (ptr10) magic.
pub fn constructor670a54_ptr10_blocks(raw_descriptor_blocks: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    constructor67076c_blocks(raw_descriptor_blocks, 0x42000000042, t)
}

fn constructor67076c_blocks(
    raw_descriptor_blocks: &[u8],
    magic: u64,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if raw_descriptor_blocks.len() % BLOCK66 != 0 {
        return Err(slice_err(format!("67076c: encoded block length {}", raw_descriptor_blocks.len())));
    }
    let mut out = Vec::with_capacity(raw_descriptor_blocks.len());
    for start in (0..raw_descriptor_blocks.len()).step_by(BLOCK66) {
        let block = &raw_descriptor_blocks[start..start + BLOCK66];
        out.extend(vm67076c(magic, block, block, t)?);
    }
    Ok(out)
}

// ---------------------------------------------------------------- df80 compress

/// kit `df80InitialWorkspace`: 4×66 encoded blocks → 0x120 workspace.
pub fn df80_initial_workspace(blocks: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if blocks.len() != DF80_INPUT_BLOCK_COUNT * BLOCK66 {
        return Err(slice_err(format!(
            "df80 blocks must be {} bytes, got {}",
            DF80_INPUT_BLOCK_COUNT * BLOCK66,
            blocks.len()
        )));
    }
    let mut workspace = vec![0u8; DF80_INITIAL_WORKSPACE];
    for index in 0..DF80_INPUT_BLOCK_COUNT {
        let start = index * BLOCK66;
        let src = &blocks[start..start + BLOCK66];
        let side_a = vm67cc18(0x22000002444, src, src, t)?;
        let side_b = vm67cecc(0x22008004942, src, src, t)?;
        replace_at(&mut workspace, index * DF80_INITIAL_STRIDE + 0x36, &vm67cc18(0x12000000dea, &side_a, &side_a, t)?);
        replace_at(&mut workspace, index * DF80_INITIAL_STRIDE + 0x24, &vm67cecc(0x12004003dcd, &side_a, &side_a, t)?);
        replace_at(&mut workspace, index * DF80_INITIAL_STRIDE + 0x12, &vm67cc18(0x120000052bb, &side_b, &side_b, t)?);
        replace_at(&mut workspace, index * DF80_INITIAL_STRIDE, &vm67cecc(0x12004003d69, &side_b, &side_b, t)?);
    }
    Ok(workspace)
}

/// kit `df80ExpandedSchedule`: 0x120 workspace → 0x480 schedule.
pub fn df80_expanded_schedule(initial_workspace: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if initial_workspace.len() != DF80_INITIAL_WORKSPACE {
        return Err(slice_err(format!(
            "df80 workspace must be {DF80_INITIAL_WORKSPACE} bytes, got {}",
            initial_workspace.len()
        )));
    }
    let mut schedule = initial_workspace.to_vec();
    schedule.resize(DF80_INITIAL_WORKSPACE + DF80_DERIVED_SCHEDULE, 0);

    for offset in (0..DF80_DERIVED_SCHEDULE).step_by(DF80_WORD) {
        let w0 = schedule[offset..offset + DF80_WORD].to_vec();
        let w1 = schedule[offset + 0x12..offset + 0x24].to_vec();
        let w9 = schedule[offset + 0xa2..offset + 0xb4].to_vec();
        let w14 = schedule[offset + 0xfc..offset + 0x10e].to_vec();

        let tmp22 = vm67cecc(0x2400900240463c, &w14, &w14, t)?;
        let tmp80 = vm67cc18(0x12000005af5, &w14, &w14, t)?;
        let tmp58 = pack_df80_zeros6_marker(0x06, &tmp80)?;
        let tmp34 = vm67cc18(0x12000005ddf, &tmp58, &tmp58, t)?;
        let mut tmpa4 = vm67cc18(0x12000000523, &tmp34, &tmp22, t)?;

        let tmp22 = vm67cecc(0x2800800280004a, &w14, &w14, t)?;
        let tmp80 = vm67cc18(0x12000002717, &w14, &w14, t)?;
        let tmp58 = pack_df80_zeros5_marker6(&tmp80)?;
        let tmp34 = vm67cc18(0x1200000304e, &tmp58, &tmp58, t)?;
        let tmp58 = vm67cc18(0x120000016d7, &tmp34, &tmp22, t)?;

        let tmp80 = vm67cecc(0x1400d001403ea3, &w14, &w14, t)?;
        let tmp22 = vm67cc18(0x120000037a4, &tmpa4, &tmp58, t)?;
        let tmp92 = vm67cc18(0x120000034f0, &tmp80, &tmp22, t)?;
        tmpa4 = vm67cc18(0x1200000050b, &tmp92, &w9, t)?;

        let tmp22 = vm67cecc(0x1000e0010042f8, &w1, &w1, t)?;
        let tmp80 = vm67cc18(0x12000000251, &w1, &w1, t)?;
        let tmp58 = pack_df80_zeros11_marker5(&tmp80)?;
        let tmp34 = vm67cc18(0x12000005e68, &tmp58, &tmp58, t)?;
        let tmp118 = vm67cc18(0x12000004120, &tmp34, &tmp22, t)?;

        let tmp80 = vm67cecc(0x24009002402391, &w1, &w1, t)?;
        let tmp58 = pack_df80_zeros6_marker(0x07, &w1)?;
        let tmp22 = vm67cc18(0x12000000da4, &tmp58, &tmp58, t)?;
        let tmp34 = vm67cc18(0x12000003e91, &tmp22, &tmp80, t)?;

        let tmp80 = vm67cecc(0x08010000802f7e, &w1, &w1, t)?;
        let tmp22 = vm67cc18(0x120000041ea, &tmp118, &tmp34, t)?;
        let tmp58 = vm67cc18(0x12000000846, &tmp80, &tmp22, t)?;
        let tmp80 = vm67cc18(0x120000019ea, &tmp58, &w0, t)?;
        let derived = vm67cc18(0x12000003ac8, &tmpa4, &tmp80, t)?;
        replace_at(&mut schedule, offset + DF80_INITIAL_WORKSPACE, &derived);
    }
    Ok(schedule)
}

/// kit `df80CompressState`: 8×18 state, 0x480 schedule → 8×18 output.
pub fn df80_compress_state(state: &[u8], schedule: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if state.len() != DF80_STATE {
        return Err(slice_err(format!("df80 state must be {DF80_STATE} bytes, got {}", state.len())));
    }
    if schedule.len() != DF80_SCHEDULE {
        return Err(slice_err(format!("df80 schedule must be {DF80_SCHEDULE} bytes, got {}", schedule.len())));
    }
    let original: Vec<&[u8]> = (0..8).map(|i| &state[i * DF80_WORD..(i + 1) * DF80_WORD]).collect();

    let mut s: Vec<Vec<u8>> = Vec::with_capacity(8);
    let s_magics = [
        0x1200000189d, 0x12000001e60, 0x1200000152d, 0x120000029ed,
        0x120000040ec, 0x120000036ed, 0x12000003423, 0x12000004056,
    ];
    for i in 0..8 {
        s.push(vm67cc18(s_magics[i], original[i], original[i], t)?);
    }

    let static_b = checked_slice(&t.df80_round_tables, DF80_SCHEDULE, DF80_WORD, "df80 round tables")?;

    for offset in (0..DF80_SCHEDULE).step_by(DF80_WORD) {
        let word = &schedule[offset..offset + DF80_WORD];
        let round_a = checked_slice(&t.df80_round_tables, offset, DF80_WORD, "df80 round tables")?;

        let tmp80 = vm67cecc(0x0c00f000c01a40, &s[4], &s[4], t)?;
        let tmp58 = pack_df80_zeros12_marker3(&s[4])?;
        let tmp22 = vm67cc18(0x120000032dc, &tmp58, &tmp58, t)?;
        let mix10 = vm67cc18(0x12000001105, &tmp22, &tmp80, t)?;

        let tmp22 = vm67cecc(0x1800c0018050f7, &s[4], &s[4], t)?;
        let tmp80 = vm67cc18(0x12000002cc3, &s[4], &s[4], t)?;
        let tmp58 = pack_df80_zeros8_zero6(&tmp80)?;
        let t64 = vm67cc18(0x120000030c4, &tmp58, &tmp58, t)?;
        let mix14 = vm67cc18(0x12000001251, &t64, &tmp22, t)?;

        let mix15 = vm67cecc(0x34005003403785, &s[4], &s[4], t)?;
        let tmp80 = vm67cc18(0x120000025a0, &s[4], &s[4], t)?;
        let tmp58 = pack_df80_zeros2_marker6(&tmp80)?;
        let mix17 = vm67cc18(0x12000000d92, &tmp58, &tmp58, t)?;
        let mix18 = vm67cc18(0x120000026f3, &mix17, &mix15, t)?;
        let mix19 = vm67cc18(0x12000000e82, &mix10, &mix14, t)?;
        let mut t76 = vm67cc18(0x120000023ee, &mix18, &mix19, t)?;
        t76 = vm67cc18(0x120000043a5, &s[7], &t76, t)?;

        let t64 = vm67cc18(0x12000005386, round_a, word, t)?;
        let t52 = vm67cc18(0x12000004501, &t76, &t64, t)?;

        let tmp58 = vm67cc18(0x12000000ce4, &s[4], &s[5], t)?;
        let tmp80 = vm67cc18(0x12000003aa4, static_b, &s[4], t)?;
        let tmp22 = vm67cc18(0x12000000f71, &tmp80, &s[6], t)?;
        let t40 = vm67cc18(0x120000020bc, &tmp58, &tmp22, t)?;
        let tmp92 = vm67cc18(0x12000000aa6, &t52, &t40, t)?;

        let tmp80 = vm67cecc(0x04011000404984, &s[0], &s[0], t)?;
        let tmp58 = pack_df80_zeros14_marker1(&s[0])?;
        let tmp22 = vm67cc18(0x1200000190b, &tmp58, &tmp58, t)?;
        let t2e = vm67cc18(0x12000003cd1, &tmp22, &tmp80, t)?;

        let tmp22 = vm67cecc(0x1c00b001c048a7, &s[0], &s[0], t)?;
        let tmp80 = vm67cc18(0x12000003683, &s[0], &s[0], t)?;
        let tmp58 = pack_df80_zeros9(&tmp80)?;
        let tmp58 = vm67cc18(0x120000017b1, &tmp58, &tmp58, t)?;
        let t1c = vm67cc18(0x12000000d5e, &tmp58, &tmp22, t)?;

        let tmp80 = vm67cecc(0x2c007002c000ba, &s[0], &s[0], t)?;
        let tmp58 = pack_df80_zeros4_marker3(&s[0])?;
        let tmp22 = vm67cc18(0x120000001fd, &tmp58, &tmp58, t)?;
        let tmp34_first = vm67cc18(0x12000006062, &tmp22, &tmp80, t)?;

        let tmp80 = vm67cc18(0x12000004ffb, &t2e, &t1c, t)?;
        let tmp58 = vm67cc18(0x120000032ca, &tmp34_first, &tmp80, t)?;

        let tmp22 = vm67cc18(0x12000000d08, &s[0], &s[1], t)?;
        let tmp34 = vm67cc18(0x120000019b6, &s[0], &s[2], t)?;
        let t2e_second = vm67cc18(0x12000004352, &s[1], &s[2], t)?;
        let t1c_second = vm67cc18(0x12000000f5f, &tmp22, &tmp34, t)?;
        let tmp80 = vm67cc18(0x12000004010, &t2e_second, &t1c_second, t)?;
        let tmpa4 = vm67cc18(0x120000035d7, &tmp58, &tmp80, t)?;

        let new_s7 = vm67cc18(0x12000003bc0, &s[6], &s[6], t)?;
        let new_s6 = vm67cc18(0x120000042aa, &s[5], &s[5], t)?;
        let new_s5 = vm67cc18(0x120000042bc, &s[4], &s[4], t)?;
        let new_s4 = vm67cc18(0x12000006050, &s[3], &tmp92, t)?;
        let new_s3 = vm67cc18(0x120000047c5, &s[2], &s[2], t)?;
        let new_s2 = vm67cc18(0x12000004e83, &s[1], &s[1], t)?;
        let new_s1 = vm67cc18(0x120000055c9, &s[0], &s[0], t)?;
        let new_s0 = vm67cc18(0x12000002088, &tmp92, &tmpa4, t)?;

        s[0] = new_s0;
        s[1] = new_s1;
        s[2] = new_s2;
        s[3] = new_s3;
        s[4] = new_s4;
        s[5] = new_s5;
        s[6] = new_s6;
        s[7] = new_s7;
    }

    let out_magics = [
        0x12000001eb4, 0x12000005b5b, 0x120000042e6, 0x12000000b94,
        0x1200000383a, 0x12000003581, 0x12000004dea, 0x12000000dd8,
    ];
    let mut out = Vec::with_capacity(DF80_STATE);
    for i in 0..8 {
        out.extend(vm67cc18(out_magics[i], original[i], &s[i], t)?);
    }
    Ok(out)
}

/// kit `df80Transform`.
pub fn df80_transform(state: &[u8], blocks: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let workspace = df80_initial_workspace(blocks, t)?;
    let schedule = df80_expanded_schedule(&workspace, t)?;
    df80_compress_state(state, &schedule, t)
}

/// kit `packDF80Zeros6Marker`.
fn pack_df80_zeros6_marker(marker: u8, src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 11, "67df80 pack")?;
    let mut out = vec![0u8; 6];
    out.push(marker);
    out.extend(&src[..11]);
    Ok(out)
}

/// kit `packDF80Zeros5Marker6`.
fn pack_df80_zeros5_marker6(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 12, "67df80 pack")?;
    let mut out = vec![0u8; 5];
    out.push(6);
    out.extend(&src[..12]);
    Ok(out)
}

/// kit `packDF80Zeros11Marker5`.
fn pack_df80_zeros11_marker5(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 6, "67df80 pack")?;
    let mut out = vec![0u8; 11];
    out.push(5);
    out.extend(&src[..6]);
    Ok(out)
}

/// kit `packDF80Zeros12Marker3`.
fn pack_df80_zeros12_marker3(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 5, "67df80 pack")?;
    let mut out = vec![0u8; 12];
    out.push(3);
    out.extend(&src[..5]);
    Ok(out)
}

/// kit `packDF80Zeros8Zero6`.
fn pack_df80_zeros8_zero6(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 8, "67df80 pack")?;
    let mut out = vec![0u8; 9];
    out.push(6);
    out.extend(&src[..8]);
    Ok(out)
}

/// kit `packDF80Zeros2Marker6`.
fn pack_df80_zeros2_marker6(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 15, "67df80 pack")?;
    let mut out = vec![0u8, 0, 6];
    out.extend(&src[..15]);
    Ok(out)
}

/// kit `packDF80Zeros14Marker1`.
fn pack_df80_zeros14_marker1(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 3, "67df80 pack")?;
    let mut out = vec![0u8; 14];
    out.push(1);
    out.extend(&src[..3]);
    Ok(out)
}

/// kit `packDF80Zeros9`.
fn pack_df80_zeros9(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 9, "67df80 pack")?;
    let mut out = vec![0u8; 9];
    out.extend(&src[..9]);
    Ok(out)
}

/// kit `packDF80Zeros4Marker3`.
fn pack_df80_zeros4_marker3(src: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(src, 13, "67df80 pack")?;
    let mut out = vec![0u8; 4];
    out.push(3);
    out.extend(&src[..13]);
    Ok(out)
}

// ---------------------------------------------------------------- finalizer + 64de54 derive

/// kit `expandU64Trits` (final_len tables → 48 trits).
fn expand_u64_trits(value: u64, table_offset: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let mut out = Vec::with_capacity(48);
    for shift in (0..64).step_by(8) {
        let index = table_offset + (((value >> shift) & 0xff) as usize) * 3;
        let packed = checked_slice(&t.final_len_tables, index, 3, "final len tables")?;
        for byte in packed {
            out.push(byte & 7);
            out.push(byte >> 3);
        }
    }
    Ok(out)
}

/// kit `fold48To34`.
fn fold48_to34(first_magic: u64, tail_magic: u64, src48: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    require(src48, 0x30, "679f48 length fold source")?;
    let mut out = vm67cc18(first_magic, src48, src48, t)?;
    let mut offset = 6usize;
    while offset < 0x30 {
        let src = &src48[offset..];
        let chunk = vm67cc18(tail_magic, src, src, t)?;
        out.extend(&chunk[2..6]);
        offset += 6;
    }
    Ok(out)
}

/// kit `final679f48LengthBlock`: bit-length block for the final df80 round.
pub fn final679f48_length_block(context_length: u64, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let bit_length = context_length.wrapping_shl(3);
    let side_a = expand_u64_trits(bit_length, 0, t)?;
    let side_b = expand_u64_trits(bit_length, 0x300, t)?;

    let folded_a = fold48_to34(0x600000051d, 0x6000002556, &side_a, t)?;
    let lane_a = vm67cecc(0x800220000010a1, &folded_a, &folded_a, t)?;

    let folded_b = fold48_to34(0x60000018af, 0x6000005ee6, &side_b, t)?;
    let lane_b = vm67cecc(0x80022000004224, &folded_b, &folded_b, t)?;

    let mixed = vm67cc18(0x420000007c0, &lane_a, &lane_b, t)?;
    vm67d524(0xc03f000c0192f, &mixed, t)
}

/// kit `finalize679f48ToSecondDF80`.
pub fn finalize679f48_to_second_df80(context: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if context.len() < CONTEXT679F48 {
        return Err(slice_err(format!("679f48 context too short: {}", context.len())));
    }
    let mut ctx = context.to_vec();
    let context_length = read_u64_le(&ctx, 0);
    let low = (context_length & 0x0f) as usize;
    let mut block_index = read_u32_le(&ctx, 0x110);
    if block_index > 4 {
        return Err(slice_err(format!("679f48 block index {block_index} > 4")));
    }

    let slot = 0x08 + (block_index as usize) * BLOCK66;
    if low != 0 {
        let pad_index = low ^ 0x0f;
        let pad1 = checked_slice(&t.finalizer_tables, FINALIZER_DD7C_PAD_OFFSET + pad_index * BLOCK66, BLOCK66, "finalizer tables")?;
        let pad2 = checked_slice(&t.finalizer_tables, FINALIZER_PAD2_OFFSET + pad_index * BLOCK66, BLOCK66, "finalizer tables")?;
        let current = checked_slice(&ctx, slot, BLOCK66, "679f48 finalizer context")?;
        let mixed = vm67cc18(0x42000005702, current, pad1, t)?;
        replace_at(&mut ctx, slot, &vm67cc18(0x42000005c47, &mixed, &pad2, t)?);
    } else {
        let static_block = checked_slice(&t.finalizer_tables, FINALIZER_ZERO_LOW_BLOCK_OFFSET, BLOCK66, "finalizer tables")?;
        replace_at(&mut ctx, slot, &vm67cc18(0x42000000ffb, static_block, static_block, t)?);
    }

    if low > 7 || block_index <= 2 {
        block_index += 1;
        write_u32_le(block_index, &mut ctx, 0x110);
        if block_index == 4 {
            let transformed = df80_transform(&ctx[0x114..0x1a4], &ctx[0x08..0x110], t)?;
            replace_at(&mut ctx, 0x114, &transformed);
            block_index = 0;
            write_u32_le(0, &mut ctx, 0x110);
        }

        if block_index <= 3 {
            let static_block = checked_slice(&t.finalizer_tables, FINALIZER_STATIC_BLOCK_OFFSET, BLOCK66, "finalizer tables")?;
            while block_index < 4 {
                let fill_slot = 0x08 + (block_index as usize) * BLOCK66;
                replace_at(&mut ctx, fill_slot, &vm67cc18(0x42000005d9d, static_block, static_block, t)?);
                block_index += 1;
                write_u32_le(block_index, &mut ctx, 0x110);
            }
        }
    }

    write_u64_le(context_length.wrapping_shl(3), &mut ctx, 0);
    let final_length = final679f48_length_block(context_length, t)?;
    let final_mixed = vm67cc18(0x420000040aa, &final_length, &ctx[0xce..0x110], t)?;
    replace_at(&mut ctx, 0xce, &final_mixed);
    let transformed = df80_transform(&ctx[0x114..0x1a4], &ctx[0x08..0x110], t)?;
    replace_at(&mut ctx, 0x114, &transformed);
    Ok(ctx)
}

/// kit `finalized679f48ContextFromInputs`.
pub fn finalized679f48_context_from_inputs(
    previous_blocks: &[u8],
    src4: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let mut context = init679f48_context(t)?;
    context = update67aa8c_len4_initial(&context, src4, t)?;
    context = apply67eb94_with_pending_raw_adapter(&context, t)?;

    let full_updates = previous_descriptor_blocks_to_dd7c_inputs(previous_blocks, t)?;
    for start in (0..full_updates.len()).step_by(BLOCK66) {
        context = apply67dd7c_update_until_df80(&context, &full_updates[start..start + BLOCK66], 0x10, t)?;
    }
    context = apply67eb94_with_pending_raw_adapter(&context, t)?;
    finalize679f48_to_second_df80(&context, t)
}

// ------------------------------------------------- 67a960 → 67a978 → 67a990 → 64de54 chain

/// kit `postDF80_67a960Inputs`.
fn post_df80_67a960_inputs(context: &[u8], t: &FirstPairTables) -> Result<(Vec<u8>, Vec<u8>), CryptoError> {
    require(context, CONTEXT679F48, "679f48 context")?;
    let state = &context[0x114..0x1a4];

    let mut buf3b0 = vec![0u8; SCRATCH130];
    let mut buf320 = vec![0u8; SCRATCH130];
    let mut buf3e = vec![0u8; SCRATCH130];
    let mut bufd = vec![0u8; SCRATCH130];

    buf3b0[15] = 6;
    replace_at(&mut buf3b0, 16, &state[0x5a..0x6c]);
    replace_at(
        &mut buf3e,
        0,
        &vm67cc18(0x40012000002ba3, &context[0x180..0x180 + 34], &buf3b0[..34], t)?,
    );

    buf320[15] = 6;
    replace_at(&mut buf320, 16, &state[0x36..0x48]);
    replace_at(
        &mut buf3b0,
        0x20,
        &vm67cc18(0x4001200000255c, &context[0x15c..0x15c + 34], &buf320[..34], t)?,
    );

    buf3b0[..31].fill(0);
    buf3b0[31] = 7;
    replace_at(&mut bufd, 0, &vm67cc18(0x80022000000ca2, &buf3e[..66], &buf3b0[..66], t)?);

    buf3b0[..16].fill(0);
    replace_at(&mut buf3b0, 16, &state[0x12..0x24]);
    replace_at(
        &mut buf3e,
        0,
        &vm67cc18(0x40012000005f0e, &context[0x138..0x138 + 34], &buf3b0[..34], t)?,
    );

    buf3b0[..31].fill(0);
    buf3b0[31] = 7;
    replace_at(&mut buf3b0, 32, &state[..0x12]);
    replace_at(&mut buf320, 0x40, &vm67cc18(0x400220000013d9, &buf3e[..50], &buf3b0[..50], t)?);

    buf320[..0x40].fill(0);
    replace_at(&mut buf3b0, 0x10, &vm67cc18(0xc00420000016e9, &bufd[..114], &buf320[..114], t)?);
    buf3b0[..15].fill(0);
    buf3b0[15] = 1;

    let mut src1 = context[0x192..0x1a4].to_vec();
    src1.resize(SCRATCH130, 0); // Swift: + 112 zero bytes (kit postDF80_67a960Inputs).
    Ok((src1, buf3b0))
}

/// kit `deriveFrom67a960Inputs`.
pub fn derive_from_67a960_inputs(src1: &[u8], src2: &[u8], offset: usize, length: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if src1.len() != SCRATCH130 || src2.len() != SCRATCH130 {
        return Err(slice_err(format!(
            "67a960 sources must be {SCRATCH130} bytes, got {} / {}",
            src1.len(),
            src2.len()
        )));
    }
    let source_67a978 = vm67cc18(0x1c0012000003b1c, src1, src2, t)?;
    derive_from_67a978_source(&source_67a978, offset, length, t)
}

/// kit `deriveFrom67a978Source`.
pub fn derive_from_67a978_source(source: &[u8], offset: usize, length: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if source.len() != SCRATCH130 {
        return Err(slice_err(format!("67a978 source must be {SCRATCH130} bytes, got {}", source.len())));
    }
    let source_67a990 = vm67cc18(0x82000000477, source, source, t)?;
    derive_from_67a990_source(&source_67a990, offset, length, t)
}

/// kit `deriveFrom67a990Source`.
pub fn derive_from_67a990_source(source: &[u8], offset: usize, length: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if source.len() != SCRATCH130 {
        return Err(slice_err(format!("67a990 source must be {SCRATCH130} bytes, got {}", source.len())));
    }
    let window = vm67cc18(0x82000003c2d, source, source, t)?;
    let chunks = final67cc18_sources(&window, t)?;
    derive_from_67cc18_sources(&chunks, offset, length, t)
}

/// kit `final67cc18Sources(fromOverlapWindow:)`.
fn final67cc18_sources(window: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    require(window, SCRATCH130, "67a990 overlap window")?;
    let first_src = &window[0x40..0x40 + BLOCK66];
    let second_src = &window[..BLOCK66];
    let mut out = vm67cc18(0x420000054c3, first_src, first_src, t)?;
    out.extend(vm67cc18(0x420000054c3, second_src, second_src, t)?);
    Ok(out)
}

/// kit `deriveFrom67cc18Sources`.
pub fn derive_from_67cc18_sources(source_chunks: &[u8], offset: usize, length: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if source_chunks.len() % BLOCK66 != 0 {
        return Err(slice_err(format!("67cc18 sources: encoded block length {}", source_chunks.len())));
    }
    let mut encoded = Vec::with_capacity(source_chunks.len());
    for start in (0..source_chunks.len()).step_by(BLOCK66) {
        let chunk = &source_chunks[start..start + BLOCK66];
        encoded.extend(vm67cc18(0x420000059c9, chunk, chunk, t)?);
    }
    derive64de54_slice(&encoded, offset, length, t)
}

/// kit `deriveFromFinalized679f48Context`.
pub fn derive_from_finalized679f48_context(context: &[u8], offset: usize, length: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if context.len() < CONTEXT679F48 {
        return Err(slice_err(format!("679f48 context too short: {}", context.len())));
    }
    let (src1, src2) = post_df80_67a960_inputs(context, t)?;
    derive_from_67a960_inputs(&src1, &src2, offset, length, t)
}

/// kit `deriveFrom679f48Context`: finalize then derive.
pub fn derive_from_679f48_context(context: &[u8], offset: usize, length: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let finalized = finalize679f48_to_second_df80(context, t)?;
    derive_from_finalized679f48_context(&finalized, offset, length, t)
}

/// kit `deriveFrom679f48Inputs`.
pub fn derive_from_679f48_inputs(
    previous_blocks: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let context = finalized679f48_context_from_inputs(previous_blocks, src4, t)?;
    derive_from_finalized679f48_context(&context, offset, length, t)
}

/// kit `deriveFrom660448RawDescriptor`.
pub fn derive_from_660448_raw_descriptor(
    raw_descriptor_blocks: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let previous_blocks = constructor670978_ptr28_blocks(raw_descriptor_blocks, t)?;
    derive_from_679f48_inputs(&previous_blocks, src4, offset, length, t)
}

/// kit `shiftedScratch`.
fn shifted_scratch(block66: &[u8]) -> Vec<u8> {
    let mut scratch = vec![0u8; SCRATCH130];
    scratch[0x3f] = 3;
    scratch[0x40..0x40 + BLOCK66].copy_from_slice(block66);
    scratch
}

/// kit `derive64de54Slice`: encoded 66-byte blocks → `length` bytes of source at `offset`.
pub fn derive64de54_slice(
    encoded_blocks: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if encoded_blocks.len() % BLOCK66 != 0 {
        return Err(slice_err(format!("64de54: encoded block length {}", encoded_blocks.len())));
    }
    let n_blocks = encoded_blocks.len() / BLOCK66;
    if n_blocks == 0 && length > 0 {
        return Err(slice_err("64de54: empty source"));
    }

    let mut expanded = Vec::with_capacity(n_blocks);
    for block_start in (0..encoded_blocks.len()).step_by(BLOCK66) {
        let block = &encoded_blocks[block_start..block_start + BLOCK66];
        expanded.push(vm64e2b8(0x42000000106, block, block, t)?);
    }

    let start_block = offset >> 4;
    let out_blocks = (length + 0x0f) >> 4;
    let mut stage_blocks: Vec<Vec<u8>> = Vec::with_capacity(out_blocks);

    for out_index in 0..out_blocks {
        let idx = start_block + out_index;
        let Some(block) = expanded.get(idx) else {
            return Err(slice_err(format!("64de54: slice starts past source at {idx}")));
        };

        let scratch_src2 = shifted_scratch(block);
        let scratch: Vec<u8> = if idx + 1 < expanded.len() {
            let mut src1 = expanded[idx + 1].clone();
            src1.resize(130, 0);
            vm64e2b8(0x100042000000148, &src1, &scratch_src2, t)?
        } else {
            vm64e2b8(0x82000000084, &scratch_src2, &scratch_src2, t)?
        };

        let mut shifted = scratch;
        for _ in 0..(16 - (offset & 0x0f)) {
            shifted = vm64e17c(&shifted, &shifted, t)?;
        }
        stage_blocks.push(vm64e2b8(0x42000000000, &shifted, &shifted, t)?);
    }

    let mut out = Vec::with_capacity(stage_blocks.len() * BLOCK66);
    for block in &stage_blocks {
        out.extend(vm64e2b8(0x42000000042, block, block, t)?);
    }
    Ok(out)
}

// ---------------------------------------------------------------- phase5 raw-key wrappers

/// kit `phase5RawKeyFrom67cc18Sources`.
pub fn phase5_raw_key_from_67cc18_sources(
    source_chunks: &[u8],
    offset: usize,
    t: &FirstPairTables,
    sched: &phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_67cc18_sources(source_chunks, offset, 0x10, t)?;
    phase5::derive_raw_key(&source, sched)
}

/// kit `phase5RawKeyFrom67a960Inputs`.
pub fn phase5_raw_key_from_67a960_inputs(
    src1: &[u8],
    src2: &[u8],
    offset: usize,
    t: &FirstPairTables,
    sched: &phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_67a960_inputs(src1, src2, offset, 0x10, t)?;
    phase5::derive_raw_key(&source, sched)
}

/// kit `phase5RawKeyFromFinalized679f48Context`.
pub fn phase5_raw_key_from_finalized679f48_context(
    context: &[u8],
    offset: usize,
    t: &FirstPairTables,
    sched: &phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_finalized679f48_context(context, offset, 0x10, t)?;
    phase5::derive_raw_key(&source, sched)
}

/// kit `phase5RawKeyFrom64de54EncodedBlocks`.
pub fn phase5_raw_key_from_64de54_encoded_blocks(
    encoded_blocks: &[u8],
    offset: usize,
    t: &FirstPairTables,
    sched: &phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive64de54_slice(encoded_blocks, offset, 0x10, t)?;
    phase5::derive_raw_key(&source, sched)
}