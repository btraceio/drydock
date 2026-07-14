# Claude Project Manager

A JavaFX desktop application for managing local Git repositories and the
`claude` CLI sessions running against them, using an embedded terminal
(no `tmux`, no external multiplexer). See `docs/implementation-plan.md`
for the full design and scope.

This repository is currently at **Milestone 1 (libghostty feasibility, in
progress)**: a single-module Gradle project with JDK 26 + JavaFX 26, one
empty window, unit-test infrastructure, and a working Gradle task that
builds libghostty for both macOS architectures. Repository management, Git
inspection, and packaging are not implemented yet.

Ghostty is vendored as a pinned git submodule at `third_party/ghostty`
(tag v1.3.1, see `.gitmodules`; clone with `git submodule update --init`).
See `docs/native-integration.md` for the full findings on how to build
libghostty from that checkout, its C API, required frameworks, and
lifecycle/threading constraints.

### Building libghostty (Task 3)

```bash
./gradlew buildGhosttyNative
```

runs `scripts/build-ghostty.sh`, which builds libghostty for **both**
`macos-x86_64` and `macos-arm64` in one invocation (using Ghostty's
`-Dxcframework-target=universal`, the only mode that can produce an
`aarch64-macos` slice when building on an Intel host — `native` mode
silently ignores `-Dtarget` and always builds the host's own architecture;
see `docs/native-integration.md` for the empirical finding), then splits the
resulting universal static library with `lipo -thin` into one archive per
architecture. Deliverables:

```text
build/native/macos-x86_64/libghostty.a
build/native/macos-arm64/libghostty.a
build/native/include/ghostty.h            (+ include/ghostty/*, module.modulemap)
build/generated/ghostty-version.properties
```

Both architectures are built and verified on this (Intel) development
machine — `lipo -info` was used to confirm each split archive actually
contains only its own architecture's object code, not merely that the
build reported success.

Rebuilding without source changes is a Gradle `UP-TO-DATE` no-op (inputs
are the script and the submodule's tracked sources, excluding its own
`.zig-cache`/`zig-out`/`macos/GhosttyKit.xcframework` build outputs); even
a forced re-run is fast (a few seconds) because Zig's own build cache
inside `third_party/ghostty/.zig-cache` avoids recompiling unchanged
sources. A clean checkout with missing prerequisites fails immediately with
a message naming exactly what's missing (wrong/missing Zig 0.15.x, missing
Xcode, or an uninitialized submodule) rather than an opaque build error.

**Known artifact limitation, not yet resolved:** Ghostty's `zig build` never
installs a loose `.dylib` on macOS (only a static `.a`, see
`docs/native-integration.md`). FFM's `SymbolLookup` needs a dynamically
loadable image, so Task 4 (FFM smoke test) will need a small shim `.dylib`
that statically links against these `.a` files and re-exports the
`ghostty_*` symbols — this is a real, confirmed gap in Ghostty's own build,
not something skipped here by mistake.

## Supported platforms

Version 0.1 targets **macOS only**, on **both**:

- Apple Silicon (`arm64`)
- Intel (`x86_64`)

> **Deviation from `docs/implementation-plan.md`:** section 3.2 lists
> "Intel macOS support" and "universal binaries" as deferred/out of scope,
> targeting Apple Silicon only. This has been explicitly changed for this
> build: dual-architecture macOS support (arm64 **and** x86_64) is
> required from v0.1, since the development machine is Intel-based.
> libghostty (and any native host shim) must be built and bundled for
> both architectures, with runtime CPU-architecture detection
> (`System.getProperty("os.arch")`) selecting the matching native
> library. That detection logic must stay isolated inside the narrow
> native boundary package the plan mandates in section 4.2/6.4
> (`app.cpm.terminal.ghostty` once that module exists) — it must not leak
> into UI, repository, Git, or persistence code. No other section-3.2/3.3
> deferred item (worktrees, staging/commits, GitHub API, cloning, remote
> SSH, Windows/Linux, universal binaries, auto-update, notarized
> distribution, etc.) is being pulled forward; this deviation is scoped
> strictly to "which macOS CPU architectures are supported".

## Development prerequisites

Run `./scripts/verify-environment.sh` to check most of these automatically
(JDK 26, `git`, `claude` CLI, wrapper presence, current CPU architecture,
zig 0.15.x, and Xcode command line tools).

Note: `scripts/verify-environment.sh` cannot detect the Xcode **Metal
Toolchain** component specifically (`xcodebuild -downloadComponent
MetalToolchain`), which `zig build`'s `metal` shader compilation step also
requires. If `./gradlew buildGhosttyNative` fails with `cannot execute tool
'metal' due to missing Metal Toolchain`, run that download command once.

