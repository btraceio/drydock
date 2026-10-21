package app.drydock.intellij.agent;

import app.drydock.intellij.agent.CloseWindowPlan.Decision;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.DoNotAskOption.Adapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.Optional;

/**
 * "This window is showing n working agents" (frame F4): asked when a window
 * that shows a working agent closes. Copy from {@link CloseWindowPlan}.
 *
 * <p>Three answers: Cancel keeps the window; Deactivate all &amp; Close; and the
 * default, Keep running &amp; Close. "Don't ask again" remembers Keep
 * running, and only when the user chose it — stopping without asking is not
 * something a ticked box should make the default.</p>
 */
final class CloseWindowDialog extends DialogWrapper {

    /** One listed agent: its dot, its title, and {@code <repo> · this window|hosted[ · needs you]}. */
    record Row(Icon dot, String title, String where) {
    }

    private static final int STOP_ALL_EXIT_CODE = NEXT_USER_EXIT_CODE;

    private final List<Row> rows;

    private CloseWindowDialog(Project project, List<Row> rows) {
        super(project, true);
        this.rows = List.copyOf(rows);
        setTitle(CloseWindowPlan.title(rows.size()));
        setDoNotAskOption(new Adapter() {
            @Override
            public void rememberChoice(boolean isSelected, int exitCode) {
                if (isSelected && exitCode == OK_EXIT_CODE) {
                    PropertiesComponent.getInstance().setValue(CloseWindowPlan.REMEMBERED_KEY,
                            Decision.KEEP_RUNNING.name());
                }
            }

            @Override
            public @NotNull String getDoNotShowMessage() {
                return "Don't ask again";
            }
        });
        init();
        // After init(): before the button exists, "&" is read as a mnemonic.
        setOKButtonText("Keep running & Close");
    }

    /** The answer, empty when the user cancelled (the window stays open). EDT. */
    static Optional<Decision> ask(Project project, List<Row> rows) {
        CloseWindowDialog dialog = new CloseWindowDialog(project, rows);
        dialog.show();
        return switch (dialog.getExitCode()) {
            case OK_EXIT_CODE -> Optional.of(Decision.KEEP_RUNNING);
            case STOP_ALL_EXIT_CODE -> Optional.of(Decision.STOP_ALL);
            default -> Optional.empty();
        };
    }

    /** The remembered answer, if "Don't ask again" was ticked on Keep running. */
    static Optional<Decision> remembered() {
        String value = PropertiesComponent.getInstance().getValue(CloseWindowPlan.REMEMBERED_KEY);
        return Decision.KEEP_RUNNING.name().equals(value) ? Optional.of(Decision.KEEP_RUNNING) : Optional.empty();
    }

    @Override
    protected Action @NotNull [] createActions() {
        Action stopAll = new DialogWrapperAction("Deactivate all && Close") {
            @Override
            protected void doAction(ActionEvent event) {
                close(STOP_ALL_EXIT_CODE);
            }
        };
        return new Action[]{getCancelAction(), stopAll, getOKAction()};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        for (Row row : rows) {
            JPanel line = new JPanel();
            line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
            line.add(new JBLabel(row.title(), row.dot(), JBLabel.LEADING));
            line.add(Box.createHorizontalStrut(JBUI.scale(12)));
            JBLabel where = new JBLabel(row.where());
            where.setForeground(UIUtil.getContextHelpForeground());
            line.add(where);
            line.setBorder(JBUI.Borders.emptyLeft(8));
            root.add(left(line));
            root.add(Box.createVerticalStrut(JBUI.scale(2)));
        }
        root.add(Box.createVerticalStrut(JBUI.scale(8)));
        JBLabel body = new JBLabel(CloseWindowPlan.BODY);
        body.setForeground(UIUtil.getContextHelpForeground());
        root.add(left(body));
        return root;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
