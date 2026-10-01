//! FirstPairSourceSlice port (PLAN_T1DMDROID.md §4/§5.5): the first-pair Phase 5 key-source
//! builder, re-derived from the plan alone. Table delivery is out of band (§9) — `FirstPairTables`
//! loads the runtime-table set from a directory, and every computation is fail-closed.

pub mod caller642;
pub mod caller6473d0;
pub mod firstpair;
pub mod highseed;
pub mod lowseed;
pub mod process2;
pub mod schedule;
pub mod seeded64;
pub mod slice;
pub mod tables;

pub use caller642::{
    builder64bd0c_arg0_u64_words, builder64bd0c_final_u32_words, builder64bd0c_output_words,
    builder64bd0c_workspace_after_update, builder642f60_eighth64bd0c_workspace,
    builder642f60_eighth_streams, builder642f60_first64bd0c_workspace_from_x1,
    builder642f60_fourth64bd0c_workspace, builder642f60_mid_fifth64bd0c_workspace,
    builder642f60_mid_stage_sp40_words_from_spa90, builder642f60_mid_stage_sp510_words,
    builder642f60_mid_stage_sp670_words, builder642f60_mid_stage_spa90_sp880_from_sp40,
    builder642f60_mid_stage_spa90_words_from_x0, builder642f60_mid_stage_static_sp9e0_sp7d0,
    builder642f60_mid_stage_streams_from_context_spf0, builder642f60_out0_source_words,
    builder642f60_out0_words_from_source, builder642f60_out1_words_from64bd0c_output,
    builder642f60_out2_words_from64bd0c_output, builder642f60_outputs,
    builder642f60_outputs_from_bundled_context, builder642f60_second64bd0c_workspace,
    builder642f60_seventh64bd0c_workspace, builder642f60_seventh_sp40_words_from_source44,
    builder642f60_seventh_sp7d0_words_from_spa90, builder642f60_seventh_sp9e0_words,
    builder642f60_seventh_source44_words, builder642f60_seventh_source_words,
    builder642f60_seventh_spa90_words_from_sp300, builder642f60_seventh_stage_sp148_words_from_source,
    builder642f60_seventh_streams, builder642f60_sixth64bd0c_workspace,
    builder642f60_sixth_streams_from_sp1a0, builder642f60_stage_sp148_words_from64bd0c_output,
    builder642f60_stage_sp1a0_words_from64bd0c_output, builder642f60_stage_sp1f8_words_from_x0,
    builder642f60_stage_sp250_words_from64bd0c_output, builder642f60_stage_sp2a8_words_from_x1,
    builder642f60_stage_sp300_words_from64bd0c_output, builder642f60_stage_spf0_words_from64bd0c_output,
    builder642f60_third64bd0c_workspace_from_x2, Builder642f60EighthStreams, Builder642f60MidSp40B,
    Builder642f60MidStageStreams, Builder642f60MidStatic, Builder642f60Result,
    Builder642f60SeventhStreams, Builder642f60SixthStreams,
};
pub use caller6473d0::{
    builder64c524_arg0_u64_words, builder64c524_final_u32_words, builder64c524_output_words,
    builder64c524_workspace_after_update, builder64cd40_arg0_u64_words, builder64cd40_final_u32_words,
    builder64cd40_output_words, builder64cd40_workspace_after_update,
    builder6473d0_eighth64c524_workspace, builder6473d0_eighth_sp2d0_words,
    builder6473d0_eighth_streams, builder6473d0_final_out4_words, builder6473d0_first64c524_workspace,
    builder6473d0_first_streams_from_in2, builder6473d0_fifth64c524_workspace,
    builder6473d0_fifth_source_words, builder6473d0_fifth_sp3d8_words, builder6473d0_fifth_streams,
    builder6473d0_fourth64c524_workspace, builder6473d0_fourth_streams,
    builder6473d0_minimal_stack20_from_preimages, builder6473d0_ninth64c524_workspace,
    builder6473d0_ninth_first_source_words, builder6473d0_ninth_first_streams,
    builder6473d0_ninth_out2_words, builder6473d0_ninth_second_source_words,
    builder6473d0_ninth_second_streams, builder6473d0_ninth_sp118_words,
    builder6473d0_ninth_sp1c8_words, builder6473d0_ninth_sp278_words, builder6473d0_ninth_sp68_words,
    builder6473d0_ninth_third_source_words, builder6473d0_outputs,
    builder6473d0_outputs_from_bundled_context, builder6473d0_post_vectors,
    builder6473d0_second64c524_workspace, builder6473d0_second_streams,
    builder6473d0_seventh64c524_workspace, builder6473d0_seventh_sp328_words,
    builder6473d0_seventh_streams, builder6473d0_sixth64c524_workspace,
    builder6473d0_sixth_sp380_words, builder6473d0_sixth_streams,
    builder6473d0_sp488_words_from64c524_output, builder6473d0_tenth64c524_workspace,
    builder6473d0_tenth_out3_words, builder6473d0_tenth_streams, builder6473d0_third64c524_workspace,
    builder6473d0_third_source_words, builder6473d0_third_sp430_words, builder6473d0_third_streams,
    Builder6473d0Result, Builder6473d0Streams,
};
pub use firstpair::{vm638840, vm641fcc, vm6420d8, vm64e17c, vm64e2b8, vm67076c, vm67cc18, vm67cecc, vm67d524};
pub use highseed::{
    builder5bcf98_p256_outputs, builder6388f0_caller_context_from_bundle,
    builder6388f0_caller_context_from_loop_tables, builder6388f0_caller_loop_tables_from_bundle,
    builder6388f0_caller_stream_u64, builder6388f0_caller_stream_u64_first_nibble_before_add,
    builder6388f0_convolution44, builder6388f0_first_pair642f60_stream_starts,
    builder6388f0_first_pair642f60_stream_starts_from_seeds,
    builder6388f0_first_pair_high_seed_stream_start_seeds_from5bcf98_outputs,
    builder6388f0_first_pair_stream_seeds_from5bcf98_outputs,
    builder6388f0_high_seed_stream_start_seeds_from5bcf98_outputs,
    builder6388f0_high_seed_stream_start_seeds_from_scalar_p256,
    builder6388f0_high_seed_x0_source_from5bcf98_output,
    builder6388f0_next642f60_inputs_from64cd40_outputs,
    builder6388f0_recover_stream_start_out0_seed_from642f60_x0,
    builder6388f0_recover_stream_start_out1_seed_from642f60_x1,
    builder6388f0_shared_context_from_bundle, builder6388f0_stream_start642f60_inputs,
    builder6388f0_stream_start642f60_x0_from_out0_seed,
    builder6388f0_stream_start642f60_x1_from_out1_seed, builder6421c0_convolution_workspace,
    builder6421c0_final_u32_words, builder6421c0_output_words, builder6421c0_workspace_after_update,
    builder6421c0_x0_streams, builder6421c0_x1_streams, builder6421c0_x2_words,
    Builder5bcf98P256Outputs, Builder6388f0CallerLoopTables,
    Builder6388f0Convolution44Constants, Builder6388f0FirstPair642f60Starts,
    Builder6388f0FirstPairHighSeedStreamStartSeeds, Builder6388f0FirstPairStreamSeeds,
    Builder6388f0HighSeedStreamStartSeeds, Builder6388f0Next642f60Inputs,
    HIGH_SEED_6421C0_SCALAR, HIGH_SEED_6421C0_X1_SOURCE, HIGH_SEED_6421C0_X2_SOURCE,
    STREAM_START_642F60_X2_SOURCE,
};
pub use lowseed::{
    builder633fa8_e10_words_from_tail_qwords, builder633fa8_null_entry_sources_from_invariant_entry,
    builder633fa8_null_first_loop_from_blocks, builder633fa8_null_initial_from_entropy,
    builder633fa8_null_post_accept_blocks, builder633fa8_null_prelude_source_from_entropy,
    builder633fa8_null_prelude_source_from_post_accept, builder633fa8_null_public_entry_source_from_entropy,
    builder633fa8_null_scalar_window_from_entropy, builder633fa8_null_scalar_window_from_entropy_source,
    builder633fa8_null_schedule_acceptance, builder633fa8_scalar_window_from_e10_words,
    builder633fa8_scalar_window_from_prelude_source, builder633fa8_static_prelude_source_from_entry_source,
    builder633fa8_static_scalar_window_from_entry_source, builder633fa8_static_tail_boundary_from_entry_source,
    builder633fa8_tail_boundary_from_prelude_source, builder633fa8_tail_qwords_from_sources,
    builder6388f0_low_seed_blocks_from_prelude_source, builder6388f0_low_seed_cf0_seeds_from_entry_source,
    builder6388f0_low_seed_loop_from_blocks, builder6388f0_low_seed_prelude_source_from_tail_stage,
    builder6388f0_low_seed_tail_pair_from_entry_source, builder6388f0_low_seed_tail_stage_from_pair,
    builder6388f0_row0_low_seed_preimages_from_entry_source, BUILDER633FA8_INVARIANT_SEED_3110,
    BUILDER633FA8_INVARIANT_WORDS_2DFC, BUILDER633FA8_INVARIANT_WORDS_3120,
    BUILDER6388F0_LOW_SEED_ENTRY_SOURCE, Builder633fa8NullEntrySources, Builder633fa8NullFirstLoopResult,
    Builder633fa8NullInitialResult, Builder633fa8NullPostAcceptResult, Builder633fa8NullScalarResult,
    Builder633fa8NullScheduleAcceptance, Builder633fa8TailBoundary, Builder6388f0LowSeedCF0Seeds,
    Builder6388f0LowSeedLoopResult, Builder6388f0LowSeedTailPair, Builder6388f0Row0LowSeedPreimages,
    Builder6473d0OutputPreimages,
};
pub use process2::{
    builder_process2_p5_public_key65_from_entropy, builder_process2_p5_public_scalar_window_from_entropy,
};
pub use seeded64::{
    builder6388f0_call64_call, builder6388f0_first64cd40_call_state,
    builder6388f0_first_pair_stream_seeds_from_entropy_and5bcf98_outputs,
    builder6388f0_first_pair_stream_seeds_from_entropy_and_sensor_points,
    builder6388f0_first_pair_stream_seeds_from_entropy_source_and5bcf98_outputs,
    builder6388f0_first_pair_stream_seeds_from_entropy_source_and_sensor_points,
    builder6388f0_first_pair_stream_seeds_from_entry_source_and5bcf98_outputs,
    builder6388f0_first_pair_stream_seeds_from_scalars_and_sensor_points,
    builder6388f0_second64cd40_call_state, builder6388f0_seeded63c278_schedules_from_rows,
    builder6388f0_seeded63c278_schedules_from_rows_arg0_scalar, builder6388f0_seeded_caller64_row,
    builder6388f0_seeded_caller64_rows, builder6388f0_seeded_caller64_rows_from_first_pair_stream_seeds,
    builder6388f0_third64cd40_call_state,
    derive_from_6388f0_first_pair_entropy_and_sensor_points,
    derive_from_6388f0_first_pair_entropy_source_and_sensor_points,
    derive_from_6388f0_first_pair_stream_seeds, derive_from_6388f0_first_pair_stream_seeds_arg0_scalar,
    derive_from_6388f0_seeded_caller64_rows, derive_from_6388f0_seeded_caller64_rows_arg0_scalar,
    phase5_raw_key_from_6388f0_first_pair_entropy_and_sensor_points,
    phase5_raw_key_from_6388f0_first_pair_stream_seeds, phase5_raw_key_from_6388f0_seeded_caller64_rows,
    BUILDER6388F0_FIRST_PAIR_STREAM_ROWS, Builder6388f0Caller64Call, Builder6388f0Caller64CallState,
    Builder6388f0Seeded63c278Schedules, Builder6388f0Seeded63c278Stream,
    Builder6388f0SeededCaller64Row,
};
pub use slice::{
    apply67dd7c_update_until_df80, apply67eb94_pending_blocks, apply67eb94_with_pending_raw_adapter,
    constructor670978_ptr28_blocks, constructor670a54_ptr10_blocks, df80_compress_state, df80_expanded_schedule,
    df80_initial_workspace, df80_transform, derive64de54_slice, derive_from_660448_raw_descriptor,
    derive_from_67a960_inputs, derive_from_67a978_source, derive_from_67a990_source, derive_from_67cc18_sources,
    derive_from_679f48_context, derive_from_679f48_inputs, derive_from_finalized679f48_context,
    final679f48_length_block, finalized679f48_context_from_inputs, finalize679f48_to_second_df80, init679f48_context,
    phase5_raw_key_from_67a960_inputs, phase5_raw_key_from_67cc18_sources,
    phase5_raw_key_from_64de54_encoded_blocks, phase5_raw_key_from_finalized679f48_context,
    previous_descriptor_blocks_to_dd7c_inputs, update67aa8c_len4_initial,
};
pub use tables::FirstPairTables;