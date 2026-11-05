package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.git.JiraIssueWriter;

import com.intellij.ide.util.PropertiesComponent;
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
import java.util.Locale;

/**
 * Where a task's Jira mirror goes: the Jira project key and the issue
 * type. Collects values only; the last answer is remembered per Drydock
 * project under {@code drydock.jira.<RepositoryId>.mirror*}, next to the
 * project's other non-secret Jira settings, so the next mirror is one
 * click.
 */
final class JiraMirrorDialog extends DialogWrapper {

    /** The issue type offered when none was used before: present in Jira's default schemes. */
    static final String DEFAULT_ISSUE_TYPE = "Task";

    private final JBTextField projectKey = new JBTextField();
    private final JBTextField issueType = new JBTextField();
    private final String taskTitle;

    JiraMirrorDialog(Project project, String taskTitle, String projectDisplayName, String lastProjectKey,
                     String lastIssueType) {
        super(project, true);
        setTitle("Mirror to Jira — " + projectDisplayName);
        projectKey.setText(lastProjectKey == null ? "" : lastProjectKey);
        projectKey.getEmptyText().setText("PROJ");
        issueType.setText(lastIssueType == null || lastIssueType.isBlank() ? DEFAULT_ISSUE_TYPE : lastIssueType);
        setOKButtonText("Create Ticket");
        this.taskTitle = taskTitle;
        init();
    }

    /** The typed project key, uppercased; only meaningful after OK. */
    String projectKey() {
        return projectKey.getText().strip().toUpperCase(Locale.ROOT);
    }

    /** The typed issue type, stripped; only meaningful after OK. */
    String issueType() {
        return issueType.getText().strip();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel hint = new JBLabel("<html>Creates a ticket from '" + escape(taskTitle) + "'. The task stays here"
                + " and records the ticket's link; the ticket names the task and carries its metadata in a"
                + " hidden issue property.</html>");
        hint.setForeground(UIUtil.getContextHelpForeground());
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Jira project key:", projectKey)
                .addLabeledComponent("Issue type:", issueType)
                .addComponentToRightColumn(hint)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        return JiraIssueWriter.projectKeyRefusal(projectKey()).map(message -> new ValidationInfo(message, projectKey))
                .orElseGet(() -> issueType().isEmpty()
                        ? new ValidationInfo("Name the issue type, e.g. " + DEFAULT_ISSUE_TYPE, issueType)
                        : null);
    }

    static String lastProjectKey(RepositoryId id) {
        return PropertiesComponent.getInstance().getValue(prefix(id) + ".mirrorProjectKey");
    }

    static String lastIssueType(RepositoryId id) {
        return PropertiesComponent.getInstance().getValue(prefix(id) + ".mirrorIssueType");
    }

    /** Remembers the answer for this Drydock project's next mirror. */
    static void remember(RepositoryId id, String projectKey, String issueType) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.setValue(prefix(id) + ".mirrorProjectKey", projectKey);
        properties.setValue(prefix(id) + ".mirrorIssueType", issueType);
    }

    private static String prefix(RepositoryId id) {
        return "drydock.jira." + id;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
