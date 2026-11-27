//! LanFetch Web server — `lanfetch-web [--port 8787] [--lan]`.
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
//! downloads-changed event. Binding defaults to 127.0.0.1, where a
//! Host allowlist is the guard (ADR-003). With --lan the server
//! binds all interfaces and every /api/* call requires the
//! install's Bearer token (engine store `web-token`, 0600): the
//! startup banner prints a one-time pairing URL whose #t=
//! fragment the page captures into sessionStorage. The versioned
//! API lives under /api/v1/* (docs/API.md) and is implemented by
//! mapping each route onto the same dispatch the legacy routes
//! use — one business-logic path, two envelopes. Connections are
//! capped and timed out (docs/SECURITY.md S2/S3).

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use include_dir::{include_dir, Dir};
use serde_json::{json, Value};

use lanfetch_engine::core;
use lanfetch_engine::dto::{
    BootstrapDto, CategoryDto, DownloadListDto, FeedPageDto, SettingsDto, SourceDto,
    SourceProbeDto, TaskDto, TitleDetailDto, TitleDto,
};
use lanfetch_engine::manager::{DlState, Manager};
use lanfetch_engine::poster_service;
use lanfetch_engine::state::{AppState, CatalogState};
use lanfetch_engine::store::SourceItem;

static DIST: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../dist");

const FEED_PAGE_SIZE: usize = 48;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// SSE subscribers, each with a unique id so a stream removes
/// exactly its own registration when it ends (audit: dead
/// senders used to be pruned only when a download stamp fired,
/// so quiet-period disconnects leaked slots until /api/events
/// wedged at 503).
type Subscribers = Arc<Mutex<Vec<(u64, Sender<u64>)>>>;

static NEXT_SUBSCRIBER: AtomicU64 = AtomicU64::new(1);

/// Hard caps (SECURITY.md S3): total in-flight connections and SSE
/// subscribers. Excess connections get a fast 503, never an
/// unbounded thread spawn.
const MAX_CONN: usize = 32;
const MAX_SSE: usize = 8;

/// Per-connection guard: the accept loop reserves a slot before
/// spawning and the handler thread releases it on exit.
struct ConnSlot(Arc<AtomicUsize>);

impl Drop for ConnSlot {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// Runtime security configuration for the server (ADR-003).
struct ServerCfg {
    lan: bool,
    token: String,
    version: String,
}

/// The machine's primary LAN address, learned without sending any
/// traffic (a UDP `connect` only selects a route). Used for the
/// pairing URL in the startup banner.
fn primary_lan_ip() -> Option<std::net::IpAddr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|a| a.ip())
}

/// Entry point from main() when --serve is present. Never returns.
pub fn run(port: u16, lan: bool) -> ! {
    let manager = Manager::load();
    let subscribers: Subscribers = Arc::new(Mutex::new(Vec::new()));
    {
        let subs = subscribers.clone();
        let bridge = manager.clone();
        manager.set_notifier(Arc::new(move || {
            let stamp = bridge.stamp();
            lock(&subs).retain(|(_, tx)| tx.send(stamp).is_ok());
        }));
    }
    let state = Arc::new(AppState {
        manager,
        sources: Mutex::new(lanfetch_engine::store::load_sources()),
        favorites: Mutex::new(lanfetch_engine::store::load_favorites()),
        posters: Arc::new(poster_service::PosterService::new()),
        catalog: Arc::new(Mutex::new(CatalogState::default())),
    });

    let cfg = Arc::new(ServerCfg {
        lan,
        token: lanfetch_engine::store::load_or_create_web_token(),
        version: env!("CARGO_PKG_VERSION").to_string(),
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
        let ip = primary_lan_ip()
            .map(|ip| ip.to_string())
            .unwrap_or_else(|| "<this-pc-ip>".to_string());
        println!("  on your network:  http://{ip}:{port}/#t={}", cfg.token);
        println!("    Open that pairing link once on each device you want to");
        println!("    allow. Traffic is plain HTTP: anyone on this network can");
        println!("    see it, but only the token can control downloads. Reset");
        println!("    the token from the desktop app's Settings if it leaks.");
    }
    println!("Press Ctrl+C to stop.");
    let conn_slots = Arc::new(AtomicUsize::new(0));
    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                if conn_slots.fetch_add(1, Ordering::SeqCst) >= MAX_CONN {
                    conn_slots.fetch_sub(1, Ordering::SeqCst);
                    respond(
                        &mut stream,
                        "503 Service Unavailable",
                        "text/plain",
                        b"server busy",
                    );
                    continue;
                }
                let slot = ConnSlot(conn_slots.clone());
                let state = state.clone();
                let subscribers = subscribers.clone();
                let cfg = cfg.clone();
                std::thread::spawn(move || {
                    let _slot = slot;
                    handle(stream, state, subscribers, cfg);
                });
            }
            Err(_) => std::thread::sleep(Duration::from_millis(10)),
        }
    }
    std::process::exit(0);
}

