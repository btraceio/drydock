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
import app.cpm.git.WorktreeService;
import javafx.animation.PauseTransition;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The repository sidebar, rebuilt to the design handoff (README section 2)
 * and remodeled WORKTREE-FIRST for the worktree lifecycle handoff (section
 * B "Discovering worktrees"): expanding a repository lists every worktree
 * {@code git worktree list} finds on disk -- including worktrees created
 * outside this app -- reconciled against the managed sessions by
 * {@code worktreeRoot}. Worktrees WITH a session render as session rows
 * (status dot, branch tag, dirty dot, PR chip, idle Resume pill);
 * worktrees WITHOUT one render UNOPENED (branch + short path + an accent
 * "Start ▸" pill + a one-click 🗑 delete, guarded off the main checkout).
 * Each repo header gains a ⟳ rescan that re-runs discovery; newly-found
 * rows get a one-shot highlight.
 *
 * <p>Data still comes straight from {@link RepositoryManager} /
 * {@link SessionManager} / {@link WorktreeService} on every rebuild --
 * nothing is cached here beyond the current discovery results. {@link
 * #refreshSessions()} (called by {@link MainWorkspace} after any session
 * change) rebuilds the tree.</p>
 */
public final class RepositorySidebar extends VBox {

    private static final Logger LOG = System.getLogger(RepositorySidebar.class.getName());

    /** How long a freshly discovered worktree row keeps its highlight ring. */
    private static final Duration DISCOVERY_HIGHLIGHT = Duration.seconds(2.4);

    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final WorktreeService worktreeService;
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

    /** Latest worktree discovery per repository ({@code git worktree list --porcelain}); absent until the first scan. */
    private final Map<RepositoryId, List<WorktreeService.Worktree>> worktreeLists = new ConcurrentHashMap<>();
    /** Repositories with a rescan in flight (spins the ⟳ button, prevents double-scans). */
    private final Set<RepositoryId> scanning = ConcurrentHashMap.newKeySet();
    /** Worktree paths discovered by the latest rescan, highlighted one-shot until the timer clears them. */
    private final Set<Path> recentlyDiscovered = new HashSet<>();
    /** Transient per-repo meta note ("Already up to date — no new worktrees") shown briefly after a rescan. */
    private final Map<RepositoryId, String> rescanNotes = new ConcurrentHashMap<>();

    private Runnable onCloneFromGitHub = () -> { };
    private Consumer<Repository> onNewWorktree = repository -> { };

    /** Tree node payload: a repository row, a session row, or an unopened (discovered) worktree row. */
    sealed interface SidebarNode {
        record RepoNode(Repository repository) implements SidebarNode { }
        record SessionNode(ManagedClaudeSession session, Repository repository) implements SidebarNode { }
        record UnopenedWorktreeNode(WorktreeService.Worktree worktree, Repository repository)
                implements SidebarNode { }
    }

