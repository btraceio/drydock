# SSH Remote-Host Sidebar Indicator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a small always-visible chip on each SSH-attached repo's sidebar row that marks it as remote and names the host.

**Architecture:** Add a package-private static helper `buildRemoteChip(Repository)` to `RepositorySidebar` that builds a styled `Label` chip (glyph + host, with truncation + tooltip). Wire it into `SidebarTreeCell.buildRepoRow` by wrapping the repo-name label and the conditional chip in a small `HBox`. Add a `.repo-remote-chip` CSS rule modeled on the existing `.branch-tag-worktree` accent chip. Test the helper directly (it depends only on its `Repository` argument); verify the wired row visually by running the app.

**Tech Stack:** Java 21, JavaFX, JUnit 5 (junit-jupiter), Gradle.

## Global Constraints

- No fully-qualified class names inline in Java — use imports only (existing repo convention).
- All new imports needed by `buildRemoteChip` (`Label`, `Tooltip`, `OverrunStyle`, `Pos`, `Priority`, `HBox`, `VBox`, `Region`) are **already imported** in `RepositorySidebar.java` — no new imports required.
- CSS must use shared theme tokens (`-drydock-accent`, `-drydock-accent-soft`) so light and dark themes both work with no theme-specific rules. Tokens are defined in `theme-light.css` / `theme-dark.css`.
- Font-family in CSS must use the full stack `"JetBrains Mono", "Menlo", monospace` to match the rest of `app.css`.
- No changes to the `Repository` / `SshRemote` model, persistence, codecs, remote polling, or the existing branch-label tooltip.
- Spec: `docs/superpowers/specs/2026-07-24-ssh-remote-host-indicator-design.md`.

---

## File Structure

- `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — Modify. Add the package-private static `buildRemoteChip(Repository)` helper and a package-private static `remoteChipText(String host)`; wire the chip into `SidebarTreeCell.buildRepoRow`.
- `app/src/main/resources/app/drydock/ui/app.css` — Modify. Add the `.repo-remote-chip` rule next to the `.branch-tag` rules.
- `app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java` — Create. Unit tests for `buildRemoteChip` / `remoteChipText`.

---

## Task 1: `buildRemoteChip` helper + unit test

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (add two static helpers near the other private static helpers in the class; `buildRepoRow` starts at line 1139)
- Test: `app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java` (create)

**Interfaces:**
- Consumes: `app.drydock.domain.Repository` (`isRemote()`, `remote()`), `app.drydock.domain.SshRemote` (`host()`). Both already imported / on the classpath.
- Produces:
  - `static String remoteChipText(String host)` → returns `"⇅ " + host`.
  - `static Label buildRemoteChip(Repository repository)` → returns a `Label` whose style classes contain `"repo-remote-chip"`, whose text is `remoteChipText(repository.remote().host())`, with a leading-ellipsis overrun, a capped max width, and a `Tooltip` reading `"Remote host: " + host`. Task 2 consumes `buildRemoteChip`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java`:

```java
package app.drydock.ui;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositorySidebarChipTest {

    // Constructing a JavaFX Tooltip requires a running FX toolkit. Start it
    // once for the whole class; ignore ISE if another test already started it.
    @BeforeAll
    static void initToolkit() throws InterruptedException {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already initialised by an earlier test -- fine.
        }
    }

    // Mirrors the remote-repo construction used in ApplicationStateCodecTest:
    // canonical 7-arg record constructor, virtual placeholderRoot() for root.
    private static Repository remoteRepo(String host) {
        SshRemote remote = new SshRemote(host, "/srv/demo");
        return new Repository(RepositoryId.newId(), remote.placeholderRoot(), "demo",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT, remote);
    }

    @Test
    void chipTextPrefixesGlyph() {
        assertEquals("⇅ prod-box", RepositorySidebar.remoteChipText("prod-box"));
    }

    @Test
    void buildRemoteChipHasStyleClassTextAndTooltip() {
        Label chip = RepositorySidebar.buildRemoteChip(remoteRepo("deploy@build.internal"));

        assertTrue(chip.getStyleClass().contains("repo-remote-chip"),
                "chip must carry the .repo-remote-chip style class");
        assertEquals("⇅ deploy@build.internal", chip.getText());
        assertNotNull(chip.getTooltip(), "chip must have a tooltip");
        assertEquals("Remote host: deploy@build.internal", chip.getTooltip().getText());
    }
}
```

The `remoteRepo(...)` factory mirrors the real remote-repo construction in
`app/src/test/java/app/drydock/state/ApplicationStateCodecTest.java:130-132`
(there is no `Repository.local` / `withRemote` helper — the canonical 7-arg
record constructor with `SshRemote.placeholderRoot()` as the root is the
established pattern). Do not add production factories just for the test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.RepositorySidebarChipTest' -PheadlessTest`
Expected: FAIL to compile — `RepositorySidebar.remoteChipText` / `RepositorySidebar.buildRemoteChip` do not exist yet (`cannot find symbol`).

- [ ] **Step 3: Write minimal implementation**

In `app/src/main/java/app/drydock/ui/RepositorySidebar.java`, add these two package-private static helpers to the `RepositorySidebar` class (place them near `buildRepoRow` / other static helpers; all referenced types are already imported):

