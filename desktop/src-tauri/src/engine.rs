// SPDX-License-Identifier: GPL-3.0-or-later
//! Controller-side protocol engine (PROTOCOL.md §3–7): pairing, reception rules, commands.
//!
//! Pure logic over [`VaultData`]: no I/O. Network effects are returned as [`Action`]s.

use std::sync::Arc;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bastion_crypto::envelope::{self, Header};
use bastion_crypto::identity::{DeviceId, DeviceKeys, ExchangeKeypair, SigningKeypair, fixed};
use bastion_crypto::pairing::{self, Token};
use bastion_crypto::replay::ReplayWindow;
use bastion_crypto::session::{Party, SymmetricKey, directional_key};
use bastion_crypto::vault::KdfParams;
use bastion_crypto::{random, sealed};
use bastion_proto::v1::{
    Command, CommandResult, Event, KeyRotation, MessageBody, PairingConfirm, PairingHello,
    PairingInvite, alert, command, command_result, event, last_chance_beacon, location,
    message_body, protection_health,
};
use prost::Message;
use zeroize::Zeroizing;

use crate::error::{AppError, AppResult};
use crate::model::{
    DeviceRecord, GeofenceDef, HealthView, LocationPoint, MAX_COMMANDS, MAX_LOCATIONS, MAX_PHOTOS,
    PairingState, PhotoMeta, RelaySettings, Secret, SeenId, SentCommand, Settings, StatusView,
    VaultData, sanitize_label,
};
use crate::vault::SealedPrivilegedKey;

/// Accepted clock skew (PROTOCOL.md §6.8).
pub const SKEW_MS: i64 = 30_000;
const DEFAULT_TTL_S: u32 = 60;
const EVENT_TTL_MAX_S: u32 = 7 * 24 * 3600;
/// Default X25519 key rotation period (PROTOCOL.md §4).
const ROTATION_INTERVAL_MS: i64 = 7 * 24 * 3600 * 1000;
const MAX_SEEN_IDS: usize = 4096;
const MAX_LOCATIONS_PER_REPORT: usize = 64;
/// TTL of a remote-input command: short, so a tap that cannot be delivered promptly is dropped
/// by the phone rather than acted on late.
const INPUT_TTL_SECONDS: u32 = 15;
/// Lifetime of a pairing invitation.
pub const INVITE_TTL_MS: i64 = 300_000;

/// Network effect requested by the engine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Deposit an envelope in a mailbox.
    Send {
        /// Recipient device.
        recipient: DeviceId,
        /// Serialized envelope.
        envelope: Vec<u8>,
    },
    /// Remove the link with a device on the relay.
    Revoke {
        /// Device to unlink.
        device: DeviceId,
    },
}

/// Something worth telling the user right away (window attention).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Notice {
    /// A phone is waiting for SAS comparison.
    PairingRequest,
    /// A phone reported an alert.
    Alert,
    /// An anti-intrusion photo arrived.
    Photo,
}

/// Media a phone sent, handed to the caller for storage (photos) or live display (frames).
/// The engine never touches the filesystem; it only validates and forwards the bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Media {
    /// A still photo to persist (bytes go to the encrypted media store, metadata to the vault).
    Photo {
        /// Device that sent it.
        device_id: Vec<u8>,
        /// Vault-assigned identifier, matching the stored [`PhotoMeta`].
        photo_id: Vec<u8>,
        /// JPEG bytes.
        jpeg: Vec<u8>,
    },
    /// A live stream frame to display, never persisted.
    Frame {
        /// Device that sent it.
        device_id: Vec<u8>,
        /// Sequence within the stream session.
        sequence: u64,
        /// JPEG bytes.
        jpeg: Vec<u8>,
    },
    /// A live audio chunk to play, never persisted.
    Audio {
        /// Device that sent it.
        device_id: Vec<u8>,
        /// Sequence within the audio session.
        sequence: u64,
        /// 16-bit mono PCM.
        pcm: Vec<u8>,
        /// Sample rate in Hz.
        sample_rate: u32,
    },
    /// A live screen-mirror frame to display, never persisted.
    Screen {
        /// Device that sent it.
        device_id: Vec<u8>,
        /// Sequence within the screen session.
        sequence: u64,
        /// JPEG bytes.
        jpeg: Vec<u8>,
        /// Transmitted frame width in pixels (for mapping input coordinates).
        width: u32,
        /// Transmitted frame height in pixels.
        height: u32,
        /// The phone is showing a secure keyguard, so input is withheld.
        locked: bool,
    },
}

struct PendingInvite {
    token: Zeroizing<Token>,
    expires_ms: i64,
}

/// An unlocked controller session.
pub struct Session {
    /// Decrypted vault contents.
    pub data: VaultData,
    identity: Arc<SigningKeypair>,
    exchange: ExchangeKeypair,
    invites: Vec<PendingInvite>,
    media: Vec<Media>,
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session").finish_non_exhaustive()
    }
}

/// Kind code of a command, used in the journal and the UI.
#[must_use]
pub fn command_kind(command: &Command) -> &'static str {
    match command.kind {
        Some(command::Kind::Ring(_)) => "ring",
        Some(command::Kind::StopRing(_)) => "stopRing",
        Some(command::Kind::LocateNow(_)) => "locate",
        Some(command::Kind::SetTrackingMode(_)) => "trackingMode",
        Some(command::Kind::LostMode(_)) => "lostMode",
        Some(command::Kind::Lock(_)) => "lock",
        Some(command::Kind::CapturePhoto(_)) => "capturePhoto",
        Some(command::Kind::Wipe(_)) => "wipe",
        Some(command::Kind::Unpair(_)) => "unpair",
        Some(command::Kind::RequestStatus(_)) => "status",
        Some(command::Kind::StreamControl(_)) => "stream",
        Some(command::Kind::AudioControl(_)) => "audio",
        Some(command::Kind::SetGeofences(_)) => "geofences",
        Some(command::Kind::ScreenControl(_)) => "screen",
        Some(command::Kind::RemoteInput(_)) => "input",
        Some(command::Kind::IntercomControl(_)) => "intercom",
        Some(command::Kind::AudioPlay(_)) => "audioPlay",
        None => "unknown",
    }
}

/// Short machine name of a camera selection.
#[must_use]
pub fn camera_name(camera: i32) -> &'static str {
    match bastion_proto::v1::capture_photo::Camera::try_from(camera) {
        Ok(bastion_proto::v1::capture_photo::Camera::Front) => "front",
        Ok(bastion_proto::v1::capture_photo::Camera::Back) => "back",
        _ => "unspecified",
    }
}

/// Whether `bytes` begins with the JPEG magic and fits the field limit.
#[must_use]
pub fn valid_jpeg(bytes: &[u8]) -> bool {
    bytes.len() >= 3
        && bytes.len() <= bastion_proto::MAX_BYTES_FIELD
        && bytes.starts_with(&[0xff, 0xd8, 0xff])
}

/// Whether a command requires the privileged counter-signature (PROTOCOL.md §7).
#[must_use]
pub fn is_sensitive(command: &Command) -> bool {
    matches!(
        command.kind,
        Some(command::Kind::Lock(_) | command::Kind::Wipe(_) | command::Kind::Unpair(_))
    )
}

