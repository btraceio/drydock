package app.drydock.intellij.settings;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.config.UserConfig;
import app.drydock.editorqa.EditorAskHarnessPolicy;
import app.drydock.editorqa.EditorAskLimits;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.workspace.WorkspaceSettings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Settings › Tools › Drydock: where new task worktrees go, and the
 * experimental workspace-box opt-in.
 *
 * <p>The worktree value lives in {@code ~/.drydock/config.json}
 * ({@link UserConfig}), so the base is set once per machine rather than per
 * IDE configuration. Reading and writing that file is I/O: it loads
 * asynchronously and the field says "Loading…" until it has. The
 * workspace-box flag and the editor Q&amp;A harness choice live in the IDE's
 * own application properties ({@code WorkspaceSettings},
 * {@code EditorAskSettings}), so a fresh install stores nothing.</p>
 */
public final class DrydockConfigurable implements Configurable {

    private TextFieldWithBrowseButton baseField;
    /** What the file holds, as last loaded or applied; null until loaded. */
    private String loaded;
    private JBCheckBox workspaceBoxes;
    private ComboBox<EditorAskHarnessPolicy.Choice> askHarness;
    private JTextField askPiModel;
    private JTextField askClaudeModel;
    private JTextField askCodexModel;
    /** Which Q&A agent CLIs the registry found; "Checking…" until the off-EDT read lands. */
    private JLabel askAvailability;

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
                + "Empty means " + defaultBase + " (~/.drydock/config.json).</html>");
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
        workspaceBoxes = new JBCheckBox("Run agent sessions on workspace boxes (experimental)");
        // The flag is IDE memory, not the async config file: read it now.
        workspaceBoxes.setSelected(WorkspaceSettings.enabled());
        JLabel boxHint = new JLabel("<html>Experimental: agent sessions then run in tmux on a Datadog workspace box "
                + "and survive the IDE closing.<br>Per-project boxes are configured from the Tasks tree's "
                + "project menu. Off, every session stays local.</html>");
        boxHint.setForeground(UIUtil.getContextHelpForeground());
        askAvailability = new JLabel("Checking installed agents…");
        askPiModel = new JTextField(EditorAskSettings.model(AgentKind.PI).orElse(""));
        askClaudeModel = new JTextField(EditorAskSettings.model(AgentKind.CLAUDE).orElse(""));
        askCodexModel = new JTextField(EditorAskSettings.model(AgentKind.CODEX).orElse(""));
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Worktree folder:", baseField)
                .addComponentToRightColumn(hint)
                .addSeparator()
                .addComponent(workspaceBoxes)
                .addComponentToRightColumn(boxHint)
                .addSeparator()
                .addComponent(new JLabel("Editor Q&A"))
                .addSeparator()
                .addLabeledComponent("Harness:", askHarness())
                .addComponentToRightColumn(askHint())
                .addLabeledComponent("Pi model:", askPiModel)
                .addLabeledComponent("Claude model:", askClaudeModel)
                .addLabeledComponent("Codex model:", askCodexModel)
                .addComponentToRightColumn(new JLabel("<html>Optional; empty uses the harness's own default."
                        + " A model applies to its harness only.<br>Applied to new conversations;"
                        + " a live conversation keeps its original harness and model.</html>"))
                .addComponent(askAvailability)
                .getPanel();
        scheduleAvailabilityCheck();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    /** The harness combo, preselected with the stored choice. */
    private ComboBox<EditorAskHarnessPolicy.Choice> askHarness() {
        // The items are the Choice values themselves (rendered by label), so
        // every path reads the selection back without decoding a string.
        askHarness = new ComboBox<>(EditorAskHarnessPolicy.Choice.values());
        askHarness.setRenderer(SimpleListCellRenderer.create("", EditorAskHarnessPolicy.Choice::label));
        askHarness.setSelectedItem(EditorAskSettings.harness());
        return askHarness;
    }

    private JLabel askHint() {
        JLabel hint = new JLabel("<html>The agent behind right-click \"Ask Agent\" questions."
                + " Auto uses the first installed of Pi, Claude, Codex.<br>A pinned harness that is"
                + " missing fails the ask visibly instead of sending it to another agent.</html>");
        hint.setForeground(UIUtil.getContextHelpForeground());
        return hint;
    }

    /** Which agent CLIs are installed, read from the registry off the EDT (registry probing blocks). */
    private void scheduleAvailabilityCheck() {
        CompletableFuture.supplyAsync(() -> {
            try {
                DrydockPluginService plugin = DrydockPluginService.getInstance();
                plugin.initialization().get(60, TimeUnit.SECONDS);
                AgentRegistry registry = plugin.registry();
                StringBuilder installed = new StringBuilder();
                for (AgentKind kind : EditorAskHarnessPolicy.QA_PREFERENCE) {
                    if (registry.isAvailable(kind)) {
                        installed.append(installed.length() == 0 ? "Installed: " : ", ").append(kind.persistedName());
                    }
                }
                return installed.length() == 0
                        ? "No Q&A agent (Pi, Claude, Codex) is installed."
                        : installed.toString();
            } catch (Exception failure) {
                return "Could not check installed agents.";
            }
        }).thenAccept(text -> ApplicationManager.getApplication().invokeLater(() -> {
            if (askAvailability == null) {
                return;   // the page was closed meanwhile
            }
            askAvailability.setText(text);
        }, ModalityState.any()));
    }

    @Override
    public boolean isModified() {
        return workspaceBoxes != null && workspaceBoxes.isSelected() != WorkspaceSettings.enabled()
                || loaded != null && !baseField.getText().strip().equals(loaded)
                || askHarnessModified() || askModelsModified();
    }

    private boolean askHarnessModified() {
        return askHarness != null
                && askHarness.getItem() != EditorAskSettings.harness();
    }

    private boolean askModelsModified() {
        return askModelModified(askPiModel, AgentKind.PI) || askModelModified(askClaudeModel, AgentKind.CLAUDE)
                || askModelModified(askCodexModel, AgentKind.CODEX);
    }

    private boolean askModelModified(JTextField field, AgentKind kind) {
        return field != null && !field.getText().strip().equals(EditorAskSettings.model(kind).orElse(""));
    }

    @Override
    public void apply() throws ConfigurationException {
        // Validate before writing anything, so a refused model leaves the
        // earlier sections (and the other model fields) untouched.
        checkAskModel(askPiModel, "Pi");
        checkAskModel(askClaudeModel, "Claude");
        checkAskModel(askCodexModel, "Codex");
        applyAsk();
        if (workspaceBoxes != null && workspaceBoxes.isSelected() != WorkspaceSettings.enabled()) {
            WorkspaceSettings.setEnabled(workspaceBoxes.isSelected());
        }
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
    }

    /** Why the worktree folder text cannot be saved: it must be a full path, or empty for the default. Pure. */
    public static Optional<String> refusal(String text) {
        if (text.isEmpty() || Path.of(text).isAbsolute()) {
            return Optional.empty();
        }
        return Optional.of("Enter a full folder path, or leave it empty for the default.");
    }

    /** Applies the Q&A section; every model has already passed {@link #checkAskModel}. */
    private void applyAsk() {
        if (askHarness != null) {
            EditorAskSettings.setHarness(askHarness.getItem());
        }
        applyAskModel(askPiModel, AgentKind.PI);
        applyAskModel(askClaudeModel, AgentKind.CLAUDE);
        applyAskModel(askCodexModel, AgentKind.CODEX);
    }

    /**
     * Why a model field cannot be saved: it must satisfy the feature's model
     * bound ({@link EditorAskLimits#checkModel} -- single line, at most 256
     * UTF-8 bytes, no line/terminal controls) or be empty.
     */
    private static void checkAskModel(JTextField field, String harness) throws ConfigurationException {
        if (field == null) {
            return;
        }
        try {
            EditorAskLimits.checkModel(field.getText());
        } catch (EditorAskLimits.RejectedInputException e) {
            throw new ConfigurationException(harness + " model: " + e.getMessage());
        }
    }

    private static void applyAskModel(JTextField field, AgentKind kind) {
        if (field != null) {
            EditorAskSettings.setModel(kind, field.getText());
        }
    }

    @Override
    public void reset() {
        if (workspaceBoxes != null) {
            workspaceBoxes.setSelected(WorkspaceSettings.enabled());
        }
        if (loaded != null) {
            baseField.setText(loaded);
        }
        if (askHarness != null) {
            askHarness.setSelectedItem(EditorAskSettings.harness());
        }
        if (askPiModel != null) {
            askPiModel.setText(EditorAskSettings.model(AgentKind.PI).orElse(""));
        }
        if (askClaudeModel != null) {
            askClaudeModel.setText(EditorAskSettings.model(AgentKind.CLAUDE).orElse(""));
        }
        if (askCodexModel != null) {
            askCodexModel.setText(EditorAskSettings.model(AgentKind.CODEX).orElse(""));
        }
    }

    /** The harness combo's current selection; test seam. */
    EditorAskHarnessPolicy.Choice selectedAskHarness() {
        return askHarness.getItem();
    }

    /** Selects a harness in the combo without applying; test seam. */
    void selectAskHarness(EditorAskHarnessPolicy.Choice choice) {
        askHarness.setSelectedItem(choice);
    }

    @Override
    public void disposeUIResources() {
        baseField = null;
        workspaceBoxes = null;
        askHarness = null;
        askPiModel = null;
        askClaudeModel = null;
        askCodexModel = null;
        askAvailability = null;
    }
}
