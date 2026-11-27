# LanFetch HTTP API

LanFetch has one command surface with three transports:

- **Tauri IPC** (desktop) — commands invoked by name from `src/lib/api.ts`.
- **Legacy HTTP** — `POST /api/<cmd>` with a JSON argument object, served by
  `lanfetch-web` (`web/src/serve.rs`). This is what the shipped frontend uses
  in web mode.
- **`/api/v1`** — the versioned REST contract defined here. It is a routing
  layer over the *same* dispatch and the *same* DTOs; no business logic is
  duplicated.

All request/response field names are camelCase, exactly as serialized by
`engine/src/dto.rs`. Types below are the wire types (Rust `u64`/`usize` →
JSON number, `Option<T>` → `T | null`).

## Compatibility rule (non-negotiable)

The legacy surface is **frozen**: `POST /api/<cmd>`, `GET /api/events`, and
`GET /posters/<key>` keep their current request shapes, response shapes, and
status codes (200 on success, `400 {"error": "<message>"}` on failure)
unchanged. `/api/v1` is purely additive — it is added alongside legacy, never
instead of it, and the shipped frontend keeps working on legacy until it is
deliberately migrated. Tauri IPC command names and argument shapes are
likewise unchanged; v1 exists only on the HTTP transport.

## Route map — legacy command → /api/v1

| Legacy command | /api/v1 route | Response |
|---|---|---|
| *(new in v1)* | `GET /api/v1/health` | `HealthDto` |
| `get_bootstrap` | `GET /api/v1/bootstrap` | `BootstrapDto` |
| `get_categories` | `GET /api/v1/categories?refresh=<bool>` | `CategoryDto[]` |
| `feed_open` | `POST /api/v1/feed` | `FeedPageDto` |
| `feed_next` | `GET /api/v1/feed/{generation}` | `FeedPageDto` |
| `search_titles` | `GET /api/v1/search?q=<query>` | `TitleDto[]` |
| `get_title_detail` | `GET /api/v1/titles/{folderUrl}` | `TitleDetailDto` |
| `get_downloads` | `GET /api/v1/downloads` | `DownloadListDto` |
| `start_download` | `POST /api/v1/downloads` | `DownloadListDto` |
| `pause_download` | `POST /api/v1/downloads/{id}/pause` | `DownloadListDto` |
| `resume_download` | `POST /api/v1/downloads/{id}/resume` | `DownloadListDto` |
| `redownload_download` | `POST /api/v1/downloads/{id}/redownload` | `DownloadListDto` |
| `remove_download` | `DELETE /api/v1/downloads/{id}` | `DownloadListDto` |
| `clear_completed_downloads` | `DELETE /api/v1/downloads/completed` | `DownloadListDto` |
| `pause_all_downloads` | `POST /api/v1/downloads/pause-all` | `DownloadListDto` |
| `resume_all_downloads` | `POST /api/v1/downloads/resume-all` | `DownloadListDto` |
| `probe_sources` | `GET /api/v1/sources/probe` | `SourceProbeDto[]` |
| *(via `get_bootstrap`)* | `GET /api/v1/sources` | `SourceDto[]` |
| `add_source` | `POST /api/v1/sources` | `SourceDto[]` |
| `remove_source` | `DELETE /api/v1/sources/{name}` | `SourceDto[]` |
| `set_source_enabled` | `PATCH /api/v1/sources/{name}` | `SourceDto[]` |
| `update_settings` | `PUT /api/v1/settings` | `SettingsDto` |
| `get_favorites` | `GET /api/v1/favorites` | `TitleDto[]` |
| `toggle_favorite` | `POST /api/v1/favorites/toggle` | `boolean` |
| `poster_srcs` | `POST /api/v1/posters` | `string[]` |
| `check_update` | `GET /api/v1/update` | `string \| null` |
| `GET /api/events` (SSE) | `GET /api/v1/events` | event stream |

Notes on individual routes:

- **`GET /api/v1/health`** → `{"status":"ok","appVersion":"<semver>","apiVersion":1}`.
  Never requires auth (see below); safe for connectivity checks.
