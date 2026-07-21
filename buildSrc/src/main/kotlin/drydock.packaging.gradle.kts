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
import drydock.tasks.RuntimeImageTask

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
