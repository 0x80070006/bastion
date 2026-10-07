// SPDX-License-Identifier: GPL-3.0-or-later
//! Vault contents and the views sent to the webview. Secrets never appear in a view type.

use std::collections::VecDeque;

use serde::{Deserialize, Serialize};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::vault::SealedPrivilegedKey;

/// Base64 (standard) serialization of byte vectors.
pub mod b64 {
    use base64::Engine;
    use base64::engine::general_purpose::STANDARD;
    use serde::{Deserialize, Deserializer, Serializer};

    /// Serializes bytes as base64.
    ///
    /// # Errors
    /// Serializer errors.
    pub fn serialize<S: Serializer>(bytes: &[u8], serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&STANDARD.encode(bytes))
    }

    /// Deserializes base64 into bytes.
    ///
    /// # Errors
    /// Invalid base64.
    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<u8>, D::Error> {
        let text = String::deserialize(deserializer)?;
        STANDARD.decode(text).map_err(serde::de::Error::custom)
    }
}

/// Secret bytes, zeroized on drop.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, Zeroize, ZeroizeOnDrop)]
pub struct Secret(#[serde(with = "b64")] pub Vec<u8>);

impl std::fmt::Debug for Secret {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Secret(redacted)")
    }
}

/// How the desktop reaches its relay.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "mode", rename_all = "camelCase")]
pub enum RelaySettings {
    /// Relay embedded in this application (topology B).
    #[serde(rename_all = "camelCase")]
    Embedded {
        /// TCP port of the HTTPS listener.
        port: u16,
        /// Host announced to phones (LAN address auto-detected when `None`).
        advertised_host: Option<String>,
    },
    /// Remote relay (VPS, home server).
    #[serde(rename_all = "camelCase")]
    Remote {
        /// Base URL, e.g. `https://relay.example.org:8443`.
        url: String,
        /// SHA-256 of the relay TLS `SubjectPublicKeyInfo`.
        #[serde(with = "b64")]
        pin: Vec<u8>,
    },
}

/// User settings.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    /// Inactivity delay before the vault locks itself; 0 disables auto-lock.
    pub auto_lock_minutes: u32,
    /// Load OpenStreetMap tiles (through the local cache).
    pub online_map: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            auto_lock_minutes: 15,
            online_map: true,
        }
    }
}

/// Pairing progress of a device.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PairingState {
    /// Hello received; the user must compare the SAS.
    PendingSas,
    /// Confirmed here, waiting for the phone confirmation.
    AwaitingPhone,
    /// Both sides confirmed: commands allowed.
    Active,
}

/// A position reported by a phone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocationPoint {
    /// WGS84 latitude.
    pub latitude: f64,
    /// WGS84 longitude.
    pub longitude: f64,
    /// Accuracy radius in metres.
    pub accuracy_m: f32,
    /// Fix time.
    pub fix_time_ms: i64,
    /// `gps`, `network`, `passive` or `unknown`.
    pub provider: String,
    /// Speed in m/s, if known.
    pub speed_mps: Option<f32>,
}

/// Health of one protection feature.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthView {
    /// Feature identifier, e.g. `location`.
    pub feature: String,
    /// `active`, `disabled`, `degraded` or `unknown`.
    pub state: String,
    /// Machine-readable reason.
    pub reason: String,
}

/// Latest status reported by a phone.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusView {
    /// Battery percentage.
    pub battery_percent: Option<u32>,
    /// Whether the phone is charging.
    pub charging: bool,
    /// `wifi`, `cellular`, `none`…
    pub network: String,
    /// `standby`, `active`, `lost` or `unspecified`.
    pub tracking_mode: String,
    /// Lost mode enabled on the phone.
    pub lost_mode: bool,
    /// Protection features.
    pub health: Vec<HealthView>,
    /// App version on the phone.
    pub app_version: String,
    /// When the report was received.
    pub received_ms: i64,
}

/// A command sent to a phone and its last known result.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SentCommand {
    /// `message_id` of the command.
    #[serde(with = "b64")]
    pub message_id: Vec<u8>,
    /// Command kind code, e.g. `ring`.
    pub kind: String,
    /// When it was sent.
    pub sent_ms: i64,
    /// `sent`, `accepted`, `completed`, `failed`, `rejected`, `unsupported`, `cancelled`.
    pub status: String,
    /// Reason code returned by the phone.
    pub reason: String,
}

/// Message id remembered for replay protection until its TTL expires.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeenId {
    /// The `message_id`.
    #[serde(with = "b64")]
    pub id: Vec<u8>,
    /// When it may be forgotten.
    pub expires_ms: i64,
}

/// A paired (or pairing) phone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceRecord {
    /// `BLAKE2b-128(identity)`.
    #[serde(with = "b64")]
    pub device_id: Vec<u8>,
    /// Label chosen on the phone (sanitized).
    pub label: String,
    /// Ed25519 identity key.
    #[serde(with = "b64")]
    pub identity: Vec<u8>,
    /// Current X25519 key.
    #[serde(with = "b64")]
    pub exchange: Vec<u8>,
    /// Epoch of `exchange`.
    pub epoch: u32,
    /// Displayed fingerprint.
    pub fingerprint: String,
    /// Pairing progress.
    pub state: PairingState,
    /// Short authentication string to compare.
    pub sas: u32,
    /// Confirmed on this computer.
    pub local_confirmed: bool,
    /// Confirmed on the phone.
    pub remote_confirmed: bool,
    /// Negotiated protocol version (anti-downgrade floor).
    pub protocol_version: u32,
    /// Last counter used for messages to the phone.
    pub send_counter: u64,
    /// Anti-replay window for messages from the phone.
    #[serde(with = "b64")]
    pub recv_window: Vec<u8>,
    /// Recently seen message ids.
    pub seen_ids: Vec<SeenId>,
    /// Pairing time.
    pub paired_at_ms: i64,
    /// Last authenticated message from the phone.
    pub last_seen_ms: Option<i64>,
    /// Position history (bounded).
    pub locations: VecDeque<LocationPoint>,
    /// Latest status.
    pub status: Option<StatusView>,
    /// Recent commands (bounded).
    pub commands: VecDeque<SentCommand>,
    /// Anti-intrusion / on-demand photos (metadata; the JPEG lives in the media store).
    #[serde(default)]
    pub photos: VecDeque<PhotoMeta>,
}

