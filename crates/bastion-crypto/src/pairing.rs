// SPDX-License-Identifier: GPL-3.0-or-later
//! Pairing (PROTOCOL.md §3): invitation token, enrollment proof and signature, SAS.

use subtle::ConstantTimeEq;

use crate::identity::DeviceKeys;
use crate::{CryptoError, context, hash, random, transcript::Transcript};

/// Invitation token length (128 bits).
pub const TOKEN_BYTES: usize = 16;
/// Maximum invitation lifetime.
pub const INVITE_MAX_TTL_MS: i64 = 300_000;

/// One-time invitation token. Never sent to the relay.
pub type Token = [u8; TOKEN_BYTES];

/// Generates a fresh invitation token.
#[must_use]
pub fn generate_token() -> Token {
    random::bytes()
}

/// `token_hash = BLAKE2b-256(key = "bastion-invite-v1", token)`.
#[must_use]
pub fn token_hash(token: &Token) -> [u8; 32] {
    // The context key is 17 bytes, inside libsodium's 16..=64 range.
    hash::blake2b_256_keyed(context::INVITE_TOKEN_HASH, token).unwrap_or([0; 32])
}

/// `proof = BLAKE2b-256(key = token, T("bastion-enroll-v1", phone.IK, phone.XK, wg.pub, pc.IK))`.
#[must_use]
pub fn enroll_proof(
    token: &Token,
    phone_identity: &[u8; 32],
    phone_exchange: &[u8; 32],
    wireguard_public: &[u8],
    controller_identity: &[u8; 32],
) -> [u8; 32] {
    let input = Transcript::new(context::ENROLL_PROOF)
        .field(phone_identity)
        .field(phone_exchange)
        .field(wireguard_public)
        .field(controller_identity)
        .finish();
    // A 16-byte key is inside libsodium's range.
    hash::blake2b_256_keyed(token, &input).unwrap_or([0; 32])
}

/// Constant-time proof verification by the controller.
#[must_use]
pub fn verify_enroll_proof(
    token: &Token,
    phone: &DeviceKeys,
    wireguard_public: &[u8],
    controller_identity: &[u8; 32],
    proof: &[u8],
) -> bool {
    let expected = enroll_proof(
        token,
        &phone.identity,
        &phone.exchange,
        wireguard_public,
        controller_identity,
    );
    proof.len() == expected.len() && bool::from(expected.ct_eq(proof))
}

/// Transcript signed by the enrolling device (PROTOCOL.md §3.2).
#[must_use]
pub fn enroll_transcript(
    token_hash: &[u8],
    device: &DeviceKeys,
    wireguard_public: &[u8],
    proof: &[u8],
    role: i32,
) -> Vec<u8> {
    Transcript::new(context::ENROLL_SIGNATURE)
        .field(token_hash)
        .field(&device.identity)
        .field(&device.exchange)
        .field(&device.exchange_signature)
        .u32(device.epoch)
        .field(wireguard_public)
        .field(proof)
        .u32(u32::try_from(role).unwrap_or(0))
        .finish()
}

/// Six-digit short authentication string (PROTOCOL.md §3.3).
///
/// `A = pc.IK ‖ pc.XK`, `B = phone.IK ‖ phone.XK` (order-independent).
#[must_use]
pub fn sas_code(token: &Token, a: &DeviceKeys, b: &DeviceKeys) -> u32 {
    let mut first = [0u8; 64];
    first[..32].copy_from_slice(&a.identity);
    first[32..].copy_from_slice(&a.exchange);
    let mut second = [0u8; 64];
    second[..32].copy_from_slice(&b.identity);
    second[32..].copy_from_slice(&b.exchange);
    let (low, high) = if first <= second {
        (first, second)
    } else {
        (second, first)
    };
    let input = Transcript::new(context::SAS)
        .field(&low)
        .field(&high)
        .finish();
    let digest = hash::blake2b_256_keyed(token, &input).unwrap_or([0; 32]);
    u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000
}

/// Formats a SAS as `"123 456"`.
#[must_use]
pub fn format_sas(code: u32) -> String {
    let digits = format!("{:06}", code % 1_000_000);
    format!("{} {}", &digits[..3], &digits[3..])
}

/// Validates an invitation expiry against the local clock.
///
/// # Errors
/// [`CryptoError::Malformed`] when expired or further than [`INVITE_MAX_TTL_MS`] ahead.
pub fn check_invite_expiry(expires_at_ms: i64, now_ms: i64) -> Result<(), CryptoError> {
    if expires_at_ms <= now_ms || expires_at_ms - now_ms > INVITE_MAX_TTL_MS + 30_000 {
        return Err(CryptoError::Malformed);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::{ExchangeKeypair, SigningKeypair};

    fn device() -> DeviceKeys {
        ExchangeKeypair::generate(0).signed_by(&SigningKeypair::generate())
    }

    #[test]
    fn sas_is_symmetric_and_token_bound() {
        let token = generate_token();
        let (pc, phone) = (device(), device());
        assert_eq!(sas_code(&token, &pc, &phone), sas_code(&token, &phone, &pc));
        let other = generate_token();
        // 1e-6 false-positive probability; acceptable for a unit test.
        assert_ne!(sas_code(&token, &pc, &phone), sas_code(&other, &pc, &phone));
        assert_ne!(
            sas_code(&token, &pc, &phone),
            sas_code(&token, &pc, &device())
        );
    }

    #[test]
    fn proof_binds_every_key() {
        let token = generate_token();
        let (pc, phone) = (device(), device());
        let wg = [7u8; 32];
        let proof = enroll_proof(&token, &phone.identity, &phone.exchange, &wg, &pc.identity);
        assert!(verify_enroll_proof(
            &token,
            &phone,
            &wg,
            &pc.identity,
            &proof
        ));
        assert!(!verify_enroll_proof(
            &token,
            &phone,
            &[8u8; 32],
            &pc.identity,
            &proof
        ));
        assert!(!verify_enroll_proof(
            &token,
            &device(),
            &wg,
            &pc.identity,
            &proof
        ));
        assert!(!verify_enroll_proof(
            &generate_token(),
            &phone,
            &wg,
            &pc.identity,
            &proof
        ));
        assert!(!verify_enroll_proof(
            &token,
            &phone,
            &wg,
            &pc.identity,
            &proof[..31]
        ));
    }

    #[test]
    fn sas_formatting() {
        assert_eq!(format_sas(42), "000 042");
        assert_eq!(format_sas(123_456), "123 456");
    }

    #[test]
    fn invite_expiry_window() {
        assert!(check_invite_expiry(1_000 + 60_000, 1_000).is_ok());
        assert!(check_invite_expiry(1_000, 1_000).is_err());
        assert!(check_invite_expiry(1_000 + 3_600_000, 1_000).is_err());
    }
}
