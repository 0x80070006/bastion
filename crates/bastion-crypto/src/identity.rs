// SPDX-License-Identifier: GPL-3.0-or-later
//! Device identities (PROTOCOL.md §2): Ed25519 identity key `IK`, signed X25519 key `XK`.

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::{CryptoError, context, hash, random, transcript::Transcript};

/// Length of Ed25519 and X25519 public keys.
pub const PUBLIC_KEY_BYTES: usize = 32;
/// Length of an Ed25519 signature.
pub const SIGNATURE_BYTES: usize = 64;
/// Length of a device identifier.
pub const DEVICE_ID_BYTES: usize = 16;

/// `device_id = BLAKE2b-128(IK.pub)`.
pub type DeviceId = [u8; DEVICE_ID_BYTES];

/// Ed25519 signing key (identity key `IK` or privileged key `PK`).
pub struct SigningKeypair(SigningKey);

impl SigningKeypair {
    /// Generates a fresh key from OS randomness.
    #[must_use]
    pub fn generate() -> Self {
        let seed = Zeroizing::new(random::bytes::<32>());
        Self(SigningKey::from_bytes(&seed))
    }

    /// Rebuilds a key from its 32-byte seed (libsodium `crypto_sign_seed_keypair`).
    #[must_use]
    pub fn from_seed(seed: &[u8; 32]) -> Self {
        Self(SigningKey::from_bytes(seed))
    }

    /// Returns the 32-byte seed, for encrypted storage only.
    #[must_use]
    pub fn seed(&self) -> Zeroizing<[u8; 32]> {
        Zeroizing::new(self.0.to_bytes())
    }

    /// Public key.
    #[must_use]
    pub fn public(&self) -> [u8; 32] {
        self.0.verifying_key().to_bytes()
    }

    /// Device identifier derived from the public key.
    #[must_use]
    pub fn device_id(&self) -> DeviceId {
        device_id(&self.public())
    }

    /// Detached Ed25519 signature (libsodium `crypto_sign_detached`).
    #[must_use]
    pub fn sign(&self, message: &[u8]) -> [u8; 64] {
        self.0.sign(message).to_bytes()
    }
}

impl std::fmt::Debug for SigningKeypair {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SigningKeypair")
            .field("public", &hex::encode(self.public()))
            .finish_non_exhaustive()
    }
}

/// X25519 key-agreement key `XK` with its epoch.
pub struct ExchangeKeypair {
    secret: StaticSecret,
    epoch: u32,
}

impl ExchangeKeypair {
    /// Generates a fresh key for `epoch`.
    #[must_use]
    pub fn generate(epoch: u32) -> Self {
        let bytes = Zeroizing::new(random::bytes::<32>());
        Self::from_secret(&bytes, epoch)
    }

    /// Rebuilds a key from its 32-byte secret.
    #[must_use]
    pub fn from_secret(secret: &[u8; 32], epoch: u32) -> Self {
        Self {
            secret: StaticSecret::from(*secret),
            epoch,
        }
    }

    /// Returns the secret, for encrypted storage only.
    #[must_use]
    pub fn secret(&self) -> Zeroizing<[u8; 32]> {
        Zeroizing::new(self.secret.to_bytes())
    }

    /// Public key (libsodium `crypto_scalarmult_base`).
    #[must_use]
    pub fn public(&self) -> [u8; 32] {
        PublicKey::from(&self.secret).to_bytes()
    }

    /// Key epoch.
    #[must_use]
    pub fn epoch(&self) -> u32 {
        self.epoch
    }

    /// X25519 shared secret with a peer public key.
    ///
    /// # Errors
    /// [`CryptoError::WeakKey`] when the result is all-zero (low-order peer key), like
    /// libsodium `crypto_scalarmult`.
    pub fn diffie_hellman(
        &self,
        peer_public: &[u8; 32],
    ) -> Result<Zeroizing<[u8; 32]>, CryptoError> {
        let shared = self.secret.diffie_hellman(&PublicKey::from(*peer_public));
        if !shared.was_contributory() {
            return Err(CryptoError::WeakKey);
        }
        Ok(Zeroizing::new(shared.to_bytes()))
    }

