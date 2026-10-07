// SPDX-License-Identifier: AGPL-3.0-or-later
//! HTTP API (docs/PROTOCOL.md §8). Every body is Protobuf; every endpoint except health and
//! enrollment requires a `Bastion-Auth` signature. Errors are opaque machine codes.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::extract::{ConnectInfo, DefaultBodyLimit, Path, State};
use axum::http::{HeaderMap, Method, StatusCode, Uri, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post, put};
use axum::{Extension, Json, Router};
use bytes::Bytes;
use prost::Message;
use serde::Serialize;
use tokio::sync::Notify;

use bastion_crypto::identity::{DeviceId, DeviceKeys};
use bastion_crypto::request_auth::{self, AuthHeader};
use bastion_crypto::{envelope, hash, pairing};
use bastion_proto::v1::{
    CreateInviteRequest, EnrollRequest, EnrollResponse, MailboxAck, MailboxBatch, MailboxItem,
    RelayError, Role as ProtoRole,
};

use crate::limits::{NonceCache, RateLimiter};
use crate::store::{MailboxQuota, Peer, Role, Store, StoreError};

/// Largest accepted request body (an envelope plus framing).
pub const MAX_BODY_BYTES: usize = bastion_proto::MAX_ENVELOPE_BYTES + 1024;
/// Header carrying a single-use administration token (controller enrollment).
pub const ADMIN_TOKEN_HEADER: &str = "bastion-admin-token";
const MAX_SEALED_HELLO_BYTES: usize = 4096;
const MAX_FETCH: u32 = 64;
const MAX_ACK: usize = 256;
const MAX_WAIT_SECONDS: u64 = 30;
const MAX_CONCURRENT_POLLS: u32 = 2;
const MAX_ACTIVE_INVITES: i64 = 8;

/// Clock returning Unix epoch milliseconds (injectable for tests).
pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

/// System wall clock.
#[must_use]
pub fn system_clock() -> Clock {
    Arc::new(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
    })
}

/// Tunable limits.
#[derive(Debug, Clone, Copy)]
pub struct Limits {
    /// Mailbox item lifetime.
    pub mailbox_ttl_ms: i64,
    /// Per-recipient quota.
    pub quota: MailboxQuota,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            mailbox_ttl_ms: 7 * 24 * 3600 * 1000,
            quota: MailboxQuota::DEFAULT,
        }
    }
}

/// Shared server state.
pub struct AppState {
    /// Persistent storage.
    pub store: Store,
    /// Wall clock.
    pub clock: Clock,
    /// Limits.
    pub limits: Limits,
    nonces: NonceCache,
    device_rate: RateLimiter<DeviceId>,
    ip_rate: RateLimiter<IpAddr>,
    waiters: Mutex<HashMap<DeviceId, Arc<Notify>>>,
    polls: Mutex<HashMap<DeviceId, u32>>,
}

impl std::fmt::Debug for AppState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AppState").finish_non_exhaustive()
    }
}

impl AppState {
    /// Builds the state around a store.
    #[must_use]
    pub fn new(store: Store, clock: Clock, limits: Limits) -> Self {
        let retention = i64::try_from(request_auth::MAX_SKEW_MS * 2 + 1_000).unwrap_or(61_000);
        Self {
            store,
            clock,
            limits,
            nonces: NonceCache::new(retention, 200_000),
            device_rate: RateLimiter::new(30, 60, 10_000),
            ip_rate: RateLimiter::new(2, 20, 10_000),
            waiters: Mutex::new(HashMap::new()),
            polls: Mutex::new(HashMap::new()),
        }
    }

    fn now(&self) -> i64 {
        (self.clock)()
    }

    fn waiter(&self, id: &DeviceId) -> Option<Arc<Notify>> {
        let mut waiters = self.waiters.lock().ok()?;
        Some(Arc::clone(waiters.entry(*id).or_default()))
    }

    fn wake(&self, id: &DeviceId) {
        if let Ok(waiters) = self.waiters.lock()
            && let Some(notify) = waiters.get(id)
        {
            notify.notify_waiters();
        }
    }

    /// Periodic cleanup of expired rows and in-memory caches.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn maintenance(&self) -> Result<usize, StoreError> {
        let now = self.now();
        self.nonces.prune(now);
        self.device_rate.prune(now, 120_000);
        self.ip_rate.prune(now, 120_000);
        self.store.prune(now)
    }
}

