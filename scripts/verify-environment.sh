#!/usr/bin/env bash
#
# Verifies that the local machine has the prerequisites needed to build and
# run Drydock, and prints their versions/paths.
#
# See README.md for the full list of prerequisites and known gotchas
# (in particular: Gradle 8.11.1 must itself run on JDK <= ~24; JDK 26 is
# used as a Gradle *toolchain* to compile/run the application).

set -uo pipefail

fail=0

section() {
    printf '\n== %s ==\n' "$1"
}

check_jdk26() {
    section "JDK 26"

    local candidates=()
    if [[ -n "${JAVA_HOME:-}" ]]; then
        candidates+=("$JAVA_HOME/bin/java")
    fi
    if command -v java >/dev/null 2>&1; then
        candidates+=("$(command -v java)")
    fi
    if [[ -d "$HOME/.sdkman/candidates/java" ]]; then
        for d in "$HOME"/.sdkman/candidates/java/26*; do
            [[ -x "$d/bin/java" ]] && candidates+=("$d/bin/java")
        done
    fi

    local found=0
    local seen=()
    for java_bin in "${candidates[@]}"; do
        [[ -x "$java_bin" ]] || continue
        # de-dup
        local already=0
        for s in "${seen[@]:-}"; do
            [[ "$s" == "$java_bin" ]] && already=1
        done
        [[ $already -eq 1 ]] && continue
        seen+=("$java_bin")

        local ver
        ver=$("$java_bin" -version 2>&1 | head -1)
        if [[ "$ver" == *'"26'* ]]; then
            echo "OK: $java_bin"
            echo "    $ver"
            found=1
        fi
    done

    if [[ $found -eq 0 ]]; then
        echo "MISSING: no JDK 26 found on JAVA_HOME, PATH, or ~/.sdkman/candidates/java."
        echo "  Install with: sdk install java 26.0.1-tem"
        fail=1
    fi
}

check_git() {
    section "git"
    if command -v git >/dev/null 2>&1; then
        echo "OK: $(command -v git)"
        echo "    $(git --version)"
    else
        echo "MISSING: git executable not found on PATH."
        fail=1
    fi
}

check_claude() {
    section "claude CLI"
    local claude_bin=""
    if command -v claude >/dev/null 2>&1; then
        claude_bin="$(command -v claude)"
    elif [[ -x "$HOME/.local/bin/claude" ]]; then
        claude_bin="$HOME/.local/bin/claude"
    fi

    if [[ -n "$claude_bin" ]]; then
        echo "OK: $claude_bin"
        echo "    $("$claude_bin" --version 2>&1 | head -1)"
    else
        echo "MISSING: claude CLI not found on PATH or in ~/.local/bin."
        fail=1
    fi
}

check_gradle() {
    section "Gradle wrapper"
    if [[ -x "$(dirname "$0")/../gradlew" ]]; then
        echo "OK: $(dirname "$0")/../gradlew present"
    else
        echo "MISSING: gradlew wrapper script not found."
        fail=1
    fi
}

check_arch() {
    section "Architecture"
    echo "os.arch (uname -m): $(uname -m)"
    echo "Supported per docs/implementation-plan.md deviation: x86_64 and arm64 macOS."
}

check_zig() {
    section "zig 0.15.x (for libghostty)"
    local zig_bin=""
    if [[ -x /usr/local/opt/zig@0.15/bin/zig ]]; then
        zig_bin=/usr/local/opt/zig@0.15/bin/zig
    elif [[ -x /opt/homebrew/opt/zig@0.15/bin/zig ]]; then
        zig_bin=/opt/homebrew/opt/zig@0.15/bin/zig
    elif command -v zig >/dev/null 2>&1; then
        zig_bin="$(command -v zig)"
    fi

    if [[ -z "$zig_bin" ]]; then
        echo "MISSING: no zig found (checked /usr/local/opt/zig@0.15, /opt/homebrew/opt/zig@0.15, PATH)."
        echo "  Install with: brew install zig@0.15"
        fail=1
        return
    fi

    local ver
    ver="$("$zig_bin" version)"
    if [[ "$ver" == 0.15.* ]]; then
        echo "OK: $zig_bin"
        echo "    $ver"
    else
        echo "WRONG VERSION: $zig_bin reports $ver; Ghostty requires exactly 0.15.x."
        echo "  Install the pinned version with: brew install zig@0.15"
        echo "  (does not need to replace the default 'zig' on PATH; see docs/native-integration.md)"
        fail=1
    fi
}

check_xcode() {
    section "Xcode command line tools (for libghostty)"
    if ! command -v xcodebuild >/dev/null 2>&1; then
        echo "MISSING: xcodebuild not found. Install full Xcode (not just the CLT) from the App Store."
        fail=1
        return
    fi
    local xcode_path
    xcode_path="$(xcode-select -p 2>/dev/null || true)"
    if [[ -n "$xcode_path" ]]; then
        echo "OK: xcode-select -p -> $xcode_path"
        echo "NOTE: building libghostty also requires the Metal Toolchain component"
        echo "      (xcodebuild -downloadComponent MetalToolchain) -- not checked here,"
        echo "      scripts/build-ghostty.sh will fail with a clear error if it's missing."
    else
        echo "MISSING: xcode-select has no configured path."
        echo "  Run: xcode-select -s /Applications/Xcode.app/Contents/Developer"
        fail=1
    fi
}

check_jdk26
check_git
check_claude
check_gradle
check_arch
check_zig
check_xcode

echo
if [[ $fail -ne 0 ]]; then
    echo "One or more required tools are missing. See messages above."
    exit 1
fi

echo "All required tools found."
exit 0
