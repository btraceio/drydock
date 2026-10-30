package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tree's rendering of the snapshot (AC7's rendering-data half; the
 * model half is T3's): which rows, marks, finding children and footers the
 * browser assembles from a {@link ReviewTabModel.Snapshot}, and that a
 * rebuild reads only the latest accepted snapshot. Everything here goes
 * through the browser's pure assembly ({@code nodeRows}, {@code treeOf},
 * {@code TreeState}) — no browser, no Swing host, no EDT (the browser
 * itself needs the platform; AC24's live checklist covers it).
 */
class ReviewTabBrowserTest {

    private static final Instant AT = Instant.EPOCH;
    private static final String SCOPE = "s1";
    private static final String BASE = "base-1";

    private int seq;

    // ---- fixtures (the same hand-built diffs the model tests use) -----------

    private static UnifiedDiff.Line ctx(int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line add(int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), text);
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

    private static ReviewVerdict verdict(String hunkDigest, ReviewVerdict.Decision decision) {
        return new ReviewVerdict(SCOPE, hunkDigest, decision, Optional.empty(), AT, BASE, "head");
    }

    /** One modified line plus one context line: row keys n1 and n2. */
    private static final UnifiedDiff.Line[] A_LINES = {ctx(1, 1, "package p;"), add(2, "int a = 1;")};
    private static final UnifiedDiff.Line[] B_LINES = {ctx(1, 1, "package q;"), add(2, "int b = 2;")};
    private static final UnifiedDiff.Line[] C_LINES = {ctx(1, 1, "package r;"), add(2, "int c = 3;")};

    private static String digestA() {
        return HunkDigest.of("a.txt", hunk(A_LINES));
    }

    private static String digestB() {
        return HunkDigest.of("b.txt", hunk(B_LINES));
    }

    private static String digestC() {
        return HunkDigest.of("c.txt", hunk(C_LINES));
    }

    private static ReviewTabModel.Snapshot snapshot(UnifiedDiff diff,
                                                    List<ReviewAnnotation> findings,
                                                    List<ReviewVerdict> verdicts,
                                                    Optional<String> currentBase) {
        return ReviewTabModel.snapshot(new ReviewTabModel.Inputs(diff, findings, verdicts, currentBase));
    }

    private static List<String> fileTexts(DefaultTreeModel model) {
        ChangesBrowserNode<?> root = (ChangesBrowserNode<?>) model.getRoot();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            texts.add(((ReviewTabBrowser.FileNode) root.getChildAt(i)).getTextPresentation());
        }
        return texts;
    }

    // ---- AC7's rendering data: rows and marks --------------------------------

    /**
     * Every mark renders onto its row's text, in the snapshot's path order,
     * and the model's hints ride the row's tooltip.
     */
    @Test
    void fileRowsRenderPathMarkAndTooltips() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(A_LINES)),
                        file("b.txt", hunk(B_LINES)),
                        file("c.txt", hunk(C_LINES)),
                        file("d.bin")),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak")),
                List.of(verdict(digestB(), ReviewVerdict.Decision.APPROVED),
                        verdict(digestC(), ReviewVerdict.Decision.CHANGES)),
                Optional.of(BASE));

        List<ReviewTabBrowser.FileRowDisplay> rows = ReviewTabBrowser.nodeRows(snapshot);
        assertEquals(List.of("a.txt", "b.txt", "c.txt", "d.bin"),
                rows.stream().map(ReviewTabBrowser.FileRowDisplay::path).toList());
        assertEquals("a.txt  ◆ 1", rows.get(0).text());
        assertTrue(rows.get(0).tooltip().isEmpty());
        assertEquals("b.txt  ✓", rows.get(1).text());
        assertEquals("c.txt  ○", rows.get(2).text());
        assertEquals(Optional.of(ReviewTabModel.CHANGES_HINT), rows.get(2).tooltip());
        assertEquals("d.bin  no text changes to review", rows.get(3).text());
        assertTrue(rows.get(3).tooltip().isEmpty());
    }

    // ---- AC7's rendering data: finding children, hidden, footer, drift ------

    /**
     * A file's children are its open findings only: severity and title with
     * the {@code :<line>} suffix, the drift hint as the tooltip of any
     * finding whose start key is not a row key — including one whose key is
     * malformed and has no line to show. Resolved and dismissed findings are
     * hidden and counted in the footer instead.
     */
    @Test
    void findingChildrenCarrySeverityTitleLineAndDriftHint() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(A_LINES))),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak"),
                        open("a.txt", "n99", "n99", Severity.NIT, "orphan"),
                        open("a.txt", "n1x", "n1x", Severity.QUESTION, "odd key"),
                        finding("a.txt", "n2", "n2", Severity.NIT, "done",
                                AnnotationStatus.RESOLVED, Triage.CONFIRMED),
                        finding("a.txt", "n3", "n3", Severity.NIT, "won't fix",
                                AnnotationStatus.OPEN, Triage.DISMISSED)),
                List.of(), Optional.of(BASE));

        ReviewTabBrowser.FileRowDisplay row = ReviewTabBrowser.nodeRows(snapshot).get(0);
        assertEquals("a.txt  ◆ 3", row.text());
        assertEquals(3, row.findings().size()); // resolved and dismissed are hidden
        // severity is the row's icon and tooltip, never a prefix in the text
        assertEquals("leak  :1", row.findings().get(0).text());
        assertEquals(Optional.of("Blocking"), row.findings().get(0).tooltip());
        assertEquals("orphan  :99", row.findings().get(1).text());
        assertEquals(Optional.of("Nit · " + ReviewTabModel.DRIFT_HINT), row.findings().get(1).tooltip());
        assertEquals("odd key", row.findings().get(2).text()); // malformed key: no :line
        assertEquals(Optional.of("Question · " + ReviewTabModel.DRIFT_HINT), row.findings().get(2).tooltip());
        assertEquals(Optional.of("2 resolved findings hidden"), row.footer());
    }

    /**
     * The node tree mirrors the display data: file nodes in path order,
     * finding child nodes, and the footer as the dim last child of the
     * file that owns it.
     */
    @Test
    void treeNodesMirrorTheSnapshot() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(A_LINES)), file("b.txt", hunk(B_LINES))),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak"),
                        finding("a.txt", "n2", "n2", Severity.NIT, "done",
                                AnnotationStatus.RESOLVED, Triage.CONFIRMED)),
                List.of(verdict(digestB(), ReviewVerdict.Decision.APPROVED)),
                Optional.of(BASE));

        DefaultTreeModel model = ReviewTabBrowser.treeOf(snapshot);
        ChangesBrowserNode<?> root = (ChangesBrowserNode<?>) model.getRoot();
        assertEquals(2, root.getChildCount());

        ReviewTabBrowser.FileNode a = (ReviewTabBrowser.FileNode) root.getChildAt(0);
        assertEquals("a.txt  ◆ 1", a.getTextPresentation());
        assertEquals("a.txt", a.getUserObject().path());
        assertEquals(2, a.getChildCount()); // the finding child, then the footer
        ReviewTabBrowser.FindingNode finding = (ReviewTabBrowser.FindingNode) a.getChildAt(0);
        assertEquals("leak  :1", finding.getTextPresentation());
        assertEquals("leak", finding.getUserObject().title());
        ReviewTabBrowser.FooterNode footer = (ReviewTabBrowser.FooterNode) a.getChildAt(1);
        assertEquals("1 resolved findings hidden", footer.getUserObject());

        ReviewTabBrowser.FileNode b = (ReviewTabBrowser.FileNode) root.getChildAt(1);
        assertEquals("b.txt  ✓", b.getTextPresentation());
        assertEquals(0, b.getChildCount());
    }

    /**
     * Only file rows and finding rows are selection targets: the footer and
     * the root are nodes, not rows, and never reach the preview.
     */
    @Test
    void onlyFileAndFindingRowsAreSelectable() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(A_LINES))),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak")),
                List.of(), Optional.empty());
        ReviewTabModel.FileRow fileRow = snapshot.files().get(0);
        ReviewTabModel.FindingRow findingRow = fileRow.findings().get(0);

        assertSame(fileRow, ReviewTabBrowser.selectableRow(new ReviewTabBrowser.FileNode(fileRow)));
        assertSame(findingRow, ReviewTabBrowser.selectableRow(new ReviewTabBrowser.FindingNode(findingRow)));
        assertNull(ReviewTabBrowser.selectableRow(
                new ReviewTabBrowser.FooterNode("1 resolved findings hidden")));
        assertNull(ReviewTabBrowser.selectableRow(ChangesBrowserNode.createRoot()));
    }

    /**
     * The accept-then-rebuild ordering: a rebuild reads exactly the latest
     * accepted snapshot — an empty tree before the first accept, and a
     * second accept replacing the first accept's rows, marks and children
     * wholesale, leaving nothing of the older one behind.
     */
    @Test
    void acceptThenRebuildReadsOnlyTheLatestSnapshot() {
        ReviewTabBrowser.TreeState tree = new ReviewTabBrowser.TreeState();
        assertEquals(0, ((ChangesBrowserNode<?>) tree.build().getRoot()).getChildCount());

        ReviewTabModel.Snapshot before = snapshot(
                diff(file("a.txt", hunk(A_LINES))),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak")),
                List.of(), Optional.empty());
        tree.accept(before);
        assertEquals(List.of("a.txt  ◆ 1"), fileTexts(tree.build()));

        ReviewTabModel.Snapshot after = snapshot(
                diff(file("a.txt", hunk(A_LINES)), file("b.txt", hunk(B_LINES))),
                List.of(),
                List.of(verdict(digestA(), ReviewVerdict.Decision.APPROVED),
                        verdict(digestB(), ReviewVerdict.Decision.APPROVED)),
                Optional.of(BASE));
        tree.accept(after);
        assertEquals(List.of("a.txt  ✓", "b.txt  ✓"), fileTexts(tree.build()));

        DefaultTreeModel rebuilt = tree.build();
        ReviewTabBrowser.FileNode a = (ReviewTabBrowser.FileNode)
                ((ChangesBrowserNode<?>) rebuilt.getRoot()).getChildAt(0);
        assertEquals(0, a.getChildCount()); // the earlier snapshot's finding row is gone
    }

    // ---- AC5's gutter navigation: selection by stable path and id ----------

    /**
     * The stable-path/id resolution a rebuild consumes its target through:
     * an exact target resolves to exactly its own finding node — root,
     * its file row, the finding — never a sibling finding, a file row or
     * a fallback. This is the pure decision the Swing half applies as a
     * tree selection after the rebuild.
     */
    @Test
    void anExactFindingTargetResolvesToItsOwnNode() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(A_LINES)), file("b.txt", hunk(B_LINES))),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak"),
                        open("b.txt", "n1", "n1", Severity.NIT, "spare")),
                List.of(), Optional.empty());
        // The findings' ids, in call order: a.txt's is f0, b.txt's is f1.

        DefaultTreeModel model = ReviewTabBrowser.treeOf(snapshot);
        TreePath path = ReviewTabBrowser.findingPath(model.getRoot(), "b.txt", "f1");

        assertEquals(3, path.getPathCount(), () -> path.toString());
        assertSame(model.getRoot(), path.getPathComponent(0), path::toString);
        ReviewTabBrowser.FileNode file = (ReviewTabBrowser.FileNode) path.getPathComponent(1);
        assertEquals("b.txt", file.getUserObject().path());
        ReviewTabBrowser.FindingNode finding = (ReviewTabBrowser.FindingNode) path.getPathComponent(2);
        assertEquals("f1", finding.getUserObject().id());
        assertEquals("spare", finding.getUserObject().title());
    }

    /**
     * Stale and malformed targets resolve to nothing: an id that belongs
     * to another file's row, a file the snapshot does not hold, and
     * null/blank path or id all yield {@code null} — the selection stays
     * whatever the rebuild left it. So does a non-assembled tree.
     */
    @Test
    void staleAndMalformedTargetsResolveToNothing() {
        ReviewTabModel.Snapshot snapshot = snapshot(
                diff(file("a.txt", hunk(A_LINES)), file("b.txt", hunk(B_LINES))),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak"),
                        open("b.txt", "n1", "n1", Severity.NIT, "spare")),
                List.of(), Optional.empty());
        Object root = ReviewTabBrowser.treeOf(snapshot).getRoot();

        assertNull(ReviewTabBrowser.findingPath(root, "a.txt", "f1"), // the file holds f0, not f1
                "an id of another file's row is stale");
        assertNull(ReviewTabBrowser.findingPath(root, "a.txt", "f9"), "no such finding id");
        assertNull(ReviewTabBrowser.findingPath(root, "gone.txt", "f0"), "no such file row");
        assertNull(ReviewTabBrowser.findingPath(root, "  ", "f0"), "blank path");
        assertNull(ReviewTabBrowser.findingPath(root, null, "f0"), "null path");
        assertNull(ReviewTabBrowser.findingPath(root, "a.txt", ""), "blank id");
        assertNull(ReviewTabBrowser.findingPath(root, "a.txt", null), "null id");
        assertNull(ReviewTabBrowser.findingPath(null, "a.txt", "f0"), "no assembled tree");
    }

    /**
     * The retention rule: a target recorded before any snapshot has
     * arrived is not spent on the empty tree — it waits for the next
     * accept, and the consumption after that snapshot's rebuild takes it
     * exactly once. A malformed target is never recorded at all, and a
     * later click overwrites an earlier one — the latest navigation is
     * the one that counts.
     */
    @Test
    void aTargetRecordedBeforeAnySnapshotIsRetainedUntilTheNextOneConsumesIt() {
        ReviewTabBrowser.TreeState tree = new ReviewTabBrowser.TreeState();

        assertTrue(tree.requestSelection("a.txt", "f0"));
        assertFalse(tree.isLoaded()); // nothing is applied yet: the target waits

        // The next snapshot arrives (accept); the rebuild that follows is
        // the consumption point.
        tree.accept(snapshot(
                diff(file("a.txt", hunk(A_LINES))),
                List.of(open("a.txt", "n1", "n1", Severity.BLOCKING, "leak")),
                List.of(), Optional.empty()));
        assertTrue(tree.isLoaded());
        assertEquals(Optional.of(new ReviewTabOpener.FindingTarget("a.txt", "f0")),
                tree.takePendingSelection());
        // Spent exactly once: the selection cannot fire twice.
        assertTrue(tree.takePendingSelection().isEmpty());

        // A malformed target records nothing, even against a loaded tree.
        assertFalse(tree.requestSelection("", "f0"));
        assertTrue(tree.takePendingSelection().isEmpty());

        // The latest click wins.
        assertTrue(tree.requestSelection("a.txt", "f0"));
        assertTrue(tree.requestSelection("b.txt", "f1"));
        assertEquals(Optional.of(new ReviewTabOpener.FindingTarget("b.txt", "f1")),
                tree.takePendingSelection());
    }

    /**
     * The record and the take are atomic against each other: when a
     * target is recorded and several takers reach for it at the same
     * instant, the target is spent at most once — the concurrent shape of
     * exactly-once consumption, which the sequential tests above cannot
     * show. The takers spin-start on a flag so they enter the take as
     * simultaneously as the JVM allows, and every assertion holds for
     * every interleaving: at most one taker per round takes the target,
     * and after the rounds nothing is left to spend.
     */
    @Test
    void aConcurrentTakeNeverSpendsTheTargetTwice() throws Exception {
        ReviewTabBrowser.TreeState tree = new ReviewTabBrowser.TreeState();
        int takers = 4;
        int rounds = 500;
        ExecutorService pool = Executors.newFixedThreadPool(takers);
        try {
            for (int round = 0; round < rounds; round++) {
                ReviewTabOpener.FindingTarget target =
                        new ReviewTabOpener.FindingTarget("a.txt", "f" + round);
                assertTrue(tree.requestSelection(target.filePath(), target.findingId()));

                AtomicBoolean go = new AtomicBoolean();
                CountDownLatch ready = new CountDownLatch(takers);
                List<Future<Boolean>> races = new ArrayList<>();
                for (int taker = 0; taker < takers; taker++) {
                    races.add(pool.submit(() -> {
                        ready.countDown();
                        while (!go.get()) {
                            Thread.onSpinWait(); // enter the take as one instant
                        }
                        return tree.takePendingSelection().isPresent();
                    }));
                }
                ready.await(20, TimeUnit.SECONDS);
                go.set(true);

                int spent = 0;
                for (Future<Boolean> race : races) {
                    spent += race.get(20, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertTrue(spent <= 1,
                        "round " + round + " spent its target " + spent + " times");
            }
            assertTrue(tree.takePendingSelection().isEmpty(), "nothing is left after the rounds");
        } finally {
            pool.shutdownNow();
        }
    }
}
