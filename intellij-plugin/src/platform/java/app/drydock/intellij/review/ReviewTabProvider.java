package app.drydock.intellij.review;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.SessionActivityListener;
import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewScope;

import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The Drydock Review tab: the {@code changesViewContent} provider that
 * assembles the header ({@code <k> / <n> reviewed}), the tree (T12's {@link
 * ReviewTabBrowser}), the diff preview (T10's {@link ReviewDiffPreview})
 * and the status placeholder (T11's {@link ReviewTabStatus}) over one
 * review scope.
 *
 * <p><strong>Composition.</strong> The tab owns no review logic: the scope
 * comes from {@link ReviewTabScopes} (T8, minted unbound for a direct
 * open, Q7); the snapshot from {@link ReviewTabRefresher} (T11) over
 * {@link ReviewTabModel} (T3); the selection's texts from {@link
 * ReviewContents} (T7), built into a request by {@link ReviewDiffRequests}
 * (T6) with fragments from {@link ReviewFragments} (T5); the write side
 * is {@link ReviewVerdictCommands} (T9) — one command, three entry points
 * (Q13: the toolbar, the row context menu, and the preview's
 * {@code DiffUserDataKeysEx.CONTEXT_ACTIONS}). The annotation store and
 * the diff service are the backend's own instances (one writer, and
 * verdict digests measured by the same diff an agent sees).</p>
 *
 * <p><strong>Threading.</strong> Everything blocking — scope resolution,
 * contents, the diff, digests — runs on the plugin's background executor
 * or the services' own executors, never the EDT and never inside the tree
 * builder; the EDT receives only Swing: {@code preview.show}, the header
 * and the status placeholder. The first user-triggered load shows the
 * loading placeholder synchronously, before any background work starts,
 * and every terminal state replaces it — nothing stays stranded (AC22).</p>
 *
 * <p><strong>Predicate.</strong> The tab is visible only in windows a
 * Drydock session covers (Q8): see {@link ReviewTabVisibility}, the
 * entry's {@code predicateClassName}. The host tool window is resolved
 * through {@link ChangesViewContentManager}, never hard-coded
 * ({@code kb/find-review-tab-host-varies}). No {@code <module>} is
 * declared for the diff modules — they stay compile-only
 * ({@code kb/find-263-diff-api-internal}).</p>
 */
final class ReviewTabProvider implements ChangesViewContentProvider {

    private static final System.Logger SYS_LOG = System.getLogger(ReviewTabProvider.class.getName());

    /** Q15: the pinned tab name — the opener (T14) and the descriptor must use this exact string. */
    static final String TAB_NAME = "Drydock Review";

    /** The tree's share of the splitter (the preview takes the rest). */
    private static final float TREE_PROPORTION = 0.55f;

    private final Project project;
    private final DrydockPluginService plugin;

    // ---- assembled in initContent, on the EDT ----
    private JComponent panel;
    private JBLabel header;
    private JBLabel statusLabel;
    private ReviewTabBrowser browser;
    private ReviewDiffPreview.Host previewHost;
    private ReviewDiffPreview.SelectionPreview preview;
    private Disposable previewParent;
    private DebounceScheduler debounce;
    private AnAction markReviewedAction;
    private AnAction unmarkAction;
    private List<AnAction> contextActions;

    // ---- wired on the background load, once the backend is ready ----
    private WorktreeService worktrees;
    private GitStatusService git;
    private ReviewContents contents;
    private ReviewVerdictCommands commands;
    private ReviewTabScopes scopes;
    /** Volatile: the file/activity listeners and the Changes link read it from any thread. */
    private volatile ReviewTabRefresher refresher;
    private final AtomicBoolean wired = new AtomicBoolean();

    /**
     * The live provider of a project's Review tab, so the Changes link
     * ({@link ReviewTabOpener}) can ask an existing tab to re-run its diff.
     * Set in {@link #initContent}, cleared in {@link #disposeContent}.
     */
    private static final Key<ReviewTabProvider> LIVE_PROVIDER = Key.create("drydock.review.tabProvider");

