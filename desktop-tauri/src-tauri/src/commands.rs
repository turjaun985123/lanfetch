//! Tauri commands — Wave 1 surface: bootstrap + server probes.
//! Catalog, poster and download commands land in their own waves.

use std::sync::Mutex;

use tauri::State;

use crate::core;
use crate::dto::{
    BootstrapDto, CategoryDto, DownloadListDto, FeedPageDto, FileDto, SettingsDto, SourceDto,
    SourceProbeDto, TaskDto, TitleDetailDto, TitleDto,
};
use crate::manager::{DlState, Manager};
use crate::store::SourceItem;

/// App and catalog state live in the engine crate (shared with
/// the web binary); re-exported here so the command surface keeps
/// its original paths.
pub use lanfetch_engine::state::{AppState, CatalogState};

const FEED_PAGE_SIZE: usize = 48;

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

// ------------------------------------------------------------ catalog feed

fn enabled_sources(state: &AppState) -> Vec<(String, String)> {
    lock(&state.sources)
        .iter()
        .filter(|s| s.enabled)
        .map(|s| (s.name.clone(), s.root_url.clone()))
        .collect()
}

fn favorites_set(state: &AppState) -> std::collections::HashSet<String> {
    lock(&state.favorites).iter().cloned().collect()
}

/// Discover the category list across all enabled sources (parallel
/// per source inside the engine). Cached after the first call;
/// `refresh = true` rediscovers and drops the listing cache, which
/// is what a manual refresh means.
#[tauri::command]
pub async fn get_categories(
    state: State<'_, AppState>,
    refresh: bool,
) -> Result<Vec<CategoryDto>, String> {
    if !refresh {
        let catalog = lock(&state.catalog);
        if !catalog.categories.is_empty() {
            return Ok(catalog.categories.iter().map(CategoryDto::from).collect());
        }
    }
    let sources = enabled_sources(&state);
    let catalog_state = state.catalog.clone();
    tauri::async_runtime::spawn_blocking(move || {
        if refresh {
            crate::catalog::clear_listing_cache();
        }
        let categories = crate::catalog::discover_categories(&sources);
        let dtos: Vec<CategoryDto> = categories.iter().map(CategoryDto::from).collect();
        let mut catalog = lock(&catalog_state);
        catalog.categories = categories;
        catalog.feed = None;
        catalog.generation += 1;
        dtos
    })
    .await
    .map_err(|e| e.to_string())
}

/// Open a feed (one category, or None for all media categories) and
/// return its first page. Always supersedes any previous feed.
#[tauri::command]
pub async fn feed_open(
    state: State<'_, AppState>,
    category: Option<String>,
) -> Result<FeedPageDto, String> {
    let catalog_state = state.catalog.clone();
    let favorites = favorites_set(&state);
    tauri::async_runtime::spawn_blocking(move || {
        // Build the session and bump the generation under the
        // lock, then crawl UNLOCKED (user-reported wedge,
        // 2026-11-27: the crawl used to run with the catalog mutex
        // held, freezing every other catalog call behind one slow
        // server; see web serve.rs feed_open).
        let (mut feed, generation) = {
            let mut catalog = lock(&catalog_state);
            if catalog.categories.is_empty() {
                return Err("categories are not loaded yet".to_string());
            }
            catalog.generation += 1;
            (
                crate::catalog::FeedSession::new(&catalog.categories, category.as_deref()),
                catalog.generation,
            )
        };
        let page = feed.next_page(FEED_PAGE_SIZE);
        let has_more = feed.has_more;
        let mut catalog = lock(&catalog_state);
        if catalog.generation != generation {
            // Superseded by a newer feed_open while crawling:
            // drop the stale page.
            return Ok(FeedPageDto {
                generation: catalog.generation,
                titles: Vec::new(),
                has_more: false,
                superseded: true,
            });
        }
        for t in &page {
            catalog.titles.insert(t.folder_url.clone(), t.clone());
        }
        let titles = page
            .iter()
            .map(|t| TitleDto::new(t, favorites.contains(&t.folder_url)))
            .collect();
        catalog.feed = Some(feed);
        Ok(FeedPageDto {
            generation,
            titles,
            has_more,
            superseded: false,
        })
    })
    .await
    .map_err(|e| e.to_string())?
}

