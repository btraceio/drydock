package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * The field wiring every start dialog shares: the task field's labelled
 * group, and the per-agent hiding rules. A TERMINAL session has no agent to
 * hand a task to -- typed text would be executed by the shell -- so its task
 * field and model picker disappear entirely, and eval mode is only
 * selectable where the harness supports it.
 */
final class StartDialogSupport {

    private StartDialogSupport() {
    }

    /** The task field's labelled group. */
    static VBox taskGroup(String labelText, TextArea taskField) {
        Label taskLabel = new Label(labelText);
        taskLabel.getStyleClass().add("worktree-field-label");
        return new VBox(4, taskLabel, taskField);
    }

    static void applyAgent(AgentKind kind, AgentRegistry registry, ModelSelector modelSelector,
                           VBox taskGroup, CheckBox evalMode) {
        boolean terminal = kind == AgentKind.TERMINAL;
        modelSelector.setManaged(!terminal);
        modelSelector.setVisible(!terminal);
        taskGroup.setManaged(!terminal);
        taskGroup.setVisible(!terminal);
        if (!terminal) {
            modelSelector.setAgent(kind);
        }
        boolean evalOk = registry.evalAvailable(kind);
        evalMode.setDisable(!evalOk);
        if (!evalOk) {
            evalMode.setSelected(false);
            evalMode.setTooltip(new Tooltip("Eval routing is not available for " + kind.persistedName()
                    + " (requires the omlx_proxy for Claude, or is unsupported for this agent)."));
        } else {
            evalMode.setTooltip(new Tooltip("Route this session's model traffic to the eval account "
                    + "(x-target-account: eval) so plugin/extension testing is not charged to ordinary usage."));
        }
    }
}
