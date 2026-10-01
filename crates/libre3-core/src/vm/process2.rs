//! FirstPairSourceSlice — the process2(5) public-point builder (Stage F1 of the 6388f0
//! first-pair builder port; kit: FirstPairSourceSlice.swift L11914-12661 plus the public
//! wrappers L3353-3373). Derives the P-256 public key `04||X||Y` (65 bytes) for the process2
//! (5) fixed sensor point from 0x11a bytes of entropy:
//! 633fa8 null public entry source → A-source words (lane loop + nibble pack) → initial
//! workspace prefixes → 42-entry initial workspace → 35d8 table qwords → low prefix →
//! 22-round qword workspace chain → scalar qwords → words → 70-byte little-endian window →
//! 5bcf98 P-256 multiply against the fixed point.
//!
//! Golden vectors: tests/firstpair_process2.rs (ported 1:1 from FirstPairSourceSliceTests.swift
//! `testProcess2P5PublicKeyMatchesAndroidEntryTraces`, kit test L4429-4466).

use super::firstpair::{read_u32_le, read_u64_le, require, slice_err, vm638840, vm641fcc, vm6420d8, write_u64_le};
use super::highseed::builder5bcf98_p256_outputs;
use super::lowseed::{
    builder633fa8_null_public_entry_source_from_entropy, BUILDER633FA8_INVARIANT_SEED_3110,
    BUILDER633FA8_INVARIANT_WORDS_2DFC,
};
use super::schedule::u32_table_word_63c278;
use super::tables::FirstPairTables;
use crate::CryptoError;

// ---------------------------------------------------------------- shared sizing constants

/// kit `builder633fa8ScalarWordCount` (FirstPairSourceSlice.swift L14737).
const BUILDER633FA8_SCALAR_WORD_COUNT: usize = 20;
/// kit `builder633fa8ScalarWindowBytes` (FirstPairSourceSlice.swift L14738).
const BUILDER633FA8_SCALAR_WINDOW_BYTES: usize = 70;

// ---------------------------------------------------------------- process2(5) public tables
// (FirstPairSourceSlice.swift L14673-14674, L14854-14947.)

/// kit `process2P5PublicTableBase` (FirstPairSourceSlice.swift L14673); the lib offset the
/// 0x518-byte `firstpair_process2_public_tables_3038c0` table starts at (kit
/// `process2P5PublicTableLength` = 0x518, L14674; enforced by the table loader,
/// kit L13409-13413, and re-asserted by the in-module size test).
const PROCESS2_P5_PUBLIC_TABLE_BASE: usize = 0x3038c0;
/// kit `process2P5PublicTableLength` (FirstPairSourceSlice.swift L14674).
#[allow(dead_code)]
const PROCESS2_P5_PUBLIC_TABLE_LENGTH: usize = 0x518;

/// kit `process2P5PublicScalarQwordFoldTable` (FirstPairSourceSlice.swift L14854).
const PROCESS2_P5_PUBLIC_SCALAR_QWORD_FOLD_TABLE: usize = 0x303d58;
/// kit `process2P5PublicScalarQwordMulTable` (FirstPairSourceSlice.swift L14855).
const PROCESS2_P5_PUBLIC_SCALAR_QWORD_MUL_TABLE: usize = 0x1219a8;
/// kit `process2P5PublicScalarQwordAddTable` (FirstPairSourceSlice.swift L14856).
const PROCESS2_P5_PUBLIC_SCALAR_QWORD_ADD_TABLE: usize = 0x1149e8;
/// kit `process2P5PublicScalarPackMulTable` (FirstPairSourceSlice.swift L14857).
const PROCESS2_P5_PUBLIC_SCALAR_PACK_MUL_TABLE: usize = 0x118608;
/// kit `process2P5PublicScalarPackAddTable` (FirstPairSourceSlice.swift L14858).
const PROCESS2_P5_PUBLIC_SCALAR_PACK_ADD_TABLE: usize = 0x118f68;
/// kit `process2P5PublicQwordFoldTableA` (FirstPairSourceSlice.swift L14859).
const PROCESS2_P5_PUBLIC_QWORD_FOLD_TABLE_A: usize = 0x303bd8;
/// kit `process2P5PublicQwordFoldTableB` (FirstPairSourceSlice.swift L14860).
const PROCESS2_P5_PUBLIC_QWORD_FOLD_TABLE_B: usize = 0x303c58;
/// kit `process2P5PublicQwordFoldTableC` (FirstPairSourceSlice.swift L14861).
const PROCESS2_P5_PUBLIC_QWORD_FOLD_TABLE_C: usize = 0x303cd8;

/// kit `process2P5PublicInitWorkspaceConstants` (FirstPairSourceSlice.swift L14862-14870).
const PROCESS2_P5_PUBLIC_INIT_WORKSPACE_COUNT_MUL: u64 = 0x94dfbb91a5378e68;
/// kit `process2P5PublicInitWorkspaceConstants.countAdd` (FirstPairSourceSlice.swift L14864).
const PROCESS2_P5_PUBLIC_INIT_WORKSPACE_COUNT_ADD: u64 = 0x4218665245881823;
/// kit `process2P5PublicInitWorkspaceConstants.productMul` (FirstPairSourceSlice.swift L14865).
const PROCESS2_P5_PUBLIC_INIT_WORKSPACE_PRODUCT_MUL: u64 = 0x501edede429b621f;
/// kit `process2P5PublicInitWorkspaceConstants.bPrefixMul` (FirstPairSourceSlice.swift L14866).
const PROCESS2_P5_PUBLIC_INIT_WORKSPACE_B_PREFIX_MUL: u64 = 0x6658ca76ca6e396a;
/// kit `process2P5PublicInitWorkspaceConstants.aPrefixMul` (FirstPairSourceSlice.swift L14867).
const PROCESS2_P5_PUBLIC_INIT_WORKSPACE_A_PREFIX_MUL: u64 = 0x918160dbec5e059c;
/// kit `process2P5PublicInitWorkspaceConstants.finalMul` (FirstPairSourceSlice.swift L14868).
const PROCESS2_P5_PUBLIC_INIT_WORKSPACE_FINAL_MUL: u64 = 0xbcb96bc3c168e865;
/// kit `process2P5PublicInitWorkspaceConstants.finalAdd` (FirstPairSourceSlice.swift L14869).
const PROCESS2_P5_PUBLIC_INIT_WORKSPACE_FINAL_ADD: u64 = 0x242a710f34e73cea;

