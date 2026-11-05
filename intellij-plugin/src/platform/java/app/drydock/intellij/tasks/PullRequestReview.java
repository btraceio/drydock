package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.WorktreeNaming;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.intellij.review.ReviewCheckouts;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.intellij.review.ReviewTabOpener;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.ReviewScope;
import app.drydock.review.tour.ReviewTour;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Opens a pull request for review in its own worktree, and the review
 * launch pieces the task window's Review flow ({@link WorktreeReview})
 * shares.
 *
 * <p>{@link #openInWorkbench} checks the PR out into its {@code pr-<n>}
 * worktree beside the repository's other worktrees ({@code gh pr checkout},
 * or the existing worktree as it stands) and opens that worktree's window
 * with its review workbench. No agent starts there: the workbench's Review
 * menu asks for one. {@link PrCheckoutService} works in a fresh detached
 * worktree and removes it again when {@code gh} fails, so the checkout never
 * touches the main checkout.</p>
 *
 * <p>The shared pieces: which agent a review launches
 * ({@link #selectReviewAgent}), the model dialog's answer
 * ({@link #awaitModelChoice}), the running review session to rejoin
 * ({@link #runningSessionFor}), the sphinx command sent into a running
 * session ({@link #submitSphinxPrompt}), and the review prompt
 * ({@link ReviewPrompt}), resolved before the agent spawns so it travels as
 * the launch's initial prompt.</p>
 */
public final class PullRequestReview {

    private static final Logger LOG = System.getLogger(PullRequestReview.class.getName());

    /** Above PrCheckoutService's five-minute gh checkout timeout, with room for cleanup. */
    private static final long CHECKOUT_AWAIT_TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(5) + 30;

    /** Materializations in flight, by {@code <root>#<number>}; a second click is refused. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public PullRequestReview() { }

    static Optional<AgentKind> selectReviewAgent(Optional<AgentKind> defaultAgent, Predicate<AgentKind> available) {
        if (defaultAgent.isPresent() && defaultAgent.get() != AgentKind.TERMINAL) {
            return defaultAgent;
        }
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            if (kind != AgentKind.TERMINAL && available.test(kind)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * The pull request's worktree at {@code base}: reused as it stands when
     * the directory exists, else {@code gh pr checkout} into it. Empty when
     * the checkout failed (reported to the user) or was interrupted.
     * Background thread.
     */
    private static Optional<Path> checkedOut(Project from, Path mainRoot, Path base, int number) {
        if (Files.isDirectory(base)) {
            return Optional.of(base);
        }
        try (PrCheckoutService checkout = new PrCheckoutService()) {
            return Optional.of(checkout.checkout(mainRoot, base, number)
                    .get(CHECKOUT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not check out PR #" + number + " of " + mainRoot, e);
            // Prefix only when the message does not already carry it. A
            // PrCheckoutException already reads "Could not check out PR #N:
            // <cause>", and re-prefixing that is what doubled the sentence;
            // but a bare TimeoutException, "gh is not installed" or a missing
            // git executable names no PR, and reporting those bare would
            // lose which checkout failed.
            String prefix = "Could not check out PR #" + number + ": ";
            String message = cause(e);
            report(from, message.startsWith(prefix) ? message : prefix + message);
            return Optional.empty();
        }
    }

    /**
     * Opens {@code pullRequest} in the review workbench, with no agent: the
     * PR is checked out into its {@code pr-<n>} worktree as a review does
     * (an existing one is reused as it stands), then the window that shows
     * the worktree -- opened as a project when none does -- opens its Review
     * tab. The worktree's own window, because a workbench reviews its own
     * project's checkout. The checkout is registered as opened for review
     * ({@link ReviewCheckouts}), which lets its Review tab load with no
     * session, and the open skips the empty-review preparation offer. Shares the review's in-flight guard: one
     * materialization per pull request at a time. EDT entry.
     */
    public void openInWorkbench(Project from, Path mainRoot, String repositoryName, OpenPullRequest pullRequest) {
        String key = mainRoot + "#" + pullRequest.number();
        if (!inFlight.add(key)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Checking out PR #" + pullRequest.number() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    Path base = WorktreeNaming.defaultDirectory(Path.of(System.getProperty("user.home")),
                            UserConfig.load().worktreesDirectory(), repositoryName,
                            PrCheckoutService.localBranchFor(pullRequest.number()));
                    Optional<Path> worktree = checkedOut(from, mainRoot, base, pullRequest.number());
                    if (worktree.isEmpty()) {
                        return;
                    }
                    Path canonical = RepositoryCatalog.canonicalize(worktree.get());
                    List<Project> open = Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                            .filter(project -> !project.isDisposed())
                            .toList();
                    Optional<Project> window = TaskNavigator.windowFor(open, Optional.empty(),
                            project -> Optional.ofNullable(project.getBasePath())
                                    .map(path -> RepositoryCatalog.canonicalize(Path.of(path))),
                            canonical);
                    // Before the window opens: its Review tab is shown only for a
                    // covered checkout, and no session covers this one.
                    ReviewCheckouts.register(window.map(project -> Path.of(project.getBasePath()))
                            .orElse(worktree.get()));
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Project target = window.filter(project -> !project.isDisposed())
                                .orElseGet(() -> TaskWindows.openAsProject(worktree.get()));
                        if (target == null || target.isDisposed()) {
                            return;
                        }
                        ProjectUtil.focusProjectWindow(target, true);
                        // The review is asked for from the workbench's Review menu, not offered on open.
                        ReviewTabOpener.openWithoutOffer(target);
                    }, ModalityState.nonModal());
                } finally {
                    inFlight.remove(key);
                }
            }
        });
    }

    /**
     * "Resolve Conflicts…" on a conflicting pull request: checks the PR out
     * into its {@code pr-<n>} worktree as {@link #openInWorkbench} does (an
     * existing one is reused as it stands), then opens the New task dialog
     * fixed to that worktree with the conflict-resolution prompt, where the
     * user picks the agent and can edit the prompt. The session then works
     * on the {@code pr-<n>} branch, which is how its row finds the PR again
     * and offers Done once it merges. Shares the in-flight guard. EDT entry.
     */
    public void resolveConflicts(Project from, Path mainRoot, String repositoryName, OpenPullRequest pullRequest) {
        String key = mainRoot + "#" + pullRequest.number();
        if (!inFlight.add(key)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Checking out PR #" + pullRequest.number() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    String branch = PrCheckoutService.localBranchFor(pullRequest.number());
                    Path base = WorktreeNaming.defaultDirectory(Path.of(System.getProperty("user.home")),
                            UserConfig.load().worktreesDirectory(), repositoryName, branch);
                    checkedOut(from, mainRoot, base, pullRequest.number()).ifPresent(worktree ->
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (!from.isDisposed()) {
                                    TaskLauncher.open(from, Optional.of(mainRoot),
                                            Optional.of(new NewTaskPlan.FixedWorktree(worktree, branch)),
                                            NewTaskPreset.resolveConflicts(pullRequest));
                                }
                            }, ModalityState.nonModal()));
                } finally {
                    inFlight.remove(key);
                }
            }
        });
    }

    /**
     * What the model dialog's answer contributes to the launch: {@code tiers}
     * is empty when no tier pickers were shown.
     */
    record ModelChoice(AgentKind agent, Optional<String> model, Optional<ModelTiers> tiers) {
    }

    /**
     * Waits for the dialog's answer. Outer empty: no review (dialog cancelled, failed, abandoned, or
     * never opened by {@code openDeadlineNanos}); inner value: what the dialog accepted.
     * Bounds only the dialog OPENING: once the EDT runnable has claimed, the user's think time is unbounded,
     * but {@code abandoned} (task cancelled / project disposed) still ends the wait.
     */
    static Optional<ModelChoice> awaitModelChoice(CompletableFuture<ModelChoice> picked,
                                                       AtomicBoolean claimed, BooleanSupplier abandoned,
                                                       LongSupplier nanoClock, long openDeadlineNanos,
                                                       long pollMillis) {
        while (true) {
            try {
                return Optional.of(picked.get(pollMillis, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                if (abandoned.getAsBoolean()) {
                    return giveUp(picked, claimed);
                }
                if (nanoClock.getAsLong() - openDeadlineNanos >= 0 && claimed.compareAndSet(false, true)) {
                    picked.cancel(false);
                    LOG.log(Level.WARNING, "The review model dialog did not open in time");
                    return Optional.empty();
                }
            } catch (CancellationException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return giveUp(picked, claimed);
            } catch (ExecutionException e) {
                LOG.log(Level.WARNING, "The review model dialog failed", e.getCause());
                return Optional.empty();
            }
        }
    }

    /**
     * The waiter stops waiting: takes the claim FIRST, so a runnable that has not run yet backs off
     * instead of opening a dialog whose answer would be dropped, then settles {@code picked}.
     */
    private static Optional<ModelChoice> giveUp(CompletableFuture<ModelChoice> picked,
                                                     AtomicBoolean claimed) {
        claimed.set(true);
        picked.cancel(false);
        return Optional.empty();
    }

    /**
     * Sends the sphinx command into a RUNNING session's console, under a
     * background progress; any outcome but a delivered prompt is shown in a
     * warning dialog. The same delivery the Ask-Agent-to-Review request
     * uses, without the review backend in between — sphinx is the harness's
     * business.
     */
    static void submitSphinxPrompt(Project from, ManagedAgentSession running, String prompt) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting sphinx review…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                PromptOutcome outcome = AgentExecutionRegistry.getInstance().submitPrompt(running.id(), prompt);
                if (outcome != PromptOutcome.DELIVERED) {
                    ApplicationManager.getApplication().invokeLater(
                            () -> Messages.showWarningDialog(from,
                                    "Could not send the sphinx review command to the session on "
                                            + running.workingDirectory() + " (" + outcome + ").",
                                    "Drydock"),
                            ModalityState.nonModal(), from.getDisposed());
                }
            }
        });
    }

    /**
     * The RUNNING session whose working directory is {@code worktree}, by
     * real path (macOS lists {@code /private/var/…} where a temporary
     * directory is {@code /var/…}), the same comparison the scope resolver
     * applies. A session on any other directory, or one that has ended, is
     * not a review to join. Pure lookup; never touches git or the EDT.
     */
    static Optional<ManagedAgentSession> runningSessionFor(List<ManagedAgentSession> sessions,
                                                           Path worktree) {
        Path real;
        try {
            real = worktree.toRealPath();
        } catch (IOException e) {
            return Optional.empty(); // the checkout to reuse is gone
        }
        for (ManagedAgentSession session : sessions) {
            if (SessionStatusFacet.of(session.status()) != SessionStatusFacet.RUNNING) {
                continue;
            }
            try {
                if (session.workingDirectory().toRealPath().equals(real)) {
                    return Optional.of(session);
                }
            } catch (IOException e) {
                continue; // a session whose directory is gone is not joinable
            }
        }
        return Optional.empty();
    }

    /**
     * The review's first message, and why there was none.
     *
     * <p>The resolver runs on the launch chain between minting the session
     * and spawning its agent (see {@code SessionDialogs.launchSession}), so
     * its result is the launch's initial prompt. {@link #resolve} never throws,
     * never completes exceptionally, and never blocks the launch indefinitely:
     * every failure is recorded as a {@code complaint} and yields an empty
     * prompt — a session without a review beats no session, and the human is
     * told what did not resolve once the session is up.</p>
     *
     * <p>Package-private for tests; see {@code PullRequestReviewTest}.</p>
     */
    static final class ReviewPrompt {

        /** Resolved without the session: the custom template needs only the PR number. */
        static ReviewPrompt fixed(String prompt) {
            ReviewPrompt p = new ReviewPrompt(ModelTiers.EMPTY, Optional.empty());
            p.needsTools = false;
            p.resolver = session -> CompletableFuture.completedFuture(Optional.of(prompt));
            return p;
        }

        /**
         * Drydock's review instruction with the launch's subagent model tiers,
         * resolved from the session's freshly bound scope. Every plugin-
         * service lookup is deferred to resolution, inside a lambda.
         */
        static ReviewPrompt drydockReview(AgentKind agent, ModelTiers tiers, Optional<String> sessionModel) {
            return drydockReview(agent, tiers, sessionModel,
                    () -> DrydockPluginService.getInstance().initialization(),
                    () -> DrydockPluginService.getInstance().reviewBackend(),
                    kind -> DrydockPluginService.getInstance().registry().supportsSubagents(kind),
                    command -> DrydockPluginService.getInstance().executor().execute(command));
        }

        /** As {@link #drydockReview(AgentKind)}, without tiers; kept for the tests. */
        static ReviewPrompt drydockReview(AgentKind agent) {
            return drydockReview(agent, ModelTiers.EMPTY, Optional.empty());
        }

        /**
         * As {@link #drydockReview(AgentKind, ModelTiers, Optional, Supplier,
         * Supplier, Predicate, Executor)}, without tiers; the shape the
         * backend-absence tests use.
         */
        static ReviewPrompt drydockReview(AgentKind agent,
                                          Supplier<? extends CompletableFuture<?>> initialization,
                                          Supplier<Optional<ReviewBackend>> backend,
                                          Predicate<AgentKind> supportsSubagents,
                                          Executor executor) {
            return drydockReview(agent, ModelTiers.EMPTY, Optional.empty(), initialization, backend,
                    supportsSubagents, executor);
        }

        /**
         * As {@link #drydockReview(AgentKind, ModelTiers, Optional)}, with the
         * plugin service's parts supplied; package-private for tests.
         */
        static ReviewPrompt drydockReview(AgentKind agent, ModelTiers tiers, Optional<String> sessionModel,
                                          Supplier<? extends CompletableFuture<?>> initialization,
                                          Supplier<Optional<ReviewBackend>> backend,
                                          Predicate<AgentKind> supportsSubagents,
                                          Executor executor) {
            ReviewPrompt p = new ReviewPrompt(tiers, sessionModel);
            p.needsTools = true;
            p.resolver = session -> CompletableFuture.supplyAsync(
                    () -> p.drydockInstruction(agent, session, initialization, backend, supportsSubagents), executor);
            return p;
        }

        private Function<ManagedAgentSession, CompletableFuture<Optional<String>>> resolver;
        /** Whether the prompt (when there is one) addresses Drydock's review tools. */
        private boolean needsTools;
        /** What Drydock's instruction asks for; a fixed prompt ignores it. */
        private volatile ReviewTour.Kind kind = ReviewTour.Kind.TOUR;
        /** The launch's subagent model tiers, spent by the review instruction's dispatch rules. */
        private final ModelTiers tiers;
        /** The launch's starting model, which an empty tier resolves to in those rules. */
        private final Optional<String> sessionModel;
        /** Why no Drydock prompt was resolved; null when one was (or a custom one rides instead). */
        private volatile ReviewRequest.Outcome complaint;

        private ReviewPrompt(ModelTiers tiers, Optional<String> sessionModel) {
            this.tiers = Objects.requireNonNull(tiers, "tiers");
            this.sessionModel = Objects.requireNonNull(sessionModel, "sessionModel");
        }

        /**
         * See {@code SessionDialogs.launchSession}'s {@code prompt} parameter. Blocks while resolving.
         * Never throws and never completes exceptionally: a failure is a complaint and an empty prompt.
         */
        /** This prompt, asking for {@code requested}: the educational tour (the default) or the section-based review. */
        ReviewPrompt asking(ReviewTour.Kind requested) {
            kind = Objects.requireNonNull(requested, "requested");
            return this;
        }

        /** Drydock's instruction asking for {@code kind} on {@code scopeId} (pure). */
        static String instructionFor(ReviewTour.Kind kind, String scopeId, boolean subagents, ModelTiers tiers,
                                     Optional<String> sessionModel) {
            return kind == ReviewTour.Kind.SECTIONS
                    ? ReviewInstructions.forSections(scopeId, subagents, tiers, sessionModel)
                    : ReviewInstructions.forScope(scopeId, subagents, tiers, sessionModel);
        }

        CompletableFuture<Optional<String>> resolve(ManagedAgentSession session) {
            CompletableFuture<Optional<String>> resolving;
            try {
                resolving = resolver.apply(session);
            } catch (RuntimeException e) {
                // e.g. the plugin service or its executor is gone mid-launch
                return CompletableFuture.completedFuture(noPrompt(new ReviewRequest.Failed(PullRequestReview.cause(e))));
            }
            return resolving.exceptionally(e -> noPrompt(new ReviewRequest.Failed(PullRequestReview.cause(e))));
        }

        /** What to tell the human once the session is up, or empty when the review is properly armed. */
        Optional<String> userMessage(String taskName, boolean holdsAccess) {
            if (complaint != null) {
                return ReviewRequest.message(taskName, complaint);
            }
            // A Drydock prompt names review tools the session must hold; one
            // launched without them runs the instruction into a wall. (A
            // fixed prompt, the sphinx command, is the harness's business —
            // no check.)
            if (needsTools && !holdsAccess) {
                return ReviewRequest.message(taskName, new ReviewRequest.NoTools());
            }
            return Optional.empty();
        }

        /**
         * Off the EDT: the backend's scope for {@code session}, as the agent's first message.
         * Never throws a RuntimeException.
         */
        private Optional<String> drydockInstruction(AgentKind agent, ManagedAgentSession session,
                                                    Supplier<? extends CompletableFuture<?>> initialization,
                                                    Supplier<Optional<ReviewBackend>> backend,
                                                    Predicate<AgentKind> supportsSubagents) {
            try {
                initialization.get().get(ReviewRequest.INIT_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return noPrompt(new ReviewRequest.NotReady());
            } catch (TimeoutException | ExecutionException | RuntimeException e) {
                return noPrompt(new ReviewRequest.NotReady());
            }
            Optional<ReviewBackend> found;
            try {
                found = backend.get();
            } catch (RuntimeException e) {
                return noPrompt(new ReviewRequest.NotReady());
            }
            return found.filter(ReviewBackend::ready)
                    .map(b -> instruction(agent, session, b, supportsSubagents))
                    .orElseGet(() -> noPrompt(new ReviewRequest.NotReady()));
        }

        private Optional<String> instruction(AgentKind agent, ManagedAgentSession session, ReviewBackend backend,
                                             Predicate<AgentKind> supportsSubagents) {
            try {
                ReviewScope scope = backend.scopeResolver().resolve(session)
                        .get(ReviewRequest.SCOPE_WAIT_SECONDS, TimeUnit.SECONDS);
                return Optional.of(instructionFor(kind, scope.id(), supportsSubagents.test(agent), tiers,
                        sessionModel));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return noPrompt(new ReviewRequest.Failed(PullRequestReview.cause(e)));
            } catch (TimeoutException | ExecutionException | RuntimeException e) {
                return noPrompt(new ReviewRequest.Failed(PullRequestReview.cause(e)));
            }
        }

        /** Records why there is no prompt and answers with none. */
        private Optional<String> noPrompt(ReviewRequest.Outcome outcome) {
            complaint = outcome;
            return Optional.empty();
        }
    }

    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }

    private static String cause(Throwable failure) {
        Throwable at = failure;
        while (at.getCause() != null && at.getCause() != at) {
            at = at.getCause();
        }
        return at.getMessage() != null && !at.getMessage().isBlank() ? at.getMessage() : at.toString();
    }
}
