package app.drydock.ui.explorer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The trail bar at the viewer's bottom edge (Explorer delta, part 1): one
 * chip per waypoint, browser-style {@code ‹ ›} back/forward, and the pin
 * toggle for the current waypoint.
 *
 * <p>Rendering only -- {@link NavigationTrail} owns the arithmetic. The bar
 * is rebuilt wholesale on every change because a trail is at most {@link
 * NavigationTrail#CAPACITY} chips and diffing that would cost more than it
 * saves.</p>
 */
final class TrailBar extends HBox {

    private final Button back = new Button("‹");
    private final Button forward = new Button("›");
    private final HBox chips = new HBox(5);
    private final Button pin = new Button("📌 pin");
    private final Label empty = new Label("no trail yet — open a file to start one");

    private IntConsumer onGoTo = index -> { };
    private Consumer<Integer> onStep = direction -> { };
    private Runnable onTogglePin = () -> { };

    TrailBar() {
        getStyleClass().add("explorer-trail-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);

        back.getStyleClass().add("trail-step-button");
        back.setTooltip(new Tooltip("Back along the trail (⌘[)"));
        back.setOnAction(e -> onStep.accept(-1));
        forward.getStyleClass().add("trail-step-button");
        forward.setTooltip(new Tooltip("Forward along the trail (⌘])"));
        forward.setOnAction(e -> onStep.accept(1));

        Region divider = new Region();
        divider.getStyleClass().add("trail-divider");

        chips.setAlignment(Pos.CENTER_LEFT);
        chips.getStyleClass().add("trail-chips");
        ScrollPane chipScroll = new ScrollPane(chips);
        chipScroll.setFitToHeight(true);
        chipScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.getStyleClass().add("trail-chip-scroll");
        HBox.setHgrow(chipScroll, Priority.ALWAYS);

        empty.getStyleClass().add("trail-empty");

        pin.getStyleClass().add("trail-pin-button");
        pin.setTooltip(new Tooltip("Pin this waypoint against eviction"));
        pin.setOnAction(e -> onTogglePin.run());

        getChildren().setAll(back, forward, divider, chipScroll, pin);
    }

    void setOnGoTo(IntConsumer handler) {
        this.onGoTo = handler == null ? index -> { } : handler;
    }

    void setOnStep(Consumer<Integer> handler) {
        this.onStep = handler == null ? direction -> { } : handler;
    }

    void setOnTogglePin(Runnable handler) {
        this.onTogglePin = handler == null ? () -> { } : handler;
    }

    /** Repaints from {@code trail}; call after every navigation. */
    void render(NavigationTrail trail) {
        back.setDisable(!trail.canGoBack());
        forward.setDisable(!trail.canGoForward());
        pin.setVisible(!trail.isEmpty());
        pin.setManaged(!trail.isEmpty());
        trail.current().ifPresent(current -> {
            pin.setText(current.pinned() ? "📌 pinned" : "📌 pin");
            pin.pseudoClassStateChanged(PINNED, current.pinned());
        });

        chips.getChildren().clear();
        if (trail.isEmpty()) {
            chips.getChildren().add(empty);
            return;
        }
        var waypoints = trail.waypoints();
        for (int i = 0; i < waypoints.size(); i++) {
            NavigationTrail.Waypoint waypoint = waypoints.get(i);
            int index = i;
            Button chip = new Button((waypoint.pinned() ? "📌 " : "") + waypoint.label());
            chip.getStyleClass().add("trail-chip");
            chip.pseudoClassStateChanged(CURRENT, i == trail.cursor());
            Tooltip tip = new Tooltip(waypoint.file() + " · line " + waypoint.line());
            tip.setShowDelay(Duration.millis(400));
            chip.setTooltip(tip);
            chip.setOnAction(e -> onGoTo.accept(index));
            chips.getChildren().add(chip);
        }
    }

    private static final javafx.css.PseudoClass CURRENT = javafx.css.PseudoClass.getPseudoClass("current");
    private static final javafx.css.PseudoClass PINNED = javafx.css.PseudoClass.getPseudoClass("pinned");
}
