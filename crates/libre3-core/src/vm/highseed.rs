//! FirstPairSourceSlice — the high-seed stream-start layer (kit: FirstPairSourceSlice.swift).
//! Stage A of the 6388f0 first-pair builder port: the 6421c0 64cd40-workspace data path that
//! turns two P-256 product coordinates (padded-70 LE) into the 88-byte out0/out1 stream-start
//! seeds, the stream-start 642f60 input mapping (and its inverse), the caller-context
//! assembly over the two new bundle tables, and the pure 64cd40-output repack. The low-seed
//! layer that produces row0Out4/Out3/Out2 is a later stage; those arrive as inputs here.
//!
//! Golden vectors: tests/firstpair_highseed.rs (ported 1:1 from FirstPairSourceSliceTests.swift).

use super::firstpair::*;
use super::schedule::{
    fold_63c278, u32_affine_63c278, u32_affine_bytes_63c278, u32_table_affine_63c278, VEC_BYTES,
    VEC_WORDS,
};
use super::tables::FirstPairTables;
use crate::CryptoError;

// ---------------------------------------------------------------- kit static sources

/// kit `streamStart642f60X2Source` (FirstPairSourceSlice.swift L501-513): the fixed x2 of the
/// stream-start 642f60 inputs.
pub const STREAM_START_642F60_X2_SOURCE: [u8; 88] = [
    0x4c, 0x2d, 0xf3, 0x05, 0xdd, 0xb7, 0x0c, 0x76,
    0xe8, 0x2a, 0xd4, 0x04, 0x3b, 0xe2, 0xee, 0xa5,
    0x81, 0xab, 0x69, 0xf3, 0x7c, 0xaa, 0x49, 0xf5,
    0xfa, 0x7d, 0x43, 0x81, 0x2f, 0x10, 0x25, 0x05,
    0xd3, 0x4e, 0x67, 0xbe, 0x8d, 0x2e, 0x98, 0xd3,
    0xe8, 0x2a, 0xe3, 0x78, 0x3b, 0x32, 0xb0, 0x16,
    0x81, 0xab, 0x69, 0x5c, 0xe3, 0x6c, 0x62, 0xec,
    0xa5, 0x62, 0x1f, 0xaf, 0xcf, 0x68, 0x46, 0x16,
    0xc5, 0x19, 0x8a, 0x79, 0x48, 0xa0, 0x3a, 0xd3,
    0xe8, 0x2a, 0xe3, 0x78, 0x3b, 0x32, 0xb0, 0x16,
    0x81, 0xab, 0x69, 0x5c, 0xe3, 0x6c, 0x62, 0xec,
];

/// kit `highSeed6421c0X2Source` (FirstPairSourceSlice.swift L514-526): the fixed 6421c0 x2.
pub const HIGH_SEED_6421C0_X2_SOURCE: [u8; 88] = [
    0xd6, 0xce, 0x5d, 0x63, 0xde, 0x75, 0xb3, 0x91,
    0x43, 0x98, 0xc9, 0xa1, 0x23, 0x40, 0x76, 0x0f,
    0x3c, 0x69, 0x5a, 0x13, 0x9c, 0xbb, 0xc9, 0x13,
    0x5d, 0x94, 0xf6, 0x57, 0xb7, 0x29, 0x9c, 0xb1,
    0x82, 0x46, 0x31, 0x56, 0x4e, 0x88, 0x5b, 0x47,
    0x9d, 0x21, 0x1c, 0xae, 0xf3, 0x69, 0xd9, 0xea,
    0x19, 0xae, 0x4d, 0x0d, 0xc9, 0x70, 0x20, 0x4b,
    0x5d, 0x94, 0xf6, 0x84, 0xd7, 0xde, 0x58, 0xc2,
    0x35, 0xac, 0xa4, 0x60, 0xfc, 0x3d, 0xd5, 0xb4,
    0xc8, 0x46, 0x76, 0x15, 0xc0, 0xa7, 0xe6, 0xc0,
    0x19, 0xae, 0x4d, 0x0d, 0xc9, 0x70, 0x20, 0x4b,
];

/// kit `highSeed6421c0X1Source` (FirstPairSourceSlice.swift L527-539): the fixed 6421c0 x1.
pub const HIGH_SEED_6421C0_X1_SOURCE: [u8; 88] = [
    0xf9, 0xb8, 0xa2, 0x3b, 0x79, 0x89, 0x3d, 0xab,
    0x28, 0xf6, 0x8f, 0x89, 0x3a, 0x72, 0x9b, 0xfc,
    0x43, 0x32, 0x3b, 0x85, 0x8f, 0xcb, 0xd6, 0x95,
    0xf4, 0xd2, 0x62, 0x09, 0x77, 0x91, 0x59, 0xaf,
    0xa1, 0x03, 0xdf, 0xee, 0x09, 0x58, 0xb8, 0x3b,
    0xb5, 0x0a, 0x88, 0x9d, 0x20, 0x4a, 0xad, 0xbb,
    0xa4, 0x80, 0x61, 0x06, 0xa7, 0x57, 0x0b, 0xca,
    0xf4, 0x52, 0x42, 0xee, 0x5f, 0xa7, 0xa2, 0x7e,
    0xac, 0x8b, 0xc6, 0xb0, 0x87, 0xa3, 0x03, 0x84,
    0xa3, 0xc2, 0xaf, 0x99, 0x20, 0x4a, 0xad, 0xbb,
    0xa4, 0x80, 0x61, 0x06, 0xa7, 0x57, 0x0b, 0xca,
];

/// kit `highSeed6421c0Scalar` (FirstPairSourceSlice.swift L540): the fixed 6421c0 scalar.
pub const HIGH_SEED_6421C0_SCALAR: u64 = 0x68404ef676a9b7d3;

// ---------------------------------------------------------------- 63c278 table constants
// (FirstPairSourceSlice.swift L14002-14034; u32/fold table offsets are absolute addresses
// inside firstpair_63c278_u32_tables_112588 / firstpair_63c278_fold_tables_2feb18.)

