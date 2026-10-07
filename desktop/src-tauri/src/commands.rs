// SPDX-License-Identifier: GPL-3.0-or-later
//! Typed Tauri commands: the only interface of the webview. Inputs are validated here;
//! outputs never contain key material.
//!
//! Tauri requires owned arguments and `Result` returns; errors are the [`AppError`] codes.
#![allow(
    clippy::missing_errors_doc,
    clippy::needless_pass_by_value,
    clippy::must_use_candidate
)]

use std::sync::Arc;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use bastion_proto::v1::{
    Command, ContactCard, LocateNow, Lock, LostMode, RequestStatus, Ring, SetTrackingMode,
    StopRing, TrackingMode, Unpair, Wipe, command,
};
use serde::{Deserialize, Serialize};
use tauri::State;

use crate::core::{AppCore, Connection, RelaySetup, is_valid_host, now_ms, parse_device_id};
use crate::engine::is_sensitive;
use crate::error::{AppError, AppResult};
use crate::model::{
    DeviceRecord, JournalEntry, LocationPoint, PairingState, RelaySettings, SentCommand, StatusView,
};

type Core<'a> = State<'a, Arc<AppCore>>;

/// Global application state for the shell.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppStatus {
    vault_exists: bool,
    unlocked: bool,
    connection: Connection,
    product_name: &'static str,
    version: &'static str,
    protocol_version: u32,
}

/// Status of the application.
#[tauri::command]
pub async fn app_status(core: Core<'_>) -> AppResult<AppStatus> {
    Ok(AppStatus {
        vault_exists: core.vault_exists(),
        unlocked: core.is_unlocked().await,
        connection: core.connection(),
        product_name: crate::PRODUCT_NAME,
        version: env!("CARGO_PKG_VERSION"),
        protocol_version: bastion_proto::PROTOCOL_VERSION,
    })
}

/// LAN address suggested for the embedded relay.
#[tauri::command]
pub fn suggested_host() -> Option<String> {
    crate::core::detect_lan_address().map(|ip| ip.to_string())
}

/// First-run setup.
#[tauri::command]
pub async fn setup(core: Core<'_>, password: String, relay: RelaySetup) -> AppResult<()> {
    core.setup(password, relay).await
}

/// Unlocks the vault.
#[tauri::command]
pub async fn unlock(core: Core<'_>, password: String) -> AppResult<()> {
    core.unlock(password).await
}

/// Locks the vault.
#[tauri::command]
pub async fn lock(core: Core<'_>) -> AppResult<()> {
    core.lock().await;
    Ok(())
}

/// User activity ping (auto-lock).
#[tauri::command]
pub fn touch(core: Core<'_>) {
    core.touch();
}

/// Summary of a device for lists.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSummary {
    id: String,
    label: String,
    state: PairingState,
    sas: String,
    fingerprint: String,
    paired_at_ms: i64,
    last_seen_ms: Option<i64>,
    last_location: Option<LocationPoint>,
    status: Option<StatusView>,
}

fn summary(d: &DeviceRecord) -> DeviceSummary {
    DeviceSummary {
        id: hex::encode(&d.device_id),
        label: d.label.clone(),
        state: d.state,
        sas: bastion_crypto::pairing::format_sas(d.sas),
        fingerprint: d.fingerprint.clone(),
        paired_at_ms: d.paired_at_ms,
        last_seen_ms: d.last_seen_ms,
        last_location: d.locations.back().cloned(),
        status: d.status.clone(),
    }
}

/// Paired devices.
#[tauri::command]
pub async fn devices(core: Core<'_>) -> AppResult<Vec<DeviceSummary>> {
    core.read(|s| s.data.devices.iter().map(summary).collect())
        .await
}

/// Detailed view of one device.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceDetail {
    summary: DeviceSummary,
    locations: Vec<LocationPoint>,
    commands: Vec<CommandView>,
}

/// A sent command as shown in the UI.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandView {
    id: String,
    kind: String,
    sent_ms: i64,
    status: String,
    reason: String,
}

impl From<&SentCommand> for CommandView {
    fn from(c: &SentCommand) -> Self {
        Self {
            id: STANDARD.encode(&c.message_id),
            kind: c.kind.clone(),
            sent_ms: c.sent_ms,
            status: c.status.clone(),
            reason: c.reason.clone(),
        }
    }
}

