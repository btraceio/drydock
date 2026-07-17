package app.cpm.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The keyboard-shortcuts modal (design handoff section 8): a centered
 * 440px panel listing every documented shortcut as a label &harr; keycap
 * row. Content mirrors the design prototype's {@code allShortcuts} list.
 */
final class ShortcutsOverlay {

    private static final String[][] SHORTCUTS = {
            {"Show all projects", "⌘A"},
            {"Show all branches", "⌘B"},
            {"New session", "⌘N"},
            {"Rename session", "⌘R"},
            {"Filter repositories", "⌘F"},
            {"Preview conversation", "Space"},
            {"Navigate list", "↑ / ↓"},
            {"Resume selected", "↵ Enter"},
            {"Toggle theme", "⌘⇧L"},
            {"Cancel / close", "Esc"},
    };

    private ShortcutsOverlay() {
    }

    static Region create(Runnable onClose) {
        Label title = new Label("Keyboard shortcuts");
        title.getStyleClass().add("modal-title");

        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox rows = new VBox(4);
        for (String[] shortcut : SHORTCUTS) {
            Label label = new Label(shortcut[0]);
            label.getStyleClass().add("shortcut-row-label");
            Label keycap = new Label(shortcut[1]);
            keycap.getStyleClass().add("keycap");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, label, spacer, keycap);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }

        VBox modal = new VBox(14, header, rows);
        modal.getStyleClass().add("modal");
        modal.setMaxWidth(440);
        modal.setMaxHeight(Region.USE_PREF_SIZE);
        return modal;
    }
}
