package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewEditorMarkModel.FileMarks;
import app.drydock.intellij.review.ReviewEditorMarkModel.Group;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless T10 tests for the pure source-editor mark model (AC5): the
 * triage/status and path filters, the no-guessing line policy delegated to
 * {@code ReviewFindingPlacement.sourceEditorLine}, the deterministic
 * grouping of findings that share a source line, and the defensive copies
 * and constructor invariants that keep the public values truthful. No
 * editor, no Swing, no store — the fixtures hand-build the same {@code
 * UnifiedDiff} shape {@code DiffService} parses.
 */
class ReviewEditorMarkModelTest {

    private static final Instant AT = Instant.EPOCH;
    private static final String SCOPE = "s1";

    // --- fixtures -----------------------------------------------------------

    private static UnifiedDiff.Line ctx(int oldLine, int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), "    context " + newLine);
    }

    private static UnifiedDiff.Line add(int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), "+   added " + newLine);
    }

    private static UnifiedDiff.Line del(int oldLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), "-   deleted " + oldLine);
    }

    /**
     * A file whose rows are the line keys o2, n1..n3, o9, n9: context and
     * additions live in the new file, deletions are old-side-only.
     */
    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 1, false, false, List.of(new UnifiedDiff.Hunk(
                "@@ -1,4 +1,4 @@", List.of(ctx(1, 1), del(2), add(2), ctx(3, 3), ctx(4, 4)))));
    }

    private static UnifiedDiff diff(UnifiedDiff.FileDiff... files) {
        return new UnifiedDiff(List.of(files));
    }

    private static ReviewAnnotation finding(String id, String file, String startKey, Severity severity) {
        return finding(id, file, startKey, severity, AnnotationStatus.OPEN, Triage.CONFIRMED);
    }

    private static ReviewAnnotation finding(String id, String file, String startKey, Severity severity,
                                            AnnotationStatus status, Triage triage) {
        return new ReviewAnnotation(SCOPE, id, file, startKey, startKey, severity,
                Confidence.HIGH, Optional.of("Title " + id), "tester", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), status, Optional.empty(), false, triage, Optional.empty());
    }

    // --- who gets a mark -----------------------------------------------------

    @Test
    void openNewSideFindingsGetGroupedPerPathAtZeroBasedSourceLines() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java"), file("src/B.java")),
                List.of(finding("f1", "src/A.java", "n2", Severity.NIT),
                        finding("f2", "src/A.java", "n4", Severity.BLOCKING),
                        finding("f3", "src/B.java", "n1", Severity.QUESTION)));

        assertEquals(List.of("src/A.java", "src/B.java"), List.copyOf(model.byPath().keySet()));
        FileMarks a = model.forPath("src/A.java").orElseThrow();
        assertEquals(2, a.byLine().size());
        // n<N> is a 1-based git line; the source editor line is N-1.
        assertEquals(Set.of(1, 3), a.byLine().keySet());
        assertEquals(1, a.byLine().get(1).count());
        assertEquals(List.of("f1"), a.byLine().get(1).findingIds());
        assertEquals(3, a.byLine().get(3).line());

        assertEquals(0, model.forPath("src/B.java").orElseThrow().byLine().keySet().iterator().next());
    }

    @Test
    void proposedAndConfirmedUnresolvedFindingsAreBothMarked() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(diff(file("src/A.java")), List.of(
                finding("f1", "src/A.java", "n1", Severity.NIT, AnnotationStatus.OPEN, Triage.PROPOSED),
                finding("f2", "src/A.java", "n2", Severity.NIT, AnnotationStatus.SENT, Triage.PROPOSED),
                finding("f3", "src/A.java", "n3", Severity.NIT, AnnotationStatus.OPEN, Triage.CONFIRMED),
                finding("f4", "src/A.java", "n4", Severity.NIT, AnnotationStatus.ADDRESSED, Triage.CONFIRMED),
                finding("f5", "src/A.java", "n1", Severity.NIT, AnnotationStatus.SENT, Triage.CONFIRMED)));

        assertEquals(5, model.byPath().get("src/A.java").byLine().values().stream()
                .mapToInt(Group::count).sum());
    }

    @Test
    void dismissedAndResolvedFindingsAreExcluded() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(diff(file("src/A.java")), List.of(
                finding("f1", "src/A.java", "n1", Severity.NIT, AnnotationStatus.OPEN, Triage.DISMISSED),
                finding("f2", "src/A.java", "n2", Severity.NIT, AnnotationStatus.RESOLVED, Triage.CONFIRMED),
                finding("f3", "src/A.java", "n3", Severity.NIT, AnnotationStatus.FIXED, Triage.PROPOSED),
                finding("f4", "src/A.java", "n4", Severity.NIT, AnnotationStatus.RESOLVED, Triage.DISMISSED)));

        assertTrue(model.isEmpty(), "no finding qualifies, so no file carries a mark");
    }

    // --- which line, never a guess -------------------------------------------

    @Test
    void oldSideOnlyKeysGetNoSourceLine() {
        // o2 is a row key of the current diff — a real deleted line — but
        // the working file does not contain it, so no editor has that line.
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java")), List.of(finding("f1", "src/A.java", "o2", Severity.BLOCKING)));

        assertTrue(model.isEmpty());
    }

    @Test
    void driftedNewSideKeysAreExcludedNotReAnchored() {
        // n5 parses as a new-side key but is not a row key of this diff:
        // given against another base. It must not fall back to any line —
        // not line 0, not the nearest row, nowhere.
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java")), List.of(finding("f1", "src/A.java", "n5", Severity.BLOCKING)));

        assertTrue(model.isEmpty());
        assertFalse(model.forPath("src/A.java").isPresent());
    }

    @Test
    void malformedKeysAreValuesNotCrashes() {
        String[] malformed = {"", "n", "o", "x2", "n+3", "n-3", "n0", "o0", "nabc", "n999999999999"};
        for (String key : malformed) {
            ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                    diff(file("src/A.java")), List.of(finding("f1", "src/A.java", key, Severity.BLOCKING)));
            assertTrue(model.isEmpty(), "a malformed key must not crash or guess: " + key);
        }
    }

    @Test
    void findingsOnFilesOutsideTheDiffGetNoMarks() {
        ReviewEditorMarkModel otherFile = ReviewEditorMarkModel.of(
                diff(file("src/A.java")), List.of(finding("f1", "src/Other.java", "n1", Severity.NIT)));
        assertTrue(otherFile.isEmpty());

        ReviewEditorMarkModel emptyDiff = ReviewEditorMarkModel.of(
                diff(), List.of(finding("f1", "src/A.java", "n1", Severity.NIT)));
        assertTrue(emptyDiff.isEmpty());
    }

    // --- deterministic grouping ----------------------------------------------

    @Test
    void findingsSharingALineCollapseIntoOneDeterministicGroup() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java")),
                List.of(finding("f1", "src/A.java", "n2", Severity.NIT),
                        finding("f2", "src/A.java", "n2", Severity.BLOCKING),
                        finding("f3", "src/A.java", "n2", Severity.DEVIATION)));

        Group group = model.forPath("src/A.java").orElseThrow().byLine().get(1);
        assertEquals(3, group.count());
        // The worst severity in force: Severity's own order is
        // BLOCKING > QUESTION > DEVIATION > NIT.
        assertEquals(Severity.BLOCKING, group.severity());
        // Every id, in the store's list order — the tree's own child order.
        assertEquals(List.of("f1", "f2", "f3"), group.findingIds());
        // The navigation target is the first id of that order.
        assertEquals("f1", group.navigationFindingId());
        assertEquals("3 findings on this line:\n"
                        + "• nit: Title f1\n"
                        + "• blocking: Title f2\n"
                        + "• deviation: Title f3",
                group.tooltip());
    }

    @Test
    void theGroupFollowsTheStoreOrderItWasFedNotTheIdsAlphabeticalOrder() {
        // Same three findings, fed in the reverse order: the group's order
        // is the input's, so a different input order is a different (still
        // deterministic) group — never a hidden sort that would disagree
        // with the tree's children.
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java")),
                List.of(finding("f3", "src/A.java", "n2", Severity.DEVIATION),
                        finding("f2", "src/A.java", "n2", Severity.BLOCKING),
                        finding("f1", "src/A.java", "n2", Severity.NIT)));

        Group group = model.forPath("src/A.java").orElseThrow().byLine().get(1);
        assertEquals(List.of("f3", "f2", "f1"), group.findingIds());
        assertEquals("f3", group.navigationFindingId());
        assertEquals("3 findings on this line:\n"
                        + "• deviation: Title f3\n"
                        + "• blocking: Title f2\n"
                        + "• nit: Title f1",
                group.tooltip());
    }

    @Test
    void aSingleFindingGroupCarriesItsTitleAsTheTooltip() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java")), List.of(finding("f1", "src/A.java", "n3", Severity.QUESTION)));

        Group group = model.forPath("src/A.java").orElseThrow().byLine().get(2);
        assertEquals(1, group.count());
        assertEquals("Title f1", group.tooltip());
        assertEquals(Severity.QUESTION, group.severity());
    }

    @Test
    void theSeverityOverrideIsTheSeverityInForce() {
        ReviewAnnotation overridden = finding("f1", "src/A.java", "n1", Severity.BLOCKING)
                .withSeverityOverride(Severity.NIT);
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(diff(file("src/A.java")), List.of(overridden));

        assertEquals(Severity.NIT,
                model.forPath("src/A.java").orElseThrow().byLine().get(0).severity());
    }

    @Test
    void groupsIterateInAscendingLineOrderAndPathsInSortedOrder() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/Z.java"), file("src/A.java")),
                List.of(finding("f1", "src/Z.java", "n4", Severity.NIT),
                        finding("f2", "src/Z.java", "n1", Severity.NIT),
                        finding("f3", "src/A.java", "n2", Severity.NIT)));

        assertEquals(List.of("src/A.java", "src/Z.java"), List.copyOf(model.byPath().keySet()));
        // The exposed map keeps the backing TreeMap's ascending line order.
        assertEquals(List.of(0, 3), List.copyOf(
                model.byPath().get("src/Z.java").byLine().keySet()));
    }

    @Test
    void rowKeysMergeAcrossHunksOfTheSamePath() {
        // Defensive pin: a FileDiff entry's rows are all its hunks' keys, so
        // a key that only a second hunk contains still addresses a line.
        UnifiedDiff.FileDiff twoHunks = new UnifiedDiff.FileDiff("src/A.java", "M", 1, 1, false,
                false, List.of(
                        new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(ctx(1, 1))),
                        new UnifiedDiff.Hunk("@@ -9 +9 @@", List.of(add(9)))));
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(twoHunks), List.of(finding("f1", "src/A.java", "n9", Severity.NIT)));

        assertTrue(model.forPath("src/A.java").orElseThrow().byLine().containsKey(8));
    }

    // --- truthfulness: defensive copies and invariants ------------------------

    @Test
    void theModelDoesNotRetainTheCallersFindingsList() {
        List<ReviewAnnotation> findings = new ArrayList<>();
        findings.add(finding("f1", "src/A.java", "n1", Severity.NIT));
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(diff(file("src/A.java")), findings);

        findings.clear();

        assertFalse(model.isEmpty());
        assertEquals(List.of("f1"),
                model.forPath("src/A.java").orElseThrow().byLine().get(0).findingIds());
    }

    @Test
    void recordConstructorsCopyTheirInputsDefensively() {
        List<String> ids = new ArrayList<>(List.of("f1", "f2"));
        Group group = new Group(4, Severity.NIT, 2, "t", ids);
        ids.clear();
        assertEquals(List.of("f1", "f2"), group.findingIds());
        assertEquals("f1", group.navigationFindingId());

        Map<Integer, Group> lines = new TreeMap<>();
        lines.put(4, group);
        FileMarks marks = new FileMarks("src/A.java", lines);
        lines.clear();
        assertEquals(Map.of(4, group), marks.byLine());
    }

    @Test
    void exposedMapsAreUnmodifiable() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java")), List.of(finding("f1", "src/A.java", "n1", Severity.NIT)));

        assertThrows(UnsupportedOperationException.class,
                () -> model.byPath().put("src/B.java", model.byPath().get("src/A.java")));
        assertThrows(UnsupportedOperationException.class,
                () -> model.byPath().get("src/A.java").byLine().put(7, null));
    }

    @Test
    void groupInvariantsKeepThePublicValuesTruthful() {
        assertThrows(NullPointerException.class, () -> new Group(0, null, 1, "t", List.of("f1")));
        assertThrows(NullPointerException.class, () -> new Group(0, Severity.NIT, 1, null, List.of("f1")));
        assertThrows(NullPointerException.class, () -> new Group(0, Severity.NIT, 1, "t", null));
        assertThrows(IllegalArgumentException.class, () -> new Group(-1, Severity.NIT, 1, "t", List.of("f1")));
        assertThrows(IllegalArgumentException.class, () -> new Group(0, Severity.NIT, 0, "t", List.of()));
        // A count that disagrees with the ids is a lie, not a rounding.
        assertThrows(IllegalArgumentException.class, () -> new Group(0, Severity.NIT, 2, "t", List.of("f1")));
        // A blank id is no navigation target.
        assertThrows(IllegalArgumentException.class, () -> new Group(0, Severity.NIT, 2, "t", List.of("f1", "")));
        // A null id is a null, refused by the defensive copy.
        assertThrows(NullPointerException.class, () -> new Group(0, Severity.NIT, 2, "t", Arrays.asList("f1", null)));
    }

    @Test
    void fileMarksInvariantsKeepThePublicValuesTruthful() {
        Group group = new Group(3, Severity.NIT, 1, "t", List.of("f1"));
        assertThrows(NullPointerException.class, () -> new FileMarks(null, Map.of(3, group)));
        assertThrows(NullPointerException.class, () -> new FileMarks("src/A.java", null));
        assertThrows(IllegalArgumentException.class, () -> new FileMarks("  ", Map.of(3, group)));
        // An empty file claims marks it does not have.
        assertThrows(IllegalArgumentException.class, () -> new FileMarks("src/A.java", Map.of()));
        // A group keyed by another line than its own is unaddressable.
        assertThrows(IllegalArgumentException.class, () -> new FileMarks("src/A.java", Map.of(4, group)));
    }

    @Test
    void aPathWithNoMarksIsAbsentNotEmpty() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(
                diff(file("src/A.java"), file("src/B.java")),
                List.of(finding("f1", "src/A.java", "n1", Severity.NIT),
                        finding("f2", "src/B.java", "o2", Severity.NIT)));

        assertTrue(model.byPath().containsKey("src/A.java"));
        assertFalse(model.byPath().containsKey("src/B.java"), "a marked entry claims marks");
        assertFalse(model.forPath("src/B.java").isPresent());
        assertFalse(model.forPath("src/None.java").isPresent());
        assertNull(model.byPath().get("src/None.java"));
        assertEquals(1, model.byPath().size());
    }

    @Test
    void emptyInputsYieldAnEmptyModel() {
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(diff(file("src/A.java")), List.of());
        assertTrue(model.isEmpty());
        assertTrue(model.byPath().isEmpty());
        assertFalse(model.forPath("src/A.java").isPresent());
    }
}
