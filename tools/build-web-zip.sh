#!/usr/bin/env bash
# Packs the browser version (repo root) with double-click launchers for Windows and Linux.
#   tools/build-web-zip.sh [output.zip]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(realpath -m "${1:-$root/downloads/VTLO-303-web-windows-linux.zip}")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
app="$work/VTLO 303 web"
mkdir -p "$app/assets"
cp -r "$root/index.html" "$root/favicon.svg" "$root/css" "$root/js" "$root/fonts" "$app/"
cp "$root/assets/cover-art.png" "$app/assets/"
cp "$root"/tools/web-launchers/* "$app/"
rm -f "$out"
(cd "$work" && zip -qr -X "$out" "VTLO 303 web")
echo "$out"
