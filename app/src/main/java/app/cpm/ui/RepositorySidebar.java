package app.cpm.ui;

import app.cpm.app.ExternalEditorLauncher;
import app.cpm.app.FinderLauncher;
import app.cpm.app.RepositoryManager;
import app.cpm.app.SessionManager;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.PrState;
import app.cpm.domain.Repository;
import app.cpm.domain.RepositoryId;
import app.cpm.domain.SessionStatus;
import app.cpm.git.GitBranchState;
import app.cpm.git.GitStatus;
import app.cpm.git.GitStatusService;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The repository sidebar, rebuilt to the design handoff (README section 2):
 * an accent "Add repository" menu button (open from disk / clone from
 * GitHub), a live filter field, a {@link TreeView} of repositories with
 * their sessions as children (custom cells: carets, status dots, hover
 * quick-actions), and a footer status line.
 *
 * <p>Data still comes straight from {@link RepositoryManager} /
 * {@link SessionManager} on every rebuild -- nothing is cached here beyond
 * the tree items currently displayed. {@link #refreshSessions()} (called
 * by {@link MainWorkspace} after any session change) rebuilds the tree.</p>
 */
public final class RepositorySidebar extends VBox {

    private static final Logger LOG = System.getLogger(RepositorySidebar.class.getName());

    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final SessionManager sessionManager;
    private final MainWorkspace mainWorkspace;
    private final ExternalEditorLauncher editorLauncher = new ExternalEditorLauncher();

    private final TextField filterField = new TextField();
    private final TreeItem<SidebarNode> treeRoot = new TreeItem<>();
    private final TreeView<SidebarNode> tree = new TreeView<>(treeRoot);
    private final Label footerLabel = new Label();
    private final Region footerDot = new Region();

    /** Which repository subtrees are expanded; new repositories start expanded. */
    private final Set<RepositoryId> collapsed = new HashSet<>();

    /** Latest known Git status per repository, populated asynchronously; absent until the first refresh completes. */
    private final Map<RepositoryId, GitStatus> statuses = new ConcurrentHashMap<>();
    /** The most recent Git-status failure per repository (e.g. the directory vanished), if any. */
    private final Map<RepositoryId, Throwable> statusFailures = new ConcurrentHashMap<>();
    /** Latest known Git status per worktree root (worktree sessions have their own checkout + branch). */
    private final Map<Path, GitStatus> worktreeStatuses = new ConcurrentHashMap<>();

    private Runnable onCloneFromGitHub = () -> { };
    private Consumer<Repository> onNewWorktree = repository -> { };

    /** Tree node payload: either a repository row or one of its session rows. */
    sealed interface SidebarNode {
        record RepoNode(Repository repository) implements SidebarNode { }
        record SessionNode(ManagedClaudeSession session, Repository repository) implements SidebarNode { }
    }

    public RepositorySidebar(RepositoryManager repositoryManager, GitStatusService gitStatusService,
                              SessionManager sessionManager, MainWorkspace mainWorkspace) {
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.sessionManager = sessionManager;
        this.mainWorkspace = mainWorkspace;

        getStyleClass().add("sidebar");

        // -- Header: add-repository menu + filter field ---------------------
        MenuItem openFromDisk = new MenuItem("Open from disk…");
        openFromDisk.setOnAction(e -> onAddRepositoryFromDisk());
        MenuItem cloneFromGitHub = new MenuItem("Clone from GitHub…");
        cloneFromGitHub.setOnAction(e -> onCloneFromGitHub.run());
        MenuButton addButton = new MenuButton("＋  Add repository", null, openFromDisk, cloneFromGitHub);
        addButton.getStyleClass().add("add-repo-button");
        addButton.setMaxWidth(Double.MAX_VALUE);

        filterField.getStyleClass().add("filter-field");
        filterField.setPromptText("⌕  Filter repositories…");
        filterField.textProperty().addListener((obs, oldText, newText) -> rebuildTree());

        VBox header = new VBox(addButton, filterField);
        header.getStyleClass().add("sidebar-header");

        // -- Tree -----------------------------------------------------------
        tree.getStyleClass().add("repo-tree");
        tree.setShowRoot(false);
        tree.setCellFactory(view -> new SidebarTreeCell());
        VBox.setVgrow(tree, Priority.ALWAYS);

        // -- Footer ---------------------------------------------------------
        footerDot.getStyleClass().addAll("status-dot", "dot-5");
        HBox footer = new HBox(footerDot, footerLabel);
        footer.getStyleClass().add("sidebar-footer");

        getChildren().addAll(header, tree, footer);

        // Keep the displayed list in sync with EVERY repository mutation,
        // not just the ones initiated by this sidebar's own handlers. The
        // listener may fire on a background thread.
        repositoryManager.addChangeListener(() -> Platform.runLater(this::onRepositoriesChanged));

        rebuildTree();
        refreshAllStatuses();
    }

