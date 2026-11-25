//! LanFetch Desktop (Rust + Rune) — the Android app's "Lanes" design and
//! feature set on Linux: a live Library (categories discovered from the
//! servers, a progressive title feed, metadata, posters, favorites,
//! global search, filters, title details with quality choice), Transfers
//! with the segmented ring, Servers, and Settings. Async work follows
//! Rune's native pattern: `Latest` worker-pool requests drained in
//! `poll`, and the download manager's own threads + Waker for progress.

#![forbid(unsafe_code)]

mod catalog;
mod core;
mod lanes;
mod manager;
mod meta;
mod store;

use catalog::{CatalogFile, Category, FeedSession, Title};
use core::*;
use lanes::*;
use manager::{DlState, Manager};
use rune::{
    col, el, row, text, text_input, App, AppSpec, Cx, El, ImageCache, Latest, ScrollSpec,
    SurfaceFit, TextEdit, Tx,
};
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use store::{load_favorites, load_settings, load_sources, save_favorites, save_sources, Settings, SourceItem};

// --------------------------------------------------------------------- i18n

/// UI strings for the app's chrome (nav, headers, primary actions).
/// English and Bangla, mirroring the Android app's two locales; deeper
/// strings stay English for now, as noted in Settings.
struct Ui {
    browse: &'static str,
    transfers: &'static str,
    servers: &'static str,
    settings: &'static str,
    library: &'static str,
    search: &'static str,
    clear: &'static str,
    load_more: &'static str,
    all: &'static str,
    movies: &'static str,
    series: &'static str,
    favorites: &'static str,
    newest: &'static str,
    by_title: &'static str,
    by_year: &'static str,
    size_big: &'static str,
    size_small: &'static str,
    download: &'static str,
    download_all: &'static str,
    back: &'static str,
    start: &'static str,
    check_title: &'static str,
    check_body: &'static str,
    refresh: &'static str,
}

const UI_EN: Ui = Ui {
    browse: "Browse",
    transfers: "Transfers",
    servers: "Servers",
    settings: "Settings",
    library: "Library",
    search: "Search",
    clear: "Clear",
    load_more: "Load more",
    all: "All",
    movies: "Movies",
    series: "Series",
    favorites: "Favorites",
    newest: "Newest",
    by_title: "Title",
    by_year: "Year",
    size_big: "Size ↓",
    size_small: "Size ↑",
    download: "Download",
    download_all: "Download all",
    back: "Back",
    start: "Start using LanFetch",
    check_title: "Let's check your connection",
    check_body: "LanFetch downloads from media servers on your local network. The DHAKA-FLIX servers live on a private ISP network and answer only from inside it — if every server shows unreachable, connect this computer to that network and re-check.",
    refresh: "Refresh",
};

const UI_BN: Ui = Ui {
    browse: "ব্রাউজ",
    transfers: "ট্রান্সফার",
    servers: "সার্ভার",
    settings: "সেটিংস",
    library: "লাইব্রেরি",
    search: "খুঁজুন",
    clear: "মুছুন",
    load_more: "আরও দেখুন",
    all: "সব",
    movies: "মুভি",
    series: "সিরিজ",
    favorites: "পছন্দের",
    newest: "নতুন",
    by_title: "নাম",
    by_year: "বছর",
    size_big: "সাইজ ↓",
    size_small: "সাইজ ↑",
    download: "ডাউনলোড",
    download_all: "সব ডাউনলোড",
    back: "পেছনে",
    start: "LanFetch ব্যবহার শুরু করুন",
    check_title: "আপনার সংযোগ পরীক্ষা করা যাক",
    check_body: "LanFetch আপনার লোকাল নেটওয়ার্কের মিডিয়া সার্ভার থেকে ডাউনলোড করে। DHAKA-FLIX সার্ভারগুলো একটি প্রাইভেট ISP নেটওয়ার্কে থাকে এবং শুধু সেই নেটওয়ার্কের ভেতর থেকেই উত্তর দেয় — সব সার্ভার unreachable দেখালে এই কম্পিউটারকে সেই নেটওয়ার্কে যুক্ত করে আবার পরীক্ষা করুন।",
    refresh: "রিফ্রেশ",
};

fn ui(s: &State) -> &'static Ui {
    match s.settings.language.as_str() {
        "bn" => &UI_BN,
        _ => &UI_EN,
    }
}

// -------------------------------------------------------------------- state

#[derive(Clone, Copy, PartialEq)]
enum Tab {
    Browse,
    Transfers,
    Servers,
    Settings,
}

#[derive(Clone, PartialEq)]
enum ProbeState {
    Unknown,
    Checking,
    Up(u128),
    Down,
}

enum PosterState {
    Loading,
    Ready(rune::Image),
    Failed,
}

struct State {
    tab: Tab,
    sources: Vec<SourceItem>,
    probes: Vec<ProbeState>,
    probe_latest: Latest<Vec<(usize, Result<u128, String>)>>,
    // Library
    categories: Vec<Category>,
    cats_loading: bool,
    cats_latest: Latest<Vec<Category>>,
    feed_session: Option<FeedSession>,
    feed_titles: Vec<Title>,
    feed_loading: bool,
    feed_latest: Latest<(FeedSession, Vec<Title>)>,
    selected_category: Option<String>,
    lib_search: TextEdit,
    search_mode: bool,
    search_results: Vec<Title>,
    search_loading: bool,
    search_latest: Latest<Vec<Title>>,
    detail: Option<Title>,
    detail_loading: bool,
    detail_latest: Latest<Title>,
    pending_download: Option<String>,
    quality_choice: Option<(String, Vec<CatalogFile>)>,
    favorites: HashSet<String>,
    kind_filter: Option<String>,
    quality_filter: Option<String>,
    fav_only: bool,
    sort_mode: u8, // 0 newest (feed order) · 1 title · 2 year · 3 size desc · 4 size asc
    // Posters
    posters: HashMap<String, PosterState>,
    poster_queue: VecDeque<String>,
    poster_active: bool,
    poster_latest: Latest<(String, Result<Vec<u8>, String>)>,
    image_cache: Option<ImageCache>,
    // Servers / settings
    add_name: TextEdit,
    add_url: TextEdit,
    settings: Settings,
    cache_msg: Option<String>,
    update_msg: Option<String>,
    update_latest: Latest<Result<Option<String>, String>>,
    selected_transfer: Option<u64>,
    manager: Arc<Manager>,
}

// ------------------------------------------------------------------ actions

fn enabled_sources(s: &State) -> Vec<(String, String)> {
    s.sources
        .iter()
        .filter(|x| x.enabled)
        .map(|x| (x.name.clone(), x.root_url.clone()))
        .collect()
}

fn start_probes(s: &mut State) {
    let mut req: Vec<(usize, Source)> = Vec::new();
    for (i, src) in s.sources.iter().enumerate() {
        if src.enabled {
            if let Some(p) = s.probes.get_mut(i) {
                *p = ProbeState::Checking;
            }
            req.push((i, src.as_source()));
        } else if let Some(p) = s.probes.get_mut(i) {
            *p = ProbeState::Unknown;
        }
    }
    s.probe_latest.request(req, |req| {
        req.iter().map(|(i, src)| (*i, probe(src))).collect::<Vec<_>>()
    });
}

