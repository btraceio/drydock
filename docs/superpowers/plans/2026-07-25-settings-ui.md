# Settings UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Settings modal — theme, interface font size, terminal font size, worktrees directory — reachable from a new gear button in the title bar and from `⌘,`.

**Architecture:** Cosmetic settings (theme, both font sizes) live in `WorkspaceUiState` and are written through `RepositoryManager`, the single holder of the state store. The worktrees directory lives in the human-editable `~/.drydock/config.json` behind `UserConfig`, which gains atomic write support. Interface font size works by generating a scaled copy of `app.css` at runtime (`UiFontScale`) rather than rewriting the sheet; terminal font size works by injecting `font-size = N` into the generated ghostty `.conf`, which the existing theme-reapply path already pushes to running surfaces.

**Tech Stack:** Java 26, JavaFX 26, Gradle (Kotlin DSL), JUnit 5, libghostty via FFM.

**Spec:** `docs/superpowers/specs/2026-07-25-settings-ui-design.md`

## Global Constraints

- Never run blocking work (file I/O, process spawns) on the JavaFX Application Thread; use a virtual thread or an owning executor and hop back with `Platform.runLater`. Every user-triggered async operation shows progress immediately, and **every** completion path — success, error, early return — clears it.
- Persistent state has one writer: managers submit transform functions to `ApplicationStateStore` via `RepositoryManager`. Never load-then-save.
- Cosmetic UI fields decode leniently: a malformed value falls back to a default and never fails the whole state decode.
- A service that writes files from a background thread exposes a flush, called from `DrydockApplication.stop()`.
- Range ownership is at the point of application, not in the codec: `uiFontSize` 11.0–16.0 clamped by `ThemeManager`, `terminalFontSize` 10.0–18.0 clamped by `TerminalThemes`.
- Defaults: `uiFontSize` 13.0, `terminalFontSize` 13.0. `app.css`'s `.root` base font size is **13px** — the divisor for every scale factor.
- Build/test: `./gradlew :app:test` runs the suite; `./gradlew :app:run` launches the app.
- Java style in this repo: package-private UI classes unless a wider scope is needed; Javadoc on every new class and non-obvious method explaining *why*, not *what*.

---

### Task 1: Verify libghostty honours `font-size` in a config file

The whole terminal-font-size design rests on this and it cannot be confirmed from this checkout — `third_party/ghostty` is an unpopulated submodule. Do this before writing any terminal code.

**Files:**
- Modify (temporarily, reverted in Step 4): `app/src/main/resources/app/drydock/ui/terminal-dark.conf`

- [ ] **Step 1: Add a deliberately huge font size to the dark terminal config**

Append this line to `app/src/main/resources/app/drydock/ui/terminal-dark.conf`:

```
font-size = 24
```

- [ ] **Step 2: Run the app and open a session**

Run: `./gradlew :app:run`

Add a repository if none is present, then start a Claude session or open a terminal tab so a ghostty surface appears.

- [ ] **Step 3: Record the outcome**

Expected: terminal text is obviously much larger than the surrounding UI.

- If it IS larger → the assumption holds. Continue to Step 4 and then Task 2.
- If it is NOT larger → **stop**. Terminal font size is cut from this round per the spec's "Deliberately out of scope" rule. Report this, remove the terminal-size slider from Task 8's modal, skip Tasks 5 and 6, and drop `terminalFontSize` from Tasks 2 and 3. Do not fall back to the per-surface `font_size` struct write — the spec documents why that route is a trap.

- [ ] **Step 4: Revert the probe**

```bash
git checkout app/src/main/resources/app/drydock/ui/terminal-dark.conf
git status   # expect: clean
```

No commit — this task produces knowledge, not code.

---

### Task 2: Add the two font-size fields to `WorkspaceUiState`

**Files:**
- Modify: `app/src/main/java/app/drydock/domain/WorkspaceUiState.java`

**Interfaces:**
- Produces: `WorkspaceUiState(Optional<RepositoryId> selectedRepositoryId, double sidebarWidth, Set<RepositoryId> expandedRepositoryIds, UiTheme theme, double uiFontSize, double terminalFontSize)`; constants `DEFAULT_UI_FONT_SIZE = 13.0`, `DEFAULT_TERMINAL_FONT_SIZE = 13.0`; methods `withUiFontSize(double)`, `withTerminalFontSize(double)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/domain/WorkspaceUiStateTest.java`:

```java
package app.drydock.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The font-size fields default to the design's 13px base and copy
 * independently of every other field.
 */
class WorkspaceUiStateTest {

    @Test
    void emptyUsesTheThirteenPixelDesignBase() {
        WorkspaceUiState empty = WorkspaceUiState.empty();

        assertEquals(13.0, empty.uiFontSize());
        assertEquals(13.0, empty.terminalFontSize());
    }

    @Test
    void withUiFontSizeLeavesEveryOtherFieldAlone() {
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withSidebarWidth(300)
                .withTheme(UiTheme.LIGHT)
                .withUiFontSize(15.5);

        assertEquals(15.5, updated.uiFontSize());
        assertEquals(300, updated.sidebarWidth());
        assertEquals(UiTheme.LIGHT, updated.theme());
        assertEquals(13.0, updated.terminalFontSize());
    }

    @Test
    void withTerminalFontSizeLeavesTheInterfaceSizeAlone() {
        WorkspaceUiState updated = WorkspaceUiState.empty()
                .withUiFontSize(16)
                .withTerminalFontSize(11);

        assertEquals(16, updated.uiFontSize());
        assertEquals(11, updated.terminalFontSize());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.domain.WorkspaceUiStateTest'`
Expected: compilation failure — `cannot find symbol: method uiFontSize()`.

- [ ] **Step 3: Add the fields**

In `WorkspaceUiState.java`, add the two components to the record header (after `theme`), add the constants, and add the two `with…` methods. Every existing `with…` method must be updated to pass the two new components through. The result:

```java
public record WorkspaceUiState(
        Optional<RepositoryId> selectedRepositoryId,
        double sidebarWidth,
        Set<RepositoryId> expandedRepositoryIds,
        UiTheme theme,
        double uiFontSize,
        double terminalFontSize
) {
    /** Design default 288px (handoff README section 2), clamped 220-520 at the SplitPane. */
    public static final double DEFAULT_SIDEBAR_WIDTH = 288.0;

    /**
     * The app.css {@code .root} base size, and therefore the divisor every
     * interface-scale factor is computed against (see {@code UiFontScale}).
     */
    public static final double DEFAULT_UI_FONT_SIZE = 13.0;

    /** Matches the interface default so terminals start visually consistent with the UI. */
    public static final double DEFAULT_TERMINAL_FONT_SIZE = 13.0;

    public WorkspaceUiState {
        Objects.requireNonNull(selectedRepositoryId, "selectedRepositoryId");
        expandedRepositoryIds = Set.copyOf(Objects.requireNonNull(expandedRepositoryIds, "expandedRepositoryIds"));
        Objects.requireNonNull(theme, "theme");
    }

    public static WorkspaceUiState empty() {
        return new WorkspaceUiState(Optional.empty(), DEFAULT_SIDEBAR_WIDTH, Set.of(), UiTheme.DARK,
                DEFAULT_UI_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE);
    }

    public WorkspaceUiState withSelectedRepositoryId(Optional<RepositoryId> newSelectedRepositoryId) {
        return new WorkspaceUiState(newSelectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize);
    }

    public WorkspaceUiState withSidebarWidth(double newSidebarWidth) {
        return new WorkspaceUiState(selectedRepositoryId, newSidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, terminalFontSize);
    }

    public WorkspaceUiState withExpandedRepositoryIds(Set<RepositoryId> newExpandedRepositoryIds) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, newExpandedRepositoryIds, theme,
                uiFontSize, terminalFontSize);
    }

    public WorkspaceUiState withTheme(UiTheme newTheme) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, newTheme,
                uiFontSize, terminalFontSize);
    }

    public WorkspaceUiState withUiFontSize(double newUiFontSize) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                newUiFontSize, terminalFontSize);
    }

    public WorkspaceUiState withTerminalFontSize(double newTerminalFontSize) {
        return new WorkspaceUiState(selectedRepositoryId, sidebarWidth, expandedRepositoryIds, theme,
                uiFontSize, newTerminalFontSize);
    }
}
```

