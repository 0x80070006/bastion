// SPDX-License-Identifier: GPL-3.0-or-later
//! Application core: vault lifecycle, embedded relay, mailbox polling, auto-lock.

use std::collections::HashMap;
use std::net::{IpAddr, TcpListener, UdpSocket};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bastion_crypto::identity::{DeviceId, SigningKeypair};
use bastion_crypto::vault::KdfParams;
use bastion_relay::{Config as RelayConfig, Relay};
use serde::{Deserialize, Serialize};
use tauri::async_runtime::JoinHandle;
use tokio::sync::oneshot;

use crate::engine::{Action, Session};
use crate::error::{AppError, AppResult};
use crate::model::RelaySettings;
use crate::relay_client::RelayClient;
use crate::vault::{self, VaultKey};

/// Default port of the embedded relay.
pub const DEFAULT_PORT: u16 = 8443;
const POLL_WAIT_SECONDS: u32 = 25;

/// Wall clock in Unix epoch milliseconds.
#[must_use]
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// File locations.
#[derive(Debug, Clone)]
pub struct Paths {
    /// Encrypted vault.
    pub vault: PathBuf,
    /// Non-secret launch settings (whether to start the embedded relay before unlock).
    pub launch: PathBuf,
    /// Embedded relay data (database, TLS key).
    pub relay_dir: PathBuf,
    /// Map tile cache.
    pub tiles: PathBuf,
    /// Encrypted photos (one file per photo, named by id).
    pub media: PathBuf,
}

impl Paths {
    /// Layout under the application data and cache directories.
    #[must_use]
    pub fn new(data: &std::path::Path, cache: &std::path::Path) -> Self {
        Self {
            vault: data.join("vault.bin"),
            launch: data.join("launch.json"),
            relay_dir: data.join("relay"),
            tiles: cache.join("tiles"),
            media: data.join("media"),
        }
    }
}

/// Non-secret settings readable while locked. Only decides whether to start the embedded
/// relay; the authoritative relay configuration lives in the vault.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Launch {
    embedded_port: Option<u16>,
}

/// Connection state shown in the UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Connection {
    /// No session.
    Idle,
    /// Last relay request succeeded.
    Online,
    /// Relay unreachable (retrying).
    Offline,
    /// Relay TLS key changed.
    PinMismatch,
}

struct EmbeddedRelay {
    relay: Relay,
    port: u16,
    stop: Option<oneshot::Sender<()>>,
}

impl Drop for EmbeddedRelay {
    fn drop(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
    }
}

struct Unlocked {
    session: Session,
    key: VaultKey,
    client: RelayClient,
}

/// UI notification hook (Tauri event emitter in production).
pub type Emitter = Arc<dyn Fn(UiEvent) + Send + Sync>;

/// Pushes a decrypted live stream frame (device id, sequence, JPEG) to the webview.
pub type FrameSink = Arc<dyn Fn(&[u8], u64, &[u8]) + Send + Sync>;

/// Pushes a decrypted live audio chunk (device id, sequence, PCM, sample rate) to the webview.
pub type AudioSink = Arc<dyn Fn(&[u8], u64, &[u8], u32) + Send + Sync>;

/// Pushes a decrypted live screen frame (device id, sequence, JPEG, width, height, locked) to
/// the webview.
pub type ScreenSink = Arc<dyn Fn(&[u8], u64, &[u8], u32, u32, bool) + Send + Sync>;

/// Events pushed to the webview.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UiEvent {
    /// Data changed: refresh views.
    Changed,
    /// Something needs the user's attention (pairing request, alert).
    Attention,
    /// The vault locked itself.
    Locked,
}

/// Shared application core.
pub struct AppCore {
    paths: Paths,
    state: tokio::sync::Mutex<Option<Unlocked>>,
    embedded: Mutex<Option<EmbeddedRelay>>,
    emit: Emitter,
    frames: FrameSink,
    audio: AudioSink,
    screen: ScreenSink,
    generation: AtomicU64,
    poller: Mutex<Option<JoinHandle<()>>>,
    connection: Mutex<Connection>,
    last_activity_ms: AtomicI64,
    auto_lock_minutes: AtomicU32,
    failed_unlocks: AtomicU32,
    next_unlock_ms: AtomicI64,
    armed_wipes: Mutex<HashMap<Vec<u8>, i64>>,
}

