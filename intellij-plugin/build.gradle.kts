// IntelliJ port feasibility probe (docs/IntellijPluginFeasibility.md §5, v0).
// Standalone Gradle project on purpose: it is NOT part of Drydock's build and
// shares nothing with it. The extracted packages keep their `app.drydock.*`
// names so the extraction diff stays reviewable against the originals.
//
// The IntelliJ Platform Gradle plugin is NOT applied here: applying it forces a
// ~1GB IDE download before anything compiles, which turned the probe into a
// 9-minute wait. The probe's first question — does the extracted core compile
// and test green under JBR's Java 21 — is answered by this plain-java build.
// The platform glue lives in the `platform` source set and compiles against a
// LOCAL IntelliJ install (ideaPlatformDir, default /Applications/IntelliJ IDEA.app
// /Contents), which is also the IDE the probe targets: the installed 2025.3.3
// (build 253) carries the public Reworked Terminal API.
plugins {
    id("java")
}

group = "io.btrace"
version = "0.1.0"

java {
    toolchain {
        // IntelliJ 2025.x runs on JBR 21; the extracted core must fit inside it.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Local IntelliJ install the platform glue compiles against (read-only; the
// probe never builds or packages a distributable yet).
val ideaPlatformDir: String =
    (findProperty("drydock.intellijHome") as String?) ?: "/Applications/IntelliJ IDEA.app/Contents"
val ideaLibJars = fileTree("$ideaPlatformDir/lib")

sourceSets {
    create("platform") {
        java {
            srcDir("src/platform/java")
            compileClasspath += sourceSets.main.get().output
        }
        resources {
            srcDir("src/platform/resources")
        }
    }
}

dependencies {
    // Platform glue compile classpath: every jar of the local IDE's lib/ plus
    // the core output. Good enough to typecheck a tool window + configurable;
    // a real build switches to the IntelliJ Platform Gradle plugin, which also
    // brings the Plugin Verifier and sandbox runIde.
    "platformCompileOnly"(ideaLibJars)

    // Structural parsing for the review change graph. Same artifacts and
    // versions as app/build.gradle.kts; a missing grammar degrades to the
    // lexical path (GrammarRegistry), it is not an error.
    implementation("io.github.bonede:tree-sitter:0.25.3")
    implementation("io.github.bonede:tree-sitter-java:0.23.4")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-python:0.23.4")
    implementation("io.github.bonede:tree-sitter-javascript:0.23.1")
    implementation("io.github.bonede:tree-sitter-typescript:0.23.2")
    implementation("io.github.bonede:tree-sitter-go:0.23.3")
    implementation("io.github.bonede:tree-sitter-rust:0.23.1")
    implementation("io.github.bonede:tree-sitter-c:0.23.2")
    implementation("io.github.bonede:tree-sitter-cpp:0.23.4")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // JBR 21 is the floor for IntelliJ 2025.x: the extracted core must not use
    // anything newer, or the port silently depends on a runtime the IDE can
    // never provide.
    options.release.set(21)
}

tasks.named<JavaCompile>("compilePlatformJava") {
    // compileOnly for a secondary source set is wired by the platformCompileOnly
    // configuration above; nothing extra needed here.
}

tasks.test {
    useJUnitPlatform()

    // Same host-isolation contract as app/build.gradle.kts: the git
    // integration tests must not inherit the developer's global git config
    // (signing agents, hooks) or every fixture commit becomes a host problem.
    // GIT_CONFIG_* env injection overrides file config (global/local) but is
    // itself overridden by an explicit `-c`, so it cannot mask a test that
    // deliberately enables signing. Set for the whole JVM, as above, so
    // every spawned git inherits it.
    val isolatedGlobalGitConfig = temporaryDir.resolve("global-gitconfig")
    environment("GIT_CONFIG_GLOBAL", isolatedGlobalGitConfig.absolutePath)
    environment("GIT_CONFIG_COUNT", "3")
    environment("GIT_CONFIG_KEY_0", "commit.gpgsign")
    environment("GIT_CONFIG_VALUE_0", "false")
    // Pin a fixture identity too: with user.* unset git auto-detects the
    // committer (getpwuid + a hostname lookup) for every reflog write, and
    // on a host whose hostname does not resolve that costs ~5s per spawned
    // git child — three children per `worktree add`, which trips the 15s
    // command timeout and fails the worktree tests for no good reason.
    environment("GIT_CONFIG_KEY_1", "user.name")
    environment("GIT_CONFIG_VALUE_1", "Drydock Fixture")
    environment("GIT_CONFIG_KEY_2", "user.email")
    environment("GIT_CONFIG_VALUE_2", "fixture@drydock.invalid")
    doFirst {
        isolatedGlobalGitConfig.parentFile.mkdirs()
        isolatedGlobalGitConfig.writeText("")
    }
}
