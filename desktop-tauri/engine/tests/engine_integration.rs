//! Engine integration tests (V2 Wave 2) — the download lifecycle
//! proven end to end against a scripted, std-only local HTTP server.
//! Test names map to the acceptance matrix in docs/TESTING.md §3.
//!
//! Every test isolates itself in a fresh $HOME (the engine derives
//! its data/downloads dirs from it) and holds a process-wide lock for
//! its whole run, because $HOME is process-global state.

use lanfetch_engine::manager::{DlState, Manager};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

// ------------------------------------------------------------ test server

struct Req {
    method: String,
    path: String,
    range: Option<(u64, Option<u64>)>,
}

struct Resp {
    status: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
    /// Hold the connection open (sending nothing more) after the
    /// headers/body — the stalled-server scenario.
    drip: bool,
}

type Handler = Arc<dyn Fn(&Req) -> Resp + Send + Sync>;

struct TestServer {
    port: u16,
    stop: Arc<AtomicBool>,
}

impl TestServer {
    fn start(handler: Handler) -> Self {
        Self::start_on(0, handler)
    }

    fn start_on(port: u16, handler: Handler) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", port)).expect("bind test server");
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let stop2 = Arc::clone(&stop);
        std::thread::spawn(move || {
            while !stop2.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let h = Arc::clone(&handler);
                        std::thread::spawn(move || handle_conn(stream, h));
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });
        TestServer { port, stop }
    }

    fn url(&self, name: &str) -> String {
        format!("http://127.0.0.1:{}/{name}", self.port)
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
    }
}

fn handle_conn(mut stream: TcpStream, handler: Handler) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let mut raw = Vec::new();
    let mut buf = [0u8; 8192];
    let head_end = loop {
        match stream.read(&mut buf) {
            Ok(0) => return,
            Ok(n) => {
                raw.extend_from_slice(&buf[..n]);
                if let Some(pos) = find(&raw, b"\r\n\r\n") {
                    break pos + 4;
                }
                if raw.len() > 64 * 1024 {
                    return;
                }
            }
            Err(_) => return,
        }
    };
    let head = String::from_utf8_lossy(&raw[..head_end]).to_string();
    let mut lines = head.lines();
    let request_line = lines.next().unwrap_or("").to_string();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_string();
    let path = parts.next().unwrap_or("/").to_string();
    let mut range = None;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.trim().eq_ignore_ascii_case("range") {
                range = parse_range_header(value.trim());
            }
        }
    }
    let resp = handler(&Req {
        method,
        path,
        range,
    });
    let mut out = format!("HTTP/1.1 {} X\r\nConnection: close\r\n", resp.status);
    for (k, v) in &resp.headers {
        out.push_str(&format!("{k}: {v}\r\n"));
    }
    out.push_str("\r\n");
    if stream.write_all(out.as_bytes()).is_err() {
        return;
    }
    if !resp.body.is_empty() && stream.write_all(&resp.body).is_err() {
        return;
    }
    let _ = stream.flush();
    if resp.drip {
        // A server that accepted the request and then went silent.
        // 90 s: comfortably past every client-side deadline the
        // engine is supposed to enforce (30 s listing deadline),
        // so a test that returns quickly proves the bound works.
        std::thread::sleep(Duration::from_secs(90));
    }
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|w| w == needle)
}

fn parse_range_header(value: &str) -> Option<(u64, Option<u64>)> {
    let spec = value.strip_prefix("bytes=")?;
    let (start, end) = spec.split_once('-')?;
    Some((
        start.trim().parse().ok()?,
        if end.trim().is_empty() {
            None
        } else {
            Some(end.trim().parse().ok()?)
        },
    ))
}

fn h(k: &str, v: impl std::fmt::Display) -> (String, String) {
    (k.to_string(), v.to_string())
}

/// A well-behaved server: HEAD + Range honored with exact
/// Content-Range, ETag "v1".
fn range_server(body: Arc<Vec<u8>>) -> Handler {
    Arc::new(move |req: &Req| {
        let total = body.len() as u64;
        if req.method == "HEAD" {
            return Resp {
                status: 200,
                headers: vec![
                    h("Content-Length", total),
                    h("Accept-Ranges", "bytes"),
                    h("ETag", "\"v1\""),
                ],
                body: Vec::new(),
                drip: false,
            };
        }
        match req.range {
            Some((start, end)) => {
                let end = end.unwrap_or(total - 1).min(total - 1);
                let slice = body[start as usize..=end as usize].to_vec();
                Resp {
                    status: 206,
                    headers: vec![
                        h("Content-Length", slice.len()),
                        h("Content-Range", format!("bytes {start}-{end}/{total}")),
                        h("ETag", "\"v1\""),
                    ],
                    body: slice,
                    drip: false,
                }
            }
            None => Resp {
                status: 200,
                headers: vec![h("Content-Length", total), h("ETag", "\"v1\"")],
                body: (*body).clone(),
                drip: false,
            },
        }
    })
}

