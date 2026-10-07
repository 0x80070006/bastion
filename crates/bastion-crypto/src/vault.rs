// SPDX-License-Identifier: GPL-3.0-or-later
//! Password-based keys (Argon2id, libsodium `crypto_pwhash` parameters).

use argon2::{Algorithm, Argon2, Params, Version};
use zeroize::Zeroizing;

use crate::CryptoError;
use crate::session::SymmetricKey;

/// Salt length (libsodium `crypto_pwhash_SALTBYTES`).
pub const SALT_BYTES: usize = 16;

/// Argon2id cost parameters.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdfParams {
    /// Number of passes.
    pub ops_limit: u32,
    /// Memory in KiB.
    pub mem_limit_kib: u32,
}

impl KdfParams {
    /// libsodium `OPSLIMIT_MODERATE` / `MEMLIMIT_MODERATE` (3 passes, 256 MiB).
    pub const MODERATE: Self = Self {
        ops_limit: 3,
        mem_limit_kib: 256 * 1024,
    };
    /// libsodium `OPSLIMIT_INTERACTIVE` / `MEMLIMIT_INTERACTIVE` (2 passes, 64 MiB).
    pub const INTERACTIVE: Self = Self {
        ops_limit: 2,
        mem_limit_kib: 64 * 1024,
    };
    /// Fast parameters for unit tests only.
    #[cfg(test)]
    pub const TEST: Self = Self {
        ops_limit: 1,
        mem_limit_kib: 64,
    };

    /// Rejects parameters weaker than [`Self::INTERACTIVE`] when read from storage, so that a
    /// tampered vault header cannot silently downgrade the KDF.
    #[must_use]
    pub fn is_acceptable(&self) -> bool {
        self.ops_limit >= Self::INTERACTIVE.ops_limit
            && self.mem_limit_kib >= Self::INTERACTIVE.mem_limit_kib
            && self.ops_limit <= 16
            && self.mem_limit_kib <= 1024 * 1024
    }
}

/// Derives a 256-bit key from a password with Argon2id v1.3, one lane.
///
/// # Errors
/// [`CryptoError::Kdf`] if the parameters are rejected or memory is unavailable.
pub fn derive_key(
    password: &[u8],
    salt: &[u8; SALT_BYTES],
    params: KdfParams,
) -> Result<SymmetricKey, CryptoError> {
    let params = Params::new(params.mem_limit_kib, params.ops_limit, 1, Some(32))
        .map_err(|_| CryptoError::Kdf)?;
    let mut out = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password, salt, out.as_mut_slice())
        .map_err(|_| CryptoError::Kdf)?;
    Ok(SymmetricKey::from_bytes(*out))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_password_and_salt_give_the_same_key() {
        let salt = [3u8; SALT_BYTES];
        let a = derive_key(b"correct horse", &salt, KdfParams::TEST).unwrap();
        let b = derive_key(b"correct horse", &salt, KdfParams::TEST).unwrap();
        let c = derive_key(b"correct horsf", &salt, KdfParams::TEST).unwrap();
        let d = derive_key(b"correct horse", &[4u8; SALT_BYTES], KdfParams::TEST).unwrap();
        assert_eq!(a.expose(), b.expose());
        assert_ne!(a.expose(), c.expose());
        assert_ne!(a.expose(), d.expose());
    }

    #[test]
    fn weak_stored_parameters_are_refused() {
        assert!(KdfParams::MODERATE.is_acceptable());
        assert!(KdfParams::INTERACTIVE.is_acceptable());
        assert!(!KdfParams::TEST.is_acceptable());
    }
}
