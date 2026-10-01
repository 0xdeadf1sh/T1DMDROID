//! FirstPairSourceSlice — the 63c278 schedule builder and the 6388f0 lane→pack→stage→
//! prefinal→final stack (kit: FirstPairSourceSlice.swift). The 63c278 schedule turns
//! (arg0, arg1/arg2, scalar) into the 20 schedule words that drive the 6388f0 lane
//! machinery; the lane stack narrows two 20-block lane streams into the encoded 66-byte
//! blocks consumed by the landed 679f48/64de54 derivation chain.

use super::firstpair::*;
use super::tables::FirstPairTables;
use crate::CryptoError;

pub(crate) const VEC_WORDS: usize = 22;
pub(crate) const VEC_BYTES: usize = VEC_WORDS * 4;
const LANE_BLOCK_COUNT: usize = 20;
const LANE_BLOCK_SIZE: usize = 0x10;
const LANE_TABLE_PACKED_ROW: usize = 9;
const LANE_TABLE_EXPANDED: usize = 18;
const LANE_A_TABLE_OFFSET: usize = 0x0000;
const LANE_B_TABLE_OFFSET: usize = 0x0900;
const LANE_A_INIT_OFFSET: usize = 0x1200;
const LANE_B_INIT_OFFSET: usize = 0x1212;
const LANE_PRIMARY_STATIC_OFFSET: usize = 0x1224;
const LANE_SECONDARY_STATIC_OFFSET: usize = 0x1236;
const WS_SIZE: usize = 0x10a; // 266
const STAGE_SIZE: usize = 0x11a; // 282
const U32_BASE: usize = 0x112588;
pub(crate) const FOLD_BASE: usize = 0x2feb18;

/// kit `pre63c278Arg0Source` + `pre63c278Scalar`: the fixed arg0/scalar of the pre-63c278 path.
pub const PRE63C278_ARG0_SOURCE: [u8; 88] = [
    0x21, 0xed, 0x7e, 0x8f, 0xc9, 0x86, 0x29, 0x76, 0xac, 0x50, 0xb4, 0xcb, 0x1e, 0x31, 0xa9, 0x1f,
    0x30, 0xfa, 0x05, 0xc7, 0x06, 0x82, 0xac, 0x26, 0xbc, 0x7d, 0xb7, 0x62, 0x19, 0xfd, 0x1d, 0x35,
    0x21, 0xed, 0x7e, 0x8f, 0xb9, 0x8b, 0xbe, 0x51, 0xa3, 0x76, 0x9d, 0xa0, 0xc5, 0x08, 0x6c, 0x23,
    0x30, 0xfa, 0x05, 0xc7, 0x06, 0x82, 0xac, 0x26, 0xbc, 0x7d, 0xb7, 0xd1, 0x19, 0xfd, 0x1d, 0x35,
    0x46, 0x68, 0x3b, 0x2a, 0x18, 0xd7, 0xe2, 0xe2, 0xa3, 0x76, 0x9d, 0xa0, 0xc5, 0x08, 0x6c, 0x23,
    0x30, 0xfa, 0x05, 0xc7, 0x06, 0x82, 0xac, 0x26,
];
pub const PRE63C278_SCALAR: u64 = 0x33b7dca8cdf2d720;

fn vec_err(count: usize) -> CryptoError {
    slice_err(format!("63c278 vector word count {count} != {VEC_WORDS}"))
}

fn require_vec(words: &[u32]) -> Result<(), CryptoError> {
    if words.len() != VEC_WORDS {
        return Err(vec_err(words.len()));
    }
    Ok(())
}

// ---------------------------------------------------------------- table helpers

pub(crate) fn u32_table_word_63c278(absolute_offset: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let relative = absolute_offset
        .checked_sub(U32_BASE)
        .ok_or_else(|| slice_err(format!("63c278 u32 table read out of bounds at {absolute_offset:#x}")))?;
    let end = relative + 4;
    if end > t.u32_tables_63c278.len() {
        return Err(slice_err(format!("63c278 u32 table read out of bounds at {absolute_offset:#x}")));
    }
    Ok(read_u32_le(&t.u32_tables_63c278, relative))
}

pub(crate) fn fold_table_u32_word_63c278(absolute_offset: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let relative = absolute_offset
        .checked_sub(FOLD_BASE)
        .ok_or_else(|| slice_err(format!("63c278 fold table read at {absolute_offset:#x}")))?;
    if relative + 4 > t.fold_tables_63c278.len() {
        return Err(slice_err(format!("63c278 fold table read out of bounds at {absolute_offset:#x}")));
    }
    Ok(read_u32_le(&t.fold_tables_63c278, relative))
}

pub(crate) fn fold_table_u64_word_63c278(absolute_offset: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let relative = absolute_offset
        .checked_sub(FOLD_BASE)
        .ok_or_else(|| slice_err(format!("63c278 fold table read at {absolute_offset:#x}")))?;
    if relative + 8 > t.fold_tables_63c278.len() {
        return Err(slice_err(format!("63c278 fold table read out of bounds at {absolute_offset:#x}")));
    }
    Ok(read_u64_le(&t.fold_tables_63c278, relative))
}

pub(crate) fn u32_affine_63c278(word: u32, index: usize, mul_table: usize, add_table: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let table_offset = (index * 4) & 0x1c;
    let multiplier = u32_table_word_63c278(mul_table + table_offset, t)?;
    let addend = u32_table_word_63c278(add_table + table_offset, t)?;
    Ok(((word as u64).wrapping_mul(multiplier as u64).wrapping_add(addend as u64)) as u32)
}

pub(crate) fn u32_affine_inverse_63c278(word: u32, index: usize, mul_table: usize, add_table: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let table_offset = (index * 4) & 0x1c;
    let multiplier = u32_table_word_63c278(mul_table + table_offset, t)?;
    if multiplier & 1 != 1 {
        return Err(slice_err(format!("non-invertible affine multiplier {multiplier:#x}")));
    }
    let addend = u32_table_word_63c278(add_table + table_offset, t)?;
    let inverse = modular_inverse_odd_u32(multiplier);
    Ok(word.wrapping_sub(addend).wrapping_mul(inverse))
}

pub(crate) fn u32_table_affine_63c278(word: u32, mul_table: usize, add_table: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let multiplier = u32_table_word_63c278(mul_table, t)?;
    let addend = u32_table_word_63c278(add_table, t)?;
    Ok(((word as u64).wrapping_mul(multiplier as u64).wrapping_add(addend as u64)) as u32)
}

/// kit `u32AffineInverseBytes63c278` (FirstPairSourceSlice.swift L11779-11803): per-word affine
/// inverse. Consumed by the 642f60 stage (kit keeps it private next to its word primitive).
#[allow(dead_code)]
pub(crate) fn u32_affine_inverse_bytes_63c278(
    input: &[u8],
    mul_table: usize,
    add_table: usize,
    label: &str,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    require(input, VEC_BYTES, label)?;
    let mut out = Vec::with_capacity(VEC_BYTES);
    for index in 0..VEC_WORDS {
        let word = u32_affine_inverse_63c278(read_u32_le(input, index * 4), index, mul_table, add_table, t)?;
        out.extend_from_slice(&word.to_le_bytes());
    }
    Ok(out)
}

/// Newton-Raphson inverse mod 2^32 (kit `modularInverseOddUInt32`).
fn modular_inverse_odd_u32(value: u32) -> u32 {
    let mut inverse = value;
    for _ in 0..5 {
        inverse = inverse.wrapping_mul(2u32.wrapping_sub(value.wrapping_mul(inverse)));
    }
    inverse
}

pub(crate) fn fold_63c278(value: u64, table_offset: usize, rounds: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let mut folded = value;
    for _ in 0..rounds {
        let relative = (table_offset - FOLD_BASE) + ((folded & 0x0f) as usize) * 8;
        if relative + 8 > t.fold_tables_63c278.len() {
            return Err(slice_err(format!("63c278 fold table read out of bounds at {table_offset:#x}")));
        }
        folded = read_u64_le(&t.fold_tables_63c278, relative).wrapping_add(folded >> 4);
    }
    Ok(folded)
}

pub(crate) fn fold32_by_nibbles_63c278(value: u32, table_offset: usize, rounds: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let mut folded = value;
    for _ in 0..rounds {
        let word = fold_table_u32_word_63c278(table_offset + ((folded & 0x0f) as usize) * 4, t)?;
        folded = word.wrapping_add(folded >> 4);
    }
    Ok(folded)
}

/// kit `fold63c278` read primitive: LE u64 word from the 63c278 fold tables at an already
/// de-based relative offset (shared by the high-seed layer's first-nibble fold).
pub(crate) fn read_fold_u64_le_63c278(fold_tables: &[u8], relative: usize) -> u64 {
    read_u64_le(fold_tables, relative)
}

fn arg0_words_63c278(arg0: &[u8]) -> Result<Vec<u32>, CryptoError> {
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    Ok((0..VEC_WORDS).map(|i| read_u32_le(arg0, i * 4)).collect())
}

// ---------------------------------------------------------------- X words / seeds

fn x1_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w0 = u32_affine_63c278(word, index, 0x115488, 0x121908, t)?;
    let w = w0.wrapping_mul(0x30316c9d).wrapping_add(0xe533e221);
    let mut folded = (w as u64).wrapping_mul(0x74ddf8a53c239deb).wrapping_add(0xc98ef94d2aa6d2f9);
    folded = fold_63c278(folded, 0x301770, 8, t)?;
    folded = folded.wrapping_mul(0xff444fcf00000000);
    Ok((w as u64).wrapping_mul(0xdda5a8135a0bc9fb).wrapping_add(folded).wrapping_add(0x8031c96ed30bf85e))
}

fn x0_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w0 = u32_affine_63c278(word, index, 0x11a9a8, 0x122aa8, t)?;
    let w = w0.wrapping_mul(0x707fe555).wrapping_add(0x1d759ee3);
    let mut folded = (w as u64).wrapping_mul(0xc7e623dc4156435d).wrapping_add(0xa7268272249650e4);
    folded = fold_63c278(folded, 0x3017f0, 8, t)?;
    folded = folded.wrapping_mul(0xd70f3ef300000000);
    Ok((w as u64).wrapping_mul(0x1defa278095a88b9).wrapping_add(folded).wrapping_add(0xc8066dafe659e3dd))
}

fn x2_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w0 = u32_affine_63c278(word, index, 0x11f588, 0x123488, t)?;
    let w = w0.wrapping_mul(0xe41d161f).wrapping_add(0xb12fcee1);
    let mut folded = (w as u64).wrapping_mul(0xf3402af2c5c78103).wrapping_add(0x81b5a02882be6230);
    folded = fold_63c278(folded, 0x301a70, 8, t)?;
    folded = folded.wrapping_mul(0xc69af5ab00000000);
    Ok((w as u64).wrapping_mul(0x057da4120776f3ff).wrapping_add(folded).wrapping_add(0x7d7d6bb0e7cd07d3))
}

