package app.drydock.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The one collapse/expand control every collapsible panel wears: a chevron
 * on the panel's outer edge, its title, and its keyboard hint, all inside a
 * single full-width button.
 *
 * <p>It exists because the app had three idioms at once -- the sessions
 * sidebar collapsed from a glyph in the window title bar, the Review rails
 * from a header of their own, the findings margin from a small button on the
 * wrong side -- and a first-time reader had no way to learn the rule from any
 * one of them. One shape, one place, in every panel.</p>
 *
 * <p>The chevron always points the way the panel would move: outward to
 * collapse, back inward to expand. That is why {@link #left} and
 * {@link #right} are separate factories rather than one with a flag -- a
 * left-hand rail and a right-hand margin genuinely fold in opposite
 * directions, and the glyph has to say so.</p>
 */
public final class PanelHeader {

    private final Button button = new Button();
    private final Label chevron = new Label();
    private final Label title = new Label();
    private final Label hint = new Label();
    private final boolean onLeft;

    private boolean collapsed;

    private PanelHeader(String titleText, String hintText, String tooltip, boolean onLeft,
                        Runnable onToggle) {
        this.onLeft = onLeft;
        chevron.getStyleClass().add("panel-header-chevron");
        title.getStyleClass().add("panel-header-title");
        title.setText(titleText);
        hint.getStyleClass().add("panel-header-hint");
        hint.setText(hintText);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = onLeft
                ? new HBox(6, chevron, title, spacer, hint)
                : new HBox(6, title, spacer, hint, chevron);
        row.setAlignment(Pos.CENTER_LEFT);

        button.setGraphic(row);
        button.getStyleClass().add("panel-header");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> onToggle.run());
        showCollapsed(false);
    }

    /** A header for a panel that folds to the left (the sidebar, the Review rails). */
    public static PanelHeader left(String title, String hint, String tooltip, Runnable onToggle) {
        return new PanelHeader(title, hint, tooltip, true, onToggle);
    }

    /** A header for a panel that folds to the right (the findings margin). */
    public static PanelHeader right(String title, String hint, String tooltip, Runnable onToggle) {
        return new PanelHeader(title, hint, tooltip, false, onToggle);
    }

    /** The node to put at the top of the panel. */
    public Region node() {
        return button;
    }

    /** Swaps the title text -- for a panel that renders more than one mode under one header. */
    public void setTitle(String text) {
        title.setText(text);
    }

    public void setTitleVisible(boolean visible) {
        title.setVisible(visible);
        title.setManaged(visible);
    }

    public void setHint(String text) {
        hint.setText(text);
    }

    public void setHintVisible(boolean visible) {
        hint.setVisible(visible);
        hint.setManaged(visible);
    }

    /** Points the chevron the way the next click would move the panel. */
    public void showCollapsed(boolean nowCollapsed) {
        collapsed = nowCollapsed;
        chevron.setText(onLeft == collapsed ? "›" : "‹");
    }
}
