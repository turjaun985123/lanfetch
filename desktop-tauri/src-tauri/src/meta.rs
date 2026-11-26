//! Release-name metadata parsing + crawl rules, ported from the Android
//! app's MetaParser.kt and CrawlRules.kt — the same grammar LanFlix used:
//! "Movie.Name.2023.1080p.WEB-DL.x265.Dual.Audio.mkv" and friends.
//!
//! One adaptation: the Kotlin original uses regex lookbehind for token
//! boundaries (audit B13); Rust's regex engine has no lookaround, so those
//! spots use an explicit boundary check (`contains_token`) instead.

use regex::Regex;
use std::sync::LazyLock;

// ------------------------------------------------------------------- rules

const JUNK_NAMES: &[&str] = &[
    "sample",
    "samples",
    "trailer",
    "trailers",
    "proof",
    "proofs",
    "subs",
    "subtitles",
    "subtitle",
    "screenshot",
    "screenshots",
    "cover",
    "covers",
    "poster",
    "posters",
    "artwork",
    "extras",
    "@eadir",
    "#recycle",
    "#snapshot",
    ".trash",
    "deleted",
    "sample video",
    "sample videos",
    "behind the scenes",
];

const NON_MEDIA_CATEGORIES: &[&str] = &[
    "android games",
    "console games",
    "pc games",
    "software",
    "games",
];

static SEASON_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^(?:season[\s._-]*(?:\d{1,2}(?:\.\d+)?|all)\b.*|s\d{1,2}\b(?:\s*[-&]\s*s?\d{1,2}\b)?(?:\s*[\(\[-].*)?|complete[\s._-]*series\b.*|disc[\s._-]*\d{1,2}\b.*|vol(?:ume)?[\s._-]*\d{1,2}\b.*)$").unwrap()
});

pub fn is_junk_folder(name: &str) -> bool {
    let n = name.trim().to_lowercase();
    JUNK_NAMES.contains(&n.as_str()) || name.starts_with('.')
}

pub fn is_non_media_category(name: &str) -> bool {
    NON_MEDIA_CATEGORIES.contains(&name.trim().to_lowercase().as_str())
}

pub fn is_season_like(name: &str) -> bool {
    SEASON_RE.is_match(name.trim())
}

// ------------------------------------------------------------------- meta

static YEAR_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\b(19\d\d|20\d\d)\b").unwrap());
static BRACKET_YEAR_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[\[(]((?:19|20)\d{2})[\])]").unwrap());
static SE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[Ss](\d{1,2})[Ee](\d{1,3})").unwrap());
static EP_ONLY_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)\b(?:ep(?:isode)?|e)[ ._-]?(\d{1,3})\b").unwrap());
static SEASON_DIR_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)(?:season|s)\s*(\d{1,2})\b").unwrap());
static BRACKET_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\[[^\]]*\]|\([^)]*\)").unwrap());
static CUT_MARKER_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\b(?:2160p|1080p|720p|480p|360p|hdcam|hdts|bluray|bdrip|brrip|web[ ._-]?dl|webrip|hdrip|dvdrip|remux|hdtv|x264|x265|h[ .]?264|h[ .]?265|hevc|av1|dual[ ._-]?audio|multi[ ._-]?audio|dubbed|aac|ac3|eac3|dts|proper|repack)\b").unwrap()
});
static SEASON_SUFFIX_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\b(?:season\s*\d{1,2}|s\d{1,2}|complete\s+series|disc\s*\d{1,2}|vol(?:ume)?\s*\d{1,2})\b.*$").unwrap()
});
static TRAILING_YEAR_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\b(19\d\d|20\d\d)\s*$").unwrap());
static WS_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());

pub const VIDEO_EXTENSIONS: &[&str] = &[
    "mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "ts", "flv", "mpg", "mpeg", "3gp",
];

pub fn is_video_file(name: &str) -> bool {
    let ext = name.rsplit('.').next().unwrap_or("").to_lowercase();
    name.contains('.') && VIDEO_EXTENSIONS.contains(&ext.as_str())
}

/// True when `token` occurs in `hay` (already lowercase) not glued to
/// other word characters — the B13 boundary rule without lookaround.
/// `_` counts as a word character, exactly like Kotlin's `\b`: in
/// "HD_CAM" the CAM is glued, not a token (audit D7).
fn contains_token(hay: &str, token: &str) -> bool {
    fn word_byte(b: u8) -> bool {
        b.is_ascii_alphanumeric() || b == b'_'
    }
    let bytes = hay.as_bytes();
    let mut from = 0;
    while let Some(pos) = hay[from..].find(token) {
        let start = from + pos;
        let end = start + token.len();
        let before_ok = start == 0 || !word_byte(bytes[start - 1]);
        let after_ok = end >= bytes.len() || !word_byte(bytes[end]);
        if before_ok && after_ok {
            return true;
        }
        from = start + 1;
    }
    false
}