Keep the existing class Javadoc and add a sentence noting the two new cosmetic fields.

- [ ] **Step 4: Fix the compilation fallout**

Run: `./gradlew :app:compileJava :app:compileTestJava`

Any direct `new WorkspaceUiState(...)` call sites (in tests and `ApplicationStateCodec`) now need two more arguments. Pass `WorkspaceUiState.DEFAULT_UI_FONT_SIZE` / `WorkspaceUiState.DEFAULT_TERMINAL_FONT_SIZE` at every site — Task 3 replaces the codec's.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:test`
Expected: PASS, including the three new tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/domain/WorkspaceUiState.java \
        app/src/test/java/app/drydock/domain/WorkspaceUiStateTest.java
git add -u
git commit -m "Add interface and terminal font size to the workspace UI state"
```

---

### Task 3: Persist the font sizes through the codec

**Files:**
- Modify: `app/src/main/java/app/drydock/state/ApplicationStateCodec.java` (`uiToJson` ~line 172, `uiFromJson` ~line 291)
- Test: `app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java`

**Interfaces:**
- Consumes: `WorkspaceUiState.uiFontSize()`, `.terminalFontSize()`, `DEFAULT_UI_FONT_SIZE`, `DEFAULT_TERMINAL_FONT_SIZE` (Task 2).
- Produces: JSON members `uiFontSize` / `terminalFontSize` inside the `ui` object.

- [ ] **Step 1: Write the failing tests**

Append to `ApplicationStateCodecTest`:

```java
    @Test
    void fontSizesRoundTrip() {
        ApplicationState state = new ApplicationState(List.of(), List.of(),
                WorkspaceUiState.empty().withUiFontSize(15).withTerminalFontSize(11));

        ApplicationState decoded = ApplicationStateCodec.fromJson(
                JsonParser.parse(JsonWriter.write(ApplicationStateCodec.toJson(state))));

        assertEquals(15, decoded.ui().uiFontSize());
        assertEquals(11, decoded.ui().terminalFontSize());
    }

    @Test
    void absentFontSizesDecodeToTheDefaults() {
        // A state file written before this feature existed: the ui object
        // has no font-size members at all.
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":%d,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK"}}
                """.formatted(ApplicationStateCodec.SCHEMA_VERSION));

        WorkspaceUiState ui = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(13.0, ui.uiFontSize());
        assertEquals(13.0, ui.terminalFontSize());
    }

    @Test
    void malformedFontSizesDecodeToTheDefaultsWithoutFailingTheWholeState() {
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":%d,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "uiFontSize":"enormous","terminalFontSize":null}}
                """.formatted(ApplicationStateCodec.SCHEMA_VERSION));

        WorkspaceUiState ui = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(13.0, ui.uiFontSize());
        assertEquals(13.0, ui.terminalFontSize());
    }

    @Test
    void outOfRangeFontSizeSurvivesTheDecodeUnchanged() {
        // The codec does not clamp -- range ownership belongs to the point
        // of application (ThemeManager / TerminalThemes), exactly like
        // sidebarWidth, which the SplitPane clamps at use. A hand-edited
        // value is honoured as far as it can be, never silently rewritten.
        JsonValue json = JsonParser.parse("""
                {"schemaVersion":%d,"repositories":[],"sessions":[],
                 "ui":{"selectedRepositoryId":null,"sidebarWidth":288.0,
                       "expandedRepositoryIds":[],"theme":"DARK",
                       "uiFontSize":900.0,"terminalFontSize":0.0}}
                """.formatted(ApplicationStateCodec.SCHEMA_VERSION));

        WorkspaceUiState ui = ApplicationStateCodec.fromJson(json).ui();

        assertEquals(900.0, ui.uiFontSize());
        assertEquals(0.0, ui.terminalFontSize());
    }
```

Add `import app.drydock.state.json.JsonWriter;` to the test's imports. If `SCHEMA_VERSION` is not accessible from the test, inline the current literal value instead of the `%d` placeholder.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.state.ApplicationStateCodecTest'`
Expected: FAIL — round-trip returns 13.0 instead of 15/11 (the fields are not encoded yet).

- [ ] **Step 3: Encode the fields**

In `uiToJson`, after the `theme` line:

```java
        obj.put("uiFontSize", JsonNumber.of(ui.uiFontSize()));
        obj.put("terminalFontSize", JsonNumber.of(ui.terminalFontSize()));
```

- [ ] **Step 4: Decode the fields leniently**

In `uiFromJson`, before the `return`:

```java
        // Cosmetic, so lenient like sidebarWidth: absent or non-numeric
        // falls back to the design default. Deliberately NOT clamped here
        // -- ThemeManager and TerminalThemes own their ranges at the point
        // of application, so a hand-edited value is never silently rewritten.
        double uiFontSize = obj.get("uiFontSize") instanceof JsonNumber n
                ? n.asDouble()
                : WorkspaceUiState.DEFAULT_UI_FONT_SIZE;
        double terminalFontSize = obj.get("terminalFontSize") instanceof JsonNumber n
                ? n.asDouble()
                : WorkspaceUiState.DEFAULT_TERMINAL_FONT_SIZE;
```

and extend the constructor call:

```java
        return new WorkspaceUiState(selected, sidebarWidth, expanded, theme, uiFontSize, terminalFontSize);
```

- [ ] **Step 5: Update the schema documentation comment**

The class Javadoc around line 69 documents the `ui` object's shape. Add the two new members to that example so the documented schema matches what is written.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/state/ApplicationStateCodec.java \
        app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java
