package drydock.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Stage 4 (plan section 23.4): wraps the self-contained `.app` bundle
 * ([AppBundleTask]'s `build/dist/Drydock.app`) into a distributable
 * `build/dist/Drydock.dmg`.
 *
 * The disk image is laid out for the conventional macOS drag-to-install
 * gesture: a staging folder holding the `.app` plus an `/Applications`
 * symlink, compressed into a read-only `UDZO` image via `hdiutil`.
 *
 * No signing happens here -- the bundle it wraps is already ad-hoc signed
 * by [AppBundleTask], which satisfies Apple Silicon's mandatory code-signing
 * for local execution. Developer ID signing and notarization of the `.dmg`
 * (Stages 5-6) remain out of scope, so a `.dmg` handed to another machine
 * still trips Gatekeeper's unidentified-developer prompt.
 *
 * Typed task with injected [ExecOperations]/[FileSystemOperations] --
 * see [RuntimeImageTask]'s class Javadoc for why.
 */
abstract class DmgTask @Inject constructor(
    private val execOps: ExecOperations,
    private val fsOps: FileSystemOperations,
) : DefaultTask() {

    /**
     * The `Drydock.app` bundle produced by [AppBundleTask].
     *
     * Deliberately the bundle itself, not [AppBundleTask]'s `distDir` root:
     * the `.dmg` is written *into* that root, so declaring the root here
     * would make the task dirty its own input on every run and never be
     * up-to-date. The bundle is also all this task actually reads.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appBundle: DirectoryProperty

    /** Volume name shown when the `.dmg` is mounted (the Finder window title). */
    @get:Input
    abstract val volumeName: Property<String>

    /** The finished disk image (`build/dist/Drydock.dmg`). */
    @get:OutputFile
    abstract val dmgFile: RegularFileProperty

    @TaskAction
    fun assemble() {
        val bundle = appBundle.get().asFile
        require(bundle.isDirectory) {
            "Expected the .app bundle at ${bundle.absolutePath}; run :app:appImage first."
        }

        // Stage the drag-install layout in the task's own temp dir: the
        // .app next to an /Applications symlink. cp -R (not Gradle copy)
        // preserves executable bits and the bundle's code signatures
        // byte-for-byte, exactly as AppBundleTask does.
        val staging = File(temporaryDir, "dmg-staging")
        fsOps.delete { delete(staging) }
        staging.mkdirs()
        execOps.exec {
            commandLine("cp", "-R", bundle.absolutePath, staging.absolutePath)
        }
        execOps.exec {
            commandLine("ln", "-s", "/Applications", File(staging, "Applications").absolutePath)
        }

        val output = dmgFile.get().asFile
        output.parentFile.mkdirs()
        // -ov overwrites a stale image so reruns are idempotent; UDZO is the
        // conventional read-only, zlib-compressed distribution format.
        execOps.exec {
            commandLine(
                "hdiutil", "create",
                "-volname", volumeName.get(),
                "-srcfolder", staging.absolutePath,
                "-ov",
                "-format", "UDZO",
                output.absolutePath,
            )
        }
    }
}
