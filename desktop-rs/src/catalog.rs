//! The live catalog, ported from the Android app's LiveServerClient +
//! LiveFeedSession: categories discovered from server roots, a
//! progressive newest-first feed of titles per category, on-demand file
//! loading (with seasons), and direct search (h5ai API with a breadth
//! fallback). Nothing is crawled into a database — listings are fetched
//! live and cached for 10 minutes, exactly like the phone app.

use crate::core::{fetch_listing, http_agent, DirEntry};
use crate::meta::*;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

// ------------------------------------------------------------------- types

#[derive(Debug, Clone)]
pub struct CatalogFile {
    pub file_name: String,
    pub file_url: String,
    pub size: i64, // -1 = unknown
    pub quality: Option<String>,
    pub source_tag: Option<String>,
    pub codec: Option<String>,
    pub audio: Option<String>,
    pub season: Option<i32>,
    pub episode: Option<i32>,
}

#[derive(Debug, Clone)]
pub struct Title {
    pub folder_url: String,
    pub source_name: String,
    pub category: String,
    pub title: String,
    pub raw_name: String,
    pub year: Option<i32>,
    pub kind: String, // "movie" | "series"
    pub poster_url: Option<String>,
    pub language: Option<String>,
    pub files: Vec<CatalogFile>,
    pub files_loaded: bool,
    pub season_count: usize,
    pub max_size: i64,
    pub best_quality: Option<String>,
    pub best_source_tag: Option<String>,
    pub best_codec: Option<String>,
    pub dual_audio: bool,
}

#[derive(Debug, Clone)]
pub struct Category {
    pub name: String,
    /// (source name, category folder URL) — one per server that has it.
    pub locations: Vec<(String, String)>,
}

// ------------------------------------------------------------- listing cache

static LISTING_CACHE: LazyLock<Mutex<HashMap<String, (Instant, Vec<DirEntry>)>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
const LISTING_TTL: Duration = Duration::from_secs(600);

pub fn fetch_cached(url: &str) -> Result<Vec<DirEntry>, String> {
    {
        let cache = LISTING_CACHE.lock().unwrap();
        if let Some((at, entries)) = cache.get(url) {
            if at.elapsed() < LISTING_TTL {
                return Ok(entries.clone());
            }
        }
    }
    let entries = fetch_listing(url)?;
    LISTING_CACHE
        .lock()
        .unwrap()
        .insert(url.to_string(), (Instant::now(), entries.clone()));
    Ok(entries)
}

pub fn clear_listing_cache() -> usize {
    let mut cache = LISTING_CACHE.lock().unwrap();
    let n = cache.len();
    cache.clear();
    n
}

// ------------------------------------------------------------------- files

pub fn to_catalog_file(title_name: &str, entry: &DirEntry, folder_season: Option<i32>) -> CatalogFile {
    let haystack = format!("{title_name} {}", entry.name);
    let (mut season, mut episode) = season_episode(title_name, &entry.name);
    if season.is_none() {
        season = folder_season;
    }
    if episode.is_none() && (folder_season.is_some() || season.is_some()) {
        episode = episode_only(&entry.name);
    }
    if season.is_none() && folder_season.is_some() {
        season = folder_season;
    }
    CatalogFile {
        file_name: entry.name.clone(),
        file_url: entry.url.clone(),
        size: entry.size.map(|s| s as i64).unwrap_or(-1),
        quality: parse_quality(&haystack),
        source_tag: parse_source_tag(&haystack),
        codec: parse_codec(&haystack),
        audio: parse_audio(&haystack),
        season,
        episode,
    }
}

fn quality_rank(q: &str) -> usize {
    ["2160p", "1080p", "720p", "480p", "360p", "CAM"]
        .iter()
        .position(|x| *x == q)
        .unwrap_or(usize::MAX)
}

fn best_quality(values: &[String]) -> Option<String> {
    values.iter().min_by_key(|q| quality_rank(q)).cloned()
}