pub fn parse_quality(text: &str) -> Option<String> {
    let t = text.to_lowercase();
    if t.contains("2160p") || contains_token(&t, "4k") || contains_token(&t, "uhd") {
        Some("2160p".into())
    } else if t.contains("1080p") {
        Some("1080p".into())
    } else if t.contains("720p") {
        Some("720p".into())
    } else if t.contains("480p") {
        Some("480p".into())
    } else if t.contains("360p") {
        Some("360p".into())
    } else if t.contains("hdcam") || contains_token(&t, "cam") || t.contains("hdts") {
        Some("CAM".into())
    } else {
        None
    }
}

pub fn parse_source_tag(text: &str) -> Option<String> {
    let t = text.to_lowercase().replace(['-', '.'], "");
    if t.contains("remux") {
        Some("REMUX".into())
    } else if t.contains("bluray") || t.contains("bdrip") || t.contains("brrip") {
        Some("BluRay".into())
    } else if t.contains("webdl") {
        Some("WEB-DL".into())
    } else if t.contains("webrip") {
        Some("WEBRip".into())
    } else if t.contains("hdrip") {
        Some("HDRip".into())
    } else if t.contains("dvdrip") {
        Some("DVDRip".into())
    } else if t.contains("hdtv") {
        Some("HDTV".into())
    } else {
        None
    }
}

pub fn parse_codec(text: &str) -> Option<String> {
    let t = text.to_lowercase();
    if t.contains("x265") || t.contains("h265") || t.contains("h.265") || t.contains("hevc") {
        Some("x265".into())
    } else if t.contains("x264")
        || t.contains("h264")
        || t.contains("h.264")
        || contains_token(&t, "avc")
    {
        Some("x264".into())
    } else if contains_token(&t, "av1") {
        Some("AV1".into())
    } else {
        None
    }
}

pub fn parse_audio(text: &str) -> Option<String> {
    let t = text.to_lowercase();
    if t.contains("multi audio") || t.contains("multi-audio") {
        Some("Multi Audio".into())
    } else if t.contains("dual audio") || t.contains("dual-audio") {
        Some("Dual Audio".into())
    } else if t.contains("dubbed") || t.contains("dubbing") {
        Some("Dubbed".into())
    } else {
        None
    }
}

const KNOWN_LANGUAGES: &[&str] = &[
    "Bangla",
    "Bengali",
    "Hindi",
    "English",
    "Korean",
    "Japanese",
    "Chinese",
    "Tamil",
    "Telugu",
    "Malayalam",
    "Kannada",
    "Punjabi",
    "Urdu",
    "Arabic",
    "French",
    "German",
    "Spanish",
    "Italian",
    "Russian",
    "Turkish",
    "Thai",
    "Indonesian",
    "Brazilian",
    "Danish",
    "Dutch",
    "Norwegian",
    "Swedish",
    "Polish",
    "Iranian",
    "Pakistani",
];

pub fn parse_language(category_path: &str) -> Option<String> {
    let hay = category_path.to_lowercase();
    KNOWN_LANGUAGES
        .iter()
        .find(|l| hay.contains(&l.to_lowercase()))
        .map(|l| l.to_string())
}

/// Bracketed year wins; otherwise the last year in the name.
pub fn parse_year(text: &str) -> Option<i32> {
    if let Some(m) = BRACKET_YEAR_RE.find(text) {
        let inner = &text[m.start()..m.end()];
        let digits: String = inner.chars().filter(|c| c.is_ascii_digit()).collect();
        if let Ok(y) = digits.parse() {
            return Some(y);
        }
    }
    YEAR_RE
        .find_iter(text)
        .last()
        .and_then(|m| m.as_str().parse().ok())
}

pub fn parse_season(text: &str) -> Option<i32> {
    SEASON_DIR_RE.captures(text).and_then(|c| c[1].parse().ok())
}

pub fn clean_title(raw: &str) -> String {
    let mut t = if is_video_file(raw) {
        raw.rsplit_once('.')
            .map(|(a, _)| a.to_string())
            .unwrap_or_else(|| raw.to_string())
    } else {
        raw.to_string()
    };
    t = BRACKET_RE.replace_all(&t, " ").to_string();
    t = t.replace(['.', '_', '-'], " ");
    if let Some(m) = CUT_MARKER_RE.find(&t) {
        t = t[..m.start()].to_string();
    }
    t = SEASON_SUFFIX_RE.replace(&t, " ").to_string();
    t = TRAILING_YEAR_RE.replace(&t, "").to_string();
    let out = WS_RE.replace_all(&t, " ").trim().to_string();
    if out.is_empty() {
        raw.trim().to_string()
    } else {
        out
    }
}

