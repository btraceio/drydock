# Claude Project Manager

A JavaFX desktop application for managing local Git repositories and the
`claude` CLI sessions running against them, using an embedded terminal
(no `tmux`, no external multiplexer). See `docs/implementation-plan.md`
for the full design and scope.

This repository is currently at **Milestone 4 (repository manager) done**,
on top of Phase 0 (feasibility gates 0A-0F, all passed) and Milestone 3
(jlink runtime image): a single-module Gradle project with JDK 26 +
JavaFX 26, libghostty embedded in a JavaFX window via an AppKit host
shim, the real `claude` CLI running in that embedded terminal, a
self-contained `jlink` runtime image
(`./gradlew runtimeImage` -> `build/image/bin/claude-project-manager`) that
runs without `JAVA_HOME`, without Gradle, and copied outside the source
tree, and now the real application entry point (`app.cpm.Main`): a
repository sidebar (add/remove a Git repository, branch and dirty
indicators, open in Finder/external editor) backed by a JSON-file
application state that persists across restarts (plan section 25). Managed
Claude sessions / terminal tabs (Milestone 5) and `.app`/`.dmg` packaging
are not implemented yet -- see `docs/implementation-plan.md` section 28
"Task 8": project-management code does not start until that report
(`docs/runtime-image.md`) is read.

Ghostty is vendored as a pinned git submodule at `third_party/ghostty`
(tag v1.3.1, see `.gitmodules`; clone with `git submodule update --init`).
See `docs/native-integration.md` for the full findings on how to build
libghostty from that checkout, its C API, required frameworks, and
lifecycle/threading constraints.

### Building libghostty (Task 3, revised in Task 4)

```bash
./gradlew buildGhosttyNative
```

runs `scripts/build-ghostty.sh`, which builds libghostty for **both**
`macos-x86_64` and `macos-arm64` in one invocation and produces, per
architecture:

```text
build/native/macos-x86_64/libghostty.dylib
build/native/macos-x86_64/libghostty.a
build/native/macos-arm64/libghostty.dylib
build/native/macos-arm64/libghostty.a
build/native/include/ghostty.h            (+ include/ghostty/*, module.modulemap)
build/generated/ghostty-version.properties
```

Both architectures are built and verified on this (Intel) development
machine: `file`/`nm -g` confirm each `.dylib` is single-architecture and
exports the full public API (`ghostty_init`, `ghostty_config_*`, etc.), not
merely that the build reported success.

**Task 3 originally** built the static xcframework
(`-Dxcframework-target=universal`) and `lipo -thin`'d it per architecture.
**Task 4 replaced that** after discovering two real defects while trying to
get a `.dylib` (FFM's `SymbolLookup` needs a dlopen-able image; a `.a`
cannot be dlopen'd) out of that static archive:

1. Apple's `libtool -static` (invoked internally by Ghostty's own
   `GhosttyLib.zig` to merge the compiled module with its C dependencies)
   silently **drops** several archive members it warns are "not 8-byte
   aligned" — including the one object containing the *entire public C
   API*. The Task 3 archive linked but exported none of `ghostty_init`,
   `ghostty_config_*`, etc. Reproduced identically across repeated clean
   rebuilds — a real toolchain/upstream defect, not a fluke.
