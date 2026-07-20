#!/bin/bash
# The `build/image/bin/claude-project-manager` launcher (plan section 23.2),
# copied verbatim into the jlink runtime image by the `runtimeImage` task
# (see buildSrc/src/main/kotlin/cpm/tasks/RuntimeImageTask.kt). No
# build-time substitution happens: this file is the launcher, byte for byte.
#
# Deviations from the plan section 23.2 example worth noting explicitly:
#
# - No `-Djava.library.path=$APP_HOME/lib` is set. This project's native
#   loading (GhosttyNativeLibrary, CpmTerminalHostLibrary) always uses
#   SymbolLookup.libraryLookup(<absolute path>, Arena.global()), never
#   System.loadLibrary/System.load relative-name lookup, so
#   java.library.path is never consulted by this codebase. Setting it
#   anyway to a flat $APP_HOME/lib would also be actively wrong here,
#   since lib/ contains macos-x86_64/ and macos-arm64/ subdirectories,
#   not the .dylib files directly. Plan section 23.2's own last line
#   ("do not add speculative JVM flags") is followed over the letter of
#   its example.
# - The entry point defaults to app.cpm.Main, the real application. Earlier
#   (Gate 0F / Task 8, Milestones 0-4) this defaulted to the Gate 0C
#   terminal spike instead, since the real application was still an empty
#   window or had no embedded terminal yet. Flipped once Milestone 5 gave
#   app.cpm.Main a real embedded terminal (managed Claude sessions in
#   tabs). CPM_MAIN_CLASS=app.cpm.terminal.Gate0cSpikeLauncher (or any
#   other gateNSpikeLauncher) still selects a Phase 0 spike manually if
#   needed for native/terminal-packaging-only verification (plan section 7
#   "Gate 0F" / section 28 "Task 8") -- note the spikes live in the spike
#   source set and are NOT bundled in the image; point CPM_EXTRA_JVM_ARGS
#   at their classes explicitly if ever needed.
set -euo pipefail

# Resolves the installation directory without depending on the current
# working directory (plan section 23.2), following symlinks so this still
# works if invoked through one (e.g. from /usr/local/bin).
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
BIN_DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
APP_HOME="$(cd -P "$BIN_DIR/.." >/dev/null 2>&1 && pwd)"

# CPM_MAIN_CLASS / CPM_EXTRA_JVM_ARGS are internal escape hatches, e.g. to
# launch a Phase 0 gateNSpikeLauncher instead of the real app for
# native/terminal-packaging-only verification (plan section 22.5); see the
# header comment above for why the default main class is app.cpm.Main (the
# real application).
MAIN_CLASS="${CPM_MAIN_CLASS:-app.cpm.Main}"

exec "$APP_HOME/runtime/bin/java" \
  --enable-native-access=ALL-UNNAMED \
  --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -Djava.awt.headless=false \
  -Xdock:name="Claude Project Manager" \
  -Xdock:icon="$APP_HOME/lib/app-icon.icns" \
  -Dapp.cpm.ghostty.nativeDir="$APP_HOME/lib" \
  -Dapp.cpm.terminalhost.nativeDir="$APP_HOME/lib" \
  ${CPM_EXTRA_JVM_ARGS:-} \
  -cp "$APP_HOME/app/*" \
  "$MAIN_CLASS" "$@"
