//! PLAN_T1DMDROID.md §5.9: ISO 15693 custom commands, manufacturer code 0x7a.
//! Android's NfcV stack appends the ISO 15693 FCS itself; these frames carry none.

use crate::crc::crc16_nfc;
use crate::ProvisionError;

/// High data rate, unaddressed: provisioning holds one tag in the field by ritual.
const FLAGS: u8 = 0x02;
const MFG_CODE: u8 = 0x7a;
const CMD_PATCH_INFO: u8 = 0xa1;
const CMD_ACTIVATE: u8 = 0xa0;
const CMD_SWITCH: u8 = 0xa8;

/// §5.9: `0x01` in patch-info byte 17 = not activated.
const SENSOR_STATE_NOT_ACTIVATED: u8 = 0x01;

/// §5.9 clean-case reply: flags, echo, status, then fields — 19 bytes raw, 17 normalized.
const SWITCH_RESPONSE_LEN: usize = 19;

/// Which command the patch-info state byte calls for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ProvisionAction {
    /// Fresh sensor: `0xa0`. Starts the wear clock; irreversible.
    Activate,
    /// Activated sensor: `0xa8`. Mints a fresh BLE PIN, ownership undisturbed.
    SwitchReceiver,
}

/// §5.9 state rule, kept here so the Kotlin side never re-derives it.
pub fn provision_action(sensor_state: u8) -> ProvisionAction {
    if sensor_state == SENSOR_STATE_NOT_ACTIVATED {
        ProvisionAction::Activate
    } else {
        ProvisionAction::SwitchReceiver
    }
}

/// `0xa1`, empty params.
pub fn patch_info_cmd() -> Vec<u8> {
    vec![FLAGS, CMD_PATCH_INFO, MFG_CODE]
}

/// `0xa0` / `0xa8`, body `timeSeconds_LE4 || receiverID_LE4 || crc16_LE2` (§5.9).
fn switch_family_cmd(cmd: u8, time_seconds: u32, receiver_id: u32) -> Vec<u8> {
    let mut body = Vec::with_capacity(10);
    body.extend_from_slice(&time_seconds.to_le_bytes());
    body.extend_from_slice(&receiver_id.to_le_bytes());
    let crc = crc16_nfc(&body);
    body.extend_from_slice(&crc.to_le_bytes());
    let mut frame = Vec::with_capacity(3 + body.len());
    frame.extend_from_slice(&[FLAGS, cmd, MFG_CODE]);
    frame.extend_from_slice(&body);
    frame
}

pub fn activate_cmd(time_seconds: u32, receiver_id: u32) -> Vec<u8> {
    switch_family_cmd(CMD_ACTIVATE, time_seconds, receiver_id)
}

pub fn switch_cmd(time_seconds: u32, receiver_id: u32) -> Vec<u8> {
    switch_family_cmd(CMD_SWITCH, time_seconds, receiver_id)
}

/// Patch info, offsets into the flags-stripped ("normalized") response (§5.9).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3PatchInfo {
    pub wear_duration_min: u16,
    pub raw_status: u8,
    /// Display order is the wire order: `b0.b1.b2.b3`.
    pub fw: Vec<u8>,
    pub sensor_state: u8,
    /// 9 ASCII bytes, verbatim.
    pub serial: String,
}

fn strip_flags(response: &[u8]) -> Result<&[u8], ProvisionError> {
    let flags = *response
        .first()
        .ok_or_else(|| ProvisionError::Malformed { reason: "empty response".into() })?;
    if flags & 0x01 != 0 {
        // ISO 15693 error flag; the error code follows at byte 1.
        return Err(match response.get(1) {
            Some(0xb1) => ProvisionError::NfcB1,
            Some(&code) => ProvisionError::Nfc { code },
            None => ProvisionError::Malformed { reason: "error flag, no code".into() },
        });
    }
    Ok(&response[1..])
}

/// Live EU 3 Plus tap: the reply is flags `00`, then a run of `0xa5` command echoes (one in the
/// clean case, more when the tag repeats), then the payload whose §5.9 offsets count from there.
/// Neither the echo nor its length is documented; skipping the run is the only stable anchor.
fn normalize(response: &[u8]) -> Result<&[u8], ProvisionError> {
    let n = strip_flags(response)?;
    let mut i = 0;
    while i < n.len() && n[i] == 0xa5 {
        i += 1;
    }
    Ok(&n[i..])
}

pub fn parse_patch_info(response: &[u8]) -> Result<Libre3PatchInfo, ProvisionError> {
    let n = normalize(response)?;
    if n.len() < 27 {
        return Err(ProvisionError::Malformed {
            reason: format!("patch info wants 27 normalized bytes, got {}", n.len()),
        });
    }
    let serial = &n[16..25];
    if !serial.iter().all(|b| b.is_ascii_graphic()) {
        return Err(ProvisionError::Malformed { reason: "serial is not printable ASCII".into() });
    }
    Ok(Libre3PatchInfo {
        wear_duration_min: u16::from_le_bytes([n[7], n[8]]),
        raw_status: n[10],
        fw: n[11..15].to_vec(),
        sensor_state: n[15],
        serial: String::from_utf8_lossy(serial).into_owned(),
    })
}

/// `0xa0`/`0xa8` answer, §5.9 raw 19 B in the clean case (flags, echo, then fields).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Libre3SwitchResponse {
    /// Colon-joined, display order (wire reversed).
    pub ble_address: String,
    /// 4 bytes in wire order; Phase 5's tail4 reads them verbatim.
    pub ble_pin: Vec<u8>,
    pub activation_time_s: u32,
}

