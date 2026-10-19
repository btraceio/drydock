package app.drydock.intellij.actions;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.SessionMode;
import app.drydock.git.BranchNameRules;
import app.drydock.intellij.actions.NewAgentSessionPlan.Placement;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "New Agent Session…": what the agent should do, which agent, whether it may
 * write, and where it runs.
 *
 * <p>Structure and copy follow the UX handoff; every colour comes from the
 * LaF, including the warning tint
 * ({@code JBUI.CurrentTheme.Banner.WARNING_*}). The decisions — default
 * placement per mode, which warning applies, what the primary button is
 * called — are {@link NewAgentSessionPlan}'s and are tested without Swing.</p>
 *
 * <p>Agent and mode are radio groups rather than the handoff's segmented
 * chips: the platform's segmented control is Kotlin-UI-DSL-only, and a radio
 * group is the IDE's own rendering of an exclusive choice, focusable and
 * arrow-navigable. The semantics are identical; only the pill shape is not.</p>
 */
final class NewAgentSessionDialog extends DialogWrapper {

    /** The handoff's ~520 px. */
    private static final int WIDTH = 520;

    /** Everything resolved off the EDT before the dialog can be built. */
    record Inputs(Path workingDirectory, String repositoryName, String currentBranch, String defaultBranch,
                  List<Agent> agents, AgentKind preselected, boolean anotherWriterActive,
                  List<String> remoteNames) {
    }

    /** What the user chose. */
    record Choice(String task, AgentKind agent, SessionMode mode, Placement placement, String branch) {
    }

    private final Inputs inputs;

    private final JTextArea task = new JTextArea(3, 40);
    private final Map<AgentKind, JBRadioButton> agentButtons = new LinkedHashMap<>();
    private final JBRadioButton writes = new JBRadioButton("Writes");
    private final JBRadioButton readOnly = new JBRadioButton("Read-only");
    private final JBRadioButton here;
    private final JBRadioButton newWorktree;
    private final JBTextField branch = new JBTextField();
    private final JBLabel warningLabel = new JBLabel();
    private final JPanel warningPanel = new JPanel(new BorderLayout());

    /** True once the human edits the branch, after which the task stops rewriting it. */
    private boolean branchEdited;

