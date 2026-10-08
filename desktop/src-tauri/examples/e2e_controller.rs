// SPDX-License-Identifier: GPL-3.0-or-later
//! Headless end-to-end check against a real phone (or emulator): runs the relay on
//! `0.0.0.0:8443`, prints the pairing link, accepts the SAS, then sends status, locate and
//! ring commands and prints what the phone reports.
//!
//! ```text
//! cargo run -p bastion-desktop --example e2e_controller -- https://10.0.2.2:8443
//! ```
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::print_stdout,
    clippy::too_many_lines,
    missing_docs
)]

use std::time::Duration;

use bastion_crypto::vault::KdfParams;
use bastion_desktop_lib::engine::{Action, Media, Session};
use bastion_desktop_lib::model::{PairingState, RelaySettings};
use bastion_desktop_lib::relay_client::RelayClient;
use bastion_proto::v1::{
    AudioControl, CapturePhoto, Command, LocateNow, RequestStatus, Ring, StopRing, StreamControl,
    capture_photo, command,
};
use bastion_relay::{Config, Relay};

fn now() -> i64 {
    bastion_desktop_lib::core::now_ms()
}

async fn run(client: &RelayClient, actions: Vec<Action>) {
    for action in actions {
        match action {
            Action::Send {
                recipient,
                envelope,
            } => client.send(&recipient, envelope).await.unwrap(),
            Action::Revoke { device } => client.revoke(&device).await.unwrap(),
        }
    }
}

