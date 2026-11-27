# LanFetch V2 Architecture

**Status:** Wave 1 architecture baseline  
**Applies to:** LanFetch at repository HEAD `43ed283`  
**Scope:** Android application, Rust engine, Tauri desktop shell, standalone web server, and Astro/TypeScript frontend  
**Primary implementation root:** `desktop-tauri/`  
**Android implementation root:** `app/`

This document records the architecture that exists today, the target architecture for LanFetch V2, the boundaries that must not be crossed, and the architectural effect of Waves 2–9.

V2 does **not** reorganize the repository into the conceptual directory tree shown in the mission brief. The current locations remain authoritative:

- `app/` remains the standalone Android application.
- `desktop-tauri/engine/` remains the shared Rust engine crate.
- `desktop-tauri/src-tauri/` remains the Tauri desktop transport and host shell.
- `desktop-tauri/web/` remains the standalone web transport and server.
- `desktop-tauri/src/` remains the frontend application.
- `desktop-tauri/packaging/` remains the desktop packaging boundary.

Architectural separation is achieved through module boundaries, contracts, ownership, and dependency rules inside those existing locations.

---

## 1. Current architecture in brief

LanFetch currently has one Rust application core, two Rust host transports, one browser frontend, and one independent Android implementation.

The Rust core is the `lanfetch-engine` crate declared in `desktop-tauri/engine/Cargo.toml`. Its `lib.rs` exposes these modules:

- `catalog`
- `core`
- `dto`
- `manager`
- `meta`
- `poster_service`
- `state`
- `store`

The Tauri application and standalone web binary both depend on that crate. The Cargo workspace in `desktop-tauri/Cargo.toml` contains exactly three members:

- `engine`
- `src-tauri`
- `web`

The frontend has one transport seam in `desktop-tauri/src/lib/api.ts`. At runtime it detects Tauri through `__TAURI_INTERNALS__`; otherwise it uses HTTP. Application code calls typed wrapper functions rather than constructing command names throughout the UI.

The Android application is not a consumer of the Rust engine. It is a separate Kotlin implementation under `app/src/main/java/com/lanfetch/app/`, organized into `data`, `download`, `net`, `filter`, and `ui` packages. Android is therefore a behavioral reference and parity target, not a third transport over the Rust core.

---

## 2. Current component map

| Area | Current location | Responsibility |
|---|---|---|
| Shared application state | `engine/src/state.rs` | Defines `AppState` and `CatalogState`, shared by the Tauri and web hosts. |
| Download management | `engine/src/manager.rs` | Owns download tasks, scheduling, task persistence, settings application, progress stamps, and the framework-neutral change notifier. |
| Download transfer | `engine/src/core.rs` | Provides the shared HTTP agent, URL probing, directory-listing fetch/parsing, segment planning, transfer progress, and `run_download`. |
| Catalog | `engine/src/catalog.rs` | Discovers categories, builds titles, pages feed sessions, searches sources, loads title files, caches listings/posters, and checks for desktop updates. |
| Metadata parsing | `engine/src/meta.rs` | Parses release names and related media metadata used by catalog construction. |
| Poster service | `engine/src/poster_service.rs` | Registers poster URLs, resolves poster keys, returns poster bytes, and determines image content type. |
| Persistence | `engine/src/store.rs` | Loads and saves settings, sources, favorites, and favorite-title snapshots using JSON files, temporary writes, rename, and `.bak` fallback. |
| Wire contracts | `engine/src/dto.rs` | Defines the Rust DTOs returned through Tauri IPC and the web API. |
| Tauri host | `src-tauri/src/lib.rs` | Builds application state, registers single-instance behavior, bridges download notifications to Tauri events, serves the poster protocol, and registers commands. |
| Tauri commands | `src-tauri/src/commands.rs` | Adapts Tauri command calls to `AppState`, the manager, catalog state, stores, and poster service. |
| Web host | `web/src/main.rs` | Parses `--port` and `--lan`, then enters the web server. |
| Web transport | `web/src/serve.rs` | Implements the current HTTP server, embedded static UI, poster route, SSE event stream, and command dispatch. |
| Frontend transport | `src/lib/api.ts` | Selects Tauri IPC or HTTP, invokes commands, mirrors Rust DTOs in TypeScript, and subscribes to download-change notifications. |
| Frontend application | `src/pages/index.astro` | Contains the current screens, rendering, application state, event handling, and boot orchestration in one Astro page. |
| Frontend utilities | `src/lib/format.ts`, `src/lib/ui.ts` | Provide formatting helpers and DOM/icon/toast/dialog primitives. |
| Design system | `src/styles/global.css`, `DESIGN.md` | Define the current tokens, component classes, responsive rules, and recorded UI decisions. |
| Android | `app/` | Contains the independent Kotlin catalog, networking, download, persistence, and Compose UI implementation. |

