// Drydock IntelliJ plugin build — v1 ("sessions") on the IntelliJ Platform
// Gradle Plugin 2.x, replacing the v0 probe's plain-java build. The directory
// layout stays exactly as the probe laid it out; the source sets are remapped
// instead of moving files so the extraction diff against the standalone app
// keeps staying reviewable:
//
//   core         = src/main/java          headless core, JBR-21 floor, ZERO
//                                            platform classpath (proven by
//                                            compileCoreJava, not by grep)
//   main         = src/platform/java      plugin glue, compiles against the
//                  src/platform/resources  platform; plugin.xml lives here
//   test         = src/test/java          the extracted core suite (JUnit 5)
//   platformTest = src/platform/test/java glue tests (JUnit Platform + the
//                                            Vintage engine, needed for the
//                                            JUnit3-style BasePlatformTestCase)
//
// The probe's local-IDE compile hack (platformCompileOnly fileTree +
// drydock.intellijHome) is gone: the IDE is resolved as a real dependency,
// which is what brings buildPlugin/verifyPlugin/runIde with it.
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.btrace"
version = "0.1.0"

java {
    toolchain {
        // IntelliJ 2025.x runs on JBR 21; the extracted core must fit inside it.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/platform/java"))
        resources.setSrcDirs(listOf("src/platform/resources"))
    }
    create("core") {
        java.setSrcDirs(listOf("src/main/java"))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test {
        // The core suite never sees the platform: its classpath is the core
        // output + core's own dependencies + JUnit 5 — NOT main's output.
        // Without this override `test` extends `main` by Gradle convention and
        // would drag the whole resolved IDE onto the suite's classpath.
        setCompileClasspath(sourceSets["core"].output + configurations["testCompileClasspath"])
        setRuntimeClasspath(output + sourceSets["core"].output + configurations["testRuntimeClasspath"])
    }
    create("platformTest") {
        java.setSrcDirs(listOf("src/platform/test/java"))
        setCompileClasspath(
            sourceSets["main"].output + sourceSets["core"].output + configurations["platformTestCompileClasspath"]
        )
        setRuntimeClasspath(
            output + sourceSets["main"].output + sourceSets["core"].output + configurations["platformTestRuntimeClasspath"]
        )
    }
}

// The headless core is packaged as its own jar: `main` compiles against it and
// the platform plugin packs it into the plugin zip next to the glue jar.
val coreJar = tasks.register<Jar>("coreJar") {
    archiveBaseName.set("drydock-core")
    archiveVersion.set("")
    from(sourceSets["core"].output)
    // patchPluginXml drops the patched plugin descriptor into EVERY source
    // set's resources output (build/resources/core, .../test, ...); the
    // plugin zip would carry two descriptors (drydock-core.jar + the glue
    // jar) and the Plugin Verifier rejects that structure. The core jar is
    // a library: it must not ship a descriptor.
    exclude("META-INF/plugin.xml")
}

// Lazy file dependency on the core jar (carries the task dependency on
// coreJar; resolved as a FileCollection, a notation DependencyHandler accepts).
val coreJarDependency = files(coreJar.map { it.archiveFile })

configurations {
    // `main` (glue) compiles and runs against the core jar + core's own
    // third-party dependencies; the IntelliJ Platform Gradle plugin adds the
    // resolved IDE and the bundled terminal plugin to `implementation`.
    named("implementation") {
        extendsFrom(configurations["coreImplementation"])
    }
    // Cut the default test→main wiring (see the sourceSets.test comment);
    // core's dependencies (tree-sitter) are all the suite needs besides JUnit.
    named("testImplementation") {
        setExtendsFrom(listOf(configurations["coreImplementation"]))
    }
    named("testRuntimeOnly") {
        setExtendsFrom(listOf())
    }
    // platformTest compiles against main + core + the platform. The platform
    // JARS enter main's classpath as a compileOnly dependency (they are
    // provided by the IDE at runtime); the standalone platformTest JVM has
    // no IDE, so its runtime needs them too.
    named("platformTestCompileOnly") {
        extendsFrom(configurations["compileOnly"])
    }
    named("platformTestCompileClasspath") {
        attributes {
            intellijPlatformAttributes()
        }
    }
    named("platformTestRuntimeClasspath") {
        attributes {
            intellijPlatformAttributes()
        }
    }
    named("platformTestImplementation") {
        extendsFrom(configurations["implementation"], configurations["coreImplementation"])
    }
    named("platformTestRuntimeOnly") {
        extendsFrom(
            configurations["implementation"],
            configurations["compileOnly"],
            configurations["runtimeOnly"],
            configurations["coreImplementation"],
            configurations["coreRuntimeOnly"],
        )
    }
}

dependencies {
    // Structural parsing for the review change graph — the ONLY third-party
    // dependency of the headless core. Same artifacts and versions as
    // app/build.gradle.kts; a missing grammar degrades to the lexical path
    // (GrammarRegistry), it is not an error. Declared on the core's own
    // configuration so compileCoreJava's classpath provably holds no platform
    // jar; `main` picks them up transitively and the platform plugin packs
    // them into the plugin zip.
    "coreImplementation"("io.github.bonede:tree-sitter:0.25.3")
    "coreImplementation"("io.github.bonede:tree-sitter-java:0.23.4")
    "coreImplementation"("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    "coreImplementation"("io.github.bonede:tree-sitter-python:0.23.4")
    "coreImplementation"("io.github.bonede:tree-sitter-javascript:0.23.1")
    "coreImplementation"("io.github.bonede:tree-sitter-typescript:0.23.2")
    "coreImplementation"("io.github.bonede:tree-sitter-go:0.23.3")
    "coreImplementation"("io.github.bonede:tree-sitter-rust:0.23.1")
    "coreImplementation"("io.github.bonede:tree-sitter-c:0.23.2")
    "coreImplementation"("io.github.bonede:tree-sitter-cpp:0.23.4")

    "implementation"(coreJarDependency)

    // Core unit suite (junit-bom 5.11.4, as in the probe and the app build).
    "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")

    // Glue tests: JUnit Platform with the Vintage engine on the runtime
    // classpath so JUnit3-style BasePlatformTestCase tests run on the same
    // task; junit:junit provides the junit.framework.TestCase API those
    // tests are compiled against (the IDE distribution ships no junit4 jar).
    "platformTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "platformTestImplementation"("org.junit.jupiter:junit-jupiter")
    "platformTestImplementation"("junit:junit:4.13.2")
    "platformTestRuntimeOnly"("org.junit.vintage:junit-vintage-engine")
    "platformTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        intellijIdeaUltimate("2025.3.3") // build 253.31033.145, verified local IU
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// buildSearchableOptions launches a headless IDE to index the plugin's
// configurable search texts; this plugin has no searchable configurables,
// and the headless instance exits 255 on this host. The task only feeds
// Marketplace search indexing -- disabling it does not affect the plugin zip.
tasks {
    named("buildSearchableOptions") {
        enabled = false
    }
}

// The IntelliJ Platform is resolved through variant attributes the platform
// plugin sets on `main`'s classpath configurations (extracted IDE + filtered
// plugin libraries); without them the IDE dependency resolves to a wrong
// variant. Mirror them onto the platformTest classpath configurations.
// (The plugin's own Attribute constants are internal, hence the literals.)
fun AttributeContainer.intellijPlatformAttributes() {
    attribute(Attribute.of("intellijPlatformExtracted", Boolean::class.javaObjectType), true)
    attribute(Attribute.of("intellijPlatformCollected", Boolean::class.javaObjectType), true)
    attribute(Attribute.of("intellijPlatformPluginLibrariesFiltered", Boolean::class.javaObjectType), true)
}

/**
 * Host-isolation contract for every test task (same as app/build.gradle.kts
 * and the probe build): the git integration tests must not inherit the
 * developer's global git config (signing agents, hooks) or every fixture
 * commit becomes a host problem. GIT_CONFIG_* env injection overrides file
 * config (global/local) but is itself overridden by an explicit `-c`, so it
 * cannot mask a test that deliberately enables signing. Set for the whole
 * JVM, so every spawned git child inherits it.
 *
 * The pinned fixture identity matters as much as the isolation: with user.*
 * unset, git auto-detects the committer (getpwuid + a hostname lookup) for
 * every reflog write, and on a host whose hostname does not resolve that
 * costs ~5s per spawned git child — three children per `worktree add` —
 * which trips the 15s command timeout and fails the worktree tests
 * (btraceio--drydock/main/drydock-intellij/
 * find-git-worktree-timeout-root-cause-ident-hostname-lookup). Losing this
 * block re-times-out the worktree tests.
 */
fun Test.withGitHostIsolation() {
    val isolatedGlobalGitConfig = temporaryDir.resolve("global-gitconfig")
    environment("GIT_CONFIG_GLOBAL", isolatedGlobalGitConfig.absolutePath)
    environment("GIT_CONFIG_COUNT", "3")
    environment("GIT_CONFIG_KEY_0", "commit.gpgsign")
    environment("GIT_CONFIG_VALUE_0", "false")
    environment("GIT_CONFIG_KEY_1", "user.name")
    environment("GIT_CONFIG_VALUE_1", "Drydock Fixture")
    environment("GIT_CONFIG_KEY_2", "user.email")
    environment("GIT_CONFIG_VALUE_2", "fixture@drydock.invalid")
    doFirst {
        isolatedGlobalGitConfig.parentFile.mkdirs()
        isolatedGlobalGitConfig.writeText("")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // JBR 21 is the floor for IntelliJ 2025.x: the extracted core must not use
    // anything newer, or the port silently depends on a runtime the IDE can
    // never provide.
    options.release.set(21)
}

tasks.test {
    val testSourceSet = sourceSets.getByName("test")

    // The IntelliJ Platform Gradle Plugin preconfigures the standard `test`
    // task as a PLATFORM test (its TestCompanion applies TestIdeTask's
    // configuration: sandbox classpath, instrumented test code, JBR launcher,
    // and system properties like java.system.class.loader=PathClassLoader
    // that a plain JVM cannot satisfy — with zero tests it even fails on the
    // missing org.junit.runners.model.Statement). For this project `test` is
    // the headless core suite, which must NOT see the platform (see the
    // sourceSets.test note); the platform test task is `platformTest` below.
    // Revert that takeover so the suite runs platform-free exactly as in the
    // probe.
    systemProperties.clear()
    jvmArgumentProviders.clear()
    classpath = testSourceSet.runtimeClasspath
    testClassesDirs = testSourceSet.output.classesDirs
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // Drop the plugin-added prerequisites (sandbox preparation, test-code
    // instrumentation, platform initialization): the suite needs none of
    // them, and its classpath carries its own build dependencies implicitly.
    val platformTestPrerequisites = setOf("prepareTest", "instrumentTestCode", "initializeIntellijPlatformPlugin")
    setDependsOn(
        dependsOn.filterNot { dep ->
            val name = when (dep) {
                is Task -> dep.name
                is TaskProvider<*> -> dep.name
                is String -> dep
                else -> null
            }
            name in platformTestPrerequisites
        }
    )

    useJUnitPlatform()
    withGitHostIsolation()
}

// The platform glue test task. It gets the full platform on its classpath
// through the platformTest source set's configurations (which extend `main`'s
// implementation configuration).
//
// NOTE for later tasks: this wiring compiles and runs plain JUnit tests
// against the platform JARS, but it does not yet provide the full IntelliJ
// test ENVIRONMENT a BasePlatformTestCase test needs (idea.home.path, sandbox
// with the plugin installed, PathClassLoader). When the first such test lands
// (T2's PluginProviderDiscoveryTest), the sanctioned upgrade path is the
// plugin's `intellijPlatformTesting.testIde` container: registering a
// `testIde` entry named "platformTest" creates a TestIdeTask with exactly
// that environment wired (see IntelliJPlatformTestingExtension in the plugin
// source); the junit/vintage deps would then move to that entry's test
// dependencies configuration.
tasks.register<Test>("platformTest") {
    group = "verification"
    description = "Runs the platform glue tests (JUnit 5 + Vintage for BasePlatformTestCase)."
    testClassesDirs = sourceSets["platformTest"].output.classesDirs
    classpath = sourceSets["platformTest"].runtimeClasspath
    useJUnitPlatform()
    withGitHostIsolation()
}
