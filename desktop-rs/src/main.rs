//! LanFetch Desktop (Rust + Rune) — the Android app's "Lanes" design and
//! feature set on Linux: Browse / Transfers / Servers / Settings tabs,
//! the segmented transfer ring, live per-lane progress, custom servers,
//! and the same transfer-engine settings. Async work follows Rune's
//! native pattern: `Latest` worker-pool requests for probes/listings
//! (drained in `poll`), and the download manager's own threads + Waker
//! for continuous progress.

#![forbid(unsafe_code)]

mod core;
mod lanes;
mod manager;
mod store;

use core::*;
use lanes::*;
use manager::{DlState, Manager};
use rune::{col, el, row, text, text_input, App, AppSpec, Cx, El, Latest, ScrollSpec, TextEdit, Tx};
use std::sync::Arc;
use store::{load_settings, load_sources, save_sources, Settings, SourceItem};

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

#[derive(Clone)]
enum Listing {
    Idle,
    Loading,
    Ready(Vec<DirEntry>),
    Error(String),
}

struct State {
    tab: Tab,
    sources: Vec<SourceItem>,
    probes: Vec<ProbeState>,
    /// Breadcrumb stack of (label, url); the last entry is on screen.
    stack: Vec<(String, String)>,
    listing: Listing,
    search: TextEdit,
    add_name: TextEdit,
    add_url: TextEdit,
    settings: Settings,
    selected_transfer: Option<u64>,
    probe_latest: Latest<Vec<(usize, Result<u128, String>)>>,
    listing_latest: Latest<(String, Result<Vec<DirEntry>, String>)>,
    manager: Arc<Manager>,
}

// ------------------------------------------------------------------ actions

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

fn open_url(s: &mut State, label: String, url: String) {
    s.stack.push((label, url.clone()));
    s.listing = Listing::Loading;
    s.listing_latest.request(url, |u| {
        let res = fetch_listing(&u);
        (u, res)
    });
}

fn reload_current(s: &mut State) {
    if let Some((_, url)) = s.stack.last().cloned() {
        s.listing = Listing::Loading;
        s.listing_latest.request(url, |u| {
            let res = fetch_listing(&u);
            (u, res)
        });
    }
}

fn go_back(s: &mut State) {
    s.stack.pop();
    match s.stack.last() {
        Some(_) => reload_current(s),
        None => s.listing = Listing::Idle,
    }
}

