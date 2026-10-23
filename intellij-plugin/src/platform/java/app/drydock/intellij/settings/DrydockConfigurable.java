package app.drydock.intellij.settings;

import app.drydock.config.UserConfig;
import app.drydock.intellij.settings.PullRequestReviewPrompt;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

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
    private JTextField reviewPromptField;
    /** The saved template, as last loaded or applied; null until loaded. */
    private String loadedPrompt;

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
        UserConfig.loadAsync().whenComplete((config, failure) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (baseField == null) {
                return;   // the page was closed meanwhile
            }
            // A load that failed leaves the field usable (empty: the
            // default), never stuck on "Loading…".
            loaded = failure == null ? config.worktreesDirectory().map(Path::toString).orElse("") : "";
            baseField.setText(loaded);
            baseField.setEnabled(true);
        }, ModalityState.any()));
        reviewPromptField = new JTextField();
        loadedPrompt = PullRequestReviewPrompt.load().orElse("");
        reviewPromptField.setText(loadedPrompt);
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Worktree folder:", baseField)
                .addComponentToRightColumn(hint)
                .addSeparator()
                .addLabeledComponent("Pull-request review prompt:", reviewPromptField)
                .addComponentToRightColumn(reviewPromptHint())
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    public boolean isModified() {
        return (loaded != null && !baseField.getText().strip().equals(loaded))
                || (loadedPrompt != null && !reviewPromptField.getText().strip().equals(loadedPrompt));
    }

    @Override
    public void apply() throws ConfigurationException {
        if (loaded != null) {
            String text = baseField.getText().strip();
            Optional<String> refusal = refusal(text);
            if (refusal.isPresent()) {
                throw new ConfigurationException(refusal.get());
            }
            Optional<Path> base = text.isEmpty() ? Optional.empty() : Optional.of(Path.of(text));
            UserConfig.updateAsync(config -> config.withWorktreesDirectory(base)).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(
                            "Could not save the worktree folder to ~/.drydock/config.json: " + failure.getMessage(),
                            "Drydock"), ModalityState.nonModal());
                }
            });
            loaded = text;
        }
        String prompt = reviewPromptField.getText().strip();
        PullRequestReviewPrompt.save(prompt.isEmpty() ? Optional.empty() : Optional.of(prompt));
        loadedPrompt = prompt;
    }

    /** Why the worktree folder text cannot be saved: it must be a full path, or empty for the default. Pure. */
    public static Optional<String> refusal(String text) {
        if (text.isEmpty() || Path.of(text).isAbsolute()) {
            return Optional.empty();
        }
        return Optional.of("Enter a full folder path, or leave it empty for the default.");
    }

    @Override
    public void reset() {
        if (loaded != null) {
            baseField.setText(loaded);
        }
        if (loadedPrompt != null) {
            reviewPromptField.setText(loadedPrompt);
        }
    }

    @Override
    public void disposeUIResources() {
        baseField = null;
        reviewPromptField = null;
    }

    private static JLabel reviewPromptHint() {
        JLabel hint = new JLabel("<html>Sent to the agent a pull request's Review starts, replacing Drydock's "
                + "own review flow. <i>$PR$</i> becomes the pull-request number,<br>e.g. <code>/sphinx review pr "
                + "$PR$</code>. The command must exist in the target harness. Empty uses Drydock's review.</html>");
        hint.setForeground(UIUtil.getContextHelpForeground());
        return hint;
    }
}
