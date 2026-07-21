package drydock.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Wraps `scripts/download-cross-jmods.sh`: downloads and checksum-verifies
 * the pinned Eclipse Temurin jmods bundle for a macOS architecture other
 * than the one this build is running on, for use as a jlink
 * `--module-path` entry when cross-linking (see docs/superpowers/specs/
 * 2026-07-20-cross-arch-port-design.md).
 *
 * The script leaves a stable `jmods` symlink inside [outputDir] pointing
 * at the real, version-named extracted directory, precisely so this
 * task's output can be wired into [RuntimeImageTask.extraModulePath] as
 * `<outputDir>/jmods` without any stdout-capture ceremony.
 */
abstract class DownloadCrossJmodsTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {

    /** `macos-x86_64` or `macos-arm64` -- the target (non-host) architecture. */
    @get:Input
    abstract val arch: Property<String>

    /** The script itself, so a change to it invalidates this task's output. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val scriptFile: RegularFileProperty

    /** Parent directory; the script extracts into here and creates `jmods` inside it. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        outputDir.get().asFile.mkdirs()
        execOps.exec {
            commandLine(
                scriptFile.get().asFile.absolutePath,
                arch.get(),
                outputDir.get().asFile.absolutePath,
            )
        }
    }
}
