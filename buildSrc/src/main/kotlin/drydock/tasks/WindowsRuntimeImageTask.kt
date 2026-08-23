package drydock.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
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
 * Counterpart of [RuntimeImageTask] for Windows: jlinks a JDK + JavaFX
 * module graph and assembles a self-contained runtime image at
 * [imageDir] (delete -> jlink -> copy jars -> Windows .bat launcher).
 * The output is a directory a colleague can unzip anywhere and launch
 * with `bin/drydock.bat`; no installer, no MSIX, no code signing.
 *
 * <p><b>Why this is a separate task rather than a flag on
 * [RuntimeImageTask]</b>: the macOS image is Mac-only by construction
 * -- it bundles {@code libghostty.dylib} + {@code libdrydockterminalhost.dylib}
 * for both Apple-Silicon and Intel, copies a dock icon for {@code
 * -Xdock:icon}, and wraps the launcher in a thin {@code .app} bundle
 * for Finder / Dock identity. None of those concepts exist on Windows:
 * the JDK ships its own Windows binaries, libghostty has no Windows
 * port in this project (JediTermFX + pty4j is the v1 Windows terminal
 * backend -- see TerminalFactory's platform default), the dock tile
 * has no analog, and Windows has no {@code .app} bundle convention.
 * Keeping the macOS task untouched (so the macOS shipping path is not
 * disturbed) and adding a focused Windows task that does only what is
 * needed there keeps the two surfaces independent.</p>
 *
 * <p><b>What is shared with [RuntimeImageTask]</b>: the jlink module
 * set ({@code java.base,java.logging,java.desktop,java.net.http,java.xml,
 * jdk.httpserver,jdk.jfr,jdk.unsupported,javafx.base,javafx.controls,
 * javafx.graphics}) is the transitive closure of what the JavaFX jars
 * require plus what {@code jdeps} reports the application uses directly
 * (the comments in [RuntimeImageTask.assemble] explain the same
 * derivation; if that list changes, this one must change in lockstep).
 * The {@code app/} jars copy is the same classpath-as-plain-jars
 * arrangement the macOS task uses.</p>
 *
 * <p><b>What is NOT here</b>: no {@code expectedMachOArch} verification
 * (jlink emits an image for whatever architecture the JDK running it
 * happens to be; on Windows that's exactly what was asked for, no
 * cross-arch work is in scope for the v1 Windows runtime image); no
 * native libraries copied (the Windows backend loads none); no icon
 * copy (no analog); no {@code .app} bundle wrap (no convention).
 * DRYDOCK_MAIN_CLASS / DRYDOCK_EXTRA_JVM_ARGS env vars keep the same
 * meaning as the macOS launcher (see the launcher.bat header comment).</p>
 */
abstract class WindowsRuntimeImageTask @Inject constructor(
    private val execOps: ExecOperations,
    private val fsOps: FileSystemOperations,
) : DefaultTask() {

    /** The application jar (`:app:jar`). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val appJar: RegularFileProperty

    /** Main's full runtime classpath (JavaFX + RichTextFX + JediTermFX/pty4j + transitives). */
    @get:InputFiles
    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    /**
     * The JDK 26 toolchain home whose `bin/jlink` is invoked (not whatever
     * JVM is running Gradle -- Gradle 8.11.1 does not run on JDK 26), so
     * the image's module set matches the JDK the application is actually
     * compiled/run against. A plain path `@Input` so a toolchain switch
     * invalidates the image, same fingerprint as the macOS task.
     */
    @get:Input
    abstract val javaHomePath: Property<String>

    /** Verbatim Windows launcher template (app/packaging/launcher.bat). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launcherScript: RegularFileProperty

    /** The image root -- `build/image-windows` for the host build. */
    @get:OutputDirectory
    abstract val imageDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val imageRoot = imageDir.get().asFile
        fsOps.delete { delete(imageRoot) }
        imageRoot.mkdirs()

        val javaHome = File(javaHomePath.get())
        val jlinkExe = File(javaHome, "bin/jlink.exe")

        val fxJars = runtimeClasspath.files.filter { it.name.startsWith("javafx-") }
        require(fxJars.size == 3) {
            "Expected exactly 3 javafx-*.jar files (base/controls/graphics), found: $fxJars"
        }
        val modulePath = fxJars.joinToString(File.pathSeparator) { it.absolutePath }
        val runtimeOut = File(imageRoot, "runtime")

        // Module list matches RuntimeImageTask.assemble() exactly (see that
        // task's comment for the derivation). If the macOS task's list
        // changes, this one must change in lockstep -- they target the
        // same application.
        execOps.exec {
            commandLine(
                jlinkExe.absolutePath,
                "--module-path", modulePath,
                "--add-modules",
                "java.base,java.logging,java.desktop,java.net.http,java.xml,jdk.httpserver,jdk.jfr,jdk.unsupported," +
                    "javafx.base,javafx.controls,javafx.graphics",
                "--output", runtimeOut.absolutePath,
                "--no-header-files",
                "--no-man-pages",
                "--strip-debug",
                "--compress", "zip-6"
            )
        }

        // Copy the application jar + every runtime-classpath jar onto a
        // plain classpath directory (app/), since the application is not
        // yet modular (same approach as RuntimeImageTask.assemble() step 2).
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

        // Generate the Windows launcher from the verbatim template.
        val binDir = File(imageRoot, "bin")
        binDir.mkdirs()
        val launcher = File(binDir, "drydock.bat")
        launcher.writeText(launcherScript.get().asFile.readText())
    }
}
