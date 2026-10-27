package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ModelTiers;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;

import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JLabel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The dialog behind {@code Review …}: the review's harness, its starting
 * model, and — when the harness dispatches subagents — the STRONG/MID/LOW
 * subagent model tiers. Everything seeds from the repository's last-used
 * values for the selected harness, and switching harnesses re-seeds, the way
 * the New task dialog's pickers behave. Confirming hands the picked harness,
 * model (empty = the harness's own default) and tiers to the launch, which
 * records them as that repository's last-used values; cancelling cancels the
 * whole review.
 *
 * <p>The tiers are prompt rules, not CLI flags: the review's instruction
 * spends them (the code-review subagent on STRONG, every other subagent on
 * LOW), so they are only asked for when the rules can bind — a harness
 * without subagents, or a review whose custom template replaces that
 * instruction, sees the starting-model row alone and records no tiers.</p>
 *
 * <p>The review instruction never spends MID: its row is there because the
 * tiers are remembered as one set per harness, shared with the Triage and
 * Implement tasks that do spend it, and the row is labelled so.</p>
 *
 * <p>"Also write a guided tour" decides whether Drydock's instruction asks
 * for a tour beside the findings: a tour costs the agent far more than its
 * findings, and a small change rarely needs one. The answer is remembered
 * IDE-wide ({@link #rememberedWithTour}), not per repository: it describes
 * how this reviewer likes to work, not the code. A custom template replaces
 * the instruction, so the box is not shown then.</p>
 */
final class ReviewModelDialog extends DialogWrapper {

    /** The IDE-wide memory of the tour box; absent means a tour (what a review asked for before the box existed). */
    private static final String WITH_TOUR_KEY = "drydock.review.withTour";

    private final List<Agent> agents;
    private final AgentKind preselected;
    private final Function<AgentKind, Optional<String>> lastUsedModel;
    private final Function<AgentKind, ModelTiers> lastUsedTiers;
    private final Predicate<AgentKind> supportsSubagents;
    private final boolean customTemplate;
    private final ModelField modelField;
    private final ModelField strongField;
    private final ModelField midField;
    private final ModelField lowField;
    private final JBCheckBox withTourBox;
    private final Map<AgentKind, JBRadioButton> agentButtons = new LinkedHashMap<>();
    /** Created in {@code createCenterPanel}, where the agent row is at hand. */
    private JPanel tierRows;

    /** The immutable submission captured before the dialog closed. */
    private AgentKind acceptedAgent;
    private Optional<String> acceptedModel;
    private Optional<ModelTiers> acceptedTiers;
    private Boolean acceptedWithTour;

    /**
     * @param preselected   the flow's default harness (the repository's
     *                      last-used agent, else the registry default)
     * @param lastUsedModel the repository's last-used starting model per harness
     * @param lastUsedTiers the repository's last-used subagent tiers per harness
     * @param customTemplate whether a custom review-prompt template replaces
     *                       Drydock's instruction: the tiers would bind nobody
     */
    ReviewModelDialog(Project project, List<Agent> agents, AgentKind preselected,
                      Function<AgentKind, Optional<String>> lastUsedModel,
                      Function<AgentKind, ModelTiers> lastUsedTiers,
                      Function<AgentKind, List<String>> suggestions,
                      Predicate<AgentKind> supportsSubagents, boolean customTemplate) {
        super(project, true);
        this.agents = List.copyOf(agents);
        this.preselected = preselected;
        this.lastUsedModel = lastUsedModel;
        this.lastUsedTiers = lastUsedTiers;
        this.supportsSubagents = supportsSubagents;
        this.customTemplate = customTemplate;
        setTitle("Review with model");
        modelField = new ModelField(suggestions);
        strongField = new ModelField(suggestions);
        midField = new ModelField(suggestions);
        lowField = new ModelField(suggestions);
        withTourBox = new JBCheckBox("Also write a guided tour", rememberedWithTour());
        withTourBox.setToolTipText("The agent walks the change step by step in the Drydock Review window's Tour "
                + "tab. Costs the agent noticeably more than findings alone.");
        init();
        applyAgent(preselected);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel agentsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ButtonGroup group = new ButtonGroup();
        for (Agent agent : agents) {
            JBRadioButton button = new JBRadioButton(agent.displayName());
            button.setEnabled(agent.isAvailable());
            if (!agent.isAvailable()) {
                button.setToolTipText("Not found on PATH: " + agent.describeSearched());
            }
            button.setSelected(agent.kind() == preselected && agent.isAvailable());
            group.add(button);
            agentButtons.put(agent.kind(), button);
            button.addActionListener(e -> applyAgent(agent.kind()));
            agentsRow.add(button);
            agentsRow.add(Box.createHorizontalStrut(JBUI.scale(12)));
        }
        if (agentButtons.values().stream().noneMatch(JBRadioButton::isSelected)) {
            agentButtons.values().stream().filter(JBRadioButton::isEnabled).findFirst()
                    .ifPresent(button -> button.setSelected(true));
        }

        String midLabel = "MID model (Triage/Implement tasks):";
        int labelWidth = Math.max(JBUI.scale(110), new JBLabel(midLabel).getPreferredSize().width);
        tierRows = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(4)));
        tierRows.add(tierRow("STRONG model:", strongField, labelWidth));
        tierRows.add(tierRow(midLabel, midField, labelWidth));
        tierRows.add(tierRow("LOW model:", lowField, labelWidth));
        tierRows.setVisible(tiersShown(selectedAgent()));

        FormBuilder form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Harness:", agentsRow)
                .addLabeledComponent("Starting model:", modelField)
                .addComponent(tierRows, JBUI.scale(4));
        if (!customTemplate) {
            form.addComponent(withTourBox, JBUI.scale(8));
        }
        return form.getPanel();
    }

    private static JPanel tierRow(String label, ModelField field, int labelWidth) {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        JComponent name = new JBLabel(label);
        name.setPreferredSize(new Dimension(labelWidth, name.getPreferredSize().height));
        panel.add(name, BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    /** Points every picker at {@code kind}, seeded from the repository's last-used values. */
    private void applyAgent(AgentKind kind) {
        modelField.setAgent(kind, lastUsedModel);
        // A hidden picker is never pointed at the harness, so it starts no
        // suggestion probe (for pi a models.json read; for the fallback a
        // subprocess).
        if (tiersShown(kind)) {
            strongField.setAgent(kind, k -> lastUsedTiers.apply(k).strong());
            midField.setAgent(kind, k -> lastUsedTiers.apply(k).mid());
            lowField.setAgent(kind, k -> lastUsedTiers.apply(k).low());
        }
        if (tierRows != null) {
            boolean wasShowing = tierRows.isShowing();
            tierRows.setVisible(tiersShown(kind));
            if (tierRows.isShowing() || wasShowing) {
                pack();
            }
        }
    }

    /** The selected harness, falling back to the preselection like the launch would. */
    private AgentKind selectedAgent() {
        return agentButtons.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(preselected);
    }

    /** Whether the tier rows show for {@code kind}: the harness dispatches subagents and no template replaced the instruction. */
    private boolean tiersShown(AgentKind kind) {
        return !customTemplate && supportsSubagents.test(kind);
    }

    @Override
    protected void doOKAction() {
        acceptedAgent = selectedAgent();
        acceptedModel = modelField.selected();
        acceptedTiers = tiersShown(acceptedAgent)
                ? Optional.of(new ModelTiers(strongField.selected(), midField.selected(), lowField.selected()))
                : Optional.empty();
        acceptedWithTour = !customTemplate && withTourBox.isSelected();
        if (!customTemplate) {
            PropertiesComponent.getInstance().setValue(WITH_TOUR_KEY, withTourBox.isSelected(), true);
        }
        super.doOKAction();
    }

    /**
     * The tour box's remembered answer, IDE-wide; true until the reviewer
     * first unticks it. An in-memory read of the application's properties
     * (persisted by the platform), so any thread may call it.
     */
    static boolean rememberedWithTour() {
        return PropertiesComponent.getInstance().getBoolean(WITH_TOUR_KEY, true);
    }

    /** Whether the instruction asks for a guided tour; false under a custom template. Only valid after OK. */
    boolean acceptedWithTour() {
        if (acceptedWithTour == null) {
            throw new IllegalStateException("The review model dialog has not been accepted");
        }
        return acceptedWithTour;
    }

    /** The picked harness. Only valid after OK. */
    AgentKind acceptedAgent() {
        if (acceptedAgent == null) {
            throw new IllegalStateException("The review model dialog has not been accepted");
        }
        return acceptedAgent;
    }

    /** The picked starting model; empty means the harness's own default. Only valid after {@code showAndGet()}. */
    Optional<String> acceptedModel() {
        if (acceptedModel == null) {
            throw new IllegalStateException("The review model dialog has not been accepted");
        }
        return acceptedModel;
    }

    /**
     * The picked subagent tiers: empty when the pickers were not shown,
     * all-empty when they were cleared. Only valid after OK.
     */
    Optional<ModelTiers> acceptedTiers() {
        if (acceptedTiers == null) {
            throw new IllegalStateException("The review model dialog has not been accepted");
        }
        return acceptedTiers;
    }
}
