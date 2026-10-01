//! FirstPairSourceSlice — the seeded caller64 composition layer (Stage E of the 6388f0
//! first-pair builder port). Closes the chain stream seeds → 118 seeded caller rows → two
//! seeded 63c278 schedules → source66:
//! - the three 64cd40 call-state builders over the caller stack window (kit L6745-7577) and
//!   the 64cd40 call wrapper (L7578-7594),
//! - the seeded caller64 row / rows / rows-from-seeds builders (L7596-7798) with the row0/
//!   row59 start recovery and the limit-118 loop,
//! - the seeded 63c278 schedule builders over those rows (L7800-7846),
//! - the sensor-point seeds assembly composing the landed high-seed and low-seed layers
//!   (L2203-2298),
//! - the derive entry points (L8299-8394) plus the phase5 raw-key wrappers the golden
//!   vectors assert on (L8868-8939).
//!
//! Golden vectors: tests/firstpair_seeded.rs (ported 1:1 from FirstPairSourceSliceTests.swift).

use std::collections::BTreeMap;

use super::caller642::{builder642f60_outputs, pack_u32_le, Builder642f60Result};
use super::caller6473d0::{
    builder64cd40_output_words, builder6473d0_minimal_stack20_from_preimages,
    builder6473d0_outputs, builder6473d0_post_vectors, BUILDER6473D0_CALLER_STACK_PREIMAGE_BYTES,
    Builder6473d0Result,
};
use super::firstpair::*;
use super::highseed::{
    builder6388f0_caller_context_from_bundle, builder6388f0_caller_stream_u64,
    builder6388f0_caller_stream_u64_first_nibble_before_add, builder6388f0_convolution44,
    builder6388f0_first_pair642f60_stream_starts_from_seeds,
    builder6388f0_first_pair_stream_seeds_from5bcf98_outputs,
    builder6388f0_next642f60_inputs_from64cd40_outputs,
    builder6388f0_recover_stream_start_out0_seed_from642f60_x0,
    builder6388f0_recover_stream_start_out1_seed_from642f60_x1,
    builder6388f0_stream_start642f60_inputs, Builder6388f0Convolution44Constants,
    Builder6388f0FirstPair642f60Starts, Builder6388f0FirstPairStreamSeeds,
    Builder6388f0Next642f60Inputs,
};
use super::lowseed::{
    builder633fa8_null_scalar_window_from_entropy,
    builder633fa8_null_scalar_window_from_entropy_source,
    builder633fa8_static_scalar_window_from_entry_source,
    builder6388f0_row0_low_seed_preimages_from_entry_source, Builder6473d0OutputPreimages,
};
use super::schedule::{
    fold_table_u32_word_63c278, schedule_words_63c278, u32_table_word_63c278,
    PRE63C278_ARG0_SOURCE, PRE63C278_SCALAR, VEC_BYTES, VEC_WORDS,
};
use super::tables::FirstPairTables;
use crate::CryptoError;

// ---------------------------------------------------------------- builder sizing constants
// (FirstPairSourceSlice.swift L14002-14044; the 63c278 vector sizes land as schedule.rs
// VEC_WORDS/VEC_BYTES, the 6473d0 caller-stack preimage size as
// caller6473d0::BUILDER6473D0_CALLER_STACK_PREIMAGE_BYTES.)

/// kit `builder6388f0CallerStackBytes` (FirstPairSourceSlice.swift L14004).
const BUILDER6388F0_CALLER_STACK_BYTES: usize = 0x5000;
/// kit `builder6388f0CallerLoopTableRows` (FirstPairSourceSlice.swift L14036).
const BUILDER6388F0_CALLER_LOOP_TABLE_ROWS: usize = 59;
/// kit `builder6388f0CallerLoopRowBytes` (FirstPairSourceSlice.swift L14037).
const BUILDER6388F0_CALLER_LOOP_ROW_BYTES: usize = 0x58;
/// kit `builder6388f0FirstPairStreamRows` (FirstPairSourceSlice.swift L14044):
/// `builder6388f0CallerLoopTableRows * 2`.
pub const BUILDER6388F0_FIRST_PAIR_STREAM_ROWS: usize = BUILDER6388F0_CALLER_LOOP_TABLE_ROWS * 2;

// ---------------------------------------------------------------- structs

/// kit `Builder6388f0Caller64CallState` (FirstPairSourceSlice.swift L189-203).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0Caller64CallState {
    pub arg0: Vec<u8>,
    pub scalar: u64,
    pub x2_workspace: Vec<u8>,
    pub x3_preimage: Vec<u8>,
    pub stack_window: Vec<u8>,
}

/// kit `Builder6388f0Caller64Call` (FirstPairSourceSlice.swift L205-228).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0Caller64Call {
    pub arg0: Vec<u8>,
    pub scalar: u64,
    pub x2_workspace: Vec<u8>,
    pub x3_preimage: Vec<u8>,
    pub stack_window: Vec<u8>,
    pub output: Vec<u8>,
}

/// kit `Builder6388f0SeededCaller64Row` (FirstPairSourceSlice.swift L230-265).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0SeededCaller64Row {
    pub index: usize,
    pub current642f60: Builder6388f0Next642f60Inputs,
    pub preimages: Builder6473d0OutputPreimages,
    pub after642f60: Builder642f60Result,
    pub after6473d0: Builder6473d0Result,
    pub minimal_stack20: Vec<u8>,
    pub first64cd40: Builder6388f0Caller64Call,
    pub second64cd40: Builder6388f0Caller64Call,
    pub third64cd40: Builder6388f0Caller64Call,
    pub next642f60: Builder6388f0Next642f60Inputs,
}

/// kit `Builder6388f0Seeded63c278Stream` (FirstPairSourceSlice.swift L267-283).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0Seeded63c278Stream {
    pub row_index: usize,
    pub arg0: Vec<u8>,
    pub arg1: Vec<u8>,
    pub arg2: Vec<u8>,
    pub scalar: u64,
    pub schedule_words: Vec<u32>,
}

/// kit `Builder6388f0Seeded63c278Schedules` (FirstPairSourceSlice.swift L285-293).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0Seeded63c278Schedules {
    pub first: Builder6388f0Seeded63c278Stream,
    pub second: Builder6388f0Seeded63c278Stream,
}

// ---------------------------------------------------------------- stack helpers

/// kit `checkedReplace` (FirstPairSourceSlice.swift L9242-9247): the fail-closed
/// `invalidSlice(offset:length:)` guard the kit applies to every caller-stack write.
fn checked_replace(dst: &mut [u8], offset: usize, src: &[u8]) -> Result<(), CryptoError> {
    match offset.checked_add(src.len()) {
        Some(end) if end <= dst.len() => {
            replace_at(dst, offset, src);
            Ok(())
        }
        _ => Err(slice_err(format!(
            "invalidSlice(offset: {offset}, length: {})",
            src.len()
        ))),
    }
}

// ---------------------------------------------------------------- 64cd40 call states

