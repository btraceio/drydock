# Architecture Notes / Unresolved Risks

Per plan section 27 rule 15 ("Record unresolved risks in
`docs/architecture.md`"). This file tracks risks flagged during Gate 0
tasks that are not yet resolved; see `docs/native-integration.md` for the
detailed investigation and `README.md` for how libghostty is currently
built. It will grow into fuller architecture documentation as later
milestones (Gate 0C onward) land.

## Unresolved risks (as of Task 4)

- **Whether JavaFX can expose a real `NSView*` at all.** Plan rule 27.7
  explicitly forbids assuming this without verification. `ghostty_surface_new`
  requires a real, addressable `NSView*` (see `docs/native-integration.md`,
  "Embedding examples" / "nsview embedding model"). If JavaFX has no public
  API for this, the native macOS host shim anticipated by plan section 8
  becomes mandatory, not a fallback. **Must be established in Gate 0C**,
  not assumed.
- **Exact create/destroy ordering and thread-affinity edge cases** for
  `ghostty_app_t` / `ghostty_surface_t` teardown. `docs/native-integration.md`
  documents the general contract (single "main"/UI-thread affinity,
  `ghostty_init` once per process, `wakeup_cb` marshaling), but the header
  does not fully document teardown ordering (surface-before-app; whether
  `ghostty_app_tick` may still fire after `ghostty_surface_free`). **Must be
  established empirically in Gates 0B–0D**; Gate 0B (Task 4) only exercised
  `ghostty_init`/`ghostty_info`/`ghostty_config_new`/`ghostty_config_free`,
  not the app/surface lifecycle.
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

All libghostty interaction lives in `app.cpm.terminal.ghostty`
(`app/src/main/java/app/cpm/terminal/ghostty/`):

- `GhosttyNativeLibrary` -- resolves and loads the architecture-matching
  `libghostty.dylib` (this is also the one place allowed to branch on
  `os.arch`, per the approved dual-architecture deviation in `README.md`).
- `GhosttyBinding` -- hand-written FFM `MethodHandle`s for the minimal API
  surface needed so far (`ghostty_init`, `ghostty_info`,
  `ghostty_config_new`/`ghostty_config_free`).
- `GhosttySmokeTest` -- the Gate 0B command-line entry point
  (`./gradlew ffmSmokeTest`).

No other package may reference `MemorySegment`, `MethodHandle`, `Linker`,
generated libghostty bindings, native pointers, or AppKit handles. If/when
bindings are generated (e.g. via `jextract`) for the larger surface-
embedding API needed from Gate 0C onward, they must live in a separate
source set/package from the hand-written code above (plan rule 27.17).