// ------------------------------------------------------------ HTTP plumbing

struct Request {
    method: String,
    /// Path only (query stripped).
    path: String,
    /// Raw query string ("" when absent).
    query: String,
    /// (lowercased name, value) pairs in arrival order.
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl Request {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }
}

fn read_request(stream: &mut TcpStream) -> Option<Request> {
    // The whole head must arrive within 30 s (SECURITY.md S3): the
    // per-read socket timeout alone would let a client dribble one
    // byte per timeout window forever.
    let started = Instant::now();
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") && head.len() < 65_536 {
        if started.elapsed() > Duration::from_secs(30) {
            return None;
        }
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
    let target = first.next()?.to_string();
    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p.to_string(), q.to_string()),
        None => (target, String::new()),
    };
    let mut content_length = 0usize;
    let mut headers = Vec::new();
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            let name = name.trim().to_lowercase();
            if name == "content-length" {
                content_length = value.trim().parse().unwrap_or(0).min(1_048_576);
            }
            headers.push((name, value.trim().to_string()));
        }
    }
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        // The body gets the same whole-transfer deadline as the
        // head (audit): read_exact alone is bounded only by the
        // per-read socket timeout, so a client dribbling one byte
        // per timeout window could hold one of the 32 connection
        // slots indefinitely — before auth is even checked.
        let body_started = Instant::now();
        let mut filled = 0;
        while filled < content_length {
            if body_started.elapsed() > Duration::from_secs(30) {
                return None;
            }
            match stream.read(&mut body[filled..]) {
                Ok(0) => return None,
                Ok(n) => filled += n,
                Err(_) => return None,
            }
        }
    }
    Some(Request {
        method,
        path,
        query,
        headers,
        body,
    })
}

/// The CSP served with the page in web mode: the Tauri shell's
/// policy (tauri.conf.json) minus its IPC/poster schemes, which a
/// browser never uses (SECURITY.md S6).
const WEB_CSP: &str = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'";

fn respond(stream: &mut TcpStream, status: &str, content_type: &str, body: &[u8]) {
    // Security headers on every response (SECURITY.md S6).
    let mut head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n",
        body.len()
    );
    if content_type.starts_with("application/json") {
        head.push_str("Cache-Control: no-store\r\n");
    }
    if content_type.starts_with("text/html") {
        head.push_str(&format!("Content-Security-Policy: {WEB_CSP}\r\n"));
    }
    head.push_str("\r\n");
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

/// DNS-rebinding guard (SECURITY.md S7): the Host header must name
/// this machine. Allowed: `localhost`, loopback literals, `*.local`
/// mDNS names, and IP literals — a rebinding attack needs a public
/// domain name resolving to a local address, and none of these is
/// one. Everything else (including a missing Host) is rejected.
fn host_allowed(host: Option<&str>) -> bool {
    let Some(host) = host.map(str::trim) else {
        return false;
    };
    if host.is_empty() {
        return false;
    }
    let name = if let Some(rest) = host.strip_prefix('[') {
        // Bracketed IPv6 literal, optionally with a port.
        match rest.split_once(']') {
            Some((inner, _)) => inner,
            None => return false,
        }
    } else {
        match host.rsplit_once(':') {
            Some((h, p)) if !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()) => h,
            _ => host,
        }
    };
    if name.eq_ignore_ascii_case("localhost") || name == "127.0.0.1" || name == "::1" {
        return true;
    }
    if name.len() > 6
        && name.to_lowercase().ends_with(".local")
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
    {
        return true;
    }
    name.parse::<std::net::Ipv4Addr>().is_ok()
}