/// buildLiveTitleFromEntries: a folder becomes a title when it directly
/// holds videos or season subfolders.
pub fn build_title(
    source_name: &str,
    category: &str,
    folder_url: &str,
    raw_name: &str,
    entries: &[DirEntry],
) -> Option<Title> {
    let videos: Vec<&DirEntry> = entries.iter().filter(|e| !e.is_dir && is_video_file(&e.name)).collect();
    let season_dirs: Vec<&DirEntry> = entries.iter().filter(|e| e.is_dir && is_season_like(&e.name)).collect();
    if videos.is_empty() && season_dirs.is_empty() {
        return None;
    }
    let files: Vec<CatalogFile> = videos.iter().map(|e| to_catalog_file(raw_name, e, None)).collect();
    let is_series = !season_dirs.is_empty() || files.iter().any(|f| f.season.is_some() || f.episode.is_some());
    let haystack = format!("{raw_name} {}", files.first().map(|f| f.file_name.as_str()).unwrap_or(""));
    let cat = if category.is_empty() { "General" } else { category };
    Some(Title {
        folder_url: folder_url.to_string(),
        source_name: source_name.to_string(),
        category: cat.to_string(),
        title: clean_title(raw_name),
        raw_name: raw_name.to_string(),
        year: parse_year(&haystack),
        kind: if is_series { "series".into() } else { "movie".into() },
        poster_url: pick_poster(entries),
        language: parse_language(cat),
        files_loaded: season_dirs.is_empty(),
        season_count: season_dirs.len(),
        max_size: files.iter().map(|f| f.size).max().unwrap_or(-1),
        best_quality: best_quality(&files.iter().filter_map(|f| f.quality.clone()).collect::<Vec<_>>())
            .or_else(|| parse_quality(raw_name)),
        best_source_tag: files
            .iter()
            .filter_map(|f| f.source_tag.clone())
            .next()
            .or_else(|| parse_source_tag(raw_name)),
        best_codec: files
            .iter()
            .filter_map(|f| f.codec.clone())
            .next()
            .or_else(|| parse_codec(raw_name)),
        dual_audio: files.iter().any(|f| f.audio.is_some()) || parse_audio(raw_name).is_some(),
        files,
    })
}

/// loadFiles: direct videos plus every season subfolder (one level of
/// nesting), sorted by season, episode, name.
pub fn load_files(title: &Title) -> Vec<CatalogFile> {
    let Ok(root) = fetch_cached(&title.folder_url) else {
        return title.files.clone();
    };
    let mut out: Vec<CatalogFile> = root
        .iter()
        .filter(|e| !e.is_dir && is_video_file(&e.name))
        .map(|e| to_catalog_file(&title.raw_name, e, None))
        .collect();
    for season_dir in root.iter().filter(|e| e.is_dir && is_season_like(&e.name)) {
        load_season_dir(&title.raw_name, season_dir, parse_season(&season_dir.name), 0, &mut out);
    }
    out.sort_by(|a, b| {
        (a.season.unwrap_or(0), a.episode.unwrap_or(0), a.file_name.to_lowercase())
            .cmp(&(b.season.unwrap_or(0), b.episode.unwrap_or(0), b.file_name.to_lowercase()))
    });
    out
}

fn load_season_dir(
    show_name: &str,
    dir: &DirEntry,
    parent_season: Option<i32>,
    depth: usize,
    out: &mut Vec<CatalogFile>,
) {
    let Ok(entries) = fetch_cached(&dir.url) else { return };
    let season = parent_season.or_else(|| parse_season(&dir.name));
    for e in &entries {
        if !e.is_dir && is_video_file(&e.name) {
            out.push(to_catalog_file(show_name, e, season));
        } else if e.is_dir && depth < 1 && is_season_like(&e.name) {
            load_season_dir(show_name, e, season, depth + 1, out);
        }
    }
}

// --------------------------------------------------------------- categories

fn category_priority(name: &str) -> usize {
    let n = name.to_lowercase();
    if n.contains("hindi") {
        0
    } else if n.contains("english") {
        1
    } else if n.contains("south indian") {
        2
    } else if n.contains("bangla") {
        3
    } else if n.contains("animation") {
        4
    } else if n.contains("imdb") {
        5
    } else if n.contains("series") || n.contains("anime") {
        6
    } else {
        7
    }
}

