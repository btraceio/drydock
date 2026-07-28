package app.drydock.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The keyboard-shortcuts modal (design handoff section 8): a centered
 * 440px panel listing every shortcut as a label &harr; keycap row, grouped
 * into sections. Lists only shortcuts the app actually binds (see {@code
 * DrydockApplication.installGlobalShortcuts}, the terminal-side intercepts
 * in {@code OpenSessionTab.onKeyEvent}, and {@code
 * ReviewDestinationView.onKeyPressed}) -- the design prototype's
 * resume-picker shortcuts are parked with the picker itself, and the
 * Review keys that belong to features not yet built are added with those
 * features, never ahead of them.
 */
final class ShortcutsOverlay {

    private record Section(String title, String[][] shortcuts) { }

    private static final Section[] SECTIONS = {
            new Section("", new String[][] {
                    {"New session", "⌘N"},
                    {"Rename session", "⌘R"},
                    {"Claude view", "⌘1"},
                    {"Terminal view", "⌘2"},
                    {"Explorer view", "⌘3"},
                    {"Review", "⌘4"},
                    {"Previous / next session tab", "⌘[ / ⌘]"},
                    {"Previous / next live session", "⌘↑ / ⌘↓"},
                    {"Toggle sidebar", "⌘0"},
                    {"Filter repositories", "⌘F"},
                    {"Toggle theme", "⌘⇧L"},
                    {"Settings", "⌘,"},
                    {"Cancel / close", "Esc"},
            }),
            new Section("IN REVIEW", new String[][] {
                    {"Previous / next review item", "j / k"},
                    {"Collapse the queue", "q"},
                    {"Focus mode — collapse every rail", "f"},
                    {"Open the bound session", "o"},
                    {"Cycle density: cozy · compact · dense", "d"},
                    {"Show or hide unchanged lines", "c"},
                    {"Previous / next intent", "[ / ]"},
                    {"Next unsettled intent", "n"},
                    {"Approve the current intent", "a"},
                    {"Request changes", "r"},
                    {"Undo this intent's verdict", "u"},
                    {"Submit the review", "⏎"},
                    {"Collapse the findings margin", "m"},
                    {"Findings from the whole review", "⇧F"},
            }),
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
        for (Section section : SECTIONS) {
            if (!section.title().isEmpty()) {
                Label sectionLabel = new Label(section.title());
                sectionLabel.getStyleClass().add("shortcut-section-label");
                rows.getChildren().add(sectionLabel);
            }
            for (String[] shortcut : section.shortcuts()) {
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
        }

        VBox modal = new VBox(14, header, rows);
        modal.getStyleClass().add("modal");
        modal.setMaxWidth(440);
        modal.setMaxHeight(Region.USE_PREF_SIZE);
        return modal;
    }
}
