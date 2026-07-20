# Gate 0F / Task 8: jlink runtime image

This is the report required by plan section 28 "Task 8": what works, what
does not, whether an AppKit shim was required, undocumented/unstable APIs
in use, native ownership rules, packaging implications, the exact generated
layout, the exact launcher JVM arguments, and whether the architecture
remains viable. Per that section, project-management code is not started
until this report is complete.

## Summary

The first self-contained `jlink` runtime image builds and runs cleanly:
JDK 26 + JavaFX 26 + the application + libghostty + the AppKit host shim,
launched with `--enable-native-access`, **without `JAVA_HOME` set, without
Gradle, and copied outside the source tree** (plan section 22.5). The
architecture remains viable — no new blocking defect was found in Task 8;
one real packaging-time-only bug was found and fixed (see below).

## Commands that work

```bash
source ~/.sdkman/bin/sdkman-init.sh
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem   # Gradle itself; see "JDK version note" below
./gradlew runtimeImage
build/image/bin/claude-project-manager
```

`./gradlew appImage`, `./gradlew macApp`, and `./gradlew dmg` are also
registered (plan section 6.3 requires the aliases to exist) but are
deliberate no-ops that fail with an explicit "not implemented yet, see
plan section 23.4 Stage N" message — see "Packaging implications" below for
why these are correctly out of scope for this phase.

### JDK version note (unchanged from Task 4/7)

Gradle 8.11.1 does not run on JDK 26 itself, so `JAVA_HOME` must point at an
older JDK (23 in the example above) to invoke Gradle; the `java` toolchain
block in `app/build.gradle.kts` still compiles/runs/jlinks the application
itself with JDK 26 via Gradle's toolchain resolution. The generated
`runtimeImage` task explicitly resolves `jlink` from the **JDK 26 toolchain
launcher**, not from `JAVA_HOME`/`$(which java)`, precisely so this
Gradle/toolchain split never leaks into the produced image.

## Exact generated runtime-image layout

```text
build/image/
├── bin/
│   └── claude-project-manager        # generated launcher (bash)
├── runtime/                          # jlink output (JDK 26 + JavaFX 26 modules)
│   ├── bin/
│   │   └── java
│   ├── conf/
│   ├── legal/
│   └── lib/
│       └── ... (java.desktop natives, libjli.dylib, etc. -- all jlink's own)
├── app/
│   ├── app.jar                       # this project's compiled classes/resources
│   ├── javafx-base-26-mac.jar
│   ├── javafx-controls-26-mac.jar
│   └── javafx-graphics-26-mac.jar
└── lib/
    ├── macos-x86_64/
    │   ├── libghostty.dylib
    │   └── libcpmterminalhost.dylib
    └── macos-arm64/
        ├── libghostty.dylib
        └── libcpmterminalhost.dylib
```

Deviation from the plan section 23.1 example layout: `lib/` has
`macos-x86_64/`/`macos-arm64/` subdirectories rather than the two `.dylib`
files directly. This is the approved dual-architecture deviation (see
`README.md`) reaching packaging: both architectures' `libghostty.dylib`/
`libcpmterminalhost.dylib` ship in the same image, and
`GhosttyNativeLibrary`/`CpmTerminalHostLibrary` (the narrow native
boundary, plan section 2.4/4.2) pick the right subdirectory at launch via
the same single `os.arch`-based `detectArchDirectoryName()` method already
used for the `build/native/<arch>/` developer-build layout — no new
arch-branching code was needed anywhere else in the codebase. The image is
built and *verified* on this Intel Mac (arm64 binaries inside it are
present and correctly `arm64`-tagged per `file(1)`, but never actually
executed on this machine — see "What was verified vs. not" below).

`build/image` (not `app/build/image`) is deliberate: the task explicitly
targets the *root* build directory so the plan's literal acceptance
command (`build/image/bin/claude-project-manager`, run from the repo root)
matches exactly, the same way `build/native` (declared in the root build
file for `buildGhosttyNative`/`buildNativeHost`) already does, even though
the `runtimeImage` task itself is defined in `app/build.gradle.kts` for
access to the app's own `Jar`/toolchain/`runtimeClasspath` objects.

