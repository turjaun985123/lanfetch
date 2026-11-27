# LanFetch Desktop — Changelog

## 2.0.0 — the V2 transformation

See the root `CHANGELOG.md` for the full 2.0.0 entry (download-engine
lifecycle, authenticated LAN web mode, /api/v1, frontend modules,
security hardening). This file remains the record of the 1.6.0 RC line.


## 1.6.0 (release candidate) — the Tauri rebuild

The Rune UI is gone. Same Rust engine (catalog, downloads,
posters — carried over untouched, tests and all), new shell:
Tauri 2 + Astro + Tailwind rendering through the system's own
webview. The three failures that ended the Rune build shaped the
architecture:

- **Category clicks could latch shut.** Selection now lives in
  the frontend and every load carries a generation number — a
  click takes effect the same frame, and a superseded load can
  never paint over a newer one, whatever the servers are doing.
- **Later launches fetched nothing.** Catalog state is a server-
  side session with an explicit lifecycle, not per-launch UI
  state; refresh rediscovers instead of reusing a dead session.
- **Slower than the phone.** Server work is parallel (one thread
  per source for probes, paged progressive feed), posters stream
  through a custom protocol from the disk cache — never base64
  over IPC — and download progress arrives as pushed events,
  not polling.

Two editions ship from one codebase, sharing one engine crate:
the desktop app (this .deb), and **lanfetch-web** — a single
self-contained binary with the UI embedded and no GUI libraries,
for running headless and driving from any browser
(`lanfetch-web --lan`, then open the printed address).

Everything the Rune build had works here: poster grid with
search and favorites, season-grouped detail with per-file
quality, live transfers with speed and ETA, pause/resume per
file or all at once, redownload when a finished file goes
missing, server management (add / remove / enable), engine
settings that apply immediately, first-run onboarding, and an
update check. Your settings, favorites, downloads and poster
cache carry over in place — this release installs as the same
`lanfetch` package.

## 1.5.0 (release candidate)

The fix release. Everything below came out of running 1.4.0 on a
real machine and auditing the result end to end.

### Fixed
- **Posters never loaded.** A queue rewrite in 1.2.0 marked every
  poster as already in flight before dispatching it, so no poster
  was ever fetched. The poster queue now owns its lifecycle, and a
  regression test pins the enqueue→dispatch contract.
- **Search results had no posters** — the breadth search path never
  resolved folder posters; it does now, using the same picker as
  browsing (including oddly named poster files the servers ship).
- **Poisoned poster cache.** HTML error pages served for missing
  posters were cached as images forever. Cache entries are now
  validated by magic bytes on read and on write, and undecodable
  images are demoted to a failure tile instead of spinning forever.
- **Transfers looked frozen.** Download progress updated internally
  but never told the UI to repaint. Progress, pause, completion and
  failure now repaint live, with smoothed speeds and an ETA
  ("~1:23 left") on running transfers; paused cards show bytes left.
- **Ticker leaks.** A download's speed ticker could outlive the
  download and stack duplicates on resume; it now stops with its
  engine and is swap-guarded.
- **One dead server stalled everything.** Listing failures are now
  cooled down per host for 45 s (not per folder URL), and only for
  transport failures — a 404 for one deleted folder no longer takes
  the whole server offline.
- **Search could hang indefinitely** on one slow source. Search now
  has a 20 s deadline and returns the results that did arrive.
- **Unbounded caches.** The in-memory poster cache is capped at 150
  images (LRU eviction) and the on-disk poster cache at ~200 MB,
  oldest first.

### Changed
- **Infinite scroll.** The grid loads the next page automatically at
  the bottom (with skeleton placeholders); the Load more button
  remains as a fallback.
- **Catalog chrome rebuilt.** The multi-row category chip wall is
  one horizontally scrolling strip (it doubles as the search-scope
  selector); kind, Favorites, sort and filters live on one toolbar
  row; the quality row is collapsed behind a Filters toggle. The
  library header shrinks from ~48% of the window to two rows.
- **Duplicate folders merge.** The same title served by several
  servers (or under several categories) is one card with a ×N
  badge; its file list is the union of all copies, and it counts as
  a favorite if any copy is.
- **Download state shows in the Library.** Cards and the detail
  view carry live badges (downloading %, queued, paused, failed,
  downloaded) without opening Transfers.
- **Responsive grid.** Column count follows the window width (3–7)
  and posters are true 2:3 at any size, not just at 1180 px.
- **Design system.** Buttons are uniformly 40 pt (chips stay 30 pt
  pills); card titles are headline size with two-line wrap; loading
  posters pulse; fallback tiles have a visible border; the nav rail
  is slimmer and shows live per-server status and active downloads;
  the detail header no longer repeats the title.
- **Settings diagnostics.** The Storage card shows live poster
  counters (dispatched / loaded / failed / on screen) and can clear
  the poster cache.
- The app no longer describes its own implementation stack in the
  UI (rail footer, Settings About).

## 1.4.0

- Scoped search: filter global search results to one or more
  categories with a multi-select "Search in" row.

## 1.3.0

- Reliability: atomic persistence with .bak fallback, download
  engine hardening (clamped reads, bounded retries, honest
  failures), UTF-8-safe filename truncation.
- Speed: parallel feed fetching, 45 s dead-server cooldown, poster
  disk cache, 4 parallel poster workers, decode at display size.
- Netflix-style pass: boxless poster cards, skeleton loading, real
  empty states, hover/press states, keyboard shortcuts.
- Release engineering: Ubuntu 24.04 build (glibc 2.39 floor),
  declared runtime dependencies, tag-to-release automation.

## 1.2.0

- Android parity: live category catalog, metadata parser, global
  search with filters and sorts, title detail with quality choice,
  favorites, Bangla UI, first-run network check, update checker.

## 1.1.0

- Android "Lanes" design system; real download scheduler; settings
  and sources persistence.

## 1.0.0

- First desktop release: segmented download engine, server probes,
  transfers view.