/// Constant-time token comparison: the response time must not
/// leak how much of a guessed token was right.
fn token_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

/// Authorization in --lan mode (ADR-003): `Authorization: Bearer
/// <token>` on any /api/* call. The single exception is the SSE
/// stream, where EventSource cannot set headers, so `?token=` is
/// accepted there — and only there.
/// The token the server currently honors. Regeneration
/// (Settings, in either edition) replaces the token file;
/// reading it back here makes a regeneration take effect
/// immediately rather than at the next server restart — a
/// revoked pairing must stop working when the user revokes it.
/// Falls back to the startup snapshot if the file is somehow
/// unreadable.
fn expected_token(cfg: &ServerCfg) -> String {
    lanfetch_engine::store::current_web_token().unwrap_or_else(|| cfg.token.clone())
}

fn authorized(request: &Request, cfg: &ServerCfg) -> bool {
    let expected = expected_token(cfg);
    if let Some(h) = request.header("authorization") {
        if let Some(t) = h.strip_prefix("Bearer ") {
            return token_eq(t.trim(), &expected);
        }
    }
    if request.path == "/api/events" || request.path == "/api/v1/events" {
        if let Some(t) = query_param(&request.query, "token") {
            return token_eq(&t, &expected);
        }
    }
    false
}

/// One query parameter, percent-decoded (query strings use form
/// encoding, so `+` is a space here — unlike path segments).
fn query_param(query: &str, key: &str) -> Option<String> {
    for pair in query.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            if k == key {
                return Some(core::percent_decode(v));
            }
        }
    }
    None
}

/// Strict percent-decoding for path segments: `%XX` decodes, `+`
/// stays a literal plus.
fn pct_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = |b: u8| -> Option<u8> {
                match b {
                    b'0'..=b'9' => Some(b - b'0'),
                    b'a'..=b'f' => Some(b - b'a' + 10),
                    b'A'..=b'F' => Some(b - b'A' + 10),
                    _ => None,
                }
            };
            if let (Some(hi), Some(lo)) = (hex(bytes[i + 1]), hex(bytes[i + 2])) {
                out.push(hi * 16 + lo);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn handle(
    mut stream: TcpStream,
    state: Arc<AppState>,
    subscribers: Subscribers,
    cfg: Arc<ServerCfg>,
) {
    let _ = stream.set_nodelay(true);
    // SECURITY.md S3: no connection may wait forever on a peer.
    let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
    let Some(request) = read_request(&mut stream) else {
        return;
    };
    if !host_allowed(request.header("host")) {
        respond(&mut stream, "403 Forbidden", "text/plain", b"bad host");
        return;
    }
    let path = request.path.as_str();
    // CSRF guard for both modes: API mutations must declare a JSON
    // content type. A cross-origin browser page can only send
    // "simple" requests (text/plain, form encodings) without a
    // preflight, and this server never answers preflights — so a
    // drive-by page cannot forge a call even to the tokenless
    // loopback bind.
    if matches!(request.method.as_str(), "POST" | "PUT" | "PATCH") && path.starts_with("/api/") {
        let json_ct = request
            .header("content-type")
            .map(|v| v.starts_with("application/json"))
            .unwrap_or(false);
        if !json_ct {
            respond_json(
                &mut stream,
                "415 Unsupported Media Type",
                &json!({ "error": "expected an application/json request" }),
            );
            return;
        }
    }
    // ADR-003: in --lan mode every /api/* route except the health
    // probe requires the token — reads included, because reads
    // disclose the library and the queue.
    if cfg.lan
        && path.starts_with("/api/")
        && path != "/api/v1/health"
        && !authorized(&request, &cfg)
    {
        if path.starts_with("/api/v1/") {
            respond_json(
                &mut stream,
                "401 Unauthorized",
                &json!({ "error": { "code": "unauthorized", "message": "missing or invalid access token", "retryable": false } }),
            );
        } else {
            respond_json(
                &mut stream,
                "401 Unauthorized",
                &json!({ "error": "unauthorized" }),
            );
        }
        return;
    }
    match (request.method.as_str(), path) {
        ("GET", "/api/events") | ("GET", "/api/v1/events") => {
            if lock(&subscribers).len() >= MAX_SSE {
                respond(
                    &mut stream,
                    "503 Service Unavailable",
                    "text/plain",
                    b"too many event subscribers",
                );
                return;
            }
            serve_events(&mut stream, &subscribers)
        }
        ("GET", _) if path.starts_with("/api/v1/") => route_v1(&mut stream, &state, &cfg, &request),
        ("POST" | "PUT" | "PATCH" | "DELETE", _) if path.starts_with("/api/v1/") => {
            route_v1(&mut stream, &state, &cfg, &request)
        }
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
            // A malformed body is a client error with its own
            // message — never silently treated as {} (Wave 0).
            let args: Value = if request.body.is_empty() {
                json!({})
            } else {
                match serde_json::from_slice(&request.body) {
                    Ok(v) => v,
                    Err(_) => {
                        respond_json(
                            &mut stream,
                            "400 Bad Request",
                            &json!({ "error": "malformed JSON body" }),
                        );
                        return;
                    }
                }
            };
            match dispatch(&state, &cmd, &args) {
                Ok(value) => respond_json(&mut stream, "200 OK", &value),
                Err(message) => {
                    respond_json(&mut stream, "400 Bad Request", &json!({ "error": message }))
                }
            }
        }
        ("GET", _) => serve_static(&mut stream, path),
        _ => respond(&mut stream, "405 Method Not Allowed", "text/plain", b""),
    }
}

