//! LanFetch core: seed sources, directory-listing fetch/parse, and the
//! segmented download engine with byte-exact resume. No GUI code here —
//! this mirrors the Kotlin core's behavior (which mirrors the original
//! LanFlix findings) in plain blocking Rust: Rune's worker pool and plain
//! threads drive it, so no async runtime is needed.

use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

// ---------------------------------------------------------------- sources

#[derive(Debug, Clone)]
pub struct Source {
    pub name: String,
    pub root_url: String,
}

/// The DHAKA-FLIX directory servers found in the LanFlix reverse
/// engineering. Private ISP-network addresses: reachable only from inside
/// that ISP's network, exactly like the original app.
pub fn seed_sources() -> Vec<Source> {
    [
        ("DHAKA-FLIX-7", "http://172.16.50.7/DHAKA-FLIX-7/"),
        ("DHAKA-FLIX-8", "http://172.16.50.8/DHAKA-FLIX-8/"),
        ("DHAKA-FLIX-10", "http://172.16.50.10/DHAKA-FLIX-10/"),
        ("DHAKA-FLIX-12", "http://172.16.50.12/DHAKA-FLIX-12/"),
        ("DHAKA-FLIX-14", "http://172.16.50.14/DHAKA-FLIX-14/"),
    ]
    .iter()
    .map(|(name, url)| Source {
        name: name.to_string(),
        root_url: url.to_string(),
    })
    .collect()
}

// ---------------------------------------------------------------- listing

#[derive(Debug, Clone)]
pub struct DirEntry {
    pub name: String,
    pub url: String,
    pub is_dir: bool,
    pub size: Option<u64>,
}

/// One process-wide agent: building a fresh Agent per call (the old
/// behavior) throws away the connection pool every time, so each
/// listing, poster and segment paid a new TCP handshake. ureq Agents
/// are cheap to clone and safe to share between threads.
pub fn http_agent() -> ureq::Agent {
    static AGENT: std::sync::LazyLock<ureq::Agent> = std::sync::LazyLock::new(|| {
        ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(6))
            .timeout_read(Duration::from_secs(30))
            .user_agent("LanFetch/1.0 (desktop-rs)")
            .build()
    });
    AGENT.clone()
}

/// Probe a server root: Ok(elapsed_ms) when it answers with any HTTP status.
pub fn probe(source: &Source) -> Result<u128, String> {
    let started = Instant::now();
    match http_agent().get(&source.root_url).call() {
        Ok(_) => Ok(started.elapsed().as_millis()),
        Err(ureq::Error::Status(_, _)) => Ok(started.elapsed().as_millis()),
        Err(e) => Err(e.to_string()),
    }
}

/// Fetch and parse one directory listing (Apache/nginx autoindex or h5ai —
/// both are anchor lists at heart; sizes are best-effort from the row text).
/// Largest listing body we will read into memory (the Android client
/// caps at 4 MB; a hostile or misdirected URL must not OOM the app).
pub const MAX_LISTING_BYTES: u64 = 4 * 1024 * 1024;

pub fn fetch_listing(url: &str) -> Result<Vec<DirEntry>, String> {
    let mut reader = http_agent()
        .get(url)
        .call()
        .map_err(|e| match e {
            // An HTTP status means the server IS reachable — callers
            // (the failure cooldown) must not treat it as an outage.
            ureq::Error::Status(code, _) => format!("http status {code}"),
            other => format!("request failed: {other}"),
        })?
        .into_reader()
        .take(MAX_LISTING_BYTES + 1);
    let mut raw = Vec::new();
    reader
        .read_to_end(&mut raw)
        .map_err(|e| format!("read failed: {e}"))?;
    if raw.len() as u64 > MAX_LISTING_BYTES {
        return Err("listing too large".to_string());
    }
    let body = String::from_utf8_lossy(&raw);
    Ok(parse_listing(&body, url))
}

