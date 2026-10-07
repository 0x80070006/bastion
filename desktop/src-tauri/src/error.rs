// SPDX-License-Identifier: GPL-3.0-or-later
//! Errors returned to the webview: a stable machine code only, never internal details
//! (they are logged without secrets instead).

use serde::Serialize;

/// Application error with a stable code the frontend translates.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum AppError {
    /// No vault yet: the setup flow must run first.
    #[error("no_vault")]
    NoVault,
    /// A vault already exists.
    #[error("vault_exists")]
    VaultExists,
    /// The vault is locked.
    #[error("locked")]
    Locked,
    /// Wrong master password.
    #[error("wrong_password")]
    WrongPassword,
    /// Master password does not meet the policy.
    #[error("weak_password")]
    WeakPassword,
    /// Too many unlock attempts; retry later.
    #[error("throttled")]
    Throttled,
    /// The vault file is corrupted or was tampered with.
    #[error("vault_corrupted")]
    VaultCorrupted,
    /// Invalid input from the user interface.
    #[error("invalid_input")]
    InvalidInput,
    /// Unknown device.
    #[error("unknown_device")]
    UnknownDevice,
    /// The device is not (yet) actively paired.
    #[error("not_active")]
    NotActive,
    /// The relay could not be reached.
    #[error("relay_unreachable")]
    RelayUnreachable,
    /// The relay refused the request.
    #[error("relay_refused")]
    RelayRefused,
    /// The relay TLS key does not match the pin.
    #[error("relay_pin_mismatch")]
    RelayPinMismatch,
    /// The embedded relay port is already in use.
    #[error("port_in_use")]
    PortInUse,
    /// A wipe must be armed and the 30-second delay must elapse first.
    #[error("wipe_not_armed")]
    WipeNotArmed,
    /// Local storage failure.
    #[error("storage")]
    Storage,
    /// Unexpected internal failure.
    #[error("internal")]
    Internal,
}

impl Serialize for AppError {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_string())
    }
}

impl From<std::io::Error> for AppError {
    fn from(error: std::io::Error) -> Self {
        tracing::error!(kind = ?error.kind(), "storage I/O error");
        Self::Storage
    }
}

/// Result alias for application operations.
pub type AppResult<T> = Result<T, AppError>;
