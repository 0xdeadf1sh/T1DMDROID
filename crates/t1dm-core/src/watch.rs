//! Watch-link crypto (SPEC §3), v1: X25519 ECDH, HKDF-SHA256->AES-128-GCM/direction, SAS anti-MITM.

use std::sync::{Arc, Mutex};

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes128Gcm, Key, Nonce};
use hkdf::Hkdf;
use rand_core::OsRng;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::CoreError;

const SALT: &[u8] = b"t1dm-watch/x25519/hkdf-sha256/aes128gcm/v1";
const INFO_ROOT: &[u8] = b"t1dm-watch root v1";
const INFO_A2B: &[u8] = b"t1dm-watch key A->B v1";
const INFO_B2A: &[u8] = b"t1dm-watch key B->A v1";
const INFO_RATCHET: &[u8] = b"t1dm-watch ratchet v1";
const SAS_INFO: &[u8] = b"t1dm-watch sas v1";

const FRAME_VER: u8 = 1;
const STATE_MAGIC: &[u8; 4] = b"T1WC";
const STATE_VER: u8 = 1;

/// export_state reserves seqs to send_next+NONCE_WINDOW; cold start burns unused window, no repeat.
const NONCE_WINDOW: u64 = 64;

const KEY_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;
/// `ver(1) || epoch(4) || seq(8)`, fed whole as AEAD AAD.
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

/// Which side of the canonical (lower,higher) pubkey order we are; fixes send/receive, no role bit.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Role {
    A,
    B,
}

struct Established {
    peer_public: [u8; X25519_LEN],
    role: Role,
    epoch: u32,
    root: [u8; 32],
    send_key: [u8; KEY_LEN],
    recv_key: [u8; KEY_LEN],
    send_next: u64,
    send_ceiling: u64,
    /// Replay guard: the smallest acceptable receive seq. Gaps are fine.
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
    Handshake,
    Established(Established),
}

struct Inner {
    /// Retained through the handshake so `accept_peer` and `sas` can run; replaced on `reset`.
    secret: StaticSecret,
    public: [u8; X25519_LEN],
    phase: Phase,
}

/// One per paired watch; `Arc`-shared, internally locked.
#[derive(uniffi::Object)]
pub struct WatchSession {
    inner: Mutex<Inner>,
}

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

fn derive_root(dh: &[u8; 32]) -> Result<[u8; 32], CoreError> {
    let hk = Hkdf::<Sha256>::new(Some(SALT), dh);
    let mut root = [0u8; 32];
    hk.expand(INFO_ROOT, &mut root)
        .map_err(|_| internal("hkdf root expand"))?;
    Ok(root)
}

/// Binding both pubkeys pins the key to the exact handshake.
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

fn ratchet_root(root: &[u8; 32]) -> Result<[u8; 32], CoreError> {
    let hk = Hkdf::<Sha256>::from_prk(root).map_err(|_| internal("hkdf ratchet from_prk"))?;
    let mut next = [0u8; 32];
    hk.expand(INFO_RATCHET, &mut next)
        .map_err(|_| internal("hkdf ratchet expand"))?;
    Ok(next)
}

/// Returns `(send_key, recv_key)` for `role`.
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