    /**
     * AC15: set (before the reload) when the selected file changed after the
     * diff; while set, the reload's LOADING report renders the
     * changed-since state instead of the plain loading text, so the notice
     * is replaced only by the fresh result's terminal state.
     */
    private volatile boolean changedSincePending;
    /**
     * P2-A: the AC15 mismatch memo — the file and both contents of the
     * last mismatch that scheduled an automatic reload, so a repeat of
     * that exact failure is not reloaded again. See {@link MismatchMemo}.
     */
    private final MismatchMemo mismatchMemo = new MismatchMemo();
    /** A mark/unmark command is running: both actions are disabled and the status says so. */
    private final AtomicBoolean commandRunning = new AtomicBoolean();
    /** The last refresh state rendered (EDT only), restored once a command's progress clears. */
    private ReviewTabStatus lastState = ReviewTabStatus.LOADING;
    private String lastText = ReviewTabStatus.LOADING.message();

    private final AtomicBoolean disposed = new AtomicBoolean();
    /** The latest resolved scope; the commands and every reload target it. */
    private volatile ReviewScope scope;
    /** The latest snapshot; the file row of a selected finding is looked up in it. */
    private volatile ReviewTabModel.Snapshot snapshot;
    /** The latest selected row (a model FileRow or FindingRow); the commands target it. */
    private volatile Object selectionRow;

    /** Project-area EP: the platform injects the owning project here (live-verified in the spike). */
    ReviewTabProvider(Project project) {
        this.project = Objects.requireNonNull(project, "project");
        this.plugin = DrydockPluginService.getInstance();
    }

    @Override
    public @NotNull JComponent initContent() {
        if (panel != null && !disposed.get()) {
            return panel; // the platform asked twice for the same content
        }
        disposed.set(false);

        header = new JBLabel();
        header.setBorder(new EmptyBorder(4, 8, 4, 8));
        statusLabel = new JBLabel();
        statusLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
        statusLabel.setVisible(false);
        browser = new ReviewTabBrowser(project, this::selected);

        // The host tool window is a property of the tab name, resolved —
        // never hard-coded (kb/find-review-tab-host-varies): the Commit
        // window is only one of the possible hosts.
        String toolWindowId = ChangesViewContentManager.getToolWindowIdFor(project, TAB_NAME);
        boolean contentVertical = ChangesViewContentManager.isToolWindowTabVertical(project, toolWindowId);
        boolean splitter = ChangesViewContentManager.shouldHaveSplitterDiffPreview(project, contentVertical);
        SYS_LOG.log(Level.INFO, "Drydock Review tab '" + TAB_NAME + "' hosted in tool window '" + toolWindowId
                + "' (contentVertical=" + contentVertical + ", splitter=" + splitter + ")");

        previewParent = Disposer.newDisposable();
        previewHost = ReviewDiffPreview.createHost(project, previewParent, splitter, null);
        preview = new ReviewDiffPreview.SelectionPreview(previewHost);
        // Coordinator decision (T13): the preview panel is embedded in the
        // tab in BOTH branches — beside the tree when the content is
        // horizontal, below it in a vertical splitter when the content is
        // vertical (the default left-docked Commit window). The platform's
        // editor-tab machinery is internal-visibility on 263, so within this
        // task's file ownership the tab is the only surface; the placement
        // rule decides the splitter's orientation, and AC24's live check
        // may swap the editor-tab host half without touching this shape.
        JComponent previewComponent = ReviewDiffPreview.component(previewHost);
        OnePixelSplitter split = new OnePixelSplitter(contentVertical, TREE_PROPORTION);
        split.setFirstComponent(browser);
        split.setSecondComponent(previewComponent);

        JBPanel<?> content = new JBPanel<>(new BorderLayout());
        content.add(header, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(statusLabel, BorderLayout.SOUTH);

        // Q13: one command, three entry points — toolbar, row context menu,
        // and the preview's CONTEXT_ACTIONS (added when a request is built).
        markReviewedAction = new MarkReviewedAction();
        unmarkAction = new UnmarkAction();
        AnAction refresh = new RefreshAction();
        contextActions = List.of(markReviewedAction, unmarkAction);
        DefaultActionGroup contextMenu = new DefaultActionGroup();
        contextMenu.add(markReviewedAction);
        contextMenu.add(unmarkAction);
        contextMenu.addSeparator();
        contextMenu.add(refresh);
        browser.getViewer().installPopupHandler(contextMenu);
        browser.addToolbarAction(markReviewedAction);
        browser.addToolbarAction(unmarkAction);
        browser.addToolbarSeparator();
        browser.addToolbarAction(refresh);

        debounce = new DebounceScheduler();
        panel = content;
        project.putUserData(LIVE_PROVIDER, this);
        subscribeRefreshTriggers(previewParent);

        // AC22 / repository rule: the first user-triggered load shows the
        // loading placeholder synchronously, before any background work is
        // scheduled — the click visibly does something before the result.
        renderStatus(ReviewTabStatus.LOADING, ReviewTabStatus.LOADING.message());
        submitLoad();
        return content;
    }

    @Override
    public void disposeContent() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        if (project.getUserData(LIVE_PROVIDER) == this) {
            project.putUserData(LIVE_PROVIDER, null);
        }
        // The refresher first: it unregisters both listeners (the store's
        // and the registry's, each isolated) and cancels pending timers, so
        // a late event cannot reach a half-disposed tab.
        ReviewTabRefresher refresher = this.refresher;
        if (refresher != null) {
            refresher.dispose();
        }
        if (previewHost != null) {
            previewHost.dispose();
        }
        if (previewParent != null) {
            Disposer.dispose(previewParent);
        }
        if (browser != null) {
            browser.dispose(); // stops the async tree's scope: no tree updates a closed tab
        }
        if (debounce != null) {
            debounce.shutdown();
        }
        closeQuietly(worktrees);
        closeQuietly(git);
        // A clean slate, so a platform re-init of this content rebuilds everything.
        panel = null;
        header = null;
        statusLabel = null;
        browser = null;
        previewHost = null;
        preview = null;
        previewParent = null;
        debounce = null;
        markReviewedAction = null;
        unmarkAction = null;
        contextActions = null;
        worktrees = null;
        git = null;
        contents = null;
        commands = null;
        scopes = null;
        refresher = null;
        scope = null;
        snapshot = null;
        selectionRow = null;
        changedSincePending = false;
        mismatchMemo.reset();
        commandRunning.set(false);
        wired.set(false);
    }