fn x0b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w0 = u32_affine_63c278(word, index, 0x118f28, 0x118548, t)?;
    let w = w0.wrapping_mul(0x4dce977f).wrapping_add(0x7275db64);
    let mut folded = (w as u64).wrapping_mul(0x3125dbf4f55c0c6d).wrapping_add(0x1167036e8591663c);
    folded = fold_63c278(folded, 0x301af0, 8, t)?;
    folded = folded.wrapping_mul(0xee1902df00000000);
    Ok((w as u64).wrapping_mul(0x41108caa0013530d).wrapping_add(folded).wrapping_add(0x2ce8cc914f903207))
}

fn accum_a_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w0 = u32_affine_63c278(word, index, 0x11a9c8, 0x120f28, t)?;
    let w = w0.wrapping_mul(0x2545ee53).wrapping_add(0xf74fe193);
    let mut folded = (w as u64).wrapping_mul(0x69289ee9a98801f5).wrapping_add(0x89bfbb0b1b21e854);
    folded = fold_63c278(folded, 0x301d70, 8, t)?;
    Ok((w as u64)
        .wrapping_mul(0x12f7e0136d4dad87)
        .wrapping_add(folded.wrapping_mul(0x9917c7f500000000))
        .wrapping_add(0xf80d0f670554b0a4))
}

fn accum_b_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w0 = u32_affine_63c278(word, index, 0x11bbe8, 0x121948, t)?;
    let w = w0.wrapping_mul(0x7dd1ecf7).wrapping_add(0xdc8c9dae);
    let mut folded = (w as u64).wrapping_mul(0xf13beb213918d361).wrapping_add(0xa1220647c9883100);
    folded = fold_63c278(folded, 0x301df0, 8, t)?;
    Ok((w as u64)
        .wrapping_mul(0x952e2be9091d60c7)
        .wrapping_add(folded.wrapping_mul(0xd89eb2d900000000))
        .wrapping_add(0x54e3b2cc004948be))
}

fn bridge_x0_word(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let w0 = u32_affine_63c278(word, index, 0x11cd88, 0x112e48, t)?;
    let w = w0.wrapping_mul(0x1c8f15cf).wrapping_add(0x05d1107b);
    let mut folded = (w as u64).wrapping_mul(0x19d189b1be9d480b).wrapping_add(0xd2bafb34c1909b26);
    folded = fold_63c278(folded, 0x301e70, 8, t)?;
    folded = folded.wrapping_mul(0x2ece929d00000000);
    Ok((w as u64).wrapping_mul(0x7529d4f2739a8b41).wrapping_add(folded).wrapping_add(0xdb7158ce45fcb750))
}

// ---------------------------------------------------------------- public schedule steps

/// kit `builder63c278InitialVectors`: x1(44) = 22 table words + 22×0xb7059a553c133489, x0(22).
pub fn initial_vectors_63c278(arg0: &[u8], arg1: &[u8], t: &FirstPairTables) -> Result<(Vec<u64>, Vec<u64>), CryptoError> {
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    require(arg1, VEC_BYTES, "63c278 arg1")?;
    let mut x1: Vec<u64> = Vec::with_capacity(44);
    for index in 0..VEC_WORDS {
        x1.push(x1_word(read_u32_le(arg1, index * 4), index, t)?);
    }
    x1.extend(std::iter::repeat(0xb7059a553c133489).take(VEC_WORDS));
    let mut x0: Vec<u64> = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        x0.push(x0_word(read_u32_le(arg0, index * 4), index, t)?);
    }
    Ok((x1, x0))
}

/// kit `builder63c278SecondInitialVectors`: x2(44) = 22 table words + 22×0x9a6e0b3eab651f3d, x0(22).
pub fn second_initial_vectors_63c278(arg0: &[u8], arg2: &[u8], t: &FirstPairTables) -> Result<(Vec<u64>, Vec<u64>), CryptoError> {
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    require(arg2, VEC_BYTES, "63c278 arg2")?;
    let mut x2: Vec<u64> = Vec::with_capacity(44);
    for index in 0..VEC_WORDS {
        x2.push(x2_word(read_u32_le(arg2, index * 4), index, t)?);
    }
    x2.extend(std::iter::repeat(0x9a6e0b3eab651f3d).take(VEC_WORDS));
    let mut x0: Vec<u64> = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        x0.push(x0b_word(read_u32_le(arg0, index * 4), index, t)?);
    }
    Ok((x2, x0))
}

fn mix_seed(carry: u64, scalar_mul: u64, scalar_add: u64, t: &FirstPairTables) -> Result<(u64, u64), CryptoError> {
    let mixed = carry.wrapping_mul(scalar_mul).wrapping_add(scalar_add);
    let mut folded = mixed.wrapping_mul(0xe56ee0d2dabe3103).wrapping_add(0xe1a57f65c01b39ac);
    folded = fold_63c278(folded, 0x301870, 7, t)?;
    folded = folded.wrapping_mul(0x43cf3bc9b0000000);
    let seed = mixed.wrapping_mul(0x47b2ca50a9011f2f).wrapping_add(folded).wrapping_add(0xa9ccf36f06c69525);
    Ok((
        seed.wrapping_mul(0x707f1c911d72472d).wrapping_add(0x20d7bce79675ce2e),
        seed.wrapping_mul(0x62d17dd555b3e7b5).wrapping_add(0xa95e929c3eca7e5e),
    ))
}

fn next_carry(updated_first: u64, updated_second: u64, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let mut folded = updated_first.wrapping_mul(0x500a38540d22b25b).wrapping_add(0xae9b83bb74900f1e);
    folded = fold_63c278(folded, 0x3018f0, 7, t)?;
    let carry_mix = folded.wrapping_mul(0x6e12b4b0721da33b).wrapping_add(0x15fb45ff71081e4e);
    let mut folded2 = carry_mix.wrapping_mul(0xb926d0a2f88df903).wrapping_add(0x0931eca912f88a4c7);
    folded2 = fold_63c278(folded2, 0x301970, 9, t)?;
    folded2 = folded2.wrapping_mul(0x30cbc3f000000000);
    let mixed2 = carry_mix.wrapping_mul(0x025241c2cd0d8443).wrapping_add(folded2);
    Ok(mixed2.wrapping_mul(0x8074fb50d5400883).wrapping_add(updated_second).wrapping_add(0x9a2a45734b3e5fb0))
}

fn mix2_seed(carry: u64, scalar_mul: u64, scalar_add: u64, t: &FirstPairTables) -> Result<(u64, u64), CryptoError> {
    let mixed = carry.wrapping_mul(scalar_mul).wrapping_add(scalar_add);
    let mut folded = mixed.wrapping_mul(0x126e65dcb0b83de1).wrapping_add(0x5454202b530d9481);
    folded = fold_63c278(folded, 0x301b70, 7, t)?;
    folded = folded.wrapping_mul(0x3d2ffccf90000000);
    let seed = mixed.wrapping_mul(0x4c89449a165d8427).wrapping_add(folded).wrapping_add(0x654ba76b767a427c);
    Ok((
        seed.wrapping_mul(0x564d78f55b5eefab).wrapping_add(0xf24aa781d14548f5),
        seed.wrapping_mul(0xeb7cfc7c768d163c).wrapping_add(0x09afd4171a0c7a44),
    ))
}

fn next_carry2(updated_first: u64, updated_second: u64, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let mut folded = updated_first.wrapping_mul(0xbca4dd7019310b05).wrapping_add(0x088f442397943c2a);
    folded = fold_63c278(folded, 0x301bf0, 7, t)?;
    let carry_mix = folded.wrapping_mul(0x727c48215454885b).wrapping_add(0xcaf590adfa7e603b);
    let mut folded2 = carry_mix.wrapping_mul(0xfb3565409b501139).wrapping_add(0x74b39dd74e3ac2ed);
    folded2 = fold_63c278(folded2, 0x301c70, 9, t)?;
    folded2 = folded2.wrapping_mul(0x0081c49000000000);
    let mixed2 = carry_mix.wrapping_mul(0xf4d598a4fa80dabf).wrapping_add(folded2);
    Ok(mixed2.wrapping_mul(0x0d5f48e79ddef1c9).wrapping_add(updated_second).wrapping_add(0x9506d95873fe6ec8))
}

/// kit `builder63c278ScalarMixVector`: 44-word mix over a 22-word x0.
pub fn scalar_mix_vector_63c278(x1_vec44: &[u64], x0_vec22: &[u64], scalar: u64, t: &FirstPairTables) -> Result<Vec<u64>, CryptoError> {
    if x1_vec44.len() != 44 || x0_vec22.len() != VEC_WORDS {
        return Err(vec_err(x1_vec44.len()));
    }
    let mut vec = x1_vec44.to_vec();
    let scalar_mul = scalar.wrapping_mul(0xc2f49ab55607d661).wrapping_add(0x5cd21b4822401581);
    let scalar_add = scalar.wrapping_mul(0x31979e72b90f9217).wrapping_add(0x3a834f793d8d50d2);
    let mut carry = vec[0];
    for index in 0..VEC_WORDS {
        let (update_mul, lane_add) = mix_seed(carry, scalar_mul, scalar_add, t)?;
        for lane in 0..VEC_WORDS {
            let pos = index + lane;
            vec[pos] = vec[pos]
                .wrapping_add(lane_add)
                .wrapping_add(x0_vec22[lane].wrapping_mul(update_mul));
        }
        carry = next_carry(vec[index], vec[index + 1], t)?;
        vec[index + 1] = carry;
    }
    Ok(vec)
}

/// kit `builder63c278ScalarMix2Vector`.
pub fn scalar_mix2_vector_63c278(x2_vec44: &[u64], x0_vec22: &[u64], scalar: u64, t: &FirstPairTables) -> Result<Vec<u64>, CryptoError> {
    if x2_vec44.len() != 44 || x0_vec22.len() != VEC_WORDS {
        return Err(vec_err(x2_vec44.len()));
    }
    let mut vec = x2_vec44.to_vec();
    let scalar_mul = scalar.wrapping_mul(0xd499812ba25ee663).wrapping_add(0x261ebe70f821cbc3);
    let scalar_add = scalar.wrapping_mul(0xb1af6fa1cb6e1d69).wrapping_add(0xbfe73a2bd6da82dc);
    let mut carry = vec[0];
    for index in 0..VEC_WORDS {
        let (update_mul, lane_add) = mix2_seed(carry, scalar_mul, scalar_add, t)?;
        for lane in 0..VEC_WORDS {
            let pos = index + lane;
            vec[pos] = vec[pos]
                .wrapping_add(lane_add)
                .wrapping_add(x0_vec22[lane].wrapping_mul(update_mul));
        }
        carry = next_carry2(vec[index], vec[index + 1], t)?;
        vec[index + 1] = carry;
    }
    Ok(vec)
}

