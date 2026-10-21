package app.drydock.intellij.tasks;

import app.drydock.projects.SharedProject;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.util.Optional;

/** Quick per-project GitHub configuration; authentication remains owned globally by {@code gh}. */
final class GitHubProjectSettingsDialog extends DialogWrapper {

    private final SharedProject registered;
    private final JBCheckBox issues = new JBCheckBox("Show all open issues");
    private final JBTextField repository = new JBTextField();

    GitHubProjectSettingsDialog(Project project, SharedProject registered) {
        super(project, true);
        this.registered = registered;
        GitHubProjectSettings settings = GitHubProjectSettings.load(registered.id());
        issues.setSelected(settings.issuesEnabled());
        repository.setText(settings.repository().orElse(""));
        repository.getEmptyText().setText("Auto-detect from this project's git remote");
        setTitle("GitHub — " + registered.displayName());
        setOKButtonText("Save");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel account = new JBLabel("Uses the globally active GitHub CLI account.");
        account.setForeground(UIUtil.getContextHelpForeground());
        JBLabel repositoryHint = new JBLabel("Override only when the git remote does not identify the desired repo.");
        repositoryHint.setForeground(UIUtil.getContextHelpForeground());
        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(issues)
                .addLabeledComponent("Repository:", repository)
                .addComponentToRightColumn(repositoryHint)
                .addSeparator()
                .addComponent(account)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        Optional<String> refusal = GitHubProjectSettings.refusal(repository.getText());
        return refusal.map(message -> new ValidationInfo(message, repository)).orElse(null);
    }

    @Override
    protected void doOKAction() {
        new GitHubProjectSettings(issues.isSelected(), Optional.of(repository.getText()))
                .save(registered.id());
        super.doOKAction();
    }
}
