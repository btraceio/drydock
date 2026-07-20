# Runtime Image Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `scripts/package-runtime-image.sh`, a script that builds the existing `runtimeImage` Gradle output and tars it into a shareable, architecture-labeled archive that can be copied to another Mac and run.

**Architecture:** A single new bash script, styled after `scripts/build-ghostty.sh`/`scripts/build-native-host.sh`/`scripts/verify-environment.sh` (`set -euo pipefail`, a `fail()` helper, `==>` progress echoes, explicit acceptance checks rather than "the command didn't error"). It shells out to `./gradlew runtimeImage`, then archives `build/image/` into `build/dist/cpm-image-macos-<arch>-<git-short-sha>.tar.gz`. No Gradle changes, no new dependencies.

**Tech Stack:** bash, `tar`, `uname`, `git`, the existing Gradle build.

## Global Constraints

- Do not modify or implement the `appImage`/`macApp`/`dmg` Gradle tasks in `build.gradle.kts` — they are deliberately reserved no-ops for later, out-of-scope work (plan section 23.3/23.4 Stages 3-6; see `docs/runtime-image.md` "Packaging implications"). This script is a separate, additive artifact.
- No code signing, notarization, `.app` bundling, or `.dmg` production.
- No cross-architecture building — the script packages whatever `runtimeImage` produces on the machine it runs on, and must clearly label the archive with that architecture.
- Match the existing `scripts/*.sh` style exactly: `#!/usr/bin/env bash`, `set -euo pipefail`, a header comment block explaining purpose/deliverables/env overrides, a `fail() { echo "error: $*" >&2; exit 1; }` helper, `==>` progress messages.
- Every failure path must name exactly what went wrong (no silent fallback, no generic "something went wrong").

---

### Task 1: `scripts/package-runtime-image.sh`

**Files:**
- Create: `scripts/package-runtime-image.sh`

**Interfaces:**
- Consumes: `./gradlew runtimeImage` (existing Gradle task, produces `build/image/` — see `app/build.gradle.kts` and `docs/runtime-image.md`). No new Gradle-side interface is touched.
- Produces: `build/dist/cpm-image-macos-<arch>-<sha>.tar.gz`, where `<arch>` is `x86_64` or `arm64` and `<sha>` is `git rev-parse --short HEAD`. This is the deliverable; no later task consumes it programmatically.

- [ ] **Step 1: Write the script**

Create `scripts/package-runtime-image.sh` with this exact content:

```bash
#!/usr/bin/env bash
#
# Packages the jlink runtime image (./gradlew runtimeImage, see
# docs/runtime-image.md) into a tarball that can be copied to another Mac
# and run without Gradle, JAVA_HOME, or this repository present.
#
# jlink produces a runtime for the architecture it runs ON -- it does not
# cross-build (docs/runtime-image.md, "What was verified vs. not"). This
# script therefore only ever produces an archive for the CURRENT machine's
# architecture. To get an archive for the other architecture, run this
# same script on a Mac of that architecture.
#
# This is deliberately separate from the ./gradlew appImage/macApp/dmg
# tasks, which are reserved no-ops for later .app/.dmg packaging work
# (plan section 23.3/23.4 Stages 3-6) -- this script does not implement,
# replace, or depend on any of them.
#
# Deliverable:
#   build/dist/cpm-image-macos-<arch>-<git-short-sha>.tar.gz
#
# Usage:
#   ./scripts/package-runtime-image.sh
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

fail() {
    echo "error: $*" >&2
    exit 1
}

cd "$repo_root"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || fail "$repo_root is not a git checkout; cannot determine a build identifier."

if [[ -n "$(git status --porcelain)" ]]; then
    echo "warning: working tree has uncommitted changes -- the packaged image will include them, not just commit $(git rev-parse --short HEAD)." >&2
fi

echo "==> Building runtime image (./gradlew runtimeImage)"
"$repo_root/gradlew" runtimeImage

[[ -x "$repo_root/build/image/bin/claude-project-manager" ]] \
    || fail "build/image/bin/claude-project-manager not found or not executable after runtimeImage; the Gradle task did not produce the expected launcher."

host_arch="$(uname -m)"
case "$host_arch" in
    x86_64) image_arch="x86_64" ;;
    arm64)  image_arch="arm64" ;;
    *) fail "unrecognized host architecture '$host_arch' (expected x86_64 or arm64); refusing to guess a label." ;;
esac

git_sha="$(git rev-parse --short HEAD)"
dist_dir="$repo_root/build/dist"
archive_name="cpm-image-macos-${image_arch}-${git_sha}.tar.gz"
archive_path="$dist_dir/$archive_name"

mkdir -p "$dist_dir"

echo "==> Archiving build/image into $archive_path"
tar -C "$repo_root/build" -czf "$archive_path" image

[[ -s "$archive_path" ]] || fail "$archive_path was not created or is empty."

echo "==> Done: $archive_path"
echo ""
echo "This archive runs ONLY on macOS $image_arch -- jlink builds a runtime"
echo "for the architecture it runs on, not a cross-build. To package for the"
echo "other architecture, run this script again on a Mac of that architecture."
echo ""
echo "On the target Mac:"
echo "  tar xzf $archive_name"
echo "  ./image/bin/claude-project-manager"
echo ""
echo "The image's default launch target is the Task 5 terminal spike"
echo "(Gate0cSpikeLauncher), not the real application. To launch the real"
echo "application instead:"
echo "  CPM_MAIN_CLASS=app.cpm.Main ./image/bin/claude-project-manager"
echo ""
echo "If macOS refuses to run the extracted binaries with a Gatekeeper"
echo "'unidentified developer' / quarantine error (this can happen when the"
echo "archive was transferred via a browser download or AirDrop, not"
echo "scp/USB), clear the quarantine attribute:"
echo "  xattr -cr ./image"
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/package-runtime-image.sh
```

