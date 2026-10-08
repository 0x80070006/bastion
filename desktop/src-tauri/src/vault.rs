// SPDX-License-Identifier: GPL-3.0-or-later
//! Encrypted local vault (ARCHITECTURE.md §4).
//!
//! File layout: `"BASTVLT1" ‖ u32be(ops) ‖ u32be(mem_kib) ‖ salt(16) ‖ nonce(24) ‖ ciphertext`,
//! the 32-byte header being the AEAD associated data, the key Argon2id(master password).
//! The privileged command key `PK` is sealed a second time under a key derived with its own
//! salt, so it is only decrypted after re-authentication (PROTOCOL.md §7).

use std::path::{Path, PathBuf};

use bastion_crypto::session::SymmetricKey;
use bastion_crypto::vault::{KdfParams, SALT_BYTES, derive_key};
use bastion_crypto::{CryptoError, random};
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::error::{AppError, AppResult};
use crate::model::VaultData;

const MAGIC: &[u8; 8] = b"BASTVLT1";
const HEADER_BYTES: usize = 8 + 4 + 4 + SALT_BYTES;
const PK_AAD: &[u8] = b"bastion-pk-v1";
/// Minimum master password length (characters).
pub const MIN_PASSWORD_CHARS: usize = 12;

/// Password-derived key and the parameters it was derived with.
pub struct VaultKey {
    key: SymmetricKey,
    header: [u8; HEADER_BYTES],
}

impl std::fmt::Debug for VaultKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("VaultKey(redacted)")
    }
}

fn header(params: KdfParams, salt: &[u8; SALT_BYTES]) -> [u8; HEADER_BYTES] {
    let mut out = [0u8; HEADER_BYTES];
    out[..8].copy_from_slice(MAGIC);
    out[8..12].copy_from_slice(&params.ops_limit.to_be_bytes());
    out[12..16].copy_from_slice(&params.mem_limit_kib.to_be_bytes());
    out[16..].copy_from_slice(salt);
    out
}

fn parse_header(bytes: &[u8]) -> AppResult<(KdfParams, [u8; SALT_BYTES])> {
    if bytes.len() < HEADER_BYTES || &bytes[..8] != MAGIC {
        return Err(AppError::VaultCorrupted);
    }
    let word = |i: usize| u32::from_be_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]]);
    let params = KdfParams {
        ops_limit: word(8),
        mem_limit_kib: word(12),
    };
    // A tampered header must not downgrade the KDF.
    if !params.is_acceptable() {
        return Err(AppError::VaultCorrupted);
    }
    let mut salt = [0u8; SALT_BYTES];
    salt.copy_from_slice(&bytes[16..HEADER_BYTES]);
    Ok((params, salt))
}

/// Master password policy: length only (passphrases encouraged), no composition rules.
///
/// # Errors
/// [`AppError::WeakPassword`].
pub fn check_password_policy(password: &str) -> AppResult<()> {
    if password.chars().count() < MIN_PASSWORD_CHARS || password.trim().is_empty() {
        return Err(AppError::WeakPassword);
    }
    Ok(())
}

impl VaultKey {
    /// Derives a fresh key (new random salt) for a new vault.
    ///
    /// # Errors
    /// [`AppError::Internal`] if Argon2id fails.
    pub fn create(password: &str, params: KdfParams) -> AppResult<Self> {
        let salt = random::bytes::<SALT_BYTES>();
        let key = derive_key(password.as_bytes(), &salt, params).map_err(|_| AppError::Internal)?;
        Ok(Self {
            key,
            header: header(params, &salt),
        })
    }

    /// Derives the key of an existing vault from its header.
    ///
    /// # Errors
    /// [`AppError::VaultCorrupted`] or [`AppError::Internal`].
    pub fn for_file(password: &str, file: &[u8]) -> AppResult<Self> {
        let (params, salt) = parse_header(file)?;
        let key = derive_key(password.as_bytes(), &salt, params).map_err(|_| AppError::Internal)?;
        Ok(Self {
            key,
            header: header(params, &salt),
        })
    }

    /// Encrypts the vault contents.
    ///
    /// # Errors
    /// [`AppError::Internal`] if serialization fails.
    pub fn seal(&self, data: &VaultData) -> AppResult<Vec<u8>> {
        let plaintext = Zeroizing::new(serde_json::to_vec(data).map_err(|_| AppError::Internal)?);
        let mut out = self.header.to_vec();
        out.extend(self.key.seal(&self.header, &plaintext));
        Ok(out)
    }

    /// Decrypts a vault file.
    ///
    /// # Errors
    /// [`AppError::WrongPassword`] when authentication fails (wrong password or tampering),
    /// [`AppError::VaultCorrupted`] for an undecodable plaintext.
    pub fn open(&self, file: &[u8]) -> AppResult<VaultData> {
        if file.len() < HEADER_BYTES || file[..HEADER_BYTES] != self.header {
            return Err(AppError::VaultCorrupted);
        }
        let plaintext = self
            .key
            .open(&self.header, &file[HEADER_BYTES..])
            .map_err(|_| AppError::WrongPassword)?;
        serde_json::from_slice(&plaintext).map_err(|_| AppError::VaultCorrupted)
    }

