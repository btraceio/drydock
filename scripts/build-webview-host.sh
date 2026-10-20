#!/usr/bin/env bash
#
# Builds the WKWebView embedding shim (native-host/DrydockWebviewHost.{h,m})
# for both supported macOS architectures. WebKit is a macOS system framework
# (nothing to bundle or install -- the whole point of the webview spike), so
# the only inputs are Xcode command line tools, same as build-native-host.sh.
#
# This is a separate native artifact from both libghostty and
# libdrydockterminalhost: it depends on Cocoa/AppKit and WebKit only.
#
# Deliverables (under $OUT_DIR, default build/native):
#   macos-x86_64/libdrydockwebviewhost.dylib
#   macos-arm64/libdrydockwebviewhost.dylib
#
# Environment overrides (all optional):
#   OUT_DIR        Where to place the per-architecture libraries. Default:
#                  <repo-root>/build/native
#   NATIVE_ARCHES  arm64 | x86_64 | both (default both; CI per-arch jobs set
#                  a single slice)
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
[[ -f "$src_dir/DrydockWebviewHost.m" ]] || fail "$src_dir/DrydockWebviewHost.m not found."

mkdir -p "$OUT_DIR/macos-x86_64" "$OUT_DIR/macos-arm64"

build_arch() {
    local target="$1" out_subdir="$2"
    echo "==> Building libdrydockwebviewhost.dylib for $target"
    clang -x objective-c \
        -target "$target" \
        -mmacosx-version-min=12.0 \
        -dynamiclib \
        -fobjc-arc \
        -Wall -Wextra -Werror \
        -framework Cocoa \
        -framework WebKit \
        -install_name "@rpath/libdrydockwebviewhost.dylib" \
        -o "$OUT_DIR/$out_subdir/libdrydockwebviewhost.dylib" \
        "$src_dir/DrydockWebviewHost.m"
}

NATIVE_ARCHES="${NATIVE_ARCHES:-both}"

built_dirs=()
case "$NATIVE_ARCHES" in
    arm64)  build_arch "arm64-apple-macos12"  macos-arm64;  built_dirs=(macos-arm64) ;;
    x86_64) build_arch "x86_64-apple-macos12" macos-x86_64; built_dirs=(macos-x86_64) ;;
    both)
        build_arch "x86_64-apple-macos12" macos-x86_64
        build_arch "arm64-apple-macos12"  macos-arm64
        built_dirs=(macos-x86_64 macos-arm64)
        ;;
    *) fail "Invalid NATIVE_ARCHES='$NATIVE_ARCHES' (expected: arm64, x86_64, or both)." ;;
esac

for arch_dir in "${built_dirs[@]}"; do
    arch_name="${arch_dir#macos-}"
    [[ "$arch_name" == "x86_64" ]] && expect="x86_64" || expect="arm64"
    actual="$(file -b "$OUT_DIR/$arch_dir/libdrydockwebviewhost.dylib" | grep -oE 'x86_64|arm64')"
    [[ "$actual" == "$expect" ]] || fail "'$OUT_DIR/$arch_dir/libdrydockwebviewhost.dylib' is architecture '$actual', expected '$expect'."
    nm -g "$OUT_DIR/$arch_dir/libdrydockwebviewhost.dylib" | grep -q "_drydock_webview_host_create" \
        || fail "'$OUT_DIR/$arch_dir/libdrydockwebviewhost.dylib' does not export drydock_webview_host_create."
    echo "==> built $OUT_DIR/$arch_dir/libdrydockwebviewhost.dylib"
done
