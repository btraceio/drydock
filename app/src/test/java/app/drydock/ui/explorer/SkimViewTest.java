package app.drydock.ui.explorer;

import app.drydock.ui.TestStages;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skim mode's rows through the headless harness: what starts open, what
 * folds away, and what a finding does to a row.
 */
class SkimViewTest extends ApplicationTest {

    private static final String SOURCE = """
            class Sidebar {

                int width() {
                    return clamp(raw);
                }

                void onRelease(MouseEvent e) {
                    widthProperty.set(tracker.raw());
                }

                private void snapToGuide() {
                    guide.snap();
                }

                private void persist() {
                    prefs.put(KEY, w);
                }
            }
            """;

    private SkimView skim;

    @Override
    public void start(Stage stage) {
        skim = new SkimView();
        // A wrapper root, so a test can size the view itself. Resizing the
        // stage instead would leak: TestFX shares one primary stage across
        // every test class, and a stage left short made a dozen assertions
        // fail over in the review package.
        javafx.scene.layout.StackPane wrapper = new javafx.scene.layout.StackPane(skim);
        Scene scene = new Scene(wrapper, 900, 600);
        scene.getStylesheets().addAll(
                SkimView.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SkimView.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    private void show(Set<Integer> changed, Map<Integer, String> findings) {
        interact(() -> skim.show(Path.of("ui/Sidebar.java"), SOURCE,
                SourceOutline.parse(SOURCE), changed, findings));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }

    private void settle() {
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Shrinks the viewport to a quarter of the content's real, laid-out
     * height, so the view genuinely overflows and one wheel notch or one
     * reveal is a small proportional step.
     *
     * <p>Derived, not chosen: a hand-picked pixel height goes stale the
     * moment a row's height, padding or count changes, and this class has
     * already had to retune such a literal twice for unrelated reasons. Call
     * after {@code show(...)} has settled -- the content has to be laid out
     * before its height means anything.</p>
     */
    private void shrinkViewportToQuarterOfContent() {
        Region rows = (Region) lookup(".skim-rows").query();
        double viewportHeight = rows.getHeight() / 4;
        interact(() -> {
            skim.setMinHeight(viewportHeight);
            skim.setPrefHeight(viewportHeight);
            skim.setMaxHeight(viewportHeight);
        });
        settle();
    }

    /**
     * Waits for {@code condition} rather than for a fixed number of pulses.
     *
     * <p>How many layout passes a scroll takes to settle is not knowable from
     * here, and it changes with how busy the machine is: two {@code settle()}
     * calls were enough when this class ran alone and not when it ran inside
     * the full suite. A timeout still fails the test, so this waits for the
     * outcome without hiding its absence.</p>
     */
    private void waitUntil(String what, java.util.concurrent.Callable<Boolean> condition) {
        try {
            org.testfx.util.WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS, condition);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("timed out waiting until " + what, e);
        }
    }

    /** The row keyed by {@code line}, or null while it is not rendered. */
    private Node rowFor(int line) {
        Region rows = (Region) lookup(".skim-rows").query();
        return rows.getChildrenUnmodifiable().stream()
                .filter(node -> Integer.valueOf(line).equals(node.getProperties().get("drydock.line")))
                .findFirst().orElse(null);
    }

    /** How far the view is scrolled, in pixels. */
    private double scrollOffset() {
        Region rows = (Region) lookup(".skim-rows").query();
        return skim.getVvalue() * (rows.getHeight() - skim.getViewportBounds().getHeight());
    }

    /**
     * Asserts the row keyed by {@code line} sits at the top of the viewport.
     *
     * <p>Derived from that row's own laid-out position rather than compared
     * against a chosen vvalue: a threshold is a guess about geometry, and the
     * question here is only ever "is this row where the reader is looking".</p>
     */
    private void assertRowAtTop(int line, String because) {
        waitUntil("row " + line + " is at the top", () -> {
            Node row = rowFor(line);
            return row != null && Math.abs(row.getBoundsInParent().getMinY() - scrollOffset()) < 2.0;
        });
        Node row = rowFor(line);
        assertEquals(row.getBoundsInParent().getMinY(), scrollOffset(), 2.0, because);
    }

    private List<String> rowSignatures() {
        return lookup(".skim-signature").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private List<String> openRowSignatures() {
        return lookup(".skim-signature-open").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    @Test
    void changedMembersStartOpenAndEverythingElseStartsFolded() {
        show(Set.of(8), Map.of());

        assertEquals(List.of("void onRelease(MouseEvent e) {"), openRowSignatures(),
                "only the member carrying the changed line is pre-expanded");
        assertTrue(rowSignatures().contains("int width() {"), "the untouched member is still a row");
        assertEquals(1, lookup(".skim-code").queryAll().size(), "exactly one body is rendered");
    }

    @Test
    void untouchedPrivateHelpersCollapseIntoOneGroupRow() {
        show(Set.of(8), Map.of());

        Label group = (Label) lookup(".skim-group-signature").query();
        assertEquals("private helpers (2)", group.getText());
        assertFalse(rowSignatures().contains("private void persist() {"),
                "a folded helper does not get a row of its own");
    }

    @Test
    void openingTheHelperGroupGivesEachHelperItsOwnRow() {
        show(Set.of(8), Map.of());
        Button group = lookup(".skim-header").queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> button.getGraphic() instanceof HBox box
                        && box.getChildren().stream().anyMatch(node -> node instanceof Label label
                        && label.getText().startsWith("private helpers")))
                .findFirst().orElseThrow();
        clickOn(group);
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertTrue(rowSignatures().contains("private void snapToGuide() {"));
        assertTrue(rowSignatures().contains("private void persist() {"));
    }

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

    @Test
    void aFindingKeepsItsMemberOutOfTheFoldedGroupAndLabelsTheRow() {
        show(Set.of(), Map.of(13, "leak"));

        Label chip = (Label) lookup(".skim-finding-tag").query();
        assertEquals("· ◆1 leak", chip.getText());
        assertEquals("private helpers (1)", ((Label) lookup(".skim-group-signature").query()).getText(),
                "a private helper with a finding is not something to fold away");
        assertTrue(lookup(".skim-header").queryAll().stream()
                        .anyMatch(node -> node.getStyleClass().contains("has-finding")),
                "the row carries the faint red tint");
    }

    @Test
    void expandingAMemberIsRememberedAndReversible() {
        show(Set.of(), Map.of());
        Button first = lookup(".skim-header").queryButton();
        clickOn(first);
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, lookup(".skim-code").queryAll().size());

        clickOn(lookup(".skim-header").queryButton());
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        assertEquals(0, lookup(".skim-code").queryAll().size(), "clicking again folds it back");
    }

    @Test
    void revealingALineOpensTheMemberThatContainsIt() {
        show(Set.of(), Map.of());
        interact(() -> skim.revealLine(8));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("void onRelease(MouseEvent e) {"), openRowSignatures());
    }

    /**
     * Revealing has to scroll to the member, not to the top. The rows are
     * rebuilt first, so their bounds are all zero until a layout pass runs --
     * reading them too early makes every reveal compute a target of 0, which
     * is exactly "jump to the top of the file" and defeats the round-trip
     * that {@code z} and a minimap tick both depend on.
     */
    @Test
    void revealingScrollsToTheMemberRatherThanTheTopOfTheFile() {
        show(Set.of(), Map.of());
        settle();
        // Derived, like every other scroll test here: the 140px literal this
        // used to hand-pick was TALLER than the folded outline, so there was
        // no overflow until the reveal itself expanded a member -- and the
        // assertion then passed on the few pixels that produced, whether or
        // not the scroll had gone anywhere near the member.
        shrinkViewportToQuarterOfContent();

        interact(() -> skim.revealLine(8));
        settle();
        settle();

        assertRowAtTop(7, "scrolled to the revealed member, not to the top");
    }

    /**
     * A line between members -- here, the blank line right before {@code
     * onRelease} -- used to make revealLine() a no-op (memberAt() is empty
     * there), which is exactly what left a fresh skim open sitting on
     * setSkim's pre-layout anchor with nothing above the viewport explained.
     * Checks both that the member opens AND that the view actually scrolled
     * there -- opening without scrolling would still leave the reveal
     * looking like it did nothing.
     */
    @Test
    void revealingALineBetweenMembersLandsOnTheNextMemberRatherThanDoingNothing() {
        show(Set.of(), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();

        int blankLineBeforeOnRelease = SOURCE.lines().toList().indexOf("    void onRelease(MouseEvent e) {");
        interact(() -> skim.revealLine(blankLineBeforeOnRelease));
        settle();

        assertTrue(skim.getVvalue() > 0.0,
                "scrolled toward the nearest member below, not left at rest: vvalue=" + skim.getVvalue());
        // Scrolled to, NOT opened: the reader pointed at a blank line, not at
        // onRelease. Opening it here would also mark it read, which the trail
        // and the dwell sampler both take at face value.
        assertEquals(List.of(), openRowSignatures(),
                "resolving forward does not open a member the reader never pointed at");
    }

    /**
     * Resolving forward onto an untouched private helper is the case with no
     * row of its own: the helpers share one folded group row, keyed by the
     * FIRST of them. Landing on a later one matched nothing and left the
     * reader wherever they happened to be.
     */
    @Test
    void revealingForwardOntoAFoldedHelperScrollsToTheGroupThatHoldsIt() {
        show(Set.of(), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();

        // Line 14: the blank line between snapToGuide (11..13) and persist
        // (15..17). It resolves forward to persist, which is folded away.
        int blankLineBeforePersist = SOURCE.lines().toList().indexOf("    private void persist() {");
        interact(() -> skim.revealLine(blankLineBeforePersist));
        settle();
        settle();

        assertRowAtTop(11, "scrolled to the group row that holds persist, not left at the top");
    }

    /**
     * The reveal has to survive the pulse after it. Expanding a member makes
     * the content taller, and ScrollPaneSkin's next layout re-derives vvalue
     * to preserve the previous ABSOLUTE offset -- which silently undid the
     * scroll revealLine had just computed. Measured before the fix: a target
     * of 0.41 came back as 0.05, the old offset against the taller content.
     *
     * <p>Two settles, therefore: one is the frame revealLine ran in, the
     * second is the pass that used to overwrite it.</p>
     */
    @Test
    void aRevealHoldsItsScrollThroughTheLayoutPassThatFollows() {
        show(Set.of(), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();

        // Inside snapToGuide, so this one opens it -- which is what changes
        // the content height and triggers the overwrite.
        int insideSnapToGuide = SOURCE.lines().toList().indexOf("    private void snapToGuide() {") + 2;
        interact(() -> skim.revealLine(insideSnapToGuide));
        settle();
        settle();

        // vvalue, not topLine(): topLine() reports the row whose BOTTOM edge
        // touches the viewport top, so the row above the target answers it.
        // The scroll position is what the overwrite was destroying.
        assertRowAtTop(11, "the view is down at the revealed member, one pulse later too");
    }



    /**
     * Stands in for FileViewer's file-open sequence, using the same two
     * calls FileViewer.openFile actually makes for a skim tab opened with a
     * jumpToLine (the ⤢ from Review, a rail match-line click): setSkim's
     * revealLine(anchor) with a bogus, pre-layout anchor deep in the file,
     * followed by scrollTo's revealLine(jumpToLine) -- here jumpToLine=1,
     * "package", which sits before every member. Before the fix, revealLine
     * on a line no member contains did nothing at all, so the bogus anchor's
     * scroll position survived untouched; this is the instrumented trace's
     * revealLine(42) -> revealLine(1) -> NONE sequence, reproduced directly.
     */
    @Test
    void anOpenSequenceEndsAtTheFirstMemberEvenWithABogusAnchorFirst() {
        show(Set.of(), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();

        int lineDeepInTheFile = SOURCE.lines().toList().indexOf("        prefs.put(KEY, w);") + 1;
        interact(() -> skim.revealLine(lineDeepInTheFile));
        settle();
        double bogusAnchorPosition = skim.getVvalue();
        assertTrue(bogusAnchorPosition > 0.0, "sanity: the bogus anchor did scroll somewhere");

        interact(() -> skim.revealLine(1));
        settle();

        assertEquals(SOURCE.lines().toList().indexOf("    int width() {") + 1, skim.topLine(),
                "the first member is what the reader sees first, not wherever the bogus anchor landed");
        // topLine() alone does not discriminate: with a quarter-height
        // viewport it reports the first row even while the view sits
        // scrolled well below it, so it passes against the unfixed code too.
        // The scroll position is what the bug actually left wrong. Compared
        // against the bogus position rather than against 0 because the first
        // row starts below the rows container's padding, so "at the top" is
        // not literally vvalue 0 and a fixed threshold would be a guess.
        assertTrue(skim.getVvalue() < bogusAnchorPosition,
                "the view moved back up to that member instead of staying where the bogus anchor left it: "
                        + skim.getVvalue() + " vs " + bogusAnchorPosition);
    }

    @Test
    void everySkimRowIsFocusTraversable() {
        show(Set.of(8), Map.of());
        assertTrue(lookup(".skim-header").queryAll().stream().allMatch(node -> node.isFocusTraversable()));
    }

    @Test
    void theWheelOverAnExpandedBodyScrollsTheSkimViewProportionally() {
        // Every member changed, so every body is open: enough rendered
        // content that the viewport below genuinely overflows without
        // squeezing it down to where Flowless's own flow has no visible
        // area left to misbehave in.
        show(Set.of(4, 8, 12, 16), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();
        // A paragraph's own line-number graphic, not the CodeArea itself:
        // Flowless's VirtualFlow is a private child *inside* the CodeArea, so
        // an event fired on the CodeArea as target never reaches it -- event
        // dispatch never descends into a target's own children. Firing on a
        // real paragraph-graphic node makes the flow a genuine ancestor of
        // the target, on the bubbling path our filter has to beat.
        Node body = lookup(".skim-body-lineno").query();
        interact(() -> skim.setVvalue(0.0));
        settle();

        interact(() -> body.fireEvent(new ScrollEvent(ScrollEvent.SCROLL,
                0, 0, 0, 0, false, false, false, false, false, false,
                0, -120, 0, -120, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null)));
        settle();

        assertTrue(skim.getVvalue() > 0.0 && skim.getVvalue() < 1.0,
                "a single wheel notch over open code must move the skim scroller proportionally,"
                        + " not be swallowed and not snap to an end: vvalue=" + skim.getVvalue());
    }

    @Test
    void theWheelOverABodyThatDoesNotOverflowLeavesTheSkimViewAtRest() {
        show(Set.of(3, 4), Map.of());
        // No height shrink here: the default stage keeps the rows well
        // inside the viewport, so there is nothing to scroll. Pins the
        // span-clamp defect, where flooring the divisor at 1 turned a wheel
        // notch into a snap to vvalue 0 or 1 instead of a no-op.
        Node body = lookup(".skim-code").query();
        interact(() -> skim.setVvalue(0.0));
        settle();

        interact(() -> body.fireEvent(new ScrollEvent(ScrollEvent.SCROLL,
                0, 0, 0, 0, false, false, false, false, false, false,
                0, -120, 0, -120, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null)));
        settle();

        assertEquals(0.0, skim.getVvalue(),
                "a wheel notch over a body with nothing to scroll must leave the skim view alone");
    }

    @Test
    void expandingAMemberKeepsTheReadersPlace() {
        show(Set.of(3, 4), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();
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

    @Test
    void anExpandedMemberDoesNotRepeatItsSignature() {
        // Line 4, the body -- NOT line 3, the signature. A change on the
        // declaration line keeps that line so its marker has somewhere to
        // sit (see aChangeOnTheSignatureLineKeepsThatLineInTheBody); this
        // test is about the ordinary case, and its old fixture happened to
        // include the signature line.
        show(Set.of(4), Map.of());
        CodeArea body = (CodeArea) lookup(".skim-code").query();
        String firstLine = body.getText().lines().findFirst().orElse("").strip();
        assertFalse(firstLine.equals("int width() {"),
                "the header already says the signature; the body starts at the line after it: "
                        + body.getText());
        assertTrue(body.getText().contains("return clamp(raw);"), body.getText());
    }

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

    @Test
    void aRevealRightAfterShowSurvivesTheNextPulse() {
        // show() + an immediate reveal is the file-open path: the viewer opens
        // the file and jumps to the requested line in the same FX pulse.
        // A restore queued by show()'s own rebuild must not undo the jump.
        show(Set.of(), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();

        // persist() is the last member -- well down the file, so revealing it
        // only reads as success if the skim view is still looking at it.
        int lineWellDown = SOURCE.lines().toList().indexOf("        prefs.put(KEY, w);") + 1;
        interact(() -> {
            skim.show(Path.of("ui/Sidebar.java"), SOURCE, SourceOutline.parse(SOURCE), Set.of(), Map.of());
            skim.revealLine(lineWellDown);
        });
        // Two pulses, not one: a single settle() only drains the synchronous
        // work above, which already leaves vvalue at the reveal's target --
        // against the regression this test pins, that first settle() alone
        // still passes. The restore show()'s rebuild queued fires on the
        // pulse after that; this second settle() is what actually forces it
        // to run and is what the regression needs to fail against.
        settle();
        settle();

        assertTrue(skim.getVvalue() > 0.0, "the requested line is still what the reader is looking at");
    }

    @Test
    void aRevealDuringAnOrdinaryRefreshSurvivesTheNextPulse() {
        // refresh() is what FileViewer.refreshSkim calls on every findings and
        // diff-overlay refresh, and findings arrive over MCP while the reader
        // is reading -- so a refresh() landing in the same pulse as a minimap
        // click or a `z` toggle is a real interleaving, not a contrived one.
        // refresh()'s own deferred restore must not overwrite a reveal that
        // lands in the gap before it fires.
        show(Set.of(), Map.of());
        settle();
        shrinkViewportToQuarterOfContent();

        // A distinctive scroll position, well past where the reveal below
        // lands: refresh()'s rebuild captures THIS as the value to restore,
        // and if the guard is missing, that capture -- not wherever the
        // reveal put the reader -- is what silently wins back.
        interact(() -> skim.setVvalue(0.95));
        settle();

        // persist() is the last member -- well down the file, so revealing it
        // only reads as success if the skim view is still looking at it.
        int lineWellDown = SOURCE.lines().toList().indexOf("        prefs.put(KEY, w);") + 1;
        interact(() -> {
            skim.refresh(Set.of(), Map.of());
            skim.revealLine(lineWellDown);
        });
        // Two pulses, not one: see aRevealRightAfterShowSurvivesTheNextPulse
        // for why a single settle() cannot force the deferred restore to run.
        settle();
        settle();

        assertTrue(skim.getVvalue() < 0.7,
                "the revealed member is still what the reader is looking at, not the pre-refresh "
                        + "scroll position refresh() queued to restore: vvalue=" + skim.getVvalue());
    }

    @Test
    void aSecondShowOfADifferentDocumentDoesNotCarryTheFirstDocumentsExpansion() {
        show(Set.of(), Map.of());
        // onRelease starts at line 7 and is not pre-expanded by default (only
        // the changed set is), so an explicit reveal is what puts a TRUE in
        // expansion keyed by that line number.
        interact(() -> skim.revealLine(7));
        settle();
        assertTrue(openRowSignatures().contains("void onRelease(MouseEvent e) {"));

        // A second, unrelated document whose member b() happens to start at
        // the same line 7, and is neither changed nor otherwise expanded.
        String other = """
                class Other {

                    int a() {
                        return 1;
                    }

                    void b() {
                        doStuff();
                    }
                }
                """;
        interact(() -> skim.show(Path.of("Other.java"), other,
                SourceOutline.parse(other), Set.of(), Map.of()));
        settle();

        assertEquals(List.of(), openRowSignatures(),
                "a stale expansion keyed by line number must not leak into an unrelated member "
                        + "of the new document");
    }

    /**
     * A change confined to the declaration line itself -- a parameter added,
     * a return type changed -- tags the row `· changed` and pre-expands it.
     * Dropping that line as a repeated signature would then show a body with
     * no green marker anywhere in it: told the member changed, shown nothing.
     */
    @Test
    void aChangeOnTheSignatureLineKeepsThatLineInTheBody() {
        int signatureLine = SOURCE.lines().toList().indexOf("    int width() {") + 1;
        show(Set.of(signatureLine), Map.of());

        CodeArea body = (CodeArea) lookup(".skim-code").query();
        String firstLine = body.getText().lines().findFirst().orElse("").strip();
        assertEquals("int width() {", firstLine,
                "the changed line is in the body so its marker has somewhere to sit");
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
}
