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
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.agent.SessionActivityListener;
import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;

import com.intellij.diff.fragments.LineFragment;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
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
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The dedicated Drydock Review window's content (P3): the header
 * ({@code <k> / <n> reviewed}), the tree ({@link ReviewTabBrowser}) and the
 * status placeholder ({@link ReviewTabStatus}) over one review scope —
 * header, tree and status only. The P2 in-panel splitter and diff preview
 * are gone: the operator rejected both, and a file selection now opens the
 * project-owned reusable editor-area diff ({@link ReviewDiffEditor}, T6)
 * whose active viewer the inlay layer ({@link ReviewDiffInlays}, T6)
 * decorates with one finding card per unresolved finding.
 *
 * <p><strong>Composition.</strong> The tab owns no review logic: the scope
 * comes from {@link ReviewTabScopes}; the snapshot from {@link
 * ReviewTabRefresher} over {@link ReviewTabModel}; the selection's texts
 * from {@link ReviewContents}, built into a titled request by {@link
 * ReviewDiffRequests} with fragments from {@link ReviewFragments}; the
 * finding cards' write side is {@link ReviewTriageActions} (T4), wired
 * through {@link TriageCards} below; the mark/unmark write side is {@link
 * ReviewVerdictCommands} — one command, two entry points (the toolbar and
 * the row context menu; the preview's third entry point left with the
 * preview). The annotation store and the diff service are the backend's own
 * instances (one writer, and verdict digests measured by the same diff an
 * agent sees).</p>
 *
 * <p><strong>The selection pipeline.</strong> A tree selection runs the
 * file's git reads (both texts, the row checks, the fragments) on the
 * plugin's background executor; the EDT continuation builds the titled
 * {@link ReviewDiffRequests.RequestContext}, feeds the inlay layer the
 * file's {@link ReviewDiffInlays.Decorations} (every unresolved finding of
 * the file, CONFIRMED and DISMISSED included, so Undo stays reachable) and
 * sends only the current selection's request through {@link
 * ReviewDiffEditor#selection}/{@link ReviewDiffEditor#requestReady} — a
 * build that lands after a newer selection is dropped by both the editor
 * and the generation gate ({@link DecorationGate}), so a late completion
 * never repaints the previous file's diff, never overwrites a fresher
 * refresh's decorations with a stale same-path read, and never applies
 * another scope's or another file's computed decorations.</p>
 *
 * <p><strong>Gutter navigation.</strong> The opener ({@link
 * ReviewTabOpener}, T8) records a finding target on the project before the
 * window's content exists; every snapshot arrival consumes it ({@link
 * ReviewTabOpener#takePendingFinding}) and hands it to {@link
 * ReviewTabBrowser#selectFinding}, whose selection then drives the diff
 * open exactly as a click on the row would.</p>
 *
 * <p><strong>Threading.</strong> Everything blocking — scope resolution,
 * contents, the diff, digests, store reads, prompt submission — runs on the
 * plugin's background executor or the services' own executors, never the
 * EDT and never inside the tree builder; the EDT receives only Swing: the
 * editor's selection/request handoff, the inlay layer's decorations, the
 * header and the status placeholder. The first user-triggered load shows
 * the loading placeholder synchronously, before any background work
 * starts, and every terminal state replaces it — nothing stays stranded
 * (AC22).</p>
 *
 * <p><strong>Visibility.</strong> The window is available only in projects
 * a Drydock session covers (Q8): see {@link ReviewTabVisibility} and
 * {@link ReviewToolWindowFactory}. No {@code <module>} is declared for the
 * diff modules — they stay compile-only ({@code kb/find-263-diff-api-internal}).</p>
 */
final class ReviewTabProvider {

    private static final System.Logger SYS_LOG = System.getLogger(ReviewTabProvider.class.getName());

    /** Q15: the pinned window name — the opener (T8) and the descriptor must use this exact string. */
    static final String TAB_NAME = "Drydock Review";

    private final Project project;
    private final DrydockPluginService plugin;

    /**
     * How an ask-to-fix prompt reaches the scope's bound session: the P1
     * {@link AgentExecutionRegistry#submitPrompt} (a blocking PTY write, so
     * it runs on the triage actions' executor, never the EDT), injected as
     * a seam so the wiring is testable with a fake submitter.
     */
    private final BiFunction<ManagedSessionId, String, PromptOutcome> promptSubmitter;

    // ---- assembled in initContent, on the EDT ----
    private JComponent panel;
    private JBLabel header;
    private JBLabel statusLabel;
    private ReviewTabBrowser browser;
    /** The disposal parent of the reusable diff editor and its inlay layer. */
    private Disposable diffParent;
    private ReviewDiffEditor diffEditor;
    private ReviewDiffInlays inlays;
    private DebounceScheduler debounce;
    private AnAction markReviewedAction;
    private AnAction unmarkAction;

