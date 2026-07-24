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

- `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — Modify. Add three package-private static helpers on the **outer** `RepositorySidebar` class — `remoteChipText(String)`, `remoteChipTooltipText(String)`, and `buildRemoteChip(Repository)` — and wire the chip into `SidebarTreeCell.buildRepoRow`.
- `app/src/main/resources/app/drydock/ui/app.css` — Modify. Add the `.repo-remote-chip` rule next to the `.branch-tag` rules.
- `app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java` — Create. Unit tests for the **pure** helpers `remoteChipText` / `remoteChipTooltipText` (no JavaFX toolkit needed, so they run headlessly / in CI). The `Label` wiring and CSS styling are verified by running the app in Task 2 — this project has no working headless FX render-test path (the Monocle/`-PheadlessTest` system properties are wired only to the `run` task, and no TestFX/Monocle dependency exists), and the sandbox has no display, so a test that constructs `Tooltip`/renders would fail there.

---

## Task 1: chip helpers + unit test

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` — add the three static helpers as members of the **outer `RepositorySidebar` class**, NOT inside the nested `private final class SidebarTreeCell` (which starts at line 1092). `buildRepoRow` lives inside `SidebarTreeCell` and will call `buildRemoteChip(...)` unqualified in Task 2 — that resolves to the outer-class static (same precedent as the existing package-private static `nextLiveIndex`). Placing them inside `SidebarTreeCell` would compile but make the test's `RepositorySidebar.buildRemoteChip(...)` fail to resolve. A safe spot is just above `buildRepoRow`'s enclosing class or next to other outer-class static helpers.
- Test: `app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java` (create)

**Interfaces:**
- Consumes: `app.drydock.domain.Repository` (`isRemote()`, `remote()`), `app.drydock.domain.SshRemote` (`host()`). Already on the classpath; `Repository` is imported in the file.
- Produces:
  - `static String remoteChipText(String host)` → returns `"⇅ " + host`. **Pure — no JavaFX.**
  - `static String remoteChipTooltipText(String host)` → returns `"Remote host: " + host`. **Pure — no JavaFX.**
  - `static Label buildRemoteChip(Repository repository)` → returns a `Label` whose style classes contain `"repo-remote-chip"`, whose text is `remoteChipText(host)`, with leading-ellipsis overrun, a capped max width, and a `Tooltip` reading `remoteChipTooltipText(host)`. Task 2 consumes `buildRemoteChip`. (Not unit-tested directly — verified by running the app in Task 2, because constructing a `Tooltip` needs a live FX toolkit the CI/sandbox lacks.)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java`. It exercises only the pure string helpers, so it needs no JavaFX toolkit and runs headlessly:

```java
package app.drydock.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositorySidebarChipTest {

    @Test
    void chipTextPrefixesGlyph() {
        assertEquals("⇅ prod-box", RepositorySidebar.remoteChipText("prod-box"));
    }

    @Test
    void chipTextKeepsFullUserAtHost() {
        assertEquals("⇅ deploy@build.internal",
                RepositorySidebar.remoteChipText("deploy@build.internal"));
    }

    @Test
    void tooltipTextPrefixesRemoteHost() {
        assertEquals("Remote host: deploy@build.internal",
                RepositorySidebar.remoteChipTooltipText("deploy@build.internal"));
    }
}
```

Rationale for not asserting the `Label`/style class here: this project has no working headless FX test path (the `glass.platform=Monocle` / `-PheadlessTest` system properties are wired only to the `run` task at `app/build.gradle.kts:167`, and there is no TestFX/Monocle dependency in `app/build.gradle.kts:80-92`), and the execution sandbox has no display, so any test that starts the toolkit or constructs a `Tooltip` would fail there. The chip's real logic — glyph, host, tooltip text — lives in the pure helpers and is fully covered; the thin `Label` glue is verified by running the app in Task 2 Step 4.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.RepositorySidebarChipTest'`
Expected: FAIL to compile — `RepositorySidebar.remoteChipText` / `RepositorySidebar.remoteChipTooltipText` do not exist yet (`cannot find symbol`).

- [ ] **Step 3: Write minimal implementation**

In `app/src/main/java/app/drydock/ui/RepositorySidebar.java`, add these three package-private static helpers to the **outer `RepositorySidebar` class** (see Files note above; all referenced types — `Label`, `OverrunStyle`, `Tooltip` — are already imported):

```java
    /** Display text for a remote-host chip: a sync glyph followed by the host. */
    static String remoteChipText(String host) {
        return "⇅ " + host;
    }

    /** Tooltip text for a remote-host chip: the full (untruncated) host. */
    static String remoteChipTooltipText(String host) {
        return "Remote host: " + host;
    }

    /**
     * Builds the sidebar chip that marks a repo as remote and names its host.
     * Package-private + static so the pure text helpers it delegates to can be
     * unit-tested; the Label/Tooltip wiring itself is verified by running the
     * app. Only ever called for repositories where {@code isRemote()} is true,
     * so {@code remote().host()} is non-null.
     */
    static Label buildRemoteChip(Repository repository) {
        String host = repository.remote().host();
        Label chip = new Label(remoteChipText(host));
        chip.getStyleClass().add("repo-remote-chip");
        chip.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        chip.setMaxWidth(160);
        chip.setTooltip(new Tooltip(remoteChipTooltipText(host)));
        return chip;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.RepositorySidebarChipTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java \
        app/src/test/java/app/drydock/ui/RepositorySidebarChipTest.java
git commit -m "Add remote-host chip helpers for SSH sidebar indicator"
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

Run: `./gradlew :app:compileJava :app:test`
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
- Testing via pure package-private static helpers (no TestFX infra / toolkit; runs headlessly) → Task 1 test. `Label`/CSS wiring verified by running the app → Task 2 Step 4. ✓
- Local repos unchanged → Task 2 Step 2 (`if (repository.isRemote())`), visual check Step 4. ✓
- No model/persistence/polling changes → nothing in either task touches them. ✓

**Placeholder scan:** None. Every step has real code and exact commands. Test commands use plain `./gradlew :app:test` (the `-PheadlessTest` property is a no-op for the `test` task — it is wired only to `run` — and the pure-string test needs no toolkit anyway).

**Type consistency:** `remoteChipText(String)`, `remoteChipTooltipText(String)`, and `buildRemoteChip(Repository)` signatures and the `"repo-remote-chip"` style-class string are identical across Task 1 (definition + test) and Task 2 (call site + CSS selector). Chip glyph `⇅` and tooltip prefix `"Remote host: "` match between the helpers and the test.
