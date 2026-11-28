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
        ("DHAKA-FLIX-9", "http://172.16.50.9/DHAKA-FLIX-9/"),
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
            // An overall deadline as well as per-read ones: a
            // server that trickles bytes resets the read timeout
            // forever, and a catalog crawl must never hang on one
            // (user-reported wedge, 2026-11-27). The transfer agent
            // deliberately has NO overall timeout — a large
            // download legitimately takes minutes.
            .timeout(Duration::from_secs(30))
            .user_agent("LanFetch/1.0 (desktop-rs)")
            // Redirects are followed manually by call_following
            // under the S4 policy (same host, no downgrade) —
            // ureq's built-in following allows both.
            .redirects(0)
            .build()
    });
    AGENT.clone()
}

/// The transfer-path agent: identical to `http_agent` except the read
/// timeout is 5 s, not 30 s. That number is the documented pause bound
/// (DOWNLOAD-ENGINE.md PA-3): a worker blocked in a read on a stalled
/// server notices a pause within one read timeout, so pause latency is
/// ≈5 s worst case instead of ≈30 s.
pub fn transfer_agent() -> ureq::Agent {
    static AGENT: std::sync::LazyLock<ureq::Agent> = std::sync::LazyLock::new(|| {
        ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(6))
            .timeout_read(Duration::from_secs(5))
            .user_agent("LanFetch/1.0 (desktop-rs)")
            .redirects(0) // manual, policy-checked: see call_following
            .build()
    });
    AGENT.clone()
}

/// A failed engine HTTP call: either the underlying ureq error
/// (statuses and transport failures keep their existing meaning
/// for callers) or a redirect-policy rejection, which is a
/// final, user-facing failure.
#[derive(Debug)]
pub enum CallError {
    // Boxed: ureq::Error is much larger than a String, and this
    // enum is returned by every engine HTTP call (clippy
    // large_enum_variant).
    Ureq(Box<ureq::Error>),
    Rejected(String),
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CallError::Ureq(e) => write!(f, "{e}"),
            CallError::Rejected(m) => write!(f, "{m}"),
        }
    }
}

const MAX_REDIRECTS: u32 = 5;

/// The host of a URL, lowercased, without userinfo or port
/// (IPv6 literals lose their brackets). None when the URL has
/// no authority.
fn host_of(url: &str) -> Option<String> {
    let (_, authority) = origin_of(url)?;
    let host_port = authority.rsplit('@').next()?;
    let host = if let Some(rest) = host_port.strip_prefix('[') {
        rest.split(']').next()?
    } else {
        host_port.split(':').next()?
    };
    Some(host.to_ascii_lowercase())
}

/// Resolve a redirect `Location` against the URL that produced
/// it and judge the result against the redirect policy
/// (docs/SECURITY.md S4, mirroring the Android app): never to
/// a different host, never from HTTPS down to HTTP. An
/// http → https hop to the same host is an upgrade and is
/// followed.
fn redirect_target(from: &str, location: &str) -> Result<String, CallError> {
    let rejected = |m: &str| CallError::Rejected(m.to_string());
    let target = if location.starts_with("http://") || location.starts_with("https://") {
        location.to_string()
    } else if let Some(rest) = location.strip_prefix("//") {
        // Scheme-relative: keep the scheme, take the new
        // authority — the host check below judges it.
        let scheme = from.split("://").next().unwrap_or("http");
        format!("{scheme}://{rest}")
    } else if location.starts_with('/') {
        match origin_of(from) {
            Some((s, a)) => format!("{s}://{a}{location}"),
            None => return Err(rejected("server sent an unusable redirect")),
        }
    } else {
        // Relative to the directory of the current URL.
        let after = from.split("://").nth(1).unwrap_or("");
        if !after.contains('/') {
            format!("{from}/{location}")
        } else {
            match from.rfind('/') {
                Some(i) => format!("{}{}", &from[..=i], location),
                None => return Err(rejected("server sent an unusable redirect")),
            }
        }
    };
    let from_host = host_of(from);
    if from_host.is_none() || from_host != host_of(&target) {
        return Err(rejected("server redirected to another host"));
    }
    if from.starts_with("https://") && target.starts_with("http://") {
        return Err(rejected("server redirected to an unencrypted connection"));
    }
    Ok(target)
}