    // ---- the load path ------------------------------------------------------

    /**
     * Resolves the tab's scope and starts the first refresh. Runs on the
     * plugin's background executor — never the EDT: the scope resolution
     * spawns git. Every outcome ends in a terminal placeholder state (AC22)
     * or the refresher's own loading-to-terminal cycle, so no path leaves
     * the loading placeholder stranded: while the service is still
     * initializing the load re-submits itself on completion rather than
     * declaring a failure it cannot know yet.
     */
    private void load() {
        if (disposed.get()) {
            return;
        }
        ReviewBackend backend = plugin.reviewBackend().orElse(null);
        if (backend == null || !backend.ready()) {
            if (!plugin.initialization().isDone()) {
                plugin.initialization().whenComplete((v, error) -> submitLoad());
                return;
            }
            onEdt(() -> renderStatus(ReviewTabStatus.BACKEND_NOT_READY,
                    ReviewTabStatus.BACKEND_NOT_READY.message()));
            return;
        }
        Path base = basePath();
        if (base == null || !ReviewTabVisibility.covers(plugin, base)) {
            // Q8's in-tab half: the predicate hides the tab in windows with
            // no covering session; if the platform does not re-evaluate it,
            // this state is what those windows see instead.
            onEdt(() -> renderStatus(ReviewTabStatus.NO_SESSION, ReviewTabStatus.NO_SESSION.message()));
            return;
        }
        wire(backend);
        scopes.resolve(base, Optional.empty()) // a direct open mints unbound (Q7)
                .whenComplete((scope, error) -> {
                    if (disposed.get()) {
                        return;
                    }
                    if (error != null) {
                        ReviewTabStatus status = statusOf(error);
                        if (status == ReviewTabStatus.DIFF_FAILED) {
                            SYS_LOG.log(Level.WARNING, "The Review tab could not resolve its scope", error);
                        }
                        ReviewTabStatus state = status;
                        onEdt(() -> renderStatus(state, state.message(error)));
                        return;
                    }
                    ReviewTabProvider.this.scope = scope;
                    // reload reports LOADING synchronously before any
                    // background work starts, and every outcome of it is
                    // terminal (T11) — the placeholder always clears.
                    refresher.reload(scope);
                });
    }

