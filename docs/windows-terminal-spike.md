# Windows support

This document covers what runs on Windows today, what is intentionally not
on Windows, and how a colleague gets Drydock running on their own Windows
machine. The companion [`docs/windows.md`](windows.md) is the install/run
guide for end users; this file is the technical rationale and the list of
known limitations.

## TL;DR

Drydock is **usable on Windows** today, with the JediTermFX + pty4j
terminal backend. Two paths to run it:

- **Dev loop**: `./gradlew :app:run -Papp.drydock.terminal.backend=jediterm` (or
  the default, which is `jediterm` on Windows).
- **Self-contained runtime image**: `./gradlew :app:windowsRuntimeImage`
  produces `build/image-windows/`, a directory a colleague unzips anywhere
  and launches with `bin/drydock.bat`. No installer, no MSIX, no code
  signing.

Both are exercised on `windows-latest` in CI: the `gradleRunSmoke` job
boots the real `app.drydock.Main` and opens a JediTermFX/ConPTY session;
the `windowsRuntimeImage` job builds the runtime image and asserts its
layout. The JediTermFX backend is the production Windows terminal
backend; the macOS path keeps the libghostty/Metal/AppKit stack unchanged.

## Why the rendering backend differs from macOS

Drydock's macOS terminal embeds the full `libghostty` (Metal renderer +
terminal surface) as a native AppKit `NSView`, glued into JavaFX via FFM
through `app.drydock.terminal.ghostty` + `app.drydock.terminal.host`. That
stack is macOS-only on two independent axes:

- **Metal rendering** — no Metal on Windows.
- **The AppKit host shim** (`native-host/DrydockTerminalHost.m`) — AppKit
  does not exist on Windows.