/// kit `builder6388f0First64cd40CallState` (FirstPairSourceSlice.swift L6745-7008). The kit's
/// `entryIndex >= 0` guard (L6761) is structural under the `usize` parameter.
pub fn builder6388f0_first64cd40_call_state(
    context_source: &[u8],
    caller_stack20: &[u8],
    post_vectors: &BTreeMap<usize, Vec<u8>>,
    entry_index: usize,
    t: &FirstPairTables,
) -> Result<Builder6388f0Caller64CallState, CryptoError> {
    require(context_source, 0x420, "6388f0 caller context")?;
    require(
        caller_stack20,
        BUILDER6473D0_CALLER_STACK_PREIMAGE_BYTES,
        "6388f0 caller stack20",
    )?;

    let mut stack = vec![0u8; BUILDER6388F0_CALLER_STACK_BYTES];
    checked_replace(&mut stack, 0x230, context_source)?;
    checked_replace(&mut stack, 0x3708, caller_stack20)?;
    for (offset, raw) in post_vectors {
        checked_replace(&mut stack, *offset, raw)?;
    }

    let loop_slot = entry_index % BUILDER6388F0_CALLER_LOOP_TABLE_ROWS;
    let loop_counter = BUILDER6388F0_CALLER_LOOP_TABLE_ROWS - 1 - loop_slot;
    let pointer_delta = loop_slot * BUILDER6388F0_CALLER_LOOP_ROW_BYTES;

    let a_mul: u64 = 0x5025a2599f75877f;
    let a_add: u64 = 0x4d8a8810a4bbc5a3;
    let b_mul: u64 = 0x23c3d48d0602f787;
    let b_add: u64 = 0x62917fc875cc9e6b;
    let first_mix_mul: u64 = 0x6f8d70f401079e5b;
    let first_mix_add: u64 = 0x31b3e556163432ed;
    let caller_mix_mul: u64 = 0x30eef2ed3a43a4f9;
    let caller_mix_add: u64 = 0x92ef60a176c7d6c9;
    let first_fold_mul: u64 = 0x838e88db00000000;
    let caller_fold_mul: u64 = 0x37fd608100000000;

    let mut first_src_word =
        read_u32_le(&stack, 0x38c0).wrapping_mul(0xc938d835).wrapping_add(0xe6fc451b);
    let mut caller_word = read_u32_le(&stack, 0x6f8 + loop_counter * 0x58)
        .wrapping_mul(0xc955b06b)
        .wrapping_add(0x454427df);
    let first_b = builder6388f0_caller_stream_u64(
        first_src_word,
        a_mul,
        a_add,
        0x300ef0,
        first_fold_mul,
        first_mix_mul,
        first_mix_add,
        t,
    )?;
    let first_a = builder6388f0_caller_stream_u64(
        caller_word,
        b_mul,
        b_add,
        0x300f70,
        caller_fold_mul,
        caller_mix_mul,
        caller_mix_add,
        t,
    )?;
    write_u64_le(first_b, &mut stack, 0x3b80);
    write_u64_le(first_b, &mut stack, 0x4130);
    write_u64_le(first_a, &mut stack, 0x39c8);
    write_u64_le(first_a, &mut stack, 0x4010);

    let mut prefix_b = first_b;
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let mut word = read_u32_le(&stack, 0x38c0 + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x112e28 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x117268 + table_offset, t)?);
        word = word.wrapping_mul(0x56d9f19b).wrapping_add(0x64a9155b);
        let value = builder6388f0_caller_stream_u64(
            word,
            a_mul,
            a_add,
            0x300ef0,
            first_fold_mul,
            first_mix_mul,
            first_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x3b80 + index * 8);
        prefix_b = prefix_b.wrapping_add(value);
        write_u64_le(prefix_b, &mut stack, 0x4130 + index * 8);
    }

    let mut prefix_a = first_a;
    let caller_stream = 0x1aec - pointer_delta;
    for index in 0..(VEC_WORDS - 1) {
        let table_offset = ((index + 1) & 7) * 4;
        let mut word = read_u32_le(&stack, caller_stream + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x117288 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x1205e8 + table_offset, t)?);
        word = word.wrapping_mul(0x994a2aa3).wrapping_add(0x7f433349);
        let value = builder6388f0_caller_stream_u64(
            word,
            b_mul,
            b_add,
            0x300f70,
            caller_fold_mul,
            caller_mix_mul,
            caller_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x39d0 + index * 8);
        prefix_a = prefix_a.wrapping_add(value);
        write_u64_le(prefix_a, &mut stack, 0x4018 + index * 8);
    }

    builder6388f0_convolution44(
        &mut stack,
        0x39c8,
        0x4010,
        0x3b80,
        0x4130,
        0x3ce0,
        Builder6388f0Convolution44Constants {
            count_mul: 0x4079ef92755bf93a,
            count_add: 0xb43c87132a6e84d1,
            product_mul: 0x129a56bce90af833,
            b_prefix_mul: 0x8de93a973ee9c82b,
            a_prefix_mul: 0xb3c2bc6591a8beaa,
            final_mul: 0xe6bf6d3dc98f10f7,
            final_add: 0x632718706bc72397,
        },
    );

    let c_mul: u64 = 0x877a8a4a5f3b0f49;
    let c_add: u64 = 0xa24f4a31979cc775;
    let d_mul: u64 = 0xddfbefdc018359d5;
    let d_add: u64 = 0x7f589737aa46bdd5;
    let c_mix_mul: u64 = 0x5a83f7862436b279;
    let c_mix_add: u64 = 0x77cf4bf823a845d0;
    let d_mix_mul: u64 = 0x080c881a27926eee7;
    let d_mix_add: u64 = 0xeabaf4ef841c8c86;
    let c_fold_mul: u64 = 0xde34e64f00000000;
    let d_fold_mul: u64 = 0x438d983500000000;

    first_src_word =
        read_u32_le(&stack, 0x37b8).wrapping_mul(0xff9582fd).wrapping_add(0xfc52cb23);
    caller_word = read_u32_le(&stack, 0x1b40 + loop_counter * 0x58)
        .wrapping_mul(0xad09fb4b)
        .wrapping_add(0x4d566e95);
    let first_c = builder6388f0_caller_stream_u64(
        first_src_word,
        c_mul,
        c_add,
        0x300ff0,
        c_fold_mul,
        c_mix_mul,
        c_mix_add,
        t,
    )?;
    let first_d = builder6388f0_caller_stream_u64(
        caller_word,
        d_mul,
        d_add,
        0x301070,
        d_fold_mul,
        d_mix_mul,
        d_mix_add,
        t,
    )?;
    write_u64_le(first_c, &mut stack, 0x39c8);
    write_u64_le(first_c, &mut stack, 0x4010);
    write_u64_le(first_d, &mut stack, 0x4130);
    write_u64_le(first_d, &mut stack, 0x3ef0);

    let mut prefix_c = first_c;
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let mut word = read_u32_le(&stack, 0x37b8 + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x1218c8 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x1221e8 + table_offset, t)?);
        word = word.wrapping_mul(0x2c6e5d55).wrapping_add(0x63f5202d);
        let value = builder6388f0_caller_stream_u64(
            word,
            c_mul,
            c_add,
            0x300ff0,
            c_fold_mul,
            c_mix_mul,
            c_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x39c8 + index * 8);
        prefix_c = prefix_c.wrapping_add(value);
        write_u64_le(prefix_c, &mut stack, 0x4010 + index * 8);
    }

    let mut prefix_d = first_d;
    let caller_stream = 0x2f34 - pointer_delta;
    for index in 0..(VEC_WORDS - 1) {
        let table_offset = ((index + 1) & 7) * 4;
        let mut word = read_u32_le(&stack, caller_stream + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x119708 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x120608 + table_offset, t)?);
        word = word.wrapping_mul(0x206cd1f3).wrapping_add(0x867e396d);
        let value = builder6388f0_caller_stream_u64(
            word,
            d_mul,
            d_add,
            0x301070,
            d_fold_mul,
            d_mix_mul,
            d_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x4138 + index * 8);
        prefix_d = prefix_d.wrapping_add(value);
        write_u64_le(prefix_d, &mut stack, 0x3ef8 + index * 8);
    }

    builder6388f0_convolution44(
        &mut stack,
        0x4130,
        0x3ef0,
        0x39c8,
        0x4010,
        0x3b80,
        Builder6388f0Convolution44Constants {
            count_mul: 0xd16513f43f99d2c0,
            count_add: 0x5bdc507e86f7d211,
            product_mul: 0x49fd76daa54ce93b,
            b_prefix_mul: 0x4a15e654a01bea9e,
            a_prefix_mul: 0xe49b61c39c833ce0,
            final_mul: 0x9054b9a41de45a5b,
            final_add: 0x9b016b93e5b24765,
        },
    );

    for offset in (0..0x160).step_by(0x10) {
        let first_a = read_u64_le(&stack, 0x3ce0 + offset);
        let second_a = read_u64_le(&stack, 0x3ce0 + offset + 8);
        let first_b = read_u64_le(&stack, 0x3b80 + offset);
        let second_b = read_u64_le(&stack, 0x3b80 + offset + 8);
        write_u64_le(
            first_a
                .wrapping_mul(0xb8bc9deccc0ade89)
                .wrapping_add(0xc46ffd16f1b1756f)
                .wrapping_add(first_b.wrapping_mul(0x9c308b62a744c677)),
            &mut stack,
            0x39c8 + offset,
        );
        write_u64_le(
            second_a
                .wrapping_mul(0xb8bc9deccc0ade89)
                .wrapping_add(0xc46ffd16f1b1756f)
                .wrapping_add(second_b.wrapping_mul(0x9c308b62a744c677)),
            &mut stack,
            0x39c8 + offset + 8,
        );
    }

    Ok(Builder6388f0Caller64CallState {
        arg0: stack[0x330..0x388].to_vec(),
        scalar: read_u64_le(context_source, 0x418),
        x2_workspace: stack[0x39c8..0x3b28].to_vec(),
        x3_preimage: stack[0x3b28..0x3b80].to_vec(),
        stack_window: stack[0x3778..0x42c8].to_vec(),
    })
}

