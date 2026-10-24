package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.intellij.review.ReviewTabOpener;
import app.drydock.intellij.settings.PullRequestReviewPrompt;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.ReviewScope;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Reviews an open pull request, end to end: the {@code pr-<n>} worktree
 * beside the repository's other worktrees, {@code gh pr checkout} into it,
 * a new agent session there, and the review prompt.
 *
 * <p>A re-review reuses what the first one built: a RUNNING review session
 * for the same worktree is joined (its console moves into this window,
 * nothing relaunched), and an ended review's worktree is re-entered as it
 * stands -- a fresh checkout would refuse the existing directory and the
 * scope resolver would refuse a held checkout.</p>
 *
 * <p>The console is a tab in the window the Review gesture came from — the
 * same default the New task flow uses ({@code OpenIn.THIS_WINDOW}); the
 * worktree's own project window is an explicit choice there, and none is
 * offered here. Hosting also keeps the review out of a freshly opened
 * window, where the IDE's README-on-first-open and the project import fight
 * the console for focus and can leave the harness unreachable behind them
 * (seen in the 2026-10-23 idea.log: two "Cannot focus editor" warnings with
 * the README composite current, and the review prompt typed into the PTY
 * while the harness was still booting — written, never read).</p>
 *
 * <p>The prompt is resolved BEFORE the agent spawns and travels as the
 * launch's initial prompt — the same channel a task typed into the New task
 * dialog uses, which the harness consumes whenever it is ready. A prompt
 * typed into the console after a spawn, by contrast, races the harness's
 * boot and can be swallowed whole. Two sources, in order: a custom
 * pull-request review prompt configured in Settings › Tools › Drydock, with
 * {@code $PR$} replaced by the pull-request number — the harness's business
 * to understand, and Drydock's review tools are not involved at all;
 * otherwise Drydock's own review flow — the session's review scope is
 * resolved and bound while the session is minted (it does not exist before,
 * and its agent may already be reading the prompt after), and
 * {@code ReviewInstructions#forScope} for it is the first message. When the
 * scope or the review service cannot be read, the session still starts, and
 * the human is told why there is no review running in it.</p>
 *
 * <p>The session runs in WRITES mode in both cases: a reviewer's agent
 * builds, runs tests, and dispatches subagents, and a read-only sandbox
 * (Codex) or plan mode (Claude) would block exactly that. The checkout
 * itself never touches the main checkout — {@link PrCheckoutService} works
 * in a fresh detached worktree and removes it again when {@code gh} fails.</p>
 */
public final class PullRequestReview {

    private static final Logger LOG = System.getLogger(PullRequestReview.class.getName());

    /** Bounded waits, mirroring the New task flow's generous launch bound. */
    private static final long LAUNCH_TIMEOUT_SECONDS = 60;

    /** How often the review waits for the model dialog's answer re-checks for cancellation/disposal. */
    private static final long DIALOG_POLL_MILLIS = 200;

    /**
     * What the launch future can legitimately take before the agent spawns:
     * the launch itself plus the prompt resolver's own bounds — scope
     * resolution (init wait + scope wait) runs INSIDE the launch chain, so a
     * slow-but-healthy review backend would otherwise consume the launch
     * budget, the flow would be reported as timed out while still succeeding,
     * and the resolver's failure message would never be evaluated.
     */
    private static final long START_TIMEOUT_SECONDS =
            LAUNCH_TIMEOUT_SECONDS + ReviewRequest.INIT_WAIT_SECONDS + ReviewRequest.SCOPE_WAIT_SECONDS;

    /** Above PrCheckoutService's five-minute gh checkout timeout, with room for cleanup. */
    private static final long CHECKOUT_AWAIT_TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(5) + 30;

    /** Bound on removing an abandoned review worktree, as TaskActions and SessionDone use. */
    private static final long REMOVE_TIMEOUT_SECONDS = 120;

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

    /** EDT entry: refuses a second materialization of the same pull request. */
    public void review(Project from, Path mainRoot, String repositoryName, OpenPullRequest pullRequest) {
        review(from, mainRoot, repositoryName, pullRequest, false);
    }

    /**
     * As {@link #review}, but first asks for the starting model and the
     * subagent model tiers in a small dialog ({@link ReviewModelDialog},
     * seeded from the repository's last-used model and tiers for the resolved
     * harness) and launches with what it picks; cancelling the dialog cancels
     * the whole review.
     */
    public void reviewWithModelDialog(Project from, Path mainRoot, String repositoryName,
                                      OpenPullRequest pullRequest) {
        review(from, mainRoot, repositoryName, pullRequest, true);
    }

    private void review(Project from, Path mainRoot, String repositoryName, OpenPullRequest pullRequest,
                        boolean pickModel) {
        String key = mainRoot + "#" + pullRequest.number();
        if (!inFlight.add(key)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Preparing review of PR #" + pullRequest.number() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    materialize(from, mainRoot, repositoryName, pullRequest, pickModel, indicator);
                } finally {
                    inFlight.remove(key);
                }
            }
        });
    }

    private void materialize(Project from, Path mainRoot, String repositoryName, OpenPullRequest pullRequest,
                             boolean pickModel, ProgressIndicator indicator) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        Optional<AgentKind> agent;
        Optional<String> rememberedModel;
        ModelTiers rememberedTiers;
        Optional<String> customTemplate;
        try {
            plugin.initialization().get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // A custom template replaces Drydock's review instruction, and with
            // it the only prompt that spends tiers: read it before the dialog,
            // which then asks for no tiers.
            customTemplate = PullRequestReviewPrompt.load();
            // The project's own record holds its last-used agent (as New task does).
            Repository local = DrydockStartupActivity.repositories(from).repositoryFor(mainRoot)
                    .get(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Optional<AgentKind> repoLastUsed = local == null ? Optional.empty()
                    : local.settings().lastUsedAgent();
            agent = selectReviewAgent(SessionDialogs.defaultAgent(plugin.registry(), repoLastUsed),
                    kind -> plugin.registry().isAvailable(kind));
            // The remembered model and tiers are keyed per harness, so they are
            // read for the harness the review will actually launch.
            rememberedModel = agent.flatMap(kind -> local == null ? Optional.empty()
                    : local.settings().lastUsedModel(kind));
            rememberedTiers = agent.map(kind -> local == null ? ModelTiers.EMPTY
                    : local.settings().lastUsedTiers(kind).orElse(ModelTiers.EMPTY)).orElse(ModelTiers.EMPTY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare the review of PR #" + pullRequest.number(), e);
            report(from, "Drydock could not prepare the review: " + e.getMessage());
            return;
        }
        if (agent.isEmpty()) {
            report(from, "No agent CLI is available, so PR #" + pullRequest.number() + " cannot be reviewed.");
            return;
        }

        // The dialog runs on the EDT while this background task waits for its
        // answer, so the in-flight guard still covers the whole materialization:
        // a second Review click is refused until the first one has either
        // launched or been cancelled. Outer empty = do not review at all.
        boolean tiersApply = plugin.registry().supportsSubagents(agent.get()) && customTemplate.isEmpty();
        Optional<ModelChoice> choice = pickModel
                ? chooseModel(from, agent.get(), rememberedModel, rememberedTiers, tiersApply, plugin, indicator)
                : Optional.of(new ModelChoice(rememberedModel, Optional.empty()));
        if (choice.isEmpty()) {
            return; // cancelled, abandoned, failed, or the dialog never opened: no checkout, no launch
        }
        Optional<String> model = choice.get().model();
        // Recorded only when the dialog asked for them; the prompt spends the
        // remembered tiers otherwise (the quick Review), which a launch that
        // asked for none leaves stored as they were.
        Optional<ModelTiers> recordedTiers = choice.get().tiers();
        ModelTiers promptTiers = recordedTiers.orElse(rememberedTiers);
        Optional<String> customPrompt = customTemplate.map(template ->
                PullRequestReviewPrompt.render(template, pullRequest.number()));

        Path base = WorktreeNaming.defaultDirectory(Path.of(System.getProperty("user.home")),
                UserConfig.load().worktreesDirectory(), repositoryName,
                PrCheckoutService.localBranchFor(pullRequest.number()));

        // A second "Review…" click reuses what the first one built. A
        // checkout refuses to touch an existing directory, and the scope
        // resolver refuses a checkout held by a RUNNING session, so without
        // this the re-click was a guaranteed error dialog. A running review
        // session is joined (its window and console focused, nothing
        // re-launched); a worktree whose review has ended is re-entered as
        // it stands -- the resolver rebinds its scope to the new session.
        Optional<ManagedAgentSession> running = runningSessionFor(plugin.stateStore().state().sessions(),
                base);
        if (running.isPresent()) {
            joinRunningReview(from, running.get());
            return;
        }

        Path worktree = base;
        if (!Files.isDirectory(base)) {
            try (PrCheckoutService checkout = new PrCheckoutService()) {
                worktree = checkout.checkout(mainRoot, base, pullRequest.number())
                        .get(CHECKOUT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not check out PR #" + pullRequest.number() + " of " + mainRoot, e);
                report(from, "Could not check out PR #" + pullRequest.number() + ": " + cause(e));
                return;
            }
        }

        Path reused = worktree;
        // No expiry on the window: openAndLaunch must run even when `from` is gone, to
        // clean up the worktree it would otherwise leave behind.
        ApplicationManager.getApplication().invokeLater(() -> openAndLaunch(from, mainRoot, reused,
                pullRequest, agent.get(), model, promptTiers, recordedTiers, customPrompt), ModalityState.nonModal());
    }

    /**
     * What the model dialog's answer contributes to the launch: {@code tiers}
     * is empty when no tier pickers were shown.
     */
    record ModelChoice(Optional<String> model, Optional<ModelTiers> tiers) {
    }

    /** Shows the review model dialog on the EDT and waits for its answer; empty means do not review. */
    private Optional<ModelChoice> chooseModel(Project from, AgentKind kind, Optional<String> rememberedModel,
                                              ModelTiers rememberedTiers, boolean tiersApply,
                                              DrydockPluginService plugin, ProgressIndicator indicator) {
        CompletableFuture<ModelChoice> picked = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!claimed.compareAndSet(false, true)) {
                return; // the waiter gave up (abandoned, interrupted, or deadline) before the dialog could open
            }
            try {
                if (picked.isDone() || from.isDisposed()) {
                    return; // defensive: nobody is waiting for an answer any more
                }
                ReviewModelDialog dialog = new ReviewModelDialog(from, kind, rememberedModel, rememberedTiers,
                        plugin.registry()::modelSuggestions, tiersApply);
                boolean delivered = dialog.showAndGet()
                        ? picked.complete(new ModelChoice(dialog.acceptedModel(), dialog.acceptedTiers()))
                        : picked.cancel(false);
                if (!delivered) {
                    LOG.log(Level.INFO, "The review model dialog was answered after the review was abandoned");
                }
            } catch (RuntimeException e) {
                picked.completeExceptionally(e);
            } finally {
                picked.cancel(false); // no-op once settled; settles every other exit
            }
        }, ModalityState.nonModal());
        long openDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_TIMEOUT_SECONDS);
        return awaitModelChoice(picked, claimed, () -> indicator.isCanceled() || from.isDisposed(),
                System::nanoTime, openDeadline, DIALOG_POLL_MILLIS);
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

    /** EDT: moves the running review's console into this window; nothing is relaunched. */
    private void joinRunningReview(Project from, ManagedAgentSession running) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (from.isDisposed()) {
                return; // the review keeps running in its hosting window; nothing to join here
            }
            AgentConsoles.showHere(from, running.id());
        }, ModalityState.nonModal(), from.getDisposed());
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
     * EDT: hosts the review console as a tab in this window, then starts the session with its prompt:
     * {@code customPrompt} when a custom template is configured, otherwise Drydock's review instruction
     * spending {@code promptTiers}. {@code recordedTiers} is what the launch records (empty: nothing).
     */
    private void openAndLaunch(Project from, Path mainRoot, Path worktree, OpenPullRequest pullRequest,
                               AgentKind agent, Optional<String> model, ModelTiers promptTiers,
                               Optional<ModelTiers> recordedTiers, Optional<String> customPrompt) {
        if (from.isDisposed()) {
            // The window the gesture came from is gone: nothing will host the review, so
            // its freshly checked-out worktree must not stay behind as an orphan.
            LOG.log(Level.INFO, "The window was closed while PR #{0} was being checked out; removing {1}",
                    pullRequest.number(), worktree);
            abandon(mainRoot, worktree, pullRequest.number());
            return;
        }
        String displayName = "Review #" + pullRequest.number() + " " + pullRequest.title();
        ReviewPrompt prompt = customPrompt.map(ReviewPrompt::fixed)
                .orElseGet(() -> ReviewPrompt.drydockReview(agent, promptTiers, model));
        var launch = SessionDialogs.launchSession(from, worktree, mainRoot, agent, displayName,
                SessionMode.WRITES, prompt::resolve, false, model, recordedTiers);
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Starting review of PR #" + pullRequest.number() + "…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ManagedAgentSession started;
                try {
                    started = launch.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    SessionDialogs.reportLaunchFailure(from, "start the review session", e);
                    return;
                } catch (TimeoutException e) {
                    LOG.log(Level.WARNING, "The review session did not start within {0}s", START_TIMEOUT_SECONDS);
                    return;
                }
                AgentConsoles.focusConsoleLater(from, started.id());
                // The prompt rode on the launch; only its absence needs a word.
                Optional<String> absence = prompt.userMessage(displayName, holdsAccess(started));
                absence.ifPresent(message ->
                        ApplicationManager.getApplication().invokeLater(
                                () -> Messages.showWarningDialog(from, message, "Drydock"),
                                ModalityState.nonModal(), from.getDisposed()));
                if (customPrompt.isEmpty() && absence.isEmpty()) {
                    // The review is armed: the Drydock instruction rode the
                    // launch and the session holds the review tools. Its
                    // console's window's Review tab is where the human watches
                    // it, through the same sanctioned opener the Changes link
                    // uses (placeholder, background mint, host resolved). A
                    // custom prompt bypasses Drydock's review tools entirely,
                    // so there is nothing for the tab to show.
                    ApplicationManager.getApplication().invokeLater(
                            () -> ReviewTabOpener.open(from, Optional.of(started)),
                            ModalityState.nonModal(), from.getDisposed());
                }
            }
        });
    }

    /** Whether the started session holds the review backend's MCP access — false when the backend is absent. */
    private static boolean holdsAccess(ManagedAgentSession started) {
        return DrydockPluginService.getInstance().reviewBackend()
                .map(backend -> backend.holdsAccess(started.id())).orElse(false);
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
            // custom prompt is the harness's business — no check.)
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
                return Optional.of(ReviewInstructions.forScope(scope.id(), supportsSubagents.test(agent),
                        tiers, sessionModel));
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

    /** Removes the unused review worktree off the EDT; tells the user when it stays. Never force-removes. */
    private static void abandon(Path mainRoot, Path worktree, int number) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (WorktreeService worktrees = new WorktreeService()) {
                // Non-forced: local changes are never discarded. The pr-<n> branch is kept.
                worktrees.remove(mainRoot, worktree, Optional.empty())
                        .get(REMOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                LOG.log(Level.INFO, "Removed the unused review worktree {0}", worktree);
                // Other windows' task trees may still list it as an idle row.
                DrydockSessionsListener.publishChanged();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                leftBehind(worktree, number, "interrupted");
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not remove the unused review worktree " + worktree, e);
                leftBehind(worktree, number, cause(e));
            }
        });
    }

    /** App-level error dialog: the source project is gone, so it has no project to expire with. */
    private static void leftBehind(Path worktree, int number, String why) {
        String message = "The window was closed while PR #" + number + " was being checked out, and its"
                + " worktree could not be removed (" + why + "):\n" + worktree
                + "\nRemove it from the task tree's worktree list, or with `git worktree remove`,"
                + " before reviewing the PR again.";
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog((Project) null, message, "Drydock"), ModalityState.nonModal());
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
