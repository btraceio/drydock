package app.drydock.intellij.review;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.tasks.WorktreeReview;
import app.drydock.review.ReviewScope;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens the Drydock Review tab — what the console strip's Changes link
 * does on both of its paths (AC18): in the session's own window
 * ({@code AgentConsoleEditor}'s non-hosted branch) and in the hosted jump
 * ({@code AgentConsoles}' "Changes ↗", which first raises that window).
 *
 * <p><strong>Order.</strong> The Changes link is a user-triggered load
 * (Q9): open documents are saved first, on the EDT, before any background
 * work. The loading placeholder then appears at once, before any
 * background work: a visible "Loading review…" progress whose
 * body is the mint of the checkout's review scope, bound to {@code
 * linkSession} — the session whose Changes link opened the tab (Q7, T8).
 * Only when that mint settles does the open path activate the dedicated
 * Review tool window. That ordering is what makes the
 * binding flow: the window's own load (T13's provider) resolves the same
 * checkout read-mostly, so by the time it runs the registry already holds
 * the link-bound scope and the provider hands it out unchanged instead of
 * minting unbound (AC19). Once the window is active, an already-loaded
 * content re-runs its diff (spec "Refresh": the Changes link re-runs the
 * diff); a content that was only just created is computing its first
 * diff already.</p>
 *
 * <p><strong>The host is the dedicated window</strong> (T7/T8): the
 * Review tree lives in the {@code Drydock Review} tool window —
 * {@link ReviewToolWindowFactory#TOOL_WINDOW_ID}, the one pinned id the
 * factory and the descriptor share. That window carries exactly one
 * content, so activating it <em>is</em> selecting it: no
 * {@code ChangesViewContentManager} call remains on the open path (T7
 * removed the subtab whose host id used to vary with the Commit
 * window's availability).</p>
 *
 * <p><strong>Threading.</strong> {@link #open} is called on the EDT; the
 * mint runs inside the visible progress task's background body, never on
 * the EDT and never in a tree builder; the host calls land on the EDT
 * through the task's own completion.</p>
 */
public final class ReviewTabOpener {

    private static final Logger LOG = System.getLogger(ReviewTabOpener.class.getName());

    /**
     * The open path's platform calls: the dedicated Review window is
     * activated by its pinned id — one content, so activation is the
     * whole selection, and no {@code ChangesViewContentManager} call
     * remains. Faked in tests.
     */
    interface Host {
        /** Activates the dedicated Review tool window ({@link ReviewToolWindowFactory#TOOL_WINDOW_ID}). */
        void activate(Project project, String toolWindowId);

        /** Q9: saves the open documents ({@link ReviewDocumentSaves#saveOpenDocuments}); EDT, write intent. */
        void saveDocuments(Project project);

        /** Re-runs the diff of the live content's scope ({@code ReviewTabProvider}); EDT, after the activation. */
        void reloadTab(Project project);
    }

    /**
     * The scope source the open path mints through: {@link
     * ReviewTabScopes#resolve}, whose mint is bound to {@code linkSession}
     * (Q7) and never rebinds a scope the registry already holds (AC19).
     * Faked in tests.
     */
    interface ScopeSource {
        CompletableFuture<ReviewScope> resolve(Path checkout, Optional<ManagedAgentSession> linkSession);
    }

    /**
     * The open path's loading state (AC18): shows {@code text} at once,
     * runs {@code background} off the EDT, then {@code done} on the EDT.
     * One seam because in production they are one thing — a visible
     * progress task whose body is the background work — so the placeholder
     * cannot appear after the work has already started. In tests the fake
     * records the placeholder and then drives the two halves.
     */
    interface Loading {
        void run(Project project, String text, Runnable background, Runnable done);
    }

    private static final ReviewTabOpener PRODUCTION = new ReviewTabOpener(
            new ProductionHost(), ReviewTabOpener::mintScope, new ProgressLoading(), new PreparationOffers());

    private final Host host;
    private final ScopeSource scopes;
    private final Loading loading;
    private final Preparation preparations;

    ReviewTabOpener(Host host, ScopeSource scopes, Loading loading, Preparation preparations) {
        this.host = Objects.requireNonNull(host, "host");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.loading = Objects.requireNonNull(loading, "loading");
        this.preparations = Objects.requireNonNull(preparations, "preparations");
    }

    /**
     * Opens the Review tab in {@code project}'s window — the session's own
     * window on both strip paths, because that is what each caller passes.
     * EDT.
     *
     * <p>{@code linkSession} is the session whose Changes link opened the
     * tab; the mint is bound to it (Q7), so that agent's {@code review_*}
     * calls reach the scope the tab shows. An empty link (a direct open of
     * the tool window) mints unbound.</p>
     */
    public static void open(Project project, Optional<ManagedAgentSession> linkSession) {
        PRODUCTION.openIn(project, linkSession);
    }

    /**
     * Opens Review focused on one finding (AC5's gutter navigation): the
     * target is recorded <em>before</em> anything the open path does, so
     * it works even when the dedicated window's content has never been
     * created — activation creates the content lazily (T7's factory), and
     * the recorded target is already waiting wherever that content's
     * first snapshot lands (T9 consumes it). EDT.
     *
     * <p>The gutter click carries no link session, so its mint is unbound
     * — the same scope a direct open of the tool window mints. A malformed
     * target (null/blank path or id) records nothing: the window still
     * opens, the selection simply never changes.</p>
     */
    public static void openFinding(Project project, String file, String findingId) {
        PRODUCTION.openFindingIn(project, file, findingId);
    }

    /** The open path over the seams; see {@link #open}. */
    void openIn(Project project, Optional<ManagedAgentSession> linkSession) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(linkSession, "linkSession");
        if (project.isDisposed()) {
            return;
        }
        // Q9: a user-triggered load saves open documents first, on the EDT,
        // before the background work the placeholder fronts.
        // (The save itself takes the write-intent lock: ReviewDocumentSaves.)
        host.saveDocuments(project);
        // The placeholder next (AC18): the click visibly does something
        // before any background work starts, and before the window is
        // activated. The mint hands its outcome to the EDT half through the
        // reference — one open, one hand-off; two opens racing is the same
        // accepted race the mint documents, and each finish reads only its
        // own outcome.
        AtomicReference<Outcome> minted = new AtomicReference<>();
        loading.run(project, ReviewTabStatus.LOADING.message(),
                () -> mint(project, linkSession, minted),
                () -> finish(project, minted.get()));
    }

    /** The gutter path over the seams; see {@link #openFinding}. */
    void openFindingIn(Project project, String file, String findingId) {
        Objects.requireNonNull(project, "project");
        if (project.isDisposed()) {
            return;
        }
        // Before activation — the whole point of the seam: the content the
        // activation may create consumes the target from the project, so
        // the target must be recorded first, from the click, with no
        // content in sight.
        FindingTarget.of(file, findingId).ifPresent(target -> recordPendingFinding(project, target));
        openIn(project, Optional.empty());
    }

    /**
     * The background half: the mint of the window's checkout scope, bound
     * to the link session (T8, Q7), plus the data probe that decides the
     * empty-review offer. The minted value itself is the tab's to read —
     * this path only establishes it in the registry. The probe runs here,
     * off the EDT, because it reads the persisted stores.
     */
    private void mint(Project project, Optional<ManagedAgentSession> linkSession, AtomicReference<Outcome> into) {
        Path checkout = checkoutOf(project);
        try {
            ReviewScope scope = scopes.resolve(checkout, linkSession).join();
            into.set(new Outcome(scope, preparations.hasData(scope)));
        } catch (RuntimeException e) {
            // The open is not lost: the tab's own load resolves the same
            // checkout and shows the failure in its own states (AC22).
            // What this loses is only this link's binding — the same
            // accepted race ReviewTabScopes documents for two loads of one
            // checkout.
            LOG.log(Level.WARNING,
                    "The Changes link could not bind the Review tab's scope for " + checkout, rootCause(e));
        }
    }

    /**
     * The EDT half after the mint: activate the dedicated Review window —
     * its single content needs no separate selection (T7) — then re-run its
     * diff. When the minted scope carries no review data (no findings, no
     * tour — a checkout no review was ever prepared for), the open ends in
     * the preparation offer instead of a silently empty window: the review
     * data lives with the checkout a review ran against, and a session that
     * never had one must be told so, with the launch one confirmation away.
     */
    private void finish(Project project, Outcome outcome) {
        if (project.isDisposed()) {
            return;
        }
        selectTab(project);
        if (outcome == null || outcome.hasData()) {
            return; // mint failed (already warned), or a review exists for this checkout
        }
        Path checkout = checkoutOf(project);
        if (preparations.offer(project, checkout, outcome.scope())) {
            preparations.launch(project, checkout, outcome.scope());
        }
    }

    /**
     * The EDT half: activate the dedicated Review window — its single
     * content needs no separate selection (T7) — then re-run its diff.
     */
    private void selectTab(Project project) {
        if (project.isDisposed()) {
            return;
        }
        host.activate(project, ReviewToolWindowFactory.TOOL_WINDOW_ID);
        host.reloadTab(project);
    }

    /**
     * The mint's outcome handed from the background half to the EDT half:
     * the scope (never null here — a failed mint stores nothing) and whether
     * it already carries review data. The flag is computed off the EDT,
     * where the stores are read.
     */
    private record Outcome(ReviewScope scope, boolean hasData) { }

    /**
     * The empty-review offer's seams: the data probe (off the EDT, reading
     * the persisted stores), the question (EDT), and the launch (EDT;
     * resolves the main root and branch itself). Faked in tests.
     */
    interface Preparation {

        /**
         * Whether {@code scope} already carries review data — at least one
         * finding or a tour. In-memory store reads; called off the EDT.
         */
        boolean hasData(ReviewScope scope);

        /**
         * Asks whether to run the review preparation for {@code scope}'s
         * checkout; true on acceptance, false on dismissal. EDT.
         */
        boolean offer(Project project, Path checkout, ReviewScope scope);

        /**
         * Launches the review-preparation flow (the task window's Review
         * flow) for {@code scope}'s checkout. EDT; the checkout's main root
         * and branch are resolved on a background thread, never here.
         */
        void launch(Project project, Path checkout, ReviewScope scope);
    }

    /** The project's checkout — the window's own scope, which on both strip
     * paths is the linked session's (AC18).
     */
    private static Path checkoutOf(Project project) {
        String base = project.getBasePath();
        if (base == null) {
            throw new IllegalStateException(project + " has no base path");
        }
        return Path.of(base);
    }

    // ---- the pending gutter-navigation target (AC5, the T8 seam) ----------

    /** The project user-data key carrying the pending finding target. */
    private static final Key<FindingTarget> PENDING_FINDING = Key.create("drydock.review.pendingFinding");

    /**
     * Records the pending target on the project — the seam between the
     * gutter click and the window's content. The target must live on the
     * project, not the content: the click can run before any content
     * exists, and the content T7's factory creates lazily on activation
     * finds the target already recorded. Project-scoped user data, never
     * a global a test could replace; deterministic on every path — a
     * later record overwrites, a take clears, project disposal discards.
     *
     * <p><strong>Threading.</strong> The record, the peek and the take are
     * atomic against each other: each runs inside a critical section
     * synchronized on the project itself — a per-project monitor, never
     * a global lock — because 263's {@code Project} extends only
     * {@code UserDataHolder}, whose platform's atomic put-if-absent
     * ({@code putUserDataIfAbsent}) lives one level down on
     * {@code UserDataHolderEx} and is not statically callable on a
     * project. The critical sections hold only the two user-data calls
     * and nothing else, so nothing else can be locked inside them; the
     * EDT is the natural caller of all three (the gutter click records
     * there, the content's rebuild completion takes there), but the
     * contract holds from any thread.</p>
     */
    static void recordPendingFinding(Project project, FindingTarget target) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(target, "target");
        synchronized (project) {
            project.putUserData(PENDING_FINDING, target);
        }
    }

    /**
     * The recorded target without consuming it — the peek a consumer can
     * check first. Atomic against the record and the take: the same
     * per-project critical section as its siblings.
     */
    static Optional<FindingTarget> pendingFinding(Project project) {
        Objects.requireNonNull(project, "project");
        synchronized (project) {
            return Optional.ofNullable(project.getUserData(PENDING_FINDING));
        }
    }

    /**
     * Takes the recorded target, clearing it: exactly one consumer gets
     * it, exactly once — whichever take runs first wins and every later
     * take finds nothing. The get and the clear are one critical section,
     * so two concurrent consumers can never both walk away with the
     * target.
     */
    static Optional<FindingTarget> takePendingFinding(Project project) {
        Objects.requireNonNull(project, "project");
        synchronized (project) {
            FindingTarget target = project.getUserData(PENDING_FINDING);
            if (target != null) {
                project.putUserData(PENDING_FINDING, null);
            }
            return Optional.ofNullable(target);
        }
    }

    /**
     * A gutter-navigation target (AC5): the finding's stable file path
     * and finding id — the value keys the tree's rows carry
     * ({@code ReviewTabModel.FileRow#path()},
     * {@code ReviewTabModel.FindingRow#id()}), so a snapshot rebuild
     * cannot invalidate a recorded target the way object identity would.
     */
    record FindingTarget(String filePath, String findingId) {

        FindingTarget {
            Objects.requireNonNull(filePath, "filePath");
            Objects.requireNonNull(findingId, "findingId");
            if (filePath.isBlank() || findingId.isBlank()) {
                throw new IllegalArgumentException(
                        "A finding target needs a file path and a finding id: '" + filePath + "'/'" + findingId + "'");
            }
        }

        /**
         * The target from caller input, or empty when the input is
         * malformed (null/blank path or id) — a malformed target is
         * simply never recorded, never an exception out of a click.
         */
        static Optional<FindingTarget> of(String filePath, String findingId) {
            if (filePath == null || filePath.isBlank() || findingId == null || findingId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new FindingTarget(filePath, findingId));
        }
    }

    /**
     * The production scope source: {@link ReviewTabScopes} over the
     * backend's own registry, through git services this mint owns (the
     * backend exposes no accessors for them) and closes once the mint
     * settles. It waits for the plugin's initialization, so a link that
     * beats it still mints bound instead of losing the binding to the
     * tab's later unbound mint.
     */
    private static CompletableFuture<ReviewScope> mintScope(Path checkout,
                                                             Optional<ManagedAgentSession> linkSession) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        return plugin.initialization().thenCompose(ignored -> {
            ReviewBackend backend = plugin.reviewBackend().orElse(null);
            if (backend == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("The Drydock review service did not start"));
            }
            WorktreeService worktrees = new WorktreeService();
            GitStatusService git = new GitStatusService();
            ReviewTabScopes scopes = new ReviewTabScopes(worktrees, git, backend.scopeRegistry(),
                    () -> ReviewTabVisibility.sessions(plugin));
            return scopes.resolve(checkout, linkSession).whenComplete((scope, error) -> {
                closeQuietly(worktrees, "worktree service");
                closeQuietly(git, "git service");
            });
        });
    }

    /** The cause behind a future's wrapper, never the wrapper itself. */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Closes a mint-owned service, isolated: one failure never skips the rest. */
    private static void closeQuietly(AutoCloseable service, String name) {
        try {
            service.close();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Closing the Changes link's " + name + " failed", e);
        }
    }

    /**
     * The production host calls: activate the dedicated Review window by its
     * pinned id — one content, so no content-manager selection exists — then
     * re-run its diff.
     */
    private static final class ProductionHost implements Host {

        @Override
        public void activate(Project project, String toolWindowId) {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId);
            if (toolWindow == null) {
                LOG.log(Level.WARNING, () -> "The '" + toolWindowId + "' tool window is not registered in "
                        + project.getName());
                return;
            }
            toolWindow.activate(null);
        }

        @Override
        public void saveDocuments(Project project) {
            ReviewDocumentSaves.saveOpenDocuments();
        }

        @Override
        public void reloadTab(Project project) {
            ReviewTabProvider.reloadFromChangesLink(project);
        }
    }

    /**
     * The production loading state: a backgroundable progress task — the
     * placeholder the click shows at once, whose body is the mint and
     * whose completion (on the EDT) opens the tab (AC18). The mint
     * therefore never runs on the EDT.
     */
    private static final class ProgressLoading implements Loading {

        @Override
        public void run(Project project, String text, Runnable background, Runnable done) {
            ProgressManager.getInstance().run(new Task.Backgroundable(project, text, true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    background.run();
                }

                @Override
                public void onFinished() {
                    done.run();
                }
            });
        }
    }

    /**
     * The production empty-review offer. The probe reads the review
     * backend's persisted stores (findings, tours) — in-memory maps loaded
     * when the backend started, so the read never blocks on disk. The
     * offer is a plain ok/cancel dialog; the launch reuses the task
     * window's Review flow ({@link WorktreeReview#reviewWithModelDialog}),
     * which owns its own background work, model pickers and in-flight
     * guard — this class only finds the checkout's main root and branch
     * for it, off the EDT.
     */
    private static final class PreparationOffers implements Preparation {

        @Override
        public boolean hasData(ReviewScope scope) {
            return DrydockPluginService.getInstance().reviewBackend()
                    .map(backend -> !backend.annotationStore().forScope(scope.id()).isEmpty()
                            || backend.tourStore().forScope(scope.id()).isPresent())
                    // No backend: the mint failed before any store existed —
                    // never offer a launch that cannot start.
                    .orElse(true);
        }

        @Override
        public boolean offer(Project project, Path checkout, ReviewScope scope) {
            if (project.isDisposed()) {
                return false;
            }
            return Messages.showOkCancelDialog(project,
                    "No review has been prepared for " + checkout.getFileName() + ".\n\n"
                            + "Run the review preparation? An agent reviews this checkout and "
                            + "posts findings to this window's Files tab, plus a guided tour in "
                            + "its Tour tab if you ask for one in the next dialog.",
                    "Drydock Review",
                    "Run preparation", "Cancel", AllIcons.General.QuestionDialog) == Messages.OK;
        }

        @Override
        public void launch(Project project, Path checkout, ReviewScope scope) {
            if (project.isDisposed()) {
                return;
            }
            Path target = scope.diffRoot();
            WorktreeService worktrees = new WorktreeService();
            worktrees.list(checkout)
                    .thenApply(listed -> {
                        WorktreeService.Worktree main = listed.stream()
                                .filter(WorktreeService.Worktree::mainCheckout).findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        checkout + " is not in a git repository"));
                        WorktreeService.Worktree own = listed.stream()
                                .filter(entry -> samePath(entry.path(), target)).findFirst()
                                .orElse(main);
                        // The task window's own branch fallback: a detached
                        // worktree is labelled by its directory name.
                        String branch = own.branch().orElse(String.valueOf(own.path().getFileName()));
                        return new String[] { main.path().toString(), own.path().toString(), branch };
                    })
                    .whenComplete((ignored, error) -> closeQuietly(worktrees, "worktree service"))
                    .thenAccept(rootAndBranch -> ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) {
                            return;
                        }
                        new WorktreeReview().reviewWithModelDialog(project,
                                Path.of(rootAndBranch[0]), Path.of(rootAndBranch[1]), rootAndBranch[2]);
                    }, ModalityState.nonModal()))
                    .exceptionally(error -> {
                        LOG.log(Level.WARNING,
                                "Could not resolve the checkout for the review preparation of " + target,
                                rootCause(error));
                        return null;
                    });
        }

        /** Real-path equality: macOS lists temporary directories as {@code /private/var/…}. */
        private static boolean samePath(Path a, Path b) {
            try {
                return a.toRealPath().equals(b.toRealPath());
            } catch (RuntimeException | java.io.IOException e) {
                return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
            }
        }
    }
}
