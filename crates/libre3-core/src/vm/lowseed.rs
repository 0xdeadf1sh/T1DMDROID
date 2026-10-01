//! FirstPairSourceSlice — the 633fa8 static/null/low-seed layer (kit: FirstPairSourceSlice.swift).
//! Stage B of the 6388f0 first-pair builder port: the row-0 low-seed preimage path over the
//! bundled 0x214-byte entry source (6388f0 low-seed CF0 seeds, tail pair/stage, prelude,
//! seed blocks and the 20-word schedule loop), the 633fa8 static scalar window, the 633fa8
//! null-entropy path (prologue/check entry sources, initial seeding, first loop, schedule
//! acceptance, post-accept blocks, prelude and scalar window, and the retrying entropy-source
//! wrapper), and the shared 633fa8 tail qwords → E10 words → scalar-window packers.
//!
//! Golden vectors: tests/firstpair_null633fa8.rs (ported 1:1 from FirstPairSourceSliceTests.swift
//! and the SessionKeyTests.swift bundled-entry-source check).

use super::firstpair::{checked_slice, read_u32_le, read_u64_le, require, slice_err, vm638840, vm641fcc, vm6420d8, write_u64_le};
use super::schedule::u32_table_word_63c278;
use super::tables::FirstPairTables;
use crate::CryptoError;

// ---------------------------------------------------------------- kit static entry source

/// kit `bundled6388f0LowSeedEntrySource` (FirstPairSourceSlice.swift L467-485): the candidate
/// invariant 532-byte entry source consumed by the row-0 low-seed path.
pub const BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_HEX: &str = concat!(
    "0500000701060202030607010603030501000503000607040400050105030105",
    "0507020005000507050206000104000603060102060003070003020006010703",
    "0303040500040303060500050705000306000704060701060200020002040701",
    "0604030706020405010303030204040400040004070107020706040301070407",
    "0207060403050302020201070700020001050603000500050607000505000707",
    "0105070407010205010301020001040707060604050700010502000201020203",
    "0702020502070700030707070002000401030303010204000702000106020703",
    "0304000606040205040003050305030706020500030305030002040101030204",
    "0305060407010400000204000307040401010706010607040205000503060000",
    "0704040002050105000707030504030502060405050503010103030000040305",
    "0007070207070301020204030701020706010305050506000401050700030607",
    "0704050403060601050204020405030302060701040002030507000604020502",
    "0705000306000000010303070402030204040303060507020500040603000607",
    "0007000104000102050202060700020101050005050302050300030503000006",
    "0404050005030107050505070203040604050402070007010106020401010005",
    "0003070206060006000401020006010303020403020101010606050607030505",
    "0403070707070507020300040707000304020001",
);

/// The decoded `bundled6388f0LowSeedEntrySource` (0x214 = 532 bytes).
pub const BUILDER6388F0_LOW_SEED_ENTRY_SOURCE: [u8; 0x214] =
    decode_hex_const::<0x214>(BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_HEX);

const fn hex_val(c: u8) -> u8 {
    match c {
        b'0'..=b'9' => c - b'0',
        b'a'..=b'f' => c - b'a' + 10,
        _ => panic!("invalid hex byte"),
    }
}

const fn decode_hex_const<const N: usize>(hex: &str) -> [u8; N] {
    let bytes = hex.as_bytes();
    let mut out = [0u8; N];
    let mut i = 0;
    while i < N {
        out[i] = (hex_val(bytes[2 * i]) << 4) | hex_val(bytes[2 * i + 1]);
        i += 1;
    }
    out
}

// ---------------------------------------------------------------- 6388f0 low-seed constants
// (FirstPairSourceSlice.swift L14686-14713.)

const BUILDER6388F0_LOW_SEED_BLOCK_BYTES: usize = 0x10a;
const BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_BYTES: usize = 0x214;
const BUILDER6388F0_LOW_SEED_ENTRY_S898_MAGIC: u64 = 0x10a000001764;
const BUILDER6388F0_LOW_SEED_ENTRY_S78E_MAGIC: u64 = 0x10a000000fe9;
const BUILDER6388F0_LOW_SEED_PREV2_BD0_MAGIC: u64 = 0x10a000006abd;
const BUILDER6388F0_LOW_SEED_PREV2_S684_MAGIC: u64 = 0x10a000005fdb;
const BUILDER6388F0_LOW_SEED_PREV_S684_MAGIC: u64 = 0x10a0000004c6;
const BUILDER6388F0_LOW_SEED_MIDDLE_MAGIC: u64 = 0x10a00000141c;
const BUILDER6388F0_LOW_SEED_PRE_BD0_MAGIC: u64 = 0x10a000003360;
const BUILDER6388F0_LOW_SEED_TAIL_BD0_MAGIC: u64 = 0x10a00000505e;
const BUILDER6388F0_LOW_SEED_TAIL_LEFT_MAGIC: u64 = 0x10a0000003bc;
const BUILDER6388F0_LOW_SEED_TAIL_RIGHT_MAGIC: u64 = 0x10a000006bc7;
const BUILDER6388F0_LOW_SEED_TAIL_S684_MAGIC: u64 = 0x10a000004a08;
const BUILDER6388F0_LOW_SEED_TAIL_STAGE_MAGIC: u64 = 0x10a000007d1f;
const BUILDER6388F0_LOW_SEED_PRELUDE_STAGE_MAGIC: u64 = 0x10a0000018b4;
const BUILDER6388F0_LOW_SEED_PRELUDE_SOURCE_MAGIC: u64 = 0x10a000000c72;
const BUILDER6388F0_LOW_SEED_PRELUDE_MAGIC: u64 = 0x810a000003acb;
const BUILDER6388F0_LOW_SEED_CF0_PHASE1_SEED_MAGIC: u64 = 0x000c107000c03d57;
const BUILDER6388F0_LOW_SEED_CF0_PHASE2_SEED_MAGIC: u64 = 0x000c107000c05886;
const BUILDER6388F0_LOW_SEED_CF0_PHASE3_SEED_MAGIC: u64 = 0x000c107000c036a0;
const BUILDER6388F0_LOW_SEED_STATIC_BASE: usize = 0x2f4d28;
const BUILDER6388F0_LOW_SEED_PREV2_STATIC: usize = 0x2f4d28;
const BUILDER6388F0_LOW_SEED_PRE_STATIC: usize = 0x2f4e32;
const BUILDER6388F0_LOW_SEED_TAIL_STATIC: usize = 0x2f4f3c;
const BUILDER6388F0_LOW_SEED_E10_SOURCE_SHIFTS: [usize; 7] = [4, 8, 16, 32, 64, 128, 256];
const BUILDER6388F0_LOW_SEED_EXPANDED_PRELUDE_BYTES: usize = 0x10c;
const BUILDER6388F0_LOW_SEED_SEED_BLOCKS_BYTES: usize = 20 * 0x10;
const BUILDER6388F0_ROW0_SEED_BLOCK_MAGIC: u64 = 0x10000005fcb;

const BUILDER6388F0_LOW_LOOP_STATIC_BASE: usize = 0x2fe600;
const BUILDER6388F0_LOW_LOOP_STATIC_ETABLE: usize = 0x2fe600;
const BUILDER6388F0_LOW_LOOP_STATIC_DTABLE: usize = 0x2fe690;
const BUILDER6388F0_LOW_SEED_STATIC_BLOCK_OFFSET: usize = 0x2fe720;
const BUILDER6388F0_LOW_LOOP_STATIC_CTABLE: usize = 0x2fe730;
const BUILDER6388F0_LOW_LOOP_STATIC_ATABLE: usize = 0x2fe742;
const BUILDER6388F0_LOW_LOOP_NIBBLE_TABLE: usize = 0x2fe754;
const BUILDER6388F0_LOW_LOOP_LANE_BYTES: usize = 18;
const BUILDER6388F0_LOW_LOOP_EINIT_MAGIC: u64 = 0x12000006ef9;
const BUILDER6388F0_LOW_LOOP_DINIT_MAGIC: u64 = 0x801000000654d;
const BUILDER6388F0_LOW_LOOP_BINIT_MAGIC: u64 = 0x12000007d0d;
const BUILDER6388F0_LOW_LOOP_F_MAGIC: u64 = 0x12000000398;
const BUILDER6388F0_LOW_LOOP_A_MAGIC: u64 = 0x12000006ddb;
const BUILDER6388F0_LOW_LOOP_T_MAGIC: u64 = 0x12000001752;
const BUILDER6388F0_LOW_LOOP_BMIX_MAGIC: u64 = 0x12000002dd3;
const BUILDER6388F0_LOW_LOOP_EADVANCE_MAGIC: u64 = 0x12000007ae7;
const BUILDER6388F0_LOW_LOOP_CADVANCE_MAGIC: u64 = 0x12000006897;
const BUILDER6388F0_LOW_LOOP_POST_F_MAGIC: u64 = 0x12000003241;
const BUILDER6388F0_LOW_LOOP_POST_D_MAGIC: u64 = 0x120000045a8;
const BUILDER6388F0_LOW_LOOP_POST_E_MAGIC: u64 = 0xc000f000c01bfa;
const BUILDER6388F0_LOW_LOOP_PACK_C_MAGIC: u64 = 0x4000004a04;
const BUILDER6388F0_LOW_LOOP_PACK_E_MAGIC: u64 = 0x8010000800350;
const BUILDER6388F0_LOW_LOOP_PACK_B_MAGIC: u64 = 0x4000002271;

// ---------------------------------------------------------------- 633fa8 scalar/E10/tail constants
// (FirstPairSourceSlice.swift L14655-14776.)

const BUILDER633FA8_SCALAR_WORD_COUNT: usize = 20;
const BUILDER633FA8_SCALAR_WINDOW_BYTES: usize = 70;
const BUILDER633FA8_SCALAR_PACK_MUL_TABLE: usize = 0x121808;
const BUILDER633FA8_SCALAR_PACK_ADD_TABLE: usize = 0x11f508;
const BUILDER633FA8_SCALAR_PACK_MUL: u32 = 0x37c0c559;
const BUILDER633FA8_SCALAR_PACK_ADD: u32 = 0xfa73673b;
const BUILDER633FA8_E10_TAIL_FOLD_TABLE: usize = 0x2fea98;
const BUILDER633FA8_E10_TAIL_MUL_TABLE: usize = 0x118e08;
const BUILDER633FA8_E10_TAIL_ADD_TABLE: usize = 0x1229c8;
const BUILDER633FA8_E10_INITIAL_CARRY: u64 = 0x7f9e71176c43f336;
const BUILDER633FA8_E10_QWORD_MUL: u64 = 0xa44fd620a45fddc7;
const BUILDER633FA8_E10_CARRY_MUL: u64 = 0x8b3babe0304f96f9;
const BUILDER633FA8_E10_CARRY_ADD: u64 = 0x12e00771bb9547af;
const BUILDER633FA8_E10_FOLD_SEED_MUL: u64 = 0x039ae28b51354965;
const BUILDER633FA8_E10_FOLD_SEED_ADD: u64 = 0x248e5dc60fc0f4fb;
const BUILDER633FA8_E10_WORD_MUL: u32 = 0x4a018c3b;
const BUILDER633FA8_E10_WORD_ADD: u32 = 0x79d84f1b;
const BUILDER633FA8_E10_NEXT_CARRY_FOLDED7_MUL: u64 = 0xd310088be2b9ce15;
const BUILDER633FA8_E10_NEXT_CARRY_FOLDED16_MUL: u64 = 0xd4631eb000000000;
const BUILDER633FA8_E10_NEXT_CARRY_ADD: u64 = 0x02f149c1c6520051;
const BUILDER633FA8_TAIL_STACK_BYTES: usize = 0x4300;
const BUILDER633FA8_TAIL_A_FOLD_TABLE: usize = 0x2fe798;
const BUILDER633FA8_TAIL_B_FOLD_TABLE: usize = 0x2fe818;
const BUILDER633FA8_TAIL_C_FOLD_TABLE: usize = 0x2fe898;
const BUILDER633FA8_TAIL_D_FOLD_TABLE: usize = 0x2fe918;
const BUILDER633FA8_TAIL_E_FOLD_TABLE: usize = 0x2fe998;
const BUILDER633FA8_TAIL_F_FOLD_TABLE: usize = 0x2fea18;

/// kit `builder633fa8InvariantWords2dfc` (FirstPairSourceSlice.swift L14764-14769).
pub const BUILDER633FA8_INVARIANT_WORDS_2DFC: [u32; 20] = [
    0x9bed19fd, 0xc70a4d0f, 0x8257d22b, 0xe2fafcb3, 0x02c77d20,
    0xb5ed0efa, 0x878c1b06, 0x4bd92d7d, 0x21c6944f, 0xd3ec5d2f,
    0x876fda86, 0x37f3e22a, 0x3cfcd7ce, 0xabdc16eb, 0x84ad2f7d,
    0x4bd92d7d, 0xf647adce, 0xaa7b701e, 0x876fda86, 0x37f3e22a,
];

/// kit `builder633fa8InvariantSeed3110` (FirstPairSourceSlice.swift L14770).
pub const BUILDER633FA8_INVARIANT_SEED_3110: u64 = 0xb6ccf02833a9825e;

/// kit `builder633fa8InvariantWords3120` (FirstPairSourceSlice.swift L14771-14776).
pub const BUILDER633FA8_INVARIANT_WORDS_3120: [u32; 20] = [
    0xb33842d7, 0x7b6ba784, 0xa2f90f36, 0xde5e2ad7, 0x3c3537a9,
    0x81d564f6, 0x339ab4a2, 0x999de03b, 0x56c13b42, 0xff14a487,
    0x5a31640c, 0xc3f85236, 0x3c1dc79e, 0x58a8d4a6, 0x541cb00e,
    0x63323fcd, 0x1aa54a16, 0x01f1b661, 0x5a31640c, 0xc3f85236,
];

// ---------------------------------------------------------------- 633fa8 null constants
// (FirstPairSourceSlice.swift L14659-14660, L14777-14852.)

