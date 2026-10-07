// SPDX-License-Identifier: GPL-3.0-or-later
//! Generates Rust types from `protocol/proto` with a vendored `protoc` (no system install).

use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let proto_root = manifest_dir.join("../../protocol/proto");
    let files = [
        "bastion/v1/envelope.proto",
        "bastion/v1/messages.proto",
        "bastion/v1/commands.proto",
        "bastion/v1/events.proto",
        "bastion/v1/pairing.proto",
        "bastion/v1/relay.proto",
    ];

    let protoc = protoc_bin_vendored::protoc_bin_path()?;
    let mut config = prost_build::Config::new();
    config.protoc_executable(protoc);
    config.compile_protos(
        &files.map(|f| proto_root.join(f)),
        std::slice::from_ref(&proto_root),
    )?;

    println!("cargo:rerun-if-changed={}", proto_root.display());
    Ok(())
}
