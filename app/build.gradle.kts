import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    application
    id("org.openjfx.javafxplugin")
    // Spike source set + gateNSpike/ffmSmokeTest tasks (buildSrc convention
    // plugin; see buildSrc/src/main/kotlin/drydock.spikes.gradle.kts).
    id("drydock.spikes")
    // runtimeImage/appImage packaging (typed tasks + templates under
    // app/packaging/; see buildSrc/src/main/kotlin/drydock.packaging.gradle.kts).
    id("drydock.packaging")
    // Central Portal Publisher API + in-memory signing. The `.base` variant
    // does NOT auto-create a publication from components["java"] (which would
    // leak JavaFX host-specific classifiers into the POM); we keep the custom
    // `drydock` publication below and let vanniktech handle transport+signing.
    id("com.vanniktech.maven.publish.base") version "0.35.0"
}

group = "io.btrace"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
    withSourcesJar()
    withJavadocJar()
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

// The source is UTF-8 (smart quotes, arrows, and box-drawing symbols in
// comments and UI strings) but JavaCompile defaults to the platform encoding,
// which is windows-1252 on Windows. Without this pin :app:compileJava fails
// on a Windows runner with dozens of "unmappable character for encoding
// windows-1252" errors -- a latent bug the Windows jeditermSpike CI job
// surfaced (macOS only passed by accident: its default encoding is UTF-8).
// Covers every source set (main, test, spike) in this module.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

application {
    mainClass.set("app.drydock.Main")

    // Required once native (libghostty) access is introduced; harmless no-op
    // until then. Kept here now so the JVM argument list is version
    // controlled from the start, per plan section 22 ("Keep JVM arguments
    // in version-controlled build configuration").
    // -Xdock:name=Drydock is a macOS-only dock-tile label (the menu-bar /
    // Cmd-Tab app name is set separately by Main's early AWT init). The
    // HotSpot JVM rejects it on Windows with "Unrecognized option:
    // -Xdock:name=Drydock" at startup, which makes ./gradlew run and the
    // generated bin/drydock script abort before Application.launch. The
    // dock tile has no analog on Windows, so the arg is omitted there.
    val isMacOsHost = System.getProperty("os.name", "").let {
        it.startsWith("Mac OS X") || it.startsWith("macOS")
    }
    applicationDefaultJvmArgs = buildList {
        add("-Dfile.encoding=UTF-8")
        add("-Djava.awt.headless=false")
        if (isMacOsHost) {
            add("-Xdock:name=Drydock")
        }
        // Required now that DrydockApplication (Milestone 5's terminal-tabs UI)
        // loads libghostty/the native host shim via FFM.
        add("--enable-native-access=ALL-UNNAMED")
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
        // was overlooked). Harmless on Windows -- the module still exists
        // in the JDK 26 jmods set even when nothing reaches into it.
        add("--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED")
    }
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

    // Cross-platform terminal backend (JediTermFX + pty4j) used on Windows,
    // where the macOS libghostty Metal surface / AppKit host shim cannot run
    // (see docs/windows-terminal-spike.md). Compiled into the app jar on
    // every platform -- the JediTermFX impls are main code selected by
    // TerminalFactory at runtime -- so the deps are on main's classpath, not
    // spike-only. jeditermfx-ui transitively pulls jeditermfx-core + pty4j
    // (the PTY; ConPTY on Windows, forkpty on macOS/Linux) + JNA + kotlin-stdlib.
    // org.openjfx excluded: the project already provides JavaFX 26 via the
    // javafx-gradle-plugin; jeditermfx-ui's POM otherwise resolves an older
    // Linux-default-classifier JavaFX that clashes. pty4j forced to 0.13.10:
    // jeditermfx-ui 1.1.0 pulls 0.12.25 whose purejavacomm:0.0.11.1 transitive
    // is not on Maven Central, and 0.13.10 dropped it; the public PTY API is
    // stable and jeditermfx-ui does not call pty4j directly. slf4j-jdk14 routes
    // JediTermFX's slf4j logs through java.util.logging (the app's logger).
    implementation("com.techsenger.jeditermfx:jeditermfx-ui:1.1.0") {
        exclude("org.openjfx")
    }
    implementation("org.jetbrains.pty4j:pty4j:0.13.10")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.13")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Headless JavaFX harness (see the Monocle block in tasks.test). TestFX
    // supplies the robot and the node-query DSL; Monocle supplies a Glass
    // platform that needs no window server, so view tests run identically on
    // a developer's Mac and on a CI runner with no GUI session. hamcrest is
    // explicit because testfx-core exposes org.hamcrest.Matcher in its public
    // API but only declares the dependency at runtime scope.
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.testfx:testfx-core:4.0.18")
    testImplementation("org.testfx:testfx-junit5:4.0.18")
    testRuntimeOnly("org.testfx:openjfx-monocle:21.0.2")
}

