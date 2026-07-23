# jbang launcher for Drydock — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let anyone with only `jbang` installed run Drydock via `jbang drydock@<owner>/<repo>` — jbang provisions JDK 26 + JavaFX, a Maven Central jar carries the app classes and both-arch native dylibs, and a bootstrap stages the dylibs before launching.

**Architecture:** A thin `JBangBootstrap` main class extracts the arch-matching `libghostty`/`libdrydockterminalhost` dylibs from its own classpath resources into `~/Library/Caches/drydock/native/<version>/`, points the existing `app.drydock.*.nativeDir` system properties at that root, then delegates to `app.drydock.Main`. The app is published to Maven Central as `io.btraceio:drydock` — a thin jar (classes + dylib resources + icon), with JavaFX/richtextfx declared as **classifier-less POM deps** (jbang recomputes the host classifier at run time). A `jbang-catalog.json` alias supplies the JVM flags and `java-version: 26`. A new "Bot in dock" icon replaces the Claude-branded one and is set on the JavaFX thread via `Taskbar.setIconImage`.

**Tech Stack:** Java 26 (FFM), JavaFX 26, Gradle (Kotlin DSL), `maven-publish` + `signing`, JUnit 5, jbang 0.101.x, macOS `iconutil` + `rsvg-convert` (librsvg) for icon generation.

## Global Constraints

- **Platform:** macOS only, arm64 **and** x86_64. Arch mapping: `x86_64`/`amd64` → `macos-x86_64`; `aarch64`/`arm64` → `macos-arm64` (reuse `NativeLibraryLocator.detectArchDirectoryName()`).
- **Coordinate:** `io.btraceio:drydock:0.1.0` (groupId `io.btraceio`, artifactId `drydock`, version `0.1.0`).
- **Runtime deps go in the POM, classifier-less:** `org.openjfx:javafx-base:26`, `org.openjfx:javafx-graphics:26`, `org.openjfx:javafx-controls:26`, `org.fxmisc.richtext:richtextfx:0.11.7`. **Never** put these in an alias `deps:` block — jbang silently drops alias deps when the alias `ref` is a GAV.
- **Alias runtime-options (exact):** `--enable-native-access=ALL-UNNAMED`, `--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED`, `-Dfile.encoding=UTF-8`, `-Djava.awt.headless=false`, `-Xdock:name=Drydock`.
- **Cache dir:** `~/Library/Caches/drydock/native/<version>/<arch-dir>/<dylib>`.
- **Version source:** the jar manifest's `Implementation-Version` (must be set explicitly by the jar task); read via `Package.getImplementationVersion()`, fall back to a content hash.
- **Dock icon:** set on the **JavaFX thread inside the app**, never in the pre-`Main` bootstrap (AWT-vs-Glass Cocoa main-thread conflict).
- **JDK vendor:** Temurin/Adoptium (its hardened runtime ships `com.apple.security.cs.disable-library-validation`, which is what permits `dlopen` of the ad-hoc/unsigned dylibs).
- **Code style:** no fully-qualified class names inline — use imports (except genuine same-name collisions). Match surrounding code's comment density and idiom.
- **Attribution:** libghostty is MIT (Ghostty); the jar and POM must carry its license notice.

## File Structure

- `app/src/main/java/app/drydock/launcher/JBangBootstrap.java` — **new.** jbang entry point: arch detect, native staging, property set, delegate to `Main`. No icon work, no `System.getenv`/`ProcessBuilder` before `Main`.
- `app/src/main/java/app/drydock/launcher/DockIcon.java` — **new.** Best-effort macOS dock-icon setter; loads the bundled PNG.
- `app/src/test/java/app/drydock/launcher/JBangBootstrapTest.java` — **new.** Unit tests for staging/properties/version.
- `app/src/test/java/app/drydock/launcher/DockIconTest.java` — **new.** Icon-resource load test (headless-safe).
- `app/src/test/resources/native/macos-arm64/*.dylib`, `.../macos-x86_64/*.dylib` — **new.** Dummy fixtures for staging tests.
- `assets/icon/drydock.svg` — **new.** Refined "Bot in dock" source art.
- `scripts/generate-icon.sh` — **new.** Rasterizes the SVG → `.icns` + PNG.
- `assets/app-icon.icns` — **regenerated** (replaces the Claude-branded icon; consumed by `appImage`/`launcher.sh`).
- `app/src/main/resources/icon/drydock.png` — **new.** 1024×1024 dock icon bundled on the classpath.
- `app/src/main/java/app/drydock/DrydockApplication.java` — **modify.** Call `DockIcon.applyDockIcon()` at the top of `startOnFxThread`.
- `app/build.gradle.kts` — **modify.** `group`/`version`, `jbangJar` task, `maven-publish` + `signing`, publication + POM.
- `jbang-catalog.json` — **new.** The `drydock` alias.
- `README.md` — **modify.** "Run with jbang" section + limitations.