pub const BUILDER633FA8_NULL_ENTROPY_BYTES: usize = 0x11a;
const BUILDER633FA8_NULL_INITIAL_A_MAGIC: u64 = 0x11a000000236;
const BUILDER633FA8_NULL_INITIAL_B_MAGIC: u64 = 0x11a0000047e0;
const BUILDER633FA8_NULL_SEED_BLOCK_MAGIC: u64 = 0x1000000725d;
const BUILDER633FA8_NULL_SEED_BLOCK_BYTES: usize = 0x10;
const BUILDER633FA8_NULL_SEED_BLOCK_STRIDE: usize = 0x0e;
const BUILDER633FA8_NULL_SEED_BLOCKS_BYTES: usize = 20 * BUILDER633FA8_NULL_SEED_BLOCK_BYTES;
const BUILDER633FA8_NULL_LOOP_STATIC_ETABLE: usize = 0x2fd1f1;
const BUILDER633FA8_NULL_LOOP_STATIC_DTABLE: usize = 0x2fd281;
const BUILDER633FA8_NULL_LOOP_STATIC_CTABLE: usize = 0x2fd311;
const BUILDER633FA8_NULL_LOOP_STATIC_ATABLE: usize = 0x2fd323;
const BUILDER633FA8_NULL_LOOP_NIBBLE_TABLE: usize = 0x303a14;
const BUILDER633FA8_NULL_LOOP_LANE_BYTES: usize = 18;
const BUILDER633FA8_NULL_LOOP_EINIT_MAGIC: u64 = 0x12000000376;
const BUILDER633FA8_NULL_LOOP_DINIT_MAGIC: u64 = 0x8010000002447;
const BUILDER633FA8_NULL_LOOP_BINIT_MAGIC: u64 = 0x120000010f3;
const BUILDER633FA8_NULL_LOOP_F_MAGIC: u64 = 0x12000004596;
const BUILDER633FA8_NULL_LOOP_A_MAGIC: u64 = 0x12000007141;
const BUILDER633FA8_NULL_LOOP_T_MAGIC: u64 = 0x12000008199;
const BUILDER633FA8_NULL_LOOP_BMIX_MAGIC: u64 = 0x1200000726d;
const BUILDER633FA8_NULL_LOOP_EADVANCE_MAGIC: u64 = 0x12000000eab;
const BUILDER633FA8_NULL_LOOP_CADVANCE_MAGIC: u64 = 0x12000005026;
const BUILDER633FA8_NULL_LOOP_POST_F_MAGIC: u64 = 0x12000003e64;
const BUILDER633FA8_NULL_LOOP_POST_D_MAGIC: u64 = 0x12000001be8;
const BUILDER633FA8_NULL_LOOP_POST_E_MAGIC: u64 = 0x0c00f000c079c5;
const BUILDER633FA8_NULL_LOOP_PACK_C_MAGIC: u64 = 0x4000000a28;
const BUILDER633FA8_NULL_LOOP_PACK_E_MAGIC: u64 = 0x8010000806883;
const BUILDER633FA8_NULL_LOOP_PACK_B_MAGIC: u64 = 0x400000186e;
const BUILDER633FA8_NULL_CHECK1_SCHEDULE_MUL_TABLE: usize = 0x11b228;
const BUILDER633FA8_NULL_CHECK1_SOURCE_MUL_TABLE: usize = 0x1152c8;
const BUILDER633FA8_NULL_CHECK1_ADD_TABLE: usize = 0x119688;
const BUILDER633FA8_NULL_CHECK1_FOLD_TABLE: usize = 0x2fd338;
const BUILDER633FA8_NULL_CHECK1_TARGET: u32 = 0xc5e51deb;
const BUILDER633FA8_NULL_CHECK1_FOLD_TARGET: u32 = 0x0b;
const BUILDER633FA8_NULL_CHECK2_SCHEDULE_MUL_TABLE: usize = 0x120e28;
const BUILDER633FA8_NULL_CHECK2_SOURCE_MUL_TABLE: usize = 0x11b248;
const BUILDER633FA8_NULL_CHECK2_ADD_TABLE: usize = 0x11f4e8;
const BUILDER633FA8_NULL_CHECK2_FOLD_TABLE: usize = 0x2fd378;
const BUILDER633FA8_NULL_CHECK2_TARGET: u32 = 0xfbc7d17c;
const BUILDER633FA8_NULL_CHECK2_FOLD_TARGET: u32 = 0x0c;
const BUILDER633FA8_NULL_POST_KEY_MUL_TABLE: usize = 0x118de8;
const BUILDER633FA8_NULL_POST_KEY_ADD_TABLE: usize = 0x113ee8;
const BUILDER633FA8_NULL_POST_INIT_CF0_STATIC: usize = 0x2fe5b8;
const BUILDER633FA8_NULL_POST_INIT_BD0_STATIC: usize = 0x2fe5ca;
const BUILDER633FA8_NULL_POST_TABLE_CF0: usize = 0x2fd3b8;
const BUILDER633FA8_NULL_POST_TABLE_BD0: usize = 0x2fdcb8;
const BUILDER633FA8_NULL_POST_FINAL_CF0_STATIC: usize = 0x2fe5dc;
const BUILDER633FA8_NULL_POST_FINAL_BD0_STATIC: usize = 0x2fe5ee;
const BUILDER633FA8_NULL_POST_INIT_CF0_MAGIC: u64 = 0x12000005508;
const BUILDER633FA8_NULL_POST_INIT_BD0_MAGIC: u64 = 0x12000005874;
const BUILDER633FA8_NULL_POST_MIX_CF0_MAGIC: u64 = 0x12000002dc1;
const BUILDER633FA8_NULL_POST_MIX_BD0_MAGIC: u64 = 0x1200000653b;
const BUILDER633FA8_NULL_POST_FINAL_CF0_MAGIC: u64 = 0x120000019c2;
const BUILDER633FA8_NULL_POST_FINAL_BD0_MAGIC: u64 = 0x1200000552c;
const BUILDER633FA8_NULL_POST_BLOCK_4080_MAGIC: u64 = 0x10000005864;
const BUILDER633FA8_NULL_POST_BLOCK_3F40_MAGIC: u64 = 0x10000006f0b;
const BUILDER633FA8_NULL_PRELUDE_FIRST_4080_MAGIC: u64 = 0x10000001638;
const BUILDER633FA8_NULL_PRELUDE_REST_4080_MAGIC: u64 = 0x10000000d7c;
const BUILDER633FA8_NULL_PRELUDE_BD0_MAGIC: u64 = 0x11a000002ffd;
const BUILDER633FA8_NULL_PRELUDE_FIRST_3F40_MAGIC: u64 = 0x10000003690;
const BUILDER633FA8_NULL_PRELUDE_REST_3F40_MAGIC: u64 = 0x10000008167;
const BUILDER633FA8_NULL_PRELUDE_AB0_MAGIC: u64 = 0x11a000003117;
const BUILDER633FA8_NULL_PRELUDE_STAGE_4080_MAGIC: u64 = 0x11a00000267f;
const BUILDER633FA8_NULL_PRELUDE_F40_MAGIC: u64 = 0x11a000000ecf;
const BUILDER633FA8_NULL_PRELUDE_SOURCE_MAGIC: u64 = 0x10a000004e02;
const BUILDER633FA8_NULL_TABLE_BASE: usize = 0x2fd1f1;
const BUILDER633FA8_NULL_NIBBLE_TABLE_BASE: usize = 0x303a14;

/// kit `builder633fa8NullEntryBitsChecksSource` (FirstPairSourceSlice.swift L14842-14852):
/// the 3-bit-stream prologue bits followed by the two 20-word check sources (266 bytes).
pub const BUILDER633FA8_NULL_ENTRY_BITS_CHECKS_SOURCE_HEX: &str = concat!(
    "3674f8f8a81c394e2bca21f938be42b1adbc94923891e2d38ee57c2d131dcebb",
    "6eed185b2fe5d82f9543c721bdf818eb782dd2545d9b6429daaa6d5b725db614",
    "4b8b6d5dca64a99a7565cb64a9baa66599b5688b34dd9aaadc9a354d53a2cd8a",
    "756bca955b56b42bca12e1343551a11412fbcb2ecd59982c841bdca6eeda33bd",
    "5e2cf8e2f1b468845576104cfaf7f8ceecfa7a15262ed5f6fa9bd9d442e12e97",
    "ec15c1cb4c3ec1ec2881104cfaf7f8ceecfa7a15262ed5f6fa9b8bb0d0b3c47a",
    "1c6cf95016b01676804ff8491d5e0e08e8c3b0504bc066ef57b66fbe719164d2",
    "19086a9310bf190e20a7c27976c5579249c17bedcf2166ef57b6453b9c865799",
    "a2246a9310bf190e20a7",
);

/// The decoded `builder633fa8NullEntryBitsChecksSource` (266 bytes).
pub const BUILDER633FA8_NULL_ENTRY_BITS_CHECKS_SOURCE: [u8; 266] =
    decode_hex_const::<266>(BUILDER633FA8_NULL_ENTRY_BITS_CHECKS_SOURCE_HEX);

// ---------------------------------------------------------------- structs

/// kit `Builder6388f0LowSeedCF0Seeds` (FirstPairSourceSlice.swift L315-325).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0LowSeedCF0Seeds {
    pub phase1: Vec<u8>,
    pub phase2: Vec<u8>,
    pub phase3: Vec<u8>,
}

/// kit `Builder6388f0LowSeedTailPair` (FirstPairSourceSlice.swift L327-335).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0LowSeedTailPair {
    pub left: Vec<u8>,
    pub right: Vec<u8>,
}

/// kit `Builder6388f0LowSeedLoopResult` (FirstPairSourceSlice.swift L337-345).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0LowSeedLoopResult {
    pub final6377f0: Vec<u8>,
    pub schedule_words: Vec<u32>,
}

/// kit `Builder6388f0Row0LowSeedPreimages` (FirstPairSourceSlice.swift L347-357).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6388f0Row0LowSeedPreimages {
    pub out4: Vec<u8>,
    pub out3: Vec<u8>,
    pub out2: Vec<u8>,
}

/// kit `Builder633fa8TailBoundary` (FirstPairSourceSlice.swift L359-379).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder633fa8TailBoundary {
    pub words3ab0: Vec<u32>,
    pub words3120: Vec<u32>,
    pub words2dfc: Vec<u32>,
    pub seed3110: u64,
    pub prelude_source: Vec<u8>,
}

/// kit `Builder633fa8NullEntrySources` (FirstPairSourceSlice.swift L381-391).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder633fa8NullEntrySources {
    pub prologue_source: Vec<u8>,
    pub check1_source_words: Vec<u32>,
    pub check2_source_words: Vec<u32>,
}

/// kit `Builder633fa8NullInitialResult` (FirstPairSourceSlice.swift L393-407).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder633fa8NullInitialResult {
    pub masked_entropy: Vec<u8>,
    pub cf0: Vec<u8>,
    pub e10: Vec<u8>,
    pub seed_inputs: Vec<u8>,
    pub seed_blocks: Vec<u8>,
}

/// kit `Builder633fa8NullFirstLoopResult` (FirstPairSourceSlice.swift L409-417).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder633fa8NullFirstLoopResult {
    pub final_t_lane: Vec<u8>,
    pub schedule_words: Vec<u32>,
}

/// kit `Builder633fa8NullScheduleAcceptance` (FirstPairSourceSlice.swift L419-427).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder633fa8NullScheduleAcceptance {
    pub first_ok: bool,
    pub second_ok: bool,
}

/// kit `Builder633fa8NullPostAcceptResult` (FirstPairSourceSlice.swift L429-437).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder633fa8NullPostAcceptResult {
    pub blocks4080: Vec<u8>,
    pub blocks3f40: Vec<u8>,
}

/// kit `Builder633fa8NullScalarResult` (FirstPairSourceSlice.swift L439-449).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder633fa8NullScalarResult {
    pub scalar_window: Vec<u8>,
    pub entropy11a: Vec<u8>,
    pub attempts: usize,
}

/// kit `Builder6473d0OutputPreimages` (FirstPairSourceSlice.swift L163-177). The producer side
/// (6473d0 stage chain) is the caller6473d0 module; the low-seed layer only consumes the shape here for
/// documentation parity — the row-0 low-seed builder returns `Builder6388f0Row0LowSeedPreimages`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Builder6473d0OutputPreimages {
    pub out4: Vec<u8>,
    pub out3: Vec<u8>,
    pub out2: Vec<u8>,
    pub out1: Vec<u8>,
    pub out0: Vec<u8>,
}

/// kit `LowSeedPhaseSpec` (FirstPairSourceSlice.swift L12873-12882).
struct LowSeedPhaseSpec {
    phase_magic: u64,
    aux_magics: [u64; 7],
    e10_magics: [u64; 7],
    e10_markers: [u8; 7],
    bd0_magic: u64,
    static_offset: usize,
    unary_magic: u64,
    f40_magics: [u64; 7],
}

/// kit `LowSeedPhaseResult` (FirstPairSourceSlice.swift L12884-12886).
struct LowSeedPhaseResult {
    final_cf0: Vec<u8>,
}

/// kit `builder6388f0LowSeedPhase1Spec` (FirstPairSourceSlice.swift L14949-14966).
const BUILDER6388F0_LOW_SEED_PHASE1_SPEC: LowSeedPhaseSpec = LowSeedPhaseSpec {
    phase_magic: 0x10a000002563,
    aux_magics: [
        0x10a0000076a7, 0x10a000002de5, 0x10a000007af9, 0x10a000007e6b,
        0x10a0000068a9, 0x10a0000062e7, 0x10a000007025,
    ],
    e10_magics: [
        0x10a0000008dc, 0x10a00000448c, 0x10a000000000, 0x10a000007493,
        0x10a0000039c1, 0x10a000000b56, 0x10a000005648,
    ],
    e10_markers: [3, 2, 3, 3, 7, 6, 3],
    bd0_magic: BUILDER6388F0_LOW_SEED_PREV2_BD0_MAGIC,
    static_offset: BUILDER6388F0_LOW_SEED_PREV2_STATIC,
    unary_magic: 0x000c107000c03253,
    f40_magics: [
        0x0410006041004174, 0x040000a040002bb7, 0x03e001203e001e19,
        0x03a002203a005ba1, 0x0320042032003f90, 0x02200820220066f1,
        0x0020102002001526,
    ],
};