    /**
     * Builds the tab's review wiring against the ready backend — once. The
     * annotation store and the diff service are the backend's own
     * instances; the read-only git services ({@link WorktreeService},
     * {@link GitStatusService}) are this tab's own (the backend exposes
     * no accessors for them), closed in {@link #disposeContent}.
     */
    private void wire(ReviewBackend backend) {
        if (!wired.compareAndSet(false, true)) {
            return;
        }
        AnnotationStore store = backend.annotationStore();
        worktrees = new WorktreeService();
        git = new GitStatusService();
        contents = new ReviewContents(new GitExecutableLocator(), git);
        commands = new ReviewVerdictCommands(backend.diffService(), git, store, plugin.executor());
        Supplier<List<ManagedAgentSession>> sessions =
                () -> ReviewTabVisibility.sessions(plugin);
        scopes = new ReviewTabScopes(worktrees, git, backend.scopeRegistry(), sessions);
        ReviewTabRefresher refresher = new ReviewTabRefresher(
                plugin.executor(),
                debounce,
                scope -> backend.diffService().diff(scope.diffRoot(), scope.diffScope(), scope.base(),
                        DiffService.REVIEW_CONTEXT_LINES),
                // The base commit's git rev-parse runs once per reload; the
                // marks path reuses the cached value and spawns no git (AC21).
                contents::baseCommit,
                (scope, diff, baseCommit) -> new ReviewTabModel.Inputs(
                        diff,
                        store.forScope(scope.id()),
                        store.verdictsFor(scope.id()),
                        baseCommit),
                ReviewTabModel::snapshot,
                this::snapshotArrived,
                (state, text) -> onEdt(() -> refreshStatus(state, text)));
        this.refresher = refresher;
        // The two refresh listeners (T11): putVerdict fires with a null key,
        // so neither can filter — both coalesce into one marks recompute.
        refresher.onDispose(store.addChangeListener(key -> refresher.marksChanged()));
        refresher.onDispose(backend.scopeRegistry().addChangeListener(scopeId -> refresher.marksChanged()));
    }

    // ---- snapshots and status (the EDT half) --------------------------------

    /** The refresher's snapshot consumer: stores first, renders on the EDT. */
    private void snapshotArrived(ReviewTabModel.Snapshot snapshot) {
        this.snapshot = snapshot;
        onEdt(() -> {
            ReviewTabBrowser browser = this.browser;
            JBLabel header = this.header;
            if (browser == null || header == null) {
                return;
            }
            browser.accept(snapshot);
            header.setText(snapshot.headerText());
        });
    }

    /**
     * The refresher's status callback (EDT): a LOADING report while the
     * AC15 changed-since notice is pending keeps the notice on screen; any
     * terminal state — the fresh result — replaces it.
     */
    private void refreshStatus(ReviewTabStatus state, String text) {
        boolean changedSince = changedSincePending;
        if (state != ReviewTabStatus.LOADING) {
            changedSincePending = false;
        }
        renderStatus(state, ReviewTabStatus.placeholderText(state, text, changedSince));
    }

    /** Renders a state's text; READY clears the placeholder outright (AC22). EDT only. */
    private void renderStatus(ReviewTabStatus state, String text) {
        lastState = state;
        lastText = text;
        if (commandRunning.get()) {
            return; // the command's progress text stays until the command ends, which re-renders this
        }
        renderStatusText(state == ReviewTabStatus.READY ? null : text);
    }

    /** Renders placeholder text; {@code null} hides it (READY, or no message to show). EDT only. */
    private void renderStatusText(String text) {
        JBLabel status = statusLabel;
        if (status == null) {
            return;
        }
        if (text == null) {
            status.setText("");
            status.setVisible(false);
        } else {
            status.setText(text);
            status.setVisible(true);
        }
    }

    // ---- selection -> preview ----------------------------------------------

