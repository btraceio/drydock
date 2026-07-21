# Cross-Arch Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement cross-architecture jlink runtime image support (both macOS architectures from one host, per `docs/superpowers/specs/2026-07-20-cross-arch-port-design.md`) against the current `cpm.packaging` convention plugin / typed-task architecture on `master`, superseding the now-obsolete `app/build.gradle.kts`-based design.

**Architecture:** A stable `jmods` symlink added to the already-carried-forward `scripts/download-cross-jmods.sh`; a new typed `DownloadCrossJmodsTask` wrapping it; three new optional inputs on the existing `RuntimeImageTask` (empty/absent by default, zero behavior change for the existing `runtimeImage` registration); two new per-architecture `RuntimeImageTask` instances plus a lifecycle task, wired in `cpm.packaging.gradle.kts`; one new root alias task.

**Tech Stack:** Kotlin (Gradle typed tasks + Kotlin DSL build scripts), bash, `jlink`, `file`, the existing Gradle build.

## Global Constraints

- Do not change the behavior, output path, or module-resolution strategy of the existing `runtimeImage`/`appImage` task registrations in `cpm.packaging.gradle.kts`. All new inputs on `RuntimeImageTask` must be optional/empty-by-default so that registration is byte-for-byte unchanged.
- Do not touch `AppBundleTask.kt`, `app/packaging/*` template files, or the `dmg`/`macApp` alias tasks in root `build.gradle.kts` — out of scope.
- Do not change `./gradlew run`, `./gradlew test`, or any other default dev-loop task.
- Do not attempt to execute the cross-linked (non-host) architecture's binary. Verify only its Mach-O architecture tag via `file(1)`, matching `scripts/build-native-host.sh`'s existing `file -b ... | grep -oE 'x86_64|arm64'` pattern.
- Pinned artifacts (unchanged from the original cross-arch design, already present in `scripts/download-cross-jmods.sh`): jmods build `26.0.1+8`, arm64 sha256 `e76d5df4bf1e1568b1de1332b1784e815746288309c79b08c72ae48545663484`, x86_64 sha256 `c323f7f94018e91a472273e9986e98890b0ca92dfd9bbdc9960c3edc6627b6b7`.
- Architecture-name mapping (`os.arch`/`uname -m` → `macos-x86_64`/`macos-arm64`) must match `NativeLibraryLocator.detectArchDirectoryName()` (`app/src/main/java/app/cpm/terminal/NativeLibraryLocator.java`) exactly: `x86_64`/`amd64` → `macos-x86_64`; `aarch64`/`arm64` → `macos-arm64`.
- New Gradle task names use camelCase with no hyphens, matching every existing task name in this codebase (`buildGhosttyNative`, `ffmSmokeTest`, `runtimeImage`, etc.) — not the literal architecture-label spelling (`macos-x86_64`) used in directory/file names.
- Bash edits to `scripts/download-cross-jmods.sh` must not rely on bash 4+ features and must keep the existing `fail()`/`==>`-message style.
- Kotlin files under `buildSrc/` follow the existing style in `RuntimeImageTask.kt`/`AppBundleTask.kt`: typed tasks with injected `ExecOperations`/`FileSystemOperations` (no `project.*` calls at execution time), explicit `@get:Input(Files/Directory/File)` annotations, no inline fully-qualified class references (imports only, per this project's own convention already visible in every buildSrc file).

---

### Task 1: `scripts/download-cross-jmods.sh` — stable `jmods` symlink

**Files:**
- Modify: `scripts/download-cross-jmods.sh`

**Interfaces:**
- Consumes: nothing new.
- Produces: in addition to the existing stdout contract (prints the real extracted directory path), the script now also leaves a symlink at `<output-dir>/jmods` pointing at that real directory, on both the idempotent-reuse and fresh-extraction paths. Task 2's `DownloadCrossJmodsTask` and Task 3's wiring depend on this symlink path being present and correct after every successful run.

- [ ] **Step 1: Add the symlink to the idempotent-reuse path**

In `scripts/download-cross-jmods.sh`, find this block (currently lines 65-68):

```bash
if [[ "$existing_jmods_dir_count" -eq 1 ]]; then
    echo "==> Already downloaded and extracted: $existing_jmods_dirs" >&2
    echo "$existing_jmods_dirs"
    exit 0
```

Replace with:

```bash
if [[ "$existing_jmods_dir_count" -eq 1 ]]; then
    ln -sfn "$existing_jmods_dirs" "$output_dir/jmods"
    echo "==> Already downloaded and extracted: $existing_jmods_dirs" >&2
    echo "$existing_jmods_dirs"
    exit 0
```

- [ ] **Step 2: Add the symlink to the fresh-extraction path**

Find this block (currently lines 100-105):

```bash
jmods_dirs="$(find "$output_dir" -type f -name '*.jmod' -exec dirname {} \; | sort -u)"
jmods_dir_count="$(echo "$jmods_dirs" | grep -c . || true)"
[[ "$jmods_dir_count" -eq 1 ]] \
    || fail "expected exactly one directory of .jmod files under $output_dir after extraction, found $jmods_dir_count: $jmods_dirs"

echo "$jmods_dirs"
```

Replace with:

```bash
jmods_dirs="$(find "$output_dir" -type f -name '*.jmod' -exec dirname {} \; | sort -u)"
jmods_dir_count="$(echo "$jmods_dirs" | grep -c . || true)"
[[ "$jmods_dir_count" -eq 1 ]] \
    || fail "expected exactly one directory of .jmod files under $output_dir after extraction, found $jmods_dir_count: $jmods_dirs"

ln -sfn "$jmods_dirs" "$output_dir/jmods"
echo "$jmods_dirs"
```

- [ ] **Step 3: Update the script's header comment**

Find this line in the header comment block (currently line 21):

```bash
# Prints the resolved jmods/ directory path to stdout on success (all
```

Replace the surrounding paragraph (lines 21-24) with:

```bash
# Prints the resolved real jmods directory path to stdout on success (all
# other output goes to stderr), AND leaves a stable symlink at
# <output-dir>/jmods pointing at that same directory -- Gradle wiring
# (buildSrc/src/main/kotlin/cpm/tasks/DownloadCrossJmodsTask.kt) uses the
# symlink path directly rather than capturing stdout. Idempotent: if
# <output-dir> already contains a directory with .jmod files in it
# (detected by content, not by name), this is a no-op that just refreshes
# the symlink and reprints the path.
```

- [ ] **Step 4: Verify the symlink is created on a fresh run**

```bash
rm -rf /tmp/cpm-jmods-symlink-test
./scripts/download-cross-jmods.sh macos-arm64 /tmp/cpm-jmods-symlink-test
ls -la /tmp/cpm-jmods-symlink-test/
readlink /tmp/cpm-jmods-symlink-test/jmods
ls /tmp/cpm-jmods-symlink-test/jmods | grep -c '\.jmod$'
```

Expected: a real download (~100MB) happens; `/tmp/cpm-jmods-symlink-test/jmods` is a symlink; `readlink` shows it points at the actual extracted directory (e.g. `.../jdk-26.0.1+8-jmods`); the `.jmod` count through the symlink is nonzero (matches accessing the real directory directly).

- [ ] **Step 5: Verify the symlink survives the idempotent path**

```bash
./scripts/download-cross-jmods.sh macos-arm64 /tmp/cpm-jmods-symlink-test
readlink /tmp/cpm-jmods-symlink-test/jmods
```

Expected: `==> Already downloaded and extracted: ...` (no re-download), and the symlink still resolves correctly (the `ln -sfn` on the idempotent path is a no-op here since nothing changed, but confirms it doesn't error or break the existing symlink).

- [ ] **Step 6: Clean up**

```bash
rm -rf /tmp/cpm-jmods-symlink-test
```

- [ ] **Step 7: Commit**

```bash
git add scripts/download-cross-jmods.sh
git commit -m "$(cat <<'EOF'
Add stable jmods symlink to download-cross-jmods.sh

Gradle wiring for the cross-arch port needs a fixed, predictable
path to declare as a plain @InputDirectory -- the real extracted
directory's name varies by JDK build (e.g. jdk-26.0.1+8-jmods/).
The script now also leaves a symlink at <output-dir>/jmods pointing
at the real directory, on both the fresh-extraction and idempotent-
reuse paths, alongside the existing (unchanged) stdout contract.
EOF
)"
```

---

### Task 2: `buildSrc` Kotlin — `DownloadCrossJmodsTask` + extend `RuntimeImageTask`

**Files:**
- Create: `buildSrc/src/main/kotlin/cpm/tasks/DownloadCrossJmodsTask.kt`
- Modify: `buildSrc/src/main/kotlin/cpm/tasks/RuntimeImageTask.kt` (full file replacement)

**Interfaces:**
- Consumes: `scripts/download-cross-jmods.sh <arch> <output-dir>` (Task 1) via `DownloadCrossJmodsTask`.
- Produces: `DownloadCrossJmodsTask.outputDir: DirectoryProperty` (Task 3 wires `<outputDir>/jmods` into `RuntimeImageTask.extraModulePath`). `RuntimeImageTask` gains `crossFxJars: ConfigurableFileCollection`, `extraModulePath: ConfigurableFileCollection`, `expectedMachOArch: Property<String>` (all `@Optional`, all consumed by Task 3's per-arch task registrations).

- [ ] **Step 1: Create `DownloadCrossJmodsTask.kt`**

Create `buildSrc/src/main/kotlin/cpm/tasks/DownloadCrossJmodsTask.kt` with this exact content:

```kotlin
package cpm.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Wraps `scripts/download-cross-jmods.sh`: downloads and checksum-verifies
 * the pinned Eclipse Temurin jmods bundle for a macOS architecture other
 * than the one this build is running on, for use as a jlink
 * `--module-path` entry when cross-linking (see docs/superpowers/specs/
 * 2026-07-20-cross-arch-port-design.md).
 *
 * The script leaves a stable `jmods` symlink inside [outputDir] pointing
 * at the real, version-named extracted directory, precisely so this
 * task's output can be wired into [RuntimeImageTask.extraModulePath] as
 * `<outputDir>/jmods` without any stdout-capture ceremony.
 */
abstract class DownloadCrossJmodsTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    /** `macos-x86_64` or `macos-arm64` -- the target (non-host) architecture. */
    @get:Input
    abstract val arch: Property<String>

    /** The script itself, so a change to it invalidates this task's output. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val scriptFile: RegularFileProperty

    /** Parent directory; the script extracts into here and creates `jmods` inside it. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        outputDir.get().asFile.mkdirs()
        execOps.exec {
            commandLine(
                scriptFile.get().asFile.absolutePath,
                arch.get(),
                outputDir.get().asFile.absolutePath,
            )
        }
    }
}
```

- [ ] **Step 2: Replace `RuntimeImageTask.kt` in full**

Replace the entire contents of `buildSrc/src/main/kotlin/cpm/tasks/RuntimeImageTask.kt` with:

```kotlin
package cpm.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Gate 0F (plan section 7 / 28 "Task 8"): assembles a self-contained
 * jlink runtime image at the caller-supplied [imageDir] (delete -> jlink
 * -> copy -> launcher -> thin .app trampoline bundle) -- `build/image`
 * for the host-only `runtimeImage` task registration (the plan's literal
 * acceptance command), `build/image-<arch>` for the cross-arch task
 * instances added alongside it.
 *
 * The image is assembled by hand rather than via a jlink/jpackage Gradle
 * plugin: the application is still non-modular (classpath/ALL-UNNAMED,
 * plan section 6.4 "prefer a modular application once native loading is
 * stable" -- not yet stable enough to modularize), so a
 * jlink-application-image plugin built around module-path application jars
 * does not fit cleanly. Doing it explicitly also matches plan section
 * 6.5's "implement the jlink command explicitly if the plugin obscures the
 * generated layout." See docs/runtime-image.md for the full report.
 *
 * A typed task with injected [ExecOperations]/[FileSystemOperations]
 * (no `project.*` access at execution time), replacing the former ad-hoc
 * `doLast` block whose deprecated `project.exec`/`project.copy` calls were
 * configuration-cache-hostile.
 *
 * Cross-architecture support (see docs/superpowers/specs/
 * 2026-07-20-cross-arch-port-design.md): [crossFxJars], [extraModulePath],
 * and [expectedMachOArch] are optional and empty/absent by default, which
 * reproduces the original single-architecture behavior byte for byte --
 * the `runtimeImage` task registration in cpm.packaging.gradle.kts never
 * sets them. When a caller does set them (the cross-arch task
 * registrations added alongside it), this same task type jlinks a runtime
 * for an architecture other than the one the build is running on, given
 * that architecture's own jmods (via [extraModulePath]) and JavaFX jars
 * (via [crossFxJars]) -- real jlink cross-linking (JEP 220), not a hack.
 */
abstract class RuntimeImageTask @Inject constructor(
    private val execOps: ExecOperations,
    private val fsOps: FileSystemOperations,
) : DefaultTask() {

    /** The application jar (`:app:jar`). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val appJar: RegularFileProperty

    /** Main's full runtime classpath (JavaFX + RichTextFX + transitives). */
    @get:InputFiles
    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    /** `build/native` at the Gradle root (both architectures' dylibs + headers). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeDir: DirectoryProperty

    /**
     * The dock icon (`assets/app-icon.icns`) as a file collection so a
     * missing icon stays non-fatal (matching the previous behavior) while
     * a present one is still fingerprinted as an input.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icon: ConfigurableFileCollection

    /** Verbatim launcher script template (app/packaging/launcher.sh). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launcherScript: RegularFileProperty

    /** Verbatim Info.plist template (app/packaging/Info.plist). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val infoPlist: RegularFileProperty

    /** Verbatim thin-bundle trampoline template (app/packaging/bundle-trampoline.sh). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundleTrampoline: RegularFileProperty

    /**
     * The JDK 26 toolchain home whose `bin/jlink` is invoked (not whatever
     * JVM is running Gradle -- Gradle 8.11.1 does not run on JDK 26), so
     * the image's module set matches the JDK the application is actually
     * compiled/run against. A plain path `@Input` so a toolchain switch
     * invalidates the image, same fingerprint as the previous ad-hoc task.
     */
    @get:Input
    abstract val javaHomePath: Property<String>

    /**
     * Cross-architecture only: explicitly-classified JavaFX module jars
     * for an architecture other than the host's (e.g. resolved with
     * classifier `mac-aarch64` while running on an x86_64 host). Empty by
     * default; when empty, [assemble] falls back to filtering
     * `javafx-`-prefixed jars out of [runtimeClasspath], exactly as before
     * this property existed.
     */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val crossFxJars: ConfigurableFileCollection

    /**
     * Cross-architecture only: extra jlink `--module-path` entries
     * prepended before the JavaFX jars -- the downloaded jmods directory
     * for the target architecture (scripts/download-cross-jmods.sh, via
     * [cpm.tasks.DownloadCrossJmodsTask]). Empty by default (no extra
     * entries; `java.*`/`jdk.*` modules resolve implicitly from
     * [javaHomePath]'s own JDK, as before).
     */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extraModulePath: ConfigurableFileCollection

    /**
     * Cross-architecture only: when present, the produced
     * `runtime/bin/java` is verified with `file(1)` to report this
     * architecture token (e.g. `"arm64"`), hard-failing otherwise --
     * mirrors `scripts/build-native-host.sh`'s existing file(1)-based
     * acceptance gate. Also gates a host-JDK-vs-pinned-jmods-build
     * consistency check (see [assemble]). This is the only verification
     * possible for a cross-linked, non-host architecture: actually
     * executing it is not possible on the build machine and is deferred
     * to CI. Absent by default (no check for the host build).
     */
    @get:Input
    @get:Optional
    abstract val expectedMachOArch: Property<String>

    /** The image root -- `build/image` for the host build, `build/image-<arch>` for cross-arch task instances. */
    @get:OutputDirectory
    abstract val imageDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val imageRoot = imageDir.get().asFile
        fsOps.delete { delete(imageRoot) }
        imageRoot.mkdirs()

        val javaHome = File(javaHomePath.get())

        // Cross-architecture only: the downloaded jmods must be the exact
        // same JDK build as the host toolchain jlinking them, or the
        // cross-link either fails with a cryptic jlink error or silently
        // links a mismatched module graph. Checked via the JDK's own
        // `release` file (a standard, stable file in every JDK
        // distribution) rather than any Gradle-internal API.
        if (expectedMachOArch.isPresent) {
            val pinnedJmodsBuild = "26.0.1+8"
            val hostRuntimeVersion = readJavaRuntimeVersion(javaHome)
            if (hostRuntimeVersion != pinnedJmodsBuild) {
                throw GradleException(
                    "Host JDK is $hostRuntimeVersion, but scripts/download-cross-jmods.sh's " +
                        "pinned jmods are build $pinnedJmodsBuild. Cross-linking requires the " +
                        "host JDK and the downloaded jmods to be the exact same JDK build -- " +
                        "update the pinned URLs/checksums in scripts/download-cross-jmods.sh " +
                        "(and this pinnedJmodsBuild constant) together if the project's JDK 26 " +
                        "toolchain version has changed."
                )
            }
        }

        // 1. jlink the JDK + JavaFX module graph. For the host architecture,
        // no --module-path entry for JDK modules is needed: jlink resolves
        // java.*/jdk.* modules from the running JDK's own module graph
        // (jrt:) when none is given. For a cross-linked architecture,
        // extraModulePath supplies that architecture's own jmods
        // explicitly (real jlink cross-linking, JEP 220).
        val jlinkExe = File(javaHome, "bin/jlink")
        val fxJars = if (crossFxJars.files.isNotEmpty()) {
            crossFxJars.files.toList()
        } else {
            runtimeClasspath.files.filter { it.name.startsWith("javafx-") }
        }
        require(fxJars.size == 3) {
            "Expected exactly 3 javafx-*.jar files (base/controls/graphics), found: $fxJars"
        }
        val modulePath = (extraModulePath.files.toList() + fxJars)
            .joinToString(File.pathSeparator) { it.absolutePath }
        val runtimeOut = File(imageRoot, "runtime")

        // Module list is the transitive closure of what jar
        // --describe-module reports the javafx-*.jar files require, plus
        // what `jdeps --print-module-deps` reports app.jar itself uses
        // directly (java.base, java.desktop, jdk.jfr) -- verified by
        // running both against this exact JDK/JavaFX pairing rather than
        // guessed. jdk.unsupported is added defensively: several
        // JavaFX/AWT internals reach for sun.misc.Unsafe-family APIs
        // reflectively, which jdeps cannot see; harmless if unused.
        execOps.exec {
            commandLine(
                jlinkExe.absolutePath,
                "--module-path", modulePath,
                "--add-modules",
                // java.net.http: GitHubService's search client (Clone-from-GitHub modal).
                "java.base,java.desktop,java.net.http,java.xml,jdk.jfr,jdk.unsupported," +
                    "javafx.base,javafx.controls,javafx.graphics",
                "--output", runtimeOut.absolutePath,
                "--no-header-files",
                "--no-man-pages",
                "--strip-debug",
                "--compress", "zip-6"
            )
        }

        if (expectedMachOArch.isPresent) {
            val javaBin = File(runtimeOut, "bin/java")
            val fileOutput = ByteArrayOutputStream()
            execOps.exec {
                commandLine("file", "-b", javaBin.absolutePath)
                standardOutput = fileOutput
            }
            val description = fileOutput.toString(Charsets.UTF_8.name())
            val expected = expectedMachOArch.get()
            if (!description.contains(expected)) {
                throw GradleException(
                    "$javaBin is not tagged as $expected (file(1) reported: " +
                        "${description.trim()}); cross-linked jlink output is wrong."
                )
            }
        }

        // 2. Copy the application jar + every runtime-classpath jar onto a
        // plain classpath directory (app/), since the application is not
        // yet modular (see the class Javadoc). For a cross-arch image, the
        // resolved (cross-classified) fxJars replace the host's own
        // javafx-*.jar files in the copied set, since JavaFX ships
        // architecture-specific native code inside those jars.
        val appLibDir = File(imageRoot, "app")
        appLibDir.mkdirs()
        val nonFxClasspathJars = runtimeClasspath.files.filter {
            it.name.endsWith(".jar") && !it.name.startsWith("javafx-")
        }
        fsOps.copy {
            from(appJar)
            from(nonFxClasspathJars)
            from(fxJars)
            into(appLibDir)
        }

        // 3. Copy libghostty + the AppKit host shim for BOTH architectures
        // (the approved dual-arch deviation) into lib/<arch>/, mirroring
        // the build/native/<arch>/ layout the native build scripts produce,
        // so NativeLibraryLocator's os.arch-based selection picks the
        // right one at launch on either machine.
        val nativeOut = nativeDir.get().asFile
        val libDir = File(imageRoot, "lib")
        for (arch in listOf("macos-x86_64", "macos-arm64")) {
            val src = File(nativeOut, arch)
            val dst = File(libDir, arch)
            dst.mkdirs()
            for (name in listOf("libghostty.dylib", "libcpmterminalhost.dylib")) {
                val source = File(src, name)
                if (source.isFile) {
                    source.copyTo(File(dst, name), overwrite = true)
                } else {
                    throw GradleException(
                        "Missing $source -- run './gradlew buildGhosttyNative buildNativeHost' first."
                    )
                }
            }
        }

        // 3b. Bundle the dock icon so the launcher's -Xdock:icon can
        // reference it. The real fix for dock identity is the .app bundle
        // (see AppBundleTask); -Xdock:* covers the bare-JVM launch.
        val iconSource = icon.files.singleOrNull { it.isFile }
        if (iconSource != null) {
            iconSource.copyTo(File(libDir, "app-icon.icns"), overwrite = true)
        }

        // 4. Generate the launcher (plan section 23.2) from the verbatim
        // template.
        val binDir = File(imageRoot, "bin")
        binDir.mkdirs()
        val launcher = File(binDir, "claude-project-manager")
        launcher.writeText(launcherScript.get().asFile.readText())
        launcher.setExecutable(true, false)

        // 5. Wrap the launcher in a minimal .app bundle so macOS shows the
        // real name + icon in the Dock/app switcher. A bare `java` process
        // is registered with LaunchServices as "java" with the generic
        // icon, and JavaFX never applies -Xdock:* (that path is AWT-only),
        // so Info.plist metadata is the only public mechanism. Launch the
        // bundle (Finder or `open`) for correct Dock identity; the plain
        // bin/ launcher stays for the diag harness, which needs inherited
        // environment variables that `open` would drop.
        val appBundle = File(imageRoot, "Claude Project Manager.app")
        val contentsDir = File(appBundle, "Contents")
        val macosDir = File(contentsDir, "MacOS").apply { mkdirs() }
        val resourcesDir = File(contentsDir, "Resources").apply { mkdirs() }
        if (iconSource != null) {
            iconSource.copyTo(File(resourcesDir, "app-icon.icns"), overwrite = true)
        }
        File(contentsDir, "Info.plist").writeText(infoPlist.get().asFile.readText())
        val bundleLauncher = File(macosDir, "claude-project-manager")
        bundleLauncher.writeText(bundleTrampoline.get().asFile.readText())
        bundleLauncher.setExecutable(true, false)
    }
}

/**
 * Reads JAVA_RUNTIME_VERSION out of a JDK installation's `release` file
 * (a standard, stable file present in every JDK distribution -- not a
 * Gradle-internal API), e.g. "26.0.1+8". Used by [RuntimeImageTask] to
 * verify the host JDK matches the exact build that
 * scripts/download-cross-jmods.sh's pinned jmods were built from -- a
 * mismatch here would otherwise surface as a cryptic jlink failure during
 * the cross-link, not a clear error naming the real cause.
 */
private fun readJavaRuntimeVersion(javaHome: File): String {
    val releaseFile = File(javaHome, "release")
    if (!releaseFile.isFile) {
        throw GradleException(
            "Expected a 'release' file at $releaseFile to read JAVA_RUNTIME_VERSION, but it does not exist."
        )
    }
    val line = releaseFile.readLines().firstOrNull { it.startsWith("JAVA_RUNTIME_VERSION=") }
        ?: throw GradleException(
            "$releaseFile does not contain a JAVA_RUNTIME_VERSION line."
        )
    return line.substringAfter("=").trim('"')
}
```

- [ ] **Step 3: Verify the module compiles and the existing `runtimeImage` task still works**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runtimeImage
find build/image -type f | sort | wc -l
build/image/bin/claude-project-manager &
pid=$!
sleep 5
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null
```

Expected: `buildSrc` recompiles cleanly (no Kotlin errors — this is the first real compile check of the new `RuntimeImageTask.kt`/`DownloadCrossJmodsTask.kt`), `runtimeImage` still succeeds and produces the same shape of output as before (non-zero file count, launcher present), and the launcher starts/stops cleanly with no zombie process — proving the new optional properties truly didn't change existing behavior.

- [ ] **Step 4: Commit**

```bash
git add buildSrc/src/main/kotlin/cpm/tasks/DownloadCrossJmodsTask.kt buildSrc/src/main/kotlin/cpm/tasks/RuntimeImageTask.kt
git commit -m "$(cat <<'EOF'
Add DownloadCrossJmodsTask; extend RuntimeImageTask for cross-arch

RuntimeImageTask gains three optional inputs (crossFxJars,
extraModulePath, expectedMachOArch), all empty/absent by default so
the existing runtimeImage task registration is byte-for-byte
unchanged. When set (by task instances added in a follow-up commit),
the same task type cross-links a runtime for a non-host macOS
architecture using that architecture's own jmods + explicitly-
classified JavaFX jars, verifies the result via file(1), and checks
the host JDK build matches the pinned jmods build before attempting
the link. DownloadCrossJmodsTask wraps
scripts/download-cross-jmods.sh as a typed task.
EOF
)"
```

---

### Task 3: Wire `runtimeImageAllArches` in `cpm.packaging.gradle.kts` + root alias

**Files:**
- Modify: `buildSrc/src/main/kotlin/cpm.packaging.gradle.kts`
- Modify: `build.gradle.kts` (root)

**Interfaces:**
- Consumes: `DownloadCrossJmodsTask`, `RuntimeImageTask`'s new optional inputs (Task 2).
- Produces: `build/image-macos-x86_64/`, `build/image-macos-arm64/` (Task 1's carried-forward `scripts/package-runtime-image.sh --all-arches` tars these); task names `:app:runtimeImageAllArches` and root alias `runtimeImageAllArches`.

- [ ] **Step 1: Append the cross-arch wiring to `cpm.packaging.gradle.kts`**

Add this content at the end of `buildSrc/src/main/kotlin/cpm.packaging.gradle.kts` (after the existing `appImage` task registration's closing `}`), and add the four new imports shown at the top alongside the existing `cpm.tasks.AppBundleTask`/`cpm.tasks.RuntimeImageTask` imports:

```kotlin
import cpm.tasks.DownloadCrossJmodsTask
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
```

```kotlin
// Cross-arch jlink runtime images: see docs/superpowers/specs/
// 2026-07-20-cross-arch-port-design.md. Opt-in -- does not affect
// runtimeImage/appImage/run in any way. Builds BOTH macOS architectures
// in one pass regardless of which one this machine actually is,
// downloading the non-host architecture's jmods via
// scripts/download-cross-jmods.sh and resolving its JavaFX jars via an
// explicit classifier (rather than the host-inferred one the javafx {}
// block in app/build.gradle.kts uses).

/** Applies the inputs every RuntimeImageTask instance needs regardless of architecture. */
fun RuntimeImageTask.configureCommonRuntimeImageInputs() {
    dependsOn(":buildGhosttyNative")
    dependsOn(":buildNativeHost")
    appJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    runtimeClasspath.from(configurations.named("runtimeClasspath"))
    nativeDir.set(rootProject.layout.buildDirectory.dir("native"))
    icon.from(rootProject.layout.projectDirectory.file("assets/app-icon.icns"))
    launcherScript.set(packagingDir.file("launcher.sh"))
    infoPlist.set(packagingDir.file("Info.plist"))
    bundleTrampoline.set(packagingDir.file("bundle-trampoline.sh"))
    javaHomePath.set(
        javaToolchains.launcherFor(java.toolchain)
            .map { it.metadata.installationPath.asFile.absolutePath }
    )
}

val hostOsArch = System.getProperty("os.arch", "").lowercase()
val hostArchLabel = when (hostOsArch) {
    "x86_64", "amd64" -> "macos-x86_64"
    "aarch64", "arm64" -> "macos-arm64"
    else -> throw org.gradle.api.GradleException(
        "Unrecognized host os.arch '$hostOsArch' (expected x86_64/amd64 or aarch64/arm64)."
    )
}
val allMacosArches = listOf("macos-x86_64", "macos-arm64")
val crossArchLabel = allMacosArches.first { it != hostArchLabel }

val downloadCrossJmods = tasks.register<DownloadCrossJmodsTask>("downloadCrossJmods") {
    group = "distribution"
    description = "Downloads and checksum-verifies the jmods bundle for the non-host macOS architecture."
    arch.set(crossArchLabel)
    scriptFile.set(rootProject.layout.projectDirectory.file("scripts/download-cross-jmods.sh"))
    outputDir.set(rootProject.layout.buildDirectory.dir("cross-jdk/$crossArchLabel"))
}

// org.openjfx's artifacts publish Gradle Module Metadata with per-platform
// variants (mac-aarch64Runtime, macRuntime, etc.) -- a bare classifier in
// the dependency notation is NOT enough to select among them on its own
// ("Cannot choose between the available variants" otherwise). The
// detached configuration needs the same org.gradle.native.operatingSystem/
// org.gradle.native.architecture attributes the javafx-gradle-plugin
// itself requests for the host build, pinned explicitly to the
// cross-linked architecture instead of the host's.
val crossFxClassifier = if (crossArchLabel == "macos-arm64") "mac-aarch64" else "mac"
val crossFxDeps = listOf("javafx-base", "javafx-controls", "javafx-graphics").map {
    dependencies.create("org.openjfx:$it:26:$crossFxClassifier")
}
val crossFxConfiguration = configurations.detachedConfiguration(*crossFxDeps.toTypedArray())
crossFxConfiguration.attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
    attribute(
        OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
        objects.named(OperatingSystemFamily::class.java, OperatingSystemFamily.MACOS)
    )
    attribute(
        MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
        objects.named(
            MachineArchitecture::class.java,
            if (crossArchLabel == "macos-arm64") MachineArchitecture.ARM64 else MachineArchitecture.X86_64
        )
    )
}

