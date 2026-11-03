package app.drydock.intellij.review;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
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
    }

    private final ReviewWorkbenchFile file;
    private final JComponent root;
    private final Supplier<JComponent> preferredFocus;
    private final JBLabel header = new JBLabel();
    private final JBPanel<?> navigator = new JBPanel<>(new BorderLayout());
    final OnePixelSplitter navSplitter = new OnePixelSplitter(false, NAV_KEY, 0.2f);
    final OnePixelSplitter inboxSplitter = new OnePixelSplitter(false, INBOX_KEY, 0.75f);
    final OnePixelSplitter outboxSplitter = new OnePixelSplitter(true, OUTBOX_KEY, 0.8f);

    ReviewWorkbenchEditor(Project project, ReviewWorkbenchFile file, Host host) {
        this.file = Objects.requireNonNull(file, "file");
        Optional<HostedDiff> diff = host.hostDiff(project, this);
        JComponent centre = diff.map(HostedDiff::component).orElseGet(() -> emptyState(NO_REVIEW_TEXT));

        navigator.add(emptyState(NAVIGATOR_EMPTY_TEXT), BorderLayout.CENTER);
        inboxSplitter.setFirstComponent(centre);
        inboxSplitter.setSecondComponent(emptyState(INBOX_EMPTY_TEXT));
        navSplitter.setFirstComponent(navigator);
        navSplitter.setSecondComponent(inboxSplitter);
        outboxSplitter.setFirstComponent(navSplitter);
        outboxSplitter.setSecondComponent(emptyState(OUTBOX_EMPTY_TEXT));

        header.setBorder(JBUI.Borders.empty(4, 8));
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(outboxSplitter, BorderLayout.CENTER);
        root = panel;
        preferredFocus = diff.map(HostedDiff::preferredFocus).orElse(() -> centre);
    }

    private static JComponent emptyState(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.CENTER);
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    /** The production host: the project's live Review content ({@code ReviewTabProvider}). */
    static Host liveHost() {
        return ReviewTabProvider::hostedDiff;
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