const BUILDER6421C0_X0_MUL_TABLE: usize = 0x113f08;
const BUILDER6421C0_X0_ADD_TABLE: usize = 0x113628;
const BUILDER6421C0_X0_FOLD_TABLE: usize = 0x2feb18;
const BUILDER6421C0_X1_MUL_TABLE: usize = 0x11b288;
const BUILDER6421C0_X1_ADD_TABLE: usize = 0x118388;
const BUILDER6421C0_X1_FOLD_TABLE: usize = 0x2feb98;
const BUILDER6421C0_X2_MUL_TABLE: usize = 0x1183a8;
const BUILDER6421C0_X2_ADD_TABLE: usize = 0x11b2a8;
const BUILDER6421C0_X2_FOLD_TABLE: usize = 0x2fec18;
const BUILDER6421C0_WORKSPACE_FOLD_TABLE: usize = 0x2fec98;
const BUILDER6421C0_REWRITE_FOLD1_TABLE: usize = 0x2fed18;
const BUILDER6421C0_REWRITE_FOLD2_TABLE: usize = 0x2fed98;
const BUILDER6421C0_FINAL_FOLD_TABLE: usize = 0x2fee18;
const BUILDER6421C0_FINAL_OUT_MUL_TABLE: usize = 0x115e48;
const BUILDER6421C0_FINAL_OUT_ADD_TABLE: usize = 0x115308;
const BUILDER6388F0_HIGH_SEED_X0_SOURCE_MUL_TABLE: usize = 0x11fd48;
const BUILDER6388F0_HIGH_SEED_X0_SOURCE_ADD_TABLE: usize = 0x1152e8;
const BUILDER6388F0_PRE63_ARG1_MUL_TABLE: usize = 0x11a988;
const BUILDER6388F0_PRE63_ARG1_ADD_TABLE: usize = 0x1184c8;
const BUILDER6388F0_PRE63_ARG2_MUL_TABLE: usize = 0x112608;
const BUILDER6388F0_PRE63_ARG2_ADD_TABLE: usize = 0x11a008;
const BUILDER6388F0_NEXT642_X1_MUL_TABLE: usize = 0x114948;
const BUILDER6388F0_NEXT642_X1_ADD_TABLE: usize = 0x1184e8;
const BUILDER6388F0_STREAM_START_OUT0_TO_642F60_X0_MUL_TABLE: usize = 0x11fd68;
const BUILDER6388F0_STREAM_START_OUT0_TO_642F60_X0_ADD_TABLE: usize = 0x1233a8;
const BUILDER6388F0_STREAM_START_OUT1_TO_642F60_X1_MUL_TABLE: usize = 0x115328;
const BUILDER6388F0_STREAM_START_OUT1_TO_642F60_X1_ADD_TABLE: usize = 0x11b2c8;

// ---------------------------------------------------------------- builder sizing constants
// (FirstPairSourceSlice.swift L14002-14044.)

const BUILDER64CD40_WORKSPACE_WORDS: usize = 44;
const BUILDER64CD40_WORKSPACE_BYTES: usize = BUILDER64CD40_WORKSPACE_WORDS * 8;
/// kit `builder6388f0SharedContextLength` (FirstPairSourceSlice.swift L14035): enforced by the
/// FirstPairTables SPEC entry (`firstpair_6388f0_shared_context_2cdae1`, exact 0x520); kept
/// named for the caller-context checks later stages share.
#[allow(dead_code)]
const BUILDER6388F0_SHARED_CONTEXT_LENGTH: usize = 0x520;
const BUILDER6388F0_CALLER_LOOP_TABLE_ROWS: usize = 59;
const BUILDER6388F0_CALLER_LOOP_ROW_BYTES: usize = 0x58;
const BUILDER6388F0_CALLER_LOOP_TABLE_BYTES: usize =
    BUILDER6388F0_CALLER_LOOP_TABLE_ROWS * BUILDER6388F0_CALLER_LOOP_ROW_BYTES;
const BUILDER6388F0_CALLER_LOOP_INTERLEAVED_ROW_BYTES: usize =
    BUILDER6388F0_CALLER_LOOP_ROW_BYTES * 2;
const BUILDER6388F0_CALLER_LOOP_TABLE1_CONTEXT_OFFSET: usize = 0x4c8;
const BUILDER6388F0_CALLER_LOOP_TABLE2_CONTEXT_OFFSET: usize = 0x1910;
const BUILDER6388F0_CALLER_CONTEXT_LENGTH: usize =
    BUILDER6388F0_CALLER_LOOP_TABLE2_CONTEXT_OFFSET + BUILDER6388F0_CALLER_LOOP_TABLE_BYTES;

// ---------------------------------------------------------------- structs

/// kit `Builder6388f0Next642f60Inputs` (FirstPairSourceSlice.swift L58-68).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0Next642f60Inputs {
    pub x0: Vec<u8>,
    pub x1: Vec<u8>,
    pub x2: Vec<u8>,
}

/// kit `Builder6388f0FirstPair642f60Starts` (FirstPairSourceSlice.swift L70-78).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0FirstPair642f60Starts {
    pub row0: Builder6388f0Next642f60Inputs,
    pub row59: Builder6388f0Next642f60Inputs,
}

/// kit `Builder6388f0FirstPairStreamSeeds` (FirstPairSourceSlice.swift L80-118). The low-seed
/// preimages (row0Out4/Out3/Out2) arrive as inputs — their builder is a later stage.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0FirstPairStreamSeeds {
    pub null_scalar_window: Vec<u8>,
    pub static_scalar_window: Vec<u8>,
    pub null_entropy_11a: Vec<u8>,
    pub null_attempts: usize,
    pub row0_out4: Vec<u8>,
    pub row0_out3: Vec<u8>,
    pub row0_out2: Vec<u8>,
    pub row0_out1: Vec<u8>,
    pub row0_out0: Vec<u8>,
    pub row59_out1: Vec<u8>,
    pub row59_out0: Vec<u8>,
}

/// kit `Builder5bcf98P256Outputs` (FirstPairSourceSlice.swift L451-459); produced by the
/// already-ported `crate::p256::multiply_padded_70` (kit P256ScalarMultiplier.swift).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder5bcf98P256Outputs {
    pub x_output70: Vec<u8>,
    pub y_output70: Vec<u8>,
}

/// kit `Builder6388f0HighSeedStreamStartSeeds` (FirstPairSourceSlice.swift L295-303).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0HighSeedStreamStartSeeds {
    pub out0: Vec<u8>,
    pub out1: Vec<u8>,
}

/// kit `Builder6388f0FirstPairHighSeedStreamStartSeeds` (FirstPairSourceSlice.swift L305-313).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0FirstPairHighSeedStreamStartSeeds {
    pub row0: Builder6388f0HighSeedStreamStartSeeds,
    pub row59: Builder6388f0HighSeedStreamStartSeeds,
}

/// kit `Builder6388f0CallerLoopTables` (FirstPairSourceSlice.swift L179-187).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0CallerLoopTables {
    pub first: Vec<u8>,
    pub second: Vec<u8>,
}

// ---------------------------------------------------------------- 64cd40-output repack

