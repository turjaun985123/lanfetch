# LanFetch

LanFetch is a download-only catalog browser and download manager for LAN
media servers that expose Apache/nginx autoindex or h5ai directory listings,
including DhakaFlix-style servers. It crawls the configured servers, builds a
poster-led library from folder names and release metadata, and downloads the
selected files with segmented parallel transfers and byte-exact resume.
There is intentionally no playback, streaming, or casting component.

The Android app is a native Kotlin/Jetpack Compose implementation. The
desktop and web editions share one Rust engine and one Astro/TypeScript
frontend, delivered through two transports: Tauri IPC for the desktop app
and HTTP/SSE for the standalone web binary.

## Editions

| Edition | What ships | Notes |
|---|---|---|
| Android app | Native Android APK | Independent Kotlin implementation using Room for its queue and catalog data. It is not a wrapper around the web UI. |
| Desktop | Linux `.deb` | Tauri application using the shared Rust engine. The package name is `lanfetch` and it installs `/usr/bin/lanfetch`. |
| Web, Linux x86_64 | Single `lanfetch-web` binary | The UI is embedded in the binary; no GUI libraries or display are required. |
| Web, Linux ARM64 | Single `lanfetch-web` binary | Same web edition built for ARM64 Linux. |
| Web, Android ARM64 | Single `lanfetch-web` binary | A standalone ARM64 web-server binary for Android environments; it is separate from the native Android APK. |

Desktop and web use the same engine state. On Linux, application data is in
`~/.local/share/lanfetch/`, downloads are in `~/Downloads/LanFetch/`, and the
poster cache is in `~/.cache/lanfetch/posters/`.

## Main behaviour

- Progressive catalog feed, search, favorites, categories, and title detail
  across all enabled sources.
- Poster detection from files inside title folders, including the
  `a_AL_.jpg` name used by some DhakaFlix folders.
- Release-name parsing for title, year, quality, codec, audio, language, and
  season/episode information where the server provides it.
- Segmented downloads with configurable connections and queue concurrency,
  pause/resume, retry after failure, progress checkpoints, and reconciliation
  of the saved plan against the actual `.part` file before completion.
- A versioned HTTP API at `/api/v1`, with the legacy web command surface kept
  for compatibility.

## Build and run

### Android

Requires JDK 17 and Android SDK 35.

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
```

### Desktop and web

Work from `desktop-tauri/`. The frontend and Rust checks used by CI are:

```sh
npm ci
npm run check
npm run test
npm run build
cargo fmt --check --all
cargo clippy --locked --workspace -- -D warnings
cargo test --locked --workspace
```

The release `.deb` is produced by the desktop CI workflow: it builds the
Tauri application without Tauri's bundler and then runs the project's
`packaging/package-deb.sh` script so the Debian package identity remains
`lanfetch`.

The web binary can be built directly from the workspace:

```sh
cargo build --release -p lanfetch-web --locked
```

Run it locally, on a chosen port, or exposed to the LAN:

```sh
./lanfetch-web
./lanfetch-web --port 8787
./lanfetch-web --lan
```

8787 is the default port. An installed desktop package is launched as
`lanfetch`.

## Web pairing and LAN access

By default, `lanfetch-web` binds to loopback and needs no token. With
`--lan`, it listens on network interfaces and requires the install's 256-bit
access token on every `/api/*` route except the health check; at startup it
prints a pairing URL whose `#t=` fragment carries the token, the browser
stores that token in `sessionStorage` and sends it as a bearer token, and
Settings shows the same token with copy and regenerate actions. Regenerating
the token takes effect immediately and invalidates earlier pairings, while
the browser that performed the regeneration adopts the new token. This is
access control over plain HTTP, not encryption: anyone able to observe LAN
traffic can observe the token and content, so use `--lan` only on networks
where that exposure is acceptable or through a private tunnel.

## Documentation

- [V2 architecture](docs/V2-ARCHITECTURE.md)
- [Download-engine contract](docs/DOWNLOAD-ENGINE.md)
- [HTTP API](docs/API.md)
- [Security model](docs/SECURITY.md)
- [Migration and data compatibility](docs/MIGRATION.md)
- [Cross-platform behavioural contract](docs/BEHAVIORAL-CONTRACT.md)
- [Desktop/web design system](desktop-tauri/DESIGN.md)
- [Responsive QA checklist](desktop-tauri/RESPONSIVE-QA.md)

## Honest limits

- There is no built-in updater. Upgrades are installed manually: a new
  `.deb`, a replacement web binary, or a new signed APK.
- The ARM web binaries are built in CI but cannot be executed on the x86
  development VM used for this project; their runtime behaviour still needs
  confirmation on target hardware.
- The seeded DhakaFlix addresses are private ISP-network addresses. They are
  reachable only from inside that ISP's network, not from the public
  internet or an unrelated LAN.
- Catalog servers do not publish cryptographic checksums. A completed
  download is verified by byte counts, range responses, and the server's
  ETag/Last-Modified validator; LanFetch does not claim checksum proof.
- The web access token is one shared secret per install, not a per-device
  identity. Revoking one paired device by regenerating the token revokes
  all of them.

## License

MIT
