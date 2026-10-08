// SPDX-License-Identifier: GPL-3.0-or-later
//! Bastion desktop backend.
//!
//! All cryptography, key storage and relay communication live here; the webview only
//! receives already-verified data through typed commands (ADR-0005).

pub mod commands;
pub mod core;
pub mod engine;
pub mod error;
pub mod model;
pub mod relay_client;
pub mod tiles;
pub mod vault;

use std::sync::Arc;
use std::time::Duration;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter as _, Manager, UserAttentionType, WindowEvent};

use crate::core::{AppCore, Paths, UiEvent};

/// Product name, injected at build time from `branding/product.json`.
pub const PRODUCT_NAME: &str = env!("BASTION_PRODUCT_NAME");

fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn emitter(app: AppHandle) -> crate::core::Emitter {
    Arc::new(move |event| {
        let name = match event {
            UiEvent::Changed => "bastion://changed",
            UiEvent::Attention => "bastion://attention",
            UiEvent::Locked => "bastion://locked",
        };
        let _ = app.emit(name, ());
        if event == UiEvent::Attention
            && let Some(window) = app.get_webview_window("main")
        {
            let _ = window.request_user_attention(Some(UserAttentionType::Critical));
        }
    })
}

/// Pushes a live stream frame to the webview as a base64 JPEG (payload stays off disk).
fn frame_sink(app: AppHandle) -> crate::core::FrameSink {
    use base64::Engine;
    Arc::new(move |device_id: &[u8], sequence: u64, jpeg: &[u8]| {
        let payload = serde_json::json!({
            "deviceId": hex::encode(device_id),
            "sequence": sequence,
            "jpeg": base64::engine::general_purpose::STANDARD.encode(jpeg),
        });
        let _ = app.emit("bastion://frame", payload);
    })
}

/// Pushes a live audio chunk to the webview as base64 PCM.
fn audio_sink(app: AppHandle) -> crate::core::AudioSink {
    use base64::Engine;
    Arc::new(
        move |device_id: &[u8], sequence: u64, pcm: &[u8], sample_rate: u32| {
            let payload = serde_json::json!({
                "deviceId": hex::encode(device_id),
                "sequence": sequence,
                "pcm": base64::engine::general_purpose::STANDARD.encode(pcm),
                "sampleRate": sample_rate,
            });
            let _ = app.emit("bastion://audio", payload);
        },
    )
}

fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Ouvrir / Open", true, None::<&str>)?;
    let lock = MenuItem::with_id(app, "lock", "Verrouiller / Lock", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quitter / Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &lock, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main")
        .tooltip(PRODUCT_NAME)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => show_main(app),
            "lock" => {
                let core = Arc::clone(app.state::<Arc<AppCore>>().inner());
                tauri::async_runtime::spawn(async move { core.lock().await });
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

/// Starts the Tauri application.
///
/// # Panics
/// Panics if the Tauri runtime cannot start (no window system available).
#[allow(clippy::expect_used, clippy::too_many_lines)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "warn".into()),
        )
        .init();
    let tile_http = tiles::http_client().ok();

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app);
        }))
        .setup(|app| {
            let data = app.path().app_data_dir()?;
            let cache = app.path().app_cache_dir()?;
            std::fs::create_dir_all(&data)?;
            let core = AppCore::new(
                Paths::new(&data, &cache),
                emitter(app.handle().clone()),
                frame_sink(app.handle().clone()),
                audio_sink(app.handle().clone()),
            );
            app.manage(Arc::clone(&core));
            tauri::async_runtime::spawn(async move {
                let mut tick = tokio::time::interval(Duration::from_secs(20));
                loop {
                    tick.tick().await;
                    core.check_auto_lock().await;
                }
            });
            build_tray(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the window keeps the app (relay, alerts) running in the tray.
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .register_asynchronous_uri_scheme_protocol("tiles", move |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            let http = tile_http.clone();
            let path = request.uri().path().to_owned();
            tauri::async_runtime::spawn(async move {
                let core = Arc::clone(app.state::<Arc<AppCore>>().inner());
                let online = core
                    .read(|s| s.data.settings.online_map)
                    .await
                    .unwrap_or(false);
                let response = match &http {
                    Some(http) => tiles::serve(core.tiles_dir(), http, &path, online).await,
                    None => {
                        tiles::serve(core.tiles_dir(), &reqwest::Client::new(), &path, false).await
                    }
                };
                responder.respond(response);
            });
        })
        .invoke_handler(tauri::generate_handler![
            commands::app_status,
            commands::suggested_host,
            commands::setup,
            commands::unlock,
            commands::lock,
            commands::touch,
            commands::devices,
            commands::device,
            commands::journal,
            commands::start_pairing,
            commands::cancel_pairing,
            commands::confirm_pairing,
            commands::send_command,
            commands::photos,
            commands::photo,
            commands::arm_wipe,
            commands::disarm_wipe,
            commands::send_sensitive,
            commands::forget_device,
            commands::settings,
            commands::update_settings,
        ])
        .run(tauri::generate_context!())
        .expect("failed to start the Tauri runtime");
}
