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
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.JBFont;
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
import java.util.Set;

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
                  List<String> remoteNames, Set<String> localBranches) {
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
        // The width is a floor, applied when the layout asks: a preferred
        // size fixed here, while the panel is still empty, pinned the height
        // to nothing, and the dialog opened with the task squashed to a thin
        // strip that read as a separator rather than a field.
        JPanel root = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                Dimension natural = super.getPreferredSize();
                return new Dimension(Math.max(natural.width, JBUI.scale(WIDTH)), natural.height);
            }
        };
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        task.setLineWrap(true);
        task.setWrapStyleWord(true);
        // A JTextArea takes the LaF's monospaced TextArea font; a task is
        // prose, and every other field in an IDE dialog is in the UI font.
        task.setFont(JBFont.label());
        // The placeholder is an emptyText on JBTextArea in newer platforms;
        // a plain JTextArea in a scroll pane keeps the hint in the label
        // above it, which survives any LaF.
        root.add(section("Task", hint("What should the agent do?")));
        JScrollPane taskScroll = ScrollPaneFactory.createScrollPane(task);
        // Every child of a Y-axis BoxLayout must share one alignmentX. A
        // centred one (a scroll pane's and a plain panel's default) among
        // left-aligned sections makes BoxLayout align them all on a common
        // axis: with the warning showing, every section jumped to the right
        // half of the dialog and the branch field was cut to "farewell-methc".
        taskScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Three rows at all times: BoxLayout hands a shortfall to whichever
        // child can shrink, and when the warning appeared that was the task,
        // squeezed to a single line.
        taskScroll.setMinimumSize(new Dimension(0, taskScroll.getPreferredSize().height));
        root.add(taskScroll);
        root.add(Box.createVerticalStrut(JBUI.scale(12)));

        ButtonGroup agentGroup = new ButtonGroup();
        JPanel agentRow = row();
        for (Agent agent : offeredAgents()) {
            JBRadioButton button = new JBRadioButton(agent.displayName());
            button.setEnabled(agent.isAvailable());
            if (!agent.isAvailable()) {
                button.setToolTipText("Not found on PATH: " + agent.describeSearched());
            }
            button.setSelected(agent.kind() == preselectedAgent());
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
        // One line tall, always: a Y-axis BoxLayout hands spare height to any
        // child without a maximum, and the branch field used to take it and
        // render twice its height.
        branch.setMaximumSize(new Dimension(Integer.MAX_VALUE, branch.getPreferredSize().height));
        worktreeRow.add(branch);
        worktreeRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        worktreeRow.add(hint("from " + inputs.defaultBranch()));
        whereColumn.add(worktreeRow);
        root.add(section("Where", whereColumn));

        warningPanel.setBackground(JBUI.CurrentTheme.Banner.WARNING_BACKGROUND);
        warningPanel.add(warningLabel, BorderLayout.CENTER);
        warningPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        showWarning(Optional.empty());
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
     * The task, so the dialog opens ready to type into. Without this the
     * platform focused the primary button, and the first words typed went
     * nowhere.
     */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return task;
    }

    /** The agents the dialog offers: the agent CLIs, never the plain shell. */
    private List<Agent> offeredAgents() {
        return inputs.agents().stream()
                .filter(agent -> NewAgentSessionPlan.offersAgent(agent.kind()))
                .toList();
    }

    /**
     * The agent selected on open: the remembered one when the dialog offers
     * it, otherwise the first available one. A remembered Terminal would
     * otherwise open the dialog with no agent selected at all.
     */
    private AgentKind preselectedAgent() {
        List<Agent> offered = offeredAgents();
        for (Agent agent : offered) {
            if (agent.kind() == inputs.preselected() && agent.isAvailable()) {
                return agent.kind();
            }
        }
        return offered.stream().filter(Agent::isAvailable).map(Agent::kind).findFirst().orElse(null);
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
        showWarning(warning);
        setOKButtonText(NewAgentSessionPlan.primaryLabel(selectedPlacement(), warning.isPresent()));
        if (worktree) {
            suggestBranch();
        }
        repaint();
    }

    /**
     * Fills or empties the warning's slot. The slot itself never leaves the
     * layout: hiding it made the window either take the warning's height out
     * of the task field (squeezed to one line) or, once re-packed, refuse to
     * shrink back and hand the spare height to the branch field. A slot of
     * constant size keeps the dialog still while Mode and Where are toggled,
     * so the buttons do not move under the pointer either.
     */
    private void showWarning(Optional<String> warning) {
        boolean shown = warning.isPresent();
        // The same outer size either way: a 1 px line plus padding, or 1 px
        // of nothing plus the same padding.
        warningPanel.setBorder(BorderFactory.createCompoundBorder(
                shown ? BorderFactory.createLineBorder(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR)
                        : BorderFactory.createEmptyBorder(1, 1, 1, 1),
                JBUI.Borders.empty(6, 8)));
        warningPanel.setOpaque(shown);
        warningLabel.setIcon(shown ? AllIcons.General.Warning : EmptyIcon.create(AllIcons.General.Warning));
        // A space, not "", so the empty slot keeps a text line's height.
        warningLabel.setText(warning.orElse(" "));
    }

    /** Keeps the branch field in step with the task until the human takes it over. */
    private void suggestBranch() {
        if (branchEdited || !newWorktree.isSelected()) {
            return;
        }
        String suggested = NewAgentSessionPlan.branchSuggestion(task.getText(), inputs.localBranches())
                .orElse("");
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
                NewAgentSessionPlan.existingBranchRefusal(name, inputs.localBranches()).ifPresent(refusal ->
                        problems.add(new ValidationInfo(refusal, branch)));
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