git commit -m "Persist the font sizes in the workspace UI state"
```

---

### Task 4: Expose the font-size writes on `RepositoryManager`

**Files:**
- Modify: `app/src/main/java/app/drydock/app/RepositoryManager.java` (next to `updateTheme`, ~line 190)

**Interfaces:**
- Produces: `RepositoryManager.updateUiFontSize(double)`, `RepositoryManager.updateTerminalFontSize(double)` — the only sanctioned route from the UI to the persisted font sizes.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/app/drydock/app/RepositoryManagerTest.java`, matching how the existing tests in that file construct a manager (reuse the file's existing setup/fixture helpers rather than inventing new ones):

```java
    @Test
    void updateUiFontSizePersistsWithoutDisturbingTheTheme() {
        manager.updateTheme(UiTheme.LIGHT);

        manager.updateUiFontSize(15.0);

        assertEquals(15.0, manager.state().ui().uiFontSize());
        assertEquals(UiTheme.LIGHT, manager.state().ui().theme());
    }

    @Test
    void updateTerminalFontSizePersistsWithoutDisturbingTheInterfaceSize() {
        manager.updateUiFontSize(15.0);

        manager.updateTerminalFontSize(11.0);

        assertEquals(11.0, manager.state().ui().terminalFontSize());
        assertEquals(15.0, manager.state().ui().uiFontSize());
    }
```

If the existing tests name the manager field differently, use that name.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.app.RepositoryManagerTest'`
Expected: compilation failure — `cannot find symbol: method updateUiFontSize(double)`.

- [ ] **Step 3: Add the two writers**

After `updateTheme`:

```java
    /**
     * Persists the interface font size immediately, like the theme: a
     * discrete user action in the settings modal, cheap to save, and losing
     * it to a crash would be a visible regression on the next launch.
     */
    public void updateUiFontSize(double uiFontSize) {
        stateStore.update(state -> state.withUi(state.ui().withUiFontSize(uiFontSize)));
    }

    /** Persists the terminal font size immediately, for the same reason as {@link #updateUiFontSize}. */
    public void updateTerminalFontSize(double terminalFontSize) {
        stateStore.update(state -> state.withUi(state.ui().withTerminalFontSize(terminalFontSize)));
    }
```

Both are transforms against the single writer, so a concurrent sidebar-width write at shutdown cannot clobber them.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/app/RepositoryManager.java \
        app/src/test/java/app/drydock/app/RepositoryManagerTest.java
git commit -m "Route font-size writes through the single state writer"
```

---

### Task 5: Teach `TerminalThemes` about font size

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/TerminalThemes.java`
- Test: `app/src/test/java/app/drydock/ui/TerminalThemesTest.java` (create)

**Interfaces:**
- Produces: `static Path configFileFor(UiTheme theme, double fontSize)` — replaces the single-argument overload; caches per `(theme, size)`; clamps `fontSize` to 10.0–18.0.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/TerminalThemesTest.java`:

```java
package app.drydock.ui;

import app.drydock.domain.UiTheme;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated ghostty config carries the user's terminal font size, and
 * each (theme, size) pair gets its own file so switching back and forth
 * never re-extracts or cross-contaminates.
 */
class TerminalThemesTest {

    @Test
    void writesTheRequestedFontSizeIntoTheConfig() throws IOException {
        Path config = TerminalThemes.configFileFor(UiTheme.DARK, 15.0);

        String text = Files.readString(config, StandardCharsets.UTF_8);

        assertTrue(text.contains("font-size = 15"), text);
        // The bundled theme content must survive alongside it.
        assertTrue(text.contains("background = #161514"), text);
    }

    @Test
    void clampsAnOutOfRangeSizeToTheSupportedBand() throws IOException {
        Path tooLarge = TerminalThemes.configFileFor(UiTheme.DARK, 900.0);
        Path tooSmall = TerminalThemes.configFileFor(UiTheme.DARK, 0.0);

        assertTrue(Files.readString(tooLarge, StandardCharsets.UTF_8).contains("font-size = 18"));
        assertTrue(Files.readString(tooSmall, StandardCharsets.UTF_8).contains("font-size = 10"));
    }

    @Test
    void cachesPerThemeAndSize() {
        Path darkThirteen = TerminalThemes.configFileFor(UiTheme.DARK, 13.0);

        assertEquals(darkThirteen, TerminalThemes.configFileFor(UiTheme.DARK, 13.0));
        assertNotEquals(darkThirteen, TerminalThemes.configFileFor(UiTheme.DARK, 14.0));
        assertNotEquals(darkThirteen, TerminalThemes.configFileFor(UiTheme.LIGHT, 13.0));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.TerminalThemesTest'`
Expected: compilation failure — `configFileFor(UiTheme, double)` does not exist.

- [ ] **Step 3: Rewrite `TerminalThemes`**

```java
package app.drydock.ui;

import app.drydock.domain.UiTheme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides the on-disk ghostty config file matching each {@link UiTheme} and
 * terminal font size. The theme bodies live as classpath resources ({@code
 * terminal-dark.conf} / {@code terminal-light.conf}, kept next to the theme
 * CSS whose tokens they mirror) but {@code ghostty_config_load_file} needs a
 * real path, so each (theme, size) pair is materialised once per process
 * into a private temp directory.
 *
 * <p>The font size rides in the config rather than in {@code
 * ghostty_surface_config_s.font_size} deliberately: a theme toggle calls
 * {@code ghostty_surface_update_config}, which re-derives a running
 * surface's configuration from the app config and would drop a per-surface
 * override, silently resetting the user's terminal size mid-session. Going
 * through the config file instead means a size change applies live to
 * running surfaces over the very same path.</p>
 */
final class TerminalThemes {

    /** Supported terminal font sizes; the settings slider exposes the same band. */
    static final double MIN_FONT_SIZE = 10.0;
    static final double MAX_FONT_SIZE = 18.0;

    private static final Map<Key, Path> EXTRACTED = new HashMap<>();

    /** Cache key: one materialised config file per theme and rounded font size. */
    private record Key(UiTheme theme, int fontSize) {
    }

    private TerminalThemes() {
    }

    /**
     * The config file for {@code theme} at {@code fontSize} points, clamped
     * to {@link #MIN_FONT_SIZE}..{@link #MAX_FONT_SIZE} -- this is the point
     * of application, and therefore the owner of the range (the codec
     * deliberately stores whatever it was given).
     */
    static synchronized Path configFileFor(UiTheme theme, double fontSize) {
        int rounded = (int) Math.round(Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE));
        return EXTRACTED.computeIfAbsent(new Key(theme, rounded), TerminalThemes::extract);
    }

    private static Path extract(Key key) {
        String resource = key.theme() == UiTheme.LIGHT ? "terminal-light.conf" : "terminal-dark.conf";
        try (InputStream stream = TerminalThemes.class.getResourceAsStream("/app/drydock/ui/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled terminal theme resource: " + resource);
            }
            String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Path dir = Files.createTempDirectory("drydock-terminal-theme");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve(resource);
            Files.writeString(file, body + System.lineSeparator()
                    + "font-size = " + key.fontSize() + System.lineSeparator(), StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract terminal theme config " + resource, e);
        }
    }
}
```

- [ ] **Step 4: Update the three call sites**

`MainWorkspace.java:437`, `:1181`, and `:1206` call the one-argument form. Each needs the current terminal font size. Add a supplier field next to the existing `themeProvider`:

```java
    /** Where new and re-themed terminals read the persisted font size from. */
    private Supplier<Double> terminalFontSizeProvider = () -> WorkspaceUiState.DEFAULT_TERMINAL_FONT_SIZE;

    /** Wires where terminals read the configured font size from (settings modal). */
    public void setTerminalFontSizeProvider(Supplier<Double> provider) {
        this.terminalFontSizeProvider =
                provider == null ? () -> WorkspaceUiState.DEFAULT_TERMINAL_FONT_SIZE : provider;
    }
```

Then at each call site pass `terminalFontSizeProvider.get()` as the second argument, e.g. line 437 becomes:

```java
        Path configFile = TerminalThemes.configFileFor(theme, terminalFontSizeProvider.get());
```

Import `app.drydock.domain.WorkspaceUiState` if not already imported.

- [ ] **Step 5: Document that the theme path now carries the size too**

Do **not** add a separate `applyTerminalFontSize` method — it would be a
pure delegate to `applyTerminalTheme`, which the reviewer would rightly flag.
Instead extend `applyTerminalTheme`'s Javadoc so the shared purpose is
discoverable from the one method that does the work:

```java
    /**
     * Re-applies the terminal config to every open terminal (called on the FX
     * thread by the theme toggle and by the settings modal's font-size
     * slider). ghostty re-reads the whole config file, so theme and font size
     * travel together over this one path -- which is exactly why the size
     * lives in the config rather than in the per-surface struct, where
     * {@code ghostty_surface_update_config} would discard it.
     */
```

Task 10 calls `applyTerminalTheme(...)` directly after a size change.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/TerminalThemes.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/ui/TerminalThemesTest.java
git commit -m "Carry the terminal font size in the generated ghostty config"
```

---

### Task 6: `UiFontScale` — the runtime-scaled stylesheet

This is where the rejected `em` approach would have failed silently, so the test is the substantive part of the task.

**Files:**
- Create: `app/src/main/java/app/drydock/ui/UiFontScale.java`
- Test: `app/src/test/java/app/drydock/ui/UiFontScaleTest.java`

**Interfaces:**
- Produces: `static String stylesheetFor(double fontSize)` returning a stylesheet URL (an external form, suitable for `Scene.getStylesheets()`); `static String scaleCss(String css, double factor)` — package-private, pure, the unit under test.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/UiFontScaleTest.java`:

```java
package app.drydock.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scaling multiplies every {@code -fx-font-size} px literal by the factor
 * and touches nothing else.
 *
 * <p>The nested-rule test is the important one. The rejected alternative --
 * rewriting the declarations as {@code em} -- looks correct line by line but
 * compounds, because JavaFX resolves font-relative sizes against the
 * inherited font rather than against {@code .root}: {@code .code-area}
 * (12px) containing {@code .lineno} (11px) would land at 10.15px instead of
 * 13.54px at factor 16/13. Absolute scaling keeps the ratio exact.</p>
 */
class UiFontScaleTest {

    @Test
    void scalesEveryFontSizeDeclaration() {
        String scaled = UiFontScale.scaleCss(".root { -fx-font-size: 13px; }", 16.0 / 13.0);

        assertTrue(scaled.contains("-fx-font-size: 16.0px"), scaled);
    }

    @Test
    void nestedRulesKeepTheirRatioInsteadOfCompounding() {
        String css = """
                .code-area { -fx-font-size: 12px; }
                .code-area .lineno { -fx-font-size: 11px; }
                """;

        String scaled = UiFontScale.scaleCss(css, 16.0 / 13.0);

        // 12 * 16/13 = 14.769..., 11 * 16/13 = 13.538...
        assertTrue(scaled.contains("-fx-font-size: 14.77px"), scaled);
        assertTrue(scaled.contains("-fx-font-size: 13.54px"), scaled);
    }

    @Test
    void preservesFractionalSourceSizes() {
        String scaled = UiFontScale.scaleCss(".pill { -fx-font-size: 12.5px; }", 2.0);

        assertTrue(scaled.contains("-fx-font-size: 25.0px"), scaled);
    }

    @Test
    void leavesEverythingThatIsNotAFontSizeAlone() {
        String css = """
                /* -fx-font-size: 99px in a comment must not move */
                .filter-field {
                    -fx-min-height: 32px;
                    -fx-max-height: 32px;
                    -fx-background-color: #161514;
                    -fx-font-family: "System";
                    -fx-padding: 0.5em;
                }
                """;

        String scaled = UiFontScale.scaleCss(css, 2.0);

        assertTrue(scaled.contains("-fx-min-height: 32px"), scaled);
        assertTrue(scaled.contains("-fx-max-height: 32px"), scaled);
        assertTrue(scaled.contains("-fx-background-color: #161514"), scaled);
        assertTrue(scaled.contains("-fx-padding: 0.5em"), scaled);
        assertTrue(scaled.contains("99px in a comment"), scaled);
    }

    @Test
    void toleratesSpacingVariantsInTheSourceDeclaration() {
        String scaled = UiFontScale.scaleCss(".a{-fx-font-size:10px}", 2.0);

        assertTrue(scaled.contains("20.0px"), scaled);
        assertFalse(scaled.contains("10px"), scaled);
    }

    @Test
    void identityFactorReturnsTheOriginalResourceUrl() {
        // The unscaled case must not write a temp file at all: it is both the
        // default and the hot path at startup.
        assertEquals(UiFontScale.baseStylesheetUrl(), UiFontScale.stylesheetFor(13.0));
    }

    @Test
    void scaledSizeProducesAUsableStylesheetUrl() {
        String url = UiFontScale.stylesheetFor(16.0);

        assertTrue(url.startsWith("file:"), url);
        assertFalse(url.equals(UiFontScale.baseStylesheetUrl()), url);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.UiFontScaleTest'`
Expected: compilation failure — `UiFontScale` does not exist.

- [ ] **Step 3: Implement `UiFontScale`**

```java
package app.drydock.ui;

import app.drydock.domain.WorkspaceUiState;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a copy of {@code app.css} with every {@code -fx-font-size} scaled
 * by the user's interface size, materialised as a temp file whose URL the
 * scene uses in place of the bundled sheet (the same extract-once-per-process
 * pattern {@link TerminalThemes} uses for ghostty configs).
 *
 * <p>Two tempting alternatives are wrong. Rewriting the declarations as
 * {@code em} compounds, because JavaFX resolves font-relative sizes against
 * the font inherited from the nearest styleable ancestor, not against {@code
 * .root}: a 12px rule containing an 11px rule would multiply both factors.
 * An inline {@code -fx-font-size} on the scene root does override the {@code
 * .root} rule, but never reaches combo popups, context menus, or tooltips --
 * they are separate scene graphs, which is exactly why app.css styles them
 * with their own top-level selectors. A stylesheet reaches them; an inline
 * style does not.</p>
 */
final class UiFontScale {

    /** Supported interface font sizes; the settings slider exposes the same band. */
    static final double MIN_FONT_SIZE = 11.0;
    static final double MAX_FONT_SIZE = 16.0;

    private static final String BASE_RESOURCE = "/app/drydock/ui/app.css";

    /**
     * Matches a {@code -fx-font-size} declaration's px literal, whatever the
     * surrounding spacing. Group 1 is the property and separator (preserved
     * verbatim), group 2 the number.
     */
    private static final Pattern FONT_SIZE =
            Pattern.compile("(-fx-font-size\\s*:\\s*)(\\d+(?:\\.\\d+)?)px");

    private static final Map<Integer, String> GENERATED = new HashMap<>();

    private UiFontScale() {
    }

    /** The bundled, unscaled sheet's URL. */
    static String baseStylesheetUrl() {
        return UiFontScale.class.getResource(BASE_RESOURCE).toExternalForm();
    }

    /**
     * The stylesheet URL for {@code fontSize}, clamped to {@link
     * #MIN_FONT_SIZE}..{@link #MAX_FONT_SIZE} -- this is the point of
     * application and therefore owns the range. The default size returns the
     * bundled resource untouched; other sizes are generated once and cached.
     *
     * <p>Touches the filesystem, so callers on the FX thread must only hit
     * the cached or default path (see {@code ThemeManager}).</p>
     */
    static synchronized String stylesheetFor(double fontSize) {
        double clamped = Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
        int key = (int) Math.round(clamped * 2);   // 0.5px resolution, matching the slider
        if (key == (int) Math.round(WorkspaceUiState.DEFAULT_UI_FONT_SIZE * 2)) {
            return baseStylesheetUrl();
        }
        return GENERATED.computeIfAbsent(key, k -> generate(k / 2.0));
    }

    private static String generate(double fontSize) {
        try (InputStream stream = UiFontScale.class.getResourceAsStream(BASE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled stylesheet: " + BASE_RESOURCE);
            }
            String css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String scaled = scaleCss(css, fontSize / WorkspaceUiState.DEFAULT_UI_FONT_SIZE);
            Path dir = Files.createTempDirectory("drydock-ui-scale");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve("app.css");
            Files.writeString(file, scaled, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file.toUri().toURL().toExternalForm();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not generate the scaled stylesheet", e);
        }
    }

    /**
     * Multiplies every {@code -fx-font-size} px literal by {@code factor},
     * rounded to two decimals, leaving the rest of the text byte-identical.
     */
    static String scaleCss(String css, double factor) {
        Matcher matcher = FONT_SIZE.matcher(css);
        StringBuilder out = new StringBuilder(css.length());
        while (matcher.find()) {
            double scaled = Double.parseDouble(matcher.group(2)) * factor;
            double rounded = Math.round(scaled * 100.0) / 100.0;
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + rounded + "px"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.ui.UiFontScaleTest'`
Expected: PASS. If `leavesEverythingThatIsNotAFontSizeAlone` fails on the comment case, the regex has drifted — it must match only `-fx-font-size` followed by a colon.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/UiFontScale.java \
        app/src/test/java/app/drydock/ui/UiFontScaleTest.java
git commit -m "Generate a font-scaled copy of app.css at runtime"
```

---

### Task 7: Apply the interface size from `ThemeManager`

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/ThemeManager.java`
- Modify: `app/src/main/java/app/drydock/ui/AppShell.java` (constructor ~line 49, `toggleTheme` ~line 143)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (`new AppShell(...)` ~line 191)

**Interfaces:**
- Consumes: `UiFontScale.stylesheetFor(double)`, `UiFontScale.MIN_FONT_SIZE`, `UiFontScale.MAX_FONT_SIZE` (Task 6).
- Produces: `ThemeManager.setTheme(UiTheme)`, `ThemeManager.setUiFontSize(double)`, `ThemeManager.uiFontSize()`; `AppShell.themeManager()` already exposes the manager.

- [ ] **Step 1: Rewrite `ThemeManager`'s apply path**

Replace the constructor, add the absolute setters, and route `apply()` through the scaled sheet:

```java
    private final Scene scene;
    private final Consumer<UiTheme> onThemeChanged;
    private UiTheme theme;
    private double uiFontSize;

    public ThemeManager(Scene scene, UiTheme initialTheme, double initialUiFontSize,
                        Consumer<UiTheme> onThemeChanged) {
        this.scene = scene;
        this.onThemeChanged = onThemeChanged;
        this.theme = initialTheme;
        this.uiFontSize = Math.clamp(initialUiFontSize, UiFontScale.MIN_FONT_SIZE, UiFontScale.MAX_FONT_SIZE);
        loadBundledFonts();
        apply();
    }

    public UiTheme theme() {
        return theme;
    }

    public double uiFontSize() {
        return uiFontSize;
    }

    public void toggle() {
        setTheme(theme.other());
    }

    /** Sets the theme absolutely (the settings modal's radio); {@link #toggle} delegates here. */
    public void setTheme(UiTheme newTheme) {
        if (newTheme == theme) {
            return;
        }
        theme = newTheme;
        apply();
        onThemeChanged.accept(theme);
    }

    /**
     * Applies an interface font size by swapping in a stylesheet whose
     * font sizes are scaled (see {@link UiFontScale}); clamped here because
     * this is the point of application. Persisting the choice is the
     * caller's job, exactly as with the theme.
     */
    public void setUiFontSize(double newUiFontSize) {
        double clamped = Math.clamp(newUiFontSize, UiFontScale.MIN_FONT_SIZE, UiFontScale.MAX_FONT_SIZE);
        if (clamped == uiFontSize) {
            return;
        }
        uiFontSize = clamped;
        apply();
    }

    private void apply() {
        scene.getStylesheets().setAll(
                UiFontScale.stylesheetFor(uiFontSize),
                resource(theme.stylesheet()));
    }
```

Note `apply()` no longer uses `resource("app.css")`; keep `resource(...)` for the theme sheet.

Update the class Javadoc: the scene carries a (possibly font-scaled) `app.css` plus exactly one token sheet.

- [ ] **Step 2: Thread the initial size through `AppShell`**

In `AppShell`'s constructor signature, add `double initialUiFontSize` immediately after `initialTheme`, and pass it to the `ThemeManager` constructor:

```java
        themeManager = new ThemeManager(scene, initialTheme, initialUiFontSize, theme -> {
            titleBar.showThemeGlyphFor(theme);
            onThemeChanged.accept(theme);
        });
```

Applying it in the constructor means the scaled sheet is in place before the stage is shown, so there is no visible re-layout at startup.

- [ ] **Step 3: Pass the persisted value at the call site**

In `DrydockApplication` (~line 191), the `new AppShell(...)` call gains the new argument:

```java
        appShell = new AppShell(primaryStage, WINDOW_TITLE, sidebar, mainWorkspace,
                repositoryManager.state().ui().sidebarWidth(),
                repositoryManager.state().ui().theme(),
                repositoryManager.state().ui().uiFontSize(),
                theme -> {
```

- [ ] **Step 4: Verify it builds and the suite still passes**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 5: Verify by eye**

Run: `./gradlew :app:run`

Expected: the app looks exactly as it did before this task. The stored size is still the 13.0 default, and `UiFontScale.stylesheetFor(13.0)` returns the bundled resource untouched, so any visible change here means the scaling path is being taken when it should not be. Task 11's manual check exercises the scaled path once the slider exists.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/ThemeManager.java \
        app/src/main/java/app/drydock/ui/AppShell.java \
        app/src/main/java/app/drydock/DrydockApplication.java
git commit -m "Apply the interface font size through the theme manager"
```

---

### Task 8: `UserConfig` write support

**Files:**
- Modify: `app/src/main/java/app/drydock/config/UserConfig.java`
- Test: `app/src/test/java/app/drydock/config/UserConfigTest.java`

**Interfaces:**
- Produces: `static void save(UserConfig config, Path configFile) throws IOException`, `static CompletableFuture<Void> saveAsync(UserConfig config)`, `static void flushPendingSaves()`.

- [ ] **Step 1: Write the failing tests**

Append to `UserConfigTest` (it already has a `@TempDir`-style setup — reuse whatever the file uses):

```java
    @Test
    void savedConfigLoadsBackUnchanged() throws Exception {
        Path configFile = tempDir.resolve("config.json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        assertEquals(Optional.of(Path.of("/tmp/worktrees")), UserConfig.load(configFile).worktreesDirectory());
    }

    @Test
    void saveCreatesTheParentDirectory() throws Exception {
        Path configFile = tempDir.resolve("nested").resolve(".drydock").resolve("config.json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        assertTrue(Files.exists(configFile));
    }

    @Test
    void saveOverwritesAMalformedFileAndLeavesNoTempBehind() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{ this is not json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        assertEquals(Optional.of(Path.of("/tmp/worktrees")), UserConfig.load(configFile).worktreesDirectory());
        try (var entries = Files.list(tempDir)) {
            assertEquals(List.of("config.json"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void savePreservesMembersItDoesNotKnowAbout() throws Exception {
        // The file is human-editable and may grow keys this build predates;
        // rewriting it must not silently delete the user's other settings.
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{\"worktreesDirectory\":\"/old\",\"somethingElse\":42}");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        String written = Files.readString(configFile);
        assertTrue(written.contains("somethingElse"), written);
        assertTrue(written.contains("/tmp/worktrees"), written);
    }

    @Test
    void savingAnEmptyConfigClearsTheDirectory() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        UserConfig.save(UserConfig.empty(), configFile);

        assertEquals(Optional.empty(), UserConfig.load(configFile).worktreesDirectory());
    }
```

Add imports as needed (`java.util.List`, `java.nio.file.Files`, `org.junit.jupiter.api.Assertions.assertTrue`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.config.UserConfigTest'`
Expected: compilation failure — `cannot find symbol: method save(UserConfig, Path)`.

- [ ] **Step 3: Implement `save`**

Add to `UserConfig`, using the repository's own JSON writer:

```java
    /**
     * Writes {@code config} to {@code configFile}: temp file in the same
     * directory, then an atomic move, so a crash mid-write can never leave a
     * truncated config where a valid one was.
     *
     * <p>Members this build does not know about are read back from the
     * existing file and preserved -- the file is hand-editable, so silently
     * dropping a key a newer build (or the user) put there would be data
     * loss.</p>
     *
     * <p>Unlike {@link #load()}, a failure here throws: a save is a
     * user-visible action, and one that silently did nothing is worse than
     * one that reports why.</p>
     */
    static void save(UserConfig config, Path configFile) throws IOException {
        JsonObject root = JsonObject.empty();
        if (Files.exists(configFile)) {
            try {
                if (JsonParser.parse(Files.readString(configFile, StandardCharsets.UTF_8))
                        instanceof JsonObject existing) {
                    root = existing;
                }
            } catch (IOException | JsonParseException e) {
                LOG.log(Level.WARNING, "Existing config " + configFile
                        + " is unreadable or malformed; replacing it", e);
            }
        }
        root.members().remove("worktreesDirectory");
        config.worktreesDirectory().ifPresent(dir ->
                root.put("worktreesDirectory", new JsonString(dir.toString())));

        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "config", ".json.tmp");
        try {
            Files.writeString(temp, JsonWriter.write(root), StandardCharsets.UTF_8);
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
```

Imports to add: `app.drydock.state.json.JsonWriter`, `java.nio.file.StandardCopyOption`.

If `JsonObject.members()` returns an immutable map, build a fresh `JsonObject` and copy the existing members into it instead of removing in place — check `JsonValue.java:22-43` and follow whichever the record supports.

- [ ] **Step 4: Implement `saveAsync` and `flushPendingSaves`**

```java
    private static final AtomicReference<CompletableFuture<Void>> PENDING_SAVE =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    /**
     * As {@link #save}, off the caller's thread -- the settings modal calls
     * this from the FX thread, where a synchronous write is forbidden. The
     * returned future completes exceptionally on failure so the caller can
     * surface it; it is never swallowed.
     */
    public static CompletableFuture<Void> saveAsync(UserConfig config) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        PENDING_SAVE.set(future);
        Thread.ofVirtual().start(() -> {
            try {
                save(config, defaultConfigFile());
                future.complete(null);
            } catch (IOException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Awaits any in-flight {@link #saveAsync} (AGENTS.md: a service writing
     * files from a background thread exposes a flush, so shutdown and tests
     * do not race a pending write). Bounded, because a wedged disk must not
     * hang shutdown -- the atomic move means the worst case is a stale file,
     * never a corrupt one.
     */
    public static void flushPendingSaves() {
        try {
            PENDING_SAVE.get().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // saveAsync's caller already reported the failure to the user.
        }
    }
```

Imports: `java.util.concurrent.atomic.AtomicReference`, `java.util.concurrent.ExecutionException`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.TimeoutException`.

- [ ] **Step 5: Call the flush at shutdown**

In `DrydockApplication.stop()`, after the sidebar-width block:

```java
        closeQuietly("UserConfig saves", UserConfig::flushPendingSaves);
```

Import `app.drydock.config.UserConfig`.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/config/UserConfig.java \
        app/src/main/java/app/drydock/DrydockApplication.java \
        app/src/test/java/app/drydock/config/UserConfigTest.java
git commit -m "Let the user config be written, atomically and off the FX thread"
```

---

### Task 9: The settings modal

**Files:**
- Create: `app/src/main/java/app/drydock/ui/SettingsModal.java`
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (new settings rules)

**Interfaces:**
- Consumes: `ThemeManager.setTheme/setUiFontSize/theme/uiFontSize` (Task 7), `RepositoryManager.updateUiFontSize/updateTerminalFontSize` (Task 4), `UserConfig.loadAsync/saveAsync` (Task 8), `UiFontScale.MIN_FONT_SIZE/MAX_FONT_SIZE` (Task 6), `TerminalThemes.MIN_FONT_SIZE/MAX_FONT_SIZE` (Task 5), `UiErrors.show(String title, Throwable failure)`.
- Produces: `public final class SettingsModal extends VBox` with `public SettingsModal(Settings settings, Runnable onClose)` and the `public interface SettingsModal.Settings` defined below.

`SettingsModal` is **public**, matching `GitHubCloneModal` and `RemoteRepositoryModal` (`GitHubCloneModal.java:32`, `RemoteRepositoryModal.java:30`) — the modals constructed from `DrydockApplication`, which lives in a different package. `ShortcutsOverlay` is package-private only because nothing outside `app.drydock.ui` builds it.

- [ ] **Step 1: Define the modal's collaborator interface and skeleton**

Create `SettingsModal.java`. It takes one bundle of callbacks so it depends on no manager directly — the same shape the other modals use:

```java
package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

/**
 * The Settings modal, reached from the title-bar gear or ⌘,. Four settings,
 * applied as they change (the macOS preferences convention -- there is no
 * OK/Cancel; Done, Esc, and × all just close).
 *
 * <p>Holds no manager or store: everything it can change arrives as a
 * callback in {@link Settings}, so persistence stays with the single state
 * writer and this class stays a view.</p>
 */
public final class SettingsModal extends VBox {

    /** Everything the modal reads and writes, supplied by the application wiring. */
    public interface Settings {
        UiTheme theme();

        void setTheme(UiTheme theme);

        double uiFontSize();

        /** Applies the interface size live; persistence is the implementation's job. */
        void setUiFontSize(double size);

        double terminalFontSize();

        /** Applies the terminal size to running surfaces live; persistence likewise. */
        void setTerminalFontSize(double size);

        CompletableFuture<Optional<Path>> loadWorktreesDirectory();

        CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory);
    }
}
```

- [ ] **Step 2: Build the layout**

Fill in the constructor, following `StartSessionModal`'s structure (header with title + `×`, body, footer):

```java
    private static final double MODAL_WIDTH = 520;

    public SettingsModal(Settings settings, Runnable onClose) {
        getStyleClass().add("modal");
        setMaxWidth(MODAL_WIDTH);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        Label title = new Label("Settings");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Button done = new Button("Done");
        done.getStyleClass().add("primary-button");
        done.setDefaultButton(true);
        done.setOnAction(e -> onClose.run());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, footerSpacer, done);

        getChildren().addAll(header,
                sectionTitle("Appearance"),
                themeRow(settings),
                sizeRow("Interface size", UiFontScale.MIN_FONT_SIZE, UiFontScale.MAX_FONT_SIZE,
                        settings.uiFontSize(), settings::setUiFontSize),
                sizeRow("Terminal size", TerminalThemes.MIN_FONT_SIZE, TerminalThemes.MAX_FONT_SIZE,
                        settings.terminalFontSize(), settings::setTerminalFontSize),
                sectionTitle("Worktrees"),
                worktreesRow(settings),
                footer);
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-section-title");
        return label;
    }
```

Use `primary-button` only if that style class already exists in app.css; otherwise use the plain default (grep app.css for the class the other modals' confirm buttons use and match it).

- [ ] **Step 3: Implement the theme row**

```java
    private static Region themeRow(Settings settings) {
        ToggleGroup group = new ToggleGroup();
        RadioButton dark = new RadioButton("Dark");
        RadioButton light = new RadioButton("Light");
        dark.setToggleGroup(group);
        light.setToggleGroup(group);
        (settings.theme() == UiTheme.LIGHT ? light : dark).setSelected(true);

        // Applied on selection, not on Done: the whole modal is apply-on-change.
        group.selectedToggleProperty().addListener((obs, old, selected) ->
                settings.setTheme(selected == light ? UiTheme.LIGHT : UiTheme.DARK));

        return labelled("Theme", new HBox(12, dark, light));
    }

    /** A settings row: a fixed-width caption on the left, the control on the right. */
    private static Region labelled(String caption, Region control) {
        Label label = new Label(caption);
        label.getStyleClass().add("settings-row-label");
        label.setMinWidth(120);
        HBox row = new HBox(12, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }
```

- [ ] **Step 4: Implement the size rows**

```java
    /**
     * A font-size slider. The value applies live while dragging so the effect
     * is visible, but the persisting callback fires only when the drag ends --
     * a state write per pixel would be pointless disk traffic.
     */
    private static Region sizeRow(String caption, double min, double max, double initial,
                                  DoubleConsumer onChanged) {
        Slider slider = new Slider(min, max, Math.clamp(initial, min, max));
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(1);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(1);

        Label value = new Label(format(slider.getValue()));
        value.getStyleClass().add("settings-value");
        value.setMinWidth(52);

        slider.valueProperty().addListener((obs, old, now) -> {
            value.setText(format(now.doubleValue()));
            onChanged.accept(now.doubleValue());
        });

        HBox control = new HBox(10, slider, value);
        control.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return labelled(caption, control);
    }

    /** "13 px" / "13.5 px" -- no trailing zero on whole sizes. */
    static String format(double size) {
        return (size == Math.rint(size)
                ? String.valueOf((int) size)
                : String.valueOf(Math.round(size * 10) / 10.0)) + " px";
    }
```

The listener applies on every change, which for a snap-to-tick slider is once per whole step, not once per pixel — so live application and persistence coincide and no debounce is needed. `Settings`' implementations (Task 10) are what persist.

- [ ] **Step 5: Implement the worktrees row**

```java
    private static Region worktreesRow(Settings settings) {
        TextField field = new TextField();
        field.setPromptText("Loading…");
        field.setDisable(true);
        Button browse = new Button("Browse…");
        browse.setDisable(true);

        Label hint = new Label("New worktrees are created here.");
        hint.getStyleClass().add("settings-hint");

        // Load off the FX thread (UserConfig reads the file); the controls
        // stay disabled with a "Loading…" prompt until it lands, so the row
        // never shows a stale or empty value as if it were the real one.
        settings.loadWorktreesDirectory().whenComplete((directory, failure) -> Platform.runLater(() -> {
            field.setDisable(false);
            browse.setDisable(false);
            field.setPromptText(System.getProperty("user.home") + "/dev/wt");
            if (failure == null) {
                directory.ifPresent(dir -> field.setText(dir.toString()));
            } else {
                UiErrors.show("Could not read the settings file", failure);
            }
        }));

        Runnable commit = () -> {
            String text = field.getText() == null ? "" : field.getText().strip();
            Optional<Path> directory = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
            field.setDisable(true);
            browse.setDisable(true);
            settings.saveWorktreesDirectory(directory).whenComplete((ignored, failure) ->
                    Platform.runLater(() -> {
                        // Every path re-enables: success, failure, and the
                        // early return inside saveWorktreesDirectory.
                        field.setDisable(false);
                        browse.setDisable(false);
                        if (failure != null) {
                            UiErrors.show("Could not save the worktrees directory", failure);
                        }
                    }));
        };

        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, had, has) -> {
            if (!has) {
                commit.run();
            }
        });

        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose the worktrees directory");
            File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
            if (chosen != null) {
                field.setText(chosen.getAbsolutePath());
                commit.run();
            }
        });

        HBox control = new HBox(8, field, browse);
        control.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return new VBox(4, labelled("Directory", control), hint);
    }
```

`worktreesRow` calls `getScene()`, so it is an **instance** method — `private Region worktreesRow(Settings settings)` — while `sectionTitle`, `labelled`, `sizeRow`, and `themeRow` stay `private static`. In Step 2's constructor the call is therefore `worktreesRow(settings)` (unqualified, on `this`), which is what the listing already shows.

`UiErrors.show(String title, Throwable failure)` is package-private static in `app.drydock.ui` (`UiErrors.java:32`), so `SettingsModal` can call it directly with the two arguments shown.

- [ ] **Step 6: Add the CSS**

Append to `app/src/main/resources/app/drydock/ui/app.css`, matching the token names the neighbouring modal rules use (grep for `-drydock-muted` / `-drydock-text` to confirm):

```css
/* --- Settings modal ------------------------------------------------- */
.settings-section-title {
    -fx-font-size: 11px;
    -fx-text-fill: -drydock-muted;
    -fx-padding: 6px 0 0 0;
}

.settings-row-label {
    -fx-font-size: 12.5px;
    -fx-text-fill: -drydock-text;
}

.settings-value {
    -fx-font-size: 12px;
    -fx-text-fill: -drydock-muted;
}

.settings-hint {
    -fx-font-size: 11px;
    -fx-text-fill: -drydock-muted;
    -fx-padding: 0 0 0 132px;
}
```

These px font sizes are correct: `UiFontScale` scales them along with the rest of the sheet.

- [ ] **Step 7: Write the formatter test**

Create `app/src/test/java/app/drydock/ui/SettingsModalTest.java`:

```java
package app.drydock.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The slider's readout: whole sizes lose the decimal, halves keep it. */
class SettingsModalTest {

    @Test
    void formatsWholeSizesWithoutADecimal() {
        assertEquals("13 px", SettingsModal.format(13.0));
    }

    @Test
    void formatsHalfSizesWithOneDecimal() {
        assertEquals("13.5 px", SettingsModal.format(13.5));
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew :app:test`
Expected: PASS. The modal itself is not instantiated in tests (it needs a JavaFX toolkit); its logic lives in `format` and in the Task 10 wiring.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/ui/SettingsModal.java \
        app/src/main/resources/app/drydock/ui/app.css \
        app/src/test/java/app/drydock/ui/SettingsModalTest.java
git commit -m "Add the settings modal"
```

---

### Task 10: Wire the modal to the gear button and ⌘,

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/TitleBar.java`
- Modify: `app/src/main/java/app/drydock/ui/AppShell.java`
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` (`SHORTCUTS`, ~line 21)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (`installGlobalShortcuts` ~line 523, wiring ~line 191)

**Interfaces:**
- Consumes: `SettingsModal(Settings, Runnable)` (Task 9), `AppShell.modalLayer()`, `AppShell.themeManager()`, `MainWorkspace.applyTerminalTheme(UiTheme)` (Task 5).
- Produces: `AppShell.setOnShowSettings(Runnable)` and `AppShell.showSettings()`.

- [ ] **Step 1: Add the gear button to the title bar**

In `TitleBar`, add the callback parameter and the button. The constructor becomes:

```java
    TitleBar(Stage stage, String title, Runnable onHelp, Runnable onSettings, Runnable onThemeToggle,
             Runnable onSidebarToggle) {
```

and the right-hand group:

```java
        Button helpButton = iconButton("?", "Keyboard shortcuts (?)");
        helpButton.setOnAction(e -> onHelp.run());
        Button settingsButton = iconButton("⚙", "Settings (⌘,)");
        settingsButton.setOnAction(e -> onSettings.run());
        themeButton.setOnAction(e -> onThemeToggle.run());
        HBox right = new HBox(4, helpButton, settingsButton, themeButton);
```

- [ ] **Step 2: Expose it on `AppShell`**

```java
    private Runnable onShowSettings = () -> { };

    /** Wires the gear button and ⌘, to the settings modal (supplied by the application). */
    public void setOnShowSettings(Runnable onShowSettings) {
        this.onShowSettings = onShowSettings == null ? () -> { } : onShowSettings;
    }

    public void showSettings() {
        onShowSettings.run();
    }
```

and pass `this::showSettings` as the new `TitleBar` argument in the constructor.

- [ ] **Step 3: Add the shortcut row**

In `ShortcutsOverlay.SHORTCUTS`, after the `Toggle theme` entry:

```java
            {"Settings", "⌘,"},
```

- [ ] **Step 4: Handle ⌘, in the global shortcut filter**

In `installGlobalShortcuts`, add a branch alongside the others:

```java
            } else if (cmd && event.getCode() == KeyCode.COMMA) {
                // Inert while another modal is up: ⌘, must never replace an
                // in-progress Start-session or New-worktree modal underneath
                // the user. (The gear button is unreachable then anyway --
                // the backdrop covers the title bar.)
                if (!appShell.modalLayer().isShowingModal()) {
                    appShell.showSettings();
                }
                event.consume();
```

- [ ] **Step 5: Wire the `Settings` implementation**

In `DrydockApplication.start`, after the `mainWorkspace.setThemeProvider(...)` line, add the terminal-size provider and the settings wiring:

```java
        mainWorkspace.setTerminalFontSizeProvider(
                () -> repositoryManager.state().ui().terminalFontSize());

        appShell.setOnShowSettings(() -> appShell.modalLayer().show(
                new SettingsModal(new SettingsModal.Settings() {
                    @Override
                    public UiTheme theme() {
                        return appShell.themeManager().theme();
                    }

                    @Override
                    public void setTheme(UiTheme theme) {
                        // Persists and re-themes terminals via AppShell's own
                        // onThemeChanged callback; no duplicate write here.
                        appShell.themeManager().setTheme(theme);
                    }

                    @Override
                    public double uiFontSize() {
                        return appShell.themeManager().uiFontSize();
                    }

                    @Override
                    public void setUiFontSize(double size) {
                        appShell.themeManager().setUiFontSize(size);
                        repositoryManager.updateUiFontSize(size);
                    }

                    @Override
                    public double terminalFontSize() {
                        return repositoryManager.state().ui().terminalFontSize();
                    }

                    @Override
                    public void setTerminalFontSize(double size) {
                        // Persist first: applyTerminalFontSize re-reads the
                        // size through the provider above.
                        repositoryManager.updateTerminalFontSize(size);
                        mainWorkspace.applyTerminalTheme(appShell.themeManager().theme());
                    }

                    @Override
                    public CompletableFuture<Optional<Path>> loadWorktreesDirectory() {
                        return UserConfig.loadAsync().thenApply(UserConfig::worktreesDirectory);
                    }

                    @Override
                    public CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory) {
                        return UserConfig.saveAsync(new UserConfig(directory));
                    }
                }, appShell.modalLayer()::close)));
```

Add `import app.drydock.ui.SettingsModal;` alongside the existing `GitHubCloneModal` / `RemoteRepositoryModal` imports at the top of `DrydockApplication` (both are public for exactly this reason, and Task 9 makes `SettingsModal` public to match). Also import `app.drydock.config.UserConfig`, `java.nio.file.Path`, `java.util.Optional`, and `java.util.concurrent.CompletableFuture` if they are not already present.

- [ ] **Step 6: Run the tests and the app**

Run: `./gradlew :app:test`
Expected: PASS.

Run: `./gradlew :app:run`

Verify by hand:
1. The gear appears between `?` and the theme glyph; clicking it opens Settings.
2. `⌘,` opens it; `⌘,` again while it is open does nothing (no stacked modal).
3. Esc, `×`, and Done all close it.
4. `?` shows `Settings ⌘,` in the shortcuts list.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/TitleBar.java \
        app/src/main/java/app/drydock/ui/AppShell.java \
        app/src/main/java/app/drydock/ui/ShortcutsOverlay.java \
        app/src/main/java/app/drydock/ui/SettingsModal.java \
        app/src/main/java/app/drydock/DrydockApplication.java
git commit -m "Open settings from the title-bar gear and command-comma"
```

---

### Task 11: End-to-end verification and the manual checklist

**Files:**
- Modify: `docs/manual-terminal-checklist.md`

- [ ] **Step 1: Add the checklist entries**

Append to `docs/manual-terminal-checklist.md`:

```markdown
## Settings — terminal font size

1. Open a Claude session so a ghostty surface is live.
2. Open Settings (⌘,) and drag **Terminal size** to 18.
3. The running terminal's text grows immediately — not only new sessions.
4. Toggle the theme (⌘⇧L). The terminal re-themes and **keeps** size 18.
   (A regression here means the size went back to a per-surface override,
   which `ghostty_surface_update_config` discards.)
5. Quit and relaunch. The terminal opens at 18.

## Settings — interface font size

1. Open Settings and sweep **Interface size** across 11 → 16.
2. At both extremes check for clipping in: the title bar and traffic
   lights, the sidebar filter field, the icon buttons, a combo-box popup
   (New worktree ▸ Fork from), a right-click context menu, and a tooltip.
   The popups and menus must scale with everything else — they are separate
   scene graphs, and an implementation that only styled the main scene would
   leave them at 13px.
3. Fixed-height controls (filter field 32px, icon buttons 30px, title bar
   44px) do not grow; confirm their text still fits at 16.
4. Quit and relaunch. The size is restored with no visible re-layout flash.
```

- [ ] **Step 2: Run the whole suite**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 3: Walk the checklist**

Run: `./gradlew :app:run` and perform every step above. Record any clipping found at 16px; if a control genuinely breaks, narrow `UiFontScale.MAX_FONT_SIZE` and note it in the spec rather than shipping a broken maximum.

- [ ] **Step 4: Verify persistence end to end**

```bash
cat ~/.drydock/config.json
```
Expected: contains `worktreesDirectory` if one was set in the modal.

Find the state file (`ApplicationStateRepository.stateFile()`; it sits next to the config under `~/.drydock/`) and confirm its `ui` object now carries `uiFontSize` and `terminalFontSize`.

- [ ] **Step 5: Commit**

```bash
git add docs/manual-terminal-checklist.md
git commit -m "Add settings checks to the manual checklist"
```

---

## Notes for the implementer

- **Task 1 is a gate, not a formality.** If libghostty ignores `font-size` in the config file, terminal font size is cut from this round. Do not substitute the per-surface `ghostty_surface_config_s.font_size` write — the spec explains at length why that route silently reverts on the next theme toggle.
- **Do not convert `app.css` to `em`.** It is the obvious-looking approach and it is wrong; `UiFontScaleTest.nestedRulesKeepTheirRatioInsteadOfCompounding` exists to catch anyone who tries.
- **Do not add clamping to the codec.** Ranges belong to `ThemeManager` and `TerminalThemes`. `ApplicationStateCodecTest.outOfRangeFontSizeSurvivesTheDecodeUnchanged` locks this in.
