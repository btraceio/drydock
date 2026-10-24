package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ModelTiers;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;

import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The dialog behind {@code Review …}: the review's starting model plus, when
 * the review's harness dispatches subagents, the STRONG/MID/LOW subagent
 * model tiers. Everything seeds from the repository's last-used values for
 * the review's harness. Confirming hands the picked model (empty = the
 * harness's own default) and tiers to the launch, which records them as that
 * repository's last-used values; cancelling cancels the whole review. The
 * agent itself is not asked here: the review flow picks it, as it always has.
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
 */
final class ReviewModelDialog extends DialogWrapper {

    private final ModelField modelField;
    private final ModelField strongField;
    private final ModelField midField;
    private final ModelField lowField;
    private final boolean tiersApply;

    /** The immutable submission captured before the dialog closed. */
    private Optional<String> acceptedModel;
    private Optional<ModelTiers> acceptedTiers;

    /**
     * @param tiersApply whether the tier rows show: the harness dispatches
     *                   subagents and the review runs Drydock's own
     *                   instruction (no custom template)
     */
    ReviewModelDialog(Project project, AgentKind agent, Optional<String> lastUsedModel, ModelTiers lastUsedTiers,
                      Function<AgentKind, List<String>> suggestions, boolean tiersApply) {
        super(project, true);
        setTitle("Review with model");
        modelField = new ModelField(suggestions);
        modelField.setAgent(agent, kind -> lastUsedModel);
        strongField = new ModelField(suggestions);
        midField = new ModelField(suggestions);
        lowField = new ModelField(suggestions);
        // A hidden picker is never pointed at the harness, so it starts no
        // suggestion probe (for pi a subprocess).
        if (tiersApply) {
            strongField.setAgent(agent, kind -> lastUsedTiers.strong());
            midField.setAgent(agent, kind -> lastUsedTiers.mid());
            lowField.setAgent(agent, kind -> lastUsedTiers.low());
        }
        this.tiersApply = tiersApply;
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        FormBuilder builder = FormBuilder.createFormBuilder()
                .addLabeledComponent("Starting model:", modelField);
        if (tiersApply) {
            String midLabel = "MID model (Triage/Implement tasks):";
            int labelWidth = Math.max(JBUI.scale(110), new JBLabel(midLabel).getPreferredSize().width);
            JPanel tiers = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(4)));
            tiers.add(tierRow("STRONG model:", strongField, labelWidth));
            tiers.add(tierRow(midLabel, midField, labelWidth));
            tiers.add(tierRow("LOW model:", lowField, labelWidth));
            builder.addComponent(tiers, JBUI.scale(4));
        }
        return builder.getPanel();
    }

    private static JPanel tierRow(String label, ModelField field, int labelWidth) {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        JComponent name = new JBLabel(label);
        name.setPreferredSize(new Dimension(labelWidth, name.getPreferredSize().height));
        panel.add(name, BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected void doOKAction() {
        acceptedModel = modelField.selected();
        acceptedTiers = tiersApply
                ? Optional.of(new ModelTiers(strongField.selected(), midField.selected(), lowField.selected()))
                : Optional.empty();
        super.doOKAction();
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
