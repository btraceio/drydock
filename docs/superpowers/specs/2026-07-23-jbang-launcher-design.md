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

- **Coordinate:** `io.btraceio:drydock:0.1.0`. The **GPG key and Sonatype
  account** are reused from btrace (no new *credentials*), and `io.btraceio` is
  already a verified namespace — but the Gradle **publish/sign wiring is net-new
  in this repo** (it currently has no `maven-publish`/`signing` config, no
  `-sources`/`-javadoc` jars, no POM metadata). Treat that wiring as real work,
  not a copy-paste.
- **Contents:**
  - all `app.drydock.*` compiled classes,
  - the new bootstrap class (§4),
  - native resources for **both** architectures:
    - `native/macos-arm64/libghostty.dylib`
    - `native/macos-arm64/libdrydockterminalhost.dylib`
    - `native/macos-x86_64/libghostty.dylib`
    - `native/macos-x86_64/libdrydockterminalhost.dylib`
  - the dock icon PNG resource (e.g. `icon/drydock.png`, 1024×1024) — see §6.
- **Not bundled:** JavaFX and richtextfx. They stay Maven dependencies so jbang
  fetches/caches them and applies its JavaFX module-path handling. The natives
  ride *inside* the jar precisely because they are the one thing not otherwise
  available on Maven.
- **Central-required sidecars:** signed `-sources` and `-javadoc` jars and a
  signed POM. Only the **main** jar carries the dylibs. Bundling `.dylib`
  resources in a Central jar is well-trodden (sqlite-jdbc, JNA-style artifacts do
  the same) — no policy risk. **Attribution:** libghostty is MIT-licensed
  (Ghostty), so the jar and POM `<licenses>` must carry Ghostty's MIT
  copyright/permission notice plus a NOTICE of the local
  `ghostty-install-macos-shared-lib.patch` modification (verify the submodule's
  actual LICENSE at plan time).
- **The POM declares the runtime deps.** JavaFX (`javafx-base`/`-controls`/
  `-graphics:26`) and `richtextfx:0.11.7` are **classifier-less** dependencies in
  this POM — this is what jbang resolves (host-correct classifier + module path)
  when it runs the GAV. They are *not* in an alias `deps:` block, which jbang
  drops for a GAV `ref` (see §5, corrected after adversarial review). The native
  libs remain bundled resources, not dependencies.
- **Manifest must carry `Implementation-Version`.** The bootstrap's
  version-stamped cache dir (§4) reads `Package.getImplementationVersion()`,
  which returns the jar manifest's `Implementation-Version` — this is **not**
  written automatically; the jar task must set it explicitly, with a test, or the
  bootstrap reads `null` and upgrade-isolation silently breaks.

The dylibs consumed by this task are the same universal-built artifacts the
`buildGhosttyNative` / `buildNativeHost` tasks already produce under
`build/native/macos-arm64/` and `build/native/macos-x86_64/`.

## 4. Bootstrap main class

New class `app.drydock.launcher.JBangBootstrap` (small, ~30 lines). At launch it:

1. Detects `os.arch` → `macos-arm64` / `macos-x86_64`, reusing the same mapping
   as `NativeLibraryLocator.detectArchDirectoryName()` (`x86_64`/`amd64` →
   `macos-x86_64`, `aarch64`/`arm64` → `macos-arm64`; anything else is a clear
   unsupported-arch error).
