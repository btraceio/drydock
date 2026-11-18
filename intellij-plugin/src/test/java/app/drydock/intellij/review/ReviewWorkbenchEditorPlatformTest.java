package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.DiffSelection;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.tour.ReviewTour;
import com.intellij.diff.FrameDiffTool;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;

import javax.swing.JComponent;
import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The review workbench tab in a booted IDE: one reusable file per project,
 * a provider that opens only that file, and an editor that lays the flow's
 * queue, centre and findings pane over the outbox dock.
 */
public class ReviewWorkbenchEditorPlatformTest extends BasePlatformTestCase {

    public void testTheProjectHasOneWorkbenchFile() {
        assertSame(ReviewWorkbenchFile.of(getProject()), ReviewWorkbenchFile.of(getProject()));
    }

    public void testTheTabIsNamedAfterTheReviewSubject() {
        assertEquals("Drydock Review", ReviewWorkbenchFile.title(null));
        assertEquals("Drydock Review", ReviewWorkbenchFile.title(" "));
        assertEquals("Drydock Review — PR #930", ReviewWorkbenchFile.title("PR #930"));
    }

    public void testTheProviderIsRegisteredAndOpensOnlyTheWorkbenchFile() {
        ReviewWorkbenchEditorProvider provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList()
                .stream().filter(ReviewWorkbenchEditorProvider.class::isInstance)
                .map(ReviewWorkbenchEditorProvider.class::cast).findFirst().orElse(null);
        assertNotNull("registered in plugin.xml", provider);
        assertTrue(provider.accept(getProject(), ReviewWorkbenchFile.of(getProject())));
        assertFalse(provider.accept(getProject(), new LightVirtualFile("A.java", "class A {}")));
    }

    public void testWithoutALiveReviewTheCentreSaysSo() {
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                NO_REVIEW);
        try {
            assertEquals(ReviewWorkbenchEditor.NO_REVIEW_TEXT,
                    ((JBLabel) editor.inboxSplitter.getFirstComponent()).getText());
            assertFalse("nothing to finish", editor.finishReview.isEnabled());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testTheLayoutIsQueueHunkFindingsOverTheOutboxDock() {
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                NO_REVIEW);
        try {
            assertFalse(editor.navSplitter.isVertical());
            assertSame(editor.navigatorPane, editor.navSplitter.getFirstComponent());
            assertSame(editor.inboxSplitter, editor.navSplitter.getSecondComponent());
            assertFalse(editor.inboxSplitter.isVertical());
            assertEquals(ReviewWorkbenchEditor.INBOX_EMPTY_TEXT,
                    ((JBLabel) ((CollapsiblePane) editor.inboxSplitter.getSecondComponent()).content()).getText());
            BorderLayout layout = (BorderLayout) editor.getComponent().getLayout();
            assertSame("the dock sits under the panes, outside the splitters", editor.outbox,
                    layout.getLayoutComponent(BorderLayout.SOUTH));
            assertSame(editor.navSplitter, layout.getLayoutComponent(BorderLayout.CENTER));
            assertEquals(ReviewWorkbenchEditor.NAV_KEY, editor.navSplitter.getSplitterProportionKey());
            assertEquals(ReviewWorkbenchEditor.INBOX_KEY, editor.inboxSplitter.getSplitterProportionKey());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testAMovedDividerIsStoredAndANewTabLoadsIt() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        String saved = properties.getValue(ReviewWorkbenchEditor.NAV_KEY);
        try {
            ReviewWorkbenchEditor first = new ReviewWorkbenchEditor(getProject(),
                    ReviewWorkbenchFile.of(getProject()), NO_REVIEW);
            first.navSplitter.setProportion(0.33f);
            assertEquals("0.33", properties.getValue(ReviewWorkbenchEditor.NAV_KEY));
            Disposer.dispose(first);

            ReviewWorkbenchEditor second = new ReviewWorkbenchEditor(getProject(),
                    ReviewWorkbenchFile.of(getProject()), NO_REVIEW);
            // The splitter loads the stored value when first shown (addNotify);
            // a headless test is never shown, so it asks for the load itself.
            second.navSplitter.setAndLoadSplitterProportionKey(ReviewWorkbenchEditor.NAV_KEY);
            assertEquals(0.33f, second.navSplitter.getProportion(), 0.001f);
            Disposer.dispose(second);
        } finally {
            properties.setValue(ReviewWorkbenchEditor.NAV_KEY, saved);
        }
    }

    public void testTheHeaderNamesTheKindTheSizeAndTheProgress() {
        FlowModel.Queue queue = new FlowModel.Queue(true, Optional.empty(), List.of(), List.of(
                hunk("h1", "d1", 3, 1), hunk("h2", "d2", 1, 0)), 2, 3);
        WorkbenchState state = WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT,
                new SectionsModel.Snapshot(SectionsModel.Source.TOUR, Optional.empty(), List.of(),
                        Optional.of(ReviewTour.Kind.SECTIONS)),
                WorkbenchState.SectionsChoice.DEFAULT);
        assertEquals("Section review · 2 files · 3 sections · +4 −1", ReviewWorkbenchEditor.metaText(state, queue));
        assertEquals("1 of 2 hunks seen", ReviewWorkbenchEditor.progressText(queue, Set.of("d2")));
        assertEquals("Educational tour", ReviewWorkbenchEditor.kindLabel(ReviewTour.Kind.TOUR));
    }

