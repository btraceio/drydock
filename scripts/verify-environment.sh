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

# This machine's real CPU, seen through Rosetta: uname(3) reports x86_64 inside
# a translated process, so consult Apple's sysctl.proc_translated flag first
# (1 = translated; absent, hence the fallback, on a genuine Intel Mac).
# https://developer.apple.com/documentation/apple-silicon/about-the-rosetta-translation-environment
hardware_cpu() {
    if [[ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" == "1" ]]; then
        echo arm64
    else
        uname -m
    fi
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

    local uname_arch translated hardware_arch
    uname_arch="$(uname -m)"
    translated="$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)"
    hardware_arch="$(hardware_cpu)"

    echo "hardware CPU:       $hardware_arch"
    echo "this shell (uname): $uname_arch"

    if [[ "$translated" == "1" ]]; then
        echo "PROBLEM: this shell runs under Rosetta 2 (sysctl.proc_translated=1)."
        echo "  Everything launched from it -- Homebrew, gradlew, the JVM -- defaults to"
        echo "  x86_64, and this project picks the native slice from the JVM's os.arch, so"
        echo "  the build produces an x86_64 app (x86_64 libghostty) on arm64 hardware."
        echo "  Start a native shell first: arch -arm64 /bin/zsh"
        fail=1
    fi

    # The JVM's os.arch -- not the machine's -- is what selects
    # build/native/<arch>/libghostty.dylib at runtime (NativeLibraryLocator) and
    # what the packaging tasks bundle (buildSrc drydock.packaging.gradle.kts).
    # An x86_64 JDK runs fine on Apple Silicon under Rosetta and reports
    # os.arch=x86_64, which is exactly how an all-x86_64 build happens there.
    local java_bin=""
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        java_bin="$JAVA_HOME/bin/java"
    elif command -v java >/dev/null 2>&1; then
        java_bin="$(command -v java)"
    fi

    if [[ -n "$java_bin" ]]; then
        local java_arch
        java_arch="$("$java_bin" -XshowSettings:properties -version 2>&1 \
            | awk -F' = ' '/^ *os\.arch/ {print $2; exit}')"
        echo "java os.arch:       ${java_arch:-unknown} ($java_bin)"
        if [[ "$hardware_arch" == "arm64" && "$java_arch" == "x86_64" ]]; then
            echo "PROBLEM: that JDK is an x86_64 build on arm64 hardware."
            echo "  Install a native arm64 JDK; a translated one makes every arch-dependent"
            echo "  part of the build (native slice selection, jlink output) x86_64."
            fail=1
        fi
    else
        echo "java os.arch:       (no java found to inspect)"
    fi

    echo "Supported per docs/implementation-plan.md deviation: x86_64 and arm64 macOS."
    echo "The JDKs that actually decide this are Gradle's daemon JVM"
    echo "(gradle/gradle-daemon-jvm.properties, toolchainVersion=17) and the JDK 26"
    echo "toolchain -- list them with: ./gradlew -q javaToolchains"
}

check_zig() {
    section "zig 0.15.x (for libghostty)"
    # Same candidate order scripts/build-ghostty.sh uses, so this reports the
    # zig the build will actually pick: the Homebrew prefix matching this
    # machine's real CPU first (/opt/homebrew on Apple Silicon, /usr/local on
    # Intel or an Intel/Rosetta Homebrew), then the other, then PATH.
    local zig_bin="" candidate
    local zig_candidates=(/usr/local/opt/zig@0.15/bin/zig /opt/homebrew/opt/zig@0.15/bin/zig)
    if [[ "$(hardware_cpu)" == "arm64" ]]; then
        zig_candidates=(/opt/homebrew/opt/zig@0.15/bin/zig /usr/local/opt/zig@0.15/bin/zig)
    fi
    for candidate in "${zig_candidates[@]}"; do
        if [[ -x "$candidate" ]]; then
            zig_bin="$candidate"
            break
        fi
    done
    if [[ -z "$zig_bin" ]] && command -v zig >/dev/null 2>&1; then
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
