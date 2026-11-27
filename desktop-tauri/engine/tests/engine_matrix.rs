//! Engine acceptance-matrix tests (V2 Wave 8, slice B) — the
//! criteria from docs/TESTING.md §3 that engine_integration.rs
//! does not already cover. Same harness pattern: a scripted,
//! std-only local HTTP server and a fresh $HOME per test under a
//! process-wide lock ($HOME is process-global state).
//!
//! Covered here:
//!   #5  range_ignored_falls_back_to_single_stream
//!   #7  pause_then_resume_completes
//!   #8  retry_after_mid_download_failure
//!   #9  kill_and_reload_resumes_from_checkpoint
//!   #10 validator_change_restarts_safely
//!   #12 status_401_403_fail_with_typed_error
//!   #17 many_tasks_respect_concurrency_cap
//! Plus two lifecycle behaviors from the audit backlog:
//!   remove_running_download_deletes_part
//!   clear_completed_only_removes_completed

use lanfetch_engine::manager::{DlState, Manager};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
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

/// A well-behaved range server with a configurable ETag: HEAD +
/// Range honored with exact Content-Range.
fn range_server_etag(body: Arc<Vec<u8>>, etag: &'static str) -> Handler {
    Arc::new(move |req: &Req| {
        let total = body.len() as u64;
        if req.method == "HEAD" {
            return Resp {
                status: 200,
                headers: vec![
                    h("Content-Length", total),
                    h("Accept-Ranges", "bytes"),
                    h("ETag", etag),
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
                        h("ETag", etag),
                    ],
                    body: slice,
                    drip: false,
                }
            }
            None => Resp {
                status: 200,
                headers: vec![h("Content-Length", total), h("ETag", etag)],
                body: (*body).clone(),
                drip: false,
            },
        }
    })
}

fn range_server(body: Arc<Vec<u8>>) -> Handler {
    range_server_etag(body, "\"v1\"")
}

/// Criterion 5's server: HEAD advertises Range support, but every
/// GET ignores the Range header and returns 200 with the whole
/// body. Records whether the engine actually attempted a ranged
/// GET (so the test proves the fallback happened, not that ranges
/// were never tried).
fn advertise_but_ignore_range_server(body: Arc<Vec<u8>>, saw_range: Arc<AtomicBool>) -> Handler {
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
        if req.range.is_some() {
            saw_range.store(true, Ordering::SeqCst);
        }
        Resp {
            status: 200,
            headers: vec![h("Content-Length", total), h("ETag", "\"v1\"")],
            body: (*body).clone(),
            drip: false,
        }
    })
}

/// A range server whose GETs wait for a gate before answering
/// (HEAD answers immediately, so probes complete). While the gate
/// is closed the download is Running but cannot move a byte —
/// the deterministic mid-transfer state for pause/remove tests.
fn gated_range_server(body: Arc<Vec<u8>>, gate: Arc<AtomicBool>) -> Handler {
    Arc::new(move |req: &Req| {
        if req.method != "HEAD" {
            // Bounded even if a failing test never opens the gate.
            for _ in 0..2400 {
                if gate.load(Ordering::SeqCst) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(25));
            }
        }
        range_server(Arc::clone(&body))(req)
    })
}

/// Criterion 8's server: the first `good` ranged GETs are served
/// correctly; every later ranged GET gets correct 206 headers
/// and then the connection closes with no body at all (server
/// dying mid-download). Setting `healed` restores full service.
fn drop_after_server(body: Arc<Vec<u8>>, good: usize, healed: Arc<AtomicBool>) -> Handler {
    let served = Arc::new(AtomicUsize::new(0));
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
        let Some((start, end)) = req.range else {
            return Resp {
                status: 200,
                headers: vec![h("Content-Length", total), h("ETag", "\"v1\"")],
                body: (*body).clone(),
                drip: false,
            };
        };
        let end = end.unwrap_or(total - 1).min(total - 1);
        let slice = body[start as usize..=end as usize].to_vec();
        let headers = vec![
            h("Content-Length", slice.len()),
            h("Content-Range", format!("bytes {start}-{end}/{total}")),
            h("ETag", "\"v1\""),
        ];
        // The probe's bytes=0-0 range GET is not a segment fetch;
        // never count or drop it. (With Accept-Ranges on HEAD the
        // engine does not send one, but the guard is free.)
        let is_probe = start == 0 && end == 0;
        if is_probe || healed.load(Ordering::SeqCst) {
            return Resp {
                status: 206,
                headers,
                body: slice,
                drip: false,
            };
        }
        let n = served.fetch_add(1, Ordering::SeqCst) + 1;
        if n <= good {
            Resp {
                status: 206,
                headers,
                body: slice,
                drip: false,
            }
        } else {
            // Correct headers, promised length — and no body.
            Resp {
                status: 206,
                headers,
                body: Vec::new(),
                drip: false,
            }
        }
    })
}

