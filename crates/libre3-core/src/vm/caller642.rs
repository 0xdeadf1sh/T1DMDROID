//! FirstPairSourceSlice — the 642f60 caller layer (kit: FirstPairSourceSlice.swift).
//! Stage C of the 6388f0 first-pair builder port: the eight 64bd0c round trips that turn
//! the next-row 642f60 inputs (x0, x1, x2) and the caller context into the out0/out1/out2
//! 88-byte row results — the stage word builders, the six 64bd0c workspaces, the mid and
//! seventh stream stacks, and the output assembly over the bundled shared context.
//!
//! Golden vectors: tests/firstpair_642f60.rs (ported 1:1 from FirstPairSourceSliceTests.swift).

use super::firstpair::{read_u32_le, read_u64_le, require, slice_err};
use super::highseed::{fold63c278_first_nibble_before_add, prefix_sums_u64, range_sum_from_prefix};
use super::schedule::{
    fold32_by_nibbles_63c278, fold_63c278, fold_table_u32_word_63c278, fold_table_u64_word_63c278,
    u32_affine_63c278, u32_table_affine_63c278, u32_table_word_63c278, VEC_BYTES, VEC_WORDS,
};
use super::tables::FirstPairTables;
use crate::CryptoError;

// ---------------------------------------------------------------- 63c278 table constants
// (FirstPairSourceSlice.swift L14045-14166; u32/fold table offsets are absolute addresses
// inside firstpair_63c278_u32_tables_112588 / firstpair_63c278_fold_tables_2feb18.)

const BUILDER642F60_SP2A8_X1_MUL_TABLE: usize = 0x116808; // L14045
const BUILDER642F60_SP2A8_X1_ADD_TABLE: usize = 0x113f48; // L14046
const BUILDER642F60_SP2A8_OUT_MUL_TABLE: usize = 0x11b2e8; // L14047
const BUILDER642F60_SP2A8_OUT_ADD_TABLE: usize = 0x11e468; // L14048
const BUILDER642F60_SP2A8_FOLD_TABLE: usize = 0x2feef0; // L14049
const BUILDER642F60_SP300_MUL_TABLE: usize = 0x114808; // L14050
const BUILDER642F60_SP300_ADD_TABLE: usize = 0x112d48; // L14051
const BUILDER642F60_SP1F8_X0_MUL_TABLE: usize = 0x122168; // L14052
const BUILDER642F60_SP1F8_X0_ADD_TABLE: usize = 0x1171c8; // L14053
const BUILDER642F60_SP1F8_OUT_MUL_TABLE: usize = 0x112d68; // L14054
const BUILDER642F60_SP1F8_OUT_ADD_TABLE: usize = 0x115368; // L14055
const BUILDER642F60_SP1F8_FOLD_TABLE: usize = 0x2ff2b0; // L14056
const BUILDER642F60_SP250_MUL_TABLE: usize = 0x118e28; // L14057
const BUILDER642F60_SP250_ADD_TABLE: usize = 0x11b368; // L14058
const BUILDER642F60_SP148_MUL_TABLE: usize = 0x113668; // L14059
const BUILDER642F60_SP148_ADD_TABLE: usize = 0x113f68; // L14060
const BUILDER642F60_SPF0_MUL_TABLE: usize = 0x116828; // L14061
const BUILDER642F60_SPF0_ADD_TABLE: usize = 0x1233c8; // L14062
const BUILDER642F60_SP1A0_MUL_TABLE: usize = 0x117ac8; // L14063
const BUILDER642F60_SP1A0_ADD_TABLE: usize = 0x120568; // L14064
const BUILDER642F60_FIRST_A_MUL_TABLE: usize = 0x11b308; // L14065
const BUILDER642F60_FIRST_A_ADD_TABLE: usize = 0x120e68; // L14066
const BUILDER642F60_FIRST_A_FOLD_TABLE: usize = 0x2fef30; // L14067
const BUILDER642F60_FIRST_B_MUL_TABLE: usize = 0x117a88; // L14068
const BUILDER642F60_FIRST_B_ADD_TABLE: usize = 0x11c308; // L14069
const BUILDER642F60_FIRST_B_FOLD_TABLE: usize = 0x2fefb0; // L14070
const BUILDER642F60_SECOND_A_MUL_TABLE: usize = 0x115388; // L14071
const BUILDER642F60_SECOND_A_ADD_TABLE: usize = 0x1153a8; // L14072
const BUILDER642F60_SECOND_A_FOLD_TABLE: usize = 0x2ff2f0; // L14073
const BUILDER642F60_SECOND_B_MUL_TABLE: usize = 0x11dc48; // L14074
const BUILDER642F60_SECOND_B_ADD_TABLE: usize = 0x11b348; // L14075
const BUILDER642F60_SECOND_B_FOLD_TABLE: usize = 0x2ff370; // L14076
const BUILDER642F60_THIRD_A_MUL_TABLE: usize = 0x117aa8; // L14077
const BUILDER642F60_THIRD_A_ADD_TABLE: usize = 0x11a8c8; // L14078
const BUILDER642F60_THIRD_A_FOLD_TABLE: usize = 0x2ff3f0; // L14079
const BUILDER642F60_THIRD_B_MUL_TABLE: usize = 0x11e488; // L14080
const BUILDER642F60_THIRD_B_ADD_TABLE: usize = 0x11e4a8; // L14081
const BUILDER642F60_THIRD_B_FOLD_TABLE: usize = 0x2ff470; // L14082
const BUILDER642F60_FOURTH_A_MUL_TABLE: usize = 0x112588; // L14083
const BUILDER642F60_FOURTH_A_ADD_TABLE: usize = 0x123cc8; // L14084
const BUILDER642F60_FOURTH_A_FOLD_TABLE: usize = 0x2ff4f0; // L14085
const BUILDER642F60_FOURTH_B_MUL_TABLE: usize = 0x115e68; // L14086
const BUILDER642F60_FOURTH_B_ADD_TABLE: usize = 0x1125a8; // L14087
const BUILDER642F60_FOURTH_B_FOLD_TABLE: usize = 0x2ff570; // L14088
const BUILDER642F60_MID_A_X0_MUL_TABLE: usize = 0x114828; // L14089
const BUILDER642F60_MID_A_X0_ADD_TABLE: usize = 0x118e48; // L14090
const BUILDER642F60_MID_A_FOLD_TABLE: usize = 0x2ff5f0; // L14091
const BUILDER642F60_MID_SP40_FOLD_TABLE: usize = 0x2ff670; // L14092
const BUILDER642F60_MID_SP40_OUT_MUL_TABLE: usize = 0x116848; // L14093
const BUILDER642F60_MID_SP40_OUT_ADD_TABLE: usize = 0x123ce8; // L14094
const BUILDER642F60_MID_CONTEXT_MUL_TABLE: usize = 0x1183c8; // L14095
const BUILDER642F60_MID_CONTEXT_ADD_TABLE: usize = 0x1171e8; // L14096
const BUILDER642F60_MID_CONTEXT_FOLD_TABLE: usize = 0x2ff6f0; // L14097
const BUILDER642F60_MID_SPF0_MUL_TABLE: usize = 0x121828; // L14098
const BUILDER642F60_MID_SPF0_ADD_TABLE: usize = 0x114848; // L14099
const BUILDER642F60_MID_SPF0_FOLD_TABLE: usize = 0x2ff770; // L14100
const BUILDER642F60_MID_SP40_B_MUL_TABLE: usize = 0x121848; // L14101
const BUILDER642F60_MID_SP40_B_ADD_TABLE: usize = 0x113688; // L14102
const BUILDER642F60_MID_SP40_B_FOLD_TABLE: usize = 0x2ff7f0; // L14103
const BUILDER642F60_MID_STATIC_SRC_TABLE: usize = 0x2fee98; // L14104
const BUILDER642F60_MID_STATIC_MUL_TABLE: usize = 0x11b388; // L14105
const BUILDER642F60_MID_STATIC_ADD_TABLE: usize = 0x1233e8; // L14106
const BUILDER642F60_MID_STATIC_FOLD_TABLE: usize = 0x2ff870; // L14107
const BUILDER642F60_SIXTH_A_MUL_TABLE: usize = 0x115e88; // L14108
const BUILDER642F60_SIXTH_A_ADD_TABLE: usize = 0x11cd48; // L14109
const BUILDER642F60_SIXTH_A_FOLD_TABLE: usize = 0x2ff8f0; // L14110
const BUILDER642F60_SIXTH_B_MUL_TABLE: usize = 0x123d08; // L14111
const BUILDER642F60_SIXTH_B_ADD_TABLE: usize = 0x122188; // L14112
const BUILDER642F60_SIXTH_B_FOLD_TABLE: usize = 0x2ff970; // L14113
const BUILDER642F60_OUT0_STATIC_Q0: usize = 0x125450; // L14114
const BUILDER642F60_OUT0_STATIC_Q1: usize = 0x126540; // L14115
const BUILDER642F60_OUT0_STATIC_D1: usize = 0x126900; // L14116
const BUILDER642F60_OUT0_SIXTH_MUL_TABLE: usize = 0x1229e8; // L14117
const BUILDER642F60_OUT0_CONTEXT_MUL_TABLE: usize = 0x123d28; // L14118
const BUILDER642F60_OUT0_SP250_MUL_TABLE: usize = 0x1183e8; // L14119
const BUILDER642F60_OUT0_FOLD_TABLE: usize = 0x2ff9f0; // L14120
const BUILDER642F60_OUT0_OUT_MUL_TABLE: usize = 0x11d428; // L14121
const BUILDER642F60_OUT0_OUT_ADD_TABLE: usize = 0x120e88; // L14122
const BUILDER642F60_SEVENTH_STATIC_Q0: usize = 0x125380; // L14123
const BUILDER642F60_SEVENTH_STATIC_Q1: usize = 0x1256c0; // L14124
const BUILDER642F60_SEVENTH_STATIC_D1: usize = 0x126c70; // L14125
const BUILDER642F60_SEVENTH_SP250_MUL_TABLE: usize = 0x11d448; // L14126
const BUILDER642F60_SEVENTH_CONTEXT_MUL_TABLE: usize = 0x117ae8; // L14127
const BUILDER642F60_SEVENTH_OUT0_MUL_TABLE: usize = 0x11cd68; // L14128
const BUILDER642F60_SEVENTH_SP148_FOLD_TABLE: usize = 0x2ffa30; // L14129
const BUILDER642F60_SEVENTH_SP148_OUT_MUL_TABLE: usize = 0x1153c8; // L14130
const BUILDER642F60_SEVENTH_SP148_OUT_ADD_TABLE: usize = 0x11dc68; // L14131
const BUILDER642F60_SEVENTH_A_MUL_TABLE: usize = 0x112d88; // L14132
const BUILDER642F60_SEVENTH_A_ADD_TABLE: usize = 0x1153e8; // L14133
const BUILDER642F60_SEVENTH_A_FOLD_TABLE: usize = 0x2ffa70; // L14134
const BUILDER642F60_SEVENTH_B_MUL_TABLE: usize = 0x11a8e8; // L14135
const BUILDER642F60_SEVENTH_B_ADD_TABLE: usize = 0x1136a8; // L14136
const BUILDER642F60_SEVENTH_B_FOLD_TABLE: usize = 0x2ffaf0; // L14137
const BUILDER642F60_SEVENTH_SP9E0_FOLD_TABLE: usize = 0x2ffb70; // L14138
const BUILDER642F60_SEVENTH_SP9E0_OUT_MUL_TABLE: usize = 0x123d48; // L14139
const BUILDER642F60_SEVENTH_SP9E0_OUT_ADD_TABLE: usize = 0x123d68; // L14140
const BUILDER642F60_SEVENTH_SP300_MUL_TABLE: usize = 0x11eba8; // L14141
const BUILDER642F60_SEVENTH_SP300_ADD_TABLE: usize = 0x11e4c8; // L14142
const BUILDER642F60_SEVENTH_SP300_FOLD_TABLE: usize = 0x2ffbf0; // L14143
const BUILDER642F60_SEVENTH_SP7D0_FOLD_TABLE: usize = 0x2ffc70; // L14144
const BUILDER642F60_SEVENTH_SP7D0_OUT_MUL_TABLE: usize = 0x114868; // L14145
const BUILDER642F60_SEVENTH_SP7D0_OUT_ADD_TABLE: usize = 0x122a08; // L14146
const BUILDER642F60_SEVENTH_SOURCE_STATIC_TABLE: usize = 0x118408; // L14147
const BUILDER642F60_SEVENTH_SOURCE_SP9E0_MUL_TABLE: usize = 0x11f528; // L14148
const BUILDER642F60_SEVENTH_SOURCE_CONTEXT368_MUL_TABLE: usize = 0x120ea8; // L14149
const BUILDER642F60_SEVENTH_SOURCE_SP7D0_MUL_TABLE: usize = 0x123408; // L14150
const BUILDER642F60_SEVENTH_SP40_FOLD_TABLE: usize = 0x2ffcf0; // L14151
const BUILDER642F60_SEVENTH_SP40_OUT_MUL_TABLE: usize = 0x11bb68; // L14152
const BUILDER642F60_SEVENTH_SP40_OUT_ADD_TABLE: usize = 0x1196a8; // L14153
const BUILDER642F60_SEVENTH_WORKSPACE_MUL_TABLE: usize = 0x123d88; // L14154
const BUILDER642F60_SEVENTH_WORKSPACE_ADD_TABLE: usize = 0x113f88; // L14155
const BUILDER642F60_SEVENTH_WORKSPACE_FOLD_TABLE: usize = 0x2ffd30; // L14156
const BUILDER642F60_OUT1_MUL_TABLE: usize = 0x11ebc8; // L14157
const BUILDER642F60_OUT1_ADD_TABLE: usize = 0x11e4e8; // L14158
const BUILDER642F60_EIGHTH_A_MUL_TABLE: usize = 0x11a908; // L14159
const BUILDER642F60_EIGHTH_A_ADD_TABLE: usize = 0x116868; // L14160
const BUILDER642F60_EIGHTH_A_FOLD_TABLE: usize = 0x2ffdb0; // L14161
const BUILDER642F60_EIGHTH_B_MUL_TABLE: usize = 0x115ea8; // L14162
const BUILDER642F60_EIGHTH_B_ADD_TABLE: usize = 0x118e68; // L14163
const BUILDER642F60_EIGHTH_B_FOLD_TABLE: usize = 0x2ffe30; // L14164
const BUILDER642F60_OUT2_MUL_TABLE: usize = 0x119f48; // L14165
const BUILDER642F60_OUT2_ADD_TABLE: usize = 0x119f68; // L14166