/// kit `builder6388f0Next642f60InputsFrom64cd40Outputs` (FirstPairSourceSlice.swift L1643-1672):
/// pure per-word table-affine repack of three 64cd40 outputs into the next 642f60 inputs.
pub fn builder6388f0_next642f60_inputs_from64cd40_outputs(
    first64cd40_output: &[u8],
    second64cd40_output: &[u8],
    third64cd40_output: &[u8],
    t: &FirstPairTables,
) -> Result<Builder6388f0Next642f60Inputs, CryptoError> {
    Ok(Builder6388f0Next642f60Inputs {
        x0: u32_affine_bytes_63c278(
            first64cd40_output,
            BUILDER6388F0_PRE63_ARG1_MUL_TABLE,
            BUILDER6388F0_PRE63_ARG1_ADD_TABLE,
            "first 64cd40 output",
            t,
        )?,
        x1: u32_affine_bytes_63c278(
            second64cd40_output,
            BUILDER6388F0_NEXT642_X1_MUL_TABLE,
            BUILDER6388F0_NEXT642_X1_ADD_TABLE,
            "second 64cd40 output",
            t,
        )?,
        x2: u32_affine_bytes_63c278(
            third64cd40_output,
            BUILDER6388F0_PRE63_ARG2_MUL_TABLE,
            BUILDER6388F0_PRE63_ARG2_ADD_TABLE,
            "third 64cd40 output",
            t,
        )?,
    })
}

// ---------------------------------------------------------------- stream-start mapping

/// kit `builder6388f0StreamStart642f60X0FromOut0Seed` (FirstPairSourceSlice.swift L1674-1683).
pub fn builder6388f0_stream_start642f60_x0_from_out0_seed(
    out0_seed: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    u32_affine_bytes_63c278(
        out0_seed,
        BUILDER6388F0_STREAM_START_OUT0_TO_642F60_X0_MUL_TABLE,
        BUILDER6388F0_STREAM_START_OUT0_TO_642F60_X0_ADD_TABLE,
        "6388f0 stream-start out0 seed",
        t,
    )
}

/// kit `builder6388f0StreamStart642f60X1FromOut1Seed` (FirstPairSourceSlice.swift L1685-1694).
pub fn builder6388f0_stream_start642f60_x1_from_out1_seed(
    out1_seed: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    u32_affine_bytes_63c278(
        out1_seed,
        BUILDER6388F0_STREAM_START_OUT1_TO_642F60_X1_MUL_TABLE,
        BUILDER6388F0_STREAM_START_OUT1_TO_642F60_X1_ADD_TABLE,
        "6388f0 stream-start out1 seed",
        t,
    )
}

/// kit `u32AffineInverseBytes63c278` (FirstPairSourceSlice.swift L11779-11803): per-word affine
/// inverse. Reuses schedule's `u32AffineInverse63c278` word primitive.
#[allow(dead_code)] // kit-layer helper; consumed from the next stage's call sites
fn u32_affine_inverse_bytes_63c278(
    input: &[u8],
    mul_table: usize,
    add_table: usize,
    label: &str,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    require(input, VEC_BYTES, label)?;
    let mut out = Vec::with_capacity(VEC_BYTES);
    for index in 0..VEC_WORDS {
        let word = super::schedule::u32_affine_inverse_63c278(read_u32_le(input, index * 4), index, mul_table, add_table, t)?;
        out.extend_from_slice(&word.to_le_bytes());
    }
    Ok(out)
}

/// kit `builder6388f0RecoverStreamStartOut0SeedFrom642f60X0` (L1696-1705).
pub fn builder6388f0_recover_stream_start_out0_seed_from642f60_x0(
    x0_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    u32_affine_inverse_bytes_63c278(
        x0_source,
        BUILDER6388F0_STREAM_START_OUT0_TO_642F60_X0_MUL_TABLE,
        BUILDER6388F0_STREAM_START_OUT0_TO_642F60_X0_ADD_TABLE,
        "6388f0 stream-start 642f60 x0 source",
        t,
    )
}

/// kit `builder6388f0RecoverStreamStartOut1SeedFrom642f60X1` (L1707-1716).
pub fn builder6388f0_recover_stream_start_out1_seed_from642f60_x1(
    x1_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    u32_affine_inverse_bytes_63c278(
        x1_source,
        BUILDER6388F0_STREAM_START_OUT1_TO_642F60_X1_MUL_TABLE,
        BUILDER6388F0_STREAM_START_OUT1_TO_642F60_X1_ADD_TABLE,
        "6388f0 stream-start 642f60 x1 source",
        t,
    )
}

// ---------------------------------------------------------------- 6421c0 helpers

/// kit `prefixSumsU64` (FirstPairSourceSlice.swift L11684-11693): inclusive prefix sums.
pub(crate) fn prefix_sums_u64(words: &[u64]) -> Vec<u64> {
    let mut total: u64 = 0;
    words
        .iter()
        .map(|&word| {
            total = total.wrapping_add(word);
            total
        })
        .collect()
}

/// kit `rangeSumFromPrefix` (FirstPairSourceSlice.swift L11706-11715).
pub(crate) fn range_sum_from_prefix(prefix: &[u64], start: usize, end: usize) -> u64 {
    if start > end {
        return 0;
    }
    let total = prefix[end];
    if start == 0 {
        return total;
    }
    total.wrapping_sub(prefix[start - 1])
}

/// kit `builder6421c0X0Streams` (FirstPairSourceSlice.swift L1718-1754): 20 raw u64 + prefix.
pub fn builder6421c0_x0_streams(x0_source: &[u8], t: &FirstPairTables) -> Result<(Vec<u64>, Vec<u64>), CryptoError> {
    if x0_source.len() < 20 * 4 {
        return Err(slice_err(format!(
            "source too short: 6421c0 x0 source has {}, wants {}",
            x0_source.len(),
            20 * 4
        )));
    }
    let mut raw: Vec<u64> = Vec::with_capacity(20);
    for index in 0..20usize {
        let word = read_u32_le(x0_source, index * 4);
        let mixed: u32 = if index == 0 {
            word.wrapping_mul(0x3239bd21).wrapping_add(0x5c47f2f0)
        } else {
            let affine = u32_affine_63c278(
                word,
                index,
                BUILDER6421C0_X0_MUL_TABLE,
                BUILDER6421C0_X0_ADD_TABLE,
                t,
            )?;
            affine.wrapping_mul(0x5da2e52f).wrapping_add(0x6605175e)
        };
        let folded = fold_63c278(
            (mixed as u64)
                .wrapping_mul(0x430e55e51aa99355)
                .wrapping_add(0x15551dd776f38e14),
            BUILDER6421C0_X0_FOLD_TABLE,
            8,
            t,
        )?;
        raw.push(
            (mixed as u64)
                .wrapping_mul(0xc788d39836400f55)
                .wrapping_add(folded.wrapping_mul(0xd50b73ff00000000))
                .wrapping_add(0xce6055b08c097bf0),
        );
    }
    Ok((raw.clone(), prefix_sums_u64(&raw)))
}

