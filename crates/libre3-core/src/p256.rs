//! PLAN_T1DMDROID.md §5.3: the first-pair P-256 scalar multiplier, ported 1:1 from the kit's
//! P256ScalarMultiplier.swift. Scalars are little-endian byte windows (the white-box VM's
//! convention), points are 32-byte big-endian X||Y affine pairs validated on the curve, and
//! outputs are the coordinates padded little-endian to 70 bytes. Deliberately NOT the `p256`
//! crate: the scalar length and padding are the VM builder's contract.

use crate::CryptoError;

/// P-256 field element as four u64 limbs, little-endian limb order.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Field(pub [u64; 4]);

const ZERO: Field = Field([0, 0, 0, 0]);
const ONE: Field = Field([1, 0, 0, 0]);
/// P-256 prime, little-endian limbs.
const MODULUS: Field = Field([
    0xffff_ffff_ffff_ffff,
    0x0000_0000_ffff_ffff,
    0x0000_0000_0000_0000,
    0xffff_ffff_0000_0001,
]);
/// 2^256 - 2^224 + 2^192 + 2^96 - 1, for carry wrap-around above the modulus.
const CARRY_CORRECTION: Field = Field([
    0x0000_0000_0000_0001,
    0xffff_ffff_0000_0000,
    0xffff_ffff_ffff_ffff,
    0x0000_0000_ffff_fffe,
]);
/// Curve b = y² − x³ + 3x.
const CURVE_B: Field = Field([
    0x3bce_3c3e_27d2_604b,
    0x651d_06b0_cc53_b0f6,
    0xb3eb_bd55_7698_86bc,
    0x5ac6_35d8_aa3a_93e7,
]);

impl Field {
    pub fn from_be32(be: &[u8]) -> Result<Field, CryptoError> {
        if be.len() != 32 {
            return Err(CryptoError::InvalidP256Point);
        }
        Ok(Field([
            u64::from_be_bytes(be[24..32].try_into().unwrap()),
            u64::from_be_bytes(be[16..24].try_into().unwrap()),
            u64::from_be_bytes(be[8..16].try_into().unwrap()),
            u64::from_be_bytes(be[0..8].try_into().unwrap()),
        ]))
    }

    /// Little-endian 70 bytes: 4 limbs LE then 38 zero bytes.
    pub fn little_endian_padded_70(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(70);
        for limb in self.0 {
            out.extend_from_slice(&limb.to_le_bytes());
        }
        out.extend(std::iter::repeat(0u8).take(38));
        out
    }

    fn squared(self) -> Field {
        self * self
    }

    fn doubled(self) -> Field {
        self + self
    }

    fn times3(self) -> Field {
        self.doubled() + self
    }

    fn times4(self) -> Field {
        self.doubled().doubled()
    }

    fn times8(self) -> Field {
        self.times4().doubled()
    }

    fn inverted(&self) -> Field {
        let exponent = Field([
            0xffff_ffff_ffff_fffd,
            0x0000_0000_ffff_ffff,
            0x0000_0000_0000_0000,
            0xffff_ffff_0000_0001,
        ]);
        let mut result = ONE;
        for bit in (0..256).rev() {
            result = result.squared();
            if exponent.bit(bit) {
                result = result * *self;
            }
        }
        result
    }

    fn bit(&self, bit: usize) -> bool {
        (self.0[bit / 64] >> (bit % 64)) & 1 != 0
    }

    fn add_raw(lhs: Field, rhs: Field) -> (Field, bool) {
        let mut out = [0u64; 4];
        let mut carry = false;
        for i in 0..4 {
            let (s1, o1) = lhs.0[i].overflowing_add(rhs.0[i]);
            let (s2, o2) = s1.overflowing_add(carry as u64);
            out[i] = s2;
            carry = o1 || o2;
        }
        (Field(out), carry)
    }

    fn sub_raw(lhs: Field, rhs: Field) -> Field {
        let mut out = [0u64; 4];
        let mut borrow = false;
        for i in 0..4 {
            let (d1, b1) = lhs.0[i].overflowing_sub(rhs.0[i]);
            let (d2, b2) = d1.overflowing_sub(borrow as u64);
            out[i] = d2;
            borrow = b1 || b2;
        }
        Field(out)
    }

