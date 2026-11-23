package app.drydock.intellij.tasks;

import app.drydock.git.JiraConnection;

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

/**
 * One user-defined Jira group's name and JQL — the dialog behind both
 * {@code Add Jira Group…} and {@code Edit Jira Group…}. Like the Jira
 * settings dialog it only collects values: no I/O, the caller saves via
 * {@link JiraGroups} and triggers the rebuild.
 *
 * <p>An empty JQL is refused, not defaulted: a group exists to scope
 * issues, and silently falling back to the old default query would hide
 * the mistake. The empty-text hint names the default anyway, as a
 * copyable starting point.</p>
 */
final class JiraGroupDialog extends DialogWrapper {

    private final JBTextField name = new JBTextField();
    private final JBTextField jql = new JBTextField();

    /**
     * {@code current} is the group being edited, or null when adding one;
     * the title and the fields follow.
     */
    JiraGroupDialog(Project project, String projectDisplayName, JiraGroup current) {
        super(project, true);
        if (current != null) {
            name.setText(current.name());
            jql.setText(current.jql());
            setTitle("Edit Jira Group — " + projectDisplayName);
        } else {
            setTitle("Add Jira Group — " + projectDisplayName);
        }
        jql.getEmptyText().setText(JiraConnection.DEFAULT_JQL);
        setOKButtonText(current == null ? "Add" : "Save");
        init();
    }

    /** The typed name, stripped; only meaningful after OK. */
    String name() {
        return name.getText().strip();
    }

    /** The typed JQL, stripped; only meaningful after OK. */
    String jql() {
        return jql.getText().strip();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel jqlHint = new JBLabel("The JQL this group's issues are listed with, in Jira's own order.");
        jqlHint.setForeground(UIUtil.getContextHelpForeground());
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Name:", name)
                .addLabeledComponent("JQL:", jql)
                .addComponentToRightColumn(jqlHint)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (name().isEmpty()) {
            return new ValidationInfo("Give the group a name", name);
        }
        if (jql().isEmpty()) {
            return new ValidationInfo("Enter the JQL this group queries Jira with", jql);
        }
        return null;
    }

    /** Saving is the caller's job: OK only closes the dialog, no I/O here. */
    @Override
    protected void doOKAction() {
        super.doOKAction();
    }
}
