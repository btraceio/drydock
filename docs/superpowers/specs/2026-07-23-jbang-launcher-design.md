# jbang launcher for Drydock — design

**Status:** approved design, pre-plan
**Date:** 2026-07-23
**Goal:** Let someone with **only jbang installed** (no JDK, no Gradle, no local
build, no zig/Xcode) run Drydock with a single command, with the required JDK 26
and all dependencies provisioned automatically.

## 1. Problem

Drydock today is launched via `./gradlew run` or the jlink `appImage`, both of
which assume a full local toolchain: JDK 26 (toolchain) + a JDK ≤24 for Gradle,
JavaFX 26, and — critically — two hand-built native dylibs (`libghostty.dylib`,
`libdrydockterminalhost.dylib`) compiled from the vendored Ghostty submodule with
**zig 0.15.2 + a full Xcode + the Metal Toolchain**. That toolchain barrier is
too high for anyone who just wants to *run* the app.

jbang solves part of this natively:

- `//JAVA 26` / alias `java-version: 26` → jbang downloads and caches a JDK 26.
- Maven dependency resolution → JavaFX 26 and richtextfx are pulled from Maven
  Central. jbang's built-in **JavaFX support** picks the correct
  `mac` / `mac-aarch64` classifier for the running host and puts JavaFX on the
  module path.
- Runtime/JVM flags → carried by alias `runtime-options`.

What jbang **cannot** do is conjure the two native dylibs or the app's own
compiled classes — neither exists on any repository jbang can reach. This design
supplies both.

## 2. What has to exist that doesn't today

1. **A published artifact** carrying the app's compiled classes *and* both
   architecture slices of both dylibs as jar resources.
2. **A bootstrap main class** that, at launch, extracts the arch-matching dylibs
   from the classpath to a real on-disk path and points Drydock's native-locator
   system properties at it (FFM's `SymbolLookup.libraryLookup` needs an absolute
   file path, not a classpath resource).
3. **A jbang catalog alias** that ties together the JDK version, the runtime
   flags, the Maven deps, the main class, and the published artifact into one
   `jbang drydock@…` command.

## 3. The published artifact — one natives-bundled jar

A new Gradle task assembles a single jar published to **Maven Central** under the
existing btraceio Sonatype domain:

- **Coordinate:** `io.btraceio:drydock:0.1.0` (reuses the btrace project's
  existing Central publishing + GPG signing config; no new Sonatype setup).
- **Contents:**
  - all `app.drydock.*` compiled classes,
  - the new bootstrap class (§4),
  - native resources for **both** architectures:
    - `native/macos-arm64/libghostty.dylib`
    - `native/macos-arm64/libdrydockterminalhost.dylib`
    - `native/macos-x86_64/libghostty.dylib`
    - `native/macos-x86_64/libdrydockterminalhost.dylib`
- **Not bundled:** JavaFX and richtextfx. They stay Maven dependencies so jbang
  fetches/caches them and applies its JavaFX module-path handling. The natives
  ride *inside* the jar precisely because they are the one thing not otherwise
  available on Maven.
- **Central-required sidecars:** signed `-sources` and `-javadoc` jars and a
  signed POM, exactly as btrace already produces. Only the **main** jar carries
  the dylibs. Bundling `.dylib` resources in a Central jar is well-trodden
  (sqlite-jdbc, JNA-style artifacts do the same) — no policy risk.
- **POM stays minimal.** The native libs are bundled resources, not declared
  dependencies. JavaFX/richtextfx are supplied by the catalog alias (§5), **not**
  the POM — see §5 for why.

The dylibs consumed by this task are the same universal-built artifacts the
`buildGhosttyNative` / `buildNativeHost` tasks already produce under
`build/native/macos-arm64/` and `build/native/macos-x86_64/`.

## 4. Bootstrap main class

New class `app.drydock.launcher.JBangBootstrap` (small, ~30 lines). At launch it:

1. Detects `os.arch` → `macos-arm64` / `macos-x86_64`, reusing the same mapping
   as `NativeLibraryLocator.detectArchDirectoryName()` (`x86_64`/`amd64` →
   `macos-x86_64`, `aarch64`/`arm64` → `macos-arm64`; anything else is a clear
   unsupported-arch error).
2. Computes a **version-stamped** cache root:
   `~/.cache/drydock/native/<version>/` (version read from a bundled
   properties/manifest value, so upgrades never collide with a stale extraction).
3. If `<cacheRoot>/macos-<arch>/libghostty.dylib` is absent, copies **both** the
   current arch's dylibs from its own classpath resources
   (`native/macos-<arch>/…`) into `<cacheRoot>/macos-<arch>/`. Extraction is
   done by the JVM writing files, which does **not** attach the
   `com.apple.quarantine` xattr, so Gatekeeper does not block the subsequent
   `dlopen` of the ad-hoc-signed slices.
4. Sets **both** native-locator properties to the *root* (the locator appends
   `macos-<arch>/<file>` itself — see `NativeLibraryLocator.resolveLibraryPath`,
   which resolves `<root>/<archDir>/<fileName>`):
   - `app.drydock.ghostty.nativeDir=<cacheRoot>`
   - `app.drydock.terminalhost.nativeDir=<cacheRoot>`

   Both may share one root, exactly like the jlink image's `lib/` directory.