/// kit `builder63c278Tail1U32Words`.
pub fn tail1_u32_words_63c278(mixed_vec44: &[u64], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    if mixed_vec44.len() != 44 {
        return Err(vec_err(mixed_vec44.len()));
    }
    let mut carry: u64 = 0x57078c52164039c3;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        carry = carry.wrapping_mul(0xea79f5006ed1ed3d);
        carry = carry.wrapping_add(mixed_vec44[VEC_WORDS + index].wrapping_mul(0x66df92deb399335b));
        carry = carry.wrapping_add(0x09c9f7e39169d6f1);
        let folded = fold_63c278(
            carry.wrapping_mul(0x4a61801334a2066b).wrapping_add(0x346cdb9fa10bc247),
            0x3019f0,
            7,
            t,
        )?;
        let word = ((carry as u32 as u64)
            .wrapping_mul(0x6d8d9d63)
            .wrapping_add((folded as u32 as u64).wrapping_mul(0x70000000))
            .wrapping_add(0xc780a908)
            & 0xffff_ffff) as u32;
        let folded_tail = fold_63c278(folded, 0x3019f0, 9, t)?;
        carry = folded
            .wrapping_mul(0xe3d2a03f1bfe297f)
            .wrapping_add(folded_tail.wrapping_mul(0x401d681000000000))
            .wrapping_add(0x7b8480dbcf98c453);
        let table_offset = (index & 7) * 4;
        let mul = u32_table_word_63c278(0x123448 + table_offset, t)?;
        let add = u32_table_word_63c278(0x123468 + table_offset, t)?;
        out.push(((word as u64).wrapping_mul(mul as u64).wrapping_add(add as u64) & 0xffff_ffff) as u32);
    }
    Ok(out)
}

/// kit `builder63c278Tail2U32Words`.
pub fn tail2_u32_words_63c278(mixed_vec44: &[u64], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    if mixed_vec44.len() != 44 {
        return Err(vec_err(mixed_vec44.len()));
    }
    let mut carry: u64 = 0x7b98879460aee9e2;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        carry = carry.wrapping_mul(0xf65fd3833526aa13);
        carry = carry.wrapping_add(mixed_vec44[VEC_WORDS + index].wrapping_mul(0x806aa29ec1ed1481));
        carry = carry.wrapping_add(0xb4f29f8797e744b7);
        let folded = fold_63c278(
            carry.wrapping_mul(0xa05b2cf659a43c93).wrapping_add(0x48da81dd905ece62),
            0x301cf0,
            7,
            t,
        )?;
        let word = ((carry as u32 as u64)
            .wrapping_mul(0x0080b9a9)
            .wrapping_add((folded as u32 as u64).wrapping_mul(0xd0000000))
            .wrapping_add(0xde4d224e)
            & 0xffff_ffff) as u32;
        let folded_tail = fold_63c278(folded, 0x301cf0, 9, t)?;
        carry = folded
            .wrapping_mul(0x3fc1e03941c67b59)
            .wrapping_add(folded_tail.wrapping_mul(0xe3984a7000000000))
            .wrapping_add(0x05975fb8f5057bb2);
        let table_offset = (index & 7) * 4;
        let mul = u32_table_word_63c278(0x112628 + table_offset, t)?;
        let add = u32_table_word_63c278(0x121928 + table_offset, t)?;
        out.push(((word as u64).wrapping_mul(mul as u64).wrapping_add(add as u64) & 0xffff_ffff) as u32);
    }
    Ok(out)
}

/// kit `builder63c278AccumulatorStreams`: (sp440 cumulative, sp4f0, sp5a0, sp390 cumulative).
pub fn accumulator_streams_63c278(arg2: &[u8], tail2_words: &[u32], t: &FirstPairTables) -> Result<([u64; VEC_WORDS], [u64; VEC_WORDS], [u64; VEC_WORDS], [u64; VEC_WORDS]), CryptoError> {
    require(arg2, VEC_BYTES, "63c278 arg2")?;
    require_vec(tail2_words)?;
    let mut sp5a0 = [0u64; VEC_WORDS];
    let mut sp440 = [0u64; VEC_WORDS];
    let mut running_a = 0u64;
    for index in 0..VEC_WORDS {
        let item = accum_a_word(read_u32_le(arg2, index * 4), index, t)?;
        sp5a0[index] = item;
        running_a = running_a.wrapping_add(item);
        sp440[index] = running_a;
    }
    let mut sp4f0 = [0u64; VEC_WORDS];
    let mut sp390 = [0u64; VEC_WORDS];
    let mut running_b = 0u64;
    for (index, word) in tail2_words.iter().enumerate() {
        let item = accum_b_word(*word, index, t)?;
        sp4f0[index] = item;
        running_b = running_b.wrapping_add(item);
        sp390[index] = running_b;
    }
    Ok((sp440, sp4f0, sp5a0, sp390))
}
// ---------------------------------------------------------------- bridge + prebranch

/// kit `builder63c278BridgeConvolutionVector`.
pub fn bridge_convolution_vector_63c278(
    sp440_cumulative: &[u64; VEC_WORDS],
    sp4f0_words: &[u64; VEC_WORDS],
    sp5a0_words: &[u64; VEC_WORDS],
    sp390_cumulative: &[u64; VEC_WORDS],
) -> Vec<u64> {
    let mut out = Vec::with_capacity(44);
    for index in 0..44usize {
        let low = index.saturating_sub(21);
        let high = index.min(21);
        let mixed: u64;
        if low > 21 {
            mixed = 0x67bdf132221fb4e9;
        } else {
            let start = index - high;
            let mut dot = 0u64;
            for pos in start..=high {
                dot = dot.wrapping_add(sp4f0_words[index - pos].wrapping_mul(sp5a0_words[pos]));
            }
            let mut span_a = sp440_cumulative[high];
            let span_b: u64;
            if index >= 22 {
                span_a = span_a.wrapping_sub(sp440_cumulative[low - 1]);
                span_b = sp390_cumulative[index - low].wrapping_sub(sp390_cumulative[index - high - 1]);
            } else {
                span_b = sp390_cumulative[index - low];
            }
            mixed = 0x67bdf132221fb4e9u64
                .wrapping_add(((high - low + 1) as u64).wrapping_mul(0x1593d040a4114154))
                .wrapping_add(dot.wrapping_mul(0x2edc06a97199e3ef))
                .wrapping_add(span_a.wrapping_mul(0x0557cced2c1cc47e))
                .wrapping_add(span_b.wrapping_mul(0xc1edf977b66f09ca));
        }
        out.push(mixed.wrapping_mul(0xb3bd694c1c94d1a7).wrapping_add(0x2c0585771e81c36a));
    }
    out
}

fn bridge_x0_word_pub(word: u32, index: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    bridge_x0_word(word, index, t)
}

/// kit `builder63c278BridgeX0Vector`.
pub fn bridge_x0_vector_63c278(arg0: &[u8], t: &FirstPairTables) -> Result<Vec<u64>, CryptoError> {
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        out.push(bridge_x0_word_pub(read_u32_le(arg0, index * 4), index, t)?);
    }
    Ok(out)
}

fn bridge_mix_seed(carry: u64, scalar_mul: u64, scalar_add: u64, t: &FirstPairTables) -> Result<(u64, u64), CryptoError> {
    let mixed = carry.wrapping_mul(scalar_mul).wrapping_add(scalar_add);
    let mut folded = mixed.wrapping_mul(0x34af0af1bbce60dd).wrapping_add(0x61b88589a4883d43);
    folded = fold_63c278(folded, 0x301ef0, 7, t)?;
    folded = folded.wrapping_mul(0x2da4669430000000);
    let seed = mixed.wrapping_mul(0x8f5055af84d40129).wrapping_add(folded).wrapping_add(0x7bf63147a7179819);
    Ok((
        seed.wrapping_mul(0xdb5bb72dd36c07a9).wrapping_add(0x155c3f0a68fbcfe1),
        seed.wrapping_mul(0x0ee832c1be220ab1).wrapping_add(0xcc246f1fe68886a9),
    ))
}

fn bridge_next_carry(updated_first: u64, updated_second: u64, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let mut folded = updated_first.wrapping_mul(0x060c229ff67c02fb).wrapping_add(0xab8d83e0c2b70611);
    folded = fold_63c278(folded, 0x301f70, 7, t)?;
    let carry_mix = folded.wrapping_mul(0x6a605d1236fbedd7).wrapping_add(0xfd36e0ea31dbe67c);
    let mut folded2 = carry_mix.wrapping_mul(0x57a20a77f75734e1).wrapping_add(0x4cc594baeecf3eca);
    folded2 = fold_63c278(folded2, 0x301ff0, 9, t)?;
    folded2 = folded2.wrapping_mul(0x9c0c689000000000);
    let mixed2 = carry_mix.wrapping_mul(0x1e0bc5b08daead97).wrapping_add(folded2);
    Ok(mixed2.wrapping_mul(0x93bd22efcdeacdc3).wrapping_add(updated_second).wrapping_add(0xc175492c1e8124ac))
}

/// kit `builder63c278BridgeMixVector`.
pub fn bridge_mix_vector_63c278(sp230_vec44: &[u64], x0_vec22: &[u64], scalar: u64, t: &FirstPairTables) -> Result<Vec<u64>, CryptoError> {
    if sp230_vec44.len() != 44 || x0_vec22.len() != VEC_WORDS {
        return Err(vec_err(sp230_vec44.len()));
    }
    let mut vec = sp230_vec44.to_vec();
    let scalar_mul = scalar.wrapping_mul(0x5bcfc2db5b41aa8b).wrapping_add(0xb0be584b9c560ceb);
    let scalar_add = scalar.wrapping_mul(0x7cb9da0648140cfd).wrapping_add(0xcb165f95963e265b);
    let mut carry = vec[0];
    for index in 0..VEC_WORDS {
        let (update_mul, lane_add) = bridge_mix_seed(carry, scalar_mul, scalar_add, t)?;
        for lane in 0..VEC_WORDS {
            let pos = index + lane;
            vec[pos] = vec[pos]
                .wrapping_add(lane_add)
                .wrapping_add(x0_vec22[lane].wrapping_mul(update_mul));
        }
        carry = bridge_next_carry(vec[index], vec[index + 1], t)?;
        vec[index + 1] = carry;
    }
    Ok(vec)
}

/// kit `builder63c278BridgeSP128Words`.
pub fn bridge_sp128_words_63c278(sp230_vec44: &[u64], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    if sp230_vec44.len() != 44 {
        return Err(vec_err(sp230_vec44.len()));
    }
    let mut carry: u64 = 0x18541ef2e5658ac6;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        carry = carry.wrapping_mul(0x590b8c9bda7aa7a5);
        carry = carry.wrapping_add(sp230_vec44[VEC_WORDS + index].wrapping_mul(0x93e68b973b124f01));
        carry = carry.wrapping_add(0x0357d31d6340b07a);
        let folded = fold_63c278(
            carry.wrapping_mul(0x052e2e9b238ffd17).wrapping_add(0x7a84d77fc047bb5c),
            0x302070,
            7,
            t,
        )?;
        let word = ((carry as u32 as u64)
            .wrapping_mul(0xbca742b5)
            .wrapping_add((folded as u32 as u64).wrapping_mul(0xd0000000))
            .wrapping_add(0x74c20619)
            & 0xffff_ffff) as u32;
        let folded_tail = fold_63c278(folded, 0x302070, 9, t)?;
        carry = folded
            .wrapping_mul(0x9d12b2b955ef375b)
            .wrapping_add(folded_tail.wrapping_mul(0xa10c8a5000000000))
            .wrapping_add(0x5831e87503aab765);
        let table_offset = (index & 7) * 4;
        let mul = u32_table_word_63c278(0x118568 + table_offset, t)?;
        let add = u32_table_word_63c278(0x1234a8 + table_offset, t)?;
        out.push(((word as u64).wrapping_mul(mul as u64).wrapping_add(add as u64) & 0xffff_ffff) as u32);
    }
    Ok(out)
}

