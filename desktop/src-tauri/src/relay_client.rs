// SPDX-License-Identifier: GPL-3.0-or-later
//! HTTPS client for the relay API: TLS 1.3 with SPKI pinning (ADR-0009), every request
//! signed with the identity key (PROTOCOL.md §8).

use std::sync::Arc;
use std::time::Duration;

use bastion_crypto::identity::{DeviceId, SigningKeypair};
use bastion_crypto::request_auth;
use bastion_proto::v1::{
    CreateInviteRequest, EnrollRequest, EnrollResponse, MailboxAck, MailboxBatch, Role,
};
use prost::Message;
use reqwest::{Method, StatusCode};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{CertificateError, DigitallySignedStruct, SignatureScheme};

use crate::error::{AppError, AppResult};

/// Accepts exactly one server key: the pinned `SubjectPublicKeyInfo`. Handshake signatures
/// are still verified, so presenting the certificate without its key fails.
#[derive(Debug)]
struct SpkiPinVerifier {
    pin: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for SpkiPinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let (_, cert) = x509_parser::parse_x509_certificate(end_entity)
            .map_err(|_| rustls::Error::InvalidCertificate(CertificateError::BadEncoding))?;
        if bastion_crypto::spki::matches(&self.pin, cert.tbs_certificate.subject_pki.raw) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Builds a TLS 1.3 client configuration trusting only `pin`.
///
/// # Errors
/// [`AppError::Internal`].
pub fn pinned_tls(pin: [u8; 32]) -> AppResult<rustls::ClientConfig> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(Arc::clone(&provider))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|_| AppError::Internal)?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(SpkiPinVerifier { pin, provider }))
        .with_no_client_auth();
    Ok(config)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
}

/// Signed relay client.
#[derive(Clone)]
pub struct RelayClient {
    http: reqwest::Client,
    base: String,
    identity: Arc<SigningKeypair>,
}

impl std::fmt::Debug for RelayClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RelayClient")
            .field("base", &self.base)
            .finish_non_exhaustive()
    }
}

fn is_certificate_error(error: &(dyn std::error::Error + 'static)) -> bool {
    if matches!(
        error.downcast_ref::<rustls::Error>(),
        Some(rustls::Error::InvalidCertificate(_))
    ) {
        return true;
    }
    // rustls errors arrive wrapped in (possibly nested) io::Errors whose `source()` skips
    // the payload.
    if let Some(inner) = error
        .downcast_ref::<std::io::Error>()
        .and_then(std::io::Error::get_ref)
        && is_certificate_error(inner)
    {
        return true;
    }
    error.source().is_some_and(is_certificate_error)
}

fn map_transport(error: &reqwest::Error) -> AppError {
    if is_certificate_error(error) {
        AppError::RelayPinMismatch
    } else {
        AppError::RelayUnreachable
    }
}

impl RelayClient {
    /// Client for `base` (e.g. `https://127.0.0.1:8443`) pinned to `pin`.
    ///
    /// # Errors
    /// [`AppError::InvalidInput`] for a non-HTTPS URL, [`AppError::Internal`].
    pub fn new(base: &str, pin: [u8; 32], identity: Arc<SigningKeypair>) -> AppResult<Self> {
        let base = base.trim_end_matches('/').to_owned();
        let url = reqwest::Url::parse(&base).map_err(|_| AppError::InvalidInput)?;
        if url.scheme() != "https" || url.host_str().is_none() || url.path() != "/" {
            return Err(AppError::InvalidInput);
        }
        let http = reqwest::Client::builder()
            .use_preconfigured_tls(pinned_tls(pin)?)
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(45))
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|_| AppError::Internal)?;
        Ok(Self {
            http,
            base,
            identity,
        })
    }

    async fn call(
        &self,
        method: Method,
        path: &str,
        body: Vec<u8>,
        extra: Option<(&str, &str)>,
        signed: bool,
    ) -> AppResult<(StatusCode, bytes_body::Body)> {
        let mut request = self
            .http
            .request(method.clone(), format!("{}{path}", self.base))
            .header(reqwest::header::CONTENT_TYPE, "application/x-protobuf");
        if signed {
            let auth = request_auth::sign(&self.identity, method.as_str(), path, now_ms(), &body);
            request = request.header(request_auth::HEADER, auth);
        }
        if let Some((name, value)) = extra {
            request = request.header(name, value);
        }
        let response = request
            .body(body)
            .send()
            .await
            .map_err(|e| map_transport(&e))?;
        let status = response.status();
        let bytes = response.bytes().await.map_err(|e| map_transport(&e))?;
        Ok((status, bytes_body::Body(bytes.to_vec())))
    }

    fn expect(status: StatusCode, wanted: StatusCode) -> AppResult<()> {
        if status == wanted {
            Ok(())
        } else {
            tracing::warn!(%status, "relay refused request");
            Err(AppError::RelayRefused)
        }
    }

    /// Checks reachability and the TLS pin.
    ///
    /// # Errors
    /// [`AppError::RelayUnreachable`], [`AppError::RelayPinMismatch`].
    pub async fn health(&self) -> AppResult<()> {
        let (status, _) = self
            .call(Method::GET, "/v1/health", Vec::new(), None, false)
            .await?;
        Self::expect(status, StatusCode::OK)
    }

    /// Registers this controller with a single-use administration token.
    ///
    /// # Errors
    /// Relay errors.
    pub async fn enroll_controller(
        &self,
        keys: &bastion_crypto::identity::DeviceKeys,
        admin_token: &str,
    ) -> AppResult<()> {
        let role = Role::Controller as i32;
        let transcript = bastion_crypto::pairing::enroll_transcript(&[], keys, &[], &[], role);
        let request = EnrollRequest {
            device: Some(keys.to_proto()),
            role,
            signature: self.identity.sign(&transcript).to_vec(),
            ..EnrollRequest::default()
        };
        let (status, body) = self
            .call(
                Method::POST,
                "/v1/enroll",
                request.encode_to_vec(),
                Some((bastion_relay::routes::ADMIN_TOKEN_HEADER, admin_token)),
                false,
            )
            .await?;
        Self::expect(status, StatusCode::CREATED)?;
        let response =
            EnrollResponse::decode(body.0.as_slice()).map_err(|_| AppError::RelayRefused)?;
        if response.device_id != self.identity.device_id() {
            return Err(AppError::RelayRefused);
        }
        Ok(())
    }

    /// Registers an invitation hash.
    ///
    /// # Errors
    /// Relay errors.
    pub async fn create_invite(&self, token_hash: [u8; 32], expires_at_ms: i64) -> AppResult<()> {
        let request = CreateInviteRequest {
            token_hash: token_hash.to_vec(),
            expires_at_ms,
        };
        let (status, _) = self
            .call(
                Method::POST,
                "/v1/invites",
                request.encode_to_vec(),
                None,
                true,
            )
            .await?;
        Self::expect(status, StatusCode::CREATED)
    }

    /// Long-polls the mailbox for at most `wait_seconds`.
    ///
    /// # Errors
    /// Relay errors.
    pub async fn fetch(&self, wait_seconds: u32) -> AppResult<MailboxBatch> {
        let path = if wait_seconds == 0 {
            "/v1/mailbox".to_owned()
        } else {
            format!("/v1/mailbox?wait={wait_seconds}")
        };
        let (status, body) = self
            .call(Method::GET, &path, Vec::new(), None, true)
            .await?;
        Self::expect(status, StatusCode::OK)?;
        MailboxBatch::decode(body.0.as_slice()).map_err(|_| AppError::RelayRefused)
    }

    /// Acknowledges (deletes) processed items.
    ///
    /// # Errors
    /// Relay errors.
    pub async fn ack(&self, ids: Vec<u64>) -> AppResult<()> {
        if ids.is_empty() {
            return Ok(());
        }
        let (status, _) = self
            .call(
                Method::POST,
                "/v1/mailbox/ack",
                MailboxAck { ids }.encode_to_vec(),
                None,
                true,
            )
            .await?;
        Self::expect(status, StatusCode::NO_CONTENT)
    }

    /// Deposits an envelope.
    ///
    /// # Errors
    /// Relay errors.
    pub async fn send(&self, recipient: &DeviceId, envelope: Vec<u8>) -> AppResult<()> {
        let path = format!("/v1/mailbox/{}", hex::encode(recipient));
        let (status, _) = self.call(Method::PUT, &path, envelope, None, true).await?;
        Self::expect(status, StatusCode::CREATED)
    }

    /// Removes the link with a device.
    ///
    /// # Errors
    /// Relay errors.
    pub async fn revoke(&self, device: &DeviceId) -> AppResult<()> {
        let path = format!("/v1/peers/{}", hex::encode(device));
        let (status, _) = self
            .call(Method::DELETE, &path, Vec::new(), None, true)
            .await?;
        // Already gone is fine.
        if status == StatusCode::FORBIDDEN {
            return Ok(());
        }
        Self::expect(status, StatusCode::NO_CONTENT)
    }
}

