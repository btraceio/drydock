// Convention plugin: the Phase-0 spike source set and its gate tasks
// (plan section 7 / 28). Applied by app/build.gradle.kts.
//
// Spike/experiment harnesses never live in app/src/main/java (AGENTS.md,
// "Code placement and hygiene"): they compile in the dedicated `spike`
// source set below, so they stay runnable via the gateNSpike tasks but
// never ship in the app jar or the jlink runtime image (the packaging
// tasks copy the jar + runtimeClasspath, neither of which references
// spike output).

plugins {
    java
}

// The spike code sees main's classes and main's full dependency set (the
// JavaFX jars the javafx plugin puts on main's configurations included);
// package-private access (e.g. GhosttySmokeTest -> GhosttyBinding) still
// works because Java access control is per-package, not per-source-set.
val spike: SourceSet = sourceSets.create("spike") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + sourceSets.main.get().runtimeClasspath
}

/**
 * Shared shape of every gate task: a [JavaExec] on the spike runtime
 * classpath, run with the JDK 26 toolchain launcher (not whatever JVM
 * launched Gradle itself -- Gradle 8.11.1 does not run on JDK 26; classes
 * are compiled for 26 via `java.toolchain`, so they must also be *run*
 * with a 26 launcher), from the root project directory (build/native
 * lives at the Gradle root, not under app/).
 *
 * `--enable-native-access=ALL-UNNAMED` (rather than naming a module) is
 * deliberate and matches plan section 6.4 exactly: "Use ALL-UNNAMED only
 * during the earliest non-modular spike." This project has no
 * module-info.java yet. No --add-exports is needed either: these tasks
 * run JavaFX from the classpath (unnamed module), so there is no
 * module-boundary enforcement to open up -- see the historical notes in
 * docs/native-integration.md.
 */
fun registerGateTask(name: String, desc: String, main: String,
                     configure: JavaExec.() -> Unit = {}) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = desc
        dependsOn(":buildGhosttyNative")
        classpath = spike.runtimeClasspath
        mainClass.set(main)
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
        workingDir = rootProject.projectDir
        configure()
    }

// Gate 0B (plan section 7 / 28 "Task 4"): FFM smoke test. Loads libghostty
// via app.drydock.terminal.ghostty (the narrow native boundary package),
// initializes it, reads back its version info, exercises config new/free,
// and exits cleanly. A root-level alias task makes `./gradlew ffmSmokeTest`
// match the plan's literal `:terminal-ghostty:ffmSmokeTest` spelling.
registerGateTask(
    "ffmSmokeTest",
    "Gate 0B: loads libghostty via FFM, calls ghostty_init/info/config, and exits cleanly.",
    "app.drydock.terminal.ghostty.GhosttySmokeTest"
)

// Gate 0C (plan section 7 / 28 "Task 5"): the smallest possible JavaFX
// window that embeds one Ghostty terminal surface. See Gate0cSpike and
// docs/native-integration.md. Launched via Gate0cSpikeLauncher (see its
// Javadoc: launching an Application subclass directly as the JVM main
// class trips JavaFX's module-path check).
registerGateTask(
    "gate0cSpike",
    "Gate 0C: opens a JavaFX window embedding one Ghostty terminal surface.",
    "app.drydock.terminal.Gate0cSpikeLauncher"
) {
    dependsOn(":buildNativeHost")
    // Runs the scripted show/resize/type/screenshot/close sequence by
    // default so pass/fail can be evaluated from logs without a human
    // driving the window. Pass -Papp.drydock.gate0c.interactive to leave the
    // window open instead.
    if (!providers.gradleProperty("app.drydock.gate0c.interactive").isPresent) {
        systemProperty("app.drydock.gate0c.autoExit", "true")
    }
}

// Gate 0D (plan section 7 / 28 "Task 6"): spawns /bin/zsh -l inside the
// Gate 0C terminal surface and works through the interactive-shell manual
// checklist headlessly where possible. See Gate0dSpike,
// docs/manual-terminal-checklist.md, and docs/native-integration.md.
registerGateTask(
    "gate0dSpike",
    "Gate 0D: runs /bin/zsh -l in the embedded terminal and drives the interaction checklist.",
    "app.drydock.terminal.Gate0dSpikeLauncher"
) {
    dependsOn(":buildNativeHost")
    // Scripted checklist by default; -Papp.drydock.gate0d.interactive leaves
    // the window open with a live shell for a human instead.
    if (!providers.gradleProperty("app.drydock.gate0d.interactive").isPresent) {
        systemProperty("app.drydock.gate0d.autoExit", "true")
    }
}

// Gate 0E (plan section 7 / 28 "Task 7"): runs the real installed `claude`
// CLI inside the embedded terminal, in a throwaway git repository (never
// this project's own repository). See Gate0eSpike and
// docs/claude-integration.md.
registerGateTask(
    "gate0eSpike",
    "Gate 0E: runs the real `claude` CLI in a throwaway test repo inside the embedded terminal.",
    "app.drydock.terminal.Gate0eSpikeLauncher"
) {
    dependsOn(":buildNativeHost")
    // Required: an absolute path to a throwaway git repository (created
    // and torn down by the caller, never this project's own checkout --
    // plan section 21 forbids interpreting arbitrary repository content
    // as config, and there is no reason to point a spike at a real repo).
    providers.gradleProperty("app.drydock.gate0e.repo").orNull?.let {
        systemProperty("app.drydock.gate0e.repo", it)
    }
    // Defaults to ~/.local/bin/claude; override with
    // -Papp.drydock.gate0e.claude=<path> if the CLI lives elsewhere.
    providers.gradleProperty("app.drydock.gate0e.claude").orNull?.let {
        systemProperty("app.drydock.gate0e.claude", it)
    }
    // Scripted transcript by default; -Papp.drydock.gate0e.interactive leaves
    // the window open with a live `claude` session instead (for a human to
    // drive the rest of the checklist).
    if (!providers.gradleProperty("app.drydock.gate0e.interactive").isPresent) {
        systemProperty("app.drydock.gate0e.autoExit", "true")
    }
}
