package app.drydock.intellij.agent;

import app.drydock.intellij.agent.SessionDonePlan.Choice;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
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
 * "Done with this session…": forget the row, or forget it and remove the
 * worktree with this window.
 *
 * <p>Structure and copy follow the UX handoff; the copy and the rules for
 * when removal is offered are {@link SessionDonePlan}'s. Removal that cannot
 * be offered stays visible but disabled, with the reason as its secondary
 * line — an option that silently vanishes leaves the user hunting for it.</p>
 */
final class SessionDoneDialog extends DialogWrapper {

    /** Wide enough for the longest secondary line on one row at default font size. */
    private static final int WIDTH = 460;

    /** Everything resolved off the EDT before the dialog is built. */
    record Inputs(int uncommittedFiles, Optional<String> removalBlocker) {
    }

    private final Inputs inputs;
    private final JBRadioButton forget = new JBRadioButton("Forget session only");
    private final JBRadioButton remove = new JBRadioButton("Forget + remove worktree");

    SessionDoneDialog(Project project, Inputs inputs) {
        super(project, true);
        this.inputs = inputs;
        // The handoff's own words, not the session's title: a title long
        // enough to be useful truncated the window title mid-word, and the
        // session is the tab the user just clicked in.
        setTitle("Done with this session");
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
                .orElseGet(() -> SessionDonePlan.removeDetail(inputs.uncommittedFiles()))));
        root.add(Box.createVerticalStrut(JBUI.scale(16)));
        root.add(hint(SessionDonePlan.footer(), 0));

        forget.addActionListener(event -> refresh());
        remove.addActionListener(event -> refresh());
        return root;
    }

    /** Forget is the safe default, so focus starts on it, not on the primary button. */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return forget;
    }

    private void refresh() {
        setOKButtonText(SessionDonePlan.primaryLabel(choice()));
    }

    /** The choice, valid only after the dialog was closed with OK. */
    Choice choice() {
        return remove.isSelected() ? Choice.FORGET_AND_REMOVE : Choice.FORGET;
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
