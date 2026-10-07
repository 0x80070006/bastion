// SPDX-License-Identifier: GPL-3.0-or-later
//! Directional session keys (PROTOCOL.md §4) and the XChaCha20-Poly1305 AEAD wrapper.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use zeroize::Zeroizing;

use crate::identity::ExchangeKeypair;
use crate::{CryptoError, context, hash, random, transcript::Transcript};

/// XChaCha20 nonce length.
pub const NONCE_BYTES: usize = 24;
/// Poly1305 tag length.
pub const TAG_BYTES: usize = 16;

/// 256-bit symmetric key, zeroized on drop.
pub struct SymmetricKey(Zeroizing<[u8; 32]>);

impl SymmetricKey {
    /// Wraps raw key bytes.
    #[must_use]
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(Zeroizing::new(bytes))
    }

    /// Generates a random key.
    #[must_use]
    pub fn generate() -> Self {
        Self::from_bytes(random::bytes())
    }

    /// Raw key bytes (test vectors and encrypted storage only).
    #[must_use]
    pub fn expose(&self) -> &[u8; 32] {
        &self.0
    }

    /// XChaCha20-Poly1305 IETF encryption with an explicit nonce.
    #[must_use]
    pub fn encrypt_with_nonce(
        &self,
        nonce: &[u8; NONCE_BYTES],
        aad: &[u8],
        plaintext: &[u8],
    ) -> Vec<u8> {
        let cipher = XChaCha20Poly1305::new((&*self.0).into());
        // Encryption only fails for plaintexts larger than ~256 GiB.
        cipher
            .encrypt(
                XNonce::from_slice(nonce),
                Payload {
                    msg: plaintext,
                    aad,
                },
            )
            .unwrap_or_default()
    }

    /// XChaCha20-Poly1305 IETF decryption.
    ///
    /// # Errors
    /// [`CryptoError::DecryptionFailed`] on authentication failure.
    pub fn decrypt(
        &self,
        nonce: &[u8],
        aad: &[u8],
        ciphertext: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        if nonce.len() != NONCE_BYTES || ciphertext.len() < TAG_BYTES {
            return Err(CryptoError::DecryptionFailed);
        }
        XChaCha20Poly1305::new((&*self.0).into())
            .decrypt(
                XNonce::from_slice(nonce),
                Payload {
                    msg: ciphertext,
                    aad,
                },
            )
            .map_err(|_| CryptoError::DecryptionFailed)
    }

    /// Encrypts with a random nonce and returns `nonce ‖ ciphertext`.
    #[must_use]
    pub fn seal(&self, aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
        let nonce = random::bytes::<NONCE_BYTES>();
        let mut out = nonce.to_vec();
        out.extend(self.encrypt_with_nonce(&nonce, aad, plaintext));
        out
    }

    /// Opens `nonce ‖ ciphertext` produced by [`Self::seal`].
    ///
    /// # Errors
    /// [`CryptoError::DecryptionFailed`].
    pub fn open(&self, aad: &[u8], sealed: &[u8]) -> Result<Zeroizing<Vec<u8>>, CryptoError> {
        if sealed.len() < NONCE_BYTES + TAG_BYTES {
            return Err(CryptoError::DecryptionFailed);
        }
        let (nonce, ciphertext) = sealed.split_at(NONCE_BYTES);
        self.decrypt(nonce, aad, ciphertext).map(Zeroizing::new)
    }
}

impl std::fmt::Debug for SymmetricKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SymmetricKey(redacted)")
    }
}

/// Public keys of one side of a session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Party {
    /// Ed25519 identity public key.
    pub identity: [u8; 32],
    /// X25519 public key of the epoch in use.
    pub exchange: [u8; 32],
}

/// Derives the key protecting messages from `sender` to `recipient`.
///
/// ```text
/// ss   = X25519(local.XK, peer.XK)
/// salt = BLAKE2b-256(T("bastion-session-v1", min(IK_a, IK_b), max(IK_a, IK_b)))
/// key  = BLAKE2b-256(key = ss, T("bastion-session-key-v1", salt,
///                                sender.IK, sender.XK, recipient.IK, recipient.XK))
/// ```
///
/// # Errors
/// [`CryptoError::InvalidKey`] if `local` is neither party's exchange key,
/// [`CryptoError::WeakKey`] for a low-order peer key.
pub fn directional_key(
    local: &ExchangeKeypair,
    sender: &Party,
    recipient: &Party,
) -> Result<SymmetricKey, CryptoError> {
    let local_public = local.public();
    let peer = if local_public == sender.exchange {
        &recipient.exchange
    } else if local_public == recipient.exchange {
        &sender.exchange
    } else {
        return Err(CryptoError::InvalidKey);
    };
    let shared = local.diffie_hellman(peer)?;
    let (low, high) = if sender.identity <= recipient.identity {
        (&sender.identity, &recipient.identity)
    } else {
        (&recipient.identity, &sender.identity)
    };
    let salt = hash::blake2b_256(
        &Transcript::new(context::SESSION)
            .field(low)
            .field(high)
            .finish(),
    );
    let info = Transcript::new(context::SESSION_KEY)
        .field(&salt)
        .field(&sender.identity)
        .field(&sender.exchange)
        .field(&recipient.identity)
        .field(&recipient.exchange)
        .finish();
    Ok(SymmetricKey::from_bytes(hash::blake2b_256_keyed(
        shared.as_slice(),
        &info,
    )?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::SigningKeypair;

    struct Device {
        ik: SigningKeypair,
        xk: ExchangeKeypair,
    }

    impl Device {
        fn new() -> Self {
            Self {
                ik: SigningKeypair::generate(),
                xk: ExchangeKeypair::generate(0),
            }
        }
        fn party(&self) -> Party {
            Party {
                identity: self.ik.public(),
                exchange: self.xk.public(),
            }
        }
    }

    #[test]
    fn both_sides_derive_the_same_directional_keys() {
        let (a, b) = (Device::new(), Device::new());
        let a2b_at_a = directional_key(&a.xk, &a.party(), &b.party()).unwrap();
        let a2b_at_b = directional_key(&b.xk, &a.party(), &b.party()).unwrap();
        let b2a_at_a = directional_key(&a.xk, &b.party(), &a.party()).unwrap();
        assert_eq!(a2b_at_a.expose(), a2b_at_b.expose());
        assert_ne!(a2b_at_a.expose(), b2a_at_a.expose());
    }

    #[test]
    fn foreign_local_key_is_rejected() {
        let (a, b, c) = (Device::new(), Device::new(), Device::new());
        assert_eq!(
            directional_key(&c.xk, &a.party(), &b.party()).err(),
            Some(CryptoError::InvalidKey)
        );
    }

    #[test]
    fn aead_detects_tampering() {
        let key = SymmetricKey::generate();
        let sealed = key.seal(b"aad", b"secret");
        assert_eq!(key.open(b"aad", &sealed).unwrap().as_slice(), b"secret");
        assert!(key.open(b"other", &sealed).is_err());
        let mut flipped = sealed.clone();
        if let Some(last) = flipped.last_mut() {
            *last ^= 1;
        }
        assert!(key.open(b"aad", &flipped).is_err());
        assert!(key.open(b"aad", &sealed[..20]).is_err());
    }
}
