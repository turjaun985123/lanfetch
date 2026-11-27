# LanFetch Security

This document describes LanFetch's threat model, the security findings from the
V2 Wave 0 audit, the controls V2 adopts for each of them, and the regression
tests that must keep those controls honest. It covers all three shipped
surfaces: the Tauri desktop app, the `lanfetch-web` standalone server, and the
Android app (as the behavioral reference).

LanFetch's security posture rests on one fact: **the web server can control
downloads on the host machine.** Anyone who can invoke its state-changing
endpoints can choose what gets downloaded, where partial files are deleted,
which catalog servers the app trusts, and what the settings are. Every control
below follows from treating that capability as privileged.

## Threat model

### Assets

- Integrity of downloaded files (a "completed" file must be the file the
  server actually sent).
- The host filesystem: the downloads directory, the app's data directory
  (task store, settings, sources), and the poster cache.
- The user's catalog configuration: the list of trusted server roots.
- Availability of the host process (threads, sockets, disk).
- Confidentiality of local paths and configuration, which reveal the user's
  setup to anyone who can read them.

### Actors and trust boundaries

1. **Loopback mode (default).** `lanfetch-web` binds `127.0.0.1`. The
   intended client is the user on the same machine. The boundary is weaker
   than it looks: any local process can connect, and a web page in the user's
   browser can attempt DNS-rebinding against the loopback binding. Loopback
   is *lower* trust, not *no* trust.
2. **`--lan` mode.** The server binds all interfaces so the user's phone can
   drive downloads on the host. The LAN is **not** assumed trustworthy: it
   may contain compromised IoT devices, guests, or a hostile hotspot. Every
   device that can reach the port is a potential attacker until it
   authenticates.
3. **Hostile or misconfigured catalog servers.** DhakaFlix-style h5ai
   servers are third-party infrastructure. A compromised or misconfigured
   server can serve malicious redirects, wrong `Content-Range` bodies,
   oversized listings, or slow-drip responses. The engine must treat every
   response as untrusted input.
4. **Malicious download sources.** A file URL the user (or an attacker who
   reached the API) supplies can point anywhere, change content between
   probe and download, falsely advertise Range support, or redirect to an
   unrelated host or a downgraded plaintext connection.

Out of scope: an attacker with local shell access to the host (they already
own the data directory), and compromise of the user's browser itself.

## Wave 0 findings

From the Wave 0 audit of `desktop-tauri/web/src/serve.rs`, the engine's HTTP
layer, and the Tauri shell:

| # | Finding | Severity |
|---|---------|----------|
| S1 | `--lan` mode exposes every state-changing command — start download with attacker-chosen URL/filename, pause/resume/remove, clear completed, settings writes, add/remove catalog sources — with **no authentication of any kind**. | High |
| S2 | Thread-per-connection with **no connection cap** and no semaphore; thread/memory exhaustion is trivially reachable. | Major |
| S3 | **No socket read/write timeouts** are ever set on accepted connections; a slow-loris client pins a thread forever. | Major |
| S4 | Engine redirects follow ureq defaults: up to 5 hops, **cross-host allowed, HTTPS→HTTP downgrade allowed**, cleartext accepted to any host. | High |
| S5 | `get_bootstrap` discloses absolute `downloads_dir` and `data_dir` filesystem paths to any caller. | Medium |
| S6 | Responses carry **no security headers** (no CSP, no `X-Content-Type-Options`, no cache controls outside SSE). | Minor |
| S7 | No `Host`/`Origin` validation on any route — DNS rebinding against even the loopback binding is unmitigated. | Medium |
| S8 | Segment responses with status 206 are trusted without `Content-Range` validation (documented in the engine audit; a wrong-range body is written at the requested offset — a file-integrity, not just availability, issue). | Major |
| S9 | Tauri capabilities grant exactly `core:default` for the `main` window — no filesystem, shell, dialog, HTTP or opener permissions. **This is correct and must not regress.** | Pass |

## V2 controls

Each finding maps to a control. Controls are requirements, not aspirations:
Wave 8's regression suite (below) tests each one. The auth model is decided
in ADR-003; this section states its security-relevant shape.

### S1 → Token authentication for remote control

- `--lan` mode requires a **bearer token** on every `/api/*` request
  (reads and writes; only `/api/v1/health` is exempt). Loopback mode keeps
  tokenless access for the local user but still applies S7's Host validation.
- The token is generated on first run (256-bit, OS CSPRNG), printed once in
  the startup banner as a pairing URL (`http://<host>:<port>/#t=…`), and
  stored in the data directory (`web-token`) with owner-only file
  permissions. It is never written to logs after startup.
- The web page reads the token from the URL fragment (fragments are never
  sent to the server), stores it in `sessionStorage`, and attaches it as
  `Authorization: Bearer` on API calls; the SSE stream uses the single
  documented query-token exception because `EventSource` cannot set headers.