impl std::fmt::Debug for AppCore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AppCore").finish_non_exhaustive()
    }
}

/// Delay a wipe must wait after being armed (PROTOCOL.md §7).
pub const WIPE_DELAY_MS: i64 = 30_000;
const WIPE_ARM_VALIDITY_MS: i64 = 5 * 60_000;

/// Detects the LAN address announced to phones (no packet is sent).
#[must_use]
pub fn detect_lan_address() -> Option<IpAddr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    let ip = socket.local_addr().ok()?.ip();
    (!ip.is_unspecified() && !ip.is_loopback()).then_some(ip)
}

fn host_for_url(host: &str) -> String {
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V6(v6)) => format!("[{v6}]"),
        _ => host.to_owned(),
    }
}

/// Inputs of the first-run setup.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "mode", rename_all = "camelCase")]
pub enum RelaySetup {
    /// Embedded relay.
    #[serde(rename_all = "camelCase")]
    Embedded {
        /// Listening port.
        port: u16,
        /// Host announced to phones; auto-detected when empty.
        advertised_host: Option<String>,
    },
    /// Remote relay.
    #[serde(rename_all = "camelCase")]
    Remote {
        /// `https://host:port`.
        url: String,
        /// Hex SPKI pin printed by `bastion-relay pin`.
        pin_hex: String,
        /// Token printed by `bastion-relay admin-token`.
        admin_token: String,
    },
}

/// Valid host name or IP literal for the QR code (no scheme, port or path).
#[must_use]
pub fn is_valid_host(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= 253
        && (host.parse::<IpAddr>().is_ok()
            || host.split('.').all(|label| {
                !label.is_empty()
                    && label.len() <= 63
                    && label.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
                    && !label.starts_with('-')
                    && !label.ends_with('-')
            }))
}

impl AppCore {
    /// Creates the core; starts the embedded relay right away if configured.
    pub fn new(
        paths: Paths,
        emit: Emitter,
        frames: FrameSink,
        audio: AudioSink,
        screen: ScreenSink,
    ) -> Arc<Self> {
        let core = Arc::new(Self {
            paths,
            state: tokio::sync::Mutex::new(None),
            embedded: Mutex::new(None),
            emit,
            frames,
            audio,
            screen,
            generation: AtomicU64::new(0),
            poller: Mutex::new(None),
            connection: Mutex::new(Connection::Idle),
            last_activity_ms: AtomicI64::new(now_ms()),
            auto_lock_minutes: AtomicU32::new(15),
            failed_unlocks: AtomicU32::new(0),
            next_unlock_ms: AtomicI64::new(0),
            armed_wipes: Mutex::new(HashMap::new()),
        });
        if let Some(port) = core.read_launch().embedded_port
            && let Err(error) = core.start_embedded(port)
        {
            tracing::warn!(%error, "embedded relay did not start");
        }
        core
    }