Image size on disk: ~105 MB (dominated by the jlinked `runtime/`; both
architectures of libghostty add a few MB each).

## Exact launcher JVM arguments

Generated verbatim into `build/image/bin/claude-project-manager`:

```bash
exec "$APP_HOME/runtime/bin/java" \
  --enable-native-access=ALL-UNNAMED \
  --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -Djava.awt.headless=false \
  -Dapp.cpm.ghostty.nativeDir="$APP_HOME/lib" \
  -Dapp.cpm.terminalhost.nativeDir="$APP_HOME/lib" \
  ${CPM_EXTRA_JVM_ARGS:-} \
  -cp "$APP_HOME/app/*" \
  "$MAIN_CLASS" "$@"
```

`$APP_HOME` is resolved from the launcher script's own (symlink-resolved)
location, never the current working directory or `JAVA_HOME` — verified by
launching from `/tmp` with `JAVA_HOME` unset and a `PATH` scrubbed down to
just `/usr/bin:/bin`.

`MAIN_CLASS` defaults to `app.cpm.Main` (the real application); `CPM_MAIN_CLASS`/
`CPM_EXTRA_JVM_ARGS` environment variables are internal escape hatches used
only by this project's own smoke testing, not part of the plan's launcher
spec. (See "Why the terminal spike, not `app.cpm.Main`, is the default launch target"
below for historical context on why the spike was chosen at Milestone 0, and how
the default changed at Milestone 5.)

### `--add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED`: a real, image-only defect found and fixed in Task 8

This flag is **not present** in the `gate0cSpike`/`gate0dSpike`/`gate0eSpike`
Gradle dev tasks, and deliberately so — a comment on `gate0cSpike` in
`app/build.gradle.kts` (written in Task 5) explicitly says adding
`--add-exports javafx.graphics/...` there fails fast with "Unknown module",
because in that dev configuration (`JavaExec` classpath, not module path)
`javafx-graphics-26-mac.jar` is loaded as part of the *unnamed* module, so
there is no `javafx.graphics` module for the JVM to open anything from, and
no module-boundary enforcement blocks `JavaFxNativeView`'s reflective use
of `com.sun.glass.ui.Window`/`View` in the first place.

The jlink image is a genuinely different runtime shape: `jlink` links
`javafx.graphics` in as a real, named module (resolved via `--module-path`
against the JavaFX module jars), while the application's own `app.jar` is
still loaded from `-cp` (unnamed module) since it is not yet modularized.
The first end-to-end run of `build/image/bin/claude-project-manager`
reproduced exactly this:

```
[gate0c] FAILED to initialize terminal: java.lang.IllegalAccessError: class
app.cpm.terminal.host.JavaFxNativeView (in unnamed module @0x...) cannot
access class com.sun.glass.ui.Window (in module javafx.graphics) because
module javafx.graphics does not export com.sun.glass.ui to unnamed module
```

