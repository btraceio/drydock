#!/usr/bin/env bash
#
# Packages the jlink runtime image into a tarball that can be copied to
# another Mac and run without Gradle, JAVA_HOME, or this repository
# present.
#
# Default (no flags): packages ONLY this machine's own architecture via
# ./gradlew runtimeImage (see docs/runtime-image.md) -- without
# --all-arches this script only ever produces an archive for the CURRENT
# machine's architecture. To get an archive for the other architecture,
# either run this script again on a Mac of that architecture, or use
# --all-arches below.
#
# --all-arches: uses ./gradlew runtimeImageAllArches (see
# docs/superpowers/specs/2026-07-20-cross-arch-runtime-image-design.md)
# to cross-link BOTH macOS architectures from this one machine in a
# single pass, producing two archives. The cross-linked (non-host)
# architecture's archive is verified only by its Mach-O architecture tag,
# never actually executed -- real execution verification on that
# hardware is CI's job, not this script's.
#
# This is deliberately separate from the ./gradlew appImage/macApp/dmg
# tasks, which are reserved no-ops for later .app/.dmg packaging work
# (plan section 23.3/23.4 Stages 3-6) -- this script does not implement,
# replace, or depend on any of them.
#
# Deliverable(s):
#   build/dist/cpm-image-macos-<arch>-<git-short-sha>.tar.gz
#
# Usage:
#   ./scripts/package-runtime-image.sh [--all-arches]
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

fail() {
    echo "error: $*" >&2
    exit 1
}

all_arches=false
case "${1:-}" in
    "") ;;
    --all-arches) all_arches=true ;;
    *) fail "usage: $0 [--all-arches]" ;;
esac

cd "$repo_root"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || fail "$repo_root is not a git checkout; cannot determine a build identifier."

if [[ -n "$(git status --porcelain)" ]]; then
    echo "warning: working tree has uncommitted changes -- the packaged image will include them, not just commit $(git rev-parse --short HEAD)." >&2
fi

git_sha="$(git rev-parse --short HEAD)"
dist_dir="$repo_root/build/dist"
mkdir -p "$dist_dir"

host_arch_uname="$(uname -m)"
case "$host_arch_uname" in
    x86_64) host_arch="x86_64" ;;
    arm64)  host_arch="arm64" ;;
    *) fail "unrecognized host architecture '$host_arch_uname' (expected x86_64 or arm64); refusing to guess a label." ;;
esac

# Packages one already-built image directory into
# build/dist/cpm-image-macos-<arch>-<sha>.tar.gz and prints its copy/run
# instructions. $1: image dir (e.g. build/image or
# build/image-macos-arm64). $2: arch label (x86_64 or arm64). $3: "host"
# or "cross" -- selects which closing note to print.
package_one() {
    local image_dir="$1" arch="$2" kind="$3"
    local archive_name="cpm-image-macos-${arch}-${git_sha}.tar.gz"
    local archive_path="$dist_dir/$archive_name"

    [[ -x "$image_dir/bin/claude-project-manager" ]] \
        || fail "$image_dir/bin/claude-project-manager not found or not executable; the Gradle task did not produce the expected launcher."

    echo "==> Archiving $image_dir into $archive_path"
    tar -C "$(dirname "$image_dir")" -czf "$archive_path" "$(basename "$image_dir")"

    [[ -s "$archive_path" ]] || fail "$archive_path was not created or is empty."

    echo "==> Done: $archive_path"
    echo ""
    echo "On the target Mac (macOS $arch):"
    echo "  tar xzf $archive_name"
    echo "  ./$(basename "$image_dir")/bin/claude-project-manager"
    echo ""
    echo "The image's default launch target is the real application (app.cpm.Main)."
    echo "To run the Task 5 terminal spike instead (escape hatch for testing):"
    echo "  CPM_MAIN_CLASS=app.cpm.terminal.Gate0cSpikeLauncher ./$(basename "$image_dir")/bin/claude-project-manager"
    echo ""
    if [[ "$kind" == "cross" ]]; then
        echo "This image was CROSS-LINKED on a $host_arch host for $arch -- it was never"
        echo "actually executed (only its Mach-O architecture tag was verified). Real"
        echo "execution verification on $arch hardware is expected to happen in CI once"
        echo "that is wired up, not by this script."
    else
        echo "This archive runs ONLY on macOS $arch -- jlink builds a runtime for the"
        echo "architecture it runs on, not a cross-build by default. Use --all-arches to"
        echo "cross-link the other architecture from this machine instead."
    fi
    echo ""
    echo "If macOS refuses to run the extracted binaries with a Gatekeeper"
    echo "'unidentified developer' / quarantine error (this can happen when the"
    echo "archive was transferred via a browser download or AirDrop, not"
    echo "scp/USB), clear the quarantine attribute:"
    echo "  xattr -cr ./$(basename "$image_dir")"
    echo ""
}

if [[ "$all_arches" == true ]]; then
    echo "==> Building both architectures' runtime images (./gradlew runtimeImageAllArches)"
    "$repo_root/gradlew" runtimeImageAllArches

    cross_arch="x86_64"
    [[ "$host_arch" == "x86_64" ]] && cross_arch="arm64"

    package_one "$repo_root/build/image-macos-${host_arch}" "$host_arch" "host"
    package_one "$repo_root/build/image-macos-${cross_arch}" "$cross_arch" "cross"
else
    echo "==> Building runtime image (./gradlew runtimeImage)"
    "$repo_root/gradlew" runtimeImage

    package_one "$repo_root/build/image" "$host_arch" "host"
fi