    public void testTheHeaderFollowsTheFlowAndFinishReviewOpensTheFinishScreen() {
        List<ReviewWorkbenchFlow> flows = new ArrayList<>();
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                flowHost(flows, null));
        try {
            ReviewWorkbenchFlow flow = flows.getFirst();
            flow.render(emptyState("PR #7"));
            assertEquals("PR #7", editor.subject.getText());
            assertEquals("0 of 0 hunks seen", editor.progressText.getText());
            assertTrue(editor.finishReview.isEnabled());
            editor.finishReview.doClick();
            assertTrue(flow.finishing());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testTheHeadersRefreshAsksTheHostToRerunTheDiff() {
        List<Project> refreshed = new ArrayList<>();
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedFlow> hostFlow(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public void refresh(Project project) {
                refreshed.add(project);
            }
        };
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                host);
        try {
            assertEquals(ReviewWorkbenchEditor.REFRESH, editor.refreshAction.getTemplatePresentation().getText());
            editor.refreshAction.actionPerformed(TestActionEvent.createTestEvent(editor.refreshAction));
            assertEquals(List.of(getProject()), refreshed);
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testThePrDescriptionsFirstParagraphIsCutAtItsFirstBlankLine() {
        assertEquals("Callers use the result of bar().", ReviewWorkbenchQueue.firstParagraph(
                "## Summary\nCallers use\n the result of bar().\n\n## Details\nMore.", 400));
        assertEquals("abcd…", ReviewWorkbenchQueue.firstParagraph("abcdefgh", 4));
    }

    public void testTheReviewMenuAsksForSectionsTheTourAndSphinxOnlyWhenTheHarnessHasIt() {
        List<String> started = new ArrayList<>();
        boolean[] loaded = {false};
        boolean[] sphinx = {false};
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedFlow> hostFlow(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public boolean canStartReview(Project project) {
                return loaded[0];
            }

            @Override
            public boolean sphinxReviewAvailable(Project project) {
                return sphinx[0];
            }

            @Override
            public void startReview(Project project, ReviewTabOpener.ReviewStart start) {
                started.add(start.name());
            }
        };
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                host);
        try {
            assertTrue("a drop-down", editor.reviewActions.isPopup());
            assertEquals(ReviewWorkbenchEditor.REVIEW_MENU, editor.reviewActions.getTemplatePresentation().getText());
            AnAction sections = editor.reviewActions.getChildActionsOrStubs()[0];
            AnAction tour = editor.reviewActions.getChildActionsOrStubs()[1];
            AnAction viaSphinx = editor.reviewActions.getChildActionsOrStubs()[2];
            assertEquals("the section-based review comes first", ReviewWorkbenchEditor.SECTION_REVIEW,
                    sections.getTemplatePresentation().getText());
            assertEquals(ReviewWorkbenchEditor.EDUCATIONAL_TOUR, tour.getTemplatePresentation().getText());
            assertEquals(ReviewWorkbenchEditor.SPHINX_REVIEW, viaSphinx.getTemplatePresentation().getText());

            AnActionEvent tourEvent = TestActionEvent.createTestEvent(tour);
            AnActionEvent sphinxEvent = TestActionEvent.createTestEvent(viaSphinx);
            tour.update(tourEvent);
            viaSphinx.update(sphinxEvent);
            assertFalse("off until the review is loaded", tourEvent.getPresentation().isEnabled());
            assertFalse("hidden without the plugin", sphinxEvent.getPresentation().isVisible());

            loaded[0] = true;
            sphinx[0] = true;
            tour.update(tourEvent);
            viaSphinx.update(sphinxEvent);
            assertTrue(tourEvent.getPresentation().isEnabled());
            assertTrue(sphinxEvent.getPresentation().isVisible());
            assertTrue(sphinxEvent.getPresentation().isEnabled());
            AnActionEvent sectionsEvent = TestActionEvent.createTestEvent(sections);
            sections.update(sectionsEvent);
            assertTrue(sectionsEvent.getPresentation().isEnabled());
            sections.actionPerformed(sectionsEvent);
            tour.actionPerformed(tourEvent);
            viaSphinx.actionPerformed(sphinxEvent);
            assertEquals(List.of("SECTIONS", "TOUR", "SPHINX"), started);
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testInboxShownFollowsWhatThereIsToReach() {
        SectionsModel.Snapshot tour = tour();
        ReviewTabModel.Snapshot none = new ReviewTabModel.Snapshot(List.of(), 0, 0);
        ReviewAnnotation finding = finding("a");

        // No review yet: the ask-for-review panel must stay reachable.
        assertTrue(ReviewWorkbenchEditor.inboxShown(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                none, WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY,
                List.of(), WorkbenchState.InboxChoice.DEFAULT)));
        // A tour, but no findings anywhere: nothing to reach — the pane yields.
        assertFalse(ReviewWorkbenchEditor.inboxShown(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                none, WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY,
                List.of(), WorkbenchState.InboxChoice.DEFAULT, tour,
                WorkbenchState.SectionsChoice.DEFAULT)));
        // A finding, even on a tab that is not shown: the pane stays.
        assertTrue(ReviewWorkbenchEditor.inboxShown(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                none, WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY,
                List.of(finding), WorkbenchState.InboxChoice.DEFAULT, tour,
                WorkbenchState.SectionsChoice.DEFAULT)));
    }

