package drydock

import java.io.File

/**
 * Architecture facts about files the build did not necessarily produce
 * itself: native libraries that may have arrived pre-built (CI's per-arch
 * fan-in, `-Pnatives.prebuilt=true`) and JDK installations resolved by
 * Gradle's toolchain detection.
 *
 * Both readers are deliberately pure file reads -- no `file(1)`, no `java
 * -version` fork -- so they can run inside a task action without an
 * injected [org.gradle.process.ExecOperations] and without the cost of a
 * process launch. `scripts/build-ghostty.sh` and
 * `scripts/build-native-host.sh` still use `file(1)` for the same purpose
 * at build time; this is the equivalent check for dylibs that were never
 * built on this machine.
 */
object NativeArch {

    // mach-o/loader.h: MH_MAGIC_64 == 0xFEEDFACF, stored little-endian on
    // every architecture Apple ships, so a 64-bit thin Mach-O begins with
    // the bytes CF FA ED FE followed by cpu_type_t, also little-endian.
    private val MH_MAGIC_64_LE = byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte())

    // mach/machine.h: CPU_ARCH_ABI64 (0x01000000) | CPU_TYPE_X86 (7) and
    // | CPU_TYPE_ARM (12).
    private const val CPU_TYPE_X86_64 = 0x01000007
    private const val CPU_TYPE_ARM64 = 0x0100000C

    /**
     * The architecture token (`x86_64` / `arm64`) of a thin 64-bit Mach-O
     * file, or `null` for anything else -- a fat/universal binary, a
     * 32-bit image, a truncated download, or a file that is not Mach-O at
     * all. Callers treat `null` as a failure rather than a pass: nothing in
     * this project's native pipeline produces anything but thin 64-bit
     * dylibs (both build scripts split the universal build per
     * architecture), so an unrecognized header means something unexpected
     * reached `build/native/`.
     */
    fun machOArch(file: File): String? {
        val head = file.inputStream().use { it.readNBytes(8) }
        if (head.size < 8) return null
        if (!head.copyOfRange(0, 4).contentEquals(MH_MAGIC_64_LE)) return null
        val cpuType = (head[4].toInt() and 0xFF) or
            ((head[5].toInt() and 0xFF) shl 8) or
            ((head[6].toInt() and 0xFF) shl 16) or
            ((head[7].toInt() and 0xFF) shl 24)
        return when (cpuType) {
            CPU_TYPE_X86_64 -> "x86_64"
            CPU_TYPE_ARM64 -> "arm64"
            else -> null
        }
    }

    /**
     * The architecture (`x86_64` / `arm64`) a JDK installation was built
     * for, read from the `OS_ARCH` line of its `release` file -- a
     * standard, stable file present in every JDK distribution (the same
     * source [drydock.tasks.RuntimeImageTask] already reads
     * JAVA_RUNTIME_VERSION from), not a Gradle-internal API. `null` when
     * the file or the line is missing, or the value is one this project
     * does not support.
     *
     * This is the only way to see a *toolchain's* architecture without
     * launching it: Gradle's JavaInstallationMetadata exposes the vendor,
     * versions and installation path, but no architecture.
     */
    fun jdkOsArch(javaHome: File): String? {
        val releaseFile = File(javaHome, "release")
        if (!releaseFile.isFile) return null
        val value = releaseFile.readLines()
            .firstOrNull { it.startsWith("OS_ARCH=") }
            ?.substringAfter("=")
            ?.trim('"')
            ?: return null
        return when (value) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x86_64"
            else -> null
        }
    }
}