2. On Darwin, `zig build` never installs a loose `.dylib` at all — Ghostty's
   own `build.zig` comment says so explicitly ("We shouldn't have this
   guard but we don't currently build on macOS this way ironically so we
   need to fix that").

Rather than hand-reconstruct Ghostty's entire dependency link line
(glslang, spirv-cross, sentry, simdutf, libintl, freetype, harfbuzz, ...),
`scripts/build-ghostty.sh` now applies a small, reviewed patch —
`third_party/patches/ghostty-install-macos-shared-lib.patch` — to the
checked-out submodule before building (idempotent: skipped if already
applied). It:

- removes the Darwin guard in `build.zig` so the *shared* library target
  (already correctly linked by Zig's own linker, not Apple's buggy
  `libtool` merge) gets installed as `libghostty.dylib`;
- adds `linkFramework("Metal")` / `linkFramework("AppKit")` in
  `src/build/SharedDeps.zig`, which nothing in this checkout does
  explicitly (confirmed necessary empirically: omitting them fails with
  `undefined symbol: _MTLCopyAllDevices` and several
  `_OBJC_CLASS_$_MTL*` errors).

The script also builds each architecture with a plain per-arch
`zig build -Dtarget=<arch>-macos` (not the xcframework/lipo path), which
sidesteps the `libtool` merge bug entirely, and hard-fails the whole build
if `nm -g` doesn't find `ghostty_init` exported — a real acceptance gate,
not just "the linker didn't error".

Rebuilding without source changes is a Gradle `UP-TO-DATE` no-op; even a
forced re-run is fast (well under 10s for both architectures) because
Zig's own build cache inside `third_party/ghostty/.zig-cache` avoids
recompiling unchanged sources. A clean checkout with missing prerequisites
fails immediately with a message naming exactly what's missing (wrong/
missing Zig 0.15.x, missing Xcode, an uninitialized submodule, or a patch
that no longer applies cleanly) rather than an opaque build error.

### FFM smoke test (Task 4, Gate 0B)

```bash
./gradlew ffmSmokeTest
```

loads the architecture-matching `libghostty.dylib` via the Java Foreign
Function & Memory API, calls `ghostty_init`, `ghostty_info` (validating the
returned version string), and `ghostty_config_new`/`ghostty_config_free`,
then exits cleanly. All FFM/native-pointer code for this lives in
`app/src/main/java/app/cpm/terminal/ghostty/` — the narrow native boundary
package mandated by the plan (section 2.4/4.2) — and is also where the
dual-architecture `os.arch` selection logic lives
(`GhosttyNativeLibrary.detectArchDirectoryName()`), per the deviation
below.

### Terminal surface embedding spike (Task 5, Gate 0C)

```bash
./gradlew gate0cSpike                              # scripted, auto-exits
./gradlew gate0cSpike -Papp.cpm.gate0c.interactive # leaves the window open
```

Opens the smallest possible JavaFX window that embeds one live Ghostty
terminal surface via a small AppKit host shim
(`native-host/CpmTerminalHost.{h,m}`, built by `./gradlew buildNativeHost`)
-- see `docs/native-integration.md` ("Task 5 / Gate 0C") for the full
investigation, including why JavaFX has no public API for this and what
was and wasn't possible to verify without a human watching the window.

### Interactive shell checklist spike (Task 6, Gate 0D)

```bash
./gradlew gate0dSpike                              # scripted, auto-exits, 12/12 checks pass
./gradlew gate0dSpike -Papp.cpm.gate0d.interactive # leaves a live /bin/zsh -l session open
```

Spawns `/bin/zsh -l` inside the Gate 0C surface and drives the plan's
manual terminal checklist (section 22.4) headlessly by reading back
rendered screen text (`GhosttySurface#readScreenText`) after sending real
input. See `docs/manual-terminal-checklist.md` (full checklist results)
and `docs/native-integration.md` ("Task 6 / Gate 0D") for two real bugs
found and fixed along the way (native vs. `GHOSTTY_KEY_*` keycodes; paste
vs. typed-key semantics).

### `claude` CLI spike (Task 7, Gate 0E)

```bash
./gradlew gate0eSpike -Papp.cpm.gate0e.repo=<throwaway git repo, NOT this project>
```

Runs the real installed `claude` CLI inside the embedded terminal, in a
disposable throwaway repository. Unlike Gate 0D, this does not assert hard
pass/fail (the CLI's behavior/timing/model output are not this project's
to control) -- it logs a full scripted transcript for manual review. See
`docs/claude-integration.md` for the complete per-checklist-item findings,
including several real incompatibilities (most importantly: closing a
terminal surface while `claude` is still running currently kills the whole
JVM, which is why this task's Gradle invocation currently exits non-zero
-- see that document for why this is being surfaced rather than hidden).

### jlink runtime image (Task 8, Gate 0F)

```bash
./gradlew runtimeImage
build/image/bin/claude-project-manager
```

