// Convention plugin: packaging for the app project -- macOS (Gate 0F:
// jlink runtime image + .app bundle + .dmg) and Windows (jlink runtime
// image + .bat launcher). Applied by app/build.gradle.kts.
//
// The heavy lifting lives in the typed task classes
// buildSrc/src/main/kotlin/drydock/tasks/{RuntimeImageTask,AppBundleTask,
// WindowsRuntimeImageTask}.kt (injected ExecOperations/FileSystemOperations,
// precise inputs/outputs); the launcher scripts, Info.plist, and the two
// .app trampolines are verbatim template files under app/packaging/.
// Root-level alias tasks (`runtimeImage`, `appImage`, `macApp`) stay in
// the root build file.
//
// Both outputs live under the *root* build directory (build/image,
// build/dist, build/image-windows -- not app/build/...) so the plan's
// literal acceptance command (section 7 "Gate 0F": `build/image/bin/drydock`,
// run from the repo root) matches exactly, the same way build/native
// (buildGhosttyNative/buildNativeHost, declared in the root build file)
// already does.

import drydock.tasks.AppBundleTask
import drydock.tasks.DmgTask
import drydock.tasks.DownloadCrossJmodsTask
import drydock.tasks.RuntimeImageTask
import drydock.tasks.WindowsRuntimeImageTask
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily

plugins {
    java
}

val packagingDir = layout.projectDirectory.dir("packaging")

// Host platform detection, used to gate the macOS-only blocks below.
// Computed once at configuration time from os.name / os.arch; the Mac
// arch label is null on non-Mac hosts, which the macOS-only blocks
// test before registering anything. The rest of the project gates the
// same way at the build script level (see applicationDefaultJvmArgs in
// app/build.gradle.kts).
val hostOsName = System.getProperty("os.name", "")
val isMacOsHost = hostOsName.startsWith("Mac OS X") || hostOsName.startsWith("macOS")
val hostOsArch = System.getProperty("os.arch", "").lowercase()
val hostArchLabel = when (hostOsArch) {
    "x86_64", "amd64" -> "macos-x86_64"
    "aarch64", "arm64" -> "macos-arm64"
    else -> null
}
val machOArchToken = mapOf("macos-x86_64" to "x86_64", "macos-arm64" to "arm64")

// --- Rosetta / foreign-architecture guard ------------------------------------
//
// hostArchLabel above comes from the Gradle DAEMON JVM's os.arch, and that is
// the only thing deciding which build/native/<arch>/ slice the packaging tasks
// copy into the image, and what expectedMachOArch demands of jlink's output.
// On Apple Silicon an x86_64 JDK runs transparently under Rosetta 2 and
// reports os.arch=x86_64, so a machine whose only JDK 17 is an Intel build
// (an Intel Homebrew under /usr/local, say) silently produces a fully x86_64
// Drydock.app -- x86_64 libghostty slice included -- while every existing
// architecture check still passes: the daemon, the JDK 26 toolchain jlink runs
// from, and the copied dylib all agree with each other. They are simply all
// wrong for the machine. Nothing in the build compared them against the actual
// CPU until this check.
//
// Apple's documented signal is the sysctl.proc_translated flag: 0 for a native
// process, 1 for a translated one, and the key does not exist at all on an
// Intel Mac (sysctlbyname reports ENOENT, i.e. a non-zero exit here).
// https://developer.apple.com/documentation/apple-silicon/about-the-rosetta-translation-environment
//
// Both tools are invoked by absolute path: this runs inside the Gradle daemon,
// whose PATH is inherited from whatever launched it and need not include
// /usr/sbin. The sysctl probe runs through `sh -c ... || echo 0` so a genuine
// Intel Mac's non-zero exit is not an exec failure, and it is skipped entirely
// when uname already says arm64 -- a translated process always reports x86_64
// from uname(3), so arm64 there is conclusive on its own.
val hardwareArchLabel: String? = if (!isMacOsHost) null else {
    val unameArch = providers.exec {
        commandLine("/usr/bin/uname", "-m")
    }.standardOutput.asText.get().trim()
    when (unameArch) {
        "arm64" -> "macos-arm64"
        "x86_64" -> {
            val translated = providers.exec {
                commandLine(
                    "/bin/sh", "-c",
                    "/usr/sbin/sysctl -n sysctl.proc_translated 2>/dev/null || echo 0"
                )
            }.standardOutput.asText.get().trim() == "1"
            if (translated) "macos-arm64" else "macos-x86_64"
        }
        else -> null
    }
}

