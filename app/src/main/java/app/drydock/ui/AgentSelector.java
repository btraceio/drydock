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

    AgentSelector(AgentRegistry registry, AgentKind preselected, Consumer<AgentKind> onSelect) {
        this.selected = preselected;
        setSpacing(6);
        Label label = new Label("Agent");
        label.getStyleClass().add("worktree-field-label");
        HBox toggles = new HBox(6);
        ToggleGroup group = new ToggleGroup();
        for (Agent agent : registry.agents()) {
            ToggleButton button = new ToggleButton(agent.displayName());
            button.getStyleClass().add("agent-toggle");
            button.setToggleGroup(group);
            button.setUserData(agent.kind());
            button.setDisable(!agent.isAvailable());
            if (!agent.isAvailable()) {
                button.setTooltip(new Tooltip("Not found. Searched: " + agent.describeSearched()));
            }
            if (agent.kind() == preselected && agent.isAvailable()) {
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
