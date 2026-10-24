package app.drydock.intellij.review;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.review.ReviewScope;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
 * Only when that mint settles does the open path resolve the host tool
 * window, activate it, and select the tab. That ordering is what makes the
 * binding flow: the tab's own load (T13's provider) resolves the same
 * checkout read-mostly, so by the time it runs the registry already holds
 * the link-bound scope and the provider hands it out unchanged instead of
 * minting unbound (AC19). Once the tab is selected, an already-loaded tab
 * re-runs its diff (spec "Refresh": the Changes link re-runs the diff); a
 * tab whose content was only just created is computing its first diff
 * already.</p>
 *
 * <p><strong>The host is resolved, never named</strong> (AC3,
 * {@code kb/find-review-tab-host-varies}): with the Commit tool window
 * disabled (modal commit) the same tab lives in the Version Control window,
 * so the open path asks {@code getToolWindowIdFor} and never hard-codes
 * any tool window id.</p>
 *
 * <p><strong>Threading.</strong> {@link #open} is called on the EDT; the
 * mint runs inside the visible progress task's background body, never on
 * the EDT and never in a tree builder; the host calls land on the EDT
 * through the task's own completion.</p>
 */
public final class ReviewTabOpener {

    private static final Logger LOG = System.getLogger(ReviewTabOpener.class.getName());

    /**
     * The open path's platform calls (AC3): the host tool window is
     * resolved from the tab name, never hard-coded. Faked in tests.
     */
    interface Host {
        /** {@link ChangesViewContentManager#getToolWindowIdFor}: the id of the tool window hosting the tab. */
        String toolWindowIdFor(Project project, String tabName);

        /** Activates the tool window resolved by {@link #toolWindowIdFor}. */
        void activate(Project project, String toolWindowId);

        /** Selects the tab in its host's content manager. */
        void selectContent(Project project, String tabName);

        /** Q9: saves the open documents ({@link ReviewDocumentSaves#saveOpenDocuments}); EDT, write intent. */
        void saveDocuments(Project project);

        /** Re-runs the diff of the live tab's scope ({@code ReviewTabProvider}); EDT, after the selection. */
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
            new ProductionHost(), ReviewTabOpener::mintScope, new ProgressLoading());

    private final Host host;
    private final ScopeSource scopes;
    private final Loading loading;

    ReviewTabOpener(Host host, ScopeSource scopes, Loading loading) {
        this.host = Objects.requireNonNull(host, "host");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.loading = Objects.requireNonNull(loading, "loading");
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
        // before any background work starts, and before the host is
        // resolved.
        loading.run(project, ReviewTabStatus.LOADING.message(),
                () -> mint(project, linkSession),
                () -> selectTab(project));
    }

    /**
     * The background half: the mint of the window's checkout scope, bound
     * to the link session (T8, Q7). The minted value itself is the tab's
     * to read — this path only establishes it in the registry.
     */
    private void mint(Project project, Optional<ManagedAgentSession> linkSession) {
        Path checkout = checkoutOf(project);
        try {
            scopes.resolve(checkout, linkSession).join();
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

    /** The EDT half: resolve the host, activate it, select the tab (AC3), then re-run its diff. */
    private void selectTab(Project project) {
        if (project.isDisposed()) {
            return;
        }
        String toolWindowId = host.toolWindowIdFor(project, ReviewTabProvider.TAB_NAME);
        if (toolWindowId == null || toolWindowId.isBlank()) {
            LOG.log(Level.WARNING, () -> "No tool window hosts the '" + ReviewTabProvider.TAB_NAME
                    + "' tab in " + project.getName());
            return;
        }
        host.activate(project, toolWindowId);
        host.selectContent(project, ReviewTabProvider.TAB_NAME);
        host.reloadTab(project);
    }

    /**
     * The project's checkout — the window's own scope, which on both strip
     * paths is the linked session's (AC18).
     */
    private static Path checkoutOf(Project project) {
        String base = project.getBasePath();
        if (base == null) {
            throw new IllegalStateException(project + " has no base path");
        }
        return Path.of(base);
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

    /** The production host calls (AC3): resolved, activated, selected — the host is never named. */
    private static final class ProductionHost implements Host {

        @Override
        public String toolWindowIdFor(Project project, String tabName) {
            return ChangesViewContentManager.getToolWindowIdFor(project, tabName);
        }

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
        public void selectContent(Project project, String tabName) {
            ChangesViewContentManager.getInstance(project).selectContent(tabName);
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
}
