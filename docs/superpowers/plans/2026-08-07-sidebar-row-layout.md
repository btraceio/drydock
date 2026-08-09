# Sidebar Row Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the sidebar session row around the session name, which today gets ~84px of a 320px sidebar.

**Architecture:** Three changes compound: the row becomes single-line (the `8m ago` line and the `Resume` pill leave), the hover action buttons move to an overlay layer so they stop reserving 70px of layout width on every row, and the branch tag is clamped so it yields to the name instead of competing with it. A small shared `RowOverlay` helper carries the overlay mechanic so the session row and the repo row get identical behavior from one implementation.

**Tech Stack:** Java 21+, JavaFX 21 (`StackPane` overlay layer, `pickOnBounds`, CSS `-fx-min-width`/gradients), Gradle, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-07-sidebar-row-layout-design.md` — read it first. It records why each change exists; this plan records how.

## Two deviations from the spec, both evidence-based

The spec was written before the row builders were read closely. Two of its
statements do not survive contact with the code, and this plan does the
right thing instead. Neither changes what the user sees.

**1. The unopened-worktree row does not have the reserved-width problem.**
Spec section B says the overlay treatment applies to "the repo row's `⟳`/`+`
and the unopened-worktree row's actions, which have the identical problem."
It does not: the unopened row's `Start ▸` pill and its 🗑 button are added
to the row unconditionally with no hover binding — they are always visible,
so their width is width they actually use. Only two builders bind visibility
to hover and leave the node managed: `buildSessionRow` (three buttons, 70px)
and `buildRepoRow` (two buttons, ~44px). The overlay applies to those two.
Touching the unopened row would be a change with no defect behind it.

**2. `SidebarRows` extraction is dropped in favour of a small shared helper.**
Spec section C moves the three row builders into a companion class. The
builders are methods of `SidebarTreeCell`, a non-static inner class: they
close over the cell (`hoverProperty()`, `getTreeItem()`) *and* over roughly
ten private fields of `RepositorySidebar` (`viewModel`, `navigator`,
`agentRegistry`, `openFindingsAt`, `recentlyDiscovered`,
`staleBucketExpanded`, `rescanNotes`, `scanning`, `sessionTooltips`,
`unopenedTooltips`, plus `filter`/`isExempt`). Extracting them means either
widening all of that to package-private or inventing a context type with a
very large surface — a big, risky, purely mechanical change riding along
with a behavioral one. The spec's actual reason for wanting it was "the
overlay change has to be made identically in more than one place", and a
shared `RowOverlay` helper satisfies that reason directly. The file stays
long; that is a separate problem, and a cleaner one to solve on its own.

## Global Constraints

- **Never break the two width clamps.** `SidebarTreeCell.computePrefWidth`
  returns `1` so the virtual flow sizes cells to the viewport, and the
  session name Label has `setMinWidth(0)` + ellipsis overrun. Together they
  stop a long agent-authored name from setting the row's minimum width and
  holding the whole window open — a bug this codebase has shipped. Every
  task must leave both in place.
- **Every new `-drydock-*` token goes in BOTH** `theme-dark.css` and
  `theme-light.css`, or `ThemeTokenContractTest` fails on name parity.
- **Every new `app.css` font size is a bare `px` literal** (e.g. `12.5px`),
  never `em`/`%`/keyword, or the same test fails.
- **Do not touch** the filter chip row, `AgentMarks`, the agent colour
  tokens, `SessionFilter`, `applyFacets`, or session ordering. They are
  recent reviewed work and are out of scope.
- **Commit style:** conventional prefix, imperative mood, last line exactly:
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`
- **Tests:** `./gradlew :app:test --tests 'app.drydock.ui.*'` in the
  FOREGROUND. Never background gradle, never two concurrent invocations
  (they contend for the daemon), and never the full suite from a subagent —
  it takes 14–20 minutes and the controller runs it.
- **`RepositorySidebar` cannot be constructed in a test** (seven
  collaborators, two spawning real `git`, `refreshAllStatuses()` and a 30s
  `INDEFINITE` timeline in its constructor). Do not build a fixture for it.
  Pure logic gets extracted and unit-tested; the rest is the visual pass.

