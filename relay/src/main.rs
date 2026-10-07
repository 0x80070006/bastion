// SPDX-License-Identifier: AGPL-3.0-or-later
//! Bastion relay entry point.
//!
//! ```text
//! bastion-relay              serve the API (configuration from BASTION_RELAY_* variables)
//! bastion-relay pin          print the TLS public-key pin to configure a controller
//! bastion-relay admin-token  print a single-use token (24 h) to enroll a controller
//! ```

use std::io::Write;
use std::time::Duration;

use bastion_relay::{Config, Relay};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let config = Config::from_env()?;
    let relay = Relay::open(&config)?;
    let command = std::env::args().nth(1);
    match command.as_deref() {
        None | Some("serve") => serve(&config, &relay).await,
        Some("pin") => {
            let pin = relay.spki_pin().ok_or("TLS is disabled")?;
            writeln!(std::io::stdout(), "{}", hex::encode(pin))?;
            Ok(())
        }
        Some("admin-token") => {
            let token = relay.create_admin_token(Duration::from_secs(24 * 3600))?;
            writeln!(std::io::stdout(), "{token}")?;
            Ok(())
        }
        Some(_) => Err("usage: bastion-relay [serve | pin | admin-token]".into()),
    }
}

async fn serve(config: &Config, relay: &Relay) -> Result<(), Box<dyn std::error::Error>> {
    let listener = std::net::TcpListener::bind(config.listen_addr)?;
    tracing::info!(
        addr = %config.listen_addr,
        tls = config.tls,
        spki_pin = relay.spki_pin().map(hex::encode).unwrap_or_default(),
        "relay listening"
    );
    relay.serve(listener, shutdown_signal()).await?;
    tracing::info!("relay stopped");
    Ok(())
}

async fn shutdown_signal() {
    if let Err(error) = tokio::signal::ctrl_c().await {
        tracing::error!(%error, "failed to listen for shutdown signal");
    }
}
