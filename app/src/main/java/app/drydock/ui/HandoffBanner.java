package app.drydock.ui;

import app.drydock.handoff.HandoffStaleness;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;

/**
 * Tells the human how far a session's work has moved since its handoff brief
 * was written, and gives them a corrective verb for it.
 *
 * <p>A warning with no verb is just anxiety, so the banner carries two:
 * <em>Refresh</em> asks the session's own agent to rewrite the brief and
 * <em>Edit</em> lets the human write it themselves. Only <em>Refresh</em> can
 * ever be disabled -- it is the one that needs a live agent, and a Refresh
 * that silently does nothing against a dead session is worse than no button
 * at all, because the failure is invisible at exactly the moment the human is
 * deciding whether to trust the brief.</p>
 *
 * <p>Forking is not a corrective verb -- it proceeds whether the brief is
 * stale or current -- so it lives in the session header as a persistent
 * control, not here. This banner warns; it does not host the primary action,
 * because hosting it meant the action vanished the moment the warning cleared
 * (see the {@code Fork to…} control on {@code OpenSessionTab}).</p>
 */
public final class HandoffBanner extends HBox {

    private final Label message = new Label();
    private final Button refresh = new Button("Refresh");
    private final Button edit = new Button("Edit");

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

        // The buttons must never be the thing that gives. Found by screenshot
        // at a narrow tab width: HBox shrinks children by default, so at ~435px
        // of content these collapsed to "R.." and "..." -- and "..." as a
        // label for Edit tells the human nothing at all. The message is the
        // only element that should absorb a squeeze, which it does by
        // wrapping, so pin the buttons to their preferred width.
        for (javafx.scene.control.Control control : List.of(refresh, edit)) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }

        getChildren().addAll(message, spacer(), refresh, edit);

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

        // Edit stays enabled whatever the session is doing: it is exactly
        // what the human needs when the agent is the thing that died.
        edit.setDisable(false);
    }

    public Button refreshButton() {
        return refresh;
    }

    public Button editButton() {
        return edit;
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