fn persist_sources(s: &mut State) {
    save_sources(&s.sources);
    while s.probes.len() < s.sources.len() {
        s.probes.push(ProbeState::Unknown);
    }
    s.probes.truncate(s.sources.len());
}

fn apply_settings(s: &mut State) {
    s.manager.apply_settings(s.settings.clone());
}

// ------------------------------------------------------------- library flow

fn start_library(s: &mut State) {
    if s.cats_loading || !s.categories.is_empty() {
        return;
    }
    s.cats_loading = true;
    s.cats_latest.request(enabled_sources(s), |srcs| catalog::discover_categories(&srcs));
}

fn drive_feed(s: &mut State) {
    if s.feed_loading || s.categories.is_empty() {
        return;
    }
    if s.feed_session.is_none() {
        s.feed_session = Some(FeedSession::new(&s.categories, s.selected_category.as_deref()));
    }
    if let Some(session) = s.feed_session.take() {
        s.feed_loading = true;
        s.feed_latest.request((session, 18usize), |(mut sess, n)| {
            let page = sess.next_page(n);
            (sess, page)
        });
    }
}

fn select_category(s: &mut State, name: Option<String>) {
    if s.feed_loading || s.cats_loading {
        return;
    }
    s.selected_category = name;
    s.feed_titles.clear();
    s.feed_session = None;
    drive_feed(s);
}

fn refresh_library(s: &mut State) {
    catalog::clear_listing_cache();
    s.categories.clear();
    s.feed_titles.clear();
    s.feed_session = None;
    s.cats_loading = false;
    start_library(s);
}

fn submit_search(s: &mut State) {
    let q = s.lib_search.text.trim().to_string();
    if q.is_empty() || s.search_loading {
        return;
    }
    s.search_mode = true;
    s.search_loading = true;
    s.search_results.clear();
    s.search_latest.request((enabled_sources(s), q), |(srcs, query)| catalog::search(&srcs, &query));
}

fn clear_search(s: &mut State) {
    s.search_mode = false;
    s.search_results.clear();
    s.lib_search = TextEdit::new("");
}

fn enqueue_posters(s: &mut State, titles: &[Title]) {
    for t in titles {
        if let Some(u) = &t.poster_url {
            if !s.posters.contains_key(u) && !s.poster_queue.iter().any(|q| q == u) {
                s.posters.insert(u.clone(), PosterState::Loading);
                s.poster_queue.push_back(u.clone());
            }
        }
    }
}

fn open_detail(s: &mut State, title: &Title) {
    s.detail_loading = true;
    let t = title.clone();
    s.detail_latest.request(t, |t| catalog::hydrate_and_load_files(t));
}

fn toggle_favorite(s: &mut State, folder_url: &str) {
    if !s.favorites.remove(folder_url) {
        s.favorites.insert(folder_url.to_string());
    }
    let urls: Vec<String> = s.favorites.iter().cloned().collect();
    save_favorites(&urls);
}

fn enqueue_file(s: &mut State, f: &CatalogFile) {
    s.manager.enqueue(f.file_url.clone(), f.file_name.clone());
}

/// The Android downloadTitle decision: series with several files queues
/// them all; a single file queues directly; a multi-file movie asks
/// which quality to take instead of silently grabbing the largest.
fn do_download(s: &mut State, title: &Title) {
    let files = &title.files;
    if files.is_empty() {
        return;
    }
    if title.kind == "series" && files.len() > 1 {
        for f in files {
            enqueue_file(s, f);
        }
        s.tab = Tab::Transfers;
    } else if files.len() == 1 {
        enqueue_file(s, &files[0]);
        s.tab = Tab::Transfers;
    } else {
        s.quality_choice = Some((title.folder_url.clone(), files.clone()));
        s.detail = Some(title.clone());
    }
}

fn request_download(s: &mut State, title: &Title) {
    if title.files_loaded {
        do_download(s, title);
    } else {
        s.pending_download = Some(title.folder_url.clone());
        open_detail(s, title);
    }
}

fn quality_rank(q: &Option<String>) -> i32 {
    match q.as_deref() {
        Some("2160p") => 5,
        Some("1080p") => 4,
        Some("720p") => 3,
        Some("480p") => 2,
        Some("360p") => 1,
        Some("CAM") => 0,
        _ => 3,
    }
}

/// pickFileFor: SMALLEST / BALANCED (≤1080p, largest) / BEST (largest).
fn pick_file(files: &[CatalogFile], pick: u8) -> Option<CatalogFile> {
    match pick {
        0 => files.iter().min_by_key(|f| f.size).cloned(),
        2 => files.iter().max_by_key(|f| f.size).cloned(),
        _ => {
            let capped: Vec<&CatalogFile> =
                files.iter().filter(|f| quality_rank(&f.quality) <= quality_rank(&Some("1080p".to_string()))).collect();
            capped
                .iter()
                .max_by_key(|f| f.size)
                .map(|f| (*f).clone())
                .or_else(|| files.iter().min_by_key(|f| f.size).cloned())
        }
    }
}

fn visible_titles(s: &State) -> Vec<Title> {
    let pool: &[Title] = if s.search_mode { &s.search_results } else { &s.feed_titles };
    let mut out: Vec<Title> = pool
        .iter()
        .filter(|t| {
            (s.kind_filter.is_none() || s.kind_filter.as_deref() == Some(t.kind.as_str()))
                && (s.quality_filter.is_none() || s.quality_filter == t.best_quality)
                && (!s.fav_only || s.favorites.contains(&t.folder_url))
        })
        .cloned()
        .collect();
    match s.sort_mode {
        1 => out.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase())),
        2 => out.sort_by(|a, b| {
            (b.year.unwrap_or(i32::MIN), a.title.to_lowercase())
                .cmp(&(a.year.unwrap_or(i32::MIN), b.title.to_lowercase()))
        }),
        3 => out.sort_by(|a, b| {
            (b.max_size, a.title.to_lowercase()).cmp(&(a.max_size, b.title.to_lowercase()))
        }),
        4 => out.sort_by(|a, b| {
            let key = |t: &Title| if t.max_size < 0 { i64::MAX } else { t.max_size };
            (key(a), a.title.to_lowercase()).cmp(&(key(b), b.title.to_lowercase()))
        }),
        _ => {}
    }
    out
}

fn pool_qualities(s: &State) -> Vec<String> {
    let pool: &[Title] = if s.search_mode { &s.search_results } else { &s.feed_titles };
    let mut qs: Vec<String> = pool.iter().filter_map(|t| t.best_quality.clone()).collect();
    qs.sort();
    qs.dedup();
    qs.sort_by(|a, b| quality_rank(&Some(b.clone())).cmp(&quality_rank(&Some(a.clone()))));
    qs
}

// --------------------------------------------------------------------- poll

