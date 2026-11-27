package app.drydock.intellij.tasks;

import app.drydock.git.PullRequestLabelFilter;

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
import java.util.Optional;

/**
 * One user-defined pull-request subgroup's name and label filter — the dialog
 * behind both {@code Add PR Group…} and {@code Edit PR Group…}. Like
 * {@link JiraGroupDialog} it only collects and validates values: no I/O, the
 * caller saves via {@link PrGroups} and triggers the rebuild.
 */
final class PrGroupDialog extends DialogWrapper {

    private final JBTextField name = new JBTextField();
    private final JBTextField filter = new JBTextField();

    /**
     * {@code current} is the group being edited, or null when adding one;
     * the title and the fields follow.
     */
    PrGroupDialog(Project project, String projectDisplayName, PrGroup current) {
        super(project, true);
        if (current != null) {
            name.setText(current.name());
            filter.setText(current.filter());
            setTitle("Edit PR Group — " + projectDisplayName);
        } else {
            setTitle("Add PR Group — " + projectDisplayName);
        }
        filter.getEmptyText().setText("comp: profiling & !tag: wip");
        setOKButtonText(current == null ? "Add" : "Save");
        init();
    }

    /** The typed name, stripped; only meaningful after OK. */
    String name() {
        return name.getText().strip();
    }

    /** The typed label filter, stripped; only meaningful after OK. */
    String filter() {
        return filter.getText().strip();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel filterHint = new JBLabel(
                "Combine labels with & (AND), | (OR), ! or - (NOT), and parentheses. Quote labels containing operators.");
        filterHint.setForeground(UIUtil.getContextHelpForeground());
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Name:", name)
                .addLabeledComponent("Labels:", filter)
                .addComponentToRightColumn(filterHint)
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
        Optional<String> refusal = PullRequestLabelFilter.refusal(filter());
        if (refusal.isPresent()) {
            return new ValidationInfo(refusal.get(), filter);
        }
        return null;
    }

    /** Saving is the caller's job: OK only closes the dialog, no I/O here. */
    @Override
    protected void doOKAction() {
        super.doOKAction();
    }
}
