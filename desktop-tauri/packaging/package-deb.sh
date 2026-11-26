#!/bin/sh
# Stage and build the LanFetch (Rust + Tauri) .deb from a release binary.
# Usage: package-deb.sh <path-to-release-binary> <output.deb>
#
# Tauri's own bundler derives the deb Package name from productName
# ("LanFetch"), which breaks the dpkg upgrade path from the Rune-era
# package ("lanfetch") — the Release plan's documented fallback is
# this script, which shipped three verified releases of the old app.
set -eu
BIN="${1:?usage: package-deb.sh <binary> <output.deb>}"
OUT="${2:?usage: package-deb.sh <binary> <output.deb>}"
ROOT="$(dirname "$0")"
# Single source of truth for the version: the backend crate's Cargo.toml.
VERSION="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$ROOT/../src-tauri/Cargo.toml" | head -n 1)"
: "${VERSION:?could not read version from src-tauri/Cargo.toml}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/usr/bin" \
         "$STAGE/usr/share/applications" \
         "$STAGE/usr/share/icons/hicolor/512x512/apps" \
         "$STAGE/DEBIAN"
install -m 0755 "$BIN" "$STAGE/usr/bin/lanfetch"
install -m 0644 "$ROOT/lanfetch.desktop" "$STAGE/usr/share/applications/lanfetch.desktop"
install -m 0644 "$ROOT/lanfetch.png" "$STAGE/usr/share/icons/hicolor/512x512/apps/lanfetch.png"
cat > "$STAGE/DEBIAN/control" <<CONTROL
Package: lanfetch
Version: $VERSION
Section: net
Priority: optional
Architecture: amd64
Depends: libwebkit2gtk-4.1-0, libgtk-3-0, libsoup-3.0-0, libjavascriptcoregtk-4.1-0, librsvg2-2
Maintainer: NPC Automators <dev@npcautomators.com>
Description: LanFetch — LAN file-server browser and resumable downloader
 Browse the DHAKA-FLIX directory servers (and any Apache/nginx autoindex
 or h5ai server) and download files with a segmented parallel engine
 with byte-exact resume. Native Rust engine with a Tauri front end.
CONTROL
dpkg-deb --build --root-owner-group "$STAGE" "$OUT"
echo "built $OUT"