val machOArchToken = mapOf("macos-x86_64" to "x86_64", "macos-arm64" to "arm64")

val runtimeImageMacosX8664 = tasks.register<RuntimeImageTask>("runtimeImageMacosX8664") {
    group = "distribution"
    description = "Cross-arch jlink runtime image for macOS x86_64 at build/image-macos-x86_64."
    configureCommonRuntimeImageInputs()
    imageDir.set(rootProject.layout.buildDirectory.dir("image-macos-x86_64"))
    if ("macos-x86_64" != hostArchLabel) {
        dependsOn(downloadCrossJmods)
        crossFxJars.from(crossFxConfiguration)
        extraModulePath.from(downloadCrossJmods.flatMap { it.outputDir.dir("jmods") })
        expectedMachOArch.set(machOArchToken["macos-x86_64"])
    }
}

val runtimeImageMacosArm64 = tasks.register<RuntimeImageTask>("runtimeImageMacosArm64") {
    group = "distribution"
    description = "Cross-arch jlink runtime image for macOS arm64 at build/image-macos-arm64."
    configureCommonRuntimeImageInputs()
    imageDir.set(rootProject.layout.buildDirectory.dir("image-macos-arm64"))
    if ("macos-arm64" != hostArchLabel) {
        dependsOn(downloadCrossJmods)
        crossFxJars.from(crossFxConfiguration)
        extraModulePath.from(downloadCrossJmods.flatMap { it.outputDir.dir("jmods") })
        expectedMachOArch.set(machOArchToken["macos-arm64"])
    }
}