/// Perform a GET (or HEAD) and follow redirects manually
/// under the S4 policy, at most MAX_REDIRECTS hops. `build`
/// re-creates the request for every hop, so headers like Range
/// and If-Range are re-sent — and never leak to another host,
/// because cross-host hops are rejected before they happen.
/// Both agents are built with redirects(0); a 3xx therefore
/// surfaces as a normal response or a Status error depending
/// on the exact code, and both shapes are handled.
pub fn call_following(
    agent: &ureq::Agent,
    head: bool,
    url: &str,
    build: impl Fn(ureq::Request) -> ureq::Request,
) -> Result<ureq::Response, CallError> {
    let mut current = url.to_string();
    let mut hops = 0u32;
    loop {
        let req = if head {
            agent.head(&current)
        } else {
            agent.get(&current)
        };
        let result = build(req).call();
        let mut location: Option<String> = None;
        match &result {
            Ok(r) if (300..400).contains(&r.status()) => {
                location = r.header("Location").map(str::to_string);
            }
            Err(ureq::Error::Status(code, r)) if (300..400).contains(code) => {
                location = r.header("Location").map(str::to_string);
            }
            _ => {}
        }
        let Some(location) = location else {
            return result.map_err(|e| CallError::Ureq(Box::new(e)));
        };
        if hops >= MAX_REDIRECTS {
            return Err(CallError::Rejected("too many redirects".into()));
        }
        hops += 1;
        current = redirect_target(&current, &location)?;
    }
}

/// Parse a `Content-Range` header ("bytes <start>-<end>/<total|*>").
/// Returns (start, end, total) with total None for "*".
/// HTTP statuses that mean "ask again later", not "this file is
/// gone": rate limiting and transient server faults. The segment
/// worker retries these through its bounded stall/backoff ladder
/// (honoring Retry-After); every other status fails the task at
/// once. Audit fix, 2026-11-28 (the pre-fix worker failed a
/// download outright on a single 503).
pub fn is_transient_status(code: u16) -> bool {
    matches!(code, 429 | 500 | 502 | 503 | 504)
}

pub fn parse_content_range(value: &str) -> Option<(u64, u64, Option<u64>)> {
    let rest = value.trim().strip_prefix("bytes ")?.trim();
    let (range, total) = rest.split_once('/')?;
    let (start, end) = range.split_once('-')?;
    Some((
        start.trim().parse().ok()?,
        end.trim().parse().ok()?,
        if total.trim() == "*" {
            None
        } else {
            Some(total.trim().parse().ok()?)
        },
    ))
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

/// Hard wall-clock cap on reading one listing body. The agent's
/// read timeout is per-read: a server dribbling a byte at a time
/// resets it indefinitely, so the read loop enforces its own
/// deadline (user-reported wedge, 2026-11-27). The error is a
/// "read failed" so the host earns the transport-failure cooldown.
const LISTING_DEADLINE: Duration = Duration::from_secs(30);

/// Read a response body under the listing bounds: at most
/// MAX_LISTING_BYTES and at most LISTING_DEADLINE overall. A
/// hostile or broken server must not be able to OOM the process
/// with an endless body or hold the read open forever (audit).
fn read_capped(reader: impl Read) -> Result<Vec<u8>, String> {
    let mut reader = reader.take(MAX_LISTING_BYTES + 1);
    let started = Instant::now();
    let mut raw = Vec::new();
    let mut chunk = [0u8; 64 * 1024];
    loop {
        if started.elapsed() > LISTING_DEADLINE {
            return Err("read failed: listing took too long".to_string());
        }
        match reader.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => raw.extend_from_slice(&chunk[..n]),
            Err(e) => return Err(format!("read failed: {e}")),
        }
        if raw.len() as u64 > MAX_LISTING_BYTES {
            return Err("listing too large".to_string());
        }
    }
    Ok(raw)
}

/// A response body as text, under the same caps as a listing
/// read — for the endpoints that used to call `into_string()`
/// uncapped (h5ai search, the update check).
pub fn body_text_capped(resp: ureq::Response) -> Result<String, String> {
    let raw = read_capped(resp.into_reader())?;
    Ok(String::from_utf8_lossy(&raw).into_owned())
}