Builds a self-contained runtime image at `build/image/` (JDK 26 + JavaFX 26
+ this application + libghostty + the AppKit host shim for **both** macOS
architectures) and, by default, launches the Task 5 terminal spike
(`Gate0cSpike`) through it rather than the real application
(`app.cpm.Main`) -- this default was set back in Milestone 3, before the
real application (Milestone 4) had a repository sidebar, and has been left
alone since changing the runtime-image default launch target is out of
scope for Milestone 4; see `docs/runtime-image.md` for why, and
`CPM_MAIN_CLASS=app.cpm.Main` to launch the real application through the
jlink image instead of a spike. Verified to run with `JAVA_HOME` unset, no Gradle on `PATH`, and
copied to `/tmp` outside this repository entirely (plan section 22.5).
`./gradlew appImage` / `macApp` / `dmg` are registered (plan section 6.3)
but intentionally fail with a "not implemented yet" message -- `.app`/
`.dmg` packaging (plan section 23.3/23.4 Stages 3-6) is out of scope for
this phase. Full report (exact layout, exact launcher JVM arguments, a
real module-boundary bug found and fixed, what was and was not verified on
Apple Silicon): `docs/runtime-image.md`.

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

# Compile, run unit tests, and launch the application
./gradlew clean test run
```

`./gradlew run` opens the real application window, titled
"Claude Project Manager": a repository sidebar (empty, with an "Add
repository..." button, on first run) on the left and a placeholder main
area on the right (terminal tabs are Milestone 5+ scope, not yet
implemented). Registered repositories, and the sidebar width, persist
across restarts in
`~/Library/Application Support/ClaudeProjectManager/state.json` (plan
section 17); add/remove a repository is saved immediately, the sidebar
width only on clean shutdown (see `RepositoryManager`'s Javadoc for why).
Close the window (or `Cmd+Q`) to exit; the process exits cleanly.

`-PheadlessTest` sets `java.awt.headless`/`testfx.headless`/`prism.order=sw`/
`glass.platform=Monocle` system properties, but **does not currently work**:
this project has no `org.testfx:openjfx-monocle` (or equivalent) dependency
on the classpath, so JavaFX fails to find a Glass platform implementation
and the JVM exits non-zero before `Application.start()` runs. Verifying a
real launch/crash-free startup currently requires an actual on-screen
window until that dependency is added.

## Repository layout (current)

```text
.
├── app/                      # the (currently) single Gradle module
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/app/cpm/                 # application code
│       │   ├── Main.java / CpmApplication.java   # real application entry point (Milestone 4)
│       │   ├── app/            # RepositoryManager, launchers (Finder/external editor)
│       │   ├── domain/         # Repository, ApplicationState, WorkspaceUiState, ...
│       │   ├── state/          # ApplicationStateRepository + JSON codec (plan section 17)
│       │   ├── git/            # GitStatusService (branch/dirty, repo-root resolution)
│       │   ├── ui/             # RepositorySidebar, UiErrors
│       │   └── terminal/
│       │       ├── ghostty/    # narrow native boundary: libghostty FFM bindings
│       │       ├── host/       # narrow native boundary: AppKit host shim FFM bindings
│       │       ├── Gate0cSpike.java            # Gate 0C composition root (Task 5)
│       │       └── Gate0cSpikeLauncher.java
│       ├── main/resources/          # app.css, icons/, syntax/ (empty for now)
│       └── test/java/app/cpm/       # JUnit 5 tests
├── native-host/              # AppKit host shim C sources (plan section 8); no ghostty dependency
│   ├── CpmTerminalHost.h
│   └── CpmTerminalHost.m
├── third_party/
│   ├── ghostty/               # pinned submodule
│   └── patches/                # reviewed patches applied by scripts/build-ghostty.sh
├── docs/
│   ├── implementation-plan.md
│   ├── native-integration.md  # Gate 0A-0C findings, patches, verification methodology
│   └── architecture.md        # unresolved risks (plan rule 27.15)
├── scripts/
│   ├── verify-environment.sh
│   ├── build-ghostty.sh       # builds libghostty for both macOS architectures
│   └── build-native-host.sh   # builds libcpmterminalhost for both macOS architectures
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