/// kit reads the scalar from the raw `contextSource` bytes (`contextBytes`, L6766/7003).

/// kit `builder6388f0Second64cd40CallState` (FirstPairSourceSlice.swift L7010-7292).
pub fn builder6388f0_second64cd40_call_state(
    context_source: &[u8],
    caller_stack20: &[u8],
    post_vectors: &BTreeMap<usize, Vec<u8>>,
    first64cd40_output: &[u8],
    entry_index: usize,
    t: &FirstPairTables,
) -> Result<Builder6388f0Caller64CallState, CryptoError> {
    require(context_source, 0x420, "6388f0 caller context")?;
    require(
        caller_stack20,
        BUILDER6473D0_CALLER_STACK_PREIMAGE_BYTES,
        "6388f0 caller stack20",
    )?;
    require(first64cd40_output, VEC_BYTES, "first 64cd40 output")?;

    let mut stack = vec![0u8; BUILDER6388F0_CALLER_STACK_BYTES];
    checked_replace(&mut stack, 0x230, context_source)?;
    checked_replace(&mut stack, 0x3708, caller_stack20)?;
    for (offset, raw) in post_vectors {
        checked_replace(&mut stack, *offset, raw)?;
    }
    checked_replace(&mut stack, 0x3b28, &first64cd40_output[..VEC_BYTES])?;

    let loop_slot = entry_index % BUILDER6388F0_CALLER_LOOP_TABLE_ROWS;
    let loop_counter = BUILDER6388F0_CALLER_LOOP_TABLE_ROWS - 1 - loop_slot;
    let pointer_delta = loop_slot * BUILDER6388F0_CALLER_LOOP_ROW_BYTES;
    write_u32_le(
        read_u32_le(&stack, 0x6f8 + loop_counter * 0x58),
        &mut stack,
        0x44,
    );
    write_u32_le(
        read_u32_le(&stack, 0x1b40 + loop_counter * 0x58),
        &mut stack,
        0x40,
    );

    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let word = read_u32_le(&stack, 0x3b28 + index * 4)
            .wrapping_mul(fold_table_u32_word_63c278(0x300ff0 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x1184c8 + table_offset, t)?);
        write_u32_le(word, &mut stack, 0x154 + index * 4);
    }

    let caller_word =
        read_u32_le(&stack, 0x44).wrapping_mul(0xa2e10181).wrapping_add(0xd0b84b4a);
    let post_word =
        read_u32_le(&stack, 0x3868).wrapping_mul(0xea9bc62b).wrapping_add(0x295fb23d);
    let caller_word_mul: u64 = 0x67eb8e340bf68edd;
    let caller_word_add: u64 = 0x7194bb146d6a6c98;
    let caller_mix_mul: u64 = 0x412cb68339b36b19;
    let caller_mix_add: u64 = 0xe7c0e7165633369b;
    let caller_fold_mul: u64 = 0xf883fc9300000000;
    let post_word_mul: u64 = 0x1e34bf9de310fbcb;
    let post_word_add: u64 = 0xe80eb386bd2c7669;
    let post_mix_mul: u64 = 0x3f2d22f0405cf24f;
    let post_mix_add: u64 = 0x316c36e4735ae9bc;
    let post_fold_mul: u64 = 0xa4e2a4f300000000;

    let caller_value = builder6388f0_caller_stream_u64(
        caller_word,
        caller_word_mul,
        caller_word_add,
        0x3013f0,
        caller_fold_mul,
        caller_mix_mul,
        caller_mix_add,
        t,
    )?;
    let post_value = builder6388f0_caller_stream_u64(
        post_word,
        post_word_mul,
        post_word_add,
        0x301370,
        post_fold_mul,
        post_mix_mul,
        post_mix_add,
        t,
    )?;
    write_u64_le(caller_value, &mut stack, 0x4130);
    write_u64_le(caller_value, &mut stack, 0x3ef0);
    write_u64_le(post_value, &mut stack, 0x3b80);
    write_u64_le(post_value, &mut stack, 0x4010);

    let mut prefix_post = post_value;
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let mut word = read_u32_le(&stack, 0x3868 + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x11c3c8 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x11d4c8 + table_offset, t)?);
        word = word.wrapping_mul(0xc99643bb).wrapping_add(0xac352509);
        let value = builder6388f0_caller_stream_u64(
            word,
            post_word_mul,
            post_word_add,
            0x301370,
            post_fold_mul,
            post_mix_mul,
            post_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x3b80 + index * 8);
        prefix_post = prefix_post.wrapping_add(value);
        write_u64_le(prefix_post, &mut stack, 0x4010 + index * 8);
    }

    let mut prefix_caller = caller_value;
    let caller_stream = 0x1aec - pointer_delta;
    for index in 0..(VEC_WORDS - 1) {
        let table_offset = ((index + 1) & 7) * 4;
        let mut word = read_u32_le(&stack, caller_stream + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x1125e8 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x118f08 + table_offset, t)?);
        word = word.wrapping_mul(0x31bbe0b7).wrapping_add(0x3fe25e18);
        let value = builder6388f0_caller_stream_u64(
            word,
            caller_word_mul,
            caller_word_add,
            0x3013f0,
            caller_fold_mul,
            caller_mix_mul,
            caller_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x4138 + index * 8);
        prefix_caller = prefix_caller.wrapping_add(value);
        write_u64_le(prefix_caller, &mut stack, 0x3ef8 + index * 8);
    }

    builder6388f0_convolution44(
        &mut stack,
        0x4130,
        0x3ef0,
        0x3b80,
        0x4010,
        0x3ce0,
        Builder6388f0Convolution44Constants {
            count_mul: 0x31387af6df27bc34,
            count_add: 0x5e7eda1d7e652662,
            product_mul: 0xdc67c7dbf68b7273,
            b_prefix_mul: 0x8f98298f0679fa22,
            a_prefix_mul: 0x662a1479caab56ce,
            final_mul: 0x26efbb4b51cdc6b5,
            final_add: 0xc5b3a8b6b472e5d3,
        },
    );

    let caller_word =
        read_u32_le(&stack, 0x40).wrapping_mul(0x63dc1441).wrapping_add(0xda7427c7);
    let post_word =
        read_u32_le(&stack, 0x3760).wrapping_mul(0xe609bd27).wrapping_add(0x93c1ccd4);
    let caller_word_mul: u64 = 0xdca944fb28ac47f7;
    let caller_word_add: u64 = 0xd57d3e716bf087fc;
    let caller_mix_mul: u64 = 0xb241122944abe41d;
    let caller_mix_add: u64 = 0xeb98df0f724a8bc5;
    let caller_fold_mul: u64 = 0xd584887500000000;
    let post_word_mul: u64 = 0x86835750d0f2d33d;
    let post_word_add: u64 = 0x93d6710e2805c2cd;
    let post_mix_mul: u64 = 0x37836d2c6f35aeaf;
    let post_mix_add: u64 = 0xabfb5017ca2ca427;
    let post_fold_mul: u64 = 0x749f87a500000000;

    let caller_value = builder6388f0_caller_stream_u64(
        caller_word,
        caller_word_mul,
        caller_word_add,
        0x3014f0,
        caller_fold_mul,
        caller_mix_mul,
        caller_mix_add,
        t,
    )?;
    let post_value = builder6388f0_caller_stream_u64(
        post_word,
        post_word_mul,
        post_word_add,
        0x301470,
        post_fold_mul,
        post_mix_mul,
        post_mix_add,
        t,
    )?;
    write_u64_le(caller_value, &mut stack, 0x4010);
    write_u64_le(caller_value, &mut stack, 0x3e40);
    write_u64_le(post_value, &mut stack, 0x4130);
    write_u64_le(post_value, &mut stack, 0x3ef0);

    let mut prefix_post = post_value;
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let mut word = read_u32_le(&stack, 0x3760 + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x11e588 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x122a68 + table_offset, t)?);
        word = word.wrapping_mul(0x712dee2f).wrapping_add(0xecb470d1);
        let value = builder6388f0_caller_stream_u64(
            word,
            post_word_mul,
            post_word_add,
            0x301470,
            post_fold_mul,
            post_mix_mul,
            post_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x4130 + index * 8);
        prefix_post = prefix_post.wrapping_add(value);
        write_u64_le(prefix_post, &mut stack, 0x3ef0 + index * 8);
    }

    let mut prefix_caller = caller_value;
    let caller_stream = 0x2f34 - pointer_delta;
    for index in 0..(VEC_WORDS - 1) {
        let table_offset = ((index + 1) & 7) * 4;
        let mut word = read_u32_le(&stack, caller_stream + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x120628 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x122a88 + table_offset, t)?);
        word = word.wrapping_mul(0x26f75f39).wrapping_add(0x1c4c83fc);
        let value = builder6388f0_caller_stream_u64(
            word,
            caller_word_mul,
            caller_word_add,
            0x3014f0,
            caller_fold_mul,
            caller_mix_mul,
            caller_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x4018 + index * 8);
        prefix_caller = prefix_caller.wrapping_add(value);
        write_u64_le(prefix_caller, &mut stack, 0x3e48 + index * 8);
    }

    builder6388f0_convolution44(
        &mut stack,
        0x4010,
        0x3e40,
        0x4130,
        0x3ef0,
        0x3b80,
        Builder6388f0Convolution44Constants {
            count_mul: 0xf8f0e2182e743120,
            count_add: 0x2ea75adafa845934,
            product_mul: 0x49eba04bc8aba147,
            b_prefix_mul: 0x2ae8b5655df9be65,
            a_prefix_mul: 0xcc8eaf52163f5260,
            final_mul: 0xfee73c0f7de3fa41,
            final_add: 0x7572d2a401ed3b6a,
        },
    );

    for offset in (0..0x160).step_by(0x10) {
        let first_a = read_u64_le(&stack, 0x3ce0 + offset);
        let second_a = read_u64_le(&stack, 0x3ce0 + offset + 8);
        let first_b = read_u64_le(&stack, 0x3b80 + offset);
        let second_b = read_u64_le(&stack, 0x3b80 + offset + 8);
        write_u64_le(
            first_a
                .wrapping_mul(0xf6ebf5f38b50e6e5)
                .wrapping_add(0x08494646ffdad49a)
                .wrapping_add(first_b.wrapping_mul(0x26f0954510cb129f)),
            &mut stack,
            0x39c8 + offset,
        );
        write_u64_le(
            second_a
                .wrapping_mul(0xf6ebf5f38b50e6e5)
                .wrapping_add(0x08494646ffdad49a)
                .wrapping_add(second_b.wrapping_mul(0x26f0954510cb129f)),
            &mut stack,
            0x39c8 + offset + 8,
        );
    }

    Ok(Builder6388f0Caller64CallState {
        arg0: stack[0x330..0x388].to_vec(),
        scalar: read_u64_le(context_source, 0x418),
        x2_workspace: stack[0x39c8..0x3b28].to_vec(),
        x3_preimage: stack[0x3b28..0x3b80].to_vec(),
        stack_window: stack[0x3778..0x42c8].to_vec(),
    })
}

