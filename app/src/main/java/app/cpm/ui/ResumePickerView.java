package app.cpm.ui;

import app.cpm.app.RepositoryManager;
import app.cpm.claude.ConversationCatalog;
import app.cpm.claude.ConversationCatalog.Conversation;
import app.cpm.domain.Repository;
import app.cpm.git.GitBranchState;
import app.cpm.git.GitStatusService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The resume picker (design handoff section 6): shown in the main pane
 * when no session tab is active (or after Back). Lists real resumable
 * conversations from claude's transcript store (via {@link
 * ConversationCatalog}) across all registered repositories, newest first;
 * search filters live; &uarr;/&darr; navigate, Enter (or click) resumes;
 * a keycap footer documents the shortcuts.
 */
final class ResumePickerView extends BorderPane {

    private static final Logger LOG = System.getLogger(ResumePickerView.class.getName());

    private static final String[][] KEYCAPS = {
            {"⌘A", "all projects"},
            {"⌘B", "all branches"},
            {"⌘R", "rename"},
            {"Space", "preview"},
            {"↑↓", "navigate"},
            {"↵", "resume"},
    };

    /** One row: a conversation plus the repository (and its branch, once known) it belongs to. */
    private record PickerItem(Repository repository, Conversation conversation, String branch) {
    }

    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final ConversationCatalog catalog;
    private final BiConsumer<Repository, Conversation> onResume;

    private final Label subLabel = new Label();
    private final TextField searchField = new TextField();
    private final ListView<PickerItem> listView = new ListView<>();
    private final List<PickerItem> allItems = new ArrayList<>();

