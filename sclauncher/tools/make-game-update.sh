#!/usr/bin/env bash
# Build the Android game-update bundle published with a launcher release.
#
# Usage:
#   sclauncher/tools/make-game-update.sh <release-tag> [unpack-version] [out-dir]
#
# Output (upload all four files to the GitHub release named <release-tag>):
#   game-update.json
#   lod-game-snapshot.jar
#   patches.zip
#
# The launcher reads:
#   https://github.com/<repo>/releases/latest/download/game-update.json
# so always publish the bundle with the newest non-prerelease release.
#
# unpack-version defaults to 5 (SC 3.0). If the game changes its unpacker,
# bump this so clients are told a fresh ISO rip is required.
set -euo pipefail

REPO="MusouSlime/Severed-Chains-Android-Lavitz-Edition"
TAG="${1:?usage: $0 <release-tag> [unpack-version] [out-dir]}"
UNPACK_VERSION="${2:-5}"
OUT="${3:-$(cd "$(dirname "$0")/../../.." && pwd)/build/game-update}"

HERE="$(cd "$(dirname "$0")" && pwd)"
ASSET="$HERE/../src/main/assets/game"
JAR="$ASSET/lod-game-snapshot.jar"
PATCHES="$ASSET/patches"

test -f "$JAR" || { echo "missing $JAR" >&2; exit 1; }
test -d "$PATCHES" || { echo "missing $PATCHES" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"
cp "$JAR" "$OUT/lod-game-snapshot.jar"

# Zip the patches dir without its parent path, matching the launcher's
# extractZip into the game directory.
(cd "$ASSET" && zip -qr "$OUT/patches.zip" patches)

JAR_SHA=$(sha256sum "$OUT/lod-game-snapshot.jar" | cut -d' ' -f1)
PAT_SHA=$(sha256sum "$OUT/patches.zip" | cut -d' ' -f1)
JAR_SIZE=$(stat -c %s "$OUT/lod-game-snapshot.jar")
PAT_SIZE=$(stat -c %s "$OUT/patches.zip")

cat > "$OUT/game-update.json" <<EOF
{
  "version": "${TAG}",
  "jar": "lod-game-snapshot.jar",
  "jarSha256": "${JAR_SHA}",
  "jarSize": ${JAR_SIZE},
  "patches": "patches.zip",
  "patchesSha256": "${PAT_SHA}",
  "patchesSize": ${PAT_SIZE},
  "unpackVersion": ${UNPACK_VERSION},
  "url": "https://github.com/${REPO}/releases/download/${TAG}/"
}
EOF

echo "Game-update bundle written to $OUT"
cat "$OUT/game-update.json"