/// Details of a device.
#[tauri::command]
pub async fn device(core: Core<'_>, id: String) -> AppResult<DeviceDetail> {
    let device_id = parse_device_id(&id)?;
    core.read(|s| {
        let d = s
            .data
            .devices
            .iter()
            .find(|d| d.device_id == device_id)
            .ok_or(AppError::UnknownDevice)?;
        Ok(DeviceDetail {
            summary: summary(d),
            locations: d.locations.iter().rev().take(500).rev().cloned().collect(),
            commands: d
                .commands
                .iter()
                .rev()
                .take(30)
                .map(CommandView::from)
                .collect(),
        })
    })
    .await?
}

/// Security journal, newest first.
#[tauri::command]
pub async fn journal(core: Core<'_>) -> AppResult<Vec<JournalEntry>> {
    core.read(|s| s.data.journal.iter().rev().cloned().collect())
        .await
}

/// Pairing QR code.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingView {
    qr_data_url: String,
    uri: String,
    expires_at_ms: i64,
    endpoint: String,
}

fn computer_label() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| crate::PRODUCT_NAME.to_owned())
}

/// Creates a pairing invitation (registers its hash on the relay) and its QR code.
#[tauri::command]
pub async fn start_pairing(core: Core<'_>) -> AppResult<PairingView> {
    let (endpoint, pin) = core.announced_relay().await?;
    let (token, token_hash, expires) = core
        .mutate(|s| Ok((s.new_invite(now_ms()), Vec::new())))
        .await?;
    core.client()
        .await?
        .create_invite(token_hash, expires)
        .await?;
    let uri = core
        .read(|s| s.invite_uri(&token, expires, &endpoint, &pin, &computer_label()))
        .await?;
    let svg = qrcode::QrCode::with_error_correction_level(uri.as_bytes(), qrcode::EcLevel::M)
        .map_err(|_| AppError::Internal)?
        .render::<qrcode::render::svg::Color<'_>>()
        .min_dimensions(320, 320)
        .quiet_zone(true)
        .dark_color(qrcode::render::svg::Color("#0a0a0b"))
        .light_color(qrcode::render::svg::Color("#ededef"))
        .build();
    Ok(PairingView {
        qr_data_url: format!("data:image/svg+xml;base64,{}", STANDARD.encode(svg)),
        uri,
        expires_at_ms: expires,
        endpoint,
    })
}

/// Forgets pending invitations.
#[tauri::command]
pub async fn cancel_pairing(core: Core<'_>) -> AppResult<()> {
    core.mutate(|s| {
        s.cancel_invites();
        Ok(((), Vec::new()))
    })
    .await
}

/// SAS decision.
#[tauri::command]
pub async fn confirm_pairing(core: Core<'_>, id: String, accept: bool) -> AppResult<()> {
    let device_id = parse_device_id(&id)?;
    core.mutate(|s| Ok(((), s.confirm_pairing(&device_id, accept, now_ms())?)))
        .await
}

/// Lock-screen contact details from the UI.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContactInput {
    message: String,
    phone: String,
    email: String,
}

fn has_control(text: &str) -> bool {
    text.chars().any(|c| c.is_control() && c != '\n')
}

/// Mirrors `ContactCard.create` on Android (limits of `bastion.v1.ContactCard`).
fn contact(input: &ContactInput) -> AppResult<ContactCard> {
    let (m, p, e) = (input.message.trim(), input.phone.trim(), input.email.trim());
    if m.chars().count() > 280
        || p.chars().count() > 64
        || e.chars().count() > 128
        || has_control(m)
        || has_control(p)
        || has_control(e)
    {
        return Err(AppError::InvalidInput);
    }
    Ok(ContactCard {
        message: m.to_owned(),
        phone: p.to_owned(),
        email: e.to_owned(),
    })
}