/// TTL used when sending a command (capped by the phone per PROTOCOL.md §6.8).
fn command_ttl(command: &Command) -> u32 {
    match command.kind {
        Some(
            command::Kind::SetTrackingMode(_)
            | command::Kind::LostMode(_)
            | command::Kind::Lock(_)
            | command::Kind::Wipe(_)
            | command::Kind::Unpair(_),
        ) => 24 * 3600,
        _ => 15 * 60,
    }
}

fn party(identity: &[u8], exchange: &[u8]) -> AppResult<Party> {
    Ok(Party {
        identity: fixed(identity).map_err(|_| AppError::Internal)?,
        exchange: fixed(exchange).map_err(|_| AppError::Internal)?,
    })
}

impl Session {
    /// Creates the keys of a brand-new controller.
    ///
    /// # Errors
    /// [`AppError::Internal`].
    pub fn create(relay: RelaySettings, password: &str, params: KdfParams) -> AppResult<Self> {
        let identity = SigningKeypair::generate();
        let exchange = ExchangeKeypair::generate(0);
        let privileged = SigningKeypair::generate();
        let data = VaultData {
            version: 1,
            identity_seed: Secret(identity.seed().to_vec()),
            exchange_secret: Secret(exchange.secret().to_vec()),
            exchange_epoch: 0,
            exchange_rotated_ms: 0,
            privileged_public: privileged.public().to_vec(),
            privileged_sealed: SealedPrivilegedKey::seal(password, &privileged.seed(), params)?,
            relay,
            controller_enrolled: false,
            devices: Vec::new(),
            journal: std::collections::VecDeque::new(),
            settings: Settings::default(),
        };
        Self::from_data(data)
    }

    /// Rebuilds the in-memory keys from decrypted vault contents.
    ///
    /// # Errors
    /// [`AppError::VaultCorrupted`].
    pub fn from_data(data: VaultData) -> AppResult<Self> {
        let seed: [u8; 32] = fixed(&data.identity_seed.0).map_err(|_| AppError::VaultCorrupted)?;
        let secret: [u8; 32] =
            fixed(&data.exchange_secret.0).map_err(|_| AppError::VaultCorrupted)?;
        Ok(Self {
            identity: Arc::new(SigningKeypair::from_seed(&seed)),
            exchange: ExchangeKeypair::from_secret(&secret, data.exchange_epoch),
            data,
            invites: Vec::new(),
            media: Vec::new(),
        })
    }

    /// Identity key, shared with the relay client for request signatures.
    #[must_use]
    pub fn identity(&self) -> Arc<SigningKeypair> {
        Arc::clone(&self.identity)
    }

    /// Signed public keys of this controller.
    #[must_use]
    pub fn keys(&self) -> DeviceKeys {
        self.exchange.signed_by(&self.identity)
    }

    /// Generates a pairing token. Returns `(token_hash, expires_at_ms)` for the relay.
    pub fn new_invite(&mut self, now_ms: i64) -> (Token, [u8; 32], i64) {
        self.invites.retain(|i| i.expires_ms > now_ms);
        let token = pairing::generate_token();
        let expires = now_ms + INVITE_TTL_MS;
        self.invites.push(PendingInvite {
            token: Zeroizing::new(token),
            expires_ms: expires,
        });
        (token, pairing::token_hash(&token), expires)
    }

    /// Drops every pending invitation (pairing dialog closed).
    pub fn cancel_invites(&mut self) {
        self.invites.clear();
    }

    /// Pairing URI encoded in the QR code (PROTOCOL.md §3.1).
    #[must_use]
    pub fn invite_uri(
        &self,
        token: &Token,
        expires_ms: i64,
        https_endpoint: &str,
        spki_pin: &[u8; 32],
        label: &str,
    ) -> String {
        let invite = PairingInvite {
            version: 1,
            relay_https_endpoint: https_endpoint.to_owned(),
            relay_wireguard_endpoint: String::new(),
            relay_tls_spki_sha256: spki_pin.to_vec(),
            relay_wireguard_public_key: Vec::new(),
            controller: Some(self.keys().to_proto()),
            controller_privileged_public_key: self.data.privileged_public.clone(),
            token: token.to_vec(),
            expires_at_ms: expires_ms,
            controller_label: sanitize_label(label),
        };
        format!(
            "bastion://pair/v1#{}",
            URL_SAFE_NO_PAD.encode(invite.encode_to_vec())
        )
    }

    /// Processes one mailbox item. Invalid items are dropped silently (no oracle); the
    /// returned notices tell the UI what deserves attention, and the media must be stored
    /// (photos) or displayed (frames) by the caller.
    pub fn handle_item(
        &mut self,
        kind: i32,
        sender_id: &[u8],
        payload: &[u8],
        now_ms: i64,
    ) -> (Vec<Action>, Vec<Notice>, Vec<Media>) {
        let result = match kind {
            2 => self
                .handle_hello(sender_id, payload, now_ms)
                .map(|n| (Vec::new(), n)),
            1 => self.handle_envelope(payload, now_ms),
            _ => Err(AppError::InvalidInput),
        };
        let (actions, notices) = result.unwrap_or_else(|error| {
            tracing::debug!(%error, "mailbox item dropped");
            (Vec::new(), Vec::new())
        });
        (actions, notices, std::mem::take(&mut self.media))
    }

    fn handle_hello(
        &mut self,
        sender_id: &[u8],
        sealed_hello: &[u8],
        now_ms: i64,
    ) -> AppResult<Vec<Notice>> {
        let plaintext =
            sealed::open(&self.exchange, sealed_hello).map_err(|_| AppError::InvalidInput)?;
        let hello =
            PairingHello::decode(plaintext.as_slice()).map_err(|_| AppError::InvalidInput)?;
        let phone = hello
            .device
            .as_ref()
            .and_then(|d| DeviceKeys::from_proto(d).ok())
            .ok_or(AppError::InvalidInput)?;
        if phone.device_id().as_slice() != sender_id {
            return Err(AppError::InvalidInput);
        }
        self.invites.retain(|i| i.expires_ms > now_ms);
        let controller = self.identity.public();
        let position = self
            .invites
            .iter()
            .position(|i| {
                pairing::verify_enroll_proof(&i.token, &phone, &[], &controller, &hello.proof)
            })
            .ok_or(AppError::InvalidInput)?;
        let invite = self.invites.remove(position);
        let sas = pairing::sas_code(&invite.token, &self.keys(), &phone);
        let device_id = phone.device_id().to_vec();
        if let Some(index) = self.data.device_index(&device_id) {
            self.data.devices.remove(index);
        }
        let record = DeviceRecord {
            device_id,
            label: sanitize_label(&hello.device_label),
            identity: phone.identity.to_vec(),
            exchange: phone.exchange.to_vec(),
            epoch: phone.epoch,
            fingerprint: phone.fingerprint(),
            state: PairingState::PendingSas,
            sas,
            local_confirmed: false,
            remote_confirmed: false,
            protocol_version: hello
                .max_protocol_version
                .clamp(1, bastion_proto::PROTOCOL_VERSION),
            send_counter: 0,
            recv_window: ReplayWindow::default().to_bytes().to_vec(),
            seen_ids: Vec::new(),
            paired_at_ms: now_ms,
            last_seen_ms: Some(now_ms),
            locations: std::collections::VecDeque::new(),
            status: None,
            commands: std::collections::VecDeque::new(),
            photos: std::collections::VecDeque::new(),
            geofences: Vec::new(),
        };
        self.data
            .log(now_ms, Some(&record), "pairing.request", None);
        self.data.devices.push(record);
        Ok(vec![Notice::PairingRequest])
    }

