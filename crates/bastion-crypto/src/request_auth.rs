// SPDX-License-Identifier: GPL-3.0-or-later
//! Relay request authentication (PROTOCOL.md §8): header
//! `Bastion-Auth: hex(device_id).timestamp_ms.hex(nonce).base64url(signature)` with
//! `signature = Ed25519(IK, T("bastion-req-v1", method, path, u64be(timestamp), nonce,
//! BLAKE2b-256(body)))`. `path` includes the query string.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

use crate::identity::{DEVICE_ID_BYTES, DeviceId, SigningKeypair, verify};
use crate::{CryptoError, context, hash, random, transcript::Transcript};

/// HTTP header name.
pub const HEADER: &str = "bastion-auth";
/// Request nonce length.
pub const NONCE_BYTES: usize = 16;
/// Accepted clock skew between client and relay.
pub const MAX_SKEW_MS: u64 = 30_000;
const MAX_HEADER_LEN: usize = 256;

/// Signed input of a request.
#[must_use]
pub fn signature_input(
    method: &str,
    path_and_query: &str,
    timestamp_ms: u64,
    nonce: &[u8; NONCE_BYTES],
    body: &[u8],
) -> Vec<u8> {
    Transcript::new(context::REQUEST_SIGNATURE)
        .field(method.as_bytes())
        .field(path_and_query.as_bytes())
        .u64(timestamp_ms)
        .field(nonce)
        .field(&hash::blake2b_256(body))
        .finish()
}

/// Builds the header value for a request.
#[must_use]
pub fn sign(
    identity: &SigningKeypair,
    method: &str,
    path_and_query: &str,
    timestamp_ms: u64,
    body: &[u8],
) -> String {
    sign_with_nonce(
        identity,
        method,
        path_and_query,
        timestamp_ms,
        body,
        &random::bytes(),
    )
}

pub(crate) fn sign_with_nonce(
    identity: &SigningKeypair,
    method: &str,
    path_and_query: &str,
    timestamp_ms: u64,
    body: &[u8],
    nonce: &[u8; NONCE_BYTES],
) -> String {
    let signature = identity.sign(&signature_input(
        method,
        path_and_query,
        timestamp_ms,
        nonce,
        body,
    ));
    format!(
        "{}.{}.{}.{}",
        hex::encode(identity.device_id()),
        timestamp_ms,
        hex::encode(nonce),
        URL_SAFE_NO_PAD.encode(signature)
    )
}

/// Parsed (not yet verified) authentication header.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthHeader {
    /// Claimed device.
    pub device_id: DeviceId,
    /// Client clock.
    pub timestamp_ms: u64,
    /// Single-use nonce.
    pub nonce: [u8; NONCE_BYTES],
    signature: [u8; 64],
}

impl AuthHeader {
    /// Parses a header value.
    ///
    /// # Errors
    /// [`CryptoError::Malformed`].
    pub fn parse(value: &str) -> Result<Self, CryptoError> {
        if value.len() > MAX_HEADER_LEN {
            return Err(CryptoError::Malformed);
        }
        let mut parts = value.split('.');
        let (Some(id), Some(ts), Some(nonce), Some(sig), None) = (
            parts.next(),
            parts.next(),
            parts.next(),
            parts.next(),
            parts.next(),
        ) else {
            return Err(CryptoError::Malformed);
        };
        let mut device_id = [0u8; DEVICE_ID_BYTES];
        hex::decode_to_slice(id, &mut device_id).map_err(|_| CryptoError::Malformed)?;
        let mut nonce_bytes = [0u8; NONCE_BYTES];
        hex::decode_to_slice(nonce, &mut nonce_bytes).map_err(|_| CryptoError::Malformed)?;
        if ts.is_empty() || ts.len() > 20 || !ts.bytes().all(|b| b.is_ascii_digit()) {
            return Err(CryptoError::Malformed);
        }
        let timestamp_ms = ts.parse().map_err(|_| CryptoError::Malformed)?;
        let signature: [u8; 64] = URL_SAFE_NO_PAD
            .decode(sig)
            .map_err(|_| CryptoError::Malformed)?
            .try_into()
            .map_err(|_| CryptoError::Malformed)?;
        Ok(Self {
            device_id,
            timestamp_ms,
            nonce: nonce_bytes,
            signature,
        })
    }

    /// Whether the timestamp is within [`MAX_SKEW_MS`] of `now_ms`.
    #[must_use]
    pub fn is_fresh(&self, now_ms: u64) -> bool {
        self.timestamp_ms.abs_diff(now_ms) <= MAX_SKEW_MS
    }

    /// Verifies the signature with the device identity key, which must hash to `device_id`.
    /// The caller MUST also check freshness and nonce uniqueness.
    ///
    /// # Errors
    /// [`CryptoError::InvalidKey`] or [`CryptoError::InvalidSignature`].
    pub fn verify(
        &self,
        identity_public: &[u8; 32],
        method: &str,
        path_and_query: &str,
        body: &[u8],
    ) -> Result<(), CryptoError> {
        if crate::identity::device_id(identity_public) != self.device_id {
            return Err(CryptoError::InvalidKey);
        }
        verify(
            identity_public,
            &signature_input(method, path_and_query, self.timestamp_ms, &self.nonce, body),
            &self.signature,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signed_request_verifies() {
        let ik = SigningKeypair::generate();
        let header = sign(&ik, "PUT", "/v1/mailbox/ab", 1_000, b"body");
        let parsed = AuthHeader::parse(&header).unwrap();
        assert_eq!(parsed.device_id, ik.device_id());
        let pk = ik.public();
        assert!(parsed.verify(&pk, "PUT", "/v1/mailbox/ab", b"body").is_ok());
        assert!(
            parsed
                .verify(&pk, "GET", "/v1/mailbox/ab", b"body")
                .is_err()
        );
        assert!(
            parsed
                .verify(&pk, "PUT", "/v1/mailbox/cd", b"body")
                .is_err()
        );
        assert!(
            parsed
                .verify(&pk, "PUT", "/v1/mailbox/ab", b"bodx")
                .is_err()
        );
        let other = SigningKeypair::generate();
        assert_eq!(
            parsed.verify(&other.public(), "PUT", "/v1/mailbox/ab", b"body"),
            Err(CryptoError::InvalidKey)
        );
    }

    #[test]
    fn freshness() {
        let ik = SigningKeypair::generate();
        let parsed = AuthHeader::parse(&sign(&ik, "GET", "/", 100_000, b"")).unwrap();
        assert!(parsed.is_fresh(100_000 + MAX_SKEW_MS));
        assert!(!parsed.is_fresh(100_000 + MAX_SKEW_MS + 1));
        assert!(!parsed.is_fresh(100_000 - MAX_SKEW_MS - 1));
    }

    #[test]
    fn malformed_headers_are_rejected() {
        let long = "x".repeat(300);
        for value in [
            "",
            "a.b.c",
            "a.b.c.d.e",
            "zz.1.00.AA",
            long.as_str(),
            "00000000000000000000000000000000.-1.00000000000000000000000000000000.AA",
            "00000000000000000000000000000000.1.00000000000000000000000000000000.AA",
        ] {
            assert_eq!(
                AuthHeader::parse(value),
                Err(CryptoError::Malformed),
                "{value}"
            );
        }
    }
}