/// A server with no Range support at all: every GET is a 200 with the
/// whole body, whatever was asked for.
fn plain_server(body: Arc<Vec<u8>>) -> Handler {
    Arc::new(move |req: &Req| {
        let total = body.len() as u64;
        if req.method == "HEAD" {
            return Resp {
                status: 200,
                headers: vec![h("Content-Length", total)],
                body: Vec::new(),
                drip: false,
            };
        }
        Resp {
            status: 200,
            headers: vec![h("Content-Length", total)],
            body: (*body).clone(),
            drip: false,
        }
    })
}

/// A hostile/misconfigured server: answers 206 but its Content-Range
/// does not describe the body it sends (off by 100 bytes). The engine
/// must never write those bytes at the requested offset (CP-2).
fn lying_range_server(body: Arc<Vec<u8>>) -> Handler {
    Arc::new(move |req: &Req| {
        let total = body.len() as u64;
        if req.method == "HEAD" {
            return Resp {
                status: 200,
                headers: vec![
                    h("Content-Length", total),
                    h("Accept-Ranges", "bytes"),
                    h("ETag", "\"v1\""),
                ],
                body: Vec::new(),
                drip: false,
            };
        }
        let (start, end) = req.range.unwrap_or((0, None));
        let end = end.unwrap_or(total - 1).min(total - 1);
        let slice = body[start as usize..=end as usize].to_vec();
        Resp {
            status: 206,
            headers: vec![
                h("Content-Length", slice.len()),
                h(
                    "Content-Range",
                    format!("bytes {}-{}/{}", start + 100, end + 100, total),
                ),
                h("ETag", "\"v1\""),
            ],
            body: slice,
            drip: false,
        }
    })
}

/// A server that answers correctly, then goes silent mid-body:
/// headers promise the full range, no byte ever arrives.
fn stall_server(total: u64) -> Handler {
    Arc::new(move |req: &Req| {
        if req.method == "HEAD" {
            return Resp {
                status: 200,
                headers: vec![
                    h("Content-Length", total),
                    h("Accept-Ranges", "bytes"),
                    h("ETag", "\"v1\""),
                ],
                body: Vec::new(),
                drip: false,
            };
        }
        let (start, end) = req.range.unwrap_or((0, None));
        let end = end.unwrap_or(total - 1).min(total - 1);
        Resp {
            status: 206,
            headers: vec![
                h("Content-Length", end - start + 1),
                h("Content-Range", format!("bytes {start}-{end}/{total}")),
                h("ETag", "\"v1\""),
            ],
            body: Vec::new(),
            drip: true,
        }
    })
}

/// A listing server whose category page answers instantly with
/// links to 16 subfolders — and every subfolder listing sends a
/// partial body and then goes silent (the wedge scenario: the
/// client must bound the read itself, not wait for the server).
fn stall_listing_server() -> Handler {
    Arc::new(move |req: &Req| {
        if req.path == "/cat/" {
            let mut html = String::from("<html><body>");
            for i in 1..=16 {
                html.push_str(&format!("<a href=\"M{i}/\">M{i}</a>"));
            }
            html.push_str("</body></html>");
            return Resp {
                status: 200,
                headers: vec![h("Content-Length", html.len())],
                body: html.into_bytes(),
                drip: false,
            };
        }
        // Partial listing, then silence (drip holds the socket).
        let partial = "<html><body><a href=\"x\">x</a>";
        Resp {
            status: 200,
            headers: vec![h("Content-Length", 100_000)],
            body: partial.as_bytes().to_vec(),
            drip: true,
        }
    })
}

// ------------------------------------------------------------ harness

static HOME_LOCK: Mutex<()> = Mutex::new(());

struct Home {
    dir: PathBuf,
    _guard: MutexGuard<'static, ()>,
}