/// Non-sensitive commands.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum CommandRequest {
    /// Ring.
    #[serde(rename_all = "camelCase")]
    Ring {
        /// 0 = until stopped.
        duration_seconds: u32,
        /// Blink the flashlight.
        flashlight: bool,
        /// Vibrate.
        vibrate: bool,
    },
    /// Stop ringing.
    StopRing,
    /// Locate now.
    #[serde(rename_all = "camelCase")]
    Locate {
        /// GPS instead of network location.
        high_accuracy: bool,
    },
    /// Tracking mode.
    #[serde(rename_all = "camelCase")]
    TrackingMode {
        /// `standby` or `active`.
        mode: String,
    },
    /// Lost mode.
    #[serde(rename_all = "camelCase")]
    LostMode {
        /// Enable or disable.
        enabled: bool,
        /// Contact shown on the lock screen.
        contact: Option<ContactInput>,
    },
    /// Status report.
    Status,
}

fn build_command(request: &CommandRequest) -> AppResult<Command> {
    let kind = match request {
        CommandRequest::Ring {
            duration_seconds,
            flashlight,
            vibrate,
        } => command::Kind::Ring(Ring {
            duration_seconds: (*duration_seconds).min(600),
            flashlight: *flashlight,
            vibrate: *vibrate,
        }),
        CommandRequest::StopRing => command::Kind::StopRing(StopRing {}),
        CommandRequest::Locate { high_accuracy } => command::Kind::LocateNow(LocateNow {
            high_accuracy: *high_accuracy,
        }),
        CommandRequest::TrackingMode { mode } => {
            let mode = match mode.as_str() {
                "standby" => TrackingMode::Standby,
                "active" => TrackingMode::Active,
                _ => return Err(AppError::InvalidInput),
            };
            command::Kind::SetTrackingMode(SetTrackingMode {
                mode: mode as i32,
                interval_seconds: 0,
            })
        }
        CommandRequest::LostMode {
            enabled,
            contact: c,
        } => command::Kind::LostMode(LostMode {
            enabled: *enabled,
            contact: c.as_ref().map(contact).transpose()?,
        }),
        CommandRequest::Status => command::Kind::RequestStatus(RequestStatus {}),
    };
    Ok(Command { kind: Some(kind) })
}

/// Sends a non-sensitive command.
#[tauri::command]
pub async fn send_command(core: Core<'_>, id: String, request: CommandRequest) -> AppResult<()> {
    let device_id = parse_device_id(&id)?;
    let command = build_command(&request)?;
    if is_sensitive(&command) {
        return Err(AppError::InvalidInput);
    }
    core.mutate(|s| Ok(((), vec![s.command(&device_id, command, None, now_ms())?])))
        .await
}

/// Sensitive commands (re-authentication required).
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum SensitiveRequest {
    /// Lock the phone.
    Lock {
        /// Contact shown on the lock screen.
        contact: Option<ContactInput>,
    },
    /// Wipe the phone (must be armed 30 s earlier).
    #[serde(rename_all = "camelCase")]
    Wipe {
        /// Include external storage.
        include_external_storage: bool,
    },
    /// Unpair.
    Unpair,
}

/// Arms a wipe; returns the time after which it may be confirmed.
#[tauri::command]
pub async fn arm_wipe(core: Core<'_>, id: String) -> AppResult<i64> {
    let device_id = parse_device_id(&id)?;
    core.read(|s| {
        s.data
            .device_index(&device_id)
            .ok_or(AppError::UnknownDevice)
    })
    .await??;
    core.arm_wipe(&device_id)
}

/// Cancels an armed wipe.
#[tauri::command]
pub fn disarm_wipe(core: Core<'_>, id: String) -> AppResult<()> {
    core.disarm_wipe(&parse_device_id(&id)?);
    Ok(())
}

/// Sends a sensitive command, counter-signed with the privileged key unsealed from the
/// master password (PROTOCOL.md §7). The key is dropped (zeroized) right after use.
#[tauri::command]
pub async fn send_sensitive(
    core: Core<'_>,
    id: String,
    password: String,
    request: SensitiveRequest,
) -> AppResult<()> {
    let device_id = parse_device_id(&id)?;
    let kind = match &request {
        SensitiveRequest::Lock { contact: c } => command::Kind::Lock(Lock {
            contact: c.as_ref().map(contact).transpose()?,
        }),
        SensitiveRequest::Wipe {
            include_external_storage,
        } => command::Kind::Wipe(Wipe {
            include_external_storage: *include_external_storage,
        }),
        SensitiveRequest::Unpair => command::Kind::Unpair(Unpair {}),
    };
    let privileged = core.unseal_privileged(password).await?;
    if matches!(request, SensitiveRequest::Wipe { .. }) {
        core.take_armed_wipe(&device_id)?;
    }
    let command = Command { kind: Some(kind) };
    core.mutate(|s| {
        Ok((
            (),
            vec![s.command(&device_id, command, Some(&privileged), now_ms())?],
        ))
    })
    .await
}

