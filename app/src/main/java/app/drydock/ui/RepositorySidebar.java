package app.drydock.ui;

import app.drydock.app.ExternalEditorLauncher;
import app.drydock.app.FinderLauncher;
import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import app.drydock.git.GitTarget;
import app.drydock.git.SshUnreachableException;
import app.drydock.git.WorktreeNotCleanException;
import app.drydock.git.WorktreeService;
import app.drydock.ui.model.WorkspaceViewModel;
import java.io.File;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
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
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>All session/status data renders from the shared {@link
 * WorkspaceViewModel}: this sidebar's async git-status/worktree fetches
 * write their results into the model, and the model's diffed events drive
 * the narrowest matching update -- a status change re-renders one row via
 * {@code TreeItem.setValue}, a tab switch restyles only the previously/
 * newly active rows, and only genuine row additions/removals trigger a
 * (coalesced) full {@link #rebuildTree()}.</p>
 */
public final class RepositorySidebar extends VBox {

    private static final Logger LOG = System.getLogger(RepositorySidebar.class.getName());

    /** How long a freshly discovered worktree row keeps its highlight ring. */
    private static final Duration DISCOVERY_HIGHLIGHT = Duration.seconds(2.4);

    /** Filter keystroke debounce (mirrors SearchRail's search debounce). */
    private static final Duration FILTER_DEBOUNCE = Duration.millis(150);

    private final RepositoryManager repositoryManager;
    private final GitStatusService gitStatusService;
    private final WorktreeService worktreeService;
    private final SessionManager sessionManager;
    private final WorkspaceNavigator navigator;
    private final WorkspaceViewModel viewModel;
    private final ExternalEditorLauncher editorLauncher = new ExternalEditorLauncher();

    private final TextField filterField = new TextField();
    private final TreeItem<SidebarNode> treeRoot = new TreeItem<>();
    private final TreeView<SidebarNode> tree = new TreeView<>(treeRoot);
    private final Label footerLabel = new Label();
    private final Region footerDot = new Region();

    /** Which repository subtrees are expanded; new repositories start expanded. */
    private final Set<RepositoryId> collapsed = new HashSet<>();

    /** Repos whose stale bucket is expanded. Distinct from {@code collapsed} (repo-level). */
    private final Set<RepositoryId> staleBucketExpanded = new HashSet<>();

    /** Repositories with a rescan in flight (spins the ⟳ button, prevents double-scans). */
    private final Set<RepositoryId> scanning = ConcurrentHashMap.newKeySet();
    /** Worktree paths discovered by the latest rescan, highlighted one-shot until the timer clears them. */
    private final Set<Path> recentlyDiscovered = new HashSet<>();
    /** Transient per-repo meta note ("Already up to date — no new worktrees") shown briefly after a rescan. */
    private final Map<RepositoryId, String> rescanNotes = new ConcurrentHashMap<>();

    /** The session last scrolled into view, so status-refresh rebuilds don't keep yanking the scroll position. */
    private ManagedSessionId lastRevealedSession;

    /** Debounces filter keystrokes so the tree isn't rebuilt per character. */
    private final PauseTransition filterDebounce = new PauseTransition(FILTER_DEBOUNCE);

    /**
     * The session snapshot whose statuses were last re-fetched, compared by
     * identity: the model swaps the immutable snapshot instance on every
     * session change, so an unchanged reference means a structure/row event
     * came from worktree discovery or repo removal -- which never used to
     * trigger a status re-fetch either.
     */
    private List<ManagedAgentSession> statusRefreshedFor = List.of();

    /**
     * Coalesces async-completion rebuilds: N git-status/worktree results
     * landing in the same FX pulse trigger ONE {@link #rebuildTree()}
     * instead of one full-tree rebuild each (see {@link #requestRebuild()}).
     */
    private final AtomicBoolean rebuildPending = new AtomicBoolean();

    private Runnable onCloneFromGitHub = () -> { };
    private Runnable onAddRemote = () -> { };
    private Consumer<Repository> onNewWorktree = repository -> { };

    // -- Per-row cached popups (context menus and tooltips are not part of
    // the scene graph, so one instance can serve every cell that ever
    // renders the row). Menu handlers resolve the LIVE session through the
    // view model, never a captured snapshot, so a cached menu cannot act on
    // stale data. Pruned on structural changes (see pruneRowCaches).
    private final Map<ManagedSessionId, ContextMenu> sessionMenus = new HashMap<>();
    private final Map<ManagedSessionId, Tooltip> sessionTooltips = new HashMap<>();
    private final Map<RepositoryId, ContextMenu> repoMenus = new HashMap<>();
    private final Map<RepositoryId, ContextMenu> newSessionMenus = new HashMap<>();
    private final Map<Path, Tooltip> unopenedTooltips = new HashMap<>();

    /** Tree node payload: a repository row, a session row, or an unopened (discovered) worktree row. */
    sealed interface SidebarNode {
        record RepoNode(Repository repository) implements SidebarNode { }
        record SessionNode(ManagedAgentSession session, Repository repository) implements SidebarNode { }
        record UnopenedWorktreeNode(WorktreeService.Worktree worktree, Repository repository)
                implements SidebarNode { }
        record StaleWorktreesNode(List<WorktreeService.Worktree> worktrees, Repository repository)
                implements SidebarNode { }
    }

    public RepositorySidebar(RepositoryManager repositoryManager, GitStatusService gitStatusService,
                              WorktreeService worktreeService, SessionManager sessionManager,
                              WorkspaceNavigator navigator, WorkspaceViewModel viewModel) {
        this.repositoryManager = repositoryManager;
        this.gitStatusService = gitStatusService;
        this.worktreeService = worktreeService;
        this.sessionManager = sessionManager;
        this.navigator = navigator;
        this.viewModel = viewModel;

        getStyleClass().add("sidebar");

        // -- Header: add-repository menu + filter field ---------------------
        MenuItem openFromDisk = new MenuItem("Open from disk…");
        openFromDisk.setOnAction(e -> onAddRepositoryFromDisk());
        MenuItem cloneFromGitHub = new MenuItem("Clone from GitHub…");
        cloneFromGitHub.setOnAction(e -> onCloneFromGitHub.run());
        MenuItem addRemote = new MenuItem("Add remote repository…");
        addRemote.setOnAction(e -> onAddRemote.run());
        MenuButton addButton = new MenuButton("＋  Add repository", null, openFromDisk, cloneFromGitHub, addRemote);
        addButton.getStyleClass().add("add-repo-button");
        addButton.setMaxWidth(Double.MAX_VALUE);

        filterField.getStyleClass().add("filter-field");
        filterField.setPromptText("⌕  Filter repos & worktrees…");
        filterDebounce.setOnFinished(e -> rebuildTree());
        filterField.textProperty().addListener((obs, oldText, newText) -> filterDebounce.playFromStart());

        VBox header = new VBox(addButton, filterField);
        header.getStyleClass().add("sidebar-header");

        // -- Tree -----------------------------------------------------------
        tree.getStyleClass().add("repo-tree");
        tree.setShowRoot(false);
        tree.setCellFactory(view -> new SidebarTreeCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        // Keyboard activation (arrows already navigate, ←/→ already
        // collapse/expand via TreeView's built-in behavior): Enter/Space
        // performs the row's primary click action.
        tree.setOnKeyPressed(event -> {
            if (event.getCode() != KeyCode.ENTER && event.getCode() != KeyCode.SPACE) {
                return;
            }
            TreeItem<SidebarNode> selected = tree.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() != null) {
                activateNode(selected);
                event.consume();
            }
        });

        // -- Footer ---------------------------------------------------------
        footerDot.getStyleClass().addAll("status-dot", "dot-5");
        HBox footer = new HBox(footerDot, footerLabel);
        footer.getStyleClass().add("sidebar-footer");

        getChildren().addAll(header, tree, footer);

        // Keep the displayed list in sync with EVERY repository mutation,
        // not just the ones initiated by this sidebar's own handlers. The
        // listener may fire on a background thread.
        repositoryManager.addChangeListener(() -> Platform.runLater(this::onRepositoriesChanged));

        // Render from the model: rows update in place; only structural
        // changes rebuild the tree (coalesced), and a tab switch touches
        // nothing but the active rows and the selection.
        viewModel.addListener(new WorkspaceViewModel.Listener() {
            @Override
            public void structureChanged() {
                maybeRefreshStatuses();
                pruneRowCaches();
                requestRebuild();
            }

            @Override
            public void sessionRowChanged(ManagedSessionId sessionId) {
                maybeRefreshStatuses();
                updateSessionRow(sessionId);
            }

            @Override
            public void repoChanged(RepositoryId repositoryId) {
                updateRepoRow(repositoryId);
                updateFooter();
            }

            @Override
            public void worktreeRowChanged(Path worktreeRoot) {
                updateWorktreeRow(worktreeRoot);
            }

            @Override
            public void activeSessionChanged(Optional<ManagedSessionId> previous,
                                             Optional<ManagedSessionId> current) {
                previous.ifPresent(RepositorySidebar.this::updateSessionRow);
                current.ifPresent(RepositorySidebar.this::updateSessionRow);
                syncActiveSelection();
            }
        });

        // A collapsed sidebar (⌘0) is detached from the scene; reveal the
        // active session's row once it is re-attached.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                syncActiveSelection();
            }
        });

        rebuildTree();
        // The constructor sweep covers the seeded snapshot; remember it so
        // the first model event does not immediately re-fetch everything.
        statusRefreshedFor = viewModel.sessions();
        refreshAllStatuses();

        // Remote repos have no local file events and no user shell touching
        // them; poll every 30s so indicators stay live and an unreachable
        // entry recovers on its own (spec: Status polling). Local repos keep
        // event-driven refresh only.
        Timeline remotePoll = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            for (Repository repository : repositoryManager.repositories()) {
                if (repository.isRemote()) {
                    refreshStatus(repository);
                }
            }
        }));
        remotePoll.setCycleCount(Timeline.INDEFINITE);
        remotePoll.play();
    }

    /** Wired by the application shell to open the Clone-from-GitHub modal (design section 7). */
    public void setOnCloneFromGitHub(Runnable handler) {
        this.onCloneFromGitHub = handler == null ? () -> { } : handler;
    }

    /** Wired by the application shell to open the Add-remote-repository modal (spec: SSH remote repositories). */
    public void setOnAddRemote(Runnable handler) {
        this.onAddRemote = handler == null ? () -> { } : handler;
    }

    /** Wired by the application shell to open the create-worktree modal (worktree handoff, section B). */
    public void setOnNewWorktree(Consumer<Repository> handler) {
        this.onNewWorktree = handler == null ? repository -> { } : handler;
    }

    /**
     * The keyboard counterpart of each row's primary click (Enter/Space on
     * the selected row): toggles a repository open/closed, resumes a
     * session (which also hands the keyboard to its terminal), or shows an
     * unopened worktree's start pane.
     */
    private void activateNode(TreeItem<SidebarNode> item) {
        switch (item.getValue()) {
            case SidebarNode.RepoNode repoNode -> item.setExpanded(!item.isExpanded());
            case SidebarNode.SessionNode sessionNode ->
                    viewModel.sessionById(sessionNode.session().id()).ifPresent(navigator::resumeSession);
            case SidebarNode.UnopenedWorktreeNode worktreeNode ->
                    navigator.showUnopenedWorktree(worktreeNode.repository(), worktreeNode.worktree());
            case SidebarNode.StaleWorktreesNode staleNode -> {
                RepositoryId repoId = staleNode.repository().id();
                if (!staleBucketExpanded.add(repoId)) {
                    staleBucketExpanded.remove(repoId);
                }
                requestRebuild();
            }
        }
    }

    /** Focuses the filter field (⌘F). */
    public void focusFilter() {
        filterField.requestFocus();
        filterField.selectAll();
    }

    /**
     * Re-fetches repo AND worktree statuses when the event was driven by an
     * actual session change (fetch-once caching left branch tags and dirty
     * dots permanently stale; see {@link #statusRefreshedFor}). The fetches
     * are async; each completion writes into the model, which re-renders
     * exactly the affected rows.
     */
    private void maybeRefreshStatuses() {
        if (viewModel.sessions() != statusRefreshedFor) {
            statusRefreshedFor = viewModel.sessions();
            refreshAllStatuses();
        }
    }

    /**
     * Schedules one {@link #rebuildTree()} on the FX thread, coalescing
     * bursts (e.g. one git-status completion per worktree) into a single
     * rebuild. Safe to call from any thread.
     */
    private void requestRebuild() {
        if (rebuildPending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                rebuildPending.set(false);
                rebuildTree();
            });
        }
    }

    // ---- Tree building ------------------------------------------------------

    private static List<Repository> sorted(List<Repository> source) {
        return source.stream()
                .sorted(Comparator.comparing(Repository::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Wrapping index of the next live session; {@code -1} when there are none. */
    static int nextLiveIndex(int count, int current, int direction) {
        if (count == 0) {
            return -1;
        }
        if (current < 0) {
            return direction > 0 ? 0 : count - 1;
        }
        return ((current + direction) % count + count) % count;
    }

    /**
     * Moves selection to the next/previous running session (top-to-bottom across
     * repos, wrapping) and opens it. Skips idle sessions, worktrees, and buckets.
     */
    public void focusAdjacentLiveSession(int direction) {
        List<ManagedAgentSession> live = new ArrayList<>();
        for (Repository repository : sorted(repositoryManager.repositories())) {
            SidebarChildren classified = childrenOf(repository);
            if (classified != null) {
                live.addAll(classified.liveSessions());
            }
        }
        if (live.isEmpty()) {
            return;
        }
        ManagedSessionId selectedId = selectedSessionId();
        int current = -1;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).id().equals(selectedId)) {
                current = i;
                break;
            }
        }
        ManagedAgentSession target = live.get(nextLiveIndex(live.size(), current, direction));
        // Same entry point as a row click; opening the session drives selection,
        // and syncActiveSelection() then expands the owning repo and scrolls the
        // row into view.
        navigator.resumeSession(target);
    }

    private ManagedSessionId selectedSessionId() {
        TreeItem<SidebarNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() instanceof SidebarNode.SessionNode sessionNode) {
            return sessionNode.session().id();
        }
        return null;
    }

    private void onRepositoriesChanged() {
        for (Repository repository : repositoryManager.repositories()) {
            if (viewModel.repoStatus(repository.id()).isEmpty()
                    && viewModel.repoStatusFailure(repository.id()).isEmpty()) {
                refreshStatus(repository);
            }
        }
        rebuildTree();
    }

    private void rebuildTree() {
        String query = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);

        List<Repository> repositories = sorted(repositoryManager.repositories());
        List<TreeItem<SidebarNode>> repoItems = new ArrayList<>();

        for (Repository repository : repositories) {
            List<SidebarNode> children = childNodesFor(repository);
            // The filter matches the repo itself (name/branch) OR any of
            // its worktree/session rows; a repo matched only through its
            // children narrows to exactly the matching rows.
            if (!query.isEmpty() && !matchesRepo(repository, query)) {
                children = children.stream().filter(child -> matchesNode(child, query)).toList();
                if (children.isEmpty()) {
                    continue;
                }
            }
            TreeItem<SidebarNode> repoItem = new TreeItem<>(new SidebarNode.RepoNode(repository));
            for (SidebarNode child : children) {
                repoItem.getChildren().add(new TreeItem<>(child));
            }
            repoItem.setExpanded(!collapsed.contains(repository.id()));
            repoItem.expandedProperty().addListener((obs, was, is) -> {
                if (is) {
                    collapsed.remove(repository.id());
                } else {
                    collapsed.add(repository.id());
                }
                // Re-render the header so the ▶ caret tracks EVERY expansion
                // change -- keyboard toggles (Enter, ←/→) included, not just
                // the row's own mouse handler.
                updateRepoRow(repository.id());
            });
            repoItems.add(repoItem);
        }

        treeRoot.getChildren().setAll(repoItems);

        updateFooter();
        syncActiveSelection();
    }

    /**
     * Footer status line (worktree handoff): N running · M worktrees · K
     * unopened, computed from the model across ALL repositories (the filter
     * narrows the tree, not the totals). Until a repo's discovery has run,
     * fall back to the session-derived worktree count so the line never
     * reads "0".
     */
    private void updateFooter() {
        int runningTotal = 0;
        for (ManagedAgentSession session : viewModel.sessions()) {
            if (session.status() == SessionStatus.RUNNING || session.status() == SessionStatus.STARTING) {
                runningTotal++;
            }
        }
        int worktreeTotal = 0;
        int unopenedTotal = 0;
        for (Repository repository : repositoryManager.repositories()) {
            SidebarChildren classified = childrenOf(repository);
            if (classified == null) {
                continue;
            }
            worktreeTotal += classified.worktreeCount() + classified.staleCount();
            unopenedTotal += (int) classified.openWorktrees().stream().filter(w -> !w.mainCheckout()).count()
                    + classified.staleCount();
        }
        if (!viewModel.anyWorktreesDiscovered()) {
            worktreeTotal = (int) viewModel.sessions().stream()
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

    // ---- In-place row updates (model-event driven) --------------------------

    /**
     * Re-renders one session's row by swapping a fresh node record into its
     * {@link TreeItem} -- the cell listens to the item's value property, so
     * exactly that row rebuilds; siblings, expansion, scroll position, and
     * the filter are untouched.
     */
    private void updateSessionRow(ManagedSessionId sessionId) {
        for (TreeItem<SidebarNode> repoItem : treeRoot.getChildren()) {
            for (TreeItem<SidebarNode> child : repoItem.getChildren()) {
                if (child.getValue() instanceof SidebarNode.SessionNode sessionNode
                        && sessionNode.session().id().equals(sessionId)) {
                    viewModel.sessionById(sessionId).ifPresent(current -> child.setValue(
                            new SidebarNode.SessionNode(current, sessionNode.repository())));
                    return;
                }
            }
        }
    }

    /**
     * Re-renders a repository's header row plus its main-checkout session
     * rows (their branch tag/dirty dot read the repo's status, and the
     * header aggregates session count/running dot/meta line).
     */
    private void updateRepoRow(RepositoryId repositoryId) {
        for (TreeItem<SidebarNode> repoItem : treeRoot.getChildren()) {
            if (!(repoItem.getValue() instanceof SidebarNode.RepoNode repoNode)
                    || !repoNode.repository().id().equals(repositoryId)) {
                continue;
            }
            repoItem.setValue(new SidebarNode.RepoNode(repoNode.repository()));
            for (TreeItem<SidebarNode> child : repoItem.getChildren()) {
                if (child.getValue() instanceof SidebarNode.SessionNode sessionNode
                        && sessionNode.session().worktreeRoot().isEmpty()) {
                    child.setValue(new SidebarNode.SessionNode(
                            viewModel.sessionById(sessionNode.session().id()).orElse(sessionNode.session()),
                            sessionNode.repository()));
                }
            }
            return;
        }
    }

    /** Drops cached menus/tooltips whose row no longer exists (deleted sessions, removed repos/worktrees). */
    private void pruneRowCaches() {
        Set<ManagedSessionId> sessionIds = new HashSet<>();
        for (ManagedAgentSession session : viewModel.sessions()) {
            sessionIds.add(session.id());
        }
        sessionMenus.keySet().retainAll(sessionIds);
        sessionTooltips.keySet().retainAll(sessionIds);

        Set<RepositoryId> repoIds = new HashSet<>();
        Set<Path> worktreePaths = new HashSet<>();
        for (Repository repository : repositoryManager.repositories()) {
            repoIds.add(repository.id());
            viewModel.worktrees(repository.id()).ifPresent(worktrees -> {
                for (WorktreeService.Worktree worktree : worktrees) {
                    worktreePaths.add(worktree.path());
                }
            });
        }
        repoMenus.keySet().retainAll(repoIds);
        newSessionMenus.keySet().retainAll(repoIds);
        unopenedTooltips.keySet().retainAll(worktreePaths);
        collapsed.retainAll(repoIds);
        staleBucketExpanded.retainAll(repoIds);
    }

    /** Re-renders the one row backed by {@code worktreeRoot} (a worktree session row or an unopened row). */
    private void updateWorktreeRow(Path worktreeRoot) {
        for (TreeItem<SidebarNode> repoItem : treeRoot.getChildren()) {
            for (TreeItem<SidebarNode> child : repoItem.getChildren()) {
                switch (child.getValue()) {
                    case SidebarNode.SessionNode sessionNode -> {
                        if (sessionNode.session().worktreeRoot()
                                .map(worktreeRoot::equals).orElse(false)) {
                            child.setValue(new SidebarNode.SessionNode(
                                    viewModel.sessionById(sessionNode.session().id())
                                            .orElse(sessionNode.session()),
                                    sessionNode.repository()));
                            return;
                        }
                    }
                    case SidebarNode.UnopenedWorktreeNode worktreeNode -> {
                        if (worktreeNode.worktree().path().equals(worktreeRoot)) {
                            child.setValue(new SidebarNode.UnopenedWorktreeNode(
                                    worktreeNode.worktree(), worktreeNode.repository()));
                            return;
                        }
                    }
                    case null, default -> { }
                }
            }
        }
    }

    /**
     * Mirrors the currently selected session tab into the tree: selects the
     * matching row and -- only while the sidebar is actually attached to
     * the scene, so a collapsed sidebar (⌘0) is never disturbed -- expands
     * its repository node and scrolls the row into view. The scroll fires
     * once per active-session change, not on every status-refresh rebuild.
     */
    private void syncActiveSelection() {
        ManagedSessionId active = viewModel.activeSession().orElse(null);
        if (active == null) {
            lastRevealedSession = null;
            tree.getSelectionModel().clearSelection();
            return;
        }
        TreeItem<SidebarNode> match = null;
        for (TreeItem<SidebarNode> repoItem : treeRoot.getChildren()) {
            for (TreeItem<SidebarNode> child : repoItem.getChildren()) {
                if (child.getValue() instanceof SidebarNode.SessionNode sessionNode
                        && sessionNode.session().id().equals(active)) {
                    match = child;
                    break;
                }
            }
            if (match != null) {
                break;
            }
        }
        if (match == null) {
            tree.getSelectionModel().clearSelection();
            return;
        }
        boolean sidebarShowing = getScene() != null;
        boolean activeChanged = !active.equals(lastRevealedSession);
        if (sidebarShowing && activeChanged) {
            match.getParent().setExpanded(true);
        }
        // Select only while the row is actually visible: TreeView's
        // selection model force-expands collapsed ancestors of a hidden
        // selection target, which would re-open a repository the user just
        // collapsed on every subsequent rebuild. Visibility is checked via
        // the parent's expanded state, NOT getRow() -- getRow() reports an
        // index for rows under a collapsed parent too (it counts as if
        // everything were expanded), so it cannot serve as this guard.
        if (match.getParent().isExpanded()) {
            if (tree.getSelectionModel().getSelectedItem() != match) {
                tree.getSelectionModel().select(match);
            }
        } else {
            tree.getSelectionModel().clearSelection();
        }
        if (sidebarShowing && activeChanged) {
            int row = tree.getRow(match);
            if (row >= 0) {
                tree.scrollTo(row);
            }
            lastRevealedSession = active;
        }
    }

    /**
     * The banded children of one repository row: live sessions, then idle
     * sessions, then open (non-stale) worktrees, then a single collapsed
     * stale-worktrees bucket (if any) -- ordering and classification
     * delegated to {@link SidebarChildren}.
     */
    private List<SidebarNode> childNodesFor(Repository repository) {
        SidebarChildren classified = childrenOf(repository);
        if (classified == null) {
            // Discovery hasn't run yet: kick it off and show session-derived rows meanwhile.
            refreshWorktrees(repository, false);
            return new ArrayList<>(sessionsFor(repository).stream()
                    .map(session -> (SidebarNode) new SidebarNode.SessionNode(session, repository))
                    .toList());
        }
        List<SidebarNode> children = new ArrayList<>();
        for (ManagedAgentSession session : classified.orderedSessions()) {
            children.add(new SidebarNode.SessionNode(session, repository));
        }
        for (WorktreeService.Worktree worktree : classified.openWorktrees()) {
            children.add(new SidebarNode.UnopenedWorktreeNode(worktree, repository));
        }
        if (!classified.staleWorktrees().isEmpty()) {
            children.add(new SidebarNode.StaleWorktreesNode(classified.staleWorktrees(), repository));
        }
        return children;
    }

    /** Classifies a repo's worktrees + sessions, or {@code null} if discovery hasn't run yet. */
    private SidebarChildren childrenOf(Repository repository) {
        List<WorktreeService.Worktree> worktrees = viewModel.worktrees(repository.id()).orElse(null);
        if (worktrees == null) {
            return null;
        }
        return SidebarChildren.classify(worktrees, sessionsFor(repository), viewModel::activityOf);
    }

    private boolean matchesRepo(Repository repository, String query) {
        if (repository.displayName().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        GitStatus status = viewModel.repoStatus(repository.id()).orElse(null);
        return status != null && UiFormats.branchText(status).toLowerCase(Locale.ROOT).contains(query);
    }

    /** Whether one worktree/session row matches the filter: session name, branch, or worktree path. */
    private boolean matchesNode(SidebarNode node, String query) {
        return switch (node) {
            case SidebarNode.RepoNode repoNode -> false;
            case SidebarNode.SessionNode sessionNode -> {
                StringBuilder text = new StringBuilder(sessionNode.session().displayName());
                sessionNode.session().worktreeRoot().ifPresent(root -> {
                    text.append(' ').append(root);
                    GitStatus status = viewModel.worktreeStatus(root).orElse(null);
                    if (status != null) {
                        text.append(' ').append(UiFormats.branchText(status));
                    }
                });
                yield text.toString().toLowerCase(Locale.ROOT).contains(query);
            }
            case SidebarNode.UnopenedWorktreeNode worktreeNode -> {
                String text = worktreeNode.worktree().branch().orElse("")
                        + " " + worktreeNode.worktree().path();
                yield text.toLowerCase(Locale.ROOT).contains(query);
            }
            case SidebarNode.StaleWorktreesNode staleNode -> staleNode.worktrees().stream().anyMatch(worktree -> {
                String text = worktree.branch().orElse("") + " " + worktree.path();
                return text.toLowerCase(Locale.ROOT).contains(query);
            });
        };
    }

    private List<ManagedAgentSession> sessionsFor(Repository repository) {
        return viewModel.sessions().stream()
                .filter(session -> session.repositoryId().equals(repository.id()))
                .sorted(Comparator.comparing(ManagedAgentSession::displayName, String.CASE_INSENSITIVE_ORDER))
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
        if (repository.isRemote()) {
            return;
        }
        if (!scanning.add(repository.id())) {
            return;
        }
        List<WorktreeService.Worktree> previous = viewModel.worktrees(repository.id()).orElse(null);
        worktreeService.list(repository.root())
                .whenComplete((worktrees, failure) -> Platform.runLater(() -> {
                    scanning.remove(repository.id());
                    if (failure != null) {
                        LOG.log(Level.DEBUG, "Worktree discovery failed for " + repository.root(), failure);
                        if (userInitiated) {
                            UiErrors.show("Could not rescan worktrees", failure);
                        }
                        // The ⟳ spinner is bound to the scanning set; drop
                        // its row back to the idle glyph.
                        updateRepoRow(repository.id());
                        return;
                    }
                    // Highlights are recorded BEFORE the model write so the
                    // structure rebuild it triggers already sees them.
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
                                updateRepoRow(repository.id());
                            });
                            clearNote.play();
                        } else {
                            recentlyDiscovered.addAll(fresh);
                            PauseTransition clearHighlight = new PauseTransition(DISCOVERY_HIGHLIGHT);
                            clearHighlight.setOnFinished(e -> {
                                fresh.forEach(recentlyDiscovered::remove);
                                fresh.forEach(RepositorySidebar.this::updateWorktreeRow);
                            });
                            clearHighlight.play();
                        }
                    }
                    viewModel.setWorktrees(repository.id(), worktrees);
                    for (WorktreeService.Worktree worktree : worktrees) {
                        if (!worktree.mainCheckout() && viewModel.worktreeStatus(worktree.path()).isEmpty()) {
                            refreshWorktreeStatus(worktree.path());
                        }
                    }
                    // An unchanged list emits no model event; the rescan
                    // note / spinner stop still need the header re-rendered.
                    updateRepoRow(repository.id());
                }));
    }

    /**
     * One-click 🗑 of an unopened worktree: {@code git worktree remove} only.
     * The branch is kept -- see {@link #deletableBranchOf}, which is always
     * empty for a row reachable from here.
     */
    private void onDeleteUnopenedWorktree(Repository repository, WorktreeService.Worktree worktree) {
        worktreeService.remove(repository.root(), worktree.path(), deletableBranchOf(worktree))
                .whenComplete((v, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        if (UiErrors.unwrap(failure) instanceof WorktreeNotCleanException) {
                            confirmForcedWorktreeDelete(repository, worktree);
                        } else {
                            UiErrors.show("Could not delete worktree", failure);
                        }
                        return;
                    }
                    viewModel.removeWorktreeStatus(worktree.path());
                    refreshWorktrees(repository, false);
                }));
    }

    /**
     * The plain remove was refused because the worktree holds uncommitted
     * work (git: "contains modified or untracked files"): ask before
     * discarding it, then retry with {@code --force}.
     */
    private void confirmForcedWorktreeDelete(Repository repository, WorktreeService.Worktree worktree) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.setTitle("Delete worktree");
        confirm.setHeaderText("\"" + worktree.branch().orElse(worktree.path().getFileName().toString())
                + "\" has uncommitted changes");
        confirm.setContentText("The worktree at " + worktree.path()
                + " contains modified or untracked files. Deleting it will discard them permanently. "
                + "Delete anyway?");
        confirm.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button ->
                worktreeService.removeForced(repository.root(), worktree.path(), deletableBranchOf(worktree))
                        .whenComplete((v, failure) -> Platform.runLater(() -> {
                            if (failure != null) {
                                UiErrors.show("Could not delete worktree", failure);
                                return;
                            }
                            viewModel.removeWorktreeStatus(worktree.path());
                            refreshWorktrees(repository, false);
                        })));
    }

    /**
     * The branch to delete along with {@code worktree}, if any. A worktree
     * discovered on disk, or one opened on a branch that already existed,
     * keeps its branch: {@code git branch -D} is unrecoverable for unpushed
     * commits, and drydock only destroys what it created.
     *
     * <p>At both sidebar call sites this is in fact <em>always</em> empty:
     * {@code childNodesFor} only mints an {@code UnopenedWorktreeNode} when
     * no session's {@code worktreeRoot} matches the path, while
     * {@code mayDeleteBranchOf} requires exactly such a session. The guard is
     * kept deliberately rather than inlined to {@code Optional.empty()}: it
     * is the ownership rule itself, and it must keep holding if a future row
     * source ever routes a session-backed worktree through here.</p>
     */
    private Optional<String> deletableBranchOf(WorktreeService.Worktree worktree) {
        return sessionManager.mayDeleteBranchOf(worktree.path()) ? worktree.branch() : Optional.empty();
    }

    /**
     * Batch-remove the bucket: one confirm, remove the cleanly-removable ones,
     * and report (never silently force) any that hold uncommitted work.
     */
    private void cleanStaleWorktrees(Repository repository, List<WorktreeService.Worktree> worktrees) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.setTitle("Clean stale worktrees");
        confirm.setHeaderText("Remove " + worktrees.size() + " stale worktree"
                + (worktrees.size() == 1 ? "" : "s") + "?");
        confirm.setContentText("Worktrees with uncommitted changes are skipped and left in place.");
        if (confirm.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> removals = new ArrayList<>();
        List<Path> skippedPaths = Collections.synchronizedList(new ArrayList<>());
        for (WorktreeService.Worktree worktree : worktrees) {
            removals.add(worktreeService.remove(repository.root(), worktree.path(), deletableBranchOf(worktree))
                    .handle((v, failure) -> {
                        Platform.runLater(() -> {
                            if (failure != null) {
                                if (UiErrors.unwrap(failure) instanceof WorktreeNotCleanException) {
                                    skippedPaths.add(worktree.path()); // dirty: report, do not force
                                } else {
                                    UiErrors.show("Could not remove worktree", failure);
                                }
                            } else {
                                viewModel.removeWorktreeStatus(worktree.path());
                            }
                        });
                        return null;
                    }));
        }
        CompletableFuture
                .allOf(removals.toArray(CompletableFuture[]::new))
                .whenComplete((v, ignored) -> Platform.runLater(() -> {
                    refreshWorktrees(repository, false);
                    if (!skippedPaths.isEmpty()) {
                        // Transient status note, cleared after 2.4s -- mirrors the
                        // "Already up to date" rescan note.
                        rescanNotes.put(repository.id(), "kept " + skippedPaths.size()
                                + " with uncommitted changes");
                        updateRepoRow(repository.id());
                        PauseTransition clearNote = new PauseTransition(Duration.seconds(2.4));
                        clearNote.setOnFinished(e -> {
                            rescanNotes.remove(repository.id());
                            updateRepoRow(repository.id());
                        });
                        clearNote.play();
                    }
                }));
    }

    // ---- Git status ---------------------------------------------------------

    private void refreshAllStatuses() {
        for (Repository repository : repositoryManager.repositories()) {
            refreshStatus(repository);
        }
        refreshWorktreeStatuses();
    }

    /** Fetches per-worktree status for every worktree session (branch tag + dirty dot per worktree checkout). */
    private void refreshWorktreeStatuses() {
        for (ManagedAgentSession session : viewModel.sessions()) {
            session.worktreeRoot().ifPresent(this::refreshWorktreeStatus);
        }
    }

    private void refreshWorktreeStatus(Path worktreeRoot) {
        gitStatusService.getStatus(worktreeRoot)
                .whenComplete((status, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        viewModel.removeWorktreeStatus(worktreeRoot);
                        LOG.log(Level.DEBUG, "Git status refresh failed for worktree " + worktreeRoot, failure);
                    } else {
                        viewModel.setWorktreeStatus(worktreeRoot, status);
                    }
                }));
    }

    private void refreshStatus(Repository repository) {
        gitStatusService.getStatus(GitTarget.of(repository))
                .whenComplete((status, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        viewModel.setRepoStatusFailure(repository.id(), UiErrors.unwrap(failure));
                        LOG.log(Level.DEBUG, "Git status refresh failed for " + repository.root(), failure);
                    } else {
                        viewModel.setRepoStatus(repository.id(), status);
                    }
                }));
    }

    private String branchTextFor(Repository repository) {
        GitStatus status = viewModel.repoStatus(repository.id()).orElse(null);
        if (status != null) {
            return UiFormats.branchText(status) + (status.dirty() ? " *" : "");
        }
        if (viewModel.repoStatusFailure(repository.id()).orElse(null) instanceof SshUnreachableException) {
            return "(unreachable)";
        }
        return viewModel.repoStatusFailure(repository.id()).isPresent() ? "(status unavailable)" : "…";
    }

    // ---- Actions ------------------------------------------------------------

    private void onAddRepositoryFromDisk() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add repository");
        Window ownerWindow = getScene() == null ? null : getScene().getWindow();
        File chosen = chooser.showDialog(ownerWindow);
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
        String location = repository.isRemote()
                ? repository.remote().host() + ":" + repository.remote().remotePath()
                : repository.root().toString();
        confirm.setContentText("This only removes it from Drydock's list. "
                + "Nothing at " + location + " is touched or deleted.");
        confirm.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> {
            repositoryManager.removeRepository(repository.id());
            // The manager's change listener rebuilds the repo list; the
            // model forgets the removed repo's status/discovery data.
            viewModel.removeRepository(repository.id());
        });
    }

    private void onDeleteSession(ManagedAgentSession session) {
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
                    navigator.noteSessionDeleted(session.id());
                })));
    }

    private void onOpenInFinder(Repository repository) {
        launchExternally("Could not open in Finder", () -> FinderLauncher.reveal(repository.root()));
    }

    private void onOpenInEditor(Repository repository) {
        launchExternally("Could not open in external editor", () -> editorLauncher.open(repository.root()));
    }

    /** A process launch that can fail with an {@link IOException} (Finder / editor / reveal). */
    private interface ExternalLaunch {
        void run() throws IOException;
    }

    /**
     * Runs a Finder/editor launch off the FX thread (process spawns block,
     * per AGENTS.md; the launched app appearing is the visible feedback)
     * and reports a failure back on it as an error alert.
     */
    private void launchExternally(String failureTitle, ExternalLaunch launch) {
        Thread.ofVirtual().start(() -> {
            try {
                launch.run();
            } catch (IOException e) {
                Platform.runLater(() -> UiErrors.show(failureTitle, e));
            }
        });
    }

    // ---- Cells --------------------------------------------------------------

    /** Abbreviates the user's home directory to {@code ~} for compact worktree paths. */
    private static String shortPath(Path path) {
        String home = System.getProperty("user.home");
        String text = path.toString();
        return home != null && text.startsWith(home) ? "~" + text.substring(home.length()) : text;
    }

    private final class SidebarTreeCell extends TreeCell<SidebarNode> {

        /**
         * Cells report a tiny preferred width so the virtual flow sizes
         * every cell to the viewport width: long branch/session names then
         * ellipsize instead of widening the tree. Without this the
         * overflowing cells grew a horizontal scrollbar that pushed the
         * right-aligned action buttons out of view, and broke single-click
         * repo expand/collapse: the click-triggered auto horizontal scroll
         * shifted the row out from under the cursor between press and
         * release, so the row's CLICKED handler never fired.
         */
        @Override
        protected double computePrefWidth(double height) {
            return 1;
        }

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
                    setContextMenu(repoMenu(repoNode.repository()));
                }
                case SidebarNode.SessionNode sessionNode -> {
                    setGraphic(buildSessionRow(sessionNode.session(), sessionNode.repository()));
                    setContextMenu(sessionMenu(sessionNode.session().id()));
                }
                case SidebarNode.UnopenedWorktreeNode worktreeNode -> {
                    setGraphic(buildUnopenedRow(worktreeNode.worktree(), worktreeNode.repository()));
                    setContextMenu(null);
                }
                case SidebarNode.StaleWorktreesNode staleNode -> {
                    setGraphic(buildStaleRow(staleNode.worktrees(), staleNode.repository()));
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

            // When a transient rescan note is present it owns the whole line
            // (branch text = note, no counts).
            String note = rescanNotes.get(repository.id());
            Label branch = new Label(note != null ? note : "⎇ " + branchTextFor(repository));
            branch.getStyleClass().add("repo-branch");
            branch.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
            HBox.setHgrow(branch, Priority.ALWAYS);
            branch.setMaxWidth(Double.MAX_VALUE);
            Throwable failure = viewModel.repoStatusFailure(repository.id()).orElse(null);
            if (failure != null) {
                branch.setTooltip(new Tooltip(String.valueOf(failure.getMessage())));
            } else if (repository.isRemote() && viewModel.repoStatus(repository.id()).isPresent()) {
                branch.setTooltip(new Tooltip(
                        "ahead/behind is as of the last fetch on " + repository.remote().host()));
            }

            Label counts = new Label(repoCountsText(repository));
            counts.getStyleClass().add("repo-count-meta");
            counts.setMinWidth(Region.USE_PREF_SIZE);

            List<ManagedAgentSession> sessions = sessionsFor(repository);
            boolean anyRunning = sessions.stream().anyMatch(s -> SessionStatusStyles.isRunning(s.status()));
            HBox branchRow = new HBox(6, branch, counts);
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
                // The spin is INDEFINITE and this row is discarded on every
                // rebuild (including the one that ends the rescan) -- stop
                // it once the button leaves the scene or it animates a
                // detached node forever.
                rescan.sceneProperty().addListener((obs, oldScene, newScene) -> {
                    if (newScene == null) {
                        spin.stop();
                    }
                });
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
            ContextMenu newMenu = newSessionMenu(repository);
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

        /** Just the counts fragment: {@code · 3 wt · 1 stale}, or "" before discovery / when a note is showing. */
        private String repoCountsText(Repository repository) {
            if (rescanNotes.get(repository.id()) != null) {
                return "";
            }
            SidebarChildren classified = childrenOf(repository);
            if (classified == null) {
                return "";
            }
            StringBuilder counts = new StringBuilder(" · ").append(classified.worktreeCount()).append(" wt");
            if (classified.staleCount() > 0) {
                counts.append(" · ").append(classified.staleCount()).append(" stale");
            }
            return counts.toString();
        }

        private HBox buildSessionRow(ManagedAgentSession session, Repository repository) {
            boolean live = SessionStatusStyles.isRunning(session.status());
            Region dot = SessionStatusStyles.createDot(8, session.status(), live);
            StackPane statusCol = new StackPane(dot);
            statusCol.getStyleClass().add("child-row-status");

            Label name = new Label(session.displayName());
            name.getStyleClass().add("session-name");

            // Branch tag (worktree handoff "Sidebar session rows"): ◫ accent
            // for a worktree checkout, ⎇ dim for the current checkout.
            boolean isWorktree = session.worktreeRoot().isPresent();
            GitStatus checkoutStatus = session.worktreeRoot()
                    .map(root -> viewModel.worktreeStatus(root).orElse(null))
                    .orElseGet(() -> viewModel.repoStatus(repository.id()).orElse(null));
            String branch = checkoutStatus != null ? UiFormats.branchText(checkoutStatus) : "…";
            Label branchTag = new Label((isWorktree ? "◫ " : "⎇ ") + branch);
            branchTag.getStyleClass().add(isWorktree ? "branch-tag-worktree" : "branch-tag");

            HBox nameRow = new HBox(6, name, branchTag);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            if (checkoutStatus != null && checkoutStatus.dirty()) {
                Region dirtyDot = new Region();
                dirtyDot.getStyleClass().add("dirty-dot");
                nameRow.getChildren().add(dirtyDot);
            }

            Label meta = new Label(UiFormats.relativeTime(session.lastOpenedAt()));
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

            Button open = quickAction("↗", "Open", false, () -> navigator.resumeSession(session));
            Button stop = quickAction("■", "Stop process", true, () -> navigator.closeSession(session.id()));
            stop.setDisable(!SessionStatusStyles.isRunning(session.status()));
            Button delete = quickAction("×", "Delete session", true, () -> onDeleteSession(session));
            HBox actions = new HBox(2, open, stop, delete);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.visibleProperty().bind(hoverProperty());

            HBox row = new HBox(8, statusCol, text, actions);
            if (prChip != null) {
                row.getChildren().add(row.getChildren().indexOf(actions), prChip);
            }
            // A session whose Claude is blocked on a human gets a badge: it
            // is the one state that makes no further progress until the user
            // comes back to it. Cleared by switching to the session.
            SessionActivity activity = viewModel.activityOf(session.id());
            if (activity == SessionActivity.NEEDS_ATTENTION) {
                Label attention = new Label("waiting");
                attention.getStyleClass().add("attention-badge");
                row.getChildren().add(row.getChildren().indexOf(actions), attention);
            }
            // An IDLE session advertises resumability with a ghost Resume
            // pill (worktree handoff: clicking the row resumes it).
            if (!SessionStatusStyles.isRunning(session.status())) {
                Label resumePill = new Label("Resume");
                resumePill.getStyleClass().add("resume-pill");
                row.getChildren().add(row.getChildren().indexOf(actions), resumePill);
            }
            row.getStyleClass().addAll("session-row", "child-row");
            row.setAlignment(Pos.CENTER_LEFT);
            if (viewModel.activeSession().filter(session.id()::equals).isPresent()) {
                row.getStyleClass().add("active");
            }
            Tooltip rowTip = sessionTooltips.computeIfAbsent(session.id(), key -> new Tooltip());
            String workingDirectoryText = session.worktreeRoot().isEmpty() && repository.isRemote()
                    ? repository.remote().host() + ":" + repository.remote().remotePath()
                    : session.workingDirectory().toString();
            rowTip.setText("Status: " + session.status()
                    + (activity == SessionActivity.UNKNOWN ? "" : "\nClaude: " + activityLabel(activity))
                    + "\nLast opened: " + session.lastOpenedAt()
                    + "\nWorking directory: " + workingDirectoryText);
            Tooltip.install(row, rowTip);
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    navigator.resumeSession(session);
                    event.consume();
                }
            });
            return row;
        }

        /** Human-facing wording for the tooltip's activity line. */
        private String activityLabel(SessionActivity activity) {
            return switch (activity) {
                case BUSY -> "working";
                case IDLE -> "at the prompt";
                case NEEDS_ATTENTION -> "waiting for you";
                case UNKNOWN -> "unknown";
            };
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
            StackPane statusCol = new StackPane(icon);
            statusCol.getStyleClass().add("child-row-status");

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
                    navigator.promptStartWorktreeSession(repository, worktree);
                    event.consume();
                }
            });

            HBox row = new HBox(8, statusCol, text, startPill);
            if (!worktree.mainCheckout()) {
                Button delete = quickAction("🗑", "Delete worktree & branch", true,
                        () -> onDeleteUnopenedWorktree(repository, worktree));
                delete.getStyleClass().add("worktree-delete-button");
                row.getChildren().add(delete);
            }
            row.getStyleClass().addAll("worktree-unopened-row", "child-row");
            if (recentlyDiscovered.contains(worktree.path())) {
                row.getStyleClass().add("worktree-discovered");
            }
            row.setAlignment(Pos.CENTER_LEFT);
            Tooltip.install(row, unopenedTooltips.computeIfAbsent(worktree.path(),
                    path -> new Tooltip("Discovered via git worktree list\n" + path)));
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    navigator.showUnopenedWorktree(repository, worktree);
                    event.consume();
                }
            });
            return row;
        }

        /**
         * The collapsed stale bucket: {@code ▸ N stale worktrees} + a Clean action.
         * Expands in place (its own {@code staleBucketExpanded} state) to plain dim
         * path rows. Clean removes the cleanly-removable worktrees in one confirm and
         * reports (never force-deletes) those with uncommitted work.
         */
        private VBox buildStaleRow(List<WorktreeService.Worktree> worktrees, Repository repository) {
            boolean expanded = staleBucketExpanded.contains(repository.id());

            Label caret = new Label(expanded ? "▾" : "▸");
            caret.getStyleClass().add("repo-caret");
            StackPane statusCol = new StackPane(caret);
            statusCol.getStyleClass().add("child-row-status");
            Label label = new Label(worktrees.size() + (worktrees.size() == 1
                    ? " stale worktree" : " stale worktrees"));
            label.getStyleClass().add("stale-summary");
            HBox.setHgrow(label, Priority.ALWAYS);

            Button clean = new Button("Clean ↺");
            clean.getStyleClass().add("stale-clean-button");
            clean.setFocusTraversable(false);
            clean.setOnAction(e -> cleanStaleWorktrees(repository, worktrees));

            HBox summary = new HBox(7, statusCol, label, clean);
            summary.getStyleClass().addAll("stale-summary-row", "child-row");
            summary.setAlignment(Pos.CENTER_LEFT);
            summary.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    if (expanded) {
                        staleBucketExpanded.remove(repository.id());
                    } else {
                        staleBucketExpanded.add(repository.id());
                    }
                    requestRebuild();
                    event.consume();
                }
            });

            VBox box = new VBox(summary);
            if (expanded) {
                for (WorktreeService.Worktree worktree : worktrees) {
                    Label path = new Label(shortPath(worktree.path()));
                    path.getStyleClass().add("stale-path");
                    path.setPadding(new Insets(2, 8, 2, 34));
                    box.getChildren().add(path);
                }
            }
            return box;
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

    }

    // ---- Cached per-row context menus ---------------------------------------

    /** Applies {@code action} to the CURRENT version of the session, resolved through the model. */
    private void withLiveSession(ManagedSessionId sessionId, Consumer<ManagedAgentSession> action) {
        viewModel.sessionById(sessionId).ifPresent(action);
    }

    /** One cached menu per session row; handlers re-resolve the session so the cache never acts stale. */
    private ContextMenu sessionMenu(ManagedSessionId sessionId) {
        return sessionMenus.computeIfAbsent(sessionId, id -> {
            MenuItem resume = new MenuItem("Resume");
            resume.setOnAction(e -> withLiveSession(id, navigator::resumeSession));

            MenuItem rename = new MenuItem("Rename…");
            rename.setOnAction(e -> withLiveSession(id, navigator::promptRenameSession));

            MenuItem stop = new MenuItem("Stop process");
            stop.setOnAction(e -> navigator.closeSession(id));

            MenuItem delete = new MenuItem("Delete session");
            delete.setOnAction(e -> withLiveSession(id, this::onDeleteSession));

            MenuItem reveal = new MenuItem("Reveal working directory");
            reveal.setOnAction(e -> withLiveSession(id, session ->
                    launchExternally("Could not reveal working directory",
                            () -> FinderLauncher.reveal(session.workingDirectory()))));

            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(resume, rename, stop, delete, new SeparatorMenuItem(), reveal);
            return menu;
        });
    }

    /** The repo "+" menu (worktree handoff "Creating"): checkout session / new worktree / rescan. */
    private ContextMenu newSessionMenu(Repository repository) {
        return newSessionMenus.computeIfAbsent(repository.id(), id -> {
            MenuItem inCheckout = new MenuItem("❯_  Session on main checkout");
            inCheckout.setOnAction(e -> navigator.openNewSession(repository));
            if (repository.isRemote()) {
                return new ContextMenu(inCheckout);
            }
            MenuItem newWorktree = new MenuItem("◫  New worktree…");
            newWorktree.setOnAction(e -> onNewWorktree.accept(repository));
            MenuItem rescan = new MenuItem("⟳  Rescan worktrees");
            rescan.setOnAction(e -> refreshWorktrees(repository, true));
            return new ContextMenu(inCheckout, newWorktree, rescan);
        });
    }

    private ContextMenu repoMenu(Repository repository) {
        return repoMenus.computeIfAbsent(repository.id(), id -> {
            MenuItem newSession = new MenuItem("New Claude session");
            newSession.setOnAction(e -> navigator.openNewSession(repository));

            MenuItem refresh = new MenuItem("Refresh");
            refresh.setOnAction(e -> {
                refreshStatus(repository);
                refreshWorktrees(repository, true);
            });

            MenuItem remove = new MenuItem("Remove from manager");
            remove.setOnAction(e -> onRemoveRepository(repository));

            ContextMenu menu = new ContextMenu();
            menu.getItems().add(newSession);
            if (!repository.isRemote()) {
                MenuItem newWorktree = new MenuItem("New worktree…");
                newWorktree.setOnAction(e -> onNewWorktree.accept(repository));

                MenuItem rescan = new MenuItem("Rescan worktrees");
                rescan.setOnAction(e -> refreshWorktrees(repository, true));

                menu.getItems().addAll(newWorktree, rescan);
            }
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(refresh);
            if (!repository.isRemote()) {
                MenuItem openFinder = new MenuItem("Open in Finder");
                openFinder.setOnAction(e -> onOpenInFinder(repository));

                MenuItem openEditor = new MenuItem("Open in external editor");
                openEditor.setOnAction(e -> onOpenInEditor(repository));

                menu.getItems().addAll(openFinder, openEditor);
            }
            menu.getItems().addAll(new SeparatorMenuItem(), remove);
            return menu;
        });
    }
}