// Escape hatch for deliberately packaging from a translated daemon.
val allowForeignArchPackaging = providers.gradleProperty("packaging.allowRosetta")
    .map { it.toBoolean() }.orElse(false).get()

if (hardwareArchLabel != null && hostArchLabel != null &&
    hardwareArchLabel != hostArchLabel
) {
    val hostToken = machOArchToken.getValue(hostArchLabel)
    val hardwareToken = machOArchToken.getValue(hardwareArchLabel)

    // Warned on every build, not only packaging ones: the same mismatch makes
    // `./gradlew run` load build/native/macos-x86_64/libghostty.dylib on an
    // Apple Silicon machine (NativeLibraryLocator keys off the app JVM's
    // os.arch, and a translated JVM can only load a translated slice -- that
    // part is correct; it is the JVM choice that is wrong).
    logger.warn(
        "WARNING: Gradle is running on a $hostToken JVM on $hardwareToken hardware " +
            "(Rosetta 2). Native slice selection, `run`, and packaging all follow the " +
            "JVM's os.arch, so this is a $hostToken build of Drydock. " +
            "Run scripts/verify-environment.sh for the details."
    )

    // The failure is deliberately NOT at configuration time: an x86_64 daemon
    // still compiles and tests the application perfectly well, and blocking
    // `:app:test` on it would be gratuitous. The mismatch only becomes a
    // shipped artifact in a RuntimeImageTask, so that is where it is refused
    // -- which also covers appImage and dmg, both of which run downstream of
    // one, and the two cross-arch instances, whose host/cross roles this same
    // mismatch silently swaps.
    val message = """
        This machine's CPU is $hardwareToken, but the Gradle daemon JVM reports
        os.arch=${System.getProperty("os.arch")} -- it is a $hostToken JVM, running under Rosetta 2
        on $hardwareToken hardware.

        Every packaging decision keys off the daemon's os.arch, so this build would
        bundle build/native/$hostArchLabel/*.dylib (libghostty included) and jlink a
        $hostToken runtime, producing a $hostToken app on a $hardwareToken machine
        without any other check noticing.

        Fix the daemon JVM (gradle/gradle-daemon-jvm.properties pins toolchainVersion=17):
          ./gradlew --stop
          /usr/libexec/java_home -V                 # list installed JDKs
          file "${'$'}(/usr/libexec/java_home -v 17)/bin/java"   # must say $hardwareToken
          ./gradlew -q javaToolchains              # and check the JDK 26 toolchain too
        Install a native $hardwareToken JDK if the only 17/26 present are $hostToken builds.

        To produce a $hostToken image on purpose, you do not need a translated daemon:
        run :app:runtimeImageMacosX8664 (or :app:runtimeImageAllArches) from a native
        $hardwareToken daemon, which jlink-cross-links it properly.

        To package from this translated daemon anyway: -Ppackaging.allowRosetta=true
    """.trimIndent()

    // configureEach stays lazy, so this costs nothing unless a packaging task
    // actually runs. The action captures nothing but the already-built String
    // -- no `project` access at execution time, the property the rest of this
    // file is careful about.
    if (!allowForeignArchPackaging) {
        tasks.withType<RuntimeImageTask>().configureEach {
            doFirst { throw GradleException(message) }
        }
    }
}