// ------------------------------------------------------------ API v1
//
// The versioned API (docs/API.md). Every route maps onto the same
// `dispatch` the legacy POST /api/<cmd> routes use, so behavior
// cannot drift between envelopes; v1 adds REST structure, typed
// errors, and the frozen error shape. New capability is added
// here (and in dispatch) — the legacy surface stays frozen.

struct V1Error {
    status: &'static str,
    code: &'static str,
    message: String,
}

fn v1_fail(status: &'static str, code: &'static str, message: impl Into<String>) -> V1Error {
    V1Error {
        status,
        code,
        message: message.into(),
    }
}

/// Run one legacy command and translate its free-text error into
/// the v1 taxonomy. The legacy strings are stable enough to key
/// on: missing-argument and validation errors are the client's
/// (400), "no such …" is a missing resource (404).
fn v1_dispatch(state: &AppState, cmd: &str, args: &Value) -> Result<Value, V1Error> {
    dispatch(state, cmd, args).map_err(|message| {
        let lower = message.to_lowercase();
        let (status, code) = if message.starts_with("missing") {
            ("400 Bad Request", "bad_request")
        } else if message.starts_with("unknown command") {
            ("400 Bad Request", "unknown_command")
        } else if lower.contains("no such") || lower.contains("not in the current session") {
            ("404 Not Found", "not_found")
        } else {
            ("400 Bad Request", "bad_request")
        };
        V1Error {
            status,
            code,
            message,
        }
    })
}

fn route_v1(stream: &mut TcpStream, state: &AppState, cfg: &ServerCfg, request: &Request) {
    match v1_handle(state, cfg, request) {
        Ok(value) => respond_json(stream, "200 OK", &value),
        Err(e) => respond_json(
            stream,
            e.status,
            &json!({ "error": { "code": e.code, "message": e.message, "retryable": false } }),
        ),
    }
}

