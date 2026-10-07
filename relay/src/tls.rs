// SPDX-License-Identifier: AGPL-3.0-or-later
//! Self-signed TLS identity (ADR-0009). The key is generated once and kept in the data
//! directory; clients pin the SHA-256 of its `SubjectPublicKeyInfo`, so the certificate
//! itself (names, validity) carries no trust.

use std::path::Path;
use std::sync::Arc;

use rcgen::{CertificateParams, KeyPair, PublicKeyData};
use rustls::ServerConfig;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};

/// TLS setup errors.
#[derive(Debug, thiserror::Error)]
pub enum TlsError {
    /// Reading or writing the key material failed.
    #[error("TLS key material I/O: {0}")]
    Io(#[from] std::io::Error),
    /// Certificate generation or key parsing failed.
    #[error("TLS certificate: {0}")]
    Certificate(#[from] rcgen::Error),
    /// rustls rejected the configuration.
    #[error("TLS configuration: {0}")]
    Rustls(#[from] rustls::Error),
}

/// Certificate, key and pin of the relay.
pub struct TlsIdentity {
    cert_der: Vec<u8>,
    key_der: Vec<u8>,
    spki_pin: [u8; 32],
}

impl std::fmt::Debug for TlsIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TlsIdentity")
            .field("spki_pin", &hex::encode(self.spki_pin))
            .finish_non_exhaustive()
    }
}

const KEY_FILE: &str = "tls-key.p8.der";
const CERT_FILE: &str = "tls-cert.der";

impl TlsIdentity {
    /// Loads the identity from `dir`, generating an ECDSA P-256 key on first use.
    ///
    /// # Errors
    /// [`TlsError`].
    pub fn load_or_create(dir: &Path) -> Result<Self, TlsError> {
        std::fs::create_dir_all(dir)?;
        let key_path = dir.join(KEY_FILE);
        let cert_path = dir.join(CERT_FILE);
        if key_path.exists() && cert_path.exists() {
            let key_der = std::fs::read(&key_path)?;
            let cert_der = std::fs::read(&cert_path)?;
            let key = KeyPair::try_from(key_der.as_slice())?;
            return Ok(Self {
                spki_pin: bastion_crypto::spki::pin(&key.subject_public_key_info()),
                cert_der,
                key_der,
            });
        }
        let identity = Self::generate()?;
        write_private(&key_path, &identity.key_der)?;
        std::fs::write(&cert_path, &identity.cert_der)?;
        Ok(identity)
    }

    /// Generates an ephemeral identity (tests, in-memory relays).
    ///
    /// # Errors
    /// [`TlsError`].
    pub fn generate() -> Result<Self, TlsError> {
        let key = KeyPair::generate()?;
        let cert = CertificateParams::new(vec!["bastion-relay".to_owned()])?.self_signed(&key)?;
        Ok(Self {
            spki_pin: bastion_crypto::spki::pin(&key.subject_public_key_info()),
            cert_der: cert.der().to_vec(),
            key_der: key.serialize_der(),
        })
    }

    /// SHA-256 of the DER `SubjectPublicKeyInfo`, transmitted in the pairing QR code.
    #[must_use]
    pub fn spki_pin(&self) -> [u8; 32] {
        self.spki_pin
    }

    /// rustls server configuration: TLS 1.3 only, ring provider, no client auth.
    ///
    /// # Errors
    /// [`TlsError::Rustls`].
    pub fn server_config(&self) -> Result<Arc<ServerConfig>, TlsError> {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let mut config = ServerConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])?
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(self.cert_der.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_der.clone())),
            )?;
        config.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(Arc::new(config))
    }
}

#[cfg(unix)]
fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

#[cfg(not(unix))]
fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    // On Windows the file inherits the ACL of the per-user data directory.
    std::fs::write(path, bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_persists_its_pin() {
        let dir = std::env::temp_dir().join(format!(
            "bastion-tls-{}",
            hex::encode(bastion_crypto::random::bytes::<8>())
        ));
        let first = TlsIdentity::load_or_create(&dir).unwrap();
        let second = TlsIdentity::load_or_create(&dir).unwrap();
        assert_eq!(first.spki_pin(), second.spki_pin());
        assert!(first.server_config().is_ok());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