    fn session_keys(&self, record: &DeviceRecord) -> AppResult<(SymmetricKey, SymmetricKey)> {
        let me = party(&self.identity.public(), &self.exchange.public())?;
        let phone = party(&record.identity, &record.exchange)?;
        let to_phone =
            directional_key(&self.exchange, &me, &phone).map_err(|_| AppError::Internal)?;
        let from_phone =
            directional_key(&self.exchange, &phone, &me).map_err(|_| AppError::Internal)?;
        Ok((to_phone, from_phone))
    }

    /// Reception rules of PROTOCOL.md §6, in order.
    fn handle_envelope(
        &mut self,
        bytes: &[u8],
        now_ms: i64,
    ) -> AppResult<(Vec<Action>, Vec<Notice>)> {
        let parsed = envelope::parse(bytes).map_err(|_| AppError::InvalidInput)?;
        if parsed.header.recipient_id != self.identity.device_id() {
            return Err(AppError::InvalidInput);
        }
        let index = self
            .data
            .device_index(&parsed.header.sender_id)
            .ok_or(AppError::UnknownDevice)?;
        let record = &self.data.devices[index];
        if parsed.header.key_epoch != record.epoch {
            return Err(AppError::InvalidInput);
        }
        let (_, from_phone) = self.session_keys(record)?;
        let identity: [u8; 32] = fixed(&record.identity).map_err(|_| AppError::Internal)?;
        let opened = envelope::open(&parsed, &[&from_phone], &identity, None)
            .map_err(|_| AppError::InvalidInput)?;
        let body =
            MessageBody::decode(opened.body.as_slice()).map_err(|_| AppError::InvalidInput)?;
        if body.protocol_version < record.protocol_version
            || body.protocol_version > bastion_proto::PROTOCOL_VERSION
        {
            return Err(AppError::InvalidInput);
        }
        let ttl = if body.ttl_seconds == 0 {
            DEFAULT_TTL_S
        } else {
            body.ttl_seconds.min(EVENT_TTL_MAX_S)
        };
        if now_ms - SKEW_MS > body.timestamp_ms.saturating_add(i64::from(ttl) * 1000)
            || body.timestamp_ms > now_ms + SKEW_MS
        {
            return Err(AppError::InvalidInput);
        }
        let active = record.state == PairingState::Active;
        let is_confirm = matches!(body.payload, Some(message_body::Payload::PairingConfirm(_)));
        if !active && !is_confirm {
            return Err(AppError::NotActive);
        }
        // Anti-replay: counter window + message id cache, persisted with the vault before the
        // payload takes effect.
        let record = &mut self.data.devices[index];
        let mut window = ReplayWindow::from_bytes(&record.recv_window).unwrap_or_default();
        record.seen_ids.retain(|s| s.expires_ms > now_ms);
        if body.message_id.len() != 16
            || record.seen_ids.iter().any(|s| s.id == body.message_id)
            || !window.accept(body.counter)
        {
            return Err(AppError::InvalidInput);
        }
        record.recv_window = window.to_bytes().to_vec();
        record.seen_ids.push(SeenId {
            id: body.message_id.clone(),
            expires_ms: body.timestamp_ms + i64::from(ttl) * 1000 + SKEW_MS,
        });
        if record.seen_ids.len() > MAX_SEEN_IDS {
            record.seen_ids.remove(0);
        }
        record.last_seen_ms = Some(now_ms);
        self.apply_payload(index, body.payload, now_ms)
    }

    fn apply_payload(
        &mut self,
        index: usize,
        payload: Option<message_body::Payload>,
        now_ms: i64,
    ) -> AppResult<(Vec<Action>, Vec<Notice>)> {
        match payload {
            Some(message_body::Payload::Event(event)) => {
                Ok((Vec::new(), self.apply_event(index, event, now_ms)))
            }
            Some(message_body::Payload::CommandResult(result)) => {
                Ok(self.apply_result(index, &result, now_ms))
            }
            Some(message_body::Payload::PairingConfirm(confirm)) => {
                Ok(self.apply_confirm(index, confirm, now_ms))
            }
            Some(message_body::Payload::KeyRotation(rotation)) => {
                self.apply_rotation(index, &rotation, now_ms);
                Ok((Vec::new(), Vec::new()))
            }
            Some(message_body::Payload::Command(_)) | None => Err(AppError::InvalidInput),
        }
    }

    fn apply_confirm(
        &mut self,
        index: usize,
        confirm: PairingConfirm,
        now_ms: i64,
    ) -> (Vec<Action>, Vec<Notice>) {
        let record = &mut self.data.devices[index];
        if !confirm.confirmed {
            let removed = self.data.devices.remove(index);
            self.data
                .log(now_ms, Some(&removed), "pairing.rejectedByPhone", None);
            let device = fixed(&removed.device_id).unwrap_or_default();
            return (vec![Action::Revoke { device }], Vec::new());
        }
        record.remote_confirmed = true;
        if record.local_confirmed && record.state != PairingState::Active {
            record.state = PairingState::Active;
            let snapshot = record.clone();
            self.data
                .log(now_ms, Some(&snapshot), "pairing.active", None);
        }
        (Vec::new(), Vec::new())
    }

    fn apply_rotation(&mut self, index: usize, rotation: &KeyRotation, now_ms: i64) {
        let record = &mut self.data.devices[index];
        let Ok(identity) = fixed::<32>(&record.identity) else {
            return;
        };
        let Ok(exchange) = fixed::<32>(&rotation.x25519_public_key) else {
            return;
        };
        let Ok(signature) = fixed::<64>(&rotation.x25519_signature) else {
            return;
        };
        let keys = DeviceKeys {
            identity,
            exchange,
            exchange_signature: signature,
            epoch: rotation.new_epoch,
        };
        if rotation.new_epoch > record.epoch && keys.verify().is_ok() {
            record.exchange = exchange.to_vec();
            record.epoch = rotation.new_epoch;
            record.fingerprint = keys.fingerprint();
            let snapshot = record.clone();
            self.data.log(now_ms, Some(&snapshot), "keys.rotated", None);
        }
    }

