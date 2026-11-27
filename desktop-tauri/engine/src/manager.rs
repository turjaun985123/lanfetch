//! Download manager: owns the task list, runs engine threads, persists
//! state as JSON under ~/.local/share/lanfetch/, and wakes the Rune UI
//! (via the registered Notifier) while downloads progress.

use crate::core::*;
use crate::store::{load_settings, save_settings, Settings};
/// Framework-neutral change notifier: the host application registers a
/// closure that the manager invokes whenever download state changes and
/// the UI should refresh. (Replaces the Rune `Waker` the desktop-rs build
/// used; the manager itself is GUI-agnostic.)
pub type Notifier = std::sync::Arc<dyn Fn() + Send + Sync>;
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
    #[serde(default)]
    id: u64,
    #[serde(default)]
    url: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    total: u64,
    #[serde(default)]
    validator: Option<String>,
    #[serde(default)]
    segments: Vec<Segment>,
    #[serde(default = "default_persisted_state")]
    state: DlState,
    #[serde(default)]
    error: Option<String>,
}

/// A persisted task with a missing state restores as Paused — the
/// one state that neither runs nor claims success (V2 RC-5).
fn default_persisted_state() -> DlState {
    DlState::Paused
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
    /// Pause requested while the probe is still in flight (T17): the
    /// probe lands the task Paused with its plan stored, never
    /// auto-started. Readiness is NOT stored anywhere — it is derived
    /// from `!segments.is_empty()` (DOWNLOAD-ENGINE.md MAP-2); the
    /// pre-V2 stored `ready` flag is what made probe-failed tasks
    /// permanently un-retryable (Wave 0 P0).
    pub pause_intent: bool,
    pub speed_bps: f64,
    last_tick_bytes: u64,
    last_tick_at: std::time::Instant,
}

impl Task {
    /// Segments with live progress while running; the persisted plan
    /// otherwise (identical shape, so the ring renders the same either way).
    pub fn live_segments(&self) -> Vec<Segment> {
        let live = self
            .progress
            .live_segments
            .lock()
            .unwrap_or_else(|e| e.into_inner());
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
    notifier: Mutex<Option<Notifier>>,
    settings: Mutex<Settings>,
    /// Serializes every `save()` (RC-1): concurrent checkpoints from
    /// the ticker, supervisors and command handlers must never
    /// interleave writes to the same state file.
    save_lock: Mutex<()>,
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

/// The user-facing error for a contained engine panic (audit H2).
const PANIC_ERROR: &str = "internal error: the download engine hit an unexpected problem";

/// Audit H2: run `f` under panic containment. The download,
/// probe and ticker supervisors run on bare threads; an uncaught
/// panic used to strand the task in Running/Queued forever — no
/// state write, no ticker stop, a scheduler slot leaked until
/// restart. Returns None when `f` panicked; the caller lands the
/// task in a terminal, retryable state instead.
fn catch_engine_panic<T>(f: impl FnOnce() -> T) -> Option<T> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).ok()
}

/// Audit (slices 1 + 2): a persisted segment plan is untrusted
/// input. Structurally invalid geometry (end before start) drops
/// the whole plan — the task then follows the existing no-plan
/// restore paths and re-probes; merely optimistic `done` values
/// are clamped to the segment's real length.
fn validate_plan(mut segments: Vec<Segment>) -> Vec<Segment> {
    for s in &mut segments {
        if s.end != u64::MAX && s.end < s.start {
            return Vec::new();
        }
        let len = s.len();
        if s.done > len {
            s.done = len;
        }
    }
    segments
}

/// Audit H1: every on-disk path (`.part`, final file, remove)
/// derives from the task name alone, so two tasks must never
/// share one — the same filename from two servers otherwise
/// interleaves bytes in a single `.part` and the second
/// completion silently overwrites the first. When the desired
/// name is taken, insert a browser-style counter before the
/// extension: `movie.mkv` → `movie (2).mkv` → `movie (3).mkv`.
fn unique_name(desired: &str, taken: impl Fn(&str) -> bool) -> String {
    if !taken(desired) {
        return desired.to_string();
    }
    let (stem, ext) = match desired.rfind('.') {
        Some(i) if i > 0 => (&desired[..i], &desired[i..]),
        _ => (desired, ""),
    };
    let mut n = 2;
    loop {
        let candidate = format!("{stem} ({n}){ext}");
        if !taken(&candidate) {
            return candidate;
        }
        n += 1;
    }
}

