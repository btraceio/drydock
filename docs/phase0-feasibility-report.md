# Phase 0 Feasibility Report

Per plan section 28 ("Stop after Task 8 and report"). This report summarizes
Tasks 1-8 (Milestone 0 / Gates 0A-0F). Full detail and reproduction steps
live in `docs/native-integration.md`, `docs/claude-integration.md`,
`docs/manual-terminal-checklist.md`, `docs/runtime-image.md`, and
`docs/architecture.md` — this document is the synthesis, not a replacement
for those.

## What works

- **Project skeleton**: Gradle (JDK 26 toolchain, JavaFX 26) single-module
  project builds, tests, and launches an empty JavaFX window (Task 1).
- **libghostty build**: pinned Ghostty submodule (`third_party/ghostty` @
  `332b2aefc6e72d363aa93ab6ecfc86eeeeb5ed28`, tag `v1.3.1`) builds into a
  real, dynamically loadable `libghostty.dylib` for both macOS x86_64 and
  arm64 via `./gradlew buildGhosttyNative` (Tasks 2-4, with a patch — see
  "Deviations" — needed to get a working shared library out of Ghostty's
  own build).
- **FFM binding + smoke test**: hand-written Java FFM bindings load the
  correct-architecture dylib and call `ghostty_init`/`ghostty_info`/
  `ghostty_config_new`/`_free` successfully (`./gradlew ffmSmokeTest`,
  Task 4).
- **Full terminal embedding (Gate 0C)**: a custom AppKit host shim
  (`native-host/DrydockTerminalHost.{h,m}`) attaches a real `ghostty_surface_t`
  to a JavaFX window's `NSView`; resize, focus, keyboard input (including a
  real OS-level keystroke via `osascript`/System Events) all reach the
  terminal and are confirmed via logs (Task 5, `./gradlew gate0cSpike`).
- **Interactive shell (Gate 0D)**: `/bin/zsh -l` runs inside the embedded
  surface; 12/12 of the plan's interactive-terminal checklist items pass
  automated verification by reading back rendered cell text — prompt
  rendering, typing/Backspace/arrows, SGR color, Unicode/emoji, resize ->
  `$COLUMNS`/SIGWINCH, real `vim` alternate-screen launch, Ctrl+C
  interrupting a foreground command, Ctrl+D exiting the shell
  (`./gradlew gate0dSpike`, Task 6; checklist in
  `docs/manual-terminal-checklist.md`).
- **Real `claude` CLI inside the embedded terminal (Gate 0E)**: the actual,
  already-authenticated `claude` CLI runs inside the surface against a
  throwaway repo; startup banner, login/config display, multiline prompt
  editing, streaming output, resize-during-output, relaunch, and
  `claude --resume`'s session picker all render correctly
  (`./gradlew gate0eSpike`, Task 7).
- **Self-contained runtime image (Gate 0F)**: `./gradlew runtimeImage`
  produces `build/image/`, a jlink-based image bundling JDK 26 + JavaFX 26 +
  the application + both architectures of libghostty/host-shim, verified to
  run the Gate 0C spike end to end from a copy outside the source tree with
  `JAVA_HOME` unset and no Gradle/sdkman on `PATH` (Task 8).

## What does not work / is not yet implemented

