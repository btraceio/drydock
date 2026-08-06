# Harness Marks and Session Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every session row a per-agent mark (Claude / Codex / Pi) and add a chip row that filters the sidebar by session status and by harness.

**Architecture:** The status→facet mapping moves out of the UI into an FX-free `SessionStatusFacet` in `app.drydock.domain`, so the chip a user clicks and the dot they see share one source of truth. `SessionFilter` (a pure record) and `applyFacets` (a pure static function) hold all the filtering logic so it can be unit-tested without a JavaFX toolkit; `RepositorySidebar` keeps only the composition and the tree surgery. `AgentMarks` owns glyph/color, delegating every agent *name* to the existing `AgentLabels`.

**Tech Stack:** Java 21+ records/sealed types, JavaFX 21, Gradle, JUnit 5, TestFX + Monocle (headless FX, already configured in `app/build.gradle.kts`).

**Spec:** `docs/superpowers/specs/2026-08-06-harness-marks-and-session-filters-design.md` — read it before starting. It records *why* each rule exists; this plan records how to build it.

## Global Constraints

- **Never block the FX thread.** No task here spawns a process or touches the filesystem, but every UI mutation must happen on the FX thread. See `AGENTS.md`.
- **One mapping, not several.** After Task 1 there must be exactly one status→facet mapping in the codebase.
- **Shared presentation logic lives in one utility.** Agent glyphs/colors go in `AgentMarks`, agent names stay in `AgentLabels`. No per-view copies.
- **Rebuild-the-world is a last resort.** Filter changes go through `requestRebuild()` (which coalesces), never a direct `rebuildTree()`.
- **CSS:** every new `app.css` font size must be a bare `px` literal (e.g. `12.5px`, not `1em`) or `ThemeTokenContractTest` fails. Every new `-drydock-*` token must be declared in **both** `theme-dark.css` and `theme-light.css` or the same test fails.
- **Glyphs:** `✳` Claude, `◈` Codex, `π` Pi, `?` unknown. These are proposed; the visual pass (Task 11) may replace a glyph or a hex value, nothing else.
- **Build/test commands:** full suite is `./gradlew :app:test` (14–20 min — run it from the controlling session, not a subagent). A single class is `./gradlew :app:test --tests 'app.drydock.ui.AgentMarksTest'`.
- **Commit style:** conventional prefix, imperative mood, and the repo's `Co-Authored-By` trailer.

---

### Task 1: `SessionStatusFacet` — one status mapping in the domain

**Files:**
- Create: `app/src/main/java/app/drydock/domain/SessionStatusFacet.java`
- Modify: `app/src/main/java/app/drydock/ui/SessionStatusStyles.java` (`isRunning`, `isError`)
- Modify: `app/src/main/java/app/drydock/ui/SidebarChildren.java` (private `isRunning` at the bottom of the file)
- Test: `app/src/test/java/app/drydock/domain/SessionStatusFacetTest.java`

**Interfaces:**
- Consumes: `app.drydock.domain.SessionStatus` (7 constants).
- Produces: `public enum SessionStatusFacet { RUNNING, IDLE, ERROR }` with `public static SessionStatusFacet of(SessionStatus)`. Tasks 2, 6 and 7 depend on it being **public**.

This task also performs the spec's one deliberate behavior change: `UNSUPPORTED_AGENT` moves from idle to **error**.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/domain/SessionStatusFacetTest.java`:

```java
package app.drydock.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SessionStatusFacetTest {

    @Test
    void runningAndStartingAreRunning() {
        assertEquals(SessionStatusFacet.RUNNING, SessionStatusFacet.of(SessionStatus.RUNNING));
        assertEquals(SessionStatusFacet.RUNNING, SessionStatusFacet.of(SessionStatus.STARTING));
    }

    @Test
    void inactiveAndExitedAreIdle() {
        assertEquals(SessionStatusFacet.IDLE, SessionStatusFacet.of(SessionStatus.INACTIVE));
        assertEquals(SessionStatusFacet.IDLE, SessionStatusFacet.of(SessionStatus.EXITED));
    }

    @Test
    void failedAndMissingDirectoryAreError() {
        assertEquals(SessionStatusFacet.ERROR, SessionStatusFacet.of(SessionStatus.FAILED));
        assertEquals(SessionStatusFacet.ERROR,
                SessionStatusFacet.of(SessionStatus.MISSING_WORKING_DIRECTORY));
    }

    /**
     * Pinned deliberately: a session whose agent this build cannot run is
     * broken, and the sidebar's `error` chip has to find it. Changing this
     * back silently re-breaks that chip.
     */
    @Test
    void unsupportedAgentIsError() {
        assertEquals(SessionStatusFacet.ERROR, SessionStatusFacet.of(SessionStatus.UNSUPPORTED_AGENT));
    }

    @Test
    void everyStatusMapsToAFacet() {
        for (SessionStatus status : SessionStatus.values()) {
            assertNotNull(SessionStatusFacet.of(status), "no facet for " + status);
        }
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew :app:test --tests 'app.drydock.domain.SessionStatusFacetTest'`
Expected: compilation failure — `cannot find symbol: class SessionStatusFacet`.

- [ ] **Step 3: Create the enum**

Create `app/src/main/java/app/drydock/domain/SessionStatusFacet.java`:

```java
package app.drydock.domain;

/**
 * The three states a session is in as far as a human scanning the sidebar is
 * concerned. This is the single mapping from the richer {@link SessionStatus}
 * lifecycle enum: the status dot, the live/idle banding, and the sidebar's
 * status filter chips all resolve through here, so what a user filters to and
 * what they see can never disagree.
 *
 * <p>{@link SessionStatus#UNSUPPORTED_AGENT} is an {@link #ERROR}: a session
 * whose agent this build cannot run makes no progress and is not merely idle.
 */
public enum SessionStatusFacet {
    RUNNING,
    IDLE,
    ERROR;

    public static SessionStatusFacet of(SessionStatus status) {
        return switch (status) {
            case RUNNING, STARTING -> RUNNING;
            case FAILED, MISSING_WORKING_DIRECTORY, UNSUPPORTED_AGENT -> ERROR;
            case INACTIVE, EXITED -> IDLE;
        };
    }
}
```

Note the exhaustive `switch` over the enum with no `default`: adding an eighth `SessionStatus` becomes a compile error here, which is the point.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:test --tests 'app.drydock.domain.SessionStatusFacetTest'`
Expected: PASS.

- [ ] **Step 5: Delegate the two UI copies to it**

In `app/src/main/java/app/drydock/ui/SessionStatusStyles.java`, replace the bodies of `isRunning` and `isError` (leave the signatures and visibility alone) and add the import `app.drydock.domain.SessionStatusFacet`:

```java
    static boolean isRunning(SessionStatus status) {
        return SessionStatusFacet.of(status) == SessionStatusFacet.RUNNING;
    }

    static boolean isError(SessionStatus status) {
        return SessionStatusFacet.of(status) == SessionStatusFacet.ERROR;
    }
```

In `app/src/main/java/app/drydock/ui/SidebarChildren.java`, replace the private duplicate near the bottom of the file:

```java
    private static boolean isRunning(SessionStatus status) {
        return SessionStatusFacet.of(status) == SessionStatusFacet.RUNNING;
    }
```

Leave `SessionStatusStyles.updateDot`'s inline running test alone — it is behaviorally identical and folding it in is optional tidy-up, not part of this change.

- [ ] **Step 6: Run the affected suites**

Run: `./gradlew :app:test --tests 'app.drydock.domain.*' --tests 'app.drydock.ui.SidebarChildrenTest'`
Expected: PASS. `SidebarChildrenTest` must be green unchanged — banding keys on `isRunning`, which this task does not alter behaviorally.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/domain/SessionStatusFacet.java \
        app/src/main/java/app/drydock/ui/SessionStatusStyles.java \
        app/src/main/java/app/drydock/ui/SidebarChildren.java \
        app/src/test/java/app/drydock/domain/SessionStatusFacetTest.java
git commit -m "refactor: one status-to-facet mapping, and an unsupported agent is an error"
```