/// discoverCategories: the root folders of every enabled server, merged
/// by name across servers (junk skipped), media categories first.
pub fn discover_categories(sources: &[(String, String)]) -> Vec<Category> {
    let mut handles = Vec::new();
    for (name, root) in sources.to_vec() {
        handles.push(std::thread::spawn(move || {
            let entries = fetch_cached(&root).unwrap_or_default();
            (name, entries)
        }));
    }
    let mut by_name: Vec<Category> = Vec::new();
    for h in handles {
        let Ok((source_name, entries)) = h.join() else { continue };
        for e in entries {
            if !e.is_dir || is_junk_folder(&e.name) {
                continue;
            }
            match by_name.iter_mut().find(|c| c.name == e.name) {
                Some(cat) => {
                    if !cat.locations.iter().any(|(_, u)| u == &e.url) {
                        cat.locations.push((source_name.clone(), e.url.clone()));
                    }
                }
                None => by_name.push(Category {
                    name: e.name.clone(),
                    locations: vec![(source_name.clone(), e.url.clone())],
                }),
            }
        }
    }
    by_name.sort_by(|a, b| {
        (is_non_media_category(&a.name), category_priority(&a.name), a.name.to_lowercase())
            .cmp(&(is_non_media_category(&b.name), category_priority(&b.name), b.name.to_lowercase()))
    });
    by_name
}

// --------------------------------------------------------------------- feed

#[derive(Clone)]
struct FeedNode {
    source_name: String,
    category: String,
    url: String,
    name: String,
    depth: usize,
}

fn extract_folder_year(name: &str) -> Option<i32> {
    let n = name.trim();
    let inner = n.strip_prefix('(').and_then(|s| s.strip_suffix(')')).unwrap_or(n);
    if inner.len() == 4 && inner.chars().all(|c| c.is_ascii_digit()) {
        let y: i32 = inner.parse().ok()?;
        if (1900..=2099).contains(&y) {
            return Some(y);
        }
    }
    None
}

fn is_container_folder(name: &str) -> bool {
    if extract_folder_year(name).is_some() {
        return true;
    }
    let n = name.trim();
    n == "0-9"
        || (n.len() == 1 && n.chars().next().map(|c| c.is_ascii_alphabetic()).unwrap_or(false))
        || (n.len() == 5
            && n.chars().nth(1) == Some('-')
            && n.chars().next().map(|c| c.is_ascii_alphabetic()).unwrap_or(false)
            && n.chars().nth(2).map(|c| c.is_ascii_alphabetic()).unwrap_or(false))
}

fn sort_newest_first(mut dirs: Vec<&DirEntry>) -> Vec<&DirEntry> {
    dirs.sort_by(|a, b| {
        (extract_folder_year(&b.name).unwrap_or(i32::MIN), a.name.to_lowercase())
            .cmp(&(extract_folder_year(&a.name).unwrap_or(i32::MIN), b.name.to_lowercase()))
    });
    dirs
}

/// LiveFeedSession port: progressive DFS over category folders, newest
/// containers first; each page expands only enough folders to fill it.
pub struct FeedSession {
    streams: Vec<VecDeque<FeedNode>>,
    seen: HashSet<String>,
    emitted: HashSet<String>,
    pub has_more: bool,
}

impl FeedSession {
    pub fn new(categories: &[Category], selected: Option<&str>) -> Self {
        let mut streams = Vec::new();
        let mut seen = HashSet::new();
        for cat in categories {
            let chosen = match selected {
                None => !is_non_media_category(&cat.name),
                Some(sel) => cat.name.eq_ignore_ascii_case(sel),
            };
            if !chosen {
                continue;
            }
            let mut stack = VecDeque::new();
            for (source_name, url) in &cat.locations {
                if seen.insert(url.clone()) {
                    stack.push_back(FeedNode {
                        source_name: source_name.clone(),
                        category: cat.name.clone(),
                        url: url.clone(),
                        name: cat.name.clone(),
                        depth: 0,
                    });
                }
            }
            if !stack.is_empty() {
                streams.push(stack);
            }
        }
        let has_more = !streams.is_empty();
        Self { streams, seen, emitted: HashSet::new(), has_more }
    }