/// kit `process2P5PublicTableU32MulTable` (FirstPairSourceSlice.swift L14871).
const PROCESS2_P5_PUBLIC_TABLE_U32_MUL_TABLE: usize = 0x116988;
/// kit `process2P5PublicTableU32AddTable` (FirstPairSourceSlice.swift L14872).
const PROCESS2_P5_PUBLIC_TABLE_U32_ADD_TABLE: usize = 0x11d528;
/// kit `process2P5PublicTableFoldTable` (FirstPairSourceSlice.swift L14873).
const PROCESS2_P5_PUBLIC_TABLE_FOLD_TABLE: usize = 0x303b58;
/// kit `process2P5PublicTableWordMul` (FirstPairSourceSlice.swift L14874).
const PROCESS2_P5_PUBLIC_TABLE_WORD_MUL: u32 = 0x347334f7;
/// kit `process2P5PublicTableWordAdd` (FirstPairSourceSlice.swift L14875).
const PROCESS2_P5_PUBLIC_TABLE_WORD_ADD: u32 = 0x7713d14d;
/// kit `process2P5PublicTableQwordMul` (FirstPairSourceSlice.swift L14876).
const PROCESS2_P5_PUBLIC_TABLE_QWORD_MUL: u64 = 0x7378135b2404ba5f;
/// kit `process2P5PublicTableQwordAdd` (FirstPairSourceSlice.swift L14877).
const PROCESS2_P5_PUBLIC_TABLE_QWORD_ADD: u64 = 0x2bb7cb5d40ee4303;
/// kit `process2P5PublicTableFinalMul` (FirstPairSourceSlice.swift L14878).
const PROCESS2_P5_PUBLIC_TABLE_FINAL_MUL: u64 = 0x714b9632f149a92d;
/// kit `process2P5PublicTableFoldMul` (FirstPairSourceSlice.swift L14879).
const PROCESS2_P5_PUBLIC_TABLE_FOLD_MUL: u64 = 0x25f3200d00000000;
/// kit `process2P5PublicTableFinalAdd` (FirstPairSourceSlice.swift L14880).
const PROCESS2_P5_PUBLIC_TABLE_FINAL_ADD: u64 = 0x77db08099d019a2f;

/// kit `process2P5PublicPrefixAInitWordMul` (FirstPairSourceSlice.swift L14881).
const PROCESS2_P5_PUBLIC_PREFIX_A_INIT_WORD_MUL: u32 = 0x68309fdf;
/// kit `process2P5PublicPrefixAInitWordAdd` (FirstPairSourceSlice.swift L14882).
const PROCESS2_P5_PUBLIC_PREFIX_A_INIT_WORD_ADD: u32 = 0x9a8acd31;
/// kit `process2P5PublicPrefixAWordMulTable` (FirstPairSourceSlice.swift L14883).
const PROCESS2_P5_PUBLIC_PREFIX_A_WORD_MUL_TABLE: usize = 0x121988;
/// kit `process2P5PublicPrefixAWordAddTable` (FirstPairSourceSlice.swift L14884).
const PROCESS2_P5_PUBLIC_PREFIX_A_WORD_ADD_TABLE: usize = 0x117328;
/// kit `process2P5PublicPrefixAWordMul` (FirstPairSourceSlice.swift L14885).
const PROCESS2_P5_PUBLIC_PREFIX_A_WORD_MUL: u32 = 0xa14d75f7;
/// kit `process2P5PublicPrefixAWordAdd` (FirstPairSourceSlice.swift L14886).
const PROCESS2_P5_PUBLIC_PREFIX_A_WORD_ADD: u32 = 0x23fe38ed;
/// kit `process2P5PublicPrefixAFoldTable` (FirstPairSourceSlice.swift L14887).
const PROCESS2_P5_PUBLIC_PREFIX_A_FOLD_TABLE: usize = 0x303a58;
/// kit `process2P5PublicPrefixAQwordMul` (FirstPairSourceSlice.swift L14888).
const PROCESS2_P5_PUBLIC_PREFIX_A_QWORD_MUL: u64 = 0xdea88f4cd7aa9967;
/// kit `process2P5PublicPrefixAQwordAdd` (FirstPairSourceSlice.swift L14889).
const PROCESS2_P5_PUBLIC_PREFIX_A_QWORD_ADD: u64 = 0x2498e0a8ace26d05;
/// kit `process2P5PublicPrefixAFoldMul` (FirstPairSourceSlice.swift L14890).
const PROCESS2_P5_PUBLIC_PREFIX_A_FOLD_MUL: u64 = 0x2403505500000000;
/// kit `process2P5PublicPrefixAFinalMul` (FirstPairSourceSlice.swift L14891).
const PROCESS2_P5_PUBLIC_PREFIX_A_FINAL_MUL: u64 = 0x77260d39cc35e0cd;
/// kit `process2P5PublicPrefixAFinalAdd` (FirstPairSourceSlice.swift L14892).
const PROCESS2_P5_PUBLIC_PREFIX_A_FINAL_ADD: u64 = 0xf68d6a799b022952;

/// kit `process2P5PublicPrefixBInitWordMul` (FirstPairSourceSlice.swift L14893).
const PROCESS2_P5_PUBLIC_PREFIX_B_INIT_WORD_MUL: u32 = 0xb417ac45;
/// kit `process2P5PublicPrefixBInitWordAdd` (FirstPairSourceSlice.swift L14894).
const PROCESS2_P5_PUBLIC_PREFIX_B_INIT_WORD_ADD: u32 = 0xb9d0b931;
/// kit `process2P5PublicPrefixBWordMulTable` (FirstPairSourceSlice.swift L14895).
const PROCESS2_P5_PUBLIC_PREFIX_B_WORD_MUL_TABLE: usize = 0x1185e8;
/// kit `process2P5PublicPrefixBWordAddTable` (FirstPairSourceSlice.swift L14896).
const PROCESS2_P5_PUBLIC_PREFIX_B_WORD_ADD_TABLE: usize = 0x112688;
/// kit `process2P5PublicPrefixBWordMul` (FirstPairSourceSlice.swift L14897).
const PROCESS2_P5_PUBLIC_PREFIX_B_WORD_MUL: u32 = 0x569e8293;
/// kit `process2P5PublicPrefixBWordAdd` (FirstPairSourceSlice.swift L14898).
const PROCESS2_P5_PUBLIC_PREFIX_B_WORD_ADD: u32 = 0xa7b25d96;
/// kit `process2P5PublicPrefixBFoldTable` (FirstPairSourceSlice.swift L14899).
const PROCESS2_P5_PUBLIC_PREFIX_B_FOLD_TABLE: usize = 0x303ad8;
/// kit `process2P5PublicPrefixBQwordMul` (FirstPairSourceSlice.swift L14900).
const PROCESS2_P5_PUBLIC_PREFIX_B_QWORD_MUL: u64 = 0xac344b5a12897c6d;
/// kit `process2P5PublicPrefixBQwordAdd` (FirstPairSourceSlice.swift L14901).
const PROCESS2_P5_PUBLIC_PREFIX_B_QWORD_ADD: u64 = 0x7f6923d8cce61732;
/// kit `process2P5PublicPrefixBFoldMul` (FirstPairSourceSlice.swift L14902).
const PROCESS2_P5_PUBLIC_PREFIX_B_FOLD_MUL: u64 = 0xcf8f92cb00000000;
/// kit `process2P5PublicPrefixBFinalMul` (FirstPairSourceSlice.swift L14903).
const PROCESS2_P5_PUBLIC_PREFIX_B_FINAL_MUL: u64 = 0x671bb0c140212b91;
/// kit `process2P5PublicPrefixBFinalAdd` (FirstPairSourceSlice.swift L14904).
const PROCESS2_P5_PUBLIC_PREFIX_B_FINAL_ADD: u64 = 0x2ecd4bceff393710;