/// kit `builder63c278PrebranchInitialStreams`: (sp390 static, sp440, sp6b0, sp658).
pub fn prebranch_initial_streams_63c278(
    arg0: &[u8],
    tail1_words: &[u32],
    sp128_words: &[u32],
    t: &FirstPairTables,
) -> Result<(Vec<u32>, Vec<u32>, Vec<u32>, Vec<u32>), CryptoError> {
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    require_vec(tail1_words)?;
    require_vec(sp128_words)?;
    let mut sp390 = Vec::with_capacity(VEC_WORDS);
    let mut sp440 = Vec::with_capacity(VEC_WORDS);
    let mut sp6b0 = Vec::with_capacity(VEC_WORDS);
    let mut sp658 = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let table_offset = (index & 7) * 4;
        sp390.push(fold_table_u32_word_63c278(0x3020f0 + index * 4, t)?);
        sp440.push(u32_table_affine_63c278(
            tail1_words[index],
            0x122228 + table_offset,
            0x11dd08 + table_offset,
            t,
        )?);
        sp6b0.push(u32_table_affine_63c278(
            sp128_words[index],
            0x1234c8 + table_offset,
            0x11b428 + table_offset,
            t,
        )?);
        sp658.push(u32_table_affine_63c278(
            read_u32_le(arg0, index * 4),
            0x114968 + table_offset,
            0x11fda8 + table_offset,
            t,
        )?);
    }
    Ok((sp390, sp440, sp6b0, sp658))
}

fn prebranch_sp4f0_fold_state(state: u32, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let folded = ((state as u64).wrapping_mul(0x3dbef531).wrapping_add(0x554aacd3)) as u32;
    let folded = fold32_by_nibbles_63c278(folded, 0x3021f8, 7, t)?;
    let selected = u32_table_word_63c278(0x122ac8 + ((folded & 7) as usize) * 4, t)?;
    Ok(selected.wrapping_add(folded >> 3))
}

fn prebranch_sp4f0_state(word: u32, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let half = word >> 1;
    let bit_table = u32_table_word_63c278(0x126850 + ((word & 1) as usize) * 4, t)?;
    Ok(((half as u64).wrapping_mul(0x0c949fdb).wrapping_add(bit_table as u64)) as u32)
}

/// kit `builder63c278PrebranchSP4F0Words`.
pub fn prebranch_sp4f0_words_63c278(arg0: &[u8], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    let arg0_words: Vec<u32> = (0..VEC_WORDS).map(|i| read_u32_le(arg0, i * 4)).collect();
    let first = ((arg0_words[0] as u64).wrapping_mul(0x7193fc77).wrapping_add(0x318e9b49)) as u32;
    let mut state = prebranch_sp4f0_state(first, t)?;
    let mut selected = prebranch_sp4f0_fold_state(state, t)?;
    let mut carry = ((state as u64)
        .wrapping_mul(0x8e834ce3)
        .wrapping_add((selected << 31) as u64)
        .wrapping_add(0x0afac599)) as u32;

    let mut out: Vec<u32> = Vec::with_capacity(VEC_WORDS);
    let mut index = 0usize;
    loop {
        if index == VEC_WORDS - 1 {
            let store_value = ((carry as u64).wrapping_mul(0x3f277405).wrapping_add(0xa0c1d6f4)) as u32;
            let table_offset = (index * 4) & 0x1c;
            out.push(u32_table_affine_63c278(store_value, 0x122248 + table_offset, 0x1172a8 + table_offset, t)?);
            break;
        }
        let next_index = index + 1;
        let next_table_offset = (next_index & 7) * 4;
        let value = u32_table_affine_63c278(
            arg0_words[next_index],
            0x11c3e8 + next_table_offset,
            0x11b448 + next_table_offset,
            t,
        )?;
        carry = ((carry as u64)
            .wrapping_mul(0x3f277405)
            .wrapping_add((value as u64).wrapping_mul(0xa8000000))) as u32;
        state = prebranch_sp4f0_state(value, t)?;
        selected = prebranch_sp4f0_fold_state(state, t)?;
        let word = ((state as u64)
            .wrapping_mul(0x8e834ce3)
            .wrapping_add(((selected << 31) as u64))
            .wrapping_add(0x0afac599)) as u32;
        carry = ((word as u64).wrapping_mul(0xb0000000).wrapping_add(carry as u64)) as u32;
        let store_value = carry.wrapping_add(0xc8c1d6f4);
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(store_value, 0x122248 + table_offset, 0x1172a8 + table_offset, t)?);
        carry = word;
        index = next_index;
    }
    Ok(out)
}

/// kit `builder63c278PrebranchSP230Words`.
pub fn prebranch_sp230_words_63c278(sp4f0_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    require_vec(sp4f0_words)?;
    let q0: Vec<u32> = (0..4).map(|i| u32_table_word_63c278(0x125f20 + i * 4, t)).collect::<Result<_, _>>()?;
    let q1: Vec<u32> = (0..4).map(|i| u32_table_word_63c278(0x125f30 + i * 4, t)).collect::<Result<_, _>>()?;
    let mut out = q0.clone();
    out.extend(&q1);
    out.extend(&q0);
    out.extend(&q1);
    out.extend(&q0);
    out.extend(q1.iter().take(2).copied());

    for (index, word) in sp4f0_words.iter().enumerate() {
        let table_offset = (index & 7) * 4;
        let mul = u32_table_word_63c278(0x11c408 + table_offset, t)?;
        out[index] = (((*word as u64).wrapping_mul(mul as u64).wrapping_add(out[index] as u64)) & 0xffff_ffff) as u32;
    }
    for index in 0..VEC_WORDS {
        let table_offset = (index & 7) * 4;
        let static_word = fold_table_u32_word_63c278(0x302148 + index * 4, t)?;
        let mul = u32_table_word_63c278(0x118588 + table_offset, t)?;
        out[index] = (((static_word as u64).wrapping_mul(mul as u64).wrapping_add(out[index] as u64)) & 0xffff_ffff) as u32;
    }
    Ok(out)
}

/// kit `builder63c278PrebranchSP5A0Words`.
pub fn prebranch_sp5a0_words_63c278(sp230_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    require_vec(sp230_words)?;
    let mut carry: u32 = 0xa7964b7d;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for (index, word) in sp230_words.iter().enumerate() {
        carry = ((carry as u64).wrapping_mul(0x856c3a53).wrapping_add(*word as u64)) as u32;
        let mut folded = ((carry as u64).wrapping_mul(0x287caef9).wrapping_add(0x0ac0f465)) as u32;
        folded = fold32_by_nibbles_63c278(folded, 0x302238, 7, t)?;
        let folded_tail = fold_table_u32_word_63c278(0x302238 + ((folded & 0x0f) as usize) * 4, t)?
            .wrapping_add(folded >> 4);
        let next_part = ((carry as u64)
            .wrapping_mul(0xd8018ba1)
            .wrapping_add((folded as u64).wrapping_mul(0x70000000))
            .wrapping_add(0x63f7e16a)) as u32;
        let tail = ((folded as u64)
            .wrapping_mul(0x20718073)
            .wrapping_add((folded_tail as u64).wrapping_mul(0xf8e7f8d0))) as u32;
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(next_part, 0x11a028 + table_offset, 0x115f48 + table_offset, t)?);
        carry = tail.wrapping_add(0xe056c4a1);
    }
    Ok(out)
}

// ---------------------------------------------------------------- branch loop

struct BranchAffineParams {
    arg0_mul: u32,
    arg0_add: u32,
    half_mul: u32,
    bit_table: usize,
    pre_mul: u32,
    pre_add: u32,
    word_mul: u32,
    word_add: u32,
    fold_table: usize,
    select_table: usize,
    arg_mul_table: usize,
    arg_add_table: usize,
    carry_mul: u32,
    value_mul: u32,
    next_mul: u32,
    loop_add: u32,
    final_add: u32,
    out_mul_table: usize,
    out_add_table: usize,
}

fn branch_state(word: u32, half_mul: u32, bit_table: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let bit = u32_table_word_63c278(bit_table + ((word & 1) as usize) * 4, t)?;
    Ok(((word >> 1) as u64).wrapping_mul(half_mul as u64).wrapping_add(bit as u64) as u32)
}

fn branch_select_bit(value: u32, fold_table: usize, select_table: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let folded = fold32_by_nibbles_63c278(value, fold_table, 7, t)?;
    let selected = u32_table_word_63c278(select_table + ((folded & 7) as usize) * 4, t)?.wrapping_add(folded >> 3);
    Ok(selected << 31)
}

fn branch_word(state: u32, p: &BranchAffineParams, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let pre_fold = ((state as u64).wrapping_mul(p.pre_mul as u64).wrapping_add(p.pre_add as u64)) as u32;
    let select = branch_select_bit(pre_fold, p.fold_table, p.select_table, t)?;
    Ok(((state as u64)
        .wrapping_mul(p.word_mul as u64)
        .wrapping_add(p.word_add as u64)
        .wrapping_add(select as u64)) as u32)
}

fn branch_affine_update(words: &[u32], p: &BranchAffineParams, t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    require_vec(words)?;
    let first = ((words[0] as u64).wrapping_mul(p.arg0_mul as u64).wrapping_add(p.arg0_add as u64)) as u32;
    let first_state = branch_state(first, p.half_mul, p.bit_table, t)?;
    let mut carry = branch_word(first_state, p, t)?;

    let mut out = vec![0u32; VEC_WORDS];
    for index in 0..(VEC_WORDS - 1) {
        let next_index = index + 1;
        let next_table_offset = (next_index & 7) * 4;
        let value = u32_table_affine_63c278(
            words[next_index],
            p.arg_mul_table + next_table_offset,
            p.arg_add_table + next_table_offset,
            t,
        )?;
        let state = branch_state(value, p.half_mul, p.bit_table, t)?;
        let word = branch_word(state, p, t)?;
        carry = ((carry as u64)
            .wrapping_mul(p.carry_mul as u64)
            .wrapping_add((value as u64).wrapping_mul(p.value_mul as u64))
            .wrapping_add((word as u64).wrapping_mul(p.next_mul as u64))) as u32;
        let store_value = carry.wrapping_add(p.loop_add);
        let table_offset = (index * 4) & 0x1c;
        out[index] = u32_table_affine_63c278(store_value, p.out_mul_table + table_offset, p.out_add_table + table_offset, t)?;
        carry = word;
    }
    let final_store = ((carry as u64).wrapping_mul(p.carry_mul as u64).wrapping_add(p.final_add as u64)) as u32;
    let final_offset = ((VEC_WORDS - 1) * 4) & 0x1c;
    out[VEC_WORDS - 1] = u32_table_affine_63c278(final_store, p.out_mul_table + final_offset, p.out_add_table + final_offset, t)?;
    Ok(out)
}

