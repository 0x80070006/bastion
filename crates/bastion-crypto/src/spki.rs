// SPDX-License-Identifier: GPL-3.0-or-later
//! TLS public-key pinning (ADR-0009): SHA-256 of the DER `SubjectPublicKeyInfo`.

use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

/// SHA-256 of a DER-encoded `SubjectPublicKeyInfo`.
#[must_use]
pub fn pin(spki_der: &[u8]) -> [u8; 32] {
    Sha256::digest(spki_der).into()
}

/// Constant-time comparison of a pin against a DER `SubjectPublicKeyInfo`.
#[must_use]
pub fn matches(expected_pin: &[u8], spki_der: &[u8]) -> bool {
    expected_pin.len() == 32 && bool::from(pin(spki_der).ct_eq(expected_pin))
}
