package app.drydock.intellij.agent;

import app.drydock.delayedtasks.SessionTaskCompletion;
import app.drydock.intellij.agent.SessionDonePlan.Choice;
import app.drydock.intellij.agent.SessionDonePlan.Risks;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskStatus;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.Component;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "Done with this task": forget it, or forget it and remove its worktree.
 * When removing would lose work, the dialog says exactly what — uncommitted
 * and untracked files, commits not merged — and the button becomes
 * {@code Remove anyway}: a warning, never a wall.
 *
 * <p>The session's tasks (T30a, P8) are listed below the choices with
 * default-checked per-task Done boxes; a task awaiting confirmation
 * (D16 review) is shown distinctly, and cancel writes nothing.</p>
 */
final class SessionDoneDialog extends DialogWrapper {

    private static final int WIDTH = 460;

    /** Resolved off the EDT before the dialog opens. {@code branch} is the worktree's, when it has
     * one; {@code tasks} is the pre-dialog task snapshot (T30a) and {@code tasksProblem} why none
     * could be read. */
    record Inputs(Risks risks, Optional<String> removalBlocker, Optional<String> branch,
            List<SessionTaskCompletion.SessionTask> tasks, Optional<String> tasksProblem) {
    }

    private final Inputs inputs;
    private final JBRadioButton forget = new JBRadioButton("Forget task only");
    private final JBRadioButton remove = new JBRadioButton("Forget + remove worktree");
    private final JBLabel risk = new JBLabel();
    private final JBCheckBox deleteBranch = new JBCheckBox();
    /** This session's task Done boxes, in snapshot order (T30a). */
    private final Map<TaskId, JBCheckBox> taskBoxes = new LinkedHashMap<>();

    SessionDoneDialog(Project project, Inputs inputs) {
        super(project, true);
        this.inputs = inputs;
        setTitle("Done with this task");
        init();
        refresh();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                Dimension natural = super.getPreferredSize();
                return new Dimension(Math.max(natural.width, JBUI.scale(WIDTH)), natural.height);
            }
        };
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        ButtonGroup group = new ButtonGroup();
        group.add(forget);
        group.add(remove);
        forget.setSelected(true);
        remove.setEnabled(inputs.removalBlocker().isEmpty());

        root.add(option(forget, SessionDonePlan.forgetDetail()));
        root.add(Box.createVerticalStrut(JBUI.scale(10)));
        root.add(option(remove, inputs.removalBlocker()
                .orElseGet(SessionDonePlan::removeDetail)));

        SessionDonePlan.riskLine(inputs.risks()).ifPresent(line -> {
            // Wrapped at the dialog's width: as one plain line, adding the
            // icon cut it to "1 commit not m...".
            risk.setIcon(AllIcons.General.Warning);
            risk.setText("<html><div style=\"width:" + JBUI.scale(WIDTH - 40) + "px\">Removing loses "
                    + StringUtil.escapeXmlEntities(line) + ".</div></html>");
            risk.setAlignmentX(Component.LEFT_ALIGNMENT);
            risk.setBorder(JBUI.Borders.emptyTop(8));
            root.add(risk);
        });
        inputs.branch().ifPresent(name -> {
            deleteBranch.setText("Also delete branch " + name);
            deleteBranch.setSelected(SessionDonePlan.deleteBranchDefault(inputs.risks()));
            deleteBranch.setAlignmentX(Component.LEFT_ALIGNMENT);
            deleteBranch.setBorder(JBUI.Borders.emptyTop(8));
            root.add(deleteBranch);
        });

        addTasks(root);

        root.add(Box.createVerticalStrut(JBUI.scale(16)));
        root.add(hint(SessionDonePlan.footer(), 0));

        forget.addActionListener(event -> refresh());
        remove.addActionListener(event -> refresh());
        return root;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return forget;
    }

    private void refresh() {
        boolean removing = remove.isSelected();
        risk.setEnabled(removing);
        deleteBranch.setEnabled(removing);
        setOKButtonText(SessionDonePlan.primaryLabel(choice(),
                removing && SessionDonePlan.needsConfirmation(inputs.risks())));
    }

    Choice choice() {
        return remove.isSelected() ? Choice.FORGET_AND_REMOVE : Choice.FORGET;
    }

    /** Whether the branch goes with the worktree; only meaningful when removing. */
    boolean deleteBranch() {
        return remove.isSelected() && inputs.branch().isPresent() && deleteBranch.isSelected();
    }

    /** The per-task Done selection (T30a): the checked boxes only; empty when the dialog
     * listed none. Read after the dialog was accepted; cancelling writes nothing. */
    Set<TaskId> selectedTasks() {
        Set<TaskId> selected = new LinkedHashSet<>();
        taskBoxes.forEach((id, box) -> {
            if (box.isSelected()) {
                selected.add(id);
            }
        });
        return Set.copyOf(selected);
    }

    /** The session's tasks (T30a, P8), from the pre-captured snapshot. */
    private void addTasks(JPanel root) {
        if (inputs.tasks().isEmpty() && inputs.tasksProblem().isEmpty()) {
            return;
        }
        root.add(Box.createVerticalStrut(JBUI.scale(12)));
        root.add(hint("This session's tasks:", 0));
        inputs.tasksProblem().ifPresent(problem -> root.add(hint(problem, 0)));
        for (SessionTaskCompletion.SessionTask task : inputs.tasks()) {
            root.add(taskRow(task));
        }
    }

    /** One task row: a default-checked Done box, or a plain line for a finished task;
     * a task awaiting confirmation (D16 review) is shown distinctly. */
    private JComponent taskRow(SessionTaskCompletion.SessionTask task) {
        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.DROPPED) {
            JComponent finished = hint("'" + task.title() + "' is already " + task.status().storedName(), 0);
            finished.setBorder(BorderFactory.createCompoundBorder(finished.getBorder(),
                    JBUI.Borders.emptyTop(4)));
            return finished;
        }
        JBCheckBox box = new JBCheckBox((task.awaitingReview() ? "Confirm '" : "Mark '")
                + task.title() + "' done", true);
        taskBoxes.put(task.id(), box);
        if (!task.awaitingReview()) {
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setBorder(JBUI.Borders.emptyTop(4));
            return box;
        }
        box.setToolTipText("The agent asked to close this task; your confirmation marks it done");
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.setBorder(JBUI.Borders.emptyTop(4));
        panel.add(box);
        panel.add(hint("awaiting your confirmation - the agent asked to close this task",
                JBUI.scale(18)));
        return panel;
    }

    private static JComponent option(JBRadioButton button, String detail) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(button);
        // Indented to the radio's text, so the line reads as the option's own:
        // the button's width less its text and right inset is exactly where
        // the text starts, in whatever LaF draws the radio.
        int textStart = button.getPreferredSize().width - button.getInsets().right
                - button.getFontMetrics(button.getFont()).stringWidth(button.getText());
        panel.add(hint(detail, textStart));
        return panel;
    }

    private static JComponent hint(String text, int indent) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        // Raw pixels: the indent is measured from already-scaled components.
        label.setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}