- **Closing a terminal surface while its child process is still alive kills
  the whole JVM** with a non-zero exit and no catchable Java exception —
  reproduced on every Gate 0E run. This is the single most load-bearing open
  defect: it must be fixed (bounded wait-for-exit-then-force-close) before
  any real session/tab-closing UI is built, and needs its own regression
  test per plan rule 14. It plausibly also explains an observed
  `claude --resume` "No conversations found" result (abrupt teardown likely
  pre-empts `claude`'s own on-exit session-persistence write).
- **Ctrl+C did not cancel an in-progress `claude` response** in either Gate
  0E run, using the identical key-delivery mechanism that reliably
  interrupts a plain foreground shell command in Gate 0D. Not yet
  root-caused (ghostty-level delivery vs. `claude`/Ink-level debounce both
  plausible) and not re-verified with a real physical keypress — flagged as
  an open risk against the plan's Gate 0E Ctrl+C requirement, not asserted
  as a confirmed bug.
- **`action_cb` (Ghostty's UI-action callback) is an ABI-correct no-op.**
  `ghostty_action_s` covers new window/tab, OSC 52 clipboard writes,
  fullscreen, quit confirmation, IPC, and more — none are handled. This is
  fine for Gates 0C-0E's narrow scope but must be implemented before real
  clipboard/OSC-52 behavior (used by `claude` and other CLIs) works.
- **Workspace-trust / per-tool permission prompts were never exercised**,
  because this dev machine's own `~/.claude/settings.json` has
  `permissions.defaultMode: "auto"` (pre-existing user config, never
  touched by this project). The app cannot assume this UI will render in
  the embedded terminal by default; needs a from-clean-account manual pass
  before v0.1 ships.
- **Screenshot verification of actual rendered glyphs was not possible** in
  this automated environment — the process lacks macOS Screen Recording
  permission (confirmed via a denied TCC query, not assumed). A human must
  run `./gradlew gate0cSpike -Papp.drydock.gate0c.interactive` locally, with
  that permission granted once, to visually confirm glyph rendering.
- **`.app` bundling, code signing, notarization, `.dmg` production** (plan
  section 23.4 Stages 3-6) are explicitly out of scope for this phase.
  `appImage`/`macApp`/`dmg` Gradle aliases exist (plan section 6.3 requires
  them) but fail immediately with a message naming the plan section/stage
  they belong to, rather than doing nothing or half-implementing signing.
- **Fixed-delay scripted input can lose a keystroke** to `claude`'s TUI if
  sent immediately after a screen transition — not a ghostty defect
  (reproduces identically against a plain shell), but any future
  programmatic input driver must poll `readScreenText()` for a settled
  screen rather than use a fixed delay.

## Whether an AppKit shim was required

**Yes, unavoidably.** Verified empirically (not assumed, per plan rule
27.7): `javap`/`javap -verbose` against the real `javafx-graphics-26-mac.jar`
confirms no public `javafx.*` API exposes a native view/window handle. The
only path to a real `NSView*` is the internal, qualified-export
`com.sun.glass.ui.View#getNativeView()`, which this project does not have
visibility into via its module (classpath-mode JavaFX, not module-path, so
`--add-exports` isn't even applicable in dev-mode task runs — it *is*
needed for the jlink image, see below). The AppKit host shim
(`native-host/DrydockTerminalHost.{h,m}`, plan section 8's 6-function suggested
API plus one documented extension for key-event forwarding) obtains that
handle reflectively via `JavaFxNativeView` (the one class in the codebase
touching `com.sun.glass.ui`) and hands the real `NSView*` to
`ghostty_surface_new`, matching Ghostty's own `nsview`-based embedding model
(confirmed as the only embedding model available at the pinned commit).

## What undocumented or unstable APIs are being used

- **`com.sun.glass.ui.Window` / `com.sun.glass.ui.View`** (JDK/JavaFX
  internal `com.sun.*` package), accessed reflectively by
  `app.drydock.terminal.host.JavaFxNativeView`, the sole point of contact.
  Confirmed to be a qualified export (not visible to arbitrary application
  code without an explicit carve-out). In dev-mode Gradle tasks this works
  silently because JavaFX runs from the classpath (unnamed module, no JPMS
  enforcement triggered). In the jlink runtime image, `javafx.graphics` is
  linked as a real named module and the same code throws
  `IllegalAccessError` until `--add-exports
  javafx.graphics/com.sun.glass.ui=ALL-UNNAMED` is added to the launcher —
  a real defect found and fixed in Task 8, and new evidence for plan rule
  27.8's warning: this class of internal-API risk was not just "unstable
  across JavaFX versions" but "silently untested by any dev-mode task"
  until a genuine jlink image was built. Whoever modularizes the
  application later (plan section 6.4) should convert this into an explicit
  `module-info.java` requirement and update the dev spike tasks to
  reproduce the same module topology so the bug class cannot hide again.
- **Ghostty's public C API (`ghostty_*`)** itself is stable/documented
  (`include/ghostty.h`), but this project's binding is hand-written FFM
  (not jextract-generated, per plan rule 27.17) against one pinned commit;
  several struct layouts (`ghostty_runtime_config_s`,
  `ghostty_surface_config_s`, `ghostty_input_key_s`, `ghostty_action_s`,
  `ghostty_target_s`, `ghostty_text_s`/`ghostty_point_s`/
  `ghostty_selection_s`) were cross-checked against throwaway
  `sizeof()`/`offsetof()`/`_Alignof()` C programs compiled against the
  pinned header rather than hand-derived blind, but remain unverified
  against any other Ghostty version.
- **`ghostty_input_key_s.keycode` is a native platform virtual keycode**
  (looked up via `input.keycodes.entries[].native`), not a `GHOSTTY_KEY_*`
  enum ordinal — a real bug was found and fixed in Task 6 where earlier
  spike code sent the wrong number space, causing every synthesized special
  key to misbehave silently until fixed.
- **Sentry crash-reporting code is compiled into libghostty** (vendored,
  dormant) — never configured, enabled, or exercised by this project;
  flagged for re-review if plan section 21's "no telemetry" constraint is
  ever at risk of being transitively violated.

## Native ownership rules

- `ghostty_init` is called exactly once per process
  (`GhosttyNativeLibrary`), and the loaded library lives in `Arena.global()`
  for the process lifetime — never explicitly unloaded.
- All `ghostty_app_*`/`ghostty_surface_*` calls, plus the periodic
  `ghostty_app_tick`, must happen on one single "main" thread, which this
  project treats as the JavaFX Application Thread (== AppKit's main thread
  in a JavaFX/AppKit-embedded process). This equivalence was exercised
  without incident across Gates 0C-0E but was **never independently proven**
  (e.g. via a `Thread`/`NSThread` identity comparison) — flagged as an
  open risk to close before Gate 0D/0E's higher event volume becomes
  production code.
- Teardown order: `GhosttySurface#close()` before `GhosttyApp#close()`,
  verified crash-free with a single surface. **Untested**: whether
  `ghostty_app_tick` is safe concurrently with/after `ghostty_surface_free`
  when an app has *multiple* surfaces (every gate so far only ever creates
  one).
- The AppKit host shim owns the `NSView`/window plumbing under the same
  process-lifetime rules; it has zero dependency on Ghostty itself
  (separate, small native module in `native-host/`).
- Closing a surface with a live child process currently kills the JVM (see
  "What does not work" above) — the fix (bounded wait-then-force-close) is
  itself a native-ownership/lifecycle rule that still needs to be written
  and tested.
- The narrow native boundary (plan section 2.4/4.2) is enforced by
  packaging: only `app.drydock.terminal.ghostty` and `app.drydock.terminal.host`
  (two sibling packages under `app/src/main/java/app/drydock/terminal/`) may
  reference `MemorySegment`, `MethodHandle`, `Linker`, native pointers, or
  AppKit handles. All arch-branching logic (`os.arch` detection) lives in
  exactly one place per library:
  `GhosttyNativeLibrary`/`DrydockTerminalHostLibrary`'s
  `detectArchDirectoryName()`. `app.drydock.terminal.Gate0cSpike` (and the later
  spikes) only ever call public, pointer-free methods on
  `GhosttyApp`/`GhosttySurface`/`DrydockTerminalHost`.

## Packaging implications

- Ghostty's own build only ever produces a static `.a` on macOS by default;
  FFM needs a `dlopen`-able image, so this project patches Ghostty's build
  (`third_party/patches/ghostty-install-macos-shared-lib.patch`, applied
  idempotently by `scripts/build-ghostty.sh`) to install its own
  already-correct shared-library target as `libghostty.dylib`, and adds
  explicit `linkFramework("Metal")`/`linkFramework("AppKit")` (needed
  empirically; nothing in the vendored build does this itself). This patch
  is a real dependency of the build going forward — if a future Ghostty
  release fixes the underlying `libtool -static` archive-member-dropping
  defect (see below) or the Darwin shared-lib guard, `scripts/build-ghostty.sh`
  will report a failed patch application loudly rather than silently
  skipping it.
- Apple's `libtool -static` (used internally by Ghostty's own default
  static-archive build) silently drops archive members it warns are "not
  8-byte aligned" — including the object holding the entire public C API.
  This is a real Apple toolchain defect (confirmed via `nm`/`ar -t` member
  diffing across repeated clean rebuilds), not a fluke, and is the reason
  the shared-library patch above exists instead of linking the static
  archive directly.
- `./gradlew runtimeImage` produces the first Stage-2 (plan section 23.4)
  self-contained jlink image at `build/image/` (~105 MB), see "Exact
  generated runtime-image layout" below. Stages 3-6 (`.app` bundling,
  `.dmg`, ad hoc signing, Developer ID signing/notarization) are
  unimplemented by design (out of Task 8's / this phase's scope). One
  forward-looking note recorded for whoever picks up Stage 3: nested
  `.dylib`s will need per-architecture code signing before the outer `.app`
  is signed, and `@rpath`/`@loader_path`-relative library IDs will be
  needed for the `.app` layout — the current native loading
  (`SymbolLookup.libraryLookup` with an absolute path resolved in Java)
  does not depend on either and will keep working unmodified through that
  transition.
