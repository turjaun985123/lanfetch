//! The LanFetch engine: catalog browsing, metadata, stores and the
//! segmented download manager. Pure Rust, no GUI dependencies —
//! shared verbatim by the Tauri desktop app and the standalone
//! web binary, which is what lets the web build be a single
//! self-contained executable.

pub mod catalog;
pub mod core;
pub mod dto;
pub mod manager;
pub mod meta;
pub mod poster_service;
pub mod state;
pub mod store;