// Mac-only block: the runtime image task, the .app bundle, the .dmg,
// and the cross-arch tasks all assume a Mac host. On non-Mac hosts (the
// Windows CI runner, primarily) only the Windows runtime image task is
// registered; everything else stays unregistered and never resolves.
//
// os.arch is the architecture of the Gradle *daemon* JVM (JDK 17 --
// gradle/gradle-daemon-jvm.properties), deliberately a different JVM from
// the JDK 26 toolchain that runs jlink. That independence is the whole
// point for the host check: an expectation derived from the jlinking JDK
// itself would be a tautology, since jlink always emits that JDK's own
// architecture.
if (isMacOsHost && hostArchLabel != null) {
    tasks.register<RuntimeImageTask>("runtimeImage") {
        group = "distribution"
        description = "Gate 0F: builds a self-contained jlink runtime image at build/image."

        dependsOn(":buildGhosttyNative")
        dependsOn(":buildNativeHost")

        // flatMap on the jar task's archiveFile carries the task dependency.
        appJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        runtimeClasspath.from(configurations.named("runtimeClasspath"))
        nativeDir.set(rootProject.layout.buildDirectory.dir("native"))
        icon.from(rootProject.layout.projectDirectory.file("assets/app-icon.icns"))
        launcherScript.set(packagingDir.file("launcher.sh"))
        infoPlist.set(packagingDir.file("Info.plist"))
        bundleTrampoline.set(packagingDir.file("bundle-trampoline.sh"))
        // jlink is invoked from the JDK 26 toolchain's own bin/ (not whatever
        // JVM is running Gradle -- Gradle 8.11.1 does not run on JDK 26), so
        // the image's module set matches the JDK the application actually
        // compiles/runs against.
        javaHomePath.set(
            javaToolchains.launcherFor(java.toolchain)
                .map { it.metadata.installationPath.asFile.absolutePath }
        )
        imageDir.set(rootProject.layout.buildDirectory.dir("image"))
        // Fail the build if jlink produced an image for an architecture other
        // than this machine's, rather than shipping a silently wrong-arch
        // bundle (see RuntimeImageTask.expectedMachOArch).
        expectedMachOArch.set(machOArchToken.getValue(hostArchLabel))
    }

    tasks.register<AppBundleTask>("appImage") {
        group = "distribution"
        description = "Stage 3: self-contained macOS .app bundle at build/dist/Drydock.app."

        // flatMap on runtimeImage's output carries the task dependency.
        imageDir.set(tasks.named<RuntimeImageTask>("runtimeImage").flatMap { it.imageDir })
        infoPlist.set(packagingDir.file("Info.plist"))
        distTrampoline.set(packagingDir.file("dist-trampoline.sh"))
        distDir.set(rootProject.layout.buildDirectory.dir("dist"))
    }

    tasks.register<DmgTask>("dmg") {
        group = "distribution"
        description = "Stage 4: distributable disk image at build/dist/Drydock.dmg (wraps the .app bundle)."

        // flatMap on appImage's output carries the task dependency, so
        // `./gradlew dmg` builds the .app first and wraps it in one step.
        // Points at the bundle rather than the dist root it sits in -- the .dmg
        // is written into that same root, and depending on the root would make
        // this task dirty its own input (see DmgTask.appBundle).
        appBundle.set(tasks.named<AppBundleTask>("appImage").flatMap { it.distDir.dir("Drydock.app") })
        volumeName.set("Drydock")
        dmgFile.set(rootProject.layout.buildDirectory.file("dist/Drydock.dmg"))
    }
}

// Windows-only block: a self-contained jlink runtime image with a .bat
// launcher. Mirrors runtimeImage in shape (delete -> jlink -> copy jars
// -> launcher) but skips libghostty / native-host copy, dock icon, and
// the .app bundle wrap -- none of which have a Windows analog. The
// output dir is build/image-windows/ to avoid colliding with the macOS
// runtimeImage's build/image/. See WindowsRuntimeImageTask's class
// Javadoc for the full rationale.
if (!isMacOsHost) {
    tasks.register<WindowsRuntimeImageTask>("windowsRuntimeImage") {
        group = "distribution"
        description = "Windows runtime image (jlink + drydock.bat launcher) at build/image-windows."

        appJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        runtimeClasspath.from(configurations.named("runtimeClasspath"))
        launcherScript.set(packagingDir.file("launcher.bat"))
        javaHomePath.set(
            javaToolchains.launcherFor(java.toolchain)
                .map { it.metadata.installationPath.asFile.absolutePath }
        )
        imageDir.set(rootProject.layout.buildDirectory.dir("image-windows"))
    }
}