// ---------------------------------------------------------------- builder sizing constants
// (FirstPairSourceSlice.swift L14167-14179.)

pub(crate) const BUILDER64BD0C_WORKSPACE_WORDS: usize = 44; // L14167
pub(crate) const BUILDER64BD0C_WORKSPACE_BYTES: usize = BUILDER64BD0C_WORKSPACE_WORDS * 8; // L14168
const BUILDER64BD0C_ARG0_MUL_TABLE: usize = 0x11c328; // L14171
const BUILDER64BD0C_ARG0_ADD_TABLE: usize = 0x113648; // L14172
const BUILDER64BD0C_ARG0_FOLD_TABLE: usize = 0x2ff030; // L14173
const BUILDER64BD0C_WORKSPACE_FOLD1_TABLE: usize = 0x2ff0b0; // L14174
const BUILDER64BD0C_REWRITE_FOLD1_TABLE: usize = 0x2ff130; // L14175
const BUILDER64BD0C_REWRITE_FOLD2_TABLE: usize = 0x2ff1b0; // L14176
const BUILDER64BD0C_FINAL_FOLD_TABLE: usize = 0x2ff230; // L14177
const BUILDER64BD0C_FINAL_OUT_MUL_TABLE: usize = 0x11b328; // L14178
const BUILDER64BD0C_FINAL_OUT_ADD_TABLE: usize = 0x115348; // L14179

// ---------------------------------------------------------------- structs

/// kit `Builder642f60Result` (FirstPairSourceSlice.swift L120-130).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder642f60Result {
    pub out0: Vec<u8>,
    pub out1: Vec<u8>,
    pub out2: Vec<u8>,
}

/// kit `builder642f60MidStageStreamsFromContextSPF0` return tuple (L4196-4228).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder642f60MidStageStreams {
    pub spa90_words: Vec<u64>,
    pub sp510_prefix: Vec<u64>,
    pub sp880_words: Vec<u64>,
    pub sp9e0_prefix: Vec<u64>,
}

/// kit `builder642f60MidStageSPA90SP880FromSP40` return tuple (L4273-4292).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder642f60MidSp40B {
    pub spa90_words: Vec<u64>,
    pub sp880_prefix: Vec<u64>,
    pub side_init: u64,
}

/// kit `builder642f60MidStageStaticSP9E0SP7D0` return tuple (L4294-4325).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder642f60MidStatic {
    pub sp9e0_words: Vec<u64>,
    pub sp7d0_prefix: Vec<u64>,
}

/// kit `builder642f60SixthStreamsFromSP1A0` return tuple (L4391-4415).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder642f60SixthStreams {
    pub spa90_words: Vec<u64>,
    pub sp670_prefix: Vec<u64>,
    pub sp880_words: Vec<u64>,
    pub sp510_prefix: Vec<u64>,
}

/// kit `builder642f60SeventhStreams` return tuple (L4625-4681).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder642f60SeventhStreams {
    pub sp670_words: Vec<u64>,
    pub spa90_prefix: Vec<u64>,
    pub sp510_words: Vec<u64>,
    pub sp880_prefix: Vec<u64>,
}

/// kit `builder642f60EighthStreams` return tuple (L5000-5032).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder642f60EighthStreams {
    pub spa90_words: Vec<u64>,
    pub sp670_prefix: Vec<u64>,
    pub sp880_words: Vec<u64>,
    pub sp510_prefix: Vec<u64>,
}

// ---------------------------------------------------------------- shared private helpers

pub(crate) fn vec_err(count: usize) -> CryptoError {
    slice_err(format!("63c278 vector word count {count} != {VEC_WORDS}"))
}

/// kit `packUInt32LE` (FirstPairSourceSlice.swift L10513-10520).
pub(crate) fn pack_u32_le(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// kit `convolutionWorkspaceU64` (FirstPairSourceSlice.swift L11645-11683): the generic
/// 22×22 → 44-word u64 convolution over inclusive prefix sums, LE-packed.
pub(crate) struct ConvolutionWorkspaceU64 {
    pub(crate) base_add: u64,
    pub(crate) count_mul: u64,
    pub(crate) product_mul: u64,
    pub(crate) sum_a_mul: u64,
    pub(crate) sum_b_mul: u64,
    pub(crate) final_mul: u64,
    pub(crate) final_add: u64,
}

pub(crate) fn convolution_workspace_u64(
    a_words: &[u64],
    b_words: &[u64],
    c: ConvolutionWorkspaceU64,
) -> Vec<u8> {
    let a_prefix = prefix_sums_u64(a_words);
    let b_prefix = prefix_sums_u64(b_words);
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_BYTES);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let start = index.saturating_sub(VEC_WORDS - 1);
        let end = index.min(VEC_WORDS - 1);
        if start > end {
            out.extend_from_slice(
                &(c.base_add
                    .wrapping_mul(c.final_mul)
                    .wrapping_add(c.final_add))
                .to_le_bytes(),
            );
            continue;
        }
        let mut product_sum: u64 = 0;
        for pos in start..=end {
            product_sum = product_sum
                .wrapping_add(a_words[pos].wrapping_mul(b_words[index - pos]));
        }
        let sum_a = range_sum_from_prefix(&a_prefix, start, end);
        let sum_b = range_sum_from_prefix(&b_prefix, index - end, index - start);
        let mixed = ((end - start + 1) as u64)
            .wrapping_mul(c.count_mul)
            .wrapping_add(c.base_add)
            .wrapping_add(product_sum.wrapping_mul(c.product_mul))
            .wrapping_add(sum_a.wrapping_mul(c.sum_a_mul))
            .wrapping_add(sum_b.wrapping_mul(c.sum_b_mul));
        out.extend_from_slice(&mixed.wrapping_mul(c.final_mul).wrapping_add(c.final_add).to_le_bytes());
    }
    out
}

/// kit `u64StreamWordFromU32Affine` (FirstPairSourceSlice.swift L11083-11109).
#[allow(clippy::too_many_arguments)]
pub(crate) fn u64_stream_word_from_u32_affine(
    word: u32,
    index: usize,
    mul_table: usize,
    add_table: usize,
    u32_mul: u32,
    u32_add: u32,
    fold_mul: u64,
    fold_add: u64,
    fold_table: usize,
    linear_mul: u64,
    folded_mul: u64,
    linear_add: u64,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let affine = u32_affine_63c278(word, index, mul_table, add_table, t)?;
    let w = affine.wrapping_mul(u32_mul).wrapping_add(u32_add);
    let folded = fold_63c278(
        (w as u64).wrapping_mul(fold_mul).wrapping_add(fold_add),
        fold_table,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(linear_mul)
        .wrapping_add(folded.wrapping_mul(folded_mul))
        .wrapping_add(linear_add))
}

/// kit `u32WordsFromTableSegments` (FirstPairSourceSlice.swift L11860-11872). The kit
/// preconditions each segment length to a multiple of 4; the static call sites below are.
pub(crate) fn u32_words_from_table_segments(
    segments: &[(usize, usize)],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    let mut out = Vec::new();
    for &(offset, byte_count) in segments {
        for inner in (0..byte_count).step_by(4) {
            out.push(u32_table_word_63c278(offset + inner, t)?);
        }
    }
    Ok(out)
}

/// kit `builder642f60AffineWordsFrom64bd0cOutput` (FirstPairSourceSlice.swift
/// L10522-10543): per-word indexed affine over a 64bd0c output.
fn builder642f60_affine_words_from64bd0c_output(
    output: &[u8],
    mul_table: usize,
    add_table: usize,
    label: &str,
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    require(output, VEC_BYTES, label)?;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        out.push(u32_affine_63c278(read_u32_le(output, index * 4), index, mul_table, add_table, t)?);
    }
    Ok(out)
}

// ---------------------------------------------------------------- word builders
// (FirstPairSourceSlice.swift L10544-10755 and L11165-11207; the `index == 0` lane and the
// table-affine lane share the fold/linear tail.)

/// kit `builder642f60FirstAWord` (L10544-10566).
fn builder642f60_first_a_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x7a6bdb55).wrapping_add(0x6f457678)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_FIRST_A_MUL_TABLE, BUILDER642F60_FIRST_A_ADD_TABLE, t)?
            .wrapping_mul(0x9935dc8f)
            .wrapping_add(0x8faec549)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x62170eaa882a1aad)
            .wrapping_add(0xfcded5c74336bb62),
        BUILDER642F60_FIRST_A_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x1ae027ac75efae5d)
        .wrapping_add(folded.wrapping_mul(0x6a59778f00000000))
        .wrapping_add(0x272a0fcbb9692010))
}

/// kit `builder642f60FirstBWord` (L10568-10590).
fn builder642f60_first_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x8a0c43a1).wrapping_add(0xe1069988)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_FIRST_B_MUL_TABLE, BUILDER642F60_FIRST_B_ADD_TABLE, t)?
            .wrapping_mul(0x8fce17f9)
            .wrapping_add(0x9aa95d9c)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x91c0e3def121255d)
            .wrapping_add(0x50be110705349aea),
        BUILDER642F60_FIRST_B_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x861960b1d03ace7f)
        .wrapping_add(folded.wrapping_mul(0x7f5bb67500000000))
        .wrapping_add(0x4a2faf413913b4a2))
}

