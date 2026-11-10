package app.drydock.intellij.review;

import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.requests.DiffRequest;
import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.DiffSelection;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.tour.ReviewTour;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.Side;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.GraphicsEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The review workbench tab in a booted IDE: one reusable file per project,
 * a provider that opens only that file, and an editor whose centre hosts the
 * live review's diff processor and disposes it with the tab.
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
            // The centre's file card; there is no section card without a live review.
            JComponent centre = editor.inboxSplitter.getFirstComponent();
            assertEquals(1, centre.getComponentCount());
            assertEquals(ReviewWorkbenchEditor.NO_REVIEW_TEXT, ((JBLabel) centre.getComponent(0)).getText());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testTheCentreHostsTheReviewDiffSeededWithTheCurrentRequestAndClosingDisposesIt() {
        List<Integer> opens = new ArrayList<>();
        ReviewDiffEditor diff = new ReviewDiffEditor(() -> opens.add(1),
                context -> new SimpleDiffRequest(context.path(), DiffContentFactory.getInstance().createEmpty(),
                        DiffContentFactory.getInstance().createEmpty(), null, null),
                viewer -> {
                });
        diff.selection("row");
        diff.requestReady("row", context("src/A.java"));
        assertEquals("the first show opens the workbench", 1, opens.size());

        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                diffOnly(diff));
        JComponent focus = editor.getPreferredFocusedComponent();
        assertNotNull(focus);
        assertTrue("the diff is inside the workbench",
                SwingUtilities.isDescendingFrom(focus, editor.getComponent()));

        Disposer.dispose(editor);
        // The processor went with the tab: the next selection reopens the workbench.
        diff.selection("row2");
        diff.requestReady("row2", context("src/B.java"));
        assertEquals(2, opens.size());
    }

    public void testTheLayoutIsNavigatorDiffInboxOverTheOutbox() {
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                NO_REVIEW);
        try {
            assertTrue("the outbox sits below", editor.outboxSplitter.isVertical());
            assertSame(editor.navSplitter, editor.outboxSplitter.getFirstComponent());
            assertFalse(editor.navSplitter.isVertical());
            assertSame(editor.inboxSplitter, editor.navSplitter.getSecondComponent());
            assertFalse(editor.inboxSplitter.isVertical());
            assertEquals(ReviewWorkbenchEditor.INBOX_EMPTY_TEXT,
                    ((JBLabel) ((CollapsiblePane) editor.inboxSplitter.getSecondComponent()).content()).getText());
            assertEquals(ReviewWorkbenchEditor.OUTBOX_EMPTY_TEXT,
                    ((JBLabel) editor.outboxSplitter.getSecondComponent()).getText());
            assertEquals(ReviewWorkbenchEditor.NAV_KEY, editor.navSplitter.getSplitterProportionKey());
            assertEquals(ReviewWorkbenchEditor.INBOX_KEY, editor.inboxSplitter.getSplitterProportionKey());
            assertEquals(ReviewWorkbenchEditor.OUTBOX_KEY, editor.outboxSplitter.getSplitterProportionKey());
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

    public void testTheHeaderNamesTheSubjectAndTheProgress() {
        WorkbenchState state = WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                new ReviewTabModel.Snapshot(List.of(), 4, 1), WorkbenchState.Filter.ALL, Optional.empty(),
                WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT);
        assertEquals("drydock  ·  1 / 4 reviewed", ReviewWorkbenchEditor.headerText(state));
    }

    public void testTheHeaderNamesTheKindOfAStoredTour() {
        for (ReviewTour.Kind kind : ReviewTour.Kind.values()) {
            WorkbenchState state = WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                    new ReviewTabModel.Snapshot(List.of(), 4, 1), WorkbenchState.Filter.ALL, Optional.empty(),
                    WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT,
                    new SectionsModel.Snapshot(SectionsModel.Source.TOUR, Optional.empty(), List.of(),
                            Optional.of(kind)),
                    WorkbenchState.SectionsChoice.DEFAULT);
            assertEquals("drydock  ·  1 / 4 reviewed  ·  " + ReviewWorkbenchEditor.kindLabel(kind),
                    ReviewWorkbenchEditor.headerText(state));
        }
        assertEquals("Section review", ReviewWorkbenchEditor.kindLabel(ReviewTour.Kind.SECTIONS));
    }

    public void testTheNavigatorsToggleAsksForTheOtherFilter() {
        List<WorkbenchState.Filter> asked = new ArrayList<>();
        ReviewWorkbenchNavigator navigator = new ReviewWorkbenchNavigator(getProject(), row -> {
        }, asked::add, List.of(), tab -> {
        }, key -> {
        });
        try {
            AnActionEvent event = TestActionEvent.createTestEvent();
            assertFalse(navigator.withFindingsAction().isSelected(event));
            navigator.withFindingsAction().setSelected(event, true);
            assertEquals(List.of(WorkbenchState.Filter.WITH_FINDINGS), asked);

            navigator.render(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                    new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.WITH_FINDINGS,
                    Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT));
            assertTrue("the toggle follows the rendered state", navigator.withFindingsAction().isSelected(event));
        } finally {
            Disposer.dispose(navigator);
        }
    }

    public void testTheNavigatorShowsTheStatesTabAndPassesOnTheUsersTabAndSection() {
        List<WorkbenchState.Navigator> tabs = new ArrayList<>();
        List<String> picked = new ArrayList<>();
        ReviewWorkbenchNavigator navigator = new ReviewWorkbenchNavigator(getProject(), row -> {
        }, filter -> {
        }, List.of(), tabs::add, picked::add);
        try {
            SectionsModel.Snapshot sections = new SectionsModel.Snapshot(SectionsModel.Source.TOUR,
                    Optional.of("Make widgets."), List.of(new SectionsModel.Section("step:s1", 1, Optional.of("s1"),
                    "Widgets", "Why.", List.of(), List.of(2), List.of(), 0, 1, 0)));
            navigator.render(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                    new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                    WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT, sections,
                    WorkbenchState.SectionsChoice.DEFAULT));
            assertEquals("a tour opens the Sections tab", WorkbenchState.Navigator.SECTIONS, navigator.tab());
            assertEquals(1, navigator.sections.list.getModel().getSize());
            assertTrue("a render selects nothing for the user", picked.isEmpty());

            navigator.sections.list.setSelectedIndex(0);
            assertEquals(List.of("step:s1"), picked);

            AnActionEvent event = TestActionEvent.createTestEvent();
            ((ToggleAction) navigator.tabActions.getChildActionsOrStubs()[1]).setSelected(event, true);
            assertEquals(List.of(WorkbenchState.Navigator.FILES), tabs);
        } finally {
            Disposer.dispose(navigator);
        }
    }

    public void testAViewIsTheDiffPlacesCollapseSetting() {
        TextDiffSettingsHolder.TextDiffSettings settings =
                TextDiffSettingsHolder.TextDiffSettings.getSettings(ReviewDiffEditor.PLACE);
        boolean saved = settings.isExpandByDefault();
        try {
            ReviewDiffEditor.applyView(settings, WorkbenchState.View.CHANGES_ONLY);
            assertFalse(settings.isExpandByDefault());
            assertEquals(WorkbenchState.View.CHANGES_ONLY, ReviewDiffEditor.storedView());
            ReviewDiffEditor.applyView(settings, WorkbenchState.View.FULL_FILE);
            assertTrue(settings.isExpandByDefault());
            assertEquals(WorkbenchState.View.FULL_FILE, ReviewDiffEditor.storedView());
        } finally {
            settings.setExpandByDefault(saved);
        }
    }

    public void testShowingAViewReappliesTheCurrentRequest() {
        TextDiffSettingsHolder.TextDiffSettings settings =
                TextDiffSettingsHolder.TextDiffSettings.getSettings(ReviewDiffEditor.PLACE);
        boolean saved = settings.isExpandByDefault();
        List<String> built = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        ReviewDiffEditor diff = new ReviewDiffEditor(() -> { }, context -> {
            built.add(context.path());
            return new SimpleDiffRequest(context.path(), DiffContentFactory.getInstance().createEmpty(),
                    DiffContentFactory.getInstance().createEmpty(), null, null);
        }, viewer -> { });
        try {
            diff.show(context("src/A.java"));
            diff.processorReady(new ReviewDiffEditor.ProcessorHandle() {
                @Override
                public void apply(DiffRequest request) {
                    applied.add(request.getTitle());
                }

                @Override
                public boolean isDisposed() {
                    return false;
                }
            });
            diff.showView(WorkbenchState.View.CHANGES_ONLY);
            assertFalse(settings.isExpandByDefault());
            assertEquals(List.of("src/A.java"), applied);
        } finally {
            settings.setExpandByDefault(saved);
        }
    }

    public void testTheHeaderTogglesAskForTheirViewOnceAStateIsShown() {
        List<WorkbenchState.View> asked = new ArrayList<>();
        List<Consumer<WorkbenchState>> headers = new ArrayList<>();
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                headers.add(header);
                return Optional.empty();
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
                asked.add(view);
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                return Optional.empty();
            }
        };
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                host);
        try {
            ToggleAction changesOnly = (ToggleAction) editor.viewActions.getChildActionsOrStubs()[0];
            ToggleAction fullFile = (ToggleAction) editor.viewActions.getChildActionsOrStubs()[1];
            assertEquals("a text-only toggle shows its text", Boolean.TRUE,
                    changesOnly.getTemplatePresentation().getClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR));
            assertEquals(Boolean.TRUE,
                    fullFile.getTemplatePresentation().getClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR));
            AnActionEvent event = TestActionEvent.createTestEvent();
            assertFalse(changesOnly.isSelected(event));

            headers.get(0).accept(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                    new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                    WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT));
            assertTrue(changesOnly.isSelected(event));
            assertFalse(fullFile.isSelected(event));

            fullFile.setSelected(event, true);
            assertEquals(List.of(WorkbenchState.View.FULL_FILE), asked);
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testTheCentreShowsTheSelectedSectionAndTheFileDiffOtherwise() {
        List<Consumer<WorkbenchState>> headers = new ArrayList<>();
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                headers.add(header);
                return Optional.empty();
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public Optional<JComponent> hostSectionView(Project project, Disposable parent) {
                return Optional.of(new JLabel("section"));
            }
        };
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                host);
        try {
            SectionsModel.Snapshot sections = new SectionsModel.Snapshot(SectionsModel.Source.TOUR, Optional.empty(),
                    List.of(new SectionsModel.Section("step:s1", 1, Optional.of("s1"), "One", "", List.of(),
                            List.of(), List.of(), 0, 0, 0)));
            ReviewTabModel.Snapshot none = new ReviewTabModel.Snapshot(List.of(), 0, 0);
            headers.get(0).accept(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, none,
                    WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                    WorkbenchState.InboxChoice.DEFAULT, sections, new WorkbenchState.SectionsChoice(
                            Optional.of(WorkbenchState.Navigator.SECTIONS), Optional.of("step:s1"))));
            assertEquals(ReviewWorkbenchEditor.CENTRE_SECTION, editor.centreCard());

            headers.get(0).accept(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, none,
                    WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                    WorkbenchState.InboxChoice.DEFAULT, sections, new WorkbenchState.SectionsChoice(
                            Optional.of(WorkbenchState.Navigator.FILES), Optional.of("step:s1"))));
            assertEquals("the Files tab shows the file diff", ReviewWorkbenchEditor.CENTRE_FILE, editor.centreCard());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testThePrDescriptionsFirstParagraphShowsUnderTheIntent() {
        assertEquals("Callers use the result of bar().",
                ReviewWorkbenchSections.firstParagraph("## Summary\nCallers use\n the result of bar().\n\n## Details\nMore.", 400));
        assertEquals("abcd…", ReviewWorkbenchSections.firstParagraph("abcdefgh", 4));

        ReviewWorkbenchSections sections = new ReviewWorkbenchSections(key -> {
        });
        sections.render(WorkbenchState.of("PR #7", WorkbenchState.Mode.PR, new ReviewTabModel.Snapshot(List.of(), 0, 0),
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT).withPrDescription(Optional.of("Make widgets.\n\nMore.")));
        List<String> shown = new ArrayList<>();
        UIUtil.uiTraverser(sections).filter(JTextComponent.class)
                .filter(text -> text.isVisible() && !text.getText().isEmpty()).forEach(text -> shown.add(text.getText()));
        // The prose no longer carries the "PR description: " prefix: the eyebrow label names the block.
        assertTrue(shown.toString(), shown.contains("Make widgets."));
    }

    public void testASectionRowsChipsAndCounts() {
        SectionsModel.Section section = new SectionsModel.Section("step:s2", 2, Optional.of("s2"), "Two", "",
                List.of(1), List.of(3, 4), List.of(), 1, 3, 2);
        assertEquals("builds on §1 · needed by §3, §4", ReviewWorkbenchSections.chips(section));
        assertEquals("1/3  ·  2 findings", ReviewWorkbenchSections.counts(section));
        assertEquals("", ReviewWorkbenchSections.chips(new SectionsModel.Section("rest", 5, Optional.empty(),
                "Not in the tour", "", List.of(), List.of(), List.of(), 0, 0, 0)));
    }

    public void testTheReviewMenuAsksForSectionsTheTourAndSphinxOnlyWhenTheHarnessHasIt() {
        List<String> started = new ArrayList<>();
        boolean[] loaded = {false};
        boolean[] sphinx = {false};
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                return Optional.empty();
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
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

    public void testTheModeChipShowsTheModeTheScopeIsIn() {
        List<Consumer<WorkbenchState>> headers = new ArrayList<>();
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                headers.add(header);
                return Optional.empty();
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                return Optional.empty();
            }
        };
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                host);
        try {
            ToggleAction local = (ToggleAction) editor.modeActions.getChildActionsOrStubs()[0];
            ToggleAction pr = (ToggleAction) editor.modeActions.getChildActionsOrStubs()[1];
            headers.get(0).accept(WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL,
                    new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                    WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT));

            assertEquals("a text-only toggle shows its text", Boolean.TRUE,
                    local.getTemplatePresentation().getClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR));
            assertEquals(Boolean.TRUE, pr.getTemplatePresentation().getClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR));
            AnActionEvent localEvent = TestActionEvent.createTestEvent(local);
            local.update(localEvent);
            assertTrue(local.isSelected(localEvent));
            assertTrue(localEvent.getPresentation().isEnabled());

            AnActionEvent prEvent = TestActionEvent.createTestEvent(pr);
            pr.update(prEvent);
            assertFalse(pr.isSelected(prEvent));
            assertFalse("the mode follows the checkout", prEvent.getPresentation().isEnabled());
            assertEquals(ReviewWorkbenchEditor.MODE_FOLLOWS_CHECKOUT, prEvent.getPresentation().getDescription());

            headers.get(0).accept(WorkbenchState.of("PR #12", WorkbenchState.Mode.PR,
                    new ReviewTabModel.Snapshot(List.of(), 0, 0), WorkbenchState.Filter.ALL, Optional.empty(),
                    WorkbenchState.View.CHANGES_ONLY, List.of(), WorkbenchState.InboxChoice.DEFAULT));
            pr.update(prEvent);
            local.update(localEvent);
            assertTrue("a PR checkout is the PR mode", pr.isSelected(prEvent));
            assertTrue(prEvent.getPresentation().isEnabled());
            assertFalse(localEvent.getPresentation().isEnabled());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testAWorkbenchOpenedBeforeAnySelectionShowsTheSeededMessageNotTheLoadingState() {
        ReviewDiffEditor diff = new ReviewDiffEditor(() -> { }, context -> {
            throw new AssertionError("nothing is selected: no request is built");
        }, viewer -> { });
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                diffOnly(diff));
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            List<String> texts = UIUtil.uiTraverser(editor.getComponent()).filter(JLabel.class).toList().stream()
                    .map(JLabel::getText).filter(text -> text != null).toList();
            assertTrue(texts.toString(), texts.stream().anyMatch(text -> text.contains(ReviewDiffEditor.NO_FILE_TEXT)));
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
        List<Consumer<WorkbenchState>> headers = new ArrayList<>();
        List<Disposable> parents = new ArrayList<>();
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                headers.add(header);
                return Optional.empty();
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(inboxActions(),
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
                Disposer.register(parent, inbox);
                parents.add(parent);
                return Optional.of(inbox);
            }
        };
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                host);
        try {
            ReviewWorkbenchInbox inbox = (ReviewWorkbenchInbox) editor.inboxPane.content();
            assertTrue("expanded to begin with", !editor.inboxPane.isCollapsed());
            assertTrue(inbox.isVisible());

            headers.get(0).accept(noFindingsTourState());
            assertTrue("no findings anywhere: the pane yields its space (collapsed to its strip)",
                    editor.inboxPane.isCollapsed());
            assertFalse(inbox.isVisible());

            headers.get(0).accept(oneFindingTourState());
            assertTrue("a finding brings the pane back", !editor.inboxPane.isCollapsed());
            assertTrue(inbox.isVisible());

            headers.get(0).accept(noFindingsTourState());
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
        List<Consumer<WorkbenchState>> headers = new ArrayList<>();
        ReviewWorkbenchEditor.Host host = new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
                return Optional.empty();
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                headers.add(header);
                return Optional.empty();
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(inboxActions(),
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
                Disposer.register(parent, inbox);
                return Optional.of(inbox);
            }
        };
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                host);
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
            // The inbox sits beside the centre in a horizontal splitter: the share it yields is
            // width. (The frame's height can also re-balance here, because Splitter's minimum
            // sizes skip hidden components in the outer vertical splitter — not what's under test.)
            int withInbox = centre.getWidth();
            assertTrue("precondition: the packed frame laid the splitters out", withInbox > 0);

            headers.get(0).accept(noFindingsTourState());
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

    private static final ReviewWorkbenchEditor.Host NO_REVIEW = new ReviewWorkbenchEditor.Host() {
        @Override
        public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
            return Optional.empty();
        }

        @Override
        public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                  Consumer<WorkbenchState> header) {
            return Optional.empty();
        }

        @Override
        public void changeView(Project project, WorkbenchState.View view) {
        }

        @Override
        public Optional<JComponent> hostInbox(Project project, Disposable parent) {
            return Optional.empty();
        }
    };

    private static ReviewWorkbenchEditor.Host diffOnly(ReviewDiffEditor diff) {
        return new ReviewWorkbenchEditor.Host() {
            @Override
            public Optional<ReviewWorkbenchEditor.HostedDiff> hostDiff(Project project, Disposable parent) {
                return Optional.of(diff.host(project, parent));
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                return Optional.empty();
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                return Optional.empty();
            }
        };
    }

    private static ReviewDiffRequests.RequestContext context(String path) {
        return new ReviewDiffRequests.RequestContext(path,
                new ReviewDiffRequests.Plan("left\n", "right\n", List.<LineFragment>of(), Side.RIGHT, 0));
    }
}
