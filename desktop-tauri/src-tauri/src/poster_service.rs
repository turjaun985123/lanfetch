//! Poster delivery for the webview: a key registry plus the
//! `lanfetch-poster://` protocol handler.
//!
//! The Rune build decoded posters in-process and held them in a
//! bounded map; the webview changes the right shape completely.
//! Posters travel as ordinary image loads — the frontend receives a
//! protocol URL per poster (`poster_srcs` command) and the browser
//! engine does fetching, decoding and memory management. Bytes come
//! from the carried engine's disk cache (`catalog::fetch_poster_cached`:
//! ~200 MB cap, tmp+rename writes, poisoned entries self-heal), so a
//! poster fetched once is served from disk on every later launch.
//!
//! Two properties are deliberate, both learned from the Rune queue:
//! failures are NOT remembered (a 404 today can succeed after the
//! server recovers — the old queue's failed-forever state is exactly
//! what this design drops), and nothing poster-shaped ever crosses
//! IPC as data — only short URL strings do.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::catalog;

#[derive(Default)]
pub struct PosterService {
    /// Poster cache key (FNV-1a hex) -> remote poster URL. The key is
    /// one-way, so the protocol handler needs this map to fetch on a
    /// cache miss; entries are added when the frontend asks for srcs.
    registry: Mutex<HashMap<String, String>>,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl PosterService {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register remote poster URLs and return their protocol srcs,
    /// positionally aligned with the input. Registering the same URL
    /// twice is a no-op that returns the same src.
    pub fn register(&self, urls: &[String]) -> Vec<String> {
        let mut reg = lock(&self.registry);
        urls.iter()
            .map(|url| {
                let key = catalog::poster_key(url);
                reg.entry(key.clone()).or_insert_with(|| url.clone());
                format!("lanfetch-poster://localhost/{key}")
            })
            .collect()
    }

    /// The remote URL behind a protocol key, if it was registered.
    pub fn resolve(&self, key: &str) -> Option<String> {
        lock(&self.registry).get(key).cloned()
    }

    /// Bytes for a key: registry lookup, then the engine's cached
    /// fetch (disk first, network on miss). Err on unknown key or a
    /// failed/invalid fetch — the handler turns that into a 404 and
    /// the frontend falls back to its initials tile.
    pub fn bytes_for(&self, key: &str) -> Result<Vec<u8>, String> {
        let url = self.resolve(key).ok_or("unknown poster key")?;
        catalog::fetch_poster_cached(&url)
    }
}

/// Extract and validate the poster key from a protocol request URI
/// (`lanfetch-poster://localhost/<16 hex chars>`). Anything else is
/// not a poster request we serve.
pub fn parse_key(uri: &str) -> Option<String> {
    let after_scheme = uri.strip_prefix("lanfetch-poster://")?; // host/key
    let (_, rest) = after_scheme.split_once('/')?;
    let key = rest.split(['?', '#']).next()?;
    if !key.contains('/') && key.len() == 16 && key.bytes().all(|b| b.is_ascii_hexdigit()) {
        Some(key.to_ascii_lowercase())
    } else {
        None
    }
}

/// Content-Type from the same magic bytes the engine validates with.
pub fn content_type(bytes: &[u8]) -> &'static str {
    if bytes.starts_with(&[0xFF, 0xD8]) {
        "image/jpeg"
    } else if bytes.starts_with(&[0x89, 0x50, 0x4E, 0x47]) {
        "image/png"
    } else if bytes.starts_with(b"GIF8") {
        "image/gif"
    } else if bytes.starts_with(b"BM") {
        "image/bmp"
    } else if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        "image/webp"
    } else {
        "application/octet-stream"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn register_returns_aligned_srcs_and_dedupes() {
        let svc = PosterService::new();
        let urls = vec![
            "http://h/a.jpg".to_string(),
            "http://h/b.jpg".to_string(),
            "http://h/a.jpg".to_string(),
        ];
        let srcs = svc.register(&urls);
        assert_eq!(srcs.len(), 3);
        assert_eq!(srcs[0], srcs[2], "same URL, same src");
        assert_ne!(srcs[0], srcs[1]);
        assert!(srcs[0].starts_with("lanfetch-poster://localhost/"));
        // The src key round-trips back to the remote URL.
        let key = parse_key(&srcs[0]).expect("src carries a valid key");
        assert_eq!(svc.resolve(&key).as_deref(), Some("http://h/a.jpg"));
    }

    #[test]
    fn resolve_unknown_key_is_none() {
        let svc = PosterService::new();
        assert_eq!(svc.resolve("0123456789abcdef"), None);
        assert!(svc.bytes_for("0123456789abcdef").is_err());
    }

    #[test]
    fn parse_key_accepts_only_16_hex() {
        assert_eq!(
            parse_key("lanfetch-poster://localhost/0123456789abcdef"),
            Some("0123456789abcdef".to_string())
        );
        assert_eq!(
            parse_key("lanfetch-poster://localhost/0123456789ABCDEF"),
            Some("0123456789abcdef".to_string())
        );
        assert_eq!(parse_key("lanfetch-poster://localhost/xyz"), None);
        assert_eq!(parse_key("lanfetch-poster://localhost/"), None);
        assert_eq!(parse_key("http://localhost/0123456789abcdef"), None);
        assert_eq!(
            parse_key("lanfetch-poster://localhost/0123456789abcdef/extra"),
            None,
            "extra path segments are not silently accepted"
        );
    }

    #[test]
    fn content_type_sniffs_each_format() {
        assert_eq!(content_type(&[0xFF, 0xD8, 0xFF]), "image/jpeg");
        assert_eq!(content_type(&[0x89, 0x50, 0x4E, 0x47]), "image/png");
        assert_eq!(content_type(b"GIF89a"), "image/gif");
        assert_eq!(content_type(b"BMxx"), "image/bmp");
        assert_eq!(content_type(b"RIFF\0\0\0\0WEBP"), "image/webp");
        assert_eq!(content_type(b"<htm"), "application/octet-stream");
    }
}
