// SPDX-License-Identifier: AGPL-3.0-or-later
//! Bastion relay library: HTTP API router and configuration.
//!
//! The relay never sees plaintext: it routes opaque end-to-end encrypted envelopes
//! between paired devices (see `docs/PROTOCOL.md`).

pub mod config;
pub mod routes;

pub use config::{Config, ConfigError};
pub use routes::router;
