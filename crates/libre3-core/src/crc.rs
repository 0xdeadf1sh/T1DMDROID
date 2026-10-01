//! PLAN_T1DMDROID.md §5.9: poly 0x1021, init 0xffff, bit-reversed input bytes, LE output.

fn bitrev8(mut b: u8) -> u8 {
    b = (b << 4) | (b >> 4);
    b = ((b & 0x33) << 2) | ((b >> 2) & 0x33);
    ((b & 0x55) << 1) | ((b >> 1) & 0x55)
}

/// MSB-first register fed the bit-reversal of each message byte; no final xor, no reflection.
pub fn crc16_nfc(msg: &[u8]) -> u16 {
    let mut crc: u16 = 0xffff;
    for &b in msg {
        crc ^= (bitrev8(b) as u16) << 8;
        for _ in 0..8 {
            crc = if crc & 0x8000 != 0 { (crc << 1) ^ 0x1021 } else { crc << 1 };
        }
    }
    crc
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_byte_matches_ccitt_false() {
        // bitrev(0x00) = 0x00, so the scheme degenerates to plain CCITT-FALSE on a zero.
        assert_eq!(crc16_nfc(&[0x00]), 0xe1f0);
    }

    #[test]
    fn one_byte_exercises_the_reversal() {
        // Hand-computed: bitrev(0x01) = 0x80 into an 0xffff-seeded MSB-first register.
        assert_eq!(crc16_nfc(&[0x01]), 0x7078);
    }
}
