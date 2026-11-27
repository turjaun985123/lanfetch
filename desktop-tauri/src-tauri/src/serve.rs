//! Web mode — `lanfetch --serve [--port 8787] [--lan]`.
//!
//! The same engine, the same Astro UI, served over HTTP so the app
//! runs in a browser: on this machine, or (with --lan) on a phone
//! on the same network, remote-controlling the downloads that land
//! on this machine's disk. The desktop window and this server are
//! two front doors to one house: every handler below mirrors a
//! Tauri command in commands.rs over the shared engine, DTOs and
//! stores, and the frontend picks its transport (IPC or fetch) at
//! runtime — the UI code is identical.
//!
//! Deliberately dependency-light: a std-only HTTP server (thread
//! per connection), serde_json for bodies, SSE for the
//! downloads-changed event. Binding defaults to 127.0.0.1; --lan
//! exposes it to the local network with no authentication, the
//! same trust model as the LAN file servers this app browses —
//! the startup banner says so out loud.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use include_dir::{include_dir, Dir};
use serde_json::{json, Value};

use crate::commands::{AppState, CatalogState};
use crate::core;
use crate::dto::{
    BootstrapDto, CategoryDto, DownloadListDto, FeedPageDto, SettingsDto, SourceDto,
    SourceProbeDto, TaskDto, TitleDetailDto, TitleDto,
};
use crate::manager::{DlState, Manager};
use crate::poster_service;
use crate::store::SourceItem;

static DIST: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../dist");

const FEED_PAGE_SIZE: usize = 48;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

type Subscribers = Arc<Mutex<Vec<Sender<u64>>>>;

/// Entry point from main() when --serve is present. Never returns.
pub fn run(port: u16, lan: bool) -> ! {
    let manager = Manager::load();
    let subscribers: Subscribers = Arc::new(Mutex::new(Vec::new()));
    {
        let subs = subscribers.clone();
        let bridge = manager.clone();
        manager.set_notifier(Arc::new(move || {
            let stamp = bridge.stamp();
            lock(&subs).retain(|tx| tx.send(stamp).is_ok());
        }));
    }
    let state = Arc::new(AppState {
        manager,
        sources: Mutex::new(crate::store::load_sources()),
        favorites: Mutex::new(crate::store::load_favorites()),
        posters: Arc::new(poster_service::PosterService::new()),
        catalog: Arc::new(Mutex::new(CatalogState::default())),
    });

    let host = if lan { "0.0.0.0" } else { "127.0.0.1" };
    let listener = match TcpListener::bind((host, port)) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("LanFetch web mode: cannot listen on {host}:{port} — {e}");
            std::process::exit(1);
        }
    };
    println!("LanFetch web mode is running:");
    println!("  on this machine:  http://127.0.0.1:{port}");
    if lan {
        println!("  on your network:  http://<this-pc-ip>:{port}  (no password — anyone on the LAN can control downloads)");
    }
    println!("Press Ctrl+C to stop.");
    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let state = state.clone();
                let subscribers = subscribers.clone();
                std::thread::spawn(move || handle(stream, state, subscribers));
            }
            Err(_) => std::thread::sleep(Duration::from_millis(10)),
        }
    }
    std::process::exit(0);
}

// ------------------------------------------------------------ HTTP plumbing

struct Request {
    method: String,
    path: String,
    body: Vec<u8>,
}

fn read_request(stream: &mut TcpStream) -> Option<Request> {
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") && head.len() < 65_536 {
        match stream.read(&mut byte) {
            Ok(0) => return None,
            Ok(_) => head.push(byte[0]),
            Err(_) => return None,
        }
    }
    let head_text = String::from_utf8_lossy(&head).into_owned();
    let mut lines = head_text.lines();
    let mut first = lines.next()?.split_whitespace();
    let method = first.next()?.to_string();
    let path = first.next()?.to_string();
    let mut content_length = 0usize;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.trim().eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse().unwrap_or(0).min(1_048_576);
            }
        }
    }
    let mut body = vec![0u8; content_length];
    if content_length > 0 && stream.read_exact(&mut body).is_err() {
        return None;
    }
    Some(Request { method, path, body })
}

