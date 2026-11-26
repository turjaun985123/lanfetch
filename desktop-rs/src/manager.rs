//! Download manager: owns the task list, runs engine threads, persists
//! state as JSON under ~/.local/share/lanfetch/, and wakes the Rune UI
//! (via the stored Waker) while downloads progress.

use crate::core::*;
use crate::store::{load_settings, save_settings, Settings};
use rune::Waker;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DlState {
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct PersistedTask {
    id: u64,
    url: String,
    name: String,
    total: u64,
    validator: Option<String>,
    segments: Vec<Segment>,
    state: DlState,
    error: Option<String>,
}

pub struct Task {
    pub id: u64,
    pub url: String,
    pub name: String,
    pub total: u64,
    pub validator: Option<String>,
    pub segments: Vec<Segment>,
    pub state: DlState,
    pub error: Option<String>,
    // runtime only
    pub pause_flag: Arc<AtomicBool>,
    pub progress: Arc<Progress>,
    /// Probed and planned; waiting only for a free concurrency slot.
    pub ready: bool,
    pub speed_bps: f64,
    last_tick_bytes: u64,
    last_tick_at: std::time::Instant,
}

impl Task {
    /// Segments with live progress while running; the persisted plan
    /// otherwise (identical shape, so the ring renders the same either way).
    pub fn live_segments(&self) -> Vec<Segment> {
        let live = self.progress.live_segments.lock().unwrap_or_else(|e| e.into_inner());
        if live.is_empty() {
            self.segments.clone()
        } else {
            live.clone()
        }
    }
    pub fn downloaded(&self) -> u64 {
        self.progress.downloaded.load(Ordering::SeqCst)
    }
    /// Seconds remaining at the current (smoothed) speed, when a
    /// total and a speed are both known (Wave 8).
    pub fn eta_secs(&self) -> Option<f64> {
        if self.total == 0 || self.speed_bps <= 0.0 {
            return None;
        }
        let remaining = self.total.saturating_sub(self.downloaded());
        Some(remaining as f64 / self.speed_bps)
    }
    pub fn fraction(&self) -> f64 {
        if self.total == 0 {
            0.0
        } else {
            (self.downloaded() as f64 / self.total as f64).clamp(0.0, 1.0)
        }
    }
}

pub struct Manager {
    pub tasks: Mutex<Vec<Task>>,
    next_id: AtomicU64,
    waker: Mutex<Option<Waker>>,
    settings: Mutex<Settings>,
    /// Monotonic counter of everything the Transfers UI renders:
    /// byte progress (via tick_speed) and every state transition.
    /// poll() compares it against the last frame's value — before
    /// this existed (50-wave program, Wave 4) download wakes never
    /// produced a repaint, so progress looked frozen even though
    /// the engine and the 4 Hz ticker were both live.
    stamp: AtomicU64,
}

pub fn data_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".local/share/lanfetch")
}

pub fn downloads_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join("Downloads/LanFetch")
}

/// Desktop notification via notify-send (the Android app posts a
/// per-file notification on completion/failure; this is the Linux
/// equivalent). Best-effort: silently does nothing when notify-send
/// is not installed.
fn notify(title: &str, body: &str) {
    let _ = std::process::Command::new("notify-send")
        .args(["--app-name=LanFetch", title, body])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
}

fn state_file() -> PathBuf {
    data_dir().join("downloads.json")
}