    /**
     * The browser's selection consumer (EDT): records the selection for the
     * commands and the preview's staleness token, then builds the file's
     * request on the background executor — its texts are blocking git
     * reads (T7), never EDT work.
     */
    private void selected(Object row) {
        selectionRow = row;
        ReviewDiffPreview.SelectionPreview preview = this.preview;
        if (preview == null) {
            return; // the tab is disposed: nothing is selected anywhere
        }
        preview.selection(row);
        ReviewTabModel.FileRow file = fileRowOf(row);
        ReviewScope scope = this.scope;
        ReviewContents contents = this.contents;
        if (file == null || scope == null || contents == null) {
            return; // nothing to preview yet: the load that follows reads fresh state
        }
        plugin.executor().execute(() -> buildPreview(scope, row, file));
    }

    /**
     * Builds one file's preview request off the EDT: the two texts, the
     * AC15 row check against both, then the fragments and the plan. The
     * platform request is assembled on the EDT (its documents and contents
     * are Swing-side) and shown only if the selection is still current.
     */
    private void buildPreview(ReviewScope scope, Object selectionId, ReviewTabModel.FileRow row) {
        if (disposed.get()) {
            return;
        }
        UnifiedDiff.FileDiff file = row.diff();
        String left = contents.leftText(scope, file);
        String right = contents.rightText(scope, file);
        if (!ReviewContents.matchesRows(left, file, false) || !ReviewContents.matchesRows(right, file, true)) {
            // AC15: the file changed after the diff — the computer is not
            // fed, the tab shows the changed-since state, and exactly one
            // re-run is scheduled (the refresher debounces and keeps at
            // most one diff in flight, with one follow-up).
            ReviewTabRefresher refresher = this.refresher;
            if (refresher == null) {
                return;
            }
            // The flag before the reload: the reload's synchronous LOADING
            // report lands on the EDT after this and must not erase the
            // notice — only the fresh result's terminal state replaces it.
            changedSincePending = true;
            onEdt(() -> renderStatus(ReviewTabStatus.LOADING, ReviewTabStatus.CHANGED_SINCE_MESSAGE));
            if (!mismatchMemo.schedulesReload(file.path(), left, right)) {
                // P2-A: the memoized failure — the same file with the same
                // contents on both sides as the mismatch that already
                // scheduled a reload. Such a file can never match git's
                // diff text (a symlink, a filter/lfs/working-tree-encoding
                // file, or a lone-CR file), so another automatic reload
                // would only rebuild the tree and re-select this file into
                // the same mismatch — the ~300 ms loop. The notice stays on
                // screen; the memo clears on a genuine change signal (a
                // file change, a turn end, a manual Refresh, or a fresh
                // matching selection — all of which reset it).
                return;
            }
            refresher.reload(scope);
            return;
        }
        mismatchMemo.reset(); // fresh data matched: the next mismatch again means a change
        ReviewDiffRequests.Plan plan = planFor(selectionId, file, left, right,
                ReviewFragments.fragmentsFor(file, left, right));
        onEdt(() -> {
            if (disposed.get() || previewParent == null || preview == null) {
                return;
            }
            SimpleDiffRequest request = ReviewDiffRequests.toRequest(project, previewParent, plan);
            // Q13's third entry point: the preview's own toolbar runs the
            // same commands as the tab's toolbar and the row context menu.
            request.putUserData(DiffUserDataKeysEx.CONTEXT_ACTIONS, contextActions);
            preview.requestReady(selectionId, request);
        });
    }

    /**
     * The plan for a selection: a finding row scrolls to its own line key
     * (a malformed persisted key falls back to the file's own scroll — it
     * is a value, not a crash), a file row to its first changed line.
     */
    private static ReviewDiffRequests.Plan planFor(Object row, UnifiedDiff.FileDiff file,
                                                   CharSequence left, CharSequence right,
                                                   List<LineFragment> fragments) {
        if (row instanceof ReviewTabModel.FindingRow finding) {
            try {
                return ReviewDiffRequests.plan(left, right, fragments,
                        ReviewDiffRequests.scrollTarget(finding.startKey()));
            } catch (IllegalArgumentException e) {
                // fall through: the file's own scroll target
            }
        }
        return ReviewDiffRequests.plan(file, left, right, fragments);
    }

