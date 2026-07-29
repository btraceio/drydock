# Review Queue Quick-Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a quick-search field to the Review queue rail so a queue of tens of items can be narrowed to the one item the reviewer wants.

**Architecture:** `ReviewQueueRail` owns the query. `setItems` keeps receiving the full queue from assembly; the rail renders a filtered *view* of it, recomputed in `rebuild()` on every keystroke. A static package-private `matches(ReviewItem, String)` holds the predicate so it can be tested without a toolkit. Everything that walks the queue — `j`/`k`, the footer count, the empty state — moves from the full list to that visible view. A separate `revealAndSelect(String)` entry point clears the query for navigations arriving from outside the rail, leaving `select(String)` exactly as it is today.

**Tech Stack:** Java 26, JavaFX 26, Gradle, JUnit 5, TestFX + Monocle (headless), CSS in `app/src/main/resources/app/drydock/ui/app.css`.

## Global Constraints

- **Never block the JavaFX application thread** on git, `gh`, or filesystem work (AGENTS.md). Nothing in this plan does I/O, so nothing here needs a background thread — but do not add any.
- **Spec of record:** `docs/superpowers/specs/2026-07-29-review-queue-quick-search-design.md`. Where this plan and the spec disagree, the spec wins; stop and ask.
- **Branch:** work continues on `fix/review-base-intents-collapse` (already checked out).
- **Build/test command:** `./gradlew :app:test`. A single class: `./gradlew :app:test --tests 'app.drydock.ui.review.ReviewQueueRailMatchTest'`.
- **The rail is `ReviewQueueRail`** at `app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java`. It is package-private (`final class`, no `public`), so its new members are package-private too — not `public`.
- **Comment style:** this codebase writes comments that explain *why*, in prose, and uses `--` for an em dash inside Javadoc. Match it. Do not add narrating comments like `// set the text`.
- **No debounce anywhere in this feature.** Filtering runs on every keystroke by design (spec §Rendering).

### Vocabulary

- **item list** — every `ReviewItem` the rail holds (`items`). Unfiltered. What `setItems` receives.
- **visible list** — what the rail is currently rendering: the query's survivors while expanded, the whole item list while collapsed. Introduced in Task 2 as `visibleItems()`.
- **query** — the text in the filter field. Owned by the field itself; read through `query()`, never cached in a second variable.

## File Structure

| File | Change | Responsibility |
| --- | --- | --- |
| `app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java` | Modify | Owns the field, the query, the predicate, the visible list, `focusFilter()`, `revealAndSelect()` |
| `app/src/test/java/app/drydock/ui/review/ReviewQueueRailMatchTest.java` | Create | Pure tests for `matches` — no toolkit |
| `app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java` | Modify | TestFX coverage of the rendered behaviour |
| `app/src/main/resources/app/drydock/ui/app.css` | Modify | `.review-queue-filter`, `.review-queue-no-match` |
| `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` | Modify | `/` in the key table; `selectScope` routes to `revealAndSelect`; exposes the rail's filterability |
| `app/src/main/java/app/drydock/ui/MainWorkspace.java` | Modify | Two pass-throughs so the scene filter can ask about the rail |
| `app/src/main/java/app/drydock/DrydockApplication.java` | Modify | One branch in `⌘F` |
| `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` | Modify | One row in the IN REVIEW section |

Four tasks. Task 1 is pure logic with no UI. Task 2 makes the feature visible and usable with the mouse. Task 3 fixes the keyboard selection paths the filter breaks. Task 4 adds the accelerators. Each ends green and committable.

---

### Task 1: The `matches` predicate

The whole search rule, as a static method with no UI attached. Nothing calls it yet — that is Task 2.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java` (add one method next to `nextIndex`, around line 190)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewQueueRailMatchTest.java` (create)

**Interfaces:**
- Consumes: `ReviewItem` (record: `scope()`, `group()`, `title()`, `subtitle()`; `group().label()` returns `"MINE"` / `"AGENTS"` / `"REQUESTED"` / `"STACK"`).
- Produces: `static boolean matches(ReviewItem item, String query)` — package-private on `ReviewQueueRail`. Tasks 2 and 3 call it.

**Note on the test class name:** the spec calls this `ReviewQueueRailTest`. The existing pure test for `nextIndex` is `ReviewQueueRailSelectionTest`, so use `ReviewQueueRailMatchTest` to match the established naming. Do not rename the existing file.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/drydock/ui/review/ReviewQueueRailMatchTest.java`:

```java
package app.drydock.ui.review;

import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quick-search predicate, tested without a JavaFX toolkit -- the rule
 * lives in a static so it can be exercised directly, the same reason
 * {@code nextIndex} is one (see {@link ReviewQueueRailSelectionTest}).
 */
class ReviewQueueRailMatchTest {

    private static final ReviewItem AGENT_WORKTREE =
            item(ReviewItem.Group.AGENTS, "agent/issue-919-metadata", "btrace · vs develop");
    private static final ReviewItem PULL_REQUEST =
            item(ReviewItem.Group.REQUESTED, "PR #854 renovate/all-minor-patch", "btrace · @renovate");

