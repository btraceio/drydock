package app.drydock.intellij.agent;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.DoNotAskOption.Adapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.Component;
import java.util.List;

/**
 * "Deactivate n working agent(s)?", before a window close or an IDE quit
 * deactivates agents that are mid-task.
 *
 * <p>Copy comes from {@link StopAgentsPlan}. "Don't ask again" is the
 * dialog's own do-not-ask option, remembered as an application setting
 * ({@link #isSuppressed()}) and only when the user confirmed: cancelling
 * with the box ticked must not silently turn the question off.</p>
 */
public final class StopAgentsDialog extends DialogWrapper {

    /** The application-level "Don't ask again" flag. */


    /** One listed agent: its status dot and its line. */
    record Row(Icon dot, String text) {
    }

    private final String body;
    private final List<Row> rows;

    StopAgentsDialog(@Nullable Project project, int working, String body, List<Row> rows, boolean quitting) {
        this(project, working, body, rows, StopAgentsPlan.primaryLabel(quitting), StopAgentsPlan.dontAskKey(false));
    }

    private StopAgentsDialog(@Nullable Project project, int working, String body, List<Row> rows,
                             String primaryLabel, String dontAskKey) {
        super(project, true);
        this.body = body;
        this.rows = List.copyOf(rows);
        setTitle(StopAgentsPlan.title(working));
        // Adapter by its own import: inside a DialogWrapper subclass the
        // simple name DoNotAskOption means the inherited nested type, which
        // is deprecated for removal.
        setDoNotAskOption(new Adapter() {
            @Override
            public void rememberChoice(boolean isSelected, int exitCode) {
                if (isSelected && exitCode == OK_EXIT_CODE) {
                    PropertiesComponent.getInstance().setValue(dontAskKey, true);
                }
            }

            @Override
            public @NotNull String getDoNotShowMessage() {
                return "Don't ask again";
            }
        });
        init();
        // After init(), not before: set on the action before the button
        // exists, the "&" is parsed as a mnemonic marker and the button read
        // "Deactivate  Close".
        setOKButtonText(primaryLabel);
    }

    /**
     * Asks before stopping one working agent from the Drydock tool window;
     * true to go ahead. Its "Don't ask again" is its own, never the close
     * question's.
     * EDT only.
     */
    public static boolean confirmTreeStop(Project project, Icon dot, String row, boolean closing) {
        if (isSuppressed(StopAgentsPlan.dontAskKey(true))) {
            return true;
        }
        return new StopAgentsDialog(project, 1, StopAgentsPlan.treeBody(closing), List.of(new Row(dot, row)),
                StopAgentsPlan.primaryLabelForTree(closing), StopAgentsPlan.dontAskKey(true)).showAndGet();
    }

    /** Whether the user asked never to be asked again. Safe on the EDT. */
    static boolean isSuppressed() {
        return isSuppressed(StopAgentsPlan.dontAskKey(false));
    }

    private static boolean isSuppressed(String key) {
        return PropertiesComponent.getInstance().getBoolean(key, false);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.add(left(new JBLabel(body)));
        root.add(Box.createVerticalStrut(JBUI.scale(8)));
        for (Row row : rows) {
            JBLabel line = new JBLabel(row.text(), row.dot(), JBLabel.LEADING);
            line.setBorder(JBUI.Borders.emptyLeft(8));
            root.add(left(line));
            root.add(Box.createVerticalStrut(JBUI.scale(2)));
        }
        root.add(Box.createVerticalStrut(JBUI.scale(8)));
        JBLabel footer = new JBLabel(StopAgentsPlan.footer());
        footer.setForeground(UIUtil.getContextHelpForeground());
        root.add(left(footer));
        return root;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