- **`POST /api/v1/feed`** body: `{"category": string | null}` (`null` = the
  merged all-sources feed). Opens a feed and returns its first page.
  **`GET /api/v1/feed/{generation}`** returns the next page of that feed. If
  the generation was superseded by a newer feed open, the page has
  `"superseded": true` and an empty `titles` array — drop it, never render it.
- **`GET /api/v1/titles/{folderUrl}`** — `folderUrl` is the title's folder
  URL, percent-encoded as a single path segment.
- **Download mutations all return the full `DownloadListDto`**, exactly as
  the legacy commands do; clients replace their task list wholesale and use
  `stamp` to discard stale snapshots. `DELETE /api/v1/downloads/{id}` also
  deletes the partial file when the task is not completed (legacy semantics).
  `DELETE /api/v1/downloads/completed` must be matched before `/{id}`.
- **`PUT /api/v1/settings`** takes a full `SettingsDto`. The server clamps
  `connections` to 1–32 and `maxConcurrent` to 1–16 (legacy behavior) and
  returns the settings as applied.
- **`POST /api/v1/favorites/toggle`** body: `{"folderUrl": string}`; returns
  the new favorite state as a bare JSON `boolean`.
- **`POST /api/v1/posters`** body: `{"urls": string[]}`; returns one src per
  input URL, in order. In web mode these are `/posters/<key>` paths; the
  bytes are served by `GET /posters/<key>` (unchanged, outside `/api/v1`)
  with the sniffed image content type, 404 for unknown keys.
- **`GET /api/v1/update`** returns the newer desktop version string, or
  `null` when up to date or the check could not be performed.

## Events (SSE)

`GET /api/v1/events` (and legacy `GET /api/events`) is a Server-Sent Events
stream carrying the download manager's change stamp:

```
data: 128

: ping
```

- Each `data:` message is the bare stamp (a monotonically increasing
  integer), identical to the Tauri `downloads-changed` event payload. It is
  a *notification*, not a snapshot: on receipt, fetch
  `GET /api/v1/downloads` and discard the result if its `stamp` is older
  than the newest stamp already seen.
- A `: ping` comment is sent every 20 s as a keepalive.
- The stream has no terminal event; clients reconnect on close
  (`EventSource` does this automatically).

## Data shapes

```jsonc
// SettingsDto
{ "connections": 8, "maxConcurrent": 3, "theme": "system",
  "language": "en", "onboardingDone": true }

// SourceDto
{ "name": "Server 1", "rootUrl": "http://172.16.50.7/", "enabled": true,
  "custom": false }                       // custom=false: a seeded source

// SourceProbeDto — status: "up" | "down" | "disabled"
{ "name": "Server 1", "rootUrl": "http://172.16.50.7/", "status": "up",
  "latencyMs": 42 }                       // latencyMs null unless "up"

// CategoryDto — sources: names of the sources carrying the category
{ "name": "Movies", "sources": ["Server 1", "Server 2"] }

// TitleDto — kind: "movie" | "series"
{ "folderUrl": "http://…/Movies/Example/", "sourceName": "Server 1",
  "category": "Movies", "title": "Example", "year": 2024, "kind": "movie",
  "posterUrl": "http://…/poster.jpg", "language": "EN", "seasonCount": 0,
  "bestQuality": "1080p", "copies": 1, "isFavorite": false }

// FeedPageDto
{ "generation": 3, "titles": [TitleDto], "hasMore": true,
  "superseded": false }

// FileDto — size is -1 when the server did not report one;
// downloadState/downloadFraction describe a task for this exact file URL
{ "fileName": "Example.2024.1080p.mkv", "fileUrl": "http://…/Example.mkv",
  "size": 734003200, "quality": "1080p", "sourceTag": null, "codec": "x264",
  "audio": "AAC", "season": null, "episode": null,
  "downloadState": "paused", "downloadFraction": 0.42 }

// TitleDetailDto
{ "title": TitleDto, "files": [FileDto] }

// SegmentDto — end is null for the open-ended final segment of an
// unknown-size file
{ "start": 0, "end": 367001599, "done": 154000000 }

// TaskDto — state: "queued" | "running" | "paused" | "completed" | "failed"
// ready: probe finished and segments planned; fileMissing: completed but
// the file was moved/deleted from the downloads folder
{ "id": 7, "url": "http://…/Example.mkv", "name": "Example.mkv",
  "state": "running", "total": 734003200, "downloaded": 308000000,
  "fraction": 0.42, "speedBps": 1250000, "etaSecs": 341, "error": null,
  "ready": true, "fileMissing": false, "segments": [SegmentDto] }

// DownloadListDto
{ "stamp": 128, "tasks": [TaskDto], "activeCount": 1 }

// BootstrapDto
{ "appVersion": "1.6.0", "settings": SettingsDto, "sources": [SourceDto],
  "favorites": ["http://…/Movies/Example/"], "downloads": DownloadListDto,
  "downloadsDir": "/home/user/Downloads/LanFetch",
  "dataDir": "/home/user/.local/share/lanfetch" }
```