tasks.register("runtimeImageAllArches") {
    group = "distribution"
    description = "Cross-links jlink runtime images for BOTH macOS architectures (x86_64 and " +
        "arm64) in one pass at build/image-macos-x86_64 and build/image-macos-arm64. Does not " +
        "execute the foreign-architecture binary -- only its Mach-O architecture tag is " +
        "verified; real execution verification is CI's job."
    dependsOn(runtimeImageMacosX8664, runtimeImageMacosArm64)
}
```

- [ ] **Step 2: Add the root alias task**

In root `build.gradle.kts`, find the existing `runtimeImage` alias task (search for `tasks.register("runtimeImage")`). Immediately after its closing `}`, add:

```kotlin
tasks.register("runtimeImageAllArches") {
    group = "distribution"
    description = "Alias for :app:runtimeImageAllArches (cross-links both macOS architectures in one pass)."
    dependsOn(":app:runtimeImageAllArches")
}
```

- [ ] **Step 3: Build and verify both architectures**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew runtimeImageAllArches
file -b build/image-macos-x86_64/runtime/bin/java
file -b build/image-macos-arm64/runtime/bin/java
```

Expected: the build succeeds (this performs a real ~100MB+ network download for the arm64 jmods bundle, unless Task 1's manual testing already cached it at `build/cross-jdk/macos-arm64/`); the first `file -b` reports `x86_64`, the second reports `arm64` — two genuinely different architectures, confirmed on this Intel host.

```bash
ls -la build/image-macos-x86_64/bin/claude-project-manager build/image-macos-arm64/bin/claude-project-manager
build/image-macos-x86_64/bin/claude-project-manager &
pid=$!
sleep 5
kill "$pid" 2>/dev/null || true
wait "$pid" 2>/dev/null
```

Expected: both launchers exist and are executable; the host-arch (`x86_64`) one launches and is cleanly killable (matches the existing `runtimeImage` acceptance bar) — the `arm64` one is **not** executed here, per this plan's Global Constraints.

- [ ] **Step 4: Verify the JDK-build-mismatch guard actually fires**

Temporarily change the `pinnedJmodsBuild` value in `RuntimeImageTask.kt` from `"26.0.1+8"` to `"99.0.0+1"`, then:

```bash
./gradlew runtimeImageAllArches
echo "exit code: $?"
```

Expected: fails FAST (before any jlink/download work for the cross task instance — though the host-arch instance, which doesn't check this at all, may still succeed/be up-to-date) with a `GradleException` naming both `99.0.0+1` (expected) and the real host version (`26.0.1+8`). Then revert `pinnedJmodsBuild` back to `"26.0.1+8"` and re-run `./gradlew runtimeImageAllArches` to confirm it succeeds again.

- [ ] **Step 5: Verify `scripts/package-runtime-image.sh --all-arches` still works against the new implementation**

```bash
./scripts/package-runtime-image.sh --all-arches
ls build/dist/
```

Expected: two archives, `cpm-image-macos-x86_64-<sha>.tar.gz` and `cpm-image-macos-arm64-<sha>.tar.gz`, exactly as this carried-forward script already promises — no script changes were needed, this step just proves the port didn't break its contract.

- [ ] **Step 6: Verify `runtimeImage`/`appImage` are still completely unaffected**

```bash
./gradlew runtimeImage appImage
ls "build/dist/Claude Project Manager.app/Contents/MacOS/"
```

Expected: both succeed exactly as they did before this plan touched anything (they don't reference any of the new task instances or new `RuntimeImageTask` properties).

- [ ] **Step 7: Commit**

```bash
git add buildSrc/src/main/kotlin/cpm.packaging.gradle.kts build.gradle.kts
git commit -m "$(cat <<'EOF'
Wire runtimeImageAllArches into cpm.packaging.gradle.kts

Adds downloadCrossJmods (DownloadCrossJmodsTask), a detached,
attribute-resolved cross-classifier JavaFX configuration, and two
RuntimeImageTask instances (runtimeImageMacosX8664/
runtimeImageMacosArm64) -- the host-matching one configured
identically to runtimeImage but at a different output dir, the other
using the new cross-arch inputs. runtimeImageAllArches is a thin
lifecycle task depending on both, with a matching root alias.
Verified end-to-end: both architectures produce genuinely different
file(1)-confirmed Mach-O binaries, the JDK-build-mismatch guard fires
correctly, and runtimeImage/appImage/package-runtime-image.sh remain
completely unaffected.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** stable jmods symlink (Task 1), typed `DownloadCrossJmodsTask` (Task 2 Step 1), `RuntimeImageTask` extension with zero-behavior-change defaults + JDK-version guard + `file(1)` gate (Task 2 Step 2), the two per-arch task instances + lifecycle task + detached attribute-resolved JavaFX configuration + root alias (Task 3) are all covered, matching every design decision confirmed in `docs/superpowers/specs/2026-07-20-cross-arch-port-design.md`. `runtimeImage`/`appImage`/`dmg`/`macApp`/`AppBundleTask`/dev-loop tasks are explicitly left untouched and each task ends with a verification step proving that.
- **Placeholder scan:** no TBD/TODO; every Kotlin/bash file is given in full, literal content; every verification step has exact commands and expected output.
- **Type/naming consistency:** `RuntimeImageTask`'s new property names (`crossFxJars`, `extraModulePath`, `expectedMachOArch`) are used identically between the class definition (Task 2) and both call sites (Task 3). Architecture labels (`macos-x86_64`/`macos-arm64`) and `file(1)` tokens (`x86_64`/`arm64`) match `NativeLibraryLocator.detectArchDirectoryName()` exactly, per Global Constraints. Task names (`downloadCrossJmods`, `runtimeImageMacosX8664`, `runtimeImageMacosArm64`, `runtimeImageAllArches`) are camelCase with no hyphens, matching every existing task name in this codebase.