/// kit `builder6388f0LowSeedPhase2Spec` (FirstPairSourceSlice.swift L14968-14985).
const BUILDER6388F0_LOW_SEED_PHASE2_SPEC: LowSeedPhaseSpec = LowSeedPhaseSpec {
    phase_magic: 0x10a0000046c4,
    aux_magics: [
        0x10a0000077b1, 0x10a0000038b7, 0x10a000001ade, 0x10a00000727f,
        0x10a0000069b3, 0x10a000007c03, 0x10a00000201b,
    ],
    e10_magics: [
        0x10a000006f1b, 0x10a00000553e, 0x10a000005d93, 0x10a000001648,
        0x10a000002459, 0x10a000002aad, 0x10a0000005d0,
    ],
    e10_markers: [3, 1, 5, 7, 6, 0, 7],
    bd0_magic: BUILDER6388F0_LOW_SEED_PRE_BD0_MAGIC,
    static_offset: BUILDER6388F0_LOW_SEED_PRE_STATIC,
    unary_magic: 0x000c107000c01105,
    f40_magics: [
        0x04100060410052fa, 0x040000a040001c0f, 0x03e001203e0028ab,
        0x03a002203a007f75, 0x0320042032002275, 0x0220082022005168,
        0x0020102002002799,
    ],
};

/// kit `builder6388f0LowSeedPhase3Spec` (FirstPairSourceSlice.swift L14987-15004).
const BUILDER6388F0_LOW_SEED_PHASE3_SPEC: LowSeedPhaseSpec = LowSeedPhaseSpec {
    phase_magic: 0x10a0000048fa,
    aux_magics: [
        0x10a0000045ba, 0x10a000000d8c, 0x10a0000019d4, 0x10a000003586,
        0x10a0000037ad, 0x10a00000759d, 0x10a000007389,
    ],
    e10_magics: [
        0x10a000004382, 0x10a00000010a, 0x10a000005ec1, 0x10a000003c3b,
        0x10a000007153, 0x10a000002125, 0x10a00000346a,
    ],
    e10_markers: [3, 3, 0, 3, 1, 0, 5],
    bd0_magic: BUILDER6388F0_LOW_SEED_TAIL_BD0_MAGIC,
    static_offset: BUILDER6388F0_LOW_SEED_TAIL_STATIC,
    unary_magic: 0x000c107000c079da,
    f40_magics: [
        0x0410006041005993, 0x040000a040001212, 0x03e001203e0006da,
        0x03a002203a0060e5, 0x0320042032004c30, 0x022008202200655f,
        0x0020102002005752,
    ],
};

// ---------------------------------------------------------------- 633fa8 tail primitives

/// kit `fold633fa8Tail` (FirstPairSourceSlice.swift L11894-11909): fold over the 633fa8 tail
/// fold tables, base 0x2fe798 (kit L14657).
fn fold633fa8_tail(value: u64, table_offset: usize, rounds: usize, t: &FirstPairTables) -> Result<u64, CryptoError> {
    const TAIL_FOLD_BASE: usize = 0x2fe798; // kit table633fa8TailFoldBase (L14657)
    let mut folded = value;
    for _ in 0..rounds {
        let relative = table_offset
            .checked_sub(TAIL_FOLD_BASE)
            .ok_or_else(|| slice_err(format!("633fa8 tail fold read at {table_offset:#x}")))?
            + ((folded & 0x0f) as usize) * 8;
        if relative + 8 > t.tail_fold_tables_633fa8.len() {
            return Err(slice_err(format!(
                "table read out of bounds: firstpair_633fa8_tail_fold_tables_2fe798 at {table_offset:#x}"
            )));
        }
        folded = read_u64_le(&t.tail_fold_tables_633fa8, relative).wrapping_add(folded >> 4);
    }
    Ok(folded)
}

/// kit `u32TableWord633fa8Tail` (FirstPairSourceSlice.swift L11843-11859): 63c278 u32 words at
/// offsets ≥ 0x112588, else the 633fa8 tail u32-low tables at base 0x112528 (kit L14658).
fn u32_table_word633fa8_tail(absolute_offset: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    const U32_BASE: usize = 0x112588; // kit table63c278U32Base (L14655); schedule::FOLD_BASE sibling
    const TAIL_U32_LOW_BASE: usize = 0x112528; // kit table633fa8TailU32LowBase (L14658)
    if absolute_offset >= U32_BASE {
        return u32_table_word_63c278(absolute_offset, t);
    }
    let relative = absolute_offset
        .checked_sub(TAIL_U32_LOW_BASE)
        .ok_or_else(|| slice_err(format!("633fa8 tail u32-low read at {absolute_offset:#x}")))?;
    if relative + 4 > t.tail_u32_low_tables_633fa8.len() {
        return Err(slice_err(format!(
            "table read out of bounds: firstpair_633fa8_tail_u32_low_tables_112528 at {absolute_offset:#x}"
        )));
    }
    Ok(read_u32_le(&t.tail_u32_low_tables_633fa8, relative))
}

/// kit `u32Affine633fa8Tail` (FirstPairSourceSlice.swift L11738-11752).
fn u32_affine633fa8_tail(
    word: u32,
    index: usize,
    mul_table: usize,
    add_table: usize,
    t: &FirstPairTables,
) -> Result<u32, CryptoError> {
    let table_offset = (index * 4) & 0x1c;
    let multiplier = u32_table_word633fa8_tail(mul_table + table_offset, t)?;
    let addend = u32_table_word633fa8_tail(add_table + table_offset, t)?;
    Ok(((word as u64).wrapping_mul(multiplier as u64).wrapping_add(addend as u64)) as u32)
}

/// kit `builder633fa8TailStreamU64` (FirstPairSourceSlice.swift L12662-12679).
#[allow(clippy::too_many_arguments)]
fn builder633fa8_tail_stream_u64(
    word: u32,
    word_mul: u64,
    word_add: u64,
    fold_table: usize,
    fold_mul: u64,
    mix_mul: u64,
    mix_add: u64,
    t: &FirstPairTables,
) -> Result<u64, CryptoError> {
    let folded = fold633fa8_tail(
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

/// kit `convolutionWorkspace633fa8Tail` (FirstPairSourceSlice.swift L12681-12743).
#[allow(clippy::too_many_arguments)]
fn convolution_workspace633fa8_tail(
    stack: &mut [u8],
    a_vector_offset: usize,
    a_prefix_offset: usize,
    b_vector_offset: usize,
    b_prefix_offset: usize,
    out_offset: usize,
    length: usize,
    output_count: usize,
    count_mul: u64,
    count_add: u64,
    product_mul: u64,
    b_prefix_mul: u64,
    a_prefix_mul: u64,
    final_mul: u64,
    final_add: u64,
) {
    let a_vector: Vec<u64> = (0..length).map(|i| read_u64_le(stack, a_vector_offset + i * 8)).collect();
    let a_prefix: Vec<u64> = (0..length).map(|i| read_u64_le(stack, a_prefix_offset + i * 8)).collect();
    let b_vector: Vec<u64> = (0..length).map(|i| read_u64_le(stack, b_vector_offset + i * 8)).collect();
    let b_prefix: Vec<u64> = (0..length).map(|i| read_u64_le(stack, b_prefix_offset + i * 8)).collect();

    for index in 0..output_count {
        let low = index.saturating_sub(length - 1);
        let high = index.min(length - 1);
        let product_sum: u64;
        let a_sum: u64;
        let b_sum: u64;
        let count: u64;
        if low <= high {
            let mut running_product_sum: u64 = 0;
            for b_index in low..=high {
                running_product_sum = running_product_sum
                    .wrapping_add(a_vector[index - b_index].wrapping_mul(b_vector[b_index]));
            }
            product_sum = running_product_sum;

            let mut running_a_sum = a_prefix[index - low];
            if index >= high + 1 {
                running_a_sum = running_a_sum.wrapping_sub(a_prefix[index - high - 1]);
            }
            a_sum = running_a_sum;

            let mut running_b_sum = b_prefix[high];
            if low > 0 {
                running_b_sum = running_b_sum.wrapping_sub(b_prefix[low - 1]);
            }
            b_sum = running_b_sum;
            count = (high - low + 1) as u64;
        } else {
            product_sum = 0;
            a_sum = 0;
            b_sum = 0;
            count = 0;
        }

        let mut out = count.wrapping_mul(count_mul).wrapping_add(count_add);
        out = out.wrapping_add(product_sum.wrapping_mul(product_mul));
        out = out.wrapping_add(b_sum.wrapping_mul(b_prefix_mul));
        out = out.wrapping_add(a_sum.wrapping_mul(a_prefix_mul));
        out = out.wrapping_mul(final_mul).wrapping_add(final_add);
        write_u64_le(out, stack, out_offset + index * 8);
    }
}

// ---------------------------------------------------------------- 633fa8 tail qwords / E10 / scalar

/// kit `builder633fa8TailQwordsFromSources` (FirstPairSourceSlice.swift L3399-3640).
pub fn builder633fa8_tail_qwords_from_sources(
    words3ab0: &[u32],
    words3120: &[u32],
    words2dfc: &[u32],
    seed3110: u64,
    t: &FirstPairTables,
) -> Result<Vec<u64>, CryptoError> {
    if words3ab0.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8TailWordCount(3ab0, {})",
            words3ab0.len()
        )));
    }
    if words3120.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8TailWordCount(3120, {})",
            words3120.len()
        )));
    }
    if words2dfc.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8TailWordCount(2dfc, {})",
            words2dfc.len()
        )));
    }

    let mut stack = vec![0u8; BUILDER633FA8_TAIL_STACK_BYTES];

    // A stream (words3ab0) → stack 0x3f40 vector + 0x3cf0 prefix.
    let mut word = words3ab0[0].wrapping_mul(0xc365675b).wrapping_add(0xe8f087b3);
    let mut value = builder633fa8_tail_stream_u64(
        word,
        0x39629fb00ae1a583,
        0xc87e38ff2ae3bb2d,
        BUILDER633FA8_TAIL_A_FOLD_TABLE,
        0xd0779b0b00000000,
        0x7168c55d8932925f,
        0x9f10057a9662ab2d,
        t,
    )?;
    write_u64_le(value, &mut stack, 0x3f40);
    write_u64_le(value, &mut stack, 0x3cf0);
    let mut prefix = value;
    for (index, &source_word) in words3ab0.iter().enumerate().skip(1) {
        word = u32_affine633fa8_tail(source_word, index, 0x11fd08, 0x11fd28, t)?;
        word = word.wrapping_mul(0x6ebad499).wrapping_add(0x8b060038);
        value = builder633fa8_tail_stream_u64(
            word,
            0x39629fb00ae1a583,
            0xc87e38ff2ae3bb2d,
            BUILDER633FA8_TAIL_A_FOLD_TABLE,
            0xd0779b0b00000000,
            0x7168c55d8932925f,
            0x9f10057a9662ab2d,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x3f40 + index * 8);
        prefix = prefix.wrapping_add(value);
        write_u64_le(prefix, &mut stack, 0x3cf0 + index * 8);
    }

    // B stream (words3120) → stack 0x3e10 vector + 0x3bd0 prefix.
    word = words3120[0].wrapping_mul(0x21753b73).wrapping_add(0x9f972fa4);
    value = builder633fa8_tail_stream_u64(
        word,
        0xc16bd9358bd641f1,
        0xdbd59c6303e46229,
        BUILDER633FA8_TAIL_B_FOLD_TABLE,
        0xe919ac4d00000000,
        0x0eb018d832b73e83,
        0x1f4e35decd254a8b,
        t,
    )?;
    write_u64_le(value, &mut stack, 0x3e10);
    write_u64_le(value, &mut stack, 0x3bd0);
    prefix = value;
    for (index, &source_word) in words3120.iter().enumerate().skip(1) {
        word = u32_affine633fa8_tail(source_word, index, 0x112528, 0x112548, t)?;
        word = word.wrapping_mul(0x740d5673).wrapping_add(0xf3b4a3bc);
        value = builder633fa8_tail_stream_u64(
            word,
            0xc16bd9358bd641f1,
            0xdbd59c6303e46229,
            BUILDER633FA8_TAIL_B_FOLD_TABLE,
            0xe919ac4d00000000,
            0x0eb018d832b73e83,
            0x1f4e35decd254a8b,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x3e10 + index * 8);
        prefix = prefix.wrapping_add(value);
        write_u64_le(prefix, &mut stack, 0x3bd0 + index * 8);
    }

    convolution_workspace633fa8_tail(
        &mut stack,
        0x3e10,
        0x3bd0,
        0x3f40,
        0x3cf0,
        0x4080,
        BUILDER633FA8_SCALAR_WORD_COUNT,
        42,
        0x88edcb9fcc5a504f,
        0xc50c4cfe6b90cc32,
        0xb280f1fcde620b25,
        0x24cc8b7736fa66cf,
        0x0512ce98be108b3a5,
        0xe8cb5b6d2f40c331,
        0xeb47abb56d203e7d,
    );

    // C stream (words2dfc) overwrites the 0x3f40 vector.
    for (index, &source_word) in words2dfc.iter().enumerate() {
        word = u32_affine633fa8_tail(source_word, index, 0x11b268, 0x120e48, t)?;
        word = word.wrapping_mul(0x4890e04f).wrapping_add(0xc2cec971);
        value = builder633fa8_tail_stream_u64(
            word,
            0xe2d3ea4512d167e7,
            0x00a7c876b324afde01,
            BUILDER633FA8_TAIL_C_FOLD_TABLE,
            0xc4e79ba300000000,
            0xf5ea48539d50faeb,
            0x37ffe0ce46814927,
            t,
        )?;
        write_u64_le(value, &mut stack, 0x3f40 + index * 8);
    }

    let q: Vec<u64> = (0..BUILDER633FA8_SCALAR_WORD_COUNT).map(|i| read_u64_le(&stack, 0x3f40 + i * 8)).collect();
    let mut x30 = read_u64_le(&stack, 0x4080);
    let seed_a = seed3110.wrapping_mul(0xac33be2f37df9899).wrapping_add(0xf586b9c725fc2655);
    let seed_b = seed3110.wrapping_mul(0xc460e481253db509).wrapping_add(0x5642aeb8585a52eb);

    for byte_offset in (0..0xb0).step_by(8) {
        let position = 0x4080 + byte_offset;
        let mut x10 = x30.wrapping_mul(seed_a).wrapping_add(seed_b);
        let mut folded = fold633fa8_tail(
            x10.wrapping_mul(0x09883223fa4660ed).wrapping_add(0x2d97cba42b4b302f),
            BUILDER633FA8_TAIL_D_FOLD_TABLE,
            7,
            t,
        )?;
        x10 = x10
            .wrapping_mul(0x9c92333d4c3638c9)
            .wrapping_add(folded.wrapping_mul(0x718df58330000000))
            .wrapping_add(0xdb9c322f9570a279);

        let x7 = x10.wrapping_mul(0x06192264fc57feaf).wrapping_add(0xe244b4f2265375bf);
        let x21 = x10.wrapping_mul(0x3fd0fde8a99b1d3c).wrapping_add(0x7bd7d5be27b1d17c);
        let x3 = x7.wrapping_mul(q[8]).wrapping_add(x21);
        x30 = x7.wrapping_mul(q[0]).wrapping_add(x21).wrapping_add(x30);
        let x4 = x7.wrapping_mul(q[9]).wrapping_add(x21);

        folded = fold633fa8_tail(
            x30.wrapping_mul(0xa5ba351ba23facf5).wrapping_add(0x0720c6fcb580eff2),
            BUILDER633FA8_TAIL_E_FOLD_TABLE,
            7,
            t,
        )?;
        let mut x12 = folded.wrapping_mul(0x9549f71510a8f0e7).wrapping_add(0x2d3bf7a5dd39f0ab);
        folded = fold633fa8_tail(
            x12.wrapping_mul(0xead4735c0bc5924d).wrapping_add(0x73beb11d9159837c),
            BUILDER633FA8_TAIL_F_FOLD_TABLE,
            9,
            t,
        )?;
        let mut x8_mix = x12.wrapping_mul(0x7794ebcd6781608d).wrapping_add(folded.wrapping_mul(0xafc58bf000000000));

        let old08 = read_u64_le(&stack, position + 0x08);
        let old10 = read_u64_le(&stack, position + 0x10);
        let mut x13 = x7.wrapping_mul(q[1]).wrapping_add(x21).wrapping_add(old08);
        let x11 = x7.wrapping_mul(q[2]).wrapping_add(x21).wrapping_add(old10);
        write_u64_le(x30, &mut stack, position);
        write_u64_le(x13, &mut stack, position + 0x08);

        let acc13 = x7.wrapping_mul(q[13]).wrapping_add(x21);
        let old18 = read_u64_le(&stack, position + 0x18);
        let old20 = read_u64_le(&stack, position + 0x20);
        let mut x14 = x7.wrapping_mul(q[3]).wrapping_add(x21).wrapping_add(old18);
        let x15 = x7.wrapping_mul(q[4]).wrapping_add(x21).wrapping_add(old20);

        let old28 = read_u64_le(&stack, position + 0x28);
        let old30 = read_u64_le(&stack, position + 0x30);
        let x16 = x7.wrapping_mul(q[5]).wrapping_add(x21).wrapping_add(old28);
        let mut x17 = x7.wrapping_mul(q[6]).wrapping_add(x21).wrapping_add(old30);
        write_u64_le(x14, &mut stack, position + 0x18);
        write_u64_le(x15, &mut stack, position + 0x20);

        let old38 = read_u64_le(&stack, position + 0x38);
        let old40 = read_u64_le(&stack, position + 0x40);
        let mut acc15 = x7.wrapping_mul(q[14]).wrapping_add(x21);
        let x1 = x7.wrapping_mul(q[7]).wrapping_add(x21).wrapping_add(old38);
        let x16b = x3.wrapping_add(old40);
        write_u64_le(x16, &mut stack, position + 0x28);
        write_u64_le(x17, &mut stack, position + 0x30);

        let old48 = read_u64_le(&stack, position + 0x48);
        let old50 = read_u64_le(&stack, position + 0x50);
        let acc0 = x7.wrapping_mul(q[15]).wrapping_add(x21);
        x14 = x4.wrapping_add(old48);
        let acc2 = x7.wrapping_mul(q[10]).wrapping_add(x21);
        x17 = acc2.wrapping_add(old50);
        write_u64_le(x1, &mut stack, position + 0x38);
        write_u64_le(x16b, &mut stack, position + 0x40);
        write_u64_le(x14, &mut stack, position + 0x48);
        write_u64_le(x17, &mut stack, position + 0x50);

        let old58 = read_u64_le(&stack, position + 0x58);
        let old60 = read_u64_le(&stack, position + 0x60);
        let acc12 = x7.wrapping_mul(q[11]).wrapping_add(x21);
        let acc30 = x7.wrapping_mul(q[12]).wrapping_add(x21);
        let acc14 = x7.wrapping_mul(q[16]).wrapping_add(x21);
        x12 = acc12.wrapping_add(old58);
        x17 = acc30.wrapping_add(old60);
        let acc1 = x7.wrapping_mul(q[17]).wrapping_add(x21);
        write_u64_le(x12, &mut stack, position + 0x58);
        write_u64_le(x17, &mut stack, position + 0x60);

        let old68 = read_u64_le(&stack, position + 0x68);
        let old70 = read_u64_le(&stack, position + 0x70);
        x13 = acc13.wrapping_add(old68);
        x12 = acc15.wrapping_add(old70);
        let acc16 = x7.wrapping_mul(q[18]).wrapping_add(x21);
        write_u64_le(x13, &mut stack, position + 0x68);
        write_u64_le(x12, &mut stack, position + 0x70);

        let old78 = read_u64_le(&stack, position + 0x78);
        let old80 = read_u64_le(&stack, position + 0x80);
        acc15 = x7.wrapping_mul(q[19]).wrapping_add(x21);
        x12 = acc0.wrapping_add(old78);
        x13 = acc14.wrapping_add(old80);
        write_u64_le(x12, &mut stack, position + 0x78);
        write_u64_le(x13, &mut stack, position + 0x80);

        x8_mix = x8_mix
            .wrapping_mul(0x56c495ec086d9247)
            .wrapping_add(read_u64_le(&stack, position + 0x08));

        let old88 = read_u64_le(&stack, position + 0x88);
        let old90 = read_u64_le(&stack, position + 0x90);
        x12 = acc1.wrapping_add(old88);
        x14 = acc16.wrapping_add(old90);
        write_u64_le(x12, &mut stack, position + 0x88);
        write_u64_le(x14, &mut stack, position + 0x90);

        let old98 = read_u64_le(&stack, position + 0x98);
        x12 = acc15.wrapping_add(old98);
        write_u64_le(x12, &mut stack, position + 0x98);

        x30 = x8_mix.wrapping_add(0xc387faf5615fb2e3);
        write_u64_le(x30, &mut stack, position + 0x08);
        write_u64_le(x11, &mut stack, position + 0x10);
        let _ = x13; // mirror the kit's write-then-overwrite ordering above
    }

    Ok((0..BUILDER633FA8_SCALAR_WORD_COUNT).map(|i| read_u64_le(&stack, 0x4130 + i * 8)).collect())
}