/// A range server that records the start offset of every ranged
/// GET (criterion 9: prove the reload resumed at the checkpoint,
/// not from zero).
fn recording_range_server(body: Arc<Vec<u8>>, starts: Arc<Mutex<Vec<u64>>>) -> Handler {
    Arc::new(move |req: &Req| {
        if req.method == "GET" {
            if let Some((start, _)) = req.range {
                starts.lock().unwrap_or_else(|e| e.into_inner()).push(start);
            }
        }
        range_server(Arc::clone(&body))(req)
    })
}

/// Criterion 12's server: everything is 403; every request is
/// counted so the test can prove the engine does not retry-loop
/// against an authorization failure.
fn forbidden_server(count: Arc<AtomicUsize>) -> Handler {
    Arc::new(move |_req: &Req| {
        count.fetch_add(1, Ordering::SeqCst);
        Resp {
            status: 403,
            headers: vec![h("Content-Length", 0)],
            body: Vec::new(),
            drip: false,
        }
    })
}

/// Criterion 17's server: a range server whose segment GETs take
/// ~900 ms, tracking how many are in flight at once.
fn concurrency_server(
    body: Arc<Vec<u8>>,
    in_flight: Arc<AtomicUsize>,
    watermark: Arc<AtomicUsize>,
) -> Handler {
    Arc::new(move |req: &Req| {
        if req.method == "GET" && req.range.is_some() {
            let now = in_flight.fetch_add(1, Ordering::SeqCst) + 1;
            watermark.fetch_max(now, Ordering::SeqCst);
            std::thread::sleep(Duration::from_millis(900));
            in_flight.fetch_sub(1, Ordering::SeqCst);
        }
        range_server(Arc::clone(&body))(req)
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
    let dir = std::env::temp_dir().join(format!("lanfetch-mx-{}-{tag}", std::process::id()));
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

fn task_count(mgr: &Manager) -> usize {
    mgr.tasks.lock().unwrap_or_else(|e| e.into_inner()).len()
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

fn wait_until(timeout: Duration, what: &str, mut cond: impl FnMut() -> bool) {
    let started = Instant::now();
    while !cond() {
        assert!(
            started.elapsed() < timeout,
            "timed out after {timeout:?} waiting for: {what}"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn downloads_path(home: &Home, name: &str) -> PathBuf {
    home.dir.join("Downloads/LanFetch").join(name)
}

fn final_bytes(home: &Home, name: &str) -> Vec<u8> {
    std::fs::read(downloads_path(home, name)).expect("final file must exist")
}

/// Seed a downloads.json in the engine's data dir (the fixture
/// pattern the restart/recovery tests are built on).
fn write_store(home: &Home, json: &str) {
    let data = home.dir.join(".local/share/lanfetch");
    std::fs::create_dir_all(&data).unwrap();
    std::fs::write(data.join("downloads.json"), json).unwrap();
}

fn write_part(home: &Home, name: &str, bytes: &[u8]) {
    let dir = home.dir.join("Downloads/LanFetch");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join(format!("{name}.part")), bytes).unwrap();
}

// ------------------------------------------------------------ tests

/// Criterion 5: the server advertises ranges (HEAD says so) but
/// answers every ranged GET with 200 + the full body. The engine
/// must fall back to treating the response as the whole file —
/// byte-exact, as a single segment.
#[test]
fn range_ignored_falls_back_to_single_stream() {
    let home = fresh_home("range-ignored");
    let body = Arc::new(body_pattern(100_000));
    let saw_range = Arc::new(AtomicBool::new(false));
    let server = TestServer::start(advertise_but_ignore_range_server(
        Arc::clone(&body),
        Arc::clone(&saw_range),
    ));
    let mgr = Manager::load();
    mgr.enqueue(server.url("movie.mkv"), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    assert!(
        saw_range.load(Ordering::SeqCst),
        "the engine must have attempted a ranged GET before falling back"
    );
    let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
    let t = tasks.iter().find(|t| t.id == id).unwrap();
    assert_eq!(
        t.segments.len(),
        1,
        "a 200-to-Range download must run as a single stream"
    );
    drop(tasks);
    assert_eq!(final_bytes(&home, "movie.mkv"), *body);
}

/// Criterion 7: pause a download that is mid-transfer (Running,
/// plan in place, zero bytes served behind the gate), then resume
/// — it must complete byte-exact. (The stall/pause *latency*
/// bound itself is pause_during_stalled_read_bounded in
/// engine_integration.rs.)
#[test]
fn pause_then_resume_completes() {
    let home = fresh_home("pause-resume");
    let body = Arc::new(body_pattern(200_000));
    let gate = Arc::new(AtomicBool::new(false));
    let server = TestServer::start(gated_range_server(Arc::clone(&body), Arc::clone(&gate)));
    let mgr = Manager::load();
    mgr.enqueue(server.url("movie.mkv"), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Running, Duration::from_secs(30));
    mgr.pause(id);
    wait_for(&mgr, id, DlState::Paused, Duration::from_secs(30));
    {
        let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
        let t = tasks.iter().find(|t| t.id == id).unwrap();
        assert_eq!(t.downloaded(), 0, "the gate let no bytes through");
        assert!(!t.segments.is_empty(), "the plan survives the pause");
    }
    gate.store(true, Ordering::SeqCst);
    mgr.start(id);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    assert_eq!(final_bytes(&home, "movie.mkv"), *body);
}

/// Criterion 8: the server dies mid-download — two of three
/// segments land, then every further response is headers-only
/// and the connection drops. The task must fail (bounded stall
/// ladder, never spin forever) with its progress credited; when
/// the server recovers, a retry resumes from the .part and
/// completes byte-exact.
#[test]
fn retry_after_mid_download_failure() {
    let home = fresh_home("mid-download-failure");
    // 3 MiB with the default 8 connections plans 3 segments
    // (plan_segments enforces a 1 MiB floor per segment).
    let body = Arc::new(body_pattern(3 * 1024 * 1024));
    let healed = Arc::new(AtomicBool::new(false));
    let server = TestServer::start(drop_after_server(Arc::clone(&body), 2, Arc::clone(&healed)));
    let mgr = Manager::load();
    mgr.enqueue(server.url("big.mkv"), "big.mkv".to_string());
    let id = only_task_id(&mgr);
    // The zero-progress stall ladder takes ~16 s to exhaust.
    wait_for(&mgr, id, DlState::Failed, Duration::from_secs(90));
    {
        let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
        let t = tasks.iter().find(|t| t.id == id).unwrap();
        assert!(
            t.downloaded() > 0,
            "two segments landed before the server died; got {} bytes",
            t.downloaded()
        );
        assert!(
            t.downloaded() < body.len() as u64,
            "the download must NOT be complete"
        );
    }
    healed.store(true, Ordering::SeqCst);
    mgr.start(id);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    assert_eq!(final_bytes(&home, "big.mkv"), *body);
}

/// Criterion 9: the app is killed mid-download. The persisted
/// state says Running with 120,000 of 200,000 bytes done, and
/// the .part on disk holds exactly those bytes. A fresh manager
/// must restore it as Paused (RC-3), and resuming must fetch
/// only the missing tail — proven by the range offsets the
/// server actually sees — and complete byte-exact.
#[test]
fn kill_and_reload_resumes_from_checkpoint() {
    let home = fresh_home("kill-reload");
    let body = Arc::new(body_pattern(200_000));
    let starts = Arc::new(Mutex::new(Vec::new()));
    let server = TestServer::start(recording_range_server(
        Arc::clone(&body),
        Arc::clone(&starts),
    ));
    write_store(
        &home,
        &format!(
            r#"[{{"id":1,"url":"{}","name":"movie.mkv","total":200000,"validator":"\"v1\"","segments":[{{"start":0,"end":199999,"done":120000}}],"state":"Running","error":null}}]"#,
            server.url("movie.mkv")
        ),
    );
    write_part(&home, "movie.mkv", &body[..120_000]);
    let mgr = Manager::load();
    let id = only_task_id(&mgr);
    assert_eq!(
        task_state(&mgr, id),
        Some(DlState::Paused),
        "a task that was Running at exit restores as Paused (RC-3)"
    );
    mgr.start(id);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    assert_eq!(final_bytes(&home, "movie.mkv"), *body);
    let starts = starts.lock().unwrap_or_else(|e| e.into_inner());
    assert!(
        !starts.is_empty(),
        "the resume must have fetched the missing tail"
    );
    assert!(
        starts.iter().all(|&s| s >= 120_000),
        "no byte before the checkpoint may be re-fetched; got starts {starts:?}"
    );
}

/// Criterion 10: the file on the server changed after the
/// stored plan was made — different length AND different ETag.
/// Retrying the failed task re-probes (T14); the RT-3 drift
/// check must reset the plan, and the completed file must be
/// the NEW content exactly — never a mix of old .part bytes
/// and new bytes. (A Paused task with a plan deliberately does
/// not re-probe on resume — start() T13 — so the drift gate is
/// the re-probe path exercised here.)
#[test]
fn validator_change_restarts_safely() {
    let home = fresh_home("validator-change");
    // The stored state describes v1: 200,000 bytes, ETag "v1",
    // 120,000 bytes already in the .part.
    let v1 = body_pattern(200_000);
    // The server now serves v2: shorter, different bytes, "v2".
    let mut v2_raw = body_pattern(150_000);
    v2_raw.reverse();
    let v2 = Arc::new(v2_raw);
    let server = TestServer::start(range_server_etag(Arc::clone(&v2), "\"v2\""));
    write_store(
        &home,
        &format!(
            r#"[{{"id":1,"url":"{}","name":"movie.mkv","total":200000,"validator":"\"v1\"","segments":[{{"start":0,"end":199999,"done":120000}}],"state":"Failed","error":"segment failed: HTTP 500"}}]"#,
            server.url("movie.mkv")
        ),
    );
    write_part(&home, "movie.mkv", &v1[..120_000]);
    let mgr = Manager::load();
    let id = only_task_id(&mgr);
    assert_eq!(task_state(&mgr, id), Some(DlState::Failed));
    mgr.start(id);
    wait_for(&mgr, id, DlState::Completed, Duration::from_secs(60));
    {
        let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
        let t = tasks.iter().find(|t| t.id == id).unwrap();
        assert_eq!(t.total, 150_000, "the plan must reset to the new length");
        assert_eq!(
            t.validator.as_deref(),
            Some("\"v2\""),
            "the stored validator must be replaced by the new one"
        );
    }
    assert_eq!(final_bytes(&home, "movie.mkv"), *v2);
}

/// Criterion 12: a 403 is an authorization failure, not a
/// transient one — the task must fail promptly, the status must
/// surface in the task's error, and the engine must not
/// retry-loop against the server. (The fully *typed* error
/// kinds are criterion 20, which has not landed yet; until then
/// the status in the error text is what the DTO carries.)
#[test]
fn status_401_403_fail_with_typed_error() {
    let home = fresh_home("forbidden");
    let count = Arc::new(AtomicUsize::new(0));
    let server = TestServer::start(forbidden_server(Arc::clone(&count)));
    let mgr = Manager::load();
    let started = Instant::now();
    mgr.enqueue(server.url("movie.mkv"), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Failed, Duration::from_secs(30));
    assert!(
        started.elapsed() < Duration::from_secs(25),
        "a 403 must fail promptly, not after a retry campaign ({:?})",
        started.elapsed()
    );
    let requests = count.load(Ordering::SeqCst);
    assert!(
        requests >= 1 && requests <= 6,
        "expected a probe's worth of requests, got {requests} — no hammering"
    );
    let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
    let t = tasks.iter().find(|t| t.id == id).unwrap();
    assert!(
        t.error.as_deref().unwrap_or("").contains("403"),
        "the HTTP status must surface in the error; got {:?}",
        t.error
    );
    drop(tasks);
    assert!(
        !downloads_path(&home, "movie.mkv").exists(),
        "a forbidden download must never produce a final file"
    );
}

/// Criterion 17: with max_concurrent = 2, six queued downloads
/// must never have more than two segment GETs in flight at the
/// server at once — and all six still complete.
#[test]
fn many_tasks_respect_concurrency_cap() {
    let home = fresh_home("concurrency-cap");
    let body = Arc::new(body_pattern(60_000));
    let in_flight = Arc::new(AtomicUsize::new(0));
    let watermark = Arc::new(AtomicUsize::new(0));
    let server = TestServer::start(concurrency_server(
        Arc::clone(&body),
        Arc::clone(&in_flight),
        Arc::clone(&watermark),
    ));
    let mgr = Manager::load();
    {
        let mut s = mgr.settings();
        s.max_concurrent = 2;
        mgr.apply_settings(s);
    }
    let mut ids = Vec::new();
    for i in 0..6 {
        let name = format!("f{i}.mkv");
        mgr.enqueue(server.url(&name), name);
    }
    {
        let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(tasks.len(), 6);
        ids.extend(tasks.iter().map(|t| t.id));
    }
    for id in &ids {
        wait_for(&mgr, *id, DlState::Completed, Duration::from_secs(60));
    }
    let wm = watermark.load(Ordering::SeqCst);
    assert!(
        wm <= 2,
        "concurrency cap violated: {wm} segment GETs in flight at once"
    );
    assert!(
        wm >= 2,
        "expected the two slots to actually overlap; watermark was {wm}"
    );
    for i in 0..6 {
        assert_eq!(final_bytes(&home, &format!("f{i}.mkv")), *body);
    }
}

/// Lifecycle: removing a Running download deletes its .part,
/// and when the (gated) workers finally unwind, the task must
/// not resurrect — no state write, no rename to the final name
/// (T18).
#[test]
fn remove_running_download_deletes_part() {
    let home = fresh_home("remove-running");
    let body = Arc::new(body_pattern(200_000));
    let gate = Arc::new(AtomicBool::new(false));
    let server = TestServer::start(gated_range_server(Arc::clone(&body), Arc::clone(&gate)));
    let mgr = Manager::load();
    mgr.enqueue(server.url("movie.mkv"), "movie.mkv".to_string());
    let id = only_task_id(&mgr);
    wait_for(&mgr, id, DlState::Running, Duration::from_secs(30));
    let part = downloads_path(&home, "movie.mkv.part");
    wait_until(Duration::from_secs(10), ".part to be created", || {
        part.exists()
    });
    mgr.remove(id, true);
    assert_eq!(task_count(&mgr), 0, "the task is gone from the list");
    assert!(!part.exists(), "remove with delete_part removes the .part");
    // Let the gated workers unwind; nothing may come back.
    gate.store(true, Ordering::SeqCst);
    std::thread::sleep(Duration::from_secs(3));
    assert_eq!(task_count(&mgr), 0, "a removed task must not resurrect");
    assert!(
        !downloads_path(&home, "movie.mkv").exists(),
        "a removed download must never be renamed to its final name"
    );
    assert!(!part.exists(), "the .part must not reappear");
}

/// Lifecycle: Clear-completed drops exactly the Completed
/// tasks — Failed and Paused tasks stay, and clearing the list
/// does not delete the finished file from disk.
#[test]
fn clear_completed_only_removes_completed() {
    let home = fresh_home("clear-completed");
    // A Paused task restored from a fixture (id 7).
    write_store(
        &home,
        r#"[{"id":7,"url":"http://x/c.mkv","name":"c.mkv","total":10,"validator":null,"segments":[{"start":0,"end":9,"done":5}],"state":"Paused","error":null}]"#,
    );
    let body = Arc::new(body_pattern(50_000));
    let server = TestServer::start(range_server(Arc::clone(&body)));
    // A port that refuses everything, for a fast Failed task.
    let dead_port = {
        let l = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let p = l.local_addr().unwrap().port();
        drop(l);
        p
    };
    let mgr = Manager::load();
    mgr.enqueue(server.url("a.mkv"), "a.mkv".to_string());
    mgr.enqueue(
        format!("http://127.0.0.1:{dead_port}/b.mkv"),
        "b.mkv".to_string(),
    );
    let (a_id, b_id) = {
        let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
        assert_eq!(tasks.len(), 3);
        (
            tasks.iter().find(|t| t.name == "a.mkv").unwrap().id,
            tasks.iter().find(|t| t.name == "b.mkv").unwrap().id,
        )
    };
    wait_for(&mgr, a_id, DlState::Completed, Duration::from_secs(60));
    wait_for(&mgr, b_id, DlState::Failed, Duration::from_secs(30));
    mgr.clear_completed();
    let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
    let remaining: Vec<u64> = tasks.iter().map(|t| t.id).collect();
    assert!(
        !remaining.contains(&a_id),
        "the completed task is cleared; remaining {remaining:?}"
    );
    assert!(
        remaining.contains(&b_id) && remaining.contains(&7),
        "Failed and Paused tasks survive a clear; remaining {remaining:?}"
    );
    drop(tasks);
    assert_eq!(
        final_bytes(&home, "a.mkv"),
        *body,
        "clearing the list must not delete the finished file"
    );
}