- [ ] **Step 3: Run it and verify the build succeeds**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/package-runtime-image.sh
```

Expected: the script prints `==> Building runtime image ...`, then Gradle's
own `runtimeImage` output, then `==> Archiving build/image into
.../build/dist/cpm-image-macos-x86_64-<sha>.tar.gz`, then `==> Done: ...`
followed by the copy/run instructions block (mentioning both the
`CPM_MAIN_CLASS` override and the `xattr -cr` fallback). Exit code `0`.

- [ ] **Step 4: Verify the archive contents and identify the exact filename**

```bash
ls -la build/dist/
archive="$(ls build/dist/cpm-image-macos-x86_64-*.tar.gz | head -1)"
echo "$archive"
tar -tzf "$archive" | head -20
```

Expected: exactly one matching archive exists; the listing includes
`image/bin/claude-project-manager`, `image/runtime/bin/java`,
`image/lib/macos-x86_64/libghostty.dylib`, and
`image/lib/macos-arm64/libghostty.dylib` (both architectures' native libs
ship in every image per `docs/runtime-image.md`, even though the JVM
itself is single-arch).

- [ ] **Step 5: Verify the archive runs standalone, outside the repo, mimicking another machine**

```bash
rm -rf /tmp/cpm-package-test && mkdir -p /tmp/cpm-package-test
cp "$archive" /tmp/cpm-package-test/
(
  cd /tmp/cpm-package-test
  tar xzf "$(basename "$archive")"
  env -i PATH=/usr/bin:/bin ./image/bin/claude-project-manager &
  pid=$!
  sleep 5
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null
)
pgrep -f claude-project-manager && echo "FAIL: process still running" || echo "OK: no zombie process"
```

Expected: the app launches (a window appears — this repeats the existing
manual-verification pattern in `docs/runtime-image.md`, since there is no
headless test path for this JavaFX app per `README.md`'s `-PheadlessTest`
caveat) with `JAVA_HOME` unset and `PATH` reduced to `/usr/bin:/bin`, and
`kill` terminates it cleanly with no zombie process left behind (matching
the exact check already done for `runtimeImage` itself in
`docs/runtime-image.md`, "What was verified").

- [ ] **Step 6: Clean up the test artifacts**

```bash
rm -rf /tmp/cpm-package-test
```

- [ ] **Step 7: Commit**

```bash
git add scripts/package-runtime-image.sh
git commit -m "$(cat <<'EOF'
Add scripts/package-runtime-image.sh

Tars the existing jlink runtimeImage output into an
architecture-labeled archive so it can be copied to another Mac and
run without Gradle/JAVA_HOME/this repo present. Deliberately separate
from the reserved appImage/macApp/dmg Gradle tasks (Stage 3-6, still
out of scope).
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** script location/style (Task 1 Step 1), architecture
  detection and labeling (Step 1's `case` block), git-sha naming and dirty
  tree warning (Step 1), `build/dist/` output (Step 1), printed
  copy/run/`CPM_MAIN_CLASS`/`xattr -cr` instructions (Step 1), and the
  "simulate another machine" test methodology from the spec's Testing
  section (Steps 3-5) are all covered. No spec requirement without a task.
- **Placeholder scan:** no TBD/TODO; every step has literal commands and
  expected output; the script is given in full, not summarized.
- **Type/naming consistency:** archive filename pattern
  (`cpm-image-macos-<arch>-<sha>.tar.gz`) is identical between the spec,
  the script body, and the verification steps. `image_arch` values
  (`x86_64`/`arm64`) match `uname -m`'s own output so no translation table
  is needed beyond the `case` statement already shown.
