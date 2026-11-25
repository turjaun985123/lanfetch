#!/bin/sh
# Stage and build the LanFetch (Rust + Rune) .deb from a release binary.
# Usage: package-deb.sh <path-to-release-binary> <output.deb>
set -eu
BIN="${1:?usage: package-deb.sh <binary> <output.deb>}"
OUT="${2:?usage: package-deb.sh <binary> <output.deb>}"
VERSION="1.2.0"
ROOT="$(dirname "$0")"
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
Maintainer: NPC Automators <dev@npcautomators.com>
Description: LanFetch — LAN file-server browser and resumable downloader
 Browse the DHAKA-FLIX directory servers (and any Apache/nginx autoindex
 or h5ai server) and download files with a segmented parallel engine
 with byte-exact resume. Native Rust + Rune GPU UI; no runtime deps
 beyond a Vulkan-capable graphics stack.
CONTROL
dpkg-deb --build --root-owner-group "$STAGE" "$OUT"
echo "built $OUT"