---

### Task 2: `SessionFilter` — the pure filter record

**Files:**
- Create: `app/src/main/java/app/drydock/ui/model/SessionFilter.java`
- Test: `app/src/test/java/app/drydock/ui/model/SessionFilterTest.java`

**Interfaces:**
- Consumes: `SessionStatusFacet` (Task 1), `AgentKind`, `ManagedAgentSession`, `SessionStatus`.
- Produces: `public record SessionFilter(Set<SessionStatusFacet> statuses, Set<AgentKind> agents)` with `public static SessionFilter none()`, `public boolean matches(ManagedAgentSession)`, `public boolean isActive()`. Tasks 6, 7, 8, 9 consume all four.

Everything must be `public`: both consumers (`RepositorySidebar`, `SessionFilterBar`) live one package up in `app.drydock.ui`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/model/SessionFilterTest.java`:

```java
package app.drydock.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionFilterTest {

    private static final RepositoryId REPO = RepositoryId.newId();

    private static ManagedAgentSession session(AgentKind kind, SessionStatus status) {
        return new ManagedAgentSession(ManagedSessionId.newId(), REPO, kind, "s",
                Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(),
                status, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), false, false);
    }

    @Test
    void anEmptyFilterIsInactiveAndMatchesEverything() {
        SessionFilter filter = SessionFilter.none();
        assertFalse(filter.isActive());
        assertTrue(filter.matches(session(AgentKind.CLAUDE, SessionStatus.RUNNING)));
        assertTrue(filter.matches(session(AgentKind.PI, SessionStatus.EXITED)));
    }

    @Test
    void facetsOrWithinTheStatusAxis() {
        SessionFilter filter = new SessionFilter(
                EnumSet.of(SessionStatusFacet.RUNNING, SessionStatusFacet.ERROR), Set.of());
        assertTrue(filter.isActive());
        assertTrue(filter.matches(session(AgentKind.CLAUDE, SessionStatus.RUNNING)));
        assertTrue(filter.matches(session(AgentKind.CLAUDE, SessionStatus.FAILED)));
        assertFalse(filter.matches(session(AgentKind.CLAUDE, SessionStatus.EXITED)));
    }

    @Test
    void axesAnd() {
        SessionFilter filter = new SessionFilter(
                EnumSet.of(SessionStatusFacet.RUNNING), Set.of(AgentKind.CODEX));
        assertTrue(filter.matches(session(AgentKind.CODEX, SessionStatus.RUNNING)));
        assertFalse(filter.matches(session(AgentKind.CLAUDE, SessionStatus.RUNNING)));
        assertFalse(filter.matches(session(AgentKind.CODEX, SessionStatus.EXITED)));
    }

    /**
     * Selecting every chip on an axis is the natural way to say "any of
     * these", so it must not be the one selection that hides the session
     * matching no chip at all (see unsupportedAgent... below).
     */
    @Test
    void selectingEveryChipOnAnAxisIsNoConstraint() {
        SessionFilter allAgents = new SessionFilter(Set.of(), Set.copyOf(AgentKind.preferenceOrder()));
        assertTrue(allAgents.matches(session(AgentKind.CLAUDE, SessionStatus.UNSUPPORTED_AGENT)));

        SessionFilter allStatuses = new SessionFilter(EnumSet.allOf(SessionStatusFacet.class), Set.of());
        assertTrue(allStatuses.matches(session(AgentKind.PI, SessionStatus.EXITED)));
    }

    /**
     * An UNSUPPORTED_AGENT session's agentKind() is a placeholder written by
     * the state decoder, so matching it against Claude asserts the one thing
     * known to be false. It stays reachable through `error`.
     */
    @Test
    void unsupportedAgentMatchesNoAgentChipButIsFoundByError() {
        ManagedAgentSession broken = session(AgentKind.CLAUDE, SessionStatus.UNSUPPORTED_AGENT);
        assertFalse(new SessionFilter(Set.of(), Set.of(AgentKind.CLAUDE)).matches(broken));
        assertTrue(new SessionFilter(EnumSet.of(SessionStatusFacet.ERROR), Set.of()).matches(broken));
    }

    @Test
    void bothSetsAreCopiedDefensively() {
        Set<AgentKind> mutable = new HashSet<>(Set.of(AgentKind.CODEX));
        SessionFilter filter = new SessionFilter(Set.of(), mutable);
        mutable.add(AgentKind.PI);
        assertEquals(Set.of(AgentKind.CODEX), filter.agents());
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.model.SessionFilterTest'`
Expected: compilation failure — `cannot find symbol: class SessionFilter`.

- [ ] **Step 3: Write the record**

Create `app/src/main/java/app/drydock/ui/model/SessionFilter.java`:

```java
package app.drydock.ui.model;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import java.util.Set;

/**
 * The sidebar's status/harness filter: which session rows survive, expressed
 * over domain types only so it can be reasoned about (and tested) without a
 * JavaFX toolkit.
 *
 * <p>An empty set is <em>no constraint</em>, not "match nothing" -- an
 * untouched filter shows every session. Facets OR within an axis and AND
 * across axes, and a fully selected axis is treated as unconstrained.
 */
public record SessionFilter(Set<SessionStatusFacet> statuses, Set<AgentKind> agents) {

    public SessionFilter {
        statuses = Set.copyOf(statuses);
        agents = Set.copyOf(agents);
    }

    public static SessionFilter none() {
        return new SessionFilter(Set.of(), Set.of());
    }

    /** Whether either axis constrains anything. */
    public boolean isActive() {
        return constrains(statuses, SessionStatusFacet.values().length)
                || constrains(agents, AgentKind.values().length);
    }

    public boolean matches(ManagedAgentSession session) {
        return matchesStatus(session) && matchesAgent(session);
    }

    private boolean matchesStatus(ManagedAgentSession session) {
        return !constrains(statuses, SessionStatusFacet.values().length)
                || statuses.contains(SessionStatusFacet.of(session.status()));
    }

    private boolean matchesAgent(ManagedAgentSession session) {
        if (!constrains(agents, AgentKind.values().length)) {
            return true;
        }
        // A session this build cannot identify has only a placeholder kind
        // (see the state decoder), so it belongs to no harness chip. `error`
        // is how it stays reachable.
        return session.status() != SessionStatus.UNSUPPORTED_AGENT
                && agents.contains(session.agentKind());
    }

    /** A set constrains unless it is empty or holds every value of its axis. */
    private static boolean constrains(Set<?> selected, int axisSize) {
        return !selected.isEmpty() && selected.size() < axisSize;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.model.SessionFilterTest'`
Expected: PASS (all six cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/model/SessionFilter.java \
        app/src/test/java/app/drydock/ui/model/SessionFilterTest.java
git commit -m "feat: SessionFilter, the sidebar's status and harness predicate"
```

---

### Task 3: `AgentMarks` — glyphs, style classes, and theme tokens

**Files:**
- Create: `app/src/main/java/app/drydock/ui/AgentMarks.java`
- Modify: `app/src/main/resources/app/drydock/ui/theme-dark.css` (add three tokens beside the worktree-lifecycle block)
- Modify: `app/src/main/resources/app/drydock/ui/theme-light.css` (the same three)
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (`.agent-mark` + four modifier classes)
- Test: `app/src/test/java/app/drydock/ui/AgentMarksTest.java`

**Interfaces:**
- Produces: `AgentMarks.glyph(AgentKind)`, `unknownGlyph()`, `styleClass(AgentKind)`, `unknownStyleClass()`, `markText(ManagedAgentSession)`, `createMark(ManagedAgentSession)`. Tasks 4, 5 and 7 consume these. No method takes an `AgentRegistry` — glyphs are per-kind constants and the unsupported case keys on `session.status()`; only *names* need the registry, and names stay in `AgentLabels`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/AgentMarksTest.java`:

```java
package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentMarksTest {

    private static ManagedAgentSession session(AgentKind kind, SessionStatus status) {
        return new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), kind, "s",
                Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(),
                status, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), false, false);
    }

    @Test
    void everyKindHasItsOwnGlyphAndStyleClass() {
        Set<String> glyphs = new HashSet<>();
        Set<String> classes = new HashSet<>();
        for (AgentKind kind : AgentKind.values()) {
            glyphs.add(AgentMarks.glyph(kind));
            classes.add(AgentMarks.styleClass(kind));
        }
        assertEquals(AgentKind.values().length, glyphs.size(), "glyphs must be distinct");
        assertEquals(AgentKind.values().length, classes.size(), "style classes must be distinct");
        assertNotEquals(AgentMarks.unknownGlyph(), AgentMarks.glyph(AgentKind.CLAUDE));
    }

    @Test
    void styleClassesFollowTheAgentMarkNamingScheme() {
        assertEquals("agent-mark-claude", AgentMarks.styleClass(AgentKind.CLAUDE));
        assertEquals("agent-mark-codex", AgentMarks.styleClass(AgentKind.CODEX));
        assertEquals("agent-mark-pi", AgentMarks.styleClass(AgentKind.PI));
        assertEquals("agent-mark-unknown", AgentMarks.unknownStyleClass());
    }

    @Test
    void markTextUsesTheSessionsKind() {
        assertEquals(AgentMarks.glyph(AgentKind.CODEX),
                AgentMarks.markText(session(AgentKind.CODEX, SessionStatus.RUNNING)));
    }

    /**
     * An unrecognized agent's kind is only a placeholder (CLAUDE, per the
     * state decoder), so it must render the unknown mark -- not the one name
     * we know to be wrong.
     */
    @Test
    void anUnsupportedAgentRendersTheUnknownMark() {
        assertEquals(AgentMarks.unknownGlyph(),
                AgentMarks.markText(session(AgentKind.CLAUDE, SessionStatus.UNSUPPORTED_AGENT)));
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.AgentMarksTest'`
Expected: compilation failure — `cannot find symbol: class AgentMarks`.

- [ ] **Step 3: Write the utility**

Create `app/src/main/java/app/drydock/ui/AgentMarks.java`:

```java
package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import javafx.scene.control.Label;

/**
 * The glyph-and-color half of agent identity: what a Claude row looks like
 * next to a Codex one. Every agent <em>name</em> comes from {@link
 * AgentLabels} instead, so there is one source per concern.
 *
 * <p>Deliberately split into pure lookups and one node factory: the lookups
 * are unit-tested, while anything building FX nodes needs a toolkit and is
 * covered by the visual pass.
 */
public final class AgentMarks {

    /** Text-presentation selector: keeps a glyph from resolving to a color emoji face. */
    private static final String TEXT_PRESENTATION = "︎";

    private AgentMarks() { }

    /** The per-agent mark, e.g. {@code ✳} for Claude. */
    public static String glyph(AgentKind kind) {
        return switch (kind) {
            case CLAUDE -> "✳" + TEXT_PRESENTATION;
            case CODEX -> "◈";
            case PI -> "π";
        };
    }

    /** The mark for a session whose persisted agent this build does not recognize. */
    public static String unknownGlyph() {
        return "?";
    }

    public static String styleClass(AgentKind kind) {
        return "agent-mark-" + kind.persistedName();
    }

    public static String unknownStyleClass() {
        return "agent-mark-unknown";
    }

    /**
     * The glyph for one session. A session with {@link
     * SessionStatus#UNSUPPORTED_AGENT} gets {@link #unknownGlyph()}: its
     * {@code agentKind()} is only a placeholder, so rendering it would put
     * the one mark known to be wrong on the session that has no agent at all.
     */
    public static String markText(ManagedAgentSession session) {
        return isUnknown(session) ? unknownGlyph() : glyph(session.agentKind());
    }

    /**
     * The sidebar row's mark. Carries no tooltip on purpose: the row already
     * installs a rich one, and a second tooltip here would replace it with a
     * poorer one exactly where the cursor lands on the row's left edge.
     */
    public static Label createMark(ManagedAgentSession session) {
        Label mark = new Label(markText(session));
        mark.getStyleClass().addAll("agent-mark",
                isUnknown(session) ? unknownStyleClass() : styleClass(session.agentKind()));
        return mark;
    }

    private static boolean isUnknown(ManagedAgentSession session) {
        return session.status() == SessionStatus.UNSUPPORTED_AGENT;
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.AgentMarksTest'`
Expected: PASS.

- [ ] **Step 5: Add the theme tokens to both sheets**

In `theme-dark.css`, after the worktree-lifecycle block (the one defining `-drydock-dirty` / `-drydock-pr` / `-drydock-merged`):

```css
    /* Agent identity (harness marks). Claude is deliberately NOT
     * -drydock-accent: accent is the app's universal emphasis color, and an
     * accent mark on every Claude row would read as "these rows are
     * special" rather than "these rows are Claude". Pi is magenta rather
     * than purple so it cannot be confused with the `merged` PR chip that
     * renders on the same rows. */
    -drydock-agent-claude: #c98168;
    -drydock-agent-codex: #5bb3ab;
    -drydock-agent-pi: #d977b0;
```

In `theme-light.css`, at the matching position:

```css
    /* Agent identity (light equivalents of the dark set). */
    -drydock-agent-claude: #9e5a3c;
    -drydock-agent-codex: #3f8f88;
    -drydock-agent-pi: #b04a86;
```

- [ ] **Step 6: Style the mark in `app.css`**

Add near the other child-row rules. Font size is a bare `px` literal — `ThemeTokenContractTest` enforces that:

```css
/* The per-agent mark in a session row's leading gutter. Identity is carried
 * by the glyph first and the color second, so the row still reads in
 * grayscale. */
.agent-mark {
    -fx-font-size: 11px;
    -fx-min-width: -fx-pref-width;
}

.agent-mark-claude { -fx-text-fill: -drydock-agent-claude; }
.agent-mark-codex { -fx-text-fill: -drydock-agent-codex; }
.agent-mark-pi { -fx-text-fill: -drydock-agent-pi; }
.agent-mark-unknown { -fx-text-fill: -drydock-text-faint; }
```

- [ ] **Step 7: Run the theme contract test**

Run: `./gradlew :app:test --tests 'app.drydock.ui.ThemeTokenContractTest' --tests 'app.drydock.ui.AgentMarksTest'`
Expected: PASS. If it fails on token parity, a token is missing from one sheet; if it fails on font size, the `11px` above was changed to a relative unit.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/drydock/ui/AgentMarks.java \
        app/src/test/java/app/drydock/ui/AgentMarksTest.java \
        app/src/main/resources/app/drydock/ui/
git commit -m "feat: per-agent marks, with their own theme tokens"
```

---

### Task 4: The mark on the sidebar session row

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — `buildSessionRow` (the `statusCol` construction and the row tooltip), plus the two bucket-path `Insets` literals in `buildStaleRow` / `buildLockedRow`
- Modify: `app/src/main/resources/app/drydock/ui/app.css` — `.child-row-status` min-width

**Interfaces:**
- Consumes: `AgentMarks.createMark(ManagedAgentSession)` (Task 3).
- Produces: no new API. Visual only; verified in Task 11.

The row's leading column is today a `StackPane`, which *stacks* its children — adding the mark to it as-is would put the mark on top of the status dot. It becomes an `HBox`.

- [ ] **Step 1: Turn the leading column into a two-glyph gutter**

In `buildSessionRow`, replace the `statusCol` construction:

```java
            // Leading gutter: status dot, then the agent mark. Two independent
            // axes (is it running / what is it running), so two marks -- and
            // an HBox, because the StackPane this used to be would have stacked
            // the mark on top of the dot.
            HBox statusCol = new HBox(3, dot, AgentMarks.createMark(session));
            statusCol.getStyleClass().add("child-row-status");
            statusCol.setAlignment(Pos.CENTER_LEFT);
```

Add the import for `AgentMarks` if the IDE does not (same package — no import needed) and keep `javafx.scene.layout.HBox`, already imported. `StackPane` may still be used elsewhere in the file; do not remove its import without checking.

- [ ] **Step 2: Widen the shared gutter, and move the bucket indents with it**

In `app.css`, the shared rule becomes:

```css
.child-row-status {
    -fx-min-width: 30;
    -fx-alignment: center;
}
```

`.child-row-status` is shared by the session, unopened-worktree, stale-bucket and locked-bucket rows, and `app.css` states the invariant in a comment above it: one fixed indent gutter so every child row's status column lines up. Widening the shared class is what keeps that true — do **not** give the session row a private wider class.

The path rows inside an expanded stale or locked bucket carry a hard-coded indent tuned against the old width. In `buildStaleRow` and `buildLockedRow`, change both occurrences of:

```java
            path.setPadding(new Insets(2, 8, 2, 34));
```

to:

```java
            path.setPadding(new Insets(2, 8, 2, 48));
```

(34 + the 14px the gutter grew.) Task 11's visual pass checks an *expanded* bucket for exactly this reason.

- [ ] **Step 3: Name the agent in the row tooltip**

Still in `buildSessionRow`, the tooltip currently names the agent only as a prefix on the activity line and omits it entirely when activity is `UNKNOWN`. Since the mark now carries no tooltip of its own, the row's must always name the agent. Replace the `rowTip.setText(...)` call with:

```java
            rowTip.setText("Status: " + session.status()
                    + "\nAgent: " + AgentLabels.displayName(agentRegistry, session)
                    + (activity == SessionActivity.UNKNOWN ? ""
                            : "\n" + AgentLabels.displayName(agentRegistry, session) + ": "
                                    + activityLabel(activity))
                    + "\nLast opened: " + session.lastOpenedAt()
                    + "\nWorking directory: " + workingDirectoryText);
```

- [ ] **Step 4: Build and run the existing suites**

Run: `./gradlew :app:test --tests 'app.drydock.ui.*'`
Expected: PASS. This task changes no tested behavior; a failure here means the row construction broke something.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/resources/app/drydock/ui/app.css
git commit -m "feat: session rows wear their harness mark"
```

---

### Task 5: The mark on the session tab's agent sub-tab

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/AgentLabels.java` — replace `subTabLabel(String)`, delete the two kind-only overloads
- Modify: `app/src/main/java/app/drydock/ui/OpenSessionTab.java` — constructor + the sub-tab construction
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` — `showPendingTab` (4 call sites) and `createOpenSessionTab`
- Test: `app/src/test/java/app/drydock/ui/AgentLabelsTest.java`

**Interfaces:**
- Consumes: `AgentMarks.glyph(AgentKind)`, `AgentMarks.unknownGlyph()` (Task 3).
- Produces: `AgentLabels.subTabLabel(String mark, String agentName)`. `subTabTooltip(String)` survives unchanged.

The sub-tab gets the **glyph only, no color class**: `.session-subtab:selected:keys` already repaints the sub-tab in accent to mean "this terminal owns the keyboard", and a per-agent text fill would put two meanings on one channel.

- [ ] **Step 1: Update the test first**

In `AgentLabelsTest`, replace the cases that assert `"✳  Codex"` / `"✳  Claude"` / `"✳  Pi"` through the kind-only overloads. Two of them cannot simply move:

```java
    @Test
    void subTabLabelPairsTheMarkWithTheAgentName() {
        assertEquals("◈  Codex", AgentLabels.subTabLabel(AgentMarks.glyph(AgentKind.CODEX), "Codex"));
        assertEquals("?  Unknown agent",
                AgentLabels.subTabLabel(AgentMarks.unknownGlyph(), "Unknown agent"));
    }

    @Test
    void subTabTooltipNamesTheAgentAndItsShortcut() {
        assertEquals("Codex (⌘1)", AgentLabels.subTabTooltip("Codex"));
    }

    /**
     * Was asserted through the deleted kind-only subTabLabel overload; it is
     * really a test of displayName's title-case fallback, so it points there
     * now.
     */
    @Test
    void displayNameFallsBackToThePersistedNameWhenNoProviderIsRegistered() {
        assertEquals("Pi", AgentLabels.displayName(registry(), AgentKind.PI));
    }
```

Keep the existing `registry()` helper in that test as-is.

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.AgentLabelsTest'`
Expected: compilation failure — `subTabLabel(String, String)` does not exist.

- [ ] **Step 3: Change `AgentLabels`**

Delete the `AGENT_GLYPH` constant and both kind-only overloads (`subTabLabel(AgentRegistry, AgentKind)` and `subTabTooltip(AgentRegistry, AgentKind)`). They have no production callers, and adopting them here would have been a regression: they route through `displayName(registry, kind)`, which for an `UNSUPPORTED_AGENT` session resolves the placeholder kind through its registered provider and returns "Claude". Rewrite the surviving `{@link}` javadoc references that pointed at them. Then:

```java
    /** Text of the agent sub-tab button, e.g. {@code ◈  Codex}. */
    static String subTabLabel(String mark, String agentName) {
        return mark + "  " + agentName;
    }
```

`displayName(AgentRegistry, AgentKind)` stays — `WorkspaceMcpSessionContext` uses it.

- [ ] **Step 4: Thread the kind into `OpenSessionTab`**

Add two constructor parameters beside the existing `agentName`, store them in final fields:

```java
    private final AgentKind agentKind;
    private final boolean unsupportedAgent;
```

and build the sub-tab from them:

```java
        String mark = unsupportedAgent ? AgentMarks.unknownGlyph() : AgentMarks.glyph(agentKind);
        claudeSubTabButton.setText(AgentLabels.subTabLabel(mark, agentName));
        claudeSubTabButton.setTooltip(new Tooltip(AgentLabels.subTabTooltip(agentName)));
```

- [ ] **Step 5: Pass them from `MainWorkspace`**

`showPendingTab` gains the same two parameters and forwards them through `createOpenSessionTab` to the constructor. All four `showPendingTab` call sites already hold a `ManagedAgentSession` (a prepared one at two sites, an existing one at the other two), so pass `session.agentKind()` and
`session.status() == SessionStatus.UNSUPPORTED_AGENT`. The kind is non-null at every site — no nullable parameter.

That unsupported case is real, not defensive: resuming such a session *does* create and select a placeholder tab (the blocked-resume check runs on a background executor), and that placeholder is the one tab that renders `?  Unknown agent`, until the `UnsupportedAgent` result removes it.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:test --tests 'app.drydock.ui.AgentLabelsTest'`
Expected: PASS. Then compile the app: `./gradlew :app:compileJava` — expected: BUILD SUCCESSFUL with no unresolved `subTabLabel` callers.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/AgentLabels.java \
        app/src/main/java/app/drydock/ui/OpenSessionTab.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/test/java/app/drydock/ui/AgentLabelsTest.java
git commit -m "feat: the agent sub-tab wears its own harness mark"
```

---

### Task 6: `applyFacets` — the pure composition step

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — add the static method (leave `rebuildTree` alone until Task 7)
- Test: `app/src/test/java/app/drydock/ui/RepositorySidebarFacetTest.java`

**Interfaces:**
- Consumes: `SessionFilter` (Task 2), `RepositorySidebar.SidebarNode` (existing sealed interface).
- Produces: `static List<SidebarNode> applyFacets(List<SidebarNode> children, SessionFilter filter, Predicate<ManagedSessionId> exempt)`. Task 7 calls it from `rebuildTree`.

Only the facets are extracted. The text query stays where it is: `matchesRepo`/`matchesNode` read the view model to match branch text, so a static, view-model-free function could not reproduce it without inventing parameters that exist only to satisfy the extraction. `SidebarNode` is package-private, so the test lives in `app.drydock.ui`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/RepositorySidebarFacetTest.java`:

```java
package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.ui.RepositorySidebar.SidebarNode;
import app.drydock.ui.model.SessionFilter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepositorySidebarFacetTest {

    private static final RepositoryId REPO_ID = RepositoryId.newId();
    private static final Repository REPO = Repository.local(REPO_ID, "repo", Path.of("/repo"));

    private static ManagedAgentSession session(AgentKind kind, SessionStatus status) {
        return new ManagedAgentSession(ManagedSessionId.newId(), REPO_ID, kind, "s",
                Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(),
                status, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), false, false);
    }

    private static SidebarNode sessionNode(ManagedAgentSession session) {
        return new SidebarNode.SessionNode(session, REPO);
    }

    private static SidebarNode unopenedNode() {
        return new SidebarNode.UnopenedWorktreeNode(
                new Worktree(Path.of("/wt/a"), Optional.of("a"), false, false, false, false,
                        Optional.empty()),
                REPO);
    }

    private static final SessionFilter RUNNING_ONLY =
            new SessionFilter(EnumSet.of(SessionStatusFacet.RUNNING), Set.of());

    @Test
    void anInactiveFilterIsTheIdentity() {
        List<SidebarNode> children = List.of(sessionNode(session(AgentKind.PI, SessionStatus.EXITED)),
                unopenedNode());
        List<SidebarNode> result =
                RepositorySidebar.applyFacets(children, SessionFilter.none(), id -> false);
        assertEquals(children, result);
    }

    @Test
    void anActiveFilterDropsEveryNonSessionRow() {
        ManagedAgentSession live = session(AgentKind.CLAUDE, SessionStatus.RUNNING);
        List<SidebarNode> result = RepositorySidebar.applyFacets(
                List.of(sessionNode(live), unopenedNode()), RUNNING_ONLY, id -> false);
        assertEquals(1, result.size());
        assertSame(live, ((SidebarNode.SessionNode) result.get(0)).session());
    }

    @Test
    void sessionsFailingTheFilterAreDropped() {
        List<SidebarNode> result = RepositorySidebar.applyFacets(
                List.of(sessionNode(session(AgentKind.CLAUDE, SessionStatus.EXITED))),
                RUNNING_ONLY, id -> false);
        assertEquals(List.of(), result);
    }

    /**
     * The frontmost session is always rendered, or clicking a chip would
     * leave the open session absent from the sidebar with the selection
     * cleared out from under it.
     */
    @Test
    void theExemptSessionSurvivesAFilterItDoesNotMatch() {
        ManagedAgentSession open = session(AgentKind.CLAUDE, SessionStatus.EXITED);
        List<SidebarNode> result = RepositorySidebar.applyFacets(
                List.of(sessionNode(open)), RUNNING_ONLY, open.id()::equals);
        assertEquals(1, result.size());
        assertSame(open, ((SidebarNode.SessionNode) result.get(0)).session());
    }
}
```

If `Repository.local(...)` is not the factory this codebase uses, build the `Repository` the way `SidebarChildrenTest` or `RepositorySidebarChipTest` does — the fixture shape is not the point of the test.

- [ ] **Step 2: Run the test and watch it fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.RepositorySidebarFacetTest'`
Expected: compilation failure — `cannot find symbol: method applyFacets`.

- [ ] **Step 3: Add the static method to `RepositorySidebar`**

Place it next to `matchesNode`, and give `SidebarNode` package-private visibility if it does not already have it (it does):

```java
    /**
     * The facet half of the sidebar's filtering: session-scoped, and pure so
     * it can be tested without an FX toolkit or a live sidebar.
     *
     * <p>An active facet filter turns the sidebar into a session list -- the
     * unopened-worktree rows and the locked/stale buckets drop out, because a
     * filter over sessions cannot say anything about a worktree that has
     * none. {@code exempt} always survives; it is how the frontmost session
     * stays on screen even when it fails the filter.
     */
    static List<SidebarNode> applyFacets(List<SidebarNode> children, SessionFilter filter,
                                         Predicate<ManagedSessionId> exempt) {
        if (!filter.isActive()) {
            return children;
        }
        List<SidebarNode> kept = new ArrayList<>();
        for (SidebarNode child : children) {
            if (child instanceof SidebarNode.SessionNode sessionNode
                    && (filter.matches(sessionNode.session())
                            || exempt.test(sessionNode.session().id()))) {
                kept.add(child);
            }
        }
        return kept;
    }
```

Add `java.util.function.Predicate` to the imports.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.RepositorySidebarFacetTest'`
Expected: PASS (all four cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/test/java/app/drydock/ui/RepositorySidebarFacetTest.java
git commit -m "feat: applyFacets, the sidebar's session-scoped filter step"
```

---

### Task 7: `SessionFilterBar` and the filtered rebuild

**Files:**
- Create: `app/src/main/java/app/drydock/ui/SessionFilterBar.java`
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — header assembly, the `filter` field, `filtering()`, `rebuildTree`
- Modify: `app/src/main/resources/app/drydock/ui/app.css` — chip rules

**Interfaces:**
- Consumes: `SessionFilter` (Task 2), `AgentMarks` (Task 3), `applyFacets` (Task 6), `AgentLabels.displayName(AgentRegistry, AgentKind)`.
- Produces: `SessionFilterBar(AgentRegistry, Runnable onChanged)`, `SessionFilter filter()`, `void clear()`, `void diagToggleFacet(String)`. Tasks 8, 9, 10 consume `filter()`, `clear()` and `diagToggleFacet`. Also produces `RepositorySidebar.filtering()`, used by Tasks 8 and 9.

- [ ] **Step 1: Write the chip bar**

Create `app/src/main/java/app/drydock/ui/SessionFilterBar.java`:

```java
package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.ui.model.SessionFilter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;

/**
 * The sidebar's status/harness filter chips, under the text filter.
 *
 * <p>A {@link FlowPane} so the row wraps as the sidebar narrows instead of
 * forcing a minimum width on the app's narrowest column, and deliberately
 * <em>no</em> {@code ToggleGroup}: a toggle group would give radio behavior
 * and silently break "facets OR within an axis".
 */
final class SessionFilterBar extends FlowPane {

    private final Map<SessionStatusFacet, ToggleButton> statusChips =
            new LinkedHashMap<>();
    private final Map<AgentKind, ToggleButton> agentChips = new LinkedHashMap<>();

    /** Suppresses per-chip notifications while {@link #clear()} resets them all. */
    private boolean suppressChange;

    private final Runnable onChanged;

    SessionFilterBar(AgentRegistry registry, Runnable onChanged) {
        super(4, 4);
        this.onChanged = onChanged;
        getStyleClass().add("session-filter-bar");

        for (SessionStatusFacet facet : SessionStatusFacet.values()) {
            statusChips.put(facet, addChip(facet.name().toLowerCase(java.util.Locale.ROOT), null));
        }
        // One chip per kind, not per registered provider: every build
        // registers all three, and gating on availability would hide the chip
        // for an agent whose CLI was uninstalled while its sessions remain.
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            String name = AgentLabels.displayName(registry, kind);
            agentChips.put(kind, addChip(AgentMarks.glyph(kind) + "  " + name,
                    AgentMarks.styleClass(kind)));
        }
    }

    private ToggleButton addChip(String text, String markClass) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().addAll("review-filter-button", "session-filter-chip");
        if (markClass != null) {
            chip.getStyleClass().add(markClass);
        }
        chip.setTooltip(new Tooltip(text));
        chip.selectedProperty().addListener((obs, was, is) -> {
            if (!suppressChange) {
                onChanged.run();
            }
        });
        getChildren().add(chip);
        return chip;
    }

    /** The live filter. Empty sets mean "no constraint"; see {@link SessionFilter}. */
    SessionFilter filter() {
        Set<SessionStatusFacet> statuses = EnumSet.noneOf(SessionStatusFacet.class);
        statusChips.forEach((facet, chip) -> {
            if (chip.isSelected()) {
                statuses.add(facet);
            }
        });
        Set<AgentKind> agents = EnumSet.noneOf(AgentKind.class);
        agentChips.forEach((kind, chip) -> {
            if (chip.isSelected()) {
                agents.add(kind);
            }
        });
        return new SessionFilter(statuses, agents);
    }

    /** Resets every chip, firing {@code onChanged} exactly once (not once per chip). */
    void clear() {
        suppressChange = true;
        try {
            statusChips.values().forEach(chip -> chip.setSelected(false));
            agentChips.values().forEach(chip -> chip.setSelected(false));
        } finally {
            suppressChange = false;
        }
        onChanged.run();
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript}): toggles one chip
     * by name, so the visual pass can drive filter combinations from a script
     * instead of by hand.
     */
    void diagToggleFacet(String name) {
        statusChips.forEach((facet, chip) -> {
            if (facet.name().equalsIgnoreCase(name)) {
                chip.setSelected(!chip.isSelected());
            }
        });
        agentChips.forEach((kind, chip) -> {
            if (kind.persistedName().equalsIgnoreCase(name)) {
                chip.setSelected(!chip.isSelected());
            }
        });
    }
}
```

- [ ] **Step 2: Style the chips**

In `app.css`, after the `.review-filter-button` rules so the ordering is unambiguous:

```css
/* Sidebar filter chips. They reuse .review-filter-button's shape so the app
 * grows no fourth chip language, but selection must be carried by the
 * background fill and text color -- .review-filter-button:focused paints the
 * same accent border as :selected, so a Tab-focused unselected chip would
 * otherwise look selected. */
