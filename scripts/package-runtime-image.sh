#!/usr/bin/env bash
#
# Packages the jlink runtime image (./gradlew runtimeImage, see
# docs/runtime-image.md) into a tarball that can be copied to another Mac
# and run without Gradle, JAVA_HOME, or this repository present.
#
# jlink produces a runtime for the architecture it runs ON -- it does not
# cross-build (docs/runtime-image.md, "What was verified vs. not"). This
# script therefore only ever produces an archive for the CURRENT machine's
# architecture. To get an archive for the other architecture, run this
# same script on a Mac of that architecture.
#
# This is deliberately separate from the ./gradlew appImage/macApp/dmg
# tasks, which are reserved no-ops for later .app/.dmg packaging work
# (plan section 23.3/23.4 Stages 3-6) -- this script does not implement,
# replace, or depend on any of them.
#
# Deliverable:
#   build/dist/cpm-image-macos-<arch>-<git-short-sha>.tar.gz
#
# Usage:
#   ./scripts/package-runtime-image.sh
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

fail() {
    echo "error: $*" >&2
    exit 1
}

cd "$repo_root"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || fail "$repo_root is not a git checkout; cannot determine a build identifier."

if [[ -n "$(git status --porcelain)" ]]; then
    echo "warning: working tree has uncommitted changes -- the packaged image will include them, not just commit $(git rev-parse --short HEAD)." >&2
fi

echo "==> Building runtime image (./gradlew runtimeImage)"
"$repo_root/gradlew" runtimeImage

[[ -x "$repo_root/build/image/bin/claude-project-manager" ]] \
    || fail "build/image/bin/claude-project-manager not found or not executable after runtimeImage; the Gradle task did not produce the expected launcher."

host_arch="$(uname -m)"
case "$host_arch" in
    x86_64) image_arch="x86_64" ;;
    arm64)  image_arch="arm64" ;;
    *) fail "unrecognized host architecture '$host_arch' (expected x86_64 or arm64); refusing to guess a label." ;;
esac

git_sha="$(git rev-parse --short HEAD)"
dist_dir="$repo_root/build/dist"
archive_name="cpm-image-macos-${image_arch}-${git_sha}.tar.gz"
archive_path="$dist_dir/$archive_name"

mkdir -p "$dist_dir"

echo "==> Archiving build/image into $archive_path"
tar -C "$repo_root/build" -czf "$archive_path" image

[[ -s "$archive_path" ]] || fail "$archive_path was not created or is empty."

echo "==> Done: $archive_path"
echo ""
echo "This archive runs ONLY on macOS $image_arch -- jlink builds a runtime"
echo "for the architecture it runs on, not a cross-build. To package for the"
echo "other architecture, run this script again on a Mac of that architecture."
echo ""
echo "On the target Mac:"
echo "  tar xzf $archive_name"
echo "  ./image/bin/claude-project-manager"
echo ""
echo "The image's default launch target is the real application (app.cpm.Main)."
echo "To run the Task 5 terminal spike instead (escape hatch for testing):"
echo "  CPM_MAIN_CLASS=app.cpm.terminal.Gate0cSpikeLauncher ./image/bin/claude-project-manager"
echo ""
echo "If macOS refuses to run the extracted binaries with a Gatekeeper"
echo "'unidentified developer' / quarantine error (this can happen when the"
echo "archive was transferred via a browser download or AirDrop, not"
echo "scp/USB), clear the quarantine attribute:"
echo "  xattr -cr ./image"
