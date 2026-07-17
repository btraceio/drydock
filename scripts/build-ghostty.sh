#!/usr/bin/env bash
#
# Builds libghostty (from the pinned third_party/ghostty submodule) for both
# macOS architectures (arm64 and x86_64), per the approved dual-architecture
# deviation documented in README.md / docs/implementation-plan.md.
#
# See docs/native-integration.md for background, and the comment block below
# ("Why this script patches the vendored submodule") for what changed in
# Task 4 and why.
#
# Deliverables (all under $OUT_DIR, default build/native):
#   macos-x86_64/libghostty.a
#   macos-x86_64/libghostty.dylib
#   macos-arm64/libghostty.a
#   macos-arm64/libghostty.dylib
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
#
# --- Why this script patches the vendored submodule -------------------------
#
# Task 3 originally built the *static* xcframework (`-Dxcframework-target=
# universal`) and lipo-thinned it into a per-arch libghostty.a. Task 4
# (the FFM smoke test) discovered two things empirically while trying to
# link a `.dylib` from that static archive (FFM's SymbolLookup needs a
# dlopen-able image; a `.a` cannot be dlopen'd):
#
#   1. Apple's `libtool -static` (invoked internally by Ghostty's own
#      GhosttyLib.zig to merge the compiled "ghostty" module with its C
#      dependencies into one archive) silently *drops* several archive
#      members it warns are "not 8-byte aligned" -- including
#      libghostty_zcu.o, the object that contains the entire public C API
#      (ghostty_init, ghostty_config_*, etc). The resulting libghostty.a
#      built by Task 3 links, but exports none of the public API. This
#      reproduced identically across repeated clean rebuilds, so it is a
#      real toolchain/upstream defect, not a one-off fluke.
#   2. On Darwin, `zig build` never installs a loose libghostty.dylib at
#      all -- upstream's own build.zig comment says so explicitly:
#      "We shouldn't have this guard but we don't currently build on macOS
#      this way ironically so we need to fix that."
#
# Rather than reverse-engineer Ghostty's entire dependency link line by hand
# (glslang, spirv-cross, sentry, simdutf, libintl, freetype, harfbuzz, ...),
# this script applies a small, reviewed patch
# (third_party/patches/ghostty-install-macos-shared-lib.patch) to the
# checked-out submodule before building:
#
#   - build.zig: removes the Darwin guard mentioned above so the *shared*
#     library target (already correctly linked by Zig's own linker, not
#     Apple's buggy libtool merge) gets installed as libghostty.dylib.
#   - src/build/SharedDeps.zig: adds `linkFramework("Metal")` and
#     `linkFramework("AppKit")`, which nothing in this checkout does
#     explicitly (pkg/macos's build.zig covers CoreFoundation/CoreGraphics/
#     CoreText/CoreVideo/QuartzCore/IOSurface/Carbon, but not Metal/AppKit).
#     Without this, linking the shared lib fails with
#     `undefined symbol: _MTLCopyAllDevices` and several
#     `_OBJC_CLASS_$_MTL*` errors -- this was never caught upstream because
#     Darwin has only ever linked libghostty via Xcode's own project
#     settings, never via a plain `zig build`.
#
# The patch is applied idempotently (skipped if already applied) so re-runs
# and CI are safe. It only touches the *build graph*, not any runtime
# behavior of libghostty itself.
#
# This script therefore no longer touches the xcframework/lipo path Task 3
# used; it invokes a plain per-architecture `zig build` (via -Dtarget)
# instead, which is simpler and sidesteps the libtool merge bug entirely for
# the shared lib. The static libghostty.a is still produced and copied too
# (for anything that later wants to statically link), but the primary,
# verified-correct deliverable used by the FFM binding is the .dylib.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

