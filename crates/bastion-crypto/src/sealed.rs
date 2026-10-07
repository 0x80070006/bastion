// SPDX-License-Identifier: GPL-3.0-or-later
//! Anonymous sealed boxes, compatible with libsodium `crypto_box_seal` (used for
//! `PairingHello`, PROTOCOL.md §3.2 step 5).

use crypto_box::{PublicKey, SecretKey};
use rand_core::OsRng;

use crate::CryptoError;
use crate::identity::ExchangeKeypair;

/// Overhead added by a sealed box (ephemeral public key + tag).
pub const OVERHEAD: usize = 32 + 16;

/// Seals `message` to an X25519 public key.
///
/// # Errors
/// [`CryptoError::InvalidKey`] if encryption fails.
pub fn seal(recipient_exchange: &[u8; 32], message: &[u8]) -> Result<Vec<u8>, CryptoError> {
    PublicKey::from(*recipient_exchange)
        .seal(&mut OsRng, message)
        .map_err(|_| CryptoError::InvalidKey)
}

/// Opens a sealed box with the recipient exchange key.
///
/// # Errors
/// [`CryptoError::DecryptionFailed`].
pub fn open(recipient: &ExchangeKeypair, sealed: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if sealed.len() < OVERHEAD {
        return Err(CryptoError::DecryptionFailed);
    }
    SecretKey::from(*recipient.secret())
        .unseal(sealed)
        .map_err(|_| CryptoError::DecryptionFailed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_and_wrong_recipient() {
        let recipient = ExchangeKeypair::generate(0);
        let sealed = seal(&recipient.public(), b"hello").unwrap();
        assert_eq!(sealed.len(), 5 + OVERHEAD);
        assert_eq!(open(&recipient, &sealed).unwrap(), b"hello");
        assert!(open(&ExchangeKeypair::generate(0), &sealed).is_err());
        assert!(open(&recipient, &sealed[..10]).is_err());
    }
}
