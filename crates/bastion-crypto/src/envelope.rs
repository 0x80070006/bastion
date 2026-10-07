// SPDX-License-Identifier: GPL-3.0-or-later
//! End-to-end envelopes (PROTOCOL.md §5–6, steps 1–5): sign-then-encrypt with the header
//! bound as AAD and inside the signed input.

use bastion_proto::v1::{Envelope, SignedMessage};
use bastion_proto::{ENVELOPE_VERSION, MAX_ENVELOPE_BYTES};
use prost::Message;

use crate::identity::{DEVICE_ID_BYTES, DeviceId, SigningKeypair, fixed, verify};
use crate::session::{NONCE_BYTES, SymmetricKey};
use crate::{CryptoError, context, random, transcript::Transcript};

/// Routing header visible to the relay.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    /// Envelope format version.
    pub version: u32,
    /// Sender device identifier.
    pub sender_id: DeviceId,
    /// Recipient device identifier.
    pub recipient_id: DeviceId,
    /// Sender exchange-key epoch.
    pub key_epoch: u32,
}

impl Header {
    /// Header of a current-version envelope.
    #[must_use]
    pub fn new(sender_id: DeviceId, recipient_id: DeviceId, key_epoch: u32) -> Self {
        Self {
            version: ENVELOPE_VERSION,
            sender_id,
            recipient_id,
            key_epoch,
        }
    }

    /// `AAD = T("bastion-env-v1", u32be(version), sender_id, recipient_id, u32be(key_epoch))`.
    #[must_use]
    pub fn aad(&self) -> Vec<u8> {
        Transcript::new(context::ENVELOPE_AAD)
            .u32(self.version)
            .field(&self.sender_id)
            .field(&self.recipient_id)
            .u32(self.key_epoch)
            .finish()
    }
}

/// Input of both the sender signature and the privileged counter-signature.
#[must_use]
pub fn signature_input(aad: &[u8], body: &[u8]) -> Vec<u8> {
    Transcript::new(context::MESSAGE_SIGNATURE)
        .field(aad)
        .field(body)
        .finish()
}

/// Signs `body` with the sender identity (and optionally the privileged key), then encrypts.
/// Returns the serialized [`Envelope`].
#[must_use]
pub fn seal(
    key: &SymmetricKey,
    header: &Header,
    sender: &SigningKeypair,
    privileged: Option<&SigningKeypair>,
    body: &[u8],
) -> Vec<u8> {
    seal_with_nonce(key, header, sender, privileged, body, &random::bytes())
}

pub(crate) fn seal_with_nonce(
    key: &SymmetricKey,
    header: &Header,
    sender: &SigningKeypair,
    privileged: Option<&SigningKeypair>,
    body: &[u8],
    nonce: &[u8; NONCE_BYTES],
) -> Vec<u8> {
    let aad = header.aad();
    let input = signature_input(&aad, body);
    let signed = SignedMessage {
        body: body.to_vec(),
        signature: sender.sign(&input).to_vec(),
        privileged_signature: privileged
            .map(|pk| pk.sign(&input).to_vec())
            .unwrap_or_default(),
    };
    Envelope {
        version: header.version,
        sender_id: header.sender_id.to_vec(),
        recipient_id: header.recipient_id.to_vec(),
        key_epoch: header.key_epoch,
        nonce: nonce.to_vec(),
        ciphertext: key.encrypt_with_nonce(nonce, &aad, &signed.encode_to_vec()),
    }
    .encode_to_vec()
}

/// Structurally validated envelope (steps 1–2 of PROTOCOL.md §6, minus the pairing lookup).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Parsed {
    /// Routing header.
    pub header: Header,
    nonce: Vec<u8>,
    ciphertext: Vec<u8>,
}

/// Parses an envelope after checking its size, version and field lengths.
///
/// # Errors
/// [`CryptoError::InvalidLength`], [`CryptoError::Malformed`] or
/// [`CryptoError::UnsupportedVersion`].
pub fn parse(bytes: &[u8]) -> Result<Parsed, CryptoError> {
    if bytes.len() > MAX_ENVELOPE_BYTES {
        return Err(CryptoError::InvalidLength);
    }
    let envelope = Envelope::decode(bytes).map_err(|_| CryptoError::Malformed)?;
    if envelope.version != ENVELOPE_VERSION {
        return Err(CryptoError::UnsupportedVersion);
    }
    if envelope.nonce.len() != NONCE_BYTES {
        return Err(CryptoError::InvalidLength);
    }
    Ok(Parsed {
        header: Header {
            version: envelope.version,
            sender_id: fixed::<DEVICE_ID_BYTES>(&envelope.sender_id)?,
            recipient_id: fixed::<DEVICE_ID_BYTES>(&envelope.recipient_id)?,
            key_epoch: envelope.key_epoch,
        },
        nonce: envelope.nonce,
        ciphertext: envelope.ciphertext,
    })
}

/// A decrypted, signature-verified message body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Opened {
    /// Routing header (authenticated).
    pub header: Header,
    /// Serialized `MessageBody` (not yet parsed).
    pub body: Vec<u8>,
    /// Whether a valid privileged counter-signature was present.
    pub privileged: bool,
}