    NewAgentSessionDialog(Project project, Inputs inputs) {
        super(project, true);
        this.inputs = inputs;
        this.here = new JBRadioButton("Here — " + inputs.repositoryName() + " / " + inputs.currentBranch());
        this.newWorktree = new JBRadioButton("New worktree");
        setTitle("New Agent Session");
        init();
        applyModeDefaults();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setPreferredSize(new Dimension(JBUI.scale(WIDTH), root.getPreferredSize().height));

        task.setLineWrap(true);
        task.setWrapStyleWord(true);
        // The placeholder is an emptyText on JBTextArea in newer platforms;
        // a plain JTextArea in a scroll pane keeps the hint in the label
        // above it, which survives any LaF.
        root.add(section("Task", hint("What should the agent do?")));
        JScrollPane taskScroll = new JScrollPane(task);
        taskScroll.setPreferredSize(new Dimension(JBUI.scale(WIDTH - 40), JBUI.scale(66)));
        root.add(taskScroll);
        root.add(Box.createVerticalStrut(JBUI.scale(12)));

        ButtonGroup agentGroup = new ButtonGroup();
        JPanel agentRow = row();
        for (Agent agent : inputs.agents()) {
            JBRadioButton button = new JBRadioButton(agent.displayName());
            button.setEnabled(agent.isAvailable());
            if (!agent.isAvailable()) {
                button.setToolTipText("Not found on PATH: " + agent.describeSearched());
            }
            button.setSelected(agent.kind() == inputs.preselected());
            agentGroup.add(button);
            agentButtons.put(agent.kind(), button);
            agentRow.add(button);
            agentRow.add(Box.createHorizontalStrut(JBUI.scale(8)));
        }
        root.add(section("Agent", agentRow));
        root.add(Box.createVerticalStrut(JBUI.scale(12)));

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(writes);
        modeGroup.add(readOnly);
        writes.setSelected(true);
        JPanel modeRow = row();
        modeRow.add(writes);
        modeRow.add(Box.createHorizontalStrut(JBUI.scale(8)));
        modeRow.add(readOnly);
        root.add(section("Mode", modeRow));
        root.add(Box.createVerticalStrut(JBUI.scale(12)));

        ButtonGroup whereGroup = new ButtonGroup();
        whereGroup.add(here);
        whereGroup.add(newWorktree);
        JPanel whereColumn = new JPanel();
        whereColumn.setLayout(new BoxLayout(whereColumn, BoxLayout.Y_AXIS));
        whereColumn.add(here);
        JPanel worktreeRow = row();
        worktreeRow.add(newWorktree);
        worktreeRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        worktreeRow.add(new JBLabel("⑂"));
        worktreeRow.add(Box.createHorizontalStrut(JBUI.scale(4)));
        branch.setColumns(22);
        worktreeRow.add(branch);
        worktreeRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        worktreeRow.add(hint("from " + inputs.defaultBranch()));
        whereColumn.add(worktreeRow);
        root.add(section("Where", whereColumn));

        warningLabel.setIcon(AllIcons.General.Warning);
        warningPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR),
                JBUI.Borders.empty(6, 8)));
        warningPanel.setBackground(JBUI.CurrentTheme.Banner.WARNING_BACKGROUND);
        warningPanel.add(warningLabel, BorderLayout.CENTER);
        warningPanel.setVisible(false);
        root.add(Box.createVerticalStrut(JBUI.scale(12)));
        root.add(warningPanel);

        task.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                suggestBranch();
            }
        });
        branch.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                branchEdited = true;
            }
        });
        writes.addActionListener(event -> applyModeDefaults());
        readOnly.addActionListener(event -> applyModeDefaults());
        here.addActionListener(event -> refreshPlacement());
        newWorktree.addActionListener(event -> refreshPlacement());
        return root;
    }

    /**
     * Mode decides the default placement (writes → its own worktree,
     * read-only → here). Only the default: the human can still override it,
     * and a later mode change re-applies the default because the two choices
     * are coupled by intent, not by constraint.
     */
    private void applyModeDefaults() {
        Placement placement = NewAgentSessionPlan.defaultPlacement(selectedMode());
        here.setSelected(placement == Placement.HERE);
        newWorktree.setSelected(placement == Placement.NEW_WORKTREE);
        refreshPlacement();
    }

    private void refreshPlacement() {
        boolean worktree = newWorktree.isSelected();
        branch.setEnabled(worktree);
        Optional<String> warning = NewAgentSessionPlan.warning(
                selectedMode(), selectedPlacement(), inputs.anotherWriterActive());
        warningLabel.setText(warning.orElse(""));
        warningPanel.setVisible(warning.isPresent());
        setOKButtonText(NewAgentSessionPlan.primaryLabel(selectedPlacement(), warning.isPresent()));
        if (worktree) {
            suggestBranch();
        }
        repaint();
    }

    /** Keeps the branch field in step with the task until the human takes it over. */
    private void suggestBranch() {
        if (branchEdited || !newWorktree.isSelected()) {
            return;
        }
        String suggested = NewAgentSessionPlan.branchSuggestion(task.getText()).orElse("");
        if (!suggested.equals(branch.getText())) {
            boolean wasEdited = branchEdited;
            branch.setText(suggested);
            branchEdited = wasEdited;   // our own write must not count as an edit
        }
    }

    @Override
    protected @NotNull List<ValidationInfo> doValidateAll() {
        List<ValidationInfo> problems = new ArrayList<>();
        if (task.getText().isBlank()) {
            problems.add(new ValidationInfo("Say what the agent should do.", task));
        }
        if (selectedAgent() == null) {
            problems.add(new ValidationInfo("No agent CLI is available.", null));
        }
        if (newWorktree.isSelected()) {
            String name = branch.getText().strip();
            if (name.isEmpty()) {
                problems.add(new ValidationInfo("Name the branch for the new worktree.", branch));
            } else {
                // The core's own rules, so the dialog and the agent-facing
                // refusals cannot disagree about what a legal branch is.
                BranchNameRules.check(name, inputs.remoteNames()).ifPresent(refusal ->
                        problems.add(new ValidationInfo(BranchNameRules.humanSentence(refusal), branch)));
            }
        }
        return problems;
    }

    /** The choice, valid only after the dialog was closed with OK. */
    Choice choice() {
        return new Choice(task.getText().strip(), selectedAgent(), selectedMode(), selectedPlacement(),
                branch.getText().strip());
    }

    private SessionMode selectedMode() {
        return readOnly.isSelected() ? SessionMode.READ_ONLY : SessionMode.WRITES;
    }

    private Placement selectedPlacement() {
        return newWorktree.isSelected() ? Placement.NEW_WORKTREE : Placement.HERE;
    }

    private AgentKind selectedAgent() {
        for (Map.Entry<AgentKind, JBRadioButton> entry : agentButtons.entrySet()) {
            if (entry.getValue().isSelected() && entry.getValue().isEnabled()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static JPanel row() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JBLabel hint(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    private static JComponent section(String title, JComponent body) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JBLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(JBColor.foreground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(JBUI.scale(4)));
        panel.add(body);
        return panel;
    }
}