### 2.1 Shared state today

`engine/src/state.rs` defines `AppState` with:

- `manager: Arc<Manager>`
- `sources: Mutex<Vec<SourceItem>>`
- `favorites: Mutex<Vec<String>>`
- `posters: Arc<PosterService>`
- `catalog: Arc<Mutex<CatalogState>>`

`CatalogState` owns discovered categories, the current `FeedSession`, a monotonically increasing generation, and a map of titles already emitted by the feed.

Both hosts construct materially the same state:

- `src-tauri/src/lib.rs` loads the manager, sources, favorites, poster service, and catalog state before building the Tauri application.
- `web/src/serve.rs` performs the corresponding construction before accepting HTTP connections.

This shared state is the strongest existing evidence that desktop and web are two front doors to one application, even though some orchestration is still duplicated in their command layers.

---

## 3. Current runtime flows

### 3.1 Startup and bootstrap

1. The host loads persisted download state through `Manager::load()`.
2. The host loads sources and favorites through `engine/src/store.rs`.
3. The host creates `PosterService` and an initially empty `CatalogState`.
4. The frontend calls `get_bootstrap`.
5. The returned `BootstrapDto` contains the application version, settings, sources, favorites, current downloads, downloads directory, and data directory.

### 3.2 Catalog browsing

1. The frontend calls `get_categories`, `feed_open`, `feed_next`, `search_titles`, or `get_title_detail` through `src/lib/api.ts`.
2. The selected transport invokes the corresponding Tauri command or web dispatch operation.
3. The host operates on the shared `CatalogState` and calls functions in `engine/src/catalog.rs`.
4. `feed_open` replaces the active feed session and increments the catalog generation.
5. `FeedPageDto.superseded` tells the frontend to discard pages belonging to an older generation.
6. Detail hydration uses titles retained in `CatalogState.titles` rather than requiring the frontend to send a complete title object back to the backend.

### 3.3 Poster delivery

Poster bytes deliberately do not travel through ordinary command responses.

- The frontend first calls `poster_srcs` to register source URLs and obtain transport-specific poster references.
- In Tauri mode, `src-tauri/src/lib.rs` registers the asynchronous `lanfetch-poster:` URI protocol and resolves bytes through `PosterService` off the protocol thread.
- In web mode, `web/src/serve.rs` serves poster bytes through its `/posters/<key>` route.
- In both cases, `PosterService` is the application boundary; the frontend does not fetch arbitrary poster URLs itself.

### 3.4 Download lifecycle

1. The frontend calls `start_download` with a URL and display name.
2. The host delegates to `Manager::enqueue`.
3. `Manager` deduplicates against queued, running, and paused tasks; creates a task; persists state; and starts URL probing.
4. A successful probe records total size, validator, segment plan, and the runtime `ready` flag.
5. `Manager::pump` starts ready queued tasks while concurrency slots are available.
6. `engine/src/core.rs::run_download` performs the segmented or single-stream transfer and updates shared progress.
7. The manager records completion, failure, or pause and persists the resulting task state.
8. The manager invokes its registered notifier whenever rendered download state changes.

The public download states in `manager.rs` and `dto.rs` are currently:

- `queued`
- `running`
- `paused`
- `completed`
- `failed`

Probing and readiness are runtime details today: a newly created task is queued with `ready = false`, and a successful probe changes it to `ready = true`.