/// kit `builder642f60SecondAWord` (L10592-10614).
fn builder642f60_second_a_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0xcce32bdb).wrapping_add(0x79dae932)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_SECOND_A_MUL_TABLE, BUILDER642F60_SECOND_A_ADD_TABLE, t)?
            .wrapping_mul(0x7bd77a89)
            .wrapping_add(0x0d07fa2a)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x0a0b1df06a7b196d)
            .wrapping_add(0xfd9f62e38b4829f7),
        BUILDER642F60_SECOND_A_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0xb1bf8eba2d4b2a69)
        .wrapping_add(folded.wrapping_mul(0xb6b0ac9300000000))
        .wrapping_add(0x381faa6c090fdcd8))
}

/// kit `builder642f60SecondBWord` (L10616-10638).
fn builder642f60_second_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x9affbc41).wrapping_add(0x96a579e0)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_SECOND_B_MUL_TABLE, BUILDER642F60_SECOND_B_ADD_TABLE, t)?
            .wrapping_mul(0x3749a60d)
            .wrapping_add(0xb803d34c)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0xa5ffca145f08d59b)
            .wrapping_add(0x8ce0b7edd5a16ba2),
        BUILDER642F60_SECOND_B_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0xabe6b5e1333dcc8f)
        .wrapping_add(folded.wrapping_mul(0xfbd091e300000000))
        .wrapping_add(0x1df38d76faeb4ead))
}

/// kit `builder642f60ThirdAWord` (L10640-10662): the A lane with the first-nibble-before-add
/// fold (`fold63c278FirstNibbleBeforeAdd`, L12798-12813).
fn builder642f60_third_a_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x92a36947).wrapping_add(0xab2632fc)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_THIRD_A_MUL_TABLE, BUILDER642F60_THIRD_A_ADD_TABLE, t)?
            .wrapping_mul(0xe77cb783)
            .wrapping_add(0x000f1f23)
    };
    let folded = fold63c278_first_nibble_before_add(
        (w as u64).wrapping_mul(0xaf7f459e89cfb7e5),
        0x5c6139b5f80c5a20,
        BUILDER642F60_THIRD_A_FOLD_TABLE,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x796b8710218eb0c5)
        .wrapping_add(folded.wrapping_mul(0x7224389f00000000))
        .wrapping_add(0x086af43c0726c3a9))
}

/// kit `builder642f60ThirdBWord` (L10664-10686).
fn builder642f60_third_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x032a79c5).wrapping_add(0x8da26e96)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_THIRD_B_MUL_TABLE, BUILDER642F60_THIRD_B_ADD_TABLE, t)?
            .wrapping_mul(0xb13f4189)
            .wrapping_add(0x2b7b0e41)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x3144f0ff41a1df83)
            .wrapping_add(0x3d414cbf18310011),
        BUILDER642F60_THIRD_B_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x861a1f875b2dc69b)
        .wrapping_add(folded.wrapping_mul(0xf0b786f700000000))
        .wrapping_add(0x63b83ae085557472))
}

/// kit `builder642f60FourthAWord` (L10688-10710).
fn builder642f60_fourth_a_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x9a4392db).wrapping_add(0x0d1015ea)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_FOURTH_A_MUL_TABLE, BUILDER642F60_FOURTH_A_ADD_TABLE, t)?
            .wrapping_mul(0x97151be3)
            .wrapping_add(0x70bf5e2b)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0xcf0e32fa8d969f65)
            .wrapping_add(0x61afec1284e66a8c),
        BUILDER642F60_FOURTH_A_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x610ca66bd199f2b5)
        .wrapping_add(folded.wrapping_mul(0xde6166ef00000000))
        .wrapping_add(0x50d31e15d8b1af56))
}

/// kit `builder642f60FourthBWord` (L10712-10734).
fn builder642f60_fourth_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x2ac5e1c1).wrapping_add(0x957be66c)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_FOURTH_B_MUL_TABLE, BUILDER642F60_FOURTH_B_ADD_TABLE, t)?
            .wrapping_mul(0xee64b1f5)
            .wrapping_add(0x5df44367)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x013aed389aef9cd9)
            .wrapping_add(0x9ac1ba0fa43555a1),
        BUILDER642F60_FOURTH_B_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x3ca485be7caa6cf3)
        .wrapping_add(folded.wrapping_mul(0xcec7175500000000))
        .wrapping_add(0x44d63f7b1e64fe52))
}

/// kit `builder642f60MidContextStreamWord` (L10736-10758).
fn builder642f60_mid_context_stream_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0xef8a98c3).wrapping_add(0x5251f797)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_MID_CONTEXT_MUL_TABLE, BUILDER642F60_MID_CONTEXT_ADD_TABLE, t)?
            .wrapping_mul(0x9198bbe1)
            .wrapping_add(0x96d49925)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x10aca1fefeaea819)
            .wrapping_add(0x791f2f89d18f0bcc),
        BUILDER642F60_MID_CONTEXT_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x7e3d39fbe4db207b)
        .wrapping_add(folded.wrapping_mul(0xf948d04d00000000))
        .wrapping_add(0xefba822749ae8302))
}

/// kit `builder642f60MidSPF0StreamWord` (L10760-10782).
fn builder642f60_mid_spf0_stream_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x833f922f).wrapping_add(0xb8f79a5c)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_MID_SPF0_MUL_TABLE, BUILDER642F60_MID_SPF0_ADD_TABLE, t)?
            .wrapping_mul(0xe9ed5087)
            .wrapping_add(0x99a662bc)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x9445eb5f6cc20c37)
            .wrapping_add(0x2e115166fc9d38de),
        BUILDER642F60_MID_SPF0_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x76037a61bba475bd)
        .wrapping_add(folded.wrapping_mul(0x7a18645500000000))
        .wrapping_add(0xeb599af66ebe44f8))
}

/// kit `builder642f60MidSP40BWord` (L10784-10806).
fn builder642f60_mid_sp40_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x68f1c9c3).wrapping_add(0x75e3de3d)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_MID_SP40_B_MUL_TABLE, BUILDER642F60_MID_SP40_B_ADD_TABLE, t)?
            .wrapping_mul(0x603eaaa7)
            .wrapping_add(0xf3704eb8)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x339d03216c178183)
            .wrapping_add(0xccccddb48073e82d),
        BUILDER642F60_MID_SP40_B_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0x6255799ade203b13)
        .wrapping_add(folded.wrapping_mul(0x504804cf00000000))
        .wrapping_add(0xece1b0fccff7a5d6))
}

/// kit `builder642f60SixthAWord` (L10808-10830).
fn builder642f60_sixth_a_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0xe12f8e63).wrapping_add(0xed30a70d)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_SIXTH_A_MUL_TABLE, BUILDER642F60_SIXTH_A_ADD_TABLE, t)?
            .wrapping_mul(0x61e5762b)
            .wrapping_add(0xd79521cb)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x5ebafd23d4800453)
            .wrapping_add(0xfce166cf66e4ed89),
        BUILDER642F60_SIXTH_A_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0xfe6b40e82ac2cfad)
        .wrapping_add(folded.wrapping_mul(0x4b28a40100000000))
        .wrapping_add(0x5ffded7fc281e70c))
}

/// kit `builder642f60SixthBWord` (L10832-10854).
fn builder642f60_sixth_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x5f8eb06b).wrapping_add(0x71dd9075)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_SIXTH_B_MUL_TABLE, BUILDER642F60_SIXTH_B_ADD_TABLE, t)?
            .wrapping_mul(0x32ca6d69)
            .wrapping_add(0x5b73a719)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0xf82a21269cc1d1db)
            .wrapping_add(0xd1172c1561159fb2),
        BUILDER642F60_SIXTH_B_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0xad5daa3cdd561923)
        .wrapping_add(folded.wrapping_mul(0x5a9053a700000000))
        .wrapping_add(0x06054c9125875977))
}

/// kit `builder642f60EighthAWord` (L11165-11186).
fn builder642f60_eighth_a_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0xcbb0f5d5).wrapping_add(0xbc0ef378)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_EIGHTH_A_MUL_TABLE, BUILDER642F60_EIGHTH_A_ADD_TABLE, t)?
            .wrapping_mul(0xf4ade1bb)
            .wrapping_add(0x14498d6f)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x9e47779cb45c572f)
            .wrapping_add(0x4d028e31657373f8),
        BUILDER642F60_EIGHTH_A_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0xa08be5a120f8c447)
        .wrapping_add(folded.wrapping_mul(0xc729619700000000))
        .wrapping_add(0x6e392c9a885df52c))
}

/// kit `builder642f60EighthBWord` (L11189-11210).
fn builder642f60_eighth_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w: u32 = if index == 0 {
        word.wrapping_mul(0x667888b5).wrapping_add(0x8f0d98ae)
    } else {
        u32_affine_63c278(word, index, BUILDER642F60_EIGHTH_B_MUL_TABLE, BUILDER642F60_EIGHTH_B_ADD_TABLE, t)?
            .wrapping_mul(0xea2a6db9)
            .wrapping_add(0x0a1fb246)
    };
    let folded = fold_63c278(
        (w as u64)
            .wrapping_mul(0x5642541b8c3e3bb7)
            .wrapping_add(0x9965e0d235e6c59b),
        BUILDER642F60_EIGHTH_B_FOLD_TABLE,
        8,
        t,
    )?;
    Ok((w as u64)
        .wrapping_mul(0xb84f64edab558edd)
        .wrapping_add(folded.wrapping_mul(0x82850df500000000))
        .wrapping_add(0xe5d3a90393662e86))
}

// ---------------------------------------------------------------- 64bd0c primitives
// (FirstPairSourceSlice.swift L5258-5374 and private helpers L10340-10393.)

/// kit `builder64bd0cArg0U64Words` (L5258-5288).
pub fn builder64bd0c_arg0_u64_words(arg0: &[u8], t: &FirstPairTables) -> Result<Vec<u64>, CryptoError> {
    require(arg0, VEC_BYTES, "64bd0c arg0")?;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let affine = u32_affine_63c278(
            read_u32_le(arg0, index * 4),
            index,
            BUILDER64BD0C_ARG0_MUL_TABLE,
            BUILDER64BD0C_ARG0_ADD_TABLE,
            t,
        )?;
        let word = affine.wrapping_mul(0x3e251f3f).wrapping_add(0xc80f68f4);
        let folded = fold_63c278(
            (word as u64)
                .wrapping_mul(0xf636dda3668409f3)
                .wrapping_add(0xa1898a9b9b0c347b),
            BUILDER64BD0C_ARG0_FOLD_TABLE,
            8,
            t,
        )?;
        out.push(
            (word as u64)
                .wrapping_mul(0x57c9f2b4caac6659)
                .wrapping_add(folded.wrapping_mul(0xa43bca7d00000000))
                .wrapping_add(0x6de2d7b43700ac09),
        );
    }
    Ok(out)
}

/// kit `builder64bd0cWorkspaceParams` (L10340-10354): (multiplier, broadcast).
fn builder64bd0c_workspace_params(
    scalar: u64,
    first_x2_word: u64,
    t: &FirstPairTables,
) -> Result<(u64, u64), CryptoError> {
    let seed_a = scalar
        .wrapping_mul(0x9cbd06d772de1901)
        .wrapping_add(0x34e214bca24f560c);
    let seed_b = scalar
        .wrapping_mul(0xbe4812554b30ebf8)
        .wrapping_add(0xc770490f6d646597);
    let mut mixed = seed_a.wrapping_mul(first_x2_word).wrapping_add(seed_b);
    let mut folded = mixed
        .wrapping_mul(0x213ec1d8d1bc2d9b)
        .wrapping_add(0x3cda12a384db6d3b);
    folded = fold_63c278(folded, BUILDER64BD0C_WORKSPACE_FOLD1_TABLE, 7, t)?;
    mixed = mixed
        .wrapping_mul(0x91ab7a47a981923b)
        .wrapping_add(folded.wrapping_mul(0x0ed61381f0000000))
        .wrapping_add(0xab62fec0d215095b);
    Ok((
        mixed.wrapping_mul(0x8493b5edc5e368a1).wrapping_add(0x6f8e182c75ab0bb8),
        mixed.wrapping_mul(0x2698148ddd26a50e).wrapping_add(0x740f3b32b62a7210),
    ))
}

