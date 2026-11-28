# Changelog

All notable changes to LanFetch are recorded here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Library filter rebuilt to Android APK parity (2026-11-28): the filter
  model, semantics and sorts now match the app's CatalogFilter /
  TitleFilterEngine — multi-select facet sets for type, quality,
  language, server and release tag (OR within a dimension, AND across),
  an inclusive year range, the APK's minimum-size presets
  (700 MB / 1.5 GB / 4 GB), a dual-audio toggle, and its five sorts
  (Newest = feed order, Title, Year, Size largest/smallest). The engine
  now parses each listing row's modification stamp, orders crawl
  siblings by container year then recency, expands the newest year
  container before fetching older ones, and carries the folder stamp
  plus source tag, largest-file size and dual-audio flag on every
  title — the pieces whose absence buried the newest (2026) series
  on desktop while the APK showed them.

### Fixed

- Audit remediation (2026-11-28, external end-to-end audit): a finished
  download can no longer overwrite an existing file in Downloads — names
  are chosen against the filesystem as well as the task list, and
  completion lands through an atomic no-overwrite link, falling back to a
  counter name; a 206 response whose `Content-Range` total disagrees with
  the probed size is now rejected, so bytes from two versions of a file
  can never mix into a "completed" download; transient HTTP statuses
  (429/500/502/503/504) retry with backoff and honor `Retry-After`
  instead of failing the download outright; poster images are
  token-gated in `--lan` mode like every other read; the web pairing
  token survives storage-disabled browsers via an in-memory copy;
  Library/Detail download badges resolve to the live task when a URL
  was downloaded more than once; the theme/sidebar pre-paint scripts
  are allow-listed in both CSPs by hash, ending the first-paint flash.

## [2.0.0] - TBD

### Added

- A versioned web API under `/api/v1`, alongside the frozen legacy
  `POST /api/<cmd>` surface. It uses the same engine operations and DTOs as
  Tauri IPC and legacy HTTP, returns structured errors, and adds a public
  `GET /api/v1/health` route.
- Token authentication for the web edition when it is exposed with `--lan`.
  The token is generated per install, stored owner-only as `web-token`, and
  can be viewed, copied, and regenerated from Settings.
- An internal download lifecycle with distinct probing, ready, pausing,
  retrying, and verifying phases, mapped to the existing five public states:
  `queued`, `running`, `paused`, `completed`, and `failed`.
- Periodic download-progress checkpoints at intervals of no more than five
  seconds while a task is running.
- Rust integration tests for probing, segmented transfer, pause and resume,
  recovery, reconciliation, redirects, and failure cases, plus Vitest suites
  for the extracted frontend modules.

### Changed

- The desktop/web frontend was split from the single Astro page into focused
  modules for Transfers, Servers, Settings, Library, and Detail. Screen state,
  mount/dispose wiring, and pure helper logic now live with their screens;
  `api.ts` remains the only frontend transport seam.
- Catalog feed work no longer holds the catalog lock while crawling. Feed
  pages and listing reads are time-bounded, preventing one slow category or
  server from wedging the rest of the catalog and the web UI.
- The transfer path uses a five-second read timeout, giving pause a bounded
  worst case of about five seconds on a stalled server instead of about
  thirty seconds.
- Download-state saves are serialized and use a unique temporary file per
  write. Persisted settings and segment plans are validated and clamped on
  load instead of being trusted as written.
- When two downloads would otherwise use the same file name, the later task
  receives a browser-style numbered name such as `movie (2).mkv`; tasks no
  longer share one `.part` file merely because their names match.

### Fixed

- A download whose first probe failed can now be retried. Readiness is
  derived from the presence of a segment plan rather than stored as a gate
  that could permanently strand the task.
- Engine failures no longer leave one-way progress flags set, which had made
  some failed tasks impossible to resume.
- Pause requests made during a re-probe are honoured, and resuming while a
  pause is still unwinding no longer loses the resume request.
- A `.part` file that was deleted, truncated, or left shorter than the saved
  progress while LanFetch was closed is reconciled before resume or
  completion; the affected ranges are downloaded again rather than treated
  as present.
- Engine panics in the download supervisor, probe path, and progress ticker
  are contained and converted into a task failure instead of leaving a task
  stuck in a non-terminal state.
- The Library's load-more path can no longer clear another operation's
  loading state and admit out-of-order pages during a category switch.
- Android's segment and single-stream retry budgets are no longer reset by
  any amount of progress: an attempt now resets the consecutive-failure
  counter only after delivering at least 256 KiB. A server that repeatedly
  sends a few bytes and drops the connection can no longer keep a download
  alive indefinitely.

### Security

- The `--lan` web mode now requires a bearer token for every `/api/*` route
  except `GET /api/v1/health`. Loopback mode remains tokenless but validates
  the `Host` header against local names and addresses.
- The web server caps concurrent connections and SSE subscribers, applies
  head and body deadlines, requires JSON content types for API mutations,
  and sends `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  cache controls for API JSON, and a content-security policy for HTML.
- Web bootstrap responses no longer disclose the host's absolute downloads
  and data-directory paths.
- Engine redirects are followed manually under a strict policy: at most five
  hops, no redirect to a different host, and no HTTPS-to-HTTP downgrade.
  Range and validator headers are re-sent only on permitted same-host hops.
- Listing entries are confined to the origin of the server that supplied
  them. Cross-origin and scheme-relative hrefs are dropped rather than
  allowing a listed server to steer the crawler, poster fetcher, or
  downloader to another address.
- The listing parser no longer slices text using offsets from a lowercased
  copy, which could panic on characters whose lowercase form has a different
  byte length, including U+0130. The fallback row window also stays on a
  character boundary.
- h5ai search and update-check response bodies are read under the same size
  cap and deadline as directory listings instead of being loaded without a
  bound.
- Every `206` segment response must carry a `Content-Range` matching the
  requested range before any of its bytes are written. Completed files are
  flushed and synchronised before the `.part` file is renamed into place.

## [1.6.0-rc6] - 2026-11-27

This was the final 1.6.0 release candidate. `1.6.0-rc1` through
`1.6.0-rc5` were superseded by this release and are not listed separately.

### Added

- A complete desktop/web UI redesign based on `desktop-tauri/DESIGN.md`:
  quiet, neutral chrome; a single indigo accent; poster artwork as the
  principal colour; and a shared token system in `src/styles/global.css`.
- One responsive navigation element for all layouts: a bottom bar on phone
  widths, a compact icon rail on tablet widths, and a collapsible expanded
  rail on desktop widths.
- A human responsive-check matrix covering eleven viewports in light and
  dark themes.

### Changed

- Library cards are real buttons with poster fallbacks, explicit loading and
  empty states, and keyboard access.
- The Detail screen uses a poster-wash hero, metadata chips, complete file
  rows, and an in-place Resume action for paused files.
- Transfers rows are keyed by task and patched in place, with segmented
  progress bars derived from the task's segment data.
- Destructive actions use the native confirmation dialog, toasts report
  completed actions, and a visually hidden announcer reports only completed
  and failed download transitions.
- The redesign preserved the existing Tauri commands, DTOs, element IDs,
  keyboard shortcuts, and generation/stamp rules used to reject stale work.

### Fixed

- Dark-mode filled accent controls now use the `--on-accent` foreground.
  White text on the dark accent measured 2.60:1; the corrected pairing
  measures 7.23:1.
