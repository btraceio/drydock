// Root build file.
//
// This project remains a single Gradle module (`app`) during the Phase 0
// feasibility spike (see docs/implementation-plan.md section 5 and 28).
// It will be split into `terminal-api`, `terminal-ghostty`, and
// `native-host` modules once the terminal prototype works.

plugins {
    // Applied only in subprojects; declared here (with apply false) so the
    // version is resolved once for the whole build.
    id("org.openjfx.javafxplugin") version "0.1.0" apply false
}

// Central Portal publishing is configured on the :app module via the
// com.vanniktech.maven.publish.base plugin (Central Portal Publisher API).
// The root group/version are kept in sync with app/build.gradle.kts by the
// release workflow's prepare job.
group = "io.btrace"
version = "0.1.0"

// Alias for the plan's literal Gate 0B task name
// (":terminal-ghostty:ffmSmokeTest") -- the project is still a single
// module ("app") during the Phase 0 feasibility spike (see section 5), so
// the real task lives at :app:ffmSmokeTest; this just makes the top-level
// spelling from section 7 / 28 work too: `./gradlew ffmSmokeTest`.
tasks.register("ffmSmokeTest") {
    group = "verification"
    description = "Alias for :app:ffmSmokeTest (Gate 0B: libghostty FFM smoke test)."
    dependsOn(":app:ffmSmokeTest")
}

// Alias for the plan's literal top-level command name (section 6.3, 7
// "Gate 0F", 28 "Task 8"): `./gradlew runtimeImage`. The real task lives
// at :app:runtimeImage (see app/build.gradle.kts for the full jlink
// image-assembly logic and docs/runtime-image.md for the report).
tasks.register("runtimeImage") {
    group = "distribution"
    description = "Alias for :app:runtimeImage (Gate 0F: jlink runtime image)."
    dependsOn(":app:runtimeImage")
}

tasks.register("runtimeImageAllArches") {
    group = "distribution"
    description = "Alias for :app:runtimeImageAllArches (cross-links both macOS architectures in one pass)."
    dependsOn(":app:runtimeImageAllArches")
}

// Windows runtime image alias: `./gradlew windowsRuntimeImage`. The real
// task lives at :app:windowsRuntimeImage and is registered (by the
// drydock.packaging plugin) only on non-macOS hosts. The alias follows
// the same gating: registered only on non-macOS hosts, so on Mac neither
// the alias nor the underlying task exists (and `./gradlew tasks` simply
// does not list it), and on Windows both are registered together. A
// runtime call on the wrong OS would otherwise fail with a confusing
// "Task with name 'windowsRuntimeImage' not found" -- gating the
// alias's registration is clearer.
val isMacOsHostRoot = System.getProperty("os.name", "").let {
    it.startsWith("Mac OS X") || it.startsWith("macOS")
}
if (!isMacOsHostRoot) {
    tasks.register("windowsRuntimeImage") {
        group = "distribution"
        description = "Alias for :app:windowsRuntimeImage (Windows jlink runtime image + drydock.bat launcher)."
        dependsOn(":app:windowsRuntimeImage")
    }
}

// Plan section 6.3 also lists appImage / macApp / dmg as required
// top-level command aliases. appImage/macApp are real (Stage 3, plan
// section 23.4): a self-contained ad-hoc-signed .app bundle assembled by
// :app:appImage at build/dist/Drydock.app. dmg (Stage 4) is now real too:
// :app:dmg wraps that .app into build/dist/Drydock.dmg (and depends on
// appImage, so `./gradlew dmg` builds the bundle first, in one step).
// Only Developer ID signing/notarization (Stages 5-6) remain out of scope
// -- see docs/runtime-image.md "Packaging implications".
listOf("appImage", "macApp").forEach { name ->
    tasks.register(name) {
        group = "distribution"
        description = "Alias for :app:appImage (Stage 3: self-contained macOS .app bundle)."
        dependsOn(":app:appImage")
    }
}
tasks.register("dmg") {
    group = "distribution"
    description = "Alias for :app:dmg (Stage 4: distributable .dmg wrapping the .app bundle)."
    dependsOn(":app:dmg")
}

