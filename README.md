# Drydock

Drydock is a macOS desktop application for managing local Git repositories and
the [`claude`](https://docs.claude.com/en/docs/claude-code) CLI sessions you run
against them. Each repository gets a sidebar entry with live branch and
dirty-state indicators; each Claude session runs in an embedded terminal inside
the app — a real [Ghostty](https://ghostty.org) terminal surface, not a
reimplementation and with no external multiplexer (`tmux`, `screen`) involved.

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
- **Persistent state** — registered repositories and window layout persist
  across restarts.
- **Remote repositories over SSH** — register a repo on a remote host via
  ambient SSH auth; sessions run `claude` on the host, the sidebar shows live
  indicators. Remote sessions show a neutral 'session ended — resume to
  reconnect' state on exit; worktrees, diffs, Explorer/Review, and activity
  badges are unavailable. Requires `git` and `claude` on the host's
  non-interactive PATH, a POSIX login shell, and an already-accepted host key.

Drydock is macOS-only and runs natively on both **Apple Silicon (`arm64`)** and
**Intel (`x86_64`)**.

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
libraries, for both macOS architectures) that runs without Gradle or a
configured `JAVA_HOME`:

```bash
./gradlew runtimeImage
build/image/bin/drydock
```

`.app`/`.dmg` packaging is not implemented yet; build from source or use the
runtime image.

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
