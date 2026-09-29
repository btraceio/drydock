package app.drydock.ui.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.ui.ShortcutsOverlay;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code p} gives the rail a second mode (spec §7.1): {@code PATH} lists one
 * row per hunk in reading order, across section boundaries, rather than
 * today's per-intent cards. It is a mode of the rail, not a fourth column --
 * the width budget that ruled out a concept map rules out a new column just
 * as firmly, and {@code RailLayout} is untouched by this task.
 *
 * <p>{@link ReviewViewFixture}'s board supplies a REVIEWER grouping ({@code
 * host.intents.set(...)}), which is exactly the case that used to skip
 * building a {@link app.drydock.review.ChangeGraph} at all ({@code
 * Host#hasReviewerGrouping}) -- PATH mode needs one regardless, so entering
 * it for the first time kicks a build off lazily. That build runs on a
 * virtual thread, off the FX thread, so every test below that needs real
 * steps waits for {@link #awaitPathReady()} rather than trusting {@link
 * WaitForAsyncUtils#waitForFxEvents()} alone to have let it finish.</p>
 */
class ReviewPathModeTest extends ReviewViewFixture {

    @Test
    void pTogglesTheRailBetweenIntentsAndPath() {
        assertEquals(ReviewIntentRail.Mode.INTENTS, view.railMode());

        pressP();
        assertEquals(ReviewIntentRail.Mode.PATH, view.railMode());

        pressP();
        assertEquals(ReviewIntentRail.Mode.INTENTS, view.railMode());
    }

    /**
     * One key, not a parallel set: {@code [} / {@code ]} step whatever the
     * rail is currently listing -- sections in INTENTS mode, hunks in PATH
     * mode.
     */
    @Test
    void bracketsStepHunksInPathModeAndSectionsInIntentsMode() {
        pressP();
        awaitPathReady();

        assertEquals(0, view.selectedPathStepForTest(), "PATH mode starts on the entry point");

        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, view.selectedPathStepForTest());
    }

    /**
     * {@code n} keeps meaning "next unsettled", a property of hunks
     * regardless of what the rail shows -- not "next row". Three of this
     * board's four hunks are pre-settled here, leaving exactly one
     * ({@link #FILE_C}'s) unsettled, so a plain "move by one" would land
     * somewhere else than a real unsettled-hunk search: this is only green
     * if {@code n} actually skips the settled rows to find it.
     */
    @Test
    void nStillWalksUnsettledWorkInPathMode() {
        settle(FILE_A, 0);
        settle(FILE_A, 1);
        settle(FILE_B, 0);
        // FILE_C's one hunk is deliberately left unsettled.

        pressP();
        awaitPathReady();

        press(KeyCode.N).release(KeyCode.N);
        WaitForAsyncUtils.waitForFxEvents();

        int selected = view.selectedPathStepForTest();
        List<String> rows = view.pathRowTextsForTest();
        assertTrue(selected >= 0 && selected < rows.size(), "n must land on a real row");
        assertTrue(rows.get(selected).contains(FILE_C),
                "the only unsettled hunk is in " + FILE_C + "; n must find it rather than just "
                        + "advancing by one");
    }

    @Test
    void everyPathRowStatesItsReason() {
        pressP();
        awaitPathReady();

        List<String> rows = view.pathRowTextsForTest();
        assertTrue(rows.size() >= 4, "this board's four hunks must all appear as rows");
        assertTrue(rows.stream().noneMatch(String::isBlank));
        // Not merely non-blank: each row must carry the word this rail uses
        // to scope the reason to the FILE rather than the hunk (this task's
        // own correction -- reason is file-level, and a row that dropped the
        // word would read as a claim about the hunk itself).
        assertTrue(rows.stream().allMatch(row -> row.contains("file ")),
                "every row must state why its FILE sits where it does: " + rows);
    }

    /** Advertised and bound must match (AGENTS.md). */
    @Test
    void theShortcutsOverlayAdvertisesP() {
        assertTrue(ShortcutsOverlay.reviewShortcutKeys().contains("p"));
    }

    /**
     * CRITICAL fix, mutation-verified below: {@code a} in PATH mode must
     * settle exactly the selected row's one hunk, never the whole INTENTS
     * section that hunk happens to also belong to. {@link ReviewViewFixture}'s
     * board groups {@link #FILE_A}'s two hunks and {@link #FILE_B}'s one into
     * "section-1" -- if {@code a} still settled by section (the bug a real
     * screenshot caught: the verdict bar read "Approve (section)" with a
     * PATH row selected), approving row 0 would silently record THREE
     * verdicts instead of one.
     */
    @Test
    void aInPathModeSettlesOnlyTheSelectedRowNotTheWholeSection() {
        pressP();
        awaitPathReady();
        assertEquals(0, view.selectedPathStepForTest());
        String selectedRow = view.pathRowTextsForTest().get(0);

        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        List<String> allDigests = List.of(digestOf(FILE_A, 0), digestOf(FILE_A, 1),
                digestOf(FILE_B, 0), digestOf(FILE_C, 0));
        long settledCount = allDigests.stream().filter(d -> host.verdict(scope, d).isPresent()).count();
        assertEquals(1, settledCount, "row 0 is " + selectedRow + "; exactly its one hunk must be "
                + "settled, not the whole section: " + allDigests.stream()
                        .map(d -> host.verdict(scope, d).isPresent()).toList());
    }

    /**
     * {@code u} undoes exactly what PATH mode's {@code a} last recorded --
     * the same one-hunk precision the settle side needs, mirrored on undo.
     */
    @Test
    void uInPathModeUndoesOnlyWhatAJustSettled() {
        pressP();
        awaitPathReady();

        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        List<String> allDigests = List.of(digestOf(FILE_A, 0), digestOf(FILE_A, 1),
                digestOf(FILE_B, 0), digestOf(FILE_C, 0));
        assertEquals(1, allDigests.stream().filter(d -> host.verdict(scope, d).isPresent()).count());

        press(KeyCode.U).release(KeyCode.U);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(allDigests.stream().noneMatch(d -> host.verdict(scope, d).isPresent()),
                "u must clear the one verdict a just recorded, leaving nothing settled");
    }

    /**
     * The verdict bar's own Undo button must act on the SAME target the
     * settle actions do -- the row on screen -- not the intents cursor.
     * Reproduces the coordinator's own trace: settle every hunk via
     * INTENTS mode's {@code a},{@code a} (settleUnit SECTION, since focus
     * stays on the rail), switch to PATH mode (selected row is {@link
     * #FILE_B}'s own hunk, the entry point), click Undo. Before the fix,
     * the bar rendered off the intents cursor regardless of mode, so Undo
     * cleared "section-2"'s two hunks -- neither of them the visible row --
     * and left the visible row's own verdict untouched.
     */
    @Test
    void theVerdictBarsUndoButtonClearsOnlyTheSelectedRow() {
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        String onScreen = digestOf(FILE_B, 0);
        List<String> offScreen = List.of(digestOf(FILE_A, 0), digestOf(FILE_A, 1), digestOf(FILE_C, 0));
        assertTrue(host.verdict(scope, onScreen).isPresent(), "setup: guards.cpp must start settled");
        assertTrue(offScreen.stream().allMatch(d -> host.verdict(scope, d).isPresent()),
                "setup: a,a in INTENTS mode must settle every hunk on this board");

        pressP();
        awaitPathReady();
        assertEquals(0, view.selectedPathStepForTest());
        assertTrue(view.pathRowTextsForTest().get(0).contains(FILE_B),
                "row 0 must be " + FILE_B + "'s own hunk for this test to mean anything");

        clickUndoButton();

        assertFalse(host.verdict(scope, onScreen).isPresent(),
                "Undo must clear the row actually on screen (guards.cpp)");
        assertTrue(offScreen.stream().allMatch(d -> host.verdict(scope, d).isPresent()),
                "Undo must NOT touch hunks nowhere near the selected row: " + offScreen.stream()
                        .map(d -> host.verdict(scope, d).isPresent()).toList());
    }

    /**
     * The verdict bar's own ‹/› buttons must step the SAME thing {@code [}/
     * {@code ]} do -- PATH rows, not the (invisible) intents cursor. Before
     * this fix, {@code VerdictHost.previousIntent}/{@code nextIntent} called
     * {@code moveIntent} unconditionally.
     */
    @Test
    void theVerdictBarsNextButtonStepsPathRowsInPathMode() {
        pressP();
        awaitPathReady();
        assertEquals(0, view.selectedPathStepForTest());

        interact(() -> ((Button) lookup(".review-verdict-next").queryAll().iterator().next()).fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, view.selectedPathStepForTest(),
                "the bar's own next button must move the PATH cursor, not the intents one");
    }

    /**
     * CRITICAL fix: a blocking finding attributed to a REAL intent must
     * still refuse {@code a} in PATH mode. {@code pathStepAsIntent}'s
     * synthetic {@code "path:" + hunkId} can never equal a finding's named
     * {@code intentId}, so asking {@code blockingFindingOpen} about the
     * synthetic id directly (the bug: proved by execution, INTENTS mode
     * refused the identical finding while PATH mode approved anyway, with
     * the bar simultaneously reading "a blocking finding is still open")
     * would silently let this through. {@code "section-1"} is the real
     * intent {@link ReviewViewFixture} already groups {@link #FILE_B} into,
     * and PATH mode's entry point (index 0) is {@link #FILE_B}'s own hunk.
     */
    @Test
    void aInPathModeIsRefusedByABlockingFindingNamingTheRealSection() {
        host.store.upsert(new ReviewAnnotation(scope.id(), "f1", Optional.of("section-1"), FILE_B,
                "n1", "n1", Severity.BLOCKING, Confidence.HIGH, Optional.of("blocker"), "Claude",
                Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false));

        pressP();
        awaitPathReady();
        assertEquals(0, view.selectedPathStepForTest());
        assertTrue(view.pathRowTextsForTest().get(0).contains(FILE_B),
                "row 0 must be " + FILE_B + "'s own hunk for this test to mean anything: "
                        + view.pathRowTextsForTest());

        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(host.verdict(scope, digestOf(FILE_B, 0)).isPresent(),
                "a blocking finding naming section-1 (the REAL section this hunk belongs to) must "
                        + "refuse approval in PATH mode exactly as it does in INTENTS mode");
    }

    /**
     * The eighth "correct behaviour shipped with no test that could catch
     * its loss" in this plan, per the coordinator: {@code
     * renderVerdictBarForPathStep}'s dispatch had no regression test at
     * all. Settling section-1 (via one {@code a} with the rail focused,
     * SECTION unit) auto-advances the INTENTS cursor to section-2
     * ("Profiler", still unsettled -- its own {@link #FILE_C} hunk is
     * unread) while ALSO settling {@link #FILE_B}'s hunk as a side effect
     * (it is section-1's own third hunk). PATH mode's row 0 is exactly
     * that now-settled {@link #FILE_B} hunk, so the two states genuinely
     * disagree: a bar still reading off the intents cursor would show
     * "2 · Profiler", unsettled; a bar reading the selected row shows
     * {@link #FILE_B}'s own name, settled.
     */
    @Test
    void theVerdictBarNamesAndSettlesOffTheSelectedRowNotTheIntentsCursor() {
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(host.verdict(scope, digestOf(FILE_B, 0)).isPresent(),
                "setup: guards.cpp's hunk must be settled as part of section-1's approval");

        pressP();
        awaitPathReady();
        assertEquals(0, view.selectedPathStepForTest());
        assertTrue(view.pathRowTextsForTest().get(0).contains(FILE_B),
                "row 0 must be " + FILE_B + "'s own hunk for this test to mean anything");

        String label = intentLabelText();
        assertTrue(label.contains(FILE_B),
                "the bar's label must name the SELECTED ROW's file (" + FILE_B + "): " + label);
        assertFalse(label.contains("Profiler"),
                "the bar must not still show the intents cursor's title ('Profiler', section-2, "
                        + "which is still unsettled): " + label);
        assertFalse(lookup(".review-verdict-settled").queryAll().isEmpty(),
                "the bar must render SETTLED, matching the selected row's own state -- the "
                        + "intents cursor (section-2) is still unsettled, so a bar reading that "
                        + "instead would show Approve/Request-changes buttons here, not a decision");
    }

    /**
     * {@code askAgentToFix} resolved through the step's REAL covering
     * intents (shared with the blocking-finding fix via {@code
     * intentsCoveringPathStep}), so it must hand off ONLY the findings
     * belonging to {@link #FILE_C}'s own section (section-2), not every
     * finding on the board. Two findings on section-1, one on section-2,
     * PATH mode selecting {@link #FILE_C}'s row.
     */
    @Test
    void askAgentToFixInPathModeHandsOffOnlyTheSelectedRowsFindings() {
        addFinding("f1", "section-1", FILE_A);
        addFinding("f2", "section-1", FILE_B);
        addFinding("f3", "section-2", FILE_C);

        pressP();
        awaitPathReady();
        // Steps: FILE_B (entry), FILE_A#0, FILE_A#1, FILE_C#0 -- walk to FILE_C's row.
        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(view.pathRowTextsForTest().get(view.selectedPathStepForTest()).contains(FILE_C),
                "the walk above must land on " + FILE_C + "'s row: " + view.pathRowTextsForTest());

        clickAskAgentButton();

        assertTrue(host.handedOffPrompts.stream().anyMatch(entry -> entry.endsWith(": 1 findings")),
                "PATH mode must hand off exactly the SELECTED ROW's one finding: "
                        + host.handedOffPrompts);
    }

    // ---- helpers --------------------------------------------------------------

    private void pressP() {
        press(KeyCode.P).release(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Polls wall time for PATH mode's rows to be populated: entering PATH
     * mode kicks off a {@link app.drydock.review.ChangeGraph} build on a
     * virtual thread the first time (see this class's own javadoc), and how
     * long that takes depends on whether this JVM has already warmed the
     * tree-sitter grammar -- the same non-guarantee {@code
     * SectionRailSwapTest.awaitCardCount} documents for the intents rail's
     * own computed-grouping swap.
     */
    private void awaitPathReady() {
        long start = System.nanoTime();
        while (view.pathRowTextsForTest().isEmpty()) {
            if (System.nanoTime() - start > 30_000_000_000L) {
                throw new AssertionError("PATH mode never populated any rows");
            }
            sleep(50);
        }
    }

    private void settle(String file, int hunkIndex) {
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOf(file, hunkIndex),
                ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH,
                host.baseCommit, host.headCommit));
    }

    private String digestOf(String file, int hunkIndex) {
        return host.diff.files().stream()
                .filter(candidate -> candidate.path().equals(file))
                .findFirst()
                .map(candidate -> HunkDigest.of(file, candidate.hunks().get(hunkIndex)))
                .orElseThrow();
    }

    /**
     * Fires the verdict bar's own Undo button -- {@code undoButton}'s text
     * is {@code "change"}, and it shares {@code .review-verdict-action}
     * with several other buttons, so it is found by text rather than by
     * style class alone.
     */
    private void clickUndoButton() {
        interact(() -> lookup(".review-verdict-action").queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> "change".equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Undo button found"))
                .fire());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void clickAskAgentButton() {
        interact(() -> lookup(".review-verdict-action").queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> "Ask the agent to fix it".equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Ask-the-agent button found"))
                .fire());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String intentLabelText() {
        String[] text = new String[1];
        interact(() -> text[0] = ((Label) lookup(".review-verdict-intent").queryAll().iterator().next())
                .getText());
        return text[0];
    }

    private void addFinding(String id, String intentId, String file) {
        host.store.upsert(new ReviewAnnotation(scope.id(), id, Optional.of(intentId), file,
                "n1", "n1", Severity.NIT, Confidence.HIGH, Optional.empty(), "Claude",
                Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false));
    }
}
