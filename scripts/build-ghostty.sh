#!/usr/bin/env bash
#
# Builds libghostty (from the pinned third_party/ghostty submodule) for both
# macOS architectures (arm64 and x86_64), per the approved dual-architecture
# deviation documented in README.md / docs/implementation-plan.md.
#
# See docs/native-integration.md for the detailed findings this script is
# based on:
#   - Ghostty's build requires exactly Zig 0.15.x (not the 0.16.x that may be
#     the default `zig` on PATH).
#   - On macOS, `zig build` never installs a loose libghostty.dylib/.a; it
#     only ever produces a static library inside macos/GhosttyKit.xcframework.
#   - `-Dxcframework-target=native` silently ignores `-Dtarget` for the
#     macOS "native" slice (Config.genericMacOSTarget(b, null) hardcodes the
#     host architecture) -- confirmed empirically in Task 3. This means the
#     *only* way to obtain an arm64 static library on this Intel host is via
#     `-Dxcframework-target=universal` (the default), which explicitly builds
#     both aarch64-macos and x86_64-macos slices (Config.genericMacOSTarget(b,
#     .aarch64) / (b, .x86_64) in GhosttyLib.initMacOSUniversal) and lipo's
#     them together. This script then un-lipo's ("thins") that universal
#     archive back into one static library per architecture, matching the
#     per-architecture deliverable layout this project needs.
#
# Deliverables (all under $OUT_DIR, default build/native):
#   macos-x86_64/libghostty.a
#   macos-arm64/libghostty.a
#   include/ghostty.h (+ include/ghostty/*, include/module.modulemap)
# and, under $GENERATED_DIR (default build/generated):
#   ghostty-version.properties
#
# Environment overrides (all optional):
#   ZIG_BIN         Path to the Zig 0.15.x binary to use. Defaults to
#                   /usr/local/opt/zig@0.15/bin/zig if present, else `zig`
#                   resolved from PATH.
#   GHOSTTY_DIR     Path to the ghostty submodule checkout.
#                   Default: <repo-root>/third_party/ghostty
#   OUT_DIR         Where to place the per-architecture libraries/headers.
#                   Default: <repo-root>/build/native
#   GENERATED_DIR   Where to place ghostty-version.properties.
#                   Default: <repo-root>/build/generated
#   GHOSTTY_OPTIMIZE Zig -Doptimize value. Default: ReleaseFast.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

GHOSTTY_DIR="${GHOSTTY_DIR:-$repo_root/third_party/ghostty}"
OUT_DIR="${OUT_DIR:-$repo_root/build/native}"
GENERATED_DIR="${GENERATED_DIR:-$repo_root/build/generated}"
GHOSTTY_OPTIMIZE="${GHOSTTY_OPTIMIZE:-ReleaseFast}"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

# --- Locate and validate Zig -------------------------------------------------
#
# Ghostty's build.zig.zon pins `minimum_zig_version = "0.15.2"` and
# src/build/zig.zig's requireZig() enforces an *exact* major.minor match
# (0.15.x only) at comptime. The Homebrew default `zig` may be a newer,
# incompatible release (0.16.x was observed on this machine, which cannot
# even parse Ghostty's build.zig -- std.process.EnvMap was removed).
if [[ -z "${ZIG_BIN:-}" ]]; then
    if [[ -x /usr/local/opt/zig@0.15/bin/zig ]]; then
        ZIG_BIN=/usr/local/opt/zig@0.15/bin/zig
    elif command -v zig >/dev/null 2>&1; then
        ZIG_BIN="$(command -v zig)"
    else
        fail "No 'zig' found. Install Zig 0.15.2, e.g.: brew install zig@0.15
(see docs/native-integration.md, section 'Required Zig version')."
    fi
fi

[[ -x "$ZIG_BIN" ]] || fail "ZIG_BIN='$ZIG_BIN' is not an executable file."

zig_version="$("$ZIG_BIN" version)"
case "$zig_version" in
    0.15.*) ;;
    *)
        fail "Zig at '$ZIG_BIN' reports version '$zig_version', but Ghostty
requires exactly 0.15.x (build.zig.zon: minimum_zig_version = 0.15.2).
Install it with: brew install zig@0.15
and re-run with ZIG_BIN=/usr/local/opt/zig@0.15/bin/zig (or let this script
auto-detect that path)." ;;
esac

# --- Validate Xcode command line tools / Metal toolchain --------------------
#
# The macOS xcframework build shells out to `xcodebuild -create-xcframework`
# and to `xcrun -sdk macosx metal` (to compile Ghostty's Metal shaders). Both
# require a full Xcode install (not just the stand-alone CLT) with the Metal
# Toolchain component downloaded (`xcodebuild -downloadComponent
# MetalToolchain`); this was required on this machine even though a
# CommandLineTools-only shell already reported a valid xcode-select path.
command -v xcodebuild >/dev/null 2>&1 || fail "'xcodebuild' not found. Install Xcode (not just the Command Line
Tools) from the App Store, then run: xcode-select -p
to confirm it points at /Applications/Xcode.app/Contents/Developer."

