package app.drydock.ui.review;

import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewVerdict;
import app.drydock.ui.ShortcutsOverlay;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