    fn apply_result(
        &mut self,
        index: usize,
        result: &CommandResult,
        now_ms: i64,
    ) -> (Vec<Action>, Vec<Notice>) {
        let status = command_result::Status::try_from(result.status)
            .unwrap_or(command_result::Status::Unspecified);
        let status_code = match status {
            command_result::Status::Accepted => "accepted",
            command_result::Status::Completed => "completed",
            command_result::Status::Failed => "failed",
            command_result::Status::Rejected => "rejected",
            command_result::Status::Unsupported => "unsupported",
            command_result::Status::Cancelled => "cancelled",
            command_result::Status::Unspecified => "unknown",
        };
        let reason: String = result
            .reason_code
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '_')
            .take(64)
            .collect();
        let record = &mut self.data.devices[index];
        let mut kind = None;
        if let Some(sent) = record
            .commands
            .iter_mut()
            .find(|c| c.message_id == result.command_message_id)
        {
            status_code.clone_into(&mut sent.status);
            sent.reason.clone_from(&reason);
            kind = Some(sent.kind.clone());
        }
        let snapshot = record.clone();
        let detail = Some(match &kind {
            Some(k) if reason.is_empty() => k.clone(),
            Some(k) => format!("{k}:{reason}"),
            None => reason,
        });
        self.data.log(
            now_ms,
            Some(&snapshot),
            &format!("result.{status_code}"),
            detail,
        );
        // A phone that executed Unpair forgets us: forget it too.
        if kind.as_deref() == Some("unpair") && status == command_result::Status::Completed {
            let removed = self.data.devices.remove(index);
            self.data
                .log(now_ms, Some(&removed), "pairing.removed", None);
            let device = fixed(&removed.device_id).unwrap_or_default();
            return (vec![Action::Revoke { device }], Vec::new());
        }
        (Vec::new(), Vec::new())
    }

    #[allow(clippy::too_many_lines)]
    fn apply_event(&mut self, index: usize, event: Event, now_ms: i64) -> Vec<Notice> {
        let mut notices = Vec::new();
        let (kind, detail, location) = match event.kind {
            Some(event::Kind::Location(report)) => {
                let record = &mut self.data.devices[index];
                for loc in report.locations.iter().take(MAX_LOCATIONS_PER_REPORT) {
                    push_location(record, loc);
                }
                return notices;
            }
            Some(event::Kind::Status(status)) => {
                self.data.devices[index].status = Some(status_view(&status, now_ms));
                return notices;
            }
            Some(event::Kind::Alert(a)) => {
                notices.push(Notice::Alert);
                let name = alert::Type::try_from(a.r#type)
                    .map_or("unknown".to_owned(), |t| camel(t.as_str_name(), "TYPE_"));
                let mut detail: Vec<String> = a
                    .detail
                    .iter()
                    .take(8)
                    .map(|(k, v)| format!("{}={}", clean(k), clean(v)))
                    .collect();
                detail.sort();
                (
                    format!("alert.{name}"),
                    (!detail.is_empty()).then(|| detail.join(",")),
                    a.location,
                )
            }
            Some(event::Kind::LastChance(beacon)) => {
                notices.push(Notice::Alert);
                let reason = last_chance_beacon::Reason::try_from(beacon.reason)
                    .map_or("unknown".to_owned(), |r| camel(r.as_str_name(), "REASON_"));
                let battery = beacon
                    .battery
                    .map(|b| format!("battery={}", b.level_percent));
                (format!("beacon.{reason}"), battery, beacon.location)
            }
            Some(event::Kind::Photo(photo)) => {
                let location = photo.location;
                if let Some(detail) = self.store_photo(
                    index,
                    &photo.jpeg,
                    photo.camera,
                    photo.trigger,
                    location.as_ref(),
                    now_ms,
                ) {
                    notices.push(Notice::Photo);
                    let record = &self.data.devices[index];
                    let snapshot = record.clone();
                    self.data
                        .log(now_ms, Some(&snapshot), "photo.received", Some(detail));
                }
                if let Some(loc) = &location {
                    push_location(&mut self.data.devices[index], loc);
                }
                return notices;
            }
            Some(event::Kind::MediaFrame(frame)) => {
                if valid_jpeg(&frame.jpeg) {
                    self.media.push(Media::Frame {
                        device_id: self.data.devices[index].device_id.clone(),
                        sequence: frame.sequence,
                        jpeg: frame.jpeg,
                    });
                }
                return notices;
            }
            Some(event::Kind::AudioChunk(chunk)) => {
                if !chunk.pcm.is_empty()
                    && chunk.pcm.len() <= bastion_proto::MAX_BYTES_FIELD
                    && (8_000..=48_000).contains(&chunk.sample_rate)
                {
                    self.media.push(Media::Audio {
                        device_id: self.data.devices[index].device_id.clone(),
                        sequence: chunk.sequence,
                        pcm: chunk.pcm,
                        sample_rate: chunk.sample_rate,
                    });
                }
                return notices;
            }
            Some(event::Kind::ScreenFrame(frame)) => {
                if valid_jpeg(&frame.jpeg) {
                    self.media.push(Media::Screen {
                        device_id: self.data.devices[index].device_id.clone(),
                        sequence: frame.sequence,
                        jpeg: frame.jpeg,
                        width: frame.width,
                        height: frame.height,
                        locked: frame.locked,
                    });
                }
                return notices;
            }
            None => return notices,
        };
        let record = &mut self.data.devices[index];
        if let Some(loc) = &location {
            push_location(record, loc);
        }
        let snapshot = record.clone();
        self.data.log(now_ms, Some(&snapshot), &kind, detail);
        notices
    }

    /// Validates a photo, records its metadata in the device and queues the bytes for storage.
    /// Returns a journal detail string when accepted.
    fn store_photo(
        &mut self,
        index: usize,
        jpeg: &[u8],
        camera: i32,
        trigger: i32,
        location: Option<&bastion_proto::v1::Location>,
        now_ms: i64,
    ) -> Option<String> {
        if !valid_jpeg(jpeg) {
            return None;
        }
        let photo_id = random::bytes::<16>().to_vec();
        let camera_name = camera_name(camera);
        let trigger_name = match bastion_proto::v1::photo_report::Trigger::try_from(trigger) {
            Ok(bastion_proto::v1::photo_report::Trigger::FailedUnlock) => "failedUnlock",
            _ => "onDemand",
        };
        let record = &mut self.data.devices[index];
        record.photos.push_back(PhotoMeta {
            id: photo_id.clone(),
            camera: camera_name.to_owned(),
            trigger: trigger_name.to_owned(),
            captured_ms: now_ms,
            has_location: location.is_some(),
        });
        while record.photos.len() > MAX_PHOTOS {
            record.photos.pop_front();
        }
        self.media.push(Media::Photo {
            device_id: record.device_id.clone(),
            photo_id,
            jpeg: jpeg.to_vec(),
        });
        Some(format!("{camera_name}:{trigger_name}"))
    }

    /// Records the local SAS decision. Accepting sends `PairingConfirm`; refusing removes
    /// the device and unlinks it on the relay.
    ///
    /// # Errors
    /// [`AppError::UnknownDevice`] or [`AppError::InvalidInput`] if not awaiting a decision.
    pub fn confirm_pairing(
        &mut self,
        device_id: &[u8],
        accept: bool,
        now_ms: i64,
    ) -> AppResult<Vec<Action>> {
        let index = self
            .data
            .device_index(device_id)
            .ok_or(AppError::UnknownDevice)?;
        if self.data.devices[index].state != PairingState::PendingSas {
            return Err(AppError::InvalidInput);
        }
        let version = self.data.devices[index].protocol_version;
        let confirm = message_body::Payload::PairingConfirm(PairingConfirm {
            confirmed: accept,
            negotiated_protocol_version: version,
        });
        let send = self.seal_to(index, confirm, 24 * 3600, None, now_ms)?;
        let mut actions = vec![send];
        if accept {
            let record = &mut self.data.devices[index];
            record.local_confirmed = true;
            record.state = if record.remote_confirmed {
                PairingState::Active
            } else {
                PairingState::AwaitingPhone
            };
            let snapshot = record.clone();
            self.data
                .log(now_ms, Some(&snapshot), "pairing.confirmed", None);
        } else {
            let removed = self.data.devices.remove(index);
            self.data
                .log(now_ms, Some(&removed), "pairing.rejected", None);
            actions.push(Action::Revoke {
                device: fixed(&removed.device_id).map_err(|_| AppError::Internal)?,
            });
        }
        Ok(actions)
    }

    /// Builds a command envelope for an active device. Sensitive commands require the
    /// privileged key (unsealed by the caller after re-authentication).
    ///
    /// # Errors
    /// [`AppError::UnknownDevice`], [`AppError::NotActive`], [`AppError::InvalidInput`].
    pub fn command(
        &mut self,
        device_id: &[u8],
        command: Command,
        privileged: Option<&SigningKeypair>,
        now_ms: i64,
    ) -> AppResult<Action> {
        let index = self
            .data
            .device_index(device_id)
            .ok_or(AppError::UnknownDevice)?;
        if self.data.devices[index].state != PairingState::Active {
            return Err(AppError::NotActive);
        }
        if is_sensitive(&command) {
            let pk = privileged.ok_or(AppError::InvalidInput)?;
            if pk.public().as_slice() != self.data.privileged_public.as_slice() {
                return Err(AppError::WrongPassword);
            }
        }
        let kind = command_kind(&command);
        let ttl = command_ttl(&command);
        let privileged = if is_sensitive(&command) {
            privileged
        } else {
            None
        };
        let message_id = random::bytes::<16>();
        let action = self.seal_with_id(
            index,
            message_body::Payload::Command(command),
            ttl,
            privileged,
            now_ms,
            message_id,
        )?;
        let record = &mut self.data.devices[index];
        record.commands.push_back(SentCommand {
            message_id: message_id.to_vec(),
            kind: kind.to_owned(),
            sent_ms: now_ms,
            status: "sent".to_owned(),
            reason: String::new(),
        });
        while record.commands.len() > MAX_COMMANDS {
            record.commands.pop_front();
        }
        let snapshot = record.clone();
        self.data.log(
            now_ms,
            Some(&snapshot),
            "command.sent",
            Some(kind.to_owned()),
        );
        Ok(action)
    }

    /// Sends a fire-and-forget remote-input command to an active device. Unlike [`command`],
    /// nothing is recorded in the command history or journal (taps fire several times a second),
    /// and a short TTL means a tap that could not be delivered quickly is dropped by the phone
    /// instead of landing late.
    ///
    /// # Errors
    /// [`AppError::UnknownDevice`], [`AppError::NotActive`], [`AppError::InvalidInput`].
    pub fn input(&mut self, device_id: &[u8], command: Command, now_ms: i64) -> AppResult<Action> {
        if !matches!(command.kind, Some(command::Kind::RemoteInput(_))) {
            return Err(AppError::InvalidInput);
        }
        self.fire_and_forget(device_id, command, now_ms)
    }

    /// Sends a fire-and-forget voice chunk (controller→phone intercom audio) to an active device.
    /// Like [`input`], it is untracked and short-lived so a late chunk is dropped, not played late.
    ///
    /// # Errors
    /// [`AppError::UnknownDevice`], [`AppError::NotActive`], [`AppError::InvalidInput`].
    pub fn audio_play(
        &mut self,
        device_id: &[u8],
        command: Command,
        now_ms: i64,
    ) -> AppResult<Action> {
        if !matches!(command.kind, Some(command::Kind::AudioPlay(_))) {
            return Err(AppError::InvalidInput);
        }
        self.fire_and_forget(device_id, command, now_ms)
    }

    fn fire_and_forget(
        &mut self,
        device_id: &[u8],
        command: Command,
        now_ms: i64,
    ) -> AppResult<Action> {
        let index = self
            .data
            .device_index(device_id)
            .ok_or(AppError::UnknownDevice)?;
        if self.data.devices[index].state != PairingState::Active {
            return Err(AppError::NotActive);
        }
        self.seal_to(
            index,
            message_body::Payload::Command(command),
            INPUT_TTL_SECONDS,
            None,
            now_ms,
        )
    }

    fn seal_to(
        &mut self,
        index: usize,
        payload: message_body::Payload,
        ttl: u32,
        privileged: Option<&SigningKeypair>,
        now_ms: i64,
    ) -> AppResult<Action> {
        self.seal_with_id(index, payload, ttl, privileged, now_ms, random::bytes())
    }

    /// Whether the controller's own `XK` is due for rotation (PROTOCOL.md §4).
    #[must_use]
    pub fn should_rotate_self(&self, now_ms: i64) -> bool {
        let has_active = self
            .data
            .devices
            .iter()
            .any(|d| d.state == PairingState::Active);
        let last = if self.data.exchange_rotated_ms > 0 {
            self.data.exchange_rotated_ms
        } else {
            self.data
                .devices
                .iter()
                .map(|d| d.paired_at_ms)
                .min()
                .unwrap_or(now_ms)
        };
        has_active && now_ms - last >= ROTATION_INTERVAL_MS
    }

    /// Rotates the controller's `XK`: a signed `KeyRotation` is sent to every active device
    /// under the current epoch, then the new key becomes current for later messages.
    ///
    /// # Errors
    /// [`AppError::Internal`].
    pub fn rotate_self(&mut self, now_ms: i64) -> AppResult<Vec<Action>> {
        let new_epoch = self.exchange.epoch() + 1;
        let new_xk = ExchangeKeypair::generate(new_epoch);
        let keys = new_xk.signed_by(&self.identity);
        let rotation = KeyRotation {
            new_epoch,
            x25519_public_key: keys.exchange.to_vec(),
            x25519_signature: keys.exchange_signature.to_vec(),
        };
        let active: Vec<usize> = self
            .data
            .devices
            .iter()
            .enumerate()
            .filter(|(_, d)| d.state == PairingState::Active)
            .map(|(i, _)| i)
            .collect();
        let mut actions = Vec::with_capacity(active.len());
        for index in active {
            let payload = message_body::Payload::KeyRotation(rotation.clone());
            actions.push(self.seal_to(index, payload, EVENT_TTL_MAX_S, None, now_ms)?);
        }
        // Swap to the new key only after every rotation message was sealed under the old one.
        self.exchange = new_xk;
        self.data.exchange_secret = Secret(self.exchange.secret().to_vec());
        self.data.exchange_epoch = new_epoch;
        self.data.exchange_rotated_ms = now_ms;
        self.data.log(now_ms, None, "keys.rotated", None);
        Ok(actions)
    }

    fn seal_with_id(
        &mut self,
        index: usize,
        payload: message_body::Payload,
        ttl: u32,
        privileged: Option<&SigningKeypair>,
        now_ms: i64,
        message_id: [u8; 16],
    ) -> AppResult<Action> {
        let (to_phone, _) = self.session_keys(&self.data.devices[index])?;
        let record = &mut self.data.devices[index];
        record.send_counter += 1;
        let body = MessageBody {
            protocol_version: record.protocol_version,
            message_id: message_id.to_vec(),
            counter: record.send_counter,
            timestamp_ms: now_ms,
            ttl_seconds: ttl,
            payload: Some(payload),
        };
        let recipient: DeviceId = fixed(&record.device_id).map_err(|_| AppError::Internal)?;
        let header = Header::new(self.identity.device_id(), recipient, self.exchange.epoch());
        let envelope = envelope::seal(
            &to_phone,
            &header,
            &self.identity,
            privileged,
            &body.encode_to_vec(),
        );
        Ok(Action::Send {
            recipient,
            envelope,
        })
    }

    /// Replaces the geofences watched by a device and sends them to the phone.
    ///
    /// # Errors
    /// [`AppError::UnknownDevice`], [`AppError::NotActive`].
    pub fn set_geofences(
        &mut self,
        device_id: &[u8],
        zones: Vec<GeofenceDef>,
        now_ms: i64,
    ) -> AppResult<Action> {
        let proto = bastion_proto::v1::SetGeofences {
            zones: zones
                .iter()
                .map(|z| bastion_proto::v1::Geofence {
                    id: z.id.clone(),
                    latitude: z.latitude,
                    longitude: z.longitude,
                    radius_m: z.radius_m,
                    name: z.name.clone(),
                })
                .collect(),
        };
        let command = Command {
            kind: Some(command::Kind::SetGeofences(proto)),
        };
        let action = self.command(device_id, command, None, now_ms)?;
        if let Some(index) = self.data.device_index(device_id) {
            self.data.devices[index].geofences = zones;
        }
        Ok(action)
    }

    /// Forgets a device locally and unlinks it on the relay (the phone is not told).
    ///
    /// # Errors
    /// [`AppError::UnknownDevice`].
    pub fn forget(&mut self, device_id: &[u8], now_ms: i64) -> AppResult<Action> {
        let index = self
            .data
            .device_index(device_id)
            .ok_or(AppError::UnknownDevice)?;
        let removed = self.data.devices.remove(index);
        self.data
            .log(now_ms, Some(&removed), "pairing.forgotten", None);
        Ok(Action::Revoke {
            device: fixed(&removed.device_id).map_err(|_| AppError::Internal)?,
        })
    }
}

