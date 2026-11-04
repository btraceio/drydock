package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.intellij.tasks.WorktreeReview;
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
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The review workbench (spec §3): a file editor tab with a header over
 * three resizable splitters — navigator | diff | inbox above the outbox.
 * Each splitter keeps its proportion per user under its own key
 * ({@link #NAV_KEY}, {@link #INBOX_KEY}, {@link #OUTBOX_KEY}); the platform
 * splitter stores a moved divider at once and loads the stored value when
 * the splitter is first shown.
 *
 * <p>The centre is the live review's own {@link ReviewDiffEditor} processor
 * ({@link Host#hostDiff}), created for this editor and disposed with it, so
 * the selection pipeline and the inlay layer of {@code ReviewTabProvider}
 * work unchanged here. The inbox is the review's findings inbox
 * ({@link ReviewWorkbenchInbox}); the outbox shows an empty state until W5
 * fills it.</p>
 */
final class ReviewWorkbenchEditor extends UserDataHolderBase implements FileEditor {

    static final String NO_REVIEW_TEXT = "No review is loaded. Open it from the Drydock Review window.";
    static final String INBOX_EMPTY_TEXT = "Findings to triage will appear here.";
    static final String OUTBOX_EMPTY_TEXT = "Decisions to send will appear here.";
    static final String NAVIGATOR_EMPTY_TEXT = "No files to review.";
    static final String CHANGES_ONLY = "Changes Only";
    static final String FULL_FILE = "Full File";
    static final String REVIEW_MENU = "Review";
    static final String EDUCATIONAL_TOUR = "Educational Tour…";
    static final String SPHINX_REVIEW = "Sphinx Review";
    private static final String TOOLBAR_PLACE = "DrydockReviewWorkbenchHeader";
    static final String MODE_FOLLOWS_CHECKOUT =
            "The mode follows the checkout: a pull request checkout is reviewed as the pull request";

    /** Navigator | rest. */
    static final String NAV_KEY = "drydock.review.nav";
    /** Diff | inbox. */
    static final String INBOX_KEY = "drydock.review.inbox";
    /** Everything above | outbox. */
    static final String OUTBOX_KEY = "drydock.review.outbox";

    /** The live review's diff, hosted in a workbench editor. */
    record HostedDiff(JComponent component, Supplier<JComponent> preferredFocus) {
        HostedDiff {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(preferredFocus, "preferredFocus");
        }
    }

    /** Where the workbench gets the live review from. Production is {@link #liveHost()}. */
    interface Host {
        /** A diff for a new workbench editor, disposed with {@code parent}; empty when no review is live. EDT. */
        Optional<HostedDiff> hostDiff(Project project, Disposable parent);

        /**
         * A Files navigator for a new workbench editor, with {@code header}
         * fed every {@link WorkbenchState}; both end with {@code parent}.
         * Empty when no review is live. EDT.
         */
        Optional<JComponent> hostNavigator(Project project, Disposable parent, Consumer<WorkbenchState> header);

        /** The user picked Changes only or Full file. EDT. */
        void changeView(Project project, WorkbenchState.View view);

        /** A findings inbox for a new workbench editor, disposed with {@code parent}; empty when no review is live. EDT. */
        Optional<JComponent> hostInbox(Project project, Disposable parent);

        /**
         * The section view for a new workbench editor, disposed with
         * {@code parent}; empty when no review is live, and then the centre
         * always shows the file diff. EDT.
         */
        default Optional<JComponent> hostSectionView(Project project, Disposable parent) {
            return Optional.empty();
        }

        /** The outbox for a new workbench editor, until {@code parent} ends; empty when no review is live. EDT. */
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

        /** Asks an agent to review the workbench's checkout: the educational tour, or sphinx's review. EDT. */
        default void startReview(Project project, boolean sphinx) {
        }
    }

    private final ReviewWorkbenchFile file;
    private final JComponent root;
    private final Supplier<JComponent> preferredFocus;
    private final JBLabel header = new JBLabel();
    /** The last state rendered; the header's toggles read it. EDT. */
    private WorkbenchState shown;
    /** The header's Changes only / Full file toggles. */
    final DefaultActionGroup viewActions;
    /** The header's mode chip: Local changes | Pull request. */
    final DefaultActionGroup modeActions;
    /** The header's Review ▾: the agent reviews that can be asked for. */
    final DefaultActionGroup reviewActions;
    private final JBPanel<?> navigator = new JBPanel<>(new BorderLayout());
    static final String CENTRE_FILE = "file";
    static final String CENTRE_SECTION = "section";
    private final CardLayout centreCards = new CardLayout();
    /** The centre: the file diff, or the selected section's cards. */
    private final JBPanel<?> centre = new JBPanel<>(centreCards);
    private String centreCard = CENTRE_FILE;
    final OnePixelSplitter navSplitter = new OnePixelSplitter(false, NAV_KEY, 0.2f);
    final OnePixelSplitter inboxSplitter = new OnePixelSplitter(false, INBOX_KEY, 0.68f);
    final OnePixelSplitter outboxSplitter = new OnePixelSplitter(true, OUTBOX_KEY, 0.8f);

    ReviewWorkbenchEditor(Project project, ReviewWorkbenchFile file, Host host) {
        this.file = Objects.requireNonNull(file, "file");
        Optional<HostedDiff> diff = host.hostDiff(project, this);
        JComponent fileDiff = diff.map(HostedDiff::component).orElseGet(() -> emptyState(NO_REVIEW_TEXT));
        centre.add(fileDiff, CENTRE_FILE);
        host.hostSectionView(project, this).ifPresent(view -> centre.add(view, CENTRE_SECTION));

        navigator.add(host.hostNavigator(project, this, this::renderHeader)
                .orElseGet(() -> emptyState(NAVIGATOR_EMPTY_TEXT)), BorderLayout.CENTER);
        inboxSplitter.setFirstComponent(centre);
        inboxSplitter.setSecondComponent(host.hostInbox(project, this).orElseGet(() -> emptyState(INBOX_EMPTY_TEXT)));
        navSplitter.setFirstComponent(navigator);
        navSplitter.setSecondComponent(inboxSplitter);
        outboxSplitter.setFirstComponent(navSplitter);
        outboxSplitter.setSecondComponent(host.hostOutbox(project, this).orElseGet(() -> emptyState(OUTBOX_EMPTY_TEXT)));

        header.setBorder(JBUI.Borders.empty(4, 8));
        viewActions = new DefaultActionGroup(
                new ViewToggle(project, host, WorkbenchState.View.CHANGES_ONLY, CHANGES_ONLY),
                new ViewToggle(project, host, WorkbenchState.View.FULL_FILE, FULL_FILE));
        reviewActions = new DefaultActionGroup(REVIEW_MENU, true);
        reviewActions.add(new StartReview(project, host, false));
        reviewActions.add(new StartReview(project, host, true));
        showTextInToolbar(reviewActions);
        ActionToolbar reviews = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE,
                new DefaultActionGroup(reviewActions), true);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, viewActions, true);
        modeActions = new DefaultActionGroup(
                new ModeChip(WorkbenchState.Mode.LOCAL), new ModeChip(WorkbenchState.Mode.PR));
        ActionToolbar modes = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, modeActions, true);
        JBPanel<?> top = new JBPanel<>(new BorderLayout());
        top.add(modes.getComponent(), BorderLayout.WEST);
        top.add(header, BorderLayout.CENTER);
        JBPanel<?> east = new JBPanel<>(new BorderLayout());
        east.add(reviews.getComponent(), BorderLayout.WEST);
        east.add(toolbar.getComponent(), BorderLayout.EAST);
        top.add(east, BorderLayout.EAST);
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        toolbar.setTargetComponent(panel);
        modes.setTargetComponent(panel);
        reviews.setTargetComponent(panel);
        panel.add(top, BorderLayout.NORTH);
        panel.add(outboxSplitter, BorderLayout.CENTER);
        root = panel;
        preferredFocus = diff.map(HostedDiff::preferredFocus).orElse(() -> fileDiff);
    }

    /** A text-only toggle renders as a blank button in a toolbar unless its presentation asks for the text. */
    static void showTextInToolbar(AnAction action) {
        action.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
    }

    /**
     * The header for {@code state} (the review's subject and its progress),
     * and the centre: the selected section when the Sections tab has one,
     * the file diff otherwise. EDT.
     */
    private void renderHeader(WorkbenchState state) {
        shown = state;
        header.setText(headerText(state));
        boolean section = state.shownSection().isPresent() && centre.getComponentCount() > 1;
        String card = section ? CENTRE_SECTION : CENTRE_FILE;
        if (!card.equals(centreCard)) {
            centreCard = card;
            centreCards.show(centre, card);
        }
    }

    /** The centre card shown. EDT. */
    String centreCard() {
        return centreCard;
    }

    /**
     * One entry of the header's Review ▾: starts an agent review of the
     * workbench's checkout. The sphinx entry shows only when the harness a
     * review would pick has the plugin; both are off until the review's
     * scope is loaded.
     */
    private static final class StartReview extends DumbAwareAction {
        private final Project project;
        private final Host host;
        private final boolean sphinx;

        StartReview(Project project, Host host, boolean sphinx) {
            super(sphinx ? SPHINX_REVIEW : EDUCATIONAL_TOUR, sphinx
                    ? "Start a review session whose first prompt is the harness's sphinx command"
                    : "Start a review session that writes the educational tour and its findings", null);
            this.project = project;
            this.host = host;
            this.sphinx = sphinx;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setVisible(!sphinx || host.sphinxReviewAvailable(project));
            e.getPresentation().setEnabled(host.canStartReview(project));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            host.startReview(project, sphinx);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /**
     * One mode of the header's chip, selected when the rendered state is in
     * that mode. The mode follows the review's scope (a scope carrying a
     * pull request is a PR review, spec §8), so the chip shows it and does
     * not switch: the other mode is disabled, and its tooltip says why.
     */
    private final class ModeChip extends DumbAwareToggleAction {
        private final WorkbenchState.Mode mode;

        ModeChip(WorkbenchState.Mode mode) {
            super(mode.label());
            this.mode = mode;
            showTextInToolbar(this);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            WorkbenchState state = shown;
            return state != null && state.mode() == mode;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            // The mode follows the scope; choosing it changes nothing.
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            WorkbenchState state = shown;
            boolean current = state != null && state.mode() == mode;
            e.getPresentation().setEnabled(current);
            e.getPresentation().setDescription(current ? null : MODE_FOLLOWS_CHECKOUT);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** One of the two view choices; selected when the rendered state shows that view. */
    private final class ViewToggle extends DumbAwareToggleAction {
        private final Project project;
        private final Host host;
        private final WorkbenchState.View view;

        ViewToggle(Project project, Host host, WorkbenchState.View view, String text) {
            super(text);
            this.project = project;
            this.host = host;
            this.view = view;
            showTextInToolbar(this);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            WorkbenchState state = shown;
            return state != null && state.view() == view;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            if (selected) {
                host.changeView(project, view);
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            e.getPresentation().setEnabled(shown != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** The header text (pure): "subject · k / n reviewed"; the mode is the chip beside it. */
    static String headerText(WorkbenchState state) {
        return state.subject() + "  ·  " + state.progressText();
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
                return ReviewTabProvider.liveScope(project).isPresent() && project.getBasePath() != null;
            }

            @Override
            public boolean sphinxReviewAvailable(Project project) {
                return ReviewTabProvider.liveScope(project)
                        .map(scope -> WorktreeReview.sphinxAvailable(scope.repoRoot()))
                        .orElse(false);
            }

            @Override
            public void startReview(Project project, boolean sphinx) {
                String base = project.getBasePath();
                ReviewTabProvider.liveScope(project).filter(scope -> base != null).ifPresent(scope ->
                        ReviewTabOpener.startReview(project, Path.of(base), scope, sphinx));
            }

            @Override
            public Optional<HostedDiff> hostDiff(Project project, Disposable parent) {
                return ReviewTabProvider.hostedDiff(project, parent);
            }

            @Override
            public Optional<JComponent> hostNavigator(Project project, Disposable parent,
                                                      Consumer<WorkbenchState> header) {
                return ReviewTabProvider.hostedNavigator(project, parent, header);
            }

            @Override
            public void changeView(Project project, WorkbenchState.View view) {
                ReviewTabProvider.changeView(project, view);
            }

            @Override
            public Optional<JComponent> hostInbox(Project project, Disposable parent) {
                return ReviewTabProvider.hostedInbox(project, parent);
            }

            @Override
            public Optional<JComponent> hostSectionView(Project project, Disposable parent) {
                return ReviewTabProvider.hostedSectionView(project, parent);
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
        // The hosted diff is a Disposer child of this editor.
    }
}