/// kit `builder64bd0cRewriteSecondWord` (L10355-10374).
fn builder64bd0c_rewrite_second_word(
    first: u64,
    second: u64,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let mut folded = first
        .wrapping_mul(0xe121bd3e759b23f3)
        .wrapping_add(0x8c105c11c96e758b);
    folded = fold_63c278(folded, BUILDER64BD0C_REWRITE_FOLD1_TABLE, 7, t)?;
    let mut mixed = folded
        .wrapping_mul(0x4afc5649aee85307)
        .wrapping_add(0xdc13fe8d315ad1a7);
    let mut folded2 = mixed
        .wrapping_mul(0x7c64ef86eb0d2547)
        .wrapping_add(0x95549ebb3b944abe);
    folded2 = fold_63c278(folded2, BUILDER64BD0C_REWRITE_FOLD2_TABLE, 9, t)?;
    mixed = mixed
        .wrapping_mul(0xd9ef08a678eb7ba3)
        .wrapping_add(folded2.wrapping_mul(0x8e62b3b000000000));
    Ok(mixed
        .wrapping_mul(0x3352cbd4c2b4f2ef)
        .wrapping_add(second)
        .wrapping_add(0x793cd011929995d8))
}

/// kit `builder64bd0cWorkspaceAfterUpdate` (L5290-5323).
pub fn builder64bd0c_workspace_after_update(
    arg0_u64_words: &[u64],
    scalar: u64,
    x2_workspace: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if arg0_u64_words.len() != VEC_WORDS {
        return Err(vec_err(arg0_u64_words.len()));
    }
    require(x2_workspace, BUILDER64BD0C_WORKSPACE_BYTES, "64bd0c x2 workspace")?;
    let mut x2_words: Vec<u64> = (0..BUILDER64BD0C_WORKSPACE_WORDS)
        .map(|i| read_u64_le(x2_workspace, i * 8))
        .collect();
    let mut carry_word = x2_words[0];
    for base in 0..VEC_WORDS {
        let (multiplier, broadcast) = builder64bd0c_workspace_params(scalar, carry_word, t)?;
        for (offset, &word) in arg0_u64_words.iter().enumerate() {
            let pos = base + offset;
            x2_words[pos] = x2_words[pos]
                .wrapping_add(broadcast)
                .wrapping_add(word.wrapping_mul(multiplier));
        }
        carry_word = builder64bd0c_rewrite_second_word(x2_words[base], x2_words[base + 1], t)?;
        x2_words[base + 1] = carry_word;
    }
    Ok(x2_words.iter().flat_map(|w| w.to_le_bytes()).collect())
}

/// kit `builder64bd0cFinalFirstFold` (L10375-10393): (nextBase, side, folded).
fn builder64bd0c_final_first_fold(
    value: u64,
    t: &FirstPairTables,
) -> Result<(u64, u32, u64), CryptoError> {
    let mut folded = value
        .wrapping_mul(0xbfeaa39c4f3a2fdf)
        .wrapping_add(0xb07328e69628c835);
    let mut side = (value as u32).wrapping_mul(0x48daeaa5);
    folded = fold_63c278(folded, BUILDER64BD0C_FINAL_FOLD_TABLE, 7, t)?;
    side = side.wrapping_add((folded as u32).wrapping_mul(0x50000000));
    let next_base = folded;
    folded = fold_63c278(folded, BUILDER64BD0C_FINAL_FOLD_TABLE, 1, t)?;
    Ok((next_base, side.wrapping_add(0xdfea4892), folded))
}

/// kit `builder64bd0cFinalU32Words` (L5325-5359).
pub fn builder64bd0c_final_u32_words(x2_workspace: &[u8], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    require(x2_workspace, BUILDER64BD0C_WORKSPACE_BYTES, "64bd0c x2 workspace")?;
    let x2_words: Vec<u64> = (0..BUILDER64BD0C_WORKSPACE_WORDS)
        .map(|i| read_u64_le(x2_workspace, i * 8))
        .collect();
    let mut carry: u64 = 0xa8100bf8a7268389;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let tail_word = x2_words[VEC_WORDS + index];
        let mixed = carry
            .wrapping_mul(0xdc6110b4d93c58f7)
            .wrapping_add(tail_word.wrapping_mul(0x29221b50b5648139));
        let folded_input = mixed.wrapping_add(0x02ea5a475ff009a0);
        let (next_base, side, mut folded) = builder64bd0c_final_first_fold(folded_input, t)?;
        folded = fold_63c278(folded, BUILDER64BD0C_FINAL_FOLD_TABLE, 8, t)?;
        let next_carry = next_base
            .wrapping_mul(0x1323954bb9644419)
            .wrapping_add(folded.wrapping_mul(0x69bbbe7000000000));
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(
            side,
            BUILDER64BD0C_FINAL_OUT_MUL_TABLE + table_offset,
            BUILDER64BD0C_FINAL_OUT_ADD_TABLE + table_offset,
            t,
        )?);
        carry = next_carry.wrapping_add(0x7a8f00bf503f94fb);
    }
    Ok(out)
}

/// kit `builder64bd0cOutputWords` (L5361-5369): the full 64bd0c path.
pub fn builder64bd0c_output_words(
    arg0: &[u8],
    scalar: u64,
    x2_workspace: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    let arg0_words = builder64bd0c_arg0_u64_words(arg0, t)?;
    let updated = builder64bd0c_workspace_after_update(&arg0_words, scalar, x2_workspace, t)?;
    builder64bd0c_final_u32_words(&updated, t)
}

// ---------------------------------------------------------------- stage word builders

/// kit `builder642f60StageSP2A8WordsFromX1` (FirstPairSourceSlice.swift L3855-3892).
pub fn builder642f60_stage_sp2a8_words_from_x1(
    x1_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    require(x1_source, VEC_BYTES, "642f60 x1 source")?;
    let mut state: u32 = 0x373c5287; // kit L3861
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let word = read_u32_le(x1_source, index * 4);
        let mixed = u32_table_affine_63c278(
            word,
            BUILDER642F60_SP2A8_X1_MUL_TABLE + table_offset,
            BUILDER642F60_SP2A8_X1_ADD_TABLE + table_offset,
            t,
        )?
        .wrapping_mul(0x3c4be1d6);
        state = state
            .wrapping_mul(0x92c1f72b)
            .wrapping_add(mixed)
            .wrapping_add(0xb77cdf91);
        let mut folded = state.wrapping_mul(0x52c0ee2f).wrapping_add(0xaec98dcc);
        let side_base = state.wrapping_mul(0x58fd5601);
        folded = fold32_by_nibbles_63c278(folded, BUILDER642F60_SP2A8_FOLD_TABLE, 7, t)?;
        let side = side_base
            .wrapping_add(folded << 28)
            .wrapping_add(0x79c97500);
        let folded8 = fold32_by_nibbles_63c278(folded, BUILDER642F60_SP2A8_FOLD_TABLE, 1, t)?;
        let output = u32_table_affine_63c278(
            side,
            BUILDER642F60_SP2A8_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_SP2A8_OUT_ADD_TABLE + table_offset,
            t,
        )?;
        out.push(output);
        state = folded
            .wrapping_mul(0x01d6d2ed)
            .wrapping_add(folded8.wrapping_mul(0xe292d130))
            .wrapping_add(0x57678c8f);
    }
    Ok(out)
}

/// kit `builder642f60StageSP300WordsFrom64bd0cOutput` (L3894-3901).
pub fn builder642f60_stage_sp300_words_from64bd0c_output(
    output: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    builder642f60_affine_words_from64bd0c_output(
        output,
        BUILDER642F60_SP300_MUL_TABLE,
        BUILDER642F60_SP300_ADD_TABLE,
        "642f60 first 64bd0c output",
        t,
    )
}

/// kit `builder642f60StageSP250WordsFrom64bd0cOutput` (L3903-3910).
pub fn builder642f60_stage_sp250_words_from64bd0c_output(
    output: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    builder642f60_affine_words_from64bd0c_output(
        output,
        BUILDER642F60_SP250_MUL_TABLE,
        BUILDER642F60_SP250_ADD_TABLE,
        "642f60 second 64bd0c output",
        t,
    )
}

/// kit `builder642f60StageSP148WordsFrom64bd0cOutput` (L3912-3919).
pub fn builder642f60_stage_sp148_words_from64bd0c_output(
    output: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    builder642f60_affine_words_from64bd0c_output(
        output,
        BUILDER642F60_SP148_MUL_TABLE,
        BUILDER642F60_SP148_ADD_TABLE,
        "642f60 third 64bd0c output",
        t,
    )
}

/// kit `builder642f60StageSPF0WordsFrom64bd0cOutput` (L3921-3928).
pub fn builder642f60_stage_spf0_words_from64bd0c_output(
    output: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    builder642f60_affine_words_from64bd0c_output(
        output,
        BUILDER642F60_SPF0_MUL_TABLE,
        BUILDER642F60_SPF0_ADD_TABLE,
        "642f60 fourth 64bd0c output",
        t,
    )
}

/// kit `builder642f60StageSP1A0WordsFrom64bd0cOutput` (L3930-3937).
pub fn builder642f60_stage_sp1a0_words_from64bd0c_output(
    output: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    builder642f60_affine_words_from64bd0c_output(
        output,
        BUILDER642F60_SP1A0_MUL_TABLE,
        BUILDER642F60_SP1A0_ADD_TABLE,
        "642f60 fifth 64bd0c output",
        t,
    )
}

/// kit `builder642f60StageSP1F8WordsFromX0` (L3939-3975).
pub fn builder642f60_stage_sp1f8_words_from_x0(
    x0_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    require(x0_source, VEC_BYTES, "642f60 x0 source")?;
    let mut state: u32 = 0x27b40eb7; // kit L3945
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let word = read_u32_le(x0_source, index * 4);
        let mixed = u32_table_affine_63c278(
            word,
            BUILDER642F60_SP1F8_X0_MUL_TABLE + table_offset,
            BUILDER642F60_SP1F8_X0_ADD_TABLE + table_offset,
            t,
        )?
        .wrapping_mul(0x8c0bfb6e);
        state = state
            .wrapping_mul(0xcfb36435)
            .wrapping_add(mixed)
            .wrapping_add(0x11d2681d);
        let mut folded = state.wrapping_mul(0xc337e20f).wrapping_add(0x69960635);
        folded = fold32_by_nibbles_63c278(folded, BUILDER642F60_SP1F8_FOLD_TABLE, 7, t)?;
        let side = state
            .wrapping_mul(0x37e76a4d)
            .wrapping_add(folded.wrapping_mul(0xd0000000))
            .wrapping_add(0x0a2a2ce9);
        let folded8 = fold32_by_nibbles_63c278(folded, BUILDER642F60_SP1F8_FOLD_TABLE, 1, t)?;
        let output = u32_table_affine_63c278(
            side,
            BUILDER642F60_SP1F8_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_SP1F8_OUT_ADD_TABLE + table_offset,
            t,
        )?;
        out.push(output);
        state = folded
            .wrapping_mul(0x01e08913)
            .wrapping_add(folded8.wrapping_mul(0xe1f76ed0))
            .wrapping_add(0xe153bede);
    }
    Ok(out)
}

// ---------------------------------------------------------------- 64bd0c workspace builders

/// kit `builder642f60First64bd0cWorkspaceFromX1` (L3977-4006).
pub fn builder642f60_first64bd0c_workspace_from_x1(
    x1_source: &[u8],
    sp2a8_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    require(x1_source, VEC_BYTES, "642f60 x1 source")?;
    if sp2a8_words.len() != VEC_WORDS {
        return Err(vec_err(sp2a8_words.len()));
    }
    let a_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_first_a_word(read_u32_le(x1_source, index * 4), index, t))
        .collect::<Result<_, _>>()?;
    let b_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_first_b_word(sp2a8_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(convolution_workspace_u64(
        &a_words,
        &b_words,
        ConvolutionWorkspaceU64 {
            base_add: 0xc69bed71f29f125a,
            count_mul: 0x90f419f6ac783668,
            product_mul: 0x4cb8f06bf0049b7d,
            sum_a_mul: 0x0f7eac37b6812618,
            sum_b_mul: 0xd1388a4d4ecb84f3,
            final_mul: 0xdacc0c3ac7084aad,
            final_add: 0x094d3bfe92d4e136,
        },
    ))
}