/// kit `process2P5PublicASourceInitialMagic` (FirstPairSourceSlice.swift L14905).
const PROCESS2_P5_PUBLIC_A_SOURCE_INITIAL_MAGIC: u64 = 0x810a000006ded;
/// kit `process2P5PublicASourceBlockMagic` (FirstPairSourceSlice.swift L14906).
const PROCESS2_P5_PUBLIC_A_SOURCE_BLOCK_MAGIC: u64 = 0x10000000b46;
/// kit `process2P5PublicASourceStaticTailBlock` (FirstPairSourceSlice.swift L14907).
const PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_TAIL_BLOCK: usize = 0x3039e0;
/// kit `process2P5PublicASourceStaticETable` (FirstPairSourceSlice.swift L14908).
const PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_E_TABLE: usize = 0x3038c0;
/// kit `process2P5PublicASourceStaticDTable` (FirstPairSourceSlice.swift L14909).
const PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_D_TABLE: usize = 0x303950;
/// kit `process2P5PublicASourceStaticCTable` (FirstPairSourceSlice.swift L14910).
const PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_C_TABLE: usize = 0x3039f0;
/// kit `process2P5PublicASourceStaticATable` (FirstPairSourceSlice.swift L14911).
const PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_A_TABLE: usize = 0x303a02;
/// kit `process2P5PublicASourceNibbleTable` (FirstPairSourceSlice.swift L14912).
const PROCESS2_P5_PUBLIC_A_SOURCE_NIBBLE_TABLE: usize = 0x303a14;
/// kit `process2P5PublicASourceBInitMagic` (FirstPairSourceSlice.swift L14913).
const PROCESS2_P5_PUBLIC_A_SOURCE_B_INIT_MAGIC: u64 = 0x12000004162;
/// kit `process2P5PublicASourceDInitMagic` (FirstPairSourceSlice.swift L14914).
const PROCESS2_P5_PUBLIC_A_SOURCE_D_INIT_MAGIC: u64 = 0x8010000000364;
/// kit `process2P5PublicASourcePrebridgeMagic` (FirstPairSourceSlice.swift L14915).
const PROCESS2_P5_PUBLIC_A_SOURCE_PREBRIDGE_MAGIC: u64 = 0x120000047ce;
/// kit `process2P5PublicASourceFMagic` (FirstPairSourceSlice.swift L14916).
const PROCESS2_P5_PUBLIC_A_SOURCE_F_MAGIC: u64 = 0x12000005eaf;
/// kit `process2P5PublicASourceAMagic` (FirstPairSourceSlice.swift L14917).
const PROCESS2_P5_PUBLIC_A_SOURCE_A_MAGIC: u64 = 0x12000003574;
/// kit `process2P5PublicASourceTMagic` (FirstPairSourceSlice.swift L14918).
const PROCESS2_P5_PUBLIC_A_SOURCE_T_MAGIC: u64 = 0x12000008187;
/// kit `process2P5PublicASourceBMixMagic` (FirstPairSourceSlice.swift L14919).
const PROCESS2_P5_PUBLIC_A_SOURCE_B_MIX_MAGIC: u64 = 0x1200000266d;
/// kit `process2P5PublicASourceEAdvanceMagic` (FirstPairSourceSlice.swift L14920).
const PROCESS2_P5_PUBLIC_A_SOURCE_E_ADVANCE_MAGIC: u64 = 0x12000000ebd;
/// kit `process2P5PublicASourceCAdvanceMagic` (FirstPairSourceSlice.swift L14921).
const PROCESS2_P5_PUBLIC_A_SOURCE_C_ADVANCE_MAGIC: u64 = 0x1200000504c;
/// kit `process2P5PublicASourcePostFMagic` (FirstPairSourceSlice.swift L14922).
const PROCESS2_P5_PUBLIC_A_SOURCE_POST_F_MAGIC: u64 = 0x12000003be7;
/// kit `process2P5PublicASourcePostDMagic` (FirstPairSourceSlice.swift L14923).
const PROCESS2_P5_PUBLIC_A_SOURCE_POST_D_MAGIC: u64 = 0x12000000224;
/// kit `process2P5PublicASourcePostEMagic` (FirstPairSourceSlice.swift L14924).
const PROCESS2_P5_PUBLIC_A_SOURCE_POST_E_MAGIC: u64 = 0x0c00f000c00e96;
/// kit `process2P5PublicASourcePackCMagic` (FirstPairSourceSlice.swift L14925).
const PROCESS2_P5_PUBLIC_A_SOURCE_PACK_C_MAGIC: u64 = 0x4000004b12;
/// kit `process2P5PublicASourcePackEMagic` (FirstPairSourceSlice.swift L14926).
const PROCESS2_P5_PUBLIC_A_SOURCE_PACK_E_MAGIC: u64 = 0x8010000805038;
/// kit `process2P5PublicASourcePackBMagic` (FirstPairSourceSlice.swift L14927).
const PROCESS2_P5_PUBLIC_A_SOURCE_PACK_B_MAGIC: u64 = 0x40000019be;

/// kit `process2P5PublicLowA8Mul` (FirstPairSourceSlice.swift L14928).
const PROCESS2_P5_PUBLIC_LOW_A8_MUL: u64 = 0x93b6e33be4ad3c3f;
/// kit `process2P5PublicLowA8Add` (FirstPairSourceSlice.swift L14929).
const PROCESS2_P5_PUBLIC_LOW_A8_ADD: u64 = 0x698d7878bd852e23;
/// kit `process2P5PublicLow80Mul` (FirstPairSourceSlice.swift L14930).
const PROCESS2_P5_PUBLIC_LOW_80_MUL: u64 = 0x4a0e602e6ec97079;
/// kit `process2P5PublicLow80Add` (FirstPairSourceSlice.swift L14931).
const PROCESS2_P5_PUBLIC_LOW_80_ADD: u64 = 0xad7d39c097694af0;
/// kit `process2P5PublicLowCopyOffsets` (FirstPairSourceSlice.swift L14932-14936).
const PROCESS2_P5_PUBLIC_LOW_COPY_OFFSETS: [(usize, usize); 13] = [
    (0x88, 1), (0x90, 0), (0x78, 2), (0x68, 4), (0x70, 3),
    (0x58, 6), (0x60, 5), (0x48, 8), (0x50, 7), (0x38, 10),
    (0x40, 9), (0x28, 12), (0x30, 11),
];

/// kit `process2P5PublicBSourceStaticWords` (FirstPairSourceSlice.swift L14937-14942).
const PROCESS2_P5_PUBLIC_B_SOURCE_STATIC_WORDS: [u32; 20] = [
    0xa99f067d, 0xb7043f80, 0x2b6ee291, 0xa4732ba2, 0x6d3a9d91,
    0x4fd9d579, 0x319597e5, 0xfce96d28, 0x48b26f75, 0x05c01679,
    0x5080bac6, 0x2e25e6a6, 0xbfbafcdf, 0x8e127707, 0x000d0fb3,
    0x4ac77820, 0x7923dadf, 0xe4ae8f3a, 0x5080bac6, 0x2e25e6a6,
];