/// The next page of the feed identified by `generation`. A stale
/// generation is not an error: it returns an empty superseded page
/// so the caller can simply drop it.
#[tauri::command]
pub async fn feed_next(state: State<'_, AppState>, generation: u64) -> Result<FeedPageDto, String> {
    let catalog_state = state.catalog.clone();
    let favorites = favorites_set(&state);
    tauri::async_runtime::spawn_blocking(move || {
        // Take the session OUT so the crawl runs unlocked; it
        // goes back only if the generation is still current.
        let mut feed = {
            let mut catalog = lock(&catalog_state);
            if generation != catalog.generation || catalog.feed.is_none() {
                return FeedPageDto {
                    generation: catalog.generation,
                    titles: Vec::new(),
                    has_more: false,
                    superseded: true,
                };
            }
            catalog.feed.take().expect("checked above")
        };
        let page = feed.next_page(FEED_PAGE_SIZE);
        let has_more = feed.has_more;
        let mut catalog = lock(&catalog_state);
        if catalog.generation != generation {
            return FeedPageDto {
                generation: catalog.generation,
                titles: Vec::new(),
                has_more: false,
                superseded: true,
            };
        }
        for t in &page {
            catalog.titles.insert(t.folder_url.clone(), t.clone());
        }
        let titles = page
            .iter()
            .map(|t| TitleDto::new(t, favorites.contains(&t.folder_url)))
            .collect();
        catalog.feed = Some(feed);
        FeedPageDto {
            generation,
            titles,
            has_more,
            superseded: false,
        }
    })
    .await
    .map_err(|e| e.to_string())
}

// ------------------------------------------------------------ detail + downloads

/// Hydrate one title (poster resolution + full file list) from the
/// session title cache the feed maintains. The hydrated title is
/// written back, so reopening the same title is cheap.
#[tauri::command]
pub async fn get_title_detail(
    state: State<'_, AppState>,
    folder_url: String,
) -> Result<TitleDetailDto, String> {
    let catalog_state = state.catalog.clone();
    let manager = state.manager.clone();
    let favorites = favorites_set(&state);
    let title = {
        let catalog = lock(&catalog_state);
        catalog
            .titles
            .get(&folder_url)
            .cloned()
            .ok_or_else(|| "title is not in the current session".to_string())?
    };
    let hydrated =
        tauri::async_runtime::spawn_blocking(move || crate::catalog::hydrate_and_load_files(title))
            .await
            .map_err(|e| e.to_string())?;
    {
        let mut catalog = lock(&catalog_state);
        catalog
            .titles
            .insert(hydrated.folder_url.clone(), hydrated.clone());
    }
    let files = hydrated
        .files
        .iter()
        .map(|f| FileDto::new(f, manager.task_by_url(&f.file_url)))
        .collect();
    Ok(TitleDetailDto {
        title: TitleDto::new(&hydrated, favorites.contains(&hydrated.folder_url)),
        files,
    })
}

/// Enqueue a file for download and return the fresh download list,
/// so the caller can update Transfers and the nav badge from the
/// same round trip.
#[tauri::command]
pub fn start_download(
    state: State<'_, AppState>,
    url: String,
    name: String,
) -> Result<DownloadListDto, String> {
    state.manager.enqueue(url, name);
    Ok(download_list(&state.manager))
}

/// Flip a title's favorite state and persist it. Returns the new
/// state (true = now a favorite). The display snapshot store is
/// maintained alongside the URL list: favoriting captures the
/// session title (the toggle lives on the detail screen, where the
/// full Title is always in the session cache), unfavoriting drops
/// the snapshot again.
#[tauri::command]
pub fn toggle_favorite(state: State<'_, AppState>, folder_url: String) -> bool {
    let now_favorite = {
        let mut favorites = lock(&state.favorites);
        if let Some(pos) = favorites.iter().position(|u| u == &folder_url) {
            favorites.remove(pos);
            false
        } else {
            favorites.push(folder_url.clone());
            true
        }
    };
    {
        let favorites = lock(&state.favorites);
        crate::store::save_favorites(&favorites);
    }
    let mut snapshots = crate::store::load_favorite_titles();
    snapshots.retain(|s| s.folder_url != folder_url);
    if now_favorite {
        let catalog = lock(&state.catalog);
        if let Some(title) = catalog.titles.get(&folder_url) {
            snapshots.push(crate::store::FavTitle::from(title));
        }
    }
    crate::store::save_favorite_titles(&snapshots);
    now_favorite
}

