package app.drydock.ui;

import app.drydock.handoff.HandoffStaleness;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Tells the human how far a session's work has moved since its handoff brief
 * was written, and gives them something to do about it.
 *
 * <p>A warning with no verb is just anxiety, so the banner carries three:
 * <em>Refresh</em> asks the session's own agent to rewrite the brief,
 * <em>Edit</em> lets the human write it themselves, and <em>Fork</em> proceeds
 * anyway. Only <em>Refresh</em> can ever be disabled -- it is the one that
 * needs a live agent, and a Refresh that silently does nothing against a dead
 * session is worse than no button at all, because the failure is invisible at
 * exactly the moment the human is deciding whether to trust the brief.</p>
 *
 * <p>Deliberately knows nothing about {@code AgentRegistry}: the fork control
 * is an empty {@link MenuButton} whose items the workspace fills in, so this
 * class stays testable without a repository or a provider probe.</p>
 */
public final class HandoffBanner extends HBox {

    private final Label message = new Label();
    private final Button refresh = new Button("Refresh");
    private final Button edit = new Button("Edit");
    private final MenuButton fork = new MenuButton("Fork to…");

    public HandoffBanner() {
        getStyleClass().add("handoff-banner");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);
        setPadding(new Insets(6, 10, 6, 10));

        message.getStyleClass().add("handoff-banner-message");
        message.setWrapText(true);
        HBox.setHgrow(message, Priority.ALWAYS);
        message.setMaxWidth(Double.MAX_VALUE);

        refresh.getStyleClass().add("handoff-banner-button");
        edit.getStyleClass().add("handoff-banner-button");
        fork.getStyleClass().add("handoff-banner-button");

        getChildren().addAll(message, spacer(), refresh, edit, fork);

        // Managed follows visible so a current brief costs no vertical space.
        managedProperty().bind(visibleProperty());
        setVisible(false);
    }

    /**
     * @param sessionRunning whether the session's agent is alive to be asked;
     *                       when false, <em>Refresh</em> is disabled and says why
     */
    public void update(HandoffStaleness staleness, boolean sessionRunning) {
        message.setText(staleness.describe());
        setVisible(staleness.shouldWarn());

        refresh.setDisable(!sessionRunning);
        refresh.setTooltip(new Tooltip(sessionRunning
                ? "Ask this session's agent to rewrite its handoff brief now."
                : "This session is not running, so its agent cannot be asked for a brief. "
                        + "Edit the brief yourself, or fork anyway."));

        // Edit and Fork stay enabled whatever the session is doing: they are
        // exactly what the human needs when the agent is the thing that died.
        edit.setDisable(false);
        fork.setDisable(false);
    }

    public Button refreshButton() {
        return refresh;
    }

    public Button editButton() {
        return edit;
    }

    public MenuButton forkButton() {
        return fork;
    }

    /** For tests and for the workspace: what the banner is currently telling the human. */
    public String messageText() {
        return message.getText();
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        return spacer;
    }
}
