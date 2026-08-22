package app.drydock.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.List;

/**
 * The keyboard-shortcuts modal (design handoff section 8): a centered
 * 440px panel listing every shortcut as a label &harr; keycap row, grouped
 * into sections. Lists only shortcuts the app actually binds (see {@code
 * DrydockApplication.installGlobalShortcuts}, the terminal-side intercepts
 * in {@code OpenSessionTab.onKeyEvent}, and {@code
 * SessionReviewView.handleShortcut}) -- the design prototype's
 * resume-picker shortcuts are parked with the picker itself, and the
 * Review keys that belong to features not yet built are added with those
 * features, never ahead of them.
 */
public final class ShortcutsOverlay {

    private record Section(String title, String[][] shortcuts) { }

    private static final Section[] SECTIONS = {
            new Section("", new String[][] {
                    {"New session", "⌘N"},
                    {"Rename session", "⌘R"},
                    {"Agent view", "⌘1"},
                    {"Terminal view", "⌘2"},
                    {"New terminal", "⌘T"},
                    {"Previous / next terminal", "⌥⌘[ / ⌥⌘]"},
                    {"Explorer view", "⌘3"},
                    {"Review this session's changes", "⌘4"},
                    {"Previous / next session tab", "⌘[ / ⌘]"},
                    {"  (in the Explorer: back / forward along the trail)", ""},
                    {"Previous / next live session", "⌘↑ / ⌘↓"},
                    {"Toggle sidebar", "⌘0"},
                    {"Session search", "⌘K"},
                    {"Toggle theme", "⌘⇧L"},
                    {"Settings", "⌘,"},
                    {"Cancel / close", "Esc"},
            }),
            new Section("IN REVIEW", new String[][] {
                    {"Focus mode — collapse every rail", "f"},
                    {"Cycle density: cozy · compact · dense", "d"},
                    {"Show or hide unchanged lines", "c"},
                    {"Reading path / intents", "p"},
                    {"Previous / next intent (or path row)", "[ / ]"},
                    {"Next unsettled intent (or hunk)", "n"},
                    {"Approve (section, or next unread hunk in the diff)", "a"},
                    {"Request changes (section, or next unread hunk in the diff)", "r"},
                    {"Undo (section, or next unread hunk in the diff)", "u"},
                    {"Approve every hunk in this file", "⇧A"},
                    {"Request changes on this file", "⇧R"},
                    {"Submit the review", "⏎"},
                    {"Collapse the intents", "i"},
                    {"Collapse the findings margin", "m"},
                    {"Findings from the whole review", "⇧F"},
                    {"MCP activity log", "\\"},
            }),
            new Section("IN THE NEW-WORKTREE MODAL", new String[][] {
                    {"Switch new / existing branch", "⌘E"},
            }),
            new Section("IN THE EXPLORER", new String[][] {
                    {"Focus the file search", "/"},
                    {"Scope: this change / the whole worktree", "d"},
                    {"Sort: churn · findings · a-z", "s"},
                    {"Skim / full text for this file", "z"},
                    {"Peek at a symbol in place", "click an underlined symbol"},
                    {"In a peek: open for real / usages / ask the agent", "⏎ / u / a"},
                    {"Back / forward along the trail", "⌘[ / ⌘]"},
                    {"Close one peek", "Esc"},
            }),
    };

    private ShortcutsOverlay() {
    }

    /** Test-only: the keycap column of one section, for the advertised↔bound parity check. */
    static List<String> diagKeysFor(String sectionTitle) {
        for (Section section : SECTIONS) {
            if (section.title().equals(sectionTitle)) {
                return Arrays.stream(section.shortcuts()).map(row -> row[1]).toList();
            }
        }
        return List.of();
    }

    /**
     * The keys this overlay advertises for Review, so a test in {@code
     * app.drydock.ui.review} -- a different package, so it cannot reach
     * {@link #diagKeysFor}'s package-private access -- can hold the two in
     * step: anything advertised here must be bound in {@code
     * SessionReviewView.handleShortcut}, and vice versa.
     */
    public static List<String> reviewShortcutKeys() {
        return diagKeysFor("IN REVIEW");
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
