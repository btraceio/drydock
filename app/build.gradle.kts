plugins {
    application
    id("org.openjfx.javafxplugin")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

// JavaFX 26. The javafx-gradle-plugin resolves the correct platform
// classifier (mac / mac-aarch64) for the JVM actually running the build,
// which already gives us dev-time support on both Intel and Apple Silicon
// Macs. Packaging for BOTH architectures at once (for the jlink runtime
// image) is handled explicitly in the packaging tasks added in later
// milestones (see docs/native-integration.md and section 23 of the plan) —
// that is where the dual-arch deviation from the plan actually lives, not
// here.
javafx {
    version = "26"
    modules = listOf("javafx.base", "javafx.controls", "javafx.graphics")
}

application {
    mainClass.set("app.cpm.Main")

    // Required once native (libghostty) access is introduced; harmless no-op
    // until then. Kept here now so the JVM argument list is version
    // controlled from the start, per plan section 22 ("Keep JVM arguments
    // in version-controlled build configuration").
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=false"
    )
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    // Allow running headless/offscreen for CI, e.g.:
    //   ./gradlew run -PheadlessTest
    if (project.hasProperty("headlessTest")) {
        systemProperty("java.awt.headless", "true")
        systemProperty("testfx.headless", "true")
        systemProperty("prism.order", "sw")
        systemProperty("glass.platform", "Monocle")
        systemProperty("monocle.platform", "Headless")
    }
}

// Gate 0B (plan section 7 / 28 "Task 4"): FFM smoke test. Loads libghostty
// via app.cpm.terminal.ghostty (the narrow native boundary package),
// initializes it, reads back its version info, exercises config
// new/free, and exits cleanly.
//
// The project is still a single Gradle module (`app`) during the Phase 0
// feasibility spike (see docs/implementation-plan.md section 5), so this is
// the "equivalent in the current module layout" of the plan's literal
// `:terminal-ghostty:ffmSmokeTest` task name. A root-level alias task below
// makes `./gradlew ffmSmokeTest` work too, matching that spelling once the
// module is eventually split out.
tasks.register<JavaExec>("ffmSmokeTest") {
    group = "verification"
    description = "Gate 0B: loads libghostty via FFM, calls ghostty_init/info/config, and exits cleanly."

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.cpm.terminal.ghostty.GhosttySmokeTest")

    // Run with the JDK 26 toolchain (not whatever JVM launched Gradle
    // itself, which may be an older JDK -- Gradle 8.11.1 does not yet run
    // on JDK 26). Classes here are compiled for 26 via `java.toolchain`
    // above, so they must also be *run* with a 26 launcher.
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))

    // --enable-native-access=ALL-UNNAMED (rather than naming the module) is
    // deliberate and matches plan section 6.4 exactly: "Use ALL-UNNAMED only
    // during the earliest non-modular spike." This project has no
    // module-info.java yet. Switch to
    // `--enable-native-access=app.cpm.terminal.ghostty` once the application
    // is modularized (plan section 6.4, "prefer a modular application once
    // native loading is stable").
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")

    // build/native lives at the Gradle root, not under app/; resolve
    // relative to a known-fixed directory rather than relying on JavaExec's
    // default working directory.
    workingDir = rootProject.projectDir
}