#[tokio::main]
async fn main() {
    let announced = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "https://10.0.2.2:8443".into());
    let dir = std::env::temp_dir().join(format!("bastion-e2e-{}", now()));
    let config = Config {
        listen_addr: "0.0.0.0:8443".parse().unwrap(),
        access_log: false,
        data_dir: dir,
        tls: true,
    };
    let relay = Relay::open(&config).unwrap();
    let pin = relay.spki_pin().unwrap();
    let listener = std::net::TcpListener::bind(config.listen_addr).unwrap();
    let serving = relay.clone();
    tokio::spawn(async move { serving.serve(listener, std::future::pending()).await });
    tokio::time::sleep(Duration::from_millis(300)).await;

    let settings = RelaySettings::Embedded {
        port: 8443,
        advertised_host: None,
    };
    let mut session =
        Session::create(settings, "e2e test password", KdfParams::INTERACTIVE).unwrap();
    let client = RelayClient::new("https://127.0.0.1:8443", pin, session.identity()).unwrap();
    let admin = relay.create_admin_token(Duration::from_secs(60)).unwrap();
    client
        .enroll_controller(&session.keys(), &admin)
        .await
        .unwrap();
    let (token, hash, expires) = session.new_invite(now());
    client.create_invite(hash, expires).await.unwrap();
    let uri = session.invite_uri(&token, expires, &announced, &pin, "E2E-PC");
    std::fs::write(std::env::temp_dir().join("bastion-e2e-uri.txt"), &uri).unwrap();
    println!("PAIRING_URI {uri}");

    let mut commanded = false;
    let mut lock_done = false;
    let mut photo_sent = false;
    let mut stream_sent = false;
    let mut audio_sent = false;
    let mut geofences_sent = false;
    let mut photos = 0usize;
    let mut frames = 0usize;
    let mut chunks = 0usize;
    let mut seen_journal = 0;
    let deadline = std::time::Instant::now() + Duration::from_secs(900);
    while std::time::Instant::now() < deadline {
        let batch = client.fetch(20).await.unwrap();
        let mut ids = Vec::new();
        for item in &batch.items {
            let (actions, _, media) =
                session.handle_item(item.kind, &item.sender_id, &item.payload, now());
            run(&client, actions).await;
            for m in media {
                match m {
                    Media::Photo { jpeg, .. } => {
                        photos += 1;
                        println!("PHOTO {} bytes", jpeg.len());
                    }
                    Media::Frame { sequence, jpeg, .. } => {
                        frames += 1;
                        println!("FRAME seq={sequence} {} bytes", jpeg.len());
                    }
                    Media::Audio {
                        sequence,
                        pcm,
                        sample_rate,
                        ..
                    } => {
                        chunks += 1;
                        println!("AUDIO seq={sequence} {} bytes @{sample_rate}Hz", pcm.len());
                    }
                }
            }
            ids.push(item.id);
        }
        client.ack(ids).await.unwrap();

        let pending: Vec<Vec<u8>> = session
            .data
            .devices
            .iter()
            .filter(|d| d.state == PairingState::PendingSas)
            .map(|d| d.device_id.clone())
            .collect();
        for id in pending {
            let d = &session.data.devices[session.data.device_index(&id).unwrap()];
            println!(
                "SAS {} from '{}' — accepting",
                bastion_crypto::pairing::format_sas(d.sas),
                d.label
            );
            let actions = session.confirm_pairing(&id, true, now()).unwrap();
            run(&client, actions).await;
        }

        if let Some(device) = session
            .data
            .devices
            .iter()
            .find(|d| d.state == PairingState::Active)
            .cloned()
            && !commanded
        {
            commanded = true;
            println!("ACTIVE {}", device.label);
            for kind in [
                command::Kind::RequestStatus(RequestStatus {}),
                command::Kind::LocateNow(LocateNow {
                    high_accuracy: false,
                }),
                command::Kind::Ring(Ring {
                    duration_seconds: 5,
                    flashlight: false,
                    vibrate: true,
                }),
                command::Kind::StopRing(StopRing {}),
            ] {
                let action = session
                    .command(&device.device_id, Command { kind: Some(kind) }, None, now())
                    .unwrap();
                run(&client, vec![action]).await;
            }
        }

        for entry in session.data.journal.iter().skip(seen_journal) {
            println!("JOURNAL {} {:?}", entry.kind, entry.detail);
        }
        seen_journal = session.data.journal.len();
        if let Some(d) = session.data.devices.first() {
            if let Some(status) = &d.status {
                println!(
                    "STATUS battery={:?} network={} tracking={}",
                    status.battery_percent, status.network, status.tracking_mode
                );
            }
            if let Some(loc) = d.locations.back() {
                println!(
                    "LOCATION {:.5},{:.5} ±{}m",
                    loc.latitude, loc.longitude, loc.accuracy_m
                );
            }
            let id = d.device_id.clone();
            let done = d
                .commands
                .iter()
                .filter(|c| c.status == "completed")
                .count();
            if done == 4 && d.commands.len() == 4 && !lock_done {
                // Sensitive command, counter-signed with the privileged key (PROTOCOL.md §7).
                let seed = session
                    .data
                    .privileged_sealed
                    .open("e2e test password")
                    .unwrap();
                let pk = bastion_crypto::identity::SigningKeypair::from_seed(&seed);
                let lock = Command {
                    kind: Some(command::Kind::Lock(bastion_proto::v1::Lock {
                        contact: None,
                    })),
                };
                let action = session.command(&id, lock, Some(&pk), now()).unwrap();
                run(&client, vec![action]).await;
                lock_done = true;
            } else if done >= 5 && !photo_sent {
                println!("locked ok, requesting a photo");
                let photo = Command {
                    kind: Some(command::Kind::CapturePhoto(CapturePhoto {
                        camera: capture_photo::Camera::Back as i32,
                    })),
                };
                run(
                    &client,
                    vec![session.command(&id, photo, None, now()).unwrap()],
                )
                .await;
                photo_sent = true;
            } else if photos >= 1 && !stream_sent {
                println!("photo received, starting a 10 s stream");
                let start = Command {
                    kind: Some(command::Kind::StreamControl(StreamControl {
                        enabled: true,
                        camera: capture_photo::Camera::Back as i32,
                        max_fps: 3,
                        max_edge_px: 480,
                        max_duration_seconds: 10,
                    })),
                };
                run(
                    &client,
                    vec![session.command(&id, start, None, now()).unwrap()],
                )
                .await;
                stream_sent = true;
            } else if stream_sent && frames >= 3 && !audio_sent {
                let stop = Command {
                    kind: Some(command::Kind::StreamControl(StreamControl {
                        enabled: false,
                        ..Default::default()
                    })),
                };
                let listen = Command {
                    kind: Some(command::Kind::AudioControl(AudioControl {
                        enabled: true,
                        max_duration_seconds: 6,
                    })),
                };
                println!("stream ok, starting a 6 s audio stream");
                run(
                    &client,
                    vec![
                        session.command(&id, stop, None, now()).unwrap(),
                        session.command(&id, listen, None, now()).unwrap(),
                    ],
                )
                .await;
                audio_sent = true;
            } else if audio_sent && chunks >= 3 && !geofences_sent {
                let stop = Command {
                    kind: Some(command::Kind::AudioControl(AudioControl {
                        enabled: false,
                        ..Default::default()
                    })),
                };
                run(
                    &client,
                    vec![session.command(&id, stop, None, now()).unwrap()],
                )
                .await;
                println!("audio ok, setting a geofence");
                let zone = bastion_desktop_lib::model::GeofenceDef {
                    id: "home".to_owned(),
                    name: "Home".to_owned(),
                    latitude: 48.8566,
                    longitude: 2.3522,
                    radius_m: 150.0,
                };
                run(
                    &client,
                    vec![session.set_geofences(&id, vec![zone], now()).unwrap()],
                )
                .await;
                geofences_sent = true;
            } else if geofences_sent
                && d.commands
                    .iter()
                    .any(|c| c.kind == "geofences" && c.status == "completed")
            {
                println!(
                    "E2E_OK photo={photos} frames={frames} chunks={chunks} geofences=ok (lock, photo, camera, audio and geofencing all worked)"
                );
                return;
            }
        }
    }
    println!(
        "E2E_TIMEOUT photos={photos} frames={frames} chunks={chunks} geofences_sent={geofences_sent}"
    );
}