Upstream Ghostty's own Windows support is in progress with no firm timeline
([ghostty-org/ghostty#2563](https://github.com/ghostty-org/ghostty/discussions/2563)),
and a Ghostty maintainer states plainly
([#11610](https://github.com/ghostty-org/ghostty/discussions/11610)) that only
`libghostty-vt` — the terminal *parser*, no rendering, no PTY, no UI — is
tested and supported on Windows. The full `libghostty` (with rendering) is
not. So the macOS architecture cannot be "just rebuilt for Windows"; both
the rendering and the embedding layers need replacing.

The replacement is [**JediTermFX**](https://github.com/techsenger/jeditermfx)
+ [**pty4j**](https://github.com/JetBrains/pty4j): a pure-JavaFX
`JediTermFxWidget` over a ConPTY PTY (Windows) or forkpty (macOS/Linux).
LGPLv3 / Apache-2.0 dual. The cost, accepted: it drops the native
Metal-rendered Ghostty surface that was a macOS design goal — unavoidable on
Windows, where Metal/AppKit do not exist. JediTermFX renders to a
JavaFX node and handles its own keyboard/mouse/focus, so the
OpenSessionTab mounting model is the JavaFX `Node` (added to the
placeholder `StackPane`) rather than the macOS native-view overlay
(attached to a Glass `Window`).

## The integration

The JediTermFX backend is wired in behind the existing `TerminalFactory`
seam (`app.drydock.terminal.TerminalFactory`), selected by platform
(`native` on macOS, `jediterm` on Windows) with a
`-Dapp.drydock.terminal.backend=jediterm|native` override for testing on
any host. The integration is lower-churn than the original plan
anticipated: rather than introduce a new Node-shaped contract, the
JediTermFX backend **implements the existing `terminal.api` interfaces**
(`JediTermFxRuntime`, `JediTermFxHostView`, `JediTermFxSurface` in
`app.drydock.terminal.jediterm`), with no-ops for the native-overlay-shaped
methods. Those no-ops are the *correct* implementation, not a stub: a
JavaFX `Node` in the scene graph is laid out, shown, focused, and given
keyboard/mouse input by JavaFX + JediTermFX itself, so the macOS path's
raw-AppKit-event routing and native-view positioning genuinely have
nothing to do here.

The one interface extension is `TerminalHostView.embeddedNode()` (default
`Optional.empty()`, so the macOS AppKit host is unchanged): the JediTermFX
host returns its widget's pane, and `OpenSessionTab` drops it into its
`placeholder` (and the shell sub-tab's `shellPlaceholder`) on attach and
removes it on dispose. macOS `app:test` passes unchanged — the AppKit
host inherits the empty default and the Node path is never taken.

`SessionManager`, `OpenSessionTab`, `TerminalBridge`, and
`MainWorkspace` are **backend-agnostic**: they consume the trio through
`TerminalFactory` and the `terminal.api` interfaces and never branch on
the backend themselves. The command model adapts inside
`JediTermFxRuntime.openSurface`: the spec's command string runs through
`bash -lc` (POSIX) or `cmd.exe /c` (Windows), mirroring how the macOS
path lets libghostty wrap the command in a login shell.

### Dependency notes (gotchas the spike uncovered)

- `org.openjfx` is **excluded** from `jeditermfx-ui`: the project already
  provides JavaFX 26 via the `javafx-gradle-plugin`, and `jeditermfx-ui`'s
  POM otherwise resolves an older, Linux-default-classifier JavaFX that
  clashes.
- `jeditermfx-ui:1.1.0` transitively pulls **pty4j 0.12.25**, which
  depends on `org.jetbrains.pty4j:purejavacomm:0.0.11.1` — and that
  artifact is **not published to Maven Central as a POM**, so the runtime
  classpath does not resolve. **pty4j 0.13.10 is forced** instead: it
  dropped the `purejavacomm` dependency entirely (its only deps are
  `kotlin-stdlib`, `jna`, `slf4j-api`). pty4j's public PTY API
  (`PtyProcessBuilder`/`PtyProcess`/`WinSize`) is stable across those
  versions, and `jeditermfx-ui` does not call pty4j classes directly
  (only the optional `jeditermfx-app` module does, which this spike
  avoids), so the override is safe.
- The `jeditermfx-app` module is **deliberately not depended on**: its
  POM hardcodes a Linux JavaFX classifier by default (via a Maven
  profile), which is wrong under Gradle on macOS/Windows. The ready
  `DefaultSettingsProvider` ships in `jeditermfx-ui` instead.
- `slf4j-jdk14` (runtime) routes JediTermFX's slf4j logs through
  `java.util.logging`, the logger the rest of the app already uses.

## CI

| Job | What it verifies |
|---|---|
| `app:test` (macos-14) | The macOS test suite; unchanged. |
| `jeditermSpike (Windows)` | The raw JediTermFX widget + pty4j spike (the old `JediTermFxSpike` harness in the spike source set), proving the ConPTY path runs at all. |
| `bootSpike (Windows)` | The real `app.drydock.Main` boots on Windows and opens a terminal-only session through the production `SessionManager` / `OpenSessionTab` / JediTermFX path. App exits via its own `diagQuit`, task exit code is the pass/fail. |
| `gradleRunSmoke (Windows)` | The actual `./gradlew :app:run` task on Windows. The regression net for the `applicationDefaultJvmArgs` list (a regression there would crash the Windows JVM at launch with "Unrecognized option" — `bootSpike` uses its own `jvmArgs` and would not catch it). |
| `windowsRuntimeImage (Windows)` | The `windowsRuntimeImage` task builds the runtime image, asserts the layout (`bin/drydock.bat` exists, `runtime/bin/java.exe` exists, `app/` has jars), uploads `build/image-windows` as a 7-day artifact. |

## Intentionally not on Windows

These are explicit, conscious limitations of the v1 Windows build, not
oversights. Each is an isolated, bounded piece of work that a future PR
can address without changing the rest of the project.

- **No `libghostty` port.** A `git worktree` of the current pinned
  Ghostty submodule (`332b2aef`) and the `buildGhosttyNative` Gradle task
  (`scripts/build-ghostty.sh`) is macOS-only by construction: the
  `buildGhosttyNative` task runs `scripts/build-ghostty.sh`, which calls
  Zig + Xcode-only tooling. Upstream Ghostty's Windows support is
  upstream's work, not Drydock's. A colleague who needs the full
  libghostty surface on Windows is working upstream of Drydock.
- **No native host shim.** `native-host/DrydockTerminalHost.m` is AppKit
  and does not compile on Windows. A Windows port would be a Win32
  message pump + surface creation, in a new source set or
  a separate native-host tree; it is not in v1 scope.
- **No dock-tile label.** The `-Xdock:name=Drydock` and `-Xdock:icon=...`
  JVM args are macOS-only (the HotSpot JVM rejects `-Xdock:*` on
  Windows); `applicationDefaultJvmArgs` gates them on `os.name` and
  `launcher.bat` carries the same exclusion. The Windows launcher
  doesn't set a dock name; Windows taskbar shows the running .bat
  filename ("drydock.bat" or however a colleague renames it).
- **No `.app` bundle / `.dmg`.** macOS-only conventions; the Windows
  task produces a directory, not an installer. A real `MSIX` /
  signed `.exe` is a packaging follow-up; v1 is the zipped directory
  + `bin/drydock.bat`.
- **No `app.drydock.terminal.backend=ghostty` on Windows.** Selecting
  the ghostty backend on Windows would crash at `GhosttyNativeLibrary`
  lookup (the JVM tries to load `libghostty.dylib` which does not
  exist on Windows). The platform default is `jediterm`; an explicit
  `-Dapp.drydock.terminal.backend=ghostty` is honored and crashes with
  a clear error, which is the right shape.
- **`readScreenText` returns `""`.** Not wired to JediTermFX's text
  buffer. The review board / PR-diffs flow that uses this is not
  tested on Windows and may show empty diffs; it does not crash.
- **Terminal re-theming is a no-op.** `TerminalRuntime.updateConfig` is
  wired for the libghostty path; the JediTermFX backend ignores it.
  JediTermFX theming goes through a `SettingsProvider`, not a hot
  config-file reload. The terminal opens dark (a
  `DarkSettingsProvider`) so it does not flash white on the dark window.
- **No `claude`-on-Windows verification in CI.** CI runs
  `windows-latest` without `claude` installed; the smoke jobs use
  `app.drydock.diag.command=echo ...` to bypass the agent provider. A
  colleague with `claude` on Windows should be able to point Drydock at
  it through the same Settings → Agent picker the macOS app uses; this
  path is **manually tested, not CI-verified**.
- **Build size on macOS.** jeditermfx + pty4j (+ JNA, kotlin-stdlib)
  are on `main`'s classpath, so they ship in the macOS app jar / jlink
  image too, even though they are unused on macOS. A few MB; a
  Windows-only packaging split can trim it later.

## Verifying on a Windows dev box

From a clean checkout with JDK 26 on `PATH`:

```bash
# 1. Dev loop: launches the real app, opens a real JavaFX window
./gradlew :app:run -Papp.drydock.terminal.backend=jediterm

# 2. Build the self-contained runtime image
./gradlew :app:windowsRuntimeImage
ls build/image-windows/
# build/image-windows/
#   app/                                # drydock.jar + every runtime classpath jar
#   bin/drydock.bat                     # the launcher
#   runtime/                            # jlinked JDK 26 + JavaFX 26
#     bin/java.exe

# 3. Or, in a single command from any directory:
build/image-windows/bin/drydock.bat
```

Diagnostic flags the same as on macOS (forwarded to the forked JVM as
`-P` project properties, per the convention documented in
`app/build.gradle.kts`):

- `-Papp.drydock.terminal.backend=jediterm` (Windows default; explicit
  override for clarity on a Mac dev box).
- `-Papp.drydock.diag.repo=<path>` + `-Papp.drydock.diag.autoCreateSession=true`
  + `-Papp.drydock.diag.command=<command>` — terminal-only session,
  bypassing the agent provider.
- `-Papp.drydock.diag.autoExitSeconds=N` — clean `diagQuit` exit after
  `N` seconds (the CI smoke pattern).
- `-Papp.drydock.diag.stateFile=<path>` — isolate persisted app state
  to a throwaway file for CI.

## What the Windows runtime image contains

- `app/drydock.jar` + every entry of the macOS app's runtime classpath
  (JavaFX 26 base/controls/graphics, RichTextFX, JediTermFX + pty4j +
  JNA + kotlin-stdlib, slf4j-jdk14, etc.) — same jars the macOS
  `runtimeImage` task copies, minus nothing (the Mac task adds the
  native libraries under `lib/`, which is the only Mac-only piece).
- `runtime/` — `jlink`'d JDK 26 + the JavaFX 26 modules
  (`java.base,java.logging,java.desktop,java.net.http,java.xml,
  jdk.httpserver,jdk.jfr,jdk.unsupported,javafx.base,javafx.controls,
  javafx.graphics`).
- `bin/drydock.bat` — sets `APP_HOME` from `%~dp0`, then runs
  `<APP_HOME>\runtime\bin\java.exe` with the same JVM args as the
  macOS launcher (modulo the dropped `-Xdock:*` and native-dir flags
  that have no Windows analog).

No `.app` bundle, no `lib/` of native dylibs, no dock icon — none of
those have a Windows analog in this project.

The same jlink module set is used by `RuntimeImageTask.assemble()` and
`WindowsRuntimeImageTask.assemble()`; if the application's needs
change, both lists must change in lockstep (the new task's class
Javadoc calls this out).
