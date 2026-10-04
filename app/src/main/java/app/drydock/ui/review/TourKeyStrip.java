package app.drydock.ui.review;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

import java.util.List;

/**
 * The tour's key-hints strip: the keys that work right now, under the code
 * and above the verdict bar, with a way to put it away.
 *
 * <p>Shown, it lists {@link TourKeyHints}; hidden, only a small "Shortcuts h"
 * button remains, which is also how it comes back. Both are real {@link
 * Button}s, and {@code h} does the same thing from the keyboard (bound in
 * {@link TourController#handleShortcut}). It does not own the preference:
 * it reports the toggle to {@code onToggle} and shows whatever {@link
 * #setHidden} says, so the persisted value stays the single source.</p>
 *
 * <p>The hints are clipped, never wrapped, when the window is narrow: the
 * list is ordered most useful first, and the hide/show button sits outside
 * the clipped part so it can never be pushed off.</p>
 */
final class TourKeyStrip extends HBox {

    private final HBox hintRow = new HBox(16);
    private final Button hideButton = new Button();
    private final Runnable onToggle;
    private List<TourKeyHints.Hint> hints = List.of();
    private boolean hidden;
    private boolean tourShown;

    TourKeyStrip(Runnable onToggle) {
        this.onToggle = onToggle;
        getStyleClass().add("tour-key-strip");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);
        hintRow.setAlignment(Pos.CENTER_LEFT);
        hintRow.setMinWidth(0);
        HBox.setHgrow(hintRow, Priority.ALWAYS);
        // Clip to the row's own box, so an overlong list is cut, not spilled over the button.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hintRow.widthProperty());
        clip.heightProperty().bind(hintRow.heightProperty());
        hintRow.setClip(clip);
        hideButton.getStyleClass().add("tour-key-strip-toggle");
        hideButton.setOnAction(event -> this.onToggle.run());
        rebuild();
    }

    /** The hints to list; an empty list (no tour) leaves the strip out of the layout. */
    void setHints(List<TourKeyHints.Hint> newHints) {
        if (newHints.equals(hints)) {
            return;
        }
        hints = List.copyOf(newHints);
        rebuild();
    }

    void setHidden(boolean newHidden) {
        if (newHidden == hidden) {
            return;
        }
        hidden = newHidden;
        rebuild();
    }

    boolean hintsHidden() {
        return hidden;
    }

    /** Whether the board is in tour mode; the strip means nothing over the hunk diff. */
    void setTourShown(boolean shown) {
        tourShown = shown;
        applyPresence();
    }

    private void rebuild() {
        hintRow.getChildren().clear();
        getStyleClass().remove("hidden-hints");
        if (hidden) {
            getStyleClass().add("hidden-hints");
            hideButton.setText("Shortcuts");
            hideButton.setTooltip(new Tooltip("Show the key hints (h)"));
            // Only the button remains, at the right edge.
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            getChildren().setAll(spacer, hideButton);
        } else {
            for (TourKeyHints.Hint hint : hints) {
                hintRow.getChildren().add(item(hint));
            }
            hintRow.getChildren().add(item(TourKeyHints.ALL));
            hideButton.setText("Hide");
            hideButton.setTooltip(new Tooltip("Hide the key hints (h); the Shortcuts button brings them back"));
            getChildren().setAll(hintRow, hideButton);
        }
        hideButton.setGraphic(keycap(TourKeyHints.HIDE.key()));
        applyPresence();
    }

    private void applyPresence() {
        boolean present = tourShown && !hints.isEmpty();
        setVisible(present);
        setManaged(present);
    }

    private static HBox item(TourKeyHints.Hint hint) {
        Label label = new Label(hint.label());
        label.getStyleClass().add("tour-key-strip-label");
        HBox box = new HBox(6, keycap(hint.key()), label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Label keycap(String key) {
        Label cap = new Label(key);
        cap.getStyleClass().add("keycap");
        return cap;
    }
}