/// The Favorites view's titles, from the persisted snapshots
/// (sorted by name). Each is also re-registered into the session
/// title cache as a minimal Title, so opening its detail hydrates
/// files from the server exactly like a feed card.
#[tauri::command]
pub fn get_favorites(state: State<'_, AppState>) -> Vec<TitleDto> {
    let favorites = favorites_set(&state);
    let mut snapshots: Vec<crate::store::FavTitle> = crate::store::load_favorite_titles()
        .into_iter()
        .filter(|s| favorites.contains(&s.folder_url))
        .collect();
    snapshots.sort_by_key(|a| a.title.to_lowercase());
    {
        let mut catalog = lock(&state.catalog);
        for s in &snapshots {
            catalog
                .titles
                .entry(s.folder_url.clone())
                .or_insert_with(|| crate::catalog::Title {
                    folder_url: s.folder_url.clone(),
                    source_name: s.source_name.clone(),
                    category: s.category.clone(),
                    title: s.title.clone(),
                    raw_name: s.raw_name.clone(),
                    year: s.year,
                    kind: s.kind.clone(),
                    poster_url: s.poster_url.clone(),
                    language: s.language.clone(),
                    files: Vec::new(),
                    files_loaded: false,
                    season_count: s.season_count,
                    max_size: 0,
                    best_quality: s.best_quality.clone(),
                    best_source_tag: None,
                    best_codec: None,
                    dual_audio: false,
                    copies: 1,
                    fav_any: true,
                });
        }
    }
    snapshots
        .iter()
        .map(|s| TitleDto {
            folder_url: s.folder_url.clone(),
            source_name: s.source_name.clone(),
            category: s.category.clone(),
            title: s.title.clone(),
            year: s.year,
            kind: s.kind.clone(),
            poster_url: s.poster_url.clone(),
            language: s.language.clone(),
            season_count: s.season_count,
            best_quality: s.best_quality.clone(),
            copies: 1,
            is_favorite: true,
        })
        .collect()
}

// ------------------------------------------------------------ transfers control

/// The current download list (the event bridge's refetch target).
#[tauri::command]
pub fn get_downloads(state: State<'_, AppState>) -> DownloadListDto {
    download_list(&state.manager)
}

#[tauri::command]
pub fn pause_download(state: State<'_, AppState>, id: u64) -> DownloadListDto {
    state.manager.pause(id);
    download_list(&state.manager)
}

/// Resume a paused task, or retry a failed one — Manager::start
/// covers both.
#[tauri::command]
pub fn resume_download(state: State<'_, AppState>, id: u64) -> DownloadListDto {
    state.manager.start(id);
    download_list(&state.manager)
}

/// Remove a task. The partial file is deleted too, except for
/// completed downloads whose finished file is never touched.
#[tauri::command]
pub fn remove_download(state: State<'_, AppState>, id: u64) -> DownloadListDto {
    let delete_part = {
        let tasks = lock(&state.manager.tasks);
        tasks
            .iter()
            .find(|t| t.id == id)
            .map(|t| t.state != DlState::Completed)
            .unwrap_or(false)
    };
    state.manager.remove(id, delete_part);
    download_list(&state.manager)
}

#[tauri::command]
pub fn clear_completed_downloads(state: State<'_, AppState>) -> DownloadListDto {
    state.manager.clear_completed();
    download_list(&state.manager)
}

// ------------------------------------------------------------ search

/// Search every enabled source (the engine fans out per source with
/// a hard deadline and returns partial results rather than hanging
/// on a dead server). Results join the session title cache, so the
/// detail screen works from search exactly as from the feed.
#[tauri::command]
pub async fn search_titles(
    state: State<'_, AppState>,
    query: String,
) -> Result<Vec<TitleDto>, String> {
    let sources = enabled_sources(&state);
    let favorites = favorites_set(&state);
    let catalog_state = state.catalog.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let results = crate::catalog::search(&sources, &query);
        {
            let mut catalog = lock(&catalog_state);
            for t in &results {
                catalog.titles.insert(t.folder_url.clone(), t.clone());
            }
        }
        results
            .iter()
            .map(|t| TitleDto::new(t, favorites.contains(&t.folder_url)))
            .collect::<Vec<_>>()
    })
    .await
    .map_err(|e| e.to_string())
}

// ------------------------------------------------------------ sources + settings

fn sources_dto(state: &AppState) -> Vec<SourceDto> {
    lock(&state.sources).iter().map(SourceDto::from).collect()
}

/// Source changes make the discovered catalog stale: drop it so the
/// next Library visit rediscovers against the new server set.
fn invalidate_catalog(state: &AppState) {
    let mut catalog = lock(&state.catalog);
    catalog.categories.clear();
    catalog.feed = None;
    catalog.generation += 1;
}

/// Apply engine settings (persisted by the manager, effective
/// immediately — running downloads keep their lanes, new ones use
/// the new values). Out-of-range numbers are clamped to the
/// engine's supported span rather than rejected.
#[tauri::command]
pub fn update_settings(
    state: State<'_, AppState>,
    connections: u32,
    max_concurrent: u32,
    theme: String,
    language: String,
    onboarding_done: bool,
) -> SettingsDto {
    let settings = crate::store::Settings {
        connections: connections.clamp(1, 32),
        max_concurrent: max_concurrent.clamp(1, 16),
        theme,
        language,
        onboarding_done,
    };
    state.manager.apply_settings(settings.clone());
    SettingsDto::from(&settings)
}

