//! PLAN_T1DMDROID.md §5.5/§5.6: AES-128-CCM (NIST SP 800-38C) with the AES block primitive
//! supplied as a closure. Two users: standard AES under a session key, and the white-box AES
//! (`cipherfn`) on the KAuth paths. Tag length configurable; Libre 3 Phase 5/6 ground truth
//! uses M=4.

use crate::CryptoError;

/// AES block primitive: one 16-byte block in, encrypted block out.
pub type Block<'k> = dyn Fn(&[u8; 16]) -> Result<[u8; 16], CryptoError> + 'k;

/// Returns (ciphertext, tag).
pub fn encrypt(
    nonce: &[u8],
    plaintext: &[u8],
    aad: &[u8],
    tag_len: usize,
    aes: &Block<'_>,
) -> Result<(Vec<u8>, Vec<u8>), CryptoError> {
    check_parameters(nonce, tag_len, plaintext.len())?;
    let mac = cbc_mac(nonce, aad, plaintext, tag_len, aes)?;
    let (ct, s0) = ctr_apply(plaintext, nonce, aes)?;
    let tag = xor_prefix(&mac, &s0, tag_len);
    Ok((ct, tag))
}

/// Verifies and returns the plaintext, or [CryptoError::MacMismatch].
pub fn decrypt(
    nonce: &[u8],
    ciphertext: &[u8],
    tag: &[u8],
    aad: &[u8],
    aes: &Block<'_>,
) -> Result<Vec<u8>, CryptoError> {
    check_parameters(nonce, tag.len(), ciphertext.len())?;
    let (pt, s0) = ctr_apply(ciphertext, nonce, aes)?;
    let mac = cbc_mac(nonce, aad, &pt, tag.len(), aes)?;
    let expected = xor_prefix(&mac, &s0, tag.len());
    if !constant_time_equal(&expected, tag) {
        return Err(CryptoError::MacMismatch);
    }
    Ok(pt)
}

/// CBC-MAC over B_0 || formatted_AAD || zero-padded plaintext per SP 800-38C.
fn cbc_mac(
    nonce: &[u8],
    aad: &[u8],
    plaintext: &[u8],
    tag_len: usize,
    aes: &Block<'_>,
) -> Result<[u8; 16], CryptoError> {
    let mut b = format_header(nonce, aad, plaintext.len(), tag_len);
    b.extend_from_slice(plaintext);
    if b.len() % 16 != 0 {
        b.resize(b.len() + (16 - b.len() % 16), 0);
    }
    let mut y = [0u8; 16];
    for block in b.chunks_exact(16) {
        let mut input = [0u8; 16];
        input.copy_from_slice(block);
        for i in 0..16 {
            input[i] ^= y[i];
        }
        y = aes(&input)?;
    }
    Ok(y)
}

fn check_parameters(nonce: &[u8], tag_len: usize, plaintext_len: usize) -> Result<(), CryptoError> {
    // L = 15 - len(nonce) must be in [2, 8].
    let l = 15usize.checked_sub(nonce.len()).ok_or(CryptoError::InvalidParameters)?;
    if !(2..=8).contains(&l) {
        return Err(CryptoError::InvalidParameters);
    }
    if !matches!(tag_len, 4 | 6 | 8 | 10 | 12 | 14 | 16) {
        return Err(CryptoError::InvalidParameters);
    }
    if l < 8 {
        let max = (1u64 << (l * 8)) - 1;
        if plaintext_len as u64 > max {
            return Err(CryptoError::InvalidParameters);
        }
    }
    Ok(())
}

/// B_0 || formatted_AAD (zero-padded to a 16-byte boundary).
fn format_header(nonce: &[u8], aad: &[u8], plaintext_len: usize, tag_len: usize) -> Vec<u8> {
    let l = 15 - nonce.len();
    let mut b0 = [0u8; 16];
    let a_flag: u8 = if aad.is_empty() { 0 } else { 0x40 };
    let m = ((tag_len - 2) / 2) as u8;
    b0[0] = a_flag | (m << 3) | ((l - 1) as u8);
    b0[1..1 + nonce.len()].copy_from_slice(nonce);
    // Q (plaintext length) as L-byte big-endian in the last L bytes.
    let mut q = plaintext_len as u64;
    for i in 0..l {
        b0[15 - i] = (q & 0xff) as u8;
        q >>= 8;
    }
    let mut out = b0.to_vec();
    if !aad.is_empty() {
        // AAD length encoding per SP 800-38C §A.2.2.
        let a = aad.len();
        if a < 0xFF00 {
            out.extend_from_slice(&[(a >> 8) as u8, a as u8]);
        } else {
            out.extend_from_slice(&[0xFF, 0xFE]);
            out.extend_from_slice(&(a as u32).to_be_bytes());
        }
        out.extend_from_slice(aad);
        if out.len() % 16 != 0 {
            out.resize(out.len() + (16 - out.len() % 16), 0);
        }
    }
    out
}

/// CTR mode: returns (output, S_0) where S_0 = AES(A_0).
fn ctr_apply(
    input: &[u8],
    nonce: &[u8],
    aes: &Block<'_>,
) -> Result<(Vec<u8>, [u8; 16]), CryptoError> {
    let l = 15 - nonce.len();
    // A_i = flags(L-1) || nonce || ctr_BE(L)
    let mut a = [0u8; 16];
    a[0] = (l - 1) as u8;
    a[1..1 + nonce.len()].copy_from_slice(nonce);
    let mut s0_block = a;
    for byte in &mut s0_block[16 - l..] {
        *byte = 0;
    }
    let s0 = aes(&s0_block)?;
    let mut out = Vec::with_capacity(input.len());
    let mut ctr: u64 = 1;
    for chunk in input.chunks(16) {
        let mut ai = a;
        let mut c = ctr;
        for j in 0..l {
            ai[15 - j] = (c & 0xff) as u8;
            c >>= 8;
        }
        let s = aes(&ai)?;
        for (k, byte) in chunk.iter().enumerate() {
            out.push(byte ^ s[k]);
        }
        ctr = ctr.wrapping_add(1);
    }
    Ok((out, s0))
}

fn xor_prefix(a: &[u8], b: &[u8], n: usize) -> Vec<u8> {
    (0..n).map(|i| a[i] ^ b[i]).collect()
}

fn constant_time_equal(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b.iter()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}