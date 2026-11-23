package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.InboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.intellij.tasks.WorktreeReview;
import app.drydock.review.ReviewIntent;
import app.drydock.review.tour.ReviewTour;
import com.intellij.icons.AllIcons;
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The review workbench (design A, "Flow"): a file editor tab with a header
 * over the queue | one hunk | findings panes, and the outbox dock under
 * them. The header names the review, its size and how many hunks are seen,
 * and offers Refresh (off, with "Refreshing…" beside it, until the reload
 * ends), the agent reviews and Finish Review. The queue and the
 * findings pane collapse to strips; the two splitters keep their
 * proportions per user under {@link #NAV_KEY} and {@link #INBOX_KEY} (the
 * platform splitter stores a moved divider at once and loads the stored
 * value when the splitter is first shown). The dock sits below, outside the
 * splitters: opening it squeezes the panes and never covers them.
 *
 * <p>The queue and the centre share one {@link ReviewWorkbenchFlow}, built
 * by the host for this editor and fed every {@link WorkbenchState}; J, K, C
 * and F work anywhere in the tab ({@link ReviewWorkbenchFlowView#installShortcuts}).</p>
 */
final class ReviewWorkbenchEditor extends UserDataHolderBase implements FileEditor {

    static final String NO_REVIEW_TEXT = "No review is loaded. Open it from the Drydock Review window.";
    static final String INBOX_EMPTY_TEXT = "Findings to triage will appear here.";
    static final String QUEUE_EMPTY_TEXT = "No hunks to review.";
    static final String FINISH_REVIEW = "Finish Review";
    static final String REFRESH = "Refresh";
    static final String REFRESH_DESCRIPTION = "Save open documents and re-run the review diff";
    static final String REFRESHING = "Refreshing…";
    static final String REVIEW_MENU = "Agent Review";
    static final String SECTION_REVIEW = "Section Review…";
    static final String EDUCATIONAL_TOUR = "Educational Tour…";
    static final String SPHINX_REVIEW = "Sphinx Review";
    private static final String TOOLBAR_PLACE = "DrydockReviewWorkbenchHeader";

    /** Queue | rest. */
    static final String NAV_KEY = "drydock.review.nav";
    /** Hunk | findings. */
    static final String INBOX_KEY = "drydock.review.inbox";
    /** The queue's and the findings pane's collapsed states, per user. */
    static final String NAV_COLLAPSED_KEY = "drydock.review.nav.collapsed";
    static final String INBOX_COLLAPSED_KEY = "drydock.review.inbox.collapsed";
    /** The panes' minimum widths while expanded, in UI-scaling units. */
    static final int NAV_MIN_WIDTH = 240;
    static final int INBOX_MIN_WIDTH = 320;

    /** The flow review for one workbench editor: its controller and its centre. */
    record HostedFlow(ReviewWorkbenchFlow flow, ReviewWorkbenchFlowView centre) {
        HostedFlow {
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(centre, "centre");
        }
    }

    /** Where the workbench gets the live review from. Production is {@link #liveHost()}. */
    interface Host {
        /**
         * The flow review for a new workbench editor, fed every
         * {@link WorkbenchState} until {@code parent} ends; its centre is
         * disposed with {@code parent}. Empty when no review is live. EDT.
         */
        Optional<HostedFlow> hostFlow(Project project, Disposable parent);

        /** A findings inbox for a new workbench editor, disposed with {@code parent}; empty when no review is live. EDT. */
        default Optional<JComponent> hostInbox(Project project, Disposable parent) {
            return Optional.empty();
        }

        /** The outbox dock for a new workbench editor, until {@code parent} ends; empty when no review is live. EDT. */
        default Optional<JComponent> hostOutbox(Project project, Disposable parent) {
            return Optional.empty();
        }

        /** Whether a review can be asked for: the live review's scope is loaded. EDT, in memory only. */
        default boolean canStartReview(Project project) {
            return false;
        }

        /** Whether the harness a review would pick has the sphinx plugin. EDT, in memory only. */
        default boolean sphinxReviewAvailable(Project project) {
            return false;
        }

        /** Asks an agent to review the workbench's checkout: sections, the educational tour, or sphinx's. EDT. */
        default void startReview(Project project, ReviewTabOpener.ReviewStart start) {
        }

        /**
         * Saves the open documents and re-runs the review's diff; runs
         * {@code done} on the EDT once the reload ends, whatever its outcome. EDT.
         */
        default void refresh(Project project, Runnable done) {
            done.run();
        }
    }

    private final ReviewWorkbenchFile file;
    private final JComponent root;
    private final Supplier<JComponent> preferredFocus;
    private final Optional<ReviewWorkbenchFlow> flow;
    final JBLabel subject = new JBLabel();
    final JBLabel meta = new JBLabel();
    final JProgressBar progress = new JProgressBar(0, 1);
    final JBLabel progressText = new JBLabel();
    final JButton finishReview = new JButton(FINISH_REVIEW);
    /** The header's Review ▾: the agent reviews that can be asked for. */
    final DefaultActionGroup reviewActions;
    /** The header's Refresh: saves the open documents and re-runs the review's diff. */
    final AnAction refreshAction;
    /** Shown while a Refresh runs; Refresh is off meanwhile. */
    final JBLabel refreshing = new JBLabel(REFRESHING);
    private boolean refreshRunning;
    private final ActionToolbar headerToolbar;
    /** The findings pane, hidden while the review has no findings to reach and no composer is open. */
    private final JComponent inboxComponent;
    /** Whether the pane is collapsed by this view's empty-yield (not the user): only our own yield re-expands. */
    private boolean emptyYielded;
    final OnePixelSplitter navSplitter = new OnePixelSplitter(false, NAV_KEY, 0.2f);
    final OnePixelSplitter inboxSplitter = new OnePixelSplitter(false, INBOX_KEY, 0.7f);
    final CollapsiblePane navigatorPane;
    final CollapsiblePane inboxPane;
    /** The dock under the panes, or an empty panel without a live review. */
    final JComponent outbox;

    ReviewWorkbenchEditor(Project project, ReviewWorkbenchFile file, Host host) {
        this.file = Objects.requireNonNull(file, "file");
        Optional<HostedFlow> hosted = host.hostFlow(project, this);
        flow = hosted.map(HostedFlow::flow);
        JComponent centre = hosted.<JComponent>map(HostedFlow::centre).orElseGet(() -> emptyState(NO_REVIEW_TEXT));

        JBPanel<?> navigator = new JBPanel<>(new BorderLayout());
        inboxComponent = host.hostInbox(project, this).orElseGet(() -> emptyState(INBOX_EMPTY_TEXT));
        if (inboxComponent instanceof ReviewWorkbenchInbox inbox) {
            inbox.onComposerChange(this::updateInboxVisibility);
        }
        navigatorPane = new CollapsiblePane(navSplitter, true, navigator, "Review queue", NAV_MIN_WIDTH,
                collapsed -> rememberCollapsed(NAV_COLLAPSED_KEY, collapsed));
        inboxPane = new CollapsiblePane(inboxSplitter, false, inboxComponent, "Findings", INBOX_MIN_WIDTH,
                collapsed -> rememberCollapsed(INBOX_COLLAPSED_KEY, collapsed));
        Optional<ReviewWorkbenchQueue> queue = flow.map(f -> new ReviewWorkbenchQueue(f,
                navigatorPane.detachHeaderToggle()));
        navigator.add(queue.<JComponent>map(q -> q).orElseGet(() -> emptyState(QUEUE_EMPTY_TEXT)),
                BorderLayout.CENTER);
        inboxSplitter.setFirstComponent(centre);
        inboxSplitter.setSecondComponent(inboxPane);
        navSplitter.setFirstComponent(navigatorPane);
        navSplitter.setSecondComponent(inboxSplitter);
        restoreCollapsed(navigatorPane, NAV_COLLAPSED_KEY);
        restoreCollapsed(inboxPane, INBOX_COLLAPSED_KEY);
        outbox = host.hostOutbox(project, this).orElseGet(JBPanel::new);

        subject.setFont(JBUI.Fonts.label(14).asBold());
        meta.setForeground(UIUtil.getContextHelpForeground());
        progressText.setForeground(UIUtil.getContextHelpForeground());
        progress.setPreferredSize(new Dimension(JBUI.scale(140), JBUI.scale(4)));
        finishReview.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true);
        finishReview.addActionListener(e -> flow.ifPresent(ReviewWorkbenchFlow::finish));
        finishReview.setEnabled(flow.isPresent());
        reviewActions = new DefaultActionGroup(REVIEW_MENU, true);
        reviewActions.add(new StartReview(project, host, ReviewTabOpener.ReviewStart.SECTIONS));
        reviewActions.add(new StartReview(project, host, ReviewTabOpener.ReviewStart.TOUR));
        reviewActions.add(new StartReview(project, host, ReviewTabOpener.ReviewStart.SPHINX));
        showTextInToolbar(reviewActions);
        refreshAction = new DumbAwareAction(REFRESH, REFRESH_DESCRIPTION, AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refresh(project, host);
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!refreshRunning);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
        ActionToolbar reviews = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE,
                new DefaultActionGroup(refreshAction, reviewActions), true);
        headerToolbar = reviews;
        refreshing.setForeground(UIUtil.getContextHelpForeground());
        refreshing.setVisible(false);
        JPanel west = new JPanel(new HorizontalLayout(JBUI.scale(16)));
        west.setOpaque(false);
        west.add(subject);
        west.add(meta);
        west.add(progress);
        west.add(progressText);
        JPanel east = new JPanel(new HorizontalLayout(JBUI.scale(8)));
        east.setOpaque(false);
        east.add(refreshing);
        east.add(reviews.getComponent());
        east.add(finishReview);
        JBPanel<?> top = new JBPanel<>(new BorderLayout());
        top.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(8, 16, 8, 12)));
        top.add(west, BorderLayout.WEST);
        top.add(east, BorderLayout.EAST);
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        reviews.setTargetComponent(panel);
        panel.add(top, BorderLayout.NORTH);
        panel.add(navSplitter, BorderLayout.CENTER);
        panel.add(outbox, BorderLayout.SOUTH);
        root = panel;
        hosted.ifPresent(h -> h.centre().installShortcuts(panel, this));
        flow.ifPresent(f -> {
            f.addListener(this::renderHeader);
            renderHeader();
        });
        preferredFocus = queue.<Supplier<JComponent>>map(q -> q::focusTarget).orElse(() -> centre);
    }

    /**
     * Refresh: shows "Refreshing…" and turns Refresh off at once, then asks
     * the host to reload; both clear when the host reports the reload
     * ended. A click while one runs does nothing. EDT.
     */
    private void refresh(Project project, Host host) {
        if (refreshRunning) {
            return;
        }
        refreshRunning = true;
        refreshing.setVisible(true);
        headerToolbar.updateActionsAsync();
        host.refresh(project, () -> {
            refreshRunning = false;
            refreshing.setVisible(false);
            headerToolbar.updateActionsAsync();
        });
    }

    /** A text-only toggle renders as a blank button in a toolbar unless its presentation asks for the text. */
    static void showTextInToolbar(AnAction action) {
        action.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
    }

    /** Persists one pane's collapsed state under its key. */
    private static void rememberCollapsed(String key, boolean collapsed) {
        PropertiesComponent.getInstance().setValue(key, collapsed);
    }

    /** Restores one pane's persisted collapsed state before the splitter is first laid out. */
    private static void restoreCollapsed(CollapsiblePane pane, String key) {
        if (PropertiesComponent.getInstance().getBoolean(key, false)) {
            pane.setCollapsed(true, false);
        }
    }

    /**
     * The header for the flow's state: the review's subject, its size, and
     * how many hunks are seen; the queue's strip says how many are to go.
     * Then the findings pane's visibility. EDT.
     */
    private void renderHeader() {
        ReviewWorkbenchFlow current = flow.orElseThrow();
        WorkbenchState state = current.state();
        FlowModel.Queue queue = current.queue();
        subject.setText(state == null ? "" : state.subject());
        meta.setText(state == null ? "" : metaText(state, queue));
        progress.setMaximum(Math.max(1, queue.order().size()));
        progress.setValue(queue.seenCount(current.seen()));
        progressText.setText(progressText(queue, current.seen()));
        navigatorPane.setStripLabel(ReviewWorkbenchQueue.stripLabel(queue, current.seen()));
        updateInboxVisibility();
    }

    /** The header's size line (pure): "Section review · 3 files · 2 sections · +12 −4". */
    static String metaText(WorkbenchState state, FlowModel.Queue queue) {
        StringBuilder text = new StringBuilder();
        state.sections().kind().ifPresent(kind -> text.append(kindLabel(kind)).append(" · "));
        text.append(queue.files()).append(queue.files() == 1 ? " file" : " files");
        if (queue.sectionCount() > 0) {
            text.append(" · ").append(queue.sectionCount())
                    .append(queue.sectionCount() == 1 ? " section" : " sections");
        }
        return text.append(" · +").append(queue.added()).append(" −").append(queue.removed()).toString();
    }

    /** The header's progress (pure): "5 of 9 hunks seen". */
    static String progressText(FlowModel.Queue queue, Set<String> seen) {
        return queue.seenCount(seen) + " of " + queue.order().size() + " hunks seen";
    }

    /**
     * Shows the findings pane while there is something to reach in it: a
     * finding on any tab, the ask-for-review panel, or an open composer (a
     * comment can start with no findings at all). With nothing, the pane
     * yields its space - collapsed to its strip, never recorded as the
     * user's preference, so it comes back wherever they left it the moment
     * a finding or the composer needs it. EDT.
     */
    private void updateInboxVisibility() {
        WorkbenchState state = flow.map(ReviewWorkbenchFlow::state).orElse(null);
        if (state == null || inboxPane == null) {
            return;
        }
        boolean reachable = inboxShown(state)
                || (inboxComponent instanceof ReviewWorkbenchInbox inbox && inbox.composer().isPresent());
        if (reachable) {
            if (emptyYielded) {
                emptyYielded = false;
                inboxPane.setCollapsed(false, false); // restore our yield, not the user's collapse
            }
        } else if (!inboxPane.isCollapsed()) {
            emptyYielded = true;
            inboxPane.setCollapsed(true, false);
        }
    }

    /** Whether {@code state}'s review has anything for the inbox (pure): no review yet, or a finding on any tab. */
    static boolean inboxShown(WorkbenchState state) {
        if (ReviewWorkbenchInbox.unreviewed(state)) {
            return true;
        }
        for (InboxModel.Tab tab : InboxModel.Tab.values()) {
            if (state.inbox().count(tab) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * One entry of the header's Review ▾: starts an agent review of the
     * workbench's checkout. The sphinx entry shows only when the harness a
     * review would pick has the plugin; every entry is off until the
     * review's scope is loaded.
     */
    private static final class StartReview extends DumbAwareAction {
        private final Project project;
        private final Host host;
        private final ReviewTabOpener.ReviewStart start;

        StartReview(Project project, Host host, ReviewTabOpener.ReviewStart start) {
            super(startLabel(start), startDescription(start), null);
            this.project = project;
            this.host = host;
            this.start = start;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setVisible(start != ReviewTabOpener.ReviewStart.SPHINX
                    || host.sphinxReviewAvailable(project));
            e.getPresentation().setEnabled(host.canStartReview(project));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            host.startReview(project, start);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** An agent review's name, in the header's menu and the inbox's buttons (pure). */
    static String startLabel(ReviewTabOpener.ReviewStart start) {
        return switch (start) {
            case SECTIONS -> SECTION_REVIEW;
            case TOUR -> EDUCATIONAL_TOUR;
            case SPHINX -> SPHINX_REVIEW;
        };
    }

    /** What an agent review does, as its tooltip (pure). */
    static String startDescription(ReviewTabOpener.ReviewStart start) {
        return switch (start) {
            case SECTIONS -> "Start a review session that groups the change into sections, with its findings";
            case TOUR -> "Start a review session that writes the educational tour and its findings";
            case SPHINX -> "Start a review session whose first prompt is the harness's sphinx command";
        };
    }

    /** What a stored tour is called in the header (pure). */
    static String kindLabel(ReviewTour.Kind kind) {
        return switch (kind) {
            case TOUR -> "Educational tour";
            case SECTIONS -> "Section review";
        };
    }

    private static JComponent emptyState(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    /** The production host: the project's live Review content ({@code ReviewTabProvider}). */
    static Host liveHost() {
        return new Host() {
            @Override
            public boolean canStartReview(Project project) {
                return ReviewTabOpener.remoteContext(project).isEmpty()
                        && ReviewTabProvider.liveScope(project).isPresent() && project.getBasePath() != null;
            }

            @Override
            public boolean sphinxReviewAvailable(Project project) {
                return ReviewTabOpener.remoteContext(project).isEmpty() && ReviewTabProvider.liveScope(project)
                        .map(scope -> WorktreeReview.sphinxAvailable(scope.repoRoot()))
                        .orElse(false);
            }

            @Override
            public void startReview(Project project, ReviewTabOpener.ReviewStart start) {
                String base = project.getBasePath();
                ReviewTabProvider.liveScope(project).filter(scope -> base != null
                                && ReviewTabOpener.remoteContext(project).isEmpty()).ifPresent(scope ->
                        ReviewTabOpener.startReview(project, Path.of(base), scope, start,
                                ReviewIntent.Origin.WORKBENCH));
            }

            @Override
            public void refresh(Project project, Runnable done) {
                ReviewTabProvider.refresh(project, done);
            }

            @Override
            public Optional<HostedFlow> hostFlow(Project project, Disposable parent) {
                return ReviewTabProvider.hostedFlow(project, parent);
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                return ReviewTabProvider.hostedInbox(project, parent);
            }

            @Override
            public Optional<JComponent> hostOutbox(Project project, Disposable parent) {
                return ReviewTabProvider.hostedOutbox(project, parent);
            }
        };
    }

    @Override
    public @NotNull JComponent getComponent() {
        return root;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return preferredFocus.get();
    }

    @Override
    public @NotNull String getName() {
        return ReviewWorkbenchFile.TITLE;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        // The flow's centre and the hosted panes are Disposer children of this editor.
    }
}