    /// Public half, signed by `identity`.
    #[must_use]
    pub fn signed_by(&self, identity: &SigningKeypair) -> DeviceKeys {
        let exchange = self.public();
        DeviceKeys {
            identity: identity.public(),
            exchange,
            exchange_signature: identity.sign(&exchange_key_transcript(&exchange, self.epoch)),
            epoch: self.epoch,
        }
    }
}

impl std::fmt::Debug for ExchangeKeypair {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ExchangeKeypair")
            .field("public", &hex::encode(self.public()))
            .field("epoch", &self.epoch)
            .finish_non_exhaustive()
    }
}

/// Public keys of a device (`bastion.v1.DeviceKeys`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceKeys {
    /// Ed25519 identity public key.
    pub identity: [u8; 32],
    /// X25519 public key.
    pub exchange: [u8; 32],
    /// Signature of `exchange` and `epoch` by `identity`.
    pub exchange_signature: [u8; 64],
    /// Epoch of `exchange`.
    pub epoch: u32,
}

impl DeviceKeys {
    /// Parses and verifies the protobuf representation.
    ///
    /// # Errors
    /// [`CryptoError::InvalidLength`] for wrong field sizes, [`CryptoError::InvalidKey`] for a
    /// weak identity key, [`CryptoError::InvalidSignature`] if `XKsig` does not verify.
    pub fn from_proto(proto: &bastion_proto::v1::DeviceKeys) -> Result<Self, CryptoError> {
        let keys = Self {
            identity: fixed(&proto.identity_public_key)?,
            exchange: fixed(&proto.x25519_public_key)?,
            exchange_signature: fixed(&proto.x25519_signature)?,
            epoch: proto.key_epoch,
        };
        keys.verify()?;
        Ok(keys)
    }

    /// Protobuf representation.
    #[must_use]
    pub fn to_proto(&self) -> bastion_proto::v1::DeviceKeys {
        bastion_proto::v1::DeviceKeys {
            identity_public_key: self.identity.to_vec(),
            x25519_public_key: self.exchange.to_vec(),
            x25519_signature: self.exchange_signature.to_vec(),
            key_epoch: self.epoch,
        }
    }

    /// Verifies `XKsig = Sign(IK, T("bastion-xk-v1", XK.pub, u32be(epoch)))`.
    ///
    /// # Errors
    /// See [`Self::from_proto`].
    pub fn verify(&self) -> Result<(), CryptoError> {
        verify(
            &self.identity,
            &exchange_key_transcript(&self.exchange, self.epoch),
            &self.exchange_signature,
        )
    }

    /// Device identifier.
    #[must_use]
    pub fn device_id(&self) -> DeviceId {
        device_id(&self.identity)
    }

    /// Displayed fingerprint (8 groups of 4 hex digits).
    #[must_use]
    pub fn fingerprint(&self) -> String {
        fingerprint(&self.identity, &self.exchange)
    }
}

/// Transcript signed to bind an X25519 key and its epoch to an identity.
#[must_use]
pub fn exchange_key_transcript(exchange: &[u8; 32], epoch: u32) -> Vec<u8> {
    Transcript::new(context::X25519_KEY_SIGNATURE)
        .field(exchange)
        .u32(epoch)
        .finish()
}

/// `device_id = BLAKE2b-128(IK.pub)`.
#[must_use]
pub fn device_id(identity_public: &[u8; 32]) -> DeviceId {
    hash::blake2b_128(identity_public)
}

