# Explorer usability fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Session Explorer behave the way its own spec says it does — a visible collapse control, expansion and scroll that survive a rebuild, a wheel that keeps working over open code, and every bound key advertised.

**Architecture:** Eleven independent defects found in a visual debug pass on 2026-08-06 (ten scene snapshots, fixture repo with a real `master...HEAD` diff). Each is fixed in isolation in the file that owns it; none share code. Every one is covered by a TestFX test in the existing `app/src/test/java/app/drydock/ui/explorer/` harness — `SearchRailViewTest` and `SkimViewTest` already drive real clicks through `org.testfx.framework.junit5.ApplicationTest`, so "the caret is clickable" is an assertion, not a screenshot.

**Tech Stack:** Java 26, JavaFX 26, RichTextFX + Flowless 0.7.4, JUnit 5, TestFX (`ApplicationTest`, `WaitForAsyncUtils`), Gradle.

## Global Constraints

- Never block the JavaFX Application Thread (AGENTS.md, "Blocking work is async"). Config reads on the FX thread go through `UserConfig.loadAsync()`, never `UserConfig.load()`.
- Never inline fully-qualified Java class names; use imports. Sole exception: same-name collisions from different packages (AGENTS.md, "Code placement and hygiene").
- Shared presentation logic (breadcrumbs included) lives in `UiFormats` — no per-view copies (AGENTS.md, "UI lifecycle hygiene").
- Anything advertised in `ShortcutsOverlay` must actually be bound, and vice versa (AGENTS.md, "UI lifecycle hygiene").
- Never start an `Animation.INDEFINITE` transition without a stop path tied to the node's lifecycle.
- Rebuild-the-world is a last resort; keystroke-driven rebuilds stay debounced at the existing 150ms.
- Comment density and tone: match the surrounding files. These classes explain *why*, not *what*, and record rejected alternatives inline. Keep that.
- Run tests targeted, never the whole suite from a subagent: the full run is 14–20 minutes. Use `./gradlew :app:test --tests '<ClassName>'`.
- No new dependencies.

## Out of scope

Recorded so the next reader knows they were seen and left alone, not missed:

- **`SourceOutline` counts fields as members.** In the fixture, `private final List<Order> orders` became a folded "private helper", which is why the group read `private helpers (5)` where only three methods were folded. Fixing it means changing what a member *is*, which moves the minimap ticks and the skim rows together — a change to `SourceOutlineTest`'s contract, not a usability fix.
- **The folded-helper group is labelled with its first member's line number** (`9`) but renders last, so the outline is not in line order. Cosmetic, and it disappears if the point above is ever addressed.
- **The code column has no horizontal scrollbar** — long lines clip at the right edge in both full text and the peek card. Wrapping vs. scrolling in a code viewer is a design decision, not a defect to patch here.
- **Explorer file tabs are not persisted** across restarts, and there is no ⌘W to close one. Both are recorded as deliberate in `docs/superpowers/specs/2026-07-22-explorer-ui-affordances-design.md`.

---

### Task 1: The rail's expand caret becomes visible and clickable

The `▸` toggle on a file row with content matches is a 10px `-drydock-text-faint` glyph on a transparent background. In the debug pass it did not render legibly at all, so the only way to collapse a match group is to hit an invisible ~14px target — every other pixel of the row opens the file. The `RotateTransition` is also dropped: rows are rebuilt on every keystroke, so it animates nodes that are about to be discarded, and rotating a glyph reads worse than swapping it (which is what `SkimView` already does).

**Files:**
- Modify: `app/src/main/resources/app/drydock/ui/app.css:1017-1023`
- Modify: `app/src/main/java/app/drydock/ui/explorer/SearchRail.java:528-533,594-610`
- Test: `app/src/test/java/app/drydock/ui/explorer/SearchRailViewTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `.result-caret` toggles carry the text `▾` when expanded and `▸` when collapsed. Task 2 relies on that text as its observable.

- [ ] **Step 1: Write the failing test**

Add to `SearchRailViewTest`:

```java
@Test
void theMatchGroupCaretIsVisibleAndCollapsesTheGroup() {
    interact(() -> rail.setSearch("lerp"));
    waitForFooter("more file");
    interact(() -> rail.toggleScope());
    settle();

    ToggleButton caret = lookup(".result-caret").queryAll().stream()
            .map(ToggleButton.class::cast)
            .filter(Node::isVisible)
            .findFirst().orElseThrow(() -> new AssertionError("no visible caret on a row with matches"));
    // A target the reader can actually hit: the design's rail rows are
    // 324px wide and every other pixel of the row opens the file.
    assertTrue(caret.getWidth() >= 14 && caret.getHeight() >= 14,
            "caret hit target is " + caret.getWidth() + "x" + caret.getHeight());
    assertEquals("▾", caret.getText(), "expanded groups point down");
    assertFalse(lookup(".result-match-line").queryAll().isEmpty(), "the group starts expanded");

    clickOn(caret);
    settle();
    assertEquals("▸", caret.getText(), "collapsed groups point right");
    // The CONTAINER, not the children: JavaFX visibility is inherited for
    // rendering, so the group's own flags are what the collapse sets, and
    // asserting on each child would force redundant per-child state into
    // the production code just to satisfy the test.
    Node lines = lookup(".result-match-lines").query();
    assertFalse(lines.isVisible(), "…and the match lines are hidden");
    assertFalse(lines.isManaged(), "…and take up no space in the rail");
}
```

Add the imports `javafx.scene.Node` and `javafx.scene.control.ToggleButton` to the test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SearchRailViewTest'`
Expected: FAIL — `assertEquals("▾", caret.getText())` gets `"▸"` (the current code rotates the glyph rather than swapping it).

- [ ] **Step 3: Restyle the caret**

Replace `app.css:1017-1023` with:

```css
.result-caret {
    -fx-background-color: transparent;
    -fx-background-radius: 3px;
    -fx-text-fill: -drydock-text-dim;
    -fx-font-size: 11px;
    -fx-padding: 0;
    -fx-min-width: 16px;
    -fx-min-height: 16px;
    -fx-alignment: center;
    -fx-cursor: hand;
}
.result-caret:hover {
    -fx-background-color: -drydock-active-bg;
    -fx-text-fill: -drydock-text;
}
```

- [ ] **Step 4: Swap the glyph instead of rotating it**

In `SearchRail.buildFileRow`, replace the caret construction at `:528-533`:

```java
        boolean hasChildren = !matches.isEmpty();
        ToggleButton caret = new ToggleButton("▾");
        caret.getStyleClass().add("result-caret");
        caret.setFocusTraversable(false);
        caret.setSelected(true);
        caret.setVisible(hasChildren);
        caret.setManaged(hasChildren);
```

and replace the expansion wiring at `:602-609` with:

```java
            // The glyph is swapped, not rotated: these rows are rebuilt on
            // every keystroke, so a RotateTransition would animate nodes that
            // are already on their way out -- and a rotated glyph sits
            // off-centre in its box. SkimView's rows have always done it this
            // way.
            caret.selectedProperty().addListener((obs, was, expanded) -> {
                caret.setText(expanded ? "▾" : "▸");
                lines.setVisible(expanded);
                lines.setManaged(expanded);
            });
```

Delete the now-unused `RotateTransition rotate = ...` line and the `caret.setRotate(90)` line, and drop the `javafx.animation.RotateTransition` import.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SearchRailViewTest'`
Expected: PASS, all tests in the class.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/resources/app/drydock/ui/app.css \
        app/src/main/java/app/drydock/ui/explorer/SearchRail.java \
        app/src/test/java/app/drydock/ui/explorer/SearchRailViewTest.java
git commit -m "The rail's collapse caret is something you can see and hit"
```

---

### Task 2: The rail remembers what you collapsed and where you had scrolled

`buildFileRow` hardcodes every group expanded and `rebuild()` destroys and recreates all rows. `rebuild()` runs on opening a file (`setOpenFile`), on a findings refresh, on an overlay refresh, on a scope or sort change, and on every debounced keystroke. So collapsing a group and then clicking a result re-expands everything, and the list snaps back to the top each time. This is the single biggest source of the "sections expand at random" impression.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/SearchRail.java:83-100,317-321,454-472,594-610`
- Test: `app/src/test/java/app/drydock/ui/explorer/SearchRailViewTest.java`

