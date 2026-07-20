import java.io.ByteArrayOutputStream

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
        // loads libghostty/the native host shim via FFM.
        "--enable-native-access=ALL-UNNAMED",
        // Unlike the gateNSpike tasks below (which force classpath-mode
        // JavaFX via `classpath = sourceSets.main.get().runtimeClasspath`,
        // avoiding JPMS enforcement entirely), the `application`/`javafx`
        // Gradle plugins configure `run` to launch JavaFX on the *module
        // path* (--module-path + --add-modules), matching the jlink runtime
        // image's own module-path setup. That means
        // app.cpm.terminal.host.JavaFxNativeView (in this app's classes,
        // an unnamed module since the app itself isn't modularized) needs
        // an explicit --add-exports to reach into javafx.graphics's
        // internal com.sun.glass.ui package -- exactly the same flag
        // runtimeImageLauncherScript() already carries for the jlink image.
        // Without this, `./gradlew run` throws IllegalAccessError the first
        // time a terminal tab is opened (this was missed when Milestone 5
        // wired the terminal-tabs UI into the real app, since the `run`
        // task's module-path-vs-classpath distinction from the spike tasks
        // was overlooked).
        "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"
    )
}

repositories {
    mavenCentral()
}

dependencies {
    // Read-only code viewer in the Session Explorer (syntax highlighting +
    // line-number gutter). Pulls flowless/reactfx/undofx/wellbehavedfx
    // transitively; all run from the plain classpath like the JavaFX jars.
    implementation("org.fxmisc.richtext:richtextfx:0.11.5")

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

    // Diagnostic support: forward -Papp.cpm.diag.* project properties to
    // the run task's application JVM as system properties (the Gradle
    // client's own -D flags never reach the forked app JVM; same pattern
    // as gate0eSpike's project.property forwarding below). Used by
    // automated visual verification: app.cpm.diag.stateFile isolates
    // persisted state to a throwaway file, and
    // app.cpm.diag.autoCreateSession=true plus app.cpm.diag.repo=<path>
    // auto-registers a repository and opens a session in it on startup
    // (see CpmApplication.start).
    project.properties.forEach { (key, value) ->
        if (key.startsWith("app.cpm.diag.") && value != null) {
            systemProperty(key, value.toString())
        }
    }

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
 * module jars -- java.* / jdk.* modules still resolve implicitly from
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
        val fileOutput = ByteArrayOutputStream()
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
}

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

        val pinnedJmodsBuild = "26.0.1+8"
        val hostRuntimeVersion = readJavaRuntimeVersion(javaHome)
        if (hostRuntimeVersion != pinnedJmodsBuild) {
            throw org.gradle.api.GradleException(
                "Host JDK is $hostRuntimeVersion, but scripts/download-cross-jmods.sh's pinned jmods " +
                    "are build $pinnedJmodsBuild. Cross-linking requires the host JDK and the " +
                    "downloaded jmods to be the exact same JDK build -- update the pinned URLs/" +
                    "checksums in scripts/download-cross-jmods.sh (and this pinnedJmodsBuild constant) " +
                    "together if the project's JDK 26 toolchain version has changed."
            )
        }

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
        val jmodsPathOutput = ByteArrayOutputStream()
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
        // org.openjfx's artifacts publish Gradle Module Metadata with
        // per-platform variants (see the "Cannot choose between the
        // available variants" error this produces without this block);
        // a bare classifier in the dependency notation above is not
        // enough to select among them -- the detached configuration also
        // needs the same org.gradle.native.operatingSystem /
        // org.gradle.native.architecture attributes the javafx-gradle-plugin
        // itself requests for the host build, but pinned explicitly to the
        // cross-linked architecture instead of the host's.
        val crossFxConfig = project.configurations.detachedConfiguration(*crossFxDeps.toTypedArray())
        crossFxConfig.attributes {
            attribute(
                org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
                project.objects.named(org.gradle.api.attributes.Usage::class.java, org.gradle.api.attributes.Usage.JAVA_RUNTIME)
            )
            attribute(
                org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
                project.objects.named(org.gradle.api.attributes.Category::class.java, org.gradle.api.attributes.Category.LIBRARY)
            )
            attribute(
                org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                project.objects.named(org.gradle.api.attributes.LibraryElements::class.java, org.gradle.api.attributes.LibraryElements.JAR)
            )
            attribute(
                org.gradle.nativeplatform.OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                project.objects.named(
                    org.gradle.nativeplatform.OperatingSystemFamily::class.java,
                    org.gradle.nativeplatform.OperatingSystemFamily.MACOS
                )
            )
            attribute(
                org.gradle.nativeplatform.MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
                project.objects.named(
                    org.gradle.nativeplatform.MachineArchitecture::class.java,
                    if (crossArch == "macos-arm64") {
                        org.gradle.nativeplatform.MachineArchitecture.ARM64
                    } else {
                        org.gradle.nativeplatform.MachineArchitecture.X86_64
                    }
                )
            )
        }
        val crossFxJars = crossFxConfig.resolve().toList()

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

