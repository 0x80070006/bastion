// SPDX-License-Identifier: AGPL-3.0-or-later
//! End-to-end API tests: enrollment, mailbox, authentication and abuse cases.
#![allow(clippy::unwrap_used, clippy::unused_self)]

use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};
use std::time::Duration;

use axum::Router;
use axum::body::Body;
use axum::http::{Request, StatusCode};
use http_body_util::BodyExt;
use prost::Message;
use tower::ServiceExt;

use bastion_crypto::envelope::{self, Header};
use bastion_crypto::identity::{ExchangeKeypair, SigningKeypair};
use bastion_crypto::session::SymmetricKey;
use bastion_crypto::{pairing, request_auth, sealed};
use bastion_proto::v1::{
    CreateInviteRequest, EnrollRequest, EnrollResponse, MailboxAck, MailboxBatch, RelayError, Role,
};
use bastion_relay::Relay;
use bastion_relay::routes::ADMIN_TOKEN_HEADER;

const START: i64 = 1_760_000_000_000;

struct Device {
    ik: SigningKeypair,
    xk: ExchangeKeypair,
}

impl Device {
    fn new() -> Self {
        Self {
            ik: SigningKeypair::generate(),
            xk: ExchangeKeypair::generate(0),
        }
    }
}

struct Harness {
    relay: Relay,
    clock: Arc<AtomicI64>,
}

impl Harness {
    fn new() -> Self {
        let clock = Arc::new(AtomicI64::new(START));
        let reader = Arc::clone(&clock);
        Self {
            relay: Relay::ephemeral(Arc::new(move || reader.load(Ordering::SeqCst))).unwrap(),
            clock,
        }
    }

    fn now(&self) -> i64 {
        self.clock.load(Ordering::SeqCst)
    }

    fn app(&self) -> Router {
        self.relay.router()
    }

    async fn send(&self, request: Request<Body>) -> (StatusCode, Vec<u8>) {
        let response = self.app().oneshot(request).await.unwrap();
        let status = response.status();
        let body = response.into_body().collect().await.unwrap().to_bytes();
        (status, body.to_vec())
    }

    fn signed(&self, device: &Device, method: &str, path: &str, body: Vec<u8>) -> Request<Body> {
        let auth = request_auth::sign(
            &device.ik,
            method,
            path,
            u64::try_from(self.now()).unwrap(),
            &body,
        );
        Request::builder()
            .method(method)
            .uri(path)
            .header(request_auth::HEADER, auth)
            .body(Body::from(body))
            .unwrap()
    }

    async fn enroll_controller(&self, pc: &Device) {
        let token = self
            .relay
            .create_admin_token(Duration::from_secs(60))
            .unwrap();
        let keys = pc.xk.signed_by(&pc.ik);
        let role = Role::Controller as i32;
        let signature = pc
            .ik
            .sign(&pairing::enroll_transcript(&[], &keys, &[], &[], role));
        let request = EnrollRequest {
            device: Some(keys.to_proto()),
            role,
            signature: signature.to_vec(),
            ..EnrollRequest::default()
        };
        let (status, _) = self
            .send(
                Request::post("/v1/enroll")
                    .header(ADMIN_TOKEN_HEADER, token)
                    .body(Body::from(request.encode_to_vec()))
                    .unwrap(),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED);
    }

    async fn invite(&self, pc: &Device) -> pairing::Token {
        let token = pairing::generate_token();
        let request = CreateInviteRequest {
            token_hash: pairing::token_hash(&token).to_vec(),
            expires_at_ms: self.now() + 120_000,
        };
        let (status, _) = self
            .send(self.signed(pc, "POST", "/v1/invites", request.encode_to_vec()))
            .await;
        assert_eq!(status, StatusCode::CREATED);
        token
    }

    fn phone_enrollment(&self, phone: &Device, pc: &Device, token: &pairing::Token) -> Vec<u8> {
        let keys = phone.xk.signed_by(&phone.ik);
        let token_hash = pairing::token_hash(token);
        let proof =
            pairing::enroll_proof(token, &keys.identity, &keys.exchange, &[], &pc.ik.public());
        let role = Role::Phone as i32;
        let signature = phone.ik.sign(&pairing::enroll_transcript(
            &token_hash,
            &keys,
            &[],
            &proof,
            role,
        ));
        EnrollRequest {
            token_hash: token_hash.to_vec(),
            device: Some(keys.to_proto()),
            wireguard_public_key: Vec::new(),
            proof: proof.to_vec(),
            role,
            signature: signature.to_vec(),
            sealed_hello: sealed::seal(&pc.xk.public(), b"hello").unwrap(),
        }
        .encode_to_vec()
    }

    async fn enroll_phone(
        &self,
        phone: &Device,
        pc: &Device,
        token: &pairing::Token,
    ) -> StatusCode {
        let body = self.phone_enrollment(phone, pc, token);
        let (status, body) = self
            .send(Request::post("/v1/enroll").body(Body::from(body)).unwrap())
            .await;
        if status == StatusCode::CREATED {
            let response = EnrollResponse::decode(body.as_slice()).unwrap();
            assert_eq!(response.controller_id, pc.ik.device_id().to_vec());
            assert_eq!(response.device_id, phone.ik.device_id().to_vec());
        }
        status
    }