5. Calls `app.drydock.Main.main(args)`.

This is the only genuinely new **runtime** code. Extraction must be safe against
concurrent first-launches (write to a temp dir + atomic rename, or tolerate an
already-present file).

## 5. Distribution + UX — jbang catalog alias

A `jbang-catalog.json` committed at the repo root defines an alias `drydock`:

- `java-version: 26` — jbang provisions/caches the JDK.
- `main: app.drydock.launcher.JBangBootstrap`
- `deps:`
  - `org.openjfx:javafx-base:26`, `org.openjfx:javafx-controls:26`,
    `org.openjfx:javafx-graphics:26`
  - `org.fxmisc.richtext:richtextfx:0.11.7`
- `runtime-options:`
  - `--enable-native-access=ALL-UNNAMED`
  - `--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED`
  - `-Dfile.encoding=UTF-8`
  - `-Djava.awt.headless=false`
- **ref (artifact):** `io.btraceio:drydock:0.1.0` (the Central GAV).

**Why JavaFX lives in the alias `deps`, not the published POM:** jbang's JavaFX
handling — picking the host's `mac`/`mac-aarch64` classifier and configuring the
module path — keys off `org.openjfx` deps it sees *directly*. Declaring them as
alias deps guarantees that handling. Relying on transitive resolution from a
static POM risks a host-wrong classifier being baked in. So the app POM omits
them and the alias supplies them.

**End-user commands:**

- `jbang drydock@<owner>/<repo>` — jbang reads `jbang-catalog.json` from GitHub
  and runs the alias. Nothing but jbang installed.
- (Advanced) `jbang io.btraceio:drydock:0.1.0` with the flags passed manually —
  the alias exists precisely so users don't have to.

## 6. Runtime-flag parity with the existing launchers

The alias `runtime-options` mirror what the jlink `launcher.sh` and `./gradlew
run` already set, and are required for the same reasons:

- `--enable-native-access=ALL-UNNAMED` — FFM native access from the unnamed
  module.
- `--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED` — the embedded
  terminal's `JavaFxNativeView` reaches `com.sun.glass.ui`; module-path JavaFX
  (which jbang uses) enforces JPMS, so without this the first terminal tab throws
  `IllegalAccessError`.
- `-Dfile.encoding=UTF-8`, `-Djava.awt.headless=false` — same as the other
  launchers.

`-Xdock:name` / `-Xdock:icon` from `launcher.sh` are **optional** here (see §7);
they can be added to `runtime-options` for a nicer dock label but require the icon
to be reachable, which a Central jar resource is not by absolute path. Left out
of v1.

## 7. Known limitations (explicit, by design)

- **Not a signed/notarized `.app` bundle.** A jbang launch is a plain JVM
  process: it shows in the dock as a Java process, has no Finder double-click, no
  bundle identity, no notarization. jbang is the *curl-able* path; the existing
  `appImage`/`dmg` pipeline remains the polished, double-clickable path. These
  are complementary, not competing.
- **Arch-fat jar.** The published jar carries both arch slices of both dylibs
  (tens of MB). The JVM and JavaFX are still arch-correct (jbang provisions the
  right JDK; JavaFX classifier is host-picked); only the bundled natives are fat.
- **CI still needs a full macOS builder.** Building the universal dylibs (zig
  0.15.2 + Xcode Metal Toolchain) and signing for Central still happens in the
  release pipeline. This design frees the **end user** from that toolchain, not
  the release engineer.
- **macOS-only.** Unchanged from the app itself; the bootstrap errors clearly on
  any non-macOS arch.

## 8. Out of scope

- Auto-update / version-check on launch.
- A jbang path that builds from local source (the "dev convenience" and
  "contributor onboarding" variants were considered and rejected in favor of the
  full "nothing installed" goal).
- Publishing to GitHub Packages (requires an auth token even for public reads —
  breaks the anonymous "nothing installed" story) or GitHub Releases (viable, but
  Central is already zero-marginal-setup via the btraceio domain and gives real
  GAV versioning).

## 9. Work summary (for the plan)

1. Gradle task: assemble the natives-bundled `io.btraceio:drydock` main jar
   (classes + bootstrap + both-arch dylib resources), depending on
   `buildGhosttyNative` / `buildNativeHost`.
2. Central publishing wiring reusing the btrace GPG/Sonatype config: main jar +
   signed `-sources`, `-javadoc`, POM.
3. `app.drydock.launcher.JBangBootstrap` (arch detect, safe extraction, property
   set, delegate to `Main`).
4. `jbang-catalog.json` alias `drydock` (java-version, deps, runtime-options,
   main, GAV ref).
5. Docs: a README/run section for the `jbang drydock@…` path and its limitations.
6. Verification: on a clean machine (or a JDK-less shell), `jbang drydock@…`
   launches a working Drydock with a live terminal tab, on both arm64 and x86_64.