    // ---- wired on the background load, once the backend is ready ----
    private WorktreeService worktrees;
    private GitStatusService git;
    private ReviewContents contents;
    private ReviewVerdictCommands commands;
    private ReviewTabScopes scopes;
    /** The backend's annotation store; volatile: the store listeners read it from their own threads. */
    private volatile AnnotationStore store;
    /** The finding cards' write side; volatile: the card callbacks read it from the EDT. */
    private volatile ReviewTriageActions triage;
    /** Volatile: the file/activity listeners and the Changes link read it from any thread. */
    private volatile ReviewTabRefresher refresher;
    private final AtomicBoolean wired = new AtomicBoolean();

    /**
     * The live provider of a project's Review content, so the Changes link
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

    /**
     * The generation guard of every asynchronous decoration producer
     * (T9): each computation mints a {@link DecorationGate.Token}, and
     * only a token still current on the EDT may feed {@link
     * ReviewDiffInlays#setDecorations}. See {@link DecorationGate}.
     */
    private final DecorationGate decorationGate = new DecorationGate();

    /**
     * The production provider, created by {@link ReviewToolWindowFactory}
     * when the dedicated window's single content is first shown: prompts
     * reach the bound session through the P1 execution registry.
     */
    ReviewTabProvider(Project project) {
        this(project, (sessionId, prompt) -> AgentExecutionRegistry.getInstance().submitPrompt(sessionId, prompt));
    }

    /** The testable wiring: the prompt seam is injected, everything else is production. */
    ReviewTabProvider(Project project, BiFunction<ManagedSessionId, String, PromptOutcome> promptSubmitter) {
        this.project = Objects.requireNonNull(project, "project");
        this.plugin = DrydockPluginService.getInstance();
        this.promptSubmitter = Objects.requireNonNull(promptSubmitter, "promptSubmitter");
    }

    /**
     * The window's whole content: header, tree, status — and the one
     * project-owned reusable editor-area diff plus its inlay layer, both
     * owned by this content and disposed with it. No splitter, no in-panel
     * preview: the file's diff opens in the editor area (T6's reusable
     * tab) and the finding cards render inside it.
     */
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

        // One reusable editor-area diff per content (T6): its tab is opened
        // by the editor itself on the first selection, its requests are
        // swapped in place afterwards, and its active viewer's decorations
        // (the finding cards) are the inlay layer's. The card callbacks
        // route through TriageCards — the write side is ReviewTriageActions
        // (T4), reached through the volatile fields wire() fills.
        diffParent = Disposer.newDisposable();
        TriageCards cardActions = new TriageCards(
                () -> triage, () -> scope, () -> store,
                plugin.executor(), this::showCardOutcome);
        inlays = new ReviewDiffInlays(ReviewDiffInlays.platformEdt(),
                ReviewDiffInlays.triageCards(Clock.systemDefaultZone(), cardActions));
        diffEditor = ReviewDiffEditor.platform(project, diffParent, inlays);

        JBPanel<?> content = new JBPanel<>(new BorderLayout());
        content.add(header, BorderLayout.NORTH);
        content.add(browser, BorderLayout.CENTER);
        content.add(statusLabel, BorderLayout.SOUTH);

        // Q13: one command, two entry points — the toolbar and the row
        // context menu. (The preview's third entry point left with the
        // preview; the diff's own toolbar is the platform's.)
        markReviewedAction = new MarkReviewedAction();
        unmarkAction = new UnmarkAction();
        AnAction refresh = new RefreshAction();
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
        subscribeRefreshTriggers(diffParent);