    public RepositorySidebar(RepositoryManager repositoryManager, GitStatusService gitStatusService,
                              WorktreeService worktreeService, SessionManager sessionManager,
                              MainWorkspace mainWorkspace) {
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.worktreeService = worktreeService;
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
        int worktreeTotal = 0;
        int unopenedTotal = 0;

        for (Repository repository : repositories) {
            if (!query.isEmpty() && !matchesFilter(repository, query)) {
                continue;
            }
            TreeItem<SidebarNode> repoItem = new TreeItem<>(new SidebarNode.RepoNode(repository));
            for (SidebarNode child : childNodesFor(repository)) {
                repoItem.getChildren().add(new TreeItem<>(child));
                if (child instanceof SidebarNode.UnopenedWorktreeNode) {
                    unopenedTotal++;
                }
            }
            worktreeTotal += additionalWorktreeCount(repository);
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

        // Footer status line (worktree handoff): N running · M worktrees ·
        // K unopened. Until a repo's discovery has run, fall back to the
        // session-derived worktree count so the line never reads "0".
        if (worktreeLists.isEmpty()) {
            worktreeTotal = (int) sessionManager.sessions().stream()
                    .filter(session -> session.worktreeRoot().isPresent())
                    .count();
        }
        String footerText = runningTotal + " running · " + worktreeTotal
                + (worktreeTotal == 1 ? " worktree" : " worktrees");
        if (unopenedTotal > 0) {
            footerText += " · " + unopenedTotal + " unopened";
        }
        footerLabel.setText(footerText);
        SessionStatusStyles.updateDot(footerDot, runningTotal > 0 ? SessionStatus.RUNNING : SessionStatus.INACTIVE);
    }

    /**
     * The worktree-first children of one repository row: the main checkout's
     * sessions (or the main checkout itself, unopened), then every
     * additional worktree on disk -- as a session row when a managed
     * session lives in it, else as an unopened row -- then any worktree
     * session whose directory discovery no longer reports (e.g. removed
     * outside the app; kept visible so it can still be cleaned up).
     */
    private List<SidebarNode> childNodesFor(Repository repository) {
        List<ManagedClaudeSession> sessions = sessionsFor(repository);
        List<WorktreeService.Worktree> worktrees = worktreeLists.get(repository.id());
        if (worktrees == null) {
            // Discovery hasn't run yet for this repo: kick it off and show
            // the session-derived rows meanwhile.
            refreshWorktrees(repository, false);
            return new ArrayList<>(sessions.stream()
                    .map(session -> (SidebarNode) new SidebarNode.SessionNode(session, repository))
                    .toList());
        }

        List<SidebarNode> children = new ArrayList<>();
        Set<ManagedClaudeSession> placed = new LinkedHashSet<>();
        for (WorktreeService.Worktree worktree : worktrees) {
            if (worktree.mainCheckout()) {
                List<ManagedClaudeSession> mainSessions = sessions.stream()
                        .filter(session -> session.worktreeRoot().isEmpty())
                        .toList();
                if (mainSessions.isEmpty()) {
                    children.add(new SidebarNode.UnopenedWorktreeNode(worktree, repository));
                } else {
                    for (ManagedClaudeSession session : mainSessions) {
                        children.add(new SidebarNode.SessionNode(session, repository));
                        placed.add(session);
                    }
                }
            } else {
                Optional<ManagedClaudeSession> match = sessions.stream()
                        .filter(session -> session.worktreeRoot()
                                .map(root -> root.equals(worktree.path()))
                                .orElse(false))
                        .findFirst();
                if (match.isPresent()) {
                    children.add(new SidebarNode.SessionNode(match.get(), repository));
                    placed.add(match.get());
                } else {
                    children.add(new SidebarNode.UnopenedWorktreeNode(worktree, repository));
                }
            }
        }
        for (ManagedClaudeSession session : sessions) {
            if (!placed.contains(session) && session.worktreeRoot().isPresent()) {
                children.add(new SidebarNode.SessionNode(session, repository));
            }
        }
        return children;
    }

    /** Count of additional (non-main) worktrees on disk, once discovery has run. */
    private int additionalWorktreeCount(Repository repository) {
        List<WorktreeService.Worktree> worktrees = worktreeLists.get(repository.id());
        if (worktrees == null) {
            return 0;
        }
        return (int) worktrees.stream().filter(worktree -> !worktree.mainCheckout()).count();
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

    // ---- Worktree discovery (worktree handoff, section B) -------------------

    /**
     * Re-runs {@code git worktree list} for {@code repository}. A
     * user-initiated rescan (the ⟳ button/menu item) additionally
     * highlights newly appearing rows and, when nothing new appeared,
     * briefly notes "Already up to date" in the repo meta line.
     */
    private void refreshWorktrees(Repository repository, boolean userInitiated) {
        if (!scanning.add(repository.id())) {
            return;
        }
        List<WorktreeService.Worktree> previous = worktreeLists.get(repository.id());
        worktreeService.list(repository.root())
                .whenComplete((worktrees, failure) -> Platform.runLater(() -> {
                    scanning.remove(repository.id());
                    if (failure != null) {
                        LOG.log(Level.DEBUG, "Worktree discovery failed for " + repository.root(), failure);
                        if (userInitiated) {
                            UiErrors.show("Could not rescan worktrees", failure);
                        }
                        return;
                    }
                    worktreeLists.put(repository.id(), worktrees);
                    for (WorktreeService.Worktree worktree : worktrees) {
                        if (!worktree.mainCheckout() && !worktreeStatuses.containsKey(worktree.path())) {
                            refreshWorktreeStatus(worktree.path());
                        }
                    }
                    if (userInitiated && previous != null) {
                        Set<Path> known = new HashSet<>();
                        for (WorktreeService.Worktree worktree : previous) {
                            known.add(worktree.path());
                        }
                        List<Path> fresh = worktrees.stream()
                                .map(WorktreeService.Worktree::path)
                                .filter(path -> !known.contains(path))
                                .toList();
                        if (fresh.isEmpty()) {
                            rescanNotes.put(repository.id(), "Already up to date — no new worktrees");
                            PauseTransition clearNote = new PauseTransition(Duration.seconds(2.4));
                            clearNote.setOnFinished(e -> {
                                rescanNotes.remove(repository.id());
                                rebuildTree();
                            });
                            clearNote.play();
                        } else {
                            recentlyDiscovered.addAll(fresh);
                            PauseTransition clearHighlight = new PauseTransition(DISCOVERY_HIGHLIGHT);
                            clearHighlight.setOnFinished(e -> {
                                fresh.forEach(recentlyDiscovered::remove);
                                rebuildTree();
                            });
                            clearHighlight.play();
                        }
                    }
                    rebuildTree();
                }));
    }

    /** One-click 🗑 of an unopened worktree: {@code git worktree remove} + {@code git branch -D}. */
    private void onDeleteUnopenedWorktree(Repository repository, WorktreeService.Worktree worktree) {
        worktreeService.remove(repository.root(), worktree.path(), worktree.branch())
                .whenComplete((v, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        UiErrors.show("Could not delete worktree", failure);
                        return;
                    }
                    worktreeStatuses.remove(worktree.path());
                    refreshWorktrees(repository, false);
                }));
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
            worktreeLists.remove(repository.id());
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

    /** Abbreviates the user's home directory to {@code ~} for compact worktree paths. */
    private static String shortPath(Path path) {
        String home = System.getProperty("user.home");
        String text = path.toString();
        return home != null && text.startsWith(home) ? "~" + text.substring(home.length()) : text;
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
                case SidebarNode.UnopenedWorktreeNode worktreeNode -> {
                    setGraphic(buildUnopenedRow(worktreeNode.worktree(), worktreeNode.repository()));
                    setContextMenu(null);
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

            Label branch = new Label(repoMetaText(repository));
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

            Button rescan = new Button("⟳");
            rescan.getStyleClass().add("row-action-button");
            rescan.setTooltip(new Tooltip("Rescan worktrees"));
            rescan.setFocusTraversable(false);
            rescan.visibleProperty().bind(hoverProperty());
            if (scanning.contains(repository.id())) {
                RotateTransition spin = new RotateTransition(Duration.seconds(0.8), rescan);
                spin.setByAngle(360);
                spin.setCycleCount(RotateTransition.INDEFINITE);
                spin.play();
            }
            rescan.setOnAction(e -> {
                refreshStatus(repository);
                refreshWorktrees(repository, true);
            });

            Button newSession = new Button("+");
            newSession.getStyleClass().add("row-action-button");
            newSession.setTooltip(new Tooltip("New session or worktree…"));
            newSession.setFocusTraversable(false);
            newSession.visibleProperty().bind(hoverProperty());
            ContextMenu newMenu = buildNewSessionMenu(repository);
            newSession.setOnAction(e -> newMenu.show(newSession, Side.BOTTOM, 0, 4));

            HBox row = new HBox(7, caret, text, count, rescan, newSession);
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

        /** Repo meta line: {@code ⎇ <base> · <n> worktrees · <m> unopened} once discovery has run. */
        private String repoMetaText(Repository repository) {
            String note = rescanNotes.get(repository.id());
            if (note != null) {
                return note;
            }
            StringBuilder meta = new StringBuilder("⎇ ").append(branchTextFor(repository));
            List<WorktreeService.Worktree> worktrees = worktreeLists.get(repository.id());
            if (worktrees != null) {
                int additional = additionalWorktreeCount(repository);
                meta.append(" · ").append(additional).append(additional == 1 ? " worktree" : " worktrees");
                long unopened = childNodesFor(repository).stream()
                        .filter(child -> child instanceof SidebarNode.UnopenedWorktreeNode)
                        .count();
                if (unopened > 0) {
                    meta.append(" · ").append(unopened).append(" unopened");
                }
            }
            return meta.toString();
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
            // An IDLE session advertises resumability with a ghost Resume
            // pill (worktree handoff: clicking the row resumes it).
            if (!SessionStatusStyles.isRunning(session.status())) {
                Label resumePill = new Label("Resume");
                resumePill.getStyleClass().add("resume-pill");
                row.getChildren().add(row.getChildren().indexOf(actions), resumePill);
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

        /**
         * A discovered worktree with no session (worktree handoff): faint
         * icon, branch as the primary line, short path as the sub line, an
         * accent Start ▸ pill and -- never on the main checkout -- a
         * one-click 🗑 that removes worktree + branch.
         */
        private HBox buildUnopenedRow(WorktreeService.Worktree worktree, Repository repository) {
            Label icon = new Label(worktree.mainCheckout() ? "⎇" : "◫");
            icon.getStyleClass().add("worktree-unopened-icon");

            String branch = worktree.branch().orElse(worktree.detached() ? "(detached)" : "(no branch)");
            Label name = new Label(branch);
            name.getStyleClass().add("worktree-unopened-branch");

            Label meta = new Label(shortPath(worktree.path()));
            meta.getStyleClass().add("session-meta");

            VBox text = new VBox(1, name, meta);
            HBox.setHgrow(text, Priority.ALWAYS);

            Label startPill = new Label("Start ▸");
            startPill.getStyleClass().add("start-pill");
            startPill.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    mainWorkspace.promptStartWorktreeSession(repository, worktree);
                    event.consume();
                }
            });

            HBox row = new HBox(8, icon, text, startPill);
            if (!worktree.mainCheckout()) {
                Button delete = quickAction("🗑", "Delete worktree & branch", true,
                        () -> onDeleteUnopenedWorktree(repository, worktree));
                delete.getStyleClass().add("worktree-delete-button");
                row.getChildren().add(delete);
            }
            row.getStyleClass().add("worktree-unopened-row");
            if (recentlyDiscovered.contains(worktree.path())) {
                row.getStyleClass().add("worktree-discovered");
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(5, 8, 5, 16));
            Tooltip.install(row, new Tooltip("Discovered via git worktree list\n" + worktree.path()));
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    mainWorkspace.showUnopenedWorktree(repository, worktree);
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

        /** The repo "+" menu (worktree handoff "Creating"): checkout session / new worktree / rescan. */
        private ContextMenu buildNewSessionMenu(Repository repository) {
            MenuItem inCheckout = new MenuItem("❯_  Session on main checkout");
            inCheckout.setOnAction(e -> mainWorkspace.openNewSession(repository));
            MenuItem newWorktree = new MenuItem("◫  New worktree…");
            newWorktree.setOnAction(e -> onNewWorktree.accept(repository));
            MenuItem rescan = new MenuItem("⟳  Rescan worktrees");
            rescan.setOnAction(e -> refreshWorktrees(repository, true));
            return new ContextMenu(inCheckout, newWorktree, rescan);
        }

        private ContextMenu buildRepoContextMenu(Repository repository) {
            MenuItem newSession = new MenuItem("New Claude session");
            newSession.setOnAction(e -> mainWorkspace.openNewSession(repository));
            MenuItem newWorktree = new MenuItem("New worktree…");
            newWorktree.setOnAction(e -> onNewWorktree.accept(repository));

            MenuItem rescan = new MenuItem("Rescan worktrees");
            rescan.setOnAction(e -> refreshWorktrees(repository, true));

            MenuItem refresh = new MenuItem("Refresh");
            refresh.setOnAction(e -> {
                refreshStatus(repository);
                refreshWorktrees(repository, true);
            });

            MenuItem openFinder = new MenuItem("Open in Finder");
            openFinder.setOnAction(e -> onOpenInFinder(repository));

            MenuItem openEditor = new MenuItem("Open in external editor");
            openEditor.setOnAction(e -> onOpenInEditor(repository));

            MenuItem remove = new MenuItem("Remove from manager");
            remove.setOnAction(e -> onRemoveRepository(repository));

            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(newSession, newWorktree, rescan, new SeparatorMenuItem(), refresh, openFinder,
                    openEditor, new SeparatorMenuItem(), remove);
            return menu;
        }
    }
}