    @Test
    void aTitleSubstringMatches() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "919"));
    }

    @Test
    void aSubtitleSubstringMatches() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "develop"));
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "btrace"));
    }

    /**
     * The rail prints the group label at the head of every row's second
     * line, so a reviewer can read "agents" on screen -- and must therefore
     * be able to search for it, even though it is not part of the subtitle.
     */
    @Test
    void aGroupLabelQueryMatchesThatGroupAndNoOther() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "agents"));
        assertFalse(ReviewQueueRail.matches(PULL_REQUEST, "agents"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "RENOVATE"));
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "ReNoVaTe"));
    }

    @Test
    void aBlankQueryMatchesEverything() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, ""));
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "   "));
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "\t"));
    }

    @Test
    void aQueryInNoFieldDoesNotMatch() {
        assertFalse(ReviewQueueRail.matches(AGENT_WORKTREE, "zzz"));
    }

    /**
     * The fields are joined by separators precisely so a query cannot match
     * the artifact where one field's tail meets the next field's head.
     */
    @Test
    void aQueryConcatenatingTwoFieldsDoesNotMatch() {
        assertFalse(ReviewQueueRail.matches(PULL_REQUEST, "patchbtrace"));
        assertFalse(ReviewQueueRail.matches(AGENT_WORKTREE, "agentsagent"));
    }

    private static ReviewItem item(ReviewItem.Group group, String title, String subtitle) {
        ReviewScope scope = new ReviewScopeRegistry().mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/x")),
                "develop", title, Optional.empty(), Optional.empty()));
        return new ReviewItem(scope, group, title, subtitle);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewQueueRailMatchTest'
