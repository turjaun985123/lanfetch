# LanFetch — Performance

> Status: staged draft for V2 Wave 9. Every number in this document is a
> design constant read from the code or the normative docs — not a
> measurement. Where something is measured, this document says by what,
> and what the measurement does **not** prove.

LanFetch's performance story is deliberately boring: a single-user app
talking to a handful of LAN file servers. There is no capacity planning
here because there is no capacity question — one user, one machine, a
few servers. What the design *does* guarantee is that no single slow,
stalled, or hostile server can wedge the app, and that the bounds are
written down as constants instead of emerging from accidents. Those
bounds are what this document records.

## 1. Engine design constants (the actual performance contract)

### Download engine (`desktop-tauri/engine/src/core.rs`, `manager.rs`)

| Constant | Value | Where | Why |
|---|---|---|---|
| Transfer read timeout | 5 s per read | `core.rs`, `transfer_agent()` | This number *is* the pause bound (below) |
| Worker chunk size | 256 KiB | `core.rs` (segment worker buffer) | Bounds pause latency on a healthy server to ~milliseconds (one chunk write) |
| Minimum segment size | 1 MiB (`MIN_SEGMENT = 1 << 20`) | `core.rs`, `plan_segments` | Segment count shrinks for small files instead of spawning useless parallel ranges; same policy as the Kotlin engine |
| Connections per task | 1–32, clamped on load and on write | `store.rs`, `Settings::clamped()` | A hand-edited or corrupt settings file cannot create an unbounded thread fan-out |
| Concurrent tasks | 1–16, clamped the same way | `store.rs`, `Settings::clamped()` | Same reason |
| Pause bound | ≈5 s worst case on a fully stalled server | `docs/DOWNLOAD-ENGINE.md` PA-3 | One in-flight read (5 s timeout) plus one chunk write. Pause is never documented as instantaneous, because it isn't |
| Progress checkpoint | ≤5 s tick while any task is running | `docs/DOWNLOAD-ENGINE.md` RC-1; `manager.rs` ticker | Crash loss is bounded to ≤5 s of transferred bytes (RC-2); saves are serialized and write the *live* segment snapshot |
| Completion durability | `fsync` (file `sync_all`) before the `.part` → final rename | `core.rs`; `docs/DOWNLOAD-ENGINE.md` CP-5 | A completed file is durable, not just closed |

### Catalog crawl (`desktop-tauri/engine/src/catalog.rs`, `core.rs`)

| Constant | Value | Where | Why |
|---|---|---|---|
| Feed page budget | 20 s per page (`PAGE_BUDGET`) | `catalog.rs`, `FeedSession::next_page` | A page returns what it has when the budget expires, with `has_more` honest — a slow server degrades the page, never hangs it |
| Listing read deadline | 30 s overall per listing (`LISTING_DEADLINE`) | `core.rs` | Per-read timeouts alone are defeated by a trickling server; the deadline is on the whole read |
| Listing size cap | 4 MiB (`MAX_LISTING_BYTES`) | `core.rs` | A hostile or broken server cannot OOM the app with an endless listing body; the h5ai search and update-check bodies go through the same capped reader |
| Catalog HTTP agent | 6 s connect, 30 s per-read, 30 s overall timeout | `core.rs`, `http_agent()` | The overall timeout is what the transfer agent deliberately lacks — a multi-GB download legitimately takes minutes; a listing never does |
| Listing cache | 600 s TTL, max 256 entries, oldest-half eviction | `catalog.rs` (`LISTING_TTL`, `LISTING_CACHE_MAX`) | Repeat category opens are free; memory stays bounded |
| Host failure cooldown | 45 s per host (`FAILURE_TTL`) | `catalog.rs` | A dead server is not re-hammered on every click; only transport failures cool a host down — HTTP statuses mean "reachable" |
| Feed crawl shape | DFS, batches of 8 folders, max depth 4, newest first | `catalog.rs` (`BATCH`, `MAX_DEPTH`) | Bounded fan-out per page; matches the Android crawler's shape |
| Search deadline | 20 s (`SEARCH_DEADLINE`) | `catalog.rs` | A search returns partial results rather than hanging |

### Posters (`desktop-tauri/engine/src/catalog.rs`, `poster_service.rs`)

