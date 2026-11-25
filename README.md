# LanFetch — LAN Media Downloader for Android

A modern, **download-only** Android app for LAN media servers (DhakaFlix-style
HTTP directory servers), built in Kotlin + Jetpack Compose. It is a clean-room
reimplementation inspired by a reverse-engineering study of LanFlix 0.8.0
(Rust/Tauri): same job — crawl directory servers, catalog the video files —
but with **no streaming, no player, no casting**. LanFetch does one thing and
does it properly: **downloading**.

## Why

Directory servers on ISP LANs (e.g. `172.16.50.x` DhakaFlix servers) hold huge
movie/show libraries, but browsers download slowly, one connection at a time,
with no resume. LanFetch fixes that.

## Features

**Download engine (the core)**
- **Segmented parallel downloads** — each file is split into byte-range
  segments (1–16 connections, configurable) fetched simultaneously.
- **Byte-exact resume** — segment maps are persisted in Room; pause, kill the
  app, reboot — resume continues exactly where it stopped.
- **Per-segment retry** with exponential backoff; servers that ignore Range
  fall back to a single resumable stream automatically.
- **Queue** with configurable concurrency (1–5), foreground-service progress
  notifications with live speed + ETA, and automatic Wi-Fi/network resume.
- **Direct-to-MediaStore** — completed downloads write straight into a pending
  `Downloads/LanFetch` entry (API 29+) without a second copy step.
- **Wi-Fi only** mode, speed smoothing (EMA), per-segment progress map in the UI.

**Catalog**
- **Folder posters** — the photo inside each movie folder (poster.jpg /
  folder.jpg / cover.jpg…) is detected during the crawl and shown on the
  library cards (Coil, cached); shows inherit their title folder's poster.
- Crawler for Apache / nginx / lighttpd directory listings (autoindex).
- Release-name metadata parser: clean title, year, quality
  (2160p/1080p/720p/480p/CAM), release type (BluRay/WEB-DL/…), codec
  (x264/x265/AV1), dual/multi audio, language from category, SxxExx episodes.
- Seeded with the six DHAKA-FLIX servers; add any directory server.

**Filtering (proper, not decorative)**
- Instant search, Movies/Shows chips, and a full filter sheet: quality,
  category, server, language, release type, year range slider, minimum size,
  favorites, dual-audio only, and 5 sort orders. Every filter is a pure,
  unit-tested function over the catalog.

## Screens

Library (poster-style grid grouped by title folder) · Downloads (segment map,
speed, ETA, pause/resume/retry) · Servers (sync, enable/disable, add custom) ·
Settings (engine tuning).

## Build

Requires JDK 17 + Android SDK 35. CI (GitHub Actions) runs tests, lint, and builds both debug and release APKs:

```
./gradlew testDebugUnitTest   # JVM unit tests: parser, metadata, crawler, query, engine, segments
./gradlew lintDebug           # Android Lint checks
./gradlew assembleRelease     # R8-minified release APK
```

## Notes

- The seeded DhakaFlix addresses are private ISP-network IPs — reachable only
  from inside that ISP's network, exactly like the original app.
- Cleartext HTTP is enabled because LAN directory servers are HTTP-only.
- Download-only by design: there is intentionally no playback or streaming code.

## License

MIT