fn static_pattern_words(q0: usize, q1: usize, tail: usize, t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let q0_words: Vec<u32> = (0..4).map(|i| u32_table_word_63c278(q0 + i * 4, t)).collect::<Result<_, _>>()?;
    let q1_words: Vec<u32> = (0..4).map(|i| u32_table_word_63c278(q1 + i * 4, t)).collect::<Result<_, _>>()?;
    let tail_words: Vec<u32> = (0..2).map(|i| u32_table_word_63c278(tail + i * 4, t)).collect::<Result<_, _>>()?;
    let mut out = q0_words.clone();
    out.extend(&q1_words);
    out.extend(&q0_words);
    out.extend(&q1_words);
    out.extend(&q0_words);
    out.extend(&tail_words);
    Ok(out)
}

struct StageReducerParams {
    carry: u32,
    carry_mul: u32,
    pre_mul: u32,
    pre_add: u32,
    fold_table: usize,
    reduce_mul: u32,
    side_mul: u32,
    reduce_add: u32,
    folded7_mul: u32,
    folded8_mul: u32,
    next_add: u32,
    out_mul_table: usize,
    out_add_table: usize,
}

/// kit `stageReducer63c278`: fold static pattern + mul-table'd streams, then a carry reducer.
fn stage_reducer(static_words: &[u32], streams: &[(&[u32], usize)], p: &StageReducerParams, t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    require_vec(static_words)?;
    let mut sp230 = static_words.to_vec();
    for (words, mul_table) in streams {
        require_vec(words)?;
        for (index, word) in words.iter().enumerate() {
            let table_offset = (index & 7) * 4;
            let mul = u32_table_word_63c278(mul_table + table_offset, t)?;
            sp230[index] = ((sp230[index] as u64).wrapping_add((*word as u64).wrapping_mul(mul as u64)) & 0xffff_ffff) as u32;
        }
    }
    let mut carry = p.carry;
    let mut out = Vec::with_capacity(VEC_WORDS);
    for (index, word) in sp230.iter().enumerate() {
        carry = ((carry as u64).wrapping_mul(p.carry_mul as u64).wrapping_add(*word as u64)) as u32;
        let mut folded7 = ((carry as u64).wrapping_mul(p.pre_mul as u64).wrapping_add(p.pre_add as u64)) as u32;
        folded7 = fold32_by_nibbles_63c278(folded7, p.fold_table, 7, t)?;
        let folded8 = fold_table_u32_word_63c278(p.fold_table + ((folded7 & 0x0f) as usize) * 4, t)?
            .wrapping_add(folded7 >> 4);
        let stage = ((carry as u64)
            .wrapping_mul(p.reduce_mul as u64)
            .wrapping_add((folded7 as u64).wrapping_mul(p.side_mul as u64))
            .wrapping_add(p.reduce_add as u64)) as u32;
        let next_carry = ((folded7 as u64)
            .wrapping_mul(p.folded7_mul as u64)
            .wrapping_add((folded8 as u64).wrapping_mul(p.folded8_mul as u64))) as u32;
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(stage, p.out_mul_table + table_offset, p.out_add_table + table_offset, t)?);
        carry = next_carry.wrapping_add(p.next_add);
    }
    Ok(out)
}

fn loop_update_sp658_odd(words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    branch_affine_update(words, &BranchAffineParams {
        arg0_mul: 0x33f71427, arg0_add: 0x58500b33, half_mul: 0x2cb60683,
        bit_table: 0x126a48, pre_mul: 0xc4fb260b, pre_add: 0xf348f6f7,
        word_mul: 0x2b1d86b1, word_add: 0xfa05b11d,
        fold_table: 0x302378, select_table: 0x112648,
        arg_mul_table: 0x117ba8, arg_add_table: 0x11cde8,
        carry_mul: 0xa822376d, value_mul: 0xb8000000, next_mul: 0x30000000,
        loop_add: 0x24e24246, final_add: 0x14e24246,
        out_mul_table: 0x1206a8, out_add_table: 0x11b468,
    }, t)
}

fn loop_sp658_even_uses_success_path(sp658_words: &[u32], sp6b0_words: &[u32], t: &FirstPairTables) -> Result<bool, CryptoError> {
    require_vec(sp658_words)?;
    require_vec(sp6b0_words)?;
    for index in (0..VEC_WORDS).rev() {
        let table_offset = (index * 4) & 0x1c;
        let check = ((sp658_words[index] as u64)
            .wrapping_mul(u32_table_word_63c278(0x1154c8 + table_offset, t)? as u64)
            .wrapping_add((sp6b0_words[index] as u64).wrapping_mul(u32_table_word_63c278(0x1172c8 + table_offset, t)? as u64))
            .wrapping_add(u32_table_word_63c278(0x11c468 + table_offset, t)? as u64)) as u32;
        if check != 0x59262fed {
            let folded = fold32_by_nibbles_63c278(check, 0x302478, 7, t)?;
            return Ok((folded & 0x0f) == 0x0d);
        }
    }
    Ok(true)
}

fn loop_update_sp658_even_success(sp658_words: &[u32], sp6b0_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let static_words = static_pattern_words(0x125a70, 0x125940, 0x126ce0, t)?;
    stage_reducer(
        &static_words,
        &[(sp658_words, 0x11b4c8), (sp6b0_words, 0x122b08)],
        &StageReducerParams {
            carry: 0x6238179a, carry_mul: 0x2cb31cf5,
            pre_mul: 0xeaa360b5, pre_add: 0x7dcae1fd,
            fold_table: 0x3025b8, reduce_mul: 0x354589c9,
            side_mul: 0xb0000000, reduce_add: 0xb0b43182,
            folded7_mul: 0x6f16c509, folded8_mul: 0x0e93af70,
            next_add: 0x29fd0d1c,
            out_mul_table: 0x11d508, out_add_table: 0x123508,
        },
        t,
    )
}

fn loop_update_sp6b0_even(words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    branch_affine_update(words, &BranchAffineParams {
        arg0_mul: 0x96928029, arg0_add: 0x666d5b3a, half_mul: 0x27acf74d,
        bit_table: 0x126a18, pre_mul: 0x84602417, pre_add: 0xf95f2c9d,
        word_mul: 0x4bd20bc9, word_add: 0x3a0734ca,
        fold_table: 0x302278, select_table: 0x11cda8,
        arg_mul_table: 0x11c428, arg_add_table: 0x119748,
        carry_mul: 0x96d2d627, value_mul: 0x98000000, next_mul: 0x90000000,
        loop_add: 0x2f40aa3d, final_add: 0x3f40aa3d,
        out_mul_table: 0x117b68, out_add_table: 0x121968,
    }, t)
}

fn loop_update_sp440_odd(words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    branch_affine_update(words, &BranchAffineParams {
        arg0_mul: 0x28f734a3, arg0_add: 0x7fc88b1c, half_mul: 0xb00c4591,
        bit_table: 0x126780, pre_mul: 0x059e578d, pre_add: 0x33273af5,
        word_mul: 0x9d8dd89f, word_add: 0xa52d9347,
        fold_table: 0x3022b8, select_table: 0x113708,
        arg_mul_table: 0x11d4e8, arg_add_table: 0x11c448,
        carry_mul: 0xd470f3b3, value_mul: 0xe8000000, next_mul: 0xd0000000,
        loop_add: 0xadcd0df0, final_add: 0x65cd0df0,
        out_mul_table: 0x11cdc8, out_add_table: 0x117b88,
    }, t)
}

fn loop_sp440_even_sp4f0_words(words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    branch_affine_update(words, &BranchAffineParams {
        arg0_mul: 0x5888f7f5, arg0_add: 0xbbf0e3d5, half_mul: 0x642326db,
        bit_table: 0x126858, pre_mul: 0x246654c1, pre_add: 0x2e782dc3,
        word_mul: 0x1101c103, word_add: 0x183fafb9,
        fold_table: 0x3022f8, select_table: 0x1154a8,
        arg_mul_table: 0x11a9e8, arg_add_table: 0x122268,
        carry_mul: 0x4d140725, value_mul: 0xa8000000, next_mul: 0xb0000000,
        loop_add: 0x6fe563d6, final_add: 0x8fe563d6,
        out_mul_table: 0x11dd28, out_add_table: 0x120f48,
    }, t)
}

fn loop_update_sp440_even(sp440_words: &[u32], sp5a0_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let sp4f0 = loop_sp440_even_sp4f0_words(sp440_words, t)?;
    let static_words = static_pattern_words(0x124f10, 0x125930, 0x126cd8, t)?;
    stage_reducer(
        &static_words,
        &[(sp4f0.as_slice(), 0x114988), (sp5a0_words, 0x11aa08)],
        &StageReducerParams {
            carry: 0xd3f16146, carry_mul: 0x84bb8555,
            pre_mul: 0xd3dd75bb, pre_add: 0x4bdc02a1,
            fold_table: 0x302338, reduce_mul: 0x26bbb9ff,
            side_mul: 0x30000000, reduce_add: 0xe8f27692,
            folded7_mul: 0xef9fd9a7, folded8_mul: 0x06026590,
            next_add: 0x613a18d6,
            out_mul_table: 0x120688, out_add_table: 0x1185a8,
        },
        t,
    )
}

fn loop_update_sp440(words: &[u32], sp5a0_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    if (words[0] & 1) != 0 {
        loop_update_sp440_odd(words, t)
    } else {
        loop_update_sp440_even(words, sp5a0_words, t)
    }
}

fn loop_update_sp390_even(words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    branch_affine_update(words, &BranchAffineParams {
        arg0_mul: 0x5b7c4419, arg0_add: 0xd8c9cb43, half_mul: 0xa30de075,
        bit_table: 0x1267f0, pre_mul: 0x936efced, pre_add: 0x32c3c0a7,
        word_mul: 0x88e44053, word_add: 0xc35d94bb,
        fold_table: 0x3023b8, select_table: 0x1149a8,
        arg_mul_table: 0x11b488, arg_add_table: 0x119768,
        carry_mul: 0x14c37dcd, value_mul: 0x18000000, next_mul: 0x30000000,
        loop_add: 0xe4da180f, final_add: 0xccda180f,
        out_mul_table: 0x1168e8, out_add_table: 0x11a048,
    }, t)
}

fn loop_sp390_odd_sp4f0_words(words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    branch_affine_update(words, &BranchAffineParams {
        arg0_mul: 0x8e39b739, arg0_add: 0x7c6d92a6, half_mul: 0xdca8620d,
        bit_table: 0x126940, pre_mul: 0x17c4f57f, pre_add: 0x5b647db4,
        word_mul: 0xb0fff815, word_add: 0x831b4fff,
        fold_table: 0x3023f8, select_table: 0x123e28,
        arg_mul_table: 0x11f5a8, arg_add_table: 0x11bc08,
        carry_mul: 0x19f6ba67, value_mul: 0xb8000000, next_mul: 0x90000000,
        loop_add: 0x85a9b64d, final_add: 0xb5a9b64d,
        out_mul_table: 0x117bc8, out_add_table: 0x116908,
    }, t)
}

fn loop_update_sp390_odd(sp390_words: &[u32], sp5a0_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let sp4f0 = loop_sp390_odd_sp4f0_words(sp390_words, t)?;
    let static_words = static_pattern_words(0x126330, 0x126020, 0x126a10, t)?;
    stage_reducer(
        &static_words,
        &[(sp4f0.as_slice(), 0x11ce08), (sp5a0_words, 0x122ae8)],
        &StageReducerParams {
            carry: 0x1cd91585, carry_mul: 0x1a4cb35b,
            pre_mul: 0x5137a735, pre_add: 0x3e9907e2,
            fold_table: 0x302438, reduce_mul: 0x38fc5a19,
            side_mul: 0xb0000000, reduce_add: 0x0f3d7c5d,
            folded7_mul: 0xa81b54e7, folded8_mul: 0x7e4ab190,
            next_add: 0x767d913c,
            out_mul_table: 0x11a068, out_add_table: 0x11b4a8,
        },
        t,
    )
}

