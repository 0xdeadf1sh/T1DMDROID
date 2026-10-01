//! PLAN_T1DMDROID.md §5.2: the Libre 3 GATT wire framing, ported 1:1 from the kit's
//! FrameAssembler.swift. Writes carry a 2-byte LE offset prefix + ≤18-byte chunk; notifies
//! carry a 1-byte sequence prefix + ≤19-byte chunk (fixed by the protocol — do not negotiate
//! a larger MTU, §5.10). Pure functions, no tables. All fail-closed.

use crate::CryptoError;

/// Default ATT-MTU-derived chunk sizes for the Libre 3 protocol (FrameAssembler.swift L21-22).
pub const WRITE_CHUNK_PAYLOAD: usize = 18;
pub const NOTIFY_CHUNK_PAYLOAD: usize = 19;

/// Kit `BleFraming.fragmentForWrite` (FrameAssembler.swift L29-46): each fragment is
/// `[offset_lo, offset_hi, chunk_bytes...]`; the last fragment is whatever remains.
pub fn fragment_for_write(message: &[u8], chunk_size: usize) -> Result<Vec<Vec<u8>>, CryptoError> {
    if chunk_size == 0 {
        return Err(CryptoError::Slice { reason: "fragmentForWrite: chunkSize must be > 0".to_owned() });
    }
    let mut out = Vec::new();
    let mut offset = 0usize;
    while offset < message.len() {
        let end = (offset + chunk_size).min(message.len());
        let mut frag = Vec::with_capacity(2 + (end - offset));
        frag.push((offset & 0xff) as u8);
        frag.push(((offset >> 8) & 0xff) as u8);
        frag.extend_from_slice(&message[offset..end]);
        out.push(frag);
        offset = end;
    }
    Ok(out)
}

/// Kit `BleFraming.reassembleWrite` (FrameAssembler.swift L49-62): sort by offset, then
/// concatenate; continuity is validated (`fragmentTooShort` / `discontinuousOffsets`).
pub fn reassemble_write(fragments: &[&[u8]]) -> Result<Vec<u8>, CryptoError> {
    let mut parsed: Vec<(usize, &[u8])> = fragments
        .iter()
        .map(|f| {
            if f.len() < 2 {
                return Err(CryptoError::Slice { reason: "fragmentTooShort".to_owned() });
            }
            Ok(((f[0] as usize) | ((f[1] as usize) << 8), &f[2..]))
        })
        .collect::<Result<_, _>>()?;
    parsed.sort_by_key(|&(offset, _)| offset);
    let mut out = Vec::new();
    for (offset, body) in parsed {
        if offset != out.len() {
            return Err(CryptoError::Slice { reason: "discontinuousOffsets".to_owned() });
        }
        out.extend_from_slice(body);
    }
    Ok(out)
}

/// Kit `BleFraming.NotifyReassembler` (FrameAssembler.swift L70-105): streaming reassembler
/// for sensor → phone notifies (1-byte sequence + chunk), sequence-monotonic.
#[derive(Default)]
pub struct NotifyReassembler {
    buffer: Vec<u8>,
    next_expected_seq: Option<u8>,
}

impl NotifyReassembler {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn available_bytes(&self) -> usize {
        self.buffer.len()
    }

    /// Kit `feed` (L80-89): append one notify fragment; returns the new total length.
    pub fn feed(&mut self, fragment: &[u8]) -> Result<usize, CryptoError> {
        let Some(&seq) = fragment.first() else {
            return Err(CryptoError::Slice { reason: "fragmentTooShort".to_owned() });
        };
        if let Some(expected) = self.next_expected_seq {
            if seq != expected {
                return Err(CryptoError::Slice {
                    reason: format!("sequenceGap(expected: {expected}, got: {seq})"),
                });
            }
        }
        self.next_expected_seq = Some(seq.wrapping_add(1));
        self.buffer.extend_from_slice(&fragment[1..]);
        Ok(self.buffer.len())
    }

    /// Kit `take` (L93-98): consume the first `n` reassembled bytes.
    pub fn take(&mut self, n: usize) -> Result<Vec<u8>, CryptoError> {
        if self.buffer.len() < n {
            return Err(CryptoError::Slice {
                reason: format!("notEnoughBytes(have: {}, want: {n})", self.buffer.len()),
            });
        }
        let head = self.buffer[..n].to_vec();
        self.buffer.drain(..n);
        Ok(head)
    }

    /// Kit `reset` (L101-104): discard everything; reset the seq counter.
    pub fn reset(&mut self) {
        self.buffer.clear();
        self.next_expected_seq = None;
    }
}