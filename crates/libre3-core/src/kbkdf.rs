//! Two KDF flavors. Flavor 1 is the lib's actual construction (verified empirically):
//! `out_block_i = aes(label_12 || counter_BE32)` — plain AES counter mode, NOT NIST 800-108.
//! Flavor 2 is standard NIST SP 800-108 CMAC-KBKDF, kept for completeness; no observed path
//! uses it.

use crate::ccm::Block;
use crate::cmac;
use crate::CryptoError;

/// The lib's KDF: `out_block_i = aes(label || counter_BE32)`.
pub fn derive(label: &[u8], length: usize, aes: &Block<'_>) -> Result<Vec<u8>, CryptoError> {
    if label.len() != 12 {
        return Err(CryptoError::InvalidParameters);
    }
    if length == 0 {
        return Ok(Vec::new());
    }
    let mut out = Vec::with_capacity(length.div_ceil(16) * 16);
    let mut counter: u32 = 1;
    while out.len() < length {
        let mut block = [0u8; 16];
        block[..12].copy_from_slice(label);
        block[12..].copy_from_slice(&counter.to_be_bytes());
        out.extend_from_slice(&aes(&block)?);
        counter = counter.wrapping_add(1);
    }
    out.truncate(length);
    Ok(out)
}

/// NIST SP 800-108 Counter-mode KBKDF, CMAC-AES128 PRF:
/// `M = i_BE32 || label || 0x00 || context || L_BE32`.
pub fn nist800108(
    label: &[u8],
    context: &[u8],
    l_bits: usize,
    aes: &Block<'_>,
) -> Result<Vec<u8>, CryptoError> {
    let out_len = l_bits / 8;
    let n = out_len.div_ceil(16);
    let mut out: Vec<u8> = Vec::with_capacity(out_len);
    for i in 1..=(n as u32) {
        let mut m = Vec::with_capacity(4 + label.len() + 1 + context.len() + 4);
        m.extend_from_slice(&i.to_be_bytes());
        m.extend_from_slice(label);
        m.push(0x00);
        m.extend_from_slice(context);
        m.extend_from_slice(&(l_bits as u32).to_be_bytes());
        out.extend_from_slice(&cmac::mac(&m, aes)?);
    }
    out.truncate(out_len);
    Ok(out)
}