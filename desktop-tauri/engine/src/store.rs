//! Persisted app state beyond downloads: user settings and the server
//! list (seeds + custom servers, with enabled flags), mirroring the
//! Android app's SettingsStore / SourceRepository. Files live next to the
//! download state in ~/.local/share/lanfetch/.
//!
//! All writes go through `write_atomic` (stage to temp, snapshot the
//! previous good file to `.bak`, rename over the target) and all reads
//! fall back to the `.bak` sibling: a crash or power loss mid-save must
//! never silently wipe the user's settings, servers or favorites.

use crate::core::{seed_sources, Source};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

fn dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".local/share/lanfetch")
}

// ------------------------------------------------------------- atomic io

/// Sibling backup path ("settings.json" → "settings.json.bak").
pub fn bak_path(path: &std::path::Path) -> PathBuf {
    let mut s = path.as_os_str().to_owned();
    s.push(".bak");
    PathBuf::from(s)
}

/// Crash-safe write: stage to a temp file, snapshot the current file to
/// `.bak`, then rename over the target (atomic on Linux).
///
/// V2 hardening (DOWNLOAD-ENGINE.md RC-1): the temp name is unique per
/// call — the pre-V2 single fixed `.tmp` path let two concurrent saves
/// of the same file interleave and tear each other's staging — and the
/// staged file is `sync_all`ed before the rename, so a power loss
/// cannot leave the renamed file hollow.
pub fn write_atomic(path: &std::path::Path, contents: &str) {
    use std::io::Write;
    use std::sync::atomic::{AtomicU64, Ordering};
    static TMP_COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = TMP_COUNTER.fetch_add(1, Ordering::SeqCst);
    let mut tmp_os = path.as_os_str().to_owned();
    tmp_os.push(format!(".tmp-{}-{n}", std::process::id()));
    let tmp = PathBuf::from(tmp_os);
    let staged = (|| -> std::io::Result<()> {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(contents.as_bytes())?;
        f.sync_all()
    })();
    if staged.is_err() {
        let _ = std::fs::remove_file(&tmp);
        return;
    }
    if path.exists() {
        let _ = std::fs::copy(path, bak_path(path));
    }
    let _ = std::fs::rename(&tmp, path);
}

/// Read + parse a JSON state file, falling back to its `.bak` sibling
/// when the main file is missing or corrupt.
fn read_json<T: serde::de::DeserializeOwned>(path: &std::path::Path) -> Option<T> {
    for candidate in [path.to_path_buf(), bak_path(path)] {
        if let Ok(raw) = std::fs::read_to_string(&candidate) {
            if let Ok(value) = serde_json::from_str(&raw) {
                return Some(value);
            }
        }
    }
    None
}

// ---------------------------------------------------------------- settings

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    /// Parallel segments per download (Android: "lanes"). Default 8.
    /// The serde default is load-bearing (V2 RC-5): without it, one
    /// missing field made the whole settings file unparseable and
    /// silently reset *every* setting.
    #[serde(default = "default_connections")]
    pub connections: u32,
    /// How many downloads may run at once; the rest wait as Queued.
    #[serde(default = "default_max_concurrent")]
    pub max_concurrent: u32,
    /// "system" | "light" | "dark" (Android also has AMOLED; the desktop
    /// dark palette already sits on near-black surfaces).
    #[serde(default = "default_theme")]
    pub theme: String,
    /// "system" | "en" | "bn" — UI language (Android: per-app locales).
    #[serde(default = "default_language")]
    pub language: String,
    /// First-run network check completed (Android: onboarding_done).
    #[serde(default)]
    pub onboarding_done: bool,
}

fn default_language() -> String {
    "system".to_string()
}

fn default_connections() -> u32 {
    8
}

fn default_max_concurrent() -> u32 {
    3
}

fn default_theme() -> String {
    "system".to_string()
}

fn default_true() -> bool {
    true
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            connections: 8,
            max_concurrent: 3,
            theme: "system".to_string(),
            language: "system".to_string(),
            onboarding_done: false,
        }
    }
}

impl Settings {
    /// The write-path bounds (API.md: connections 1–32,
    /// max_concurrent 1–16), applied on load as well as on write.
    /// A hand-edited or corrupt settings.json must not be able to
    /// spin the probe planner (connections = u32::MAX would plan
    /// ~4 billion segments) or silently remove the scheduler cap
    /// (audit slices 2 + 10).
    pub fn clamped(mut self) -> Self {
        self.connections = self.connections.clamp(1, 32);
        self.max_concurrent = self.max_concurrent.clamp(1, 16);
        self
    }
}