| Constant | Value | Where |
|---|---|---|
| Single poster fetch cap | 2,000,000 bytes | `catalog.rs`, `fetch_poster_bytes` |
| On-disk poster cache ceiling | 200,000,000 bytes (~200 MB) | `catalog.rs` (`POSTER_CACHE_CAP`); `poster_service.rs` |
| Poster validity | Magic-byte sniff (JPEG/PNG/GIF/BMP/WebP), never the server's `Content-Type` | `catalog.rs`, `is_image_bytes` |

### Web server (`desktop-tauri/web/src/serve.rs`)

| Constant | Value | Where | Why |
|---|---|---|---|
| Default port | 8787 (`--port` to change, `--lan` for other devices) | `serve.rs` module doc / `main.rs` | — |
| Connection cap | 32 concurrent (`MAX_CONN`), excess gets 503 | `serve.rs` | One dribbling client cannot exhaust the server; the cap is enforced before auth work |
| SSE subscriber cap | 8 (`MAX_SSE`) | `serve.rs` | Event streams are the expensive connection; subscribers unregister on disconnect |
| Socket timeouts | 30 s read / 30 s write; whole head 30 s; whole body 30 s | `serve.rs`, `read_request` | Slow-loris clients (head *or* body) are cut off in bounded time |
| SSE keepalive | 20 s | `serve.rs`, `event_loop` | Dead streams are detected; the read timeout is lifted only for SSE |

### Frontend (`desktop-tauri/src/`)

- Download-progress DOM updates **patch keyed rows in place**; a full
  list re-render on a progress tick is a defect
  (`docs/ENGINEERING-STANDARDS.md` §3, `desktop-tauri/DESIGN.md`
  "Transfers"). Progress ticks are coalesced to ≤1/sec and segmented
  fills patch without transition (DESIGN.md, R2 arbitrations).
- Posters are the only images; they load lazily through the poster
  service and its bounded cache (above), not by hot-linking servers.

## 2. What CI measures — and what it does not

CI (`.github/workflows/desktop-tauri.yml`) runs, on every push to
`main` and on every `desktop-tauri-v*` tag:

- `cargo fmt --check`, `cargo clippy -- -D warnings` (hard gates),
- the full Rust test suites (engine unit + integration tests against
  an in-process scripted HTTP server), Vitest pure-logic suites,
  `tsc` + the Astro build,
- static responsive guardrails (see RESPONSIVE-DESIGN.md).

Some integration tests assert **time bounds against scripted hostile
servers** — e.g. a feed page against stalling listings must return
inside its budget, and pause during a stalled read must land inside
its bound. These are regression tripwires with generous margins, run
on shared CI runners.

**Plainly:** CI numbers are *indicative only*. Runner load varies,
margins are set wide on purpose, and no CI result is a benchmark.
There are **no published throughput figures** for LanFetch — no
MB/s claim, no "downloads N files in M seconds" claim — because
nothing in the pipeline measures one under controlled conditions,
and real-world speed is dominated by the user's LAN servers, not by
this code.

**Not measured anywhere:**

- Sustained download throughput on real hardware against real
  DhakaFlix-class servers (the VM that builds LanFetch cannot even
  reach the user's ISP-network servers).
- Memory footprint under a large catalog (thousands of titles) —
  bounded by the caps above, not profiled.
- Startup time, UI frame rates, poster-decode cost.
- ARM binaries' runtime behavior (the CI machines that build them
  are not the user's devices; see RELEASE-CHECKLIST.md).

## 3. Human-side checks

Performance truth that CI cannot supply is checked by a person, on
the release candidate, per RELEASE-CHECKLIST.md:

- The x86_64 web binary is smoke-run and curled (it must answer).
- On the user's own PC/phone, the RC is used against the real
  servers: category opens feel instant (cache), a stalled server
  shows up as a bounded pause (≤≈5 s), and the catalog no longer
  wedges when switching categories mid-crawl — the three behaviors
  the constants above exist to guarantee.
- The RESPONSIVE-QA.md pass (11 viewports × 2 themes) doubles as the
  UI-smoothness check: progress bars move without rows jumping, and
  the theme switch flips without a cross-fade.

If any of those feel wrong on real hardware, that is a bug report
against the constants in §1 — not a tuning suggestion.