GHOSTTY_DIR="${GHOSTTY_DIR:-$repo_root/third_party/ghostty}"
OUT_DIR="${OUT_DIR:-$repo_root/build/native}"
GENERATED_DIR="${GENERATED_DIR:-$repo_root/build/generated}"
GHOSTTY_OPTIMIZE="${GHOSTTY_OPTIMIZE:-ReleaseFast}"
PATCH_FILE="$repo_root/third_party/patches/ghostty-install-macos-shared-lib.patch"

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

# --- Validate Xcode command line tools ---------------------------------------
#
# Building the shared lib still needs a full Xcode install (not just the
# stand-alone CLT): compiling Ghostty's Metal shaders shells out to
# `xcrun -sdk macosx metal`, which requires the Metal Toolchain component
# (`xcodebuild -downloadComponent MetalToolchain`).
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

# --- Apply the local build-graph patch, idempotently -------------------------
[[ -f "$PATCH_FILE" ]] || fail "Missing patch file '$PATCH_FILE'."

if git -C "$GHOSTTY_DIR" apply --check --reverse "$PATCH_FILE" >/dev/null 2>&1; then
    echo "  patch already applied: $(basename "$PATCH_FILE")"
elif git -C "$GHOSTTY_DIR" apply --check "$PATCH_FILE" >/dev/null 2>&1; then
    git -C "$GHOSTTY_DIR" apply "$PATCH_FILE"
    echo "  applied patch: $(basename "$PATCH_FILE")"
else
    fail "Patch '$PATCH_FILE' does not apply cleanly to '$GHOSTTY_DIR'
(HEAD $actual_commit). This usually means the pinned Ghostty commit moved
without updating the patch, or the submodule working tree has unrelated
local edits. Inspect with:
  git -C '$GHOSTTY_DIR' apply --check '$PATCH_FILE'"
fi

echo "== Building libghostty =="
echo "  zig:          $ZIG_BIN ($zig_version)"
echo "  ghostty dir:  $GHOSTTY_DIR"
echo "  commit:       $actual_commit"
echo "  optimize:     $GHOSTTY_OPTIMIZE"
echo "  out dir:      $OUT_DIR"

mkdir -p "$OUT_DIR/macos-x86_64" "$OUT_DIR/macos-arm64" "$OUT_DIR/include" "$GENERATED_DIR"