    fn ge(lhs: &Field, rhs: &Field) -> bool {
        for i in (0..4).rev() {
            match lhs.0[i].cmp(&rhs.0[i]) {
                std::cmp::Ordering::Less => return false,
                std::cmp::Ordering::Greater => return true,
                std::cmp::Ordering::Equal => {}
            }
        }
        true
    }

    fn add_product(product: &mut [u64; 8], index: usize, lhs: u64, rhs: u64) {
        let full = (lhs as u128) * (rhs as u128);
        let low = full as u64;
        let high = (full >> 64) as u64;
        // A u128 product's high half is at most 2^64-2, so the carry chain stays in-window.
        let mut carry = Self::add_word(product, index, low) as u64;
        carry = Self::add_word(product, index + 1, high.wrapping_add(carry)) as u64;
        let mut carry_index = index + 2;
        while carry != 0 {
            carry = Self::add_word(product, carry_index, carry) as u64;
            carry_index += 1;
        }
    }

    fn add_word(limbs: &mut [u64; 8], index: usize, word: u64) -> bool {
        let (v, overflow) = limbs[index].overflowing_add(word);
        limbs[index] = v;
        overflow
    }

    fn reduce(limbs: &[u64; 8]) -> Field {
        let mut remainder = ZERO;
        for bit in (0..limbs.len() * 64).rev() {
            remainder = shift_append_bit_mod_p(remainder, bit_set(limbs, bit));
        }
        remainder
    }
}

impl std::ops::Add for Field {
    type Output = Field;
    fn add(self, rhs: Field) -> Field {
        let (sum, carry) = Field::add_raw(self, rhs);
        if carry {
            let (corrected, _) = Field::add_raw(sum, CARRY_CORRECTION);
            if Field::ge(&corrected, &MODULUS) {
                return Field::sub_raw(corrected, MODULUS);
            }
            return corrected;
        }
        if Field::ge(&sum, &MODULUS) {
            return Field::sub_raw(sum, MODULUS);
        }
        sum
    }
}

impl std::ops::Sub for Field {
    type Output = Field;
    fn sub(self, rhs: Field) -> Field {
        if Field::ge(&self, &rhs) {
            return Field::sub_raw(self, rhs);
        }
        let diff = Field::sub_raw(rhs, self);
        Field::sub_raw(MODULUS, diff)
    }
}

impl std::ops::Mul for Field {
    type Output = Field;
    fn mul(self, rhs: Field) -> Field {
        let mut product = [0u64; 8];
        for i in 0..4 {
            for j in 0..4 {
                Field::add_product(&mut product, i + j, self.0[i], rhs.0[j]);
            }
        }
        Field::reduce(&product)
    }
}

fn shift_append_bit_mod_p(value: Field, bit: bool) -> Field {
    let mut out = [0u64; 4];
    let mut carry: u64 = bit as u64;
    for i in 0..4 {
        let next_carry = value.0[i] >> 63;
        out[i] = (value.0[i] << 1) | carry;
        carry = next_carry;
    }
    let mut shifted = Field(out);
    if carry != 0 {
        let (corrected, _) = Field::add_raw(shifted, CARRY_CORRECTION);
        shifted = corrected;
    } else if Field::ge(&shifted, &MODULUS) {
        shifted = Field::sub_raw(shifted, MODULUS);
    }
    if Field::ge(&shifted, &MODULUS) {
        shifted = Field::sub_raw(shifted, MODULUS);
    }
    shifted
}

fn bit_set(limbs: &[u64; 8], bit: usize) -> bool {
    (limbs[bit / 64] >> (bit % 64)) & 1 != 0
}

/// Affine point validated on P-256: y² == x³ − 3x + b.
#[derive(Clone, Copy, Debug)]
pub struct AffinePoint {
    pub x: Field,
    pub y: Field,
}

impl AffinePoint {
    /// X and Y as 32-byte big-endian arrays.
    pub fn from_be32(x_be: &[u8], y_be: &[u8]) -> Result<AffinePoint, CryptoError> {
        let x = Field::from_be32(x_be)?;
        let y = Field::from_be32(y_be)?;
        let rhs = x.squared() * x - x.times3() + CURVE_B;
        if y.squared() != rhs {
            return Err(CryptoError::InvalidP256Point);
        }
        Ok(AffinePoint { x, y })
    }
}

#[derive(Clone, Copy)]
struct JacobianPoint {
    x: Field,
    y: Field,
    z: Field,
    infinity: bool,
}