/// kit `builder6388f0Third64cd40CallState` (FirstPairSourceSlice.swift L7294-7576).
pub fn builder6388f0_third64cd40_call_state(
    context_source: &[u8],
    caller_stack20: &[u8],
    post_vectors: &BTreeMap<usize, Vec<u8>>,
    second64cd40_output: &[u8],
    entry_index: usize,
    t: &FirstPairTables,
) -> Result<Builder6388f0Caller64CallState, CryptoError> {
    require(context_source, 0x420, "6388f0 caller context")?;
    require(
        caller_stack20,
        BUILDER6473D0_CALLER_STACK_PREIMAGE_BYTES,
        "6388f0 caller stack20",
    )?;
    require(second64cd40_output, VEC_BYTES, "second 64cd40 output")?;

    let mut stack = vec![0u8; BUILDER6388F0_CALLER_STACK_BYTES];
    checked_replace(&mut stack, 0x230, context_source)?;
    checked_replace(&mut stack, 0x3708, caller_stack20)?;
    for (offset, raw) in post_vectors {
        checked_replace(&mut stack, *offset, raw)?;
    }
    checked_replace(&mut stack, 0x3b28, &second64cd40_output[..VEC_BYTES])?;

    let loop_slot = entry_index % BUILDER6388F0_CALLER_LOOP_TABLE_ROWS;
    let loop_counter = BUILDER6388F0_CALLER_LOOP_TABLE_ROWS - 1 - loop_slot;
    let pointer_delta = loop_slot * BUILDER6388F0_CALLER_LOOP_ROW_BYTES;
    write_u32_le(
        read_u32_le(&stack, 0x6f8 + loop_counter * 0x58),
        &mut stack,
        0x44,
    );
    write_u32_le(
        read_u32_le(&stack, 0x1b40 + loop_counter * 0x58),
        &mut stack,
        0x40,
    );

    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let word = read_u32_le(&stack, 0x3b28 + index * 4)
            .wrapping_mul(u32_table_word_63c278(0x114948 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x1184e8 + table_offset, t)?);
        write_u32_le(word, &mut stack, 0xfc + index * 4);
    }

    let caller_word =
        read_u32_le(&stack, 0x44).wrapping_mul(0x52b341e9).wrapping_add(0x8fe4704a);
    let post_word =
        read_u32_le(&stack, 0x3810).wrapping_mul(0xb1c3b83d).wrapping_add(0x4ac96e8d);
    let caller_word_mul: u64 = 0xc601c25eb7863abb;
    let caller_word_add: u64 = 0x8a9ac40e5bfb780d;
    let caller_mix_mul: u64 = 0xe76d920aeec9873d;
    let caller_mix_add: u64 = 0x63d22c2ddb82d5a1;
    let caller_fold_mul: u64 = 0x9897ad9900000000;
    let post_word_mul: u64 = 0x16aacea9a72f0c45;
    let post_word_add: u64 = 0xe952bbc97872445c;
    let post_mix_mul: u64 = 0x9c887c5c45db1a3b;
    let post_mix_add: u64 = 0x6536302ead1b2169;
    let post_fold_mul: u64 = 0x7db1cb8100000000;

    let caller_value = builder6388f0_caller_stream_u64(
        caller_word,
        caller_word_mul,
        caller_word_add,
        0x3015f0,
        caller_fold_mul,
        caller_mix_mul,
        caller_mix_add,
        t,
    )?;
    let post_value = builder6388f0_caller_stream_u64(
        post_word,
        post_word_mul,
        post_word_add,
        0x301570,
        post_fold_mul,
        post_mix_mul,
        post_mix_add,
        t,
    )?;
    write_u64_le(caller_value, &mut stack, 0x4130);
    write_u64_le(caller_value, &mut stack, 0x3ef0);
    write_u64_le(post_value, &mut stack, 0x3b80);
    write_u64_le(post_value, &mut stack, 0x4010);

    let mut prefix_post = post_value;
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let mut word = read_u32_le(&stack, 0x3810 + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x120648 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x122208 + table_offset, t)?);
        word = word.wrapping_mul(0xad44242b).wrapping_add(0x28772583);
        let value = builder6388f0_caller_stream_u64(
            word,
            post_word_mul,
            post_word_add,
            0x301570,
            post_fold_mul,
            post_mix_mul,
            post_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x3b80 + index * 8);
        prefix_post = prefix_post.wrapping_add(value);
        write_u64_le(prefix_post, &mut stack, 0x4010 + index * 8);
    }

    let mut prefix_caller = caller_value;
    let caller_stream = 0x1aec - pointer_delta;
    for index in 0..(VEC_WORDS - 1) {
        let table_offset = ((index + 1) & 7) * 4;
        let mut word = read_u32_le(&stack, caller_stream + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x119728 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x1218e8 + table_offset, t)?);
        word = word.wrapping_mul(0xb7911189).wrapping_add(0x50798488);
        let value = builder6388f0_caller_stream_u64(
            word,
            caller_word_mul,
            caller_word_add,
            0x3015f0,
            caller_fold_mul,
            caller_mix_mul,
            caller_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x4138 + index * 8);
        prefix_caller = prefix_caller.wrapping_add(value);
        write_u64_le(prefix_caller, &mut stack, 0x3ef8 + index * 8);
    }

    builder6388f0_convolution44(
        &mut stack,
        0x4130,
        0x3ef0,
        0x3b80,
        0x4010,
        0x3ce0,
        Builder6388f0Convolution44Constants {
            count_mul: 0x02949f32b4f07fd8,
            count_add: 0xbea7dd815afcbcc1,
            product_mul: 0x7611b37d7c8f4475,
            b_prefix_mul: 0xb4709b2cff94859c,
            a_prefix_mul: 0xde2dc8d44e8f4662,
            final_mul: 0x3b985d4b603f64d9,
            final_add: 0x9a4acc2ae823c739,
        },
    );

    let caller_word =
        read_u32_le(&stack, 0x40).wrapping_mul(0x10aa89f9).wrapping_add(0x5f38d605);
    let post_word =
        read_u32_le(&stack, 0x3708).wrapping_mul(0x49dc9b53).wrapping_add(0x4de59f05);
    let caller_word_mul: u64 = 0x8b7f3e328f16058b;
    let caller_word_add: u64 = 0xc7947e77ef912670;
    let caller_mix_mul: u64 = 0x272b96c7a7cb8ff9;
    let caller_mix_add: u64 = 0x7dff808a85cebcac;
    let caller_fold_mul: u64 = 0xbcbba6f500000000;
    let post_word_mul: u64 = 0x4cea0abb01866b97;
    let post_word_add: u64 = 0xd8436bdaf28ca051;
    let post_mix_mul: u64 = 0x4c0e84f7d0089f9b;
    let post_mix_add: u64 = 0xb5d5f7a06307c689;
    let post_fold_mul: u64 = 0x832f036300000000;

    let caller_value = builder6388f0_caller_stream_u64_first_nibble_before_add(
        caller_word,
        caller_word_mul,
        caller_word_add,
        0x3016f0,
        caller_fold_mul,
        caller_mix_mul,
        caller_mix_add,
        t,
    )?;
    let post_value = builder6388f0_caller_stream_u64(
        post_word,
        post_word_mul,
        post_word_add,
        0x301670,
        post_fold_mul,
        post_mix_mul,
        post_mix_add,
        t,
    )?;
    write_u64_le(caller_value, &mut stack, 0x4010);
    write_u64_le(caller_value, &mut stack, 0x3e40);
    write_u64_le(post_value, &mut stack, 0x4130);
    write_u64_le(post_value, &mut stack, 0x3ef0);

    let mut prefix_post = post_value;
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let mut word = read_u32_le(&stack, 0x3708 + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x115f28 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x118508 + table_offset, t)?);
        word = word.wrapping_mul(0x68c9b103).wrapping_add(0x45ce4a73);
        let value = builder6388f0_caller_stream_u64(
            word,
            post_word_mul,
            post_word_add,
            0x301670,
            post_fold_mul,
            post_mix_mul,
            post_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x4130 + index * 8);
        prefix_post = prefix_post.wrapping_add(value);
        write_u64_le(prefix_post, &mut stack, 0x3ef0 + index * 8);
    }

    let mut prefix_caller = caller_value;
    let caller_stream = 0x2f34 - pointer_delta;
    for index in 0..(VEC_WORDS - 1) {
        let table_offset = ((index + 1) & 7) * 4;
        let mut word = read_u32_le(&stack, caller_stream + index * 4);
        word = word
            .wrapping_mul(u32_table_word_63c278(0x115468 + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(0x118528 + table_offset, t)?);
        word = word.wrapping_mul(0xf6c4e17d).wrapping_add(0x0882a9cf);
        let value = builder6388f0_caller_stream_u64_first_nibble_before_add(
            word,
            caller_word_mul,
            caller_word_add,
            0x3016f0,
            caller_fold_mul,
            caller_mix_mul,
            caller_mix_add,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x4018 + index * 8);
        prefix_caller = prefix_caller.wrapping_add(value);
        write_u64_le(prefix_caller, &mut stack, 0x3e48 + index * 8);
    }

    builder6388f0_convolution44(
        &mut stack,
        0x4010,
        0x3e40,
        0x4130,
        0x3ef0,
        0x3b80,
        Builder6388f0Convolution44Constants {
            count_mul: 0x638b8c646690163a,
            count_add: 0x96f60e030a9158de,
            product_mul: 0x59ee1b3f304ea615,
            b_prefix_mul: 0x3a17e3aa11527f5e,
            a_prefix_mul: 0xcf4a3bc55798adef,
            final_mul: 0x15bcf2fe3c06e5af,
            final_add: 0xcc8339ef4cba9cd0,
        },
    );

    for offset in (0..0x160).step_by(0x10) {
        let first_a = read_u64_le(&stack, 0x3ce0 + offset);
        let second_a = read_u64_le(&stack, 0x3ce0 + offset + 8);
        let first_b = read_u64_le(&stack, 0x3b80 + offset);
        let second_b = read_u64_le(&stack, 0x3b80 + offset + 8);
        write_u64_le(
            first_a
                .wrapping_mul(0x929296759110b0a3)
                .wrapping_add(0x0ede13af97827959)
                .wrapping_add(first_b.wrapping_mul(0x70b7f6eaabceff57)),
            &mut stack,
            0x39c8 + offset,
        );
        write_u64_le(
            second_a
                .wrapping_mul(0x929296759110b0a3)
                .wrapping_add(0x0ede13af97827959)
                .wrapping_add(second_b.wrapping_mul(0x70b7f6eaabceff57)),
            &mut stack,
            0x39c8 + offset + 8,
        );
    }

    Ok(Builder6388f0Caller64CallState {
        arg0: stack[0x330..0x388].to_vec(),
        scalar: read_u64_le(context_source, 0x418),
        x2_workspace: stack[0x39c8..0x3b28].to_vec(),
        x3_preimage: stack[0x3b28..0x3b80].to_vec(),
        stack_window: stack[0x3778..0x42c8].to_vec(),
    })
}

