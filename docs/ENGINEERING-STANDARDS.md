# LanFetch Engineering Standards

Binding for all V2 work in this repo: the Rust engine (`desktop-tauri/engine/`),
the Tauri shell (`desktop-tauri/src-tauri/`), the web binary (`desktop-tauri/web/`),
the Astro/TypeScript frontend (`desktop-tauri/src/`), and the Android app (`app/`).
Where this document and a wave brief disagree, this document wins until amended
by an architecture decision record in `docs/ARCHITECTURE-DECISIONS/`.

## 1. General principles

1. **No silent failures.** Every error is classified, surfaced, or explicitly
   logged with its code. `let _ =`, `.ok()`, and empty `catch` blocks on fallible
   operations are defects unless a comment states why the failure is harmless.
2. **No stringly-typed logic.** Code must never branch on the text of an error
   message. (Wave 0 found the catalog detecting outages by matching
   `"request failed"` / `"read failed"` prefixes — that pattern is banned.)
3. **Compatibility is a feature.** Public IPC commands, DTO shapes, persisted
   file formats, and the web API change additively only (§8).
4. **One behavior, one implementation.** Desktop and web share the engine;
   Android is the behavioral reference. A fix lands in the shared layer or is
   documented as platform-specific in the parity matrix.
5. **No new dependency without a decision record** stating what it replaces,
   its maintenance status, and its size/feature cost. The engine currently
   depends only on serde, serde_json, ureq, and regex; keep it that way.

## 2. Rust standards

- `cargo fmt --check --all` and `cargo clippy --locked --workspace -- -D warnings`
  are hard CI gates and must pass before merge. No `#[allow]`
  added to silence a lint without a comment naming the reason.
- `unsafe` is forbidden in the engine and web crates. In `src-tauri` it is
  permitted only inside Tauri-generated or plugin code.
- No `unwrap()` / `expect()` / panic paths on user input, network data, or
  persisted data. `unwrap()` is acceptable only on locks via the established
  poison-recovery idiom `lock().unwrap_or_else(|e| e.into_inner())` and in tests.
- Functions returning fallible results return `Result<T, EngineError>` (§5).
  `Box<dyn Error>`, `anyhow`, and `String` error types are banned in the engine.
- Blocking I/O never runs on an async executor thread or the Tauri main thread;
  engine work runs on engine-owned threads (§6) or `spawn_blocking`.
- Every public item in the engine carries a doc comment stating its contract:
  preconditions, failure modes (by error code), and threading assumptions.

## 3. TypeScript / Astro standards

- `npm run check` (tsc, strict) and `npm run build` are hard CI gates.
  Known limit, stated honestly: tsc does not parse `.astro` files, so the page
  script is verified by build + review until `@astrojs/check` is adopted by a
  decision record. New logic therefore lives in `src/lib/*.ts` modules, which
  tsc *does* check — not in the `.astro` inline script.
- No `any` without a comment; transport payloads are typed against the DTO
  interfaces in `src/lib/api.ts`, which mirror `engine/src/dto.rs` field-for-field.
- The frontend never constructs backend URLs, poster sources, or command names
  outside `api.ts`. Rendering keys off load/error events and DTO fields only —
  never off parsing a URL or an error string.
- DOM updates for high-frequency state (download progress) must patch in place;
  full re-renders of a list on a progress tick are a defect.
- Accessibility is part of done: focus-visible states, aria state attributes
  kept in sync by the same code that changes visual state, and no interactive
  element that is not keyboard-reachable.

## 4. Kotlin / Android standards

- The Android app is preserved. Changes are limited to parity fixes, hygiene
  items approved by Squad 9 review, and migration-safety work. No architectural
  rewrites to match the Rust side.
- Kotlin coroutines: no `GlobalScope`; work belongs to the coordinator/service
  scopes that already own it. Cancellation must remain call-level (socket
  cancelled), never flag-polled across a blocking read.
- Room: migrations are explicit, sequential, and non-destructive (§8). Every
  schema version ships its exported schema JSON in `app/schemas/`.
- Android errors map to the same taxonomy codes as the engine (§5) at the UI
  boundary, so identical failures read identically on all three platforms.

## 5. Error-handling convention

### 5.1 The type

All engine failures are one hand-rolled enum (no new dependency). Codes are
stable forever: a code, once shipped, is never reused or renamed.

```rust
pub struct EngineError {
    pub code: ErrorCode,        // stable, machine-readable, SCREAMING_SNAKE
    pub retryable: bool,        // may an automatic or one-tap retry succeed?
    pub message: String,        // developer detail; NEVER shown raw to users
    pub source_detail: Option<String>, // underlying OS/HTTP detail, for logs
}
```

`ErrorCode` is serialized to the frontend as a string. The DTO keeps the
existing `error: Option<String>` field (user-ready message, §5.3) and gains
`error_code: Option<String>` and `error_retryable: Option<bool>` — additive,
so RC6-era frontends keep working.