pub fn season_episode(title_name: &str, file_name: &str) -> (Option<i32>, Option<i32>) {
    let se = SE_RE
        .captures(file_name)
        .or_else(|| SE_RE.captures(title_name));
    (
        se.as_ref().and_then(|c| c[1].parse().ok()),
        se.and_then(|c| c[2].parse().ok()),
    )
}

pub fn episode_only(file_name: &str) -> Option<i32> {
    EP_ONLY_RE
        .captures(file_name)
        .and_then(|c| c[1].parse().ok())
}

/// Poster choice inside a folder listing (PosterPicker.kt): poster >
/// folder/cover > fanart > backdrop > any image.
pub fn pick_poster(entries: &[crate::core::DirEntry]) -> Option<String> {
    const IMAGE_EXTS: &[&str] = &["jpg", "jpeg", "png", "webp", "gif", "bmp"];
    fn score(name: &str) -> i32 {
        let n = name.to_lowercase();
        if n.contains("poster") {
            5
        } else if n.contains("folder") || n.contains("cover") {
            4
        } else if n.contains("fanart") {
            3
        } else if n.contains("backdrop") {
            2
        } else {
            1
        }
    }
    entries
        .iter()
        .filter(|e| {
            !e.is_dir
                && e.name.contains('.')
                && IMAGE_EXTS.contains(
                    &e.name
                        .rsplit('.')
                        .next()
                        .unwrap_or("")
                        .to_lowercase()
                        .as_str(),
                )
        })
        .max_by_key(|e| score(&e.name))
        .map(|e| e.url.clone())
}

// ------------------------------------------------------------------ tests

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean_title_strips_release_noise() {
        assert_eq!(
            clean_title("Movie.Name.2023.1080p.WEB-DL.x265.Dual.Audio.mkv"),
            "Movie Name"
        );
    }

    #[test]
    fn full_release_name_fields() {
        let n = "Movie.Name.2023.1080p.WEB-DL.x265.Dual.Audio.mkv";
        assert_eq!(parse_quality(n).as_deref(), Some("1080p"));
        assert_eq!(parse_source_tag(n).as_deref(), Some("WEB-DL"));
        assert_eq!(parse_codec(n).as_deref(), Some("x265"));
        assert_eq!(parse_year(n), Some(2023));
    }

    #[test]
    fn bracket_year_wins() {
        assert_eq!(parse_year("Wonder Woman 1984 (2020)"), Some(2020));
    }

    #[test]
    fn quality_token_boundaries_b13() {
        // "uhd" glued inside "uhdrip" is not a token (audit B13).
        assert_eq!(parse_quality("Sh4ke.uhdrip.x264"), None);
        assert_eq!(parse_codec("Sh4ke.uhdrip.x264").as_deref(), Some("x264"));
        assert_eq!(parse_quality("Film.4K.Remux").as_deref(), Some("2160p"));
    }

    #[test]
    fn underscore_is_a_word_character() {
        // Kotlin \b semantics (audit D7): CAM glued by '_' is no token.
        assert_eq!(parse_quality("Movie.HD_CAM.2020.mkv"), None);
        assert_eq!(parse_quality("Movie.CAM.2020.mkv").as_deref(), Some("CAM"));
    }

    #[test]
    fn season_like_shapes() {
        for yes in [
            "Season 1 (Hindi Language)",
            "Season ALL",
            "Season 1 (Part-1)",
            "S01-S02",
        ] {
            assert!(is_season_like(yes), "{yes}");
        }
        assert!(!is_season_like("Seasonal Greetings"));
    }

    #[test]
    fn junk_and_non_media() {
        assert!(is_junk_folder("Sample"));
        assert!(is_junk_folder(".trash"));
        assert!(!is_junk_folder("Movies"));
        assert!(is_non_media_category("PC Games"));
    }

    #[test]
    fn season_episode_parsing() {
        assert_eq!(
            season_episode("Show", "Show.S02E03.720p.mkv"),
            (Some(2), Some(3))
        );
        assert_eq!(episode_only("Ep 12 - Title.mkv"), Some(12));
    }

    #[test]
    fn audio_variants() {
        assert_eq!(
            parse_audio("Movie Dual Audio.mkv").as_deref(),
            Some("Dual Audio")
        );
        assert_eq!(
            parse_audio("Movie Hindi Dubbed.mkv").as_deref(),
            Some("Dubbed")
        );
    }
}