/// kit `builder642f60Second64bd0cWorkspace` (L4008-4036).
pub fn builder642f60_second64bd0c_workspace(
    sp1f8_words: &[u32],
    sp300_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if sp1f8_words.len() != VEC_WORDS {
        return Err(vec_err(sp1f8_words.len()));
    }
    if sp300_words.len() != VEC_WORDS {
        return Err(vec_err(sp300_words.len()));
    }
    let a_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_second_a_word(sp1f8_words[index], index, t))
        .collect::<Result<_, _>>()?;
    let b_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_second_b_word(sp300_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(convolution_workspace_u64(
        &a_words,
        &b_words,
        ConvolutionWorkspaceU64 {
            base_add: 0x65de471500bb3121,
            count_mul: 0x5b751607ca3bf450,
            product_mul: 0xf90d1f20daf847f7,
            sum_a_mul: 0x3bd2bf8830ac06c7,
            sum_b_mul: 0x8510f1581a89dd50,
            final_mul: 0x95f22cc42a8e1323,
            final_add: 0x54b290ac63e72185,
        },
    ))
}

/// kit `builder642f60Third64bd0cWorkspaceFromX2` (L4038-4064).
pub fn builder642f60_third64bd0c_workspace_from_x2(
    x2_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    require(x2_source, VEC_BYTES, "642f60 x2 source")?;
    let source_words: Vec<u32> = (0..VEC_WORDS).map(|index| read_u32_le(x2_source, index * 4)).collect();
    let a_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_third_a_word(source_words[index], index, t))
        .collect::<Result<_, _>>()?;
    let b_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_third_b_word(source_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(convolution_workspace_u64(
        &a_words,
        &b_words,
        ConvolutionWorkspaceU64 {
            base_add: 0x5b1e432b74fd20f9,
            count_mul: 0xd6a9de8138afb1c4,
            product_mul: 0x491764cf27f996a7,
            sum_a_mul: 0x5f259b9e6d3d894f,
            sum_b_mul: 0x0634d81d5a7a1464,
            final_mul: 0x81b9bc3ed86899db,
            final_add: 0x7f3bdcb4320a4605,
        },
    ))
}

/// kit `builder642f60Fourth64bd0cWorkspace` (L4066-4088).
pub fn builder642f60_fourth64bd0c_workspace(
    sp148_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if sp148_words.len() != VEC_WORDS {
        return Err(vec_err(sp148_words.len()));
    }
    let a_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_fourth_a_word(sp148_words[index], index, t))
        .collect::<Result<_, _>>()?;
    let b_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_fourth_b_word(sp148_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(convolution_workspace_u64(
        &a_words,
        &b_words,
        ConvolutionWorkspaceU64 {
            base_add: 0xca87452057c62cf5,
            count_mul: 0x33f7ea217636a2b0,
            product_mul: 0x25c9902b9655a323,
            sum_a_mul: 0x5486edf9ebf09668,
            sum_b_mul: 0x9ae2908cd350c4ca,
            final_mul: 0xc84690dc7332d8bf,
            final_add: 0x2381c41e82ce093d,
        },
    ))
}

// ---------------------------------------------------------------- mid stages

/// kit `builder642f60MidStageSPA90WordsFromX0` (L4090-4120).
pub fn builder642f60_mid_stage_spa90_words_from_x0(
    x0_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u64>, CryptoError> {
    require(x0_source, VEC_BYTES, "642f60 x0 source")?;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let affine = u32_affine_63c278(
            read_u32_le(x0_source, index * 4),
            index,
            BUILDER642F60_MID_A_X0_MUL_TABLE,
            BUILDER642F60_MID_A_X0_ADD_TABLE,
            t,
        )?;
        let word = affine.wrapping_mul(0x3c1bc237).wrapping_add(0xd6718b75);
        let folded = fold_63c278(
            (word as u64)
                .wrapping_mul(0x4570116131d5875b)
                .wrapping_add(0x4ca880cd5cde550e),
            BUILDER642F60_MID_A_FOLD_TABLE,
            8,
            t,
        )?;
        out.push(
            (word as u64)
                .wrapping_mul(0xc7860ccbc266aa3d)
                .wrapping_add(folded.wrapping_mul(0xbffb9fb900000000))
                .wrapping_add(0x0bfdc66a47f4cadf),
        );
    }
    Ok(out)
}

/// kit `builder642f60MidStageSP40WordsFromSPA90` (L4122-4194).
pub fn builder642f60_mid_stage_sp40_words_from_spa90(
    spa90_words: &[u64],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if spa90_words.len() != VEC_WORDS {
        return Err(vec_err(spa90_words.len()));
    }
    let mut carry: u64 = 0xd2263697af87081f;
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_WORDS);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let mut low = index.saturating_sub(21);
        let mut high = index.min(21);
        let mut accum: u64 = 0x4ca9f4732c4678da;
        while high > low {
            let high_word = spa90_words[high];
            let low_word = spa90_words[low];
            let left = high_word
                .wrapping_mul(0xb2358691225cfc35)
                .wrapping_add(0xdf9a7386fc929cb6);
            accum = left
                .wrapping_mul(low_word)
                .wrapping_add(high_word.wrapping_mul(0xdf9a7386fc929cb6))
                .wrapping_add(accum)
                .wrapping_add(0xe1240ffc79c75054);
            high -= 1;
            low += 1;
        }

        let mixed = accum.wrapping_mul(0x7047539999fd499e);
        carry = carry.wrapping_mul(0x7d2900791bc15f17);
        let state: u64 = if high == low {
            let center = spa90_words[high];
            let center_term = center
                .wrapping_mul(0xe6b5f1d6d357e2db)
                .wrapping_add(0x7888b2a9570a9e54);
            center_term
                .wrapping_mul(center)
                .wrapping_add(mixed)
                .wrapping_add(carry)
                .wrapping_add(0xe771da0c03bd224c)
        } else {
            mixed.wrapping_add(carry).wrapping_add(0xb928102d38c55e60)
        };

        let folded_input = state
            .wrapping_mul(0x93dfdd33afa41fcb)
            .wrapping_add(0xb5028820475851e2);
        let folded7 = fold_63c278(folded_input, BUILDER642F60_MID_SP40_FOLD_TABLE, 7, t)?;
        let folded16 = fold_63c278(folded7, BUILDER642F60_MID_SP40_FOLD_TABLE, 9, t)?;
        let side = (state as u32)
            .wrapping_mul(0x6d4b301f)
            .wrapping_add((folded7 as u32).wrapping_mul(0x30000000))
            .wrapping_add(0xb22b53c3);
        carry = folded7
            .wrapping_mul(0x33b21893aa33e715)
            .wrapping_add(folded16.wrapping_mul(0x5cc18eb000000000))
            .wrapping_add(0xd1af5299bbb3ce82);

        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(
            side,
            BUILDER642F60_MID_SP40_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_MID_SP40_OUT_ADD_TABLE + table_offset,
            t,
        )?);
    }
    Ok(out)
}

/// kit `builder642f60MidStageStreamsFromContextSPF0` (L4196-4228).
pub fn builder642f60_mid_stage_streams_from_context_spf0(
    context_source: &[u8],
    spf0_words: &[u32],
    t: &FirstPairTables,
) -> Result<Builder642f60MidStageStreams, CryptoError> {
    require(context_source, 0x100, "642f60 context source")?;
    if spf0_words.len() != VEC_WORDS {
        return Err(vec_err(spf0_words.len()));
    }
    let context_words: Vec<u32> = (0..VEC_WORDS)
        .map(|index| read_u32_le(context_source, 0xa8 + index * 4))
        .collect();
    let spa90_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_mid_context_stream_word(context_words[index], index, t))
        .collect::<Result<_, _>>()?;
    let sp880_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_mid_spf0_stream_word(spf0_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(Builder642f60MidStageStreams {
        sp510_prefix: prefix_sums_u64(&spa90_words),
        sp9e0_prefix: prefix_sums_u64(&sp880_words),
        spa90_words,
        sp880_words,
    })
}

/// kit `builder642f60MidStageSP670Words` (L4230-4271).
pub fn builder642f60_mid_stage_sp670_words(
    spa90_words: &[u64],
    sp510_prefix: &[u64],
    sp880_words: &[u64],
    sp9e0_prefix: &[u64],
) -> Result<Vec<u64>, CryptoError> {
    if spa90_words.len() != VEC_WORDS {
        return Err(vec_err(spa90_words.len()));
    }
    if sp510_prefix.len() != VEC_WORDS {
        return Err(vec_err(sp510_prefix.len()));
    }
    if sp880_words.len() != VEC_WORDS {
        return Err(vec_err(sp880_words.len()));
    }
    if sp9e0_prefix.len() != VEC_WORDS {
        return Err(vec_err(sp9e0_prefix.len()));
    }
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_WORDS);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let start = index.saturating_sub(21);
        let end = index.min(21);
        let mut product_sum: u64 = 0;
        if start <= end {
            for position in start..=end {
                product_sum = product_sum
                    .wrapping_add(spa90_words[position].wrapping_mul(sp880_words[index - position]));
            }
        }
        let span: u64 = if start <= end { (end - start + 1) as u64 } else { 0 };
        let sum_a = range_sum_from_prefix(sp510_prefix, start, end);
        let sum_b = range_sum_from_prefix(sp9e0_prefix, index - end, index - start);
        let mixed = span
            .wrapping_mul(0x268d985caf171be0)
            .wrapping_add(0x91891dd268ac7a45)
            .wrapping_add(product_sum.wrapping_mul(0xbc643695604233c9))
            .wrapping_add(sum_a.wrapping_mul(0x55d047a51fd1fdd0))
            .wrapping_add(sum_b.wrapping_mul(0x268318c9a7c7fd06));
        out.push(mixed.wrapping_mul(0xc36e55bcdc7360d9).wrapping_add(0x20aeeecb67e4d8ee));
    }
    Ok(out)
}

/// kit `builder642f60MidStageSPA90SP880FromSP40` (L4273-4292).
pub fn builder642f60_mid_stage_spa90_sp880_from_sp40(
    sp40_words: &[u32],
    t: &FirstPairTables,
) -> Result<Builder642f60MidSp40B, CryptoError> {
    if sp40_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(sp40_words.len()));
    }
    let spa90_words: Vec<u64> = (0..BUILDER64BD0C_WORKSPACE_WORDS)
        .map(|index| builder642f60_mid_sp40_b_word(sp40_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(Builder642f60MidSp40B {
        sp880_prefix: prefix_sums_u64(&spa90_words),
        side_init: 0x9b3fe2a5f2a431c6,
        spa90_words,
    })
}

/// kit `builder642f60MidStageStaticSP9E0SP7D0` (L4294-4325).
pub fn builder642f60_mid_stage_static_sp9e0_sp7d0(
    side_init: u64,
    t: &FirstPairTables,
) -> Result<Builder642f60MidStatic, CryptoError> {
    let mut sp9e0_words: Vec<u64> = Vec::with_capacity(VEC_WORDS);
    sp9e0_words.push(side_init);
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let source = fold_table_u32_word_63c278(BUILDER642F60_MID_STATIC_SRC_TABLE + index * 4, t)?;
        let multiplier = u32_table_word_63c278(BUILDER642F60_MID_STATIC_MUL_TABLE + table_offset, t)?;
        let addend = u32_table_word_63c278(BUILDER642F60_MID_STATIC_ADD_TABLE + table_offset, t)?;
        let word = source
            .wrapping_mul(multiplier)
            .wrapping_add(addend)
            .wrapping_mul(0x8e3923f3)
            .wrapping_add(0xdcf87258);
        let folded = fold_63c278(
            (word as u64)
                .wrapping_mul(0x76e0c10d644166b9)
                .wrapping_add(0x9f48a8b2fd92040d),
            BUILDER642F60_MID_STATIC_FOLD_TABLE,
            8,
            t,
        )?;
        sp9e0_words.push(
            (word as u64)
                .wrapping_mul(0x5a02cb2433277ab9)
                .wrapping_add(folded.wrapping_mul(0xedf34bff00000000))
                .wrapping_add(0x6f59c0117d1d1775),
        );
    }
    Ok(Builder642f60MidStatic {
        sp7d0_prefix: prefix_sums_u64(&sp9e0_words),
        sp9e0_words,
    })
}