fn loop_update_sp390(words: &[u32], sp5a0_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    if (words[0] & 1) != 0 {
        loop_update_sp390_odd(words, sp5a0_words, t)
    } else {
        loop_update_sp390_even(words, t)
    }
}

fn loop_update_sp390_predicate_false(sp390_words: &[u32], arg0: &[u8], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let arg0_words = arg0_words_63c278(arg0)?;
    let static_words = static_pattern_words(0x126040, 0x126600, 0x126740, t)?;
    stage_reducer(
        &static_words,
        &[(sp390_words, 0x11b4e8), (arg0_words.as_slice(), 0x11bc28)],
        &StageReducerParams {
            carry: 0x6306d080, carry_mul: 0x90b4d58b,
            pre_mul: 0x323154f1, pre_add: 0x154382ee,
            fold_table: 0x3025f8, reduce_mul: 0x30b9cbfb,
            side_mul: 0x50000000, reduce_add: 0x61849d3d,
            folded7_mul: 0x1fb5a053, folded8_mul: 0x04a5fad0,
            next_add: 0x002fe7ef,
            out_mul_table: 0x117be8, out_add_table: 0x1172e8,
        },
        t,
    )
}

fn loop_update_sp390_predicate_join(sp390_words: &[u32], sp440_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let static_words = static_pattern_words(0x124d90, 0x125c10, 0x126998, t)?;
    stage_reducer(
        &static_words,
        &[(sp390_words, 0x11dd48), (sp440_words, 0x122288)],
        &StageReducerParams {
            carry: 0xd554336d, carry_mul: 0x43e12a11,
            pre_mul: 0xfd350b93, pre_add: 0xc2fdb2e2,
            fold_table: 0x302638, reduce_mul: 0x419ce971,
            side_mul: 0x50000000, reduce_add: 0x967ae928,
            folded7_mul: 0xcbd75deb, folded8_mul: 0x428a2150,
            next_add: 0x27f798a1,
            out_mul_table: 0x118f48, out_add_table: 0x11ec28,
        },
        t,
    )
}

fn loop_update_sp6b0_failure(sp6b0_words: &[u32], sp658_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let static_words = static_pattern_words(0x1256b0, 0x125f40, 0x1268d8, t)?;
    stage_reducer(
        &static_words,
        &[(sp6b0_words, 0x1149c8), (sp658_words, 0x11f5c8)],
        &StageReducerParams {
            carry: 0x2b0fe6d9, carry_mul: 0x346b3047,
            pre_mul: 0xe8d292cb, pre_add: 0x6376b766,
            fold_table: 0x3024b8, reduce_mul: 0xd98513db,
            side_mul: 0xf0000000, reduce_add: 0x8af9de6d,
            folded7_mul: 0x74f0e285, folded8_mul: 0xb0f1d7b0,
            next_add: 0x6ac588e9,
            out_mul_table: 0x11fdc8, out_add_table: 0x115f68,
        },
        t,
    )
}

fn loop_update_sp440_predicate_true(sp440_words: &[u32], arg0: &[u8], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let arg0_words = arg0_words_63c278(arg0)?;
    let static_words = static_pattern_words(0x124f20, 0x125a00, 0x126c68, t)?;
    stage_reducer(
        &static_words,
        &[(sp440_words, 0x112668), (arg0_words.as_slice(), 0x116928)],
        &StageReducerParams {
            carry: 0x43bff476, carry_mul: 0x8123c767,
            pre_mul: 0xbc55d64f, pre_add: 0x3db88f4f,
            fold_table: 0x302538, reduce_mul: 0xb7e919a9,
            side_mul: 0x90000000, reduce_add: 0xd881235b,
            folded7_mul: 0x239e1779, folded8_mul: 0xc61e8870,
            next_add: 0xa1d86ec1,
            out_mul_table: 0x113728, out_add_table: 0x116948,
        },
        t,
    )
}

fn loop_update_sp440_predicate_join(sp440_words: &[u32], sp390_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    let static_words = static_pattern_words(0x125440, 0x126030, 0x1267b8, t)?;
    stage_reducer(
        &static_words,
        &[(sp440_words, 0x114068), (sp390_words, 0x11c488)],
        &StageReducerParams {
            carry: 0xf35277e4, carry_mul: 0x3ae4bb05,
            pre_mul: 0x130a19eb, pre_add: 0x624d99a5,
            fold_table: 0x302578, reduce_mul: 0x53cea2cf,
            side_mul: 0x30000000, reduce_add: 0x81d9862e,
            folded7_mul: 0x53c4f527, folded8_mul: 0xc3b0ad90,
            next_add: 0x59871186,
            out_mul_table: 0x120f68, out_add_table: 0x11a088,
        },
        t,
    )
}

/// kit `builder63c278Predicate64D55C`: 1 or 0.
fn predicate_64d55c(sp440_words: &[u32], sp390_words: &[u32], t: &FirstPairTables) -> Result<u32, CryptoError> {
    if sp440_words.len() < VEC_WORDS || sp390_words.len() < VEC_WORDS {
        return Err(vec_err(sp440_words.len()));
    }
    for index in (0..VEC_WORDS).rev() {
        let table_offset = (index * 4) & 0x1c;
        let check = ((sp440_words[index] as u64)
            .wrapping_mul(u32_table_word_63c278(0x11fde8 + table_offset, t)? as u64)
            .wrapping_add((sp390_words[index] as u64).wrapping_mul(u32_table_word_63c278(0x1206c8 + table_offset, t)? as u64))
            .wrapping_add(u32_table_word_63c278(0x1185c8 + table_offset, t)? as u64)) as u32;
        if check != 0x213734c0 {
            let folded = fold32_by_nibbles_63c278(check, 0x3024f8, 7, t)?;
            return Ok(if folded & 0x0f != 0 { 1 } else { 0 });
        }
    }
    Ok(0)
}

/// kit `builder63c278TerminalSP658Ready`.
fn terminal_sp658_ready(sp658_words: &[u32], t: &FirstPairTables) -> Result<bool, CryptoError> {
    if sp658_words.len() < VEC_WORDS {
        return Err(vec_err(sp658_words.len()));
    }
    if ((sp658_words[0] as u64).wrapping_mul(0x04dc738d) & 0xffff_ffff) as u32 != 0x49f4222f {
        return Ok(false);
    }
    for index in 1..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let check = ((fold_table_u32_word_63c278(0x3021a0 + index * 4, t)? as u64)
            .wrapping_mul(u32_table_word_63c278(0x1234e8 + table_offset, t)? as u64)
            .wrapping_add((sp658_words[index] as u64).wrapping_mul(u32_table_word_63c278(0x120668 + table_offset, t)? as u64))
            .wrapping_add(u32_table_word_63c278(0x11e5a8 + table_offset, t)? as u64)) as u32;
        if check != 0x0a2c3abe {
            return Ok(false);
        }
    }
    Ok(true)
}

/// kit `builder63c278BranchLoop`.
pub fn branch_loop_63c278(
    arg0: &[u8],
    mut sp390: Vec<u32>,
    mut sp440: Vec<u32>,
    mut sp6b0: Vec<u32>,
    mut sp658: Vec<u32>,
    sp5a0_words: &[u32],
    t: &FirstPairTables,
) -> Result<(Vec<u32>, Vec<u32>, Vec<u32>, Vec<u32>), CryptoError> {
    require(arg0, VEC_BYTES, "63c278 arg0")?;
    require_vec(sp5a0_words)?;
    for _ in 0..2000 {
        if terminal_sp658_ready(&sp658, t)? {
            return Ok((sp390, sp440, sp6b0, sp658));
        }
        if (sp6b0[0] & 1) == 0 {
            sp6b0 = loop_update_sp6b0_even(&sp6b0, t)?;
            sp440 = loop_update_sp440(&sp440, sp5a0_words, t)?;
            continue;
        }
        while (sp658[0] & 1) != 0 {
            sp658 = loop_update_sp658_odd(&sp658, t)?;
            sp390 = loop_update_sp390(&sp390, sp5a0_words, t)?;
        }
        if loop_sp658_even_uses_success_path(&sp658, &sp6b0, t)? {
            sp658 = loop_update_sp658_even_success(&sp658, &sp6b0, t)?;
            if predicate_64d55c(&sp440, &sp390, t)? == 0 {
                sp390 = loop_update_sp390_predicate_false(&sp390, arg0, t)?;
            }
            sp390 = loop_update_sp390_predicate_join(&sp390, &sp440, t)?;
        } else {
            sp6b0 = loop_update_sp6b0_failure(&sp6b0, &sp658, t)?;
            if predicate_64d55c(&sp440, &sp390, t)? != 0 {
                sp440 = loop_update_sp440_predicate_true(&sp440, arg0, t)?;
            }
            sp440 = loop_update_sp440_predicate_join(&sp440, &sp390, t)?;
        }
    }
    Err(slice_err("63c278 branch loop did not terminate in 2000 iterations"))
}

/// kit `builder63c278FinalScheduleFromSP440U32`: 20 schedule words from a ≥22-word stream.
pub fn final_schedule_from_sp440_63c278(sp440_words: &[u32], t: &FirstPairTables) -> Result<Vec<u32>, CryptoError> {
    if sp440_words.len() < VEC_WORDS {
        return Err(vec_err(sp440_words.len()));
    }
    let mut staged = Vec::with_capacity(VEC_WORDS);
    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        staged.push(u32_table_affine_63c278(sp440_words[index], 0x117308 + table_offset, 0x11b508 + table_offset, t)?);
    }
    let mut out = Vec::with_capacity(20);
    for index in 0..20usize {
        let table_offset = (index * 4) & 0x1c;
        out.push(u32_table_affine_63c278(staged[index], 0x11f5e8 + table_offset, 0x1154e8 + table_offset, t)?);
    }
    Ok(out)
}

