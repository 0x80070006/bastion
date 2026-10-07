// SPDX-License-Identifier: AGPL-3.0-or-later
//! In-memory abuse limits: token-bucket rate limiting and the request nonce cache. Nothing
//! here is persisted or logged.

use std::collections::HashMap;
use std::hash::Hash;
use std::sync::Mutex;

/// Token bucket per key.
#[derive(Debug)]
pub struct RateLimiter<K> {
    buckets: Mutex<HashMap<K, Bucket>>,
    per_second: f64,
    burst: f64,
    max_keys: usize,
}

#[derive(Debug, Clone, Copy)]
struct Bucket {
    tokens: f64,
    updated_ms: i64,
}

impl<K: Eq + Hash + Clone> RateLimiter<K> {
    /// `per_second` sustained requests, `burst` capacity, at most `max_keys` tracked keys.
    #[must_use]
    pub fn new(per_second: u32, burst: u32, max_keys: usize) -> Self {
        Self {
            buckets: Mutex::new(HashMap::new()),
            per_second: f64::from(per_second),
            burst: f64::from(burst),
            max_keys,
        }
    }

    /// Takes one token; `false` when the key is over its rate (or the table is full).
    pub fn check(&self, key: &K, now_ms: i64) -> bool {
        let Ok(mut buckets) = self.buckets.lock() else {
            return false;
        };
        if !buckets.contains_key(key) && buckets.len() >= self.max_keys {
            #[allow(clippy::cast_possible_truncation)]
            let refill_ms = (self.burst / self.per_second * 1000.0) as i64;
            buckets.retain(|_, b| now_ms - b.updated_ms < refill_ms);
            if buckets.len() >= self.max_keys {
                return false;
            }
        }
        let bucket = buckets.entry(key.clone()).or_insert(Bucket {
            tokens: self.burst,
            updated_ms: now_ms,
        });
        #[allow(clippy::cast_precision_loss)]
        let elapsed = (now_ms - bucket.updated_ms).max(0) as f64 / 1000.0;
        bucket.tokens = (bucket.tokens + elapsed * self.per_second).min(self.burst);
        bucket.updated_ms = now_ms;
        if bucket.tokens >= 1.0 {
            bucket.tokens -= 1.0;
            true
        } else {
            false
        }
    }

    /// Drops buckets idle for longer than `idle_ms`.
    pub fn prune(&self, now_ms: i64, idle_ms: i64) {
        if let Ok(mut buckets) = self.buckets.lock() {
            buckets.retain(|_, b| now_ms - b.updated_ms < idle_ms);
        }
    }
}

/// `(device_id, nonce)`.
type NonceKey = ([u8; 16], [u8; 16]);

/// Remembers request nonces for the freshness window so a signed request cannot be replayed.
#[derive(Debug)]
pub struct NonceCache {
    seen: Mutex<HashMap<NonceKey, i64>>,
    retention_ms: i64,
    max_entries: usize,
}

impl NonceCache {
    /// Keeps nonces for `retention_ms` (at least twice the accepted clock skew).
    #[must_use]
    pub fn new(retention_ms: i64, max_entries: usize) -> Self {
        Self {
            seen: Mutex::new(HashMap::new()),
            retention_ms,
            max_entries,
        }
    }

    /// Records `(device, nonce)`; `false` if it was already seen (replay) or the cache is
    /// saturated (fail closed).
    pub fn insert(&self, device: [u8; 16], nonce: [u8; 16], now_ms: i64) -> bool {
        let Ok(mut seen) = self.seen.lock() else {
            return false;
        };
        if seen.len() >= self.max_entries {
            let retention = self.retention_ms;
            seen.retain(|_, at| now_ms - *at < retention);
            if seen.len() >= self.max_entries {
                return false;
            }
        }
        seen.insert((device, nonce), now_ms).is_none()
    }

    /// Forgets nonces older than the retention window.
    pub fn prune(&self, now_ms: i64) {
        if let Ok(mut seen) = self.seen.lock() {
            let retention = self.retention_ms;
            seen.retain(|_, at| now_ms - *at < retention);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bucket_allows_burst_then_refills() {
        let limiter = RateLimiter::new(2, 3, 10);
        for _ in 0..3 {
            assert!(limiter.check(&1, 0));
        }
        assert!(!limiter.check(&1, 0));
        assert!(limiter.check(&2, 0), "keys are independent");
        assert!(limiter.check(&1, 500));
        assert!(!limiter.check(&1, 500));
    }

    #[test]
    fn full_table_fails_closed() {
        let limiter = RateLimiter::new(1, 1, 1);
        assert!(limiter.check(&1, 0));
        assert!(!limiter.check(&2, 0));
        assert!(limiter.check(&2, 10_000), "idle keys are evicted");
    }

    #[test]
    fn nonce_replay_is_detected() {
        let cache = NonceCache::new(60_000, 2);
        assert!(cache.insert([1; 16], [1; 16], 0));
        assert!(!cache.insert([1; 16], [1; 16], 10));
        assert!(cache.insert([2; 16], [1; 16], 10));
        assert!(
            !cache.insert([3; 16], [1; 16], 10),
            "saturated cache fails closed"
        );
        assert!(cache.insert([3; 16], [1; 16], 70_000));
    }
}