/// kit `builder633fa8E10WordsFromTailQwords` (FirstPairSourceSlice.swift L3642-3683).
pub fn builder633fa8_e10_words_from_tail_qwords(
    tail_qwords: &[u64],
    t: &FirstPairTables,
) -> Result<Vec<u32>, CryptoError> {
    if tail_qwords.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8QwordCount({})",
            tail_qwords.len()
        )));
    }
    let mut carry: u64 = BUILDER633FA8_E10_INITIAL_CARRY;
    let mut out: Vec<u32> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT);
    for (index, &qword) in tail_qwords.iter().enumerate() {
        let source = qword.wrapping_mul(BUILDER633FA8_E10_QWORD_MUL);
        carry = carry.wrapping_mul(BUILDER633FA8_E10_CARRY_MUL).wrapping_add(source);
        carry = carry.wrapping_add(BUILDER633FA8_E10_CARRY_ADD);

        let folded_seed = carry
            .wrapping_mul(BUILDER633FA8_E10_FOLD_SEED_MUL)
            .wrapping_add(BUILDER633FA8_E10_FOLD_SEED_ADD);
        let mut word = (carry as u32).wrapping_mul(BUILDER633FA8_E10_WORD_MUL);
        let folded7 = fold633fa8_tail(folded_seed, BUILDER633FA8_E10_TAIL_FOLD_TABLE, 7, t)?;
        word = word
            .wrapping_add(((folded7 & 0x0f) as u32) << 28)
            .wrapping_add(BUILDER633FA8_E10_WORD_ADD);
        let folded16 = fold633fa8_tail(folded7, BUILDER633FA8_E10_TAIL_FOLD_TABLE, 9, t)?;

        let table_offset = (index * 4) & 0x1c;
        word = word
            .wrapping_mul(u32_table_word_63c278(BUILDER633FA8_E10_TAIL_MUL_TABLE + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(BUILDER633FA8_E10_TAIL_ADD_TABLE + table_offset, t)?);
        out.push(word);

        carry = folded7
            .wrapping_mul(BUILDER633FA8_E10_NEXT_CARRY_FOLDED7_MUL)
            .wrapping_add(folded16.wrapping_mul(BUILDER633FA8_E10_NEXT_CARRY_FOLDED16_MUL))
            .wrapping_add(BUILDER633FA8_E10_NEXT_CARRY_ADD);
    }
    Ok(out)
}

/// kit `builder633fa8ScalarWindowFromE10Words` (FirstPairSourceSlice.swift L3685-3725).
pub fn builder633fa8_scalar_window_from_e10_words(
    words: &[u32],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8WordCount({})",
            words.len()
        )));
    }
    let mut out = vec![0u8; BUILDER633FA8_SCALAR_WINDOW_BYTES];
    let mut accumulator: u64 = 0;
    let mut bit_count = 0usize;
    let mut out_index = 0usize;
    for (index, &word) in words.iter().enumerate() {
        let table_offset = (index * 4) & 0x1c;
        let packed = (word as u64)
            .wrapping_mul(u32_table_word_63c278(BUILDER633FA8_SCALAR_PACK_MUL_TABLE + table_offset, t)? as u64)
            .wrapping_add(u32_table_word_63c278(BUILDER633FA8_SCALAR_PACK_ADD_TABLE + table_offset, t)? as u64);
        let mut value = (packed & 0xffff_ffff) as u32;
        value = value
            .wrapping_mul(BUILDER633FA8_SCALAR_PACK_MUL)
            .wrapping_add(BUILDER633FA8_SCALAR_PACK_ADD);
        accumulator ^= (value as u64) << bit_count;
        bit_count += 28;
        while bit_count > 16 && out_index < BUILDER633FA8_SCALAR_WINDOW_BYTES - 1 {
            out[out_index] = (accumulator & 0xff) as u8;
            accumulator >>= 8;
            out_index += 1;
            bit_count -= 8;
        }
    }
    if bit_count >= 1 && out_index < BUILDER633FA8_SCALAR_WINDOW_BYTES - 1 {
        out[out_index] = (accumulator & 0xff) as u8;
    }
    Ok(out)
}

// ---------------------------------------------------------------- low-seed private helpers

/// kit `builder6388f0LowSeedStaticBlock` (FirstPairSourceSlice.swift L12965-12973).
fn builder6388f0_low_seed_static_block(lib_offset: usize, t: &FirstPairTables) -> Result<Vec<u8>, CryptoError> {
    let offset = lib_offset - BUILDER6388F0_LOW_SEED_STATIC_BASE;
    Ok(checked_slice(
        &t.low_seed_statics_6388f0,
        offset,
        BUILDER6388F0_LOW_SEED_BLOCK_BYTES,
        "firstpair_6388f0_low_seed_statics_2f4d28",
    )?
    .to_vec())
}

/// kit `builder6388f0LowLoopStaticBlock` (FirstPairSourceSlice.swift L12975-12987).
fn builder6388f0_low_loop_static_block(
    lib_offset: usize,
    byte_count: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let offset = lib_offset - BUILDER6388F0_LOW_LOOP_STATIC_BASE;
    Ok(checked_slice(
        &t.low_loop_statics_6388f0,
        offset,
        byte_count,
        "firstpair_6388f0_low_loop_statics_2fe600",
    )?
    .to_vec())
}

/// kit `builder6388f0LowLoopStaticByte` (FirstPairSourceSlice.swift L12989-12991).
fn builder6388f0_low_loop_static_byte(lib_offset: usize, t: &FirstPairTables) -> Result<u8, CryptoError> {
    Ok(builder6388f0_low_loop_static_block(lib_offset, 1, t)?[0])
}

/// kit `builder6388f0LowSeedE10SourceFromAB0` (FirstPairSourceSlice.swift L12939-12963).
fn builder6388f0_low_seed_e10_source_from_ab0(
    marker: u8,
    shift: usize,
    ab0: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    if marker > 7 {
        return Err(slice_err(format!("invalidLowSeedMarker({marker})")));
    }
    if !(1..=BUILDER6388F0_LOW_SEED_BLOCK_BYTES).contains(&shift) {
        return Err(slice_err(format!("invalidLowSeedShift({shift})")));
    }
    require(ab0, BUILDER6388F0_LOW_SEED_BLOCK_BYTES - shift, "6388f0 low-seed ab0 source")?;

    let mut out = vec![0u8; BUILDER6388F0_LOW_SEED_BLOCK_BYTES];
    out[shift - 1] = marker;
    let copy_count = BUILDER6388F0_LOW_SEED_BLOCK_BYTES - shift;
    if copy_count > 0 {
        out[shift..BUILDER6388F0_LOW_SEED_BLOCK_BYTES].copy_from_slice(&ab0[..copy_count]);
    }
    Ok(out)
}

