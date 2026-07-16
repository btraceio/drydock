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
        "-Djava.awt.headless=false",
        // Required now that CpmApplication (Milestone 5's terminal-tabs UI)
        // loads libghostty/the native host shim via FFM -- see the
        // gate0cSpike task's comment for why ALL-UNNAMED (not
        // --add-exports) is correct for this project's current
        // classpath-mode (non-modular) setup.
        "--enable-native-access=ALL-UNNAMED"
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
    // The real application now embeds Ghostty terminal surfaces (Milestone
    // 5's terminal-tabs UI), so `run` needs both native libraries built
    // first, same as every gateNSpike task below.
    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir = rootProject.projectDir

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

// Gate 0F (plan section 7 / 28 "Task 8"): the first self-contained jlink
// runtime image. See docs/runtime-image.md for the full report (what
// works, what does not, exact layout, exact launcher JVM arguments,
// whether the architecture remains viable).
//
// The image is assembled by hand (delete -> jlink -> copy -> generate
// launcher) rather than via a jlink/jpackage Gradle plugin: this project's
// application module is still non-modular (classpath/ALL-UNNAMED, see the
// gate0cSpike comment above and plan section 6.4 "prefer a modular
// application once native loading is stable" -- not yet stable enough to
// modularize), so a jlink-application-image plugin built around
// module-path application jars does not fit cleanly yet. Doing it
// explicitly also matches plan section 6.5's "implement the jlink command
// explicitly if the plugin obscures the generated layout."
// Lives under the *root* build directory (not app/build/image) so the
// plan's literal acceptance command (section 7 "Gate 0F": `build/image/bin/
// claude-project-manager`, run from the repo root) matches exactly, the
// same way build/native (buildGhosttyNative/buildNativeHost, declared in
// the root build file) already does.
val runtimeImageDir = rootProject.layout.buildDirectory.dir("image")

tasks.register("runtimeImage") {
    group = "distribution"
    description = "Gate 0F: builds a self-contained jlink runtime image at build/image."

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))
    dependsOn(tasks.named("jar"))

    val toolchainService = project.extensions.getByType(JavaToolchainService::class.java)
    val javaLauncherProvider = toolchainService.launcherFor(java.toolchain)
    val jarTaskProvider = tasks.named<Jar>("jar")
    val runtimeClasspathFiles = configurations.named("runtimeClasspath")
    val nativeBuildDir = rootProject.layout.buildDirectory.dir("native")

    inputs.file(jarTaskProvider.flatMap { it.archiveFile })
    inputs.files(runtimeClasspathFiles)
    inputs.dir(nativeBuildDir)
    inputs.property("javaLauncher", javaLauncherProvider.map { it.metadata.installationPath.asFile.absolutePath })
    outputs.dir(runtimeImageDir)

    doLast {
        val imageRoot = runtimeImageDir.get().asFile
        project.delete(imageRoot)
        imageRoot.mkdirs()

        // 1. jlink the JDK + JavaFX module graph. jlink is invoked from the
        // *JDK 26 toolchain's* own bin/ (not whatever JVM is running
        // Gradle -- see the ffmSmokeTest comment above: Gradle 8.11.1 does
        // not yet run on JDK 26 itself), so the runtime image's module set
        // matches the JDK the application was actually compiled/run
        // against. No --module-path entry for JDK modules is needed:
        // jlink resolves java.*/jdk.* modules from the running JDK's own
        // module graph (jrt:) when none is given -- verified empirically,
        // since this Temurin 26.0.1 distribution ships no jmods/ directory
        // at all (newer Temurin builds split jmods into a separate
        // download). Only the JavaFX module jars need an explicit
        // --module-path entry.
        val javaHome = javaLauncherProvider.get().metadata.installationPath.asFile
        val jlinkExe = File(javaHome, "bin/jlink")
        val fxJars = runtimeClasspathFiles.get().files.filter { it.name.startsWith("javafx-") }
        require(fxJars.size == 3) {
            "Expected exactly 3 javafx-*.jar files (base/controls/graphics) on the runtime " +
                "classpath, found: $fxJars"
        }
        val modulePath = fxJars.joinToString(File.pathSeparator) { it.absolutePath }
        val runtimeOut = File(imageRoot, "runtime")

        // Module list is the transitive closure of what jar
        // --describe-module reports the javafx-*.jar files require, plus
        // what `jdeps --print-module-deps` reports app.jar itself uses
        // directly (java.base, java.desktop, jdk.jfr) -- verified by
        // running both against this exact JDK/JavaFX pairing rather than
        // guessed. jdk.unsupported is added defensively even though
        // neither tool reported it as required: several JavaFX/AWT
        // internals reach for sun.misc.Unsafe-family APIs reflectively,
        // which jdeps/jar --describe-module cannot see; harmless to
        // include if unused.
        // project.exec {} is deprecated in Gradle 8.x (in favor of an
        // injected ExecOperations, which is awkward to wire up for an
        // ad-hoc tasks.register {} block in a build script) but still
        // fully functional; not worth the extra indirection for one exec
        // call at this stage. Revisit if/when this task is promoted to a
        // real Task subclass.
        @Suppress("DEPRECATION")
        project.exec {
            commandLine(
                jlinkExe.absolutePath,
                "--module-path", modulePath,
                "--add-modules",
                "java.base,java.desktop,java.xml,jdk.jfr,jdk.unsupported," +
                    "javafx.base,javafx.controls,javafx.graphics",
                "--output", runtimeOut.absolutePath,
                "--no-header-files",
                "--no-man-pages",
                "--strip-debug",
                "--compress", "zip-6"
            )
        }

        // 2. Copy the application jar + JavaFX jars onto a plain classpath
        // directory (app/), since the application is not yet modular (see
        // the top-of-task comment).
        val appLibDir = File(imageRoot, "app")
        appLibDir.mkdirs()
        project.copy {
            from(jarTaskProvider.get().archiveFile)
            from(fxJars)
            into(appLibDir)
        }

        // 3. Copy libghostty + the AppKit host shim for BOTH architectures
        // (the approved dual-arch deviation) into lib/<arch>/, mirroring
        // the build/native/<arch>/ layout scripts/build-ghostty.sh and
        // scripts/build-native-host.sh already produce. This machine can
        // only ever load and test the macos-x86_64 copy (it is an Intel
        // Mac), but the macos-arm64 copy is bundled unconditionally so
        // that GhosttyNativeLibrary/CpmTerminalHostLibrary's existing
        // os.arch-based selection (the one place in the codebase allowed
        // to branch on CPU architecture, per plan section 2.4/4.2) would
        // pick it correctly if this exact image were copied onto Apple
        // Silicon hardware.
        val nativeOut = nativeBuildDir.get().asFile
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
                    throw org.gradle.api.GradleException(
                        "Missing $source -- run './gradlew buildGhosttyNative buildNativeHost' first."
                    )
                }
            }
        }

        // 4. Generate the launcher (plan section 23.2).
        val binDir = File(imageRoot, "bin")
        binDir.mkdirs()
        val launcher = File(binDir, "claude-project-manager")
        launcher.writeText(runtimeImageLauncherScript())
        launcher.setExecutable(true, false)
    }
}

