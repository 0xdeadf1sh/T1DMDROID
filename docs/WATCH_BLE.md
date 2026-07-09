# T1DM Watch — BLE Protocol Reference

Protocol documentation for the **T1DM companion watch**, an optional ESP32-C3
wrist peripheral that receives a periodic, encrypted glucose glance from the
phone. The phone is the sole data source; the watch never sends glucose data
back. The accessory is optional — the phone application is fully functional
without it.

This document is transport- and platform-neutral: it describes the BLE
advertisement, the custom GATT service map, the pairing handshake, the
application-layer cryptography, and the wire layout of the 5-minute push. It
does not assume any particular BLE stack, and is intended to be sufficient to
implement the firmware side from this document alone.

All multi-byte integers are **little-endian** unless stated otherwise. Glucose
values are in **mg/dL**; convert with `mmol/L = mg/dL / 18.0`. Byte strings are
written in hex, most-significant byte first as printed.

---

## Contents

> **Reading guide.** The link has two planes: a one-time **pairing** plane
> (§4–§5) that establishes keys, and a recurring **push** plane (§6) that
> delivers the glance. To build a receiver you need §2 (GATT map), §4–§5
> (handshake + crypto) to obtain keys, and §6 (the sealed push). §9 lists the
> constants and the golden vectors that gate the shared implementation.

