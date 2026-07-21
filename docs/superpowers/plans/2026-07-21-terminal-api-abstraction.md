# terminal-api Abstraction + Claude/Terminal Split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a technology-neutral `app.drydock.terminal.api` abstraction in place (Ghostty behind it, all `ui`/`app` consumers rewired to it), then split the session tab's Terminal sub-tab into a **Claude** tab (the CLI, unchanged) and a plain login-shell **Terminal** tab.

**Architecture:** Extract interfaces (`TerminalRuntime`, `TerminalSurface`, `TerminalHostView`) + value types (`TerminalSpec`, `Shortcut`) into `terminal.api`; the existing `terminal.ghostty` / `terminal.host` classes implement them. All AppKit-keycode translation stays impl-internal — the api absorbs it behind `TerminalSurface.dispatchKeyEvent`, `submitLine`, and raw-modifier mouse methods, so `ui` never imports a keycode again. A single concrete `TerminalFactory` (in the `terminal` root package) is the only impl-aware seam consumers touch for construction. No Gradle restructuring (that is Phase B).

**Tech Stack:** Java 26, JavaFX 26, Java FFM (Foreign Function & Memory API), libghostty, JUnit 5, Gradle 8.11.1.

## Global Constraints

- **JDK toolchain 26**; Gradle itself runs on JDK ≤ 24 (see README "Gradle on JDK 26"). Build/test with `JAVA_HOME` pointed at a JDK 23, e.g. `export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem`.
- **No fully-qualified class names in code** — imports only, except same-name-different-package collisions (project memory rule).
- **Single Gradle module** — `settings.gradle.kts` stays `include(":app")`. Do NOT add subprojects or `module-info.java`.
- **macOS only, both `arm64` and `x86_64`** — never hard-code an architecture; native-lib selection stays inside the `terminal.ghostty`/`terminal.host` packages.
- **Claude path behavior is unchanged** — the `claude` launch command, env cleanup, activity hooks, resume fallback, persistence, and clipboard/key handling must be byte-for-byte equivalent after the refactor.
- **Compile-green at every task boundary**: the verification gate is `./gradlew compileJava compileTestJava compileSpikeJava test` — it MUST pass before each commit. **`compileSpikeJava` is mandatory and easy to forget**: the `spike` source set (`app/src/spike/java`, package `app.drydock.terminal`) contains `Gate0cSpike`/`Gate0dSpike`/`Gate0eSpike`, which import the concrete terminal classes but are NOT compiled by `compileJava`, `test`, `check`, or `build`. Omitting `compileSpikeJava` lets a signature break land silently. The spikes call `GhosttyApp.create` / `GhosttySurface.create` / `DrydockTerminalHost.createForCurrentWindow` (all kept) and `host.setKeyEventListener(<5-arg lambda>)` (binds structurally to the relocated SAM), so they are expected to keep compiling unchanged — the gate is what proves it.
- The shell **Terminal** tab is **ephemeral** (never persisted/resumed) and **lazy** (created on first switch to it).

---

### Task 1: `TerminalSpec` value type + relocate `Shortcut` to `terminal.api`

Establishes the neutral value types. `Shortcut` currently lives nested in `GhosttyKeyTranslator`; move it so `ui` can depend on it without importing a ghostty class. `TerminalSpec` becomes the "what to run" seam, with the login-shell factory the shell tab will use.