// Cross-arch jlink runtime images: see docs/superpowers/specs/
// 2026-07-20-cross-arch-port-design.md. Opt-in -- does not affect
// runtimeImage/appImage/run in any way. Builds BOTH macOS architectures
// in one pass regardless of which one this machine actually is,
// downloading the non-host architecture's jmods via
// scripts/download-cross-jmods.sh and resolving its JavaFX jars via an
// explicit classifier (rather than the host-inferred one the javafx {}
// block in app/build.gradle.kts uses).
//
// Mac-only: there is no cross-arch story for the Windows image yet (a
// single Windows jlink output is enough for v1; the org.openjfx
// classifier for windows is the one the host build resolves anyway).
if (isMacOsHost && hostArchLabel != null) {

/** Applies the inputs every RuntimeImageTask instance needs regardless of architecture. */
fun RuntimeImageTask.configureCommonRuntimeImageInputs() {
    dependsOn(":buildGhosttyNative")
    dependsOn(":buildNativeHost")
    appJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    runtimeClasspath.from(configurations.named("runtimeClasspath"))
    nativeDir.set(rootProject.layout.buildDirectory.dir("native"))
    icon.from(rootProject.layout.projectDirectory.file("assets/app-icon.icns"))
    launcherScript.set(packagingDir.file("launcher.sh"))
    infoPlist.set(packagingDir.file("Info.plist"))
    bundleTrampoline.set(packagingDir.file("bundle-trampoline.sh"))
    javaHomePath.set(
        javaToolchains.launcherFor(java.toolchain)
            .map { it.metadata.installationPath.asFile.absolutePath }
    )
}

val allMacosArches = listOf("macos-x86_64", "macos-arm64")
val crossArchLabel = allMacosArches.first { it != hostArchLabel }

val downloadCrossJmods = tasks.register<DownloadCrossJmodsTask>("downloadCrossJmods") {
    group = "distribution"
    description = "Downloads and checksum-verifies the jmods bundle for the non-host macOS architecture."
    arch.set(crossArchLabel)
    scriptFile.set(rootProject.layout.projectDirectory.file("scripts/download-cross-jmods.sh"))
    outputDir.set(rootProject.layout.buildDirectory.dir("cross-jdk/$crossArchLabel"))
}

// org.openjfx's artifacts publish Gradle Module Metadata with per-platform
// variants (mac-aarch64Runtime, macRuntime, etc.) -- a bare classifier in
// the dependency notation is NOT enough to select among them on its own
// ("Cannot choose between the available variants" otherwise). The
// detached configuration needs the same org.gradle.native.operatingSystem/
// org.gradle.native.architecture attributes the javafx-gradle-plugin
// itself requests for the host build, pinned explicitly to the
// cross-linked architecture instead of the host's.
val crossFxClassifier = if (crossArchLabel == "macos-arm64") "mac-aarch64" else "mac"
val crossFxDeps = listOf("javafx-base", "javafx-controls", "javafx-graphics").map {
    dependencies.create("org.openjfx:$it:26:$crossFxClassifier")
}
val crossFxConfiguration = configurations.detachedConfiguration(*crossFxDeps.toTypedArray())
crossFxConfiguration.attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
    attribute(
        OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
        objects.named(OperatingSystemFamily::class.java, OperatingSystemFamily.MACOS)
    )
    attribute(
        MachineArchitecture.ARCHITECTURE_ATTRIBUTE,
        objects.named(
            MachineArchitecture::class.java,
            if (crossArchLabel == "macos-arm64") MachineArchitecture.ARM64 else MachineArchitecture.X86_64
        )
    )
}

val runtimeImageMacosX8664 = tasks.register<RuntimeImageTask>("runtimeImageMacosX8664") {
    group = "distribution"
    description = "Cross-arch jlink runtime image for macOS x86_64 at build/image-macos-x86_64."
    configureCommonRuntimeImageInputs()
    imageDir.set(rootProject.layout.buildDirectory.dir("image-macos-x86_64"))
    if ("macos-x86_64" != hostArchLabel) {
        dependsOn(downloadCrossJmods)
        crossFxJars.from(crossFxConfiguration)
        extraModulePath.from(downloadCrossJmods.flatMap { it.outputDir.dir("jmods") })
        expectedMachOArch.set(machOArchToken["macos-x86_64"])
    }
}

val runtimeImageMacosArm64 = tasks.register<RuntimeImageTask>("runtimeImageMacosArm64") {
    group = "distribution"
    description = "Cross-arch jlink runtime image for macOS arm64 at build/image-macos-arm64."
    configureCommonRuntimeImageInputs()
    imageDir.set(rootProject.layout.buildDirectory.dir("image-macos-arm64"))
    if ("macos-arm64" != hostArchLabel) {
        dependsOn(downloadCrossJmods)
        crossFxJars.from(crossFxConfiguration)
        extraModulePath.from(downloadCrossJmods.flatMap { it.outputDir.dir("jmods") })
        expectedMachOArch.set(machOArchToken["macos-arm64"])
    }
}

tasks.register("runtimeImageAllArches") {
    group = "distribution"
    description = "Cross-links jlink runtime images for BOTH macOS architectures (x86_64 and " +
        "arm64) in one pass at build/image-macos-x86_64 and build/image-macos-arm64. Does not " +
        "execute the foreign-architecture binary -- only its Mach-O architecture tag is " +
        "verified; real execution verification is CI's job."
    dependsOn(runtimeImageMacosX8664, runtimeImageMacosArm64)
}
}