### 5.2 Taxonomy

| Code | Meaning | Retryable | User message template |
|---|---|---|---|
| `PROBE_FAILED` | Initial probe could not characterize the URL | yes | "Couldn't reach the file. Check the server, then try again." |
| `NET_TIMEOUT` | Connect/read exceeded its bounded timeout | yes | "The server took too long to respond." |
| `NET_UNREACHABLE` | DNS failure, refused connection, host down | yes | "Server unreachable." |
| `NET_HTTP_403` | Forbidden | no | "The server refused access to this file (403)." |
| `NET_HTTP_404` | Not found | no | "This file no longer exists on the server (404)." |
| `NET_HTTP_416` | Range not satisfiable, unrecoverable case | no | "The server can't resume this download." |
| `NET_HTTP_429` | Rate limited | yes (after `Retry-After`) | "The server is busy. Retrying shortly…" |
| `NET_HTTP_5XX` | Server error | yes | "The server had a problem ({status})." |
| `RANGE_UNSUPPORTED` | Server claims ranges but answers 200 to a range GET | yes (single-stream restart) | "This server doesn't support resuming; restarting from the beginning." |
| `RANGE_INVALID` | 206 received with missing/mismatched `Content-Range` | no | "The server sent a corrupt response. Download stopped to protect the file." |
| `FILE_CHANGED` | Validator (ETag/Last-Modified) or size changed mid-download | yes (full restart) | "The file changed on the server. Restarting the download." |
| `SIZE_MISMATCH` | Received bytes ≠ expected total at completion check | no | "The downloaded file is incomplete or damaged." |
| `PARTIAL_MISSING` | `.part` file absent or shorter than persisted progress | yes (re-plan) | "Part of the download was lost; recovering what's possible." |
| `DISK_FULL` | Write failed with ENOSPC | no | "Not enough disk space to continue this download." |
| `DISK_IO` | Other local write/read failure | no | "Couldn't write to disk. Check the download folder." |
| `STORE_CORRUPT` | Persisted state unreadable and backup exhausted | no | "Saved data was damaged and has been reset." |
| `SETTINGS_INVALID` | Settings failed validation | no | "That setting isn't valid." |
| `CANCELLED` | User or system cancelled | no | (no message; state only) |
| `INTERNAL` | Anything unclassified — a bug by definition | no | "Something went wrong inside LanFetch." |

A failure that reaches `INTERNAL` in a release build is filed as a defect
against the taxonomy, not shrugged at.

### 5.3 Rules

- User messages come only from the template column, with `{status}`-style
  substitutions. Raw OS/HTTP text goes to `source_detail` and logs.
- HTTP status failures keep their numeric status in `source_detail`; codes
  `NET_HTTP_5XX` cover 500–599 as a class, other statuses get their own code
  or `NET_HTTP_4XX`-style class codes by decision record.
- Retry policy reads `retryable` and the code — never message text. 429 and
  5xx retry with exponential backoff + jitter and honor `Retry-After`.
- Every state transition to Failed records code + message at the transition
  site; the UI never reconstructs an error from state.
- Tauri commands return `Result<T, EngineError>` serialized as
  `{code, retryable, message}`; the web API maps codes to HTTP statuses
  (4xx-class codes → their status, `NET_*` transport failures → 502,
  `DISK_*`/`STORE_*`/`INTERNAL` → 500) with the same JSON body shape.

## 6. Concurrency policy

- **Lock set and order.** `Manager` owns exactly three mutexes, always acquired
  in this order and never in reverse: `tasks` → `settings` → `notifier`.
  A thread holding one may take a later one; it must never take an earlier one.
  New shared state joins this list by decision record, at the end.
- **No lock is held across I/O** — network, disk, or another thread's join.
  The pattern is: lock, snapshot or mutate, drop, then act. (Wave 0 found
  saves racing on one fixed `.tmp` path; see next rule.)
- **Save serialization.** All persistence writes go through a single save path
  guarded by a dedicated save mutex, so `write_atomic` calls for the same file
  can never interleave. Saves are debounced/coalesced by the manager, and the
  atomic write ends with `sync_all` on the file before rename.
- **Thread ownership.** Every spawned thread has a named owner responsible for
  its exit: the probe thread (owned by `enqueue`, bounded by probe timeouts),
  the per-task supervisor (owns its segment workers and joins them), and the
  ticker (swap-guarded singleton, stopped-flag shutdown). Detached threads
  that outlive their owner are defects. Shutdown stops the ticker, flags
  workers, and joins supervisors before process exit.
- **One writer per file.** A `.part` file is written by exactly one task's
  workers, at disjoint segment offsets, coordinated by the supervisor.
  Duplicate enqueue of a URL already Queued/Running/Paused returns the
  existing task — enforced inside one lock scope, not check-then-act.