tasks.test {
    useJUnitPlatform()

    // Headless JavaFX. Monocle replaces the platform's Glass backend with one
    // that renders to memory, so a test can show a real Stage, apply the real
    // stylesheets and drive real key events without a window server -- which
    // is what lets the view tests run on the macos-14 CI runner.
    //
    // Set for the whole test JVM rather than per test class: these are read
    // once at toolkit startup, and Gradle runs one JVM for the suite. The
    // non-FX tests are unaffected.
    //
    // headless.geometry is load-bearing: Monocle's default virtual screen is
    // 1280x800, and a Scene larger than the screen overflows the software
    // pixel buffer (BufferOverflowException from the Prism SW pipeline). The
    // Review view's rails only stay expanded above 1320px wide, so the
    // virtual screen has to be bigger than the widest scene any test builds.
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("prism.order", "sw")
    systemProperty("javafx.headless", "true")
    systemProperty("headless.geometry", "1920x1200-32")


    // Inputs for RuntimeImageModuleListTest: the packaged app jar, its runtime
    // classpath, and the convention-plugin source that declares the jlink
    // --add-modules list. The test runs jdeps against the jar the packaging
    // actually consumes and fails if the declared list stops covering it (a
    // missing java.logging once shipped a .dmg that crashed at launch).
    val appJarProvider = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val moduleListSource = rootProject.layout.projectDirectory.file(
        "buildSrc/src/main/kotlin/drydock/tasks/RuntimeImageTask.kt"
    )
    dependsOn(appJarProvider)
    inputs.file(appJarProvider).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(moduleListSource).withPathSensitivity(PathSensitivity.RELATIVE)
    doFirst {
        systemProperty("drydock.test.appJar", appJarProvider.get().asFile.absolutePath)
        systemProperty("drydock.test.runtimeClasspath", runtimeClasspath.get().asPath)
        systemProperty("drydock.test.moduleListSource", moduleListSource.asFile.absolutePath)
    }
}

// Thin, publishable jar for the jbang launcher: the app's own classes and
// resources (including icon/drydock.png) plus both macOS arch slices of the
// two native dylibs. JavaFX/richtextfx are NOT bundled -- they are declared as
// classifier-less POM dependencies (see the publishing block) so jbang
// resolves the host-correct classifier at run time.
tasks.register<Jar>("jbangJar") {
    group = "distribution"
    description = "Natives-bundled jar for `jbang drydock@...` (io.btrace:drydock)."
    archiveBaseName.set("drydock")
    archiveClassifier.set("")

    dependsOn(rootProject.tasks.named("buildGhosttyNative"))
    dependsOn(rootProject.tasks.named("buildNativeHost"))
    // With -Pnatives.prebuilt=true the two build tasks above are skipped; this
    // verifies the downloaded dylibs are present so the jar is never packaged
    // with missing natives.
    dependsOn(rootProject.tasks.named("checkPrebuiltNatives"))

    from(sourceSets.main.get().output)

    val nativeDir = rootProject.layout.buildDirectory.dir("native")
    listOf("macos-arm64", "macos-x86_64").forEach { arch ->
        from(nativeDir.map { it.dir(arch) }) {
            include("libghostty.dylib", "libdrydockterminalhost.dylib")
            into("native/$arch")
        }
    }

    // Ghostty is MIT-licensed; ship its notice inside the jar.
    from(rootProject.layout.projectDirectory.file("third_party/ghostty/LICENSE")) {
        into("META-INF/licenses")
        rename { "LICENSE-ghostty.txt" }
    }

    manifest {
        attributes(
            "Implementation-Title" to "Drydock",
            "Implementation-Version" to project.version.toString(),
            "Main-Class" to "app.drydock.launcher.JBangBootstrap"
        )
    }
}

