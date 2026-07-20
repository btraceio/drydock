# Build decomposition and spike relocation — design

Work pack: `refactor/build-and-spikes`. Scope: (1) move the Phase-0 spike
harnesses out of the shipped jar, (2) decompose `app/build.gradle.kts`
(~610 lines) into buildSrc convention plugins with typed, configuration-
cache-friendly packaging tasks, (3) deduplicate the terminal native
loader/binding layer, (4) remove the machine-specific
`org.gradle.java.home` pin.

## 1. Spike source set (`src/spike/java`)

A dedicated `spike` source set in the `app` project holds every Phase-0
harness: the six `Gate0{c,d,e}Spike`/`*Launcher` classes **and**
`GhosttySmokeTest` (Gate 0B — equally a spike harness per AGENTS.md "Code
placement and hygiene"; it lives in `app.cpm.terminal.ghostty` and keeps
package-private access to `GhosttyBinding` because package membership, not
source-set membership, governs Java access).

Wiring (in the `cpm.spikes` convention plugin):

- `compileClasspath += main.output + main.compileClasspath`
- `runtimeClasspath += spike.output + main.runtimeClasspath`

This sees main's classes plus its full dependency set (JavaFX jars
resolved by the javafx-gradle-plugin land on main's configurations) with
no extra dependency declarations. The `jar` task only packages
`main.output`, so the spike classes drop out of the app jar and therefore
out of the runtime image / .app bundle automatically — the image copies
`jar` + `runtimeClasspath`, neither of which references spike output.

The four gate tasks (`ffmSmokeTest`, `gate0cSpike`, `gate0dSpike`,
`gate0eSpike`) switch `classpath` to `sourceSets["spike"].runtimeClasspath`
and otherwise keep their exact JVM args, property forwarding, and
`dependsOn` on the root native-build tasks.

Fixes applied while moving (the pack owns the spikes, so this is safe):

- `Gate0cSpike` inline FQNs (`java.util.Set`, `javafx.animation.Timeline`
  / `KeyFrame`, `javafx.util.Duration`) become imports (memory rule /
  AGENTS.md "no inline FQNs").
