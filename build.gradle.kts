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

// Plan section 6.3 also lists appImage / macApp / dmg as required
// top-level command aliases. appImage/macApp are now real (Stage 3, plan
// section 23.4): a self-contained ad-hoc-signed .app bundle assembled by
// :app:appImage at build/dist/Claude Project Manager.app. dmg (Stage 4)
// and Developer ID signing/notarization (Stages 5-6) remain explicit
// no-ops that fail with a clear message -- see docs/runtime-image.md
// "Packaging implications".
listOf("appImage", "macApp").forEach { name ->
    tasks.register(name) {
        group = "distribution"
        description = "Alias for :app:appImage (Stage 3: self-contained macOS .app bundle)."
        dependsOn(":app:appImage")
    }
}
listOf(
    "dmg" to "Stage 4 (plan section 23.4): produce a local .dmg from the .app bundle."
).forEach { (name, futureWork) ->
    tasks.register(name) {
        group = "distribution"
        description = "Not yet implemented -- $futureWork"
        doLast {
            throw GradleException(
                "'$name' is not implemented yet. $futureWork Currently only " +
                    "'./gradlew runtimeImage' (the raw jlink image, plan section 23.4 Stage 2) " +
                    "exists -- see docs/runtime-image.md."
            )
        }
    }
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

tasks.register<Exec>("buildGhosttyNative") {
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
    // build/native: buildNativeHost writes libcpmterminalhost.dylib into
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

// Builds the tiny AppKit host shim (native-host/CpmTerminalHost.{h,m}, plan
// section 8) for both supported macOS architectures. See
// scripts/build-native-host.sh and docs/native-integration.md.
tasks.register<Exec>("buildNativeHost") {
    group = "native"
    description = "Builds libcpmterminalhost (macOS arm64 + x86_64) via scripts/build-native-host.sh."

    inputs.file("${rootDir}/scripts/build-native-host.sh")
    inputs.dir("${rootDir}/native-host")
    outputs.file(ghosttyNativeOutputDir.map { it.dir("macos-x86_64").file("libcpmterminalhost.dylib") })
    outputs.file(ghosttyNativeOutputDir.map { it.dir("macos-arm64").file("libcpmterminalhost.dylib") })

    commandLine("bash", "${rootDir}/scripts/build-native-host.sh")
}
