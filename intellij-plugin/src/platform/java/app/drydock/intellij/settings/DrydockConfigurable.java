package app.drydock.intellij.settings;

import app.drydock.config.UserConfig;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Settings › Tools › Drydock: where new task worktrees go.
 *
 * <p>The value lives in {@code ~/.drydock/config.json} ({@link UserConfig}),
 * the file the standalone app reads too, so the base is set once for both
 * tools. Reading and writing that file is I/O: it loads asynchronously and
 * the field says "Loading…" until it has.</p>
 */
public final class DrydockConfigurable implements Configurable {

    private TextFieldWithBrowseButton baseField;
    /** What the file holds, as last loaded or applied; null until loaded. */
    private String loaded;

    /** The folder new worktrees go under: the configured one, else {@code <home>/dev/wt}. */
    public static Path effectiveBase(Optional<Path> configured, Path home) {
        return configured.orElseGet(() -> home.resolve("dev").resolve("wt"));
    }

    @Override
    public @Nls String getDisplayName() {
        return "Drydock";
    }

    @Override
    public @Nullable JComponent createComponent() {
        baseField = new TextFieldWithBrowseButton();
        baseField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Worktree Folder"));
        baseField.setText("Loading…");
        baseField.setEnabled(false);
        String defaultBase = effectiveBase(Optional.empty(), Path.of(System.getProperty("user.home"))).toString();
        JLabel hint = new JLabel("<html>New tasks get a worktree in <i>&lt;folder&gt;/&lt;repo&gt;-&lt;branch&gt;</i>. "
                + "Empty means " + defaultBase + ".<br>Shared with the standalone Drydock app "
                + "(~/.drydock/config.json).</html>");
        hint.setForeground(UIUtil.getContextHelpForeground());
        UserConfig.loadAsync().thenAccept(config -> ApplicationManager.getApplication().invokeLater(() -> {
            loaded = config.worktreesDirectory().map(Path::toString).orElse("");
            baseField.setText(loaded);
            baseField.setEnabled(true);
        }, ModalityState.any()));
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Worktree folder:", baseField)
                .addComponentToRightColumn(hint)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    public boolean isModified() {
        return loaded != null && !baseField.getText().strip().equals(loaded);
    }

    @Override
    public void apply() {
        String text = baseField.getText().strip();
        Optional<Path> base = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
        UserConfig.updateAsync(config -> config.withWorktreesDirectory(base));
        loaded = text;
    }

    @Override
    public void reset() {
        if (loaded != null) {
            baseField.setText(loaded);
        }
    }

    @Override
    public void disposeUIResources() {
        baseField = null;
    }
}
