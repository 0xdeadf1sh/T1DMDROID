//! kAuth blob unwrap (PLAN_T1DMDROID.md §5.4 cached-reconnect key source). Ported from the
//! kit's KAuth.swift: the 12-byte KBKDF label is blob[0..12] in plaintext; the keystream is
//! `aes_K(label || counter_BE32)` for counters starting at 2 (counter 1 is the GCM tag mask).

use crate::cipherfn::{self, CipherFnTables};
use crate::CryptoError;

/// Total kAuth blob size as observed across all captures.
pub const BLOB_SIZE: usize = 149;

/// The 5 byte-ranges that vary per session (encrypted content).
pub const VARIABLE_RANGES: [(usize, usize); 5] = [(0, 4), (49, 16), (69, 24), (97, 16), (129, 20)];

/// The 4 decrypted ciphertext blocks of a kAuth blob (plaintext form), plus its label.
pub struct UnwrappedKAuth {
    /// blob[49..65]; hypothesized = nonce1.
    pub block_a: Vec<u8>,
    /// blob[69..93]; hypothesized = kEnc (first 16) + 8B pad.
    pub block_b: Vec<u8>,
    /// blob[97..113]; hypothesized = ivEnc.
    pub block_c: Vec<u8>,
    /// blob[129..149]; hypothesized = exportedkAuth (first 16) + 4B pad.
    pub block_d: Vec<u8>,
    /// blob[0..12], the KBKDF label.
    pub nonce12: Vec<u8>,
}

impl UnwrappedKAuth {
    pub fn nonce1(&self) -> &[u8] {
        &self.block_a
    }

    pub fn k_enc(&self) -> &[u8] {
        &self.block_b[..16]
    }

    pub fn iv_enc(&self) -> &[u8] {
        &self.block_c
    }

    pub fn exported_kauth(&self) -> &[u8] {
        &self.block_d[..16]
    }
}

/// Static K1/K2 from the blob (verbatim lib state copies).
pub fn static_keys(blob: &[u8]) -> Result<(&[u8], &[u8]), CryptoError> {
    if blob.len() != BLOB_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("kAuth blob must be {BLOB_SIZE} bytes, got {}", blob.len()),
        });
    }
    Ok((&blob[17..33], &blob[33..49]))
}

/// The variable bytes of a kAuth blob, concatenated: 80 bytes per session.
pub fn variable_bytes(blob: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if blob.len() != BLOB_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("kAuth blob must be {BLOB_SIZE} bytes, got {}", blob.len()),
        });
    }
    let mut out = Vec::with_capacity(80);
    for (off, len) in VARIABLE_RANGES {
        out.extend_from_slice(&blob[off..off + len]);
    }
    Ok(out)
}

/// The lib's KBKDF: `block_i = aes_K(label_12 || i_BE32)` for i in 1..n.
pub fn kdf(
    label: &[u8],
    start_counter: u32,
    block_count: usize,
    t: &CipherFnTables,
) -> Result<Vec<u8>, CryptoError> {
    if label.len() != 12 {
        return Err(CryptoError::LibAes {
            reason: format!("KBKDF label must be 12 bytes, got {}", label.len()),
        });
    }
    let mut out = Vec::with_capacity(block_count * 16);
    for i in start_counter..start_counter + block_count as u32 {
        let mut input = Vec::with_capacity(16);
        input.extend_from_slice(label);
        input.extend_from_slice(&i.to_be_bytes());
        out.extend_from_slice(&cipherfn::aes_k(&input, t)?);
    }
    Ok(out)
}

/// XOR-CTR decrypt of a wrapped blob: label = blob[0..12], keystream from counter 2.
pub fn ctr_decrypt(blob: &[u8], t: &CipherFnTables) -> Result<Vec<u8>, CryptoError> {
    if blob.len() < 12 {
        return Err(CryptoError::LibAes {
            reason: format!("wrapped blob must be at least 12 bytes, got {}", blob.len()),
        });
    }
    let body = &blob[12..];
    let blocks_needed = body.len().div_ceil(16).max(1);
    let keystream = kdf(&blob[..12], 2, blocks_needed, t)?;
    Ok(body.iter().zip(&keystream).map(|(b, k)| b ^ k).collect())
}

/// Unwrap a 149-byte kAuth blob into its 4 plaintext blocks plus the 12-byte label.
///
/// SCATTERED encryption: 4 ciphertext regions (blob[49..65], [69..93], [97..113], [129..149])
/// map sequentially onto the contiguous keystream KDF[2..N]. End-to-end sensor pairing is the
/// validation oracle for this mapping (the kit's hypothesis, carried unverified).
pub fn unwrap149(blob: &[u8], t: &CipherFnTables) -> Result<UnwrappedKAuth, CryptoError> {
    if blob.len() != BLOB_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("kAuth blob must be {BLOB_SIZE} bytes, got {}", blob.len()),
        });
    }
    let label = blob[..12].to_vec();
    let encrypted: [(usize, usize); 4] = [(49, 16), (69, 24), (97, 16), (129, 20)];
    let keystream = kdf(&label, 2, 5, t)?;

    let mut ks_offset = 0usize;
    let mut blocks: Vec<Vec<u8>> = Vec::with_capacity(4);
    for (off, len) in encrypted {
        let cipher = &blob[off..off + len];
        blocks.push(
            cipher
                .iter()
                .zip(&keystream[ks_offset..ks_offset + len])
                .map(|(b, k)| b ^ k)
                .collect(),
        );
        ks_offset += len;
    }

    Ok(UnwrappedKAuth {
        nonce12: label,
        block_a: blocks.remove(0),
        block_b: blocks.remove(0),
        block_c: blocks.remove(0),
        block_d: blocks.remove(0),
    })
}