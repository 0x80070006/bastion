// SPDX-License-Identifier: GPL-3.0-or-later
//! Error type. Variants are deliberately coarse: callers must not leak which check failed
//! to a remote party (PROTOCOL.md §6: rejections at steps 1–5 are silent).

/// Cryptographic failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum CryptoError {
    /// A key has the wrong length or is not a valid curve point.
    #[error("invalid key")]
    InvalidKey,
    /// An X25519 exchange produced an all-zero (non-contributory) secret.
    #[error("weak key exchange")]
    WeakKey,
    /// A signature does not verify.
    #[error("invalid signature")]
    InvalidSignature,
    /// AEAD authentication failed.
    #[error("decryption failed")]
    DecryptionFailed,
    /// A field has an unexpected length.
    #[error("invalid length")]
    InvalidLength,
    /// The input could not be parsed.
    #[error("malformed input")]
    Malformed,
    /// Unknown envelope or format version.
    #[error("unsupported version")]
    UnsupportedVersion,
    /// Password key derivation failed (parameters rejected or out of memory).
    #[error("key derivation failed")]
    Kdf,
}