```

Expected: compilation failure — `cannot find symbol: method matches(ReviewItem,String)`. A compile error is the correct red here; there is nothing to call yet.

- [ ] **Step 3: Add the predicate**

In `ReviewQueueRail.java`, immediately after the `nextIndex` method (it ends with `return (int) Math.clamp((long) current + delta, 0, size - 1);` and its closing brace, around line 198), add:

```java
    /**
     * Whether {@code item} survives the quick-search {@code query}.
     *
     * <p>The haystack is the row's whole visible text: the group label the
     * rail prints at the head of the second line, then the title, then the
     * subtitle. They are joined by separators so a query cannot match the
     * artifact where one field's tail meets the next one's head -- what you
     * can read in the row is what you can search for, and nothing else. A
     * blank query matches everything.</p>
     *
     * <p>Package-private and static for the same reason {@link #nextIndex}
     * is: the rule is then testable without a running toolkit.</p>
     */
    static boolean matches(ReviewItem item, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        String haystack = item.group().label() + " " + item.title() + " " + item.subtitle();
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
```

`java.util.Locale` is already imported in this file (line 25). Do not add it again.

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewQueueRailMatchTest'
```

Expected: 7 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java \
        app/src/test/java/app/drydock/ui/review/ReviewQueueRailMatchTest.java
git commit -m "Review queue: the quick-search predicate, over every field the row shows"
```

---

### Task 2: The field, the filtered render, the footer and the empty state

After this task the feature works with the mouse: type, rows narrow, footer counts, collapse suppresses it.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java` (imports; field declarations ~line 68-76; constructor ~line 87-103; `rebuild()` ~line 243-269)
- Modify: `app/src/main/resources/app/drydock/ui/app.css` (after the `.review-queue-group` block, ~line 2228)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java` (append tests + helpers)

**Interfaces:**
- Consumes: `matches(ReviewItem, String)` from Task 1.
- Produces, all package-private on `ReviewQueueRail`:
  - `private List<ReviewItem> visibleItems()` — the query's survivors while expanded, `items` while collapsed. Task 3 uses it.
  - `private String query()` — the field's text, never null.
  - `private final TextField filterField` — Task 4 attaches key handlers to it.
  - CSS classes `review-queue-filter` (on the field) and `review-queue-no-match` (on the empty-state label) — the tests query both.

- [ ] **Step 1: Write the failing tests**

Append these to `ReviewDestinationViewTest.java`, before the private helper block that starts with `private void seedQueue()`:

```java
    @Test
    void typingNarrowsTheRailAndDropsAnEmptiedGroupHeading() {
        seedMixedQueue();
        assertEquals(List.of("feat/a", "agent/issue-919", "agent/issue-920"), renderedTitles());
        assertEquals(List.of("MINE", "AGENTS"), renderedGroups());

        typeQuery("919");

        assertEquals(List.of("agent/issue-919"), renderedTitles());
        assertEquals(List.of("AGENTS"), renderedGroups(),
                "a group whose every row was filtered out must not keep its heading");
    }

    @Test
    void theFooterCountsWhatIsShownAgainstWhatExists() {
        seedMixedQueue();
        assertEquals("3 items", footerText());

        typeQuery("919");
        assertEquals("1 of 3 items", footerText());

        typeQuery("zzz");
        assertEquals("0 of 3 items", footerText());

        typeQuery("");
        assertEquals("3 items", footerText());
    }

    /**
     * An empty rail reads as a broken queue. The queue is fine; the query is
     * too narrow, and the rail has to say so.
     */
    @Test
    void noMatchesExplainsItselfInsteadOfShowingAnEmptyRail() {
        seedMixedQueue();
        typeQuery("zzz");

        assertTrue(renderedTitles().isEmpty());
        assertEquals("No queue item matches \"zzz\"",
                ((Label) lookup(".review-queue-no-match").query()).getText());
    }

    /**
     * A collapse can come from a window resize, so it cannot silently hide
     * rows: the 44px rail has no field and no footer to explain a gap, so it
     * shows everything and re-applies the query on the way back out.
     */
    @Test
    void collapsingSuspendsTheQueryAndExpandingRestoresIt() {
        seedMixedQueue();
        typeQuery("919");
        assertEquals(1, renderedTitles().size());

        type(KeyCode.Q);
        settledRailWidth();
        // Count rows, not titles: a collapsed row is an icon column with no
        // title label, so renderedTitles() is empty by construction there.
        assertEquals(3, renderedRows(), "a collapsed rail must render every item");
        assertFalse(lookup(".review-queue-filter").query().isVisible());

        type(KeyCode.Q);
        settledRailWidth();
        assertEquals(1, renderedTitles().size(), "the query returns with the rail");
        assertTrue(lookup(".review-queue-filter").query().isVisible());
    }
```

And append these helpers to the private helper block at the end of the class:

```java
    /** Three items across two groups, so a query can empty a whole group. */
    private void seedMixedQueue() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        interact(() -> view.setItems(List.of(
                item(registry, "feat/a", Optional.empty()),
                agentItem(registry, "agent/issue-919"),
                agentItem(registry, "agent/issue-920")), 1));
    }

    private static ReviewItem agentItem(ReviewScopeRegistry registry, String head) {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/" + head)),
                "master", head, Optional.empty(), Optional.empty()));
        return new ReviewItem(scope, ReviewItem.Group.AGENTS, head, "drydock · vs master");
    }

    /** Replaces the filter field's text on the FX thread, as typing would. */
    private void typeQuery(String query) {
        interact(() -> ((javafx.scene.control.TextField) lookup(".review-queue-filter").query())
                .setText(query));
    }

    private List<String> renderedTitles() {
        return lookup(".review-queue-item").queryAll().stream()
                .map(node -> (Parent) ((Button) node).getGraphic())
                .flatMap(graphic -> graphic.lookupAll(".review-queue-title").stream().findFirst().stream())
                .map(label -> ((Label) label).getText())
                .toList();
    }

    /** Rows regardless of collapse: a collapsed row renders an icon, not a title. */
    private int renderedRows() {
        return lookup(".review-queue-item").queryAll().size();
    }

    private List<String> renderedGroups() {
        return lookup(".review-queue-group").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private String footerText() {
        return ((Label) lookup(".review-queue-rail .review-rail-footer").query()).getText();
    }
```

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDestinationViewTest'
```

Expected: the four new tests fail — `.review-queue-filter` is not in the scene graph, so `lookup(...).query()` throws. The pre-existing tests in the class must still pass.

- [ ] **Step 3: Add the field and the visible list**

In `ReviewQueueRail.java`:

**(a) Imports.** Add to the existing import block:

```java
import javafx.geometry.Insets;
import javafx.scene.control.TextField;
```

**(b) Field declaration.** After `private final Label footer = new Label();` (~line 73):

```java
    private final TextField filterField = new TextField();
```

**(c) Constructor.** Replace the final line `getChildren().setAll(header.node(), scroll, footer);` with:

```java
        filterField.getStyleClass().addAll("filter-field", "review-queue-filter");
        filterField.setPromptText("⌕  Filter the queue…");
        // No debounce: this rebuild is tens of buttons over in-memory
        // lookups, so a timer would only add latency (spec §Rendering).
        filterField.textProperty().addListener((observable, old, text) -> rebuild());
        VBox.setMargin(filterField, new Insets(0, 8, 6, 8));

        getChildren().setAll(header.node(), filterField, scroll, footer);
```

**(d) The visible list.** Add these two methods immediately above `private void rebuild()`:

```java
    /**
     * What the rail is actually rendering: the query's survivors while
     * expanded, and every item while collapsed.
     *
     * <p>A collapse suppresses the filtering as well as the field. The 44px
     * rail still draws one row per item, so a collapsed rail that kept
     * filtering would show three icons where thirteen exist -- with no
     * field, no footer count and nothing on screen to explain the gap. The
     * query is kept rather than cleared, because a collapse can come from a
     * window resize rather than from the user.</p>
     */
    private List<ReviewItem> visibleItems() {
        if (collapsed) {
            return List.copyOf(items);
        }
        return items.stream().filter(item -> matches(item, query())).toList();
    }

    private String query() {
        return filterField.getText() == null ? "" : filterField.getText();
    }
```

**(e) `rebuild()`.** Replace the whole method with:

```java
    private void rebuild() {
        header.showCollapsed(collapsed);
        header.setTitleVisible(!collapsed);
        header.setHintVisible(!collapsed);
        filterField.setVisible(!collapsed);
        filterField.setManaged(!collapsed);
        footer.setVisible(!collapsed);
        footer.setManaged(!collapsed);

        List<ReviewItem> visible = visibleItems();
        buttonsByScopeId.clear();
        List<Node> children = new ArrayList<>();
        ReviewItem.Group lastGroup = null;
        for (ReviewItem item : visible) {
            if (item.group() != lastGroup) {
                lastGroup = item.group();
                if (!collapsed) {
                    Label group = new Label(lastGroup.label());
                    group.getStyleClass().add("review-queue-group");
                    children.add(group);
                }
            }
            Button row = buildRow(item);
            buttonsByScopeId.put(item.scope().id(), row);
            children.add(row);
        }
        // An empty rail reads as a broken queue. Say what actually happened:
        // the queue is fine and the query is too narrow.
        if (visible.isEmpty() && !items.isEmpty() && !collapsed) {
            Label noMatch = new Label("No queue item matches \"" + query().strip() + "\"");
            noMatch.getStyleClass().add("review-queue-no-match");
            noMatch.setWrapText(true);
            children.add(noMatch);
        }
        rows.getChildren().setAll(children);
        applySelectionStyles();
        footer.setText(footerText(visible.size(), items.size()));
    }

    /** {@code 13 items}, or {@code 3 of 13 items} while a query is narrowing the rail. */
    private static String footerText(int shown, int total) {
        String noun = total == 1 ? " item" : " items";
        return shown == total ? total + noun : shown + " of " + total + noun;
    }
```

The loop body is unchanged from today except that it walks `visible` rather than `items`; the group-heading rule needs no change because it already keys off the *rendered* sequence.

- [ ] **Step 4: Add the styles**

In `app/src/main/resources/app/drydock/ui/app.css`, immediately after the `.review-queue-group { ... }` block:

```css
/* The queue's quick-search field: the sidebar's filter, sized for a 236px
 * rail. Hidden and unmanaged while the rail is collapsed. */
.review-queue-filter {
    -fx-min-height: 26px; -fx-max-height: 26px;
    -fx-font-size: 11px;
    -fx-padding: 0 8 0 8;
}
.review-queue-no-match {
    -fx-text-fill: -drydock-text-faint;
    -fx-font-size: 11px;
    -fx-padding: 10 4 0 4;
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDestinationViewTest'
```

Expected: PASS, including every pre-existing test in the class.

- [ ] **Step 6: Run the whole suite**

```bash
./gradlew :app:test
```

Expected: PASS. `rebuild()` changed shape, so anything else asserting on rail contents would surface here.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java \
        app/src/main/resources/app/drydock/ui/app.css \
        app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java
git commit -m "Review queue: a quick-search field that narrows the rail as you type"
```

---

### Task 3: `j`/`k` over the visible list, and `revealAndSelect`

Task 2 left two keyboard paths reading the full list. `j`/`k` would step onto rows the query is hiding, and `⌘4` / the sidebar badge could select a row the user cannot see.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java` (`moveSelection` and `indexOfSelection`, ~line 162-177; new `revealAndSelect`)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (`selectScope`, line 365-367)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java`

**Interfaces:**
- Consumes: `visibleItems()` and `query()` from Task 2.
- Produces: `void revealAndSelect(String scopeId)` on `ReviewQueueRail`, called only by `ReviewDestinationView.selectScope`.

**What must NOT change:** `select(String)` keeps today's semantics exactly — it resolves against the **full** item list and never touches the query. `j`/`k`, a row's own click handler, and `ReviewDestinationView.setItems` restoring the previous selection after a reassembly all go through it. `select` already null-checks `buttonsByScopeId.get(scopeId)` before scrolling, so a selection with no rendered row is already safe.

- [ ] **Step 1: Write the failing tests**

Append to `ReviewDestinationViewTest.java`, alongside the Task 2 tests:

```java
    /**
     * The centre panel never moves on a keystroke -- a selection is a real
     * git diff -- so a query that hides the selected row leaves both the
     * selection and the rendered diff alone.
     */
    @Test
    void aQueryThatHidesTheSelectionLeavesTheSelectionAlone() {
        seedMixedQueue();
        type(KeyCode.J);
        assertEquals("agent/issue-919", selectedTitle());
        String selected = view.diagSelectedScopeId().orElseThrow();

        typeQuery("feat");

        assertEquals(List.of("feat/a"), renderedTitles());
        assertNull(selectedTitle(), "the selected row is filtered out, so no rendered row is selected");
        assertEquals(Optional.of(selected), view.diagSelectedScopeId(),
                "the selection itself, and the diff it drives, must survive the filter");
    }

    @Test
    void jAndKWalkTheVisibleListWhenTheSelectionIsHidden() {
        seedMixedQueue();
        type(KeyCode.J);
        assertEquals("agent/issue-919", selectedTitle());

        // "feat" hides the selection. "agent" would NOT -- it matches both
        // AGENTS rows, so the selection would still be visible and the test
        // would exercise nothing.
        typeQuery("feat");
        type(KeyCode.J);

        assertEquals("feat/a", selectedTitle(),
                "j must enter the visible list, not step from the hidden row");

        typeQuery("920");
        type(KeyCode.K);

        assertEquals("agent/issue-920", selectedTitle(),
                "k must enter the visible list from its own end");
    }

    /**
     * A targeted navigation (⌘4, the sidebar's badge) must never land on a
     * row the user cannot see. A reassembly restoring its own selection must
     * never clear what the user typed.
     */
    @Test
    void revealAndSelectClearsTheQueryButAPlainSelectDoesNot() {
        seedMixedQueue();
        typeQuery("feat");
        String hidden = view.diagItems().get(1).scope().id();

        interact(() -> view.selectScope(hidden));

        assertEquals("", queryText(), "a targeted navigation clears the query");
        assertEquals("agent/issue-919", selectedTitle());

        typeQuery("feat");
        interact(() -> view.setItems(view.diagItems(), 1));

        assertEquals("feat", queryText(), "a reassembly must leave the query alone");
    }
```

Add one more helper to the helper block:

```java
    private String queryText() {
        return ((javafx.scene.control.TextField) lookup(".review-queue-filter").query()).getText();
    }
```

Add the static import `assertNull` to the existing import block:

```java
import static org.junit.jupiter.api.Assertions.assertNull;
```

Both accessors these tests use already exist on `ReviewDestinationView` — add neither:

- `public List<ReviewItem> diagItems()` (line 1042) returns `queue.items()`, the **full** item list, so `diagItems().get(1)` is `agent/issue-919` regardless of any query.
- `public Optional<String> diagSelectedScopeId()` (line 1017) resolves through `queue.selected()`, which searches the full list — which is exactly why the selection survives a filter that hides its row.

While you are here, tighten `diagItems()`'s Javadoc: it says "the queue rows currently rendered", which this feature makes false. Make it read `Diagnostic-only: every queue item, filtered or not (visual verification harness).`

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDestinationViewTest'
```

Expected two failures:
- `jAndKWalkTheVisibleListWhenTheSelectionIsHidden` — today's `moveSelection` steps through the **full** list onto a row the query is not rendering, so no rendered row carries the `selected` pseudo-class and `selectedTitle()` returns `null` rather than `"feat/a"`.
- `revealAndSelectClearsTheQueryButAPlainSelectDoesNot` — fails on the first assertion, because nothing clears the query.

- [ ] **Step 3: Point `j`/`k` at the visible list**

In `ReviewQueueRail.java`, replace `moveSelection` and `indexOfSelection` with:

```java
    /**
     * {@code j} / {@code k}: moves the selection by {@code delta} through the
     * rows the rail is actually showing. A selection the query has hidden is
     * not in that list, so it reports {@code -1} and {@link #nextIndex}'s
     * existing "nothing selected" branch enters the visible list from
     * whichever end the key came from.
     */
    void moveSelection(int delta) {
        List<ReviewItem> visible = visibleItems();
        int next = nextIndex(indexOfSelection(visible), visible.size(), delta);
        if (next >= 0) {
            select(visible.get(next).scope().id());
        }
    }

    private int indexOfSelection(List<ReviewItem> visible) {
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).scope().id().equals(selectedScopeId)) {
                return i;
            }
        }
        return -1;
    }
```

`nextIndex` itself does not change.

- [ ] **Step 4: Add `revealAndSelect`**

In `ReviewQueueRail.java`, immediately after `select(String scopeId)`:

```java
    /**
     * Clears any query, then selects -- for a navigation arriving from
     * outside the rail ({@code ⌘4}, the sidebar's {@code ◨n} badge), which
     * must never land on a row the query is hiding: a badge that appears to
     * do nothing is worse than a cleared query.
     *
     * <p>Deliberately not folded into {@link #select}. That method is also
     * what {@code j}/{@code k}, a row's own click handler, and the
     * reassembly that restores the previous selection all call, and none of
     * those may touch what the user typed.</p>
     */
    void revealAndSelect(String scopeId) {
        if (!query().isEmpty()) {
            filterField.clear();
        }
        select(scopeId);
    }
```

`filterField.clear()` fires the text listener, which rebuilds — so the row exists by the time `select` looks it up in `buttonsByScopeId`.

- [ ] **Step 5: Route `selectScope` through it**

In `ReviewDestinationView.java`, replace lines 364-367:

```java
    /** Selects the item for {@code scopeId} ({@code ⌘4} and the sidebar's {@code ◨n} badge). */
    public void selectScope(String scopeId) {
        queue.revealAndSelect(scopeId);
    }
```

Leave the `queue.select(...)` call in `setItems` (line ~360) exactly as it is — that is the reassembly path, and it must not clear the query.

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDestinationViewTest'
```

Expected: PASS, including `jAndKWalkTheQueueAndClampAtBothEnds` — with no query, the visible list is the item list, so today's behaviour is unchanged.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java
git commit -m "Review queue: j/k walk the filtered rail, and a targeted navigation reveals its row"
```

---

### Task 4: `/`, `Enter`, `Esc`, `⌘F`, and the shortcuts overlay

The accelerators. This is the task the adversarial spec review reshaped, so read the two boxed notes before writing code.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java` (`focusFilter()`, the field's key handler)
- Modify: `app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java` (key table ~line 918; two new public methods near `selectScope`)
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java` (two pass-throughs near `isReviewShowing()`, line 722)
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java` (the `⌘F` branch, ~line 804)
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java` (IN REVIEW section, ~line 46)
- Test: `app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java`

**Interfaces:**
- Consumes: `visibleItems()`, `query()`, `filterField` (Task 2); `select` (unchanged).
- Produces:
  - `void focusFilter()` on `ReviewQueueRail` — package-private.
  - `public boolean queueFilterAvailable()` and `public void focusQueueFilter()` on `ReviewDestinationView`.
  - `public boolean isReviewQueueFilterable()` and `public void focusReviewQueueFilter()` on `MainWorkspace`.

> **Why `Esc` is on the field and not in `unwindOne()`.** The scene-level Escape branch in `DrydockApplication` (~line 760) gates Review's entire unwind — `unwindReviewOverlay`, `hideReview`, `showPicker` — behind `!inTextInput`, where `inTextInput` is `scene().getFocusOwner() instanceof TextInputControl`. The filter field is one, so with focus in it `unwindOne()` is never reached and a step added there would be dead code. That branch also never calls `event.consume()`, so Escape still propagates to the focused node. **Do not touch the `!inTextInput` gate** — it is what makes Escape cancel the inline tab rename instead of navigating.

> **Why `⌘F` needs the collapse state.** A scene filter runs before a node filter, so `⌘F` cannot be bound from Review's own table. And the rail is collapsed in ordinary situations — after `q`, in focus mode, and in **any window under 1180px** (`ReviewDestinationView:791` is `queue.setCollapsed(queueCollapsedByUser || width < QUEUE_COLLAPSE_WIDTH)`). If the new branch fired there it would focus a hidden field and stop calling `sidebar.focusFilter()`, turning a working key into a dead one. Hence `isReviewQueueFilterable()`. Neither key expands the rail: that `||` means clearing `queueCollapsedByUser` cannot override a responsive collapse.

- [ ] **Step 1: Write the failing tests**

Append to `ReviewDestinationViewTest.java`:

```java
    @Test
    void slashFocusesTheFilterAndTypingIntoItDoesNotFireTheKeyTable() {
        seedMixedQueue();
        Optional<String> before = view.diagSelectedScopeId();

        type(KeyCode.SLASH);

        Node field = lookup(".review-queue-filter").query();
        assertTrue(field.isFocused(), "/ must focus the quick-search field");
        assertEquals("", queryText(), "/ must not type itself into the field it just focused");

        // j into the field is a j, not a selection move: Review's key table
        // returns early while a text input has focus. "j" matches nothing, so
        // no row renders -- read the selection through diagSelectedScopeId,
        // which sees the full list; selectedTitle() only sees rendered rows.
        press(KeyCode.J).release(KeyCode.J);
        assertEquals("j", queryText(), "j must land in the field as a character");
        assertEquals(before, view.diagSelectedScopeId(),
                "typing must never move the centre panel");
    }

    @Test
    void enterInTheFieldSelectsTheFirstMatch() {
        seedMixedQueue();
        assertEquals("feat/a", selectedTitle());

        typeQuery("agent");
        interact(() -> lookup(".review-queue-filter").query().requestFocus());
        press(KeyCode.ENTER).release(KeyCode.ENTER);

        assertEquals("agent/issue-919", selectedTitle());
    }

    @Test
    void escInTheFieldClearsTheQueryAndRestoresEveryRow() {
        seedMixedQueue();
        type(KeyCode.SLASH);
        typeQuery("919");
        assertEquals(1, renderedTitles().size());

        interact(() -> lookup(".review-queue-filter").query().requestFocus());
        press(KeyCode.ESCAPE).release(KeyCode.ESCAPE);

        assertEquals("", queryText());
        assertEquals(3, renderedTitles().size());
        assertFalse(lookup(".review-queue-filter").query().isFocused(),
                "Esc returns focus to the rail so the key table works again");
    }

    /**
     * The field does not exist while the rail is collapsed, so / must be
     * inert there -- and ⌘F must keep routing to the sidebar rather than
     * focusing something invisible.
     */
    @Test
    void withTheRailCollapsedSlashIsInertAndTheFilterIsUnavailable() {
        seedMixedQueue();
        type(KeyCode.Q);
        settledRailWidth();

        type(KeyCode.SLASH);

        assertFalse(lookup(".review-queue-filter").query().isFocused());
        assertFalse(view.queueFilterAvailable(),
                "⌘F must fall back to the sidebar while the rail is collapsed");
    }
```

**If the headless robot does not synthesise typed characters:** `assertEquals("j", queryText(), …)` depends on Monocle delivering a `KEY_TYPED` for `press(KeyCode.J)`. It should. If it turns out not to, that assertion is the only one to drop — keep the two that carry the real invariants (the field is focused, and `diagSelectedScopeId()` did not move), and note the omission in the commit message. Do **not** "fix" it by typing through `typeQuery`, which sets text directly and would prove nothing about where the keystroke went.

**Note on `⌘F`:** its routing lives in `DrydockApplication`'s scene filter, which this harness does not build — `ReviewDestinationViewTest` mounts the view standalone. `queueFilterAvailable()` is the seam that carries the decision, so asserting on it is the honest test. The one-line branch in `DrydockApplication` is verified by hand in Step 8.

- [ ] **Step 2: Run them and confirm they fail**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDestinationViewTest'
```

Expected: the class does not compile, because `queueFilterAvailable()` does not exist yet. Once Step 3 and Step 4 land it compiles, and the four tests are red for their own reasons: `SLASH` is not in the key table, the field has no key handler, and `/` types itself into the field it focuses.

- [ ] **Step 3: Add `focusFilter` and the field's key handler**

In `ReviewQueueRail.java`, add after `setCollapsed`:

```java
    /**
     * Focuses the quick-search field and selects what is in it ({@code ⌘F}).
     * A no-op while collapsed: the field is hidden and unmanaged there, so it
     * cannot take focus, and neither key expands the rail -- {@code q} owns
     * this rail's width.
     */
    void focusFilter() {
        focusFilter(false);
    }

    /**
     * As {@link #focusFilter()}, but discards the one typed slash still in
     * flight -- so {@code /} opens the field rather than pre-loading it with
     * a {@code "/"}.
     */
    void focusFilter(boolean swallowTypedSlash) {
        if (collapsed) {
            return;
        }
        swallowNextTypedSlash = swallowTypedSlash;
        filterField.requestFocus();
        filterField.selectAll();
    }
```

And in the constructor, after the `textProperty()` listener:

```java
        filterField.setOnKeyPressed(this::onFilterKeyPressed);
        filterField.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (swallowNextTypedSlash) {
                swallowNextTypedSlash = false;
                if ("/".equals(event.getCharacter())) {
                    event.consume();
                }
            }
        });