fn v1_handle(state: &AppState, cfg: &ServerCfg, request: &Request) -> Result<Value, V1Error> {
    let rest = request.path.strip_prefix("/api/v1/").unwrap_or("");
    let segs: Vec<&str> = rest.split('/').filter(|s| !s.is_empty()).collect();
    let body: Value = if request.body.is_empty() {
        json!({})
    } else {
        serde_json::from_slice(&request.body)
            .map_err(|_| v1_fail("400 Bad Request", "bad_request", "malformed JSON body"))?
    };
    let id_arg = |s: &str| -> Result<Value, V1Error> {
        s.parse::<u64>()
            .map(|id| json!({ "id": id }))
            .map_err(|_| v1_fail("400 Bad Request", "bad_request", "invalid numeric id"))
    };
    match (request.method.as_str(), segs.as_slice()) {
        ("GET", ["health"]) => Ok(json!({
            "status": "ok",
            "appVersion": cfg.version,
            "apiVersion": 1,
        })),
        ("GET", ["bootstrap"]) => v1_dispatch(state, "get_bootstrap", &json!({})),
        ("GET", ["categories"]) => {
            let refresh = query_param(&request.query, "refresh").as_deref() == Some("true");
            v1_dispatch(state, "get_categories", &json!({ "refresh": refresh }))
        }
        ("POST", ["feed"]) => v1_dispatch(state, "feed_open", &body),
        ("GET", ["feed", generation]) => {
            let generation: u64 = generation
                .parse()
                .map_err(|_| v1_fail("400 Bad Request", "bad_request", "invalid generation"))?;
            v1_dispatch(state, "feed_next", &json!({ "generation": generation }))
        }
        ("GET", ["search"]) => {
            let q = query_param(&request.query, "q").unwrap_or_default();
            v1_dispatch(state, "search_titles", &json!({ "query": q }))
        }
        ("GET", ["titles", enc]) => v1_dispatch(
            state,
            "get_title_detail",
            &json!({ "folderUrl": pct_decode(enc) }),
        ),
        ("GET", ["downloads"]) => v1_dispatch(state, "get_downloads", &json!({})),
        ("POST", ["downloads"]) => v1_dispatch(state, "start_download", &body),
        ("DELETE", ["downloads", "completed"]) => {
            v1_dispatch(state, "clear_completed_downloads", &json!({}))
        }
        ("POST", ["downloads", "pause-all"]) => {
            v1_dispatch(state, "pause_all_downloads", &json!({}))
        }
        ("POST", ["downloads", "resume-all"]) => {
            v1_dispatch(state, "resume_all_downloads", &json!({}))
        }
        ("POST", ["downloads", id, "pause"]) => v1_dispatch(state, "pause_download", &id_arg(id)?),
        ("POST", ["downloads", id, "resume"]) => {
            v1_dispatch(state, "resume_download", &id_arg(id)?)
        }
        ("POST", ["downloads", id, "redownload"]) => {
            v1_dispatch(state, "redownload_download", &id_arg(id)?)
        }
        ("DELETE", ["downloads", id]) => v1_dispatch(state, "remove_download", &id_arg(id)?),
        ("GET", ["sources"]) => to_json(sources_dto(state))
            .map_err(|e| v1_fail("500 Internal Server Error", "internal", e)),
        ("GET", ["sources", "probe"]) => v1_dispatch(state, "probe_sources", &json!({})),
        ("POST", ["sources"]) => v1_dispatch(state, "add_source", &body),
        ("DELETE", ["sources", name]) => {
            v1_dispatch(state, "remove_source", &json!({ "name": pct_decode(name) }))
        }
        ("PATCH", ["sources", name]) => {
            let enabled = body
                .get("enabled")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            v1_dispatch(
                state,
                "set_source_enabled",
                &json!({ "name": pct_decode(name), "enabled": enabled }),
            )
        }
        ("PUT", ["settings"]) => v1_dispatch(state, "update_settings", &body),
        ("GET", ["web-token"]) => v1_dispatch(state, "get_web_token", &json!({})),
        ("POST", ["web-token", "regenerate"]) => {
            v1_dispatch(state, "regenerate_web_token", &json!({}))
        }
        ("GET", ["favorites"]) => v1_dispatch(state, "get_favorites", &json!({})),
        ("POST", ["favorites", "toggle"]) => v1_dispatch(state, "toggle_favorite", &body),
        ("POST", ["posters"]) => v1_dispatch(state, "poster_srcs", &body),
        ("GET", ["update"]) => v1_dispatch(state, "check_update", &json!({})),
        _ => Err(v1_fail(
            "404 Not Found",
            "not_found",
            format!("no such route: {} {}", request.method, request.path),
        )),
    }
}

