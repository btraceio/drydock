# Runtime image packaging (`scripts/package-runtime-image.sh`)

## Problem

`./gradlew runtimeImage` already produces a self-contained `build/image/`
(JDK 26 + JavaFX 26 + the application + libghostty + the AppKit host shim)
that has been verified to run copied outside the repo, with `JAVA_HOME`
unset and no Gradle on `PATH` (`docs/runtime-image.md`). There is, however,
no single command that produces a shareable artifact from that image, and
no documented procedure for actually moving it to another Mac.

`.app`/`.dmg` packaging (plan section 23.3/23.4, Stages 3-6) is explicitly
out of scope for this phase (`docs/runtime-image.md` "Packaging
implications"; the `appImage`/`macApp`/`dmg` Gradle tasks are deliberate
no-ops reserved for that later work). This design does not touch those
tasks or their scope. It only wraps the existing, already-working
`runtimeImage` output into a tarball that can be copied to another Mac and
run from a Terminal.

## Non-goals

- Code signing (ad hoc or Developer ID), notarization.
- `.app` bundle or `.dmg` wrapping.
- Cross-architecture building. `jlink` produces a runtime for the
  architecture it runs *on* (`docs/runtime-image.md`, "What was verified
  vs. not"); this script cannot make an Intel Mac produce an Apple Silicon
  runtime, or vice versa. Producing archives for both architectures means
  running this script once per architecture, on a machine of that
  architecture.

## Design

New script: `scripts/package-runtime-image.sh`, matching the existing
style of `scripts/build-ghostty.sh`/`scripts/verify-environment.sh`
(bash, `set -euo pipefail`, clear failure messages naming what went
wrong).

Steps:

1. Run `./gradlew runtimeImage` from the repo root. If it fails, the
   script exits non-zero immediately with Gradle's own error output — no
   partial/stale archive is produced.
2. Detect the host architecture via `uname -m`, mapped to the same
   `macos-x86_64` / `macos-arm64` naming the app's own
   `detectArchDirectoryName()` already uses (`x86_64` → `macos-x86_64`;
   `arm64` → `macos-arm64`; anything else is a hard failure naming the
   unexpected value, not a silent guess).
3. Create `build/dist/` if absent.
4. Tar `build/image/` into
   `build/dist/cpm-image-<macos-arch>-<git-short-sha>.tar.gz`
   (e.g. `cpm-image-macos-x86_64-156dc78.tar.gz`). The git short SHA
   (`git rev-parse --short HEAD`) identifies exactly what was built,
   consistent with this repo's plan-driven, milestone-tracked history;
   the script fails clearly if run outside a git checkout or with a dirty
   tree left unremarked (a dirty tree is allowed but the script prints a
   warning that the packaged build includes uncommitted changes, since
   that's a real difference from "here's build 156dc78").
5. Print, verbatim, the copy/run instructions for the receiving Mac:
   ```
   tar xzf cpm-image-macos-<arch>-<sha>.tar.gz
   ./image/bin/claude-project-manager
   ```
   plus:
   - a note that this archive only runs on the same CPU architecture it
     was built on (`<arch>`), and that producing an archive for the other
     architecture means re-running this script on a Mac of that
     architecture;
   - a reminder that the image's default launch target is the real
     application (`app.cpm.Main`), and that the Task 5 terminal spike can be
     launched via `CPM_MAIN_CLASS=app.cpm.terminal.Gate0cSpikeLauncher ./image/bin/claude-project-manager`
     if needed for testing (this environment variable already exists in the
     generated launcher; nothing new is added by this script);
   - a one-line Gatekeeper fallback: if macOS quarantines the extracted
     binaries (typically only when transferred via a browser download or
     AirDrop, not `scp`/USB), `xattr -cr ./image` clears the quarantine
     flag. The binaries remain unsigned; this is a documented workaround,
     not a fix, and is out of scope to actually resolve (see Non-goals).

## Testing

Run the script on this (Intel) machine, copy the resulting tarball to
`/tmp` to simulate "another machine" (matching the existing verification
methodology in `docs/runtime-image.md`), extract it there, and launch with
`PATH` reduced to `/usr/bin:/bin` and `JAVA_HOME` unset — confirming the
packaged artifact behaves identically to the already-verified
`build/image/` it was created from. Apple Silicon execution remains
unverified on this hardware, exactly as already flagged for the runtime
image itself; this design does not change that gap, only documents how to
close it (re-run the script on Apple Silicon hardware).