tasks.register<Exec>("verifyEnvironment") {
    group = "verification"
    description = "Runs scripts/verify-environment.sh to check local dev prerequisites."
    commandLine("bash", "${rootDir}/scripts/verify-environment.sh")
}

// Builds libghostty for both supported macOS architectures (arm64 and
// x86_64 -- see the "Supported platforms" deviation in README.md) from the
// pinned third_party/ghostty submodule.
//
// Delegates to scripts/build-ghostty.sh, which:
//   - requires Zig 0.15.x (not whatever newer/older `zig` may be on PATH)
//     and a full Xcode install with the Metal Toolchain component, failing
//     with a clear, actionable message if either is missing;
//   - fails clearly if the third_party/ghostty submodule is not initialized;
//   - shells out to `zig build -Dxcframework-target=universal` (the only
//     mode that produces an aarch64-macos slice on an x86_64 host, and vice
//     versa -- see docs/native-integration.md), then splits the resulting
//     universal static library into one archive per architecture.
//
// Declaring explicit inputs/outputs lets Gradle skip this task entirely
// (UP-TO-DATE) when nothing relevant has changed; even when it does run,
// Zig's own build cache (third_party/ghostty/.zig-cache) makes a no-op
// rebuild fast (a few seconds) rather than a full rebuild (several minutes).
val ghosttyNativeOutputDir = layout.buildDirectory.dir("native")
val ghosttyGeneratedDir = layout.buildDirectory.dir("generated")

// CI builds each macOS arch slice natively on its own runner (arm64 on
// macos-14, x86_64 on the Intel macos-13) and the fan-in stage job downloads
// both into build/native/. With -Pnatives.prebuilt=true the two native build
// tasks are skipped and checkPrebuiltNatives verifies the downloaded dylibs are
// present, so jbangJar consumes them without re-running the (cross-compiling)
// native build. Unset -> unchanged local behavior (both slices built on host).
val nativesPrebuilt = providers.gradleProperty("natives.prebuilt")
    .map { it.toBoolean() }.orElse(false)

// The dylibs jbangJar actually bundles (per arch). checkPrebuiltNatives asserts
// these exist when natives are supplied pre-built by upstream CI jobs.
val prebuiltNativeDylibs = listOf("macos-arm64", "macos-x86_64").flatMap { arch ->
    listOf("libghostty.dylib", "libdrydockterminalhost.dylib").map { lib ->
        ghosttyNativeOutputDir.map { it.dir(arch).file(lib) }
    }
}

tasks.register("checkPrebuiltNatives") {
    group = "native"
    description = "Verifies pre-built native dylibs are present and correctly labelled " +
        "(for -Pnatives.prebuilt=true)."
    onlyIf { nativesPrebuilt.get() }
    doLast {
        val files = prebuiltNativeDylibs.map { it.get().asFile }
        val missing = files.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "natives.prebuilt=true but expected native libraries are missing:\n" +
                    missing.joinToString("\n") { "  - $it" } +
                    "\nEach must be downloaded from the per-arch native build job into build/native/<arch>/."
            )
        }

        // Presence alone is not enough. scripts/build-{ghostty,native-host}.sh
        // verify each slice with file(1) on the machine that builds it, but on
        // the -Pnatives.prebuilt=true path those scripts never run here: the
        // dylibs arrive as downloaded CI artifacts and go straight into
        // jbangJar's native/<arch>/ entries. Two artifacts downloaded into each
        // other's directory, or a stale/hand-copied dylib, would ship silently
        // -- NativeLibraryLocator picks the directory by the JVM's os.arch and
        // trusts the label, so the failure would land on a user as a dlopen
        // error, not here. Verify the label against the Mach-O header instead.
        val mislabelled = files.mapNotNull { file ->
            val expected = file.parentFile.name.removePrefix("macos-")
            val actual = drydock.NativeArch.machOArch(file)
            if (actual == expected) null else "  - $file is ${actual ?: "not a thin 64-bit Mach-O file"}, expected $expected"
        }
        if (mislabelled.isNotEmpty()) {
            throw GradleException(
                "natives.prebuilt=true but these native libraries do not match the " +
                    "architecture of the directory they are in:\n" +
                    mislabelled.joinToString("\n") +
                    "\nCheck that each per-arch CI artifact was downloaded into the matching " +
                    "build/native/<arch>/ directory (inspect with: file build/native/*/*.dylib)."
            )
        }
    }
}