    public void testTheInboxPaneYieldsItsSpaceWhileThereIsNothingToReach() {
        List<ReviewWorkbenchFlow> flows = new ArrayList<>();
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                flowHost(flows, this::inbox));
        try {
            ReviewWorkbenchFlow flow = flows.getFirst();
            ReviewWorkbenchInbox inbox = (ReviewWorkbenchInbox) editor.inboxPane.content();
            assertTrue("expanded to begin with", !editor.inboxPane.isCollapsed());
            assertTrue(inbox.isVisible());

            flow.render(noFindingsTourState());
            assertTrue("no findings anywhere: the pane yields its space (collapsed to its strip)",
                    editor.inboxPane.isCollapsed());
            assertFalse(inbox.isVisible());

            flow.render(oneFindingTourState());
            assertTrue("a finding brings the pane back", !editor.inboxPane.isCollapsed());
            assertTrue(inbox.isVisible());

            flow.render(noFindingsTourState());
            assertTrue(editor.inboxPane.isCollapsed());

            // A comment can start with no findings at all: the composer keeps the pane.
            inbox.openComposer(new ReviewWorkbenchInbox.Composer(
                    ReviewWorkbenchInbox.Composer.Mode.COMMENT,
                    new DiffSelection.Range("A.java", "n10", "n12", false)));
            assertTrue("the composer needs its pane", !editor.inboxPane.isCollapsed());
            assertTrue(inbox.isVisible());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testAnInboxVisibilityFlipRelayoutsTheSplitterAtOnce() {
        if (GraphicsEnvironment.isHeadless()) {
            return; // the relayout is only observable in a peer-created (packable) hierarchy
        }
        List<ReviewWorkbenchFlow> flows = new ArrayList<>();
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                flowHost(flows, this::inbox));
        JFrame frame = new JFrame();
        // The splitter's one-pixel divider asks IdeGlassPaneUtil.find for the root pane's glass
        // pane on addNotify: a bare JFrame's JPanel glass pane is not an IdeGlassPane, so install
        // one, the way the IDE window itself does, before packing.
        frame.getRootPane().setGlassPane(new IdeGlassPaneImpl(frame.getRootPane(), false));
        frame.setContentPane(editor.getComponent());
        frame.pack();
        try {
            JComponent centre = editor.inboxSplitter.getFirstComponent();
            JComponent inbox = (JComponent) editor.inboxPane.content();
            assertTrue(inbox.isVisible());
            int withInbox = centre.getWidth();
            assertTrue("precondition: the packed frame laid the splitters out", withInbox > 0);

            flows.getFirst().render(noFindingsTourState());
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue("no findings anywhere: the pane yields its space (collapsed to its strip)",
                    editor.inboxPane.isCollapsed());
            assertTrue("the yield relayouts the splitter at once: the centre reclaimed the inbox's "
                            + "share (was " + withInbox + ", now " + centre.getWidth() + ")",
                    centre.getWidth() > withInbox);
        } finally {
            frame.dispose();
            Disposer.dispose(editor);
        }
    }