- `Gate0eSpike` interpolated `CLAUDE_BIN` unquoted into a
  `/bin/zsh -l -c "... exec ${CLAUDE_BIN}"` string (~line 335) — the exact
  pattern its own class Javadoc forbids. libghostty's embedded `command`
  field is *inherently* a shell string (`sh -c`, see
  `GhosttySurface.create`'s Javadoc), so literal argv-list spawning is not
  expressible through the surface API; the fix mirrors the production
  pattern (`SessionManager.shellQuote`): every interpolation of
  `CLAUDE_BIN` into a command string goes through a POSIX single-quote
  helper, and the env-probe step exports the marker via the quoted
  wrapper without splicing raw property values. This removes the
  injection/quoting hazard while keeping the spike runnable.

## 2. buildSrc convention plugins

`buildSrc` (Kotlin DSL, `kotlin-dsl` plugin) with two precompiled script
plugins plus two typed task classes:

- **`cpm.packaging`** — registers `runtimeImage` and `appImage` in `app`,
  implemented as `RuntimeImageTask` / `AppBundleTask` (abstract
  `DefaultTask` subclasses in `buildSrc/src/main/kotlin`) with injected
  `ExecOperations` and `FileSystemOperations`. No `project.*` access at
  execution time: `project.exec`/`project.delete`/`project.copy` (the
  deprecated, configuration-cache-hostile calls) become
  `execOps.exec`/`fsOps.delete`/`fsOps.copy`. All inputs are `Property`/
  `ConfigurableFileCollection`: app jar, runtime classpath, native dir,
  icon, template files, toolchain java home (as an `@Input` path string +
  `@Internal` provider, same fingerprint as today).
- **`cpm.spikes`** — creates the `spike` source set and registers the four
  gate tasks. Property forwarding uses `providers.gradleProperty` (CC
  input tracking) instead of `project.hasProperty` where practical.

Script/plist content moves from Kotlin string literals to verbatim
template files under `app/packaging/`:

- `launcher.sh` — the 35-line runtime-image launcher (today built as a
  Kotlin string with `${'$'}` escapes; it has **zero** build-time
  substitutions, so it becomes a plain file copied + chmodded).
- `Info.plist` — shared by both bundles (also no substitutions).
- `bundle-trampoline.sh` / `dist-trampoline.sh` — the two thin bash
  trampolines (differ only in the relative path to `bin/`).

These are declared `@InputFiles` on the tasks, so editing a template
correctly invalidates the outputs. Target: `app/build.gradle.kts` keeps
only the application/javafx/dependency/test/`run` configuration —
well under 200 lines.

The root `build.gradle.kts` aliases (`runtimeImage`, `appImage`, …) and
native `Exec` tasks stay where they are (they are small, root-scoped, and
already input/output-correct per AGENTS.md's Gradle rule).

## 3. Terminal loader/binding dedup

- **Shared locator**: `app.cpm.terminal.NativeLibraryLocator` — one
  helper holding the verbatim-duplicated logic from
  `GhosttyNativeLibrary` / `CpmTerminalHostLibrary`
  (`resolveLibraryPath` / `detectArchDirectoryName` /
  `findBuildNativeDirectory`), parameterized by (system property name,
  dylib file name, "run this Gradle task first" hint). It must be
  `public` (Java has no sub-package visibility and both facades live in
  child packages) but is documented as internal to the narrow native
  boundary and exposes no FFM types — it only resolves `Path`s. The two
  public facades stay as thin wrappers so `MainWorkspace` (owned by
  another pack) and the packaging property contract
  (`app.cpm.ghostty.nativeDir` / `app.cpm.terminalhost.nativeDir`) are
  untouched.
- **Binding singletons**: `GhosttyBinding`, `GhosttyAppBinding`, and
  `CpmTerminalHostBinding` gain a `static of(SymbolLookup)` accessor
  backed by a `ConcurrentHashMap<SymbolLookup, Binding>`
  (`computeIfAbsent`), constructors go private. Since each library's
  `SymbolLookup` is already a process-wide singleton, each binding links
  its ~20 downcall handles once per process instead of once per
  `GhosttyApp` / `CpmTerminalHost` instance. Call sites updated:
  `GhosttyApp.ensureProcessInitialized`/`create`,
  `CpmTerminalHost.createForCurrentWindow`, `GhosttySmokeTest`.
  `GhosttySurface`, `OpenSessionTab`, `MainWorkspace` are not touched
  (another pack owns them); they only consume bindings via `GhosttyApp`,
  so they get the sharing for free.

## 4. Daemon JVM criteria instead of `org.gradle.java.home`

`gradle.properties` pins this machine's absolute JDK 17 path. Gradle
8.11 supports daemon JVM criteria: `gradle/gradle-daemon-jvm.properties`
with `toolchainVersion=17` makes every `gradlew` invocation locate a
local JDK 17 via the standard toolchain detection (no absolute path, no
`~` limitation). The explanatory comment (why 17, why not 24/26) moves
into that file. Risk: criteria-based daemon selection is incubating in
8.11 and does not auto-provision a missing JDK 17 (no
`toolchainDownloadUrl` configured) — same failure mode as today's
absolute path on a machine without JDK 17, but now with a clear Gradle
error and machine independence.

## Risks

- **Configuration cache**: the goal is "improved", not "guaranteed on":
  third-party plugins (org.openjfx.javafxplugin 0.1.0) and the root
  `providers.exec` fingerprinting may still block full CC adoption.
  Verified via `./gradlew --configuration-cache help`; remaining
  incompatibilities get documented, not forced.
- **Packaging behavior equivalence**: `runtimeImage`/`appImage` are
  exercised end-to-end only if the ghostty native build succeeds on this
  machine (Zig 0.15 + Xcode Metal toolchain, submodule freshly
  initialized in this worktree); otherwise fall back to task-graph
  configuration checks (`--dry-run`) and document what did not run.
- **Spike classpath drift**: spikes now compile in their own compilation
  unit; a `compileSpikeJava` run is added to the per-step verification
  loop so main-side refactors (e.g. the binding singletons) cannot
  silently break them.
- **`zsh -c` fix limits**: quoting (not argv-list) is the strongest fix
  available through libghostty's shell-string `command` field; the spike
  documents this constraint at the call site.

## Step plan (build stays green after every step)

1. **Daemon JVM criteria.** Add `gradle/gradle-daemon-jvm.properties`
   (`toolchainVersion=17` + migrated comment), drop `org.gradle.java.home`
   from `gradle.properties`. Verify: `./gradlew help compileJava`.
2. **Spike source set + move (in `app/build.gradle.kts` first).** Create
   the `spike` source set inline, `git mv` the six `Gate0*` files and
   `GhosttySmokeTest` to `app/src/spike/java/...`, fix `Gate0cSpike`'s
   inline FQNs and `Gate0eSpike`'s unquoted `CLAUDE_BIN` interpolation,
   repoint the four gate tasks at the spike runtime classpath. Verify:
   `./gradlew compileJava compileSpikeJava test`, `jar tf` shows no
   `Gate0`/`GhosttySmokeTest` classes, gate tasks configure (`--dry-run`).
3. **buildSrc + `cpm.spikes`.** Introduce `buildSrc` (kotlin-dsl), move
   the source-set creation and the four gate task registrations into the
   `cpm.spikes` precompiled plugin; apply it from `app`. Verify: same as
   step 2.
4. **`cpm.packaging` templates + typed tasks.** Extract `launcher.sh`,
   `Info.plist`, `bundle-trampoline.sh`, `dist-trampoline.sh` to
   `app/packaging/`; add `RuntimeImageTask`/`AppBundleTask` (injected
   `ExecOperations`/`FileSystemOperations`); register both tasks from the
   `cpm.packaging` plugin; delete the old ad-hoc `doLast` tasks and the
   two Kotlin string builders. Verify: `compileJava test`,
   `runtimeImage`/`appImage` (executed if native libs built, else
   `--dry-run`), output layout diffed against the pre-refactor image if
   executed.
5. **Native loader dedup.** Add `app.cpm.terminal.NativeLibraryLocator`;
   shrink `GhosttyNativeLibrary`/`CpmTerminalHostLibrary` to facades.
   Verify: `compileJava compileSpikeJava test`.
6. **Binding singletons.** `of(SymbolLookup)` +
   `ConcurrentHashMap` for `GhosttyBinding`/`GhosttyAppBinding`/
   `CpmTerminalHostBinding`; private constructors; update call sites.
   Verify: `compileJava compileSpikeJava test`.
7. **Self-review + verify.** Branch diff read-through; `jar tf` check;
   `./gradlew --configuration-cache help` (document what remains
   incompatible); final `compileJava compileTestJava test`; report which
   packaging tasks executed vs only configured.