/// kit `builder6421c0X1Streams` (FirstPairSourceSlice.swift L1756-1792): 22 raw u64 + prefix.
pub fn builder6421c0_x1_streams(x1_source: &[u8], t: &FirstPairTables) -> Result<(Vec<u64>, Vec<u64>), CryptoError> {
    if x1_source.len() < VEC_BYTES {
        return Err(slice_err(format!(
            "source too short: 6421c0 x1 source has {}, wants {VEC_BYTES}",
            x1_source.len()
        )));
    }
    let mut raw: Vec<u64> = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let word = read_u32_le(x1_source, index * 4);
        let mixed: u32 = if index == 0 {
            word.wrapping_mul(0x105085d7).wrapping_add(0x841874d8)
        } else {
            let affine = u32_affine_63c278(
                word,
                index,
                BUILDER6421C0_X1_MUL_TABLE,
                BUILDER6421C0_X1_ADD_TABLE,
                t,
            )?;
            affine.wrapping_mul(0x97c9fb77).wrapping_add(0x6b1b1a39)
        };
        let folded = fold_63c278(
            (mixed as u64)
                .wrapping_mul(0x8f1272d1ced32651)
                .wrapping_add(0x7eda487fd3a46989),
            BUILDER6421C0_X1_FOLD_TABLE,
            8,
            t,
        )?;
        raw.push(
            (mixed as u64)
                .wrapping_mul(0x9dcd2446c70edca3)
                .wrapping_add(folded.wrapping_mul(0xe9148d4d00000000))
                .wrapping_add(0x2df1f5e9fb0ab4f8),
        );
    }
    Ok((raw.clone(), prefix_sums_u64(&raw)))
}

/// kit `builder6421c0X2Words` (FirstPairSourceSlice.swift L1794-1824): 22 u64 words.
pub fn builder6421c0_x2_words(x2_source: &[u8], t: &FirstPairTables) -> Result<Vec<u64>, CryptoError> {
    if x2_source.len() < VEC_BYTES {
        return Err(slice_err(format!(
            "source too short: 6421c0 x2 source has {}, wants {VEC_BYTES}",
            x2_source.len()
        )));
    }
    let mut out: Vec<u64> = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let affine = u32_affine_63c278(
            read_u32_le(x2_source, index * 4),
            index,
            BUILDER6421C0_X2_MUL_TABLE,
            BUILDER6421C0_X2_ADD_TABLE,
            t,
        )?;
        let mixed = affine.wrapping_mul(0x6819ef77).wrapping_add(0x57cf46ce);
        let folded = fold_63c278(
            (mixed as u64)
                .wrapping_mul(0xc4e90084bd222fd1)
                .wrapping_add(0xf9e4937efa15a0b7),
            BUILDER6421C0_X2_FOLD_TABLE,
            8,
            t,
        )?;
        out.push(
            (mixed as u64)
                .wrapping_mul(0x06447e0a39c79467)
                .wrapping_add(folded.wrapping_mul(0xcfaf794900000000))
                .wrapping_add(0xe882bfc48de82700),
        );
    }
    Ok(out)
}

/// kit `builder6421c0ConvolutionWorkspace` (FirstPairSourceSlice.swift L1826-1872): the 352-byte
/// 64cd40 workspace as the x0/x1 convolution (44 u64 words, LE).
pub fn builder6421c0_convolution_workspace(
    x0_raw: &[u64],
    x0_prefix: &[u64],
    x1_raw: &[u64],
    x1_prefix: &[u64],
) -> Result<Vec<u8>, CryptoError> {
    if x0_raw.len() != 20 || x0_prefix.len() != 20 {
        return Err(slice_err(format!("63c278 vector word count {}", x0_raw.len())));
    }
    if x1_raw.len() != VEC_WORDS || x1_prefix.len() != VEC_WORDS {
        return Err(slice_err(format!("63c278 vector word count {}", x1_raw.len())));
    }
    let mut out = Vec::with_capacity(BUILDER64CD40_WORKSPACE_BYTES);
    for index in 0..BUILDER64CD40_WORKSPACE_WORDS {
        let low = index.saturating_sub(x1_raw.len() - 1);
        let high = index.min(x0_raw.len() - 1);
        let product_sum: u64;
        let x0_sum: u64;
        let x1_sum: u64;
        let count: u64;
        if high >= low {
            let mut sum: u64 = 0;
            for x0_index in low..=high {
                sum = sum.wrapping_add(x0_raw[x0_index].wrapping_mul(x1_raw[index - x0_index]));
            }
            product_sum = sum;
            x0_sum = range_sum_from_prefix(x0_prefix, low, high);
            x1_sum = range_sum_from_prefix(x1_prefix, index - high, index - low);
            count = (high - low + 1) as u64;
        } else {
            product_sum = 0;
            x0_sum = 0;
            x1_sum = 0;
            count = 0;
        }
        let mixed = count
            .wrapping_mul(0xdd9e6926c32c9984)
            .wrapping_add(0x7bf33cd7983bce3c)
            .wrapping_add(x0_sum.wrapping_mul(0xe703af65ab19ca84))
            .wrapping_add(product_sum.wrapping_mul(0xe6337be2ad0561b9))
            .wrapping_add(x1_sum.wrapping_mul(0x1cd6868a83aeef79));
        out.extend_from_slice(
            &mixed
                .wrapping_mul(0x2e60fd6d05fe470b)
                .wrapping_add(0xf48a714d4ddd3ee7)
                .to_le_bytes(),
        );
    }
    Ok(out)
}