/// Normalized switch reply: status byte, then address, pin, time, crc — 17 bytes. The status
/// byte carries the sensor's refusal; 0xB1 is the documented account/region mismatch (§8).
pub fn parse_switch_response(response: &[u8]) -> Result<Libre3SwitchResponse, ProvisionError> {
    let n = normalize(response)?;
    if n.len() != SWITCH_RESPONSE_LEN - 2 {
        return Err(ProvisionError::Malformed {
            reason: format!(
                "switch response wants {} normalized bytes, got {}",
                SWITCH_RESPONSE_LEN - 2,
                n.len()
            ),
        });
    }
    if n[0] != 0x00 {
        return Err(match n[0] {
            0xb1 => ProvisionError::NfcB1,
            code => ProvisionError::Nfc { code },
        });
    }
    // Trailing CRC's coverage is undocumented; read, never enforced.
    Ok(Libre3SwitchResponse {
        ble_address: n[1..7]
            .iter()
            .rev()
            .map(|b| format!("{b:02X}"))
            .collect::<Vec<_>>()
            .join(":"),
        ble_pin: n[7..11].to_vec(),
        activation_time_s: u32::from_le_bytes([n[11], n[12], n[13], n[14]]),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const RECEIVER: u32 = 0x684fc53f;

    #[test]
    fn patch_info_frame() {
        assert_eq!(patch_info_cmd(), [0x02, 0xa1, 0x7a]);
    }

    #[test]
    fn activate_frame_assembly() {
        let f = activate_cmd(1, RECEIVER);
        assert_eq!(&f[..3], [0x02, 0xa0, 0x7a]);
        assert_eq!(&f[3..11], [0x01, 0x00, 0x00, 0x00, 0x3f, 0xc5, 0x4f, 0x68]);
        assert_eq!(f.len(), 13);
        let crc = crc16_nfc(&f[3..11]);
        assert_eq!(&f[11..13], crc.to_le_bytes());
    }

    #[test]
    fn switch_frame_assembly() {
        assert_eq!(&switch_cmd(1, RECEIVER)[..3], [0x02, 0xa8, 0x7a]);
    }

    #[test]
    fn action_rule() {
        assert_eq!(provision_action(0x01), ProvisionAction::Activate);
        assert_eq!(provision_action(0x02), ProvisionAction::SwitchReceiver);
    }

    /// Normalized patch-info payload: wear@7, status@10, fw@11, state@15, serial@16, crc@25.
    fn info_payload() -> Vec<u8> {
        let mut n = vec![0u8; 27];
        n[7] = 0x38;
        n[8] = 0x1b; // 6968 min
        n[10] = 0x07;
        n[11..15].copy_from_slice(&[1, 4, 2, 30]);
        n[15] = 0x02;
        n[16..25].copy_from_slice(b"A12345678");
        n
    }

    #[test]
    fn parses_patch_info() {
        let raw = [vec![0x00, 0xa5], info_payload()].concat();
        let info = parse_patch_info(&raw).unwrap();
        assert_eq!(info.wear_duration_min, 0x1b38);
        assert_eq!(info.raw_status, 0x07);
        assert_eq!(info.fw, [1, 4, 2, 30]);
        assert_eq!(info.sensor_state, 0x02);
        assert_eq!(info.serial, "A12345678");
    }

    #[test]
    fn a_repeated_echo_run_is_skipped() {
        let raw = [vec![0x00], vec![0xa5; 8], info_payload()].concat();
        let info = parse_patch_info(&raw).unwrap();
        assert_eq!(info.serial, "A12345678");
        assert_eq!(info.wear_duration_min, 0x1b38);
    }

    #[test]
    fn nfc_b1_is_its_own_error() {
        let err = parse_patch_info(&[0x01, 0xb1]).unwrap_err();
        assert!(matches!(err, ProvisionError::NfcB1));
    }

    #[test]
    fn short_response_refused() {
        assert!(matches!(
            parse_patch_info(&[0x00, 0x00]),
            Err(ProvisionError::Malformed { .. })
        ));
    }

    #[test]
    fn parses_switch_response() {
        let mut n = vec![0x00];
        n.extend_from_slice(&[0xee, 0xff, 0xc0, 0xff, 0xee, 0xc0]);
        n.extend_from_slice(&[0xde, 0xad, 0xbe, 0xef]);
        n.extend_from_slice(&123_456_789u32.to_le_bytes());
        n.extend_from_slice(&[0, 0]); // crc
        let raw = [vec![0x00, 0xa5], n].concat();
        assert_eq!(raw.len(), SWITCH_RESPONSE_LEN);
        let parsed = parse_switch_response(&raw).unwrap();
        assert_eq!(parsed.ble_address, "C0:EE:FF:C0:FF:EE");
        assert_eq!(parsed.ble_pin, [0xde, 0xad, 0xbe, 0xef]);
        assert_eq!(parsed.activation_time_s, 123_456_789);
    }

    #[test]
    fn switch_error_status_is_its_own_error() {
        let mut n = vec![0x05];
        n.extend_from_slice(&[0u8; 16]);
        let raw = [vec![0x00, 0xa5], n].concat();
        let err = parse_switch_response(&raw).unwrap_err();
        assert!(matches!(err, ProvisionError::Nfc { code: 5 }));
    }

    #[test]
    fn switch_b1_is_account_mismatch() {
        let mut n = vec![0xb1];
        n.extend_from_slice(&[0u8; 16]);
        let raw = [vec![0x00, 0xa5], n].concat();
        assert!(matches!(parse_switch_response(&raw).unwrap_err(), ProvisionError::NfcB1));
    }

    #[test]
    fn bad_prefix_refused() {
        assert!(matches!(
            parse_switch_response(&[0u8; 19]),
            Err(ProvisionError::Malformed { .. })
        ));
    }
}