// ---------------------------------------------------------------- 64cd40 call

/// kit `builder6388f0Call64Call` (FirstPairSourceSlice.swift L7578-7594).
pub fn builder6388f0_call64_call(
    state: Builder6388f0Caller64CallState,
    t: &FirstPairTables,
) -> Result<Builder6388f0Caller64Call, CryptoError> {
    let output = pack_u32_le(&builder64cd40_output_words(
        &state.arg0,
        state.scalar,
        &state.x2_workspace,
        t,
    )?);
    Ok(Builder6388f0Caller64Call {
        arg0: state.arg0,
        scalar: state.scalar,
        x2_workspace: state.x2_workspace,
        x3_preimage: state.x3_preimage,
        stack_window: state.stack_window,
        output,
    })
}

// ---------------------------------------------------------------- seeded caller64 rows

/// kit `builder6388f0SeededCaller64Row` (FirstPairSourceSlice.swift L7596-7670).
pub fn builder6388f0_seeded_caller64_row(
    index: usize,
    current_642f60: &Builder6388f0Next642f60Inputs,
    preimages: &Builder6473d0OutputPreimages,
    context_source: Option<&[u8]>,
    t: &FirstPairTables,
) -> Result<Builder6388f0SeededCaller64Row, CryptoError> {
    let context: Vec<u8> = match context_source {
        Some(source) => source.to_vec(),
        None => builder6388f0_caller_context_from_bundle(t)?,
    };

    let after642f60 = builder642f60_outputs(
        &current_642f60.x0,
        &current_642f60.x1,
        &current_642f60.x2,
        &context,
        t,
    )?;
    let after6473d0 = builder6473d0_outputs(
        &after642f60.out0,
        &after642f60.out1,
        &after642f60.out2,
        &context,
        Some(&preimages.out0),
        Some(&preimages.out1),
        t,
    )?;
    let minimal_stack20 = builder6473d0_minimal_stack20_from_preimages(preimages)?;
    let post_vectors = builder6473d0_post_vectors(&after6473d0);

    let first64cd40 = builder6388f0_call64_call(
        builder6388f0_first64cd40_call_state(
            &context,
            &minimal_stack20,
            &post_vectors,
            index,
            t,
        )?,
        t,
    )?;
    let second64cd40 = builder6388f0_call64_call(
        builder6388f0_second64cd40_call_state(
            &context,
            &minimal_stack20,
            &post_vectors,
            &first64cd40.output,
            index,
            t,
        )?,
        t,
    )?;
    let third64cd40 = builder6388f0_call64_call(
        builder6388f0_third64cd40_call_state(
            &context,
            &minimal_stack20,
            &post_vectors,
            &second64cd40.output,
            index,
            t,
        )?,
        t,
    )?;
    let next642f60 = builder6388f0_next642f60_inputs_from64cd40_outputs(
        &first64cd40.output,
        &second64cd40.output,
        &third64cd40.output,
        t,
    )?;

    Ok(Builder6388f0SeededCaller64Row {
        index,
        current642f60: current_642f60.clone(),
        preimages: preimages.clone(),
        after642f60,
        after6473d0,
        minimal_stack20,
        first64cd40,
        second64cd40,
        third64cd40,
        next642f60,
    })
}

