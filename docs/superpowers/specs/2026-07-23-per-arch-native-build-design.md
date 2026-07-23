# Per-arch native build → fan-in publish (design)

## Problem

The release workflow's `stage-maven` job builds both macOS native slices
(`libghostty`, `libdrydockterminalhost`) on a single Apple Silicon runner by
**cross-compiling** the `x86_64` slice. That cross-compile fails:

```
pkg/macos/video/pixel_format.zig:55:22: error: root source file struct 'cimport'
  has no member named 'kCVPixelFormatType_30RGB_r210'
```

This is a `zig translate-c` artifact of importing CoreVideo headers for a
non-host target. It never surfaced before because every prior CI run died
earlier (the `setup-zig@v1` tarball-naming 404), so `buildGhosttyNative` had
never actually executed in CI.

## Goal

Build each macOS arch slice **natively on its own runner**, then merge the
resulting libraries in a downstream job that packages and publishes. This
sidesteps the entire class of cross-compile / translate-c problems.

**Out of scope:** Linux/Windows native libraries. The native code is macOS-only
by construction — `libdrydockterminalhost` is Objective-C on Cocoa/AppKit and
`libghostty` links Metal/CoreVideo/AppKit. Cross-platform support would require
a different terminal-host implementation and app-level platform abstraction;
that is a separate project, not a CI change.

## Key facts that shape the design

- `jbangJar` (`app/build.gradle.kts`) bundles **only** the two `.dylib` files
  per arch (`libghostty.dylib`, `libdrydockterminalhost.dylib`). It does not
  bundle the `.a` static libs, the `include/` headers, or
  `ghostty-version.properties`.
- Nothing in `compileJava` / `processResources` depends on the native outputs;
  only `jbangJar` and `run` do.
- Therefore each native job need only produce and hand off **two dylibs**, and
  the fan-in job needs only those four files total.
- GitHub runners: `macos-14`/`macos-15` are Apple Silicon (`arm64`); the current
  Intel (`x86_64`) label is `macos-15-intel` (the classic `macos-13` Intel
  runner has been retired).

## Architecture

```
setup ──┬─> prepare (workflow_dispatch & !dry_run: bump/commit/tag/push)
        │
        ├─> native-arm64   (macos-14, Apple Silicon) ─┐
        ├─> native-x86_64  (macos-15-intel)           ├─> stage-maven (macos-14)
        │                                              │
        └──────────────────────────────────────────────┘
                                                        └─> wait-for-maven
                                                              └─> create-github-release
```

### native-arm64 / native-x86_64 jobs

Each job:
1. `actions/checkout` (submodules recursive; same ref logic as `stage-maven` —
   `github.ref` under `dry_run`, else `needs.setup.outputs.ref`).
2. `mlugg/setup-zig@…` (SHA-pinned v2.2.1).
3. Ensure Metal Toolchain.
4. Run `scripts/build-ghostty.sh` and `scripts/build-native-host.sh` for **its
   own arch only** (native build, no `-Dtarget` cross-compile of the other
   arch).
5. Upload `build/native/macos-<arch>/{libghostty.dylib,libdrydockterminalhost.dylib}`
   as artifact `natives-macos-<arch>` (`if-no-files-found: error`).

`native-arm64` runs on `macos-14`; `native-x86_64` on `macos-15-intel`.

### stage-maven job

No longer builds natives, so it drops `setup-zig` and the Metal Toolchain step.
1. `actions/checkout` (ref logic as today).
2. `actions/download-artifact` for both `natives-macos-*` into
   `build/native/macos-arm64/` and `build/native/macos-x86_64/`.
3. `actions/setup-java` (26 + 17).
4. Publish with `-Pnatives.prebuilt=true`:
   - real release: `:app:publishAllPublicationsToMavenCentralRepository`
   - `dry_run`: `:app:publishToMavenLocal`
5. Upload the `jbangJar`, staging/dry-run summary steps (unchanged).

## Script changes

`scripts/build-ghostty.sh` and `scripts/build-native-host.sh` gain an
**arch selector** via an env var (e.g. `NATIVE_ARCHES`, accepting `arm64`,
`x86_64`, or both). Default remains **both**, so local `./gradlew`
development behavior is unchanged. The post-build validation loops iterate
only the arch(es) actually built.

## Gradle change: `-Pnatives.prebuilt=true`

- `buildGhosttyNative` and `buildNativeHost` gain `onlyIf { !prebuilt }` — they
  do not shell out when the property is set.
- A new lightweight `checkPrebuiltNatives` task (active only when `prebuilt`)
  asserts all four dylibs exist under `build/native/<arch>/`, failing with a
  clear message if an artifact failed to download. `jbangJar` depends on it
  when prebuilt.
- `jbangJar` otherwise consumes `build/native/<arch>/` exactly as today.
- When the property is unset, behavior is byte-for-byte identical to current.

## dry_run interaction

`dry_run` semantics are preserved: the two native jobs always run; only
`prepare`, `stage-maven`'s publish target, `wait-for-maven`, and
`create-github-release` are gated by it. Under `dry_run`, `stage-maven`
downloads the natives and runs `publishToMavenLocal` — no tags, no Central
deployment, no GitHub release.

## Risks

- **Does native compilation actually fix `kCVPixelFormatType_30RGB_r210`?**
  High likelihood (native builds are the standard cure for translate-c
  cross-target gaps), but only confirmable when it runs.
- **Intel runners are on borrowed time.** `macos-15-intel` is the current Intel
  label (the classic `macos-13` was retired). GitHub will eventually sunset
  Intel macOS runners entirely; when that happens, `x86_64` would need
  cross-compile again (revisit this design) or be dropped from the bundle.