fn serve_events(stream: &mut TcpStream, subscribers: &Subscribers) {
    // The SSE stream writes only; a read timeout would kill an
    // idle-but-healthy stream, so it is lifted here (S3 exception).
    let _ = stream.set_read_timeout(None);
    let (tx, rx): (Sender<u64>, Receiver<u64>) = channel();
    let id = NEXT_SUBSCRIBER.fetch_add(1, Ordering::SeqCst);
    lock(subscribers).push((id, tx));
    event_loop(stream, &rx);
    // Audit: unregister on EVERY exit. Dead senders used to be
    // pruned only by the broadcast retain, which runs only when a
    // download stamp fires — in a quiet period, disconnected
    // browsers leaked their slots until the subscriber cap
    // rejected every new /api/events with 503.
    lock(subscribers).retain(|(sub_id, _)| *sub_id != id);
}

fn event_loop(stream: &mut TcpStream, rx: &Receiver<u64>) {
    let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n";
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
            // SECURITY.md S5: the web transport never discloses
            // absolute paths (they leak the account name and layout
            // to anyone who can reach the page). The values stay
            // display-shaped — the desktop's own IPC bootstrap in
            // commands.rs keeps the real paths for the local user.
            downloads_dir: "~/Downloads/LanFetch".to_string(),
            data_dir: "~/.local/share/lanfetch".to_string(),
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
            let settings = lanfetch_engine::store::Settings {
                connections: (arg_u64(args, "connections")? as u32).clamp(1, 32),
                max_concurrent: (arg_u64(args, "maxConcurrent")? as u32).clamp(1, 16),
                theme: arg_str(args, "theme")?,
                language: arg_str(args, "language")?,
                onboarding_done: arg_bool(args, "onboardingDone"),
            };
            state.manager.apply_settings(settings.clone());
            to_json(SettingsDto::from(&settings))
        }
        "get_web_token" => to_json(lanfetch_engine::store::load_or_create_web_token()),
        "regenerate_web_token" => to_json(lanfetch_engine::store::regenerate_web_token()),
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
        "check_update" => to_json(
            lanfetch_engine::catalog::check_desktop_update()
                .ok()
                .flatten(),
        ),
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
        lanfetch_engine::catalog::clear_listing_cache();
    }
    let categories = lanfetch_engine::catalog::discover_categories(&sources);
    let dtos: Vec<CategoryDto> = categories.iter().map(CategoryDto::from).collect();
    let mut catalog = lock(&state.catalog);
    catalog.categories = categories;
    catalog.feed = None;
    catalog.generation += 1;
    dtos
}