/// CP-4 reconciliation: clamp a task's segment plan to what the .part
/// file actually holds, and return the (possibly adjusted) plan. The
/// file — not the persisted JSON — is ground truth: bytes the file
/// cannot prove are planned again.
fn reconcile_part(name: &str, mut segments: Vec<Segment>) -> Vec<Segment> {
    let claimed: u64 = segments.iter().map(|s| s.done).sum();
    if claimed == 0 || segments.is_empty() {
        return segments;
    }
    let part = downloads_dir().join(format!("{name}.part"));
    match std::fs::metadata(&part) {
        Err(_) => {
            // Gone entirely: no byte of the claimed progress exists.
            for s in segments.iter_mut() {
                s.done = 0;
            }
        }
        Ok(md) => {
            let len = md.len();
            if len < claimed {
                // Walk in order: earlier segments keep what the file
                // can prove, the first short segment is clamped, and
                // every later segment restarts — a hole can never
                // reach Completed.
                let mut budget = len;
                for s in segments.iter_mut() {
                    let keep = s.done.min(budget);
                    budget -= keep;
                    s.done = keep;
                }
            }
            let bounded = segments.iter().all(|s| s.end != u64::MAX);
            if bounded {
                let total: u64 = segments.iter().map(|s| s.len()).sum();
                if len > total {
                    if let Ok(f) = std::fs::OpenOptions::new().write(true).open(&part) {
                        let _ = f.set_len(total);
                    }
                }
            }
        }
    }
    segments
}