/// kit `builder642f60MidStageSP510Words` (L4327-4365).
pub fn builder642f60_mid_stage_sp510_words(
    spa90_words: &[u64],
    sp880_prefix: &[u64],
    sp9e0_words: &[u64],
    sp7d0_prefix: &[u64],
) -> Result<Vec<u64>, CryptoError> {
    if spa90_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(spa90_words.len()));
    }
    if sp880_prefix.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(sp880_prefix.len()));
    }
    if sp9e0_words.len() != VEC_WORDS {
        return Err(vec_err(sp9e0_words.len()));
    }
    if sp7d0_prefix.len() != VEC_WORDS {
        return Err(vec_err(sp7d0_prefix.len()));
    }
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_WORDS);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let start = index.saturating_sub(21);
        let end = index;
        let mut product_sum: u64 = 0;
        for position in start..=end {
            product_sum = product_sum
                .wrapping_add(spa90_words[position].wrapping_mul(sp9e0_words[index - position]));
        }
        let span = end - start + 1;
        let sum_a = range_sum_from_prefix(sp880_prefix, start, end);
        let sum_b = sp7d0_prefix[span - 1];
        let mixed = product_sum
            .wrapping_mul(0x29f4a886cd96e34d)
            .wrapping_add((span as u64).wrapping_mul(0xfb27869a34fe306e))
            .wrapping_add(sum_a.wrapping_mul(0x7228cc7a696bf425))
            .wrapping_add(sum_b.wrapping_mul(0x4005e2eb6883e7de));
        out.push(mixed.wrapping_mul(0x10af80ba2ba8ff03).wrapping_add(0x603f2c10b20e1521));
    }
    Ok(out)
}

/// kit `builder642f60MidFifth64bd0cWorkspace` (L4367-4389).
pub fn builder642f60_mid_fifth64bd0c_workspace(
    sp670_words: &[u64],
    sp510_words: &[u64],
) -> Result<Vec<u8>, CryptoError> {
    if sp670_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(sp670_words.len()));
    }
    if sp510_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(sp510_words.len()));
    }
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_BYTES);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let word = sp670_words[index]
            .wrapping_mul(0x311e50313531405d)
            .wrapping_add(sp510_words[index].wrapping_mul(0xc817dbca0a20eafd))
            .wrapping_add(0xc254ca1fa792908c);
        out.extend_from_slice(&word.to_le_bytes());
    }
    Ok(out)
}

/// kit `builder642f60SixthStreamsFromSP1A0` (L4391-4415).
pub fn builder642f60_sixth_streams_from_sp1a0(
    sp1a0_words: &[u32],
    t: &FirstPairTables,
) -> Result<Builder642f60SixthStreams, CryptoError> {
    if sp1a0_words.len() != VEC_WORDS {
        return Err(vec_err(sp1a0_words.len()));
    }
    let spa90_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_sixth_a_word(sp1a0_words[index], index, t))
        .collect::<Result<_, _>>()?;
    let sp880_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_sixth_b_word(sp1a0_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(Builder642f60SixthStreams {
        sp670_prefix: prefix_sums_u64(&spa90_words),
        sp510_prefix: prefix_sums_u64(&sp880_words),
        spa90_words,
        sp880_words,
    })
}

/// kit `builder642f60Sixth64bd0cWorkspace` (L4417-4458).
pub fn builder642f60_sixth64bd0c_workspace(
    spa90_words: &[u64],
    sp670_prefix: &[u64],
    sp880_words: &[u64],
    sp510_prefix: &[u64],
) -> Result<Vec<u8>, CryptoError> {
    if spa90_words.len() != VEC_WORDS {
        return Err(vec_err(spa90_words.len()));
    }
    if sp670_prefix.len() != VEC_WORDS {
        return Err(vec_err(sp670_prefix.len()));
    }
    if sp880_words.len() != VEC_WORDS {
        return Err(vec_err(sp880_words.len()));
    }
    if sp510_prefix.len() != VEC_WORDS {
        return Err(vec_err(sp510_prefix.len()));
    }
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_BYTES);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let start = index.saturating_sub(21);
        let end = index.min(21);
        let mut product_sum: u64 = 0;
        if start <= end {
            for position in start..=end {
                product_sum = product_sum
                    .wrapping_add(spa90_words[position].wrapping_mul(sp880_words[index - position]));
            }
        }
        let span: u64 = if start <= end { (end - start + 1) as u64 } else { 0 };
        let sum_a = range_sum_from_prefix(sp670_prefix, start, end);
        let sum_b = range_sum_from_prefix(sp510_prefix, index - end, index - start);
        let mixed = span
            .wrapping_mul(0xc9579b83c731c3c0)
            .wrapping_add(0x5c81c51b07a75dd5)
            .wrapping_add(product_sum.wrapping_mul(0x5af5ce9c3c24da93))
            .wrapping_add(sum_a.wrapping_mul(0x22758d71fea188c0))
            .wrapping_add(sum_b.wrapping_mul(0xf1d0ed7a635c3b3f));
        let word = mixed.wrapping_mul(0xa731aa4721be8565).wrapping_add(0x25f1b6bafa949dff);
        out.extend_from_slice(&word.to_le_bytes());
    }
    Ok(out)
}

// ---------------------------------------------------------------- out0 / seventh stages

/// kit `builder642f60Out0SourceWords` (L4460-4502).
pub fn builder642f60_out0_source_words(
    sixth_output: &[u8],
    context_source: &[u8],
    sp250_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    require(sixth_output, VEC_BYTES, "642f60 sixth output")?;
    require(context_source, 0x208, "642f60 context source")?;
    if sp250_words.len() != VEC_WORDS {
        return Err(vec_err(sp250_words.len()));
    }
    let base_words = u32_words_from_table_segments(
        &[
            (BUILDER642F60_OUT0_STATIC_Q0, 16),
            (BUILDER642F60_OUT0_STATIC_Q1, 16),
            (BUILDER642F60_OUT0_STATIC_Q0, 16),
            (BUILDER642F60_OUT0_STATIC_Q1, 16),
            (BUILDER642F60_OUT0_STATIC_Q0, 16),
            (BUILDER642F60_OUT0_STATIC_D1, 8),
        ],
        t,
    )?;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let sixth_word = read_u32_le(sixth_output, index * 4);
        let context_word = read_u32_le(context_source, 0x1b0 + index * 4);
        let mut word = base_words[index].wrapping_add(
            sixth_word.wrapping_mul(u32_table_word_63c278(
                BUILDER642F60_OUT0_SIXTH_MUL_TABLE + table_offset,
                t,
            )?),
        );
        word = word.wrapping_add(context_word.wrapping_mul(u32_table_word_63c278(
            BUILDER642F60_OUT0_CONTEXT_MUL_TABLE + table_offset,
            t,
        )?));
        let sp250_delta = sp250_words[index].wrapping_mul(u32_table_word_63c278(
            BUILDER642F60_OUT0_SP250_MUL_TABLE + table_offset,
            t,
        )?);
        out.push(word.wrapping_add(sp250_delta).wrapping_add(sp250_delta));
    }
    Ok(out)
}

/// kit `builder642f60Out0WordsFromSource` (L4504-4544).
pub fn builder642f60_out0_words_from_source(
    source_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if source_words.len() != VEC_WORDS {
        return Err(vec_err(source_words.len()));
    }
    let mut state: u32 = 0xb326b224;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for (index, &word) in source_words.iter().enumerate() {
        let table_offset = (index * 4) & 0x1c;
        state = state.wrapping_mul(0x3d98bc67).wrapping_add(word);
        let mut folded7 = state.wrapping_mul(0xe98a6e39).wrapping_add(0xa9ce435c);
        folded7 = fold32_by_nibbles_63c278(folded7, BUILDER642F60_OUT0_FOLD_TABLE, 7, t)?;
        let side = state
            .wrapping_mul(0x88625dcf)
            .wrapping_add(folded7.wrapping_mul(0x90000000))
            .wrapping_add(0x647eea94);
        let folded8 = fold32_by_nibbles_63c278(folded7, BUILDER642F60_OUT0_FOLD_TABLE, 1, t)?;
        out.push(u32_table_affine_63c278(
            side,
            BUILDER642F60_OUT0_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_OUT0_OUT_ADD_TABLE + table_offset,
            t,
        )?);
        state = folded7
            .wrapping_mul(0x50717a0f)
            .wrapping_add(folded8.wrapping_mul(0xf8e85f10))
            .wrapping_add(0x119b9786);
    }
    Ok(out)
}

/// kit `builder642f60SeventhSourceWords` (L4546-4581).
pub fn builder642f60_seventh_source_words(
    sp250_words: &[u32],
    context_source: &[u8],
    out0_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if sp250_words.len() != VEC_WORDS {
        return Err(vec_err(sp250_words.len()));
    }
    require(context_source, 0x260, "642f60 context source")?;
    if out0_words.len() != VEC_WORDS {
        return Err(vec_err(out0_words.len()));
    }
    let base_words = u32_words_from_table_segments(
        &[
            (BUILDER642F60_SEVENTH_STATIC_Q0, 16),
            (BUILDER642F60_SEVENTH_STATIC_Q1, 16),
            (BUILDER642F60_SEVENTH_STATIC_Q0, 16),
            (BUILDER642F60_SEVENTH_STATIC_Q1, 16),
            (BUILDER642F60_SEVENTH_STATIC_Q0, 16),
            (BUILDER642F60_SEVENTH_STATIC_D1, 8),
        ],
        t,
    )?;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let context_word = read_u32_le(context_source, 0x208 + index * 4);
        out.push(
            base_words[index]
                .wrapping_add(sp250_words[index].wrapping_mul(u32_table_word_63c278(
                    BUILDER642F60_SEVENTH_SP250_MUL_TABLE + table_offset,
                    t,
                )?))
                .wrapping_add(context_word.wrapping_mul(u32_table_word_63c278(
                    BUILDER642F60_SEVENTH_CONTEXT_MUL_TABLE + table_offset,
                    t,
                )?))
                .wrapping_add(out0_words[index].wrapping_mul(u32_table_word_63c278(
                    BUILDER642F60_SEVENTH_OUT0_MUL_TABLE + table_offset,
                    t,
                )?)),
        );
    }
    Ok(out)
}

/// kit `builder642f60SeventhStageSP148WordsFromSource` (L4583-4623).
pub fn builder642f60_seventh_stage_sp148_words_from_source(
    source_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if source_words.len() != VEC_WORDS {
        return Err(vec_err(source_words.len()));
    }
    let mut state: u32 = 0xf92a7de1;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for (index, &word) in source_words.iter().enumerate() {
        let table_offset = (index * 4) & 0x1c;
        state = state.wrapping_mul(0x2bd72421).wrapping_add(word);
        let mut folded7 = state.wrapping_mul(0x79766d05).wrapping_add(0x22dc5eef);
        folded7 = fold32_by_nibbles_63c278(folded7, BUILDER642F60_SEVENTH_SP148_FOLD_TABLE, 7, t)?;
        let side = state
            .wrapping_mul(0x072b272d)
            .wrapping_add(folded7.wrapping_mul(0x70000000))
            .wrapping_add(0x63742f4b);
        let folded8 = fold32_by_nibbles_63c278(folded7, BUILDER642F60_SEVENTH_SP148_FOLD_TABLE, 1, t)?;
        out.push(u32_table_affine_63c278(
            side,
            BUILDER642F60_SEVENTH_SP148_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_SEVENTH_SP148_OUT_ADD_TABLE + table_offset,
            t,
        )?);
        state = folded7
            .wrapping_mul(0x04d53e2d)
            .wrapping_add(folded8.wrapping_mul(0xb2ac1d30))
            .wrapping_add(0x1cf006eb);
    }
    Ok(out)
}