// Stage 3 (plan section 23.4): a SELF-CONTAINED .app bundle. Unlike the
// thin bundle inside build/image (a trampoline into the image, unusable
// once moved), this one carries the entire runtime image under Contents/,
// so the finished bundle can be copied or symlinked to /Applications and
// launched from Finder/Dock with the proper name and icon. The bundle is
// ad-hoc signed as a whole so Gatekeeper (and Apple Silicon's mandatory
// code-signing) accept the locally built binaries; Developer ID signing
// and notarization (Stages 5-6) remain out of scope.
val appBundleDistDir = rootProject.layout.buildDirectory.dir("dist")

tasks.register("appImage") {
    group = "distribution"
    description = "Stage 3: self-contained macOS .app bundle at build/dist/Claude Project Manager.app."

    dependsOn(tasks.named("runtimeImage"))
    inputs.dir(runtimeImageDir)
    outputs.dir(appBundleDistDir)

    doLast {
        val distRoot = appBundleDistDir.get().asFile
        project.delete(distRoot)
        val bundle = File(distRoot, "Claude Project Manager.app")
        val contents = File(bundle, "Contents")
        val macosDir = File(contents, "MacOS").apply { mkdirs() }
        val resourcesDir = File(contents, "Resources").apply { mkdirs() }
        val imageRoot = runtimeImageDir.get().asFile

        // cp -R rather than Gradle's copy so executable bits and the
        // runtime's existing code signatures survive byte-for-byte.
        for (part in listOf("bin", "runtime", "app", "lib")) {
            @Suppress("DEPRECATION")
            project.exec {
                commandLine("cp", "-R", File(imageRoot, part).absolutePath, contents.absolutePath)
            }
        }

        val icon = File(imageRoot, "lib/app-icon.icns")
        if (icon.isFile) {
            icon.copyTo(File(resourcesDir, "app-icon.icns"), overwrite = true)
        }

        File(contents, "Info.plist").writeText(appBundleInfoPlist())

        val bundleLauncher = File(macosDir, "claude-project-manager")
        bundleLauncher.writeText(
            """
            #!/bin/bash
            # The whole runtime image lives under Contents/ of this bundle;
            # the real launcher resolves APP_HOME relative to itself, so a
            # plain exec one level up is all that is needed.
            DIR="$(cd "$(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
            exec "${'$'}DIR/../bin/claude-project-manager" "${'$'}@"
            """.trimIndent() + "\n"
        )
        bundleLauncher.setExecutable(true, false)

        @Suppress("DEPRECATION")
        project.exec {
            commandLine("codesign", "--force", "--deep", "--sign", "-", bundle.absolutePath)
        }
    }
}

