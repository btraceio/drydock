#!/bin/bash
# Rasterizes assets/icon/drydock.svg into the macOS .icns and the classpath
# PNG bundled in the jbang jar. Requires librsvg (`brew install librsvg`) and
# macOS `iconutil`. Regenerate whenever the SVG changes; the outputs are
# committed so ordinary builds need neither tool.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SVG="$ROOT/assets/icon/drydock.svg"
ICONSET="$(mktemp -d)/drydock.iconset"
mkdir -p "$ICONSET"

command -v rsvg-convert >/dev/null || { echo "Need rsvg-convert: brew install librsvg" >&2; exit 1; }
command -v iconutil    >/dev/null || { echo "Need iconutil (macOS)" >&2; exit 1; }

render() { rsvg-convert -w "$1" -h "$1" "$SVG" -o "$2"; }

for pair in "16 16x16" "32 16x16@2x" "32 32x32" "64 32x32@2x" \
            "128 128x128" "256 128x128@2x" "256 256x256" "512 256x256@2x" \
            "512 512x512" "1024 512x512@2x"; do
  set -- $pair
  render "$1" "$ICONSET/icon_$2.png"
done

iconutil -c icns "$ICONSET" -o "$ROOT/assets/app-icon.icns"
mkdir -p "$ROOT/app/src/main/resources/icon"
render 1024 "$ROOT/app/src/main/resources/icon/drydock.png"
echo "Wrote assets/app-icon.icns and app/src/main/resources/icon/drydock.png"