.session-filter-bar {
    -fx-padding: 6 10 2 10;
}

.session-filter-chip {
    -fx-font-size: 11px;
}
```

- [ ] **Step 3: Wire it into the sidebar**

In `RepositorySidebar`, add the fields:

```java
    private SessionFilter filter = SessionFilter.none();
    private final SessionFilterBar filterBar;
```

Construct the bar after `filterField` is configured and put it in the header as its third child:

```java
        filterBar = new SessionFilterBar(agentRegistry, () -> {
            filter = filterBar.filter();
            requestRebuild();
        });

        VBox header = new VBox(addButton, filterField, filterBar);
```

`requestRebuild()`, not `rebuildTree()`: it coalesces, so a burst of chip changes costs one rebuild. There is no debounce — the 150 ms `FILTER_DEBOUNCE` exists for keystrokes, and a click is one discrete event.

Add the single predicate every filter-aware surface will use:

```java
    /**
     * Whether the sidebar is narrowed at all -- by chips or by text. Every
     * filter-aware surface (empty state, footer suffix, repo aggregates, the
     * childless-repo rule) keys on this one predicate, or they disagree with
     * each other about what the user is looking at.
     */
    private boolean filtering() {
        return filter.isActive() || !currentQuery().isEmpty();
    }

    private String currentQuery() {
        return filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);
    }
