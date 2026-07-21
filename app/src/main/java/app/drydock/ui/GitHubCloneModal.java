package app.drydock.ui;

import app.drydock.app.RepositoryManager;
import app.drydock.github.GitHubRepo;
import app.drydock.github.GitHubService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Clone-from-GitHub modal (design handoff section 7): a live search
 * over the real GitHub API (or a pasted URL), result rows with Clone
 * buttons; cloning asks for a parent directory, runs {@code git clone},
 * then registers the fresh clone as a repository.
 */
public final class GitHubCloneModal extends VBox {

    private final GitHubService gitHubService;
    private final RepositoryManager repositoryManager;
    private final Runnable onClose;

    private final TextField searchField = new TextField();
    private final VBox results = new VBox(6);
    private final Label statusLine = new Label();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(400));

    /** Serial number of the latest search; stale async responses are dropped. */
    private int searchGeneration;

    public GitHubCloneModal(GitHubService gitHubService, RepositoryManager repositoryManager, Runnable onClose) {
        this.gitHubService = gitHubService;
        this.repositoryManager = repositoryManager;
        this.onClose = onClose;

        getStyleClass().add("modal");
        setMaxWidth(540);
        setMaxHeight(560);

        Label title = new Label("⎇  Clone from GitHub");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        searchField.getStyleClass().add("modal-search");
        searchField.setPromptText("Search repositories or paste a URL…");
        // One handler, registered once; it reads the CURRENT text when the
        // debounce fires (re-registering per keystroke leaked a fresh
        // handler capturing each intermediate text).
        debounce.setOnFinished(e -> runSearch(searchField.getText()));
        searchField.textProperty().addListener((obs, oldText, newText) -> debounce.playFromStart());

        statusLine.getStyleClass().add("gh-meta");

        ScrollPane scroll = new ScrollPane(results);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(340);
        scroll.getStyleClass().add("gh-results-scroll");
        results.setFillWidth(true);
        showEmptyState("Type to search GitHub, or paste a repository URL.");

        getChildren().addAll(header, searchField, statusLine, scroll);
        Platform.runLater(searchField::requestFocus);
    }

    private void runSearch(String query) {
        int generation = ++searchGeneration;
        if (query == null || query.strip().isEmpty()) {
            statusLine.setText("");
            showEmptyState("Type to search GitHub, or paste a repository URL.");
            return;
        }
        statusLine.setText("Searching…");
        gitHubService.search(query).whenComplete((repos, failure) -> Platform.runLater(() -> {
            if (generation != searchGeneration) {
                return; // a newer search superseded this one
            }
            if (failure != null) {
                statusLine.setText(String.valueOf(UiErrors.unwrap(failure).getMessage()));
                showEmptyState("Search failed.");
                return;
            }
            statusLine.setText(repos.size() + (repos.size() == 1 ? " result" : " results"));
            if (repos.isEmpty()) {
                showEmptyState("No repositories found.");
            } else {
                showResults(repos);
            }
        }));
    }

    private void showEmptyState(String message) {
        Label glyph = new Label("◍");
        glyph.getStyleClass().add("picker-empty-glyph");
        Label label = new Label(message);
        label.getStyleClass().add("picker-empty-title");
        VBox empty = new VBox(6, glyph, label);
        empty.setAlignment(Pos.CENTER);
        empty.setPrefHeight(200);
        results.getChildren().setAll(empty);
    }

    private void showResults(List<GitHubRepo> repos) {
        results.getChildren().setAll(repos.stream().map(this::buildRow).toList());
    }

    private HBox buildRow(GitHubRepo repo) {
        Label initial = new Label(repo.fullName().substring(0, 1).toUpperCase(Locale.ROOT));
        initial.getStyleClass().add("conv-initial");

        Label name = new Label(repo.fullName());
        name.getStyleClass().add("gh-name");
        Label description = new Label(repo.description().orElse(""));
        description.getStyleClass().add("gh-desc");
        description.setMaxWidth(300);

        String stars = repo.stars() < 0 ? "" : "★ " + formatStars(repo.stars());
        String meta = repo.language().map(lang -> stars.isEmpty() ? lang : stars + " · " + lang).orElse(stars);
        Label metaLabel = new Label(meta);
        metaLabel.getStyleClass().add("gh-meta");

        VBox text = new VBox(2, name, description, metaLabel);
        if (repo.description().isEmpty()) {
            text.getChildren().remove(description);
        }
        if (meta.isEmpty()) {
            text.getChildren().remove(metaLabel);
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        Button cloneButton = new Button("Clone");
        cloneButton.getStyleClass().add("clone-button");
        cloneButton.setOnAction(e -> startClone(repo, cloneButton));

        HBox row = new HBox(12, initial, text, cloneButton);
        row.getStyleClass().add("gh-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String formatStars(long stars) {
        return stars >= 1000 ? String.format(Locale.ROOT, "%.1fk", stars / 1000.0) : String.valueOf(stars);
    }

    private void startClone(GitHubRepo repo, Button cloneButton) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Clone " + repo.fullName() + " into…");
        Window owner = getScene() == null ? null : getScene().getWindow();
        File parent = chooser.showDialog(owner);
        if (parent == null) {
            return;
        }
        cloneButton.setDisable(true);
        cloneButton.setText("Cloning…");
        statusLine.setText("Cloning " + repo.fullName() + " — this can take a while for large repositories…");
        gitHubService.clone(repo, parent.toPath())
                .thenCompose(repositoryManager::addRepository)
                .whenComplete((repository, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        cloneButton.setDisable(false);
                        cloneButton.setText("Clone");
                        statusLine.setText("");
                        UiErrors.show("Could not clone " + repo.fullName(), failure);
                        return;
                    }
                    onClose.run();
                }));
    }
}
