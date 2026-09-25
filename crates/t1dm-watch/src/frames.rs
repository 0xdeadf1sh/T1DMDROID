//! Unsealed frames, SPEC/watch.md §2–§3, §6: the GATT map, STATUS, KEX and CONTROL.

use crate::{dec, Result};

pub const SERVICE_UUID: u128 = 0x7ed10000_c0de_4a7c_9b0d_1d0a7a7c0f01;
pub const KEX_UUID: u128 = 0x7ed10001_c0de_4a7c_9b0d_1d0a7a7c0f01;
pub const CONTROL_UUID: u128 = 0x7ed10002_c0de_4a7c_9b0d_1d0a7a7c0f01;
pub const PUSH_UUID: u128 = 0x7ed10003_c0de_4a7c_9b0d_1d0a7a7c0f01;
pub const STATUS_UUID: u128 = 0x7ed10004_c0de_4a7c_9b0d_1d0a7a7c0f01;

pub const PROTO: u8 = 0x01;
pub const NAME_PREFIX: &str = "T1DM-Watch";
pub const DEVICE_ID_LEN: usize = 8;
pub const STATUS_LEN: usize = 3 + DEVICE_ID_LEN;
pub const FLAG_EXTENDED: u8 = 0x01;
/// Record kinds 0x02–0x05 need a sealed record of up to `records::RECORD_MAX` in one write.
pub const EXTENDED_MIN_MTU: u16 = 247;

const TYPE_HELLO: u8 = 0x01;
const TYPE_HELLO_ACK: u8 = 0x02;
const TYPE_CONFIRM: u8 = 0x03;
const TYPE_CONFIRM_ACK: u8 = 0x04;
const TYPE_ERR_EPOCH: u8 = 0x10;
const TYPE_ERR_AUTH: u8 = 0x11;
const TYPE_PUSH_ACK: u8 = 0x20;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Status {
    pub proto: u8,
    pub epoch: u8,
    pub flags: u8,
    pub device_id: [u8; DEVICE_ID_LEN],
}

impl Status {
    pub fn extended(&self) -> bool {
        self.flags & FLAG_EXTENDED != 0
    }

    pub fn encode(&self) -> [u8; STATUS_LEN] {
        let mut out = [0u8; STATUS_LEN];
        out[0] = self.proto;
        out[1] = self.epoch;
        out[2] = self.flags;
        out[3..].copy_from_slice(&self.device_id);
        out
    }

    /// Trailing bytes are ignored.
    pub fn decode(b: &[u8]) -> Result<Self> {
        if b.len() < STATUS_LEN {
            return Err(dec(format!("STATUS: {} bytes, need {STATUS_LEN}", b.len())));
        }
        if b[0] != PROTO {
            return Err(dec(format!("STATUS: proto {}", b[0])));
        }
        Ok(Status { proto: b[0], epoch: b[1], flags: b[2], device_id: b[3..STATUS_LEN].try_into().unwrap() })
    }
}

pub fn advertised_name(device_id: &[u8; DEVICE_ID_LEN]) -> String {
    let mut s = String::with_capacity(NAME_PREFIX.len() + 9);
    s.push_str(NAME_PREFIX);
    s.push('-');
    for b in &device_id[..4] {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// Central → peripheral, on KEX.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kex {
    Hello { epoch: u8, public: [u8; 32] },
    Confirm { epoch: u8, ok: bool },
}

impl Kex {
    pub fn encode(&self) -> Vec<u8> {
        match *self {
            Kex::Hello { epoch, public } => {
                let mut v = vec![TYPE_HELLO, PROTO, epoch];
                v.extend_from_slice(&public);
                v
            }
            Kex::Confirm { epoch, ok } => vec![TYPE_CONFIRM, PROTO, epoch, ok as u8],
        }
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        let (ty, epoch) = header(b)?;
        match ty {
            TYPE_HELLO => Ok(Kex::Hello { epoch, public: key32(b)? }),
            TYPE_CONFIRM => Ok(Kex::Confirm { epoch, ok: byte(b, 3)? != 0 }),
            t => Err(dec(format!("KEX: unknown type {t:#04x}"))),
        }
    }
}

/// Peripheral → central, on CONTROL.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Control {
    HelloAck { epoch: u8, public: [u8; 32] },
    ConfirmAck { epoch: u8, ok: bool },
    ErrEpoch { epoch: u8 },
    ErrAuth { epoch: u8 },
    PushAck { epoch: u8, seq: u32 },
}

impl Control {
    pub fn encode(&self) -> Vec<u8> {
        match *self {
            Control::HelloAck { epoch, public } => {
                let mut v = vec![TYPE_HELLO_ACK, PROTO, epoch];
                v.extend_from_slice(&public);
                v
            }
            Control::ConfirmAck { epoch, ok } => vec![TYPE_CONFIRM_ACK, PROTO, epoch, ok as u8],
            Control::ErrEpoch { epoch } => vec![TYPE_ERR_EPOCH, PROTO, epoch],
            Control::ErrAuth { epoch } => vec![TYPE_ERR_AUTH, PROTO, epoch],
            Control::PushAck { epoch, seq } => {
                let mut v = vec![TYPE_PUSH_ACK, PROTO, epoch];
                v.extend_from_slice(&seq.to_le_bytes());
                v
            }
        }
    }

    pub fn decode(b: &[u8]) -> Result<Self> {
        let (ty, epoch) = header(b)?;
        match ty {
            TYPE_HELLO_ACK => Ok(Control::HelloAck { epoch, public: key32(b)? }),
            TYPE_CONFIRM_ACK => Ok(Control::ConfirmAck { epoch, ok: byte(b, 3)? != 0 }),
            TYPE_ERR_EPOCH => Ok(Control::ErrEpoch { epoch }),
            TYPE_ERR_AUTH => Ok(Control::ErrAuth { epoch }),
            TYPE_PUSH_ACK => {
                let s = b.get(3..7).ok_or_else(|| dec("PUSH_ACK: short"))?;
                Ok(Control::PushAck { epoch, seq: u32::from_le_bytes(s.try_into().unwrap()) })
            }
            t => Err(dec(format!("CONTROL: unknown type {t:#04x}"))),
        }
    }
}

fn header(b: &[u8]) -> Result<(u8, u8)> {
    if b.len() < 3 {
        return Err(dec(format!("frame: {} bytes", b.len())));
    }
    if b[1] != PROTO {
        return Err(dec(format!("frame: proto {}", b[1])));
    }
    Ok((b[0], b[2]))
}

fn byte(b: &[u8], i: usize) -> Result<u8> {
    b.get(i).copied().ok_or_else(|| dec("frame: short"))
}

fn key32(b: &[u8]) -> Result<[u8; 32]> {
    b.get(3..35)
        .and_then(|s| s.try_into().ok())
        .ok_or_else(|| dec("frame: public key short"))
}