tasks.named<JavaExec>("run") {
    // The macOS production app embeds Ghostty terminal surfaces (Milestone
    // 5's terminal-tabs UI), so on Mac `run` needs libghostty + the AppKit
    // host shim built first. Neither can be built on Windows -- Zig + Xcode
    // only -- and on Windows the JediTermFX/pty4j backend is the only path
    // that runs, so the native deps are skipped there. The dep is gated at
    // configuration time on os.name (matching the -Xdock:name gating in
    // applicationDefaultJvmArgs above), so a Windows `gradle :app:run`
    // proceeds straight to launching the app rather than failing the
    // ghostty build.
    val isMacOsHost = System.getProperty("os.name", "").let {
        it.startsWith("Mac OS X") || it.startsWith("macOS")
    }
    if (isMacOsHost) {
        dependsOn(rootProject.tasks.named("buildGhosttyNative"))
        dependsOn(rootProject.tasks.named("buildNativeHost"))
    }
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    workingDir = rootProject.projectDir

    // Diagnostic support: forward -Papp.drydock.diag.* and
    // -Papp.drydock.terminal.backend project properties to the run task's
    // application JVM as system properties (the Gradle client's own -D flags
    // never reach the forked app JVM; same pattern as bootSpike's property
    // forwarding in the drydock.spikes plugin). Used by automated visual
    // verification: app.drydock.diag.stateFile isolates persisted state to a
    // throwaway file, and app.drydock.diag.autoCreateSession=true plus
    // app.drydock.diag.repo=<path> auto-registers a repository and opens a
    // session in it on startup (see DrydockApplication.start).
    project.properties.forEach { (key, value) ->
        if (value == null) return@forEach
        if (key.startsWith("app.drydock.diag.") || key == "app.drydock.terminal.backend") {
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

publishing {
    publications {
        create<MavenPublication>("drydock") {
            groupId = "io.btrace"
            artifactId = "drydock"
            version = project.version.toString()

            // The natives-bundled jar is the main artifact -- NOT
            // components["java"], whose POM would leak the javafx-gradle
            // plugin's host-specific classifiers.
            artifact(tasks.named("jbangJar"))
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            pom {
                name.set("Drydock")
                description.set("Manage local Git repositories and the claude CLI sessions you run against them (macOS).")
                url.set("https://github.com/btraceio/drydock")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("jbachorik")
                        name.set("Jaroslav Bachorik")
                    }
                }
                scm {
                    url.set("https://github.com/btraceio/drydock")
                    connection.set("scm:git:https://github.com/btraceio/drydock.git")
                }
                // Runtime deps declared here, classifier-less, so jbang
                // resolves the host classifier + module path at run time.
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    fun runtimeDep(groupId: String, artifactId: String, dependencyVersion: String) {
                        val dependency = dependencies.appendNode("dependency")
                        dependency.appendNode("groupId", groupId)
                        dependency.appendNode("artifactId", artifactId)
                        dependency.appendNode("version", dependencyVersion)
                        dependency.appendNode("scope", "runtime")
                    }
                    runtimeDep("org.openjfx", "javafx-base", "26")
                    runtimeDep("org.openjfx", "javafx-graphics", "26")
                    runtimeDep("org.openjfx", "javafx-controls", "26")
                    runtimeDep("org.fxmisc.richtext", "richtextfx", "0.11.7")
                }
            }
        }
    }
    repositories {
        mavenLocal()
        // Central release repo is added when wiring the actual release
        // (Sonatype credentials via ~/.gradle/gradle.properties); out of scope
        // for local verification.
    }
}

tasks.named<Javadoc>("javadoc") {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// Central Portal Publisher API transport + signing for the custom `drydock`
// publication created above. Credentials come from Gradle properties / env
// (ORG_GRADLE_PROJECT_mavenCentralUsername/Password) and the in-memory GPG key
// (ORG_GRADLE_PROJECT_signingInMemoryKey/KeyId/KeyPassword). signAllPublications
// skips signing for publishToMavenLocal, so local/dry-run installs stay usable
// without keys.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
}