fn respond(stream: &mut TcpStream, status: &str, content_type: &str, body: &[u8]) {
    let head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

fn respond_json(stream: &mut TcpStream, status: &str, value: &Value) {
    respond(
        stream,
        status,
        "application/json",
        serde_json::to_string(value).unwrap_or_default().as_bytes(),
    );
}

fn handle(mut stream: TcpStream, state: Arc<AppState>, subscribers: Subscribers) {
    let _ = stream.set_nodelay(true);
    let Some(request) = read_request(&mut stream) else {
        return;
    };
    let path = request.path.split('?').next().unwrap_or("/").to_string();
    match (request.method.as_str(), path.as_str()) {
        ("GET", "/api/events") => serve_events(&mut stream, &subscribers),
        ("GET", _) if path.starts_with("/posters/") => {
            let key = path.trim_start_matches("/posters/");
            match state.posters.bytes_for(key) {
                Ok(bytes) => respond(
                    &mut stream,
                    "200 OK",
                    poster_service::content_type(&bytes),
                    &bytes,
                ),
                Err(_) => respond(
                    &mut stream,
                    "404 Not Found",
                    "text/plain",
                    b"no such poster",
                ),
            }
        }
        ("POST", _) if path.starts_with("/api/") => {
            let cmd = path.trim_start_matches("/api/").to_string();
            let args: Value = serde_json::from_slice(&request.body).unwrap_or(json!({}));
            match dispatch(&state, &cmd, &args) {
                Ok(value) => respond_json(&mut stream, "200 OK", &value),
                Err(message) => {
                    respond_json(&mut stream, "400 Bad Request", &json!({ "error": message }))
                }
            }
        }
        ("GET", _) => serve_static(&mut stream, &path),
        _ => respond(&mut stream, "405 Method Not Allowed", "text/plain", b""),
    }
}

fn serve_events(stream: &mut TcpStream, subscribers: &Subscribers) {
    let (tx, rx): (Sender<u64>, Receiver<u64>) = channel();
    lock(subscribers).push(tx);
    let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
    if stream.write_all(head.as_bytes()).is_err() {
        return;
    }
    loop {
        match rx.recv_timeout(Duration::from_secs(20)) {
            Ok(stamp) => {
                if stream
                    .write_all(format!("data: {stamp}\n\n").as_bytes())
                    .is_err()
                {
                    return;
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                if stream.write_all(b": ping\n\n").is_err() {
                    return;
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => return,
        }
        if stream.flush().is_err() {
            return;
        }
    }
}

fn serve_static(stream: &mut TcpStream, path: &str) {
    let rel = path.trim_start_matches('/');
    let rel = if rel.is_empty() { "index.html" } else { rel };
    if rel.contains("..") {
        respond(stream, "404 Not Found", "text/plain", b"not found");
        return;
    }
    let file = DIST.get_file(rel).or_else(|| {
        if rel.contains('.') {
            None
        } else {
            DIST.get_file("index.html")
        }
    });
    match file {
        Some(file) => respond(stream, "200 OK", static_content_type(rel), file.contents()),
        None => respond(stream, "404 Not Found", "text/plain", b"not found"),
    }
}

fn static_content_type(path: &str) -> &'static str {
    if path.ends_with(".html") {
        "text/html; charset=utf-8"
    } else if path.ends_with(".css") {
        "text/css"
    } else if path.ends_with(".js") || path.ends_with(".mjs") {
        "text/javascript"
    } else if path.ends_with(".json") {
        "application/json"
    } else if path.ends_with(".png") {
        "image/png"
    } else if path.ends_with(".jpg") || path.ends_with(".jpeg") {
        "image/jpeg"
    } else if path.ends_with(".svg") {
        "image/svg+xml"
    } else if path.ends_with(".ico") {
        "image/x-icon"
    } else if path.ends_with(".woff2") {
        "font/woff2"
    } else if path.ends_with(".woff") {
        "font/woff"
    } else if path.ends_with(".webp") {
        "image/webp"
    } else {
        "application/octet-stream"
    }
}

// ------------------------------------------------------------ arg helpers

fn arg_str(args: &Value, key: &str) -> Result<String, String> {
    args.get(key)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| format!("missing string argument: {key}"))
}

fn arg_u64(args: &Value, key: &str) -> Result<u64, String> {
    args.get(key)
        .and_then(Value::as_u64)
        .ok_or_else(|| format!("missing number argument: {key}"))
}

fn arg_bool(args: &Value, key: &str) -> bool {
    args.get(key).and_then(Value::as_bool).unwrap_or(false)
}

fn to_json<T: serde::Serialize>(value: T) -> Result<Value, String> {
    serde_json::to_value(value).map_err(|e| e.to_string())
}

// ------------------------------------------------------------ shared helpers

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

fn sources_dto(state: &AppState) -> Vec<SourceDto> {
    lock(&state.sources).iter().map(SourceDto::from).collect()
}

fn invalidate_catalog(state: &AppState) {
    let mut catalog = lock(&state.catalog);
    catalog.categories.clear();
    catalog.feed = None;
    catalog.generation += 1;
}

// ------------------------------------------------------------ API dispatch

fn dispatch(state: &AppState, cmd: &str, args: &Value) -> Result<Value, String> {
    match cmd {
        "get_bootstrap" => to_json(BootstrapDto {
            app_version: env!("CARGO_PKG_VERSION").to_string(),
            settings: SettingsDto::from(&state.manager.settings()),
            sources: sources_dto(state),
            favorites: lock(&state.favorites).clone(),
            downloads: download_list(&state.manager),
            downloads_dir: crate::manager::downloads_dir()
                .to_string_lossy()
                .into_owned(),
            data_dir: crate::manager::data_dir().to_string_lossy().into_owned(),
        }),
        "poster_srcs" => {
            let urls: Vec<String> = args
                .get("urls")
                .and_then(Value::as_array)
                .map(|a| {
                    a.iter()
                        .filter_map(Value::as_str)
                        .map(str::to_string)
                        .collect()
                })
                .unwrap_or_default();
            let srcs = state.posters.register(&urls);
            let web_srcs: Vec<String> = srcs
                .iter()
                .map(|s| match poster_service::parse_key(s) {
                    Some(key) => format!("/posters/{key}"),
                    None => s.clone(),
                })
                .collect();
            to_json(web_srcs)
        }
        "probe_sources" => to_json(probe_sources(state)),
        "get_categories" => to_json(get_categories(state, arg_bool(args, "refresh"))),
        "feed_open" => {
            let category = args
                .get("category")
                .and_then(Value::as_str)
                .map(str::to_string);
            to_json(feed_open(state, category)?)
        }
        "feed_next" => to_json(feed_next(state, arg_u64(args, "generation")?)),
        "search_titles" => to_json(search_titles(state, &arg_str(args, "query")?)),
        "get_title_detail" => to_json(get_title_detail(state, &arg_str(args, "folderUrl")?)?),
        "start_download" => {
            state
                .manager
                .enqueue(arg_str(args, "url")?, arg_str(args, "name")?);
            to_json(download_list(&state.manager))
        }
        "toggle_favorite" => to_json(toggle_favorite(state, arg_str(args, "folderUrl")?)),
        "get_favorites" => to_json(get_favorites(state)),
        "get_downloads" => to_json(download_list(&state.manager)),
        "pause_download" => {
            state.manager.pause(arg_u64(args, "id")?);
            to_json(download_list(&state.manager))
        }
        "resume_download" => {
            state.manager.start(arg_u64(args, "id")?);
            to_json(download_list(&state.manager))
        }
        "remove_download" => {
            let id = arg_u64(args, "id")?;
            let delete_part = {
                let tasks = lock(&state.manager.tasks);
                tasks
                    .iter()
                    .find(|t| t.id == id)
                    .map(|t| t.state != DlState::Completed)
                    .unwrap_or(false)
            };
            state.manager.remove(id, delete_part);
            to_json(download_list(&state.manager))
        }
        "clear_completed_downloads" => {
            state.manager.clear_completed();
            to_json(download_list(&state.manager))
        }
        "pause_all_downloads" => {
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
            to_json(download_list(&state.manager))
        }
        "resume_all_downloads" => {
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
            to_json(download_list(&state.manager))
        }
        "redownload_download" => to_json(redownload(state, arg_u64(args, "id")?)?),
        "update_settings" => {
            let settings = crate::store::Settings {
                connections: (arg_u64(args, "connections")? as u32).clamp(1, 32),
                max_concurrent: (arg_u64(args, "maxConcurrent")? as u32).clamp(1, 16),
                theme: arg_str(args, "theme")?,
                language: arg_str(args, "language")?,
                onboarding_done: arg_bool(args, "onboardingDone"),
            };
            state.manager.apply_settings(settings.clone());
            to_json(SettingsDto::from(&settings))
        }
        "add_source" => to_json(add_source(
            state,
            arg_str(args, "name")?,
            arg_str(args, "rootUrl")?,
        )?),
        "remove_source" => to_json(remove_source(state, arg_str(args, "name")?)?),
        "set_source_enabled" => to_json(set_source_enabled(
            state,
            arg_str(args, "name")?,
            arg_bool(args, "enabled"),
        )?),
        "check_update" => to_json(crate::catalog::check_desktop_update().ok().flatten()),
        _ => Err(format!("unknown command: {cmd}")),
    }
}

fn probe_sources(state: &AppState) -> Vec<SourceProbeDto> {
    let sources: Vec<(String, String, bool)> = lock(&state.sources)
        .iter()
        .map(|s| (s.name.clone(), s.root_url.clone(), s.enabled))
        .collect();
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
        .collect()
}

fn get_categories(state: &AppState, refresh: bool) -> Vec<CategoryDto> {
    if !refresh {
        let catalog = lock(&state.catalog);
        if !catalog.categories.is_empty() {
            return catalog.categories.iter().map(CategoryDto::from).collect();
        }
    }
    let sources = enabled_sources(state);
    if refresh {
        crate::catalog::clear_listing_cache();
    }
    let categories = crate::catalog::discover_categories(&sources);
    let dtos: Vec<CategoryDto> = categories.iter().map(CategoryDto::from).collect();
    let mut catalog = lock(&state.catalog);
    catalog.categories = categories;
    catalog.feed = None;
    catalog.generation += 1;
    dtos
}

fn feed_open(state: &AppState, category: Option<String>) -> Result<FeedPageDto, String> {
    let favorites = favorites_set(state);
    let mut catalog = lock(&state.catalog);
    if catalog.categories.is_empty() {
        return Err("categories are not loaded yet".to_string());
    }
    catalog.generation += 1;
    let generation = catalog.generation;
    let mut feed = crate::catalog::FeedSession::new(&catalog.categories, category.as_deref());
    let page = feed.next_page(FEED_PAGE_SIZE);
    for t in &page {
        catalog.titles.insert(t.folder_url.clone(), t.clone());
    }
    let titles = page
        .iter()
        .map(|t| TitleDto::new(t, favorites.contains(&t.folder_url)))
        .collect();
    let has_more = feed.has_more;
    catalog.feed = Some(feed);
    Ok(FeedPageDto {
        generation,
        titles,
        has_more,
        superseded: false,
    })
}

fn feed_next(state: &AppState, generation: u64) -> FeedPageDto {
    let favorites = favorites_set(state);
    let mut catalog = lock(&state.catalog);
    if generation != catalog.generation || catalog.feed.is_none() {
        return FeedPageDto {
            generation: catalog.generation,
            titles: Vec::new(),
            has_more: false,
            superseded: true,
        };
    }
    let page = {
        let feed = catalog.feed.as_mut().expect("checked above");
        feed.next_page(FEED_PAGE_SIZE)
    };
    for t in &page {
        catalog.titles.insert(t.folder_url.clone(), t.clone());
    }
    let has_more = catalog.feed.as_ref().map(|f| f.has_more).unwrap_or(false);
    let titles = page
        .iter()
        .map(|t| TitleDto::new(t, favorites.contains(&t.folder_url)))
        .collect();
    FeedPageDto {
        generation,
        titles,
        has_more,
        superseded: false,
    }
}

fn search_titles(state: &AppState, query: &str) -> Vec<TitleDto> {
    let sources = enabled_sources(state);
    let favorites = favorites_set(state);
    let results = crate::catalog::search(&sources, query);
    {
        let mut catalog = lock(&state.catalog);
        for t in &results {
            catalog.titles.insert(t.folder_url.clone(), t.clone());
        }
    }
    results
        .iter()
        .map(|t| TitleDto::new(t, favorites.contains(&t.folder_url)))
        .collect()
}

fn get_title_detail(state: &AppState, folder_url: &str) -> Result<TitleDetailDto, String> {
    let favorites = favorites_set(state);
    let title = {
        let catalog = lock(&state.catalog);
        catalog
            .titles
            .get(folder_url)
            .cloned()
            .ok_or_else(|| "title is not in the current session".to_string())?
    };
    let hydrated = crate::catalog::hydrate_and_load_files(title);
    {
        let mut catalog = lock(&state.catalog);
        catalog
            .titles
            .insert(hydrated.folder_url.clone(), hydrated.clone());
    }
    let files = hydrated
        .files
        .iter()
        .map(|f| crate::dto::FileDto::new(f, state.manager.task_by_url(&f.file_url)))
        .collect();
    Ok(TitleDetailDto {
        title: TitleDto::new(&hydrated, favorites.contains(&hydrated.folder_url)),
        files,
    })
}

fn toggle_favorite(state: &AppState, folder_url: String) -> bool {
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

fn get_favorites(state: &AppState) -> Vec<TitleDto> {
    let favorites = favorites_set(state);
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

fn redownload(state: &AppState, id: u64) -> Result<DownloadListDto, String> {
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

fn add_source(state: &AppState, name: String, root_url: String) -> Result<Vec<SourceDto>, String> {
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
    {
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
    }
    invalidate_catalog(state);
    Ok(sources_dto(state))
}

fn remove_source(state: &AppState, name: String) -> Result<Vec<SourceDto>, String> {
    {
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
    }
    invalidate_catalog(state);
    Ok(sources_dto(state))
}

fn set_source_enabled(
    state: &AppState,
    name: String,
    enabled: bool,
) -> Result<Vec<SourceDto>, String> {
    {
        let mut sources = lock(&state.sources);
        match sources.iter_mut().find(|s| s.name == name) {
            None => return Err("No such server.".to_string()),
            Some(s) => s.enabled = enabled,
        }
        crate::store::save_sources(&sources);
    }
    invalidate_catalog(state);
    Ok(sources_dto(state))
}