/// API errors, rendered as a `RelayError` body with a stable code.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApiError {
    /// Malformed request.
    BadRequest,
    /// Missing, invalid, stale or replayed authentication.
    Unauthorized,
    /// Authenticated but not allowed (also used for unknown invitations).
    Forbidden,
    /// Rate limit or concurrent poll limit reached.
    TooManyRequests,
    /// Mailbox full.
    MailboxFull,
    /// Device already registered with another role.
    Conflict,
    /// Internal failure (details are logged, never returned).
    Internal,
}

impl ApiError {
    fn parts(self) -> (StatusCode, &'static str) {
        match self {
            Self::BadRequest => (StatusCode::BAD_REQUEST, "bad_request"),
            Self::Unauthorized => (StatusCode::UNAUTHORIZED, "unauthorized"),
            Self::Forbidden => (StatusCode::FORBIDDEN, "forbidden"),
            Self::TooManyRequests => (StatusCode::TOO_MANY_REQUESTS, "rate_limited"),
            Self::MailboxFull => (StatusCode::INSUFFICIENT_STORAGE, "mailbox_full"),
            Self::Conflict => (StatusCode::CONFLICT, "conflict"),
            Self::Internal => (StatusCode::INTERNAL_SERVER_ERROR, "internal"),
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, code) = self.parts();
        (
            status,
            Proto(RelayError {
                code: code.to_owned(),
            }),
        )
            .into_response()
    }
}

impl From<StoreError> for ApiError {
    fn from(error: StoreError) -> Self {
        match error {
            StoreError::QuotaExceeded => Self::MailboxFull,
            StoreError::RoleConflict => Self::Conflict,
            StoreError::Sqlite(_) | StoreError::Poisoned => {
                tracing::error!(%error, "storage error");
                Self::Internal
            }
        }
    }
}

/// Protobuf response body.
struct Proto<M>(M);

impl<M: Message> IntoResponse for Proto<M> {
    fn into_response(self) -> Response {
        (
            [
                (header::CONTENT_TYPE, "application/x-protobuf"),
                (header::CACHE_CONTROL, "no-store"),
            ],
            self.0.encode_to_vec(),
        )
            .into_response()
    }
}

/// Health response. Deliberately minimal: no software version, no host information.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Health {
    /// Always `"ok"` when the process is serving requests.
    pub status: &'static str,
    /// Supported application protocol version.
    pub protocol_version: u32,
}

async fn health() -> Json<Health> {
    Json(Health {
        status: "ok",
        protocol_version: bastion_proto::PROTOCOL_VERSION,
    })
}

/// Verifies `Bastion-Auth` (PROTOCOL.md §8) and the per-device rate limit.
fn authenticate(
    state: &AppState,
    method: &Method,
    uri: &Uri,
    headers: &HeaderMap,
    body: &[u8],
) -> Result<Peer, ApiError> {
    let value = headers
        .get(request_auth::HEADER)
        .and_then(|v| v.to_str().ok())
        .ok_or(ApiError::Unauthorized)?;
    let auth = AuthHeader::parse(value).map_err(|_| ApiError::Unauthorized)?;
    let now = state.now();
    if !auth.is_fresh(u64::try_from(now).unwrap_or(0)) {
        return Err(ApiError::Unauthorized);
    }
    let peer = state
        .store
        .peer(&auth.device_id)?
        .ok_or(ApiError::Unauthorized)?;
    let path = uri.path_and_query().map_or(uri.path(), |pq| pq.as_str());
    auth.verify(&peer.identity, method.as_str(), path, body)
        .map_err(|_| ApiError::Unauthorized)?;
    // The nonce is recorded only after the signature verified, so unauthenticated junk
    // cannot fill the cache.
    if !state.nonces.insert(auth.device_id, auth.nonce, now) {
        return Err(ApiError::Unauthorized);
    }
    if !state.device_rate.check(&auth.device_id, now) {
        return Err(ApiError::TooManyRequests);
    }
    Ok(peer)
}

fn parse_device_id(hex_id: &str) -> Result<DeviceId, ApiError> {
    let mut id = [0u8; 16];
    hex::decode_to_slice(hex_id, &mut id).map_err(|_| ApiError::BadRequest)?;
    Ok(id)
}

