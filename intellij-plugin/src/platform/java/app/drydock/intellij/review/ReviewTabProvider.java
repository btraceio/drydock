package app.drydock.intellij.review;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.WorktreeService;
import app.drydock.github.GitHubReviewRequest;
import app.drydock.github.GitHubReviewService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.agent.SessionActivityListener;
import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.intellij.tasks.WorktreeReview;
import app.drydock.review.ChangeGraph;
import app.drydock.review.DiffSelection;
import app.drydock.review.FixTarget;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Triage;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Sections;
import app.drydock.review.Severity;
import app.drydock.review.SubmitPlan;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStore;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The review behind the dedicated Drydock Review window and the review
 * workbench. The window's content is a launcher: the header
 * ({@code <k> / <n> reviewed}), the Open Workbench button, the batched
 * decisions bar and the status placeholder ({@link ReviewTabStatus}). The
 * workbench walks the review's hunks in its flow ({@link ReviewWorkbenchFlow},
 * {@link #hostedFlow}): a queue of hunks and a centre that shows one hunk's
 * diff at a time, decorated by its own inlay layer ({@link ReviewDiffInlays},
 * {@link FlowActions#decorator}) with one finding card per unresolved
 * finding of the hunk.
 *
 * <p><strong>Composition.</strong> The tab owns no review logic: the scope
 * comes from {@link ReviewTabScopes}; the snapshot from {@link
 * ReviewTabRefresher} over {@link ReviewTabModel}; a hunk's texts from
 * {@link ReviewContents}, built into a request by {@link
 * ReviewDiffRequests} with fragments from {@link ReviewFragments}; the
 * finding cards' write side is {@link ReviewTriageActions} (T4), wired
 * through {@link TriageCards} below. The annotation store and the diff
 * service are the backend's own instances (one writer, and digests
 * measured by the same diff an agent sees).</p>
 *
 * <p><strong>A hunk's diff.</strong> The flow asks for the hunk's request
 * ({@link FlowActions#hunkContext}): the file's git reads (both texts, the
 * row checks, the fragments of that hunk only) run on the plugin's
 * background executor. When a text no longer matches the diff's rows
 * (AC15: the file changed after the diff), the hunk shows the
 * changed-since notice and one reload is scheduled; {@link MismatchMemo}
 * keeps a file that can never match from reloading in a loop.</p>
 *
 * <p><strong>Gutter navigation.</strong> The opener ({@link
 * ReviewTabOpener}, T8) records a finding target on the project before the
 * window's content exists; a snapshot arrival or a flow attaching consumes
 * it ({@link ReviewTabOpener#takePendingFinding}) once a workbench flow
 * exists, and every flow shows the hunk the finding starts on
 * ({@link ReviewWorkbenchFlow#showLine}).</p>
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

    /** The window's button into the review workbench. */
    static final String OPEN_WORKBENCH = "Open Workbench";

    /** The launcher window's body: the review itself is the workbench's. */
    static final String LAUNCHER_TEXT = "Review the files in the workbench.";

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
    /** The workbench inboxes showing this review (EDT only); the selection actions open their composer. */
    private final List<ReviewWorkbenchInbox> inboxes = new ArrayList<>();
    /** The workbench flows showing this review (EDT only); finding and file targets land on them. */
    private final List<ReviewWorkbenchFlow> flows = new ArrayList<>();
    /** The flows' seen and flagged marks, in this project's properties. */
    private final ReviewFlowMarks flowMarks;
    /** The disposal parent of the content's own listeners and the source-editor marks. */
    private Disposable contentParent;
    private DebounceScheduler debounce;
    /** The batched-decisions hand-off: pending counts on the left, the one Send on the right. */
    private JPanel decisionsBar;
    private JBLabel decisionsLabel;
    private JButton sendDecisionsButton;
    private boolean sendInFlight;

    // ---- wired on the background load, once the backend is ready ----
    private WorktreeService worktrees;
    private GitStatusService git;
    private ReviewContents contents;
    private ReviewTabScopes scopes;
    /** The backend's pending questions and the selection questions it mints (Ask About Selection). */
    private volatile PendingQuestions pendingQuestions;
    private volatile SelectionAsks selectionAsks;
    /** The backend's annotation store; volatile: the store listeners read it from their own threads. */
    private volatile AnnotationStore store;
    /** The finding cards' write side, shared by every hunk diff's inlay layer. */
    private TriageCards cardActions;
    /** The backend's tour store: the agent's tours, the Sections tab's first source. */
    private volatile TourStore tours;
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
     * AC15: set (before the reload) when a shown hunk's file changed after
     * the diff; while set, the reload's LOADING report renders the
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
    /** The last refresh state rendered (EDT only): a catalog change re-runs a load stranded in NO_SESSION. */
    private ReviewTabStatus lastState = ReviewTabStatus.LOADING;

    private final AtomicBoolean disposed = new AtomicBoolean();

    // ---- the review workbench (spec §3) ----
    /** The workbench panes fed with every state (EDT only). */
    private final List<WorkbenchView> workbenchViews = new ArrayList<>();
    /** The file of the hunk a workbench flow shows, if any; the inbox's This File scope follows it. */
    private volatile String shownFile;
    /** The workbench inbox's tab and scope; set on the EDT, read when the state is rebuilt. */
    private volatile WorkbenchState.InboxChoice inboxChoice = WorkbenchState.InboxChoice.DEFAULT;
    /** The latest workbench state, for a view that attaches after it was built. */
    private volatile WorkbenchState workbenchState;
    /** The queue's Sections | Files tab; set on the EDT, read when the state is rebuilt. */
    private volatile WorkbenchState.SectionsChoice sectionsChoice = WorkbenchState.SectionsChoice.DEFAULT;
    /** The base commit the latest snapshot was built against, null before the first: the sections judge staleness by it. */
    private volatile Optional<String> reviewBase;
    /** The computed sections of one diff, kept while the diff is equal. */
    private volatile ComputedSections computedSections;
    /** The latest pull request description read, for one scope. */
    private volatile PrDescription prDescription;
    /** The scope whose pull request description is being read, if any. */
    private final AtomicReference<String> prDescriptionInFlight = new AtomicReference<>();
    /** The diff whose computed sections are being built, if any. */
    private final AtomicReference<UnifiedDiff> sectionsInFlight = new AtomicReference<>();

    /**
     * The scope's diff root as the project's own files name it
     * ({@link #checkoutRoot}): where the review's paths resolve to
     * project {@code VirtualFile}s. Set off the EDT with the scope.
     */
    private volatile Path checkoutRoot;
    /** The finding marks in this project's source editors, for {@link #editorMarksRoot}; EDT. */
    private ReviewEditorHighlighters editorMarks;
    private Path editorMarksRoot;
    /** The latest resolved scope; every reload targets it. */
    private volatile ReviewScope scope;
    /** The latest snapshot; a finding's or a hunk's file row is looked up in it. */
    private volatile ReviewTabModel.Snapshot snapshot;

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
        this.flowMarks = new ReviewFlowMarks(PropertiesComponent.getInstance(project));
    }

    /**
     * The window's whole content: the progress header, Open Workbench, the
     * batched decisions bar and the status line. The review itself is the
     * workbench's ({@link #hostedFlow}); this content owns the review's
     * wiring and the listeners, disposed with it.
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

        // The finding cards of every hunk diff route through TriageCards —
        // the write side is ReviewTriageActions (T4), reached through the
        // volatile fields wire() fills. Decisions reach the agent as ONE
        // batch (the decisions bar below), so the cards carry no
        // per-finding send at all.
        contentParent = Disposer.newDisposable();
        cardActions = new TriageCards(
                () -> triage, () -> scope, () -> store, this::showCardOutcome);

        buildDecisionsBar();

        // The workbench is the review surface; this window is the launcher:
        // the progress header, the way into the workbench, the batched
        // decisions and the status.
        JButton openWorkbench = new JButton(OPEN_WORKBENCH);
        openWorkbench.addActionListener(event -> ReviewWorkbenchFile.open(project));
        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.CENTER);
        top.add(openWorkbench, BorderLayout.EAST);
        JBLabel launcher = new JBLabel(LAUNCHER_TEXT, SwingConstants.CENTER);
        launcher.setForeground(UIUtil.getContextHelpForeground());
        JBPanel<?> content = new JBPanel<>(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(launcher, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(decisionsBar, BorderLayout.NORTH);
        south.add(statusLabel, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        debounce = new DebounceScheduler();
        panel = content;
        project.putUserData(LIVE_PROVIDER, this);
        subscribeRefreshTriggers(contentParent);

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
        // The source-editor marks also end with contentParent below; this hop clears the field on the EDT that owns it.
        onEdt(() -> {
            if (editorMarks != null) {
                editorMarks.dispose();
            }
            editorMarks = null;
            editorMarksRoot = null;
        });
        if (contentParent != null) {
            Disposer.dispose(contentParent);
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
        flows.clear(); // each ends with its workbench editor
        inboxes.clear();
        contentParent = null;
        debounce = null;
        worktrees = null;
        git = null;
        contents = null;
        scopes = null;
        refresher = null;
        store = null;
        pendingQuestions = null;
        selectionAsks = null;
        triage = null;
        scope = null;
        snapshot = null;
        shownFile = null;
        changedSincePending = false;
        workbenchViews.clear();
        workbenchState = null;
        mismatchMemo.reset();
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
                    ReviewTabProvider.this.checkoutRoot = checkoutRoot(scope.diffRoot(), base,
                            ReviewTabProvider::realPathOrNull);
                    ReviewTabProvider.this.scope = scope;
                    onEdt(() -> nameWorkbench(scope));
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
        tours = backend.tourStore();
        pendingQuestions = backend.pendingQuestions();
        selectionAsks = backend.selectionAsks();
        worktrees = new WorktreeService();
        git = new GitStatusService();
        contents = new ReviewContents(new GitExecutableLocator(), git);
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
                (scope, diff, baseCommit) -> {
                    reviewBase = baseCommit;
                    return new ReviewTabModel.Inputs(
                            diff,
                            annotations.forScope(scope.id()),
                            annotations.verdictsFor(scope.id()),
                            baseCommit);
                },
                ReviewTabModel::snapshot,
                this::snapshotArrived,
                (state, text) -> onEdt(() -> refreshStatus(state, text)));
        this.refresher = refresher;
        // The two refresh listeners (T11): putVerdict fires with a null key,
        // so neither can filter — both coalesce into one marks recompute.
        // A hunk diff's finding cards listen to the store themselves
        // (FlowActions#decorator).
        refresher.onDispose(annotations.addChangeListener(key -> {
            refresher.marksChanged();
            onEdt(this::refreshDecisionsBar);
        }));
        refresher.onDispose(backend.scopeRegistry().addChangeListener(scopeId -> {
            // The registry's copy is the truth: a session that starts reviewing this checkout after
            // the tab resolved its scope binds it there (the scope's id is the same), and every send
            // reads the session from this field.
            adopted(this.scope, scopeId, backend.scopeRegistry()::byId).ifPresent(updated -> {
                this.scope = updated;
                submitWorkbenchState();
            });
            refresher.marksChanged();
        }));
        // A posted or merged tour regroups the queue's Sections tab.
        refresher.onDispose(backend.tourStore().addChangeListener(scopeId -> {
            ReviewScope current = this.scope;
            if (current != null && current.id().equals(scopeId)) {
                submitWorkbenchState();
            }
        }));
    }

    /**
     * The registry's copy of {@code current} after a change to scope
     * {@code changedId} (pure): present only when the change is to the
     * scope this content shows and the registry still holds it. The copy is
     * the truth: a session that binds this checkout's scope after the
     * content resolved it is recorded there, under the same id.
     */
    static Optional<ReviewScope> adopted(ReviewScope current, String changedId,
                                         Function<String, Optional<ReviewScope>> byId) {
        return current != null && current.id().equals(changedId) ? byId.apply(changedId) : Optional.empty();
    }

    // ---- the source-editor marks --------------------------------------------

    /**
     * Repaints the source-editor marks for {@code rows} (spec §2, "Round
     * trip"): the model is built here, off the EDT, from the snapshot's file
     * diffs and the scope's findings, and applied on the EDT. The layer is
     * created for the checkout root the review's paths resolve under, and
     * recreated when that root changes; it ends with the content.
     */
    private void refreshEditorMarks(ReviewTabModel.Snapshot rows) {
        ReviewScope current = scope;
        AnnotationStore annotations = store;
        Path root = checkoutRoot;
        if (current == null || annotations == null || root == null) {
            return;
        }
        UnifiedDiff diff = new UnifiedDiff(rows.files().stream().map(ReviewTabModel.FileRow::diff).toList());
        ReviewEditorMarkModel model = ReviewEditorMarkModel.of(diff, annotations.forScope(current.id()));
        onEdt(() -> {
            Disposable parent = contentParent;
            if (disposed.get() || parent == null) {
                return;
            }
            if (editorMarks == null || !root.equals(editorMarksRoot)) {
                if (editorMarks != null) {
                    editorMarks.dispose();
                }
                editorMarks = ReviewEditorHighlighters.platform(project, root, parent);
                editorMarksRoot = root;
            }
            editorMarks.applyModel(model);
        });
    }

    // ---- snapshots and status (the EDT half) --------------------------------

    /**
     * The refresher's snapshot consumer: stores first, repaints the
     * source-editor marks, rebuilds the workbench state, then renders on the
     * EDT — and consumes the opener's pending finding target (T8/T9) once
     * the snapshot is stored, so a gutter click that beat the content's
     * first load still lands on the finding.
     */
    private void snapshotArrived(ReviewTabModel.Snapshot snapshot) {
        this.snapshot = snapshot;
        refreshEditorMarks(snapshot);
        rebuildWorkbenchState();
        onEdt(() -> {
            JBLabel header = this.header;
            if (header == null) {
                return;
            }
            header.setText(snapshot.headerText());
            refreshDecisionsBar();
            consumePendingFindingTarget();
        });
    }

    /**
     * The gutter-navigation half of the opener's contract (T8/T9): after a
     * snapshot lands, or a workbench flow attaches, the pending target the
     * opener recorded is taken from the project and every workbench flow
     * shows the hunk the finding starts on. With no flow attached the
     * target stays pending: the opener opens the workbench, whose flow
     * takes it. The take is the consumption: found or stale, the target is
     * spent exactly once, and a stale one (a finding the snapshot no longer
     * lists) changes nothing.
     *
     * <p>A pending file target ({@link ReviewTabOpener#openFile}, a source
     * editor's "Back to review") is taken the same way once a snapshot is
     * here: the flows show the hunk at its line, else the file's first hunk.
     * A file the review no longer lists spends the target and changes
     * nothing.</p>
     */
    private void consumePendingFindingTarget() {
        ReviewTabModel.Snapshot rows = snapshot;
        if (flows.isEmpty() || disposed.get() || rows == null) {
            return; // the targets wait for a flow and for the snapshot that lists their files
        }
        List<ReviewWorkbenchFlow> targets = List.copyOf(flows);
        consumeTarget(ReviewTabOpener.takePendingFinding(project), (file, findingId) ->
                findingStart(rows, file, findingId).ifPresent(key -> {
                    for (ReviewWorkbenchFlow flow : targets) {
                        flow.showLine(file, key);
                    }
                }));
        ReviewTabOpener.takePendingFile(project).flatMap(target -> fileAt(rows, target)).ifPresent(at -> {
            for (ReviewWorkbenchFlow flow : targets) {
                flow.showLine(at.file().path(), at.scrollKey());
            }
        });
    }

    /** The start key of the finding with {@code findingId} in {@code file}'s row (pure); none when not listed. */
    static Optional<String> findingStart(ReviewTabModel.Snapshot rows, String file, String findingId) {
        return rows.files().stream().filter(row -> row.path().equals(file))
                .flatMap(row -> row.findings().stream())
                .filter(finding -> finding.id().equals(findingId))
                .map(ReviewTabModel.FindingRow::startKey)
                .findFirst();
    }

    /** {@code target}'s file in {@code rows}, at its line (pure); none when the review no longer lists it. */
    static Optional<FileAt> fileAt(ReviewTabModel.Snapshot rows, ReviewTabOpener.FileTarget target) {
        return rows.files().stream().filter(row -> row.path().equals(target.filePath())).findFirst()
                .map(row -> new FileAt(row, target.scrollKey()));
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

    /**
     * The checkout file a request's right side may be backed by (pure): the
     * file under the scope's {@link ReviewScope#diffRoot()}. Empty for a
     * deleted file, and for a {@link DiffScope#BASE} scope with no worktree
     * of its own, whose diff root is another checkout. Whether the file
     * really backs the side is decided later, by comparing its document with
     * the reviewed text ({@link ReviewDiffContents#choose}); a base scope's
     * right side is {@code HEAD}, so it is file-backed only while the
     * checkout matches {@code HEAD}.
     */
    static Optional<Path> worktreeFileOf(ReviewScope scope, Path root, UnifiedDiff.FileDiff file) {
        if ("D".equals(file.kind())
                || (scope.diffScope() == DiffScope.BASE && scope.worktree().isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(root.resolve(file.path()));
    }

    /**
     * The root the review's paths resolve under (pure over {@code real}):
     * the project's own base path when it is the same directory as the
     * scope's diff root, the diff root otherwise. Git reports the diff root
     * by its real path ({@code /private/tmp/...} on macOS) while the project
     * may name it through a symlink ({@code /tmp/...}); the VFS gives the two
     * spellings different {@code VirtualFile}s, so a review file resolved
     * under the diff root would not be the project's file: not in a search
     * scope over project files, and not the document the user edits.
     */
    static Path checkoutRoot(Path diffRoot, Path projectBase, UnaryOperator<Path> real) {
        if (projectBase == null) {
            return diffRoot;
        }
        Path realDiffRoot = real.apply(diffRoot);
        return realDiffRoot != null && realDiffRoot.equals(real.apply(projectBase)) ? projectBase : diffRoot;
    }

    /** The checkout root for {@code scope}: the resolved one, or the scope's diff root before it is known. */
    private Path rootFor(ReviewScope scope) {
        Path root = checkoutRoot;
        return root == null ? scope.diffRoot() : root;
    }

    /** {@link Path#toRealPath()}, or null when the path cannot be resolved. File I/O: never on the EDT. */
    private static Path realPathOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code file} with only its hunk {@code hunkIndex} (pure); none when the index is out of range. */
    static UnifiedDiff.FileDiff onlyHunk(UnifiedDiff.FileDiff file, int hunkIndex) {
        List<UnifiedDiff.Hunk> hunks = hunkIndex >= 0 && hunkIndex < file.hunks().size()
                ? List.of(file.hunks().get(hunkIndex))
                : List.of();
        return new UnifiedDiff.FileDiff(file.path(), file.kind(), file.insertions(), file.deletions(), file.staged(),
                file.untracked(), hunks, file.oldPath());
    }

    /**
     * A hunk diff's decorations (pure): every unresolved finding on the
     * hunk's rows, CONFIRMED and DISMISSED included so Undo stays reachable,
     * and none of the file's other findings on code the diff folds away.
     * The row keys and the fallback are the hunk's own.
     */
    static ReviewDiffInlays.Decorations hunkDecorations(ReviewTabModel.FileRow row, int hunkIndex,
                                                        List<ReviewAnnotation> scopeFindings) {
        UnifiedDiff.FileDiff hunkOnly = onlyHunk(row.diff(), hunkIndex);
        Set<String> rowKeys = new LinkedHashSet<>();
        for (UnifiedDiff.Hunk hunk : hunkOnly.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                rowKeys.add(line.lineKey());
            }
        }
        List<ReviewAnnotation> onHunk = scopeFindings.stream()
                .filter(finding -> finding.file().equals(row.path()) && !finding.resolved())
                .filter(finding -> rowKeys.contains(finding.startKey()))
                .toList();
        return new ReviewDiffInlays.Decorations(row.path(), rowKeys,
                ReviewFindingPlacement.firstHunkFallback(hunkOnly), onHunk);
    }

    /**
     * A file selected at one of its lines: what a source editor's "Back to
     * review" asks for (the hunk's first changed row). The flows show the
     * hunk that holds {@code scrollKey}.
     */
    record FileAt(ReviewTabModel.FileRow file, String scrollKey) {
        FileAt {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(scrollKey, "scrollKey");
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

    // ---- the batched decisions hand-off --------------------------------------

    /**
     * Builds the decisions bar: the pending decisions since the last
     * hand-off, with one Send below them. Hidden until a decision exists —
     * a bar advertising zero decisions is noise.
     */
    private void buildDecisionsBar() {
        decisionsBar = new JPanel(new BorderLayout());
        decisionsBar.setBorder(new EmptyBorder(2, 8, 2, 8));
        decisionsLabel = new JBLabel();
        sendDecisionsButton = new JButton("Send decisions…");
        sendDecisionsButton.setToolTipText("One prompt to the scope's agent: the findings you agreed with"
                + " (with your comments), and the ones you dismissed as no-action.");
        sendDecisionsButton.addActionListener(event -> sendDecisions());
        // The label above Send, not beside it: a narrow launcher clips the label instead of drawing it
        // under the button.
        decisionsBar.add(decisionsLabel, BorderLayout.NORTH);
        JPanel send = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(2)));
        send.add(sendDecisionsButton);
        decisionsBar.add(send, BorderLayout.SOUTH);
        decisionsBar.setVisible(false);
    }

    /**
     * Re-reads the pending decisions from the store — in-memory, cheap on
     * the EDT — and shows the bar only when a decision exists. The Send
     * button is dead until the backend wiring ({@code triage}) is in place,
     * so a click before the first load completes can never silently do
     * nothing. Every decision path lands here (store listener, snapshot, a
     * completed send), so the bar never lags its own send.
     */
    private void refreshDecisionsBar() {
        JPanel bar = decisionsBar;
        JBLabel label = decisionsLabel;
        if (bar == null || label == null) {
            return;
        }
        AnnotationStore annotations = store;
        ReviewScope current = scope;
        PendingDecisions pending = annotations == null || current == null
                ? new PendingDecisions(0, 0)
                : pendingDecisions(annotations.forScope(current.id()), current.pr().isPresent());
        label.setText(pending.describe());
        bar.setVisible(pending.total() > 0);
        sendDecisionsButton.setEnabled(triage != null && pending.total() > 0 && !sendInFlight);
    }

    /** The tool window's Send decisions button: the shared send, its outcome in the card notice. EDT. */
    private void sendDecisions() {
        sendDecisionsButton.setEnabled(false);
        sendToSession().whenComplete((outcome, error) -> onEdt(() -> {
            refreshDecisionsBar();
            if (outcome != null) {
                outcome.ifPresent(message -> showCardOutcome(message.notice(), message.text()));
            }
        }));
    }

    /** One scope's pull request description, as read from gh (empty when it has none or gh failed). */
    private record PrDescription(String scopeId, Optional<String> text) {
    }

    /**
     * The pull request's description for a PR review (off the EDT): read
     * from gh once per scope on the plugin's executor, the state rebuilt
     * when it arrives; none while it is being read, and none for a local
     * review.
     */
    private Optional<String> prDescriptionOf(ReviewScope scope) {
        if (scope.pr().isEmpty()) {
            return Optional.empty();
        }
        PrDescription cached = prDescription;
        if (cached != null && cached.scopeId().equals(scope.id())) {
            return cached.text();
        }
        ReviewBackend backend = plugin.reviewBackend().orElse(null);
        if (backend != null && !scope.id().equals(prDescriptionInFlight.getAndSet(scope.id()))) {
            backend.pullRequests().pullRequestBody(scope.diffRoot(), scope.pr().get().number())
                    .whenComplete((text, error) -> {
                        prDescription = new PrDescription(scope.id(), error == null ? text : Optional.empty());
                        prDescriptionInFlight.compareAndSet(scope.id(), null);
                        if (!disposed.get()) {
                            submitWorkbenchState();
                        }
                    });
        }
        return Optional.empty();
    }

    /** The notification group a posted review is reported in. */
    static final String REVIEW_NOTIFICATIONS = "Drydock review";

    /** What the pre-submit checks found: why gh cannot post, the fresh-head note, the PR's diff when its head moved. */
    private record SubmitChecks(Optional<String> unavailable, String note, UnifiedDiff freshDiff) {
    }

    /**
     * The PR lane's Submit Review (EDT): the gh check and the fresh-head
     * check off the EDT, then the submit dialog over the plan -- built
     * against the reviewed diff, or against the PR's current diff when its
     * head moved, so a comment a newer push displaced is refused rather than
     * posted onto other code. Completes with the message to show when no
     * dialog opens, empty when it did.
     */
    CompletableFuture<Optional<String>> openSubmit() {
        ReviewScope current = scope;
        ReviewBackend backend = plugin.reviewBackend().orElse(null);
        ReviewTabModel.Snapshot rows = snapshot;
        AnnotationStore annotations = store;
        GitStatusService gitStatus = git;
        if (current == null || backend == null || rows == null || annotations == null || gitStatus == null) {
            return CompletableFuture.completedFuture(Optional.of(STILL_LOADING));
        }
        if (current.pr().isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of("This review is not a pull request review."));
        }
        int number = current.pr().get().number();
        Path root = current.diffRoot();
        GitHubReviewService reviews = backend.reviews();
        UnifiedDiff reviewed = new UnifiedDiff(rows.files().stream().map(ReviewTabModel.FileRow::diff).toList());
        CompletableFuture<SubmitChecks> checks = reviews.unavailableReason(root).thenCompose(reason -> {
            if (reason.isPresent()) {
                return CompletableFuture.completedFuture(new SubmitChecks(reason, "", null));
            }
            return CompletableFuture.supplyAsync(() -> {
                Optional<String> remote = reviews.currentHeadSha(root, number).join();
                Optional<String> local = gitStatus.commitForRefBlocking(current.worktree().orElse(root), "HEAD");
                PrSubmission.FreshHead head = PrSubmission.classify(remote, local);
                if (head.state() != PrSubmission.FreshHead.State.MOVED) {
                    return new SubmitChecks(Optional.empty(), head.note(), null);
                }
                Optional<String> diffText = reviews.pullRequestDiff(root, number).join();
                return diffText.isEmpty()
                        ? new SubmitChecks(Optional.empty(),
                                PrSubmission.FreshHead.uncertain("the pull request's current diff could not be read").note(),
                                null)
                        : new SubmitChecks(Optional.empty(), head.note(), DiffService.parseUnified(diffText.get()));
            }, plugin.executor());
        });
        return checks.thenApply(found -> {
            List<ReviewAnnotation> findings = annotations.forScope(current.id());
            List<ReviewVerdict.Decision> decisions = PrSubmission.decisions(annotations.verdictsFor(current.id()));
            SubmitPlan plan = found.freshDiff() == null
                    ? SubmitPlan.of(findings, decisions, PrSubmission.diffIndex(reviewed, "this diff"),
                            PrSubmission.lineText(reviewed))
                    : SubmitPlan.of(findings, decisions, PrSubmission.diffIndex(found.freshDiff(),
                            "the PR's current head"));
            onEdt(() -> {
                if (disposed.get() || project.isDisposed()) {
                    return;
                }
                new ReviewSubmitDialog(project, number, plan, found.unavailable(), found.note(),
                        SubmitPlan.untriagedCount(findings),
                        (finalPlan, edited, overridden, event, summary) -> post(current, number,
                                finalPlan.withBodies(edited).withBodyFallback(PrSubmission.overriddenNotes(overridden,
                                        annotations.forScope(current.id()), PrSubmission.lineText(reviewed))),
                                edited, event, summary)).show();
            });
            return Optional.<String>empty();
        }).exceptionally(error -> Optional.of("Could not check GitHub: " + rootMessage(error)));
    }

    /**
     * Posts the review and, only when GitHub took it, records the post
     * ({@link PrSubmission#recordPosted}) and reports its URL. A rejected or
     * unavailable post mutates nothing and completes with its message.
     */
    private CompletableFuture<Optional<String>> post(ReviewScope current, int number, SubmitPlan plan,
                                                     Map<ReviewAnnotation.Key, String> edited,
                                                     GitHubReviewRequest.Event event, String summary) {
        ReviewBackend backend = plugin.reviewBackend().orElse(null);
        AnnotationStore annotations = store;
        if (backend == null || annotations == null) {
            return CompletableFuture.completedFuture(Optional.of(STILL_LOADING));
        }
        return backend.reviews().submit(current.diffRoot(), number, event, plan.composeBody(summary), plan.comments())
                .thenApply(outcome -> switch (outcome) {
                    case GitHubReviewService.Posted posted -> {
                        PrSubmission.recordPosted(annotations, current.id(), plan, edited, Instant.now());
                        onEdt(() -> NotificationGroupManager.getInstance().getNotificationGroup(REVIEW_NOTIFICATIONS)
                                .createNotification("Review posted on PR #" + number, posted.reviewUrl(),
                                        NotificationType.INFORMATION)
                                .notify(project));
                        yield Optional.<String>empty();
                    }
                    case GitHubReviewService.Rejected rejected -> Optional.of(rejected.message());
                    case GitHubReviewService.Unavailable unavailable -> Optional.of(unavailable.message());
                });
    }

    /** The IDE property a scope's "Fixes land on" choice is remembered under, keyed by the scope's id. */
    static final String FIXES_LAND_ON_KEY = "drydock.review.fixesLandOnSubPr.";

    /** Whether a pull request review's fixes land on a stacked sub-PR (remembered per scope). Any thread. */
    private boolean stackedSubPr(ReviewScope scope) {
        return scope.pr().isPresent()
                && PropertiesComponent.getInstance(project).getBoolean(FIXES_LAND_ON_KEY + scope.id(), false);
    }

    /** Where the scope's fixes land: a stacked sub-PR of its pull request, when chosen; its own branch otherwise. */
    private FixTarget fixTarget(ReviewScope scope) {
        return stackedSubPr(scope)
                ? new FixTarget.StackedSubPr(scope.pr().orElseThrow().number())
                : FixTarget.SAME_BRANCH;
    }

    /** The outbox's "Fixes land on" choice (EDT): remembered for the scope, then the state is rebuilt. */
    private void fixesLandOn(boolean stacked) {
        ReviewScope current = scope;
        if (current == null || current.pr().isEmpty()) {
            return;
        }
        PropertiesComponent.getInstance(project).setValue(FIXES_LAND_ON_KEY + current.id(), stacked, false);
        submitWorkbenchState();
    }

    /** What the outbox says while another send is running. */
    static final String SEND_RUNNING = "A send to the agent is already running; nothing else was sent.";
    /** What the outbox says when a send was delivered. */
    static final String SEND_DELIVERED = "Sent to the agent. The findings move to Done.";

    /**
     * The one send to the bound session (EDT): the tool window's button and
     * the workbench outbox both come here, and one in-flight flag keeps a
     * second send from starting while one runs. Completes, never
     * exceptionally, with the outcome's message for the caller to show --
     * empty only when nothing needs saying.
     */
    CompletableFuture<Optional<TriageCards.Surfaced>> sendToSession() {
        ReviewTriageActions actions = triage;
        ReviewScope current = scope;
        if (actions == null || current == null) {
            return CompletableFuture.completedFuture(
                    Optional.of(new TriageCards.Surfaced(TriageCards.Notice.WARNING, STILL_LOADING)));
        }
        if (sendInFlight) {
            return CompletableFuture.completedFuture(
                    Optional.of(new TriageCards.Surfaced(TriageCards.Notice.WARNING, SEND_RUNNING)));
        }
        sendInFlight = true;
        CompletableFuture<Optional<TriageCards.Surfaced>> result = new CompletableFuture<>();
        try {
            actions.sendDecisions(current, subjectOf(current), fixTarget(current)).whenComplete((outcome, error) -> onEdt(() -> {
                sendInFlight = false;
                if (error != null) {
                    result.complete(Optional.of(new TriageCards.Surfaced(TriageCards.Notice.ERROR,
                            "Sending the decisions to the agent failed: " + rootMessage(error))));
                    return;
                }
                result.complete(Optional.ofNullable(TriageCards.fixOutcome(outcome.state())));
            }));
        } catch (RuntimeException e) {
            // The scheduling itself was rejected: no future exists, so the flag clears here.
            sendInFlight = false;
            result.complete(Optional.of(new TriageCards.Surfaced(TriageCards.Notice.ERROR,
                    "Sending the decisions to the agent failed: " + rootMessage(e))));
        }
        return result;
    }

    /**
     * The pending decisions of one finding list (pure): the unresolved
     * findings with a decision that no hand-off has carried yet — exactly
     * what {@code sendDecisions} will include. The membership test is the
     * send's own {@link ReviewTriageActions#isPendingDecision}, so the bar
     * and the send can never drift apart.
     */
    record PendingDecisions(int toFix, int dismissed) {

        int total() {
            return toFix + dismissed;
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (toFix > 0) {
                parts.add(toFix + (toFix == 1 ? " finding agreed" : " findings agreed"));
            }
            if (dismissed > 0) {
                parts.add(dismissed + " dismissed");
            }
            return String.join(" · ", parts) + " — not sent to the agent yet";
        }
    }

    static PendingDecisions pendingDecisions(List<ReviewAnnotation> findings) {
        return pendingDecisions(findings, false);
    }

    /** As {@link #pendingDecisions(List)}, under the send's {@link ReviewTriageActions#forSession} rule. */
    static PendingDecisions pendingDecisions(List<ReviewAnnotation> findings, boolean prMode) {
        int toFix = 0;
        int dismissed = 0;
        for (ReviewAnnotation finding : findings) {
            if (!ReviewTriageActions.forSession(finding, prMode)) {
                continue;
            }
            if (finding.triage() == Triage.CONFIRMED) {
                toFix++;
            } else if (finding.triage() == Triage.DISMISSED) {
                dismissed++;
            }
        }
        return new PendingDecisions(toFix, dismissed);
    }

    /**
     * The hand-off's subject (pure): the pull request when the scope
     * carries one, else the checkout directory's name.
     */
    /** The review's mode (pure): a scope that carries a pull request is reviewed as the pull request (spec §8). */
    static WorkbenchState.Mode modeOf(ReviewScope scope) {
        return scope.pr().isPresent() ? WorkbenchState.Mode.PR : WorkbenchState.Mode.LOCAL;
    }

    static String subjectOf(ReviewScope scope) {
        return scope.pr()
                .map(pr -> "PR #" + pr.number())
                .orElseGet(() -> String.valueOf(scope.diffRoot().getFileName()));
    }

    /**
     * The card factory with the scope's triage labels: a pull-request
     * review confirms findings into the posted review ("Confirm"), a local
     * review only agrees the finding is valid ("Agree") — the same store
     * state either way, named for what the click means where there is no
     * submission target. The wrapper runs on the EDT at decoration time,
     * where the volatile scope read is fine.
     */
    private ReviewDiffInlays.CardFactory labeledCards(ReviewDiffInlays.CardFactory cards) {
        return (finding, driftHint) -> {
            JComponent component = cards.card(finding, driftHint);
            ReviewScope current = scope;
            if (component instanceof FindingInlayCard card) {
                boolean prReview = current != null && current.pr().isPresent();
                card.setTriageLabels(prReview ? "Confirm" : "Agree", "Won't fix");
            }
            return component;
        };
    }

    /**
     * The card callbacks' wiring (T9): every card button hands its finding
     * to {@link ReviewTriageActions} — all store mutation and the prompt
     * submission run there, on the injected executor — and every
     * completion, success, refusal, failure or a missing finding included,
     * clears the card's progress on the EDT and surfaces what happened
     * distinctly, without blocking. The write side is reached through the
     * injected suppliers (the production instance's are the provider's
     * volatile fields, filled by {@code wire()}); the agent hand-off is not
     * here at all — decisions reach the agent as one batch through the
     * review tab's decisions bar, never one prompt per click. A {@link RuntimeException}
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
        private final Surface surface;

        /** The failure messages, one prefix per action: synchronous scheduling rejections and asynchronous failures surface the same words. */
        private static final String UPDATING_FAILED = "Updating the finding failed: ";
        private static final String COMMENT_FAILED = "Saving the comment failed: ";

        TriageCards(Supplier<ReviewTriageActions> triage, Supplier<ReviewScope> scope,
                    Supplier<AnnotationStore> store, Surface surface) {
            this.triage = Objects.requireNonNull(triage, "triage");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.store = Objects.requireNonNull(store, "store");
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

        /**
         * The message a decisions hand-off outcome surfaces (pure): a
         * delivered prompt needs none — the store change rebuild shows the
         * SENT state — while no-session, an empty batch, a refusal and a
         * failed write are distinct, never-blank messages; only the failed
         * write is an error, the rest are states the human chose or can act
         * on.
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

    /**
     * The one-line diagnosis of a failure, never blank: the message, or
     * the type when the failure carries none. The same discipline as the
     * app's {@code UiErrors.message} — which is JavaFX-bound and not part
     * of the plugin, so this is the tab's two-line form of it.
     */
    static String rootMessage(Throwable failure) {
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
    // ---- the review workbench (spec §3) -------------------------------------

    /** A workbench pane fed with every new {@link WorkbenchState}. EDT. */
    interface WorkbenchView {
        void render(WorkbenchState state);
    }

    /**
     * Rebuilds the workbench state from the latest snapshot and the
     * workbench's own choices, then pushes it to the attached views on the
     * EDT. Off the EDT: it runs on the snapshot's thread or the plugin's
     * executor, never on the EDT itself.
     */
    private void rebuildWorkbenchState() {
        ReviewTabModel.Snapshot current = this.snapshot;
        ReviewScope scope = this.scope;
        if (current == null || scope == null || disposed.get()) {
            return;
        }
        AnnotationStore annotations = this.store;
        List<ReviewAnnotation> findings = annotations == null ? List.of() : annotations.forScope(scope.id());
        List<ReviewVerdict> verdicts = annotations == null ? List.of() : annotations.verdictsFor(scope.id());
        WorkbenchState state = WorkbenchState.of(subjectOf(scope), modeOf(scope), current,
                WorkbenchState.Filter.ALL, Optional.ofNullable(shownFile), WorkbenchState.View.CHANGES_ONLY,
                findings, inboxChoice,
                sectionsOf(scope, current, verdicts, findings), sectionsChoice)
                .withOutbox(OutboxModel.snapshot(findings, scope.sessionId().isPresent(), scope.pr().isPresent())
                        .withStackedSubPr(stackedSubPr(scope)))
                .withPrDescription(prDescriptionOf(scope));
        workbenchState = state;
        onEdt(() -> {
            if (disposed.get()) {
                return;
            }
            for (WorkbenchView view : List.copyOf(workbenchViews)) {
                view.render(state);
            }
        });
    }

    /** {@link Sections#of} for one diff; empty when the change graph could not be built. */
    private record ComputedSections(UnifiedDiff diff, List<Sections.Section> sections) {
    }

    /**
     * The queue's sections (off the EDT): the scope's stored
     * tour, else the computed sections, over the files {@code shown} lists
     * (the Files tab's: its untracked IDE files are left out here too), with
     * the base commit the latest snapshot used. The change graph parses
     * every changed file, so it is built once per diff and only while there
     * is no tour; a parse failure costs the computed sections, never the
     * state.
     */
    private SectionsModel.Snapshot sectionsOf(ReviewScope scope, ReviewTabModel.Snapshot shown,
                                              List<ReviewVerdict> verdicts, List<ReviewAnnotation> findings) {
        Optional<String> base = this.reviewBase;
        if (base == null) {
            return SectionsModel.Snapshot.EMPTY;
        }
        UnifiedDiff diff = new UnifiedDiff(shown.files().stream().map(ReviewTabModel.FileRow::diff).toList());
        TourStore tourStore = this.tours;
        Optional<ReviewTour> tour = tourStore == null
                ? Optional.empty()
                : tourStore.forScope(scope.id()).map(TourRecord::tour);
        List<Sections.Section> computed = tour.isPresent() ? List.of() : computedSections(diff);
        return SectionsModel.of(diff, tour, computed, verdicts, findings, base);
    }

    /**
     * The computed sections of {@code diff}, or none while they are being
     * built: the build runs once per diff on the plugin's executor, so a
     * large diff's parse never holds up the panes, and its completion
     * rebuilds the state.
     */
    private List<Sections.Section> computedSections(UnifiedDiff diff) {
        ComputedSections cached = this.computedSections;
        // Equal, not identical: the diff is rebuilt from the snapshot's rows on every state.
        if (cached != null && cached.diff().equals(diff)) {
            return cached.sections();
        }
        if (!diff.equals(sectionsInFlight.getAndSet(diff))) {
            try {
                plugin.executor().execute(() -> buildComputedSections(diff));
            } catch (RejectedExecutionException e) {
                // The plugin is shutting down: nothing is left to show the sections.
            }
        }
        return List.of();
    }

    private void buildComputedSections(UnifiedDiff diff) {
        List<Sections.Section> sections;
        try {
            sections = Sections.of(diff, ChangeGraph.of(diff));
        } catch (RuntimeException e) {
            SYS_LOG.log(Level.WARNING, "The review's change graph could not be built; the workbench shows no "
                    + "computed sections: " + e.getMessage(), e);
            sections = List.of();
        }
        computedSections = new ComputedSections(diff, sections);
        sectionsInFlight.compareAndSet(diff, null);
        if (!disposed.get()) {
            submitWorkbenchState();
        }
    }

    private void submitWorkbenchState() {
        try {
            plugin.executor().execute(this::rebuildWorkbenchState);
        } catch (RejectedExecutionException e) {
            // The plugin is shutting down: nothing is left to render the state.
        }
    }

    /** Attaches a workbench view until {@code parent} is disposed, and renders the current state into it. EDT. */
    private void attachWorkbenchView(WorkbenchView view, Disposable parent) {
        workbenchViews.add(view);
        Disposer.register(parent, () -> workbenchViews.remove(view));
        WorkbenchState current = workbenchState;
        if (current != null) {
            view.render(current);
        }
    }

    /** The queue's Sections | Files switch (EDT): remembered, then the state is rebuilt off the EDT. */
    private void workbenchTabChanged(WorkbenchState.Navigator tab) {
        sectionsChoice = new WorkbenchState.SectionsChoice(Optional.of(tab), sectionsChoice.section());
        submitWorkbenchState();
    }


    /** What the inbox says when a finding is no longer in the store. */
    static final String FINDING_GONE = "The finding is gone: the review changed since it was shown.";
    /** What the inbox says before the review's backend is wired. */
    static final String STILL_LOADING = "The review is still loading: nothing was changed.";

    /** The inbox's text for a challenge outcome (pure); empty when there is nothing to report. */
    static Optional<String> challengeMessage(ReviewTriageActions.ChallengeOutcome.State state) {
        return switch (state) {
            case DELIVERED -> Optional.of("Asked the agent. Its answer will appear in the thread.");
            case BLANK -> Optional.empty();
            case NO_SESSION -> Optional.of("No agent session is bound to this review: nothing was sent.");
            case MISSING -> Optional.of(FINDING_GONE);
            case REFUSED -> Optional.of("The agent's session did not take the question. Your comment is kept.");
            case FAILED -> Optional.of("Sending the question failed. Your comment is kept.");
        };
    }

    /** The scope of {@code project}'s live review, once one is loaded. Any thread. */
    static Optional<ReviewScope> liveScope(Project project) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        return provider == null || provider.disposed.get() ? Optional.empty() : Optional.ofNullable(provider.scope);
    }

    /**
     * A findings inbox over the live review for a new workbench editor,
     * rendered from every {@link WorkbenchState} until {@code parent} is
     * disposed. Empty when the project has no live Review content. EDT.
     */
    static Optional<JComponent> hostedInbox(Project project, Disposable parent) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null || provider.disposed.get()) {
            return Optional.empty();
        }
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(provider.new InboxActions(), Clock.systemDefaultZone());
        Disposer.register(parent, inbox);
        provider.attachWorkbenchView(inbox::render, parent);
        provider.inboxes.add(inbox);
        Disposer.register(parent, () -> provider.inboxes.remove(inbox));
        return Optional.of(inbox);
    }

    /**
     * Shows a finding the review lists (a take's red flag): every workbench
     * flow moves to the hunk the finding starts on. Nothing happens for a
     * finding the review no longer lists. EDT.
     */
    private void showFinding(String findingId) {
        ReviewTabModel.Snapshot rows = snapshot;
        if (rows == null) {
            return;
        }
        for (ReviewTabModel.FileRow file : rows.files()) {
            for (ReviewTabModel.FindingRow finding : file.findings()) {
                if (finding.id().equals(findingId)) {
                    showLine(file.path(), finding.startKey());
                    return;
                }
            }
        }
    }

    /** Every workbench flow shows the hunk of {@code file} that holds {@code lineKey}. EDT. */
    private void showLine(String file, String lineKey) {
        for (ReviewWorkbenchFlow flow : List.copyOf(flows)) {
            flow.showLine(file, lineKey);
        }
    }

    /**
     * The outbox's session lane over the live review for a new workbench
     * editor, rendered from every {@link WorkbenchState} until
     * {@code parent} is disposed. Its Send goes through
     * {@link #sendToSession}, the tool window's own send. Empty when the
     * project has no live Review content. EDT.
     */
    static Optional<JComponent> hostedOutbox(Project project, Disposable parent) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null || provider.disposed.get()) {
            return Optional.empty();
        }
        ReviewWorkbenchOutbox outbox = new ReviewWorkbenchOutbox(new ReviewWorkbenchOutbox.Actions() {
            @Override
            public CompletableFuture<Optional<String>> send() {
                return provider.sendToSession().thenApply(
                        message -> Optional.of(message.map(TriageCards.Surfaced::text).orElse(SEND_DELIVERED)));
            }

            @Override
            public void fixesLandOn(boolean stackedSubPr) {
                provider.fixesLandOn(stackedSubPr);
            }

            @Override
            public CompletableFuture<Optional<String>> submitReview() {
                return provider.openSubmit();
            }

            @Override
            public void showFinding(ReviewAnnotation finding) {
                provider.showLine(finding.file(), finding.startKey());
            }
        });
        provider.attachWorkbenchView(outbox::render, parent);
        return Optional.of(outbox);
    }

    /**
     * The flow review over the live review for a new workbench editor: its
     * controller, fed every {@link WorkbenchState} until {@code parent} is
     * disposed, and its centre, disposed with {@code parent}. A pending
     * finding or file target goes to it. Empty when the project has no live
     * Review content. EDT.
     */
    static Optional<ReviewWorkbenchEditor.HostedFlow> hostedFlow(Project project, Disposable parent) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null || provider.disposed.get()) {
            return Optional.empty();
        }
        ReviewWorkbenchFlow flow = new ReviewWorkbenchFlow(
                () -> Optional.ofNullable(provider.scope).map(ReviewScope::id), provider.flowMarks,
                provider::workbenchTabChanged);
        ReviewWorkbenchFlowView centre = new ReviewWorkbenchFlowView(project, flow, provider.new FlowActions());
        Disposer.register(parent, centre);
        flow.addListener(() -> provider.flowMoved(flow));
        provider.attachWorkbenchView(flow::render, parent);
        provider.flows.add(flow);
        Disposer.register(parent, () -> provider.flows.remove(flow));
        provider.consumePendingFindingTarget();
        return Optional.of(new ReviewWorkbenchEditor.HostedFlow(flow, centre));
    }

    /**
     * A flow showed another hunk (EDT): when its file differs from the one
     * recorded, the state is rebuilt, so the inbox's This File scope
     * follows the hunk shown. The finish screen keeps the last file.
     */
    private void flowMoved(ReviewWorkbenchFlow flow) {
        String file = flow.current().map(FlowModel.Hunk::file).orElse(null);
        if (file != null && !file.equals(shownFile)) {
            shownFile = file;
            submitWorkbenchState();
        }
    }

    /**
     * AC15 for a hunk diff (any thread): the file changed after the diff,
     * so the status shows the changed-since notice and one reload runs,
     * unless {@link MismatchMemo} has seen this exact mismatch reload
     * already (a file whose text can never match git's, which would reload
     * in a loop).
     */
    private void hunkChangedSince(ReviewScope current, String path, String left, String right) {
        ReviewTabRefresher reloads = refresher;
        if (reloads == null) {
            return;
        }
        // The flag before the reload: the reload's synchronous LOADING report
        // must not erase the notice; only the fresh result's terminal state does.
        changedSincePending = true;
        onEdt(() -> renderStatus(ReviewTabStatus.LOADING, ReviewTabStatus.CHANGED_SINCE_MESSAGE));
        if (mismatchMemo.schedulesReload(path, left, right)) {
            reloads.reload(current);
        }
    }

    /** The flow centre's side of the live review. */
    private final class FlowActions implements ReviewWorkbenchFlowView.Actions {

        /**
         * The hunk's diff, built off the EDT: the snapshot's row for
         * {@code path}, both sides' texts, and the git fragments, scrolled to
         * {@code scrollKey}. Empty when the texts no longer match the diff's
         * rows (the file changed since), which also reloads the review
         * ({@link #hunkChangedSince}).
         */
        @Override
        public CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(String path, int hunkIndex,
                                                                                        String scrollKey) {
            ReviewScope current = scope;
            ReviewContents texts = contents;
            ReviewTabModel.Snapshot rows = snapshot;
            if (current == null || texts == null || rows == null || disposed.get()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            try {
                return CompletableFuture.supplyAsync(() -> {
                    Optional<ReviewTabModel.FileRow> row = rows.files().stream()
                            .filter(candidate -> candidate.path().equals(path))
                            .findFirst();
                    if (row.isEmpty()) {
                        return Optional.<ReviewDiffRequests.RequestContext>empty();
                    }
                    UnifiedDiff.FileDiff file = row.get().diff();
                    String left = texts.leftText(current, file);
                    String right = texts.rightText(current, file);
                    if (!ReviewContents.matchesRows(left, file, false) || !ReviewContents.matchesRows(right, file, true)) {
                        hunkChangedSince(current, path, left, right);
                        return Optional.<ReviewDiffRequests.RequestContext>empty();
                    }
                    mismatchMemo.reset(); // fresh data matched: the next mismatch again means a change
                    // Only this hunk's fragments: the viewer then folds the file's other changes like
                    // unchanged code, and the card shows the change its section is about.
                    List<LineFragment> fragments = ReviewFragments.fragmentsFor(onlyHunk(file, hunkIndex), left, right);
                    Optional<Path> worktreeFile = worktreeFileOf(current, rootFor(current), file);
                    ReviewDiffContents.warm(worktreeFile);
                    return Optional.of(new ReviewDiffRequests.RequestContext(path, ReviewDiffRequests.plan(left,
                            right, fragments, ReviewDiffRequests.scrollTarget(scrollKey)), worktreeFile));
                }, plugin.executor());
            } catch (RejectedExecutionException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        /**
         * The reviewer's comment on the hunk, stored as a human finding on
         * its changed lines ({@link #addComment}): the added lines, or the
         * removed ones for a hunk that only removes. Refused when the
         * snapshot no longer lists the hunk.
         */
        @Override
        public CompletableFuture<Optional<String>> comment(FlowModel.Hunk hunk, String text) {
            ReviewTabModel.Snapshot rows = snapshot;
            Optional<DiffSelection.Range> range = rows == null ? Optional.empty() : hunkRange(rows, hunk);
            return range.isEmpty()
                    ? CompletableFuture.completedFuture(Optional.of(FINDING_GONE_HUNK))
                    : addComment(range.get(), text, Severity.QUESTION)
                            .thenApply(message -> message.filter(shown -> !COMMENT_ADDED.equals(shown)));
        }

        @Override
        public CompletableFuture<Optional<String>> submitReview() {
            return openSubmit();
        }

        @Override
        public CompletableFuture<Optional<String>> sendToSession() {
            return ReviewTabProvider.this.sendToSession().thenApply(
                    message -> Optional.of(message.map(TriageCards.Surfaced::text).orElse(SEND_DELIVERED)));
        }

        /**
         * A hunk's own decoration layer and gutter "+", as the file view's:
         * the file's finding cards, gutter marks and ranges, read off the EDT
         * now and on every store change, until {@code lifetime} ends.
         */
        @Override
        public Consumer<FrameDiffTool.DiffViewer> decorator(String path, int hunkIndex, Disposable lifetime) {
            ReviewDiffInlays layer = new ReviewDiffInlays(ReviewDiffInlays.platformEdt(), labeledCards(
                    ReviewDiffInlays.triageCards(Clock.systemDefaultZone(), cardActions)));
            ReviewSymbolNavigation symbolNavigation = new ReviewSymbolNavigation();
            ReviewGutterComments gutter = new ReviewGutterComments(
                    target -> resolve(target).isPresent(),
                    target -> compose(project, Optional.of(target), ReviewWorkbenchInbox.Composer.Mode.COMMENT));
            AtomicBoolean ended = new AtomicBoolean();
            Disposer.register(lifetime, () -> {
                ended.set(true);
                layer.dispose();
            });
            Runnable refresh = () -> {
                try {
                    plugin.executor().execute(() -> {
                        ReviewScope current = scope;
                        AnnotationStore annotations = store;
                        ReviewTabModel.Snapshot rows = snapshot;
                        if (current == null || annotations == null || rows == null || ended.get()) {
                            return;
                        }
                        rows.files().stream().filter(row -> row.path().equals(path)).findFirst().ifPresent(row -> {
                            ReviewDiffInlays.Decorations decorations = hunkDecorations(row, hunkIndex,
                                    annotations.forScope(current.id()));
                            onEdt(() -> {
                                if (!ended.get()) {
                                    layer.setDecorations(decorations);
                                }
                            });
                        });
                    });
                } catch (RejectedExecutionException e) {
                    // The plugin is shutting down: the card goes with it.
                }
            };
            AnnotationStore annotations = store;
            if (annotations != null) {
                Runnable unsubscribe = annotations.addChangeListener(key -> refresh.run());
                Disposer.register(lifetime, unsubscribe::run);
            }
            refresh.run();
            return viewer -> {
                layer.viewerChanged(viewer);
                gutter.viewerChanged(viewer);
                symbolNavigation.viewerChanged(viewer);
            };
        }
    }

    /** What the flow says when a hunk to comment on is no longer in the review. */
    static final String FINDING_GONE_HUNK = "The hunk is gone: the review changed since it was shown.";

    /**
     * The changed lines of {@code hunk} in {@code rows} as one comment range
     * (pure): the new side over its added lines, or the old side over its
     * removed lines for a hunk that only removes. Empty when the snapshot no
     * longer lists the hunk.
     */
    static Optional<DiffSelection.Range> hunkRange(ReviewTabModel.Snapshot rows, FlowModel.Hunk hunk) {
        return rows.files().stream().filter(row -> row.path().equals(hunk.file())).findFirst()
                .filter(row -> hunk.index() < row.diff().hunks().size()
                        && hunk.index() < row.hunkDigests().size()
                        && row.hunkDigests().get(hunk.index()).equals(hunk.digest()))
                .flatMap(row -> {
                    UnifiedDiff.Hunk lines = row.diff().hunks().get(hunk.index());
                    boolean adds = lines.lines().stream().anyMatch(line -> line.kind() == UnifiedDiff.Line.Kind.ADD);
                    UnifiedDiff.Line.Kind kind = adds ? UnifiedDiff.Line.Kind.ADD : UnifiedDiff.Line.Kind.DEL;
                    int[] span = lines.lines().stream().filter(line -> line.kind() == kind)
                            .mapToInt(line -> (adds ? line.newLine() : line.oldLine()).orElse(0))
                            .filter(n -> n > 0).sorted().toArray();
                    return span.length == 0 ? Optional.empty() : DiffSelection.resolve(row.diff(),
                            adds ? DiffSelection.Side.NEW : DiffSelection.Side.OLD, span[0], span[span.length - 1]);
                });
    }

    /** What the inbox says for a selection with no reviewable range. */
    static final String NO_RANGE =
            "Select changed lines of the diff: a review comment goes on the lines the change touches.";

    /**
     * Opens the inbox composer for a diff selection (the selection actions):
     * the selection is resolved within one hunk of the shown file's diff; a
     * selection with no range says why instead. EDT.
     */
    static void compose(Project project, Optional<ReviewSelection.Target> target,
                        ReviewWorkbenchInbox.Composer.Mode mode) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null || provider.disposed.get()) {
            return;
        }
        Optional<DiffSelection.Range> range = target.flatMap(provider::resolve);
        for (ReviewWorkbenchInbox inbox : List.copyOf(provider.inboxes)) {
            range.ifPresentOrElse(r -> inbox.openComposer(new ReviewWorkbenchInbox.Composer(mode, r)),
                    () -> inbox.notice(NO_RANGE));
        }
    }

    /** A selection target's range in the latest snapshot's file diff; empty when the file or the range is not there. */
    private Optional<DiffSelection.Range> resolve(ReviewSelection.Target target) {
        ReviewTabModel.Snapshot current = snapshot;
        if (current == null) {
            return Optional.empty();
        }
        return current.files().stream()
                .filter(row -> row.path().equals(target.path()))
                .findFirst()
                .flatMap(row -> DiffSelection.resolve(row.diff(), target.side(), target.first(), target.last()));
    }

    /**
     * Stores the reviewer's comment as a human finding at the range: confirmed
     * and, in a local review, not posted to a PR — it is an instruction for
     * the session. Off the EDT, on the plugin's executor.
     */
    private CompletableFuture<Optional<String>> addComment(DiffSelection.Range range, String text, Severity severity) {
        AnnotationStore annotations = store;
        ReviewScope current = scope;
        if (annotations == null || current == null) {
            return CompletableFuture.completedFuture(Optional.of(STILL_LOADING));
        }
        return CompletableFuture.supplyAsync(() -> {
            annotations.upsert(ReviewAnnotation.human(current.id(), range.file(), range.startKey(), range.endKey(),
                    new ReviewAnnotation.Message("You", Instant.now(), text), severity).withPostToPr(false));
            return Optional.of(COMMENT_ADDED);
        }, plugin.executor());
    }

    /** What the inbox says once a comment is stored. */
    static final String COMMENT_ADDED = "Comment added to the Outbox.";

    /** The inbox's text for a selection question's delivery (pure). */
    static String askMessage(PromptOutcome outcome) {
        return switch (outcome) {
            case DELIVERED -> "Asked the agent. Its answer will arrive as a finding to triage.";
            case REFUSED -> "The agent's session did not take the question: nothing was asked.";
            case FAILED -> "Sending the question failed: nothing was asked.";
        };
    }

    /** What the inbox says when no session can be asked. */
    static final String ASK_NO_SESSION = "No agent session is bound to this review: nothing was asked.";

    /**
     * Asks the scope's bound session about a selection: a pending question
     * is minted, its range remembered ({@link SelectionAsks}), and
     * {@link ReviewInstructions#forSelectionAsk} submitted. A question that
     * did not reach the agent is forgotten again. The submission is a
     * blocking PTY write, so it runs on the plugin's executor.
     */
    private CompletableFuture<Optional<String>> askAboutSelection(DiffSelection.Range range, String question) {
        ReviewScope current = scope;
        PendingQuestions questions = pendingQuestions;
        SelectionAsks asks = selectionAsks;
        if (current == null || questions == null || asks == null) {
            return CompletableFuture.completedFuture(Optional.of(STILL_LOADING));
        }
        Optional<ManagedSessionId> session = current.sessionId();
        if (session.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of(ASK_NO_SESSION));
        }
        return CompletableFuture.supplyAsync(() -> {
            PendingQuestions.PendingAsk ask = questions.mint(current.id(),
                    range.file() + ":" + range.startKey() + ".." + range.endKey(), session.get());
            asks.remember(ask.questionId(), new SelectionAsks.Asked(current.id(), range, question, ask.askedAt()));
            PromptOutcome outcome = promptSubmitter.apply(session.get(), ReviewInstructions.forSelectionAsk(
                    ask.questionId(), current.id(), range.file(), range.startKey(), range.endKey(), question));
            if (outcome != PromptOutcome.DELIVERED) {
                asks.forget(ask.questionId());
            }
            return Optional.of(askMessage(outcome));
        }, plugin.executor());
    }

    /** The inbox's actions on the live review: the triage write side, the flows, the inbox choice. */
    private final class InboxActions implements ReviewWorkbenchInbox.Actions {

        @Override
        public CompletableFuture<Optional<String>> postToPr(ReviewAnnotation finding) {
            ReviewTriageActions actions = triage;
            return actions == null ? loading() : actions.confirm(finding.key()).thenApply(ReviewTabProvider::goneIfEmpty);
        }

        @Override
        public CompletableFuture<Optional<String>> requestFix(ReviewAnnotation finding) {
            ReviewTriageActions actions = triage;
            return actions == null ? loading() : actions.requestFix(finding.key()).thenApply(ReviewTabProvider::goneIfEmpty);
        }

        @Override
        public CompletableFuture<Optional<String>> dismiss(ReviewAnnotation finding) {
            ReviewTriageActions actions = triage;
            return actions == null ? loading() : actions.dismiss(finding.key()).thenApply(ReviewTabProvider::goneIfEmpty);
        }

        @Override
        public CompletableFuture<Optional<String>> undo(ReviewAnnotation finding) {
            ReviewTriageActions actions = triage;
            return actions == null ? loading() : actions.undo(finding.key()).thenApply(ReviewTabProvider::goneIfEmpty);
        }

        @Override
        public CompletableFuture<Optional<String>> comment(ReviewAnnotation finding, String text) {
            ReviewTriageActions actions = triage;
            return actions == null ? loading() : actions.comment(finding.key(), text).thenApply(outcome ->
                    outcome.state() == ReviewTriageActions.CommentOutcome.State.MISSING
                            ? Optional.of(FINDING_GONE)
                            : Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<String>> challenge(ReviewAnnotation finding, String text) {
            ReviewTriageActions actions = triage;
            ReviewScope current = scope;
            return actions == null || current == null
                    ? loading()
                    : actions.challenge(finding.key(), text, current).thenApply(outcome -> challengeMessage(outcome.state()));
        }

        @Override
        public void showInDiff(ReviewAnnotation finding) {
            showLine(finding.file(), finding.startKey());
        }

        @Override
        public void choose(WorkbenchState.InboxChoice choice) {
            inboxChoice = Objects.requireNonNull(choice, "choice");
            submitWorkbenchState();
        }

        @Override
        public boolean canStartReview() {
            return scope != null && project.getBasePath() != null;
        }

        @Override
        public boolean sphinxReviewAvailable() {
            ReviewScope current = scope;
            return current != null && WorktreeReview.sphinxAvailable(current.repoRoot());
        }

        @Override
        public void startReview(ReviewTabOpener.ReviewStart start) {
            ReviewScope current = scope;
            String base = project.getBasePath();
            if (current != null && base != null) {
                ReviewTabOpener.startReview(project, Path.of(base), current, start);
            }
        }

        @Override
        public CompletableFuture<Optional<String>> submit(ReviewWorkbenchInbox.Composer composer, String text,
                                                          Severity severity) {
            return switch (composer.mode()) {
                case COMMENT -> addComment(composer.range(), text, severity);
                case ASK -> askAboutSelection(composer.range(), text);
            };
        }

        private CompletableFuture<Optional<String>> loading() {
            return CompletableFuture.completedFuture(Optional.of(STILL_LOADING));
        }
    }

    private static Optional<String> goneIfEmpty(Optional<ReviewAnnotation> updated) {
        return updated.isEmpty() ? Optional.of(FINDING_GONE) : Optional.empty();
    }

    /** The live review's checkout root and latest snapshot, for the review search scope. */
    record ReviewFiles(Path root, ReviewTabModel.Snapshot snapshot) {
    }

    /** The live review's files; empty before its first snapshot or without a live Review content. Any thread. */
    static Optional<ReviewFiles> reviewFiles(Project project) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null || provider.disposed.get()) {
            return Optional.empty();
        }
        ReviewScope scope = provider.scope;
        ReviewTabModel.Snapshot snapshot = provider.snapshot;
        return scope == null || snapshot == null
                ? Optional.empty()
                : Optional.of(new ReviewFiles(provider.rootFor(scope), snapshot));
    }

    /** Names the workbench tab after the review's subject ({@link #subjectOf}). EDT. */
    private void nameWorkbench(ReviewScope scope) {
        if (disposed.get() || project.isDisposed()) {
            return;
        }
        ReviewWorkbenchFile file = ReviewWorkbenchFile.of(project);
        file.setSubject(subjectOf(scope));
        FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file);
    }

    static void reloadFromChangesLink(Project project) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null) {
            SYS_LOG.log(Level.INFO, "The Changes link found no live '" + TAB_NAME + "' content in "
                    + project.getName() + "; the tab's own first load computes the diff");
            return;
        }
        provider.userReload();
    }

    /**
     * The workbench's Refresh (EDT): a user-triggered load, so open documents
     * are saved first (Q9). Before a scope is resolved (still loading, or
     * the first load never completed) the whole load path re-runs, showing
     * its own placeholder and terminal state; nothing happens without a live
     * Review content.
     */
    static void refresh(Project project) {
        ReviewTabProvider provider = project.getUserData(LIVE_PROVIDER);
        if (provider == null || provider.disposed.get()) {
            return;
        }
        if (provider.scope == null || provider.refresher == null) {
            ReviewDocumentSaves.saveOpenDocuments();
            provider.renderStatus(ReviewTabStatus.LOADING, ReviewTabStatus.LOADING.message());
            provider.submitLoad();
            return;
        }
        provider.userReload();
    }

    /** A user-triggered re-run (workbench Refresh, Changes link): save first (Q9, EDT), then reload. */
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