impl Manager {
    pub fn load() -> Arc<Self> {
        let mgr = Arc::new(Self {
            tasks: Mutex::new(Vec::new()),
            next_id: AtomicU64::new(1),
            notifier: Mutex::new(None),
            settings: Mutex::new(load_settings()),
            save_lock: Mutex::new(()),
            stamp: AtomicU64::new(0),
        });
        // A torn previous save falls back to the .bak snapshot rather
        // than silently dropping the whole queue (see store::write_atomic).
        let saved: Option<Vec<PersistedTask>> =
            [state_file(), crate::store::bak_path(&state_file())]
                .iter()
                .find_map(|candidate| {
                    std::fs::read_to_string(candidate)
                        .ok()
                        .and_then(|raw| serde_json::from_str(&raw).ok())
                });
        if let Some(saved) = saved {
            {
                let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                // H1: a pre-fix downloads.json can hold duplicate
                // names (two tasks sharing one .part). Rename the
                // later duplicates with the counter scheme; their
                // old shared .part stays with the first task, and
                // CP-4 reconciliation zeroes the renamed task's
                // unprovable progress on its next launch.
                let mut used_names: Vec<String> = Vec::new();
                for p in saved {
                    let unique = unique_name(&p.name, |n| used_names.iter().any(|u| u == n));
                    used_names.push(unique.clone());
                    // Validate before anything sums or trusts the
                    // plan (audit slices 1 + 2).
                    let segments = validate_plan(p.segments);
                    let downloaded: u64 = segments.iter().map(|s| s.done).sum();
                    let was_ready = !segments.is_empty();
                    let progress = Arc::new(Progress::new(p.total));
                    progress.downloaded.store(downloaded, Ordering::SeqCst);
                    mgr.next_id.fetch_max(p.id + 1, Ordering::SeqCst);
                    tasks.push(Task {
                        id: p.id,
                        url: p.url,
                        name: unique,
                        total: p.total,
                        validator: p.validator,
                        segments,
                        // RC-3 restore mapping: mid-flight at exit
                        // comes back Paused when a plan exists to
                        // resume from; interrupted before its probe
                        // produced a plan, it comes back Failed with
                        // the reason recorded — and Failed is always
                        // retryable (T14), so it is not a dead end.
                        state: match p.state {
                            DlState::Running | DlState::Queued => {
                                if was_ready {
                                    DlState::Paused
                                } else {
                                    DlState::Failed
                                }
                            }
                            other => other,
                        },
                        error: match p.state {
                            DlState::Running | DlState::Queued if !was_ready => {
                                Some("interrupted before the download started".to_string())
                            }
                            _ => p.error,
                        },
                        pause_flag: Arc::new(AtomicBool::new(true)),
                        pause_intent: false,
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

    pub fn set_notifier(&self, notifier: Notifier) {
        *self.notifier.lock().unwrap_or_else(|e| e.into_inner()) = Some(notifier);
    }

    pub fn settings(&self) -> Settings {
        self.settings
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
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
        if let Some(n) = self
            .notifier
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            n();
        }
    }

    /// The UI stamp described on the field: read by poll().
    pub fn stamp(&self) -> u64 {
        self.stamp.load(Ordering::SeqCst)
    }

    fn bump(&self) {
        self.stamp.fetch_add(1, Ordering::SeqCst);
    }

    /// The task tracking a file URL, with its live fraction —
    /// drives the Library card notes and detail-row badges
    /// (Wave 14: the Library used to show no download state).
    pub fn task_by_url(&self, url: &str) -> Option<(DlState, f64)> {
        let tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
        tasks
            .iter()
            .find(|t| t.url == url)
            .map(|t| (t.state, t.fraction()))
    }

    pub fn save(&self) {
        let _save_guard = self.save_lock.lock().unwrap_or_else(|e| e.into_inner());
        let tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
        let saved: Vec<PersistedTask> = tasks
            .iter()
            .map(|t| PersistedTask {
                id: t.id,
                url: t.url.clone(),
                name: t.name.clone(),
                total: t.total,
                validator: t.validator.clone(),
                segments: {
                    // RC-1 (audit slice 2): a Running task's real
                    // progress lives in Progress::live_segments,
                    // refreshed after every chunk — persist THAT,
                    // or the 5 s checkpoint saves the launch-time
                    // plan and a kill loses everything downloaded
                    // since launch.
                    if t.state == DlState::Running {
                        let live = t
                            .progress
                            .live_segments
                            .lock()
                            .unwrap_or_else(|e| e.into_inner());
                        if live.is_empty() {
                            t.segments.clone()
                        } else {
                            live.clone()
                        }
                    } else {
                        t.segments.clone()
                    }
                },
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
    /// Dedup is atomic (T1): the check and the insert happen in one
    /// lock scope, so a fast double-add can never create two tasks for
    /// one URL (the pre-V2 code checked and inserted in separate
    /// scopes). A URL owned by a Failed task retries that task (T14)
    /// instead of creating a shadowing duplicate.
    pub fn enqueue(self: &Arc<Self>, url: String, name: String) {
        enum Act {
            Nothing,
            Retry(u64),
            Probe(u64),
        }
        let act = {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            if tasks.iter().any(|t| {
                t.url == url
                    && matches!(
                        t.state,
                        DlState::Queued | DlState::Running | DlState::Paused
                    )
            }) {
                Act::Nothing
            } else if let Some(t) = tasks
                .iter()
                .find(|t| t.url == url && t.state == DlState::Failed)
            {
                Act::Retry(t.id)
            } else {
                let id = self.next_id.fetch_add(1, Ordering::SeqCst);
                // H1: the stored name must be unique across all
                // tasks — every path derives from it.
                let clean = sanitize_keep_ext(&name);
                let unique = unique_name(&clean, |n| tasks.iter().any(|t| t.name == n));
                tasks.push(Task {
                    id,
                    url: url.clone(),
                    name: unique,
                    total: 0,
                    validator: None,
                    segments: Vec::new(),
                    state: DlState::Queued,
                    error: None,
                    pause_flag: Arc::new(AtomicBool::new(false)),
                    pause_intent: false,
                    progress: Arc::new(Progress::new(0)),
                    speed_bps: 0.0,
                    last_tick_bytes: 0,
                    last_tick_at: std::time::Instant::now(),
                });
                Act::Probe(id)
            }
        };
        match act {
            Act::Nothing => {}
            Act::Retry(id) => self.start(id),
            Act::Probe(id) => {
                self.save();
                self.bump();
                self.wake();
                self.probe_task(id);
            }
        }
    }

    /// Run the probe for a task (initial enqueue, or a retry) on a
    /// worker thread and land the outcome. Success stores the plan —
    /// or keeps the existing plan and its progress when the re-probe
    /// describes the same file (RT-3) — and pumps. Failure marks the
    /// task Failed (T4). A pause requested while the probe was in
    /// flight lands the task Paused with its plan stored (T17); a
    /// task removed meanwhile is left alone.
    fn probe_task(self: &Arc<Self>, id: u64) {
        let url = {
            let tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            let Some(t) = tasks.iter().find(|t| t.id == id) else {
                return;
            };
            t.url.clone()
        };
        let mgr = Arc::clone(self);
        std::thread::spawn(move || {
            // H2: a panic mid-probe must not strand the task in
            // Queued forever — contain it and land the task Failed.
            let panicked = catch_engine_panic(|| {
                let lanes = mgr.settings().connections.max(1) as usize;
                match probe_url(&url) {
                    Ok(probe) => {
                        let total = probe.total.unwrap_or(0);
                        // Assigned unconditionally inside the block below
                        // (its only other exit is the early return).
                        let paused_intent;
                        {
                            let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                            let Some(t) = tasks.iter_mut().find(|t| t.id == id) else {
                                return;
                            };
                            // RT-3 drift check: same total, and validators
                            // equal whenever both are known, means the
                            // stored plan (and its downloaded bytes) still
                            // describes this file — keep it.
                            let no_drift = !t.segments.is_empty()
                                && t.total == total
                                && match (&t.validator, &probe.validator) {
                                    (Some(old), Some(new)) => old == new,
                                    _ => true,
                                };
                            if !no_drift {
                                t.segments = if probe.accept_ranges {
                                    plan_segments(total, lanes)
                                } else if total > 0 {
                                    vec![Segment {
                                        start: 0,
                                        end: total - 1,
                                        done: 0,
                                    }]
                                } else {
                                    vec![Segment {
                                        start: 0,
                                        end: u64::MAX,
                                        done: 0,
                                    }]
                                };
                                t.progress.downloaded.store(0, Ordering::SeqCst);
                            }
                            t.total = total;
                            t.validator = probe.validator.clone();
                            t.progress.total.store(total, Ordering::SeqCst);
                            paused_intent = t.pause_intent;
                            t.pause_intent = false;
                            if paused_intent {
                                t.state = DlState::Paused;
                                t.pause_flag.store(true, Ordering::SeqCst);
                            } else {
                                t.state = DlState::Queued;
                            }
                        }
                        mgr.save();
                        mgr.bump();
                        if !paused_intent {
                            mgr.pump();
                        }
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
                }
            })
            .is_none();
            if panicked {
                // Land the task Failed (retryable via T14) rather
                // than Queued forever; save + bump + wake so the UI
                // shows it immediately.
                {
                    let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                    if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                        if t.state == DlState::Queued {
                            t.state = DlState::Failed;
                            t.error = Some(PANIC_ERROR.to_string());
                        }
                    }
                }
                mgr.save();
                mgr.bump();
                mgr.wake();
            }
        });
    }

    /// Resume or retry a task (T13/T14). A Paused task with a plan
    /// re-enters the queue directly; a Failed task — or a Paused one
    /// whose probe never produced a plan — always re-probes first.
    /// Readiness is derived from the plan, never a stored gate: the
    /// pre-V2 `ready` flag made probe-failed tasks un-retryable
    /// forever (the Wave 0 P0 deadlock).
    pub fn start(self: &Arc<Self>, id: u64) {
        enum Act {
            Nothing,
            Queue,
            Probe,
        }
        let act = {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            let Some(t) = tasks.iter_mut().find(|t| t.id == id) else {
                return;
            };
            match t.state {
                DlState::Running | DlState::Completed => Act::Nothing,
                // "Resume" of a still-queued task only cancels a
                // pending pause intent recorded during its probe.
                DlState::Queued => {
                    t.pause_intent = false;
                    Act::Nothing
                }
                DlState::Failed => {
                    t.error = None;
                    t.pause_intent = false;
                    t.state = DlState::Queued;
                    Act::Probe
                }
                DlState::Paused => {
                    t.pause_intent = false;
                    t.error = None;
                    t.state = DlState::Queued;
                    if t.segments.is_empty() {
                        Act::Probe
                    } else {
                        Act::Queue
                    }
                }
            }
        };
        match act {
            Act::Nothing => {}
            Act::Queue => {
                self.bump();
                self.pump();
                self.wake();
            }
            Act::Probe => {
                self.bump();
                self.wake();
                self.probe_task(id);
            }
        }
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
                if t.state == DlState::Queued && !t.segments.is_empty() {
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
            let Some(t) = tasks.iter_mut().find(|t| t.id == id) else {
                return;
            };
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
        // CP-4: the .part on disk is ground truth, not the persisted
        // plan. Reconcile before any byte is requested — a .part that
        // was deleted or truncated while the app was closed clamps the
        // plan, so the missing tail is re-fetched instead of the file
        // completing with zero-filled holes.
        let segments = reconcile_part(&name, segments);
        {
            let mut tasks = self.tasks.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                t.segments = segments.clone();
                let have: u64 = segments.iter().map(|s| s.done).sum();
                t.progress.downloaded.store(have, Ordering::SeqCst);
                t.last_tick_bytes = have;
            }
        }
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
                // H2: a panic in the ticker must not leak
                // ticker_active (a later resume would never get a
                // ticker again) — the reset runs on every exit.
                let _ = catch_engine_panic(|| {
                    let mut last_checkpoint = std::time::Instant::now();
                    while !progress.stopped.load(Ordering::SeqCst) {
                        std::thread::sleep(std::time::Duration::from_millis(250));
                        if progress.stopped.load(Ordering::SeqCst) {
                            break;
                        }
                        mgr.tick_speed(id);
                        // RC-1 periodic checkpoint: a kill mid-download
                        // loses at most ~5 s of progress, not everything
                        // since the last state transition.
                        if last_checkpoint.elapsed() >= std::time::Duration::from_secs(5) {
                            mgr.save();
                            last_checkpoint = std::time::Instant::now();
                        }
                        mgr.wake();
                    }
                });
                progress.ticker_active.store(false, Ordering::SeqCst);
            });
        }
        std::thread::spawn(move || {
            let dir = downloads_dir();
            let _ = std::fs::create_dir_all(&dir);
            let part = dir.join(format!("{name}.part"));
            // H2: contain a panic inside the engine — everything
            // below (ticker stop, state write, rename) must run on
            // EVERY exit, or the task strands in Running forever.
            // On panic the task lands Failed with the plan it
            // launched with, so a retry resumes from the .part.
            let out = match catch_engine_panic(|| {
                run_download(
                    &url,
                    &part,
                    segments.clone(),
                    validator.clone(),
                    Arc::clone(&pause_flag),
                    Arc::clone(&progress),
                )
            }) {
                Some(out) => out,
                None => {
                    progress.failed.store(true, Ordering::SeqCst);
                    *progress.message.lock().unwrap_or_else(|e| e.into_inner()) =
                        Some(PANIC_ERROR.to_string());
                    segments
                }
            };
            // Every exit lands here — complete, failed or paused:
            // tell the ticker to stop (Wave 9).
            progress.stopped.store(true, Ordering::SeqCst);
            let mut finished = progress.finished.load(Ordering::SeqCst);
            let mut failed = progress.failed.load(Ordering::SeqCst);
            // T18: a task removed while its workers were unwinding
            // must not be resurrected — no rename, no state write, no
            // notification for a task that no longer exists. (The
            // pre-V2 code renamed .part → final unconditionally, so a
            // removed download could reappear as a finished file.)
            let exists = {
                let tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                tasks.iter().any(|t| t.id == id)
            };
            let mut rename_error: Option<String> = None;
            if finished && exists {
                let final_path = dir.join(&name);
                match std::fs::rename(&part, &final_path) {
                    Ok(()) => {
                        // Best-effort directory fsync so the rename
                        // itself survives a power loss (CP-5).
                        if let Ok(d) = std::fs::File::open(&dir) {
                            let _ = d.sync_all();
                        }
                    }
                    Err(e) => {
                        // T12: a failed rename fails the task; the
                        // .part is preserved so a retry can finish
                        // the job. (Pre-V2 the result was discarded
                        // with `let _ =` and the task claimed success.)
                        rename_error =
                            Some(format!("could not move the finished file into place: {e}"));
                        finished = false;
                        failed = true;
                    }
                }
            }
            if exists {
                let mut tasks = mgr.tasks.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                    t.segments = out;
                    if finished {
                        t.state = DlState::Completed;
                        t.speed_bps = 0.0;
                    } else if failed {
                        t.state = DlState::Failed;
                        t.error = rename_error.clone().or_else(|| {
                            progress
                                .message
                                .lock()
                                .unwrap_or_else(|e| e.into_inner())
                                .clone()
                        });
                    } else {
                        t.state = DlState::Paused;
                        t.speed_bps = 0.0;
                    }
                }
            }
            if exists && finished {
                notify("Download finished", &name);
            } else if exists && failed {
                let err = rename_error.unwrap_or_else(|| {
                    progress
                        .message
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .clone()
                        .unwrap_or_default()
                });
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
                match t.state {
                    // T6: running workers unwind asynchronously; the
                    // supervisor lands the task in Paused.
                    DlState::Running => {}
                    // T16/T17: a queued task with a plan pauses
                    // immediately; one still probing records the
                    // intent and the probe lands it Paused (a probe
                    // failure wins over the intent and lands Failed).
                    // The pre-V2 code set only the flag here, and
                    // pump() cleared it at launch — the pause was
                    // silently discarded.
                    DlState::Queued => {
                        if t.segments.is_empty() {
                            t.pause_intent = true;
                        } else {
                            t.state = DlState::Paused;
                        }
                    }
                    _ => {}
                }
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
                    t.speed_bps = if t.speed_bps > 0.0 {
                        0.5 * inst + 0.5 * t.speed_bps
                    } else {
                        inst
                    };
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
    fn validate_plan_drops_invalid_geometry_and_clamps_done() {
        // Audit slices 1 + 2: persisted plans are untrusted.
        let invalid = vec![Segment {
            start: 100,
            end: 50,
            done: 0,
        }];
        assert!(validate_plan(invalid).is_empty());
        let optimistic = vec![Segment {
            start: 0,
            end: 99,
            done: 10_000,
        }];
        let fixed = validate_plan(optimistic);
        assert_eq!(fixed.len(), 1);
        assert_eq!(fixed[0].done, 100);
        // The open-ended (unknown length) segment is valid and
        // its done is not clamped against the sentinel length.
        let open = vec![Segment {
            start: 0,
            end: u64::MAX,
            done: 500,
        }];
        assert_eq!(validate_plan(open)[0].done, 500);
    }

    #[test]
    fn panic_containment_returns_none_and_keeps_values() {
        // H2: the guard the supervisors rely on — a panicking
        // closure yields None (caller lands the task Failed), a
        // healthy one yields its value untouched.
        assert_eq!(catch_engine_panic(|| 42), Some(42));
        let panicked: Option<()> = catch_engine_panic(|| panic!("simulated engine fault"));
        assert_eq!(panicked, None);
    }

    #[test]
    fn unique_name_counters_collisions() {
        // H1: free names pass through; collisions get the
        // browser-style counter before the extension.
        let none: Vec<String> = Vec::new();
        assert_eq!(
            unique_name("movie.mkv", |n| none.iter().any(|u| u == n)),
            "movie.mkv"
        );
        let one = vec!["movie.mkv".to_string()];
        assert_eq!(
            unique_name("movie.mkv", |n| one.iter().any(|u| u == n)),
            "movie (2).mkv"
        );
        let two = vec!["movie.mkv".to_string(), "movie (2).mkv".to_string()];
        assert_eq!(
            unique_name("movie.mkv", |n| two.iter().any(|u| u == n)),
            "movie (3).mkv"
        );
        // Extensionless names counter at the end.
        let plain = vec!["README".to_string()];
        assert_eq!(
            unique_name("README", |n| plain.iter().any(|u| u == n)),
            "README (2)"
        );
    }

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