## Errors

Legacy errors keep their frozen shape: HTTP 400 with `{"error": "<message>"}`.

All `/api/v1` errors use the structured shape:

```json
{ "error": { "code": "not_found", "message": "no such download: 7",
             "retryable": false } }
```

| HTTP | `code` | Meaning | `retryable` |
|---|---|---|---|
| 400 | `bad_request` | Malformed JSON or a missing/invalid argument | false |
| 401 | `unauthorized` | Missing or invalid bearer token (see Auth) | false |
| 404 | `not_found` | Unknown route, download id, source name, or poster key | false |
| 400 | `unknown_command` | Legacy dispatch reached with an unrecognized command | false |
| 502 | `upstream_unavailable` | A source server could not be reached or probed | true |
| 500 | `internal` | Anything else; `message` is human-readable, never a stack trace | false |

`message` is the same human-readable string the legacy surface returns
today. `retryable` is advisory: it tells the client whether repeating the
identical request can succeed without changing anything.

## Auth

The web server's bind mode decides the trust boundary (see ADR-003 for the
full model):

- **Loopback bind (default, `127.0.0.1`):** no token. A `Host` allowlist
  check (`127.0.0.1`, `localhost`, `[::1]`) blunts DNS rebinding.
- **Non-loopback bind (`--lan`):** a bearer token is **required on every
  `/api/*` route** — v1 and legacy, reads and writes — presented as:

  ```
  Authorization: Bearer <token>
  ```

  The only exemptions are `GET /api/v1/health` and the static UI assets /
  `GET /posters/<key>` (keys are unguessable content hashes; poster bytes
  are not sensitive).

Rules for the token:

- Generated by the server (256-bit, OS CSPRNG), stored in the app data dir
  as `web-token` with owner-only permissions, shown to the user at `--lan`
  startup (as a pairing URL whose `#t=` fragment carries it) and in
  Settings; it is never written to logs and never accepted in a URL query
  string — with one exception:
- **SSE exception:** `EventSource` cannot set headers, so the events routes
  accept the token via the `token` query parameter in non-loopback mode.
  This is the *only* query-string acceptance.
- Compared in constant time; failures return `401` with the v1 error shape
  on v1 routes and `401 {"error": "unauthorized"}` on legacy routes.
- The web page stores the token in `sessionStorage` after pairing and
  attaches it to every API call; a "Reset web access token" action in
  Settings regenerates it and invalidates paired browsers.
- The token gates *control*, not content encryption: traffic is still plain
  HTTP on the LAN. The startup banner must say so.

## Versioning policy

- The version lives in the path (`/api/v1`). Within v1, changes are
  **additive only**: new routes, new optional request fields, and new
  response fields may be added; existing fields are never removed, renamed,
  or retyped, and clients must ignore response fields they don't know.
- A breaking change ships as `/api/v2` **alongside** v1 and legacy; v1 is
  removed only in a major app release whose notes announce it, never
  silently.
- The DTOs in `engine/src/dto.rs` are the single source of truth for every
  transport. A DTO change that would alter the wire shape of an existing
  field is a breaking change by definition, on IPC and legacy HTTP too.
- `appVersion` in `BootstrapDto`/`HealthDto` is the *application* version;
  it is independent of the API version and never used for negotiation.
