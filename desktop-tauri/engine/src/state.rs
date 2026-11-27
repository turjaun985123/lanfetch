//! Application state shared by both front doors (the Tauri
//! commands and the web server): one download manager, the
//! configured sources, the favorites list, the poster service and
//! the catalog browsing session.

use std::sync::{Arc, Mutex};

use crate::catalog::{Category, FeedSession, Title};
use crate::manager::Manager;
use crate::poster_service::PosterService;
use crate::store::SourceItem;

pub struct AppState {
    pub manager: Arc<Manager>,
    pub sources: Mutex<Vec<SourceItem>>,
    pub favorites: Mutex<Vec<String>>,
    pub posters: Arc<PosterService>,
    pub catalog: Arc<Mutex<CatalogState>>,
}

/// Catalog browsing state: the discovered categories plus the one
/// live feed session. Every feed_open bumps `generation`; pages from
/// an older generation are reported `superseded` and must be dropped
/// by the caller — the server-side half of the rule that a slow load
/// can never overwrite a newer screen (the Rune build latched onto
/// the first click instead, which is the bug this replaces).
#[derive(Default)]
pub struct CatalogState {
    pub categories: Vec<Category>,
    pub feed: Option<FeedSession>,
    pub generation: u64,
    /// Every title the feed has emitted, by folder URL — the detail
    /// command hydrates from here instead of making the frontend
    /// round-trip a whole Title it only knows as a DTO.
    pub titles: std::collections::HashMap<String, Title>,
}