pub fn fetch_listing(url: &str) -> Result<Vec<DirEntry>, String> {
    let resp = call_following(&http_agent(), false, url, |r| r).map_err(|e| match e {
        // An HTTP status means the server IS reachable — callers
        // (the failure cooldown) must not treat it as an outage.
        CallError::Ureq(e) => match &*e {
            ureq::Error::Status(code, _) => format!("http status {code}"),
            other => format!("request failed: {other}"),
        },
        // A rejected redirect is a reachable server misbehaving:
        // its own message, and NOT the transport-failure class.
        CallError::Rejected(m) => m,
    })?;
    let raw = read_capped(resp.into_reader())?;
    let body = String::from_utf8_lossy(&raw);
    Ok(parse_listing(&body, url))
}

/// Find `needle` (ASCII) in `haystack` at or after `from`,
/// comparing ASCII letters case-insensitively. The returned
/// offset indexes `haystack` itself. Searching a lowercased
/// COPY instead — the previous approach — breaks whenever a
/// character's lowercase form has a different byte length
/// (U+0130 İ expands 2 → 3 bytes): every later offset shifts
/// and slicing the original at a shifted offset panics on a
/// char boundary (audit slices 1/3; Bengali/Hindi/Turkish
/// titles make this a normal-use crash, not an edge case).
fn find_ci(haystack: &str, needle: &str, from: usize) -> Option<usize> {
    let h = haystack.as_bytes();
    let n = needle.as_bytes();
    if n.is_empty() || from > h.len() || h.len() - from < n.len() {
        return None;
    }
    (from..=h.len() - n.len()).find(|&i| {
        h[i..i + n.len()]
            .iter()
            .zip(n.iter())
            .all(|(a, b)| a.eq_ignore_ascii_case(b))
    })
}

/// The largest char boundary at or below `i`.
fn floor_char_boundary(s: &str, mut i: usize) -> usize {
    if i > s.len() {
        i = s.len();
    }
    while i > 0 && !s.is_char_boundary(i) {
        i -= 1;
    }
    i
}

