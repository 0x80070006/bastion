// SPDX-License-Identifier: GPL-3.0-or-later
//! Operating-system randomness (equivalent of libsodium `randombytes_buf`).

use rand_core::{OsRng, RngCore};

/// Returns `N` bytes from the operating system CSPRNG.
#[must_use]
pub fn bytes<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    OsRng.fill_bytes(&mut out);
    out
}