fn poll(s: &mut State, _now: f64, _tx: &mut Tx) -> bool {
    let mut changed = false;
    if let Some(results) = s.probe_latest.take() {
        for (i, res) in results {
            if let Some(p) = s.probes.get_mut(i) {
                *p = match res {
                    Ok(ms) => ProbeState::Up(ms),
                    Err(_) => ProbeState::Down,
                };
            }
        }
        changed = true;
    }
    if let Some(cats) = s.cats_latest.take() {
        s.categories = cats;
        s.cats_loading = false;
        s.feed_session = None;
        s.feed_titles.clear();
        drive_feed(s);
        changed = true;
    }
    if let Some((session, page)) = s.feed_latest.take() {
        s.feed_session = Some(session);
        s.feed_loading = false;
        enqueue_posters(s, &page);
        s.feed_titles.extend(page);
        changed = true;
    }
    if let Some(results) = s.search_latest.take() {
        s.search_loading = false;
        enqueue_posters(s, &results);
        s.search_results = results;
        changed = true;
    }
    if let Some(title) = s.detail_latest.take() {
        s.detail_loading = false;
        enqueue_posters(s, std::slice::from_ref(&title));
        if s.pending_download.as_deref() == Some(title.folder_url.as_str()) {
            s.pending_download = None;
            do_download(s, &title);
        } else {
            s.detail = Some(title);
        }
        changed = true;
    }
    if let Some((url, res)) = s.poster_latest.take() {
        s.poster_active = false;
        match res {
            Ok(bytes) => {
                if let Some(cache) = &s.image_cache {
                    let img = cache.load(bytes);
                    s.posters.insert(url, PosterState::Ready(img));
                } else {
                    s.posters.insert(url, PosterState::Failed);
                }
            }
            Err(_) => {
                s.posters.insert(url, PosterState::Failed);
            }
        }
        changed = true;
    }
    if !s.poster_active {
        if let Some(url) = s.poster_queue.pop_front() {
            s.poster_active = true;
            s.poster_latest.request(url, |u| {
                let res = catalog::fetch_poster_bytes(&u);
                (u, res)
            });
        }
    }
    if let Some(res) = s.update_latest.take() {
        s.update_msg = Some(match res {
            Ok(Some(tag)) => format!("Update available: {tag} — download it from the GitHub releases page"),
            Ok(None) => "You're on the latest desktop version".to_string(),
            Err(e) => format!("Update check failed: {e}"),
        });
        changed = true;
    }
    changed
}

// --------------------------------------------------------------------- view

fn dark_of(s: &State, cx: &Cx<State>) -> bool {
    match s.settings.theme.as_str() {
        "dark" => true,
        "light" => false,
        _ => cx.system_dark(),
    }
}

fn nav_item<S: 'static>(
    p: &Palette,
    glyph: &str,
    label: &str,
    selected: bool,
    badge_count: usize,
    f: impl Fn(&mut S) + 'static,
) -> El {
    let (bg, glyph_c, label_c) = if selected {
        (p.accent_container, p.accent, p.ink)
    } else {
        (p.surface, p.ink3, p.ink3)
    };
    let mut item = row()
        .w_full()
        .h(40.0)
        .px(12.0)
        .gap(10.0)
        .radius(12.0)
        .fill(bg)
        .child(text(glyph).font(HEADLINE).color(glyph_c))
        .child(text(label).font(LABEL).color(label_c).grow(1.0));
    if badge_count > 0 {
        item = item.child(
            el()
                .radius(999.0)
                .fill(p.accent)
                .px(7.0)
                .py(1.0)
                .child(text(badge_count.to_string()).font(SMALL).color(p.on_accent)),
        );
    }
    item.on_press(f)
}

fn rail(s: &State, p: &Palette) -> El {
    let u = ui(s);
    let active = {
        let tasks = s.manager.tasks.lock().unwrap();
        tasks.iter().filter(|t| matches!(t.state, DlState::Running | DlState::Queued)).count()
    };
    col()
        .w(232.0)
        .h_full()
        .padding(16.0)
        .gap(6.0)
        .fill(p.surface)
        .child(
            col()
                .gap(1.0)
                .px(12.0)
                .py(6.0)
                .child(text("lanfetch").font(BRAND).color(p.ink))
                .child(text("LAN file downloader").font(SMALL).color(p.ink3)),
        )
        .child(el().h(10.0))
        .child(nav_item(p, "⌂", u.browse, s.tab == Tab::Browse, 0, |s: &mut State| s.tab = Tab::Browse))
        .child(nav_item(p, "↓", u.transfers, s.tab == Tab::Transfers, active, |s: &mut State| {
            s.tab = Tab::Transfers
        }))
        .child(nav_item(p, "▤", u.servers, s.tab == Tab::Servers, 0, |s: &mut State| s.tab = Tab::Servers))
        .child(el().grow(1.0))
        .child(nav_item(p, "⚙", u.settings, s.tab == Tab::Settings, 0, |s: &mut State| {
            s.tab = Tab::Settings
        }))
        .child(
            text(format!("v{} · Rust + Rune", env!("CARGO_PKG_VERSION")))
                .font(SMALL)
                .color(p.ink3)
                .px(12.0)
                .py(6.0),
        )
}

fn c_white() -> rune::Color {
    rune::color::hex("#ECEFEF").unwrap()
}

fn poster_el(s: &State, p: &Palette, t: &Title, w: f32, h: f32) -> El {
    // w <= 0 means "fill the available width" (grid cards).
    let base = if w <= 0.0 {
        el().w_full().h(h).radius(12.0).clip_rounded(12.0).fill(poster_tone(&t.title))
    } else {
        el().w(w).h(h).radius(12.0).clip_rounded(12.0).fill(poster_tone(&t.title))
    };
    if let Some(url) = &t.poster_url {
        if let Some(PosterState::Ready(img)) = s.posters.get(url) {
            return base.pixels_fit(img.pixels(), SurfaceFit::Fill);
        }
    }
    let _ = p;
    base.center().child(text(initials(&t.title)).font(TITLE).color(c_white()))
}

// ------------------------------------------------------------------ library

fn title_card(s: &State, p: &Palette, t: &Title) -> El {
    let u = ui(s);
    let fav = s.favorites.contains(&t.folder_url);
    let mut meta = String::new();
    if let Some(y) = t.year {
        meta += &format!("{y}");
    }
    if let Some(q) = &t.best_quality {
        if !meta.is_empty() {
            meta += " · ";
        }
        meta += q;
    }
    if t.max_size > 0 {
        if !meta.is_empty() {
            meta += " · ";
        }
        meta += &fmt_bytes(t.max_size as u64);
    }
    if meta.is_empty() {
        meta = t.source_name.clone();
    }
    let open = t.clone();
    let dl = t.clone();
    let fav_url = t.folder_url.clone();
    card(p)
        .grow(1.0)
        .padding(10.0)
        .child(
            col()
                .w_full()
                .gap(8.0)
                .child(poster_el(s, p, t, 0.0, 148.0))
                .child(text(t.title.clone()).font(LABEL).color(p.ink).w_full().wrap())
                .child(text(meta).font(SMALL).color(p.ink2).w_full().wrap())
                .child(
                    row()
                        .w_full()
                        .gap(6.0)
                        .child(badge(
                            if t.kind == "series" { u.series.to_string() } else { u.movies.to_string() },
                            p.accent_container,
                            p.on_accent_container,
                        ))
                        .child(el().grow(1.0))
                        .child(
                            el()
                                .px(8.0)
                                .py(4.0)
                                .radius(8.0)
                                .fill(if fav { p.accent_container } else { p.raised })
                                .child(text("★").font(LABEL).color(if fav { p.accent } else { p.ink3 }))
                                .on_press(move |s: &mut State| toggle_favorite(s, &fav_url)),
                        )
                        .child(
                            el()
                                .px(8.0)
                                .py(4.0)
                                .radius(8.0)
                                .fill(p.raised)
                                .child(text("↓").font(LABEL).color(p.ink2))
                                .on_press(move |s: &mut State| request_download(s, &dl)),
                        ),
                ),
        )
        .on_press(move |s: &mut State| open_detail(s, &open))
}

