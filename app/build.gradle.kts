plugins {
    application
    id("org.openjfx.javafxplugin")
    // Spike source set + gateNSpike/ffmSmokeTest tasks (buildSrc convention
    // plugin; see buildSrc/src/main/kotlin/cpm.spikes.gradle.kts).
    id("cpm.spikes")
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
        // JavaFX via the spike source set's runtimeClasspath,
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

        // 2. Copy the application jar + every runtime-classpath jar (JavaFX
        // plus third-party libraries such as RichTextFX and its transitive
        // deps) onto a plain classpath directory (app/), since the
        // application is not yet modular (see the top-of-task comment).
        val appLibDir = File(imageRoot, "app")
        appLibDir.mkdirs()
        project.copy {
            from(jarTaskProvider.get().archiveFile)
            from(runtimeClasspathFiles.get().files.filter { it.name.endsWith(".jar") })
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

        // 3b. Bundle the dock icon (assets/app-icon.icns, generated clay
        // rounded-square + prompt glyph) so the launcher's -Xdock:icon can
        // reference it. The real fix for dock identity is a jpackage .app
        // bundle (future); -Xdock:* covers the bare-JVM launch until then.
        val iconSource = rootProject.file("assets/app-icon.icns")
        if (iconSource.isFile) {
            iconSource.copyTo(File(libDir, "app-icon.icns"), overwrite = true)
        }

        // 4. Generate the launcher (plan section 23.2).
        val binDir = File(imageRoot, "bin")
        binDir.mkdirs()
        val launcher = File(binDir, "claude-project-manager")
        launcher.writeText(runtimeImageLauncherScript())
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

