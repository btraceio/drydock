# Cross-architecture jlink runtime image

## Problem

`./gradlew runtimeImage` (and the `scripts/package-runtime-image.sh` script
added earlier) produce a self-contained runtime image, but only for the
architecture of the machine that ran the build. `jlink` links whatever
`--module-path` it is given; today's `runtimeImage` task supplies no
explicit `--module-path` entry for `java.*`/`jdk.*` modules at all, relying
on `jlink` resolving them implicitly from the JDK it is itself running from
(`app/build.gradle.kts`, `runtimeImage` task comment) — which only ever
matches the *host's own* architecture. The JavaFX module jars have the same
gap: they come from `runtimeClasspath`, which the `javafx-gradle-plugin`
resolves to whichever platform classifier (`mac` / `mac-aarch64`) matches
the JVM running the build (`app/build.gradle.kts` top-of-file comment) —
also host-arch only. Only the native `.dylib`s (`libghostty`,
`libcpmterminalhost`) are already genuinely dual-arch, built explicitly for
both by `scripts/build-ghostty.sh`/`scripts/build-native-host.sh`.

This means an Intel Mac cannot today produce a package that actually runs
on Apple Silicon (`docs/runtime-image.md`, "What was verified vs. not":
"`runtime/bin/java` itself is x86_64-only... Producing a dual-arch runtime
... would need either two separate jlink invocations ... or a
universal/fat launcher"). `jlink` supports genuine cross-linking (JEP 220):
given the target platform's own `jmods` and module jars via
`--module-path`, it can produce a runtime image for a platform other than
the one it's running on. This design adds that.

## Non-goals

- Actually **running** the foreign-architecture binary. This design
  produces bits and verifies their *architecture tag* via `file(1)` (the
  same acceptance-gate pattern `buildGhosttyNative`/`buildNativeHost`
  already use), not their runtime behavior. Real execution verification on
  Apple Silicon hardware is deferred to CI, per explicit instruction.
- Extending `appImage`/`macApp`/`dmg` (the `.app` bundle / ad hoc signing
  tasks) to cross-arch. Those stay host-arch only. This design only covers
  the raw jlink image (`runtimeImage`'s output shape) and the tarball
  packaging script.
- Changing the default `./gradlew runtimeImage`/`./gradlew run`/CI dev
  loop. Those must keep working exactly as they do today: fast, offline,
  host-arch only. Cross-arch building is strictly opt-in.
- Windows/Linux cross-targets, or any architecture pair beyond
  macOS x86_64 ⟷ macOS arm64 (the two already-supported architectures per
  `README.md` "Supported platforms").

## Pinned artifacts

Both architectures' `jmods` bundles, matching the project's documented JDK
26.0.1 (build `26.0.1+8`) toolchain exactly, verified via the Adoptium API
and independently cross-checked against each asset's published
`.sha256.txt` sidecar file:

| Target arch | Archive | SHA-256 |
|---|---|---|
| `macos-arm64` | `OpenJDK26U-jmods_aarch64_mac_hotspot_26.0.1_8.tar.gz` | `e76d5df4bf1e1568b1de1332b1784e815746288309c79b08c72ae48545663484` |
| `macos-x86_64` | `OpenJDK26U-jmods_x64_mac_hotspot_26.0.1_8.tar.gz` | `c323f7f94018e91a472273e9986e98890b0ca92dfd9bbdc9960c3edc6627b6b7` |

Download URLs (GitHub release assets, stable):
```
https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_aarch64_mac_hotspot_26.0.1_8.tar.gz
https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_x64_mac_hotspot_26.0.1_8.tar.gz
```

If the project's toolchain JDK version ever changes, these pinned
URLs/hashes must be updated together — call this out as a maintenance note
in the new script's header comment, the same way `scripts/build-ghostty.sh`
documents its exact Zig 0.15.2 version requirement.

## Design

### `scripts/download-cross-jmods.sh <macos-x86_64|macos-arm64> <output-dir>`

New script, styled like the existing `scripts/*.sh` (bash,
`set -euo pipefail`, `fail()` helper, `==>` progress messages).

1. If `<output-dir>/jmods/` already contains `.jmod` files, exit 0
   immediately (idempotent — no re-download).
2. Resolve the archive+checksum for the requested arch from the pinned
   table above (hard-fail on any other value — no guessing).
3. Acquire the archive: if `CPM_CROSS_JMODS_ARCHIVE` is set, use that local
   file path; otherwise `curl` the pinned URL to a temp file.
4. Verify SHA-256 against the pinned hash regardless of source (local
   override included — the override exists to swap the *transport*, e.g.
   an internal mirror or pre-downloaded copy, not to bypass integrity
   checking). Hard-fail with the expected vs. actual hash on mismatch.
5. Extract to `<output-dir>`, then `find <output-dir> -type d -name jmods`
   to locate the actual `jmods/` directory (the archive's internal
   top-level directory name is not hardcoded — verify what it actually is
   during implementation rather than assuming). Hard-fail if not found or
   if more than one match exists.
6. Print the resolved `jmods/` path on success (last line of stdout, so
   the caller — a Gradle `Exec`/`exec {}` block — can capture it).

### `app/build.gradle.kts`: extract the shared assembly function

The current `runtimeImage` task's `doLast` block (jlink invocation, app jar
+ classpath copy, dual-arch native `.dylib` copy, icon, launcher script
generation) is refactored into a private function, e.g.:

```kotlin
fun assembleRuntimeImage(
    outputDir: File,
    jlinkModulePath: List<File>,   // javafx jars, and (foreign-arch only) the jmods dir
    javaHomeForJlink: File,        // whose bin/jlink to invoke — always the host JDK 26 toolchain
    appJar: File,
    classpathJars: List<File>,
    nativeBuildDir: File,
)
```

`runtimeImage` (the existing task) calls this with its current arguments
unchanged — same `build/image/` output, same implicit-`jrt:`-resolution
behavior for `java.*`/`jdk.*` modules (i.e. `jlinkModulePath` for the host
build contains only the host-arch JavaFX jars, exactly as today; no jmods
directory is added for the host's own arch, since implicit resolution
already works and is already verified). This is a pure refactor: verify
`build/image/` is byte-for-byte identical before/after (diff a build run
before the refactor against one after, modulo timestamps).

### New task: `runtimeImageAllArches`

```
group = "distribution"
description = "Cross-links jlink runtime images for BOTH macOS architectures " +
    "(x86_64 and arm64) in one pass, regardless of host arch. Downloads the " +
    "non-host architecture's jmods via scripts/download-cross-jmods.sh. " +
    "Produces build/image-macos-x86_64/ and build/image-macos-arm64/. Does " +
    "NOT execute the foreign-architecture binary -- only its Mach-O " +
    "architecture tag is verified (file(1)). Real execution verification is " +
    "CI's job, not this task's."
```

For each of `macos-x86_64`/`macos-arm64`:
- If it's the host's own arch: call `assembleRuntimeImage` exactly like
  `runtimeImage` does (implicit resolution, host's own FX jars), output to
  `build/image-<arch>/`.
- If it's the *other* arch:
  1. Run `scripts/download-cross-jmods.sh <arch> build/cross-jdk/<arch>` to
     get the jmods dir.
  2. Resolve that arch's JavaFX 26 jars via an explicit Gradle
     `Configuration` with the matching classifier (`mac-aarch64` for
     `macos-arm64`, `mac` for `macos-x86_64`) — a normal Maven Central
     dependency resolution, the same trusted artifact source the project
     already depends on for JavaFX, just with an explicit rather than
     platform-inferred classifier.
  3. Call `assembleRuntimeImage` with `jlinkModulePath` = jmods dir +
     those explicitly-resolved FX jars, output to `build/image-<arch>/`.
  4. After linking: `file(1)` on `build/image-<arch>/runtime/bin/java`;
     hard-fail (naming the actual vs. expected architecture string) if it
     doesn't report the expected Mach-O architecture. This mirrors
     `scripts/build-native-host.sh`'s existing `file -b ... | grep -oE
     'x86_64|arm64'` acceptance-gate pattern exactly.

Both output directories get the full existing image contents (native libs
for both arches already ship in every image today, per the existing
`runtimeImage` task's step 3 — unchanged) plus the arch-appropriate JVM.

### `scripts/package-runtime-image.sh`: `--all-arches` flag

Default (no flag): unchanged behavior — `./gradlew runtimeImage`, tar
`build/image/`, one archive, host-arch only.

With `--all-arches`: runs `./gradlew runtimeImageAllArches` instead, then
tars each `build/image-macos-<arch>/` into its own
`build/dist/cpm-image-macos-<arch>-<git-short-sha>.tar.gz` (same naming
pattern as today, now produced twice in one invocation), printing the same
copy/run instructions block per archive. The "this archive only runs on
the architecture it was built on, re-run this script on a Mac of that
architecture to get the other one" note is dropped for the arm64 output
specifically (replaced with a note that this one was cross-linked and its
*bits* are verified but its *execution* is not — direct the reader to
whatever CI verification exists once that lands, without asserting it
exists yet).

## Testing

- **Refactor regression:** build `build/image/` before and after the
  `assembleRuntimeImage` extraction; diff directory trees (paths + file
  sizes, since embedded build timestamps if any would differ) to confirm
  no behavior change to the existing host-arch task.
- **Download script:** run `download-cross-jmods.sh` for the non-host arch
  twice in a row — first run downloads/verifies/extracts and prints the
  path; second run is a no-op (idempotency). Corrupt one byte of a cached
  archive copy and confirm checksum verification catches it (test the
  failure path, not just the success path).
- **Cross-link:** run `runtimeImageAllArches` on this (Intel) machine;
  confirm both `build/image-macos-x86_64/runtime/bin/java` and
  `build/image-macos-arm64/runtime/bin/java` exist and `file(1)` reports
  the correct, *different* architectures for each — the concrete
  regression this whole design exists to fix (today only one exists, and
  it's always x86_64 regardless of what's asked for).
- **Packaging:** run `package-runtime-image.sh --all-arches`; confirm two
  correctly-named, non-empty tarballs are produced, and that the existing
  default (no-flag) invocation still produces exactly the one
  host-arch-only tarball it always has.
- Explicitly **not** tested here (deferred to CI per instruction): actually
  launching the arm64 image's `claude-project-manager` launcher on real
  Apple Silicon hardware.
