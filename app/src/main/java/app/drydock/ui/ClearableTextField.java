package app.drydock.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * Wraps a {@link TextField} in a {@link StackPane} with a small {@code ×}
 * clear button pinned to its right edge, visible only while the field holds
 * text. One shared component for every search/filter box in the app so the
 * affordance is identical everywhere.
 *
 * <p>The button is non-traversable (Tab skips it) and calls {@code clear()}
 * on the field, which fires the field's own text listener so the existing
 * debounce/rebuild wiring runs unchanged.</p>
 */
public final class ClearableTextField extends StackPane {

    private final TextField field;

    public ClearableTextField(TextField field) {
        this.field = field;
        getStyleClass().add("clearable-text-field");
        // The field fills the StackPane so an HGROW on the wrapper still grows
        // the input, not just the clear button's anchor. StackPane sizes a
        // child to the pane's width when the child's max width is unbounded.
        field.setMaxWidth(Double.MAX_VALUE);

        Button clear = new Button("×");
        clear.getStyleClass().add("clear-text-button");
        clear.setFocusTraversable(false);
        clear.setTooltip(new javafx.scene.control.Tooltip("Clear"));
        clear.setOnAction(e -> field.clear());
        // Visible only while there is something to clear; an always-on × next
        // to an empty field is just noise.
        clear.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> !field.getText().isEmpty(), field.textProperty()));
        clear.managedProperty().bind(clear.visibleProperty());

        StackPane.setAlignment(clear, Pos.CENTER_RIGHT);
        StackPane.setMargin(clear, new Insets(0, 6, 0, 0));

        getChildren().setAll(field, clear);
    }

    public TextField field() {
        return field;
    }
}