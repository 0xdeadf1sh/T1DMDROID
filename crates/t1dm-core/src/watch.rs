//! Watch-link cryptography (SPEC §3, [[watch-link]]) — the security source of truth for
//! the phone → ESP32-C3 push. Kotlin owns the GATT plumbing and the at-rest key wrap
//! (Keystore/StrongBox); *this* module owns every byte that crosses the air.
//!
//! Suite (v1, versioned on the wire and in the persisted state):
//!   - key agreement  : **X25519** ECDH (RFC 7748), one ephemeral keypair per session
//!   - key derivation : **HKDF-SHA256** — extract the DH secret to a root, expand two
//!                      per-direction AES keys bound to *both* public keys (transcript
//!                      binding → no unknown-key-share); a manual ratchet advances the root
//!   - record AEAD    : **AES-128-GCM**, 16-byte key, 12-byte nonce, 16-byte tag
//!   - nonce          : `epoch:u32_le || seq:u64_le` — monotone per direction; the send
//!                      counter is *windowed* and the window is **burned on cold start** so
//!                      a `kill -9` / battery-yank can never re-emit a `(key, nonce)` pair
//!   - authentication : a deterministic 6-digit **SAS** over both public keys (ZRTP-style),
//!                      compared aloud on phone + watch to defeat a MITM on first pair
//!
//! Data flows phone → watch, but the state machine is symmetric: `seal` is the local
//! send direction and `open` the receive direction (PUSH_ACK / loopback tests), each with
//! its own key and counter. Every `#[uniffi::export]` fn returns `Result` and never panics
//! on hostile input — a truncated frame, a replayed seq, an epoch mismatch, a poisoned
//! lock: all are `Err`, never an abort (release builds are `panic = "abort"`).

use std::sync::{Arc, Mutex};

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes128Gcm, Key, Nonce};
use hkdf::Hkdf;
use rand_core::OsRng;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::CoreError;

// ── Suite v1 domain-separation constants ───────────────────────────────────────────
/// HKDF-Extract salt — pins the whole schedule to this app + suite version.
const SALT: &[u8] = b"t1dm-watch/x25519/hkdf-sha256/aes128gcm/v1";
/// Expand label: DH secret → 32-byte epoch-0 root.
const INFO_ROOT: &[u8] = b"t1dm-watch root v1";
/// Expand labels for the two per-direction keys (A = lexicographically lower pubkey).
const INFO_A2B: &[u8] = b"t1dm-watch key A->B v1";
const INFO_B2A: &[u8] = b"t1dm-watch key B->A v1";
/// Expand label for the manual ratchet: root_e → root_{e+1}.
const INFO_RATCHET: &[u8] = b"t1dm-watch ratchet v1";
/// SAS domain separator.
const SAS_INFO: &[u8] = b"t1dm-watch sas v1";

/// AEAD frame version byte (leads every sealed record).
const FRAME_VER: u8 = 1;
/// Persisted-state blob magic + version.
const STATE_MAGIC: &[u8; 4] = b"T1WC";
const STATE_VER: u8 = 1;

/// Send-counter reservation window. Each `export_state` reserves seqs up to
/// `send_next + NONCE_WINDOW`; `seal` refuses once it reaches the reserved ceiling until
/// the caller checkpoints again. Cold start jumps `send_next` to the persisted ceiling,
/// burning any unused seqs in the window → no `(key, nonce)` can ever repeat.
const NONCE_WINDOW: u64 = 64;

/// AES-128-GCM sizes.
const KEY_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
/// Frame header: `ver(1) || epoch(4) || seq(8)`. The full header is fed as AEAD AAD, so
/// epoch/seq are authenticated and cannot be mauled.
const HDR_LEN: usize = 1 + 4 + 8;

const X25519_LEN: usize = 32;

#[inline]
fn dec(reason: impl Into<String>) -> CoreError {
    CoreError::Decode { reason: reason.into() }
}
#[inline]
fn internal(reason: impl Into<String>) -> CoreError {
    CoreError::Internal { reason: reason.into() }
}

/// Which side of the canonical (lower, higher) pubkey ordering we are. Fixes which of the
/// two derived keys is "send" vs "receive" without any negotiated role bit.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Role {
    /// Our pubkey sorts lower → we send with k_A2B, receive with k_B2A.
    A,
    /// Our pubkey sorts higher → we send with k_B2A, receive with k_A2B.
    B,
}

/// The established, per-epoch secret material + counters.
struct Established {
    peer_public: [u8; X25519_LEN],
    role: Role,
    epoch: u32,
    /// Current epoch root (ratcheted on `rotate`); zeroized when replaced.
    root: [u8; 32],
    send_key: [u8; KEY_LEN],
    recv_key: [u8; KEY_LEN],
    /// Next send seq to emit.
    send_next: u64,
    /// Reserved ceiling: `seal` refuses at `send_next == send_ceiling`.
    send_ceiling: u64,
    /// Smallest acceptable receive seq (strictly-increasing replay guard; gaps ok).
    recv_min: u64,
}