```

and have `rebuildTree()` use `currentQuery()` for its existing `query` local.

- [ ] **Step 4: Compose the filters in `rebuildTree`**

Inside the per-repository loop, apply the facets before the existing text narrowing, and gate the childless-repo drop on `filtering()`:

```java
        for (Repository repository : repositories) {
            List<SidebarNode> children = applyFacets(childNodesFor(repository), filter, this::isExempt);
            if (!query.isEmpty() && !matchesRepo(repository, query)) {
                children = children.stream().filter(child -> matchesNode(child, query)).toList();
            }
            // Only drop a childless repo while filtering: with no filter at
            // all, a freshly added repository with no worktrees and no
            // sessions must keep showing itself (and its + and ⟳ buttons).
            if (children.isEmpty() && filtering()) {
                continue;
            }
            ...
```

Add the exemption predicate (Task 8 extends its consequences; this is the whole implementation):

```java
    /** The frontmost session is always rendered -- see {@link #applyFacets}. */
    private boolean isExempt(ManagedSessionId sessionId) {
        return viewModel.activeSession().filter(sessionId::equals).isPresent();
    }
```

- [ ] **Step 5: Run the test suites**

Run: `./gradlew :app:test --tests 'app.drydock.ui.*'`
Expected: PASS, including `RepositorySidebarFacetTest` from Task 6.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/SessionFilterBar.java \
        app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/resources/app/drydock/ui/app.css
git commit -m "feat: filter the sidebar by session status and harness"
```

---

### Task 8: Keeping the filtered tree honest

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — `sessionRowChanged`, `activeSessionChanged`, `focusAdjacentLiveSession`, the `collapsed` handling in `rebuildTree`

**Interfaces:**
- Consumes: `filter`, `filtering()`, `isExempt` (Task 7).
- Produces: no new API.

Four holes that only open once a filter exists. None of them is optional: each produces a sidebar that lies about what is in it.

- [ ] **Step 1: Re-evaluate row membership on status changes**

`sessionRowChanged` mutates a `TreeItem` in place and never re-checks membership, so a session that exits while `running` is active stays in the list rendering as an idle row, and one that starts running never appears. Replace the handler body:

```java
            @Override
            public void sessionRowChanged(ManagedSessionId sessionId) {
                maybeRefreshStatuses();
                if (filter.isActive() && membershipChanged(sessionId)) {
                    requestRebuild();
                } else {
                    updateSessionRow(sessionId);
                }
            }
```

and add:

```java
    /**
     * Whether {@code sessionId} belongs in the tree but is missing, or is in
     * the tree but no longer belongs. The {@code isExempt} term is not
     * optional: an exempt row fails {@code matches} by definition while
     * sitting in the tree, and it is the frontmost session -- the one
     * emitting the most row events -- so testing {@code matches} alone would
     * force a full rebuild on every one of them that could never resolve the
     * mismatch it reacted to.
     */
    private boolean membershipChanged(ManagedSessionId sessionId) {
        ManagedAgentSession session = viewModel.session(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        boolean belongs = filter.matches(session) || isExempt(sessionId);
        return belongs != isInTree(sessionId);
    }

    private boolean isInTree(ManagedSessionId sessionId) {
        for (TreeItem<SidebarNode> repoItem : treeRoot.getChildren()) {
            for (TreeItem<SidebarNode> child : repoItem.getChildren()) {
                if (child.getValue() instanceof SidebarNode.SessionNode sessionNode
                        && sessionNode.session().id().equals(sessionId)) {
                    return true;
                }
            }
        }
        return false;
    }
```

If `viewModel.session(ManagedSessionId)` does not exist, find the session by streaming `viewModel.sessions()`.

- [ ] **Step 2: Rebuild when the exemption moves**

`activeSessionChanged` does not rebuild today, so the exemption — which is a function of the active session — would never materialize for the incoming session nor lapse for the outgoing one. Replace the handler body:

```java
            @Override
            public void activeSessionChanged(Optional<ManagedSessionId> previous,
                                             Optional<ManagedSessionId> current) {
                if (filtering() && (membershipChanged(previous.orElse(null))
                        || membershipChanged(current.orElse(null)))) {
                    requestRebuild();
                    return;
                }
                previous.ifPresent(RepositorySidebar.this::updateSessionRow);
                current.ifPresent(RepositorySidebar.this::updateSessionRow);
                syncActiveSelection();
            }
```

Make `membershipChanged(null)` return `false` (guard at the top) so the `orElse(null)` calls are safe.

- [ ] **Step 3: Make the live-session cycle respect the filter**

`focusAdjacentLiveSession` walks the unfiltered model, so ⌘-cycling can land on a session the filter hides — which the exemption then pops into the tree, papering over an inconsistency instead of fixing one. In the accumulation loop:

```java
            if (classified != null) {
                for (ManagedAgentSession candidate : classified.liveSessions()) {
                    if (filter.matches(candidate)) {
                        live.add(candidate);
                    }
                }
            }
```

Facets only — not the text query, which this method ignores today and continues to ignore. The wrap-around is unchanged; it wraps over the narrower list, and under an `idle`- or `error`-only filter that list is empty, so the key becomes the no-op it already is when nothing is live.

- [ ] **Step 4: Force-expand while filtering**

A collapsed repository silently swallowing the only match — with no empty state, because the tree is not empty — is the worst failure this feature can produce. Add the snapshot field:

```java
    /** The user's collapse set, stashed while a filter forces every repo open. */
    private Set<RepositoryId> collapsedBeforeFilter;
```

and at the top of `rebuildTree()`, before the repository loop:

```java
        // A filter is a global question ("where are my errors?"); repo
        // expansion is a local reading preference. Re-assert the expansion on
        // every change to the filter -- not only on entry, or switching from
        // `running` to `error` would leave the sole matching session inside a
        // repo the user collapsed earlier.
        if (filtering()) {
            if (collapsedBeforeFilter == null) {
                collapsedBeforeFilter = new HashSet<>(collapsed);
            }
            if (filterChangedSinceLastRebuild) {
                collapsed.clear();
            }
        } else if (collapsedBeforeFilter != null) {
            collapsed.clear();
            collapsed.addAll(collapsedBeforeFilter);
            collapsedBeforeFilter = null;
        }
        filterChangedSinceLastRebuild = false;
```

Set `filterChangedSinceLastRebuild = true` (a new `private boolean` field) in the `SessionFilterBar` callback and in the `filterDebounce` handler — the two places a filter can change. Between filter changes the disclosure triangle stays live, so it is never a dead control while the user reads results; collapses made mid-filter are discarded when the filter clears, since the pre-filter snapshot is what gets restored.

- [ ] **Step 5: Let an exempt row admit what it is**

An exempt row is a row the filter says should not be there, so it must not be
silent about it. In `buildSessionRow`, append a line to the row tooltip when
the row survives only by exemption:

```java
            if (filter.isActive() && !filter.matches(session) && isExempt(session.id())) {
                rowTip.setText(rowTip.getText()
                        + "\nShown because it is open — it does not match the current filter.");
            }
```

- [ ] **Step 6: Run the suites**

Run: `./gradlew :app:test --tests 'app.drydock.ui.*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java
git commit -m "fix: keep the filtered sidebar honest as sessions and focus move"
```

---

### Task 9: The empty state, in two forms

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — an `emptyState` node, the swap/banner logic at the end of `rebuildTree`
- Modify: `app/src/main/resources/app/drydock/ui/app.css` — empty-state block

**Interfaces:**
- Consumes: `filtering()` (Task 7), `filterBar.clear()` (Task 7), `isExempt` (Task 7).
- Produces: no new API.

The match set being empty does not always mean the tree is: the active-session exemption can leave exactly one row standing. Swapping the tree away in that case would delete the row the exemption exists to protect.

- [ ] **Step 1: Build the empty-state node**

In the constructor, after the tree is configured:

```java
        Label emptyMessage = new Label("Nothing matches your filters");
        emptyMessage.getStyleClass().add("sidebar-empty-message");
        Button clearFilters = new Button("Clear filters");
        clearFilters.getStyleClass().add("sidebar-empty-clear");
        clearFilters.setOnAction(e -> {
            filterField.clear();
            filterBar.clear();
        });
        emptyState = new VBox(8, emptyMessage, clearFilters);
        emptyState.getStyleClass().add("sidebar-empty-state");
        emptyState.setAlignment(Pos.CENTER);
```

The button clears the **text field as well as** the chips: a user with both a typo and a chip active would otherwise click it, see the tree stay empty, and conclude it is broken. The message is "Nothing matches your filters", not "No sessions match" — the text query matches repositories and branches too, so the narrower wording would be false.

Store `emptyState` and a `Label emptyBanner` (same message, `.sidebar-empty-banner`, no vgrow) as fields.

- [ ] **Step 2: Choose the form at the end of `rebuildTree`**

```java
        // Two forms, because an exempt row can leave the tree non-empty while
        // nothing actually matched. Swap only when there is nothing to show
        // at all; otherwise the exempt row would be deleted from the screen,
        // re-creating the failure the exemption exists to prevent.
        boolean nothingMatched = filtering() && matchCount == 0;
        boolean treeIsEmpty = treeRoot.getChildren().isEmpty();
        boolean noRepositoriesAtAll = repositoryManager.repositories().isEmpty();
        showEmptyState(nothingMatched && !noRepositoriesAtAll, treeIsEmpty);
```

where `matchCount` is accumulated in the repository loop as the number of surviving **non-exempt** session rows, and:

```java
    private void showEmptyState(boolean nothingMatched, boolean treeIsEmpty) {
        boolean swap = nothingMatched && treeIsEmpty;
        boolean banner = nothingMatched && !treeIsEmpty;

        if (swap && !getChildren().contains(emptyState)) {
            boolean treeHadFocus = isInsideTree(getScene() == null ? null : getScene().getFocusOwner());
            getChildren().set(getChildren().indexOf(tree), emptyState);
            VBox.setVgrow(emptyState, Priority.ALWAYS);
            if (treeHadFocus) {
                emptyState.getChildren().get(1).requestFocus();
            }
        } else if (!swap && getChildren().contains(emptyState)) {
            boolean buttonHadFocus = getScene() != null
                    && emptyState.getChildren().get(1).equals(getScene().getFocusOwner());
            getChildren().set(getChildren().indexOf(emptyState), tree);
            VBox.setVgrow(tree, Priority.ALWAYS);
            if (buttonHadFocus) {
                filterField.requestFocus();
            }
        }
        emptyBanner.setVisible(banner);
        emptyBanner.setManaged(banner);
    }
```

Insert `emptyBanner` into the sidebar's children directly above the tree at construction, initially `setVisible(false)` and `setManaged(false)`.

Focus moves **only when the focus owner is inside the node being removed**. An unconditional rule would yank the caret out of the filter field on the 150 ms debounce — swallowing the next keystroke and turning Space into *Clear filters* — and this codebase has already shipped keystroke-routing regressions at this exact field. The banner form moves no focus at all: nothing leaves the scene.

The state is suppressed entirely when there are no repositories: a user with an empty workspace typing into the filter field is not looking at a filter problem, and *Clear filters* cannot help them.

- [ ] **Step 3: Style it**

```css
.sidebar-empty-state {
    -fx-padding: 24 16 24 16;
}

.sidebar-empty-message {
    -fx-font-size: 12px;
    -fx-text-fill: -drydock-text-faint;
}

.sidebar-empty-banner {
    -fx-font-size: 11px;
    -fx-text-fill: -drydock-text-faint;
    -fx-padding: 6 12 6 12;
}
```

- [ ] **Step 4: Run the suites**

Run: `./gradlew :app:test --tests 'app.drydock.ui.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/resources/app/drydock/ui/app.css
git commit -m "feat: tell the user when a filter matched nothing"
```

---

### Task 10: Footer, prompt text, and repo aggregates

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — `updateFooter`, the `filterField` prompt, `buildRepoRow`, `repoCountsText`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` — the `diagTabStep` verb switch
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — `diagToggleFacet` passthrough

**Interfaces:**
- Consumes: `filtering()`, `filter.isActive()`, `filterBar.diagToggleFacet` (Task 7).
- Produces: `RepositorySidebar.diagToggleFacet(String)`, called from `DrydockApplication`.

- [ ] **Step 1: Footer suffix**

At the end of `updateFooter`, before `footerLabel.setText`:

```java
        if (filtering()) {
            // The footer's job is "what exists" -- shrinking the totals would
            // erase the only remaining evidence of what the filter hides.
            footerText += " · filtered";
        }
```

Same predicate as the empty state, so the footer and the message never disagree. `updateFooter` is also driven by `repoChanged`, so the suffix is recomputed from the live filter on every call.

- [ ] **Step 2: Prompt text**

```java
        filterField.setPromptText("⌕  Filter repos & sessions…");
```

The old "repos & worktrees" stops being true once chips hide worktree rows.

- [ ] **Step 3: Repo aggregates follow the filter**

In `buildRepoRow`, the session badge and the aggregate dot are computed from the unfiltered list. While `filtering()`, read the surviving count from the repo item's **already-composed children** — never a recount through `filter.matches`, which would disagree with the screen in both directions (the text half of `filtering()` also drops children, and a repo matched by name keeps children the query does not match):

```java
            List<ManagedAgentSession> sessions = sessionsFor(repository);
            // Composed children, minus any row present only by exemption: an
            // exempt row did not match, so counting it would overstate the
            // matches, and a repo present ONLY by exemption must show no
            // count at all rather than a bare "0 of M".
            List<ManagedAgentSession> shown = getTreeItem() == null ? sessions
                    : getTreeItem().getChildren().stream()
                            .map(TreeItem::getValue)
                            .filter(SidebarNode.SessionNode.class::isInstance)
                            .map(node -> ((SidebarNode.SessionNode) node).session())
                            .filter(candidate -> !filter.isActive() || filter.matches(candidate))
                            .toList();
            Label count = new Label(!filtering() || shown.size() == sessions.size()
                    ? String.valueOf(sessions.size())
                    : shown.isEmpty() ? "" : shown.size() + " of " + sessions.size());
```

and make the aggregate running dot reflect `shown` rather than every session.

In `repoCountsText`, suppress the worktree/locked/stale counts when `filter.isActive()` — those rows are gone by then, so the counts would point at nothing:

```java
        if (filter.isActive()) {
            return "";
        }
```

Note the deliberate split: `filtering()` for the session badge, `filter.isActive()` for the worktree counts, because only the chips remove those rows.

- [ ] **Step 4: The diagnostic verb**

In `RepositorySidebar`, beside the existing `diagFilter`:

```java
    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript}): toggles one
     * filter chip by name, so a scripted visual pass can capture filter
     * combinations.
     */
    public void diagToggleFacet(String name) {
        filterBar.diagToggleFacet(name);
    }
