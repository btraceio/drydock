# Cross-Architecture jlink Runtime Image Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a single machine (either macOS architecture) produce working jlink runtime images for BOTH macOS x86_64 and arm64 in one pass, by cross-linking the non-host architecture using its downloaded jmods + explicitly-classified JavaFX jars.

**Architecture:** A new standalone script downloads and checksum-verifies the non-host architecture's JDK jmods bundle. The existing `runtimeImage` Gradle task's image-assembly logic is extracted into a shared, arch-parameterized function so it isn't duplicated. A new, opt-in `runtimeImageAllArches` task calls that function twice — once with the host's existing (implicit-resolution) inputs, once with the cross-linked inputs — and verifies the cross-linked JVM's Mach-O architecture tag via `file(1)`. `scripts/package-runtime-image.sh` gets an `--all-arches` flag to drive the new task and tar both outputs.

**Tech Stack:** Kotlin (Gradle build script), bash, `curl`, `shasum`, `tar`, `file`, `jlink`, the existing Gradle build.

## Global Constraints

- Do not change the behavior, output path, or module-resolution strategy of the existing `runtimeImage` task. Its refactor into a shared function must be behavior-preserving — verify old vs. new `build/image/` output is equivalent.
- Do not touch `appImage`/`macApp`/`dmg` (root `build.gradle.kts` or `app/build.gradle.kts`) — they remain host-arch only and out of scope.
- Do not change `./gradlew run`, `./gradlew test`, or any other part of the default dev loop. Cross-arch building is strictly opt-in (`runtimeImageAllArches`, a new task nobody calls implicitly).
- Do not attempt to execute the cross-linked (non-host) architecture's binary. Verify only its Mach-O architecture tag via `file(1)`, mirroring the existing pattern in `scripts/build-native-host.sh` (`file -b ... | grep -oE 'x86_64|arm64'`). Real execution verification is explicitly deferred to CI.
- Pinned artifacts (must match exactly, from `docs/superpowers/specs/2026-07-20-cross-arch-runtime-image-design.md`, independently verified against each asset's published `.sha256.txt` sidecar):

  | Target arch | URL | SHA-256 |
  |---|---|---|
  | `macos-arm64` | `https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_aarch64_mac_hotspot_26.0.1_8.tar.gz` | `e76d5df4bf1e1568b1de1332b1784e815746288309c79b08c72ae48545663484` |
  | `macos-x86_64` | `https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_x64_mac_hotspot_26.0.1_8.tar.gz` | `c323f7f94018e91a472273e9986e98890b0ca92dfd9bbdc9960c3edc6627b6b7` |

- Match existing `scripts/*.sh` style exactly: `#!/usr/bin/env bash`, `set -euo pipefail`, a `fail() { echo "error: $*" >&2; exit 1; }` helper, `==>` progress messages to stderr. Every failure path must name exactly what went wrong.
- Bash scripts in this repo must not rely on bash 4+ features (`mapfile`, associative arrays) — macOS ships bash 3.2 at `/usr/bin/bash` by default, and no existing script requires a newer one.
- The architecture-name mapping (`os.arch`/`uname -m` → `macos-x86_64`/`macos-arm64`) must match `GhosttyNativeLibrary.detectArchDirectoryName()` (`app/src/main/java/app/cpm/terminal/ghostty/GhosttyNativeLibrary.java`) exactly: `x86_64`/`amd64` → `macos-x86_64`; `aarch64`/`arm64` → `macos-arm64`.

---

### Task 1: `scripts/download-cross-jmods.sh`

**Files:**
- Create: `scripts/download-cross-jmods.sh`

**Interfaces:**
- Consumes: nothing from this plan (standalone). Reads `$CPM_CROSS_JMODS_ARCHIVE` (optional env override).
- Produces: CLI contract for Task 2: `scripts/download-cross-jmods.sh <macos-x86_64|macos-arm64> <output-dir>` — on success, prints exactly one line to **stdout** (all other output goes to stderr): the absolute path to the extracted `jmods/` directory. Exit 0 on success; non-zero with a `error: ...` message on any failure. Idempotent — a second call with the same `<output-dir>` is a no-op that reprints the same path.

- [ ] **Step 1: Write the script**

Create `scripts/download-cross-jmods.sh` with this exact content:

```bash
#!/usr/bin/env bash
#
# Downloads and checksum-verifies the Eclipse Temurin JDK 26.0.1 "jmods"
# bundle for a macOS architecture, for use as a jlink --module-path entry
# when cross-linking a runtime image for an architecture other than the
# one the build is running on (see docs/superpowers/specs/
# 2026-07-20-cross-arch-runtime-image-design.md).
#
# Pinned to build 26.0.1+8, matching this project's documented JDK 26.0.1
# toolchain (README.md prerequisites table) exactly. If that toolchain
# version ever changes, the URLs/checksums below must be updated together.
#
# Usage:
#   scripts/download-cross-jmods.sh <macos-x86_64|macos-arm64> <output-dir>
#
# Prints the resolved jmods/ directory path to stdout on success (all
# other output goes to stderr). Idempotent: if <output-dir> already
# contains an extracted jmods/ directory with .jmod files in it, this is
# a no-op that just reprints the path.
#
# Environment overrides:
#   CPM_CROSS_JMODS_ARCHIVE   Use this local .tar.gz path instead of
#                             downloading. Still checksum-verified against
#                             the same pinned hash as the network path --
#                             this overrides the transport, not the
#                             integrity check.
set -euo pipefail

fail() {
    echo "error: $*" >&2
    exit 1
}

[[ $# -eq 2 ]] || fail "usage: $0 <macos-x86_64|macos-arm64> <output-dir>"

arch="$1"
output_dir="$2"

case "$arch" in
    macos-arm64)
        url="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_aarch64_mac_hotspot_26.0.1_8.tar.gz"
        expected_sha256="e76d5df4bf1e1568b1de1332b1784e815746288309c79b08c72ae48545663484"
        ;;
    macos-x86_64)
        url="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.1%2B8/OpenJDK26U-jmods_x64_mac_hotspot_26.0.1_8.tar.gz"
        expected_sha256="c323f7f94018e91a472273e9986e98890b0ca92dfd9bbdc9960c3edc6627b6b7"
        ;;
    *)
        fail "unrecognized architecture '$arch' (expected macos-x86_64 or macos-arm64)."
        ;;
esac

existing_jmods_dir=""
if [[ -d "$output_dir" ]]; then
    existing_jmods_dir="$(find "$output_dir" -type f -name '*.jmod' -exec dirname {} \; 2>/dev/null | sort -u | head -1 || true)"
fi
if [[ -n "$existing_jmods_dir" ]]; then
    echo "==> Already downloaded and extracted: $existing_jmods_dir" >&2
    echo "$existing_jmods_dir"
    exit 0
fi

mkdir -p "$output_dir"

tmp_dir=""
cleanup() {
    [[ -n "$tmp_dir" && -d "$tmp_dir" ]] && rm -rf "$tmp_dir"
}
trap cleanup EXIT

if [[ -n "${CPM_CROSS_JMODS_ARCHIVE:-}" ]]; then
    [[ -f "$CPM_CROSS_JMODS_ARCHIVE" ]] || fail "CPM_CROSS_JMODS_ARCHIVE='$CPM_CROSS_JMODS_ARCHIVE' does not exist."
    archive_path="$CPM_CROSS_JMODS_ARCHIVE"
    echo "==> Using local archive: $archive_path" >&2
else
    tmp_dir="$(mktemp -d)"
    archive_path="$tmp_dir/jmods.tar.gz"
    echo "==> Downloading $url" >&2
    curl -fsSL -o "$archive_path" "$url" || fail "download of $url failed."
fi

actual_sha256="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
[[ "$actual_sha256" == "$expected_sha256" ]] \
    || fail "checksum mismatch for $archive_path: expected $expected_sha256, got $actual_sha256."

echo "==> Extracting to $output_dir" >&2
tar -xzf "$archive_path" -C "$output_dir"

# The archive's top-level directory is named e.g. jdk-26.0.1+8-jmods/, not
# "jmods" -- .jmod files sit directly inside it. Locate it by content
# (a directory containing .jmod files) rather than assuming a name.
jmods_dirs="$(find "$output_dir" -type f -name '*.jmod' -exec dirname {} \; | sort -u)"
jmods_dir_count="$(echo "$jmods_dirs" | grep -c . || true)"
[[ "$jmods_dir_count" -eq 1 ]] \
    || fail "expected exactly one directory of .jmod files under $output_dir after extraction, found $jmods_dir_count: $jmods_dirs"

echo "$jmods_dirs"
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/download-cross-jmods.sh
```

- [ ] **Step 3: Verify usage/argument validation**

```bash
./scripts/download-cross-jmods.sh
echo "exit code: $?"
./scripts/download-cross-jmods.sh macos-x86_64
echo "exit code: $?"
./scripts/download-cross-jmods.sh bogus-arch /tmp/cpm-jmods-test
echo "exit code: $?"
```

Expected: all three fail (non-zero exit) with an `error: usage: ...` or `error: unrecognized architecture ...` message on stderr; none attempt a download.

- [ ] **Step 4: Verify a real download + extraction for the non-host architecture**

Determine the non-host architecture first (if this machine is Intel, test `macos-arm64`; if Apple Silicon, test `macos-x86_64` — either is fine, this step just needs an architecture whose jmods this machine doesn't already have from its own toolchain):

```bash
rm -rf /tmp/cpm-jmods-test
./scripts/download-cross-jmods.sh macos-arm64 /tmp/cpm-jmods-test
```

Expected: prints `==> Downloading ...`, `==> Extracting to ...` (stderr), then exactly one line on stdout — an absolute path ending in `.../jmods`. Verify:

```bash
jmods_dir="$(./scripts/download-cross-jmods.sh macos-arm64 /tmp/cpm-jmods-test 2>/dev/null)"
echo "$jmods_dir"
ls "$jmods_dir" | grep -c '\.jmod$'
```

Expected: the second run (idempotency check) exits fast without downloading (confirm by checking there's no new `==> Downloading` on stderr — redirect stderr to a file and grep it if needed), the printed path is a directory, and it contains a nonzero count of `.jmod` files (dozens — one per JDK module).

- [ ] **Step 5: Verify checksum mismatch is caught**

```bash
mkdir -p /tmp/cpm-jmods-bad
echo "not a real jmods archive" > /tmp/cpm-jmods-bad/fake.tar.gz
CPM_CROSS_JMODS_ARCHIVE=/tmp/cpm-jmods-bad/fake.tar.gz ./scripts/download-cross-jmods.sh macos-arm64 /tmp/cpm-jmods-bad-out
echo "exit code: $?"
```

Expected: fails with `error: checksum mismatch for /tmp/cpm-jmods-bad/fake.tar.gz: expected e76d5df4..., got <some other hash>.` — proving the override path is still checksum-verified, not trusted blindly.

- [ ] **Step 6: Clean up test artifacts**

```bash
rm -rf /tmp/cpm-jmods-test /tmp/cpm-jmods-bad /tmp/cpm-jmods-bad-out
```

- [ ] **Step 7: Commit**

```bash
git add scripts/download-cross-jmods.sh
git commit -m "$(cat <<'EOF'
Add scripts/download-cross-jmods.sh

Downloads and checksum-verifies the Eclipse Temurin JDK 26.0.1 jmods
bundle for a given macOS architecture, pinned to build 26.0.1+8 and
verified against each asset's published .sha256.txt. Standalone
prerequisite for cross-linking jlink runtime images (see
docs/superpowers/specs/2026-07-20-cross-arch-runtime-image-design.md);
not yet wired into any Gradle task.
EOF
)"
```

---

### Task 2: `app/build.gradle.kts` — shared assembly function + `runtimeImageAllArches`

**Files:**
- Modify: `app/build.gradle.kts:270-457` (the `runtimeImage` task's `doLast` block gets extracted into a new shared function; a new `runtimeImageAllArches` task is added after it)
- Modify: `build.gradle.kts` (root) — add an alias task, matching the existing `runtimeImage`/`ffmSmokeTest` alias pattern

**Interfaces:**
- Consumes: `scripts/download-cross-jmods.sh <arch> <output-dir>` (Task 1) — invoked via `project.exec`, its single stdout line is the resolved jmods directory path.
- Produces: `build/image-macos-x86_64/` and `build/image-macos-arm64/` (Task 3 tars these). Task name `:app:runtimeImageAllArches` and root alias `runtimeImageAllArches`.

- [ ] **Step 1: Extract the shared `assembleRuntimeImage` function**

In `app/build.gradle.kts`, add this function immediately before the existing `runtimeImage` task registration (i.e. insert it right before line 291's `tasks.register("runtimeImage") {`):

```kotlin
/**
 * Assembles one architecture's self-contained jlink runtime image at
 * [outputDir]: jlinks the JDK + JavaFX module graph, copies the app jar +
 * runtime classpath, copies both architectures' native libraries, bundles
 * the dock icon, and generates the launcher script plus a thin .app
 * wrapper. Extracted from the original single-architecture runtimeImage
 * task body so both it and the cross-arch runtimeImageAllArches task
 * below share one implementation (see docs/superpowers/specs/
 * 2026-07-20-cross-arch-runtime-image-design.md).
 *
 * [modulePathEntries] is the full jlink --module-path. For the host's own
 * architecture this is just the (implicitly platform-matched) JavaFX
 * module jars -- java.*/jdk.* modules still resolve implicitly from
 * [jlinkExe]'s own JDK, exactly as before this function existed. For a
 * cross-linked architecture it additionally includes a downloaded
 * jmods/ directory for that architecture (scripts/download-cross-jmods.sh)
 * -- jlink accepts a directory of .jmod files as a --module-path entry
 * the same way $JAVA_HOME/jmods normally works.
 *
 * If [expectedMachOArch] is non-null, the produced runtime/bin/java is
 * verified with file(1) to report that architecture token, hard-failing
 * otherwise -- mirrors buildGhosttyNative/buildNativeHost's existing
 * file(1)-based acceptance gate. This is the only verification possible
 * for a cross-linked, non-host architecture: actually executing it is not
 * possible on this machine and is deferred to CI.
 */
fun assembleRuntimeImage(
    outputDir: File,
    archLabel: String,
    jlinkExe: File,
    modulePathEntries: List<File>,
    appJarFile: File,
    classpathJars: List<File>,
    nativeBuildDir: File,
    expectedMachOArch: String?,
) {
    project.delete(outputDir)
    outputDir.mkdirs()

    // 1. jlink the JDK + JavaFX module graph.
    val modulePath = modulePathEntries.joinToString(File.pathSeparator) { it.absolutePath }
    val runtimeOut = File(outputDir, "runtime")

    @Suppress("DEPRECATION")
    project.exec {
        commandLine(
            jlinkExe.absolutePath,
            "--module-path", modulePath,
            "--add-modules",
            "java.base,java.desktop,java.net.http,java.xml,jdk.jfr,jdk.unsupported," +
                "javafx.base,javafx.controls,javafx.graphics",
            "--output", runtimeOut.absolutePath,
            "--no-header-files",
            "--no-man-pages",
            "--strip-debug",
            "--compress", "zip-6"
        )
    }

    if (expectedMachOArch != null) {
        val javaBin = File(runtimeOut, "bin/java")
        val fileOutput = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        project.exec {
            commandLine("file", "-b", javaBin.absolutePath)
            standardOutput = fileOutput
        }
        val description = fileOutput.toString(Charsets.UTF_8.name())
        if (!description.contains(expectedMachOArch)) {
            throw org.gradle.api.GradleException(
                "$javaBin is not tagged as $expectedMachOArch (file(1) reported: " +
                    "${description.trim()}); cross-linked jlink output for $archLabel is wrong."
            )
        }
    }

    // 2. Copy the application jar + every runtime-classpath jar onto a
    // plain classpath directory (app/), since the application is not yet
    // modular.
    val appLibDir = File(outputDir, "app")
    appLibDir.mkdirs()
    project.copy {
        from(appJarFile)
        from(classpathJars)
        into(appLibDir)
    }

    // 3. Copy libghostty + the AppKit host shim for BOTH architectures
    // into lib/<arch>/ -- both architectures' native libs ship in every
    // image regardless of which JVM architecture the image itself was
    // linked for.
    val libDir = File(outputDir, "lib")
    for (arch in listOf("macos-x86_64", "macos-arm64")) {
        val src = File(nativeBuildDir, arch)
        val dst = File(libDir, arch)
        dst.mkdirs()
        for (name in listOf("libghostty.dylib", "libcpmterminalhost.dylib")) {
            val source = File(src, name)
            if (source.isFile) {
                source.copyTo(File(dst, name), overwrite = true)
            } else {
                throw org.gradle.api.GradleException(
                    "Missing $source -- run './gradlew buildGhosttyNative buildNativeHost' first."
                )
            }
        }
    }

    // 3b. Bundle the dock icon.
    val iconSource = rootProject.file("assets/app-icon.icns")
    if (iconSource.isFile) {
        iconSource.copyTo(File(libDir, "app-icon.icns"), overwrite = true)
    }

    // 4. Generate the launcher.
    val binDir = File(outputDir, "bin")
    binDir.mkdirs()
    val launcher = File(binDir, "claude-project-manager")
    launcher.writeText(runtimeImageLauncherScript())
    launcher.setExecutable(true, false)

    // 5. Wrap the launcher in a minimal .app bundle.
    val appBundle = File(outputDir, "Claude Project Manager.app")
    val contentsDir = File(appBundle, "Contents")
    val macosDir = File(contentsDir, "MacOS").apply { mkdirs() }
    val resourcesDir = File(contentsDir, "Resources").apply { mkdirs() }
    if (iconSource.isFile) {
        iconSource.copyTo(File(resourcesDir, "app-icon.icns"), overwrite = true)
    }
    File(contentsDir, "Info.plist").writeText(appBundleInfoPlist())
    val bundleLauncher = File(macosDir, "claude-project-manager")
    bundleLauncher.writeText(
        """
        #!/bin/bash
        # Thin trampoline: the bundle lives inside the runtime image, so
        # the real launcher is three levels up. exec keeps the pid (and
        # therefore the Dock entry) on the java process's ancestry.
        DIR="$(dirname "${'$'}{BASH_SOURCE[0]}")"
        exec "${'$'}DIR/../../../bin/claude-project-manager" "${'$'}@"
        """.trimIndent() + "\n"
    )
    bundleLauncher.setExecutable(true, false)
}
```

- [ ] **Step 2: Replace the existing `runtimeImage` task's `doLast` body to call the shared function**

Replace the entire `doLast { ... }` block of the existing `runtimeImage` task (currently `app/build.gradle.kts` lines 311-456, from `doLast {` through its matching closing `}` right before the task registration's own closing `}`) with:

```kotlin
    doLast {
        val javaHome = javaLauncherProvider.get().metadata.installationPath.asFile
        val jlinkExe = File(javaHome, "bin/jlink")
        val fxJars = runtimeClasspathFiles.get().files.filter { it.name.startsWith("javafx-") }
        require(fxJars.size == 3) {
            "Expected exactly 3 javafx-*.jar files (base/controls/graphics) on the runtime " +
                "classpath, found: $fxJars"
        }
        assembleRuntimeImage(
            outputDir = runtimeImageDir.get().asFile,
            archLabel = "host",
            jlinkExe = jlinkExe,
            modulePathEntries = fxJars,
            appJarFile = jarTaskProvider.get().archiveFile.get().asFile,
            classpathJars = runtimeClasspathFiles.get().files.filter { it.name.endsWith(".jar") },
            nativeBuildDir = nativeBuildDir.get().asFile,
            expectedMachOArch = null,
        )
    }
```

Leave everything above the `doLast` block (the task registration header, `dependsOn`, `inputs`/`outputs` declarations, and the `val javaHome`/`jlinkExe`/etc. declarations that were previously *inside* `doLast` but are now moved into it as shown) — do not change `runtimeImageDir`, `jarTaskProvider`, `runtimeClasspathFiles`, or `nativeBuildDir` (these stay exactly as declared today, just after the `outputs.dir(...)` line and before the new `doLast`).

- [ ] **Step 3: Verify the refactor preserved behavior**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runtimeImage
find build/image -type f | sort > /tmp/cpm-image-after-refactor.txt
wc -l /tmp/cpm-image-after-refactor.txt
build/image/bin/claude-project-manager &
pid=$!
sleep 5
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null
```

Expected: the build succeeds exactly as it did before this change (same task, same `build/image/` output path), the file listing is non-empty and includes `bin/claude-project-manager`, `runtime/bin/java`, `app/app.jar` (or similarly named app jar), `lib/macos-x86_64/libghostty.dylib`, and `lib/macos-arm64/libghostty.dylib`; the launcher starts a window and `kill` stops it cleanly (same acceptance criteria as `docs/runtime-image.md`'s original Task 8 verification — this refactor must not regress it).

- [ ] **Step 4: Add the `runtimeImageAllArches` task**

Add this immediately after the (now-refactored) `runtimeImage` task's closing `}` in `app/build.gradle.kts` (i.e. right after what is now the end of Step 2's block, before the existing `appImage` task/comment):

```kotlin
// Cross-arch jlink runtime images: see docs/superpowers/specs/
// 2026-07-20-cross-arch-runtime-image-design.md. Opt-in -- does not
// affect runtimeImage/appImage/run in any way. Builds BOTH macOS
// architectures in one pass regardless of which one this machine
// actually is, downloading the non-host architecture's jmods via
// scripts/download-cross-jmods.sh and resolving its JavaFX jars via an
// explicit classifier (rather than the host-inferred one the javafx {}
// block at the top of this file uses).
val crossJdkDir = rootProject.layout.buildDirectory.dir("cross-jdk")

tasks.register("runtimeImageAllArches") {
    group = "distribution"
    description = "Cross-links jlink runtime images for BOTH macOS architectures (x86_64 and " +
        "arm64) in one pass at build/image-macos-x86_64 and build/image-macos-arm64. Does not " +
        "execute the foreign-architecture binary -- only its Mach-O architecture tag is " +
        "verified; real execution verification is CI's job."

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))
    dependsOn(tasks.named("jar"))

    val toolchainService = project.extensions.getByType(JavaToolchainService::class.java)
    val javaLauncherProvider = toolchainService.launcherFor(java.toolchain)
    val jarTaskProvider = tasks.named<Jar>("jar")
    val runtimeClasspathFiles = configurations.named("runtimeClasspath")
    val nativeBuildDir = rootProject.layout.buildDirectory.dir("native")
    val scriptsDir = rootProject.file("scripts")

    inputs.file(jarTaskProvider.flatMap { it.archiveFile })
    inputs.files(runtimeClasspathFiles)
    inputs.dir(nativeBuildDir)
    inputs.property("javaLauncher", javaLauncherProvider.map { it.metadata.installationPath.asFile.absolutePath })
    outputs.dir(rootProject.layout.buildDirectory.dir("image-macos-x86_64"))
    outputs.dir(rootProject.layout.buildDirectory.dir("image-macos-arm64"))

    doLast {
        val hostOsArch = System.getProperty("os.arch", "").lowercase()
        val hostArchLabel = when (hostOsArch) {
            "x86_64", "amd64" -> "macos-x86_64"
            "aarch64", "arm64" -> "macos-arm64"
            else -> throw org.gradle.api.GradleException(
                "Unrecognized host os.arch '$hostOsArch' (expected x86_64/amd64 or aarch64/arm64)."
            )
        }
        val allArches = listOf("macos-x86_64", "macos-arm64")
        val crossArch = allArches.first { it != hostArchLabel }

        val javaHome = javaLauncherProvider.get().metadata.installationPath.asFile
        val jlinkExe = File(javaHome, "bin/jlink")
        val appJarFile = jarTaskProvider.get().archiveFile.get().asFile
        val classpathJars = runtimeClasspathFiles.get().files.filter { it.name.endsWith(".jar") }
        val nativeOut = nativeBuildDir.get().asFile

        // Host arch: identical inputs to the runtimeImage task above
        // (implicit jrt: module resolution, host-classified FX jars).
        val hostFxJars = runtimeClasspathFiles.get().files.filter { it.name.startsWith("javafx-") }
        require(hostFxJars.size == 3) {
            "Expected exactly 3 javafx-*.jar files (base/controls/graphics) on the runtime " +
                "classpath, found: $hostFxJars"
        }
        assembleRuntimeImage(
            outputDir = rootProject.layout.buildDirectory.dir("image-$hostArchLabel").get().asFile,
            archLabel = hostArchLabel,
            jlinkExe = jlinkExe,
            modulePathEntries = hostFxJars,
            appJarFile = appJarFile,
            classpathJars = classpathJars,
            nativeBuildDir = nativeOut,
            expectedMachOArch = null,
        )

        // Cross arch: explicit jmods + explicit-classifier FX jars.
        val jmodsOutputDir = File(crossJdkDir.get().asFile, crossArch)
        val downloadScript = File(scriptsDir, "download-cross-jmods.sh")
        val jmodsPathOutput = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        project.exec {
            commandLine(downloadScript.absolutePath, crossArch, jmodsOutputDir.absolutePath)
            standardOutput = jmodsPathOutput
        }
        val jmodsDir = File(jmodsPathOutput.toString(Charsets.UTF_8.name()).trim())
        require(jmodsDir.isDirectory) {
            "scripts/download-cross-jmods.sh did not print a valid jmods directory path, got: '$jmodsDir'"
        }

        val fxClassifier = when (crossArch) {
            "macos-x86_64" -> "mac"
            "macos-arm64" -> "mac-aarch64"
            else -> error("unreachable: crossArch was $crossArch")
        }
        val crossFxDeps = listOf("javafx-base", "javafx-controls", "javafx-graphics").map {
            project.dependencies.create("org.openjfx:$it:26:$fxClassifier")
        }
        val crossFxJars = project.configurations.detachedConfiguration(*crossFxDeps.toTypedArray()).resolve().toList()

        val crossExpectedMachOArch = when (crossArch) {
            "macos-x86_64" -> "x86_64"
            "macos-arm64" -> "arm64"
            else -> error("unreachable: crossArch was $crossArch")
        }

        assembleRuntimeImage(
            outputDir = rootProject.layout.buildDirectory.dir("image-$crossArch").get().asFile,
            archLabel = crossArch,
            jlinkExe = jlinkExe,
            modulePathEntries = listOf(jmodsDir) + crossFxJars,
            appJarFile = appJarFile,
            classpathJars = classpathJars,
            nativeBuildDir = nativeOut,
            expectedMachOArch = crossExpectedMachOArch,
        )
    }
}
```

- [ ] **Step 5: Add the root-level alias task**

In root `build.gradle.kts`, find the existing `runtimeImage` alias task (search for `tasks.register("runtimeImage")`). Immediately after its closing `}`, add:

```kotlin
tasks.register("runtimeImageAllArches") {
    group = "distribution"
    description = "Alias for :app:runtimeImageAllArches (cross-links both macOS architectures in one pass)."
    dependsOn(":app:runtimeImageAllArches")
}
```

- [ ] **Step 6: Run `runtimeImageAllArches` and verify both architectures**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runtimeImageAllArches
file -b build/image-macos-x86_64/runtime/bin/java
file -b build/image-macos-arm64/runtime/bin/java
```

Expected: the task succeeds (no `GradleException` from the `file(1)` acceptance gate); the first `file -b` reports `x86_64` (or contains that token; run this on the Intel dev machine, so this one is the *host* build), the second reports `arm64` (the *cross-linked* build) — two genuinely different architectures, which is the concrete regression this whole plan exists to fix (today, both would be `x86_64`, or the arm64 one wouldn't exist at all).

```bash
ls build/image-macos-x86_64/bin/claude-project-manager build/image-macos-arm64/bin/claude-project-manager
build/image-macos-x86_64/bin/claude-project-manager &
pid=$!
sleep 5
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null
```

Expected: both launchers exist and are executable; the host-arch (`x86_64`) one actually launches and is cleanly killable (same as Step 3's check) — the `arm64` one is **not** executed here (can't, wrong architecture on this machine) per this plan's Global Constraints.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts build.gradle.kts
git commit -m "$(cat <<'EOF'
Add runtimeImageAllArches: cross-link both macOS architectures

Extracts the existing runtimeImage task's image-assembly logic into
a shared assembleRuntimeImage function (behavior-preserving refactor,
verified against the pre-refactor build/image/ output), then adds a
new opt-in runtimeImageAllArches task that calls it once for the host
architecture (unchanged, implicit resolution) and once for the other
macOS architecture (explicit jmods via
scripts/download-cross-jmods.sh + an explicitly-classified JavaFX
dependency). The cross-linked output's JVM architecture is verified
via file(1); it is not executed. Does not affect runtimeImage,
appImage, or the default dev loop.
EOF
)"
```

---

### Task 3: `scripts/package-runtime-image.sh` — `--all-arches` flag

**Files:**
- Modify: `scripts/package-runtime-image.sh` (full rewrite of its body; header comment and overall style unchanged)

**Interfaces:**
- Consumes: `:app:runtimeImageAllArches`/root alias `runtimeImageAllArches` (Task 2), producing `build/image-macos-x86_64/` and `build/image-macos-arm64/`.
- Produces: no change to the deliverable naming pattern (`build/dist/cpm-image-macos-<arch>-<git-short-sha>.tar.gz`); now potentially two archives per invocation when `--all-arches` is passed.

- [ ] **Step 1: Replace the script content**

Replace the entire contents of `scripts/package-runtime-image.sh` with:

```bash
#!/usr/bin/env bash
#
# Packages the jlink runtime image into a tarball that can be copied to
# another Mac and run without Gradle, JAVA_HOME, or this repository
# present.
#
# Default (no flags): packages ONLY this machine's own architecture via
# ./gradlew runtimeImage (see docs/runtime-image.md) -- without
# --all-arches this script only ever produces an archive for the CURRENT
# machine's architecture. To get an archive for the other architecture,
# either run this script again on a Mac of that architecture, or use
# --all-arches below.
#
# --all-arches: uses ./gradlew runtimeImageAllArches (see
# docs/superpowers/specs/2026-07-20-cross-arch-runtime-image-design.md)
# to cross-link BOTH macOS architectures from this one machine in a
# single pass, producing two archives. The cross-linked (non-host)
# architecture's archive is verified only by its Mach-O architecture tag,
# never actually executed -- real execution verification on that
# hardware is CI's job, not this script's.
#
# This is deliberately separate from the ./gradlew appImage/macApp/dmg
# tasks, which are reserved no-ops for later .app/.dmg packaging work
# (plan section 23.3/23.4 Stages 3-6) -- this script does not implement,
# replace, or depend on any of them.
#
# Deliverable(s):
#   build/dist/cpm-image-macos-<arch>-<git-short-sha>.tar.gz
#
# Usage:
#   ./scripts/package-runtime-image.sh [--all-arches]
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

fail() {
    echo "error: $*" >&2
    exit 1
}

all_arches=false
case "${1:-}" in
    "") ;;
    --all-arches) all_arches=true ;;
    *) fail "usage: $0 [--all-arches]" ;;
