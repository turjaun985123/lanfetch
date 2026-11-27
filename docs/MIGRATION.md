# Migration & Data Compatibility — LanFetch V2

This document is the contract for how existing user data survives the move
from the 1.6.0 line (RC1–RC6, desktop + web) and the Android 1.3.x line to
V2. The rule is absolute: **no V2 change may silently discard, reset, or
fail to parse data written by any shipped RC.** Where a format must grow,
it grows additively; where a parse fails, the previous behaviour (`.bak`
fallback, then documented defaults) applies — never an empty queue or a
wiped settings file presented as normal.

## 1. Desktop / web state files (shared Rust engine)

Desktop (Tauri) and web (`lanfetch-web`) use the *same* engine and the
*same* files, so one migration story covers both editions. All state lives
in `~/.local/share/lanfetch/`; downloads land in `~/Downloads/LanFetch/`
as `<name>.part`, renamed to the final name on completion; poster cache
lives in `~/.cache/lanfetch/posters/` (content-sniffed, self-healing,
200 MB oldest-first cap — no migration needed, ever: it is a cache).

| File | Written by | V2 treatment |
|---|---|---|
| `downloads.json` | `Vec<PersistedTask>` — `id, url, name, total, validator, segments[{start,end,done}], state, error` | **Format unchanged.** New persisted fields, if any, are added only with `#[serde(default)]`. The five `DlState` values keep their exact serialized names; the V2 internal state machine maps onto them (see §4). |
| `settings.json` | `Settings` — `connections, max_concurrent, theme` (required today), `language`, `onboarding_done` (already `#[serde(default)]`) | **Extended only.** V2 adds `#[serde(default)]` to the three currently-required fields so a file missing any one of them can never again fail the whole parse and reset *all* settings. |
| `sources.json` | `Vec<SourceItem>` — `name, root_url, enabled, custom` | **Unchanged.** First-run seeding (5 DhakaFlix seeds) still happens only when the file is absent/empty; an existing user list — including disabled seeds and custom servers — is never re-seeded or re-ordered. |
| `favorites.json` | `Vec<String>` of folder URLs | **Unchanged.** |
| `favorite_titles.json` | `Vec<FavTitle>` display snapshots | **Unchanged in V2.0.** Any future field is defaulted; a snapshot file that fails to parse degrades the Favorites grid only, never the queue. |

Every file above is written through `write_atomic` (stage `.tmp` → snapshot
current file to `.bak` → rename) and read with `.bak` fallback. V2 keeps
this mechanism; the known weakness that concurrent saves share one `.tmp`
path is fixed inside the save path (serialized saves, per ADR/standards)
without changing the on-disk layout, so `.bak` files written by RC6 remain
valid fallbacks for V2's first run.

### 1.1 In-flight downloads across the upgrade

On load, RC6 already restores `Running`/`Queued` tasks as `Paused` and
derives readiness from a non-empty segment plan. V2 preserves this and
repairs its two holes:

- **Probe-failed tasks** (no segments, previously un-retryable) restore as
  `Failed` and become retryable: V2's retry re-runs the probe instead of
  demanding a plan that was never created. No file change is required —
  the persisted shape already contains everything needed (`url`, `error`).
- **`.part` reconciliation:** before a restored task may resume or
  complete, V2 checks the actual `.part` length against the persisted
  segment progress and clamps/re-fetches any range the file cannot prove.
  A `.part` deleted while the app was closed restarts that task's plan
  instead of completing with zero-filled holes.

Progress checkpointing (periodic `save()` during a run, not only at state
transitions) changes *when* `downloads.json` is written, never its format.

## 2. Desktop upgrade path (.deb)

Unchanged from the 1.6.0 line: the package is `Package: lanfetch`,
installing `/usr/bin/lanfetch`, and it upgrades in place over any RC1–RC6
install. Package scripts do not touch `~/.local/share/lanfetch/`,
`~/Downloads/LanFetch/`, or the poster cache — user state is not part of
the package and survives install, upgrade, and removal of the package
itself. There is no updater plugin; upgrades remain a manual `.deb`
install, and release notes must say exactly that.

The web binaries are single files replaced by dropping in the new binary;
they read and write the same state directory as the desktop app, so a user
may switch between desktop and web editions on one machine without any
conversion.

## 3. Web access token (new in V2)

RC1–RC6 web mode has **no authentication**: localhost by default, and
`--lan` exposes full control to the network. V2 introduces a per-install
access token for the web API (model: ADR-003):

- On the **first V2 start** of `lanfetch-web`, if no token exists, one is
  generated (256-bit) and stored in the data directory as `web-token`
  with owner-only permissions. `settings.json` from RC6 parses unchanged.
- In `--lan` mode the token is **required on every `/api/*` call**; the
  startup banner prints the pairing URL (`http://<host>:<port>/#t=…`)
  once. Existing `--lan` users need to do nothing in advance; they open
  the printed URL once on their phone.
- Loopback mode is unchanged in shape: no token required, with a new
  `Host` allowlist check closing the DNS-rebinding hole.
- The desktop app shows the same token in Settings (masked, behind a
  reveal) and can regenerate it; regeneration invalidates the old token
  immediately.
- A user who loses the token deletes the `web-token` file (or uses the
  desktop app's regenerate button); the next web start issues and prints
  a fresh one. No data is tied to the token — losing it locks nobody out
  of their downloads, settings, or files.

## 4. State names across versions

The public DTO keeps the five RC6 state strings (`queued`, `running`,
`paused`, `completed`, `failed`). V2's richer internal lifecycle
(probing, retrying, verifying, …) is exposed only through additive DTO
fields, never by renaming or removing existing values, so RC6-era
frontends, scripts, and saved data keep their meaning.

## 5. Android

**Room v9 is untouched by V2.** No schema change, no new migration, no
destructive fallback is introduced. The registered chain (3→4→5→6→7→8→9)
and existing user databases open exactly as in 1.3.x. Two pre-existing
hygiene items are recorded, not silently "fixed" by V2: installs older
than schema v3 have no migration path (and no destructive fallback) in
the shipped app, and `app/schemas/` contains exported schemas 4–8 only —
`9.json` was never committed, so the 8→9 step cannot be exercised by
`MigrationTestHelper` until that file is generated and committed under
Squad 9 review. Android settings (DataStore) and the downloads directory
are likewise unchanged; the Android app needs no data migration for V2.

## 6. Rollback

- **Desktop/web:** installing an RC6 `.deb` (or an older web binary) over
  V2 is safe for all files in §1 *provided* V2 has only added defaulted
  fields — serde ignores unknown fields on the way back, and the five
  state names are unchanged, so RC6 reads V2-written files. The one
  caveat: a task V2 restored and re-probed keeps its (valid) segment
  plan; no V2-only state is ever required to interpret the file.
- **Web token:** rolling back to RC6 removes token enforcement (RC6 has
  none) — the `web-token` file sits inert in the data directory. Users
  rolling back *for security reasons* should note the rollback itself
  re-opens the unauthenticated `--lan` exposure; the supported secure
  rollback is to run the RC6 binary without `--lan`.
- **Android:** V2 ships no Android schema change, so APK rollback is a
  plain install of the previous signed build, data intact.
- **Backups:** before the first V2 run, the engine's existing `.bak`
  snapshots are the per-file safety net. Users who want a full manual
  backup copy `~/.local/share/lanfetch/` — that directory, plus
  `~/Downloads/LanFetch/`, is the entire user state.