async fn create_invite(
    State(state): State<Arc<AppState>>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<StatusCode, ApiError> {
    let peer = authenticate(&state, &method, &uri, &headers, &body)?;
    if peer.role != Role::Controller {
        return Err(ApiError::Forbidden);
    }
    let request = CreateInviteRequest::decode(body).map_err(|_| ApiError::BadRequest)?;
    let token_hash: [u8; 32] = request
        .token_hash
        .as_slice()
        .try_into()
        .map_err(|_| ApiError::BadRequest)?;
    let now = state.now();
    pairing::check_invite_expiry(request.expires_at_ms, now).map_err(|_| ApiError::BadRequest)?;
    state.store.create_invite(
        &token_hash,
        &peer.device_id,
        request.expires_at_ms,
        now,
        MAX_ACTIVE_INVITES,
    )?;
    Ok(StatusCode::CREATED)
}

fn verify_enrollment(request: &EnrollRequest) -> Result<(DeviceKeys, ProtoRole), ApiError> {
    let device = request
        .device
        .as_ref()
        .ok_or(ApiError::BadRequest)
        .and_then(|d| DeviceKeys::from_proto(d).map_err(|_| ApiError::BadRequest))?;
    let role = ProtoRole::try_from(request.role).map_err(|_| ApiError::BadRequest)?;
    if !matches!(request.wireguard_public_key.len(), 0 | 32)
        || !matches!(request.proof.len(), 0 | 32)
        || !matches!(request.token_hash.len(), 0 | 32)
    {
        return Err(ApiError::BadRequest);
    }
    let transcript = pairing::enroll_transcript(
        &request.token_hash,
        &device,
        &request.wireguard_public_key,
        &request.proof,
        request.role,
    );
    bastion_crypto::identity::verify(&device.identity, &transcript, &request.signature)
        .map_err(|_| ApiError::Unauthorized)?;
    Ok((device, role))
}

async fn enroll(
    State(state): State<Arc<AppState>>,
    connect: Option<Extension<ConnectInfo<SocketAddr>>>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Response, ApiError> {
    let now = state.now();
    // The address is only used transiently as a rate-limit key; it is never logged.
    let ip = connect.map_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED), |c| c.0.0.ip());
    if !state.ip_rate.check(&ip, now) {
        return Err(ApiError::TooManyRequests);
    }
    let request = EnrollRequest::decode(body).map_err(|_| ApiError::BadRequest)?;
    let (device, role) = verify_enrollment(&request)?;
    let device_id = device.device_id();
    let controller_id = match role {
        ProtoRole::Phone => enroll_phone(&state, &request, &device, now)?.to_vec(),
        ProtoRole::Controller => {
            let token = headers
                .get(ADMIN_TOKEN_HEADER)
                .and_then(|v| v.to_str().ok())
                .ok_or(ApiError::Forbidden)?;
            if !state
                .store
                .consume_admin_token(&admin_token_hash(token), now)?
            {
                return Err(ApiError::Forbidden);
            }
            let controller = Peer {
                device_id,
                role: Role::Controller,
                identity: device.identity,
            };
            state.store.register_controller(&controller, now)?;
            Vec::new()
        }
        ProtoRole::Unspecified => return Err(ApiError::BadRequest),
    };
    let response = EnrollResponse {
        device_id: device_id.to_vec(),
        addresses: Vec::new(),
        allowed_ips: Vec::new(),
        dns: Vec::new(),
        controller_id,
        persistent_keepalive_seconds: 0,
    };
    Ok((StatusCode::CREATED, Proto(response)).into_response())
}

fn enroll_phone(
    state: &AppState,
    request: &EnrollRequest,
    device: &DeviceKeys,
    now: i64,
) -> Result<DeviceId, ApiError> {
    let token_hash: [u8; 32] = request
        .token_hash
        .as_slice()
        .try_into()
        .map_err(|_| ApiError::BadRequest)?;
    if request.proof.len() != 32
        || request.sealed_hello.is_empty()
        || request.sealed_hello.len() > MAX_SEALED_HELLO_BYTES
    {
        return Err(ApiError::BadRequest);
    }
    let phone = Peer {
        device_id: device.device_id(),
        role: Role::Phone,
        identity: device.identity,
    };
    let controller = state
        .store
        .enroll_phone(
            &token_hash,
            &phone,
            &request.sealed_hello,
            now,
            state.limits.mailbox_ttl_ms,
        )?
        .ok_or(ApiError::Forbidden)?;
    state.wake(&controller);
    Ok(controller)
}

/// Hash under which an administration token is stored.
#[must_use]
pub fn admin_token_hash(token: &str) -> [u8; 32] {
    hash::blake2b_256(token.as_bytes())
}