mod bytes_body {
    /// Response body bytes.
    #[derive(Debug)]
    pub struct Body(pub Vec<u8>);
}

#[cfg(test)]
mod tests {
    use super::*;
    use bastion_crypto::identity::ExchangeKeypair;
    use bastion_relay::{Config, Relay};

    async fn start_relay(dir: &std::path::Path) -> (String, [u8; 32], Relay) {
        let config = Config {
            listen_addr: "127.0.0.1:0".parse().unwrap(),
            access_log: false,
            data_dir: dir.to_path_buf(),
            tls: true,
        };
        let relay = Relay::open(&config).unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let serving = relay.clone();
        tokio::spawn(async move {
            let _ = serving.serve(listener, std::future::pending()).await;
        });
        tokio::time::sleep(Duration::from_millis(100)).await;
        (format!("https://{addr}"), relay.spki_pin().unwrap(), relay)
    }

    #[tokio::test]
    async fn pinned_tls_round_trip_and_mismatch() {
        let dir = tempfile::tempdir().unwrap();
        let (base, pin, relay) = start_relay(dir.path()).await;
        let identity = Arc::new(SigningKeypair::generate());
        let client = RelayClient::new(&base, pin, Arc::clone(&identity)).unwrap();
        client.health().await.unwrap();

        let keys = ExchangeKeypair::generate(0).signed_by(&identity);
        let token = relay.create_admin_token(Duration::from_secs(60)).unwrap();
        client.enroll_controller(&keys, &token).await.unwrap();
        assert_eq!(client.fetch(0).await.unwrap().items, Vec::new());

        let wrong = RelayClient::new(&base, [0; 32], identity).unwrap();
        assert_eq!(wrong.health().await, Err(AppError::RelayPinMismatch));
    }

    #[test]
    fn only_https_base_urls_are_accepted() {
        let id = Arc::new(SigningKeypair::generate());
        assert!(RelayClient::new("http://relay:8443", [0; 32], Arc::clone(&id)).is_err());
        assert!(RelayClient::new("https://relay:8443/x", [0; 32], Arc::clone(&id)).is_err());
        assert!(RelayClient::new("https://relay:8443/", [0; 32], id).is_ok());
    }
}