---

### Task 1: `JBangBootstrap` — native staging, properties, version

**Files:**
- Create: `app/src/main/java/app/drydock/launcher/JBangBootstrap.java`
- Create: `app/src/test/java/app/drydock/launcher/JBangBootstrapTest.java`
- Create: `app/src/test/resources/native/macos-arm64/libghostty.dylib`, `app/src/test/resources/native/macos-arm64/libdrydockterminalhost.dylib`, `app/src/test/resources/native/macos-x86_64/libghostty.dylib`, `app/src/test/resources/native/macos-x86_64/libdrydockterminalhost.dylib`

**Interfaces:**
- Consumes: `app.drydock.terminal.NativeLibraryLocator.detectArchDirectoryName()` (existing, returns `"macos-arm64"`/`"macos-x86_64"`), `app.drydock.Main.main(String[])` (existing).
- Produces: `JBangBootstrap.main(String[])`; package-visible helpers `stageNatives(Path cacheRoot, String archDir)`, `applyNativeDirProperties(Path cacheRoot)`, `defaultCacheRoot(String version)`, `resolveVersion()`.

- [ ] **Step 1: Create the four dummy fixture files**

```bash
cd /Users/jbachorik/dev/wt/olifer-jbang-launcher
mkdir -p app/src/test/resources/native/macos-arm64 app/src/test/resources/native/macos-x86_64
printf 'dummy-ghostty-arm64\n'   > app/src/test/resources/native/macos-arm64/libghostty.dylib
printf 'dummy-host-arm64\n'      > app/src/test/resources/native/macos-arm64/libdrydockterminalhost.dylib
printf 'dummy-ghostty-x86_64\n'  > app/src/test/resources/native/macos-x86_64/libghostty.dylib
printf 'dummy-host-x86_64\n'     > app/src/test/resources/native/macos-x86_64/libdrydockterminalhost.dylib
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/app/drydock/launcher/JBangBootstrapTest.java`:

```java
package app.drydock.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JBangBootstrapTest {

    @Test
    void stageNativesCopiesBothDylibs(@TempDir Path tmp) throws IOException {
        JBangBootstrap.stageNatives(tmp, "macos-x86_64");
        assertTrue(Files.isRegularFile(tmp.resolve("macos-x86_64/libghostty.dylib")));
        assertTrue(Files.isRegularFile(tmp.resolve("macos-x86_64/libdrydockterminalhost.dylib")));
    }

    @Test
    void stageNativesIsIdempotent(@TempDir Path tmp) throws IOException {
        JBangBootstrap.stageNatives(tmp, "macos-x86_64");
        JBangBootstrap.stageNatives(tmp, "macos-x86_64"); // must not throw
        assertTrue(Files.isRegularFile(tmp.resolve("macos-x86_64/libghostty.dylib")));
    }

    @Test
    void applyNativeDirPropertiesSetsBothAndRespectsOverride(@TempDir Path tmp) {
        String ghostty = "app.drydock.ghostty.nativeDir";
        String host = "app.drydock.terminalhost.nativeDir";
        String preset = "/preset/override";
        System.setProperty(ghostty, preset);
        System.clearProperty(host);
        try {
            JBangBootstrap.applyNativeDirProperties(tmp);
            assertEquals(preset, System.getProperty(ghostty), "must not override a pre-set property");
            assertEquals(tmp.toString(), System.getProperty(host));
        } finally {
            System.clearProperty(ghostty);
            System.clearProperty(host);
        }
    }

    @Test
    void defaultCacheRootIsUnderLibraryCaches() {
        Path root = JBangBootstrap.defaultCacheRoot("1.2.3");
        String tail = Path.of("Library", "Caches", "drydock", "native", "1.2.3").toString();
        assertTrue(root.toString().endsWith(tail), root.toString());
    }

    @Test
    void resolveVersionFallsBackWhenManifestAbsent() {
        // Running from classes (no jar manifest) => getImplementationVersion() is null.
        String v = JBangBootstrap.resolveVersion();
        assertNotNull(v);
        assertFalse(v.isBlank());
        assertTrue(v.startsWith("dev-"), v);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.launcher.JBangBootstrapTest'`
