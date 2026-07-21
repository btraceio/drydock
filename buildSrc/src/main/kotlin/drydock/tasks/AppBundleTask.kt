package drydock.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Stage 3 (plan section 23.4): a SELF-CONTAINED macOS .app bundle at
 * `build/dist/Drydock.app`. Unlike the thin bundle inside
 * `build/image` (a trampoline into the image, unusable once moved), this
 * one carries the entire runtime image under `Contents/`, so the finished
 * bundle can be copied or symlinked to /Applications and launched from
 * Finder/Dock with the proper name and icon. The bundle is ad-hoc signed
 * as a whole so Gatekeeper (and Apple Silicon's mandatory code-signing)
 * accept the locally built binaries; Developer ID signing and notarization
 * (Stages 5-6) remain out of scope.
 *
 * Typed task with injected [ExecOperations]/[FileSystemOperations] --
 * see [RuntimeImageTask]'s class Javadoc for why.
 */
abstract class AppBundleTask @Inject constructor(
    private val execOps: ExecOperations,
    private val fsOps: FileSystemOperations,
) : DefaultTask() {

    /** The assembled runtime image ([RuntimeImageTask.imageDir]). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imageDir: DirectoryProperty

    /** Verbatim Info.plist template (app/packaging/Info.plist, shared with the thin bundle). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val infoPlist: RegularFileProperty

    /** Verbatim self-contained-bundle trampoline template (app/packaging/dist-trampoline.sh). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val distTrampoline: RegularFileProperty

    /** The dist root (`build/dist` at the Gradle root). */
    @get:OutputDirectory
    abstract val distDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val distRoot = distDir.get().asFile
        fsOps.delete { delete(distRoot) }
        val bundle = File(distRoot, "Drydock.app")
        val contents = File(bundle, "Contents")
        val macosDir = File(contents, "MacOS").apply { mkdirs() }
        val resourcesDir = File(contents, "Resources").apply { mkdirs() }
        val imageRoot = imageDir.get().asFile

        // cp -R rather than Gradle's copy so executable bits and the
        // runtime's existing code signatures survive byte-for-byte.
        for (part in listOf("bin", "runtime", "app", "lib")) {
            execOps.exec {
                commandLine("cp", "-R", File(imageRoot, part).absolutePath, contents.absolutePath)
            }
        }

        val icon = File(imageRoot, "lib/app-icon.icns")
        if (icon.isFile) {
            icon.copyTo(File(resourcesDir, "app-icon.icns"), overwrite = true)
        }

        File(contents, "Info.plist").writeText(infoPlist.get().asFile.readText())

        val bundleLauncher = File(macosDir, "drydock")
        bundleLauncher.writeText(distTrampoline.get().asFile.readText())
        bundleLauncher.setExecutable(true, false)

        execOps.exec {
            commandLine("codesign", "--force", "--deep", "--sign", "-", bundle.absolutePath)
        }
    }
}