/**
 * Reads JAVA_RUNTIME_VERSION out of a JDK installation's `release` file
 * (a standard, stable file present in every JDK distribution -- not a
 * Gradle-internal API), e.g. "26.0.1+8". Used by runtimeImageAllArches
 * to verify the host JDK matches the exact build that
 * scripts/download-cross-jmods.sh's pinned jmods were built from --
 * a mismatch here would otherwise surface as a cryptic jlink failure
 * during the cross-link, not a clear error naming the real cause.
 */
fun readJavaRuntimeVersion(javaHome: File): String {
    val releaseFile = File(javaHome, "release")
    if (!releaseFile.isFile) {
        throw org.gradle.api.GradleException(
            "Expected a 'release' file at $releaseFile to read JAVA_RUNTIME_VERSION, but it does not exist."
        )
    }
    val line = releaseFile.readLines().firstOrNull { it.startsWith("JAVA_RUNTIME_VERSION=") }
        ?: throw org.gradle.api.GradleException(
            "$releaseFile does not contain a JAVA_RUNTIME_VERSION line."
        )
    return line.substringAfter("=").trim('"')
}

/**
 * The Info.plist content shared by both .app bundles (the thin trampoline
 * bundle inside build/image and the self-contained build/dist bundle) --
 * previously duplicated verbatim in the two tasks above.
 */
fun appBundleInfoPlist(): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
        <key>CFBundleName</key><string>Claude Project Manager</string>
        <key>CFBundleDisplayName</key><string>Claude Project Manager</string>
        <key>CFBundleIdentifier</key><string>app.cpm.claude-project-manager</string>
        <key>CFBundleExecutable</key><string>claude-project-manager</string>
        <key>CFBundleIconFile</key><string>app-icon</string>
        <key>CFBundlePackageType</key><string>APPL</string>
        <key>CFBundleShortVersionString</key><string>0.1</string>
        <key>NSHighResolutionCapable</key><true/>
    </dict>
    </plist>
""".trimIndent() + "\n"

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
 * - The entry point defaults to `app.cpm.Main`, the real application. Earlier
 *   (Gate 0F / Task 8, Milestones 0-4) this defaulted to the Gate 0C terminal
 *   spike (`app.cpm.terminal.Gate0cSpikeLauncher`) instead, since the real
 *   application was still an empty window (Milestones 0-3) or had no
 *   embedded terminal yet (Milestone 4's repository sidebar). Flipped now
 *   that Milestone 5 gives `app.cpm.Main` a real embedded terminal (managed
 *   Claude sessions in tabs), as anticipated by this comment's prior
 *   revision. `CPM_MAIN_CLASS=app.cpm.terminal.Gate0cSpikeLauncher` (or any
 *   other `gateNSpikeLauncher`) still selects a Phase 0 spike manually if
 *   needed for native/terminal-packaging-only verification (plan section 7
 *   "Gate 0F" / section 28 "Task 8").
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

# CPM_MAIN_CLASS / CPM_EXTRA_JVM_ARGS are internal escape hatches, e.g. to
# launch a Phase 0 gateNSpikeLauncher instead of the real app for
# native/terminal-packaging-only verification (plan section 22.5); see
# app/build.gradle.kts's runtimeImageLauncherScript() Javadoc for why the
# default main class is app.cpm.Main (the real application).
MAIN_CLASS="${d}{CPM_MAIN_CLASS:-app.cpm.Main}"

exec "${d}APP_HOME/runtime/bin/java" \
  --enable-native-access=ALL-UNNAMED \
  --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -Djava.awt.headless=false \
  -Xdock:name="Claude Project Manager" \
  -Xdock:icon="${d}APP_HOME/lib/app-icon.icns" \
  -Dapp.cpm.ghostty.nativeDir="${d}APP_HOME/lib" \
  -Dapp.cpm.terminalhost.nativeDir="${d}APP_HOME/lib" \
  ${d}{CPM_EXTRA_JVM_ARGS:-} \
  -cp "${d}APP_HOME/app/*" \
  "${d}MAIN_CLASS" "${d}@"
"""
}