/// Metadata of a stored photo. The JPEG itself is kept as a separate encrypted file named
/// by `id`; this keeps large images out of the frequently re-encrypted vault.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoMeta {
    /// 16-byte identifier, also the media file name.
    #[serde(with = "b64")]
    pub id: Vec<u8>,
    /// `front`, `back` or `unspecified`.
    pub camera: String,
    /// `onDemand` or `failedUnlock`.
    pub trigger: String,
    /// When the phone captured it.
    pub captured_ms: i64,
    /// Whether a location accompanied the photo.
    pub has_location: bool,
}

/// Security journal entry.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JournalEntry {
    /// When it happened (local clock).
    pub time_ms: i64,
    /// Related device (hex id), if any.
    pub device_id: Option<String>,
    /// Device label at that time.
    pub device_label: Option<String>,
    /// Machine code, translated by the frontend (e.g. `alert.simRemoved`).
    pub kind: String,
    /// Optional machine-readable detail.
    pub detail: Option<String>,
}

/// Everything persisted in the encrypted vault.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VaultData {
    /// Format version.
    pub version: u32,
    /// Identity key `IK` seed.
    pub identity_seed: Secret,
    /// Exchange key `XK` secret.
    pub exchange_secret: Secret,
    /// Epoch of `XK`.
    pub exchange_epoch: u32,
    /// When `XK` was last rotated (0 = never since creation).
    #[serde(default)]
    pub exchange_rotated_ms: i64,
    /// Privileged key `PK` public half.
    #[serde(with = "b64")]
    pub privileged_public: Vec<u8>,
    /// Privileged key `PK`, sealed under its own password-derived key.
    pub privileged_sealed: SealedPrivilegedKey,
    /// Relay configuration.
    pub relay: RelaySettings,
    /// Whether this controller is registered on the relay.
    pub controller_enrolled: bool,
    /// Paired devices.
    pub devices: Vec<DeviceRecord>,
    /// Security journal (bounded).
    pub journal: VecDeque<JournalEntry>,
    /// User settings.
    pub settings: Settings,
}

/// Maximum journal length.
pub const MAX_JOURNAL: usize = 1000;
/// Maximum stored positions per device.
pub const MAX_LOCATIONS: usize = 2000;
/// Maximum remembered commands per device.
pub const MAX_COMMANDS: usize = 100;
/// Maximum stored photos per device (older ones and their files are purged).
pub const MAX_PHOTOS: usize = 200;

impl VaultData {
    /// Appends a journal entry, dropping the oldest beyond [`MAX_JOURNAL`].
    pub fn log(
        &mut self,
        time_ms: i64,
        device: Option<&DeviceRecord>,
        kind: &str,
        detail: Option<String>,
    ) {
        self.journal.push_back(JournalEntry {
            time_ms,
            device_id: device.map(|d| hex::encode(&d.device_id)),
            device_label: device.map(|d| d.label.clone()),
            kind: kind.to_owned(),
            detail,
        });
        while self.journal.len() > MAX_JOURNAL {
            self.journal.pop_front();
        }
    }

    /// Index of a device by id.
    #[must_use]
    pub fn device_index(&self, device_id: &[u8]) -> Option<usize> {
        self.devices.iter().position(|d| d.device_id == device_id)
    }

    #[cfg(test)]
    pub(crate) fn new_for_tests() -> Self {
        Self {
            version: 1,
            identity_seed: Secret(vec![1; 32]),
            exchange_secret: Secret(vec![2; 32]),
            exchange_epoch: 0,
            exchange_rotated_ms: 0,
            privileged_public: vec![3; 32],
            privileged_sealed: SealedPrivilegedKey::seal(
                "correct horse battery",
                &[4; 32],
                bastion_crypto::vault::KdfParams::INTERACTIVE,
            )
            .unwrap_or_else(|_| unreachable!()),
            relay: RelaySettings::Embedded {
                port: 8443,
                advertised_host: None,
            },
            controller_enrolled: false,
            devices: Vec::new(),
            journal: VecDeque::new(),
            settings: Settings::default(),
        }
    }
}

/// Removes control characters and bounds the length of a label received from a phone.
#[must_use]
pub fn sanitize_label(label: &str) -> String {
    let cleaned: String = label.chars().filter(|c| !c.is_control()).take(64).collect();
    let trimmed = cleaned.trim();
    if trimmed.is_empty() {
        "Android".to_owned()
    } else {
        trimmed.to_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_are_sanitized() {
        assert_eq!(sanitize_label("  Pixel\u{0007} 9a \n"), "Pixel 9a");
        assert_eq!(sanitize_label(""), "Android");
        assert_eq!(sanitize_label(&"x".repeat(100)).len(), 64);
    }

    #[test]
    fn journal_is_bounded() {
        let mut data = VaultData::new_for_tests();
        for i in 0..(MAX_JOURNAL + 5) {
            data.log(i64::try_from(i).unwrap(), None, "test", None);
        }
        assert_eq!(data.journal.len(), MAX_JOURNAL);
        assert_eq!(data.journal.front().map(|e| e.time_ms), Some(5));
    }
}