| Tool | Required version | Notes |
|---|---|---|
| JDK | 26 (Temurin 26.0.1 used in dev) | Used as the Gradle **toolchain** to compile and run the app. Install via `sdk install java 26.0.1-tem` (sdkman). |
| Gradle | 8.11.1 | Provided via the checked-in wrapper (`./gradlew`); no separate install needed to build this project. |
| zig | **0.15.2 exactly** (not 0.16.0) | Ghostty (pinned at `third_party/ghostty`, tag v1.3.1) requires exactly Zig 0.15.x (`build.zig.zon`'s `minimum_zig_version = "0.15.2"`, enforced by an exact major.minor check in `src/build/zig.zig`). The Homebrew default `zig` formula currently installs 0.16.0, which fails both that check and fails to compile Ghostty's build script (`std.process.EnvMap` was removed in 0.16). Install the versioned formula alongside the default one: `brew install zig@0.15` (keg-only, installs to `/usr/local/opt/zig@0.15/bin/zig`, does not relink the default `zig`). Any Gradle task invoking `zig build` for libghostty must use this path explicitly — see `docs/native-integration.md`. |
| Xcode | 26.5, **full Xcode, not just the standalone CLT**, plus the Metal Toolchain component | `zig build`'s macOS xcframework step shells out to `xcodebuild -create-xcframework` and to `xcrun -sdk macosx metal` (to compile Ghostty's Metal shaders); the latter requires the Metal Toolchain component, which is not installed by default even with `xcode-select -p` pointing at a full Xcode install. If missing, download it once with `xcodebuild -downloadComponent MetalToolchain` (confirmed working without `sudo` on this machine; ~690 MB download). If `xcodebuild` itself reports plugin-loading errors first, run `xcodebuild -runFirstLaunch` (also works without `sudo` here). |
| git | 2.49.0 (any recent 2.x) | Invoked directly as a subprocess; never through a shell string (plan sections 6.7, 21). |
| `claude` CLI | any recent version supporting `--version`/`--help` | Capability-detected at startup once Claude integration is implemented (plan section 6.8); not yet wired up in this milestone. |

### Important Gradle/JDK 26 interaction

Gradle **8.11.1 itself cannot run on a JDK 26 JVM** — its own version
parsing throws `java.lang.IllegalArgumentException: 26.0.1` when the
Gradle daemon is launched under JDK 26. This is a Gradle-side limitation,
not a project bug, and there is nothing in `build.gradle.kts` that can
paper over it.

Two independent JDKs are therefore in play:

- the JDK that launches **Gradle itself** (must be JDK ≤ ~24 today,
  verified working with JDK 23), and
- the JDK 26 **toolchain** that Gradle uses to actually **compile and run
  the application** (configured explicitly in `app/build.gradle.kts` via
  `java.toolchain.languageVersion = 26`, auto-detected from
  `~/.sdkman/candidates/java` — no manual `JAVA_HOME` juggling required
  once a JDK 26 is installed via sdkman).

Practically, if your default `JAVA_HOME`/`PATH` `java` is JDK 26 (e.g.
after `sdk use java 26.0.1-tem`), point Gradle at an older JDK for its own
launcher, for example:

```bash
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean test run
```

Gradle's toolchain support then auto-detects the installed JDK 26 and uses
it to compile and run `app.cpm.Main`. Compiled class files were confirmed
to be Java 26 bytecode (`major version: 70`) even while the Gradle daemon
itself runs on JDK 23.

## Building and running

```bash
# One-time: generate/refresh the wrapper (already checked in)
gradle wrapper --gradle-version 8.11.1

# Verify local prerequisites
./scripts/verify-environment.sh

# Compile, run unit tests, and launch the (currently empty) JavaFX window
./gradlew clean test run
```

`./gradlew run` opens a single blank JavaFX window titled
"Claude Project Manager". Close the window (or `Cmd+Q`) to exit; the
process exits cleanly.

For CI or headless environments, pass `-PheadlessTest` to run with
software rendering / Monocle instead of a real display:

```bash
./gradlew run -PheadlessTest
```

## Repository layout (current)

```text
.
├── app/                      # the (currently) single Gradle module
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/app/cpm/       # application code
│       ├── main/resources/          # app.css, icons/, syntax/ (empty for now)
│       └── test/java/app/cpm/       # JUnit 5 tests
├── docs/
│   └── implementation-plan.md
├── scripts/
│   └── verify-environment.sh
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── gradle/wrapper/
```

This will grow into the multi-module layout described in
`docs/implementation-plan.md` section 5 (`terminal-api`,
`terminal-ghostty`, `native-host`, `packaging`, etc.) once the terminal
prototype (Tasks 2-8) is working; per plan section 27 rule 2, later
milestones are intentionally not scaffolded yet.