/// kit `builder6388f0SeededCaller64Rows` (FirstPairSourceSlice.swift L7672-7772). The kit's
/// `limit >= 0` guard (L7679) is structural under the `usize` parameter.
pub fn builder6388f0_seeded_caller64_rows(
    starts: &Builder6388f0FirstPair642f60Starts,
    row0_low_preimages: &Builder6473d0OutputPreimages,
    context_source: Option<&[u8]>,
    limit: usize,
    x2_source: Option<&[u8]>,
    t: &FirstPairTables,
) -> Result<Vec<Builder6388f0SeededCaller64Row>, CryptoError> {
    if limit > BUILDER6388F0_FIRST_PAIR_STREAM_ROWS {
        return Err(slice_err(format!("invalid6388f0RowLimit({limit})")));
    }

    let context: Vec<u8> = match context_source {
        Some(source) => source.to_vec(),
        None => builder6388f0_caller_context_from_bundle(t)?,
    };

    let row0_out0 = builder6388f0_recover_stream_start_out0_seed_from642f60_x0(&starts.row0.x0, t)?;
    let row0_out1 = builder6388f0_recover_stream_start_out1_seed_from642f60_x1(&starts.row0.x1, t)?;
    let row59_out0 = builder6388f0_recover_stream_start_out0_seed_from642f60_x0(&starts.row59.x0, t)?;
    let row59_out1 = builder6388f0_recover_stream_start_out1_seed_from642f60_x1(&starts.row59.x1, t)?;

    let row0_start =
        builder6388f0_stream_start642f60_inputs(&row0_out0, &row0_out1, x2_source, t)?;
    let row59_start =
        builder6388f0_stream_start642f60_inputs(&row59_out0, &row59_out1, x2_source, t)?;

    let mut rows: Vec<Builder6388f0SeededCaller64Row> = Vec::with_capacity(limit);
    let mut previous_6473d0: Option<Builder6473d0Result> = None;
    let mut carried_642f60: Option<Builder6388f0Next642f60Inputs> = None;
    let mut active_out0_seed: Option<Vec<u8>> = None;
    let mut active_out1_seed: Option<Vec<u8>> = None;

    for index in 0..limit {
        let current_642f60: Builder6388f0Next642f60Inputs;
        let preimages: Builder6473d0OutputPreimages;

        if index == 0 {
            current_642f60 = row0_start.clone();
            active_out0_seed = Some(row0_out0.clone());
            active_out1_seed = row0_out1.clone().into();
            preimages = Builder6473d0OutputPreimages {
                out4: row0_low_preimages.out4.clone(),
                out3: row0_low_preimages.out3.clone(),
                out2: row0_low_preimages.out2.clone(),
                out1: row0_out1.clone(),
                out0: row0_out0.clone(),
            };
        } else if index == BUILDER6388F0_CALLER_LOOP_TABLE_ROWS {
            let previous = previous_6473d0.as_ref().ok_or_else(|| {
                slice_err(format!("invalid6388f0EntryIndex({index})"))
            })?;
            current_642f60 = row59_start.clone();
            active_out0_seed = row59_out0.clone().into();
            active_out1_seed = row59_out1.clone().into();
            preimages = Builder6473d0OutputPreimages {
                out4: previous.out4.clone(),
                out3: previous.out3.clone(),
                out2: previous.out2.clone(),
                out1: row59_out1.clone(),
                out0: row59_out0.clone(),
            };
        } else {
            let (carried, previous, out0, out1) = match (
                carried_642f60.as_ref(),
                previous_6473d0.as_ref(),
                active_out0_seed.as_ref(),
                active_out1_seed.as_ref(),
            ) {
                (Some(c), Some(p), Some(o0), Some(o1)) => (c, p, o0, o1),
                _ => {
                    return Err(slice_err(format!("invalid6388f0EntryIndex({index})")));
                }
            };
            current_642f60 = carried.clone();
            preimages = Builder6473d0OutputPreimages {
                out4: previous.out4.clone(),
                out3: previous.out3.clone(),
                out2: previous.out2.clone(),
                out1: out1.clone(),
                out0: out0.clone(),
            };
        }

        let row = builder6388f0_seeded_caller64_row(
            index,
            &current_642f60,
            &preimages,
            Some(&context),
            t,
        )?;
        previous_6473d0 = Some(row.after6473d0.clone());
        carried_642f60 = Some(row.next642f60.clone());
        rows.push(row);
    }
    Ok(rows)
}

/// kit `builder6388f0SeededCaller64RowsFromFirstPairStreamSeeds` (FirstPairSourceSlice.swift
/// L7774-7798).
pub fn builder6388f0_seeded_caller64_rows_from_first_pair_stream_seeds(
    seeds: &Builder6388f0FirstPairStreamSeeds,
    context_source: Option<&[u8]>,
    limit: usize,
    x2_source: Option<&[u8]>,
    t: &FirstPairTables,
) -> Result<Vec<Builder6388f0SeededCaller64Row>, CryptoError> {
    let starts = builder6388f0_first_pair642f60_stream_starts_from_seeds(seeds, x2_source, t)?;
    let row0_low_preimages = Builder6473d0OutputPreimages {
        out4: seeds.row0_out4.clone(),
        out3: seeds.row0_out3.clone(),
        out2: seeds.row0_out2.clone(),
        out1: seeds.row0_out1.clone(),
        out0: seeds.row0_out0.clone(),
    };
    builder6388f0_seeded_caller64_rows(
        &starts,
        &row0_low_preimages,
        context_source,
        limit,
        x2_source,
        t,
    )
}

// ---------------------------------------------------------------- seeded 63c278 schedules

/// kit `Builder6388f0Seeded63c278SchedulesFromRows(rows:)` (FirstPairSourceSlice.swift
/// L7800-7808): the default `pre63c278Arg0Source` / `pre63c278Scalar` overload.
pub fn builder6388f0_seeded63c278_schedules_from_rows(
    rows: &[Builder6388f0SeededCaller64Row],
    t: &FirstPairTables,
) -> Result<Builder6388f0Seeded63c278Schedules, CryptoError> {
    builder6388f0_seeded63c278_schedules_from_rows_arg0_scalar(
        rows,
        &PRE63C278_ARG0_SOURCE,
        PRE63C278_SCALAR,
        t,
    )
}