/// Removes a device locally and on the relay (for phones that can no longer be reached).
#[tauri::command]
pub async fn forget_device(core: Core<'_>, id: String, password: String) -> AppResult<()> {
    let device_id = parse_device_id(&id)?;
    drop(core.unseal_privileged(password).await?);
    core.mutate(|s| Ok(((), vec![s.forget(&device_id, now_ms())?])))
        .await
}

/// Settings view.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsView {
    auto_lock_minutes: u32,
    online_map: bool,
    relay_mode: &'static str,
    relay_endpoint: Option<String>,
    advertised_host: Option<String>,
    relay_pin: String,
    controller_fingerprint: String,
}

/// Current settings.
#[tauri::command]
pub async fn settings(core: Core<'_>) -> AppResult<SettingsView> {
    let announced = core.announced_relay().await.ok();
    core.read(|s| {
        let (mode, advertised) = match &s.data.relay {
            RelaySettings::Embedded {
                advertised_host, ..
            } => ("embedded", advertised_host.clone()),
            RelaySettings::Remote { .. } => ("remote", None),
        };
        SettingsView {
            auto_lock_minutes: s.data.settings.auto_lock_minutes,
            online_map: s.data.settings.online_map,
            relay_mode: mode,
            relay_endpoint: announced.as_ref().map(|(e, _)| e.clone()),
            advertised_host: advertised,
            relay_pin: announced.map(|(_, p)| hex::encode(p)).unwrap_or_default(),
            controller_fingerprint: s.keys().fingerprint(),
        }
    })
    .await
}

/// Settings update.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsUpdate {
    auto_lock_minutes: u32,
    online_map: bool,
    advertised_host: Option<String>,
}

/// Saves settings.
#[tauri::command]
pub async fn update_settings(core: Core<'_>, update: SettingsUpdate) -> AppResult<()> {
    if update.auto_lock_minutes > 24 * 60 {
        return Err(AppError::InvalidInput);
    }
    let host = update
        .advertised_host
        .map(|h| h.trim().to_owned())
        .filter(|h| !h.is_empty());
    if host.as_deref().is_some_and(|h| !is_valid_host(h)) {
        return Err(AppError::InvalidInput);
    }
    core.mutate(|s| {
        s.data.settings.auto_lock_minutes = update.auto_lock_minutes;
        s.data.settings.online_map = update.online_map;
        if let RelaySettings::Embedded {
            advertised_host, ..
        } = &mut s.data.relay
        {
            *advertised_host = host;
        }
        Ok(((), Vec::new()))
    })
    .await?;
    core.set_auto_lock(update.auto_lock_minutes);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contact_limits() {
        let ok = ContactInput {
            message: "Récompense si trouvé".into(),
            phone: "+33 6 00 00 00 00".into(),
            email: String::new(),
        };
        assert!(contact(&ok).is_ok());
        let long = ContactInput {
            message: "é".repeat(281),
            ..ok.clone()
        };
        assert_eq!(contact(&long).err(), Some(AppError::InvalidInput));
        let control = ContactInput {
            phone: "\u{1b}[2J".into(),
            ..ok
        };
        assert_eq!(contact(&control).err(), Some(AppError::InvalidInput));
    }

    #[test]
    fn sensitive_commands_cannot_be_sent_as_regular_ones() {
        for request in [
            CommandRequest::Ring {
                duration_seconds: 9999,
                flashlight: true,
                vibrate: true,
            },
            CommandRequest::Status,
        ] {
            assert!(!is_sensitive(&build_command(&request).unwrap()));
        }
        let ring = build_command(&CommandRequest::Ring {
            duration_seconds: 9999,
            flashlight: false,
            vibrate: false,
        })
        .unwrap();
        assert!(matches!(
            ring.kind,
            Some(command::Kind::Ring(Ring {
                duration_seconds: 600,
                ..
            }))
        ));
        assert!(
            build_command(&CommandRequest::TrackingMode {
                mode: "lost".into()
            })
            .is_err()
        );
    }
}
