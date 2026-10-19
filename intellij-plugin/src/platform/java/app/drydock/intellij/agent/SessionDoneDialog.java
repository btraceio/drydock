package app.drydock.intellij.agent;

import app.drydock.intellij.agent.SessionDonePlan.Choice;
import app.drydock.intellij.agent.SessionDonePlan.Risks;

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
import java.util.Optional;

/**
 * "Done with this task": forget it, or forget it and remove its worktree.
 * When removing would lose work, the dialog says exactly what — uncommitted
 * and untracked files, commits not merged — and the button becomes
 * {@code Remove anyway}: a warning, never a wall.
 */
final class SessionDoneDialog extends DialogWrapper {

    private static final int WIDTH = 460;

    /** Resolved off the EDT before the dialog opens. {@code branch} is the worktree's, when it has one. */
    record Inputs(Risks risks, Optional<String> removalBlocker, boolean windowOpen, Optional<String> branch) {
    }

    private final Inputs inputs;
    private final JBRadioButton forget = new JBRadioButton("Forget task only");
    private final JBRadioButton remove = new JBRadioButton("Forget + remove worktree");
    private final JBLabel risk = new JBLabel();
    private final JBCheckBox deleteBranch = new JBCheckBox();

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
                .orElseGet(() -> SessionDonePlan.removeDetail(inputs.windowOpen()))));

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