esac

cd "$repo_root"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || fail "$repo_root is not a git checkout; cannot determine a build identifier."

if [[ -n "$(git status --porcelain)" ]]; then
    echo "warning: working tree has uncommitted changes -- the packaged image will include them, not just commit $(git rev-parse --short HEAD)." >&2
fi

git_sha="$(git rev-parse --short HEAD)"
dist_dir="$repo_root/build/dist"
mkdir -p "$dist_dir"

host_arch_uname="$(uname -m)"
case "$host_arch_uname" in
    x86_64) host_arch="x86_64" ;;
    arm64)  host_arch="arm64" ;;
    *) fail "unrecognized host architecture '$host_arch_uname' (expected x86_64 or arm64); refusing to guess a label." ;;
esac

# Packages one already-built image directory into
# build/dist/cpm-image-macos-<arch>-<sha>.tar.gz and prints its copy/run
# instructions. $1: image dir (e.g. build/image or
# build/image-macos-arm64). $2: arch label (x86_64 or arm64). $3: "host"
# or "cross" -- selects which closing note to print.
package_one() {
    local image_dir="$1" arch="$2" kind="$3"
    local archive_name="cpm-image-macos-${arch}-${git_sha}.tar.gz"
    local archive_path="$dist_dir/$archive_name"

    [[ -x "$image_dir/bin/claude-project-manager" ]] \
        || fail "$image_dir/bin/claude-project-manager not found or not executable; the Gradle task did not produce the expected launcher."

    echo "==> Archiving $image_dir into $archive_path"
    tar -C "$(dirname "$image_dir")" -czf "$archive_path" "$(basename "$image_dir")"

    [[ -s "$archive_path" ]] || fail "$archive_path was not created or is empty."

    echo "==> Done: $archive_path"
    echo ""
    echo "On the target Mac (macOS $arch):"
    echo "  tar xzf $archive_name"
    echo "  ./$(basename "$image_dir")/bin/claude-project-manager"
    echo ""
    echo "The image's default launch target is the real application (app.cpm.Main)."
    echo "To run the Task 5 terminal spike instead (escape hatch for testing):"
    echo "  CPM_MAIN_CLASS=app.cpm.terminal.Gate0cSpikeLauncher ./$(basename "$image_dir")/bin/claude-project-manager"
    echo ""
    if [[ "$kind" == "cross" ]]; then
        echo "This image was CROSS-LINKED on a $host_arch host for $arch -- it was never"
        echo "actually executed (only its Mach-O architecture tag was verified). Real"
        echo "execution verification on $arch hardware is expected to happen in CI once"
        echo "that is wired up, not by this script."
    else
        echo "This archive runs ONLY on macOS $arch -- jlink builds a runtime for the"
        echo "architecture it runs on, not a cross-build by default. Use --all-arches to"
        echo "cross-link the other architecture from this machine instead."
    fi
    echo ""
    echo "If macOS refuses to run the extracted binaries with a Gatekeeper"
    echo "'unidentified developer' / quarantine error (this can happen when the"
    echo "archive was transferred via a browser download or AirDrop, not"
    echo "scp/USB), clear the quarantine attribute:"
    echo "  xattr -cr ./$(basename "$image_dir")"
    echo ""
}

