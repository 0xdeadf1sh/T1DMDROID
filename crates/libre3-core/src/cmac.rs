//! RFC 4493 AES-CMAC, generic over the same injected block primitive as [crate::ccm].

use crate::ccm::Block;
use crate::CryptoError;
/// L = AES_K(0), K1 = double(L), K2 = double(K1).
pub fn subkeys(aes: &Block<'_>) -> Result<([u8; 16], [u8; 16], [u8; 16]), CryptoError> {
    let zero = [0u8; 16];
    let l = aes(&zero)?;
    let k1 = gf128_double(&l);
    let k2 = gf128_double(&k1);
    Ok((l, k1, k2))
}

/// RFC 4493 AES-CMAC over `message`.
pub fn mac(message: &[u8], aes: &Block<'_>) -> Result<[u8; 16], CryptoError> {
    let (_, k1, k2) = subkeys(aes)?;
    let mut last_block = [0u8; 16];
    let mut last_xor = k1;
    let n;
    if message.is_empty() {
        n = 1;
        last_block[0] = 0x80;
        last_xor = k2;
    } else {
        n = message.len().div_ceil(16);
        let last_start = (n - 1) * 16;
        let last = &message[last_start..];
        if last.len() == 16 {
            last_block.copy_from_slice(last);
            last_xor = k1;
        } else {
            last_block[..last.len()].copy_from_slice(last);
            last_block[last.len()] = 0x80;
            last_xor = k2;
        }
    }
    for i in 0..16 {
        last_block[i] ^= last_xor[i];
    }
    let mut x = [0u8; 16];
    for block in message.chunks_exact(16).take(n - 1) {
        let mut y = x;
        for (out, b) in y.iter_mut().zip(block) {
            *out ^= b;
        }
        x = aes(&y)?;
    }
    let mut y = x;
    for (out, b) in y.iter_mut().zip(&last_block) {
        *out ^= b;
    }
    aes(&y)
}

/// RFC 4493 GF(2^128) doubling with reduction polynomial 0x87.
pub fn gf128_double(input: &[u8; 16]) -> [u8; 16] {
    let mut out = [0u8; 16];
    let mut carry = 0u8;
    for i in (0..16).rev() {
        let b = input[i];
        out[i] = ((b << 1) & 0xff) | carry;
        carry = (b & 0x80) >> 7;
    }
    if input[0] & 0x80 != 0 {
        out[15] ^= 0x87;
    }
    out
}