# --- Build once per architecture ---------------------------------------------
#
# Plain (non-xcframework) build: `-Demit-xcframework=false -Demit-macos-app=
# false` skips both the buggy libtool-merged static archive path and the
# Xcode app step; `-Dtarget=<arch>-macos` cross-compiles the shared+static
# libs for that architecture directly (this path does honor -Dtarget,
# unlike the xcframework "native" slice Task 3 found ignores it).
build_one_arch() {
    local zig_target="$1"   # e.g. x86_64-macos or aarch64-macos
    local out_subdir="$2"   # e.g. macos-x86_64 or macos-arm64
    local prefix
    prefix="$(mktemp -d "${TMPDIR:-/tmp}/ghostty-build-XXXXXX")"

    echo "-- building $out_subdir (target=$zig_target) --"
    (
        cd "$GHOSTTY_DIR"
        # -Dsentry=false is REQUIRED for embedding in a JVM: ghostty's
        # bundled Sentry/Breakpad crash handler installs a task-level Mach
        # exception handler that intercepts EXC_BAD_ACCESS before the JVM's
        # own handlers. The JVM triggers such exceptions ROUTINELY and
        # recovers from them (safepoint polls, implicit null checks);
        # Breakpad misreads the first one as a fatal crash, writes a
        # minidump, and _exit(1)s the whole process -- observed as the app
        # silently quitting (no hs_err, no crash report) the moment a
        # session's child process exited.
        "$ZIG_BIN" build \
            -Doptimize="$GHOSTTY_OPTIMIZE" \
            -Dtarget="$zig_target" \
            -Demit-xcframework=false \
            -Demit-macos-app=false \
            -Dsentry=false \
            --prefix "$prefix"
    )

    local dylib="$prefix/lib/libghostty.dylib"
    local staticlib="$prefix/lib/libghostty.a"
    local header="$prefix/include/ghostty.h"

    [[ -f "$dylib" ]] || fail "Expected '$dylib' not found after build for $out_subdir.
The zig build reported success but did not produce the expected artifact --
re-inspect third_party/ghostty/build.zig and the applied patch
($PATCH_FILE); the build graph may have changed upstream."
    [[ -f "$staticlib" ]] || fail "Expected '$staticlib' not found after build for $out_subdir."
    [[ -f "$header" ]] || fail "Expected '$header' not found after build for $out_subdir."

    # Hard acceptance gate: verify the public C API is actually exported,
    # not just that the linker succeeded. This is exactly the defect Task 4
    # found in Task 3's static-archive-only build (ghostty_init silently
    # missing), so never skip this check.
    #
    # NOTE: capture nm's output into a variable first, then grep it, rather
    # than piping `nm ... | grep -q ...` directly. `grep -q` exits as soon
    # as it finds a match, which can SIGPIPE the still-writing `nm` process;
    # with `set -o pipefail` that makes the pipeline report failure (nm's
    # non-zero/signal exit status) even though grep *did* find the symbol.
    nm_output="$(nm -g "$dylib" 2>/dev/null || true)"
    if ! grep -q ' T _ghostty_init$' <<<"$nm_output"; then
        fail "'$dylib' was built but does not export ghostty_init.
This is the exact defect this script's patch/build path was written to
avoid (see the big comment at the top of this script) -- something about
the build has changed. Inspect with: nm -g '$dylib' | grep ghostty_"
    fi

    cp "$dylib" "$OUT_DIR/$out_subdir/libghostty.dylib"
    cp "$staticlib" "$OUT_DIR/$out_subdir/libghostty.a"
    # Not rewriting the install name (id) here: FFM loads this dylib by an
    # explicit absolute path (SymbolLookup.libraryLookup), which does not
    # consult the library's own install name -- that only matters for a
    # *dependent* image resolving this one by name. (install_name_tool -id
    # was tried and rejected: the header has no spare load-command room,
    # so Apple's tool refuses with "the program must be relinked".)

    # Headers are identical across architectures; copy once, from whichever
    # arch builds first.
    if [[ ! -f "$OUT_DIR/include/ghostty.h" ]]; then
        rsync -a --delete "$prefix/include/" "$OUT_DIR/include/"
    fi

    rm -rf "$prefix"
}

build_one_arch "x86_64-macos"  "macos-x86_64"
build_one_arch "aarch64-macos" "macos-arm64"

for arch_dir in macos-x86_64 macos-arm64; do
    arch_name="${arch_dir#macos-}"
    [[ "$arch_name" == "x86_64" ]] && expect="x86_64" || expect="arm64"
    actual_arch="$(file -b "$OUT_DIR/$arch_dir/libghostty.dylib" | grep -oE 'x86_64|arm64')"
    [[ "$actual_arch" == "$expect" ]] || fail "'$OUT_DIR/$arch_dir/libghostty.dylib' is architecture '$actual_arch', expected '$expect'."
done

[[ -f "$OUT_DIR/include/ghostty.h" ]] || fail "ghostty.h missing from copied headers at '$OUT_DIR/include'."

# --- Record build metadata ---------------------------------------------------
cat > "$GENERATED_DIR/ghostty-version.properties" <<EOF
# Generated by scripts/build-ghostty.sh. Do not edit by hand.
ghostty.commit=$actual_commit
ghostty.tag=v1.3.1
ghostty.optimize=$GHOSTTY_OPTIMIZE
ghostty.patch=third_party/patches/ghostty-install-macos-shared-lib.patch
zig.version=$zig_version
EOF

echo "== libghostty build complete =="
echo "  $OUT_DIR/macos-x86_64/libghostty.dylib (+ libghostty.a)"
echo "  $OUT_DIR/macos-arm64/libghostty.dylib (+ libghostty.a)"
echo "  $OUT_DIR/include/ghostty.h"
echo "  $GENERATED_DIR/ghostty-version.properties"
