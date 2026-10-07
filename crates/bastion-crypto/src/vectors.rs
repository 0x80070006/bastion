// SPDX-License-Identifier: GPL-3.0-or-later
//! Shared interoperability vectors (`protocol/testvectors/v1.txt`).
//!
//! The Rust implementation generates them from fixed seeds; the Kotlin implementation
//! (libsodium) recomputes and checks every value. Regenerate with
//! `BASTION_UPDATE_VECTORS=1 cargo test -p bastion-crypto vectors`.
#![allow(clippy::similar_names, clippy::too_many_lines)]

use std::path::PathBuf;

use bastion_proto::v1::{Command, MessageBody, Ring, command, message_body};
use prost::Message;
use rand_core::{CryptoRng, RngCore};

use crate::envelope::{self, Header};
use crate::identity::{ExchangeKeypair, SigningKeypair};
use crate::session::{Party, directional_key};
use crate::{hash, pairing, request_auth, transcript::Transcript};

/// Deterministic RNG for the sealed-box vector (never used outside tests).
struct FixedRng(u8);

impl RngCore for FixedRng {
    fn next_u32(&mut self) -> u32 {
        u32::from(self.0)
    }
    fn next_u64(&mut self) -> u64 {
        u64::from(self.0)
    }
    fn fill_bytes(&mut self, dest: &mut [u8]) {
        for byte in dest {
            *byte = self.0;
            self.0 = self.0.wrapping_mul(31).wrapping_add(7);
        }
    }
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rand_core::Error> {
        self.fill_bytes(dest);
        Ok(())
    }
}

impl CryptoRng for FixedRng {}

fn seed(label: &str) -> [u8; 32] {
    hash::blake2b_256(label.as_bytes())
}