fn detail_view(s: &State, p: &Palette, t: &Title) -> El {
    let u = ui(s);
    let fav = s.favorites.contains(&t.folder_url);
    let fav_url = t.folder_url.clone();
    let dl_title = t.clone();
    let mut badges = row().w_full().gap(6.0);
    if let Some(y) = t.year {
        badges = badges.child(badge(y.to_string(), p.raised, p.ink2));
    }
    if let Some(q) = &t.best_quality {
        badges = badges.child(badge(q.clone(), p.accent_container, p.on_accent_container));
    }
    if let Some(tag) = &t.best_source_tag {
        badges = badges.child(badge(tag.clone(), p.raised, p.ink2));
    }
    if let Some(c) = &t.best_codec {
        badges = badges.child(badge(c.clone(), p.raised, p.ink2));
    }
    if let Some(l) = &t.language {
        badges = badges.child(badge(l.clone(), p.raised, p.ink2));
    }
    if t.kind == "series" {
        badges = badges.child(badge(
            format!("{} seasons", t.season_count),
            p.raised,
            p.ink2,
        ));
    }
    if t.dual_audio {
        badges = badges.child(badge("Dual Audio".to_string(), p.raised, p.ink2));
    }

    let mut body = col().w_full().grow(1.0).shrink().padding(16.0).gap(12.0);
    body = body.child(
        row()
            .w_full()
            .gap(16.0)
            .child(poster_el(s, p, t, 150.0, 210.0))
            .child(
                col()
                    .grow(1.0)
                    .gap(8.0)
                    .child(text(t.title.clone()).font(TITLE).color(p.ink).w_full().wrap())
                    .child(text(format!("{} · {}", t.category, t.source_name)).font(BODY).color(p.ink2))
                    .child(badges)
                    .child(
                        row()
                            .gap(8.0)
                            .child(btn_primary(u.download, p, move |s: &mut State| {
                                do_download(s, &dl_title)
                            }))
                            .child(
                                el()
                                    .px(10.0)
                                    .py(6.0)
                                    .radius(10.0)
                                    .fill(if fav { p.accent_container } else { p.raised })
                                    .child(text("★ Favorite").font(LABEL).color(if fav { p.accent } else { p.ink2 }))
                                    .on_press(move |s: &mut State| toggle_favorite(s, &fav_url)),
                            ),
                    ),
            ),
    );

    // Quality choice (Android B12): shown when a multi-file movie's
    // Download was pressed — never silently queue the largest file.
    if let Some((url, files)) = &s.quality_choice {
        if url == &t.folder_url {
            let mut choice = col().w_full().gap(8.0);
            choice = choice.child(section_label(p, "Choose quality"));
            for (pick, label) in [(0u8, "Smallest"), (1, "Balanced"), (2, "Best")] {
                if let Some(f) = pick_file(files, pick) {
                    let chosen = f.clone();
                    choice = choice.child(
                        card(p)
                            .w_full()
                            .padding(10.0)
                            .child(
                                row()
                                    .w_full()
                                    .gap(10.0)
                                    .child(text(label).font(LABEL).color(p.ink).w(90.0))
                                    .child(text(f.file_name.clone()).font(SMALL).color(p.ink2).grow(1.0).wrap())
                                    .child(badge(
                                        if f.size >= 0 { fmt_bytes(f.size as u64) } else { "—".into() },
                                        p.raised,
                                        p.ink2,
                                    )),
                            )
                            .on_press(move |s: &mut State| {
                                s.quality_choice = None;
                                enqueue_file(s, &chosen);
                                s.tab = Tab::Transfers;
                            }),
                    );
                }
            }
            choice = choice.child(btn_tonal(u.clear, p, |s: &mut State| s.quality_choice = None));
            body = body.child(choice);
        }
    }

    body = body.child(section_label(p, &format!("Files · {}", t.files.len())));
    let mut list = col().w_full().grow(1.0).shrink().gap(8.0).scroll(ScrollSpec { stick_bottom: false });
    for f in &t.files {
        let mut label = f.file_name.clone();
        if let (Some(sn), Some(ep)) = (f.season, f.episode) {
            label = format!("S{sn:02}E{ep:02} · {label}");
        }
        let file = f.clone();
        list = list.child(
            card(p).w_full().padding(10.0).child(
                row()
                    .w_full()
                    .gap(10.0)
                    .child(text(label).font(SMALL).color(p.ink).grow(1.0).wrap())
                    .child(badge(
                        f.quality.clone().unwrap_or_default(),
                        p.raised,
                        p.ink2,
                    ))
                    .child(badge(
                        if f.size >= 0 { fmt_bytes(f.size as u64) } else { "—".into() },
                        p.raised,
                        p.ink2,
                    ))
                    .child(btn_primary(u.download, p, move |s: &mut State| {
                        enqueue_file(s, &file);
                        s.tab = Tab::Transfers;
                    })),
            ),
        );
    }
    body = body.child(list);
    col().w_full().h_full().fill(p.canvas).child(body)
}