- Dual-architecture bundling required one intentional, narrow semantic
  change: the previously-uncalled `NATIVE_DIR_PROPERTY` override in
  `GhosttyNativeLibrary`/`DrydockTerminalHostLibrary` now points at a **root**
  directory containing `macos-x86_64`/`macos-arm64` subdirectories, instead
  of directly at an arch directory — no new arch-branching code, same
  single `detectArchDirectoryName()` decision point used everywhere else.

## Exact generated runtime-image layout

```text
build/image/
├── bin/
│   └── drydock        # generated launcher (bash)
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
    │   └── libdrydockterminalhost.dylib
    └── macos-arm64/
        ├── libghostty.dylib
        └── libdrydockterminalhost.dylib
```

Deviation from plan section 23.1's example layout: `lib/` has
`macos-x86_64/`/`macos-arm64/` subdirectories instead of two flat `.dylib`
files, to carry both architectures in one image (approved dual-arch
deviation). `build/image` (root, not `app/build/image`) is deliberate so
the plan's literal acceptance command
(`build/image/bin/drydock`, run from the repo root) matches
exactly.

## Exact launcher JVM arguments

Generated verbatim into `build/image/bin/drydock`:

```bash
exec "$APP_HOME/runtime/bin/java" \
  --enable-native-access=ALL-UNNAMED \
  --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -Djava.awt.headless=false \
  -Dapp.drydock.ghostty.nativeDir="$APP_HOME/lib" \
  -Dapp.drydock.terminalhost.nativeDir="$APP_HOME/lib" \
  ${DRYDOCK_EXTRA_JVM_ARGS:-} \
  -cp "$APP_HOME/app/*" \
  "$MAIN_CLASS" "$@"
```

