//! Watch link, T1DMCOMMON `SPEC/watch.md`. Hostile input is `Err`, never a panic.

mod session;
pub use session::*;

pub mod frames;
pub mod records;

#[derive(Debug, thiserror::Error)]
pub enum WatchError {
    #[error("decode failed: {0}")]
    Decode(String),
    /// Never-expected states, surfaced as `Err` to stay off the panic path.
    #[error("internal error: {0}")]
    Internal(String),
}

pub type Result<T> = std::result::Result<T, WatchError>;

#[inline]
pub(crate) fn dec(reason: impl Into<String>) -> WatchError {
    WatchError::Decode(reason.into())
}

#[inline]
pub(crate) fn internal(reason: impl Into<String>) -> WatchError {
    WatchError::Internal(reason.into())
}
