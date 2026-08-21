package app.drydock.ui;

import app.drydock.agent.api.AgentRegistry;
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
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import app.drydock.git.GitTarget;
import app.drydock.git.SshUnreachableException;
import app.drydock.git.WorktreeLockedException;
import app.drydock.git.WorktreeNotCleanException;
import app.drydock.git.WorktreeService;
import app.drydock.review.RepositoryPullRequests;
import app.drydock.review.SessionReviewScopes;
import app.drydock.ui.model.SessionFilter;
import app.drydock.ui.model.WorkspaceViewModel;
import java.io.File;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
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
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
    private final RepositoryPullRequests repositoryPullRequests;
    private final SessionManager sessionManager;
    private final WorkspaceNavigator navigator;
    private final WorkspaceViewModel viewModel;
    private final ExternalEditorLauncher editorLauncher = new ExternalEditorLauncher();

    private final TextField filterField = new TextField();
    private SessionFilter filter = SessionFilter.none();
    private final SessionFilterBar filterBar;

    /** Open findings for a worktree checkout, if any -- the per-row ◨n badge. */
    private Function<Path, Optional<Integer>> openFindingsAt = path -> Optional.empty();

    /**
     * The finding counts the tree was last rendered with. A queue refresh
     * that changes no count must not rebuild the tree: rebuild-the-world is
     * a last resort (AGENTS.md), and Review reassembles its queue every time
     * the destination is shown.
     */
    private Map<Path, Integer> renderedFindingCounts = Map.of();
    private final TreeItem<SidebarNode> treeRoot = new TreeItem<>();
    private final TreeView<SidebarNode> tree = new TreeView<>(treeRoot);

    /**
     * The two forms of "nothing to show": {@code emptyState} swaps in for
     * the tree when there is truly nothing left (see {@link #showEmptyState}
     * for why that decision cannot be made from the filter alone), and
     * {@code emptyBanner} sits above the tree when the active-session
     * exemption leaves exactly one row standing.
     */
    private final VBox emptyState;
    private final Label emptyBanner = new Label("Nothing matches your filters");
    private final Label footerLabel = new Label();
    private final Region footerDot = new Region();

    /** Which repository subtrees are expanded; new repositories start expanded. */
    private final Set<RepositoryId> collapsed = new HashSet<>();

    /** The user's collapse set, stashed while a filter forces every repo open. */
    private Set<RepositoryId> collapsedBeforeFilter;

    /**
     * Set in the two places a filter can change (the {@link SessionFilterBar}
     * callback and the {@link #filterDebounce} handler) and consumed at the
     * top of {@link #rebuildTree()}. Force-expansion must fire only on an
     * actual filter change, not on every rebuild, or the disclosure triangle
     * is a dead control for as long as a filter is on.
     */
    private boolean filterChangedSinceLastRebuild;

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "forcehover"
     * verb): the row Node, if any, whose actions strip should render
     * visible without a real hover. {@code null} in production and by
     * default -- see {@link #diagForceHoverRow} and the {@code .or(...)}
     * added to {@code actions.visibleProperty()}'s binding in
     * {@code buildSessionRow}/{@code buildRepoRow}, which this drives.
     */
    private final ObjectProperty<Node> diagForcedHoverRow = new SimpleObjectProperty<>();

    /** Repos whose stale bucket is expanded. Distinct from {@code collapsed} (repo-level). */
    private final Set<RepositoryId> staleBucketExpanded = new HashSet<>();

    /** Repos whose locked-worktree bucket is expanded. */
    private final Set<RepositoryId> lockedBucketExpanded = new HashSet<>();

    /** Repositories with a rescan in flight (spins the ⟳ button, prevents double-scans). */
    private final Set<RepositoryId> scanning = ConcurrentHashMap.newKeySet();

    /** Repos whose pull-request group is expanded. Distinct from {@code collapsed} (repo-level). */
    private final Set<RepositoryId> pullRequestsExpanded = new HashSet<>();
    /** Repositories with a pull-request scan in flight (also spins the ⟳ button, prevents double-scans). */
    private final Set<RepositoryId> scanningPullRequests = ConcurrentHashMap.newKeySet();
    /** Repos with a PR-scan request that arrived mid-scan; re-run once the in-flight one lands (see refreshPullRequests). */
    private final Set<RepositoryId> pendingPullRequestRescan = ConcurrentHashMap.newKeySet();
    /** The worktree list the in-flight PR scan for a repo started with (see refreshPullRequests / shouldQueuePullRequestRescan). */
    private final Map<RepositoryId, List<WorktreeService.Worktree>> pullRequestScanWorktrees = new ConcurrentHashMap<>();
    /** Repos whose worktree list changed while collapsed, so their PR outcome is known stale; rescanned on next expand. */
    private final Set<RepositoryId> pullRequestsStale = ConcurrentHashMap.newKeySet();
    /** Worktree paths discovered by the latest rescan, highlighted one-shot until the timer clears them. */
    private final Set<Path> recentlyDiscovered = new HashSet<>();
    /** Transient per-repo meta note ("Already up to date — no new worktrees") shown briefly after a rescan. */
    private final Map<RepositoryId, String> rescanNotes = new ConcurrentHashMap<>();
    /**
     * Pull requests currently being materialized into a worktree + session,
     * so their row can say so and refuse a second click. Keyed by (repository,
     * number) rather than by the row node because rows are rebuilt from the
     * model constantly and a node identity would not survive that.
     */
    private final PullRequestMaterialization.InFlight materializingPullRequests =
            new PullRequestMaterialization.InFlight();

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
    /** The sidebar's own collapse control; the shell owns the actual folding. */
    private Runnable onToggleSidebar = () -> { };
    private Consumer<Repository> onNewWorktree = repository -> { };

    // -- Per-row cached popups (context menus and tooltips are not part of
    // the scene graph, so one instance can serve every cell that ever
    // renders the row). Menu handlers resolve the LIVE session through the
    // view model, never a captured snapshot, so a cached menu cannot act on
    // stale data. Pruned on structural changes (see pruneRowCaches).
    private final Map<ManagedSessionId, ContextMenu> sessionMenus = new HashMap<>();
    private final Map<ManagedSessionId, Tooltip> sessionTooltips = new HashMap<>();
    private final AgentRegistry agentRegistry;
    private final Map<RepositoryId, ContextMenu> repoMenus = new HashMap<>();
    /** One cached menu per discovered-worktree row, keyed and pruned by checkout path. */
    private final Map<Path, ContextMenu> unopenedMenus = new HashMap<>();
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
        record LockedWorktreesNode(List<WorktreeService.Worktree> worktrees, Repository repository)
                implements SidebarNode { }
        /** The collapsed "PULL REQUESTS (n)" bucket: open PRs with no local worktree (Task 10). */
        record PullRequestGroupNode(RepositoryPullRequests.Outcome outcome, Repository repository)
                implements SidebarNode { }
        /** One open pull request with no local worktree -- a virtual, not-yet-checked-out row. */
        record PullRequestNode(GhCliService.OpenPullRequest pullRequest, Repository repository)
                implements SidebarNode { }
    }

    public RepositorySidebar(RepositoryManager repositoryManager, GitStatusService gitStatusService,
                              WorktreeService worktreeService, RepositoryPullRequests repositoryPullRequests,
                              SessionManager sessionManager, AgentRegistry agentRegistry,
                              WorkspaceNavigator navigator, WorkspaceViewModel viewModel) {
        this.repositoryManager = repositoryManager;
        this.agentRegistry = agentRegistry;
        this.gitStatusService = gitStatusService;
        this.worktreeService = worktreeService;
        this.repositoryPullRequests = repositoryPullRequests;
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
        filterField.setPromptText("⌕  Filter repos & sessions…");
        filterDebounce.setOnFinished(e -> {
            filterChangedSinceLastRebuild = true;
            rebuildTree();
        });
        filterField.textProperty().addListener((obs, oldText, newText) -> filterDebounce.playFromStart());

        filterBar = new SessionFilterBar(agentRegistry, () -> onFilterChipsChanged());

        VBox header = new VBox(addButton, new ClearableTextField(filterField), filterBar);
        header.getStyleClass().add("sidebar-header");

        // The same collapse control the Review rails wear, in the same place.
        // Before this, the only way to fold the sidebar was a glyph up in the
        // window title bar -- a different shape, nowhere near the thing it
        // acted on, and nothing a first-time reader could generalize from.
        PanelHeader collapseHeader = PanelHeader.left("SESSIONS", "⌘0",
                "Collapse or expand the sidebar (⌘0)", () -> onToggleSidebar.run());

        refreshReviewBadges();

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

        // -- Empty state ------------------------------------------------------
        Label emptyMessage = new Label("Nothing matches your filters");
        emptyMessage.getStyleClass().add("sidebar-empty-message");
        Button clearFilters = new Button("Clear filters");
        clearFilters.getStyleClass().add("sidebar-empty-clear");
        clearFilters.setOnAction(e -> {
            // filterField.clear() re-arms filterDebounce (its text listener
            // fires playFromStart()); left alone, it would fire again ~150ms
            // later and call rebuildTree() directly, bypassing the
            // requestRebuild() coalescing that filterBar.clear()'s callback
            // already triggers -- two rebuilds instead of the required one.
            filterField.clear();
            filterDebounce.stop();
            filterBar.clear();
        });
        emptyState = new VBox(8, emptyMessage, clearFilters);
        emptyState.getStyleClass().add("sidebar-empty-state");
        emptyState.setAlignment(Pos.CENTER);

        emptyBanner.getStyleClass().add("sidebar-empty-banner");
        emptyBanner.setVisible(false);
        emptyBanner.setManaged(false);

        // -- Footer ---------------------------------------------------------
        footerDot.getStyleClass().addAll("status-dot", "dot-5");
        HBox footer = new HBox(footerDot, footerLabel);
        footer.getStyleClass().add("sidebar-footer");

        getChildren().addAll(collapseHeader.node(), header, emptyBanner, tree, footer);

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
                if (filter.isActive() && membershipChanged(sessionId)) {
                    requestRebuild();
                } else {
                    updateSessionRow(sessionId);
                }
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
                if (filter.isActive() && (membershipChanged(previous.orElse(null))
                        || membershipChanged(current.orElse(null)))) {
                    requestRebuild();
                    return;
                }
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

    /** Wired by the application shell to the same fold {@code ⌘0} and the title-bar glyph perform. */
    public void setOnToggleSidebar(Runnable handler) {
        this.onToggleSidebar = handler == null ? () -> { } : handler;
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
            case SidebarNode.LockedWorktreesNode lockedNode -> {
                RepositoryId repoId = lockedNode.repository().id();
                if (!lockedBucketExpanded.add(repoId)) {
                    lockedBucketExpanded.remove(repoId);
                }
                requestRebuild();
            }
            case SidebarNode.PullRequestGroupNode groupNode -> {
                if (groupNode.outcome() instanceof RepositoryPullRequests.Outcome.Unavailable) {
                    refreshPullRequests(groupNode.repository());
                } else {
                    item.setExpanded(!item.isExpanded());
                }
            }
            case SidebarNode.PullRequestNode pullRequestNode -> materializePullRequest(pullRequestNode);
        }
    }

    /**
     * Materializes {@code node}'s pull request: the workspace creates the
     * worktree, checks the PR out into it, starts a session and lands on its
     * review board.
     *
     * <p>The row is claimed first and released by the workspace's settle
     * hook, which runs on every ending -- cancelled at the Start-session
     * modal, failed at the checkout, failed at the session, or landed. A
     * second click while one is running is refused here rather than by the
     * disabled row alone: the row is disabled a rebuild later, and the
     * gesture that reaches this method is not always the pill (⏎ on the
     * selected row comes through {@code activateNode}).</p>
     */
    private void materializePullRequest(SidebarNode.PullRequestNode node) {
        PullRequestMaterialization.Target target = new PullRequestMaterialization.Target(
                node.repository().root(), node.pullRequest().number());
        if (!materializingPullRequests.begin(target)) {
            return;
        }
        requestRebuild();
        try {
            navigator.startReviewForPullRequest(node.repository(), node.pullRequest(), () -> {
                materializingPullRequests.end(target);
                requestRebuild();
            });
        } catch (RuntimeException e) {
            // A synchronous throw before the flow ever reaches a settle path
            // (its worktree-path resolution touches the filesystem) would
            // otherwise leak the claim and disable this row permanently.
            materializingPullRequests.end(target);
            requestRebuild();
            throw e;
        }
    }

    /**
     * Re-runs worktree discovery for {@code repository} on somebody else's
     * behalf -- the workspace, after it changed what is on disk (a pull
     * request materialized into a new worktree, say).
     *
     * <p>Deliberately the same call the sidebar's own gestures make, so the
     * caller gets the whole job and not a subset of it: the new list, each
     * new worktree's git status, and -- when the list actually changed -- the
     * {@code gh} rescan that dedups a now-checked-out PR's row away, or the
     * stale mark that defers it while the repo is collapsed.</p>
     */
    public void refreshWorktreesFor(Repository repository) {
        refreshWorktrees(repository, false);
    }

    /** Focuses the filter field (⌘F). */
    public void focusFilter() {
        filterField.requestFocus();
        filterField.selectAll();
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript}): focuses the filter
     * and types into it through the real text property, so the automated pass
     * can check that a session's native terminal actually let the keyboard go
     * -- the filter is the second place (with the tab rename) where keystrokes
     * used to disappear into the shell.
     */
    public void diagFilter(String text) {
        focusFilter();
        filterField.setText(text);
        filterField.positionCaret(text.length());
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript}): toggles one
     * filter chip by name, so a scripted visual pass can capture filter
     * combinations.
     */
    public void diagToggleFacet(String name) {
        filterBar.diagToggleFacet(name);
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript}): expands every
     * repository's stale-worktree bucket, standing in for the mouse click
     * {@link #activateNode} normally uses to toggle {@link
     * #staleBucketExpanded} -- the scripted diag driver has no pointer.
     */
    public void diagExpandStaleBuckets() {
        for (Repository repository : repositoryManager.repositories()) {
            staleBucketExpanded.add(repository.id());
        }
        requestRebuild();
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "forcehover"
     * verb): makes {@code row}'s actions strip render visible even though
     * nothing is actually hovering it. Does NOT unbind {@code
     * actions.visibleProperty()} from the cell's real {@code
     * hoverProperty()} -- {@code buildSessionRow}/{@code buildRepoRow} bind
     * it to {@code hoverProperty().or(...this row is the forced one...)},
     * so a real hover still works exactly as before and this call is a
     * no-op for every row except {@code row}. Pass {@code null} to release
     * whichever row was previously forced (a fresh sidebar rebuild also
     * clears it implicitly, since the old row's binding is discarded with
     * the row).
     */
    public void diagForceHoverRow(Node row) {
        diagForcedHoverRow.set(row);
    }

    /**
     * Diagnostic-only: sets a repository row's expansion to {@code expanded}
     * through the same {@code TreeItem.setExpanded} call the repo row's own
     * mouse-click handler uses (see {@code buildRepoRow}), so the
     * {@code expandedProperty} listener installed in {@link #rebuildTree}
     * fires the real expand path -- including the B1 rescan a repo whose PR
     * outcome went stale while collapsed self-heals on. Exists so a headless
     * test can drive the expand trigger without a TestFX robot click, which
     * intermittently fails to toggle a TreeView row under monocle/load (the
     * click reports success without the {@code expandedProperty} listener
     * ever firing, leaving the rescan the test exists to prove never
     * started). No-op (no listener fire) when the row is already in that
     * state, matching {@code setExpanded}'s own contract. Not reachable
     * outside tests.
     */
    public void diagSetRepoExpanded(RepositoryId repoId, boolean expanded) {
        for (TreeItem<SidebarNode> item : treeRoot.getChildren()) {
            if (item.getValue() instanceof SidebarNode.RepoNode repo && repo.repository().id().equals(repoId)) {
                item.setExpanded(expanded);
                return;
            }
        }
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "clickedge" verb):
     * the session the workspace currently considers active, so the driver can
     * capture it before a synthetic click and compare after -- the same
     * accessor {@link #isExempt} uses to decide whether a filtered-out row
     * still belongs in the tree.
     */
    public Optional<ManagedSessionId> diagActiveSession() {
        return viewModel.activeSession();
    }

    /**
     * Diagnostic-only: fires the {@code PR #n} chip's own click handler on
     * {@code row}. Not a robot click: synthetic pointer input in a headless
     * run reports success without reaching the app, so this delivers the
     * event straight to the node the handler is installed on. Returns whether
     * the row had a chip at all -- a silent no-op would let a test that
     * proves nothing look green.
     */
    static boolean diagClickPrChip(Node row) {
        return diagClickRowBadge(row, ".pr-chip");
    }

    /** Diagnostic-only: fires the {@code ◨n} findings badge's click handler; see {@link #diagClickPrChip}. */
    static boolean diagClickFindingsBadge(Node row) {
        return diagClickRowBadge(row, ".worktree-findings-badge");
    }

    /**
     * Diagnostic-only: fires a pull-request row's {@code Review ▸} pill; see
     * {@link #diagClickPrChip}. Deliberately fires the handler rather than
     * moving a pointer, so it also reaches a row that has disabled itself --
     * which is exactly the case a double-click test needs to make.
     */
    static boolean diagClickReviewPill(Node row) {
        return diagClickRowBadge(row, ".start-pill");
    }

    private static boolean diagClickRowBadge(Node row, String selector) {
        Node badge = row.lookup(selector);
        if (badge == null) {
            return false;
        }
        badge.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null));
        return true;
    }

    /**
     * Diagnostic-only: the {@code Review ▸} entries of {@code sessionId}'s
     * cached context menu, read the way a right-click reads them -- through
     * the menu's real {@code onShowing}, which is what re-reads the live
     * session's pull request.
     */
    List<MenuItem> diagReviewMenuItems(ManagedSessionId sessionId) {
        ContextMenu menu = sessionMenu(sessionId);
        menu.fireEvent(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
        return menu.getItems().stream()
                .filter(MenuItem::isVisible)
                .filter(item -> item.getText() != null && item.getText().startsWith("Review ▸"))
                .toList();
    }

    /**
     * Diagnostic-only ({@code app.drydock.diag.tabScript} "clickedge" verb):
     * the {@link ManagedAgentSession} id backing the {@code index}th realized
     * ".session-row", in the same top-to-bottom screen order the driver
     * hovers and clicks in (see {@code diagRowBounds} in DrydockApplication).
     * A CSS class alone carries no identity, so this walks up to the
     * enclosing {@link TreeCell} to read the row's {@link SidebarNode}.
     * Empty when the index is out of range or the row is not a session row.
     */
    public Optional<ManagedSessionId> diagSessionIdForRow(int index) {
        List<Node> rows = new ArrayList<>(lookupAll(".session-row"));
        rows.sort(Comparator.comparingDouble(
                node -> node.localToScreen(node.getBoundsInLocal()).getMinY()));
        if (index < 0 || index >= rows.size()) {
            return Optional.empty();
        }
        Node node = rows.get(index);
        while (node != null && !(node instanceof TreeCell<?>)) {
            node = node.getParent();
        }
        if (node instanceof TreeCell<?> cell
                && cell.getItem() instanceof SidebarNode.SessionNode sessionNode) {
            return Optional.of(sessionNode.session().id());
        }
        return Optional.empty();
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

    /** Display text for a remote-host chip: a sync glyph followed by the host. */
    static String remoteChipText(String host) {
        return "⇅ " + host;
    }

    /** Tooltip text for a remote-host chip: the full (untruncated) host. */
    static String remoteChipTooltipText(String host) {
        return "Remote host: " + host;
    }

    /**
     * Header text for the "PULL REQUESTS" bucket: a count for a landed scan,
     * or a retry affordance for one that could not run. Never called for
     * {@link RepositoryPullRequests.Outcome.Absent} -- {@code childNodesFor}
     * never mints a group for it, so there is nothing sensible to say.
     */
    static String pullRequestGroupLabel(RepositoryPullRequests.Outcome outcome) {
        return switch (outcome) {
            case RepositoryPullRequests.Outcome.Rows rows -> "PULL REQUESTS (" + rows.pullRequests().size() + ")";
            case RepositoryPullRequests.Outcome.Unavailable unavailable -> "PULL REQUESTS — unavailable · retry";
            case RepositoryPullRequests.Outcome.Absent absent ->
                    throw new IllegalArgumentException("Absent has no group to label");
        };
    }

    /** One PR row's text: its number, title, and (if known) who opened it. */
    static String pullRequestRowText(GhCliService.OpenPullRequest pullRequest) {
        String author = pullRequest.author().map(name -> " · @" + name).orElse("");
        return "#" + pullRequest.number() + "  " + pullRequest.title() + author;
    }

    /**
     * Whether a repository is due for a PR scan right now. {@code
     * worktreesDiscovered} must be true first: {@link
     * RepositoryPullRequests#scan} dedups the PRs it returns against the
     * worktree list it is given, and scanning before discovery has landed
     * means that list is empty, so every PR that already has a local
     * worktree wrongly earns a row (fixed alongside a matching re-scan from
     * {@code refreshWorktrees}'s completion whenever that list actually
     * changes, so a worktree appearing/disappearing later keeps the group
     * correct too). {@code pullRequestsScanned} then keeps a repo that
     * already has an outcome -- of any kind, {@code Absent} included --
     * from being rescanned on every rebuild. NOT the whole story where
     * staleness is concerned, though: a repo can hold a correct-when-taken
     * outcome that a LATER worktree change invalidated while the repo was
     * collapsed (deliberately not auto-rescanned then -- see {@code
     * refreshWorktrees}'s completion); every caller of this method also
     * consults {@link #pullRequestsStale} alongside it, since {@code
     * pullRequestsScanned} alone cannot tell "scanned" from "scanned, but
     * no longer accurate" apart.
     *
     * <p>Consequence worth naming: a repository whose worktree discovery
     * fails and keeps failing never satisfies {@code worktreesDiscovered}
     * (nothing ever calls {@code viewModel.setWorktrees} for it), so it
     * never gets a PR scan either, automatic or otherwise -- the PR group
     * inherits that pre-existing failure mode rather than working around
     * it. Accepted: a repo already unable to show its worktrees has a
     * bigger problem than a missing PULL REQUESTS group.</p>
     */
    static boolean needsPullRequestScan(boolean worktreesDiscovered, boolean pullRequestsScanned) {
        return worktreesDiscovered && !pullRequestsScanned;
    }

    /**
     * Whether a landed worktree-discovery result should re-run the PR scan
     * (the other half of keeping the group's dedup correct, alongside
     * {@link #needsPullRequestScan}): the very first landing, when an
     * earlier scan -- if any -- had nothing to dedup against, or any actual
     * change to the list, since a worktree appearing or disappearing
     * changes which PRs are selectable. {@code previous} is {@code null}
     * for "never discovered before".
     */
    static boolean worktreeListChanged(List<WorktreeService.Worktree> previous,
                                       List<WorktreeService.Worktree> current) {
        return previous == null || !previous.equals(current);
    }

    /**
     * Whether a PR-scan request arriving while one is already in flight
     * should be remembered and re-run once that scan lands (true), or
     * dropped as a genuine duplicate of the one already running (false).
     * Compares the worktree list the in-flight scan captured against the
     * current one, NOT just "is something in flight": a scan already
     * captures whatever list existed when it started, so if that list has
     * not moved since, the in-flight scan's result will already be
     * correct once it lands, and queuing a second run would just repeat
     * the identical {@code gh pr list} call the first one is already
     * making. It IS queued when the list has moved -- the ⟳ race this
     * exists for: the worktree rescan that same click starts lands its own
     * re-run request here (via {@code refreshWorktrees}'s completion)
     * while the PR scan the SAME click fired moments earlier is still in
     * flight, captured against the pre-rescan list.
     */
    static boolean shouldQueuePullRequestRescan(List<WorktreeService.Worktree> capturedWorktrees,
                                                List<WorktreeService.Worktree> currentWorktrees) {
        return !Objects.equals(capturedWorktrees, currentWorktrees);
    }

    /**
     * The group row's actual label text: {@link #pullRequestGroupLabel}
     * verbatim, except for a landed {@code Rows} outcome where {@code
     * shown} (the number of {@code PullRequestNode} children a text
     * filter left standing, see {@link #pullRequestGroupItem}) is less
     * than the outcome's full count -- then "n of m", the same idiom
     * {@code buildRepoRow}'s own count uses, so the header never claims a
     * count the rows below it do not back up.
     */
    static String pullRequestGroupText(RepositoryPullRequests.Outcome outcome, int shown) {
        if (!(outcome instanceof RepositoryPullRequests.Outcome.Rows rows) || shown == rows.pullRequests().size()) {
            return pullRequestGroupLabel(outcome);
        }
        return "PULL REQUESTS (" + shown + " of " + rows.pullRequests().size() + ")";
    }

    /**
     * Builds the sidebar chip that marks a repo as remote and names its host.
     * Package-private + static so the pure text helpers it delegates to can be
     * unit-tested; the Label/Tooltip wiring itself is verified by running the
     * app. Only ever called for repositories where {@code isRemote()} is true,
     * so {@code remote().host()} is non-null.
     */
    static Label buildRemoteChip(Repository repository) {
        String host = repository.remote().host();
        Label chip = new Label(remoteChipText(host));
        chip.getStyleClass().add("repo-remote-chip");
        chip.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        chip.setMaxWidth(160);
        chip.setTooltip(new Tooltip(remoteChipTooltipText(host)));
        return chip;
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
                for (ManagedAgentSession candidate : classified.liveSessions()) {
                    if (filter.matches(candidate)) {
                        live.add(candidate);
                    }
                }
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

    /**
     * Whether {@code sessionId} belongs in the tree but is missing, or is in
     * the tree but no longer belongs. Reasons about the chip filter only --
     * {@link SessionFilter#matches} never looks at the text query -- so
     * callers must gate on {@link SessionFilter#isActive()}, not {@link
     * #filtering()}: a text-only query cannot change this method's answer,
     * and gating on {@code filtering()} would trigger rebuilds that cannot
     * change the outcome. The {@code isExempt} term is not optional: an
     * exempt row fails {@code matches} by definition while sitting in the
     * tree, and it is the frontmost session -- the one emitting the most
     * row events -- so testing {@code matches} alone would force a full
     * rebuild on every one of them that could never resolve the mismatch it
     * reacted to.
     */
    private boolean membershipChanged(ManagedSessionId sessionId) {
        if (sessionId == null) {
            return false;
        }
        ManagedAgentSession session = viewModel.sessionById(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        boolean belongs = filter.matches(session) || isExempt(sessionId);
        return belongs != isInTree(sessionId);
    }

    private boolean isInTree(ManagedSessionId sessionId) {
        for (TreeItem<SidebarNode> repoItem : treeRoot.getChildren()) {
            for (TreeItem<SidebarNode> child : repoItem.getChildren()) {
                if (child.getValue() instanceof SidebarNode.SessionNode sessionNode
                        && sessionNode.session().id().equals(sessionId)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Whether the sidebar is narrowed at all -- by chips or by text. Every
     * filter-aware surface (empty state, footer suffix, repo aggregates, the
     * childless-repo rule) keys on this one predicate, or they disagree with
     * each other about what the user is looking at.
     */
    private boolean filtering() {
        return filter.isActive() || !currentQuery().isEmpty();
    }

    /** Chip callback: re-reads {@link #filterBar} and coalesces into one rebuild. */
    private void onFilterChipsChanged() {
        filter = filterBar.filter();
        filterChangedSinceLastRebuild = true;
        requestRebuild();
    }

    private String currentQuery() {
        return filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);
    }

    /** The frontmost session is always rendered -- see {@link #applyFacets}. */
    private boolean isExempt(ManagedSessionId sessionId) {
        return viewModel.activeSession().filter(sessionId::equals).isPresent();
    }

    private void rebuildTree() {
        // A filter is a global question ("where are my errors?"); repo
        // expansion is a local reading preference. Re-assert the expansion on
        // every change to the filter -- not only on entry, or switching from
        // `running` to `error` would leave the sole matching session inside a
        // repo the user collapsed earlier.
        if (filtering()) {
            if (collapsedBeforeFilter == null) {
                collapsedBeforeFilter = new HashSet<>(collapsed);
            }
            if (filterChangedSinceLastRebuild) {
                collapsed.clear();
            }
        } else if (collapsedBeforeFilter != null) {
            collapsed.clear();
            collapsed.addAll(collapsedBeforeFilter);
            collapsedBeforeFilter = null;
        }
        filterChangedSinceLastRebuild = false;

        String query = currentQuery();

        List<Repository> repositories = sorted(repositoryManager.repositories());
        List<TreeItem<SidebarNode>> repoItems = new ArrayList<>();
        // Surviving rows of ANY kind (session, unopened worktree, stale/
        // locked bucket), except a session row that is present only because
        // {@code isExempt} accepted it despite failing the filter -- the
        // active session can keep a repo, and the tree, non-empty on its
        // own, and that must not count as a "match" or the empty state
        // would never show while a session is running. A worktree/bucket
        // row is never exempt, so it always counts when it survives: under
        // an active chip filter those rows are already stripped by
        // applyFacets, but a text-only query (chips inactive) leaves them
        // in place, and a query that matches a branch or worktree path but
        // no session is a real match, not an empty result.
        int matchCount = 0;

        for (Repository repository : repositories) {
            List<SidebarNode> children = applyFacets(childNodesFor(repository), filter, this::isExempt);
            // The filter matches the repo itself (name/branch) OR any of
            // its worktree/session rows; a repo matched only through its
            // children narrows to exactly the matching rows.
            boolean repoMatchedByName = !query.isEmpty() && matchesRepo(repository, query);
            if (!query.isEmpty() && !repoMatchedByName) {
                children = children.stream().filter(child -> matchesNode(child, query)).toList();
            }
            // Only drop a childless repo while filtering: with no filter at
            // all, a freshly added repository with no worktrees and no
            // sessions must keep showing itself (and its + and ⟳ buttons).
            // A repo matched BY NAME survives even childless -- typing a
            // repository's name before its worktree discovery has returned
            // must show the repo, not "Nothing matches your filters".
            if (children.isEmpty() && filtering() && !repoMatchedByName) {
                continue;
            }
            if (children.isEmpty() && repoMatchedByName) {
                matchCount++;
            }
            for (SidebarNode child : children) {
                boolean exemptOnly = child instanceof SidebarNode.SessionNode sessionNode
                        && !filter.matches(sessionNode.session())
                        && isExempt(sessionNode.session().id());
                if (!exemptOnly) {
                    matchCount++;
                }
            }
            TreeItem<SidebarNode> repoItem = new TreeItem<>(new SidebarNode.RepoNode(repository));
            for (SidebarNode child : children) {
                repoItem.getChildren().add(
                        pullRequestGroupItem(child, repository).orElseGet(() -> new TreeItem<>(child)));
            }
            repoItem.setExpanded(!collapsed.contains(repository.id()));
            repoItem.expandedProperty().addListener((obs, was, is) -> {
                if (is) {
                    collapsed.remove(repository.id());
                    // A repo row expanding is one of refreshPullRequests's
                    // four triggers (see its javadoc) -- and the one that
                    // actually fires the FIRST scan for a repo that starts
                    // collapsed, or recovers one whose outcome went stale
                    // while collapsed: refreshWorktrees's completion
                    // deliberately does not scan a collapsed repo (see the
                    // comment there), marking it stale instead, and
                    // pullRequestScanDue is what notices that mark here.
                    if (pullRequestScanDue(repository)) {
                        refreshPullRequests(repository);
                    }
                } else {
                    collapsed.add(repository.id());
                }
                // Re-render the header so the ▶ caret tracks EVERY expansion
                // change -- keyboard toggles (Enter, ←/→) included, not just
                // the row's own mouse handler.
                updateRepoRow(repository.id());
            });
            if (repoItem.isExpanded() && pullRequestScanDue(repository)) {
                refreshPullRequests(repository);
            }
            repoItems.add(repoItem);
        }

        treeRoot.getChildren().setAll(repoItems);

        // Two forms, because an exempt row can leave the tree non-empty while
        // nothing actually matched. Swap only when there is nothing to show
        // at all; otherwise the exempt row would be deleted from the screen,
        // re-creating the failure the exemption exists to prevent.
        boolean nothingMatched = filtering() && matchCount == 0;
        boolean treeIsEmpty = treeRoot.getChildren().isEmpty();
        boolean noRepositoriesAtAll = repositoryManager.repositories().isEmpty();
        showEmptyState(nothingMatched && !noRepositoriesAtAll, treeIsEmpty);

        updateFooter();
        syncActiveSelection();
    }

    /**
     * Whether {@code repository} is due for a PR scan right now, at either
     * of the two places {@code rebuildTree} asks: on repository add (a
     * newly built, expanded {@code TreeItem}) and on repo-row expand. True
     * for either half of {@link #needsPullRequestScan}'s reason (discovery
     * has landed, nothing scanned yet) OR {@link #pullRequestsStale} (an
     * outcome exists, but a worktree change invalidated it while the repo
     * was collapsed) -- {@code needsPullRequestScan} alone cannot tell
     * "scanned" from "scanned, but no longer accurate" apart.
     */
    private boolean pullRequestScanDue(Repository repository) {
        return needsPullRequestScan(viewModel.worktrees(repository.id()).isPresent(),
                viewModel.pullRequests(repository.id()).isPresent())
                || pullRequestsStale.contains(repository.id());
    }

    /**
     * A {@code PullRequestGroupNode}'s TreeItem, unlike every other child
     * row, holds real {@code PullRequestNode} children -- one per pull
     * request in a landed {@code Rows} outcome, none for {@code
     * Unavailable} -- rather than being a leaf, so the TreeView's own
     * expand/collapse drives it. Starts collapsed; its expand state
     * survives rebuilds in {@link #pullRequestsExpanded}, the same way a
     * repository row's does in {@link #collapsed}. Empty for every other
     * {@code SidebarNode}, so the caller falls back to a plain leaf item.
     */
    private Optional<TreeItem<SidebarNode>> pullRequestGroupItem(SidebarNode child, Repository repository) {
        if (!(child instanceof SidebarNode.PullRequestGroupNode groupNode)) {
            return Optional.empty();
        }
        TreeItem<SidebarNode> groupItem = new TreeItem<>(groupNode);
        groupItem.getChildren().setAll(
                pullRequestChildItems(groupNode.outcome(), repository, pullRequestNarrowQuery(repository)));
        groupItem.setExpanded(pullRequestsExpanded.contains(repository.id()));
        groupItem.expandedProperty().addListener((obs, was, is) -> {
            if (is) {
                pullRequestsExpanded.add(repository.id());
            } else {
                pullRequestsExpanded.remove(repository.id());
            }
            // Mirrors the repo row's own listener: the caret this draws
            // (▸/▾) is computed at render time from getTreeItem().isExpanded(),
            // so without an explicit repaint here it goes stale on every
            // toggle -- mouse click and keyboard (→ / Enter) alike.
            updatePullRequestGroupRow(repository.id());
        });
        return Optional.of(groupItem);
    }

    /**
     * The query {@link #pullRequestGroupItem} and {@link
     * #updatePullRequestGroupRow} narrow a group's PR rows by -- empty
     * unless a text filter is active AND the repo did not already match by
     * its own name/branch, mirroring {@code rebuildTree}'s own top-level
     * children filter exactly (a repo matched by name shows everything
     * under it, unnarrowed).
     */
    private String pullRequestNarrowQuery(Repository repository) {
        String query = currentQuery();
        if (query.isEmpty() || matchesRepo(repository, query)) {
            return "";
        }
        return query;
    }

    /**
     * The {@code PullRequestNode} child items a group's TreeItem should
     * hold for {@code outcome}, narrowed to those matching {@code
     * narrowQuery} (empty means unnarrowed -- every PR in a landed {@code
     * Rows}, none for {@code Unavailable}). Shared by {@link
     * #pullRequestGroupItem} (a fresh TreeItem) and {@link
     * #updatePullRequestGroupRow} (an in-place repaint of an existing one)
     * so both ALWAYS agree on which children a group holds -- the in-place
     * repaint used to only swap the node's value and leave stale children
     * in place, which could show a wrong "n of m" for one frame with no
     * filter active at all.
     */
    private List<TreeItem<SidebarNode>> pullRequestChildItems(RepositoryPullRequests.Outcome outcome,
                                                               Repository repository, String narrowQuery) {
        List<TreeItem<SidebarNode>> items = new ArrayList<>();
        if (!(outcome instanceof RepositoryPullRequests.Outcome.Rows rows)) {
            return items;
        }
        for (GhCliService.OpenPullRequest pullRequest : rows.pullRequests()) {
            SidebarNode prNode = new SidebarNode.PullRequestNode(pullRequest, repository);
            // Routed through matchesNode's own PullRequestNode case
            // (rather than a private duplicate of the same check) so that
            // case is the one place this decision is made.
            if (!narrowQuery.isEmpty() && !matchesNode(prNode, narrowQuery)) {
                continue;
            }
            items.add(new TreeItem<>(prNode));
        }
        return items;
    }

    /**
     * Force-repaints {@code repositoryId}'s PULL REQUESTS group row in
     * place, without depending on a model change to get there. {@link
     * WorkspaceViewModel#setPullRequests} only notifies (and so only
     * triggers a coalesced {@link #rebuildTree()}) when the outcome
     * actually differs from what was stored -- a retry that lands the SAME
     * {@code Unavailable} message notifies nobody. Without this, the
     * "checking…" text a retry click writes straight onto the row's {@code
     * Label} (see {@code buildPullRequestGroupRow}) would be stranded
     * forever, and the group's expand caret (this method's other caller,
     * {@link #pullRequestGroupItem}) would never repaint after a toggle.
     *
     * <p>Rebuilds the group's CHILDREN (via {@link #pullRequestChildItems},
     * the same helper a fresh {@link #pullRequestGroupItem} uses) before
     * touching its value: the row's label counts the live child list, not
     * just the outcome, so replacing only the value here -- leaving
     * whatever children happened to be attached before -- could show a
     * stale "n of m" even with no filter active.</p>
     *
     * <p>A freshly constructed {@code PullRequestGroupNode} always repaints
     * its cell here regardless: {@code TreeItem}'s value property
     * invalidates on reference inequality, not {@code equals()}, so a new
     * instance fires even when it is content-equal to the one already
     * showing. If the fresh outcome no longer earns a group at all (e.g. it
     * changed to an empty {@code Rows}), this leaves the stale row alone --
     * an outcome CHANGE always does notify, so a real {@link
     * #rebuildTree()} is already coalesced and will remove it structurally.
     */
    private void updatePullRequestGroupRow(RepositoryId repositoryId) {
        for (TreeItem<SidebarNode> repoItem : treeRoot.getChildren()) {
            if (!(repoItem.getValue() instanceof SidebarNode.RepoNode repoNode)
                    || !repoNode.repository().id().equals(repositoryId)) {
                continue;
            }
            for (TreeItem<SidebarNode> child : repoItem.getChildren()) {
                if (child.getValue() instanceof SidebarNode.PullRequestGroupNode groupNode) {
                    Repository repository = groupNode.repository();
                    pullRequestGroupNodeFor(repository).ifPresent(fresh -> {
                        if (fresh instanceof SidebarNode.PullRequestGroupNode freshGroup) {
                            List<TreeItem<SidebarNode>> freshChildren = pullRequestChildItems(
                                    freshGroup.outcome(), repository, pullRequestNarrowQuery(repository));
                            // Most calls here repaint content that did not
                            // change at all -- a completion whose outcome
                            // matched what was stored, and the group's own
                            // expand/collapse listener (which only needs the
                            // caret redrawn). A setAll on those replaces every
                            // child TreeItem with an equal-but-new instance,
                            // which drops TreeView selection/focus off a PR row
                            // every time the user toggles the group. TreeItem
                            // has identity equals, so compare the VALUES.
                            if (!nodeValuesOf(child.getChildren()).equals(nodeValuesOf(freshChildren))) {
                                child.getChildren().setAll(freshChildren);
                            }
                        }
                        child.setValue(fresh);
                    });
                    return;
                }
            }
            return;
        }
    }

    /** The {@code SidebarNode} values of {@code items}, in order -- records, so this compares by content. */
    private static List<SidebarNode> nodeValuesOf(List<TreeItem<SidebarNode>> items) {
        return items.stream().map(TreeItem::getValue).toList();
    }

    /**
     * Chooses between the two empty-state forms. {@code nothingMatched} is
     * already false when there are no repositories at all -- an empty
     * workspace is not a filter problem, and Clear filters cannot help.
     *
     * <p>Focus moves only when the current focus owner sits inside the node
     * being removed: an unconditional move would yank the caret out of the
     * filter field on the 150ms debounce, swallowing the next keystroke and
     * turning Space into "Clear filters". The banner form moves no focus at
     * all, because nothing leaves the scene.
     */
    private void showEmptyState(boolean nothingMatched, boolean treeIsEmpty) {
        boolean swap = nothingMatched && treeIsEmpty;
        boolean banner = nothingMatched && !treeIsEmpty;

        Node focusOwner = getScene() == null ? null : getScene().getFocusOwner();

        if (swap && !getChildren().contains(emptyState)) {
            boolean treeHadFocus = isDescendantOf(focusOwner, tree);
            getChildren().set(getChildren().indexOf(tree), emptyState);
            VBox.setVgrow(emptyState, Priority.ALWAYS);
            if (treeHadFocus) {
                emptyState.getChildren().get(1).requestFocus();
            }
        } else if (!swap && getChildren().contains(emptyState)) {
            boolean buttonHadFocus = isDescendantOf(focusOwner, emptyState);
            getChildren().set(getChildren().indexOf(emptyState), tree);
            VBox.setVgrow(tree, Priority.ALWAYS);
            if (buttonHadFocus) {
                filterField.requestFocus();
            }
        }
        emptyBanner.setVisible(banner);
        emptyBanner.setManaged(banner);
    }

    /** Whether {@code node} is {@code ancestor} itself or a descendant of it. */
    private static boolean isDescendantOf(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
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
            if (SessionStatusStyles.isRunning(session.status())) {
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
            worktreeTotal += classified.worktreeCount() + classified.staleCount() + classified.lockedCount();
            unopenedTotal += (int) classified.openWorktrees().stream().filter(w -> !w.mainCheckout()).count()
                    + classified.staleCount() + classified.lockedCount();
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
        if (filtering()) {
            // The footer's job is "what exists" -- shrinking the totals would
            // erase the only remaining evidence of what the filter hides.
            footerText += " · filtered";
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
        unopenedMenus.keySet().retainAll(worktreePaths);
        collapsed.retainAll(repoIds);
        if (collapsedBeforeFilter != null) {
            collapsedBeforeFilter.retainAll(repoIds);
        }
        staleBucketExpanded.retainAll(repoIds);
        lockedBucketExpanded.retainAll(repoIds);
        pullRequestsExpanded.retainAll(repoIds);
        // Without this, a repository removed while a PR-scan request was
        // queued for it (pendingPullRequestRescan) spawns one more gh
        // process on the in-flight scan's completion, for a repo that no
        // longer exists; pullRequestsStale and pullRequestScanWorktrees
        // have the identical leak shape.
        pendingPullRequestRescan.retainAll(repoIds);
        pullRequestsStale.retainAll(repoIds);
        pullRequestScanWorktrees.keySet().retainAll(repoIds);
    }

    /** Supplies a worktree checkout's open-finding count for its {@code ◨n} badge. */
    public void setOpenFindingsAt(Function<Path, Optional<Integer>> lookup) {
        this.openFindingsAt = lookup == null ? path -> Optional.empty() : lookup;
        refreshReviewBadges();
    }

    /**
     * Re-reads the per-worktree {@code ◨n} badges the tree cells draw, after
     * every queue reassembly. The sidebar is purely Sessions now (nav §1):
     * the queue's own item count belongs to the Review tab's badge, which is
     * where Review itself lives.
     */
    public void refreshReviewBadges() {
        Map<Path, Integer> current = currentFindingCounts();
        if (!current.equals(renderedFindingCounts)) {
            renderedFindingCounts = current;
            rebuildTree();
        }
    }

    /** Every worktree path the tree can show, mapped to its open-finding count. */
    private Map<Path, Integer> currentFindingCounts() {
        Map<Path, Integer> counts = new LinkedHashMap<>();
        for (Repository repository : repositoryManager.repositories()) {
            for (SidebarNode child : childNodesFor(repository)) {
                Path checkout = switch (child) {
                    case SidebarNode.SessionNode node -> node.session().worktreeRoot().orElse(null);
                    case SidebarNode.UnopenedWorktreeNode node -> node.worktree().path();
                    default -> null;
                };
                if (checkout != null) {
                    openFindingsAt.apply(checkout).ifPresent(count -> counts.put(checkout, count));
                }
            }
        }
        return Map.copyOf(counts);
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
     * sessions, then open worktrees, then a collapsed locked-worktrees bucket
     * and a collapsed stale-worktrees bucket (each if non-empty) -- ordering
     * and classification delegated to {@link SidebarChildren}.
     */
    private List<SidebarNode> childNodesFor(Repository repository) {
        SidebarChildren classified = childrenOf(repository);
        if (classified == null) {
            // Discovery hasn't run yet: kick it off and show session-derived rows meanwhile.
            refreshWorktrees(repository, false);
            List<SidebarNode> children = new ArrayList<>(sessionsFor(repository).stream()
                    .map(session -> (SidebarNode) new SidebarNode.SessionNode(session, repository))
                    .toList());
            pullRequestGroupNodeFor(repository).ifPresent(children::add);
            return children;
        }
        List<SidebarNode> children = new ArrayList<>();
        for (ManagedAgentSession session : classified.orderedSessions()) {
            children.add(new SidebarNode.SessionNode(session, repository));
        }
        for (WorktreeService.Worktree worktree : classified.openWorktrees()) {
            children.add(new SidebarNode.UnopenedWorktreeNode(worktree, repository));
        }
        if (!classified.lockedWorktrees().isEmpty()) {
            children.add(new SidebarNode.LockedWorktreesNode(classified.lockedWorktrees(), repository));
        }
        if (!classified.staleWorktrees().isEmpty()) {
            children.add(new SidebarNode.StaleWorktreesNode(classified.staleWorktrees(), repository));
        }
        pullRequestGroupNodeFor(repository).ifPresent(children::add);
        return children;
    }

    /**
     * The repo's {@code PULL REQUESTS} group, if its latest scan earns one:
     * a landed {@code Rows} with at least one entry, or an {@code
     * Unavailable} the reader can retry. Nothing for a scan that has not
     * run yet, for {@code Absent} (no {@code gh}), or for an empty {@code
     * Rows} -- a group that says "(0)" is noise.
     */
    private Optional<SidebarNode> pullRequestGroupNodeFor(Repository repository) {
        return viewModel.pullRequests(repository.id())
                .filter(outcome -> outcome instanceof RepositoryPullRequests.Outcome.Unavailable
                        || (outcome instanceof RepositoryPullRequests.Outcome.Rows rows
                                && !rows.pullRequests().isEmpty()))
                .map(outcome -> new SidebarNode.PullRequestGroupNode(outcome, repository));
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
            case SidebarNode.LockedWorktreesNode lockedNode -> lockedNode.worktrees().stream().anyMatch(worktree -> {
                String text = worktree.branch().orElse("") + " " + worktree.path();
                return text.toLowerCase(Locale.ROOT).contains(query);
            });
            case SidebarNode.PullRequestNode pullRequestNode -> matchesPullRequest(pullRequestNode.pullRequest(), query);
            case SidebarNode.PullRequestGroupNode groupNode ->
                    groupNode.outcome() instanceof RepositoryPullRequests.Outcome.Rows rows
                            && rows.pullRequests().stream().anyMatch(pullRequest -> matchesPullRequest(pullRequest, query));
        };
    }

    /** A pull request matches on its number, title or head branch -- the same fields the row renders. */
    private static boolean matchesPullRequest(GhCliService.OpenPullRequest pullRequest, String query) {
        String text = "#" + pullRequest.number() + " " + pullRequest.title() + " " + pullRequest.headRefName();
        return text.toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * The facet half of the sidebar's filtering: session-scoped, and pure so
     * it can be tested without an FX toolkit or a live sidebar.
     *
     * <p>An active facet filter turns the sidebar into a session list -- the
     * unopened-worktree rows and the locked/stale buckets drop out, because a
     * filter over sessions cannot say anything about a worktree that has
     * none. {@code exempt} always survives; it is how the frontmost session
     * stays on screen even when it fails the filter.
     */
    static List<SidebarNode> applyFacets(List<SidebarNode> children, SessionFilter filter,
                                         Predicate<ManagedSessionId> exempt) {
        if (!filter.isActive()) {
            return children;
        }
        List<SidebarNode> kept = new ArrayList<>();
        for (SidebarNode child : children) {
            if (child instanceof SidebarNode.SessionNode sessionNode
                    && (filter.matches(sessionNode.session())
                            || exempt.test(sessionNode.session().id()))) {
                kept.add(child);
            }
        }
        return kept;
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
                    // The PR group dedups against this exact list: a
                    // worktree appearing (e.g. a checkout of a listed PR)
                    // or disappearing changes which PRs are selectable, and
                    // the very first landing corrects any earlier scan that
                    // had to run before discovery had anything to dedup
                    // against (worktreeListChanged's `previous == null`
                    // case). A collapsed repo's worktree discovery still
                    // runs as it always has, but must not cascade into a
                    // `gh pr list` spawn for a row nobody is looking at --
                    // marked stale instead of rescanned, so ANY outcome it
                    // already holds is known to need a fresh scan without
                    // actually running one, and pullRequestScanDue (via
                    // pullRequestsStale) picks it up the moment the repo is
                    // next expanded. A repo NOT collapsed rescans right
                    // away, same as before.
                    if (worktreeListChanged(previous, worktrees)) {
                        if (collapsed.contains(repository.id())) {
                            pullRequestsStale.add(repository.id());
                        } else {
                            refreshPullRequests(repository);
                        }
                    }
                    // An unchanged list emits no model event; the rescan
                    // note / spinner stop still need the header re-rendered.
                    updateRepoRow(repository.id());
                }));
    }

    /**
     * Re-runs the open-pull-request scan for {@code repository} and writes
     * the outcome into the view model. Mirrors {@link #refreshWorktrees}:
     * the scan itself (a {@code gh} process spawn) never touches the FX
     * thread, only the {@link Platform#runLater} that follows it does; a
     * repository removed mid-flight is handled the same way a mid-flight
     * status/worktree refresh is -- the write lands in the view model under
     * its (now orphaned) id, but {@code rebuildTree} only ever iterates
     * {@code repositoryManager.repositories()}, so it is simply never
     * rendered. Always a no-op for a remote repository, which has no local
     * checkout to ask {@code gh} about. Four call sites: repository add and
     * repo-row expansion (both via {@link #rebuildTree()}, gated by {@link
     * #pullRequestScanDue}), the ⟳ rescan, and {@link #refreshWorktrees}'s
     * completion re-running this whenever the worktree list actually
     * changes AND the repo is expanded (a collapsed repo is marked {@link
     * #pullRequestsStale} instead -- see that method).
     *
     * <p>A request arriving while a scan is already in flight is NOT
     * simply dropped: {@link #shouldQueuePullRequestRescan} compares the
     * worktree list the in-flight scan captured ({@link
     * #pullRequestScanWorktrees}) against the current one, and only if
     * they differ is the request remembered in {@link
     * #pendingPullRequestRescan} for a re-run once the in-flight scan
     * lands -- an identical list means the in-flight scan's result will
     * already be correct, and queuing anyway would spawn a redundant,
     * identical {@code gh pr list}. This matters most on the ⟳ path, which
     * calls this at the same moment it starts a worktree rescan: that
     * worktree rescan's own completion calls back in here (see above) once
     * it knows the list changed, but by then the PR scan the SAME click
     * fired moments earlier is very likely still in flight (a local {@code
     * git worktree list} finishes in milliseconds; {@code gh pr list} is a
     * network call) -- dropping that second request unconditionally left
     * the in-flight scan land dedup'ed against the pre-rescan list, so the
     * group never healed; queuing it unconditionally instead double-spawned
     * an identical scan on every startup rebuild.</p>
     *
     * <p>{@link RepositoryPullRequests#scan} already converts every failure
     * it knows about into {@code Outcome.Unavailable}, so the future here
     * failing outright is not expected in practice -- but if it ever does,
     * an outcome is still recorded. Leaving the view model untouched on
     * failure would leave {@code pullRequests(id)} empty forever, and
     * {@link #needsPullRequestScan} would re-fire this scan -- a {@code gh}
     * process spawn -- on every subsequent {@link #rebuildTree()}.</p>
     */
    private void refreshPullRequests(Repository repository) {
        if (repository.isRemote()) {
            return;
        }
        List<WorktreeService.Worktree> worktrees = viewModel.worktrees(repository.id()).orElse(List.of());
        if (!scanningPullRequests.add(repository.id())) {
            if (shouldQueuePullRequestRescan(pullRequestScanWorktrees.get(repository.id()), worktrees)) {
                pendingPullRequestRescan.add(repository.id());
            }
            return;
        }
        // A scan is launching against the CURRENT list right now, so
        // whatever made the outcome stale (if anything) is being
        // addressed; leaving the mark would only cause a redundant queued
        // re-run once this one lands (shouldQueuePullRequestRescan would
        // still say no, since the lists match -- harmless, but pointless).
        pullRequestsStale.remove(repository.id());
        pullRequestScanWorktrees.put(repository.id(), worktrees);
        updateRepoRow(repository.id()); // progress must show immediately (AGENTS.md)
        repositoryPullRequests.scan(repository.root(), worktrees)
                .whenComplete((outcome, failure) -> Platform.runLater(() -> {
                    // Every completion path -- success, failure, and (via the
                    // guards above) early return -- clears the progress state.
                    scanningPullRequests.remove(repository.id());
                    pullRequestScanWorktrees.remove(repository.id());
                    if (failure != null) {
                        LOG.log(Level.DEBUG, "Pull-request scan failed for " + repository.root(), failure);
                        Throwable cause = UiErrors.unwrap(failure);
                        String message = cause.getMessage() != null
                                ? cause.getMessage() : "Could not scan pull requests";
                        viewModel.setPullRequests(repository.id(),
                                new RepositoryPullRequests.Outcome.Unavailable(message));
                    } else {
                        viewModel.setPullRequests(repository.id(), outcome);
                    }
                    updateRepoRow(repository.id());
                    // setPullRequests only notifies (and rebuilds) when the
                    // outcome actually changed; force-repaint the row
                    // directly so a retry's "checking…" text (see
                    // buildPullRequestGroupRow) never gets stranded.
                    updatePullRequestGroupRow(repository.id());
                    // A request that arrived while this scan was running,
                    // against a list that has since moved on (see this
                    // method's javadoc), gets its re-run now.
                    if (pendingPullRequestRescan.remove(repository.id())) {
                        refreshPullRequests(repository);
                    }
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
                        Throwable cause = UiErrors.unwrap(failure);
                        if (cause instanceof WorktreeNotCleanException) {
                            confirmForcedWorktreeDelete(repository, worktree);
                        } else if (cause instanceof WorktreeLockedException locked) {
                            confirmLockedWorktreeDelete(repository, worktree, locked.lockReason());
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
     * The plain remove was refused because the worktree is locked -- a
     * deliberate marker (e.g. a tool that set it up is still mid-flight):
     * name the lock and its reason, then override it with a double-force
     * removal only once the user has confirmed.
     */
    private void confirmLockedWorktreeDelete(Repository repository, WorktreeService.Worktree worktree,
            Optional<String> lockReason) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.setTitle("Delete worktree");
        confirm.setHeaderText("\"" + worktree.branch().orElse(worktree.path().getFileName().toString())
                + "\" is locked" + lockReason.map(reason -> " (" + reason + ")").orElse(""));
        confirm.setContentText("The worktree at " + worktree.path()
                + " is locked; removing it may disrupt whatever set the lock. Delete anyway?");
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

    /**
     * Batch-remove the locked bucket. Unlike the stale bucket, every worktree
     * here is locked on purpose, so there is no "clean subset" to remove
     * quietly: one confirm that spells out the override, then a forced
     * (double-force) removal of each -- discarding any uncommitted work and the
     * lock alike. Per-worktree failures are surfaced individually.
     */
    private void cleanLockedWorktrees(Repository repository, List<WorktreeService.Worktree> worktrees) {
        Alert confirm = new Alert(AlertType.CONFIRMATION);
        confirm.setTitle("Clean locked worktrees");
        confirm.setHeaderText("Remove " + worktrees.size() + " locked worktree"
                + (worktrees.size() == 1 ? "" : "s") + "?");
        confirm.setContentText("These worktrees are locked -- something may still be using them "
                + "(a tool mid-setup). Removing them overrides the lock and discards any uncommitted "
                + "work. Remove anyway?");
        if (confirm.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> removals = new ArrayList<>();
        for (WorktreeService.Worktree worktree : worktrees) {
            removals.add(worktreeService.removeForced(repository.root(), worktree.path(), deletableBranchOf(worktree))
                    .handle((v, failure) -> {
                        Platform.runLater(() -> {
                            if (failure != null) {
                                UiErrors.show("Could not remove worktree", failure);
                            } else {
                                viewModel.removeWorktreeStatus(worktree.path());
                            }
                        });
                        return null;
                    }));
        }
        CompletableFuture
                .allOf(removals.toArray(CompletableFuture[]::new))
                .whenComplete((v, ignored) -> Platform.runLater(() -> refreshWorktrees(repository, false)));
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
        // The name is agent-authored and can be a near-miss of a sibling's,
        // and the sidebar sorts by name so the impostor lands adjacent. The
        // working directory is what actually tells two sessions apart.
        confirm.setContentText("This removes the session from the manager (stopping it first if running). "
                + AgentLabels.displayName(agentRegistry, session)
                + "'s own conversation history on disk is not deleted."
                + "\n\nWorking directory: " + session.workingDirectory());
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
                    setContextMenu(unopenedWorktreeMenu(worktreeNode.repository(), worktreeNode.worktree()));
                }
                case SidebarNode.StaleWorktreesNode staleNode -> {
                    setGraphic(buildStaleRow(staleNode.worktrees(), staleNode.repository()));
                    setContextMenu(null);
                }
                case SidebarNode.LockedWorktreesNode lockedNode -> {
                    setGraphic(buildLockedRow(lockedNode.worktrees(), lockedNode.repository()));
                    setContextMenu(null);
                }
                case SidebarNode.PullRequestGroupNode groupNode -> {
                    setGraphic(buildPullRequestGroupRow(groupNode));
                    setContextMenu(null);
                }
                case SidebarNode.PullRequestNode pullRequestNode -> {
                    setGraphic(buildPullRequestRow(pullRequestNode));
                    setContextMenu(null);
                }
            }
        }

        private StackPane buildRepoRow(Repository repository) {
            Label caret = new Label("▶");
            caret.getStyleClass().add("repo-caret");
            boolean expanded = getTreeItem() != null && getTreeItem().isExpanded();
            caret.setRotate(expanded ? 90 : 0);

            Label name = new Label(repository.displayName());
            name.getStyleClass().add("repo-name");
            // Keep the truncation `name` had when it sat directly in the VBox:
            // inside an HBox it would otherwise take preferred width and let a
            // long repo name blow out the row.
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox nameRow = new HBox(6, name);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            if (repository.isRemote()) {
                nameRow.getChildren().add(buildRemoteChip(repository));
            }

            // When a transient rescan note is present it owns the whole line
            // (branch text = note, no counts).
            String note = rescanNotes.get(repository.id());
            Label branch = new Label(note != null ? note : "⎇ " + branchTextFor(repository));
            branch.getStyleClass().add("repo-branch");
            branch.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
            HBox.setHgrow(branch, Priority.ALWAYS);
            branch.setMinWidth(0);
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
            // Composed children, minus any row present only by exemption: an
            // exempt row did not match, so counting it would overstate the
            // matches, and a repo present ONLY by exemption must show no
            // count at all rather than a bare "0 of M".
            List<ManagedAgentSession> shown = getTreeItem() == null ? sessions
                    : getTreeItem().getChildren().stream()
                            .map(TreeItem::getValue)
                            .filter(SidebarNode.SessionNode.class::isInstance)
                            .map(node -> ((SidebarNode.SessionNode) node).session())
                            .filter(candidate -> !filter.isActive() || filter.matches(candidate))
                            .toList();
            boolean anyRunning = shown.stream().anyMatch(s -> SessionStatusStyles.isRunning(s.status()));
            HBox branchRow = new HBox(6, branch, counts);
            branchRow.setAlignment(Pos.CENTER_LEFT);
            if (anyRunning) {
                branchRow.getChildren().add(SessionStatusStyles.createDot(5, SessionStatus.RUNNING));
            }

            VBox text = new VBox(1, nameRow, branchRow);
            HBox.setHgrow(text, Priority.ALWAYS);

            Label count = new Label(!filtering() || shown.size() == sessions.size()
                    ? String.valueOf(sessions.size())
                    : shown.isEmpty() ? "" : shown.size() + " of " + sessions.size());
            count.getStyleClass().add("repo-count");

            Button rescan = new Button("⟳");
            rescan.getStyleClass().add("row-action-button");
            rescan.setTooltip(new Tooltip("Rescan worktrees"));
            rescan.setFocusTraversable(false);
            if (scanning.contains(repository.id()) || scanningPullRequests.contains(repository.id())) {
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
                refreshPullRequests(repository);
            });

            Button newSession = new Button("+");
            newSession.getStyleClass().add("row-action-button");
            newSession.setTooltip(new Tooltip("New session or worktree…"));
            newSession.setFocusTraversable(false);
            ContextMenu newMenu = newSessionMenu(repository);
            newSession.setOnAction(e -> newMenu.show(newSession, Side.BOTTOM, 0, 4));

            HBox actions = new HBox(2, rescan, newSession);
            actions.setAlignment(Pos.CENTER_RIGHT);

            HBox row = new HBox(7, caret, text, count);
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
            // Diag-only override folded into the same binding, not a
            // separate unbind path -- see diagForceHoverRow.
            actions.visibleProperty().bind(hoverProperty().or(
                    Bindings.createBooleanBinding(() -> row == diagForcedHoverRow.get(), diagForcedHoverRow)));
            return RowOverlay.wrap(row, actions);
        }

        /** Just the counts fragment: {@code · 3 wt · 2 locked · 1 stale}, or "" before discovery / when a note is showing. */
        private String repoCountsText(Repository repository) {
            if (rescanNotes.get(repository.id()) != null) {
                return "";
            }
            if (filter.isActive()) {
                return "";
            }
            SidebarChildren classified = childrenOf(repository);
            if (classified == null) {
                return "";
            }
            StringBuilder counts = new StringBuilder(" · ").append(classified.worktreeCount()).append(" wt");
            if (classified.lockedCount() > 0) {
                counts.append(" · ").append(classified.lockedCount()).append(" locked");
            }
            if (classified.staleCount() > 0) {
                counts.append(" · ").append(classified.staleCount()).append(" stale");
            }
            return counts.toString();
        }

        /**
         * The {@code ◨n} badge (spec §2): that checkout's open findings, and
         * a click that opens review on its LOCAL scope -- findings are
         * recorded against the checkout, so the local changes are what the
         * count is counting. Absent rather than zero when no reviewer has
         * run -- a confident zero would read as "reviewed, nothing found".
         *
         * <p>{@code onReview} rather than a fixed call because the same
         * badge sits on two row kinds with two different destinations: a
         * session row has a Review sub-tab to land on, an unopened worktree
         * row has to start a session first.</p>
         */
        private Optional<Label> findingsBadge(Path checkoutRoot, Runnable onReview) {
            return openFindingsAt.apply(checkoutRoot).map(count -> {
                Label badge = new Label("◨" + count);
                badge.getStyleClass().add("worktree-findings-badge");
                Tooltip.install(badge, new Tooltip(count + (count == 1 ? " open finding" : " open findings")
                        + " — click to review this worktree"));
                badge.setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY) {
                        onReview.run();
                        event.consume();
                    }
                });
                return badge;
            });
        }

        /** The eval-mode tooltip line, honest per provider: Pi reroutes, Claude and Codex are marked but not rerouted. */
        private static String evalTooltipLine(ManagedAgentSession session) {
            return switch (session.agentKind()) {
                case PI -> "Eval mode: on (x-target-account: eval via the Pi bridge extension)";
                case CLAUDE -> "Eval mode: on (x-target-account: eval via omlx_proxy)";
                case CODEX -> "Eval mode: on (not supported for Codex; requests are NOT rerouted)";
            };
        }

        private StackPane buildSessionRow(ManagedAgentSession session, Repository repository) {
            boolean live = SessionStatusStyles.isRunning(session.status());
            Region dot = SessionStatusStyles.createDot(8, session.status(), live);
            // Leading gutter: status dot, then the agent mark. Two independent
            // axes (is it running / what is it running), so two marks -- and
            // an HBox, because the StackPane this used to be would have stacked
            // the mark on top of the dot.
            HBox statusCol = new HBox(3, dot, AgentMarks.createMark(session));
            statusCol.getStyleClass().add("child-row-status");

            Label name = new Label(session.displayName());
            name.getStyleClass().add("session-name");
            // The name is agent-authored (session_rename) and can be 60 code
            // points of full-width CJK. A Label's min width is its pref
            // width, so without this the row -- and then the window -- takes
            // its minimum width from the title. The clamp resolves against
            // the HGROW'd `text` column below, not against the text.
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);

            // Branch tag (worktree handoff "Sidebar session rows"): ◫ accent
            // for a worktree checkout, ⎇ dim for the current checkout.
            boolean isWorktree = session.worktreeRoot().isPresent();
            GitStatus checkoutStatus = session.worktreeRoot()
                    .map(root -> viewModel.worktreeStatus(root).orElse(null))
                    .orElseGet(() -> viewModel.repoStatus(repository.id()).orElse(null));
            String branch = checkoutStatus != null ? UiFormats.branchText(checkoutStatus) : "…";

            // One line, name-first: the branch is capped so it yields
            // characters before the name does (see SidebarRowMetrics).
            HBox.setHgrow(name, Priority.ALWAYS);

            Label branchTag = new Label((isWorktree ? "◫ " : "⎇ ") + branch);
            branchTag.getStyleClass().add(isWorktree ? "branch-tag-worktree" : "branch-tag");
            branchTag.setMinWidth(0);
            branchTag.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
            HBox.setHgrow(branchTag, Priority.NEVER);

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
            if (prChip != null && session.prState() == PrState.OPEN) {
                // Only while the PR is OPEN: a merged PR has no pull-request
                // scope to show, and a chip that lands on the local one
                // instead would be a lie about what it reviews.
                Tooltip.install(prChip, new Tooltip("Review this pull request"));
                prChip.setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY) {
                        navigator.showReviewForSession(session.id(),
                                SessionReviewScopes.Choice.PULL_REQUEST);
                        event.consume();
                    }
                });
            }

            Button open = quickAction("↗", "Open", false, () -> navigator.resumeSession(session));
            Button stop = quickAction("■", "Stop process", true, () -> navigator.closeSession(session.id()));
            stop.setDisable(!SessionStatusStyles.isRunning(session.status()));
            Button delete = quickAction("×", "Delete session", true, () -> onDeleteSession(session));
            HBox actions = new HBox(2, open, stop, delete);
            actions.setAlignment(Pos.CENTER_RIGHT);

            HBox row = new HBox(8, statusCol, name);
            if (checkoutStatus != null && checkoutStatus.dirty()) {
                Region dirtyDot = new Region();
                dirtyDot.getStyleClass().add("dirty-dot");
                row.getChildren().add(dirtyDot);
            }
            if (prChip != null) {
                row.getChildren().add(prChip);
            }
            session.worktreeRoot().ifPresent(root ->
                    findingsBadge(root, () -> navigator.showReviewForSession(session.id(),
                            SessionReviewScopes.Choice.LOCAL))
                            .ifPresent(badge -> row.getChildren().add(badge)));
            // A session whose Claude is blocked on a human gets a badge: it
            // is the one state that makes no further progress until the user
            // comes back to it. Cleared by switching to the session.
            SessionActivity activity = viewModel.activityOf(session.id());
            if (activity == SessionActivity.NEEDS_ATTENTION) {
                Label attention = new Label("waiting");
                attention.getStyleClass().add("attention-badge");
                row.getChildren().add(attention);
            }
            if (session.evalMode()) {
                Label evalChip = new Label("eval");
                evalChip.getStyleClass().add("eval-badge");
                row.getChildren().add(evalChip);
            }
            row.getChildren().add(branchTag);
            branchTag.maxWidthProperty().bind(
                    row.widthProperty().map(w -> SidebarRowMetrics.branchTagMaxWidth(w.doubleValue())));
            row.getStyleClass().addAll("session-row", "child-row");
            row.setAlignment(Pos.CENTER_LEFT);
            if (viewModel.activeSession().filter(session.id()::equals).isPresent()) {
                row.getStyleClass().add("active");
            }
            Tooltip rowTip = sessionTooltips.computeIfAbsent(session.id(), key -> new Tooltip());
            String workingDirectoryText = session.worktreeRoot().isEmpty() && repository.isRemote()
                    ? repository.remote().host() + ":" + repository.remote().remotePath()
                    : session.workingDirectory().toString();
            rowTip.setText(session.displayName()
                    + "\nStatus: " + session.status()
                    + "\nAgent: " + AgentLabels.displayName(agentRegistry, session)
                    + (session.evalMode() ? "\n" + evalTooltipLine(session) : "")
                    + (activity == SessionActivity.UNKNOWN ? ""
                            : "\nActivity: "
                                    + activityLabel(activity))
                    + "\nLast opened: " + session.lastOpenedAt()
                    + "\nWorking directory: " + workingDirectoryText);
            if (filter.isActive() && !filter.matches(session) && isExempt(session.id())) {
                rowTip.setText(rowTip.getText()
                        + "\nShown because it is open — it does not match the current filter.");
            }
            Tooltip.install(row, rowTip);
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    navigator.resumeSession(session);
                    event.consume();
                }
            });
            // Diag-only override folded into the same binding, not a
            // separate unbind path -- see diagForceHoverRow.
            actions.visibleProperty().bind(hoverProperty().or(
                    Bindings.createBooleanBinding(() -> row == diagForcedHoverRow.get(), diagForcedHoverRow)));
            return RowOverlay.wrap(row, actions);
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
         * A discovered worktree with no session (worktree handoff), one
         * line like a session row: faint icon, branch name taking the
         * width, an accent Start ▸ pill and -- never on the main checkout
         * -- a one-click 🗑 that removes worktree + branch. The path moved
         * to the row's tooltip -- a second line would have broken the
         * single-line shape shared with session rows, and the tooltip
         * already carried it.
         */
        private HBox buildUnopenedRow(WorktreeService.Worktree worktree, Repository repository) {
            Label icon = new Label(worktree.mainCheckout() ? "⎇" : "◫");
            icon.getStyleClass().add("worktree-unopened-icon");
            StackPane statusCol = new StackPane(icon);
            statusCol.getStyleClass().add("child-row-status");

            String branch = worktree.branch().orElse(worktree.detached() ? "(detached)" : "(no branch)");
            Label name = new Label(branch);
            name.getStyleClass().add("worktree-unopened-branch");
            // Single line, name-first: the name takes the remaining width
            // (matching buildSessionRow). Without the min-width clamp, a
            // Label's min width is its pref width, and a long branch name
            // would set the row's -- and then the window's -- minimum width.
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);

            Label startPill = new Label("Start ▸");
            startPill.getStyleClass().add("start-pill");
            // Never shrink below its own text -- an HGROW'd sibling must not
            // be able to ellipsize this into "…".
            startPill.setMinWidth(Region.USE_PREF_SIZE);
            startPill.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    navigator.promptStartWorktreeSession(repository, worktree);
                    event.consume();
                }
            });

            HBox row = new HBox(8, statusCol, name, startPill);
            // A dirty dot when this checkout has uncommitted changes, matching
            // the session row's -- the worktree's state is independent of
            // whether a session is running in it.
            GitStatus unopenedStatus = viewModel.worktreeStatus(worktree.path()).orElse(null);
            if (unopenedStatus != null && unopenedStatus.dirty()) {
                Region dirtyDot = new Region();
                dirtyDot.getStyleClass().add("dirty-dot");
                row.getChildren().add(row.getChildren().indexOf(startPill), dirtyDot);
            }
            findingsBadge(worktree.path(), () -> navigator.startReviewForWorktree(repository, worktree,
                    SessionReviewScopes.Choice.LOCAL))
                    .ifPresent(badge -> row.getChildren().add(row.getChildren().indexOf(startPill), badge));
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
                    // Line up under the summary label: .child-row padding-left
                    // (16) + .child-row-status min-width (30) + this row's
                    // HBox spacing (7) = 53.
                    path.setPadding(new Insets(2, 8, 2, 53));
                    box.getChildren().add(path);
                }
            }
            return box;
        }

        /**
         * The collapsed locked bucket: {@code ▸ N locked worktrees} + a Clean
         * action. Like the stale bucket, but these worktrees are locked -- held
         * on purpose by whatever created them -- so cleaning force-removes them
         * and demands an explicit confirmation first (see
         * {@link #cleanLockedWorktrees}). Reuses the stale bucket's styling.
         */
        private VBox buildLockedRow(List<WorktreeService.Worktree> worktrees, Repository repository) {
            boolean expanded = lockedBucketExpanded.contains(repository.id());

            Label caret = new Label(expanded ? "▾" : "▸");
            caret.getStyleClass().add("repo-caret");
            StackPane statusCol = new StackPane(caret);
            statusCol.getStyleClass().add("child-row-status");
            Label label = new Label(worktrees.size() + (worktrees.size() == 1
                    ? " locked worktree" : " locked worktrees"));
            label.getStyleClass().add("stale-summary");
            HBox.setHgrow(label, Priority.ALWAYS);

            Button clean = new Button("Clean ↺");
            clean.getStyleClass().add("stale-clean-button");
            clean.setFocusTraversable(false);
            clean.setOnAction(e -> cleanLockedWorktrees(repository, worktrees));

            HBox summary = new HBox(7, statusCol, label, clean);
            summary.getStyleClass().addAll("stale-summary-row", "child-row");
            summary.setAlignment(Pos.CENTER_LEFT);
            summary.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    if (expanded) {
                        lockedBucketExpanded.remove(repository.id());
                    } else {
                        lockedBucketExpanded.add(repository.id());
                    }
                    requestRebuild();
                    event.consume();
                }
            });

            VBox box = new VBox(summary);
            if (expanded) {
                for (WorktreeService.Worktree worktree : worktrees) {
                    Label path = new Label(shortPath(worktree.path())
                            + worktree.lockReason().map(reason -> "  (" + reason + ")").orElse(""));
                    path.getStyleClass().add("stale-path");
                    // Line up under the summary label: .child-row padding-left
                    // (16) + .child-row-status min-width (30) + this row's
                    // HBox spacing (7) = 53.
                    path.setPadding(new Insets(2, 8, 2, 53));
                    box.getChildren().add(path);
                }
            }
            return box;
        }

        /**
         * The collapsed {@code PULL REQUESTS (n)} bucket header. Unlike the
         * stale/locked buckets, its expand state lives on the real {@code
         * TreeItem} (see {@link #pullRequestGroupItem}), so the caret here
         * just mirrors {@code getTreeItem().isExpanded()} and a click
         * toggles it -- except for an {@code Unavailable} scan, which has no
         * children to expand and instead re-runs the scan.
         */
        private HBox buildPullRequestGroupRow(SidebarNode.PullRequestGroupNode node) {
            Repository repository = node.repository();
            boolean unavailable = node.outcome() instanceof RepositoryPullRequests.Outcome.Unavailable;
            boolean expanded = !unavailable && getTreeItem() != null && getTreeItem().isExpanded();

            Label caret = new Label(unavailable ? "◧" : expanded ? "▾" : "▸");
            caret.getStyleClass().add("repo-caret");
            StackPane statusCol = new StackPane(caret);
            statusCol.getStyleClass().add("child-row-status");

            int shown = getTreeItem() != null ? getTreeItem().getChildren().size() : 0;
            Label label = new Label(pullRequestGroupText(node.outcome(), shown));
            label.getStyleClass().add("stale-summary");
            HBox.setHgrow(label, Priority.ALWAYS);
            if (node.outcome() instanceof RepositoryPullRequests.Outcome.Unavailable unavailableOutcome) {
                // The only diagnostic the user could act on -- the label
                // itself only ever says "unavailable · retry".
                label.setTooltip(new Tooltip(unavailableOutcome.message()));
            }

            HBox row = new HBox(7, statusCol, label);
            row.getStyleClass().addAll("stale-summary-row", "child-row", "pull-request-group-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                if (unavailable) {
                    // Immediate feedback for this click specifically (AGENTS.md):
                    // the outcome itself is unchanged until the scan lands, so
                    // nothing else would repaint this row in the meantime.
                    label.setText("PULL REQUESTS — checking…");
                    refreshPullRequests(repository);
                } else if (getTreeItem() != null) {
                    getTreeItem().setExpanded(!getTreeItem().isExpanded());
                }
                event.consume();
            });
            return row;
        }

        /**
         * One open pull request with no local worktree: an {@code ◧} icon,
         * its number/title/author, and a {@code Review ▸} pill -- styled
         * from the same {@code worktree-unopened-row} rules as an unopened
         * worktree row, so the two "not yet opened" row kinds read as
         * siblings.
         *
         * <p>The pill materializes the pull request: a worktree, {@code gh pr
         * checkout} into it, a session, and its review board. That takes a
         * whole-branch fetch, so while one is running the row says
         * "Opening…" and is disabled -- a second click would otherwise start
         * a second {@code git worktree add} at the same path, and the human
         * would be shown a collision failure for work that is succeeding.
         * The in-flight state is held by the sidebar, not the row, because
         * the row is rebuilt from the model while the fetch runs.</p>
         */
        private HBox buildPullRequestRow(SidebarNode.PullRequestNode node) {
            PullRequestMaterialization.Target target = new PullRequestMaterialization.Target(
                    node.repository().root(), node.pullRequest().number());
            boolean materializing = materializingPullRequests.isRunning(target);
            Label icon = new Label("◧");
            icon.getStyleClass().add("worktree-unopened-icon");
            StackPane statusCol = new StackPane(icon);
            statusCol.getStyleClass().add("child-row-status");

            Label text = new Label(pullRequestRowText(node.pullRequest()));
            text.getStyleClass().add("worktree-unopened-branch");
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            text.setTextOverrun(OverrunStyle.ELLIPSIS);

            Label reviewPill = new Label(materializing ? "Opening…" : "Review ▸");
            reviewPill.getStyleClass().add("start-pill");
            reviewPill.setMinWidth(Region.USE_PREF_SIZE);
            reviewPill.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    materializePullRequest(node);
                    event.consume();
                }
            });

            HBox row = new HBox(8, statusCol, text, reviewPill);
            row.getStyleClass().addAll("worktree-unopened-row", "child-row");
            row.setAlignment(Pos.CENTER_LEFT);
            // Disabling the whole row, not just the pill: while the fetch
            // runs, nothing on this row is a thing to press.
            row.setDisable(materializing);
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

    }

    // ---- Cached per-row context menus ---------------------------------------

    /** Applies {@code action} to the CURRENT version of the session, resolved through the model. */
    private void withLiveSession(ManagedSessionId sessionId, Consumer<ManagedAgentSession> action) {
        viewModel.sessionById(sessionId).ifPresent(action);
    }

    /**
     * What the {@code Review ▸} block of a row's context menu offers: the
     * local changes always, the pull request only when the checkout carries
     * one. Pure and static so the offer can be pinned without a pointer --
     * synthetic pointer input in this project's headless runs reports success
     * without reaching the app, so a robot click here would assert nothing.
     */
    static List<String> reviewMenuLabels(Optional<Integer> prNumber) {
        List<String> labels = new ArrayList<>();
        labels.add("Review ▸ Local changes");
        prNumber.ifPresent(number -> labels.add("Review ▸ PR #" + number));
        return List.copyOf(labels);
    }

    /** The open PR of the LIVE session, if it has one -- never a captured snapshot's. */
    private Optional<Integer> openPullRequestOf(ManagedSessionId sessionId) {
        return viewModel.sessionById(sessionId)
                .filter(session -> session.prState() == PrState.OPEN)
                .flatMap(ManagedAgentSession::prNumber);
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

            MenuItem reviewLocal = new MenuItem();
            reviewLocal.setOnAction(e -> navigator.showReviewForSession(id, SessionReviewScopes.Choice.LOCAL));
            MenuItem reviewPullRequest = new MenuItem();
            reviewPullRequest.setOnAction(e ->
                    navigator.showReviewForSession(id, SessionReviewScopes.Choice.PULL_REQUEST));

            ContextMenu menu = new ContextMenu();
            menu.getItems().addAll(resume, rename, stop, delete,
                    new SeparatorMenuItem(), reviewLocal, reviewPullRequest,
                    new SeparatorMenuItem(), reveal);
            // Relabelled on every showing, not once at build: this menu is
            // cached for the life of the row, and whether the checkout
            // carries an open pull request changes underneath it.
            menu.setOnShowing(e -> applyReviewMenuLabels(reviewLocal, reviewPullRequest, id));
            applyReviewMenuLabels(reviewLocal, reviewPullRequest, id);
            return menu;
        });
    }

    /** Re-reads {@link #reviewMenuLabels} for the live session and hides the PR entry when there is none. */
    private void applyReviewMenuLabels(MenuItem local, MenuItem pullRequest, ManagedSessionId sessionId) {
        List<String> labels = reviewMenuLabels(openPullRequestOf(sessionId));
        local.setText(labels.get(0));
        boolean hasPullRequest = labels.size() > 1;
        pullRequest.setText(hasPullRequest ? labels.get(1) : "");
        pullRequest.setVisible(hasPullRequest);
    }

    /**
     * The context menu of a discovered worktree that has no session yet. Only
     * the local scope: a checkout with no session has no pull-request scope
     * resolved for it either, and the entry starts a session before there is
     * anywhere to land.
     */
    private ContextMenu unopenedWorktreeMenu(Repository repository, WorktreeService.Worktree worktree) {
        return unopenedMenus.computeIfAbsent(worktree.path(), path -> {
            MenuItem review = new MenuItem(reviewMenuLabels(Optional.empty()).get(0));
            review.setOnAction(e -> navigator.startReviewForWorktree(repository, worktree,
                    SessionReviewScopes.Choice.LOCAL));
            return new ContextMenu(review);
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
            rescan.setOnAction(e -> {
                refreshWorktrees(repository, true);
                refreshPullRequests(repository);
            });
            return new ContextMenu(inCheckout, newWorktree, rescan);
        });
    }

    private ContextMenu repoMenu(Repository repository) {
        return repoMenus.computeIfAbsent(repository.id(), id -> {
            MenuItem newSession = new MenuItem("New session");
            newSession.setOnAction(e -> navigator.openNewSession(repository));

            MenuItem refresh = new MenuItem("Refresh");
            refresh.setOnAction(e -> {
                refreshStatus(repository);
                refreshWorktrees(repository, true);
                refreshPullRequests(repository);
            });

            MenuItem remove = new MenuItem("Remove from manager");
            remove.setOnAction(e -> onRemoveRepository(repository));

            ContextMenu menu = new ContextMenu();
            menu.getItems().add(newSession);
            if (!repository.isRemote()) {
                MenuItem newWorktree = new MenuItem("New worktree…");
                newWorktree.setOnAction(e -> onNewWorktree.accept(repository));

                MenuItem rescan = new MenuItem("Rescan worktrees");
                rescan.setOnAction(e -> {
                    refreshWorktrees(repository, true);
                    refreshPullRequests(repository);
                });

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