### 3.5 Download-change events

Download updates are push-based in both transports.

- The manager owns a framework-neutral `Notifier`.
- Tauri converts a notification into the `downloads-changed` event and sends the manager's current stamp.
- The web server converts a notification into an SSE message containing the same stamp.
- `src/lib/api.ts::onDownloadsChanged` hides that difference from the page.
- The frontend refetches the download list after notification and uses the stamp and its existing fetch-coalescing guards to discard stale work.

No ordinary download-progress polling is part of the current architecture.

---

## 4. Current persistence and concurrency

### 4.1 Persistence

The Rust engine uses file-backed persistence rather than an embedded database.

- `store.rs` writes JSON through a temporary file and rename.
- Before replacement, the previous file is retained as a `.bak` sibling.
- Reading falls back to the backup when the primary file cannot be used.
- Settings are stored as `settings.json`.
- Sources are stored as `sources.json`.
- Favorite URLs are stored as `favorites.json`.
- Favorite-title snapshots are stored separately so the Favorites screen can render known titles.
- `Manager` persists download tasks as JSON under the LanFetch data directory.
- Partial downloads use a `.part` file in the downloads directory and are renamed when complete.

Android persistence is separate: Room stores its application data, migrations are explicit, and settings use Android-side storage mechanisms. V2 does not unify Android and Rust storage formats merely for symmetry.

### 4.2 Concurrency

- `Manager` owns the task list behind a mutex.
- Download work runs on engine-managed threads rather than on the Tauri command thread or web request thread.
- A manager ticker updates speed and progress stamps while downloads are active.
- `AppState` uses mutexes for sources, favorites, and catalog state.
- Tauri executes blocking poster work through `spawn_blocking`.
- The current web server uses one thread per accepted connection.
- Frontend feed, search, detail, and download refreshes use generation, stamp, in-flight, and dirty flags to prevent older asynchronous work from overwriting newer state.

These mechanisms work, but several are conventions inside individual modules rather than explicit architectural contracts. V2 makes the important ones documented, testable, and bounded.

---

## 5. Current architectural constraints and debts

The Wave 0 audit established the following constraints. They are architectural facts, not reasons to discard the existing system.

1. **The lifecycle is under-modeled.** Five public states cannot directly express probing, pausing, retrying, verification, or cancellation. Readiness is a separate boolean, which allowed a failed initial probe to leave a task permanently unready.
2. **Transport orchestration is duplicated.** `src-tauri/src/commands.rs` and the dispatch section of `web/src/serve.rs` both translate requests into operations over `AppState`; they must not be allowed to diverge into separate business logic.
3. **Errors are mostly strings.** Rust command errors, engine errors, and web errors do not yet share a stable kind/code taxonomy that a frontend can branch on.
4. **The web server is intentionally dependency-light but under-bounded.** `web/src/serve.rs` implements HTTP directly with standard-library sockets, thread-per-connection handling, embedded static files, and SSE. It currently lacks the production connection, timeout, authentication, and versioning boundaries required for LAN exposure.
5. **The frontend is behavior-rich but monolithic.** `src/pages/index.astro` is approximately 2,800 lines and owns markup, screen rendering, module state, event listeners, and boot logic in one page closure.
6. **The transport seam is valuable but incomplete.** `api.ts` chooses its transport once, does not expose an unsubscribe handle for download events, has no common timeout/abort contract, and performs no runtime DTO validation.
7. **Catalog and transfer responsibilities overlap inside large engine files.** `catalog.rs` and `core.rs` are coherent enough to preserve, but their internal policy boundaries—HTTP policy, listing parsing, caching, transfer execution, and segment planning—need explicit ownership.
8. **Android and Rust behavior has drifted.** Android currently has stronger cancellation, retry/re-probe, redirect, and Content-Range behavior in several paths. Parity must be specified behaviorally rather than assumed from similar feature names.
9. **Persistence is resilient to torn JSON writes but not yet fully reconciled with partial files.** Atomic JSON replacement does not by itself prove that persisted segment offsets match the actual `.part` file after power loss or external deletion.
10. **Legacy code is outside the target architecture.** The old `desktop/` Compose Desktop tree and the empty `desktop-rs/` shell are not workspace members, are not built by the desktop/web CI path, and must not be treated as additional V2 transports.