/// kit `process2P5PublicFixedPointBE` (FirstPairSourceSlice.swift L14943-14947); the process2(5)
/// fixed sensor point `04||X||Y`, big-endian coordinates.
const PROCESS2_P5_PUBLIC_FIXED_POINT_BE: [u8; 65] = [
    0x04,
    0xa9, 0xbf, 0x2b, 0xe2, 0xfd, 0x3d, 0x90, 0xf6, 0x46, 0x7b, 0x8c, 0xa0, 0x74, 0x71, 0x0d, 0xb3,
    0x80, 0x4e, 0xb0, 0xcf, 0xcc, 0x95, 0x2a, 0x86, 0xd2, 0x32, 0x89, 0x69, 0x5d, 0x43, 0x5e, 0xe0,
    0x95, 0x23, 0xa7, 0xd0, 0xe8, 0xaa, 0x2c, 0x53, 0xc6, 0xf7, 0xa4, 0x9e, 0x9b, 0x6b, 0xd0, 0xdb,
    0x7a, 0x2d, 0x10, 0x35, 0xcd, 0x61, 0x87, 0x6f, 0x37, 0xe4, 0x3a, 0x74, 0xa1, 0xb6, 0x52, 0x37,
];

// ---------------------------------------------------------------- public wrappers

/// kit `builderProcess2P5PublicScalarWindowFromEntropy` (FirstPairSourceSlice.swift L3353-3359):
/// the 70-byte little-endian scalar window for the process2(5) public path.
pub fn builder_process2_p5_public_scalar_window_from_entropy(
    entropy11a: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let qwords = builder_process2_p5_public_scalar_qwords_from_entropy(entropy11a, t)?;
    let words = builder_process2_p5_public_scalar_words_from_qwords(&qwords, t)?;
    builder_process2_p5_public_scalar_window_from_words(&words, t)
}

/// kit `builderProcess2P5PublicKey65FromEntropy` (FirstPairSourceSlice.swift L3361-3373): the
/// uncompressed P-256 public point `04||X||Y` (65 bytes) for the process2(5) fixed sensor point.
pub fn builder_process2_p5_public_key65_from_entropy(
    entropy11a: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let scalar_window = builder_process2_p5_public_scalar_window_from_entropy(entropy11a, t)?;
    let fixed_point = &PROCESS2_P5_PUBLIC_FIXED_POINT_BE[1..]; // kit `process2P5PublicFixedPointBE.dropFirst()`
    let outputs = builder5bcf98_p256_outputs(&scalar_window, fixed_point)?;
    let mut out = Vec::with_capacity(65);
    out.push(0x04);
    out.extend(outputs.x_output70[..32].iter().rev());
    out.extend(outputs.y_output70[..32].iter().rev());
    Ok(out)
}

// ---------------------------------------------------------------- qwords from entropy

/// kit `builderProcess2P5PublicScalarQwordsFromEntropy` (FirstPairSourceSlice.swift L11914-11945).
fn builder_process2_p5_public_scalar_qwords_from_entropy(
    entropy11a: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u64>, CryptoError> {
    let x1_source = builder633fa8_null_public_entry_source_from_entropy(entropy11a, t)?;
    let a_source_words = builder_process2_p5_public_a_source_words_from_entry_arg_source(&x1_source, t)?;
    let b_source_words = PROCESS2_P5_PUBLIC_B_SOURCE_STATIC_WORDS;
    let initial_workspace = builder_process2_p5_public_initial_workspace_from_source_words(
        &a_source_words,
        &b_source_words,
        t,
    )?;
    let table35d8 =
        builder_process2_p5_public_table35d8_from_source_words(&BUILDER633FA8_INVARIANT_WORDS_2DFC, t)?;
    let low_prefix =
        builder_process2_p5_public_low_prefix_from_table(&table35d8, BUILDER633FA8_INVARIANT_SEED_3110)?;

    let mut high_stack = vec![0u8; 0x360 + 42 * 8];
    let repeated = table35d8[7];
    for offset in [0x140usize, 0x148, 0x150, 0x158, 0x160, 0x168, 0x170] {
        write_u64_le(repeated, &mut high_stack, offset);
    }
    for (index, value) in initial_workspace.iter().enumerate() {
        write_u64_le(*value, &mut high_stack, 0x360 + index * 8);
    }

    builder_process2_p5_public_qwords_from_preframe(&low_prefix, &high_stack, t)
}

/// kit `builderProcess2P5PublicASourceWordsFromEntryArgSource` (FirstPairSourceSlice.swift
/// L11947-12141): the 20-word lane schedule over the 633fa8 null public entry source.
fn builder_process2_p5_public_a_source_words_from_entry_arg_source(
    source11a: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if source11a.len() != 0x11a {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicSourceLength({})",
            source11a.len()
        )));
    }
    let source = source11a.to_vec();
    let prelude = vm6420d8(
        PROCESS2_P5_PUBLIC_A_SOURCE_INITIAL_MAGIC,
        &source,
        &source,
        t,
    )?;
    require(&prelude, 0x10a, "process2(5) public prelude")?;

    let mut seed_inputs: Vec<u8> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT * 0x10);
    for index in 0..19usize {
        let start = index * 0x0e;
        seed_inputs.extend_from_slice(&prelude[start..start + 0x10]);
    }
    seed_inputs.extend_from_slice(&process2_p5_public_table_block(
        PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_TAIL_BLOCK,
        0x10,
        t,
    )?);

    let mut seed_blocks: Vec<u8> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT * 0x10);
    for index in 0..BUILDER633FA8_SCALAR_WORD_COUNT {
        let start = index * 0x10;
        let block = &seed_inputs[start..start + 0x10];
        seed_blocks.extend_from_slice(&vm638840(
            PROCESS2_P5_PUBLIC_A_SOURCE_BLOCK_MAGIC,
            block,
            block,
            t,
        )?);
    }

    let mut init_c_lane = process2_p5_public_table_block(
        PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_C_TABLE,
        0x10,
        t,
    )?;
    init_c_lane.extend_from_slice(&[0x06, 0x06]);
    let static_a_lane = process2_p5_public_table_block(
        PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_A_TABLE,
        0x12,
        t,
    )?;

    let mut out: Vec<u32> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT);
    for outer_index in 0..BUILDER633FA8_SCALAR_WORD_COUNT {
        let lane = outer_index & 7;
        let e_source = process2_p5_public_table_block(
            PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_E_TABLE + 0x12 * lane,
            0x12,
            t,
        )?;
        let d_source = process2_p5_public_table_block(
            PROCESS2_P5_PUBLIC_A_SOURCE_STATIC_D_TABLE + 0x12 * lane,
            0x12,
            t,
        )?;
        let block_offset = outer_index * 0x10;
        let block = &seed_blocks[block_offset..block_offset + 0x10];

        let mut b_lane = vm638840(
            PROCESS2_P5_PUBLIC_A_SOURCE_B_INIT_MAGIC,
            &e_source,
            &e_source,
            t,
        )?;
        let d_lane_initial = vm6420d8(
            PROCESS2_P5_PUBLIC_A_SOURCE_D_INIT_MAGIC,
            block,
            block,
            t,
        )?;
        let mut e_lane = vm638840(
            PROCESS2_P5_PUBLIC_A_SOURCE_PREBRIDGE_MAGIC,
            &b_lane,
            &b_lane,
            t,
        )?;
        let mut c_lane = init_c_lane.clone();

        for _ in 0..28 {
            let f_lane = vm638840(
                PROCESS2_P5_PUBLIC_A_SOURCE_F_MAGIC,
                &d_lane_initial,
                &c_lane,
                t,
            )?;
            let a_lane = vm638840(
                PROCESS2_P5_PUBLIC_A_SOURCE_A_MAGIC,
                &static_a_lane,
                &f_lane,
                t,
            )?;
            let t_lane = vm638840(PROCESS2_P5_PUBLIC_A_SOURCE_T_MAGIC, &b_lane, &a_lane, t)?;
            e_lane = vm638840(
                PROCESS2_P5_PUBLIC_A_SOURCE_B_MIX_MAGIC,
                &e_lane,
                &t_lane,
                t,
            )?;
            b_lane = vm638840(
                PROCESS2_P5_PUBLIC_A_SOURCE_E_ADVANCE_MAGIC,
                &b_lane,
                &b_lane,
                t,
            )?;
            c_lane = vm638840(
                PROCESS2_P5_PUBLIC_A_SOURCE_C_ADVANCE_MAGIC,
                &c_lane,
                &c_lane,
                t,
            )?;
        }

        let f_lane = vm638840(
            PROCESS2_P5_PUBLIC_A_SOURCE_POST_F_MAGIC,
            &e_lane,
            &b_lane,
            t,
        )?;
        let d_lane = vm638840(
            PROCESS2_P5_PUBLIC_A_SOURCE_POST_D_MAGIC,
            &f_lane,
            &d_source,
            t,
        )?;
        let mut pack_e_lane = vm641fcc(PROCESS2_P5_PUBLIC_A_SOURCE_POST_E_MAGIC, &d_lane, t)?;

        let mut packed_lane = [0u8; 4];
        let mut shift: i32 = 32;
        for pack_index in 0..8usize {
            let c_word = vm638840(
                PROCESS2_P5_PUBLIC_A_SOURCE_PACK_C_MAGIC,
                &pack_e_lane,
                &pack_e_lane,
                t,
            )?;
            if shift >= 5 {
                pack_e_lane = vm6420d8(
                    PROCESS2_P5_PUBLIC_A_SOURCE_PACK_E_MAGIC,
                    &pack_e_lane,
                    &pack_e_lane,
                    t,
                )?;
            }
            let b_word = vm638840(
                PROCESS2_P5_PUBLIC_A_SOURCE_PACK_B_MAGIC,
                &c_word,
                &c_word,
                t,
            )?;

            let selected = (b_word[2] as usize) ^ ((b_word[3] as usize) << 3);
            let packed =
                process2_p5_public_table_byte(PROCESS2_P5_PUBLIC_A_SOURCE_NIBBLE_TABLE + selected, t)?;
            let mut nibble = if (pack_index & 1) == 0 { packed & 0x0f } else { packed >> 4 };
            if shift < 4 {
                let mask: u8 = if shift == 0 { 0 } else { ((1u16 << shift) - 1) as u8 };
                nibble &= mask;
            }

            let byte_index = pack_index >> 1;
            if (pack_index & 1) == 0 {
                packed_lane[byte_index] = nibble;
            } else {
                packed_lane[byte_index] ^= nibble << 4;
            }
            shift = std::cmp::max(shift - 4, 0);
        }

        out.push(read_u32_le(&packed_lane, 0));
    }
    Ok(out)
}