/// kit `builder6421c0WorkspaceParams` (FirstPairSourceSlice.swift L10275-10296).
fn builder6421c0_workspace_params(
    scalar: u64,
    first_word: u64,
    t: &FirstPairTables,
) -> Result<(u64, u64), CryptoError> {
    let seed_a = scalar
        .wrapping_mul(0x5509a203390f347f)
        .wrapping_add(0x32f1fb0a9d874bf4);
    let seed_b = scalar
        .wrapping_mul(0x4c2221c00f3005fb)
        .wrapping_add(0x0ff14ba0b2a5c7ba);
    let mut mixed = first_word.wrapping_mul(seed_a).wrapping_add(seed_b);
    let folded = fold_63c278(
        mixed
            .wrapping_mul(0x473c6a74e974ae65)
            .wrapping_add(0xadebeda263d28433),
        BUILDER6421C0_WORKSPACE_FOLD_TABLE,
        7,
        t,
    )?;
    mixed = mixed
        .wrapping_mul(0xef65aceeafea45e9)
        .wrapping_add(folded.wrapping_mul(0x5fe62b0cb0000000))
        .wrapping_add(0xd7d1a2ac976837c3);
    Ok((
        mixed.wrapping_mul(0x9c52396943c088f7).wrapping_add(0x8983840ba934a2f1),
        mixed.wrapping_mul(0x0fd36815b245b0f2).wrapping_add(0x3aa2f36c3a09d43e),
    ))
}

/// kit `builder6421c0RewriteSecondWord` (FirstPairSourceSlice.swift L10298-10319).
fn builder6421c0_rewrite_second_word(
    first: u64,
    second: u64,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let folded = fold_63c278(
        first
            .wrapping_mul(0x12c340b4b411bb8d)
            .wrapping_add(0xab10f2a46110bceb),
        BUILDER6421C0_REWRITE_FOLD1_TABLE,
        7,
        t,
    )?;
    let mut mixed = folded
        .wrapping_mul(0xcfdc2f8d3b1f41e3)
        .wrapping_add(0x317484327c6f968a);
    let folded2 = fold_63c278(
        mixed
            .wrapping_mul(0xeefa3d8f20f54f35)
            .wrapping_add(0x483345b5f608f667),
        BUILDER6421C0_REWRITE_FOLD2_TABLE,
        9,
        t,
    )?;
    mixed = mixed
        .wrapping_mul(0x6b6283330fe2b923)
        .wrapping_add(folded2.wrapping_mul(0x6214609000000000));
    Ok(mixed
        .wrapping_mul(0x8d48d385aeebeb5d)
        .wrapping_add(second)
        .wrapping_add(0x71783af05ec8119f))
}

/// kit `builder6421c0WorkspaceAfterUpdate` (FirstPairSourceSlice.swift L1874-1906): the scalar
/// and x2 mix over the workspace (44 u64 words, LE).
pub fn builder6421c0_workspace_after_update(
    workspace: &[u8],
    x2_words: &[u64],
    scalar: u64,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if workspace.len() < BUILDER64CD40_WORKSPACE_BYTES {
        return Err(slice_err(format!(
            "source too short: 6421c0 workspace has {}, wants {BUILDER64CD40_WORKSPACE_BYTES}",
            workspace.len()
        )));
    }
    if x2_words.len() != VEC_WORDS {
        return Err(slice_err(format!("63c278 vector word count {}", x2_words.len())));
    }
    let mut words: Vec<u64> = (0..BUILDER64CD40_WORKSPACE_WORDS)
        .map(|i| read_u64_le(workspace, i * 8))
        .collect();

    for base in 0..VEC_WORDS {
        let (multiplier, broadcast) = builder6421c0_workspace_params(scalar, words[base], t)?;
        for (offset, &word) in x2_words.iter().enumerate() {
            let pos = base + offset;
            words[pos] = words[pos]
                .wrapping_add(broadcast)
                .wrapping_add(word.wrapping_mul(multiplier));
        }
        words[base + 1] = builder6421c0_rewrite_second_word(words[base], words[base + 1], t)?;
    }
    Ok(words.iter().flat_map(|w| w.to_le_bytes()).collect())
}

/// kit `builder6421c0FinalU32Words` (FirstPairSourceSlice.swift L1908-1953): workspace → 22 u32.
pub fn builder6421c0_final_u32_words(workspace: &[u8], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    if workspace.len() < BUILDER64CD40_WORKSPACE_BYTES {
        return Err(slice_err(format!(
            "source too short: 6421c0 workspace has {}, wants {BUILDER64CD40_WORKSPACE_BYTES}",
            workspace.len()
        )));
    }
    let words: Vec<u64> = (0..BUILDER64CD40_WORKSPACE_WORDS)
        .map(|i| read_u64_le(workspace, i * 8))
        .collect();
    let mut carry: u64 = 0x14ee1c03e369d629;
    let mut out: Vec<u32> = Vec::with_capacity(VEC_WORDS);

    for index in 0..VEC_WORDS {
        let tail_word = words[VEC_WORDS + index];
        let mixed = carry
            .wrapping_mul(0x0338c0e89dc8ee71)
            .wrapping_add(tail_word.wrapping_mul(0x32afeb8e00ff3e85))
            .wrapping_add(0xfc9f014fa6b572f5);
        let folded7 = fold_63c278(
            mixed
                .wrapping_mul(0xea4b89dcd43400c5)
                .wrapping_add(0x3ea3d75ac0581688),
            BUILDER6421C0_FINAL_FOLD_TABLE,
            7,
            t,
        )?;
        let side = ((mixed & 0xffff_ffff)
            .wrapping_mul(0x279eaf81)
            .wrapping_add((folded7 & 0xffff_ffff).wrapping_mul(0x30000000))
            .wrapping_add(0xac5f152c)) as u32;
        let folded = fold_63c278(folded7, BUILDER6421C0_FINAL_FOLD_TABLE, 9, t)?;
        carry = folded7
            .wrapping_mul(0x571b49fe43ec4f5d)
            .wrapping_add(folded.wrapping_mul(0xc13b0a3000000000))
            .wrapping_add(0x04e301c0d1003cfc);
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(
            side,
            BUILDER6421C0_FINAL_OUT_MUL_TABLE + table_offset,
            BUILDER6421C0_FINAL_OUT_ADD_TABLE + table_offset,
            t,
        )?);
    }
    Ok(out)
}

/// kit `builder6421c0OutputWords` (FirstPairSourceSlice.swift L1955-1976): the full 6421c0 path.
pub fn builder6421c0_output_words(
    x0_source: &[u8],
    x1_source: &[u8],
    x2_source: &[u8],
    scalar: u64,
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    let (x0_raw, x0_prefix) = builder6421c0_x0_streams(x0_source, t)?;
    let (x1_raw, x1_prefix) = builder6421c0_x1_streams(x1_source, t)?;
    let x2_words = builder6421c0_x2_words(x2_source, t)?;
    let workspace = builder6421c0_convolution_workspace(&x0_raw, &x0_prefix, &x1_raw, &x1_prefix)?;
    let updated = builder6421c0_workspace_after_update(&workspace, &x2_words, scalar, t)?;
    builder6421c0_final_u32_words(&updated, t)
}