Expected: FAIL — compilation error, `JBangBootstrap` does not exist.

- [ ] **Step 4: Implement `JBangBootstrap`**

Create `app/src/main/java/app/drydock/launcher/JBangBootstrap.java`:

```java
package app.drydock.launcher;

import app.drydock.Main;
import app.drydock.terminal.NativeLibraryLocator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * jbang entry point. Stages the bundled native dylibs for the current CPU
 * architecture onto disk (FFM's {@code SymbolLookup.libraryLookup} needs an
 * absolute path, not a classpath resource), points the app's native-locator
 * system properties at them, then hands off to {@link Main}.
 *
 * <p>Only used when Drydock is launched from the published jbang jar; the
 * jlink image and {@code ./gradlew run} enter through {@link Main} directly
 * and resolve natives from {@code build/native} or the image {@code lib/}.</p>
 *
 * <p>Deliberately does <b>no</b> AWT/dock-icon work and never calls
 * {@code System.getenv()}/{@code ProcessBuilder} before delegating: {@link
 * Main} must run {@code LoginShellEnvironment.mergeLoginShellPath()} before the
 * environment is first snapshotted.</p>
 */
public final class JBangBootstrap {

    private static final String GHOSTTY_DYLIB = "libghostty.dylib";
    private static final String HOST_DYLIB = "libdrydockterminalhost.dylib";
    private static final List<String> DYLIBS = List.of(GHOSTTY_DYLIB, HOST_DYLIB);

    private static final String GHOSTTY_NATIVE_DIR_PROP = "app.drydock.ghostty.nativeDir";
    private static final String HOST_NATIVE_DIR_PROP = "app.drydock.terminalhost.nativeDir";

    private JBangBootstrap() {
    }

    public static void main(String[] args) throws IOException {
        String archDir = NativeLibraryLocator.detectArchDirectoryName();
        Path cacheRoot = defaultCacheRoot(resolveVersion());
        stageNatives(cacheRoot, archDir);
        applyNativeDirProperties(cacheRoot);
        Main.main(args);
    }

    /** {@code ~/Library/Caches/drydock/native/<version>}. */
    static Path defaultCacheRoot(String version) {
        return Path.of(System.getProperty("user.home"),
                "Library", "Caches", "drydock", "native", version);
    }

    /**
     * Copies this arch's two dylibs from classpath resources
     * ({@code native/<archDir>/<file>}) into {@code <cacheRoot>/<archDir>/},
     * skipping any already present. Writes via a temp file + atomic rename so
     * concurrent first-launches do not tear.
     */
    static void stageNatives(Path cacheRoot, String archDir) throws IOException {
        Path archDest = cacheRoot.resolve(archDir);
        Files.createDirectories(archDest);
        for (String dylib : DYLIBS) {
            Path dest = archDest.resolve(dylib);
            if (Files.isRegularFile(dest)) {
                continue;
            }
            copyResource("native/" + archDir + "/" + dylib, dest);
        }
    }

    private static void copyResource(String resourcePath, Path dest) throws IOException {
        try (InputStream in = JBangBootstrap.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Bundled native resource not found on classpath: " + resourcePath);
            }
            Path tmp = Files.createTempFile(dest.getParent(), ".stage-", ".tmp");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /** Points both native-locator roots at {@code cacheRoot}, never overriding a caller-set value. */
    static void applyNativeDirProperties(Path cacheRoot) {
        String root = cacheRoot.toString();
        setIfAbsent(GHOSTTY_NATIVE_DIR_PROP, root);
        setIfAbsent(HOST_NATIVE_DIR_PROP, root);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /** Manifest {@code Implementation-Version}, or {@code dev-<hash>} when unavailable. */
    static String resolveVersion() {
        String version = JBangBootstrap.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) {
            return version;
        }
        return "dev-" + ghosttyContentHash();
    }

    private static String ghosttyContentHash() {
        String archDir;
        try {
            archDir = NativeLibraryLocator.detectArchDirectoryName();
        } catch (RuntimeException e) {
            return "unknown";
        }
        try (InputStream in = JBangBootstrap.class.getClassLoader()
                .getResourceAsStream("native/" + archDir + "/" + GHOSTTY_DYLIB)) {
            if (in == null) {
                return "unknown";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.launcher.JBangBootstrapTest'`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/launcher/JBangBootstrap.java \
        app/src/test/java/app/drydock/launcher/JBangBootstrapTest.java \
        app/src/test/resources/native