i.e. the exact reflective AppKit-window-handle access that Gate 0C's whole
native-view strategy depends on (see `JavaFxNativeView`'s own Javadoc)
silently works in every dev spike so far only because those spikes never
actually exercised JPMS enforcement — the classpath-mode dev harness masked
a real module-boundary problem that only a genuine jlink image surfaces.
Adding `--add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED` to the
*launcher* (where `javafx.graphics` really is a named module) fixes it
immediately; verified end-to-end (see "What was verified" below). **This is
new evidence for plan rule 27.8's warning about undocumented/internal API
use** — not just "unstable across JavaFX versions" but "silently
untested-for by any dev-mode task," and worth calling out for whoever
eventually modularizes the application (plan section 6.4): at that point
this flag should become a `module-info.java` `opens .. to javafx.graphics`
requirement check rather than a launcher flag, and the dev spike tasks
should be updated to reproduce the same module topology so this class of
bug cannot hide again.

## Undocumented/unstable APIs in use (packaging-relevant, see also `docs/native-integration.md`/`docs/architecture.md`)

- `com.sun.glass.ui.Window`/`com.sun.glass.ui.View` (JDK/JavaFX internal,
  `com.sun.*`), reflectively accessed by `JavaFxNativeView` — now confirmed
  (see above) to require an explicit module-system carve-out once JavaFX is
  loaded as a real module, which the jlink image does and dev-mode
  classpath execution does not. No new internal API was added in Task 8
  itself; this section documents that Task 8 changed *how* the existing one
  is exposed to JPMS enforcement, not that a new one was introduced.

## Native ownership rules

Unchanged from Tasks 4/5 (`docs/native-integration.md`): libghostty is
process-wide-singleton-initialized exactly once
(`GhosttyNativeLibrary`/`ghostty_init`), loaded into `Arena.global()` and
never unloaded; the AppKit host shim owns its `NSView`/window plumbing
under the same lifetime rules. Task 8 changes only *where* the backing
`.dylib` files are found at runtime (bundled `<image>/lib/<arch>/` instead
of a developer's `build/native/<arch>/`), not any ownership/threading rule
— see the `NATIVE_DIR_PROPERTY` Javadoc changes below.

A small, backward-compatible change was needed in `GhosttyNativeLibrary`
and `CpmTerminalHostLibrary` (the narrow native boundary, plan
section 2.4/4.2) to make this work for a dual-arch bundle: previously,
`-D<...>.nativeDir=<dir>` (an override hook that had no caller yet) pointed
*directly* at a directory containing the `.dylib`. It now points at a
**root** directory containing one `macos-x86_64`/`macos-arm64`
subdirectory each (the same shape `build/native/` already has for
developer builds) — the same single `os.arch`-based
`detectArchDirectoryName()` method that already selected the
developer-build subdirectory now also selects the packaged one. No new
arch-branching code exists anywhere; this was a one-line semantic
adjustment to an override property with no prior callers (verified via
`grep` before changing it), not a new decision point.

## Packaging implications (plan section 23.3/23.4, out of scope for Task 8)

Stage 2 (raw jlink image, this task) is done. Stages 3-6 —
`.app` bundling (`Info.plist`, `Contents/MacOS`, `Contents/Frameworks`),
`.dmg` production, ad hoc signing, and Developer ID signing/notarization —
are explicitly **not** implemented, per plan section 3 ("Initial Scope")
and section 28 Task 8's own "do not implement project management until
this report is complete." `./gradlew appImage`/`macApp`/`dmg` exist (plan
section 6.3 requires the aliases) but fail immediately with a message
naming the plan section/stage they correspond to, rather than doing
nothing silently or half-implementing signing without being asked.

One thing worth flagging now for whoever picks up Stage 3: the nested
`.dylib`s will need per-architecture code signing *before* the outer `.app`
is signed (plan section 23.4, "All nested native libraries and executables
must be signed in the correct order"), and `@rpath`/`@loader_path`-relative
library IDs (not `DYLD_LIBRARY_PATH`) will be needed for the `.app` layout
— this project's current native loading (`SymbolLookup.libraryLookup` with
an absolute path resolved in Java) does not depend on either of those and
will keep working unmodified either way, since it never consults the
dylib's own install name or `DYLD_LIBRARY_PATH`.

## Why the terminal spike, not `app.cpm.Main`, is the default launch target

At this point in the plan (Milestone 0 done; Milestones 1-2 in progress),
`app.cpm.Main`/`CpmApplication` is still a literal empty JavaFX window (see
that class's own Javadoc) — launching it by default would satisfy none of
plan section 7 "Gate 0F"'s or section 28 "Task 8"'s actual acceptance
criteria ("must launch **the terminal spike**"). The generated launcher
therefore defaults `MAIN_CLASS` to `app.cpm.terminal.Gate0cSpikeLauncher`
(Task 5's Gate 0C spike — the most advanced terminal-rendering code that
exists), with `CPM_MAIN_CLASS` as an env-var override used to run other
spikes (`Gate0dSpikeLauncher`, `Gate0eSpikeLauncher`,
`app.cpm.terminal.ghostty.GhosttySmokeTest`) through the same image without
a rebuild. This default is expected to change to `app.cpm.Main` once the
real application embeds a terminal (Milestone 2 onward) — tracked here so
it is not forgotten, not because it is meant to be permanent.

## What was verified

Run from a copy at `/tmp/cpm-image-test` (outside the source/build tree),
with `JAVA_HOME` unset and `PATH` reduced to `/usr/bin:/bin` (no `java`, no
Gradle, no sdkman on `PATH` at all):

- `CPM_MAIN_CLASS=app.cpm.terminal.ghostty.GhosttySmokeTest
  build/image/bin/claude-project-manager` (Gate 0B smoke test) — passes:
  `ghostty_init`/`ghostty_info`/`ghostty_config_new`/`_free` all succeed,
  `os.arch: x86_64` confirms the right architecture subdirectory was
  selected.
- `/tmp/cpm-image-test/bin/claude-project-manager` (defaults to
  `Gate0cSpikeLauncher`) — after the `--add-exports` fix above, runs the
  full Gate 0C sequence successfully end to end: `ghostty_init`, AppKit host
  view created and attached to the JavaFX window's `NSView`,
  `ghostty_app_new`, `ghostty_surface_new` (`scale=2.0`, i.e. real
  HiDPI/Retina scale factor read from the live window, not a hardcoded
  value), resize (900x600 logical -> 1800x1200 px), focus, and an initial
  `ghostty_app_tick`/`ghostty_surface_draw` — closed cleanly with `kill`
  (SIGTERM) and left no zombie process behind (checked via `pgrep` after).
- `file(1)` confirms `runtime/bin/java` and `lib/macos-x86_64/*.dylib` are
  genuine single-architecture `x86_64` Mach-O binaries, and
  `lib/macos-arm64/*.dylib` are genuine single-architecture `arm64`
  Mach-O binaries — i.e. the bundled arm64 copy is real, correctly-tagged
  output from `buildGhosttyNative`/`buildNativeHost` (Task 3/5), not a
  placeholder.

### What was verified vs. not (the honest gap)

This machine is Intel-only (`i7-9750H`), so the bundled `macos-arm64`
`.dylib`s and `runtime/bin/java` (an x86_64 binary — jlink always produces
a runtime for the architecture it runs *on*, it does not cross-build) can
only be confirmed present, correctly named, and correctly `file(1)`-tagged
here — they were never actually *executed*. Two real limitations follow
from this, both inherited from the plan's original Apple-Silicon-only
scope rather than newly introduced by Task 8:

1. **`runtime/bin/java` itself is x86_64-only.** A literal copy of this
   exact image cannot run natively on Apple Silicon; only the bundled
   libghostty/host-shim `.dylib`s are truly dual-arch. Producing a
   dual-arch **runtime** (not just dual-arch native libraries under it)
   would need either two separate jlink invocations (one per
   `--module-path` JavaFX classifier, i.e. `mac`/`mac-aarch64`) producing
   two separate images, or a universal/fat launcher that picks between two
   bundled `runtime/` trees at startup. Neither was needed for the Task 8
   acceptance criteria (which only require the image to work on *this*
   machine), so this is left as explicit future work rather than
   guessed-at now, per plan rule 27.2 ("Do not implement speculative
   abstractions").
2. Because of (1), the dual-arch **native-library selection logic** added
   in Task 8 (`lib/<arch>/`, `NATIVE_DIR_PROPERTY` semantics) is verified
   correct by inspection and by successfully exercising the x86_64 branch
   end-to-end, but the arm64 branch's actual runtime behavior (does
   `ghostty_init`/AppKit view creation/etc. genuinely work when loaded by a
   real arm64 JVM) remains unverified on real Apple Silicon hardware,
   exactly as already flagged for Tasks 3-5 in `docs/native-integration.md`.

## Is the architecture still viable?

Yes. No new blocking defect was found. The one real defect found in Task 8
(`--add-exports` needed once JavaFX is a real linked module) is fixed and
verified, and is a one-line launcher argument, not a redesign. The
remaining open items (Task 7's "closing a surface with a live child kills
the JVM" bug, arm64 hardware verification) were already known before Task 8
and are unaffected by packaging; they block *later* work (real session
lifecycle code, an Apple Silicon verification pass) but not the runtime
image itself.
