// SPDX-License-Identifier: GPL-3.0-or-later
//! BLAKE2b, equivalent to libsodium `crypto_generichash` (keyed and unkeyed).

use blake2::digest::consts::{U16, U32};
use blake2::digest::{Digest, Mac};
use blake2::{Blake2b, Blake2bMac};

use crate::CryptoError;

/// Minimum key length accepted by libsodium `crypto_generichash`.
pub const KEY_MIN_BYTES: usize = 16;
/// Maximum key length accepted by libsodium `crypto_generichash`.
pub const KEY_MAX_BYTES: usize = 64;

/// Unkeyed BLAKE2b with a 32-byte output.
#[must_use]
pub fn blake2b_256(data: &[u8]) -> [u8; 32] {
    Blake2b::<U32>::digest(data).into()
}

/// Unkeyed BLAKE2b with a 16-byte output.
#[must_use]
pub fn blake2b_128(data: &[u8]) -> [u8; 16] {
    Blake2b::<U16>::digest(data).into()
}

/// Keyed BLAKE2b with a 32-byte output (MAC / KDF).
///
/// # Errors
/// [`CryptoError::InvalidLength`] if the key is not 16..=64 bytes (libsodium limits).
pub fn blake2b_256_keyed(key: &[u8], data: &[u8]) -> Result<[u8; 32], CryptoError> {
    if !(KEY_MIN_BYTES..=KEY_MAX_BYTES).contains(&key.len()) {
        return Err(CryptoError::InvalidLength);
    }
    let mut mac = Blake2bMac::<U32>::new_from_slice(key).map_err(|_| CryptoError::InvalidLength)?;
    mac.update(data);
    Ok(mac.finalize().into_bytes().into())
}

#[cfg(test)]
mod tests {
    use super::*;

    // libsodium: crypto_generichash(out, 32, "abc", 3, NULL, 0).
    #[test]
    fn unkeyed_matches_reference() {
        assert_eq!(
            hex::encode(blake2b_256(b"abc")),
            "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319"
        );
    }

    #[test]
    fn keyed_rejects_out_of_range_keys() {
        assert_eq!(
            blake2b_256_keyed(&[0; 15], b""),
            Err(CryptoError::InvalidLength)
        );
        assert_eq!(
            blake2b_256_keyed(&[0; 65], b""),
            Err(CryptoError::InvalidLength)
        );
        assert!(blake2b_256_keyed(&[0; 16], b"").is_ok());
    }
}