git commit -m "Add JBangBootstrap: stage native dylibs and delegate to Main"
```

---

### Task 2: "Bot in dock" icon artwork

**Files:**
- Create: `assets/icon/drydock.svg`
- Create: `scripts/generate-icon.sh`
- Create (generated, committed): `assets/app-icon.icns`, `app/src/main/resources/icon/drydock.png`

**Interfaces:**
- Produces: classpath resource `/icon/drydock.png` (consumed by Task 3's `DockIcon`) and `assets/app-icon.icns` (consumed by existing `appImage`/`launcher.sh`).

- [ ] **Step 1: Write the source SVG**

Create `assets/icon/drydock.svg`:

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <defs>
    <linearGradient id="gDock" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#173347"/><stop offset="1" stop-color="#0A1721"/>
    </linearGradient>
    <radialGradient id="gloss" cx="0.3" cy="0.12" r="0.9">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.16"/>
      <stop offset="0.5" stop-color="#ffffff" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="1024" height="1024" rx="228" fill="url(#gDock)"/>
  <rect width="1024" height="1024" rx="228" fill="url(#gloss)"/>
  <!-- caisson water hint -->
  <rect x="800" y="300" width="126" height="470" rx="10" fill="#17A2A2" opacity="0.26"/>
  <g fill="none" stroke="#17A2A2" stroke-width="14" stroke-linecap="round" opacity="0.65">
    <line x1="820" y1="404" x2="906" y2="404"/><line x1="820" y1="474" x2="906" y2="474"/>
    <line x1="820" y1="544" x2="906" y2="544"/>
  </g>
  <!-- antenna + agent spark -->
  <line x1="512" y1="352" x2="512" y2="300" stroke="#EDE7DA" stroke-width="18" stroke-linecap="round"/>
  <path d="M 512 250 C 520 284 528 292 562 300 C 528 308 520 316 512 350
           C 504 316 496 308 462 300 C 496 292 504 284 512 250 Z" fill="#17A2A2"/>
  <!-- head / body -->
  <rect x="318" y="352" width="388" height="276" rx="60" fill="#EDE7DA"/>
  <!-- eyes -->
  <circle cx="446" cy="472" r="40" fill="#123040"/>
  <circle cx="578" cy="472" r="40" fill="#123040"/>
  <circle cx="460" cy="460" r="12" fill="#17A2A2"/>
  <circle cx="592" cy="460" r="12" fill="#17A2A2"/>
  <!-- smile -->
  <path d="M 452 552 Q 512 586 572 552" fill="none" stroke="#123040" stroke-width="16" stroke-linecap="round"/>
  <!-- keel blocks -->
  <g fill="#E0A23C">
    <rect x="316" y="628" width="48" height="52" rx="6"/><rect x="436" y="628" width="48" height="52" rx="6"/>
    <rect x="556" y="628" width="48" height="52" rx="6"/><rect x="660" y="628" width="48" height="52" rx="6"/>
  </g>
  <!-- dock floor -->
  <rect x="180" y="690" width="664" height="12" rx="6" fill="#F2F6F4" opacity="0.85"/>
</svg>
```

- [ ] **Step 2: Write the generation script**

Create `scripts/generate-icon.sh`:

```bash
#!/bin/bash
# Rasterizes assets/icon/drydock.svg into the macOS .icns and the classpath
# PNG bundled in the jbang jar. Requires librsvg (`brew install librsvg`) and
# macOS `iconutil`. Regenerate whenever the SVG changes; the outputs are
# committed so ordinary builds need neither tool.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SVG="$ROOT/assets/icon/drydock.svg"
ICONSET="$(mktemp -d)/drydock.iconset"
mkdir -p "$ICONSET"

command -v rsvg-convert >/dev/null || { echo "Need rsvg-convert: brew install librsvg" >&2; exit 1; }
command -v iconutil    >/dev/null || { echo "Need iconutil (macOS)" >&2; exit 1; }

render() { rsvg-convert -w "$1" -h "$1" "$SVG" -o "$2"; }

for pair in "16 16x16" "32 16x16@2x" "32 32x32" "64 32x32@2x" \
            "128 128x128" "256 128x128@2x" "256 256x256" "512 256x256@2x" \
            "512 512x512" "1024 512x512@2x"; do
  set -- $pair
  render "$1" "$ICONSET/icon_$2.png"
done

iconutil -c icns "$ICONSET" -o "$ROOT/assets/app-icon.icns"
mkdir -p "$ROOT/app/src/main/resources/icon"
render 1024 "$ROOT/app/src/main/resources/icon/drydock.png"
echo "Wrote assets/app-icon.icns and app/src/main/resources/icon/drydock.png"
```

- [ ] **Step 3: Install the rasterizer and run the script**

```bash
brew install librsvg
chmod +x scripts/generate-icon.sh
./scripts/generate-icon.sh
```
Expected: prints `Wrote assets/app-icon.icns and app/src/main/resources/icon/drydock.png`.

- [ ] **Step 4: Verify the outputs are valid**

```bash
sips -g pixelWidth -g pixelHeight app/src/main/resources/icon/drydock.png
iconutil -c iconset assets/app-icon.icns -o /tmp/verify.iconset && ls /tmp/verify.iconset && rm -rf /tmp/verify.iconset
```
Expected: PNG reports `pixelWidth: 1024` / `pixelHeight: 1024`; the iconset lists `icon_16x16.png` … `icon_512x512@2x.png` (10 files) without error.

- [ ] **Step 5: Open the PNG and confirm it is the Bot-in-dock mark (on-screen)**

```bash
open app/src/main/resources/icon/drydock.png
```
Expected: a navy squircle with a bone-white bot (teal eyes + spark) on brass keel blocks. If it looks wrong, fix the SVG and re-run Steps 3–4 before committing.

- [ ] **Step 6: Commit**

```bash
git add assets/icon/drydock.svg scripts/generate-icon.sh assets/app-icon.icns app/src/main/resources/icon/drydock.png
git commit -m "Replace the app icon with the drydock 'Bot in dock' mark"
```

---

### Task 3: `DockIcon` + wire it onto the JavaFX thread

**Files:**
- Create: `app/src/main/java/app/drydock/launcher/DockIcon.java`
- Create: `app/src/test/java/app/drydock/launcher/DockIconTest.java`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (add import + one call in `startOnFxThread`, near line 148)

