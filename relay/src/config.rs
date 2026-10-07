// SPDX-License-Identifier: AGPL-3.0-or-later
//! Runtime configuration, read from environment variables only (no secrets on the command line).

use std::net::SocketAddr;
use std::path::PathBuf;

/// Relay runtime configuration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    /// Address of the HTTPS API listener.
    pub listen_addr: SocketAddr,
    /// Whether access logs are emitted. Off by default; they never contain IP addresses.
    pub access_log: bool,
    /// Directory holding the database and the TLS key (created if missing).
    pub data_dir: PathBuf,
    /// Serve TLS 1.3 (default). Plain HTTP is only for tests and for a listener bound to a
    /// WireGuard interface.
    pub tls: bool,
}

/// Configuration errors.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ConfigError {
    /// `BASTION_RELAY_LISTEN` is not a valid socket address.
    #[error("BASTION_RELAY_LISTEN is not a valid socket address: {0}")]
    InvalidListenAddr(String),
    /// A boolean variable is not `true` or `false`.
    #[error("{0} must be `true` or `false`")]
    InvalidBool(&'static str),
}

impl Config {
    /// Default API listener.
    pub const DEFAULT_LISTEN: &'static str = "127.0.0.1:8443";
    /// Default data directory.
    pub const DEFAULT_DATA_DIR: &'static str = "data";

    /// Builds the configuration from a variable lookup function (testable without touching
    /// the process environment).
    ///
    /// # Errors
    /// Returns a [`ConfigError`] when a variable is present but malformed.
    pub fn from_lookup(lookup: impl Fn(&str) -> Option<String>) -> Result<Self, ConfigError> {
        let listen = lookup("BASTION_RELAY_LISTEN").unwrap_or_else(|| Self::DEFAULT_LISTEN.into());
        let listen_addr = listen
            .parse()
            .map_err(|_| ConfigError::InvalidListenAddr(listen.clone()))?;
        let boolean = |name: &'static str, default: bool| match lookup(name).as_deref() {
            None => Ok(default),
            Some("true") => Ok(true),
            Some("false") => Ok(false),
            Some(_) => Err(ConfigError::InvalidBool(name)),
        };
        Ok(Self {
            listen_addr,
            access_log: boolean("BASTION_RELAY_ACCESS_LOG", false)?,
            data_dir: lookup("BASTION_RELAY_DATA")
                .unwrap_or_else(|| Self::DEFAULT_DATA_DIR.into())
                .into(),
            tls: boolean("BASTION_RELAY_TLS", true)?,
        })
    }

    /// Builds the configuration from the process environment.
    ///
    /// # Errors
    /// See [`Config::from_lookup`].
    pub fn from_env() -> Result<Self, ConfigError> {
        Self::from_lookup(|key| std::env::var(key).ok())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_private_quiet_and_encrypted() {
        let config = Config::from_lookup(|_| None);
        assert_eq!(
            config,
            Ok(Config {
                listen_addr: Config::DEFAULT_LISTEN.parse().unwrap(),
                access_log: false,
                data_dir: Config::DEFAULT_DATA_DIR.into(),
                tls: true,
            })
        );
    }

    #[test]
    fn rejects_malformed_values() {
        let bad_addr =
            Config::from_lookup(|k| (k == "BASTION_RELAY_LISTEN").then(|| "nope".into()));
        assert_eq!(bad_addr, Err(ConfigError::InvalidListenAddr("nope".into())));
        let bad_log =
            Config::from_lookup(|k| (k == "BASTION_RELAY_ACCESS_LOG").then(|| "1".into()));
        assert_eq!(
            bad_log,
            Err(ConfigError::InvalidBool("BASTION_RELAY_ACCESS_LOG"))
        );
        let bad_tls = Config::from_lookup(|k| (k == "BASTION_RELAY_TLS").then(|| "no".into()));
        assert_eq!(bad_tls, Err(ConfigError::InvalidBool("BASTION_RELAY_TLS")));
    }
}