if [[ "$all_arches" == true ]]; then
    echo "==> Building both architectures' runtime images (./gradlew runtimeImageAllArches)"
    "$repo_root/gradlew" runtimeImageAllArches

    cross_arch="x86_64"
    [[ "$host_arch" == "x86_64" ]] && cross_arch="arm64"

    package_one "$repo_root/build/image-macos-${host_arch}" "$host_arch" "host"
    package_one "$repo_root/build/image-macos-${cross_arch}" "$cross_arch" "cross"
else
    echo "==> Building runtime image (./gradlew runtimeImage)"
    "$repo_root/gradlew" runtimeImage

    package_one "$repo_root/build/image" "$host_arch" "host"
fi
```

- [ ] **Step 2: Verify the default (no-flag) path still works exactly as before**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/package-runtime-image.sh
ls build/dist/
```

Expected: exactly one `cpm-image-macos-x86_64-<sha>.tar.gz` is produced (same naming pattern as before this change), and the printed instructions include the "Use --all-arches to cross-link..." line (new) but are otherwise equivalent in content to before.

- [ ] **Step 3: Verify `--all-arches` produces two archives**

```bash
./scripts/package-runtime-image.sh --all-arches
ls build/dist/
```

Expected: two archives now exist:
`cpm-image-macos-x86_64-<sha>.tar.gz` and `cpm-image-macos-arm64-<sha>.tar.gz`. The x86_64 one's printed instructions say it runs only on macOS x86_64 as usual; the arm64 one's printed instructions explicitly say it was cross-linked and not executed, deferring real verification to CI.

