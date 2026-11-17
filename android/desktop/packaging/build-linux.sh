#!/usr/bin/env bash
# Builds the Linux packages: a portable tar.gz (app + trimmed Java runtime) and a .deb
# with a menu entry. Needs a JDK 17+ with jpackage, plus fakeroot and dpkg-deb for the .deb.
#   desktop/packaging/build-linux.sh [output-dir]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="${1:-$root/desktop/build/linux}"
work="$out/work"
rm -rf "$work" && mkdir -p "$work"

(cd "$root" && ./gradlew --no-daemon -q -PdesktopOnly :desktop:installDist)
common=(--name "VTLO 3O3" --app-version 1.0.0 --vendor VTLO
  --description "VTLO 3O3 studio: bass line, drum machine, sampler"
  --icon "$here/vtlo-3o3.png"
  --input "$root/desktop/build/install/vtlo-3o3/lib" --main-jar desktop.jar
  --main-class com.vtlo.desktop.MainKt)

jpackage --type app-image "${common[@]}" --dest "$work"
cp "$here/README-linux.txt" "$work/VTLO 3O3/README.txt"
(cd "$work" && tar -czf "$out/VTLO-3O3-linux-x64.tar.gz" "VTLO 3O3")
echo "$out/VTLO-3O3-linux-x64.tar.gz"

if command -v fakeroot >/dev/null && command -v dpkg-deb >/dev/null; then
  jpackage --type deb "${common[@]}" --dest "$out" \
    --linux-package-name vtlo-3o3 --linux-app-category sound --linux-menu-group "AudioVideo;Audio" \
    --linux-shortcut --linux-deb-maintainer "VTLO <noreply@vtlo.invalid>"
  ls "$out"/vtlo-3o3_*.deb
fi