    /** Wired by the application shell to open the Clone-from-GitHub modal (design section 7). */
    public void setOnCloneFromGitHub(Runnable handler) {
        this.onCloneFromGitHub = handler == null ? () -> { } : handler;
    }

    /** Wired by the application shell to open the create-worktree modal (worktree handoff, section B). */
    public void setOnNewWorktree(Consumer<Repository> handler) {
        this.onNewWorktree = handler == null ? repository -> { } : handler;
    }

    /** Focuses the filter field (⌘F). */
    public void focusFilter() {
        filterField.requestFocus();
        filterField.selectAll();
    }

    /** Rebuilds the tree from current manager state; called after any session change. */
    public void refreshSessions() {
        refreshWorktreeStatuses();
        rebuildTree();
    }

    // ---- Tree building ------------------------------------------------------

    private static List<Repository> sorted(List<Repository> source) {
        return source.stream()
                .sorted(Comparator.comparing(Repository::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void onRepositoriesChanged() {
        for (Repository repository : repositoryManager.repositories()) {
            if (!statuses.containsKey(repository.id()) && !statusFailures.containsKey(repository.id())) {
                refreshStatus(repository);
            }
        }
        rebuildTree();
    }

    private void rebuildTree() {
        String query = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);

        List<Repository> repositories = sorted(repositoryManager.repositories());
        List<TreeItem<SidebarNode>> repoItems = new ArrayList<>();
        int runningTotal = 0;

        for (Repository repository : repositories) {
            if (!query.isEmpty() && !matchesFilter(repository, query)) {
                continue;
            }
            TreeItem<SidebarNode> repoItem = new TreeItem<>(new SidebarNode.RepoNode(repository));
            for (ManagedClaudeSession session : sessionsFor(repository)) {
                repoItem.getChildren().add(new TreeItem<>(new SidebarNode.SessionNode(session, repository)));
            }
            repoItem.setExpanded(!collapsed.contains(repository.id()));
            repoItem.expandedProperty().addListener((obs, was, is) -> {
                if (is) {
                    collapsed.remove(repository.id());
                } else {
                    collapsed.add(repository.id());
                }
            });
            repoItems.add(repoItem);
        }

        for (ManagedClaudeSession session : sessionManager.sessions()) {
            if (session.status() == SessionStatus.RUNNING || session.status() == SessionStatus.STARTING) {
                runningTotal++;
            }
        }

        treeRoot.getChildren().setAll(repoItems);

        long worktreeTotal = sessionManager.sessions().stream()
                .filter(session -> session.worktreeRoot().isPresent())
                .count();
        footerLabel.setText(runningTotal + " running · " + worktreeTotal
                + (worktreeTotal == 1 ? " worktree" : " worktrees"));
        SessionStatusStyles.updateDot(footerDot, runningTotal > 0 ? SessionStatus.RUNNING : SessionStatus.INACTIVE);
    }

    private boolean matchesFilter(Repository repository, String query) {
        if (repository.displayName().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        GitStatus status = statuses.get(repository.id());
        return status != null && branchText(status).toLowerCase(Locale.ROOT).contains(query);
    }

    private List<ManagedClaudeSession> sessionsFor(Repository repository) {
        return sessionManager.sessions().stream()
                .filter(session -> session.repositoryId().equals(repository.id()))
                .sorted(Comparator.comparing(ManagedClaudeSession::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---- Git status ---------------------------------------------------------

    private void refreshAllStatuses() {
        for (Repository repository : repositoryManager.repositories()) {
            refreshStatus(repository);
        }
        refreshWorktreeStatuses();
    }

    /** Fetches per-worktree status for sessions not yet covered (branch tag + dirty dot per worktree checkout). */
    private void refreshWorktreeStatuses() {
        for (ManagedClaudeSession session : sessionManager.sessions()) {
            session.worktreeRoot().ifPresent(root -> {
                if (!worktreeStatuses.containsKey(root)) {
                    refreshWorktreeStatus(root);
                }
            });
        }
    }

    private void refreshWorktreeStatus(Path worktreeRoot) {
        gitStatusService.getStatus(worktreeRoot)
                .whenComplete((status, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        worktreeStatuses.remove(worktreeRoot);
                        LOG.log(Level.DEBUG, "Git status refresh failed for worktree " + worktreeRoot, failure);
                    } else {
                        worktreeStatuses.put(worktreeRoot, status);
                    }
                    rebuildTree();
                }));
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
                    rebuildTree();
                }));
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

    private String branchTextFor(Repository repository) {
        GitStatus status = statuses.get(repository.id());
        if (status != null) {
            return branchText(status) + (status.dirty() ? " *" : "");
        }
        return statusFailures.containsKey(repository.id()) ? "(status unavailable)" : "…";
    }

    // ---- Actions ------------------------------------------------------------

    private void onAddRepositoryFromDisk() {
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
            refreshStatus(repository);
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
            rebuildTree();
        });
    }

