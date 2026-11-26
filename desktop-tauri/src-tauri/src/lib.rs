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
pub mod store;

use commands::AppState;
use std::sync::{Arc, Mutex};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let manager = manager::Manager::load();
    // The downloads event bridge (manager stamp watcher -> Tauri
    // events) lands with the Transfers wave. Until then a no-op
    // notifier absorbs state-change signals.
    manager.set_notifier(Arc::new(|| {}));
    let state = AppState {
        manager,
        sources: Mutex::new(store::load_sources()),
        favorites: Mutex::new(store::load_favorites()),
    };
    tauri::Builder::default()
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            commands::get_bootstrap,
            commands::probe_sources
        ])
        .run(tauri::generate_context!())
        .expect("error while running LanFetch");
}
