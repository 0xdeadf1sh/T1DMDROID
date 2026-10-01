//! PLAN_T1DMDROID.md §5.3: the first-pair Phase 5 session-key surfaces, ported 1:1 from the
//! kit's SessionKey.swift. The Phase 5 key source composes the accepted null entropy (633fa8),
//! the bundled entry source, the Phase 4 sensor ephemeral point, and the sensor certificate's
//! static point; the raw key is the white-box 66→16 schedule (phase5). The kit's older
//! ECDH-only `SessionKey.derive` injection point is deliberately absent — it always threw
//! `.notYetSpecified`. All fail-closed.

use crate::ephemeral::EphemeralKeyPair;
use crate::phase5::{derive_raw_key, ScheduleTables};
use crate::vm::lowseed::{builder633fa8_null_scalar_window_from_entropy,
    builder633fa8_null_scalar_window_from_entropy_source, builder633fa8_static_scalar_window_from_entry_source,
    BUILDER6388F0_LOW_SEED_ENTRY_SOURCE};
use crate::vm::process2::builder_process2_p5_public_key65_from_entropy;
use crate::vm::seeded64::{builder6388f0_first_pair_stream_seeds_from_scalars_and_sensor_points,
    derive_from_6388f0_first_pair_stream_seeds};
use crate::vm::FirstPairTables;
use crate::CryptoError;

/// The bundled entry source (kit `FirstPairSourceSlice.bundled6388f0LowSeedEntrySource`,
/// SHA-256 263e4b14…; SessionKeyTests.swift L35-42).
pub const BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE: [u8; 0x214] = BUILDER6388F0_LOW_SEED_ENTRY_SOURCE;

/// Kit `FirstPairPhase5KeyInputs` (SessionKey.swift L50-77). `entry_source` is explicit in
/// Rust; the bundled default is `BUNDLED_6388F0_LOW_SEED_ENTRY_SOURCE`.
pub struct FirstPairPhase5KeyInputs {
    pub entry_source: Vec<u8>,
    /// Accepted 282-byte entropy block for the null scalar branch.
    pub null_entropy_11a: Vec<u8>,
    /// Sensor ephemeral P-256 point from Phase 4, `04 || X || Y`.
    pub sensor_ephemeral_pub65: Vec<u8>,
    /// Sensor static P-256 point from the certificate, `04 || X || Y`.
    pub sensor_static_pub65: Vec<u8>,
    /// Optional native 70-byte static-scalar window for cert families whose Phase 5 static
    /// branch is not the bundled entry-source-derived default (`03 03` phone certs carry it).
    pub static_scalar_window: Option<Vec<u8>>,
}

/// Kit `FirstPairPhase5KeyMaterial` (SessionKey.swift L79-99).
#[derive(Debug)]
pub struct FirstPairPhase5KeyMaterial {
    pub source66: Vec<u8>,
    pub raw_key: [u8; 16],
    pub null_entropy_11a: Vec<u8>,
    pub null_scalar_window: Vec<u8>,
    pub null_attempts: usize,
}

/// Kit `FirstPairNativeEphemeralMaterial` (SessionKey.swift L101-118).
pub struct FirstPairNativeEphemeralMaterial {
    pub key_pair: EphemeralKeyPair,
    pub null_entropy_11a: Vec<u8>,
    pub null_scalar_window: Vec<u8>,
    pub attempts: usize,
}

/// Kit `SessionKey.uncompressedPointXYBE` (SessionKey.swift L324-333): 65-byte `04 || X || Y`,
/// XY as big-endian; anything else is `invalidSensorPointEncoding`.
fn uncompressed_point_xy_be<'a>(point65: &'a [u8], label: &str) -> Result<&'a [u8], CryptoError> {
    if point65.len() != 65 || point65.first() != Some(&0x04) {
        return Err(CryptoError::Slice {
            reason: format!(
                "invalidSensorPointEncoding(label: {}, count: {}, prefix: {})",
                label,
                point65.len(),
                point65.first().map_or("nil".to_owned(), |b| format!("{b}")),
            ),
        });
    }
    Ok(&point65[1..])
}

/// Kit `SessionKey.deriveFirstPairPhase5Material(_:)` (SessionKey.swift L176-206): row 0 uses
/// the Phase 4 sensor ephemeral point; row 59 the sensor certificate's static public point.
pub fn derive_first_pair_phase5_material(
    inputs: &FirstPairPhase5KeyInputs,
    t: &FirstPairTables,
    sched: &ScheduleTables,
) -> Result<FirstPairPhase5KeyMaterial, CryptoError> {
    let row0_point = uncompressed_point_xy_be(&inputs.sensor_ephemeral_pub65, "sensor ephemeral")?;
    let row59_point = uncompressed_point_xy_be(&inputs.sensor_static_pub65, "sensor static")?;
    let null_scalar =
        builder633fa8_null_scalar_window_from_entropy(&inputs.null_entropy_11a, t)?;
    let static_scalar = match &inputs.static_scalar_window {
        Some(override70) => override70.clone(),
        None => builder633fa8_static_scalar_window_from_entry_source(&inputs.entry_source, t)?,
    };
    let seeds = builder6388f0_first_pair_stream_seeds_from_scalars_and_sensor_points(
        &inputs.entry_source,
        &null_scalar,
        &static_scalar,
        row0_point,
        row59_point,
        &inputs.null_entropy_11a,
        1,
        None,
        None,
        None,
        t,
    )?;
    material_from_seeds(&seeds, t, sched)
}