```

And declare the flag next to `filterField`:

```java
    /**
     * Set when {@code /} focuses this field. Consuming that key's {@code
     * KEY_PRESSED} in Review's table does not stop the separate {@code
     * KEY_TYPED} from arriving, and by the time it does, this field owns
     * focus -- so the key that opens the filter would otherwise type itself
     * into it. Never set for {@code ⌘F}: a shortcut-modified press produces
     * no character, and swallowing there would eat a real keystroke.
     */
    private boolean swallowNextTypedSlash;
```

Then add the handler next to `focusFilter`:

```java
    /**
     * The field's own {@code Enter} and {@code Esc}.
     *
     * <p>Esc has to live here rather than in Review's unwind: the
     * scene-level Escape branch gates that unwind behind "no text input has
     * focus", so it never reaches Review while this field is focused -- but
     * it does not consume the event either, so the key arrives at the
     * focused node. A blank query is not ours to swallow; leaving it
     * unconsumed lets the ordinary unwind resume once focus is off the
     * field.</p>
     */
    private void onFilterKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER -> {
                // One selection, one git diff -- Enter is what commits the
                // filter, never a keystroke.
                visibleItems().stream().findFirst()
                        .ifPresent(item -> select(item.scope().id()));
                event.consume();
            }
            case ESCAPE -> {
                if (!query().isEmpty()) {
                    filterField.clear();
                    returnFocusToRail();
                    event.consume();
                }
            }
            default -> { }
        }
    }

    /** Moves focus off the field so Review's key table stops returning early. */
    private void returnFocusToRail() {
        Button selected = buttonsByScopeId.get(selectedScopeId);
        if (selected != null) {
            selected.requestFocus();
        } else {
            scroll.requestFocus();
        }
    }
