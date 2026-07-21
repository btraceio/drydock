package drydock.tasks

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
 * the `runtimeImage` task registration in drydock.packaging.gradle.kts never
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
     * [drydock.tasks.DownloadCrossJmodsTask]). Empty by default (no extra
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

        // crossFxJars and extraModulePath are meant to be set together
        // (both empty for a host build, both non-empty for a cross build)
        // -- a partial configuration would silently link cross FX jars
        // against host jmods or vice versa. Both call sites in
        // drydock.packaging.gradle.kts already set them together, but this
        // makes the invariant explicit rather than relying on that.
        require(crossFxJars.isEmpty == extraModulePath.isEmpty) {
            "crossFxJars and extraModulePath must be set together (both empty for a host " +
                "build, both non-empty for a cross build) -- got crossFxJars=${crossFxJars.files} " +
                "extraModulePath=${extraModulePath.files}."
        }

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
            for (name in listOf("libghostty.dylib", "libdrydockterminalhost.dylib")) {
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
        val launcher = File(binDir, "drydock")
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
        val bundleLauncher = File(macosDir, "drydock")
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
