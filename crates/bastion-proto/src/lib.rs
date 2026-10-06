// SPDX-License-Identifier: GPL-3.0-or-later
//! Bastion wire protocol types, generated from `protocol/proto/bastion/v1`.
//!
//! The `.proto` files are the single source of truth; see `docs/PROTOCOL.md`.

/// Protocol package `bastion.v1`.
#[allow(missing_docs, clippy::all, clippy::pedantic)]
pub mod v1 {
    include!(concat!(env!("OUT_DIR"), "/bastion.v1.rs"));
}

/// Current envelope format version (`Envelope.version`).
pub const ENVELOPE_VERSION: u32 = 1;

/// Current application protocol version (`MessageBody.protocol_version`).
pub const PROTOCOL_VERSION: u32 = 1;

/// Maximum accepted serialized envelope size, checked before parsing (PROTOCOL.md §6.1).
pub const MAX_ENVELOPE_BYTES: usize = 512 * 1024;

/// Maximum size of any `bytes` field inside a message body (PROTOCOL.md §6.6).
pub const MAX_BYTES_FIELD: usize = 384 * 1024;

#[cfg(test)]
mod tests {
    use super::v1::{Command, Ring, command::Kind};
    use super::v1::{Envelope, MessageBody, message_body::Payload};
    use prost::Message;

    #[test]
    fn envelope_round_trips() {
        let envelope = Envelope {
            version: super::ENVELOPE_VERSION,
            sender_id: vec![1u8; 16],
            recipient_id: vec![2u8; 16],
            key_epoch: 3,
            nonce: vec![4u8; 24],
            ciphertext: vec![5u8; 64],
        };
        let bytes = envelope.encode_to_vec();
        let decoded = Envelope::decode(bytes.as_slice()).expect("decode");
        assert_eq!(decoded, envelope);
    }

    #[test]
    fn command_payload_round_trips() {
        let body = MessageBody {
            protocol_version: super::PROTOCOL_VERSION,
            message_id: vec![9u8; 16],
            counter: 42,
            timestamp_ms: 1_700_000_000_000,
            ttl_seconds: 60,
            payload: Some(Payload::Command(Command {
                kind: Some(Kind::Ring(Ring {
                    duration_seconds: 30,
                    flashlight: true,
                    vibrate: true,
                })),
            })),
        };
        let decoded = MessageBody::decode(body.encode_to_vec().as_slice()).expect("decode");
        assert_eq!(decoded, body);
    }

    #[test]
    fn truncated_input_is_rejected() {
        let mut bytes = Envelope {
            version: 1,
            ciphertext: vec![7u8; 32],
            ..Envelope::default()
        }
        .encode_to_vec();
        bytes.truncate(bytes.len() - 5);
        assert!(Envelope::decode(bytes.as_slice()).is_err());
    }
}