fn generate() -> String {
    let mut out: Vec<(String, String)> = Vec::new();
    let mut put = |name: &str, value: &[u8]| out.push((name.to_owned(), hex::encode(value)));

    let pc_ik = SigningKeypair::from_seed(&seed("pc-ik"));
    let pc_xk = ExchangeKeypair::from_secret(&seed("pc-xk"), 7);
    let pc_pk = SigningKeypair::from_seed(&seed("pc-pk"));
    let phone_ik = SigningKeypair::from_seed(&seed("phone-ik"));
    let phone_xk = ExchangeKeypair::from_secret(&seed("phone-xk"), 0);
    let pc = pc_xk.signed_by(&pc_ik);
    let phone = phone_xk.signed_by(&phone_ik);

    put("blake2b_256_abc", &hash::blake2b_256(b"abc"));
    put(
        "transcript_example",
        &Transcript::new(b"ctx").field(b"ab").u32(7).u64(9).finish(),
    );

    put("pc_ik_seed", pc_ik.seed().as_slice());
    put("pc_ik_pub", &pc_ik.public());
    put("pc_device_id", &pc_ik.device_id());
    put("pc_xk_secret", pc_xk.secret().as_slice());
    put("pc_xk_pub", &pc_xk.public());
    put("pc_xk_epoch", &pc_xk.epoch().to_be_bytes());
    put("pc_xk_sig", &pc.exchange_signature);
    put("pc_fingerprint", pc.fingerprint().as_bytes());
    put("pc_pk_seed", pc_pk.seed().as_slice());
    put("pc_pk_pub", &pc_pk.public());

    put("phone_ik_seed", phone_ik.seed().as_slice());
    put("phone_ik_pub", &phone_ik.public());
    put("phone_device_id", &phone_ik.device_id());
    put("phone_xk_secret", phone_xk.secret().as_slice());
    put("phone_xk_pub", &phone_xk.public());
    put("phone_xk_epoch", &phone_xk.epoch().to_be_bytes());
    put("phone_xk_sig", &phone.exchange_signature);
    put("phone_fingerprint", phone.fingerprint().as_bytes());

    let token: pairing::Token = seed("token")[..16].try_into().unwrap_or_default();
    let wg = [0x77u8; 32];
    let token_hash = pairing::token_hash(&token);
    let proof = pairing::enroll_proof(&token, &phone.identity, &phone.exchange, &wg, &pc.identity);
    let enroll = pairing::enroll_transcript(&token_hash, &phone, &wg, &proof, 1);
    put("token", &token);
    put("token_hash", &token_hash);
    put("wireguard_pub", &wg);
    put("enroll_proof", &proof);
    put("enroll_transcript", &enroll);
    put("enroll_signature", &phone_ik.sign(&enroll));
    let sas = pairing::sas_code(&token, &pc, &phone);
    put("sas_code", &sas.to_be_bytes());
    put("sas_display", pairing::format_sas(sas).as_bytes());

    let pc_party = Party {
        identity: pc.identity,
        exchange: pc.exchange,
    };
    let phone_party = Party {
        identity: phone.identity,
        exchange: phone.exchange,
    };
    let pc_to_phone = directional_key(&pc_xk, &pc_party, &phone_party)
        .unwrap_or_else(|_| crate::session::SymmetricKey::from_bytes([0; 32]));
    let phone_to_pc = directional_key(&phone_xk, &phone_party, &pc_party)
        .unwrap_or_else(|_| crate::session::SymmetricKey::from_bytes([0; 32]));
    put("session_pc_to_phone", pc_to_phone.expose());
    put("session_phone_to_pc", phone_to_pc.expose());

    let body = MessageBody {
        protocol_version: 1,
        message_id: vec![0x11; 16],
        counter: 42,
        timestamp_ms: 1_760_000_000_000,
        ttl_seconds: 60,
        payload: Some(message_body::Payload::Command(Command {
            kind: Some(command::Kind::Ring(Ring {
                duration_seconds: 30,
                flashlight: true,
                vibrate: true,
            })),
        })),
    }
    .encode_to_vec();
    let header = Header::new(pc_ik.device_id(), phone_ik.device_id(), pc_xk.epoch());
    let nonce = [0x24u8; 24];
    put("envelope_aad", &header.aad());
    put("envelope_body", &body);
    put("envelope_nonce", &nonce);
    put(
        "envelope_signature_input",
        &envelope::signature_input(&header.aad(), &body),
    );
    put(
        "envelope_bytes",
        &envelope::seal_with_nonce(&pc_to_phone, &header, &pc_ik, Some(&pc_pk), &body, &nonce),
    );

    let path = format!("/v1/mailbox/{}", hex::encode(phone_ik.device_id()));
    let request_nonce = [0x42u8; 16];
    let timestamp: u64 = 1_760_000_000_123;
    put("request_method", b"PUT");
    put("request_path", path.as_bytes());
    put("request_timestamp", &timestamp.to_be_bytes());
    put("request_nonce", &request_nonce);
    put("request_body", &body);
    put(
        "request_header",
        request_auth::sign_with_nonce(&pc_ik, "PUT", &path, timestamp, &body, &request_nonce)
            .as_bytes(),
    );

    let sealed = crypto_box::PublicKey::from(pc.exchange)
        .seal(&mut FixedRng(5), b"pairing hello")
        .unwrap_or_default();
    put("sealed_plaintext", b"pairing hello");
    put("sealed_to_pc", &sealed);

    let mut text = String::from(
        "# Bastion protocol v1 interoperability vectors (hex). Generated by\n\
         # `BASTION_UPDATE_VECTORS=1 cargo test -p bastion-crypto vectors`; checked by Rust\n\
         # and Kotlin tests. Seeds are BLAKE2b-256 of ASCII labels; *_display and\n\
         # *_fingerprint values are hex-encoded UTF-8.\n",
    );
    for (name, value) in out {
        text.push_str(&name);
        text.push_str(" = ");
        text.push_str(&value);
        text.push('\n');
    }
    text
}

fn path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../protocol/testvectors/v1.txt")
}

#[test]
fn vectors_match_committed_file() {
    let generated = generate();
    if std::env::var_os("BASTION_UPDATE_VECTORS").is_some() {
        std::fs::create_dir_all(path().parent().unwrap()).unwrap();
        std::fs::write(path(), &generated).unwrap();
    }
    let committed = std::fs::read_to_string(path())
        .expect("missing vectors: run with BASTION_UPDATE_VECTORS=1")
        .replace("\r\n", "\n");
    assert_eq!(committed, generated, "vectors are stale");
}

#[test]
fn committed_sealed_box_opens() {
    let pc_xk = ExchangeKeypair::from_secret(&seed("pc-xk"), 7);
    let committed = std::fs::read_to_string(path()).unwrap();
    let sealed = committed
        .lines()
        .find_map(|l| l.strip_prefix("sealed_to_pc = "))
        .unwrap();
    let opened = crate::sealed::open(&pc_xk, &hex::decode(sealed).unwrap()).unwrap();
    assert_eq!(opened, b"pairing hello");
}
