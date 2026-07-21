# Architecture Notes / Unresolved Risks

Per plan section 27 rule 15 ("Record unresolved risks in
`docs/architecture.md`"). This file tracks risks flagged during Gate 0
tasks that are not yet resolved; see `docs/native-integration.md` for the
detailed investigation and `README.md` for how libghostty is currently
built. It will grow into fuller architecture documentation as later
milestones (Gate 0C onward) land.

## Resolved in Task 5 (Gate 0C)

- **Whether JavaFX can expose a real `NSView*` at all.** Resolved: **no**,
  not through any public API (verified via `javap` against the actual
  `javafx-graphics-26-mac.jar`, not assumed). A native pointer is reachable
  only through the internal, unsupported `com.sun.glass.ui` package. The
  AppKit host shim from plan section 8 was therefore implemented (not just
  anticipated) — see `native-host/DrydockTerminalHost.{h,m}` and
  `app.drydock.terminal.host`. Full writeup, including the qualified-export
  detail and why no `--add-exports` flag is currently needed (classpath-mode
  JavaFX, not module-path), in `docs/native-integration.md`, "Task 5 / Gate
  0C".
- **Create/destroy ordering for `ghostty_app_t`/`ghostty_surface_t`**:
  partially established — `app.drydock.terminal.ghostty.GhosttyApp`/`GhosttySurface`
  implement and exercise surface-before-app teardown
  (`GhosttySurface#close` then `GhosttyApp#close`), verified crash-free in
  the Gate 0C spike's shutdown path. **Still open**: whether
  `ghostty_app_tick` can safely be called after (or concurrently with) a
  `ghostty_surface_free` on the *same* app with *multiple* surfaces —
  Gate 0C only ever has one surface, so this is untested.

## Unresolved risks (as of Task 5)

- **`action_cb` is a no-op stub that always returns `false` without reading
  its payload.** See `docs/native-integration.md`, "What is and isn't wired
  up for Gate 0C" — `ghostty_action_s` is a large tagged union covering
  every UI-triggered action (new window/tab, OSC 52 clipboard writes,
  fullscreen, quit confirmation, IPC, etc.). None of these are handled.
  This is fine for Gate 0C's narrow scope (typing text, resizing, drawing)
  but **must be implemented before real usage** (Gate 0D/0E and beyond) —
  otherwise, e.g., `claude`'s use of OSC 52 clipboard writes, or a
  keybinding-triggered action, will silently do nothing.
- **JavaFX-Application-Thread-is-AppKit-main-thread was not independently
  proven**, only exercised without incident. See
  `docs/native-integration.md`'s threading-contract paragraph for the
  specific cross-check (`Thread`/`NSThread` identity comparison) that would
  make this airtight; recommended before Gate 0D introduces a real,
  continuously-running child process and much higher event volume.
- **`JavaFxNativeView`'s "most recently created window" heuristic** only
  works for a single-window process (true of every Gate so far, false of
  the eventual multi-repository/multi-session application). A real fix
  needs a `Stage`-to-native-`Window` correlation this task deliberately did
  not implement (see `docs/native-integration.md`).
- **The Gate 0C spike's key-code mapping is intentionally minimal**
  (printable text plus Enter/Backspace/Tab/Escape/arrows only) — it exists
  to satisfy Gate 0C's "keyboard input reaches the terminal" criterion, not
  Gate 0D's much larger checklist (modifiers, Ctrl+C/D, Option combos,
  copy/paste, IME, function keys, etc.), which is explicitly out of scope
  until Task 6.
- **The Gate 0C automated run could not capture/verify a screenshot**: the
  process lacks macOS Screen Recording permission (`screencapture` failed
  with "could not create image from display", confirmed via a failed
  `TCC.db` query, not just assumed). No agent/CI process can grant this
  permission itself; a human must run `./gradlew gate0cSpike
  -Papp.drydock.gate0c.interactive` locally once, with that permission granted,
  to visually confirm actual terminal glyphs render (as opposed to just
  "no crash while calling draw").