/// `BLAKE2b-256(T("bastion-fp-v1", IK.pub, XK.pub))`, first 8 groups of 4 hex digits.
#[must_use]
pub fn fingerprint(identity_public: &[u8; 32], exchange_public: &[u8; 32]) -> String {
    let digest = hash::blake2b_256(
        &Transcript::new(context::FINGERPRINT)
            .field(identity_public)
            .field(exchange_public)
            .finish(),
    );
    let hex = hex::encode(&digest[..16]);
    hex.as_bytes()
        .chunks(4)
        .map(|chunk| String::from_utf8_lossy(chunk).into_owned())
        .collect::<Vec<_>>()
        .join(" ")
}

/// Verifies a detached Ed25519 signature with strict rules (canonical encodings, no
/// small-order keys), like libsodium `crypto_sign_verify_detached`.
///
/// # Errors
/// [`CryptoError::InvalidKey`] or [`CryptoError::InvalidSignature`].
pub fn verify(public: &[u8], message: &[u8], signature: &[u8]) -> Result<(), CryptoError> {
    let public: [u8; 32] = public.try_into().map_err(|_| CryptoError::InvalidKey)?;
    let signature: [u8; 64] = signature
        .try_into()
        .map_err(|_| CryptoError::InvalidSignature)?;
    let key = VerifyingKey::from_bytes(&public).map_err(|_| CryptoError::InvalidKey)?;
    if key.is_weak() {
        return Err(CryptoError::InvalidKey);
    }
    key.verify_strict(message, &Signature::from_bytes(&signature))
        .map_err(|_| CryptoError::InvalidSignature)
}

/// Converts a slice into a fixed-size array.
///
/// # Errors
/// [`CryptoError::InvalidLength`] if the length differs.
pub fn fixed<const N: usize>(bytes: &[u8]) -> Result<[u8; N], CryptoError> {
    bytes.try_into().map_err(|_| CryptoError::InvalidLength)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signed_exchange_key_verifies_and_detects_tampering() {
        let ik = SigningKeypair::generate();
        let xk = ExchangeKeypair::generate(3);
        let keys = xk.signed_by(&ik);
        assert_eq!(keys.verify(), Ok(()));
        assert_eq!(DeviceKeys::from_proto(&keys.to_proto()), Ok(keys.clone()));

        let mut wrong_epoch = keys.clone();
        wrong_epoch.epoch = 4;
        assert_eq!(wrong_epoch.verify(), Err(CryptoError::InvalidSignature));

        let mut wrong_key = keys;
        wrong_key.exchange[0] ^= 1;
        assert_eq!(wrong_key.verify(), Err(CryptoError::InvalidSignature));
    }

    #[test]
    fn low_order_exchange_is_rejected() {
        let xk = ExchangeKeypair::generate(0);
        assert_eq!(xk.diffie_hellman(&[0u8; 32]), Err(CryptoError::WeakKey));
    }

    #[test]
    fn exchange_is_symmetric() {
        let a = ExchangeKeypair::generate(0);
        let b = ExchangeKeypair::generate(0);
        assert_eq!(
            *a.diffie_hellman(&b.public()).unwrap(),
            *b.diffie_hellman(&a.public()).unwrap()
        );
    }

    #[test]
    fn identity_round_trips_through_seed() {
        let ik = SigningKeypair::generate();
        let restored = SigningKeypair::from_seed(&ik.seed());
        assert_eq!(ik.public(), restored.public());
        assert_eq!(ik.sign(b"m"), restored.sign(b"m"));
    }

    #[test]
    fn fingerprint_format() {
        let fp = fingerprint(&[1; 32], &[2; 32]);
        assert_eq!(fp.len(), 8 * 4 + 7);
        assert_eq!(fp.split(' ').count(), 8);
    }

    #[test]
    fn weak_identity_keys_are_rejected() {
        // The neutral element is a small-order point.
        let mut neutral = [0u8; 32];
        neutral[0] = 1;
        assert_eq!(
            verify(&neutral, b"m", &[0; 64]),
            Err(CryptoError::InvalidKey)
        );
    }
}