    private void onDeleteSession(ManagedClaudeSession session) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.setTitle("Delete session");
        confirm.setHeaderText("Delete session \"" + session.displayName() + "\"?");
        confirm.setContentText("This removes the session from the manager (stopping it first if running). "
                + "Claude's own conversation history on disk is not deleted.");
        confirm.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button ->
                sessionManager.deleteSession(session.id()).whenComplete((v, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        UiErrors.show("Could not delete session", ex);
                    }
                    mainWorkspace.noteSessionDeleted(session.id());
                    rebuildTree();
                })));
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

    // ---- Cells --------------------------------------------------------------

    /** Relative "time ago" for session meta lines (design: `branch · 2h ago`). */
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

    private final class SidebarTreeCell extends TreeCell<SidebarNode> {

        @Override
        protected void updateItem(SidebarNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setText(null);
            switch (node) {
                case SidebarNode.RepoNode repoNode -> {
                    setGraphic(buildRepoRow(repoNode.repository()));
                    setContextMenu(buildRepoContextMenu(repoNode.repository()));
                }
                case SidebarNode.SessionNode sessionNode -> {
                    setGraphic(buildSessionRow(sessionNode.session(), sessionNode.repository()));
                    setContextMenu(buildSessionContextMenu(sessionNode.session()));
                }
            }
        }

        private HBox buildRepoRow(Repository repository) {
            Label caret = new Label("▶");
            caret.getStyleClass().add("repo-caret");
            boolean expanded = getTreeItem() != null && getTreeItem().isExpanded();
            caret.setRotate(expanded ? 90 : 0);

            Label name = new Label(repository.displayName());
            name.getStyleClass().add("repo-name");

            Label branch = new Label("⎇ " + branchTextFor(repository));
            branch.getStyleClass().add("repo-branch");
            Throwable failure = statusFailures.get(repository.id());
            if (failure != null) {
                branch.setTooltip(new Tooltip(String.valueOf(failure.getMessage())));
            }

            List<ManagedClaudeSession> sessions = sessionsFor(repository);
            boolean anyRunning = sessions.stream().anyMatch(s -> SessionStatusStyles.isRunning(s.status()));
            HBox branchRow = new HBox(6, branch);
            branchRow.setAlignment(Pos.CENTER_LEFT);
            if (anyRunning) {
                branchRow.getChildren().add(SessionStatusStyles.createDot(5, SessionStatus.RUNNING));
            }

            VBox text = new VBox(1, name, branchRow);
            HBox.setHgrow(text, Priority.ALWAYS);

            Label count = new Label(String.valueOf(sessions.size()));
            count.getStyleClass().add("repo-count");

            Button newSession = new Button("+");
            newSession.getStyleClass().add("row-action-button");
            newSession.setTooltip(new Tooltip("New session or worktree…"));
            newSession.setFocusTraversable(false);
            newSession.visibleProperty().bind(hoverProperty());
            ContextMenu newMenu = buildNewSessionMenu(repository);
            newSession.setOnAction(e -> newMenu.show(newSession, Side.BOTTOM, 0, 4));

            HBox row = new HBox(7, caret, text, count, newSession);
            row.getStyleClass().add("repo-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && getTreeItem() != null) {
                    boolean nowExpanded = !getTreeItem().isExpanded();
                    getTreeItem().setExpanded(nowExpanded);
                    RotateTransition rotate = new RotateTransition(Duration.seconds(0.12), caret);
                    rotate.setToAngle(nowExpanded ? 90 : 0);
                    rotate.play();
                    event.consume();
                }
            });
            return row;
        }

        private HBox buildSessionRow(ManagedClaudeSession session, Repository repository) {
            Region dot = SessionStatusStyles.createDot(8, session.status());

            Label name = new Label(session.displayName());
            name.getStyleClass().add("session-name");

            // Branch tag (worktree handoff "Sidebar session rows"): ◫ accent
            // for a worktree checkout, ⎇ dim for the current checkout.
            boolean isWorktree = session.worktreeRoot().isPresent();
            GitStatus checkoutStatus = session.worktreeRoot()
                    .map(worktreeStatuses::get)
                    .orElseGet(() -> statuses.get(repository.id()));
            String branch = checkoutStatus != null ? branchText(checkoutStatus) : "…";
            Label branchTag = new Label((isWorktree ? "◫ " : "⎇ ") + branch);
            branchTag.getStyleClass().add(isWorktree ? "branch-tag-worktree" : "branch-tag");

            HBox nameRow = new HBox(6, name, branchTag);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            if (checkoutStatus != null && checkoutStatus.dirty()) {
                Region dirtyDot = new Region();
                dirtyDot.getStyleClass().add("dirty-dot");
                nameRow.getChildren().add(dirtyDot);
            }

            Label meta = new Label(relativeTime(session.lastOpenedAt()));
            meta.getStyleClass().add("session-meta");

            VBox text = new VBox(1, nameRow, meta);
            HBox.setHgrow(text, Priority.ALWAYS);

            // Right-aligned PR chip: `PR #n` while open, `merged` after.
            Label prChip = switch (session.prState()) {
                case OPEN -> new Label(session.prNumber().map(n -> "PR #" + n).orElse("PR"));
                case MERGED -> new Label("merged");
                case NONE -> null;
            };
            if (prChip != null) {
                prChip.getStyleClass().add(session.prState() == PrState.MERGED
                        ? "pr-chip-merged" : "pr-chip");
            }

            Button open = quickAction("↗", "Open", false, () -> mainWorkspace.resumeSession(session));
            Button stop = quickAction("■", "Stop process", true, () -> mainWorkspace.closeSession(session.id()));
            stop.setDisable(!SessionStatusStyles.isRunning(session.status()));
            Button delete = quickAction("×", "Delete session", true, () -> onDeleteSession(session));
            HBox actions = new HBox(2, open, stop, delete);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.visibleProperty().bind(hoverProperty());

            HBox row = new HBox(8, dot, text, actions);
            if (prChip != null) {
                row.getChildren().add(row.getChildren().indexOf(actions), prChip);
            }
            row.getStyleClass().add("session-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5, 8, 5, 16));
            if (mainWorkspace.activeSessionId().filter(session.id()::equals).isPresent()) {
                row.getStyleClass().add("active");
            }
            Tooltip.install(row, new Tooltip(
                    "Status: " + session.status() + "\nLast opened: " + session.lastOpenedAt()
                            + "\nWorking directory: " + session.workingDirectory()));
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    mainWorkspace.resumeSession(session);
                    event.consume();
                }
            });
            return row;
        }

        private Button quickAction(String glyph, String tooltip, boolean destructive, Runnable action) {
            Button button = new Button(glyph);
            button.getStyleClass().add("row-action-button");
            if (destructive) {
                button.getStyleClass().add("destructive");
            }
            button.setTooltip(new Tooltip(tooltip));
            button.setFocusTraversable(false);
            button.setOnAction(e -> action.run());
            return button;
        }

        private ContextMenu buildSessionContextMenu(ManagedClaudeSession session) {
            MenuItem resume = new MenuItem("Resume");
            resume.setOnAction(e -> mainWorkspace.resumeSession(session));

            MenuItem rename = new MenuItem("Rename…");
            rename.setOnAction(e -> mainWorkspace.promptRenameSession(session));

            MenuItem stop = new MenuItem("Stop process");
            stop.setOnAction(e -> mainWorkspace.closeSession(session.id()));

            MenuItem delete = new MenuItem("Delete session");
            delete.setOnAction(e -> onDeleteSession(session));

            MenuItem reveal = new MenuItem("Reveal working directory");
            reveal.setOnAction(e -> {
                try {
                    FinderLauncher.reveal(session.workingDirectory());
                } catch (IOException ex) {
                    UiErrors.show("Could not reveal working directory", ex);
                }
            });

            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(resume, rename, stop, delete, new SeparatorMenuItem(), reveal);
            return menu;
        }

        /** The repo "+" menu (worktree handoff "Creating"): current checkout vs a fresh worktree. */
        private ContextMenu buildNewSessionMenu(Repository repository) {
            MenuItem inCheckout = new MenuItem("❯_  Session in current checkout");
            inCheckout.setOnAction(e -> mainWorkspace.openNewSession(repository));
            MenuItem newWorktree = new MenuItem("◫  New worktree…");
            newWorktree.setOnAction(e -> onNewWorktree.accept(repository));
            return new ContextMenu(inCheckout, newWorktree);
        }

        private ContextMenu buildRepoContextMenu(Repository repository) {
            MenuItem newSession = new MenuItem("New Claude session");
            newSession.setOnAction(e -> mainWorkspace.openNewSession(repository));
            MenuItem newWorktree = new MenuItem("New worktree…");
            newWorktree.setOnAction(e -> onNewWorktree.accept(repository));

            MenuItem refresh = new MenuItem("Refresh");
            refresh.setOnAction(e -> refreshStatus(repository));

            MenuItem openFinder = new MenuItem("Open in Finder");
            openFinder.setOnAction(e -> onOpenInFinder(repository));

            MenuItem openEditor = new MenuItem("Open in external editor");
            openEditor.setOnAction(e -> onOpenInEditor(repository));

            MenuItem remove = new MenuItem("Remove from manager");
            remove.setOnAction(e -> onRemoveRepository(repository));

            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(newSession, newWorktree, new SeparatorMenuItem(), refresh, openFinder, openEditor,
                    new SeparatorMenuItem(), remove);
            return menu;
        }
    }
}
