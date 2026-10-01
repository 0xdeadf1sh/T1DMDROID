//! PLAN_T1DMDROID.md §5.8: the account-ID fold the sensor stores; a mismatch answers 0xB1.

/// Region selects the fold; both fold the lowercased dashed account UUID.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Libre3Region {
    Eu,
    Us,
}

/// `freeStyleLibre3` fold: UTF-16 code units, `h = (h *% 0x811c9dc5) ^ unit`, seed 0.
fn fold_eu(account_id: &str) -> u32 {
    account_id
        .to_lowercase()
        .encode_utf16()
        .fold(0u32, |h, unit| h.wrapping_mul(0x811c_9dc5) ^ (unit as u32))
}

/// `libreByAbbott` fold: UTF-8 bytes as consecutive 4-byte big-endian words, wrapping sum;
/// a trailing partial word is left-padded (a 36-char ID never hits that path).
fn fold_us(account_id: &str) -> u32 {
    let bytes = account_id.to_lowercase();
    bytes.as_bytes().chunks(4).fold(0u32, |sum, chunk| {
        let mut word = [0u8; 4];
        word[4 - chunk.len()..].copy_from_slice(chunk);
        sum.wrapping_add(u32::from_be_bytes(word))
    })
}

pub fn receiver_id(account_id: &str, region: Libre3Region) -> u32 {
    match region {
        Libre3Region::Eu => fold_eu(account_id),
        Libre3Region::Us => fold_us(account_id),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Synthetic account; both folds computed independently of this code.
    const ACCOUNT: &str = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    #[test]
    fn eu_golden() {
        assert_eq!(receiver_id(ACCOUNT, Libre3Region::Eu), 0x684fc53f);
    }

    #[test]
    fn us_golden() {
        assert_eq!(receiver_id(ACCOUNT, Libre3Region::Us), 0x4a4a1b1b);
    }

    #[test]
    fn folds_the_lowercased_string() {
        assert_eq!(receiver_id(&ACCOUNT.to_uppercase(), Libre3Region::Eu), 0x684fc53f);
        assert_eq!(receiver_id(&ACCOUNT.to_uppercase(), Libre3Region::Us), 0x4a4a1b1b);
    }
}
