# LanFetch — Dependency Map

Companion to `docs/V2-ARCHITECTURE.md`. Records who depends on whom —
crates, engine modules, frontend modules, Android packages — plus the
external dependency census and the known duplication. Edges are taken
from actual imports, not intentions. Snapshot: v1.6.0 (RC6, `43ed283`).

## 1. Rust workspace (desktop-tauri/Cargo.toml)

Members: `engine`, `src-tauri`, `web`. Resolver 2; release profile is
workspace-level (opt-level 3, LTO, codegen-units 1, strip).

    lanfetch-tauri ──► lanfetch-engine        lanfetch-web ──► lanfetch-engine
    (Tauri shell)      (path dep)             (web binary)     (path dep)
         │                                          │
         └─ re-exports engine modules             └─ embeds frontend dist
            as crate::{core, dto, manager, …}        via include_dir (build input)

No crate depends on a shell: the engine has zero GUI deps, which is
what lets the web binary ship as one self-contained executable
(CI asserts this with `ldd`). Both shells consume the *same*
`engine::state::AppState`; there is no second application layer.

## 2. Engine module graph (engine/src/, 4,330 LOC)

| Module | LOC | Depends on (crate-internal) | Role |
|---|---|---|---|
| `core` | 823 | — (std + ureq only) | HTTP agent, listings, probe, segments, `Source`, `seed_sources` |
| `meta` | 410 | — (regex only) | Release-name parsing (year/quality/season/episode/audio) |
| `store` | 247 | `core` (`seed_sources`, `Source`) | JSON persistence: settings, sources, favorites (atomic write + `.bak`) |
| `manager` | 611 | `core::*`, `store` (settings) | Download lifecycle, scheduler, persistence of tasks |
| `catalog` | 1,741 | `core` (`fetch_listing`, `http_agent`, `DirEntry`), `meta::*` | Crawl, feed sessions, search, poster disk cache |
| `poster_service` | 159 | `catalog` (`poster_key`, `fetch_poster_cached`) | Poster resolution/dedup for the shells |
| `dto` | 289 | `core::Segment`, `manager::{DlState, Task}`, `store::{Settings, SourceItem}`, `catalog::{Category, Title}` (in `From`/`new` impls) | Wire types — the IPC/HTTP contract |
| `state` | 36 | `catalog`, `manager`, `poster_service`, `store::SourceItem` | `AppState` composition root shared by both shells |
| `lib` | 14 | declares all modules `pub` | — |

Layering, bottom → top:

    core, meta            (leaves; no crate-internal deps)
      ▲     ▲
    store   catalog       (store→core is only for seed data — a mild
      ▲       ▲            smell: seed constants live in transport)
      └─ manager          (manager ↛ catalog, manager ↛ dto: clean)
              ▲
    dto ──► manager, catalog, store, core   (top layer; shells only)
    state ──► manager, catalog, poster_service, store

Properties worth keeping:
- **No cycles.** Every edge points one way; `dto` is a pure consumer,
  `manager` never reaches back into `catalog` or `dto`.
- Shells use the engine asymmetrically but compatibly: `src-tauri`
  re-exports engine modules through its own `lib.rs` and calls them
  as `crate::…`; `web/src/serve.rs` names `lanfetch_engine::…`
  directly (catalog discovery, search, hydrate, store, manager).

## 3. Frontend modules (desktop-tauri/src/)

    pages/index.astro (2,805 lines: markup + the one module script)
      ├── styles/global.css ──► @import "tailwindcss" (+ @theme tokens)
      ├── lib/api.ts ──► @tauri-apps/api/{core,event}   [only dep]
      ├── lib/format.ts ──► type-only import from lib/api.ts
      └── lib/ui.ts ──► (nothing; DOM + the sprite in index.astro)

- `api.ts` is the sole transport seam: Tauri IPC ⇄ `POST /api/<cmd>`
  + SSE, chosen once at runtime. Its DTO interfaces mirror
  `engine/src/dto.rs` by hand — pinned by convention, not by codegen.
- `format.ts` is stateless/DOM-free; `ui.ts` (icon/el/toast/dialog)
  depends on the inline SVG sprite defined in `index.astro`, so the
  sprite and `ui.ts` must move together in any extraction.
- Build chain: `astro` (static output, no SSR adapter) +
  `@tailwindcss/vite`; `@tauri-apps/cli` is build-tooling only.

