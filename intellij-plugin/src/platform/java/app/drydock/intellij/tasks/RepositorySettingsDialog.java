package app.drydock.intellij.tasks;

import app.drydock.git.JiraConnection;
import app.drydock.intellij.identity.AgentIdentitySettings;
import app.drydock.intellij.workspace.WorkspaceSettings;
import app.drydock.workspace.WorkspaceBoxNames;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One sectioned settings dialog per registered Drydock project: Jira, GitHub,
 * workspace box (its section shown only while the experimental opt-in flag is
 * on) and agent identity, one dialog instead of per-source menu entries. OK
 * saves every section and then runs the caller's {@code afterSave} — the tree
 * reactions stay with the callers, because only they know which caches a
 * Jira change invalidates and a workspace change does not.
 *
 * <p>The dialog only collects values (spec AC11, carried over): it performs
 * no I/O beyond reading the current values to pre-fill. The Jira secret is
 * read from the password safe before the dialog opens (off the EDT, under a
 * modal progress) and written back after OK (off the EDT, under a modal
 * progress), exactly the flow the old Jira dialog's caller owned — the
 * password safe can reach the OS keychain and block, so neither touch may run
 * on the EDT (spec AC13, AC14).</p>
 */
public final class RepositorySettingsDialog extends DialogWrapper {

    private final SharedProject registered;
    private final JiraSection jiraSection;
    private final GitHubSection githubSection;
    private final WorkspaceSection workspaceSection;
    private final AgentIdentitySection agentIdentitySection;
    private final List<Section> sections;

    /** The dialog as shown on the EDT; package-private so tests can drive its fields. */
    RepositorySettingsDialog(Project project, SharedProject registered, String storedJiraSecret) {
        super(project, true);
        this.registered = registered;
        this.jiraSection = new JiraSection(storedJiraSecret);
        this.githubSection = new GitHubSection();
        // The workspace-box opt-in is read at dialog build time: a flag-off
        // dialog shows no workspace-box surface, matching the menu that never
        // offered the entry without the flag.
        this.workspaceSection = WorkspaceSettings.enabled() ? new WorkspaceSection() : null;
        this.agentIdentitySection = new AgentIdentitySection();
        List<Section> sections = new ArrayList<>(List.of(jiraSection, githubSection));
        if (workspaceSection != null) {
            sections.add(workspaceSection);
        }
        sections.add(agentIdentitySection);
        this.sections = List.copyOf(sections);
        setTitle("Drydock Repository Settings — " + registered.displayName());
        setOKButtonText("Save");
        init();
    }

