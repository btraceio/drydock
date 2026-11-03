package app.drydock.intellij.review;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The review workbench (spec §3): a file editor tab whose centre hosts the
 * review's diff. The diff is the live review's own
 * {@link ReviewDiffEditor} processor ({@link Host#hostDiff}), created for
 * this editor and disposed with it, so the selection pipeline and the inlay
 * layer of {@code ReviewTabProvider} work unchanged here.
 */
final class ReviewWorkbenchEditor extends UserDataHolderBase implements FileEditor {

    static final String NO_REVIEW_TEXT = "No review is loaded. Open it from the Drydock Review window.";

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

    ReviewWorkbenchEditor(Project project, ReviewWorkbenchFile file, Host host) {
        this.file = Objects.requireNonNull(file, "file");
        Optional<HostedDiff> diff = host.hostDiff(project, this);
        JComponent centre = diff.map(HostedDiff::component).orElseGet(() ->
                new JBLabel(NO_REVIEW_TEXT, SwingConstants.CENTER));
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        panel.add(centre, BorderLayout.CENTER);
        root = panel;
        preferredFocus = diff.map(HostedDiff::preferredFocus).orElse(() -> centre);
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