---

## 6. Target architecture principles

### Principle 1 — One application core

Desktop and web must use the same Rust domain and application services. A Tauri command and an HTTP endpoint are adapters over the same operation, not parallel implementations.

### Principle 2 — Transports contain no business rules

A transport may authenticate, parse, validate shape, invoke an application operation, map errors, and serialize a response. It may not decide download eligibility, segment policy, catalog ranking, favorites semantics, or persistence rules.

### Principle 3 — Preserve locations, clarify modules

V2 does not move `engine`, `src-tauri`, `web`, `src`, or `app`. New or split modules may be introduced inside those existing roots when a boundary is otherwise impossible to test or enforce.

### Principle 4 — Public compatibility before internal cleanup

Existing command names, DTO fields, persisted data, Android behavior, and user workflows remain compatible unless a separately approved migration changes them. Internal lifecycle phases may be richer than the public five-state DTO, but every internal phase must have an explicit public mapping.

### Principle 5 — No fabricated state

The UI may display only state supplied by the engine or a documented derivation from it. Progress, speed, ETA, segment activity, server health, and completion must not be synthesized to make the interface appear more capable than the backend.

### Principle 6 — Bounded work

Network requests, download workers, web connections, subscribers, retries, caches, and event delivery must have documented limits. No new unbounded thread, queue, response body, or retry loop is acceptable.

### Principle 7 — Persistence must reconcile, not merely reload

Startup recovery must compare persisted intent with actual filesystem state before trusting byte offsets. A missing, truncated, or inconsistent partial file must cause replanning or restart of the affected ranges, never silent completion with holes.

### Principle 8 — Android parity is behavioral

Android and Rust do not need identical code, storage, or threading models. They must satisfy the same user-visible contracts for adding, pausing, resuming, retrying, recovering, validating, and failing downloads.

### Principle 9 — Security follows exposure

Tauri IPC is a local application boundary protected by Tauri capabilities and the application command surface. HTTP is a network boundary. Binding beyond loopback requires authentication, request validation, limits, and explicit user-visible exposure status.

### Principle 10 — Every boundary needs an executable test

A contract that exists only in documentation is not an architectural boundary. Lifecycle transitions, DTO compatibility, API behavior, recovery, and cross-platform operations require automated tests at the lowest practical level.

---

## 7. Target logical layers mapped to current locations

| Logical layer | Physical location in V2 | Owns | Must not own |
|---|---|---|---|
| Domain model | `engine/src/manager.rs`, `engine/src/core.rs`, `engine/src/catalog.rs`, `engine/src/meta.rs` | Download lifecycle, segment model, catalog entities, metadata rules | HTTP server routing, Tauri types, frontend presentation |
| Application services | Primarily `engine/src/state.rs` plus service-facing functions in engine modules | Operations used identically by desktop and web: bootstrap, catalog sessions, downloads, sources, favorites, settings, posters | Transport-specific status codes, window behavior, browser behavior |
| Download engine | `engine/src/manager.rs` and transfer code in `engine/src/core.rs` | Task ownership, scheduling, probing, transfer execution, recovery, completion validation | UI notifications beyond the framework-neutral notifier |
| Networking policy | Transfer/listing/probe code in `engine/src/core.rs` and catalog callers | Timeouts, redirects, retries, range validation, response limits, error classification | Download persistence or UI state |
| Catalog service | `engine/src/catalog.rs` and `CatalogState` in `engine/src/state.rs` | Categories, feed sessions, search, title construction, detail hydration, listing/poster caches | Download scheduling |
| Persistence | `engine/src/store.rs` and manager persistence in `engine/src/manager.rs` | JSON formats, atomic replacement, backup fallback, schema evolution, recovery inputs | Network operations or transport authorization |
| API contracts | `engine/src/dto.rs` and `src/lib/api.ts` | Rust DTO definitions and their TypeScript mirrors, command names, event payload semantics | Business decisions hidden in serialization |
| Tauri transport | `src-tauri/src/commands.rs` | Tauri argument adaptation, application-service invocation, Tauri error mapping | Independent catalog or download logic |
| Tauri host | `src-tauri/src/lib.rs` | Window/application lifecycle, single-instance behavior, event bridge, poster protocol, capability surface | Download or catalog policy |
| Web transport | `web/src/serve.rs` or its in-place successor modules | HTTP parsing/routing, authentication, limits, static delivery, SSE, HTTP error mapping | Independent catalog or download logic |
| Frontend application | `src/pages/index.astro` plus extracted modules/components under `src/` | Presentation, interaction state, screen composition, accessibility, responsive behavior | Download business rules or direct command construction outside `api.ts` |
| Android application | `app/` | Android UI, Room persistence, Android networking, foreground-service behavior | Rust engine internals or Tauri/web assumptions |