    pub fn next_page(&mut self, page_size: usize) -> Vec<Title> {
        const MAX_DEPTH: usize = 4;
        let mut collected: Vec<Title> = Vec::new();
        let mut requests = 0usize;
        let max_requests = page_size * 6;
        let mut turn = 0usize;
        while collected.len() < page_size && requests < max_requests {
            // Round-robin: take the front node of the next non-empty stream.
            let mut node: Option<FeedNode> = None;
            for _ in 0..self.streams.len().max(1) {
                if self.streams.is_empty() {
                    break;
                }
                let idx = turn % self.streams.len();
                turn += 1;
                if !self.streams[idx].is_empty() {
                    node = self.streams[idx].pop_front();
                    break;
                }
            }
            let Some(node) = node else { break };
            requests += 1;
            let Ok(entries) = fetch_cached(&node.url) else { continue };

            if node.depth > 0 && !is_container_folder(&node.name) {
                if let Some(title) = build_title(&node.source_name, &node.category, &node.url, &node.name, &entries) {
                    if self.emitted.insert(title.folder_url.clone()) {
                        collected.push(title);
                    }
                    continue;
                }
            }
            if node.depth >= MAX_DEPTH {
                continue;
            }
            let child_dirs: Vec<&DirEntry> = entries
                .iter()
                .filter(|e| e.is_dir && !is_junk_folder(&e.name) && !is_season_like(&e.name))
                .collect();
            // Push reversed so the newest pops first (stack front = next).
            let stream_idx = self
                .streams
                .iter()
                .position(|s| s.iter().any(|n| n.category == node.category && n.source_name == node.source_name))
                .unwrap_or(0);
            for child in sort_newest_first(child_dirs).into_iter().rev() {
                if !self.emitted.contains(&child.url) && self.seen.insert(child.url.clone()) {
                    let target = if stream_idx < self.streams.len() { stream_idx } else { 0 };
                    if target < self.streams.len() {
                        self.streams[target].push_front(FeedNode {
                            source_name: node.source_name.clone(),
                            category: node.category.clone(),
                            url: child.url.clone(),
                            name: child.name.clone(),
                            depth: node.depth + 1,
                        });
                    }
                }
            }
        }
        self.has_more = self.streams.iter().any(|s| !s.is_empty());
        collected
    }
}

// ------------------------------------------------------------------- search