`$APP_HOME` resolves from the launcher script's own (symlink-resolved)
location, never CWD or `JAVA_HOME` — verified by launching from `/tmp` with
`JAVA_HOME` unset and `PATH` reduced to `/usr/bin:/bin`. `MAIN_CLASS`
defaults to `app.drydock.terminal.Gate0cSpikeLauncher` (the most advanced
terminal-rendering code that exists at this point in the plan;
`app.drydock.Main` is still a literal empty window); `DRYDOCK_MAIN_CLASS`/
`DRYDOCK_EXTRA_JVM_ARGS` are internal escape hatches for this project's own
smoke testing (e.g. running `Gate0dSpikeLauncher`, `Gate0eSpikeLauncher`, or
`GhosttySmokeTest` through the same image without a rebuild), not part of
the plan's launcher spec.

The `--add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED` flag is
**not** present in the dev-mode `gate0cSpike`/`gate0dSpike`/`gate0eSpike`
Gradle tasks — it is neither needed nor even usable there (classpath-mode
JavaFX is the unnamed module, so `--add-exports` targeting
`javafx.graphics` fails fast with "Unknown module"). It is required only in
the jlink image because `jlink` links `javafx.graphics` in as a real named
module while `app.jar` remains on `-cp` (unnamed, not yet modularized) —
see "What undocumented or unstable APIs are being used" above for the full
story and the exact `IllegalAccessError` this fixes.

