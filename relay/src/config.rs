// SPDX-License-Identifier: AGPL-3.0-or-later
//! Runtime configuration, read from environment variables only (no secrets on the command line).

use std::net::SocketAddr;

/// Relay runtime configuration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    /// Address of the HTTP API listener.
    pub listen_addr: SocketAddr,
    /// Whether access logs are emitted. Off by default: no IP addresses are logged.
    pub access_log: bool,
}

/// Configuration errors.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ConfigError {
    /// `BASTION_RELAY_LISTEN` is not a valid socket address.
    #[error("BASTION_RELAY_LISTEN is not a valid socket address: {0}")]
    InvalidListenAddr(String),
    /// `BASTION_RELAY_ACCESS_LOG` is not `true` or `false`.
    #[error("BASTION_RELAY_ACCESS_LOG must be `true` or `false`")]
    InvalidAccessLog,
}

impl Config {
    /// Default API listener.
    pub const DEFAULT_LISTEN: &'static str = "127.0.0.1:8443";

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
        let access_log = match lookup("BASTION_RELAY_ACCESS_LOG").as_deref() {
            None | Some("false") => false,
            Some("true") => true,
            Some(_) => return Err(ConfigError::InvalidAccessLog),
        };
        Ok(Self {
            listen_addr,
            access_log,
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
    fn defaults_are_private_and_quiet() {
        let config = Config::from_lookup(|_| None);
        assert_eq!(
            config,
            Ok(Config {
                listen_addr: Config::DEFAULT_LISTEN.parse().unwrap(),
                access_log: false,
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
        assert_eq!(bad_log, Err(ConfigError::InvalidAccessLog));
    }
}
