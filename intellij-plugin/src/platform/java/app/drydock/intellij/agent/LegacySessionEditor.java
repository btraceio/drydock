package app.drydock.intellij.agent;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import java.util.Objects;

/**
 * What a restored per-session {@link AgentConsoleFile} tab opens now: a static,
 * inert placeholder. It builds no pane, console, timer or transcript probe and
 * attaches to no execution, so a tab persisted by an earlier build can never
 * start or show a live agent; the canonical {@link SessionHostEditor} is the
 * only surface that does. The label is fixed text, so there is nothing to
 * refresh and nothing to release on disposal.
 */
final class LegacySessionEditor extends UserDataHolderBase implements FileEditor {

    static final String PLACEHOLDER_TEXT = "This session now lives in Drydock Sessions. Close this tab.";

    private final VirtualFile file;
    private final JPanel root = new JPanel(new BorderLayout());

    LegacySessionEditor(VirtualFile file) {
        this.file = Objects.requireNonNull(file, "file");
        root.add(new JBLabel(PLACEHOLDER_TEXT, SwingConstants.CENTER), BorderLayout.CENTER);
    }

    @Override
    public @NotNull JComponent getComponent() { return root; }

    @Override
    public JComponent getPreferredFocusedComponent() { return null; }

    @Override
    public @NotNull String getName() { return "Agent session"; }

    @Override
    public VirtualFile getFile() { return file; }

    @Override
    public void setState(@NotNull FileEditorState state) { }

    @Override
    public boolean isModified() { return false; }

    @Override
    public boolean isValid() { return true; }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { }

    @Override
    public void dispose() { }
}