## Whether the architecture remains viable

**Yes.** Every Gate 0A-0F acceptance criterion in the plan has been met on
this Intel Mac: a real embedded Ghostty terminal running inside a JavaFX
window via a custom AppKit host shim, an interactive shell passing the
full Gate 0D checklist, the real `claude` CLI running and mostly behaving
correctly inside it, and a self-contained jlink runtime image launching all
of the above with no dev toolchain present. No finding in eight tasks
suggests the JavaFX + AppKit-shim + libghostty + FFM architecture is
unworkable; every problem found (libtool archive-member dropping,
JPMS export enforcement in the jlink image, keycode-vs-enum-ordinal
confusion, close-while-child-alive JVM death, unverified Ctrl+C
cancellation) was either fixed outright or is a well-scoped, well-documented
open task rather than an architectural dead end. The one item that would
most change this assessment if it turned out badly is the close-while-child-
alive JVM crash — it must be fixed early in the next milestone, since real
session-management UI cannot be built on top of a lifecycle operation that
currently kills the whole application.

## Dual-arch status

Per the user's approved deviation from plan section 3.2 (Apple Silicon
only) to targeting both macOS x86_64 and macOS arm64.

**Verified on x86_64** (this development machine, an Intel i7-9750H Mac):
- `libghostty.dylib` and `libdrydockterminalhost.dylib` build, link, and export
  the correct symbols (`nm -g` gate in `scripts/build-ghostty.sh`).
- The full FFM smoke test, Gate 0C/0D/0E terminal spikes, and the jlink
  runtime image all run end to end, repeatedly, with no crashes beyond the
  one documented close-while-child-alive defect.
- The generated jlink image's own `runtime/bin/java` and the
  `lib/macos-x86_64/*.dylib` files are confirmed genuine single-architecture
  x86_64 Mach-O binaries via `file(1)`.
- The single `os.arch`-based `detectArchDirectoryName()` arch-selection
  logic (the only place allowed to branch on architecture, per the narrow
  native boundary requirement) correctly resolves to the x86_64
  subdirectory at runtime, both from `build/native/` (dev builds) and from
  the packaged image's `lib/` (Task 8).

**Unverified / future work on arm64** (no Apple Silicon hardware available
in this environment):
- `build/native/macos-arm64/libghostty.dylib` and
  `.../libdrydockterminalhost.dylib` are built by the same
  `scripts/build-ghostty.sh`/`buildNativeHost` pipeline and confirmed
  genuine, correctly-tagged `arm64` Mach-O binaries via `file(1)` and
  `lipo -info` — but never actually loaded, `dlopen`'d, or exercised by any
  running process.
- The bundled `build/image/lib/macos-arm64/*.dylib` files are present and
  correctly tagged in the jlink image but likewise never executed.