## 4. Android packages (app/, single module, ~11,200 LOC)

    ui/* (library, downloads, servers, settings, onboarding,
          components, theme, brand)
      ├──► data      (7 files: Room AppDb, entities, repositories,
      │               DataStore SettingsStore)
      ├──► download  (7 files: Coordinator, Engine, Service, Sink,
      │               SegmentPlanner, models, Format)
      └──► net       (9 files: OkHttp Http, h5ai/autoindex parsers,
                      LiveServerClient, LiveFeedSession, Sources)
    download ──► net (transport) + data (entities/state)
    net ──► data (models)        filter (1 file) ──► data

`ui/theme` is the most-imported package (palette/spacing/radii are
referenced by every screen); `data` is the shared vocabulary layer.
Manual DI via `LanFetchApp`/`AppContainer` — no DI framework.
Root Gradle includes only `:app`; `desktop/` is a separate build.

## 5. External dependency census

### Rust — engine (4 direct)
| Dep | Used by | Verdict |
|---|---|---|
| `serde` (derive) | dto, store, manager | Keep — the persistence + wire format |
| `serde_json` | store, dto consumers | Keep — JSON store files and API bodies |
| `ureq` 2 | `core` (single shared agent) | Keep — blocking HTTP fits the thread-per-segment model; see V2 networking review before any swap |
| `regex` 1 | `meta` | Keep — release-name grammar |

### Rust — src-tauri (7 direct + build)
| Dep | Verdict |
|---|---|
| `tauri` 2, `tauri-build` 2 | Keep — the shell itself |
| `tauri-plugin-single-instance` 2 | Keep — one-instance guarantee |
| `lanfetch-engine` (path) | Keep — the shared core |
| `serde`, `serde_json` | Keep — command args/results serialization |
| `ureq` 2 | **DEAD — remove** (see §6) |
| `regex` 1 | **DEAD — remove** (see §6) |

### Rust — web (4 direct)
| Dep | Verdict |
|---|---|
| `lanfetch-engine` (path) | Keep |
| `serde`, `serde_json` | Keep — `/api` bodies |
| `include_dir` 0.7 | Keep — embeds the built frontend into the single binary |

### npm (package.json)
| Dep | Verdict |
|---|---|
| `astro` ^7 | Keep — static shell; no framework integration installed |
| `tailwindcss` ^4 + `@tailwindcss/vite` ^4 | Keep — the design-token CSS pipeline |
| `@tauri-apps/api` ^2 | Keep — IPC + events in `lib/api.ts` |
| `@tauri-apps/cli` ^2 (dev), `typescript` ~5.9 (dev) | Keep — build tooling; note `tsc` cannot parse `.astro`, so the page script is outside its reach |

### Gradle — Android app (versions from gradle/libs.versions.toml)
| Dep | Verdict |
|---|---|
| AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28 | Keep — toolchain |
| Compose BOM 2024.12.01, Material3, material-icons-core, ui/graphics/tooling | Keep — the UI |
| activity-compose 1.9.3, lifecycle 2.8.7, navigation-compose 2.8.5, core-ktx 1.15.0, appcompat 1.8.0 | Keep — AndroidX scaffolding (appcompat also carries the v1.3.9 theme fix) |
| Room 2.8.5 (runtime/ktx/compiler via KSP) | Keep — download/source persistence, schema export on |
| OkHttp 4.12.0 | Keep — Android transport; parity reference for the Rust engine |
| Coil 2.7.0 | Keep — poster loading |
| jsoup 1.18.3 | Keep — listing HTML parsing |
| kotlinx-coroutines-android 1.9.0 | Keep — structured concurrency + call cancellation |
| DataStore Preferences 1.1.1 | Keep — settings |
| JUnit 4.13.2 (test) | Keep — 57 JVM unit tests |

### Gradle — legacy desktop/ (own build, not in root settings)
Compose Desktop 1.7.3 + Kotlin 2.0.21, OkHttp 4.12.0, jsoup 1.18.3,
coroutines-core/swing 1.9.0. **Frozen** — see §7; its deps serve no
shipped artifact and should not be upgraded, only retired with it.

## 6. Dead dependencies

`src-tauri/Cargo.toml` declares `ureq = "2"` and `regex = "1"`, but
no file under `src-tauri/src/` references either (verified by grep):
all HTTP lives in `engine::core`, all parsing in `engine::meta`, and
the shell reaches both through the engine re-export. Cost today is
compile time and audit surface, not binary size (unused crates are
not linked into code paths) — removal is a two-line change with a CI
run, scheduled as V2 hygiene, not a hotfix.

No dead deps were found in the engine, web, npm, or Android sets.

## 7. Duplication map

### Three download engines (no shared code)
| Engine | Where | Status |
|---|---|---|
| Kotlin (Android) | `app/…/download/` — Coordinator/Engine/Service/Sink/SegmentPlanner | Shipped, most mature: re-probe retry, socket-level cancel, Content-Range validation, 57 tests incl. local-server integration |
| Rust | `engine/src/manager.rs` + `core.rs` transfer | Shipped (desktop + web); lifecycle has 1 unit test; defect register in the Wave 0 audit |
| Kotlin (legacy desktop) | `desktop/…/download/` — Engine, SegmentPlanner, DesktopDownloadSink, DesktopDownloadManager | **Dead**: unbuilt by CI, unreleased, superseded by Tauri |

### Two catalog stacks
Rust `catalog.rs` + `meta.rs` (crawl, h5ai/autoindex, poster picking,
search ranking) vs Kotlin `app/…/net/` (LiveServerClient,
LiveFeedSession, H5aiJson, AutoindexParser, MetaParser, PosterPicker)
— same servers, same formats, evolved independently; behavioral
drift is expected and is what Squad 9's parity contracts must pin.
The legacy `desktop/…/net/` tree is a third copy, file-for-file in
spirit with `app/…/net/`, and retires with the tree.

### Legacy desktop/ tree
27 tracked files, ~3,176 LOC: a complete Compose Desktop product
(own Gradle build, own `.deb` packaging, version 1.0.0) that no
workflow builds and no release ships. It is the only remaining
consumer-shaped copy of the pre-Tauri architecture. Recommendation
of record: delete in a V2 wave as its own commit (recoverable from
git history), after the parity contracts no longer need it as a
reference — do not port anything from it.

### Smaller duplications
- Seed sources: engine seeds 5 DhakaFlix servers, Android seeds 6
  (`.9` missing on desktop) — one list, two copies, already drifted.
- Format helpers: `app/…/download/Format.kt` vs frontend
  `lib/format.ts` vs engine inline formatting — same display rules
  in three languages; acceptable across platforms, but changes must
  be made in all three deliberately.
- DTO definitions: `engine/src/dto.rs` ⇄ `lib/api.ts` interfaces,
  hand-mirrored (see §3).