pub fn parse_listing(html: &str, base_url: &str) -> Vec<DirEntry> {
    let mut out = Vec::new();
    let mut pos = 0usize;
    while let Some(a_start) = find_ci(html, "<a ", pos) {
        let Some(tag_end) = html[a_start..].find('>') else {
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
        let text_end = find_ci(html, "</a>", pos).unwrap_or(pos);
        let raw_text = strip_tags(&html[pos..text_end]);
        let name = percent_decode(raw_text.trim());
        if name.is_empty() || name == "Parent Directory" || name.starts_with("?") {
            continue;
        }
        // The rest of the row (to end of line / row) may carry a size.
        let row_end = find_ci(html, "</tr>", text_end)
            .unwrap_or_else(|| floor_char_boundary(html, text_end + 200));
        let size = parse_size_after(&html[text_end..row_end]);
        let is_dir = href.ends_with('/');
        let clean_href = href.trim_end_matches('/');
        let decoded_href = percent_decode(clean_href);
        let display = if name == decoded_href || name.is_empty() {
            decoded_href
        } else {
            name
        };
        // Cross-origin entries are dropped by join_url (server
        // confinement) — skip them entirely.
        let Some(url) = join_url(base_url, &href) else {
            continue;
        };
        out.push(DirEntry {
            name: display,
            url,
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
    // find_ci for the same reason as parse_listing: offsets
    // from a lowercased copy can shift and panic.
    let i = find_ci(tag, &format!("{attr}="), 0)? + attr.len() + 1;
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

/// (scheme, authority) of an absolute URL, if it has both.
fn origin_of(url: &str) -> Option<(&str, &str)> {
    let scheme_end = url.find("://")?;
    let after = &url[scheme_end + 3..];
    let auth_end = after.find('/').unwrap_or(after.len());
    Some((&url[..scheme_end], &after[..auth_end]))
}

fn same_origin(a: &str, b: &str) -> bool {
    match (origin_of(a), origin_of(b)) {
        (Some((s1, h1)), Some((s2, h2))) => {
            s1.eq_ignore_ascii_case(s2) && h1.eq_ignore_ascii_case(h2)
        }
        _ => false,
    }
}

/// Resolve `href` against `base`, confined to the base's
/// origin. A listing is served BY a source, so an entry
/// pointing at a different scheme/host/port is not part of
/// the catalog — trusting it (the previous behavior) let any
/// listed server steer the crawler, poster fetcher and
/// downloader at arbitrary addresses, including LAN services
/// (audit slices 1/3/9). Cross-origin absolute hrefs and
/// scheme-relative hrefs resolve to None; the caller drops
/// the entry.
fn join_url(base: &str, href: &str) -> Option<String> {
    if href.starts_with("http://") || href.starts_with("https://") {
        return same_origin(base, href).then(|| href.to_string());
    }
    if href.starts_with("//") {
        // Scheme-relative: another authority in disguise.
        return None;
    }
    if let Some(stripped) = href.strip_prefix('/') {
        // Absolute path: keep scheme + authority of the base.
        if let Some(scheme_end) = base.find("://") {
            let after = &base[scheme_end + 3..];
            let auth_end = after
                .find('/')
                .map(|i| scheme_end + 3 + i)
                .unwrap_or(base.len());
            return Some(format!("{}/{}", &base[..auth_end], stripped));
        }
    }
    let base = if base.ends_with('/') {
        base.to_string()
    } else {
        format!("{base}/")
    };
    Some(format!("{base}{href}"))
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
/// Field defaults keep a partially-written persisted plan loadable
/// (V2 RC-5): a missing `end` means the open segment, never a
/// zero-length one, so corrupt data cannot fake a completion.
#[derive(Debug, Clone, Copy, serde::Serialize, serde::Deserialize)]
pub struct Segment {
    #[serde(default)]
    pub start: u64,
    #[serde(default = "default_segment_end")]
    pub end: u64, // inclusive; u64::MAX = to EOF (unknown total)
    #[serde(default)]
    pub done: u64,
}

fn default_segment_end() -> u64 {
    u64::MAX
}

impl Segment {
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn len(&self) -> u64 {
        if self.end == u64::MAX {
            u64::MAX
        } else {
            // Checked: corrupt geometry (end < start) — only
            // reachable via hand-edited persisted state — must
            // not wrap in release or panic in debug builds
            // (audit slices 1 + 8). Invalid geometry has no
            // length; restore validation drops such plans.
            self.end.checked_sub(self.start).map_or(0, |d| d + 1)
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
    if let Ok(resp) = call_following(&agent, true, url, |r| r) {
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
    let resp = call_following(&agent, false, url, |r| r.set("Range", "bytes=0-0"))
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
    // The plan's expected total size, when known: every 206 the
    // workers accept must declare exactly this total in its
    // Content-Range (audit P1, 2026-11-28 — see the CP-2 check in
    // the segment worker).
    let expected_total: Option<u64> = if total_known {
        let total = segments.iter().map(|s| s.len()).sum::<u64>();
        let _ = file.set_len(total);
        Some(total)
    } else {
        None
    };
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
            let agent = transfer_agent();
            // Bounded retries with backoff: a server that accepts then
            // drops, trickles, or answers 206 with empty bodies must fail
            // the segment — the old loop re-requested the remaining range
            // immediately, forever, at full speed.
            let mut stalled = 0u32;
            // Set from a transient response's Retry-After header:
            // the next stall sleep waits at least this long.
            let mut retry_floor = Duration::ZERO;
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
                // Redirects follow the S4 policy via
                // call_following; Range and If-Range are re-sent
                // on every (same-host) hop.
                let resp = match call_following(&agent, false, &url, |mut req| {
                    req = req.set("Range", &range);
                    if let Some(v) = &validator {
                        // If-Range guards against the file changing under a resume.
                        req = req.set("If-Range", v);
                    }
                    req
                }) {
                    Ok(r) => Some(r),
                    Err(CallError::Rejected(msg)) => {
                        progress.failed.store(true, Ordering::SeqCst);
                        *progress.message.lock().unwrap_or_else(|e| e.into_inner()) = Some(msg);
                        return;
                    }
                    Err(CallError::Ureq(e)) => match &*e {
                        ureq::Error::Status(416, _) => {
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
                        ureq::Error::Status(code, r) => {
                            if is_transient_status(*code) {
                                // Audit fix (2026-11-28): 429/5xx is
                                // the server asking for patience, not
                                // a verdict on the file — retry
                                // through the stall/backoff ladder
                                // below (bounded by the same budget),
                                // honoring Retry-After when sent.
                                // The HTTP-date form is not parsed;
                                // the ladder's own backoff applies.
                                if let Some(secs) = r
                                    .header("Retry-After")
                                    .and_then(|v| v.trim().parse::<u64>().ok())
                                {
                                    retry_floor = Duration::from_secs(secs.min(300));
                                }
                                None
                            } else {
                                progress.failed.store(true, Ordering::SeqCst);
                                *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                                    Some(format!("segment failed: HTTP {code}"));
                                return;
                            }
                        }
                        // Transport errors (refused, reset, timeout) retry
                        // through the stall/backoff path below.
                        ureq::Error::Transport(_) => None,
                    },
                };
                let mut hit_eof = false;
                if let Some(resp) = resp {
                    let status = resp.status();
                    // CP-2 (DOWNLOAD-ENGINE.md): a 206 is only usable
                    // when its Content-Range starts exactly at the
                    // requested offset and ends at the requested end
                    // (any end for an open segment). A missing or
                    // mismatched header means the body is NOT the
                    // range we asked for — writing it at `from` would
                    // silently corrupt the file (the pre-V2 engine did
                    // exactly that). An unusable response writes zero
                    // bytes; the stall judgement below retries it a
                    // bounded number of times, then fails the task.
                    let mut usable = status == 200;
                    if status == 206 {
                        usable = resp
                            .header("Content-Range")
                            .and_then(parse_content_range)
                            .map(|(cs, ce, total)| {
                                cs == from
                                    && ce >= cs
                                    && (to == u64::MAX || ce == to)
                                    // Audit P1 (2026-11-28): the
                                    // declared total must also match
                                    // the plan's expected size. A
                                    // server that swaps the file
                                    // mid-download (or ignores
                                    // If-Range) can answer a perfectly
                                    // in-range 206 for a DIFFERENT
                                    // version of the file — start/end
                                    // alone cannot detect that, the
                                    // total can. A missing total with
                                    // a known plan size is likewise
                                    // unusable.
                                    && match (total, expected_total) {
                                        (Some(t), Some(exp)) => t == exp,
                                        (None, Some(_)) => false,
                                        _ => true,
                                    }
                            })
                            .unwrap_or(false);
                    }
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
                    if usable {
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
                                        *progress
                                            .message
                                            .lock()
                                            .unwrap_or_else(|e| e.into_inner()) =
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
                    let backoff_ms = (250u64 << stalled.min(4))
                        .min(4000)
                        .max(retry_floor.as_millis() as u64);
                    retry_floor = Duration::ZERO;
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
                        // CP-5 durability ordering: the bytes must be
                        // on stable storage before the manager renames
                        // .part to the final name — a power loss after
                        // rename must not expose a hollow file.
                        let _ = f.sync_all();
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
    fn segment_len_never_wraps_on_corrupt_geometry() {
        // Audit slices 1 + 8: end < start must yield 0, not a
        // wrapped huge length (release) or a panic (debug).
        let bad = Segment {
            start: 100,
            end: 50,
            done: 0,
        };
        assert_eq!(bad.len(), 0);
        let ok = Segment {
            start: 10,
            end: 19,
            done: 3,
        };
        assert_eq!(ok.len(), 10);
        let open = Segment {
            start: 0,
            end: u64::MAX,
            done: 0,
        };
        assert_eq!(open.len(), u64::MAX);
    }

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
    fn content_range_parses_valid_and_rejects_garbage() {
        assert_eq!(
            parse_content_range("bytes 100-199/1234"),
            Some((100, 199, Some(1234)))
        );
        assert_eq!(parse_content_range("bytes 0-499/*"), Some((0, 499, None)));
        assert_eq!(parse_content_range("bytes */1234"), None);
        assert_eq!(parse_content_range("items 0-1/2"), None);
        assert_eq!(parse_content_range("bytes 5-3/10"), Some((5, 3, Some(10))));
        assert_eq!(parse_content_range(""), None);
    }

    #[test]
    fn transient_statuses_are_the_retryable_set() {
        for code in [429u16, 500, 502, 503, 504] {
            assert!(is_transient_status(code), "{code} must be transient");
        }
        for code in [200u16, 206, 301, 400, 401, 403, 404, 410, 416, 501] {
            assert!(!is_transient_status(code), "{code} must fail at once");
        }
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
    fn parse_listing_survives_expanding_lowercase_chars() {
        // U+0130 (İ) lowercases to a 3-byte sequence: offsets
        // taken from a lowercased copy shifted and panicked on
        // the next slice (audit). The title must survive intact.
        let html =
            "<tr><td><a href=\"İstanbul Film.mkv\">İstanbul Film.mkv</a></td><td>5M</td></tr>\
                    <tr><td><a href=\"Second.mkv\">Second.mkv</a></td><td>6M</td></tr>";
        let entries = parse_listing(html, "http://172.16.50.7/Movies/");
        assert_eq!(entries.len(), 2);
        assert!(entries.iter().any(|e| e.name == "İstanbul Film.mkv"));
        assert!(entries.iter().any(|e| e.name == "Second.mkv"));
    }

    #[test]
    fn parse_listing_drops_cross_origin_hrefs() {
        // A listed server must not steer the crawler elsewhere
        // (audit slices 1/3/9): other hosts, other ports and
        // scheme-relative hrefs are dropped; same-origin
        // absolute and relative hrefs survive.
        let html = "<a href=\"http://evil.example/x.mkv\">Evil.mkv</a>\
                    <a href=\"http://172.16.50.7:9999/y.mkv\">Port.mkv</a>\
                    <a href=\"//evil.example/z.mkv\">SchemeRel.mkv</a>\
                    <a href=\"http://172.16.50.7/Movies/Good.mkv\">Good.mkv</a>\
                    <a href=\"Rel.mkv\">Rel.mkv</a>";
        let entries = parse_listing(html, "http://172.16.50.7/Movies/");
        let names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(names, vec!["Good.mkv", "Rel.mkv"]);
    }

    #[test]
    fn parse_listing_row_without_tr_end_keeps_char_boundary() {
        // No </tr>: the fallback window (+200 bytes) can land
        // inside a multi-byte character — slicing there used to
        // panic. The leading 'x' shifts the 2-byte é run so the
        // window edge falls mid-character.
        let title = "é".repeat(150);
        let html = format!("<a href=\"f.mkv\">f.mkv</a> 5M x{title}");
        let entries = parse_listing(&html, "http://172.16.50.7/");
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].size, Some(5 * 1024 * 1024));
    }

    #[test]
    fn redirect_policy_same_host_followed() {
        // Relative and absolute-path locations resolve on the
        // same origin; an http -> https upgrade is allowed; a
        // port change keeps the same host (mirrors Android's
        // host comparison).
        assert_eq!(
            redirect_target("http://172.16.50.7/Movies/f.mkv", "/Movies2/f.mkv").unwrap(),
            "http://172.16.50.7/Movies2/f.mkv"
        );
        assert_eq!(
            redirect_target("http://172.16.50.7/Movies/f.mkv", "g.mkv").unwrap(),
            "http://172.16.50.7/Movies/g.mkv"
        );
        assert_eq!(
            redirect_target("http://172.16.50.7/f.mkv", "https://172.16.50.7/f.mkv").unwrap(),
            "https://172.16.50.7/f.mkv"
        );
        assert!(
            redirect_target("http://172.16.50.7/f.mkv", "http://172.16.50.7:8080/f.mkv").is_ok()
        );
    }

    #[test]
    fn redirect_policy_rejects_cross_host_and_downgrade() {
        let cross = redirect_target("http://172.16.50.7/f.mkv", "http://evil.example/f.mkv");
        assert!(
            matches!(&cross, Err(CallError::Rejected(m)) if m.contains("another host")),
            "{cross:?}"
        );
        let scheme_rel = redirect_target("http://172.16.50.7/f.mkv", "//evil.example/f.mkv");
        assert!(
            matches!(&scheme_rel, Err(CallError::Rejected(m)) if m.contains("another host")),
            "{scheme_rel:?}"
        );
        let down = redirect_target("https://172.16.50.7/f.mkv", "http://172.16.50.7/f.mkv");
        assert!(
            matches!(&down, Err(CallError::Rejected(m)) if m.contains("unencrypted")),
            "{down:?}"
        );
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