2. Computes a **version-stamped** cache root under the macOS caches convention:
   `~/Library/Caches/drydock/native/<version>/`. `<version>` comes from
   `JBangBootstrap.class.getPackage().getImplementationVersion()` (the jar
   manifest's `Implementation-Version` — which the jar task MUST set, see §3);
   stamping by version means upgrades never collide with a stale extraction. If
   the version is somehow `null`, fall back to a content hash of the bundled
   dylib rather than an unstamped shared dir.
3. If `<cacheRoot>/macos-<arch>/libghostty.dylib` is absent, copies **both** the
   current arch's dylibs from its own classpath resources
   (`native/macos-<arch>/…`) into `<cacheRoot>/macos-<arch>/`, writing to a temp
   file + atomic rename so concurrent first-launches don't tear. The dlopen of
   the copied slices succeeds for two reasons: (a) JVM-written files carry no
   `com.apple.quarantine` xattr, and — the load-critical one — (b) the
   provisioned JDK is Temurin, whose hardened runtime ships
   `com.apple.security.cs.disable-library-validation`, so a notarized `java`
   is permitted to load the ad-hoc-signed (arm64) / unsigned (x86_64) third-party
   dylibs. **This assumes a Temurin/Adoptium JDK; the plan must pin/verify the
   vendor**, since a JDK built *without* that entitlement would fail the load with
   a code-signature error regardless of quarantine. The copy preserves the
   embedded ad-hoc signature (plain file copy; no re-sign).
4. Sets **both** native-locator properties to the *root* (the locator appends
   `macos-<arch>/<file>` itself — see `NativeLibraryLocator.resolveLibraryPath`,
   which resolves `<root>/<archDir>/<fileName>`):
   - `app.drydock.ghostty.nativeDir=<cacheRoot>`
   - `app.drydock.terminalhost.nativeDir=<cacheRoot>`

   Both may share one root, exactly like the jlink image's `lib/` directory.
5. Calls `app.drydock.Main.main(args)`. **The dock icon is NOT set here** — see
   §6; doing AWT `Taskbar` work before JavaFX's Cocoa toolkit starts risks a
   main-thread conflict, so icon-setting moves onto the FX thread inside the app.

This is the only genuinely new **runtime** code. Extraction must be safe against
concurrent first-launches (write to a temp dir + atomic rename, or tolerate an
already-present file).

## 5. Distribution + UX — jbang catalog alias

A `jbang-catalog.json` committed at the repo root defines an alias `drydock`:

- `java-version: 26` — jbang provisions/caches the JDK.
- `main: app.drydock.launcher.JBangBootstrap` (overrides whatever `Main-Class`
  the jar manifest carries — verified).
- `runtime-options:`
  - `--enable-native-access=ALL-UNNAMED`
  - `--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED`
  - `-Dfile.encoding=UTF-8`
  - `-Djava.awt.headless=false`
  - `-Xdock:name=Drydock` (dock/menu-bar label; a launch-time JVM arg, so the
    alias is the right place for it — see §6).
- **ref (artifact):** `io.btraceio:drydock:0.1.0` (the Central GAV).
- **No alias `deps:`** — see the correction below.

**JavaFX/richtextfx live in the published POM, NOT in alias `deps` (corrected
after adversarial review).** The runtime deps are declared as normal,
**classifier-less** dependencies in the `io.btraceio:drydock` POM:

- `org.openjfx:javafx-base:26`, `org.openjfx:javafx-controls:26`,
  `org.openjfx:javafx-graphics:26`
- `org.fxmisc.richtext:richtextfx:0.11.7`

**Why the earlier "alias `deps`" plan was wrong (proven, jbang 0.101.0):** when
an alias's `ref` is a **GAV**, jbang **silently drops the alias `deps`** — they
never reach the JVM (confirmed: JavaFX landed on neither module-path nor
classpath, and `--add-exports` then failed with *"Unknown module:
javafx.graphics"*). My first probe only exercised `--deps` on a *source* script,
a different code path, so it gave a false green. The POM route is what actually
works **and** is host-correct: jbang honors a GAV's POM dependencies and
**recomputes the host classifier at run time** — from a classifier-less
`org.openjfx:*` POM dep it resolved the `-mac` (x86_64) slice and put JavaFX on
the module path with `--add-exports` resolving cleanly. So §5's original premise
("a static POM risks a host-wrong classifier") was backwards; the POM is exactly
what makes classifier selection correct and per-host.

Equivalent fallback (also proven): commit a tiny `Launcher.java` carrying
`//DEPS io.btraceio:drydock:0.1.0` + `//DEPS org.openjfx:*` and point the alias
`ref` at that file. The POM route is preferred (nothing to commit, deps travel
with the artifact); the `//DEPS` route is the backstop if POM resolution ever
misbehaves.

**Still to verify at plan time (arm64):** the classifier resolution proved
genuinely host-driven on x86_64 (picked `-mac`), so `mac-aarch64` on Apple
Silicon is low-risk but untested on this Intel dev machine. Also re-run against
JavaFX **26** specifically (the probe used 26's POM shape but an older cached
classifier jar in one run) to confirm 26's `mac`/`mac-aarch64` artifacts get the
same treatment.

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

### Dock name and icon (required — the app must not look unbranded in the dock)

A jbang launch is not a `.app` bundle, so the dock label/icon must be supplied
explicitly. They split by *when* they can be set:

- **Dock name** — `-Xdock:name=Drydock` is a JVM **launch** arg. jbang applies
  `runtime-options` at launch, so this goes straight in the alias (§5). No file
  needed.
- **Dock icon** — `-Xdock:icon=<path>` needs an absolute file path at launch,
  which a Central jar resource cannot provide, so the icon is set **at run time**
  via `java.awt.Taskbar.getTaskbar().setIconImage(image)` from the bundled PNG.
  **Ordering is the risk (flagged by adversarial review):** Drydock already
  drives Glass/Cocoa on the macOS main thread and hands `NSView*` pointers to a
  native host shim, so bringing up AWT's own Cocoa/`NSApplication` *before*
  JavaFX starts (i.e. in the pre-`Main` bootstrap) is exactly the fragile
  AWT-vs-Glass main-thread case. Therefore **set the icon from inside the running
  app on the JavaFX thread** (e.g. early in `DrydockApplication.start`, or via
  `Platform.runLater` after `Platform.startup`), NOT in `JBangBootstrap` before
  `Main`. This must be **verified by running the actual app** on macOS (per the
  project rule to verify UI changes on-screen), on both arches. `Taskbar`/
  `isSupported(Feature.ICON_IMAGE)` is guarded so a non-macOS run degrades
  cleanly. AWT is available because `-Djava.awt.headless=false` is set.

The icon PNG is bundled as a jar resource (e.g. a 1024×1024 `icon/drydock.png`)
alongside the dylibs (§3).

**New artwork — chosen direction: "Bot in dock".** The current
`assets/app-icon.icns` is Claude-branded (terracotta fill + white CLI-prompt
chevron). It is **replaced** with a drydock-themed mark: a friendly **agent
(bot) cradled on keel blocks in a dry dock** — face with glowing teal eyes and a
signal spark, beside the caisson, on the dock floor. The metaphor is exact:
Drydock is where you dock your `claude` **agents** for service, so the vessel
under repair is an agent, not a ship. Palette (deliberately off Claude
terracotta): deep navy ground `#173347`→`#0A1721`, bone `#EDE7DA` bot, teal
`#17A2A2` eyes/spark, brass `#E0A23C` keel blocks.

The refined source art regenerates *both* `assets/app-icon.icns` (used by
`appImage` / `launcher.sh`) and the bundled `icon/drydock.png` (used by the jbang
path), so both launchers look identical. First-pass vectors were reviewed
(artifact, 2026-07-23); the plan covers refining the spark, weighting, and
small-size (32 px) hinting, then producing the multi-resolution iconset.

## 7. Known limitations (explicit, by design)

- **Not a signed/notarized `.app` bundle.** A jbang launch is a plain JVM
  process: no Finder double-click, no bundle identity, no notarization. jbang is
  the *curl-able* path; the existing `appImage`/`dmg` pipeline remains the
  polished, double-clickable path. These are complementary, not competing. (The
  dock *name* and *icon* are handled — see §6 — so it does not look unbranded;
  what's missing is bundle-level identity/notarization, not cosmetics.)
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
   (classes + bootstrap + both-arch dylib resources + icon PNG), depending on
   `buildGhosttyNative` / `buildNativeHost`. **Set `Implementation-Version` in the
   manifest** (with a test asserting the bootstrap reads it back). Include Ghostty
   MIT license + patch NOTICE in the jar.
2. Central publish/sign wiring — **net-new in this repo** (add `maven-publish` +
   `signing`, `-sources`/`-javadoc` jars, POM metadata **including the JavaFX +
   richtextfx classifier-less runtime deps** and `<licenses>`), reusing btrace's
   GPG key + Sonatype account.
3. `app.drydock.launcher.JBangBootstrap` (arch detect, safe temp+atomic
   extraction to `~/Library/Caches/…`, version-stamped, set `nativeDir`
   properties, delegate to `Main` — **no icon work here**).
4. Dock icon on the **FX thread** inside the app (not the bootstrap) from the
   bundled PNG, guarded via `Taskbar.isSupported`; **verify on-screen** on both
   arches. New "Bot in dock" artwork → regenerate `assets/app-icon.icns` **and**
   `icon/drydock.png`; add `-Xdock:name=Drydock` to the alias.
5. `jbang-catalog.json` alias `drydock` (`java-version: 26`, `runtime-options`,
   `main`, GAV `ref` — **no alias `deps`**; deps come from the POM).
6. Docs: a README/run section for the `jbang drydock@…` path and its limitations.
7. Verification (the review's must-fix set): after the POM-deps fix, confirm
   `jbang drydock@…` actually launches a working Drydock with a live terminal
   tab, correct dock name/icon, and host-correct JavaFX classifier on **both**
   arm64 and x86_64, using a **Temurin** JDK 26 (dylib load depends on its
   `disable-library-validation` entitlement).