```

Add the import:

```java
import javafx.scene.input.KeyEvent;
```

- [ ] **Step 4: Add `/` to Review's key table and the two accessors**

In `ReviewDestinationView.java`, add one arm to the `switch` in `onKeyPressed`, next to `case Q`:

```java
            case SLASH -> { queue.focusFilter(true); yield true; }
```

The `true` is what stops the slash typing itself into the field it just opened. `focusQueueFilter()` (⌘F) keeps calling the no-arg `queue.focusFilter()`.

The method's existing early return (`event.getTarget() instanceof TextInputControl`) already means a slash typed *into* the field is a slash, not a re-focus.

Then add next to `selectScope`:

```java
    /**
     * Whether {@code ⌘F} should reach the queue's quick-search field. False
     * while the rail is collapsed, where the field does not exist -- the
     * scene filter then keeps its existing route to the sidebar's filter
     * rather than focusing something invisible.
     */
    public boolean queueFilterAvailable() {
        return !queue.collapsed();
    }

    /** Focuses the queue's quick-search field ({@code ⌘F}). */
    public void focusQueueFilter() {
        queue.focusFilter();
    }
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
./gradlew :app:test --tests 'app.drydock.ui.review.ReviewDestinationViewTest'
```

Expected: PASS.

- [ ] **Step 6: Plumb the state out to the scene filter**

In `MainWorkspace.java`, next to `isReviewShowing()` (line 722):

```java
    /** Whether {@code ⌘F} belongs to the Review queue's filter rather than the sidebar's. */
    public boolean isReviewQueueFilterable() {
        return reviewShowing && reviewDestination.queueFilterAvailable();
    }

    /** Focuses the Review queue's quick-search field ({@code ⌘F}). */
    public void focusReviewQueueFilter() {
        reviewDestination.focusQueueFilter();
    }