- `jlink`'s own output (`runtime/bin/java`) is single-architecture, matching
  whatever machine built it (x86_64 here). A literal copy of `build/image`
  cannot run natively on Apple Silicon today — a true dual-arch *runtime*
  image would need either two separate jlink outputs (one per JavaFX
  platform classifier, each paired with its matching native libs) or an
  arch-picking outer launcher. This is scoped as explicit future work, not
  attempted in Task 8, since the acceptance criterion was only "runs on the
  machine that built it."
- The AppKit/FFM interaction (host shim <-> `com.sun.glass.ui` <-> Ghostty)
  has no known reason to differ across Apple Silicon vs. Intel Macs (no
  arch-specific code exists above the narrow native boundary), but this is
  an expectation, not a verified fact — a human with Apple Silicon hardware
  should run `./gradlew ffmSmokeTest`, `gate0cSpike`, `gate0dSpike`, and a
  `build/image` smoke test there before the dual-arch claim can be
  considered fully proven.

## Deviations from the plan

1. **Dual-architecture target (macOS x86_64 + arm64) instead of Apple
   Silicon only** (plan section 3.2) — explicitly pre-approved by the user
   for the whole session. All arch-selection logic is isolated inside the
   narrow native boundary package (`app.drydock.terminal.ghostty`/
   `app.drydock.terminal.host`'s `detectArchDirectoryName()`), per the user's
   explicit instruction; no arch checks exist anywhere else in the
   codebase. See "Dual-arch status" above for exactly what is verified vs.
   not.
2. **Ghostty build patch** (`third_party/patches/ghostty-install-macos-shared-lib.patch`,
   applied idempotently, not committed into the pristine submodule) —
   necessary because (a) Ghostty's own Darwin build guard prevents
   installing its already-correct shared-library target on macOS (upstream
   comment admits this is a known bug), and (b) Apple's `libtool -static`
   silently drops 8-byte-misaligned archive members, corrupting the
   default static-archive deliverable in a way that would have required
   hand-linking Ghostty's entire dependency graph (glslang, spirv-cross,
   sentry, simdutf, libintl, freetype, harfbuzz, ...) to work around
   without this patch.
3. **Zig 0.15.2 pinned explicitly** (`/usr/local/opt/zig@0.15/bin/zig`, not
   the Homebrew-default 0.16.0 on `PATH`) — Ghostty's `build.zig.zon`
   requires exactly 0.15.x and its build script does not even compile under
   0.16 (`std.process.EnvMap` removed). Documented, not silently patched
   around; `scripts/build-ghostty.sh` auto-detects the correct binary
   (override via `ZIG_BIN`).
4. **Gradle itself must run under JDK <= ~24** (this session used
   `~/.sdkman/candidates/java/23.0.1-tem`), even though the *toolchain*
   used to compile/run/package the application is genuine JDK 26 — Gradle
   8.11.1's own launcher throws `IllegalArgumentException: 26.0.1` from its
   internal version parser. This is a Gradle/JDK-26 tooling incompatibility
   unrelated to the project's own code; documented in `README.md`, not
   worked around by downgrading the toolchain.
5. **One extra native function beyond plan section 8's literal 6-function
   host-shim API**: `drydock_terminal_host_set_key_event_callback`, added
   because nothing else in the suggested API can deliver AppKit's
   `keyDown`/`keyUp`/`flagsChanged` events to Java; documented at the point
   of addition (Task 5) as a justified, minimal extension rather than a
   silent scope change.
6. **`GhosttySmokeTest`/`GhosttyBinding` are hand-written FFM bindings, not
   jextract-generated**, per plan rule 27.17's preference to keep the
   binding minimal for the small API surface actually used so far; any
   future jextract-generated bindings for a larger surface must live in a
   separate source set, not merged into this hand-written code.

No deviation was made from plan section 21 ("Security Constraints") or
section 3.2/3.3 scope-limiting rules (no project-management features, no
multi-repo/session UI, no persistence layer, etc. were built) — everything
in Tasks 1-8 stayed within Milestone 0's terminal-embedding feasibility
scope.