pub fn load_settings() -> Settings {
    read_json::<Settings>(&dir().join("settings.json"))
        .unwrap_or_default()
        .clamped()
}

pub fn save_settings(s: &Settings) {
    let _ = std::fs::create_dir_all(dir());
    if let Ok(json) = serde_json::to_string_pretty(s) {
        write_atomic(&dir().join("settings.json"), &json);
    }
}

// ------------------------------------------------------------- web token

/// The web API access token (ADR-003): 256-bit, hex-encoded, stored
/// as `web-token` in the data dir with owner-only permissions. The
/// web server requires it on every `/api/*` call when bound
/// non-loopback (`--lan`); loopback stays tokenless. Written directly
/// (not via `write_atomic`) so no `.bak` copy of the secret ever
/// lands on disk with looser permissions.
pub fn web_token_path() -> PathBuf {
    dir().join("web-token")
}

fn generate_token() -> String {
    let mut bytes = [0u8; 32];
    let filled = std::fs::File::open("/dev/urandom")
        .and_then(|mut f| {
            use std::io::Read;
            f.read_exact(&mut bytes)
        })
        .is_ok();
    if !filled {
        // Last-resort mixing if /dev/urandom is somehow unavailable
        // (no shipped target allows that): time + pid, never a
        // constant — a predictable token would be worse than none.
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        for (i, b) in bytes.iter_mut().enumerate() {
            *b = (now >> ((i % 8) * 8)) as u8
                ^ (std::process::id() as u8)
                    .wrapping_mul(31)
                    .wrapping_add(i as u8);
        }
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn read_token_at(path: &std::path::Path) -> Option<String> {
    let raw = std::fs::read_to_string(path).ok()?;
    let t = raw.trim().to_string();
    if t.len() >= 32 && t.bytes().all(|b| b.is_ascii_hexdigit()) {
        Some(t)
    } else {
        None
    }
}

fn write_token_at(path: &std::path::Path, token: &str) {
    use std::io::Write;
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    if let Ok(mut f) = opts.open(path) {
        let _ = f.write_all(token.as_bytes());
        let _ = f.sync_all();
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
}

fn load_or_create_token_at(path: &std::path::Path) -> String {
    if let Some(t) = read_token_at(path) {
        return t;
    }
    let token = generate_token();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    write_token_at(path, &token);
    token
}

/// Load the install's web token, generating and persisting one on
/// first run. Idempotent: an existing valid token is returned as-is.
pub fn load_or_create_web_token() -> String {
    load_or_create_token_at(&web_token_path())
}

/// Replace the web token, invalidating every paired browser.
pub fn regenerate_web_token() -> String {
    let token = generate_token();
    let _ = std::fs::create_dir_all(dir());
    write_token_at(&web_token_path(), &token);
    token
}

/// The current web token, if one has been created.
pub fn current_web_token() -> Option<String> {
    read_token_at(&web_token_path())
}

// ----------------------------------------------------------------- sources

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceItem {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub root_url: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// True for user-added servers (removable); seeds can only be disabled.
    #[serde(default)]
    pub custom: bool,
}

impl SourceItem {
    pub fn as_source(&self) -> Source {
        Source {
            name: self.name.clone(),
            root_url: self.root_url.clone(),
        }
    }
}

/// The stored server list; first run seeds the DHAKA-FLIX defaults.
pub fn load_sources() -> Vec<SourceItem> {
    let path = dir().join("sources.json");
    if let Some(items) = read_json::<Vec<SourceItem>>(&path) {
        if !items.is_empty() {
            return items;
        }
    }
    seed_sources()
        .into_iter()
        .map(|s| SourceItem {
            name: s.name,
            root_url: s.root_url,
            enabled: true,
            custom: false,
        })
        .collect()
}

pub fn save_sources(items: &[SourceItem]) {
    let _ = std::fs::create_dir_all(dir());
    if let Ok(json) = serde_json::to_string_pretty(items) {
        write_atomic(&dir().join("sources.json"), &json);
    }
}

// --------------------------------------------------------------- favorites

/// Favorite title folder URLs (Android: favorites table in Room).
pub fn load_favorites() -> Vec<String> {
    read_json(&dir().join("favorites.json")).unwrap_or_default()
}

pub fn save_favorites(urls: &[String]) {
    let _ = std::fs::create_dir_all(dir());
    if let Ok(json) = serde_json::to_string_pretty(urls) {
        write_atomic(&dir().join("favorites.json"), &json);
    }
}

// ------------------------------------------------------------------ tests

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!("lanfetch-test-{}-{tag}.json", std::process::id()))
    }

    #[test]
    fn atomic_write_round_trip() {
        let path = temp_path("roundtrip");
        write_atomic(&path, "[\"a\",\"b\"]");
        assert_eq!(
            read_json::<Vec<String>>(&path).unwrap(),
            vec!["a".to_string(), "b".to_string()]
        );
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(bak_path(&path));
    }

    #[test]
    fn settings_clamped_to_write_path_bounds() {
        // Audit slices 2 + 10: values from disk get the same
        // clamps the API write paths apply.
        let wild = Settings {
            connections: u32::MAX,
            max_concurrent: 0,
            ..Settings::default()
        }
        .clamped();
        assert_eq!(wild.connections, 32);
        assert_eq!(wild.max_concurrent, 1);
        let sane = Settings::default().clamped();
        assert_eq!(sane.connections, 8);
        assert_eq!(sane.max_concurrent, 3);
    }

    #[test]
    fn web_token_lifecycle() {
        let dir = std::env::temp_dir().join(format!("lanfetch-tok-{}", std::process::id()));
        let path = dir.join("web-token");
        let _ = std::fs::remove_dir_all(&dir);
        let first = load_or_create_token_at(&path);
        assert_eq!(first.len(), 64, "256-bit token, hex-encoded");
        assert!(first.bytes().all(|b| b.is_ascii_hexdigit()));
        // Idempotent: a second load returns the same token.
        assert_eq!(load_or_create_token_at(&path), first);
        // A corrupt file is replaced, never trusted.
        std::fs::write(&path, "not-a-token").unwrap();
        let healed = load_or_create_token_at(&path);
        assert_ne!(healed, first);
        assert_eq!(healed.len(), 64);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_main_falls_back_to_bak() {
        let path = temp_path("fallback");
        write_atomic(&path, "[\"good\"]");
        write_atomic(&path, "[\"newer\"]"); // snapshots "good" into .bak
        std::fs::write(&path, "{torn").unwrap(); // simulate a torn write
        assert_eq!(
            read_json::<Vec<String>>(&path).unwrap(),
            vec!["good".to_string()]
        );
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(bak_path(&path));
    }
}

/// A favorited title's display snapshot, persisted next to the
/// favorites URL list so the Favorites view can render after a
/// restart — the URL list alone cannot paint a poster grid.
/// Snapshots are taken when the favorite is toggled (always from
/// the detail screen, where the full Title is at hand).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FavTitle {
    #[serde(default)]
    pub folder_url: String,
    #[serde(default)]
    pub source_name: String,
    #[serde(default)]
    pub category: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub raw_name: String,
    #[serde(default)]
    pub year: Option<i32>,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub poster_url: Option<String>,
    #[serde(default)]
    pub language: Option<String>,
    #[serde(default)]
    pub season_count: usize,
    #[serde(default)]
    pub best_quality: Option<String>,
}

impl From<&crate::catalog::Title> for FavTitle {
    fn from(t: &crate::catalog::Title) -> Self {
        FavTitle {
            folder_url: t.folder_url.clone(),
            source_name: t.source_name.clone(),
            category: t.category.clone(),
            title: t.title.clone(),
            raw_name: t.raw_name.clone(),
            year: t.year,
            kind: t.kind.clone(),
            poster_url: t.poster_url.clone(),
            language: t.language.clone(),
            season_count: t.season_count,
            best_quality: t.best_quality.clone(),
        }
    }
}

pub fn load_favorite_titles() -> Vec<FavTitle> {
    read_json(&dir().join("favorite_titles.json")).unwrap_or_default()
}

pub fn save_favorite_titles(titles: &[FavTitle]) {
    let _ = std::fs::create_dir_all(dir());
    if let Ok(json) = serde_json::to_string_pretty(titles) {
        write_atomic(&dir().join("favorite_titles.json"), &json);
    }
}