// ---------------------------------------------------------------- high seeds from P-256

/// kit `builder6388f0HighSeedX0SourceFrom5bcf98Output` (FirstPairSourceSlice.swift L1978-2012):
/// 70-byte padded-LE coordinate → the 88-byte 6421c0 x0 source (28-bit repack + affine).
pub fn builder6388f0_high_seed_x0_source_from5bcf98_output(
    source70: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if source70.len() < 70 {
        return Err(slice_err(format!(
            "source too short: 6388f0 high x0 source input has {}, wants 70",
            source70.len()
        )));
    }
    let mut packed_words = [0u32; 18];
    for (index, &byte) in source70.iter().take(70).enumerate() {
        let word_index = index / 4;
        let shift = (index * 8) & 0x18;
        packed_words[word_index] |= (byte as u32) << shift;
    }

    let mut out_words: Vec<u32> = Vec::with_capacity(20);
    for index in 0..20usize {
        let bit_offset = index * 28;
        let word_index = bit_offset >> 5;
        let shift = bit_offset & 0x1c;
        let mut value = packed_words[word_index] >> shift;
        if shift != 0 {
            value |= packed_words[word_index + 1] << (32 - shift);
        }
        value &= 0x0fffffff;
        value = value.wrapping_mul(0x83dcb233).wrapping_add(0x774e86a1);
        out_words.push(u32_affine_63c278(
            value,
            index,
            BUILDER6388F0_HIGH_SEED_X0_SOURCE_MUL_TABLE,
            BUILDER6388F0_HIGH_SEED_X0_SOURCE_ADD_TABLE,
            t,
        )?);
    }
    Ok(out_words.iter().flat_map(|w| w.to_le_bytes()).collect())
}

/// kit `builder6388f0HighSeedStreamStartSeedsFrom5bcf98Outputs` (L2014-2040). `None` falls back
/// to the kit's fixed x1/x2/scalar statics (L2021-2023).
pub fn builder6388f0_high_seed_stream_start_seeds_from5bcf98_outputs(
    first_output70: &[u8],
    second_output70: &[u8],
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    t: &FirstPairTables,
) -> Result<Builder6388f0HighSeedStreamStartSeeds, CryptoError> {
    let resolved_x1: &[u8] = x1_source.unwrap_or(&HIGH_SEED_6421C0_X1_SOURCE);
    let resolved_x2: &[u8] = x2_source.unwrap_or(&HIGH_SEED_6421C0_X2_SOURCE);
    let resolved_scalar = scalar.unwrap_or(HIGH_SEED_6421C0_SCALAR);
    let out0 = builder6421c0_output_words(
        &builder6388f0_high_seed_x0_source_from5bcf98_output(first_output70, t)?,
        resolved_x1,
        resolved_x2,
        resolved_scalar,
        t,
    )?;
    let out1 = builder6421c0_output_words(
        &builder6388f0_high_seed_x0_source_from5bcf98_output(second_output70, t)?,
        resolved_x1,
        resolved_x2,
        resolved_scalar,
        t,
    )?;
    Ok(Builder6388f0HighSeedStreamStartSeeds {
        out0: out_words_bytes(&out0),
        out1: out_words_bytes(&out1),
    })
}

/// kit `packUInt32LE` (FirstPairSourceSlice.swift L10513-10520).
fn out_words_bytes(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|w| w.to_le_bytes()).collect()
}

/// kit `builder6388f0FirstPairHighSeedStreamStartSeedsFrom5bcf98Outputs` (L2042-2067).
pub fn builder6388f0_first_pair_high_seed_stream_start_seeds_from5bcf98_outputs(
    row0_first_output70: &[u8],
    row0_second_output70: &[u8],
    row59_first_output70: &[u8],
    row59_second_output70: &[u8],
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPairHighSeedStreamStartSeeds, CryptoError> {
    Ok(Builder6388f0FirstPairHighSeedStreamStartSeeds {
        row0: builder6388f0_high_seed_stream_start_seeds_from5bcf98_outputs(
            row0_first_output70,
            row0_second_output70,
            x1_source,
            x2_source,
            scalar,
            t,
        )?,
        row59: builder6388f0_high_seed_stream_start_seeds_from5bcf98_outputs(
            row59_first_output70,
            row59_second_output70,
            x1_source,
            x2_source,
            scalar,
            t,
        )?,
    })
}

// ---------------------------------------------------------------- seeds assembly

/// kit `builder6388f0FirstPairStreamSeedsFrom5bcf98Outputs` (FirstPairSourceSlice.swift
/// L2069-2107): row0Out4/Out3/Out2 arrive as inputs here — the low-seed layer that produces
/// them is a later stage.
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_first_pair_stream_seeds_from5bcf98_outputs(
    row0_out4: &[u8],
    row0_out3: &[u8],
    row0_out2: &[u8],
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
    let high_seeds = builder6388f0_first_pair_high_seed_stream_start_seeds_from5bcf98_outputs(
        row0_first_output70,
        row0_second_output70,
        row59_first_output70,
        row59_second_output70,
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
        row0_out4: row0_out4.to_vec(),
        row0_out3: row0_out3.to_vec(),
        row0_out2: row0_out2.to_vec(),
        row0_out1: high_seeds.row0.out1,
        row0_out0: high_seeds.row0.out0,
        row59_out1: high_seeds.row59.out1,
        row59_out0: high_seeds.row59.out0,
    })
}

// ---------------------------------------------------------------- stream-start inputs

/// kit `builder6388f0StreamStart642f60Inputs` (FirstPairSourceSlice.swift L3727-3745).
pub fn builder6388f0_stream_start642f60_inputs(
    out0_seed: &[u8],
    out1_seed: &[u8],
    x2_source: Option<&[u8]>,
    t: &FirstPairTables,
) -> Result<Builder6388f0Next642f60Inputs, CryptoError> {
    let resolved_x2: &[u8] = x2_source.unwrap_or(&STREAM_START_642F60_X2_SOURCE);
    if resolved_x2.len() < VEC_BYTES {
        return Err(slice_err(format!(
            "source too short: 6388f0 stream-start 642f60 x2 source has {}, wants {VEC_BYTES}",
            resolved_x2.len()
        )));
    }
    Ok(Builder6388f0Next642f60Inputs {
        x0: builder6388f0_stream_start642f60_x0_from_out0_seed(out0_seed, t)?,
        x1: builder6388f0_stream_start642f60_x1_from_out1_seed(out1_seed, t)?,
        x2: resolved_x2[..VEC_BYTES].to_vec(),
    })
}