/// kit `builder6388f0LowSeedPhaseFromCF0Seed` (FirstPairSourceSlice.swift L12888-12937).
/// The kit's per-spec count checks become array-length equality by construction (fixed-size
/// `[u64; 7]` spec arrays vs the 7-entry `builder6388f0LowSeedE10SourceShifts`).
fn builder6388f0_low_seed_phase_from_cf0_seed(
    spec: &LowSeedPhaseSpec,
    seed_cf0: &[u8],
    t: &FirstPairTables,
) -> Result<LowSeedPhaseResult, CryptoError> {
    require(seed_cf0, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed cf0 seed")?;

    let static_block = builder6388f0_low_seed_static_block(spec.static_offset, t)?;
    let mut cf0 = seed_cf0.to_vec();
    for index in 0..spec.aux_magics.len() {
        let bd0 = vm638840(spec.bd0_magic, &cf0, &static_block, t)?;
        let ab0 = vm641fcc(spec.unary_magic, &bd0, t)?;
        let e10_source = builder6388f0_low_seed_e10_source_from_ab0(
            spec.e10_markers[index],
            BUILDER6388F0_LOW_SEED_E10_SOURCE_SHIFTS[index],
            &ab0,
        )?;
        let e10 = vm638840(spec.e10_magics[index], &e10_source, &e10_source, t)?;
        let f40 = vm6420d8(spec.f40_magics[index], &ab0, &ab0, t)?;
        let aux = vm638840(spec.aux_magics[index], &e10, &f40, t)?;
        cf0 = vm638840(spec.phase_magic, &cf0, &aux, t)?;
    }
    Ok(LowSeedPhaseResult { final_cf0: cf0 })
}

/// kit `builder6388f0LowSeedTailPairFromSlotState` (FirstPairSourceSlice.swift L13221-13282).
fn builder6388f0_low_seed_tail_pair_from_slot_state(
    pre_s898: &[u8],
    pre_s78e: &[u8],
    pre_bd0: &[u8],
    tail_bd0: &[u8],
    t: &FirstPairTables,
) -> Result<Builder6388f0LowSeedTailPair, CryptoError> {
    require(pre_s898, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed pre s898")?;
    require(pre_s78e, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed pre s78e")?;
    require(pre_bd0, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed pre bd0")?;
    require(tail_bd0, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed tail bd0")?;

    let prev_s684 = vm638840(BUILDER6388F0_LOW_SEED_PREV_S684_MAGIC, pre_bd0, pre_bd0, t)?;
    let prev_s57a = vm638840(BUILDER6388F0_LOW_SEED_MIDDLE_MAGIC, pre_s898, &prev_s684, t)?;
    let seed_s898 = vm638840(BUILDER6388F0_LOW_SEED_TAIL_LEFT_MAGIC, pre_s78e, pre_s78e, t)?;
    let seed_s78e = vm638840(BUILDER6388F0_LOW_SEED_TAIL_RIGHT_MAGIC, &prev_s57a, &prev_s57a, t)?;
    let tail_s684 = vm638840(BUILDER6388F0_LOW_SEED_TAIL_S684_MAGIC, tail_bd0, tail_bd0, t)?;
    let tail_s57a = vm638840(BUILDER6388F0_LOW_SEED_MIDDLE_MAGIC, &seed_s898, &tail_s684, t)?;
    let left = vm638840(BUILDER6388F0_LOW_SEED_TAIL_LEFT_MAGIC, &seed_s78e, &seed_s78e, t)?;
    let right = vm638840(BUILDER6388F0_LOW_SEED_TAIL_RIGHT_MAGIC, &tail_s57a, &tail_s57a, t)?;
    Ok(Builder6388f0LowSeedTailPair { left, right })
}

/// kit `builder6388f0LowSeedTailPairFromEntryAndCF0` (FirstPairSourceSlice.swift L13124-13219).
fn builder6388f0_low_seed_tail_pair_from_entry_and_cf0(
    entry_source: &[u8],
    pre2_cf0: &[u8],
    pre_cf0: &[u8],
    tail_cf0: &[u8],
    t: &FirstPairTables,
) -> Result<Builder6388f0LowSeedTailPair, CryptoError> {
    if entry_source.len() < BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_BYTES {
        return Err(slice_err(format!(
            "source too short: 6388f0 low-seed entry source has {}, wants {BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_BYTES}",
            entry_source.len()
        )));
    }
    require(pre2_cf0, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed pre2 cf0")?;
    require(pre_cf0, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed pre cf0")?;
    require(tail_cf0, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed tail cf0")?;

    let source = entry_source;
    let entry_head = &source[..BUILDER6388F0_LOW_SEED_BLOCK_BYTES];
    let entry_tail = &source[BUILDER6388F0_LOW_SEED_BLOCK_BYTES..BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_BYTES];
    let pre2_s898 = vm638840(BUILDER6388F0_LOW_SEED_ENTRY_S898_MAGIC, entry_head, entry_head, t)?;
    let pre2_s78e = vm638840(BUILDER6388F0_LOW_SEED_ENTRY_S78E_MAGIC, entry_tail, entry_tail, t)?;

    let pre2_static = builder6388f0_low_seed_static_block(BUILDER6388F0_LOW_SEED_PREV2_STATIC, t)?;
    let pre_static = builder6388f0_low_seed_static_block(BUILDER6388F0_LOW_SEED_PRE_STATIC, t)?;
    let tail_static = builder6388f0_low_seed_static_block(BUILDER6388F0_LOW_SEED_TAIL_STATIC, t)?;
    let pre2_bd0 = vm638840(BUILDER6388F0_LOW_SEED_PREV2_BD0_MAGIC, pre2_cf0, &pre2_static, t)?;
    let pre2_s684 = vm638840(BUILDER6388F0_LOW_SEED_PREV2_S684_MAGIC, &pre2_bd0, &pre2_bd0, t)?;
    let pre2_s57a = vm638840(BUILDER6388F0_LOW_SEED_MIDDLE_MAGIC, &pre2_s898, &pre2_s684, t)?;
    let pre_s898 = vm638840(BUILDER6388F0_LOW_SEED_TAIL_LEFT_MAGIC, &pre2_s78e, &pre2_s78e, t)?;
    let pre_s78e = vm638840(BUILDER6388F0_LOW_SEED_TAIL_RIGHT_MAGIC, &pre2_s57a, &pre2_s57a, t)?;
    let pre_bd0 = vm638840(BUILDER6388F0_LOW_SEED_PRE_BD0_MAGIC, pre_cf0, &pre_static, t)?;
    let tail_bd0 = vm638840(BUILDER6388F0_LOW_SEED_TAIL_BD0_MAGIC, tail_cf0, &tail_static, t)?;
    builder6388f0_low_seed_tail_pair_from_slot_state(&pre_s898, &pre_s78e, &pre_bd0, &tail_bd0, t)
}

// ---------------------------------------------------------------- low-seed public path

/// kit `builder6388f0LowSeedCF0SeedsFromEntrySource` (FirstPairSourceSlice.swift L2299-2422).
pub fn builder6388f0_low_seed_cf0_seeds_from_entry_source(
    entry_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder6388f0LowSeedCF0Seeds, CryptoError> {
    if entry_source.len() < BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_BYTES {
        return Err(slice_err(format!(
            "source too short: 6388f0 low-seed entry source has {}, wants {BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_BYTES}",
            entry_source.len()
        )));
    }

    let source = entry_source;
    let entry_head = &source[..BUILDER6388F0_LOW_SEED_BLOCK_BYTES];
    let entry_tail = &source[BUILDER6388F0_LOW_SEED_BLOCK_BYTES..BUILDER6388F0_LOW_SEED_ENTRY_SOURCE_BYTES];

    let pre2_s898 = vm638840(BUILDER6388F0_LOW_SEED_ENTRY_S898_MAGIC, entry_head, entry_head, t)?;
    let pre2_s78e = vm638840(BUILDER6388F0_LOW_SEED_ENTRY_S78E_MAGIC, entry_tail, entry_tail, t)?;
    let phase1_seed_cf0 = vm641fcc(BUILDER6388F0_LOW_SEED_CF0_PHASE1_SEED_MAGIC, &pre2_s78e, t)?;
    let phase1 = builder6388f0_low_seed_phase_from_cf0_seed(&BUILDER6388F0_LOW_SEED_PHASE1_SPEC, &phase1_seed_cf0, t)?;

    let pre2_static = builder6388f0_low_seed_static_block(BUILDER6388F0_LOW_SEED_PREV2_STATIC, t)?;
    let pre2_bd0 = vm638840(BUILDER6388F0_LOW_SEED_PREV2_BD0_MAGIC, &phase1.final_cf0, &pre2_static, t)?;
    let pre2_s684 = vm638840(BUILDER6388F0_LOW_SEED_PREV2_S684_MAGIC, &pre2_bd0, &pre2_bd0, t)?;
    let pre2_s57a = vm638840(BUILDER6388F0_LOW_SEED_MIDDLE_MAGIC, &pre2_s898, &pre2_s684, t)?;
    let pre_s898 = vm638840(BUILDER6388F0_LOW_SEED_TAIL_LEFT_MAGIC, &pre2_s78e, &pre2_s78e, t)?;
    let pre_s78e = vm638840(BUILDER6388F0_LOW_SEED_TAIL_RIGHT_MAGIC, &pre2_s57a, &pre2_s57a, t)?;
    let phase2_seed_cf0 = vm641fcc(BUILDER6388F0_LOW_SEED_CF0_PHASE2_SEED_MAGIC, &pre_s78e, t)?;
    let phase2 = builder6388f0_low_seed_phase_from_cf0_seed(&BUILDER6388F0_LOW_SEED_PHASE2_SPEC, &phase2_seed_cf0, t)?;

    let pre_static = builder6388f0_low_seed_static_block(BUILDER6388F0_LOW_SEED_PRE_STATIC, t)?;
    let pre_bd0 = vm638840(BUILDER6388F0_LOW_SEED_PRE_BD0_MAGIC, &phase2.final_cf0, &pre_static, t)?;
    let prev_s684 = vm638840(BUILDER6388F0_LOW_SEED_PREV_S684_MAGIC, &pre_bd0, &pre_bd0, t)?;
    let prev_s57a = vm638840(BUILDER6388F0_LOW_SEED_MIDDLE_MAGIC, &pre_s898, &prev_s684, t)?;
    let seed_s78e = vm638840(BUILDER6388F0_LOW_SEED_TAIL_RIGHT_MAGIC, &prev_s57a, &prev_s57a, t)?;
    let phase3_seed_cf0 = vm641fcc(BUILDER6388F0_LOW_SEED_CF0_PHASE3_SEED_MAGIC, &seed_s78e, t)?;

    Ok(Builder6388f0LowSeedCF0Seeds {
        phase1: phase1_seed_cf0,
        phase2: phase2_seed_cf0,
        phase3: phase3_seed_cf0,
    })
}

/// kit `builder6388f0LowSeedTailPairFromEntrySource` (FirstPairSourceSlice.swift L2424-2451).
pub fn builder6388f0_low_seed_tail_pair_from_entry_source(
    entry_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder6388f0LowSeedTailPair, CryptoError> {
    let seeds = builder6388f0_low_seed_cf0_seeds_from_entry_source(entry_source, t)?;
    let phase1 = builder6388f0_low_seed_phase_from_cf0_seed(&BUILDER6388F0_LOW_SEED_PHASE1_SPEC, &seeds.phase1, t)?;
    let phase2 = builder6388f0_low_seed_phase_from_cf0_seed(&BUILDER6388F0_LOW_SEED_PHASE2_SPEC, &seeds.phase2, t)?;
    let phase3 = builder6388f0_low_seed_phase_from_cf0_seed(&BUILDER6388F0_LOW_SEED_PHASE3_SPEC, &seeds.phase3, t)?;
    builder6388f0_low_seed_tail_pair_from_entry_and_cf0(
        entry_source,
        &phase1.final_cf0,
        &phase2.final_cf0,
        &phase3.final_cf0,
        t,
    )
}

/// kit `builder6388f0LowSeedTailStageFromPair` (FirstPairSourceSlice.swift L2453-2465).
pub fn builder6388f0_low_seed_tail_stage_from_pair(
    pair: &Builder6388f0LowSeedTailPair,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    require(&pair.left, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed tail left")?;
    require(&pair.right, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed tail right")?;
    vm638840(
        BUILDER6388F0_LOW_SEED_TAIL_STAGE_MAGIC,
        &pair.left,
        &pair.right,
        t,
    )
}

/// kit `builder6388f0LowSeedPreludeSourceFromTailStage` (FirstPairSourceSlice.swift L2467-2485).
pub fn builder6388f0_low_seed_prelude_source_from_tail_stage(
    tail_stage: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    require(tail_stage, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed tail stage")?;
    let stage = vm638840(
        BUILDER6388F0_LOW_SEED_PRELUDE_STAGE_MAGIC,
        tail_stage,
        tail_stage,
        t,
    )?;
    vm638840(
        BUILDER6388F0_LOW_SEED_PRELUDE_SOURCE_MAGIC,
        &stage,
        &stage,
        t,
    )
}

/// kit `builder6388f0LowSeedBlocksFromPreludeSource` (FirstPairSourceSlice.swift L2487-2527).
pub fn builder6388f0_low_seed_blocks_from_prelude_source(
    prelude_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let prelude = prelude_source;
    require(prelude, BUILDER6388F0_LOW_SEED_BLOCK_BYTES, "6388f0 low-seed prelude source")?;
    let expanded = vm6420d8(BUILDER6388F0_LOW_SEED_PRELUDE_MAGIC, prelude, prelude, t)?;
    if expanded.len() != BUILDER6388F0_LOW_SEED_EXPANDED_PRELUDE_BYTES {
        return Err(slice_err(format!(
            "invalidLowSeedExpandedPreludeLength({})",
            expanded.len()
        )));
    }

    let mut out: Vec<u8> = Vec::with_capacity(BUILDER6388F0_LOW_SEED_SEED_BLOCKS_BYTES);
    for index in 0..19usize {
        let start = index * 0x0e;
        let block = &expanded[start..start + 0x10];
        out.extend_from_slice(&vm638840(BUILDER6388F0_ROW0_SEED_BLOCK_MAGIC, block, block, t)?);
    }
    let static_block = builder6388f0_low_loop_static_block(BUILDER6388F0_LOW_SEED_STATIC_BLOCK_OFFSET, 0x10, t)?;
    out.extend_from_slice(&vm638840(
        BUILDER6388F0_ROW0_SEED_BLOCK_MAGIC,
        &static_block,
        &static_block,
        t,
    )?);
    Ok(out)
}

/// kit `builder6388f0LowSeedLoopFromBlocks` (FirstPairSourceSlice.swift L2529-2696).
pub fn builder6388f0_low_seed_loop_from_blocks(
    seed_blocks: &[u8],
    t: &FirstPairTables,
) -> Result<Builder6388f0LowSeedLoopResult, CryptoError> {
    if seed_blocks.len() != BUILDER6388F0_LOW_SEED_SEED_BLOCKS_BYTES {
        return Err(slice_err(format!(
            "invalidLowSeedSeedBlocksLength({})",
            seed_blocks.len()
        )));
    }
    let blocks = seed_blocks;
    let mut schedule_words: Vec<u32> = Vec::with_capacity(20);
    let mut final6377f0: Vec<u8> = Vec::new();

    for outer_index in 0..20usize {
        let lane = outer_index & 7;
        let mut c_lane = builder6388f0_low_loop_static_block(BUILDER6388F0_LOW_LOOP_STATIC_CTABLE, 0x10, t)?;
        c_lane.extend_from_slice(&[0x05, 0x04]);

        let e_source = builder6388f0_low_loop_static_block(
            BUILDER6388F0_LOW_LOOP_STATIC_ETABLE + BUILDER6388F0_LOW_LOOP_LANE_BYTES * lane,
            BUILDER6388F0_LOW_LOOP_LANE_BYTES,
            t,
        )?;
        let mut e_lane = vm638840(BUILDER6388F0_LOW_LOOP_EINIT_MAGIC, &e_source, &e_source, t)?;
        let block_offset = outer_index * 0x10;
        let block = &blocks[block_offset..block_offset + 0x10];
        let d_lane = vm6420d8(BUILDER6388F0_LOW_LOOP_DINIT_MAGIC, block, block, t)?;
        let mut b_lane = vm638840(BUILDER6388F0_LOW_LOOP_BINIT_MAGIC, &e_lane, &e_lane, t)?;

        let mut a_lane = vec![0u8; BUILDER6388F0_LOW_LOOP_LANE_BYTES];
        let mut t_lane = vec![0u8; BUILDER6388F0_LOW_LOOP_LANE_BYTES];
        for _ in 0..28 {
            let f_lane = vm638840(BUILDER6388F0_LOW_LOOP_F_MAGIC, &d_lane, &c_lane, t)?;
            let a_source = builder6388f0_low_loop_static_block(
                BUILDER6388F0_LOW_LOOP_STATIC_ATABLE,
                BUILDER6388F0_LOW_LOOP_LANE_BYTES,
                t,
            )?;
            a_lane = vm638840(BUILDER6388F0_LOW_LOOP_A_MAGIC, &a_source, &f_lane, t)?;
            t_lane = vm638840(BUILDER6388F0_LOW_LOOP_T_MAGIC, &e_lane, &a_lane, t)?;
            b_lane = vm638840(BUILDER6388F0_LOW_LOOP_BMIX_MAGIC, &b_lane, &t_lane, t)?;
            e_lane = vm638840(BUILDER6388F0_LOW_LOOP_EADVANCE_MAGIC, &e_lane, &e_lane, t)?;
            c_lane = vm638840(BUILDER6388F0_LOW_LOOP_CADVANCE_MAGIC, &c_lane, &c_lane, t)?;
        }

        final6377f0 = t_lane.clone();
        let f_lane = vm638840(BUILDER6388F0_LOW_LOOP_POST_F_MAGIC, &b_lane, &e_lane, t)?;
        let d_source = builder6388f0_low_loop_static_block(
            BUILDER6388F0_LOW_LOOP_STATIC_DTABLE + BUILDER6388F0_LOW_LOOP_LANE_BYTES * lane,
            BUILDER6388F0_LOW_LOOP_LANE_BYTES,
            t,
        )?;
        let post_d_lane = vm638840(BUILDER6388F0_LOW_LOOP_POST_D_MAGIC, &f_lane, &d_source, t)?;
        let mut pack_e_lane = vm641fcc(BUILDER6388F0_LOW_LOOP_POST_E_MAGIC, &post_d_lane, t)?;

        let mut packed_lane = a_lane.clone();
        packed_lane[..4].copy_from_slice(&[0, 0, 0, 0]);
        let mut shift = 32usize;
        for pack_index in 0..8usize {
            let c_word = vm638840(BUILDER6388F0_LOW_LOOP_PACK_C_MAGIC, &pack_e_lane, &pack_e_lane, t)?;
            if shift >= 5 {
                pack_e_lane = vm6420d8(BUILDER6388F0_LOW_LOOP_PACK_E_MAGIC, &pack_e_lane, &pack_e_lane, t)?;
            }
            let b_word = vm638840(BUILDER6388F0_LOW_LOOP_PACK_B_MAGIC, &c_word, &c_word, t)?;
            let selected = (b_word[2] as usize) ^ ((b_word[3] as usize) << 3);
            let packed = builder6388f0_low_loop_static_byte(BUILDER6388F0_LOW_LOOP_NIBBLE_TABLE + selected, t)?;
            let mut nibble = if pack_index & 1 == 0 { packed & 0x0f } else { packed >> 4 };
            if shift < 4 {
                let mask: u8 = if shift == 0 { 0 } else { ((1u16 << shift) - 1) as u8 };
                nibble &= mask;
            }

            let byte_index = pack_index >> 1;
            if pack_index & 1 == 0 {
                packed_lane[byte_index] = nibble;
            } else {
                packed_lane[byte_index] ^= nibble << 4;
            }
            shift = shift.saturating_sub(4);
        }
        schedule_words.push(read_u32_le(&packed_lane, 0));
    }

    Ok(Builder6388f0LowSeedLoopResult {
        final6377f0,
        schedule_words,
    })
}

/// kit `builder6388f0Row0LowSeedPreimagesFromEntrySource` (FirstPairSourceSlice.swift L2698-2739).
pub fn builder6388f0_row0_low_seed_preimages_from_entry_source(
    entry_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder6388f0Row0LowSeedPreimages, CryptoError> {
    let seeds = builder6388f0_low_seed_cf0_seeds_from_entry_source(entry_source, t)?;
    let phase3 = builder6388f0_low_seed_phase_from_cf0_seed(&BUILDER6388F0_LOW_SEED_PHASE3_SPEC, &seeds.phase3, t)?;
    let tail_static = builder6388f0_low_seed_static_block(BUILDER6388F0_LOW_SEED_TAIL_STATIC, t)?;
    let tail_bd0 = vm638840(BUILDER6388F0_LOW_SEED_TAIL_BD0_MAGIC, &phase3.final_cf0, &tail_static, t)?;
    let tail_s684 = vm638840(BUILDER6388F0_LOW_SEED_TAIL_S684_MAGIC, &tail_bd0, &tail_bd0, t)?;
    let pair = builder6388f0_low_seed_tail_pair_from_entry_source(entry_source, t)?;
    let tail_stage = builder6388f0_low_seed_tail_stage_from_pair(&pair, t)?;
    let prelude_source = builder6388f0_low_seed_prelude_source_from_tail_stage(&tail_stage, t)?;
    let seed_blocks = builder6388f0_low_seed_blocks_from_prelude_source(&prelude_source, t)?;
    let loop_result = builder6388f0_low_seed_loop_from_blocks(&seed_blocks, t)?;

    let base_out3: Vec<u8> = tail_s684[204..266]
        .iter()
        .chain(pair.right.iter().take(26))
        .copied()
        .collect();
    let mut out3: Vec<u8> = base_out3.iter().take(62).copied().collect();
    out3.extend_from_slice(&loop_result.final6377f0);
    out3.extend_from_slice(&base_out3[base_out3.len() - 8..]);
    Ok(Builder6388f0Row0LowSeedPreimages {
        out4: tail_s684[116..204].to_vec(),
        out3,
        out2: pair.right[26..114].to_vec(),
    })
}

// ---------------------------------------------------------------- 633fa8 static path

/// kit `builder633fa8StaticPreludeSourceFromEntrySource` (FirstPairSourceSlice.swift L2741-2747).
pub fn builder633fa8_static_prelude_source_from_entry_source(
    entry_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let pair = builder6388f0_low_seed_tail_pair_from_entry_source(entry_source, t)?;
    let tail_stage = builder6388f0_low_seed_tail_stage_from_pair(&pair, t)?;
    builder6388f0_low_seed_prelude_source_from_tail_stage(&tail_stage, t)
}

/// kit `builder633fa8TailBoundaryFromPreludeSource` (FirstPairSourceSlice.swift L2756-2768).
pub fn builder633fa8_tail_boundary_from_prelude_source(
    prelude_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder633fa8TailBoundary, CryptoError> {
    let seed_blocks = builder6388f0_low_seed_blocks_from_prelude_source(prelude_source, t)?;
    let loop_result = builder6388f0_low_seed_loop_from_blocks(&seed_blocks, t)?;
    Ok(Builder633fa8TailBoundary {
        words3ab0: loop_result.schedule_words,
        words3120: BUILDER633FA8_INVARIANT_WORDS_3120.to_vec(),
        words2dfc: BUILDER633FA8_INVARIANT_WORDS_2DFC.to_vec(),
        seed3110: BUILDER633FA8_INVARIANT_SEED_3110,
        prelude_source: prelude_source.to_vec(),
    })
}

/// kit `builder633fa8StaticTailBoundaryFromEntrySource` (FirstPairSourceSlice.swift L2749-2754).
pub fn builder633fa8_static_tail_boundary_from_entry_source(
    entry_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder633fa8TailBoundary, CryptoError> {
    let prelude_source = builder633fa8_static_prelude_source_from_entry_source(entry_source, t)?;
    builder633fa8_tail_boundary_from_prelude_source(&prelude_source, t)
}

/// kit `builder633fa8ScalarWindowFromPreludeSource` (FirstPairSourceSlice.swift L2770-2782).
pub fn builder633fa8_scalar_window_from_prelude_source(
    prelude_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let boundary = builder633fa8_tail_boundary_from_prelude_source(prelude_source, t)?;
    let qwords = builder633fa8_tail_qwords_from_sources(
        &boundary.words3ab0,
        &boundary.words3120,
        &boundary.words2dfc,
        boundary.seed3110,
        t,
    )?;
    let e10_words = builder633fa8_e10_words_from_tail_qwords(&qwords, t)?;
    builder633fa8_scalar_window_from_e10_words(&e10_words, t)
}

/// kit `builder633fa8StaticScalarWindowFromEntrySource` (FirstPairSourceSlice.swift L2784-2789).
pub fn builder633fa8_static_scalar_window_from_entry_source(
    entry_source: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let prelude_source = builder633fa8_static_prelude_source_from_entry_source(entry_source, t)?;
    builder633fa8_scalar_window_from_prelude_source(&prelude_source, t)
}

// ---------------------------------------------------------------- 633fa8 null private helpers

/// kit `builder633fa8NullTableBlock` (FirstPairSourceSlice.swift L12993-13005).
fn builder633fa8_null_table_block(
    lib_offset: usize,
    byte_count: usize,
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let offset = lib_offset - BUILDER633FA8_NULL_TABLE_BASE;
    Ok(checked_slice(
        &t.null_tables_633fa8,
        offset,
        byte_count,
        "firstpair_633fa8_null_tables_2fd1f1",
    )?
    .to_vec())
}

/// kit `builder633fa8NullNibbleByte` (FirstPairSourceSlice.swift L13007-13015).
fn builder633fa8_null_nibble_byte(lib_offset: usize, t: &FirstPairTables) -> Result<u8, CryptoError> {
    let offset = lib_offset - BUILDER633FA8_NULL_NIBBLE_TABLE_BASE;
    Ok(checked_slice(&t.null_nibble_633fa8, offset, 1, "firstpair_633fa8_null_nibble_303a14")?[0])
}

/// kit `u32TableWord633fa8Null` (FirstPairSourceSlice.swift L13017-13026).
fn u32_table_word633fa8_null(absolute_offset: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let relative = absolute_offset
        .checked_sub(BUILDER633FA8_NULL_TABLE_BASE)
        .ok_or_else(|| slice_err(format!("633fa8 null table read at {absolute_offset:#x}")))?;
    if relative + 4 > t.null_tables_633fa8.len() {
        return Err(slice_err(format!(
            "table read out of bounds: firstpair_633fa8_null_tables_2fd1f1 at {absolute_offset:#x}"
        )));
    }
    Ok(read_u32_le(&t.null_tables_633fa8, relative))
}

/// kit `fold633fa8NullCheck32` (FirstPairSourceSlice.swift L13028-13042).
fn fold633fa8_null_check32(value: u32, table_offset: usize, rounds: usize, t: &FirstPairTables) -> Result<u32, CryptoError> {
    let mut folded = value;
    for _ in 0..rounds {
        let word = u32_table_word633fa8_null(table_offset + ((folded & 0x0f) as usize) * 4, t)?;
        folded = word.wrapping_add(folded >> 4);
    }
    Ok(folded)
}

/// kit `expand3BitPairTableRow633fa8Null` (FirstPairSourceSlice.swift L13044-13055).
fn expand3_bit_pair_table_row633fa8_null(raw9: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if raw9.len() != 9 {
        return Err(slice_err(format!(
            "source too short: 633fa8 null 3-bit pair row has {}, wants 9",
            raw9.len()
        )));
    }
    let mut out: Vec<u8> = Vec::with_capacity(BUILDER633FA8_NULL_LOOP_LANE_BYTES);
    for &value in raw9 {
        out.push(value & 7);
        out.push((value >> 3) & 7);
    }
    Ok(out)
}

/// kit `stitch633fa8NullPrelude11A` (FirstPairSourceSlice.swift L13057-13088).
fn stitch633fa8_null_prelude11a(first_block: &[u8], rest_blocks: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if first_block.len() != BUILDER633FA8_NULL_SEED_BLOCK_BYTES {
        return Err(slice_err(format!(
            "source too short: 633fa8 null prelude first stitch block has {}, wants {BUILDER633FA8_NULL_SEED_BLOCK_BYTES}",
            first_block.len()
        )));
    }
    let rest_byte_count = (BUILDER633FA8_SCALAR_WORD_COUNT - 1) * BUILDER633FA8_NULL_SEED_BLOCK_BYTES;
    if rest_blocks.len() != rest_byte_count {
        return Err(slice_err(format!(
            "source too short: 633fa8 null prelude rest stitch blocks has {}, wants {rest_byte_count}",
            rest_blocks.len()
        )));
    }

    let mut out = vec![0u8; BUILDER633FA8_NULL_ENTROPY_BYTES];
    out[..BUILDER633FA8_NULL_SEED_BLOCK_BYTES].copy_from_slice(first_block);
    for index in 0..BUILDER633FA8_SCALAR_WORD_COUNT - 1 {
        let src_start = index * BUILDER633FA8_NULL_SEED_BLOCK_BYTES + 2;
        let dst_start = BUILDER633FA8_NULL_SEED_BLOCK_BYTES + index * BUILDER633FA8_NULL_SEED_BLOCK_STRIDE;
        out[dst_start..dst_start + BUILDER633FA8_NULL_SEED_BLOCK_STRIDE]
            .copy_from_slice(&rest_blocks[src_start..src_start + BUILDER633FA8_NULL_SEED_BLOCK_STRIDE]);
    }
    Ok(out)
}

/// kit `builder633fa8NullScheduleCheck` (FirstPairSourceSlice.swift L13090-13122).
fn builder633fa8_null_schedule_check(
    schedule_words: &[u32],
    source_words: &[u32],
    schedule_mul_table: usize,
    source_mul_table: usize,
    add_table: usize,
    fold_table: usize,
    target: u32,
    fold_target: u32,
    t: &FirstPairTables,
) -> Result<bool, CryptoError> {
    if schedule_words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8WordCount({})",
            schedule_words.len()
        )));
    }
    if source_words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8WordCount({})",
            source_words.len()
        )));
    }

    for index in (0..BUILDER633FA8_SCALAR_WORD_COUNT).rev() {
        let table_offset = (index * 4) & 0x1c;
        let word = schedule_words[index]
            .wrapping_mul(u32_table_word_63c278(schedule_mul_table + table_offset, t)?)
            .wrapping_add(
                source_words[index]
                    .wrapping_mul(u32_table_word_63c278(source_mul_table + table_offset, t)?),
            )
            .wrapping_add(u32_table_word_63c278(add_table + table_offset, t)?);
        if word == target {
            continue;
        }
        let folded = fold633fa8_null_check32(word, fold_table, 7, t)?;
        return Ok((folded & 0x0f) == fold_target);
    }
    Ok(true)
}

/// kit `unpack3BitStream5bdd14` (FirstPairSourceSlice.swift L12745-12796): returns the unpacked
/// bytes and the new byte cursor.
fn unpack3_bit_stream5bdd14(source: &[u8], offset: usize, count: usize) -> Result<(Vec<u8>, usize), CryptoError> {
    if offset >= source.len() {
        return Err(slice_err(format!(
            "source too short: 3-bit unpack source has {}, wants {}",
            source.len(),
            offset + 1
        )));
    }

    let mut pointer = offset;
    let mut bit_offset = 0usize;
    let mut out: Vec<u8> = Vec::with_capacity(count);
    let mut remaining = count;
    while remaining > 0 {
        let current_bit = bit_offset & 0xff;
        if current_bit == 8 {
            pointer += 1;
            if pointer >= source.len() {
                return Err(slice_err(format!(
                    "source too short: 3-bit unpack source has {}, wants {}",
                    source.len(),
                    pointer + 1
                )));
            }
            out.push(source[pointer] & 7);
            bit_offset = 3;
        } else if current_bit == 0 {
            out.push(source[pointer] & 7);
            bit_offset = 3;
        } else if current_bit <= 5 {
            out.push((source[pointer] >> current_bit) & 7);
            bit_offset = current_bit + 3;
        } else {
            let span_bits = current_bit - 5;
            if pointer + 1 >= source.len() {
                return Err(slice_err(format!(
                    "source too short: 3-bit unpack source has {}, wants {}",
                    source.len(),
                    pointer + 2
                )));
            }
            let low = source[pointer] >> current_bit;
            let high = source[pointer + 1] & ((1u16 << span_bits) as u8 - 1);
            out.push((low | (high << (8 - current_bit))) & 7);
            pointer += 1;
            bit_offset = span_bits;
        }
        remaining -= 1;
    }

    pointer += 1;
    if pointer > source.len() {
        return Err(slice_err(format!(
            "source too short: 3-bit unpack source has {}, wants {pointer}",
            source.len()
        )));
    }
    Ok((out, pointer))
}

// ---------------------------------------------------------------- 633fa8 null public path

/// kit `builder633fa8NullEntrySourcesFromInvariantEntry` (FirstPairSourceSlice.swift L2791-2816).
pub fn builder633fa8_null_entry_sources_from_invariant_entry() -> Result<Builder633fa8NullEntrySources, CryptoError> {
    let source = &BUILDER633FA8_NULL_ENTRY_BITS_CHECKS_SOURCE;
    let (prologue, cursor) = unpack3_bit_stream5bdd14(source, 0, BUILDER633FA8_NULL_ENTROPY_BYTES)?;
    let check_bytes = BUILDER633FA8_SCALAR_WORD_COUNT * 4;
    if cursor + 2 * check_bytes > source.len() {
        return Err(slice_err(format!(
            "source too short: 633fa8 null entry checks has {}, wants {}",
            source.len(),
            cursor + 2 * check_bytes
        )));
    }
    let check1 = (0..BUILDER633FA8_SCALAR_WORD_COUNT)
        .map(|i| read_u32_le(source, cursor + i * 4))
        .collect();
    let check2 = (0..BUILDER633FA8_SCALAR_WORD_COUNT)
        .map(|i| read_u32_le(source, cursor + check_bytes + i * 4))
        .collect();
    Ok(Builder633fa8NullEntrySources {
        prologue_source: prologue,
        check1_source_words: check1,
        check2_source_words: check2,
    })
}

/// kit `builder633fa8NullInitialFromEntropy` (FirstPairSourceSlice.swift L2818-2892).
pub fn builder633fa8_null_initial_from_entropy(
    entropy11a: &[u8],
    prologue_source: &[u8],
    t: &FirstPairTables,
) -> Result<Builder633fa8NullInitialResult, CryptoError> {
    if entropy11a.len() != BUILDER633FA8_NULL_ENTROPY_BYTES {
        return Err(slice_err(format!(
            "invalid633fa8NullEntropyLength({})",
            entropy11a.len()
        )));
    }
    if prologue_source.len() < BUILDER633FA8_NULL_ENTROPY_BYTES {
        return Err(slice_err(format!(
            "source too short: 633fa8 null prologue source has {}, wants {BUILDER633FA8_NULL_ENTROPY_BYTES}",
            prologue_source.len()
        )));
    }
    let masked_entropy: Vec<u8> = entropy11a.iter().map(|&b| b & 7).collect();
    let prologue = &prologue_source[..BUILDER633FA8_NULL_ENTROPY_BYTES];
    let cf0 = vm638840(BUILDER633FA8_NULL_INITIAL_A_MAGIC, &masked_entropy, prologue, t)?;
    let e10 = vm638840(BUILDER633FA8_NULL_INITIAL_B_MAGIC, &cf0, &cf0, t)?;
    if e10.len() < BUILDER633FA8_NULL_ENTROPY_BYTES {
        return Err(slice_err(format!(
            "source too short: 633fa8 null initial e10 has {}, wants {BUILDER633FA8_NULL_ENTROPY_BYTES}",
            e10.len()
        )));
    }

    let last_seed_input_end = (BUILDER633FA8_SCALAR_WORD_COUNT - 1) * BUILDER633FA8_NULL_SEED_BLOCK_STRIDE
        + BUILDER633FA8_NULL_SEED_BLOCK_BYTES;
    if e10.len() < last_seed_input_end {
        return Err(slice_err(format!(
            "source too short: 633fa8 null initial seed input source has {}, wants {last_seed_input_end}",
            e10.len()
        )));
    }

    let mut seed_inputs: Vec<u8> =
        Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT * BUILDER633FA8_NULL_SEED_BLOCK_BYTES);
    for index in 0..BUILDER633FA8_SCALAR_WORD_COUNT {
        let start = index * BUILDER633FA8_NULL_SEED_BLOCK_STRIDE;
        seed_inputs.extend_from_slice(&e10[start..start + BUILDER633FA8_NULL_SEED_BLOCK_BYTES]);
    }

    let mut seed_blocks: Vec<u8> =
        Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT * BUILDER633FA8_NULL_SEED_BLOCK_BYTES);
    for index in 0..BUILDER633FA8_SCALAR_WORD_COUNT {
        let start = index * BUILDER633FA8_NULL_SEED_BLOCK_BYTES;
        let block = &seed_inputs[start..start + BUILDER633FA8_NULL_SEED_BLOCK_BYTES];
        seed_blocks.extend_from_slice(&vm638840(BUILDER633FA8_NULL_SEED_BLOCK_MAGIC, block, block, t)?);
    }

    Ok(Builder633fa8NullInitialResult {
        masked_entropy,
        cf0,
        e10,
        seed_inputs,
        seed_blocks,
    })
}

/// kit `builder633fa8NullFirstLoopFromBlocks` (FirstPairSourceSlice.swift L2894-3062).
pub fn builder633fa8_null_first_loop_from_blocks(
    seed_blocks: &[u8],
    t: &FirstPairTables,
) -> Result<Builder633fa8NullFirstLoopResult, CryptoError> {
    if seed_blocks.len() != BUILDER633FA8_NULL_SEED_BLOCKS_BYTES {
        return Err(slice_err(format!(
            "invalid633fa8NullSeedBlocksLength({})",
            seed_blocks.len()
        )));
    }
    let blocks = seed_blocks;
    let mut schedule_words: Vec<u32> = Vec::with_capacity(BUILDER633FA8_SCALAR_WORD_COUNT);
    let mut final_t_lane: Vec<u8> = Vec::new();

    for outer_index in 0..BUILDER633FA8_SCALAR_WORD_COUNT {
        let lane = outer_index & 7;
        let mut c_lane = builder633fa8_null_table_block(BUILDER633FA8_NULL_LOOP_STATIC_CTABLE, 0x10, t)?;
        c_lane.extend_from_slice(&[0x05, 0x05]);

        let e_source = builder633fa8_null_table_block(
            BUILDER633FA8_NULL_LOOP_STATIC_ETABLE + BUILDER633FA8_NULL_LOOP_LANE_BYTES * lane,
            BUILDER633FA8_NULL_LOOP_LANE_BYTES,
            t,
        )?;
        let mut e_lane = vm638840(BUILDER633FA8_NULL_LOOP_EINIT_MAGIC, &e_source, &e_source, t)?;
        let block_offset = outer_index * BUILDER633FA8_NULL_SEED_BLOCK_BYTES;
        let block = &blocks[block_offset..block_offset + BUILDER633FA8_NULL_SEED_BLOCK_BYTES];
        let d_lane = vm6420d8(BUILDER633FA8_NULL_LOOP_DINIT_MAGIC, block, block, t)?;
        let mut b_lane = vm638840(BUILDER633FA8_NULL_LOOP_BINIT_MAGIC, &e_lane, &e_lane, t)?;

        let mut a_lane = vec![0u8; BUILDER633FA8_NULL_LOOP_LANE_BYTES];
        let mut t_lane = vec![0u8; BUILDER633FA8_NULL_LOOP_LANE_BYTES];
        for _ in 0..28 {
            let f_lane = vm638840(BUILDER633FA8_NULL_LOOP_F_MAGIC, &d_lane, &c_lane, t)?;
            let a_source = builder633fa8_null_table_block(
                BUILDER633FA8_NULL_LOOP_STATIC_ATABLE,
                BUILDER633FA8_NULL_LOOP_LANE_BYTES,
                t,
            )?;
            a_lane = vm638840(BUILDER633FA8_NULL_LOOP_A_MAGIC, &a_source, &f_lane, t)?;
            t_lane = vm638840(BUILDER633FA8_NULL_LOOP_T_MAGIC, &e_lane, &a_lane, t)?;
            b_lane = vm638840(BUILDER633FA8_NULL_LOOP_BMIX_MAGIC, &b_lane, &t_lane, t)?;
            e_lane = vm638840(BUILDER633FA8_NULL_LOOP_EADVANCE_MAGIC, &e_lane, &e_lane, t)?;
            c_lane = vm638840(BUILDER633FA8_NULL_LOOP_CADVANCE_MAGIC, &c_lane, &c_lane, t)?;
        }

        final_t_lane = t_lane.clone();
        let f_lane = vm638840(BUILDER633FA8_NULL_LOOP_POST_F_MAGIC, &b_lane, &e_lane, t)?;
        let d_source = builder633fa8_null_table_block(
            BUILDER633FA8_NULL_LOOP_STATIC_DTABLE + BUILDER633FA8_NULL_LOOP_LANE_BYTES * lane,
            BUILDER633FA8_NULL_LOOP_LANE_BYTES,
            t,
        )?;
        let post_d_lane = vm638840(BUILDER633FA8_NULL_LOOP_POST_D_MAGIC, &f_lane, &d_source, t)?;
        let mut pack_e_lane = vm641fcc(BUILDER633FA8_NULL_LOOP_POST_E_MAGIC, &post_d_lane, t)?;

        let mut packed_lane = a_lane.clone();
        packed_lane[..4].copy_from_slice(&[0, 0, 0, 0]);
        let mut shift = 32usize;
        for pack_index in 0..8usize {
            let c_word = vm638840(BUILDER633FA8_NULL_LOOP_PACK_C_MAGIC, &pack_e_lane, &pack_e_lane, t)?;
            if shift >= 5 {
                pack_e_lane = vm6420d8(BUILDER633FA8_NULL_LOOP_PACK_E_MAGIC, &pack_e_lane, &pack_e_lane, t)?;
            }
            let b_word = vm638840(BUILDER633FA8_NULL_LOOP_PACK_B_MAGIC, &c_word, &c_word, t)?;
            let selected = (b_word[2] as usize) ^ ((b_word[3] as usize) << 3);
            let packed = builder633fa8_null_nibble_byte(BUILDER633FA8_NULL_LOOP_NIBBLE_TABLE + selected, t)?;
            let mut nibble = if pack_index & 1 == 0 { packed & 0x0f } else { packed >> 4 };
            if shift < 4 {
                let mask: u8 = if shift == 0 { 0 } else { ((1u16 << shift) - 1) as u8 };
                nibble &= mask;
            }

            let byte_index = pack_index >> 1;
            if pack_index & 1 == 0 {
                packed_lane[byte_index] = nibble;
            } else {
                packed_lane[byte_index] ^= nibble << 4;
            }
            shift = shift.saturating_sub(4);
        }

        schedule_words.push(read_u32_le(&packed_lane, 0));
    }

    Ok(Builder633fa8NullFirstLoopResult {
        final_t_lane,
        schedule_words,
    })
}

/// kit `builder633fa8NullScheduleAcceptance` (FirstPairSourceSlice.swift L3064-3093).
pub fn builder633fa8_null_schedule_acceptance(
    schedule_words: &[u32],
    check1_source_words: &[u32],
    check2_source_words: &[u32],
    t: &FirstPairTables,
) -> Result<Builder633fa8NullScheduleAcceptance, CryptoError> {
    let first_ok = builder633fa8_null_schedule_check(
        schedule_words,
        check1_source_words,
        BUILDER633FA8_NULL_CHECK1_SCHEDULE_MUL_TABLE,
        BUILDER633FA8_NULL_CHECK1_SOURCE_MUL_TABLE,
        BUILDER633FA8_NULL_CHECK1_ADD_TABLE,
        BUILDER633FA8_NULL_CHECK1_FOLD_TABLE,
        BUILDER633FA8_NULL_CHECK1_TARGET,
        BUILDER633FA8_NULL_CHECK1_FOLD_TARGET,
        t,
    )?;
    let second_ok = builder633fa8_null_schedule_check(
        schedule_words,
        check2_source_words,
        BUILDER633FA8_NULL_CHECK2_SCHEDULE_MUL_TABLE,
        BUILDER633FA8_NULL_CHECK2_SOURCE_MUL_TABLE,
        BUILDER633FA8_NULL_CHECK2_ADD_TABLE,
        BUILDER633FA8_NULL_CHECK2_FOLD_TABLE,
        BUILDER633FA8_NULL_CHECK2_TARGET,
        BUILDER633FA8_NULL_CHECK2_FOLD_TARGET,
        t,
    )?;
    Ok(Builder633fa8NullScheduleAcceptance { first_ok, second_ok })
}

/// kit `builder633fa8NullPostAcceptBlocks` (FirstPairSourceSlice.swift L3095-3218).
pub fn builder633fa8_null_post_accept_blocks(
    schedule_words: &[u32],
    t: &FirstPairTables,
) -> Result<Builder633fa8NullPostAcceptResult, CryptoError> {
    if schedule_words.len() != BUILDER633FA8_SCALAR_WORD_COUNT {
        return Err(slice_err(format!(
            "invalid633fa8WordCount({})",
            schedule_words.len()
        )));
    }
    let init_cf0 = builder633fa8_null_table_block(BUILDER633FA8_NULL_POST_INIT_CF0_STATIC, BUILDER633FA8_NULL_SEED_BLOCK_BYTES, t)?;
    let init_bd0 = builder633fa8_null_table_block(BUILDER633FA8_NULL_POST_INIT_BD0_STATIC, BUILDER633FA8_NULL_SEED_BLOCK_BYTES, t)?;
    let final_cf0_static = builder633fa8_null_table_block(BUILDER633FA8_NULL_POST_FINAL_CF0_STATIC, BUILDER633FA8_NULL_LOOP_LANE_BYTES, t)?;
    let final_bd0_static = builder633fa8_null_table_block(BUILDER633FA8_NULL_POST_FINAL_BD0_STATIC, BUILDER633FA8_NULL_LOOP_LANE_BYTES, t)?;

    let mut blocks4080: Vec<u8> = Vec::with_capacity(BUILDER633FA8_NULL_SEED_BLOCKS_BYTES);
    let mut blocks3f40: Vec<u8> = Vec::with_capacity(BUILDER633FA8_NULL_SEED_BLOCKS_BYTES);

    for (index, &schedule_word) in schedule_words.iter().enumerate() {
        let table_offset = (index * 4) & 0x1c;
        let selector = schedule_word
            .wrapping_mul(u32_table_word_63c278(BUILDER633FA8_NULL_POST_KEY_MUL_TABLE + table_offset, t)?)
            .wrapping_add(u32_table_word_63c278(BUILDER633FA8_NULL_POST_KEY_ADD_TABLE + table_offset, t)?);
        let mut cf0_state = init_cf0.clone();
        let mut bd0_state = init_bd0.clone();

        for shift in [24usize, 16, 8, 0] {
            let cf0_source: Vec<u8> = [0u8; 3]
                .iter()
                .chain(std::iter::once(&0x05u8))
                .chain(cf0_state[0..8].iter())
                .chain(cf0_state[8..12].iter())
                .chain(cf0_state[12..14].iter())
                .copied()
                .collect();
            let bd0_source: Vec<u8> = [0u8; 3]
                .iter()
                .chain(std::iter::once(&0x03u8))
                .chain(bd0_state[0..8].iter())
                .chain(bd0_state[8..12].iter())
                .chain(bd0_state[12..14].iter())
                .copied()
                .collect();
            let cf0 = vm638840(BUILDER633FA8_NULL_POST_INIT_CF0_MAGIC, &cf0_source, &cf0_source, t)?;
            let bd0 = vm638840(BUILDER633FA8_NULL_POST_INIT_BD0_MAGIC, &bd0_source, &bd0_source, t)?;
            let byte_value = ((selector >> shift) & 0xff) as usize;
            let cf0_row = expand3_bit_pair_table_row633fa8_null(&builder633fa8_null_table_block(
                BUILDER633FA8_NULL_POST_TABLE_CF0 + byte_value * 9,
                9,
                t,
            )?)?;
            let bd0_row = expand3_bit_pair_table_row633fa8_null(&builder633fa8_null_table_block(
                BUILDER633FA8_NULL_POST_TABLE_BD0 + byte_value * 9,
                9,
                t,
            )?)?;
            cf0_state = vm638840(BUILDER633FA8_NULL_POST_MIX_CF0_MAGIC, &cf0, &cf0_row, t)?;
            bd0_state = vm638840(BUILDER633FA8_NULL_POST_MIX_BD0_MAGIC, &bd0, &bd0_row, t)?;
        }

        let e10 = vm638840(BUILDER633FA8_NULL_POST_FINAL_CF0_MAGIC, &cf0_state, &final_cf0_static, t)?;
        let ab0 = vm638840(BUILDER633FA8_NULL_POST_FINAL_BD0_MAGIC, &bd0_state, &final_bd0_static, t)?;
        blocks4080.extend_from_slice(&vm638840(BUILDER633FA8_NULL_POST_BLOCK_4080_MAGIC, &e10, &e10, t)?);
        blocks3f40.extend_from_slice(&vm638840(BUILDER633FA8_NULL_POST_BLOCK_3F40_MAGIC, &ab0, &ab0, t)?);
    }

    Ok(Builder633fa8NullPostAcceptResult {
        blocks4080,
        blocks3f40,
    })
}

/// kit `builder633fa8NullPreludeSourceFromPostAccept` (FirstPairSourceSlice.swift L3220-3311).
pub fn builder633fa8_null_prelude_source_from_post_accept(
    blocks4080: &[u8],
    blocks3f40: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    if blocks4080.len() != BUILDER633FA8_NULL_SEED_BLOCKS_BYTES {
        return Err(slice_err(format!(
            "invalid633fa8NullPostAcceptBlocksLength(4080, {})",
            blocks4080.len()
        )));
    }
    if blocks3f40.len() != BUILDER633FA8_NULL_SEED_BLOCKS_BYTES {
        return Err(slice_err(format!(
            "invalid633fa8NullPostAcceptBlocksLength(3f40, {})",
            blocks3f40.len()
        )));
    }

    let bytes4080 = blocks4080;
    let first4080_block = &bytes4080[..BUILDER633FA8_NULL_SEED_BLOCK_BYTES];
    let first4080 = vm638840(BUILDER633FA8_NULL_PRELUDE_FIRST_4080_MAGIC, first4080_block, first4080_block, t)?;
    let mut rest4080: Vec<u8> =
        Vec::with_capacity((BUILDER633FA8_SCALAR_WORD_COUNT - 1) * BUILDER633FA8_NULL_SEED_BLOCK_BYTES);
    for index in 1..BUILDER633FA8_SCALAR_WORD_COUNT {
        let start = index * BUILDER633FA8_NULL_SEED_BLOCK_BYTES;
        let block = &bytes4080[start..start + BUILDER633FA8_NULL_SEED_BLOCK_BYTES];
        rest4080.extend_from_slice(&vm638840(BUILDER633FA8_NULL_PRELUDE_REST_4080_MAGIC, block, block, t)?);
    }
    let stitched4080 = stitch633fa8_null_prelude11a(&first4080, &rest4080)?;
    let bd0 = vm638840(BUILDER633FA8_NULL_PRELUDE_BD0_MAGIC, &stitched4080, &stitched4080, t)?;

    let bytes3f40 = blocks3f40;
    let first3f40_block = &bytes3f40[..BUILDER633FA8_NULL_SEED_BLOCK_BYTES];
    let first3f40 = vm638840(BUILDER633FA8_NULL_PRELUDE_FIRST_3F40_MAGIC, first3f40_block, first3f40_block, t)?;
    let mut rest3f40: Vec<u8> =
        Vec::with_capacity((BUILDER633FA8_SCALAR_WORD_COUNT - 1) * BUILDER633FA8_NULL_SEED_BLOCK_BYTES);
    for index in 1..BUILDER633FA8_SCALAR_WORD_COUNT {
        let start = index * BUILDER633FA8_NULL_SEED_BLOCK_BYTES;
        let block = &bytes3f40[start..start + BUILDER633FA8_NULL_SEED_BLOCK_BYTES];
        rest3f40.extend_from_slice(&vm638840(BUILDER633FA8_NULL_PRELUDE_REST_3F40_MAGIC, block, block, t)?);
    }
    let stitched3f40 = stitch633fa8_null_prelude11a(&first3f40, &rest3f40)?;
    let ab0 = vm638840(BUILDER633FA8_NULL_PRELUDE_AB0_MAGIC, &stitched3f40, &stitched3f40, t)?;
    let stage4080 = vm638840(BUILDER633FA8_NULL_PRELUDE_STAGE_4080_MAGIC, &bd0, &ab0, t)?;
    let f40 = vm638840(BUILDER633FA8_NULL_PRELUDE_F40_MAGIC, &stage4080, &ab0, t)?;
    vm638840(BUILDER633FA8_NULL_PRELUDE_SOURCE_MAGIC, &f40, &f40, t)
}

/// kit `builder633fa8NullPreludeSourceFromEntropy` (FirstPairSourceSlice.swift L3313-3336).
pub fn builder633fa8_null_prelude_source_from_entropy(
    entropy11a: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let sources = builder633fa8_null_entry_sources_from_invariant_entry()?;
    let initial = builder633fa8_null_initial_from_entropy(entropy11a, &sources.prologue_source, t)?;
    let loop_result = builder633fa8_null_first_loop_from_blocks(&initial.seed_blocks, t)?;
    let acceptance = builder633fa8_null_schedule_acceptance(
        &loop_result.schedule_words,
        &sources.check1_source_words,
        &sources.check2_source_words,
        t,
    )?;
    if !(acceptance.first_ok && acceptance.second_ok) {
        return Err(slice_err("rejected633fa8NullEntropy"));
    }

    let post_accept = builder633fa8_null_post_accept_blocks(&loop_result.schedule_words, t)?;
    builder633fa8_null_prelude_source_from_post_accept(&post_accept.blocks4080, &post_accept.blocks3f40, t)
}

/// kit `builder633fa8NullScalarWindowFromEntropy` (FirstPairSourceSlice.swift L3338-3343).
pub fn builder633fa8_null_scalar_window_from_entropy(
    entropy11a: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let prelude_source = builder633fa8_null_prelude_source_from_entropy(entropy11a, t)?;
    builder633fa8_scalar_window_from_prelude_source(&prelude_source, t)
}

/// kit `builder633fa8NullPublicEntrySourceFromEntropy` (FirstPairSourceSlice.swift L3345-3351).
pub fn builder633fa8_null_public_entry_source_from_entropy(
    entropy11a: &[u8],
    t: &FirstPairTables,
) -> Result<Vec<u8>, CryptoError> {
    let prelude_source = builder633fa8_null_prelude_source_from_entropy(entropy11a, t)?;
    let scalar = builder633fa8_scalar_window_from_prelude_source(&prelude_source, t)?;
    let mut out = prelude_source;
    out.extend_from_slice(&scalar[..0x10]);
    Ok(out)
}

/// kit `builder633fa8NullScalarWindowFromEntropySource` (FirstPairSourceSlice.swift L3375-3397):
/// the attempt loop. `maxAttempts == 0` mirrors the kit `invalid633fa8NullMaxAttempts(0)` case;
/// only `rejected633fa8NullEntropy` is retried (any other error propagates).
pub fn builder633fa8_null_scalar_window_from_entropy_source<F>(
    max_attempts: usize,
    mut entropy_source: F,
    t: &FirstPairTables,
) -> Result<Builder633fa8NullScalarResult, CryptoError>
where
    F: FnMut(usize) -> Result<Vec<u8>, CryptoError>,
{
    if max_attempts == 0 {
        return Err(slice_err("invalid633fa8NullMaxAttempts(0)"));
    }

    for attempt in 1..=max_attempts {
        let entropy = entropy_source(BUILDER633FA8_NULL_ENTROPY_BYTES)?;
        match builder633fa8_null_scalar_window_from_entropy(&entropy, t) {
            Ok(scalar) => {
                return Ok(Builder633fa8NullScalarResult {
                    scalar_window: scalar,
                    entropy11a: entropy,
                    attempts: attempt,
                });
            }
            // kit catches only `FirstPairSourceSliceError.rejected633fa8NullEntropy`.
            Err(CryptoError::Slice { ref reason }) if reason == "rejected633fa8NullEntropy" => continue,
            Err(e) => return Err(e),
        }
    }
    Err(slice_err(format!(
        "rejected633fa8NullEntropyAfterAttempts({max_attempts})"
    )))
}
