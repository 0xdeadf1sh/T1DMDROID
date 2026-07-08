# LinX / AiDEX X CGM — BLE Protocol Reference

Protocol documentation for the **Microtech / Ottai AiDEX X** continuous glucose
monitor, sold in Europe as **LinX** (Type `GX-01S`). Reconstructed from
Juggluco's open-source `aidexx` module and verified against live hardware and
captured byte vectors where noted.

This document is transport- and platform-neutral: it describes the BLE
advertisement layout and its CRC32, which is everything needed to listen for
glucose passively. It does not assume any particular BLE stack.

All multi-byte integers are **little-endian** unless stated otherwise.
Glucose values are in **mg/dL**; convert with `mmol/L = mg/dL / 18.0`.

---

## Contents

> **Scope.** This reference documents the sensor's glucose broadcast. §1 and §3
> are self-contained — identity plus the advertisement decode with its CRC — and
> are all a reader needs.

| § | Section |
|---|---|
| 1 | [Device identity](#1-device-identity) |
| 2 | [How glucose is read](#2-how-glucose-is-read) |
| 3 | [Passive broadcast (advertisement)](#3-passive-broadcast-advertisement) |
| 4 | [Reference constants](#4-reference-constants) |

**Reading path: §1 → §3. Done.**

---

## 1. Device identity

| Field | Value / meaning |
|---|---|
| Sensor family | AiDEX X (Microtech/Ottai); EU brand "LinX" |
| Advertised name | `LinX-<SERIAL>` (other families: `AiDEX X-`, `Lumi-`, `Smart-`) |
| Serial | 10 chars, e.g. `22222C74D9`; equals the last 10 chars of the adv name |
| Rated life | ~14 days |
| BLE address | LE **random / resolvable** (rotates); match by name suffix, not address |
| Chip vendor | Silicon Labs (seen in HCI company id of the address) |

---

## 2. How glucose is read

The sensor continuously advertises the current value, the trend, and the two
previous minutes, in cleartext, protected by a CRC32. Reading it is therefore
stateless and entirely read-only: observe an advertisement, validate its CRC,
decode the payload (§3). Readings arrive for as long as the sensor advertises.

---

## 3. Passive broadcast (advertisement)

The connectable advertisement (observed length 62 bytes of AD payload) contains:

| AD structure | bytes | content |
|---|---|---|
| Flags | `02 01 06` | |
| Complete 16-bit Service UUIDs | `03 02 1F 18` | `0x181F` CGM Service |
| Manufacturer Specific Data | `17 FF 59 00 …` | company `0x0059` (listed as "Nordic"), 20-byte glucose payload |
| Complete Local Name | `13 09 "LinX-……"` | |
| Manufacturer Specific Data | `08 FF 59 00 …` | 7-byte secondary payload (not needed for glucose) |

### 3.1 Glucose payload (the 0x0059 manufacturer data, 20 bytes)

Offsets are within the manufacturer payload that begins right after the company
id `59 00`. This block is byte-for-byte:

```
struct LastPast {          // offset 0, 8 bytes
    u16 minfromstart;      //  0  minutes since sensor activation (the "id")
    u8  status;            //  2  0 = normal
    u8  calTemp;           //  3
    i8  trend;             //  4  rate-of-change in 0.1 mg/dL/min units
    u16 bitfield;          //  5  glucose:10 | unknown:5 | valid:1(bit15)
    u8  quality;           //  7
};
struct NextGlucose prev[2] {   // offset 8, 2 x 3 bytes: minute-1 and minute-2
    u16 bitfield;          //  glucose:10 | unknown:5 | valid:1(bit15)
    u8  quality;
};
u16 reserved;              // offset 14
u32 crc32;                 // offset 16
```

Decoded: `glucose_mgdl = bitfield & 0x3FF`, `valid = (bitfield >> 15) & 1`,
`rate = trend * 0.1` mg/dL/min. Timestamp of a value with index `id` =
`activation_epoch + id*60` seconds.

### 3.2 CRC32 validation

Let `P` = the 20-byte payload above.

```
seed = ( le32(P[0..3]) + le32(P[4..7]) + le32(P[8..11]) + le32(P[12..15]) ) mod 0x7FA777
crc  = crc32_normal(P[0..15], init=seed)          // MSB-first, poly 0x04C11DB7, no reflect, no final xor
valid_packet = (crc == le32(P[16..19]))
```

> **The four-word sum accumulates in a wrapping `uint32`** (mod 2³²) before the
> `mod 0x7FA777`. The golden vectors below never overflow 2³²; real advertisements
> routinely do, so a wider (full-precision) accumulator yields the wrong seed for them.

`crc32_normal` (bit-serial, big-endian bit order):

```
crc = init
for each byte b in buf:
    crc ^= b << 24
    repeat 8: crc = (crc & 0x80000000) ? ((crc<<1) ^ 0x04C11DB7) : (crc<<1)   // 32-bit
return crc
```

**Golden vector** (real advertisement): payload
`60 54 01 00 1F 5C 80 3E 54 80 3A 4E 80 36 00 00 7E AE DD 01` decodes to
`minfromstart=21600, trend=31, glucose=92 mg/dL (valid), prev=[84,78]`, and the
CRC32 = `0x01DDAE7E` matches.

**Live sample** (serial `22222C74D9`): payload
`8a 05 00 00 08 73 80 63 70 80 64 6e 80 63 00 00 a0 7d b6 66` →
`115 mg/dL (6.4 mmol/L), trend +0.8, prev 112/110, minfromstart=1418`, CRC ok.

Note: the sensor interleaves this 20-byte glucose advertisement (~once per
minute) with a shorter 5-byte `0x0059` status advertisement — filter on payload
length ≥ 20 **and** a passing CRC32.

---

## 4. Reference constants

- CRC32 (advertisement): poly `0x04C11DB7`, MSB-first, no reflect/xorout, seed
  from payload (§3.2).
- Glucose limits treated as valid: `18 … 800` mg/dL.
- `mmol/L = mg/dL / 18.0`; trend rate `= trend_field * 0.1` mg/dL/min.
