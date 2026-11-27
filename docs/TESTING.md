# LanFetch — Testing

How LanFetch is tested today, what V2 adds, which CI job gates what, and —
just as important — what is **not** tested and how that gap is covered
instead. Claims in this document are limited to tests that actually run;
anything else is labelled as a plan or a known limit.

## 1. Current baseline (pre-V2, at the 1.6.0 line)

### Rust — 47 unit tests, all inside `lanfetch-engine`

| File | Tests | Covers |
|---|---|---|
| `engine/src/catalog.rs` | 24 | Title building, poster sniff/cache cap, copy merging, search ranking, category scoping |
| `engine/src/meta.rs` | 9 | Release-name parsing (year/quality/season/episode/audio) |
| `engine/src/core.rs` | 7 | Segment-planning math, filename sanitize, ETA format, listing parse |
| `engine/src/poster_service.rs` | 4 | Key parsing, source dedup, content-type sniff |
| `engine/src/store.rs` | 2 | Atomic write round-trip, corrupt-main → `.bak` fallback |
| `engine/src/manager.rs` | **1** | The event-stamp counter only — **no lifecycle transition is tested** |

There is no `tests/` integration directory in any Rust crate, and no test
HTTP server, fixture server, or failure-injection harness exists yet.

### Android — 57 JVM unit tests, 12 files

Under `app/src/test/java/com/lanfetch/app/` (MetaParser 10, DownloadEngine 7,
SegmentPlanner 7, AutoindexParser 6, H5aiJson 6, others 1–4). Notably,
`DownloadEngineTest.kt` runs a real local socket server and proves
probe-without-Accept-Ranges, segmented byte-exactness, resume with If-Range,
stale-validator handling, and cancellation of a stalled read — exactly the
behaviors the Rust engine does not yet prove. There are **no instrumented
tests** (no `app/src/androidTest/`).

### Frontend — zero automated tests

`desktop-tauri/package.json` has no test runner. `npm run check`
(`tsc --noEmit`) type-checks only `src/lib/{api,format,ui}.ts`; it cannot
parse `.astro`, so the ~2,600-line page script is neither type-checked nor
tested. The only frontend gate is the static responsive-guardrails step
(grep of compiled CSS — a tripwire, not a layout test).

### Explicitly untested today (Wave 0 findings)

- The Rust download lifecycle end to end: probe, ready gating, pause/resume/
  retry over real HTTP, segmented transfer, completion validation.
- The web server (`web/src/serve.rs`): parsing, routing, limits, LAN
  exposure behavior — 0 tests.
- Task persistence/recovery across a kill/restart with an in-flight download.
- Tauri command contracts (0 tests in `src-tauri/`); the frontend↔backend
  contract rests on shared Rust DTO types plus unchecked TS casts.
- Rendered UI behavior in any browser (see §7).

## 2. V2 test architecture — principles

1. **Test at the lowest layer that can prove the behavior.** Pure logic
   (planning, parsing, formatting) stays in unit tests; anything involving
   sockets, files, or state machines is an integration test against a real
   local server — never a mock of the engine's own internals.
2. **One in-process test HTTP server** (Rust, std-only `TcpListener` on an
   ephemeral port, scripted per test) serves every engine and web-API
   integration test. It can: serve fixed bodies, honor or ignore `Range`,
   lie about `Content-Range`, truncate mid-body, drip bytes slowly, return
   any status, redirect, and change content/validators between requests.
3. **Every acceptance criterion maps to a named test** (§3). A criterion
   without a test is reported as unverified, never as passed.
4. **Tests run in CI, not locally** (standing project rule). A test that
   only passes on a developer machine does not count.
5. **No test is disabled to reach green.** A failing test blocks the wave
   or the scenario is documented as a known limitation with the failure
   attached.

## 3. Rust engine integration tests — acceptance matrix

Each test records: environment, scripted server behavior, expected result,
actual result, downloaded size, SHA-256 of the result where applicable, and
pass/fail. Mapping to the V2 brief Part 5 acceptance criteria:

| # | Criterion | Test |
|---|---|---|
| 1 | Ordinary HTTP download | `http_plain_download_byte_exact` — full body, checksum match |
| 2 | HTTPS download | `https_download_byte_exact` — local TLS fixture with a test CA; wrong-CA variant must fail |
| 3 | Single-connection download | `single_stream_when_ranges_absent` — one connection, byte-exact |
| 4 | Segmented download | `segmented_download_byte_exact` — N ranges assembled in order, checksum match |
| 5 | Range unsupported | `range_ignored_falls_back_to_single_stream` — server answers 200 to Range requests |
| 6 | Range falsely advertised | `range_206_wrong_content_range_rejected` — 206 with mismatched `Content-Range` must fail, never complete corrupt |
| 7 | Pause/resume | `pause_then_resume_completes` — pause mid-transfer, resume, checksum match; `pause_during_stalled_read_bounded` — pause during a stalled read takes effect within the documented bound |
| 8 | Retry after failure | `retry_after_probe_failure` — first probe fails (server down), retry after server up must re-probe and complete (regression test for the Wave 0 P0 ready-flag deadlock); `retry_after_mid_download_failure` |
| 9 | Recovery after restart | `kill_and_reload_resumes_from_checkpoint` — drop the manager mid-download, reload from the store, complete byte-exact |
| 10 | Server file changed | `validator_change_restarts_safely` — ETag/Last-Modified or length changes between probe and resume → plan resets, no mixed-content file |
| 11 | Redirects | `redirect_same_host_followed`, `redirect_cross_host_rejected`, `redirect_policy_rejects_cross_host_and_downgrade` (unit; the downgrade case needs a TLS endpoint the std-only harness cannot serve) |
| 12 | Authentication failures | `status_401_403_fail_with_typed_error` — no retry loop, error kind surfaces to the DTO |
| 13 | Rate limiting | `status_429_honors_retry_after` — bounded backoff, eventually completes or fails typed, never hammers |
| 14 | Disk-full | `disk_full_fails_cleanly` — target on a size-capped tmpfs (or write-failure injection): task fails typed, `.part` state stays consistent, no false completion |
| 15 | No corrupt completions | Property across all tests above: a task may reach Completed **only** with byte count == known total and checksum == source; `part_deleted_while_closed_is_reconciled` — `.part` truncated/deleted while the app is closed → missing ranges re-fetched, never zero-filled holes |
| 16 | No duplicate workers | `duplicate_enqueue_same_url_single_task` — concurrent enqueues of one URL yield one task, one worker set |
| 17 | Bounded resources | `many_tasks_respect_concurrency_cap` — running workers never exceed `max_concurrent`; connection/thread counts asserted |
| 18 | No permanently stuck tasks | `stalled_server_eventually_fails` — drip server that stops making progress → task fails typed within the documented deadline; `pause_during_reprobe_of_planned_task_is_honored` |
| 19 | Task persistence | `store_roundtrip_preserves_tasks` — queue, segments, validators, states survive save/load; `corrupt_main_falls_back_to_bak`; `legacy_store_still_loads` — a 1.6.0-format fixture loads without dropping tasks |
| 20 | Error reporting | `errors_are_typed_not_strings` — each failure mode asserts its error kind/code, not a message substring |

Plus lifecycle unit tests for the state machine itself: every legal
transition accepted, every illegal one rejected, driven from the Wave 1
lifecycle contract.

## 4. Web API tests

Against the same in-process server harness, exercising the real
`lanfetch-web` binary (or its router in-process):

- **Contract:** every `/api/v1` route returns the documented shape and
  status codes; unknown routes/methods → 404/405 with the standard error
  body; malformed JSON → 400, never a panic or a silent `{}`.
- **Compatibility:** the legacy `POST /api/<cmd>` adapter returns identical
  payloads to the Tauri commands for the same engine state.
- **Auth (Wave 3):** state-changing routes reject missing/invalid tokens
  (401), accept the paired token, and never leak the token in logs or
  error bodies; localhost-only routes stay localhost-only under `--lan`.
- **Limits:** oversized bodies rejected at the documented cap; header
  floods and slow-loris clients are cut by the documented timeouts;
  connection count is capped.
- **Events:** SSE delivers download stamps in order, prunes dead
  subscribers, and sends keepalives.