fn feed_open(state: &AppState, category: Option<String>) -> Result<FeedPageDto, String> {
    let favorites = favorites_set(state);
    // Build the session and bump the generation under the lock,
    // then crawl UNLOCKED (user-reported wedge, 2026-11-27: the
    // crawl used to run with the catalog mutex held, so one slow
    // server froze every other catalog call, each category click
    // queued another full crawl behind the lock, and a page
    // refresh just joined the back of the queue).
    let (mut feed, generation) = {
        let mut catalog = lock(&state.catalog);
        if catalog.categories.is_empty() {
            return Err("categories are not loaded yet".to_string());
        }
        catalog.generation += 1;
        (
            lanfetch_engine::catalog::FeedSession::new(&catalog.categories, category.as_deref()),
            catalog.generation,
        )
    };
    let page = feed.next_page(FEED_PAGE_SIZE);
    let has_more = feed.has_more;
    let mut catalog = lock(&state.catalog);
    if catalog.generation != generation {
        // A newer feed_open superseded this one while it crawled:
        // drop the stale page instead of overwriting the session.
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
}

fn feed_next(state: &AppState, generation: u64) -> FeedPageDto {
    let favorites = favorites_set(state);
    // Take the session OUT of the catalog so the crawl runs
    // unlocked; it goes back only if the generation is still
    // current when the page lands (see feed_open).
    let mut feed = {
        let mut catalog = lock(&state.catalog);
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
    let mut catalog = lock(&state.catalog);
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
}

fn search_titles(state: &AppState, query: &str) -> Vec<TitleDto> {
    let sources = enabled_sources(state);
    let favorites = favorites_set(state);
    let results = lanfetch_engine::catalog::search(&sources, query);
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
    let hydrated = lanfetch_engine::catalog::hydrate_and_load_files(title);
    {
        let mut catalog = lock(&state.catalog);
        catalog
            .titles
            .insert(hydrated.folder_url.clone(), hydrated.clone());
    }
    let files = hydrated
        .files
        .iter()
        .map(|f| lanfetch_engine::dto::FileDto::new(f, state.manager.task_by_url(&f.file_url)))
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
        lanfetch_engine::store::save_favorites(&favorites);
    }
    let mut snapshots = lanfetch_engine::store::load_favorite_titles();
    snapshots.retain(|s| s.folder_url != folder_url);
    if now_favorite {
        let catalog = lock(&state.catalog);
        if let Some(title) = catalog.titles.get(&folder_url) {
            snapshots.push(lanfetch_engine::store::FavTitle::from(title));
        }
    }
    lanfetch_engine::store::save_favorite_titles(&snapshots);
    now_favorite
}

fn get_favorites(state: &AppState) -> Vec<TitleDto> {
    let favorites = favorites_set(state);
    let mut snapshots: Vec<lanfetch_engine::store::FavTitle> =
        lanfetch_engine::store::load_favorite_titles()
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
                .or_insert_with(|| lanfetch_engine::catalog::Title {
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
        if lanfetch_engine::manager::downloads_dir()
            .join(&task.name)
            .exists()
        {
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
        lanfetch_engine::store::save_sources(&sources);
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
        lanfetch_engine::store::save_sources(&sources);
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
        lanfetch_engine::store::save_sources(&sources);
    }
    invalidate_catalog(state);
    Ok(sources_dto(state))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_allowlist_accepts_local_names_only() {
        for ok in [
            Some("127.0.0.1"),
            Some("127.0.0.1:8787"),
            Some("localhost"),
            Some("LOCALHOST:8787"),
            Some("[::1]"),
            Some("[::1]:8787"),
            Some("192.168.1.20:8787"),
            Some("my-pc.local"),
            Some("my-pc.local:8787"),
        ] {
            assert!(host_allowed(ok), "should allow {ok:?}");
        }
        for bad in [
            None,
            Some(""),
            Some("evil.example.com"),
            Some("evil.example.com:8787"),
            Some("attacker.local.evil.com"),
            Some("[::1"),
            Some("localhost.evil.com"),
        ] {
            assert!(!host_allowed(bad), "should reject {bad:?}");
        }
    }

    #[test]
    fn pct_decode_keeps_plus_and_decodes_escapes() {
        assert_eq!(pct_decode("a+b"), "a+b");
        assert_eq!(pct_decode("a%20b"), "a b");
        assert_eq!(pct_decode("%41%42"), "AB");
        assert_eq!(pct_decode("100%"), "100%");
        assert_eq!(pct_decode("%zz"), "%zz");
    }

    #[test]
    fn query_param_uses_form_decoding() {
        assert_eq!(
            query_param("q=hello+world&x=1", "q").as_deref(),
            Some("hello world")
        );
        assert_eq!(query_param("x=1", "q"), None);
        assert_eq!(
            query_param("token=abc%20d", "token").as_deref(),
            Some("abc d")
        );
    }

    #[test]
    fn token_comparison_is_exact() {
        assert!(token_eq("abc", "abc"));
        assert!(!token_eq("abc", "abd"));
        assert!(!token_eq("abc", "abcd"));
        assert!(!token_eq("", "a"));
    }
}
