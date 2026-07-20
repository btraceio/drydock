#!/usr/bin/env bash
#
# Downloads and checksum-verifies the Eclipse Temurin JDK 26.0.1 "jmods"
# bundle for a macOS architecture, for use as a jlink --module-path entry
# when cross-linking a runtime image for an architecture other than the
# one the build is running on (see docs/superpowers/specs/
# 2026-07-20-cross-arch-runtime-image-design.md).
#
# Pinned to build 26.0.1+8. The project's Gradle toolchain only pins JDK
# major version 26 (not an exact build), so this script's pinned build
# number and the host JDK actually installed can drift apart --
# app/build.gradle.kts's runtimeImageAllArches task checks for exactly
# this drift before cross-linking and fails clearly if they don't match.
# If you upgrade the project's JDK 26 toolchain to a new exact build,
# update BOTH the URLs/checksums below AND the pinnedJmodsBuild constant
# in app/build.gradle.kts's runtimeImageAllArches task together.
#
# Usage:
#   scripts/download-cross-jmods.sh <macos-x86_64|macos-arm64> <output-dir>
#
# Prints the resolved jmods/ directory path to stdout on success (all
# other output goes to stderr). Idempotent: if <output-dir> already
# contains a directory with .jmod files in it (detected by content, not by
# name), this is a no-op that just reprints the path.
#
# Environment overrides:
#   CPM_CROSS_JMODS_ARCHIVE   Use this local .tar.gz path instead of
#                             downloading. Still checksum-verified against
#                             the same pinned hash as the network path --
#                             this overrides the transport, not the
#                             integrity check.
set -euo pipefail

fail() {
    echo "error: $*" >&2
    exit 1
}

[[ $# -eq 2 ]] || fail "usage: $0 <macos-x86_64|macos-arm64> <output-dir>"

arch="$1"
output_dir="$2"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"

case "$arch" in
    macos-arm64)
        url="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_aarch64_mac_hotspot_26.0.1_8.tar.gz"
        expected_sha256="e76d5df4bf1e1568b1de1332b1784e815746288309c79b08c72ae48545663484"
        ;;
    macos-x86_64)
        url="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_x64_mac_hotspot_26.0.1_8.tar.gz"
        expected_sha256="c323f7f94018e91a472273e9986e98890b0ca92dfd9bbdc9960c3edc6627b6b7"
        ;;
    *)
        fail "unrecognized architecture '$arch' (expected macos-x86_64 or macos-arm64)."
        ;;
esac

existing_jmods_dirs=""
if [[ -d "$output_dir" ]]; then
    existing_jmods_dirs="$(find "$output_dir" -type f -name '*.jmod' -exec dirname {} \; 2>/dev/null | sort -u)"
fi
existing_jmods_dir_count="$(echo "$existing_jmods_dirs" | grep -c . || true)"
if [[ "$existing_jmods_dir_count" -eq 1 ]]; then
    echo "==> Already downloaded and extracted: $existing_jmods_dirs" >&2
    echo "$existing_jmods_dirs"
    exit 0
elif [[ "$existing_jmods_dir_count" -gt 1 ]]; then
    fail "found $existing_jmods_dir_count directories with .jmod files under $output_dir (expected 0 or 1); remove $output_dir and retry: $existing_jmods_dirs"
fi

tmp_dir=""
cleanup() {
    [[ -n "$tmp_dir" && -d "$tmp_dir" ]] && rm -rf "$tmp_dir"
}
trap cleanup EXIT

if [[ -n "${CPM_CROSS_JMODS_ARCHIVE:-}" ]]; then
    [[ -f "$CPM_CROSS_JMODS_ARCHIVE" ]] || fail "CPM_CROSS_JMODS_ARCHIVE='$CPM_CROSS_JMODS_ARCHIVE' does not exist."
    archive_path="$CPM_CROSS_JMODS_ARCHIVE"
    echo "==> Using local archive: $archive_path" >&2
else
    tmp_dir="$(mktemp -d)"
    archive_path="$tmp_dir/jmods.tar.gz"
    echo "==> Downloading $url" >&2
    curl -fsSL -o "$archive_path" "$url" || fail "download of $url failed."
fi

actual_sha256="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
[[ "$actual_sha256" == "$expected_sha256" ]] \
    || fail "checksum mismatch for $archive_path: expected $expected_sha256, got $actual_sha256."

echo "==> Extracting to $output_dir" >&2
tar -xzf "$archive_path" -C "$output_dir"

# The archive's top-level directory is named e.g. jdk-26.0.1+8-jmods/, not
# "jmods" -- .jmod files sit directly inside it. Locate it by content
# (a directory containing .jmod files) rather than assuming a name.
jmods_dirs="$(find "$output_dir" -type f -name '*.jmod' -exec dirname {} \; | sort -u)"
jmods_dir_count="$(echo "$jmods_dirs" | grep -c . || true)"
[[ "$jmods_dir_count" -eq 1 ]] \
    || fail "expected exactly one directory of .jmod files under $output_dir after extraction, found $jmods_dir_count: $jmods_dirs"

echo "$jmods_dirs"
