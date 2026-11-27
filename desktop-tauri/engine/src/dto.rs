//! IPC DTOs — the only shapes that cross the Tauri boundary.
//!
//! The carried domain types are never exposed directly: `catalog::Title`
//! has no serde derives by design, and `manager::Task` holds runtime
//! `Arc`s. Commands map domain values into these DTOs at the boundary.
//! Field names are camelCase on the wire.

use serde::Serialize;

use crate::core::Segment;
use crate::manager::{DlState, Task};
use crate::store::{Settings, SourceItem};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsDto {
    pub connections: u32,
    pub max_concurrent: u32,
    pub theme: String,
    pub language: String,
    pub onboarding_done: bool,
}

impl From<&Settings> for SettingsDto {
    fn from(s: &Settings) -> Self {
        SettingsDto {
            connections: s.connections,
            max_concurrent: s.max_concurrent,
            theme: s.theme.clone(),
            language: s.language.clone(),
            onboarding_done: s.onboarding_done,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceDto {
    pub name: String,
    pub root_url: String,
    pub enabled: bool,
    pub custom: bool,
}

impl From<&SourceItem> for SourceDto {
    fn from(s: &SourceItem) -> Self {
        SourceDto {
            name: s.name.clone(),
            root_url: s.root_url.clone(),
            enabled: s.enabled,
            custom: s.custom,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SegmentDto {
    pub start: u64,
    /// `null` for the open-ended final segment of an unknown-size file
    /// (internally `u64::MAX`, which JavaScript cannot represent).
    pub end: Option<u64>,
    pub done: u64,
}

impl From<&Segment> for SegmentDto {
    fn from(s: &Segment) -> Self {
        SegmentDto {
            start: s.start,
            end: if s.end == u64::MAX { None } else { Some(s.end) },
            done: s.done,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskDto {
    pub id: u64,
    pub url: String,
    pub name: String,
    pub state: String,
    pub total: u64,
    pub downloaded: u64,
    pub fraction: f64,
    pub speed_bps: f64,
    pub eta_secs: Option<f64>,
    pub error: Option<String>,
    pub ready: bool,
    /// A completed task whose file is no longer in the downloads
    /// folder (the user moved or deleted it) — drives the
    /// Redownload action, the Android-parity gap closer.
    pub file_missing: bool,
    pub segments: Vec<SegmentDto>,
}

impl From<&Task> for TaskDto {
    fn from(t: &Task) -> Self {
        TaskDto {
            id: t.id,
            url: t.url.clone(),
            name: t.name.clone(),
            state: match t.state {
                DlState::Queued => "queued",
                DlState::Running => "running",
                DlState::Paused => "paused",
                DlState::Completed => "completed",
                DlState::Failed => "failed",
            }
            .to_string(),
            total: t.total,
            downloaded: t.downloaded(),
            fraction: t.fraction(),
            speed_bps: t.speed_bps,
            eta_secs: t.eta_secs(),
            error: t.error.clone(),
            // MAP-2: derived, never stored — a task is ready exactly
            // when it has a segment plan. No code path may gate start
            // or retry on this value.
            ready: !t.segments.is_empty(),
            file_missing: t.state == DlState::Completed
                && !crate::manager::downloads_dir().join(&t.name).exists(),
            segments: t.live_segments().iter().map(SegmentDto::from).collect(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadListDto {
    /// The manager's monotonic change stamp; the frontend discards any
    /// event/snapshot older than the newest stamp it has seen.
    pub stamp: u64,
    pub tasks: Vec<TaskDto>,
    pub active_count: usize,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BootstrapDto {
    pub app_version: String,
    pub settings: SettingsDto,
    pub sources: Vec<SourceDto>,
    pub favorites: Vec<String>,
    pub downloads: DownloadListDto,
    pub downloads_dir: String,
    pub data_dir: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceProbeDto {
    pub name: String,
    pub root_url: String,
    /// "up" | "down" | "disabled"
    pub status: String,
    pub latency_ms: Option<u64>,
}

// ------------------------------------------------------------ catalog

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CategoryDto {
    pub name: String,
    /// Names of the sources that carry this category.
    pub sources: Vec<String>,
}

impl From<&crate::catalog::Category> for CategoryDto {
    fn from(c: &crate::catalog::Category) -> Self {
        CategoryDto {
            name: c.name.clone(),
            sources: c.locations.iter().map(|(name, _)| name.clone()).collect(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TitleDto {
    pub folder_url: String,
    pub source_name: String,
    pub category: String,
    pub title: String,
    pub year: Option<i32>,
    /// "movie" | "series"
    pub kind: String,
    /// Remote poster URL (not a protocol src — the frontend batches
    /// these through `poster_srcs`), null when the folder has none.
    pub poster_url: Option<String>,
    pub language: Option<String>,
    pub season_count: usize,
    pub best_quality: Option<String>,
    pub copies: usize,
    pub is_favorite: bool,
}

impl TitleDto {
    pub fn new(t: &crate::catalog::Title, is_favorite: bool) -> Self {
        TitleDto {
            folder_url: t.folder_url.clone(),
            source_name: t.source_name.clone(),
            category: t.category.clone(),
            title: t.title.clone(),
            year: t.year,
            kind: t.kind.clone(),
            poster_url: t.poster_url.clone(),
            language: t.language.clone(),
            season_count: t.season_count,
            best_quality: t.best_quality.clone(),
            copies: t.copies,
            is_favorite,
        }
    }
}

/// One page of a feed. `generation` identifies the feed the page
/// belongs to; `superseded` is true when the caller's generation was
/// already replaced by a newer feed_open (the page is then empty and
/// must be dropped, never rendered).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedPageDto {
    pub generation: u64,
    pub titles: Vec<TitleDto>,
    pub has_more: bool,
    pub superseded: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileDto {
    pub file_name: String,
    pub file_url: String,
    /// Bytes; -1 when the server did not report a size.
    pub size: i64,
    pub quality: Option<String>,
    pub source_tag: Option<String>,
    pub codec: Option<String>,
    pub audio: Option<String>,
    pub season: Option<i32>,
    pub episode: Option<i32>,
    /// Download state for this exact file URL when a task exists:
    /// "queued" | "running" | "paused" | "completed" | "failed".
    pub download_state: Option<String>,
    pub download_fraction: Option<f64>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TitleDetailDto {
    pub title: TitleDto,
    pub files: Vec<FileDto>,
}

impl FileDto {
    pub fn new(
        f: &crate::catalog::CatalogFile,
        task: Option<(crate::manager::DlState, f64)>,
    ) -> Self {
        let (download_state, download_fraction) = match task {
            Some((state, fraction)) => (
                Some(
                    match state {
                        crate::manager::DlState::Queued => "queued",
                        crate::manager::DlState::Running => "running",
                        crate::manager::DlState::Paused => "paused",
                        crate::manager::DlState::Completed => "completed",
                        crate::manager::DlState::Failed => "failed",
                    }
                    .to_string(),
                ),
                Some(fraction),
            ),
            None => (None, None),
        };
        FileDto {
            file_name: f.file_name.clone(),
            file_url: f.file_url.clone(),
            size: f.size,
            quality: f.quality.clone(),
            source_tag: f.source_tag.clone(),
            codec: f.codec.clone(),
            audio: f.audio.clone(),
            season: f.season,
            episode: f.episode,
            download_state,
            download_fraction,
        }
    }
}
