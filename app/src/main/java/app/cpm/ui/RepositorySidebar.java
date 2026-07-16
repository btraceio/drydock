package app.cpm.ui;

import app.cpm.app.DuplicateRepositoryException;
import app.cpm.app.ExternalEditorLauncher;
import app.cpm.app.FinderLauncher;
import app.cpm.app.RepositoryManager;
import app.cpm.app.SessionManager;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.Repository;
import app.cpm.domain.RepositoryId;
import app.cpm.git.GitBranchState;
import app.cpm.git.GitStatus;
import app.cpm.git.GitStatusService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The repository sidebar (plan section 12): a list of registered
 * repositories with a branch/dirty indicator per row, an "Add
 * repository..." action, a per-repository context menu (including "New
 * Claude session"), and -- since Milestone 5's terminal-tabs step -- a
 * nested list of that repository's {@link ManagedClaudeSession} rows
 * (double-click or "Resume" to open/focus a tab; per-session context menu
 * for rename/stop/reveal), approximating plan section 12's two-level tree
 * sketch using a single flat {@link ListView} whose cells render their own
 * child rows, rather than an actual {@code TreeView}.</p>
 *
 * <p>Session state itself is not cached here: every row is rendered
 * directly from {@link SessionManager#sessions()} each time a cell
 * updates, and {@link #refreshSessions()} (called by {@link MainWorkspace}
 * after any open/resume/close/rename) simply forces a re-render.</p>
 */
public final class RepositorySidebar extends BorderPane {

    private static final Logger LOG = System.getLogger(RepositorySidebar.class.getName());

    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final SessionManager sessionManager;
    private final MainWorkspace mainWorkspace;
    private final ExternalEditorLauncher editorLauncher = new ExternalEditorLauncher();

    private final ObservableList<Repository> repositories = FXCollections.observableArrayList();
    private final ListView<Repository> listView = new ListView<>(repositories);

    /** Latest known Git status per repository, populated asynchronously; absent until the first refresh completes. */
    private final Map<RepositoryId, GitStatus> statuses = new ConcurrentHashMap<>();
    /** The most recent Git-status failure per repository (e.g. the directory vanished), if any. */
    private final Map<RepositoryId, Throwable> statusFailures = new ConcurrentHashMap<>();

    public RepositorySidebar(RepositoryManager repositoryManager, GitStatusService gitStatusService,
                              SessionManager sessionManager, MainWorkspace mainWorkspace) {
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.sessionManager = sessionManager;
        this.mainWorkspace = mainWorkspace;

        repositories.setAll(sorted(repositoryManager.repositories()));

        listView.setCellFactory(view -> new RepositoryCell());
        listView.setPlaceholder(new Label("No repositories yet. Use \"Add repository...\" to get started."));

        Button addButton = new Button("Add repository...");
        addButton.setOnAction(e -> onAddRepository());
        HBox toolbar = new HBox(addButton);
        toolbar.setPadding(new Insets(8));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        setTop(toolbar);
        setCenter(listView);

        refreshAllStatuses();
    }

    private static List<Repository> sorted(List<Repository> source) {
        return source.stream()
                .sorted(Comparator.comparing(Repository::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void refreshAllStatuses() {
        for (Repository repository : repositories) {
            refreshStatus(repository);
        }
    }

    private void refreshStatus(Repository repository) {
        gitStatusService.getStatus(repository.root())
                .whenComplete((status, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        statusFailures.put(repository.id(), UiErrors.unwrap(failure));
                        statuses.remove(repository.id());
                        LOG.log(Level.DEBUG, "Git status refresh failed for " + repository.root(), failure);
                    } else {
                        statuses.put(repository.id(), status);
                        statusFailures.remove(repository.id());
                    }
                    listView.refresh();
                }));
    }

    private void onAddRepository() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add repository");
        Window ownerWindow = getScene() == null ? null : getScene().getWindow();
        java.io.File chosen = chooser.showDialog(ownerWindow);
        if (chosen == null) {
            return;
        }

        Path directory = chosen.toPath();
        repositoryManager.addRepository(directory).whenComplete((repository, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                UiErrors.show("Could not add repository", failure);
                return;
            }
            repositories.setAll(sorted(repositoryManager.repositories()));
            refreshStatus(repository);
            listView.getSelectionModel().select(repository);
        }));
    }

    private void onRemoveRepository(Repository repository) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.setTitle("Remove repository");
        confirm.setHeaderText("Remove \"" + repository.displayName() + "\" from the manager?");
        confirm.setContentText("This only removes it from Claude Project Manager's list. "
                + "Nothing on disk at " + repository.root() + " is touched or deleted.");
        confirm.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
            repositoryManager.removeRepository(repository.id());
            statuses.remove(repository.id());
            statusFailures.remove(repository.id());
            repositories.setAll(sorted(repositoryManager.repositories()));
        });
    }

    private void onOpenInFinder(Repository repository) {
        try {
            FinderLauncher.reveal(repository.root());
        } catch (IOException e) {
            UiErrors.show("Could not open in Finder", e);
        }
    }

    private void onOpenInEditor(Repository repository) {
        try {
            editorLauncher.open(repository.root());
        } catch (IOException e) {
            UiErrors.show("Could not open in external editor", e);
        }
    }

    /** Exposed for tests: the repositories currently displayed, in display order. */
    ObservableList<Repository> displayedRepositories() {
        return repositories;
    }

    /**
     * Re-renders every row's session sub-list from {@link SessionManager}'s
     * current in-memory state. Called by {@link MainWorkspace} (via {@link
     * MainWorkspace#setOnSessionsChanged}) after any session is opened,
     * resumed, closed, or renamed -- session metadata itself lives in
     * {@link SessionManager}, not a local cache here, so a plain {@link
     * ListView#refresh()} (forcing every visible cell's {@code
     * updateItem} to re-run) is sufficient.
     */
    public void refreshSessions() {
        listView.refresh();
    }

    private List<ManagedClaudeSession> sessionsFor(Repository repository) {
        return sessionManager.sessions().stream()
                .filter(session -> session.repositoryId().equals(repository.id()))
                .sorted(Comparator.comparing(ManagedClaudeSession::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private final class RepositoryCell extends ListCell<Repository> {

        private final Label nameLabel = new Label();
        private final Label branchLabel = new Label();
        private final Label sessionsLabel = new Label("0 running sessions");
        private final VBox sessionsBox = new VBox(1);
        private final VBox container;

        RepositoryCell() {
            nameLabel.getStyleClass().add("repository-name");
            branchLabel.getStyleClass().add("repository-branch");
            sessionsLabel.getStyleClass().add("repository-sessions");

            HBox topRow = new HBox(6, nameLabel);
            topRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            HBox bottomRow = new HBox(10, branchLabel, sessionsLabel);
            bottomRow.setAlignment(Pos.CENTER_LEFT);

            sessionsBox.setPadding(new Insets(2, 0, 0, 14));

            container = new VBox(2, topRow, bottomRow, sessionsBox);
            container.setPadding(new Insets(4, 8, 4, 8));
        }

        @Override
        protected void updateItem(Repository repository, boolean empty) {
            super.updateItem(repository, empty);
            if (empty || repository == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }

            nameLabel.setText(repository.displayName());

            List<ManagedClaudeSession> sessions = sessionsFor(repository);
            long running = sessions.stream().filter(s -> s.status() == app.cpm.domain.SessionStatus.RUNNING).count();
            sessionsLabel.setText(running + " running session" + (running == 1 ? "" : "s"));
            sessionsBox.getChildren().setAll(sessions.stream().map(this::buildSessionRow).toList());

            GitStatus status = statuses.get(repository.id());
            Throwable failure = statusFailures.get(repository.id());
            if (status != null) {
                branchLabel.setText(branchText(status) + (status.dirty() ? " *" : ""));
                setTooltip(null);
            } else if (failure != null) {
                branchLabel.setText("(status unavailable)");
                setTooltip(new javafx.scene.control.Tooltip(String.valueOf(failure.getMessage())));
            } else {
                branchLabel.setText("(checking...)");
                setTooltip(null);
            }

            setText(null);
            setGraphic(container);
            setContextMenu(buildContextMenu(repository));
        }

        private HBox buildSessionRow(ManagedClaudeSession session) {
            Label nameLabel = new Label(statusIcon(session) + " " + session.displayName());
            nameLabel.getStyleClass().add("session-row-name");
            HBox row = new HBox(6, nameLabel);
            row.setAlignment(Pos.CENTER_LEFT);
            javafx.scene.control.Tooltip.install(row, new javafx.scene.control.Tooltip(
                    "Status: " + session.status() + "\nLast opened: " + session.lastOpenedAt()
                            + "\nWorking directory: " + session.workingDirectory()));

            ContextMenu sessionMenu = buildSessionContextMenu(session);
            row.setOnContextMenuRequested(event -> {
                // Without consuming, this event bubbles up to the enclosing
                // ListCell, which has its own repository-level context menu
                // set via setContextMenu(...) -- Control's default handling
                // shows THAT menu too, on top of this one, on every
                // right-click of a session row.
                sessionMenu.show(row, event.getScreenX(), event.getScreenY());
                event.consume();
            });
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    mainWorkspace.resumeSession(session);
                }
            });
            return row;
        }

        private String statusIcon(ManagedClaudeSession session) {
            return switch (session.status()) {
                case RUNNING -> "●"; // filled circle
                case STARTING -> "◐";
                case FAILED, MISSING_WORKING_DIRECTORY -> "⚠"; // warning
                case EXITED, INACTIVE -> "○"; // hollow circle
            };
        }

        private ContextMenu buildSessionContextMenu(ManagedClaudeSession session) {
            MenuItem resume = new MenuItem("Resume");
            resume.setOnAction(e -> mainWorkspace.resumeSession(session));

            MenuItem rename = new MenuItem("Rename...");
            rename.setOnAction(e -> mainWorkspace.promptRenameSession(session));

            MenuItem stop = new MenuItem("Stop process");
            stop.setOnAction(e -> {
                mainWorkspace.closeSession(session.id());
                refreshSessions();
            });

            MenuItem reveal = new MenuItem("Reveal working directory");
            reveal.setOnAction(e -> {
                try {
                    FinderLauncher.reveal(session.workingDirectory());
                } catch (IOException ex) {
                    UiErrors.show("Could not reveal working directory", ex);
                }
            });

            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(resume, rename, stop, new SeparatorMenuItem(), reveal);
            return menu;
        }

        private String branchText(GitStatus status) {
            if (status.branch() instanceof GitBranchState.OnBranch onBranch) {
                return onBranch.name();
            }
            if (status.branch() instanceof GitBranchState.Detached detached) {
                String oid = detached.commitOid();
                return "detached@" + (oid.length() > 7 ? oid.substring(0, 7) : oid);
            }
            return "(unknown)";
        }

        private ContextMenu buildContextMenu(Repository repository) {
            ContextMenu menu = new ContextMenu();

            MenuItem newSession = new MenuItem("New Claude session");
            newSession.setOnAction(e -> mainWorkspace.openNewSession(repository));

            MenuItem refresh = new MenuItem("Refresh");
            refresh.setOnAction(e -> refreshStatus(repository));

            MenuItem openFinder = new MenuItem("Open in Finder");
            openFinder.setOnAction(e -> onOpenInFinder(repository));

            MenuItem openEditor = new MenuItem("Open in external editor");
            openEditor.setOnAction(e -> onOpenInEditor(repository));

            MenuItem remove = new MenuItem("Remove from manager");
            remove.setOnAction(e -> onRemoveRepository(repository));

            menu.getItems().addAll(newSession, new SeparatorMenuItem(), refresh, openFinder, openEditor,
                    new SeparatorMenuItem(), remove);
            return menu;
        }
    }
}
