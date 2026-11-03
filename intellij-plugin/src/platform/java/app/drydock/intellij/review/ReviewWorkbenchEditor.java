package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
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
import java.beans.PropertyChangeListener;
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
 * work unchanged here. The inbox and the outbox show empty states until
 * their phases (W2, W5) fill them.</p>
 */
final class ReviewWorkbenchEditor extends UserDataHolderBase implements FileEditor {

    static final String NO_REVIEW_TEXT = "No review is loaded. Open it from the Drydock Review window.";
    static final String INBOX_EMPTY_TEXT = "Findings to triage will appear here.";
    static final String OUTBOX_EMPTY_TEXT = "Decisions to send will appear here.";
    static final String NAVIGATOR_EMPTY_TEXT = "No files to review.";
    static final String CHANGES_ONLY = "Changes Only";
    static final String FULL_FILE = "Full File";
    private static final String TOOLBAR_PLACE = "DrydockReviewWorkbenchHeader";
    static final String PR_LATER = "Pull request reviews arrive with the PR lane";

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
    private final JBPanel<?> navigator = new JBPanel<>(new BorderLayout());
    final OnePixelSplitter navSplitter = new OnePixelSplitter(false, NAV_KEY, 0.2f);
    final OnePixelSplitter inboxSplitter = new OnePixelSplitter(false, INBOX_KEY, 0.75f);
    final OnePixelSplitter outboxSplitter = new OnePixelSplitter(true, OUTBOX_KEY, 0.8f);

    ReviewWorkbenchEditor(Project project, ReviewWorkbenchFile file, Host host) {
        this.file = Objects.requireNonNull(file, "file");
        Optional<HostedDiff> diff = host.hostDiff(project, this);
        JComponent centre = diff.map(HostedDiff::component).orElseGet(() -> emptyState(NO_REVIEW_TEXT));

        navigator.add(host.hostNavigator(project, this, this::renderHeader)
                .orElseGet(() -> emptyState(NAVIGATOR_EMPTY_TEXT)), BorderLayout.CENTER);
        inboxSplitter.setFirstComponent(centre);
        inboxSplitter.setSecondComponent(emptyState(INBOX_EMPTY_TEXT));
        navSplitter.setFirstComponent(navigator);
        navSplitter.setSecondComponent(inboxSplitter);
        outboxSplitter.setFirstComponent(navSplitter);
        outboxSplitter.setSecondComponent(emptyState(OUTBOX_EMPTY_TEXT));

        header.setBorder(JBUI.Borders.empty(4, 8));
        viewActions = new DefaultActionGroup(
                new ViewToggle(project, host, WorkbenchState.View.CHANGES_ONLY, CHANGES_ONLY),
                new ViewToggle(project, host, WorkbenchState.View.FULL_FILE, FULL_FILE));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, viewActions, true);
        modeActions = new DefaultActionGroup(
                new ModeChip(WorkbenchState.Mode.LOCAL), new ModeChip(WorkbenchState.Mode.PR));
        ActionToolbar modes = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, modeActions, true);
        JBPanel<?> top = new JBPanel<>(new BorderLayout());
        top.add(modes.getComponent(), BorderLayout.WEST);
        top.add(header, BorderLayout.CENTER);
        top.add(toolbar.getComponent(), BorderLayout.EAST);
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        toolbar.setTargetComponent(panel);
        modes.setTargetComponent(panel);
        panel.add(top, BorderLayout.NORTH);
        panel.add(outboxSplitter, BorderLayout.CENTER);
        root = panel;
        preferredFocus = diff.map(HostedDiff::preferredFocus).orElse(() -> centre);
    }

    /** The header for {@code state}: the review's subject and its progress. EDT. */
    private void renderHeader(WorkbenchState state) {
        shown = state;
        header.setText(headerText(state));
    }

    /**
     * One mode of the header's chip, selected when the rendered state is in
     * that mode. Only {@link WorkbenchState.Mode#LOCAL} can be chosen until
     * the PR lane (W5); the PR mode is shown, disabled, so the header does
     * not shift when it arrives. Choosing the mode the review is in already
     * changes nothing.
     */
    private final class ModeChip extends DumbAwareToggleAction {
        private final WorkbenchState.Mode mode;

        ModeChip(WorkbenchState.Mode mode) {
            super(mode.label());
            this.mode = mode;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            WorkbenchState state = shown;
            return state != null && state.mode() == mode;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            // LOCAL is the only mode before W5, and it is selected already.
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            boolean available = mode == WorkbenchState.Mode.LOCAL;
            e.getPresentation().setEnabled(available);
            e.getPresentation().setDescription(available ? null : PR_LATER);
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
