// SPDX-License-Identifier: AGPL-3.0-or-later
//! SQLite storage: peers, controller↔phone links, hashed invitations and admin tokens,
//! mailboxes with TTL. The relay stores only public keys and opaque blobs.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use rusqlite::{Connection, OptionalExtension, Transaction, params};

use bastion_crypto::identity::DeviceId;

/// Storage errors.
#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    /// Underlying SQLite failure.
    #[error("storage failure: {0}")]
    Sqlite(#[from] rusqlite::Error),
    /// A quota (invitations, mailbox size) would be exceeded.
    #[error("quota exceeded")]
    QuotaExceeded,
    /// The device is already registered with another role.
    #[error("role conflict")]
    RoleConflict,
    /// The storage mutex was poisoned by a panicking thread.
    #[error("storage lock poisoned")]
    Poisoned,
}

/// Device role, numerically identical to `bastion.v1.Role`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Phone agent.
    Phone,
    /// Desktop controller.
    Controller,
}

impl Role {
    fn to_i64(self) -> i64 {
        match self {
            Self::Phone => 1,
            Self::Controller => 2,
        }
    }

    fn from_i64(value: i64) -> Option<Self> {
        match value {
            1 => Some(Self::Phone),
            2 => Some(Self::Controller),
            _ => None,
        }
    }
}

/// A registered device.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Peer {
    /// `BLAKE2b-128(identity)`.
    pub device_id: DeviceId,
    /// Role fixed at enrollment.
    pub role: Role,
    /// Ed25519 identity public key, used to authenticate requests.
    pub identity: [u8; 32],
}

/// Mailbox item kind, numerically identical to `bastion.v1.MailboxItem.Kind`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ItemKind {
    /// Serialized `Envelope`.
    Envelope = 1,
    /// Sealed `PairingHello`.
    PairingHello = 2,
}

/// A stored mailbox item.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Item {
    /// Relay-assigned identifier.
    pub id: u64,
    /// Kind (1 = envelope, 2 = pairing hello).
    pub kind: i32,
    /// Depositing device.
    pub sender: DeviceId,
    /// Opaque payload.
    pub payload: Vec<u8>,
    /// Relay clock at reception.
    pub received_ms: i64,
}

/// Mailbox quotas.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MailboxQuota {
    /// Maximum number of pending items per recipient.
    pub max_items: i64,
    /// Maximum total payload bytes per recipient.
    pub max_bytes: i64,
}

impl MailboxQuota {
    /// Default quota: 512 items or 32 MiB per recipient.
    pub const DEFAULT: Self = Self {
        max_items: 512,
        max_bytes: 32 * 1024 * 1024,
    };
}