impl Manager {
    pub fn load() -> Arc<Self> {
        let mgr = Arc::new(Self {
            tasks: Mutex::new(Vec::new()),
            next_id: AtomicU64::new(1),
            waker: Mutex::new(None),
            settings: Mutex::new(load_settings()),
            stamp: AtomicU64::new(0),
        });
        // A torn previous save falls back to the .bak snapshot rather
        // than silently dropping the whole queue (see store::write_atomic).
        let saved: Option<Vec<PersistedTask>> =
            [state_file(), crate::store::bak_path(&state_file())].iter().find_map(|candidate| {
                std::fs::read_to_string(candidate)
                    .ok()
                    .and_then(|raw| serde_json::from_str(&raw).ok())
            });
        if let Some(saved) = saved {
            {
                let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                for p in saved {
                    let downloaded: u64 = p.segments.iter().map(|s| s.done).sum();
                    let was_ready = !p.segments.is_empty();
                    let progress = Arc::new(Progress::new(p.total));
                    progress.downloaded.store(downloaded, Ordering::SeqCst);
                    mgr.next_id.fetch_max(p.id + 1, Ordering::SeqCst);
                    tasks.push(Task {
                        id: p.id,
                        url: p.url,
                        name: p.name,
                        total: p.total,
                        validator: p.validator,
                        segments: p.segments,
                        // Anything mid-flight at exit comes back paused.
                        state: match p.state {
                            DlState::Running | DlState::Queued => DlState::Paused,
                            other => other,
                        },
                        error: p.error,
                        pause_flag: Arc::new(AtomicBool::new(true)),
                        // A restored task with a plan can resume immediately.
                        ready: was_ready,
                        progress,
                        speed_bps: 0.0,
                        last_tick_bytes: downloaded,
                        last_tick_at: std::time::Instant::now(),
                    });
                }
            }
        }
        mgr
    }

    pub fn set_waker(&self, waker: Waker) {
        *self.waker.lock().unwrap_or_else(|e| e.into_inner()) = Some(waker);
    }

