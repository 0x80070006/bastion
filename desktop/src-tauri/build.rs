// SPDX-License-Identifier: GPL-3.0-or-later
//! Exposes the product name from `branding/product.json` (ADR-0002) and runs the Tauri build.

use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let branding_path = manifest_dir.join("../../branding/product.json");
    let branding: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&branding_path)?)?;
    let name = branding["name"]
        .as_str()
        .ok_or("branding/product.json: `name` must be a string")?;
    println!("cargo:rustc-env=BASTION_PRODUCT_NAME={name}");
    println!("cargo:rerun-if-changed={}", branding_path.display());

    tauri_build::build();
    Ok(())
}