fn camel(screaming: &str, prefix: &str) -> String {
    let rest = screaming.strip_prefix(prefix).unwrap_or(screaming);
    let mut out = String::new();
    for (i, word) in rest.split('_').enumerate() {
        let lower = word.to_ascii_lowercase();
        if i == 0 {
            out.push_str(&lower);
        } else {
            let mut chars = lower.chars();
            if let Some(first) = chars.next() {
                out.push(first.to_ascii_uppercase());
                out.extend(chars);
            }
        }
    }
    out
}

fn clean(text: &str) -> String {
    text.chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
        .take(32)
        .collect()
}

fn push_location(record: &mut DeviceRecord, loc: &bastion_proto::v1::Location) {
    let valid = loc.latitude.is_finite()
        && loc.longitude.is_finite()
        && (-90.0..=90.0).contains(&loc.latitude)
        && (-180.0..=180.0).contains(&loc.longitude)
        && loc.accuracy_m.is_finite()
        && loc.accuracy_m >= 0.0;
    if !valid {
        return;
    }
    let provider = match location::Provider::try_from(loc.provider) {
        Ok(location::Provider::Gps) => "gps",
        Ok(location::Provider::Network) => "network",
        Ok(location::Provider::Passive) => "passive",
        _ => "unknown",
    };
    record.locations.push_back(LocationPoint {
        latitude: loc.latitude,
        longitude: loc.longitude,
        accuracy_m: loc.accuracy_m,
        fix_time_ms: loc.fix_time_ms,
        provider: provider.to_owned(),
        speed_mps: loc.speed_mps.filter(|s| s.is_finite()),
    });
    while record.locations.len() > MAX_LOCATIONS {
        record.locations.pop_front();
    }
}