- **Ghostty's own libtool-merged static archive silently drops archive
  members** (see `docs/native-integration.md`, "Task 4 update" section, and
  `third_party/patches/ghostty-install-macos-shared-lib.patch`). Worked
  around by patching `build.zig`/`SharedDeps.zig` to install the (correctly
  linked) shared library instead of relying on the static-archive merge.
  This is an upstream/toolchain defect outside this project's control;
  if a future Ghostty version fixes it, the patch may become unnecessary
  (or fail to apply, which `scripts/build-ghostty.sh` will report loudly
  rather than silently skip).
- **Sentry crash-reporting code is compiled into libghostty** (see the
  `sentry_*` object files pulled in by the build). This project has not
  configured, enabled, or exercised any Sentry reporting path -- it is
  vendored, dormant code, not something this project's build turns on.
  Revisit if plan section 21 ("Do not add telemetry", "Do not upload logs")
  is ever at risk of being violated transitively; not believed to be a risk
  today since nothing in this project calls Ghostty's crash-reporting init
  paths.

## Narrow native boundary (plan section 2.4 / 4.2)

All native (libghostty + AppKit host shim) interaction lives in two
sibling packages under `app/src/main/java/app/drydock/terminal/`:

`app.drydock.terminal.ghostty/` (libghostty):
- `GhosttyNativeLibrary` -- resolves and loads the architecture-matching
  `libghostty.dylib` (this is also the one place allowed to branch on
  `os.arch`, per the approved dual-architecture deviation in `README.md`).
- `GhosttyBinding` -- Gate 0B bindings (`ghostty_init`, `ghostty_info`,
  `ghostty_config_new`/`ghostty_config_free`).
- `GhosttyAppBinding` -- Gate 0C struct layouts + downcall handles for the
  app/surface API (`ghostty_app_*`, `ghostty_surface_*`).
- `GhosttyApp` / `GhosttySurface` -- public, `MemorySegment`-free lifecycle
  wrappers around one `ghostty_app_t`/`ghostty_surface_t`.
- `GhosttySmokeTest` -- the Gate 0B command-line entry point
  (`./gradlew ffmSmokeTest`).

`app.drydock.terminal.host/` (AppKit host shim, plan section 8):
- `DrydockTerminalHostLibrary` / `DrydockTerminalHostBinding` -- loads
  `libdrydockterminalhost.dylib` and binds its 7 functions (6 from the plan's
  suggested API + 1 documented key-event-forwarding extension; see
  `docs/native-integration.md`).
- `JavaFxNativeView` -- package-private; the *only* class anywhere in this
  codebase that touches the internal `com.sun.glass.ui` API, and only to
  obtain one `NSView*` pointer.
- `DrydockTerminalHost` -- public, `MemorySegment`-free (except
  `contentViewHandle()`, callable only from the ghostty package) entry
  point.