/// Kit `SessionKey.deriveFirstPairPhase5Source(_:)` (SessionKey.swift L172-174).
pub fn derive_first_pair_phase5_source(
    inputs: &FirstPairPhase5KeyInputs,
    t: &FirstPairTables,
    sched: &ScheduleTables,
) -> Result<Vec<u8>, CryptoError> {
    Ok(derive_first_pair_phase5_material(inputs, t, sched)?.source66)
}

/// Kit `SessionKey.deriveFirstPairPhase5RawKey(_:)` (SessionKey.swift L226-228).
pub fn derive_first_pair_phase5_raw_key(
    inputs: &FirstPairPhase5KeyInputs,
    t: &FirstPairTables,
    sched: &ScheduleTables,
) -> Result<[u8; 16], CryptoError> {
    Ok(derive_first_pair_phase5_material(inputs, t, sched)?.raw_key)
}

/// Kit `SessionKey.deriveFirstPairPhase5Material(entrySource:sensorEphemeralPub65:…:maxAttempts:
/// entropySource:)` (SessionKey.swift L263-304): with a static-scalar override the null
/// window comes from the entropy source; without, the whole path runs from the entropy source
/// (`builder6388f0FirstPairStreamSeedsFromEntropySourceAndSensorPoints`).
pub fn derive_first_pair_phase5_material_from_entropy_source<F>(
    entry_source: &[u8],
    sensor_ephemeral_pub65: &[u8],
    sensor_static_pub65: &[u8],
    static_scalar_window: Option<&[u8]>,
    max_attempts: usize,
    entropy_source: F,
    t: &FirstPairTables,
    sched: &ScheduleTables,
) -> Result<FirstPairPhase5KeyMaterial, CryptoError>
where
    F: FnMut(usize) -> Result<Vec<u8>, CryptoError>,
{
    let row0_point = uncompressed_point_xy_be(sensor_ephemeral_pub65, "sensor ephemeral")?;
    let row59_point = uncompressed_point_xy_be(sensor_static_pub65, "sensor static")?;
    let seeds = if let Some(override70) = static_scalar_window {
        let null_result = builder633fa8_null_scalar_window_from_entropy_source(
            max_attempts,
            entropy_source,
            t,
        )?;
        builder6388f0_first_pair_stream_seeds_from_scalars_and_sensor_points(
            entry_source,
            &null_result.scalar_window,
            override70,
            row0_point,
            row59_point,
            &null_result.entropy11a,
            null_result.attempts,
            None,
            None,
            None,
            t,
        )?
    } else {
        crate::vm::seeded64::builder6388f0_first_pair_stream_seeds_from_entropy_source_and_sensor_points(
            entry_source,
            row0_point,
            row59_point,
            max_attempts,
            None,
            None,
            None,
            entropy_source,
            t,
        )?
    };
    material_from_seeds(&seeds, t, sched)
}

/// Kit `SessionKey.makeFirstPairNativeEphemeral(maxAttempts:entropySource:)` (SessionKey.swift
/// L134-155): sample the accepted null-branch entropy, derive the native null scalar for
/// Phase 5, and send the `process2(5)` fixed-point public point derived from that same
/// entropy. The entropy coupling is the §5.3 constraint: a random ephemeral never reaches a
/// verified Phase 6.
pub fn make_first_pair_native_ephemeral<F>(
    max_attempts: usize,
    entropy_source: F,
    t: &FirstPairTables,
) -> Result<FirstPairNativeEphemeralMaterial, CryptoError>
where
    F: FnMut(usize) -> Result<Vec<u8>, CryptoError>,
{
    let result = builder633fa8_null_scalar_window_from_entropy_source(
        max_attempts,
        entropy_source,
        t,
    )?;
    let public_key65 = builder_process2_p5_public_key65_from_entropy(&result.entropy11a, t)?;
    let key_pair = EphemeralKeyPair::from_native_scalar_window_le_with_override(
        &result.scalar_window,
        &public_key65,
    )?;
    Ok(FirstPairNativeEphemeralMaterial {
        key_pair,
        null_entropy_11a: result.entropy11a,
        null_scalar_window: result.scalar_window,
        attempts: result.attempts,
    })
}

/// Kit `SessionKey.material(from:)` (SessionKey.swift L335-345).
fn material_from_seeds(
    seeds: &crate::vm::highseed::Builder6388f0FirstPairStreamSeeds,
    t: &FirstPairTables,
    sched: &ScheduleTables,
) -> Result<FirstPairPhase5KeyMaterial, CryptoError> {
    let source66 = derive_from_6388f0_first_pair_stream_seeds(seeds, &[0, 0, 0, 1], 0, 0x10, t)?;
    let raw_key = derive_raw_key(&source66, sched)?;
    Ok(FirstPairPhase5KeyMaterial {
        source66,
        raw_key,
        null_entropy_11a: seeds.null_entropy_11a.clone(),
        null_scalar_window: seeds.null_scalar_window.clone(),
        null_attempts: seeds.null_attempts,
    })
}