fn fresh_home(tag: &str) -> Home {
    let guard = HOME_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let dir = std::env::temp_dir().join(format!("lanfetch-it-{}-{tag}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::env::set_var("HOME", &dir);
    Home { dir, _guard: guard }
}

fn body_pattern(n: usize) -> Vec<u8> {
    (0..n).map(|i| (i % 251) as u8).collect()
}

fn task_state(mgr: &Manager, id: u64) -> Option<DlState> {
    mgr.tasks
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .iter()
        .find(|t| t.id == id)
        .map(|t| t.state)
}

fn only_task_id(mgr: &Manager) -> u64 {
    let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
    assert_eq!(tasks.len(), 1, "expected exactly one task");
    tasks[0].id
}

fn wait_for(mgr: &Manager, id: u64, want: DlState, timeout: Duration) -> DlState {
    let started = Instant::now();
    loop {
        if let Some(s) = task_state(mgr, id) {
            if s == want {
                return s;
            }
        }
        assert!(
            started.elapsed() < timeout,
            "task {id} did not reach {want:?} within {timeout:?}; now {:?}",
            task_state(mgr, id)
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn final_bytes(home: &Home, name: &str) -> Vec<u8> {
    std::fs::read(home.dir.join("Downloads/LanFetch").join(name)).expect("final file must exist")
}

// ------------------------------------------------------------ tests

#[test]
fn http_plain_download_byte_exact() {
    let home = fresh_home("plain");
    let body = Arc::new(body_pattern(100_000));
    let server = TestServer::start(plain_server(Arc::clone(&body)));
    let mgr = Manager::load();
    mgr.enqueue(server.url("movie.mkv"), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    assert_eq!(final_bytes(&home, "movie.mkv"), *body);
}

#[test]
fn segmented_download_byte_exact() {
    let home = fresh_home("segmented");
    // 3 MiB with the default 8 connections plans 3 segments
    // (plan_segments enforces a 1 MiB floor per segment).
    let body = Arc::new(body_pattern(3 * 1024 * 1024));
    let server = TestServer::start(range_server(Arc::clone(&body)));
    let mgr = Manager::load();
    mgr.enqueue(server.url("big.mkv"), "big.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(90));
    {
        let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
        let t = tasks.iter().find(|t| t.id == id).unwrap();
        assert!(
            t.segments.len() >= 2,
            "expected a multi-segment plan, got {} segment(s)",
            t.segments.len()
        );
    }
    assert_eq!(final_bytes(&home, "big.mkv"), *body);
}

/// The Wave 0 P0 regression test: a task whose first probe failed was
/// permanently un-retryable (the stored `ready` gate). Retry must
/// re-probe and complete once the server is back.
#[test]
fn retry_after_probe_failure() {
    let home = fresh_home("retry");
    // Reserve a port, then drop the listener: connections refuse.
    let port = {
        let l = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let p = l.local_addr().unwrap().port();
        drop(l);
        p
    };
    let url = format!("http://127.0.0.1:{port}/movie.mkv");
    let mgr = Manager::load();
    mgr.enqueue(url, "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Failed, Duration::from_secs(30));

    let body = Arc::new(body_pattern(120_000));
    let _server = TestServer::start_on(port, range_server(Arc::clone(&body)));
    mgr.start(id);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    assert_eq!(final_bytes(&home, "movie.mkv"), *body);
}

/// CP-2: a 206 whose Content-Range lies about the body must fail the
/// task — never complete with bytes written at the wrong offset.
#[test]
fn range_206_wrong_content_range_rejected() {
    let home = fresh_home("lying");
    let body = Arc::new(body_pattern(200_000));
    let server = TestServer::start(lying_range_server(body));
    let mgr = Manager::load();
    mgr.enqueue(server.url("movie.mkv"), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    // The bounded stall-retry ladder takes ~16 s to exhaust.
    wait_for(&mgr, id, DlState::Failed, Duration::from_secs(90));
    let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
    let t = tasks.iter().find(|t| t.id == id).unwrap();
    assert_eq!(t.downloaded(), 0, "lying bytes must never be credited");
    drop(tasks);
    assert!(
        !home.dir.join("Downloads/LanFetch/movie.mkv").exists(),
        "a corrupt download must never reach the final name"
    );
}

/// CP-4/RC-2: progress claimed in downloads.json but the .part was
/// deleted while the app was closed → the missing bytes are
/// re-fetched; the file completes byte-exact, never with holes.
#[test]
fn part_deleted_while_closed_is_reconciled() {
    let home = fresh_home("reconcile");
    let body = Arc::new(body_pattern(200_000));
    let server = TestServer::start(range_server(Arc::clone(&body)));
    let data = home.dir.join(".local/share/lanfetch");
    std::fs::create_dir_all(&data).unwrap();
    // RC6-format state: 120,000 of 200,000 bytes claimed done, and
    // no .part on disk at all.
    let json = format!(
        r#"[{{"id":1,"url":"{}","name":"movie.mkv","total":200000,"validator":"\"v1\"","segments":[{{"start":0,"end":199999,"done":120000}}],"state":"Paused","error":null}}]"#,
        server.url("movie.mkv")
    );
    std::fs::write(data.join("downloads.json"), json).unwrap();
    let mgr = Manager::load();
    let id = only_task_id(&mgr);
    assert_eq!(task_state(&mgr, id), Some(DlState::Paused));
    mgr.start(id);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    assert_eq!(final_bytes(&home, "movie.mkv"), *body);
}

/// T1: enqueueing the same URL repeatedly yields exactly one task.
#[test]
fn duplicate_enqueue_same_url_single_task() {
    let _home = fresh_home("dedup");
    let body = Arc::new(body_pattern(50_000));
    let server = TestServer::start(range_server(body));
    let mgr = Manager::load();
    let url = server.url("movie.mkv");
    mgr.enqueue(url.clone(), "movie.mkv".to_string());
    mgr.enqueue(url.clone(), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    mgr.enqueue(url, "movie.mkv".to_string());
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
    assert_eq!(tasks.len(), 1, "duplicate enqueues must not multiply tasks");
}

/// RC-3/RC-5: a state file written by the 1.6.0 line loads losslessly
/// and restores per the mapping — Queued with a plan → Paused,
/// Queued without one → Failed (retryable), Completed → Completed.
#[test]
fn legacy_store_still_loads() {
    let home = fresh_home("legacy");
    let data = home.dir.join(".local/share/lanfetch");
    std::fs::create_dir_all(&data).unwrap();
    let json = r#"[
      {"id":1,"url":"http://x/a.mkv","name":"a.mkv","total":10,"validator":null,
       "segments":[{"start":0,"end":9,"done":5}],"state":"Queued","error":null},
      {"id":2,"url":"http://x/b.mkv","name":"b.mkv","total":0,"validator":null,
       "segments":[],"state":"Queued","error":null},
      {"id":3,"url":"http://x/c.mkv","name":"c.mkv","total":10,"validator":null,
       "segments":[{"start":0,"end":9,"done":10}],"state":"Completed","error":null}
    ]"#;
    std::fs::write(data.join("downloads.json"), json).unwrap();
    let mgr = Manager::load();
    assert_eq!(task_state(&mgr, 1), Some(DlState::Paused));
    assert_eq!(task_state(&mgr, 2), Some(DlState::Failed));
    assert_eq!(task_state(&mgr, 3), Some(DlState::Completed));
    let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
    let t2 = tasks.iter().find(|t| t.id == 2).unwrap();
    assert!(
        t2.error
            .as_deref()
            .unwrap_or("")
            .contains("interrupted before the download started"),
        "interrupted task must carry its reason, got {:?}",
        t2.error
    );
}

/// PA-3: pause during a stalled read takes effect within the
/// documented bound (≈5 s read timeout), not the pre-V2 ≈30 s.
#[test]
fn pause_during_stalled_read_bounded() {
    let _home = fresh_home("pausebound");
    let server = TestServer::start(stall_server(1024 * 1024));
    let mgr = Manager::load();
    mgr.enqueue(server.url("movie.mkv"), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Running, Duration::from_secs(30));
    // Let the worker settle into its blocked read.
    std::thread::sleep(Duration::from_millis(500));
    let started = Instant::now();
    mgr.pause(id);
    wait_for(&mgr, id, DlState::Paused, Duration::from_secs(20));
    assert!(
        started.elapsed() < Duration::from_secs(12),
        "pause took {:?}; the documented bound is ~5 s",
        started.elapsed()
    );
}

/// Regression (user-reported wedge, 2026-11-27): a feed page whose
/// folder listings stall mid-body must still return within a
/// bounded time — the listing read deadline plus the page budget —
/// instead of waiting on the server (90 s per folder here), and
/// has_more must stay honest about the unfetched remainder.
#[test]
fn feed_page_bounded_against_stalling_listings() {
    let _home = fresh_home("feedstall");
    let server = TestServer::start(stall_listing_server());
    let root = format!("http://127.0.0.1:{}/cat/", server.port);
    let categories = vec![lanfetch_engine::catalog::Category {
        name: "Movies".to_string(),
        locations: vec![("SRC".to_string(), root)],
    }];
    let mut feed = lanfetch_engine::catalog::FeedSession::new(&categories, Some("Movies"));
    let started = Instant::now();
    let _page = feed.next_page(48);
    let elapsed = started.elapsed();
    assert!(
        elapsed < Duration::from_secs(55),
        "feed page took {elapsed:?} against stalling listings"
    );
    assert!(
        feed.has_more,
        "unfetched folders remain, has_more must stay honest"
    );
}