The important target change is not the table's vocabulary; it is the direction of dependency. Presentation and transports depend on contracts and application services. The engine does not depend on Tauri, HTTP, Astro, or Android.

---

## 8. Transport separation

```text
                         ┌──────────────────────────────┐
                         │       Frontend application    │
                         │  src/pages + src/lib modules  │
                         └──────────────┬───────────────┘
                                        │
                         Typed wrappers and DTO mirrors
                              src/lib/api.ts
                                        │
                    ┌───────────────────┴───────────────────┐
                    │                                       │
          Tauri transport                         HTTP transport
     src-tauri/src/commands.rs                 web/src/serve.rs
     Tauri events for changes                  SSE for changes
     lanfetch-poster: for media                /posters for media
                    │                                       │
                    └───────────────────┬───────────────────┘
                                        │
                         Shared application state/services
                              engine/src/state.rs
                                        │
              ┌─────────────────────────┼─────────────────────────┐
              │                         │                         │
      Download manager           Catalog service             Poster service
      engine/src/manager.rs      engine/src/catalog.rs       engine/src/poster_service.rs
              │                         │                         │
              └─────────────────────────┼─────────────────────────┘
                                        │
                    Networking and persistence boundaries
                    engine/src/core.rs    engine/src/store.rs
                                        │
                         LAN file servers / local disk
```

Android stands outside this diagram by design:

```text
Android UI ──> Android application/data layer ──> Kotlin download engine
     │                    │                               │
     └────────────── Room/DataStore                 OkHttp/network
```

Android participates through shared behavioral contracts and the cross-platform test matrix, not through shared Rust code or a forced storage format.

---

## 9. Contract architecture

### 9.1 Command contract

The current command vocabulary is the compatibility baseline. It includes bootstrap, source probing, poster registration, categories, feed paging, title detail, search, favorites, download controls, settings, source management, bulk download controls, redownload, and update checking.

Rules:

- `src/lib/api.ts` remains the only frontend location that knows command names.
- Tauri command names and web command adapters must resolve to the same application operation.
- A command may be added without breaking existing callers.
- Removing or renaming a command requires a compatibility adapter or a documented major-version migration.
- A command returning a download list must return the manager's current stamp with that list.

### 9.2 DTO contract

`engine/src/dto.rs` is the canonical Rust definition. The interfaces in `src/lib/api.ts` are mirrors, not independent designs.

Rules:

- Existing field names and meanings are stable.
- New fields should be additive when practical.
- A field must represent backend data or a clearly documented derivation.
- The frontend must not infer unavailable metadata from names, artwork, or file paths when the backend contract does not supply it.
- Contract tests must detect Rust/TypeScript drift for the fields the UI consumes.

### 9.3 Lifecycle contract

V2 introduces a formal internal lifecycle without discarding the five public states prematurely.

The internal model must be able to represent:

- queued
- probing
- ready
- running
- pausing
- paused
- retrying
- verifying
- completed
- failed
- cancelled

Until a versioned public contract intentionally exposes more states, the mapping is:

| Internal phase | Public DTO state |
|---|---|
| Queued | `queued` |
| Probing | `queued` |
| Ready | `queued` |
| Retrying | `queued` or `failed`, according to whether an attempt is scheduled or exhausted |
| Running | `running` |
| Pausing | `running` until the worker has actually stopped |
| Paused | `paused` |
| Verifying | `running` |
| Completed | `completed` |
| Failed | `failed` |
| Cancelled before completion | `failed` only if cancellation is represented as a terminal user action in the existing UI contract; otherwise it remains internal until the API version defines it explicitly |

No transition may be reported complete merely because it was requested. In particular, a task is not paused until its worker has stopped writing, and it is not completed until byte and integrity validation succeed.

### 9.4 Error contract

V2 replaces transport-visible free-form errors with a two-part model:

- a stable machine-readable kind or code;
- a human-readable message suitable for display and logs.

The taxonomy must distinguish at least:

- invalid input
- not found
- unauthorized or forbidden
- transport failure
- timeout
- HTTP status failure
- range or validator failure
- filesystem failure
- insufficient space
- persistence failure
- internal failure

Tauri and HTTP may serialize that model differently, but neither may collapse distinct application failures into unrelated meanings. Existing string messages may remain during migration as the human-readable component.

### 9.5 Event contract

The download-change event remains a monotonic stamp, not a replacement for fetching authoritative state.

Rules:

- A notification means “download state may have changed.”
- The payload stamp must come from `Manager::stamp`.
- A client must ignore work older than the newest stamp it has applied.
- Event delivery may coalesce bursts, but it may not invent a final state.
- Reconnection or subscription recovery must trigger a fresh authoritative fetch.

---

## 10. Ownership

| Boundary | Accountable owner |
|---|---|
| Architecture and ADRs | Squad 1 — Architecture and Engineering Leadership |
| Rust lifecycle, manager, transfer correctness | Squad 2 — Rust Core and Download Engine |
| HTTP policy and network failure behavior | Squad 3 — Networking and HTTP Infrastructure |
| Tauri commands, host lifecycle, permissions | Squad 4 — Tauri Desktop Backend |
| Web server, HTTP API, LAN security | Squad 5 — Web Server and API |
| Frontend modules, state, transport client | Squad 6 — Frontend Architecture and State Management |
| Desktop interaction and visual implementation | Squad 7 — Professional Desktop UI/UX |
| Responsive web behavior | Squad 8 — Responsive Web and Cross-Platform Experience |
| Android preservation and parity evidence | Squad 9 — Android Preservation and Cross-Platform Parity |
| Quality gates, performance, security, release readiness | Squad 10 — QA, Performance, Security and Release Engineering |
| Shared files and integration order | Integration Director |

Ownership means review responsibility, not exclusive authorship. A change crossing a boundary requires review from the owners on both sides.

The highest-conflict shared surfaces are:

- `engine/src/dto.rs`
- `engine/src/state.rs`
- `engine/src/manager.rs`
- `engine/src/core.rs`
- `src/lib/api.ts`
- `src/pages/index.astro`
- `.github/workflows/desktop-tauri.yml`
- `.github/workflows/android.yml`

No squad may change one side of a shared contract and leave the other side for a later wave.

---

## 11. Architectural changes by wave

### Wave 2 — Rust engine stabilization

- Formalize the internal download state machine inside the existing manager/core boundary.
- Make retry of a probe-failed task re-enter probing rather than depend on a stale `ready` value.
- Replace pause signaling with a bounded cancellation design whose worker-stop semantics are explicit.
- Add progress checkpointing and startup reconciliation between persisted segments and the actual `.part` file.
- Validate `Content-Range`, response length, validators, and segment boundaries before trusting received bytes.
- Serialize or otherwise make download-state saves race-safe.
- Add local HTTP integration and failure-injection tests without changing the public DTO contract except through the approved mapping.

### Wave 3 — Networking, HTTP API, and security