**Interfaces:**
- Consumes: the `▾`/`▸` caret text from Task 1.
- Produces: `SearchRail` keeps a `Set<Path> collapsedGroups` keyed by session-relative path, and a `resultsScroll` field. No public API change.

- [ ] **Step 1: Write the failing test**

Add to `SearchRailViewTest`:

```java
@Test
void aCollapsedGroupStaysCollapsedWhenTheRailRebuilds() {
    interact(() -> rail.setSearch("lerp"));
    waitForFooter("more file");
    interact(() -> rail.toggleScope());
    settle();

    ToggleButton caret = lookup(".result-caret").queryAll().stream()
            .map(ToggleButton.class::cast)
            .filter(Node::isVisible)
            .findFirst().orElseThrow();
    clickOn(caret);
    settle();
    assertEquals("▸", caret.getText());

    // Exactly what opening a file from the rail does.
    interact(() -> rail.setOpenFile(Path.of("ui/LayoutMath.java")));
    settle();

    ToggleButton afterRebuild = lookup(".result-caret").queryAll().stream()
            .map(ToggleButton.class::cast)
            .filter(Node::isVisible)
            .findFirst().orElseThrow();
    assertEquals("▸", afterRebuild.getText(), "the group is still collapsed after a rebuild");
    Node lines = lookup(".result-match-lines").query();
    assertFalse(lines.isVisible(), "…and its match lines are still hidden");
    assertFalse(lines.isManaged(), "…and still take up no space");
}

@Test
void theResultListKeepsItsScrollPositionAcrossARebuild() {
    interact(() -> rail.toggleScope());
    settle();
    ScrollPane scroll = (ScrollPane) lookup(".search-results-scroll").query();
    interact(() -> scroll.setVvalue(0.5));
    settle();

    interact(() -> rail.refresh());
    settle();
    assertEquals(0.5, scroll.getVvalue(), 0.05,
            "a refresh must not throw the reader back to the top of the list");
}
```

Add the import `javafx.scene.control.ScrollPane` to the test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SearchRailViewTest'`
Expected: FAIL — the caret reads `▾` after the rebuild, and `getVvalue()` is `0.0`.

- [ ] **Step 3: Promote the ScrollPane to a field and add the collapsed set**

In the field block around `SearchRail.java:83-100`, add:

```java
    private final ScrollPane resultsScroll = new ScrollPane(resultsBox);

    /**
     * Groups the reader has collapsed, by session-relative path. Held here
     * rather than on the row because rebuild() destroys every row -- and it
     * rebuilds on opening a file, on a findings or overlay refresh, and on
     * every debounced keystroke, so row-local state survives almost nothing.
     */
    private final Set<Path> collapsedGroups = new LinkedHashSet<>();
```

In `buildExpandedContent` (`:317-321`), replace the local `ScrollPane scroll = new ScrollPane(resultsBox);` with uses of the field:

```java
        resultsBox.getStyleClass().add("search-results");
        resultsScroll.setFitToWidth(true);
        resultsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        resultsScroll.getStyleClass().add("search-results-scroll");
        VBox.setVgrow(resultsScroll, Priority.ALWAYS);
```

and replace the two later references to `scroll` (in `expandedContent.getChildren().setAll(...)` and the second `VBox.setVgrow(scroll, ...)` at `:347-350`) with `resultsScroll`.

- [ ] **Step 4: Preserve the scroll position across rebuild**

At the top of `rebuild()` (`:454`), capture the position; restore it after the rows are back:

```java
    private void rebuild() {
        // Captured before the children go, restored after they are back: the
        // reader's place in a 200-row list is not something a keystroke or a
        // background findings refresh gets to discard.
        double scrollPosition = resultsScroll.getVvalue();
        List<FileRailModel.Entry> all = entries();
```

and immediately before the `funnelFooter.setText(...)` block at `:498`:

```java
        // Deferred one pulse: the ScrollPane clamps vvalue against a content
        // height that is still zero until the new rows have been laid out.
        Platform.runLater(() -> resultsScroll.setVvalue(scrollPosition));
```

- [ ] **Step 5: Persist the caret state**

In `buildFileRow`, replace the caret's initial state (from Task 1's Step 4):

```java
        boolean hasChildren = !matches.isEmpty();
        boolean expanded = !collapsedGroups.contains(entry.relative());
        ToggleButton caret = new ToggleButton(expanded ? "▾" : "▸");
        caret.getStyleClass().add("result-caret");
        caret.setFocusTraversable(false);
        caret.setSelected(expanded);
        caret.setVisible(hasChildren);
        caret.setManaged(hasChildren);
```

and in the `if (hasChildren)` block, apply the remembered state to the lines at build time and record changes:

```java
            lines.setVisible(expanded);
            lines.setManaged(expanded);
            group.getChildren().add(lines);

            caret.selectedProperty().addListener((obs, was, nowExpanded) -> {
                caret.setText(nowExpanded ? "▾" : "▸");
                lines.setVisible(nowExpanded);
                lines.setManaged(nowExpanded);
                if (nowExpanded) {
                    collapsedGroups.remove(entry.relative());
                } else {
                    collapsedGroups.add(entry.relative());
                }
            });
```

(The `lines.setVisible`/`setManaged` pair must be set at build time as well as in the listener — a listener alone never fires for a group that was already collapsed.)

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SearchRailViewTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/SearchRail.java \
        app/src/test/java/app/drydock/ui/explorer/SearchRailViewTest.java
git commit -m "A rail rebuild stops discarding what you collapsed and where you were"
```

---

### Task 3: The wheel keeps working over an expanded skim body

Flowless's `VirtualFlow` registers a handler for `ScrollEvent.ANY` that scrolls by the delta and then consumes unconditionally (verified in `flowless-0.7.4.jar` bytecode). Each expanded skim member is a `VirtualizedScrollPane<CodeArea>` sized exactly to its content, so it *cannot* scroll but still eats every wheel event — the outer `SkimView` scroller never sees them. Scrolling works over a signature row and dies over open code. `SkimView.rebuild()` also clears every row without restoring `vvalue`, so expanding a member jumps the view.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/SkimView.java:138-152,290-299`
- Test: `app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing public. `SkimView` gains a private `redispatchWheel(ScrollEvent)`.

- [ ] **Step 1: Write the failing test**

Add to `SkimViewTest`:

```java
@Test
void theWheelOverAnExpandedBodyScrollsTheSkimViewNotNothing() {
    show(Set.of(3, 4), Map.of());
    // The changed member is open by default, so its body is on screen.
    Node body = lookup(".skim-code").query();
    interact(() -> skim.setVvalue(0.0));
    settle();

    interact(() -> body.fireEvent(new ScrollEvent(ScrollEvent.SCROLL,
            0, 0, 0, 0, false, false, false, false, false, false,
            0, -120, 0, -120, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
            ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null)));
    settle();

    assertTrue(skim.getVvalue() > 0.0,
            "the wheel over open code must move the skim scroller, not be swallowed");
}

