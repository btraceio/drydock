plugins {
    application
    id("org.openjfx.javafxplugin")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

// JavaFX 26. The javafx-gradle-plugin resolves the correct platform
// classifier (mac / mac-aarch64) for the JVM actually running the build,
// which already gives us dev-time support on both Intel and Apple Silicon
// Macs. Packaging for BOTH architectures at once (for the jlink runtime
// image) is handled explicitly in the packaging tasks added in later
// milestones (see docs/native-integration.md and section 23 of the plan) —
// that is where the dual-arch deviation from the plan actually lives, not
// here.
javafx {
    version = "26"
    modules = listOf("javafx.base", "javafx.controls", "javafx.graphics")
}

application {
    mainClass.set("app.cpm.Main")

    // Required once native (libghostty) access is introduced; harmless no-op
    // until then. Kept here now so the JVM argument list is version
    // controlled from the start, per plan section 22 ("Keep JVM arguments
    // in version-controlled build configuration").
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=false"
    )
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    // Allow running headless/offscreen for CI, e.g.:
    //   ./gradlew run -PheadlessTest
    if (project.hasProperty("headlessTest")) {
        systemProperty("java.awt.headless", "true")
        systemProperty("testfx.headless", "true")
        systemProperty("prism.order", "sw")
        systemProperty("glass.platform", "Monocle")
        systemProperty("monocle.platform", "Headless")
    }
}

// Gate 0B (plan section 7 / 28 "Task 4"): FFM smoke test. Loads libghostty
// via app.cpm.terminal.ghostty (the narrow native boundary package),
// initializes it, reads back its version info, exercises config
// new/free, and exits cleanly.
//
// The project is still a single Gradle module (`app`) during the Phase 0
// feasibility spike (see docs/implementation-plan.md section 5), so this is
// the "equivalent in the current module layout" of the plan's literal
// `:terminal-ghostty:ffmSmokeTest` task name. A root-level alias task below
// makes `./gradlew ffmSmokeTest` work too, matching that spelling once the
// module is eventually split out.
tasks.register<JavaExec>("ffmSmokeTest") {
    group = "verification"
    description = "Gate 0B: loads libghostty via FFM, calls ghostty_init/info/config, and exits cleanly."

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("app.cpm.terminal.ghostty.GhosttySmokeTest")

    // Run with the JDK 26 toolchain (not whatever JVM launched Gradle
    // itself, which may be an older JDK -- Gradle 8.11.1 does not yet run
    // on JDK 26). Classes here are compiled for 26 via `java.toolchain`
    // above, so they must also be *run* with a 26 launcher.
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))

    // --enable-native-access=ALL-UNNAMED (rather than naming the module) is
    // deliberate and matches plan section 6.4 exactly: "Use ALL-UNNAMED only
    // during the earliest non-modular spike." This project has no
    // module-info.java yet. Switch to
    // `--enable-native-access=app.cpm.terminal.ghostty` once the application
    // is modularized (plan section 6.4, "prefer a modular application once
    // native loading is stable").
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")

    // build/native lives at the Gradle root, not under app/; resolve
    // relative to a known-fixed directory rather than relying on JavaExec's
    // default working directory.
    workingDir = rootProject.projectDir
}