pub fn percent_decode(raw: &str) -> String {
    let kept = raw.replace('+', "\u{1}");
    let bytes = kept.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(v) = u8::from_str_radix(&kept[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).replace('\u{1}', "+")
}

pub fn decode_last_segment(url: &str) -> String {
    percent_decode(url.trim_end_matches('/').rsplit('/').next().unwrap_or(""))
}

pub fn extract_category(root_url: &str, folder_url: &str) -> String {
    let rel = folder_url.strip_prefix(root_url).unwrap_or("").trim_matches('/');
    percent_decode(rel.split('/').next().unwrap_or(""))
}

fn resolve_title_folder(dir_url: &str, root_url: &str) -> (String, String, bool) {
    let dir_name = decode_last_segment(dir_url);
    if is_season_like(&dir_name) {
        let parent = format!("{}/", dir_url.trim_end_matches('/').rsplit_once('/').map(|(a, _)| a).unwrap_or(""));
        if parent.len() > root_url.len() {
            let name = decode_last_segment(&parent);
            return (parent, name, true);
        }
    }
    (dir_url.to_string(), dir_name, false)
}

struct H5aiItem {
    href: String,
    size: i64,
    is_dir: bool,
}

fn h5ai_search(source_root: &str, query: &str) -> Option<Vec<H5aiItem>> {
    let root_path = {
        let after_scheme = source_root.split("://").nth(1).unwrap_or("");
        match after_scheme.find('/') {
            Some(i) => after_scheme[i..].to_string(),
            None => "/".to_string(),
        }
    };
    let esc = |v: &str| v.replace('\\', "\\\\").replace('"', "\\\"");
    let body = format!(
        "{{\"action\":\"get\",\"search\":{{\"href\":\"{}\",\"pattern\":\"{}\",\"ignorecase\":true}}}}",
        esc(&root_path),
        esc(query)
    );
    let endpoint = format!("{}?", source_root.trim_end_matches('/'));
    let resp = http_agent().post(&endpoint).send_string(&body).ok()?;
    let text = resp.into_string().ok()?;
    let json: serde_json::Value = serde_json::from_str(&text).ok()?;
    let arr = json.get("search")?.as_array()?;
    Some(
        arr.iter()
            .filter_map(|item| {
                let href = item.get("href")?.as_str()?.to_string();
                Some(H5aiItem {
                    is_dir: href.ends_with('/'),
                    size: item.get("size").and_then(|v| v.as_i64()).unwrap_or(-1),
                    href,
                })
            })
            .collect(),
    )
}

fn resolve_href(root_url: &str, href: &str) -> Option<String> {
    if href.starts_with("http://") || href.starts_with("https://") {
        return Some(href.to_string());
    }
    // h5ai hrefs are absolute paths from the server root.
    let origin = {
        let scheme_end = root_url.find("://")? + 3;
        let host_end = root_url[scheme_end..].find('/').map(|i| scheme_end + i).unwrap_or(root_url.len());
        &root_url[..host_end]
    };
    if let Some(p) = href.strip_prefix('/') {
        Some(format!("{origin}/{p}"))
    } else {
        Some(format!("{}{}", root_url, href))
    }
}

fn group_h5ai_hits(source_name: &str, root_url: &str, items: Vec<H5aiItem>) -> Vec<Title> {
    let mut title_dirs: Vec<(String, String)> = Vec::new(); // (folder_url, raw_name)
    let mut files_by_title: HashMap<String, Vec<CatalogFile>> = HashMap::new();
    let mut series_set: HashSet<String> = HashSet::new();
    for item in items {
        let Some(abs) = resolve_href(root_url, &item.href) else { continue };
        if !abs.starts_with(root_url) {
            continue;
        }
        if item.is_dir {
            let dir_name = decode_last_segment(&abs);
            if dir_name.is_empty() || is_junk_folder(&dir_name) {
                continue;
            }
            let rel = abs.strip_prefix(root_url).unwrap_or("").trim_matches('/');
            if !rel.contains('/') {
                continue; // category roots themselves
            }
            if is_non_media_category(rel.split('/').next().unwrap_or("")) {
                continue;
            }
            let (title_url, title_name, is_season) = resolve_title_folder(&abs, root_url);
            if !title_dirs.iter().any(|(u, _)| u == &title_url) {
                title_dirs.push((title_url.clone(), title_name));
            }
            if is_season {
                series_set.insert(title_url);
            }
        } else {
            let file_name = decode_last_segment(&abs);
            if !is_video_file(&file_name) {
                continue;
            }
            let parent = format!("{}/", abs.rsplit_once('/').map(|(a, _)| a).unwrap_or(""));
            let (title_url, title_name, is_season) = resolve_title_folder(&parent, root_url);
            if !title_dirs.iter().any(|(u, _)| u == &title_url) {
                title_dirs.push((title_url.clone(), title_name.clone()));
            }
            if is_season {
                series_set.insert(title_url.clone());
            }
            let season_dir = if is_season { Some(decode_last_segment(&parent)) } else { None };
            let entry = DirEntry {
                name: file_name,
                url: abs,
                is_dir: false,
                size: if item.size >= 0 { Some(item.size as u64) } else { None },
            };
            files_by_title
                .entry(title_url)
                .or_default()
                .push(to_catalog_file(&title_name, &entry, season_dir.as_deref().and_then(parse_season)));
        }
    }
    title_dirs
        .into_iter()
        .map(|(folder_url, raw_name)| {
            let files = files_by_title.get(&folder_url).cloned().unwrap_or_default();
            let category = extract_category(root_url, &folder_url);
            let cat = if category.is_empty() { "General".to_string() } else { category };
            let is_series = series_set.contains(&folder_url)
                || files.iter().any(|f| f.season.is_some() || f.episode.is_some());
            let haystack =
                format!("{raw_name} {}", files.first().map(|f| f.file_name.as_str()).unwrap_or(""));
            Title {
                folder_url,
                source_name: source_name.to_string(),
                category: cat.clone(),
                title: clean_title(&raw_name),
                raw_name: raw_name.clone(),
                year: parse_year(&haystack),
                kind: if is_series { "series".into() } else { "movie".into() },
                poster_url: None,
                language: parse_language(&cat),
                files_loaded: false,
                season_count: files.iter().filter_map(|f| f.season).collect::<HashSet<_>>().len(),
                max_size: files.iter().map(|f| f.size).max().unwrap_or(-1),
                best_quality: best_quality(&files.iter().filter_map(|f| f.quality.clone()).collect::<Vec<_>>())
                    .or_else(|| parse_quality(&raw_name)),
                best_source_tag: files
                    .iter()
                    .filter_map(|f| f.source_tag.clone())
                    .next()
                    .or_else(|| parse_source_tag(&raw_name)),
                best_codec: files
                    .iter()
                    .filter_map(|f| f.codec.clone())
                    .next()
                    .or_else(|| parse_codec(&raw_name)),
                dual_audio: files.iter().any(|f| f.audio.is_some()) || parse_audio(&raw_name).is_some(),
                files,
            }
        })
        .collect()
}

fn breadth_search(source_name: &str, root_url: &str, query: &str) -> Vec<Title> {
    let needle = query.to_lowercase();
    let mut queue: VecDeque<(String, usize)> = VecDeque::new();
    let mut seen: HashSet<String> = HashSet::new();
    queue.push_back((root_url.to_string(), 0));
    seen.insert(root_url.to_string());
    let mut results: Vec<Title> = Vec::new();
    let mut scanned = 0usize;
    while let Some((url, depth)) = queue.pop_front() {
        if scanned >= 60 || results.len() >= 40 {
            break;
        }
        let Ok(entries) = fetch_cached(&url) else { continue };
        scanned += 1;
        let category = extract_category(root_url, &url);
        let cat = if category.is_empty() { "General".to_string() } else { category };
        for e in &entries {
            if e.is_dir {
                if is_junk_folder(&e.name) {
                    continue;
                }
                if depth == 0 && is_non_media_category(&e.name) {
                    continue;
                }
                if e.name.to_lowercase().contains(&needle) && depth >= 1 {
                    if let Ok(folder_entries) = fetch_cached(&e.url) {
                        if let Some(t) = build_title(source_name, &cat, &e.url, &e.name, &folder_entries) {
                            if !results.iter().any(|x| x.folder_url == t.folder_url) {
                                results.push(t);
                            }
                        }
                    }
                } else if depth < 2 && seen.insert(e.url.clone()) {
                    queue.push_back((e.url.clone(), depth + 1));
                }
            } else if is_video_file(&e.name) && e.name.to_lowercase().contains(&needle) {
                let parent_name = decode_last_segment(&url);
                let file = to_catalog_file(&parent_name, e, None);
                let is_series = file.season.is_some() || file.episode.is_some();
                let haystack = format!("{parent_name} {}", e.name);
                if !results.iter().any(|x| x.folder_url == url) {
                    results.push(Title {
                        folder_url: url.clone(),
                        source_name: source_name.to_string(),
                        category: cat.clone(),
                        title: clean_title(&parent_name),
                        raw_name: parent_name,
                        year: parse_year(&haystack),
                        kind: if is_series { "series".into() } else { "movie".into() },
                        poster_url: None,
                        language: parse_language(&cat),
                        files_loaded: false,
                        season_count: 0,
                        max_size: file.size,
                        best_quality: file.quality.clone().or_else(|| parse_quality(&haystack)),
                        best_source_tag: file.source_tag.clone(),
                        best_codec: file.codec.clone(),
                        dual_audio: file.audio.is_some(),
                        files: vec![file],
                    });
                }
            }
        }
    }
    results
}

fn rank_results(mut items: Vec<Title>, query: &str) -> Vec<Title> {
    let q = query.to_lowercase();
    fn tier(t: &str, q: &str) -> usize {
        if t == q {
            0
        } else if t.starts_with(q) {
            1
        } else if t.split(|c: char| !c.is_alphanumeric()).any(|w| w == q) {
            2
        } else if t.contains(q) {
            3
        } else {
            4
        }
    }
    items.sort_by(|a, b| {
        (tier(&a.title.to_lowercase(), &q), -(a.year.unwrap_or(0)), a.title.to_lowercase()).cmp(&(
            tier(&b.title.to_lowercase(), &q),
            -(b.year.unwrap_or(0)),
            b.title.to_lowercase(),
        ))
    });
    items
}

/// searchDirect port: h5ai search per server, breadth fallback, merged
/// by folder URL and ranked.
pub fn search(sources: &[(String, String)], query: &str) -> Vec<Title> {
    let query = query.trim().to_string();
    if query.is_empty() {
        return Vec::new();
    }
    let mut handles = Vec::new();
    for (name, root) in sources.to_vec() {
        let q = query.clone();
        handles.push(std::thread::spawn(move || {
            match h5ai_search(&root, &q) {
                Some(items) => group_h5ai_hits(&name, &root, items),
                None => breadth_search(&name, &root, &q),
            }
        }));
    }
    let mut merged: Vec<Title> = Vec::new();
    for h in handles {
        if let Ok(titles) = h.join() {
            for t in titles {
                if !merged.iter().any(|x| x.folder_url == t.folder_url) {
                    merged.push(t);
                }
            }
        }
    }
    rank_results(merged, &query)
}

/// hydrateTitle + loadFiles combined for the detail view: resolve the
/// poster from the folder listing, then load every downloadable file.
pub fn hydrate_and_load_files(mut title: Title) -> Title {
    if title.poster_url.is_none() {
        if let Ok(entries) = fetch_cached(&title.folder_url) {
            if let Some(rebuilt) = build_title(&title.source_name, &title.category, &title.folder_url, &title.raw_name, &entries) {
                title.poster_url = rebuilt.poster_url;
                if title.best_quality.is_none() {
                    title.best_quality = rebuilt.best_quality;
                }
            }
        }
    }
    title.files = load_files(&title);
    title.files_loaded = true;
    title.season_count = title.files.iter().filter_map(|f| f.season).collect::<HashSet<_>>().len();
    if title.files.iter().any(|f| f.season.is_some() || f.episode.is_some()) {
        title.kind = "series".into();
    }
    title.max_size = title.files.iter().map(|f| f.size).max().unwrap_or(title.max_size);
    title
}

// ------------------------------------------------------------ update check

fn version_gt(a: &str, b: &str) -> bool {
    let parse = |v: &str| {
        v.split('.').map(|p| p.parse::<u32>().unwrap_or(0)).collect::<Vec<_>>()
    };
    parse(a) > parse(b)
}

/// The Android app's UpdateChecker, desktop edition: the newest GitHub
/// release tagged `desktop-rs-v*`, if it is newer than this build.
pub fn check_desktop_update() -> Result<Option<String>, String> {
    let resp = http_agent()
        .get("https://api.github.com/repos/turjaun985123/lanfetch/releases?per_page=10")
        .call()
        .map_err(|e| e.to_string())?;
    let text = resp.into_string().map_err(|e| e.to_string())?;
    let json: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    let current = env!("CARGO_PKG_VERSION");
    if let Some(releases) = json.as_array() {
        for rel in releases {
            if let Some(tag) = rel.get("tag_name").and_then(|v| v.as_str()) {
                if let Some(v) = tag.strip_prefix("desktop-rs-v") {
                    return Ok(if version_gt(v, current) { Some(tag.to_string()) } else { None });
                }
            }
        }
    }
    Ok(None)
}

// ------------------------------------------------------------------ posters

/// Fetch poster bytes (bounded to ~2 MB) for the UI's ImageCache.
pub fn fetch_poster_bytes(url: &str) -> Result<Vec<u8>, String> {
    let resp = http_agent().get(url).call().map_err(|e| e.to_string())?;
    let mut buf = Vec::new();
    use std::io::Read;
    resp.into_reader()
        .take(2_000_001)
        .read_to_end(&mut buf)
        .map_err(|e| e.to_string())?;
    if buf.len() > 2_000_000 {
        return Err("poster too large".into());
    }
    Ok(buf)
}
