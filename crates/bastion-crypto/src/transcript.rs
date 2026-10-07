// SPDX-License-Identifier: GPL-3.0-or-later
//! Unambiguous encoding of signed / hashed inputs (PROTOCOL.md §1.1).
//!
//! `T(ctx, f1, …, fn) = u32be(len(ctx)) ‖ ctx ‖ u32be(len(f1)) ‖ f1 ‖ … ‖ u32be(len(fn)) ‖ fn`.
//! Integers are encoded as fixed-width big-endian fields (with their own length prefix).

/// Builder for a length-prefixed transcript.
#[derive(Debug, Clone, Default)]
pub struct Transcript(Vec<u8>);

impl Transcript {
    /// Starts a transcript with a domain-separation context.
    #[must_use]
    pub fn new(context: &[u8]) -> Self {
        let mut transcript = Self(Vec::with_capacity(160));
        transcript.push(context);
        transcript
    }

    fn push(&mut self, field: &[u8]) {
        // Protocol inputs are bounded to 512 KiB (PROTOCOL.md §9), far below u32::MAX.
        let len = u32::try_from(field.len()).unwrap_or(u32::MAX);
        self.0.extend_from_slice(&len.to_be_bytes());
        self.0.extend_from_slice(field);
    }

    /// Appends a byte field.
    #[must_use]
    pub fn field(mut self, field: &[u8]) -> Self {
        self.push(field);
        self
    }

    /// Appends a `u32` as a 4-byte big-endian field.
    #[must_use]
    pub fn u32(self, value: u32) -> Self {
        self.field(&value.to_be_bytes())
    }

    /// Appends a `u64` as an 8-byte big-endian field.
    #[must_use]
    pub fn u64(self, value: u64) -> Self {
        self.field(&value.to_be_bytes())
    }

    /// Returns the encoded bytes.
    #[must_use]
    pub fn finish(self) -> Vec<u8> {
        self.0
    }
}

#[cfg(test)]
mod tests {
    use super::Transcript;

    #[test]
    fn encoding_is_length_prefixed() {
        let bytes = Transcript::new(b"ctx").field(b"ab").u32(7).finish();
        assert_eq!(
            bytes,
            [
                0, 0, 0, 3, b'c', b't', b'x', 0, 0, 0, 2, b'a', b'b', 0, 0, 0, 4, 0, 0, 0, 7
            ]
        );
    }

    #[test]
    fn field_boundaries_are_unambiguous() {
        let a = Transcript::new(b"c").field(b"ab").field(b"c").finish();
        let b = Transcript::new(b"c").field(b"a").field(b"bc").finish();
        assert_ne!(a, b);
    }
}
