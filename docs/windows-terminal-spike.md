# Windows terminal: JediTermFX + pty4j spike

Status: **backend integrated behind the `TerminalFactory` seam; verified on macOS
(macOS `app:test` + the `nodeTerminalSpike` integration harness) and on Windows
CI (`jeditermSpike` + `nodeTerminalSpike` on `windows-latest`). Full-app boot on
Windows + packaging are the remaining follow-ups.** This document records the
constraint, the backend, and what is and isn't yet wired.

## Why Windows needs a different terminal backend

Drydock's macOS terminal embeds the **full** `libghostty` — the Metal renderer
plus the terminal surface — as a native AppKit `NSView` glued into JavaFX via
FFM (`app.drydock.terminal.ghostty` + `app.drydock.terminal.host`). That stack is
macOS-only on two independent axes:

- **Metal rendering** — there is no Metal on Windows.
- **The AppKit host shim** (`native-host/DrydockTerminalHost.m`) — AppKit does
  not exist on Windows.

Upstream Ghostty's own Windows support is in progress with no firm timeline
([ghostty-org/ghostty#2563](https://github.com/ghostty-org/ghostty/discussions/2563)),
and a Ghostty maintainer states plainly
([#11610](https://github.com/ghostty-org/ghostty/discussions/11610)) that only
`libghostty-vt` — the terminal *parser*, no rendering, no PTY, no UI — is tested
and supported on Windows. The full `libghostty` (with rendering) is not. So the
current architecture cannot be "just rebuilt for Windows"; the rendering and
embedding layers both need replacing.

## The candidate: JediTermFX + pty4j

- [**JediTermFX**](https://github.com/techsenger/jeditermfx) (`com.techsenger.jeditermfx`)
  — a port of JetBrains' JediTerm to JavaFX. A real JavaFX `Node` (`JediTermFxWidget`)
  that renders the terminal in JavaFX and connects to any PTY backend through a
  small `TtyConnector` interface. LGPLv3 / Apache-2.0 dual.
- [**pty4j**](https://github.com/JetBrains/pty4j) (`org.jetbrains.pty4j:pty4j`)
  — JetBrains' PTY library. **Windows uses ConPTY**; macOS/Linux use the native
  forkpty path. Pure-Java + small per-OS native libs bundled in the jar.

This keeps the terminal embedded in the JavaFX window (no external
multiplexer, no separate window) and works on all three platforms from one
code path. The cost, accepted: it drops the "real native Metal-rendered Ghostty
surface" that was a stated macOS design goal — unavoidable on Windows, where
Metal/AppKit do not exist.

## The spike

`app/src/spike/java/app/drydock/terminal/JediTermFxSpike.java` (+ launcher),
run via `./gradlew jeditermSpike`. It opens a JavaFX window with a
`JediTermFxWidget` running the host shell (`/bin/bash --login` on macOS/Linux,
`cmd.exe` on Windows) over a pty4j PTY. In auto-exit mode (the gate task's
default) it sends `echo DRYDOCK_SPIKE_OK` + `exit` and tears down when the shell
exits. `-Papp.drydock.jediterm.interactive` leaves a live shell open.

Per AGENTS.md, spike harnesses live in the `spike` source set, not
`app/src/main/java`, and never ship in the app jar or the jlink image. The
spike is pure Java + pty4j's own native libs — no Zig, no Xcode, no libghostty,
no AppKit — so the `jeditermSpike` gate task does **not** depend on
`buildGhosttyNative`/`buildNativeHost` and runs on a machine without the
macOS native toolchain (including the Windows box it is meant for).

### Verified (macOS run, 2026-08-18)

- JediTermFX 1.1.0 (`jeditermfx-ui`, transitively `jeditermfx-core`) + pty4j
  0.13.10 resolve from Maven Central and **compile and run against the
  project's JDK 26 / JavaFX 26 in classpath mode** (no `--module-path`, no
  `--add-exports` beyond what the existing spike harness already sets).
- pty4j's native PTY lib loads (`Extracted pty4j native in 29 ms`).
- A PTY is created, a shell is launched in it, input written through the
  widget's `TtyConnector` reaches the shell (the `exit` it reads proves the
  input direction), and the shell exits cleanly (code 0) with the widget
  tearing down. `./gradlew jeditermSpike` → `BUILD SUCCESSFUL`.

### NOT verified from this host

- **The Windows ConPTY path** (`cmd.exe`) — the spike ran on macOS. The
  Windows branch is one `if (isWindows())` in `startShell()`; the PTY creation,
  rendering, and input handling are shared, but ConPTY-specific behavior
  (exit detection, resize, encoding) needs an actual Windows run.
- **Integration with `OpenSessionTab` / `TerminalBridge`** — the spike is a
  standalone window, not wired into the app (see "Integration plan" below).

### Windows verification in CI

The `jediterm-spike` job in `.github/workflows/tests.yml` runs
`./gradlew :app:jeditermSpike` on `windows-latest`, which is the only way to
exercise the ConPTY (`cmd.exe`) path from this macOS-only dev loop. The spike
is the one piece of the project that CAN run on Windows today (pure Java +
pty4j's own native libs, no Zig/Xcode/libghostty), so the job needs no native
toolchain and no submodule. It opens a real JavaFX window and auto-exits once
the shell does; `windows-latest` hosts the desktop session that needs. (If a
future runner image cannot, add the Monocle headless path the `:app:test` job
uses — `glass.platform=Monocle` + monocle on the spike runtime classpath —
rather than dropping the job.)

## Dependency notes (gotchas the spike uncovered)

- `org.openjfx` is **excluded** from `jeditermfx-ui`: the project already
  provides JavaFX 26 via the `javafx-gradle-plugin`, and `jeditermfx-ui`'s POM
  otherwise resolves an older, Linux-default-classifier JavaFX that clashes.
- `jeditermfx-ui:1.1.0` transitively pulls **pty4j 0.12.25**, which depends on
  `org.jetbrains.pty4j:purejavacomm:0.0.11.1` — and that artifact is **not
  published to Maven Central as a POM**, so the runtime classpath does not
  resolve. **pty4j 0.13.10 is forced** instead: it dropped the `purejavacomm`
  dependency entirely (its only deps are `kotlin-stdlib`, `jna`, `slf4j-api`).
  pty4j's public PTY API (`PtyProcessBuilder`/`PtyProcess`/`WinSize`) is stable
  across those versions, and `jeditermfx-ui` does not call pty4j classes
  directly (only the optional `jeditermfx-app` module does, which this spike
  avoids), so the override is safe.
- The `jeditermfx-app` module is **deliberately not depended on**: its POM
  hardcodes a Linux JavaFX classifier by default (via a Maven profile), which
  is wrong under Gradle on macOS/Windows. The ready `DefaultSettingsProvider`
  ships in `jeditermfx-ui` instead, and the spike's `SpikePtyConnector`
  replicates `app`'s `PtyProcessTtyConnector` (3 methods over
  `ProcessTtyConnector`) so no `app`-module classes are needed.
- `slf4j-jdk14` (spike runtime-only) routes JediTermFX's slf4j logs through
  `java.util.logging`, the logger the rest of the app already uses.

## Integration (done)

The backend is now wired in behind the `TerminalFactory` seam, selected by
platform (`native` on macOS, `jediterm` on Windows) with a
`-Dapp.drydock.terminal.backend=jediterm|native` override for testing on any
host. The approach turned out lower-churn than the original plan below
anticipated: rather than introduce a new Node-shaped contract, the JediTermFX
backend **implements the existing `terminal.api` interfaces**
(`JediTermFxRuntime`, `JediTermFxHostView`, `JediTermFxSurface` in
`app.drydock.terminal.jediterm`), with no-ops for the native-overlay-shaped
methods. Those no-ops are the *correct* implementation, not a stub: a JavaFX
`Node` in the scene graph is laid out, shown, focused, and given keyboard/mouse
input by JavaFX + JediTermFX itself, so the macOS path's raw-AppKit-event
routing and native-view positioning genuinely have nothing to do here.

The one interface extension is `TerminalHostView.embeddedNode()` (default
`Optional.empty()`, so the macOS AppKit host is unchanged): the JediTermFX
host returns its widget's pane, and `OpenSessionTab` drops it into its
`placeholder` (and the shell sub-tab's `shellPlaceholder`) on attach and removes
it on dispose. macOS `app:test` passes unchanged -- the AppKit host inherits the
empty default and the Node path is never taken.

`SessionManager`, `OpenSessionTab`, `TerminalBridge`, and `MainWorkspace` are
**backend-agnostic**: they consume the trio through `TerminalFactory` and the
`terminal.api` interfaces and never branch on the backend themselves. The
command model adapts inside `JediTermFxRuntime.openSurface`: the spec's command
string runs through `bash -lc` (POSIX) or `cmd.exe /c` (Windows), mirroring how
the macOS path lets libghostty wrap the command in a login shell.

Verification: `./gradlew nodeTerminalSpike` (forces `backend=jediterm`) drives
the real `TerminalFactory` + impls through `openSurface`/`processExited`/
`closeGracefully` + the embedded-node wiring; it passes on macOS and runs on
the Windows CI job. macOS `app:test` passes (AppKit path untouched).

### Remaining (the follow-ups)

- **Full-app boot + terminal-only session on Windows** is verified in CI by
  the `bootSpike (Windows)` job: it boots the real `app.drydock.Main` on
  `windows-latest`, registers a throwaway repo, and opens a terminal-only
  session whose command (`echo ...`) runs via ConPTY through the real
  `SessionManager`/`OpenSessionTab`/JediTermFX path -- no `claude` installed (a
  `app.drydock.diag.command` override bypasses the agent provider, and
  `openNewSessionWithDefaultAgent` proceeds with a registered kind when that
  override is set). The app exits cleanly via its own `diagQuit` watchdog, so
  the task's exit code is the pass/fail. A `./gradlew run` on a Windows dev
  box is still blocked by the macOS-only `-Xdock:name` in
  `applicationDefaultJvmArgs` (the bootSpike task sets its own JVM args without
  it); gating that flag to macOS is a separate dev-experience fix. A true
  end-to-end *Claude* session on Windows remains a manual step (run Drydock on
  a Windows box with `claude` installed) -- CI cannot install `claude`.
- **Runtime re-theming** (`TerminalRuntime.updateConfig`) is a no-op on the
  JediTermFX backend -- JediTermFX theming goes through a `SettingsProvider`,
  not a hot config-file reload; a theme bridge is deferred. The terminal opens
  dark (a `DarkSettingsProvider`) so it does not flash white on the dark
  window.
- **The shell sub-tab on Windows**: `openSurface` runs `cmd.exe /c <command>`,
  which nests an interactive `cmd.exe` harmlessly but is lightly tested.
- **`readScreenText`** returns `""` (not wired to JediTermFX's text buffer).
- **Windows packaging** (`runtimeImageWindows`) + the README "how to get the
  packaged Windows app" section, following the existing cross-arch pattern:
  cross-jlink to Windows (Windows jmods, Windows JavaFX `win`-classifier jars),
  a `.bat`/`.cmd` launcher without the macOS-only `-Xdock:name`/`-Xdock:icon`
  flags and without native-dylib bundling (none for Windows), no `.app`/`Info.plist`.
  Documented only once the full app boots on Windows.
- **Build size**: jeditermfx + pty4j (+ JNA, kotlin-stdlib) are now on `main`'s
  classpath and so ship in the macOS app jar / jlink image too, even though they
  are unused on macOS. A few MB; a Windows-only packaging split can trim it
  later.

### Original plan (kept for the reasoning)


The existing `terminal.api` seam is **macOS-native-shaped, not neutral**, so a
JediTermFX backend is not a drop-in behind `TerminalFactory`:

- `TerminalHostView` is explicitly "a native view embedded as an overlay" —
  its only implementation is the AppKit shim. `TerminalSurface.dispatchKeyEvent`
  takes raw **AppKit** keyCodes/modifierFlags. `TerminalSpec` is POSIX-shell
  (`${SHELL:-/bin/zsh}`, `exec -l`) — not Windows.
- `OpenSessionTab` mounts the terminal via a `TerminalBridge` that overlays a
  native view on a `placeholder` `StackPane` and translates raw AppKit events.
  A JediTermFX backend is a pure-JavaFX `Node` added straight to the scene
  graph that handles its own keyboard/mouse — a different mounting model with
  no raw native key events to translate.

The integration is therefore a real refactor of the terminal boundary, not a
new impl of the existing interfaces. The shape that fits the codebase's own
stated direction (`TerminalFactory`'s Javadoc: "Phase B replaces the direct
impl references with a provider lookup"):

1. Introduce a **JavaFX-`Node`-shaped terminal contract** alongside the native
   one (e.g. a `TerminalPane` that is a `javafx.scene.Node` + a small lifecycle),
   or generalize `TerminalRuntime`/`TerminalSurface` to a model that admits a
   pure-JavaFX impl. The native-surface path stays for macOS; the Node path is
   the Windows (and optional cross-platform) backend.
2. **Select by platform at `TerminalFactory`**: macOS keeps the Ghostty-native
   trio; Windows (and any platform without the native build) gets the
   JediTermFX `Node`. `NativeLibraryLocator.detectArchDirectoryName()`'s
   hard throw on non-macOS arches becomes the selection point.
3. `OpenSessionTab`'s `placeholder` gains a **Node-mounted path**: instead of
   overlaying a native view + forwarding raw key events, add the JediTermFX
   `Node` as a child and let it own input. `TerminalBridge`'s
   geometry/raw-event machinery is macOS-only behind this branch.
4. `TerminalSpec` becomes platform-aware: a login shell on macOS, `cmd.exe`
   (or a configured shell / `claude` invocation) on Windows — the spec already
   encodes "what to run," it just needs a Windows variant instead of assuming
   POSIX.

Once that lands, the **Windows packaging** (`runtimeImageWindows`) follows
the existing cross-arch pattern: cross-jlink to Windows (Windows jmods via a
`download-cross-jmods` variant, Windows JavaFX `win`-classifier jars), a
`.bat`/`.cmd` launcher **without** the macOS-only `-Xdock:name`/`-Xdock:icon`
flags and **without** the native-dylib bundling step (there are none for
Windows), and no `.app`/`Info.plist` wrap. A top-level `windowsAppImage` /
`runtimeImageWindows` alias mirrors `appImage`/`runtimeImage`. The README's
"how to get the packaged Windows app" section is written **after** the
integration is verified on Windows — documenting a package that crashes on
session-open would be dishonest, and the terminal is the point of the app.

## Running the spike

```bash
./gradlew jeditermSpike                              # auto-exit; pass/fail from logs
./gradlew jeditermSpike -Papp.drydock.jediterm.interactive   # live shell, human drives
```