    async fn fetch(&self, device: &Device) -> MailboxBatch {
        let (status, body) = self
            .send(self.signed(device, "GET", "/v1/mailbox", Vec::new()))
            .await;
        assert_eq!(status, StatusCode::OK);
        MailboxBatch::decode(body.as_slice()).unwrap()
    }

    async fn paired(&self) -> (Device, Device) {
        let (pc, phone) = (Device::new(), Device::new());
        self.enroll_controller(&pc).await;
        let token = self.invite(&pc).await;
        assert_eq!(
            self.enroll_phone(&phone, &pc, &token).await,
            StatusCode::CREATED
        );
        (pc, phone)
    }
}

fn envelope_from(sender: &Device, recipient: &Device) -> Vec<u8> {
    let header = Header::new(sender.ik.device_id(), recipient.ik.device_id(), 0);
    envelope::seal(
        &SymmetricKey::generate(),
        &header,
        &sender.ik,
        None,
        b"opaque",
    )
}

fn error_code(body: &[u8]) -> String {
    RelayError::decode(body).unwrap().code
}

#[tokio::test]
async fn full_pairing_and_mailbox_flow() {
    let h = Harness::new();
    let (pc, phone) = h.paired().await;

    let batch = h.fetch(&pc).await;
    assert_eq!(batch.items.len(), 1);
    let hello = &batch.items[0];
    assert_eq!(hello.kind, 2);
    assert_eq!(hello.sender_id, phone.ik.device_id().to_vec());
    assert_eq!(sealed::open(&pc.xk, &hello.payload).unwrap(), b"hello");

    let path = format!("/v1/mailbox/{}", hex::encode(phone.ik.device_id()));
    let (status, _) = h
        .send(h.signed(&pc, "PUT", &path, envelope_from(&pc, &phone)))
        .await;
    assert_eq!(status, StatusCode::CREATED);
    let batch = h.fetch(&phone).await;
    assert_eq!(batch.items.len(), 1);
    assert_eq!(batch.items[0].kind, 1);

    let ack = MailboxAck {
        ids: vec![batch.items[0].id],
    };
    let (status, _) = h
        .send(h.signed(&phone, "POST", "/v1/mailbox/ack", ack.encode_to_vec()))
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    assert_eq!(h.fetch(&phone).await.items, Vec::new());
}