fn library_view(s: &State, p: &Palette) -> El {
    let u = ui(s);
    if s.detail_loading {
        let header = screen_header(p, text(u.library).font(TITLE).color(p.ink), None, None);
        return col()
            .w_full()
            .h_full()
            .fill(p.canvas)
            .child(header)
            .child(
                col()
                    .w_full()
                    .grow(1.0)
                    .center()
                    .child(text("Loading…").font(BODY).color(p.ink2)),
            );
    }
    if let Some(t) = &s.detail {
        let header = screen_header(
            p,
            text(t.title.clone()).font(TITLE).color(p.ink),
            Some(format!("{} · {}", t.category, t.source_name)),
            Some(row().gap(8.0).child(btn_tonal(u.back, p, |s: &mut State| {
                s.detail = None;
                s.quality_choice = None;
            }))),
        );
        return col().w_full().h_full().fill(p.canvas).child(header).child(detail_view(s, p, t));
    }

    let online = s.probes.iter().filter(|x| matches!(x, ProbeState::Up(_))).count();
    let pool_count = if s.search_mode { s.search_results.len() } else { s.feed_titles.len() };
    let subtitle = if s.cats_loading {
        "Discovering categories…".to_string()
    } else if s.search_mode {
        format!("{pool_count} results · {online} servers online")
    } else {
        format!("{pool_count} titles · {} categories · {online} online", s.categories.len())
    };
    let header = screen_header(
        p,
        text(u.library).font(TITLE).color(p.ink),
        Some(subtitle),
        Some(row().gap(8.0).child(btn_tonal(u.refresh, p, |s: &mut State| refresh_library(s)))),
    );

    let mut body = col().w_full().grow(1.0).shrink().padding(16.0).gap(10.0);

    // Search row (global, across all servers — Android's library search).
    let mut search_row = row()
        .w_full()
        .gap(8.0)
        .child(
            text_input(&s.lib_search, BODY)
                .label("Search all servers")
                .placeholder("Search movies & series…", p.ink3)
                .color(p.ink)
                .grow(1.0)
                .h(40.0)
                .padding(12.0)
                .radius(10.0)
                .fill(p.surface)
                .border(1.0, p.hairline)
                .on_edit(|s: &mut State| &mut s.lib_search),
        )
        .child(btn_primary(u.search, p, |s: &mut State| submit_search(s)));
    if s.search_mode {
        search_row = search_row.child(btn_tonal(u.clear, p, |s: &mut State| clear_search(s)));
    }
    body = body.child(search_row);

    // Category chips, chunked into rows (All + discovered categories).
    if !s.search_mode {
        let mut names: Vec<Option<String>> = vec![None];
        for c in &s.categories {
            names.push(Some(c.name.clone()));
        }
        for chunk in names.chunks(7) {
            let mut row_el = row().w_full().gap(8.0);
            for name in chunk {
                let selected = s.selected_category == *name;
                let label = name.clone().unwrap_or_else(|| u.all.to_string());
                let target = name.clone();
                row_el = row_el.child(chip(&label, selected, p, move |s: &mut State| {
                    select_category(s, target.clone())
                }));
            }
            body = body.child(row_el);
        }
    }

    // Filter row: kind, favorites, sort.
    let mut filters = row().w_full().gap(8.0);
    for (kind, label) in [(None, u.all), (Some("movie".to_string()), u.movies), (Some("series".to_string()), u.series)] {
        let selected = s.kind_filter == kind;
        filters = filters.child(chip(label, selected, p, move |s: &mut State| {
            s.kind_filter = kind.clone()
        }));
    }
    filters = filters.child(chip(u.favorites, s.fav_only, p, |s: &mut State| s.fav_only = !s.fav_only));
    filters = filters.child(el().grow(1.0));
    for (mode, label) in
        [(0u8, u.newest), (1, u.by_title), (2, u.by_year), (3, u.size_big), (4, u.size_small)]
    {
        let selected = s.sort_mode == mode;
        filters = filters.child(chip(label, selected, p, move |s: &mut State| s.sort_mode = mode));
    }
    body = body.child(filters);

    // Quality facet chips from the loaded pool.
    let qualities = pool_qualities(s);
    if qualities.len() > 1 {
        let mut qrow = row().w_full().gap(8.0);
        for q in qualities {
            let selected = s.quality_filter.as_deref() == Some(q.as_str());
            let target = q.clone();
            qrow = qrow.child(chip(&q, selected, p, move |s: &mut State| {
                s.quality_filter = if s.quality_filter.as_deref() == Some(target.as_str()) {
                    None
                } else {
                    Some(target.clone())
                };
            }));
        }
        body = body.child(qrow);
    }

    // Title grid, three cards per row.
    let visible = visible_titles(s);
    let mut grid = col().w_full().grow(1.0).shrink().gap(12.0).scroll(ScrollSpec { stick_bottom: false });
    if visible.is_empty() {
        let msg = if s.feed_loading || s.search_loading || s.cats_loading {
            "Loading…"
        } else if s.search_mode {
            "No results. Try another title, or check the Servers tab — the servers answer only from inside the ISP network."
        } else {
            "No titles yet. If the servers are unreachable, connect to the ISP network and press Refresh."
        };
        grid = grid.child(text(msg).font(BODY).color(p.ink2).w_full().wrap());
    }
    for chunk in visible.chunks(3) {
        let mut row_el = row().w_full().gap(12.0);
        for t in chunk {
            row_el = row_el.child(title_card(s, p, t));
        }
        for _ in chunk.len()..3 {
            row_el = row_el.child(el().grow(1.0));
        }
        grid = grid.child(row_el);
    }
    if !s.search_mode && s.feed_session.as_ref().map(|f| f.has_more).unwrap_or(false) {
        grid = grid.child(
            row()
                .w_full()
                .center()
                .child(btn_tonal(u.load_more, p, |s: &mut State| drive_feed(s))),
        );
    }
    body = body.child(grid);
    col().w_full().h_full().fill(p.canvas).child(header).child(body)
}

// ---------------------------------------------------------------- transfers

fn transfer_card(s: &State, p: &Palette, task: &manager::Task) -> El {
    let id = task.id;
    let segs: Vec<(u64, u64)> = task.live_segments().iter().map(|sg| (sg.done, sg.len())).collect();
    let (color, glyph) = match task.state {
        DlState::Running => (p.accent, "Ⅱ"),
        DlState::Queued => (p.accent, "·"),
        DlState::Paused => (p.paused, "▶"),
        DlState::Failed => (p.failed, "↻"),
        DlState::Completed => (p.done, "✓"),
    };
    let downloaded = task.downloaded();
    let pct = if task.total > 0 {
        format!(" · {}%", (task.fraction() * 100.0) as u32)
    } else {
        String::new()
    };
    let status = match task.state {
        DlState::Queued => {
            if task.ready {
                "Queued — waiting for a free slot".to_string()
            } else {
                "Queued — probing…".to_string()
            }
        }
        DlState::Running => format!(
            "{} of {} · {}{}",
            fmt_bytes(downloaded),
            if task.total > 0 { fmt_bytes(task.total) } else { "?".into() },
            fmt_speed(task.speed_bps),
            pct
        ),
        DlState::Paused => format!("Paused · {} of {}{}", fmt_bytes(downloaded), fmt_bytes(task.total), pct),
        DlState::Completed => format!("Finished · {}", fmt_bytes(task.total)),
        DlState::Failed => format!("Failed: {}", task.error.clone().unwrap_or_default()),
    };
    let state_badge = match task.state {
        DlState::Running => badge("Downloading".to_string(), p.accent_container, p.on_accent_container),
        DlState::Queued => badge("Queued".to_string(), p.raised, p.ink2),
        DlState::Paused => badge("Paused".to_string(), p.paused_container, p.paused),
        DlState::Failed => badge("Failed".to_string(), p.failed_container, p.failed),
        DlState::Completed => badge("Finished".to_string(), p.raised, p.done),
    };
    let ring = segmented_ring(
        p,
        color,
        glyph,
        &segs,
        task.state == DlState::Completed,
        task.state == DlState::Running && task.total == 0,
        move |s: &mut State| match task_state_of(s, id) {
            Some(DlState::Running) => s.manager.pause(id),
            Some(DlState::Paused) | Some(DlState::Failed) => s.manager.start(id),
            _ => {}
        },
    );
    let expanded = s.selected_transfer == Some(id);
    let mut card_el = card(p).w_full().padding(12.0).child(
        row()
            .w_full()
            .gap(12.0)
            .child(ring)
            .child(
                col()
                    .grow(1.0)
                    .gap(3.0)
                    .child(text(task.name.clone()).font(HEADLINE).color(p.ink).w_full().wrap())
                    .child(
                        row()
                            .w_full()
                            .gap(8.0)
                            .child(state_badge)
                            .child(text(status).font(SMALL).color(p.ink2).grow(1.0).wrap()),
                    ),
            )
            .child(text(if expanded { "⌄" } else { "›" }).font(HEADLINE).color(p.ink3)),
    )
    .on_press(move |s: &mut State| {
        s.selected_transfer = if s.selected_transfer == Some(id) { None } else { Some(id) };
    });
    if expanded {
        let mut detail = col().w_full().gap(6.0).pt(10.0);
        let live = task.live_segments();
        if live.len() > 1 {
            detail = detail.child(section_label(p, "Segments"));
            for (i, sg) in live.iter().enumerate() {
                let frac = if sg.len() == 0 || sg.len() == u64::MAX {
                    0.0
                } else {
                    sg.done as f64 / sg.len() as f64
                };
                detail = detail.child(
                    row()
                        .w_full()
                        .gap(8.0)
                        .child(text(format!("Lane {}", i + 1)).font(SMALL).color(p.ink2).w(52.0))
                        .child(progress_bar(frac, color, p.raised).grow(1.0))
                        .child(text(format!("{}%", (frac * 100.0) as u32)).font(SMALL).color(p.ink3).w(36.0)),
                );
            }
        }
        detail = detail
            .child(text(format!("Source: {}", task.url)).font(SMALL).color(p.ink3).w_full().wrap())
            .child(
                text(format!("Saves to: {}", manager::downloads_dir().join(&task.name).display()))
                    .font(SMALL)
                    .color(p.ink3)
                    .w_full()
                    .wrap(),
            );
        let mut actions = row().w_full().gap(8.0).pt(4.0);
        match task.state {
            DlState::Running => {
                actions = actions.child(btn_tonal("Pause", p, move |s: &mut State| s.manager.pause(id)));
            }
            DlState::Paused | DlState::Failed => {
                actions = actions.child(btn_primary("Resume", p, move |s: &mut State| s.manager.start(id)));
            }
            _ => {}
        }
        actions = actions.child(btn_tonal("Remove", p, move |s: &mut State| s.manager.remove(id, false)));
        actions = actions.child(btn_tonal("Remove + delete partial", p, move |s: &mut State| {
            s.manager.remove(id, true)
        }));
        detail = detail.child(actions);
        card_el = card_el.child(detail);
    }
    card_el
}