| § | Section | Needed for |
|---|---|---|
| 1 | [Device identity](#1-device-identity) | discovery |
| 2 | [GATT service map](#2-gatt-service-map) | all |
| 3 | [Roles and data flow](#3-roles-and-data-flow) | orientation |
| 4 | [Pairing handshake](#4-pairing-handshake) | keys |
| 5 | [Cryptography](#5-cryptography) | keys |
| 6 | [The 5-minute push](#6-the-5-minute-push) | **the glance** |
| 7 | [Control frames](#7-control-frames) | liveness / errors |
| 8 | [Key management and recovery](#8-key-management-and-recovery) | rotation / reset / desync |
| 9 | [Reference constants and golden vectors](#9-reference-constants-and-golden-vectors) | CI gate |

---

## 1. Device identity

| Field | Value / meaning |
|---|---|
| Peripheral | ESP32-C3 (hardware AES / SHA / TRNG) |
| Advertised name | `T1DM-Watch-<id>` — the phone matches by the **`T1DM-Watch` prefix**, not by address |
| BLE address | LE random (may rotate); match by name prefix |
| Role | GATT **peripheral**; the phone is the GATT **central** |
| Bond | LESC bond (numeric-comparison or Just Works) as defence-in-depth; the app-layer AEAD (§5) is the source of truth |

The watch advertises the custom service UUID (§2) and its local name. The phone
scans for the name prefix, connects, and drives the whole session. The link is a
**connected GATT session** and requires the phone's `BLUETOOTH_CONNECT`
permission — distinct from the passive, `neverForLocation` `BLUETOOTH_SCAN` used
to read the CGM advertisement.

---

## 2. GATT service map

One primary custom service. The service and its characteristics share a 128-bit
base and differ only in the first 32-bit group.

**Service** `7ed10000-c0de-4a7c-9b0d-1d0a7a7c0f01`

| Characteristic | UUID | Properties | Role |
|---|---|---|---|
| KEX | `7ed10001-c0de-4a7c-9b0d-1d0a7a7c0f01` | write (with response) | phone → watch: HELLO, CONFIRM, UNPAIR (§4, §8) |
| CONTROL | `7ed10002-c0de-4a7c-9b0d-1d0a7a7c0f01` | notify | watch → phone: HELLO_ACK, CONFIRM_ACK, PUSH_ACK, ERR_* (§7) |
| PUSH | `7ed10003-c0de-4a7c-9b0d-1d0a7a7c0f01` | write (without response) | phone → watch: sealed glance frames (§6) |
| STATUS | `7ed10004-c0de-4a7c-9b0d-1d0a7a7c0f01` | read | identity / epoch block, read on discovery (§8) |

CONTROL notifications are subscribed by writing the standard CCCD
`00002902-0000-1000-8000-00805f9b34fb`.

**Bring-up sequence (central).** Scan by name prefix → connect → negotiate MTU
(target **247**) → discover the service → subscribe CONTROL → read STATUS. A
single sealed push (§6.1) plus its 20-byte glance fits comfortably inside one
247-byte MTU with no fragmentation.

**STATUS block** (read): `[u8 proto][u8 epoch][u8 flags][8B watch-id]…`. `epoch`
is the low byte of the session epoch (§5); the central compares it against its
own live session to detect a reflash / desync (§8).

---

## 3. Roles and data flow

- Data flows **phone → watch only**. The watch never transmits glucose data.
- The phone pushes one glance **every 5 minutes**, aligned to the same 5-minute
  grid the rest of the system uses.
- The push **suspends** while the phone is in low-power / battery-saver mode; the
  final frame before suspension sets the `LOW_POWER` status bit (§6.3) so the
  frozen glance is expected rather than read as a fault.
- The only watch → phone traffic is the CONTROL plane: handshake
  acknowledgements, an optional `PUSH_ACK`, and error frames.

The cryptographic state machine (§5) is symmetric — each side has an independent
send key and receive key — so the optional `PUSH_ACK` return path is sealed with
the watch's own send key. Glucose payloads still travel one way only.

---

## 4. Pairing handshake

Keys are established once, on first pair, and confirmed out-of-band by a **Short
Authentication String (SAS)** the user compares on both screens. This is an
SAS-authenticated key agreement: the SAS is the integrity check on the
public-key exchange, defeating a man-in-the-middle on first pair.

```
  phone (central)                        watch (peripheral)
  ───────────────                        ──────────────────
  (mint ephemeral X25519 keypair)
  HELLO      ─▶ KEX                       (mint ephemeral X25519 keypair)
                            CONTROL ◀─  HELLO_ACK
  [both run ECDH + derive keys + compute the SAS (§5)]
  ── SAS shown on BOTH screens; the user compares and confirms ──
  CONFIRM    ─▶ KEX
                            CONTROL ◀─  CONFIRM_ACK
  [session LIVE — epoch-0 keys usable; the push (§6) begins]
```

Handshake frames are **not** AEAD-sealed (they bootstrap the keys); their
integrity rests on the SAS comparison, not on a MAC. Each frame is
`[u8 type][u8 proto][…body…]`:

| Frame | Dir | type | Body |
|---|---|---|---|
| HELLO | phone → watch (KEX) | `0x01` | `[u8 epoch][32B phone X25519 public]` |
| HELLO_ACK | watch → phone (CONTROL) | `0x02` | `[u8 epoch][32B watch X25519 public]` |
| CONFIRM | phone → watch (KEX) | `0x03` | `[u8 epoch][u8 ok]` |
| CONFIRM_ACK | watch → phone (CONTROL) | `0x04` | `[u8 epoch][u8 ok]` |

`epoch` in the handshake is the low byte of the session epoch (0 on first pair,
incremented by rotation, §8). A frame whose `proto` or `epoch` does not match the
in-flight handshake is rejected and the pairing is restarted.

The 32-byte public keys are raw X25519 (Curve25519) public keys, little-endian
u-coordinates per RFC 7748.

---

## 5. Cryptography

The link is protected by **application-layer AES-128-GCM AEAD**, which is
authoritative; the LESC bond underneath is defence-in-depth only. The suite is
**versioned** — both the frame version byte (§6.1) and the domain-separation
strings below pin it to *suite v1*.

### 5.1 Key agreement — X25519

Each side mints one ephemeral X25519 keypair per session (from a CSPRNG / the
C3's TRNG) and exchanges the 32-byte public key in HELLO / HELLO_ACK (§4). The
shared secret is `dh = X25519(our_secret, peer_public)`.

A **contributory-behaviour** check is mandatory: reject the handshake if `dh` is
the all-zero output (a small-order or malicious peer key), if the peer key is
all-zero, or if the peer key equals our own.

### 5.2 Key derivation — HKDF-SHA256

Let `pk_low` and `pk_high` be the two public keys sorted as **byte strings**
(`pk_low ≤ pk_high`); this canonical ordering is order-independent and needs no
negotiated role bit. Define the side whose own public key is `pk_low` as **A**
and the other as **B**.

```
root_0 = HKDF-SHA256-Expand( PRK = HKDF-SHA256-Extract(salt = SALT, ikm = dh),
                             info = INFO_ROOT, L = 32 )

k_A2B  = HKDF-SHA256-Expand( PRK = root_e, info = INFO_A2B || pk_low || pk_high, L = 16 )
k_B2A  = HKDF-SHA256-Expand( PRK = root_e, info = INFO_B2A || pk_low || pk_high, L = 16 )
```

Both public keys are bound into the per-direction `info` (transcript binding →
no unknown-key-share). Direction assignment:

| Side | send key | receive key |
|---|---|---|
| A (own key = `pk_low`) | `k_A2B` | `k_B2A` |
| B (own key = `pk_high`) | `k_B2A` | `k_A2B` |

Because the two directions use different keys, `(key, nonce)` uniqueness across
directions is automatic. `root_e` is the epoch root (`root_0` at epoch 0); the
manual ratchet (§8) advances it.

Constants (ASCII, no trailing NUL):

| Name | Value |
|---|---|
| `SALT` | `t1dm-watch/x25519/hkdf-sha256/aes128gcm/v1` |
| `INFO_ROOT` | `t1dm-watch root v1` |
| `INFO_A2B` | `t1dm-watch key A->B v1` |
| `INFO_B2A` | `t1dm-watch key B->A v1` |
| `INFO_RATCHET` | `t1dm-watch ratchet v1` |
| `SAS_INFO` | `t1dm-watch sas v1` |

### 5.3 SAS — the confirmation string

A 6-digit decimal code, deterministic and order-independent:

```
d   = SHA-256( SAS_INFO || pk_low || pk_high )
sas = ( u32_be(d[0..4]) mod 1_000_000 )         formatted as 6 zero-padded digits
```

Both sides compute the identical string from the public keys alone. It is
displayed for the user to compare; confirmation gates the promotion of the keys
to LIVE.

### 5.4 Record AEAD — AES-128-GCM

Every sealed record uses AES-128-GCM with the sender's 16-byte direction key, a
12-byte nonce, and a 16-byte tag:

```
nonce = epoch : u32_le || seq : u64_le                 (4 + 8 = 12 bytes)
```

`seq` is a per-epoch, per-direction monotone counter starting at 0. The 13-byte
cleartext frame header (§6.1) is fed as the AEAD **associated data** (optionally
followed by extra caller context, empty for the push), so `version`, `epoch` and
`seq` are authenticated and cannot be mauled.

### 5.5 Nonce discipline — monotone, windowed, burned

No `(key, nonce)` pair is ever reused, even across a crash or battery yank:

- The sender emits strictly increasing `seq` within an epoch.
- The sender reserves a **window** of `NONCE_WINDOW = 64` sequence numbers and
  durably persists the reserved ceiling *before* using any seq in the window.
- On cold start the sender resumes at the persisted ceiling with **no
  headroom** — the first seal refuses until a fresh window is reserved and
  persisted. This "window burn" guarantees the next nonce is strictly past
  anything that could have been emitted before the crash.
- The receiver keeps a strictly-increasing watermark `recv_min` (the smallest
  acceptable `seq`); a frame with `seq < recv_min` is a replay / reorder and is
  rejected. Gaps (dropped frames) are tolerated. `recv_min` advances only after
  the tag verifies.

A firmware author implementing an AEAD send direction (e.g. `PUSH_ACK`) must
apply the same windowed-burn discipline to its own counter.

---

## 6. The 5-minute push

The glance is a compact record — current BG, trend, a one-line forecast summary
from the selected model, the alert band, and status bits. **No images.** It is
serialized to the plaintext of §6.2, sealed with the sender's send key (§5.4),
and written to the PUSH characteristic.

### 6.1 Sealed record (written to PUSH)

```
 off  type   field
  0   u8     version = 0x01
  1   u32    epoch                 (little-endian)
  5   u64    seq                   (little-endian; the windowed nonce counter)
 13   ..     ciphertext || 16-byte GCM tag
```

Bytes `[0..13)` are the cleartext header **and** the AEAD associated data
(§5.4). The receiver reconstructs the nonce as `epoch:u32_le || seq:u64_le`,
selects its receive key (§5.2), verifies the tag, enforces `seq ≥ recv_min`
(§5.5), and on success decrypts the glance and advances `recv_min`.

The ciphertext length equals the glance plaintext length (AES-GCM is a stream
cipher under the hood); total record length is `13 + plaintext_len + 16`.

### 6.2 Glance plaintext

```
 off  type  field                 notes
  0   u8    payload_version = 0x01
  1   u8    status_bits           §6.3
  2   i16   bg_mgdl               -1 = no reading
  4   i16   trend_tenths          rate in 0.1 mg/dL/min; 0x8000 = none
  6   u8    alert_band            0..4 (§6.4); 0xFF = none
  7   u8    forecast_status       0..4 (§6.5); 0xFF = none
  8   i16   fc_end_mgdl           selected-model median BG at horizon end; -1 = none
 10   u8    fc_horizon_steps      horizon length in 5-min steps (e.g. 24 = 120 min)
 11   u8    fc_trend              0..4 (§6.6)
 12   u32   reading_age_s         seconds since the last MEASURED reading
 16   u8    summary_len N         N ≤ 40
 17  ..N    summary               UTF-8 one-line forecast summary (selected model)
```

Total glance length is `17 + N` bytes.

### 6.3 Status bits

| Bit | Mask | Meaning |
|---|---|---|
| 0 | `0x01` | `LOW_POWER` — the phone has suspended the 5-min scheduler |
| 1 | `0x02` | `STALE` — the reading is older than the freshness window |
| 2 | `0x04` | `SIGNAL_LOSS` — no measured reading within the loss window |
| 3 | `0x08` | `WARMUP` — the forecast is withheld (collecting context) |
| 4 | `0x10` | `PREDICTED_LOW` — the selected forecast crosses the low threshold |
| 5 | `0x20` | `PREDICTED_HIGH` — the selected forecast crosses the high threshold |
| 6 | `0x40` | `ALARM` — a model-free alarm is active |
| 7 | `0x80` | `FORECAST_UNAVAILABLE` — no eligible forecast (degenerate / none) |

### 6.4 `alert_band`

`0 = URGENT_LOW`, `1 = LOW`, `2 = IN_RANGE`, `3 = HIGH`, `4 = URGENT_HIGH`.

### 6.5 `forecast_status`

`0 = OK`, `1 = NON_FINITE`, `2 = RAIL_PINNED`, `3 = COLLAPSED_BAND`,
`4 = MISORDERED_QUANTILES`. Only `OK` is eligible to drive the glance forecast.

### 6.6 `fc_trend`

`0 = FLAT`, `1 = RISING`, `2 = FALLING`, `3 = RISING_FAST`, `4 = FALLING_FAST`.

---

## 7. Control frames

Watch → phone, on the CONTROL notify characteristic. Each is
`[u8 type][u8 proto][…]`:

| Frame | type | Body | Meaning |
|---|---|---|---|
| HELLO_ACK | `0x02` | `[u8 epoch][32B public]` | handshake step 2 (§4) |
| CONFIRM_ACK | `0x04` | `[u8 epoch][u8 ok]` | handshake step 4 (§4) |
| PUSH_ACK | `0x20` | `[u8 epoch][u32 seq]` | (optional) the watch decrypted the push with this seq |
| ERR_EPOCH | `0x10` | `[u8 watch_epoch]` | epoch mismatch — force re-pair (§8) |
| ERR_AUTH | `0x11` | `[u8 epoch]` | AEAD open failed — force re-pair (§8) |

`PUSH_ACK` is a liveness and seq-audit aid; it is optional and the push does not
block on it.

---

## 8. Key management and recovery

- **Rotation.** Manual key rotation advances the epoch root by one ratchet step
  (`root_{e+1} = HKDF-SHA256-Expand(PRK = root_e, info = INFO_RATCHET, L = 32)`),
  increments the epoch, re-derives both direction keys, and resets both
  counters. It is **forward-secret**: the previous root is wiped, so a
  compromise of the new root does not expose past traffic. The peer must ratchet
  in lockstep; a fresh SAS confirms the new epoch.
- **Reset / unpair.** The phone writes UNPAIR (`type 0x06`, body `[u8 epoch]`) to
  KEX; both sides wipe key material and counters. Each side mints a fresh
  keypair and returns to the unpaired state; the link must be re-paired (§4).
- **Window burn (nonce persistence).** Both sides persist their per-epoch send
  ceiling and, on restart, resume strictly above it (§5.5), so no `(key, nonce)`
  is reused across process death or a battery yank.
- **Epoch desync / reflash.** A reflashed watch presents a different `epoch` in
  its STATUS block (read on discovery), or emits `ERR_EPOCH` / `ERR_AUTH`.
  Either condition forces a full re-pair: the phone wipes local key material and
  counters and returns to the pairing flow.
- **Reconnect.** A dropped link reconnects with exponential backoff while a
  pairing exists and the accessory is enabled.

---

## 9. Reference constants and golden vectors

### 9.1 Constants

- **Key agreement:** X25519 (Curve25519, RFC 7748).
- **KDF:** HKDF-SHA256 (RFC 5869); `SALT`, `INFO_*` per §5.2.
- **AEAD:** AES-128-GCM, 16-byte key, **12-byte nonce = `epoch:u32_le ‖
  seq:u64_le`**, 16-byte tag.
- **Per-direction keys:** `k_A2B` / `k_B2A`; A = the side whose public key sorts
  lower as a byte string.
- **Frame version byte:** `0x01`. **Record header (AAD):** `version(1) ‖
  epoch(u32_le) ‖ seq(u64_le)` = 13 bytes.
- **SAS:** 6 decimal digits, `u32_be(SHA-256(SAS_INFO ‖ pk_low ‖ pk_high)[0..4])
  mod 10⁶`; order-independent.
- **Nonce window:** `NONCE_WINDOW = 64`; monotone `seq` per epoch, resume
  strictly above the persisted ceiling.
- **MTU target:** 247. **Advertised-name prefix:** `T1DM-Watch`.
- **Glance plaintext:** 17-byte fixed head + N-byte summary (N ≤ 40); sentinels
  `bg = -1`, `trend = 0x8000`, `band = 0xFF`, `forecast_status = 0xFF`,
  `fc_end = -1`.

### 9.2 X25519 known-answer (RFC 7748 §5.2)

```
scalar = a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4
u      = e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c
X25519(scalar, u) = c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552
```

### 9.3 HKDF-SHA256 known-answer (RFC 5869 Test Case 1)

```
IKM  = 0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b   (22 bytes)
salt = 000102030405060708090a0b0c                     (13 bytes)
info = f0f1f2f3f4f5f6f7f8f9                            (10 bytes)
L    = 42
OKM  = 3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf
       34007208d5b887185865
```

### 9.4 AES-128-GCM known-answer

12-byte nonce, tag appended to the ciphertext:

```
key       = 000102030405060708090a0b0c0d0e0f
nonce     = 101112131415161718191a1b
aad       = feedfacedeadbeef
plaintext = 48656c6c6f2c20776174636821                 ("Hello, watch!")
ct || tag = 8c4b6fc36063969876a93e9de6265a21d754cb10add2e5c59b74c78fe3
```

### 9.5 SAS determinism and symmetry

```
pk_a = 7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13
pk_b = 0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20
SAS(pk_a, pk_b) = SAS(pk_b, pk_a) = 013208
```

(Here `pk_b < pk_a` as byte strings, so `pk_low = pk_b`, `pk_high = pk_a`.)

### 9.6 Worked sealed push

Two fixed X25519 secrets drive a full handshake, then session **a** seals one
record at epoch 0, seq 0, with empty caller AAD; session **b** opens it.

```
secret_a  = 1111111111111111111111111111111111111111111111111111111111111111
secret_b  = 2222222222222222222222222222222222222222222222222222222222222222
public_a  = 7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13
public_b  = 0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20
```

`public_b < public_a`, so `pk_low = public_b`, `pk_high = public_a`; session
**b** is side A (its key is `pk_low`), session **a** is side B. The derived
material and the sealed frame:

```
SAS         = 013208
root (e=0)  = 0be77e9a87b1e44a64c930b5d5447269e953d7a9fe12c6d391d65cc77bbd8a8b
a_send_key  = 073d0e97b2b5208f5604905dfd12528b
              ( = HKDF-Expand(PRK = root, info = INFO_B2A || pk_low || pk_high, L = 16),
                because session a is side B )

plaintext   = 01730580620a4f4b2c66635f656e64403435            (18 bytes)
aad         = (empty)
frame       = 01 00000000 0000000000000000
              142a05b0d197782e62c52722ca05bfcd79d79a2428685774eba1acecf5297964d17e
```

The frame decomposes as `version=01`, `epoch=00000000`, `seq=0000000000000000`,
then the 34-byte `ciphertext || tag` (18-byte ciphertext + 16-byte tag). Session
**b** (holding the matching receive key) decrypts it back to `plaintext` and
advances its receive watermark to 1.

These vectors — the X25519 and HKDF known-answers, the AES-128-GCM known-answer,
the SAS, and the worked sealed push — gate the shared firmware/app
implementation and are maintained alongside the cryptographic core's test suite.