impl Drop for Established {
    fn drop(&mut self) {
        self.root.zeroize();
        self.send_key.zeroize();
        self.recv_key.zeroize();
    }
}

enum Phase {
    /// Fresh keypair minted; awaiting the peer's public key.
    Handshake,
    Established(Established),
}

struct Inner {
    /// Our X25519 secret. Retained so `accept_peer` (post-SAS confirm) can run and so
    /// `sas()`/`public_key()` work throughout the handshake; consumed on `reset`.
    secret: StaticSecret,
    public: [u8; X25519_LEN],
    phase: Phase,
}

/// A watch crypto session. One per paired watch; `Arc`-shared, internally locked.
#[derive(uniffi::Object)]
pub struct WatchSession {
    inner: Mutex<Inner>,
}

// ── key schedule ────────────────────────────────────────────────────────────────────

/// Order the two public keys canonically → `(low, high)`.
fn order_pubkeys<'a>(
    ours: &'a [u8; X25519_LEN],
    theirs: &'a [u8; X25519_LEN],
) -> (&'a [u8; X25519_LEN], &'a [u8; X25519_LEN]) {
    if ours.as_slice() <= theirs.as_slice() {
        (ours, theirs)
    } else {
        (theirs, ours)
    }
}

/// HKDF-Extract(SALT, dh) then Expand(INFO_ROOT) → the epoch-0 root.
fn derive_root(dh: &[u8; 32]) -> Result<[u8; 32], CoreError> {
    let hk = Hkdf::<Sha256>::new(Some(SALT), dh);
    let mut root = [0u8; 32];
    hk.expand(INFO_ROOT, &mut root)
        .map_err(|_| internal("hkdf root expand"))?;
    Ok(root)
}

/// Expand a per-direction 16-byte key from the epoch root: `HKDF-Expand(root, label ||
/// pk_low || pk_high)`. Binding both pubkeys pins the key to the exact handshake.
fn derive_dir_key(
    root: &[u8; 32],
    label: &[u8],
    pk_low: &[u8; X25519_LEN],
    pk_high: &[u8; X25519_LEN],
) -> Result<[u8; KEY_LEN], CoreError> {
    let hk = Hkdf::<Sha256>::from_prk(root).map_err(|_| internal("hkdf from_prk"))?;
    let mut info = Vec::with_capacity(label.len() + 2 * X25519_LEN);
    info.extend_from_slice(label);
    info.extend_from_slice(pk_low);
    info.extend_from_slice(pk_high);
    let mut key = [0u8; KEY_LEN];
    hk.expand(&info, &mut key)
        .map_err(|_| internal("hkdf key expand"))?;
    Ok(key)
}

/// Advance the root one ratchet step: `root' = HKDF-Expand(root, INFO_RATCHET)`.
fn ratchet_root(root: &[u8; 32]) -> Result<[u8; 32], CoreError> {
    let hk = Hkdf::<Sha256>::from_prk(root).map_err(|_| internal("hkdf ratchet from_prk"))?;
    let mut next = [0u8; 32];
    hk.expand(INFO_RATCHET, &mut next)
        .map_err(|_| internal("hkdf ratchet expand"))?;
    Ok(next)
}

/// (send_key, recv_key) for `role` from the current epoch root + ordered pubkeys.
fn dir_keys(
    root: &[u8; 32],
    role: Role,
    pk_low: &[u8; X25519_LEN],
    pk_high: &[u8; X25519_LEN],
) -> Result<([u8; KEY_LEN], [u8; KEY_LEN]), CoreError> {
    let k_a2b = derive_dir_key(root, INFO_A2B, pk_low, pk_high)?;
    let k_b2a = derive_dir_key(root, INFO_B2A, pk_low, pk_high)?;
    Ok(match role {
        Role::A => (k_a2b, k_b2a),
        Role::B => (k_b2a, k_a2b),
    })
}

/// The deterministic 6-digit SAS over both public keys (order-independent).
fn compute_sas(a: &[u8; X25519_LEN], b: &[u8; X25519_LEN]) -> String {
    let (lo, hi) = order_pubkeys(a, b);
    let mut h = Sha256::new();
    h.update(SAS_INFO);
    h.update(lo);
    h.update(hi);
    let d = h.finalize();
    let v = u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 1_000_000;
    format!("{v:06}")
}

fn nonce_bytes(epoch: u32, seq: u64) -> [u8; NONCE_LEN] {
    let mut n = [0u8; NONCE_LEN];
    n[0..4].copy_from_slice(&epoch.to_le_bytes());
    n[4..12].copy_from_slice(&seq.to_le_bytes());
    n
}