    /**
     * Opens the settings dialog for {@code registered} and, when the user
     * confirms, saves every section and runs {@code afterSave} once the
     * (possibly failing) Jira credential write settled. EDT; the password
     * safe touches run under modal progress on background threads.
     */
    public static void open(Project project, SharedProject registered, Runnable afterSave) {
        JiraProjectSettings current = JiraProjectSettings.load(registered.id());
        ProgressManager.getInstance().run(new Task.Modal(project, "Reading Jira credentials…", true) {
            private String stored = "";

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // Reading a stored token with nothing configured yet would
                // only mint a password-safe key for a base URL the user has
                // not chosen; skip it.
                stored = current.baseUrl().isEmpty() ? "" : JiraCredentials.read(current).orElse("");
            }

            @Override
            public void onSuccess() {
                show(project, registered, stored, afterSave);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                // The token could not be read, but the rest is still editable.
                show(project, registered, "", afterSave);
            }
        });
    }

    private static void show(Project project, SharedProject registered, String storedJiraSecret,
                             Runnable afterSave) {
        RepositorySettingsDialog dialog = new RepositorySettingsDialog(project, registered, storedJiraSecret);
        if (!dialog.showAndGet()) {
            return;
        }
        JiraProjectSettings jira = dialog.jiraSection.settings();
        for (Section section : dialog.sections) {
            section.save();
        }
        ProgressManager.getInstance().run(new Task.Modal(project, "Saving Jira credentials…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                JiraCredentials.write(jira, dialog.jiraSection.secret());
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Messages.showErrorDialog(project,
                        "Could not store the Jira token (" + error.getClass().getSimpleName() + ")",
                        "Repository Settings");
            }

            @Override
            public void onFinished() {
                // Only after the write: the rebuild must see the new token.
                afterSave.run();
            }
        });
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        FormBuilder builder = FormBuilder.createFormBuilder();
        for (Section section : sections) {
            builder.addComponent(new TitledSeparator(section.title()))
                    .addComponent(section.panel());
        }
        panel.add(scrollable(builder.getPanel()), BorderLayout.NORTH);
        return panel;
    }

    /** The four sections stacked are taller than a modal dialog wants; scroll the rest. */
    private static JComponent scrollable(JComponent panel) {
        panel.setPreferredSize(new Dimension(560, panel.getPreferredSize().height));
        return ScrollPaneFactory.createScrollPane(panel);
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        for (Section section : sections) {
            Optional<ValidationInfo> refusal = section.validate();
            if (refusal.isPresent()) {
                return refusal.orElseThrow();
            }
        }
        return null;
    }

    /** OK only closes; the saves run in {@link #show} off the validation path (spec AC11). */
    @Override
    protected void doOKAction() {
        super.doOKAction();
    }

    /** The sections, package-visible so tests can drive fields and saves directly. */
    JiraSection jiraSection() {
        return jiraSection;
    }

    WorkspaceSection workspaceSection() {
        return workspaceSection;
    }

    AgentIdentitySection agentIdentitySection() {
        return agentIdentitySection;
    }

    /** The Jira section's pre-filled secret field, so tests can read and edit it. */
    JBPasswordField jiraSecretField() {
        return jiraSection.secret;
    }

    /** The workspace box-name field, so tests can read the pre-fill and edit before a save. */
    JBTextField boxNameField() {
        return workspaceSection.boxNameField();
    }

    /** The clone-path field, so tests can read the pre-fill and edit before a save. */
    JBTextField clonePathField() {
        return workspaceSection.clonePathField();
    }

    /** The agent-identity enable checkbox, so tests can flip it before a save. */
    JBCheckBox identityEnabledCheckbox() {
        return agentIdentitySection.enabledCheckbox();
    }

    /** The credential-command field, so tests can read the pre-fill and edit before a save. */
    JBTextField identityCommandField() {
        return agentIdentitySection.credentialCommandField();
    }

    // ---- sections ------------------------------------------------------------

    /** One titled settings area: its form, its validation, and its save. */
    private interface Section {

        String title();

        JComponent panel();

        Optional<ValidationInfo> validate();

        /** The property writes for this section; never the password safe. */
        void save();
    }

    final class JiraSection implements Section {

        private final JBCheckBox enabled =
                new JBCheckBox("Show my Jira issues in this project's Issues section");
        private final ComboBox<JiraConnection.Deployment> deployment =
                new ComboBox<>(JiraConnection.Deployment.values());
        private final JBTextField baseUrl = new JBTextField();
        private final JBTextField email = new JBTextField();
        private final JBLabel secretLabel = new JBLabel("API token:");
        private final JBPasswordField secret = new JBPasswordField();

        JiraSection(String storedSecret) {
            JiraProjectSettings current = JiraProjectSettings.load(registered.id());
            deployment.setRenderer(new DeploymentCellRenderer());
            enabled.setSelected(current.enabled());
            deployment.setSelectedItem(current.deployment());
            baseUrl.setText(current.baseUrl());
            email.setText(current.email().orElse(""));
            secret.setText(storedSecret == null ? "" : storedSecret);
            baseUrl.getEmptyText().setText("https://your-site.atlassian.net");
            deployment.addActionListener(event -> updateDeploymentUi());
            updateDeploymentUi();
        }

        @Override
        public String title() {
            return "Jira";
        }

        @Override
        public JComponent panel() {
            JBLabel secretHint = new JBLabel("Stored in the IDE password safe. Clear it and Save to remove it.");
            secretHint.setForeground(UIUtil.getContextHelpForeground());
            return FormBuilder.createFormBuilder()
                    .addComponent(enabled)
                    .addLabeledComponent("Deployment:", deployment)
                    .addLabeledComponent("Base URL:", baseUrl)
                    .addLabeledComponent("Account email:", email)
                    .addLabeledComponent(secretLabel, secret)
                    .addComponentToRightColumn(secretHint)
                    .getPanel();
        }

        @Override
        public Optional<ValidationInfo> validate() {
            char[] password = secret.getPassword();
            boolean hasSecret = password != null && password.length > 0;
            return JiraProjectSettings.refusal(
                            enabled.isSelected(), selectedDeployment(), baseUrl.getText(), email.getText(), hasSecret)
                    .map(refusal -> new ValidationInfo(refusal.message(), componentFor(refusal.field())));
        }

        @Override
        public void save() {
            settings().save(registered.id());
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
        private static final class DeploymentCellRenderer
                extends SimpleListCellRenderer<JiraConnection.Deployment> {

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

    final class GitHubSection implements Section {

        private final JBCheckBox issues = new JBCheckBox("Show all open issues");
        private final JBCheckBox pullRequests = new JBCheckBox("Show all open pull requests");
        private final JBTextField repository = new JBTextField();

        GitHubSection() {
            GitHubProjectSettings settings = GitHubProjectSettings.load(registered.id());
            issues.setSelected(settings.issuesEnabled());
            pullRequests.setSelected(settings.pullRequestsEnabled());
            repository.setText(settings.repository().orElse(""));
            repository.getEmptyText().setText("Auto-detect from this project's git remote");
        }

        @Override
        public String title() {
            return "GitHub";
        }

        @Override
        public JComponent panel() {
            JBLabel account = new JBLabel("Uses the globally active GitHub CLI account.");
            account.setForeground(UIUtil.getContextHelpForeground());
            JBLabel repositoryHint =
                    new JBLabel("Override only when the git remote does not identify the desired repo.");
            repositoryHint.setForeground(UIUtil.getContextHelpForeground());
            return FormBuilder.createFormBuilder()
                    .addComponent(issues)
                    .addComponent(pullRequests)
                    .addLabeledComponent("Repository:", repository)
                    .addComponentToRightColumn(repositoryHint)
                    .addComponent(account)
                    .getPanel();
        }

        @Override
        public Optional<ValidationInfo> validate() {
            return GitHubProjectSettings.refusal(repository.getText())
                    .map(message -> new ValidationInfo(message, repository));
        }

        @Override
        public void save() {
            new GitHubProjectSettings(issues.isSelected(), pullRequests.isSelected(),
                    Optional.of(repository.getText())).save(registered.id());
        }
    }

    final class WorkspaceSection implements Section {

        private final JBTextField boxName = new JBTextField();
        private final JBTextField clonePath = new JBTextField();

        WorkspaceSection() {
            boxName.setText(WorkspaceSettings.box(registered.id()).orElse(""));
            boxName.getEmptyText().setText("Default: " + WorkspaceBoxNames.defaultName(registered.displayName()));
            clonePath.setText(WorkspaceSettings.clonePath(registered.id()).orElse(""));
            clonePath.getEmptyText().setText("Discovered on first use");
        }

        @Override
        public String title() {
            return "Workspace Box";
        }

        @Override
        public JComponent panel() {
            JBLabel boxHint = new JBLabel(
                    "The box this project's agent sessions run on. A re-created box gets a new name; "
                            + "the old one is left in place.");
            boxHint.setForeground(UIUtil.getContextHelpForeground());
            JBLabel cloneHint = new JBLabel(
                    "Override only when discovery on the box finds none or several clones of this repository.");
            cloneHint.setForeground(UIUtil.getContextHelpForeground());
            return FormBuilder.createFormBuilder()
                    .addLabeledComponent("Box name:", boxName)
                    .addComponentToRightColumn(boxHint)
                    .addLabeledComponent("Clone path:", clonePath)
                    .addComponentToRightColumn(cloneHint)
                    .getPanel();
        }

        @Override
        public Optional<ValidationInfo> validate() {
            Optional<String> box = WorkspaceSettings.boxNameRefusal(boxName.getText());
            if (box.isPresent()) {
                return Optional.of(new ValidationInfo(box.orElseThrow(), boxName));
            }
            return WorkspaceSettings.clonePathRefusal(clonePath.getText())
                    .map(message -> new ValidationInfo(message, clonePath));
        }

        @Override
        public void save() {
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
    }

    final class AgentIdentitySection implements Section {

        private final JBCheckBox enabled = new JBCheckBox(
                "Agent sessions in this repository commit, push and open PRs under an agent identity");
        private final JBTextField credentialCommand = new JBTextField();

        AgentIdentitySection() {
            AgentIdentitySettings settings = AgentIdentitySettings.load(registered.id());
            enabled.setSelected(settings.enabled());
            credentialCommand.setText(settings.credentialCommand());
            credentialCommand.getEmptyText().setText("The shell command that mints the identity");
        }

        @Override
        public String title() {
            return "Agent Identity";
        }

        @Override
        public JComponent panel() {
            JBLabel contract = new JBLabel(
                    "The command prints KEY=VALUE lines on stdout (GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, "
                            + "GIT_COMMITTER_NAME, GIT_COMMITTER_EMAIL, GIT_CREDENTIAL_HELPER_SPEC, GH_TOKEN; "
                            + "all optional). Drydock re-runs it at every launch, and the session's gh "
                            + "re-runs it on every call, so it must be safe to re-run.");
            contract.setForeground(UIUtil.getContextHelpForeground());
            contract.setVerticalTextPosition(JBLabel.TOP);
            JBLabel reference = new JBLabel("See docs/AgentGithubIdentity.md for the contract and an example.");
            reference.setForeground(UIUtil.getContextHelpForeground());
            return FormBuilder.createFormBuilder()
                    .addComponent(enabled)
                    .addLabeledComponent("Credential command:", credentialCommand)
                    .addComponentToRightColumn(contract)
                    .addComponentToRightColumn(reference)
                    .getPanel();
        }

        @Override
        public Optional<ValidationInfo> validate() {
            return AgentIdentitySettings.refusal(enabled.isSelected(), credentialCommand.getText())
                    .map(message -> new ValidationInfo(message, credentialCommand));
        }

        @Override
        public void save() {
            new AgentIdentitySettings(enabled.isSelected(), credentialCommand.getText()).save(registered.id());
        }

        /** The command field as shown, so tests can read the pre-fill and edit before a save. */
        JBTextField credentialCommandField() {
            return credentialCommand;
        }

        /** The enable checkbox as shown, so tests can flip it before a save. */
        JBCheckBox enabledCheckbox() {
            return enabled;
        }
    }
}