/// The web API access token (ADR-003): Settings shows it so the
/// user can pair another device with the web edition, and offers
/// regeneration — which locks out every previously paired
/// device. Reading creates the token on first use.
#[tauri::command]
pub fn get_web_token() -> String {
    crate::store::load_or_create_web_token()
}

#[tauri::command]
pub fn regenerate_web_token() -> String {
    crate::store::regenerate_web_token()
}

#[tauri::command]
pub fn add_source(
    state: State<'_, AppState>,
    name: String,
    root_url: String,
) -> Result<Vec<SourceDto>, String> {
    let name = name.trim().to_string();
    let mut root_url = root_url.trim().to_string();
    if name.is_empty() {
        return Err("Give the server a name.".to_string());
    }
    if !(root_url.starts_with("http://") || root_url.starts_with("https://")) {
        return Err("The URL must start with http:// or https://".to_string());
    }
    if !root_url.ends_with('/') {
        root_url.push('/');
    }
    let mut sources = lock(&state.sources);
    if sources.iter().any(|s| s.name.eq_ignore_ascii_case(&name)) {
        return Err("A server with this name already exists.".to_string());
    }
    sources.push(SourceItem {
        name,
        root_url,
        enabled: true,
        custom: true,
    });
    crate::store::save_sources(&sources);
    drop(sources);
    invalidate_catalog(&state);
    Ok(sources_dto(&state))
}

#[tauri::command]
pub fn remove_source(state: State<'_, AppState>, name: String) -> Result<Vec<SourceDto>, String> {
    let mut sources = lock(&state.sources);
    match sources.iter().find(|s| s.name == name) {
        None => return Err("No such server.".to_string()),
        Some(s) if !s.custom => {
            return Err("Built-in servers can be disabled, not removed.".to_string());
        }
        _ => {}
    }
    sources.retain(|s| s.name != name);
    crate::store::save_sources(&sources);
    drop(sources);
    invalidate_catalog(&state);
    Ok(sources_dto(&state))
}

#[tauri::command]
pub fn set_source_enabled(
    state: State<'_, AppState>,
    name: String,
    enabled: bool,
) -> Result<Vec<SourceDto>, String> {
    let mut sources = lock(&state.sources);
    match sources.iter_mut().find(|s| s.name == name) {
        None => return Err("No such server.".to_string()),
        Some(s) => s.enabled = enabled,
    }
    crate::store::save_sources(&sources);
    drop(sources);
    invalidate_catalog(&state);
    Ok(sources_dto(&state))
}

#[tauri::command]
pub fn pause_all_downloads(state: State<'_, AppState>) -> DownloadListDto {
    let ids: Vec<u64> = {
        let tasks = lock(&state.manager.tasks);
        tasks
            .iter()
            .filter(|t| matches!(t.state, DlState::Running | DlState::Queued))
            .map(|t| t.id)
            .collect()
    };
    for id in ids {
        state.manager.pause(id);
    }
    download_list(&state.manager)
}

#[tauri::command]
pub fn resume_all_downloads(state: State<'_, AppState>) -> DownloadListDto {
    let ids: Vec<u64> = {
        let tasks = lock(&state.manager.tasks);
        tasks
            .iter()
            .filter(|t| t.state == DlState::Paused)
            .map(|t| t.id)
            .collect()
    };
    for id in ids {
        state.manager.start(id);
    }
    download_list(&state.manager)
}

/// Redownload a completed task whose file has left the downloads
/// folder: the stale task is dropped and the same URL is enqueued
/// fresh. Refuses (as an error the UI shows) when the file is still
/// there — redownloading over an existing file is never implicit.
#[tauri::command]
pub fn redownload_download(state: State<'_, AppState>, id: u64) -> Result<DownloadListDto, String> {
    let (url, name) = {
        let tasks = lock(&state.manager.tasks);
        let task = tasks
            .iter()
            .find(|t| t.id == id)
            .ok_or("no such download")?;
        if task.state != DlState::Completed {
            return Err("only a completed download can be redownloaded".to_string());
        }
        if crate::manager::downloads_dir().join(&task.name).exists() {
            return Err("the file is still in the downloads folder".to_string());
        }
        (task.url.clone(), task.name.clone())
    };
    state.manager.remove(id, false);
    state.manager.enqueue(url, name);
    Ok(download_list(&state.manager))
}

/// The newer desktop release's version string, if one exists
/// (engine checks the project's GitHub releases for the
/// desktop-tauri-v prefix). Errors collapse to None: an offline
/// check is silent, never a nag.
#[tauri::command]
pub async fn check_update() -> Option<String> {
    tauri::async_runtime::spawn_blocking(crate::catalog::check_desktop_update)
        .await
        .ok()
        .and_then(|r| r.ok())
        .flatten()
}