/// Jump to breadcrumb depth (0 = first stack entry).
fn jump_to(s: &mut State, depth: usize) {
    if depth + 1 < s.stack.len() {
        s.stack.truncate(depth + 1);
        reload_current(s);
    }
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
    if let Some((url, res)) = s.listing_latest.take() {
        if s.stack.last().map(|(_, u)| u == &url).unwrap_or(false) {
            s.listing = match res {
                Ok(entries) => Listing::Ready(entries),
                Err(e) => Listing::Error(e),
            };
            changed = true;
        }
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
        .child(nav_item(p, "⌂", "Browse", s.tab == Tab::Browse, 0, |s: &mut State| s.tab = Tab::Browse))
        .child(nav_item(p, "↓", "Transfers", s.tab == Tab::Transfers, active, |s: &mut State| {
            s.tab = Tab::Transfers
        }))
        .child(nav_item(p, "▤", "Servers", s.tab == Tab::Servers, 0, |s: &mut State| s.tab = Tab::Servers))
        .child(el().grow(1.0))
        .child(nav_item(p, "⚙", "Settings", s.tab == Tab::Settings, 0, |s: &mut State| {
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

// ------------------------------------------------------------------- browse

fn browse_view(s: &State, p: &Palette) -> El {
    let online = s.probes.iter().filter(|x| matches!(x, ProbeState::Up(_))).count();
    let enabled = s.sources.iter().filter(|x| x.enabled).count();
    let (title, subtitle) = match s.stack.last() {
        Some((label, _)) => (label.clone(), Some(format!("{online} servers online"))),
        None => ("Browse".to_string(), Some(format!("{online} online · {enabled} servers enabled"))),
    };
    let mut actions = row().gap(8.0);
    if !s.stack.is_empty() {
        actions = actions.child(btn_tonal("Back", p, |s: &mut State| go_back(s)));
        actions = actions.child(btn_tonal("Refresh", p, |s: &mut State| reload_current(s)));
    }
    let header = screen_header(
        p,
        text(title).font(TITLE).color(p.ink),
        subtitle,
        Some(actions),
    );

    let mut body = col().w_full().grow(1.0).shrink().padding(16.0).gap(12.0);

    if s.stack.is_empty() {
        body = body.child(section_label(p, "Pick a server"));
        let mut list = col().w_full().grow(1.0).shrink().gap(8.0).scroll(ScrollSpec { stick_bottom: false });
        let mut any = false;
        for (i, src) in s.sources.iter().enumerate() {
            if !src.enabled {
                continue;
            }
            any = true;
            let (dot, note) = match s.probes.get(i) {
                Some(ProbeState::Up(ms)) => (p.done, format!("{ms} ms")),
                Some(ProbeState::Down) => (p.failed, "unreachable".to_string()),
                Some(ProbeState::Checking) => (p.ink3, "checking…".to_string()),
                _ => (p.ink3, String::new()),
            };
            let (label, url) = (src.name.clone(), src.root_url.clone());
            list = list.child(
                card(p)
                    .w_full()
                    .padding(14.0)
                    .child(
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
                            .child(badge(p, note, p.raised, p.ink2)),
                    )
                    .on_press(move |s: &mut State| open_url(s, label.clone(), url.clone())),
            );
        }
        if !any {
            list = list.child(
                text("All servers are disabled — enable one in the Servers tab.")
                    .font(BODY)
                    .color(p.ink2),
            );
        }
        body = body.child(list);
        body = body.child(
            text("The DHAKA-FLIX servers live on a private ISP network — they answer only from inside it.")
                .font(SMALL)
                .color(p.ink3)
                .w_full()
                .wrap(),
        );
        return col().w_full().h_full().fill(p.canvas).child(header).child(body);
    }

    // breadcrumb
    let mut crumbs = row().w_full().gap(6.0);
    for (i, (label, _)) in s.stack.iter().enumerate() {
        if i > 0 {
            crumbs = crumbs.child(text("/").font(SMALL).color(p.ink3));
        }
        let last = i + 1 == s.stack.len();
        crumbs = crumbs.child(
            el()
                .px(8.0)
                .py(4.0)
                .radius(8.0)
                .fill(if last { p.accent_container } else { p.raised })
                .child(
                    text(label.clone())
                        .font(LABEL)
                        .color(if last { p.on_accent_container } else { p.ink2 }),
                )
                .on_press(move |s: &mut State| jump_to(s, i)),
        );
    }
    body = body.child(crumbs);

    // search (filters the current folder, like Android's library search)
    body = body.child(
        text_input(&s.search, BODY)
            .label("Filter this folder")
            .placeholder("Filter this folder…", p.ink3)
            .color(p.ink)
            .w_full()
            .h(40.0)
            .padding(12.0)
            .radius(10.0)
            .fill(p.surface)
            .border(1.0, p.hairline)
            .on_edit(|s: &mut State| &mut s.search),
    );

    let query = s.search.text.trim().to_lowercase();
    let mut list = col().w_full().grow(1.0).shrink().gap(8.0).scroll(ScrollSpec { stick_bottom: false });
    match &s.listing {
        Listing::Idle | Listing::Loading => {
            list = list.child(text("Loading…").font(BODY).color(p.ink2));
        }
        Listing::Error(e) => {
            list = list.child(
                col()
                    .gap(6.0)
                    .child(text("Servers unreachable").font(HEADLINE).color(p.ink))
                    .child(
                        text(format!("Could not load this folder: {e}"))
                            .font(BODY)
                            .color(p.ink2)
                            .w_full()
                            .wrap(),
                    ),
            );
        }
        Listing::Ready(entries) => {
            let shown: Vec<&DirEntry> = entries
                .iter()
                .filter(|e| query.is_empty() || e.name.to_lowercase().contains(&query))
                .collect();
            if shown.is_empty() {
                list = list.child(text("Nothing here matches.").font(BODY).color(p.ink2));
            }
            for entry in shown {
                let name = entry.name.clone();
                let tile = el()
                    .size(40.0, 40.0)
                    .radius(10.0)
                    .fill(poster_tone(&name))
                    .center()
                    .child(text(initials(&name)).font(HEADLINE).color(c_white()));
                if entry.is_dir {
                    let (url, label) = (entry.url.clone(), name.clone());
                    list = list.child(
                        card(p)
                            .w_full()
                            .padding(10.0)
                            .child(
                                row()
                                    .w_full()
                                    .gap(12.0)
                                    .child(tile)
                                    .child(text(name).font(LABEL).color(p.ink).grow(1.0))
                                    .child(badge(p, "Folder".to_string(), p.raised, p.ink2))
                                    .child(text("›").font(HEADLINE).color(p.ink3)),
                            )
                            .on_press(move |s: &mut State| open_url(s, label.clone(), url.clone())),
                    );
                } else {
                    let size = entry.size.map(fmt_bytes).unwrap_or_else(|| "—".to_string());
                    let (url, fname) = (entry.url.clone(), name.clone());
                    list = list.child(
                        card(p).w_full().padding(10.0).child(
                            row()
                                .w_full()
                                .gap(12.0)
                                .child(tile)
                                .child(text(name).font(BODY).color(p.ink).grow(1.0))
                                .child(badge(p, size, p.raised, p.ink2))
                                .child(btn_primary("Download", p, move |s: &mut State| {
                                    s.manager.enqueue(url.clone(), fname.clone());
                                    s.tab = Tab::Transfers;
                                })),
                        ),
                    );
                }
            }
        }
    }
    body = body.child(list);
    col().w_full().h_full().fill(p.canvas).child(header).child(body)
}

fn c_white() -> rune::Color {
    rune::color::hex("#ECEFEF").unwrap()
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
                    .child(text(status).font(SMALL).color(p.ink2).w_full().wrap()),
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
    let header = screen_header(p, text("Transfers").font(TITLE).color(p.ink), Some(subtitle), Some(actions));
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
    let online = s.probes.iter().filter(|x| matches!(x, ProbeState::Up(_))).count();
    let enabled = s.sources.iter().filter(|x| x.enabled).count();
    let header = screen_header(
        p,
        text("Servers").font(TITLE).color(p.ink),
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
                    .child(badge(p, note, p.raised, p.ink2))
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
    let header = screen_header(
        p,
        text("Settings").font(TITLE).color(p.ink),
        Some("Transfers, appearance & storage".to_string()),
        None,
    );
    let cur_conn = s.settings.connections;
    let cur_max = s.settings.max_concurrent;
    let cur_theme = s.settings.theme.clone();

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
                    ),
            ),
        );
    col().w_full().h_full().fill(p.canvas).child(header).child(body)
}

// ---------------------------------------------------------------------- root

fn view(s: &State, cx: &mut Cx<State>) -> El {
    let p = palette(dark_of(s, cx));
    let screen = match s.tab {
        Tab::Browse => browse_view(s, &p),
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
        s.listing_latest.set_waker(waker);
    });
    spec.poll = Some(poll);
    let app = App::new(spec);
    let handle = app.handle();
    app.init(|| {
        let sources = load_sources();
        let probes = vec![ProbeState::Unknown; sources.len()];
        State {
            tab: Tab::Browse,
            sources,
            probes,
            stack: Vec::new(),
            listing: Listing::Idle,
            search: TextEdit::new(""),
            add_name: TextEdit::new(""),
            add_url: TextEdit::new(""),
            settings: load_settings(),
            selected_transfer: None,
            probe_latest: Latest::default(),
            listing_latest: Latest::default(),
            manager: Manager::load(),
        }
    })
    .on_ready(move |_| {
        handle.update(|s, _tx| {
            start_probes(s);
        });
    })
    .run();
}