/// kit `builder6388f0Seeded63c278SchedulesFromRows(rows:arg0:scalar:)` (FirstPairSourceSlice.swift
/// L7810-7846).
pub fn builder6388f0_seeded63c278_schedules_from_rows_arg0_scalar(
    rows: &[Builder6388f0SeededCaller64Row],
    arg0: &[u8],
    scalar: u64,
    t: &FirstPairTables,
) -> Result<Builder6388f0Seeded63c278Schedules, CryptoError> {
    if rows.len() < BUILDER6388F0_FIRST_PAIR_STREAM_ROWS {
        return Err(slice_err(format!("invalid6388f0RowLimit({})", rows.len())));
    }
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    let arg0_prefix = &arg0[..VEC_BYTES];

    let stream = |row_index: usize| -> Result<Builder6388f0Seeded63c278Stream, CryptoError> {
        let row = &rows[row_index];
        let arg1 = &row.next642f60.x0;
        let arg2 = &row.next642f60.x2;
        Ok(Builder6388f0Seeded63c278Stream {
            row_index,
            arg0: arg0_prefix.to_vec(),
            arg1: arg1.clone(),
            arg2: arg2.clone(),
            scalar,
            schedule_words: schedule_words_63c278(arg0_prefix, arg1, arg2, scalar, t)?,
        })
    };

    Ok(Builder6388f0Seeded63c278Schedules {
        first: stream(BUILDER6388F0_CALLER_LOOP_TABLE_ROWS - 1)?,
        second: stream(BUILDER6388F0_FIRST_PAIR_STREAM_ROWS - 1)?,
    })
}

// ---------------------------------------------------------------- sensor-point seeds assembly