fn task_state_of(s: &State, id: u64) -> Option<DlState> {
    s.manager.tasks.lock().unwrap().iter().find(|t| t.id == id).map(|t| t.state)
}

fn transfers_view(s: &State, p: &Palette) -> El {
    let u = ui(s);
    let tasks = s.manager.tasks.lock().unwrap();
    let active = tasks.iter().filter(|t| t.state == DlState::Running).count();
    let queued = tasks.iter().filter(|t| t.state == DlState::Queued).count();
    let done = tasks.iter().filter(|t| t.state == DlState::Completed).count();
    let speed: f64 = tasks.iter().filter(|t| t.state == DlState::Running).map(|t| t.speed_bps).sum();
    let mut subtitle = format!("{active} active · {done} completed");
    if queued > 0 {
        subtitle += &format!(" · {queued} queued");
    }
    if active > 0 {
        subtitle += &format!(" · {}", fmt_speed(speed));
    }
    let mut actions = row().gap(8.0);
    if done > 0 {
        actions = actions.child(btn_tonal("Clear completed", p, |s: &mut State| s.manager.clear_completed()));
    }
    let header = screen_header(p, text(u.transfers).font(TITLE).color(p.ink), Some(subtitle), Some(actions));
    let mut body = col().w_full().grow(1.0).shrink().padding(16.0).gap(10.0);
    if tasks.is_empty() {
        drop(tasks);
        body = body.child(
            col()
                .w_full()
                .grow(1.0)
                .center()
                .gap(8.0)
                .child(
                    el()
                        .size(52.0, 52.0)
                        .radius(16.0)
                        .fill(p.raised)
                        .border(1.0, p.hairline)
                        .center()
                        .child(text("↓").font(TITLE).color(p.ink2)),
                )
                .child(text("No transfers").font(HEADLINE).color(p.ink))
                .child(
                    text("Movies and episodes you download appear here with live multi-segment progress and speed.")
                        .font(BODY)
                        .color(p.ink2)
                        .w(420.0)
                        .wrap(),
                ),
        );
    } else {
        let mut list = col().w_full().grow(1.0).shrink().gap(8.0).scroll(ScrollSpec { stick_bottom: false });
        for task in tasks.iter() {
            list = list.child(transfer_card(s, p, task));
        }
        drop(tasks);
        body = body.child(list);
    }
    col().w_full().h_full().fill(p.canvas).child(header).child(body)
}

// ------------------------------------------------------------------ servers