async fn put_envelope(
    State(state): State<Arc<AppState>>,
    Path(recipient): Path<String>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<StatusCode, ApiError> {
    let sender = authenticate(&state, &method, &uri, &headers, &body)?;
    let recipient = parse_device_id(&recipient)?;
    let parsed = envelope::parse(&body).map_err(|_| ApiError::BadRequest)?;
    if parsed.header.sender_id != sender.device_id || parsed.header.recipient_id != recipient {
        return Err(ApiError::BadRequest);
    }
    if !state.store.is_linked(&sender.device_id, &recipient)? {
        return Err(ApiError::Forbidden);
    }
    state.store.put_envelope(
        &recipient,
        &sender.device_id,
        &body,
        state.now(),
        state.limits.mailbox_ttl_ms,
        state.limits.quota,
    )?;
    state.wake(&recipient);
    Ok(StatusCode::CREATED)
}

/// Releases a long-poll slot on drop.
struct PollSlot<'a> {
    state: &'a AppState,
    id: DeviceId,
}

impl<'a> PollSlot<'a> {
    fn acquire(state: &'a AppState, id: DeviceId) -> Option<Self> {
        let mut polls = state.polls.lock().ok()?;
        let count = polls.entry(id).or_insert(0);
        if *count >= MAX_CONCURRENT_POLLS {
            return None;
        }
        *count += 1;
        Some(Self { state, id })
    }
}

impl Drop for PollSlot<'_> {
    fn drop(&mut self) {
        if let Ok(mut polls) = self.state.polls.lock()
            && let Some(count) = polls.get_mut(&self.id)
        {
            *count = count.saturating_sub(1);
            if *count == 0 {
                polls.remove(&self.id);
            }
        }
    }
}

fn wait_seconds(uri: &Uri) -> Result<u64, ApiError> {
    match uri.query() {
        None | Some("") => Ok(0),
        Some(query) => query
            .strip_prefix("wait=")
            .and_then(|v| v.parse::<u64>().ok())
            .map(|v| v.min(MAX_WAIT_SECONDS))
            .ok_or(ApiError::BadRequest),
    }
}

async fn fetch_mailbox(
    State(state): State<Arc<AppState>>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let peer = authenticate(&state, &method, &uri, &headers, &[])?;
    let wait = wait_seconds(&uri)?;
    let notify = state.waiter(&peer.device_id).ok_or(ApiError::Internal)?;
    let _slot = PollSlot::acquire(&state, peer.device_id).ok_or(ApiError::TooManyRequests)?;
    let notified = notify.notified();
    tokio::pin!(notified);
    // Register interest before reading, so a deposit between the read and the wait is not lost.
    notified.as_mut().enable();
    let mut items = state.store.fetch(&peer.device_id, state.now(), MAX_FETCH)?;
    if items.is_empty() && wait > 0 {
        let _ = tokio::time::timeout(Duration::from_secs(wait), notified).await;
        items = state.store.fetch(&peer.device_id, state.now(), MAX_FETCH)?;
    }
    let batch = MailboxBatch {
        items: items
            .into_iter()
            .map(|item| MailboxItem {
                id: item.id,
                kind: item.kind,
                sender_id: item.sender.to_vec(),
                payload: item.payload,
                received_at_ms: item.received_ms,
            })
            .collect(),
    };
    Ok(Proto(batch).into_response())
}

async fn ack(
    State(state): State<Arc<AppState>>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<StatusCode, ApiError> {
    let peer = authenticate(&state, &method, &uri, &headers, &body)?;
    let request = MailboxAck::decode(body).map_err(|_| ApiError::BadRequest)?;
    if request.ids.len() > MAX_ACK {
        return Err(ApiError::BadRequest);
    }
    state.store.ack(&peer.device_id, &request.ids)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn revoke(
    State(state): State<Arc<AppState>>,
    Path(target): Path<String>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
) -> Result<StatusCode, ApiError> {
    let peer = authenticate(&state, &method, &uri, &headers, &[])?;
    let target = parse_device_id(&target)?;
    if !state.store.revoke(&peer.device_id, &target)? {
        return Err(ApiError::Forbidden);
    }
    state.wake(&target);
    Ok(StatusCode::NO_CONTENT)
}

/// Builds the relay API router.
pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/v1/health", get(health))
        .route("/v1/invites", post(create_invite))
        .route("/v1/enroll", post(enroll))
        .route("/v1/mailbox", get(fetch_mailbox))
        .route("/v1/mailbox/ack", post(ack))
        .route("/v1/mailbox/{recipient}", put(put_envelope))
        .route("/v1/peers/{id}", delete(revoke))
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
        .with_state(state)
}