```java
    /** Display text for a remote-host chip: a sync glyph followed by the host. */
    static String remoteChipText(String host) {
        return "⇅ " + host;
    }

    /**
     * Builds the sidebar chip that marks a repo as remote and names its host.
     * Package-private + static so it can be unit-tested without rendering a
     * whole sidebar cell. Only ever called for repositories where
     * {@code isRemote()} is true, so {@code remote().host()} is non-null.
     */
    static Label buildRemoteChip(Repository repository) {
        String host = repository.remote().host();
        Label chip = new Label(remoteChipText(host));
        chip.getStyleClass().add("repo-remote-chip");
        chip.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        chip.setMaxWidth(160);
        chip.setTooltip(new Tooltip("Remote host: " + host));
        return chip;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.RepositorySidebarChipTest' -PheadlessTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java
git commit -m "Add buildRemoteChip helper for SSH remote-host sidebar indicator"
```

---

## Task 2: Wire the chip into the repo row + CSS

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` (`buildRepoRow`, around lines 1145-1176)
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (add rule after the `.branch-tag-worktree` block ending ~line 1241)

**Interfaces:**
- Consumes: `buildRemoteChip(Repository)` from Task 1.
- Produces: repo header rows that render the chip for remote repos and are unchanged for local repos.

- [ ] **Step 1: Add the CSS rule**

In `app/src/main/resources/app/drydock/ui/app.css`, immediately after the `.branch-tag-worktree { ... }` block (the one ending near line 1241, just before `.dirty-dot`), add:

```css
.repo-remote-chip {
    -fx-font-size: 10.5px;
    -fx-font-family: "JetBrains Mono", "Menlo", monospace;
    -fx-background-radius: 4px;
    -fx-padding: 0 5 0 5;
    -fx-text-fill: -drydock-accent;
    -fx-background-color: -drydock-accent-soft;
}
```

- [ ] **Step 2: Wire the chip into `buildRepoRow`**

In `app/src/main/java/app/drydock/ui/RepositorySidebar.java`, the current code (lines 1145-1146 and 1176) reads:

```java
            Label name = new Label(repository.displayName());
            name.getStyleClass().add("repo-name");
```
...(unchanged branch/counts code)...
```java
            VBox text = new VBox(1, name, branchRow);
```

Change the `name` construction site to build a `nameRow` HBox, and change the `VBox` to use `nameRow`. Replace the two `name`-creation lines with:

```java
            Label name = new Label(repository.displayName());
            name.getStyleClass().add("repo-name");
            // Keep the truncation `name` had when it sat directly in the VBox:
            // inside an HBox it would otherwise take preferred width and let a
            // long repo name blow out the row.
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox nameRow = new HBox(6, name);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            if (repository.isRemote()) {
                nameRow.getChildren().add(buildRemoteChip(repository));
            }
```

Then change the `VBox text = new VBox(1, name, branchRow);` line to:

```java
            VBox text = new VBox(1, nameRow, branchRow);
```

- [ ] **Step 3: Build to verify it compiles and existing tests still pass**

Run: `./gradlew :app:compileJava :app:test -PheadlessTest`
Expected: BUILD SUCCESSFUL; all tests pass (including `RepositorySidebarChipTest` and the existing `SidebarChildrenTest`).

- [ ] **Step 4: Visual verification by running the app**

Per project convention (verify UI changes by running the app; use the gradle-run instance, do not disturb the packaged Drydock):

Run: `./gradlew :app:run`

Confirm on screen:
- A repo registered over SSH shows an accent-colored `⇅ <host>` chip immediately to the right of its name on the top line of its sidebar row.
- Hovering the chip shows the `Remote host: <host>` tooltip.
- A long host truncates with a leading `…` inside the chip instead of widening the row.
- Local (non-remote) repo rows look exactly as before — no chip.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/main/resources/app/drydock/ui/app.css
git commit -m "Show remote-host chip on SSH repo sidebar rows"
```

---

## Self-Review

**Spec coverage:**
- Row layout change (wrap name + chip in HBox, preserve name truncation) → Task 2 Step 2. ✓
- The chip (glyph + host, `.repo-remote-chip`, leading-ellipsis, max-width, tooltip) → Task 1 Step 3. ✓
- Host truncation with full host in tooltip → Task 1 (`setTextOverrun` + `setMaxWidth` + tooltip). ✓
- CSS `.repo-remote-chip` with accent tokens + full font stack → Task 2 Step 1. ✓
- Testing via package-private static helper (no TestFX infra) → Task 1 test. ✓
- Local repos unchanged → Task 2 Step 2 (`if (repository.isRemote())`), visual check Step 4. ✓
- No model/persistence/polling changes → nothing in either task touches them. ✓

**Placeholder scan:** None. The test factory uses the concrete 7-arg record constructor verified against `ApplicationStateCodecTest`; every step has real code and exact commands.

**Type consistency:** `remoteChipText(String)` and `buildRemoteChip(Repository)` signatures and the `"repo-remote-chip"` style-class string are identical across Task 1 (definition + test) and Task 2 (call site + CSS selector). Chip glyph `⇅` (`⇅`) and tooltip prefix `"Remote host: "` match between helper and test.