/// kit `builder63c278ScheduleWords`: the full 20-word schedule for one (arg0, arg1, arg2, scalar).
pub fn schedule_words_63c278(
    arg0: &[u8],
    arg1: &[u8],
    arg2: &[u8],
    scalar: u64,
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    let (x1_vec44, x0_vec22) = initial_vectors_63c278(arg0, arg1, t)?;
    let mixed = scalar_mix_vector_63c278(&x1_vec44, &x0_vec22, scalar, t)?;
    let tail1 = tail1_u32_words_63c278(&mixed, t)?;

    let (x2_vec44, x0b_vec22) = second_initial_vectors_63c278(arg0, arg2, t)?;
    let mixed2 = scalar_mix2_vector_63c278(&x2_vec44, &x0b_vec22, scalar, t)?;
    let tail2 = tail2_u32_words_63c278(&mixed2, t)?;

    let (sp440_cumulative, sp4f0_words, sp5a0_words, sp390_cumulative) =
        accumulator_streams_63c278(arg2, &tail2, t)?;
    let bridge = bridge_convolution_vector_63c278(&sp440_cumulative, &sp4f0_words, &sp5a0_words, &sp390_cumulative);
    let bridge_x0 = bridge_x0_vector_63c278(arg0, t)?;
    let bridge_mixed = bridge_mix_vector_63c278(&bridge, &bridge_x0, scalar, t)?;
    let sp128 = bridge_sp128_words_63c278(&bridge_mixed, t)?;

    let (sp390_static, sp440_words, sp6b0_words, sp658_words) =
        prebranch_initial_streams_63c278(arg0, &tail1, &sp128, t)?;
    let pre4f0 = prebranch_sp4f0_words_63c278(arg0, t)?;
    let pre230 = prebranch_sp230_words_63c278(&pre4f0, t)?;
    let pre5a0 = prebranch_sp5a0_words_63c278(&pre230, t)?;
    let (final_sp390, final_sp440, _final_sp6b0, _final_sp658) =
        branch_loop_63c278(arg0, sp390_static, sp440_words, sp6b0_words, sp658_words, &pre5a0, t)?;
    let _ = final_sp390;
    final_schedule_from_sp440_63c278(&final_sp440, t)
}

// ---------------------------------------------------------------- 6388f0 lane stack

fn selector_6388f0(index: usize, schedule_word: u32, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let table_offset = (index * 4) & 0x1c;
    if table_offset + 4 > t.selector_mul_6388f0.len() || table_offset + 4 > t.selector_add_6388f0.len() {
        return Err(slice_err(format!("6388f0 selector table read at {table_offset:#x}")));
    }
    let multiplier = read_u32_le(&t.selector_mul_6388f0, table_offset) as u64;
    let addend = read_u32_le(&t.selector_add_6388f0, table_offset) as u64;
    Ok(((schedule_word as u64).wrapping_mul(multiplier).wrapping_add(addend) & 0xffff_ffff) as u32)
}

fn lane_prefixed(prefix_word: u32, state18: &[u8]) -> Result<Vec<u8>, CryptoError> {
    require(state18, 14, "6388f0 lane state")?;
    let mut out = prefix_word.to_le_bytes().to_vec();
    out.extend(&state18[..14]);
    Ok(out)
}

fn lane_table_expand(table_offset: usize, selector_byte: u8, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let row_offset = table_offset + (selector_byte as usize) * LANE_TABLE_PACKED_ROW;
    let row = checked_slice(&t.lane_tables_6388f0, row_offset, LANE_TABLE_PACKED_ROW, "6388f0 lane tables")?;
    let mut out = Vec::with_capacity(LANE_TABLE_EXPANDED);
    for packed in row {
        out.push(packed & 7);
        out.push((packed >> 3) & 7);
    }
    Ok(out)
}

/// kit `builder6388f0LaneBlocksFromScheduleWords`: 20 words → (primary, secondary) lane blocks.
pub fn lane_blocks_from_schedule_words_6388f0(
    schedule_words: &[u32],
    t: &FirstPairTables,
) -> Result<(Vec<u8>, Vec<u8>), CryptoError> {
    if schedule_words.len() != LANE_BLOCK_COUNT {
        return Err(slice_err(format!("6388f0 schedule wants {LANE_BLOCK_COUNT} words, got {}", schedule_words.len())));
    }
    let primary_static = checked_slice(&t.lane_tables_6388f0, LANE_PRIMARY_STATIC_OFFSET, LANE_TABLE_EXPANDED, "6388f0 lane tables")?;
    let secondary_static = checked_slice(&t.lane_tables_6388f0, LANE_SECONDARY_STATIC_OFFSET, LANE_TABLE_EXPANDED, "6388f0 lane tables")?;

    let mut primary_lanes: Vec<u8> = Vec::with_capacity(LANE_BLOCK_COUNT * LANE_BLOCK_SIZE);
    let mut secondary_lanes: Vec<u8> = Vec::with_capacity(LANE_BLOCK_COUNT * LANE_BLOCK_SIZE);

    for (index, word) in schedule_words.iter().enumerate() {
        let selector = selector_6388f0(index, *word, t)?;
        let mut primary_state =
            checked_slice(&t.lane_tables_6388f0, LANE_A_INIT_OFFSET, LANE_BLOCK_SIZE, "6388f0 lane tables")?.to_vec();
        primary_state.extend([0x05, 0x04]);
        let mut secondary_state =
            checked_slice(&t.lane_tables_6388f0, LANE_B_INIT_OFFSET, LANE_BLOCK_SIZE, "6388f0 lane tables")?.to_vec();
        secondary_state.extend([0x05, 0x02]);

        for shift in [24u32, 16, 8, 0] {
            let primary_primer = lane_prefixed(0x03000000, &primary_state)?;
            primary_state = vm638840(0x1200000712f, &primary_primer, &primary_primer, t)?;
            let secondary_primer = lane_prefixed(0x01000000, &secondary_state)?;
            secondary_state = vm638840(0x120000003aa, &secondary_primer, &secondary_primer, t)?;

            let selector_byte = ((selector >> shift) & 0xff) as u8;
            primary_state = vm638840(
                0x1200000551a,
                &primary_state,
                &lane_table_expand(LANE_A_TABLE_OFFSET, selector_byte, t)?,
                t,
            )?;
            secondary_state = vm638840(
                0x12000000c60,
                &secondary_state,
                &lane_table_expand(LANE_B_TABLE_OFFSET, selector_byte, t)?,
                t,
            )?;
        }

        let primary_source = vm638840(0x12000003d45, &primary_state, primary_static, t)?;
        let secondary_source = vm638840(0x12000005e9d, &secondary_state, secondary_static, t)?;
        primary_lanes.extend(vm638840(0x10000000214, &primary_source, &primary_source, t)?);
        secondary_lanes.extend(vm638840(0x10000003231, &secondary_source, &secondary_source, t)?);
    }
    Ok((primary_lanes, secondary_lanes))
}

// ---- 6388f0 tail layers (internal → final raw; prefinal/workspace/stage/pack builders)

/// kit `builder6388f0FinalRawBlocks`.
pub fn final_raw_blocks_6388f0(internal_blocks: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if internal_blocks.len() % 0x42 != 0 {
        return Err(slice_err(format!("6388f0 internal blocks: encoded length {}", internal_blocks.len())));
    }
    let mut out = Vec::with_capacity(internal_blocks.len());
    for start in (0..internal_blocks.len()).step_by(0x42) {
        let block = &internal_blocks[start..start + 0x42];
        out.extend(vm638840(0x42000007e29, block, block, t)?);
    }
    Ok(out)
}

/// kit `builder6388f0PrefinalLen32InternalBlocks`.
pub fn prefinal_len32_internal_blocks_6388f0(prefinal_source_blocks: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if prefinal_source_blocks.len() != 2 * 0x42 {
        return Err(slice_err(format!("6388f0 prefinal wants {} bytes, got {}", 2 * 0x42, prefinal_source_blocks.len())));
    }
    let call0 = &prefinal_source_blocks[..0x42];
    let call1 = &prefinal_source_blocks[0x42..];
    let block1 = vm638840(0x42000003bf9, call0, call0, t)?;
    let block0 = vm638840(0x42000003bf9, call1, call1, t)?;
    let mut out = block0;
    out.extend(block1);
    Ok(out)
}

/// kit `builder6388f0Len32PrefinalSourcesFromWorkspace`.
pub fn len32_prefinal_sources_from_workspace_6388f0(workspace_source: &[u8], t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    if workspace_source.len() != WS_SIZE {
        return Err(slice_err(format!("6388f0 workspace wants {WS_SIZE} bytes, got {}", workspace_source.len())));
    }
    let workspace = vm638840(0x10a000006cd1, workspace_source, workspace_source, t)?;
    if workspace.len() != WS_SIZE {
        return Err(slice_err(format!("6388f0 workspace produced {} bytes", workspace.len())));
    }
    let first_prefinal = workspace[..0x42].to_vec();
    let updated = vm6420d8(0x1000ca0100063f1, &workspace, &workspace, t)?;
    if updated.len() != WS_SIZE {
        return Err(slice_err(format!("6388f0 workspace update produced {} bytes", updated.len())));
    }
    let second_prefinal = updated[..0x42].to_vec();
    let mut out = first_prefinal;
    out.extend(second_prefinal);
    Ok(out)
}

/// kit `builder6388f0Len32PrefinalSourcesFromStageInputs`.
pub fn len32_prefinal_sources_from_stage_inputs_6388f0(
    stage_a_source: &[u8],
    stage_b_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if stage_a_source.len() != STAGE_SIZE || stage_b_source.len() != STAGE_SIZE {
        return Err(slice_err(format!("6388f0 stage inputs must be {STAGE_SIZE} bytes, got {} / {}", stage_a_source.len(), stage_b_source.len())));
    }
    let stage_a = vm638840(0x11a000003e76, stage_a_source, stage_a_source, t)?;
    let stage_b = vm638840(0x11a000004f0c, stage_b_source, &stage_a, t)?;
    let stage_c = vm638840(0x11a000004b16, &stage_b, &stage_a, t)?;
    let stage_d_source = &stage_c[..WS_SIZE];
    let stage_d = vm638840(0x10a0000078bb, stage_d_source, stage_d_source, t)?;
    len32_prefinal_sources_from_workspace_6388f0(&stage_d, t)
}

/// kit `pack6388f0Twenty16To282`.
fn pack_twenty16_to_282(head16: &[u8], body_blocks16: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if head16.len() != LANE_BLOCK_SIZE {
        return Err(slice_err(format!("6388f0 pack head must be {LANE_BLOCK_SIZE} bytes, got {}", head16.len())));
    }
    if body_blocks16.len() != (LANE_BLOCK_COUNT - 1) * LANE_BLOCK_SIZE {
        return Err(slice_err(format!("6388f0 pack body must be {} bytes, got {}", (LANE_BLOCK_COUNT - 1) * LANE_BLOCK_SIZE, body_blocks16.len())));
    }
    let mut out = head16.to_vec();
    for offset in (0..body_blocks16.len()).step_by(LANE_BLOCK_SIZE) {
        out.extend(&body_blocks16[offset + 2..offset + LANE_BLOCK_SIZE]);
    }
    Ok(out)
}

/// kit `builder6388f0Len32StageInputsFromPackOutputs`.
pub fn len32_stage_inputs_from_pack_outputs_6388f0(
    stage_b_pack_head16: &[u8],
    stage_b_pack_body16: &[u8],
    stage_a_pack_head16: &[u8],
    stage_a_pack_body16: &[u8],
    t: &FirstPairTables,
) -> Result<(Vec<u8>, Vec<u8>), CryptoError> {
    let stage_b_pack = pack_twenty16_to_282(stage_b_pack_head16, stage_b_pack_body16)?;
    let stage_b_source = vm638840(0x11a000000a2c, &stage_b_pack, &stage_b_pack, t)?;
    let stage_a_source = pack_twenty16_to_282(stage_a_pack_head16, stage_a_pack_body16)?;
    Ok((stage_a_source, stage_b_source))
}

