package app.drydock.ui;

import app.drydock.domain.UiTheme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * Custom 44px title bar for the undecorated stage (design handoff README
 * section 1): traffic-light buttons on the left, an absolutely centered
 * non-interactive title, and two 30px icon buttons (shortcuts help, theme
 * toggle) on the right. The whole bar is a drag region; double-click
 * toggles zoom, mirroring the native macOS title bar it replaces.
 *
 * <p>The close light fires {@link WindowEvent#WINDOW_CLOSE_REQUEST} rather
 * than {@code stage.close()} so {@code DrydockApplication}'s running-sessions
 * confirmation flow still intercepts it.</p>
 */
final class TitleBar extends StackPane {

    private final Button themeButton = iconButton("☾", "Toggle theme (⌘⇧L)");
    private final Button sidebarButton = iconButton("◧", "Hide sidebar (⌘0)");

    private double dragOffsetX;
    private double dragOffsetY;

    TitleBar(Stage stage, String title, Runnable onHelp, Runnable onThemeToggle,
             Runnable onSidebarToggle) {
        getStyleClass().add("title-bar");

        Button close = trafficLight("close", () ->
                stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
        Button minimize = trafficLight("minimize", () -> stage.setIconified(true));
        Button zoom = trafficLight("zoom", () -> stage.setMaximized(!stage.isMaximized()));
        sidebarButton.setOnAction(e -> onSidebarToggle.run());
        HBox lights = new HBox(8, close, minimize, zoom);
        lights.setAlignment(Pos.CENTER_LEFT);
        HBox.setMargin(sidebarButton, new Insets(0, 0, 0, 10));
        lights.getChildren().add(sidebarButton);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("title-bar-title");
        titleLabel.setMouseTransparent(true);

        Button helpButton = iconButton("?", "Keyboard shortcuts (?)");
        helpButton.setOnAction(e -> onHelp.run());
        themeButton.setOnAction(e -> onThemeToggle.run());
        HBox right = new HBox(4, helpButton, themeButton);
        right.setAlignment(Pos.CENTER_RIGHT);

        HBox sides = new HBox(lights, spacer(), right);
        sides.setAlignment(Pos.CENTER);
        getChildren().addAll(sides, titleLabel);
        StackPane.setAlignment(titleLabel, Pos.CENTER);

        // Window drag: anywhere on the bar that is not one of the buttons.
        setOnMousePressed(event -> {
            dragOffsetX = stage.getX() - event.getScreenX();
            dragOffsetY = stage.getY() - event.getScreenY();
        });
        setOnMouseDragged(event -> {
            if (!stage.isMaximized()) {
                stage.setX(event.getScreenX() + dragOffsetX);
                stage.setY(event.getScreenY() + dragOffsetY);
            }
        });
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    /** Updates the theme toggle glyph: shows the theme you would switch TO. */
    void showThemeGlyphFor(UiTheme current) {
        themeButton.setText(current == UiTheme.DARK ? "☀" : "☾");
    }

    /** Reflects the sidebar collapse state in the toggle button's glyph and tooltip. */
    void showSidebarState(boolean collapsed) {
        sidebarButton.setText(collapsed ? "◨" : "◧");
        sidebarButton.getTooltip().setText(collapsed ? "Show sidebar (⌘0)" : "Hide sidebar (⌘0)");
    }

    private static Button trafficLight(String role, Runnable action) {
        Button button = new Button();
        button.getStyleClass().addAll("traffic-light", role);
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    private static Button iconButton(String glyph, String tooltip) {
        Button button = new Button(glyph);
        button.getStyleClass().add("icon-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
