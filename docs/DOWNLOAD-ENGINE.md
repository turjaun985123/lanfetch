# LanFetch Download Engine

Normative contract for the Rust download engine (`desktop-tauri/engine/src/manager.rs`,
`desktop-tauri/engine/src/core.rs`). Wave 2 implements it; the engine test
suite is written against the numbered clauses. Terms: a **task** is one queued
download (`Task` in `manager.rs`); a **plan** is its segment list
(`Vec<Segment>`, each `start`/`end` inclusive, `end == u64::MAX` meaning
open-ended); a **checkpoint** is one atomic persist of `downloads.json` (§6).

Where the spec mandates behavior the pre-V2 code does not have, the clause is
marked **[Wave 2 change]** so implementers and testers can tell contract from
status quo.

## 1. Internal state machine

The engine has eleven internal states. Only five of them are public (§2).
See ADR-004 for why.

| State | Meaning | Plan? | Workers? |
|---|---|---|---|
| `Queued` | Task exists, no probe started yet | no | no |
| `Probing` | `probe_url` in flight (HEAD, falling back to `Range: bytes=0-0`) | no | probe thread |
| `Ready` | Probe succeeded, plan built, waiting for a concurrency slot | yes | no |
| `Running` | Transfer supervisor live, ≥1 segment worker active or in stall-backoff | yes | yes |
| `Pausing` | Pause requested, workers not yet unwound | yes | exiting |
| `Paused` | Workers exited, plan + `done` counts checkpointed, resumable | yes | no |
| `Retrying` | Retry of a `Failed` task accepted, re-probe pending | stale | no |
| `Verifying` | All segments report complete; completion checks (§5) running | yes | supervisor only |
| `Completed` | Verified, fsynced, renamed to the final file name. Terminal | yes | no |
| `Failed` | An operation failed; `error` is set. Always retryable (§3) | maybe | no |
| `Cancelled` | User removed the task. Terminal; the record is dropped | — | exiting |

Legal transitions — any transition not listed here is a bug:

| # | From → To | Event | Guard | Side effects |
|---|---|---|---|---|
| T1 | ∅ → `Queued` | `enqueue(url, name)` | No live task with the same URL in `Queued/Probing/Ready/Running/Pausing/Paused`. If a `Failed` task owns the URL, T14 applies to it instead of creating a duplicate; if a `Completed` task owns it, a new task is created (re-download) | Task persisted; probe scheduled |
| T2 | `Queued → Probing` | Probe worker starts | Task not `Cancelled` | — |
| T3 | `Probing → Ready` | Probe returns `Ok` | Plan built: ranges accepted → `plan_segments(total, connections)`; known total without ranges → one segment `[0, total-1]`; unknown total → one open segment `[0, ∞)` | `total`, `validator` (ETag else Last-Modified), plan stored; checkpoint |
| T4 | `Probing → Failed` | Probe returns `Err` | — | `error` set; plan stays empty; checkpoint; failure notification |
| T5 | `Ready → Running` | `pump()` | Running count < `max_concurrent` (settings, min 1); FIFO by task id; only `pump()` may make this transition | `pause_flag` cleared; `error` cleared; speed clock reset |
| T6 | `Running → Pausing` | `pause(id)`, `remove(id)`, or shutdown | State is `Running` | `pause_flag` set; UI stamp bumped immediately |
| T7 | `Pausing → Paused` | All segment workers joined, neither finished nor failed | — | Segment `done` counts harvested from workers; checkpoint |
| T8 | `Running → Verifying` | Supervisor exit | Every segment `remaining() == 0` and the failed flag is unset | Enters §5 checklist |
| T9 | `Running → Failed` | Supervisor exit with failed flag set | Failure classes: HTTP status error, stall exhaustion (§3), write error, `416` with `remaining() > 0`, `200` on a ranged/multi-segment request, validator drift | `error` = failure message; checkpoint; failure notification |
| T10 | `Verifying → Completed` | §5 checklist passes | All of §5.1–§5.5, in order | Rename already performed per §5.5; checkpoint; completion notification |
| T11 | `Verifying → Ready` | §5.4 reconciliation clamps `done` counts | `.part` shorter than claimed progress, or missing with `Σ done == 0` after reset | Clamped plan checkpointed; task resumes the missing tail via T5 |
| T12 | `Verifying → Failed` | §5 check fails irrecoverably (e.g. rename I/O error) | — | `error` set; `.part` preserved for retry |
| T13 | `Paused → Ready` | `start(id)` (resume) | State is `Paused` **and plan is non-empty**. If the plan is empty, T14 applies instead | `error` cleared; §5.4 reconciliation runs before any new request |
| T14 | `Failed → Retrying` | `start(id)` (retry) or `enqueue` of the same URL (T1) | State is `Failed`. **No other guard — in particular never a readiness or plan guard** | `error` cleared; re-probe scheduled |
| T15 | `Retrying → Probing` | Probe worker starts | Task not `Cancelled` | Re-probe outcome routes through T3/T4, modified by §3 drift rules |
| T16 | `Ready → Paused` | `pause(id)` | State is `Ready` (no workers to unwind, so no `Pausing`) | Checkpoint |
| T17 | `Queued/Probing → Paused` | `pause(id)` | Pause intent recorded; a running probe is allowed to finish, then: probe `Ok` → `Paused` with the plan stored (never auto-started); probe `Err` → T4 (`Failed` wins over pause intent) | Checkpoint on landing |
| T18 | any non-terminal → `Cancelled` | `remove(id)` | None | `pause_flag` set; record dropped and checkpointed; `.part` deleted iff `delete_part`; exiting workers must not rename, checkpoint, or resurrect the file (the supervisor re-checks task existence and state before any rename) |
| T19 | `Completed/Failed →` dropped | `clear_completed()` / `remove(id)` | — | Record dropped; checkpoint |

Invariants (testable at every checkpoint):

- **SM-1** At most one probe and one transfer supervisor per task at any moment.
- **SM-2** `downloaded == Σ segments.done`, and `done` never decreases except by plan reset (§3) or reconciliation clamp (§5.4).
- **SM-3** Segments are disjoint, ordered, and cover `[0, total)` when the total is known.
- **SM-4** `error.is_some()` ⟺ state is `Failed`.
- **SM-5** No state is a dead end except `Completed` and `Cancelled`; in particular `Failed` accepts T14 from every plan condition, including an empty plan. (Regression clause for the shipped defect in which `start()` early-returned on `!ready` and a probe-failed task could never be retried.)
- **SM-6** Segment-level stall retries (§3) happen inside `Running` and never change the task state.

## 2. Mapping to the public DTO states

`TaskDto.state` (`dto.rs`) and `FileDto.download_state` expose exactly five strings. The mapping is total and many-to-one:

| Internal | DTO `state` |
|---|---|
| `Queued`, `Probing`, `Ready`, `Retrying` | `"queued"` |
| `Running`, `Pausing`, `Verifying` | `"running"` |
| `Paused` | `"paused"` |
| `Completed` | `"completed"` |
| `Failed` | `"failed"` |
| `Cancelled` | — (task absent from `DownloadListDto.tasks`) |

- **MAP-1** Clients must treat the state string, never `fraction`, as authoritative: a task in `Verifying` legitimately shows `fraction == 1.0` with state `"running"`.
- **MAP-2** `TaskDto.ready` remains on the wire for compatibility and is **derived**: `ready := !segments.is_empty()`. It is display metadata only. No server path and no client path may use it to gate start, retry, or scheduling. **[Wave 2 change: replaces the stored `Task.ready` gate in `start()`/`pump()`.]**
- **MAP-3** `TaskDto.fileMissing` is unchanged: derived, `true` iff the task is `Completed` and the final file no longer exists.