- **Events, not polling.** State changes push through the notifier/SSE seam;
  progress ticks are coalesced (≤1/second/task at the UI boundary) and stale
  stamps are dropped by consumers.
- Lock poisoning is recovered with `into_inner()` and treated as a bug signal:
  the recovering site logs the task/state involved.

## 7. Testing standards

- **Engine unit tests** live beside the code. Every state-machine transition
  (legal and illegal) gets a table-driven test; Wave 0's baseline — exactly
  one test in `manager.rs`, covering only the event stamp — is the floor V2
  must never return to.
- **Engine integration tests** run against a local in-process HTTP fixture
  server (test-only code, never shipped). Required scenarios mirror Part 5 of
  the V2 brief: plain/range/range-lying servers, mid-transfer disconnect,
  wrong `Content-Range`, changed validator, 403/404/416/429/5xx, disk-full
  (via a capped temp dir), crash-and-reload of persisted state. Each test
  asserts final bytes or the exact error code — never just "did not panic".
- **Persistence tests** include golden files: state written by the previous
  release must load losslessly into the current build (§8).
- **Web API tests** exercise the real server on an ephemeral port: routing,
  auth rejection, body limits, and one full download lifecycle per release.
- **Frontend**: `src/lib/*.ts` modules get unit tests when they carry logic
  (formatting, state derivation). The `.astro` page script is exempt only
  while tsc cannot check it, and that exemption is re-examined each wave.
- **Android**: existing JVM tests keep passing; engine changes require the
  matching `DownloadEngineTest`-style local-server test; every new Room
  version ships a migration test against its exported schema.
- **No test may be disabled, ignored, or deleted to reach green.** A failing
  test blocks the wave; the fix is in the code or in a reviewed test change
  whose commit message says why the expectation was wrong.
- CI is the only verification environment (standing user order): no claim of
  "tested" from a local run. Benchmarks measured in CI are reported as
  indicative, never as precise timings.

## 8. Versioning and migration rules

- **Versions.** Desktop/web editions share one version (currently 1.6.0) kept
  identical in `package.json`, both crate manifests, and the Android
  `versionName` line is independent but never silently drifted — a bump is
  one commit touching every surface.
- **Serde defaults are mandatory.** Every field of every persisted struct
  (`Settings`, `PersistedTask`, sources, favorites) carries `#[serde(default)]`
  or a named default function. A field without one fails review. (Today only
  two `Settings` fields comply; Wave 2 brings the rest under this rule.)
- **Additive-only schema evolution.** New fields are optional-with-default;
  fields are never removed or retyped in place; enum variants (including
  `DlState`) are never renamed or reordered — new variants append, and old
  files containing unknown variants must fail soft to a recoverable state,
  never to an empty queue.
- **Format changes ship with**: the serde defaults, a golden-file load test
  using the previous format, and a CHANGELOG entry naming the change.
- **Android Room** follows the same law: explicit migrations only, no
  destructive fallback, schema JSON exported per version.
- **API/IPC evolution.** New commands and endpoints are additive; the web API
  is versioned (`/api/v1/…`) and a version, once shipped, keeps its contract
  until a decision record retires it with a compatibility adapter.

## 9. Quality gates — brief G1–G10 as concrete checks

| Gate | Concrete check in this repo |
|---|---|
| G1 Architecture reviewed | Decision record merged in `docs/ARCHITECTURE-DECISIONS/` for any new module boundary, dependency, or contract change |
| G2 Implementation complete | Wave checklist in the PR/commit body: every item implemented or explicitly deferred with a named wave |
| G3 Type & compile checks | `cargo clippy --locked --workspace -- -D warnings`, `npm run check`, Android compile — all green in CI |
| G4 Unit tests | `cargo test --locked --workspace` and Android JVM tests green; new logic ships with new tests (§7) |
| G5 Integration tests | Engine fixture-server suite + web API suite green in CI |
| G6 Regression tests | The Wave 0 defect register: each fixed defect has a named regression test that fails on the pre-fix code |
| G7 Security review | Web/auth changes reviewed against `docs/SECURITY.md`; no unauthenticated state-changing route reachable in `--lan` mode |
| G8 Performance verified | Before/after CI benchmark notes for engine throughput and pause latency; no unbounded threads, bodies, or queues introduced |
| G9 UI behavior verified | Frontend build + responsive guardrails green; behavior claims verified by executed tests or the human QA checklist — stated as which |
| G10 Integration director approves | Chief integrator verifies the CI verdict at job and step level for the exact run before the wave is declared done |

## 10. Commits and review

- One wave = one single-purpose commit = one CI cycle. Waves land serially;
  the next wave starts only after the previous CI verdict is verified.
- Commit messages state the wave, the behavior change, and the tests added.
- Any agent may block a merge on a standards violation by citing the section
  number. The integration director arbitrates; the arbitration is recorded
  in the wave's decision notes.
