package app.drydock.intellij.agent;

import app.drydock.intellij.agent.SessionDoneBulk.Done;
import app.drydock.intellij.agent.SessionDonePlan.Choice;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
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
import java.util.List;

/**
 * "Clean up done sessions": the per-session Done dialog's two choices,
 * asked once for every done session of the project at once. No per-session
 * detail here -- no task boxes, no risk lines: the risks stay per session,
 * and the removal pass keeps each worktree its own guards refuse, naming
 * it in the report. The summary line says how many agents still run, since
 * a done session may still be running and the choice includes stopping it.
 */
final class SessionDoneBulkDialog extends DialogWrapper {

    private static final int WIDTH = 460;

    private final List<Done> targets;
    private final JBRadioButton forget = new JBRadioButton("Forget tasks only");
    private final JBRadioButton remove = new JBRadioButton("Forget + remove worktrees");
    private final JBCheckBox deleteBranches = new JBCheckBox("Also delete merged branches", true);

    SessionDoneBulkDialog(Project project, List<Done> targets) {
        super(project, true);
        this.targets = List.copyOf(targets);
        setTitle("Clean up done sessions");
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

        long running = targets.stream().filter(Done::running).count();
        JBLabel summary = new JBLabel("<html><div style=\"width:" + JBUI.scale(WIDTH - 40) + "px\">"
                + SessionDonePlan.bulkSummary(targets.size(), (int) running) + "</div></html>");
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(summary);

        root.add(Box.createVerticalStrut(JBUI.scale(12)));
        ButtonGroup group = new ButtonGroup();
        group.add(forget);
        group.add(remove);
        forget.setSelected(true);

        root.add(option(forget, SessionDonePlan.bulkForgetDetail()));
        root.add(Box.createVerticalStrut(JBUI.scale(10)));
        root.add(option(remove, SessionDonePlan.bulkRemoveDetail()));

        deleteBranches.setAlignmentX(Component.LEFT_ALIGNMENT);
        deleteBranches.setBorder(JBUI.Borders.emptyTop(8));
        root.add(deleteBranches);
        root.add(hint(SessionDonePlan.bulkDeleteBranchDetail(), 0));

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
        deleteBranches.setEnabled(remove.isSelected());
        setOKButtonText(SessionDonePlan.primaryLabel(choice(), false));
    }

    Choice choice() {
        return remove.isSelected() ? Choice.FORGET_AND_REMOVE : Choice.FORGET;
    }

    /** Whether merged branches go with the removed worktrees; only meaningful when removing. */
    boolean deleteBranches() {
        return remove.isSelected() && deleteBranches.isSelected();
    }

    /** The per-session dialog's option layout: the detail line indented to the radio's text. */
    private static JComponent option(JBRadioButton button, String detail) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(button);
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