## 3. Retry contract

- **RT-1** Retry is a **full re-probe**, identical to the Android coordinator's resume: `start(id)` on a `Failed` task performs T14 → T15 and runs `probe_url` again (HEAD first, `Range: bytes=0-0` fallback). There is no "resume from stored plan" path for `Failed` tasks.
- **RT-2** Readiness is derived (MAP-2), never a gate: retry must be accepted for a task whose probe failed before any plan existed, after any number of consecutive probe failures, and after a restart (§6). A retry that silently no-ops is a contract violation.
- **RT-3** Drift check on re-probe success: compare the fresh `validator` and `total` against the stored ones. If both validators are present and differ, or the totals differ, the content is assumed changed — **plan reset**: segments replanned from the new total, every `done` zeroed, `.part` truncated to zero length, `downloaded = 0`, stored validator/total replaced; then T3 lands in `Ready`. If either validator is absent, totals alone decide. If no drift: the existing plan and `done` counts are kept and the transfer resumes byte-exactly.
- **RT-4** Drift detected mid-transfer (a `200` answering a ranged request, `If-Range` ignored, or `416` with bytes outstanding) fails the task via T9 with a file-changed error; the subsequent user retry recovers through RT-3. The engine never silently restarts a drifted transfer on its own.
- **RT-5** Re-probe failure returns the task to `Failed` (T4) with the *new* error replacing the old. Manual retries are unbounded.
- **RT-6** In-transfer segment retries are separate and bounded: a segment attempt that makes no byte progress backs off `250ms · 2^n` capped at 4 s (pause-responsive, §4) and fails the segment — and via T9 the task — after 7 consecutive no-progress attempts. Byte progress resets the counter.
- **RT-7** Resume of a `Paused` task (T13) does *not* re-probe; the stored validator guards the transfer via `If-Range` on every segment request, and drift surfaces as RT-4.

## 4. Pause contract

- **PA-1** `pause(id)` sets the task's `pause_flag` and performs T6 synchronously; it returns immediately and never blocks on workers.
- **PA-2** Workers observe the flag at the top of every request loop, at the top of every read loop, and in ≤50 ms slices during stall backoff.
- **PA-3 — Honest latency bound.** Pause is *not* instantaneous and is never documented as such. Worst-case time from `pause(id)` to `Paused` is **one in-flight read plus one chunk write**: a worker blocked inside a single socket read cannot see the flag until that read returns or times out. With the transfer path's per-read timeout at its Wave 2 value of **5 s**, the bound is ≈5 s on a completely stalled server, and one 256 KiB chunk (~milliseconds) on a healthy one. **[Wave 2 change: the shared agent's `timeout_read` is 30 s today (`core.rs`, `http_agent`); the transfer path's per-read timeout is bounded to 5 s so PA-3's number holds.]** Until all workers join, the task is `Pausing` and the DTO still reports `"running"`.
- **PA-4** Byte accounting stays truthful while pausing: `done` increments only after a successful `write_all_at`, so bytes that land between the flag being set and the worker exiting are counted, and `downloaded` is frozen from the moment T7 lands.
- **PA-5** `pause(id)` is idempotent and a no-op in `Paused`, `Failed`, and `Completed`. While `Pausing`, `pump()` must not relaunch the task and `start(id)` is a no-op.

## 5. Completion contract

A task may enter `Completed` only by passing every check in `Verifying`, in this order:

- **CP-1 Byte counts.** Every segment has `remaining() == 0`, with `done` credited exclusively from successful `write_all_at` returns — never from bytes read, bytes requested, or connection close. For a known total, `Σ done == total`. A clean connection close is evidence of nothing.
- **CP-2 Content-Range validation.** **[Wave 2 change: not validated today.]** Every `206` response must carry a `Content-Range` whose start equals the requested range start and whose end equals the requested end (for an open segment, the served end). A mismatched or missing `Content-Range` fails that segment attempt (RT-6 retry; persistent mismatch fails the task via T9) and none of its bytes are written. A `200` response is accepted only for a single-segment plan at `start == 0` with `done == 0` (whole-body answer, including the `If-Range`-drift case); on any other plan it is T9 file-changed.
- **CP-3 Unknown totals.** For the single open segment, a clean EOF ends the file: `end := start + done − 1`, `total := done`, after which CP-1 applies with that total.
- **CP-4 `.part` reconciliation.** At `Verifying` entry — and before the first request of any resumed or restored task (T13, §6) — the actual `.part` file is ground truth, not the persisted plan. Let `L = len(.part)`. If the file is missing: all `done` counts reset to 0 and the task returns to `Ready` (T11), unless `Σ done` was already 0. If `L < Σ done`: walk segments in order; the first segment whose cumulative `done` exceeds `L` is clamped to the bytes actually present, all later segments are zeroed, and the task returns to `Ready` (T11) — a holed file can never reach `Completed`. If `L > total` (known total), truncate to `total`.
- **CP-5 Durability ordering.** On pass: truncate `.part` to `total` → flush → **`sync_all` the `.part`** → rename `.part` to the final name → best-effort `sync_all` of the containing directory → only then transition to `Completed`, checkpoint, and notify. **[Wave 2 change: today the tail is flushed without `sync_all` and the rename result is discarded (`let _ =`).]** A rename failure is T12: state `Failed`, error set, `.part` preserved.
- **CP-6** No cryptographic checksum is verified: the catalog servers publish none. Completion means byte-count- and range-exact against the server's own validator, and documentation must not claim more.

## 6. Recovery contract

- **RC-1 Checkpoint cadence.** `downloads.json` is written atomically (temp file + rename, previous copy kept as `.bak`, per `store::write_atomic`) on: (a) every state transition in §1; (b) every segment completion; and (c) a periodic tick of **≤5 s** while any task is `Running`. **[Wave 2 change: today only transitions checkpoint, so a crash loses all progress since the last transition.]** All checkpoint writes are serialized through a single lock — concurrent saves must never share one temp path. **[Wave 2 change: closes the concurrent-`save()` temp-file race.]**
- **RC-2 Loss bound.** After a crash or power loss, re-downloaded bytes are bounded by what arrived since the last checkpoint (≤5 s of transfer) plus whatever CP-4 clamps away. Progress is never *over*-claimed after recovery: CP-4 runs before any restored task moves a byte.
- **RC-3 Restore mapping** (`Manager::load`). Persisted internal states map back as:

  | Persisted | Restored as |
  |---|---|
  | `Completed` | `Completed` |
  | `Failed` | `Failed` (error preserved; T14 retry available immediately) |
  | `Paused`, `Ready` | `Paused` (plan kept) |
  | `Running`, `Pausing`, `Verifying` | `Paused` (plan kept; a restored `Verifying` re-verifies via CP-4/CP-1 on next start) |
  | `Queued`, `Probing`, `Retrying` | `Paused` if a plan exists, else `Failed` with error "interrupted before the download started" |
  | `Cancelled` | never persisted |

  Restored tasks come back with `pause_flag` set, `downloaded = Σ segments.done`, and `next_id` advanced past every restored id.
- **RC-4** A torn or corrupt `downloads.json` falls back to `downloads.json.bak`; if both are unreadable the queue starts empty. The fallback must never silently drop a parseable queue.
- **RC-5** Persistence is forward-compatible by construction: every persisted field carries a serde default, so a file written by an older build — including one from before the internal states existed, whose 5-state values land on the RC-3 rows for `Queued/Running/Paused/Completed/Failed` — loads without resetting the queue or the user's settings. **[Wave 2 change: `PersistedTask`/`Settings` gain the missing defaults.]**