/**
 * The generated `build/image/bin/claude-project-manager` launcher script.
 *
 * Deviations from the plan section 23.2 example worth noting explicitly:
 *
 * - No `-Djava.library.path=$APP_HOME/lib` is set. This project's native
 *   loading (`GhosttyNativeLibrary`, `CpmTerminalHostLibrary`) always uses
 *   `SymbolLookup.libraryLookup(<absolute path>, Arena.global())`, never
 *   `System.loadLibrary`/`System.load` relative-name lookup, so
 *   `java.library.path` is never consulted by this codebase. Setting it
 *   anyway to a flat `$APP_HOME/lib` would also be actively wrong here,
 *   since `lib/` contains `macos-x86_64/`/`macos-arm64/` subdirectories,
 *   not the `.dylib` files directly. Plan section 23.2's own last line
 *   ("do not add speculative JVM flags") is followed over the letter of
 *   its example.
 * - The entry point defaults to the Gate 0C terminal spike
 *   (`app.cpm.terminal.Gate0cSpikeLauncher`), not `app.cpm.Main`. This
 *   default was set when Gate 0F / Task 8 was built (Milestone 0 done,
 *   Milestones 1-2 in progress), and would then have proven nothing about
 *   native/terminal packaging since the real application was still an
 *   empty window; the terminal spike is what plan section 7 "Gate 0F" and
 *   section 28 "Task 8" actually ask this image to launch. `app.cpm.Main`
 *   is a real application as of Milestone 4 (a repository sidebar with
 *   persisted state -- see `CpmApplication`'s own Javadoc) but does not yet
 *   embed a terminal, so this default has deliberately been left alone
 *   rather than switched as part of Milestone 4 (out of scope -- changing
 *   the packaging/launch-target default is a Milestone 5+/packaging-phase
 *   decision). `CPM_MAIN_CLASS=app.cpm.Main` selects it manually in the
 *   meantime; expected to become the default once the real application
 *   embeds a terminal (Milestone 5 onward).
 */
fun runtimeImageLauncherScript(): String {
    val d = "\$"
    return """#!/bin/bash
set -euo pipefail

# Resolves the installation directory without depending on the current
# working directory (plan section 23.2), following symlinks so this still
# works if invoked through one (e.g. from /usr/local/bin).
SOURCE="${d}{BASH_SOURCE[0]}"
while [ -h "${d}SOURCE" ]; do
  DIR="${d}(cd -P "${d}(dirname "${d}SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="${d}(readlink "${d}SOURCE")"
  [[ ${d}SOURCE != /* ]] && SOURCE="${d}DIR/${d}SOURCE"
done
BIN_DIR="${d}(cd -P "${d}(dirname "${d}SOURCE")" >/dev/null 2>&1 && pwd)"
APP_HOME="${d}(cd -P "${d}BIN_DIR/.." >/dev/null 2>&1 && pwd)"

# CPM_MAIN_CLASS / CPM_EXTRA_JVM_ARGS are internal escape hatches used only
# by this project's own runtime-image smoke test (plan section 22.5); see
# app/build.gradle.kts's runtimeImageLauncherScript() Javadoc for why the
# default main class is the Gate 0C terminal spike, not app.cpm.Main.
MAIN_CLASS="${d}{CPM_MAIN_CLASS:-app.cpm.terminal.Gate0cSpikeLauncher}"

exec "${d}APP_HOME/runtime/bin/java" \
  --enable-native-access=ALL-UNNAMED \
  --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -Djava.awt.headless=false \
  -Dapp.cpm.ghostty.nativeDir="${d}APP_HOME/lib" \
  -Dapp.cpm.terminalhost.nativeDir="${d}APP_HOME/lib" \
  ${d}{CPM_EXTRA_JVM_ARGS:-} \
  -cp "${d}APP_HOME/app/*" \
  "${d}MAIN_CLASS" "${d}@"
"""
}

