//! CI performance smoke (V2 Wave 8) — indicative only.
//!
//! Modeled on the bd-marketplace Wave 46 load smoke: exercise the
//! hot paths in-process, PRINT the timings, and assert only
//! correctness plus a generous pathology ceiling. The printed
//! numbers are a regression tripwire for humans reading CI logs
//! — they are NOT a benchmark and NOT a capacity claim: CI
//! runners vary too much for anything stronger. (This project
//! also bans headless/screenshot CI; nothing here renders.)
//!
//! Three smokes:
//! - listing-parse throughput over a large synthetic h5ai page;
//! - feed paging over a synthetic multi-folder tree, against the
//!   scripted listing server below;
//! - a segmented 8 MiB download from the scripted range server,
//!   byte-exact, through the real Manager (default settings →
//!   the default segment plan).
//!
//! The harness (scripted std-only HTTP server + $HOME isolation)
//! is a trimmed copy of engine_integration.rs's, kept
//! self-contained so this file can evolve independently.
//! Total runtime target: well under 60 s.

use lanfetch_engine::catalog::{Category, FeedSession};
use lanfetch_engine::core::parse_listing;
use lanfetch_engine::manager::{DlState, Manager};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// The only timing assertion anywhere in this file: a smoke
/// that crosses two minutes is not "slow CI", it is a
/// pathological regression (a re-introduced wedge, an accidental
/// quadratic, a lost batching). Everything below this ceiling
/// passes regardless of the printed number.
const PATHOLOGY_CEILING: Duration = Duration::from_secs(120);

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
    /// Hold the connection open after the response (unused by
    /// the perf handlers; kept so this harness stays a faithful
    /// copy of engine_integration.rs's).
    drip: bool,
}

type Handler = Arc<dyn Fn(&Req) -> Resp + Send + Sync>;

struct TestServer {
    port: u16,
    stop: Arc<AtomicBool>,
}