**Interfaces:**
- Consumes: classpath resource `/icon/drydock.png` (Task 2).
- Produces: `DockIcon.applyDockIcon()` (best-effort, never throws) and package-visible `DockIcon.loadIconImage()` returning `java.awt.Image` or `null`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/launcher/DockIconTest.java`:

```java
package app.drydock.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DockIconTest {

    @Test
    void loadsBundledIcon() {
        // Headless-safe: touches only ImageIO + the classpath resource, not Taskbar.
        assertNotNull(DockIcon.loadIconImage(), "bundled /icon/drydock.png must load");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.launcher.DockIconTest'`
Expected: FAIL — `DockIcon` does not exist.

- [ ] **Step 3: Implement `DockIcon`**

Create `app/src/main/java/app/drydock/launcher/DockIcon.java`:

```java
package app.drydock.launcher;

import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Taskbar.Feature;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import javax.imageio.ImageIO;

/**
 * Sets the macOS dock icon at run time from the bundled PNG. Needed because a
 * jbang launch is not a {@code .app} bundle and {@code -Xdock:icon} needs a
 * launch-time path the jar cannot provide.
 *
 * <p>MUST be invoked on the JavaFX application thread, after the toolkit is up
 * -- initializing AWT's Cocoa layer before JavaFX's Glass toolkit risks an
 * {@code NSApplication} main-thread conflict.</p>
 */
public final class DockIcon {

    private static final Logger LOG = System.getLogger(DockIcon.class.getName());
    private static final String ICON_RESOURCE = "/icon/drydock.png";

    private DockIcon() {
    }

    /** Best-effort: set the dock icon. Silently does nothing if unsupported. Never throws. */
    public static void applyDockIcon() {
        try {
            if (!Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Feature.ICON_IMAGE)) {
                return;
            }
            Image image = loadIconImage();
            if (image != null) {
                taskbar.setIconImage(image);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Could not set the dock icon", e);
        }
    }

    /** Loads the bundled icon, or returns {@code null} if missing/unreadable. */
    static Image loadIconImage() {
        try (InputStream in = DockIcon.class.getResourceAsStream(ICON_RESOURCE)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.launcher.DockIconTest'`
Expected: PASS.

- [ ] **Step 5: Wire the call into `startOnFxThread`**

In `app/src/main/java/app/drydock/DrydockApplication.java`, add the import (with the other `app.drydock.*` imports near the top):

```java
import app.drydock.launcher.DockIcon;
```

Then make `startOnFxThread` (line ~147) call it first — change:

```java
    private void startOnFxThread(Stage primaryStage) {
        // Diagnostic override (see the app.drydock.diag.* section in
```

to:

```java
    private void startOnFxThread(Stage primaryStage) {
        // Set the macOS dock icon here (on the FX thread, toolkit already up)
        // rather than in the jbang bootstrap -- doing AWT/Taskbar work before
        // Glass initializes risks an NSApplication main-thread conflict.
        DockIcon.applyDockIcon();

        // Diagnostic override (see the app.drydock.diag.* section in
```

- [ ] **Step 6: Verify on-screen (per the project rule to verify UI by running the app)**

```bash
./gradlew :app:run -Papp.drydock.diag.stateFile=/tmp/drydock-icon-check.json
```
Expected: the app window opens and its **dock icon is the Bot-in-dock mark** (not the generic Java cup). Screenshot the running app to confirm.
**Caution:** if a packaged Drydock is already running, only inspect/stop the `./gradlew :app:run` process — never disturb the user's packaged instance. Close this run when done.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/launcher/DockIcon.java \
        app/src/test/java/app/drydock/launcher/DockIconTest.java \
        app/src/main/java/app/drydock/DrydockApplication.java
git commit -m "Set the macOS dock icon on the FX thread from the bundled PNG"
```

---

### Task 4: `jbangJar` — the natives-bundled publishable jar

**Files:**
- Modify: `app/build.gradle.kts` (add `group`/`version` and the `jbangJar` task)

**Interfaces:**
- Consumes: `sourceSets.main.output` (classes + resources incl. `icon/drydock.png`), `build/native/<arch>/*.dylib` (from `buildGhosttyNative`/`buildNativeHost`), `third_party/ghostty/LICENSE`.
- Produces: `app/build/libs/drydock-0.1.0.jar` (Gradle task `:app:jbangJar`) with `Implementation-Version` in its manifest.

- [ ] **Step 1: Confirm the Ghostty license filename, then add coordinates and the jar task**

First confirm the exact license filename (Gradle's `from(<file>)` silently skips a missing path, so a wrong name would drop the notice unnoticed):

```bash
git submodule update --init third_party/ghostty   # if not already checked out
ls third_party/ghostty | grep -iE 'license|copying'
```
Expected: a `LICENSE` file. If it is named differently (e.g. `LICENSE.md`, `COPYING`), use that exact name in the `from(...)`/`rename` below.

In `app/build.gradle.kts`, add at the top level (after the `plugins { … }` block):

```kotlin
group = "io.btraceio"
version = "0.1.0"
```

Then add the task (anywhere after the `dependencies { … }` block):

```kotlin
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
```

- [ ] **Step 2: Build the jar**

Run: `./gradlew :app:jbangJar`
Expected: BUILD SUCCESSFUL; `app/build/libs/drydock-0.1.0.jar` exists. (First run also builds the native dylibs — needs zig 0.15.2 + Xcode Metal Toolchain; see README requirements.)

- [ ] **Step 3: Assert the jar contains the expected entries**

```bash
JAR=app/build/libs/drydock-0.1.0.jar
unzip -l "$JAR" | grep -E 'native/macos-arm64/libghostty\.dylib|native/macos-arm64/libdrydockterminalhost\.dylib|native/macos-x86_64/libghostty\.dylib|native/macos-x86_64/libdrydockterminalhost\.dylib|icon/drydock\.png|app/drydock/launcher/JBangBootstrap\.class|META-INF/licenses/LICENSE-ghostty\.txt'
unzip -p "$JAR" META-INF/MANIFEST.MF | grep 'Implementation-Version'
```
Expected: all seven grep lines match; manifest shows `Implementation-Version: 0.1.0`.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Add jbangJar: thin jar bundling both-arch native dylibs"
```

---

### Task 5: Maven publish + signing wiring

**Files:**
- Modify: `app/build.gradle.kts` (add `maven-publish` + `signing` plugins, sources/javadoc jars, the `drydock` publication + POM)

**Interfaces:**
- Consumes: the `jbangJar` task (Task 4).
- Produces: Maven publication `drydock` (`io.btraceio:drydock:0.1.0`) with a POM declaring the four classifier-less runtime deps and the licenses; installable to `~/.m2` via `publishToMavenLocal`.

- [ ] **Step 1: Add the plugins**

In `app/build.gradle.kts`, add to the `plugins { … }` block:

```kotlin
    `maven-publish`
    signing
```

- [ ] **Step 2: Create the sources/javadoc jars**

In the existing `java { … }` block (the one with `toolchain`), add:

```kotlin
    withSourcesJar()
    withJavadocJar()
```

- [ ] **Step 3: Add the publishing + signing block**

Append to `app/build.gradle.kts`:

```kotlin
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

signing {
    // Sign only for a real publish, never for publishToMavenLocal.
    setRequired({ gradle.taskGraph.allTasks.any { it.name.startsWith("publish") && it.name.contains("Repository") && !it.name.contains("MavenLocal") } })
    sign(publishing.publications["drydock"])
}
```

- [ ] **Step 4: Publish to the local Maven repo**

Run: `./gradlew :app:publishToMavenLocal`
Expected: BUILD SUCCESSFUL; artifacts appear under `~/.m2/repository/io/btraceio/drydock/0.1.0/`.

- [ ] **Step 5: Assert the artifacts and POM**

```bash
ls ~/.m2/repository/io/btraceio/drydock/0.1.0/
grep -A2 'javafx-controls\|richtextfx' ~/.m2/repository/io/btraceio/drydock/0.1.0/drydock-0.1.0.pom
grep -q '<classifier>' ~/.m2/repository/io/btraceio/drydock/0.1.0/drydock-0.1.0.pom && echo "FAIL: POM has a classifier" || echo "OK: no classifiers in POM deps"
```
Expected: the directory lists `drydock-0.1.0.jar`, `-sources.jar`, `-javadoc.jar`, `.pom`; the POM lists `javafx-*` and `richtextfx` as runtime deps; **no** `<classifier>` element; prints `OK: no classifiers in POM deps`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Publish io.btraceio:drydock with classifier-less runtime deps"
```

---

### Task 6: `jbang-catalog.json` alias

**Files:**
- Create: `jbang-catalog.json` (repo root)

**Interfaces:**
- Consumes: the published GAV `io.btraceio:drydock:0.1.0` (verified from `mavenLocal` in this task; from Central once released).
- Produces: the `drydock` jbang alias → `jbang drydock@<owner>/<repo>`.

- [ ] **Step 1: Write the catalog**

Create `jbang-catalog.json`:

```json
{
  "aliases": {
    "drydock": {
      "script-ref": "io.btraceio:drydock:0.1.0",
      "description": "Drydock — manage local Git repos and claude CLI sessions (macOS, arm64/x86_64).",
      "java-version": "26",
      "main": "app.drydock.launcher.JBangBootstrap",
      "runtime-options": [
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=false",
        "-Xdock:name=Drydock"
      ]
    }
  }
}
```

- [ ] **Step 2: Verify the alias resolves and sets up JavaFX (dry-run against mavenLocal)**

Run: `jbang --repos mavenlocal,mavencentral --catalog ./jbang-catalog.json --verbose run drydock 2>&1 | grep -E 'module-path|add-exports|JBangBootstrap' | head`
Expected: the printed `java …` line has `--module-path <…javafx-base…javafx-graphics…javafx-controls…>`, carries `--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED` with **no** "Unknown module" warning, and runs `app.drydock.launcher.JBangBootstrap`.

- [ ] **Step 3: Verify it actually launches Drydock (on-screen)**

Run: `jbang --repos mavenlocal,mavencentral --catalog ./jbang-catalog.json run drydock`
Expected: JDK 26 is provisioned (first run), the Drydock window opens with the Bot-in-dock dock icon and the dock name "Drydock". Open a session tab and confirm the embedded terminal works (this exercises the staged native dylibs). Close the app.
If the terminal tab throws `IllegalAccessError`, the `--add-exports` did not apply — re-check Step 2.

- [ ] **Step 4: Commit**

```bash
git add jbang-catalog.json
git commit -m "Add the drydock jbang-catalog alias"
```

---

### Task 7: Docs + cross-arch verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Add a "Run with jbang" section to README.md**

Insert after the "## Requirements" section in `README.md`:

```markdown
## Run with jbang (no JDK/Gradle needed)

If you have [jbang](https://www.jbang.dev) installed, you can run Drydock without
installing a JDK, Gradle, or the native toolchain:

```

```bash
jbang drydock@btraceio/drydock
```

```markdown
jbang provisions a **Temurin JDK 26** and JavaFX automatically. The app's classes
and the required native libraries (`libghostty`, `libdrydockterminalhost`, for
both Apple Silicon and Intel) ship inside the `io.btraceio:drydock` Maven Central
jar; on first launch they are extracted to `~/Library/Caches/drydock/native/`.

**Limitations vs. the packaged app.** A jbang launch is a plain JVM process, not a
signed/notarized `.app` bundle: no Finder double-click and no bundle identity
(the dock name and Bot-in-dock icon are still set). For the polished,
double-clickable app, build the `.app` with `./gradlew appImage`. The dylib load
relies on Temurin's `disable-library-validation` entitlement; a non-Temurin JDK
may refuse to load them.
```

- [ ] **Step 2: Commit the docs**

```bash
git add README.md
git commit -m "Document the jbang run path"
```

- [ ] **Step 3: Full cross-arch verification (manual gate)**

Confirm the must-fix items from the design review, on **both** architectures where possible:

- On the current host (x86_64): a clean-cache run works end to end.
  ```bash
  rm -rf ~/.jbang/cache ~/Library/Caches/drydock
  jbang --repos mavenlocal,mavencentral --catalog ./jbang-catalog.json run drydock
  ```
  Expected: JDK 26 downloads, JavaFX resolves the `-mac` (x86_64) classifier, the app opens, a terminal tab works, dock name/icon correct.
- On an Apple Silicon (arm64) host, repeat and confirm jbang resolves the **`mac-aarch64`** JavaFX classifier and the app runs. Record the result. If jbang mis-picks the classifier on arm64, fall back to the committed `Launcher.java` + `//DEPS` variant noted in the spec §5.
- Confirm the provisioned JDK is **Temurin/Adoptium** (`jbang jdk list`); if jbang defaults to another vendor that lacks `disable-library-validation`, document `jbang jdk install 26 <temurin-id>` as a prerequisite.

- [ ] **Step 4: Note any arch/vendor findings**

If arm64 or a non-Temurin vendor needs special handling, add a short note to the README "Limitations" paragraph and commit.

---

## Notes for the release engineer (out of scope for this plan)

The **actual Maven Central release** (Sonatype credentials, the Central publishing repository, GPG signing on publish, and a CI job that builds the universal dylibs with zig 0.15.2 + Xcode Metal Toolchain before `jbangJar`) is deliberately not automated here. Task 5 verifies the publication shape via `publishToMavenLocal`; wiring the real `publish` repository + credentials reuses the btrace GPG key and Sonatype account and is a follow-up.
