# Drydock

Drydock is a desktop application for managing local Git repositories and
the [`claude`](https://docs.claude.com/en/docs/claude-code) CLI sessions you run
against them. Each repository gets a sidebar entry with live branch and
dirty-state indicators; each Claude session runs in an embedded terminal inside
the app — a real [Ghostty](https://ghostty.org) terminal surface on macOS, or
JediTermFX + ConPTY on Windows, with no external multiplexer (`tmux`, `screen`)
involved.

The goal is a single window where you can see every repo you work in, start or
resume a `claude` session per repo in its own tab, and keep an eye on session
activity, Git status, and your changes without leaving the app.

## Features

- **Repository sidebar** — add/remove local Git repositories; live branch and
  dirty indicators; open a repo in Finder or your external editor.
- **Embedded terminal sessions** — run the real `claude` CLI in embedded Ghostty
  terminal tabs, one per session, with session activity surfaced in the UI.
- **Git & GitHub awareness** — repository status, review, and search surfaces
  built around the repos you register.
- **MCP tools for your sessions** — sessions Drydock starts can call back into
  the app: read the review comments you left on a diff and reply to them,
  create worktrees and open sessions in them (bounded, and a session an agent
  started cannot spawn further ones), and list your registered repositories
  and running sessions. Local sessions only; remote SSH sessions do not get
  these tools.
- **Persistent state** — registered repositories and window layout persist
  across restarts.
- **Remote repositories over SSH** — register a repo on a remote host via
  ambient SSH auth; sessions run `claude` on the host, the sidebar shows live
  indicators. Remote sessions show a neutral 'session ended — resume to
  reconnect' state on exit; worktrees, diffs, Explorer/Review, and activity
  badges are unavailable. Requires `git` and `claude` on the host's
  non-interactive PATH, a POSIX login shell, and an already-accepted host key.

Drydock runs natively on both **Apple Silicon (`arm64`)** and **Intel
(`x86_64`)** macOS, and on **Windows (x86_64)** with the [JediTermFX +
pty4j](https://github.com/techsenger/jeditermfx) terminal backend. The
macOS build embeds the full `libghostty` (Metal renderer + terminal
surface) and is the primary supported target; the Windows build uses
JediTermFX + ConPTY and is functional for everyday work, with the
limitations documented in
[`docs/windows-terminal-spike.md`](docs/windows-terminal-spike.md).
Install/run guide for Windows: [`docs/windows.md`](docs/windows.md).

## Requirements

Run `./scripts/verify-environment.sh` to check most prerequisites automatically.

| Tool | Version | Notes |
|---|---|---|
| JDK | 26 | Gradle **toolchain** used to compile and run the app (`sdk install java 26.0.1-tem`). |
| JDK (for Gradle itself) | ≤ 24 | Gradle 8.11.1 cannot *run* on JDK 26; its launcher needs an older JDK. See below. |
| Gradle | 8.11.1 | Provided by the checked-in wrapper (`./gradlew`); no separate install. |
| zig | **0.15.2 exactly** | Required to build vendored libghostty (`brew install zig@0.15`). Not 0.16.x. |
| Xcode | Full Xcode + Metal Toolchain | Needed to compile Ghostty's Metal shaders. Install the component once with `xcodebuild -downloadComponent MetalToolchain`. |
| git | recent 2.x | |
| `claude` CLI | recent | The session Drydock manages. |

> **Gradle on JDK 26.** Gradle 8.11.1 throws on a JDK 26 JVM, so two JDKs are in
> play: an older one (verified with JDK 23) launches Gradle, and the JDK 26
> *toolchain* compiles and runs the app. If your default `java` is JDK 26, point
> Gradle at an older JDK for its launcher:
> ```bash
> export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
> export PATH="$JAVA_HOME/bin:$PATH"
> ```
> Gradle's toolchain support then auto-detects the installed JDK 26 for the app.

## Run with jbang (no JDK/Gradle needed)

If you have [jbang](https://www.jbang.dev) installed, you can run Drydock without
installing a JDK, Gradle, or the native toolchain:

```bash
jbang drydock@btraceio/drydock
```

Use the **catalog alias** (above), not the bare release-jar URL: the alias
carries the required JDK version and JVM flags, which a plain URL cannot. If
jbang launches on an older JVM — you'll see
`InvalidModuleDescriptorException: Unsupported major.minor version 68.0` while
reading a JavaFX module — jbang could not provision JDK 26. Fix it with
`jbang jdk install 26` (and `jbang version --update` if jbang is old), then
re-run with `jbang --fresh drydock@btraceio/drydock`.

jbang provisions a **Temurin JDK 26** and JavaFX automatically. The app's classes
and the required native libraries (`libghostty`, `libdrydockterminalhost`, for
both Apple Silicon and Intel) ship inside the release jar
(`drydock-<version>.jar` attached to each GitHub Release, resolved through the
catalog alias above); on first launch they are extracted to
`~/Library/Caches/drydock/native/`.

First launch downloads the JDK and dependencies, so it's slower than subsequent
runs. A few warnings on startup are benign and can be ignored: `WARNING: Unknown
module: javafx.graphics` (JavaFX runs from the classpath, so the export flag is
a harmless no-op), a jbang/Gson reflective-final-field warning, and — only when
run without a display — `CVDisplayLink error` lines.

**Limitations vs. the packaged app.** A jbang launch is a plain JVM process, not a
signed/notarized `.app` bundle: no Finder double-click and no bundle identity
(the dock name and Bot-in-dock icon are still set). For the polished,
double-clickable app, build the `.app` with `./gradlew appImage`. The dylib load
relies on Temurin's `disable-library-validation` entitlement; a non-Temurin JDK
may refuse to load them.

## Building and running

Ghostty is vendored as a pinned Git submodule, so initialize submodules first:

```bash
git submodule update --init
```

Then compile, run tests, and launch the app:

```bash
./gradlew clean test run
```

`./gradlew run` opens the Drydock window: a repository sidebar on the left and
the workspace (terminal tabs) on the right. Registered repositories and window
layout persist across restarts under
`~/Library/Application Support/`. Close the window (or `Cmd+Q`) to exit.

To build a self-contained runtime image (JDK 26 + JavaFX 26 + the app + native
libraries) that runs without Gradle or a configured `JAVA_HOME`:

**macOS (host architecture)** — the everyday image, plus the `.app`/`.dmg`
wrappers around it:

```bash
./gradlew runtimeImage        # build/image/bin/drydock
./gradlew appImage            # build/dist/Drydock.app  (wraps runtimeImage)
./gradlew dmg                 # build/dist/Drydock.dmg  (wraps appImage)
```

**macOS (both architectures, cross-linked in one pass)** — downloads the
non-host architecture's jmods and produces an image per arch. Does not execute
the foreign-architecture binary; only its Mach-O tag is verified, real
execution verification is CI's job:

```bash
./gradlew runtimeImageAllArches   # build/image-macos-x86_64 and build/image-macos-arm64
# or a single arch:
./gradlew runtimeImageMacosX8664  # build/image-macos-x86_64
./gradlew runtimeImageMacosArm64  # build/image-macos-arm64
```

**Windows** — a self-contained jlink image with a `drydock.bat` launcher.
Registered only on a non-Mac host (the Windows CI runner). See
[`docs/windows.md`](docs/windows.md) for the Windows-specific scope and caveats:

```cmd
.\gradlew.bat :app:windowsRuntimeImage   # build\image-windows\bin\drydock.bat
```

The runtime image layout and launcher details are documented in
[`docs/runtime-image.md`](docs/runtime-image.md).

## IntelliJ plugin

`intellij-plugin/` holds a Drydock plugin for IntelliJ IDEA: a **Drydock**
tool window on the right edge that lists your projects and their agent tasks,
starts a new task (branch and worktree pre-filled) with `⌃⌥N` on macOS, and switches to
or stops a running one. It shares the project list with the standalone app
(`~/.drydock/projects.json`), so a project registered in one shows up in the
other.

New task gives each new worktree a random three-word branch name, such as
`curious-mossy-otter`, and a `<repo>-<branch>` folder. The generated name stays
fixed while you edit the task; both fields remain editable under **Change…**.

It needs IntelliJ IDEA **2026.3 or newer** (an EAP build until 2026.3 ships)
with the bundled Git and Terminal plugins enabled, and at least one agent CLI
(`claude`, `codex` or `pi`) on your `PATH`.

**Get the plugin zip** — either download the `drydock-intellij-plugin`
artifact from the latest *IntelliJ plugin* workflow run on the repository's
Actions tab (unzip the download once; inside is the plugin zip), or build it
yourself (no submodules or Zig needed; Gradle fetches the IDE it builds
against, and compiles with a JDK 25 it finds installed):

```bash
cd intellij-plugin
./gradlew buildPlugin    # build/distributions/drydock-intellij-0.1.0.zip
```

**Install it** — in IntelliJ IDEA open *Settings → Plugins*, click the ⚙ icon,
choose *Install Plugin from Disk…*, pick `drydock-intellij-0.1.0.zip` (the zip
itself, not an unpacked folder) and restart the IDE when asked. The tool
window then appears on the right; worktree placement is under *Settings →
Tools → Drydock*.

To try it without touching your own IDE, `./gradlew runIde` starts a sandbox
IDE with the plugin installed and a project list of its own under
`build/sandbox-projects/`. Before a release, also run `./gradlew verifyPlugin`
(the Plugin Verifier; it downloads the IDE builds it checks, ~4 GB each, so
check your free disk first).

## Releasing

See [docs/RELEASING.md](docs/RELEASING.md) for how releases are cut: CI builds
the macOS natives, the jbang jar and the Windows runtime image, and attaches
the jar and the Windows image zip to the GitHub Release.

## Contributing

The design, milestone reports, and native-integration findings live in
[`docs/`](docs/):

- [`docs/implementation-plan.md`](docs/implementation-plan.md) — full design and
  scope.
- [`docs/native-integration.md`](docs/native-integration.md) — how libghostty is
  built, its C API, required frameworks, and lifecycle/threading constraints.
- [`docs/runtime-image.md`](docs/runtime-image.md) — the jlink runtime image
  layout and launcher details.
- [`docs/architecture.md`](docs/architecture.md) — architecture and open risks.

Native code lives at the edges: the AppKit host shim in
[`native-host/`](native-host/) (built by `./gradlew buildNativeHost`) and the
FFM bindings under `app/src/main/java/app/drydock/terminal/`. Ghostty itself is
pinned at `third_party/ghostty` (tag v1.3.1) and built by
`scripts/build-ghostty.sh` — CPU-architecture selection stays isolated in the
native boundary package and must not leak into UI, Git, or persistence code.

Build the native pieces directly with:

```bash
./gradlew buildGhosttyNative   # libghostty, both architectures
./gradlew buildNativeHost      # the AppKit host shim, both architectures
```