The corresponding native sources are `third_party/ghostty` (vendored,
patched per `third_party/patches/`) and `native-host/DrydockTerminalHost.{h,m}`
(this project's own tiny AppKit shim, with zero dependency on Ghostty).

No other package may reference `MemorySegment`, `MethodHandle`, `Linker`,
generated libghostty bindings, native pointers, or AppKit handles.
`app.drydock.terminal.Gate0cSpike` (the Gate 0C composition root) only ever
calls the public, pointer-free methods on `GhosttyApp`/`GhosttySurface`/
`DrydockTerminalHost`. If/when bindings are generated (e.g. via `jextract`) for
a larger API surface, they must live in a separate source set/package from
the hand-written code above (plan rule 27.17).

## Unresolved risks (added in Task 7 / Gate 0E)

Full detail and reproduction steps in `docs/claude-integration.md`. Summarized
here per plan rule 15:

- **Closing a `GhosttySurface` whose child process is still alive terminates the
  entire JVM with a non-zero exit status**, reproducibly, with no catchable Java
  exception logged anywhere in this project's own code. Gate 0C/0D never hit this
  because their checklists always drove the child to a confirmed clean exit
  (`processExited() == true`) before closing. Gate 0E's `claude` transcript is the
  first spike to close a surface with a live child, and does so every time it runs
  to its scripted end. **Must be fixed with a bounded wait-for-exit-then-force-close
  protocol before any real session-tab-closing code is written**, and needs its own
  focused regression test once that lifecycle code exists (plan rule 14). This is
  also the most likely root cause of a second observed symptom: `claude --resume`
  reporting "No conversations found" for a repo a full multi-turn session had just
  run in — abrupt teardown likely pre-empts `claude`'s own on-exit session-persistence
  write.
- **A fixed-delay-then-send-Enter script can lose a keystroke to `claude`'s TUI**
  if sent too soon after a screen transition (a completed response, or a fresh
  relaunch banner) — not a ghostty defect (the identical `sendKey` call is 100%
  reliable against a plain shell per Gate 0D), but a real risk for anything that
  drives this terminal programmatically at faster-than-human speed. Any future
  scripted-input code (this project's own automation, or something built on top of
  it) should poll `readScreenText()` for two consecutive identical reads
  ("screen has settled") before sending the next key, rather than trusting a fixed
  delay.
- **Ctrl+C did not interrupt an in-progress `claude` response** in either Gate 0E
  run, using the exact mechanism Gate 0D proved interrupts a foreground shell
  command. Not yet root-caused (ghostty-level delivery vs. `claude`/Ink-level
  debounce are both plausible); needs re-verification with a real physical
  keypress before being treated as a confirmed application bug, but is flagged as
  a real risk to the plan's Gate 0E "Ctrl+C cancellation" requirement either way.
- **The workspace-trust and per-tool permission prompts could not be exercised**
  on this dev machine, because its own pre-existing `~/.claude/settings.json` has
  `permissions.defaultMode: "auto"` (the user's own configuration; this project
  never reads or writes that file). The application cannot assume this UI will
  always render in the embedded terminal — its presence is entirely a function of
  the user's own Claude Code settings, which are explicitly out of this project's
  control (plan section 21). Needs a from-clean-account manual pass before v0.1
  ships, per the same rule that flagged this as unresolved rather than assumed-ok.

## Resolved / added in Task 8 (Gate 0F, jlink runtime image)

Full detail in `docs/runtime-image.md`. Summary:

- The first self-contained `jlink` runtime image (`./gradlew runtimeImage`
  -> `build/image/`) builds and runs the Gate 0C terminal spike end to end
  with `JAVA_HOME` unset, no Gradle, and copied outside the source tree —
  verified on this Intel Mac. `appImage`/`macApp`/`dmg` (plan section 6.3)
  are registered as explicit not-yet-implemented no-ops (plan section 23.4
  Stages 3-6 are out of scope for this phase).
- **Real, packaging-only defect found and fixed:** `JavaFxNativeView`'s
  reflective use of `com.sun.glass.ui.Window`/`View` (see above) throws
  `IllegalAccessError` inside the jlink image, even though it works
  unmodified in every dev-mode Gradle spike task. Cause: `jlink` links
  `javafx.graphics` in as a real named module, while the application
  itself still runs from `-cp` (unnamed module, not yet modularized) — a
  module-boundary enforcement gap that classpath-mode dev execution simply
  never exercises. Fixed with `--add-exports
  javafx.graphics/com.sun.glass.ui=ALL-UNNAMED` on the generated launcher.
  Flagged as new evidence for plan rule 27.8: this class of internal-API
  risk was not just "unstable across JavaFX versions" but "silently
  untested by any dev-mode task" until Task 8 built something with the
  application's real production module topology.
- Dual-architecture native library bundling (`lib/macos-x86_64/`,
  `lib/macos-arm64/`) required a one-line semantic change to
  `GhosttyNativeLibrary`/`DrydockTerminalHostLibrary`'s previously-uncalled
  `NATIVE_DIR_PROPERTY` override (root directory + arch subdirectory,
  instead of pointing directly at the arch-specific directory) — no new
  arch-branching code anywhere; the existing single `os.arch`-based
  `detectArchDirectoryName()` method still makes the only decision.
  Verified end-to-end for x86_64; the bundled arm64 `.dylib`s are
  confirmed genuine and correctly tagged (`file(1)`) but unverified at
  runtime (no Apple Silicon hardware available) — same gap already flagged
  for Tasks 3-5 in `docs/native-integration.md`, not a new one.
- `runtime/bin/java` itself is single-architecture (whatever `jlink` ran
  on — x86_64 here); a literal copy of this image cannot run natively on
  Apple Silicon. A true dual-arch *runtime* would need two separate jlink
  outputs (one per JavaFX platform classifier) or a picking launcher;
  left as explicit future work since Task 8's acceptance criteria only
  require the image to work on the machine that built it.
