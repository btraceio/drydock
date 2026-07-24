package app.drydock.ui;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A segmented agent picker: one toggle per discovered agent, unavailable ones
 * disabled with a "where we looked" tooltip. Exposes the current selection.
 */
final class AgentSelector extends VBox {

    private AgentKind selected;

    /** Pure selection logic, unit-tested without a JavaFX thread. */
    static Optional<AgentKind> initialSelection(AgentRegistry registry, Optional<AgentKind> repoLastUsed) {
        return registry.resolveDefault(repoLastUsed);
    }

    /** As {@link #initialSelection(AgentRegistry, Optional)}, gated to remote-capable agents. */
    static Optional<AgentKind> initialSelection(AgentRegistry registry, Optional<AgentKind> repoLastUsed,
                                                 boolean requireRemoteCapability) {
        return registry.resolveDefault(repoLastUsed, requireRemoteCapability);
    }

    AgentSelector(AgentRegistry registry, AgentKind preselected, Consumer<AgentKind> onSelect) {
        this(registry, preselected, false, onSelect);
    }

    /**
     * @param requireRemoteCapability when true (target repository is remote),
     *                                 agents that don't report
     *                                 {@link AgentRegistry#supportsRemote} are
     *                                 disabled too, alongside unavailable
     *                                 ones. {@code registry} already has this
     *                                 cached (see {@link AgentRegistry}), so
     *                                 no probing happens here -- safe to call
     *                                 on the FX thread.
     */
    AgentSelector(AgentRegistry registry, AgentKind preselected, boolean requireRemoteCapability,
                  Consumer<AgentKind> onSelect) {
        this.selected = preselected;
        setSpacing(6);
        Label label = new Label("Agent");
        label.getStyleClass().add("worktree-field-label");
        HBox toggles = new HBox(6);
        ToggleGroup group = new ToggleGroup();
        for (Agent agent : registry.agents()) {
            boolean remoteOk = !requireRemoteCapability || registry.supportsRemote(agent.kind());
            boolean disabled = !agent.isAvailable() || !remoteOk;
            ToggleButton button = new ToggleButton(agent.displayName());
            button.getStyleClass().add("agent-toggle");
            button.setToggleGroup(group);
            button.setUserData(agent.kind());
            button.setDisable(disabled);
            if (!agent.isAvailable()) {
                button.setTooltip(new Tooltip("Not found. Searched: " + agent.describeSearched()));
            } else if (!remoteOk) {
                button.setTooltip(new Tooltip("Doesn't support remote sessions"));
            }
            if (agent.kind() == preselected && !disabled) {
                button.setSelected(true);
            }
            button.setOnAction(e -> {
                selected = (AgentKind) button.getUserData();
                onSelect.accept(selected);
            });
            toggles.getChildren().add(button);
        }
        getChildren().addAll(label, toggles);
    }

    AgentKind selected() {
        return selected;
    }
}