@Test
void expandingAMemberKeepsTheReadersPlace() {
    show(Set.of(3, 4), Map.of());
    interact(() -> skim.setVvalue(0.4));
    settle();
    double before = skim.getVvalue();

    Button helpers = lookup(".skim-header").queryAll().stream()
            .map(Button.class::cast)
            .filter(row -> row.getGraphic() instanceof HBox box && box.getChildren().stream()
                    .anyMatch(node -> node instanceof Label label
                            && label.getText().startsWith("private helpers")))
            .findFirst().orElseThrow();
    clickOn(helpers);
    settle();

    assertEquals(before, skim.getVvalue(), 0.05,
            "expanding a group must not throw the reader to the top");
}
```

Add the imports `javafx.scene.Node` and `javafx.scene.input.ScrollEvent` to the test file. `settle()` is the existing `WaitForAsyncUtils.waitForFxEvents()` helper — add one to `SkimViewTest` if it does not have it:

```java
    private void settle() {
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: FAIL — `skim.getVvalue()` stays `0.0` (the body consumed the event), and the second test's vvalue is `0.0` after the rebuild.

- [ ] **Step 3: Re-dispatch the wheel from the body to the skim scroller**

In `SkimView.buildBody`, after the `VirtualizedScrollPane` is constructed and sized (`:290-299`), add:

```java
        // Flowless's VirtualFlow handles ScrollEvent.ANY and consumes it
        // unconditionally -- even here, where the body is sized to its
        // content and has nothing to scroll. Without this filter the wheel
        // dies wherever the cursor sits over open code. A filter, not a
        // handler: it has to win before the event reaches the flow.
        scroll.addEventFilter(ScrollEvent.SCROLL, this::redispatchWheel);
```

and add the method next to `topLine()`:

```java
    /**
     * Applies a wheel event that landed on a member body to this scroller.
     * Deltas are in pixels, so they are converted against the same
     * scrollable span {@link #topLine()} uses.
     */
    private void redispatchWheel(ScrollEvent event) {
        event.consume();
        double span = Math.max(1, rows.getHeight() - getViewportBounds().getHeight());
        setVvalue(Math.max(0, Math.min(1, getVvalue() - event.getDeltaY() / span)));
    }
```

Add the import `javafx.scene.input.ScrollEvent`.

- [ ] **Step 4: Keep the scroll position across a rebuild**

`revealLine` calls `rebuild()` and *then* forces a layout pass and sets `vvalue` itself, so a deferred restore would land after it and undo the reveal. One flag, captured synchronously, keeps the two apart. Add the field:

```java
    /** Set while revealLine is driving the scroll, so rebuild's restore does not undo it. */
    private boolean revealing;
```

and replace `SkimView.rebuild()` (`:138-152`) with:

```java
    private void rebuild() {
        // Expanding one member must not move every other one under the
        // reader. Captured, not read in the lambda: `revealing` is already
        // back to false by the time a deferred read would run.
        double scrollPosition = getVvalue();
        boolean restoreScroll = !revealing;
        rows.getChildren().clear();
        List<SourceOutline.Member> folded = new ArrayList<>();
        for (SourceOutline.Member member : outline.members()) {
            boolean untouchedHelper = member.privateHelper()
                    && !member.isChanged(changed)
                    && findingIn(member) == null;
            if (untouchedHelper) {
                folded.add(member);
                continue;
            }
            rows.getChildren().add(buildRow(member));
        }
        if (!folded.isEmpty()) {
            rows.getChildren().add(buildHelperGroup(folded));
        }
        // Deferred: the ScrollPane clamps vvalue against a content height
        // that is still zero until the new rows have been laid out.
        if (restoreScroll) {
            Platform.runLater(() -> setVvalue(scrollPosition));
        }
    }
```

Add the import `javafx.application.Platform`.

- [ ] **Step 5: Let `revealLine` claim the scroll**

Wrap the rebuild inside `revealLine`'s lambda so it is exempt from the restore. Only the three lines at the top of the lambda change; the rest of the method (the forced layout pass, the node search, the `setVvalue`) is untouched:

```java
        outline.memberAt(line).ifPresent(member -> {
            expansion.put(member.startLine(), true);
            onMemberRead.accept(member.startLine());
            revealing = true;
            try {
                rebuild();
            } finally {
                revealing = false;
            }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: PASS, including the pre-existing reveal tests in the class.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/SkimView.java \
        app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java
git commit -m "The wheel survives open code in skim mode, and so does your place in the file"
```

---

### Task 4: An expanded skim member stops repeating its own signature

The header row renders `23 ▾ public long total() { · 10 lines · changed` and the body then opens with line 23, `public long total() {`. Every expanded member wastes a line on a duplicate and reads like a rendering fault.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/SkimView.java:252-260`
- Test: `app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `buildBody` starts the body at `member.startLine() + 1` when line `startLine` is exactly the signature and the member has more than one line. `onBodyBuilt.accept(area, from)` still receives the body's real first line, so the peek gesture's line arithmetic is unchanged.

- [ ] **Step 1: Write the failing test**

Add to `SkimViewTest`:

```java
@Test
void anExpandedMemberDoesNotRepeatItsSignature() {
    show(Set.of(3, 4), Map.of());
    CodeArea body = (CodeArea) lookup(".skim-code").query();
    assertFalse(body.getText().startsWith("int width()"),
            "the header already says the signature; the body starts at the line after it: "
                    + body.getText());
    assertTrue(body.getText().contains("return clamp(raw);"), body.getText());
}

@Test
void aSingleLineMemberStillShowsItsOnlyLine() {
    String source = """
            interface Clock {
                Instant now();
            }
            """;
    interact(() -> skim.show(Path.of("Clock.java"), source,
            SourceOutline.parse(source), Set.of(2), Map.of()));
    settle();
    CodeArea body = (CodeArea) lookup(".skim-code").query();
    assertFalse(body.getText().isBlank(), "a one-line member must not fold away to nothing");
}
```

Add the import `org.fxmisc.richtext.CodeArea` to the test file.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: FAIL on the first test — the body text starts with `int width()`.

- [ ] **Step 3: Skip the signature line**

In `SkimView.buildBody`, replace the first two lines:

```java
        int from = Math.max(1, member.startLine());
        int to = Math.min(lines.size(), member.endLine());
        // The header row already shows the signature. Repeating it as the
        // body's first line costs a row per open member and reads like a
        // rendering fault -- so it is dropped, but only when line `from`
        // really is the signature (a wrapped or annotated declaration is
        // not) and only when something is left underneath it.
        if (to > from && from <= lines.size()
                && lines.get(from - 1).strip().equals(member.signature())) {
            from++;
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/SkimView.java \
        app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java
git commit -m "An open skim member says its signature once"
```

---

### Task 5: A search hit inside a folded private helper can actually be reached

`revealLine` records `expansion.put(startLine, true)`, but `rebuild()`'s folding test only looks at `privateHelper && !changed && no finding` — it never consults `expansion`. The member stays inside the `private helpers (N)` group, the scroll loop finds no node carrying its start line, and the click produces nothing at all. Reproduced with a match at line 63 inside `auditSettlement`: the rail offers the hit, the viewer cannot show it.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/SkimView.java:138-152`
- Test: `app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java`

**Interfaces:**
- Consumes: `rebuild()` as rewritten in Task 3 Step 4.
- Produces: nothing public.

- [ ] **Step 1: Write the failing test**

Add to `SkimViewTest`:

```java
@Test
void revealingALineInsideAFoldedHelperOpensIt() {
    show(Set.of(), Map.of());
    assertTrue(lookup(".skim-group-signature").queryAll().stream()
                    .anyMatch(node -> ((Label) node).getText().startsWith("private helpers")),
            "the untouched private helpers start folded into their group");

    // snapToGuide's body -- inside the folded group.
    int lineInsideAHelper = SOURCE.lines().toList().indexOf("    private void snapToGuide() {") + 2;
    interact(() -> skim.revealLine(lineInsideAHelper));
    settle();

    assertTrue(lookup(".skim-signature-open").queryAll().stream()
                    .anyMatch(node -> ((Label) node).getText().contains("snapToGuide")),
            "…and revealing a line inside one pulls it out of the group, open");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: FAIL — no open `snapToGuide` row; it is still inside `private helpers (2)`.

- [ ] **Step 3: Make the fold test honour an explicit expansion**

In `rebuild()`, extend the `untouchedHelper` condition:

```java
            // An explicit expansion beats the fold: revealLine puts one here
            // when a search hit or a minimap click lands inside an untouched
            // helper, and leaving it folded would make that click do nothing
            // at all.
            boolean untouchedHelper = member.privateHelper()
                    && !member.isChanged(changed)
                    && findingIn(member) == null
                    && !Boolean.TRUE.equals(expansion.get(member.startLine()));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/SkimView.java \
        app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java
git commit -m "A hit inside a folded helper is somewhere skim mode can take you"
```

---

### Task 6: Skim mode opens at the top of the file, or at the line you asked for

Observed: opening `OrderService.java` put `total()` (line 23) at the top of the viewport with the constructor and `place()` above it and nothing saying so; the same file after a trail-back showed all six members from the top. `openFile` calls `setSkim(tab, true)`, which anchors on `currentLineOf(tab)` — a value read from a `CodeArea` that has not been laid out yet.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileViewer.java:1505-1516`
- Modify: `app/src/main/java/app/drydock/ui/explorer/SkimView.java` (add `scrollToTop`)
- Test: `app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `void SkimView.scrollToTop()` — package-visible, sets `vvalue` to 0 after a forced layout pass.

- [ ] **Step 1: Write the failing test**

Add to `SkimViewTest`:

```java
@Test
void scrollToTopPutsTheFirstMemberAtTheTop() {
    show(Set.of(3, 4), Map.of());
    interact(() -> skim.setVvalue(0.8));
    settle();
    interact(() -> skim.scrollToTop());
    settle();
    assertEquals(0.0, skim.getVvalue(), 0.001);
    assertEquals(SOURCE.lines().toList().indexOf("    int width() {") + 1, skim.topLine(),
            "the first member is what the reader sees first");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: FAIL — `scrollToTop()` does not exist (compile error).

- [ ] **Step 3: Add `scrollToTop`**

In `SkimView`, next to `revealLine`:

```java
    /**
     * Puts the top of the file at the top of the viewport. Called when a
     * file opens in skim mode with no line to jump to: {@code setSkim}'s
     * anchor is read from a CodeArea that has not been laid out yet, and an
     * arbitrary anchor leaves members above the viewport with nothing
     * saying they are there.
     */
    void scrollToTop() {
        applyCss();
        layout();
        setVvalue(0);
    }
```

- [ ] **Step 4: Anchor the open**

In `FileViewer`'s open path (`:1505-1516`), replace:

```java
                skimView.show(file, text, outline, changedLinesFor(tab), findingLabelsFor(tab));
                // Default skim when the file is in the current review scope
                // (delta part 2): a changed file is opened to be read for its
                // change, and its shape is the fastest way in. Everything
                // else opens as text, because that is what "open a file"
                // means everywhere else in the app.
                if (!changedLinesFor(tab).isEmpty()) {
                    setSkim(tab, true);
                }
                refreshMinimap(tab);
                jumpToLine.ifPresent(line -> scrollTo(tab, line));
```

with:

```java
                skimView.show(file, text, outline, changedLinesFor(tab), findingLabelsFor(tab));
                // Default skim when the file is in the current review scope
                // (delta part 2): a changed file is opened to be read for its
                // change, and its shape is the fastest way in. Everything
                // else opens as text, because that is what "open a file"
                // means everywhere else in the app.
                if (!changedLinesFor(tab).isEmpty() && skimDefault.getAsBoolean()) {
                    setSkim(tab, true);
                    // setSkim anchors on currentLineOf(tab), which reads a
                    // CodeArea that has not been laid out yet -- so a fresh
                    // open landed mid-file with members above the viewport
                    // and nothing saying so. An open has one honest anchor:
                    // the line that was asked for, or the top.
                    if (jumpToLine.isEmpty()) {
                        skimView.scrollToTop();
                    }
                }
                refreshMinimap(tab);
                jumpToLine.ifPresent(line -> scrollTo(tab, line));
```

`skimDefault` is introduced in Task 7. Until then, add the field and its default so this task compiles and can be reviewed on its own:

```java
    /** Whether a changed file opens folded; see the Explorer setting (Task 7). */
    private BooleanSupplier skimDefault = () -> true;
```

with the import `java.util.function.BooleanSupplier`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SkimViewTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/SkimView.java \
        app/src/main/java/app/drydock/ui/explorer/FileViewer.java \
        app/src/test/java/app/drydock/ui/explorer/SkimViewTest.java
git commit -m "A file that opens in skim mode opens at its top, not somewhere in its middle"
```

---

### Task 7: A setting for whether changed files open folded

Skim-by-default is the delta's design and it stays the default. It is also the single behaviour most likely to read as "the Explorer is doing something I did not ask for", so it gets a switch. It goes in `UserConfig` (`~/.drydock/config.json`), next to `worktreesDirectory` — that file is the hand-editable user-preference home; `ApplicationState` is the app's own state and adding a cosmetic key there means touching the codec and its schema tests for no gain.

**Watch out:** `DrydockApplication`'s `saveWorktreesDirectory` currently writes `new UserConfig(directory)`. With a second component that call would silently reset the new preference on every worktrees-directory edit. It must read-modify-write.

**Files:**
- Modify: `app/src/main/java/app/drydock/config/UserConfig.java:40,45-47,101-104,142-143`
- Modify: `app/src/main/java/app/drydock/ui/SettingsModal.java:41-58,104-110`
- Modify: `app/src/main/java/app/drydock/DrydockApplication.java:322-328`
- Modify: `app/src/main/java/app/drydock/ui/MainWorkspace.java:2822-2840`
- Modify: `app/src/main/java/app/drydock/ui/explorer/SessionExplorerView.java`
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileViewer.java`
- Test: `app/src/test/java/app/drydock/config/UserConfigTest.java` (existing; check the exact path with `ls app/src/test/java/app/drydock/config/`)
- Test: `app/src/test/java/app/drydock/ui/SettingsModalTest.java`

**Interfaces:**
- Consumes: `FileViewer.skimDefault` from Task 6.
- Produces:
  - `UserConfig(Optional<Path> worktreesDirectory, boolean openChangedFilesInSkim)`; `UserConfig.empty()` returns `openChangedFilesInSkim = true`.
  - `SettingsModal.Settings` gains `CompletableFuture<Boolean> loadOpenChangedFilesInSkim()` and `CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value)`.
  - `SessionExplorerView.setSkimDefault(BooleanSupplier)` → delegates to `FileViewer.setSkimDefault(BooleanSupplier)`.

- [ ] **Step 1: Write the failing config test**

Add to the `UserConfig` test class:

```java
@Test
void skimDefaultsOnAndSurvivesAWorktreesDirectoryEdit(@TempDir Path dir) throws Exception {
    Path configFile = dir.resolve("config.json");
    assertTrue(UserConfig.load(configFile).openChangedFilesInSkim(),
            "a missing config still opens changed files folded — that is the delta's default");

    UserConfig.save(new UserConfig(Optional.empty(), false), configFile);
    assertFalse(UserConfig.load(configFile).openChangedFilesInSkim());

    // The read-modify-write the settings modal must do.
    UserConfig existing = UserConfig.load(configFile);
    UserConfig.save(new UserConfig(Optional.of(dir), existing.openChangedFilesInSkim()), configFile);
    UserConfig reloaded = UserConfig.load(configFile);
    assertEquals(Optional.of(dir), reloaded.worktreesDirectory());
    assertFalse(reloaded.openChangedFilesInSkim(), "…and the skim preference is still off");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.config.UserConfigTest'`
Expected: FAIL to compile — `openChangedFilesInSkim()` does not exist.

- [ ] **Step 3: Add the config field**

In `UserConfig.java`:

```java
public record UserConfig(Optional<Path> worktreesDirectory, boolean openChangedFilesInSkim) {

    public static UserConfig empty() {
        // Skim-by-default is the Explorer delta's design (part 2); the
        // setting exists to turn it off, not to opt into it.
        return new UserConfig(Optional.empty(), true);
    }
```

in `load(Path)`, after the `worktreesDirectory` parse:

```java
            boolean openChangedFilesInSkim = !(root.get("openChangedFilesInSkim") instanceof JsonBoolean b)
                    || b.value();
            return new UserConfig(worktreesDirectory, openChangedFilesInSkim);
```

and in `save`, next to the existing `root.members().remove("worktreesDirectory")`:

```java
        root.members().remove("openChangedFilesInSkim");
        root.put("openChangedFilesInSkim", new JsonBoolean(config.openChangedFilesInSkim()));
```

`JsonBoolean` is `app.drydock.state.json.JsonValue.JsonBoolean` — add the import alongside the file's existing `JsonString` one. Note the default is written explicitly rather than omitted when true: this file is hand-editable, and a key that only appears when you turn something off is a key nobody discovers.

- [ ] **Step 4: Run the config test**

Run: `./gradlew :app:test --tests 'app.drydock.config.UserConfigTest'`
Expected: PASS.

- [ ] **Step 5: Fix the read-modify-write in the application wiring**

In `DrydockApplication`, replace the `saveWorktreesDirectory` override and add the two new ones:

```java
                @Override
                public CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory) {
                    // Read-modify-write: constructing a bare UserConfig here
                    // would reset every other preference in the file on each
                    // worktrees-directory edit.
                    return UserConfig.loadAsync().thenCompose(existing ->
                            UserConfig.saveAsync(new UserConfig(directory, existing.openChangedFilesInSkim())));
                }

                @Override
                public CompletableFuture<Boolean> loadOpenChangedFilesInSkim() {
                    return UserConfig.loadAsync().thenApply(UserConfig::openChangedFilesInSkim);
                }

                @Override
                public CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value) {
                    return UserConfig.loadAsync().thenCompose(existing ->
                            UserConfig.saveAsync(new UserConfig(existing.worktreesDirectory(), value)));
                }
```

- [ ] **Step 6: Add the settings row**

In `SettingsModal.Settings`, add:

```java
        /** Whether a file in the current change opens folded to its signatures (Explorer delta, part 2). */
        CompletableFuture<Boolean> loadOpenChangedFilesInSkim();

        CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value);
```

and in the constructor's `getChildren().addAll(...)`, after `worktreesRow(settings)`:

```java
                sectionTitle("Explorer"),
                skimRow(settings),
```

with:

```java
    /**
     * Disabled until its value arrives, like every other async-backed row
     * here: a checkbox that shows a default it has not read yet would let
     * one click write that default back over the user's real preference.
     */
    private static Region skimRow(Settings settings) {
        CheckBox box = new CheckBox("Open changed files folded to their signatures");
        box.getStyleClass().add("settings-check");
        box.setDisable(true);
        Label hint = new Label("Skim mode. Press z in the Explorer to switch either way.");
        hint.getStyleClass().add("settings-check-hint");
        // The listener is attached only once the stored value has landed:
        // wiring it before would make the very act of showing the loaded
        // value fire a save of the value we just read.
        settings.loadOpenChangedFilesInSkim().whenComplete((value, failure) -> Platform.runLater(() -> {
            box.setSelected(failure == null ? value : true);
            box.setDisable(false);
            box.selectedProperty().addListener((obs, was, is) -> settings.saveOpenChangedFilesInSkim(is));
        }));
        return new VBox(4, box, hint);
    }
```

Add the import `javafx.scene.control.CheckBox`.

A stock `CheckBox` renders against modena's light defaults inside the dark modal — the same trap `worktreesRow` documents. Add to `app.css`, immediately after the `.settings-radio` block (~`:2130`):

```css
/* Same reason as .settings-radio above: a stock CheckBox caption is
 * near-black on the dark theme. Box styling follows .result-check, the
 * established precedent for a checkbox in this app. */
.settings-check {
    -fx-text-fill: -drydock-text;
}
.settings-check .box {
    -fx-background-color: -drydock-input-bg;
    -fx-background-radius: 4px;
    -fx-border-color: -drydock-border-strong;
    -fx-border-radius: 4px;
}
.settings-check:selected .box {
    -fx-background-color: -drydock-accent;
    -fx-border-color: -drydock-accent;
}
.settings-check:selected .mark {
    -fx-background-color: white;
}
/* Not .settings-hint: that one is indented 132px to clear the caption
 * column of the labelled rows, and this row has no caption column. */
.settings-check-hint {
    -fx-font-size: 11px;
    -fx-text-fill: -drydock-text-faint;
    -fx-padding: 0 0 0 22px;
}
```

- [ ] **Step 7: Wire it to the Explorer**

In `FileViewer`, replace the Task 6 placeholder field with a setter:

```java
    /** Whether a changed file opens folded; the Settings "Explorer" checkbox. */
    private BooleanSupplier skimDefault = () -> true;

    /** Wired by MainWorkspace from the user's config; read at every file open. */
    void setSkimDefault(BooleanSupplier supplier) {
        this.skimDefault = supplier == null ? () -> true : supplier;
    }
```

In `SessionExplorerView`:

```java
    /**
     * Whether a file in the current change opens folded. Read per open, not
     * captured, so a change in Settings takes effect on the next file rather
     * than the next session.
     */
    public void setSkimDefault(BooleanSupplier skimDefault) {
        viewer.setSkimDefault(skimDefault);
    }
```

In `MainWorkspace`'s explorer factory (`:2824` onward), after `explorer.setFindingsProvider(...)`:

```java
                // Read from a cached value, never UserConfig.load(): this
                // runs on the FX thread on every file open (AGENTS.md,
                // "Blocking work is async").
                explorer.setSkimDefault(() -> skimDefaultCache.get());
```

and add to `MainWorkspace`'s fields:

```java
    /**
     * The Explorer's skim-by-default preference, refreshed off-thread and
     * read synchronously by the Explorer on every file open. Seeded with the
     * delta's default so the very first open before the load lands behaves
     * as designed.
     */
    private final AtomicBoolean skimDefaultCache = new AtomicBoolean(true);
```

with an off-thread refresh in the constructor and after the settings modal closes:

```java
        UserConfig.loadAsync().thenAccept(config ->
                skimDefaultCache.set(config.openChangedFilesInSkim()));
```

Wire the second refresh wherever `DrydockApplication` already closes the settings modal (the `appShell.modalLayer().show(settingsModal, settingsModal::flushPendingEdit)` call site) — add a `mainWorkspace.refreshExplorerPreferences()` to that `onClosed` callback, chained after `flushPendingEdit`, and expose:

```java
    /** Re-reads the Explorer's user preferences after the settings modal closes. */
    public void refreshExplorerPreferences() {
        UserConfig.loadAsync().thenAccept(config ->
                skimDefaultCache.set(config.openChangedFilesInSkim()));
    }
```

- [ ] **Step 8: Write the settings-modal test**

`SettingsModalTest` is a plain JUnit class testing `format(double)` with no JavaFX toolkit, so the row test goes in a new TestFX class rather than being bolted onto it. Create `app/src/test/java/app/drydock/ui/SettingsModalSkimRowTest.java`:

```java
package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Explorer row of the settings modal: it must show the STORED value,
 * not the default, and it must not write that value straight back the
 * moment it displays it.
 */
class SettingsModalSkimRowTest extends ApplicationTest {

    private final AtomicReference<Boolean> saved = new AtomicReference<>();
    private SettingsModal modal;

    @Override
    public void start(Stage stage) {
        modal = new SettingsModal(new SettingsModal.Settings() {
            @Override
            public UiTheme theme() {
                return UiTheme.DARK;
            }

            @Override
            public void setTheme(UiTheme theme) {
            }

            @Override
            public SizeSetting interfaceSize() {
                return new SizeSetting(() -> 13.0, size -> { }, size -> { });
            }

            @Override
            public SizeSetting terminalSize() {
                return new SizeSetting(() -> 13.0, size -> { }, size -> { });
            }

            @Override
            public CompletableFuture<Optional<Path>> loadWorktreesDirectory() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Void> saveWorktreesDirectory(Optional<Path> directory) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Boolean> loadOpenChangedFilesInSkim() {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletableFuture<Void> saveOpenChangedFilesInSkim(boolean value) {
                saved.set(value);
                return CompletableFuture.completedFuture(null);
            }
        }, () -> { });
        Scene scene = new Scene(new StackPane(modal), 600, 700);
        scene.getStylesheets().addAll(
                SettingsModal.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SettingsModal.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void theSkimCheckboxReadsAndWritesThePreference() {
        WaitForAsyncUtils.waitForFxEvents();
        CheckBox box = (CheckBox) modal.lookup(".settings-check");
        assertFalse(box.isSelected(), "the modal shows the stored preference, not the default");
        assertFalse(box.isDisabled(), "…and is enabled once the value has arrived");
        assertEquals(null, saved.get(), "showing a value is not a reason to write it back");

        interact(() -> box.setSelected(true));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(Boolean.TRUE, saved.get());
    }
}
```

- [ ] **Step 9: Run the three test classes**

Run: `./gradlew :app:test --tests 'app.drydock.config.UserConfigTest' --tests 'app.drydock.ui.SettingsModalTest' --tests 'app.drydock.ui.SettingsModalSkimRowTest'`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/app/drydock/config/UserConfig.java \
        app/src/main/java/app/drydock/ui/SettingsModal.java \
        app/src/main/java/app/drydock/DrydockApplication.java \
        app/src/main/java/app/drydock/ui/MainWorkspace.java \
        app/src/main/java/app/drydock/ui/explorer/SessionExplorerView.java \
        app/src/main/java/app/drydock/ui/explorer/FileViewer.java \
        app/src/test/java/app/drydock/config/ app/src/test/java/app/drydock/ui/SettingsModalTest.java
git commit -m "Whether a changed file opens folded is now the reader's call"
```

---

### Task 8: The rail always shows the file you are reading

Opening `Strings.java` while the query `settled` is active left the rail listing only `OrderService.java`, still styled as the selection, and no row at all for the file on screen. `FileRailModel.visible` applies the query filter before the open-file exemption, so the exemption only ever worked with an empty query. The footer's counts must not inflate as a result — an open file kept for orientation is not "a match".

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileRailModel.java:113-120,161-172`
- Test: `app/src/test/java/app/drydock/ui/explorer/FileRailModelTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `FileRailModel.visible` keeps `openFile` under any scope and any query. `footer`'s repo-scope match count comes from `matchCount(all, needle)`, a new private helper, not from `visible(...).size()`.

- [ ] **Step 1: Write the failing test**

Add to `FileRailModelTest` (match the class's existing `Entry` construction style):

```java
@Test
void theOpenFileKeepsItsRowEvenWhenTheQueryDoesNotMatchIt() {
    Path open = Path.of("ui/Strings.java");
    List<FileRailModel.Entry> all = List.of(
            new FileRailModel.Entry(Path.of("ui/OrderService.java"), 13, 0, true),
            new FileRailModel.Entry(open, 0, 0, false));

    List<Path> shownInRepo = FileRailModel.visible(all, FileRailModel.Scope.REPO,
                    FileRailModel.Sort.NAME, "settled", open).stream()
            .map(FileRailModel.Entry::relative).toList();
    assertTrue(shownInRepo.contains(open), "the file on screen is never dropped: " + shownInRepo);

    List<Path> shownInDiff = FileRailModel.visible(all, FileRailModel.Scope.DIFF,
                    FileRailModel.Sort.NAME, "settled", open).stream()
            .map(FileRailModel.Entry::relative).toList();
    assertTrue(shownInDiff.contains(open), "…in either scope: " + shownInDiff);
}

@Test
void theOpenFileDoesNotInflateTheMatchCount() {
    Path open = Path.of("ui/Strings.java");
    List<FileRailModel.Entry> all = List.of(
            new FileRailModel.Entry(Path.of("ui/OrderService.java"), 13, 0, true),
            new FileRailModel.Entry(open, 0, 0, false));
    assertEquals("1 match across the repo · out-of-change dimmed",
            FileRailModel.footer(all, FileRailModel.Scope.REPO, FileRailModel.Sort.NAME,
                    "settled", open, 6));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.FileRailModelTest'`
Expected: FAIL — the open file is filtered out, and once it is kept the footer says "2 matches".

- [ ] **Step 3: Keep the open file, count matches separately**

Replace `visible`:

```java
    /**
     * The rows the rail renders.
     *
     * <p>The currently open file survives every narrowing -- scope AND
     * query. The rail must not drop the row for the file the reader is
     * looking at: that is the dead-end the delta forbids, and it is worse
     * than a hidden match because it leaves the rail's selection pointing at
     * a file that is no longer on screen.</p>
     */
    static List<Entry> visible(List<Entry> all, Scope scope, Sort sort, String query, Path openFile) {
        String needle = query == null ? "" : query.strip();
        return all.stream()
                .filter(entry -> entry.relative().equals(openFile)
                        || (entry.matches(needle) && (scope == Scope.REPO || entry.inDiff())))
                .sorted(comparator(sort))
                .toList();
    }

    /** How many entries the query actually matches — the open file is kept for orientation, not counted as a hit. */
    private static int matchCount(List<Entry> all, String needle) {
        return (int) all.stream().filter(entry -> entry.matches(needle)).count();
    }
```

and in `footer`, replace the repo-scope-with-query branch:

```java
        if (!needle.isBlank()) {
            int matches = matchCount(all, needle);
            return matches + (matches == 1 ? " match" : " matches")
                    + " across the repo · out-of-change dimmed";
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.FileRailModelTest' --tests 'app.drydock.ui.explorer.SearchRailViewTest'`
Expected: PASS in both — the view test exercises the same footer strings.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/FileRailModel.java \
        app/src/test/java/app/drydock/ui/explorer/FileRailModelTest.java
git commit -m "The rail never loses the row for the file you are reading"
```

---

### Task 9: The Explorer survives a narrow window

At 1000×760 the rail keeps its full 324px, the code column clips with no horizontal scrollbar, the breadcrumb degrades to `src › … › … › … › … › Strin…`, and the skim/full/editable controls render as `…`, `…`, `e…` — two identical unreadable buttons, one of which switches the reading mode. Review has a specced narrow mode; the Explorer has none. This is the minimal pass: auto-collapse the rail, elide the breadcrumb from the left, and floor the header controls.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/UiFormats.java:66-80`
- Modify: `app/src/main/java/app/drydock/ui/explorer/SessionExplorerView.java:134-164,336-355`
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileViewer.java:2004-2020`
- Create: `app/src/test/java/app/drydock/ui/UiFormatsTest.java` (no test for `UiFormats` exists today)
- Modify: `app/src/test/java/app/drydock/ui/explorer/SessionExplorerViewTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `List<Node> UiFormats.breadcrumbSegments(Path path, int maxSegments)` — the existing one-arg overload delegates with `Integer.MAX_VALUE`. When elided, the first node is a `Label` with the text `…` and the style class `breadcrumb-segment`.

- [ ] **Step 1: Write the failing tests**

Create `UiFormatsTest`. It builds `Label`s, so it needs the toolkit — extend `ApplicationTest` with an empty `start`, the cheapest way to get one in this codebase:

```java
package app.drydock.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The breadcrumb's eliding: which segment gives way when there is no room. */
class UiFormatsTest extends ApplicationTest {

    @Override
    public void start(Stage stage) {
        // No scene needed: these are node factories, not a view. The
        // toolkit is what the Labels want, and extending ApplicationTest is
        // how every other UI test in this tree gets one.
    }
```

with the two tests:

```java
@Test
void aLongBreadcrumbElidesFromTheLeft() {
    List<Node> nodes = UiFormats.breadcrumbSegments(
            Path.of("src/main/java/demo/util/Strings.java"), 3);
    List<String> texts = nodes.stream().map(node -> ((Label) node).getText()).toList();
    assertEquals(List.of("…", "›", "demo", "›", "util", "›", "Strings.java"), texts,
            "the file name is the part that must never be the thing that goes");
}

@Test
void aShortBreadcrumbIsUnchanged() {
    List<Node> nodes = UiFormats.breadcrumbSegments(Path.of("docs/README.md"), 3);
    List<String> texts = nodes.stream().map(node -> ((Label) node).getText()).toList();
    assertEquals(List.of("docs", "›", "README.md"), texts);
}
```

For `SessionExplorerViewTest`. Its `start` currently makes the view the scene root (`new Scene(view, 1200, 800)`), so the view's width is the scene's and a test can only change it by resizing the shared primary stage — which `SkimViewTest` and `SearchRailViewTest` both document as leaking across test classes. Wrap it, exactly as those two do, and drive the view's own `maxWidth` (a `StackPane` lays a child out at its max within the space available):

```java
        view = new SessionExplorerView(root, searchService);
        // A wrapper root, so a test can size the VIEW itself. Resizing the
        // stage instead leaks: TestFX shares one primary stage for the whole
        // JVM (the same reason SkimViewTest wraps its subject).
        StackPane wrapper = new StackPane(view);
        Scene scene = new Scene(wrapper, 1600, 800);
```

with the import `javafx.scene.layout.StackPane`, then:

```java
/** Sizes the view within the wrapper; a StackPane lays a child out at its max. */
private void widthOf(double width) {
    interact(() -> view.setMaxWidth(width));
    waitForFxEvents();
}

@Test
void theRailCollapsesItselfWhenTheWindowGetsNarrow() {
    widthOf(1000);
    assertTrue(onFx(() -> view.diagRailCollapsed()), "below the threshold the rail gets out of the way");

    widthOf(1500);
    assertFalse(onFx(() -> view.diagRailCollapsed()), "…and comes back when there is room again");
}

@Test
void aRailTheReaderCollapsedStaysCollapsedWhenTheWindowWidens() {
    interact(() -> view.diagCollapseRail());
    waitForFxEvents();
    widthOf(1500);
    assertTrue(onFx(() -> view.diagRailCollapsed()),
            "widening the window must not undo a collapse the reader asked for");
}
```

`onFx` and `waitForFxEvents` are the class's existing helpers; the subject field is `view`, not `explorer`.

Note the collapse is animated over 160ms (`SessionExplorerView.COLLAPSE_ANIMATION`), but `railCollapsed` — what `diagRailCollapsed` reports — flips synchronously at the start of `setRailCollapsed`, so these assertions do not race the `Timeline`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.UiFormatsTest' --tests 'app.drydock.ui.explorer.SessionExplorerViewTest'`
Expected: FAIL to compile — neither the two-arg `breadcrumbSegments` nor the two diag accessors exist.

- [ ] **Step 3: Add the eliding overload to `UiFormats`**

```java
    public static List<Node> breadcrumbSegments(Path path) {
        return breadcrumbSegments(path, Integer.MAX_VALUE);
    }

    /**
     * As {@link #breadcrumbSegments(Path)}, keeping at most {@code
     * maxSegments} trailing components and standing in for what it dropped
     * with a leading {@code …}. Elides from the LEFT: the file name is the
     * one segment a reader is never helped by losing.
     */
    public static List<Node> breadcrumbSegments(Path path, int maxSegments) {
        List<Path> segments = new ArrayList<>();
        path.forEach(segments::add);
        boolean elided = segments.size() > maxSegments;
        List<Path> kept = elided
                ? segments.subList(segments.size() - maxSegments, segments.size())
                : segments;

        List<Node> nodes = new ArrayList<>();
        if (elided) {
            nodes.add(segment("…"));
        }
        for (Path part : kept) {
            if (!nodes.isEmpty()) {
                Label sep = new Label("›");
                sep.getStyleClass().add("breadcrumb-separator");
                nodes.add(sep);
            }
            nodes.add(segment(part.toString()));
        }
        return nodes;
    }

    private static Label segment(String text) {
        Label part = new Label(text);
        part.getStyleClass().add("breadcrumb-segment");
        return part;
    }
```

- [ ] **Step 4: Use it, and floor the header controls**

In `FileViewer.updateBreadcrumb`:

```java
        // Below this the trailing controls start eating each other; three
        // trailing segments is what fits beside them at the narrowest width
        // the window allows.
        int maxSegments = getWidth() > 0 && getWidth() < 900 ? 3 : Integer.MAX_VALUE;
        breadcrumb.getChildren().addAll(UiFormats.breadcrumbSegments(shown, maxSegments));
```

and in the constructor, beside the existing edit-banner floors (`:275-279`), add the same treatment for the breadcrumb's trailing controls:

```java
        // The same trap the edit banner hit: an HBox shrinks its rigid
        // children before its growing one, so at 1000px the skim/full pair
        // and the chip rendered as "…", "…", "e…" -- two identical
        // unreadable buttons, one of which changes the reading mode.
        skimSegment.setMinWidth(Region.USE_PREF_SIZE);
        statusChip.setMinWidth(Region.USE_PREF_SIZE);
        gutterToggle.setMinWidth(Region.USE_PREF_SIZE);
```

`updateBreadcrumb` must also re-run on resize, or the elision only applies on a tab switch. In the constructor:

```java
        widthProperty().addListener((obs, was, is) ->
                updateBreadcrumb(fileTabs.getSelectionModel().getSelectedItem()));
```

- [ ] **Step 5: Auto-collapse the rail**

In `SessionExplorerView`, add:

```java
    /** Below this the rail's 324px is most of a narrow window; it gets out of the way. */
    private static final double NARROW_WIDTH = 1100;

    /** True when the reader collapsed the rail themselves, so widening must not undo it. */
    private boolean collapsedByReader;
```

in the constructor, after the collapse/expand handlers are wired:

```java
        rail.setOnCollapseRequested(() -> {
            collapsedByReader = true;
            setRailCollapsed(true);
        });
        rail.setOnExpandRequested(() -> {
            collapsedByReader = false;
            setRailCollapsed(false);
        });
        widthProperty().addListener((obs, was, width) -> {
            if (width.doubleValue() <= 0) {
                return;
            }
            if (width.doubleValue() < NARROW_WIDTH) {
                setRailCollapsed(true);
            } else if (!collapsedByReader) {
                setRailCollapsed(false);
            }
        });
```

and add the two diagnostic accessors next to the existing `diagTrail`:

```java
    /** Diagnostic- and test-only: whether the rail is showing its collapsed strip. */
    public boolean diagRailCollapsed() {
        return railCollapsed;
    }

    /** Diagnostic- and test-only: the reader's own «, so a test can assert it survives a resize. */
    public void diagCollapseRail() {
        collapsedByReader = true;
        setRailCollapsed(true);
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.UiFormatsTest' --tests 'app.drydock.ui.explorer.SessionExplorerViewTest' --tests 'app.drydock.ui.review.*'`
Expected: PASS. The Review package is included because it also consumes `UiFormats.breadcrumbSegments`; the one-arg overload must be behaviourally identical.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/drydock/ui/UiFormats.java \
        app/src/main/java/app/drydock/ui/explorer/SessionExplorerView.java \
        app/src/main/java/app/drydock/ui/explorer/FileViewer.java \
        app/src/test/java/app/drydock/ui/ app/src/test/java/app/drydock/ui/explorer/SessionExplorerViewTest.java
git commit -m "The Explorer stays legible in a narrow window"
```

---

### Task 10: The toast stops covering the peek card's actions

With a peek open, the bottom-centre toast ("Looking for priceFor…") lands on top of the card's action row and covers `a ask the agent` — the row that carries the peek's only mnemonics.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/explorer/FileViewer.java:288-292` (toast setup) and wherever `flashToast` runs
- Test: `app/src/test/java/app/drydock/ui/explorer/SessionExplorerViewTest.java`

**Interfaces:**
- Consumes: `FileViewer.isPeekOpen()` (already exists).
- Produces: nothing public.

- [ ] **Step 1: Write the failing test**

```java
@Test
void aToastRaisedOverAnOpenPeekDoesNotSitOnItsActions() {
    openFile("ui/Sidebar.java");
    // The class's own helper: it waits for the card rather than sleeping,
    // because resolution is a repository text search.
    peek("clamp");
    interact(() -> view.diagToast("hello"));
    waitForFxEvents();

    Node toast = view.lookup(".explorer-toast");
    assertEquals(Pos.TOP_CENTER, StackPane.getAlignment(toast),
            "with a peek open the toast moves out of the card's action row");
}
```

Add the imports `javafx.geometry.Pos` and `javafx.scene.layout.StackPane` to the test class.

Add a diagnostic hook alongside the existing `diagPeek`:

```java
    /** Diagnostic- and test-only: raises the viewer's toast. */
    public void diagToast(String message) {
        viewer.toast(message);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SessionExplorerViewTest'`
Expected: FAIL — the alignment is `BOTTOM_CENTER`.

- [ ] **Step 3: Move the toast when a peek is open**

`toast(String)` (`:871`) delegates to `flashToast` (`:876`), so `flashToast` is the single seam. Add, right after the `getScene() == null` early return and before `toast.setText(message)`:

```java
        // The peek card's action row lives at the bottom of the viewer, and
        // it is the only place the peek's keys are advertised. A toast that
        // covers it hides the answer to "what can I do here".
        StackPane.setAlignment(toast, isPeekOpen() ? Pos.TOP_CENTER : Pos.BOTTOM_CENTER);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'app.drydock.ui.explorer.SessionExplorerViewTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/drydock/ui/explorer/FileViewer.java \
        app/src/main/java/app/drydock/ui/explorer/SessionExplorerView.java \
        app/src/test/java/app/drydock/ui/explorer/SessionExplorerViewTest.java
git commit -m "A toast stops sitting on the peek card's actions"
```

---

### Task 11: Every Explorer key the app binds is advertised

`/`, `d` and `s` are bound in `SessionExplorerView.installShortcuts` and appear nowhere in `ShortcutsOverlay`. AGENTS.md ("UI lifecycle hygiene") requires parity in both directions, so this is a rule violation, not a nicety. The rail also shows a `d` key hint beside the scope toggle and nothing beside the sort button.

**Files:**
- Modify: `app/src/main/java/app/drydock/ui/ShortcutsOverlay.java:63-69`
- Modify: `app/src/main/java/app/drydock/ui/explorer/SearchRail.java:283-296`
- Test: `app/src/test/java/app/drydock/ui/explorer/SearchRailViewTest.java`
- Test: a new `app/src/test/java/app/drydock/ui/ShortcutsOverlayParityTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ShortcutsOverlay` exposes `static List<String> diagKeysFor(String sectionTitle)` returning the keycap column of a section, so the parity test can assert against it without scraping nodes.

- [ ] **Step 1: Write the failing tests**

`ShortcutsOverlayParityTest`:

```java
/**
 * AGENTS.md: anything advertised here must be bound, and vice versa. The
 * Explorer's single-letter layer lives in SessionExplorerView.installShortcuts;
 * this pins the "and vice versa" half, which is the one that rotted.
 */
class ShortcutsOverlayParityTest {

    @Test
    void everyExplorerKeyTheViewBindsIsAdvertised() {
        List<String> advertised = ShortcutsOverlay.diagKeysFor("IN THE EXPLORER");
        for (String key : List.of("/", "d", "s", "z", "⏎ / u / a", "⌘[ / ⌘]", "Esc")) {
            assertTrue(advertised.contains(key),
                    "the Explorer binds " + key + " but the overlay does not mention it: " + advertised);
        }
    }
}
```

Add to `SearchRailViewTest`:

```java
@Test
void theSortButtonAdvertisesItsKeyLikeTheScopeToggleDoes() {
    List<String> hints = lookup(".rail-key-hint").queryAll().stream()
            .map(node -> ((Label) node).getText())
            .toList();
    assertEquals(List.of("d", "s"), hints, "both single-letter rail keys are on screen");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'app.drydock.ui.ShortcutsOverlayParityTest' --tests 'app.drydock.ui.explorer.SearchRailViewTest'`
Expected: FAIL — `diagKeysFor` does not exist; the rail shows only `["d"]`.

- [ ] **Step 3: Add the missing overlay rows and the accessor**

Replace the `IN THE EXPLORER` section:

```java
            new Section("IN THE EXPLORER", new String[][] {
                    {"Focus the file search", "/"},
                    {"Scope: this change / the whole worktree", "d"},
                    {"Sort: churn · findings · a-z", "s"},
                    {"Skim / full text for this file", "z"},
                    {"Peek at a symbol in place", "click an underlined symbol"},
                    {"In a peek: open for real / usages / ask the agent", "⏎ / u / a"},
                    {"Back / forward along the trail", "⌘[ / ⌘]"},
                    {"Close one peek", "Esc"},
            }),
```

and add:

```java
    /** Test-only: the keycap column of one section, for the advertised↔bound parity check. */
    static List<String> diagKeysFor(String sectionTitle) {
        for (Section section : SECTIONS) {
            if (section.title().equals(sectionTitle)) {
                return Arrays.stream(section.shortcuts()).map(row -> row[1]).toList();
            }
        }
        return List.of();
    }
```

with the imports `java.util.Arrays` and `java.util.List`.

- [ ] **Step 4: Add the sort key hint**

In `SearchRail.buildExpandedContent`, after `sortButton.setOnAction(...)`:

```java
        Label sortKey = new Label("s");
        sortKey.getStyleClass().add("rail-key-hint");
```

and put it in the header row beside the sort button:

```java
        HBox headerRow = new HBox(6, header, scopeSegment, scopeKey, headerSpacer,
                sortButton, sortKey, collapse);
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'app.drydock.ui.ShortcutsOverlayParityTest' --tests 'app.drydock.ui.explorer.SearchRailViewTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/drydock/ui/ShortcutsOverlay.java \
        app/src/main/java/app/drydock/ui/explorer/SearchRail.java \
        app/src/test/java/app/drydock/ui/ShortcutsOverlayParityTest.java \
        app/src/test/java/app/drydock/ui/explorer/SearchRailViewTest.java
git commit -m "The Explorer's keys are written down where the reader looks for them"
```

---

## Final verification

- [ ] **Run the whole suite once, from the controlling session, not a subagent** (it takes 14–20 minutes, past the 10-minute Bash ceiling a subagent has):

```bash
./gradlew :app:test
```

- [ ] **Rebuild the fixture repo.** A diag session's search root and its repo root are the same checkout, and `DiffOverlay`'s base branch is read from that same checkout — so on a branch, `git diff <base>...HEAD` is structurally empty and nothing in the Explorer's diff half ever lights up. A **detached HEAD** is what breaks the tie: `setBaseBranch` is never called, the base stays its `"master"` default, and the diff is real. Any repo shaped like this works:

```bash
R=<tmp>/fixture-repo
rm -rf "$R" && mkdir -p "$R/src/main/java/demo" && cd "$R"
git init -q -b master && git config user.email a@b.c && git config user.name Fixture
# Two or three Java files with a mix of public methods and private helpers,
# at least one helper carrying a distinctive string to search for.
git add -A && git commit -qm "Base revision"
git checkout -qb work
# Edit one method body and add one new method, in two different files.
git add -A && git commit -qm "Bulk discounts on large orders"
git checkout -q --detach                                  # <- the point
git branch -f master $(git rev-list --max-parents=0 HEAD)
git diff --stat master...HEAD                             # must be non-empty
```

- [ ] **Re-run the visual pass and compare against the 2026-08-06 baseline:**

```bash
./gradlew run \
  -Papp.drydock.diag.stateFile=<tmp>/diag-state.json \
  -Papp.drydock.diag.autoCreateSession=true \
  -Papp.drydock.diag.repo=<tmp>/fixture-repo \
  -Papp.drydock.diag.explorerScript="34:open:src/main/java/demo/OrderService.java,37:shot:<tmp>/after/01-skim-default.png,45:search:settled,48:shot:<tmp>/after/03-search-needle.png,50:open:src/main/java/demo/util/Strings.java,53:shot:<tmp>/after/04-second-file.png,73:resize:1000x760,75:shot:<tmp>/after/09-narrow.png"
```

Check, shot by shot: `01` opens at the constructor (line 12) not at `total()` (line 23) and no member repeats its signature; `03` shows a legible `▾` on the match group; `04` lists `Strings.java` as the open file; `09` shows the rail collapsed to its 46px strip with `[skim|full]` and `editable` still readable. Kill the instance by PID afterwards (`ps -eo pid,command | grep diag.autoCreateSession`), never by name pattern — an agent session may be hosted inside the user's own Drydock.

- [ ] **Hand-verify the two things no harness can drive:** scroll the wheel over an expanded skim body (Task 3) and click a search match that lands inside an untouched private helper (Task 5). Neither has a diag verb — there is no wheel input and no match-line click.
