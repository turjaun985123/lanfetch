//! Download manager: owns the task list, runs engine threads, persists
//! state as JSON under ~/.local/share/lanfetch/, and wakes the Rune UI
//! (via the stored Waker) while downloads progress.

use crate::core::*;
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
    pub speed_bps: f64,
    last_tick_bytes: u64,
    last_tick_at: std::time::Instant,
}

impl Task {
    pub fn downloaded(&self) -> u64 {
        self.progress.downloaded.load(Ordering::SeqCst)
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
}

pub fn data_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".local/share/lanfetch")
}

pub fn downloads_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join("Downloads/LanFetch")
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
        });
        if let Ok(raw) = std::fs::read_to_string(state_file()) {
            if let Ok(saved) = serde_json::from_str::<Vec<PersistedTask>>(&raw) {
                let mut tasks = mgr.tasks.lock().unwrap();
                for p in saved {
                    let downloaded: u64 = p.segments.iter().map(|s| s.done).sum();
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
        *self.waker.lock().unwrap() = Some(waker);
    }

    pub fn wake(&self) {
        if let Some(w) = self.waker.lock().unwrap().as_ref() {
            w();
        }
    }

    pub fn save(&self) {
        let tasks = self.tasks.lock().unwrap();
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
            let _ = std::fs::write(state_file(), json);
        }
    }

    /// Queue a new download (probes on a worker thread, then starts).
    pub fn enqueue(self: &Arc<Self>, url: String, name: String) {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let progress = Arc::new(Progress::new(0));
        {
            let mut tasks = self.tasks.lock().unwrap();
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
                progress,
                speed_bps: 0.0,
                last_tick_bytes: 0,
                last_tick_at: std::time::Instant::now(),
            });
        }
        self.save();
        self.wake();
        let mgr = Arc::clone(self);
        std::thread::spawn(move || match probe_url(&url) {
            Ok(probe) => {
                let total = probe.total.unwrap_or(0);
                let segments = if probe.accept_ranges {
                    plan_segments(total, 8)
                } else if total > 0 {
                    vec![Segment { start: 0, end: total - 1, done: 0 }]
                } else {
                    vec![Segment { start: 0, end: u64::MAX, done: 0 }]
                };
                {
                    let mut tasks = mgr.tasks.lock().unwrap();
                    if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                        t.total = total;
                        t.validator = probe.validator.clone();
                        t.segments = segments;
                        t.progress.total.store(total, Ordering::SeqCst);
                    }
                }
                mgr.save();
                mgr.start(id);
            }
            Err(e) => {
                let mut tasks = mgr.tasks.lock().unwrap();
                if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                    t.state = DlState::Failed;
                    t.error = Some(e);
                }
                drop(tasks);
                mgr.save();
                mgr.wake();
            }
        });
    }

    /// Start (or resume) a task's engine thread.
    pub fn start(self: &Arc<Self>, id: u64) {
        let (url, name, segments, validator, pause_flag, progress) = {
            let mut tasks = self.tasks.lock().unwrap();
            let Some(t) = tasks.iter_mut().find(|t| t.id == id) else { return };
            if t.state == DlState::Running || t.state == DlState::Completed {
                return;
            }
            t.state = DlState::Running;
            t.error = None;
            t.pause_flag.store(false, Ordering::SeqCst);
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
        // UI ticker: wake ~4x/s while this task runs so bars/speed move.
        {
            let mgr = Arc::clone(self);
            let progress = Arc::clone(&progress);
            std::thread::spawn(move || {
                while !progress.finished.load(Ordering::SeqCst) && !progress.failed.load(Ordering::SeqCst) {
                    std::thread::sleep(std::time::Duration::from_millis(250));
                    mgr.tick_speed(id);
                    mgr.wake();
                }
            });
        }
        std::thread::spawn(move || {
            let dir = downloads_dir();
            let _ = std::fs::create_dir_all(&dir);
            let part = dir.join(format!("{name}.part"));
            let out = run_download(&url, &part, segments, validator, pause_flag, Arc::clone(&progress));
            let finished = progress.finished.load(Ordering::SeqCst);
            let failed = progress.failed.load(Ordering::SeqCst);
            if finished {
                let final_path = dir.join(&name);
                let _ = std::fs::rename(&part, &final_path);
            }
            {
                let mut tasks = mgr.tasks.lock().unwrap();
                if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
                    t.segments = out;
                    if finished {
                        t.state = DlState::Completed;
                        t.speed_bps = 0.0;
                    } else if failed {
                        t.state = DlState::Failed;
                        t.error = progress.message.lock().unwrap().clone();
                    } else {
                        t.state = DlState::Paused;
                        t.speed_bps = 0.0;
                    }
                }
            }
            mgr.save();
            mgr.wake();
        });
    }

    pub fn pause(&self, id: u64) {
        let mut tasks = self.tasks.lock().unwrap();
        if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
            t.pause_flag.store(true, Ordering::SeqCst);
        }
    }

    pub fn remove(&self, id: u64, delete_part: bool) {
        let removed = {
            let mut tasks = self.tasks.lock().unwrap();
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
        self.wake();
    }

    fn tick_speed(&self, id: u64) {
        let mut tasks = self.tasks.lock().unwrap();
        if let Some(t) = tasks.iter_mut().find(|t| t.id == id) {
            let now = std::time::Instant::now();
            let dt = now.duration_since(t.last_tick_at).as_secs_f64();
            if dt >= 0.2 {
                let bytes = t.downloaded();
                t.speed_bps = (bytes.saturating_sub(t.last_tick_bytes) as f64) / dt;
                t.last_tick_bytes = bytes;
                t.last_tick_at = now;
            }
        }
    }
}