- Failed authentication returns `401` with no detail.
- **Rotation UX:** Settings shows the web access token (masked, behind a
  reveal action) with *Regenerate* — invalidating the old token
  immediately. Regenerating is the recovery action for a leaked token;
  there is no token recovery flow by design.

### S2 → Bounded connection handling

- A connection semaphore caps concurrent connections (default 32); excess
  connections are refused promptly rather than queued into unbounded
  threads. SSE subscribers count against a separate, smaller cap.

### S3 → Socket timeouts

- Accepted sockets get explicit read and write timeouts (30 s read, 30 s
  write; SSE connections exempt from the read timeout but keep the 20 s
  keepalive ping and are closed on failed writes). Header reads keep the
  existing 64 KB cap; request bodies keep the 1 MB clamp.

### S4 → Redirect policy (mirrors Android)

The engine adopts the policy Android already enforces in
`app/src/main/java/com/lanfetch/app/net/Http.kt` (`CleartextHostPolicy` and
its redirect interceptor):

- Redirects to a **different host are rejected** ("Server redirected to
  another host").
- Redirects from HTTPS to HTTP are **rejected** ("Server redirected to an
  unencrypted connection").
- Cleartext HTTP is permitted only to private/LAN addresses and to hosts
  the user has explicitly added as catalog sources; public cleartext is
  rejected.
- Redirect count stays bounded (≤ 5).

### S5 → No path disclosure

- `get_bootstrap` stops returning absolute filesystem paths over the web
  transport. The desktop IPC may keep them (local, same-user); the web DTO
  carries display-safe values (folder names, free-space figures) instead.

### S6 → Response headers

- All web responses carry `X-Content-Type-Options: nosniff` and
  `Referrer-Policy: no-referrer`; HTML responses carry a CSP at least as
  strict as the Tauri shell's (`script-src 'self'`, `object-src 'none'`,
  `frame-ancestors 'none'`). API JSON responses are `Cache-Control:
  no-store`.

### S7 → Host validation

- Requests whose `Host` header is not the bound address, a local interface
  name, or `localhost` are rejected with `403`. This is the DNS-rebinding
  mitigation for both loopback and LAN modes.

### S8 → Response validation

- Segment responses must present a `Content-Range` whose start matches the
  requested offset; mismatches fail the segment (never write-then-trust).
  Covered in full in `docs/DOWNLOAD-ENGINE.md`; listed here because it is
  the integrity half of the hostile-server threat.

### S9 → Tauri posture (keep)

- Capabilities stay at `core:default` for the main window. Any future
  permission addition requires a line in this document naming the command
  that needs it. The Rust command surface remains the entire desktop
  security boundary, as `capabilities/default.json` states.

## Token storage summary

| Where | What | Protection |
|-------|------|------------|
| Host data directory | Token at rest (`web-token`) | Owner-only permissions; never logged |
| Startup banner / Settings | Pairing URL shown to the user | Local surfaces only; never sent to catalog servers |
| Browser | Token after pairing | `sessionStorage`; attached as Bearer header; fragment never leaves the browser |
| Requests | `Authorization: Bearer` (API clients) or the events-route query exception | Plain HTTP on LAN — see limitations |

## Known limitations

- LAN mode runs over **plain HTTP**: the token and traffic are visible to a
  passive on-path observer on the same network. Token auth stops casual and
  active unauthenticated control; it is not transport encryption. Users on
  untrusted networks should prefer loopback mode or a private tunnel
  (e.g. Tailscale). This limitation is stated in the `--lan` startup banner.
- Token auth is a single shared secret, not per-device identity; revoking
  one device means regenerating for all.
- Android's foreground-service and storage permissions are platform-managed
  and audited separately in the Android regression suite.

## Security regression tests (Wave 8 must run)

1. Unauthenticated request to every state-changing endpoint in `--lan`
   mode → `401`, and no state change occurs.
2. Wrong-token requests → `401`; valid token → success.
3. Token regeneration invalidates the old token.
4. Connection flood beyond the cap → excess connections refused; server
   stays responsive to authenticated requests.
5. Slow-loris client (partial headers, indefinite) → connection closed by
   the read timeout; thread count returns to baseline.
6. Engine redirect cases: cross-host redirect → failure; HTTPS→HTTP
   downgrade → failure; same-host redirect → followed; public cleartext →
   failure; added-source cleartext → allowed.
7. `Host` header spoofing (rebinding simulation) → `403` in both modes.
8. Bootstrap response contains no absolute filesystem paths (web transport).
9. Responses carry the S6 headers; static serving rejects `..` and encoded
   traversal (existing behavior, pinned as regression).
10. Segment response with mismatched `Content-Range` → segment failure,
    zero bytes written at the wrong offset.
11. Tauri capability file diff: any permission beyond `core:default` fails
    CI until this document is updated with its justification.