    pub fn settings(&self) -> Settings {
        self.settings.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Persist new settings and re-run the scheduler (a raised concurrency
    /// limit starts waiting downloads immediately).
    pub fn apply_settings(self: &Arc<Self>, s: Settings) {
        save_settings(&s);
        *self.settings.lock().unwrap_or_else(|e| e.into_inner()) = s;
        self.pump();
        self.wake();
    }

    pub fn wake(&self) {
        if let Some(w) = self.waker.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            w();
        }
    }

    /// The UI stamp described on the field: read by poll().
    pub fn stamp(&self) -> u64 {
        self.stamp.load(Ordering::SeqCst)
    }

    fn bump(&self) {
        self.stamp.fetch_add(1, Ordering::SeqCst);
    }

    pub fn save(&self) {
        let tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
        let saved: Vec<PersistedTask> = tasks
            .iter()
            .map(|t| PersistedTask {
                id: t.id,
                url: t.url.clone(),
                name: t.name.clone(),
                total: t.total,
                validator: t.validator.clone(),
                segments: t.segments.clone(),
                state: t.state,
                error: t.error.clone(),
            })
            .collect();
        drop(tasks);
        let _ = std::fs::create_dir_all(data_dir());
        if let Ok(json) = serde_json::to_string_pretty(&saved) {
            // Atomic write + .bak snapshot: a crash mid-save must not
            // tear the only copy of the queue and its resume state.
            crate::store::write_atomic(&state_file(), &json);
        }
    }

    /// Queue a new download (probes on a worker thread, then starts).
    /// A URL that is already queued, running or paused is not duplicated
    /// (the Android download coordinator dedups the same way).
    pub fn enqueue(self: &Arc<Self>, url: String, name: String) {
        {
            let tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            if tasks.iter().any(|t| {
                t.url == url && matches!(t.state, DlState::Queued | DlState::Running | DlState::Paused)
            }) {
                return;
            }
        }
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let progress = Arc::new(Progress::new(0));
        {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            tasks.push(Task {
                id,
                url: url.clone(),
                name: sanitize_keep_ext(&name),
                total: 0,
                validator: None,
                segments: Vec::new(),
                state: DlState::Queued,
                error: None,
                pause_flag: Arc::new(AtomicBool::new(false)),
                ready: false,
                progress,
                speed_bps: 0.0,
                last_tick_bytes: 0,
                last_tick_at: std::time::Instant::now(),
            });
        }
        self.save();
        self.bump();
        self.wake();
        let mgr = Arc::clone(self);
        std::thread::spawn(move || match probe_url(&url) {
            Ok(probe) => {
                let total = probe.total.unwrap_or(0);
                let lanes = mgr.settings().connections.max(1) as usize;
                let segments = if probe.accept_ranges {
                    plan_segments(total, lanes)
                } else if total > 0 {
                    vec![Segment { start: 0, end: total - 1, done: 0 }]
                } else {
                    vec![Segment { start: 0, end: u64::MAX, done: 0 }]
                };
                {
                    let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                    if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                        t.total = total;
                        t.validator = probe.validator.clone();
                        t.segments = segments;
                        t.ready = true;
                        t.progress.total.store(total, Ordering::SeqCst);
                    }
                }
                mgr.save();
                mgr.bump();
                mgr.pump();
                mgr.wake();
            }
            Err(e) => {
                let mut failed_name = String::new();
                {
                    let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                    if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                        t.state = DlState::Failed;
                        t.error = Some(e.clone());
                        failed_name = t.name.clone();
                    }
                }
                mgr.save();
                mgr.bump();
                mgr.wake();
                if !failed_name.is_empty() {
                    notify("Download failed", &format!("{failed_name}: {e}"));
                }
            }
        });
    }

    /// Resume a paused/failed task: it re-enters the queue and the
    /// scheduler starts it as soon as a concurrency slot is free.
    pub fn start(self: &Arc<Self>, id: u64) {
        {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            let Some(t) = tasks.iter_mut().find(|t| t.id == id) else { return };
            if t.state == DlState::Running || t.state == DlState::Completed || !t.ready {
                return;
            }
            t.state = DlState::Queued;
            t.error = None;
        }
        self.bump();
        self.pump();
        self.wake();
    }

    /// Scheduler: start ready, queued tasks (in queue order) until the
    /// configured number of simultaneous downloads is running.
    pub fn pump(self: &Arc<Self>) {
        let max = self.settings().max_concurrent.max(1);
        let mut to_launch: Vec<u64> = Vec::new();
        {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            let mut running = tasks.iter().filter(|t| t.state == DlState::Running).count() as u32;
            for t in tasks.iter_mut() {
                if running >= max {
                    break;
                }
                if t.state == DlState::Queued && t.ready {
                    t.state = DlState::Running;
                    t.error = None;
                    t.pause_flag.store(false, Ordering::SeqCst);
                    running += 1;
                    to_launch.push(t.id);
                }
            }
        }
        if !to_launch.is_empty() {
            self.bump();
        }
        for id in to_launch {
            self.launch(id);
        }
    }

    /// Spawn the engine thread for a task already marked Running by pump().
    fn launch(self: &Arc<Self>, id: u64) {
        let (url, name, segments, validator, pause_flag, progress) = {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            let Some(t) = tasks.iter_mut().find(|t| t.id == id) else { return };
            // Restart the speed clock at launch (Wave 8): the tick
            // fields were set at enqueue/restore, so without this
            // the first sample after a resume spans the whole
            // paused interval and speed reads ~0.
            t.last_tick_bytes = t.downloaded();
            t.last_tick_at = std::time::Instant::now();
            t.speed_bps = 0.0;
            // A resumed task's Progress still carries stopped=true
            // from its previous run; clear it or the new ticker
            // exits instantly (Wave 9).
            t.progress.stopped.store(false, Ordering::SeqCst);
            (
                t.url.clone(),
                t.name.clone(),
                t.segments.clone(),
                t.validator.clone(),
                Arc::clone(&t.pause_flag),
                Arc::clone(&t.progress),
            )
        };
        self.wake();
        let mgr = Arc::clone(self);
        // UI ticker: wake ~4x/s while this task runs so bars/speed
        // move. Loops on Progress::stopped (set on EVERY engine
        // exit — the old finished/failed condition leaked the
        // ticker forever on pause) and is swap-guarded so a resume
        // never runs a second ticker for the same task (Wave 9).
        if !progress.ticker_active.swap(true, Ordering::SeqCst) {
            let mgr = Arc::clone(self);
            let progress = Arc::clone(&progress);
            std::thread::spawn(move || {
                while !progress.stopped.load(Ordering::SeqCst) {
                    std::thread::sleep(std::time::Duration::from_millis(250));
                    if progress.stopped.load(Ordering::SeqCst) {
                        break;
                    }
                    mgr.tick_speed(id);
                    mgr.wake();
                }
                progress.ticker_active.store(false, Ordering::SeqCst);
            });
        }
        std::thread::spawn(move || {
            let dir = downloads_dir();
            let _ = std::fs::create_dir_all(&dir);
            let part = dir.join(format!("{name}.part"));
            let out = run_download(&url, &part, segments, validator, pause_flag, Arc::clone(&progress));
            // Every exit lands here — complete, failed or paused:
            // tell the ticker to stop (Wave 9).
            progress.stopped.store(true, Ordering::SeqCst);
            let finished = progress.finished.load(Ordering::SeqCst);
            let failed = progress.failed.load(Ordering::SeqCst);
            if finished {
                let final_path = dir.join(&name);
                let _ = std::fs::rename(&part, &final_path);
            }
            {
                let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                    t.segments = out;
                    if finished {
                        t.state = DlState::Completed;
                        t.speed_bps = 0.0;
                    } else if failed {
                        t.state = DlState::Failed;
                        t.error = progress.message.lock().unwrap_or_else(|e| e.into_inner()).clone();
                    } else {
                        t.state = DlState::Paused;
                        t.speed_bps = 0.0;
                    }
                }
            }
            if finished {
                notify("Download finished", &name);
            } else if failed {
                let err = progress.message.lock().unwrap_or_else(|e| e.into_inner()).clone().unwrap_or_default();
                notify("Download failed", &format!("{name}: {err}"));
            }
            mgr.bump();
            mgr.save();
            mgr.pump();
            mgr.wake();
        });
    }

    pub fn pause(&self, id: u64) {
        {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                t.pause_flag.store(true, Ordering::SeqCst);
            }
        }
        // Feedback now, not whenever the engine thread unwinds
        // (audit F4: pause used to neither bump nor wake).
        self.bump();
        self.wake();
    }

    /// Drop all completed tasks (the Transfers header's Clear action).
    pub fn clear_completed(self: &Arc<Self>) {
        {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            tasks.retain(|t| t.state != DlState::Completed);
        }
        self.save();
        self.bump();
        self.wake();
    }

    pub fn remove(self: &Arc<Self>, id: u64, delete_part: bool) {
        let removed = {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(pos) = tasks.iter().position(|t| t.id == id) {
                let t = tasks.remove(pos);
                t.pause_flag.store(true, Ordering::SeqCst);
                Some(t)
            } else {
                None
            }
        };
        if let Some(t) = removed {
            if delete_part {
                let _ = std::fs::remove_file(downloads_dir().join(format!("{}.part", t.name)));
            }
        }
        self.save();
        self.bump();
        self.wake();
    }

    fn tick_speed(&self, id: u64) {
        let mut moved = false;
        {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                let now = std::time::Instant::now();
                let dt = now.duration_since(t.last_tick_at).as_secs_f64();
                if dt >= 0.2 {
                    let bytes = t.downloaded();
                    // EMA smoothing (Wave 8): the raw two-point
                    // sample over ~250 ms bursts wildly at LAN
                    // speeds (256 KiB chunks); half-weight history
                    // keeps the readout — and the ETA derived from
                    // it — steady.
                    let inst = (bytes.saturating_sub(t.last_tick_bytes) as f64) / dt;
                    t.speed_bps = if t.speed_bps > 0.0 { 0.5 * inst + 0.5 * t.speed_bps } else { inst };
                    moved = bytes != t.last_tick_bytes;
                    t.last_tick_bytes = bytes;
                    t.last_tick_at = now;
                }
            }
        }
        // Only real byte movement earns a repaint: a paused task's
        // ticker (which outlives the pause until the lifecycle wave)
        // must not keep the UI redrawing forever.
        if moved {
            self.bump();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stamp_advances_on_transitions() {
        // The regression shape for the frozen-Transfers bug: state
        // transitions must move the stamp poll() watches, or the UI
        // never repaints for download activity.
        let m = Manager::load();
        let s0 = m.stamp();
        m.clear_completed();
        assert!(m.stamp() > s0, "clear_completed must bump the UI stamp");
        let s1 = m.stamp();
        m.pause(999_999); // unknown id: still user-visible feedback path
        assert!(m.stamp() > s1, "pause must bump the UI stamp");
    }
}
