# Porting cross-arch jlink images onto the cpm.packaging convention plugin

## Problem

While `feat/paackaging` was in progress, `master` merged a large refactor
(`446ec65` "Decompose packaging into a cpm.packaging convention plugin
with typed tasks") that moved the entire `runtimeImage`/`appImage`
implementation out of `app/build.gradle.kts` into typed Gradle task
classes (`buildSrc/src/main/kotlin/cpm/tasks/{RuntimeImageTask,
AppBundleTask}.kt`, injected `ExecOperations`/`FileSystemOperations` for
config-cache compatibility) plus verbatim template files under
`app/packaging/`, wired together by a `cpm.packaging` convention plugin
(`buildSrc/src/main/kotlin/cpm.packaging.gradle.kts`). A plain merge of
`feat/paackaging` would conflict at the file-structure level (code our
branch edited no longer exists where we left it) and risks resurrecting
deleted legacy task registrations.

This design ports the already-approved cross-arch capability
(`docs/superpowers/specs/2026-07-20-cross-arch-runtime-image-design.md` —
pinned jmods artifacts, checksums, non-goals all still apply unchanged)
onto the new typed-task architecture, and the already-shipped single-arch
packaging capability
(`docs/superpowers/specs/2026-07-20-runtime-image-packaging-design.md`)
forward with no changes needed (it only touches standalone scripts, which
don't conflict).

## What carries forward unchanged

- All pinned URLs/checksums for the jmods artifacts (build `26.0.1+8`).
- `scripts/package-runtime-image.sh` and its `--all-arches` flag/contract:
  still invokes `./gradlew runtimeImage` (unchanged task, unchanged
  output `build/image`) by default, and `./gradlew runtimeImageAllArches`
  (same task *name*, now implemented differently underneath) for
  `--all-arches`, tarring `build/image-macos-<arch>` directories. No
  script changes needed beyond re-verifying against the new task
  implementation.
- The non-goals from the original cross-arch design: no `appImage`/
  `macApp`/`dmg` extension to cross-arch, no execution of the
  foreign-architecture binary, no CI wiring.

## What changes

### `scripts/download-cross-jmods.sh`: add a stable symlink

In addition to the existing stdout contract (prints the real extracted
directory path, e.g. `.../jdk-26.0.1+8-jmods`), the script now also
creates/refreshes a symlink at `<output-dir>/jmods` pointing at that real
directory, on both the idempotent-reuse path and the fresh-extraction
path. This gives Gradle a fixed, predictable path
(`build/cross-jdk/<arch>/jmods`) to declare as a plain `@InputDirectory`,
with no stdout-capture/`ExecOperations`-output-redirection ceremony
needed — consistent with the rest of this codebase's typed-task,
config-cache-friendly direction. The CLI stdout contract is unchanged
(still used by manual/CLI invocations); the symlink is purely additive.

### New typed task: `DownloadCrossJmodsTask`

`buildSrc/src/main/kotlin/cpm/tasks/DownloadCrossJmodsTask.kt` — wraps
`scripts/download-cross-jmods.sh` via injected `ExecOperations`, matching
`RuntimeImageTask`/`AppBundleTask`'s existing style:

- `@Input arch: Property<String>` (`"macos-x86_64"` or `"macos-arm64"`).
- `@OutputDirectory outputDir: DirectoryProperty` (e.g.
  `build/cross-jdk/<arch>`, containing the symlink after execution).
- `@TaskAction`: `execOps.exec { commandLine(scriptPath, arch.get(), outputDir.get().asFile.absolutePath) }`.

### `RuntimeImageTask`: three new optional inputs, zero behavior change by default

Extend `buildSrc/src/main/kotlin/cpm/tasks/RuntimeImageTask.kt` with:

- `@get:InputFiles @get:Optional abstract val crossFxJars: ConfigurableFileCollection`
  — when non-empty, used instead of filtering `runtimeClasspath` for
  `javafx-`-prefixed jars (both for the jlink module path and for the
  copy into `app/`). Empty by default (the existing `runtimeImage`
  registration never sets this, so its behavior is byte-for-byte
  unchanged).
- `@get:InputFiles @get:Optional abstract val extraModulePath: ConfigurableFileCollection`
  — extra `--module-path` entries prepended before the FX jars (the
  downloaded jmods directory, for a cross build). Empty by default.
- `@get:Input @get:Optional abstract val expectedMachOArch: Property<String>`
  — when present, the produced `runtime/bin/java` is verified via
  `file(1)` to report that architecture token, hard-failing otherwise
  (mirrors `scripts/build-native-host.sh`'s existing pattern). Absent by
  default (no check for the host build — consistent with the original
  cross-arch design's choice not to gate the already-verified host path).
- When `expectedMachOArch` is present, `assemble()` also verifies the
  host JDK's `release` file `JAVA_RUNTIME_VERSION` matches the pinned
  jmods build (`26.0.1+8`) before attempting the cross-link, exactly as
  the original cross-arch design's fix (commit `10ce173` on the
  now-superseded branch history) — failing fast with a clear message
  rather than a cryptic `jlink` error on drift.

`assemble()`'s jar-copy step also changes to copy `appJar` + every
runtime-classpath jar that is **not** `javafx-`-prefixed (architecture-
independent third-party jars like RichTextFX) + the resolved `fxJars`
(host or cross, whichever applies) — so a cross-arch image gets the
cross-classified FX jars in its `app/` classpath directory, not the
host's.

### `cpm.packaging.gradle.kts`: two new task instances + a lifecycle task

The existing `runtimeImage`/`appImage` registrations are untouched. New,
purely additive registrations:

- Host-architecture detection (`System.getProperty("os.arch")`, mapped
  identically to `GhosttyNativeLibrary.detectArchDirectoryName()`:
  `x86_64`/`amd64` → `macos-x86_64`; `aarch64`/`arm64` → `macos-arm64`).
- For the cross architecture only: one `DownloadCrossJmodsTask` instance,
  and a detached `Configuration` resolving the cross-classified JavaFX
  dependency (`org.openjfx:javafx-{base,controls,graphics}:26:<classifier>`
  with explicit `Usage`/`Category`/`LibraryElements`/
  `OperatingSystemFamily`/`MachineArchitecture` attributes — the same
  fix already proven necessary and working on the superseded branch).
- Two `RuntimeImageTask` instances, `runtimeImageMacosX8664` and
  `runtimeImageMacosArm64` (task names cannot contain hyphens as
  written here without backticks convention used elsewhere; match
  whatever this file's existing naming convention prefers — verify
  against sibling task names before finalizing), each with its own
  `imageDir` = `build/image-macos-<arch>`:
  - The one matching the host architecture: configured identically to
    `runtimeImage` (same `runtimeClasspath`, no `crossFxJars`/
    `extraModulePath`/`expectedMachOArch`) but a different `imageDir`.
  - The one for the *other* architecture: `crossFxJars` from the detached
    configuration, `extraModulePath` from the download task's `jmods`
    symlink directory, `expectedMachOArch` set to that architecture's
    `file(1)` token, and `dependsOn` the download task.
- A lifecycle task `runtimeImageAllArches` (plain `tasks.register("...")`,
  no typed class needed — it does nothing but depend on the two image
  tasks) with `dependsOn` both per-arch tasks.

### Root `build.gradle.kts`

One new alias task, matching the existing `runtimeImage`/`appImage`
alias pattern exactly: `runtimeImageAllArches` → `dependsOn(":app:runtimeImageAllArches")`.

## Git mechanics

`feat/paackaging` is updated in place, rebased in spirit rather than
mechanically: its tip moves from the old branch point (`966bbaa`) to
current `master` (`016ad39`). The documentation commits (specs, plans,
and the `docs/runtime-image.md` corrections — pure additions/edits with
no structural conflict) and the two standalone scripts carry forward as
they are (re-applied on top of current master, not re-derived). The
Kotlin integration is implemented fresh against the new typed-task
architecture described above, replacing the superseded
`app/build.gradle.kts`-based commits entirely. The old commits remain
reachable via reflog/the git object store for reference, but the branch
ref itself moves forward.

## Testing

Same acceptance bar as the original cross-arch design: build
`runtimeImageAllArches` on this Intel machine, confirm both
`build/image-macos-x86_64/runtime/bin/java` and
`build/image-macos-arm64/runtime/bin/java` exist and `file(1)` reports
correctly different architectures, confirm the JDK-build-mismatch guard
fires on an induced mismatch and is silent on a real match, confirm
`scripts/package-runtime-image.sh --all-arches` still produces two
correctly-named, launchable (host-arch one) archives. Additionally:
confirm the existing `runtimeImage`/`appImage` tasks are provably
unaffected (same output, same behavior) by running them before and after
the `RuntimeImageTask` extension and diffing output.
