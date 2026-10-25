package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewDiffRequests.ScrollTarget;
import app.drydock.intellij.review.ReviewFindingPlacement.LineKey;
import app.drydock.intellij.review.ReviewFindingPlacement.Placement;
import com.intellij.diff.util.Side;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless T3 tests for the pure placement policy: the centralized line-key
 * parsing, the exact-side and first-hunk-fallback placement (AC6), and the
 * source-editor position policy (AC5). No IDE state anywhere — the fixtures
 * hand-build the same {@link UnifiedDiff} shape {@code DiffService} parses.
 */
class ReviewFindingPlacementTest {

    private static final ScrollTarget FALLBACK = new ScrollTarget(Side.LEFT, 7);

    // --- fixtures ------------------------------------------------------------

    private static UnifiedDiff.Line context(int oldLine, int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), "ctx");
    }

    private static UnifiedDiff.Line add(int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), "added");
    }

    private static UnifiedDiff.Line del(int oldLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), "deleted");
    }

    private static UnifiedDiff.FileDiff file(UnifiedDiff.Line... lines) {
        return new UnifiedDiff.FileDiff("src/A.java", "M", 0, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", List.of(lines))));
    }

    /** The current row keys of a file: its hunks' line keys, like the tree groups them. */
    private static Set<String> rowKeys(UnifiedDiff.FileDiff file) {
        return file.hunks().stream()
                .flatMap(hunk -> hunk.lines().stream())
                .map(UnifiedDiff.Line::lineKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // --- the centralized parsing ---------------------------------------------

    @Test
    void parseMapsNewSideKeysToRightAndOldSideKeysToLeft() {
        LineKey nKey = ReviewFindingPlacement.parse("n12");
        assertEquals(Side.RIGHT, nKey.side());
        assertEquals(12, nKey.gitLine());

        LineKey oKey = ReviewFindingPlacement.parse("o7");
        assertEquals(Side.LEFT, oKey.side());
        assertEquals(7, oKey.gitLine());
    }

    @Test
    void parseIsStrictRefusingAnythingNotAOneBasedLineNumber() {
        for (String bad : new String[]{"x3", "n", "o", "nabc", "n-4", "n0", "", "42"}) {
            assertThrows(IllegalArgumentException.class, () -> ReviewFindingPlacement.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ReviewFindingPlacement.parse(null));
    }

    @Test
    void theStrictParseIsTheOneTheScrollTargetSeamDelegatesTo() {
        // ReviewDiffRequests.scrollTarget is the compatibility seam over the
        // centralized parsing: same mapping, same refusals.
        assertEquals(new ScrollTarget(Side.RIGHT, 11), ReviewDiffRequests.scrollTarget("n12"));
        assertEquals(new ScrollTarget(Side.LEFT, 6), ReviewDiffRequests.scrollTarget("o7"));
        assertThrows(IllegalArgumentException.class, () -> ReviewDiffRequests.scrollTarget("n-4"));
    }

    @Test
    void parseAcceptsOnlyDigitsAfterTheSideLetterSignedNumbersAreRefused() {
        // The old parse delegated to Integer.parseInt, which accepted
        // "n+5"/"o+5" as line 5 despite the documented [no]<digits>
        // grammar; the centralized parse is where that grammar is enforced.
        for (String bad : new String[]{"n+5", "o+5", "n+12", "o+12", "n+0", "o+0"}) {
            assertThrows(IllegalArgumentException.class, () -> ReviewFindingPlacement.parse(bad), bad);
        }
    }

    @Test
    void parsePreservesTheRejectionOfNegativesAndWhitespace() {
        for (String bad : new String[]{"n-4", "o-4", "n 5", "o 5", "n5 ", " n5", "\tn5"}) {
            assertThrows(IllegalArgumentException.class, () -> ReviewFindingPlacement.parse(bad), bad);
        }
    }

    @Test
    void theScrollTargetSeamRefusesSignedAndPaddedKeysLikeTheStrictParse() {
        // The compatibility seam delegates to the centralized parsing, so
        // the digits-only grammar applies to it too — the old scrollTarget
        // accepted "n+5" via Integer.parseInt.
        assertThrows(IllegalArgumentException.class, () -> ReviewDiffRequests.scrollTarget("n+5"));
        assertThrows(IllegalArgumentException.class, () -> ReviewDiffRequests.scrollTarget("o+5"));
        assertThrows(IllegalArgumentException.class, () -> ReviewDiffRequests.scrollTarget("n-4"));
        assertThrows(IllegalArgumentException.class, () -> ReviewDiffRequests.scrollTarget("n 5"));
    }

    // --- exact placement ------------------------------------------------------

    @Test
    void aPresentNewSideKeyPlacesOnTheRightOneLineBeforeTheGitLine() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2), add(3), context(4, 4));
        Placement placement = ReviewFindingPlacement.placement("n2", rowKeys(file), FALLBACK);
        assertEquals(Side.RIGHT, placement.side());
        assertEquals(1, placement.line());
        assertFalse(placement.drifted());
        assertEquals(Optional.empty(), placement.driftHint());
    }

    @Test
    void aPresentOldSideKeyPlacesOnTheLeftOneLineBeforeTheGitLine() {
        UnifiedDiff.FileDiff file = file(context(2, 2), del(3), del(4));
        Placement placement = ReviewFindingPlacement.placement("o3", rowKeys(file), FALLBACK);
        assertEquals(Side.LEFT, placement.side());
        assertEquals(2, placement.line());
        assertFalse(placement.drifted());
        assertEquals(Optional.empty(), placement.driftHint());
    }

    @Test
    void exactPlacementAgreesWithTheCentralScrollTargetMapping() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(42), context(43, 2));
        Placement placement = ReviewFindingPlacement.placement("n42", rowKeys(file), FALLBACK);
        ScrollTarget scroll = ReviewDiffRequests.scrollTarget("n42");
        assertEquals(scroll.side(), placement.side());
        assertEquals(scroll.line(), placement.line());
    }

    // --- the drift fallback (AC6) ---------------------------------------------

    @Test
    void aKeyTheCurrentDiffNoLongerContainsFallsBackWithTheDriftHint() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2));
        Set<String> rowKeys = rowKeys(file); // holds "n1", "n2", "o1" — not "n99"
        Placement placement = ReviewFindingPlacement.placement("n99", rowKeys, FALLBACK);
        assertEquals(Side.LEFT, placement.side());
        assertEquals(7, placement.line());
        assertTrue(placement.drifted());
        assertEquals(Optional.of(ReviewTabModel.DRIFT_HINT), placement.driftHint());
    }

    @Test
    void anOldSideKeyMissingFromTheRowKeysFallsBackWithTheDriftHint() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2));
        Placement placement = ReviewFindingPlacement.placement("o3", rowKeys(file), FALLBACK);
        assertTrue(placement.drifted());
        assertEquals(Optional.of(ReviewTabModel.DRIFT_HINT), placement.driftHint());
    }

    @Test
    void malformedKeysAreDriftValuesNotCrashes() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2));
        Set<String> rowKeys = rowKeys(file);
        for (String bad : new String[]{"x3", "n", "nabc", "n-4", "n0", "", "42", null}) {
            Placement placement = ReviewFindingPlacement.placement(bad, rowKeys, FALLBACK);
            assertEquals(Side.LEFT, placement.side(), bad);
            assertEquals(7, placement.line(), bad);
            assertTrue(placement.drifted(), bad);
            assertEquals(Optional.of(ReviewTabModel.DRIFT_HINT), placement.driftHint(), bad);
        }
    }

    // --- the first-hunk fallback target ---------------------------------------

    @Test
    void firstHunkFallbackTargetsTheFirstChangedLineOfTheFirstChangedHunk() {
        // The first hunk's first changed line is the deletion, not the
        // addition behind it.
        UnifiedDiff.FileDiff file = file(context(1, 1), del(2), add(3));
        ScrollTarget fallback = ReviewFindingPlacement.firstHunkFallback(file);
        assertEquals(Side.LEFT, fallback.side());
        assertEquals(1, fallback.line());
    }

    @Test
    void firstHunkFallbackStaysInTheFirstHunkEvenWhenALaterHunkHoldsTheFirstAdd() {
        // Unlike the file-row scroll target (first added line anywhere in
        // the file), the drift fallback belongs to the file's first hunk.
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/B.java", "M", 2, 1, false, false,
                List.of(
                        new UnifiedDiff.Hunk("@@", List.of(context(1, 1), del(2), del(3))),
                        new UnifiedDiff.Hunk("@@", List.of(context(4, 2), add(3)))));
        ScrollTarget fallback = ReviewFindingPlacement.firstHunkFallback(file);
        assertEquals(Side.LEFT, fallback.side());
        assertEquals(1, fallback.line()); // hunk 1's first deleted line (oldLine 2), minus 1
    }

    @Test
    void firstHunkFallbackUsesTheRightSideForAdditions() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2));
        ScrollTarget fallback = ReviewFindingPlacement.firstHunkFallback(file);
        assertEquals(Side.RIGHT, fallback.side());
        assertEquals(1, fallback.line());
    }

    @Test
    void firstHunkFallbackFallsToTheTopOfTheRightSideWithoutChangedLines() {
        UnifiedDiff.FileDiff file = file(context(1, 1), context(2, 2));
        ScrollTarget fallback = ReviewFindingPlacement.firstHunkFallback(file);
        assertEquals(Side.RIGHT, fallback.side());
        assertEquals(0, fallback.line());
    }

    // --- the source-editor position (AC5) -------------------------------------

    @Test
    void aValidPresentNewSideKeyAddressesAZeroBasedSourceLine() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2), add(3));
        OptionalInt line = ReviewFindingPlacement.sourceEditorLine("n2", rowKeys(file));
        assertTrue(line.isPresent());
        assertEquals(1, line.getAsInt());
    }

    @Test
    void oldSideKeysNeverAddressASourceLineEvenWhenPresent() {
        UnifiedDiff.FileDiff file = file(context(2, 2), del(3));
        // "o3" is a row key of the current diff — still the left (deleted)
        // side, which the working file does not contain.
        assertTrue(rowKeys(file).contains("o3"));
        assertFalse(ReviewFindingPlacement.sourceEditorLine("o3", rowKeys(file)).isPresent());
    }

    @Test
    void driftedNewSideKeysNeverAddressASourceLine() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2));
        // The key parses, but the current diff does not contain it: the
        // finding was given against another base.
        assertFalse(ReviewFindingPlacement.sourceEditorLine("n99", rowKeys(file)).isPresent());
    }

    @Test
    void malformedKeysNeverAddressASourceLine() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2));
        Set<String> rowKeys = rowKeys(file);
        for (String bad : new String[]{"x3", "n", "nabc", "n-4", "n0", "", "42", null}) {
            assertFalse(ReviewFindingPlacement.sourceEditorLine(bad, rowKeys).isPresent(), bad);
        }
    }

    // --- parameter contracts ---------------------------------------------------

    @Test
    void placementRequiresItsRowKeysAndFallbackNotNull() {
        assertThrows(NullPointerException.class,
                () -> ReviewFindingPlacement.placement("n2", null, FALLBACK));
        assertThrows(NullPointerException.class,
                () -> ReviewFindingPlacement.placement("n2", Set.of("n2"), null));
        assertThrows(NullPointerException.class,
                () -> ReviewFindingPlacement.firstHunkFallback(null));
        assertThrows(NullPointerException.class,
                () -> ReviewFindingPlacement.sourceEditorLine("n2", null));
    }

    @Test
    void rowKeysAreMatchedByExactStringNeverByParse() {
        // Placement matches keys as strings, never by re-parsing them:
        // "n5" is not the row key "n+5" (which does not even parse under
        // the digits-only grammar), so the finding drifts instead of being
        // re-anchored through an equivalent parse.
        Set<String> rowKeys = Set.of("n+5");
        Placement placement = ReviewFindingPlacement.placement("n5", rowKeys, FALLBACK);
        assertTrue(placement.drifted());
    }
}
