// SPDX-License-Identifier: GPL-3.0-or-later
//! Domain-separation strings (ASCII, no terminator). Must stay byte-identical to
//! `org.bastion.core.crypto.ContextStrings` on Android.

/// Signature of an X25519 key by an identity key.
pub const X25519_KEY_SIGNATURE: &[u8] = b"bastion-xk-v1";
/// Invitation token hash key.
pub const INVITE_TOKEN_HASH: &[u8] = b"bastion-invite-v1";
/// Enrollment proof (keyed by the invitation token).
pub const ENROLL_PROOF: &[u8] = b"bastion-enroll-v1";
/// Enrollment request signature.
pub const ENROLL_SIGNATURE: &[u8] = b"bastion-enroll-sig-v1";
/// Short authentication string.
pub const SAS: &[u8] = b"bastion-sas-v1";
/// Session salt.
pub const SESSION: &[u8] = b"bastion-session-v1";
/// Directional session key.
pub const SESSION_KEY: &[u8] = b"bastion-session-key-v1";
/// Envelope additional authenticated data.
pub const ENVELOPE_AAD: &[u8] = b"bastion-env-v1";
/// Message signature.
pub const MESSAGE_SIGNATURE: &[u8] = b"bastion-msg-v1";
/// Relay request signature.
pub const REQUEST_SIGNATURE: &[u8] = b"bastion-req-v1";
/// Displayed device fingerprint.
pub const FINGERPRINT: &[u8] = b"bastion-fp-v1";

/// All context strings (tests assert they are pairwise distinct).
pub const ALL: [&[u8]; 11] = [
    X25519_KEY_SIGNATURE,
    INVITE_TOKEN_HASH,
    ENROLL_PROOF,
    ENROLL_SIGNATURE,
    SAS,
    SESSION,
    SESSION_KEY,
    ENVELOPE_AAD,
    MESSAGE_SIGNATURE,
    REQUEST_SIGNATURE,
    FINGERPRINT,
];

#[cfg(test)]
mod tests {
    #[test]
    fn contexts_are_distinct_ascii() {
        for (i, a) in super::ALL.iter().enumerate() {
            assert!(a.is_ascii());
            for b in &super::ALL[i + 1..] {
                assert_ne!(a, b);
            }
        }
    }
}