        // AC22 / repository rule: the first user-triggered load shows the
        // loading placeholder synchronously, before any background work is
        // scheduled — the click visibly does something before the result.
        renderStatus(ReviewTabStatus.LOADING, ReviewTabStatus.LOADING.message());
        submitLoad();
        return content;
    }

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
        // The inlay layer releases Swing-owned decorations (cards, inlays,
        // highlighters), so its disposal is EDT-confined — the hop keeps
        // that contract whatever thread disposes the content, and the
        // layer's own disposed flag makes a late queued rebuild a no-op.
        ReviewDiffInlays layer = this.inlays;
        if (layer != null) {
            onEdt(layer::dispose);
        }
        if (diffParent != null) {
            Disposer.dispose(diffParent);
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
        diffParent = null;
        diffEditor = null;
        inlays = null;
        debounce = null;
        markReviewedAction = null;
        unmarkAction = null;
        worktrees = null;
        git = null;
        contents = null;
        commands = null;
        scopes = null;
        refresher = null;
        store = null;
        triage = null;
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
            // Q8's in-window half: the factory hides the window in projects
            // with no covering session; if the platform does not re-evaluate
            // availability, this state is what those windows see instead.
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
        AnnotationStore annotations = backend.annotationStore();
        store = annotations;
        worktrees = new WorktreeService();
        git = new GitStatusService();
        contents = new ReviewContents(new GitExecutableLocator(), git);
        commands = new ReviewVerdictCommands(backend.diffService(), git, annotations, plugin.executor());
        // The finding cards' write side (T4): every store mutation and the
        // prompt submission run on the plugin's executor; the submitter seam
        // is the P1 registry path injected at construction.
        triage = new ReviewTriageActions(annotations, plugin.executor(), promptSubmitter,
                Clock.systemDefaultZone());
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
                        annotations.forScope(scope.id()),
                        annotations.verdictsFor(scope.id()),
                        baseCommit),
                ReviewTabModel::snapshot,
                this::snapshotArrived,
                (state, text) -> onEdt(() -> refreshStatus(state, text)));
        this.refresher = refresher;
        // The two refresh listeners (T11): putVerdict fires with a null key,
        // so neither can filter — both coalesce into one marks recompute,
        // and both also refresh the decorated file's inlay cards (a triage
        // write must reach the cards without waiting for the tree).
        refresher.onDispose(annotations.addChangeListener(key -> {
            refresher.marksChanged();
            submitDecorationRefresh();
        }));
        refresher.onDispose(backend.scopeRegistry().addChangeListener(scopeId -> {
            refresher.marksChanged();
            submitDecorationRefresh();
        }));
    }

    // ---- snapshots and status (the EDT half) --------------------------------

    /**
     * The refresher's snapshot consumer: stores first, refreshes the
     * decorated file's inlay decorations off the EDT, then renders on the
     * EDT — and consumes the opener's pending finding target (T8/T9) after
     * the tree accepted the snapshot, so a gutter click that beat the
     * content's first load still lands on the finding.
     */
    private void snapshotArrived(ReviewTabModel.Snapshot snapshot) {
        this.snapshot = snapshot;
        refreshDecorations();
        onEdt(() -> {
            ReviewTabBrowser browser = this.browser;
            JBLabel header = this.header;
            if (browser == null || header == null) {
                return;
            }
            browser.accept(snapshot);
            header.setText(snapshot.headerText());
            consumePendingFindingTarget();
        });
    }

    /**
     * The gutter-navigation half of the opener's contract (T8/T9): after a
     * snapshot lands, the pending target the opener recorded before this
     * content existed — or while another snapshot was in flight — is taken
     * from the project and the browser selects that finding; the tree's
     * own selection listener then drives the diff open, exactly as a click
     * on the row would. The take is the consumption: found or stale, the
     * target is spent exactly once, and a stale one leaves the selection
     * unchanged (the browser's own rule).
     */
    private void consumePendingFindingTarget() {
        ReviewTabBrowser browser = this.browser;
        if (browser == null || disposed.get()) {
            return;
        }
        consumeTarget(ReviewTabOpener.takePendingFinding(project),
                (file, findingId) -> browser.selectFinding(file, findingId));
    }

    /**
     * The pure consumption half of the pending-target handoff: a recorded
     * target selects its finding; no target selects nothing. Separate from
     * the Swing half so the handoff (target spent exactly once, exact
     * file/finding forwarded) is testable headless.
     */
    static void consumeTarget(Optional<ReviewTabOpener.FindingTarget> pending,
                              BiConsumer<String, String> selectFinding) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(selectFinding, "selectFinding");
        pending.ifPresent(target -> selectFinding.accept(target.filePath(), target.findingId()));
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

    // ---- selection -> the reusable diff -------------------------------------

    /**
     * The browser's selection consumer (EDT): records the selection for
     * the commands, marks it current with the reusable editor (requests
     * for any earlier selection are stale from here on), then builds the
     * file's request on the background executor — its texts are blocking
     * git reads (T7), never EDT work. The selection change also mints the
     * decoration generation for the scheduled build (or, when nothing is
     * scheduled for this selection, invalidates the in-flight ones), so a
     * late decoration completion never repaints the previous file's diff.
     */
    private void selected(Object row) {
        selectionRow = row;
        ReviewDiffEditor editor = this.diffEditor;
        if (editor == null) {
            return; // the tab is disposed: nothing is selected anywhere
        }
        editor.selection(row);
        ReviewTabModel.FileRow file = fileRowOf(row);
        ReviewScope current = this.scope;
        ReviewContents reader = this.contents;
        if (file == null || current == null || reader == null) {
            // Nothing is scheduled for this selection, but the selection
            // still changed: every in-flight decoration computation is
            // stale from here on, whatever file it was computed for.
            decorationGate.invalidate();
            return; // nothing to show yet: the load that follows reads fresh state
        }
        DecorationGate.Token token = decorationGate.begin(current.id(), file.path());
        plugin.executor().execute(() -> buildRequest(current, row, file, token));
    }

    /**
     * Builds one file's request off the EDT: the two texts, the AC15 row
     * check against both, then the fragments. The titled request context
     * and the inlay decorations are applied on the EDT, and only if the
     * selection is still current — the editor drops a stale request
     * itself, and the provider's own check keeps a stale selection's
     * decorations from re-targeting the inlays away from whatever the
     * newer selection is about to show. The {@link DecorationGate} token
     * minted when the build was scheduled adds the generation check: a
     * computation that began later — a store, scope or snapshot refresh,
     * of this file or another — owns the layer then, and this build's
     * (possibly same-path) read cannot reapply over its fresher state.
     */
    private void buildRequest(ReviewScope scope, Object selectionId, ReviewTabModel.FileRow row,
                              DecorationGate.Token token) {
        if (disposed.get()) {
            return;
        }
        UnifiedDiff.FileDiff file = row.diff();
        String left = contents.leftText(scope, file);
        String right = contents.rightText(scope, file);
        if (!ReviewContents.matchesRows(left, file, false) || !ReviewContents.matchesRows(right, file, true)) {
            // AC15: the file changed after the diff — the fragments are
            // not fed, the tab shows the changed-since state, and exactly
            // one re-run is scheduled (the refresher debounces and keeps at
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
        List<LineFragment> fragments = ReviewFragments.fragmentsFor(file, left, right);
        // The file's decorations — every unresolved finding, triaged or
        // not, so CONFIRMED/DISMISSED cards keep their Undo — read off the
        // EDT like everything store-side here.
        AnnotationStore annotations = this.store;
        ReviewDiffInlays.Decorations decorations = annotations == null
                ? null
                : decorationsFor(row, annotations.forScope(scope.id()));
        onEdt(() -> {
            if (disposed.get()) {
                return;
            }
            ReviewDiffEditor editor = diffEditor;
            ReviewDiffInlays layer = inlays;
            if (editor == null || layer == null) {
                return;
            }
            if (selectionRow != selectionId) {
                return; // a newer selection superseded this build: neither its request nor its decorations apply
            }
            // The decorations first: the viewer change the request's apply
            // fires then finds them current, and the layer's path check
            // keeps them off any other file's diff. The gate drops them
            // when a computation that began later — a store/scope/snapshot
            // refresh with a fresher read — owns the layer's next state.
            if (decorations != null
                    && decorationGate.applies(token, currentScopeId(), selectedPath())) {
                layer.setDecorations(decorations);
            }
            // The titled context is built here, on the EDT, from the
            // off-EDT texts and fragments; the editor drops it itself when
            // its own selection token says a newer selection won.
            editor.requestReady(selectionId,
                    requestContextFor(selectionId, file, left, right, fragments));
        });
    }

    /**
     * The titled request context for a selection (pure, EDT-built by
     * {@link #buildRequest}): the file path names the request — the title
     * the reusable tab shows and the identity the inlay layer's path
     * check compares — and the plan scrolls a finding row to its own line
     * key (a malformed persisted key falls back to the file's own scroll —
     * it is a value, not a crash), a file row to its first changed line.
     */
    static ReviewDiffRequests.RequestContext requestContextFor(Object row, UnifiedDiff.FileDiff file,
                                                               CharSequence left, CharSequence right,
                                                               List<LineFragment> fragments) {
        Objects.requireNonNull(file, "file");
        ReviewDiffRequests.Plan plan;
        if (row instanceof ReviewTabModel.FindingRow finding) {
            try {
                plan = ReviewDiffRequests.plan(left, right, fragments,
                        ReviewDiffRequests.scrollTarget(finding.startKey()));
            } catch (IllegalArgumentException e) {
                // fall through: the file's own scroll target
                plan = ReviewDiffRequests.plan(file, left, right, fragments);
            }
        } else {
            plan = ReviewDiffRequests.plan(file, left, right, fragments);
        }
        return new ReviewDiffRequests.RequestContext(file.path(), plan);
    }

    /**
     * The inlay layer's input for one selected file (pure): the current
     * diff's row keys, the first-hunk fallback target ({@link
     * ReviewFindingPlacement#firstHunkFallback}), and <em>every</em>
     * unresolved finding of that file — CONFIRMED and DISMISSED included,
     * so their cards stay rendered and Undo stays reachable; only a
     * resolved finding leaves (it is finished, not triaged). Findings of
     * other files are not this file's decorations.
     */
    static ReviewDiffInlays.Decorations decorationsFor(ReviewTabModel.FileRow row,
                                                        List<ReviewAnnotation> scopeFindings) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(scopeFindings, "scopeFindings");
        Set<String> rowKeys = new LinkedHashSet<>();
        for (UnifiedDiff.Hunk hunk : row.diff().hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                rowKeys.add(line.lineKey());
            }
        }
        List<ReviewAnnotation> unresolved = scopeFindings.stream()
                .filter(finding -> finding.file().equals(row.path()))
                .filter(finding -> !finding.resolved())
                .toList();
        return new ReviewDiffInlays.Decorations(row.path(), rowKeys,
                ReviewFindingPlacement.firstHunkFallback(row.diff()), unresolved);
    }

    /**
     * Re-reads the decorated file's findings and re-targets the inlay layer
     * (T9): every store or scope change and every snapshot arrival runs
     * this off the EDT, so the cards rebuild from fresh triage state —
     * CONFIRMED/DISMISSED findings stay rendered, Undo included — while
     * the layer's own coalescing collapses a burst of store writes into
     * one rebuild, and its request-path check keeps the previous file's
     * findings off whatever diff the viewer currently shows. The
     * computation mints its {@link DecorationGate} token when it begins,
     * and its EDT apply is dropped when a computation that began later —
     * a newer selection, snapshot, scope or store read — has taken over,
     * so a refresh computed for old state can never overwrite what a
     * newer computation applied.
     */
    private void refreshDecorations() {
        if (disposed.get()) {
            return;
        }
        ReviewScope current = scope;
        AnnotationStore annotations = store;
        ReviewTabModel.FileRow row = decoratedFileRow();
        if (current == null || annotations == null || row == null) {
            return; // nothing selected or nothing wired yet: the next selection reads fresh state
        }
        DecorationGate.Token token = decorationGate.begin(current.id(), row.path());
        ReviewDiffInlays.Decorations decorations = decorationsFor(row, annotations.forScope(current.id()));
        onEdt(() -> {
            ReviewDiffInlays layer = inlays;
            if (layer == null || disposed.get()) {
                return;
            }
            if (!decorationGate.applies(token, currentScopeId(), selectedPath())) {
                return; // stale: a newer computation — a newer file, scope or store read — owns the layer
            }
            layer.setDecorations(decorations);
        });
    }

    /**
     * Schedules a decoration refresh on the plugin executor (the store
     * and registry listeners fire on their own threads, never the EDT); a
     * shutdown race is ignored, not thrown.
     */
    private void submitDecorationRefresh() {
        if (disposed.get()) {
            return;
        }
        try {
            plugin.executor().execute(this::refreshDecorations);
        } catch (RejectedExecutionException e) {
            SYS_LOG.log(Level.WARNING, "Review diff decoration refresh not scheduled: plugin executor is shut down");
        }
    }

    /**
     * The file row the inlay decorations target: the latest snapshot's row
     * for the selected file's path — never the selected row instance
     * itself, which a rebuild may already have replaced — resolved purely
     * by path so a scope refresh's new diff (and its new row keys) drives
     * the next decoration rebuild.
     */
    private ReviewTabModel.FileRow decoratedFileRow() {
        ReviewTabModel.Snapshot current = snapshot;
        String path = selectedPath();
        if (current == null || path == null) {
            return null;
        }
        return current.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElse(null);
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

    /** The current scope's id; null before the first load resolves one. */
    private String currentScopeId() {
        ReviewScope current = scope;
        return current == null ? null : current.id();
    }

    /**
     * The currently selected file's path, whichever row shape carries the
     * selection; null when nothing file-shaped is selected. Safe off the
     * EDT ({@code selectionRow} is volatile) — the gate's apply check runs
     * on the EDT, the decorated-row resolution off it.
     */
    private String selectedPath() {
        Object row = selectionRow;
        if (row instanceof ReviewTabModel.FileRow file) {
            return file.path();
        }
        if (row instanceof ReviewTabModel.FindingRow finding) {
            return finding.annotation().file();
        }
        return null;
    }

    /**
     * The generation guard of every asynchronous decoration producer (T9):
     * each computation — a selection's request build, and the store,
     * scope and snapshot refreshes — mints a {@link Token} when it begins,
     * capturing the generation, the scope id and the file path it computes
     * for, and its EDT apply is allowed only while that token is still
     * current: the latest minted generation, the current scope, and the
     * currently selected file. A computation that began for an older file,
     * an older scope, or an older store/snapshot read of the same file can
     * therefore never overwrite what a newer computation applied — the
     * stale same-path case the selection's identity check alone cannot
     * see, because the path is identical. The provider mints when it
     * schedules a selection's build (on the EDT) and at every refresh's
     * compute start (the computation's inputs are captured then); a
     * selection nothing is scheduled for only {@linkplain #invalidate()
     * invalidates}. Pure state and no platform class, so the queueing
     * discipline — old and new completions, deterministic order — is
     * testable headless.
     */
    static final class DecorationGate {

        /**
         * One decoration computation's identity: the generation minted
         * when it began, and the scope and file it computes for. The
         * generation is compared first, so the common stale case — a newer
         * computation exists — is one atomic read.
         */
        record Token(long generation, String scopeId, String path) {
            Token {
                Objects.requireNonNull(scopeId, "scopeId");
                Objects.requireNonNull(path, "path");
            }
        }

        private final AtomicLong generation = new AtomicLong();

        /** Mints the token of a computation beginning now for {@code scopeId}/{@code path}. */
        Token begin(String scopeId, String path) {
            return new Token(generation.incrementAndGet(), scopeId, path);
        }

        /**
         * Retires every in-flight token without beginning a computation:
         * a selection changed for which nothing is scheduled, so nothing
         * new will supersede the stale applies — the bump does.
         */
        void invalidate() {
            generation.incrementAndGet();
        }

        /**
         * Whether a computed decoration set may still be applied (EDT):
         * the token's generation is the latest minted, the scope it was
         * computed for is the current one, and the file it was computed for
         * is the currently selected file. A null current scope or path —
         * nothing resolved, nothing file-shaped selected — blocks the
         * apply.
         */
        boolean applies(Token token, String currentScopeId, String currentPath) {
            return token.generation() == generation.get()
                    && token.scopeId().equals(currentScopeId)
                    && token.path().equals(currentPath);
        }
    }

    // ---- the finding cards' write side --------------------------------------

    /**
     * Shows one card outcome on the EDT: a warning for outcomes the human
     * caused or can act on (no session, a refusal, a missing finding), an
     * error for failures. Production maps these to the platform's dialogs;
     * tests record them.
     */
    private void showCardOutcome(TriageCards.Notice notice, String text) {
        if (disposed.get()) {
            return;
        }
        if (notice == TriageCards.Notice.WARNING) {
            Messages.showWarningDialog(project, text, "Drydock Review");
        } else {
            Messages.showErrorDialog(project, text, "Drydock Review");
        }
    }

    /**
     * The card callbacks' wiring (T9): every card button hands its finding
     * to {@link ReviewTriageActions} — all store mutation and the prompt
     * submission run there, on the injected executor — and every
     * completion, success, refusal, failure or a missing finding included,
     * clears the card's progress on the EDT and surfaces what happened
     * distinctly, without blocking. The write side is reached through the
     * injected suppliers (the production instance's are the provider's
     * volatile fields, filled by {@code wire()}); the prompt path resolves
     * the finding fresh from the store, off the EDT, so a stale card can
     * never send a removed finding's text. A {@link RuntimeException}
     * thrown synchronously while an action's work is being scheduled —
     * an executor rejecting, the one case no future ever sees — completes
     * the card and surfaces the ERROR with the root cause the same way, so
     * no card is ever left busy and no failure escapes into the diff's
     * UI. An {@link Error} is not an operational failure: it is never
     * converted into a dialog (that would swallow it) — the card clears
     * and the Error is rethrown, so it propagates to its caller and the
     * executor's own uncaught handling instead of dying in the UI.
     */
    static final class TriageCards implements ReviewDiffInlays.CardActions {

        /** How strongly an outcome must be surfaced; the production surface maps it to a dialog. */
        enum Notice { WARNING, ERROR }

        /** Shows one outcome's message; always called on the EDT. */
        interface Surface {
            void show(Notice notice, String text);
        }

        /** One outcome's message: how strongly to surface it, and its never-blank text. */
        record Surfaced(Notice notice, String text) {
            Surfaced {
                Objects.requireNonNull(notice, "notice");
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("an outcome message is never blank");
                }
            }
        }

        private final Supplier<ReviewTriageActions> triage;
        private final Supplier<ReviewScope> scope;
        private final Supplier<AnnotationStore> store;
        /** Where the fix path's store read runs (the plugin executor in production; never the EDT). */
        private final Executor storeReader;
        private final Surface surface;

        /** The failure messages, one prefix per action: synchronous scheduling rejections and asynchronous failures surface the same words. */
        private static final String UPDATING_FAILED = "Updating the finding failed: ";
        private static final String COMMENT_FAILED = "Saving the comment failed: ";
        private static final String FIX_FAILED = "Asking the agent to fix the finding failed: ";

        TriageCards(Supplier<ReviewTriageActions> triage, Supplier<ReviewScope> scope,
                    Supplier<AnnotationStore> store, Executor storeReader, Surface surface) {
            this.triage = Objects.requireNonNull(triage, "triage");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.store = Objects.requireNonNull(store, "store");
            this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
            this.surface = Objects.requireNonNull(surface, "surface");
        }

        @Override
        public void confirm(ReviewAnnotation.Key key, FindingInlayCard card) {
            ReviewTriageActions actions = triage.get();
            if (actions == null) {
                onEdt(card::complete);
                return;
            }
            schedule(card, UPDATING_FAILED, () -> actions.confirm(key),
                    (updated, error) -> reportTriage(card, updated, error));
        }

        @Override
        public void dismiss(ReviewAnnotation.Key key, FindingInlayCard card) {
            ReviewTriageActions actions = triage.get();
            if (actions == null) {
                onEdt(card::complete);
                return;
            }
            schedule(card, UPDATING_FAILED, () -> actions.dismiss(key),
                    (updated, error) -> reportTriage(card, updated, error));
        }

        @Override
        public void undo(ReviewAnnotation.Key key, FindingInlayCard card) {
            ReviewTriageActions actions = triage.get();
            if (actions == null) {
                onEdt(card::complete);
                return;
            }
            schedule(card, UPDATING_FAILED, () -> actions.undo(key),
                    (updated, error) -> reportTriage(card, updated, error));
        }

        @Override
        public void comment(ReviewAnnotation.Key key, FindingInlayCard card, String text) {
            ReviewTriageActions actions = triage.get();
            if (actions == null) {
                onEdt(card::complete);
                return;
            }
            schedule(card, COMMENT_FAILED, () -> actions.comment(key, text),
                    (outcome, error) -> reportComment(card, outcome, error));
        }

        @Override
        public void fix(ReviewAnnotation.Key key, FindingInlayCard card) {
            ReviewTriageActions actions = triage.get();
            ReviewScope current = scope.get();
            AnnotationStore annotations = store.get();
            if (actions == null || current == null || annotations == null) {
                // Nothing is wired yet (or the tab closed under the card):
                // no work exists to run, and the card must not stay busy.
                onEdt(card::complete);
                return;
            }
            try {
                storeReader.execute(() -> askToFix(actions, annotations, current, key, card));
            } catch (RuntimeException e) {
                // The store read itself was rejected — an executor shut
                // down under the card: no task ever runs, so no future
                // exists; the card still completes and the failure surfaces.
                Throwable rejected = e;
                onEdt(() -> {
                    card.complete();
                    surface.show(Notice.ERROR, FIX_FAILED + rootMessage(rejected));
                });
            } catch (Error e) {
                // An Error is not an operational failure to convert into a
                // dialog — surfacing it as one swallows it. The card clears
                // (here on the EDT synchronously, from a worker via the
                // EDT hop) and the Error is rethrown to whoever must see
                // it.
                onEdt(card::complete);
                throw e;
            }
        }

        /**
         * The fix path's store-reader task: resolves the finding fresh, off
         * the EDT, then schedules the ask. A synchronous rejection of the
         * ask's own scheduling — the triage executor refusing while this
         * task already runs — is caught by {@link #schedule} like every
         * other action's, instead of killing the task with the card busy.
         */
        private void askToFix(ReviewTriageActions actions, AnnotationStore annotations,
                              ReviewScope current, ReviewAnnotation.Key key, FindingInlayCard card) {
            ReviewAnnotation finding = annotations.byKey(key).orElse(null);
            if (finding == null) {
                // A removed finding is never sent, never marked, and
                // surfaced distinctly — the never-pretend rule.
                onEdt(() -> {
                    card.complete();
                    surface.show(Notice.WARNING, "This finding no longer exists; nothing was sent.");
                });
                return;
            }
            schedule(card, FIX_FAILED,
                    () -> actions.askToFix(current, finding.file(), List.of(finding)),
                    (outcome, error) -> reportFix(card, outcome, error));
        }

        /**
         * Schedules one card action through its future-supplier and hands
         * the future's typed outcome to its reporter: creating the future
         * itself schedules the work ({@link ReviewTriageActions} runs
         * everything on its executor), and an executor that rejects
         * synchronously throws before any {@code whenComplete} exists — so
         * the supplier runs under a catch that completes the card and
         * surfaces the ERROR with the root cause, exactly as the future's
         * own exceptional completion would be. An {@link Error} is not an
         * operational failure and is never converted into a dialog — that
         * would swallow it: the card clears (synchronously on the EDT,
         * scheduled from a worker) and the Error is rethrown to the
         * caller, so an Error raised inside the store-reader task's
         * {@code askToFix} propagates out of the task instead of dying in
         * a dialog.
         */
        private <T> void schedule(FindingInlayCard card, String failure,
                                  Supplier<CompletableFuture<T>> action,
                                  BiConsumer<T, Throwable> report) {
            CompletableFuture<T> future;
            try {
                future = action.get();
            } catch (RuntimeException e) {
                Throwable thrown = e;
                onEdt(() -> {
                    card.complete();
                    surface.show(Notice.ERROR, failure + rootMessage(thrown));
                });
                return;
            } catch (Error e) {
                onEdt(card::complete);
                throw e;
            }
            future.whenComplete((outcome, error) -> onEdt(() -> report.accept(outcome, error)));
        }

        /** The triage completions: confirm, dismiss, undo. */
        private void reportTriage(FindingInlayCard card, Optional<ReviewAnnotation> updated,
                                 Throwable error) {
            card.complete();
            if (error != null) {
                surface.show(Notice.ERROR, UPDATING_FAILED + rootMessage(error));
                return;
            }
            if (updated.isEmpty()) {
                // The card was stale — the finding left the store; the
                // rebuild drops the card, the human is told why it went.
                surface.show(Notice.WARNING, "This finding no longer exists.");
            }
            // A successful update needs no message: the store change
            // rebuilds the card from the fresh triage state.
        }

        /** The comment completion; see {@link #comment}. */
        private void reportComment(FindingInlayCard card, ReviewTriageActions.CommentOutcome outcome,
                                   Throwable error) {
            card.complete();
            if (error != null) {
                surface.show(Notice.ERROR, COMMENT_FAILED + rootMessage(error));
                return;
            }
            switch (outcome.state()) {
                // APPENDED: the store change rebuilds the card with the thread.
                case APPENDED, BLANK -> { } // BLANK is unreachable: the card never submits a blank comment
                case MISSING -> surface.show(Notice.WARNING,
                        "This finding no longer exists; the comment was not saved.");
            }
        }

        /** The fix completion; see {@link #fix} and {@link #fixOutcome}. */
        private void reportFix(FindingInlayCard card, ReviewTriageActions.FixOutcome outcome, Throwable error) {
            card.complete();
            if (error != null) {
                surface.show(Notice.ERROR, FIX_FAILED + rootMessage(error));
                return;
            }
            Surfaced message = fixOutcome(outcome.state());
            if (message != null) {
                surface.show(message.notice(), message.text());
            }
        }

        /**
         * The message an ask-to-fix outcome surfaces (pure): a delivered
         * prompt needs none — the store change rebuild shows the SENT state
         * — while no-session, an empty hand-off, a refusal and a failed
         * write are distinct, never-blank messages; only the failed write
         * is an error, the rest are states the human chose or can act on.
         */
        static Surfaced fixOutcome(ReviewTriageActions.FixOutcome.State state) {
            return switch (state) {
                case DELIVERED -> null;
                case NO_SESSION -> new Surfaced(Notice.WARNING,
                        "No agent session is bound to this review scope; nothing was sent.");
                case EMPTY -> new Surfaced(Notice.WARNING,
                        "Nothing was left to hand over; nothing was sent.");
                case REFUSED -> new Surfaced(Notice.WARNING,
                        "The agent session refused the prompt; nothing was sent and nothing was marked.");
                case FAILED -> new Surfaced(Notice.ERROR,
                        "Sending the prompt to the agent failed; nothing was sent and nothing was marked.");
            };
        }

        /** The cause behind a future's wrapper, never the wrapper itself; never blank. */
        private static String rootMessage(Throwable failure) {
            Throwable t = failure;
            while (t instanceof CompletionException && t.getCause() != null) {
                t = t.getCause();
            }
            String message = t.getMessage();
            return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
        }

        private static void onEdt(Runnable runnable) {
            ReviewTabProvider.onEdt(runnable);
        }
    }

    // ---- the write side (Q13: one command, two entry points) --------------

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
     * connections live on the diff parent, disposed with the content.
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
        // The PR-review flow opens a worktree's window before its session
        // registers, so a tab opened in that gap renders NO_SESSION and
        // nothing else re-ran the load: the coverage check reads the
        // in-memory session list, which nothing else in the tab watches.
        // The catalog's change topic is exactly that gap's signal -- a
        // stranded NO_SESSION re-runs the load (which finds the session and
        // proceeds); a tab already showing a scope ignores the event, since
        // its live updates come from the store and activity listeners.
        ApplicationManager.getApplication().getMessageBus().connect(parent)
                .subscribe(DrydockSessionsListener.TOPIC, this::sessionCatalogChanged);
    }

    /** The durable session catalog changed (any thread): maybe a stranded NO_SESSION can now load. */
    private void sessionCatalogChanged() {
        onEdt(() -> {
            if (disposed.get()) {
                return;
            }
            // lastState is EDT-confined; reading it here keeps the check
            // against the same value renderStatus wrote. Re-running a load
            // that is not stranded is harmless but wasteful, so only the
            // stranded state re-runs it.
            if (lastState == ReviewTabStatus.NO_SESSION) {
                submitLoad();
            }
        });
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
     * request executor writes it and any listener thread may clear it.</p>
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
        ReviewDocumentSaves.saveOpenDocuments();
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

    /** Toolbar / context menu: approve every hunk of the selected file (T9). */
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

    /** Toolbar / context menu: clear the human approvals of the selected file (T9, Q14). */
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
                ReviewDocumentSaves.saveOpenDocuments();
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
