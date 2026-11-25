//! LanFetch Desktop (Rust + Rune): browse the seeded LAN directory
//! servers and download files with the segmented, resumable engine.
//! Async work follows Rune's native pattern: `Latest` worker-pool
//! requests for probes/listings (drained in `poll`), and the download
//! manager's own threads + Waker for continuous progress.

#![forbid(unsafe_code)]

mod core;
mod manager;

use core::*;
use manager::{DlState, Manager};
use rune::tok::{self, type_};
use rune::{App, AppSpec, Cx, El, Latest, ScrollSpec, Tx, col, el, row, text};
use std::sync::Arc;

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
    servers: Vec<Source>,
    probes: Vec<ProbeState>,
    /// Breadcrumb stack of (label, url); the last entry is on screen.
    stack: Vec<(String, String)>,
    listing: Listing,
    probe_latest: Latest<Vec<(usize, Result<u128, String>)>>,
    listing_latest: Latest<(String, Result<Vec<DirEntry>, String>)>,
    manager: Arc<Manager>,
}

fn start_probes(s: &mut State) {
    s.probes = vec![ProbeState::Checking; s.servers.len()];
    let servers = s.servers.clone();
    s.probe_latest.request(servers, |servers| {
        servers
            .iter()
            .enumerate()
            .map(|(i, src)| (i, probe(src)))
            .collect::<Vec<_>>()
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

fn go_back(s: &mut State) {
    s.stack.pop();
    match s.stack.last() {
        Some((_, url)) => {
            let url = url.clone();
            s.listing = Listing::Loading;
            s.listing_latest.request(url, |u| {
                let res = fetch_listing(&u);
                (u, res)
            });
        }
        None => s.listing = Listing::Idle,
    }
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

// ------------------------------------------------------------------ view

fn btn<S: 'static>(label: &str, t: &tok::Theme, f: impl Fn(&mut S) + 'static) -> El {
    el()
        .h(30.0)
        .px(12.0)
        .center()
        .radius(8.0)
        .fill(t.control_off)
        .child(text(label).font(type_::LABEL).color(t.text))
        .on_press(f)
}

fn progress_bar(frac: f64, t: &tok::Theme, dark: bool) -> El {
    let f = frac.clamp(0.0, 1.0) as f32;
    row()
        .w_full()
        .h(6.0)
        .radius(3.0)
        .fill(t.control_off)
        .child(el().h(6.0).radius(3.0).fill(tok::accent(0, dark)).grow(f.max(0.0001)))
        .child(el().h(6.0).grow((1.0 - f).max(0.0001)))
}

fn view(s: &State, cx: &mut Cx<State>) -> El {
    let dark = cx.system_dark();
    let t = tok::theme(dark);

    // ---- servers sidebar
    let mut side = col()
        .w(248.0)
        .h_full()
        .padding(14.0)
        .gap(8.0)
        .fill(t.card)
        .child(text("Servers").font(type_::TITLE).color(t.text));
    for (i, src) in s.servers.iter().enumerate() {
        let (dot_color, note) = match &s.probes[i] {
            ProbeState::Up(ms) => (rune::color::hex("#2ecc71").unwrap_or(t.text), format!("{ms} ms")),
            ProbeState::Down => (rune::color::hex("#e74c3c").unwrap_or(t.text), "unreachable".to_string()),
            ProbeState::Checking => (t.text_secondary, "checking…".to_string()),
            ProbeState::Unknown => (t.text_secondary, String::new()),
        };
        let (label, url) = (src.name.clone(), src.root_url.clone());
        let row_el = row()
            .w_full()
            .px(10.0)
            .py(9.0)
            .gap(8.0)
            .radius(8.0)
            .fill(t.control_off)
            .child(el().size(9.0, 9.0).radius(5.0).fill(dot_color))
            .child(
                col()
                    .gap(1.0)
                    .child(text(src.name.clone()).font(type_::LABEL).color(t.text))
                    .child(text(note).font(type_::BODY).color(t.text_secondary)),
            )
            .on_press(move |s: &mut State| {
                s.stack.clear();
                open_url(s, label.clone(), url.clone());
            });
        side = side.child(row_el);
    }
    side = side.child(btn("Re-check servers", &t, |s: &mut State| start_probes(s)));
    side = side.child(
        text("The DHAKA-FLIX servers live on a private ISP network — they answer only from inside it.")
            .font(type_::BODY)
            .color(t.text_secondary)
            .w(220.0).wrap(),
    );

    // ---- browse column
    let current_label = s
        .stack
        .last()
        .map(|(label, _)| label.clone())
        .unwrap_or_else(|| "Pick a server".to_string());
    let mut head = row().w_full().gap(8.0).child(
        text(current_label).font(type_::TITLE).color(t.text).grow(1.0),
    );
    if !s.stack.is_empty() {
        head = head.child(btn("Back", &t, |s: &mut State| go_back(s)));
    }
    if let Some((label, url)) = s.stack.last().cloned() {
        head = head.child(btn("Refresh", &t, move |s: &mut State| {
            s.stack.pop();
            open_url(s, label.clone(), url.clone());
        }));
    }
    let mut browse = col().grow(1.0).h_full().padding(14.0).gap(10.0).child(head);
    let mut list = col()
        .w_full()
        .grow(1.0)
        .shrink()
        .gap(6.0)
        .scroll(ScrollSpec { stick_bottom: false });
    match &s.listing {
        Listing::Idle => {
            list = list.child(
                text("Choose a server on the left to browse its folders.")
                    .font(type_::BODY)
                    .color(t.text_secondary),
            );
        }
        Listing::Loading => {
            list = list.child(text("Loading…").font(type_::BODY).color(t.text_secondary));
        }
        Listing::Error(e) => {
            list = list.child(
                text(format!("Could not load this folder: {e}"))
                    .font(type_::BODY)
                    .color(t.text_secondary)
                    .w_full()
                    .wrap(),
            );
        }
        Listing::Ready(entries) => {
            if entries.is_empty() {
                list = list.child(text("This folder is empty.").font(type_::BODY).color(t.text_secondary));
            }
            for entry in entries {
                let name = entry.name.clone();
                if entry.is_dir {
                    let url = entry.url.clone();
                    let label = name.clone();
                    list = list.child(
                        row()
                            .w_full()
                            .h(38.0)
                            .px(10.0)
                            .gap(8.0)
                            .radius(8.0)
                            .fill(t.card)
                            .child(text(format!("▸ {name}")).font(type_::LABEL).color(t.text).grow(1.0))
                            .on_press(move |s: &mut State| open_url(s, label.clone(), url.clone())),
                    );
                } else {
                    let size = entry.size.map(fmt_bytes).unwrap_or_else(|| "—".to_string());
                    let (url, fname) = (entry.url.clone(), name.clone());
                    list = list.child(
                        row()
                            .w_full()
                            .h(38.0)
                            .px(10.0)
                            .gap(8.0)
                            .radius(8.0)
                            .fill(t.card)
                            .child(text(name).font(type_::BODY).color(t.text).grow(1.0))
                            .child(text(size).font(type_::BODY).color(t.text_secondary))
                            .child(btn("Download", &t, move |s: &mut State| {
                                s.manager.enqueue(url.clone(), fname.clone());
                            })),
                    );
                }
            }
        }
    }
    browse = browse.child(list);

    // ---- downloads column
    let mut dl = col()
        .w(330.0)
        .h_full()
        .padding(14.0)
        .gap(8.0)
        .fill(t.card)
        .child(text("Downloads").font(type_::TITLE).color(t.text));
    let mut dl_list = col()
        .w_full()
        .grow(1.0)
        .shrink()
        .gap(8.0)
        .scroll(ScrollSpec { stick_bottom: false });
    {
        let tasks = s.manager.tasks.lock().unwrap();
        if tasks.is_empty() {
            dl_list = dl_list.child(
                text("Nothing yet — download a file and it will appear here, resumable even after a restart.")
                    .font(type_::BODY)
                    .color(t.text_secondary)
                    .w(302.0).wrap(),
            );
        }
        for task in tasks.iter() {
            let id = task.id;
            let status = match task.state {
                DlState::Queued => "Queued — probing…".to_string(),
                DlState::Running => format!(
                    "{} of {} · {}",
                    fmt_bytes(task.downloaded()),
                    if task.total > 0 { fmt_bytes(task.total) } else { "?".to_string() },
                    fmt_speed(task.speed_bps)
                ),
                DlState::Paused => format!("Paused · {} of {}", fmt_bytes(task.downloaded()), fmt_bytes(task.total)),
                DlState::Completed => format!("Done · {}", fmt_bytes(task.total)),
                DlState::Failed => format!("Failed: {}", task.error.clone().unwrap_or_default()),
            };
            let mut card = col()
                .w_full()
                .padding(10.0)
                .gap(6.0)
                .radius(10.0)
                .fill(t.bg)
                .child(text(task.name.clone()).font(type_::LABEL).color(t.text).w_full().wrap())
                .child(progress_bar(task.fraction(), &t, dark))
                .child(text(status).font(type_::BODY).color(t.text_secondary).w_full().wrap());
            let mut actions = row().w_full().gap(6.0);
            match task.state {
                DlState::Running => {
                    actions = actions.child(btn("Pause", &t, move |s: &mut State| s.manager.pause(id)));
                }
                DlState::Paused | DlState::Failed => {
                    actions = actions.child(btn("Resume", &t, move |s: &mut State| s.manager.start(id)));
                }
                _ => {}
            }
            actions = actions.child(btn("Remove", &t, move |s: &mut State| {
                s.manager.remove(id, false)
            }));
            card = card.child(actions);
            dl_list = dl_list.child(card);
        }
    }
    dl = dl.child(dl_list);
    dl = dl.child(
        text(format!("Files land in {}", manager::downloads_dir().display()))
            .font(type_::BODY)
            .color(t.text_secondary)
            .w(302.0).wrap(),
    );

    row().w_full().h_full().fill(t.bg).child(side).child(browse).child(dl)
}

fn main() {
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
        let servers = seed_sources();
        let probes = vec![ProbeState::Unknown; servers.len()];
        State {
        servers,
        probes,
        stack: Vec::new(),
        listing: Listing::Idle,
        probe_latest: Latest::default(),
        listing_latest: Latest::default(),
        manager: Manager::load(),
        }
    })
    .on_ready(move |_| {
        handle.update(|s, _tx| {
            s.probes = vec![ProbeState::Unknown; s.servers.len()];
            start_probes(s);
        });
    })
    .run();
}