---

### Task 1: `SidebarRowMetrics` — the branch tag's width, as a pure function

**Files:**
- Create: `app/src/main/java/app/drydock/ui/SidebarRowMetrics.java`
- Test: `app/src/test/java/app/drydock/ui/SidebarRowMetricsTest.java`

**Interfaces:**
- Produces: `static double branchTagMaxWidth(double rowWidth)`. Task 2 binds
  the branch Label's `maxWidthProperty` to it.

Making the branch yield to the name needs a deterministic rule, because
JavaFX's `HBox` shrinks resizable children proportionally toward their
minimums — with both Labels clamped to `minWidth 0`, a long name and a long
branch would simply share the squeeze, which is today's behavior and the
thing being fixed. Capping the branch at a fraction of the row makes the
name's share the remainder, and makes the rule testable without a toolkit.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/SidebarRowMetricsTest.java`:

```java
package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SidebarRowMetricsTest {

    /** The rule: the branch never takes more than a bounded share of the row. */
    @Test
    void branchTakesABoundedShareOfTheRow() {
        assertEquals(96.0, SidebarRowMetrics.branchTagMaxWidth(240.0), 0.001);
        assertEquals(128.0, SidebarRowMetrics.branchTagMaxWidth(320.0), 0.001);
    }

    /** The name is what is left, and it is always the larger share. */
    @Test
    void theNameKeepsTheMajorityOfTheRow() {
        for (double width : new double[] {120, 240, 320, 640}) {
            double branch = SidebarRowMetrics.branchTagMaxWidth(width);
            assertTrue(branch < width - branch,
                    "branch " + branch + " should be smaller than the name's share at " + width);
        }
    }

    /**
     * A collapsing sidebar reports zero and then negative widths mid-layout;
     * a negative maxWidth would be passed straight to a Label.
     */
    @Test
    void degenerateWidthsClampToZero() {
        assertEquals(0.0, SidebarRowMetrics.branchTagMaxWidth(0.0), 0.001);
        assertEquals(0.0, SidebarRowMetrics.branchTagMaxWidth(-40.0), 0.001);
    }

    /** Below this, a branch tag is unreadable and the name should have it all. */
    @Test
    void aVeryNarrowRowGivesTheBranchNothing() {
        assertEquals(0.0, SidebarRowMetrics.branchTagMaxWidth(60.0), 0.001);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.SidebarRowMetricsTest'`
Expected: compile failure — `cannot find symbol: class SidebarRowMetrics`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/app/drydock/ui/SidebarRowMetrics.java`:

```java
package app.drydock.ui;

/**
 * Width rules for the sidebar's child rows, kept apart from the FX node
 * building so they can be reasoned about (and tested) on their own.
 */
final class SidebarRowMetrics {

    /** The branch tag's share of a session row. The name gets the rest. */
    private static final double BRANCH_SHARE = 0.4;

    /**
     * Below this the row is too narrow for a branch tag to say anything, so
     * the name takes the row outright rather than both ellipsizing to noise.
     */
    private static final double BRANCH_FLOOR_PX = 72.0;

    private SidebarRowMetrics() { }