```

And in `DrydockApplication.diagTabStep`'s verb switch, beside `case "filter"`:

```java
            case "facet" -> sidebar.diagToggleFacet(arg);
```

The switch is hard-coded; adding the sidebar method alone does nothing.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :app:test`
Expected: PASS. This is the long one (14–20 min); run it from the controlling session, not a subagent.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/java/app/drydock/DrydockApplication.java
git commit -m "feat: the sidebar chrome says what the filter is hiding"
```

---

### Task 11: Visual verification

**Files:**
- No source changes expected. If the pass finds a problem, fix it and note what changed.

**Interfaces:**
- Consumes: everything above, plus the `facet` diag verb from Task 10.

Not optional, and not a substitute for the tests: the two things this feature depends on most — whether the glyphs render in the right face and whether the colors are distinguishable — cannot be settled statically. Drive it with the diagnostic run properties, `tabScript`'s `filter:` and `facet:` verbs, and the `shot:` scene snapshot, **in both themes**.

- [ ] **Step 1: Capture the marks**

Sidebar with all three harnesses present plus an `UNSUPPORTED_AGENT` session. Confirm each mark is distinguishable at the sidebar's 12.5 px, and that **none renders as a color emoji**. `✳` is emoji-presentation-capable; `AgentMarks` already appends U+FE0E to it, and if that is not enough, swap the glyph. `π` is a Greek letterform with its own metrics and baseline — check its vertical alignment against the dot beside it.

- [ ] **Step 2: Capture the same view in grayscale**

The glyph alone must identify the agent. If it does not, the glyph set is wrong, not the palette.

- [ ] **Step 3: Check the colors against their neighbors**

Each mark must be distinguishable from the other two and from `-drydock-running` (`#6cc07a` dark / `#4c9d5e` light), `-drydock-error` (`#e06c6c` / `#c6493f`) and `-drydock-merged`. The light ramp is the tighter of the two, and Claude-clay against light error red is the closest pair in the set. If a value fails, change the value — the token names and the structure stand.