```

In `DrydockApplication.java`, replace the `⌘F` branch (~line 804):

```java
            } else if (cmd && event.getCode() == KeyCode.F) {
                // Review's queue owns ⌘F while it is showing and its rail is
                // expanded; otherwise the key keeps its sidebar meaning
                // rather than becoming a dead key.
                if (mainWorkspace.isReviewQueueFilterable()) {
                    mainWorkspace.focusReviewQueueFilter();
                } else {
                    sidebar.focusFilter();
                }
                event.consume();
```

In `ShortcutsOverlay.java`, add to the `IN REVIEW` section immediately after `{"Collapse the queue", "q"}`:

```java
                    {"Filter the queue", "/"},
```

- [ ] **Step 7: Run the whole suite**

```bash
./gradlew :app:test
```

Expected: PASS.

- [ ] **Step 8: Verify by hand in the running app**

```bash
./gradlew run
```

Check, in order:
1. Open Review (`⌘4`). The field sits under the `QUEUE` header. Type — rows narrow, the footer reads `N of M items`.
2. Type something that matches nothing: the `No queue item matches "…"` row appears, not an empty rail.
3. `Esc` in the field: query cleared, every row back, and `j` then moves the selection (proving focus left the field).
4. `/` focuses the field and the field is **empty** — no stray `/` in it. Then `j` typed into it inserts a `j` instead of moving the selection.
5. `⌘F` with Review showing focuses the queue filter; `⌘F` on a session tab still focuses the sidebar filter.
6. Press `q` to collapse: every row is back at 44px, no field. `/` and `⌘F` there — `⌘F` must focus the **sidebar** filter. Press `q` again: the query is back and applied.
7. Drag the window under 1180px with a query typed: the rail auto-collapses and shows every row.
8. `?` shows `Filter the queue · /` in the IN REVIEW section.

If any step misbehaves, that is a bug in this task, not a spec question — fix it before committing.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/app/drydock/ui/review/ReviewQueueRail.java \
        app/src/main/java/app/drydock/ui/review/ReviewDestinationView.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/main/java/app/drydock/DrydockApplication.java \
        app/src/main/java/app/drydock/ui/ShortcutsOverlay.java \
        app/src/test/java/app/drydock/ui/review/ReviewDestinationViewTest.java
git commit -m "Review queue: / and ⌘F reach the filter, Enter commits it, Esc clears it"
```

---

## Spec coverage

| Spec section | Task |
| --- | --- |
| Placement — field under the header, hidden + unmanaged when collapsed | 2 |
| Placement — collapse suppresses filtering, retains the query | 2 |
| Where the filtering lives — rail owns it, `setItems` keeps the full queue | 2 |
| Matching — group + title + subtitle, case-insensitive, blank matches all | 1 |
| Matching — static package-private `matches` | 1 |
| Rendering — filtered `rebuild()`, group headings fall out, no debounce | 2 |
| Rendering — no-match row | 2 |
| Footer — `N of M items` | 2 |
| Selection — typing never moves the centre panel | 3 (test), 2 (behaviour) |
| Selection — `j`/`k` walk the visible list | 3 |
| Selection — `Enter` selects the first match | 4 |
| Selection — reassembly preserves the query | 3 (test); no code change needed |
| Clearing on targeted navigation — `revealAndSelect` | 3 |
| Keyboard — `/`, `⌘F` + collapsed-rail rule, `Esc` on the field | 4 |
| Keyboard — `ShortcutsOverlay` row | 4 |
| Out of scope — intent rail, persistence, ranking | not implemented, by design |
