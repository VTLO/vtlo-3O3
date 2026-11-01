#!/usr/bin/env bash
# Builds the portable Windows zip from Linux (or WSL): app jars + a Temurin 21
# Windows JRE + a small native launcher. Needs curl, unzip, zip and mingw-w64.
#   desktop/packaging/build-portable.sh [output-dir]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="${1:-$root/desktop/build/portable}"
work="$out/work"
app="$work/VTLO 3O3"
rm -rf "$work" && mkdir -p "$app/app"

(cd "$root" && ./gradlew --no-daemon -q -PdesktopOnly :desktop:installDist)
cp "$root"/desktop/build/install/vtlo-3o3/lib/*.jar "$app/app/"

jre_zip="$out/temurin-21-jre-windows-x64.zip"
if [ ! -f "$jre_zip" ]; then
  curl -fsSL -o "$jre_zip" "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"
fi
mkdir -p "$work/jre" && unzip -q "$jre_zip" -d "$work/jre"
mv "$work"/jre/*/ "$app/runtime"

x86_64-w64-mingw32-windres "$here/launcher.rc" -O coff -o "$work/launcher.res"
x86_64-w64-mingw32-gcc -O2 -s -municode -mwindows -o "$app/VTLO 3O3.exe" "$here/launcher.c" "$work/launcher.res"
cp "$here/README-windows.txt" "$app/README.txt"
(cd "$work" && rm -f "$out/VTLO-3O3-windows-x64-portable.zip" && zip -qr "$out/VTLO-3O3-windows-x64-portable.zip" "VTLO 3O3")
echo "$out/VTLO-3O3-windows-x64-portable.zip"
