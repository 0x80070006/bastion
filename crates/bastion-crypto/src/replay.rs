// SPDX-License-Identifier: GPL-3.0-or-later
//! Sliding anti-replay window over message counters (PROTOCOL.md §6.9), as in IPsec and
//! WireGuard. Bit `i` of the bitmap records whether `highest - i` was accepted.

/// Window size in messages.
pub const WINDOW: u64 = 1024;
const WORDS: usize = 16;
/// Serialized size: `u64be(highest) ‖ 16 × u64be(bitmap word)`.
pub const SERIALIZED_BYTES: usize = 8 + WORDS * 8;

/// Anti-replay state for one direction of one pairing.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ReplayWindow {
    highest: u64,
    bitmap: [u64; WORDS],
}

impl ReplayWindow {
    fn bit(&self, offset: u64) -> bool {
        let (word, bit) = split(offset);
        self.bitmap[word] & (1 << bit) != 0
    }

    fn set(&mut self, offset: u64) {
        let (word, bit) = split(offset);
        self.bitmap[word] |= 1 << bit;
    }

    /// Highest accepted counter (0 when nothing was accepted).
    #[must_use]
    pub fn highest(&self) -> u64 {
        self.highest
    }

    /// Whether `counter` would be accepted. Counter 0 is never valid.
    #[must_use]
    pub fn check(&self, counter: u64) -> bool {
        if counter == 0 {
            return false;
        }
        if counter > self.highest {
            return true;
        }
        let offset = self.highest - counter;
        offset < WINDOW && !self.bit(offset)
    }

    /// Records `counter`; returns `false` (and changes nothing) if it is a replay or too old.
    pub fn accept(&mut self, counter: u64) -> bool {
        if !self.check(counter) {
            return false;
        }
        if counter > self.highest {
            let shift = counter - self.highest;
            let mut shifted = Self {
                highest: counter,
                bitmap: [0; WORDS],
            };
            if shift < WINDOW {
                for offset in 0..WINDOW - shift {
                    if self.bit(offset) {
                        shifted.set(offset + shift);
                    }
                }
            }
            *self = shifted;
            self.set(0);
        } else {
            self.set(self.highest - counter);
        }
        true
    }

    /// Serializes the window for persistence.
    #[must_use]
    pub fn to_bytes(&self) -> [u8; SERIALIZED_BYTES] {
        let mut out = [0u8; SERIALIZED_BYTES];
        out[..8].copy_from_slice(&self.highest.to_be_bytes());
        for (i, word) in self.bitmap.iter().enumerate() {
            out[8 + i * 8..16 + i * 8].copy_from_slice(&word.to_be_bytes());
        }
        out
    }

    /// Restores a window; returns `None` for a wrong length.
    #[must_use]
    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != SERIALIZED_BYTES {
            return None;
        }
        let word = |i: usize| {
            let mut buf = [0u8; 8];
            buf.copy_from_slice(&bytes[i..i + 8]);
            u64::from_be_bytes(buf)
        };
        let mut window = Self {
            highest: word(0),
            bitmap: [0; WORDS],
        };
        for (i, slot) in window.bitmap.iter_mut().enumerate() {
            *slot = word(8 + i * 8);
        }
        Some(window)
    }
}

fn split(offset: u64) -> (usize, u32) {
    // offset < WINDOW = 1024, so both conversions are lossless.
    let word = usize::try_from(offset / 64).unwrap_or(0);
    let bit = u32::try_from(offset % 64).unwrap_or(0);
    (word, bit)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_replay_is_rejected() {
        let mut w = ReplayWindow::default();
        assert!(!w.accept(0));
        assert!(w.accept(1));
        assert!(!w.accept(1));
        assert!(w.accept(5));
        assert!(w.accept(3));
        assert!(!w.accept(3));
        assert!(!w.accept(5));
        assert!(w.accept(2));
    }

    #[test]
    fn out_of_window_is_rejected() {
        let mut w = ReplayWindow::default();
        assert!(w.accept(2000));
        assert!(!w.accept(2000 - WINDOW));
        assert!(w.accept(2000 - WINDOW + 1));
        assert!(w.accept(2000 + WINDOW + 5));
        assert!(!w.accept(2000));
    }

    #[test]
    fn shifting_preserves_history() {
        let mut w = ReplayWindow::default();
        for c in [10, 12, 14] {
            assert!(w.accept(c));
        }
        assert!(w.accept(100));
        for c in [10, 12, 14, 100] {
            assert!(!w.check(c));
        }
        for c in [11, 13, 99] {
            assert!(w.check(c));
        }
    }

    #[test]
    fn serialization_round_trips() {
        let mut w = ReplayWindow::default();
        for c in [3, 70, 71, 500, 1100] {
            w.accept(c);
        }
        assert_eq!(ReplayWindow::from_bytes(&w.to_bytes()), Some(w));
        assert_eq!(ReplayWindow::from_bytes(&[0; 3]), None);
    }
}