// Gate 0C (plan section 7 / 28 "Task 5"): the smallest possible JavaFX
// window that embeds one Ghostty terminal surface. See
// app.cpm.terminal.Gate0cSpike and docs/native-integration.md.
tasks.register<JavaExec>("gate0cSpike") {
    group = "verification"
    description = "Gate 0C: opens a JavaFX window embedding one Ghostty terminal surface."

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))

    classpath = sourceSets.main.get().runtimeClasspath
    // See Gate0cSpikeLauncher's Javadoc: launching Gate0cSpike (an
    // Application subclass) directly as the JVM's main class trips
    // JavaFX's "JavaFX runtime components are missing" module-path check,
    // even though everything needed is present on the classpath.
    mainClass.set("app.cpm.terminal.Gate0cSpikeLauncher")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))

    jvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED"
        // Note: app.cpm.terminal.host.JavaFxNativeView reaches into the
        // internal com.sun.glass.ui.{Window,View} classes (see that
        // class's Javadoc for why, and the risk of doing so -- plan rule
        // 27.8). No --add-exports is needed here because this project
        // runs JavaFX from the classpath (unnamed module), not the module
        // path: javafx-graphics-26-mac.jar's module-info.class is ignored
        // in that mode and there is no module-boundary enforcement to
        // open up. If/when this project modularizes (plan section 6.4,
        // "prefer a modular application once native loading is stable"),
        // this will need to become
        // --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED (or
        // to the application's own named module) -- verified empirically
        // that --add-exports referencing "javafx.graphics" fails fast
        // ("Unknown module") in the current classpath-mode setup.
    )

    // Runs the scripted show/resize/type/screenshot/close sequence by
    // default so this task's pass/fail (and its log output) can be
    // evaluated without a human driving the window. Pass
    // -Papp.cpm.gate0c.interactive to leave the window open instead.
    if (!project.hasProperty("app.cpm.gate0c.interactive")) {
        systemProperty("app.cpm.gate0c.autoExit", "true")
    }

    workingDir = rootProject.projectDir
}

// Gate 0D (plan section 7 / 28 "Task 6"): spawns /bin/zsh -l inside the
// Gate 0C terminal surface and works through the interactive-shell manual
// checklist headlessly where possible. See app.cpm.terminal.Gate0dSpike,
// docs/manual-terminal-checklist.md, and docs/native-integration.md.
tasks.register<JavaExec>("gate0dSpike") {
    group = "verification"
    description = "Gate 0D: runs /bin/zsh -l in the embedded terminal and drives the interaction checklist."

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.cpm.terminal.Gate0dSpikeLauncher")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))

    // See gate0cSpike's comment above for why ALL-UNNAMED (not
    // --add-exports) is correct here.
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")

    // Runs the scripted checklist sequence by default. Pass
    // -Papp.cpm.gate0d.interactive to leave the window open with a live
    // shell instead (for a human to drive the remaining checklist items).
    if (!project.hasProperty("app.cpm.gate0d.interactive")) {
        systemProperty("app.cpm.gate0d.autoExit", "true")
    }

    workingDir = rootProject.projectDir
}

// Gate 0E (plan section 7 / 28 "Task 7"): runs the real installed `claude`
// CLI inside the embedded terminal, in a throwaway git repository (never
// this project's own repository). See app.cpm.terminal.Gate0eSpike and
// docs/claude-integration.md.
tasks.register<JavaExec>("gate0eSpike") {
    group = "verification"
    description = "Gate 0E: runs the real `claude` CLI in a throwaway test repo inside the embedded terminal."

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.cpm.terminal.Gate0eSpikeLauncher")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))

    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")

    // Required: an absolute path to a throwaway git repository (created
    // and torn down by the caller, never this project's own checkout --
    // plan section 21 forbids interpreting arbitrary repository content as
    // config, and there is no reason to point a spike at a real repo).
    if (project.hasProperty("app.cpm.gate0e.repo")) {
        systemProperty("app.cpm.gate0e.repo", project.property("app.cpm.gate0e.repo") as String)
    }
    // Defaults to ~/.local/bin/claude; override with
    // -Papp.cpm.gate0e.claude=<path> if the CLI lives elsewhere.
    if (project.hasProperty("app.cpm.gate0e.claude")) {
        systemProperty("app.cpm.gate0e.claude", project.property("app.cpm.gate0e.claude") as String)
    }

    // Runs the scripted transcript by default. Pass
    // -Papp.cpm.gate0e.interactive to leave the window open with a live
    // `claude` session instead (for a human to drive the rest of the
    // checklist -- workspace trust UI polish, real clipboard, etc.).
    if (!project.hasProperty("app.cpm.gate0e.interactive")) {
        systemProperty("app.cpm.gate0e.autoExit", "true")
    }

    workingDir = rootProject.projectDir
}