- **Static/UI:** embedded UI serves at `/`, unknown paths fall back
  correctly, `..` and encoded traversal attempts 404.

## 5. Frontend unit tests (Vitest, pure logic only)

Scope is deliberately narrow — logic that can be proven without a browser:

- `src/lib/format.ts`: byte/speed/ETA formatting, state labels.
- Extracted pure modules as Wave 4 lands them: segment-bar geometry,
  chip/search state derivation, download-list patching decisions
  (signature computation), settings merge.
- Transport seam (`api.ts`): request shaping and error normalization for
  both IPC and HTTP modes, with the transport faked at the seam only.

DOM rendering, layout, focus behavior, and visual appearance are **not**
claimed from Vitest — see §7.

## 6. Android regression

- The existing 57 JVM tests keep running in CI unchanged; any V2-era
  Android change must keep them green.
- **Schema/migration notes (Wave 0):** Room is at v9 with a non-destructive
  3→9 chain, but there is no 1→2/2→3 path (a pre-v3 install would crash on
  open — no destructive fallback is configured) and the exported
  `9.json` schema was never committed (`app/schemas/` stops at 8), so
  8→9 is currently untestable with MigrationTestHelper. V2 hygiene:
  commit `9.json`, add a migration test for 8→9, and make an explicit,
  reviewed decision on the pre-v3 path — no silent destructive fallback.
- The Android engine tests are the behavioral reference for the Rust
  matrix in §3; where behaviors differ, the parity spec — not whichever
  implementation happens to exist — decides which side is wrong.
- No instrumented/emulator tests exist; on-device behavior (foreground
  service, notifications) remains verified by the user's own phone, as
  with the 1.3.x line.

## 7. CI gate mapping

`.github/workflows/desktop-tauri.yml`:

| Job | Runs | Gates |
|---|---|---|
| `frontend` | Version consistency, `npm ci`, `tsc --noEmit`, Astro build, static responsive guardrails; **V2 adds Vitest** | Type errors, build breaks, guardrail regressions |
| `backend` | `cargo fmt --check`, `cargo clippy --workspace -- -D warnings`, `cargo test --locked --workspace` (**V2: includes the §3 integration suite**) | Format, lints, all Rust tests |
| `bundle` | Tauri build, `.deb` packaging with Package/Version identity assert, web binary `ldd` no-GUI-libs assert | Packaging identity |
| `web-arm64` / `web-android-arm64` | Cross builds (+ engine tests where the runner supports them) | Cross-target compilation |
| `release` (tags) | Publishes the four assets after read-back verification | Release integrity |

`.github/workflows/android.yml`: `testDebugUnitTest` (the 57 tests),
`lintDebug`, `assembleDebug`; signed release APKs on `v*` tags.

A wave is done only when its CI run is verified green **at job and step
level** — never from a run-level badge or a watcher exit code alone.

## 8. Honest limits

- **No browser end-to-end or visual-regression tests.** The user has a
  standing ban on headless/screenshot CI, and it remains in force until
  they lift it explicitly. Consequence: rendered layout truth is the
  human checklist in `desktop-tauri/RESPONSIVE-QA.md` (11 viewports × 2
  themes), executed by a person on release candidates. CI guardrails are
  static tripwires and are never described as layout tests.
- **WebKitGTK is never exercised in CI.** The desktop app's real webview
  can behave differently from Chromium; only the user's own machine
  verifies it.
- **CI benchmarks are indicative only.** Shared CI runners cannot produce
  trustworthy absolute timings. Performance figures are reported as
  relative before/after comparisons on the same runner class, or as
  measurements from the user's hardware — never as precise claims.
- **ARM and Android-ARM binaries cannot execute on the x86 CI/VM hosts**
  beyond their native-runner test jobs; the Termux runtime verdict
  belongs to the user's device.
- **The page script inside `index.astro` stays outside type checking**
  until the Wave 4 module extraction moves logic into `.ts` files that
  `tsc` and Vitest can reach; adopting `@astrojs/check` is a separate,
  explicit decision.
- **Real DhakaFlix servers are unreachable from CI** (ISP-LAN only), so
  all network behavior is proven against the scripted local server; the
  final real-server verdict is the user's, on their network.
