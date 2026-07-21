plugins {
    application
    id("org.openjfx.javafxplugin")
    // Spike source set + gateNSpike/ffmSmokeTest tasks (buildSrc convention
    // plugin; see buildSrc/src/main/kotlin/drydock.spikes.gradle.kts).
    id("drydock.spikes")
    // runtimeImage/appImage packaging (typed tasks + templates under
    // app/packaging/; see buildSrc/src/main/kotlin/drydock.packaging.gradle.kts).
    id("drydock.packaging")
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
    mainClass.set("app.drydock.Main")

    // Required once native (libghostty) access is introduced; harmless no-op
    // until then. Kept here now so the JVM argument list is version
    // controlled from the start, per plan section 22 ("Keep JVM arguments
    // in version-controlled build configuration").
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=false",
        // Required now that DrydockApplication (Milestone 5's terminal-tabs UI)
        // loads libghostty/the native host shim via FFM.
        "--enable-native-access=ALL-UNNAMED",
        // Unlike the gateNSpike tasks (drydock.spikes plugin; they force
        // classpath-mode JavaFX via the spike source set's runtimeClasspath,
        // avoiding JPMS enforcement entirely), the `application`/`javafx`
        // Gradle plugins configure `run` to launch JavaFX on the *module
        // path* (--module-path + --add-modules), matching the jlink runtime
        // image's own module-path setup. That means
        // app.drydock.terminal.host.JavaFxNativeView (in this app's classes,
        // an unnamed module since the app itself isn't modularized) needs
        // an explicit --add-exports to reach into javafx.graphics's
        // internal com.sun.glass.ui package -- exactly the same flag
        // app/packaging/launcher.sh already carries for the jlink image.
        // Without this, `./gradlew run` throws IllegalAccessError the first
        // time a terminal tab is opened (this was missed when Milestone 5
        // wired the terminal-tabs UI into the real app, since the `run`
        // task's module-path-vs-classpath distinction from the spike tasks
        // was overlooked).
        "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"
    )
}

repositories {
    mavenCentral()
}

dependencies {
    // Read-only code viewer in the Session Explorer (syntax highlighting +
    // line-number gutter). Pulls flowless/reactfx/undofx/wellbehavedfx
    // transitively; all run from the plain classpath like the JavaFX jars.
    // 0.11.6+ is required on JavaFX 24+: TextFlow.getUnderlineShape(int, int)
    // became final, and older TextFlowExt overrode it, so loading the class
    // for the first rendered paragraph threw IncompatibleClassChangeError.
    implementation("org.fxmisc.richtext:richtextfx:0.11.7")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    // The real application now embeds Ghostty terminal surfaces (Milestone
    // 5's terminal-tabs UI), so `run` needs both native libraries built
    // first, same as every gateNSpike task (drydock.spikes plugin).
    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir = rootProject.projectDir

    // Diagnostic support: forward -Papp.drydock.diag.* project properties to
    // the run task's application JVM as system properties (the Gradle
    // client's own -D flags never reach the forked app JVM; same pattern
    // as gate0eSpike's property forwarding in the drydock.spikes plugin). Used by
    // automated visual verification: app.drydock.diag.stateFile isolates
    // persisted state to a throwaway file, and
    // app.drydock.diag.autoCreateSession=true plus app.drydock.diag.repo=<path>
    // auto-registers a repository and opens a session in it on startup
    // (see DrydockApplication.start).
    project.properties.forEach { (key, value) ->
        if (key.startsWith("app.drydock.diag.") && value != null) {
            systemProperty(key, value.toString())
        }
    }

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
