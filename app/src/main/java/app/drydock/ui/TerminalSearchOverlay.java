package app.drydock.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/**
 * The terminal search overlay: a top-anchored bar shown while Cmd-F search is
 * active. Contains a text field for the needle, a match-count label, and
 * prev/next/close buttons. Text changes and navigation are forwarded to the
 * terminal core via a binding-action string consumer (wired to
 * {@code TerminalBridge.performBindingAction}); the terminal core owns the
 * search thread and reports match counts back through the surface's
 * {@link app.drydock.terminal.api.TerminalSurface.SearchListener}.
 *
 * <p>Styled to match the app's chip/pill vocabulary (see {@code .terminal-search-overlay}
 * in {@code app.css}), not the old oversized status pill.</p>
 */
final class TerminalSearchOverlay extends HBox {

    private final TextField needleField = new TextField();
    private final Label matchCount = new Label();
    private final Button prevButton = new Button("↑");
    private final Button nextButton = new Button("↓");
    private final Button closeButton = new Button("✕");

    private Consumer<String> bindingActionHandler = s -> { };

    TerminalSearchOverlay() {
        getStyleClass().add("terminal-search-overlay");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        setPadding(new Insets(6, 10, 6, 10));

        needleField.getStyleClass().add("terminal-search-field");
        needleField.setPrefWidth(200);
        needleField.setPromptText("Search");
        HBox.setHgrow(needleField, javafx.scene.layout.Priority.ALWAYS);

        matchCount.getStyleClass().add("terminal-search-count");
        matchCount.setText("0");
        matchCount.setMinWidth(60);
        matchCount.setAlignment(Pos.CENTER);

        prevButton.getStyleClass().add("terminal-search-nav");
        prevButton.setFocusTraversable(false);
        prevButton.setTooltip(new javafx.scene.control.Tooltip("Previous match (⇧⏎)"));
        prevButton.setOnAction(e -> navigate(false));

        nextButton.getStyleClass().add("terminal-search-nav");
        nextButton.setFocusTraversable(false);
        nextButton.setTooltip(new javafx.scene.control.Tooltip("Next match (⏎)"));
        nextButton.setOnAction(e -> navigate(true));

        closeButton.getStyleClass().add("terminal-search-close");
        closeButton.setFocusTraversable(false);
        closeButton.setTooltip(new javafx.scene.control.Tooltip("Close search (Esc)"));
        closeButton.setOnAction(e -> close());

        getChildren().addAll(needleField, matchCount, prevButton, nextButton, closeButton);

        // Debounce text changes so the search thread is not spammed on every
        // keystroke — 150ms matches the sidebar's keystroke-debounce rule.
        javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
        debounce.setOnFinished(e -> {
            String needle = needleField.getText();
            if (needle.isEmpty()) {
                // An empty needle cancels the search but does NOT hide the
                // overlay (the user might be clearing the field to type a new
                // needle). The terminal core stops highlighting but the bar
                // stays.
                bindingActionHandler.accept("search:");
            } else {
                bindingActionHandler.accept("search:" + needle);
            }
        });
        needleField.textProperty().addListener((obs, oldV, newV) -> debounce.playFromStart());

        // Enter = next match, Shift+Enter = previous match, Esc = close.
        needleField.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                navigate(!e.isShiftDown());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            }
        });

        // Managed follows visible so a hidden overlay costs no vertical space.
        // Bind after construction so the explicit setManaged(false) in the
        // constructor (which runs before this line) does not clash with the
        // bound value.
        managedProperty().bind(visibleProperty());
    }

    /** Sets the consumer that receives binding-action strings (e.g. "search:foo"). */
    void setBindingActionHandler(Consumer<String> handler) {
        this.bindingActionHandler = handler != null ? handler : s -> { };
    }

    /** Shows the overlay and focuses the text field. */
    void show() {
        setVisible(true);
        needleField.clear();
        needleField.requestFocus();
    }

    /** Hides the overlay. Called from the core's END_SEARCH notification. */
    void hide() {
        setVisible(false);
    }

    /**
     * User-initiated close: sends {@code end_search} to the terminal core.
     * The core will then notify END_SEARCH, which calls {@link #hide()}.
     */
    private void close() {
        bindingActionHandler.accept("end_search");
    }

    private void navigate(boolean forward) {
        bindingActionHandler.accept("navigate_search:" + (forward ? "next" : "previous"));
    }

    /** Updates the match count display; {@code -1} means "search in progress / unknown". */
    void setMatchCount(long total) {
        matchCount.getProperties().put("drydock.search.total", total);
        if (total < 0) {
            matchCount.setText("…");
        } else {
            matchCount.setText(total + " match" + (total == 1 ? "" : "es"));
        }
    }

    /** Updates the current match index display; {@code -1} means none. */
    void setSelectedIndex(long selected) {
        Object totalObj = matchCount.getProperties().get("drydock.search.total");
        long total = totalObj instanceof Long l ? l : -1;
        if (selected < 0 || total < 0) {
            return;
        }
        matchCount.setText((selected + 1) + " of " + total);
    }
}
