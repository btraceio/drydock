package app.drydock.intellij.workspace;

import app.drydock.projects.SharedProject;
import app.drydock.workspace.WorkspaceBoxNames;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Per-project workspace-box configuration, modeled on the tasks layer's
 * {@code GitHubProjectSettingsDialog}: the box-name override and the
 * box-side clone path — the manual escape hatch when auto-discovery cannot
 * decide (ASM-17). Modal UI with no process spawns: OK writes both fields
 * into {@link WorkspaceSettings}, which the launch path reads when a remote
 * session actually starts. The box name is validated by
 * {@link WorkspaceBoxNames} — never option-escaped — and the clone path must
 * be a full path or empty for discovery.
 */
public final class WorkspaceBoxDialog extends DialogWrapper {

    private final SharedProject registered;
    private final JBTextField boxName = new JBTextField();
    private final JBTextField clonePath = new JBTextField();

    public WorkspaceBoxDialog(Project project, SharedProject registered) {
        super(project, true);
        this.registered = registered;
        boxName.setText(WorkspaceSettings.box(registered.id()).orElse(""));
        boxName.getEmptyText().setText("Default: " + WorkspaceBoxNames.defaultName(registered.displayName()));
        clonePath.setText(WorkspaceSettings.clonePath(registered.id()).orElse(""));
        clonePath.getEmptyText().setText("Discovered on first use");
        setTitle("Workspace Box — " + registered.displayName());
        setOKButtonText("Save");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel boxHint = new JBLabel("The box this project's agent sessions run on. A re-created box gets a new name; the old one is left in place.");
        boxHint.setForeground(UIUtil.getContextHelpForeground());
        JBLabel cloneHint = new JBLabel("Override only when discovery on the box finds none or several clones of this repository.");
        cloneHint.setForeground(UIUtil.getContextHelpForeground());
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Box name:", boxName)
                .addComponentToRightColumn(boxHint)
                .addLabeledComponent("Clone path:", clonePath)
                .addComponentToRightColumn(cloneHint)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        Optional<String> box = boxNameRefusal(boxName.getText());
        if (box.isPresent()) {
            return new ValidationInfo(box.orElseThrow(), boxName);
        }
        return clonePathRefusal(clonePath.getText())
                .map(message -> new ValidationInfo(message, clonePath))
                .orElse(null);
    }

    @Override
    protected void doOKAction() {
        save();
        super.doOKAction();
    }

    /** Writes both fields into {@link WorkspaceSettings}; a blank value clears the override. */
    void save() {
        WorkspaceSettings.setBox(registered.id(), boxName.getText());
        WorkspaceSettings.setClonePath(registered.id(), clonePath.getText());
    }

    /** The box-name field as shown, so tests can read the pre-fill and edit before a save. */
    JBTextField boxNameField() {
        return boxName;
    }

    /** The clone-path field as shown, so tests can read the pre-fill and edit before a save. */
    JBTextField clonePathField() {
        return clonePath;
    }

    /** Why the box-name text cannot be saved: a CLI-valid name or empty. Pure. */
    static Optional<String> boxNameRefusal(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || WorkspaceBoxNames.validate(value)) {
            return Optional.empty();
        }
        return Optional.of("Use lowercase letters, digits, and dashes, starting alphanumeric, or leave it empty for the default.");
    }

    /** Why the clone-path text cannot be saved: a full path or empty. Pure. */
    static Optional<String> clonePathRefusal(String path) {
        String value = path == null ? "" : path.strip();
        if (value.isEmpty() || Path.of(value).isAbsolute()) {
            return Optional.empty();
        }
        return Optional.of("Enter a full path on the box, or leave it empty for auto-discovery.");
    }
}
