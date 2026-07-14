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
    inputs.files(
        fileTree("${rootDir}/third_party/ghostty") {
            exclude(".zig-cache/**", "zig-out/**", "macos/GhosttyKit.xcframework/**")
        }
    )
    outputs.dir(ghosttyNativeOutputDir)
    outputs.file(ghosttyGeneratedDir.map { it.file("ghostty-version.properties") })

    // System.getenv("ZIG_BIN") lets a developer pin a different Zig 0.15.x
    // location than the script's own auto-detection
    // (/usr/local/opt/zig@0.15/bin/zig); left unset here to defer entirely
    // to the script's documented defaults/detection.
    commandLine("bash", "${rootDir}/scripts/build-ghostty.sh")
}