- [ ] **Step 4: Verify the host-arch archive from the `--all-arches` run still launches**

```bash
rm -rf /tmp/cpm-cross-package-test && mkdir -p /tmp/cpm-cross-package-test
cp build/dist/cpm-image-macos-x86_64-*.tar.gz /tmp/cpm-cross-package-test/
(
  cd /tmp/cpm-cross-package-test
  tar xzf cpm-image-macos-x86_64-*.tar.gz
  env -i PATH=/usr/bin:/bin ./image-macos-x86_64/bin/claude-project-manager &
  pid=$!
  sleep 5
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null
)
pgrep -f claude-project-manager && echo "FAIL: process still running" || echo "OK: no zombie process"
rm -rf /tmp/cpm-cross-package-test
```

Expected: launches cleanly outside the repo, `JAVA_HOME` unset, `PATH` reduced — same acceptance bar as the original single-arch packaging script's own verification.

- [ ] **Step 5: Verify usage validation**

```bash
./scripts/package-runtime-image.sh --bogus-flag
echo "exit code: $?"
```

Expected: fails immediately with `error: usage: ./scripts/package-runtime-image.sh [--all-arches]`, before attempting any build.

- [ ] **Step 6: Commit**

```bash
git add scripts/package-runtime-image.sh
git commit -m "$(cat <<'EOF'
Add --all-arches flag to package-runtime-image.sh

Default behavior (no flag) is unchanged: builds and packages only
this machine's own architecture. --all-arches instead drives the new
runtimeImageAllArches Gradle task and packages both resulting image
directories into separate, correctly-labeled archives, with distinct
printed guidance for the cross-linked (unexecuted, CI-verified-later)
architecture vs. the host one.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** pinned URL/checksum table (Global Constraints + Task 1 script body), idempotent download with env override (Task 1), behavior-preserving refactor of `runtimeImage` (Task 2 Steps 1-3), new opt-in `runtimeImageAllArches` producing both `build/image-macos-x86_64`/`build/image-macos-arm64` with `file(1)` architecture verification (Task 2 Steps 4-6), root alias task (Task 2 Step 5), and `--all-arches` packaging with distinct host/cross guidance (Task 3) are all covered. No spec requirement without a task. Deliberately not covered (per spec's Non-goals): extending `appImage`/`macApp`/`dmg`, actual arm64 execution, CI wiring.
- **Placeholder scan:** no TBD/TODO; every step has literal, complete code (both the bash script and the full Kotlin function/task bodies) and exact verification commands with expected output.
- **Type/naming consistency:** `assembleRuntimeImage`'s parameter names and the values passed at both call sites (Task 2 Steps 2 and 4) match exactly. The `macos-x86_64`/`macos-arm64` labels and the `x86_64`/`arm64` `file(1)` tokens are used consistently across Task 1 (script arg), Task 2 (Kotlin `when` branches, `outputs.dir` paths), and Task 3 (shell `case`/variable names) — verified against `GhosttyNativeLibrary.detectArchDirectoryName()`'s existing mapping (Global Constraints) rather than invented independently.