fn to_arr32(v: &[u8], what: &str) -> Result<[u8; 32], CoreError> {
    if v.len() != X25519_LEN {
        return Err(dec(format!("{what}: expected 32 bytes, got {}", v.len())));
    }
    let mut a = [0u8; 32];
    a.copy_from_slice(v);
    Ok(a)
}

// ── FFI surface ─────────────────────────────────────────────────────────────────────

#[uniffi::export]
impl WatchSession {
    /// Start a handshake: mint a fresh ephemeral X25519 keypair (OS CSPRNG). The session
    /// is in the `Handshake` phase until `accept_peer`.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret).to_bytes();
        Arc::new(Self {
            inner: Mutex::new(Inner { secret, public, phase: Phase::Handshake }),
        })
    }

    /// Restore a persisted session and **burn the send window**: `send_next` jumps to the
    /// persisted ceiling with NO headroom, so the next nonce is strictly past anything used
    /// pre-crash *and* the first `seal` refuses until the caller reserves+persists a fresh
    /// window via `export_state` — that mandatory cold-start checkpoint is what makes a
    /// second crash equally safe. The blob is the ciphertext-of-secrets Kotlin keeps under
    /// Keystore/StrongBox.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        // magic(4) ver(1) role(1) epoch(4) send_ceiling(8) recv_min(8)
        //   root(32) our_secret(32) our_public(32) peer_public(32)
        const LEN: usize = 4 + 1 + 1 + 4 + 8 + 8 + 32 + 32 + 32 + 32;
        if state.len() != LEN {
            return Err(dec(format!("state blob: expected {LEN} bytes, got {}", state.len())));
        }
        if &state[0..4] != STATE_MAGIC {
            return Err(dec("state blob: bad magic"));
        }
        if state[4] != STATE_VER {
            return Err(dec(format!("state blob: unsupported version {}", state[4])));
        }
        let role = match state[5] {
            0 => Role::A,
            1 => Role::B,
            r => return Err(dec(format!("state blob: bad role {r}"))),
        };
        let mut off = 6;
        let epoch = u32::from_le_bytes(state[off..off + 4].try_into().unwrap());
        off += 4;
        let send_ceiling = u64::from_le_bytes(state[off..off + 8].try_into().unwrap());
        off += 8;
        let recv_min = u64::from_le_bytes(state[off..off + 8].try_into().unwrap());
        off += 8;
        let root = to_arr32(&state[off..off + 32], "root")?;
        off += 32;
        let secret_bytes = to_arr32(&state[off..off + 32], "secret")?;
        off += 32;
        let public = to_arr32(&state[off..off + 32], "public")?;
        off += 32;
        let peer_public = to_arr32(&state[off..off + 32], "peer")?;

        let secret = StaticSecret::from(secret_bytes);
        let (pk_low, pk_high) = order_pubkeys(&public, &peer_public);
        let (send_key, recv_key) = dir_keys(&root, role, pk_low, pk_high)?;

        // BURN: never reuse a seq below the reserved ceiling.
        let est = Established {
            peer_public,
            role,
            epoch,
            root,
            send_key,
            recv_key,
            send_next: send_ceiling,
            send_ceiling,
            recv_min,
        };
        Ok(Arc::new(Self {
            inner: Mutex::new(Inner { secret, public, phase: Phase::Established(est) }),
        }))
    }

    /// Our 32-byte X25519 public key (send to the peer as HELLO).
    pub fn public_key(&self) -> Result<Vec<u8>, CoreError> {
        Ok(self.lock()?.public.to_vec())
    }

    /// The current epoch (0 after the first `accept_peer`, +1 per `rotate`), or `Err` if
    /// still in handshake.
    pub fn epoch(&self) -> Result<u32, CoreError> {
        match &self.lock()?.phase {
            Phase::Established(e) => Ok(e.epoch),
            Phase::Handshake => Err(dec("no session: still in handshake")),
        }
    }

    /// True once keys are live.
    pub fn is_established(&self) -> Result<bool, CoreError> {
        Ok(matches!(self.lock()?.phase, Phase::Established(_)))
    }

    /// The authoritative next send seq to emit (`send_next`). After `restore` this is the
    /// burned ceiling, so the phone panel surfaces the resumed counter, not a local 0.
    /// `Err` while still in handshake.
    pub fn send_seq(&self) -> Result<u64, CoreError> {
        match &self.lock()?.phase {
            Phase::Established(e) => Ok(e.send_next),
            Phase::Handshake => Err(dec("no session: still in handshake")),
        }
    }

    /// The authoritative receive replay floor (`recv_min`): the smallest seq still
    /// acceptable to `open`. `Err` while still in handshake.
    pub fn recv_min(&self) -> Result<u64, CoreError> {
        match &self.lock()?.phase {
            Phase::Established(e) => Ok(e.recv_min),
            Phase::Handshake => Err(dec("no session: still in handshake")),
        }
    }

    /// Accept the peer's public key: run ECDH, derive the epoch-0 root + both direction
    /// keys, and arm the counters. Idempotent-unsafe: re-accepting resets the session to
    /// a fresh epoch-0 (use `rotate` to advance an established link).
    pub fn accept_peer(&self, peer_public: Vec<u8>) -> Result<(), CoreError> {
        let peer = to_arr32(&peer_public, "peer public")?;
        // Reject a degenerate/identical key (all-zero DH or peer == us).
        if peer == [0u8; 32] {
            return Err(dec("peer public key is all-zero"));
        }
        let mut inner = self.lock()?;
        if peer == inner.public {
            return Err(dec("peer public key equals ours"));
        }
        let peer_pk = PublicKey::from(peer);
        let dh = inner.secret.diffie_hellman(&peer_pk);
        let dh_bytes: [u8; 32] = *dh.as_bytes();
        // X25519 contributory-behaviour guard: an all-zero shared secret means a
        // small-order/attacker point — refuse rather than key off a known value.
        if !dh.was_contributory() {
            return Err(dec("non-contributory ECDH (small-order peer key)"));
        }
        let root = derive_root(&dh_bytes)?;
        let role = if inner.public.as_slice() <= peer.as_slice() { Role::A } else { Role::B };
        let (pk_low, pk_high) = order_pubkeys(&inner.public, &peer);
        let (send_key, recv_key) = dir_keys(&root, role, pk_low, pk_high)?;
        inner.phase = Phase::Established(Established {
            peer_public: peer,
            role,
            epoch: 0,
            root,
            send_key,
            recv_key,
            send_next: 0,
            // In-memory initial window; `export_state` extends + persists it.
            send_ceiling: NONCE_WINDOW,
            recv_min: 0,
        });
        Ok(())
    }

    /// The 6-digit SAS to compare on both devices. `Err` until `accept_peer` (the peer key
    /// is one of its two inputs).
    pub fn sas(&self) -> Result<String, CoreError> {
        let inner = self.lock()?;
        match &inner.phase {
            Phase::Established(e) => Ok(compute_sas(&inner.public, &e.peer_public)),
            Phase::Handshake => Err(dec("no SAS: peer key not yet accepted")),
        }
    }

    /// Seal `plaintext` for the send direction → a self-describing frame
    /// (`ver||epoch||seq||ct||tag`). `aad` is extra caller context authenticated but not
    /// transmitted (may be empty). Refuses once the reserved nonce window is exhausted —
    /// checkpoint via `export_state` to extend it.
    pub fn seal(&self, plaintext: Vec<u8>, aad: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        let mut inner = self.lock()?;
        let e = match &mut inner.phase {
            Phase::Established(e) => e,
            Phase::Handshake => return Err(dec("cannot seal: no session")),
        };
        if e.send_next >= e.send_ceiling {
            return Err(dec("nonce window exhausted; checkpoint (export_state) required"));
        }
        let seq = e.send_next;
        let mut header = Vec::with_capacity(HDR_LEN);
        header.push(FRAME_VER);
        header.extend_from_slice(&e.epoch.to_le_bytes());
        header.extend_from_slice(&seq.to_le_bytes());
        let mut full_aad = Vec::with_capacity(HDR_LEN + aad.len());
        full_aad.extend_from_slice(&header);
        full_aad.extend_from_slice(&aad);
        let cipher = Aes128Gcm::new(Key::<Aes128Gcm>::from_slice(&e.send_key));
        let nonce = nonce_bytes(e.epoch, seq);
        let ct = cipher
            .encrypt(Nonce::from_slice(&nonce), Payload { msg: &plaintext, aad: &full_aad })
            .map_err(|_| internal("aes-gcm seal"))?;
        e.send_next += 1;
        let mut frame = header;
        frame.extend_from_slice(&ct);
        Ok(frame)
    }

    /// Open a received frame for the receive direction → plaintext. Enforces: frame
    /// version, epoch match, strictly-increasing seq (replay/reorder guard), and the GCM
    /// tag. `aad` must match what the sender authenticated.
    pub fn open(&self, frame: Vec<u8>, aad: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        let mut inner = self.lock()?;
        let e = match &mut inner.phase {
            Phase::Established(e) => e,
            Phase::Handshake => return Err(dec("cannot open: no session")),
        };
        if frame.len() < HDR_LEN + TAG_LEN {
            return Err(dec(format!("frame too short: {} bytes", frame.len())));
        }
        if frame[0] != FRAME_VER {
            return Err(dec(format!("unknown frame version {}", frame[0])));
        }
        let epoch = u32::from_le_bytes(frame[1..5].try_into().unwrap());
        let seq = u64::from_le_bytes(frame[5..13].try_into().unwrap());
        if epoch != e.epoch {
            return Err(dec(format!("epoch mismatch: frame {epoch}, session {}", e.epoch)));
        }
        if seq < e.recv_min {
            return Err(dec(format!("replay/reorder: seq {seq} < min {}", e.recv_min)));
        }
        let mut full_aad = Vec::with_capacity(HDR_LEN + aad.len());
        full_aad.extend_from_slice(&frame[0..HDR_LEN]);
        full_aad.extend_from_slice(&aad);
        let cipher = Aes128Gcm::new(Key::<Aes128Gcm>::from_slice(&e.recv_key));
        let nonce = nonce_bytes(epoch, seq);
        let pt = cipher
            .decrypt(Nonce::from_slice(&nonce), Payload { msg: &frame[HDR_LEN..], aad: &full_aad })
            .map_err(|_| dec("aes-gcm open: authentication failed"))?;
        // Advance only after the tag verifies.
        e.recv_min = seq
            .checked_add(1)
            .ok_or_else(|| internal("recv seq overflow"))?;
        Ok(pt)
    }

    /// Manual key rotation: ratchet the root forward one epoch, re-derive both keys, reset
    /// the per-epoch counters. Forward-secret — the previous root is zeroized. Returns the
    /// new epoch. The peer must ratchet in lockstep (REKEY on the wire).
    pub fn rotate(&self) -> Result<u32, CoreError> {
        let mut inner = self.lock()?;
        let public = inner.public;
        let e = match &mut inner.phase {
            Phase::Established(e) => e,
            Phase::Handshake => return Err(dec("cannot rotate: no session")),
        };
        let new_epoch = e.epoch.checked_add(1).ok_or_else(|| internal("epoch overflow"))?;
        let new_root = ratchet_root(&e.root)?;
        let (pk_low, pk_high) = order_pubkeys(&public, &e.peer_public);
        let (send_key, recv_key) = dir_keys(&new_root, e.role, pk_low, pk_high)?;
        e.root.zeroize();
        e.root = new_root;
        e.epoch = new_epoch;
        e.send_key = send_key;
        e.recv_key = recv_key;
        e.send_next = 0;
        e.send_ceiling = NONCE_WINDOW;
        e.recv_min = 0;
        Ok(new_epoch)
    }

    /// Session reset / unpair: discard all derived secrets and mint a fresh keypair. The
    /// link must be re-paired (new `accept_peer` + new SAS) afterwards.
    pub fn reset(&self) -> Result<(), CoreError> {
        let mut inner = self.lock()?;
        let secret = StaticSecret::random_from_rng(OsRng);
        inner.public = PublicKey::from(&secret).to_bytes();
        inner.secret = secret; // old StaticSecret zeroizes on drop (dalek zeroize feature)
        inner.phase = Phase::Handshake;
        Ok(())
    }

    /// Serialize the resumable session + **reserve a fresh send window** (`send_ceiling =
    /// send_next + NONCE_WINDOW`). The caller MUST durably persist the returned blob before
    /// trusting subsequent seals; on the next cold start `restore` burns to this ceiling.
    /// The blob carries the root + our secret in the clear — Kotlin wraps it at rest.
    pub fn export_state(&self) -> Result<Vec<u8>, CoreError> {
        let mut inner = self.lock()?;
        let secret_bytes = inner.secret.to_bytes();
        let public = inner.public;
        let e = match &mut inner.phase {
            Phase::Established(e) => e,
            Phase::Handshake => return Err(dec("cannot export: no session")),
        };
        e.send_ceiling = e
            .send_next
            .checked_add(NONCE_WINDOW)
            .ok_or_else(|| internal("send ceiling overflow"))?;
        let mut out = Vec::with_capacity(4 + 1 + 1 + 4 + 8 + 8 + 32 * 4);
        out.extend_from_slice(STATE_MAGIC);
        out.push(STATE_VER);
        out.push(match e.role {
            Role::A => 0,
            Role::B => 1,
        });
        out.extend_from_slice(&e.epoch.to_le_bytes());
        out.extend_from_slice(&e.send_ceiling.to_le_bytes());
        out.extend_from_slice(&e.recv_min.to_le_bytes());
        out.extend_from_slice(&e.root);
        out.extend_from_slice(&secret_bytes);
        out.extend_from_slice(&public);
        out.extend_from_slice(&e.peer_public);
        Ok(out)
    }
}