fn servers_view(s: &State, p: &Palette) -> El {
    let u = ui(s);
    let online = s.probes.iter().filter(|x| matches!(x, ProbeState::Up(_))).count();
    let enabled = s.sources.iter().filter(|x| x.enabled).count();
    let header = screen_header(
        p,
        text(u.servers).font(TITLE).color(p.ink),
        Some(format!("{online} online • {enabled} of {} enabled", s.sources.len())),
        Some(row().gap(8.0).child(btn_tonal("Re-check", p, |s: &mut State| start_probes(s)))),
    );
    let mut list = col().w_full().grow(1.0).shrink().gap(8.0).scroll(ScrollSpec { stick_bottom: false });
    for (i, src) in s.sources.iter().enumerate() {
        let (dot, note) = if !src.enabled {
            (p.ink3, "Disabled".to_string())
        } else {
            match s.probes.get(i) {
                Some(ProbeState::Up(ms)) => (p.done, format!("{ms} ms")),
                Some(ProbeState::Down) => (p.failed, "Unreachable".to_string()),
                Some(ProbeState::Checking) => (p.ink3, "Checking…".to_string()),
                _ => (p.ink3, String::new()),
            }
        };
        let mut actions = row().gap(8.0);
        if src.enabled {
            actions = actions.child(btn_tonal("Disable", p, move |s: &mut State| {
                if let Some(x) = s.sources.get_mut(i) {
                    x.enabled = false;
                }
                persist_sources(s);
                start_probes(s);
            }));
        } else {
            actions = actions.child(btn_primary("Enable", p, move |s: &mut State| {
                if let Some(x) = s.sources.get_mut(i) {
                    x.enabled = true;
                }
                persist_sources(s);
                start_probes(s);
            }));
        }
        if src.custom {
            actions = actions.child(btn_tonal("Remove", p, move |s: &mut State| {
                if i < s.sources.len() && s.sources[i].custom {
                    s.sources.remove(i);
                }
                persist_sources(s);
                start_probes(s);
            }));
        }
        list = list.child(
            card(p).w_full().padding(14.0).child(
                row()
                    .w_full()
                    .gap(12.0)
                    .child(el().size(9.0, 9.0).radius(5.0).fill(dot))
                    .child(
                        col()
                            .grow(1.0)
                            .gap(2.0)
                            .child(text(src.name.clone()).font(HEADLINE).color(p.ink))
                            .child(text(src.root_url.clone()).font(SMALL).color(p.ink2)),
                    )
                    .child(badge(note, p.raised, p.ink2))
                    .child(actions),
            ),
        );
    }
    let add_card = card(p).w_full().padding(14.0).child(
        col()
            .w_full()
            .gap(10.0)
            .child(section_label(p, "Add media server"))
            .child(
                row()
                    .w_full()
                    .gap(8.0)
                    .child(
                        text_input(&s.add_name, BODY)
                            .label("Server name")
                            .placeholder("Name", p.ink3)
                            .color(p.ink)
                            .grow(1.0)
                            .h(40.0)
                            .padding(12.0)
                            .radius(10.0)
                            .fill(p.canvas)
                            .border(1.0, p.hairline)
                            .on_edit(|s: &mut State| &mut s.add_name),
                    )
                    .child(
                        text_input(&s.add_url, BODY)
                            .label("Server URL")
                            .placeholder("http://192.168.1.10/movies/", p.ink3)
                            .color(p.ink)
                            .grow(2.0)
                            .h(40.0)
                            .padding(12.0)
                            .radius(10.0)
                            .fill(p.canvas)
                            .border(1.0, p.hairline)
                            .on_edit(|s: &mut State| &mut s.add_url),
                    )
                    .child(btn_primary("Add", p, |s: &mut State| {
                        let name = s.add_name.text.trim().to_string();
                        let mut url = s.add_url.text.trim().to_string();
                        if name.is_empty() || url.is_empty() {
                            return;
                        }
                        if !url.ends_with('/') {
                            url.push('/');
                        }
                        s.sources.push(SourceItem { name, root_url: url, enabled: true, custom: true });
                        s.add_name = TextEdit::new("");
                        s.add_url = TextEdit::new("");
                        persist_sources(s);
                        start_probes(s);
                    })),
            ),
    );
    let body = col()
        .w_full()
        .grow(1.0)
        .shrink()
        .padding(16.0)
        .gap(12.0)
        .child(list)
        .child(add_card)
        .child(
            text("The DHAKA-FLIX servers live on a private ISP network — they answer only from inside it. Custom servers can be any HTTP directory listing (Apache/nginx autoindex or h5ai).")
                .font(SMALL)
                .color(p.ink3)
                .w_full()
                .wrap(),
        );
    col().w_full().h_full().fill(p.canvas).child(header).child(body)
}

// ----------------------------------------------------------------- settings

fn settings_view(s: &State, p: &Palette) -> El {
    let u = ui(s);
    let header = screen_header(
        p,
        text(u.settings).font(TITLE).color(p.ink),
        Some("Transfers, appearance & storage".to_string()),
        None,
    );
    let cur_conn = s.settings.connections;
    let cur_max = s.settings.max_concurrent;
    let cur_theme = s.settings.theme.clone();
    let cur_lang = s.settings.language.clone();

    let mut conn_chips = row().gap(8.0);
    for n in [4u32, 8, 16] {
        conn_chips = conn_chips.child(chip(&format!("{n}"), cur_conn == n, p, move |s: &mut State| {
            s.settings.connections = n;
            apply_settings(s);
        }));
    }
    let mut max_chips = row().gap(8.0);
    for n in [1u32, 2, 3, 5] {
        max_chips = max_chips.child(chip(&format!("{n}"), cur_max == n, p, move |s: &mut State| {
            s.settings.max_concurrent = n;
            apply_settings(s);
        }));
    }
    let mut theme_chips = row().gap(8.0);
    for (key, label) in [("system", "System"), ("light", "Light"), ("dark", "Dark")] {
        theme_chips = theme_chips.child(chip(label, cur_theme == key, p, move |s: &mut State| {
            s.settings.theme = key.to_string();
            apply_settings(s);
        }));
    }
    let mut lang_chips = row().gap(8.0);
    for (key, label) in [("system", "System"), ("en", "English"), ("bn", "বাংলা")] {
        lang_chips = lang_chips.child(chip(label, cur_lang == key, p, move |s: &mut State| {
            s.settings.language = key.to_string();
            apply_settings(s);
        }));
    }

    let body = col()
        .w_full()
        .grow(1.0)
        .shrink()
        .padding(16.0)
        .gap(10.0)
        .scroll(ScrollSpec { stick_bottom: false })
        .child(section_label(p, "Transfer engine"))
        .child(
            card(p).w_full().padding(14.0).child(
                col()
                    .w_full()
                    .gap(12.0)
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(
                                col()
                                    .grow(1.0)
                                    .gap(2.0)
                                    .child(text("Connections per download").font(LABEL).color(p.ink))
                                    .child(text("Parallel segments (lanes) for each file").font(SMALL).color(p.ink2)),
                            )
                            .child(conn_chips),
                    )
                    .child(el().w_full().h(1.0).fill(p.hairline))
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(
                                col()
                                    .grow(1.0)
                                    .gap(2.0)
                                    .child(text("Simultaneous downloads").font(LABEL).color(p.ink))
                                    .child(text("Extra downloads wait in the queue").font(SMALL).color(p.ink2)),
                            )
                            .child(max_chips),
                    ),
            ),
        )
        .child(section_label(p, "Appearance"))
        .child(
            card(p).w_full().padding(14.0).child(
                col()
                    .w_full()
                    .gap(12.0)
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(
                                col()
                                    .grow(1.0)
                                    .gap(2.0)
                                    .child(text("Theme").font(LABEL).color(p.ink))
                                    .child(text("The Lanes palette, same as the Android app").font(SMALL).color(p.ink2)),
                            )
                            .child(theme_chips),
                    )
                    .child(el().w_full().h(1.0).fill(p.hairline))
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(
                                col()
                                    .grow(1.0)
                                    .gap(2.0)
                                    .child(text("Language").font(LABEL).color(p.ink))
                                    .child(text("Navigation and headers; deeper text stays English for now").font(SMALL).color(p.ink2)),
                            )
                            .child(lang_chips),
                    ),
            ),
        )
        .child(section_label(p, "Storage & cache"))
        .child(
            card(p).w_full().padding(14.0).child(
                col()
                    .w_full()
                    .gap(8.0)
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(text("Downloads folder").font(LABEL).color(p.ink).grow(1.0))
                            .child(text(manager::downloads_dir().display().to_string()).font(SMALL).color(p.ink2)),
                    )
                    .child(el().w_full().h(1.0).fill(p.hairline))
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(text("App data").font(LABEL).color(p.ink).grow(1.0))
                            .child(text(manager::data_dir().display().to_string()).font(SMALL).color(p.ink2)),
                    )
                    .child(el().w_full().h(1.0).fill(p.hairline))
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(
                                col()
                                    .grow(1.0)
                                    .gap(2.0)
                                    .child(text("Listing cache").font(LABEL).color(p.ink))
                                    .child(
                                        text(s.cache_msg.clone().unwrap_or_else(|| "Server listings are cached for 10 minutes".to_string()))
                                            .font(SMALL)
                                            .color(p.ink2),
                                    ),
                            )
                            .child(btn_tonal(u.clear, p, |s: &mut State| {
                                let n = catalog::clear_listing_cache();
                                s.cache_msg = Some(format!("Cleared {n} cached listings"));
                            })),
                    ),
            ),
        )
        .child(section_label(p, "About"))
        .child(
            card(p).w_full().padding(14.0).child(
                col()
                    .w_full()
                    .gap(4.0)
                    .child(text(format!("LanFetch Desktop v{}", env!("CARGO_PKG_VERSION"))).font(LABEL).color(p.ink))
                    .child(
                        text("Native Rust + Rune port of the Android app — same servers, same segmented engine, same Lanes design.")
                            .font(SMALL)
                            .color(p.ink2)
                            .w_full()
                            .wrap(),
                    )
                    .child(el().w_full().h(1.0).fill(p.hairline))
                    .child(
                        row()
                            .w_full()
                            .gap(12.0)
                            .child(
                                text(s.update_msg.clone().unwrap_or_else(|| "Updates ship as GitHub releases (desktop-rs tags)".to_string()))
                                    .font(SMALL)
                                    .color(p.ink2)
                                    .grow(1.0)
                                    .wrap(),
                            )
                            .child(btn_tonal("Check for updates", p, |s: &mut State| {
                                s.update_msg = Some("Checking…".to_string());
                                s.update_latest.request((), |_: ()| catalog::check_desktop_update());
                            })),
                    ),
            ),
        );
    col().w_full().h_full().fill(p.canvas).child(header).child(body)
}