#[uniffi::export]
impl WatchSession {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret).to_bytes();
        Arc::new(Self {
            inner: Mutex::new(Inner { secret, public, phase: Phase::Handshake }),
        })
    }

    /// Restore burns send window: send_next jumps to ceiling; export_state must checkpoint first.
    #[uniffi::constructor]
    pub fn restore(state: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        // magic(4) ver(1) role(1) epoch(4) ceiling(8) recv_min(8) root(32) sec(32) pub(32) peer(32)
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

    /// Send to the peer as HELLO.
    pub fn public_key(&self) -> Result<Vec<u8>, CoreError> {
        Ok(self.lock()?.public.to_vec())
    }

    /// 0 after the first `accept_peer`, +1 per `rotate`. `Err` while still in handshake.
    pub fn epoch(&self) -> Result<u32, CoreError> {
        match &self.lock()?.phase {
            Phase::Established(e) => Ok(e.epoch),
            Phase::Handshake => Err(dec("no session: still in handshake")),
        }
    }

    pub fn is_established(&self) -> Result<bool, CoreError> {
        Ok(matches!(self.lock()?.phase, Phase::Established(_)))
    }

    /// After `restore` this is the burned ceiling, not a local 0. `Err` while still in handshake.
    pub fn send_seq(&self) -> Result<u64, CoreError> {
        match &self.lock()?.phase {
            Phase::Established(e) => Ok(e.send_next),
            Phase::Handshake => Err(dec("no session: still in handshake")),
        }
    }

    /// The smallest seq `open` still accepts. `Err` while still in handshake.
    pub fn recv_min(&self) -> Result<u64, CoreError> {
        match &self.lock()?.phase {
            Phase::Established(e) => Ok(e.recv_min),
            Phase::Handshake => Err(dec("no session: still in handshake")),
        }
    }

    /// ECDH -> epoch-0 root, both keys, armed counters; re-accept RESETS to epoch 0, use rotate.
    pub fn accept_peer(&self, peer_public: Vec<u8>) -> Result<(), CoreError> {
        let peer = to_arr32(&peer_public, "peer public")?;
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
        // A small-order peer point would key off a known value.
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
            // In-memory until `export_state` persists one.
            send_ceiling: NONCE_WINDOW,
            recv_min: 0,
        });
        Ok(())
    }

    /// `Err` until `accept_peer`; the peer key is one of its two inputs.
    pub fn sas(&self) -> Result<String, CoreError> {
        let inner = self.lock()?;
        match &inner.phase {
            Phase::Established(e) => Ok(compute_sas(&inner.public, &e.peer_public)),
            Phase::Handshake => Err(dec("no SAS: peer key not yet accepted")),
        }
    }

    /// Seal send: ver||epoch||seq||ct||tag; aad authenticated, not sent; refuses past window.
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

    /// Enforces frame version, epoch, strictly-increasing seq, GCM tag; aad must match sender's.
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

    /// Ratchets root one epoch, re-derives keys, resets counters; zeroizes old root; peer follows.
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

    /// Discard all derived secrets and mint a fresh keypair; the link must then be re-paired.
    pub fn reset(&self) -> Result<(), CoreError> {
        let mut inner = self.lock()?;
        let secret = StaticSecret::random_from_rng(OsRng);
        inner.public = PublicKey::from(&secret).to_bytes();
        inner.secret = secret; // the old secret zeroizes on drop
        inner.phase = Phase::Handshake;
        Ok(())
    }

    /// Serializes session, reserves a send window; caller MUST persist before seals; Kotlin wraps.
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

