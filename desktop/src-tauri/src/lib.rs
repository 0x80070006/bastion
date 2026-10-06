// SPDX-License-Identifier: GPL-3.0-or-later
//! Bastion desktop backend.
//!
//! All cryptography, key storage and relay communication live here; the webview only
//! receives already-verified data through typed commands (ADR-0005).

use serde::Serialize;

/// Product name, injected at build time from `branding/product.json`.
pub const PRODUCT_NAME: &str = env!("BASTION_PRODUCT_NAME");

/// Static application information exposed to the frontend.
#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    /// Display name.
    pub product_name: &'static str,
    /// Application version from Cargo.
    pub version: &'static str,
    /// Supported application protocol version.
    pub protocol_version: u32,
}

/// Returns static application information.
#[must_use]
pub fn app_info() -> AppInfo {
    AppInfo {
        product_name: PRODUCT_NAME,
        version: env!("CARGO_PKG_VERSION"),
        protocol_version: bastion_proto::PROTOCOL_VERSION,
    }
}

#[tauri::command]
fn get_app_info() -> AppInfo {
    app_info()
}

/// Starts the Tauri application.
///
/// # Panics
/// Panics if the Tauri runtime cannot start (no window system available).
#[allow(clippy::expect_used)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![get_app_info])
        .run(tauri::generate_context!())
        .expect("failed to start the Tauri runtime");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_info_uses_branding_and_protocol() {
        let info = app_info();
        assert_ne!(info.product_name, "");
        assert_eq!(info.protocol_version, bastion_proto::PROTOCOL_VERSION);
    }
}