tasks.register<Exec>("buildGhosttyNative") {
    // Skipped when the natives are supplied pre-built (CI fan-in); the
    // checkPrebuiltNatives task verifies their presence instead.
    onlyIf { !nativesPrebuilt.get() }

    group = "native"
    description = "Builds libghostty (macOS arm64 + x86_64) via scripts/build-ghostty.sh."

    inputs.file("${rootDir}/scripts/build-ghostty.sh")
    // Fingerprint the submodule by its pinned commit hash rather than by
    // hashing its entire working tree (tens of thousands of files, which
    // made every up-to-date check slow). Local uncommitted edits inside
    // third_party/ghostty therefore do NOT retrigger this task -- commit
    // (or run with --rerun-tasks) to pick them up.
    val ghosttyCommit = providers.exec {
        commandLine("git", "-C", "${rootDir}/third_party/ghostty", "rev-parse", "HEAD")
    }.standardOutput.asText.map { it.trim() }
    inputs.property("ghosttyCommit", ghosttyCommit)

    // Outputs are the specific files/dirs the script produces, NOT all of
    // build/native: buildNativeHost writes libdrydockterminalhost.dylib into
    // the same build/native/<arch>/ directories, and claiming the whole
    // root as this task's output made the two tasks' outputs overlap,
    // silently disabling caching and up-to-date checks for both.
    listOf("macos-x86_64", "macos-arm64").forEach { arch ->
        outputs.file(ghosttyNativeOutputDir.map { it.dir(arch).file("libghostty.dylib") })
        outputs.file(ghosttyNativeOutputDir.map { it.dir(arch).file("libghostty.a") })
    }
    outputs.dir(ghosttyNativeOutputDir.map { it.dir("include") })
    outputs.file(ghosttyGeneratedDir.map { it.file("ghostty-version.properties") })

    // System.getenv("ZIG_BIN") lets a developer pin a different Zig 0.15.x
    // location than the script's own auto-detection
    // (/usr/local/opt/zig@0.15/bin/zig); left unset here to defer entirely
    // to the script's documented defaults/detection.
    commandLine("bash", "${rootDir}/scripts/build-ghostty.sh")
}

// Builds the tiny AppKit host shim (native-host/DrydockTerminalHost.{h,m}, plan
// section 8) for both supported macOS architectures. See
// scripts/build-native-host.sh and docs/native-integration.md.
tasks.register<Exec>("buildNativeHost") {
    group = "native"
    description = "Builds libdrydockterminalhost (macOS arm64 + x86_64) via scripts/build-native-host.sh."
    // Skipped when the natives are supplied pre-built (CI fan-in).
    onlyIf { !nativesPrebuilt.get() }

    inputs.file("${rootDir}/scripts/build-native-host.sh")
    inputs.dir("${rootDir}/native-host")
    outputs.file(ghosttyNativeOutputDir.map { it.dir("macos-x86_64").file("libdrydockterminalhost.dylib") })
    outputs.file(ghosttyNativeOutputDir.map { it.dir("macos-arm64").file("libdrydockterminalhost.dylib") })

    commandLine("bash", "${rootDir}/scripts/build-native-host.sh")
}
