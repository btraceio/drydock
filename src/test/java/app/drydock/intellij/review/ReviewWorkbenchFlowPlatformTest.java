package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.DiffSelection;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.tools.fragmented.UnifiedDiffTool;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The flow review in a booted IDE: the controller's implicit seen marks and
 * their persistence, the flag, targets that arrive early, the queue's picks,
 * the centre's footer, and the comment range of a hunk.
 */
public class ReviewWorkbenchFlowPlatformTest extends BasePlatformTestCase {

    private static Line ctx(int oldLine, int newLine) {
        return new Line(Line.Kind.CONTEXT, OptionalInt.of(oldLine), OptionalInt.of(newLine), "c");
    }

    private static Line add(int newLine) {
        return new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(newLine), "+");
    }

    private static Line del(int oldLine) {
        return new Line(Line.Kind.DEL, OptionalInt.of(oldLine), OptionalInt.empty(), "-");
    }

    private static final UnifiedDiff.Hunk A0 = new UnifiedDiff.Hunk("@@ -1,1 +1,3 @@",
            List.of(ctx(1, 1), add(2), add(3)));
    private static final UnifiedDiff.Hunk A1 = new UnifiedDiff.Hunk("@@ -19,2 +21,1 @@",
            List.of(ctx(19, 21), del(20)));
    private static final UnifiedDiff.Hunk B0 = new UnifiedDiff.Hunk("@@ -4,2 +4,2 @@",
            List.of(ctx(4, 4), del(5), add(5)));
    private static final String A_0 = HunkIds.hunkId("src/A.java", 0);
    private static final String A_1 = HunkIds.hunkId("src/A.java", 1);
    private static final String B_0 = HunkIds.hunkId("src/B.java", 0);

    private static ReviewTabModel.FileRow row(String path, UnifiedDiff.Hunk... hunks) {
        UnifiedDiff.FileDiff diff = new UnifiedDiff.FileDiff(path, "modified", 0, 0, false, false, List.of(hunks));
        List<String> ids = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        for (int i = 0; i < hunks.length; i++) {
            ids.add(HunkIds.hunkId(path, i));
            digests.add(HunkDigest.of(path, hunks[i]));
        }
        return new ReviewTabModel.FileRow(path, diff,
                new ReviewTabModel.Mark(ReviewTabModel.Mark.Kind.UNREVIEWED, 0, Optional.empty()), List.of(),
                Optional.empty(), ids, digests, 0, hunks.length);
    }

    private static final ReviewTabModel.Snapshot ROWS = new ReviewTabModel.Snapshot(
            List.of(row("src/A.java", A0, A1), row("src/B.java", B0)), 2, 0);

    private static WorkbenchState state() {
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, ROWS, WorkbenchState.Filter.ALL,
                Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT,
                SectionsModel.Snapshot.EMPTY, new WorkbenchState.SectionsChoice(
                        Optional.of(WorkbenchState.Navigator.FILES), Optional.empty()));
    }

    private final AtomicReference<String> scope = new AtomicReference<>("scope-1");
    private final List<WorkbenchState.Navigator> tabs = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        forgetMarks(); // the light project, and its properties, outlive a test
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            forgetMarks();
        } finally {
            super.tearDown();
        }
    }

    private void forgetMarks() {
        PropertiesComponent properties = PropertiesComponent.getInstance(getProject());
        for (String id : List.of("scope-1", "scope-2")) {
            properties.setList(ReviewFlowMarks.SEEN_KEY + id, List.of());
            properties.setList(ReviewFlowMarks.FLAGGED_KEY + id, List.of());
        }
        PropertiesComponent.getInstance().unsetValue(ReviewWorkbenchFlow.HIDE_SEEN_KEY);
    }

    private ReviewWorkbenchFlow flow() {
        return new ReviewWorkbenchFlow(() -> Optional.ofNullable(scope.get()),
                new ReviewFlowMarks(PropertiesComponent.getInstance(getProject())), tabs::add);
    }

    private static String current(ReviewWorkbenchFlow flow) {
        return flow.current().map(FlowModel.Hunk::key).orElse(null);
    }

    public void testNextMarksTheHunkLeftSeenAndTheLastNextOpensTheFinishScreen() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        assertEquals("opening marks nothing", A_0, current(flow));
        assertEquals(0, flow.queue().seenCount(flow.seen()));

        flow.next();
        assertEquals(A_1, current(flow));
        assertEquals(Set.of(HunkDigest.of("src/A.java", A0)), flow.seen());
        flow.next();
        flow.next();
        assertTrue("Next on the last hunk finishes", flow.finishing());
        assertEquals(3, flow.queue().seenCount(flow.seen()));

        flow.back();
        assertFalse(flow.finishing());
        assertEquals("Back from the finish screen shows the last hunk", B_0, current(flow));
    }

    public void testTheSeenMarksPersistPerScopeAndAFreshFlowStartsAtTheFirstUnseenHunk() {
        ReviewWorkbenchFlow first = flow();
        first.render(state());
        first.go(B_0);
        assertEquals("picking another hunk marks the one left", Set.of(HunkDigest.of("src/A.java", A0)),
                first.seen());

        ReviewWorkbenchFlow second = flow();
        second.render(state());
        assertEquals(A_1, current(second));

        scope.set("scope-2");
        second.render(state());
        assertTrue("another scope has its own marks", second.seen().isEmpty());
        assertEquals(A_0, current(second));
    }

    public void testBackMarksTheHunkLeftAndDoesNothingAtTheStart() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        flow.back();
        assertEquals(A_0, current(flow));
        assertTrue("nothing was left", flow.seen().isEmpty());
        flow.go(B_0);
        flow.back();
        assertEquals(A_1, current(flow));
        assertTrue(flow.seen().contains(HunkDigest.of("src/B.java", B0)));
    }

    public void testTheFlagTogglesOnTheCurrentHunkAndPersists() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        flow.toggleFlag();
        assertTrue(flow.currentFlagged());
        assertEquals(1, flow.queue().flaggedCount(flow.flagged()));
        assertEquals(List.of(HunkDigest.of("src/A.java", A0)), PropertiesComponent.getInstance(getProject())
                .getList(ReviewFlowMarks.FLAGGED_KEY + "scope-1"));
        flow.toggleFlag();
        assertFalse(flow.currentFlagged());
        List<String> stored = PropertiesComponent.getInstance(getProject())
                .getList(ReviewFlowMarks.FLAGGED_KEY + "scope-1");
        assertTrue("the unflag is stored: " + stored, stored == null || stored.isEmpty());
        ReviewWorkbenchFlow fresh = flow();
        fresh.render(state());
        assertTrue("a fresh flow sees no flag", fresh.flagged().isEmpty());
    }

    public void testATargetBeforeTheFirstQueueWaitsForItAndAnUnknownLineFallsBackToTheFilesFirstHunk() {
        ReviewWorkbenchFlow flow = flow();
        flow.showLine("src/A.java", "o20");
        flow.render(state());
        assertEquals(A_1, current(flow));

        flow.showLine("src/B.java", "n99");
        assertEquals(B_0, current(flow));
        flow.showLine("src/C.java", "n1");
        assertEquals("a file the queue does not list changes nothing", B_0, current(flow));
    }

    public void testTheStoredMarksKeepOnlyTheDigestsTheReviewStillHas() {
        ReviewFlowMarks marks = new ReviewFlowMarks(PropertiesComponent.getInstance(getProject()));
        marks.storeSeen("scope-1", Set.of("live", "gone"), List.of("live"));
        assertEquals(Set.of("live"), marks.seen("scope-1"));
    }

    public void testTheQueueShowsTheCurrentHunkAndAPickGoesToIt() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        ReviewWorkbenchQueue queue = new ReviewWorkbenchQueue(flow, null);
        assertEquals("3 to go", queue.toGo.getText());
        assertTrue(queue.list.getSelectedValue() instanceof FlowModel.Item item && item.hunk().key().equals(A_0));

        int b = indexOf(queue, B_0);
        queue.list.setSelectedIndex(b);
        assertEquals(B_0, current(flow));
        assertEquals("2 to go", queue.toGo.getText());

        queue.list.setSelectedIndex(0); // src/A.java's header is not a pick
        assertTrue(queue.list.getSelectedValue() instanceof FlowModel.Item item && item.hunk().key().equals(B_0));

        flow.setHideSeen(true);
        assertEquals("the seen A hunk is hidden, the rest stay", -1, indexOf(queue, A_0));
        assertTrue(indexOf(queue, A_1) >= 0);
    }

    public void testTheQueueStripNamesTheTabAndWhatIsLeft() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        assertEquals("Files · 3 to go", ReviewWorkbenchQueue.stripLabel(flow.queue(), flow.seen()));
    }

    public void testTheCentresFooterWalksTheQueueAndNamesTheLastStep() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        ReviewWorkbenchFlowView view = new ReviewWorkbenchFlowView(getProject(), flow,
                ReviewWorkbenchEditorPlatformTest.NO_ACTIONS);
        try {
            assertEquals("Hunk 1 of 3", view.position.getText());
            assertFalse("no hunk before the first", view.back.isEnabled());
            assertFalse(view.flaggedChip.isVisible());
            view.flag.doClick();
            assertTrue(view.flaggedChip.isVisible());
            assertEquals(ReviewWorkbenchFlowView.UNFLAG, view.flag.getText());
            view.next.doClick();
            view.next.doClick();
            assertEquals(ReviewWorkbenchFlowView.FINISH, view.next.getText());
            view.next.doClick();
            assertTrue(flow.finishing());
        } finally {
            Disposer.dispose(view);
        }
    }

    public void testApproveOnTheFinishScreenRecordsTheSeenHunksAndShowsTheOutcome() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        List<List<String>> asked = new ArrayList<>();
        CompletableFuture<Optional<String>> written = new CompletableFuture<>();
        ReviewWorkbenchFlowView view = new ReviewWorkbenchFlowView(getProject(), flow,
                new ReviewWorkbenchFlowViewActions(asked, written));
        try {
            flow.next(); // A_0 seen
            flow.finish();
            assertEquals("Approve 1 Seen Hunk", view.approve.getText());
            assertTrue(view.approve.isEnabled());

            view.approve.doClick();
            assertEquals(List.of(List.of(HunkDigest.of("src/A.java", A0))), asked);
            assertEquals(ReviewWorkbenchFlowView.APPROVING, view.approve.getText());
            assertFalse(view.approve.isEnabled());
            view.approve.doClick();
            assertEquals("a running approval is not started twice", 1, asked.size());

            written.complete(Optional.empty());
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals("Approved 1 hunk.", view.finishNotice.getText());
        } finally {
            Disposer.dispose(view);
        }
    }

    public void testApproveIsOffWithNothingSeenAndNamesTheCount() {
        assertEquals("Approve", ReviewWorkbenchFlowView.approveLabel(0));
        assertEquals("Approve 3 Seen Hunks", ReviewWorkbenchFlowView.approveLabel(3));
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        ReviewWorkbenchFlowView view = new ReviewWorkbenchFlowView(getProject(), flow,
                ReviewWorkbenchEditorPlatformTest.NO_ACTIONS);
        try {
            flow.finish();
            assertFalse(view.approve.isEnabled());
            assertEquals(ReviewWorkbenchFlowView.NOTHING_TO_APPROVE, view.approve.getToolTipText());
        } finally {
            Disposer.dispose(view);
        }
    }

    /** Flow centre actions that record approvals and complete them with {@code written}. */
    private static final class ReviewWorkbenchFlowViewActions implements ReviewWorkbenchFlowView.Actions {
        private final List<List<String>> asked;
        private final CompletableFuture<Optional<String>> written;

        ReviewWorkbenchFlowViewActions(List<List<String>> asked, CompletableFuture<Optional<String>> written) {
            this.asked = asked;
            this.written = written;
        }

        @Override
        public CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(String path, int hunkIndex,
                                                                                        String scrollKey) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public Consumer<FrameDiffTool.DiffViewer> decorator(String path, int hunkIndex, Disposable lifetime) {
            return viewer -> {
            };
        }

        @Override
        public CompletableFuture<Optional<String>> comment(FlowModel.Hunk hunk, String text) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<String>> submitReview() {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<String>> sendToSession() {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<String>> approve(List<String> digests) {
            asked.add(digests);
            return written;
        }
    }

    public void testTheUnifiedViewerGoesFirstAndNothingElseMoves() {
        String unified = UnifiedDiffTool.class.getCanonicalName();
        assertEquals(List.of(unified), ReviewWorkbenchFlowView.unifiedFirst(List.of()));
        assertEquals(List.of(unified, "a.Simple", "b.Other"),
                ReviewWorkbenchFlowView.unifiedFirst(List.of("a.Simple", unified, "b.Other")));
    }

    public void testFirstNewLineIsTheScrollKeysOrTheLowestNewRow() {
        assertEquals(2, ReviewWorkbenchFlowView.firstNewLine(new FlowModel.Hunk("k", "a", 0, "d", "2–3", "n2", 0, 2,
                Set.of("n1", "n2", "n3"), 0, false)));
        assertEquals(21, ReviewWorkbenchFlowView.firstNewLine(new FlowModel.Hunk("k", "a", 1, "d", "old 20", "o20",
                1, 0, Set.of("o19", "n21", "o20"), 0, false)));
        assertEquals("a malformed key is skipped, never thrown", 4, ReviewWorkbenchFlowView.firstNewLine(
                new FlowModel.Hunk("k", "a", 0, "d", "4", "nx", 0, 1, Set.of("n+1", "n0", "n4"), 0, false)));
        assertEquals(1, ReviewWorkbenchFlowView.firstNewLine(new FlowModel.Hunk("k", "a", 0, "d", "", "", 0, 0,
                Set.of("x"), 0, false)));
    }

    public void testAHunkWhoseFileChangedSinceIsAskedAgainUnderANewerStateOnly() {
        ReviewWorkbenchFlow flow = flow();
        flow.render(state());
        List<String> asked = new ArrayList<>();
        ReviewWorkbenchFlowView.Actions changedSince = new ReviewWorkbenchFlowView.Actions() {
            @Override
            public CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(String path,
                                                                                            int hunkIndex,
                                                                                            String scrollKey) {
                asked.add(path + "#" + hunkIndex);
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public Consumer<FrameDiffTool.DiffViewer> decorator(String path, int hunkIndex, Disposable lifetime) {
                return ReviewWorkbenchEditorPlatformTest.NO_ACTIONS.decorator(path, hunkIndex, lifetime);
            }

            @Override
            public CompletableFuture<Optional<String>> comment(FlowModel.Hunk hunk, String text) {
                return ReviewWorkbenchEditorPlatformTest.NO_ACTIONS.comment(hunk, text);
            }

            @Override
            public CompletableFuture<Optional<String>> submitReview() {
                return ReviewWorkbenchEditorPlatformTest.NO_ACTIONS.submitReview();
            }

            @Override
            public CompletableFuture<Optional<String>> sendToSession() {
                return ReviewWorkbenchEditorPlatformTest.NO_ACTIONS.sendToSession();
            }

            @Override
            public CompletableFuture<Optional<String>> approve(List<String> digests) {
                return ReviewWorkbenchEditorPlatformTest.NO_ACTIONS.approve(digests);
            }
        };
        ReviewWorkbenchFlowView view = new ReviewWorkbenchFlowView(getProject(), flow, changedSince);
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of("src/A.java#0"), asked);

            flow.toggleFlag(); // a re-render under the same state
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals("the same state does not ask again", 1, asked.size());

            flow.render(state()); // the reload's state
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of("src/A.java#0", "src/A.java#0"), asked);

            flow.toggleFlag();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals("a repeat failure waits for the next state", 2, asked.size());
        } finally {
            Disposer.dispose(view);
        }
    }

    public void testAHunksCommentRangeIsItsAddedLinesOrItsRemovedOnes() {
        FlowModel.Queue queue = FlowModel.of(state(), Set.of());
        assertEquals(Optional.of(new DiffSelection.Range("src/A.java", "n2", "n3", false)),
                ReviewTabProvider.hunkRange(ROWS, queue.hunk(A_0).orElseThrow()));
        assertEquals(Optional.of(new DiffSelection.Range("src/A.java", "o20", "o20", false)),
                ReviewTabProvider.hunkRange(ROWS, queue.hunk(A_1).orElseThrow()));
        FlowModel.Hunk changed = queue.hunk(B_0).orElseThrow();
        FlowModel.Hunk stale = new FlowModel.Hunk(changed.key(), changed.file(), changed.index(), "other-digest",
                changed.lines(), changed.scrollKey(), changed.removed(), changed.added(), changed.rowKeys(), 0, false);
        assertEquals("a hunk whose digest moved on has no range", Optional.empty(),
                ReviewTabProvider.hunkRange(ROWS, stale));
    }

    private static int indexOf(ReviewWorkbenchQueue queue, String key) {
        for (int i = 0; i < queue.list.getModel().getSize(); i++) {
            if (queue.list.getModel().getElementAt(i) instanceof FlowModel.Item item && item.hunk().key().equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
