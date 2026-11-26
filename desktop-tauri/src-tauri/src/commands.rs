//! Tauri commands — Wave 1 surface: bootstrap + server probes.
//! Catalog, poster and download commands land in their own waves.

use std::sync::{Arc, Mutex};

use tauri::State;

use crate::core;
use crate::dto::{BootstrapDto, DownloadListDto, SettingsDto, SourceDto, SourceProbeDto, TaskDto};
use crate::manager::{DlState, Manager};
use crate::store::SourceItem;

pub struct AppState {
    pub manager: Arc<Manager>,
    pub sources: Mutex<Vec<SourceItem>>,
    pub favorites: Mutex<Vec<String>>,
    pub posters: Arc<crate::poster_service::PosterService>,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn download_list(manager: &Manager) -> DownloadListDto {
    let tasks = lock(&manager.tasks);
    let active_count = tasks
        .iter()
        .filter(|t| matches!(t.state, DlState::Running | DlState::Queued))
        .count();
    DownloadListDto {
        stamp: manager.stamp(),
        tasks: tasks.iter().map(TaskDto::from).collect(),
        active_count,
    }
}

/// Everything the first paint needs, from local disk only — no network.
/// First-run and returning launches enter through this same path; the
/// onboarding gate is just the `onboardingDone` flag in the payload.
#[tauri::command]
pub fn get_bootstrap(state: State<'_, AppState>) -> BootstrapDto {
    let settings = state.manager.settings();
    BootstrapDto {
        app_version: env!("CARGO_PKG_VERSION").to_string(),
        settings: SettingsDto::from(&settings),
        sources: lock(&state.sources).iter().map(SourceDto::from).collect(),
        favorites: lock(&state.favorites).clone(),
        downloads: download_list(&state.manager),
        downloads_dir: crate::manager::downloads_dir()
            .to_string_lossy()
            .into_owned(),
        data_dir: crate::manager::data_dir().to_string_lossy().into_owned(),
    }
}

/// Register remote poster URLs and return their `lanfetch-poster://`
/// srcs, positionally aligned with the input. Only URL strings cross
/// IPC — the bytes themselves travel as ordinary image loads through
/// the protocol handler (disk-cached by the engine).
#[tauri::command]
pub fn poster_srcs(state: State<'_, AppState>, urls: Vec<String>) -> Vec<String> {
    state.posters.register(&urls)
}

/// Probe every configured source in parallel (one thread per source —
/// the Rune UI probed serially, which made one dead server delay all
/// the others). Disabled sources are reported, not probed.
#[tauri::command]
pub async fn probe_sources(state: State<'_, AppState>) -> Result<Vec<SourceProbeDto>, String> {
    let sources: Vec<(String, String, bool)> = lock(&state.sources)
        .iter()
        .map(|s| (s.name.clone(), s.root_url.clone(), s.enabled))
        .collect();
    tauri::async_runtime::spawn_blocking(move || {
        let handles: Vec<_> = sources
            .into_iter()
            .map(|(name, root_url, enabled)| {
                std::thread::spawn(move || {
                    if !enabled {
                        return SourceProbeDto {
                            name,
                            root_url,
                            status: "disabled".to_string(),
                            latency_ms: None,
                        };
                    }
                    let source = core::Source {
                        name: name.clone(),
                        root_url: root_url.clone(),
                    };
                    match core::probe(&source) {
                        Ok(ms) => SourceProbeDto {
                            name,
                            root_url,
                            status: "up".to_string(),
                            latency_ms: Some(ms as u64),
                        },
                        Err(_) => SourceProbeDto {
                            name,
                            root_url,
                            status: "down".to_string(),
                            latency_ms: None,
                        },
                    }
                })
            })
            .collect();
        handles
            .into_iter()
            .map(|h| {
                h.join().unwrap_or_else(|_| SourceProbeDto {
                    name: String::new(),
                    root_url: String::new(),
                    status: "down".to_string(),
                    latency_ms: None,
                })
            })
            .collect::<Vec<_>>()
    })
    .await
    .map_err(|e| e.to_string())
}
