//! Persisted app state beyond downloads: user settings and the server
//! list (seeds + custom servers, with enabled flags), mirroring the
//! Android app's SettingsStore / SourceRepository. Files live next to the
//! download state in ~/.local/share/lanfetch/.

use crate::core::{seed_sources, Source};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

fn dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".local/share/lanfetch")
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
    let path = dir().join("settings.json");
    std::fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

pub fn save_settings(s: &Settings) {
    let _ = std::fs::create_dir_all(dir());
    if let Ok(json) = serde_json::to_string_pretty(s) {
        let _ = std::fs::write(dir().join("settings.json"), json);
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
    if let Ok(raw) = std::fs::read_to_string(&path) {
        if let Ok(items) = serde_json::from_str::<Vec<SourceItem>>(&raw) {
            if !items.is_empty() {
                return items;
            }
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
        let _ = std::fs::write(dir().join("sources.json"), json);
    }
}

// --------------------------------------------------------------- favorites

/// Favorite title folder URLs (Android: favorites table in Room).
pub fn load_favorites() -> Vec<String> {
    let path = dir().join("favorites.json");
    std::fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

pub fn save_favorites(urls: &[String]) {
    let _ = std::fs::create_dir_all(dir());
    if let Ok(json) = serde_json::to_string_pretty(urls) {
        let _ = std::fs::write(dir().join("favorites.json"), json);
    }
}