#[tokio::test]
async fn invitation_tokens_are_single_use_and_expire() {
    let h = Harness::new();
    let pc = Device::new();
    h.enroll_controller(&pc).await;
    let token = h.invite(&pc).await;
    assert_eq!(
        h.enroll_phone(&Device::new(), &pc, &token).await,
        StatusCode::CREATED
    );
    assert_eq!(
        h.enroll_phone(&Device::new(), &pc, &token).await,
        StatusCode::FORBIDDEN
    );

    let token = h.invite(&pc).await;
    h.clock.fetch_add(121_000, Ordering::SeqCst);
    assert_eq!(
        h.enroll_phone(&Device::new(), &pc, &token).await,
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn forged_enrollment_signature_is_rejected() {
    let h = Harness::new();
    let pc = Device::new();
    h.enroll_controller(&pc).await;
    let token = h.invite(&pc).await;
    let mut request =
        EnrollRequest::decode(h.phone_enrollment(&Device::new(), &pc, &token).as_slice()).unwrap();
    request.proof[0] ^= 1;
    let (status, _) = h
        .send(
            Request::post("/v1/enroll")
                .body(Body::from(request.encode_to_vec()))
                .unwrap(),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    // The failed attempt did not burn the invitation.
    assert_eq!(
        h.enroll_phone(&Device::new(), &pc, &token).await,
        StatusCode::CREATED
    );
}

#[tokio::test]
async fn controller_enrollment_requires_a_valid_admin_token() {
    let h = Harness::new();
    let pc = Device::new();
    let keys = pc.xk.signed_by(&pc.ik);
    let role = Role::Controller as i32;
    let request = EnrollRequest {
        device: Some(keys.to_proto()),
        role,
        signature: pc
            .ik
            .sign(&pairing::enroll_transcript(&[], &keys, &[], &[], role))
            .to_vec(),
        ..EnrollRequest::default()
    };
    for header in [None, Some("forged")] {
        let mut builder = Request::post("/v1/enroll");
        if let Some(value) = header {
            builder = builder.header(ADMIN_TOKEN_HEADER, value);
        }
        let (status, body) = h
            .send(builder.body(Body::from(request.encode_to_vec())).unwrap())
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        assert_eq!(error_code(&body), "forbidden");
    }
}

#[tokio::test]
async fn replayed_stale_and_forged_requests_are_rejected() {
    let h = Harness::new();
    let (pc, _phone) = h.paired().await;

    let request = h.signed(&pc, "GET", "/v1/mailbox", Vec::new());
    let auth = request.headers()[request_auth::HEADER].clone();
    assert_eq!(h.send(request).await.0, StatusCode::OK);
    let replay = Request::get("/v1/mailbox")
        .header(request_auth::HEADER, auth)
        .body(Body::empty())
        .unwrap();
    assert_eq!(h.send(replay).await.0, StatusCode::UNAUTHORIZED);

    let stale = h.signed(&pc, "GET", "/v1/mailbox", Vec::new());
    h.clock.fetch_add(31_000, Ordering::SeqCst);
    assert_eq!(h.send(stale).await.0, StatusCode::UNAUTHORIZED);

    // Signed for another path.
    let wrong = h.signed(&pc, "GET", "/v1/mailbox?wait=1", Vec::new());
    let (parts, body) = wrong.into_parts();
    let mut tampered = Request::get("/v1/mailbox").body(body).unwrap();
    *tampered.headers_mut() = parts.headers;
    assert_eq!(h.send(tampered).await.0, StatusCode::UNAUTHORIZED);

    // Unknown device.
    let stranger = Device::new();
    assert_eq!(
        h.send(h.signed(&stranger, "GET", "/v1/mailbox", Vec::new()))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn envelopes_are_only_routed_between_linked_devices() {
    let h = Harness::new();
    let (pc, phone) = h.paired().await;
    let (other_pc, other_phone) = h.paired().await;

    // Not linked.
    let path = format!("/v1/mailbox/{}", hex::encode(other_phone.ik.device_id()));
    let (status, _) = h
        .send(h.signed(&pc, "PUT", &path, envelope_from(&pc, &other_phone)))
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // Spoofed sender inside the envelope.
    let path = format!("/v1/mailbox/{}", hex::encode(phone.ik.device_id()));
    let (status, _) = h
        .send(h.signed(&pc, "PUT", &path, envelope_from(&other_pc, &phone)))
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Path and envelope recipient disagree.
    let path = format!("/v1/mailbox/{}", hex::encode(other_phone.ik.device_id()));
    let (status, _) = h
        .send(h.signed(&pc, "PUT", &path, envelope_from(&pc, &phone)))
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Phones cannot create invitations.
    let request = CreateInviteRequest {
        token_hash: vec![0; 32],
        expires_at_ms: h.now() + 60_000,
    };
    let (status, _) = h
        .send(h.signed(&phone, "POST", "/v1/invites", request.encode_to_vec()))
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn oversized_and_malformed_bodies_are_rejected() {
    let h = Harness::new();
    let (pc, phone) = h.paired().await;
    let path = format!("/v1/mailbox/{}", hex::encode(phone.ik.device_id()));
    let huge = vec![0u8; bastion_relay::routes::MAX_BODY_BYTES + 1];
    let (status, _) = h.send(h.signed(&pc, "PUT", &path, huge)).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
    let (status, _) = h.send(h.signed(&pc, "PUT", &path, vec![0xff; 10])).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = h
        .send(
            Request::post("/v1/enroll")
                .body(Body::from(vec![0xff, 0xff]))
                .unwrap(),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn long_poll_wakes_up_on_deposit() {
    let h = Arc::new(Harness::new());
    let (pc, phone) = h.paired().await;
    let phone = Arc::new(phone);
    let poller = {
        let h = Arc::clone(&h);
        let phone = Arc::clone(&phone);
        tokio::spawn(async move {
            let started = std::time::Instant::now();
            let (status, body) = h
                .send(h.signed(&phone, "GET", "/v1/mailbox?wait=20", Vec::new()))
                .await;
            assert_eq!(status, StatusCode::OK);
            (
                MailboxBatch::decode(body.as_slice()).unwrap(),
                started.elapsed(),
            )
        })
    };
    tokio::time::sleep(Duration::from_millis(200)).await;
    let path = format!("/v1/mailbox/{}", hex::encode(phone.ik.device_id()));
    let (status, _) = h
        .send(h.signed(&pc, "PUT", &path, envelope_from(&pc, &phone)))
        .await;
    assert_eq!(status, StatusCode::CREATED);
    let (batch, elapsed) = poller.await.unwrap();
    assert_eq!(batch.items.len(), 1);
    assert!(elapsed < Duration::from_secs(10));
}

#[tokio::test]
async fn revocation_unlinks_and_deletes_the_phone() {
    let h = Harness::new();
    let (pc, phone) = h.paired().await;
    let path = format!("/v1/peers/{}", hex::encode(phone.ik.device_id()));
    let stranger_path = format!("/v1/peers/{}", hex::encode(pc.ik.device_id()));
    let (other_pc, _) = h.paired().await;
    assert_eq!(
        h.send(h.signed(&other_pc, "DELETE", &path, Vec::new()))
            .await
            .0,
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        h.send(h.signed(&pc, "DELETE", &path, Vec::new())).await.0,
        StatusCode::NO_CONTENT
    );
    // The phone no longer exists, so it cannot authenticate any more.
    assert_eq!(
        h.send(h.signed(&phone, "DELETE", &stranger_path, Vec::new()))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
}