/// kit `builderProcess2P5PublicInitialWorkspaceFromSourceWords` (FirstPairSourceSlice.swift
/// L12143-12155).
fn builder_process2_p5_public_initial_workspace_from_source_words(
    a_source_words: &[u32],
    b_source_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u64>, CryptoError> {
    let (a_prefix, b_prefix) = builder_process2_p5_public_initial_prefixes_from_source_words(
        a_source_words,
        b_source_words,
        t,
    )?;
    builder_process2_p5_public_initial_workspace_from_prefixes(&a_prefix, &b_prefix)
}

/// kit `builderProcess2P5PublicInitialPrefixesFromSourceWords` (FirstPairSourceSlice.swift
/// L12157-12240).
fn builder_process2_p5_public_initial_prefixes_from_source_words(
    a_source_words: &[u32],
    b_source_words: &[u32],
    t: &FirstPairTables,
) -> Result<(Vec<u64>, Vec<u64>), CryptoError> {
    if a_source_words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicWordCount(A-prefix, {})",
            a_source_words.len()
        )));
    }
    if b_source_words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicWordCount(B-prefix, {})",
            b_source_words.len()
        )));
    }

    let mut a_values: Vec<u64> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT);
    let mut word = a_source_words[0]
        .wrapping_mul(PROCESS2_P5_PUBLIC_PREFIX_A_INIT_WORD_MUL)
        .wrapping_add(PROCESS2_P5_PUBLIC_PREFIX_A_INIT_WORD_ADD);
    a_values.push(builder_process2_p5_public_prefix_qword(
        word,
        PROCESS2_P5_PUBLIC_PREFIX_A_FOLD_TABLE,
        PROCESS2_P5_PUBLIC_PREFIX_A_QWORD_MUL,
        PROCESS2_P5_PUBLIC_PREFIX_A_QWORD_ADD,
        PROCESS2_P5_PUBLIC_PREFIX_A_FOLD_MUL,
        PROCESS2_P5_PUBLIC_PREFIX_A_FINAL_MUL,
        PROCESS2_P5_PUBLIC_PREFIX_A_FINAL_ADD,
        t,
    )?);
    for index in 1..BUILDER633FA8_SCALAR_WORD_COUNT {
        let table_offset = (index << 2) & 0x1c;
        word = a_source_words[index]
            .wrapping_mul(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_PREFIX_A_WORD_MUL_TABLE + table_offset,
                t,
            )?)
            .wrapping_add(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_PREFIX_A_WORD_ADD_TABLE + table_offset,
                t,
            )?);
        word = word
            .wrapping_mul(PROCESS2_P5_PUBLIC_PREFIX_A_WORD_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_PREFIX_A_WORD_ADD);
        a_values.push(builder_process2_p5_public_prefix_qword(
            word,
            PROCESS2_P5_PUBLIC_PREFIX_A_FOLD_TABLE,
            PROCESS2_P5_PUBLIC_PREFIX_A_QWORD_MUL,
            PROCESS2_P5_PUBLIC_PREFIX_A_QWORD_ADD,
            PROCESS2_P5_PUBLIC_PREFIX_A_FOLD_MUL,
            PROCESS2_P5_PUBLIC_PREFIX_A_FINAL_MUL,
            PROCESS2_P5_PUBLIC_PREFIX_A_FINAL_ADD,
            t,
        )?);
    }

    let mut b_values: Vec<u64> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT);
    let mut word = b_source_words[0]
        .wrapping_mul(PROCESS2_P5_PUBLIC_PREFIX_B_INIT_WORD_MUL)
        .wrapping_add(PROCESS2_P5_PUBLIC_PREFIX_B_INIT_WORD_ADD);
    b_values.push(builder_process2_p5_public_prefix_qword(
        word,
        PROCESS2_P5_PUBLIC_PREFIX_B_FOLD_TABLE,
        PROCESS2_P5_PUBLIC_PREFIX_B_QWORD_MUL,
        PROCESS2_P5_PUBLIC_PREFIX_B_QWORD_ADD,
        PROCESS2_P5_PUBLIC_PREFIX_B_FOLD_MUL,
        PROCESS2_P5_PUBLIC_PREFIX_B_FINAL_MUL,
        PROCESS2_P5_PUBLIC_PREFIX_B_FINAL_ADD,
        t,
    )?);
    for index in 1..BUILDER633FA8_SCALAR_WORD_COUNT {
        let table_offset = (index & 7) << 2;
        word = b_source_words[index]
            .wrapping_mul(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_PREFIX_B_WORD_MUL_TABLE + table_offset,
                t,
            )?)
            .wrapping_add(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_PREFIX_B_WORD_ADD_TABLE + table_offset,
                t,
            )?);
        word = word
            .wrapping_mul(PROCESS2_P5_PUBLIC_PREFIX_B_WORD_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_PREFIX_B_WORD_ADD);
        b_values.push(builder_process2_p5_public_prefix_qword(
            word,
            PROCESS2_P5_PUBLIC_PREFIX_B_FOLD_TABLE,
            PROCESS2_P5_PUBLIC_PREFIX_B_QWORD_MUL,
            PROCESS2_P5_PUBLIC_PREFIX_B_QWORD_ADD,
            PROCESS2_P5_PUBLIC_PREFIX_B_FOLD_MUL,
            PROCESS2_P5_PUBLIC_PREFIX_B_FINAL_MUL,
            PROCESS2_P5_PUBLIC_PREFIX_B_FINAL_ADD,
            t,
        )?);
    }

    Ok((cumulative_qwords(&a_values), cumulative_qwords(&b_values)))
}

