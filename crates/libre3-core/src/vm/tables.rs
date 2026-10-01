//! Runtime-table set for the first-pair source builder (FirstPairSourceSlice). The same
//! table files the cipher-fn/libaes/phase5 modules read, loaded here with the size checks
//! the kit's `SourceTables` applies. No table bytes in the repo (PLAN_T1DMDROID.md §9).

use crate::CryptoError;

/// The validated table set. Fields stay private; access goes through checked slicing.
pub struct FirstPairTables {
    pub sbox19: Vec<u8>,
    pub prog64e2b8: Vec<u8>,
    pub prog638840: Vec<u8>,
    pub low_seed_statics_6388f0: Vec<u8>,
    pub low_loop_statics_6388f0: Vec<u8>,
    pub lane_tables_6388f0: Vec<u8>,
    pub selector_mul_6388f0: Vec<u8>,
    pub selector_add_6388f0: Vec<u8>,
    pub u32_tables_63c278: Vec<u8>,
    pub fold_tables_63c278: Vec<u8>,
    pub tail_fold_tables_633fa8: Vec<u8>,
    pub tail_u32_low_tables_633fa8: Vec<u8>,
    pub null_tables_633fa8: Vec<u8>,
    pub null_nibble_633fa8: Vec<u8>,
    pub process2_public_tables: Vec<u8>,
    pub prog67cc18: Vec<u8>,
    pub ttable_b_ext: Vec<u8>,
    pub final_len_tables: Vec<u8>,
    pub df80_round_tables: Vec<u8>,
    pub finalizer_tables: Vec<u8>,
    pub seed_tables_679f48: Vec<u8>,
    pub reducer67ea28_nibble: Vec<u8>,
    pub prog67076c: Vec<u8>,
    pub shared_context_6388f0: Vec<u8>,
    pub caller_loop_interleaved_6388f0: Vec<u8>,
}

/// (name, want, at-least) — `at_least = true` mirrors the kit's `count >= length` checks on
/// the program regions; everything else is an exact size.
const SPEC: &[(&str, usize, bool)] = &[
    ("sbox_19bit_lib_986819", 0x80000, false),
    ("firstpair_prog_64e2b8_3041b4", 0x250, true),
    ("firstpair_prog_638840_2f5046", 0x8200, true),
    ("firstpair_6388f0_low_seed_statics_2f4d28", 0x31e, false),
    ("firstpair_6388f0_low_loop_statics_2fe600", 0x194, false),
    ("firstpair_6388f0_lane_tables_302678", 0x1248, false),
    ("firstpair_6388f0_selector_mul_116968", 0x20, false),
    ("firstpair_6388f0_selector_add_119788", 0x20, false),
    ("firstpair_63c278_u32_tables_112588", 0x14790, false),
    ("firstpair_63c278_fold_tables_2feb18", 0x3b60, false),
    ("firstpair_633fa8_tail_fold_tables_2fe798", 0x380, false),
    ("firstpair_633fa8_tail_u32_low_tables_112528", 0x40, false),
    ("firstpair_633fa8_null_tables_2fd1f1", 0x140f, false),
    ("firstpair_633fa8_null_nibble_303a14", 0x40, false),
    ("firstpair_process2_public_tables_3038c0", 0x518, false),
    ("firstpair_prog_67cc18_369862", 0x6100, true),
    ("child23_ttable_b_ext_976ea8_100000", 0x100000, true),
    ("firstpair_final_len_tables_372102", 0x600, false),
    ("firstpair_df80_round_tables_37120e", 0x492, false),
    ("firstpair_finalizer_tables_370e30", 0x12d2, false),
    ("firstpair_679f48_seed_tables_37075e", 0x6d2, false),
    ("firstpair_reducer67ea28_nibble_373cf4", 0x40, false),
    ("firstpair_prog_67076c_35d3ef", 0x84, false),
    // FirstPairSourceSlice.swift L3781-3790 (builder6388f0SharedContextFromBundle):
    // `builder6388f0SharedContextLength = 0x520` (L14035), exact-size guard.
    ("firstpair_6388f0_shared_context_2cdae1", 0x520, false),
    // FirstPairSourceSlice.swift L3792-3815 (builder6388f0CallerLoopTablesFromBundle):
    // `builder6388f0CallerLoopInterleavedLength = 59 * 0xb0` (L14036-14040), exact-size guard.
    ("firstpair_6388f0_caller_loop_interleaved_2cdfa9", 59 * 0xb0, false),
];

impl FirstPairTables {
    /// Loads and validates the table set from a directory (the runtime-table bundle).
    pub fn from_dir(dir: &std::path::Path) -> Result<FirstPairTables, CryptoError> {
        let loaded: Vec<Vec<u8>> = SPEC
            .iter()
            .map(|&(name, want, at_least)| {
                let bytes = std::fs::read(std::path::Path::new(dir).join(format!("{name}.bin")))
                    .map_err(|_| CryptoError::TablesMissing { name: name.to_owned() })?;
                let ok = if at_least { bytes.len() >= want } else { bytes.len() == want };
                if !ok {
                    return Err(CryptoError::TablesSize {
                        name: name.to_owned(),
                        want,
                        got: bytes.len(),
                    });
                }
                Ok(bytes)
            })
            .collect::<Result<_, _>>()?;
        let mut it = loaded.into_iter();
        macro_rules! next {
            () => {
                it.next().expect("table count")
            };
        }
        Ok(FirstPairTables {
            sbox19: next!(),
            prog64e2b8: next!(),
            prog638840: next!(),
            low_seed_statics_6388f0: next!(),
            low_loop_statics_6388f0: next!(),
            lane_tables_6388f0: next!(),
            selector_mul_6388f0: next!(),
            selector_add_6388f0: next!(),
            u32_tables_63c278: next!(),
            fold_tables_63c278: next!(),
            tail_fold_tables_633fa8: next!(),
            tail_u32_low_tables_633fa8: next!(),
            null_tables_633fa8: next!(),
            null_nibble_633fa8: next!(),
            process2_public_tables: next!(),
            prog67cc18: next!(),
            ttable_b_ext: next!(),
            final_len_tables: next!(),
            df80_round_tables: next!(),
            finalizer_tables: next!(),
            seed_tables_679f48: next!(),
            reducer67ea28_nibble: next!(),
            prog67076c: next!(),
            shared_context_6388f0: next!(),
            caller_loop_interleaved_6388f0: next!(),
        })
    }
}