impl TestServer {
    fn start(handler: Handler) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind test server");
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
/// Content-Range, ETag "v1" (copied from engine_integration.rs).
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

/// A synthetic catalog tree: the category root lists
/// FOLDER_COUNT movie folders; each folder lists exactly one
/// .mkv. Listings are h5ai-shaped anchor rows, generated per
/// request from the path alone (stateless).
const FEED_FOLDERS: usize = 40;

fn listing_server() -> Handler {
    Arc::new(move |req: &Req| {
        let html = if req.path == "/Movies/" {
            let mut s = String::from("<html><body><table id=\"fb\">");
            for i in 1..=FEED_FOLDERS {
                s.push_str(&format!(
                    "<tr><td class=\"fb-n\"><a href=\"Movie{i:02}/\">Movie{i:02}</a></td>\
                     <td class=\"fb-s\"></td></tr>"
                ));
            }
            s.push_str("</table></body></html>");
            s
        } else if req.path.starts_with("/Movies/Movie") && req.path.ends_with('/') {
            let name = req.path.trim_matches('/').rsplit('/').next().unwrap_or("");
            format!(
                "<html><body><table id=\"fb\">\
                 <tr><td class=\"fb-n\"><a href=\"{name}.mkv\">{name}.mkv</a></td>\
                 <td class=\"fb-s\">1.5G</td></tr>\
                 </table></body></html>"
            )
        } else {
            return Resp {
                status: 404,
                headers: vec![],
                body: Vec::new(),
                drip: false,
            };
        };
        Resp {
            status: 200,
            headers: vec![
                h("Content-Length", html.len()),
                h("Content-Type", "text/html"),
            ],
            body: html.into_bytes(),
            drip: false,
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
    let dir = std::env::temp_dir().join(format!("lanfetch-perf-{}-{tag}", std::process::id()));
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

// ------------------------------------------------------------ smokes

/// Listing-parse throughput: one large h5ai page, 5,000 file
/// anchors with sizes. Asserts every entry parses (and a couple
/// of spot values); the elapsed time is printed, never asserted
/// beyond the pathology ceiling.
#[test]
fn perf_parse_listing_5000_anchors() {
    const ANCHORS: usize = 5_000;
    let mut html = String::from("<html><body><table id=\"fb\">");
    for i in 1..=ANCHORS {
        html.push_str(&format!(
            "<tr><td class=\"fb-n\"><a href=\"Movie{i:04}.mkv\">Movie{i:04}.mkv</a></td>\
             <td class=\"fb-d\">2026-11-27 10:00</td><td class=\"fb-s\">1.5G</td></tr>"
        ));
    }
    html.push_str("</table></body></html>");

    let started = Instant::now();
    let entries = parse_listing(&html, "http://172.16.50.7/Movies/");
    let elapsed = started.elapsed();

    assert_eq!(entries.len(), ANCHORS, "every anchor must parse");
    assert!(entries.iter().any(|e| e.name == "Movie0001.mkv"));
    assert!(entries.iter().any(|e| e.name == "Movie5000.mkv"));
    assert!(
        entries.iter().all(|e| e.size == Some(1_610_612_736)),
        "row sizes must parse (1.5G)"
    );
    println!(
        "PERF parse_listing: {} anchors from {} bytes in {:?} ({:.0} entries/s)",
        entries.len(),
        html.len(),
        elapsed,
        ANCHORS as f64 / elapsed.as_secs_f64()
    );
    assert!(
        elapsed < PATHOLOGY_CEILING,
        "parse_listing took {elapsed:?} — pathological regression?"
    );
}

/// Feed paging: a FeedSession over the synthetic tree (1 root +
/// 40 folder listings) paged at 12 until exhausted. Asserts all
/// 40 titles arrive exactly once across the pages; per-page
/// elapsed is printed.
#[test]
fn perf_feed_pages_over_synthetic_tree() {
    let server = TestServer::start(listing_server());
    let categories = vec![Category {
        name: "Movies".to_string(),
        locations: vec![("PerfServer".to_string(), server.url("Movies/"))],
    }];
    let mut session = FeedSession::new(&categories, None);
    assert!(session.has_more, "session must start with work");

    let started = Instant::now();
    let mut total = 0usize;
    let mut pages = 0usize;
    while session.has_more {
        assert!(pages < 20, "feed must terminate (runaway paging?)");
        let page_started = Instant::now();
        let page = session.next_page(12);
        let page_elapsed = page_started.elapsed();
        pages += 1;
        total += page.len();
        println!(
            "PERF feed page {pages}: {} titles in {page_elapsed:?} (has_more={})",
            page.len(),
            session.has_more
        );
        if page.is_empty() {
            break;
        }
    }
    let elapsed = started.elapsed();

    assert_eq!(total, FEED_FOLDERS, "every folder must yield one title");
    assert!(
        pages >= 2,
        "40 titles at page size 12 must take several pages"
    );
    println!("PERF feed: {total} titles in {pages} pages, {elapsed:?} total");
    assert!(
        elapsed < PATHOLOGY_CEILING,
        "feed paging took {elapsed:?} — pathological regression?"
    );
}

/// Segmented download: 8 MiB from the range server through the
/// real Manager with default settings (8 connections → the
/// default segment plan). Asserts byte-exact completion; the
/// elapsed time and MiB/s are printed.
#[test]
fn perf_segmented_download_8mib_byte_exact() {
    let home = fresh_home("download-8mib");
    let body = Arc::new(body_pattern(8 * 1024 * 1024));
    let server = TestServer::start(range_server(Arc::clone(&body)));
    let mgr = Manager::load();
    mgr.enqueue(server.url("big.mkv"), "big.mkv".to_string());
    let id = only_task_id(&mgr);

    let started = Instant::now();
    wait_for(&mgr, id, DlState::Completed, PATHOLOGY_CEILING);
    let elapsed = started.elapsed();

    assert_eq!(
        final_bytes(&home, "big.mkv"),
        *body,
        "download must be byte-exact"
    );
    let mib_per_s = 8.0 / elapsed.as_secs_f64();
    println!("PERF download: 8 MiB segmented in {elapsed:?} ({mib_per_s:.1} MiB/s, loopback)");
    assert!(
        elapsed < PATHOLOGY_CEILING,
        "8 MiB download took {elapsed:?} — pathological regression?"
    );
}