    ResumePickerView(RepositoryManager repositoryManager, GitStatusService gitStatusService,
                     ConversationCatalog catalog, BiConsumer<Repository, Conversation> onResume) {
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.catalog = catalog;
        this.onResume = onResume;

        getStyleClass().add("main-pane");

        // -- Header ---------------------------------------------------------
        Label iconTile = new Label("↻");
        iconTile.getStyleClass().add("picker-icon-tile");
        Label title = new Label("Resume session");
        title.getStyleClass().add("picker-title");
        subLabel.getStyleClass().add("picker-sub");
        VBox titles = new VBox(1, title, subLabel);
        HBox header = new HBox(10, iconTile, titles);
        header.setAlignment(Pos.CENTER_LEFT);

        // -- Search ---------------------------------------------------------
        searchField.getStyleClass().add("picker-search");
        searchField.setPromptText("Search conversations… (type to filter)");
        searchField.textProperty().addListener((obs, oldText, newText) -> applyFilter());
        searchField.setOnKeyPressed(event -> {
            int index = listView.getSelectionModel().getSelectedIndex();
            switch (event.getCode()) {
                case DOWN -> {
                    listView.getSelectionModel().select(Math.min(index + 1, listView.getItems().size() - 1));
                    event.consume();
                }
                case UP -> {
                    listView.getSelectionModel().select(Math.max(index - 1, 0));
                    event.consume();
                }
                case ENTER -> {
                    resumeSelected();
                    event.consume();
                }
                default -> { }
            }
        });

        // -- List -----------------------------------------------------------
        listView.getStyleClass().add("conversation-list");
        listView.setCellFactory(view -> new ConversationCell());
        listView.setPlaceholder(buildEmptyState());
        listView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                resumeSelected();
                event.consume();
            }
        });
        VBox.setVgrow(listView, Priority.ALWAYS);

        VBox center = new VBox(14, header, searchField, listView);
        center.getStyleClass().add("resume-picker");

        // -- Keycap footer --------------------------------------------------
        FlowPane footer = new FlowPane();
        footer.getStyleClass().add("keycap-footer");
        for (String[] keycap : KEYCAPS) {
            Label key = new Label(keycap[0]);
            key.getStyleClass().add("keycap");
            Label label = new Label(keycap[1]);
            label.getStyleClass().add("keycap-label");
            HBox chip = new HBox(6, key, label);
            chip.setAlignment(Pos.CENTER_LEFT);
            footer.getChildren().add(chip);
        }

        setCenter(center);
        setBottom(footer);
    }

    /** Focuses the search field; called whenever the picker becomes visible. */
    void focusSearch() {
        searchField.requestFocus();
    }

    /**
     * Re-scans every registered repository's claude transcript store on a
     * background (virtual) thread and repopulates the list, newest first.
     */
    void refresh() {
        List<Repository> repositories = repositoryManager.repositories();
        Thread.ofVirtual().name("resume-picker-scan").start(() -> {
            List<PickerItem> scanned = new ArrayList<>();
            for (Repository repository : repositories) {
                String branch = resolveBranch(repository);
                for (Conversation conversation : catalog.listConversations(repository.root())) {
                    scanned.add(new PickerItem(repository, conversation, branch));
                }
            }
            scanned.sort((a, b) -> b.conversation().lastModified().compareTo(a.conversation().lastModified()));
            Platform.runLater(() -> {
                allItems.clear();
                allItems.addAll(scanned);
                subLabel.setText("all projects · " + scanned.size()
                        + (scanned.size() == 1 ? " conversation" : " conversations"));
                applyFilter();
            });
        });
    }

    private String resolveBranch(Repository repository) {
        try {
            var status = gitStatusService.getStatus(repository.root()).join();
            if (status.branch() instanceof GitBranchState.OnBranch onBranch) {
                return onBranch.name();
            }
            if (status.branch() instanceof GitBranchState.Detached detached) {
                String oid = detached.commitOid();
                return "detached@" + (oid.length() > 7 ? oid.substring(0, 7) : oid);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Branch lookup failed for " + repository.root(), e);
        }
        return "?";
    }

    private void applyFilter() {
        String query = searchField.getText() == null ? ""
                : searchField.getText().strip().toLowerCase(Locale.ROOT);
        List<PickerItem> filtered = query.isEmpty() ? List.copyOf(allItems)
                : allItems.stream()
                        .filter(item -> item.conversation().title().toLowerCase(Locale.ROOT).contains(query)
                                || item.repository().displayName().toLowerCase(Locale.ROOT).contains(query))
                        .toList();
        listView.getItems().setAll(filtered);
        if (!filtered.isEmpty()) {
            listView.getSelectionModel().select(0);
        }
    }

    private void resumeSelected() {
        PickerItem selected = listView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            onResume.accept(selected.repository(), selected.conversation());
        }
    }

    private VBox buildEmptyState() {
        Label glyph = new Label("◍");
        glyph.getStyleClass().add("picker-empty-glyph");
        Label title = new Label("No conversations found");
        title.getStyleClass().add("picker-empty-title");
        Label hint = new Label("Conversations started by this app (or claude itself) in a registered "
                + "repository appear here. ⌘A shows all projects.");
        hint.getStyleClass().add("picker-empty-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(360);
        hint.setAlignment(Pos.CENTER);
        VBox empty = new VBox(8, glyph, title, hint);
        empty.setAlignment(Pos.CENTER);
        return empty;
    }

    private static String relativeTime(Instant instant) {
        long seconds = Math.max(0, java.time.Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) {
            return "now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86400) + "d ago";
    }

    private final class ConversationCell extends ListCell<PickerItem> {

        ConversationCell() {
            setOnMouseEntered(event -> {
                if (getItem() != null) {
                    getListView().getSelectionModel().select(getIndex());
                }
            });
            setOnMouseClicked(event -> {
                if (getItem() != null) {
                    onResume.accept(getItem().repository(), getItem().conversation());
                }
            });
        }

        @Override
        protected void updateItem(PickerItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            setGraphic(buildRow(item, isSelected()));
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            if (getItem() != null) {
                setGraphic(buildRow(getItem(), selected));
            }
        }

        private HBox buildRow(PickerItem item, boolean selected) {
            Conversation conversation = item.conversation();

            Label initial = new Label(conversation.title().isEmpty() ? "?"
                    : conversation.title().substring(0, 1).toUpperCase(Locale.ROOT));
            initial.getStyleClass().add("conv-initial");

            Label title = new Label(conversation.title());
            title.getStyleClass().add("conv-title");
            Label meta = new Label("⎇ " + item.branch() + " · " + item.repository().displayName()
                    + " · " + conversation.messageCount() + " messages · "
                    + relativeTime(conversation.lastModified()));
            meta.getStyleClass().add("conv-meta");
            VBox text = new VBox(2, title, meta);
            HBox.setHgrow(text, Priority.ALWAYS);

            HBox row = new HBox(12, initial, text);
            if (selected) {
                Label badge = new Label("↵ RESUME");
                badge.getStyleClass().add("resume-badge");
                row.getChildren().add(badge);
            }
            row.getStyleClass().add("conversation-row");
            if (selected) {
                row.getStyleClass().add("selected");
            }
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }
    }
}