fn status_view(status: &bastion_proto::v1::StatusReport, now_ms: i64) -> StatusView {
    use bastion_proto::v1::{NetworkType, TrackingMode};
    let network = match NetworkType::try_from(status.network) {
        Ok(NetworkType::None) => "none",
        Ok(NetworkType::Wifi) => "wifi",
        Ok(NetworkType::Cellular) => "cellular",
        Ok(NetworkType::Ethernet) => "ethernet",
        Ok(NetworkType::Other) => "other",
        _ => "unknown",
    };
    let tracking = match TrackingMode::try_from(status.tracking_mode) {
        Ok(TrackingMode::Standby) => "standby",
        Ok(TrackingMode::Active) => "active",
        Ok(TrackingMode::Lost) => "lost",
        _ => "unspecified",
    };
    StatusView {
        battery_percent: status.battery.map(|b| b.level_percent.min(100)),
        charging: status.battery.is_some_and(|b| b.charging),
        network: network.to_owned(),
        tracking_mode: tracking.to_owned(),
        lost_mode: status.lost_mode,
        health: status
            .health
            .iter()
            .take(32)
            .map(|h| HealthView {
                feature: clean(&h.feature),
                state: match protection_health::State::try_from(h.state) {
                    Ok(protection_health::State::Active) => "active",
                    Ok(protection_health::State::Disabled) => "disabled",
                    Ok(protection_health::State::Degraded) => "degraded",
                    _ => "unknown",
                }
                .to_owned(),
                reason: clean(&h.reason_code),
            })
            .collect(),
        app_version: clean(&status.app_version),
        received_ms: now_ms,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bastion_proto::v1::{LocationReport, Ring, StatusReport};

    const NOW: i64 = 1_760_000_000_000;

    struct Phone {
        ik: SigningKeypair,
        xk: ExchangeKeypair,
        counter: u64,
    }

    impl Phone {
        fn new() -> Self {
            Self {
                ik: SigningKeypair::generate(),
                xk: ExchangeKeypair::generate(0),
                counter: 0,
            }
        }

        fn hello(&self, session: &Session, token: &Token) -> Vec<u8> {
            let keys = self.xk.signed_by(&self.ik);
            let proof = pairing::enroll_proof(
                token,
                &keys.identity,
                &keys.exchange,
                &[],
                &session.identity.public(),
            );
            let hello = PairingHello {
                device: Some(keys.to_proto()),
                proof: proof.to_vec(),
                device_label: "Pixel\u{1b}[31m".to_owned(),
                max_protocol_version: 1,
            };
            bastion_crypto::sealed::seal(&session.exchange.public(), &hello.encode_to_vec())
                .unwrap()
        }

        fn envelope(
            &mut self,
            session: &Session,
            payload: message_body::Payload,
            timestamp: i64,
        ) -> Vec<u8> {
            self.counter += 1;
            let body = MessageBody {
                protocol_version: 1,
                message_id: random::bytes::<16>().to_vec(),
                counter: self.counter,
                timestamp_ms: timestamp,
                ttl_seconds: 3600,
                payload: Some(payload),
            };
            let me = Party {
                identity: self.ik.public(),
                exchange: self.xk.public(),
            };
            let pc = Party {
                identity: session.identity.public(),
                exchange: session.exchange.public(),
            };
            let key = directional_key(&self.xk, &me, &pc).unwrap();
            let header = Header::new(
                self.ik.device_id(),
                session.identity.device_id(),
                self.xk.epoch(),
            );
            envelope::seal(&key, &header, &self.ik, None, &body.encode_to_vec())
        }

        // Rotates the phone's X25519 key: the KeyRotation is sealed under the old key, then the
        // new key becomes current.
        fn rotate(&mut self, session: &Session, timestamp: i64) -> Vec<u8> {
            let new_xk = ExchangeKeypair::generate(self.xk.epoch() + 1);
            let keys = new_xk.signed_by(&self.ik);
            let rotation = message_body::Payload::KeyRotation(KeyRotation {
                new_epoch: new_xk.epoch(),
                x25519_public_key: keys.exchange.to_vec(),
                x25519_signature: keys.exchange_signature.to_vec(),
            });
            let envelope = self.envelope(session, rotation, timestamp);
            self.xk = new_xk;
            envelope
        }

        fn open(&self, session: &Session, action: &Action) -> MessageBody {
            let Action::Send {
                envelope: bytes, ..
            } = action
            else {
                panic!("not a send")
            };
            let me = Party {
                identity: self.ik.public(),
                exchange: self.xk.public(),
            };
            let pc = Party {
                identity: session.identity.public(),
                exchange: session.exchange.public(),
            };
            let key = directional_key(&self.xk, &pc, &me).unwrap();
            let parsed = envelope::parse(bytes).unwrap();
            let pk: [u8; 32] = session
                .data
                .privileged_public
                .as_slice()
                .try_into()
                .unwrap();
            let opened =
                envelope::open(&parsed, &[&key], &session.identity.public(), Some(&pk)).unwrap();
            MessageBody::decode(opened.body.as_slice()).unwrap()
        }
    }

    fn session() -> Session {
        Session::create(
            RelaySettings::Embedded {
                port: 8443,
                advertised_host: None,
            },
            "correct horse battery",
            KdfParams::INTERACTIVE,
        )
        .unwrap()
    }

    fn paired() -> (Session, Phone) {
        let mut s = session();
        let mut phone = Phone::new();
        let (token, _, _) = s.new_invite(NOW);
        let hello = phone.hello(&s, &token);
        let (_, notices, _) = s.handle_item(2, &phone.ik.device_id(), &hello, NOW);
        assert_eq!(notices, vec![Notice::PairingRequest]);
        let id = phone.ik.device_id();
        let actions = s.confirm_pairing(&id, true, NOW).unwrap();
        assert!(phone.open(&s, &actions[0]).payload.is_some());
        let confirm = message_body::Payload::PairingConfirm(PairingConfirm {
            confirmed: true,
            negotiated_protocol_version: 1,
        });
        let env = phone.envelope(&s, confirm, NOW);
        s.handle_item(1, &id, &env, NOW);
        assert_eq!(s.data.devices[0].state, PairingState::Active);
        (s, phone)
    }

    #[test]
    fn pairing_computes_the_same_sas_as_the_phone() {
        let mut s = session();
        let phone = Phone::new();
        let (token, _, _) = s.new_invite(NOW);
        s.handle_item(2, &phone.ik.device_id(), &phone.hello(&s, &token), NOW);
        let record = &s.data.devices[0];
        assert_eq!(record.state, PairingState::PendingSas);
        assert_eq!(record.label, "Pixel[31m");
        let expected = pairing::sas_code(&token, &phone.xk.signed_by(&phone.ik), &s.keys());
        assert_eq!(record.sas, expected);
    }

    #[test]
    fn hello_without_matching_invite_is_ignored() {
        let mut s = session();
        let phone = Phone::new();
        let foreign = pairing::generate_token();
        s.new_invite(NOW);
        s.handle_item(2, &phone.ik.device_id(), &phone.hello(&s, &foreign), NOW);
        assert_eq!(s.data.devices, Vec::new());
        // Expired invitation.
        let (token, _, _) = s.new_invite(NOW);
        s.handle_item(
            2,
            &phone.ik.device_id(),
            &phone.hello(&s, &token),
            NOW + INVITE_TTL_MS + 1,
        );
        assert_eq!(s.data.devices, Vec::new());
    }

    #[test]
    fn an_active_pairing_survives_a_vault_round_trip() {
        let (s, mut phone) = paired();
        // Simulate a restart: the vault is serialized, then a fresh Session is rebuilt from it.
        let json = serde_json::to_vec(&s.data).unwrap();
        let data: VaultData = serde_json::from_slice(&json).unwrap();
        let mut restored = Session::from_data(data).unwrap();
        assert_eq!(restored.data.devices.len(), 1);
        assert_eq!(restored.data.devices[0].state, PairingState::Active);
        // The restored session can still open the phone's messages and send commands.
        let status = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Status(bastion_proto::v1::StatusReport {
                lost_mode: true,
                ..Default::default()
            })),
        });
        restored.handle_item(
            1,
            &phone.ik.device_id(),
            &phone.envelope(&restored, status, NOW),
            NOW,
        );
        assert!(restored.data.devices[0].status.as_ref().unwrap().lost_mode);
        let ring = Command {
            kind: Some(command::Kind::Ring(Ring::default())),
        };
        let id = phone.ik.device_id();
        assert!(restored.command(&id, ring, None, NOW).is_ok());
    }

    #[test]
    fn commands_need_an_active_pairing_and_sensitive_ones_the_privileged_key() {
        let mut s = session();
        let phone = Phone::new();
        let (token, _, _) = s.new_invite(NOW);
        s.handle_item(2, &phone.ik.device_id(), &phone.hello(&s, &token), NOW);
        let ring = Command {
            kind: Some(command::Kind::Ring(Ring::default())),
        };
        let id = phone.ik.device_id();
        assert_eq!(
            s.command(&id, ring.clone(), None, NOW),
            Err(AppError::NotActive)
        );

        let (mut s, phone) = paired();
        let action = s.command(&id_of(&phone), ring, None, NOW).unwrap();
        let body = phone.open(&s, &action);
        assert_eq!(body.counter, 2, "PairingConfirm used counter 1");
        let lock = Command {
            kind: Some(command::Kind::Lock(bastion_proto::v1::Lock::default())),
        };
        assert_eq!(
            s.command(&id_of(&phone), lock.clone(), None, NOW),
            Err(AppError::InvalidInput)
        );
        let wrong_pk = SigningKeypair::generate();
        assert_eq!(
            s.command(&id_of(&phone), lock.clone(), Some(&wrong_pk), NOW),
            Err(AppError::WrongPassword)
        );
        let seed = s
            .data
            .privileged_sealed
            .open("correct horse battery")
            .unwrap();
        let pk = SigningKeypair::from_seed(&seed);
        assert!(s.command(&id_of(&phone), lock, Some(&pk), NOW).is_ok());
    }

    fn id_of(phone: &Phone) -> Vec<u8> {
        phone.ik.device_id().to_vec()
    }

    #[test]
    fn events_are_applied_once_and_stale_ones_dropped() {
        let (mut s, mut phone) = paired();
        let id = phone.ik.device_id();
        let loc = bastion_proto::v1::Location {
            latitude: 48.85,
            longitude: 2.35,
            accuracy_m: 12.0,
            fix_time_ms: NOW,
            ..Default::default()
        };
        let report = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Location(LocationReport {
                locations: vec![loc],
            })),
        });
        let env = phone.envelope(&s, report.clone(), NOW);
        s.handle_item(1, &id, &env, NOW);
        s.handle_item(1, &id, &env, NOW);
        assert_eq!(s.data.devices[0].locations.len(), 1, "replay ignored");

        let stale = phone.envelope(&s, report.clone(), NOW - 2 * 3600 * 1000);
        s.handle_item(1, &id, &stale, NOW);
        let future = phone.envelope(&s, report, NOW + SKEW_MS + 1000);
        s.handle_item(1, &id, &future, NOW);
        assert_eq!(s.data.devices[0].locations.len(), 1);

        let status = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Status(StatusReport {
                lost_mode: true,
                ..Default::default()
            })),
        });
        let env = phone.envelope(&s, status, NOW);
        s.handle_item(1, &id, &env, NOW);
        assert!(s.data.devices[0].status.as_ref().unwrap().lost_mode);
    }

    #[test]
    fn invalid_coordinates_are_dropped() {
        let (mut s, mut phone) = paired();
        let id = phone.ik.device_id();
        let bad = bastion_proto::v1::Location {
            latitude: f64::NAN,
            longitude: 400.0,
            ..Default::default()
        };
        let report = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Location(LocationReport {
                locations: vec![bad],
            })),
        });
        let env = phone.envelope(&s, report, NOW);
        s.handle_item(1, &id, &env, NOW);
        assert!(s.data.devices[0].locations.is_empty());
    }

    #[test]
    fn rejection_by_the_phone_removes_the_device() {
        let mut s = session();
        let mut phone = Phone::new();
        let (token, _, _) = s.new_invite(NOW);
        s.handle_item(2, &phone.ik.device_id(), &phone.hello(&s, &token), NOW);
        let no = message_body::Payload::PairingConfirm(PairingConfirm {
            confirmed: false,
            negotiated_protocol_version: 1,
        });
        let env = phone.envelope(&s, no, NOW);
        let (actions, _, _) = s.handle_item(1, &phone.ik.device_id(), &env, NOW);
        assert_eq!(s.data.devices, Vec::new());
        assert_eq!(
            actions,
            vec![Action::Revoke {
                device: phone.ik.device_id()
            }]
        );
    }

    #[test]
    fn events_before_activation_are_refused() {
        let mut s = session();
        let mut phone = Phone::new();
        let (token, _, _) = s.new_invite(NOW);
        s.handle_item(2, &phone.ik.device_id(), &phone.hello(&s, &token), NOW);
        let status = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Status(StatusReport::default())),
        });
        let env = phone.envelope(&s, status, NOW);
        s.handle_item(1, &phone.ik.device_id(), &env, NOW);
        assert!(s.data.devices[0].status.is_none());
    }

    #[test]
    fn enum_names_are_camel_cased() {
        assert_eq!(camel("TYPE_SIM_REMOVED", "TYPE_"), "simRemoved");
        assert_eq!(camel("REASON_SHUTDOWN", "REASON_"), "shutdown");
    }

    #[test]
    fn phone_key_rotation_is_applied_and_comms_continue() {
        let (mut s, mut phone) = paired();
        let id = phone.ik.device_id();
        assert_eq!(s.data.devices[0].epoch, 0);
        // The phone rotates; the controller must adopt the new epoch and key.
        let rotation = phone.rotate(&s, NOW);
        s.handle_item(1, &id, &rotation, NOW);
        assert_eq!(s.data.devices[0].epoch, 1);
        // A message under the new key is accepted; a replay of the rotation is ignored.
        let status = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Status(bastion_proto::v1::StatusReport {
                lost_mode: true,
                ..Default::default()
            })),
        });
        s.handle_item(1, &id, &phone.envelope(&s, status, NOW), NOW);
        assert!(s.data.devices[0].status.as_ref().unwrap().lost_mode);
        assert!(!s.should_rotate_self(NOW));
    }

    #[test]
    fn controller_rotates_its_own_key_when_due() {
        let (mut s, _phone) = paired();
        assert!(!s.should_rotate_self(NOW));
        // Eight days later it is due; rotation bumps the epoch and messages each active device.
        let later = NOW + 8 * 24 * 3600 * 1000;
        assert!(s.should_rotate_self(later));
        let before = s.data.exchange_epoch;
        let actions = s.rotate_self(later).unwrap();
        assert_eq!(actions.len(), 1);
        assert!(matches!(actions[0], Action::Send { .. }));
        assert_eq!(s.data.exchange_epoch, before + 1);
        assert_eq!(s.exchange.epoch(), before + 1);
        assert!(!s.should_rotate_self(later));
    }

    fn jpeg(byte: u8) -> Vec<u8> {
        let mut v = vec![0xff, 0xd8, 0xff];
        v.extend(std::iter::repeat_n(byte, 64));
        v
    }

    #[test]
    fn photos_are_stored_with_metadata_and_bytes_forwarded() {
        use bastion_proto::v1::{PhotoReport, capture_photo, photo_report};
        let (mut s, mut phone) = paired();
        let id = phone.ik.device_id();
        let report = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Photo(PhotoReport {
                jpeg: jpeg(7),
                captured_at_ms: NOW,
                camera: capture_photo::Camera::Front as i32,
                trigger: photo_report::Trigger::FailedUnlock as i32,
                command_message_id: Vec::new(),
                location: None,
            })),
        });
        let (_, notices, media) = s.handle_item(1, &id, &phone.envelope(&s, report, NOW), NOW);
        assert!(notices.contains(&Notice::Photo));
        let photo = &s.data.devices[0].photos[0];
        assert_eq!(photo.camera, "front");
        assert_eq!(photo.trigger, "failedUnlock");
        match &media[..] {
            [
                Media::Photo {
                    photo_id,
                    jpeg: bytes,
                    ..
                },
            ] => {
                assert_eq!(photo_id, &photo.id);
                assert_eq!(bytes, &jpeg(7));
            }
            other => panic!("expected one photo, got {other:?}"),
        }

        // A non-JPEG payload is rejected before storage.
        let bad = message_body::Payload::Event(Event {
            kind: Some(event::Kind::Photo(PhotoReport {
                jpeg: vec![0u8; 32],
                ..Default::default()
            })),
        });
        let (_, _, media) = s.handle_item(1, &id, &phone.envelope(&s, bad, NOW), NOW);
        assert_eq!(media, Vec::new());
        assert_eq!(s.data.devices[0].photos.len(), 1);
    }

    #[test]
    fn stream_frames_are_forwarded_not_stored() {
        use bastion_proto::v1::MediaFrame;
        let (mut s, mut phone) = paired();
        let id = phone.ik.device_id();
        let frame = message_body::Payload::Event(Event {
            kind: Some(event::Kind::MediaFrame(MediaFrame {
                sequence: 5,
                jpeg: jpeg(3),
                ..Default::default()
            })),
        });
        let (_, notices, media) = s.handle_item(1, &id, &phone.envelope(&s, frame, NOW), NOW);
        assert_eq!(notices, Vec::new());
        assert!(s.data.devices[0].photos.is_empty());
        match &media[..] {
            [
                Media::Frame {
                    sequence,
                    jpeg: bytes,
                    ..
                },
            ] => {
                assert_eq!(*sequence, 5);
                assert_eq!(bytes, &jpeg(3));
            }
            other => panic!("expected one frame, got {other:?}"),
        }
    }
}