/// Order-independent; `Err` on a non-32-byte key.
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
        assert_eq!(k["okm"].as_str().unwrap().len(), l * 2);
    }

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

    // The worked vector docs/WATCH_BLE.md publishes.
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

        let opened = b.open(frame, aad).unwrap();
        assert_eq!(opened, plaintext, "peer opens the worked frame");
    }

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
        // PUSH_ACK uses the other key pair.
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
        let mut bad = f0.clone();
        let last = bad.len() - 1;
        bad[last] ^= 0x01;
        assert!(b.open(bad, vec![]).is_err());
        assert!(b.open(f0.clone(), vec![0x99]).is_err());

        assert_eq!(b.open(f0.clone(), vec![]).unwrap(), b"hello");
        assert!(b.open(f0, vec![]).is_err());

        a.rotate().unwrap();
        let f_new = a.seal(b"e1".to_vec(), vec![]).unwrap();
        assert!(b.open(f_new.clone(), vec![]).is_err(), "epoch-desync must fail closed");
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

    #[test]
    fn kill9_no_nonce_reuse() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();

        let blob = a.export_state().unwrap();
        let mut used = Vec::new();
        for _ in 0..3 {
            let f = a.seal(b"x".to_vec(), vec![]).unwrap();
            let seq = u64::from_le_bytes(f[5..13].try_into().unwrap());
            used.push(seq);
            b.open(f, vec![]).unwrap();
        }
        assert_eq!(used, vec![0, 1, 2]);

        // kill -9: no further export, so `a`'s in-memory send_next vanishes.
        drop(a);
        let a2 = WatchSession::restore(blob).unwrap();

        assert!(a2.seal(b"y".to_vec(), vec![]).is_err(), "restore must force a checkpoint");
        let _blob2 = a2.export_state().unwrap(); // reserve a new window

        let f = a2.seal(b"y".to_vec(), vec![]).unwrap();
        let seq = u64::from_le_bytes(f[5..13].try_into().unwrap());
        assert_eq!(seq, NONCE_WINDOW, "cold start must burn to the reserved ceiling");
        assert!(!used.contains(&seq), "a (key,nonce) was reused after restart!");
    }

    #[test]
    fn export_restore_preserves_recv_and_epoch() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();
        a.rotate().unwrap();
        b.rotate().unwrap();

        let f = a.seal(b"m".to_vec(), vec![]).unwrap();
        b.open(f, vec![]).unwrap();
        let blob = b.export_state().unwrap();
        let b2 = WatchSession::restore(blob).unwrap();
        assert_eq!(b2.epoch().unwrap(), 1, "epoch survives restore");
        // Watermark asserted indirectly: re-deriving an epoch-1 seq-0 frame here is impractical.
        let f2 = a.seal(b"n".to_vec(), vec![]).unwrap(); // seq 1
        assert_eq!(b2.open(f2, vec![]).unwrap(), b"n");
    }

    #[test]
    fn send_seq_recv_min_resume_after_restore() {
        let a = WatchSession::new();
        let b = WatchSession::new();
        a.accept_peer(b.public_key().unwrap()).unwrap();
        b.accept_peer(a.public_key().unwrap()).unwrap();
        assert_eq!(a.send_seq().unwrap(), 0);
        assert_eq!(a.recv_min().unwrap(), 0);
        assert!(WatchSession::new().send_seq().is_err());
        assert!(WatchSession::new().recv_min().is_err());

        for _ in 0..3 {
            let f = a.seal(b"x".to_vec(), vec![]).unwrap();
            b.open(f, vec![]).unwrap();
        }
        assert_eq!(a.send_seq().unwrap(), 3);
        assert_eq!(b.recv_min().unwrap(), 3);

        let blob = a.export_state().unwrap();
        drop(a);
        let a2 = WatchSession::restore(blob).unwrap();
        assert_eq!(a2.send_seq().unwrap(), 3 + NONCE_WINDOW, "resumed send seq == burned ceiling");

        let bblob = b.export_state().unwrap();
        let b2 = WatchSession::restore(bblob).unwrap();
        assert_eq!(b2.recv_min().unwrap(), 3, "resumed recv floor persists");
    }

    #[test]
    fn hostile_inputs_never_panic() {
        let s = WatchSession::new();
        assert!(s.accept_peer(vec![0u8; 31]).is_err());
        assert!(s.accept_peer(vec![]).is_err());
        assert!(s.accept_peer(vec![0u8; 32]).is_err());
        assert!(s.seal(vec![1, 2, 3], vec![]).is_err());
        assert!(s.open(vec![0u8; 64], vec![]).is_err());
        assert!(s.sas().is_err());
        assert!(s.rotate().is_err());
        assert!(s.export_state().is_err());
        assert!(s.epoch().is_err());
        assert!(s.send_seq().is_err());
        assert!(s.recv_min().is_err());
        assert!(WatchSession::restore(vec![]).is_err());
        assert!(WatchSession::restore(vec![0u8; 200]).is_err());
        let peer = WatchSession::new();
        s.accept_peer(peer.public_key().unwrap()).unwrap();
        for len in 0..(HDR_LEN + TAG_LEN) {
            assert!(s.open(vec![0u8; len], vec![]).is_err());
        }
        assert!(watch_sas(vec![0u8; 5], vec![0u8; 32]).is_err());
    }
}