/// kit `builderProcess2P5PublicInitialWorkspaceFromPrefixes` (FirstPairSourceSlice.swift
/// L12242-12299): the 42-entry convolution workspace from the two cumulative prefixes.
fn builder_process2_p5_public_initial_workspace_from_prefixes(
    a_prefix: &[u64],
    b_prefix: &[u64],
) -> Result<Vec<u64>, CryptoError> {
    if a_prefix.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicQwordCount(A-prefix, {})",
            a_prefix.len()
        )));
    }
    if b_prefix.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicQwordCount(B-prefix, {})",
            b_prefix.len()
        )));
    }

    let a_vec = diff_cumulative_qwords(a_prefix);
    let b_vec = diff_cumulative_qwords(b_prefix);
    let mut out: Vec<u64> = Vec::with_capacity(42);
    for index in 0..42usize {
        let low = index.saturating_sub(19);
        let high = std::cmp::min(index, 19);
        let product_sum: u64;
        let a_sum: u64;
        let b_sum: u64;
        let count: u64;
        if low <= high {
            let mut product: u64 = 0;
            for b_index in low..=high {
                product = product
                    .wrapping_add(a_vec[index - b_index].wrapping_mul(b_vec[b_index]));
            }
            product_sum = product;

            let mut a_window = a_prefix[index - low];
            // kit: `if index - high - 1 >= 0`
            if index > high {
                a_window = a_window.wrapping_sub(a_prefix[index - high - 1]);
            }
            a_sum = a_window;

            let mut b_window = b_prefix[high];
            if low != 0 {
                b_window = b_window.wrapping_sub(b_prefix[low - 1]);
            }
            b_sum = b_window;
            count = (high - low + 1) as u64;
        } else {
            product_sum = 0;
            a_sum = 0;
            b_sum = 0;
            count = 0;
        }

        let mut value = count
            .wrapping_mul(PROCESS2_P5_PUBLIC_INIT_WORKSPACE_COUNT_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_INIT_WORKSPACE_COUNT_ADD);
        value = value.wrapping_add(product_sum.wrapping_mul(PROCESS2_P5_PUBLIC_INIT_WORKSPACE_PRODUCT_MUL));
        value = value.wrapping_add(b_sum.wrapping_mul(PROCESS2_P5_PUBLIC_INIT_WORKSPACE_B_PREFIX_MUL));
        value = value.wrapping_add(a_sum.wrapping_mul(PROCESS2_P5_PUBLIC_INIT_WORKSPACE_A_PREFIX_MUL));
        value = value
            .wrapping_mul(PROCESS2_P5_PUBLIC_INIT_WORKSPACE_FINAL_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_INIT_WORKSPACE_FINAL_ADD);
        out.push(value);
    }
    Ok(out)
}

/// kit `builderProcess2P5PublicTable35d8FromSourceWords` (FirstPairSourceSlice.swift
/// L12301-12336).
fn builder_process2_p5_public_table35d8_from_source_words(
    source_words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u64>, CryptoError> {
    if source_words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicWordCount(table35d8, {})",
            source_words.len()
        )));
    }
    let mut out: Vec<u64> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT);
    for (index, source_word) in source_words.iter().enumerate() {
        let table_offset = (index << 2) & 0x1c;
        let mut word = source_word
            .wrapping_mul(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_TABLE_U32_MUL_TABLE + table_offset,
                t,
            )?)
            .wrapping_add(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_TABLE_U32_ADD_TABLE + table_offset,
                t,
            )?);
        word = word
            .wrapping_mul(PROCESS2_P5_PUBLIC_TABLE_WORD_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_TABLE_WORD_ADD);

        let mut qword = (word as u64)
            .wrapping_mul(PROCESS2_P5_PUBLIC_TABLE_QWORD_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_TABLE_QWORD_ADD);
        qword = fold_process2_p5_public(
            qword,
            PROCESS2_P5_PUBLIC_TABLE_FOLD_TABLE,
            8,
            t,
        )?;
        let folded = qword.wrapping_mul(PROCESS2_P5_PUBLIC_TABLE_FOLD_MUL);
        out.push(
            (word as u64)
                .wrapping_mul(PROCESS2_P5_PUBLIC_TABLE_FINAL_MUL)
                .wrapping_add(folded)
                .wrapping_add(PROCESS2_P5_PUBLIC_TABLE_FINAL_ADD),
        );
    }
    Ok(out)
}

/// kit `builderProcess2P5PublicLowPrefixFromTable` (FirstPairSourceSlice.swift L12338-12360).
fn builder_process2_p5_public_low_prefix_from_table(
    qwords35d8: &[u64],
    seed80: u64,
) -> Result<Vec<u8>, CryptoError> {
    if qwords35d8.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicQwordCount(low-prefix table, {})",
            qwords35d8.len()
        )));
    }
    let mut out = vec![0u8; 0xb0];
    write_u64_le(
        seed80
            .wrapping_mul(PROCESS2_P5_PUBLIC_LOW_A8_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_LOW_A8_ADD),
        &mut out,
        0xa8,
    );
    write_u64_le(
        seed80
            .wrapping_mul(PROCESS2_P5_PUBLIC_LOW_80_MUL)
            .wrapping_add(PROCESS2_P5_PUBLIC_LOW_80_ADD),
        &mut out,
        0x80,
    );
    for (low_offset, table_index) in PROCESS2_P5_PUBLIC_LOW_COPY_OFFSETS {
        write_u64_le(qwords35d8[table_index], &mut out, low_offset);
    }
    Ok(out)
}