- [ ] **Step 4: Capture the sub-tab separately**

`.session-subtab` opts into JetBrains Mono while the sidebar runs in the System face, so the same three glyphs resolve through two different fallback chains in one window. Include the `:selected:keys` state to confirm the glyph change did not disturb that state's accent color.

- [ ] **Step 5: Check the gutter alignment**

One repo showing a session row, an unopened-worktree row, and a stale bucket **expanded** — the shared column widened in Task 4 and the bucket's path rows carry their own hard-coded indent, so an expanded bucket is where a mismatch shows.

- [ ] **Step 6: Capture the filter states**

Each single facet; one multi-facet combination; text + facet together. Confirm the repo badge reads `N of M` and the worktree counts disappear under a chip filter.

- [ ] **Step 7: Capture both empty-state forms**

The full swap with its *Clear filters* button (including the recovery path from text-only emptiness), and the banner form: filter to `error` with no errors while sitting in an idle session, which must show the ghost row **and** the message together.

- [ ] **Step 8: Commit any fixes and record the outcome**

```bash
git add -A
git commit -m "fix: visual pass adjustments for the harness marks"
```

---

## Notes for the implementer

- **Read the spec's reasoning before changing a rule.** Several rules here look like they could be simplified and cannot: the `isExempt` term in `membershipChanged` (drop it and the sidebar rebuilds forever), the two empty-state forms (collapse them and the exempt row vanishes), and the shared-gutter widening (make it session-only and the child rows stop lining up).
- **`filtering()` vs `filter.isActive()`** is a real distinction, not redundancy: chips remove non-session rows, the text query does not. Three places use the narrower one — dropping non-session rows, suppressing the worktree counts, and the membership check in `sessionRowChanged` (correct there because a text query matches name, branch and path but never status, so a status change cannot alter text-query membership).
- **If a test needs a live `RepositorySidebar`, stop.** It cannot be constructed in a test today: seven collaborators, two of which spawn real `git`, plus a `refreshAllStatuses()` call and a 30 s `INDEFINITE` timeline with no stop path in the constructor. That is why the filtering logic is extracted into pure functions. Building that fixture is a separate change.