**Files:**
- Create: `app/src/main/java/app/drydock/terminal/api/Shortcut.java`
- Create: `app/src/main/java/app/drydock/terminal/api/TerminalSpec.java`
- Test: `app/src/test/java/app/drydock/terminal/api/TerminalSpecTest.java`
- Modify: `app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java` (remove nested `Shortcut`, import `app.drydock.terminal.api.Shortcut`)
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java:8` (import moves to `app.drydock.terminal.api.Shortcut`)
- Modify: `app/src/main/java/app/drydock/ui/TerminalBridge.java` (import `Shortcut` from `terminal.api`)

**Interfaces:**
- Produces: `enum Shortcut { TERMINAL_SUB_TAB, EXPLORER_SUB_TAB, REVIEW_SUB_TAB, PREVIOUS_SESSION_TAB, NEXT_SESSION_TAB, TOGGLE_SIDEBAR }` in package `app.drydock.terminal.api`.
- Produces: `record TerminalSpec(String command, String workingDirectory)` with `static TerminalSpec loginShell(String workingDirectory)`.

- [ ] **Step 1: Write the failing test**

```java
// app/src/test/java/app/drydock/terminal/api/TerminalSpecTest.java
package app.drydock.terminal.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalSpecTest {

    @Test
    void loginShellRunsTheDefaultShellInteractivelyInTheGivenDirectory() {
        TerminalSpec spec = TerminalSpec.loginShell("/work/repo");
        // exec replaces /bin/sh (libghostty runs the command via `/bin/sh -c`)
        // with the user's $SHELL as a login shell, falling back to zsh.
        assertEquals("exec ${SHELL:-/bin/zsh} -l", spec.command());
        assertEquals("/work/repo", spec.workingDirectory());
    }

    @Test
    void plainConstructorKeepsCommandAndDirectoryVerbatim() {
        TerminalSpec spec = new TerminalSpec("claude --resume 'x'", "/work/repo");
        assertEquals("claude --resume 'x'", spec.command());
        assertEquals("/work/repo", spec.workingDirectory());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'app.drydock.terminal.api.TerminalSpecTest'`
Expected: FAIL — `TerminalSpec`/`Shortcut` do not exist (compilation error).

- [ ] **Step 3: Create `Shortcut` and `TerminalSpec`**

```java
// app/src/main/java/app/drydock/terminal/api/Shortcut.java
package app.drydock.terminal.api;

/** A neutral, app-level keyboard shortcut a terminal surface intercepts and reports to the UI. */
public enum Shortcut {
    /** ⌘1 -- switch to the Terminal sub-tab. */
    TERMINAL_SUB_TAB,
    /** ⌘2 -- switch to the Explorer sub-tab. */
    EXPLORER_SUB_TAB,
    /** ⌘3 -- switch to the Review sub-tab. */
    REVIEW_SUB_TAB,
    /** ⌘⇧[ -- select the previous session tab. */
    PREVIOUS_SESSION_TAB,
    /** ⌘⇧] -- select the next session tab. */
    NEXT_SESSION_TAB,
    /** ⌘0 -- toggle the sidebar. */
    TOGGLE_SIDEBAR
}
```

```java
// app/src/main/java/app/drydock/terminal/api/TerminalSpec.java
package app.drydock.terminal.api;

/**
 * What a terminal surface should run: a single POSIX shell command string
 * (libghostty always executes it via {@code /bin/sh -c "<command>"}) and the
 * working directory to start it in. The seam that makes "run Claude" and "run
 * a plain shell" just two different specs of the same surface machinery.
 */
public record TerminalSpec(String command, String workingDirectory) {

    /**
     * A plain interactive login shell: {@code exec} replaces {@code /bin/sh}
     * with the user's {@code $SHELL} (falling back to {@code /bin/zsh}) as a
     * login shell ({@code -l}), so it reads the user's normal profile.
     */
    public static TerminalSpec loginShell(String workingDirectory) {
        return new TerminalSpec("exec ${SHELL:-/bin/zsh} -l", workingDirectory);
    }
}
```

- [ ] **Step 4: Relocate `Shortcut` out of `GhosttyKeyTranslator`**

In `app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java`: delete the nested `public enum Shortcut { ... }` block, and add `import app.drydock.terminal.api.Shortcut;` at the top. The `AppShortcut(Shortcut shortcut, boolean keyDown)` record and every other `Shortcut` reference now resolve to the imported api enum — no other change to that file.

- [ ] **Step 5: Fix the three files importing the relocated `Shortcut`**

- `OpenSessionTab.java`: change line 8 `import app.drydock.terminal.ghostty.GhosttyKeyTranslator.Shortcut;` → `import app.drydock.terminal.api.Shortcut;`.
- `TerminalBridge.java`: change `import app.drydock.terminal.ghostty.GhosttyKeyTranslator.Shortcut;` → `import app.drydock.terminal.api.Shortcut;` (keep the other `GhosttyKeyTranslator.*` imports for now; they are removed in Task 6).
- `app/src/test/java/app/drydock/terminal/ghostty/GhosttyKeyTranslatorTest.java`: it imports `app.drydock.terminal.ghostty.GhosttyKeyTranslator.Shortcut` (line ~7) — change it to `import app.drydock.terminal.api.Shortcut;`. (The enum-name strings in its `@CsvSource` are unchanged by this task — Task 1 only relocates, it does not rename.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS — `TerminalSpecTest` green; existing `GhosttyKeyTranslator` tests still green (translation logic unchanged).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/terminal/api app/src/test/java/app/drydock/terminal/api \
        app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java \
        app/src/main/java/app/drydock/ui/OpenSessionTab.java \
        app/src/main/java/app/drydock/ui/TerminalBridge.java \
        app/src/test/java/app/drydock/terminal/ghostty/GhosttyKeyTranslatorTest.java
git commit -m "Add terminal.api TerminalSpec + relocate Shortcut out of GhosttyKeyTranslator"
```

---

### Task 2: `TerminalHostView` interface

Extract the neutral contract for "a native view embedded in the window." The four listener interfaces move to the api (they are the host↔surface event contract). `contentViewHandle()` stays off the neutral interface — it returns a raw `MemorySegment` and is used only within the impl packages.

**Files:**
- Create: `app/src/main/java/app/drydock/terminal/api/TerminalHostView.java`
- Modify: `app/src/main/java/app/drydock/terminal/host/DrydockTerminalHost.java` (implement the interface; delete the now-moved nested listener interfaces)
- Modify: `app/src/main/java/app/drydock/terminal/host/DrydockTerminalHostBinding.java` (its callback-setter parameters retype to the api listener interfaces)

**Interfaces:**
- Produces: `interface TerminalHostView extends AutoCloseable` with `setFrame(double,double,double,double)`, `setVisible(boolean)`, `setFocused(boolean)`, `setKeyEventListener(KeyEventListener)`, `setScrollEventListener(ScrollEventListener)`, `setMousePosEventListener(MousePosEventListener)`, `setMouseButtonEventListener(MouseButtonEventListener)`, `void close()`, and the four nested `@FunctionalInterface` listener types (verbatim signatures from today's `DrydockTerminalHost`).
- Consumes: nothing.

- [ ] **Step 1: Create the interface**

```java
// app/src/main/java/app/drydock/terminal/api/TerminalHostView.java
package app.drydock.terminal.api;

/**
 * A native view embedded as an overlay in the host window, into which a
 * {@link TerminalSurface} renders. The only implementation is the macOS
 * AppKit host shim, but the contract is expressed neutrally: positions are in
 * device-independent window coordinates and input arrives as raw platform key
 * events through the listener interfaces below.
 *
 * <p>Every method must be called on the JavaFX Application Thread.</p>
 */
public interface TerminalHostView extends AutoCloseable {

    /** Sets the view's frame in the parent window's content-view coordinate space. */
    void setFrame(double x, double y, double width, double height);

    void setVisible(boolean visible);

    void setFocused(boolean focused);

    /** Registers the raw key-event listener (at most once per view). */
    void setKeyEventListener(KeyEventListener listener);

    /** Registers the raw scroll-event listener (at most once per view). */
    void setScrollEventListener(ScrollEventListener listener);

    /** Registers the mouse-position listener (at most once per view). */
    void setMousePosEventListener(MousePosEventListener listener);

    /** Registers the mouse-button listener (at most once per view). */
    void setMouseButtonEventListener(MouseButtonEventListener listener);

    @Override
    void close();

    /**
     * A raw, uninterpreted platform key event. {@code keyCode}/{@code
     * modifierFlags} are the native (AppKit) values; {@code characters} and
     * {@code unshiftedCharacters} are NSEvent's {@code characters} /
     * {@code charactersIgnoringModifiers}.
     */
    @FunctionalInterface
    interface KeyEventListener {
        void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                        String unshiftedCharacters);
    }

    /** A raw scrollWheel event; {@code scrollMods} is a pre-packed scroll-mods value. */
    @FunctionalInterface
    interface ScrollEventListener {
        void onScrollEvent(double deltaX, double deltaY, int scrollMods);
    }

    /** A mouse-position event in view-local points (top-left origin); {@code modifierFlags} raw. */
    @FunctionalInterface
    interface MousePosEventListener {
        void onMousePosEvent(double x, double y, int modifierFlags);
    }

    /** A mouse-button event; {@code state}/{@code button} carry ghostty enum values, {@code modifierFlags} raw. */
    @FunctionalInterface
    interface MouseButtonEventListener {
        void onMouseButtonEvent(int state, int button, int modifierFlags);
    }
}
```

- [ ] **Step 2: Make `DrydockTerminalHost` implement it**

In `DrydockTerminalHost.java`:
- Add `import app.drydock.terminal.api.TerminalHostView;`.
- Change the class declaration to `public final class DrydockTerminalHost implements TerminalHostView, AutoCloseable`.
- Add `@Override` to `setFrame`, `setVisible`, `setFocused`, the four `set*EventListener` methods, and `close`.
- **Delete** the four nested `public interface KeyEventListener/ScrollEventListener/MousePosEventListener/MouseButtonEventListener` blocks (now in the api). Change the `set*EventListener` parameter types from `KeyEventListener` etc. to `TerminalHostView.KeyEventListener` etc. (import the api interface members; reference them as nested types on `TerminalHostView`).
- Keep `createForCurrentWindow()` and `contentViewHandle()` exactly as-is (both stay concrete-only).

- [ ] **Step 3: Retype every `DrydockTerminalHost.<X>Listener` reference in the binding**

`DrydockTerminalHostBinding.java` references the nested listener names at **~12 sites**, not just the setters — all must change from `DrydockTerminalHost.<X>Listener` to `TerminalHostView.<X>Listener` (add `import app.drydock.terminal.api.TerminalHostView;`). The sites (line numbers approximate):
- Setter parameters: `setKeyEventCallback` (~293), `setScrollEventCallback` (~307), `setMousePosEventCallback` (~321), `setMouseButtonEventCallback` (~335).
- `.class` literals used for descriptor lookup: `DrydockTerminalHost.KeyEventListener.class` (~83), `ScrollEventListener.class` (~99), `MousePosEventListener.class` (~111), `MouseButtonEventListener.class` (~123).
- Trampoline helper parameters: (~353) `MouseButtonEventListener`, (~368) `MousePosEventListener`, (~383) `ScrollEventListener`, (~398) `KeyEventListener`.

Grep to confirm none remain: `grep -n "DrydockTerminalHost\.\(Key\|Scroll\|MousePos\|MouseButton\)EventListener" app/src/main/java/app/drydock/terminal/host/DrydockTerminalHostBinding.java` — expected: no output after the edit. The upcall-binding bodies are otherwise unchanged (the SAM shapes are identical), and structural lambda binding means `DrydockTerminalHost.setKeyEventListener(...)` still accepts the spike/`TerminalBridge` lambdas — `compileSpikeJava` in Step 4 proves it.

- [ ] **Step 4: Compile & test**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS — no behavior change; host still constructs and registers listeners.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/terminal/api/TerminalHostView.java \
        app/src/main/java/app/drydock/terminal/host/DrydockTerminalHost.java \
        app/src/main/java/app/drydock/terminal/host/DrydockTerminalHostBinding.java
git commit -m "Extract TerminalHostView interface; DrydockTerminalHost implements it"
```

---

### Task 3: `TerminalSurface` interface (absorbs key/mouse translation)

The neutral running-command contract. This is where the AppKit-keycode coupling moves out of the UI: the interface gains `dispatchKeyEvent` (returns an intercepted `Shortcut` or performs the key), `submitLine` (typed text + Return), and mouse methods that take **raw** modifier flags and translate internally.

**Files:**
- Create: `app/src/main/java/app/drydock/terminal/api/TerminalSurface.java`
- Modify: `app/src/main/java/app/drydock/terminal/ghostty/GhosttySurface.java` (implement interface; add the three absorbed methods; make mouse methods translate raw flags)

**Interfaces:**
- Consumes: `Shortcut`, `TerminalRuntime` (Task 5 — forward reference; `applyConfig` takes `TerminalRuntime`, satisfied because Task 5 lands the interface and `GhosttyApp implements TerminalRuntime`). To keep Task 3 self-contained and compile-green, `applyConfig` in this task keeps its concrete `GhosttyApp` parameter and is retyped to `TerminalRuntime` in Task 5.
- Produces: `interface TerminalSurface extends AutoCloseable` — methods listed in Step 1.

- [ ] **Step 1: Create the interface**

```java
// app/src/main/java/app/drydock/terminal/api/TerminalSurface.java
package app.drydock.terminal.api;

import java.util.Optional;

/**
 * One running command inside a {@link TerminalHostView}. Input (keyboard,
 * mouse), output (screen text), and lifecycle. All keycode translation lives
 * behind this interface: callers hand it raw platform key/mouse events and it
 * either performs them or, for an intercepted app shortcut, reports a neutral
 * {@link Shortcut}. Every method must be called on the JavaFX Application Thread.
 */
public interface TerminalSurface extends AutoCloseable {

    /**
     * Classifies and performs a raw platform key event. Returns the neutral
     * {@link Shortcut} to run (on key-down only) when the event is an
     * intercepted app shortcut; otherwise performs the key (forwarding or
     * typing into the running program) and returns {@link Optional#empty()}.
     */
    Optional<Shortcut> dispatchKeyEvent(int keyCode, int modifierFlags, boolean keyDown,
                                        String characters, String unshiftedCharacters);

    /** Types {@code line} as real keystrokes and submits it with Return (single line; no embedded newline). */
    void submitLine(String line);

    void setSize(int widthPx, int heightPx);

    void setFocus(boolean focused);

    void draw();

    void refresh();

    /** Forwards a mouse-position event; {@code modifierFlags} are raw platform flags. */
    void sendMousePos(double x, double y, int modifierFlags);

    /** Forwards a mouse-button event; {@code modifierFlags} are raw platform flags. */
    void sendMouseButton(int state, int button, int modifierFlags);

    /** Forwards a scroll event; {@code scrollMods} is the pre-packed value from the host. */
    void sendMouseScroll(double deltaX, double deltaY, int scrollMods);

    String readScreenText();

    boolean processExited();

    /** Gracefully closes the surface, killing the child after {@code gracePeriodMillis}. */
    void closeGracefully(long gracePeriodMillis, long pollIntervalMillis, Runnable onDone);

    @Override
    void close();
}
```

- [ ] **Step 2: Make `GhosttySurface` implement it and absorb translation**

In `GhosttySurface.java`:
- Add imports: `app.drydock.terminal.api.TerminalSurface`, `app.drydock.terminal.api.Shortcut`, `java.util.Optional`.
- Change class declaration to `public final class GhosttySurface implements TerminalSurface, AutoCloseable`.
- Add `@Override` to `setSize`, `setFocus`, `draw`, `refresh`, `sendMouseScroll`, `readScreenText`, `processExited`, `closeGracefully`, `close`.
- Keep the existing `sendKey`, `sendCharKey`, `sendTypedText`, `sendText`, `applyConfig(GhosttyApp)` methods (not on the interface; used internally / retyped later).
- **Change the two mouse methods to take raw flags** (move the translation in from `TerminalBridge`). Rename the current native-facing bodies to private helpers and add the `@Override` public methods:

```java
    @Override
    public void sendMousePos(double x, double y, int modifierFlags) {
        sendMousePosGhostty(x, y, GhosttyKeyTranslator.translateModifiers(modifierFlags));
    }

    @Override
    public void sendMouseButton(int state, int button, int modifierFlags) {
        sendMouseButtonGhostty(state, button, GhosttyKeyTranslator.translateModifiers(modifierFlags));
    }
```

Rename the existing `public void sendMousePos(double x, double y, int mods) { ... native ... }` body to `private void sendMousePosGhostty(double x, double y, int mods)` and likewise `sendMouseButton` → `private void sendMouseButtonGhostty(int state, int button, int mods)`. (The callers that passed already-translated mods — only `TerminalBridge` — are updated in Task 6 to pass raw flags.)

- **Add `dispatchKeyEvent`** (moves the `switch` from `TerminalBridge.onKeyEvent`):

```java
    @Override
    public Optional<Shortcut> dispatchKeyEvent(int keyCode, int modifierFlags, boolean keyDown,
                                               String characters, String unshiftedCharacters) {
        // Classification policy (app-shortcut interception, special-key vs
        // typed-character split, unshifted-codepoint rule) lives in
        // GhosttyKeyTranslator; this method performs the effects.
        switch (GhosttyKeyTranslator.translate(keyCode, modifierFlags, keyDown, characters, unshiftedCharacters)) {
            case GhosttyKeyTranslator.AppShortcut(Shortcut shortcut, boolean down) -> {
                // App shortcuts run on key-down only; both edges are swallowed by the caller.
                return down ? Optional.of(shortcut) : Optional.empty();
            }
            case GhosttyKeyTranslator.ForwardKey(int code, int mods, boolean down, int unshiftedCodepoint) -> {
                sendKey(code, mods, down, unshiftedCodepoint);
                return Optional.empty();
            }
            case GhosttyKeyTranslator.TypeCharacters(String typed, int mods) -> {
                typed.codePoints().forEach(cp -> sendCharKey(cp, mods));
                return Optional.empty();
            }
            case GhosttyKeyTranslator.Ignore ignored -> {
                return Optional.empty();
            }
        }
    }
```

- **Add `submitLine`** (moves the Return-key logic from `TerminalBridge.sendPrompt`):

```java
    @Override
    public void submitLine(String line) {
        sendTypedText(line);
        // Return keypress (raw macOS keycode; see GhosttyKeyTranslator).
        sendKey(GhosttyKeyTranslator.KEY_RETURN, 0, true, 0);
        sendKey(GhosttyKeyTranslator.KEY_RETURN, 0, false, 0);
    }
```

- [ ] **Step 3: Compile & test**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS — `GhosttySurface` implements the interface; `TerminalBridge` still calls the old concrete methods (its rewire is Task 6), and the renamed private mouse helpers keep the native path intact.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/drydock/terminal/api/TerminalSurface.java \
        app/src/main/java/app/drydock/terminal/ghostty/GhosttySurface.java
git commit -m "Extract TerminalSurface interface; absorb key/mouse translation into the Ghostty impl"
```

---

### Task 4: `TerminalRuntime` interface + `openSurface`

The per-view runtime. Gains `openSurface`, so surface creation goes through the runtime instead of a static `GhosttySurface.create(app, host, ...)`.

**Files:**
- Create: `app/src/main/java/app/drydock/terminal/api/TerminalRuntime.java`
- Modify: `app/src/main/java/app/drydock/terminal/ghostty/GhosttyApp.java` (implement interface; add `openSurface`)

**Interfaces:**
- Consumes: `TerminalHostView`, `TerminalSurface`, `TerminalSpec`.
- Produces: `interface TerminalRuntime extends AutoCloseable` with `tick()`, `setFocus(boolean)`, `updateConfig(java.nio.file.Path)`, `TerminalSurface openSurface(TerminalHostView host, double scaleFactor, TerminalSpec spec)`, `void close()`.

- [ ] **Step 1: Create the interface**

```java
// app/src/main/java/app/drydock/terminal/api/TerminalRuntime.java
package app.drydock.terminal.api;

import java.nio.file.Path;

/**
 * A per-view terminal runtime: the event/render pump that owns one or more
 * {@link TerminalSurface}s embedded in a {@link TerminalHostView}. Every
 * method must be called on the JavaFX Application Thread.
 */
public interface TerminalRuntime extends AutoCloseable {

    /** Pumps one iteration of the runtime's event loop. */
    void tick();

    void setFocus(boolean focused);

    /** Re-loads the runtime's config from {@code configFile} (theme switch). */
    void updateConfig(Path configFile);

    /** Opens a new surface in {@code host}, running {@code spec}, at the given output scale. */
    TerminalSurface openSurface(TerminalHostView host, double scaleFactor, TerminalSpec spec);

    @Override
    void close();
}
```

- [ ] **Step 2: Make `GhosttyApp` implement it**

In `GhosttyApp.java`:
- Add imports: `app.drydock.terminal.api.TerminalRuntime`, `app.drydock.terminal.api.TerminalSurface`, `app.drydock.terminal.api.TerminalHostView`, `app.drydock.terminal.api.TerminalSpec`.
- Change declaration to `public final class GhosttyApp implements TerminalRuntime, AutoCloseable`.
- Add `@Override` to `tick`, `setFocus`, `updateConfig`, `close`.
- Add `openSurface`, delegating to the existing static `GhosttySurface.create`, casting the neutral host to the concrete one (the two impl packages are allowed to know each other; documented seam for Phase B):

```java
    @Override
    public TerminalSurface openSurface(TerminalHostView host, double scaleFactor, TerminalSpec spec) {
        // Within the native-boundary packages the host is always the AppKit
        // DrydockTerminalHost, whose contentViewHandle() the surface needs.
        DrydockTerminalHost ghosttyHost = (DrydockTerminalHost) host;
        return GhosttySurface.create(this, ghosttyHost, scaleFactor, spec.command(), spec.workingDirectory());
    }
```

Add `import app.drydock.terminal.host.DrydockTerminalHost;`.

- [ ] **Step 3: Retype `GhosttySurface.applyConfig` to the interface**

In `GhosttySurface.java`, change `public void applyConfig(GhosttyApp app)` to `public void applyConfig(TerminalRuntime runtime)` and, inside, cast to the concrete app where the native handle is needed: `GhosttyApp ghosttyApp = (GhosttyApp) runtime;` then use `ghosttyApp` for the existing config-handle access. Add `import app.drydock.terminal.api.TerminalRuntime;`. (This keeps `TerminalBridge.applyTerminalTheme` compiling once it holds a `TerminalRuntime`.)

- [ ] **Step 4: Compile & test**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/terminal/api/TerminalRuntime.java \
        app/src/main/java/app/drydock/terminal/ghostty/GhosttyApp.java \
        app/src/main/java/app/drydock/terminal/ghostty/GhosttySurface.java
git commit -m "Extract TerminalRuntime interface with openSurface; GhosttyApp implements it"
```

---

### Task 5: `TerminalFactory` — the single impl-aware construction seam

Consumers currently call `GhosttyApp.ensureProcessInitialized`, `GhosttyApp.create`, and `DrydockTerminalHost.createForCurrentWindow` directly. Funnel all three through one factory so `ui`/`app` never name a concrete terminal class for construction.

> **Refinement of the spec:** the spec placed `TerminalFactory` in `terminal.api`. To avoid `terminal.api` depending on the impl packages (a backwards dependency a reviewer would flag), it lives in the `terminal` **root** package (alongside `NativeLibraryLocator`), which is allowed to know the impl. `terminal.api` stays pure interfaces.

**Files:**
- Create: `app/src/main/java/app/drydock/terminal/TerminalFactory.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:1062-1081` (route runtime/host creation through the factory)

**Interfaces:**
- Consumes: `GhosttyApp`, `DrydockTerminalHost`, `GhosttyNativeLibrary`, `TerminalRuntime`, `TerminalHostView`.
- Produces: `final class TerminalFactory` with `static void ensureProcessInitialized()`, `static TerminalRuntime createRuntime(Runnable onWakeup, java.util.Optional<java.nio.file.Path> configFile)`, `static TerminalHostView createHostForCurrentWindow()`.

- [ ] **Step 1: Create the factory**

```java
// app/src/main/java/app/drydock/terminal/TerminalFactory.java
package app.drydock.terminal;

import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.ghostty.GhosttyApp;
import app.drydock.terminal.ghostty.GhosttyNativeLibrary;
import app.drydock.terminal.host.DrydockTerminalHost;

import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The single construction seam between the app and the terminal implementation.
 * Everything outside the {@code terminal.ghostty} / {@code terminal.host}
 * packages obtains runtimes and host views here and otherwise depends only on
 * {@code app.drydock.terminal.api}. Phase B replaces the direct impl references
 * below with a provider lookup when the impl moves to its own module.
 */
public final class TerminalFactory {

    private TerminalFactory() {
    }

    /** Calls {@code ghostty_init} once per process (idempotent). Call before {@link #createRuntime}. */
    public static void ensureProcessInitialized() {
        GhosttyApp.ensureProcessInitialized(GhosttyNativeLibrary.lookup());
    }

    /** Creates a runtime whose wakeup callback fires {@code onWakeup} on the FX thread (coalesced). */
    public static TerminalRuntime createRuntime(Runnable onWakeup, Optional<Path> configFile) {
        SymbolLookup lookup = GhosttyNativeLibrary.lookup();
        return GhosttyApp.create(lookup, onWakeup, configFile);
    }

    /** Creates a host view attached to the current (most recently shown) window. */
    public static TerminalHostView createHostForCurrentWindow() {
        return DrydockTerminalHost.createForCurrentWindow();
    }
}
```

- [ ] **Step 2: Route `MainWorkspace.createOpenSessionTab` through the factory**

In `MainWorkspace.java`, replace the direct calls (lines ~1062–1081). New body of that section:

```java
        TerminalFactory.ensureProcessInitialized();

        OpenSessionTab[] holder = new OpenSessionTab[1];
        TerminalRuntime app = TerminalFactory.createRuntime(() -> {
            if (holder[0] != null) {
                holder[0].tickAndDraw();
            }
        }, Optional.of(TerminalThemes.configFileFor(themeProvider.get())));
        TerminalHostView host;
        try {
            host = TerminalFactory.createHostForCurrentWindow();
        } catch (RuntimeException e) {
            app.close();
            throw e;
        }
        OpenSessionTab openTab = new OpenSessionTab(sessionId, displayName, repository, stage, app, host);
```

Update imports in `MainWorkspace.java`: remove `import app.drydock.terminal.ghostty.GhosttyApp;`, `import app.drydock.terminal.ghostty.GhosttyNativeLibrary;`, `import app.drydock.terminal.host.DrydockTerminalHost;`; add `import app.drydock.terminal.TerminalFactory;`, `import app.drydock.terminal.api.TerminalRuntime;`, `import app.drydock.terminal.api.TerminalHostView;`. (The `local var` `app`/`host` types change from concrete to interface; the `OpenSessionTab` ctor param types change in Task 6.)

- [ ] **Step 3: Compile & test**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS. (After this step `MainWorkspace` still passes interface-typed `app`/`host` to an `OpenSessionTab` ctor whose params are still concrete — that compiles because `GhosttyApp`/`DrydockTerminalHost` ARE the runtime/host. Task 6 flips the ctor param types.)

Note: if the ctor param types (still concrete `GhosttyApp`/`DrydockTerminalHost` at this point) reject the interface-typed arguments, do Step 2 and Task 6's `OpenSessionTab` ctor retype together in one commit. They are separated for review clarity but may be merged if the compiler requires it.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/drydock/terminal/TerminalFactory.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java
git commit -m "Add TerminalFactory; route MainWorkspace terminal construction through it"
```

---

### Task 6: Flip all `ui`/`app` consumers to `terminal.api` types

Retype every field, parameter, return type, and record component in `ui`/`app` from the concrete terminal classes to the api interfaces, and rewire `TerminalBridge`'s input path to the absorbed surface methods. Acceptance: **no source outside `terminal.ghostty` / `terminal.host` / `terminal.TerminalFactory` imports a concrete terminal class.**

**Files:**
- Modify: `app/src/main/java/app/drydock/app/SessionManager.java`
- Modify: `app/src/main/java/app/drydock/app/SessionOpenResult.java`
- Modify: `app/src/main/java/app/drydock/ui/TerminalBridge.java`
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (residual `attachSurface`/`app()`/`host()` types)

**Interfaces:**
- Consumes: `TerminalRuntime`, `TerminalSurface`, `TerminalHostView`, `TerminalSpec`, `Shortcut`.
- Produces: consumer types now expressed purely in `terminal.api`.

- [ ] **Step 1: `SessionOpenResult`** — change every `GhosttySurface` to `TerminalSurface`. In `SessionOpenResult.java`: replace `import app.drydock.terminal.ghostty.GhosttySurface;` with `import app.drydock.terminal.api.TerminalSurface;`, and change `Opened(ManagedClaudeSession session, GhosttySurface surface)` and `AlreadyOpen(..., GhosttySurface activeSurface)` component types to `TerminalSurface`.

- [ ] **Step 2: `SessionManager`** — retype **every** `GhosttyApp`/`GhosttySurface`/`DrydockTerminalHost` occurrence (not just method params):
  - Replace imports of `GhosttyApp`, `GhosttySurface`, `DrydockTerminalHost` with `TerminalRuntime`, `TerminalSurface`, `TerminalHostView`, `TerminalSpec`.
  - Public method params in `createSession`, `launchSession`, `resumeSession`, `startFreshConversation`, `launchNewSession`, and the private `createSurfaceOnFxThread`: `GhosttyApp app` → `TerminalRuntime app`, `DrydockTerminalHost host` → `TerminalHostView host`, `CompletableFuture<GhosttySurface>` → `CompletableFuture<TerminalSurface>`.
  - **Also** (found by review — do not miss these): the `activeSurfaces` field/map value type (`...put(id, launch.surface())` ~line 287), the `finalizeResume(ManagedClaudeSession session, GhosttySurface surface, ...)` parameter (~line 336), and any other local/field typed `GhosttySurface` all become `TerminalSurface`. The producer sites `new SessionOpenResult.Opened(running, launch.surface())` (~288), `new SessionOpenResult.Opened(running, surface)` (~347), and `new SessionOpenResult.AlreadyOpen(session, active.get(), activeSurface)` (~377) compile unchanged once the surfaces are `TerminalSurface`. Verify with `grep -n "GhosttySurface\|GhosttyApp\|DrydockTerminalHost" app/src/main/java/app/drydock/app/SessionManager.java` → no output.
  - Change `createSurfaceOnFxThread` to build via the runtime and a `TerminalSpec`:

```java
    private CompletableFuture<TerminalSurface> createSurfaceOnFxThread(TerminalRuntime app, TerminalHostView host,
                                                                       double scaleFactor, String command,
                                                                       String workingDirectory) {
        CompletableFuture<TerminalSurface> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(app.openSurface(host, scaleFactor, new TerminalSpec(command, workingDirectory)));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
```

- [ ] **Step 3: `TerminalBridge`** — retype the trio and rewire input:
  - Change fields `private final GhosttyApp app;` → `TerminalRuntime app;`, `private final DrydockTerminalHost host;` → `TerminalHostView host;`, `private GhosttySurface surface;` → `TerminalSurface surface;`. Update the ctor param types and the `app()` / `host()` accessor return types accordingly.
  - Replace imports: drop `GhosttyApp`, `GhosttySurface`, `DrydockTerminalHost`, and all `GhosttyKeyTranslator.*`; add `app.drydock.terminal.api.TerminalRuntime`, `TerminalSurface`, `TerminalHostView`, `Shortcut`.
  - Rewrite `onKeyEvent` to delegate to the surface:

```java
    private void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                             String unshiftedCharacters) {
        if (LOG_KEYS) {
            System.out.println("[diag] key event: keyCode=" + keyCode + " mods=0x" + Integer.toHexString(modifierFlags)
                    + " down=" + keyDown + " chars=" + toCodepoints(characters)
                    + " unshifted=" + toCodepoints(unshiftedCharacters));
        }
        if (disposed || surface == null) {
            return;
        }
        surface.dispatchKeyEvent(keyCode, modifierFlags, keyDown, characters, unshiftedCharacters)
               .ifPresent(shortcutHandler);
    }
```

  - In `onMousePosEvent` / `onMouseButtonEvent`, remove `GhosttyKeyTranslator.translateModifiers(...)` — pass the raw `modifierFlags` straight through (the surface now translates): `surface.sendMousePos(x, y, modifierFlags);` and `surface.sendMouseButton(state, button, modifierFlags);`.
  - Rewrite `sendPrompt` to use `submitLine`:

```java
    void sendPrompt(String instruction) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            surface.submitLine(instruction);
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }
```

  - `applyTerminalTheme` already calls `app.updateConfig(...)` and `surface.applyConfig(app)` — both now interface calls (`app` is `TerminalRuntime`, `applyConfig(TerminalRuntime)`); no logic change.

- [ ] **Step 4: `OpenSessionTab`** — retype the ctor and `attachSurface`:
  - Replace imports `GhosttyApp`, `GhosttySurface`, `DrydockTerminalHost` with `TerminalRuntime`, `TerminalSurface`, `TerminalHostView`.
  - Ctor signature: `OpenSessionTab(ManagedSessionId sessionId, String displayName, Optional<Repository> repository, Stage stage, TerminalRuntime app, TerminalHostView host)`.
  - `void attachSurface(TerminalSurface surface)` (was `GhosttySurface`).
  - The `app()` / `host()` accessors that delegate to `bridge.app()` / `bridge.host()` now return `TerminalRuntime` / `TerminalHostView`.

- [ ] **Step 5: `MainWorkspace` residuals** — any remaining local variable or method signature referencing `GhosttySurface` (e.g. around `attachOpenedSession` / `opened.surface()`) becomes `TerminalSurface`. `placeholderTab.app()` / `.host()` results are already interface-typed.

- [ ] **Step 6: Verify the boundary is clean**

Run:
```bash
grep -rln "terminal\.ghostty\.\|terminal\.host\." app/src/main/java/app/drydock \
  | grep -vE "/terminal/(ghostty|host)/|/terminal/TerminalFactory\.java"
```
Expected: **no output** (nothing outside the impl packages and the factory imports a concrete terminal class).

- [ ] **Step 7: Compile & test**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/drydock/app/SessionManager.java \
        app/src/main/java/app/drydock/app/SessionOpenResult.java \
        app/src/main/java/app/drydock/ui/TerminalBridge.java \
        app/src/main/java/app/drydock/ui/OpenSessionTab.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java
git commit -m "Rewire ui/app consumers to terminal.api; UI no longer references concrete terminal classes"
```

---

### Task 7: Rename the Terminal sub-tab to Claude (relabel only, no new surface)

Low-risk relabel: the existing single terminal surface is the **Claude** tab. Rename the enum constant, button, tooltip, and shortcut; shift Explorer/Review accelerators. No behavior change — still one surface running `claude`.

**Files:**
- Modify: `app/src/main/java/app/drydock/terminal/api/Shortcut.java` (rename `TERMINAL_SUB_TAB` → `CLAUDE_SUB_TAB`; add `TERMINAL_SUB_TAB` back for the shell in Task 8)
- Modify: `app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java` (the ⌘-number → Shortcut mapping)
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java` (`SubTab`, buttons, `showSubTab`, `runShortcut`)

**Interfaces:**
- Consumes: `Shortcut`.
- Produces: `SubTab { CLAUDE, EXPLORER, REVIEW }` (TERMINAL added in Task 8); `Shortcut.CLAUDE_SUB_TAB`.

- [ ] **Step 1: Locate the mapping (it is NOT keycode constants)**

The ⌘-number mapping lives **inside `translate(...)`** (around lines 158–171) as a `switch` on the **typed-character codepoint**, not on any `KEY_1`-style constant (the only keycode constants are `KEY_RETURN`/`KEY_D`). It looks like this today:

```java
Shortcut shortcut = switch (cp) {
    case '1' -> Shortcut.TERMINAL_SUB_TAB;
    case '2' -> Shortcut.EXPLORER_SUB_TAB;
    case '3' -> Shortcut.REVIEW_SUB_TAB;
    case '[', '{' -> Shortcut.PREVIOUS_SESSION_TAB;
    case ']', '}' -> Shortcut.NEXT_SESSION_TAB;
    case '0' -> Shortcut.TOGGLE_SIDEBAR;
    default -> null;
};
```
Confirm with: `grep -n "case '1'\|case '2'\|case '3'\|Shortcut\." app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java`.

- [ ] **Step 2: Rename in `Shortcut`**

In `Shortcut.java`, rename `TERMINAL_SUB_TAB` → `CLAUDE_SUB_TAB` and update its Javadoc to "⌘1 -- switch to the Claude sub-tab." (The shell's `TERMINAL_SUB_TAB` at ⌘2 is added in Task 8.)

- [ ] **Step 3: Update the `switch` and the two failing tests**

In `GhosttyKeyTranslator.java`, change `case '1' -> Shortcut.TERMINAL_SUB_TAB;` to `case '1' -> Shortcut.CLAUDE_SUB_TAB;` (leave `'2'`/`'3'` for now; they shift in Task 8).

In `app/src/test/java/app/drydock/terminal/ghostty/GhosttyKeyTranslatorTest.java` two assertions break:
- `commandShortcutsAreIntercepted`'s `@CsvSource`: change the row `"1, TERMINAL_SUB_TAB"` → `"1, CLAUDE_SUB_TAB"`.
- `shortcutKeyUpIsSwallowedWithoutRetriggering` (~line 96): change `assertEquals(Shortcut.TERMINAL_SUB_TAB, shortcut.shortcut());` → `assertEquals(Shortcut.CLAUDE_SUB_TAB, ...)`.

- [ ] **Step 4: Update `OpenSessionTab`**
  - `enum SubTab { CLAUDE, EXPLORER, REVIEW }`; `activeSubTab = SubTab.CLAUDE`.
  - Rename `terminalSubTabButton` → `claudeSubTabButton`, declared as `new ToggleButton("✳  Claude")`; tooltip `"Claude (⌘1)"`. Update its `getStyleClass`/`setOnAction` references to the new field name.
  - `showSubTab`: replace `SubTab.TERMINAL` branches with `SubTab.CLAUDE`; the `bridge.setTerminalSubTabActive(...)` calls stay (the Claude bridge). The `else` that restores the surface region now triggers on `SubTab.CLAUDE`.
  - `runShortcut`: `case CLAUDE_SUB_TAB -> showSubTab(SubTab.CLAUDE);` (was `TERMINAL_SUB_TAB`).

- [ ] **Step 5: Compile & test**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/terminal/api/Shortcut.java \
        app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java \
        app/src/test/java/app/drydock/terminal/ghostty/GhosttyKeyTranslatorTest.java \
        app/src/main/java/app/drydock/ui/OpenSessionTab.java
git commit -m "Rename the session Terminal sub-tab to Claude (relabel only)"
```

---

### Task 8: Add the plain-shell Terminal sub-tab (lazy, ephemeral)

Add a second sub-tab **Terminal** that runs a login shell in the session's working directory, backed by its own lazily-created runtime + host + `TerminalBridge`. Disposed with the tab; never persisted.

**Files:**
- Modify: `app/src/main/java/app/drydock/terminal/api/Shortcut.java` (add `TERMINAL_SUB_TAB` for ⌘2; renumber)
- Modify: `app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java` (⌘2→TERMINAL, ⌘3→EXPLORER, ⌘4→REVIEW)
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java` (SubTab.TERMINAL, second bridge, lazy creation, buttons/tooltips, dispose)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (supply a shell-terminal provider + the session working directory)

**Interfaces:**
- Consumes: `TerminalFactory`, `TerminalRuntime`, `TerminalHostView`, `TerminalSurface`, `TerminalSpec`, `Shortcut`, `TerminalThemes`.
- Produces: a working ⌘2 Terminal sub-tab.

- [ ] **Step 1: Extend `Shortcut`, the `switch` (add a new `'4'` case), and the test**

In `Shortcut.java`, the sub-tab block becomes, in order: `CLAUDE_SUB_TAB` (⌘1), `TERMINAL_SUB_TAB` (⌘2), `EXPLORER_SUB_TAB` (⌘3), `REVIEW_SUB_TAB` (⌘4). (`CLAUDE_SUB_TAB` already exists from Task 7; re-add `TERMINAL_SUB_TAB` for the shell.)

In `GhosttyKeyTranslator.java`, the codepoint `switch` gains a `'4'` case and shifts `'2'`/`'3'` — there is **no `'4'` case today**, so this is an addition, not just an edit:

```java
Shortcut shortcut = switch (cp) {
    case '1' -> Shortcut.CLAUDE_SUB_TAB;
    case '2' -> Shortcut.TERMINAL_SUB_TAB;
    case '3' -> Shortcut.EXPLORER_SUB_TAB;
    case '4' -> Shortcut.REVIEW_SUB_TAB;
    case '[', '{' -> Shortcut.PREVIOUS_SESSION_TAB;
    case ']', '}' -> Shortcut.NEXT_SESSION_TAB;
    case '0' -> Shortcut.TOGGLE_SIDEBAR;
    default -> null;
};
```

In `GhosttyKeyTranslatorTest.java`, update the `commandShortcutsAreIntercepted` `@CsvSource` so its number rows read exactly:
```
"1, CLAUDE_SUB_TAB",
"2, TERMINAL_SUB_TAB",
"3, EXPLORER_SUB_TAB",
"4, REVIEW_SUB_TAB",
```
(keep the `[`/`{`/`]`/`}`/`0` rows unchanged). The line-96 assertion already reads `CLAUDE_SUB_TAB` from Task 7 and stays.

- [ ] **Step 2: Add a shell-terminal provider seam on `OpenSessionTab`**

`OpenSessionTab` needs to create a second runtime+host on demand, themed like the first, with its wakeup wired to the shell bridge. Add a provider the workspace supplies:

```java
    /** One lazily-created native trio for the shell sub-tab (runtime + host, themed by MainWorkspace). */
    record ShellTerminal(TerminalRuntime runtime, TerminalHostView host) { }

    /** Supplies a fresh shell runtime+host whose wakeup drives {@code onWakeup} (the shell bridge's tickAndDraw). */
    private java.util.function.Function<Runnable, ShellTerminal> shellTerminalProvider = onWakeup -> null;
    private String shellWorkingDirectory = System.getProperty("user.home");

    void setShellTerminalProvider(java.util.function.Function<Runnable, ShellTerminal> provider) {
        this.shellTerminalProvider = provider;
    }

    void setShellWorkingDirectory(String dir) {
        this.shellWorkingDirectory = dir;
    }
```

- [ ] **Step 3: Add the shell bridge + lazy build in `OpenSessionTab`**

Fields and a second anchor (the shell surface needs its own layout anchor, mirroring `placeholder`):

```java
    private final StackPane shellPlaceholder = new StackPane();
    private TerminalBridge shellBridge;   // null until first shown
    private boolean shellStarted;
```

In the ctor, style `shellPlaceholder` like `placeholder` and add its bounds/transform listeners guarded on `shellBridge != null`:

```java
        shellPlaceholder.getStyleClass().add("terminal-region");
        shellPlaceholder.boundsInLocalProperty().addListener((o, ov, nv) -> { if (shellBridge != null) shellBridge.updateGeometry(); });
        shellPlaceholder.localToSceneTransformProperty().addListener((o, ov, nv) -> { if (shellBridge != null) shellBridge.updateGeometry(); });
```

Lazy builder:

```java
    private void ensureShellStarted() {
        if (shellStarted) {
            return;
        }
        shellStarted = true;
        // The wakeup callback closes over shellBridge (assigned just below); a
        // wakeup arriving before that assignment is safely dropped by the guard.
        ShellTerminal shell = shellTerminalProvider.apply(() -> {
            if (shellBridge != null) {
                shellBridge.tickAndDraw();
            }
        });
        if (shell == null) {
            shellStarted = false;   // provider unavailable (e.g. headless test); leave the tab empty
            return;
        }
        shellBridge = new TerminalBridge(shell.runtime(), shell.host(), shellPlaceholder,
                stage::getOutputScaleX, this::sessionId, this::runShortcut);
        TerminalSurface surface = shell.runtime().openSurface(shell.host(), stage.getOutputScaleX(),
                TerminalSpec.loginShell(shellWorkingDirectory));
        shellBridge.adoptSurface(surface);
        shellBridge.wireInputListeners();
    }
```

(Store `stage` as a field if it is not already — the ctor receives it. Add `private final Stage stage;` and assign it.)

- [ ] **Step 4: Wire `SubTab.TERMINAL` into `showSubTab`**

`enum SubTab { CLAUDE, TERMINAL, EXPLORER, REVIEW }`. In `showSubTab`, the native-surface sub-tabs are now CLAUDE and TERMINAL; Explorer/Review still replace the region. This rewrite **must preserve two behaviors the current method has** (both flagged by the plan review): (a) the `view == null` bail-out that re-selects the previous button when Explorer/Review fails to build, and (b) the `Platform.runLater(<active bridge>::updateGeometry)` after the center swap, so the newly-shown native frame re-tracks the placeholder's fresh bounds. Only one native view is visible at a time; switching to TERMINAL builds it lazily:

```java
    void showSubTab(SubTab subTab) {
        claudeSubTabButton.setSelected(subTab == SubTab.CLAUDE);
        terminalSubTabButton.setSelected(subTab == SubTab.TERMINAL);
        explorerSubTabButton.setSelected(subTab == SubTab.EXPLORER);
        reviewSubTabButton.setSelected(subTab == SubTab.REVIEW);
        if (subTab == activeSubTab) {
            return;
        }
        if (subTab == SubTab.EXPLORER || subTab == SubTab.REVIEW) {
            Region view = subTab == SubTab.EXPLORER ? explorerViewOrBuild() : reviewViewOrBuild();
            if (view == null) {
                // Build failed: undo the button selection, stay put (mirrors the original guard).
                claudeSubTabButton.setSelected(activeSubTab == SubTab.CLAUDE);
                terminalSubTabButton.setSelected(activeSubTab == SubTab.TERMINAL);
                explorerSubTabButton.setSelected(activeSubTab == SubTab.EXPLORER);
                reviewSubTabButton.setSelected(activeSubTab == SubTab.REVIEW);
                return;
            }
            activeSubTab = subTab;
            content.setCenter(view);
            bridge.setTerminalSubTabActive(false);
            if (shellBridge != null) {
                shellBridge.setTerminalSubTabActive(false);
            }
            return;
        }
        // CLAUDE or TERMINAL: show the corresponding native surface, hide the other.
        boolean shellActive = subTab == SubTab.TERMINAL;
        if (shellActive) {
            ensureShellStarted();
        }
        activeSubTab = subTab;
        content.setCenter(shellActive ? shellPlaceholder : placeholder);
        bridge.setTerminalSubTabActive(!shellActive);
        if (shellBridge != null) {
            shellBridge.setTerminalSubTabActive(shellActive);
        }
        // The center swap invalidates the placeholder's bounds only on the next
        // layout pass; recompute the active native frame after it (as the
        // original TERMINAL branch did for the single bridge).
        TerminalBridge active = shellActive ? shellBridge : bridge;
        if (active != null) {
            Platform.runLater(active::updateGeometry);
        }
    }
```

Also propagate workspace visibility to the shell bridge: in `setVisible(boolean visible)` (the sole caller of `bridge.setWorkspaceVisible`), add `if (shellBridge != null) shellBridge.setWorkspaceVisible(visible);` after the existing `bridge.setWorkspaceVisible(visible);`.

- [ ] **Step 5: Buttons, tooltips, `runShortcut`, dispose**
  - Add `private final ToggleButton terminalSubTabButton = new ToggleButton("❯_  Terminal");` tooltip `"Terminal (⌘2)"`; add it to the sub-tab bar `HBox` between Claude and Explorer; wire `setOnAction(e -> showSubTab(SubTab.TERMINAL))`.
  - Update Explorer/Review tooltips to `(⌘3)` / `(⌘4)`.
  - `runShortcut`: add `case TERMINAL_SUB_TAB -> showSubTab(SubTab.TERMINAL);`.
  - `disposeNativeResources`: after `bridge.disposeNativeResources();` add `if (shellBridge != null) { shellBridge.disposeNativeResources(); }`.

- [ ] **Step 6: Supply the provider + working dir from `MainWorkspace`**

In `MainWorkspace.createOpenSessionTab`, after constructing `openTab`, wire the shell provider (it mirrors the Claude runtime/host creation, themed identically) and the working directory (the session's `searchRoot`):

```java
        openTab.setShellWorkingDirectory(searchRoot.toString());
        openTab.setShellTerminalProvider(onWakeup -> {
            TerminalRuntime shellRuntime = TerminalFactory.createRuntime(onWakeup,
                    Optional.of(TerminalThemes.configFileFor(themeProvider.get())));
            TerminalHostView shellHost;
            try {
                shellHost = TerminalFactory.createHostForCurrentWindow();
            } catch (RuntimeException e) {
                shellRuntime.close();
                throw e;
            }
            return new OpenSessionTab.ShellTerminal(shellRuntime, shellHost);
        });
```

- [ ] **Step 7: Compile & test**

Run: `./gradlew compileJava compileTestJava compileSpikeJava test`
Expected: PASS.

- [ ] **Step 8: Manual verification**

Run: `export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem && ./gradlew run`
Check:
- ⌘1 **Claude** shows the running `claude` session exactly as before (create + resume both work).
- ⌘2 **Terminal** opens a working login shell whose CWD is the session's worktree root; typing/scrolling/selection work; app shortcuts (⌘1–⌘4, ⌘0, ⌘⇧[ /]) still switch correctly from inside it.
- ⌘3 **Explorer** and ⌘4 **Review** still work.
- Closing the tab leaves no orphaned native view (both surfaces disposed); reopening the session starts a fresh shell (ephemeral).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/terminal/api/Shortcut.java \
        app/src/main/java/app/drydock/terminal/ghostty/GhosttyKeyTranslator.java \
        app/src/test/java/app/drydock/terminal/ghostty/GhosttyKeyTranslatorTest.java \
        app/src/main/java/app/drydock/ui/OpenSessionTab.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java
git commit -m "Add plain login-shell Terminal sub-tab (lazy, ephemeral) alongside Claude"
```

---

## Notes for the executor

- **Threading:** every terminal call is JavaFX-Application-Thread-only. The shell surface is created inside `ensureShellStarted`, which runs on the FX thread (invoked from a button handler) — do not wrap it in a background thread.
- **Second host "current window":** `createHostForCurrentWindow()` attaches to the most-recently-shown window. Both the Claude and shell hosts attach to the same session window; only one is visible at a time (`setVisible`), so overlap is not a concern.
- **Headless/tests:** `shellTerminalProvider` returns `null` when native construction is unavailable; `ensureShellStarted` then no-ops, so unit tests never require a live surface.
- **Phase B seam:** the `(DrydockTerminalHost) host` cast in `GhosttyApp.openSurface` and the impl references in `TerminalFactory` are the two spots that become a provider/module boundary in Phase B.