/// kit `builder642f60SeventhStreams` (L4625-4681).
pub fn builder642f60_seventh_streams(
    sp1a0_words: &[u32],
    sp148_words: &[u32],
    t: &FirstPairTables,
) -> Result<Builder642f60SeventhStreams, CryptoError> {
    if sp1a0_words.len() != VEC_WORDS {
        return Err(vec_err(sp1a0_words.len()));
    }
    if sp148_words.len() != VEC_WORDS {
        return Err(vec_err(sp148_words.len()));
    }
    let sp670_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| {
            u64_stream_word_from_u32_affine(
                sp1a0_words[index],
                index,
                BUILDER642F60_SEVENTH_A_MUL_TABLE,
                BUILDER642F60_SEVENTH_A_ADD_TABLE,
                0xf36a661d,
                0x55308919,
                0xce5ac3ad5b5dac97,
                0x48dc073b21398a79,
                BUILDER642F60_SEVENTH_A_FOLD_TABLE,
                0xfa62b370c3eadc41,
                0xf45f1f1900000000,
                0x6fc778fe52193dd5,
                t,
            )
        })
        .collect::<Result<_, _>>()?;
    let sp510_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| {
            u64_stream_word_from_u32_affine(
                sp148_words[index],
                index,
                BUILDER642F60_SEVENTH_B_MUL_TABLE,
                BUILDER642F60_SEVENTH_B_ADD_TABLE,
                0x84c35e4f,
                0xea2fdd20,
                0x1f41e4ec093ed9f7,
                0x27d1733855de4d16,
                BUILDER642F60_SEVENTH_B_FOLD_TABLE,
                0x9fac6b22392e3497,
                0x09482d9f00000000,
                0xf6042c7612dc729e,
                t,
            )
        })
        .collect::<Result<_, _>>()?;
    let mut spa90_prefix = vec![0u64];
    spa90_prefix.extend(prefix_sums_u64(&sp670_words));
    let mut sp880_prefix = vec![0u64];
    sp880_prefix.extend(prefix_sums_u64(&sp510_words));
    Ok(Builder642f60SeventhStreams {
        sp670_words,
        spa90_prefix,
        sp510_words,
        sp880_prefix,
    })
}

/// kit `builder642f60SeventhSP9E0Words` (L4683-4762).
pub fn builder642f60_seventh_sp9e0_words(
    sp670_words: &[u64],
    spa90_prefix: &[u64],
    sp510_words: &[u64],
    sp880_prefix: &[u64],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if sp670_words.len() != VEC_WORDS {
        return Err(vec_err(sp670_words.len()));
    }
    if spa90_prefix.len() != VEC_WORDS + 1 {
        return Err(vec_err(spa90_prefix.len()));
    }
    if sp510_words.len() != VEC_WORDS {
        return Err(vec_err(sp510_words.len()));
    }
    if sp880_prefix.len() != VEC_WORDS + 1 {
        return Err(vec_err(sp880_prefix.len()));
    }
    let mut state: u64 = 0x8360a2c993f75737;
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_WORDS);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let start = index.saturating_sub(VEC_WORDS - 1);
        let end = index.min(VEC_WORDS - 1);
        let mixed: u64 = if start <= end {
            let mut product_sum: u64 = 0;
            for position in start..=end {
                product_sum = product_sum
                    .wrapping_add(sp670_words[position].wrapping_mul(sp510_words[index - position]));
            }
            let span = (end - start + 1) as u64;
            let sum_a = spa90_prefix[end + 1].wrapping_sub(spa90_prefix[start]);
            let sum_b = sp880_prefix[index - start + 1].wrapping_sub(sp880_prefix[index - end]);
            state
                .wrapping_add(span.wrapping_mul(0x005dbd39bbb74611))
                .wrapping_add(product_sum.wrapping_mul(0x376b7bf8523b310f))
                .wrapping_add(sum_a.wrapping_mul(0x05844a4f0ab6c52b))
                .wrapping_add(sum_b.wrapping_mul(0xbcb254e552fa427d))
        } else {
            state
        };
        let folded7 = fold_63c278(
            mixed
                .wrapping_mul(0x8db6469e177ed14b)
                .wrapping_add(0x0980afdda9144775),
            BUILDER642F60_SEVENTH_SP9E0_FOLD_TABLE,
            7,
            t,
        )?;
        let folded8 = fold_63c278(folded7, BUILDER642F60_SEVENTH_SP9E0_FOLD_TABLE, 1, t)?;
        let folded16 = fold_63c278(folded8, BUILDER642F60_SEVENTH_SP9E0_FOLD_TABLE, 8, t)?;
        let side = (mixed as u32)
            .wrapping_mul(0xe15d12ad)
            .wrapping_add((folded7 as u32).wrapping_mul(0x90000000))
            .wrapping_add(0x07600fb6);
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(
            side,
            BUILDER642F60_SEVENTH_SP9E0_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_SEVENTH_SP9E0_OUT_ADD_TABLE + table_offset,
            t,
        )?);
        state = folded7
            .wrapping_mul(0xfac2e2a1bcc53063)
            .wrapping_add(folded16.wrapping_mul(0x33acf9d000000000))
            .wrapping_add(0x42e2c949e6b96dc1);
    }
    Ok(out)
}

/// kit `builder642f60SeventhSPA90WordsFromSP300` (L4764-4786).
pub fn builder642f60_seventh_spa90_words_from_sp300(
    sp300_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u64>, CryptoError> {
    if sp300_words.len() != VEC_WORDS {
        return Err(vec_err(sp300_words.len()));
    }
    (0..VEC_WORDS)
        .map(|index| {
            u64_stream_word_from_u32_affine(
                sp300_words[index],
                index,
                BUILDER642F60_SEVENTH_SP300_MUL_TABLE,
                BUILDER642F60_SEVENTH_SP300_ADD_TABLE,
                0x923b2603,
                0x0d7c3c6d,
                0x461236e7241ea4af,
                0xc0bb06ebd489d8f1,
                BUILDER642F60_SEVENTH_SP300_FOLD_TABLE,
                0x1925dd7dc803ae75,
                0x6a8f4fe500000000,
                0x52f0304276b65fde,
                t,
            )
        })
        .collect()
}

/// kit `builder642f60SeventhSP7D0WordsFromSPA90` (L4788-4879).
pub fn builder642f60_seventh_sp7d0_words_from_spa90(
    spa90_words: &[u64],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if spa90_words.len() != VEC_WORDS {
        return Err(vec_err(spa90_words.len()));
    }
    let mut state: u64 = 0xd71b81e668a07680;
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_WORDS);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let start = index.saturating_sub(VEC_WORDS - 1);
        let end = index.min(VEC_WORDS - 1);
        let mut pair_accumulator: u64 = 0xb616243568409e12;
        let mut left = start;
        let mut right = end;

        if end > start {
            loop {
                let end_word = spa90_words[right];
                right -= 1;
                let product = end_word
                    .wrapping_mul(0x3a825182ec92a9ef)
                    .wrapping_add(0x975bbf5d33a0b7f4);
                let mut mixed_pair = end_word
                    .wrapping_mul(0x975bbf5d33a0b7f4)
                    .wrapping_add(pair_accumulator);
                let start_word = spa90_words[left];
                left += 1;
                mixed_pair = product.wrapping_mul(start_word).wrapping_add(mixed_pair);
                pair_accumulator = mixed_pair.wrapping_add(0x4657dd9b924a1870);
                if right <= left {
                    break;
                }
            }
        }

        pair_accumulator = pair_accumulator.wrapping_mul(0xc3c1f54f3c2cd4a6);
        let scaled_state = state.wrapping_mul(0x7f0a8f747ca98163);
        let mixed: u64 = if right == left {
            let center = spa90_words[right];
            let center_mixed = center
                .wrapping_mul(0x8b03bdcc8a740e7d)
                .wrapping_add(0x25af1839607d5838);
            center_mixed
                .wrapping_mul(center)
                .wrapping_add(pair_accumulator)
                .wrapping_add(scaled_state)
                .wrapping_add(0xe804eb7226c5f391)
        } else {
            pair_accumulator
                .wrapping_add(scaled_state)
                .wrapping_add(0x0bea08ebd101a741)
        };

        let product = mixed.wrapping_mul(0x416e14010d9d6b21);
        let first_fold = fold_table_u64_word_63c278(
            BUILDER642F60_SEVENTH_SP7D0_FOLD_TABLE + ((product & 0x0f) as usize) * 8,
            t,
        )?
        .wrapping_add(product.wrapping_add(0xe4602986bf1f9a80) >> 4);
        let folded7 = fold_63c278(first_fold, BUILDER642F60_SEVENTH_SP7D0_FOLD_TABLE, 6, t)?;
        let folded8 = fold_63c278(folded7, BUILDER642F60_SEVENTH_SP7D0_FOLD_TABLE, 1, t)?;
        let side = (mixed as u32)
            .wrapping_mul(0x3e3dcae5)
            .wrapping_add((folded7 as u32).wrapping_mul(0xb0000000))
            .wrapping_add(0x8a63e3dc);
        let folded16 = fold_63c278(folded8, BUILDER642F60_SEVENTH_SP7D0_FOLD_TABLE, 8, t)?;
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(
            side,
            BUILDER642F60_SEVENTH_SP7D0_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_SEVENTH_SP7D0_OUT_ADD_TABLE + table_offset,
            t,
        )?);
        state = folded7
            .wrapping_mul(0x38c35e2d317591eb)
            .wrapping_add(folded16.wrapping_mul(0xe8a6e15000000000))
            .wrapping_add(0x7cfc7c8b77dde511);
    }
    Ok(out)
}

/// kit `builder642f60SeventhSource44Words` (L4881-4921).
pub fn builder642f60_seventh_source44_words(
    sp9e0_words: &[u32],
    context_source: &[u8],
    sp7d0_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if sp9e0_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(sp9e0_words.len()));
    }
    require(context_source, 0x418, "642f60 context source")?;
    if sp7d0_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(sp7d0_words.len()));
    }
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_WORDS);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let context_word = read_u32_le(context_source, 0x368 + index * 4);
        let sp7d0_delta = sp7d0_words[index].wrapping_mul(u32_table_word_63c278(
            BUILDER642F60_SEVENTH_SOURCE_SP7D0_MUL_TABLE + table_offset,
            t,
        )?);
        out.push(
            u32_table_word_63c278(BUILDER642F60_SEVENTH_SOURCE_STATIC_TABLE + table_offset, t)?
                .wrapping_add(sp9e0_words[index].wrapping_mul(u32_table_word_63c278(
                    BUILDER642F60_SEVENTH_SOURCE_SP9E0_MUL_TABLE + table_offset,
                    t,
                )?))
                .wrapping_add(context_word.wrapping_mul(u32_table_word_63c278(
                    BUILDER642F60_SEVENTH_SOURCE_CONTEXT368_MUL_TABLE + table_offset,
                    t,
                )?))
                .wrapping_add(sp7d0_delta)
                .wrapping_add(sp7d0_delta),
        );
    }
    Ok(out)
}

/// kit `builder642f60SeventhSP40WordsFromSource44` (L4923-4961).
pub fn builder642f60_seventh_sp40_words_from_source44(
    source_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if source_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(source_words.len()));
    }
    let mut state: u32 = 0xcfda05ba;
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_WORDS);
    for (index, &word) in source_words.iter().enumerate() {
        let table_offset = (index * 4) & 0x1c;
        state = state.wrapping_mul(0x0862c569).wrapping_add(word);
        let mut folded7 = state.wrapping_mul(0x5e8a87f3).wrapping_add(0x54d7c56f);
        folded7 = fold32_by_nibbles_63c278(folded7, BUILDER642F60_SEVENTH_SP40_FOLD_TABLE, 7, t)?;
        let side = state
            .wrapping_mul(0x12f83eed)
            .wrapping_add(folded7 << 28)
            .wrapping_add(0x51f93a0a);
        let folded8 = fold_table_u32_word_63c278(
            BUILDER642F60_SEVENTH_SP40_FOLD_TABLE + ((folded7 & 0x0f) as usize) * 4,
            t,
        )?
        .wrapping_add(folded7 >> 4);
        out.push(u32_table_affine_63c278(
            side,
            BUILDER642F60_SEVENTH_SP40_OUT_MUL_TABLE + table_offset,
            BUILDER642F60_SEVENTH_SP40_OUT_ADD_TABLE + table_offset,
            t,
        )?);
        state = folded7
            .wrapping_mul(0x36a73103)
            .wrapping_add(folded8.wrapping_mul(0x958cefd0))
            .wrapping_add(0x9e56fff6);
    }
    Ok(out)
}