    private Optional<JComponent> inbox(Disposable parent) {
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(inboxActions(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        Disposer.register(parent, inbox);
        return Optional.of(inbox);
    }

    /**
     * A host whose flow is a real one over the scope "s1", recorded in
     * {@code flows}; {@code inbox} builds the findings pane, null for none.
     */
    private static ReviewWorkbenchEditor.Host flowHost(List<ReviewWorkbenchFlow> flows,
                                                       Function<Disposable, Optional<JComponent>> inbox) {
        return new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedFlow> hostFlow(Project project, Disposable parent) {
                ReviewWorkbenchFlow flow = new ReviewWorkbenchFlow(() -> Optional.of("s1"),
                        new ReviewFlowMarks(PropertiesComponent.getInstance(project)), tab -> {
                        });
                ReviewWorkbenchFlowView centre = new ReviewWorkbenchFlowView(project, flow, NO_ACTIONS);
                Disposer.register(parent, centre);
                flows.add(flow);
                return Optional.of(new ReviewWorkbenchEditor.HostedFlow(flow, centre));
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                return inbox == null ? Optional.empty() : inbox.apply(parent);
            }
        };
    }

    /** Flow centre actions that load and store nothing. */
    static final ReviewWorkbenchFlowView.Actions NO_ACTIONS = new ReviewWorkbenchFlowView.Actions() {
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
            return CompletableFuture.completedFuture(Optional.empty());
        }
    };

    private static FlowModel.Hunk hunk(String key, String digest, int added, int removed) {
        return new FlowModel.Hunk(key, "src/A.java", 0, digest, "1", "n1", removed, added, Set.of("n1"), 0, false);
    }

    private static WorkbenchState emptyState(String subject) {
        return WorkbenchState.of(subject, WorkbenchState.Mode.PR, new ReviewTabModel.Snapshot(List.of(), 0, 0),
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT);
    }

    private static WorkbenchState noFindingsTourState() {
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT,
                tour(), WorkbenchState.SectionsChoice.DEFAULT);
    }

    private static WorkbenchState oneFindingTourState() {
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                WorkbenchState.View.CHANGES_ONLY, List.of(finding("a")), WorkbenchState.InboxChoice.DEFAULT,
                tour(), WorkbenchState.SectionsChoice.DEFAULT);
    }

    private static SectionsModel.Snapshot tour() {
        return new SectionsModel.Snapshot(SectionsModel.Source.TOUR, Optional.empty(),
                List.of(new SectionsModel.Section("step:s1", 1, Optional.of("s1"), "Widgets", "Why.",
                        List.of(), List.of(), List.of(), 0, 0, 0)));
    }

    private static ReviewAnnotation finding(String id) {
        return new ReviewAnnotation("s1", id, "A.java", "n1", "n1", Severity.BLOCKING, Confidence.HIGH,
                Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body")), Optional.empty(),
                AnnotationStatus.OPEN, Optional.empty(), false, Triage.PROPOSED, Optional.empty());
    }

    private static ReviewWorkbenchInbox.Actions inboxActions() {
        return new ReviewWorkbenchInbox.Actions() {
            @Override public CompletableFuture<Optional<String>> postToPr(ReviewAnnotation f) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletableFuture<Optional<String>> requestFix(ReviewAnnotation f) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletableFuture<Optional<String>> dismiss(ReviewAnnotation f) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletableFuture<Optional<String>> undo(ReviewAnnotation f) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletableFuture<Optional<String>> comment(ReviewAnnotation f, String t) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public CompletableFuture<Optional<String>> challenge(ReviewAnnotation f, String t) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override public void showInDiff(ReviewAnnotation f) {
            }

            @Override public void choose(WorkbenchState.InboxChoice c) {
            }

            @Override public CompletableFuture<Optional<String>> submit(ReviewWorkbenchInbox.Composer c, String text,
                                                                       Severity severity) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
    }

    private static final ReviewWorkbenchEditor.Host NO_REVIEW = (project, parent) -> Optional.empty();
}