/// kit `builder6388f0FirstPair642f60StreamStarts` (row/seeds overload, L3747-3766).
pub fn builder6388f0_first_pair642f60_stream_starts(
    row0_out0_seed: &[u8],
    row0_out1_seed: &[u8],
    row59_out0_seed: &[u8],
    row59_out1_seed: &[u8],
    x2_source: Option<&[u8]>,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPair642f60Starts, CryptoError> {
    Ok(Builder6388f0FirstPair642f60Starts {
        row0: builder6388f0_stream_start642f60_inputs(row0_out0_seed, row0_out1_seed, x2_source, t)?,
        row59: builder6388f0_stream_start642f60_inputs(row59_out0_seed, row59_out1_seed, x2_source, t)?,
    })
}

/// kit `builder6388f0FirstPair642f60StreamStarts(seeds:x2Source:)` (L3768-3779).
pub fn builder6388f0_first_pair642f60_stream_starts_from_seeds(
    seeds: &Builder6388f0FirstPairStreamSeeds,
    x2_source: Option<&[u8]>,
    t: &FirstPairTables,
) -> Result<Builder6388f0FirstPair642f60Starts, CryptoError> {
    builder6388f0_first_pair642f60_stream_starts(
        &seeds.row0_out0,
        &seeds.row0_out1,
        &seeds.row59_out0,
        &seeds.row59_out1,
        x2_source,
        t,
    )
}

// ---------------------------------------------------------------- caller context

/// kit `builder6388f0SharedContextFromBundle` (FirstPairSourceSlice.swift L3781-3790). The size
/// guard is carried by the FirstPairTables SPEC (`0x520`, kit L14035).
pub fn builder6388f0_shared_context_from_bundle(t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    Ok(t.shared_context_6388f0.clone())
}

/// kit `builder6388f0CallerLoopTablesFromBundle` (FirstPairSourceSlice.swift L3792-3815):
/// de-interleave the bundle table into first/second, 59 rows of 2×0x58 bytes.
pub fn builder6388f0_caller_loop_tables_from_bundle(
    t: &FirstPairTables,
) -> Result<Builder6388f0CallerLoopTables, CryptoError> {
    let interleaved = &t.caller_loop_interleaved_6388f0;
    if interleaved.len() != BUILDER6388F0_CALLER_LOOP_TABLE_ROWS * BUILDER6388F0_CALLER_LOOP_INTERLEAVED_ROW_BYTES {
        return Err(slice_err(format!(
            "invalid table size: firstpair_6388f0_caller_loop_interleaved_2cdfa9 has {}",
            interleaved.len()
        )));
    }

    let mut first = Vec::with_capacity(BUILDER6388F0_CALLER_LOOP_TABLE_BYTES);
    let mut second = Vec::with_capacity(BUILDER6388F0_CALLER_LOOP_TABLE_BYTES);
    for row in 0..BUILDER6388F0_CALLER_LOOP_TABLE_ROWS {
        let row_offset = row * BUILDER6388F0_CALLER_LOOP_INTERLEAVED_ROW_BYTES;
        first.extend_from_slice(&interleaved[row_offset..row_offset + BUILDER6388F0_CALLER_LOOP_ROW_BYTES]);
        second.extend_from_slice(
            &interleaved[row_offset + BUILDER6388F0_CALLER_LOOP_ROW_BYTES
                ..row_offset + BUILDER6388F0_CALLER_LOOP_INTERLEAVED_ROW_BYTES],
        );
    }
    Ok(Builder6388f0CallerLoopTables { first, second })
}

/// kit `builder6388f0CallerContextFromLoopTables` (FirstPairSourceSlice.swift L3817-3849).
pub fn builder6388f0_caller_context_from_loop_tables(
    loop_tables: &Builder6388f0CallerLoopTables,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if loop_tables.first.len() < BUILDER6388F0_CALLER_LOOP_TABLE_BYTES {
        return Err(slice_err(format!(
            "source too short: 6388f0 caller loop table 1 has {}, wants {BUILDER6388F0_CALLER_LOOP_TABLE_BYTES}",
            loop_tables.first.len()
        )));
    }
    if loop_tables.second.len() < BUILDER6388F0_CALLER_LOOP_TABLE_BYTES {
        return Err(slice_err(format!(
            "source too short: 6388f0 caller loop table 2 has {}, wants {BUILDER6388F0_CALLER_LOOP_TABLE_BYTES}",
            loop_tables.second.len()
        )));
    }

    let shared = builder6388f0_shared_context_from_bundle(t)?;
    let mut context = vec![0u8; BUILDER6388F0_CALLER_CONTEXT_LENGTH];
    replace_at(&mut context, 0, &shared);
    replace_at(
        &mut context,
        BUILDER6388F0_CALLER_LOOP_TABLE1_CONTEXT_OFFSET,
        &loop_tables.first[..BUILDER6388F0_CALLER_LOOP_TABLE_BYTES],
    );
    replace_at(
        &mut context,
        BUILDER6388F0_CALLER_LOOP_TABLE2_CONTEXT_OFFSET,
        &loop_tables.second[..BUILDER6388F0_CALLER_LOOP_TABLE_BYTES],
    );
    Ok(context)
}

/// kit `builder6388f0CallerContextFromBundle` (FirstPairSourceSlice.swift L3851-3853).
pub fn builder6388f0_caller_context_from_bundle(t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    builder6388f0_caller_context_from_loop_tables(&builder6388f0_caller_loop_tables_from_bundle(t)?, t)
}

// ---------------------------------------------------------------- P-256 wrapper

/// kit `builder5bcf98P256Outputs` (P256ScalarMultiplier.swift L4-26): scalar window × sensor
/// point, coordinates as padded-70 LE. Delegates to the already-ported P-256 multiplier.
pub fn builder5bcf98_p256_outputs(
    scalar_window_le: &[u8],
    sensor_point_xy_be: &[u8],
) -> Result<Builder5bcf98P256Outputs, CryptoError> {
    if scalar_window_le.len() < 70 {
        return Err(slice_err(format!("invalidP256ScalarLength({})", scalar_window_le.len())));
    }
    if sensor_point_xy_be.len() < 64 {
        return Err(slice_err(format!("invalidP256PointLength({})", sensor_point_xy_be.len())));
    }
    let (x_output70, y_output70) = crate::p256::multiply_padded_70(scalar_window_le, sensor_point_xy_be)?;
    Ok(Builder5bcf98P256Outputs { x_output70, y_output70 })
}

/// kit `builder6388f0HighSeedStreamStartSeedsFromScalarP256` (P256ScalarMultiplier.swift
/// L28-46): the 5bcf98 P-256 wrapper feeding the high-seed stream-start path.
pub fn builder6388f0_high_seed_stream_start_seeds_from_scalar_p256(
    scalar_window_le: &[u8],
    sensor_point_xy_be: &[u8],
    x1_source: Option<&[u8]>,
    x2_source: Option<&[u8]>,
    scalar: Option<u64>,
    t: &FirstPairTables,
) -> Result<Builder6388f0HighSeedStreamStartSeeds, CryptoError> {
    let outputs = builder5bcf98_p256_outputs(scalar_window_le, sensor_point_xy_be)?;
    builder6388f0_high_seed_stream_start_seeds_from5bcf98_outputs(
        &outputs.x_output70,
        &outputs.y_output70,
        x1_source,
        x2_source,
        scalar,
        t,
    )
}

// ---------------------------------------------------------------- private stream helpers
// (FirstPairSourceSlice.swift L10980-11081.) These belong to the 6388f0 caller-loop layer
// (a later stage); ported now because this layer's tests exercise the convolution + u64
// stream primitives they share. Not yet referenced by any public fn here — kept `pub` for
// the follow-up stages, mirroring the kit's file layout.

/// kit `builder6388f0CallerStreamU64` (L10980-10999).
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_caller_stream_u64(
    word: u32,
    word_mul: u64,
    word_add: u64,
    fold_table: usize,
    fold_mul: u64,
    mix_mul: u64,
    mix_add: u64,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let folded = fold_63c278(
        (word as u64).wrapping_mul(word_mul).wrapping_add(word_add),
        fold_table,
        8,
        t,
    )?;
    Ok(folded
        .wrapping_mul(fold_mul)
        .wrapping_add((word as u64).wrapping_mul(mix_mul))
        .wrapping_add(mix_add))
}

/// kit `fold63c278FirstNibbleBeforeAdd` (FirstPairSourceSlice.swift L12798-12813): one fold
/// round whose shift uses (product + addend) >> 4 before the remaining 7 rounds.
/// Shared with the 642f60 caller layer's third-workspace word builders.
pub(crate) fn fold63c278_first_nibble_before_add(
    product: u64,
    addend: u64,
    table_offset: usize,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let relative = table_offset - super::schedule::FOLD_BASE + ((product & 0x0f) as usize) * 8;
    if relative + 8 > t.fold_tables_63c278.len() {
        return Err(slice_err(format!(
            "table read out of bounds: firstpair_63c278_fold_tables_2feb18 at {table_offset}"
        )));
    }
    let folded = super::schedule::read_fold_u64_le_63c278(&t.fold_tables_63c278, relative)
        .wrapping_add((product.wrapping_add(addend)) >> 4);
    fold_63c278(folded, table_offset, 7, t)
}

/// kit `builder6388f0CallerStreamU64FirstNibbleBeforeAdd` (L11001-11021).
#[allow(clippy::too_many_arguments)]
pub fn builder6388f0_caller_stream_u64_first_nibble_before_add(
    word: u32,
    word_mul: u64,
    word_add: u64,
    fold_table: usize,
    fold_mul: u64,
    mix_mul: u64,
    mix_add: u64,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let product = (word as u64).wrapping_mul(word_mul);
    let folded = fold63c278_first_nibble_before_add(product, word_add, fold_table, t)?;
    Ok(folded
        .wrapping_mul(fold_mul)
        .wrapping_add((word as u64).wrapping_mul(mix_mul))
        .wrapping_add(mix_add))
}

/// kit `builder6388f0Convolution44` (FirstPairSourceSlice.swift L11023-11081): 44-word x0/x1
/// convolution written into a caller stack window.
pub fn builder6388f0_convolution44(
    stack: &mut [u8],
    a_vec_offset: usize,
    a_prefix_offset: usize,
    b_vec_offset: usize,
    b_prefix_offset: usize,
    out_offset: usize,
    constants: Builder6388f0Convolution44Constants,
) {
    let a_vec: Vec<u64> = (0..VEC_WORDS).map(|i| read_u64_le(stack, a_vec_offset + i * 8)).collect();
    let a_prefix: Vec<u64> = (0..VEC_WORDS).map(|i| read_u64_le(stack, a_prefix_offset + i * 8)).collect();
    let b_vec: Vec<u64> = (0..VEC_WORDS).map(|i| read_u64_le(stack, b_vec_offset + i * 8)).collect();
    let b_prefix: Vec<u64> = (0..VEC_WORDS).map(|i| read_u64_le(stack, b_prefix_offset + i * 8)).collect();

    for index in 0..BUILDER64CD40_WORKSPACE_WORDS {
        let low = index.saturating_sub(VEC_WORDS - 1);
        let high = index.min(VEC_WORDS - 1);
        if low > high {
            let value = constants
                .count_add
                .wrapping_mul(constants.final_mul)
                .wrapping_add(constants.final_add);
            write_u64_le(value, stack, out_offset + index * 8);
            continue;
        }

        let mut product_sum: u64 = 0;
        for b_index in low..=high {
            product_sum = product_sum
                .wrapping_add(a_vec[index - b_index].wrapping_mul(b_vec[b_index]));
        }

        let mut a_sum = a_prefix[index - low];
        if index > high {
            a_sum = a_sum.wrapping_sub(a_prefix[index - high - 1]);
        }

        let mut b_sum = b_prefix[high];
        if low > 0 {
            b_sum = b_sum.wrapping_sub(b_prefix[low - 1]);
        }

        let count = (high - low + 1) as u64;
        let mut out = count
            .wrapping_mul(constants.count_mul)
            .wrapping_add(constants.count_add);
        out = out.wrapping_add(product_sum.wrapping_mul(constants.product_mul));
        out = out.wrapping_add(b_sum.wrapping_mul(constants.b_prefix_mul));
        out = out.wrapping_add(a_sum.wrapping_mul(constants.a_prefix_mul));
        out = out.wrapping_mul(constants.final_mul).wrapping_add(constants.final_add);
        write_u64_le(out, stack, out_offset + index * 8);
    }
}

/// kit `builder6388f0Convolution44` constants tuple (FirstPairSourceSlice.swift L11030-11038).
#[derive(Clone, Copy, Debug)]
pub struct Builder6388f0Convolution44Constants {
    pub count_mul: u64,
    pub count_add: u64,
    pub product_mul: u64,
    pub b_prefix_mul: u64,
    pub a_prefix_mul: u64,
    pub final_mul: u64,
    pub final_add: u64,
}