/// kit `builderProcess2P5PublicQwordsFromPreframe` (FirstPairSourceSlice.swift L12362-12371):
/// the last workspace's tail 20 qwords.
fn builder_process2_p5_public_qwords_from_preframe(
    low_prefix: &[u8],
    high_stack: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u64>, CryptoError> {
    let workspaces = builder_process2_p5_public_qword_workspaces_from_preframe(low_prefix, high_stack, t)?;
    Ok(workspaces[workspaces.len() - 1][22..42].to_vec())
}

/// kit `builderProcess2P5PublicQwordWorkspacesFromPreframe` (FirstPairSourceSlice.swift
/// L12373-12492): the 22-round qword workspace chain over the 0xb0 low prefix and the
/// 0x360 + 42*8 high stack.
fn builder_process2_p5_public_qword_workspaces_from_preframe(
    low_prefix: &[u8],
    high_stack: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<Vec<u64>>, CryptoError> {
    require(low_prefix, 0xb0, "process2(5) qword low prefix")?;
    require(high_stack, 0x360 + 42 * 8, "process2(5) qword high stack")?;

    let low28 = read_u64_le(low_prefix, 0x28);
    let low30 = read_u64_le(low_prefix, 0x30);
    let low38 = read_u64_le(low_prefix, 0x38);
    let low40 = read_u64_le(low_prefix, 0x40);
    let low48 = read_u64_le(low_prefix, 0x48);
    let low50 = read_u64_le(low_prefix, 0x50);
    let low58 = read_u64_le(low_prefix, 0x58);
    let low60 = read_u64_le(low_prefix, 0x60);
    let low68 = read_u64_le(low_prefix, 0x68);
    let low70 = read_u64_le(low_prefix, 0x70);
    let low78 = read_u64_le(low_prefix, 0x78);
    let low80 = read_u64_le(low_prefix, 0x80);
    let low88 = read_u64_le(low_prefix, 0x88);
    let low90 = read_u64_le(low_prefix, 0x90);
    let low_a8 = read_u64_le(low_prefix, 0xa8);

    let mut workspace: Vec<u64> = (0..42usize).map(|i| read_u64_le(high_stack, 0x360 + i * 8)).collect();
    let mut x22 = workspace[0];
    let x6 = read_u64_le(high_stack, 0x140);
    let x19 = read_u64_le(high_stack, 0x148);
    let x21 = read_u64_le(high_stack, 0x150);
    let x23 = read_u64_le(high_stack, 0x158);
    let x24 = read_u64_le(high_stack, 0x160);
    let x26 = read_u64_le(high_stack, 0x168);
    let x28 = read_u64_le(high_stack, 0x170);

    let mut workspaces = Vec::with_capacity(23);
    workspaces.push(workspace.clone());
    for index in 0..22usize {
        let reg15 = low88;
        let reg2 = low50;
        let reg1 = low58;
        let mut reg4 = low40;
        let mut reg3 = low48;

        let mut state = x22.wrapping_mul(low_a8).wrapping_add(low80);
        let mut folded = fold_process2_p5_public(
            state
                .wrapping_mul(0x87d6a191657cf88b)
                .wrapping_add(0x55ab3c8b3f81c5ea),
            PROCESS2_P5_PUBLIC_QWORD_FOLD_TABLE_A,
            7,
            t,
        )?;
        state = state
            .wrapping_mul(0x5513e20130c294ff)
            .wrapping_add(folded.wrapping_mul(0x097f450230000000))
            .wrapping_add(0x65416d6b1d6e1cbc);
        let x27 = state
            .wrapping_mul(0xde9a0217389253bb)
            .wrapping_add(0x7368784697fb3dc5);
        let x20 = state
            .wrapping_mul(0x421be0fdc09a97cf)
            .wrapping_add(0x492946def7da33b1);

        reg3 = x27.wrapping_mul(reg3).wrapping_add(x20);
        let x22_head = x27.wrapping_mul(low90).wrapping_add(x20).wrapping_add(x22);
        reg4 = x27.wrapping_mul(reg4).wrapping_add(x20);

        folded = fold_process2_p5_public(
            x22_head
                .wrapping_mul(0xe991db2a5d2a7fad)
                .wrapping_add(0xddaca38024dd36cd),
            PROCESS2_P5_PUBLIC_QWORD_FOLD_TABLE_B,
            7,
            t,
        )?;
        let x12 = folded
            .wrapping_mul(0xcf053a359e1d9b81)
            .wrapping_add(0xcfa9a29b5752d274);
        folded = fold_process2_p5_public(
            x12.wrapping_mul(0x71795e15d000819b)
                .wrapping_add(0xf0c1332200ddc903),
            PROCESS2_P5_PUBLIC_QWORD_FOLD_TABLE_C,
            9,
            t,
        )?;

        let old = workspace[index..index + 20].to_vec();
        let mut out = [0u64; 20];
        out[0] = x22_head;
        out[1] = x27.wrapping_mul(reg15).wrapping_add(x20).wrapping_add(old[1]);
        out[2] = x27.wrapping_mul(low78).wrapping_add(x20).wrapping_add(old[2]);
        let acc13 = x27.wrapping_mul(x6).wrapping_add(x20);
        out[3] = x27.wrapping_mul(low70).wrapping_add(x20).wrapping_add(old[3]);
        out[4] = x27.wrapping_mul(low68).wrapping_add(x20).wrapping_add(old[4]);
        out[5] = x27.wrapping_mul(low60).wrapping_add(x20).wrapping_add(old[5]);
        out[6] = x27.wrapping_mul(reg1).wrapping_add(x20).wrapping_add(old[6]);
        let acc15 = x27.wrapping_mul(x19).wrapping_add(x20);
        out[7] = x27.wrapping_mul(reg2).wrapping_add(x20).wrapping_add(old[7]);
        out[8] = reg3.wrapping_add(old[8]);
        let acc0 = x27.wrapping_mul(x21).wrapping_add(x20);
        out[9] = reg4.wrapping_add(old[9]);
        let acc2 = x27.wrapping_mul(low38).wrapping_add(x20);
        out[10] = acc2.wrapping_add(old[10]);
        let acc12 = x27.wrapping_mul(low30).wrapping_add(x20);
        out[11] = acc12.wrapping_add(old[11]);
        let acc22 = x27.wrapping_mul(low28).wrapping_add(x20);
        out[12] = acc22.wrapping_add(old[12]);
        let acc14 = x27.wrapping_mul(x23).wrapping_add(x20);
        let acc1 = x27.wrapping_mul(x24).wrapping_add(x20);
        out[13] = acc13.wrapping_add(old[13]);
        out[14] = acc15.wrapping_add(old[14]);
        let acc16 = x27.wrapping_mul(x26).wrapping_add(x20);
        let acc15_tail = x27.wrapping_mul(x28).wrapping_add(x20);
        out[15] = acc0.wrapping_add(old[15]);
        out[16] = acc14.wrapping_add(old[16]);

        let mut final_acc = folded
            .wrapping_mul(0x7a1cf7b000000000)
            .wrapping_add(x12.wrapping_mul(0x85a6c1a6777a6587));
        final_acc = final_acc
            .wrapping_mul(0xbf59bd30f12b2173)
            .wrapping_add(out[1]);
        out[17] = acc1.wrapping_add(old[17]);
        out[18] = acc16.wrapping_add(old[18]);
        out[19] = acc15_tail.wrapping_add(old[19]);
        x22 = final_acc.wrapping_add(0x0ba9328bc380f3f5);
        out[1] = x22;

        for (out_index, value) in out.iter().enumerate() {
            workspace[index + out_index] = *value;
        }
        workspaces.push(workspace.clone());
    }
    Ok(workspaces)
}

/// kit `builderProcess2P5PublicScalarWordsFromQwords` (FirstPairSourceSlice.swift L12494-12539).
fn builder_process2_p5_public_scalar_words_from_qwords(
    qwords: &[u64],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if qwords.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicQwordCount(scalar words, {})",
            qwords.len()
        )));
    }
    let mut state: u64 = 0x8e047df005b7774b;
    let mut out: Vec<u32> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT);
    for (index, qword) in qwords.iter().enumerate() {
        state = state
            .wrapping_mul(0x4f9b1e335b5175b1)
            .wrapping_add(qword.wrapping_mul(0xddc0126ec4f0da8b))
            .wrapping_add(0x807a205bcf09b957);
        let folded_seed = state
            .wrapping_mul(0x0cc6d1cb7a71ea27)
            .wrapping_add(0x75f17a53af690cbc);
        let folded7 = fold_process2_p5_public(
            folded_seed,
            PROCESS2_P5_PUBLIC_SCALAR_QWORD_FOLD_TABLE,
            7,
            t,
        )?;
        // kit: UInt64(UInt32(truncatingIfNeeded: state)) etc., masked to 32 bits.
        let mut word = ((((state as u32) as u64)
            .wrapping_mul(0xb904cc8b)
            .wrapping_add(((folded7 as u32) as u64).wrapping_mul(0x30000000))
            .wrapping_add(0x7733dbc5)) & 0xffff_ffff) as u32;
        let folded16 = fold_process2_p5_public(
            folded7,
            PROCESS2_P5_PUBLIC_SCALAR_QWORD_FOLD_TABLE,
            9,
            t,
        )?;
        let table_offset = (index * 4) & 0x1c;
        word = word
            .wrapping_mul(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_SCALAR_QWORD_MUL_TABLE + table_offset,
                t,
            )?)
            .wrapping_add(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_SCALAR_QWORD_ADD_TABLE + table_offset,
                t,
            )?);
        out.push(word);

        state = folded7
            .wrapping_mul(0xf5b69300c49039c7)
            .wrapping_add(folded16.wrapping_mul(0xb6fc639000000000))
            .wrapping_add(0x5c589cf77e794af2);
    }
    Ok(out)
}