pub fn parse_listing(html: &str, base_url: &str) -> Vec<DirEntry> {
    let mut out = Vec::new();
    let lower = html.to_lowercase();
    let mut pos = 0usize;
    while let Some(rel) = lower[pos..].find("<a ") {
        let a_start = pos + rel;
        let Some(tag_end) = lower[a_start..].find('>') else {
            break;
        };
        let tag = &html[a_start..a_start + tag_end];
        pos = a_start + tag_end + 1;
        let Some(href) = extract_attr(tag, "href") else {
            continue;
        };
        if href.starts_with('?') || href.starts_with('#') || href == "../" || href == ".." {
            continue;
        }
        // Anchor text up to </a>.
        let text_end = lower[pos..].find("</a>").map(|i| pos + i).unwrap_or(pos);
        let raw_text = strip_tags(&html[pos..text_end]);
        let name = percent_decode(raw_text.trim());
        if name.is_empty() || name == "Parent Directory" || name.starts_with("?") {
            continue;
        }
        // The rest of the row (to end of line / row) may carry a size.
        let row_end = lower[text_end..]
            .find("</tr>")
            .map(|i| text_end + i)
            .unwrap_or_else(|| (text_end + 200).min(html.len()));
        let size = parse_size_after(&html[text_end..row_end]);
        let is_dir = href.ends_with('/');
        let clean_href = href.trim_end_matches('/');
        let decoded_href = percent_decode(clean_href);
        let display = if name == decoded_href || name.is_empty() {
            decoded_href
        } else {
            name
        };
        out.push(DirEntry {
            name: display,
            url: join_url(base_url, &href),
            is_dir,
            size: if is_dir { None } else { size },
        });
    }
    // Folders first, then files, each alphabetical — like the Android app.
    out.sort_by(|a, b| {
        b.is_dir
            .cmp(&a.is_dir)
            .then(a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    out.dedup_by(|a, b| a.url == b.url);
    out
}

fn extract_attr(tag: &str, attr: &str) -> Option<String> {
    let lower = tag.to_lowercase();
    let i = lower.find(&format!("{attr}="))? + attr.len() + 1;
    let rest = &tag[i..];
    let first = rest.chars().next()?;
    if first == '"' || first == '\'' {
        let end = rest[1..].find(first)? + 1;
        Some(rest[1..end].to_string())
    } else {
        let end = rest.find(|c: char| c.is_whitespace()).unwrap_or(rest.len());
        Some(rest[..end].to_string())
    }
}

fn strip_tags(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut in_tag = false;
    for c in s.chars() {
        match c {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => out.push(c),
            _ => {}
        }
    }
    out.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}

fn parse_size_after(row: &str) -> Option<u64> {
    // Apache: "1.2M", nginx: "1234567" or "-", h5ai varies. Take the last
    // plausible size token in the row text.
    let text = strip_tags(row);
    let mut best: Option<u64> = None;
    for tok in text.split_whitespace() {
        let t = tok.trim_end_matches([',', ';']);
        if let Ok(v) = t.parse::<u64>() {
            if v > 0 {
                best = Some(v);
            }
        } else if let Some(v) = parse_suffixed_size(t) {
            best = Some(v);
        }
    }
    best
}

fn parse_suffixed_size(t: &str) -> Option<u64> {
    let (num, mult) = t
        .strip_suffix(['K', 'k'])
        .map(|s| (s, 1024.0))
        .or_else(|| t.strip_suffix('M').map(|s| (s, 1024.0 * 1024.0)))
        .or_else(|| t.strip_suffix('G').map(|s| (s, 1024.0 * 1024.0 * 1024.0)))?;
    num.parse::<f64>().ok().map(|v| (v * mult) as u64)
}

pub fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(if bytes[i] == b'+' { b' ' } else { bytes[i] });
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn join_url(base: &str, href: &str) -> String {
    if href.starts_with("http://") || href.starts_with("https://") {
        return href.to_string();
    }
    if let Some(stripped) = href.strip_prefix('/') {
        // Absolute path: keep scheme + authority of the base.
        if let Some(scheme_end) = base.find("://") {
            let after = &base[scheme_end + 3..];
            let auth_end = after
                .find('/')
                .map(|i| scheme_end + 3 + i)
                .unwrap_or(base.len());
            return format!("{}/{}", &base[..auth_end], stripped);
        }
    }
    let base = if base.ends_with('/') {
        base.to_string()
    } else {
        format!("{base}/")
    };
    format!("{base}{href}")
}

// ---------------------------------------------------------------- format

pub fn fmt_bytes(n: u64) -> String {
    const K: f64 = 1024.0;
    let n = n as f64;
    if n < K {
        format!("{} B", n as u64)
    } else if n < K * K {
        format!("{:.1} KB", n / K)
    } else if n < K * K * K {
        format!("{:.1} MB", n / (K * K))
    } else {
        format!("{:.2} GB", n / (K * K * K))
    }
}

pub fn fmt_speed(bytes_per_sec: f64) -> String {
    if bytes_per_sec <= 0.0 {
        return "—".to_string();
    }
    format!("{}/s", fmt_bytes(bytes_per_sec as u64))
}

/// Format a remaining-time estimate: "~1:23" under an hour,
/// "~2:05:40" above it, "—" when there is no usable estimate
/// (50-wave program, Wave 8 — ETA did not exist anywhere before).
pub fn fmt_eta(secs: f64) -> String {
    if !secs.is_finite() || secs <= 0.0 {
        return "—".to_string();
    }
    let s = secs.round() as u64;
    if s < 3600 {
        format!("~{}:{:02}", s / 60, s % 60)
    } else {
        format!("~{}:{:02}:{:02}", s / 3600, (s % 3600) / 60, s % 60)
    }
}

/// Sanitize a server-supplied file name for local disk (S5 rules, same as
/// the Kotlin port): strip path separators and control characters, keep the
/// extension, cap the length.
pub fn sanitize_keep_ext(name: &str) -> String {
    let mut cleaned: String = name
        .chars()
        .map(|c| {
            if c == '/' || c == '\\' || (c as u32) < 0x20 {
                '_'
            } else {
                c
            }
        })
        .collect();
    cleaned = cleaned.trim().trim_start_matches('.').to_string();
    if cleaned.is_empty() {
        cleaned = "download".to_string();
    }
    if cleaned.len() > 180 {
        // Cut on a UTF-8 char boundary: names on these servers carry
        // Bangla/emoji, and slicing mid-character panics — and this runs
        // on the UI thread via enqueue, so that was a crash on tapping
        // Download for long non-ASCII names.
        let floor_boundary = |s: &str, mut n: usize| {
            n = n.min(s.len());
            while n > 0 && !s.is_char_boundary(n) {
                n -= 1;
            }
            n
        };
        if let Some(dot) = cleaned.rfind('.') {
            let ext = cleaned[dot..].to_string();
            let keep = floor_boundary(&cleaned, 180usize.saturating_sub(ext.len()).min(dot));
            cleaned = format!("{}{}", &cleaned[..keep], ext);
        } else {
            let cut = floor_boundary(&cleaned, 180);
            cleaned.truncate(cut);
        }
    }
    cleaned
}

// ---------------------------------------------------------------- download

/// One byte range of a download; `done` bytes of it are already on disk.
#[derive(Debug, Clone, Copy, serde::Serialize, serde::Deserialize)]
pub struct Segment {
    pub start: u64,
    pub end: u64, // inclusive; u64::MAX = to EOF (unknown total)
    pub done: u64,
}

impl Segment {
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn len(&self) -> u64 {
        if self.end == u64::MAX {
            u64::MAX
        } else {
            self.end - self.start + 1
        }
    }
    pub fn remaining(&self) -> u64 {
        self.len().saturating_sub(self.done)
    }
}

pub fn plan_segments(total: u64, connections: usize) -> Vec<Segment> {
    const MIN_SEGMENT: u64 = 1 << 20; // 1 MiB, same policy as the Kotlin engine
    if total == 0 {
        return vec![Segment {
            start: 0,
            end: u64::MAX,
            done: 0,
        }];
    }
    let mut count = connections.max(1) as u64;
    while count > 1 && total / count < MIN_SEGMENT {
        count -= 1;
    }
    let chunk = total / count;
    let mut segs = Vec::new();
    let mut start = 0u64;
    for i in 0..count {
        let end = if i == count - 1 {
            total - 1
        } else {
            start + chunk - 1
        };
        segs.push(Segment {
            start,
            end,
            done: 0,
        });
        start = end + 1;
    }
    segs
}

#[derive(Debug, Clone)]
pub struct Probe {
    pub total: Option<u64>,
    pub accept_ranges: bool,
    pub validator: Option<String>,
}

/// HEAD first, Range-probe fallback (some autoindex servers refuse HEAD).
pub fn probe_url(url: &str) -> Result<Probe, String> {
    let agent = http_agent();
    if let Ok(resp) = agent.head(url).call() {
        let total = resp.header("Content-Length").and_then(|v| v.parse().ok());
        let accept = resp
            .header("Accept-Ranges")
            .map(|v| v.eq_ignore_ascii_case("bytes"))
            .unwrap_or(false);
        let validator = resp
            .header("ETag")
            .or_else(|| resp.header("Last-Modified"))
            .map(|s| s.to_string());
        return Ok(Probe {
            total,
            accept_ranges: accept,
            validator,
        });
    }
    let resp = agent
        .get(url)
        .set("Range", "bytes=0-0")
        .call()
        .map_err(|e| format!("probe failed: {e}"))?;
    let status = resp.status();
    let content_range = resp.header("Content-Range").map(|s| s.to_string());
    let validator = resp
        .header("ETag")
        .or_else(|| resp.header("Last-Modified"))
        .map(|s| s.to_string());
    if status == 206 {
        let total = content_range.and_then(|cr| cr.rsplit('/').next().and_then(|t| t.parse().ok()));
        Ok(Probe {
            total,
            accept_ranges: true,
            validator,
        })
    } else {
        let total = resp.header("Content-Length").and_then(|v| v.parse().ok());
        Ok(Probe {
            total,
            accept_ranges: false,
            validator,
        })
    }
}

/// Shared live counters the UI polls.
pub struct Progress {
    pub downloaded: AtomicU64,
    pub total: AtomicU64,
    pub failed: AtomicBool,
    pub finished: AtomicBool,
    pub message: Mutex<Option<String>>,
    /// Live per-segment snapshot, refreshed by `run_download` workers after
    /// every chunk so the UI can draw the Android app's segmented ring and
    /// per-lane bars while a transfer is running (not just at the end).
    pub live_segments: Mutex<Vec<Segment>>,
    /// Set by the engine thread the moment run_download returns —
    /// on EVERY exit (complete, failed, paused). The UI ticker
    /// loops on this instead of finished/failed: the pause path
    /// sets neither of those, so tickers used to leak forever on
    /// pause and pile up across resumes (50-wave program, Wave 9).
    pub stopped: AtomicBool,
    /// Swap-guarded by the ticker spawn so a task never runs two
    /// tickers at once.
    pub ticker_active: AtomicBool,
}

impl Progress {
    pub fn new(total: u64) -> Self {
        Self {
            downloaded: AtomicU64::new(0),
            total: AtomicU64::new(total),
            failed: AtomicBool::new(false),
            finished: AtomicBool::new(false),
            message: Mutex::new(None),
            live_segments: Mutex::new(Vec::new()),
            stopped: AtomicBool::new(false),
            ticker_active: AtomicBool::new(false),
        }
    }
}

/// Run (or resume) one download into `part_path`. Blocking; call on a
/// thread. Segments download in parallel threads writing at offsets into
/// the .part file. `pause` stops workers at the next chunk boundary; the
/// returned segment list reflects exactly what is on disk.
pub fn run_download(
    url: &str,
    part_path: &std::path::Path,
    mut segments: Vec<Segment>,
    validator: Option<String>,
    pause: Arc<AtomicBool>,
    progress: Arc<Progress>,
) -> Vec<Segment> {
    use std::os::unix::fs::FileExt;

    let file = match std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(part_path)
    {
        Ok(f) => f,
        Err(e) => {
            progress.failed.store(true, Ordering::SeqCst);
            *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                Some(format!("cannot open file: {e}"));
            return segments;
        }
    };
    let total_known = segments.iter().all(|s| s.end != u64::MAX);
    if total_known {
        let total = segments.iter().map(|s| s.len()).sum::<u64>();
        let _ = file.set_len(total);
    }
    let file = Arc::new(file);
    let seg_state: Vec<Arc<Mutex<Segment>>> = segments
        .drain(..)
        .map(|s| Arc::new(Mutex::new(s)))
        .collect();
    let already: u64 = seg_state
        .iter()
        .map(|s| s.lock().unwrap_or_else(|e| e.into_inner()).done)
        .sum();
    progress.downloaded.store(already, Ordering::SeqCst);
    *progress
        .live_segments
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = seg_state
        .iter()
        .map(|s| *s.lock().unwrap_or_else(|e| e.into_inner()))
        .collect();

    let mut handles = Vec::new();
    for (seg_idx, seg) in seg_state.iter().enumerate() {
        if seg.lock().unwrap_or_else(|e| e.into_inner()).remaining() == 0 {
            continue;
        }
        let seg = Arc::clone(seg);
        let file = Arc::clone(&file);
        let pause = Arc::clone(&pause);
        let progress = Arc::clone(&progress);
        let url = url.to_string();
        let validator = validator.clone();
        let single_segment = seg_state.len() == 1;
        handles.push(std::thread::spawn(move || {
            let agent = http_agent();
            // Bounded retries with backoff: a server that accepts then
            // drops, trickles, or answers 206 with empty bodies must fail
            // the segment — the old loop re-requested the remaining range
            // immediately, forever, at full speed.
            let mut stalled = 0u32;
            let mut last_done = seg.lock().unwrap_or_else(|e| e.into_inner()).done;
            loop {
                if pause.load(Ordering::SeqCst) || progress.failed.load(Ordering::SeqCst) {
                    return;
                }
                let (from, to) = {
                    let s = seg.lock().unwrap_or_else(|e| e.into_inner());
                    if s.remaining() == 0 {
                        return;
                    }
                    (s.start + s.done, s.end)
                };
                let range = if to == u64::MAX {
                    format!("bytes={from}-")
                } else {
                    format!("bytes={from}-{to}")
                };
                let mut req = agent.get(&url).set("Range", &range);
                if let Some(v) = &validator {
                    // If-Range guards against the file changing under a resume.
                    req = req.set("If-Range", v);
                }
                let resp = match req.call() {
                    Ok(r) => Some(r),
                    Err(ureq::Error::Status(416, _)) => {
                        // Range not satisfiable: benign only when the
                        // segment is already fully on disk; otherwise the
                        // file shrank or changed under the resume.
                        let s = seg.lock().unwrap_or_else(|e| e.into_inner());
                        if s.remaining() > 0 {
                            progress.failed.store(true, Ordering::SeqCst);
                            *progress.message.lock().unwrap_or_else(|e| e.into_inner()) = Some(
                                "range not satisfiable — file may have changed; restart needed"
                                    .into(),
                            );
                        }
                        return;
                    }
                    Err(ureq::Error::Status(code, _)) => {
                        progress.failed.store(true, Ordering::SeqCst);
                        *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                            Some(format!("segment failed: HTTP {code}"));
                        return;
                    }
                    // Transport errors (refused, reset, timeout) retry
                    // through the stall/backoff path below.
                    Err(ureq::Error::Transport(_)) => None,
                };
                let mut hit_eof = false;
                if let Some(resp) = resp {
                    let status = resp.status();
                    if status == 200 {
                        // Server ignored Range (or If-Range validator
                        // changed): the body is the whole file, which is
                        // only safe for a single-segment plan starting at
                        // 0 with nothing done — otherwise lanes would
                        // write over each other's ranges.
                        let s = seg.lock().unwrap_or_else(|e| e.into_inner());
                        if s.start != 0 || s.done != 0 || !single_segment {
                            progress.failed.store(true, Ordering::SeqCst);
                            *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                                Some("server ignored Range / file changed — restart needed".into());
                            return;
                        }
                    }
                    let mut reader = resp.into_reader();
                    let mut buf = vec![0u8; 256 * 1024];
                    loop {
                        if pause.load(Ordering::SeqCst) {
                            return;
                        }
                        // Never read past the segment's end: an
                        // over-sending server would otherwise stream into
                        // the next segment's territory while `done`
                        // overshoots — a "completed" but corrupt file.
                        let want = {
                            let s = seg.lock().unwrap_or_else(|e| e.into_inner());
                            let rem = s.remaining();
                            if rem == 0 {
                                break;
                            }
                            if s.end == u64::MAX {
                                buf.len()
                            } else {
                                (rem as usize).min(buf.len())
                            }
                        };
                        match reader.read(&mut buf[..want]) {
                            Ok(0) => {
                                hit_eof = true;
                                break;
                            }
                            Ok(n) => {
                                let offset = {
                                    let s = seg.lock().unwrap_or_else(|e| e.into_inner());
                                    s.start + s.done
                                };
                                if let Err(e) = file.write_all_at(&buf[..n], offset) {
                                    progress.failed.store(true, Ordering::SeqCst);
                                    *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                                        Some(format!("write failed: {e}"));
                                    return;
                                }
                                {
                                    let mut s = seg.lock().unwrap_or_else(|e| e.into_inner());
                                    s.done += n as u64;
                                    if let Some(live) = progress
                                        .live_segments
                                        .lock()
                                        .unwrap_or_else(|e| e.into_inner())
                                        .get_mut(seg_idx)
                                    {
                                        live.done = s.done;
                                    }
                                }
                                progress.downloaded.fetch_add(n as u64, Ordering::SeqCst);
                            }
                            Err(_) => break, // mid-segment drop: judged below
                        }
                    }
                }
                if hit_eof {
                    // Unknown-total stream: EOF is the true end of file.
                    let mut s = seg.lock().unwrap_or_else(|e| e.into_inner());
                    if s.end == u64::MAX && s.done > 0 {
                        s.end = s.start + s.done - 1;
                    }
                }
                // Judge the attempt: no byte progress → back off and
                // retry a bounded number of times, then fail honestly.
                let now_done = seg.lock().unwrap_or_else(|e| e.into_inner()).done;
                if now_done == last_done {
                    stalled += 1;
                    if stalled > 6 {
                        progress.failed.store(true, Ordering::SeqCst);
                        *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                            Some("download stalled — server stopped sending data".into());
                        return;
                    }
                    let backoff_ms = (250u64 << stalled.min(4)).min(4000);
                    let mut slept = 0u64;
                    while slept < backoff_ms {
                        if pause.load(Ordering::SeqCst) || progress.failed.load(Ordering::SeqCst) {
                            return;
                        }
                        std::thread::sleep(Duration::from_millis(50));
                        slept += 50;
                    }
                } else {
                    stalled = 0;
                    last_done = now_done;
                }
            }
        }));
    }
    for h in handles {
        let _ = h.join();
    }
    let out: Vec<Segment> = seg_state
        .iter()
        .map(|s| *s.lock().unwrap_or_else(|e| e.into_inner()))
        .collect();
    let complete = out.iter().all(|s| s.remaining() == 0);
    if complete && !progress.failed.load(Ordering::SeqCst) {
        // Truncate any over-long tail (server shrank) and finish. The
        // reopen must not panic: the .part can vanish mid-download
        // ("Remove + delete partial" races this path), and a panic here
        // would wedge the task as Running forever.
        let mut finished_ok = true;
        if let Some(last) = out.last() {
            if last.end != u64::MAX {
                match std::fs::OpenOptions::new().write(true).open(part_path) {
                    Ok(mut f) => {
                        let _ = f.set_len(last.end + 1);
                        let _ = f.flush();
                        let _ = f.seek(SeekFrom::Start(0));
                    }
                    Err(err) => {
                        finished_ok = false;
                        progress.failed.store(true, Ordering::SeqCst);
                        *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                            Some(format!("cannot reopen finished file: {err}"));
                    }
                }
            }
        }
        if finished_ok {
            progress.finished.store(true, Ordering::SeqCst);
        }
    }
    out
}

// ------------------------------------------------------------------ tests

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn eta_formats_minutes_hours_and_none() {
        assert_eq!(fmt_eta(83.0), "~1:23");
        assert_eq!(fmt_eta(7540.0), "~2:05:40");
        assert_eq!(fmt_eta(3599.0), "~59:59");
        assert_eq!(fmt_eta(3600.0), "~1:00:00");
        assert_eq!(fmt_eta(0.0), "—");
        assert_eq!(fmt_eta(-5.0), "—");
        assert_eq!(fmt_eta(f64::INFINITY), "—");
        assert_eq!(fmt_eta(f64::NAN), "—");
    }

    #[test]
    fn plan_zero_total_is_open_segment() {
        let segs = plan_segments(0, 8);
        assert_eq!(segs.len(), 1);
        assert_eq!(segs[0].end, u64::MAX);
    }

    #[test]
    fn plan_respects_min_segment() {
        // 5 MiB over 8 lanes would make sub-1MiB segments: lanes shrink.
        let segs = plan_segments(5 * 1024 * 1024, 8);
        assert_eq!(segs.len(), 5);
    }

    #[test]
    fn plan_is_contiguous_and_complete() {
        let total = 10_737_418_240u64; // 10 GiB
        let segs = plan_segments(total, 8);
        assert_eq!(segs.len(), 8);
        assert_eq!(segs.first().unwrap().start, 0);
        assert_eq!(segs.last().unwrap().end, total - 1);
        for w in segs.windows(2) {
            assert_eq!(w[1].start, w[0].end + 1);
        }
        assert_eq!(segs.iter().map(|s| s.len()).sum::<u64>(), total);
        assert!(segs.iter().all(|s| s.len() >= (1 << 20)));
    }

    #[test]
    fn sanitize_strips_separators() {
        let out = sanitize_keep_ext("a/b\\c.mkv");
        assert!(!out.contains('/') && !out.contains('\\'));
        assert!(out.ends_with(".mkv"));
    }

    #[test]
    fn sanitize_long_bangla_name_keeps_extension() {
        // >180 bytes of multi-byte text: the old code panicked slicing
        // mid-character (Reliability #4).
        let name = format!("{}.mkv", "বাংলা সিনেমা ".repeat(30));
        let out = sanitize_keep_ext(&name);
        assert!(out.ends_with(".mkv"));
        assert!(out.len() <= 180);
    }

    #[test]
    fn parse_listing_apache_rows() {
        let html = "<a href=\"Movies/\">Movies/</a> - -\n<a href=\"clip.mp4\">clip.mp4</a> 2024-01-01 10:00 1234567";
        let entries = parse_listing(html, "http://h/root/");
        assert_eq!(entries.len(), 2);
        assert!(entries[0].is_dir);
        assert_eq!(entries[1].name, "clip.mp4");
        assert_eq!(entries[1].size, Some(1234567));
    }
}