/// kit `builder6388f0FirstPairStreamSeedsFromScalarsAndSensorPoints` (FirstPairSourceSlice.swift
/// L2203-2243): landed high-seed (P-256 → 6421c0) + low-seed (entry source) composition.
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_first_pair_stream_seeds_from_scalars_and_sensor_points(
    entry_source: &[u8],
    null_scalar_window: &[u8],
    static_scalar_window: &[u8],
    row0_sensor_point_xy_be: &[u8],
    row59_sensor_point_xy_be: &[u8],
    null_entropy_11a: &[u8],
    null_attempts: usize,
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPairStreamSeeds, CryptoError> {
    let low = builder6388f0_row0_low_seed_preimages_from_entry_source(entry_source, t)?;
    let row0_high = super::highseed::builder6388f0_high_seed_stream_start_seeds_from_scalar_p256(
        null_scalar_window,
        row0_sensor_point_xy_be,
        x1_source,
        x2_source,
        scalar,
        t,
    )?;
    let row59_high = super::highseed::builder6388f0_high_seed_stream_start_seeds_from_scalar_p256(
        static_scalar_window,
        row59_sensor_point_xy_be,
        x1_source,
        x2_source,
        scalar,
        t,
    )?;
    Ok(Builder6388f0FirstPairStreamSeeds {
        null_scalar_window: null_scalar_window.to_vec(),
        static_scalar_window: static_scalar_window.to_vec(),
        null_entropy_11a: null_entropy_11a.to_vec(),
        null_attempts,
        row0_out4: low.out4,
        row0_out3: low.out3,
        row0_out2: low.out2,
        row0_out1: row0_high.out1,
        row0_out0: row0_high.out0,
        row59_out1: row59_high.out1,
        row59_out0: row59_high.out0,
    })
}

/// kit `builder6388f0FirstPairStreamSeedsFromEntropyAndSensorPoints` (FirstPairSourceSlice.swift
/// L2245-2268).
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_first_pair_stream_seeds_from_entropy_and_sensor_points(
    entry_source: &[u8],
    null_entropy_11a: &[u8],
    row0_sensor_point_xy_be: &[u8],
    row59_sensor_point_xy_be: &[u8],
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPairStreamSeeds, CryptoError> {
    let null_scalar = builder633fa8_null_scalar_window_from_entropy(null_entropy_11a, t)?;
    let static_scalar = builder633fa8_static_scalar_window_from_entry_source(entry_source, t)?;
    builder6388f0_first_pair_stream_seeds_from_scalars_and_sensor_points(
        entry_source,
        &null_scalar,
        &static_scalar,
        row0_sensor_point_xy_be,
        row59_sensor_point_xy_be,
        null_entropy_11a,
        1,
        x1_source,
        x2_source,
        scalar,
        t,
    )
}

/// kit `builder6388f0FirstPairStreamSeedsFromEntropySourceAndSensorPoints` (FirstPairSourceSlice.swift
/// L2270-2297).
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_first_pair_stream_seeds_from_entropy_source_and_sensor_points<F>(
    entry_source: &[u8],
    row0_sensor_point_xy_be: &[u8],
    row59_sensor_point_xy_be: &[u8],
    max_attempts: usize,
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    entropy_source: F,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPairStreamSeeds, CryptoError>
where
    F: FnMut(usize) -> Result<Vec<u8>, CryptoError>,
{
    let null_result =
        builder633fa8_null_scalar_window_from_entropy_source(max_attempts, entropy_source, t)?;
    let static_scalar = builder633fa8_static_scalar_window_from_entry_source(entry_source, t)?;
    builder6388f0_first_pair_stream_seeds_from_scalars_and_sensor_points(
        entry_source,
        &null_result.scalar_window,
        &static_scalar,
        row0_sensor_point_xy_be,
        row59_sensor_point_xy_be,
        &null_result.entropy11a,
        null_result.attempts,
        x1_source,
        x2_source,
        scalar,
        t,
    )
}

// ---------------------------------------------------------------- 5bcf98-output seeds trio
// kit `builder6388f0FirstPairStreamSeedsFrom…5bcf98Outputs` (FirstPairSourceSlice.swift
// L2109-2201): the entry-source / entropy / entropy-source compositions over the landed
// 5bcf98-output seeds assembly, closing the Stage E boundary.

/// kit `builder6388f0FirstPairStreamSeedsFromEntrySourceAnd5bcf98Outputs`
/// (FirstPairSourceSlice.swift L2109-2143): the low preimages come from the bundled entry
/// source and the static scalar window defaults to the entry-source derivation when empty.
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs(
    entry_source: &[u8],
    row0_first_output70: &[u8],
    row0_second_output70: &[u8],
    row59_first_output70: &[u8],
    row59_second_output70: &[u8],
    null_scalar_window: &[u8],
    static_scalar_window: &[u8],
    null_entropy_11a: &[u8],
    null_attempts: usize,
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPairStreamSeeds, CryptoError> {
    let low = builder6388f0_row0_low_seed_preimages_from_entry_source(entry_source, t)?;
    let resolved_static_scalar_window = if static_scalar_window.is_empty() {
        builder633fa8_static_scalar_window_from_entry_source(entry_source, t)?
    } else {
        static_scalar_window.to_vec()
    };
    builder6388f0_first_pair_stream_seeds_from5bcf98_outputs(
        &low.out4,
        &low.out3,
        &low.out2,
        row0_first_output70,
        row0_second_output70,
        row59_first_output70,
        row59_second_output70,
        null_scalar_window,
        &resolved_static_scalar_window,
        null_entropy_11a,
        null_attempts,
        x1_source,
        x2_source,
        scalar,
        t,
    )
}

/// kit `builder6388f0FirstPairStreamSeedsFromEntropyAnd5bcf98Outputs`
/// (FirstPairSourceSlice.swift L2145-2170): the null scalar window derives from the given
/// null entropy (attempt count fixed at 1, mirroring the kit).
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_first_pair_stream_seeds_from_entropy_and5bcf98_outputs(
    entry_source: &[u8],
    row0_first_output70: &[u8],
    row0_second_output70: &[u8],
    row59_first_output70: &[u8],
    row59_second_output70: &[u8],
    null_entropy_11a: &[u8],
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPairStreamSeeds, CryptoError> {
    let null_scalar = builder633fa8_null_scalar_window_from_entropy(null_entropy_11a, t)?;
    builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs(
        entry_source,
        row0_first_output70,
        row0_second_output70,
        row59_first_output70,
        row59_second_output70,
        &null_scalar,
        &[],
        null_entropy_11a,
        1,
        x1_source,
        x2_source,
        scalar,
        t,
    )
}

/// kit `builder6388f0FirstPairStreamSeedsFromEntropySourceAnd5bcf98Outputs`
/// (FirstPairSourceSlice.swift L2172-2201): the retrying null-entropy wrapper feeding the
/// entry-source composition.
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_first_pair_stream_seeds_from_entropy_source_and5bcf98_outputs<F>(
    entry_source: &[u8],
    row0_first_output70: &[u8],
    row0_second_output70: &[u8],
    row59_first_output70: &[u8],
    row59_second_output70: &[u8],
    max_attempts: usize,
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    entropy_source: F,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPairStreamSeeds, CryptoError>
where
    F: FnMut(usize) -> Result<Vec<u8>, CryptoError>,
{
    let null_result =
        builder633fa8_null_scalar_window_from_entropy_source(max_attempts, entropy_source, t)?;
    builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs(
        entry_source,
        row0_first_output70,
        row0_second_output70,
        row59_first_output70,
        row59_second_output70,
        &null_result.scalar_window,
        &[],
        &null_result.entropy11a,
        null_result.attempts,
        x1_source,
        x2_source,
        scalar,
        t,
    )
}

// ---------------------------------------------------------------- derivation

/// kit `deriveFrom6388f0SeededCaller64Rows(rows:src4:offset:length:)` (FirstPairSourceSlice.swift
/// L8299-8313): the default `pre63c278Arg0Source` / `pre63c278Scalar` overload.
pub fn derive_from_6388f0_seeded_caller64_rows(
    rows: &[Builder6388f0SeededCaller64Row],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    derive_from_6388f0_seeded_caller64_rows_arg0_scalar(
        rows,
        &PRE63C278_ARG0_SOURCE,
        PRE63C278_SCALAR,
        src4,
        offset,
        length,
        t,
    )
}

/// kit `deriveFrom6388f0SeededCaller64Rows(rows:arg0:scalar:src4:offset:length:)`
/// (FirstPairSourceSlice.swift L8315-8335).
pub fn derive_from_6388f0_seeded_caller64_rows_arg0_scalar(
    rows: &[Builder6388f0SeededCaller64Row],
    arg0: &[u8],
    scalar: u64,
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let schedules = builder6388f0_seeded63c278_schedules_from_rows_arg0_scalar(rows, arg0, scalar, t)?;
    super::schedule::derive_from_6388f0_schedule_len32_streams(
        &schedules.first.schedule_words,
        &schedules.second.schedule_words,
        src4,
        offset,
        length,
        t,
    )
}

/// kit `deriveFrom6388f0FirstPairStreamSeeds(seeds:src4:offset:length:)` (FirstPairSourceSlice.swift
/// L8337-8351): the default `pre63c278Arg0Source` / `pre63c278Scalar` overload.
pub fn derive_from_6388f0_first_pair_stream_seeds(
    seeds: &Builder6388f0FirstPairStreamSeeds,
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    derive_from_6388f0_first_pair_stream_seeds_arg0_scalar(
        seeds,
        &PRE63C278_ARG0_SOURCE,
        PRE63C278_SCALAR,
        src4,
        offset,
        length,
        t,
    )
}

/// kit `deriveFrom6388f0FirstPairStreamSeeds(seeds:arg0:scalar:src4:offset:length:)`
/// (FirstPairSourceSlice.swift L8353-8370).
pub fn derive_from_6388f0_first_pair_stream_seeds_arg0_scalar(
    seeds: &Builder6388f0FirstPairStreamSeeds,
    arg0: &[u8],
    scalar: u64,
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let rows = builder6388f0_seeded_caller64_rows_from_first_pair_stream_seeds(
        seeds,
        None,
        BUILDER6388F0_FIRST_PAIR_STREAM_ROWS,
        None,
        t,
    )?;
    derive_from_6388f0_seeded_caller64_rows_arg0_scalar(&rows, arg0, scalar, src4, offset, length, t)
}

/// kit `deriveFrom6388f0FirstPairEntropyAndSensorPoints` (FirstPairSourceSlice.swift L8372-8393).
#[allow(clippy::too_many_arguments)]
pub fn derive_from_6388f0_first_pair_entropy_and_sensor_points(
    entry_source: &[u8],
    null_entropy_11a: &[u8],
    row0_sensor_point_xy_be: &[u8],
    row59_sensor_point_xy_be: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let seeds = builder6388f0_first_pair_stream_seeds_from_entropy_and_sensor_points(
        entry_source,
        null_entropy_11a,
        row0_sensor_point_xy_be,
        row59_sensor_point_xy_be,
        None,
        None,
        None,
        t,
    )?;
    derive_from_6388f0_first_pair_stream_seeds(&seeds, src4, offset, length, t)
}

/// kit `deriveFrom6388f0FirstPairEntropySourceAndSensorPoints` (FirstPairSourceSlice.swift
/// L8395-8418).
#[allow(clippy::too_many_arguments)]
pub fn derive_from_6388f0_first_pair_entropy_source_and_sensor_points<F>(
    entry_source: &[u8],
    row0_sensor_point_xy_be: &[u8],
    row59_sensor_point_xy_be: &[u8],
    max_attempts: usize,
    src4: &[u8],
    offset: usize,
    length: usize,
    entropy_source: F,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError>
where
    F: FnMut(usize) -> Result<Vec<u8>, CryptoError>,
{
    let seeds = builder6388f0_first_pair_stream_seeds_from_entropy_source_and_sensor_points(
        entry_source,
        row0_sensor_point_xy_be,
        row59_sensor_point_xy_be,
        max_attempts,
        None,
        None,
        None,
        entropy_source,
        t,
    )?;
    derive_from_6388f0_first_pair_stream_seeds(&seeds, src4, offset, length, t)
}

// ---------------------------------------------------------------- phase5 raw-key wrappers
// (FirstPairSourceSlice.swift L8868-8939.) These are the kit's phase5 entry points over the
// derivation above; ported with this stage because the golden vectors assert on them directly
// (FirstPairSourceSliceTests.swift L2791, L2826, L1062).

/// kit `phase5RawKeyFrom6388f0SeededCaller64Rows(rows:offset:)` (FirstPairSourceSlice.swift
/// L8868-8878).
pub fn phase5_raw_key_from_6388f0_seeded_caller64_rows(
    rows: &[Builder6388f0SeededCaller64Row],
    t: &FirstPairTables,
    sched: &crate::phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_6388f0_seeded_caller64_rows(rows, &[0, 0, 0, 1], 0, 0x10, t)?;
    crate::phase5::derive_raw_key(&source, sched)
}

/// kit `phase5RawKeyFrom6388f0FirstPairStreamSeeds(seeds:offset:)` (FirstPairSourceSlice.swift
/// L8896-8906).
pub fn phase5_raw_key_from_6388f0_first_pair_stream_seeds(
    seeds: &Builder6388f0FirstPairStreamSeeds,
    t: &FirstPairTables,
    sched: &crate::phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_6388f0_first_pair_stream_seeds(seeds, &[0, 0, 0, 1], 0, 0x10, t)?;
    crate::phase5::derive_raw_key(&source, sched)
}

/// kit `phase5RawKeyFrom6388f0FirstPairEntropyAndSensorPoints` (FirstPairSourceSlice.swift
/// L8924-8940).
pub fn phase5_raw_key_from_6388f0_first_pair_entropy_and_sensor_points(
    entry_source: &[u8],
    null_entropy_11a: &[u8],
    row0_sensor_point_xy_be: &[u8],
    row59_sensor_point_xy_be: &[u8],
    t: &FirstPairTables,
    sched: &crate::phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_6388f0_first_pair_entropy_and_sensor_points(
        entry_source,
        null_entropy_11a,
        row0_sensor_point_xy_be,
        row59_sensor_point_xy_be,
        &[0, 0, 0, 1],
        0,
        0x10,
        t,
    )?;
    crate::phase5::derive_raw_key(&source, sched)
}
