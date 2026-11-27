//! LanFetch desktop (Tauri) application entry.
//!
//! The catalog/download engine modules (`core`, `catalog`, `meta`,
//! `store`, `manager`) are carried over from the desktop-rs build
//! essentially unchanged — see the Tauri rebuild plan (Wave 1). Only
//! the shell is new: `dto` defines the IPC shapes and `commands` the
//! Tauri command surface over the carried engine.

pub mod catalog;
pub mod commands;
pub mod core;
pub mod dto;
pub mod manager;
pub mod meta;
pub mod poster_service;
pub mod serve;
pub mod store;

use commands::AppState;
use std::sync::{Arc, Mutex};
use tauri::Emitter;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let manager = manager::Manager::load();
    let manager_bridge = manager.clone();
    let posters = Arc::new(poster_service::PosterService::new());
    let state = AppState {
        manager,
        sources: Mutex::new(store::load_sources()),
        favorites: Mutex::new(store::load_favorites()),
        posters: posters.clone(),
        catalog: Arc::new(Mutex::new(commands::CatalogState::default())),
    };
    tauri::Builder::default()
        // Single instance, registered first by the plugin's own
        // contract: a second launch focuses the running window
        // instead of opening a rival app over the same downloads.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            use tauri::Manager;
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .manage(state)
        .setup(move |app| {
            // Downloads event bridge: the manager's framework-neutral
            // notifier becomes a Tauri event carrying the change
            // stamp. The frontend refetches the download list on the
            // event and discards anything stamped older than what it
            // already rendered — push, never polling. The Weak keeps
            // the closure from owning the manager that owns it.
            let handle = app.handle().clone();
            let weak = Arc::downgrade(&manager_bridge);
            manager_bridge.set_notifier(Arc::new(move || {
                if let Some(m) = weak.upgrade() {
                    let _ = handle.emit("downloads-changed", m.stamp());
                }
            }));
            Ok(())
        })
        .register_asynchronous_uri_scheme_protocol(
            "lanfetch-poster",
            move |_ctx, request, responder| {
                let service = posters.clone();
                let key = poster_service::parse_key(&request.uri().to_string());
                // Disk/network fetch is blocking work; keep it off
                // the protocol thread so one slow server cannot stall
                // every other poster's load.
                tauri::async_runtime::spawn_blocking(move || {
                    let bytes = key.and_then(|k| service.bytes_for(&k).ok());
                    let mut response = tauri::http::Response::new(Vec::new());
                    match bytes {
                        Some(b) => {
                            response.headers_mut().insert(
                                tauri::http::header::CONTENT_TYPE,
                                tauri::http::HeaderValue::from_static(
                                    poster_service::content_type(&b),
                                ),
                            );
                            response.headers_mut().insert(
                                tauri::http::header::CACHE_CONTROL,
                                tauri::http::HeaderValue::from_static("max-age=86400"),
                            );
                            *response.body_mut() = b;
                        }
                        None => {
                            *response.status_mut() = tauri::http::StatusCode::NOT_FOUND;
                        }
                    }
                    responder.respond(response);
                });
            },
        )
        .invoke_handler(tauri::generate_handler![
            commands::get_bootstrap,
            commands::probe_sources,
            commands::poster_srcs,
            commands::get_categories,
            commands::feed_open,
            commands::feed_next,
            commands::get_title_detail,
            commands::start_download,
            commands::toggle_favorite,
            commands::get_downloads,
            commands::pause_download,
            commands::resume_download,
            commands::remove_download,
            commands::clear_completed_downloads,
            commands::search_titles,
            commands::update_settings,
            commands::add_source,
            commands::remove_source,
            commands::set_source_enabled,
            commands::get_favorites,
            commands::pause_all_downloads,
            commands::resume_all_downloads,
            commands::redownload_download,
            commands::check_update
        ])
        .run(tauri::generate_context!())
        .expect("error while running LanFetch");
}
