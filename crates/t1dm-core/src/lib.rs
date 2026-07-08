//! t1dm-core — the correctness core. Phase 0 exposes a single `Result`-returning
//! `roundtrip` over uniffi to prove the FFI seam end-to-end (Rust `#[test]`, the
//! uniffi-generated Kotlin binding, and the app all exercise the *same* function).
//! AiDEX decode, model pre/post, the curve engine and watch crypto land in later
//! phases (PLAN.private.md §2.2).
//!
//! Release builds are `panic = "abort"`, so a panic tears down the whole process.
//! The discipline that follows: **every `#[uniffi::export]` fn returns `Result` and
//! never panics on hostile input** — a malformed advert is `Err`, not a slice-OOB.

uniffi::setup_scaffolding!();

/// Error surface crossing the FFI. uniffi maps this onto a Kotlin exception, so the
/// Kotlin side sees a typed throwable rather than a process abort. Grows one variant
/// per fallible concern as the core fills in (decode, crypto, inference pre/post).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// A byte payload failed to decode/validate (CRC mismatch, bad framing, …).
    #[error("decode failed: {reason}")]
    Decode { reason: String },
    /// A catch-all for invariants that should never trip in practice; surfacing it
    /// as `Err` keeps us on the no-panic path even for "impossible" states.
    #[error("internal error: {reason}")]
    Internal { reason: String },
}

/// Phase-0 liveness probe for the Kotlin↔Rust seam. Echoes its argument through the
/// uniffi boundary so both sides can assert byte-identical behaviour.
#[uniffi::export]
pub fn roundtrip(msg: String) -> Result<String, CoreError> {
    Ok(format!("rust-core echo: {msg}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_echoes() {
        assert_eq!(roundtrip("x".to_string()).unwrap(), "rust-core echo: x");
    }

    #[test]
    fn roundtrip_never_panics_on_empty() {
        assert_eq!(roundtrip(String::new()).unwrap(), "rust-core echo: ");
    }
}