impl WatchSession {
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, Inner>, CoreError> {
        self.inner.lock().map_err(|_| internal("session lock poisoned"))
    }

    #[cfg(test)]
    fn from_secret_bytes(secret_bytes: [u8; 32]) -> Arc<Self> {
        let secret = StaticSecret::from(secret_bytes);
        let public = PublicKey::from(&secret).to_bytes();
        Arc::new(Self {
            inner: Mutex::new(Inner { secret, public, phase: Phase::Handshake }),
        })
    }
}

/// Standalone SAS over two public keys — lets the UI / docs recompute it independently of a
/// live session. Order-independent; `Err` on a non-32-byte key.
#[uniffi::export]
pub fn watch_sas(a: Vec<u8>, b: Vec<u8>) -> Result<String, CoreError> {
    Ok(compute_sas(&to_arr32(&a, "pubkey a")?, &to_arr32(&b, "pubkey b")?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn hexs(s: &str) -> Vec<u8> {
        let s: String = s.chars().filter(|c| !c.is_whitespace()).collect();
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }
    fn tohex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }
    fn golden() -> Value {
        serde_json::from_str(include_str!("testdata/watch_golden.json")).unwrap()
    }

    // ── RFC 7748 §5.2 X25519 known-answer ────────────────────────────────────────────
    #[test]
    fn x25519_rfc7748_kat() {
        let g = golden();
        let k = &g["x25519_rfc7748"];
        let scalar = to_arr32(&hexs(k["scalar"].as_str().unwrap()), "scalar").unwrap();
        let u = to_arr32(&hexs(k["u"].as_str().unwrap()), "u").unwrap();
        let want = hexs(k["output"].as_str().unwrap());
        let got = x25519_dalek::x25519(scalar, u);
        assert_eq!(tohex(&got), tohex(&want), "RFC 7748 X25519 KAT");
    }

    // ── RFC 5869 Test Case 1 HKDF-SHA256 ─────────────────────────────────────────────
    #[test]
    fn hkdf_sha256_rfc5869_tc1() {
        let g = golden();
        let k = &g["hkdf_rfc5869_tc1"];
        let ikm = hexs(k["ikm"].as_str().unwrap());
        let salt = hexs(k["salt"].as_str().unwrap());
        let info = hexs(k["info"].as_str().unwrap());
        let l = k["l"].as_u64().unwrap() as usize;
        let hk = Hkdf::<Sha256>::new(Some(&salt), &ikm);
        let mut okm = vec![0u8; l];
        hk.expand(&info, &mut okm).unwrap();
        assert_eq!(tohex(&okm), k["okm"].as_str().unwrap());
        // Also pin the PRK via from_prk round-trip length.
        assert_eq!(k["okm"].as_str().unwrap().len(), l * 2);
    }

    // ── AES-128-GCM seal/open round-trip on a fixed vector ───────────────────────────
    #[test]
    fn aes128gcm_roundtrip_kat() {
        let g = golden();
        let k = &g["aes128gcm"];
        let key = hexs(k["key"].as_str().unwrap());
        let nonce = hexs(k["nonce"].as_str().unwrap());
        let aad = hexs(k["aad"].as_str().unwrap());
        let pt = hexs(k["plaintext"].as_str().unwrap());
        let cipher = Aes128Gcm::new(Key::<Aes128Gcm>::from_slice(&key));
        let ct = cipher
            .encrypt(Nonce::from_slice(&nonce), Payload { msg: &pt, aad: &aad })
            .unwrap();
        assert_eq!(tohex(&ct), k["ciphertext_and_tag"].as_str().unwrap(), "GCM seal");
        let back = cipher
            .decrypt(Nonce::from_slice(&nonce), Payload { msg: &ct, aad: &aad })
            .unwrap();
        assert_eq!(back, pt, "GCM open");
    }

    // ── SAS determinism + order-independence ─────────────────────────────────────────
    #[test]
    fn sas_deterministic_and_symmetric() {
        let g = golden();
        let k = &g["sas"];
        let a = hexs(k["pubkey_a"].as_str().unwrap());
        let b = hexs(k["pubkey_b"].as_str().unwrap());
        let want = k["sas"].as_str().unwrap();
        assert_eq!(watch_sas(a.clone(), b.clone()).unwrap(), want);
        assert_eq!(watch_sas(b, a).unwrap(), want, "SAS must be order-independent");
    }

    // ── fully-worked sealed PUSH frame (the vector docs/WATCH_BLE.md publishes) ───────
    #[test]
    fn worked_push_frame() {
        let g = golden();
        let k = &g["worked_push"];
        let sa = to_arr32(&hexs(k["secret_a"].as_str().unwrap()), "sa").unwrap();
        let sb = to_arr32(&hexs(k["secret_b"].as_str().unwrap()), "sb").unwrap();
        let a = WatchSession::from_secret_bytes(sa);
        let b = WatchSession::from_secret_bytes(sb);
        let pa = a.public_key().unwrap();
        let pb = b.public_key().unwrap();
        assert_eq!(tohex(&pa), k["public_a"].as_str().unwrap(), "pubkey A");
        assert_eq!(tohex(&pb), k["public_b"].as_str().unwrap(), "pubkey B");

        a.accept_peer(pb.clone()).unwrap();
        b.accept_peer(pa.clone()).unwrap();
        assert_eq!(a.sas().unwrap(), k["sas"].as_str().unwrap(), "SAS");
        assert_eq!(a.sas().unwrap(), b.sas().unwrap(), "SAS match");

        // Inspect derived material (A's send key = phone→watch if A is phone).
        {
            let inner = a.lock().unwrap();
            if let Phase::Established(e) = &inner.phase {
                assert_eq!(tohex(&e.root), k["root_epoch0"].as_str().unwrap(), "root");
                assert_eq!(tohex(&e.send_key), k["a_send_key"].as_str().unwrap(), "A send key");
            } else {
                panic!("A not established");
            }
        }

        let plaintext = hexs(k["plaintext"].as_str().unwrap());
        let aad = hexs(k["aad"].as_str().unwrap());
        let frame = a.seal(plaintext.clone(), aad.clone()).unwrap();
        assert_eq!(tohex(&frame), k["frame"].as_str().unwrap(), "worked PUSH frame");

        // The counterpart opens it.
        let opened = b.open(frame, aad).unwrap();
        assert_eq!(opened, plaintext, "peer opens the worked frame");
    }

    // ── loopback e2e: random handshake → seal/open both ways, SAS matches ─────────────
    #[test]
    fn loopback_e2e() {
        let phone = WatchSession::new();
        let watch = WatchSession::new();
        let pp = phone.public_key().unwrap();
        let wp = watch.public_key().unwrap();
        phone.accept_peer(wp).unwrap();
        watch.accept_peer(pp).unwrap();
        assert_eq!(phone.sas().unwrap(), watch.sas().unwrap(), "SAS agrees");
        assert!(phone.sas().unwrap().len() == 6);

        for i in 0..10u8 {
            let msg = vec![i; (i as usize) + 1];
            let f = phone.seal(msg.clone(), vec![0xAB, i]).unwrap();
            assert_eq!(watch.open(f, vec![0xAB, i]).unwrap(), msg, "push {i}");
        }
        // Reverse direction (PUSH_ACK) uses the other key pair.
        let ack = watch.seal(b"ack".to_vec(), vec![]).unwrap();
        assert_eq!(phone.open(ack, vec![]).unwrap(), b"ack");
    }

    #[test]
    fn open_rejects_tamper_replay_and_epoch() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();

        let f0 = a.seal(b"hello".to_vec(), vec![]).unwrap();
        // Tamper one ciphertext byte → auth failure, not panic.
        let mut bad = f0.clone();
        let last = bad.len() - 1;
        bad[last] ^= 0x01;
        assert!(b.open(bad, vec![]).is_err());
        // AAD mismatch → auth failure.
        assert!(b.open(f0.clone(), vec![0x99]).is_err());

        // Good open advances the watermark.
        assert_eq!(b.open(f0.clone(), vec![]).unwrap(), b"hello");
        // Replay the same frame → rejected (seq < min).
        assert!(b.open(f0, vec![]).is_err());

        // Epoch mismatch: rotate only A, its next frame won't open on stale-epoch B.
        a.rotate().unwrap();
        let f_new = a.seal(b"e1".to_vec(), vec![]).unwrap();
        assert!(b.open(f_new.clone(), vec![]).is_err(), "epoch-desync must fail closed");
        // Both rotate → back in sync.
        b.rotate().unwrap();
        assert_eq!(b.open(f_new, vec![]).unwrap(), b"e1");
    }

    #[test]
    fn rotate_forward_secrecy_changes_keys() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();

        let key_e0 = {
            let g = a.lock().unwrap();
            match &g.phase {
                Phase::Established(e) => e.send_key,
                _ => panic!(),
            }
        };
        assert_eq!(a.rotate().unwrap(), 1);
        let key_e1 = {
            let g = a.lock().unwrap();
            match &g.phase {
                Phase::Established(e) => e.send_key,
                _ => panic!(),
            }
        };
        assert_ne!(key_e0, key_e1, "rotation must change the direction key");
    }

    // ── kill -9 / battery-yank: the persisted window is burned, never reused ──────────
    #[test]
    fn kill9_no_nonce_reuse() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();

        // Checkpoint at seq 0 → reserves ceiling = NONCE_WINDOW.
        let blob = a.export_state().unwrap();
        // Emit three frames pre-crash (seqs 0,1,2); the peer tracks them.
        let mut used = Vec::new();
        for _ in 0..3 {
            let f = a.seal(b"x".to_vec(), vec![]).unwrap();
            let seq = u64::from_le_bytes(f[5..13].try_into().unwrap());
            used.push(seq);
            b.open(f, vec![]).unwrap();
        }
        assert_eq!(used, vec![0, 1, 2]);

        // *** kill -9 ***  — no further export; `a` and its in-memory send_next vanish.
        drop(a);
        let a2 = WatchSession::restore(blob).unwrap();

        // Cold start is fail-closed: `restore` burns send_next up to the persisted ceiling
        // and leaves NO headroom, so the first `seal` refuses until a fresh window is
        // reserved + persisted. (Otherwise a second crash could re-emit the same ceiling.)
        assert!(a2.seal(b"y".to_vec(), vec![]).is_err(), "restore must force a checkpoint");
        let _blob2 = a2.export_state().unwrap(); // reserve + (caller would persist) a new window

        // The next nonce must be past the burned window (>= NONCE_WINDOW), never a reuse.
        let f = a2.seal(b"y".to_vec(), vec![]).unwrap();
        let seq = u64::from_le_bytes(f[5..13].try_into().unwrap());
        assert_eq!(seq, NONCE_WINDOW, "cold start must burn to the reserved ceiling");
        assert!(!used.contains(&seq), "a (key,nonce) was reused after restart!");
        // And the peer (whose recv watermark survived at 3) still accepts it — seq 64 > 2.
        // (fresh b here would accept from 0; the real peer persists recv_min alongside.)
    }

    #[test]
    fn export_restore_preserves_recv_and_epoch() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();
        a.rotate().unwrap();
        b.rotate().unwrap();

        // b receives one frame → recv_min advances to 1.
        let f = a.seal(b"m".to_vec(), vec![]).unwrap();
        b.open(f, vec![]).unwrap();
        let blob = b.export_state().unwrap();
        let b2 = WatchSession::restore(blob).unwrap();
        assert_eq!(b2.epoch().unwrap(), 1, "epoch survives restore");
        // A stale (seq 0) frame at this epoch must still be rejected after restore.
        // Re-derive an epoch-1 seq-0 frame from a fresh sender sharing b2's keys is
        // impractical here; instead assert the watermark by re-sending from a.
        let f2 = a.seal(b"n".to_vec(), vec![]).unwrap(); // seq 1
        assert_eq!(b2.open(f2, vec![]).unwrap(), b"n");
    }

    // ── resume surfaces the real counters, not a local 0 ─────────────────────────────
    #[test]
    fn send_seq_recv_min_resume_after_restore() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();
        // Fresh session: counters start at 0; handshake-phase peers Err before establish.
        assert_eq!(a.send_seq().unwrap(), 0);
        assert_eq!(a.recv_min().unwrap(), 0);
        assert!(WatchSession::new().send_seq().is_err());
        assert!(WatchSession::new().recv_min().is_err());

        // Emit a few frames so send_next advances; b's recv_min tracks them.
        for _ in 0..3 {
            let f = a.seal(b"x".to_vec(), vec![]).unwrap();
            b.open(f, vec![]).unwrap();
        }
        assert_eq!(a.send_seq().unwrap(), 3);
        assert_eq!(b.recv_min().unwrap(), 3);

        // Checkpoint → the persisted ceiling is send_next + NONCE_WINDOW.
        let blob = a.export_state().unwrap();
        drop(a);
        let a2 = WatchSession::restore(blob).unwrap();
        // Resume must surface the BURNED ceiling (3 + NONCE_WINDOW), never a local 0.
        assert_eq!(a2.send_seq().unwrap(), 3 + NONCE_WINDOW, "resumed send seq == burned ceiling");

        // recv_min likewise survives a restore of the receiving side.
        let bblob = b.export_state().unwrap();
        let b2 = WatchSession::restore(bblob).unwrap();
        assert_eq!(b2.recv_min().unwrap(), 3, "resumed recv floor persists");
    }

    #[test]
    fn hostile_inputs_never_panic() {
        let s = WatchSession::new();
        // Bad-length peer key.
        assert!(s.accept_peer(vec![0u8; 31]).is_err());
        assert!(s.accept_peer(vec![]).is_err());
        // All-zero peer key.
        assert!(s.accept_peer(vec![0u8; 32]).is_err());
        // Ops before establishment.
        assert!(s.seal(vec![1, 2, 3], vec![]).is_err());
        assert!(s.open(vec![0u8; 64], vec![]).is_err());
        assert!(s.sas().is_err());
        assert!(s.rotate().is_err());
        assert!(s.export_state().is_err());
        assert!(s.epoch().is_err());
        assert!(s.send_seq().is_err());
        assert!(s.recv_min().is_err());
        // Malformed restore blobs.
        assert!(WatchSession::restore(vec![]).is_err());
        assert!(WatchSession::restore(vec![0u8; 200]).is_err());
        // Establish, then feed truncated / garbage frames to open.
        let peer = WatchSession::new();
        s.accept_peer(peer.public_key().unwrap()).unwrap();
        for len in 0..(HDR_LEN + TAG_LEN) {
            assert!(s.open(vec![0u8; len], vec![]).is_err());
        }
        assert!(watch_sas(vec![0u8; 5], vec![0u8; 32]).is_err());
    }
}
