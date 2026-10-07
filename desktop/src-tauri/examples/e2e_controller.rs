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
use bastion_desktop_lib::engine::{Action, Session};
use bastion_desktop_lib::model::{PairingState, RelaySettings};
use bastion_desktop_lib::relay_client::RelayClient;
use bastion_proto::v1::{Command, LocateNow, RequestStatus, Ring, StopRing, command};
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
    let mut seen_journal = 0;
    let deadline = std::time::Instant::now() + Duration::from_secs(900);
    while std::time::Instant::now() < deadline {
        let batch = client.fetch(20).await.unwrap();
        let mut ids = Vec::new();
        for item in &batch.items {
            let (actions, _) =
                session.handle_item(item.kind, &item.sender_id, &item.payload, now());
            run(&client, actions).await;
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
            let done = d
                .commands
                .iter()
                .filter(|c| c.status == "completed")
                .count();
            if done == 4 && d.commands.len() == 4 {
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
                let id = d.device_id.clone();
                let action = session.command(&id, lock, Some(&pk), now()).unwrap();
                run(&client, vec![action]).await;
            } else if done >= 5 {
                println!("E2E_OK all commands completed, including privileged lock");
                return;
            }
        }
    }
    println!("E2E_TIMEOUT");
}