/// kit `builder642f60Seventh64bd0cWorkspace` (L4963-4989).
pub fn builder642f60_seventh64bd0c_workspace(
    sp40_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if sp40_words.len() != BUILDER64BD0C_WORKSPACE_WORDS {
        return Err(vec_err(sp40_words.len()));
    }
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_BYTES);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let word = u64_stream_word_from_u32_affine(
            sp40_words[index],
            index,
            BUILDER642F60_SEVENTH_WORKSPACE_MUL_TABLE,
            BUILDER642F60_SEVENTH_WORKSPACE_ADD_TABLE,
            0x2e6bbea3,
            0xe3db739a,
            0x40c95ec2845e4b0b,
            0xb5edeaa67030b38d,
            BUILDER642F60_SEVENTH_WORKSPACE_FOLD_TABLE,
            0xa2d77df3e3f51135,
            0x7122434100000000,
            0xb3aefd596d371f14,
            t,
        )?;
        out.extend_from_slice(&word.to_le_bytes());
    }
    Ok(out)
}

/// kit `builder642f60Out1WordsFrom64bd0cOutput` (L4991-4998).
pub fn builder642f60_out1_words_from64bd0c_output(
    output: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    builder642f60_affine_words_from64bd0c_output(
        output,
        BUILDER642F60_OUT1_MUL_TABLE,
        BUILDER642F60_OUT1_ADD_TABLE,
        "642f60 output #1",
        t,
    )
}

// ---------------------------------------------------------------- eighth stage

/// kit `builder642f60EighthStreams` (L5000-5032).
pub fn builder642f60_eighth_streams(
    sp2a8_words: &[u32],
    x2_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder642f60EighthStreams, CryptoError> {
    if sp2a8_words.len() != VEC_WORDS {
        return Err(vec_err(sp2a8_words.len()));
    }
    require(x2_source, VEC_BYTES, "642f60 x2 source")?;
    let x2_words: Vec<u32> = (0..VEC_WORDS).map(|index| read_u32_le(x2_source, index * 4)).collect();
    let spa90_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_eighth_a_word(sp2a8_words[index], index, t))
        .collect::<Result<_, _>>()?;
    let sp880_words: Vec<u64> = (0..VEC_WORDS)
        .map(|index| builder642f60_eighth_b_word(x2_words[index], index, t))
        .collect::<Result<_, _>>()?;
    Ok(Builder642f60EighthStreams {
        sp670_prefix: prefix_sums_u64(&spa90_words),
        sp510_prefix: prefix_sums_u64(&sp880_words),
        spa90_words,
        sp880_words,
    })
}

/// kit `builder642f60Eighth64bd0cWorkspace` (L5034-5078).
pub fn builder642f60_eighth64bd0c_workspace(
    spa90_words: &[u64],
    sp670_prefix: &[u64],
    sp880_words: &[u64],
    sp510_prefix: &[u64],
) -> Result<Vec<u8>, CryptoError> {
    if spa90_words.len() != VEC_WORDS {
        return Err(vec_err(spa90_words.len()));
    }
    if sp670_prefix.len() != VEC_WORDS {
        return Err(vec_err(sp670_prefix.len()));
    }
    if sp880_words.len() != VEC_WORDS {
        return Err(vec_err(sp880_words.len()));
    }
    if sp510_prefix.len() != VEC_WORDS {
        return Err(vec_err(sp510_prefix.len()));
    }
    let mut out = Vec::with_capacity(BUILDER64BD0C_WORKSPACE_BYTES);
    for index in 0..BUILDER64BD0C_WORKSPACE_WORDS {
        let start = index.saturating_sub(VEC_WORDS - 1);
        let end = index.min(VEC_WORDS - 1);
        let mixed: u64 = if start <= end {
            let mut product_sum: u64 = 0;
            for position in start..=end {
                product_sum = product_sum
                    .wrapping_add(spa90_words[position].wrapping_mul(sp880_words[index - position]));
            }
            let span = (end - start + 1) as u64;
            let sum_a = range_sum_from_prefix(sp670_prefix, start, end);
            let sum_b = range_sum_from_prefix(sp510_prefix, index - end, index - start);
            span.wrapping_mul(0x05f89c998f88e9a2)
                .wrapping_add(0xe3449c12b03ff8d9)
                .wrapping_add(product_sum.wrapping_mul(0xeab93afc6984b71d))
                .wrapping_add(sum_a.wrapping_mul(0xc2592d51a5992a23))
                .wrapping_add(sum_b.wrapping_mul(0xf8e4c71d4c7a89de))
        } else {
            0xe3449c12b03ff8d9
        };
        let word = mixed.wrapping_mul(0xca274927c26656e9).wrapping_add(0x89706c698c29e887);
        out.extend_from_slice(&word.to_le_bytes());
    }
    Ok(out)
}

/// kit `builder642f60Out2WordsFrom64bd0cOutput` (L5080-5087).
pub fn builder642f60_out2_words_from64bd0c_output(
    output: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    builder642f60_affine_words_from64bd0c_output(
        output,
        BUILDER642F60_OUT2_MUL_TABLE,
        BUILDER642F60_OUT2_ADD_TABLE,
        "642f60 output #2",
        t,
    )
}

// ---------------------------------------------------------------- output assembly

/// kit `builder642f60Outputs` (FirstPairSourceSlice.swift L5089-5247): the eight 64bd0c
/// round trips from (in0, in1, in2, contextSource) to out0/out1/out2.
pub fn builder642f60_outputs(
    in0: &[u8],
    in1: &[u8],
    in2: &[u8],
    context_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder642f60Result, CryptoError> {
    require(in0, VEC_BYTES, "642f60 in0")?;
    require(in1, VEC_BYTES, "642f60 in1")?;
    require(in2, VEC_BYTES, "642f60 in2")?;
    require(context_source, 0x420, "642f60 context source")?;

    let arg0 = &context_source[0x100..0x158];
    let scalar = read_u64_le(context_source, 0x418);

    let sp2a8_words = builder642f60_stage_sp2a8_words_from_x1(in1, t)?;
    let first_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_first64bd0c_workspace_from_x1(in1, &sp2a8_words, t)?,
        t,
    )?);
    let sp300_words = builder642f60_stage_sp300_words_from64bd0c_output(&first_output, t)?;

    let sp1f8_words = builder642f60_stage_sp1f8_words_from_x0(in0, t)?;
    let second_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_second64bd0c_workspace(&sp1f8_words, &sp300_words, t)?,
        t,
    )?);
    let sp250_words = builder642f60_stage_sp250_words_from64bd0c_output(&second_output, t)?;

    let third_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_third64bd0c_workspace_from_x2(in2, t)?,
        t,
    )?);
    let sp148_words = builder642f60_stage_sp148_words_from64bd0c_output(&third_output, t)?;

    let fourth_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_fourth64bd0c_workspace(&sp148_words, t)?,
        t,
    )?);
    let spf0_words = builder642f60_stage_spf0_words_from64bd0c_output(&fourth_output, t)?;

    let mid_spa90_words = builder642f60_mid_stage_spa90_words_from_x0(in0, t)?;
    let mid_sp40_words = builder642f60_mid_stage_sp40_words_from_spa90(&mid_spa90_words, t)?;
    let mid_streams = builder642f60_mid_stage_streams_from_context_spf0(context_source, &spf0_words, t)?;
    let mid_sp670_words = builder642f60_mid_stage_sp670_words(
        &mid_streams.spa90_words,
        &mid_streams.sp510_prefix,
        &mid_streams.sp880_words,
        &mid_streams.sp9e0_prefix,
    )?;
    let mid_sp40_b = builder642f60_mid_stage_spa90_sp880_from_sp40(&mid_sp40_words, t)?;
    let mid_static = builder642f60_mid_stage_static_sp9e0_sp7d0(mid_sp40_b.side_init, t)?;
    let mid_sp510_words = builder642f60_mid_stage_sp510_words(
        &mid_sp40_b.spa90_words,
        &mid_sp40_b.sp880_prefix,
        &mid_static.sp9e0_words,
        &mid_static.sp7d0_prefix,
    )?;

    let fifth_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_mid_fifth64bd0c_workspace(&mid_sp670_words, &mid_sp510_words)?,
        t,
    )?);
    let sp1a0_words = builder642f60_stage_sp1a0_words_from64bd0c_output(&fifth_output, t)?;

    let sixth_streams = builder642f60_sixth_streams_from_sp1a0(&sp1a0_words, t)?;
    let sixth_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_sixth64bd0c_workspace(
            &sixth_streams.spa90_words,
            &sixth_streams.sp670_prefix,
            &sixth_streams.sp880_words,
            &sixth_streams.sp510_prefix,
        )?,
        t,
    )?);

    let out0_source_words = builder642f60_out0_source_words(&sixth_output, context_source, &sp250_words, t)?;
    let out0_words = builder642f60_out0_words_from_source(&out0_source_words, t)?;

    let seventh_source_words =
        builder642f60_seventh_source_words(&sp250_words, context_source, &out0_words, t)?;
    let seventh_sp148_words = builder642f60_seventh_stage_sp148_words_from_source(&seventh_source_words, t)?;
    let seventh_streams = builder642f60_seventh_streams(&sp1a0_words, &seventh_sp148_words, t)?;
    let seventh_sp9e0_words = builder642f60_seventh_sp9e0_words(
        &seventh_streams.sp670_words,
        &seventh_streams.spa90_prefix,
        &seventh_streams.sp510_words,
        &seventh_streams.sp880_prefix,
        t,
    )?;
    let seventh_spa90_words = builder642f60_seventh_spa90_words_from_sp300(&sp300_words, t)?;
    let seventh_sp7d0_words = builder642f60_seventh_sp7d0_words_from_spa90(&seventh_spa90_words, t)?;
    let seventh_source44_words = builder642f60_seventh_source44_words(
        &seventh_sp9e0_words,
        context_source,
        &seventh_sp7d0_words,
        t,
    )?;
    let seventh_sp40_words = builder642f60_seventh_sp40_words_from_source44(&seventh_source44_words, t)?;
    let seventh_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_seventh64bd0c_workspace(&seventh_sp40_words, t)?,
        t,
    )?);
    let out1_words = builder642f60_out1_words_from64bd0c_output(&seventh_output, t)?;

    let eighth_streams = builder642f60_eighth_streams(&sp2a8_words, in2, t)?;
    let eighth_output = pack_u32_le(&builder64bd0c_output_words(
        arg0,
        scalar,
        &builder642f60_eighth64bd0c_workspace(
            &eighth_streams.spa90_words,
            &eighth_streams.sp670_prefix,
            &eighth_streams.sp880_words,
            &eighth_streams.sp510_prefix,
        )?,
        t,
    )?);
    let out2_words = builder642f60_out2_words_from64bd0c_output(&eighth_output, t)?;

    Ok(Builder642f60Result {
        out0: pack_u32_le(&out0_words),
        out1: pack_u32_le(&out1_words),
        out2: pack_u32_le(&out2_words),
    })
}

/// kit `builder642f60OutputsFromBundledContext` (FirstPairSourceSlice.swift L5249-5256).
pub fn builder642f60_outputs_from_bundled_context(
    in0: &[u8],
    in1: &[u8],
    in2: &[u8],
    t: &FirstPairTables,
) -> Result<Builder642f60Result, CryptoError> {
    let context = super::highseed::builder6388f0_shared_context_from_bundle(t)?;
    builder642f60_outputs(in0, in1, in2, &context, t)
}