/// Decrypts with the first candidate key that authenticates, then verifies the signatures
/// (steps 4–5 and 10 of PROTOCOL.md §6).
///
/// A present-but-invalid privileged signature rejects the whole message.
///
/// # Errors
/// [`CryptoError::DecryptionFailed`], [`CryptoError::Malformed`] or
/// [`CryptoError::InvalidSignature`].
pub fn open(
    parsed: &Parsed,
    candidate_keys: &[&SymmetricKey],
    sender_identity: &[u8; 32],
    privileged_key: Option<&[u8; 32]>,
) -> Result<Opened, CryptoError> {
    let aad = parsed.header.aad();
    let plaintext = candidate_keys
        .iter()
        .find_map(|key| key.decrypt(&parsed.nonce, &aad, &parsed.ciphertext).ok())
        .ok_or(CryptoError::DecryptionFailed)?;
    let signed = SignedMessage::decode(plaintext.as_slice()).map_err(|_| CryptoError::Malformed)?;
    let input = signature_input(&aad, &signed.body);
    verify(sender_identity, &input, &signed.signature)?;
    let privileged = if signed.privileged_signature.is_empty() {
        false
    } else {
        let key = privileged_key.ok_or(CryptoError::InvalidSignature)?;
        verify(key, &input, &signed.privileged_signature)?;
        true
    };
    Ok(Opened {
        header: parsed.header,
        body: signed.body,
        privileged,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture {
        key: SymmetricKey,
        sender: SigningKeypair,
        privileged: SigningKeypair,
        header: Header,
    }

    fn fixture() -> Fixture {
        let sender = SigningKeypair::generate();
        Fixture {
            key: SymmetricKey::generate(),
            header: Header::new(sender.device_id(), [9; 16], 0),
            sender,
            privileged: SigningKeypair::generate(),
        }
    }

    #[test]
    fn round_trip_with_and_without_privilege() {
        let f = fixture();
        let bytes = seal(&f.key, &f.header, &f.sender, None, b"body");
        let opened = open(&parse(&bytes).unwrap(), &[&f.key], &f.sender.public(), None).unwrap();
        assert_eq!(opened.body, b"body");
        assert!(!opened.privileged);

        let bytes = seal(&f.key, &f.header, &f.sender, Some(&f.privileged), b"wipe");
        let parsed = parse(&bytes).unwrap();
        let pk = f.privileged.public();
        assert!(
            open(&parsed, &[&f.key], &f.sender.public(), Some(&pk))
                .unwrap()
                .privileged
        );
        // Privileged signature present but no key registered: rejected, not silently ignored.
        assert_eq!(
            open(&parsed, &[&f.key], &f.sender.public(), None),
            Err(CryptoError::InvalidSignature)
        );
        let wrong = SigningKeypair::generate().public();
        assert_eq!(
            open(&parsed, &[&f.key], &f.sender.public(), Some(&wrong)),
            Err(CryptoError::InvalidSignature)
        );
    }

    #[test]
    fn header_tampering_is_detected() {
        let f = fixture();
        let bytes = seal(&f.key, &f.header, &f.sender, None, b"body");
        let mut envelope = Envelope::decode(bytes.as_slice()).unwrap();
        envelope.recipient_id = vec![8; 16];
        let parsed = parse(&envelope.encode_to_vec()).unwrap();
        assert_eq!(
            open(&parsed, &[&f.key], &f.sender.public(), None),
            Err(CryptoError::DecryptionFailed)
        );

        let mut envelope = Envelope::decode(bytes.as_slice()).unwrap();
        envelope.key_epoch = 1;
        let parsed = parse(&envelope.encode_to_vec()).unwrap();
        assert!(open(&parsed, &[&f.key], &f.sender.public(), None).is_err());
    }

    #[test]
    fn wrong_sender_identity_is_rejected() {
        let f = fixture();
        let bytes = seal(&f.key, &f.header, &f.sender, None, b"body");
        let other = SigningKeypair::generate().public();
        assert_eq!(
            open(&parse(&bytes).unwrap(), &[&f.key], &other, None),
            Err(CryptoError::InvalidSignature)
        );
    }

    #[test]
    fn second_candidate_key_is_tried() {
        let f = fixture();
        let bytes = seal(&f.key, &f.header, &f.sender, None, b"body");
        let stale = SymmetricKey::generate();
        assert!(
            open(
                &parse(&bytes).unwrap(),
                &[&stale, &f.key],
                &f.sender.public(),
                None
            )
            .is_ok()
        );
    }

    #[test]
    fn structural_checks_happen_before_decryption() {
        assert_eq!(
            parse(&vec![0u8; MAX_ENVELOPE_BYTES + 1]),
            Err(CryptoError::InvalidLength)
        );
        assert_eq!(parse(&[0xff, 0xff]), Err(CryptoError::Malformed));
        let wrong_version = Envelope {
            version: 2,
            ..Envelope::default()
        };
        assert_eq!(
            parse(&wrong_version.encode_to_vec()),
            Err(CryptoError::UnsupportedVersion)
        );
        let short_nonce = Envelope {
            version: 1,
            sender_id: vec![0; 16],
            recipient_id: vec![0; 16],
            nonce: vec![0; 12],
            ..Envelope::default()
        };
        assert_eq!(
            parse(&short_nonce.encode_to_vec()),
            Err(CryptoError::InvalidLength)
        );
    }
}
