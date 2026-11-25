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
pub fn write_atomic(path: &std::path::Path, contents: &str) {
    let mut tmp_os = path.as_os_str().to_owned();
    tmp_os.push(".tmp");
    let tmp = PathBuf::from(tmp_os);
    if std::fs::write(&tmp, contents).is_err() {
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
    pub connections: u32,
    /// How many downloads may run at once; the rest wait as Queued.
    pub max_concurrent: u32,
    /// "system" | "light" | "dark" (Android also has AMOLED; the desktop
    /// dark palette already sits on near-black surfaces).
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

pub fn load_settings() -> Settings {
    read_json(&dir().join("settings.json")).unwrap_or_default()
}

pub fn save_settings(s: &Settings) {
    let _ = std::fs::create_dir_all(dir());
    if let Ok(json) = serde_json::to_string_pretty(s) {
        write_atomic(&dir().join("settings.json"), &json);
    }
}

// ----------------------------------------------------------------- sources

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceItem {
    pub name: String,
    pub root_url: String,
    pub enabled: bool,
    /// True for user-added servers (removable); seeds can only be disabled.
    pub custom: bool,
}

impl SourceItem {
    pub fn as_source(&self) -> Source {
        Source { name: self.name.clone(), root_url: self.root_url.clone() }
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
        .map(|s| SourceItem { name: s.name, root_url: s.root_url, enabled: true, custom: false })
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
    fn corrupt_main_falls_back_to_bak() {
        let path = temp_path("fallback");
        write_atomic(&path, "[\"good\"]");
        write_atomic(&path, "[\"newer\"]"); // snapshots "good" into .bak
        std::fs::write(&path, "{torn").unwrap(); // simulate a torn write
        assert_eq!(read_json::<Vec<String>>(&path).unwrap(), vec!["good".to_string()]);
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(bak_path(&path));
    }
}
