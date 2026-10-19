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
//                                            + the platform glue tests
//                                            (BasePlatformTestCase via the
//                                            Vintage engine); the plugin
//                                            configures this task as a
//                                            platform test
//
// The probe's local-IDE compile hack (platformCompileOnly fileTree +
// drydock.intellijHome) is gone: the IDE is resolved as a real dependency,
// which is what brings buildPlugin/verifyPlugin/runIde with it.

import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
    // `test` keeps the Gradle default: it extends `main` (the platform
    // glue) and therefore sees core (via main's implementation), the glue
    // classes, and the resolved IDE on its compile classpath. The platform
    // glue tests (BasePlatformTestCase subclasses) live in the same source
    // set as the core suite; the core suite still never touches platform
    // APIs (enforced by the import grep + the pure coreCompileClasspath
    // gates), it merely runs in a JVM that also has them available.
    // An earlier design kept the glue tests in a separate `platformTest`
    // source set driven by a custom testIde entry, but the platform plugin's
    // runtime-fix wiring for such entries is Kotlin-internal to the plugin:
    // a BasePlatformTestCase boot could not resolve the IDE's lib/modules
    // descriptors (NPE creating the app MessageBus), and no external hook
    // reaches it. The standard source set gets the full wiring for free.
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

    // Test suite: JUnit 5 (junit-bom 5.11.4, as in the probe and the app
    // build) plus the Vintage engine so JUnit3-style BasePlatformTestCase
    // tests run on the same task; junit:junit provides the junit.framework
    // TestCase API those tests are compiled against (the IDE distribution
    // ships no junit4 jar).
    "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testImplementation"("junit:junit:4.13.2")
    "testRuntimeOnly"("org.junit.vintage:junit-vintage-engine")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        intellijIdeaUltimate("2025.3.3") // build 253.31033.145, verified local IU
        bundledPlugin("org.jetbrains.plugins.terminal")
        // The New task dialog's start-point picker is Git's own (GitRefDialog).
        bundledPlugin("Git4Idea")
        // testFramework declared in the dependencies block is what wires the
        // standard `test` task: BasePlatformTestCase + the full test-runtime
        // environment (sandbox dirs, idea.home.path, the platform classpath)
        // without any custom testIde entry.
        testFramework(TestFrameworkType.Platform)
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
    // The sandbox IDE keeps its own registered-projects list: trying the
    // tool window out must not add scratch repositories to the list the
    // user's real Drydock app and IDE share (~/.drydock/projects.json).
    // Seeding still reads the real app's list, read-only.
    named<JavaExec>("runIde") {
        val sandboxProjects = layout.buildDirectory.file("sandbox-projects/projects.json")
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Ddrydock.projectsFile=" + sandboxProjects.get().asFile.absolutePath)
        })
    }
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
    // The IntelliJ Platform Gradle Plugin preconfigures the standard `test`
    // task as a PLATFORM test (sandbox classpath, instrumented test code,
    // JBR launcher, system properties like
    // java.system.class.loader=PathClassLoader): BasePlatformTestCase
    // subclasses in this source set need exactly that. An earlier design
    // reverted the takeover so the core suite would run platform-free in a
    // separate task; the suites are merged now (see the sourceSets comment)
    // and the takeover stays.
    useJUnitPlatform()
    withGitHostIsolation()
    // A platform test starts the real DrydockPluginService, which seeds and
    // follows the shared projects list. Without this it seeded the user's
    // real ~/.drydock/projects.json from their app's state.
    val testProjects = layout.buildDirectory.file("test-projects/projects.json")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Ddrydock.projectsFile=" + testProjects.get().asFile.absolutePath)
    })
}