impl JacobianPoint {
    const INFINITY: JacobianPoint = JacobianPoint {
        x: ZERO,
        y: ONE,
        z: ZERO,
        infinity: true,
    };

    fn from_affine(affine: &AffinePoint) -> JacobianPoint {
        JacobianPoint {
            x: affine.x,
            y: affine.y,
            z: ONE,
            infinity: false,
        }
    }

    fn double(&self) -> JacobianPoint {
        if self.infinity || self.y == ZERO {
            return JacobianPoint::INFINITY;
        }
        let yy = self.y.squared();
        let yyyy = yy.squared();
        let zz = self.z.squared();
        let zzzz = zz.squared();
        let s = (self.x * yy).times4();
        let m = (self.x.squared() - zzzz).times3();
        let x3 = m.squared() - s.doubled();
        let y3 = m * (s - x3) - yyyy.times8();
        let z3 = self.y.doubled() * self.z;
        JacobianPoint {
            x: x3,
            y: y3,
            z: z3,
            infinity: false,
        }
    }

    fn add_mixed(&self, addend: &AffinePoint) -> JacobianPoint {
        if self.infinity {
            return JacobianPoint::from_affine(addend);
        }
        let z1z1 = self.z.squared();
        let u2 = addend.x * z1z1;
        let s2 = addend.y * self.z * z1z1;
        let h = u2 - self.x;
        if h == ZERO {
            return if s2 == self.y {
                self.double()
            } else {
                JacobianPoint::INFINITY
            };
        }
        let hh = h.squared();
        let i = hh.times4();
        let j = h * i;
        let r = (s2 - self.y).doubled();
        let v = self.x * i;
        let x3 = r.squared() - j - v.doubled();
        let y3 = r * (v - x3) - (self.y * j).doubled();
        let z3 = (self.z + h).squared() - z1z1 - hh;
        JacobianPoint {
            x: x3,
            y: y3,
            z: z3,
            infinity: false,
        }
    }

    fn affine(&self) -> Result<AffinePoint, CryptoError> {
        if self.infinity {
            return Err(CryptoError::InvalidP256Point);
        }
        let z_inv = self.z.inverted();
        let z_inv2 = z_inv.squared();
        let z_inv3 = z_inv2 * z_inv;
        Ok(AffinePoint {
            x: self.x * z_inv2,
            y: self.y * z_inv3,
        })
    }
}

/// Bit-by-bit double-and-add over a little-endian scalar window (§5.3 first-pair contract).
pub fn multiply(scalar_le: &[u8], point: &AffinePoint) -> Result<AffinePoint, CryptoError> {
    let Some(top_bit) = highest_set_bit(scalar_le) else {
        return Err(CryptoError::InvalidP256Point);
    };
    let mut result = JacobianPoint::INFINITY;
    for bit in (0..=top_bit).rev() {
        result = result.double();
        if scalar_bit(scalar_le, bit) {
            result = result.add_mixed(point);
        }
    }
    result.affine()
}

fn highest_set_bit(scalar_le: &[u8]) -> Option<usize> {
    for byte_index in (0..scalar_le.len()).rev() {
        let byte = scalar_le[byte_index];
        if byte == 0 {
            continue;
        }
        for bit in (0..8).rev() {
            if byte & (1 << bit) != 0 {
                return Some(byte_index * 8 + bit);
            }
        }
    }
    None
}

fn scalar_bit(scalar_le: &[u8], bit: usize) -> bool {
    (scalar_le[bit / 8] >> (bit % 8)) & 1 != 0
}

/// §5.3 builder helper: scalar window (≥70 LE bytes) × sensor point (X||Y big-endian), outputs
/// as the VM's little-endian-padded-70 form.
pub fn multiply_padded_70(
    scalar_window_le: &[u8],
    sensor_point_xy_be: &[u8],
) -> Result<(Vec<u8>, Vec<u8>), CryptoError> {
    if scalar_window_le.len() < 70 {
        return Err(CryptoError::InvalidP256Point);
    }
    if sensor_point_xy_be.len() < 64 {
        return Err(CryptoError::InvalidP256Point);
    }
    let point = AffinePoint::from_be32(&sensor_point_xy_be[..32], &sensor_point_xy_be[32..64])?;
    let product = multiply(&scalar_window_le[..70], &point)?;
    Ok((
        product.x.little_endian_padded_70(),
        product.y.little_endian_padded_70(),
    ))
}