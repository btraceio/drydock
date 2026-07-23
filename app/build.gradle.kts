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

group = "io.btraceio"
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

// Thin, publishable jar for the jbang launcher: the app's own classes and
// resources (including icon/drydock.png) plus both macOS arch slices of the
// two native dylibs. JavaFX/richtextfx are NOT bundled -- they are declared as
// classifier-less POM dependencies (see the publishing block) so jbang
// resolves the host-correct classifier at run time.
tasks.register<Jar>("jbangJar") {
    group = "distribution"
    description = "Natives-bundled jar for `jbang drydock@...` (io.btraceio:drydock)."
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

publishing {
    publications {
        create<MavenPublication>("drydock") {
            groupId = "io.btraceio"
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