    /**
     * The widest the branch tag may be on a row of {@code rowWidth}.
     *
     * <p>A cap rather than a layout priority because {@code HBox} shrinks its
     * resizable children proportionally: with the name and the branch both
     * clamped to {@code minWidth 0}, they would share the squeeze and the
     * name would keep losing characters it cannot spare. Capping the branch
     * makes the name's share the remainder.
     */
    static double branchTagMaxWidth(double rowWidth) {
        if (rowWidth < BRANCH_FLOOR_PX) {
            return 0.0;
        }
        return rowWidth * BRANCH_SHARE;
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.SidebarRowMetricsTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/SidebarRowMetrics.java \
        app/src/test/java/app/drydock/ui/SidebarRowMetricsTest.java
git commit -m "feat: a width rule that makes the branch tag yield to the session name"
```

---

### Task 2: `RowOverlay` — hover actions that cost no layout width

**Files:**
- Create: `app/src/main/java/app/drydock/ui/RowOverlay.java`
- Modify: `app/src/main/resources/app/drydock/ui/theme-dark.css` (two tokens)
- Modify: `app/src/main/resources/app/drydock/ui/theme-light.css` (the same two)
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (`.row-overlay`)

**Interfaces:**
- Produces: `static StackPane wrap(Region row, Node actions)`. Tasks 3 and 4
  call it — the session row and the repo row respectively.

- [ ] **Step 1: Add the two theme tokens**

The overlay's fade must end in the row's own background, and that background
differs by state. Add to `theme-dark.css`, beside the agent tokens:

```css
    /* Sidebar row overlay fade. The hover-action strip floats above the row
     * rather than reserving width, so the text it covers has to fade out
     * into the row's own background -- which differs between a hovered row
     * and the active one, hence two values rather than one. Solid, because
     * a gradient stop cannot be a translucent overlay of an unknown
     * backdrop. */
    -drydock-row-fade: #171614;
    -drydock-row-fade-active: #201f1d;
```

And to `theme-light.css`, at the matching position:

```css
    /* Sidebar row overlay fade (light equivalents of the dark set). */
    -drydock-row-fade: #f4f2ee;
    -drydock-row-fade-active: #e9e6e0;
```

These are the sidebar's resting and active row backgrounds composited to
opaque. They are proposed values; the visual pass in Task 6 owns the final
hex, and the acceptance criterion is that no seam is visible where the fade
begins, in either theme, in all three row states.

- [ ] **Step 2: Style the overlay in `app.css`**

```css
/* The floating hover-action strip. It sits in an overlay layer so it costs
   no layout width -- before this, three 22px buttons reserved 70px on every
   session row whether or not the cursor was on it, on a row that had ~84px
   left for the session name. */
.row-overlay-actions {
    -fx-padding: 0 8 0 24;
    -fx-background-color: linear-gradient(to right, transparent 0%, -drydock-row-fade 24px);
}

.session-row.active .row-overlay-actions,
.repo-row.active .row-overlay-actions {
    -fx-background-color: linear-gradient(to right, transparent 0%, -drydock-row-fade-active 24px);
}
```

- [ ] **Step 3: Write the helper**

Create `app/src/main/java/app/drydock/ui/RowOverlay.java`:

```java
package app.drydock.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Floats a row's hover actions above it instead of beside it.
 *
 * <p>The sidebar's action buttons bind their visibility to the row's hover
 * state but stay <em>managed</em>, so they reserve their width on every row
 * at all times -- 70px of a 320px sidebar on a session row whose name had
 * ~84px to live in. Putting them in an overlay layer returns that width
 * permanently, and keeps the list still while the cursor crosses it: an
 * unmanaged-when-hidden fix would reclaim the same width but reflow every
 * row twice per crossing.
 */
final class RowOverlay {

    private RowOverlay() { }

    /**
     * {@code row} with {@code actions} floating over its trailing edge.
     *
     * <p>The stack is {@code pickOnBounds = false} so only the buttons
     * themselves are click targets: the rest of the strip's area passes
     * clicks through to the row beneath, which is what opens the session.
     * Without that, the right-hand third of every row would silently stop
     * responding.
     */
    static StackPane wrap(Region row, Node actions) {
        // The cell reports a preferred width of 1 so the virtual flow sizes
        // every cell to the viewport; the wrapper must not reintroduce a
        // preferred width of its own, and the row must be free to fill it.
        row.setMaxWidth(Double.MAX_VALUE);

        StackPane stack = new StackPane(row, actions);
        stack.setPickOnBounds(false);
        stack.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(actions, Pos.CENTER_RIGHT);
        if (actions instanceof Region region) {
            region.setMaxWidth(Region.USE_PREF_SIZE);
            region.setPickOnBounds(false);
            region.getStyleClass().add("row-overlay-actions");
        }
        return stack;
    }
}
```

- [ ] **Step 4: Compile and run the theme contract test**

Run: `./gradlew :app:compileJava`
then: `./gradlew :app:test --tests 'app.drydock.ui.ThemeTokenContractTest'`
Expected: both BUILD SUCCESSFUL. A test failure here means a token is
missing from one of the two sheets, or `app.css` referenced one that is not
defined. `RowOverlay` has no caller yet — that is expected at this point,
and Tasks 3 and 4 are what wire it in.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RowOverlay.java \
        app/src/main/resources/app/drydock/ui/
git commit -m "feat: an overlay layer for row hover actions"
```

---

### Task 3: The session row becomes one line

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` —
  `buildSessionRow` (starts at line 1782) and the `SidebarTreeCell.updateItem`
  arm that calls it (line ~1603)

**Interfaces:**
- Consumes: `SidebarRowMetrics.branchTagMaxWidth(double)` (Task 1),
  `RowOverlay.wrap(Region, Node)` (Task 2).
- Produces: `buildSessionRow` returns a `StackPane` rather than an `HBox`;
  `updateItem`'s `SessionNode` arm must accept that. Nothing else in the file
  calls it.

- [ ] **Step 1: Rebuild the row's body**

In `buildSessionRow`, the leading gutter and the name Label keep their
current construction (status dot + agent mark in the `HBox` gutter; the name
with `setMinWidth(0)`, `setMaxWidth(Double.MAX_VALUE)` and
`OverrunStyle.ELLIPSIS`). Replace the `nameRow`/`meta`/`text` VBox block
with a single-line arrangement:

```java
            // One line, name-first: the branch is capped so it yields
            // characters before the name does (see SidebarRowMetrics).
            HBox.setHgrow(name, Priority.ALWAYS);

            Label branchTag = new Label((isWorktree ? "◫ " : "⎇ ") + branch);
            branchTag.getStyleClass().add(isWorktree ? "branch-tag-worktree" : "branch-tag");
            branchTag.setMinWidth(0);
            branchTag.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
            HBox.setHgrow(branchTag, Priority.NEVER);
```

Delete the `meta` Label (`UiFormats.relativeTime(session.lastOpenedAt())`)
and the `VBox text` entirely — the row's ordering already says what it said
(live band first, needs-attention pinned to its front, then idle, each
most-recently-opened first), and the tooltip still carries the exact time.

Delete the `resumePill` block. Clicking anywhere on the row already resumes
the session, and the pill cost ~62px of the name's width to say so.

Keep the dirty dot, the `prChip`, the findings badge and the `attention`
badge exactly as they are — those say things the name does not.

- [ ] **Step 2: Assemble the row and cap the branch**

The row's children, in order: gutter, name, then any informational chips,
then the branch tag last:

```java
            HBox row = new HBox(8, statusCol, name);
            if (checkoutStatus != null && checkoutStatus.dirty()) {
                Region dirtyDot = new Region();
                dirtyDot.getStyleClass().add("dirty-dot");
                row.getChildren().add(dirtyDot);
            }
            if (prChip != null) {
                row.getChildren().add(prChip);
            }
            session.worktreeRoot().ifPresent(root ->
                    findingsBadge(root).ifPresent(badge -> row.getChildren().add(badge)));
            SessionActivity activity = viewModel.activityOf(session.id());
            if (activity == SessionActivity.NEEDS_ATTENTION) {
                Label attention = new Label("waiting");
                attention.getStyleClass().add("attention-badge");
                row.getChildren().add(attention);
            }
            row.getChildren().add(branchTag);
            branchTag.maxWidthProperty().bind(
                    row.widthProperty().map(SidebarRowMetrics::branchTagMaxWidth));
```

`Region.maxWidthProperty` is a `DoubleProperty`; bind it through
`row.widthProperty().map(...)` (JavaFX 19+ `ObservableValue.map`, available
on this toolchain) rather than a hand-rolled `DoubleBinding`.

- [ ] **Step 3: Float the actions**

The `actions` HBox keeps its construction and its
`visibleProperty().bind(hoverProperty())`. Instead of being added to `row`,
it is passed to the overlay, and `buildSessionRow`'s return type becomes
`StackPane`:

```java
            row.getStyleClass().addAll("session-row", "child-row");
            row.setAlignment(Pos.CENTER_LEFT);
            // ... existing active-state, tooltip and click handler code, all
            // still applied to `row` ...
            return RowOverlay.wrap(row, actions);
```

Change the method signature from `private HBox buildSessionRow(...)` to
`private StackPane buildSessionRow(...)`. In `updateItem`'s `SessionNode`
arm, `setGraphic(buildSessionRow(...))` needs no change — `setGraphic` takes
a `Node`.

Leave the click handler, the tooltip install and the `.active` style class
on `row`, not on the wrapper: the hover pseudo-class the actions bind to is
the cell's (`hoverProperty()` of `SidebarTreeCell`), and `.session-row:hover`
styling keys on the row.

- [ ] **Step 4: Update the tooltip**

The row tooltip already carries status, agent, last-opened and working
directory. It is now the only place the timestamp lives, so verify the
`Last opened:` line is present and unchanged. No edit expected — confirm
rather than assume, and say so in your report.

- [ ] **Step 5: Build and test**

Run: `./gradlew :app:test --tests 'app.drydock.ui.*'`
Expected: BUILD SUCCESSFUL. This task changes no tested behavior; a failure
means the row construction broke a compile or an existing assertion.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java
git commit -m "feat: single-line session rows, with the name taking the width"
```

---

### Task 4: The repo row's actions float too

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/RepositorySidebar.java` —
  `buildRepoRow` (starts at line 1623)

**Interfaces:**
- Consumes: `RowOverlay.wrap(Region, Node)` (Task 2).
- Produces: `buildRepoRow` returns `StackPane`.

The repo row has the identical defect: `rescan` and `newSession` bind
visibility to hover but stay managed, reserving ~44px on every repository
row. The unopened-worktree row does NOT have this problem — its `Start ▸`
pill and 🗑 button are always visible — so it is not touched.

- [ ] **Step 1: Group the two buttons and float them**

In `buildRepoRow`, the two buttons currently sit directly in the row:
`HBox row = new HBox(7, caret, text, count, rescan, newSession);`. Group
them and hand them to the overlay:

```java
            HBox actions = new HBox(2, rescan, newSession);
            actions.setAlignment(Pos.CENTER_RIGHT);

            HBox row = new HBox(7, caret, text, count);
            row.getStyleClass().add("repo-row");
            row.setAlignment(Pos.CENTER_LEFT);
            // ... existing click handler, unchanged, on `row` ...
            return RowOverlay.wrap(row, actions);
```

Each button keeps its own `visibleProperty().bind(hoverProperty())` — the
overlay changes where they live, not when they appear.

Change the signature from `private HBox buildRepoRow(...)` to
`private StackPane buildRepoRow(...)`.

- [ ] **Step 2: Check the rescan spin still stops**

`buildRepoRow` starts a `RotateTransition` with `INDEFINITE` cycles on the
`rescan` button while a scan is in flight, and stops it via a
`sceneProperty` listener when the button leaves the scene. The button now
leaves the scene as part of the overlay rather than the row. Confirm that
listener is untouched and still fires — an `INDEFINITE` animation on a
detached node runs forever, which AGENTS.md calls out by name.

- [ ] **Step 3: Build and test**

Run: `./gradlew :app:test --tests 'app.drydock.ui.*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/drydock/ui/RepositorySidebar.java
git commit -m "feat: float the repository row's hover actions too"
```

---

### Task 5: Gutter alignment and the agent mark's min-width

**Files:**
- Modify: `app/src/main/resources/app/drydock/ui/app.css` —
  `.child-row-status` (line ~250 area) and `.agent-mark`

- [ ] **Step 1: Left-align the shared gutter**

`.child-row-status` is the shared indent gutter for the session, unopened,
stale and locked rows. It currently centers its content, which was fine when
every row had a single glyph in it — but the session row now holds two (dot
+ mark), and a centered pair does not begin where a centered single glyph
does. Left-align it so all four row types start at the same x:

```css
.child-row-status {
    -fx-min-width: 30;
    -fx-alignment: center-left;
}
```

This is the resolution of the alignment question deferred from the
harness-marks work, and the drift visible in that build is the evidence for
it.

- [ ] **Step 2: Give the agent mark a real min-width**

`.agent-mark` currently declares `-fx-min-width: -fx-pref-width`. JavaFX
resolves `-fx-pref-width` there as a stylesheet lookup, and nothing declares
it for that node, so the rule is almost certainly inert. Replace it with a
literal so the glyph can never be squeezed:

```css
.agent-mark {
    -fx-font-size: 11px;
    -fx-min-width: 12px;
}
```

- [ ] **Step 3: Run the theme contract test**

Run: `./gradlew :app:test --tests 'app.drydock.ui.ThemeTokenContractTest'`
Expected: PASS. It enforces that every `app.css` font size is a bare `px`
literal, which `11px` satisfies.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/app/drydock/ui/app.css
git commit -m "fix: line up every child row's leading gutter"
```

---

### Task 6: Visual verification, before and after

**Files:**
- No source changes expected. If the pass finds a problem, fix it and record
  what changed.

The claim being tested is "the rows are readable now", and only a comparison
at the same width supports it. A control capture at the pre-redesign commit
already exists at `/tmp/control-dark.png`; if it is gone, produce one the
same way.

How this app is verified visually (documented in `docs/architecture.md`, not
in AGENTS.md): run the real app via `./gradlew run` with
`-Papp.drydock.diag.*` properties and capture with the in-app `shot:` script
verb. `screencapture` does not work for an agent process — no Screen
Recording grant. Script steps are `<atSeconds>:<verb>[:<arg>]`, comma
separated; `tabScript` verbs include `filter`, `facet:<name>`, `shot:<path>`
and `mark`. The state file's schema key is `schemaVersion` — a wrong key is
not a hard error, the app logs "missing, truncated, or malformed" and starts
EMPTY, which looks exactly like the diag properties being ignored. Kill
leftover instances by PID (`ps -eo pid,command | grep diag`), never
`pkill -f app.drydock.DrydockApplication` — the user's own Drydock may be
hosting an agent session.

Reuse the state file from the harness-marks visual pass: four sessions with
long agent-authored names across all three agents plus an unrecognised one,
`sidebarWidth` 320.0, one stale worktree.

- [ ] **Step 1: Capture the after, at 320px, both themes**

The unfiltered sidebar. Compare against the control: the session name must
be legible rather than cut at ~9 characters, and no row may show a `…`-only
pill.

- [ ] **Step 2: Capture a hovered row**

The overlay is the riskiest part of this change. Confirm: the buttons appear
over the row's trailing edge; the text under them fades out with no visible
seam where the gradient starts; and **nothing in the row moves** as the
cursor enters. Check all three backgrounds — resting, hover, and the
`.active` row — since the fade has a different token for the active case.

- [ ] **Step 3: Confirm the row is still clickable under the strip**

`pickOnBounds = false` is what keeps the row's trailing third clickable. Use
the diag script to click at the row's right-hand end, outside the buttons,
and confirm the session opens rather than the click being swallowed.

- [ ] **Step 4: Capture the informational chips**

A row with a `PR #n` chip and one with the `waiting` badge, so their
interaction with the capped branch tag is visible. The branch must be the
thing that shortens, not the name.

- [ ] **Step 5: Capture the gutter alignment**

One repository showing a session row, an unopened-worktree row and an
EXPANDED stale bucket, so all four gutter users are in one frame. Their
leading glyphs must begin at the same x.

- [ ] **Step 6: Sanity-check the narrow case**

Drag or set the sidebar narrow (~200px) and confirm the branch tag
disappears rather than fighting the name, that no horizontal scrollbar
appears, and — the important one — that the window can still be made
narrower, i.e. no row is holding a minimum width open.

- [ ] **Step 7: Commit any fixes and record the outcome**

```bash
git add -A
git commit -m "fix: visual pass adjustments for the sidebar row layout"
```

---

## Notes for the implementer

- **The two clamps are load-bearing.** `computePrefWidth` returning 1 and
  `name.setMinWidth(0)` are what stop a long session title from holding the
  application window open and from growing a horizontal scrollbar that
  pushes the action buttons out of view. The `StackPane` wrapper sits
  directly between them; if the window stops being resizable or a scrollbar
  appears, that is where to look.
- **`pickOnBounds = false` is not cosmetic.** Without it the overlay strip
  eats clicks across the row's trailing third and the row silently stops
  opening its session.
- **Do not "fix" the unopened-worktree row to match.** Its buttons are
  always visible, so they use the width they reserve. There is no defect
  there.