xcode_path="$(xcode-select -p 2>/dev/null || true)"
[[ -n "$xcode_path" ]] || fail "xcode-select has no configured path. Run: xcode-select -s /Applications/Xcode.app/Contents/Developer"

# --- Validate the submodule is initialized ----------------------------------
[[ -f "$GHOSTTY_DIR/build.zig" ]] || fail "Ghostty submodule not found/initialized at '$GHOSTTY_DIR'.
Run: git submodule update --init"

pinned_commit="$(git -C "$repo_root" submodule status -- third_party/ghostty \
    | sed -E 's/^[ +-]?([0-9a-f]+).*/\1/')"
[[ -n "$pinned_commit" ]] || fail "Could not determine the pinned Ghostty submodule commit via 'git submodule status'."

actual_commit="$(git -C "$GHOSTTY_DIR" rev-parse HEAD)"
if [[ "$actual_commit" != "$pinned_commit" ]]; then
    echo "WARNING: third_party/ghostty HEAD ($actual_commit) does not match" >&2
    echo "the commit recorded by the parent repo's submodule pointer ($pinned_commit)." >&2
    echo "Run: git submodule update --init" >&2
fi

echo "== Building libghostty =="
echo "  zig:          $ZIG_BIN ($zig_version)"
echo "  ghostty dir:  $GHOSTTY_DIR"
echo "  commit:       $actual_commit"
echo "  optimize:     $GHOSTTY_OPTIMIZE"
echo "  out dir:      $OUT_DIR"

# --- Run the actual zig build ------------------------------------------------
#
# -Dxcframework-target=universal (the default) is required -- not just
# requested -- because it is the only mode that builds an aarch64-macos slice
# from an x86_64 host (see header comment above). It also produces iOS /
# iOS-simulator slices we don't need, but there is no supported way to ask
# for "only the two macOS architectures" without patching build.zig, so we
# accept the extra build cost and simply ignore those slices below.
(
    cd "$GHOSTTY_DIR"
    "$ZIG_BIN" build \
        -Doptimize="$GHOSTTY_OPTIMIZE" \
        -Dxcframework-target=universal \
        -Demit-xcframework=true \
        -Demit-macos-app=false
)

xcframework_dir="$GHOSTTY_DIR/macos/GhosttyKit.xcframework"
universal_slice="$xcframework_dir/macos-arm64_x86_64"
universal_lib="$universal_slice/libghostty.a"

[[ -f "$universal_lib" ]] || fail "Expected universal static library not found at '$universal_lib'.
The zig build reported success but did not produce the expected artifact --
this usually means Ghostty's build layout has changed since
docs/native-integration.md was written; re-inspect
third_party/ghostty/src/build/GhosttyXCFramework.zig."

lipo_archs="$(lipo -archs "$universal_lib")"
echo "  universal lib archs: $lipo_archs"

# --- Split the universal (fat) static library into per-arch outputs --------
mkdir -p "$OUT_DIR/macos-x86_64" "$OUT_DIR/macos-arm64" "$OUT_DIR/include"

lipo -thin x86_64 "$universal_lib" -output "$OUT_DIR/macos-x86_64/libghostty.a"
lipo -thin arm64  "$universal_lib" -output "$OUT_DIR/macos-arm64/libghostty.a"

for arch_dir in macos-x86_64 macos-arm64; do
    file "$OUT_DIR/$arch_dir/libghostty.a" | grep -q 'ar archive' \
        || fail "Split library '$OUT_DIR/$arch_dir/libghostty.a' does not look like a valid archive."
done

# --- Copy public headers (identical across slices; take them from the
#     universal slice) -------------------------------------------------------
rsync -a --delete "$universal_slice/Headers/" "$OUT_DIR/include/"

[[ -f "$OUT_DIR/include/ghostty.h" ]] || fail "ghostty.h missing from copied headers at '$OUT_DIR/include'."

# --- Record build metadata ---------------------------------------------------
mkdir -p "$GENERATED_DIR"
cat > "$GENERATED_DIR/ghostty-version.properties" <<EOF
# Generated by scripts/build-ghostty.sh. Do not edit by hand.
ghostty.commit=$actual_commit
ghostty.tag=v1.3.1
ghostty.optimize=$GHOSTTY_OPTIMIZE
ghostty.xcframework.target=universal
zig.version=$zig_version
EOF

echo "== libghostty build complete =="
echo "  $OUT_DIR/macos-x86_64/libghostty.a"
echo "  $OUT_DIR/macos-arm64/libghostty.a"
echo "  $OUT_DIR/include/ghostty.h"
echo "  $GENERATED_DIR/ghostty-version.properties"