const SCHEMA_VERSION: i64 = 1;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS peers (
    device_id BLOB PRIMARY KEY,
    role INTEGER NOT NULL,
    identity BLOB NOT NULL,
    created_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS links (
    controller_id BLOB NOT NULL,
    phone_id BLOB NOT NULL,
    created_ms INTEGER NOT NULL,
    PRIMARY KEY (controller_id, phone_id)
);
CREATE INDEX IF NOT EXISTS links_phone ON links (phone_id);
CREATE TABLE IF NOT EXISTS invites (
    token_hash BLOB PRIMARY KEY,
    controller_id BLOB NOT NULL,
    expires_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS admin_tokens (
    token_hash BLOB PRIMARY KEY,
    expires_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS mailbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recipient BLOB NOT NULL,
    sender BLOB NOT NULL,
    kind INTEGER NOT NULL,
    payload BLOB NOT NULL,
    received_ms INTEGER NOT NULL,
    expires_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS mailbox_recipient ON mailbox (recipient, id);
CREATE INDEX IF NOT EXISTS mailbox_expiry ON mailbox (expires_ms);
";

/// Thread-safe SQLite store. Queries are short; a single connection behind a mutex is
/// enough for a personal relay.
#[derive(Debug)]
pub struct Store {
    conn: Mutex<Connection>,
}

impl Store {
    /// Opens (and migrates) the database at `path`.
    ///
    /// # Errors
    /// [`StoreError::Sqlite`].
    pub fn open(path: &Path) -> Result<Self, StoreError> {
        Self::init(Connection::open(path)?)
    }

    /// In-memory database (tests, ephemeral relays).
    ///
    /// # Errors
    /// [`StoreError::Sqlite`].
    pub fn in_memory() -> Result<Self, StoreError> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> Result<Self, StoreError> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "secure_delete", "ON")?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.execute_batch(SCHEMA)?;
        conn.pragma_update(None, "user_version", SCHEMA_VERSION)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    fn conn(&self) -> Result<MutexGuard<'_, Connection>, StoreError> {
        self.conn.lock().map_err(|_| StoreError::Poisoned)
    }

    /// Looks up a peer.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn peer(&self, device_id: &DeviceId) -> Result<Option<Peer>, StoreError> {
        let conn = self.conn()?;
        let row = conn
            .query_row(
                "SELECT role, identity FROM peers WHERE device_id = ?1",
                params![device_id.as_slice()],
                |row| Ok((row.get::<_, i64>(0)?, row.get::<_, Vec<u8>>(1)?)),
            )
            .optional()?;
        Ok(row.and_then(|(role, identity)| {
            Some(Peer {
                device_id: *device_id,
                role: Role::from_i64(role)?,
                identity: identity.try_into().ok()?,
            })
        }))
    }

    fn upsert_peer(tx: &Transaction<'_>, peer: &Peer, now_ms: i64) -> Result<(), StoreError> {
        let existing: Option<i64> = tx
            .query_row(
                "SELECT role FROM peers WHERE device_id = ?1",
                params![peer.device_id.as_slice()],
                |row| row.get(0),
            )
            .optional()?;
        match existing {
            Some(role) if role != peer.role.to_i64() => Err(StoreError::RoleConflict),
            Some(_) => Ok(()),
            None => {
                tx.execute(
                    "INSERT INTO peers (device_id, role, identity, created_ms) VALUES (?1, ?2, ?3, ?4)",
                    params![
                        peer.device_id.as_slice(),
                        peer.role.to_i64(),
                        peer.identity.as_slice(),
                        now_ms
                    ],
                )?;
                Ok(())
            }
        }
    }

    /// Registers a controller.
    ///
    /// # Errors
    /// [`StoreError::RoleConflict`] if the device is already a phone.
    pub fn register_controller(&self, peer: &Peer, now_ms: i64) -> Result<(), StoreError> {
        let mut conn = self.conn()?;
        let tx = conn.transaction()?;
        Self::upsert_peer(&tx, peer, now_ms)?;
        tx.commit()?;
        Ok(())
    }

    /// Whether a controller↔phone link exists between `a` and `b` (either order).
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn is_linked(&self, a: &DeviceId, b: &DeviceId) -> Result<bool, StoreError> {
        let conn = self.conn()?;
        Ok(conn
            .query_row(
                "SELECT 1 FROM links WHERE (controller_id = ?1 AND phone_id = ?2)
                 OR (controller_id = ?2 AND phone_id = ?1)",
                params![a.as_slice(), b.as_slice()],
                |_| Ok(()),
            )
            .optional()?
            .is_some())
    }

    /// Records an invitation (`token_hash` only) for a controller.
    ///
    /// # Errors
    /// [`StoreError::QuotaExceeded`] beyond `max_active` pending invitations.
    pub fn create_invite(
        &self,
        token_hash: &[u8; 32],
        controller: &DeviceId,
        expires_ms: i64,
        now_ms: i64,
        max_active: i64,
    ) -> Result<(), StoreError> {
        let mut conn = self.conn()?;
        let tx = conn.transaction()?;
        let active: i64 = tx.query_row(
            "SELECT COUNT(*) FROM invites WHERE controller_id = ?1 AND expires_ms > ?2",
            params![controller.as_slice(), now_ms],
            |row| row.get(0),
        )?;
        if active >= max_active {
            return Err(StoreError::QuotaExceeded);
        }
        tx.execute(
            "INSERT OR REPLACE INTO invites (token_hash, controller_id, expires_ms) VALUES (?1, ?2, ?3)",
            params![token_hash.as_slice(), controller.as_slice(), expires_ms],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// Enrolls a phone: consumes the invitation (single use, deleted whatever happens next),
    /// registers the phone, links it to the inviting controller and deposits the sealed hello
    /// in the controller mailbox — atomically. Returns the controller id, or `None` if the
    /// invitation is unknown or expired.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn enroll_phone(
        &self,
        token_hash: &[u8; 32],
        phone: &Peer,
        sealed_hello: &[u8],
        now_ms: i64,
        ttl_ms: i64,
    ) -> Result<Option<DeviceId>, StoreError> {
        let mut conn = self.conn()?;
        let consumed: Option<(Vec<u8>, i64)> = conn
            .query_row(
                "DELETE FROM invites WHERE token_hash = ?1 RETURNING controller_id, expires_ms",
                params![token_hash.as_slice()],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?;
        let Some((controller, expires_ms)) = consumed else {
            return Ok(None);
        };
        let Ok(controller) = DeviceId::try_from(controller.as_slice()) else {
            return Ok(None);
        };
        if expires_ms <= now_ms {
            return Ok(None);
        }
        let tx = conn.transaction()?;
        Self::upsert_peer(&tx, phone, now_ms)?;
        tx.execute(
            "INSERT OR IGNORE INTO links (controller_id, phone_id, created_ms) VALUES (?1, ?2, ?3)",
            params![controller.as_slice(), phone.device_id.as_slice(), now_ms],
        )?;
        tx.execute(
            "INSERT INTO mailbox (recipient, sender, kind, payload, received_ms, expires_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                controller.as_slice(),
                phone.device_id.as_slice(),
                ItemKind::PairingHello as i64,
                sealed_hello,
                now_ms,
                now_ms + ttl_ms
            ],
        )?;
        tx.commit()?;
        Ok(Some(controller))
    }

    /// Stores the hash of a single-use administration token.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn create_admin_token(
        &self,
        token_hash: &[u8; 32],
        expires_ms: i64,
    ) -> Result<(), StoreError> {
        self.conn()?.execute(
            "INSERT OR REPLACE INTO admin_tokens (token_hash, expires_ms) VALUES (?1, ?2)",
            params![token_hash.as_slice(), expires_ms],
        )?;
        Ok(())
    }

    /// Consumes an administration token; `true` if it existed and had not expired.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn consume_admin_token(
        &self,
        token_hash: &[u8; 32],
        now_ms: i64,
    ) -> Result<bool, StoreError> {
        let expires: Option<i64> = self
            .conn()?
            .query_row(
                "DELETE FROM admin_tokens WHERE token_hash = ?1 RETURNING expires_ms",
                params![token_hash.as_slice()],
                |row| row.get(0),
            )
            .optional()?;
        Ok(expires.is_some_and(|e| e > now_ms))
    }

    /// Deposits an envelope.
    ///
    /// # Errors
    /// [`StoreError::QuotaExceeded`] when the recipient mailbox is full.
    pub fn put_envelope(
        &self,
        recipient: &DeviceId,
        sender: &DeviceId,
        payload: &[u8],
        now_ms: i64,
        ttl_ms: i64,
        quota: MailboxQuota,
    ) -> Result<u64, StoreError> {
        let mut conn = self.conn()?;
        let tx = conn.transaction()?;
        let (count, bytes): (i64, i64) = tx.query_row(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(payload)), 0) FROM mailbox WHERE recipient = ?1",
            params![recipient.as_slice()],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let size = i64::try_from(payload.len()).unwrap_or(i64::MAX);
        if count >= quota.max_items || bytes.saturating_add(size) > quota.max_bytes {
            return Err(StoreError::QuotaExceeded);
        }
        tx.execute(
            "INSERT INTO mailbox (recipient, sender, kind, payload, received_ms, expires_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                recipient.as_slice(),
                sender.as_slice(),
                ItemKind::Envelope as i64,
                payload,
                now_ms,
                now_ms + ttl_ms
            ],
        )?;
        let id = tx.last_insert_rowid();
        tx.commit()?;
        Ok(u64::try_from(id).unwrap_or_default())
    }

    /// Oldest pending items of a mailbox.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn fetch(
        &self,
        recipient: &DeviceId,
        now_ms: i64,
        limit: u32,
    ) -> Result<Vec<Item>, StoreError> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare_cached(
            "SELECT id, kind, sender, payload, received_ms FROM mailbox
             WHERE recipient = ?1 AND expires_ms > ?2 ORDER BY id LIMIT ?3",
        )?;
        let rows = stmt.query_map(params![recipient.as_slice(), now_ms, limit], |row| {
            Ok((
                row.get::<_, i64>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, Vec<u8>>(2)?,
                row.get::<_, Vec<u8>>(3)?,
                row.get::<_, i64>(4)?,
            ))
        })?;
        let mut items = Vec::new();
        for row in rows {
            let (id, kind, sender, payload, received_ms) = row?;
            let (Ok(id), Ok(kind), Ok(sender)) = (
                u64::try_from(id),
                i32::try_from(kind),
                DeviceId::try_from(sender.as_slice()),
            ) else {
                continue;
            };
            items.push(Item {
                id,
                kind,
                sender,
                payload,
                received_ms,
            });
        }
        Ok(items)
    }

    /// Deletes acknowledged items of the caller mailbox; returns how many were removed.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn ack(&self, recipient: &DeviceId, ids: &[u64]) -> Result<usize, StoreError> {
        let mut conn = self.conn()?;
        let tx = conn.transaction()?;
        let mut removed = 0;
        {
            let mut stmt =
                tx.prepare_cached("DELETE FROM mailbox WHERE recipient = ?1 AND id = ?2")?;
            for id in ids {
                let Ok(id) = i64::try_from(*id) else { continue };
                removed += stmt.execute(params![recipient.as_slice(), id])?;
            }
        }
        tx.commit()?;
        Ok(removed)
    }

    /// Removes the link between `caller` and `target` and their pending messages to each
    /// other. A phone left without any controller is deleted entirely. When `caller ==
    /// target`, the caller unregisters itself. Returns `false` if `caller` may not do this.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn revoke(&self, caller: &DeviceId, target: &DeviceId) -> Result<bool, StoreError> {
        let mut conn = self.conn()?;
        let tx = conn.transaction()?;
        if caller == target {
            Self::delete_peer(&tx, caller)?;
            tx.commit()?;
            return Ok(true);
        }
        let removed = tx.execute(
            "DELETE FROM links WHERE (controller_id = ?1 AND phone_id = ?2)
             OR (controller_id = ?2 AND phone_id = ?1)",
            params![caller.as_slice(), target.as_slice()],
        )?;
        if removed == 0 {
            return Ok(false);
        }
        tx.execute(
            "DELETE FROM mailbox WHERE (recipient = ?1 AND sender = ?2) OR (recipient = ?2 AND sender = ?1)",
            params![caller.as_slice(), target.as_slice()],
        )?;
        for phone in [caller, target] {
            let orphan: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM peers WHERE device_id = ?1 AND role = 1)
                 AND NOT EXISTS(SELECT 1 FROM links WHERE phone_id = ?1)",
                params![phone.as_slice()],
                |row| row.get(0),
            )?;
            if orphan {
                Self::delete_peer(&tx, phone)?;
            }
        }
        tx.commit()?;
        Ok(true)
    }

    fn delete_peer(tx: &Transaction<'_>, id: &DeviceId) -> Result<(), StoreError> {
        let id = id.as_slice();
        tx.execute(
            "DELETE FROM links WHERE controller_id = ?1 OR phone_id = ?1",
            params![id],
        )?;
        tx.execute("DELETE FROM mailbox WHERE recipient = ?1", params![id])?;
        tx.execute("DELETE FROM invites WHERE controller_id = ?1", params![id])?;
        tx.execute("DELETE FROM peers WHERE device_id = ?1", params![id])?;
        Ok(())
    }

    /// Deletes expired invitations, administration tokens and mailbox items.
    ///
    /// # Errors
    /// [`StoreError`].
    pub fn prune(&self, now_ms: i64) -> Result<usize, StoreError> {
        let conn = self.conn()?;
        let mut removed = conn.execute(
            "DELETE FROM invites WHERE expires_ms <= ?1",
            params![now_ms],
        )?;
        removed += conn.execute(
            "DELETE FROM admin_tokens WHERE expires_ms <= ?1",
            params![now_ms],
        )?;
        removed += conn.execute(
            "DELETE FROM mailbox WHERE expires_ms <= ?1",
            params![now_ms],
        )?;
        Ok(removed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(id: u8, role: Role) -> Peer {
        Peer {
            device_id: [id; 16],
            role,
            identity: [id; 32],
        }
    }

    #[test]
    fn invitation_is_single_use_and_links_devices() {
        let store = Store::in_memory().unwrap();
        let pc = peer(1, Role::Controller);
        store.register_controller(&pc, 0).unwrap();
        store
            .create_invite(&[7; 32], &pc.device_id, 1_000, 0, 8)
            .unwrap();
        let phone = peer(2, Role::Phone);
        assert_eq!(
            store
                .enroll_phone(&[7; 32], &phone, b"hello", 10, 100)
                .unwrap(),
            Some(pc.device_id)
        );
        assert_eq!(
            store
                .enroll_phone(&[7; 32], &phone, b"hello", 10, 100)
                .unwrap(),
            None
        );
        assert!(store.is_linked(&pc.device_id, &phone.device_id).unwrap());
        assert!(store.is_linked(&phone.device_id, &pc.device_id).unwrap());
        let items = store.fetch(&pc.device_id, 10, 64).unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].kind, ItemKind::PairingHello as i32);
        assert_eq!(items[0].payload, b"hello");
    }

    #[test]
    fn expired_invitation_is_refused_and_still_consumed() {
        let store = Store::in_memory().unwrap();
        store.create_invite(&[7; 32], &[1; 16], 100, 0, 8).unwrap();
        assert_eq!(
            store
                .enroll_phone(&[7; 32], &peer(2, Role::Phone), b"h", 100, 10)
                .unwrap(),
            None
        );
        store
            .create_invite(&[8; 32], &[1; 16], 1_000, 0, 8)
            .unwrap();
        assert!(store.peer(&[2; 16]).unwrap().is_none());
    }

    #[test]
    fn invite_quota() {
        let store = Store::in_memory().unwrap();
        for i in 0..2u8 {
            store
                .create_invite(&[i; 32], &[1; 16], 1_000, 0, 2)
                .unwrap();
        }
        assert!(matches!(
            store.create_invite(&[9; 32], &[1; 16], 1_000, 0, 2),
            Err(StoreError::QuotaExceeded)
        ));
    }

    #[test]
    fn mailbox_quota_ack_and_expiry() {
        let store = Store::in_memory().unwrap();
        let quota = MailboxQuota {
            max_items: 2,
            max_bytes: 10,
        };
        let (a, b) = ([1; 16], [2; 16]);
        let first = store.put_envelope(&b, &a, b"1234", 0, 100, quota).unwrap();
        store.put_envelope(&b, &a, b"5678", 0, 50, quota).unwrap();
        assert!(matches!(
            store.put_envelope(&b, &a, b"9", 0, 100, quota),
            Err(StoreError::QuotaExceeded)
        ));
        assert_eq!(store.fetch(&b, 60, 64).unwrap().len(), 1);
        assert_eq!(
            store.ack(&a, &[first]).unwrap(),
            0,
            "only the recipient may ack"
        );
        assert_eq!(store.ack(&b, &[first]).unwrap(), 1);
        assert_eq!(store.prune(60).unwrap(), 1);
        assert_eq!(store.fetch(&b, 0, 64).unwrap(), Vec::new());
    }

    #[test]
    fn revoke_removes_link_and_orphan_phone() {
        let store = Store::in_memory().unwrap();
        let pc = peer(1, Role::Controller);
        let phone = peer(2, Role::Phone);
        store.register_controller(&pc, 0).unwrap();
        store
            .create_invite(&[7; 32], &pc.device_id, 1_000, 0, 8)
            .unwrap();
        store.enroll_phone(&[7; 32], &phone, b"h", 1, 100).unwrap();
        assert!(!store.revoke(&[9; 16], &phone.device_id).unwrap());
        assert!(store.revoke(&pc.device_id, &phone.device_id).unwrap());
        assert!(!store.is_linked(&pc.device_id, &phone.device_id).unwrap());
        assert!(store.peer(&phone.device_id).unwrap().is_none());
        assert!(store.peer(&pc.device_id).unwrap().is_some());
        assert_eq!(store.fetch(&pc.device_id, 1, 64).unwrap(), Vec::new());
    }

    #[test]
    fn roles_cannot_change() {
        let store = Store::in_memory().unwrap();
        let pc = peer(1, Role::Controller);
        store.register_controller(&pc, 0).unwrap();
        store
            .create_invite(&[7; 32], &[3; 16], 1_000, 0, 8)
            .unwrap();
        let as_phone = Peer {
            role: Role::Phone,
            ..pc
        };
        assert!(matches!(
            store.enroll_phone(&[7; 32], &as_phone, b"h", 1, 100),
            Err(StoreError::RoleConflict)
        ));
    }

    #[test]
    fn admin_tokens_are_single_use() {
        let store = Store::in_memory().unwrap();
        store.create_admin_token(&[5; 32], 100).unwrap();
        assert!(!store.consume_admin_token(&[6; 32], 0).unwrap());
        assert!(store.consume_admin_token(&[5; 32], 0).unwrap());
        assert!(!store.consume_admin_token(&[5; 32], 0).unwrap());
        store.create_admin_token(&[5; 32], 100).unwrap();
        assert!(!store.consume_admin_token(&[5; 32], 100).unwrap());
    }
}
