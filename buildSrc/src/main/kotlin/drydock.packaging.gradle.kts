// Convention plugin: macOS packaging for the app project -- the jlink
// runtime image (Gate 0F) and the self-contained .app bundle (Stage 3).
// Applied by app/build.gradle.kts.
//
// The heavy lifting lives in the typed task classes
// buildSrc/src/main/kotlin/drydock/tasks/{RuntimeImageTask,AppBundleTask}.kt
// (injected ExecOperations/FileSystemOperations, precise inputs/outputs);
// the launcher script, Info.plist, and the two .app trampolines are
// verbatim template files under app/packaging/. Root-level alias tasks
// (`runtimeImage`, `appImage`, `macApp`) stay in the root build file.
//
// Both outputs live under the *root* build directory (build/image,
// build/dist -- not app/build/...) so the plan's literal acceptance
// command (section 7 "Gate 0F": `build/image/bin/drydock`,
// run from the repo root) matches exactly, the same way build/native
// (buildGhosttyNative/buildNativeHost, declared in the root build file)
// already does.

import drydock.tasks.AppBundleTask
import drydock.tasks.DownloadCrossJmodsTask
import drydock.tasks.RuntimeImageTask
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily

plugins {
    java
}

val packagingDir = layout.projectDirectory.dir("packaging")

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

// Cross-arch jlink runtime images: see docs/superpowers/specs/
// 2026-07-20-cross-arch-port-design.md. Opt-in -- does not affect
// runtimeImage/appImage/run in any way. Builds BOTH macOS architectures
// in one pass regardless of which one this machine actually is,
// downloading the non-host architecture's jmods via
// scripts/download-cross-jmods.sh and resolving its JavaFX jars via an
// explicit classifier (rather than the host-inferred one the javafx {}
// block in app/build.gradle.kts uses).

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

val hostOsArch = System.getProperty("os.arch", "").lowercase()
val hostArchLabel = when (hostOsArch) {
    "x86_64", "amd64" -> "macos-x86_64"
    "aarch64", "arm64" -> "macos-arm64"
    else -> throw org.gradle.api.GradleException(
        "Unrecognized host os.arch '$hostOsArch' (expected x86_64/amd64 or aarch64/arm64)."
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

val machOArchToken = mapOf("macos-x86_64" to "x86_64", "macos-arm64" to "arm64")

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