    /**
     * The file row a selection addresses: a file row itself, or the file
     * row of a selected finding (look up in the latest snapshot — the
     * finding's own file, wherever the snapshot lists it).
     */
    private ReviewTabModel.FileRow fileRowOf(Object row) {
        if (row instanceof ReviewTabModel.FileRow file) {
            return file;
        }
        if (row instanceof ReviewTabModel.FindingRow finding) {
            ReviewTabModel.Snapshot current = snapshot;
            if (current == null) {
                return null;
            }
            return current.files().stream()
                    .filter(file -> file.path().equals(finding.annotation().file()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    // ---- the write side (Q13: one command, three entry points) -------------

    /**
     * Runs the mark/unmark command for the current selection. Never blocks
     * the caller (the EDT): the command's own future carries the work. A
     * refusal is a sentence written for the human (T9) and is shown; a
     * failure is shown with a never-blank diagnosis — "Something went
     * wrong" is not one. Success needs no message: the store listener
     * coalesces into one marks recompute and the tree refreshes itself.
     */
    private void runCommand(boolean mark) {
        ReviewVerdictCommands commands = this.commands;
        ReviewScope scope = this.scope;
        ReviewTabModel.FileRow row = fileRowOf(selectionRow);
        if (commands == null || scope == null || row == null) {
            return; // nothing selected to command; the tree's own state says so
        }
        if (!commandRunning.compareAndSet(false, true)) {
            return; // one command at a time: the actions are disabled while one runs
        }
        String path = row.path();
        // Progress at once (repository rule): both actions disable (their
        // update() reads commandRunning) and the status says what runs;
        // every completion path below clears it.
        renderStatusText((mark ? "Marking " : "Unmarking ") + path + " reviewed…");
        CompletableFuture<ReviewVerdictCommands.Result> future;
        try {
            future = mark
                    ? commands.markReviewed(scope, row.diff())
                    : commands.unmark(scope, row.diff());
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((result, error) -> onEdt(() -> reportCommand(mark, path, result, error)));
    }

    /** Clears the command's progress and shows its outcome; EDT only (its callers hop). */
    private void reportCommand(boolean mark, String path, ReviewVerdictCommands.Result result, Throwable error) {
        commandRunning.set(false);
        if (statusLabel != null) {
            renderStatus(lastState, lastText); // the refresh state the command's progress covered
        }
        if (disposed.get()) {
            return;
        }
        if (error != null) {
            Messages.showErrorDialog(project, "Could not " + (mark ? "mark " : "unmark ") + path
                    + (mark ? " reviewed: " : ": ") + rootMessage(error), "Drydock Review");
            return;
        }
        if (result instanceof ReviewVerdictCommands.Result.Refused refused) {
            Messages.showWarningDialog(project, refused.reason(), "Drydock Review");
        }
    }

    /**
     * The one-line diagnosis of a failure, never blank: the message, or
     * the type when the failure carries none. The same discipline as the
     * app's {@code UiErrors.message} — which is JavaFX-bound and not part
     * of the plugin, so this is the tab's two-line form of it.
     */
    private static String rootMessage(Throwable failure) {
        Throwable t = failure;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    // ---- refresh triggers (spec "Refresh", AC21) -----------------------------

    /**
     * File changes re-run the diff: VFS changes under the tab's checkout
     * (git's own directory excluded — a diff touching the index must not
     * trigger itself), and the end of a covering agent's turn (the edge
     * {@code HostedFileRefresher} also rides — an agent's edits are done
     * then, even where no VFS refresh has seen them yet). Both are
     * event-driven: they save no documents (Q9 is for user-triggered loads)
     * and go through {@link ReviewTabRefresher#reload}, which debounces a
     * burst into one run and keeps at most one diff in flight. The
     * connections live on {@code parent}, disposed with the content.
     */
    private void subscribeRefreshTriggers(Disposable parent) {
        project.getMessageBus().connect(parent).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                filesChanged(events);
            }
        });
        ApplicationManager.getApplication().getMessageBus().connect(parent)
                .subscribe(SessionActivityListener.TOPIC, (SessionActivityListener) this::activityChanged);
    }

    /** VFS listener (EDT or a write-action thread): memory only, then a debounced reload. */
    private void filesChanged(List<? extends VFileEvent> events) {
        ReviewScope scope = this.scope;
        ReviewTabRefresher refresher = this.refresher;
        if (disposed.get() || scope == null || refresher == null) {
            return; // nothing loaded yet: the pending first load reads fresh state
        }
        Path root = scope.diffRoot().toAbsolutePath().normalize();
        for (VFileEvent event : events) {
            if (underCheckout(root, event.getPath())) {
                mismatchMemo.reset(); // a genuine change: a mismatch may again mean new content
                refresher.reload(scope);
                return;
            }
        }
    }

    /** Whether {@code path} lies in {@code root}'s working tree and outside any {@code .git} directory. Lexical. */
    static boolean underCheckout(Path root, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        Path candidate;
        try {
            candidate = Path.of(path).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return false;
        }
        if (!candidate.startsWith(root)) {
            return false;
        }
        for (Path element : root.relativize(candidate)) {
            if (element.toString().equals(".git")) {
                return false;
            }
        }
        return true;
    }

    /**
     * P2-A: the AC15 mismatch memo. Some files can never match git's diff
     * text — a symlink (git shows the link target; the working-tree read
     * follows it), a file behind {@code ident}/{@code filter} (git-lfs) or
     * {@code working-tree-encoding} attributes, a lone-CR file (git splits
     * lines on LF only) — so the AC15 content check fails them on every
     * selection. Unchecked, that fed the loop: mismatch → reload → tree
     * rebuild → re-select → mismatch, re-running the diff about every
     * {@value ReviewTabRefresher#RELOAD_DEBOUNCE_MS} ms while the file
     * stayed selected.
     *
     * <p>The memo records the file and both contents of the mismatch that
     * scheduled a reload; an automatic reload is scheduled only for a
     * failure that differs from the memoized one. A genuine change signal
     * — a file change, a turn end, a manual Refresh, or a fresh matching
     * selection — resets it ({@link #reset()}), and a real content change
     * fails the equality check on its own. Pure decision logic, no platform
     * class, so the policy is testable headless; synchronized because the
     * preview executor writes it and any listener thread may clear it.</p>
     */
    static final class MismatchMemo {

        /** The mismatch that scheduled the last automatic reload: its bar for the next. */
        private record Failed(String path, String left, String right) { }

        private Failed failed;

        /**
         * Whether an automatic reload should still be scheduled for this
         * mismatch, recording it as the bar the next must differ from.
         * Exactly one reload per identical failure: the first call records
         * and answers {@code true}; a repeat of the same file with the same
         * contents on both sides answers {@code false} until
         * {@link #reset()}.
         */
        synchronized boolean schedulesReload(String path, String left, String right) {
            if (failed != null && failed.path().equals(path)
                    && failed.left().equals(left) && failed.right().equals(right)) {
                return false;
            }
            failed = new Failed(path, left, right);
            return true;
        }

        /** Clears the memo: a genuine change signal arrived, so a mismatch may again schedule a reload. */
        synchronized void reset() {
            failed = null;
        }
    }

    /**
     * Activity listener (any thread): a covering session leaving BUSY ended
     * its turn — its edits are done, so the diff re-runs (debounced).
     */
    private void activityChanged(ManagedSessionId sessionId, Optional<SessionActivity> activity) {
        ReviewScope scope = this.scope;
        ReviewTabRefresher refresher = this.refresher;
        if (disposed.get() || scope == null || refresher == null
                || activity.isEmpty() || activity.get() == SessionActivity.BUSY) {
            return;
        }
        Path root = scope.diffRoot().toAbsolutePath().normalize();
        for (ManagedAgentSession session : ReviewTabVisibility.sessions(plugin)) {
            if (session.id().equals(sessionId)
                    && session.workingDirectory().toAbsolutePath().normalize().equals(root)) {
                mismatchMemo.reset(); // the turn's edits are done: a mismatch may again mean new content
                refresher.reload(scope);
                return;
            }
        }
    }

    /**
     * The Changes link's re-run (Q9 user-triggered load): saves open
     * documents on the EDT, then re-runs the diff of the live tab's scope.
     * A tab whose first load has not resolved its scope yet has nothing to
     * re-run — that load reads fresh state. EDT.
     */
    static void reloadFromChangesLink(Project project) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null) {
            SYS_LOG.log(Level.INFO, "The Changes link found no live '" + TAB_NAME + "' content in "
                    + project.getName() + "; the tab's own first load computes the diff");
            return;
        }
        provider.userReload();
    }

    /** A user-triggered re-run (Refresh action, Changes link): save first (Q9, EDT), then reload. */
    private void userReload() {
        if (disposed.get()) {
            return;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        ReviewScope scope = this.scope;
        ReviewTabRefresher refresher = this.refresher;
        if (scope == null || refresher == null) {
            return;
        }
        mismatchMemo.reset(); // manual Refresh always reloads, and a mismatch after it may again mean a change
        refresher.reload(scope);
    }

    // ---- helpers ------------------------------------------------------------

    private Path basePath() {
        String base = project.getBasePath();
        return base == null ? null : Path.of(base);
    }

    /**
     * The terminal state a scope-resolution failure maps to: not a git
     * checkout (outside a repository, not listed as a worktree, or an
     * unreadable path) for the checkout-shape failures; the diff-failed
     * state (which carries the cause's message) for anything else, logged
     * at WARNING by the caller.
     */
    private static ReviewTabStatus statusOf(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof NotAGitRepositoryException
                || cause instanceof IllegalStateException
                || cause instanceof UncheckedIOException
                || cause instanceof IOException) {
            return ReviewTabStatus.NOT_GIT;
        }
        return ReviewTabStatus.DIFF_FAILED;
    }

    /** Schedules {@link #load} on the plugin executor; a shutdown race is ignored, not thrown. */
    private void submitLoad() {
        try {
            plugin.executor().execute(this::load);
        } catch (RejectedExecutionException e) {
            SYS_LOG.log(Level.WARNING, "Review tab load not scheduled: plugin executor is shut down");
        }
    }

    private static void onEdt(Runnable runnable) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDispatchThread()) {
            runnable.run();
        } else {
            application.invokeLater(runnable);
        }
    }

    /** Closes a tab-owned service, isolated: one failure never skips the rest. */
    private static void closeQuietly(AutoCloseable service) {
        if (service == null) {
            return;
        }
        try {
            service.close();
        } catch (Exception e) {
            SYS_LOG.log(Level.WARNING, "Closing the review tab's "
                    + service.getClass().getSimpleName() + " failed", e);
        }
    }

    // ---- the tab's actions ---------------------------------------------------

    /** Toolbar / context menu / preview: approve every hunk of the selected file (T9). */
    private final class MarkReviewedAction extends AnAction {
        MarkReviewedAction() {
            super("Mark Reviewed", "Approve every hunk of the selected file", AllIcons.Actions.Checked);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            runCommand(true);
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabled(!commandRunning.get());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** Toolbar / context menu / preview: clear the human approvals of the selected file (T9, Q14). */
    private final class UnmarkAction extends AnAction {
        UnmarkAction() {
            super("Unmark Reviewed", "Clear your approvals of the selected file", AllIcons.Actions.Undo);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            runCommand(false);
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabled(!commandRunning.get());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /**
     * The refresh action: a user-triggered load, so open documents are
     * saved first (Q9) — here on the EDT, before the background re-diff
     * starts (the refresher reload reports LOADING synchronously).
     */
    private final class RefreshAction extends AnAction {
        RefreshAction() {
            super("Refresh", "Save open documents and re-run the review diff", AllIcons.Actions.Refresh);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            ReviewScope scope = ReviewTabProvider.this.scope;
            ReviewTabRefresher refresher = ReviewTabProvider.this.refresher;
            if (scope == null || refresher == null) {
                FileDocumentManager.getInstance().saveAllDocuments();
                // No scope yet (still loading, or the first load never
                // completed): re-run the whole load path, which shows its
                // own placeholder and terminal state.
                renderStatus(ReviewTabStatus.LOADING, ReviewTabStatus.LOADING.message());
                submitLoad();
                return;
            }
            userReload();
        }
    }

    /**
     * The refresher's production scheduler: one daemon thread for the
     * debounce/coalesce windows, cancellable per task, shut down with the
     * tab. Never real time is slept here — that discipline is the fake
     * scheduler's, in the refresher's tests.
     */
    private static final class DebounceScheduler implements ReviewTabRefresher.Scheduler {

        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "drydock-review-tab-refresh");
            thread.setDaemon(true);
            return thread;
        });

        @Override
        public ReviewTabRefresher.Cancelable schedule(Runnable task, long delayMs) {
            ScheduledFuture<?> future = scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        void shutdown() {
            scheduler.shutdownNow();
        }
    }
}