/// kit `builder6388f0PackOutputsFromLaneBlocks`:
/// returns (stageB head, stageB body, stageA head, stageA body).
pub fn pack_outputs_from_lane_blocks_6388f0(
    primary_lane_blocks: &[u8],
    secondary_lane_blocks: &[u8],
    t: &FirstPairTables,
) -> Result<(Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>), CryptoError> {
    let lane_blocks_len = LANE_BLOCK_COUNT * LANE_BLOCK_SIZE;
    if primary_lane_blocks.len() != lane_blocks_len || secondary_lane_blocks.len() != lane_blocks_len {
        return Err(slice_err(format!("6388f0 lane blocks must be {lane_blocks_len} bytes")));
    }
    let stage_b_pack_head = vm638840(0x10000000388, &primary_lane_blocks[..LANE_BLOCK_SIZE], &primary_lane_blocks[..LANE_BLOCK_SIZE], t)?;
    let mut stage_b_pack_body = Vec::with_capacity((LANE_BLOCK_COUNT - 1) * LANE_BLOCK_SIZE);
    for block_start in (LANE_BLOCK_SIZE..lane_blocks_len).step_by(LANE_BLOCK_SIZE) {
        let block = &primary_lane_blocks[block_start..block_start + LANE_BLOCK_SIZE];
        stage_b_pack_body.extend(vm638840(0x10000003bd7, block, block, t)?);
    }
    let stage_a_pack_head = vm638840(0x100000062d7, &secondary_lane_blocks[..LANE_BLOCK_SIZE], &secondary_lane_blocks[..LANE_BLOCK_SIZE], t)?;
    let mut stage_a_pack_body = Vec::with_capacity((LANE_BLOCK_COUNT - 1) * LANE_BLOCK_SIZE);
    for block_start in (LANE_BLOCK_SIZE..lane_blocks_len).step_by(LANE_BLOCK_SIZE) {
        let block = &secondary_lane_blocks[block_start..block_start + LANE_BLOCK_SIZE];
        stage_a_pack_body.extend(vm638840(0x10000008177, block, block, t)?);
    }
    Ok((stage_b_pack_head, stage_b_pack_body, stage_a_pack_head, stage_a_pack_body))
}

// ---------------------------------------------------------------- derive wrappers

use super::slice;

/// kit `deriveFrom660448Sources`.
pub fn derive_from_660448_sources(
    first_raw_blocks: &[u8],
    second_raw_blocks: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let mut combined = first_raw_blocks.to_vec();
    combined.extend(second_raw_blocks);
    slice::derive_from_660448_raw_descriptor(&combined, src4, offset, length, t)
}

/// kit `deriveFrom64d774RawStreams`.
pub fn derive_from_64d774_raw_streams(
    first_raw_blocks: &[u8],
    second_raw_blocks: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_for_660448 = slice::constructor670a54_ptr10_blocks(first_raw_blocks, t)?;
    let second_for_660448 = slice::constructor670a54_ptr10_blocks(second_raw_blocks, t)?;
    derive_from_660448_sources(&first_for_660448, &second_for_660448, src4, offset, length, t)
}

/// kit `deriveFrom6388f0InternalStreams`.
pub fn derive_from_6388f0_internal_streams(
    first_internal_blocks: &[u8],
    second_internal_blocks: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_raw = final_raw_blocks_6388f0(first_internal_blocks, t)?;
    let second_raw = final_raw_blocks_6388f0(second_internal_blocks, t)?;
    derive_from_64d774_raw_streams(&first_raw, &second_raw, src4, offset, length, t)
}

/// kit `deriveFrom6388f0PrefinalLen32Streams`.
pub fn derive_from_6388f0_prefinal_len32_streams(
    first_prefinal_blocks: &[u8],
    second_prefinal_blocks: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_internal = prefinal_len32_internal_blocks_6388f0(first_prefinal_blocks, t)?;
    let second_internal = prefinal_len32_internal_blocks_6388f0(second_prefinal_blocks, t)?;
    derive_from_6388f0_internal_streams(&first_internal, &second_internal, src4, offset, length, t)
}

/// kit `deriveFrom6388f0WorkspaceLen32Streams`.
pub fn derive_from_6388f0_workspace_len32_streams(
    first_workspace_source: &[u8],
    second_workspace_source: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_prefinal = len32_prefinal_sources_from_workspace_6388f0(first_workspace_source, t)?;
    let second_prefinal = len32_prefinal_sources_from_workspace_6388f0(second_workspace_source, t)?;
    derive_from_6388f0_prefinal_len32_streams(&first_prefinal, &second_prefinal, src4, offset, length, t)
}

/// kit `deriveFrom6388f0StageLen32Streams`.
pub fn derive_from_6388f0_stage_len32_streams(
    first_stage_a_source: &[u8],
    first_stage_b_source: &[u8],
    second_stage_a_source: &[u8],
    second_stage_b_source: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_prefinal = len32_prefinal_sources_from_stage_inputs_6388f0(first_stage_a_source, first_stage_b_source, t)?;
    let second_prefinal = len32_prefinal_sources_from_stage_inputs_6388f0(second_stage_a_source, second_stage_b_source, t)?;
    derive_from_6388f0_prefinal_len32_streams(&first_prefinal, &second_prefinal, src4, offset, length, t)
}

#[allow(clippy::too_many_arguments)]
/// kit `deriveFrom6388f0PackLen32Streams`.
pub fn derive_from_6388f0_pack_len32_streams(
    first_stage_b_pack_head16: &[u8],
    first_stage_b_pack_body16: &[u8],
    first_stage_a_pack_head16: &[u8],
    first_stage_a_pack_body16: &[u8],
    second_stage_b_pack_head16: &[u8],
    second_stage_b_pack_body16: &[u8],
    second_stage_a_pack_head16: &[u8],
    second_stage_a_pack_body16: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_stage = len32_stage_inputs_from_pack_outputs_6388f0(
        first_stage_b_pack_head16, first_stage_b_pack_body16, first_stage_a_pack_head16, first_stage_a_pack_body16, t,
    )?;
    let second_stage = len32_stage_inputs_from_pack_outputs_6388f0(
        second_stage_b_pack_head16, second_stage_b_pack_body16, second_stage_a_pack_head16, second_stage_a_pack_body16, t,
    )?;
    derive_from_6388f0_stage_len32_streams(
        &first_stage.0, &first_stage.1, &second_stage.0, &second_stage.1, src4, offset, length, t,
    )
}

/// kit `deriveFrom6388f0LaneLen32Streams`.
pub fn derive_from_6388f0_lane_len32_streams(
    first_primary_lane_blocks: &[u8],
    first_secondary_lane_blocks: &[u8],
    second_primary_lane_blocks: &[u8],
    second_secondary_lane_blocks: &[u8],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_pack = pack_outputs_from_lane_blocks_6388f0(first_primary_lane_blocks, first_secondary_lane_blocks, t)?;
    let second_pack = pack_outputs_from_lane_blocks_6388f0(second_primary_lane_blocks, second_secondary_lane_blocks, t)?;
    derive_from_6388f0_pack_len32_streams(
        &first_pack.0, &first_pack.1, &first_pack.2, &first_pack.3,
        &second_pack.0, &second_pack.1, &second_pack.2, &second_pack.3,
        src4, offset, length, t,
    )
}

/// kit `deriveFrom6388f0ScheduleLen32Streams`.
pub fn derive_from_6388f0_schedule_len32_streams(
    first_schedule_words: &[u32],
    second_schedule_words: &[u32],
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let (first_primary, first_secondary) = lane_blocks_from_schedule_words_6388f0(first_schedule_words, t)?;
    let (second_primary, second_secondary) = lane_blocks_from_schedule_words_6388f0(second_schedule_words, t)?;
    derive_from_6388f0_lane_len32_streams(
        &first_primary, &first_secondary, &second_primary, &second_secondary, src4, offset, length, t,
    )
}

/// kit `deriveFrom63c278ScheduleInputs`.
pub fn derive_from_63c278_schedule_inputs(
    arg0: &[u8],
    first_arg1: &[u8],
    first_arg2: &[u8],
    second_arg1: &[u8],
    second_arg2: &[u8],
    scalar: u64,
    src4: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let first_schedule = schedule_words_63c278(arg0, first_arg1, first_arg2, scalar, t)?;
    let second_schedule = schedule_words_63c278(arg0, second_arg1, second_arg2, scalar, t)?;
    derive_from_6388f0_schedule_len32_streams(&first_schedule, &second_schedule, src4, offset, length, t)
}

/// kit `deriveFromPre63c278ScheduleInputs` — the fixed arg0/scalar path.
pub fn derive_from_pre63c278_schedule_inputs(
    first_arg1: &[u8],
    first_arg2: &[u8],
    second_arg1: &[u8],
    second_arg2: &[u8],
    offset: usize,
    length: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    derive_from_63c278_schedule_inputs(
        &PRE63C278_ARG0_SOURCE, first_arg1, first_arg2, second_arg1, second_arg2,
        PRE63C278_SCALAR, &[0, 0, 0, 1], offset, length, t,
    )
}

/// kit `phase5RawKeyFrom63c278ScheduleInputs`.
pub fn phase5_raw_key_from_63c278_schedule_inputs(
    arg0: &[u8],
    first_arg1: &[u8],
    first_arg2: &[u8],
    second_arg1: &[u8],
    second_arg2: &[u8],
    scalar: u64,
    t: &FirstPairTables,
    sched: &crate::phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_63c278_schedule_inputs(
        arg0, first_arg1, first_arg2, second_arg1, second_arg2, scalar, &[0, 0, 0, 1], 0, 0x10, t,
    )?;
    crate::phase5::derive_raw_key(&source, sched)
}

/// kit `phase5RawKeyFromPre63c278ScheduleInputs`.
pub fn phase5_raw_key_from_pre63c278_schedule_inputs(
    first_arg1: &[u8],
    first_arg2: &[u8],
    second_arg1: &[u8],
    second_arg2: &[u8],
    t: &FirstPairTables,
    sched: &crate::phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_pre63c278_schedule_inputs(first_arg1, first_arg2, second_arg1, second_arg2, 0, 0x10, t)?;
    crate::phase5::derive_raw_key(&source, sched)
}

/// kit `phase5RawKeyFrom6388f0ScheduleLen32Streams`.
pub fn phase5_raw_key_from_6388f0_schedule_len32_streams(
    first_schedule_words: &[u32],
    second_schedule_words: &[u32],
    t: &FirstPairTables,
    sched: &crate::phase5::ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    let source = derive_from_6388f0_schedule_len32_streams(first_schedule_words, second_schedule_words, &[0, 0, 0, 1], 0, 0x10, t)?;
    crate::phase5::derive_raw_key(&source, sched)
}

/// kit `u32AffineBytes63c278`: 88-byte word stream through the per-word table affine.
pub(crate) fn u32_affine_bytes_63c278(
    input: &[u8],
    mul_table: usize,
    add_table: usize,
    label: &str,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    require(input, VEC_BYTES, label)?;
    let mut out = Vec::with_capacity(VEC_BYTES);
    for index in 0..VEC_WORDS {
        let table_offset = (index * 4) & 0x1c;
        let word = u32_table_affine_63c278(
            read_u32_le(input, index * 4),
            mul_table + table_offset,
            add_table + table_offset,
            t,
        )?;
        out.extend_from_slice(&word.to_le_bytes());
    }
    Ok(out)
}

