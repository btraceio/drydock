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
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Gate 0F (plan section 7 / 28 "Task 8"): assembles the self-contained
 * jlink runtime image at `build/image` (delete -> jlink -> copy ->
 * launcher -> thin .app trampoline bundle).
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

    /** The image root (`build/image` at the Gradle root, per the plan's literal acceptance command). */
    @get:OutputDirectory
    abstract val imageDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val imageRoot = imageDir.get().asFile
        fsOps.delete { delete(imageRoot) }
        imageRoot.mkdirs()

        // 1. jlink the JDK + JavaFX module graph. No --module-path entry
        // for JDK modules is needed: jlink resolves java.*/jdk.* modules
        // from the running JDK's own module graph (jrt:) when none is
        // given -- verified empirically, since the Temurin 26.0.1
        // distribution ships no jmods/ directory at all. Only the JavaFX
        // module jars need an explicit --module-path entry.
        val jlinkExe = File(File(javaHomePath.get()), "bin/jlink")
        val fxJars = runtimeClasspath.files.filter { it.name.startsWith("javafx-") }
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

        // 2. Copy the application jar + every runtime-classpath jar onto a
        // plain classpath directory (app/), since the application is not
        // yet modular (see the class Javadoc).
        val appLibDir = File(imageRoot, "app")
        appLibDir.mkdirs()
        fsOps.copy {
            from(appJar)
            from(runtimeClasspath.files.filter { it.name.endsWith(".jar") })
            into(appLibDir)
        }

        // 3. Copy libghostty + the AppKit host shim for BOTH architectures
        // (the approved dual-arch deviation) into lib/<arch>/, mirroring
        // the build/native/<arch>/ layout the native build scripts produce,
        // so GhosttyNativeLibrary/CpmTerminalHostLibrary's os.arch-based
        // selection picks the right one at launch on either machine.
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