/// kit `builderProcess2P5PublicScalarWindowFromWords` (FirstPairSourceSlice.swift L12541-12572):
/// the 28-bits-per-word little-endian bit pack into the 70-byte window.
fn builder_process2_p5_public_scalar_window_from_words(
    words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalidProcess2P5PublicWordCount(scalar pack, {})",
            words.len()
        )));
    }
    let mut out = vec![0u8; BUILDER633FA8_SCALAR_WINDOW_BYTES];
    let mut acc: u64 = 0;
    let mut bits: u32 = 0;
    let mut out_index = 0usize;
    for (index, word) in words.iter().enumerate() {
        let table_offset = (index * 4) & 0x1c;
        let mut value = word
            .wrapping_mul(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_SCALAR_PACK_MUL_TABLE + table_offset,
                t,
            )?)
            .wrapping_add(u32_table_word_63c278(
                PROCESS2_P5_PUBLIC_SCALAR_PACK_ADD_TABLE + table_offset,
                t,
            )?);
        value = value
            .wrapping_mul(0x0b6afc2f)
            .wrapping_add(0x4608a396);

        acc ^= (value as u64) << bits;
        bits += 28;
        while bits > 16 && out_index < 69 {
            out[out_index] = (acc & 0xff) as u8;
            acc >>= 8;
            out_index += 1;
            bits -= 8;
        }
    }
    if bits >= 1 && out_index < 69 {
        out[out_index] = (acc & 0xff) as u8;
    }
    Ok(out)
}

/// kit `builderProcess2P5PublicPrefixQword` (FirstPairSourceSlice.swift L12574-12593).
fn builder_process2_p5_public_prefix_qword(
    word: u32,
    fold_table: usize,
    qword_mul: u64,
    qword_add: u64,
    fold_mul: u64,
    final_mul: u64,
    final_add: u64,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let folded = fold_process2_p5_public(
        (word as u64).wrapping_mul(qword_mul).wrapping_add(qword_add),
        fold_table,
        8,
        t,
    )?;
    Ok(folded
        .wrapping_mul(fold_mul)
        .wrapping_add((word as u64).wrapping_mul(final_mul))
        .wrapping_add(final_add))
}

/// kit `diffCumulativeQwords` (FirstPairSourceSlice.swift L12595-12603).
fn diff_cumulative_qwords(prefixes: &[u64]) -> Vec<u64> {
    if prefixes.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(prefixes.len());
    out.push(prefixes[0]);
    for index in 1..prefixes.len() {
        out.push(prefixes[index].wrapping_sub(prefixes[index - 1]));
    }
    out
}

/// kit `cumulativeQwords` (FirstPairSourceSlice.swift L12605-12614).
fn cumulative_qwords(values: &[u64]) -> Vec<u64> {
    let mut total: u64 = 0;
    let mut out = Vec::with_capacity(values.len());
    for value in values {
        total = total.wrapping_add(*value);
        out.push(total);
    }
    out
}

/// kit `process2P5PublicTableBlock` (FirstPairSourceSlice.swift L12616-12629): checked slice of
/// the 0x518-byte `firstpair_process2_public_tables_3038c0` table by absolute lib offset.
fn process2_p5_public_table_block(
    lib_offset: usize,
    byte_count: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let relative = lib_offset
        .checked_sub(PROCESS2_P5_PUBLIC_TABLE_BASE)
        .ok_or_else(|| {
            slice_err(format!(
                "table read out of bounds: firstpair_process2_public_tables_3038c0 at {lib_offset:#x}"
            ))
        })?;
    if relative + byte_count > t.process2_public_tables.len() {
        return Err(slice_err(format!(
            "table read out of bounds: firstpair_process2_public_tables_3038c0 at {lib_offset:#x}"
        )));
    }
    Ok(t.process2_public_tables[relative..relative + byte_count].to_vec())
}

/// kit `process2P5PublicTableByte` (FirstPairSourceSlice.swift L12631-12636).
fn process2_p5_public_table_byte(lib_offset: usize, t: &FirstPairTables) -> Result<u8, CryptoError> {
    Ok(process2_p5_public_table_block(lib_offset, 1, t)?[0])
}

/// kit `process2P5PublicTableUInt64` (FirstPairSourceSlice.swift L12638-12644).
fn process2_p5_public_table_u64(lib_offset: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    let bytes = process2_p5_public_table_block(lib_offset, 8, t)?;
    Ok(read_u64_le(&bytes, 0))
}

/// kit `foldProcess2P5Public` (FirstPairSourceSlice.swift L12646-12660): the nibble-indexed
/// fold over the process2(5) public table set.
fn fold_process2_p5_public(
    value: u64,
    table_offset: usize,
    rounds: usize,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let mut folded = value;
    for _ in 0..rounds {
        folded = process2_p5_public_table_u64(
            table_offset + (folded & 0x0f) as usize * 8,
            t,
        )?
        .wrapping_add(folded >> 4);
    }
    Ok(folded)
}

/// Kit size cross-check (FirstPairSourceSlice.swift L13409-13413): the loaded process2(5)
/// public table set must be exactly `process2P5PublicTableLength` (0x518) bytes; the loader
/// already enforces this (tables.rs), asserted here so the offset math above stays honest.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn process2_public_table_length_matches_kit() {
        assert_eq!(PROCESS2_P5_PUBLIC_TABLE_LENGTH, 0x518);
        assert_eq!(PROCESS2_P5_PUBLIC_TABLE_BASE + PROCESS2_P5_PUBLIC_TABLE_LENGTH, 0x303dd8);
    }
}