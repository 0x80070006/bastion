// SPDX-License-Identifier: GPL-3.0-or-later
//! Bastion protocol v1 cryptography (docs/PROTOCOL.md).
//!
//! Every construction here is a composition of libsodium-compatible primitives (Ed25519,
//! X25519, XChaCha20-Poly1305, BLAKE2b, Argon2id, `crypto_box_seal`) implemented by audited
//! pure-Rust crates (ADR-0016). Signed and hashed inputs are always explicit
//! [`transcript::Transcript`]s, never protobuf serializations.
//!
//! Byte-level compatibility with the Kotlin implementation is enforced by the shared test
//! vectors in `protocol/testvectors/v1.txt`.

pub mod context;
pub mod envelope;
mod error;
pub mod hash;
pub mod identity;
pub mod pairing;
pub mod random;
pub mod replay;
pub mod request_auth;
pub mod sealed;
pub mod session;
pub mod spki;
pub mod transcript;
pub mod vault;

#[cfg(test)]
mod vectors;

pub use error::CryptoError;
