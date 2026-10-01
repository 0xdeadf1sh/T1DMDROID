//! Cross-check of the hand-rolled §5.3 P-256 scalar multiplier against the spec-verified
//! `p256` crate (test-only dependency): same scalar, same generator, same result. The generator
//! bytes come from the crate itself, so no memorized constant is trusted here.

use ::p256::elliptic_curve::{ops::MulByGenerator, sec1::ToEncodedPoint, Group};
use ::p256::{ProjectivePoint, Scalar};
use libre3_core::p256;

fn generator_xy_be() -> Vec<u8> {
    let point = ProjectivePoint::generator().to_affine().to_encoded_point(false);
    point.as_bytes()[1..65].to_vec()
}

fn limbs_to_be32(field: &p256::Field) -> Vec<u8> {
    let l = field.0;
    let mut out = Vec::with_capacity(32);
    out.extend_from_slice(&l[3].to_be_bytes());
    out.extend_from_slice(&l[2].to_be_bytes());
    out.extend_from_slice(&l[1].to_be_bytes());
    out.extend_from_slice(&l[0].to_be_bytes());
    out
}

fn crate_product_xy_be(k: u64) -> Vec<u8> {
    let point = ProjectivePoint::mul_by_generator(&Scalar::from(k))
        .to_affine()
        .to_encoded_point(false);
    point.as_bytes()[1..65].to_vec()
}

/// Scalar k = 0x014523 (little-endian window) × G, compared against the p256 crate.
#[test]
fn multiply_matches_p256_crate() {
    let gen = generator_xy_be();
    let point = p256::AffinePoint::from_be32(&gen[..32], &gen[32..]).unwrap();
    let mut window = [0u8; 70];
    window[0] = 0x23;
    window[1] = 0x45;
    window[2] = 0x01; // scalar LE = 0x014523
    let product = p256::multiply(&window, &point).unwrap();
    let expected = crate_product_xy_be(0x014523);
    assert_eq!(limbs_to_be32(&product.x), &expected[..32]);
    assert_eq!(limbs_to_be32(&product.y), &expected[32..]);
}

/// The padded-70 outputs carry the 32-byte coordinate little-endian, then 38 zeros.
#[test]
fn padded_70_output_contract() {
    let gen = generator_xy_be();
    let mut window = [0u8; 70];
    window[0] = 1;
    let (x70, y70) = p256::multiply_padded_70(&window, &gen).unwrap();
    let le32 = |be: &[u8]| -> Vec<u8> { be.iter().rev().copied().collect() };
    assert_eq!(&x70[..32], &le32(&gen[..32])[..]);
    assert_eq!(&y70[..32], &le32(&gen[32..])[..]);
    assert!(x70[32..].iter().all(|b| *b == 0));
    assert!(y70[32..].iter().all(|b| *b == 0));
}

/// Out-of-curve points and short windows are refused (y² == x³ − 3x + b is validated).
#[test]
fn off_curve_point_rejected() {
    let gen = generator_xy_be();
    assert!(p256::multiply_padded_70(&[0x01], &[0u8; 64]).is_err());
    assert!(p256::multiply_padded_70(&[0x01], &gen).is_err());
    assert!(p256::multiply_padded_70(&[], &gen).is_err());
}