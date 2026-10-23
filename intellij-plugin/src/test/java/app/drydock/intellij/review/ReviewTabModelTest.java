package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure snapshot behind the Review tab's rows and marks (spec AC5-AC8):
 * every mark rule, the finding children, the footer and the header counts,
 * against hand-built diffs -- no git, no IDE. The snapshot is the contract
 * the tree (T12) and the header (T13) render, so this table is what they are
 * tested through.
 */
class ReviewTabModelTest {

    private static final Instant AT = Instant.EPOCH;
    private static final String SCOPE = "s1";
    private static final String BASE = "base-1";
    private static final String OLD_BASE = "base-0";

    private int seq;

    // ---- fixtures -----------------------------------------------------------

    private static UnifiedDiff.Line ctx(int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line add(int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line del(int oldLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), text);
    }

    private static UnifiedDiff.Hunk hunk(UnifiedDiff.Line... lines) {
        return new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(lines));
    }

    private static UnifiedDiff.FileDiff file(String path, boolean untracked,
                                              UnifiedDiff.Hunk... hunks) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 1, false, untracked, List.of(hunks));
    }

    private static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Hunk... hunks) {
        return file(path, false, hunks);
    }

    private static UnifiedDiff diff(UnifiedDiff.FileDiff... files) {
        return new UnifiedDiff(List.of(files));
    }

    private ReviewAnnotation finding(String file, String startKey, String endKey, Severity severity,
                                     String title, AnnotationStatus status, Triage triage) {
        return new ReviewAnnotation(SCOPE, "f" + seq++, file, startKey, endKey, severity,
                Confidence.HIGH, Optional.of(title), "tester", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), status, Optional.empty(), false, triage, Optional.empty());
    }

    private ReviewAnnotation open(String file, String startKey, String endKey, Severity severity,
                                  String title) {
        return finding(file, startKey, endKey, severity, title, AnnotationStatus.OPEN, Triage.CONFIRMED);
    }

    private static ReviewVerdict verdict(String hunkDigest, ReviewVerdict.Decision decision,
                                         String baseCommit) {
        return new ReviewVerdict(SCOPE, hunkDigest, decision, Optional.empty(), AT, baseCommit, "head");
    }

    /** The one-file, two-hunk diff most mark cases run against. */
    private static final UnifiedDiff.Line[] HUNK_A_LINES = {
            ctx(1, 1, "package p;"),
            add(2, "int answer = 42;"),
    };
    private static final UnifiedDiff.Line[] HUNK_B_LINES = {
            del(9, "int stale = 41;"),
            ctx(10, 10, "}"),
    };

    private static String digestA() {
        return HunkDigest.of("a.txt", hunk(HUNK_A_LINES));
    }

    private static String digestB() {
        return HunkDigest.of("a.txt", hunk(HUNK_B_LINES));
    }

    private static ReviewTabModel.FileRow row(ReviewTabModel.Snapshot snapshot, String path) {
        return snapshot.files().stream()
                .filter(row -> row.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + path));
    }

    private static ReviewTabModel.Snapshot snapshot(UnifiedDiff diff,
                                                    List<ReviewAnnotation> findings,
                                                    List<ReviewVerdict> verdicts,
                                                    Optional<String> currentBase) {
        return ReviewTabModel.snapshot(new ReviewTabModel.Inputs(diff, findings, verdicts, currentBase));
    }

    // ---- AC6: one mark per row, precedence Q3 -------------------------------

    /**
     * The mark table. Open findings outrank approvals (Q3): a file with both
     * shows its findings, never a silent {@code ✓}.
     */
    @Test
    void markTable() {
        record Case(String name, ReviewTabModel.Inputs inputs,
                    ReviewTabModel.Mark.Kind kind, String text, Optional<String> hint) {
        }

        ReviewTabModel.Inputs twoHunks = new ReviewTabModel.Inputs(
                diff(file("a.txt", hunk(HUNK_A_LINES), hunk(HUNK_B_LINES))),
                List.of(), List.of(), Optional.empty());

        List<Case> cases = List.of(
                new Case("open findings count only unresolved, undismissed ones",
                        new ReviewTabModel.Inputs(twoHunks.diff(),
                                List.of(open("a.txt", "n1", "n2", Severity.BLOCKING, "leak"),
                                        open("a.txt", "n2", "n2", Severity.NIT, "style"),
                                        finding("a.txt", "n3", "n3", Severity.NIT, "done",
                                                AnnotationStatus.RESOLVED, Triage.CONFIRMED),
                                        finding("a.txt", "n4", "n4", Severity.NIT, "won't fix",
                                                AnnotationStatus.OPEN, Triage.DISMISSED)),
                                List.of(), Optional.empty()),
                        ReviewTabModel.Mark.Kind.OPEN_FINDINGS, "◆ 2", Optional.empty()),
                new Case("open findings outrank full approval",
                        new ReviewTabModel.Inputs(twoHunks.diff(),
                                List.of(open("a.txt", "n1", "n2", Severity.QUESTION, "why")),
                                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED, BASE),
                                        verdict(digestB(), ReviewVerdict.Decision.APPROVED, BASE)),
                                Optional.of(BASE)),
                        ReviewTabModel.Mark.Kind.OPEN_FINDINGS, "◆ 1", Optional.empty()),
                new Case("every hunk approved and fresh is reviewed",
                        new ReviewTabModel.Inputs(twoHunks.diff(), List.of(),
                                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED, BASE),
                                        verdict(digestB(), ReviewVerdict.Decision.APPROVED, BASE)),
                                Optional.of(BASE)),
                        ReviewTabModel.Mark.Kind.REVIEWED, "✓", Optional.empty()),
                new Case("an auto-approval that covers everything is reviewed",
                        new ReviewTabModel.Inputs(twoHunks.diff(), List.of(),
                                List.of(verdict(digestA(), ReviewVerdict.Decision.AUTO_APPROVED, BASE),
                                        verdict(digestB(), ReviewVerdict.Decision.AUTO_APPROVED, BASE)),
                                Optional.of(BASE)),
                        ReviewTabModel.Mark.Kind.REVIEWED, "✓", Optional.empty()),
                new Case("one unsettled hunk leaves the row unreviewed",
                        new ReviewTabModel.Inputs(twoHunks.diff(), List.of(),
                                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED, BASE)),
                                Optional.of(BASE)),
                        ReviewTabModel.Mark.Kind.UNREVIEWED, "○", Optional.empty()),
                new Case("a changes request carries its own tooltip",
                        new ReviewTabModel.Inputs(twoHunks.diff(), List.of(),
                                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED, BASE),
                                        verdict(digestB(), ReviewVerdict.Decision.CHANGES, BASE)),
                                Optional.of(BASE)),
                        ReviewTabModel.Mark.Kind.UNREVIEWED, "○",
                        Optional.of(ReviewTabModel.CHANGES_HINT)),
                new Case("a stale verdict is not reviewed",
                        new ReviewTabModel.Inputs(twoHunks.diff(), List.of(),
                                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED, OLD_BASE),
                                        verdict(digestB(), ReviewVerdict.Decision.APPROVED, OLD_BASE)),
                                Optional.of(BASE)),
                        ReviewTabModel.Mark.Kind.UNREVIEWED, "○",
                        Optional.of(ReviewTabModel.STALE_HINT)),
                new Case("a missing base commit vouches for nothing (Q4, conservative)",
                        new ReviewTabModel.Inputs(twoHunks.diff(), List.of(),
                                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED, BASE),
                                        verdict(digestB(), ReviewVerdict.Decision.APPROVED, BASE)),
                                Optional.empty()),
                        ReviewTabModel.Mark.Kind.UNREVIEWED, "○",
                        Optional.of(ReviewTabModel.STALE_HINT)),
                new Case("a binary or mode-only file has nothing to review",
                        new ReviewTabModel.Inputs(diff(file("a.bin")),
                                List.of(), List.of(), Optional.of(BASE)),
                        ReviewTabModel.Mark.Kind.NO_TEXT_CHANGES, "no text changes to review",
                        Optional.empty()));

        for (Case c : cases) {
            ReviewTabModel.Snapshot snapshot = ReviewTabModel.snapshot(c.inputs());
            ReviewTabModel.FileRow row = row(snapshot, c.inputs().diff().files().get(0).path());
            assertEquals(c.kind(), row.mark().kind(), c.name() + ": kind");
            assertEquals(c.text(), row.mark().text(), c.name() + ": text");
            if (c.hint().isPresent()) {
                assertTrue(row.mark().hint().isPresent(), c.name() + ": hint expected");
                assertTrue(row.mark().hint().get().contains(c.hint().get()),
                        c.name() + ": hint '" + row.mark().hint().orElse("") + "' lacks '" + c.hint().get() + "'");
            }
        }
    }

    // ---- AC7: finding children, hidden footer, drift hint -------------------

    /** Open findings become children: severity, title, and the line their start key addresses. */
    @Test
    void openFindingsAreChildrenWithSeverityTitleAndLine() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(HUNK_A_LINES))),
                List.of(open("a.txt", "n2", "n2", Severity.BLOCKING, "leak"),
                        open("a.txt", "o9", "o9", Severity.NIT, "style")),
                List.of(), Optional.empty());

        ReviewTabModel.FileRow row = row(snapshot, "a.txt");
        assertEquals(2, row.findings().size());
        ReviewTabModel.FindingRow leaked = row.findings().get(0);
        assertEquals("leak", leaked.title());
        assertEquals(Severity.BLOCKING, leaked.severity());
        assertEquals(Optional.of(2), leaked.startLine());
        assertTrue(leaked.driftHint().isEmpty());
        ReviewTabModel.FindingRow styled = row.findings().get(1);
        assertEquals(Severity.NIT, styled.severity());
        assertEquals(Optional.of(9), styled.startLine());
    }

    /**
     * Resolved and dismissed findings are hidden from the children and
     * counted in the footer (spec, surface 7) -- both, because the footer's
     * number is what tells the human something was swept out of view.
     */
    @Test
    void resolvedAndDismissedFindingsAreHiddenWithAFooter() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(HUNK_A_LINES))),
                List.of(open("a.txt", "n2", "n2", Severity.NIT, "style"),
                        finding("a.txt", "n3", "n3", Severity.NIT, "fixed",
                                AnnotationStatus.RESOLVED, Triage.CONFIRMED),
                        finding("a.txt", "n4", "n4", Severity.NIT, "not a finding",
                                AnnotationStatus.OPEN, Triage.DISMISSED)),
                List.of(), Optional.empty());

        ReviewTabModel.FileRow row = row(snapshot, "a.txt");
        assertEquals(1, row.findings().size());
        assertEquals("style", row.findings().get(0).title());
        assertEquals(Optional.of("2 resolved findings hidden"), row.resolvedFindingsFooter());
    }

    /**
     * A finding whose start key matches no row key of the current diff keeps
     * its key -- never migrated -- and carries the drift hint ({@code
     * kb/find-finding-anchor-drift-gap}: exact match only).
     */
    @Test
    void aFindingOffDiffKeepsItsKeyAndGainsTheDriftHint() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(HUNK_A_LINES))),
                List.of(open("a.txt", "n99", "n99", Severity.BLOCKING, "leak")),
                List.of(), Optional.empty());

        ReviewTabModel.FindingRow drifted = row(snapshot, "a.txt").findings().get(0);
        assertEquals("n99", drifted.startKey());
        assertEquals(Optional.of(ReviewTabModel.DRIFT_HINT), drifted.driftHint());
    }

    /** A key that exists in the diff (added or deleted line) carries no hint. */
    @Test
    void anOnDiffDeletedLineKeyIsNotDrift() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(HUNK_B_LINES))),
                List.of(open("a.txt", "o9", "o9", Severity.NIT, "gone")),
                List.of(), Optional.empty());

        assertTrue(row(snapshot, "a.txt").findings().get(0).driftHint().isEmpty());
    }

    // ---- AC8: header counts --------------------------------------------------

    /** n counts only files with at least one hunk; k counts the reviewed ones. */
    @Test
    void headerCountsFilesWithHunksOnly() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(HUNK_A_LINES)),
                        file("b.txt", hunk(HUNK_A_LINES)),
                        file("c.bin")),
                List.of(),
                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED, BASE)),
                Optional.of(BASE));

        assertEquals(2, snapshot.reviewableFiles());
        assertEquals(1, snapshot.reviewedFiles());
        assertEquals("1 / 2 reviewed", snapshot.headerText());
    }

    /** An empty snapshot reads a zeroed header, not a crash. */
    @Test
    void anEmptySnapshotReadsZeroReviewed() {
        ReviewTabModel.Snapshot snapshot = snapshot(diff(), List.of(), List.of(), Optional.empty());

        assertEquals(List.of(), snapshot.files());
        assertEquals("0 / 0 reviewed", snapshot.headerText());
    }

    // ---- AC5: display-only IDE-noise filter ----------------------------------

    /**
     * Only untracked IDE files are hidden: an untracked {@code .idea/} file
     * and an untracked {@code .iml} vanish, a tracked, modified {@code
     * .idea/codeStyles/Project.xml} stays and reviews like any other file.
     */
    @Test
    void untrackedIdeFilesAreHiddenButTrackedOnesAreShown() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file(".idea/workspace.xml", true),
                        file("z.iml", true),
                        file(".idea/codeStyles/Project.xml", hunk(HUNK_A_LINES)),
                        file("src/Main.java", hunk(HUNK_A_LINES))),
                List.of(), List.of(), Optional.empty());

        assertEquals(List.of(".idea/codeStyles/Project.xml", "src/Main.java"),
                snapshot.files().stream().map(ReviewTabModel.FileRow::path).toList());
    }

    /** Rows come out in path order, whatever order the diff arrived in. */
    @Test
    void rowsAreInPathOrder() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("src/Main.java"),
                        file("a.txt"),
                        file(".idea/codeStyles/Project.xml")),
                List.of(), List.of(), Optional.empty());

        assertEquals(List.of(".idea/codeStyles/Project.xml", "a.txt", "src/Main.java"),
                snapshot.files().stream().map(ReviewTabModel.FileRow::path).toList());
    }

    /** Hiding a file changes no other file's hunk ids or digests (display-only). */
    @Test
    void hidingUntrackedIdeFilesLeavesOtherRowsIdsIntact() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file(".idea/workspace.xml", true, hunk(HUNK_A_LINES)),
                        file("a.txt", hunk(HUNK_A_LINES), hunk(HUNK_B_LINES))),
                List.of(), List.of(), Optional.empty());

        ReviewTabModel.FileRow a = row(snapshot, "a.txt");
        assertEquals(List.of(HunkIds.hunkId("a.txt", 0), HunkIds.hunkId("a.txt", 1)),
                a.hunkIds());
        assertEquals(List.of(digestA(), digestB()), a.hunkDigests());
        assertEquals(1, snapshot.reviewableFiles());
    }

    // ---- AC4 support: what the scope comparison reads back -------------------

    /** Hunk ids and digests are exposed per hunk, in hunk order (AC4's comparison). */
    @Test
    void hunkIdsAndDigestsFollowHunkOrder() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(HUNK_A_LINES), hunk(HUNK_B_LINES))),
                List.of(), List.of(), Optional.empty());

        ReviewTabModel.FileRow row = row(snapshot, "a.txt");
        assertEquals(List.of(HunkIds.hunkId("a.txt", 0), HunkIds.hunkId("a.txt", 1)),
                row.hunkIds());
        assertEquals(List.of(HunkDigest.of("a.txt", hunk(HUNK_A_LINES)),
                        HunkDigest.of("a.txt", hunk(HUNK_B_LINES))),
                row.hunkDigests());
        assertFalse(row.hunkDigests().get(0).equals(row.hunkDigests().get(1)));
    }
}