    /// Encrypts a media blob (photo) with the vault key, bound to `id` so a file cannot be
    /// swapped for another. Returned bytes are a self-contained `nonce ‖ ciphertext`.
    #[must_use]
    pub fn seal_media(&self, id: &[u8], bytes: &[u8]) -> Vec<u8> {
        self.key.seal(&media_aad(id), bytes)
    }

    /// Decrypts a media blob produced by [`Self::seal_media`].
    ///
    /// # Errors
    /// [`AppError::VaultCorrupted`].
    pub fn open_media(&self, id: &[u8], sealed: &[u8]) -> AppResult<Zeroizing<Vec<u8>>> {
        self.key
            .open(&media_aad(id), sealed)
            .map_err(|_| AppError::VaultCorrupted)
    }
}

fn media_aad(id: &[u8]) -> Vec<u8> {
    let mut aad = b"bastion-media-v1".to_vec();
    aad.extend_from_slice(id);
    aad
}

/// The privileged key sealed under its own password-derived key.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SealedPrivilegedKey {
    #[serde(with = "crate::model::b64")]
    salt: Vec<u8>,
    ops_limit: u32,
    mem_limit_kib: u32,
    #[serde(with = "crate::model::b64")]
    sealed: Vec<u8>,
}

impl SealedPrivilegedKey {
    /// Seals a `PK` seed.
    ///
    /// # Errors
    /// [`AppError::Internal`].
    pub fn seal(password: &str, seed: &[u8; 32], params: KdfParams) -> AppResult<Self> {
        let salt = random::bytes::<SALT_BYTES>();
        let key = derive_key(password.as_bytes(), &salt, params).map_err(|_| AppError::Internal)?;
        Ok(Self {
            salt: salt.to_vec(),
            ops_limit: params.ops_limit,
            mem_limit_kib: params.mem_limit_kib,
            sealed: key.seal(PK_AAD, seed),
        })
    }

    /// Re-derives the key from the password and returns the `PK` seed.
    ///
    /// # Errors
    /// [`AppError::WrongPassword`] or [`AppError::VaultCorrupted`].
    pub fn open(&self, password: &str) -> AppResult<Zeroizing<[u8; 32]>> {
        let params = KdfParams {
            ops_limit: self.ops_limit,
            mem_limit_kib: self.mem_limit_kib,
        };
        let salt: [u8; SALT_BYTES] = self
            .salt
            .as_slice()
            .try_into()
            .map_err(|_| AppError::VaultCorrupted)?;
        if !params.is_acceptable() {
            return Err(AppError::VaultCorrupted);
        }
        let key = derive_key(password.as_bytes(), &salt, params).map_err(|_| AppError::Internal)?;
        let seed = key
            .open(PK_AAD, &self.sealed)
            .map_err(|_: CryptoError| AppError::WrongPassword)?;
        let mut out = Zeroizing::new([0u8; 32]);
        if seed.len() != 32 {
            return Err(AppError::VaultCorrupted);
        }
        out.copy_from_slice(&seed);
        Ok(out)
    }
}

/// Writes `bytes` atomically (temporary file + rename) so a crash never leaves a truncated
/// vault.
///
/// # Errors
/// [`AppError::Storage`].
pub fn write_atomic(path: &Path, bytes: &[u8]) -> AppResult<()> {
    let tmp: PathBuf = path.with_extension("tmp");
    {
        use std::io::Write;
        let mut file = std::fs::File::create(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    std::fs::rename(&tmp, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const FAST: KdfParams = KdfParams::INTERACTIVE;

    #[test]
    fn vault_round_trip_and_wrong_password() {
        let key = VaultKey::create("correct horse battery", FAST).unwrap();
        let data = VaultData::new_for_tests();
        let file = key.seal(&data).unwrap();
        let reopened = VaultKey::for_file("correct horse battery", &file).unwrap();
        assert_eq!(reopened.open(&file).unwrap(), data);
        let wrong = VaultKey::for_file("correct horse battery!", &file).unwrap();
        assert_eq!(wrong.open(&file), Err(AppError::WrongPassword));
    }

    #[test]
    fn tampering_is_detected() {
        let key = VaultKey::create("correct horse battery", FAST).unwrap();
        let mut file = key.seal(&VaultData::new_for_tests()).unwrap();
        let last = file.len() - 1;
        file[last] ^= 1;
        assert_eq!(key.open(&file), Err(AppError::WrongPassword));
        // Downgraded KDF parameters in the header are refused before any derivation.
        let mut weak = key.seal(&VaultData::new_for_tests()).unwrap();
        weak[12..16].copy_from_slice(&1u32.to_be_bytes());
        assert_eq!(
            VaultKey::for_file("correct horse battery", &weak).err(),
            Some(AppError::VaultCorrupted)
        );
    }

    #[test]
    fn privileged_key_needs_the_password() {
        let sealed = SealedPrivilegedKey::seal("correct horse battery", &[9; 32], FAST).unwrap();
        assert_eq!(*sealed.open("correct horse battery").unwrap(), [9; 32]);
        assert_eq!(
            sealed.open("wrong horse battery").err(),
            Some(AppError::WrongPassword)
        );
    }

    #[test]
    fn password_policy() {
        assert!(check_password_policy("short").is_err());
        assert!(check_password_policy("            ").is_err());
        assert!(check_password_policy("une phrase de passe").is_ok());
    }
}