    fn read_launch(&self) -> Launch {
        std::fs::read(&self.paths.launch)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    /// Whether a vault exists.
    #[must_use]
    pub fn vault_exists(&self) -> bool {
        self.paths.vault.exists()
    }

    /// Tile cache directory.
    #[must_use]
    pub fn tiles_dir(&self) -> &std::path::Path {
        &self.paths.tiles
    }

    /// Current connection state.
    #[must_use]
    pub fn connection(&self) -> Connection {
        self.connection.lock().map_or(Connection::Idle, |c| *c)
    }

    fn set_connection(&self, value: Connection) {
        let changed = self
            .connection
            .lock()
            .is_ok_and(|mut c| std::mem::replace(&mut *c, value) != value);
        if changed {
            (self.emit)(UiEvent::Changed);
        }
    }

    /// Records user activity (auto-lock).
    pub fn touch(&self) {
        self.last_activity_ms.store(now_ms(), Ordering::Relaxed);
    }

    /// Port of the running embedded relay.
    #[must_use]
    pub fn embedded_port(&self) -> Option<u16> {
        self.embedded.lock().ok()?.as_ref().map(|e| e.port)
    }

    fn start_embedded(&self, port: u16) -> AppResult<Relay> {
        let mut slot = self.embedded.lock().map_err(|_| AppError::Internal)?;
        if let Some(running) = slot.as_ref()
            && running.port == port
        {
            return Ok(running.relay.clone());
        }
        let config = RelayConfig {
            listen_addr: std::net::SocketAddr::from(([0, 0, 0, 0], port)),
            access_log: false,
            data_dir: self.paths.relay_dir.clone(),
            tls: true,
        };
        let relay = Relay::open(&config).map_err(|error| {
            tracing::error!(%error, "embedded relay open failed");
            AppError::Storage
        })?;
        let listener = TcpListener::bind(config.listen_addr).map_err(|_| AppError::PortInUse)?;
        let (stop, stopped) = oneshot::channel::<()>();
        let serving = relay.clone();
        tauri::async_runtime::spawn(async move {
            let shutdown = async move {
                let _ = stopped.await;
            };
            if let Err(error) = serving.serve(listener, shutdown).await {
                tracing::error!(%error, "embedded relay stopped");
            }
        });
        *slot = Some(EmbeddedRelay {
            relay: relay.clone(),
            port,
            stop: Some(stop),
        });
        Ok(relay)
    }

    fn client_for(&self, settings: &RelaySettings, session: &Session) -> AppResult<RelayClient> {
        match settings {
            RelaySettings::Embedded { port, .. } => {
                let relay = self.start_embedded(*port)?;
                let pin = relay.spki_pin().ok_or(AppError::Internal)?;
                RelayClient::new(
                    &format!("https://127.0.0.1:{port}"),
                    pin,
                    session.identity(),
                )
            }
            RelaySettings::Remote { url, pin } => {
                let pin: [u8; 32] = pin
                    .as_slice()
                    .try_into()
                    .map_err(|_| AppError::VaultCorrupted)?;
                RelayClient::new(url, pin, session.identity())
            }
        }
    }

    /// Endpoint and pin announced in pairing QR codes.
    ///
    /// # Errors
    /// [`AppError::Locked`], [`AppError::RelayUnreachable`] when no LAN address is found.
    pub async fn announced_relay(&self) -> AppResult<(String, [u8; 32])> {
        let guard = self.state.lock().await;
        let unlocked = guard.as_ref().ok_or(AppError::Locked)?;
        match &unlocked.session.data.relay {
            RelaySettings::Embedded {
                port,
                advertised_host,
            } => {
                let host = advertised_host
                    .clone()
                    .filter(|h| !h.is_empty())
                    .or_else(|| detect_lan_address().map(|ip| ip.to_string()))
                    .ok_or(AppError::RelayUnreachable)?;
                let pin = self
                    .embedded
                    .lock()
                    .ok()
                    .and_then(|e| e.as_ref().and_then(|e| e.relay.spki_pin()))
                    .ok_or(AppError::RelayUnreachable)?;
                Ok((format!("https://{}:{port}", host_for_url(&host)), pin))
            }
            RelaySettings::Remote { url, pin } => Ok((
                url.clone(),
                pin.as_slice()
                    .try_into()
                    .map_err(|_| AppError::VaultCorrupted)?,
            )),
        }
    }

    /// First-run setup: creates keys, registers on the relay, writes the vault.
    ///
    /// # Errors
    /// Setup failures; nothing is written unless everything succeeded.
    pub async fn setup(self: &Arc<Self>, password: String, relay: RelaySetup) -> AppResult<()> {
        if self.vault_exists() {
            return Err(AppError::VaultExists);
        }
        vault::check_password_policy(&password)?;
        let (settings, admin_token) = match relay {
            RelaySetup::Embedded {
                port,
                advertised_host,
            } => {
                let host = advertised_host
                    .map(|h| h.trim().to_owned())
                    .filter(|h| !h.is_empty());
                if port < 1024 || host.as_deref().is_some_and(|h| !is_valid_host(h)) {
                    return Err(AppError::InvalidInput);
                }
                let relay = self.start_embedded(port)?;
                let token = relay
                    .create_admin_token(Duration::from_secs(300))
                    .map_err(|_| AppError::Internal)?;
                (
                    RelaySettings::Embedded {
                        port,
                        advertised_host: host,
                    },
                    token,
                )
            }
            RelaySetup::Remote {
                url,
                pin_hex,
                admin_token,
            } => {
                let mut pin = [0u8; 32];
                hex::decode_to_slice(pin_hex.trim(), &mut pin)
                    .map_err(|_| AppError::InvalidInput)?;
                (
                    RelaySettings::Remote {
                        url: url.trim().trim_end_matches('/').to_owned(),
                        pin: pin.to_vec(),
                    },
                    admin_token.trim().to_owned(),
                )
            }
        };
        let (session, key) = tauri::async_runtime::spawn_blocking({
            let settings = settings.clone();
            move || -> AppResult<(Session, VaultKey)> {
                let session = Session::create(settings, &password, KdfParams::MODERATE)?;
                let key = VaultKey::create(&password, KdfParams::MODERATE)?;
                Ok((session, key))
            }
        })
        .await
        .map_err(|_| AppError::Internal)??;
        let mut session = session;
        let client = self.client_for(&settings, &session)?;
        client.health().await?;
        client
            .enroll_controller(&session.keys(), &admin_token)
            .await?;
        session.data.controller_enrolled = true;
        session.data.log(now_ms(), None, "setup.done", None);
        if let Some(dir) = self.paths.vault.parent() {
            std::fs::create_dir_all(dir)?;
        }
        vault::write_atomic(&self.paths.vault, &key.seal(&session.data)?)?;
        let launch = Launch {
            embedded_port: match settings {
                RelaySettings::Embedded { port, .. } => Some(port),
                RelaySettings::Remote { .. } => None,
            },
        };
        vault::write_atomic(
            &self.paths.launch,
            &serde_json::to_vec(&launch).map_err(|_| AppError::Internal)?,
        )?;
        self.activate(Unlocked {
            session,
            key,
            client,
        })
        .await;
        Ok(())
    }

    /// Unlocks the vault with the master password (throttled after failures).
    ///
    /// # Errors
    /// [`AppError::WrongPassword`], [`AppError::Throttled`], [`AppError::NoVault`]…
    pub async fn unlock(self: &Arc<Self>, password: String) -> AppResult<()> {
        if !self.vault_exists() {
            return Err(AppError::NoVault);
        }
        if self.state.lock().await.is_some() {
            return Ok(());
        }
        if now_ms() < self.next_unlock_ms.load(Ordering::SeqCst) {
            return Err(AppError::Throttled);
        }
        let file = std::fs::read(&self.paths.vault)?;
        let opened = tauri::async_runtime::spawn_blocking(
            move || -> AppResult<(VaultKey, crate::model::VaultData)> {
                let key = VaultKey::for_file(&password, &file)?;
                let data = key.open(&file)?;
                Ok((key, data))
            },
        )
        .await
        .map_err(|_| AppError::Internal)?;
        let (key, data) = match opened {
            Ok(v) => v,
            Err(error) => {
                if error == AppError::WrongPassword {
                    let failures = self.failed_unlocks.fetch_add(1, Ordering::SeqCst) + 1;
                    let delay = 1000_i64 << failures.min(5);
                    self.next_unlock_ms
                        .store(now_ms() + delay, Ordering::SeqCst);
                }
                return Err(error);
            }
        };
        self.failed_unlocks.store(0, Ordering::SeqCst);
        let session = Session::from_data(data)?;
        let client = self.client_for(&session.data.relay.clone(), &session)?;
        self.activate(Unlocked {
            session,
            key,
            client,
        })
        .await;
        Ok(())
    }

    async fn activate(self: &Arc<Self>, unlocked: Unlocked) {
        self.auto_lock_minutes.store(
            unlocked.session.data.settings.auto_lock_minutes,
            Ordering::SeqCst,
        );
        let client = unlocked.client.clone();
        *self.state.lock().await = Some(unlocked);
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        self.touch();
        let core = Arc::clone(self);
        let handle =
            tauri::async_runtime::spawn(async move { core.poll_loop(client, generation).await });
        if let Ok(mut poller) = self.poller.lock()
            && let Some(old) = poller.replace(handle)
        {
            old.abort();
        }
        (self.emit)(UiEvent::Changed);
    }

    /// Locks: stops polling and drops every key from memory.
    pub async fn lock(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
        if let Ok(mut poller) = self.poller.lock()
            && let Some(handle) = poller.take()
        {
            handle.abort();
        }
        *self.state.lock().await = None;
        if let Ok(mut armed) = self.armed_wipes.lock() {
            armed.clear();
        }
        self.set_connection(Connection::Idle);
        (self.emit)(UiEvent::Locked);
    }

    /// Whether the vault is unlocked.
    pub async fn is_unlocked(&self) -> bool {
        self.state.lock().await.is_some()
    }

    /// Locks when the inactivity delay elapsed. Called periodically.
    pub async fn check_auto_lock(&self) {
        let minutes = self.auto_lock_minutes.load(Ordering::SeqCst);
        if minutes == 0 || !self.is_unlocked().await {
            return;
        }
        let idle = now_ms() - self.last_activity_ms.load(Ordering::Relaxed);
        if idle > i64::from(minutes) * 60_000 {
            self.lock().await;
        }
    }

    async fn poll_loop(self: Arc<Self>, client: RelayClient, generation: u64) {
        let mut backoff = 1u64;
        while self.generation.load(Ordering::SeqCst) == generation {
            match client.fetch(POLL_WAIT_SECONDS).await {
                Ok(batch) => {
                    backoff = 1;
                    self.set_connection(Connection::Online);
                    if !batch.items.is_empty() {
                        self.process(&client, generation, batch).await;
                    }
                    self.maybe_rotate_keys().await;
                }
                Err(error) => {
                    self.set_connection(if error == AppError::RelayPinMismatch {
                        Connection::PinMismatch
                    } else {
                        Connection::Offline
                    });
                    tokio::time::sleep(Duration::from_secs(backoff)).await;
                    backoff = (backoff * 2).min(60);
                }
            }
        }
    }

    async fn process(
        &self,
        client: &RelayClient,
        generation: u64,
        batch: bastion_proto::v1::MailboxBatch,
    ) {
        let mut actions = Vec::new();
        let mut notices = Vec::new();
        let mut media = Vec::new();
        let mut ids = Vec::new();
        {
            let mut guard = self.state.lock().await;
            let Some(unlocked) = guard.as_mut() else {
                return;
            };
            if self.generation.load(Ordering::SeqCst) != generation {
                return;
            }
            let now = now_ms();
            for item in &batch.items {
                let (a, n, m) =
                    unlocked
                        .session
                        .handle_item(item.kind, &item.sender_id, &item.payload, now);
                actions.extend(a);
                notices.extend(n);
                media.extend(m);
                ids.push(item.id);
            }
            // Persist (counters, replay windows, photo metadata) before storing bytes or acking.
            if let Err(error) = Self::save(&self.paths, unlocked) {
                tracing::error!(%error, "vault save failed; items left on the relay");
                return;
            }
            self.store_media(unlocked, media);
        }
        let _ = self.execute(client, actions).await;
        if let Err(error) = client.ack(ids).await {
            tracing::warn!(%error, "ack failed");
        }
        (self.emit)(UiEvent::Changed);
        if !notices.is_empty() {
            (self.emit)(UiEvent::Attention);
        }
    }

    async fn execute(&self, client: &RelayClient, actions: Vec<Action>) -> AppResult<()> {
        let mut result = Ok(());
        for action in actions {
            let outcome = match action {
                Action::Send {
                    recipient,
                    envelope,
                } => client.send(&recipient, envelope).await,
                Action::Revoke { device } => client.revoke(&device).await,
            };
            if let Err(error) = outcome {
                tracing::warn!(%error, "relay action failed");
                result = Err(error);
            }
        }
        result
    }

    fn save(paths: &Paths, unlocked: &Unlocked) -> AppResult<()> {
        vault::write_atomic(&paths.vault, &unlocked.key.seal(&unlocked.session.data)?)
    }

    /// Rotates the controller's X25519 key when due (PROTOCOL.md §4), persisting before send.
    async fn maybe_rotate_keys(&self) {
        let due = {
            let guard = self.state.lock().await;
            guard
                .as_ref()
                .is_some_and(|u| u.session.should_rotate_self(now_ms()))
        };
        if !due {
            return;
        }
        if let Err(error) = self.mutate(|s| Ok(((), s.rotate_self(now_ms())?))).await {
            tracing::warn!(%error, "key rotation failed");
        }
    }

    /// Persists photos to the encrypted media store and forwards live frames to the webview.
    fn store_media(&self, unlocked: &Unlocked, media: Vec<crate::engine::Media>) {
        use crate::engine::Media;
        let mut stored_photo = false;
        for item in media {
            match item {
                Media::Photo { photo_id, jpeg, .. } => {
                    if let Err(error) = std::fs::create_dir_all(&self.paths.media) {
                        tracing::error!(%error, "cannot create media directory");
                        continue;
                    }
                    let sealed = unlocked.key.seal_media(&photo_id, &jpeg);
                    let path = self
                        .paths
                        .media
                        .join(format!("{}.enc", hex::encode(&photo_id)));
                    if let Err(error) = vault::write_atomic(&path, &sealed) {
                        tracing::error!(%error, "cannot write photo");
                    } else {
                        stored_photo = true;
                    }
                }
                Media::Frame {
                    device_id,
                    sequence,
                    jpeg,
                } => (self.frames)(&device_id, sequence, &jpeg),
                Media::Audio {
                    device_id,
                    sequence,
                    pcm,
                    sample_rate,
                } => (self.audio)(&device_id, sequence, &pcm, sample_rate),
                Media::Screen {
                    device_id,
                    sequence,
                    jpeg,
                    width,
                    height,
                    locked,
                } => (self.screen)(&device_id, sequence, &jpeg, width, height, locked),
            }
        }
        if stored_photo {
            self.prune_media(unlocked);
        }
    }

    /// Deletes media files no longer referenced by any device's photo metadata.
    fn prune_media(&self, unlocked: &Unlocked) {
        let kept: std::collections::HashSet<String> = unlocked
            .session
            .data
            .devices
            .iter()
            .flat_map(|d| d.photos.iter().map(|p| hex::encode(&p.id)))
            .collect();
        let Ok(entries) = std::fs::read_dir(&self.paths.media) else {
            return;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if let Some(id) = name.strip_suffix(".enc")
                && !kept.contains(id)
            {
                let _ = std::fs::remove_file(entry.path());
            }
        }
    }

    /// Returns the JPEG of a stored photo.
    ///
    /// # Errors
    /// [`AppError::Locked`], [`AppError::UnknownDevice`] or [`AppError::VaultCorrupted`].
    pub async fn photo_bytes(&self, photo_id: &[u8]) -> AppResult<Vec<u8>> {
        let guard = self.state.lock().await;
        let unlocked = guard.as_ref().ok_or(AppError::Locked)?;
        let path = self
            .paths
            .media
            .join(format!("{}.enc", hex::encode(photo_id)));
        let sealed = std::fs::read(&path).map_err(|_| AppError::UnknownDevice)?;
        unlocked
            .key
            .open_media(photo_id, &sealed)
            .map(|bytes| bytes.to_vec())
    }

    /// Runs `f` on the unlocked session, persists, then performs the returned actions.
    ///
    /// # Errors
    /// [`AppError::Locked`] or the errors of `f` and of the relay.
    pub async fn mutate<T>(
        &self,
        f: impl FnOnce(&mut Session) -> AppResult<(T, Vec<Action>)>,
    ) -> AppResult<T> {
        let (value, actions, client) = {
            let mut guard = self.state.lock().await;
            let unlocked = guard.as_mut().ok_or(AppError::Locked)?;
            let (value, actions) = f(&mut unlocked.session)?;
            Self::save(&self.paths, unlocked)?;
            (value, actions, unlocked.client.clone())
        };
        self.touch();
        let delivered = self.execute(&client, actions).await;
        (self.emit)(UiEvent::Changed);
        delivered.map(|()| value)
    }

    /// Read-only access to the unlocked session.
    ///
    /// # Errors
    /// [`AppError::Locked`].
    pub async fn read<T>(&self, f: impl FnOnce(&Session) -> T) -> AppResult<T> {
        let guard = self.state.lock().await;
        guard
            .as_ref()
            .map(|u| f(&u.session))
            .ok_or(AppError::Locked)
    }

    /// Relay client of the session.
    ///
    /// # Errors
    /// [`AppError::Locked`].
    pub async fn client(&self) -> AppResult<RelayClient> {
        let guard = self.state.lock().await;
        guard
            .as_ref()
            .map(|u| u.client.clone())
            .ok_or(AppError::Locked)
    }

    /// Updates the auto-lock delay cache after a settings change.
    pub fn set_auto_lock(&self, minutes: u32) {
        self.auto_lock_minutes.store(minutes, Ordering::SeqCst);
    }

    /// Unseals the privileged key after re-authentication (Argon2id, off the async runtime).
    ///
    /// # Errors
    /// [`AppError::WrongPassword`], [`AppError::Locked`].
    pub async fn unseal_privileged(&self, password: String) -> AppResult<SigningKeypair> {
        let sealed = self.read(|s| s.data.privileged_sealed.clone()).await?;
        let seed = tauri::async_runtime::spawn_blocking(move || sealed.open(&password))
            .await
            .map_err(|_| AppError::Internal)??;
        Ok(SigningKeypair::from_seed(&seed))
    }

    /// Arms a wipe for `device`; it may be sent between 30 s and 5 min later.
    ///
    /// # Errors
    /// [`AppError::Internal`].
    pub fn arm_wipe(&self, device: &[u8]) -> AppResult<i64> {
        let now = now_ms();
        self.armed_wipes
            .lock()
            .map_err(|_| AppError::Internal)?
            .insert(device.to_vec(), now);
        Ok(now + WIPE_DELAY_MS)
    }

    /// Consumes an armed wipe if its delay elapsed.
    ///
    /// # Errors
    /// [`AppError::WipeNotArmed`].
    pub fn take_armed_wipe(&self, device: &[u8]) -> AppResult<()> {
        let now = now_ms();
        let mut armed = self.armed_wipes.lock().map_err(|_| AppError::Internal)?;
        match armed.get(device) {
            Some(at) if now - at >= WIPE_DELAY_MS && now - at <= WIPE_ARM_VALIDITY_MS => {
                armed.remove(device);
                Ok(())
            }
            _ => Err(AppError::WipeNotArmed),
        }
    }

    /// Cancels an armed wipe.
    pub fn disarm_wipe(&self, device: &[u8]) {
        if let Ok(mut armed) = self.armed_wipes.lock() {
            armed.remove(device);
        }
    }
}

/// Parses a hex device id coming from the UI.
///
/// # Errors
/// [`AppError::InvalidInput`].
pub fn parse_device_id(hex_id: &str) -> AppResult<DeviceId> {
    let mut id = [0u8; 16];
    hex::decode_to_slice(hex_id, &mut id).map_err(|_| AppError::InvalidInput)?;
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hosts_are_validated() {
        for ok in ["192.168.1.10", "relay.example.org", "fd77::1", "my-pc"] {
            assert!(is_valid_host(ok), "{ok}");
        }
        for bad in ["", "https://x", "a b", "host:8443", "-bad.example", "x/y"] {
            assert!(!is_valid_host(bad), "{bad}");
        }
        assert_eq!(host_for_url("fd77::1"), "[fd77::1]");
    }
}
