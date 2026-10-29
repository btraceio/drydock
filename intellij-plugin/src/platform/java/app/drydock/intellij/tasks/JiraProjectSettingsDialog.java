package app.drydock.intellij.tasks;

import app.drydock.git.JiraConnection;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.util.Optional;

/**
 * Quick per-project Jira configuration. The dialog only collects values
 * (spec AC11): it performs no I/O — the settings are saved and the token
 * is read from and written to the IDE password safe by the caller, which
 * must do both off the EDT (see {@code JiraCredentials}). The current
 * secret is handed in already read, so the dialog can show that one is
 * stored.
 */
final class JiraProjectSettingsDialog extends DialogWrapper {

    private final JBCheckBox enabled = new JBCheckBox("Show my Jira issues in this project's Issues section");
    private final ComboBox<JiraConnection.Deployment> deployment =
            new ComboBox<>(JiraConnection.Deployment.values());
    private final JBTextField baseUrl = new JBTextField();
    private final JBTextField email = new JBTextField();
    private final JBLabel secretLabel = new JBLabel("API token:");
    private final JBPasswordField secret = new JBPasswordField();

    JiraProjectSettingsDialog(Project project, SharedProject registered, JiraProjectSettings current, String currentSecret) {
        super(project, true);
        deployment.setRenderer(new DeploymentCellRenderer());
        enabled.setSelected(current.enabled());
        deployment.setSelectedItem(current.deployment());
        baseUrl.setText(current.baseUrl());
        email.setText(current.email().orElse(""));
        secret.setText(currentSecret == null ? "" : currentSecret);
        baseUrl.getEmptyText().setText("https://your-site.atlassian.net");
        deployment.addActionListener(event -> updateDeploymentUi());
        updateDeploymentUi();
        setTitle("Jira — " + registered.displayName());
        setOKButtonText("Save");
        init();
    }

    /** The saved form: the visible fields minus the secret, normalized by the record. */
    JiraProjectSettings settings() {
        return new JiraProjectSettings(
                enabled.isSelected(),
                selectedDeployment(),
                baseUrl.getText(),
                Optional.of(email.getText()));
    }

    /** The typed token, possibly empty; an empty value removes the stored credential. */
    String secret() {
        char[] password = secret.getPassword();
        return password == null || password.length == 0 ? "" : new String(password);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel secretHint = new JBLabel("Stored in the IDE password safe. Clear it and Save to remove it.");
        secretHint.setForeground(UIUtil.getContextHelpForeground());
        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(enabled)
                .addLabeledComponent("Deployment:", deployment)
                .addLabeledComponent("Base URL:", baseUrl)
                .addLabeledComponent("Account email:", email)
                .addLabeledComponent(secretLabel, secret)
                .addComponentToRightColumn(secretHint)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        char[] password = secret.getPassword();
        boolean hasSecret = password != null && password.length > 0;
        return JiraProjectSettings.refusal(
                        enabled.isSelected(), selectedDeployment(), baseUrl.getText(), email.getText(), hasSecret)
                .map(refusal -> new ValidationInfo(refusal.message(), componentFor(refusal.field())))
                .orElse(null);
    }

    /** Saving is the caller's job (T9): OK only closes the dialog, no I/O here. */
    @Override
    protected void doOKAction() {
        super.doOKAction();
    }

    /**
     * The account email and the token caption follow the deployment:
     * only Cloud uses an account email paired with an API token, Data
     * Center authenticates with a single personal access token.
     */
    private void updateDeploymentUi() {
        boolean cloud = selectedDeployment() == JiraConnection.Deployment.CLOUD;
        email.setEnabled(cloud);
        secretLabel.setText(cloud ? "API token:" : "Personal access token:");
    }

    private JiraConnection.Deployment selectedDeployment() {
        Object selected = deployment.getSelectedItem();
        return selected instanceof JiraConnection.Deployment value ? value : JiraConnection.Deployment.CLOUD;
    }

    private JComponent componentFor(JiraProjectSettings.Field field) {
        return switch (field) {
            case BASE_URL -> baseUrl;
            case EMAIL -> email;
            case SECRET -> secret;
        };
    }

    /** Shows each deployment's human name; the deprecated {@code create} factories are avoided. */
    private static final class DeploymentCellRenderer extends SimpleListCellRenderer<JiraConnection.Deployment> {

        @Override
        public void customize(
                @Nullable JList<? extends JiraConnection.Deployment> list,
                JiraConnection.Deployment value,
                int index,
                boolean selected,
                boolean hasFocus) {
            setText(value == null ? "" : value.label());
        }
    }
}