- Extract one documented networking policy from the existing engine call sites: timeouts, redirect rules, status retry, backoff, range handling, and error classification.
- Preserve `ureq` unless measurements or required cancellation behavior justify an ADR-backed replacement.
- Turn the web transport into a bounded, authenticated network boundary while retaining the current location and embedded-UI model.
- Add a versioned HTTP API and retain a compatibility adapter for the existing `/api/<command>` frontend contract during migration.
- Keep SSE or replace it only through an ADR that preserves the stamp-based authoritative-refetch model.
- Ensure Tauri and web adapters call the same application operations rather than duplicated dispatch logic.

### Wave 4 — Frontend architecture

- Decompose `src/pages/index.astro` into screen, component, service, and state modules inside the existing `src/` tree.
- Preserve `src/lib/api.ts` as the transport seam while adding disposal, timeout/abort behavior, and consistent error handling.
- Separate server state, application state, user preferences, and temporary UI state.
- Give each screen an explicit mount/dispose lifecycle so listeners, observers, timers, and subscriptions cannot leak.
- A framework adoption is not assumed by this architecture. If React or SolidJS is selected, it must consume the same transport and DTO contracts and be justified by a separate ADR; the architectural requirement is modular boundaries, not a particular framework logo.

### Wave 5 — Desktop experience and Tauri integration

- Reduce `src-tauri/src/commands.rs` to a thin, typed adapter over shared application services.
- Add predictable event coalescing at the host bridge if measurements show the manager's notification cadence is excessive.
- Define application shutdown behavior for manager workers, poster work, and the event bridge.
- Keep Tauri permissions at least privilege; the current capability model grants `core:default` only and must not expand without a feature requirement.
- Desktop screens consume the Wave 4 frontend modules and do not acquire desktop-only download logic.

### Wave 6 — Responsive web experience

- Keep one frontend build and one API contract for desktop and web.
- Express phone, tablet, compact-desktop, and wide-desktop behavior as shell and layout boundaries rather than separate applications.
- Preserve the web server's embedded-static delivery model unless Wave 3 evidence justifies a different server architecture.
- Do not describe responsive web behavior as a native Android or native mobile application.

### Wave 7 — Android parity

- Convert the behavioral contract into an executable Android/Rust comparison matrix.
- Verify add, pause, resume, retry, recovery, unsupported ranges, changed files, duplicate URLs, disconnects, and disk-full behavior on both implementations.
- Preserve Room data and existing Android downloads; any Android change requires Squad 9 review and migration evidence.
- Do not introduce JNI, UniFFI, or a shared Rust Android library without a separate cost/benefit ADR.

### Wave 8 — Testing, performance, and security architecture

- Add permanent test seams rather than test-only production branches: local HTTP fixtures, API contract tests, lifecycle tests, frontend component tests, and cross-platform scenario tests.
- Measure startup, memory, idle cost, search, poster loading, API latency, throughput, pause latency, and resume latency against the Wave 0 baseline.
- Add dependency, permission, path-traversal, authentication, and LAN-exposure regression tests to CI.
- Browser-based layout or visual testing may be added only as an explicitly approved testing architecture; static CSS guardrails must not be described as layout tests.

### Wave 9 — Release preparation

- Freeze the V2 contracts and record any intentional breaking change in a migration document.
- Keep the existing release outputs and locations: desktop package, standalone web binaries, and Android release artifacts.
- Validate upgrades using data produced by the shipped 1.6.x formats.
- Publish architecture, API, security, testing, performance, migration, and release-checklist documents that match the final code.
- Do not mark V2 complete from build success alone; release evidence must include the engine acceptance scenarios and the cross-platform parity matrix.

---

## 12. Non-negotiable architectural invariants

1. The Rust engine must not depend on Tauri, Astro, or a specific HTTP server.
2. Tauri and web transports must not maintain separate download business logic.
3. The frontend must not bypass `src/lib/api.ts` to invoke commands.
4. Download completion requires validation, not merely connection closure.
5. A failed probe must never create an permanently unretryable task.
6. Persisted segment progress must never be trusted beyond the verified length and state of the partial file.
7. LAN-exposed control requires authentication and bounded request handling.
8. Android user data must survive V2 upgrades without destructive migration.
9. No working feature may be replaced by a placeholder to satisfy a new architecture.
10. No architectural claim is complete without implementation and test evidence.