// --------------------------------------------------------------- onboarding

fn onboarding_view(s: &State, p: &Palette) -> El {
    let u = ui(s);
    let mut list = col().w(520.0).gap(8.0);
    for (i, src) in s.sources.iter().enumerate() {
        if !src.enabled {
            continue;
        }
        let (dot, note) = match s.probes.get(i) {
            Some(ProbeState::Up(ms)) => (p.done, format!("{ms} ms")),
            Some(ProbeState::Down) => (p.failed, "Unreachable".to_string()),
            Some(ProbeState::Checking) => (p.ink3, "Checking…".to_string()),
            _ => (p.ink3, String::new()),
        };
        list = list.child(
            card(p).w_full().padding(12.0).child(
                row()
                    .w_full()
                    .gap(12.0)
                    .child(el().size(9.0, 9.0).radius(5.0).fill(dot))
                    .child(
                        col()
                            .grow(1.0)
                            .gap(2.0)
                            .child(text(src.name.clone()).font(HEADLINE).color(p.ink))
                            .child(text(src.root_url.clone()).font(SMALL).color(p.ink2)),
                    )
                    .child(badge(note, p.raised, p.ink2)),
            ),
        );
    }
    col()
        .w_full()
        .h_full()
        .fill(p.canvas)
        .center()
        .child(
            col()
                .w(560.0)
                .gap(14.0)
                .child(text("lanfetch").font(BRAND).color(p.ink))
                .child(text(u.check_title).font(TITLE).color(p.ink).w_full().wrap())
                .child(text(u.check_body).font(BODY).color(p.ink2).w_full().wrap())
                .child(list)
                .child(
                    row()
                        .gap(8.0)
                        .child(btn_tonal(u.refresh, p, |s: &mut State| start_probes(s)))
                        .child(btn_primary(u.start, p, |s: &mut State| {
                            s.settings.onboarding_done = true;
                            apply_settings(s);
                            start_library(s);
                        })),
                ),
        )
}

// ---------------------------------------------------------------------- root

fn view(s: &State, cx: &mut Cx<State>) -> El {
    let p = palette(dark_of(s, cx));
    if !s.settings.onboarding_done {
        return onboarding_view(s, &p);
    }
    let screen = match s.tab {
        Tab::Browse => library_view(s, &p),
        Tab::Transfers => transfers_view(s, &p),
        Tab::Servers => servers_view(s, &p),
        Tab::Settings => settings_view(s, &p),
    };
    row()
        .w_full()
        .h_full()
        .fill(p.canvas)
        .child(rail(s, &p))
        .child(el().w(1.0).h_full().fill(p.hairline))
        .child(screen.grow(1.0))
}

fn main() {
    // Register the Android app's font family (Anek Bangla, OFL) so the
    // desktop UI renders in the same typeface; styles fall back to the
    // system sans if a face fails to load.
    static ANEK_REGULAR: &[u8] = include_bytes!("../assets/fonts/anek_bangla_regular.ttf");
    static ANEK_MEDIUM: &[u8] = include_bytes!("../assets/fonts/anek_bangla_medium.ttf");
    static ANEK_SEMIBOLD: &[u8] = include_bytes!("../assets/fonts/anek_bangla_semibold.ttf");
    static ANEK_BOLD: &[u8] = include_bytes!("../assets/fonts/anek_bangla_bold.ttf");
    let _ = rune::text::add_font(ANEK_REGULAR);
    let _ = rune::text::add_font(ANEK_MEDIUM);
    let _ = rune::text::add_font(ANEK_SEMIBOLD);
    let _ = rune::text::add_font(ANEK_BOLD);

    let mut spec = AppSpec::new("LanFetch", (1180.0, 760.0), || unreachable!(), view);
    spec.setup = Some(|s: &mut State, waker| {
        s.manager.set_waker(waker.clone());
        s.probe_latest.set_waker(waker.clone());
        s.cats_latest.set_waker(waker.clone());
        s.feed_latest.set_waker(waker.clone());
        s.search_latest.set_waker(waker.clone());
        s.detail_latest.set_waker(waker.clone());
        s.poster_latest.set_waker(waker.clone());
        s.update_latest.set_waker(waker.clone());
        s.image_cache = Some(ImageCache::new(waker));
    });
    spec.poll = Some(poll);
    let app = App::new(spec);
    let handle = app.handle();
    app.init(|| {
        let sources = load_sources();
        let probes = vec![ProbeState::Unknown; sources.len()];
        let settings = load_settings();
        State {
            tab: Tab::Browse,
            sources,
            probes,
            probe_latest: Latest::default(),
            categories: Vec::new(),
            cats_loading: false,
            cats_latest: Latest::default(),
            feed_session: None,
            feed_titles: Vec::new(),
            feed_loading: false,
            feed_latest: Latest::default(),
            selected_category: None,
            lib_search: TextEdit::new(""),
            search_mode: false,
            search_results: Vec::new(),
            search_loading: false,
            search_latest: Latest::default(),
            detail: None,
            detail_loading: false,
            detail_latest: Latest::default(),
            pending_download: None,
            quality_choice: None,
            favorites: load_favorites().into_iter().collect(),
            kind_filter: None,
            quality_filter: None,
            fav_only: false,
            sort_mode: 0,
            posters: HashMap::new(),
            poster_queue: VecDeque::new(),
            poster_active: false,
            poster_latest: Latest::default(),
            image_cache: None,
            add_name: TextEdit::new(""),
            add_url: TextEdit::new(""),
            settings,
            cache_msg: None,
            update_msg: None,
            update_latest: Latest::default(),
            selected_transfer: None,
            manager: Manager::load(),
        }
    })
    .on_ready(move |_| {
        handle.update(|s, _tx| {
            start_probes(s);
            if s.settings.onboarding_done {
                start_library(s);
            }
        });
    })
    .run();
}
