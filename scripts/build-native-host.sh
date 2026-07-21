#!/usr/bin/env bash
#
# Builds the tiny AppKit host shim (native-host/DrydockTerminalHost.{h,m}, see
# docs/implementation-plan.md section 8 and docs/native-integration.md) for
# both supported macOS architectures.
#
# This is a completely separate native artifact from libghostty: it has no
# dependency on the ghostty submodule at all (see DrydockTerminalHost.h -- it
# never includes any ghostty header), only on Cocoa/AppKit, which ships with
# Xcode.
#
# Deliverables (under $OUT_DIR, default build/native):
#   macos-x86_64/libdrydockterminalhost.dylib
#   macos-arm64/libdrydockterminalhost.dylib
#
# Environment overrides (all optional):
#   OUT_DIR   Where to place the per-architecture libraries. Default:
#             <repo-root>/build/native
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
src_dir="$repo_root/native-host"
OUT_DIR="${OUT_DIR:-$repo_root/build/native}"

fail() {
    echo "error: $*" >&2
    exit 1
}

command -v clang >/dev/null 2>&1 || fail "clang not found. Install Xcode command line tools (xcode-select --install)."
[[ -f "$src_dir/DrydockTerminalHost.m" ]] || fail "$src_dir/DrydockTerminalHost.m not found."

mkdir -p "$OUT_DIR/macos-x86_64" "$OUT_DIR/macos-arm64"

build_arch() {
    local target="$1" out_subdir="$2"
    echo "==> Building libdrydockterminalhost.dylib for $target"
    clang -x objective-c \
        -target "$target" \
        -mmacosx-version-min=12.0 \
        -dynamiclib \
        -fobjc-arc \
        -Wall -Wextra -Werror \
        -framework Cocoa \
        -install_name "@rpath/libdrydockterminalhost.dylib" \
        -o "$OUT_DIR/$out_subdir/libdrydockterminalhost.dylib" \
        "$src_dir/DrydockTerminalHost.m"
}

build_arch "x86_64-apple-macos12" macos-x86_64
build_arch "arm64-apple-macos12" macos-arm64

for arch_dir_expect in "macos-x86_64:x86_64" "macos-arm64:arm64"; do
    arch_dir="${arch_dir_expect%%:*}"
    expect="${arch_dir_expect##*:}"
    actual="$(file -b "$OUT_DIR/$arch_dir/libdrydockterminalhost.dylib" | grep -oE 'x86_64|arm64')"
    [[ "$actual" == "$expect" ]] || fail "'$OUT_DIR/$arch_dir/libdrydockterminalhost.dylib' is architecture '$actual', expected '$expect'."
    nm -g "$OUT_DIR/$arch_dir/libdrydockterminalhost.dylib" | grep -q "_drydock_terminal_host_create" \
        || fail "'$OUT_DIR/$arch_dir/libdrydockterminalhost.dylib' does not export drydock_terminal_host_create."
done

echo "==> libdrydockterminalhost.dylib built for both architectures:"
echo "  $OUT_DIR/macos-x86_64/libdrydockterminalhost.dylib"
echo "  $OUT_DIR/macos-arm64/libdrydockterminalhost.dylib"
