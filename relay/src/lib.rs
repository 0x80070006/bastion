// SPDX-License-Identifier: AGPL-3.0-or-later
//! Bastion relay library: HTTP API, storage and TLS identity.
//!
//! The relay never sees plaintext: it routes opaque end-to-end encrypted envelopes
//! between paired devices (see `docs/PROTOCOL.md`). [`Relay`] is used both by the standalone
//! binary and embedded in the desktop application (topology B, ARCHITECTURE.md §6).

pub mod config;
pub mod limits;
pub mod routes;
pub mod store;
pub mod tls;

use std::future::Future;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use axum_server::tls_rustls::RustlsConfig;
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

pub use config::{Config, ConfigError};
pub use routes::{AppState, Clock, Limits, system_clock};
use store::{Store, StoreError};
use tls::{TlsError, TlsIdentity};

/// Relay startup and runtime errors.
#[derive(Debug, thiserror::Error)]
pub enum RelayError {
    /// Storage failure.
    #[error(transparent)]
    Store(#[from] StoreError),
    /// TLS identity failure.
    #[error(transparent)]
    Tls(#[from] TlsError),
    /// Listener failure.
    #[error("listener: {0}")]
    Io(#[from] std::io::Error),
}

/// A relay instance: state, optional TLS identity.
#[derive(Debug, Clone)]
pub struct Relay {
    state: Arc<AppState>,
    tls: Option<Arc<TlsIdentity>>,
}

impl Relay {
    /// Opens the relay described by `config` (database and TLS key in `data_dir`).
    ///
    /// # Errors
    /// [`RelayError`].
    pub fn open(config: &Config) -> Result<Self, RelayError> {
        std::fs::create_dir_all(&config.data_dir)?;
        let store = Store::open(&config.data_dir.join("relay.sqlite3"))?;
        let tls = if config.tls {
            Some(Arc::new(TlsIdentity::load_or_create(&config.data_dir)?))
        } else {
            None
        };
        Ok(Self {
            state: Arc::new(AppState::new(store, system_clock(), Limits::default())),
            tls,
        })
    }

    /// In-memory relay without TLS (tests).
    ///
    /// # Errors
    /// [`RelayError`].
    pub fn ephemeral(clock: Clock) -> Result<Self, RelayError> {
        Ok(Self {
            state: Arc::new(AppState::new(Store::in_memory()?, clock, Limits::default())),
            tls: None,
        })
    }

    /// Shared state (tests and embedding).
    #[must_use]
    pub fn state(&self) -> &Arc<AppState> {
        &self.state
    }

    /// SHA-256 pin of the TLS public key, when TLS is enabled.
    #[must_use]
    pub fn spki_pin(&self) -> Option<[u8; 32]> {
        self.tls.as_ref().map(|t| t.spki_pin())
    }

    /// Creates a single-use administration token allowing one controller enrollment.
    ///
    /// # Errors
    /// [`RelayError::Store`].
    pub fn create_admin_token(&self, ttl: Duration) -> Result<String, RelayError> {
        let token = URL_SAFE_NO_PAD.encode(bastion_crypto::random::bytes::<32>());
        let ttl_ms = i64::try_from(ttl.as_millis()).unwrap_or(i64::MAX);
        let expires = (self.state.clock)().saturating_add(ttl_ms);
        self.state
            .store
            .create_admin_token(&routes::admin_token_hash(&token), expires)?;
        Ok(token)
    }

    /// The API router.
    pub fn router(&self) -> Router {
        routes::router(Arc::clone(&self.state))
    }

    /// Runs periodic cleanup until the task is aborted.
    #[must_use]
    pub fn spawn_maintenance(&self) -> tokio::task::JoinHandle<()> {
        let state = Arc::clone(&self.state);
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(60));
            loop {
                interval.tick().await;
                if let Err(error) = state.maintenance() {
                    tracing::warn!(%error, "relay maintenance failed");
                }
            }
        })
    }

    /// Serves the API on an already bound listener until `shutdown` resolves.
    ///
    /// # Errors
    /// [`RelayError`].
    pub async fn serve(
        &self,
        listener: std::net::TcpListener,
        shutdown: impl Future<Output = ()> + Send + 'static,
    ) -> Result<(), RelayError> {
        listener.set_nonblocking(true)?;
        let handle = axum_server::Handle::new();
        let stopper = handle.clone();
        tokio::spawn(async move {
            shutdown.await;
            stopper.graceful_shutdown(Some(Duration::from_secs(5)));
        });
        let app = self
            .router()
            .into_make_service_with_connect_info::<SocketAddr>();
        let maintenance = self.spawn_maintenance();
        let result = if let Some(tls) = &self.tls {
            let config = RustlsConfig::from_config(tls.server_config()?);
            axum_server::from_tcp_rustls(listener, config)?
                .handle(handle)
                .serve(app)
                .await
        } else {
            axum_server::from_tcp(listener)?
                .handle(handle)
                .serve(app)
                .await
        };
        maintenance.abort();
        result.map_err(RelayError::Io)
    }
}
